package com.xxianna27.sockettvserver;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQUEST_MEDIA_PROJECTION = 1;
    private MediaProjectionManager mediaProjectionManager;

    private static Context mContext;

    private EditText portEditText;
    private EditText prefixLengthEditText;
    private Button rescanButton;

    private Spinner frameRateSpinner;
    private Spinner bitrateSpinner;
    private Spinner encodingFormatSpinner;
    private Button startButton;
    private Button restartButton;
    private Switch zoomFitSwitch;

    //目标设备
    private Spinner targetDeviceSpinner;
    private List<String> targetDevices = new ArrayList<>();
    private ArrayAdapter<String> targetDeviceAdapter;

    private static Activity now_activity;
    public static Activity get_activity(){
        return now_activity;
    }


    private String getNetworkIpAddress() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                // 检查接口名称是否以 "wlan"、"wlp"（Wi-Fi）或 "eth"、"en"（以太网）开头
                if (networkInterface.getName().startsWith("wlan") ||
                        networkInterface.getName().startsWith("wlp") ||
                        networkInterface.getName().startsWith("eth") ||
                        networkInterface.getName().startsWith("en")) {

                    Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                    while (inetAddresses.hasMoreElements()) {
                        InetAddress inetAddress = inetAddresses.nextElement();
                        // 检查是否为 IPv4 地址且不是环回地址
                        if (!inetAddress.isLoopbackAddress() && inetAddress.getAddress().length == 4) {
                            return inetAddress.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return null;
    }
    private void scanNetworkForDevices() {
        ExecutorService executorService = Executors.newFixedThreadPool(128); // 创建一个固定大小的线程池，大小为16

        // 获取当前Wi-Fi网络的IP地址和子网掩码
        String ipAddress = getNetworkIpAddress();
        int prefixLength = 24; // 假设子网掩码的前缀长度为24
        String prefixLengthText = prefixLengthEditText.getText().toString();
        if (!prefixLengthText.isEmpty()) {
            prefixLength = Integer.parseInt(prefixLengthText);
        }

        // 将IP地址转换为整数
        int ipInt = ipToInt(ipAddress);

        // 计算网络地址和广播地址
        int networkAddress = ipInt & (0xFFFFFFFF << (32 - prefixLength));
        int broadcastAddress = networkAddress | (0xFFFFFFFF >>> prefixLength);

        // 遍历IP地址范围
        for (int i = networkAddress + 1; i < broadcastAddress; i++) {
            String ip = intToIp(i);
            executorService.execute(() -> {
                if (isDeviceAvailable(ip)) {
                    runOnUiThread(() -> {
                        targetDevices.add(ip);
                        targetDeviceAdapter.notifyDataSetChanged();
                    });
                }
            });
        }

        executorService.shutdown(); // 关闭线程池
    }

    // 将IP地址字符串转换为整数
    private int ipToInt(String ipAddress) {
        String[] parts = ipAddress.split("\\.");
        int result = 0;
        for (int i = 0; i < 4; i++) {
            result |= Integer.parseInt(parts[i]) << (24 - (8 * i));
        }
        return result;
    }

    // 将整数形式的IP地址转换为字符串形式
    private String intToIp(int ip) {
        return ((ip >> 24) & 0xFF) + "." +
                ((ip >> 16) & 0xFF) + "." +
                ((ip >> 8) & 0xFF) + "." +
                (ip & 0xFF);
    }

    private boolean isDeviceAvailable(String ip) {
        try {
            URL url = new URL("http://" + ip + ":57682/mytvtvtv/");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            int responseCode = connection.getResponseCode();
            return responseCode == 200;
        } catch (Exception e) {
            return false;
        }
    }



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = getApplicationContext();
        setContentView(R.layout.activity_main);
        portEditText = findViewById(R.id.portEditText);
        prefixLengthEditText = findViewById(R.id.prefixLengthEditText);
        zoomFitSwitch = findViewById(R.id.zoomFitSwitch);

        // 初始化Spinner和Button
        frameRateSpinner = findViewById(R.id.frameRateSpinner);
        bitrateSpinner = findViewById(R.id.bitrateSpinner);
        encodingFormatSpinner = findViewById(R.id.encodingFormatSpinner);
        startButton = findViewById(R.id.startButton);
        restartButton = findViewById(R.id.restartButton);
        rescanButton = findViewById(R.id.rescanButton);

        now_activity = this;

        Button rotateButton = findViewById(R.id.rotateButton);
        rotateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 获取当前屏幕方向
                int currentOrientation = getResources().getConfiguration().orientation;

                // 根据当前方向切换屏幕方向
                if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                } else {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                }
            }
        });



        targetDeviceSpinner = findViewById(R.id.targetDeviceSpinner);
        targetDeviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, targetDevices);
        targetDeviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        targetDeviceSpinner.setAdapter(targetDeviceAdapter);
//        scanNetworkForDevices();

        new Thread(this::scanNetworkForDevices).start();
        // 设置帧率选项
        ArrayAdapter<CharSequence> frameRateAdapter = ArrayAdapter.createFromResource(this,
                R.array.frame_rate_array, android.R.layout.simple_spinner_item);
        frameRateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        frameRateSpinner.setAdapter(frameRateAdapter);

        // 设置码率选项
        ArrayAdapter<CharSequence> bitrateAdapter = ArrayAdapter.createFromResource(this,
                R.array.bitrate_array, android.R.layout.simple_spinner_item);
        bitrateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bitrateSpinner.setAdapter(bitrateAdapter);

        // 设置编码格式选项
        ArrayAdapter<CharSequence> encodingFormatAdapter = ArrayAdapter.createFromResource(this,
                R.array.encoding_format_array, android.R.layout.simple_spinner_item);
        encodingFormatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        encodingFormatSpinner.setAdapter(encodingFormatAdapter);

        // 启动投屏按钮点击事件
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String targetDevice = targetDeviceSpinner.getSelectedItem().toString();
                String frameRate = frameRateSpinner.getSelectedItem().toString();
                String port = portEditText.getText().toString(); // 获取端口号
                boolean isZoomFitEnabled = zoomFitSwitch.isChecked(); // 获取Switch的状态

                new Thread(() -> {
                    try {
                        URL url = new URL("http://" + targetDevice + ":57682/mytvtvtvconnect");
                        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                        connection.setRequestMethod("POST");
                        connection.setDoOutput(true);
                        connection.setRequestProperty("Content-Type", "application/json; utf-8");
                        connection.setRequestProperty("Accept", "application/json");

                        String jsonInputString = "{\"frameRate\": \"" + frameRate + "\", \"port\": \"" + port + "\", \"zoomFit\": " + isZoomFitEnabled + "}";

                        try (OutputStream os = connection.getOutputStream()) {
                            byte[] input = jsonInputString.getBytes("utf-8");
                            os.write(input, 0, input.length);
                        }

                        int responseCode = connection.getResponseCode();
                        if (responseCode == 200) {
                            // 请求成功
                        } else {
                            // 请求失败
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();

                mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
            }
        });

        // 重启应用按钮点击事件
        restartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = getIntent();
                finish();
                System.exit(0); // 终止当前进程
                startActivity(intent);
            }
        });

        // 设置重新扫描按钮的点击事件
        rescanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 清空之前的设备列表
                targetDevices.clear();
                targetDeviceAdapter.notifyDataSetChanged();

                // 重新扫描网络
                new Thread(MainActivity.this::scanNetworkForDevices).start();
            }
        });
    }

    public static Context getContext(){
        return mContext;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK) {
            // 获取用户选择的帧率、码率和编码格式
            String frameRate = frameRateSpinner.getSelectedItem().toString();
            String bitrate = bitrateSpinner.getSelectedItem().toString();
            String encodingFormat = encodingFormatSpinner.getSelectedItem().toString();
            String port = portEditText.getText().toString(); // 获取端口号

            Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
            serviceIntent.putExtra("resultCode", resultCode);
            serviceIntent.putExtra("data", data);
            serviceIntent.putExtra("frameRate", frameRate);
            serviceIntent.putExtra("bitrate", bitrate);
            serviceIntent.putExtra("encodingFormat", encodingFormat);
            serviceIntent.putExtra("port", port); // 传递端口号
            serviceIntent.setAction("init_service");
            startForegroundService(serviceIntent);
        }
    }
}