package com.ruzakj.speedometer

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivityV2 : Activity(), LocationListener {
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var stats: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var speedView: SpeedView
    private lateinit var statusText: TextView
    private lateinit var gpsText: TextView
    private lateinit var accuracyText: TextView
    private lateinit var maxText: TextView
    private lateinit var avgText: TextView
    private lateinit var distanceText: TextView
    private lateinit var timeText: TextView
    private lateinit var startButton: Button
    private lateinit var locationManager: LocationManager

    private var running = false
    private var lastLocation: Location? = null
    private var lastAcceptedNs = 0L
    private var filteredSpeed = 0f
    private var maxSpeed = 0f
    private var totalDistance = 0f
    private var movingTimeMs = 0L
    private var speedSamples = 0
    private var speedSum = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        buildUi()
        configureInsets()
        configurePip()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleBox.addView(TextView(this).apply {
            text = "SPEEDOMETER"
            textSize = 20f
            typeface = Typeface.create("sans", Typeface.BOLD)
            setTextColor(TEXT)
        }, LinearLayout.LayoutParams(-1, dp(30)))
        statusText = TextView(this).apply {
            text = "GPS READY  •  OFFLINE"
            textSize = 11f
            setTextColor(ACCENT)
        }
        titleBox.addView(statusText, LinearLayout.LayoutParams(-1, dp(20)))
        header.addView(titleBox, LinearLayout.LayoutParams(0, dp(54), 1f))
        header.addView(TextView(this).apply {
            text = "GPS ONLY"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(ACCENT)
            background = rounded(ACCENT_DARK, 40)
        }, LinearLayout.LayoutParams(dp(78), dp(32)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(58)))

        speedView = SpeedView(this)
        root.addView(speedView, LinearLayout.LayoutParams(-1, 0, 1f))

        stats = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        maxText = stat("MAX", "0.0 km/h")
        avgText = stat("AVERAGE", "0.0 km/h")
        distanceText = stat("DISTANCE", "0.00 km")
        timeText = stat("MOVING", "00:00")
        accuracyText = stat("ACCURACY", "—")
        gpsText = stat("GPS", "WAITING")
        r1.addView(maxText, cellParams()); r1.addView(avgText, cellParams()); r1.addView(distanceText, cellParams())
        r2.addView(timeText, cellParams()); r2.addView(accuracyText, cellParams()); r2.addView(gpsText, cellParams())
        stats.addView(r1, LinearLayout.LayoutParams(-1, 0, 1f)); stats.addView(r2, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(stats, LinearLayout.LayoutParams(-1, dp(116)))

        controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        startButton = actionButton("START", true) { if (running) stopTracking() else requestOrStart() }
        controls.addView(startButton, buttonParams())
        controls.addView(actionButton("PIP", false) { enterPip() }, buttonParams())
        controls.addView(actionButton("RESET", false) { resetTrip() }, buttonParams())
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(58)))
        setContentView(root)
    }

    private fun configureInsets() {
        root.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top; bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION") val t = insets.systemWindowInsetTop
                @Suppress("DEPRECATION") val b = insets.systemWindowInsetBottom
                top = t; bottom = b
            }
            v.setPadding(dp(16), top + dp(8), dp(16), bottom + dp(8))
            insets
        }
        root.requestApplyInsets()
    }

    private fun configurePip() {
        if (Build.VERSION.SDK_INT >= 26) {
            setPictureInPictureParams(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    private fun enterPip() {
        if (!running || Build.VERSION.SDK_INT < 26) return
        enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (running && Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode) enterPip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        header.visibility = if (inPip) View.GONE else View.VISIBLE
        stats.visibility = if (inPip) View.GONE else View.VISIBLE
        controls.visibility = if (inPip) View.GONE else View.VISIBLE
        speedView.pipMode = inPip
        speedView.invalidate()
    }

    private fun requestOrStart() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
            statusText.text = "ALLOW PRECISE LOCATION TO START"
            return
        }
        startTracking()
    }

    private fun startTracking() {
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            statusText.text = "GPS OFF  •  ENABLE LOCATION"
            gpsText.text = "GPS\nOFF"
            return
        }
        running = true
        startButton.text = "STOP"
        statusText.text = "GPS SEARCHING  •  OFFLINE"
        gpsText.text = "GPS\nSEARCHING"
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this, Looper.getMainLooper())
        } catch (_: SecurityException) {
            running = false
            statusText.text = "LOCATION PERMISSION REQUIRED"
        }
    }

    private fun stopTracking() {
        running = false
        locationManager.removeUpdates(this)
        startButton.text = "START"
        statusText.text = "GPS PAUSED  •  OFFLINE"
    }

    private fun resetTrip() {
        maxSpeed = 0f; totalDistance = 0f; movingTimeMs = 0L; lastLocation = null; lastAcceptedNs = 0L
        filteredSpeed = 0f; speedSamples = 0; speedSum = 0.0
        maxText.text = "MAX\n0.0 km/h"; avgText.text = "AVERAGE\n0.0 km/h"; distanceText.text = "DISTANCE\n0.00 km"
        timeText.text = "MOVING\n00:00"; accuracyText.text = "ACCURACY\n—"; gpsText.text = "GPS\nWAITING"
        speedView.setSpeed(0f, 999f)
    }

    override fun onLocationChanged(location: Location) {
        val accuracy = if (location.hasAccuracy()) location.accuracy else 999f
        val rawMps = if (location.hasSpeed()) max(0f, location.speed) else 0f
        val speedAccuracy = if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else Float.MAX_VALUE
        if (accuracy > 35f) {
            statusText.text = String.format(Locale.US, "GPS WEAK  •  ±%.0f m", accuracy)
            accuracyText.text = String.format(Locale.US, "ACCURACY\n±%.0f m", accuracy)
            gpsText.text = "GPS\nWEAK"
            return
        }

        val nowNs = location.elapsedRealtimeNanos
        val dt = if (lastLocation != null && nowNs > lastAcceptedNs) (nowNs - lastAcceptedNs) / 1_000_000_000f else 0f
        val previous = lastLocation
        if (previous != null && dt > 0f) {
            val d = previous.distanceTo(location)
            val derived = d / dt
            if (derived > 69.4f && rawMps < 55f) return
            if (d > 120f && dt < 2.5f) return
            totalDistance += d
            if (rawMps > 0.5f) movingTimeMs += (dt * 1000f).toLong()
        }

        val rawKmh = rawMps * 3.6f
        val quality = qualityFactor(accuracy, speedAccuracy)
        val alpha = 0.25f + 0.55f * quality
        filteredSpeed += (rawKmh - filteredSpeed) * alpha
        if (rawKmh < 1.5f && filteredSpeed < 1.0f) filteredSpeed = 0f
        lastLocation = Location(location); lastAcceptedNs = nowNs
        maxSpeed = max(maxSpeed, filteredSpeed)
        if (quality > 0.35f) { speedSamples++; speedSum += filteredSpeed }
        val avg = if (speedSamples > 0) speedSum / speedSamples else 0.0
        speedView.setSpeed(filteredSpeed, accuracy)
        maxText.text = String.format(Locale.US, "MAX\n%.1f km/h", maxSpeed)
        avgText.text = String.format(Locale.US, "AVERAGE\n%.1f km/h", avg)
        distanceText.text = String.format(Locale.US, "DISTANCE\n%.2f km", totalDistance / 1000f)
        timeText.text = "MOVING\n${duration(movingTimeMs)}"
        accuracyText.text = String.format(Locale.US, "ACCURACY\n±%.0f m", accuracy)
        val label = when { accuracy <= 5f -> "EXCELLENT"; accuracy <= 10f -> "GOOD"; accuracy <= 20f -> "FAIR"; else -> "WEAK" }
        gpsText.text = "GPS\n$label"
        statusText.text = if (speedAccuracy != Float.MAX_VALUE) String.format(Locale.US, "GPS %s  •  ±%.1f m/s  •  OFFLINE", label, speedAccuracy) else "GPS $label  •  OFFLINE"
    }

    private fun qualityFactor(positionAccuracy: Float, speedAccuracy: Float): Float {
        val p = (1f - ((positionAccuracy - 3f) / 27f)).coerceIn(0f, 1f)
        val s = if (speedAccuracy == Float.MAX_VALUE) 0.55f else (1f - speedAccuracy / 5f).coerceIn(0f, 1f)
        return (p * 0.7f + s * 0.3f).coerceIn(0f, 1f)
    }

    private fun duration(ms: Long): String {
        val total = ms / 1000; val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }

    override fun onProviderDisabled(provider: String) { if (provider == LocationManager.GPS_PROVIDER) { statusText.text = "GPS OFF"; gpsText.text = "GPS\nOFF" } }
    override fun onProviderEnabled(provider: String) { if (provider == LocationManager.GPS_PROVIDER) { statusText.text = "GPS READY  •  OFFLINE"; gpsText.text = "GPS\nREADY" } }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQUEST_LOCATION) {
            val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (fine) startTracking() else statusText.text = "PRECISE LOCATION REQUIRED"
        }
    }

    override fun onDestroy() { locationManager.removeUpdates(this); super.onDestroy() }

    private fun stat(label: String, value: String) = TextView(this).apply {
        text = "$label\n$value"; textSize = 11f; gravity = Gravity.CENTER; setTextColor(TEXT_SECONDARY); setPadding(dp(2), dp(2), dp(2), dp(2)); background = rounded(CARD, 16)
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; isAllCaps = false; setTextColor(if (primary) 0xFF07100A.toInt() else TEXT_SECONDARY); background = rounded(if (primary) ACCENT else CARD, 16); stateListAnimator = null; setOnClickListener { action() }
    }

    private fun cellParams() = LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
    private fun buttonParams() = LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private class SpeedView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var speed = 0f
        private var accuracy = 999f
        var pipMode = false
        fun setSpeed(value: Float, accuracyM: Float) { speed = value.coerceIn(0f, 180f); accuracy = accuracyM; invalidate() }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val radius = min(width, height) * if (pipMode) 0.39f else 0.35f
            val rect = RectF(cx-radius, cy-radius, cx+radius, cy+radius)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = if (pipMode) 12f else 15f; paint.strokeCap = Paint.Cap.ROUND
            paint.color = 0xFF222733.toInt(); canvas.drawArc(rect, 135f, 270f, false, paint)
            paint.color = when { accuracy <= 5f -> 0xFF54D58A.toInt(); accuracy <= 10f -> 0xFFE4D45E.toInt(); accuracy <= 20f -> 0xFFFFA34D.toInt(); else -> 0xFF5E6674.toInt() }
            canvas.drawArc(rect, 135f, 270f * (speed / 180f), false, paint)
            paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.create("sans", Typeface.NORMAL)
            paint.color = 0xFF89929F.toInt(); paint.textSize = radius * 0.11f; canvas.drawText("GPS SPEED", cx, cy-radius*0.43f, paint)
            paint.color = 0xFFF5F7FA.toInt(); paint.textSize = radius * if (pipMode) 0.46f else 0.48f; canvas.drawText(String.format(Locale.US, "%.1f", speed), cx, cy+radius*0.08f, paint)
            paint.color = 0xFF9BA4B1.toInt(); paint.textSize = radius * 0.12f; canvas.drawText("km/h", cx, cy+radius*0.27f, paint)
            paint.color = 0xFF697280.toInt(); paint.textSize = radius * 0.075f; canvas.drawText(if (accuracy < 900f) String.format(Locale.US, "GPS ±%.0f m", accuracy) else "WAITING FOR GPS", cx, cy+radius*0.43f, paint)
        }
    }

    companion object {
        private const val REQUEST_LOCATION = 100
        private const val BG = 0xFF07090E.toInt()
        private const val CARD = 0xFF11151D.toInt()
        private const val TEXT = 0xFFF5F7FA.toInt()
        private const val TEXT_SECONDARY = 0xFFB8C0CC.toInt()
        private const val ACCENT = 0xFF6EE7A0.toInt()
        private const val ACCENT_DARK = 0xFF173525.toInt()
    }
}
