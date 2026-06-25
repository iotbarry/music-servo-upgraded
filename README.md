# Music Servo Upgraded

一个用音乐律动控制蓝牙舵机的网页控制台。它可以读取麦克风、电脑/浏览器正在播放的声音，或者本地音乐文件，并根据音量和节拍驱动舵机摆动。

## 功能特点

- 支持麦克风输入
- 支持电脑/标签页声音输入
- 支持本地音乐文件输入
- 支持 Web Bluetooth 连接舵机控制板
- 可调灵敏度、输入增强、最小角度、最大角度、响应速度和平滑程度
- 内置噪声门限，低于设定音量时不会发送舵机指令
- 未连接舵机时也可以先看页面里的舵机预览

## 安全提醒

舵机如果被背景噪声频繁触发，可能会过热或损坏。第一次测试时建议：

1. 先不要连接舵机，只用页面预览调参数。
2. 将“噪声门限”调到 35% 到 50%。
3. 确认安静时不会触发动作后，再连接舵机。
4. 再根据音乐强弱慢慢降低门限。
5. 如果舵机抖动太频繁，提高“平滑程度”或“响应速度”，并降低“最大角度”。

## 使用方法

直接用桌面版 Chrome 或 Edge 打开 `index.html`。

推荐流程：

1. 打开页面。
2. 选择声音来源：麦克风、电脑/标签页声音或本地音乐文件。
3. 调整噪声门限和灵敏度，先确认页面预览正常。
4. 点击“连接蓝牙”，选择舵机控制板。
5. 根据音乐效果微调角度范围、响应速度和平滑程度。

## 读取电脑音乐

点击“电脑/标签页声音”后，浏览器会弹出共享窗口。请选择正在播放音乐的标签页或屏幕，并勾选“共享音频”。

如果没有勾选共享音频，页面无法读取电脑正在播放的音乐。

## 蓝牙说明

页面使用 Web Bluetooth，并沿用 Nordic UART 常见 UUID：

- Service UUID: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- Write Characteristic UUID: `6e400002-b5a3-f393-e0a9-e50e24dcca9e`

发送的舵机控制数据沿用原项目里的 `set_onboard_output` 指令格式。

## 文件说明

- `index.html`: 完整网页控制台，包含样式和脚本
- `firmware/esp32-music-servo/`: ESP32 Arduino 固件
- `android-app/`: 安卓 App 工程，用手机麦克风直接控制 ESP32 舵机
- `README.md`: 项目说明

## ESP32 固件

固件在 `firmware/esp32-music-servo/esp32-music-servo.ino`。

默认设置：

- 蓝牙名称：`Music Servo ESP32`
- 舵机信号线：GPIO 18
- 舵机角度范围：0° 到 180°

接线时请使用独立 5V 电源给舵机供电，并让 ESP32 GND 与舵机电源 GND 共地。不要直接用 ESP32 的 3.3V 给舵机供电。

## 浏览器要求

- 桌面版 Chrome 或 Edge
- 需要支持 Web Bluetooth
- 需要允许麦克风或屏幕/标签页音频权限

## 安卓 APK

安卓工程在 `android-app/`。它可以监听手机麦克风，并通过蓝牙向 ESP32 发送和网页一致的舵机律动指令。

仓库里包含 GitHub Actions 配置，可以在 GitHub 上自动构建 debug APK。

## 项目描述

Music-driven Web Bluetooth servo controller with microphone, system audio, local music input, sensitivity tuning, and a noise gate to protect servos from frequent low-volume triggers.
