package com.wayfindr.hus.aa

import java.io.ByteArrayOutputStream

/** Minimal protobuf (proto2) writer for the few Android Auto messages the head unit sends.
 *  Field numbers are from aasdk_proto (openDsh/aasdk). */
class Pb {
    private val out = ByteArrayOutputStream()

    fun int(field: Int, v: Long) = apply { tag(field, 0); varint(v) }
    fun int(field: Int, v: Int) = int(field, v.toLong())
    fun bool(field: Int, v: Boolean) = int(field, if (v) 1L else 0L)
    fun str(field: Int, v: String) = bytes(field, v.toByteArray())
    fun msg(field: Int, m: Pb) = bytes(field, m.toBytes())
    fun bytes(field: Int, v: ByteArray) = apply {
        tag(field, 2); varint(v.size.toLong()); out.write(v)
    }

    fun toBytes(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    private fun varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}

/** Parsed protobuf message: field number -> values (Long for varint/fixed, ByteArray for
 *  length-delimited). */
class PbMsg(bytes: ByteArray, offset: Int = 0, end: Int = bytes.size) {
    private val fields = HashMap<Int, MutableList<Any>>()

    init {
        var i = offset
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (i < end) {
                val b = bytes[i++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }
        while (i < end) {
            val key = varint()
            val field = (key ushr 3).toInt()
            val value: Any = when ((key and 7).toInt()) {
                0 -> varint()
                1 -> { var v = 0L; for (k in 0 until 8) v = v or ((bytes[i + k].toLong() and 0xFF) shl (8 * k)); i += 8; v }
                2 -> { val n = varint().toInt(); val v = bytes.copyOfRange(i, i + n); i += n; v }
                5 -> { var v = 0L; for (k in 0 until 4) v = v or ((bytes[i + k].toLong() and 0xFF) shl (8 * k)); i += 4; v }
                else -> break
            }
            fields.getOrPut(field) { ArrayList() }.add(value)
        }
    }

    fun long(field: Int, default: Long = 0): Long = fields[field]?.firstOrNull() as? Long ?: default
    fun int(field: Int, default: Int = 0): Int = long(field, default.toLong()).toInt()
    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray
    fun str(field: Int): String = bytes(field)?.toString(Charsets.UTF_8) ?: ""
    fun msg(field: Int): PbMsg? = bytes(field)?.let { PbMsg(it) }
    fun msgs(field: Int): List<PbMsg> = fields[field].orEmpty().filterIsInstance<ByteArray>().map { PbMsg(it) }
}
