package io.github.sorubedo.routeplanner.route;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import io.github.sorubedo.routeplanner.route.RoutePath.Point;
import io.github.sorubedo.routeplanner.route.AutoRoutePlanner.*;

public class AutoRoutePlannerTest {
    private Point p(double north,double east) { return new Point(40+north/111195,116+east/(111195*Math.cos(Math.toRadians(40)))); }
    private List<Point> square() { return List.of(p(0,0),p(0,400),p(400,400),p(400,0)); }
    private Checkpoint cp(double north,double east,double sort) { return new Checkpoint(p(north,east),sort,false); }
    private Input input(List<Checkpoint> cps,int mode) { return new Input(square(),p(60,60),cps,mode,1000,42); }
    private void validate(Input input,Plan plan) {
        RoutePath path=plan.path(); assertEquals(0,RoutePath.distance(input.start(),path.at(0)),0.01);
        assertEquals(0,RoutePath.distance(path.points.get(0),path.points.get(path.points.size()-1)),0.001);
        for(int i=1;i<plan.route().size();i++) assertTrue(AutoRoutePlanner.segmentInside(plan.route().get(i-1),plan.route().get(i),input.polygon()));
        for(Checkpoint cp:input.checkpoints()) assertTrue(plan.route().stream().anyMatch(p->RoutePath.distance(p,cp.position())<0.01));
    }
    @Test public void zeroAndOnePointGenerateClosedLoopsAtFixedStart() {
        for(List<Checkpoint> cps:List.of(List.<Checkpoint>of(),List.of(cp(240,220,5)))) {
            Input in=input(cps,2); Plan plan=AutoRoutePlanner.plan(in,null,0); validate(in,plan);
            assertEquals("geometry",plan.mode()); assertTrue(plan.path().length>=50);
            assertEquals(cps.size(),plan.visitOrder().size());
        }
    }
    @Test public void orderedModesUseStableSortRatherThanListOrder() {
        List<Checkpoint> cps=List.of(cp(330,330,8),cp(70,300,1),cp(300,70,1));
        for(int mode:List.of(2,3)) {
            Input in=input(cps,mode); Plan plan=AutoRoutePlanner.plan(in,null,0); validate(in,plan);
            assertEquals(List.of(1,2,0),plan.visitOrder());
        }
    }
    @Test public void unorderedModeIgnoresSort() {
        Input in=input(List.of(cp(70,80,100),cp(320,320,-100)),1);
        Plan plan=AutoRoutePlanner.plan(in,null,0); validate(in,plan); assertEquals(List.of(0,1),plan.visitOrder());
    }
    @Test public void concaveBoundaryRoutesAroundTheNotch() {
        List<Point> poly=List.of(p(0,0),p(0,400),p(400,400),p(400,260),p(100,260),p(100,140),p(400,140),p(400,0));
        Input in=new Input(poly,p(320,70),List.of(cp(320,330,1),cp(50,200,2)),2,1000,42);
        assertFalse(AutoRoutePlanner.segmentInside(in.start(),in.checkpoints().get(0).position(),poly));
        Plan plan=AutoRoutePlanner.plan(in,null,0); validate(in,plan);
        assertTrue(plan.route().size()>4);
    }
    @Test public void duplicatePointsAndPointsAtStartRemainVisits() {
        Input in=input(List.of(cp(60,60,2),cp(60,60,1)),2); Plan plan=AutoRoutePlanner.plan(in,null,0);
        validate(in,plan); assertEquals(List.of(1,0),plan.visitOrder()); assertTrue(plan.path().length>=30);
    }
    @Test public void rejectsEmptySelfIntersectingAndCrossFenceInputs() {
        try { new Input(List.of(),p(0,0),List.of(),0,1000,42); fail(); } catch(IllegalArgumentException expected) {}
        try { new Input(List.of(p(0,0),p(400,400),p(0,400),p(400,0)),p(100,100),List.of(),0,1000,42); fail(); } catch(IllegalArgumentException expected) {}
        try { input(List.of(cp(500,500,0)),1); fail(); } catch(IllegalArgumentException expected) {}
    }
    @Test public void seededStartsAndRandomConcaveCasesStayInside() {
        List<Point> concave=List.of(p(0,0),p(0,400),p(400,400),p(400,260),p(100,260),p(100,140),p(400,140),p(400,0));
        for(List<Point> poly:List.of(square(),concave)) for(int seed=0;seed<40;seed++) {
            Random rng=new Random(seed); Point start=AutoRoutePlanner.randomStart(poly,rng);
            assertEquals(0,RoutePath.distance(start,AutoRoutePlanner.randomStart(poly,new Random(seed))),0.001);
            for(int count:List.of(0,1,5)) {
                List<Checkpoint> cps=new ArrayList<>();
                for(int i=0;i<count;i++) cps.add(new Checkpoint(AutoRoutePlanner.randomStart(poly,rng),count-i,false));
                Input in=new Input(poly,start,cps,seed%2==0?2:1,1000,seed); validate(in,AutoRoutePlanner.plan(in,null,0));
            }
        }
    }
    private Road road(Point... points) { return new Road(List.of(points),"footway","","","",""); }
    @Test public void roadsUseAnAlternativeRatherThanRepeatingTheSameEdge() {
        Point a=p(60,60),b=p(60,340),c=p(340,340),d=p(340,60);
        Input in=new Input(square(),a,List.of(new Checkpoint(b,1,false),new Checkpoint(c,2,false)),2,1000,42);
        Plan plan=AutoRoutePlanner.plan(in,List.of(road(a,b,c,d,a)),0); validate(in,plan);
        assertEquals("roads",plan.mode()); assertEquals(0,plan.repeatedMetres(),0.01);
    }
    @Test public void splitEdgesStillCountRepeatedPhysicalIntervals() {
        Point a=p(200,60),b=p(200,340);
        Input in=new Input(square(),p(200,100),List.of(cp(200,250,1),cp(200,300,2)),2,1000,42);
        Plan plan=AutoRoutePlanner.plan(in,List.of(road(a,b)),0); validate(in,plan);
        assertEquals("roads",plan.mode()); assertTrue(plan.repeatedMetres()>195 && plan.repeatedMetres()<205);
    }
    @Test public void walkAccessAndFootOneWayAreRespected() {
        assertFalse(AutoRoutePlanner.walkable(new Road(List.of(),"footway","no","","","")));
        assertFalse(AutoRoutePlanner.walkable(new Road(List.of(),"motorway","yes","","","")));
        assertFalse(AutoRoutePlanner.walkable(new Road(List.of(),"footway","","private","","")));
        Point a=p(60,60),b=p(60,340),c=p(340,340),d=p(340,60);
        Input in=new Input(square(),a,List.of(new Checkpoint(c,1,false),new Checkpoint(b,2,false)),2,1000,42);
        Plan plan=AutoRoutePlanner.plan(in,List.of(new Road(List.of(a,b,c,d,a),"footway","yes","","","yes")),0);
        validate(in,plan); assertTrue(plan.path().length>1800);
    }
    @Test public void publicCampusSnapshotPlansZeroSingleAndOrderedCheckpointRoadLoops() throws Exception {
        List<Road> roads=new ArrayList<>();
        try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(
                getClass().getResourceAsStream("/campus-roads.tsv"),java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while((line=reader.readLine())!=null) {
                if(line.startsWith("#")) continue;
                String[] fields=line.split("\\t",-1); List<Point> points=new ArrayList<>();
                for(String pair:fields[5].split(";")) { String[] xy=pair.split(","); points.add(new Point(Double.parseDouble(xy[0]),Double.parseDouble(xy[1]))); }
                roads.add(new Road(points,fields[0],fields[1],fields[2],fields[3],fields[4]));
            }
        }
        List<Point> polygon=List.of(new Point(39.997,116.322),new Point(39.997,116.330),new Point(40.0015,116.330),new Point(40.0015,116.322));
        List<Checkpoint> checkpoints=List.of(new Checkpoint(new Point(39.9980819,116.3232452),1,false),
                new Checkpoint(new Point(40.0010782,116.3239536),2,false),new Checkpoint(new Point(40.0003586,116.3280318),3,false));
        for(int count:List.of(0,1,3)) {
            Input in=new Input(polygon,new Point(39.99915,116.32418),checkpoints.subList(0,count),2,1000,42);
            Plan plan=AutoRoutePlanner.plan(in,roads,System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4));
            validate(in,plan); assertEquals("roads",plan.mode()); assertEquals(count,plan.visitOrder().size());
        }
    }
    @Test public void roadDeadlineAbortsRatherThanReturningUnvalidatedPath() {
        try { AutoRoutePlanner.plan(input(List.of(),1),List.of(road(p(60,60),p(60,340))),System.nanoTime()-1); fail(); }
        catch(IllegalStateException expected) {}
    }
    @Test public void duplicateOsmWaysDoNotHideRepeatedRoads() {
        Point a=p(200,60),b=p(200,340);
        Input in=new Input(square(),a,List.of(cp(200,200,1),new Checkpoint(b,2,false)),2,1000,42);
        Plan plan=AutoRoutePlanner.plan(in,List.of(road(a,b),road(b,a)),0); validate(in,plan);
        assertTrue(plan.repeatedMetres()>275);
    }
    @Test public void coordinateConversionRoundTripsAndFinalSegmentsRemainInside() {
        Point original=new Point(39.999,116.325),gcj=AutoRoutePlanner.wgsToGcj(original);
        assertTrue(RoutePath.distance(original,gcj)>100);
        assertEquals(0,RoutePath.distance(original,AutoRoutePlanner.gcjToWgs(gcj)),0.01);
        Input in=input(List.of(cp(270,270,2),cp(180,330,1)),2); Plan plan=AutoRoutePlanner.plan(in,null,0);
        List<Point> gcjPoly=new ArrayList<>(); for(Point p:in.polygon()) gcjPoly.add(AutoRoutePlanner.wgsToGcj(p));
        for(int i=1;i<plan.route().size();i++) assertTrue(AutoRoutePlanner.segmentInside(AutoRoutePlanner.wgsToGcj(plan.route().get(i-1)),AutoRoutePlanner.wgsToGcj(plan.route().get(i)),gcjPoly));
    }
}
