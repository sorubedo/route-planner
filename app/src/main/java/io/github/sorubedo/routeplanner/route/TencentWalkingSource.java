package io.github.sorubedo.routeplanner.route;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.tencent.lbssearch.TencentSearch;
import com.tencent.lbssearch.httpresponse.HttpResponseListener;
import com.tencent.lbssearch.object.param.WalkingParam;
import com.tencent.lbssearch.object.result.WalkingResultObject;
import com.tencent.tencentmap.mapsdk.maps.model.LatLng;
import com.tencent.tencentmap.mapsdk.maps.TencentMapInitializer;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.sorubedo.routeplanner.route.RoutePath.Point;

/** SDK walking requests. Public SDK has no cancel handle; closing invalidates all callbacks. */
public final class TencentWalkingSource implements AutoRouteJob.WalkingSource {
    private static final long TTL_MS=86_400_000;
    private static final ExecutorService IO=Executors.newFixedThreadPool(2);
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private record Cached(long timestamp,List<List<Point>> routes) {}
    private static final Map<String,Cached> MEMORY=Collections.synchronizedMap(new LinkedHashMap<>(128,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Cached> entry) {return size()>128;}
    });
    private final Context context;
    private final boolean useCache;
    private final String providedKey;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final CompletableFuture<AutoRouteJob.WalkingResult> future=new CompletableFuture<>();
    private long until;
    public TencentWalkingSource(Context context,String apiKey,boolean useCache) {this.context=context.getApplicationContext();this.providedKey=apiKey;this.useCache=useCache;}
    @Override public CompletableFuture<AutoRouteJob.WalkingResult> request(Point from,Point to,long deadlineNanos) {
        until=deadlineNanos;
        IO.execute(()->{
            try {
                Point a=AutoRoutePlanner.wgsToGcj(from),b=AutoRoutePlanner.wgsToGcj(to);
                String apiKey=providedKey;
                if(apiKey==null || apiKey.isBlank()) throw new IOException("请先在地图设置中填写腾讯地图 Key");
                String cacheKey=hash(context.getPackageName()+"|"+apiKey+"|5.6.0|"+String.format(Locale.ROOT,"%.7f,%.7f|%.7f,%.7f",a.latitude,a.longitude,b.latitude,b.longitude));
                File directory=new File(context.getCacheDir(),"walking-v1"),file=new File(directory,cacheKey+".json");
                Cached cached=useCache?MEMORY.get(cacheKey):null;
                if(useCache && cached==null) cached=read(file);
                if(cached!=null && fresh(cached) && live()) {
                    MEMORY.put(cacheKey,cached);Log.i("RouteWalking","walking segment cache hit");
                    future.complete(new AutoRouteJob.WalkingResult(cached.routes(),true));return;
                }
                final String requestKey=apiKey;
                MAIN.post(()->{
                    if(!live())return;
                    try {
                        // The map consent screen is shown before any SDK request.
                        TencentMapInitializer.setAgreePrivacy(true);
                        new TencentSearch(context,requestKey,null).getRoutePlan(new WalkingParam(new LatLng(a.latitude,a.longitude),new LatLng(b.latitude,b.longitude)),
                            new HttpResponseListener<WalkingResultObject>() {
                                @Override public void onSuccess(int code,WalkingResultObject response) {
                                    if(!live())return;
                                    IO.execute(()->{
                                        try {
                                            if(!live())return;
                                            if(response==null || !response.isStatusOk() || response.result==null || response.result.routes==null)
                                                throw new IOException("腾讯步行服务错误（"+(response==null?code:response.status)+"）");
                                            List<List<Point>> routes=new ArrayList<>();int total=0;
                                            for(WalkingResultObject.Route route:response.result.routes) {
                                                if(routes.size()>=8)break;
                                                if(route.polyline==null || route.polyline.size()<2 || route.polyline.size()>5000)continue;
                                                List<Point> points=new ArrayList<>();
                                                for(LatLng p:route.polyline) points.add(AutoRoutePlanner.gcjToWgs(new Point(p.latitude,p.longitude)));
                                                total+=points.size();if(total>20000)break;routes.add(List.copyOf(points));
                                            }
                                            if(routes.isEmpty())throw new IOException("腾讯未返回步行路线");
                                            if(!live())return;
                                            Cached value=new Cached(System.currentTimeMillis(),List.copyOf(routes));MEMORY.put(cacheKey,value);
                                            write(directory,file,value);
                                            if(!live())return;
                                            Log.i("RouteWalking","SDK walking success: candidates="+routes.size()+" vertices="+total);
                                            future.complete(new AutoRouteJob.WalkingResult(value.routes(),false));
                                        }catch(Exception error) {if(live())future.completeExceptionally(error);}
                                    });
                                }
                                @Override public void onFailure(int code,String message,Throwable error) {
                                    if(live()) {
                                        Log.w("RouteWalking","SDK walking failure: code="+code);
                                        future.completeExceptionally(new IOException("腾讯步行服务错误（"+code+"）"));
                                    }
                                }
                            });
                    }catch(Exception error) {if(live())future.completeExceptionally(error);}
                });
            }catch(Exception error) {if(live())future.completeExceptionally(error);}
        });
        return future;
    }
    private boolean live() {return !closed.get() && !future.isDone() && System.nanoTime()<until;}
    private static boolean fresh(Cached value) {long age=System.currentTimeMillis()-value.timestamp();return age>=0 && age<TTL_MS;}
    private static String hash(String input) throws Exception {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static Cached read(File file) {
        try {
            if(!file.isFile() || file.length()>1_500_000)return null;
            JSONObject json=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
            JSONArray routes=json.getJSONArray("routes");if(routes.length()>8)return null;
            List<List<Point>> all=new ArrayList<>();int total=0;
            for(int i=0;i<routes.length();i++) {
                JSONArray route=routes.getJSONArray(i);if(route.length()>5000)return null;
                List<Point> points=new ArrayList<>();
                for(int j=0;j<route.length();j++){JSONArray p=route.getJSONArray(j);points.add(new Point(p.getDouble(0),p.getDouble(1)));}
                total+=points.size();if(total>20000)return null;all.add(List.copyOf(points));
            }
            return all.isEmpty()?null:new Cached(json.getLong("timestamp"),List.copyOf(all));
        }catch(Exception ignored) {return null;}
    }
    private static void write(File directory,File file,Cached cached) {
        File temp=null;
        try {
            if(!directory.exists() && !directory.mkdirs())return;
            JSONArray routes=new JSONArray();
            for(List<Point> path:cached.routes()) {JSONArray route=new JSONArray();for(Point p:path)route.put(new JSONArray().put(p.latitude).put(p.longitude));routes.put(route);}
            temp=File.createTempFile("walking-",".tmp",directory);
            java.nio.file.Files.write(temp.toPath(),new JSONObject().put("timestamp",cached.timestamp()).put("routes",routes).toString().getBytes(StandardCharsets.UTF_8));
            java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            File[] files=directory.listFiles((dir,name)->name.endsWith(".json"));
            if(files!=null && files.length>128) {
                Arrays.sort(files,Comparator.comparingLong(File::lastModified));
                for(int i=0;i<files.length-128;i++)files[i].delete();
            }
        }catch(Exception ignored) {if(temp!=null)temp.delete();}
    }
    @Override public void close() {closed.set(true);future.cancel(false);}
}
