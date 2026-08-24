package com.ruzakj.speedometer

import android.app.Activity
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
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import java.util.Locale
import kotlin.math.max

/** Secondary telemetry layer. Never paints over the PiP dashboard. */
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
        override fun run() {
            if (isInPip()) {
                invalidate()
            } else {
                hideSystemBars()
                invalidate()
            }
            handler.postDelayed(this, 250L)
        }
    }

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        post { hideSystemBars() }
        handler.post(refresh)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun activity(): Activity? = context as? Activity
    private fun isInPip(): Boolean = Build.VERSION.SDK_INT >= 24 && activity()?.isInPictureInPictureMode == true

    private fun hideSystemBars() {
        val a = activity() ?: return
        if (isInPip()) return
        if (Build.VERSION.SDK_INT >= 30) {
            a.window.setDecorFitsSystemWindows(false)
            a.window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            a.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun smartMovingMs(): Long = movingMs

    // Keep the controls in the empty lower area of the dashboard instead of covering the title/gauge.
    private fun chipRect(): RectF {
        val bottom = height.toFloat() - dp(62)
        return RectF(width - dp(112).toFloat(), bottom - dp(34).toFloat(), width - dp(12).toFloat(), bottom)
    }

    private fun isGraphTouch(e: MotionEvent): Boolean =
        showGraph && e.x < width * .72f && e.y > height - dp(250)

    private fun isChipTouch(e: MotionEvent): Boolean = chipRect().contains(e.x, e.y)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isInPip()) return false
        if (event.action == MotionEvent.ACTION_DOWN) return isChipTouch(event) || isGraphTouch(event)
        if (event.action == MotionEvent.ACTION_UP) {
            if (isChipTouch(event)) {
                val current = limit()
                val next = limits.firstOrNull { it > current + .1f } ?: limits.first()
                setLimit(next)
                invalidate()
                performClick()
                return true
            }
            if (isGraphTouch(event)) {
                showGraph = false
                invalidate()
                performClick()
                return true
            }
        }
        return false
    }

    private fun sample(now: Long, s: Float) {
        if (lastSampleAt != 0L) {
            val dt = now - lastSampleAt
            if (dt in 1..2_000) {
                if (s >= 2f) {
                    movingState = true
                    stoppedSince = 0L
                } else if (movingState && s <= 1f) {
                    if (stoppedSince == 0L) stoppedSince = now
                    if (now - stoppedSince >= 4_000L) movingState = false
                } else if (s > 1f) {
                    stoppedSince = 0L
                }
                if (movingState) movingMs += dt
            }
        }
        lastSampleAt = now
        history.addLast(s)
        while (history.size > 120) history.removeFirst()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (isInPip()) return

        val now = System.currentTimeMillis()
        val s = speed().coerceIn(0f, 110f)
        sample(now, s)
        val lim = limit().coerceIn(30f, 110f)
        val acc = accuracy()
        val sAcc = speedAccuracy()
        val over = s >= lim && s >= 3f
        if (over && !lastOverspeed) {
            flashUntil = now + 1800L
            vibrate()
        }
        lastOverspeed = over

        // Compact telemetry strip at the very bottom. Nothing is drawn over the title, gauge or stat cards.
        val bottom = height.toFloat() - dp(62)
        val left = dp(12).toFloat()
        val chip = chipRect()
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
        text(c, "$gps  •  ±${if (acc < 900f) String.format(Locale.US, "%.0f", acc) else "—"} m  •  $speedAccText", left, bottom - dp(40), 8f, if (acc <= 10f) 0xFF56F0D0.toInt() else 0xFFFFB84D.toInt(), Paint.Align.LEFT, true)
        text(c, if (movingState) "MOVING" else "SMART STOP  •  ${formatMoving(movingMs)}", left, bottom - dp(24), 8f, if (movingState) 0xFF56F0D0.toInt() else 0xFF9AA5B5.toInt(), Paint.Align.LEFT, true)

        if (showGraph) drawGraph(c, dp(12).toFloat(), height - dp(245).toFloat(), width * .72f, dp(150).toFloat())
        if (over) text(c, String.format(Locale.US, "OVERSPEED  %.1f km/h", s), width / 2f, height - dp(110).toFloat(), 12f, 0xFFFF4966.toInt(), Paint.Align.CENTER, true)
    }

    private fun drawGraph(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xEE141A24.toInt()
        c.drawRoundRect(RectF(x, y, x + w, y + h), dp(14).toFloat(), dp(14).toFloat(), paint)
        val data = history.toList()
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

    private fun formatMoving(ms: Long): String {
        val s = ms / 1000L
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(220L, VibrationEffect.DEFAULT_AMPLITUDE)) else v.vibrate(220L)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
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
