from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/java/com/ruzakj/speedometer/MainActivityV2.kt'
MARKER = '// RIDE_INSIGHTS_V1'

text = MAIN.read_text(encoding='utf-8')
if MARKER in text:
    print('Ride insights already applied')
    raise SystemExit(0)

# 1) State for smart-stop, live graph and configurable overspeed alert.
needle = 'private var averageSum=0.0; private var averageSamples=0; private var accuracyM=999f; private var speedAccuracyMps=Float.MAX_VALUE'
insert = needle + '; private var smartMoving=false; private var stoppedSinceNs=0L; private var overspeedLimitKmh=80f; private val speedHistory=ArrayDeque<Float>()'
if needle not in text:
    raise SystemExit('state anchor not found')
text = text.replace(needle, insert, 1)

# 2) Create the live telemetry overlay without replacing the existing UI.
old = 'locationManager=getSystemService(Context.LOCATION_SERVICE) as LocationManager;dash=Dashboard(this)'
new = '''locationManager=getSystemService(Context.LOCATION_SERVICE) as LocationManager;dash=Dashboard(this)
        overspeedLimitKmh=getSharedPreferences("ride_settings",Context.MODE_PRIVATE).getFloat("overspeed_limit",80f).coerceIn(30f,110f)
        val insights=RideInsightsOverlay(this,{speedKmh},{accuracyM},{speedAccuracyMps},{movingTime()},{smartMoving},{overspeedLimitKmh},{setOverspeedLimit(it)},{speedHistory.toList()})
        val root=android.widget.FrameLayout(this);root.addView(dash,android.widget.FrameLayout.LayoutParams(-1,-1));root.addView(insights,android.widget.FrameLayout.LayoutParams(-1,-1))'''
if old not in text:
    raise SystemExit('onCreate anchor not found')
text = text.replace(old, new, 1)
text = text.replace('setContentView(dash);handler.post(refresh);', 'setContentView(root);handler.post(refresh);', 1)

# 3) Better smart-stop and filtering. This replaces only the old location callback.
start = text.find('    override fun onLocationChanged(location:Location){')
end = text.find('    private fun gpsQualityFactor()', start)
if start < 0 or end < 0:
    raise SystemExit('location callback anchors not found')
new_callback = '''    override fun onLocationChanged(location:Location){
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
'''
text = text[:start] + new_callback + text[end:]

# 4) Persist the trip when GPS is stopped, and reset graph/state cleanly.
old_stop='    private fun stopTracking(){tracking=false;locationManager.removeUpdates(this);dash.message="GPS PAUSED  •  OFFLINE";dash.invalidate()}'
new_stop='''    private fun stopTracking(){
        if(tracking)HistoryStore.save(this,distanceM/1000f,maxKmh,averageKmh(),movingMs)
        tracking=false;locationManager.removeUpdates(this);smartMoving=false;stoppedSinceNs=0L;dash.message="GPS PAUSED  •  OFFLINE";dash.invalidate()
    }'''
if old_stop not in text:
    raise SystemExit('stopTracking anchor not found')
text=text.replace(old_stop,new_stop,1)

old_reset='    private fun resetTrip(){maxKmh=0f;distanceM=0f;movingMs=0L;averageSum=0.0;averageSamples=0;lastLocation=null;lastElapsedNs=0L;speedKmh=0f;accuracyM=999f;speedAccuracyMps=Float.MAX_VALUE;dash.message=if(tracking)"GPS ACTIVE  •  OFFLINE" else "READY  •  OFFLINE";dash.invalidate()}'
new_reset='''    private fun resetTrip(){maxKmh=0f;distanceM=0f;movingMs=0L;averageSum=0.0;averageSamples=0;lastLocation=null;lastElapsedNs=0L;speedKmh=0f;accuracyM=999f;speedAccuracyMps=Float.MAX_VALUE;smartMoving=false;stoppedSinceNs=0L;speedHistory.clear();dash.message=if(tracking)"GPS ACTIVE  •  OFFLINE" else "READY  •  OFFLINE";dash.invalidate()}'''
if old_reset not in text:
    raise SystemExit('resetTrip anchor not found')
text=text.replace(old_reset,new_reset,1)

# 5) Add a persistent overspeed-limit setter before PIP.
anchor='    private fun enterPip(){'
setter='''    private fun setOverspeedLimit(value:Float){overspeedLimitKmh=value.coerceIn(30f,110f);getSharedPreferences("ride_settings",Context.MODE_PRIVATE).edit().putFloat("overspeed_limit",overspeedLimitKmh).apply();dash.invalidate()}
'''
if anchor not in text:
    raise SystemExit('PIP anchor not found')
text=text.replace(anchor,setter+anchor,1)

# 6) Mark the source so this build-time patch is idempotent.
text=text.replace('class MainActivityV2 : Activity(), LocationListener {', 'class MainActivityV2 : Activity(), LocationListener {\n    '+MARKER, 1)
MAIN.write_text(text, encoding='utf-8')
print('Applied ride insights patch')
