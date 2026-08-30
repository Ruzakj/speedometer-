package com.ruzakj.speedometer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class RideRecorderService : Service(), LocationListener, SensorEventListener {
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private val gps = PrecisionGpsProcessor(220f)
    private var rideId = 0L
    private var startedAt = 0L
    private var lastLocation: Location? = null
    private var distanceM = 0f
    private var maxKmh = 0f
    private var speedSum = 0.0
    private var speedSamples = 0
    private var movingMs = 0L
    private var leanDeg = 0f
    private var gForce = 0f
    private var altitudeM = 0.0
    private var gradientPct = 0f
    private var accelStartNs = 0L
    private var zeroTo60 = 0f
    private var zeroTo100 = 0f
    private var armed = true
    private var lastNotificationAt = 0L
    private lateinit var routeFile: File

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finishRide()
            return START_NOT_STICKY
        }
        if (rideId == 0L) startRide()
        return START_STICKY
    }

    private fun startRide() {
        rideId = System.currentTimeMillis()
        startedAt = rideId
        val dir = File(filesDir, "rides").apply { mkdirs() }
        routeFile = File(dir, "$rideId.csv")
        routeFile.writeText("timestamp,latitude,longitude,altitude_m,speed_kmh,lean_deg,g_force,gradient_pct\n")
        startForeground(NOTIFICATION_ID, notification("Acquiring precision GPS…"))
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            runCatching { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250L, 0f, this) }
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onLocationChanged(location: Location) {
        val fix = gps.process(location)
        if (!fix.accepted) return
        val speed = fix.speedKmh
        maxKmh = max(maxKmh, speed)
        if (speed >= 2f) {
            distanceM += fix.distanceDeltaM
            movingMs += (fix.dtSeconds * 1000f).toLong().coerceAtLeast(0L)
            speedSum += speed
            speedSamples++
        }

        if (location.hasAltitude()) altitudeM = location.altitude
        val prev = lastLocation
        if (prev != null && location.hasAltitude() && prev.hasAltitude()) {
            val horizontal = prev.distanceTo(location)
            if (horizontal >= 3f) {
                val raw = (((location.altitude - prev.altitude) / horizontal) * 100.0).toFloat().coerceIn(-35f, 35f)
                gradientPct += (raw - gradientPct) * 0.25f
            }
        }
        lastLocation = Location(location)

        updateAccelerationTimers(speed, location.elapsedRealtimeNanos)
        appendPoint(location, speed)
        publishLive(speed, fix)

        val now = System.currentTimeMillis()
        if (now - lastNotificationAt >= 1000L) {
            lastNotificationAt = now
            val text = String.format(Locale.US, "%.1f km/h • %.2f km • %.0f m", speed, distanceM / 1000f, altitudeM)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text))
        }
    }

    private fun updateAccelerationTimers(speed: Float, nowNs: Long) {
        if (speed < 2f) {
            armed = true
            accelStartNs = 0L
            zeroTo60 = 0f
            zeroTo100 = 0f
            return
        }
        if (armed && speed >= 3f) {
            armed = false
            accelStartNs = nowNs
        }
        if (accelStartNs == 0L) return
        val elapsed = (nowNs - accelStartNs) / 1_000_000_000f
        if (zeroTo60 == 0f && speed >= 60f) zeroTo60 = elapsed
        if (zeroTo100 == 0f && speed >= 100f) zeroTo100 = elapsed
    }

    private fun appendPoint(location: Location, speed: Float) {
        val line = String.format(Locale.US, "%d,%.7f,%.7f,%.2f,%.2f,%.2f,%.3f,%.2f\n", System.currentTimeMillis(), location.latitude, location.longitude, altitudeM, speed, leanDeg, gForce, gradientPct)
        runCatching { routeFile.appendText(line) }
    }

    private fun publishLive(speed: Float, fix: PrecisionGpsProcessor.Fix) {
        getSharedPreferences("ride_live", MODE_PRIVATE).edit()
            .putLong("ride_id", rideId)
            .putFloat("speed", speed)
            .putFloat("lean", leanDeg)
            .putFloat("g_force", gForce)
            .putFloat("altitude", altitudeM.toFloat())
            .putFloat("gradient", gradientPct)
            .putFloat("zero60", zeroTo60)
            .putFloat("zero100", zeroTo100)
            .putFloat("gps_quality", fix.quality)
            .putFloat("distance_km", distanceM / 1000f)
            .apply()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotation = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                leanDeg += (roll.coerceIn(-90f, 90f) - leanDeg) * 0.18f
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val magnitude = kotlin.math.sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2]) / SensorManager.GRAVITY_EARTH
                gForce += (magnitude - gForce) * 0.22f
                if (abs(gForce) < 0.01f) gForce = 0f
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun finishRide() {
        runCatching { locationManager.removeUpdates(this) }
        sensorManager.unregisterListener(this)
        if (rideId != 0L) {
            RideRecorderStore.saveSummary(
                this,
                RideRecorderStore.RideSummary(
                    id = rideId,
                    startedAt = startedAt,
                    endedAt = System.currentTimeMillis(),
                    distanceKm = distanceM / 1000f,
                    maxKmh = maxKmh,
                    avgKmh = if (speedSamples > 0) (speedSum / speedSamples).toFloat() else 0f,
                    movingMs = movingMs,
                    zeroTo60 = zeroTo60,
                    zeroTo100 = zeroTo100,
                    maxLeanDeg = RideRecorderStore.maxAbsColumn(routeFile, 5),
                    maxG = RideRecorderStore.maxAbsColumn(routeFile, 6),
                    routePath = routeFile.absolutePath
                )
            )
        }
        getSharedPreferences("ride_live", MODE_PRIVATE).edit().clear().apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(this) }
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ride tracking", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification {
        val open = Intent(this, MainActivityV2::class.java)
        val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Speedometer • Background Tracking")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        const val ACTION_START = "com.ruzakj.speedometer.START_RIDE"
        const val ACTION_STOP = "com.ruzakj.speedometer.STOP_RIDE"
        private const val CHANNEL_ID = "ride_tracking"
        private const val NOTIFICATION_ID = 4206
    }
}
