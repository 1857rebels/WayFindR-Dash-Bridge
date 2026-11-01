package com.wayfindr.hus

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The bridge's log: logcat (tag HusBridge), the last lines for the screen, and bridge.log in
 *  the app's files folder (adb pull /sdcard/Android/data/com.wayfindr.hus/files/bridge.log). */
object BridgeLog {
    private const val KEEP_LINES = 400
    private const val MAX_FILE_BYTES = 2_000_000L

    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var file: File? = null

    @Volatile var listener: (() -> Unit)? = null

    fun init(context: Context) {
        val f = File(context.getExternalFilesDir(null) ?: context.filesDir, "bridge.log")
        if (f.length() > MAX_FILE_BYTES) f.renameTo(File(f.parentFile, "bridge.old.log"))
        file = f
    }

    fun log(msg: String) {
        Log.i("HusBridge", msg)
        val line = "${synchronized(time) { time.format(Date()) }} $msg"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > KEEP_LINES) lines.removeFirst()
            runCatching { file?.appendText(line + "\n") }
        }
        listener?.invoke()
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
}

fun log(msg: String) = BridgeLog.log(msg)
