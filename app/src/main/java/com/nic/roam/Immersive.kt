package com.nic.roam

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Hides the status and navigation bars.
 *
 * [oneSwipeBack] decides what an edge swipe does with gesture navigation. Off, the first swipe
 * only reveals the bars, which keeps a stray swipe on the dashboard from closing the app. On,
 * the swipe goes straight to the back gesture, which is what menus and dialogs want. Only
 * Android 12+ can tell the two apart; older versions always need the reveal swipe first.
 */
fun Window.applyImmersive(oneSwipeBack: Boolean) {
    // Without this the window is letterboxed below the camera cutout while the bars are hidden
    // and stretches into it whenever a swipe shows them, so the whole screen jumps. Content
    // keeps clear of the cutout through its safe insets instead.
    if (Build.VERSION.SDK_INT >= 28) {
        val mode = if (Build.VERSION.SDK_INT >= 30) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (attributes.layoutInDisplayCutoutMode != mode) {
            attributes = attributes.also { it.layoutInDisplayCutoutMode = mode }
        }
    }
    if (Build.VERSION.SDK_INT >= 30) {
        setDecorFitsSystemWindows(false)
        insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = if (oneSwipeBack && Build.VERSION.SDK_INT >= 31) {
                WindowInsetsController.BEHAVIOR_DEFAULT
            } else {
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    } else {
        @Suppress("DEPRECATION")
        decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}
