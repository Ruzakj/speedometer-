package com.ruzakj.speedometer

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

class SpeedometerApp : Application() {
    private var lastSavedKey = ""
    private var wasTracking = false
    private val handler = Handler(Looper.getMainLooper())
    private val sessionMonitor = object : Runnable {
        override fun run() {
            val activity = currentMain
            if (activity != null) {
                val tracking = readBoolean(activity, "tracking")
                if (wasTracking && !tracking) saveSnapshot(activity)
                wasTracking = tracking
            }
            handler.postDelayed(this, 500L)
        }
    }
    private var currentMain: MainActivityV2? = null

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) { if (activity is MainActivityV2) { currentMain = activity; installHistoryButton(activity) } }
            override fun onActivityResumed(activity: Activity) { if (activity is MainActivityV2) { currentMain = activity; installHistoryButton(activity) } }
            override fun onActivityPaused(activity: Activity) { if (activity is MainActivityV2) saveSnapshot(activity) }
            override fun onActivityDestroyed(activity: Activity) { if (activity === currentMain) currentMain = null }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
        handler.post(sessionMonitor)
    }

    private fun installHistoryButton(activity: Activity) {
        val decor = activity.window.decorView as? FrameLayout ?: return
        if (decor.findViewWithTag<View>("history_button") != null) return
        val button = TextView(activity).apply {
            tag = "history_button"
            text = "HISTORY"
            textSize = 9f
            setTextColor(0xFFB05CFF.toInt())
            gravity = Gravity.CENTER
            setBackgroundColor(0x221B2230)
            setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
            elevation = dp(activity, 4).toFloat()
            setOnClickListener { activity.startActivity(android.content.Intent(activity, HistoryActivity::class.java)) }
        }
        decor.addView(button, FrameLayout.LayoutParams(dp(activity, 78), dp(activity, 34), Gravity.TOP or Gravity.END).apply { topMargin = dp(activity, 8); rightMargin = dp(activity, 10) })
    }

    private fun saveSnapshot(activity: MainActivityV2) {
        runCatching {
            if (!readBoolean(activity, "tracking") && !wasTracking) return
            val c = activity.javaClass
            val distance = c.getDeclaredField("distanceM").apply { isAccessible = true }.getFloat(activity) / 1000f
            val max = c.getDeclaredField("maxKmh").apply { isAccessible = true }.getFloat(activity)
            val sum = c.getDeclaredField("averageSum").apply { isAccessible = true }.getDouble(activity)
            val samples = c.getDeclaredField("averageSamples").apply { isAccessible = true }.getInt(activity)
            val moving = c.getDeclaredField("movingMs").apply { isAccessible = true }.getLong(activity)
            val avg = if (samples > 0) (sum / samples).toFloat() else 0f
            val key = "%.3f|%.2f|%.2f|%d".format(java.util.Locale.US, distance, max, avg, moving)
            if (key == lastSavedKey) return
            lastSavedKey = key
            HistoryStore.save(activity, distance, max, avg, moving)
        }
    }

    private fun readBoolean(activity: MainActivityV2, field: String): Boolean = runCatching {
        activity.javaClass.getDeclaredField(field).apply { isAccessible = true }.getBoolean(activity)
    }.getOrDefault(false)

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()
}
