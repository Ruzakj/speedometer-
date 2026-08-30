package com.ruzakj.speedometer

import android.location.Location
import android.os.Build
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Precision GNSS speed pipeline.
 *
 * Design goals:
 * 1) Prefer Android's GNSS Doppler speed (Location.speed) over coordinate deltas.
 * 2) Use the platform-reported speed uncertainty as the Kalman measurement variance.
 * 3) Reject stale / low-quality / physically implausible samples before they reach the UI.
 * 4) Integrate accepted Doppler speed for trip distance so horizontal GPS jitter cannot
 *    manufacture distance while the motorcycle is stationary.
 * 5) Keep latency low by making the filter trust high-quality GNSS fixes aggressively.
 */
class PrecisionGpsProcessor(private val maxSpeedKmh: Float = 110f) {

    data class Fix(
        val accepted: Boolean,
        val speedKmh: Float,
        val horizontalAccuracyM: Float,
        val speedAccuracyMps: Float,
        val quality: Float,
        val distanceDeltaM: Float,
        val dtSeconds: Float,
        val reason: String? = null
    )

    private var lastLocation: Location? = null
    private var lastElapsedNs = 0L

    // Scalar Kalman state for speed in m/s.
    private var initialized = false
    private var x = 0f
    private var p = 4f

    private var stationarySinceNs = 0L

    fun reset() {
        lastLocation = null
        lastElapsedNs = 0L
        initialized = false
        x = 0f
        p = 4f
        stationarySinceNs = 0L
    }

    fun process(location: Location): Fix {
        val hAcc = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
        val sAcc = if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) {
            location.speedAccuracyMetersPerSecond
        } else Float.NaN

        val now = location.elapsedRealtimeNanos
        val dt = if (lastElapsedNs > 0L && now > lastElapsedNs) {
            ((now - lastElapsedNs) / 1_000_000_000.0).toFloat()
        } else 0f

        // Stale / pathological fixes never update the filter.
        if (hAcc > 40f) return rejected(hAcc, sAcc, dt, "horizontal accuracy")
        if (dt > 0f && dt > 4f) {
            // Re-acquire rather than bridging a long GNSS outage with a fake acceleration.
            initialized = false
            p = 4f
        }

        val previous = lastLocation
        val coordinateSpeed = if (previous != null && dt in 0.15f..3.0f) {
            previous.distanceTo(location) / dt
        } else Float.NaN

        // Doppler speed is the primary measurement. Coordinate-derived speed is fallback only.
        val hasDoppler = location.hasSpeed() && location.speed >= 0f
        val measured = when {
            hasDoppler -> location.speed
            coordinateSpeed.isFinite() && hAcc <= 8f && previous != null && previous.accuracy <= 8f -> coordinateSpeed
            else -> Float.NaN
        }

        if (!measured.isFinite()) {
            remember(location, now)
            return rejected(hAcc, sAcc, dt, "no reliable speed")
        }

        // Hard speed-uncertainty rejection. ±3.5 m/s is already ±12.6 km/h.
        if (sAcc.isFinite() && sAcc > 3.5f) {
            remember(location, now)
            return rejected(hAcc, sAcc, dt, "speed uncertainty")
        }

        // Reject impossible coordinate teleportation independently of Doppler speed.
        if (previous != null && dt in 0.15f..2.5f) {
            val jump = previous.distanceTo(location)
            val derived = jump / dt
            if (jump > 120f || derived > 75f) {
                remember(location, now)
                return rejected(hAcc, sAcc, dt, "position jump")
            }
        }

        val measurementSigma = when {
            sAcc.isFinite() -> max(0.18f, sAcc)
            hasDoppler && hAcc <= 5f -> 0.45f
            hasDoppler && hAcc <= 10f -> 0.75f
            hasDoppler -> 1.25f
            else -> max(1.5f, hAcc / max(dt, 0.25f) * 0.30f)
        }

        // Prediction: motorcycle acceleration can change rapidly; process noise grows with dt.
        if (!initialized) {
            x = measured
            p = measurementSigma * measurementSigma
            initialized = true
        } else {
            val safeDt = dt.coerceIn(0.05f, 2f)
            val accelSigma = 2.8f // m/s², keeps strong acceleration/braking responsive.
            p += accelSigma * accelSigma * safeDt * safeDt

            val innovation = measured - x
            val innovationSigma = sqrt(p + measurementSigma * measurementSigma)
            val gate = max(3.8f * innovationSigma, 4.5f)
            if (abs(innovation) > gate && hAcc > 8f) {
                remember(location, now)
                return rejected(hAcc, sAcc, dt, "speed outlier")
            }

            val r = measurementSigma * measurementSigma
            val k = p / (p + r)
            x += k * innovation
            p = (1f - k) * p
        }

        x = x.coerceIn(0f, maxSpeedKmh / 3.6f)

        // Stationary lock. This removes the common 0.5–2 km/h GNSS crawl at a red light.
        val rawNearZero = measured < 0.55f
        val stationaryConfidence = hAcc <= 12f && (!sAcc.isFinite() || sAcc <= 1.2f)
        if (rawNearZero && stationaryConfidence) {
            if (stationarySinceNs == 0L) stationarySinceNs = now
            if (now - stationarySinceNs >= 1_500_000_000L && x < 1.1f) x = 0f
        } else {
            stationarySinceNs = 0L
        }

        val quality = quality(hAcc, sAcc)

        // Trip distance comes from time-integrated GNSS speed, not coordinate zig-zag.
        // Trapezoidal integration is unnecessary here because the Kalman state is already
        // a continuous estimate; clamp dt so an outage cannot create phantom distance.
        val distanceDelta = if (dt in 0.05f..2.0f && quality >= 0.30f && x >= 0.8f) {
            x * dt
        } else 0f

        remember(location, now)
        return Fix(
            accepted = true,
            speedKmh = x * 3.6f,
            horizontalAccuracyM = hAcc,
            speedAccuracyMps = sAcc,
            quality = quality,
            distanceDeltaM = distanceDelta,
            dtSeconds = dt
        )
    }

    private fun quality(hAcc: Float, sAcc: Float): Float {
        val position = (1f - (hAcc - 3f) / 27f).coerceIn(0f, 1f)
        val speed = if (sAcc.isFinite()) (1f - sAcc / 3.5f).coerceIn(0f, 1f) else 0.55f
        // Speed uncertainty matters more for a speedometer than horizontal position accuracy.
        return (position * 0.35f + speed * 0.65f).coerceIn(0f, 1f)
    }

    private fun rejected(hAcc: Float, sAcc: Float, dt: Float, reason: String) = Fix(
        accepted = false,
        speedKmh = x * 3.6f,
        horizontalAccuracyM = hAcc,
        speedAccuracyMps = sAcc,
        quality = quality(hAcc, sAcc),
        distanceDeltaM = 0f,
        dtSeconds = dt,
        reason = reason
    )

    private fun remember(location: Location, now: Long) {
        lastLocation = Location(location)
        lastElapsedNs = now
    }
}
