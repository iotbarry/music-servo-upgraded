# ESP32 Arduino 固件

这份固件用于配合仓库根目录的 `index.html`。ESP32 会广播一个蓝牙设备，网页连接后通过蓝牙发送舵机角度，ESP32 再输出 PWM 控制舵机。

## 默认接线

| 舵机线 | 连接 |
| --- | --- |
| 信号线 | ESP32 GPIO 18 |
| 电源正极 | 外部 5V 电源 |
| 电源负极 | 外部电源 GND |
| 共地 | ESP32 GND 与外部电源 GND 相连 |

不要直接用 ESP32 的 3.3V 给舵机供电。舵机建议使用独立 5V 电源，并且必须和 ESP32 共地。

## Arduino IDE 设置

1. 安装 ESP32 Arduino 开发板支持。
2. 打开 `esp32-music-servo.ino`。
3. 选择开发板，例如 `ESP32 Dev Module`。
4. 选择正确串口。
5. 上传。

## 网页连接

上传成功后，ESP32 会广播蓝牙名称：

```text
Music Servo ESP32
```

打开网页，点击“连接蓝牙”，选择这个设备即可。

## 可调参数

如果你的舵机接在其他引脚，修改：

```cpp
static const int SERVO_PIN = 18;
```

如果舵机角度不准，微调：

```cpp
static const int MIN_PULSE_US = 500;
static const int MAX_PULSE_US = 2500;
```

## 安全建议

第一次测试时先把网页里的“噪声门限”调高，确认安静时舵机不会频繁动作，再慢慢降低门限。
