package com.ruzakj.speedometer

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
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
import kotlin.math.roundToInt
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
            handler.postDelayed(this, 250L)
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
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(dp(16), bars.top + dp(6), dp(16), bars.bottom + dp(8))
            } else {
                view.setPadding(dp(16), dp(24), dp(16), dp(16))
            }
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
            duration = 1450L
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                introSpeed = if (t < 0.45f) {
                    MAX_SPEED * easeOutCubic(t / 0.45f)
                } else {
                    MAX_SPEED * (1f - easeInOutCubic((t - 0.45f) / 0.55f))
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

    private fun easeOutCubic(value: Float): Float {
        val p = value.coerceIn(0f, 1f)
        return 1f - (1f - p) * (1f - p) * (1f - p)
    }

    private fun easeInOutCubic(value: Float): Float {
        val p = value.coerceIn(0f, 1f)
        return if (p < 0.5f) {
            4f * p * p * p
        } else {
            1f - ((-2f * p + 2f) * (-2f * p + 2f) * (-2f * p + 2f)) / 2f
        }
    }

    private fun startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_LOCATION
            )
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            dash.message = "GPS OFF  •  ENABLE LOCATION"
            dash.invalidate()
            return
        }
        tracking = true
        dash.message = "GPS ACTIVE  •  OFFLINE"
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,
                0f,
                this,
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) {
            tracking = false
            dash.message = "LOCATION PERMISSION REQUIRED"
        }
        dash.invalidate()
    }

    private fun stopTracking() {
        tracking = false
        locationManager.removeUpdates(this)
        dash.message = "GPS PAUSED  •  OFFLINE"
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
        dash.message = if (tracking) "GPS ACTIVE  •  OFFLINE" else "READY  •  OFFLINE"
        dash.invalidate()
    }

    override fun onLocationChanged(location: Location) {
        accuracyM = if (location.hasAccuracy()) location.accuracy else 999f
        speedAccuracyMps = if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) {
            location.speedAccuracyMetersPerSecond
        } else {
            Float.MAX_VALUE
        }

        if (accuracyM > 35f) {
            dash.message = String.format(Locale.US, "GPS WEAK  •  ±%.0f m", accuracyM)
            dash.invalidate()
            return
        }

        val nowNs = location.elapsedRealtimeNanos
        val previous = lastLocation
        val dt = if (previous != null && nowNs > lastElapsedNs) {
            (nowNs - lastElapsedNs) / 1_000_000_000f
        } else {
            0f
        }
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
        val targetKmh = (rawMps * 3.6f).coerceIn(0f, MAX_SPEED)
        val alpha = 0.30f + 0.50f * quality
        speedKmh += (targetKmh - speedKmh) * alpha
        speedKmh = speedKmh.coerceIn(0f, MAX_SPEED)
        if (targetKmh < 1.5f && speedKmh < 1f) speedKmh = 0f

        lastLocation = Location(location)
        lastElapsedNs = nowNs
        maxKmh = max(maxKmh, speedKmh).coerceAtMost(MAX_SPEED)
        if (quality > 0.35f) {
            averageSamples++
            averageSum += speedKmh.toDouble()
        }
        dash.message = "GPS ACTIVE  •  OFFLINE"
        dash.invalidate()
    }

    private fun gpsQualityFactor(): Float {
        val position = (1f - (accuracyM - 3f) / 27f).coerceIn(0f, 1f)
        val velocity = if (speedAccuracyMps == Float.MAX_VALUE) {
            0.55f
        } else {
            (1f - speedAccuracyMps / 5f).coerceIn(0f, 1f)
        }
        return (position * 0.7f + velocity * 0.3f).coerceIn(0f, 1f)
    }

    private fun gpsLabel(): String = when {
        accuracyM <= 5f -> "EXCELLENT"
        accuracyM <= 10f -> "GOOD"
        accuracyM <= 20f -> "FAIR"
        accuracyM < 900f -> "WEAK"
        else -> "SEARCHING"
    }

    private fun averageKmh(): Float {
        return if (averageSamples == 0) 0f else (averageSum / averageSamples).toFloat().coerceAtMost(MAX_SPEED)
    }

    private fun movingTime(): String {
        val seconds = movingMs / 1000L
        return if (seconds >= 3600L) {
            String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600L, (seconds % 3600L) / 60L, seconds % 60L)
        } else {
            String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26 || isInPictureInPictureMode) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(9, 16))
            .apply {
                if (Build.VERSION.SDK_INT >= 31) setSeamlessResizeEnabled(true)
            }
            .build()
        try {
            enterPictureInPictureMode(params)
        } catch (_: IllegalStateException) {
            dash.message = "PIP UNAVAILABLE"
            dash.invalidate()
        }
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean) {
        super.onPictureInPictureModeChanged(inPip)
        dash.setPipMode(inPip)
        dash.invalidate()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == REQUEST_LOCATION && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            startTracking()
        } else {
            dash.message = "PRECISE GPS PERMISSION REQUIRED"
            dash.invalidate()
        }
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            dash.message = "GPS OFF  •  ENABLE LOCATION"
            dash.invalidate()
        }
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            dash.message = "GPS READY  •  OFFLINE"
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
        var message = "READY  •  OFFLINE"

        init {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        fun setPipMode(value: Boolean) {
            pipMode = value
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(BG)
            if (pipMode) drawPip(canvas) else drawFull(canvas)
        }

        private fun shownSpeed(): Float {
            return if (introAnimating) introSpeed else speedKmh.coerceAtMost(MAX_SPEED)
        }

        private fun drawFull(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val left = df(16f)
            val right = w - left
            val contentW = right - left

            text(canvas, "MOTO", left, df(25f), df(20f), TEXT, true, Paint.Align.LEFT)
            text(canvas, "SPEED", left + df(61f), df(25f), df(20f), CYAN, true, Paint.Align.LEFT)
            text(canvas, if (tracking) "● GPS LIVE" else "○ GPS READY", left, df(46f), df(9f), if (tracking) GREEN else MUTED, true, Paint.Align.LEFT)
            drawMotorAccent(canvas, right - df(54f), df(26f), df(82f), df(34f))
            pill(canvas, right - df(84f), df(52f), df(84f), df(25f), "OFFLINE", CYAN)

            val top = df(82f)
            val bottom = min(h - df(218f), top + df(395f))
            val panel = RectF(left, top, right, bottom)
            premiumCard(canvas, panel)
            text(canvas, "RIDE / SPORT", left + df(18f), top + df(24f), df(9f), PURPLE, true, Paint.Align.LEFT)
            text(canvas, gpsLabel(), right - df(18f), top + df(24f), df(9f), gaugeColor(), true, Paint.Align.RIGHT)

            val cx = w / 2f
            val cy = top + (bottom - top) * 0.60f
            val radius = min(contentW * 0.39f, (bottom - top) * 0.38f)
            val arc = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = df(12f)
            paint.shader = null
            paint.color = TRACK
            canvas.drawArc(arc, 138f, 264f, false, paint)

            val fraction = (shownSpeed() / MAX_SPEED).coerceIn(0f, 1f)
            paint.shader = LinearGradient(
                arc.left,
                arc.bottom,
                arc.right,
                arc.top,
                intArrayOf(CYAN, PURPLE, PINK),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawArc(arc, 138f, 264f * fraction, false, paint)
            paint.shader = null

            paint.strokeWidth = df(3f)
            for (i in 0..11) {
                val angle = Math.toRadians(138.0 + i * 264.0 / 11.0)
                val inner = radius - df(19f)
                val outer = radius - df(7f)
                paint.color = if (i / 11f <= fraction) CYAN else GRID
                canvas.drawLine(
                    cx + cos(angle).toFloat() * inner,
                    cy + sin(angle).toFloat() * inner,
                    cx + cos(angle).toFloat() * outer,
                    cy + sin(angle).toFloat() * outer,
                    paint
                )
            }

            for (i in 0..11) {
                val value = i * 10
                val angle = Math.toRadians(138.0 + i * 264.0 / 11.0)
                val labelRadius = radius - df(34f)
                text(
                    canvas,
                    value.toString(),
                    cx + cos(angle).toFloat() * labelRadius,
                    cy + sin(angle).toFloat() * labelRadius + df(3f),
                    df(8f),
                    if (value % 20 == 0) TEXT else MUTED,
                    true,
                    Paint.Align.CENTER
                )
            }

            text(canvas, "GPS SPEED", cx, cy - df(48f), df(10f), MUTED, true, Paint.Align.CENTER)
            text(canvas, String.format(Locale.US, "%.1f", shownSpeed()), cx, cy + df(18f), df(58f), TEXT, true, Paint.Align.CENTER)
            text(canvas, "km/h", cx, cy + df(46f), df(14f), MUTED, false, Paint.Align.CENTER)
            text(
                canvas,
                if (introAnimating) "SYSTEM CHECK • MAX 110" else if (accuracyM < 900f) String.format(Locale.US, "GPS ±%.0f m", accuracyM) else "WAITING FOR GPS",
                cx,
                cy + df(70f),
                df(9f),
                gaugeColor(),
                true,
                Paint.Align.CENTER
            )

            val statTop = bottom + df(12f)
            val gap = df(8f)
            val cardW = (contentW - gap) / 2f
            val cardH = df(64f)
            statCard(canvas, RectF(left, statTop, left + cardW, statTop + cardH), "AVG", String.format(Locale.US, "%.1f", averageKmh()), "km/h", CYAN)
            statCard(canvas, RectF(left + cardW + gap, statTop, right, statTop + cardH), "MAX", String.format(Locale.US, "%.1f", maxKmh), "km/h", PINK)
            statCard(canvas, RectF(left, statTop + cardH + gap, left + cardW, statTop + cardH * 2f + gap), "TRIP", String.format(Locale.US, "%.2f", distanceM / 1000f), "km", PURPLE)
            statCard(canvas, RectF(left + cardW + gap, statTop + cardH + gap, right, statTop + cardH * 2f + gap), "MOVING", movingTime(), "", ORANGE)

            val buttonY = statTop + cardH * 2f + gap * 2f
            val buttonGap = df(8f)
            val buttonW = (contentW - buttonGap * 2f) / 3f
            startRect = RectF(left, buttonY, left + buttonW, buttonY + df(46f))
            resetRect = RectF(left + buttonW + buttonGap, buttonY, left + buttonW * 2f + buttonGap, buttonY + df(46f))
            pipRect = RectF(left + buttonW * 2f + buttonGap * 2f, buttonY, right, buttonY + df(46f))

            val startColor = if (tracking) PINK else CYAN
            button(canvas, startRect, if (tracking) "STOP • GPS" else "START • GPS", startColor)
            button(canvas, resetRect, "RESET", MUTED)
            button(canvas, pipRect, "PIP", PURPLE)
            text(canvas, message, right, h - df(12f), df(8f), MUTED, true, Paint.Align.RIGHT)
        }

        private fun drawPip(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h * 0.46f
            text(canvas, "MOTO SPEED", cx, h * 0.14f, df(12f), CYAN, true, Paint.Align.CENTER)
            text(canvas, String.format(Locale.US, "%.1f", speedKmh.coerceAtMost(MAX_SPEED)), cx, cy, df(64f), TEXT, true, Paint.Align.CENTER)
            text(canvas, "km/h  /  MAX 110", cx, cy + df(30f), df(13f), MUTED, false, Paint.Align.CENTER)
            text(canvas, if (accuracyM < 900f) String.format(Locale.US, "±%.0f m  •  %s", accuracyM, gpsLabel()) else "SEARCHING GPS", cx, cy + df(54f), df(9f), gaugeColor(), true, Paint.Align.CENTER)
            text(canvas, if (tracking) "● LIVE • OFFLINE" else "PAUSED • OFFLINE", cx, h - df(16f), df(9f), if (tracking) GREEN else MUTED, true, Paint.Align.CENTER)
        }

        private fun premiumCard(canvas: Canvas, rect: RectF) {
            fill.style = Paint.Style.FILL
            fill.color = CARD
            fill.setShadowLayer(df(18f), 0f, df(8f), 0x70000000)
            canvas.drawRoundRect(rect, df(24f), df(24f), fill)
            fill.clearShadowLayer()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = df(1f)
            paint.shader = null
            paint.color = 0x553B4A65
            canvas.drawRoundRect(rect, df(24f), df(24f), paint)
        }

        private fun statCard(canvas: Canvas, rect: RectF, label: String, value: String, unit: String, accent: Int) {
            fill.style = Paint.Style.FILL
            fill.color = CARD2
            canvas.drawRoundRect(rect, df(16f), df(16f), fill)
            fill.color = accent
            canvas.drawRoundRect(RectF(rect.left, rect.top, rect.left + df(4f), rect.bottom), df(2f), df(2f), fill)
            text(canvas, label, rect.left + df(14f), rect.top + df(20f), df(8f), MUTED, true, Paint.Align.LEFT)
            text(canvas, value, rect.left + df(14f), rect.top + df(43f), df(18f), TEXT, true, Paint.Align.LEFT)
            if (unit.isNotEmpty()) text(canvas, unit, rect.right - df(12f), rect.top + df(43f), df(8f), accent, true, Paint.Align.RIGHT)
        }

        private fun button(canvas: Canvas, rect: RectF, label: String, accent: Int) {
            fill.style = Paint.Style.FILL
            fill.color = 0xFF151A25.toInt()
            canvas.drawRoundRect(rect, df(14f), df(14f), fill)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = df(1.5f)
            paint.shader = null
            paint.color = accent
            canvas.drawRoundRect(rect, df(14f), df(14f), paint)
            text(canvas, label, rect.centerX(), rect.centerY() + df(3f), df(8f), accent, true, Paint.Align.CENTER)
        }

        private fun pill(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, label: String, accent: Int) {
            fill.style = Paint.Style.FILL
            fill.color = 0x331EE6FF
            canvas.drawRoundRect(RectF(x, y, x + width, y + height), height / 2f, height / 2f, fill)
            text(canvas, label, x + width / 2f, y + height * 0.67f, df(8f), accent, true, Paint.Align.CENTER)
        }

        private fun drawMotorAccent(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = df(2f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.shader = null
            paint.color = CYAN
            val wheelY = y + height * 0.68f
            canvas.drawCircle(x + width * 0.2f, wheelY, df(7f), paint)
            canvas.drawCircle(x + width * 0.78f, wheelY, df(7f), paint)
            val path = Path()
            path.moveTo(x + width * 0.2f, wheelY - df(7f))
            path.lineTo(x + width * 0.35f, y + height * 0.38f)
            path.lineTo(x + width * 0.55f, y + height * 0.38f)
            path.lineTo(x + width * 0.78f, wheelY - df(7f))
            path.moveTo(x + width * 0.35f, y + height * 0.38f)
            path.lineTo(x + width * 0.45f, wheelY)
            path.lineTo(x + width * 0.62f, wheelY)
            path.moveTo(x + width * 0.55f, y + height * 0.38f)
            path.lineTo(x + width * 0.66f, y + height * 0.18f)
            canvas.drawPath(path, paint)
        }

        private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean, align: Paint.Align) {
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = color
            paint.textSize = size
            paint.typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textAlign = align
            canvas.drawText(value, x, y, paint)
        }

        private fun gaugeColor(): Int = when {
            accuracyM <= 5f -> CYAN
            accuracyM <= 10f -> GREEN
            accuracyM <= 20f -> ORANGE
            else -> PINK
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP && !pipMode) {
                when {
                    startRect.contains(event.x, event.y) -> if (tracking) stopTracking() else startTracking()
                    resetRect.contains(event.x, event.y) -> resetTrip()
                    pipRect.contains(event.x, event.y) -> enterPip()
                }
                performClick()
                return true
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun df(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        const val REQUEST_LOCATION = 42
        const val MAX_SPEED = 110f
        const val BG = 0xFF080A10.toInt()
        const val CARD = 0xFF101521.toInt()
        const val CARD2 = 0xFF131A28.toInt()
        const val TRACK = 0xFF252E40.toInt()
        const val GRID = 0xFF3A455A.toInt()
        const val TEXT = 0xFFF5F7FF.toInt()
        const val MUTED = 0xFF8994AA.toInt()
        const val CYAN = 0xFF21E6FF.toInt()
        const val PURPLE = 0xFF9B6CFF.toInt()
        const val PINK = 0xFFFF4D8D.toInt()
        const val GREEN = 0xFF39E58C.toInt()
        const val ORANGE = 0xFFFFB24A.toInt()
    }
}
