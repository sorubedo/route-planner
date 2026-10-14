package io.github.sorubedo.routeplanner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.tencent.tencentmap.mapsdk.maps.*;
import com.tencent.tencentmap.mapsdk.maps.model.*;
import io.github.sorubedo.routeplanner.route.*;
import io.github.sorubedo.routeplanner.route.RoutePath.Point;
import io.github.sorubedo.routeplanner.route.AutoRoutePlanner.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android route editor with a native Tencent map. */
public final class MainActivity extends Activity {
    private static final String ORIGIN = "https://route.planner/assets/";
    private static final int MAP_SETTINGS = 1;
    private String mapKey = "";
    private WebView web;
    private MapView mapView;
    private TencentMap map;
    private FrameLayout mapHost;
    private TextView hint;
    private Marker runner;
    private volatile String center = "[40.00045,116.33205]"; // Initial GCJ-02; replaced by camera callback.
    private String latestDrawing, latestCamera;
    private float latestZoom;
    private final Map<String, BitmapDescriptor> icons = new HashMap<>();
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<Integer, PlanningRequest> requests = new ConcurrentHashMap<>();
    private final Handler toastHandler=new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> toastQueue=new ArrayDeque<>();
    private boolean showingToast;
    private void showNextToast(){
        String text=toastQueue.pollFirst();
        if(text==null || isDestroyed()){showingToast=false;return;}
        showingToast=true;Toast.makeText(this,text,Toast.LENGTH_LONG).show();
        toastHandler.postDelayed(this::showNextToast,3600);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        mapHost = new FrameLayout(this);
        root.addView(mapHost, new LinearLayout.LayoutParams(-1, 0, .46f));
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setUserAgentString(web.getSettings().getUserAgentString() + " RoutePlanner/1.0.0");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith(ORIGIN);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest r) {
                String url = r.getUrl().toString();
                if (!url.startsWith(ORIGIN)) return null;
                String path = url.substring(ORIGIN.length()).split("\\?", 2)[0];
                if (path.contains("..")) return new WebResourceResponse("text/plain", "utf-8", null);
                try {
                    String mime = path.endsWith(".js") ? "application/javascript" : path.endsWith(".json") ? "application/json" : path.endsWith(".css") ? "text/css" : "text/html";
                    return new WebResourceResponse(mime, "utf-8", getAssets().open(path));
                } catch (IOException e) { return new WebResourceResponse("text/plain", "utf-8", null); }
            }
        });
        web.addJavascriptInterface(new Bridge(), "Native");
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, .54f));
        setContentView(root);
        web.loadUrl(ORIGIN + "index.html");
        try { mapKey = MapKeyStore.get(this); }
        catch (Exception error) { Toast.makeText(this, "无法读取地图 Key，请在地图设置中重新填写", Toast.LENGTH_LONG).show(); }
        if (mapKey.isEmpty()) {
            LinearLayout setup = new LinearLayout(this); setup.setGravity(Gravity.CENTER); setup.setOrientation(LinearLayout.VERTICAL);
            TextView message = new TextView(this); message.setText("填写自己的腾讯地图 Key 后即可打开地图"); setup.addView(message);
            android.widget.Button settings = new android.widget.Button(this); settings.setText("地图设置");
            settings.setOnClickListener(v -> openMapSettings()); setup.addView(settings);
            mapHost.addView(setup, new FrameLayout.LayoutParams(-1,-1));
        } else if (getPreferences(MODE_PRIVATE).getBoolean("tencent-map-consent", false)) createMap();
        else new AlertDialog.Builder(this).setTitle("使用腾讯地图")
            .setMessage("地图由腾讯地图提供，联网加载底图时会发送地图范围与服务所需设备信息。本应用不申请定位权限。步行规划会向腾讯发送每段起终点坐标。")
            .setPositiveButton("同意并打开", (d,w) -> { getPreferences(MODE_PRIVATE).edit().putBoolean("tencent-map-consent", true).apply(); createMap(); })
            .setNegativeButton("退出", (d,w) -> finish()).setCancelable(false).show();
    }
    private void openMapSettings() {
        startActivityForResult(new Intent(this, MapKeyActivity.class), MAP_SETTINGS);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MAP_SETTINGS && resultCode == RESULT_OK) recreate();
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void createMap() {
        if (mapKey.isEmpty() || isDestroyed()) return;
        TencentMapInitializer.setAgreePrivacy(true);
        hint = new TextView(this);
        File cache = new File(getCacheDir(), "tencent-map"), local = new File(getFilesDir(), "tencent-map");
        cache.mkdirs(); local.mkdirs();
        TencentMapOptions options = new TencentMapOptions().setMapKey(mapKey).setCustomCacheRootPath(cache.getAbsolutePath()).setCustomLocalPath(local.getAbsolutePath())
            .setOnAuthCallback(new TencentMap.OnAuthResultCallback() {
                @Override public void onAuthSuccess() { Log.i("RoutePlannerMap", "Tencent map authorization succeeded"); }
                @Override public void onAuthFail(int code, String error) {
                    Log.e("RoutePlannerMap", "Tencent map authorization failed: " + code);
                    runOnUiThread(() -> hint.setText("腾讯地图鉴权失败（" + code + "），请在地图设置中核对 Key、包名与服务权限"));
                }
            }).setOnMapLoadedCallback(() -> {
                Log.i("RoutePlannerMap", "Tencent base map loaded");
                runOnUiThread(() -> { if (latestCamera != null) camera(latestCamera, latestZoom); });
            });
        mapView = new MapView(this, options); map = mapView.getMap();
        map.getUiSettings().setRotateGesturesEnabled(false);
        map.getUiSettings().setTiltGesturesEnabled(false);
        mapHost.addView(mapView, new FrameLayout.LayoutParams(-1, -1));
        hint.setText("腾讯地图 · 点击地图绘制区域或插入打卡点");
        hint.setTextColor(Color.rgb(30,85,65)); hint.setTextSize(12); hint.setBackgroundColor(0xeef4f8f5);
        hint.setPadding(dp(10),dp(6),dp(10),dp(6));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START); hp.setMargins(dp(8),dp(8),dp(8),0);
        mapHost.addView(hint,hp);
        TextView expand = new TextView(this); expand.setText("展开地图"); expand.setTextSize(12); expand.setTextColor(Color.rgb(30,85,65));
        expand.setBackgroundColor(0xeef4f8f5); expand.setPadding(dp(10),dp(8),dp(10),dp(8));
        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END); ep.setMargins(0,0,dp(10),dp(28));
        mapHost.addView(expand,ep);
        expand.setOnClickListener(v -> {
            boolean bigger = ((LinearLayout.LayoutParams)mapHost.getLayoutParams()).weight < .6f;
            mapHost.setLayoutParams(new LinearLayout.LayoutParams(-1,0,bigger?.68f:.46f));
            web.setLayoutParams(new LinearLayout.LayoutParams(-1,0,bigger?.32f:.54f));
            expand.setText(bigger?"收起地图":"展开地图");
        });
        map.setOnMapClickListener(p -> js("nativeMapClick", p.latitude, p.longitude));
        map.setOnCameraChangeListener(new TencentMap.OnCameraChangeListener() {
            private void update(CameraPosition p) { center = "[" + p.target.latitude + "," + p.target.longitude + "]"; }
            @Override public void onCameraChange(CameraPosition p) { update(p); }
            @Override public void onCameraChangeFinished(CameraPosition p) { update(p); }
        });
        mapView.onStart(); mapView.onResume();
        if(latestDrawing!=null)draw(latestDrawing);
        if(latestCamera!=null)camera(latestCamera,latestZoom);
    }
    private void js(String function, Object... args) {
        JSONArray a = new JSONArray(); for(Object o:args)a.put(o);
        String values = a.toString(); values = values.substring(1,values.length()-1);
        final String call = "window." + function + " && window." + function + "(" + values + ")";
        runOnUiThread(() -> { if(!isDestroyed())web.evaluateJavascript(call,null); });
    }
    private static LatLng point(JSONArray a) throws Exception { return new LatLng(a.getDouble(0),a.getDouble(1)); }
    private static List<LatLng> points(JSONArray a) throws Exception {
        List<LatLng> list = new ArrayList<>(); if(a==null)return list;
        if(a.length()>20000)throw new IOException("地图路径过大");
        for(int i=0;i<a.length();i++)list.add(point(a.getJSONArray(i))); return list;
    }
    private void line(JSONArray a, int color, float width) throws Exception {
        List<LatLng> list=points(a);if(list.size()>1)map.addPolyline(new PolylineOptions().addAll(list).color(color).width(dp(width)).lineCap(true).zIndex(5));
    }
    private BitmapDescriptor icon(String label,int color) {
        String key=label+":"+color;
        if(!icons.containsKey(key)) {
            int size=dp(28); Bitmap b=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888); Canvas c=new Canvas(b);
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.WHITE);c.drawCircle(size/2f,size/2f,size/2f,p);
            p.setColor(color);c.drawCircle(size/2f,size/2f,size/2f-dp(2),p);
            p.setColor(Color.WHITE);p.setTextSize(dp(14));p.setTextAlign(Paint.Align.CENTER);p.setFakeBoldText(true);
            c.drawText(label,size/2f,size/2f-(p.ascent()+p.descent())/2,p);
            icons.put(key,BitmapDescriptorFactory.fromBitmap(b));
        }
        return icons.get(key);
    }
    private Marker marker(LatLng p,String label,int color) {
        return map.addMarker(new MarkerOptions(p).icon(icon(label,color)).anchor(.5f,.5f).infoWindowEnable(false).zIndex(10));
    }
    private void polygon(JSONArray a,int stroke,int fill) throws Exception {
        List<LatLng> list=points(a);if(list.size()>=3)map.addPolygon(new PolygonOptions().addAll(list).strokeColor(stroke).strokeWidth(dp(1.5f)).fillColor(fill).zIndex(1));
    }
    private void draw(String json) {
        latestDrawing=json;if(map==null)return;
        try {
            JSONObject data=new JSONObject(json);map.clearAllOverlays();runner=null;
            JSONArray regions=data.getJSONArray("regions");
            for(int i=0;i<regions.length();i++) {
                JSONObject r=regions.getJSONObject(i);boolean active=r.optBoolean("available",true);
                polygon(r.getJSONArray("polygon"),active?0xff308d65:0xff829088,active?0x18308d65:0x18829088);
            }
            JSONArray draft=data.optJSONArray("draft");line(draft,0xff9a7029,2);
            if(draft!=null)for(int i=0;i<draft.length();i++)marker(point(draft.getJSONArray(i)),""+(i+1),0xff9a7029);
            JSONArray cps=data.getJSONArray("checkpoints");
            for(int i=0;i<cps.length();i++)marker(point(cps.getJSONObject(i).getJSONArray("position")),""+(i+1),0xff2377b8);
            JSONArray segments=data.optJSONArray("segments");
            if(segments!=null && segments.length()>0) {
                for(int i=0;i<segments.length();i++) {JSONObject segment=segments.getJSONObject(i);line(segment.getJSONArray("route"),segment.optString("mode").equals("roads")?0xff2377b8:0xffcc7826,4.5f);}
            } else line(data.optJSONArray("route"),data.optString("routeMode").equals("geometry")?0xffcc7826:0xff2377b8,4.5f);
            if(data.has("start"))marker(point(data.getJSONArray("start")),"起",0xff226e54);
            hint.setText("腾讯地图 · "+data.optString("hint","点击地图编辑点"));
        } catch(Exception e) { Log.e("RoutePlannerMap","Drawing failed",e); }
    }
    private void camera(String json,float zoom) {
        latestCamera=json;latestZoom=zoom;if(map==null)return;
        try {
            List<LatLng> list=points(new JSONArray(json));if(list.isEmpty())return;
            if(list.size()==1)map.moveCamera(CameraUpdateFactory.newLatLngZoom(list.get(0),zoom));
            else map.moveCamera(CameraUpdateFactory.newLatLngBounds(new LatLngBounds.Builder().include(list).build(),dp(32)));
        }catch(Exception e){Log.e("RoutePlannerMap","Camera failed",e);}
    }
    private final class PlanningRequest {
        final int id; final AtomicBoolean done=new AtomicBoolean(); volatile AutoRouteJob job;
        PlanningRequest(int id){this.id=id;}
        void finish(boolean ok,String data){
            if(!done.compareAndSet(false,true))return;
            requests.remove(id,this);js("nativePlanReply",id,ok,data);
        }
        void cancel(){done.set(true);requests.remove(id,this);AutoRouteJob current=job;if(current!=null)current.cancel();}
    }
    private static Point modelPoint(JSONArray p) throws Exception {return new Point(p.getDouble(0),p.getDouble(1));}
    private static JSONArray coordinates(List<Point> points) throws JSONException {JSONArray out=new JSONArray();for(Point p:points)out.put(new JSONArray().put(p.latitude).put(p.longitude));return out;}
    private Input planningInput(JSONObject config) throws Exception {
        JSONArray cps=config.getJSONArray("checkpoints"),regions=config.getJSONArray("regions");
        if(cps.length()>24 || regions.length()>8)throw new IOException("最多 8 个区域与 24 个打卡点");
        List<Checkpoint> checkpoints=new ArrayList<>();
        for(int i=0;i<cps.length();i++){JSONObject cp=cps.getJSONObject(i);checkpoints.add(new Checkpoint(modelPoint(cp.getJSONArray("position")),cp.optDouble("sort",i+1),cp.optBoolean("nfcEnable")));}
        List<List<Point>> candidates=new ArrayList<>();
        for(int i=0;i<regions.length();i++) {
            JSONObject region=regions.getJSONObject(i);if(!region.optBoolean("available",true))continue;
            JSONArray raw=region.getJSONArray("polygon");List<Point> poly=new ArrayList<>();for(int j=0;j<raw.length();j++)poly.add(modelPoint(raw.getJSONArray(j)));
            AutoRoutePlanner.validatePolygon(poly);
            if(checkpoints.stream().allMatch(cp->AutoRoutePlanner.inside(cp.position(),poly)))candidates.add(poly);
        }
        int target=config.getInt("targetMeters");
        if(regions.length()==0) {
            Point center=modelPoint(config.getJSONArray("center"));double e=Math.max(450,target/3.0+50)/111195,l=e/Math.cos(Math.toRadians(center.latitude));
            List<Point> poly=List.of(new Point(center.latitude-e,center.longitude-l),new Point(center.latitude-e,center.longitude+l),new Point(center.latitude+e,center.longitude+l),new Point(center.latitude+e,center.longitude-l));
            if(checkpoints.stream().allMatch(cp->AutoRoutePlanner.inside(cp.position(),poly)))candidates.add(poly);
        }
        if(candidates.isEmpty())throw new IOException("没有可容纳全部打卡点的启用区域");
        long seed=new java.security.SecureRandom().nextLong();Random random=new Random(seed);List<Point> poly=candidates.get(random.nextInt(candidates.size()));
        Point start=AutoRoutePlanner.randomStart(poly,random);
        return new Input(poly,start,checkpoints,config.optBoolean("ordered")?2:0,target,seed);
    }
    private String planJson(Input input,Plan plan) throws Exception {
        JSONArray warnings=new JSONArray(),segments=new JSONArray();int roads=0;
        for(int i=0;i<plan.segments().size();i++) {
            Segment s=plan.segments().get(i);if(s.mode().equals("roads"))roads++;else warnings.put("第 "+(i+1)+" 段采用围栏内折线："+s.reason());
            segments.put(new JSONObject().put("route",coordinates(s.route())).put("mode",s.mode()).put("reason",s.reason()).put("attempts",s.attempts()).put("cached",s.cached()));
        }
        if(!plan.mode().equals("roads"))warnings.put("几何段只校验围栏，需核对建筑、水域和门禁");
        if(input.checkpoints().stream().anyMatch(Checkpoint::nfc))warnings.put("含 NFC 点，仍需原验证");
        if(Math.abs(plan.path().length-input.targetMetres())>input.targetMetres()*.25)warnings.put("实际单圈长度受围栏、打卡点和道路限制，期望长度仅作参考");
        JSONArray checkpoints=new JSONArray();for(Checkpoint cp:input.checkpoints())checkpoints.put(new JSONObject().put("position",coordinates(List.of(cp.position())).get(0)).put("sort",cp.sort()).put("nfcEnable",cp.nfc()));
        return new JSONObject().put("schemaVersion",2).put("coordinateSystem","WGS84").put("roadProvider","tencent-sdk").put("mode",plan.mode())
            .put("route",coordinates(plan.route())).put("start",coordinates(List.of(input.start())).get(0)).put("polygon",coordinates(input.polygon()))
            .put("meters",plan.path().length).put("repeatedMeters",plan.repeatedMetres()).put("visits",new JSONArray(plan.visitOrder()))
            .put("checkpoints",checkpoints).put("warnings",warnings).put("segments",segments).put("roadSegments",roads).put("closed",true).toString();
    }
    public final class Bridge {
        @JavascriptInterface public void mapSettings() { runOnUiThread(() -> openMapSettings()); }
        @JavascriptInterface public String manual(String json){
            try {
                JSONArray input=new JSONArray(json);if(input.length()>200)throw new IOException("最多 200 个路线点");
                List<RoutePath.Point> anchors=new ArrayList<>();
                for(int i=0;i<input.length();i++){JSONArray p=input.getJSONArray(i);anchors.add(new RoutePath.Point(p.getDouble(0),p.getDouble(1)));}
                RoutePath path=new RoutePath(anchors);JSONArray route=new JSONArray();
                for(RoutePath.Point p:path.points)route.put(new JSONArray().put(p.latitude).put(p.longitude));
                return new JSONObject().put("route",route).put("meters",path.length).toString();
            }catch(Exception e){return "{\"error\":"+JSONObject.quote(e.getMessage()==null?"手动路线无效":e.getMessage())+"}";}
        }
        @JavascriptInterface public String center() {return center;}
        @JavascriptInterface public void draw(String json){if(json.length()<2_000_000)runOnUiThread(()->MainActivity.this.draw(json));}
        @JavascriptInterface public void camera(String json,double zoom){if(json.length()<2_000_000)runOnUiThread(()->MainActivity.this.camera(json,(float)zoom));}
        @JavascriptInterface public void runner(double lat,double lng,boolean visible){runOnUiThread(()->{
            if(map==null)return;
            if(!visible){if(runner!=null)runner.remove();runner=null;return;}
            LatLng p=new LatLng(lat,lng);if(runner==null)runner=marker(p,"跑",0xffbc4424);else runner.setPosition(p);
        });}
        @JavascriptInterface public void satellite(boolean on){runOnUiThread(()->{if(map!=null)map.setMapType(on?TencentMap.MAP_TYPE_SATELLITE:TencentMap.MAP_TYPE_NORMAL);});}
        @JavascriptInterface public void copy(String data){
            if(data.length()>2_000_000)return;
            runOnUiThread(()->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("路线规划 JSON",data));
                Toast.makeText(MainActivity.this,"规划 JSON 已复制",Toast.LENGTH_SHORT).show();});
        }
        @JavascriptInterface public void toast(String text){if(text.length()<200)runOnUiThread(()->{
            if(toastQueue.size()>=8)toastQueue.removeFirst();toastQueue.addLast(text);if(!showingToast)showNextToast();
        });}
        @JavascriptInterface public void cancelPlan(int id){PlanningRequest request=requests.get(id);if(request!=null)request.cancel();}
        @JavascriptInterface public void plan(String config,int id,boolean roads){
            if(config.length()>2_000_000){js("nativePlanReply",id,false,"规划输入过大");return;}
            if(roads && mapKey.isEmpty()){js("nativePlanReply",id,false,"请先在地图设置中填写 Key，或关闭“优先步行道路”进行几何规划");return;}
            for(PlanningRequest old:requests.values())old.cancel();
            PlanningRequest request=new PlanningRequest(id);requests.put(id,request);
            worker.execute(()->{
                try {
                    Input input=planningInput(new JSONObject(config));if(request.done.get())return;
                    if(!roads) {
                        var prepared=SegmentedRoutePlanner.prepare(input,System.nanoTime()+TimeUnit.SECONDS.toNanos(5));
                        request.finish(true,planJson(input,SegmentedRoutePlanner.assemble(prepared,prepared.legs())));return;
                    }
                    request.job=AutoRouteJob.start(input,n->new TencentWalkingSource(MainActivity.this,mapKey,n==1),worker,timer,progress->{
                        if(!request.done.get())js("nativePlanProgress",id,progress.attempt(),progress.completed(),progress.total());
                    },(plan,error)->{
                        try {request.finish(error==null,error==null?planJson(input,plan):"自动规划失败："+error.getMessage());}
                        catch(Exception e){request.finish(false,"规划结果无效");}
                    });
                    if(request.done.get())request.job.cancel();
                }catch(Exception e){request.finish(false,e.getMessage()==null?"自动规划失败":e.getMessage());}
            });
        }
    }
    @Override protected void onStart(){super.onStart();if(mapView!=null)mapView.onStart();}
    @Override protected void onResume(){super.onResume();web.onResume();if(mapView!=null)mapView.onResume();}
    @Override protected void onPause(){if(mapView!=null)mapView.onPause();web.onPause();super.onPause();}
    @Override protected void onStop(){if(mapView!=null)mapView.onStop();super.onStop();}
    @Override protected void onDestroy(){
        toastHandler.removeCallbacksAndMessages(null);toastQueue.clear();
        for(PlanningRequest r:requests.values())r.cancel();worker.shutdownNow();timer.shutdownNow();
        if(mapView!=null)mapView.onDestroy();web.destroy();super.onDestroy();
    }
}
