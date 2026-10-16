package com.nic.roam

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** ScrollView that wraps its content but never grows past [maxHeightPx]. */
class PanelScrollView(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {

    var maxHeightPx = Int.MAX_VALUE
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
    }
}
