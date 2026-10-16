package com.nic.roam

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Centered dialog listing [releases]; serves both the after-update popup and the full
 * history. [markLatest] tags the first entry, which only makes sense in the full list.
 */
fun Activity.showReleaseNotesDialog(
    title: String,
    releases: List<ReleaseNotes.Release>,
    buttonLabel: String,
    markLatest: Boolean = false,
    onDismiss: (() -> Unit)? = null
) {
    val metrics = resources.displayMetrics
    val d = metrics.density
    fun dp(v: Int) = (v * d).toInt()
    val match = LinearLayout.LayoutParams.MATCH_PARENT
    val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
    val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy")
    val teko = Typeface.createFromAsset(assets, "fonts/Teko-text.ttf")
    val accent = Color.rgb(255, 138, 61)
    val primary = Color.argb(222, 255, 255, 255)
    val secondary = Color.argb(115, 255, 255, 255)

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.panel_bg)
        setPadding(dp(24), dp(22), dp(24), dp(16))
    }

    root.addView(TextView(this).apply {
        text = title
        textSize = 22f
        typeface = teko
        gravity = Gravity.CENTER
        setTextColor(Color.argb(242, 255, 255, 255))
    })
    root.addView(View(this).apply {
        setBackgroundColor(accent)
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(2)).also {
            it.gravity = Gravity.CENTER_HORIZONTAL
            it.topMargin = dp(6)
            it.bottomMargin = dp(14)
        }
    })

    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    releases.forEachIndexed { i, release ->
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, if (i == 0) 0 else dp(16), 0, dp(4))
        }
        header.addView(TextView(this).apply {
            text = release.name
            textSize = 20f
            typeface = teko
            setTextColor(primary)
        })
        if (markLatest && i == 0) header.addView(TextView(this).apply {
            text = "LATEST"
            textSize = 10f
            letterSpacing = 0.14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            setTextColor(Color.rgb(17, 17, 17))
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = 999 * d
            }
            setPadding(dp(8), dp(3), dp(8), dp(3))
            layoutParams = LinearLayout.LayoutParams(wrap, wrap).also { it.marginStart = dp(10) }
        })
        header.addView(TextView(this).apply {
            text = LocalDate.parse(release.date).format(dateFormat)
            textSize = 14f
            typeface = teko
            setTextColor(secondary)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, wrap, 1f)
        })
        content.addView(header)

        release.notes.forEach { note ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                setPadding(0, dp(5), 0, dp(5))
            }
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                }
                layoutParams = LinearLayout.LayoutParams(dp(5), dp(5)).also {
                    it.topMargin = dp(9); it.marginEnd = dp(12)
                }
            })
            row.addView(TextView(this).apply {
                text = note
                textSize = 16f
                typeface = teko
                setLineSpacing(0f, 1.1f)
                setTextColor(primary)
                layoutParams = LinearLayout.LayoutParams(0, wrap, 1f)
            })
            content.addView(row)
        }
    }

    // Cap the list so the full history scrolls inside the dialog instead of filling the screen
    val maxHeight = (metrics.heightPixels * 0.6).toInt()
    val scroll = object : ScrollView(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        addView(content, LinearLayout.LayoutParams(match, wrap))
    }
    root.addView(scroll, LinearLayout.LayoutParams(match, wrap))

    val dialog = Dialog(this)
    root.addView(TextView(this).apply {
        text = buttonLabel
        textSize = 16f
        typeface = teko
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(17, 17, 17))
        setBackgroundResource(R.drawable.seg_selected)
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(match, dp(44)).also { it.topMargin = dp(18) }
        setOnClickListener { dialog.dismiss() }
    })

    dialog.setContentView(root)
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setLayout(minOf(metrics.widthPixels - dp(48), dp(420)), WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.CENTER)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.7f)
    }
    if (onDismiss != null) dialog.setOnDismissListener { onDismiss() }
    // A focusable dialog window drops the activity's immersive mode and the system bars pop
    // back in. Showing it unfocusable, hiding the bars on it too, then making it focusable
    // keeps them hidden.
    dialog.window?.setFlags(
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    )
    dialog.show()
    dialog.window?.applyImmersive(oneSwipeBack = true)
    dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
}
