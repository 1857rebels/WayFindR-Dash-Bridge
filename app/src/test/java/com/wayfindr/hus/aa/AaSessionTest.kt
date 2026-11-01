package com.wayfindr.hus.aa

import com.wayfindr.hus.Config
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status

/**
 * AaSession against a fake phone that speaks the head unit server's side of the protocol:
 * version exchange, TLS (phone = server), service discovery, channel open, video setup and
 * frames (including a message split over FIRST/LAST frames), media status, ping and touch input.
 * It checks the framing and TLS plumbing; the real phone can still differ.
 */
class AaSessionTest {

    private class FakePhone(socket: Socket) {
        class Msg(val ch: Int, val control: Boolean, val id: Int, val body: ByteArray) {
            fun pb() = PbMsg(body)
        }

        private val input = DataInputStream(socket.getInputStream())
        private val output = socket.getOutputStream()
        private val engine = AaTransport.sslContext().createSSLEngine().apply { useClientMode = false }
        private val app = ByteBuffer.allocate(1 shl 17)
        private val net = ByteBuffer.allocate(1 shl 17)

        fun read(): Msg {
            val ch = input.readUnsignedByte()
            val flags = input.readUnsignedByte()
            val size = input.readUnsignedShort()
            if (flags and 3 == 1) input.readInt()
            val raw = ByteArray(size).also { input.readFully(it) }
            assertEquals("head unit sends whole messages", 3, flags and 3)
            val plain = if (flags and 8 != 0) unwrap(raw) else raw
            val id = ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF)
            return Msg(ch, flags and 4 != 0, id, plain.copyOfRange(2, plain.size))
        }

        fun send(ch: Int, id: Int, body: ByteArray, encrypt: Boolean = true, control: Boolean = false) {
            val payload = idPlus(id, body).let { if (encrypt) wrap(it) else it }
            frame(ch, 3 or (if (encrypt) 8 else 0) or (if (control) 4 else 0), payload, null)
        }

        /** One message in a FIRST and a LAST frame, each encrypted on its own, as the phone does
         *  with video frames over 16 KB. */
        fun sendSplit(ch: Int, id: Int, body: ByteArray) {
            val plain = idPlus(id, body)
            val cut = plain.size / 2
            frame(ch, 1 or 8, wrap(plain.copyOfRange(0, cut)), plain.size)
            frame(ch, 2 or 8, wrap(plain.copyOfRange(cut, plain.size)), null)
        }

        fun handshake() {
            engine.beginHandshake()
            var pending = ByteBuffer.allocate(0)
            while (true) {
                when (engine.handshakeStatus) {
                    HandshakeStatus.NEED_UNWRAP -> {
                        if (!pending.hasRemaining()) pending = ByteBuffer.wrap(read().also { assertEquals(3, it.id) }.body)
                        app.clear()
                        val r = engine.unwrap(pending, app)
                        if (r.status == Status.BUFFER_UNDERFLOW) {
                            val more = read().body
                            pending = ByteBuffer.wrap(ByteArray(pending.remaining()).also { pending.get(it) } + more)
                        }
                    }
                    HandshakeStatus.NEED_TASK -> while (true) engine.delegatedTask?.run() ?: break
                    HandshakeStatus.NEED_WRAP -> {
                        val out = ByteArrayOutputStream()
                        while (engine.handshakeStatus == HandshakeStatus.NEED_WRAP) {
                            net.clear(); engine.wrap(ByteBuffer.allocate(0), net); net.flip()
                            out.write(net.array(), 0, net.limit())
                        }
                        send(0, 3, out.toByteArray(), encrypt = false)
                    }
                    else -> return
                }
            }
        }

        private fun idPlus(id: Int, body: ByteArray) = byteArrayOf((id shr 8).toByte(), id.toByte()) + body

        private fun frame(ch: Int, flags: Int, payload: ByteArray, total: Int?) {
            val head = ByteArrayOutputStream()
            head.write(ch); head.write(flags); head.write(payload.size shr 8); head.write(payload.size and 0xFF)
            if (total != null) for (s in listOf(24, 16, 8, 0)) head.write((total shr s) and 0xFF)
            output.write(head.toByteArray() + payload)
            output.flush()
        }

        private fun wrap(plain: ByteArray): ByteArray {
            val src = ByteBuffer.wrap(plain)
            val out = ByteArrayOutputStream()
            while (src.hasRemaining()) {
                net.clear(); engine.wrap(src, net); net.flip()
                out.write(net.array(), 0, net.limit())
            }
            return out.toByteArray()
        }

        private fun unwrap(raw: ByteArray): ByteArray {
            val src = ByteBuffer.wrap(raw)
            val out = ByteArrayOutputStream()
            while (src.hasRemaining()) {
                app.clear(); engine.unwrap(src, app); app.flip()
                out.write(app.array(), 0, app.limit())
            }
            return out.toByteArray()
        }
    }

    @Test(timeout = 30_000)
    fun sessionWithFakePhone() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val nowPlaying = NowPlaying()
        val frames = LinkedBlockingQueue<ByteArray>()
        val config = Config(husPort = server.localPort, launch = "none", seat = "left")
        val session = AaSession(config, nowPlaying) { d, o, l -> frames.add(d.copyOfRange(o, o + l)) }
        val runner = Thread { runCatching { session.run() } }.apply { start() }
        val phone = FakePhone(server.accept())

        // Version exchange, then TLS with the head unit as the client.
        val version = phone.read()
        assertEquals(0, version.ch); assertEquals(1, version.id)
        assertArrayEquals(byteArrayOf(0, 1, 0, 1), version.body)
        phone.send(0, 2, byteArrayOf(0, 1, 0, 1, 0, 0), encrypt = false)
        phone.handshake()
        assertEquals("auth complete", 4, phone.read().id)

        // Service discovery.
        phone.send(0, 5, Pb().str(4, "Pixel").str(5, "Google").toBytes())
        val sdr = phone.read()
        assertEquals(6, sdr.id)
        val channels = sdr.pb().msgs(1)
        assertEquals(listOf(3, 2, 1, 7, 10, 6), channels.map { it.int(1) })
        val videoConfig = channels[0].msg(3)!!.msg(4)!!
        assertEquals(listOf(1, 1, 240, 180, 110), (1..5).map { videoConfig.int(it) })
        val touch = channels[2].msg(4)!!.msg(2)!!
        assertEquals(560 to 300, touch.int(1) to touch.int(2))
        assertEquals("Wayfindr", sdr.pb().str(2))

        // Video channel: open, setup (-> response + focus), start, frames.
        phone.send(3, 7, Pb().int(1, 0).int(2, 3).toBytes(), control = true)
        val open = phone.read()
        assertEquals(3, open.ch); assertEquals(8, open.id); assertTrue(open.control)
        phone.send(3, 0x8000, Pb().int(1, 0).toBytes())
        val setup = phone.read()
        assertEquals(0x8003, setup.id); assertEquals(2, setup.pb().int(1)); assertEquals(1, setup.pb().int(2))
        val focus = phone.read()
        assertEquals(0x8008, focus.id); assertEquals(1, focus.pb().int(1))
        phone.send(3, 0x8001, Pb().int(1, 5).int(2, 0).toBytes())

        val small = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        phone.send(3, 0x0000, ByteArray(8) + small)
        val ack = phone.read()
        assertEquals(0x8004, ack.id); assertEquals(5, ack.pb().int(1)); assertEquals(1, ack.pb().int(2))
        assertArrayEquals(small, frames.poll(2, TimeUnit.SECONDS))

        val big = ByteArray(40_000) { (it * 7).toByte() }
        phone.sendSplit(3, 0x0000, ByteArray(8) + big)
        assertEquals(0x8004, phone.read().id)
        assertArrayEquals(big, frames.poll(2, TimeUnit.SECONDS))

        // Now playing, then a ping to know it has been handled.
        phone.send(10, 0x8003, Pb().str(1, "Song").str(2, "Artist").str(3, "Album").int(6, 100).int(7, 0).toBytes())
        phone.send(10, 0x8001, Pb().int(1, 3).str(2, "app").int(3, 0).int(4, 0).int(5, 0).int(6, 0).toBytes())
        phone.send(0, 0x0b, Pb().int(1, 42).toBytes())
        val pong = phone.read()
        assertEquals(0x0c, pong.id); assertEquals(42L, pong.pb().long(1))
        assertEquals(listOf("Song", "Artist", "Album", "pause"),
            listOf(nowPlaying.title, nowPlaying.artist, nowPlaying.album, nowPlaying.state))

        // Joystick zoom in (left seat): a tap at AA frame (204, 313) = touchscreen (84, 223).
        session.tap(204, 313)
        for (action in listOf(0, 1)) {
            val ev = phone.read()
            assertEquals(1, ev.ch); assertEquals(0x8001, ev.id)
            val t = ev.pb().msg(3)!!
            val loc = t.msg(1)!!
            assertEquals(listOf(84, 223, action), listOf(loc.int(1), loc.int(2), t.int(3)))
        }
        session.pressKey(87)
        for (pressed in listOf(1, 0)) {
            val key = phone.read().pb().msg(4)!!.msg(1)!!
            assertEquals(87 to pressed, key.int(1) to key.int(2))
        }

        // Shutdown ends the session.
        phone.send(0, 0x0f, Pb().int(1, 1).toBytes())
        assertEquals(0x10, phone.read().id)
        runner.join(5000)
        assertTrue(!runner.isAlive)
        server.close()
    }
}
