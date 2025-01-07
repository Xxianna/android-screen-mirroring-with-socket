package com.xxianna27.sockettvserver;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StrictMode;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;

import androidx.core.app.ActivityCompat;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

public class ScreenCaptureService extends Service {

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private Handler handler = new Handler(Looper.getMainLooper());

    private MediaCodec mediaCodec;
    private Surface inputSurface;

    private ServerSocket serverSocket;
    private Socket clientSocket;
    private OutputStream outputStream;
    private ByteBuffer sps;
    private ByteBuffer pps;
    int frameRateint;
    long bitrateint;
    String encodingFormat;
    int portint;

    int screenWidth = 0;
    int screenHeight = 0;
    int screenDensity = 0;

    //音频
    private MediaCodec audioCodec; // 音频编码器
    private AudioRecord audioRecord; // 音频录制器
    private int audioSampleRate = 48000;//音频采样率
    private int channelConfig = AudioFormat.CHANNEL_IN_MONO; //单声道
    private int audioFormat = AudioFormat.ENCODING_PCM_16BIT;//pcm 16bit
    private int audioMinBufferSize;// 录音最小buffer
    private String audioEncodingFormat = MediaFormat.MIMETYPE_AUDIO_AAC; // 音频编码格式，这里使用AAC
    private int audioBitRate = 128000; // 音频码率
    private long audioPresentationTimeUs = 0; // 音频时间戳
    private DatagramSocket rtpSocket;
    private InetAddress rtpAddress;
    private int rtpPort = 5004;
    private byte[] audioBuffer;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundService();
    }

    @SuppressLint("ForegroundServiceType")
    private void startForegroundService() {
        NotificationChannel channel = new NotificationChannel(
                "ScreenCaptureService",
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, "ScreenCaptureService")
                .setContentTitle("Screen Capture Service")
                .setContentText("Screen capture is active")
                .setSmallIcon(R.drawable.ic_notification)
                .build();

        // 指定 foregroundServiceType
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    private long parseBitrate(String bitrateStr) {
        if (bitrateStr.endsWith("k")) {
            return Long.parseLong(bitrateStr.replace("k", "")) * 1024; // 转换为以 bps 为单位
        } else if (bitrateStr.endsWith("m")) {
            return Long.parseLong(bitrateStr.replace("m", "")) * 1024 * 1024; // 转换为以 bps 为单位
        } else {
            return Long.parseLong(bitrateStr); // 默认单位为 bps
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (action != null && action.equals("init_service")) {
                int resultCode = intent.getIntExtra("resultCode", -1);
                Intent data = intent.getParcelableExtra("data");
                String frameRate = intent.getStringExtra("frameRate");
                String bitrate = intent.getStringExtra("bitrate");
                encodingFormat = intent.getStringExtra("encodingFormat");
                String port = intent.getStringExtra("port");
                if (port != null) {
                    portint = Integer.parseInt(port);
                } else portint = 12345;
                // 将帧率转换为整数
                if (frameRate != null) {
                    frameRateint = Integer.parseInt(frameRate);
                } else frameRateint = 30;
                // 将码率转换为整数（支持 k 和 M 单位）
                if (bitrate != null) {
                    bitrateint = parseBitrate(bitrate);
                } else bitrateint = 500000;
                if (resultCode == -1 && data != null) {
                    startCapture(resultCode, data);
                }
            }
        }
        return START_STICKY;
    }

    private void startCapture(int resultCode, Intent data) {
        MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);

        // 注册 MediaProjection 回调
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                releaseResources();
            }
        }, handler);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        // 创建 VirtualDisplay
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null, null, null
        );
        //禁用主线程网络操作检查
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
        //socket服务器
        new Thread(this::startSocketServer).start();

//        //初始化音频
//        initAudioRecord();
//        initAudioEncoder();
//        //启动音频录制
//        startAudioRecord();
//        //socket服务器
//        new Thread(this::startSocketServer).start();
//        //启动RTP发送
//        new Thread(this::startRtpSender).start();
    }

    private void startSocketServer() {
        try {
            serverSocket = new ServerSocket(portint); // 使用 12345 端口
            while (true) {
                if (clientSocket == null || clientSocket.isClosed()) {
                    clientSocket = serverSocket.accept(); // 等待客户端连接
                    outputStream = clientSocket.getOutputStream();
                    // 配置 MediaCodec
                    try {
                        mediaCodec = MediaCodec.createEncoderByType(encodingFormat);
                        MediaFormat format = MediaFormat.createVideoFormat(encodingFormat, screenWidth, screenHeight);
                        format.setInteger(MediaFormat.KEY_BIT_RATE, (int) bitrateint);
                        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRateint);
                        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

                        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                        inputSurface = mediaCodec.createInputSurface();
                        mediaCodec.start();

                        // 等待输出格式变化，获取 SPS 和 PPS
                        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                        int outputBufferId = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
                        if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat outputFormat = mediaCodec.getOutputFormat();
                            sps = outputFormat.getByteBuffer("csd-0"); // SPS
                            pps = outputFormat.getByteBuffer("csd-1"); // PPS
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        return;
                    }
                    virtualDisplay.setSurface(inputSurface);
                    startStreaming();
                } else {
                    // 如果没有新的客户端连接，等待 500 毫秒
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void startStreaming() {
        while (true) {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferId = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);

            if (outputBufferId >= 0) {
                ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferId);
                if (outputBuffer != null && outputStream != null) {
                    byte[] data = new byte[bufferInfo.size];
                    outputBuffer.get(data);

                    // 检查是否为关键帧
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                        // 在关键帧之前插入 SPS 和 PPS
                        if (sps != null && pps != null) {
                            byte[] spsData = new byte[sps.remaining()];
                            sps.get(spsData);
                            byte[] ppsData = new byte[pps.remaining()];
                            pps.get(ppsData);

                            try {
                                outputStream.write(spsData); // 写入 SPS
                                outputStream.write(ppsData); // 写入 PPS
                            } catch (IOException e) {
                                e.printStackTrace();
                            }

                            // 重置 SPS 和 PPS 的 position，以便下次使用
                            sps.clear();
                            pps.clear();
                        }
                    }

                    try {
                        outputStream.write(data); // 写入视频帧数据
                    } catch (SocketException e) {
                        e.printStackTrace(); // 打印异常日志
                        // 处理连接断开
                        if (clientSocket != null && !clientSocket.isClosed()) {
                            try {
                                clientSocket.close(); // 关闭客户端Socket
                                outputStream = null;
                                break;
                            } catch (IOException ex) {
                                ex.printStackTrace();
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace(); // 处理其他IO异常
                    }
                }
                mediaCodec.releaseOutputBuffer(outputBufferId, false);
            } else if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // 没有可用的输出缓冲区，稍后再试
            } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 输出格式发生变化，可以忽略
            }

            // 继续循环读取
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }


    private void sendRtpPacket(byte[] data, int offset, int size, long timestamp, boolean isAudio) {
        if (rtpSocket == null || rtpAddress == null) {
            Log.e("TAG", "rtp socket 未初始化");
            return;
        }
        // RTP 包头
        byte[] rtpHeader = new byte[12];
        rtpHeader[0] = (byte) 0x80;  // 版本号 + 标志位
        rtpHeader[1] = isAudio ? (byte) 97 : (byte) 96; // 负载类型, 97为AAC 96为H264
        int sequenceNumber = (int) (System.currentTimeMillis() % 65535);
        rtpHeader[2] = (byte) (sequenceNumber >> 8);    // 序列号
        rtpHeader[3] = (byte) (sequenceNumber);
        rtpHeader[4] = (byte) (timestamp >> 24);   // 时间戳
        rtpHeader[5] = (byte) (timestamp >> 16);
        rtpHeader[6] = (byte) (timestamp >> 8);
        rtpHeader[7] = (byte) (timestamp);
        rtpHeader[8] = (byte) (0); // 同步源标识符 (SSRC)
        rtpHeader[9] = (byte) (0);
        rtpHeader[10] = (byte) (0);
        rtpHeader[11] = (byte) (0);
        // 拼接 RTP 包数据
        byte[] rtpPacket = new byte[12 + size];
        System.arraycopy(rtpHeader, 0, rtpPacket, 0, 12);
        System.arraycopy(data, offset, rtpPacket, 12, size);

        try {
            DatagramPacket packet = new DatagramPacket(rtpPacket, rtpPacket.length, rtpAddress, rtpPort);
            rtpSocket.send(packet);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initRtpSocket() {
        try {
            rtpSocket = new DatagramSocket();
            rtpAddress = InetAddress.getByName("239.0.0.1");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startRtpSender() {
        initRtpSocket();
        while (true) {
            if (audioBuffer != null && audioBuffer.length > 0) {
                sendRtpPacket(audioBuffer, 0, audioBuffer.length, audioPresentationTimeUs, true);
                audioBuffer = null;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void initAudioRecord() {
        audioMinBufferSize = AudioRecord.getMinBufferSize(audioSampleRate, channelConfig, audioFormat);

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
            ActivityCompat.requestPermissions(MainActivity.get_activity(), new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO_PERMISSION);
            return;
        }
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, audioSampleRate, channelConfig, audioFormat, audioMinBufferSize * 10);
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e("TAG", "音频录制器初始化失败");
            return;
        }
    }
    private void startAudioRecord() {
        if (audioRecord == null) {
            initAudioRecord();
        }
        if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
            audioRecord.startRecording();
            //启动子线程读取录音数据并编码
            new Thread(this::readAudioDataAndEncode).start();
        }
    }
    private void readAudioDataAndEncode() {
        byte[] buffer = new byte[audioMinBufferSize];
        while (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING){
            int len = audioRecord.read(buffer,0,buffer.length);
            if (len>0){
                encodeAudioData(buffer,len);
            }
        }
        releaseAudioRecord();
    }

    private void encodeAudioData(byte[] buffer, int len) {
        int inputBufferIndex = audioCodec.dequeueInputBuffer(10000);
        if (inputBufferIndex >= 0) {
            ByteBuffer inputBuffer = audioCodec.getInputBuffer(inputBufferIndex);
            inputBuffer.clear();
            inputBuffer.put(buffer, 0, len);
            audioCodec.queueInputBuffer(inputBufferIndex, 0, len, audioPresentationTimeUs, 0);
            audioPresentationTimeUs = System.nanoTime()/1000; // 音频时间戳
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = audioCodec.dequeueOutputBuffer(bufferInfo, 10000);
            while (outputBufferIndex >= 0) {
                ByteBuffer outputBuffer = audioCodec.getOutputBuffer(outputBufferIndex);
                byte[] outData = new byte[bufferInfo.size];
                outputBuffer.get(outData);
                outputBuffer.clear();
                //处理音频数据
                this.audioBuffer = outData;

                audioCodec.releaseOutputBuffer(outputBufferIndex, false);
                outputBufferIndex = audioCodec.dequeueOutputBuffer(bufferInfo, 10000);
            }
        }
    }
    private void releaseAudioRecord() {
        if (audioRecord != null){
            audioRecord.stop();
            audioRecord.release();
            audioRecord=null;
        }
    }

    private void initAudioEncoder() {
        try {
            audioCodec = MediaCodec.createEncoderByType(audioEncodingFormat);
            MediaFormat format = MediaFormat.createAudioFormat(audioEncodingFormat, audioSampleRate, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, audioBitRate);
            audioCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioCodec.start();
        } catch (Exception e) {
            Log.e("TAG", "初始化音频编码器失败", e);
            e.printStackTrace();
        }
    }

    private void releaseResources() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            outputStream = null;
        }
        if (clientSocket != null) {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            clientSocket = null;
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            serverSocket = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseResources();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}