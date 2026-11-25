package com.shinpstudio.navoverlay;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends Activity {
    EditText name,coords; Spinner mode,size; LinearLayout results; android.content.SharedPreferences prefs;
    JSONArray places=new JSONArray();
    int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    TextView text(String s,int sp){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setPadding(0,dp(5),0,dp(5));return t;}
    void button(LinearLayout root,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v->action.run());root.addView(b);}
    @Override public void onCreate(Bundle state){super.onCreate(state);prefs=getSharedPreferences("nav",0);try{places=new JSONArray(prefs.getString("places","[]"));}catch(Exception ignored){}
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(18),dp(18),dp(18),dp(24));scroll.addView(root);setContentView(scroll);
        root.addView(text("ナビオーバーレイ",26));root.addView(text("BRouter内蔵 / オフライン経路探索",15));
        root.addView(text("目的地の名前（登録済みの場所を検索）",15));name=new EditText(this);name.setSingleLine();name.setHint("例：自宅、駅、学校");root.addView(name);results=new LinearLayout(this);results.setOrientation(1);root.addView(results);
        root.addView(text("目的地の緯度, 経度",15));coords=new EditText(this);coords.setSingleLine();coords.setHint("35.000000, 139.000000");root.addView(coords);
        name.setText(prefs.getString("name",""));coords.setText(prefs.getString("coords",""));name.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){renderPlaces();}public void afterTextChanged(android.text.Editable e){}});renderPlaces();
        button(root,"目的地を登録",()->{try{Route.Point p=parse(coords.getText().toString());String n=name.getText().toString().trim();if(n.isEmpty())throw new IllegalArgumentException("名前を入力してください");savePlace(n,p);renderPlaces();toast("登録しました");}catch(Exception e){toast(e.getMessage());}});
        button(root,"目的地CSVを取り込む",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,31);});
        root.addView(text("移動方法",15));mode=spinner(new String[]{"徒歩","自転車"});root.addView(mode);mode.setSelection(prefs.getInt("mode",0));
        root.addView(text("文字サイズ",15));size=spinner(new String[]{"小","標準","大"});root.addView(size);size.setSelection(prefs.getInt("size",1));
        button(root,"ナビ開始",this::startNav);button(root,"ナビ終了（枠を消す）",()->stopService(new Intent(this,NavService.class)));button(root,"オーバーレイ権限設定",()->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))));
        root.addView(text("経路データはビルド時に取得済み。初回の経路探索時に端末内に展開します。通信なしで利用できます。道路標識・実際の道路状況を優先してください。\n経路データ © OpenStreetMap contributors",13));
        Uri uri=getIntent().getData();if(uri!=null&&"geo".equals(uri.getScheme())){try{String s=uri.getSchemeSpecificPart();String base=s.split("\\?",2)[0];if(base.equals("0,0")&&s.contains("q=")){base=Uri.decode(s.substring(s.indexOf("q=")+2).split("&",2)[0]).split("\\(",2)[0];}Route.Point p=parse(base);coords.setText(p.lat()+", "+p.lon());name.setText("");}catch(Exception e){toast("座標付きgeoリンクのみ対応");}}
    }
    Spinner spinner(String[] a){Spinner s=new Spinner(this);ArrayAdapter<String> x=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,a);s.setAdapter(x);return s;}
    static Route.Point parse(String s){String[] a=s.trim().split("[,，\\s]+",-1);if(a.length!=2)throw new IllegalArgumentException("緯度, 経度を入力してください");return new Route.Point(Double.parseDouble(a[0]),Double.parseDouble(a[1]));}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    void savePlace(String n,Route.Point p)throws JSONException{for(int i=places.length()-1;i>=0;i--)if(places.getJSONObject(i).optString("name").equals(n))places.remove(i);places.put(new JSONObject().put("name",n).put("lat",p.lat()).put("lon",p.lon()));prefs.edit().putString("places",places.toString()).apply();}
    void renderPlaces(){results.removeAllViews();String q=name.getText().toString().toLowerCase(Locale.ROOT);int count=0;for(int i=places.length()-1;i>=0&&count<5;i--){JSONObject o=places.optJSONObject(i);if(o==null)continue;String n=o.optString("name");if(!n.toLowerCase(Locale.ROOT).contains(q))continue;count++;button(results,n,()->{name.setText(n);coords.setText(o.optDouble("lat")+", "+o.optDouble("lon"));});}}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req!=31||result!=RESULT_OK||data==null)return;int ok=0,invalid=0;try(BufferedReader r=new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(data.getData()),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null){if(line.isBlank()||line.startsWith("#")||line.equals("name,lat,lon"))continue;String[] a=line.split(",",-1);try{if(a.length!=3||a[0].isBlank())throw new IllegalArgumentException();savePlace(a[0].trim(),new Route.Point(Double.parseDouble(a[1]),Double.parseDouble(a[2])));ok++;}catch(Exception e){invalid++;}if(ok+invalid>=10000)break;}renderPlaces();toast("登録 "+ok+"件 / 不正 "+invalid+"件");}catch(Exception e){toast("CSV読込エラー: "+e.getMessage());}}
    void startNav(){try{Route.Point p=parse(coords.getText().toString());if(!Settings.canDrawOverlays(this)){toast("オーバーレイ権限を許可後、再度開始してください");startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));return;}if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},10);return;}if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);return;}
        prefs.edit().putString("name",name.getText().toString()).putString("coords",coords.getText().toString()).putInt("mode",mode.getSelectedItemPosition()).putInt("size",size.getSelectedItemPosition()).apply();Intent i=new Intent(this,NavService.class).putExtra("lat",p.lat()).putExtra("lon",p.lon()).putExtra("name",name.getText().toString()).putExtra("mode",mode.getSelectedItemPosition()).putExtra("size",size.getSelectedItemPosition());startForegroundService(i);moveTaskToBack(true);
    }catch(Exception e){toast("開始できません: "+e.getMessage());}}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==10||r==11)toast("許可後、ナビ開始を押してください");}
}
