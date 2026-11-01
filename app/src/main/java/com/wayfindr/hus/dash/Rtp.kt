package com.wayfindr.hus.dash

import java.util.Random

/** RFC 6184 for the dash: single NAL or FU-A (never STAP-A), marker on the last packet of each
 *  NAL, 90 kHz clock, payload <= 1380 bytes (Wayfindr's RtpPacketizer.kt). */
class RtpPacketizer(private val send: (ByteArray) -> Unit) {
    private companion object {
        const val MAX_PAYLOAD = 1380
        const val PT = 96
    }

    private val random = Random()
    private var seq = random.nextInt(0x10000)
    private val ssrc = random.nextInt()
    private val tsBase = random.nextInt()

    fun packetize(nal: ByteArray, ptsMs: Long) {
        val ts = tsBase + (ptsMs * 90).toInt()
        if (nal.size <= MAX_PAYLOAD) { emit(nal, 0, nal.size, null, true, ts); return }
        val nalType = nal[0].toInt() and 0x1F
        val fuInd = (nal[0].toInt() and 0xE0) or 28
        var offset = 1
        var first = true
        while (offset < nal.size) {
            val n = minOf(MAX_PAYLOAD - 2, nal.size - offset)
            val last = offset + n >= nal.size
            val fuHdr = (if (first) 0x80 else 0) or (if (last) 0x40 else 0) or nalType
            emit(nal, offset, n, byteArrayOf(fuInd.toByte(), fuHdr.toByte()), last, ts)
            offset += n
            first = false
        }
    }

    private fun emit(src: ByteArray, off: Int, len: Int, prefix: ByteArray?, marker: Boolean, ts: Int) {
        val p = prefix?.size ?: 0
        val pkt = ByteArray(12 + p + len)
        pkt[0] = 0x80.toByte()
        pkt[1] = ((if (marker) 0x80 else 0) or PT).toByte()
        pkt[2] = (seq shr 8).toByte(); pkt[3] = seq.toByte()
        for (i in 0 until 4) pkt[4 + i] = (ts shr (24 - 8 * i)).toByte()
        for (i in 0 until 4) pkt[8 + i] = (ssrc shr (24 - 8 * i)).toByte()
        prefix?.copyInto(pkt, 12)
        System.arraycopy(src, off, pkt, 12 + p, len)
        seq = (seq + 1) and 0xFFFF
        send(pkt)
    }
}

/** Caches SPS/PPS (SPS normalised to the stock phone's 67 42 00 29 shape the dash firmware
 *  whitelists), bundles them in front of every IDR, drops SEI/AUD (Wayfindr's NalProcessor.kt). */
class NalProcessor {
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    /** One encoder output buffer -> (unit, isIdr) pairs to send. */
    fun frameUnits(data: ByteArray): List<Pair<ByteArray, Boolean>> {
        val out = ArrayList<Pair<ByteArray, Boolean>>()
        for (nal in splitNals(data)) {
            when (val t = nal[0].toInt() and 0x1F) {
                7 -> sps = nal.copyOf().also { if (it.size >= 4 && it[1].toInt() == 0x42) { it[2] = 0; it[3] = 0x29 } }
                8 -> pps = nal
                5 -> {
                    val s = sps; val p = pps
                    out.add((if (s != null && p != null) s + START + p + START + nal else nal) to true)
                }
                6, 9 -> Unit
                else -> if (t in 1..4 || t in 10..12) out.add(nal to false)
            }
        }
        return out
    }

    private companion object {
        val START = byteArrayOf(0, 0, 0, 1)

        /** Annex-B byte stream -> NAL units (start codes removed). */
        fun splitNals(data: ByteArray): List<ByteArray> {
            val codes = ArrayList<IntArray>()   // (start code begin, payload begin)
            var i = 0
            while (i + 2 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                    codes.add(intArrayOf(if (i > 0 && data[i - 1].toInt() == 0) i - 1 else i, i + 3))
                    i += 3
                } else i++
            }
            return codes.indices.mapNotNull { k ->
                val begin = codes[k][1]
                val end = if (k + 1 < codes.size) codes[k + 1][0] else data.size
                if (end > begin) data.copyOfRange(begin, end) else null
            }
        }
    }
}
