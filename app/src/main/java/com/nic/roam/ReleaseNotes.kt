package com.nic.roam

/**
 * Newest first. `code` is the versionCode that shipped the release.
 * Keep notes to one line each; the dialog shows them as single rows.
 */
object ReleaseNotes {

    data class Release(val code: Int, val name: String, val date: String, val notes: List<String>)

    fun since(lastSeenCode: Int) = all.filter { it.code > lastSeenCode }

    val all = listOf(
        Release(6, "1.1.0", "2026-10-16", listOf(
            "HUD mirror: lay the phone flat under the windshield and read the speed in the reflection",
            "Pick a colour for the readout: white, red, orange, yellow, green, cyan or blue",
            "Size sliders for the speed and for the max speed and heading lines",
            "Shows 0 while parked instead of two dashes when the GPS goes quiet",
            "What's new after each update, and the full list under Release notes in settings",
        )),
        Release(5, "1.0.4", "2026-08-22", listOf(
            "Optional compass heading under the speed, off by default",
            "Removed the km/h label to keep the readout clean",
            "Retuned the out-of-the-box defaults",
        )),
        Release(4, "1.0.3", "2026-08-16", listOf(
            "New Teko display font throughout the app, including the settings panel",
            "Outline digits are a clean single-line hollow numeral now",
        )),
        Release(2, "1.0.1", "2026-08-15", listOf(
            "First F-Droid release",
        )),
        Release(1, "1.0.0", "2026-07-31", listOf(
            "GPS speed on a pure black screen, built to avoid OLED burn-in",
            "The readout moves to a new spot every few minutes, with optional hue drift and outline digits",
            "No ads, no tracking, no network access",
        )),
    )
}
