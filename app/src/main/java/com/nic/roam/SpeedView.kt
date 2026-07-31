package com.nic.roam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

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
    var stale = false
    var maxKmh = 0f

    var useMph = false
    var roam = true
    var colorShift = true
    var outline = false
    var showMax = true
    var moveIntervalSec = 180f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    private val startedAt = SystemClock.elapsedRealtime()
    private var running = false
    private var sliding = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, if (sliding) SLIDE_FRAME_MS else IDLE_FRAME_MS)
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

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val t = (SystemClock.elapsedRealtime() - startedAt) / 1000.0

        val bigSize = bigTextSize(w, h)
        val smallSize = bigSize * 0.16f
        val maxSize = bigSize * 0.11f

        paint.textSize = bigSize
        labelPaint.textSize = smallSize

        val speed = if (useMph) speedKmh * MPH else speedKmh
        val digits = if (!hasFix) "- -" else speed.roundToInt().coerceAtLeast(0).toString()
        val unit = if (useMph) "mph" else "km/h"

        val digitsW = paint.measureText(digits)
        val gap = bigSize * 0.10f
        // Cap height, not the font's full line height: digits have no descenders, so using
        // the metrics directly would leave the block visibly high in the safe area.
        val digitsH = bigSize * 0.72f
        val maxLine = if (showMax && maxKmh > 0f) {
            val m = if (useMph) maxKmh * MPH else maxKmh
            "max ${m.roundToInt()}"
        } else null

        var blockH = digitsH + gap + smallSize
        if (maxLine != null) blockH += maxSize * 2.2f
        val blockW = maxOf(digitsW, labelPaint.measureText(unit))

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

        val tint = when {
            !hasFix -> Color.rgb(120, 120, 120)
            colorShift -> {
                val hue = ((t / 720.0 * 360.0) % 360.0).toFloat()
                Color.HSVToColor(floatArrayOf(hue, 0.20f, 1f))
            }
            else -> Color.WHITE
        }
        val alpha = if (stale) 90 else 255

        paint.color = tint
        paint.alpha = alpha
        if (outline) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = bigSize * 0.045f
        } else {
            paint.style = Paint.Style.FILL
        }
        canvas.drawText(digits, cx, top + digitsH, paint)

        labelPaint.color = tint
        labelPaint.alpha = (alpha * 0.55f).toInt()
        canvas.drawText(unit, cx, top + digitsH + gap + smallSize, labelPaint)

        if (maxLine != null) {
            labelPaint.textSize = maxSize
            labelPaint.alpha = (alpha * 0.38f).toInt()
            canvas.drawText(maxLine, cx, top + blockH, labelPaint)
        }

        if (!hasFix) {
            labelPaint.textSize = maxSize
            labelPaint.alpha = 110
            canvas.drawText("searching for GPS", cx, top + digitsH + gap + smallSize * 2.4f, labelPaint)
        }
    }

    // R2 low-discrepancy sequence: consecutive slots land far apart and the set fills the
    // safe area evenly, which a plain random pick does not guarantee over a short drive.
    private fun slotX(step: Int) = frac(0.5f + 0.7548776f * step) * 2f - 1f

    private fun slotY(step: Int) = frac(0.5f + 0.5698403f * step) * 2f - 1f

    private fun frac(v: Float) = v - kotlin.math.floor(v)

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    private fun bigTextSize(w: Float, h: Float): Float {
        paint.textSize = 100f
        val ref = paint.measureText("188")
        val byWidth = w * 0.62f / ref * 100f
        val byHeight = h * 0.40f
        return min(byWidth, byHeight)
    }

    companion object {
        private const val IDLE_FRAME_MS = 250L
        private const val SLIDE_FRAME_MS = 16L
        private const val SLIDE_SECONDS = 1.1f
        private const val MPH = 0.621371f
    }
}
