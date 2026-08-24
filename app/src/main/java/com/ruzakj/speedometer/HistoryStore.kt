package com.ruzakj.speedometer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HistoryStore {
    private const val PREFS = "trip_history"
    private const val KEY = "sessions"
    private const val MAX_ITEMS = 50

    data class Trip(val timestamp: Long, val distanceKm: Float, val maxKmh: Float, val avgKmh: Float, val movingMs: Long)

    fun save(context: Context, distanceKm: Float, maxKmh: Float, avgKmh: Float, movingMs: Long) {
        if (distanceKm < 0.01f && maxKmh < 1f && movingMs < 10_000L) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val arr = JSONArray()
        arr.put(JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("distance", distanceKm)
            put("max", maxKmh)
            put("avg", avgKmh)
            put("moving", movingMs)
        })
        for (i in 0 until minOf(old.length(), MAX_ITEMS - 1)) arr.put(old.getJSONObject(i))
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun load(context: Context): List<Trip> {
        val arr = runCatching { JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(Trip(o.optLong("timestamp"), o.optDouble("distance").toFloat(), o.optDouble("max").toFloat(), o.optDouble("avg").toFloat(), o.optLong("moving")))
            }
        }
    }

    fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    fun date(ts: Long) = SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault()).format(Date(ts))
    fun duration(ms: Long): String { val s = ms / 1000; return if (s >= 3600) String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) else String.format(Locale.US, "%02d:%02d", s / 60, s % 60) }
}
