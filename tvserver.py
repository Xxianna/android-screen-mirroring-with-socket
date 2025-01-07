import socket
import subprocess
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from screeninfo import get_monitors
import threading
import time

def get_screen_resolution():
    monitors = get_monitors()
    if not monitors:
        raise Exception("No monitors found!")
    # 获取主显示器的分辨率
    primary_monitor = monitors[0]
    return primary_monitor.width, primary_monitor.height

class StreamPlayer:
    def __init__(self):
        self.ffplay_process = None
        self.client_socket = None
        self.is_playing = False

    def start_streaming(self, host, port, frame_rate, zoomfitbig):
        # 如果已经在播放，先停止当前的播放
        if self.is_playing:
            self.stop_streaming()

        # 获取当前屏幕的分辨率
        screen_width, screen_height = get_screen_resolution()
        print(f"Screen Resolution: {screen_width}x{screen_height}")

        # 设置窗口的最大宽度和高度（根据屏幕分辨率动态调整）
        max_width = screen_width  # 最大宽度为屏幕宽度
        max_height = screen_height  # 最大高度为屏幕高度

        # 连接到服务器
        self.client_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.client_socket.connect((host, port))
        print(f"Connected to {host}:{port}")

        # 根据 zoomfitbig 参数设置不同的缩放逻辑
        if zoomfitbig == "False" or zoomfitbig == False:
            # 不放大，保持原始比例，限制窗口大小
            scale_filter = f'scale={max_width}:{max_height}:force_original_aspect_ratio=decrease'
            self.ffplay_process = subprocess.Popen([
                'ffplay', 
                '-framerate', str(frame_rate), 
                '-i', '-', 
                # '-vf', f'scale={max_width}:{max_height}:force_original_aspect_ratio=decrease,transpose=0', 
                '-vf', scale_filter,
                '-x', str(max_width), 
                '-y', str(max_height)
            ], stdin=subprocess.PIPE)
        else:
            # 等比例放大，直到宽度大于屏幕且高度等于屏幕，或者宽度等于屏幕且高度大于屏幕
            scale_filter = f'scale={max_width}:{max_height}:force_original_aspect_ratio=increase'
            # 启动ffplay进程进行实时播放，并限制窗口大小
            self.ffplay_process = subprocess.Popen([
                'ffplay', 
                '-framerate', str(frame_rate), 
                '-i', '-', 
                # '-vf', f'scale={max_width}:{max_height}:force_original_aspect_ratio=decrease,transpose=0', 
                '-vf', scale_filter,
            ], stdin=subprocess.PIPE)

        self.is_playing = True

        # 启动一个线程来处理数据接收和播放
        threading.Thread(target=self._stream_data, daemon=True).start()

    def _stream_data(self):
        try:
            while self.is_playing:
                data = self.client_socket.recv(1024)
                if not data:
                    break
                # 将接收到的数据发送到ffplay的标准输入
                self.ffplay_process.stdin.write(data)
        except Exception as e:
            print(f"Streaming error: {e}")
        finally:
            self.stop_streaming()

    def stop_streaming(self):
        if self.is_playing:
            self.is_playing = False
            if self.ffplay_process:
                self.ffplay_process.stdin.close()
                self.ffplay_process.terminate()
                self.ffplay_process.wait()
            if self.client_socket:
                self.client_socket.close()
            print("Streaming stopped.")

class MyHTTPRequestHandler(BaseHTTPRequestHandler):
    frame_rate = 30  # 默认帧率
    start_streaming = False  # 是否开始流媒体播放
    player = StreamPlayer()
    socketport = 57683
    zoomfitbig = "False"

    def do_GET(self):
        if self.path == '/mytvtvtv/':
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'OK')
        else:
            self.send_error(404, "Not Found")

    def do_POST(self):
        if self.path == '/mytvtvtvconnect':
            content_length = int(self.headers['Content-Length'])
            post_data = self.rfile.read(content_length)
            try:
                data = json.loads(post_data)
                if 'frameRate' in data:
                    self.frame_rate = data['frameRate']
                    self.socketport = int(data['port'])
                    self.zoomfitbig = data['zoomFit']
                    print(f"Frame Rate: {self.frame_rate} ZoomFitBig: {self.zoomfitbig}")
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(b'Frame rate updated')

                    self.player.stop_streaming()  # 停止当前的播放

                    # 获取客户端的IP地址
                    client_ip = self.client_address[0]
                    print(f"Client IP: {client_ip}")

                    # 触发延迟启动流媒体播放
                    threading.Thread(target=self.delayed_start_streaming, args=(client_ip,)).start()
                else:
                    self.send_error(400, "Bad Request: frameRate not found")
            except json.JSONDecodeError:
                self.send_error(400, "Bad Request: Invalid JSON")
        else:
            self.send_error(404, "Not Found")

    def delayed_start_streaming(self, client_ip):
        # 等待5秒
        print("Starting streaming after 5 seconds delay...")
        time.sleep(5)
        self.start_streaming = True
        self.player.start_streaming(client_ip, self.socketport, self.frame_rate, self.zoomfitbig)

def start_http_server():
    server_address = ('', 57682)
    httpd = HTTPServer(server_address, MyHTTPRequestHandler)
    print(f"Starting HTTP server on port 57682...")
    httpd.serve_forever()

def main():
    # 获取当前屏幕的分辨率
    screen_width, screen_height = get_screen_resolution()
    print(f"Screen Resolution: {screen_width}x{screen_height}")

    # 设置窗口的最大宽度和高度（根据屏幕分辨率动态调整）
    max_width = screen_width  # 最大宽度为屏幕宽度
    max_height = screen_height  # 最大高度为屏幕高度

    # 启动HTTP服务器
    http_thread = threading.Thread(target=start_http_server)
    http_thread.daemon = True
    http_thread.start()

    # 循环等待
    while True:
        time.sleep(1)

if __name__ == '__main__':
    main()