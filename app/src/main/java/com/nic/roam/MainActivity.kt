package com.nic.roam

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity(), LocationListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var speed: SpeedView
    private lateinit var panel: View
    private lateinit var unitButton: Button
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
        unitButton = findViewById(R.id.unit)
        status = findViewById(R.id.status)

        speed.useMph = prefs.getBoolean("mph", false)
        speed.roam = prefs.getBoolean("roam", true)
        speed.colorShift = prefs.getBoolean("color", true)
        speed.outline = prefs.getBoolean("outline", false)
        speed.showMax = prefs.getBoolean("showmax", true)

        speed.setOnClickListener { setPanelVisible(panel.visibility != View.VISIBLE) }
        panel.setOnClickListener { setPanelVisible(false) }

        unitButton.setOnClickListener {
            speed.useMph = !speed.useMph
            prefs.edit().putBoolean("mph", speed.useMph).apply()
            updateUnitLabel()
            keepPanelAwake()
        }
        updateUnitLabel()

        bindSwitch(R.id.swRoam, "roam", speed.roam) { speed.roam = it }

        bindSwitch(R.id.swColor, "color", speed.colorShift) { speed.colorShift = it }
        bindSwitch(R.id.swOutline, "outline", speed.outline) { speed.outline = it }
        bindSwitch(R.id.swMax, "showmax", speed.showMax) { speed.showMax = it }

        val intervalLabel = findViewById<TextView>(R.id.lblInterval)
        bindSeekBar(R.id.interval, "interval", 2) { p ->
            val minutes = p + 1
            speed.moveIntervalSec = minutes * 60f
            intervalLabel.text = "Move every $minutes min"
        }
        bindSeekBar(R.id.brightness, "brightness", 75) { p ->
            val lp = window.attributes
            lp.screenBrightness = 0.02f + p / 100f * 0.98f
            window.attributes = lp
        }

        findViewById<Button>(R.id.resetMax).setOnClickListener {
            speed.maxKmh = 0f
            keepPanelAwake()
        }

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

    private fun updateUnitLabel() {
        unitButton.text = if (speed.useMph) "Units: mph" else "Units: km/h"
    }

    private fun setPanelVisible(visible: Boolean) {
        panel.visibility = if (visible) View.VISIBLE else View.GONE
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
            !gpsOn -> "GPS is off — turn on Location"
            else -> "Tap anywhere to hide"
        }
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

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
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
        speed.hasFix = true
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
    }
}
