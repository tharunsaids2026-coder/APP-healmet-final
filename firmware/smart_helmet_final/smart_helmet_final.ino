/*
  Smart Helmet with Automatic Crash Detection - final ESP32 firmware (Team NEXORA)

  Board   : ESP32 DevKit (30-pin), Arduino IDE with "esp32 by Espressif Systems"
  Sketch  : save in a folder named smart_helmet_final. Only built-in libraries are used.
  Partition scheme: Tools > Partition Scheme > Huge APP if the sketch is too big.

  Pins (match the breadboard diagram)
    D21 = MPU6050 SDA      D22 = MPU6050 SCL
    D13 = IR sensor OUT    (LOW = head inside the helmet)
    D14 = Reed switch      (LOW = strap clipped, magnet in the buckle)
    D27 = Cancel button    (LOW = pressed)
    D26 = Green LED        (ON = helmet armed)
    D4  = Buzzer           (HIGH = sound)

  What it does
    1. Strap open for 3 s            -> the ESP32 shuts down (deep sleep). Clip the strap to wake it.
    2. Strap clipped and head inside -> helmet is "armed", green LED on.
    3. Impact above 3 g while armed  -> check that the helmet then stays still for 3 s.
    4. Still for 3 s                 -> buzzer beeps for 10 s and the phone is told ("WARN").
                                        The cancel button OR the "I'm OK" button in the app stops it.
    5. Not cancelled                 -> sends "CRASH" over Bluetooth to the Helmet SOS app,
                                        which sends SMS, Telegram and calls.
    6. 30 s cooldown, then armed again.

  Messages helmet -> phone (notify) : "WARN" warning started, "OK" rider pressed the helmet button,
                                      "CRASH" nobody cancelled.
  Message  phone -> helmet (write)  : "STOP" cancels the warning (rider tapped I'm OK in the app).
*/

#include <Wire.h>
#include <math.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include "driver/rtc_io.h"

// ---------------- Pins ----------------
#define PIN_SDA      21
#define PIN_SCL      22
#define PIN_IR       13
#define PIN_REED     14      // RTC pin: can wake the ESP32 from deep sleep
#define PIN_CANCEL   27
#define PIN_LED      26
#define PIN_BUZZER   4

// ---------------- Settings ----------------
#define USE_IR_SENSOR       true     // false = "worn" depends on the reed switch only (bench tests)
#define ENABLE_SHUTDOWN     true     // false = never go to sleep (easier while uploading code)
#define IMPACT_G            3.0f     // demo threshold, not a validated crash value
#define STILL_BAND_G        0.15f    // |g - 1| below this counts as "still"
#define STILL_TIME_MS       3000UL   // helmet must stay still this long after the impact
#define STILL_WAIT_MAX_MS   15000UL  // give up if the helmet keeps moving
#define WARN_TIME_MS        10000UL  // buzzer time before the alert is sent
#define COOLDOWN_MS         30000UL  // pause after an alert or a cancel
#define UNCLIP_SLEEP_MS     3000UL   // strap must stay open this long before shutdown
#define ARM_GRACE_MS        700UL    // a crash can still count just after the strap pops open

// ---------------- Bluetooth (the app looks for the name SmartHelmet) ----------------
#define SERVICE_UUID "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define CHAR_UUID    "6e400003-b5a3-f393-e0a9-e50e24dcca9e"   // helmet -> phone (notify)
#define RX_UUID      "6e400002-b5a3-f393-e0a9-e50e24dcca9e"   // phone -> helmet (write)
#define CRASH_RETRY_MS 120000UL   // keep trying to reach the phone this long after a crash

BLECharacteristic *crashChar;
bool phoneConnected = false;
volatile bool phoneStop = false;     // set when the app sends "STOP"
bool crashSent = false;              // CRASH went out; the button can still stop the alert
bool crashPending = false;           // crash confirmed but the phone was not connected yet
unsigned long crashPendingAt = 0;

class ServerCb : public BLEServerCallbacks {
  void onConnect(BLEServer *s)    { phoneConnected = true;  Serial.println("Phone connected."); }
  void onDisconnect(BLEServer *s) { phoneConnected = false; Serial.println("Phone disconnected."); BLEDevice::startAdvertising(); }
};

class RxCb : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) {
    uint8_t *d = c->getData();
    size_t n = c->getLength();
    String s = "";
    for (size_t i = 0; i < n; i++) s += (char)d[i];
    s.toUpperCase();
    if (s.indexOf("STOP") >= 0) {
      phoneStop = true;
      Serial.println("App says: rider is OK (STOP).");
    }
  }
};

void setupBLE() {
  BLEDevice::init("SmartHelmet");
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCb());
  BLEService *service = server->createService(SERVICE_UUID);
  crashChar = service->createCharacteristic(CHAR_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  crashChar->addDescriptor(new BLE2902());
  BLECharacteristic *rxChar = service->createCharacteristic(
      RX_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rxChar->setCallbacks(new RxCb());
  service->start();
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
}

// One short message to the app. Does nothing if the phone is not connected.
void notifyPhone(const char *msg) {
  if (!phoneConnected) return;
  crashChar->setValue(msg);
  crashChar->notify();
}

// Sends CRASH three times, one second apart (the app ignores repeats for 30 s).
void sendCrashBurst() {
  for (int i = 0; i < 3; i++) {
    notifyPhone("CRASH");
    delay(1000);
  }
  crashSent = true;
  Serial.println("CRASH sent to the phone.");
}

// If the phone is not connected right now, keep the crash and send it as soon as it reconnects.
void sendCrashToPhone() {
  if (phoneConnected) {
    sendCrashBurst();
  } else {
    crashPending = true;
    crashPendingAt = millis();
    Serial.println("Phone not connected: will send CRASH as soon as it reconnects.");
  }
}

// ---------------- MPU6050 (motion sensor) ----------------
#define MPU_ADDR 0x68

void mpuWrite(uint8_t reg, uint8_t val) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(reg);
  Wire.write(val);
  Wire.endTransmission();
}

int16_t read16() {
  uint8_t hi = Wire.read();
  uint8_t lo = Wire.read();
  return (int16_t)((hi << 8) | lo);
}

bool setupMPU() {
  Wire.begin(PIN_SDA, PIN_SCL);
  Wire.beginTransmission(MPU_ADDR);
  if (Wire.endTransmission() != 0) return false;   // sensor not found
  mpuWrite(0x6B, 0x00);   // wake the sensor
  mpuWrite(0x1C, 0x10);   // +-8 g range (4096 counts per g)
  return true;
}

// Total acceleration in g (about 1.0 when still). Returns -1 if the read failed.
float readG() {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(0x3B);
  if (Wire.endTransmission(false) != 0) return -1;
  if (Wire.requestFrom(MPU_ADDR, 6) != 6) return -1;
  float x = read16() / 4096.0f;
  float y = read16() / 4096.0f;
  float z = read16() / 4096.0f;
  return sqrtf(x * x + y * y + z * z);
}

// ---------------- Helmet state ----------------
bool strapClipped() { return digitalRead(PIN_REED) == LOW; }
bool headInside()   { return digitalRead(PIN_IR) == LOW; }
bool cancelPressed(){ return digitalRead(PIN_CANCEL) == LOW; }
bool helmetArmed()  { return strapClipped() && (!USE_IR_SENSOR || headInside()); }

enum State { IDLE, STILL_CHECK, WARNING, COOLDOWN };
State state = IDLE;

unsigned long impactAt = 0, stillSince = 0, warnAt = 0, cooldownAt = 0;
unsigned long unclippedSince = 0, lastArmedAt = 0;

void goToSleep() {
  Serial.println("Strap open: shutting down. Clip the strap to wake the helmet.");
  digitalWrite(PIN_LED, LOW);
  digitalWrite(PIN_BUZZER, LOW);
  delay(100);
  esp_sleep_enable_ext0_wakeup((gpio_num_t)PIN_REED, 0);   // wake when the reed switch closes (LOW)
  rtc_gpio_pullup_en((gpio_num_t)PIN_REED);                // keep the pull-up alive during sleep
  rtc_gpio_pulldown_dis((gpio_num_t)PIN_REED);
  esp_deep_sleep_start();
}

void setup() {
  Serial.begin(115200);
  pinMode(PIN_REED, INPUT_PULLUP);
  pinMode(PIN_CANCEL, INPUT_PULLUP);
  pinMode(PIN_IR, INPUT_PULLUP);     // reads "no head" if the sensor is unpowered
  pinMode(PIN_BUZZER, OUTPUT);
  pinMode(PIN_LED, OUTPUT);
  digitalWrite(PIN_BUZZER, LOW);
  digitalWrite(PIN_LED, LOW);
  rtc_gpio_hold_dis((gpio_num_t)PIN_REED);

  if (setupMPU()) Serial.println("MPU6050 found.");
  else            Serial.println("MPU6050 NOT found. Check VCC, GND, SDA (D21) and SCL (D22).");

  setupBLE();
  unclippedSince = strapClipped() ? 0 : millis();
  Serial.println("Helmet started.");
}

void loop() {
  unsigned long now = millis();

  // ---- Shutdown when the strap is open (never while a crash alert is in progress) ----
  bool busy = (state == STILL_CHECK || state == WARNING || crashPending);

  // ---- Crash waiting for the phone to reconnect ----
  if (crashPending) {
    if (cancelPressed()) {
      Serial.println("Cancelled by the rider before the phone reconnected.");
      crashPending = false;
    } else if (phoneConnected) {
      sendCrashBurst();
      crashPending = false;
    } else if (now - crashPendingAt > CRASH_RETRY_MS) {
      Serial.println("Phone did not reconnect: giving up on the CRASH message.");
      crashPending = false;
    }
  }
  if (strapClipped()) {
    unclippedSince = 0;
  } else if (ENABLE_SHUTDOWN && !busy) {
    if (unclippedSince == 0) unclippedSince = now;
    if (now - unclippedSince >= UNCLIP_SLEEP_MS) goToSleep();
  }

  bool armed = helmetArmed();
  if (armed) lastArmedAt = now;
  digitalWrite(PIN_LED, armed ? HIGH : LOW);
  float g = readG();

  switch (state) {

    case IDLE:
      // armed, or armed a moment ago (a crash can throw the strap open)
      if (g > IMPACT_G && (armed || (now - lastArmedAt) < ARM_GRACE_MS)) {
        Serial.print("Impact: "); Serial.print(g, 2); Serial.println(" g");
        impactAt = now;
        stillSince = 0;
        state = STILL_CHECK;
      }
      break;

    case STILL_CHECK:
      if (cancelPressed()) {
        Serial.println("Cancelled during the check.");
        cooldownAt = now;
        state = COOLDOWN;
        break;
      }
      if (g >= 0) {
        if (fabsf(g - 1.0f) < STILL_BAND_G) {
          if (stillSince == 0) stillSince = now;
          if (now - stillSince >= STILL_TIME_MS) {
            Serial.println("Still after the impact: warning the rider.");
            warnAt = now;
            phoneStop = false;
            crashSent = false;
            notifyPhone("WARN");      // the app shows its own countdown with an I'm OK button
            state = WARNING;
          }
        } else {
          stillSince = 0;          // still moving, restart the stillness timer
        }
      }
      if (state == STILL_CHECK && now - impactAt > STILL_WAIT_MAX_MS) {
        Serial.println("Helmet kept moving: not a crash.");
        state = IDLE;
      }
      break;

    case WARNING:
      digitalWrite(PIN_BUZZER, ((now / 300) % 2 == 0) ? HIGH : LOW);   // beeping
      if (cancelPressed() || phoneStop) {
        Serial.println(phoneStop ? "Cancelled from the phone." : "Cancelled by the rider.");
        digitalWrite(PIN_BUZZER, LOW);
        if (!phoneStop) notifyPhone("OK");   // tell the app to drop its countdown
        phoneStop = false;
        cooldownAt = now;
        state = COOLDOWN;
      } else if (now - warnAt >= WARN_TIME_MS) {
        digitalWrite(PIN_BUZZER, LOW);
        sendCrashToPhone();
        cooldownAt = millis();
        state = COOLDOWN;
      }
      break;

    case COOLDOWN:
      // Button after the alert went out: tell the app to stop the calls and send the all-clear.
      if (crashSent && cancelPressed()) {
        Serial.println("Button pressed after the alert: telling the app the rider is OK.");
        notifyPhone("OK");
        crashSent = false;
      }
      if (now - cooldownAt >= COOLDOWN_MS) {
        Serial.println("Ready again.");
        crashSent = false;
        state = IDLE;
      }
      break;
  }

  delay(20);
}
