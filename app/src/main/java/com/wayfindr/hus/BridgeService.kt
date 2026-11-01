package com.wayfindr.hus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.wayfindr.hus.aa.AaSession
import com.wayfindr.hus.aa.NowPlaying
import com.wayfindr.hus.dash.DashSession
import com.wayfindr.hus.dash.DashWifi
import com.wayfindr.hus.dash.NalProcessor
import com.wayfindr.hus.dash.RtpPacketizer
import com.wayfindr.hus.video.VideoPipeline
import java.net.ConnectException
import java.util.concurrent.Executors

/**
 * Everything the Pi did, on the phone:
 *  - an Android Auto head unit connected to the phone's own head unit server (AaSession), in
 *    place of the wireless AA dongle + aa-headless;
 *  - the video composite + 526x300 encode (VideoPipeline), in place of ffmpeg;
 *  - the dash's Wi-Fi (DashWifi) and K1G session (DashSession) with RTP video, joystick and the
 *    music widget, in place of dash_wifi.py / dash_bridge.py.
 * Runs as a foreground service so it keeps going with the screen off.
 */
class BridgeService : Service() {
    companion object {
        const val ACTION_STOP = "com.wayfindr.hus.STOP"
        private const val CHANNEL = "bridge"
        @Volatile var running = false
            private set
        @Volatile var status = "stopped"
            private set
        /** The running service, for the app screen's Maps / Music buttons. */
        @Volatile var instance: BridgeService? = null
            private set
    }

    /** Saves Android Auto's current full screen (800x480, before the dash crop) to
     *  files/captures/aa-HHMMSS.png. With no dash, video is turned on for 15 s to take it. */
    fun capture() = Thread {
        val name = "aa-" + java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(java.util.Date()) + ".png"
        val file = java.io.File(getExternalFilesDir("captures"), name)
        if (!video.hasFrame) {
            log("capture: turning Android Auto video on")
            captureUntil = SystemClock.elapsedRealtime() + 15_000
            updateVideoFocus()
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!video.hasFrame && SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
            Thread.sleep(2500)   // let Android Auto finish drawing its screen
        }
        val done = java.util.concurrent.CountDownLatch(1)
        video.capture(file) { ok ->
            log(if (ok) "capture: saved ${file.absolutePath}" else "capture: no Android Auto picture to save")
            done.countDown()
        }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
    }.start()

    fun setView(view: String) {
        config = config.copy(view = view)
        log("view: ${if (config.alternate) "alternate view" else "default nav mode"}, layout ${config.layout()}, " +
            "route card ${config.routeCard}")
    }

    /** Joystick UP / DOWN: changes the view and the app together, and saves both so the app
     *  screen and the next reconnect follow. */
    private fun switchMode(view: String, app: String) {
        Config.load(this).copy(view = view, launch = app).save(this)
        setView(view)
        showApp(app)
    }

    /** Brings [app] (maps | media) up full screen in Android Auto now, and after every reconnect.
     *  "media" is Android Auto's media button: it opens the media app last used (e.g. Spotify). */
    fun showApp(app: String) {
        config = config.copy(launch = app)
        val key = if (app == "media") 0x10001 else 0x10002
        val session = aa
        if (session == null || !session.connected) { log("app: $app opens when Android Auto connects"); return }
        log("app: switching Android Auto to $app")
        inputs.execute {
            session.pressKey(key)
            if (app == "media") { Thread.sleep(1500); openNowPlaying(session) }
        }
    }

    /** If Spotify opened on a browse screen, taps its green now-playing button so the dash shows
     *  the full-screen player. Does nothing when the player is already up (that spot is then
     *  the repeat button). */
    private fun openNowPlaying(session: AaSession) {
        val button = config.nowPlayingButton ?: return
        val result = java.util.concurrent.LinkedBlockingQueue<Int>()
        // Spotify green (#1ED760): strong green, little red, some blue
        video.countPixels(button, { r, g, b -> g > 150 && r < 110 && b in 40..160 && g - r > 80 }, { result.add(it) })
        val green = result.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: -1
        if (green > button.w * button.h / 4) {
            log("app: Spotify browse screen ($green green px), opening now playing")
            session.tap(button.x + button.w / 2, button.y + button.h / 2)
        } else {
            log("app: now playing already showing ($green green px at the button spot)")
        }
    }

    @Volatile private lateinit var config: Config
    private val nowPlaying = NowPlaying()
    private lateinit var video: VideoPipeline
    private lateinit var wifi: DashWifi
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopping = false
    @Volatile private var aa: AaSession? = null
    @Volatile private var dash: DashSession? = null
    private val nals = NalProcessor()
    private val rtp = RtpPacketizer { pkt -> dash?.sendRtp(pkt) }
    private val t0 = SystemClock.elapsedRealtime()
    private var lastPts = 0L
    @Volatile private var waitForKeyFrame = true
    @Volatile private var sentFrames = 0L
    private val inputs = Executors.newSingleThreadExecutor()
    private val joystick = Joystick()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY
        running = true
        instance = this
        BridgeLog.init(this)
        config = Config.load(this)
        startForeground(1, notification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wayfindr:hus").apply { acquire() }
        log("bridge: seat ${config.seat}, layout ${config.layout()}, status strip ${if (config.statusStrip) "on" else "off"}, " +
            "card ${config.idleCard}, ${config.fps} fps ${config.bitrate / 1000} kbps")

        // Test hooks for adb (am broadcast -a ...): TEST_VIDEO --ez on true|false, CAPTURE,
        // TEST_APP --es app maps|media, TEST_TAP --ei x N --ei y N (AA 800x480 frame points),
        // TEST_BUTTON --ei code N (a dash joystick code, e.g. 6 = UP, 7 = DOWN).
        registerReceiver(testReceiver, android.content.IntentFilter().apply {
            for (a in listOf("TEST_VIDEO", "CAPTURE", "TEST_APP", "TEST_TAP", "TEST_BUTTON")) addAction("com.wayfindr.hus.$a")
        }, RECEIVER_EXPORTED)
        // TEST_VIDEO renders too, so the Re-centre finder runs without a dash (output is dropped).
        video = VideoPipeline({ config }, { dash != null || testVideo }, ::onEncoded)
        video.start()
        wifi = DashWifi(this, config.dashPrefix, config.dashPassword, config.dashSsid, config.dashBssid) { ssid, bssid ->
            config = config.copy(dashSsid = ssid, dashBssid = bssid)
            config.save(this)
        }
        wifi.start()
        Thread(::aaLoop, "hus-aa").start()
        Thread(::dashLoop, "hus-dash").start()
        Thread(::statsLoop, "hus-stats").apply { isDaemon = true }.start()
        Thread(::focusLoop, "hus-focus").apply { isDaemon = true }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        running = false
        instance = null
        status = "stopped"
        runCatching { unregisterReceiver(testReceiver) }
        aa?.close()
        dash?.close()
        if (::wifi.isInitialized) wifi.stop()
        if (::video.isInitialized) video.stop()
        inputs.shutdownNow()
        wakeLock?.let { if (it.isHeld) it.release() }
        log("bridge: stopped")
        super.onDestroy()
    }

    // ── Android Auto ────────────────────────────────────────────────────────────

    private fun aaLoop() {
        var refusedLogged = false
        while (!stopping) {
            var projecting = false
            val session = AaSession(config, nowPlaying, ::wantVideo) { data, off, len ->
                if (!projecting) {
                    projecting = true
                    video.startDecoder()
                    setAaStatus("projecting")
                }
                video.feed(data, off, len)
            }
            try {
                setAaStatus("waiting for the head unit server")
                aa = session
                session.run()
            } catch (e: ConnectException) {
                if (!refusedLogged) {
                    log("aa: nothing on 127.0.0.1:${config.husPort}. Start the head unit server: Android Auto > " +
                        "Developer settings (tap Version 10x) > menu > Start head unit server")
                    refusedLogged = true
                }
            } catch (e: Exception) {
                refusedLogged = false
                if (!stopping) log("aa: session error: ${e.message ?: e}")
            } finally {
                aa = null
                video.stopDecoder()
            }
            if (!stopping) Thread.sleep(if (quickReconnect) 300L else 2000L)
            quickReconnect = false
        }
    }

    /** Android Auto draws and encodes video only while a dash session wants it (or the test
     *  switch is on: adb shell am broadcast -a com.wayfindr.hus.TEST_VIDEO --ez on true). */
    @Volatile private var testVideo = false
    @Volatile private var lastDashAt = -60_000L
    /** Video is wanted while a dash session runs, and for 20 s after one ends, so a short Wi-Fi
     *  drop doesn't reconnect Android Auto twice. */
    @Volatile private var captureUntil = 0L
    private fun wantVideo() = dash != null || testVideo || SystemClock.elapsedRealtime().let {
        it - lastDashAt < 20_000 || it < captureUntil
    }

    /** Checks once a second whether Android Auto's video focus should change. */
    private fun focusLoop() {
        while (!stopping) {
            if (dash != null) lastDashAt = SystemClock.elapsedRealtime()
            updateVideoFocus()
            Thread.sleep(1000)
        }
    }
    @Volatile private var quickReconnect = false

    /** Reconnects Android Auto when its video focus no longer matches whether a dash wants the
     *  picture (focus can only be set when the session starts; reconnecting takes ~2 s). */
    private fun updateVideoFocus() {
        val s = aa ?: return
        val focus = s.videoFocus ?: return
        if (focus == wantVideo()) return
        log("aa: reconnecting to turn video ${if (wantVideo()) "on" else "off"}")
        quickReconnect = true
        s.close()
    }

    private val testReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.wayfindr.hus.CAPTURE" -> capture()
                "com.wayfindr.hus.TEST_APP" -> showApp(intent.getStringExtra("app") ?: "maps")
                "com.wayfindr.hus.TEST_BUTTON" -> intent.getIntExtra("code", -1).let { log("test: dash button 0x%02X".format(it)); joystick.onButton(it) }
                "com.wayfindr.hus.TEST_TAP" -> {
                    val x = intent.getIntExtra("x", -1); val y = intent.getIntExtra("y", -1)
                    aa?.let { s -> log("test: tap $x,$y"); inputs.execute { s.tap(x, y) } }
                }
                else -> {
                    testVideo = intent.getBooleanExtra("on", false)
                    log("test: video wanted without a dash: $testVideo")
                    updateVideoFocus()
                }
            }
        }
    }

    // ── Dash ────────────────────────────────────────────────────────────────────

    private fun dashLoop() {
        while (!stopping) {
            val network = wifi.network
            val ssid = wifi.currentSsid
            if (network == null || ssid.isBlank()) {
                setStatus(if (network == null) "looking for the dash Wi-Fi" else "joined the dash, waiting for its SSID")
                Thread.sleep(1000)
                continue
            }
            try {
                val session = DashSession(network, ssid, { config }, nowPlaying, ::battery, joystick::onButton)
                dash = session
                updateVideoFocus()
                waitForKeyFrame = true
                sentFrames = 0
                video.requestKeyFrame()
                setStatus("connecting to $ssid")
                try {
                    // Blocks for the whole session; a keyframe is requested once streaming starts.
                    Thread {
                        while (!stopping && dash === session && !session.streaming) Thread.sleep(100)
                        video.requestKeyFrame()
                        setStatus("streaming to $ssid")
                    }.apply { isDaemon = true }.start()
                    session.run()
                } finally {
                    dash = null
                    session.close()
                }
            } catch (e: Exception) {
                if (!stopping) log("dash: session ended: ${e.message ?: e}")
            }
            if (!stopping) Thread.sleep(3000)
        }
    }

    /** Encoder output -> NAL handling -> RTP to the dash, starting from a keyframe. */
    private fun onEncoded(data: ByteArray, key: Boolean) {
        val units = nals.frameUnits(data)
        val session = dash ?: return
        if (units.isEmpty() || !session.streaming) return
        if (waitForKeyFrame) {
            if (units.none { it.second }) return
            waitForKeyFrame = false
        }
        val pts = maxOf(SystemClock.elapsedRealtime() - t0, lastPts + 1)
        lastPts = pts
        for ((unit, _) in units) rtp.packetize(unit, pts)
        sentFrames++
    }

    private fun battery(): Pair<Int, Boolean> {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) to bm.isCharging
    }

    private fun statsLoop() {
        while (!stopping) {
            Thread.sleep(30_000)
            if (stopping) break
            log("stats: ${video.decodedFrames} AA frames decoded, ${video.encodedFrames} encoded, " +
                "$sentFrames sent to the dash, ${dash?.ackedFrames ?: 0} acked")
        }
    }

    // ── Joystick → Android Auto ─────────────────────────────────────────────────

    /** Dash joystick codes (Pi bridge, verified on the bike 2026-10-31): map mode RIGHT 0x13 zoom
     *  in, LEFT 0x14 zoom out, DOWN 0x15 re-centre; in the dash's music mode (centre click 0x18
     *  enters it) 0x09 next, 0x0A previous, 0x05 play/pause. UP 0x06 / DOWN 0x07 (seen in music
     *  mode, 2026-11-01; Wayfindr uses them for calls, which this app doesn't handle) switch
     *  between default nav mode + Maps and alternate view + Music. */
    private inner class Joystick {
        private var lastCode = -1
        private var lastMs = -10_000L

        fun onButton(code: Int) {
            val now = SystemClock.elapsedRealtime()
            // The stick can send a ghost second direction (RIGHT then DOWN): ignore a different
            // direction within 300 ms, as Wayfindr's JoystickDebounce does.
            if (code != lastCode && now - lastMs < 300) {
                log("joystick: 0x%02X ignored (%d ms after 0x%02X)".format(code, now - lastMs, lastCode))
                return
            }
            lastCode = code; lastMs = now
            val session = aa
            if (session == null || !session.connected) {
                log("joystick: 0x%02X, Android Auto not connected".format(code)); return
            }
            inputs.execute {
                when (code) {
                    0x13 -> config.zoomIn.let { log("joystick: zoom in (tap ${it.x},${it.y})"); session.tap(it.x, it.y) }
                    0x14 -> config.zoomOut.let { log("joystick: zoom out (tap ${it.x},${it.y})"); session.tap(it.x, it.y) }
                    0x15 -> {
                        // Tap Re-centre only where it is drawn; else AA's NAVIGATION key.
                        val spot = video.findRecentre()
                        if (spot != null) { log("joystick: re-centre (tap ${spot.x},${spot.y})"); session.tap(spot.x, spot.y) }
                        else { log("joystick: re-centre (no button showing, NAVIGATION key)"); session.pressKey(65538) }
                    }
                    0x09 -> { log("joystick: next track"); session.pressKey(87) }
                    0x0A -> { log("joystick: previous track"); session.pressKey(88) }
                    0x05 -> { log("joystick: play/pause"); session.pressKey(85) }
                    0x06 -> { log("joystick: UP, default nav mode + Maps"); switchMode("default", "maps") }
                    0x07 -> { log("joystick: DOWN, alternate view + Music"); switchMode("alternate", "media") }
                }
            }
        }
    }

    // ── Notification ────────────────────────────────────────────────────────────

    @Volatile private var aaStatus = "starting"
    @Volatile private var dashStatus = "starting"

    private fun setAaStatus(s: String) { aaStatus = s; updateStatus() }
    private fun setStatus(s: String) { dashStatus = s; updateStatus() }

    @Synchronized private fun updateStatus() {
        val s = "Android Auto: $aaStatus\nDash: $dashStatus"
        if (s == status || stopping) return
        status = s
        log("status: ${s.replace("\n", "; ")}")
        getSystemService(NotificationManager::class.java).notify(1, notification(s))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Dash bridge", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("WFR Dash Bridge")
            .setContentText(text.replace("\n", " · "))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
