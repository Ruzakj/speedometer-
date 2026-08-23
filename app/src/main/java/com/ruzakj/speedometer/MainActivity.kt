package com.ruzakj.speedometer

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max

class MainActivity : Activity(), LocationListener {
    private lateinit var speedView: SpeedView
    private lateinit var maxText: TextView
    private lateinit var locationManager: LocationManager
    private var maxSpeed = 0f
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF090B10.toInt())
        }

        val title = TextView(this).apply {
            text = "SPEEDOMETER"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(-1, 60))

        speedView = SpeedView(this)
        root.addView(speedView, LinearLayout.LayoutParams(-1, 0, 1f))

        maxText = TextView(this).apply {
            text = "MAX 0.0 km/h"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFB9C0CC.toInt())
        }
        root.addView(maxText, LinearLayout.LayoutParams(-1, 56))

        val button = Button(this).apply {
            text = "START"
            setOnClickListener {
                if (running) stopTracking() else startTracking()
                text = if (running) "STOP" else "START"
            }
        }
        root.addView(button, LinearLayout.LayoutParams(-1, 64))
        setContentView(root)
    }

    private fun startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 100)
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Please enable GPS", Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this, Looper.getMainLooper())
    }

    private fun stopTracking() {
        running = false
        locationManager.removeUpdates(this)
        speedView.setSpeed(0f)
    }

    override fun onLocationChanged(location: Location) {
        val speed = if (location.hasSpeed()) max(0f, location.speed * 3.6f) else 0f
        if (speed > maxSpeed) {
            maxSpeed = speed
            maxText.text = String.format("MAX %.1f km/h", maxSpeed)
        }
        speedView.setSpeed(speed)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startTracking()
    }

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        super.onDestroy()
    }

    private class SpeedView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var speed = 0f
        private val maxGauge = 180f

        fun setSpeed(value: Float) {
            speed = value.coerceIn(0f, maxGauge)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = (minOf(width, height) * 0.39f)
            val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 18f
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = 0xFF252A33.toInt()
            canvas.drawArc(rect, 135f, 270f, false, paint)

            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawArc(rect, 135f, 270f * (speed / maxGauge), false, paint)

            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = radius * 0.55f
            canvas.drawText(String.format("%.1f", speed), cx, cy + radius * 0.08f, paint)

            paint.color = 0xFF9EA6B3.toInt()
            paint.textSize = radius * 0.13f
            canvas.drawText("km/h", cx, cy + radius * 0.29f, paint)

            paint.textSize = radius * 0.10f
            paint.color = 0xFF737B88.toInt()
            canvas.drawText("GPS SPEED", cx, cy - radius * 0.48f, paint)
        }
    }
}
