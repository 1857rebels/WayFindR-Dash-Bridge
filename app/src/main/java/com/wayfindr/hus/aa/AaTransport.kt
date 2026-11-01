package com.wayfindr.hus.aa

import java.util.Base64
import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** One Android Auto message: channel, whether it is a channel-control message (channel open),
 *  message id and payload (after the id). */
class AaMessage(val channel: Int, val control: Boolean, val id: Int, val data: ByteArray, val offset: Int = 0) {
    val size get() = data.size - offset
    fun pb() = PbMsg(data, offset)
}

/**
 * Android Auto framing over the head unit server's TCP socket, the same as aasdk's
 * MessageInStream / MessageOutStream / Cryptor:
 *   frame = [channel][flags] [size:2] ([total size:4] on a FIRST frame) payload
 *   flags  = 0x08 encrypted, 0x04 control message, 0x01 FIRST / 0x02 LAST (0x03 = whole message)
 *   payload = [message id:2] [protobuf or raw data]
 * After the version exchange the head unit runs a TLS 1.2 handshake as the client, carried in
 * SSL_HANDSHAKE messages, with the head unit certificate every aasdk/openauto unit uses.
 */
class AaTransport(private val socket: Socket) {
    private val input = DataInputStream(socket.getInputStream().buffered(65536))
    private val output: OutputStream = socket.getOutputStream()
    private val writeLock = Any()
    private val engine: SSLEngine = sslContext().createSSLEngine().apply {
        useClientMode = true
        enabledProtocols = arrayOf("TLSv1.2")
    }
    private val appBuf = ByteBuffer.allocate(engine.session.applicationBufferSize + 0x4000)
    private val netBuf = ByteBuffer.allocate(engine.session.packetBufferSize + 0x4000)
    @Volatile var encrypted = false
        private set

    /** Partly received messages, per channel. */
    private val partial = HashMap<Int, java.io.ByteArrayOutputStream>()

    // ── TLS handshake ───────────────────────────────────────────────────────────

    fun beginHandshake() = engine.beginHandshake()

    /** Feeds the phone's handshake bytes (null for the first step) and returns the bytes to send
     *  back (possibly empty), or null once the handshake has finished. */
    fun handshakeStep(peer: ByteArray?): ByteArray? {
        val incoming = ByteBuffer.wrap(peer ?: ByteArray(0))
        val toSend = java.io.ByteArrayOutputStream()
        while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    while (true) engine.delegatedTask?.run() ?: break
                }
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netBuf.clear()
                    engine.wrap(ByteBuffer.allocate(0), netBuf)
                    netBuf.flip()
                    toSend.write(netBuf.array(), 0, netBuf.limit())
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (!incoming.hasRemaining()) return toSend.toByteArray()
                    appBuf.clear()
                    val r = engine.unwrap(incoming, appBuf)
                    if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) return toSend.toByteArray()
                }
                else -> {
                    // FINISHED / NOT_HANDSHAKING
                    encrypted = true
                    return if (toSend.size() > 0) toSend.toByteArray() else null
                }
            }
        }
    }

    // ── Sending ─────────────────────────────────────────────────────────────────

    /** Sends one message. Messages here are small, so each goes in a single frame. */
    fun send(channel: Int, id: Int, body: ByteArray, encrypt: Boolean = encrypted, control: Boolean = false) {
        val plain = ByteArray(2 + body.size)
        plain[0] = (id shr 8).toByte(); plain[1] = id.toByte()
        System.arraycopy(body, 0, plain, 2, body.size)
        synchronized(writeLock) {
            val payload = if (encrypt) encrypt(plain) else plain
            require(payload.size < 0x10000) { "message too large: ${payload.size}" }
            val flags = 0x03 or (if (encrypt) 0x08 else 0) or (if (control) 0x04 else 0)
            val frame = ByteArray(4 + payload.size)
            frame[0] = channel.toByte(); frame[1] = flags.toByte()
            frame[2] = (payload.size shr 8).toByte(); frame[3] = payload.size.toByte()
            System.arraycopy(payload, 0, frame, 4, payload.size)
            output.write(frame)
            output.flush()
        }
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(plain)
        val out = java.io.ByteArrayOutputStream()
        while (src.hasRemaining()) {
            netBuf.clear()
            engine.wrap(src, netBuf)
            netBuf.flip()
            out.write(netBuf.array(), 0, netBuf.limit())
        }
        return out.toByteArray()
    }

    // ── Receiving ───────────────────────────────────────────────────────────────

    /** Blocks for the next complete message; throws when the socket closes. */
    fun receive(): AaMessage {
        while (true) {
            val channel = input.readUnsignedByte()
            val flags = input.readUnsignedByte()
            val size = input.readUnsignedShort()
            val frameType = flags and 0x03
            if (frameType == 0x01) input.readInt()   // FIRST frame: total message size
            val raw = ByteArray(size)
            input.readFully(raw)
            val payload = if (flags and 0x08 != 0) decrypt(raw) else raw
            val whole: ByteArray = when (frameType) {
                0x03 -> payload
                0x01 -> { partial[channel] = java.io.ByteArrayOutputStream().apply { write(payload) }; continue }
                0x00 -> { partial[channel]?.write(payload); continue }
                else -> {   // LAST
                    val buf = partial.remove(channel) ?: continue
                    buf.write(payload); buf.toByteArray()
                }
            }
            if (whole.size < 2) continue
            val id = ((whole[0].toInt() and 0xFF) shl 8) or (whole[1].toInt() and 0xFF)
            return AaMessage(channel, flags and 0x04 != 0, id, whole, 2)
        }
    }

    private fun decrypt(raw: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(raw)
        val out = java.io.ByteArrayOutputStream()
        while (src.hasRemaining()) {
            appBuf.clear()
            val r = engine.unwrap(src, appBuf)
            appBuf.flip()
            out.write(appBuf.array(), 0, appBuf.limit())
            if (r.status != SSLEngineResult.Status.OK) break
        }
        return out.toByteArray()
    }

    fun close() = runCatching { socket.close() }

    internal companion object {
        // aasdk's head unit certificate and key (src/Messenger/Cryptor.cpp), DER, key as PKCS#8.
        private const val CERT =
            "MIIDKjCCAhICARswDQYJKoZIhvcNAQELBQAwWzELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNhbGlmb3JuaWExFjAUBgNVBAcMDU1v" +
            "dW50YWluIFZpZXcxHzAdBgNVBAoMFkdvb2dsZSBBdXRvbW90aXZlIExpbmswJhcRMTQwNzA0MDAwMDAwLTA3MDAXETQ1MDQyOTE0" +
            "MjgzOC0wNzAwMFMxCzAJBgNVBAYTAkpQMQ4wDAYDVQQIDAVUb2t5bzERMA8GA1UEBwwISGFjaGlvamkxFDASBgNVBAoMC0pWQyBL" +
            "ZW53b29kMQswCQYDVQQLDAIwMTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAM911mNnUfx+WJtxuk06GO7kXRW/gXUV" +
            "NQBkbAFZmVdVNvLoEQNthi2X8WCOwX6n6oMPxU2MGJnvicP36kBqfHhfQ2Fvqlf7YjjhgBHh0lqKShVPxIvdatBjVQ76aym5H3Gp" +
            "kigLGkmeyiVoVO8oc3cJ1bO96wFRmk7kJbYcEjQyakODPDu4QgWUTwp1Z8Dn41ARMG5OFh6otITLXBzj9REkUPkxfS03dBXGr5/L" +
            "IqvSsnxib1hJ47xnYJXROUsBy3e6T+fYZEEzZa7y7tFioHIQ8G/TziPmvFzmQpaWMGiYfoIgX8WoR3GD1diYW+wBaZTW+4SFUZJm" +
            "RKgqTbMNFkMCAwEAATANBgkqhkiG9w0BAQsFAAOCAQEAsGdH5VFn78WsBElMXaMziqFCzmilkvr85/QpGCIztI0FdF6xyMBJk/gY" +
            "s2thwvF+tCCpXoO8mjgJuvJZlwr6fHzKOx5hNUb06AeMtsUzUfFjSZXKrSR+XmclVd+Z6/ie33VhGePOPTKYmJ/PPfTT9wvT93qs" +
            "wcxhA+oX5yqLbU3uDPF1ZnJaEeD/YN45K/4eEA4/0SDXaWW14OScdS2LV0BcYmsbkPVNYZn37FlY7e2Z4FUphh0A7yME2Eh/e57Q" +
            "xWrJ1wubdzGnX8mrABc67ADUU5r9tlTRqMs7FGOk6QS2Cxp4pqeVQsrPts4OEwyPUyb3LfFNo3+sP111D9zEow=="
        private const val KEY =
            "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDPddZjZ1H8flibcbpNOhju5F0Vv4F1FTUAZGwBWZlXVTby6BED" +
            "bYYtl/FgjsF+p+qDD8VNjBiZ74nD9+pAanx4X0Nhb6pX+2I44YAR4dJaikoVT8SL3WrQY1UO+mspuR9xqZIoCxpJnsolaFTvKHN3" +
            "CdWzvesBUZpO5CW2HBI0MmpDgzw7uEIFlE8KdWfA5+NQETBuThYeqLSEy1wc4/URJFD5MX0tN3QVxq+fyyKr0rJ8Ym9YSeO8Z2CV" +
            "0TlLAct3uk/n2GRBM2Wu8u7RYqByEPBv084j5rxc5kKWljBomH6CIF/FqEdxg9XYmFvsAWmU1vuEhVGSZkSoKk2zDRZDAgMBAAEC" +
            "ggEAHa7tkuF4oJjvUqZuEpiqcpvoGbGB81+qarjznynTv+QobY74yDXGigWAeuFSHC4oZsI957+Q0Y2td4WkVb0mvA5dVLamd9o3" +
            "Do5tRaG6+EtrGCuGosB2hQSBahg4dwrOzfOGPwZ/p2L552pLJMDz7GdS1VnqIxEq8/i+0JSpoiAGdd2ZBsYg0/7/hCFvlHtfMy3/" +
            "E97q4zIAjILLkWb0ee96Rp6lp4UR/duHg1XWI39HynHpwVzoQtjEhlz/hdub3VYapJ0HSJKuJplOOCuZoWNI7jS2QJrEj03oMbMm" +
            "mamcyfTuJ+GXZWOcY5jC3K6HowoSfIupOtOD3HSLeYzCyQKBgQD0jKWM9F6tfEdQAm35wo5rK5fI23zSr08hwZvJE2+fuohjq2vm" +
            "635ltoWB+Ad1In/icgJGrTYel4MnyiP0GaFMCFxfpzWKsfkgKNGW3jSps/B6YhynJu2Zv4Og1hsjw7h1Q0Yf0g98Yrx1Gk6iTab5" +
            "+1+T3gabKRZtkXkKc4oinwKBgQDZLJznGUoHlCTZmTBcjPFvOjNJ+6ceFZZ2DiUBvIifBrpjkYHflQ101YjxMApmw3Z8q9dEWAKN" +
            "s+t5RtEQlozehn3ajlficXGCO8ATW3JGpw9Vt0pthmC1yqolfTqxb+Dev/dEfRc+PZKAkgd7D2wzOMZYNWIwdcuae0jytUDt3QKB" +
            "gE6vfokXC+uoRE3TKk2lsyt6kFEZhlVIAR8sa5LMRStQ2pevTX20oivaCaUjCEtBOYLECkVxcCtxtsqzuNPO914+hnJkm86vqygU" +
            "2jM/9hPwiNzn+q4x0VuaCqFSotLkI9LfPY35ifAM2PSY7Vo9wA9JOZybYObF1qiUmSyqtnSjAoGBAIPQokNahCZpcpxocIQcQAaE" +
            "ytCi3+JQtAxftXKCXmI3kTYSAUQVkh9R5FaQFCAfj3FIU9Z1nMcpZ0krBIYO+t2twAHB1/HnbT/gyEp3fLsJdzNNlu03XMe6hN9Q" +
            "TSmZgGFzHsABNPRgJuXGvKeiysekNC8h58EGHlhbcAFwpkI5AoGAQz+LHMj85VJnQ/LaD5dXz64ZDILleNDpzGRVNfcazRldt6aB" +
            "Q6dfxyh4JdLhWuER/j8dgxY/sYHkwVQ4RW2UuIszTmu5QvsKumF1+BrINdVxM9+TvmUuGa4oDCne0gc+gk5CIo1Df+qSzmSb/126" +
            "NQZTYBGJTeqbEsx2B6AXPx4="

        fun sslContext(): SSLContext {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(Base64.getDecoder().decode(CERT).inputStream()) as X509Certificate
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(KEY)))
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setKeyEntry("hu", key, CharArray(0), arrayOf(cert))
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
            // The phone's certificate is Google's own; like aasdk, the head unit doesn't check it.
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            return SSLContext.getInstance("TLSv1.2").apply { init(kmf.keyManagers, arrayOf<TrustManager>(trustAll), null) }
        }
    }
}
