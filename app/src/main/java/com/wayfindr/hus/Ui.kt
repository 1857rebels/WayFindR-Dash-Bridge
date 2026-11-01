package com.wayfindr.hus

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Colours and small view builders for the app screen (no libraries, so no Material). Dark only:
 *  the phone is usually looked at on the bike. */
object Ui {
    const val BG = 0xFF0E1116.toInt()
    const val SURFACE = 0xFF171C24.toInt()
    const val FIELD = 0xFF222935.toInt()
    const val OUTLINE = 0xFF2E3746.toInt()
    const val TEXT = 0xFFE9EDF3.toInt()
    const val MUTED = 0xFF8D97A8.toInt()
    const val ACCENT = 0xFFFF8A3D.toInt()
    const val ON_ACCENT = 0xFF1C0F05.toInt()
    const val GOOD = 0xFF3DDC84.toInt()
    const val WAIT = 0xFFF5B942.toInt()
    const val OFF = 0xFF5B6475.toInt()
    const val DANGER = 0xFFFF6B6B.toInt()

    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

fun Context.dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radiusDp: Number, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
    if (strokeColor != 0) setStroke(dp(1), strokeColor)
}

/** [bg] with a touch ripple clipped to the same rounded shape. */
fun Context.pressable(bg: Drawable, radiusDp: Number): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), bg, rounded(0xFFFFFFFF.toInt(), radiusDp))

fun Context.text(s: CharSequence, sizeSp: Float = 14f, color: Int = Ui.TEXT, medium: Boolean = false) =
    TextView(this).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (medium) typeface = Ui.MEDIUM
        setLineSpacing(0f, 1.15f)
    }

/** Small upper-case heading inside a card. */
fun Context.overline(s: String) = text(s.uppercase(), 11.5f, Ui.MUTED, medium = true).apply {
    letterSpacing = 0.08f
}

fun Context.vertical(block: LinearLayout.() -> Unit = {}) =
    LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; block() }

fun Context.horizontal(block: LinearLayout.() -> Unit = {}) =
    LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; block() }

fun <T : View> T.margins(top: Int = 0, bottom: Int = 0): T = apply {
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .also { it.topMargin = top; it.bottomMargin = bottom }
}

/** A full-width button drawn from a TextView, so it has no platform styling or ALL CAPS. */
fun Context.button(label: String, onClick: () -> Unit) = text(label, 15f, Ui.TEXT, medium = true).apply {
    gravity = Gravity.CENTER
    minHeight = dp(50)
    setPadding(dp(16), 0, dp(16), 0)
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
    styleButton(this, filled = false)
}

fun Context.styleButton(b: TextView, filled: Boolean, color: Int = Ui.ACCENT) {
    if (filled) {
        b.background = pressable(rounded(color, 14), 14)
        b.setTextColor(Ui.ON_ACCENT)
    } else {
        b.background = pressable(rounded(Ui.FIELD, 14, if (color == Ui.ACCENT) Ui.OUTLINE else color), 14)
        b.setTextColor(if (color == Ui.ACCENT) Ui.TEXT else color)
    }
}

/** Labelled single-line text field. */
fun Context.field(parent: LinearLayout, label: String, value: String, hint: String = "", numeric: Boolean = false): EditText {
    parent.addView(text(label, 13f, Ui.MUTED).margins(top = dp(12), bottom = dp(6)))
    return EditText(this).apply {
        setText(value)
        this.hint = hint
        setSingleLine()
        textSize = 15f
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.OFF)
        highlightColor = 0x55FF8A3D
        background = rounded(Ui.FIELD, 12, Ui.OUTLINE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }.also(parent::addView)
}

/** A row of mutually exclusive choices in a rounded track; [select] does not call [onPick]. */
class Segmented(
    context: Context,
    private val options: List<Pair<String, String>>,
    private val onPick: (String) -> Unit,
) : LinearLayout(context) {
    private val items = LinkedHashMap<String, TextView>()
    var selected: String? = null
        private set

    init {
        orientation = HORIZONTAL
        val inset = context.dp(4)
        setPadding(inset, inset, inset, inset)
        background = context.rounded(Ui.FIELD, 14, Ui.OUTLINE)
        for ((key, label) in options) {
            val item = context.text(label, 14.5f, Ui.MUTED, medium = true).apply {
                gravity = Gravity.CENTER
                minHeight = context.dp(42)
                setPadding(context.dp(8), 0, context.dp(8), 0)
                isClickable = true
                setOnClickListener { if (selected != key) { select(key); onPick(key) } }
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
            items[key] = item
            addView(item)
        }
    }

    fun select(key: String) {
        selected = key
        items.forEach { (k, v) ->
            val on = k == key
            v.background = if (on) context.rounded(Ui.ACCENT, 11) else context.pressable(context.rounded(0, 11), 11)
            v.setTextColor(if (on) Ui.ON_ACCENT else Ui.MUTED)
        }
    }
}
