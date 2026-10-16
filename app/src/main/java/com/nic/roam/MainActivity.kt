package com.nic.roam

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

class MainActivity : Activity(), LocationListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var speed: SpeedView
    private lateinit var panel: PanelScrollView
    private lateinit var btnKmh: TextView
    private lateinit var btnMph: TextView
    private lateinit var status: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var lastFixAt = 0L
    private var smoothed = 0f
    private var listening = false

    private val hidePanel = Runnable { setPanelVisible(false) }

    private val watchdog = object : Runnable {
        override fun run() {
            val age = SystemClock.elapsedRealtime() - lastFixAt
            if (lastFixAt > 0L) {
                speed.stale = age > 4_000
                if (age > 20_000) {
                    speed.hasFix = false
                    smoothed = 0f
                    speed.speedKmh = 0f
                }
            }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("roam", Context.MODE_PRIVATE)
        speed = findViewById(R.id.speed)
        panel = findViewById(R.id.panel)
        btnKmh = findViewById(R.id.btnKmh)
        btnMph = findViewById(R.id.btnMph)
        status = findViewById(R.id.status)

        applyUiFont(panel)
        sizePanel()
        // The window covers the camera cutout (see applyImmersive), so the readout and the
        // panel step around it themselves.
        speed.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 28) {
                val c = insets.displayCutout
                v.setPadding(
                    c?.safeInsetLeft ?: 0, c?.safeInsetTop ?: 0,
                    c?.safeInsetRight ?: 0, c?.safeInsetBottom ?: 0
                )
                val lp = panel.layoutParams as ViewGroup.MarginLayoutParams
                val top = (24 * resources.displayMetrics.density).toInt() + (c?.safeInsetTop ?: 0)
                if (lp.topMargin != top) {
                    lp.topMargin = top
                    panel.layoutParams = lp
                }
            }
            insets
        }
        // The bottom fifth stays clear so the readout can still be seen with the panel open.
        (panel.parent as View).addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val top = (panel.layoutParams as ViewGroup.MarginLayoutParams).topMargin
            val maxH = (v.height * 0.8f).toInt() - top
            if (maxH != panel.maxHeightPx) panel.post { panel.maxHeightPx = maxH }
        }

        speed.useMph = prefs.getBoolean("mph", false)
        speed.roam = prefs.getBoolean("roam", true)
        speed.colorShift = prefs.getBoolean("color", false)
        speed.outline = prefs.getBoolean("outline", true)
        speed.showMax = prefs.getBoolean("showmax", false)
        speed.showHeading = prefs.getBoolean("heading", false)

        speed.setOnClickListener { setPanelVisible(panel.visibility != View.VISIBLE) }
        panel.setOnClickListener { setPanelVisible(false) }
        findViewById<View>(R.id.closePanel).setOnClickListener { setPanelVisible(false) }
        findViewById<View>(R.id.closePanelBottom).setOnClickListener { setPanelVisible(false) }

        btnKmh.setOnClickListener { setUnit(false) }
        btnMph.setOnClickListener { setUnit(true) }
        updateUnitSegment()

        bindSwitch(R.id.swRoam, "roam", speed.roam) {
            speed.roam = it
            updateDependentRows()
        }

        bindSwitch(R.id.swColor, "color", speed.colorShift) { speed.colorShift = it }
        bindSwitch(R.id.swOutline, "outline", speed.outline) { speed.outline = it }
        bindSwitch(R.id.swMax, "showmax", speed.showMax) {
            speed.showMax = it
            updateDependentRows()
        }
        bindSwitch(R.id.swHeading, "heading", speed.showHeading) {
            speed.showHeading = it
            updateDependentRows()
        }
        speed.mirror = prefs.getBoolean("mirror", false)
        bindSwitch(R.id.swMirror, "mirror", speed.mirror) { speed.mirror = it }

        speed.baseColor = prefs.getInt("colour", Color.WHITE)
        buildSwatches()

        val speedSizeValue = findViewById<TextView>(R.id.speedSizeValue)
        bindSeekBar(R.id.speedSize, "speedsize", 5) { p ->
            val pct = 50 + p * 10
            speed.speedScale = pct / 100f
            speedSizeValue.text = "$pct%"
        }
        val labelSizeValue = findViewById<TextView>(R.id.labelSizeValue)
        bindSeekBar(R.id.labelSize, "labelsize", 0) { p ->
            val pct = 100 + p * 25
            speed.labelScale = pct / 100f
            labelSizeValue.text = "$pct%"
        }

        val intervalValue = findViewById<TextView>(R.id.intervalValue)
        bindSeekBar(R.id.interval, "interval", 2) { p ->
            val minutes = p + 1
            speed.moveIntervalSec = minutes * 60f
            intervalValue.text = "$minutes min"
        }
        updateDependentRows()

        val brightValue = findViewById<TextView>(R.id.brightValue)
        bindSeekBar(R.id.brightness, "brightness", 80) { p ->
            val lp = window.attributes
            lp.screenBrightness = 0.02f + p / 100f * 0.98f
            window.attributes = lp
            brightValue.text = "$p%"
        }

        findViewById<TextView>(R.id.resetMax).setOnClickListener {
            speed.maxKmh = 0f
            keepPanelAwake()
        }
        findViewById<TextView>(R.id.releaseNotes).setOnClickListener {
            setPanelVisible(false)
            showReleaseNotesDialog("Release notes", ReleaseNotes.all, "Close", markLatest = true)
        }

        maybeShowWhatsNew()

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ), 1
            )
        }
    }

    // Once per update, covering every release since the app was last opened. No marker on an
    // update means it came from before release notes existed, so only the current release is
    // shown. The marker is written on dismiss so a killed app shows it again.
    private fun maybeShowWhatsNew() {
        val lastSeen = prefs.getInt("last_seen_version", -1)
        if (lastSeen >= BuildConfig.VERSION_CODE) return
        val info = packageManager.getPackageInfo(packageName, 0)
        if (lastSeen < 0 && info.firstInstallTime == info.lastUpdateTime) {
            // Fresh install: nothing is new to this user.
            prefs.edit().putInt("last_seen_version", BuildConfig.VERSION_CODE).apply()
            return
        }
        val releases = if (lastSeen < 0) ReleaseNotes.all.take(1) else ReleaseNotes.since(lastSeen)
        if (releases.isEmpty()) return
        showReleaseNotesDialog("What's new", releases, "Got it") {
            prefs.edit().putInt("last_seen_version", BuildConfig.VERSION_CODE).apply()
        }
    }

    // Give the whole settings panel the same Teko face as the readout.
    private val uiTypeface by lazy { Typeface.createFromAsset(assets, "fonts/Teko-text.ttf") }
    private fun applyUiFont(v: View) {
        when (v) {
            is ViewGroup -> for (i in 0 until v.childCount) applyUiFont(v.getChildAt(i))
            is TextView -> v.typeface = uiTypeface
        }
    }

    private fun bindSwitch(id: Int, key: String, initial: Boolean, apply: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            apply(checked)
            prefs.edit().putBoolean(key, checked).apply()
            keepPanelAwake()
        }
    }

    private fun bindSeekBar(id: Int, key: String, default: Int, apply: (Int) -> Unit) {
        val bar = findViewById<SeekBar>(id)
        val stored = prefs.getInt(key, default)
        bar.progress = stored
        apply(stored)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                apply(p)
                if (fromUser) keepPanelAwake()
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putInt(key, sb.progress).apply()
            }
        })
    }

    // Sliders that only matter while another setting is on are dimmed and locked otherwise.
    private fun updateDependentRows() {
        setRowEnabled(R.id.intervalRow, R.id.interval, speed.roam)
        setRowEnabled(R.id.labelSizeRow, R.id.labelSize, speed.showMax || speed.showHeading)
    }

    private fun setRowEnabled(rowId: Int, barId: Int, enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.35f
        findViewById<View>(rowId).alpha = alpha
        findViewById<SeekBar>(barId).apply {
            this.alpha = alpha
            isEnabled = enabled
        }
    }

    private val swatchDots = mutableListOf<View>()

    private fun buildSwatches() {
        val row = findViewById<LinearLayout>(R.id.swatches)
        val size = (36 * resources.displayMetrics.density).toInt()
        for ((name, colour) in SWATCHES) {
            val cell = FrameLayout(this)
            cell.layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            val dot = View(this)
            dot.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            dot.tag = colour
            dot.contentDescription = name
            cell.addView(dot)
            cell.setOnClickListener {
                speed.baseColor = colour
                prefs.edit().putInt("colour", colour).apply()
                refreshSwatches()
                keepPanelAwake()
            }
            row.addView(cell)
            swatchDots += dot
        }
        refreshSwatches()
    }

    private fun refreshSwatches() {
        val d = resources.displayMetrics.density
        for (dot in swatchDots) {
            val colour = dot.tag as Int
            val fill = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colour)
            }
            // A separate ring with a gap, not a stroke on the dot itself, so the mark stays
            // visible on swatches that share its colour.
            val ring = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (colour == speed.baseColor) setStroke((2 * d).toInt(), Color.WHITE)
            }
            dot.background = LayerDrawable(arrayOf(ring, fill)).apply {
                val inset = (5 * d).toInt()
                setLayerInset(1, inset, inset, inset, inset)
            }
        }
    }

    private fun setUnit(mph: Boolean) {
        speed.useMph = mph
        prefs.edit().putBoolean("mph", mph).apply()
        updateUnitSegment()
        keepPanelAwake()
    }

    private fun updateUnitSegment() {
        val selected = if (speed.useMph) btnMph else btnKmh
        val other = if (speed.useMph) btnKmh else btnMph
        selected.setBackgroundResource(R.drawable.seg_selected)
        selected.setTextColor(Color.rgb(17, 17, 17))
        other.background = null
        other.setTextColor(Color.argb(153, 255, 255, 255))
    }

    // From targetSdk 36 predictive back skips onBackPressed, so on API 33+ back is caught with
    // a callback that only exists while the panel is open; otherwise back leaves the app.
    private val closePanelOnBack: Any? by lazy {
        if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { setPanelVisible(false) } else null
    }

    private fun setBackClosesPanel(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val callback = closePanelOnBack as OnBackInvokedCallback
        if (enabled) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback
            )
        } else {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        }
    }

    @Deprecated("Only reached below API 33; newer versions use the OnBackInvokedCallback")
    override fun onBackPressed() {
        if (panel.visibility == View.VISIBLE) setPanelVisible(false) else super.onBackPressed()
    }

    private fun setPanelVisible(visible: Boolean) {
        if (visible != (panel.visibility == View.VISIBLE)) setBackClosesPanel(visible)
        panel.visibility = if (visible) View.VISIBLE else View.GONE
        applyImmersive()
        handler.removeCallbacks(hidePanel)
        // The panel is ordinary static UI, so it is the one thing here that could burn in.
        if (visible) {
            updateStatus()
            handler.postDelayed(hidePanel, PANEL_TIMEOUT_MS)
        }
    }

    private fun keepPanelAwake() {
        handler.removeCallbacks(hidePanel)
        handler.postDelayed(hidePanel, PANEL_TIMEOUT_MS)
        updateStatus()
    }

    private fun updateStatus() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsOn = try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }
        status.text = when {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED -> "Location permission denied"
            !gpsOn -> "GPS is off. Turn on Location"
            else -> ""
        }
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
    }

    // The activity handles rotation itself, so the panel width is recomputed here; full width in
    // landscape spreads the rows and swatches far apart.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sizePanel()
    }

    private fun sizePanel() {
        val d = resources.displayMetrics.density
        val screenDp = resources.configuration.screenWidthDp
        val lp = panel.layoutParams
        lp.width = (minOf(screenDp - 48, PANEL_MAX_WIDTH_DP) * d).toInt()
        panel.layoutParams = lp
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        speed.setRunning(true)
        startLocation()
        handler.post(watchdog)
    }

    override fun onPause() {
        super.onPause()
        speed.setRunning(false)
        stopLocation()
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(hidePanel)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    private fun applyImmersive() {
        window.applyImmersive(oneSwipeBack = panel.visibility == View.VISIBLE)
    }

    private fun startLocation() {
        if (listening) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this)
            listening = true
        } catch (e: Exception) {
            listening = false
        }
    }

    private fun stopLocation() {
        if (!listening) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.removeUpdates(this)
        } catch (e: Exception) {
            // provider already gone
        }
        listening = false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startLocation()
        updateStatus()
    }

    override fun onLocationChanged(location: Location) {
        var kmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        // GPS reports a metre or two of drift as motion when parked.
        if (kmh < 1.5f) kmh = 0f
        smoothed += (kmh - smoothed) * 0.45f
        if (smoothed < 0.4f) smoothed = 0f

        speed.speedKmh = smoothed
        // Below a walking pace GPS bearing is just noise; clear it so the heading hides rather
        // than flickering through random directions while parked.
        speed.headingDeg = if (location.hasBearing() && smoothed >= HEADING_MIN_KMH) {
            location.bearing
        } else {
            -1f
        }
        speed.hasFix = true
        speed.hadFix = true
        speed.stale = false
        if (smoothed > speed.maxKmh) speed.maxKmh = smoothed
        lastFixAt = SystemClock.elapsedRealtime()
    }

    // Defaulted on modern SDKs, but abstract in the framework on older ones — leaving them
    // out risks AbstractMethodError on API 26-29 devices.
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    companion object {
        private const val PANEL_TIMEOUT_MS = 15_000L
        private const val HEADING_MIN_KMH = 5f
        private const val PANEL_MAX_WIDTH_DP = 440
        private val SWATCHES = listOf(
            "White" to Color.WHITE,
            "Red" to Color.rgb(255, 59, 48),
            "Orange" to Color.rgb(255, 138, 61),
            "Yellow" to Color.rgb(255, 214, 10),
            "Green" to Color.rgb(52, 199, 89),
            "Cyan" to Color.rgb(34, 211, 238),
            "Blue" to Color.rgb(64, 132, 255),
        )
    }
}
