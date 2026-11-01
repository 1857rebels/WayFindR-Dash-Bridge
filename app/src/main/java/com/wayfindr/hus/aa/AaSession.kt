package com.wayfindr.hus.aa

import com.wayfindr.hus.Config
import com.wayfindr.hus.log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** What the phone is playing, from Android Auto's media status channel. */
class NowPlaying {
    @Volatile var title = ""
    @Volatile var artist = ""
    @Volatile var album = ""
    /** none | change | play | pause */
    @Volatile var state = "none"
}

/**
 * A headless Android Auto head unit talking to the phone's own head unit server
 * (127.0.0.1:5277) — the phone version of the Pi's aa-headless. Offers the same services:
 * video (800x480, margins 240x180 so AA lays out 560x300), touch + keys, driving-status and
 * night sensors, microphone, the media status channel, and only the system audio sink, so music
 * and navigation voice stay on the phone (and its Bluetooth intercom).
 *
 * [onVideo] gets each H.264 access unit; [nowPlaying] is kept up to date.
 */
class AaSession(
    private val config: Config,
    private val nowPlaying: NowPlaying,
    /** Whether the picture is wanted (a dash is attached). Without it the head unit takes video
     *  focus away, so Android Auto stops rendering and encoding (it sent ~51 fps on a ride for a
     *  dash that shows 4, 2026-11-01). */
    private val wantVideo: () -> Boolean = { true },
    private val onVideo: (ByteArray, Int, Int) -> Unit,
) {
    private companion object {
        // aasdk ChannelId
        const val CH_CONTROL = 0
        const val CH_INPUT = 1
        const val CH_SENSOR = 2
        const val CH_VIDEO = 3
        const val CH_SYSTEM_AUDIO = 6
        const val CH_MIC = 7
        const val CH_MEDIA_STATUS = 10

        // Control messages (ControlMessageIdsEnum)
        const val VERSION_REQUEST = 0x0001
        const val VERSION_RESPONSE = 0x0002
        const val SSL_HANDSHAKE = 0x0003
        const val AUTH_COMPLETE = 0x0004
        const val SERVICE_DISCOVERY_REQUEST = 0x0005
        const val SERVICE_DISCOVERY_RESPONSE = 0x0006
        const val CHANNEL_OPEN_REQUEST = 0x0007
        const val CHANNEL_OPEN_RESPONSE = 0x0008
        const val PING_REQUEST = 0x000b
        const val PING_RESPONSE = 0x000c
        const val NAVIGATION_FOCUS_REQUEST = 0x000d
        const val NAVIGATION_FOCUS_RESPONSE = 0x000e
        const val SHUTDOWN_REQUEST = 0x000f
        const val SHUTDOWN_RESPONSE = 0x0010
        const val AUDIO_FOCUS_REQUEST = 0x0012
        const val AUDIO_FOCUS_RESPONSE = 0x0013

        // AV channel messages
        const val AV_MEDIA_WITH_TIMESTAMP = 0x0000
        const val AV_MEDIA = 0x0001
        const val AV_SETUP_REQUEST = 0x8000
        const val AV_START = 0x8001
        const val AV_STOP = 0x8002
        const val AV_SETUP_RESPONSE = 0x8003
        const val AV_MEDIA_ACK = 0x8004
        const val AV_INPUT_OPEN_REQUEST = 0x8005
        const val AV_INPUT_OPEN_RESPONSE = 0x8006
        const val VIDEO_FOCUS_REQUEST = 0x8007
        const val VIDEO_FOCUS_INDICATION = 0x8008

        // Sensor / input / media status channel messages
        const val SENSOR_START_REQUEST = 0x8001
        const val SENSOR_START_RESPONSE = 0x8002
        const val SENSOR_EVENT = 0x8003
        const val INPUT_EVENT = 0x8001
        const val BINDING_REQUEST = 0x8002
        const val BINDING_RESPONSE = 0x8003
        const val MEDIA_PLAYBACK = 0x8001
        const val MEDIA_METADATA = 0x8003

        const val SENSOR_NIGHT = 10
        const val SENSOR_DRIVING_STATUS = 13

        // ButtonCodeEnum
        const val KEY_MEDIA = 0x10001
        const val KEY_NAVIGATION = 0x10002
        const val KEY_TEL = 0x10004
        const val KEY_SCROLL_WHEEL = 0x10000

        const val MARGIN_W = 240
        const val MARGIN_H = 180
    }

    private var transport: AaTransport? = null
    private val sessions = HashMap<Int, Int>()   // AV channel -> session id from its start indication
    private var videoFrames = 0L
    private val timers = Executors.newSingleThreadScheduledExecutor()
    // The touchscreen is AA's UI area: the 800x480 frame minus the margins.
    private val touchLeft = MARGIN_W / 2
    private val touchTop = MARGIN_H / 2
    private val touchW = 800 - MARGIN_W
    private val touchH = 480 - MARGIN_H

    @Volatile var connected = false
        private set

    /** Connects and runs the session until the phone ends it or the socket drops. */
    fun run() {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress("127.0.0.1", config.husPort), 3000)
        val t = AaTransport(socket)
        transport = t
        log("aa: connected to the head unit server on port ${config.husPort}")
        try {
            t.send(CH_CONTROL, VERSION_REQUEST, byteArrayOf(0, 1, 0, 1), encrypt = false)
            while (true) handle(t.receive())
        } finally {
            connected = false
            timers.shutdownNow()
            t.close()
            log("aa: session ended after $videoFrames video frames")
        }
    }

    fun close() {
        transport?.close()
    }

    // ── Input (joystick → Android Auto) ─────────────────────────────────────────

    /** Press and release one button, like a steering-wheel key. */
    fun pressKey(code: Int) {
        for (pressed in listOf(true, false)) {
            val event = Pb().int(1, code).bool(2, pressed).int(3, 0).bool(4, false)
            sendInput(Pb().int(1, nowMicros()).msg(4, Pb().msg(1, event)))
        }
    }

    /** A tap at (x, y) in AA's 800x480 frame. Blocks ~50 ms. */
    fun tap(x: Int, y: Int) {
        touch(0, x, y)        // PRESS
        Thread.sleep(50)
        touch(1, x, y)        // RELEASE
    }

    private fun touch(action: Int, x: Int, y: Int) {
        val location = Pb()
            .int(1, (x - touchLeft).coerceIn(0, touchW - 1))
            .int(2, (y - touchTop).coerceIn(0, touchH - 1))
            .int(3, 0)
        val event = Pb().msg(1, location).int(2, 0).int(3, action)
        sendInput(Pb().int(1, nowMicros()).msg(3, event))
    }

    private fun sendInput(indication: Pb) {
        val t = transport ?: return
        if (!connected) return
        runCatching { t.send(CH_INPUT, INPUT_EVENT, indication.toBytes()) }
    }

    // Wall-clock microseconds: Maps ignored touches stamped with time-since-boot (Pi, 2026-10-31).
    private fun nowMicros() = System.currentTimeMillis() * 1000

    // ── Messages from the phone ─────────────────────────────────────────────────

    private fun handle(m: AaMessage) {
        val t = transport ?: return
        if (m.control && m.id == CHANNEL_OPEN_REQUEST) {
            log("aa: channel ${m.channel} open")
            t.send(m.channel, CHANNEL_OPEN_RESPONSE, Pb().int(1, 0).toBytes(), control = true)
            return
        }
        when (m.channel) {
            CH_CONTROL -> control(t, m)
            CH_VIDEO -> video(t, m)
            CH_SYSTEM_AUDIO -> audio(t, m)
            CH_MIC -> mic(t, m)
            CH_SENSOR -> sensor(t, m)
            CH_INPUT -> if (m.id == BINDING_REQUEST) t.send(CH_INPUT, BINDING_RESPONSE, Pb().int(1, 0).toBytes())
            CH_MEDIA_STATUS -> mediaStatus(m)
        }
    }

    private fun control(t: AaTransport, m: AaMessage) {
        when (m.id) {
            VERSION_RESPONSE -> {
                val d = m.data; val o = m.offset
                val major = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
                val minor = ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)
                val mismatch = m.size >= 6 && d[o + 4].toInt() != 0
                log("aa: phone protocol $major.$minor${if (mismatch) " (MISMATCH)" else ""}")
                if (mismatch) throw IllegalStateException("protocol version mismatch")
                t.beginHandshake()
                t.handshakeStep(null)?.let { t.send(CH_CONTROL, SSL_HANDSHAKE, it, encrypt = false) }
            }
            SSL_HANDSHAKE -> {
                val reply = t.handshakeStep(m.data.copyOfRange(m.offset, m.data.size))
                if (reply != null && reply.isNotEmpty()) t.send(CH_CONTROL, SSL_HANDSHAKE, reply, encrypt = false)
                if (t.encrypted) {
                    log("aa: TLS handshake complete")
                    t.send(CH_CONTROL, AUTH_COMPLETE, Pb().int(1, 0).toBytes(), encrypt = false)
                }
            }
            SERVICE_DISCOVERY_REQUEST -> {
                val req = m.pb()
                log("aa: service discovery from ${req.str(5)} ${req.str(4)}")
                t.send(CH_CONTROL, SERVICE_DISCOVERY_RESPONSE, serviceDiscovery().toBytes())
            }
            PING_REQUEST -> t.send(CH_CONTROL, PING_RESPONSE, Pb().int(1, m.pb().long(1)).toBytes())
            NAVIGATION_FOCUS_REQUEST -> t.send(CH_CONTROL, NAVIGATION_FOCUS_RESPONSE, Pb().int(1, 2).toBytes())
            AUDIO_FOCUS_REQUEST -> {
                // RELEASE -> LOSS, GAIN_TRANSIENT -> GAIN_TRANSIENT, else GAIN
                val state = when (m.pb().int(1)) { 4 -> 3; 2 -> 2; else -> 1 }
                t.send(CH_CONTROL, AUDIO_FOCUS_RESPONSE, Pb().int(1, state).toBytes())
            }
            SHUTDOWN_REQUEST -> {
                log("aa: phone requested shutdown, reason ${m.pb().int(1)}")
                runCatching { t.send(CH_CONTROL, SHUTDOWN_RESPONSE, ByteArray(0)) }
                throw IllegalStateException("phone ended the session")
            }
        }
    }

    private fun serviceDiscovery(): Pb {
        fun channel(id: Int, field: Int, body: Pb) = Pb().int(1, id).msg(field, body)
        val audioConfig = Pb().int(1, 16000).int(2, 16).int(3, 1)
        val video = Pb().int(1, 3).bool(5, true)   // VIDEO, available while in a call
            .msg(4, Pb().int(1, 1).int(2, 1).int(3, MARGIN_W).int(4, MARGIN_H).int(5, config.dpi))   // 480p, 30 fps
        val sensors = Pb().msg(1, Pb().int(1, SENSOR_DRIVING_STATUS)).msg(1, Pb().int(1, SENSOR_NIGHT))
        // HOME, BACK, DPAD up/down/left/right/centre, media play/pause/next/previous and AA's own
        // MEDIA / NAVIGATION / TEL buttons, rotary wheel
        val input = Pb().apply {
            for (code in listOf(3, 4, 19, 20, 21, 22, 23, 85, 87, 88, KEY_MEDIA, KEY_NAVIGATION, KEY_TEL, KEY_SCROLL_WHEEL))
                int(1, code)
        }.msg(2, Pb().int(1, touchW).int(2, touchH))
        val mic = Pb().int(1, 1).msg(2, audioConfig)                       // AUDIO
        val systemAudio = Pb().int(1, 1).int(2, 2).msg(3, audioConfig).bool(5, true)   // AUDIO, SYSTEM
        log("aa: offering video 800x480 margins ${MARGIN_W}x$MARGIN_H dpi ${config.dpi}, system audio only")
        return Pb()
            .msg(1, channel(CH_VIDEO, 3, video))
            .msg(1, channel(CH_SENSOR, 2, sensors))
            .msg(1, channel(CH_INPUT, 4, input))
            .msg(1, channel(CH_MIC, 5, mic))
            .msg(1, channel(CH_MEDIA_STATUS, 9, Pb()))
            .msg(1, channel(CH_SYSTEM_AUDIO, 3, systemAudio))
            .str(2, "Wayfindr")
            .str(3, "Mt.Fuji 450")
            .str(4, "2026")
            .str(5, "0")
            .bool(6, false)
            .str(7, "Wayfindr")
            .str(8, "Phone HUS bridge")
            .str(9, "1")
            .str(10, "0.1")
            .bool(11, false)
            .bool(12, false)
    }

    private fun avSetup(t: AaTransport, channel: Int) =
        t.send(channel, AV_SETUP_RESPONSE, Pb().int(1, 2).int(2, 1).int(3, 0).toBytes())   // OK, max 1 unacked

    private fun ack(t: AaTransport, channel: Int) =
        t.send(channel, AV_MEDIA_ACK, Pb().int(1, sessions[channel] ?: 0).int(2, 1).toBytes())

    /** Whether this session gave the phone video focus, i.e. Android Auto draws and encodes.
     *  Fixed for the session: on the S25 Ultra (2026-11-01) focus given later started no video,
     *  and focus taken away ended the session, so the caller reconnects to change it. */
    @Volatile var videoFocus: Boolean? = null
        private set

    private fun sendVideoFocus(t: AaTransport, on: Boolean) {
        if (videoFocus != on) log("aa: video focus ${if (on) "on" else "off (no dash, Android Auto draws nothing)"}")
        videoFocus = on
        // FOCUSED 1 / UNFOCUSED 2
        t.send(CH_VIDEO, VIDEO_FOCUS_INDICATION, Pb().int(1, if (on) 1 else 2).bool(2, false).toBytes())
    }

    private fun video(t: AaTransport, m: AaMessage) {
        when (m.id) {
            AV_SETUP_REQUEST -> {
                log("aa: video setup, config ${m.pb().int(1)}")
                avSetup(t, CH_VIDEO)
                sendVideoFocus(t, wantVideo())
            }
            AV_START -> {
                sessions[CH_VIDEO] = m.pb().int(1)
                connected = true
                log("aa: video started")
            }
            AV_STOP -> log("aa: video stopped after $videoFrames frames")
            VIDEO_FOCUS_REQUEST -> sendVideoFocus(t, videoFocus ?: wantVideo())
            AV_MEDIA_WITH_TIMESTAMP, AV_MEDIA -> {
                val skip = if (m.id == AV_MEDIA_WITH_TIMESTAMP) 8 else 0
                onVideo(m.data, m.offset + skip, m.size - skip)
                if (++videoFrames == 1L) onFirstFrame()
                // Acked at once: the phone doesn't wait for acks (272 of 300 frames came before
                // the previous ack when acks were held back, 2026-11-01), so they can't pace it.
                ack(t, CH_VIDEO)
            }
        }
    }

    private fun onFirstFrame() {
        log("aa: first video frame")
        val key = when (config.launch) {
            "maps" -> KEY_NAVIGATION; "media" -> KEY_MEDIA; "phone" -> KEY_TEL; else -> return
        }
        // AA opens its home screen first; once it has settled, open the app full screen.
        timers.schedule({
            log("aa: pressing key $key to open ${config.launch}")
            pressKey(key)
        }, 3, TimeUnit.SECONDS)
    }

    private fun audio(t: AaTransport, m: AaMessage) {
        when (m.id) {
            AV_SETUP_REQUEST -> avSetup(t, m.channel)
            AV_START -> sessions[m.channel] = m.pb().int(1)
            AV_MEDIA_WITH_TIMESTAMP, AV_MEDIA -> ack(t, m.channel)   // nothing plays it: the dash has no speaker
        }
    }

    private fun mic(t: AaTransport, m: AaMessage) {
        when (m.id) {
            AV_SETUP_REQUEST -> avSetup(t, CH_MIC)
            AV_INPUT_OPEN_REQUEST -> {
                log("aa: phone ${if (m.pb().long(1) != 0L) "opened" else "closed"} the microphone (no audio sent)")
                t.send(CH_MIC, AV_INPUT_OPEN_RESPONSE, Pb().int(1, 0).int(2, 0).toBytes())
            }
        }
    }

    private fun sensor(t: AaTransport, m: AaMessage) {
        if (m.id != SENSOR_START_REQUEST) return
        val type = m.pb().int(1)
        log("aa: sensor start, type $type")
        t.send(CH_SENSOR, SENSOR_START_RESPONSE, Pb().int(1, 0).toBytes())
        val event = when (type) {
            SENSOR_DRIVING_STATUS -> Pb().msg(13, Pb().int(1, 0))   // UNRESTRICTED
            SENSOR_NIGHT -> Pb().msg(10, Pb().bool(1, false))
            else -> return
        }
        t.send(CH_SENSOR, SENSOR_EVENT, event.toBytes())
    }

    private fun mediaStatus(m: AaMessage) {
        val pb = m.pb()
        when (m.id) {
            MEDIA_METADATA -> {
                nowPlaying.title = pb.str(1); nowPlaying.artist = pb.str(2); nowPlaying.album = pb.str(3)
                log("aa: media track ${nowPlaying.title} / ${nowPlaying.artist} / ${nowPlaying.album}")
            }
            MEDIA_PLAYBACK -> {
                val state = when (pb.int(1)) { 1 -> "change"; 2 -> "play"; 3 -> "pause"; else -> "none" }
                if (state != nowPlaying.state) log("aa: media state $state")   // repeats every second
                nowPlaying.state = state
            }
        }
    }
}
