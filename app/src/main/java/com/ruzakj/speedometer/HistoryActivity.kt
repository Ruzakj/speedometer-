package com.ruzakj.speedometer

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class HistoryActivity : Activity() {
    private val bg = Color.rgb(9, 11, 16)
    private val text = Color.rgb(240, 244, 250)
    private val muted = Color.rgb(145, 154, 170)
    private val cyan = Color.rgb(30, 230, 255)
    private val purple = Color.rgb(176, 92, 255)
    private val pink = Color.rgb(255, 75, 155)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "RIDE HISTORY"
            textSize = 22f
            setTextColor(text)
            setTypeface(typeface, 1)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(TextView(this).apply {
            text = "CLEAR"
            textSize = 11f
            setTextColor(purple)
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { HistoryStore.clear(this@HistoryActivity); render(root) }
        }, LinearLayout.LayoutParams(dp(72), dp(40)))
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Stored locally • offline • unlimited rides"
            textSize = 11f
            setTextColor(muted)
            setPadding(0, 0, 0, dp(10))
        })
        root.addView(TextView(this).apply {
            tag = "total_distance"
            textSize = 18f
            setTextColor(cyan)
            setTypeface(typeface, 1)
            setPadding(0, 0, 0, dp(8))
        })
        setContentView(root)
        render(root)
    }

    private fun render(root: LinearLayout) {
        while (root.childCount > 3) root.removeViewAt(3)
        val trips = HistoryStore.load(this)
        root.findViewWithTag<TextView>("total_distance")?.text = String.format(Locale.US, "TOTAL DISTANCE  %.2f km", HistoryStore.totalDistanceKm(this))
        if (trips.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "NO RIDES YET\n\nStart GPS tracking and finish a trip to see it here."
                textSize = 15f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(70), dp(20), dp(70))
            })
            return
        }

        val topSpeed = trips.maxOfOrNull { it.maxKmh } ?: 0f
        val longest = trips.maxOfOrNull { it.distanceKm } ?: 0f
        val totalMoving = trips.sumOf { it.movingMs }
        val avgRide = trips.map { it.distanceKm.toDouble() }.average().toFloat()
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = rounded(0x141B2230)
        }
        summary.addView(TextView(this).apply {
            text = String.format(Locale.US, "%d RIDES   •   TOP %.1f km/h   •   LONGEST %.2f km", trips.size, topSpeed, longest)
            textSize = 10f
            setTextColor(muted)
        })
        summary.addView(TextView(this).apply {
            text = String.format(Locale.US, "AVG RIDE %.2f km   •   MOVING %s", avgRide, HistoryStore.duration(totalMoving))
            textSize = 10f
            setTextColor(pink)
            setTypeface(typeface, 1)
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(summary, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(12)) })

        trips.forEachIndexed { index, trip ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(13), dp(16), dp(13))
                background = rounded(if (index == 0) 0x2020E6FF else 0x181B2230)
            }
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(this).apply {
                text = "RIDE ${trips.size - index}"
                textSize = 12f
                setTextColor(if (index == 0) cyan else text)
                setTypeface(typeface, 1)
            }, LinearLayout.LayoutParams(0, dp(30), 1f))
            top.addView(TextView(this).apply {
                text = HistoryStore.date(trip.timestamp)
                textSize = 10f
                setTextColor(muted)
            })
            card.addView(top)
            card.addView(TextView(this).apply {
                text = String.format(Locale.US, "%.2f km    •    MAX %.1f km/h    •    AVG %.1f km/h\nMOVING %s", trip.distanceKm, trip.maxKmh, trip.avgKmh, HistoryStore.duration(trip.movingMs))
                textSize = 12f
                setTextColor(text)
                setPadding(0, dp(4), 0, 0)
            })
            root.addView(card, LinearLayout.LayoutParams(-1, dp(88)).apply { setMargins(0, 0, 0, dp(10)) })
        }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(18).toFloat()
        setStroke(dp(1), 0x443B4A65)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
