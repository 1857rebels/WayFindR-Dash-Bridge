package com.wayfindr.hus

import android.content.Context

/**
 * Settings, the same ones as the Pi's /etc/default/wayfindr-pi where they still apply.
 * Coordinates are in Android Auto's 800x480 frame (AA_DPI 110, margins 240x180), measured on the
 * Pi on 2026-10-31 / 2026-11-01.
 */
data class Config(
    val dashPrefix: String = "RE_",
    val dashPassword: String = "12345678",
    /** Exact dash SSID once seen (the dash checks it in its auth handshake). */
    val dashSsid: String = "",
    val dashBssid: String = "",
    /** Driver seat location set on the phone (Android Auto > Advanced > Driver seat location). */
    val seat: String = "left",
    /** "scale,x,y" of the map on the dash; empty = the default for the seat. */
    val fillLayout: String = "",
    /** Android Auto's clock, signal and battery in a strip above the turn card. */
    val statusStrip: Boolean = true,
    /** turn | nosymbol | none | 0xNN (see the Pi README: the joystick needs a turn symbol). */
    val idleCard: String = "turn",
    val title: String = "Android Auto",
    val dpi: Int = 110,
    val fps: Int = 4,
    val bitrate: Int = 200_000,
    /** Android Auto's head unit server (Android Auto > Developer settings > Start head unit server). */
    val husPort: Int = 5277,
    /** App opened full screen once video starts: maps | media | phone | none. */
    val launch: String = "maps",
    /** default = default nav mode (fillLayout + idleCard); alternate = alternate view
     *  (altLayout, no turn circle). Switchable live from the app screen. */
    val view: String = "default",
    /** Alternate view map placement "scale,x,y" (the user's choice, 2026-11-01, left seat). */
    val altLayout: String = "0.900,39,35",
    /** "x,y[,scale]" of the clock / signal / battery strip: its top-left in the dash's 526x300
     *  frame and its size (1 = AA's own pixels), per view; empty = the standard 186,4 at 1.35.
     *  Set with the Dash Map Sizer. */
    val stripPos: String = "",
    val altStripPos: String = "",
) {
    val leftSeat get() = seat == "left"
    val alternate get() = view == "alternate"

    /** Route card in use: the alternate view blanks the turn symbol, so the dash draws no turn
     *  circle. The dash then sends no map-mode joystick presses (zoom / re-centre), as found on
     *  the Pi on 2026-10-31; its music-mode presses were not tested that way. */
    val routeCard get() = if (alternate) "nosymbol" else idleCard

    /** (scale, x, y) of the map on the dash. */
    fun layout(): Triple<Float, Int, Int> {
        val parts = (if (alternate) altLayout else fillLayout).split(",").map { it.trim() }
        if (parts.size == 3) runCatching {
            return Triple(parts[0].toFloat(), parts[1].toInt(), parts[2].toInt())
        }
        return if (leftSeat) Triple(0.876f, 87, 41) else Triple(0.846f, 100, 49)
    }

    /** Top-left and size of the status strip on the dash (STRIP_W x STRIP_H times scale). */
    fun strip(): StripPlace {
        val parts = (if (alternate) altStripPos else stripPos).split(",").map { it.trim() }
        if (parts.size in 2..3) runCatching {
            val scale = parts.getOrNull(2)?.toFloat() ?: 1f
            return StripPlace(parts[0].toInt(), parts[1].toInt(), scale.coerceIn(0.5f, 2.5f))
        }
        return StripPlace(186, 4, 1.35f)  // the user's standard, chosen in the sizer 2026-11-01
    }

    /** Part of AA's frame sent to the dash: the map and its button column beside AA's side bar. */
    val crop get() = if (leftSeat) Rect(176, 90, 504, 300) else Rect(120, 90, 500, 300)
    /** AA's side bar at DPI 110: signal + battery icons, then the clock under them. */
    val railIcons get() = if (leftSeat) Rect(125, 102, 44, 18) else Rect(630, 102, 44, 18)
    val railClock get() = if (leftSeat) Rect(121, 118, 52, 24) else Rect(626, 118, 52, 24)
    /** Strip where Maps draws "Re-centre" once the map has been moved. */
    val probe get() = if (leftSeat) Rect(300, 340, 340, 36) else Rect(190, 340, 240, 36)
    /** Spotify's green "now playing" button in its browse screens (left seat, DPI 110, captured
     *  2026-11-01). The same spot in its full-screen player is the repeat button, so it is tapped
     *  only when it is actually green. Not measured for the right seat. */
    val nowPlayingButton get() = if (leftSeat) Rect(622, 332, 44, 44) else null
    val zoomIn get() = if (leftSeat) Point(204, 313) else Point(596, 313)
    val zoomOut get() = if (leftSeat) Point(204, 357) else Point(596, 357)

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("dashPrefix", dashPrefix).putString("dashPassword", dashPassword)
            .putString("dashSsid", dashSsid).putString("dashBssid", dashBssid)
            .putString("seat", seat).putString("fillLayout", fillLayout)
            .putBoolean("statusStrip", statusStrip).putString("idleCard", idleCard)
            .putString("title", title).putInt("dpi", dpi).putInt("fps", fps)
            .putInt("bitrate", bitrate).putInt("husPort", husPort).putString("launch", launch)
            .putString("view", view).putString("altLayout", altLayout)
            .putString("stripPos", stripPos).putString("altStripPos", altStripPos)
            .apply()
    }

    companion object {
        private const val PREFS = "hus_config"
        /** Status strip size: AA's icons (44) + 8 + its clock (52) + 4, by the clock's 24 + 2. */
        const val STRIP_W = 108
        const val STRIP_H = 26

        fun load(context: Context): Config {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = Config()
            return Config(
                dashPrefix = p.getString("dashPrefix", d.dashPrefix)!!,
                dashPassword = p.getString("dashPassword", d.dashPassword)!!,
                dashSsid = p.getString("dashSsid", d.dashSsid)!!,
                dashBssid = p.getString("dashBssid", d.dashBssid)!!,
                seat = p.getString("seat", d.seat)!!,
                fillLayout = p.getString("fillLayout", d.fillLayout)!!,
                statusStrip = p.getBoolean("statusStrip", d.statusStrip),
                idleCard = p.getString("idleCard", d.idleCard)!!,
                title = p.getString("title", d.title)!!,
                dpi = p.getInt("dpi", d.dpi),
                fps = p.getInt("fps", d.fps),
                bitrate = p.getInt("bitrate", d.bitrate),
                husPort = p.getInt("husPort", d.husPort),
                launch = p.getString("launch", d.launch)!!,
                view = p.getString("view", d.view)!!,
                altLayout = p.getString("altLayout", d.altLayout)!!,
                stripPos = p.getString("stripPos", d.stripPos)!!,
                altStripPos = p.getString("altStripPos", d.altStripPos)!!,
            )
        }
    }
}

data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)
data class Point(val x: Int, val y: Int)
data class StripPlace(val x: Int, val y: Int, val scale: Float)
