package com.shinpstudio.navoverlay;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.*;
import android.os.*;
import android.view.*;
import android.widget.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class NavService extends Service implements LocationListener, SensorEventListener {
    private static final String CHANNEL="offline_navigation";
    private static final long MAX_FIX_AGE_NS = TimeUnit.SECONDS.toNanos(15);
    private static final long MAX_CACHED_FIX_AGE_NS = TimeUnit.SECONDS.toNanos(5);
    private static final long MIN_FIX_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long MAX_HEADING_AGE_NS = TimeUnit.SECONDS.toNanos(3);
    private static final long DECLINATION_INTERVAL_NS = TimeUnit.MINUTES.toNanos(5);
    private static final float MAX_ACCURACY_METERS = 45f;

    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<String> locationProviders = new ArrayList<>(2);
    private boolean calculating,active,failed; private int generation,mode,size,offCount,arrivalCount; private long lastRequest; private int alerted=-1;
    private LocationManager lm; private WindowManager wm; private LinearLayout overlay; private TextView primary,secondary; private Route.Point destination; private Location fix; private Route route; private double progress; private boolean initial=true; private String destName=""; private WindowManager.LayoutParams layout;
    private TextView direction;
    private SensorManager sensorManager;
    private Sensor orientationSensor;
    private final float[] deviceMatrix = new float[9];
    private final float[] screenMatrix = new float[9];
    private double magneticHeading = Double.NaN;
    private double routeBearing = Double.NaN;
    private float declination;
    private long headingAt;
    private long declinationAt;
    private int directionSector = -1;
    private long locationStartedAt;
    private Location impreciseFix;
    private final Runnable locationStatus = new Runnable() {
        @Override public void run() {
            if (!active) return;
            refreshLocationStatus();
            refreshDirection();
            ui.postDelayed(this, 5000);
        }
    };
    int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent i,int flags,int id){if(i==null||"STOP".equals(i.getAction())){stopSelf();return START_NOT_STICKY;}
        try{generation++;active=true;calculating=false;failed=false;route=null;fix=null;initial=true;progress=0;offCount=arrivalCount=0;alerted=-1;lastRequest=0;destination=new Route.Point(i.getDoubleExtra("lat",Double.NaN),i.getDoubleExtra("lon",Double.NaN));destName=i.getStringExtra("name");mode=i.getIntExtra("mode",0);size=i.getIntExtra("size",1);
            NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel(CHANNEL,"ナビ案内",NotificationManager.IMPORTANCE_LOW));PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,NavService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(com.shinpstudio.navoverlay.R.drawable.ic_nav).setContentTitle("オフラインナビ動作中").setContentText("端末の位置情報で案内しています").setContentIntent(open).addAction(new Notification.Action.Builder((android.graphics.drawable.Icon)null,"終了",stop).build()).setOngoing(true).build();startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            createOverlay();
            startHeadingUpdates();
            startLocationUpdates();
        }catch(Exception e){Toast.makeText(this,"ナビ開始失敗: "+e.getMessage(),Toast.LENGTH_LONG).show();stopSelf();}return START_NOT_STICKY;
    }

    private void startHeadingUpdates() {
        magneticHeading = Double.NaN;
        routeBearing = Double.NaN;
        headingAt = 0;
        declinationAt = 0;
        directionSector = -1;
        sensorManager = getSystemService(SensorManager.class);
        if (sensorManager == null) return;

        sensorManager.unregisterListener(this);
        orientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (orientationSensor != null && sensorManager.registerListener(
            this, orientationSensor, SensorManager.SENSOR_DELAY_UI, ui)) return;

        orientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        if (orientationSensor != null && !sensorManager.registerListener(
            this, orientationSensor, SensorManager.SENSOR_DELAY_UI, ui)) orientationSensor = null;
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!active || event.sensor != orientationSensor || overlay == null || overlay.getDisplay() == null) return;
        if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
            (event.values.length >= 5 && event.values[4] > Math.PI / 4)) {
            magneticHeading = Double.NaN;
        } else {
            SensorManager.getRotationMatrixFromVector(deviceMatrix, event.values);
            int rotation = overlay.getDisplay().getRotation();
            int right = switch (rotation) {
                case Surface.ROTATION_90 -> SensorManager.AXIS_Y;
                case Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X;
                case Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y;
                default -> SensorManager.AXIS_X;
            };
            int up = switch (rotation) {
                case Surface.ROTATION_90 -> SensorManager.AXIS_MINUS_X;
                case Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_Y;
                case Surface.ROTATION_270 -> SensorManager.AXIS_X;
                default -> SensorManager.AXIS_Y;
            };
            SensorManager.remapCoordinateSystem(deviceMatrix, right, up, screenMatrix);
            magneticHeading = Heading.magneticDegrees(screenMatrix);
            headingAt = event.timestamp;
        }
        refreshDirection();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor == orientationSensor && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            magneticHeading = Double.NaN;
            refreshDirection();
        }
    }

    private void refreshDirection() {
        if (direction == null) return;
        if (!Double.isFinite(routeBearing) || !isFresh(fix, MAX_FIX_AGE_NS)) {
            directionSector = -1;
            if (direction.getVisibility() != View.GONE) direction.setVisibility(View.GONE);
            return;
        }

        String text;
        if (orientationSensor == null) {
            text = "進む向き  方位センサーなし";
        } else if (!Double.isFinite(magneticHeading) ||
            SystemClock.elapsedRealtimeNanos() - headingAt > MAX_HEADING_AGE_NS) {
            text = "進む向き  方位を確認中";
        } else {
            directionSector = Heading.sector(routeBearing, magneticHeading + declination, directionSector);
            text = "進む向き  " + Heading.direction(directionSector);
        }
        if (!text.contentEquals(direction.getText())) direction.setText(text);
        if (direction.getVisibility() != View.VISIBLE) direction.setVisibility(View.VISIBLE);
    }

    private void startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("正確な位置情報を許可してください");
        }
        lm = getSystemService(LocationManager.class);
        lm.removeUpdates(this);
        locationProviders.clear();
        List<String> available = lm.getAllProviders();
        if (available.contains(LocationManager.GPS_PROVIDER)) locationProviders.add(LocationManager.GPS_PROVIDER);
        boolean hasFused = available.contains(LocationManager.FUSED_PROVIDER);
        if (hasFused) locationProviders.add(LocationManager.FUSED_PROVIDER);
        if ((!hasFused || !lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) &&
            available.contains(LocationManager.NETWORK_PROVIDER)) {
            locationProviders.add(LocationManager.NETWORK_PROVIDER);
        }

        locationStartedAt = SystemClock.elapsedRealtimeNanos();
        impreciseFix = null;
        Location newest = null;
        LocationRequest request = new LocationRequest.Builder(1000)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(1000)
            .build();
        for (String provider : locationProviders) {
            // Keep registered providers that are disabled so Settings changes
            // can recover an existing navigation session.
            lm.requestLocationUpdates(provider, request, getMainExecutor(), this);
            if (!lm.isProviderEnabled(provider)) continue;
            Location cached = lm.getLastKnownLocation(provider);
            if (isFresh(cached, MAX_CACHED_FIX_AGE_NS) && isPrecise(cached) &&
                (newest == null || cached.getElapsedRealtimeNanos() > newest.getElapsedRealtimeNanos())) {
                newest = cached;
            }
        }
        if (newest != null) onLocationChanged(newest);
        ui.removeCallbacks(locationStatus);
        locationStatus.run();
    }

    private static boolean isFresh(Location location, long maxAge) {
        if (location == null) return false;
        long age = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
        return age >= 0 && age <= maxAge;
    }

    private static boolean isPrecise(Location location) {
        return location.hasAccuracy() && location.getAccuracy() > 0 &&
            location.getAccuracy() <= MAX_ACCURACY_METERS;
    }

    private void refreshLocationStatus() {
        if (!active || calculating || failed || lm == null) return;
        if (!lm.isLocationEnabled()) {
            show("位置情報が無効", "端末の位置情報を有効にしてください");
        } else if (locationProviders.stream().noneMatch(lm::isProviderEnabled)) {
            show("位置情報を取得できません", "端末の位置情報設定を確認してください");
        } else if (isFresh(fix, MAX_FIX_AGE_NS)) {
            return;
        } else if (isFresh(impreciseFix, MAX_FIX_AGE_NS)) {
            float accuracy = impreciseFix.getAccuracy();
            String detail = impreciseFix.hasAccuracy() && accuracy > 0 && Float.isFinite(accuracy)
                ? "現在 ±" + Math.round(accuracy) + "m / 必要 ±" + Math.round(MAX_ACCURACY_METERS) + "m以内"
                : "精度情報を取得中です";
            show("現在地の精度が不足", detail);
        } else if (SystemClock.elapsedRealtimeNanos() - locationStartedAt >= MAX_FIX_AGE_NS) {
            show("現在地の受信待ち", "空の開けた場所で位置を取得してください");
        } else {
            show("現在地を取得中", "GPSと端末の位置情報を確認しています");
        }
    }

    private void createOverlay(){wm=getSystemService(WindowManager.class);if(overlay!=null){wm.removeView(overlay);overlay=null;}overlay=new LinearLayout(this);overlay.setOrientation(LinearLayout.HORIZONTAL);overlay.setGravity(Gravity.CENTER_VERTICAL);overlay.setPadding(dp(10),dp(5),dp(4),dp(5));GradientDrawable bg=new GradientDrawable();bg.setColor(0xED17221E);bg.setCornerRadius(dp(12));bg.setStroke(dp(2),0xFF66D69B);overlay.setBackground(bg);
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);
        direction = new TextView(this);
        direction.setTextColor(0xFF89EDAD);
        direction.setTextSize(size == 0 ? 13 : size == 2 ? 19 : 16);
        direction.setTypeface(null, Typeface.BOLD);
        direction.setVisibility(View.GONE);
        labels.addView(direction);
        primary=new TextView(this);primary.setTextColor(Color.WHITE);primary.setTextSize(size==0?17:size==2?26:21);primary.setTypeface(null,Typeface.BOLD);secondary=new TextView(this);secondary.setTextColor(0xFFD2E4DA);secondary.setTextSize(size==0?11:size==2?16:13);labels.addView(primary);labels.addView(secondary);overlay.addView(labels);TextView close=new TextView(this);close.setText("×");close.setTextSize(25);close.setTextColor(Color.WHITE);close.setGravity(Gravity.CENTER);close.setContentDescription("ナビを終了");overlay.addView(close,new LinearLayout.LayoutParams(dp(42),dp(48)));close.setOnClickListener(v->stopSelf());
        layout=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);layout.gravity=Gravity.TOP|Gravity.LEFT;var prefs=getSharedPreferences("nav",0);layout.x=prefs.getInt("x",dp(12));layout.y=prefs.getInt("y",dp(48));wm.addView(overlay,layout);
        labels.setOnTouchListener(new View.OnTouchListener(){float sx,sy;int ox,oy;boolean moving;public boolean onTouch(View v,MotionEvent e){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:sx=e.getRawX();sy=e.getRawY();ox=layout.x;oy=layout.y;moving=false;return true;case MotionEvent.ACTION_MOVE:int dx=(int)(e.getRawX()-sx),dy=(int)(e.getRawY()-sy);if(Math.abs(dx)+Math.abs(dy)>dp(6))moving=true;if(moving){layout.x=Math.max(0,Math.min(getResources().getDisplayMetrics().widthPixels-overlay.getWidth(),ox+dx));layout.y=Math.max(0,Math.min(getResources().getDisplayMetrics().heightPixels-overlay.getHeight()-dp(64),oy+dy));wm.updateViewLayout(overlay,layout);}return true;case MotionEvent.ACTION_UP:if(moving)prefs.edit().putInt("x",layout.x).putInt("y",layout.y).apply();else if(failed){failed=false;calculate();}return true;case MotionEvent.ACTION_CANCEL:return true;}return false;}});
    }
    private void show(String title, String detail) { show(title, detail, Double.NaN); }

    private void show(String title, String detail, double bearing) {
        routeBearing = bearing;
        if (primary != null) {
            primary.setText(title);
            secondary.setText(detail);
        }
        refreshDirection();
    }
    @Override public void onLocationChanged(Location location) {
        if (!active || !isFresh(location, MAX_FIX_AGE_NS)) return;
        if (!isPrecise(location)) {
            if (impreciseFix == null || location.getElapsedRealtimeNanos() > impreciseFix.getElapsedRealtimeNanos()) {
                impreciseFix = location;
            }
            refreshLocationStatus();
            return;
        }
        if (fix != null && location.getElapsedRealtimeNanos() - fix.getElapsedRealtimeNanos() < MIN_FIX_INTERVAL_NS) return;
        fix = location;
        if (declinationAt == 0 || location.getElapsedRealtimeNanos() - declinationAt > DECLINATION_INTERVAL_NS) {
            float altitude = location.hasAltitude() ? (float) location.getAltitude() : 0;
            declination = new GeomagneticField((float) location.getLatitude(), (float) location.getLongitude(),
                altitude, location.getTime()).getDeclination();
            declinationAt = location.getElapsedRealtimeNanos();
        }
        updateRouteForFix();
    }

    private void updateRouteForFix() {
        if (!isFresh(fix, MAX_FIX_AGE_NS)) {
            refreshLocationStatus();
            return;
        }
        if (route == null) {
            if (!failed) calculate();
            return;
        }
        if (calculating) return;
        Location l = fix;
        Route.Point p=new Route.Point(l.getLatitude(),l.getLongitude());Route.Match m=route.match(p,progress,initial);double threshold=Math.max(mode==0?35:45,l.getAccuracy()*1.6);
        if(m.cross()>threshold){if(++offCount>=3&&SystemClock.elapsedRealtime()-lastRequest>15000){show("経路を再探索中","ルートから外れました");calculate();}else show("経路を確認中","GPSまたは経路のずれを確認");return;}offCount=0;initial=false;progress=Math.max(progress,m.along());double remaining=Math.max(0,route.length()-progress);
        if(remaining<18&&Route.distance(p,route.points.get(route.points.size()-1))<Math.max(15,l.getAccuracy())&&l.getAccuracy()<25){if(++arrivalCount>=3){boolean near=Route.distance(p,destination)<Math.max(25,l.getAccuracy());show(near?"到着しました":"経路の終点に到着",near?"案内を終了します":"目的地までは周囲を確認してください");vibrate();active=false;ui.postDelayed(this::stopSelf,3500);return;}}else arrivalCount=0;
        Route.Turn t=route.next(progress);double d=Math.max(0,route.at(t.index())-progress);String label=Route.label(t.code());String detail="残り "+format(remaining)+(destName==null||destName.isBlank()?"":" · "+destName);boolean far=d>45&&!t.code().equals("DEST")&&!t.code().equals("BL")&&!t.code().equals("OFFR");
        show((far ? "次の案内まで " : Route.arrow(t.code()) + " " + label + " ") + format(d),
            far ? "次：" + label + " · " + detail : detail, route.bearingAhead(progress));
        if(!t.code().equals("DEST")&&!t.code().equals("C")&&d<(mode==0?20:35)&&alerted!=t.index()){alerted=t.index();vibrate();}
    }
    private void calculate(){if(!active||calculating)return;if(!isFresh(fix,MAX_FIX_AGE_NS)){refreshLocationStatus();return;}calculating=true;lastRequest=SystemClock.elapsedRealtime();int g=++generation;Location l=fix;Route.Point dest=destination;int m=mode;show("経路探索中","BRouterでオフライン計算");worker.execute(()->{try{Route result=EmbeddedRouter.calculate(this,new Route.Point(l.getLatitude(),l.getLongitude()),dest,m);ui.post(()->{if(!active||g!=generation)return;calculating=false;failed=false;route=result;progress=0;initial=true;offCount=arrivalCount=0;alerted=-1;updateRouteForFix();});}catch(Exception e){ui.post(()->{if(!active||g!=generation)return;calculating=false;failed=true;route=null;show("経路探索に失敗（タップで再試行）","経路データの範囲を確認");Toast.makeText(this,"BRouter: "+e.getMessage(),Toast.LENGTH_LONG).show();});}});}
    private String format(double meters){return meters>=1000?String.format(java.util.Locale.JAPAN,"%.1fkm",meters/1000):Math.round(meters)+"m";}
    private void vibrate(){try{Vibrator v=getSystemService(VibratorManager.class).getDefaultVibrator();v.vibrate(VibrationEffect.createOneShot(120,VibrationEffect.DEFAULT_AMPLITUDE));}catch(Exception ignored){}}
    @Override public void onProviderEnabled(String provider) {
        if (!isFresh(fix, MAX_FIX_AGE_NS)) locationStartedAt = SystemClock.elapsedRealtimeNanos();
        refreshLocationStatus();
    }
    @Override public void onProviderDisabled(String provider) { refreshLocationStatus(); }
    @Override public void onDestroy(){active=false;generation++;if(sensorManager!=null)sensorManager.unregisterListener(this);ui.removeCallbacksAndMessages(null);if(lm!=null)lm.removeUpdates(this);if(overlay!=null)try{wm.removeView(overlay);}catch(Exception ignored){}worker.shutdownNow();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
