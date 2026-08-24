package com.ruzakj.speedometer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.max

/** Lightweight live telemetry overlay. It deliberately draws only in the top HUD area
 * so the existing portrait/landscape speedometer remains the primary interface. */
class RideInsightsOverlay(
    context: Context,
    private val speed: () -> Float,
    private val accuracy: () -> Float,
    private val speedAccuracy: () -> Float,
    private val movingTime: () -> String,
    private val moving: () -> Boolean,
    private val limit: () -> Float,
    private val setLimit: (Float) -> Unit,
    private val samples: () -> List<Float>
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val handler = Handler(Looper.getMainLooper())
    private var lastOverspeed = false
    private var flashUntil = 0L
    private var showGraph = false
    private val limits = floatArrayOf(50f, 60f, 70f, 80f, 90f, 100f, 110f)
    private val refresh = object : Runnable {
        override fun run() { invalidate(); handler.postDelayed(this, 250L) }
    }

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        handler.post(refresh)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        // Top-right telemetry chip cycles the configurable overspeed limit.
        val chip = RectF(width - dp(112), dp(8), width - dp(12), dp(48))
        if (chip.contains(event.x, event.y)) {
            val current = limit()
            val next = limits.firstOrNull { it > current + .1f } ?: limits.first()
            setLimit(next)
            invalidate()
            return true
        }
        // Long-ish tap on the graph area toggles the live graph.
        if (event.y in dp(54).toFloat()..dp(154).toFloat() && event.x < width * .72f) {
            showGraph = !showGraph
            invalidate()
            return true
        }
        return false
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val now = System.currentTimeMillis()
        val s = speed().coerceIn(0f, 110f)
        val lim = limit().coerceIn(30f, 110f)
        val acc = accuracy()
        val sAcc = speedAccuracy()
        val over = s >= lim && s >= 3f
        if (over && !lastOverspeed) {
            flashUntil = now + 1800L
            vibrate()
        }
        lastOverspeed = over

        val left = dp(12).toFloat()
        val top = dp(8).toFloat()
        val chip = RectF(width - dp(112).toFloat(), top, width - dp(12).toFloat(), dp(48).toFloat())
        paint.style = Paint.Style.FILL
        paint.color = if (over || now < flashUntil) 0xD9FF355E.toInt() else 0xC91A202B.toInt()
        c.drawRoundRect(chip, dp(18).toFloat(), dp(18).toFloat(), paint)
        text(c, String.format(Locale.US, "LIMIT %.0f", lim), chip.centerX(), chip.centerY() + dp(4), 10f, 0xFFF7F9FC.toInt(), Paint.Align.CENTER, true)

        val gps = when {
            acc <= 5f -> "GPS EXCELLENT"
            acc <= 10f -> "GPS GOOD"
            acc <= 20f -> "GPS FAIR"
            acc < 900f -> "GPS WEAK"
            else -> "GPS SEARCHING"
        }
        val speedAccText = if (sAcc.isFinite()) String.format(Locale.US, "±%.1f m/s", sAcc) else "speed ±—"
        text(c, "$gps  •  ±${if (acc < 900f) String.format(Locale.US, "%.0f", acc) else "—"} m  •  $speedAccText", left, top + dp(15), 9f,
            if (acc <= 10f) 0xFF56F0D0.toInt() else 0xFFFFB84D.toInt(), Paint.Align.LEFT, true)
        text(c, if (moving()) "MOVING" else "SMART STOP", left, top + dp(32), 8f,
            if (moving()) 0xFF56F0D0.toInt() else 0xFF9AA5B5.toInt(), Paint.Align.LEFT, true)

        if (showGraph) drawGraph(c, dp(12).toFloat(), dp(60).toFloat(), width * .72f, dp(92).toFloat())
        if (over) {
            text(c, String.format(Locale.US, "OVERSPEED  %.1f km/h", s), width / 2f, height - dp(18), 12f, 0xFFFF4966.toInt(), Paint.Align.CENTER, true)
        }
    }

    private fun drawGraph(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xB8141A24.toInt()
        c.drawRoundRect(RectF(x, y, x + w, y + h), dp(14).toFloat(), dp(14).toFloat(), paint)
        val data = samples()
        if (data.size < 2) {
            text(c, "SPEED GRAPH  •  collecting…", x + dp(12), y + dp(22), 9f, 0xFF9AA5B5.toInt(), Paint.Align.LEFT, true)
            return
        }
        val maxV = max(20f, data.maxOrNull() ?: 20f)
        path.reset()
        data.forEachIndexed { i, v ->
            val px = x + dp(10) + (w - dp(20)) * i.toFloat() / (data.size - 1).coerceAtLeast(1)
            val py = y + h - dp(10) - (h - dp(24)) * (v / maxV).coerceIn(0f, 1f)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        paint.color = 0xFF31D8FF.toInt()
        c.drawPath(path, paint)
        text(c, String.format(Locale.US, "LIVE SPEED  max %.1f", data.maxOrNull() ?: 0f), x + dp(12), y + dp(18), 8f, 0xFFDAE5F2.toInt(), Paint.Align.LEFT, true)
    }

    private fun vibrate() {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(220L, VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") v.vibrate(220L)
    }

    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align, bold: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = dp(size).toFloat()
        paint.textAlign = align
        paint.typeface = if (bold) android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) else android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        c.drawText(value, x, y, paint)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float) = v * resources.displayMetrics.density
}
