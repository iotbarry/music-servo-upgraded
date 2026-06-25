package com.iotbarry.musicservo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final UUID SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID WRITE_CHAR_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final int SERVO_INDEX = 1;
    private static final int REQ_PERMISSIONS = 1001;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private boolean scanning = false;
    private boolean connected = false;

    private AudioRecord audioRecord;
    private Thread audioThread;
    private volatile boolean listening = false;

    private TextView statusText;
    private TextView audioText;
    private TextView angleText;
    private TextView sentText;
    private TextView logText;
    private Button connectButton;
    private Button listenButton;
    private Button testButton;

    private int sensitivity = 14;
    private int inputGain = 3;
    private int noiseGate = 35;
    private int minAngle = 5;
    private int maxAngle = 60;
    private int sendRateMs = 80;
    private int smoothness = 30;

    private int currentAngle = 0;
    private int lastSentAngle = 0;
    private int sentCount = 0;
    private long lastSendAt = 0L;
    private long lastBeatAt = 0L;
    private double slowEnergy = 0.02;
    private double fastEnergy = 0.02;
    private double smoothedTarget = 0.0;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = getDeviceName(device);
            if (isTargetDeviceName(name) || resultMatchesService(result)) {
                stopScan();
                connectDevice(device);
            }
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                runOnUiThread(() -> {
                    setStatus("已连接，正在发现服务");
                    log("蓝牙已连接");
                });
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                writeChar = null;
                runOnUiThread(() -> {
                    setStatus("已断开");
                    connectButton.setText("连接开发板");
                    log("蓝牙已断开");
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            writeChar = service == null ? null : service.getCharacteristic(WRITE_CHAR_UUID);
            runOnUiThread(() -> {
                if (writeChar == null) {
                    setStatus("未找到舵机服务");
                    log("没有找到匹配的蓝牙写入通道");
                } else {
                    setStatus("开发板已就绪");
                    connectButton.setText("断开连接");
                    log("已找到舵机写入通道");
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        buildUi();
        ensurePermissions(false);
    }

    @Override
    protected void onDestroy() {
        stopListening();
        stopScan();
        closeGatt();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(7, 12, 18));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        scrollView.addView(root);

        TextView title = text("音乐律动舵机", 28, true);
        root.addView(title);
        root.addView(text("监听手机麦克风，并通过蓝牙向 ESP32 发送舵机律动指令", 14, false));

        LinearLayout statusPanel = panel();
        statusText = text("未连接", 20, true);
        audioText = text("音量 0% · 噪声门限 35%", 16, false);
        angleText = text("角度 0°", 42, true);
        sentText = text("已发送 0 条", 16, false);
        statusPanel.addView(label("状态"));
        statusPanel.addView(statusText);
        statusPanel.addView(audioText);
        statusPanel.addView(angleText);
        statusPanel.addView(sentText);
        root.addView(statusPanel);

        LinearLayout buttons = panel();
        connectButton = button("连接开发板", true);
        listenButton = button("开始监听", true);
        testButton = button("测试舵机", false);
        buttons.addView(connectButton);
        buttons.addView(listenButton);
        buttons.addView(testButton);
        root.addView(buttons);

        connectButton.setOnClickListener(v -> {
            if (connected || gatt != null) {
                closeGatt();
            } else {
                startScan();
            }
        });
        listenButton.setOnClickListener(v -> {
            if (listening) stopListening();
            else startListening();
        });
        testButton.setOnClickListener(v -> {
            int middle = (minAngle + maxAngle) / 2;
            sendServo(minAngle, middle, 5, 1);
            mainHandler.postDelayed(() -> sendServo(middle, maxAngle, 5, 1), 230);
            mainHandler.postDelayed(() -> sendServo(maxAngle, minAngle, 5, 1), 480);
        });

        LinearLayout controls = panel();
        controls.addView(label("参数"));
        controls.addView(slider("噪声门限", 0, 80, noiseGate, "%", value -> noiseGate = value));
        controls.addView(slider("灵敏度", 1, 20, sensitivity, "", value -> sensitivity = value));
        controls.addView(slider("输入增强", 1, 8, inputGain, "x", value -> inputGain = value));
        controls.addView(slider("最小角度", 0, 90, minAngle, "°", value -> {
            minAngle = Math.min(value, maxAngle - 1);
            updateStatusLabels(0);
        }));
        controls.addView(slider("最大角度", 10, 180, maxAngle, "°", value -> {
            maxAngle = Math.max(value, minAngle + 1);
            updateStatusLabels(0);
        }));
        controls.addView(slider("响应速度", 40, 260, sendRateMs, "ms", value -> sendRateMs = value));
        controls.addView(slider("平滑程度", 0, 80, smoothness, "%", value -> smoothness = value));
        root.addView(controls);

        LinearLayout safety = panel();
        safety.addView(label("保护建议"));
        safety.addView(text("第一次测试先把噪声门限调到 40% 左右。安静时不发送指令，再慢慢降低门限。舵机请使用独立 5V 电源，并和 ESP32 共地。", 14, false));
        root.addView(safety);

        LinearLayout logPanel = panel();
        logPanel.addView(label("日志"));
        logText = text("", 13, false);
        logPanel.addView(logText);
        root.addView(logPanel);

        setContentView(scrollView);
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(com.iotbarry.musicservo.R.drawable.panel_bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(16), 0, 0);
        panel.setLayoutParams(params);
        return panel;
    }

    private TextView text(String value, int sp, boolean strong) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(strong ? Color.rgb(231, 237, 247) : Color.rgb(145, 160, 181));
        view.setTextSize(sp);
        view.setGravity(Gravity.START);
        view.setPadding(0, dp(4), 0, dp(4));
        if (strong) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView label(String value) {
        TextView view = text(value, 13, true);
        view.setTextColor(Color.rgb(56, 189, 248));
        return view;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackgroundResource(primary ? R.drawable.button_primary : R.drawable.button_dark);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(8), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private View slider(String name, int min, int max, int initial, String unit, IntSetter setter) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(4));

        TextView label = text("", 14, true);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(initial - min);
        label.setText(name + " " + initial + unit);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = min + progress;
                setter.set(value);
                label.setText(name + " " + value + unit);
                updateStatusLabels(0);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        box.addView(label);
        box.addView(bar);
        return box;
    }

    private boolean ensurePermissions(boolean startAfterGrant) {
        List<String> needed = new ArrayList<>();
        addPermissionIfNeeded(needed, Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31) {
            addPermissionIfNeeded(needed, Manifest.permission.BLUETOOTH_SCAN);
            addPermissionIfNeeded(needed, Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            addPermissionIfNeeded(needed, Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
            if (startAfterGrant) log("请先允许权限，再重新点击操作");
            return false;
        }
        return true;
    }

    private void addPermissionIfNeeded(List<String> needed, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            needed.add(permission);
        }
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (!ensurePermissions(true)) return;
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            log("请先打开手机蓝牙");
            return;
        }
        if (scanning) return;

        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            log("这台手机不支持低功耗蓝牙扫描");
            return;
        }

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build());
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanning = true;
        setStatus("正在扫描开发板");
        connectButton.setText("扫描中");
        scanner.startScan(filters, settings, scanCallback);
        mainHandler.postDelayed(() -> {
            if (scanning) {
                stopScan();
                setStatus("未找到开发板");
                connectButton.setText("连接开发板");
                log("扫描超时，请确认 ESP32 已上电");
            }
        }, 12000);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (scanner != null && scanning) {
            scanner.stopScan(scanCallback);
        }
        scanning = false;
    }

    @SuppressLint("MissingPermission")
    private void connectDevice(BluetoothDevice device) {
        setStatus("正在连接 " + safeName(device));
        log("发现开发板：" + safeName(device));
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        stopScan();
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
        }
        gatt = null;
        writeChar = null;
        connected = false;
        setStatus("未连接");
        connectButton.setText("连接开发板");
    }

    private boolean resultMatchesService(ScanResult result) {
        return result.getScanRecord() != null
                && result.getScanRecord().getServiceUuids() != null
                && result.getScanRecord().getServiceUuids().contains(new ParcelUuid(SERVICE_UUID));
    }

    private boolean isTargetDeviceName(String name) {
        if (TextUtils.isEmpty(name)) return false;
        return name.contains("Music Servo") || name.startsWith("直播-");
    }

    @SuppressLint("MissingPermission")
    private String getDeviceName(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return "";
        }
        return device == null ? "" : device.getName();
    }

    private String safeName(BluetoothDevice device) {
        String name = getDeviceName(device);
        return TextUtils.isEmpty(name) ? "未命名设备" : name;
    }

    private void startListening() {
        if (!ensurePermissions(true)) return;
        if (listening) return;

        int sampleRate = 44100;
        int minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int bufferSize = Math.max(minBuffer, 2048);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
        );

        listening = true;
        listenButton.setText("停止监听");
        log("开始监听麦克风");

        audioThread = new Thread(() -> audioLoop(bufferSize), "music-servo-audio");
        audioThread.start();
    }

    private void stopListening() {
        listening = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException ignored) {
            }
            audioRecord.release();
            audioRecord = null;
        }
        listenButton.setText("开始监听");
    }

    private void audioLoop(int bufferSize) {
        short[] buffer = new short[bufferSize / 2];
        try {
            audioRecord.startRecording();
        } catch (IllegalStateException error) {
            runOnUiThread(() -> log("麦克风启动失败"));
            listening = false;
            return;
        }

        while (listening && audioRecord != null) {
            int read = audioRecord.read(buffer, 0, buffer.length);
            if (read <= 0) continue;
            double rms = computeRms(buffer, read);
            handleAudioEnergy(rms);
        }
    }

    private double computeRms(short[] buffer, int read) {
        double sum = 0.0;
        for (int i = 0; i < read; i++) {
            double sample = buffer[i] / 32768.0;
            sum += sample * sample;
        }
        return Math.sqrt(sum / Math.max(1, read));
    }

    private void handleAudioEnergy(double rms) {
        double gained = clamp01(rms * inputGain * 7.0);
        slowEnergy += (gained - slowEnergy) * 0.035;
        fastEnergy += (gained - fastEnergy) * 0.34;

        int energyPct = (int) Math.round(gained * 100.0);
        mainHandler.post(() -> updateStatusLabels(energyPct));

        double gate = noiseGate / 100.0;
        if (gained < gate) {
            return;
        }

        long now = System.currentTimeMillis();
        double onset = fastEnergy - slowEnergy;
        double threshold = 0.015 + (20 - sensitivity) * 0.0045;
        boolean beat = onset > threshold && now - lastBeatAt > 130;
        boolean canSend = now - lastSendAt >= sendRateMs;

        if (!canSend) return;

        int span = Math.max(1, maxAngle - minAngle);
        double target01 = clamp01(gained);
        if (beat) {
            lastBeatAt = now;
            target01 = Math.max(0.35, target01);
        } else if (gained < gate + 0.08) {
            return;
        }

        double rawTarget = minAngle + span * target01;
        smoothedTarget += (rawTarget - smoothedTarget) * (1.0 - smoothness / 100.0);
        int target = clamp((int) Math.round(smoothedTarget), minAngle, maxAngle);
        int speed = Math.max(2, 12 - (int) Math.round(target01 * 7.0));

        lastSendAt = now;
        sendServo(lastSentAngle, target, speed, 1);
        lastSentAngle = target;
    }

    private void sendServo(int from, int to, int speed, int repeat) {
        currentAngle = clamp(to, 0, 180);
        mainHandler.post(() -> {
            angleText.setText("角度 " + currentAngle + "°");
            if (writeChar == null || gatt == null) {
                return;
            }
            String json = String.format(Locale.US,
                    "{\"method\":\"set_onboard_output\",\"data\":[{\"index\":%d,\"mode\":3,\"sia\":%d,\"sra\":%d,\"sat\":%d,\"rt\":%d,\"rit\":35,\"rc\":%d,\"rds\":0}]}",
                    SERVO_INDEX,
                    clamp(from, 0, 180),
                    clamp(to, 0, 180),
                    clamp(speed, 1, 40),
                    Math.max(80, Math.abs(to - from) * speed + 50),
                    Math.max(1, repeat)
            );
            writeBle(json);
        });
    }

    @SuppressLint("MissingPermission")
    private void writeBle(String json) {
        if (gatt == null || writeChar == null) return;
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

        boolean accepted;
        if (Build.VERSION.SDK_INT >= 33) {
            accepted = gatt.writeCharacteristic(writeChar, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS;
        } else {
            writeChar.setValue(data);
            accepted = gatt.writeCharacteristic(writeChar);
        }

        if (accepted) {
            sentCount++;
            sentText.setText("已发送 " + sentCount + " 条");
        }
    }

    private void setStatus(String status) {
        statusText.setText(status);
    }

    private void updateStatusLabels(int energyPct) {
        audioText.setText("音量 " + energyPct + "% · 噪声门限 " + noiseGate + "%");
        angleText.setText("角度 " + currentAngle + "°");
    }

    private void log(String message) {
        String old = logText == null ? "" : logText.getText().toString();
        String next = old + "\n" + message;
        if (next.length() > 1200) {
            next = next.substring(next.length() - 1200);
        }
        if (logText != null) logText.setText(next.trim());
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    interface IntSetter {
        void set(int value);
    }
}
