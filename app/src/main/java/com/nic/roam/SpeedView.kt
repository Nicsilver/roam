package com.nic.roam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.WindowInsets
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class SpeedUnit(val pref: String, val perKmh: Float) {
    KMH("kmh", 1f),
    MPH("mph", 0.621371f),
    KNOTS("kn", 0.539957f);

    companion object {
        fun fromPref(value: String?) = entries.firstOrNull { it.pref == value } ?: KMH
    }
}

/**
 * Draws the whole UI itself so the readout can be placed at an arbitrary offset.
 * All the burn-in mitigation lives here:
 *  - the block jumps to a new spot every few minutes, sliding briefly so the move reads as
 *    intentional rather than a glitch
 *  - hue drift, so no single subpixel carries the load for long
 *  - pure black background and an optional outline digit style, which lights far fewer pixels
 */
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

    var swipeEnabled = true
    // The drag fraction is in screen heights since the drag began, positive upwards.
    var onBrightnessDragStart: (() -> Unit)? = null
    var onBrightnessDrag: ((Float) -> Unit)? = null
    var onBrightnessDragEnd: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minEdgeGuard = 24 * resources.displayMetrics.density
    private var pointerId = 0
    private var downX = 0f
    private var downY = 0f
    private var trackingDrag = false
    private var dragging = false
    private var dragBaseY = 0f
    // Fraction already dragged by fingers that have since lifted.
    private var carried = 0f

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

    // A drag that starts inside the system gesture area (back, home, revealing the bars)
    // belongs to the system, never to us.
    private fun startsInGestureArea(x: Float, y: Float): Boolean {
        var l = minEdgeGuard
        var t = minEdgeGuard
        var r = minEdgeGuard
        var b = minEdgeGuard
        val root = rootWindowInsets
        if (root != null && Build.VERSION.SDK_INT >= 29) {
            val g = if (Build.VERSION.SDK_INT >= 30) {
                root.getInsets(WindowInsets.Type.systemGestures())
            } else {
                @Suppress("DEPRECATION")
                root.systemGestureInsets
            }
            l = max(l, g.left.toFloat())
            t = max(t, g.top.toFloat())
            r = max(r, g.right.toFloat())
            b = max(b, g.bottom.toFloat())
        }
        return x < l || x > width - r || y < t || y > height - b
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                dragging = false
                carried = 0f
                trackingDrag = swipeEnabled && !startsInGestureArea(downX, downY)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    // Hand over to a remaining finger, re-baselined so the value does not jump.
                    val next = if (event.actionIndex == 0) 1 else 0
                    val y = event.getY(next)
                    if (dragging) carried += (dragBaseY - event.getY(event.actionIndex)) / height
                    pointerId = event.getPointerId(next)
                    downX = event.getX(next)
                    downY = y
                    dragBaseY = y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(pointerId)
                if (i < 0) return true
                val x = event.getX(i)
                val y = event.getY(i)
                if (trackingDrag && !dragging) {
                    val dx = x - downX
                    val dy = y - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        if (abs(dy) > abs(dx) * 1.5f) {
                            dragging = true
                            // Start from here so the value does not jump by the slop distance.
                            dragBaseY = y
                            carried = 0f
                            onBrightnessDragStart?.invoke()
                            val cancel = MotionEvent.obtain(event)
                            cancel.action = MotionEvent.ACTION_CANCEL
                            tapDetector.onTouchEvent(cancel)
                            cancel.recycle()
                        } else {
                            // Mostly horizontal drags are not ours; stop looking at this gesture.
                            trackingDrag = false
                        }
                    }
                }
                if (dragging) {
                    onBrightnessDrag?.invoke(carried + (dragBaseY - y) / height)
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
    private val indicatorText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.createFromAsset(context.assets, "fonts/Teko-text.ttf")
    }
    // Horizontal extent of the speed block in padded coordinates, for placing the indicator.
    private var blockLeft = 0f
    private var blockRight = 0f

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
        blockLeft = cx - blockW / 2f
        blockRight = cx + blockW / 2f

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

    // Drawn in the same padded, optionally mirrored space as the readout. The bar takes
    // whichever side of the speed block has more room and shrinks to fit rather than overlap it.
    private fun drawBrightnessIndicator(canvas: Canvas) {
        val left = indicatorHideAt - SystemClock.elapsedRealtime()
        if (left <= 0L) return
        val fade = (left / INDICATOR_FADE_MS.toFloat()).coerceAtMost(1f)
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        if (w <= 0f || h <= 0f) return
        val d = resources.displayMetrics.density

        // The number always sits right of the bar. Under pressure the bar narrows first and
        // only then does the number shrink.
        val fullBarW = 28 * d
        val minBarW = 8 * d
        val fullGap = 10 * d
        val fullTextSize = 40 * d
        indicatorText.textSize = fullTextSize
        val fullTextW = indicatorText.measureText("100%")

        val freeLeft = blockLeft
        val freeRight = w - blockRight
        val onLeft = freeLeft >= freeRight
        val free = max(if (onLeft) freeLeft else freeRight, 0f)
        val margin = min(12 * d, free * 0.2f)
        val clearance = min(12 * d, free * 0.2f)
        val avail = free - margin - clearance
        val spare = avail - fullGap - fullTextW
        val k = if (spare >= minBarW) 1f
        else (avail / (minBarW + fullGap + fullTextW)).coerceIn(0.05f, 1f)
        val barW = if (k < 1f) minBarW * k else spare.coerceAtMost(fullBarW)
        val gap = fullGap * k
        indicatorText.textSize = fullTextSize * k
        val textW = fullTextW * k
        val barH = h * 0.8f
        val barTop = (h - barH) / 2f
        val barBottom = barTop + barH
        val x = if (onLeft) margin else w - margin - textW - gap - barW
        val a = (255 * fade).toInt()

        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        if (mirror) canvas.scale(-1f, 1f, w / 2f, h / 2f)

        indicatorPaint.color = Color.WHITE
        indicatorPaint.alpha = (a * 0.25f).toInt()
        canvas.drawRoundRect(x, barTop, x + barW, barBottom, barW / 2f, barW / 2f, indicatorPaint)
        indicatorPaint.alpha = a
        val fillTop = barBottom - barH * indicatorPct / 100f
        canvas.save()
        canvas.clipRect(x, fillTop, x + barW, barBottom)
        canvas.drawRoundRect(x, barTop, x + barW, barBottom, barW / 2f, barW / 2f, indicatorPaint)
        canvas.restore()

        // The number rides the fill level, kept inside the bar's extent.
        val textH = indicatorText.textSize
        val ty = fillTop.coerceIn(barTop + textH * 0.5f, barBottom - textH * 0.5f)
        indicatorText.color = Color.WHITE
        indicatorText.alpha = a
        indicatorText.textAlign = Paint.Align.LEFT
        val tx = x + barW + gap
        canvas.drawText("$indicatorPct%", tx, ty + textH * 0.3f, indicatorText)
        canvas.restore()
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
