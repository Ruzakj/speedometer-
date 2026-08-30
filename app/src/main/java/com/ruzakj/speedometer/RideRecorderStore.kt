package com.ruzakj.speedometer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs

object RideRecorderStore {
    private const val PREFS = "ride_recorder_history"
    private const val KEY = "rides"

    data class RideSummary(
        val id: Long,
        val startedAt: Long,
        val endedAt: Long,
        val distanceKm: Float,
        val maxKmh: Float,
        val avgKmh: Float,
        val movingMs: Long,
        val zeroTo60: Float,
        val zeroTo100: Float,
        val maxLeanDeg: Float,
        val maxG: Float,
        val routePath: String
    )

    data class RoutePoint(
        val timestamp: Long,
        val lat: Double,
        val lon: Double,
        val altitude: Double,
        val speedKmh: Float,
        val leanDeg: Float,
        val gForce: Float,
        val gradientPct: Float
    )

    fun saveSummary(context: Context, ride: RideSummary) {
        if (ride.distanceKm < 0.01f && ride.maxKmh < 1f && ride.movingMs < 10_000L) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val arr = JSONArray()
        arr.put(toJson(ride))
        for (i in 0 until old.length().coerceAtMost(99)) arr.put(old.optJSONObject(i))
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun load(context: Context): List<RideSummary> {
        val arr = runCatching { JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    RideSummary(
                        o.optLong("id"), o.optLong("startedAt"), o.optLong("endedAt"),
                        o.optDouble("distanceKm").toFloat(), o.optDouble("maxKmh").toFloat(),
                        o.optDouble("avgKmh").toFloat(), o.optLong("movingMs"),
                        o.optDouble("zeroTo60").toFloat(), o.optDouble("zeroTo100").toFloat(),
                        o.optDouble("maxLeanDeg").toFloat(), o.optDouble("maxG").toFloat(),
                        o.optString("routePath")
                    )
                )
            }
        }
    }

    fun find(context: Context, id: Long): RideSummary? = load(context).firstOrNull { it.id == id }

    fun route(ride: RideSummary): List<RoutePoint> {
        val file = File(ride.routePath)
        if (!file.exists()) return emptyList()
        return file.useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val p = line.split(',')
                if (p.size < 8) null else runCatching {
                    RoutePoint(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toFloat(), p[5].toFloat(), p[6].toFloat(), p[7].toFloat())
                }.getOrNull()
            }.toList()
        }
    }

    fun csvText(ride: RideSummary): String = runCatching { File(ride.routePath).readText() }.getOrDefault("")

    fun gpxText(ride: RideSummary): String {
        val points = route(ride)
        val body = points.joinToString("\n") { p ->
            String.format(Locale.US, "    <trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.2f</ele><time>%d</time><extensions><speedKmh>%.2f</speedKmh><leanDeg>%.2f</leanDeg><gForce>%.3f</gForce><gradientPct>%.2f</gradientPct></extensions></trkpt>", p.lat, p.lon, p.altitude, p.timestamp, p.speedKmh, p.leanDeg, p.gForce, p.gradientPct)
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Speedometer\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n  <trk><name>Ride ${ride.id}</name><trkseg>\n$body\n  </trkseg></trk>\n</gpx>\n"
    }

    fun maxAbsColumn(file: File, index: Int): Float {
        if (!file.exists()) return 0f
        var maxV = 0f
        file.useLines { lines ->
            lines.drop(1).forEach { line ->
                val p = line.split(',')
                if (p.size > index) {
                    val v = p[index].toFloatOrNull() ?: 0f
                    maxV = kotlin.math.max(maxV, abs(v))
                }
            }
        }
        return maxV
    }

    private fun toJson(r: RideSummary) = JSONObject().apply {
        put("id", r.id); put("startedAt", r.startedAt); put("endedAt", r.endedAt)
        put("distanceKm", r.distanceKm); put("maxKmh", r.maxKmh); put("avgKmh", r.avgKmh); put("movingMs", r.movingMs)
        put("zeroTo60", r.zeroTo60); put("zeroTo100", r.zeroTo100); put("maxLeanDeg", r.maxLeanDeg); put("maxG", r.maxG); put("routePath", r.routePath)
    }
}
