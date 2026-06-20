/*
  Music Servo Upgraded - ESP32 Arduino firmware

  Board: ESP32 Dev Module
  Servo signal pin: GPIO 18 by default

  This firmware exposes a Nordic UART compatible BLE service. The web page sends
  JSON commands to the RX/write characteristic, and the ESP32 converts them into
  servo PWM movements.
*/

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// Must match index.html.
static const char *DEVICE_NAME = "Music Servo ESP32";
static const char *SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
static const char *WRITE_CHAR_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
static const char *NOTIFY_CHAR_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";

// Change these values for your hardware.
static const int SERVO_PIN = 18;
static const int SERVO_INDEX = 1;
static const int PWM_FREQ = 50;
static const int PWM_RESOLUTION_BITS = 16;

// Typical hobby servo pulse range. Calibrate if your servo needs different limits.
static const int MIN_PULSE_US = 500;
static const int MAX_PULSE_US = 2500;
static const int MIN_ANGLE = 0;
static const int MAX_ANGLE = 180;

// Protection limits.
static const uint32_t MIN_COMMAND_GAP_MS = 45;
static const int MAX_REPEAT_COUNT = 5;
static const size_t MAX_MESSAGE_LENGTH = 768;

BLECharacteristic *notifyChar = nullptr;
bool bleConnected = false;
String rxBuffer;
int currentAngle = 90;
uint32_t lastCommandAt = 0;

int clampInt(int value, int low, int high) {
  if (value < low) return low;
  if (value > high) return high;
  return value;
}

void writeServoAngle(int angle) {
  angle = clampInt(angle, MIN_ANGLE, MAX_ANGLE);
  currentAngle = angle;

  const int pulseUs = map(angle, MIN_ANGLE, MAX_ANGLE, MIN_PULSE_US, MAX_PULSE_US);
  const uint32_t maxDuty = (1UL << PWM_RESOLUTION_BITS) - 1;
  const uint32_t duty = (uint32_t)((uint64_t)pulseUs * maxDuty / 20000ULL);
  ledcWrite(SERVO_PIN, duty);
}

void moveServoSmooth(int fromAngle, int toAngle, int stepDelayMs) {
  fromAngle = clampInt(fromAngle, MIN_ANGLE, MAX_ANGLE);
  toAngle = clampInt(toAngle, MIN_ANGLE, MAX_ANGLE);
  stepDelayMs = clampInt(stepDelayMs, 1, 40);

  if (fromAngle == toAngle) {
    writeServoAngle(toAngle);
    return;
  }

  const int direction = toAngle > fromAngle ? 1 : -1;
  for (int angle = fromAngle; angle != toAngle; angle += direction) {
    writeServoAngle(angle);
    delay(stepDelayMs);
  }
  writeServoAngle(toAngle);
}

bool extractIntValue(const String &json, const char *key, int &value) {
  const String token = String("\"") + key + "\"";
  int pos = json.indexOf(token);
  if (pos < 0) return false;

  pos = json.indexOf(':', pos + token.length());
  if (pos < 0) return false;
  pos += 1;

  while (pos < (int)json.length() && isspace((unsigned char)json[pos])) pos++;

  int sign = 1;
  if (pos < (int)json.length() && json[pos] == '-') {
    sign = -1;
    pos++;
  }

  bool foundDigit = false;
  long result = 0;
  while (pos < (int)json.length() && isdigit((unsigned char)json[pos])) {
    foundDigit = true;
    result = result * 10 + (json[pos] - '0');
    pos++;
  }

  if (!foundDigit) return false;
  value = (int)(result * sign);
  return true;
}

bool jsonLooksComplete(const String &text) {
  int depth = 0;
  bool inString = false;
  bool escaped = false;

  for (size_t i = 0; i < text.length(); i++) {
    const char c = text[i];

    if (escaped) {
      escaped = false;
      continue;
    }

    if (c == '\\' && inString) {
      escaped = true;
      continue;
    }

    if (c == '"') {
      inString = !inString;
      continue;
    }

    if (inString) continue;

    if (c == '{') depth++;
    if (c == '}') depth--;
  }

  return depth == 0 && text.indexOf('{') >= 0 && text.lastIndexOf('}') >= 0;
}

void sendStatus(const String &message) {
  Serial.println(message);
  if (bleConnected && notifyChar != nullptr) {
    notifyChar->setValue(message.c_str());
    notifyChar->notify();
  }
}

void handleCommand(const String &json) {
  if (json.indexOf("\"set_onboard_output\"") < 0) {
    sendStatus("Ignored command: unsupported method");
    return;
  }

  int index = SERVO_INDEX;
  int fromAngle = currentAngle;
  int toAngle = currentAngle;
  int speed = 5;
  int repeatCount = 1;
  int repeatDelayMs = 35;

  extractIntValue(json, "index", index);
  extractIntValue(json, "sia", fromAngle);
  extractIntValue(json, "sra", toAngle);
  extractIntValue(json, "sat", speed);
  extractIntValue(json, "rc", repeatCount);
  extractIntValue(json, "rit", repeatDelayMs);

  if (index != SERVO_INDEX) {
    sendStatus("Ignored command: servo index does not match");
    return;
  }

  const uint32_t now = millis();
  if (now - lastCommandAt < MIN_COMMAND_GAP_MS) {
    sendStatus("Ignored command: rate limited");
    return;
  }
  lastCommandAt = now;

  fromAngle = clampInt(fromAngle, MIN_ANGLE, MAX_ANGLE);
  toAngle = clampInt(toAngle, MIN_ANGLE, MAX_ANGLE);
  speed = clampInt(speed, 1, 40);
  repeatCount = clampInt(repeatCount, 1, MAX_REPEAT_COUNT);
  repeatDelayMs = clampInt(repeatDelayMs, 0, 1000);

  for (int i = 0; i < repeatCount; i++) {
    moveServoSmooth(fromAngle, toAngle, speed);
    if (repeatCount > 1) {
      delay(repeatDelayMs);
      moveServoSmooth(toAngle, fromAngle, speed);
      delay(repeatDelayMs);
    }
  }

  sendStatus(String("Servo moved to ") + toAngle + " deg");
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    bleConnected = true;
    sendStatus("BLE connected");
  }

  void onDisconnect(BLEServer *server) override {
    bleConnected = false;
    rxBuffer = "";
    Serial.println("BLE disconnected");
    server->getAdvertising()->start();
  }
};

class WriteCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    String incoming = characteristic->getValue();
    if (incoming.length() == 0) return;

    for (size_t i = 0; i < incoming.length(); i++) {
      rxBuffer += incoming[i];
    }

    if (rxBuffer.length() > MAX_MESSAGE_LENGTH) {
      sendStatus("Dropped command: message too long");
      rxBuffer = "";
      return;
    }

    if (jsonLooksComplete(rxBuffer)) {
      const String command = rxBuffer;
      rxBuffer = "";
      handleCommand(command);
    }
  }
};

void setupBle() {
  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(517);

  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(SERVICE_UUID);

  BLECharacteristic *writeChar = service->createCharacteristic(
    WRITE_CHAR_UUID,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  writeChar->setCallbacks(new WriteCallbacks());

  notifyChar = service->createCharacteristic(
    NOTIFY_CHAR_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  notifyChar->addDescriptor(new BLE2902());

  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();
}

void setup() {
  Serial.begin(115200);
  delay(300);

  ledcAttach(SERVO_PIN, PWM_FREQ, PWM_RESOLUTION_BITS);
  writeServoAngle(currentAngle);

  setupBle();

  Serial.println();
  Serial.println("Music Servo ESP32 ready");
  Serial.print("BLE name: ");
  Serial.println(DEVICE_NAME);
  Serial.print("Servo pin: GPIO ");
  Serial.println(SERVO_PIN);
}

void loop() {
  delay(20);
}
