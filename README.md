# Helmet SOS (Android)

Background Android app for the Smart Helmet. It keeps a Bluetooth LE link to the ESP32
("SmartHelmet", see helmet_ble.ino). When the helmet sends "CRASH" the phone:

1. at the start of the helmet's 10 s buzzer window ("WARN") shows a big **I'm OK** button with a countdown
   (also on the notification / lock screen). Tapping it stops the helmet's buzzer too ("STOP"),
2. if nobody cancels, the helmet sends "CRASH" and the phone sends the location by **SMS** and **Telegram**
   and **calls** the family numbers one by one until someone answers,
3. if you tap **I'm OK** afterwards, it stops the calls and tells the family it was a false alarm.

## Get the APK (no Android Studio needed)
1. Create a GitHub repository and upload everything in this folder (keep the .github folder).
2. Open the repository's Actions tab, run "Build APK" (it also runs on every push).
3. Download the artifact "HelmetSOS-apk", unzip it, copy app-debug.apk to the phone and install it.

## Helmet firmware
`firmware/smart_helmet_final/smart_helmet_final.ino` (ESP32 DevKit, Arduino IDE, built-in libraries only).
Pressing the helmet button also stops everything, even after the alert has gone out (calls stop, family gets an all-clear).
Helmet to phone: "WARN", "OK" (helmet button pressed), "CRASH". Phone to helmet: "STOP".
If the phone is not connected when the crash is confirmed, the helmet keeps the CRASH and sends it when the phone reconnects (up to 2 minutes).
Upload the new firmware, then install the new APK. An old firmware still works, but the phone then shows its own countdown after CRASH.

## First run
The app opens a setup screen: Telegram bot token, family chat ID(s) and family phone number(s).
Everything can be changed later with the gear icon (Settings), where you can also switch Telegram / SMS / calls
on or off and set the countdown length.

Then tap "Save and start protection" and allow Location, Nearby devices, SMS, Phone and Notifications.
If Android blocks SMS or call-log permission for a sideloaded app: App info > menu > "Allow restricted settings".
Set the app to Unrestricted in the battery list.

## Status screen
The top card says what is happening (looking for helmet, protection on, countdown, sending, calling, delivered...).
Below it: helmet link, GPS fix, which channels are on, permissions, battery, delivery result per channel, and a timestamped activity log.

"Send test alert" runs the whole flow with Telegram + SMS only (no calls) and can be cancelled during the countdown.
