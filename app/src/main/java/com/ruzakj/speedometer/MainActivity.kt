package com.ruzakj.speedometer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity(), LocationListener {
    private lateinit var speedView: SpeedView
    private lateinit var maxText: TextView
    private lateinit var avgText: TextView
    private lateinit var distanceText: TextView
    private lateinit var timeText: TextView
    private lateinit var accuracyText: TextView
    private lateinit var gpsText: TextView
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var locationManager: LocationManager

    private var running = false
    private var lastLocation: Location? = null
    private var filteredSpeed = 0f
    private var maxSpeed = 0f
    private var totalDistance = 0f
    private var movingTimeMs = 0L
    private var startTimeMs = 0L
    private var lastAcceptedElapsedNs = 0L
    private var speedSamples = 0
    private var speedSum = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(8), dp(16), dp(10))
        }

        // Android 15/16 edge-to-edge safe area.
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(dp(16), bars.top + dp(8), dp(16), bars.bottom + dp(10))
                insets
            }
            root.requestApplyInsets()
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "SPEEDOMETER"
            textSize = 20f
            typeface = Typeface.create("sans", Typeface.BOLD)
            setTextColor(TEXT)
        }
        statusText = TextView(this).apply {
            text = "GPS ready • Offline"
            textSize = 12f
            setTextColor(MUTED)
        }
        titleBox.addView(title, LinearLayout.LayoutParams(-1, dp(28)))
        titleBox.addView(statusText, LinearLayout.LayoutParams(-1, dp(20)))
        header.addView(titleBox, LinearLayout.LayoutParams(0, dp(52), 1f))

        val offline = chip("OFFLINE")
        header.addView(offline, LinearLayout.LayoutParams(dp(78), dp(34)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(58)))

        speedView = SpeedView(this)
        root.addView(speedView, LinearLayout.LayoutParams(-1, 0, 1f))

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        maxText = statCard("MAX", "0.0 km/h")
        avgText = statCard("AVERAGE", "0.0 km/h")
        distanceText = statCard("DISTANCE", "0.00 km")
        timeText = statCard("MOVING", "00:00")
        accuracyText = statCard("ACCURACY", "—")
        gpsText = statCard("GPS", "SEARCHING")

        row1.addView(maxText, cardParams())
        row1.addView(avgText, cardParams())
        row1.addView(distanceText, cardParams())
        row2.addView(timeText, cardParams())
        row2.addView(accuracyText, cardParams())
        row2.addView(gpsText, cardParams())
        stats.addView(row1, LinearLayout.LayoutParams(-1, 0, 1f))
        stats.addView(row2, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(stats, LinearLayout.LayoutParams(-1, dp(132)))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionButton = button("START", true).apply { setOnClickListener { if (running) stopTracking() else startTracking() } }
        val reset = button("RESET", false).apply { setOnClickListener { resetTrip() } }
        buttons.addView(actionButton, buttonParams(1f))
        buttons.addView(reset, buttonParams(1f))
        root.addView(buttons, LinearLayout.LayoutParams(-1, dp(54)))

        setContentView(root)
    }

    private fun chip(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(ACCENT)
        background = rounded(ACCENT_DARK, 50f)
    }

    private fun statCard(label: String, value: String) = TextView(this).apply {
        text = "$label\n$value"
        textSize = 13f
        gravity = Gravity.CENTER
        setLineSpacing(0f, 0.9f)
        setTextColor(TEXT_SECONDARY)
        background = rounded(CARD, 18f)
        setPadding(dp(4), dp(3), dp(4), dp(3))
    }

    private fun button(text: String, primary: Boolean) = Button(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        setTextColor(if (primary) ColorTextOnAccent else TEXT_SECONDARY)
        background = rounded(if (primary) ACCENT else CARD, 18f)
        stateListAnimator = null
    }

    private fun cardParams() = LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
    private fun buttonParams(weight: Float) = LinearLayout.LayoutParams(0, -1, weight).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius.toInt()).toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 100)
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            statusText.text = "GPS disabled • Enable location"
            gpsText.text = "GPS\nOFF"
            return
        }
        running = true
        if (startTimeMs == 0L) startTimeMs = System.currentTimeMillis()
        actionButton.text = "STOP"
        statusText.text = "GPS searching • Offline"
        gpsText.text = "GPS\nSEARCHING"
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this, Looper.getMainLooper())
        } catch (_: SecurityException) {
            running = false
        }
    }

    private fun stopTracking() {
        running = false
        locationManager.removeUpdates(this)
        actionButton.text = "START"
        statusText.text = "GPS paused • Offline"
    }

    private fun resetTrip() {
        maxSpeed = 0f
        totalDistance = 0f
        movingTimeMs = 0L
        startTimeMs = if (running) System.currentTimeMillis() else 0L
        lastLocation = null
        lastAcceptedElapsedNs = 0L
        speedSamples = 0
        speedSum = 0.0
        filteredSpeed = 0f
        maxText.text = "MAX\n0.0 km/h"
        avgText.text = "AVERAGE\n0.0 km/h"
        distanceText.text = "DISTANCE\n0.00 km"
        timeText.text = "MOVING\n00:00"
        accuracyText.text = "ACCURACY\n—"
        gpsText.text = "GPS\nSEARCHING"
        speedView.setSpeed(0f, 999f)
    }

    override fun onLocationChanged(location: Location) {
        val accuracyM = if (location.hasAccuracy()) location.accuracy else 999f
        val speedMps = if (location.hasSpeed()) max(0f, location.speed) else 0f
        val speedAccuracy = if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else Float.MAX_VALUE

        if (accuracyM > 35f) {
            statusText.text = String.format(Locale.US, "GPS weak • ±%.0f m", accuracyM)
            accuracyText.text = String.format(Locale.US, "ACCURACY\n±%.0f m", accuracyM)
            gpsText.text = "GPS\nWEAK"
            return
        }

        val elapsedNs = location.elapsedRealtimeNanos
        val dt = if (lastLocation != null && elapsedNs > lastAcceptedElapsedNs) (elapsedNs - lastAcceptedElapsedNs) / 1_000_000_000f else 0f
        val previous = lastLocation
        if (previous != null && dt > 0f) {
            val jumpDistance = previous.distanceTo(location)
            val derivedSpeed = jumpDistance / dt
            if (derivedSpeed > 69.4f && speedMps < 55f) return
            if (jumpDistance > 120f && dt < 2.5f) return
            totalDistance += jumpDistance
            if (speedMps > 0.5f) movingTimeMs += (dt * 1000f).toLong()
        }

        val rawKmh = speedMps * 3.6f
        val quality = qualityFactor(accuracyM, speedAccuracy)
        val alpha = 0.25f + 0.55f * quality
        filteredSpeed += (rawKmh - filteredSpeed) * alpha
        if (abs(filteredSpeed) < 1.0f && rawKmh < 1.5f) filteredSpeed = 0f

        lastLocation = Location(location)
        lastAcceptedElapsedNs = elapsedNs
        maxSpeed = max(maxSpeed, filteredSpeed)
        if (quality > 0.35f) {
            speedSamples++
            speedSum += filteredSpeed
        }

        val avg = if (speedSamples > 0) speedSum / speedSamples else 0.0
        speedView.setSpeed(filteredSpeed, accuracyM)
        maxText.text = String.format(Locale.US, "MAX\n%.1f km/h", maxSpeed)
        avgText.text = String.format(Locale.US, "AVERAGE\n%.1f km/h", avg)
        distanceText.text = String.format(Locale.US, "DISTANCE\n%.2f km", totalDistance / 1000f)
        timeText.text = "MOVING\n${formatDuration(movingTimeMs)}"
        accuracyText.text = String.format(Locale.US, "ACCURACY\n±%.0f m", accuracyM)

        val label = when {
            accuracyM <= 5f -> "EXCELLENT"
            accuracyM <= 10f -> "GOOD"
            accuracyM <= 20f -> "FAIR"
            else -> "WEAK"
        }
        gpsText.text = "GPS\n$label"
        statusText.text = if (speedAccuracy != Float.MAX_VALUE) String.format(Locale.US, "GPS %s • ±%.1f m/s • Offline", label, speedAccuracy) else "GPS $label • Offline"
    }

    private fun qualityFactor(accuracy: Float, speedAccuracy: Float): Float {
        val position = (1f - ((accuracy - 3f) / 27f)).coerceIn(0f, 1f)
        val speed = if (speedAccuracy == Float.MAX_VALUE) 0.55f else (1f - speedAccuracy / 5f).coerceIn(0f, 1f)
        return (position * 0.7f + speed * 0.3f).coerceIn(0f, 1f)
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            statusText.text = "GPS disabled • Enable location"
            gpsText.text = "GPS\nOFF"
        }
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            statusText.text = "GPS ready • Offline"
            gpsText.text = "GPS\nREADY"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startTracking()
    }

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        super.onDestroy()
    }

    private class SpeedView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var speed = 0f
        private var accuracy = 999f
        private val maxGauge = 180f

        fun setSpeed(value: Float, accuracyM: Float) {
            speed = value.coerceIn(0f, maxGauge)
            accuracy = accuracyM
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) * 0.35f
            val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 15f
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = 0xFF222733.toInt()
            canvas.drawArc(rect, 135f, 270f, false, paint)

            paint.color = when {
                accuracy <= 5f -> 0xFF54D58A.toInt()
                accuracy <= 10f -> 0xFFE4D45E.toInt()
                accuracy <= 20f -> 0xFFFFA34D.toInt()
                else -> 0xFF5E6674.toInt()
            }
            canvas.drawArc(rect, 135f, 270f * (speed / maxGauge), false, paint)

            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans", Typeface.NORMAL)
            paint.color = 0xFF89929F.toInt()
            paint.textSize = radius * 0.11f
            canvas.drawText("GPS SPEED", cx, cy - radius * 0.43f, paint)

            paint.color = 0xFFF5F7FA.toInt()
            paint.textSize = radius * 0.48f
            canvas.drawText(String.format(Locale.US, "%.1f", speed), cx, cy + radius * 0.08f, paint)

            paint.color = 0xFF9BA4B1.toInt()
            paint.textSize = radius * 0.12f
            canvas.drawText("km/h", cx, cy + radius * 0.27f, paint)

            paint.color = 0xFF697280.toInt()
            paint.textSize = radius * 0.075f
            canvas.drawText(if (accuracy < 900f) String.format(Locale.US, "GPS ±%.0f m", accuracy) else "WAITING FOR GPS", cx, cy + radius * 0.40f, paint)
        }
    }

    companion object {
        private const val BG = 0xFF07090D.toInt()
        private const val CARD = 0xFF11151D.toInt()
        private const val ACCENT = 0xFF65D6FF.toInt()
        private const val ACCENT_DARK = 0xFF12303A.toInt()
        private const val TEXT = 0xFFF5F7FA.toInt()
        private const val TEXT_SECONDARY = 0xFFC1C8D2.toInt()
        private const val MUTED = 0xFF737D8B.toInt()
        private const val ColorTextOnAccent = 0xFF071017.toInt()
    }
}
