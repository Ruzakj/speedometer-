package com.ruzakj.speedometer

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class MainActivityV2 : Activity(), LocationListener {
    private lateinit var dash: Dashboard
    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var tracking = false
    private var lastLocation: Location? = null
    private var lastElapsedNs = 0L
    private var speedKmh = 0f
    private var maxKmh = 0f
    private var distanceM = 0f
    private var movingMs = 0L
    private var averageSum = 0.0
    private var averageSamples = 0
    private var accuracyM = 999f
    private var speedAccuracyMps = Float.MAX_VALUE

    private var introAnimating = false
    private var introSpeed = 0f
    private var introAnimator: ValueAnimator? = null

    private val refresh = object : Runnable {
        override fun run() {
            dash.invalidate()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        dash = Dashboard(this)
        dash.setOnApplyWindowInsetsListener { view, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()) else null
            if (bars != null) view.setPadding(dp(16), bars.top + dp(8), dp(16), bars.bottom + dp(8))
            else view.setPadding(dp(16), dp(24), dp(16), dp(16))
            insets
        }
        setContentView(dash)
        handler.post(refresh)
        playStartupSweep()
    }

    private fun playStartupSweep() {
        introAnimator?.cancel()
        introAnimating = true
        introSpeed = 0f
        introAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500L
            addUpdateListener { animation ->
                val t = animation.animatedValue as Float
                introSpeed = if (t < 0.46f) {
                    val p = easeOutCubic(t / 0.46f)
                    180f * p
                } else {
                    val p = easeInOutCubic((t - 0.46f) / 0.54f)
                    180f * (1f - p)
                }
                dash.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    introAnimating = false
                    introSpeed = 0f
                    dash.invalidate()
                }
            })
            start()
        }
    }

    private fun easeOutCubic(x: Float): Float {
        val p = x.coerceIn(0f, 1f)
        return 1f - (1f - p) * (1f - p) * (1f - p)
    }

    private fun easeInOutCubic(x: Float): Float {
        val p = x.coerceIn(0f, 1f)
        return if (p < 0.5f) 4f * p * p * p else 1f - ((-2f * p + 2f) * (-2f * p + 2f) * (-2f * p + 2f)) / 2f
    }

    private fun startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            dash.message = "GPS OFF • ENABLE LOCATION"
            dash.invalidate()
            return
        }
        tracking = true
        dash.message = "GPS ACTIVE • OFFLINE"
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this, Looper.getMainLooper())
        } catch (_: SecurityException) {
            tracking = false
            dash.message = "LOCATION PERMISSION REQUIRED"
        }
        dash.invalidate()
    }

    private fun stopTracking() {
        tracking = false
        locationManager.removeUpdates(this)
        dash.message = "GPS PAUSED • OFFLINE"
        dash.invalidate()
    }

    private fun resetTrip() {
        maxKmh = 0f
        distanceM = 0f
        movingMs = 0L
        averageSum = 0.0
        averageSamples = 0
        lastLocation = null
        lastElapsedNs = 0L
        speedKmh = 0f
        accuracyM = 999f
        speedAccuracyMps = Float.MAX_VALUE
        dash.message = if (tracking) "GPS ACTIVE • OFFLINE" else "READY • OFFLINE"
        dash.invalidate()
    }

    override fun onLocationChanged(location: Location) {
        accuracyM = if (location.hasAccuracy()) location.accuracy else 999f
        speedAccuracyMps = if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else Float.MAX_VALUE
        if (accuracyM > 35f) {
            dash.message = String.format(Locale.US, "GPS WEAK • ±%.0f m", accuracyM)
            dash.invalidate()
            return
        }

        val nowNs = location.elapsedRealtimeNanos
        val previous = lastLocation
        val dt = if (previous != null && nowNs > lastElapsedNs) (nowNs - lastElapsedNs) / 1_000_000_000f else 0f
        val rawMps = if (location.hasSpeed()) max(0f, location.speed) else 0f

        if (previous != null && dt > 0f) {
            val jumpM = previous.distanceTo(location)
            val derivedMps = jumpM / dt
            if (jumpM > 120f && dt < 2.5f) return
            if (derivedMps > 69.4f && rawMps < 55f) return
            distanceM += jumpM
            if (rawMps > 0.5f) movingMs += (dt * 1000f).toLong()
        }

        val quality = gpsQualityFactor()
        val targetKmh = rawMps * 3.6f
        val alpha = 0.28f + 0.55f * quality
        speedKmh += (targetKmh - speedKmh) * alpha
        if (targetKmh < 1.5f && speedKmh < 1f) speedKmh = 0f

        lastLocation = Location(location)
        lastElapsedNs = nowNs
        maxKmh = max(maxKmh, speedKmh)
        if (quality > 0.35f) {
            averageSamples++
            averageSum += speedKmh
        }
        dash.message = "GPS ACTIVE • OFFLINE"
        dash.invalidate()
    }

    private fun gpsQualityFactor(): Float {
        val position = (1f - (accuracyM - 3f) / 27f).coerceIn(0f, 1f)
        val velocity = if (speedAccuracyMps == Float.MAX_VALUE) 0.55f else (1f - speedAccuracyMps / 5f).coerceIn(0f, 1f)
        return (position * 0.7f + velocity * 0.3f).coerceIn(0f, 1f)
    }

    private fun gpsLabel(): String = when {
        accuracyM <= 5f -> "EXCELLENT"
        accuracyM <= 10f -> "GOOD"
        accuracyM <= 20f -> "FAIR"
        accuracyM < 900f -> "WEAK"
        else -> "SEARCHING"
    }

    private fun averageKmh(): Float = if (averageSamples == 0) 0f else (averageSum / averageSamples).toFloat()

    private fun movingTime(): String {
        val seconds = movingMs / 1000L
        return if (seconds >= 3600L) String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600L, (seconds % 3600L) / 60L, seconds % 60L)
        else String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26 || isInPictureInPictureMode) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(9, 16))
            .apply { if (Build.VERSION.SDK_INT >= 31) setSeamlessResizeEnabled(true) }
            .build()
        try {
            enterPictureInPictureMode(params)
        } catch (_: IllegalStateException) {
            dash.message = "PIP UNAVAILABLE"
            dash.invalidate()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        dash.setPipMode(isInPictureInPictureMode)
        dash.invalidate()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQUEST_LOCATION && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) startTracking()
        else {
            dash.message = "PRECISE GPS PERMISSION REQUIRED"
            dash.invalidate()
        }
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            dash.message = "GPS OFF • ENABLE LOCATION"
            dash.invalidate()
        }
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            dash.message = "GPS READY • OFFLINE"
            dash.invalidate()
        }
    }

    override fun onDestroy() {
        introAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
        locationManager.removeUpdates(this)
        super.onDestroy()
    }

    private inner class Dashboard(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private var startRect = RectF()
        private var resetRect = RectF()
        private var pipRect = RectF()
        private var pipMode = false
        var message = "READY • OFFLINE"

        fun setPipMode(value: Boolean) { pipMode = value }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(BG)
            if (pipMode) drawPip(canvas) else drawFull(canvas)
        }

        private fun displaySpeed(): Float = if (introAnimating) introSpeed else speedKmh

        private fun drawPip(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h * 0.48f
            text(canvas, "MOTO • GPS", cx, h * 0.16f, 12f, CYAN, true, Paint.Align.CENTER)
            text(canvas, String.format(Locale.US, "%.1f", speedKmh), cx, cy, min(72f, w * 0.23f), TEXT, true, Paint.Align.CENTER)
            text(canvas, "km/h", cx, cy + df(30), 14f, MUTED, false, Paint.Align.CENTER)
            text(canvas, if (accuracyM < 900f) String.format(Locale.US, "±%.0f m • %s", accuracyM, gpsLabel()) else "SEARCHING GPS",
                cx, cy + df(54), 9f, gaugeColor(), true, Paint.Align.CENTER)
            text(canvas, if (tracking) "● LIVE • OFFLINE" else "PAUSED • OFFLINE", cx, h - df(16), 9f,
                if (tracking) GREEN else MUTED, true, Paint.Align.CENTER)
        }

        private fun drawFull(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val left = df(16)
            val right = w - df(16)
            val contentW = right - left
            val shownSpeed = displaySpeed()

            text(canvas, "SPEEDOMETER", left, df(24), 19f, TEXT, true, Paint.Align.LEFT)
            text(canvas, if (tracking) "● GPS ACTIVE" else "○ GPS READY", left, df(45), 10f, if (tracking) GREEN else MUTED, true, Paint.Align.LEFT)
            drawMotorcycleAccent(canvas, right - df(52), df(26), df(78), df(34))
            pill(canvas, right - df(88), df(52), df(88), df(26), "OFFLINE", CYAN)

            val panelTop = df(86)
            val panelHeight = min(df(380), h * 0.47f)
            val panel = RectF(left, panelTop, right, panelTop + panelHeight)
            roundCard(canvas, panel)
            text(canvas, "RIDE / SPORT", left + df(18), panel.top + df(25), 9f, CYAN, true, Paint.Align.LEFT)
            text(canvas, gpsLabel(), right - df(18), panel.top + df(25), 9f, gaugeColor(), true, Paint.Align.RIGHT)

            val cx = w / 2f
            val cy = panel.top + panelHeight * 0.59f
            val radius = min(contentW * 0.39f, panelHeight * 0.40f)
            val arc = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = df(10)
            paint.color = TRACK
            canvas.drawArc(arc, 140f, 260f, false, paint)
            paint.color = gaugeColor()
            canvas.drawArc(arc, 140f, 260f * (shownSpeed / 180f).coerceIn(0f, 1f), false, paint)

            paint.strokeWidth = df(2)
            for (i in 0..9) {
                val angle = Math.toRadians(140.0 + i * 260.0 / 9.0)
                val r1 = radius - df(16)
                val r2 = radius - df(5)
                paint.color = if (i * 20f <= shownSpeed) gaugeColor() else GRID
                canvas.drawLine(cx + cos(angle).toFloat() * r1, cy + sin(angle).toFloat() * r1,
                    cx + cos(angle).toFloat() * r2, cy + sin(angle).toFloat() * r2, paint)
            }

            text(canvas, "GPS SPEED", cx, cy - df(50), 11f, MUTED, true, Paint.Align.CENTER)
            text(canvas, String.format(Locale.US, "%.1f", shownSpeed), cx, cy + df(20), 62f, TEXT, false, Paint.Align.CENTER)
            text(canvas, "km/h", cx, cy + df(49), 15f, MUTED, false, Paint.Align.CENTER)
            text(canvas, if (introAnimating) "SYSTEM CHECK" else if (accuracyM < 900f) String.format(Locale.US, "GPS ±%.0f m", accuracyM) else "WAITING FOR GPS",
                cx, cy + df(76), 10f, gaugeColor(), true, Paint.Align.CENTER)

            val dataTop = panel.bottom + df(10)
            val dataHeight = df(106)
            val dataPanel = RectF(left, dataTop, right, dataTop + dataHeight)
            roundCard(canvas, dataPanel)
            text(canvas, "RIDE DATA", left + df(14), dataTop + df(20), 10f, MUTED, true, Paint.Align.LEFT)
            stat(canvas, "AVG", String.format(Locale.US, "%.1f", averageKmh()), "km/h", left + df(14), dataTop + df(28))
            stat(canvas, "MAX", String.format(Locale.US, "%.1f", maxKmh), "km/h", left + df(106), dataTop + df(28))
            stat(canvas, "TRIP", String.format(Locale.US, "%.2f", distanceM / 1000f), "km", left + df(198), dataTop + df(28))
            stat(canvas, "TIME", movingTime(), "", right - df(86), dataTop + df(28))
            text(canvas, "ACCURACY", left + df(14), dataTop + df(82), 9f, MUTED, true, Paint.Align.LEFT)
            text(canvas, if (accuracyM < 900f) String.format(Locale.US, "±%.0f m", accuracyM) else "—", left + df(14), dataTop + df(98), 12f, TEXT, true, Paint.Align.LEFT)
            text(canvas, "GPS", left + df(106), dataTop + df(82), 9f, MUTED, true, Paint.Align.LEFT)
            text(canvas, gpsLabel(), left + df(106), dataTop + df(98), 12f, gaugeColor(), true, Paint.Align.LEFT)
            text(canvas, "MODE", left + df(198), dataTop + df(82), 9f, MUTED, true, Paint.Align.LEFT)
            text(canvas, "SPORT", left + df(198), dataTop + df(98), 12f, CYAN, true, Paint.Align.LEFT)

            val buttonY = h - df(58)
            startRect = RectF(left, buttonY, left + contentW * 0.46f, buttonY + df(46))
            resetRect = RectF(left + contentW * 0.48f, buttonY, left + contentW * 0.72f, buttonY + df(46))
            pipRect = RectF(right - contentW * 0.24f, buttonY, right, buttonY + df(46))
            button(canvas, startRect, if (tracking) "STOP" else "START", tracking)
            button(canvas, resetRect, "RESET", false)
            button(canvas, pipRect, "PIP", false)
            text(canvas, message, cx, buttonY - df(8), 10f, MUTED, true, Paint.Align.CENTER)
        }

        private fun drawMotorcycleAccent(canvas: Canvas, cx: Float, cy: Float, width: Float, height: Float) {
            val wheelR = height * 0.20f
            val leftWheel = cx - width * 0.30f
            val rightWheel = cx + width * 0.30f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = df(2)
            paint.color = CYAN
            canvas.drawCircle(leftWheel, cy + height * 0.20f, wheelR, paint)
            canvas.drawCircle(rightWheel, cy + height * 0.20f, wheelR, paint)
            val path = Path()
            path.moveTo(leftWheel, cy + height * 0.20f)
            path.lineTo(cx - width * 0.08f, cy + height * 0.08f)
            path.lineTo(cx + width * 0.12f, cy + height * 0.20f)
            path.lineTo(leftWheel, cy + height * 0.20f)
            path.moveTo(cx - width * 0.08f, cy + height * 0.08f)
            path.lineTo(cx + width * 0.02f, cy - height * 0.02f)
            path.lineTo(cx + width * 0.18f, cy + height * 0.04f)
            path.lineTo(rightWheel, cy + height * 0.20f)
            path.moveTo(cx + width * 0.18f, cy + height * 0.04f)
            path.lineTo(cx + width * 0.26f, cy - height * 0.08f)
            path.moveTo(cx - width * 0.02f, cy - height * 0.01f)
            path.lineTo(cx - width * 0.18f, cy - height * 0.01f)
            paint.color = GREEN
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawPath(path, paint)
        }

        private fun stat(canvas: Canvas, label: String, value: String, unit: String, x: Float, y: Float) {
            text(canvas, label, x, y + df(15), 9f, MUTED, true, Paint.Align.LEFT)
            text(canvas, value, x, y + df(34), 13f, TEXT, true, Paint.Align.LEFT)
            if (unit.isNotEmpty()) text(canvas, unit, x, y + df(47), 8f, MUTED, false, Paint.Align.LEFT)
        }

        private fun button(canvas: Canvas, rect: RectF, label: String, active: Boolean) {
            fill.color = if (active) GREEN else PANEL
            canvas.drawRoundRect(rect, df(13), df(13), fill)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = df(1)
            paint.color = if (active) GREEN else GRID
            canvas.drawRoundRect(rect, df(13), df(13), paint)
            text(canvas, label, rect.centerX(), rect.centerY() + df(4), 12f, if (active) BG else TEXT, true, Paint.Align.CENTER)
        }

        private fun pill(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, label: String, color: Int) {
            fill.color = PANEL
            canvas.drawRoundRect(RectF(x, y, x + width, y + height), height / 2f, height / 2f, fill)
            text(canvas, label, x + width / 2f, y + height * 0.67f, 8f, color, true, Paint.Align.CENTER)
        }

        private fun roundCard(canvas: Canvas, rect: RectF) {
            fill.color = PANEL
            canvas.drawRoundRect(rect, df(20), df(20), fill)
        }

        private fun gaugeColor(): Int = when {
            accuracyM <= 5f -> GREEN
            accuracyM <= 10f -> CYAN
            accuracyM <= 20f -> AMBER
            else -> MUTED
        }

        private fun text(canvas: Canvas, value: String, x: Float, y: Float, sizeSp: Float, color: Int, bold: Boolean, align: Paint.Align) {
            paint.style = Paint.Style.FILL
            paint.textAlign = align
            paint.typeface = if (bold) Typeface.create("sans", Typeface.BOLD) else Typeface.create("sans", Typeface.NORMAL)
            paint.textSize = sizeSp * resources.displayMetrics.scaledDensity
            paint.color = color
            canvas.drawText(value, x, y, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP && !pipMode) {
                when {
                    startRect.contains(event.x, event.y) -> if (tracking) stopTracking() else startTracking()
                    resetRect.contains(event.x, event.y) -> resetTrip()
                    pipRect.contains(event.x, event.y) -> enterPip()
                }
            }
            return true
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun df(value: Int): Float = value * resources.displayMetrics.density

    companion object {
        private const val REQUEST_LOCATION = 10
        private const val BG = 0xFF070A0E.toInt()
        private const val PANEL = 0xFF10151B.toInt()
        private const val TRACK = 0xFF28323C.toInt()
        private const val GRID = 0xFF34414D.toInt()
        private const val TEXT = 0xFFF3F6F8.toInt()
        private const val MUTED = 0xFF7F8B96.toInt()
        private const val GREEN = 0xFF54E08A.toInt()
        private const val GREEN_DARK = 0xFF123B29.toInt()
        private const val CYAN = 0xFF52D7E9.toInt()
        private const val AMBER = 0xFFF2B95D.toInt()
    }
}
