package com.ruzakj.speedometer

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.location.*
import android.os.*
import android.util.Rational
import android.view.*
import java.util.Locale
import kotlin.math.*

class MainActivityV2 : Activity(), LocationListener {
    private lateinit var ui: Dash
    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var last: Location? = null
    private var lastNs = 0L
    private var speed = 0f
    private var maxSpeed = 0f
    private var distance = 0f
    private var movingMs = 0L
    private var samples = 0
    private var sum = 0.0
    private var accuracy = 999f
    private var speedAccuracy = Float.MAX_VALUE

    private val tick = object : Runnable {
        override fun run() {
            ui.invalidate()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        ui = Dash(this)
        ui.setOnApplyWindowInsetsListener { v, i ->
            if (Build.VERSION.SDK_INT >= 30) {
                val x = i.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(dp(14), x.top + dp(8), dp(14), x.bottom + dp(8))
            }
            i
        }
        setContentView(ui)
        handler.post(tick)
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                10
            )
            return
        }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            ui.msg = "GPS OFF • ENABLE LOCATION"
            ui.invalidate()
            return
        }
        running = true
        ui.msg = "GPS ACTIVE • OFFLINE"
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this, Looper.getMainLooper())
        } catch (_: SecurityException) {
            running = false
        }
        ui.invalidate()
    }

    private fun stop() {
        running = false
        lm.removeUpdates(this)
        ui.msg = "GPS PAUSED • OFFLINE"
        ui.invalidate()
    }

    private fun reset() {
        maxSpeed = 0f
        distance = 0f
        movingMs = 0L
        samples = 0
        sum = 0.0
        last = null
        lastNs = 0L
        speed = 0f
        accuracy = 999f
        speedAccuracy = Float.MAX_VALUE
        ui.msg = if (running) "GPS ACTIVE • OFFLINE" else "READY • OFFLINE"
        ui.invalidate()
    }

    override fun onLocationChanged(l: Location) {
        accuracy = if (l.hasAccuracy()) l.accuracy else 999f
        val raw = if (l.hasSpeed()) max(0f, l.speed) else 0f
        speedAccuracy = if (Build.VERSION.SDK_INT >= 26 && l.hasSpeedAccuracy()) {
            l.speedAccuracyMetersPerSecond
        } else {
            Float.MAX_VALUE
        }
        if (accuracy > 35f) {
            ui.msg = String.format(Locale.US, "GPS WEAK • ±%.0f m", accuracy)
            ui.invalidate()
            return
        }

        val ns = l.elapsedRealtimeNanos
        val old = last
        val dt = if (old != null && ns > lastNs) (ns - lastNs) / 1e9f else 0f
        if (old != null && dt > 0f) {
            val jump = old.distanceTo(l)
            val derived = jump / dt
            if (derived > 69.4f && raw < 55f) return
            if (jump > 120f && dt < 2.5f) return
            distance += jump
            if (raw > 0.5f) movingMs += (dt * 1000f).toLong()
        }

        val q = quality()
        val target = raw * 3.6f
        val alpha = 0.22f + 0.58f * q
        speed += (target - speed) * alpha
        if (abs(speed) < 1f && target < 1.5f) speed = 0f

        last = Location(l)
        lastNs = ns
        maxSpeed = max(maxSpeed, speed)
        if (q > 0.35f) {
            samples++
            sum += speed
        }
        ui.invalidate()
    }

    private fun quality(): Float {
        val p = (1f - (accuracy - 3f) / 27f).coerceIn(0f, 1f)
        val v = if (speedAccuracy == Float.MAX_VALUE) 0.55f
        else (1f - speedAccuracy / 5f).coerceIn(0f, 1f)
        return (p * 0.7f + v * 0.3f).coerceIn(0f, 1f)
    }

    private fun avg() = if (samples > 0) (sum / samples).toFloat() else 0f

    private fun gps() = when {
        accuracy <= 5f -> "EXCELLENT"
        accuracy <= 10f -> "GOOD"
        accuracy <= 20f -> "FAIR"
        accuracy < 900f -> "WEAK"
        else -> "SEARCHING"
    }

    private fun time(): String {
        val t = movingMs / 1000
        return if (t >= 3600) String.format(Locale.US, "%02d:%02d:%02d", t / 3600, t % 3600 / 60, t % 60)
        else String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
    }

    private fun pip() {
        if (Build.VERSION.SDK_INT >= 26) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(9, 16))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        if (r == 10 && g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) start()
    }

    override fun onProviderDisabled(p: String) {
        if (p == LocationManager.GPS_PROVIDER) {
            ui.msg = "GPS OFF • ENABLE LOCATION"
            ui.invalidate()
        }
    }

    override fun onProviderEnabled(p: String) {
        if (p == LocationManager.GPS_PROVIDER) {
            ui.msg = "GPS READY • OFFLINE"
            ui.invalidate()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        lm.removeUpdates(this)
        super.onDestroy()
    }

    private inner class Dash(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        var msg = "READY • OFFLINE"
        private var a = RectF()
        private var b = RectF()
        private var d = RectF()

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            c.drawColor(BG)
            val w = width.toFloat()
            val h = height.toFloat()
            val l = dp(14).toFloat()
            val r = w - l
            val cw = r - l
            txt(c, "SPEEDOMETER", l, dp(25).toFloat(), 19, TEXT, true, Paint.Align.LEFT)
            txt(c, if (running) "● GPS ACTIVE" else "○ GPS READY", l, dp(46).toFloat(), 10,
                if (running) GREEN else MUTED, true, Paint.Align.LEFT)
            pill(c, r - dp(84), dp(12), dp(84), dp(28), "OFFLINE", CYAN)
            pill(c, r - dp(170), dp(12), dp(78), dp(28), gps(), GREEN)

            val top = dp(62).toFloat()
            val gh = min(dp(400).toFloat(), h * 0.49f)
            val box = RectF(l, top, r, top + gh)
            card(c, box, PANEL)
            val cx = w / 2f
            val cy = box.top + gh * 0.57f
            val rad = min(cw * 0.39f, gh * 0.43f)
            val arc = RectF(cx - rad, cy - rad, cx + rad, cy + rad)
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = dp(12).toFloat()
            p.color = TRACK
            c.drawArc(arc, 140f, 260f, false, p)
            p.color = gcol()
            c.drawArc(arc, 140f, 260f * (speed / 180f).coerceIn(0f, 1f), false, p)
            p.strokeWidth = dp(2).toFloat()
            for (i in 0..9) {
                val an = Math.toRadians(140 + i * 260.0 / 9)
                val rr1 = rad - dp(18)
                val rr2 = rad - dp(5)
                p.color = if (i * 20 <= speed) gcol() else GRID
                c.drawLine(
                    cx + cos(an).toFloat() * rr1,
                    cy + sin(an).toFloat() * rr1,
                    cx + cos(an).toFloat() * rr2,
                    cy + sin(an).toFloat() * rr2,
                    p
                )
            }
            txt(c, "GPS SPEED", cx, cy - dp(52).toFloat(), 12, MUTED, true, Paint.Align.CENTER)
            txt(c, String.format(Locale.US, "%.1f", speed), cx, cy + dp(20).toFloat(), 64, TEXT, false, Paint.Align.CENTER)
            txt(c, "km/h", cx, cy + dp(49).toFloat(), 16, MUTED, false, Paint.Align.CENTER)
            txt(c, if (accuracy < 900) String.format(Locale.US, "GPS ±%.0f m", accuracy) else "WAITING FOR GPS",
                cx, cy + dp(76).toFloat(), 11, gcol(), true, Paint.Align.CENTER)

            val it = box.bottom + dp(10)
            val ih = dp(94).toFloat()
            card(c, RectF(l, it, r, it + ih), PANEL)
            txt(c, "RIDE DATA", l + dp(14), it + dp(20).toFloat(), 10, MUTED, true, Paint.Align.LEFT)
            info(c, "AVG", String.format(Locale.US, "%.1f km/h", avg()), l + dp(14), it)
            info(c, "MAX", String.format(Locale.US, "%.1f km/h", maxSpeed), l + dp(112), it)
            info(c, "TRIP", String.format(Locale.US, "%.2f km", distance / 1000), l + dp(210), it)
            info(c, "TIME", time(), r - dp(78), it)
            txt(c, "ACCURACY", l + dp(14), it + dp(76).toFloat(), 9, MUTED, true, Paint.Align.LEFT)
            txt(c, if (accuracy < 900) String.format(Locale.US, "±%.0f m", accuracy) else "—",
                l + dp(14), it + dp(91).toFloat(), 13, TEXT, true, Paint.Align.LEFT)
            txt(c, "GPS", l + dp(112), it + dp(76).toFloat(), 9, MUTED, true, Paint.Align.LEFT)
            txt(c, gps(), l + dp(112), it + dp(91).toFloat(), 13, gcol(), true, Paint.Align.LEFT)
            txt(c, "MODE", l + dp(210), it + dp(76).toFloat(), 9, MUTED, true, Paint.Align.LEFT)
            txt(c, "SPORT", l + dp(210), it + dp(91).toFloat(), 13, CYAN, true, Paint.Align.LEFT)

            val by = h - dp(65).toFloat()
            a = RectF(l, by, l + cw * 0.46f, by + dp(48))
            b = RectF(l + cw * 0.48f, by, l + cw * 0.72f, by + dp(48))
            d = RectF(r - cw * 0.24f, by, r, by + dp(48))
            action(c, a, if (running) "STOP" else "START", running)
            action(c, b, "RESET", false)
            action(c, d, "PIP", false)
            txt(c, msg, w / 2f, by - dp(8).toFloat(), 10, MUTED, true, Paint.Align.CENTER)
        }

        private fun info(c: Canvas, label: String, value: String, x: Float, y: Float) {
            txt(c, label, x, y + dp(43).toFloat(), 9, MUTED, true, Paint.Align.LEFT)
            txt(c, value, x, y + dp(61).toFloat(), 13, TEXT, true, Paint.Align.LEFT)
        }

        private fun action(c: Canvas, q: RectF, s: String, on: Boolean) {
            fill.color = if (on) GREEN else PANEL
            c.drawRoundRect(q, dp(14).toFloat(), dp(14).toFloat(), fill)
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(1).toFloat()
            p.color = if (on) GREEN else GRID
            c.drawRoundRect(q, dp(14).toFloat(), dp(14).toFloat(), p)
            txt(c, s, q.centerX(), q.centerY() + dp(5).toFloat(), 12, if (on) BG else TEXT, true, Paint.Align.CENTER)
        }

        private fun pill(c: Canvas, x: Float, y: Float, w: Float, h: Float, s: String, col: Int) {
            fill.color = if (s == "OFFLINE") PANEL else if (s == "GOOD" || s == "EXCELLENT") GREEN_DARK else PANEL
            c.drawRoundRect(RectF(x, y, x + w, y + h), h / 2, h / 2, fill)
            txt(c, s, x + w / 2, y + h * 0.67f, 9, col, true, Paint.Align.CENTER)
        }

        private fun card(c: Canvas, q: RectF, col: Int) {
            fill.color = col
            c.drawRoundRect(q, dp(20).toFloat(), dp(20).toFloat(), fill)
        }

        private fun gcol() = when {
            accuracy <= 5f -> GREEN
            accuracy <= 10f -> CYAN
            accuracy <= 20f -> AMBER
            else -> MUTED
        }

        private fun txt(c: Canvas, s: String, x: Float, y: Float, z: Float, col: Int, bold: Boolean, align: Paint.Align) {
            p.style = Paint.Style.FILL
            p.textAlign = align
            p.typeface = if (bold) Typeface.create("sans", Typeface.BOLD) else Typeface.create("sans", Typeface.NORMAL)
            p.textSize = z * resources.displayMetrics.scaledDensity
            p.color = col
            c.drawText(s, x, y, p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_UP) {
                when {
                    a.contains(e.x, e.y) -> if (running) stop() else start()
                    b.contains(e.x, e.y) -> reset()
                    d.contains(e.x, e.y) -> pip()
                }
            }
            return true
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
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
