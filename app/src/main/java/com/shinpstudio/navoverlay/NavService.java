package com.shinpstudio.navoverlay;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.location.*;
import android.os.*;
import android.view.*;
import android.widget.*;

import java.util.concurrent.*;

public class NavService extends Service implements LocationListener {
    private static final String CHANNEL="offline_navigation";
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private boolean calculating,active,failed; private int generation,mode,size,offCount,arrivalCount; private long lastRequest; private int alerted=-1;
    private LocationManager lm; private WindowManager wm; private LinearLayout overlay; private TextView primary,secondary; private Route.Point destination; private Location fix; private Route route; private double progress; private boolean initial=true; private String destName=""; private WindowManager.LayoutParams layout;
    private final Runnable stale= new Runnable(){public void run(){if(active&&fix!=null&&!calculating&&SystemClock.elapsedRealtimeNanos()-fix.getElapsedRealtimeNanos()>15_000_000_000L)show("GPS信号待ち","位置が更新されていません");if(active)ui.postDelayed(this,5000);}};
    int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent i,int flags,int id){if(i==null||"STOP".equals(i.getAction())){stopSelf();return START_NOT_STICKY;}
        try{generation++;active=true;calculating=false;failed=false;route=null;fix=null;initial=true;progress=0;offCount=arrivalCount=0;alerted=-1;lastRequest=0;destination=new Route.Point(i.getDoubleExtra("lat",Double.NaN),i.getDoubleExtra("lon",Double.NaN));destName=i.getStringExtra("name");mode=i.getIntExtra("mode",0);size=i.getIntExtra("size",1);
            NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel(CHANNEL,"ナビ案内",NotificationManager.IMPORTANCE_LOW));PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,NavService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(com.shinpstudio.navoverlay.R.drawable.ic_nav).setContentTitle("オフラインナビ動作中").setContentText("GPSで現在地を取得しています").setContentIntent(open).addAction(new Notification.Action.Builder((android.graphics.drawable.Icon)null,"終了",stop).build()).setOngoing(true).build();startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            createOverlay();show("GPS取得中","位置が確定するまでお待ちください");
            lm=getSystemService(LocationManager.class);lm.removeUpdates(this);lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this,Looper.getMainLooper());if(!lm.isProviderEnabled(LocationManager.GPS_PROVIDER))show("GPSが無効","端末の位置情報を有効にしてください");
            ui.removeCallbacks(stale);ui.postDelayed(stale,5000);
        }catch(Exception e){Toast.makeText(this,"ナビ開始失敗: "+e.getMessage(),Toast.LENGTH_LONG).show();stopSelf();}return START_NOT_STICKY;
    }
    private void createOverlay(){wm=getSystemService(WindowManager.class);if(overlay!=null){wm.removeView(overlay);overlay=null;}overlay=new LinearLayout(this);overlay.setOrientation(LinearLayout.HORIZONTAL);overlay.setGravity(Gravity.CENTER_VERTICAL);overlay.setPadding(dp(10),dp(5),dp(4),dp(5));GradientDrawable bg=new GradientDrawable();bg.setColor(0xED17221E);bg.setCornerRadius(dp(12));bg.setStroke(dp(2),0xFF66D69B);overlay.setBackground(bg);
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(1);primary=new TextView(this);primary.setTextColor(Color.WHITE);primary.setTextSize(size==0?17:size==2?26:21);primary.setTypeface(null,Typeface.BOLD);secondary=new TextView(this);secondary.setTextColor(0xFFD2E4DA);secondary.setTextSize(size==0?11:size==2?16:13);labels.addView(primary);labels.addView(secondary);overlay.addView(labels);TextView close=new TextView(this);close.setText("×");close.setTextSize(25);close.setTextColor(Color.WHITE);close.setGravity(Gravity.CENTER);close.setContentDescription("ナビを終了");overlay.addView(close,new LinearLayout.LayoutParams(dp(42),dp(48)));close.setOnClickListener(v->stopSelf());
        layout=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);layout.gravity=Gravity.TOP|Gravity.LEFT;var prefs=getSharedPreferences("nav",0);layout.x=prefs.getInt("x",dp(12));layout.y=prefs.getInt("y",dp(48));wm.addView(overlay,layout);
        labels.setOnTouchListener(new View.OnTouchListener(){float sx,sy;int ox,oy;boolean moving;public boolean onTouch(View v,MotionEvent e){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:sx=e.getRawX();sy=e.getRawY();ox=layout.x;oy=layout.y;moving=false;return true;case MotionEvent.ACTION_MOVE:int dx=(int)(e.getRawX()-sx),dy=(int)(e.getRawY()-sy);if(Math.abs(dx)+Math.abs(dy)>dp(6))moving=true;if(moving){layout.x=Math.max(0,Math.min(getResources().getDisplayMetrics().widthPixels-overlay.getWidth(),ox+dx));layout.y=Math.max(0,Math.min(getResources().getDisplayMetrics().heightPixels-overlay.getHeight()-dp(64),oy+dy));wm.updateViewLayout(overlay,layout);}return true;case MotionEvent.ACTION_UP:if(moving)prefs.edit().putInt("x",layout.x).putInt("y",layout.y).apply();else if(failed){failed=false;calculate();}return true;case MotionEvent.ACTION_CANCEL:return true;}return false;}});
    }
    private void show(String a,String b){if(primary!=null){primary.setText(a);secondary.setText(b);}}
    @Override public void onLocationChanged(Location l){if(!active||l==null)return;if(l.getAccuracy()>45||SystemClock.elapsedRealtimeNanos()-l.getElapsedRealtimeNanos()>15_000_000_000L){if(!calculating)show("GPS精度低下","正確な位置を取得中");return;}fix=l;if(route==null){if(!failed)calculate();return;}if(calculating)return;
        Route.Point p=new Route.Point(l.getLatitude(),l.getLongitude());Route.Match m=route.match(p,progress,initial);double threshold=Math.max(mode==0?35:45,l.getAccuracy()*1.6);
        if(m.cross()>threshold){if(++offCount>=3&&SystemClock.elapsedRealtime()-lastRequest>15000){show("経路を再探索中","ルートから外れました");calculate();}else show("経路を確認中","GPSまたは経路のずれを確認");return;}offCount=0;initial=false;progress=Math.max(progress,m.along());double remaining=Math.max(0,route.length()-progress);
        if(remaining<18&&Route.distance(p,route.points.get(route.points.size()-1))<Math.max(15,l.getAccuracy())&&l.getAccuracy()<25){if(++arrivalCount>=3){boolean near=Route.distance(p,destination)<Math.max(25,l.getAccuracy());show(near?"到着しました":"経路の終点に到着",near?"案内を終了します":"目的地までは周囲を確認してください");vibrate();active=false;ui.postDelayed(this::stopSelf,3500);return;}}else arrivalCount=0;
        Route.Turn t=route.next(progress);double d=Math.max(0,route.at(t.index())-progress);String label=Route.label(t.code());String detail="残り "+format(remaining)+(destName==null||destName.isBlank()?"":" · "+destName);boolean far=d>45&&!t.code().equals("DEST")&&!t.code().equals("BL")&&!t.code().equals("OFFR");show((far?"↑ 直進 ":Route.arrow(t.code())+" "+label+" ")+format(d),far?"次："+label+" · "+detail:detail);if(!t.code().equals("DEST")&&!t.code().equals("C")&&d<(mode==0?20:35)&&alerted!=t.index()){alerted=t.index();vibrate();}
    }
    private void calculate(){if(!active||calculating||fix==null)return;calculating=true;lastRequest=SystemClock.elapsedRealtime();int g=++generation;Location l=fix;Route.Point dest=destination;int m=mode;show("経路探索中","BRouterでオフライン計算");worker.execute(()->{try{Route result=EmbeddedRouter.calculate(this,new Route.Point(l.getLatitude(),l.getLongitude()),dest,m);ui.post(()->{if(!active||g!=generation)return;calculating=false;failed=false;route=result;progress=0;initial=true;offCount=arrivalCount=0;alerted=-1;if(fix!=null)onLocationChanged(fix);});}catch(Exception e){ui.post(()->{if(!active||g!=generation)return;calculating=false;failed=true;route=null;show("経路探索に失敗（タップで再試行）","経路データの範囲を確認");Toast.makeText(this,"BRouter: "+e.getMessage(),Toast.LENGTH_LONG).show();});}});}
    private String format(double meters){return meters>=1000?String.format(java.util.Locale.JAPAN,"%.1fkm",meters/1000):Math.round(meters)+"m";}
    private void vibrate(){try{Vibrator v=getSystemService(VibratorManager.class).getDefaultVibrator();v.vibrate(VibrationEffect.createOneShot(120,VibrationEffect.DEFAULT_AMPLITUDE));}catch(Exception ignored){}}
    @Override public void onProviderDisabled(String p){show("GPSが無効","位置情報を有効にしてください");}
    @Override public void onDestroy(){active=false;generation++;ui.removeCallbacksAndMessages(null);if(lm!=null)lm.removeUpdates(this);if(overlay!=null)try{wm.removeView(overlay);}catch(Exception ignored){}worker.shutdownNow();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
