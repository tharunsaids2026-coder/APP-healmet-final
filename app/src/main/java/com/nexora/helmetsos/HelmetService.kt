package com.nexora.helmetsos

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** What the service is doing right now. The screen shows this, so the person always knows. */
enum class Phase { STOPPED, SEARCHING, ARMED, COUNTDOWN, SENDING, CALLING, DONE, CANCELLED, ERROR }

/** Live state shared with the screen (read every half second). */
object Live {
    @Volatile var running = false
    @Volatile var phase = Phase.STOPPED
    @Volatile var headline = "Protection is off"
    @Volatile var detail = "Tap Start protection to begin watching for a crash."
    @Volatile var helmetOk = false
    @Volatile var helmetText = "Not connected"
    @Volatile var fixTime = 0L
    @Volatile var fixAcc = -1f
    @Volatile var countdownEndsAt = 0L
    @Volatile var alertOpen = false
    @Volatile var alertIsTest = false
    @Volatile var tgState = ""
    @Volatile var smsState = ""
    @Volatile var callState = ""
    val log = CopyOnWriteArrayList<String>()
}

/**
 * Foreground service: keeps a Bluetooth LE link to the helmet and the phone's GPS warm.
 * When the helmet sends "CRASH": short countdown (cancel from the phone) -> SMS + Telegram + calls.
 */
class HelmetService : Service() {

    companion object {
        const val ACTION_START = "com.nexora.helmetsos.START"
        const val ACTION_TEST = "com.nexora.helmetsos.TEST"
        const val ACTION_CANCEL = "com.nexora.helmetsos.CANCEL"
        const val HELMET_NAME = "SmartHelmet"
        private const val ACTION_SMS_SENT = "com.nexora.helmetsos.SMS_SENT"
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private const val HELMET_WINDOW_MS = 10_000L   // the helmet's own buzzer window
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CHANNEL = "helmet_sos"
        private const val ALERT_CHANNEL = "helmet_alert"
        private const val NOTIF_ID = 1
        private const val ALERT_NOTIF_ID = 2
    }

    private class Cfg(
        val token: String, val chats: List<String>, val phones: List<String>,
        val tgOn: Boolean, val smsOn: Boolean, val callOn: Boolean, val countdown: Int
    )

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var helmetConnected = false
    @Volatile private var lastFix: Location? = null
    private var lastSent = 0L
    private lateinit var locationManager: LocationManager

    // alert state (touched on the main thread only, except alertGen)
    private var countingDown = false
    private var preAlert = false          // countdown started by the helmet's WARN (sending waits for CRASH)
    @Volatile private var rxChar: BluetoothGattCharacteristic? = null
    private var sending = false
    @Volatile private var alertGen = 0
    private var smsTotal = 0
    private var smsOk = 0
    private var smsFail = 0
    private var smsReq = 100

    // ---------------- lifecycle ----------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            logEvent("Could not start the background service. Allow precise Location, Nearby devices and Notifications, then try again.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            Live.running = true
            registerSmsReceiver()
            startLocation()
            startScan()
        }
        when (intent?.action) {
            ACTION_TEST -> handler.post { beginAlert(true) }
            ACTION_CANCEL -> handler.post { cancelAlert() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        countingDown = false
        alertGen++
        handler.removeCallbacksAndMessages(null)
        unregisterCallWatcher()
        try { unregisterReceiver(smsReceiver) } catch (_: Exception) {}
        stopScan()
        closeGatt()
        stopLocation()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ALERT_NOTIF_ID)
        Live.running = false
        Live.alertOpen = false
        Live.helmetOk = false
        Live.helmetText = "Not connected"
        Live.phase = Phase.STOPPED
        Live.headline = "Protection is off"
        Live.detail = "Tap Start protection to begin watching for a crash."
        logEvent("Protection stopped")
        super.onDestroy()
    }

    private fun goForeground(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Helmet SOS status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Crash alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                description = "Shown when a crash is detected so you can cancel it"
            }
        )
        val n = buildNotification(Live.headline)
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Helmet SOS")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    /** Heads-up notification with an "I'm OK" button, so the alert can be stopped from the lock screen. */
    private fun postAlertNotification(title: String, text: String, countdownEnd: Long) {
        val cancelPi = PendingIntent.getService(
            this, 2, Intent(this, HelmetService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java).putExtra("alert", true),
            PendingIntent.FLAG_IMMUTABLE
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "I'm OK - stop alert", cancelPi
        ).build()
        val b = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(action)
            .setOngoing(true)
        if (countdownEnd > 0L) {
            b.setWhen(countdownEnd).setUsesChronometer(true).setChronometerCountDown(true)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ALERT_NOTIF_ID, b.build())
    }

    private fun launchUi() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("alert", true)
            )
        } catch (_: Exception) {}
    }

    // ---------------- status + log ----------------

    private fun logEvent(text: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        Live.log.add(0, "$t  $text")
        while (Live.log.size > 40) Live.log.removeAt(Live.log.size - 1)
    }

    private fun setStatus(phase: Phase, head: String, detail: String, logIt: Boolean = true) {
        if (logIt && head != Live.headline) logEvent(head)
        Live.phase = phase
        Live.headline = head
        Live.detail = detail
        handler.post {
            if (running) {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIF_ID, buildNotification(head))
            }
        }
    }

    /** Helmet link changed. Only touches the headline when no alert is in progress. */
    private fun setLink(ok: Boolean, text: String, phase: Phase, head: String, detail: String) {
        handler.post {
            helmetConnected = ok
            Live.helmetOk = ok
            Live.helmetText = text
            if (!countingDown && !Live.alertOpen) setStatus(phase, head, detail)
        }
    }

    private fun idleStatus() {
        if (countingDown || Live.alertOpen || !running) return
        if (helmetConnected) setStatus(Phase.ARMED, "Protection is on", "Helmet connected. Watching for a crash.")
        else setStatus(Phase.SEARCHING, "Looking for your helmet", "Switch the helmet on and keep it near the phone.")
    }

    private val calmTask = Runnable { idleStatus() }
    private val clearAlert = Runnable {
        Live.alertOpen = false
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ALERT_NOTIF_ID)
        idleStatus()
    }

    // ---------------- settings ----------------

    private fun split(s: String) = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun cfg(): Cfg {
        val p = getSharedPreferences("helmet", MODE_PRIVATE)
        val token = p.getString("token", "").orEmpty()
        val chats = split(p.getString("chat", "").orEmpty())
        val phones = split(p.getString("phone", "").orEmpty())
        return Cfg(
            token, chats, phones,
            p.getBoolean("tg_on", true) && token.isNotEmpty() && chats.isNotEmpty(),
            p.getBoolean("sms_on", true) && phones.isNotEmpty(),
            p.getBoolean("call_on", true) && phones.isNotEmpty(),
            p.getInt("countdown", 10).coerceIn(0, 60)
        )
    }

    // ---------------- location (kept warm, never sent unless a crash happens) ----------------

    private fun setFix(l: Location?) {
        lastFix = l
        if (l != null) {
            Live.fixTime = l.time
            Live.fixAcc = l.accuracy
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val cur = lastFix
            if (cur == null || location.accuracy <= cur.accuracy + 20f || location.time - cur.time > 30_000L) {
                setFix(location)
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            var any = false
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (locationManager.isProviderEnabled(p)) {
                    any = true
                    locationManager.requestLocationUpdates(p, 5000L, 0f, locationListener, Looper.getMainLooper())
                }
            }
            if (!any) logEvent("Location is switched off on the phone. Turn it on.")
            setFix(
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            )
        } catch (e: SecurityException) {
            logEvent("Location permission is missing")
        }
    }

    private fun stopLocation() {
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
    }

    // ---------------- Bluetooth LE ----------------

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rec = result.scanRecord
            val byName = rec?.deviceName == HELMET_NAME
            val byUuid = rec?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
            if ((byName || byUuid) && gatt == null) {
                stopScan()
                setLink(false, "Connecting...", Phase.SEARCHING, "Helmet found", "Connecting to the helmet...")
                val g = result.device.connectGatt(this@HelmetService, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
                gatt = g
                // If the connection never completes, drop it and search again.
                handler.postDelayed({
                    if (running && gatt === g && !helmetConnected) {
                        logEvent("Helmet connection timed out, trying again")
                        closeGatt()
                        startScan()
                    }
                }, 20_000L)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            setLink(false, "Scan failed ($errorCode)", Phase.ERROR, "Bluetooth scan failed", "Error $errorCode. Retrying...")
            retryScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!running || scanning || gatt != null) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            setLink(false, "Bluetooth is off", Phase.ERROR, "Bluetooth is off", "Turn Bluetooth on. Retrying...")
            retryScan()
            return
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // A filter is required: Android pauses unfiltered scans when the screen is off.
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        try {
            adapter.bluetoothLeScanner.startScan(filters, settings, scanCallback)
            scanning = true
            setLink(false, "Searching...", Phase.SEARCHING, "Looking for your helmet", "Switch the helmet on and keep it near the phone.")
        } catch (e: Exception) {
            setLink(false, "Cannot scan", Phase.ERROR, "Cannot scan for the helmet", e.message ?: "Check Bluetooth permission.")
            retryScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        try {
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        scanning = false
    }

    private fun retryScan() {
        handler.postDelayed({ startScan() }, 3000)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val g = gatt
        gatt = null
        // disconnect() first, so the helmet really sees the link drop and starts advertising again.
        // close() alone can leave the helmet thinking a phone is still connected.
        try { g?.disconnect() } catch (_: Exception) {}
        try { g?.close() } catch (_: Exception) {}
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Short pause, and clear Android's cached service list, so a re-flashed helmet is seen correctly.
                handler.postDelayed({
                    if (running && gatt === g) {
                        try { g.javaClass.getMethod("refresh").invoke(g) } catch (_: Exception) {}
                        g.discoverServices()
                    }
                }, 600L)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
                gatt = null
                rxChar = null
                if (running) {
                    setLink(false, "Disconnected", Phase.SEARCHING, "Helmet disconnected", "Reconnecting...")
                    retryScan()
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val ch = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
            val cccd = ch?.getDescriptor(CCCD_UUID)
            if (st != BluetoothGatt.GATT_SUCCESS || ch == null || cccd == null) {
                logEvent("Helmet service not found (status $st), reconnecting")
                setLink(false, "Helmet service not found", Phase.ERROR, "Helmet service not found", "Reconnecting...")
                handler.post { if (running && gatt === g) { closeGatt(); retryScan() } }
                return
            }
            rxChar = g.getService(SERVICE_UUID)?.getCharacteristic(RX_UUID)
            if (rxChar == null) logEvent("Helmet firmware has no phone-stop channel. Update the helmet code.")
            g.setCharacteristicNotification(ch, true)
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!g.writeDescriptor(cccd)) logEvent("Could not switch on helmet notifications")
            // "Connected" is shown only after the helmet accepts this (see onDescriptorWrite below).
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            if (st == BluetoothGatt.GATT_SUCCESS) {
                logEvent("Helmet notifications are on")
                setLink(true, "Connected", Phase.ARMED, "Protection is on", "Helmet connected. Watching for a crash.")
            } else {
                logEvent("Helmet notifications failed (status $st), reconnecting")
                handler.post { if (running && gatt === g) { closeGatt(); retryScan() } }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleHelmetMessage(ch.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleHelmetMessage(value)
        }
    }

    private fun handleHelmetMessage(value: ByteArray) {
        val text = String(value, Charsets.UTF_8).trim().uppercase(Locale.ROOT)
        when {
            text.contains("CRASH") -> handler.post {
                if (countingDown && preAlert) {
                    // The helmet's 10 s window ended and nobody cancelled: send now.
                    countingDown = false
                    preAlert = false
                    handler.removeCallbacks(tick)
                    fireAlert(false)
                } else beginAlert(false)
            }
            text.contains("WARN") -> handler.post { beginPreAlert() }
            text == "OK" -> handler.post {
                if (countingDown && preAlert) endPreAlert("Cancelled with the helmet button", "Nothing was sent.")
                else if (Live.alertOpen && !Live.alertIsTest) cancelAlert()   // after sending: stop calls, send all-clear
            }
        }
    }

    /** Helmet started its buzzer window: show the cancel screen now. Nothing is sent until CRASH arrives. */
    private fun beginPreAlert() {
        if (countingDown) return
        val now = System.currentTimeMillis()
        handler.removeCallbacks(calmTask)
        handler.removeCallbacks(clearAlert)
        Live.alertIsTest = false
        Live.alertOpen = false
        Live.tgState = ""; Live.smsState = ""; Live.callState = ""
        countingDown = true
        preAlert = true
        Live.countdownEndsAt = now + HELMET_WINDOW_MS
        setStatus(Phase.COUNTDOWN, "Helmet warning: sending in 10s", "Tap \"I'm OK\" or press the helmet button to cancel.")
        postAlertNotification("Helmet warning", "Crash suspected. Tap I'm OK to cancel.", Live.countdownEndsAt)
        launchUi()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun endPreAlert(head: String, detail: String) {
        if (!countingDown || !preAlert) return
        countingDown = false
        preAlert = false
        handler.removeCallbacks(tick)
        alertGen++
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ALERT_NOTIF_ID)
        setStatus(Phase.CANCELLED, head, detail)
        handler.postDelayed(calmTask, 6000)
    }

    /** Tells the helmet the rider is OK so it silences its buzzer and cancels. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun sendStopToHelmet() {
        val g = gatt
        val ch = rxChar
        if (g == null || ch == null) {
            logEvent("Could not reach the helmet to stop its buzzer. Press the helmet button.")
            return
        }
        try {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = "STOP".toByteArray()
            g.writeCharacteristic(ch)
            logEvent("Told the helmet to stop its buzzer")
        } catch (e: Exception) {
            logEvent("Stop message failed: ${e.message}")
        }
    }

    // ---------------- alert flow: countdown -> send -> (cancel any time) ----------------

    private val tick = object : Runnable {
        override fun run() {
            if (!countingDown) return
            val left = ((Live.countdownEndsAt - System.currentTimeMillis() + 999L) / 1000L).toInt()
            if (left <= 0 && preAlert) {
                // Waiting for the helmet's CRASH message; give up if it never comes.
                if (System.currentTimeMillis() - Live.countdownEndsAt > 20_000L) {
                    endPreAlert("Helmet warning ended", "No crash alert followed, so nothing was sent.")
                    return
                }
                setStatus(Phase.COUNTDOWN, "Waiting for the helmet...", "Tap \"I'm OK\" to cancel.", false)
                handler.postDelayed(this, 1000)
                return
            }
            if (left <= 0) {
                countingDown = false
                fireAlert(Live.alertIsTest)
                return
            }
            val pre = if (preAlert) "Helmet warning" else if (Live.alertIsTest) "Test alert" else "Crash detected"
            setStatus(Phase.COUNTDOWN, "$pre: sending in ${left}s", "Tap \"I'm OK\" to cancel. Nothing is sent until the timer ends.", false)
            handler.postDelayed(this, 1000)
        }
    }

    private fun beginAlert(test: Boolean) {
        if (countingDown) return
        val now = System.currentTimeMillis()
        if (!test && now - lastSent < 30_000L) return
        val c = cfg()
        handler.removeCallbacks(calmTask)
        handler.removeCallbacks(clearAlert)
        Live.alertIsTest = test
        Live.alertOpen = false
        preAlert = false
        Live.tgState = ""; Live.smsState = ""; Live.callState = ""
        if (c.countdown <= 0) {
            fireAlert(test)
            return
        }
        countingDown = true
        Live.countdownEndsAt = now + c.countdown * 1000L
        val pre = if (test) "Test alert" else "Crash detected"
        setStatus(Phase.COUNTDOWN, "$pre: sending in ${c.countdown}s", "Tap \"I'm OK\" to cancel. Nothing is sent until the timer ends.")
        postAlertNotification(pre, "Sending to your family soon. Tap I'm OK to cancel.", Live.countdownEndsAt)
        if (!test) launchUi()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun fireAlert(test: Boolean) {
        val c = cfg()
        lastSent = System.currentTimeMillis()
        Live.alertOpen = true
        Live.alertIsTest = test
        val gen = ++alertGen
        handler.removeCallbacks(clearAlert)
        handler.postDelayed(clearAlert, 15 * 60_000L)

        val callOn = c.callOn && !test
        Live.tgState = if (c.tgOn) "Sending..." else "Off"
        Live.smsState = if (c.smsOn) "Sending..." else "Off"
        Live.callState = if (callOn) "Starting..." else if (test) "Skipped for tests" else "Off"
        smsTotal = 0; smsOk = 0; smsFail = 0

        if (!c.tgOn && !c.smsOn && !callOn) {
            Live.alertOpen = false
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ALERT_NOTIF_ID)
            setStatus(Phase.ERROR, "Nothing to send", "Add a Telegram chat or a phone number in Settings.")
            return
        }

        postAlertNotification(
            if (test) "Test alert sent" else "Crash alert sent",
            "Family is being alerted. Tap I'm OK if this is a false alarm.", 0L
        )
        sending = c.tgOn || c.smsOn
        setStatus(Phase.SENDING, if (test) "Sending test alert" else "Sending crash alert", "Details below.")
        if (callOn) startCalls(c.phones)
        if (!sending) { refreshAlert(); return }

        Thread {
            var loc = lastFix
            var waited = 0
            while (loc == null && waited < 10_000 && gen == alertGen) {
                Thread.sleep(500)
                waited += 500
                loc = lastFix
            }
            if (gen != alertGen) return@Thread
            val fix = loc
            if (c.smsOn) handler.post { if (gen == alertGen) sendSms(c.phones, smsBody(test, fix), true) }

            var tgResult = ""
            if (c.tgOn) {
                val body = alertText(test, fix)
                var ok = 0
                var lastErr: String? = null
                for (ch in c.chats) {
                    if (gen != alertGen) return@Thread
                    try {
                        post(c.token, "sendMessage", mapOf("chat_id" to ch, "text" to body))
                        if (fix != null) {
                            post(c.token, "sendLocation", mapOf(
                                "chat_id" to ch,
                                "latitude" to String.format(Locale.US, "%.6f", fix.latitude),
                                "longitude" to String.format(Locale.US, "%.6f", fix.longitude)
                            ))
                        }
                        ok++
                    } catch (e: Exception) {
                        lastErr = e.message
                    }
                }
                tgResult = if (ok > 0) "Sent to $ok of ${c.chats.size} chats" else "Failed: ${lastErr ?: "no internet?"}"
            }
            handler.post {
                if (gen != alertGen) return@post
                if (c.tgOn) {
                    Live.tgState = tgResult
                    logEvent("Telegram: $tgResult")
                }
                sending = false
                refreshAlert()
            }
        }.start()
    }

    /** Works out the headline from the state of Telegram, SMS and calls. */
    private fun refreshAlert() {
        if (!Live.alertOpen) return
        val smsBusy = smsTotal > 0 && smsOk + smsFail < smsTotal
        val test = Live.alertIsTest
        if (sending || callActive || smsBusy) {
            if (callActive) setStatus(Phase.CALLING, "Calling your family", "Alerts are going out. Details below.")
            else setStatus(Phase.SENDING, if (test) "Sending test alert" else "Sending crash alert", "Details below.")
        } else {
            val ok = Live.tgState.startsWith("Sent") || Live.smsState.startsWith("Sent") || Live.callState == "Answered"
            if (ok) {
                setStatus(Phase.DONE, if (test) "Test alert delivered" else "Alert delivered",
                    "Tap \"I'm OK\" if this was a false alarm.")
            } else {
                setStatus(Phase.ERROR, "Alert could not be sent", "Check signal, Telegram details and SMS permission.")
            }
        }
    }

    /** "I'm OK": stops the countdown, or stops the calls and tells the family it was a false alarm. */
    private fun cancelAlert() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ALERT_NOTIF_ID)
        if (countingDown) {
            if (preAlert) sendStopToHelmet()
            countingDown = false
            preAlert = false
            handler.removeCallbacks(tick)
            alertGen++
            Live.alertOpen = false
            Live.tgState = ""; Live.smsState = ""; Live.callState = ""
            setStatus(Phase.CANCELLED, "Alert cancelled", "You said you're OK. Nothing was sent.")
            handler.postDelayed(calmTask, 6000)
            return
        }
        if (!Live.alertOpen) return
        val test = Live.alertIsTest
        alertGen++
        sending = false
        stopCalls()
        Live.alertOpen = false
        handler.removeCallbacks(clearAlert)
        if (test) {
            setStatus(Phase.CANCELLED, "Test stopped", "")
            handler.postDelayed(calmTask, 5000)
            return
        }
        val c = cfg()
        val msg = "ALL CLEAR: the rider says they are OK. Please ignore the earlier crash alert."
        if (c.tgOn) {
            Thread {
                var ok = 0
                for (ch in c.chats) {
                    try { post(c.token, "sendMessage", mapOf("chat_id" to ch, "text" to msg)); ok++ } catch (_: Exception) {}
                }
                handler.post { logEvent("All-clear sent on Telegram ($ok of ${c.chats.size})") }
            }.start()
        }
        if (c.smsOn) {
            sendSms(c.phones, msg, false)
            logEvent("All-clear sent by SMS")
        }
        setStatus(Phase.CANCELLED, "Alert stopped", "Calls stopped. Your family was told it was a false alarm.")
        handler.postDelayed(calmTask, 8000)
    }

    private fun alertText(test: Boolean, loc: Location?): String {
        val sb = StringBuilder(
            if (test) "TEST: simulated crash alert from Helmet SOS." else "CRASH DETECTED on the Smart Helmet."
        )
        if (loc != null) {
            val ageMin = (System.currentTimeMillis() - loc.time) / 60_000L
            sb.append("\nLocation: https://maps.google.com/?q=")
                .append(String.format(Locale.US, "%.6f,%.6f", loc.latitude, loc.longitude))
                .append("\nAccuracy about ").append(loc.accuracy.toInt()).append(" m")
            if (ageMin >= 2) sb.append(", last fix ").append(ageMin).append(" min ago")
        } else {
            sb.append("\nLocation not available.")
        }
        return sb.toString()
    }

    private fun smsBody(test: Boolean, loc: Location?): String {
        val head = if (test) "TEST from Helmet SOS: simulated crash." else "SOS: crash detected on the Smart Helmet."
        if (loc == null) return "$head Location not available."
        val ageMin = (System.currentTimeMillis() - loc.time) / 60_000L
        val sb = StringBuilder(head)
            .append(" Location: https://maps.google.com/?q=")
            .append(String.format(Locale.US, "%.6f,%.6f", loc.latitude, loc.longitude))
            .append(" (about ").append(loc.accuracy.toInt()).append(" m")
        if (ageMin >= 2) sb.append(", ").append(ageMin).append(" min old")
        return sb.append(")").toString()
    }

    // ---------------- SMS ----------------

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val to = i.getStringExtra("to") ?: "?"
            if (resultCode == Activity.RESULT_OK) {
                smsOk++
                logEvent("SMS sent to $to")
            } else {
                smsFail++
                logEvent("SMS to $to failed (code $resultCode)")
            }
            updateSmsState()
            refreshAlert()
        }
    }

    private fun registerSmsReceiver() {
        val f = IntentFilter(ACTION_SMS_SENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(smsReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(smsReceiver, f)
    }

    private fun updateSmsState() {
        Live.smsState = when {
            smsOk + smsFail < smsTotal -> "Sending... ($smsOk of $smsTotal sent)"
            smsFail == 0 -> "Sent to $smsOk of $smsTotal"
            smsOk == 0 -> "Failed for all $smsTotal"
            else -> "Sent to $smsOk of $smsTotal ($smsFail failed)"
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    @SuppressLint("MissingPermission")
    private fun sendSms(numbers: List<String>, text: String, track: Boolean) {
        if (numbers.isEmpty()) return
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            if (track) {
                Live.smsState = "Failed: SMS permission missing"
                logEvent("SMS: permission missing")
                refreshAlert()
            }
            return
        }
        val sm = try { smsManager() } catch (e: Exception) { null }
        if (sm == null) {
            if (track) {
                Live.smsState = "Failed: SMS not available on this phone"
                refreshAlert()
            }
            return
        }
        if (track) {
            smsTotal = numbers.size; smsOk = 0; smsFail = 0
            Live.smsState = "Sending... (0 of ${numbers.size} sent)"
        }
        for (n in numbers) {
            try {
                val parts = sm.divideMessage(text)
                var sent: ArrayList<PendingIntent?>? = null
                if (track) {
                    val list = ArrayList<PendingIntent?>()
                    for (i in parts.indices) {
                        list.add(
                            if (i == parts.size - 1) PendingIntent.getBroadcast(
                                this, smsReq++,
                                Intent(ACTION_SMS_SENT).setPackage(packageName).putExtra("to", n),
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            ) else null
                        )
                    }
                    sent = list
                }
                sm.sendMultipartTextMessage(n, null, parts, sent, null)
            } catch (e: Exception) {
                if (track) {
                    smsFail++
                    logEvent("SMS to $n failed: ${e.message}")
                    updateSmsState()
                }
            }
        }
        if (track) refreshAlert()
    }

    // ---------------- Phone calls (one number after another until someone answers) ----------------

    private var callQueue: List<String> = emptyList()
    private var callIdx = 0
    private var callActive = false
    private var callPhase = 0          // 0 idle, 1 dialing, 2 in call
    private var callGen = 0
    private var callStart = 0L
    private var offhookAt = 0L
    private var telephony: TelephonyManager? = null
    private var legacyListener: PhoneStateListener? = null
    private var modernCallback: TelephonyCallback? = null

    private fun callStatus(text: String) {
        Live.callState = text
        logEvent("Call: $text")
        refreshAlert()
    }

    private fun startCalls(list: List<String>) {
        if (callActive || list.isEmpty()) return
        val canWatch = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            registerCallWatcher()
        if (!canWatch) {
            // Cannot tell whether a call was answered, so only call the first number.
            val err = placeCall(list[0])
            callStatus(err ?: "Calling ${list[0]} (first number only, answer not detected)")
            return
        }
        callQueue = list
        callIdx = 0
        callActive = true
        dialNext()
    }

    private fun dialNext() {
        val total = callQueue.size * 2            // each number is tried up to twice
        if (callIdx >= total) {
            callActive = false
            callPhase = 0
            callStatus("Nobody answered")
            return
        }
        val number = callQueue[callIdx % callQueue.size]
        callIdx++
        callPhase = 1
        callStart = System.currentTimeMillis()
        val gen = ++callGen
        callStatus("Calling $number ($callIdx of $total)")
        val err = placeCall(number)
        if (err != null) {
            callStatus(err)
            handler.post { if (gen == callGen) dialNext() }
            return
        }
        // If the call never starts, move on after 20 seconds. But if the phone line is busy the call
        // IS in progress (answered or still ringing): never dial again on top of it.
        handler.postDelayed({
            if (gen == callGen && callPhase == 1) {
                if (isLineBusy()) {
                    callPhase = 2
                    offhookAt = System.currentTimeMillis() - 20_000L
                } else {
                    dialNext()
                }
            }
        }, 20_000L)
    }

    /** True while the phone is dialing, ringing out or in a call. False if idle or unknown. */
    @Suppress("DEPRECATION")
    private fun isLineBusy(): Boolean = try {
        val t = telephony
        t != null && t.callState != TelephonyManager.CALL_STATE_IDLE
    } catch (e: Exception) {
        false
    }

    private fun onCallState(state: Int) {
        if (!callActive) return
        if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            if (callPhase == 1) {
                callPhase = 2
                offhookAt = System.currentTimeMillis()
            }
        } else if (state == TelephonyManager.CALL_STATE_IDLE && callPhase == 2) {
            callPhase = 0
            val connectedMs = System.currentTimeMillis() - offhookAt
            val gen = callGen
            // Give Android a moment to write the call log entry.
            handler.postDelayed({ if (gen == callGen) checkAnswered(connectedMs, 0) }, 2500L)
        }
    }

    /**
     * Reads the call log a few times (it is often written late) before deciding.
     * Duration > 0 means someone answered, so stop calling immediately.
     */
    private fun checkAnswered(connectedMs: Long, attempt: Int) {
        if (!callActive) return
        val gen = callGen
        val d = lastOutgoingDuration()
        if (d > 0L) {
            callActive = false
            callStatus("Answered")
            return
        }
        if (attempt < 4) {
            handler.postDelayed({ if (gen == callGen) checkAnswered(connectedMs, attempt + 1) }, 2000L)
            return
        }
        // Final decision. Log says 0 s -> nobody answered. No log entry / unreadable -> judge by call length.
        val answered = if (d == 0L) false else connectedMs > 15_000L
        if (answered) {
            callActive = false
            callStatus("Answered")
        } else {
            callStatus("No answer, trying the next number...")
            handler.postDelayed({ if (gen == callGen && callActive) dialNext() }, 1500L)
        }
    }

    /** Duration (s) of the latest outgoing call since this dial, or -1 if not found or unreadable. */
    private fun lastOutgoingDuration(): Long {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return -1L
        var duration = -1L
        try {
            val c = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.DURATION),
                CallLog.Calls.DATE + " >= ? AND " + CallLog.Calls.TYPE + " = ?",
                arrayOf((callStart - 5000L).toString(), CallLog.Calls.OUTGOING_TYPE.toString()),
                CallLog.Calls.DATE + " DESC"
            )
            if (c != null) {
                if (c.moveToFirst()) duration = c.getLong(0)
                c.close()
            }
        } catch (e: Exception) {
            duration = -1L
        }
        return duration
    }

    /** Stops the call sequence and hangs up a call that is still ringing. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun stopCalls() {
        val wasCalling = callActive || Live.callState.startsWith("Calling") || Live.callState == "Starting..."
        callGen++
        callActive = false
        callPhase = 0
        if (wasCalling) {
            try {
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    (getSystemService(TELECOM_SERVICE) as TelecomManager).endCall()
                }
            } catch (_: Exception) {}
            Live.callState = "Stopped by you"
            logEvent("Call: stopped by you")
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun registerCallWatcher(): Boolean {
        if (telephony != null) return true
        return try {
            val t = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) { onCallState(state) }
                }
                t.registerTelephonyCallback(mainExecutor, cb)
                modernCallback = cb
            } else {
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) { onCallState(state) }
                }
                t.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                legacyListener = l
            }
            telephony = t
            true
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterCallWatcher() {
        try {
            val t = telephony ?: return
            if (Build.VERSION.SDK_INT >= 31) {
                modernCallback?.let { t.unregisterTelephonyCallback(it) }
            } else {
                legacyListener?.let { t.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (_: Exception) {}
        telephony = null
        modernCallback = null
        legacyListener = null
    }

    /** Places one call. Returns null on success, or a short error message. */
    @SuppressLint("MissingPermission")
    private fun placeCall(number: String): String? {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "Not placed: Phone permission missing"
        }
        val uri = Uri.fromParts("tel", number, null)
        return try {
            (getSystemService(TELECOM_SERVICE) as TelecomManager).placeCall(uri, Bundle())
            null
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            } catch (e2: Exception) {
                "Call failed: ${e2.message}"
            }
        }
    }

    // ---------------- Telegram ----------------

    private fun post(token: String, method: String, params: Map<String, String>) {
        val body = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val c = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) throw RuntimeException("Telegram replied ${c.responseCode}")
        } finally {
            c.disconnect()
        }
    }
}
