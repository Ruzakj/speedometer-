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

class RideInsightsOverlay(
    context: Context,
    private val speed: () -> Float,
    private val accuracy: () -> Float,
    private val speedAccuracy: () -> Float,
    private val limit: () -> Float,
    private val setLimit: (Float) -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val handler = Handler(Looper.getMainLooper())
    private val history = ArrayDeque<Float>()
    private val limits = floatArrayOf(50f, 60f, 70f, 80f, 90f, 100f, 110f)
    private var lastOverspeed = false
    private var flashUntil = 0L
    private var showGraph = false
    private var movingState = false
    private var stoppedSince = 0L
    private var movingMs = 0L
    private var lastSampleAt = 0L

    private val refresh = object : Runnable {
        override fun run() { invalidate(); handler.postDelayed(this, 250L) }
    }

    init { setLayerType(View.LAYER_TYPE_SOFTWARE, null); handler.post(refresh) }
    override fun onDetachedFromWindow() { handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }

    fun smartMovingMs(): Long = movingMs

    private fun isGraphTouch(e: MotionEvent) = e.y in dp(54).toFloat()..dp(154).toFloat() && e.x < width * .72f
    private fun isChipTouch(e: MotionEvent) = RectF((width - dp(112)).toFloat(), dp(8).toFloat(), (width - dp(12)).toFloat(), dp(48).toFloat()).contains(e.x, e.y)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return isChipTouch(event) || isGraphTouch(event)
        if (event.action == MotionEvent.ACTION_UP) {
            if (isChipTouch(event)) {
                val current = limit()
                val next = limits.firstOrNull { it > current + .1f } ?: limits.first()
                setLimit(next); invalidate(); performClick(); return true
            }
            if (isGraphTouch(event)) { showGraph = !showGraph; invalidate(); performClick(); return true }
        }
        return false
    }

    private fun sample(now: Long, s: Float) {
        if (lastSampleAt != 0L) {
            val dt = now - lastSampleAt
            if (dt in 1..2_000) {
                if (s >= 2f) { movingState = true; stoppedSince = 0L }
                else if (movingState && s <= 1f) {
                    if (stoppedSince == 0L) stoppedSince = now
                    if (now - stoppedSince >= 4_000L) movingState = false
                } else if (s > 1f) stoppedSince = 0L
                if (movingState) movingMs += dt
            }
        }
        lastSampleAt = now
        history.addLast(s)
        while (history.size > 120) history.removeFirst()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val now = System.currentTimeMillis()
        val s = speed().coerceIn(0f, 110f)
        sample(now, s)
        val lim = limit().coerceIn(30f, 110f)
        val acc = accuracy()
        val sAcc = speedAccuracy()
        val over = s >= lim && s >= 3f
        if (over && !lastOverspeed) { flashUntil = now + 1800L; vibrate() }
        lastOverspeed = over

        val left = dp(12).toFloat(); val top = dp(8).toFloat()
        val chip = RectF((width - dp(112)).toFloat(), top, (width - dp(12)).toFloat(), dp(48).toFloat())
        paint.style = Paint.Style.FILL
        paint.color = if (over || now < flashUntil) 0xD9FF355E.toInt() else 0xC91A202B.toInt()
        c.drawRoundRect(chip, dp(18).toFloat(), dp(18).toFloat(), paint)
        text(c, String.format(Locale.US, "LIMIT %.0f", lim), chip.centerX(), chip.centerY() + dp(4), 10f, 0xFFF7F9FC.toInt(), Paint.Align.CENTER, true)

        val gps = when { acc <= 5f -> "GPS EXCELLENT"; acc <= 10f -> "GPS GOOD"; acc <= 20f -> "GPS FAIR"; acc < 900f -> "GPS WEAK"; else -> "GPS SEARCHING" }
        val speedAccText = if (sAcc.isFinite()) String.format(Locale.US, "±%.1f m/s", sAcc) else "speed ±—"
        text(c, "$gps  •  ±${if (acc < 900f) String.format(Locale.US, "%.0f", acc) else "—"} m  •  $speedAccText", left, top + dp(15), 9f, if (acc <= 10f) 0xFF56F0D0.toInt() else 0xFFFFB84D.toInt(), Paint.Align.LEFT, true)
        text(c, if (movingState) "MOVING" else "SMART STOP  •  ${formatMoving(movingMs)}", left, top + dp(32), 8f, if (movingState) 0xFF56F0D0.toInt() else 0xFF9AA5B5.toInt(), Paint.Align.LEFT, true)
        text(c, "tap graph area  •  LIMIT tap cycles 50–110", left, top + dp(46), 7f, 0xFF657186.toInt(), Paint.Align.LEFT, false)
        if (showGraph) drawGraph(c, dp(12).toFloat(), dp(60).toFloat(), width * .72f, dp(92).toFloat())
        if (over) text(c, String.format(Locale.US, "OVERSPEED  %.1f km/h", s), width / 2f, (height - dp(18)).toFloat(), 12f, 0xFFFF4966.toInt(), Paint.Align.CENTER, true)
    }

    private fun drawGraph(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        paint.style = Paint.Style.FILL; paint.color = 0xB8141A24.toInt(); c.drawRoundRect(RectF(x, y, x + w, y + h), dp(14).toFloat(), dp(14).toFloat(), paint)
        val data = history.toList()
        if (data.size < 2) { text(c, "SPEED GRAPH  •  collecting…", x + dp(12), y + dp(22), 9f, 0xFF9AA5B5.toInt(), Paint.Align.LEFT, true); return }
        val maxV = max(20f, data.maxOrNull() ?: 20f); path.reset()
        data.forEachIndexed { i, v ->
            val px = x + dp(10) + (w - dp(20)) * i.toFloat() / (data.size - 1).coerceAtLeast(1)
            val py = y + h - dp(10) - (h - dp(24)) * (v / maxV).coerceIn(0f, 1f)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); paint.color = 0xFF31D8FF.toInt(); c.drawPath(path, paint)
        text(c, String.format(Locale.US, "LIVE SPEED  max %.1f", data.maxOrNull() ?: 0f), x + dp(12), y + dp(18), 8f, 0xFFDAE5F2.toInt(), Paint.Align.LEFT, true)
    }

    private fun formatMoving(ms: Long): String {
        val s = ms / 1000L
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(220L, VibrationEffect.DEFAULT_AMPLITUDE)) else v.vibrate(220L)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align, bold: Boolean) {
        paint.style = Paint.Style.FILL; paint.color = color; paint.textSize = dp(size).toFloat(); paint.textAlign = align
        paint.typeface = if (bold) android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) else android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        c.drawText(value, x, y, paint)
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float) = v * resources.displayMetrics.density
}
