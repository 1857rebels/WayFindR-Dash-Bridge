package com.wayfindr.hus.dash

import android.net.Network
import com.wayfindr.hus.Config
import com.wayfindr.hus.aa.NowPlaying
import com.wayfindr.hus.log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One K1G session with the dash, as the Pi's dash_bridge.py DashSession (from Wayfindr's
 * DashSession.kt): initial burst, RSA/AES auth, nav-mode entry, then 4 Hz projection keep-alive,
 * 1 Hz route card, status heartbeat and music widget, answering the dash's frame acks and
 * button events. Sockets are bound to the dash's Wi-Fi network.
 */
class DashSession(
    network: Network,
    private val ssid: String,
    private val configSource: () -> Config,
    private val nowPlaying: NowPlaying,
    private val battery: () -> Pair<Int, Boolean>,
    private val onButton: (Int) -> Unit,
) {
    /** Read on every use, so the view chosen on the app screen applies at once. */
    private val config get() = configSource()

    private companion object {
        const val DASH_IP = "192.168.1.1"
        const val BROADCAST = "192.168.1.255"
        const val CTRL_PORT = 2000
        const val RX_PORT = 2002
        const val RTP_PORT = 5000
        const val AUTH_TIMEOUT_S = 15L
        const val RX_WATCHDOG_MS = 6_000L
        const val HOSTNAME = "WayfindR"
    }

    class Failed(msg: String) : Exception(msg)

    private val auth = K1g.Auth(ssid)
    private var seq = 0
    private val authenticated = CountDownLatch(1)
    private val ended = CountDownLatch(1)
    @Volatile private var failReason: String? = null
    @Volatile var streaming = false
        private set
    @Volatile private var closed = false
    @Volatile private var lastRx = 0L
    @Volatile private var firstAck = false
    @Volatile var ackedFrames = 0
        private set
    private var authRetries = 0
    private var musicOn = false
    private var beats = 0

    private val dashAddr = InetAddress.getByName(DASH_IP)
    private val broadcastAddr = InetAddress.getByName(BROADCAST)
    // RX must be open before the first TX (catches the early pubkey; avoids ICMP unreachable).
    private val rx = DatagramSocket(null).apply {
        reuseAddress = true; soTimeout = 500; bind(InetSocketAddress(RX_PORT)); network.bindSocket(this)
    }
    private val tx = DatagramSocket(null).apply {
        reuseAddress = true; broadcast = true; bind(InetSocketAddress(CTRL_PORT)); network.bindSocket(this)
    }
    private val rtp = DatagramSocket().apply { network.bindSocket(this) }

    fun send(pkt: ByteArray) {
        val p = synchronized(this) { K1g.patchSeq(pkt, seq).also { seq = (seq + 1) and 0xFF } }
        try {
            tx.send(DatagramPacket(p, p.size, broadcastAddr, CTRL_PORT))
        } catch (e: Exception) {
            fail("control send failed: ${e.message}")
        }
    }

    fun sendRtp(pkt: ByteArray) {
        runCatching { rtp.send(DatagramPacket(pkt, pkt.size, dashAddr, RTP_PORT)) }
    }

    private fun fail(reason: String) {
        if (failReason == null) failReason = reason
        ended.countDown()
    }

    fun close() {
        closed = true
        streaming = false
        for (pkt in listOf(K1g.projectionStop(), K1g.projectionOff()))
            runCatching { val p = K1g.patchSeq(pkt, seq); tx.send(DatagramPacket(p, p.size, broadcastAddr, CTRL_PORT)) }
        ended.countDown()
        rx.close(); tx.close(); rtp.close()
    }

    private fun every(periodMs: Long, fn: () -> Unit) = Thread {
        while (!closed && failReason == null) {
            runCatching(fn)
            Thread.sleep(periodMs)
        }
    }.apply { isDaemon = true; start() }

    private fun rxLoop() {
        val buf = ByteArray(65535)
        while (!closed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                rx.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (!closed) fail("receive failed: ${e.message}")
                return
            }
            lastRx = System.currentTimeMillis()
            for (t in K1g.parseIncoming(buf.copyOf(packet.length))) dispatch(t)
        }
    }

    private fun dispatch(t: K1g.Tlv) {
        val v = t.value
        when {
            t.type == 0x07 -> when (val ev = auth.ingest(t.sub, v)) {
                is K1g.Auth.Event.Send -> { log("dash: got RSA public key, sending session key"); send(ev.packet) }
                K1g.Auth.Event.Confirmed -> authenticated.countDown()
                K1g.Auth.Event.Rejected -> {
                    authRetries++
                    log("dash: auth rejected (retry $authRetries); is the SSID right? '$ssid'")
                    auth.reset()
                    if (authRetries <= 5) send(K1g.authRequest())
                }
                null -> Unit
            }
            t.type == 0x09 && t.sub == 0x06 && v.firstOrNull() == 0x55.toByte() -> {
                if (!firstAck) { firstAck = true; log("dash: decoded the first keyframe (09 06 55), video accepted") }
                ackedFrames++
                send(K1g.frameDecodedIdr())
            }
            t.type == 0x09 && t.sub == 0x04 && v.firstOrNull() == 0x55.toByte() -> {
                ackedFrames++
                send(K1g.frameDecodedP())
            }
            t.type == 0x09 && t.sub == 0x00 && v.isNotEmpty() -> {
                val code = v.last().toInt() and 0xFF
                log("dash: button event 0x%02X".format(code))
                send(K1g.buttonAck(code))
                onButton(code)
            }
            t.type != 0x0C && t.type != 0x0F && !(t.type == 0x01 && t.sub == 0x01) ->   // 01 01 = 1 Hz counter
                log("dash: event %02X %02X %s".format(t.type, t.sub, v.joinToString(" ") { "%02X".format(it) }))
        }
    }

    private fun routeCard(projectionOn: Boolean): ByteArray {
        val card = config.routeCard
        return when {
            card == "none" -> K1g.idleRouteCard(config.title, projectionOn)
            card == "nosymbol" -> K1g.routeCard(config.title, projectionOn, maneuver = K1g.NAV_NONE)
            card.startsWith("0x") -> K1g.routeCard(config.title, projectionOn, maneuver = card.substring(2).toInt(16))
            else -> K1g.routeCard(config.title, projectionOn)
        }
    }

    private fun heartbeat() {
        val (pct, charging) = battery()
        send(K1g.heartbeat(pct, charging))
        if (beats++ % 30 == 0) send(K1g.timeSync())
        if (streaming && firstAck && System.currentTimeMillis() - lastRx > RX_WATCHDOG_MS)
            fail("dash silent for ${RX_WATCHDOG_MS / 1000}+ s")
    }

    /** Now-playing to the dash's music widget, ~1 Hz like the original app (the dash drops a
     *  card it stops hearing about). 05 17 must be on or the dash never shows the song. */
    private fun music() {
        val title = nowPlaying.title
        val active = title.isNotEmpty() && nowPlaying.state != "none"
        if (active) {
            send(K1g.musicActive(true))
            send(K1g.musicPlaying(nowPlaying.state != "pause"))
            send(K1g.nowPlaying(title, nowPlaying.album, nowPlaying.artist))
        } else if (musicOn) {
            send(K1g.musicActive(false))
        }
        if (active != musicOn) log("dash: music widget ${if (active) "on: $title" else "off"}")
        musicOn = active
    }

    /** Handshake and nav-mode entry, then blocks until the session fails or is closed. */
    fun run() {
        Thread(::rxLoop).apply { isDaemon = true; start() }
        every(1000, ::heartbeat)
        log("dash: connecting to $ssid")
        for (pkt in K1g.initialBurst(HOSTNAME)) { send(pkt); Thread.sleep(20) }
        if (!authenticated.await(AUTH_TIMEOUT_S, TimeUnit.SECONDS))
            throw Failed("no auth confirmation (07 01 01) within $AUTH_TIMEOUT_S s")
        log("dash: authenticated")
        send(K1g.navContext()); Thread.sleep(40)
        send(K1g.emptyLists()); Thread.sleep(40)
        for (i in 0 until 4) { send(routeCard(false)); Thread.sleep(if (i < 1) 100 else 500) }
        send(K1g.projectionFrame()); Thread.sleep(60)
        send(K1g.navPlaceholder()); Thread.sleep(10)
        send(K1g.navStart()); Thread.sleep(40)
        send(routeCard(true))
        log("dash: nav mode entered, streaming")
        streaming = true
        every(250) { send(K1g.projectionFrame()) }
        every(1000) { send(routeCard(true)) }
        every(1000, ::music)
        ended.await()
        if (!closed) throw Failed(failReason ?: "ended")
    }
}
