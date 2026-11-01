package com.wayfindr.hus

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Status, start/stop, what the dash shows, settings and the live log. The bridge itself runs in
 *  BridgeService. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val uiPrefs by lazy { getSharedPreferences("hus_ui", MODE_PRIVATE) }

    private lateinit var aaRow: StatusRow
    private lateinit var dashRow: StatusRow
    private lateinit var hint: TextView
    private lateinit var startStop: TextView
    private lateinit var apps: Segmented
    private lateinit var views: Segmented
    private lateinit var viewNote: TextView
    private lateinit var logView: TextView
    private lateinit var prefix: EditText
    private lateinit var password: EditText
    private lateinit var ssid: EditText
    private lateinit var seats: Segmented
    private lateinit var layout: EditText
    private lateinit var stripPos: EditText
    private lateinit var altLayout: EditText
    private lateinit var altStripPos: EditText
    private lateinit var strip: Switch
    private lateinit var routeCard: EditText
    private lateinit var bitrate: EditText
    private lateinit var port: EditText

    private inner class StatusRow(label: String, parent: LinearLayout) {
        private val dot = View(this@MainActivity)
        private val value = text("", 16f, Ui.TEXT, medium = true)

        init {
            parent.addView(horizontal {
                addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).also { it.marginEnd = dp(14) })
                addView(vertical {
                    addView(text(label, 12.5f, Ui.MUTED))
                    addView(value)
                })
            }.margins(top = dp(12)))
        }

        fun set(text: String, color: Int) {
            value.text = text
            dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            if (logView.visibility == View.VISIBLE) logView.text = BridgeLog.snapshot().takeLast(120).joinToString("\n")
            val c = Config.load(this@MainActivity)
            if (apps.selected != c.launch) apps.select(c.launch)
            if (views.selected != c.view) selectView(c.view)
            ui.removeCallbacks(this)
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeLog.init(this)
        val c = Config.load(this)
        val root = vertical { setPadding(dp(18), dp(12), dp(18), dp(28)) }

        // Header
        root.addView(text("WayFindR", 15f, Ui.ACCENT, medium = true).margins(top = dp(14)))
        root.addView(text("WFR Dash Bridge", 30f, Ui.TEXT, medium = true).margins(top = dp(2)))
        root.addView(text("Android Auto from this phone, straight to the bike's dash.", 14f, Ui.MUTED)
            .margins(top = dp(4), bottom = dp(18)))

        // Status and start/stop
        card(root) {
            addView(overline("Status"))
            aaRow = StatusRow("Android Auto", this)
            dashRow = StatusRow("Dash", this)
            hint = text("", 13f, Ui.MUTED).margins(top = dp(12)).also(::addView)
            startStop = button("") { toggle() }.margins(top = dp(16)).also(::addView)
        }

        // What the dash shows; both apply at once while the bridge runs.
        card(root) {
            addView(overline("On the dash"))
            apps = Segmented(this@MainActivity, listOf("maps" to "Maps", "media" to "Music")) { showApp(it) }
                .also { it.select(c.launch); addView(it.margins(top = dp(10))) }
            addView(text("Music opens Spotify's full-screen player.", 13f, Ui.MUTED).margins(top = dp(8)))

            addView(overline("View").margins(top = dp(20)))
            views = Segmented(this@MainActivity, listOf("default" to "Default nav", "alternate" to "Alternate")) { setView(it) }
                .also { addView(it.margins(top = dp(10))) }
            viewNote = text("", 13f, Ui.MUTED).margins(top = dp(8)).also(::addView)
            selectView(c.view)
        }

        collapsible(root, "setup", "Phone setup", openByDefault = !BridgeService.running) {
            listOf(
                "Android Auto settings: tap Version 10 times to turn on developer mode.",
                "Start the head unit server: Android Auto settings > menu ⋮ > Start head unit server. " +
                    "On Samsung: Settings > Connected devices > Android Auto > ⋮ > Start head unit server. " +
                    "Repeat after every phone restart.",
                "Android Auto > Advanced > Driver seat location must match the seat in Settings.",
                "Tap Start bridge. The first time, pick the dash's RE_ Wi-Fi when Android asks.",
                "After the ride, stop the bridge and choose ⋮ > Stop head unit server: left running, it drains the battery.",
            ).forEachIndexed { i, step -> addView(step(i + 1, step)) }
        }

        collapsible(root, "settings", "Settings") {
            addView(overline("Dash Wi-Fi").margins(top = dp(4)))
            prefix = field(this, "Name starts with", c.dashPrefix)
            password = field(this, "Password", c.dashPassword)
            ssid = field(this, "Dash name", c.dashSsid, hint = "Filled in once seen; clear to search again")
            routeCard = field(this, "Route card", c.idleCard, hint = "turn | nosymbol | none | 0xNN")

            addView(overline("Layout").margins(top = dp(22)))
            addView(text("Seat", 13f, Ui.MUTED).margins(top = dp(12), bottom = dp(6)))
            seats = Segmented(this@MainActivity, listOf("left" to "Left seat", "right" to "Right seat")) {}
                .also { it.select(c.seat); addView(it.margins()) }
            layout = field(this, "Map layout  scale, x, y", c.fillLayout, hint = "Empty = default for the seat")
            stripPos = field(this, "Clock strip  x, y, size", c.stripPos, hint = "Empty = standard 186,4,1.35")
            strip = Switch(this@MainActivity).apply {
                text = "Clock, signal and battery strip"
                textSize = 15f
                setTextColor(Ui.TEXT)
                isChecked = c.statusStrip
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = ColorStateList(states, intArrayOf(Ui.ACCENT, Ui.MUTED))
                trackTintList = ColorStateList(states, intArrayOf(0x66FF8A3D, Ui.OUTLINE))
            }.also { addView(it.margins(top = dp(14))) }

            addView(overline("Alternate view").margins(top = dp(22)))
            altLayout = field(this, "Map layout  scale, x, y", c.altLayout, hint = "0.900,39,35")
            altStripPos = field(this, "Clock strip  x, y, size", c.altStripPos, hint = "Empty = standard 186,4,1.35")
            addView(text("The Dash Map Sizer gives these values for each view.", 12.5f, Ui.MUTED).margins(top = dp(10)))

            addView(overline("Advanced").margins(top = dp(22)))
            bitrate = field(this, "Video bitrate (bits/s)", c.bitrate.toString(), numeric = true)
            port = field(this, "Head unit server port", c.husPort.toString(), numeric = true)
            addView(text("Saved when you leave this screen; they apply the next time the bridge starts.",
                12.5f, Ui.MUTED).margins(top = dp(16)))
        }

        logView = text("", 10.5f, 0xFFB8C0CC.toInt()).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            background = rounded(Ui.BG, 10)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        collapsible(root, "showLog", "Log", onToggle = { if (it) refresh.run() }) { addView(logView.margins(top = dp(4))) }

        card(root) {
            addView(overline("Screen capture"))
            addView(text("Saves Android Auto's whole 800×480 picture, before the dash crop, to files/captures. " +
                "Handy for measuring button positions in other apps.", 13f, Ui.MUTED).margins(top = dp(8)))
            addView(button("Capture Android Auto screen") {
                val s = BridgeService.instance
                if (s == null) toast("Start the bridge first")
                else { s.capture(); toast("Capturing; the file name appears in the log") }
            }.margins(top = dp(14)))
        }

        // Android 15+ draws apps edge to edge: keep the content clear of the system bars and keyboard.
        window.decorView.setBackgroundColor(Ui.BG)
        window.insetsController?.setSystemBarsAppearance(0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(root)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        askPermissions()
    }

    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresh); save() }

    // ── Screen pieces ───────────────────────────────────────────────────────────

    private fun card(parent: LinearLayout, block: LinearLayout.() -> Unit) = vertical {
        background = rounded(Ui.SURFACE, 20, Ui.OUTLINE)
        setPadding(dp(18), dp(16), dp(18), dp(18))
        block()
    }.also { parent.addView(it.margins(bottom = dp(12))) }

    /** A card whose body opens and closes from its title row; remembered in prefs under [key]. */
    private fun collapsible(
        parent: LinearLayout, key: String, title: String, openByDefault: Boolean = false,
        onToggle: (Boolean) -> Unit = {}, block: LinearLayout.() -> Unit,
    ) = vertical {
        background = rounded(Ui.SURFACE, 20, Ui.OUTLINE)
        val chevron = text("›", 22f, Ui.MUTED)
        val body = vertical { setPadding(dp(18), 0, dp(18), dp(18)); block() }
        val header = horizontal {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = pressable(rounded(0, 20), 20)
            addView(text(title, 16f, Ui.TEXT, medium = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron)
        }
        fun show(open: Boolean) {
            body.visibility = if (open) View.VISIBLE else View.GONE
            chevron.rotation = if (open) 90f else 0f
            uiPrefs.edit().putBoolean(key, open).apply()
            onToggle(open)
        }
        header.setOnClickListener { show(body.visibility != View.VISIBLE) }
        addView(header)
        addView(body)
        show(uiPrefs.getBoolean(key, openByDefault))
    }.also { parent.addView(it.margins(bottom = dp(12))) }

    private fun step(n: Int, s: String) = horizontal {
        gravity = Gravity.TOP
        addView(text("$n", 12.5f, Ui.ACCENT, medium = true).apply {
            gravity = Gravity.CENTER
            background = rounded(0x22FF8A3D, 11)
        }, LinearLayout.LayoutParams(dp(22), dp(22)).also { it.marginEnd = dp(12) })
        addView(text(s, 14f, Ui.TEXT), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }.margins(top = dp(10))

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ── Behaviour ───────────────────────────────────────────────────────────────

    private fun updateStatus() {
        val running = BridgeService.running
        val lines = BridgeService.status.lines()
        fun part(prefix: String) = lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
        val aa = part("Android Auto: ") ?: "starting"
        val dash = part("Dash: ") ?: "starting"
        if (!running) {
            aaRow.set("Not running", Ui.OFF)
            dashRow.set("Not running", Ui.OFF)
        } else {
            aaRow.set(aa.replaceFirstChar { it.uppercase() }, if (aa == "projecting") Ui.GOOD else Ui.WAIT)
            dashRow.set(dash.replaceFirstChar { it.uppercase() }, if (dash.startsWith("streaming")) Ui.GOOD else Ui.WAIT)
        }
        val tip = when {
            !running -> "The phone screen can stay off while it runs."
            aa.startsWith("waiting") -> "Start the head unit server: Android Auto > ⋮ > Start head unit server " +
                "(Samsung: Settings > Connected devices > Android Auto > ⋮)."
            dash.startsWith("looking") -> "Turn on the bike. The phone joins the dash's Wi-Fi by itself."
            else -> ""
        }
        hint.text = tip
        hint.visibility = if (tip.isEmpty()) View.GONE else View.VISIBLE
        startStop.text = if (running) "Stop bridge" else "Start bridge"
        styleButton(startStop, filled = !running, color = if (running) Ui.DANGER else Ui.ACCENT)
    }

    private fun showApp(app: String) {
        Config.load(this).copy(launch = app).save(this)
        BridgeService.instance?.showApp(app)
    }

    private fun setView(view: String) {
        Config.load(this).copy(view = view).save(this)
        BridgeService.instance?.setView(view)
        selectView(view)
    }

    private fun selectView(view: String) {
        views.select(view)
        viewNote.text = if (view == "alternate")
            "Map at 0.900, 39, 35 and no turn circle. Without the circle the dash sends no joystick zoom or re-centre."
        else "Your map layout with the turn circle; the joystick zooms and re-centres."
    }

    private fun save() {
        val c = Config.load(this)
        val newSsid = ssid.text.toString().trim()
        c.copy(
            dashPrefix = prefix.text.toString().trim(),
            dashPassword = password.text.toString(),
            dashSsid = newSsid,
            dashBssid = if (newSsid == c.dashSsid) c.dashBssid else "",
            seat = seats.selected ?: c.seat,
            fillLayout = layout.text.toString().trim(),
            stripPos = stripPos.text.toString().trim(),
            altLayout = altLayout.text.toString().trim().ifEmpty { Config().altLayout },
            altStripPos = altStripPos.text.toString().trim(),
            statusStrip = strip.isChecked,
            idleCard = routeCard.text.toString().trim().ifEmpty { "turn" },
            bitrate = bitrate.text.toString().toIntOrNull() ?: c.bitrate,
            husPort = port.text.toString().toIntOrNull() ?: c.husPort,
        ).save(this)
    }

    private fun toggle() {
        if (BridgeService.running) {
            startService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_STOP))
        } else {
            save()
            startForegroundService(Intent(this, BridgeService::class.java))
        }
        ui.postDelayed({ refresh.run() }, 300)
    }

    private fun askPermissions() {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            wanted += Manifest.permission.NEARBY_WIFI_DEVICES
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }
}
