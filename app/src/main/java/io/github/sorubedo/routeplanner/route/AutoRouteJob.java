package io.github.sorubedo.routeplanner.route;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import io.github.sorubedo.routeplanner.route.RoutePath.Point;
import io.github.sorubedo.routeplanner.route.AutoRoutePlanner.*;

/** Three shared deadlines; successful legs survive retries and expired callbacks are ignored. */
public final class AutoRouteJob {
    public static final long ATTEMPT_BUDGET_MS=20_000;
    public static final int MAX_ATTEMPTS=3, MAX_IN_FLIGHT=3;
    public record WalkingResult(List<List<Point>> routes,boolean cached) {}
    public interface WalkingSource extends AutoCloseable {
        CompletableFuture<WalkingResult> request(Point from,Point to,long deadlineNanos);
        @Override void close();
    }
    public record Progress(int attempt,int maxAttempts,String previousFailure,int completed,int total) {}
    private static final class Call {
        final int leg,round; final WalkingSource source;
        Call(int leg,int round,WalkingSource source) {this.leg=leg;this.round=round;this.source=source;}
        void stop() {try {source.close();} catch(RuntimeException ignored) {}}
    }
    private final Input input;
    private final IntFunction<WalkingSource> sources;
    private final ExecutorService worker;
    private final ScheduledExecutorService timer;
    private final long budgetMillis,pacingMillis;
    private final Consumer<Progress> progress;
    private final BiConsumer<Plan,Throwable> completion;
    private final Map<Integer,Call> active=new HashMap<>();
    private boolean terminal;
    private SegmentedRoutePlanner.Prepared prepared;
    private List<Segment> legs;
    private int round,completed,cursor;
    private List<Integer> pending;
    private long until;
    private ScheduledFuture<?> timeout,pump;
    private Future<?> preparation;

    private AutoRouteJob(Input input,IntFunction<WalkingSource> sources,ExecutorService worker,
            ScheduledExecutorService timer,long budgetMillis,long pacingMillis,Consumer<Progress> progress,BiConsumer<Plan,Throwable> completion) {
        if(budgetMillis<=0 || pacingMillis<0) throw new IllegalArgumentException("Invalid attempt budget");
        this.input=input;this.sources=sources;this.worker=worker;this.timer=timer;this.budgetMillis=budgetMillis;
        this.pacingMillis=pacingMillis;this.progress=progress;this.completion=completion;
    }
    public static AutoRouteJob start(Input input,IntFunction<WalkingSource> sources,ExecutorService worker,
            ScheduledExecutorService timer,Consumer<Progress> progress,BiConsumer<Plan,Throwable> completion) {
        return start(input,sources,worker,timer,ATTEMPT_BUDGET_MS,350,progress,completion);
    }
    // Test budgets exercise actual timers and late callbacks without a minute-long wait.
    public static AutoRouteJob start(Input input,IntFunction<WalkingSource> sources,ExecutorService worker,
            ScheduledExecutorService timer,long budgetMillis,long pacingMillis,Consumer<Progress> progress,BiConsumer<Plan,Throwable> completion) {
        AutoRouteJob job=new AutoRouteJob(input,sources,worker,timer,budgetMillis,pacingMillis,progress,completion);
        job.preparation=worker.submit(()->{
            try {
                var ready=SegmentedRoutePlanner.prepare(input,System.nanoTime()+TimeUnit.SECONDS.toNanos(5));
                synchronized(job) {
                    if(job.terminal)return;
                    job.prepared=ready;job.legs=new ArrayList<>(ready.legs());job.beginRound(null);
                }
            } catch(Throwable error) {job.finish(null,error);}
        });
        return job;
    }
    private synchronized void beginRound(String failure) {
        if(terminal)return;
        round++;until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        pending=new ArrayList<>();cursor=0;
        for(int i=0;i<legs.size();i++) if(!"roads".equals(legs.get(i).mode()))pending.add(i);
        emit(failure);
        int thisRound=round;
        timeout=timer.schedule(()->expired(thisRound),budgetMillis,TimeUnit.MILLISECONDS);
        pump=timer.schedule(this::launch,0,TimeUnit.MILLISECONDS);
    }
    private synchronized void launch() {
        if(terminal || System.nanoTime()>=until)return;
        if(active.size()>=MAX_IN_FLIGHT || cursor>=pending.size()) {pump=null;return;}
        int index=pending.get(cursor++),thisRound=round; Segment fallback=legs.get(index);
        legs.set(index,new Segment(fallback.route(),"geometry",fallback.reason(),fallback.attempts()+1,false));
        WalkingSource source;
        try {source=sources.apply(round);} catch(Throwable error) {failedLaunch(index,error);return;}
        Call call=new Call(index,round,source);active.put(index,call);
        try {
            List<Point> path=fallback.route();
            source.request(path.get(0),path.get(path.size()-1),until).whenComplete((answer,error)->{
                try {worker.execute(()->resolved(call,answer,error));}
                catch(RejectedExecutionException ignored) {call.stop();}
            });
        } catch(Throwable error) {worker.execute(()->resolved(call,null,error));}
        if(cursor<pending.size()) pump=timer.schedule(()->{synchronized(this){if(round==thisRound)launch();}},pacingMillis,TimeUnit.MILLISECONDS);
        else pump=null;
    }
    private synchronized void failedLaunch(int index,Throwable error) {
        Segment old=legs.get(index);legs.set(index,new Segment(old.route(),"geometry",reason(error),old.attempts(),false));
        if(active.isEmpty() && cursor>=pending.size()) endRound(reason(error));
        else pump=timer.schedule(this::launch,pacingMillis,TimeUnit.MILLISECONDS);
    }
    private synchronized void resolved(Call call,WalkingResult answer,Throwable error) {
        if(terminal || call.round!=round || active.get(call.leg)!=call)return;
        if(System.nanoTime()>=until) {expired(round);return;}
        active.remove(call.leg);call.stop();
        Segment old=legs.get(call.leg);String failure=error==null?"未找到围栏内步行路线":reason(error);
        List<Point> best=null;double score=Double.POSITIVE_INFINITY;
        if(error==null && answer!=null && answer.routes()!=null) {
            List<Point> previous=new ArrayList<>();
            // Use already known earlier legs when comparing SDK alternatives.
            for(int i=0;i<call.leg;i++) if("roads".equals(legs.get(i).mode()))previous.addAll(legs.get(i).route());
            for(List<Point> candidate:answer.routes().stream().limit(8).collect(java.util.stream.Collectors.toList())) {
                try {
                    List<Point> path=SegmentedRoutePlanner.validateCandidate(prepared,call.leg,candidate);
                    double value=SegmentedRoutePlanner.length(path)+SegmentedRoutePlanner.overlap(path,previous)*4;
                    if(value<score) {score=value;best=path;}
                }catch(IllegalArgumentException invalid) {failure=invalid.getMessage();}
            }
        }
        if(best!=null) {legs.set(call.leg,new Segment(best,"roads","",old.attempts(),answer.cached()));completed++;}
        else legs.set(call.leg,new Segment(old.route(),"geometry",failure,old.attempts(),false));
        emit(best==null?failure:null);
        if(active.isEmpty() && cursor>=pending.size()) endRound(failure);
        else if(pump==null) pump=timer.schedule(this::launch,pacingMillis,TimeUnit.MILLISECONDS);
    }
    private synchronized void expired(int expected) {
        if(terminal || round!=expected)return;
        for(int index:pending) if(!"roads".equals(legs.get(index).mode())) {
            Segment old=legs.get(index);legs.set(index,new Segment(old.route(),"geometry","该路段规划超时",old.attempts(),false));
        }
        endRound("未完成路段超时");
    }
    private synchronized void endRound(String failure) {
        if(terminal)return;
        if(timeout!=null)timeout.cancel(false);if(pump!=null)pump.cancel(false);pump=null;
        // Clear before cancellation can synchronously invoke a completion callback.
        List<Call> cancelled=new ArrayList<>(active.values());active.clear();cancelled.forEach(Call::stop);
        if(completed<legs.size() && round<MAX_ATTEMPTS) {beginRound(failure);return;}
        try {
            List<Segment> improved=SegmentedRoutePlanner.improveRoadTopology(prepared,legs,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(750));
            finish(SegmentedRoutePlanner.assemble(prepared,improved),null);
        } catch(Throwable error) {finish(null,error);}
    }
    private void emit(String failure) {progress.accept(new Progress(round,MAX_ATTEMPTS,failure,completed,legs.size()));}
    private static String reason(Throwable error) {
        while(error instanceof CompletionException && error.getCause()!=null)error=error.getCause();
        String text=error.getMessage();return text==null?"步行服务不可用":text.substring(0,Math.min(120,text.length()));
    }
    private synchronized void finish(Plan plan,Throwable error) {
        if(terminal)return;terminal=true;cleanup();completion.accept(plan,error);
    }
    public synchronized void cancel() {if(terminal)return;terminal=true;cleanup();}
    private void cleanup() {
        if(preparation!=null && !preparation.isDone())preparation.cancel(true);
        if(timeout!=null)timeout.cancel(false);if(pump!=null)pump.cancel(false);
        List<Call> cancelled=new ArrayList<>(active.values());active.clear();cancelled.forEach(Call::stop);
    }
}
