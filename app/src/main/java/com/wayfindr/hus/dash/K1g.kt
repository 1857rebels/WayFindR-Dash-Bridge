package com.wayfindr.hus.dash

import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.util.Calendar
import javax.crypto.Cipher

/**
 * K1G dash protocol, ported from the Pi's k1g.py (itself from Wayfindr's K1GPacket.kt,
 * DashCommands.kt and DashAuth.kt).
 *
 * Outgoing packet: [len:2][segcount:2] 00000000 0201 0005 "K1G " [seq] then TLVs
 * (type, sub, len:2, value). Incoming packets carry their TLVs from offset 8.
 */
object K1g {
    private val MAGIC = "K1G ".toByteArray()

    fun hex(s: String): ByteArray {
        val c = s.replace(" ", "")
        return ByteArray(c.length / 2) { c.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun setLen(pkt: ByteArray): ByteArray {
        pkt[0] = (pkt.size shr 8).toByte(); pkt[1] = pkt.size.toByte()
        return pkt
    }

    class Tlv(val type: Int, val sub: Int, val value: ByteArray)

    fun build(vararg tlvs: Tlv): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, (1 + tlvs.size).toByte(), 0, 0, 0, 0, 2, 1, 0, 5))
        out.write(MAGIC); out.write(0)
        for (t in tlvs) {
            out.write(t.type); out.write(t.sub); out.write(t.value.size shr 8); out.write(t.value.size and 0xFF)
            out.write(t.value)
        }
        return setLen(out.toByteArray())
    }

    private fun indexOf(pkt: ByteArray, marker: ByteArray, fromEnd: Boolean = false): Int {
        val range = if (fromEnd) (pkt.size - marker.size downTo 0) else (0..pkt.size - marker.size)
        for (i in range) if ((marker.indices).all { pkt[i + it] == marker[it] }) return i
        return -1
    }

    fun patchSeq(pkt: ByteArray, seq: Int): ByteArray {
        val out = pkt.copyOf()
        val k = indexOf(out, MAGIC)
        if (k >= 0 && k + 4 < out.size) out[k + 4] = seq.toByte()
        return setLen(out)
    }

    /** Dash -> app packet: its TLVs. */
    fun parseIncoming(data: ByteArray): List<Tlv> {
        val tlvs = ArrayList<Tlv>()
        if (data.size < 8) return tlvs
        val count = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        var i = 8
        while (tlvs.size < count && i + 4 <= data.size) {
            val t = data[i].toInt() and 0xFF
            val s = data[i + 1].toInt() and 0xFF
            val n = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            i += 4
            tlvs.add(Tlv(t, s, data.copyOfRange(i, minOf(i + n, data.size))))
            i += n
        }
        return tlvs
    }

    // ── Auth ────────────────────────────────────────────────────────────────────

    fun authRequest() = hex("0016000200000000020100054b314720000804000101")

    fun authSendKey(ciphertext: ByteArray) = hex("0095000200000000020100054B3147200008000080") + ciphertext

    /** RSA-1024 + AES-256 handshake. The dash sends its modulus (07 00) and exponent (07 03),
     *  possibly in separate packets; we answer with RSA(ssid || random AES-256 key). */
    class Auth(private val ssid: String) {
        private var modulus: BigInteger? = null
        private var exponent: BigInteger? = null
        private var keySent = false

        sealed class Event {
            class Send(val packet: ByteArray) : Event()
            object Confirmed : Event()
            object Rejected : Event()
        }

        fun reset() { modulus = null; exponent = null; keySent = false }

        fun ingest(sub: Int, value: ByteArray): Event? {
            when (sub) {
                0x00 -> modulus = BigInteger(1, value)
                0x03 -> exponent = BigInteger(1, value)
                0x01 -> return if (value.isNotEmpty() && value[0].toInt() == 1) Event.Confirmed else Event.Rejected
                else -> return null
            }
            val m = modulus; val e = exponent
            if (!keySent && m != null && e != null) {
                keySent = true
                val sessionKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(m, e))
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, key) }
                return Event.Send(authSendKey(cipher.doFinal(ssid.toByteArray() + sessionKey)))
            }
            return null
        }
    }

    // ── Session setup ───────────────────────────────────────────────────────────

    fun timeSync(): ByteArray {
        val c = Calendar.getInstance()
        return build(Tlv(0x06, 0x06, byteArrayOf(c.get(Calendar.HOUR_OF_DAY).toByte(),
            c.get(Calendar.MINUTE).toByte(), c.get(Calendar.SECOND).toByte())))
    }

    fun hostnameAnnounce(hostname: String): ByteArray {
        val raw = hostname.toByteArray().take(200).toByteArray()
        return setLen(hex("0021000200000000020100054b314720") + byteArrayOf(1, 6, 0x0B, 0, (raw.size + 1).toByte()) + raw + ZERO)
    }

    fun initialBurst(hostname: String) = listOf(
        authRequest(),
        hostnameAnnounce(hostname),
        timeSync(),
        hex("0016000200000000020100054b314720030557000155"),
        hex("0016000200000000020100054b3147200405560001aa"),
        hex("0016000200000000020100054b3147200506050001aa"),
        hex("0016000200000000020100054b3147200605170001aa"),
        hex("001d000200000000020100054b314720080a020008aa55000000000000"),
        hex("0044000a00000000020100054b3147200906080001ff060300015506040001a2060f0001aa" +
            "0601000101054c000113052d00020000051b0001190521000132054d000132"),
    )

    fun navContext() = hex("0016000200000000020100054B31472000052E00011E")
    fun emptyLists() = hex("002A000600000000020100054B31472000052F0001000530000100053100010005320001000533000100")
    fun navStart() = hex("0016000200000000020100054B31472000068000010B")
    fun navPlaceholder() = build(Tlv(0x06, 0x0A, byteArrayOf(0, 0)))
    fun projectionFrame() = hex("0016000200000000020100054B314720000556000155")
    fun projectionStop() = hex("0016000200000000020100054B3147200005560001AA")
    fun projectionOff() = hex("0016000200000000020100054B3147200006050001AA")
    fun frameDecodedIdr() = hex("0016000200000000020100054B314720000611000155")
    fun frameDecodedP() = hex("0016000200000000020100054B314720000612000155")
    fun buttonAck(code: Int) = build(Tlv(0x06, 0x80, byteArrayOf(code.toByte())))

    // ── 1 Hz status heartbeat ───────────────────────────────────────────────────

    private val HB = hex("0049000b00000000020100054b3147200006080001050610000139060300015506040001a2060f0001aa" +
        "0601000101054c000113052d00020000051b0001190521000132054d000132")

    fun heartbeat(batteryPct: Int, charging: Boolean, tempC: Int = 25, headingDeg: Int = 0,
                  signal: Boolean = true, gpsOn: Boolean = true, mediaVolume: Int = 3, callVolume: Int = 9): ByteArray {
        val pkt = HB.copyOf()
        fun put(t: Int, s: Int, v: Int) {
            val at = indexOf(pkt, byteArrayOf(t.toByte(), s.toByte(), 0, 1))
            if (at >= 0 && at + 4 < pkt.size) pkt[at + 4] = v.toByte()
        }
        put(0x06, 0x04, batteryPct.coerceIn(0, 100) + 100)
        put(0x06, 0x0F, if (charging) 0x55 else 0xAA)
        put(0x06, 0x01, if (signal) 0x01 else 0x00)
        put(0x06, 0x03, if (gpsOn) 0x55 else 0xAA)
        put(0x05, 0x4C, 0x10 + mediaVolume.coerceIn(0, 10))
        put(0x05, 0x1B, 0x10 + callVolume.coerceIn(0, 10))
        put(0x06, 0x10, tempC + 40)
        val h = indexOf(pkt, byteArrayOf(0x05, 0x2D, 0, 2))
        val deg = ((headingDeg % 360) + 360) % 360
        if (h >= 0 && h + 5 < pkt.size) { pkt[h + 4] = (deg shr 8).toByte(); pkt[h + 5] = deg.toByte() }
        return pkt
    }

    // ── Route card ──────────────────────────────────────────────────────────────

    private val NAV_TEMPLATE = hex(
        "007e001100000000020100054b31472025050100145461696c6c65206465204d617320647520477200" +
        "050200013c050300013405050002000a05060001300507000130050800043033303305540001300509" +
        "0002004f0546000110050a000155050c000104050b0006303031303030055500012006050001aa060d0001aa")
    private val seqOff = indexOf(NAV_TEMPLATE, MAGIC) + 4
    private val titleLen = ((NAV_TEMPLATE[seqOff + 3].toInt() and 0xFF) shl 8) or (NAV_TEMPLATE[seqOff + 4].toInt() and 0xFF)
    private val NAV_PREFIX = NAV_TEMPLATE.copyOfRange(0, seqOff)
    private val NAV_SUFFIX = NAV_TEMPLATE.copyOfRange(seqOff + 5 + titleLen, NAV_TEMPLATE.size)

    const val NAV_NONE = 0xFF

    fun routeCard(title: String, projectionOn: Boolean, maneuver: Int? = null, primaryUnit: Int? = null,
                  totalDist: Int = 0, totalUnit: Int? = null, etaHhmm: String? = null, secondary: Int? = null,
                  secondaryDist: Int = 0, secondaryUnit: Int? = null): ByteArray {
        val rt = title.toByteArray().take(60).toByteArray() + ZERO
        val pkt = NAV_PREFIX + byteArrayOf(0, 0x05, 0x01, (rt.size shr 8).toByte(), rt.size.toByte()) + rt + NAV_SUFFIX
        fun patch1(t: Int, s: Int, v: Int) {
            val m = indexOf(pkt, byteArrayOf(t.toByte(), s.toByte(), 0, 1), fromEnd = true)
            if (m >= 0 && m + 4 < pkt.size) pkt[m + 4] = v.toByte()
        }
        fun patch2(t: Int, s: Int, v: Int) {
            val m = indexOf(pkt, byteArrayOf(t.toByte(), s.toByte(), 0, 2), fromEnd = true)
            if (m >= 0 && m + 5 < pkt.size) { pkt[m + 4] = (v shr 8).toByte(); pkt[m + 5] = v.toByte() }
        }
        patch1(0x06, 0x05, if (projectionOn) 0x55 else 0xAA)
        maneuver?.let { patch1(0x05, 0x02, it) }
        primaryUnit?.let { patch1(0x05, 0x06, it) }
        patch2(0x05, 0x09, totalDist)
        patch1(0x05, 0x03, secondary ?: NAV_NONE)
        patch2(0x05, 0x05, secondaryDist)
        secondaryUnit?.let { patch1(0x05, 0x07, it) }
        totalUnit?.let { patch1(0x05, 0x46, it) }
        if (etaHhmm != null && etaHhmm.length == 4) {
            val m = indexOf(pkt, byteArrayOf(0x05, 0x08, 0, 4), fromEnd = true)
            if (m >= 0 && m + 8 < pkt.size) etaHhmm.toByteArray().copyInto(pkt, m + 4)
        }
        return setLen(pkt)
    }

    /** Route card with no turn shown (Wayfindr's HIDE_TURN_WHEN_IDLE card). */
    fun idleRouteCard(title: String, projectionOn: Boolean) = routeCard(title, projectionOn, maneuver = NAV_NONE,
        primaryUnit = 0, totalDist = 0, totalUnit = 0, etaHhmm = "    ", secondary = NAV_NONE, secondaryDist = 0, secondaryUnit = 0)

    // ── Music widget ────────────────────────────────────────────────────────────

    private const val MEDIA_FIELD_MAX = 19   // the original app cuts each field to 19 chars

    /** One byte per char, like the original app: the dash font is single-byte. */
    private fun mediaField(s: String) = ByteArray(minOf(s.length, MEDIA_FIELD_MAX)) { minOf(s[it].code, 0xFF).toByte() }

    /** 05 17: music widget on (55) or off (AA); required before the dash shows a song. */
    fun musicActive(on: Boolean) = build(Tlv(0x05, 0x17, byteArrayOf((if (on) 0x55 else 0xAA).toByte())))

    /** 05 19: playing (55) or paused (AA). */
    fun musicPlaying(playing: Boolean) = build(Tlv(0x05, 0x19, byteArrayOf((if (playing) 0x55 else 0xAA).toByte())))

    /** 05 0D: title, album, artist, NUL-separated. */
    fun nowPlaying(title: String, album: String, artist: String) =
        build(Tlv(0x05, 0x0D, mediaField(title) + ZERO + mediaField(album) + ZERO + mediaField(artist)))
}

private val ZERO = byteArrayOf(0)
