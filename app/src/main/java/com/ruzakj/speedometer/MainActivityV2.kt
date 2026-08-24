package com.ruzakj.speedometer

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
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
    // RIDE_INSIGHTS_V1
    private lateinit var dash: Dashboard
    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var tracking=false; private var lastLocation:Location?=null; private var lastElapsedNs=0L
    private var speedKmh=0f; private var maxKmh=0f; private var distanceM=0f; private var movingMs=0L
    private var averageSum=0.0; private var averageSamples=0; private var accuracyM=999f; private var speedAccuracyMps=Float.MAX_VALUE; private var smartMoving=false; private var stoppedSinceNs=0L; private var overspeedLimitKmh=80f; private val speedHistory=ArrayDeque<Float>()
    private var introAnimating=false; private var introSpeed=0f; private var introAnimator:ValueAnimator?=null
    private var burnShiftX=0f; private var burnShiftY=0f; private var burnIndex=0
    private val burnInShift=object:Runnable{override fun run(){if(!isFinishing&&!isInPictureInPictureMode){val d=dfForBurnIn();val pattern=arrayOf(floatArrayOf(1f,0f),floatArrayOf(0f,1f),floatArrayOf(-1f,0f),floatArrayOf(0f,-1f),floatArrayOf(1f,1f),floatArrayOf(-1f,1f),floatArrayOf(-1f,-1f),floatArrayOf(1f,-1f));val p=pattern[burnIndex%pattern.size];burnShiftX=p[0]*d;burnShiftY=p[1]*d;burnIndex++;dash.invalidate()};handler.postDelayed(this,75000L)}}
    private val refresh=object:Runnable{override fun run(){dash.invalidate();handler.postDelayed(this,250L)}}

    override fun onCreate(state:Bundle?){super.onCreate(state);window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);window.statusBarColor=BG;window.navigationBarColor=BG
        if(Build.VERSION.SDK_INT>=29){window.isStatusBarContrastEnforced=false;window.isNavigationBarContrastEnforced=false}
        locationManager=getSystemService(Context.LOCATION_SERVICE) as LocationManager;dash=Dashboard(this)
        overspeedLimitKmh=getSharedPreferences("ride_settings",Context.MODE_PRIVATE).getFloat("overspeed_limit",80f).coerceIn(30f,110f)
        val insights=RideInsightsOverlay(this,{speedKmh},{accuracyM},{speedAccuracyMps},{overspeedLimitKmh},{setOverspeedLimit(it)})
        val root=android.widget.FrameLayout(this);root.addView(dash,android.widget.FrameLayout.LayoutParams(-1,-1));root.addView(insights,android.widget.FrameLayout.LayoutParams(-1,-1))
        dash.setOnApplyWindowInsetsListener{view,insets->if(Build.VERSION.SDK_INT>=30){val b=insets.getInsets(WindowInsets.Type.systemBars());view.setPadding(dp(16),b.top+dp(6),dp(16),b.bottom+dp(8))}else view.setPadding(dp(16),dp(24),dp(16),dp(16));insets}
        setContentView(root);handler.post(refresh);handler.postDelayed(burnInShift,75000L);playStartupSweep()
    }
    private fun dfForBurnIn()=min(2.5f*resources.displayMetrics.density,3f)
    override fun onConfigurationChanged(newConfig:Configuration){super.onConfigurationChanged(newConfig);dash.animateOrientationTransition()}
    private fun playStartupSweep(){introAnimator?.cancel();introAnimating=true;introSpeed=0f;introAnimator=ValueAnimator.ofFloat(0f,1f).apply{duration=1450L;addUpdateListener{a->val t=a.animatedValue as Float;introSpeed=if(t<.45f)MAX_SPEED*easeOutCubic(t/.45f)else MAX_SPEED*(1f-easeInOutCubic((t-.45f)/.55f));dash.invalidate()};addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(a:Animator){introAnimating=false;introSpeed=0f;dash.invalidate()}});start()}}
    private fun easeOutCubic(v:Float):Float{val p=v.coerceIn(0f,1f);return 1f-(1f-p)*(1f-p)*(1f-p)}
    private fun easeInOutCubic(v:Float):Float{val p=v.coerceIn(0f,1f);return if(p<.5f)4f*p*p*p else 1f-((-2f*p+2f)*(-2f*p+2f)*(-2f*p+2f))/2f}
    private fun startTracking(){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),REQUEST_LOCATION);return};if(!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)){dash.message="GPS OFF  •  ENABLE LOCATION";dash.invalidate();return};tracking=true;dash.message="GPS ACTIVE  •  OFFLINE";try{locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,500L,0f,this,Looper.getMainLooper())}catch(_:SecurityException){tracking=false;dash.message="LOCATION PERMISSION REQUIRED"};dash.invalidate()}
    private fun stopTracking(){
        if(tracking)HistoryStore.save(this,distanceM/1000f,maxKmh,averageKmh(),movingMs)
        tracking=false;locationManager.removeUpdates(this);smartMoving=false;stoppedSinceNs=0L;dash.message="GPS PAUSED  •  OFFLINE";dash.invalidate()
    }
    private fun resetTrip(){maxKmh=0f;distanceM=0f;movingMs=0L;averageSum=0.0;averageSamples=0;lastLocation=null;lastElapsedNs=0L;speedKmh=0f;accuracyM=999f;speedAccuracyMps=Float.MAX_VALUE;smartMoving=false;stoppedSinceNs=0L;speedHistory.clear();dash.message=if(tracking)"GPS ACTIVE  •  OFFLINE" else "READY  •  OFFLINE";dash.invalidate()}
    override fun onLocationChanged(location:Location){
        accuracyM=if(location.hasAccuracy())location.accuracy else 999f
        speedAccuracyMps=if(Build.VERSION.SDK_INT>=26&&location.hasSpeedAccuracy())location.speedAccuracyMetersPerSecond else Float.MAX_VALUE
        if(accuracyM>35f){dash.message=String.format(Locale.US,"GPS WEAK  •  ±%.0f m",accuracyM);dash.invalidate();return}
        val now=location.elapsedRealtimeNanos
        val prev=lastLocation
        val dt=if(prev!=null&&now>lastElapsedNs)(now-lastElapsedNs)/1_000_000_000f else 0f
        val raw=if(location.hasSpeed())max(0f,location.speed)else 0f
        val q=gpsQualityFactor()
        if(prev!=null&&dt>0f){
            val jump=prev.distanceTo(location)
            val derived=jump/dt
            if(jump>120f&&dt<2.5f)return
            if(derived>69.4f&&raw<55f)return
            // Ignore GPS drift while stationary; retain real movement without imposing a trip-distance cap.
            if(jump>=1.5f&&derived>=1.2f&&derived<=55f)distanceM+=jump
        }
        val target=(raw*3.6f).coerceIn(0f,MAX_SPEED)
        val alpha=.30f+.50f*q
        speedKmh+=(target-speedKmh)*alpha
        speedKmh=speedKmh.coerceIn(0f,MAX_SPEED)
        if(target<1.5f&&speedKmh<1f)speedKmh=0f

        // Smart stop: enter moving above 2 km/h, leave moving only after 4 s below 1 km/h.
        if(speedKmh>=2f){smartMoving=true;stoppedSinceNs=0L}
        else if(smartMoving&&speedKmh<=1f){if(stoppedSinceNs==0L)stoppedSinceNs=now;if(now-stoppedSinceNs>=4_000_000_000L)smartMoving=false}
        else if(speedKmh>1f){stoppedSinceNs=0L}
        if(prev!=null&&dt>0f&&smartMoving)movingMs+=(dt*1000f).toLong()
        if(smartMoving&&q>.35f){averageSamples++;averageSum+=speedKmh.toDouble()}

        speedHistory.addLast(speedKmh)
        while(speedHistory.size>120)speedHistory.removeFirst()
        lastLocation=Location(location);lastElapsedNs=now
        maxKmh=max(maxKmh,speedKmh).coerceAtMost(MAX_SPEED)
        if(speedKmh>=overspeedLimitKmh&&speedKmh>=3f)dash.message=String.format(Locale.US,"OVERSPEED  •  %.1f / %.0f km/h",speedKmh,overspeedLimitKmh) else dash.message="GPS ACTIVE  •  OFFLINE"
        dash.invalidate()
    }
    private fun gpsQualityFactor():Float{val p=(1f-(accuracyM-3f)/27f).coerceIn(0f,1f);val v=if(speedAccuracyMps==Float.MAX_VALUE).55f else(1f-speedAccuracyMps/5f).coerceIn(0f,1f);return(p*.7f+v*.3f).coerceIn(0f,1f)}
    private fun gpsLabel()=when{accuracyM<=5f->"EXCELLENT";accuracyM<=10f->"GOOD";accuracyM<=20f->"FAIR";accuracyM<900f->"WEAK";else->"SEARCHING"}
    private fun averageKmh()=if(averageSamples==0)0f else(averageSum/averageSamples).toFloat().coerceAtMost(MAX_SPEED)
    private fun movingTime():String{val s=movingMs/1000L;return if(s>=3600)String.format(Locale.US,"%02d:%02d:%02d",s/3600,(s%3600)/60,s%60)else String.format(Locale.US,"%02d:%02d",s/60,s%60)}
    private fun setOverspeedLimit(value:Float){overspeedLimitKmh=value.coerceIn(30f,110f);getSharedPreferences("ride_settings",Context.MODE_PRIVATE).edit().putFloat("overspeed_limit",overspeedLimitKmh).apply();dash.invalidate()}
    private fun enterPip(){if(Build.VERSION.SDK_INT<26||isInPictureInPictureMode)return;val landscape=resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE;val ratio=if(landscape)Rational(16,9)else Rational(9,16);val p=PictureInPictureParams.Builder().setAspectRatio(ratio).apply{if(Build.VERSION.SDK_INT>=31)setSeamlessResizeEnabled(true)}.build();try{enterPictureInPictureMode(p)}catch(_:IllegalStateException){dash.message="PIP UNAVAILABLE";dash.invalidate()}}
    override fun onPictureInPictureModeChanged(v:Boolean){super.onPictureInPictureModeChanged(v);dash.setPipMode(v);dash.invalidate()}
    override fun onRequestPermissionsResult(c:Int,p:Array<out String>,r:IntArray){super.onRequestPermissionsResult(c,p,r);if(c==REQUEST_LOCATION&&r.isNotEmpty()&&r[0]==PackageManager.PERMISSION_GRANTED)startTracking()else if(c==REQUEST_LOCATION){dash.message="PRECISE GPS PERMISSION REQUIRED";dash.invalidate()}}
    override fun onProviderDisabled(p:String){if(p==LocationManager.GPS_PROVIDER){dash.message="GPS OFF  •  ENABLE LOCATION";dash.invalidate()}}
    override fun onProviderEnabled(p:String){if(p==LocationManager.GPS_PROVIDER){dash.message="GPS READY  •  OFFLINE";dash.invalidate()}}
    override fun onDestroy(){introAnimator?.cancel();handler.removeCallbacksAndMessages(null);locationManager.removeUpdates(this);super.onDestroy()}

    private inner class Dashboard(context:Context):View(context){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private val fill=Paint(Paint.ANTI_ALIAS_FLAG);private var startRect=RectF();private var resetRect=RectF();private var pipRect=RectF();private var pipMode=false;private var transition=1f;private var transitionAnimator:ValueAnimator?=null;var message="READY  •  OFFLINE"
        init{setLayerType(View.LAYER_TYPE_SOFTWARE,null)}
        fun setPipMode(v:Boolean){pipMode=v}
        fun animateOrientationTransition(){transitionAnimator?.cancel();transition=0f;transitionAnimator=ValueAnimator.ofFloat(0f,1f).apply{duration=360L;addUpdateListener{transition=it.animatedValue as Float;invalidate()};start()}}
        override fun onDraw(c:Canvas){super.onDraw(c);c.drawColor(BG);if(pipMode)drawPip(c)else{val land=width>height;val p=transition.coerceIn(0f,1f);c.save();c.translate(burnShiftX,(1f-p)*df(12f)+burnShiftY);c.scale(.985f+.015f*p,.985f+.015f*p,width/2f,height/2f);if(land)drawLandscape(c)else drawPortrait(c);c.restore()}}
        private fun shownSpeed()=if(introAnimating)introSpeed else speedKmh.coerceAtMost(MAX_SPEED)
        private fun drawPortrait(c:Canvas){val w=width.toFloat();val h=height.toFloat();val l=df(16f);val r=w-l;val cw=r-l;text(c,"MOTO",l,df(25f),df(20f),TEXT,true,Paint.Align.LEFT);text(c,"SPEED",l+df(61f),df(25f),df(20f),CYAN,true,Paint.Align.LEFT);text(c,if(tracking)"● GPS LIVE"else"○ GPS READY",l,df(46f),df(9f),if(tracking)GREEN else MUTED,true,Paint.Align.LEFT);drawMotorAccent(c,r-df(54f),df(26f),df(82f),df(34f));pill(c,r-df(84f),df(52f),df(84f),df(25f),"OFFLINE",CYAN);val top=df(82f);val bottom=min(h-df(218f),top+df(395f));premiumCard(c,RectF(l,top,r,bottom));text(c,"RIDE / SPORT",l+df(18f),top+df(24f),df(9f),PURPLE,true,Paint.Align.LEFT);text(c,gpsLabel(),r-df(18f),top+df(24f),df(9f),gaugeColor(),true,Paint.Align.RIGHT);drawGauge(c,w/2f,top+(bottom-top)*.60f,min(cw*.39f,(bottom-top)*.38f),true);val st=bottom+df(12f);val gap=df(8f);val cardW=(cw-gap)/2f;val cardH=df(64f);statCard(c,RectF(l,st,l+cardW,st+cardH),"AVG",String.format(Locale.US,"%.1f",averageKmh()),"km/h",CYAN);statCard(c,RectF(l+cardW+gap,st,r,st+cardH),"MAX",String.format(Locale.US,"%.1f",maxKmh),"km/h",PINK);statCard(c,RectF(l,st+cardH+gap,l+cardW,st+cardH*2+gap),"TRIP",String.format(Locale.US,"%.2f",distanceM/1000f),"km",PURPLE);statCard(c,RectF(l+cardW+gap,st+cardH+gap,r,st+cardH*2+gap),"MOVING",movingTime(),"",ORANGE);val by=st+cardH*2+gap*2;val bw=(cw-gap*2)/3f;startRect=RectF(l,by,l+bw,by+df(46f));resetRect=RectF(l+bw+gap,by,l+bw*2+gap,by+df(46f));pipRect=RectF(l+bw*2+gap*2,by,r,by+df(46f));button(c,startRect,if(tracking)"STOP • GPS"else"START • GPS",if(tracking)PINK else CYAN);button(c,resetRect,"RESET",MUTED);button(c,pipRect,"PIP",PURPLE);text(c,message,r,h-df(12f),df(8f),MUTED,true,Paint.Align.RIGHT)}
        private fun drawLandscape(c:Canvas){val w=width.toFloat();val h=height.toFloat();val l=df(22f);val r=w-l;val top=df(14f);val bottom=h-df(14f);val gap=df(14f);val leftR=l+(r-l)*.58f;val rightL=leftR+gap;val gaugeBox=RectF(l,top+df(50f),leftR,bottom);val info=RectF(rightL,top+df(50f),r,bottom);text(c,"MOTO",l,top+df(20f),df(20f),TEXT,true,Paint.Align.LEFT);text(c,"SPEED",l+df(61f),top+df(20f),df(20f),CYAN,true,Paint.Align.LEFT);text(c,if(tracking)"● GPS LIVE"else"○ GPS READY",l,top+df(40f),df(9f),if(tracking)GREEN else MUTED,true,Paint.Align.LEFT);text(c,"SPORT  /  MAX 110",r,top+df(20f),df(9f),PURPLE,true,Paint.Align.RIGHT);drawMotorAccent(c,r-df(100f),top+df(24f),df(82f),df(34f));premiumCard(c,gaugeBox);text(c,"TFT RIDE DISPLAY",l+df(18f),top+df(74f),df(9f),PURPLE,true,Paint.Align.LEFT);text(c,gpsLabel(),leftR-df(18f),top+df(74f),df(9f),gaugeColor(),true,Paint.Align.RIGHT);val cx=(l+leftR)/2f;val cy=(gaugeBox.top+gaugeBox.bottom)/2f+df(8f);val rad=min((leftR-l)*.39f,(bottom-top)*.37f);drawGauge(c,cx,cy,rad,false);premiumCard(c,info);text(c,"RIDE TELEMETRY",info.left+df(16f),info.top+df(24f),df(9f),CYAN,true,Paint.Align.LEFT);pill(c,info.right-df(76f),info.top+df(10f),df(60f),df(22f),"OFFLINE",CYAN);val iw=info.width();val colW=(iw-df(36f))/2f;val x1=info.left+df(12f);val x2=info.centerX()+df(5f);statCard(c,RectF(x1,info.top+df(42f),x1+colW,info.top+df(98f)),"MAX",String.format(Locale.US,"%.1f",maxKmh),"km/h",PINK);statCard(c,RectF(x2,info.top+df(42f),x2+colW,info.top+df(98f)),"AVG",String.format(Locale.US,"%.1f",averageKmh()),"km/h",CYAN);statCard(c,RectF(x1,info.top+df(106f),x1+colW,info.top+df(162f)),"TRIP",String.format(Locale.US,"%.2f",distanceM/1000f),"km",PURPLE);statCard(c,RectF(x2,info.top+df(106f),x2+colW,info.top+df(162f)),"GPS",if(accuracyM<900f)String.format(Locale.US,"±%.0f",accuracyM)else"--","m",ORANGE);text(c,if(introAnimating)"SYSTEM CHECK  •  0 → 110 → 0"else message,info.left+df(14f),info.bottom-df(48f),df(8f),MUTED,true,Paint.Align.LEFT);val by=info.bottom-df(38f);val bw=(iw-df(36f))/3f;startRect=RectF(x1,by,x1+bw,by+df(30f));resetRect=RectF(x1+df(6f)+bw,by,x1+df(6f)+bw*2,by+df(30f));pipRect=RectF(info.right-df(12f)-bw,by,info.right-df(12f),by+df(30f));button(c,startRect,if(tracking)"STOP"else"START",if(tracking)PINK else CYAN);button(c,resetRect,"RESET",MUTED);button(c,pipRect,"PIP",PURPLE)}
        private fun drawGauge(c:Canvas,cx:Float,cy:Float,radius:Float,portrait:Boolean){val arc=RectF(cx-radius,cy-radius,cx+radius,cy+radius);paint.style=Paint.Style.STROKE;paint.strokeCap=Paint.Cap.ROUND;paint.strokeWidth=df(if(portrait)12f else 14f);paint.shader=null;paint.color=TRACK;c.drawArc(arc,138f,264f,false,paint);val f=(shownSpeed()/MAX_SPEED).coerceIn(0f,1f);paint.shader=LinearGradient(arc.left,arc.bottom,arc.right,arc.top,intArrayOf(CYAN,PURPLE,PINK),null,Shader.TileMode.CLAMP);c.drawArc(arc,138f,264f*f,false,paint);paint.shader=null;paint.strokeWidth=df(3f);for(i in 0..11){val a=Math.toRadians(138.0+i*264.0/11.0);val inn=radius-df(19f);val out=radius-df(7f);paint.color=if(i/11f<=f)CYAN else GRID;c.drawLine(cx+cos(a).toFloat()*inn,cy+sin(a).toFloat()*inn,cx+cos(a).toFloat()*out,cy+sin(a).toFloat()*out,paint)};for(i in 0..11){val v=i*10;val a=Math.toRadians(138.0+i*264.0/11.0);val lr=radius-df(34f);text(c,v.toString(),cx+cos(a).toFloat()*lr,cy+sin(a).toFloat()*lr+df(3f),df(if(portrait)8f else 9f),if(v%20==0)TEXT else MUTED,true,Paint.Align.CENTER)};val needleAngle=Math.toRadians(138.0+(264.0*f));val needleLen=radius-df(46f);val nx=cx+cos(needleAngle).toFloat()*needleLen;val ny=cy+sin(needleAngle).toFloat()*needleLen;paint.style=Paint.Style.STROKE;paint.strokeWidth=df(if(portrait)3.2f else 3.8f);paint.strokeCap=Paint.Cap.ROUND;paint.color=PINK;c.drawLine(cx,cy,nx,ny,paint);paint.style=Paint.Style.FILL;paint.color=TEXT;c.drawCircle(cx,cy,df(7f),paint);paint.color=PINK;c.drawCircle(cx,cy,df(4f),paint);text(c,"GPS SPEED",cx,cy-df(48f),df(10f),MUTED,true,Paint.Align.CENTER);text(c,String.format(Locale.US,"%.1f",shownSpeed()),cx,cy+df(18f),df(if(portrait)58f else 64f),TEXT,true,Paint.Align.CENTER);text(c,"km/h",cx,cy+df(46f),df(14f),MUTED,false,Paint.Align.CENTER);text(c,if(introAnimating)"SYSTEM CHECK • MAX 110"else if(accuracyM<900f)String.format(Locale.US,"GPS ±%.0f m",accuracyM)else"WAITING FOR GPS",cx,cy+df(70f),df(9f),gaugeColor(),true,Paint.Align.CENTER)}
        private fun drawPip(c:Canvas){val w=width.toFloat();val h=height.toFloat();val cx=w/2f;val cy=h*.46f;text(c,"MOTO SPEED",cx,h*.14f,df(12f),CYAN,true,Paint.Align.CENTER);text(c,String.format(Locale.US,"%.1f",speedKmh.coerceAtMost(MAX_SPEED)),cx,cy,df(64f),TEXT,true,Paint.Align.CENTER);text(c,"km/h  /  MAX 110",cx,cy+df(30f),df(13f),MUTED,false,Paint.Align.CENTER);text(c,if(accuracyM<900f)String.format(Locale.US,"±%.0f m  •  %s",accuracyM,gpsLabel())else"SEARCHING GPS",cx,cy+df(54f),df(9f),gaugeColor(),true,Paint.Align.CENTER);text(c,if(tracking)"● LIVE • OFFLINE"else"PAUSED • OFFLINE",cx,h-df(16f),df(9f),if(tracking)GREEN else MUTED,true,Paint.Align.CENTER)}
        private fun premiumCard(c:Canvas,r:RectF){fill.style=Paint.Style.FILL;fill.color=CARD;fill.setShadowLayer(df(18f),0f,df(8f),0x70000000);c.drawRoundRect(r,df(24f),df(24f),fill);fill.clearShadowLayer();paint.style=Paint.Style.STROKE;paint.strokeWidth=df(1f);paint.shader=null;paint.color=0x553B4A65;c.drawRoundRect(r,df(24f),df(24f),paint)}
        private fun statCard(c:Canvas,r:RectF,label:String,value:String,unit:String,accent:Int){fill.style=Paint.Style.FILL;fill.color=CARD2;c.drawRoundRect(r,df(16f),df(16f),fill);fill.color=accent;c.drawRoundRect(RectF(r.left,r.top,r.left+df(4f),r.bottom),df(2f),df(2f),fill);text(c,label,r.left+df(14f),r.top+df(20f),df(8f),MUTED,true,Paint.Align.LEFT);text(c,value,r.left+df(14f),r.top+df(43f),df(18f),TEXT,true,Paint.Align.LEFT);if(unit.isNotEmpty())text(c,unit,r.right-df(12f),r.top+df(43f),df(8f),accent,true,Paint.Align.RIGHT)}
        private fun button(c:Canvas,r:RectF,label:String,accent:Int){fill.style=Paint.Style.FILL;fill.color=0xFF151A25.toInt();c.drawRoundRect(r,df(14f),df(14f),fill);paint.style=Paint.Style.STROKE;paint.strokeWidth=df(1.5f);paint.shader=null;paint.color=accent;c.drawRoundRect(r,df(14f),df(14f),paint);text(c,label,r.centerX(),r.centerY()+df(3f),df(8f),accent,true,Paint.Align.CENTER)}
        private fun pill(c:Canvas,x:Float,y:Float,w:Float,h:Float,label:String,accent:Int){fill.style=Paint.Style.FILL;fill.color=0x331EE6FF;c.drawRoundRect(RectF(x,y,x+w,y+h),h/2f,h/2f,fill);text(c,label,x+w/2f,y+h*.67f,df(8f),accent,true,Paint.Align.CENTER)}
        private fun drawMotorAccent(c:Canvas,x:Float,y:Float,w:Float,h:Float){paint.style=Paint.Style.STROKE;paint.strokeWidth=df(2f);paint.strokeCap=Paint.Cap.ROUND;paint.shader=null;paint.color=CYAN;val wy=y+h*.68f;c.drawCircle(x+w*.2f,wy,df(7f),paint);c.drawCircle(x+w*.78f,wy,df(7f),paint);val p=Path();p.moveTo(x+w*.2f,wy-df(7f));p.lineTo(x+w*.35f,y+h*.38f);p.lineTo(x+w*.55f,y+h*.38f);p.lineTo(x+w*.78f,wy-df(7f));p.moveTo(x+w*.35f,y+h*.38f);p.lineTo(x+w*.45f,wy);p.lineTo(x+w*.62f,wy);p.moveTo(x+w*.55f,y+h*.38f);p.lineTo(x+w*.66f,y+h*.18f);c.drawPath(p,paint)}
        private fun text(c:Canvas,v:String,x:Float,y:Float,size:Float,color:Int,bold:Boolean,align:Paint.Align){paint.style=Paint.Style.FILL;paint.shader=null;paint.color=color;paint.textSize=size;paint.typeface=if(bold)Typeface.create("sans-serif",Typeface.BOLD)else Typeface.create("sans-serif",Typeface.NORMAL);paint.textAlign=align;c.drawText(v,x,y,paint)}
        private fun gaugeColor()=when{accuracyM<=5f->CYAN;accuracyM<=10f->GREEN;accuracyM<=20f->ORANGE;else->PINK}
        override fun onTouchEvent(e:MotionEvent):Boolean{if(e.action==MotionEvent.ACTION_UP&&!pipMode){when{startRect.contains(e.x,e.y)->if(tracking)stopTracking()else startTracking();resetRect.contains(e.x,e.y)->resetTrip();pipRect.contains(e.x,e.y)->enterPip()};performClick();return true};return true}
        override fun performClick():Boolean{super.performClick();return true}
    }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).roundToInt();private fun df(v:Float)=v*resources.displayMetrics.density
    companion object{const val REQUEST_LOCATION=42;const val MAX_SPEED=110f;const val BG=0xFF080A10.toInt();const val CARD=0xFF101521.toInt();const val CARD2=0xFF131A28.toInt();const val TRACK=0xFF252E40.toInt();const val GRID=0xFF3A455A.toInt();const val TEXT=0xFFF5F7FF.toInt();const val MUTED=0xFF8994AA.toInt();const val CYAN=0xFF21E6FF.toInt();const val PURPLE=0xFF9B6CFF.toInt();const val PINK=0xFFFF4D8D.toInt();const val GREEN=0xFF39E58C.toInt();const val ORANGE=0xFFFFB24A.toInt()}
}
