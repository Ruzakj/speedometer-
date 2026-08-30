package com.ruzakj.speedometer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
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
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(bg)
        }
        scroll.addView(root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "RIDE HISTORY"; textSize = 22f; setTextColor(this@HistoryActivity.text); setTypeface(typeface, 1)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(TextView(this).apply {
            text = "REPLAY"; textSize = 10f; setTextColor(cyan); gravity = Gravity.CENTER
            setOnClickListener { RideRecorderStore.load(this@HistoryActivity).firstOrNull()?.let { openReplay(it.id) } }
        }, LinearLayout.LayoutParams(dp(70), dp(40)))
        header.addView(TextView(this).apply {
            text = "CLEAR"; textSize = 10f; setTextColor(purple); gravity = Gravity.CENTER
            setOnClickListener { HistoryStore.clear(this@HistoryActivity); render(root) }
        }, LinearLayout.LayoutParams(dp(60), dp(40)))
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Background routes • telemetry • replay • GPX/CSV export"
            textSize = 11f; setTextColor(muted); setPadding(0, 0, 0, dp(10))
        })
        root.addView(TextView(this).apply {
            tag = "total_distance"; textSize = 18f; setTextColor(cyan); setTypeface(typeface, 1); setPadding(0, 0, 0, dp(8))
        })
        setContentView(scroll)
        render(root)
    }

    private fun render(root: LinearLayout) {
        while (root.childCount > 3) root.removeViewAt(3)
        val advanced = RideRecorderStore.load(this)
        val legacy = HistoryStore.load(this)
        val total = advanced.sumOf { it.distanceKm.toDouble() }.takeIf { it > 0.0 } ?: HistoryStore.totalDistanceKm(this)
        root.findViewWithTag<TextView>("total_distance")?.text = String.format(Locale.US, "TOTAL DISTANCE  %.2f km", total)

        if (advanced.isNotEmpty()) {
            root.addView(TextView(this).apply {
                text = "PRECISION RECORDED RIDES"; textSize = 10f; setTextColor(purple); setTypeface(typeface, 1); setPadding(0, dp(6), 0, dp(8))
            })
            advanced.forEachIndexed { index, ride ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = rounded(if (index == 0) 0x2020E6FF.toInt() else 0x181B2230.toInt())
                    setOnClickListener { openReplay(ride.id) }
                }
                card.addView(TextView(this).apply {
                    text = "RIDE ${advanced.size - index}  •  ${HistoryStore.date(ride.startedAt)}"
                    textSize = 11f; setTextColor(if (index == 0) cyan else this@HistoryActivity.text); setTypeface(typeface, 1)
                })
                val t60 = if (ride.zeroTo60 > 0f) String.format(Locale.US, "%.2fs", ride.zeroTo60) else "—"
                val t100 = if (ride.zeroTo100 > 0f) String.format(Locale.US, "%.2fs", ride.zeroTo100) else "—"
                card.addView(TextView(this).apply {
                    text = String.format(Locale.US, "%.2f km  •  MAX %.1f  •  AVG %.1f km/h\n0–60 %s  •  0–100 %s  •  LEAN %.1f°  •  G %.2f\nTap = Replay / Export", ride.distanceKm, ride.maxKmh, ride.avgKmh, t60, t100, ride.maxLeanDeg, ride.maxG)
                    textSize = 11f; setTextColor(this@HistoryActivity.text); setPadding(0, dp(5), 0, 0)
                })
                root.addView(card, LinearLayout.LayoutParams(-1, dp(96)).apply { setMargins(0, 0, 0, dp(10)) })
            }
        }

        if (legacy.isEmpty() && advanced.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "NO RIDES YET\n\nStart GPS tracking and finish a trip to see it here."
                textSize = 15f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(dp(20), dp(70), dp(20), dp(70))
            })
            return
        }

        if (legacy.isNotEmpty()) {
            root.addView(TextView(this).apply {
                text = "LEGACY TRIP SUMMARY"; textSize = 10f; setTextColor(muted); setTypeface(typeface, 1); setPadding(0, dp(10), 0, dp(8))
            })
            legacy.take(20).forEachIndexed { index, trip ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); background = rounded(0x181B2230.toInt())
                }
                card.addView(TextView(this).apply {
                    text = "OLD RIDE ${legacy.size - index}  •  ${HistoryStore.date(trip.timestamp)}"
                    textSize = 10f; setTextColor(muted)
                })
                card.addView(TextView(this).apply {
                    text = String.format(Locale.US, "%.2f km  •  MAX %.1f  •  AVG %.1f km/h  •  %s", trip.distanceKm, trip.maxKmh, trip.avgKmh, HistoryStore.duration(trip.movingMs))
                    textSize = 11f; setTextColor(this@HistoryActivity.text); setPadding(0, dp(5), 0, 0)
                })
                root.addView(card, LinearLayout.LayoutParams(-1, dp(68)).apply { setMargins(0, 0, 0, dp(8)) })
            }
        }
    }

    private fun openReplay(id: Long) {
        startActivity(Intent(this, RideReplayActivity::class.java).putExtra("ride_id", id))
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0x443B4A65.toInt())
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
