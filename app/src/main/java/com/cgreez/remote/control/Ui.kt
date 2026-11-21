package com.cgreez.remote.control

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Colours used across the app. */
object Pal {
    val bg = Color.parseColor("#0D0F12")
    val surface = Color.parseColor("#16191E")
    val key = Color.parseColor("#1E2228")
    val line = Color.parseColor("#262B32")
    val text = Color.parseColor("#E8EBEF")
    val muted = Color.parseColor("#7D8793")
    val accent = Color.parseColor("#4FD1C5")
    val onAccent = Color.parseColor("#06201D")
    val danger = Color.parseColor("#F0605D")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Int): Float = v * resources.displayMetrics.density

fun roundRect(color: Int, radiusPx: Float): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = radiusPx
    return g
}

fun circle(color: Int): GradientDrawable {
    val g = GradientDrawable()
    g.shape = GradientDrawable.OVAL
    g.setColor(color)
    return g
}

/** Gentle press animation for ordinary buttons. Does not consume the touch, so clicks still work. */
@SuppressLint("ClickableViewAccessibility")
fun pressFx(v: View) {
    v.setOnTouchListener { view, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }
        false
    }
}

/**
 * Makes a view act like a remote key: it fires on touch-down and, when [repeat] is true,
 * keeps firing while the finger stays down (hold to repeat).
 */
@SuppressLint("ClickableViewAccessibility")
fun attachPress(v: View, repeat: Boolean, interval: Long, onPress: () -> Unit) {
    val rep = HoldRepeater(Handler(Looper.getMainLooper()))
    fun release() {
        rep.stop()
        v.alpha = 1f
        v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
    }
    v.setOnTouchListener { _, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                v.alpha = 0.7f
                v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(60).start()
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onPress()
                if (repeat) rep.start(interval = interval, action = onPress)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
        true
    }
}

/**
 * A round or rounded-rectangle icon button. With [hold] it behaves like a remote key (fires on
 * touch-down, repeats while held); otherwise it is an ordinary tap button.
 */
fun Context.iconButton(
    icon: Int,
    sizeDp: Int,
    iconDp: Int,
    fill: Int,
    tint: Int,
    desc: String,
    widthDp: Int = sizeDp,
    cornerDp: Int = -1,
    hold: Boolean = false,
    repeat: Boolean = false,
    interval: Long = 110L,
    onPress: () -> Unit
): FrameLayout {
    val f = FrameLayout(this)
    val shape: GradientDrawable = if (cornerDp < 0) circle(fill) else roundRect(fill, dpf(cornerDp))
    f.contentDescription = desc
    val iv = ImageView(this)
    iv.setImageResource(icon)
    iv.setColorFilter(tint)
    f.addView(iv, FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
    f.layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(sizeDp))
    if (hold) {
        f.background = shape
        attachPress(f, repeat, interval, onPress)
    } else {
        f.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
        f.isClickable = true
        f.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onPress()
        }
        pressFx(f)
    }
    return f
}

/** The ImageView inside a button made by [iconButton]. */
fun FrameLayout.iconOf(): ImageView = getChildAt(0) as ImageView

/** A text pill button. */
fun Context.pillButton(
    label: String,
    fill: Int,
    textColor: Int,
    heightDp: Int = 52,
    textSp: Float = 15f,
    onClick: () -> Unit
): TextView {
    val b = TextView(this)
    b.text = label
    b.setTextColor(textColor)
    b.textSize = textSp
    b.setTypeface(b.typeface, Typeface.BOLD)
    b.gravity = Gravity.CENTER
    b.minHeight = dp(heightDp)
    b.setSingleLine(true)
    b.ellipsize = android.text.TextUtils.TruncateAt.END
    b.setPadding(dp(10), 0, dp(10), 0)
    b.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), roundRect(fill, dpf(heightDp)), null)
    b.isClickable = true
    b.setOnClickListener {
        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onClick()
    }
    pressFx(b)
    return b
}

/** A rounded app-icon tile with the app's real icon (loaded from the TV), a letter until it arrives, and a label. */
fun Context.appTile(app: TvApp, sizeDp: Int, widthDp: Int, onClick: () -> Unit): LinearLayout {
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.gravity = Gravity.CENTER_HORIZONTAL

    var tileColor = Pal.key
    if (app.bg.isNotEmpty()) {
        try {
            tileColor = Color.parseColor(app.bg)
        } catch (e: Exception) {
        }
    }
    val radius = dpf(sizeDp / 4)
    val tile = FrameLayout(this)
    tile.background = roundRect(tileColor, radius)
    tile.clipToOutline = true
    tile.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    val letter = TextView(this)
    letter.text = app.title.take(1).uppercase()
    letter.setTextColor(Pal.text)
    letter.textSize = (sizeDp / 2.4f)
    letter.gravity = Gravity.CENTER
    tile.addView(letter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    val img = ImageView(this)
    img.scaleType = ImageView.ScaleType.FIT_CENTER
    tile.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    TvHub.loadIcon(app) { bmp ->
        if (bmp != null) {
            img.setImageBitmap(bmp)
            letter.visibility = View.GONE
        }
    }

    col.addView(tile, LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
    val label = TextView(this)
    label.text = app.title
    label.setTextColor(Pal.muted)
    label.textSize = 11f
    label.maxLines = 1
    label.ellipsize = android.text.TextUtils.TruncateAt.END
    label.gravity = Gravity.CENTER
    val lp = LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.topMargin = dp(6)
    col.addView(label, lp)

    col.isClickable = true
    col.setOnClickListener {
        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onClick()
    }
    pressFx(col)
    col.layoutParams = LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
    return col
}

/**
 * Round directional pad with OK in the middle. The arrow zones fire on touch and repeat while
 * held. OK fires once on touch and never repeats, however long it's held - holding it registers
 * as one OK press (a hold), not a burst of OK, OK, OK.
 */
class DPadView(
    context: Context,
    private val onKey: (String) -> Unit
) : View(context) {

    private val zNone = 0
    private val zUp = 1
    private val zDown = 2
    private val zLeft = 3
    private val zRight = 4
    private val zOk = 5

    private var zone = zNone
    private val rep = HoldRepeater(Handler(Looper.getMainLooper()))

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.surface }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.line
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(1)
    }
    private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
    private val okPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.accent }
    private val okTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.onAccent
        textAlign = Paint.Align.CENTER
        textSize = context.dpf(20)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val oval = RectF()

    private val chevrons = listOf(
        context.getDrawable(R.drawable.ic_chev_up)!!.mutate(),
        context.getDrawable(R.drawable.ic_chev_down)!!.mutate(),
        context.getDrawable(R.drawable.ic_chev_left)!!.mutate(),
        context.getDrawable(R.drawable.ic_chev_right)!!.mutate()
    )

    init {
        for (c in chevrons) c.setTint(Pal.text)
        contentDescription = "Directional pad"
    }

    override fun onDetachedFromWindow() {
        rep.stop()
        super.onDetachedFromWindow()
    }

    private fun cx() = width / 2f
    private fun cy() = height / 2f
    private fun outerR() = min(width, height) / 2f - context.dpf(2)
    private fun okR() = outerR() * 0.37f

    override fun onDraw(canvas: Canvas) {
        val cx = cx()
        val cy = cy()
        val r = outerR()
        canvas.drawCircle(cx, cy, r, ringPaint)
        canvas.drawCircle(cx, cy, r, edgePaint)

        oval.set(cx - r, cy - r, cx + r, cy + r)
        when (zone) {
            zUp -> canvas.drawArc(oval, 225f, 90f, true, hiPaint)
            zDown -> canvas.drawArc(oval, 45f, 90f, true, hiPaint)
            zLeft -> canvas.drawArc(oval, 135f, 90f, true, hiPaint)
            zRight -> canvas.drawArc(oval, -45f, 90f, true, hiPaint)
        }

        val okR = okR()
        okPaint.alpha = if (zone == zOk) 170 else 255
        canvas.drawCircle(cx, cy, okR, okPaint)
        canvas.drawText("OK", cx, cy + okTextPaint.textSize * 0.35f, okTextPaint)

        val d = (r + okR) / 2f
        val half = context.dp(15)
        place(canvas, chevrons[0], cx, cy - d, half)
        place(canvas, chevrons[1], cx, cy + d, half)
        place(canvas, chevrons[2], cx - d, cy, half)
        place(canvas, chevrons[3], cx + d, cy, half)
    }

    private fun place(canvas: Canvas, d: android.graphics.drawable.Drawable, x: Float, y: Float, half: Int) {
        d.setBounds((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt())
        d.draw(canvas)
    }

    private fun zoneAt(x: Float, y: Float): Int {
        val dx = x - cx()
        val dy = y - cy()
        val dist = hypot(dx, dy)
        if (dist <= okR()) return zOk
        if (dist > outerR()) return zNone
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) zRight else zLeft
        } else {
            if (dy > 0) zDown else zUp
        }
    }

    private fun nameOf(z: Int): String = when (z) {
        zUp -> "UP"
        zDown -> "DOWN"
        zLeft -> "LEFT"
        zRight -> "RIGHT"
        else -> "ENTER"
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private var okFired = false
    private val okHoldTask = Runnable {
        okFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        TvHub.okHold()
    }

    private fun setZone(z: Int) {
        if (z == zone) return
        val was = zone
        zone = z
        rep.stop()
        uiHandler.removeCallbacks(okHoldTask)
        // OK works like Back: letting go quickly is a tap (OK, sent on release); holding it sends
        // one hold and nothing more, however long the finger stays down.
        if (was == zOk && !okFired) onKey("ENTER")
        okFired = false
        invalidate()
        if (z == zOk) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            uiHandler.postDelayed(okHoldTask, TvHub.OK_HOLD_MS)
        } else if (z != zNone) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val name = nameOf(z)
            onKey(name)
            rep.start(action = { onKey(name) })
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                setZone(zoneAt(e.x, e.y))
            }
            MotionEvent.ACTION_MOVE -> setZone(zoneAt(e.x, e.y))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setZone(zNone)
        }
        return true
    }
}
