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
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Colours used across the app. Call [apply] before building any screen. */
object Pal {
    var dark = true
    var bg = 0
    var surface = 0
    var key = 0
    var line = 0
    var text = 0
    var muted = 0
    var accent = 0
    var onAccent = 0
    var danger = 0

    val accentNames = listOf("blue", "green", "orange", "purple", "grey")
    private val darkAccent = mapOf(
        "blue" to "#5B9DFF", "green" to "#4CD08A", "orange" to "#FF9F4A",
        "purple" to "#A98BFF", "grey" to "#B4BCC7"
    )
    private val lightAccent = mapOf(
        "blue" to "#2F6FE4", "green" to "#1FA463", "orange" to "#E8750F",
        "purple" to "#7C4DDB", "grey" to "#5F6873"
    )

    fun swatch(name: String, dark: Boolean): Int =
        Color.parseColor((if (dark) darkAccent else lightAccent)[name] ?: "#5B9DFF")

    fun apply(isDark: Boolean, accentName: String) {
        dark = isDark
        if (isDark) {
            bg = Color.parseColor("#0F1115")
            surface = Color.parseColor("#1A1D23")
            key = Color.parseColor("#252A32")
            line = Color.parseColor("#2E343D")
            text = Color.parseColor("#EDEFF3")
            muted = Color.parseColor("#8A93A0")
            onAccent = Color.parseColor("#0B1220")
            danger = Color.parseColor("#FF6B68")
        } else {
            bg = Color.parseColor("#F2F4F8")
            surface = Color.parseColor("#FFFFFF")
            key = Color.parseColor("#E8ECF2")
            line = Color.parseColor("#D8DDE5")
            text = Color.parseColor("#14181F")
            muted = Color.parseColor("#66707D")
            onAccent = Color.parseColor("#FFFFFF")
            danger = Color.parseColor("#D9403D")
        }
        accent = swatch(accentName, isDark)
    }

    init { apply(true, "blue") }

    fun withAlpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)
}

/** Blends a colour toward white (f > 0) or black (f < 0). */
fun shade(color: Int, f: Float): Int {
    val t = if (f >= 0) 255 else 0
    val k = kotlin.math.abs(f)
    val r = (Color.red(color) + (t - Color.red(color)) * k).toInt()
    val g = (Color.green(color) + (t - Color.green(color)) * k).toInt()
    val b = (Color.blue(color) + (t - Color.blue(color)) * k).toInt()
    return Color.argb(Color.alpha(color), r, g, b)
}

/** Button background: soft top-to-bottom gradient with a thin edge. Accent fills get a glow-like lift instead of an edge. */
fun Context.btnShape(fill: Int, radiusPx: Float, oval: Boolean = false): GradientDrawable {
    if (fill == Color.TRANSPARENT) return if (oval) circle(fill) else roundRect(fill, radiusPx)
    val isAccent = fill == Pal.accent
    val top = if (isAccent) shade(fill, 0.18f) else shade(fill, if (Pal.dark) 0.06f else -0.03f)
    val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, fill))
    if (oval) g.shape = GradientDrawable.OVAL else g.cornerRadius = radiusPx
    if (!isAccent) g.setStroke(dp(1), Pal.line)
    return g
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

private fun pressDown(v: View, scale: Float) {
    v.animate().cancel()
    v.animate().scaleX(scale).scaleY(scale).setDuration(90)
        .setInterpolator(DecelerateInterpolator()).start()
}

private fun pressUp(v: View) {
    v.animate().cancel()
    v.animate().scaleX(1f).scaleY(1f).setDuration(280)
        .setInterpolator(OvershootInterpolator(2.4f)).start()
}

/** Gentle springy press animation for ordinary buttons. Does not consume the touch, so clicks still work. */
@SuppressLint("ClickableViewAccessibility")
fun pressFx(v: View) {
    v.setOnTouchListener { view, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressDown(view, 0.95f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressUp(view)
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
        v.animate().alpha(1f).setDuration(220).start()
        pressUp(v)
    }
    v.setOnTouchListener { _, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                v.animate().alpha(0.75f).setDuration(90).start()
                pressDown(v, 0.93f)
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onPress()
                if (repeat) rep.start(interval = interval, action = onPress)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
        true
    }
}

fun rippleColor(): ColorStateList = ColorStateList.valueOf(Pal.withAlpha(Pal.accent, 0x40))

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
    val shape: GradientDrawable = btnShape(fill, if (cornerDp < 0) 0f else dpf(cornerDp), cornerDp < 0)
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
        f.background = RippleDrawable(rippleColor(), shape, null)
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
    b.background = RippleDrawable(rippleColor(), btnShape(fill, dpf(heightDp)), null)
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
 * held. OK: a quick tap sends one OK (on release); holding past [TvHub.okHoldMs] starts a hold,
 * shown by a ring filling around OK, and works as chosen in Settings' "OK hold" mode.
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
    private var hiZone = zNone
    private var hi = 0f
    private var ring = 0f
    private var hiAnim: ValueAnimator? = null
    private var ringAnim: ValueAnimator? = null
    private val rep = HoldRepeater(Handler(Looper.getMainLooper()))

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.surface }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.line
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(1)
    }
    private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.accent }
    private val okPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.accent
        setShadowLayer(context.dpf(10), 0f, context.dpf(3), Pal.withAlpha(Pal.accent, 110))
    }
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.accent
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(3)
        strokeCap = Paint.Cap.ROUND
    }
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
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        contentDescription = "Directional pad"
    }

    override fun onDetachedFromWindow() {
        rep.stop()
        hiAnim?.cancel()
        ringAnim?.cancel()
        uiHandler.removeCallbacks(okHoldTask)
        TvHub.okHoldEnd()
        super.onDetachedFromWindow()
    }

    private fun cx() = width / 2f
    private fun cy() = height / 2f
    private fun outerR() = min(width, height) / 2f - context.dpf(6)
    private fun okR() = outerR() * 0.37f

    override fun onDraw(canvas: Canvas) {
        val cx = cx()
        val cy = cy()
        val r = outerR()
        canvas.drawCircle(cx, cy, r, ringPaint)
        canvas.drawCircle(cx, cy, r, edgePaint)

        oval.set(cx - r, cy - r, cx + r, cy + r)
        if (hi > 0f && hiZone != zOk && hiZone != zNone) {
            hiPaint.alpha = (hi * 70).toInt()
            when (hiZone) {
                zUp -> canvas.drawArc(oval, 225f, 90f, true, hiPaint)
                zDown -> canvas.drawArc(oval, 45f, 90f, true, hiPaint)
                zLeft -> canvas.drawArc(oval, 135f, 90f, true, hiPaint)
                zRight -> canvas.drawArc(oval, -45f, 90f, true, hiPaint)
            }
        }

        val okScale = if (hiZone == zOk) 1f - 0.07f * hi else 1f
        val okR = okR() * okScale
        canvas.drawCircle(cx, cy, okR, okPaint)
        canvas.drawText("OK", cx, cy + okTextPaint.textSize * 0.35f, okTextPaint)

        if (ring > 0f) {
            val rr = okR() + context.dpf(8)
            oval.set(cx - rr, cy - rr, cx + rr, cy + rr)
            holdPaint.alpha = 220
            canvas.drawArc(oval, -90f, 360f * ring, false, holdPaint)
        }

        val d = (r + okR()) / 2f + context.dpf(3)
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

    private fun animHi(to: Float) {
        hiAnim?.cancel()
        val a = ValueAnimator.ofFloat(hi, to)
        a.duration = if (to > hi) 90L else 260L
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            hi = it.animatedValue as Float
            invalidate()
        }
        hiAnim = a
        a.start()
    }

    private fun animRing(start: Boolean) {
        ringAnim?.cancel()
        if (start) {
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.duration = TvHub.okHoldMs
            a.interpolator = LinearInterpolator()
            a.addUpdateListener {
                ring = it.animatedValue as Float
                invalidate()
            }
            ringAnim = a
            a.start()
        } else if (ring > 0f) {
            val a = ValueAnimator.ofFloat(ring, 0f)
            a.duration = 200L
            a.addUpdateListener {
                ring = it.animatedValue as Float
                invalidate()
            }
            ringAnim = a
            a.start()
        }
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private var okFired = false
    private val okHoldTask = Runnable {
        okFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val real = TvHub.okHoldStart()
        if (!real && TvHub.okHoldRepeat) {
            rep.start(initialDelay = TvHub.okHoldRepeatMs, interval = TvHub.okHoldRepeatMs) { TvHub.okHoldStart() }
        }
    }

    private fun setZone(z: Int) {
        if (z == zone) return
        val was = zone
        zone = z
        rep.stop()
        uiHandler.removeCallbacks(okHoldTask)
        if (was == zOk) {
            if (!okFired) onKey("ENTER") else TvHub.okHoldEnd()
            animRing(false)
        }
        okFired = false
        if (z == zNone) {
            animHi(0f)
        } else {
            hiZone = z
            animHi(1f)
        }
        if (z == zOk) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            animRing(true)
            uiHandler.postDelayed(okHoldTask, TvHub.okHoldMs)
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
