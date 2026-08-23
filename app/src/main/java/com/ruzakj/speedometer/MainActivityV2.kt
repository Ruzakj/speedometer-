package com.ruzakj.speedometer

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.roundToInt

class MainActivityV2 : Activity(), LocationListener {
    private lateinit var dash: Dashboard
    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var tracking = false
    private var lastLocation: Location? = null
    private var lastElapsedNs = 0L
    private var speedKmh = 0f
    private var maxKmh = 0f
    private var distanceM = 0f
    private var movingMs = 0L
    private var averageSum = 0.0
    private var averageSamples = 0
    private var accuracyM = 999f
    private var speedAccuracyMps = Float.MAX_VALUE
    private var introAnimating = false
    private var introSpeed = 0f
    private var introAnimator: ValueAnimator? = null

    private val refresh = object : Runnable {
        override fun run() { dash.invalidate(); handler.postDelayed(this, 250L) }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        if (Build.VERSION.SDK_INT >= 29) { window.isStatusBarContrastEnforced = false; window.isNavigationBarContrastEnforced = false }
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        dash = Dashboard(this)
        dash.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(dp(16), bars.top + dp(6), dp(16), bars.bottom + dp(8))
            } else view.setPadding(dp(16), dp(24), dp(16), dp(16))
            insets
        }
        setContentView(dash)
        handler.post(refresh)
        playStartupSweep()
    }

    private fun playStartupSweep() {
        introAnimator?.cancel(); introAnimating = true; introSpeed = 0f
        introAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1450L
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                introSpeed = if (t < 0.45f) MAX_SPEED * easeOutCubic(t / 0.45f) else MAX_SPEED * (1f - easeInOutCubic((t - 0.45f) / 0.55f))
                dash.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { introAnimating = false; introSpeed = 0f; dash.invalidate() }
            })
            start()
        }
    }
    private fun easeOutCubic(x: Float): Float { val p=x.coerceIn(0f,1f); return 1f-(1f-p)*(1f-p)*(1f-p) }
    private fun easeInOutCubic(x: Float): Float { val p=x.coerceIn(0f,1f); return if(p<.5f) 4f*p*p*p else 1f-((-2f*p+2f)*(-2f*p+2f)*(-2f*p+2f))/2f }

    private fun startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION); return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) { dash.message="GPS OFF  •  ENABLE LOCATION"; dash.invalidate(); return }
        tracking=true; dash.message="GPS ACTIVE  •  OFFLINE"
        try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,500L,0f,this,Looper.getMainLooper()) }
        catch (_:SecurityException){ tracking=false; dash.message="LOCATION PERMISSION REQUIRED" }
        dash.invalidate()
    }
    private fun stopTracking(){ tracking=false; locationManager.removeUpdates(this); dash.message="GPS PAUSED  •  OFFLINE"; dash.invalidate() }
    private fun resetTrip(){ maxKmh=0f;distanceM=0f;movingMs=0L;averageSum=0.0;averageSamples=0;lastLocation=null;lastElapsedNs=0L;speedKmh=0f;accuracyM=999f;speedAccuracyMps=Float.MAX_VALUE;dash.message=if(tracking)"GPS ACTIVE  •  OFFLINE" else "READY  •  OFFLINE";dash.invalidate() }

    override fun onLocationChanged(location: Location) {
        accuracyM=if(location.hasAccuracy()) location.accuracy else 999f
        speedAccuracyMps=if(Build.VERSION.SDK_INT>=26 && location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else Float.MAX_VALUE
        if(accuracyM>35f){dash.message=String.format(Locale.US,"GPS WEAK  •  ±%.0f m",accuracyM);dash.invalidate();return}
        val nowNs=location.elapsedRealtimeNanos; val previous=lastLocation
        val dt=if(previous!=null && nowNs>lastElapsedNs)(nowNs-lastElapsedNs)/1_000_000_000f else 0f
        val rawMps=if(location.hasSpeed())max(0f,location.speed) else 0f
        if(previous!=null && dt>0f){
            val jumpM=previous.distanceTo(location);val derivedMps=jumpM/dt
            if(jumpM>120f && dt<2.5f)return
            if(derivedMps>69.4f && rawMps<55f)return
            distanceM+=jumpM
            if(rawMps>.5f)movingMs+=(dt*1000f).toLong()
        }
        val quality=gpsQualityFactor();val targetKmh=rawMps*3.6f;val alpha=.30f+.50f*quality
        speedKmh+=(targetKmh-speedKmh)*alpha
        if(targetKmh<1.5f && speedKmh<1f)speedKmh=0f
        lastLocation=Location(location);lastElapsedNs=nowNs
        maxKmh=max(maxKmh,min(speedKmh,MAX_SPEED))
        if(quality>.35f){averageSamples++;averageSum+=min(speedKmh,MAX_SPEED)}
        dash.message="GPS ACTIVE  •  OFFLINE";dash.invalidate()
    }
    private fun gpsQualityFactor():Float{val pos=(1f-(accuracyM-3f)/27f).coerceIn(0f,1f);val vel=if(speedAccuracyMps==Float.MAX_VALUE).55f else (1f-speedAccuracyMps/5f).coerceIn(0f,1f);return(pos*.7f+vel*.3f).coerceIn(0f,1f)}
    private fun gpsLabel():String=when{accuracyM<=5f->"EXCELLENT";accuracyM<=10f->"GOOD";accuracyM<=20f->"FAIR";accuracyM<900f->"WEAK";else->"SEARCHING"}
    private fun averageKmh():Float=if(averageSamples==0)0f else(averageSum/averageSamples).toFloat()
    private fun movingTime():String{val s=movingMs/1000L;return if(s>=3600)String.format(Locale.US,"%02d:%02d:%02d",s/3600,(s%3600)/60,s%60) else String.format(Locale.US,"%02d:%02d",s/60,s%60)}
    private fun enterPip(){if(Build.VERSION.SDK_INT<26||isInPictureInPictureMode)return;val params=PictureInPictureParams.Builder().setAspectRatio(Rational(9,16)).apply{if(Build.VERSION.SDK_INT>=31)setSeamlessResizeEnabled(true)}.build();try{enterPictureInPictureMode(params)}catch(_:IllegalStateException){dash.message="PIP UNAVAILABLE";dash.invalidate()}}
    override fun onPictureInPictureModeChanged(inPip:Boolean){super.onPictureInPictureModeChanged(inPip);dash.setPipMode(inPip);dash.invalidate()}
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);if(code==REQUEST_LOCATION&&results.isNotEmpty()&&results[0]==PackageManager.PERMISSION_GRANTED)startTracking()else{dash.message="PRECISE GPS PERMISSION REQUIRED";dash.invalidate()}}
    override fun onProviderDisabled(provider:String){if(provider==LocationManager.GPS_PROVIDER){dash.message="GPS OFF  •  ENABLE LOCATION";dash.invalidate()}}
    override fun onProviderEnabled(provider:String){if(provider==LocationManager.GPS_PROVIDER){dash.message="GPS READY  •  OFFLINE";dash.invalidate()}}
    override fun onDestroy(){introAnimator?.cancel();handler.removeCallbacksAndMessages(null);locationManager.removeUpdates(this);super.onDestroy()}

    private inner class Dashboard(context:Context):View(context){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
        private var startRect=RectF();private var resetRect=RectF();private var pipRect=RectF();private var pipMode=false
        var message="READY  •  OFFLINE"
        init{setLayerType(View.LAYER_TYPE_SOFTWARE,null)}
        fun setPipMode(v:Boolean){pipMode=v}
        override fun onDraw(c:Canvas){super.onDraw(c);c.drawColor(BG);if(pipMode)drawPip(c)else drawFull(c)}
        private fun shownSpeed():Float=if(introAnimating)introSpeed else speedKmh.coerceAtMost(MAX_SPEED)

        private fun drawFull(c:Canvas){
            val w=width.toFloat();val h=height.toFloat();val l=df(16);val r=w-l;val cw=r-l
            text(c,"MOTO",l,df(25),20f,TEXT,true,Paint.Align.LEFT);text(c,"SPEED",l+df(61),df(25),20f,CYAN,true,Paint.Align.LEFT)
            text(c,if(tracking)"● GPS LIVE" else "○ GPS READY",l,df(46),9f,if(tracking)GREEN:MUTED,true,Paint.Align.LEFT)
            drawMotorAccent(c,r-df(54),df(26),df(82),df(34));pill(c,r-df(84),df(52),df(84),df(25),"OFFLINE",CYAN)
            val top=df(82);val bottom=min(h-df(218),top+df(395));val panel=RectF(l,top,r,bottom);premiumCard(c,panel)
            text(c,"RIDE / SPORT",l+df(18),top+df(24),9f,PURPLE,true,Paint.Align.LEFT);text(c,gpsLabel(),r-df(18),top+df(24),9f,gaugeColor(),true,Paint.Align.RIGHT)
            val cx=w/2f;val cy=top+(bottom-top)*.60f;val radius=min(cw*.39f,(bottom-top)*.38f);val arc=RectF(cx-radius,cy-radius,cx+radius,cy+radius)
            paint.style=Paint.Style.STROKE;paint.strokeCap=Paint.Cap.ROUND;paint.strokeWidth=df(12);paint.color=TRACK;c.drawArc(arc,138f,264f,false,paint)
            val fraction=(shownSpeed()/MAX_SPEED).coerceIn(0f,1f);paint.shader=LinearGradient(arc.left,arc.bottom,arc.right,arc.top,floatArrayOf(CYAN,PURPLE,PINK),null,Shader.TileMode.CLAMP);c.drawArc(arc,138f,264f*fraction,false,paint);paint.shader=null
            paint.strokeWidth=df(3)
            for(i in 0..11){val a=Math.toRadians(138.0+i*264.0/11.0);val rr1=radius-df(19);val rr2=radius-df(7);paint.color=if(i/11f<=fraction)CYAN else GRID;c.drawLine(cx+cos(a).toFloat()*rr1,cy+sin(a).toFloat()*rr1,cx+cos(a).toFloat()*rr2,cy+sin(a).toFloat()*rr2,paint)}
            for(i in 0..11){val value=i*10;val a=Math.toRadians(138.0+i*264.0/11.0);val rr=radius-df(34);text(c,value.toString(),cx+cos(a).toFloat()*rr,cy+sin(a).toFloat()*rr+df(3),8f,if(value%20==0)TEXT else MUTED,true,Paint.Align.CENTER)}
            text(c,"GPS SPEED",cx,cy-df(48),10f,MUTED,true,Paint.Align.CENTER);text(c,String.format(Locale.US,"%.1f",shownSpeed()),cx,cy+df(18),58f,TEXT,true,Paint.Align.CENTER);text(c,"km/h",cx,cy+df(46),14f,MUTED,false,Paint.Align.CENTER)
            text(c,if(introAnimating)"SYSTEM CHECK • MAX 110" else if(accuracyM<900f)String.format(Locale.US,"GPS ±%.0f m",accuracyM) else "WAITING FOR GPS",cx,cy+df(70),9f,gaugeColor(),true,Paint.Align.CENTER)
            val statTop=bottom+df(12);val gap=df(8);val cardW=(cw-gap)/2f;val cardH=df(64)
            statCard(c,RectF(l,statTop,l+cardW,statTop+cardH),"AVG",String.format(Locale.US,"%.1f",averageKmh()),"km/h",CYAN);statCard(c,RectF(l+cardW+gap,statTop,r,statTop+cardH),"MAX",String.format(Locale.US,"%.1f",maxKmh),"km/h",PINK)
            statCard(c,RectF(l,statTop+cardH+gap,l+cardW,statTop+cardH*2+gap),"TRIP",String.format(Locale.US,"%.2f",distanceM/1000f),"km",PURPLE);statCard(c,RectF(l+cardW+gap,statTop+cardH+gap,r,statTop+cardH*2+gap),"MOVING",movingTime(),"",ORANGE)
            val by=statTop+cardH*2+gap*2;val bw=(cw-df(16))/3f;startRect=RectF(l,by,l+bw,by+df(46));resetRect=RectF(l+bw+df(8),by,l+2*bw+df(8),by+df(46));pipRect=RectF(l+2*bw+df(16),by,r,by+df(46))
            button(c,startRect,if(tracking)"STOP • GPS" else "START • GPS",if(tracking)PINK:CYAN);button(c,resetRect,"RESET",MUTED);button(c,pipRect,"PIP",PURPLE)
            text(c,message,r,h-df(12),8f,MUTED,true,Paint.Align.RIGHT)
        }

        private fun drawPip(c:Canvas){val w=width.toFloat();val h=height.toFloat();val cx=w/2f;val cy=h*.46f;text(c,"MOTO SPEED",cx,h*.14f,12f,CYAN,true,Paint.Align.CENTER);text(c,String.format(Locale.US,"%.1f",speedKmh.coerceAtMost(MAX_SPEED)),cx,cy,64f,TEXT,true,Paint.Align.CENTER);text(c,"km/h  /  MAX 110",cx,cy+df(30),13f,MUTED,false,Paint.Align.CENTER);text(c,if(accuracyM<900f)String.format(Locale.US,"±%.0f m  •  %s",accuracyM,gpsLabel()) else "SEARCHING GPS",cx,cy+df(54),9f,gaugeColor(),true,Paint.Align.CENTER);text(c,if(tracking)"● LIVE • OFFLINE" else "PAUSED • OFFLINE",cx,h-df(16),9f,if(tracking)GREEN:MUTED,true,Paint.Align.CENTER)}
        private fun premiumCard(c:Canvas,r:RectF){fill.style=Paint.Style.FILL;fill.color=CARD;fill.setShadowLayer(df(18),0f,df(8),0x70000000);c.drawRoundRect(r,df(24),df(24),fill);fill.clearShadowLayer();paint.style=Paint.Style.STROKE;paint.strokeWidth=df(1);paint.color=0x553B4A65;c.drawRoundRect(r,df(24),df(24),paint)}
        private fun statCard(c:Canvas,r:RectF,label:String,value:String,unit:String,accent:Int){fill.style=Paint.Style.FILL;fill.color=CARD2;c.drawRoundRect(r,df(16),df(16),fill);fill.color=accent;c.drawRoundRect(RectF(r.left,r.top,r.left+df(4),r.bottom),df(2),df(2),fill);text(c,label,r.left+df(14),r.top+df(20),8f,MUTED,true,Paint.Align.LEFT);text(c,value,r.left+df(14),r.top+df(43),18f,TEXT,true,Paint.Align.LEFT);if(unit.isNotEmpty())text(c,unit,r.right-df(12),r.top+df(43),8f,accent,true,Paint.Align.RIGHT)}
        private fun button(c:Canvas,r:RectF,label:String,accent:Int){fill.style=Paint.Style.FILL;fill.color=0xFF151A25;c.drawRoundRect(r,df(14),df(14),fill);paint.style=Paint.Style.STROKE;paint.strokeWidth=df(1.5f);paint.color=accent;c.drawRoundRect(r,df(14),df(14),paint);text(c,label,r.centerX(),r.centerY()+df(3),8f,accent,true,Paint.Align.CENTER)}
        private fun pill(c:Canvas,x:Float,y:Float,w:Float,h:Float,label:String,accent:Int){fill.style=Paint.Style.FILL;fill.color=0x331EE6FF;c.drawRoundRect(RectF(x,y,x+w,y+h),h/2,h/2,fill);text(c,label,x+w/2,y+h*.67f,8f,accent,true,Paint.Align.CENTER)}
        private fun drawMotorAccent(c:Canvas,x:Float,y:Float,w:Float,h:Float){paint.style=Paint.Style.STROKE;paint.strokeWidth=df(2);paint.strokeCap=Paint.Cap.ROUND;paint.color=CYAN;val cy=y+h*.68f;c.drawCircle(x+w*.2f,cy,df(7),paint);c.drawCircle(x+w*.78f,cy,df(7),paint);val p=Path();p.moveTo(x+w*.2f,cy-df(7));p.lineTo(x+w*.35f,y+h*.38f);p.lineTo(x+w*.55f,y+h*.38f);p.lineTo(x+w*.78f,cy-df(7));p.moveTo(x+w*.35f,y+h*.38f);p.lineTo(x+w*.45f,cy);p.lineTo(x+w*.62f,cy);p.moveTo(x+w*.55f,y+h*.38f);p.lineTo(x+w*.66f,y+h*.18f);c.drawPath(p,paint)}
        private fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int,bold:Boolean,align:Paint.Align){paint.style=Paint.Style.FILL;paint.shader=null;paint.color=color;paint.textSize=df(size);paint.typeface=if(bold)Typeface.create("sans-serif",Typeface.BOLD) else Typeface.create("sans-serif",Typeface.NORMAL);paint.textAlign=align;c.drawText(s,x,y,paint)}
        private fun gaugeColor():Int=when{accuracyM<=5f->CYAN;accuracyM<=10f->GREEN;accuracyM<=20f->ORANGE;else->PINK}
        override fun onTouchEvent(e:MotionEvent):Boolean{if(e.action==MotionEvent.ACTION_UP&&!pipMode){when{startRect.contains(e.x,e.y)->if(tracking)stopTracking()else startTracking();resetRect.contains(e.x,e.y)->resetTrip();pipRect.contains(e.x,e.y)->enterPip()};performClick();return true};return true}
        override fun performClick():Boolean{super.performClick();return true}
    }
    private fun dp(v:Int):Int=(v*resources.displayMetrics.density).roundToInt()
    private fun df(v:Float):Float=v*resources.displayMetrics.density
    companion object{const val REQUEST_LOCATION=42;const val MAX_SPEED=110f;const val BG=0xFF080A10.toInt();const val CARD=0xFF101521.toInt();const val CARD2=0xFF131A28.toInt();const val TRACK=0xFF252E40.toInt();const val GRID=0xFF3A455A.toInt();const val TEXT=0xFFF5F7FF.toInt();const val MUTED=0xFF8994AA.toInt();const val CYAN=0xFF21E6FF.toInt();const val PURPLE=0xFF9B6CFF.toInt();const val PINK=0xFFFF4D8D.toInt();const val GREEN=0xFF39E58C.toInt();const val ORANGE=0xFFFFB24A.toInt()}
}
