package com.nexora.helmetsos

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

private val BG = Color.parseColor("#0E0E10")
private val CARD = Color.parseColor("#1C1C20")
private val CARD2 = Color.parseColor("#2A2A30")
private val WHITE = Color.parseColor("#FFFFFF")
private val MUTED = Color.parseColor("#9A9AA3")
private val HINT = Color.parseColor("#5E5E66")
private val ORANGE = Color.parseColor("#FF5A2B")
private val GREEN = Color.parseColor("#34C759")
private val AMBER = Color.parseColor("#FFB020")
private val RED = Color.parseColor("#FF453A")
private val BLUE = Color.parseColor("#5AA9FF")

class MainActivity : Activity() {

    private var screen = ""
    private var pendingAction: String? = null
    private val ui = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refreshHome()
            ui.postDelayed(this, 500)
        }
    }

    // form fields (setup + settings)
    private lateinit var fToken: EditText
    private lateinit var fChat: EditText
    private lateinit var fPhone: EditText
    private var fCount: EditText? = null
    private var sTg: Switch? = null
    private var sSms: Switch? = null
    private var sCall: Switch? = null

    // home views
    private lateinit var heroCard: LinearLayout
    private lateinit var heroDot: View
    private lateinit var heroTitle: TextView
    private lateinit var heroDetail: TextView
    private lateinit var cancelBtn: LinearLayout
    private lateinit var startBtn: LinearLayout
    private lateinit var helmetVal: TextView
    private lateinit var gpsVal: TextView
    private lateinit var sendVal: TextView
    private lateinit var permVal: TextView
    private lateinit var battVal: TextView
    private lateinit var deliveryCard: LinearLayout
    private lateinit var tgVal: TextView
    private lateinit var smsVal: TextView
    private lateinit var callVal: TextView
    private lateinit var logView: TextView

    // ---------------- helpers ----------------

    private fun prefs() = getSharedPreferences("helmet", MODE_PRIVATE)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun shape(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(top), 0, dp(bottom)) }

    private fun tv(t: String, size: Float = 14f, color: Int = WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(22), dp(18), dp(28))
    }

    private fun wrap(col: View) = ScrollView(this).apply {
        setBackgroundColor(BG)
        isFillViewport = true
        addView(col)
    }

    private fun card(top: Int = 14) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 26)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        layoutParams = lp(top = top)
    }

    private fun accentBar() = View(this).apply {
        background = shape(ORANGE, 4)
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(4)
        }
    }

    /** Rounded pill button like the reference design. Title TextView is child 1. */
    private fun pill(icon: String, title: String, fill: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape(fill, 40)
        setPadding(dp(22), dp(16), dp(22), dp(16))
        layoutParams = lp(top = 12)
        isClickable = true
        setOnClickListener { onClick() }
        addView(tv(icon, 18f))
        addView(tv(title, 16f, WHITE, true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(tv("\u00BB", 20f, if (fill == CARD2) MUTED else WHITE, true))
    }

    private fun setPillText(p: LinearLayout, t: String) {
        (p.getChildAt(1) as TextView).text = t
    }

    private fun bullet(icon: String, text: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape(CARD2, 16)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = lp(top = 8)
        addView(tv(icon, 18f).apply { setPadding(0, 0, dp(12), 0) })
        addView(tv(text, 13f, WHITE))
    }

    private fun infoRow(parent: LinearLayout, label: String): TextView {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        r.addView(tv(label, 14f, MUTED).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.36f)
        })
        val v = tv("", 14f, WHITE, true).apply {
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.64f)
        }
        r.addView(v)
        parent.addView(r)
        return v
    }

    private fun setVal(v: TextView, text: String, color: Int) {
        v.text = text
        v.setTextColor(color)
    }

    private fun field(label: String, value: String, hint: String, type: Int, help: String, secret: Boolean = false): EditText {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(top = 14)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(tv(label, 13f, WHITE, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val et = EditText(this).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(HINT)
            setTextColor(WHITE)
            textSize = 15f
            inputType = type
            setSingleLine()
            background = shape(CARD2, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = lp(top = 6)
        }
        if (secret) {
            var shown = false
            head.addView(tv("Show", 13f, ORANGE, true).apply {
                setOnClickListener {
                    shown = !shown
                    text = if (shown) "Hide" else "Show"
                    et.inputType = InputType.TYPE_CLASS_TEXT or
                        (if (shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                    et.setSelection(et.text.length)
                }
            })
        }
        box.addView(head)
        box.addView(et)
        box.addView(tv(help, 12f, MUTED).apply { setPadding(0, dp(4), 0, 0) })
        (currentFormParent ?: return et).addView(box)
        return et
    }

    private var currentFormParent: LinearLayout? = null

    private fun toggleRow(parent: LinearLayout, title: String, sub: String, on: Boolean): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(tv(title, 15f, WHITE, true))
        col.addView(tv(sub, 12f, MUTED))
        val sw = Switch(this).apply { isChecked = on }
        row.addView(col)
        row.addView(sw)
        parent.addView(row)
        return sw
    }

    // ---------------- lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        handleIntent(intent)
        if (prefs().getBoolean("setup_done", false)) showHome() else showSetup()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        if (screen != "home" && prefs().getBoolean("setup_done", false) && intent?.getBooleanExtra("alert", false) == true) showHome()
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("alert", false) != true) return
        // Crash alert: show over the lock screen so it can be cancelled quickly.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(poll)
        if (screen == "home") ui.post(poll)
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (screen == "settings") showHome() else super.onBackPressed()
    }

    // ---------------- first-run setup ----------------

    private fun showSetup() {
        screen = "setup"
        ui.removeCallbacks(poll)
        val col = column()
        col.addView(accentBar())
        col.addView(tv("\u26D1\uFE0F", 44f).apply { gravity = Gravity.CENTER; layoutParams = lp(top = 22) })
        col.addView(tv("Set up Helmet SOS", 26f, WHITE, true).apply { gravity = Gravity.CENTER; layoutParams = lp(top = 10) })
        col.addView(tv(
            "Tell the app who to alert when your helmet reports a crash. You can change all of this later in Settings.",
            14f, MUTED
        ).apply { gravity = Gravity.CENTER; layoutParams = lp(top = 8) })

        val sug = card(18)
        sug.addView(tv("Suggested setup:", 15f, WHITE, true))
        sug.addView(bullet("\uD83D\uDCCD", "Your live location goes to Telegram and by SMS"))
        sug.addView(bullet("\uD83D\uDCDE", "Family is called one by one until someone answers"))
        sug.addView(bullet("\u270B", "Cancel from your phone if you are OK"))
        col.addView(sug)

        val form = card()
        currentFormParent = form
        buildFields(form, false)
        col.addView(form)

        col.addView(pill("\u2713", "Save and start protection", ORANGE) {
            if (saveForm(true)) run(HelmetService.ACTION_START)
        })
        setContentView(wrap(col))
    }

    // ---------------- settings ----------------

    private fun showSettings() {
        screen = "settings"
        ui.removeCallbacks(poll)
        val col = column()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("\u2039", 30f, WHITE, true).apply {
            setPadding(0, 0, dp(16), dp(4))
            setOnClickListener { showHome() }
        })
        head.addView(tv("Settings", 24f, WHITE, true))
        col.addView(head)

        val form = card()
        currentFormParent = form
        buildFields(form, true)
        col.addView(form)

        val rel = card()
        rel.addView(tv("Keep it running", 15f, WHITE, true))
        rel.addView(tv("Set the app to Unrestricted in the battery list so Android never stops it.", 12f, MUTED))
        col.addView(rel)
        col.addView(pill("\uD83D\uDD0B", "Allow background running", CARD2) { askBattery() })
        col.addView(pill("\uD83D\uDCF1", "Allow display over other apps", CARD2) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(pill("\uD83D\uDD11", "Grant missing permissions", CARD2) {
            val m = missing()
            if (m.isEmpty()) toast("All permissions are already granted.")
            else {
                pendingAction = null
                requestPermissions(m.toTypedArray(), 1)
            }
        })
        col.addView(pill("\u2713", "Save changes", ORANGE) {
            if (saveForm(false)) {
                toast("Saved")
                showHome()
            }
        })
        setContentView(wrap(col))
    }

    private fun buildFields(parent: LinearLayout, extras: Boolean) {
        val p = prefs()
        parent.addView(tv("Alert details", 15f, WHITE, true))
        fToken = field(
            "Telegram bot token", p.getString("token", "").orEmpty(), "123456789:ABC-DEF...",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            "Create a bot with @BotFather and paste its token.", true
        )
        fChat = field(
            "Family Telegram chat ID(s)", p.getString("chat", "").orEmpty(), "123456789,987654321",
            InputType.TYPE_CLASS_TEXT,
            "Find your ID with @userinfobot. Separate several with commas."
        )
        fPhone = field(
            "Family phone numbers", p.getString("phone", "").orEmpty(), "+919876543210,+919123456789",
            InputType.TYPE_CLASS_PHONE,
            "With country code, separated by commas. Used for SMS and calls, in this order."
        )
        if (extras) {
            fCount = field(
                "Phone countdown (seconds)", p.getInt("countdown", 10).toString(), "10",
                InputType.TYPE_CLASS_NUMBER,
                "Used for the test alert and for helmets that give no warning first. The helmet's own 10 s warning is separate."
            )
            parent.addView(tv("Send alerts by", 15f, WHITE, true).apply { setPadding(0, dp(18), 0, 0) })
            sTg = toggleRow(parent, "Telegram", "Message + map pin", p.getBoolean("tg_on", true))
            sSms = toggleRow(parent, "SMS", "Text with a Google Maps link", p.getBoolean("sms_on", true))
            sCall = toggleRow(parent, "Phone calls", "Calls each number until answered", p.getBoolean("call_on", true))
        } else {
            fCount = null; sTg = null; sSms = null; sCall = null
        }
    }

    private fun saveForm(first: Boolean): Boolean {
        val token = fToken.text.toString().trim()
        val chat = fChat.text.toString().replace(" ", "").trim()
        val phone = fPhone.text.toString().replace(" ", "").replace("-", "").trim()
        if (token.isEmpty() != chat.isEmpty()) {
            toast("Telegram needs both the bot token and a chat ID.")
            return false
        }
        if (token.isEmpty() && phone.isEmpty()) {
            toast("Enter Telegram details, or at least one phone number.")
            return false
        }
        if (phone.isNotEmpty() && !phone.split(",").all { n -> n.count { it.isDigit() } >= 7 }) {
            toast("A phone number looks too short. Use the country code, e.g. +919876543210")
            return false
        }
        val e = prefs().edit()
            .putString("token", token)
            .putString("chat", chat)
            .putString("phone", phone)
        if (first) e.putBoolean("setup_done", true)
        fCount?.let { e.putInt("countdown", (it.text.toString().toIntOrNull() ?: 10).coerceIn(0, 60)) }
        sTg?.let { e.putBoolean("tg_on", it.isChecked) }
        sSms?.let { e.putBoolean("sms_on", it.isChecked) }
        sCall?.let { e.putBoolean("call_on", it.isChecked) }
        e.apply()
        return true
    }

    // ---------------- home ----------------

    private fun showHome() {
        screen = "home"
        val col = column()

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("\u26D1\uFE0F", 22f).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CARD2) }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(12) }
        })
        head.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(tv("Helmet SOS", 22f, WHITE, true))
            addView(tv("Crash alert for your family", 12f, MUTED))
        })
        head.addView(tv("\u2699", 26f, WHITE).apply {
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { showSettings() }
        })
        col.addView(head)

        // hero status
        heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(CARD, 28, MUTED)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = lp(top = 18)
        }
        val heroRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heroDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(MUTED) }
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(12) }
        }
        heroTitle = tv("", 21f, WHITE, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        heroRow.addView(heroDot)
        heroRow.addView(heroTitle)
        heroCard.addView(heroRow)
        heroDetail = tv("", 14f, MUTED).apply { setPadding(dp(26), dp(6), 0, 0) }
        heroCard.addView(heroDetail)
        col.addView(heroCard)

        cancelBtn = pill("\u270B", "I'm OK - cancel alert", RED) {
            startService(Intent(this, HelmetService::class.java).setAction(HelmetService.ACTION_CANCEL))
        }
        col.addView(cancelBtn)

        // delivery
        deliveryCard = card()
        deliveryCard.addView(tv("Last alert delivery", 15f, WHITE, true))
        tgVal = infoRow(deliveryCard, "Telegram")
        smsVal = infoRow(deliveryCard, "SMS")
        callVal = infoRow(deliveryCard, "Calls")
        col.addView(deliveryCard)

        // checks
        val checks = card()
        checks.addView(tv("What is happening", 15f, WHITE, true))
        helmetVal = infoRow(checks, "Helmet")
        gpsVal = infoRow(checks, "Location")
        sendVal = infoRow(checks, "Alerts to")
        permVal = infoRow(checks, "Permissions")
        battVal = infoRow(checks, "Battery")
        permVal.setOnClickListener {
            val m = missing()
            if (m.isNotEmpty()) { pendingAction = null; requestPermissions(m.toTypedArray(), 1) }
        }
        battVal.setOnClickListener { askBattery() }
        col.addView(checks)

        startBtn = pill("\u25B6", "Start protection", ORANGE) {
            if (Live.running) {
                stopService(Intent(this, HelmetService::class.java))
            } else run(HelmetService.ACTION_START)
        }
        col.addView(startBtn)
        col.addView(pill("\uD83E\uDDEA", "Send test alert", CARD2) {
            toast("Test sends Telegram + SMS and CALLS the saved numbers. You can cancel it during the countdown.")
            run(HelmetService.ACTION_TEST)
        })

        val log = card()
        log.addView(tv("Activity", 15f, WHITE, true))
        logView = tv("", 12f, MUTED).apply { setPadding(0, dp(6), 0, 0); typeface = Typeface.MONOSPACE }
        log.addView(logView)
        col.addView(log)

        setContentView(wrap(col))
        ui.removeCallbacks(poll)
        ui.post(poll)
    }

    private fun ageText(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return when {
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60} min ago"
            else -> "${s / 3600} h ago"
        }
    }

    private fun stateColor(s: String) = when {
        s.startsWith("Sent") || s == "Answered" -> GREEN
        s.startsWith("Failed") || s.startsWith("Nobody") -> RED
        s.startsWith("Stopped") -> AMBER
        s == "Off" || s.startsWith("Skipped") -> MUTED
        else -> ORANGE
    }

    private fun refreshHome() {
        if (screen != "home") return
        val now = System.currentTimeMillis()
        val running = Live.running
        val ph = Live.phase

        val color = when (ph) {
            Phase.STOPPED -> MUTED
            Phase.SEARCHING -> AMBER
            Phase.ARMED, Phase.DONE -> GREEN
            Phase.COUNTDOWN, Phase.ERROR -> RED
            Phase.SENDING, Phase.CALLING -> ORANGE
            Phase.CANCELLED -> BLUE
        }
        (heroDot.background as GradientDrawable).setColor(color)
        (heroCard.background as GradientDrawable).setStroke(dp(1), color)
        heroTitle.text = Live.headline
        heroDetail.text = Live.detail
        heroDetail.visibility = if (Live.detail.isBlank()) View.GONE else View.VISIBLE

        val counting = ph == Phase.COUNTDOWN
        cancelBtn.visibility = if (counting || Live.alertOpen) View.VISIBLE else View.GONE
        setPillText(
            cancelBtn,
            when {
                counting -> "I'm OK - cancel alert"
                Live.alertIsTest -> "Stop test"
                else -> "I'm OK - stop alert, send all-clear"
            }
        )

        deliveryCard.visibility =
            if (Live.tgState.isEmpty() && Live.smsState.isEmpty() && Live.callState.isEmpty()) View.GONE else View.VISIBLE
        setVal(tgVal, Live.tgState, stateColor(Live.tgState))
        setVal(smsVal, Live.smsState, stateColor(Live.smsState))
        setVal(callVal, Live.callState, stateColor(Live.callState))

        // helmet
        if (!running) setVal(helmetVal, "Protection is off", MUTED)
        else if (Live.helmetOk) setVal(helmetVal, "Connected", GREEN)
        else setVal(helmetVal, Live.helmetText, AMBER)

        // location
        if (!running) setVal(gpsVal, "Protection is off", MUTED)
        else if (Live.fixTime == 0L) setVal(gpsVal, "Waiting for a GPS fix...", AMBER)
        else {
            val age = now - Live.fixTime
            val t = "within ${Live.fixAcc.toInt()} m, ${ageText(age)}"
            setVal(gpsVal, t, if (age < 120_000L) GREEN else AMBER)
        }

        // alert channels
        val p = prefs()
        val token = p.getString("token", "").orEmpty()
        val chat = p.getString("chat", "").orEmpty()
        val phone = p.getString("phone", "").orEmpty()
        val tg = p.getBoolean("tg_on", true) && token.isNotEmpty() && chat.isNotEmpty()
        val sms = p.getBoolean("sms_on", true) && phone.isNotEmpty()
        val call = p.getBoolean("call_on", true) && phone.isNotEmpty()
        fun mark(on: Boolean, n: String) = if (on) "$n \u2713" else "$n off"
        setVal(sendVal, mark(tg, "Telegram") + "   " + mark(sms, "SMS") + "   " + mark(call, "Calls"),
            if (tg || sms || call) WHITE else RED)

        // permissions
        val m = missing()
        if (m.isEmpty()) setVal(permVal, "All granted", GREEN)
        else setVal(permVal, "Missing: " + friendly(m) + " (tap)", RED)

        // battery
        val ignoring = try {
            (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) { true }
        if (ignoring) setVal(battVal, "Unrestricted", GREEN)
        else setVal(battVal, "Not allowed yet, tap", AMBER)

        setPillText(startBtn, if (running) "Stop protection" else "Start protection")
        logView.text = if (Live.log.isEmpty()) "Nothing yet." else Live.log.take(8).joinToString("\n")
    }

    /** Shows Android's "always allow in background" dialog; falls back to the battery list. */
    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    // ---------------- permissions ----------------

    private fun needed(): List<String> {
        val p = prefs()
        val hasPhone = p.getString("phone", "").orEmpty().isNotBlank()
        val l = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) {
            l += Manifest.permission.BLUETOOTH_SCAN
            l += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        if (hasPhone && p.getBoolean("sms_on", true)) l += Manifest.permission.SEND_SMS
        if (hasPhone && p.getBoolean("call_on", true)) {
            l += Manifest.permission.CALL_PHONE
            l += Manifest.permission.READ_PHONE_STATE
            l += Manifest.permission.READ_CALL_LOG
            l += Manifest.permission.ANSWER_PHONE_CALLS
        }
        return l
    }

    private fun missing() = needed().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun friendly(list: List<String>): String = list.map {
        when (it) {
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION -> "Location"
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT -> "Nearby devices"
            Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
            Manifest.permission.SEND_SMS -> "SMS"
            Manifest.permission.CALL_PHONE -> "Calls"
            Manifest.permission.READ_PHONE_STATE -> "Phone state"
            Manifest.permission.READ_CALL_LOG -> "Call log"
            Manifest.permission.ANSWER_PHONE_CALLS -> "End calls"
            else -> "Other"
        }
    }.distinct().joinToString(", ")

    private fun run(action: String) {
        val m = missing()
        if (m.isNotEmpty()) {
            pendingAction = action
            requestPermissions(m.toTypedArray(), 1)
            if (screen != "home") {
                // setup screen: show the status page while permissions are asked
                if (prefs().getBoolean("setup_done", false)) showHome()
            }
            return
        }
        launch(action)
        if (screen != "home") showHome()
    }

    private fun launch(action: String) {
        startForegroundService(Intent(this, HelmetService::class.java).setAction(action))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val still = missing()
        val essential = still.filter {
            it == Manifest.permission.ACCESS_FINE_LOCATION || it == Manifest.permission.ACCESS_COARSE_LOCATION ||
                it == Manifest.permission.BLUETOOTH_SCAN || it == Manifest.permission.BLUETOOTH_CONNECT
        }
        val action = pendingAction
        pendingAction = null
        if (essential.isNotEmpty()) {
            toast("Location and Nearby devices permissions are required.")
            return
        }
        if (Manifest.permission.SEND_SMS in still || Manifest.permission.CALL_PHONE in still) {
            toast("Some alert permissions are missing. If Android blocks them: App info, menu, Allow restricted settings.")
        } else if (Manifest.permission.READ_PHONE_STATE in still) {
            toast("Without Phone state permission only the first number is called.")
        }
        if (action != null) {
            launch(action)
            if (screen != "home") showHome()
        }
    }
}
