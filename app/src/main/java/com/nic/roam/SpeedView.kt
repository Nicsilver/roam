package com.nic.roam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws the whole UI itself so the readout can be placed at an arbitrary offset.
 * All the burn-in mitigation lives here:
 *  - the block jumps to a new spot every few minutes, sliding briefly so the move reads as
 *    intentional rather than a glitch
 *  - hue drift, so no single subpixel carries the load for long
 *  - pure black background and an optional outline digit style, which lights far fewer pixels
 */
enum class SpeedUnit(val pref: String, val perKmh: Float) {
    KMH("kmh", 1f),
    MPH("mph", 0.621371f),
    KNOTS("kn", 0.539957f);

    companion object {
        fun fromPref(value: String?) = values().firstOrNull { it.pref == value } ?: KMH
    }
}

class SpeedView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var speedKmh = 0f
    var hasFix = false
    // Once any fix has arrived, losing it almost always means the phone is parked somewhere the
    // GPS chip has gone quiet, so 0 is a better answer than a "no signal" placeholder.
    var hadFix = false
    var stale = false
    var maxKmh = 0f

    var unit = SpeedUnit.KMH
    var roam = true
    var colorShift = true
    var outline = false
    var showMax = true
    var showHeading = true
    var baseColor = Color.WHITE
    // HUD use: the phone lies flat under the windshield, which shows the screen reflected.
    var mirror = false
    var speedScale = 1f
    var labelScale = 1f
    // Course over ground in degrees, or -1 when there is none. GPS bearing is meaningless at a
    // standstill, so MainActivity clears it below a small speed rather than us guessing here.
    var headingDeg = -1f
    var moveIntervalSec = 180f

    // Teko (SIL OFL), tall and condensed, for the digits. The outline style uses a second copy
    // converted offline to clean single-line hollow outlines — one line per digit, counters and
    // all. Stroking the solid face at draw time instead would trace both walls of every stem and
    // cross itself at the tight junctions.
    private val fillTypeface =
        Typeface.createFromAsset(context.assets, "fonts/Teko-digits.ttf")
    private val outlineTypeface =
        Typeface.createFromAsset(context.assets, "fonts/Teko-digits-outline.ttf")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = fillTypeface
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.createFromAsset(context.assets, "fonts/Teko-text.ttf")
    }

    // Drag callbacks for the brightness swipe. The fraction is the distance dragged since the
    // drag began, in screen heights, positive upwards.
    var swipeEnabled = true
    var onBrightnessDragStart: (() -> Unit)? = null
    var onBrightnessDrag: ((Float) -> Unit)? = null
    var onBrightnessDragEnd: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    // The system owns the screen edges (back, home, revealing the bars), so a drag that starts
    // there is never ours.
    private val edgeGuard = 24 * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var trackingDrag = false
    private var dragging = false
    private var dragBaseY = 0f

    // A plain View clicks on any down/up pair inside it, so an edge swipe for back or the
    // system bars would also toggle the settings panel. Only a real tap counts.
    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            performClick()
            return true
        }
    })

    private fun endDrag() {
        if (dragging) onBrightnessDragEnd?.invoke()
        dragging = false
        trackingDrag = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                trackingDrag = swipeEnabled &&
                    downX > edgeGuard && downX < width - edgeGuard &&
                    downY > edgeGuard && downY < height - edgeGuard
            }
            MotionEvent.ACTION_MOVE -> {
                if (trackingDrag && !dragging) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        // Mostly horizontal drags are not ours; stop looking at this gesture.
                        if (abs(dy) > abs(dx) * 1.5f) {
                            dragging = true
                            // Start from here so the value does not jump by the slop distance.
                            dragBaseY = event.y
                            onBrightnessDragStart?.invoke()
                            val cancel = MotionEvent.obtain(event)
                            cancel.action = MotionEvent.ACTION_CANCEL
                            tapDetector.onTouchEvent(cancel)
                            cancel.recycle()
                        } else {
                            trackingDrag = false
                        }
                    }
                }
                if (dragging) {
                    onBrightnessDrag?.invoke((dragBaseY - event.y) / height)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    endDrag()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> endDrag()
        }
        return if (dragging) true else tapDetector.onTouchEvent(event)
    }

    private var indicatorPct = 0
    private var indicatorHideAt = 0L
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Shows the brightness bar for a moment; call again to keep it up while dragging. */
    fun flashBrightness(pct: Int) {
        indicatorPct = pct
        indicatorHideAt = SystemClock.elapsedRealtime() + INDICATOR_MS
        invalidate()
    }

    private fun indicatorVisible() = SystemClock.elapsedRealtime() < indicatorHideAt

    private val startedAt = SystemClock.elapsedRealtime()
    private var running = false
    private var sliding = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, if (sliding || indicatorVisible()) SLIDE_FRAME_MS else IDLE_FRAME_MS)
        }
    }

    fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        removeCallbacks(tick)
        if (value) post(tick)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        setRunning(false)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        // Padding carries the camera cutout's safe insets; the readout lays out inside them.
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        if (w <= 0f || h <= 0f) return

        val t = (SystemClock.elapsedRealtime() - startedAt) / 1000.0

        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        if (mirror) canvas.scale(-1f, 1f, w / 2f, h / 2f)

        paint.typeface = if (outline) outlineTypeface else fillTypeface
        // Labels hang off the unscaled size so the speed slider and the label slider stay
        // independent of each other.
        val baseSize = baseTextSize(w, h)
        val headingSize = baseSize * 0.13f * labelScale
        val maxSize = baseSize * 0.11f * labelScale
        val bigSize = digitTextSize(w, h, baseSize, headingSize, maxSize)
        paint.textSize = bigSize

        val speed = speedKmh * unit.perKmh
        val digits = if (!hasFix) "0" else speed.roundToInt().coerceAtLeast(0).toString()

        val digitsW = paint.measureText(digits)
        val gap = baseSize * 0.10f
        // Cap height, not the font's full line height: digits have no descenders, so using
        // the metrics directly would leave the block visibly high in the safe area.
        val digitsH = bigSize * 0.72f
        val headingLine =
            if (showHeading && hasFix && headingDeg >= 0f) headingLabel(headingDeg) else null
        val maxLine = if (showMax && maxKmh > 0f) {
            val m = maxKmh * unit.perKmh
            "max ${m.roundToInt()}"
        } else null

        // Each label line is a fixed gap plus its own size, so large labels do not open up a
        // wide hole under the digits. At 100% this matches the original spacing.
        var blockH = digitsH
        if (headingLine != null) blockH += gap + headingSize * HEADING_ADVANCE
        if (maxLine != null) blockH += gap + maxSize * MAX_ADVANCE
        // Big labels can be wider than a one-digit speed; the roam range must keep them on screen.
        var blockW = digitsW
        if (headingLine != null) {
            labelPaint.textSize = headingSize
            blockW = max(blockW, labelPaint.measureText(headingLine))
        }
        if (maxLine != null) {
            labelPaint.textSize = maxSize
            blockW = max(blockW, labelPaint.measureText(maxLine))
        }

        val margin = min(w, h) * 0.03f
        val ax = ((w - blockW) / 2f - margin).coerceAtLeast(0f)
        val ay = ((h - blockH) / 2f - margin).coerceAtLeast(0f)

        var dx = 0f
        var dy = 0f
        sliding = false
        if (roam) {
            val step = (t / moveIntervalSec).toInt()
            val into = (t - step * moveIntervalSec).toFloat()
            var k = if (step == 0) 1f else (into / SLIDE_SECONDS).coerceIn(0f, 1f)
            k = k * k * (3f - 2f * k)
            sliding = k < 1f
            dx = lerp(slotX(step - 1), slotX(step), k) * ax
            dy = lerp(slotY(step - 1), slotY(step), k) * ay
        }

        val cx = w / 2f + dx
        val top = (h - blockH) / 2f + dy

        val tint = if (colorShift) driftedColor(t) else baseColor
        // Dimming flags a speed that has gone stale while moving. Without a fix the readout is
        // a resting 0, which should look like any other 0.
        val alpha = if (stale && hasFix) 90 else 255

        paint.color = tint
        paint.alpha = alpha
        canvas.drawText(digits, cx, top + digitsH, paint)

        labelPaint.color = tint

        if (headingLine != null) {
            labelPaint.textSize = headingSize
            labelPaint.alpha = (alpha * 0.62f).toInt()
            canvas.drawText(headingLine, cx, top + digitsH + gap + headingSize * HEADING_ADVANCE, labelPaint)
        }

        if (maxLine != null) {
            labelPaint.textSize = maxSize
            labelPaint.alpha = (alpha * 0.5f).toInt()
            canvas.drawText(maxLine, cx, top + blockH, labelPaint)
        }

        if (!hasFix && !hadFix) {
            // Not label-scaled: at large label sizes this line would outgrow the screen width.
            val hintSize = baseSize * 0.11f
            labelPaint.textSize = hintSize
            labelPaint.alpha = 110
            canvas.drawText("searching for GPS", cx, top + digitsH + gap + hintSize * 1.5f, labelPaint)
        }

        canvas.restore()
        drawBrightnessIndicator(canvas)
    }

    private fun drawBrightnessIndicator(canvas: Canvas) {
        val left = indicatorHideAt - SystemClock.elapsedRealtime()
        if (left <= 0L) return
        val fade = (left / INDICATOR_FADE_MS.toFloat()).coerceAtMost(1f)
        val d = resources.displayMetrics.density
        val barH = height * 0.3f
        val barW = 4 * d
        val x = paddingLeft + 28 * d
        val bottom = height / 2f + barH / 2f
        val top = bottom - barH
        val a = (255 * fade).toInt()

        indicatorPaint.color = Color.WHITE
        indicatorPaint.alpha = (a * 0.25f).toInt()
        canvas.drawRoundRect(x, top, x + barW, bottom, barW / 2f, barW / 2f, indicatorPaint)
        indicatorPaint.alpha = a
        val fillTop = bottom - barH * indicatorPct / 100f
        canvas.drawRoundRect(x, fillTop, x + barW, bottom, barW / 2f, barW / 2f, indicatorPaint)

        labelPaint.textSize = 16 * d
        labelPaint.color = Color.WHITE
        labelPaint.alpha = a
        canvas.drawText("$indicatorPct%", x + barW / 2f, top - 10 * d, labelPaint)
    }

    private fun driftedColor(t: Double): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(baseColor, hsv)
        if (hsv[1] < 0.05f) {
            // White has no hue to keep, so tint it faintly and walk the whole wheel.
            val hue = ((t / 720.0 * 360.0) % 360.0).toFloat()
            return Color.HSVToColor(floatArrayOf(hue, 0.20f, 1f))
        }
        // A picked colour should stay recognisably itself, so only sway around its hue.
        hsv[0] = ((hsv[0] + 20f * sin(t / 720.0 * 2.0 * PI).toFloat()) + 360f) % 360f
        return Color.HSVToColor(hsv)
    }

    // R2 low-discrepancy sequence: consecutive slots land far apart and the set fills the
    // safe area evenly, which a plain random pick does not guarantee over a short drive.
    private fun slotX(step: Int) = frac(0.5f + 0.7548776f * step) * 2f - 1f

    private fun slotY(step: Int) = frac(0.5f + 0.5698403f * step) * 2f - 1f

    private fun headingLabel(deg: Float): String {
        val d = ((deg % 360f) + 360f) % 360f
        val point = COMPASS_8[(d / 45f).roundToInt() % 8]
        return "%s %03d°".format(point, d.roundToInt() % 360)
    }

    private fun frac(v: Float) = v - kotlin.math.floor(v)

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    private fun refWidth(): Float {
        paint.textSize = 100f
        return paint.measureText("188")
    }

    private fun baseTextSize(w: Float, h: Float) = min(w * 0.62f / refWidth() * 100f, h * 0.40f)

    // Reserves room for the enabled label lines whether or not they are showing yet, so the
    // digits do not shrink the moment the heading or max line first appears.
    private fun digitTextSize(w: Float, h: Float, base: Float, headingSize: Float, maxSize: Float): Float {
        val gap = base * 0.10f
        var labelsH = 0f
        if (showHeading) labelsH += gap + headingSize * HEADING_ADVANCE
        if (showMax) labelsH += gap + maxSize * MAX_ADVANCE
        val fitWidth = w * 0.94f / refWidth() * 100f
        val fitHeight = ((h * 0.94f - labelsH) / 0.72f).coerceAtLeast(base * 0.3f)
        return min(base * speedScale, min(fitWidth, fitHeight))
    }

    companion object {
        private const val IDLE_FRAME_MS = 250L
        private const val SLIDE_FRAME_MS = 16L
        private const val SLIDE_SECONDS = 1.1f
        private const val INDICATOR_MS = 900L
        private const val INDICATOR_FADE_MS = 400L
        private const val HEADING_ADVANCE = 1.2f
        private const val MAX_ADVANCE = 1.3f
        private val COMPASS_8 = arrayOf(
            "N", "NE", "E", "SE", "S", "SW", "W", "NW"
        )
    }
}
