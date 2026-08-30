package com.ruzakj.speedometer

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.max

class RideReplayActivity : Activity() {
    private var ride: RideRecorderStore.RideSummary? = null
    private var exportType: String? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val id = intent.getLongExtra("ride_id", 0L)
        ride = if (id != 0L) RideRecorderStore.find(this, id) else RideRecorderStore.load(this).firstOrNull()
        val current = ride
        if (current == null) {
            setContentView(TextView(this).apply { text = "No recorded route yet"; gravity = Gravity.CENTER; textSize = 18f })
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.rgb(8, 10, 16))
        }
        root.addView(TextView(this).apply {
            text = String.format(Locale.US, "RIDE REPLAY\n%.2f km  •  MAX %.1f km/h  •  AVG %.1f km/h", current.distanceKm, current.maxKmh, current.avgKmh)
            setTextColor(Color.WHITE); textSize = 18f; setTypeface(typeface, 1)
        })
        root.addView(TextView(this).apply {
            val t60 = if (current.zeroTo60 > 0f) String.format(Locale.US, "%.2fs", current.zeroTo60) else "—"
            val t100 = if (current.zeroTo100 > 0f) String.format(Locale.US, "%.2fs", current.zeroTo100) else "—"
            text = String.format(Locale.US, "0–60 %s  •  0–100 %s  •  LEAN %.1f°  •  G %.2f", t60, t100, current.maxLeanDeg, current.maxG)
            setTextColor(Color.rgb(145, 154, 170)); textSize = 11f; setPadding(0, dp(6), 0, dp(10))
        })
        root.addView(RouteView(current), LinearLayout.LayoutParams(-1, 0, 1f))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        actions.addView(Button(this).apply { text = "EXPORT CSV"; setOnClickListener { createExport("csv") } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(Button(this).apply { text = "EXPORT GPX"; setOnClickListener { createExport("gpx") } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(actions)
        setContentView(root)
    }

    private fun createExport(type: String) {
        exportType = type
        val mime = if (type == "gpx") "application/gpx+xml" else "text/csv"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = mime
            putExtra(Intent.EXTRA_TITLE, "ride_${ride?.id ?: System.currentTimeMillis()}.$type")
        }
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    @Deprecated("Deprecated in Android API; retained for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val current = ride ?: return
        val content = if (exportType == "gpx") RideRecorderStore.gpxText(current) else RideRecorderStore.csvText(current)
        runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) } }
    }

    private inner class RouteView(private val summary: RideRecorderStore.RideSummary) : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val points = RideRecorderStore.route(summary)
        private var replayIndex = 0
        private val replay = object : Runnable {
            override fun run() {
                if (points.isNotEmpty()) {
                    replayIndex = (replayIndex + max(1, points.size / 240)).coerceAtMost(points.lastIndex)
                    invalidate()
                    if (replayIndex < points.lastIndex) postDelayed(this, 35L)
                }
            }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); postDelayed(replay, 500L) }
        override fun onDetachedFromWindow() { removeCallbacks(replay); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            c.drawColor(Color.rgb(12, 16, 24))
            if (points.size < 2) return
            val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
            val minLon = points.minOf { it.lon }; val maxLon = points.maxOf { it.lon }
            val latSpan = (maxLat - minLat).coerceAtLeast(0.000001)
            val lonSpan = (maxLon - minLon).coerceAtLeast(0.000001)
            fun x(lon: Double) = dp(18) + ((lon - minLon) / lonSpan * (width - dp(36))).toFloat()
            fun y(lat: Double) = height - dp(18) - ((lat - minLat) / latSpan * (height - dp(36))).toFloat()

            path.reset()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.lon), y(p.lat)) else path.lineTo(x(p.lon), y(p.lat)) }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3).toFloat(); paint.color = Color.rgb(33, 230, 255); paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            c.drawPath(path, paint)

            val p = points[replayIndex]
            paint.style = Paint.Style.FILL; paint.color = Color.rgb(255, 77, 141)
            c.drawCircle(x(p.lon), y(p.lat), dp(7).toFloat(), paint)
            paint.color = Color.WHITE; paint.textSize = dp(11).toFloat()
            c.drawText(String.format(Locale.US, "%.1f km/h  •  %.0f m  •  %.1f%%", p.speedKmh, p.altitude, p.gradientPct), dp(14).toFloat(), dp(24).toFloat(), paint)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    companion object { private const val REQUEST_EXPORT = 600 }
}
