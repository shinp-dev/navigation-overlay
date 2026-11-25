package com.shinpstudio.navoverlay;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends Activity {
    private static final int MAX_DESTINATION_RESULTS = 5;
    private static final long SEARCH_TIMEOUT_MS = 15_000L;
    private static final String SEARCH_ERROR = "地名検索に失敗しました。通信状況を確認してください。";

    EditText name,coords; Spinner mode,size; LinearLayout results; android.content.SharedPreferences prefs;
    JSONArray places=new JSONArray();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Button searchButton;
    private TextView searchStatus;
    private Runnable searchTimeout;
    private long searchGeneration;
    private ExecutorService legacyGeocoder;
    private Future<?> legacySearch;

    int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    TextView text(String s,int sp){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setPadding(0,dp(5),0,dp(5));return t;}
    Button button(LinearLayout root,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v->action.run());root.addView(b);return b;}
    @Override public void onCreate(Bundle state){super.onCreate(state);prefs=getSharedPreferences("nav",0);try{places=new JSONArray(prefs.getString("places","[]"));}catch(Exception ignored){}
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(18),dp(18),dp(24));scroll.addView(root);setContentView(scroll);
        root.addView(text("ナビオーバーレイ",26));root.addView(text("BRouter内蔵 / オフライン経路探索",15));
        root.addView(text("目的地の名前・住所", 15));
        name = new EditText(this);
        name.setSingleLine();
        name.setHint("例：東京駅、東京都千代田区丸の内");
        name.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        name.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            searchDestination();
            return true;
        });
        root.addView(name);
        searchButton = button(root, "地名・住所を検索", this::searchDestination);
        searchStatus = text("", 13);
        searchStatus.setVisibility(View.GONE);
        searchStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(searchStatus);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results);
        root.addView(text("目的地の緯度, 経度",15));coords=new EditText(this);coords.setSingleLine();coords.setHint("35.000000, 139.000000");root.addView(coords);
        name.setText(prefs.getString("name", ""));
        coords.setText(prefs.getString("coords", ""));
        watchInput(name, () -> {
            resetDestinationSearch();
            renderPlaces();
        });
        watchInput(coords, this::resetDestinationSearch);
        renderPlaces();
        button(root,"目的地を登録",()->{try{Route.Point p=parse(coords.getText().toString());String n=name.getText().toString().trim();if(n.isEmpty())throw new IllegalArgumentException("名前を入力してください");savePlace(n,p);renderPlaces();toast("登録しました");}catch(Exception e){toast(e.getMessage());}});
        button(root,"目的地CSVを取り込む",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,31);});
        root.addView(text("移動方法",15));mode=spinner(new String[]{"徒歩","自転車"});root.addView(mode);mode.setSelection(prefs.getInt("mode",0));
        root.addView(text("文字サイズ",15));size=spinner(new String[]{"小","標準","大"});root.addView(size);size.setSelection(prefs.getInt("size",1));
        button(root,"ナビ開始",this::startNav);button(root,"ナビ終了（枠を消す）",()->stopService(new Intent(this,NavService.class)));button(root,"オーバーレイ権限設定",()->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))));
        button(root, "ヘルプ", this::showNavigationHelp);
        root.addView(text("経路データはビルド時に取得済み。初回の経路探索時に端末内に展開します。通信なしで利用できます。道路標識・実際の道路状況を優先してください。\n経路データ © OpenStreetMap contributors",13));
        Uri uri=getIntent().getData();if(uri!=null&&"geo".equals(uri.getScheme())){try{String s=uri.getSchemeSpecificPart();String base=s.split("\\?",2)[0];if(base.equals("0,0")&&s.contains("q=")){base=Uri.decode(s.substring(s.indexOf("q=")+2).split("&",2)[0]).split("\\(",2)[0];}Route.Point p=parse(base);coords.setText(p.lat()+", "+p.lon());name.setText("");}catch(Exception e){toast("座標付きgeoリンクのみ対応");}}
    }

    private void showNavigationHelp() {
        new AlertDialog.Builder(this)
            .setTitle("ナビのヘルプ")
            .setMessage("「進む向き」の矢印は、BRouterが計算した経路の少し先を、スマホから見た向きで示します。\n\n"
                + "たとえば目的地が正面にあっても、道がつながっておらず右から回り込む経路なら、矢印は右を示します。\n\n"
                + "矢印の下には、次の曲がり方や距離を表示します。現地の標識や通行状況を優先してください。")
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void watchInput(EditText field, Runnable changed) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                changed.run();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
    }

    private void searchDestination() {
        if (searchTimeout != null) return;
        String query = name.getText().toString().trim();
        if (query.isEmpty()) {
            showSearchStatus("目的地の名前や住所を入力してください。");
            return;
        }
        if (!Geocoder.isPresent()) {
            showSearchStatus("この端末では地名検索を利用できません。緯度・経度を入力してください。");
            return;
        }

        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(name.getWindowToken(), 0);
        long request = ++searchGeneration;
        searchTimeout = () -> finishDestinationSearch(request, null, "地名検索がタイムアウトしました。");
        ui.postDelayed(searchTimeout, SEARCH_TIMEOUT_MS);
        searchButton.setEnabled(false);
        showSearchStatus("目的地を検索中…");

        Geocoder geocoder = new Geocoder(getApplicationContext());
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                geocoder.getFromLocationName(query, MAX_DESTINATION_RESULTS, new Geocoder.GeocodeListener() {
                    @Override public void onGeocode(List<Address> addresses) {
                        ui.post(() -> finishDestinationSearch(request, addresses, null));
                    }
                    @Override public void onError(String message) {
                        ui.post(() -> finishDestinationSearch(request, null, SEARCH_ERROR));
                    }
                });
            } else {
                if (legacyGeocoder == null) legacyGeocoder = Executors.newSingleThreadExecutor();
                legacySearch = legacyGeocoder.submit(() -> {
                    try {
                        List<Address> addresses = geocoder.getFromLocationName(query, MAX_DESTINATION_RESULTS);
                        ui.post(() -> finishDestinationSearch(request, addresses, null));
                    } catch (IOException | RuntimeException failure) {
                        ui.post(() -> finishDestinationSearch(request, null, SEARCH_ERROR));
                    }
                });
            }
        } catch (RuntimeException failure) {
            finishDestinationSearch(request, null, SEARCH_ERROR);
        }
    }

    private void finishDestinationSearch(long request, List<Address> addresses, String error) {
        if (request != searchGeneration || searchTimeout == null || isFinishing() || isDestroyed()) return;
        resetDestinationSearch();
        if (error != null) {
            showSearchStatus(error);
            return;
        }

        Map<Route.Point, String> suggestions = new LinkedHashMap<>();
        if (addresses != null) {
            for (Address address : addresses) {
                if (address == null || !address.hasLatitude() || !address.hasLongitude()) continue;
                try {
                    Route.Point point = new Route.Point(address.getLatitude(), address.getLongitude());
                    Set<String> lines = new LinkedHashSet<>();
                    String feature = address.getFeatureName();
                    if (feature != null && !feature.trim().isEmpty()) lines.add(feature.trim());
                    for (int i = 0; i <= address.getMaxAddressLineIndex(); i++) {
                        String line = address.getAddressLine(i);
                        if (line != null && !line.trim().isEmpty()) lines.add(line.trim());
                    }
                    String label = lines.isEmpty() ? coordinateText(point) : String.join("\n", lines);
                    suggestions.putIfAbsent(point, label);
                } catch (IllegalArgumentException invalidCoordinates) {
                    // Skip unusable coordinates returned by the device provider.
                }
            }
        }
        if (suggestions.isEmpty()) {
            showSearchStatus("目的地が見つかりません。市区町村名や住所を加えて検索してください。");
            return;
        }

        List<Route.Point> points = new ArrayList<>(suggestions.keySet());
        if (points.size() == 1) {
            applyDestination(points.get(0), suggestions.get(points.get(0)));
            return;
        }
        String[] labels = points.stream()
            .map(point -> suggestions.get(point) + "\n" + coordinateText(point))
            .toArray(String[]::new);
        new AlertDialog.Builder(this)
            .setTitle("目的地の候補")
            .setItems(labels, (dialog, index) -> {
                Route.Point point = points.get(index);
                applyDestination(point, suggestions.get(point));
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static String coordinateText(Route.Point point) {
        return String.format(Locale.ROOT, "%.6f, %.6f", point.lat(), point.lon());
    }

    private void applyDestination(Route.Point point, String label) {
        coords.setText(coordinateText(point));
        showSearchStatus(label);
    }

    private void showSearchStatus(String message) {
        searchStatus.setText(message);
        searchStatus.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void resetDestinationSearch() {
        if (searchTimeout != null) {
            ui.removeCallbacks(searchTimeout);
            searchTimeout = null;
            searchButton.setEnabled(true);
        }
        if (legacySearch != null) {
            legacySearch.cancel(true);
            legacySearch = null;
        }
        showSearchStatus("");
    }

    @Override protected void onDestroy() {
        resetDestinationSearch();
        if (legacyGeocoder != null) legacyGeocoder.shutdownNow();
        super.onDestroy();
    }

    Spinner spinner(String[] a){Spinner s=new Spinner(this);ArrayAdapter<String> x=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,a);s.setAdapter(x);return s;}
    static Route.Point parse(String s){String[] a=s.trim().split("[,，\\s]+",-1);if(a.length!=2)throw new IllegalArgumentException("緯度, 経度を入力してください");return new Route.Point(Double.parseDouble(a[0]),Double.parseDouble(a[1]));}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    void savePlace(String n,Route.Point p)throws JSONException{for(int i=places.length()-1;i>=0;i--)if(places.getJSONObject(i).optString("name").equals(n))places.remove(i);places.put(new JSONObject().put("name",n).put("lat",p.lat()).put("lon",p.lon()));prefs.edit().putString("places",places.toString()).apply();}
    void renderPlaces(){results.removeAllViews();String q=name.getText().toString().toLowerCase(Locale.ROOT);int count=0;for(int i=places.length()-1;i>=0&&count<5;i--){JSONObject o=places.optJSONObject(i);if(o==null)continue;String n=o.optString("name");if(!n.toLowerCase(Locale.ROOT).contains(q))continue;count++;button(results,n,()->{name.setText(n);coords.setText(o.optDouble("lat")+", "+o.optDouble("lon"));});}}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req!=31||result!=RESULT_OK||data==null)return;int ok=0,invalid=0;try(BufferedReader r=new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(data.getData()),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null){if(line.isBlank()||line.startsWith("#")||line.equals("name,lat,lon"))continue;String[] a=line.split(",",-1);try{if(a.length!=3||a[0].isBlank())throw new IllegalArgumentException();savePlace(a[0].trim(),new Route.Point(Double.parseDouble(a[1]),Double.parseDouble(a[2])));ok++;}catch(Exception e){invalid++;}if(ok+invalid>=10000)break;}renderPlaces();toast("登録 "+ok+"件 / 不正 "+invalid+"件");}catch(Exception e){toast("CSV読込エラー: "+e.getMessage());}}
    void startNav(){if(searchTimeout!=null){toast("目的地の検索結果を待ってください");return;}try{Route.Point p=parse(coords.getText().toString());if(!Settings.canDrawOverlays(this)){toast("オーバーレイ権限を許可後、再度開始してください");startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));return;}if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},10);return;}if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);return;}
        prefs.edit().putString("name",name.getText().toString()).putString("coords",coords.getText().toString()).putInt("mode",mode.getSelectedItemPosition()).putInt("size",size.getSelectedItemPosition()).apply();Intent i=new Intent(this,NavService.class).putExtra("lat",p.lat()).putExtra("lon",p.lon()).putExtra("name",name.getText().toString()).putExtra("mode",mode.getSelectedItemPosition()).putExtra("size",size.getSelectedItemPosition());startForegroundService(i);moveTaskToBack(true);
    }catch(Exception e){toast("開始できません: "+e.getMessage());}}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==10||r==11)toast("許可後、ナビ開始を押してください");}
}
