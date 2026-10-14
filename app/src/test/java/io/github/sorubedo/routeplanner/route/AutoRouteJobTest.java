package io.github.sorubedo.routeplanner.route;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.*;
import static org.junit.Assert.*;
import io.github.sorubedo.routeplanner.route.RoutePath.Point;
import io.github.sorubedo.routeplanner.route.AutoRoutePlanner.*;

public class AutoRouteJobTest {
    private ExecutorService worker;
    private ScheduledExecutorService timer;
    @Before public void setup(){worker=Executors.newFixedThreadPool(3);timer=Executors.newSingleThreadScheduledExecutor();}
    @After public void cleanup(){worker.shutdownNow();timer.shutdownNow();}
    private Point p(double north,double east){return new Point(40+north/111195,116+east/(111195*Math.cos(Math.toRadians(40))));}
    private Input input(){return new Input(List.of(p(0,0),p(0,400),p(400,400),p(400,0)),p(60,60),List.of(new Checkpoint(p(60,340),1,false),new Checkpoint(p(340,340),2,false)),2,500,42);}
    private AutoRouteJob.WalkingSource source(BiFunction<Point,Point,CompletableFuture<AutoRouteJob.WalkingResult>> request,Runnable close){
        return new AutoRouteJob.WalkingSource(){
            public CompletableFuture<AutoRouteJob.WalkingResult> request(Point a,Point b,long until){return request.apply(a,b);}
            public void close(){close.run();}
        };
    }
    private CompletableFuture<AutoRouteJob.WalkingResult> success(Point a,Point b){return CompletableFuture.completedFuture(new AutoRouteJob.WalkingResult(List.of(List.of(a,b)),false));}
    private AutoRouteJob start(IntFunction<AutoRouteJob.WalkingSource> sources,long budget,Consumer<AutoRouteJob.Progress> progress,CompletableFuture<Plan> output){
        return AutoRouteJob.start(input(),sources,worker,timer,budget,0,progress,(plan,error)->{if(error==null)output.complete(plan);else output.completeExceptionally(error);});
    }
    @Test public void defaultPolicyIsTwentySecondsAndThreeRounds(){assertEquals(20_000,AutoRouteJob.ATTEMPT_BUDGET_MS);assertEquals(3,AutoRouteJob.MAX_ATTEMPTS);}
    @Test public void oneFailedLegRetriesAloneAndKeepsBothSuccessfulLegs() throws Exception {
        AtomicInteger calls=new AtomicInteger();CompletableFuture<Plan> output=new CompletableFuture<>();
        start(n->source((a,b)->{
            calls.incrementAndGet();
            if(RoutePath.distance(a,input().checkpoints().get(0).position())<.01)return CompletableFuture.failedFuture(new java.io.IOException("offline"));
            return success(a,b);
        },()->{}),1000,p->{},output);
        Plan result=output.get(3,TimeUnit.SECONDS);
        assertEquals("mixed",result.mode());assertEquals(5,calls.get());assertEquals(3,result.segments().size());
        assertEquals(List.of("roads","geometry","roads"),result.segments().stream().map(Segment::mode).toList());
        assertEquals(3,result.segments().get(1).attempts());assertEquals(1,result.segments().get(0).attempts());
        assertEquals(List.of(0,1),result.visitOrder());assertEquals(0,RoutePath.distance(input().start(),result.route().get(0)),.005);
    }
    @Test public void sharedDeadlinesBoundAllLegsAndExpiredCallbacksCannotWin() throws Exception {
        List<CompletableFuture<AutoRouteJob.WalkingResult>> late=new CopyOnWriteArrayList<>();
        AtomicInteger closed=new AtomicInteger(),completions=new AtomicInteger();CompletableFuture<Plan> output=new CompletableFuture<>();
        long began=System.nanoTime();
        AutoRouteJob.start(input(),n->source((a,b)->{var f=new CompletableFuture<AutoRouteJob.WalkingResult>();late.add(f);return f;},closed::incrementAndGet),worker,timer,120,0,p->{},(plan,error)->{completions.incrementAndGet();if(error==null)output.complete(plan);else output.completeExceptionally(error);});
        Plan result=output.get(2,TimeUnit.SECONDS);long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);
        assertEquals("geometry",result.mode());assertTrue("elapsed="+elapsed,elapsed>=350 && elapsed<1500);assertEquals(9,closed.get());
        for(var future:late)future.complete(new AutoRouteJob.WalkingResult(List.of(List.of(p(60,60),p(60,340))),false));
        worker.submit(()->{}).get(1,TimeUnit.SECONDS);assertEquals(1,completions.get());
        assertTrue(result.segments().stream().allMatch(s->s.attempts()==3));
    }
    @Test public void boundaryViolatingLegFallsBackWithoutDiscardingValidRoads() throws Exception {
        CompletableFuture<Plan> output=new CompletableFuture<>();
        start(n->source((a,b)->RoutePath.distance(a,input().start())<.01?
            CompletableFuture.completedFuture(new AutoRouteJob.WalkingResult(List.of(List.of(a,p(-20,200),b)),false)):success(a,b),()->{}),1000,p->{},output);
        Plan result=output.get(2,TimeUnit.SECONDS);assertEquals("mixed",result.mode());
        assertEquals("geometry",result.segments().get(0).mode());assertEquals("该路段越过围栏",result.segments().get(0).reason());
        for(int i=1;i<result.route().size();i++)assertTrue(AutoRoutePlanner.segmentInside(result.route().get(i-1),result.route().get(i),input().polygon()));
    }
    @Test public void cancellationClosesSourcesWithoutRetryOrCompletion() throws Exception {
        CountDownLatch entered=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger(),closed=new AtomicInteger(),outputs=new AtomicInteger();
        AutoRouteJob job=AutoRouteJob.start(input(),n->source((a,b)->{calls.incrementAndGet();entered.countDown();return new CompletableFuture<>();},closed::incrementAndGet),worker,timer,500,100,p->{},(p,e)->outputs.incrementAndGet());
        assertTrue(entered.await(1,TimeUnit.SECONDS));job.cancel();
        timer.shutdown();assertTrue(timer.awaitTermination(1,TimeUnit.SECONDS));
        assertEquals(1,calls.get());assertEquals(1,closed.get());assertEquals(0,outputs.get());
    }
    @Test public void lateFirstRoundSuccessDoesNotReplaceSecondRoundRoute() throws Exception {
        CompletableFuture<AutoRouteJob.WalkingResult> late=new CompletableFuture<>();CompletableFuture<Plan> output=new CompletableFuture<>();AtomicInteger closed=new AtomicInteger();
        start(n->source((a,b)->n==1 && RoutePath.distance(a,input().start())<.01?late:success(a,b),closed::incrementAndGet),100,p->{},output);
        Plan result=output.get(2,TimeUnit.SECONDS);assertEquals("roads",result.mode());assertEquals(2,result.segments().get(0).attempts());
        late.complete(new AutoRouteJob.WalkingResult(List.of(List.of(p(60,60),p(-10,-10),p(60,340))),false));
        worker.submit(()->{}).get(1,TimeUnit.SECONDS);assertEquals("roads",result.mode());assertEquals(4,closed.get());
    }
    @Test public void concurrencyNeverExceedsThreeAndSuccessEndsFirstRound() throws Exception {
        AtomicInteger active=new AtomicInteger(),max=new AtomicInteger();CompletableFuture<Plan> output=new CompletableFuture<>();
        start(n->source((a,b)->{
            int count=active.incrementAndGet();max.accumulateAndGet(count,Math::max);
            var f=new CompletableFuture<AutoRouteJob.WalkingResult>();timer.schedule(()->{active.decrementAndGet();f.complete(new AutoRouteJob.WalkingResult(List.of(List.of(a,b)),false));},20,TimeUnit.MILLISECONDS);return f;
        },()->{}),1000,p->{},output);
        Plan plan=output.get(2,TimeUnit.SECONDS);assertTrue(max.get()<=3);assertEquals("roads",plan.mode());assertTrue(plan.segments().stream().allMatch(s->s.attempts()==1));
    }
}
