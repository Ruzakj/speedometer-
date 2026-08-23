package com.ruzakj.speedometer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.View
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
    private lateinit var speedText: TextView
    private lateinit var maxText: TextView
    private lateinit var avgText: TextView
    private lateinit var distanceText: TextView
    private lateinit var timeText: TextView
    private lateinit var accuracyText: TextView
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
    private var lastMovingTimestampMs = 0L
    private var lastAcceptedElapsedNs = 0L
    private var speedSamples = 0
    private var speedSum = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 18)
            setBackgroundColor(0xFF080A0F.toInt())
        }

        val title = TextView(this).apply {
            text = "SPEEDOMETER  •  GPS ONLY"
            textSize = 17f
            setTextColor(0xFFF3F5F7.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(-1, 48))

        statusText = TextView(this).apply {
            text = "GPS: READY • Offline"
            textSize = 13f
            setTextColor(0xFF8D96A3.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, 32))

        speedView = SpeedView(this)
        root.addView(speedView, LinearLayout.LayoutParams(-1, 0, 1f))

        speedText = TextView(this).apply {
            text = "0.0 km/h"
            textSize = 1f
            setTextColor(0x00000000)
        }

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        maxText = stat("MAX", "0.0 km/h")
        avgText = stat("AVG", "0.0 km/h")
        distanceText = stat("DISTANCE", "0.00 km")
        timeText = stat("MOVING", "00:00")
        accuracyText = stat("ACCURACY", "-- m")

        row1.addView(maxText, weightParams())
        row1.addView(avgText, weightParams())
        row1.addView(distanceText, weightParams())
        row2.addView(timeText, weightParams())
        row2.addView(accuracyText, weightParams())
        stats.addView(row1, LinearLayout.LayoutParams(-1, 66))
        stats.addView(row2, LinearLayout.LayoutParams(-1, 66))
        root.addView(stats, LinearLayout.LayoutParams(-1, 132))

        actionButton = Button(this).apply {
            text = "START"
            setOnClickListener { if (running) stopTracking() else startTracking() }
        }
        root.addView(actionButton, LinearLayout.LayoutParams(-1, 58))

        val reset = Button(this).apply {
            text = "RESET TRIP"
            setOnClickListener { resetTrip() }
        }
        root.addView(reset, LinearLayout.LayoutParams(-1, 50))
        setContentView(root)
    }

    private fun stat(label: String, value: String): TextView = TextView(this).apply {
        text = "$label\n$value"
        textSize = 13f
        setTextColor(0xFFB9C1CD.toInt())
        gravity = Gravity.CENTER
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, -1, 1f)

    private fun startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 100)
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            statusText.text = "GPS: OFF • Enable location"
            return
        }
        running = true
        if (startTimeMs == 0L) startTimeMs = System.currentTimeMillis()
        actionButton.text = "STOP"
        statusText.text = "GPS: SEARCHING • Offline"
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,
                0f,
                this,
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) {
            running = false
        }
    }

    private fun stopTracking() {
        running = false
        locationManager.removeUpdates(this)
        actionButton.text = "START"
        statusText.text = "GPS: PAUSED • Offline"
    }

    private fun resetTrip() {
        maxSpeed = 0f
        totalDistance = 0f
        movingTimeMs = 0L
        startTimeMs = if (running) System.currentTimeMillis() else 0L
        lastMovingTimestampMs = 0L
        speedSamples = 0
        speedSum = 0.0
        lastLocation = null
        filteredSpeed = 0f
        maxText.text = "MAX\n0.0 km/h"
        avgText.text = "AVG\n0.0 km/h"
        distanceText.text = "DISTANCE\n0.00 km"
        timeText.text = "MOVING\n00:00"
        accuracyText.text = "ACCURACY\n-- m"
        speedView.setSpeed(0f, 0f)
    }

    override fun onLocationChanged(location: Location) {
        val accuracyM = if (location.hasAccuracy()) location.accuracy else 999f
        val speedMps = if (location.hasSpeed()) max(0f, location.speed) else 0f
        val speedAccuracy = if (android.os.Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else Float.MAX_VALUE

        // Reject clearly unreliable fixes. This prevents GPS jumps from becoming huge speeds/distances.
        if (accuracyM > 35f) {
            statusText.text = String.format(Locale.US, "GPS: WEAK • ±%.0f m", accuracyM)
            accuracyText.text = String.format(Locale.US, "ACCURACY\n±%.0f m", accuracyM)
            return
        }

        val elapsedNs = location.elapsedRealtimeNanos
        val dt = if (lastLocation != null && elapsedNs > lastAcceptedElapsedNs) (elapsedNs - lastAcceptedElapsedNs) / 1_000_000_000f else 0f
        val previous = lastLocation

        if (previous != null && dt > 0f) {
            val jumpDistance = previous.distanceTo(location)
            val derivedSpeed = jumpDistance / dt
            // Ignore physically implausible GPS jumps (> 250 km/h) unless the sensor itself agrees.
            if (derivedSpeed > 69.4f && speedMps < 55f) return
            if (jumpDistance > 120f && dt < 2.5f) return
            totalDistance += jumpDistance
            if (speedMps > 0.5f) {
                movingTimeMs += (dt * 1000f).toLong()
                lastMovingTimestampMs = System.currentTimeMillis()
            }
        }

        val rawKmh = speedMps * 3.6f
        val quality = qualityFactor(accuracyM, speedAccuracy)
        // Adaptive low-pass filter: excellent GPS remains responsive; weak GPS is smoother.
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
        avgText.text = String.format(Locale.US, "AVG\n%.1f km/h", avg)
        distanceText.text = String.format(Locale.US, "DISTANCE\n%.2f km", totalDistance / 1000f)
        timeText.text = "MOVING\n${formatDuration(movingTimeMs)}"
        accuracyText.text = String.format(Locale.US, "ACCURACY\n±%.0f m", accuracyM)
        statusText.text = statusLabel(accuracyM, speedAccuracy)
    }

    private fun qualityFactor(accuracy: Float, speedAccuracy: Float): Float {
        val position = (1f - ((accuracy - 3f) / 27f)).coerceIn(0f, 1f)
        val speed = if (speedAccuracy == Float.MAX_VALUE) 0.55f else (1f - speedAccuracy / 5f).coerceIn(0f, 1f)
        return (position * 0.7f + speed * 0.3f).coerceIn(0f, 1f)
    }

    private fun statusLabel(accuracy: Float, speedAccuracy: Float): String {
        val label = when {
            accuracy <= 5f -> "EXCELLENT"
            accuracy <= 10f -> "GOOD"
            accuracy <= 20f -> "FAIR"
            else -> "WEAK"
        }
        return if (speedAccuracy != Float.MAX_VALUE) String.format(Locale.US, "GPS: %s • ±%.1f m/s", label, speedAccuracy) else "GPS: $label • Offline"
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) statusText.text = "GPS: OFF"
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) statusText.text = "GPS: READY • Offline"
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
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) * 0.39f
            val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 18f
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = 0xFF252A33.toInt()
            canvas.drawArc(rect, 135f, 270f, false, paint)

            paint.color = when {
                accuracy <= 5f -> 0xFF55D98A.toInt()
                accuracy <= 10f -> 0xFFE8D66A.toInt()
                accuracy <= 20f -> 0xFFFFA64D.toInt()
                else -> 0xFF777F8C.toInt()
            }
            canvas.drawArc(rect, 135f, 270f * (speed / maxGauge), false, paint)

            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.color = 0xFF8D96A3.toInt()
            paint.textSize = radius * 0.12f
            canvas.drawText("GPS SPEED", cx, cy - radius * 0.48f, paint)

            paint.color = 0xFFF7F8FA.toInt()
            paint.textSize = radius * 0.52f
            canvas.drawText(String.format(Locale.US, "%.1f", speed), cx, cy + radius * 0.08f, paint)

            paint.color = 0xFF9EA6B3.toInt()
            paint.textSize = radius * 0.13f
            canvas.drawText("km/h", cx, cy + radius * 0.29f, paint)

            paint.color = 0xFF707987.toInt()
            paint.textSize = radius * 0.085f
            canvas.drawText(if (accuracy < 900f) String.format(Locale.US, "GPS ±%.0f m", accuracy) else "WAITING FOR GPS", cx, cy + radius * 0.43f, paint)
        }
    }
}
