package io.github.sorubedo.routeplanner.route;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import io.github.sorubedo.routeplanner.route.RoutePath.Point;
import io.github.sorubedo.routeplanner.route.AutoRoutePlanner.*;

public class SegmentedRoutePlannerTest {
    private Point p(double north,double east){return new Point(40+north/111195,116+east/(111195*Math.cos(Math.toRadians(40))));}
    private List<Point> square(){return List.of(p(0,0),p(0,400),p(400,400),p(400,0));}
    @Test public void zeroAndSingleCheckpointsBecomeRequestableClosedLegs() {
        for(List<Checkpoint> cps:List.of(List.<Checkpoint>of(),List.of(new Checkpoint(p(200,200),1,false)))) {
            Input input=new Input(square(),p(60,60),cps,0,1000,42);
            var prepared=SegmentedRoutePlanner.prepare(input,System.nanoTime()+1_000_000_000L);
            assertTrue(prepared.legs().size()>=3);Plan plan=SegmentedRoutePlanner.assemble(prepared,prepared.legs());
            assertEquals(0,RoutePath.distance(plan.route().get(0),plan.route().get(plan.route().size()-1)),.005);
            for(Checkpoint cp:cps)assertTrue(plan.route().stream().anyMatch(q->RoutePath.distance(q,cp.position())<.005));
        }
    }
    @Test public void shortCheckpointTourAddsLoopShapeWithoutAddingCheckins() {
        Input input=new Input(square(),p(60,60),List.of(new Checkpoint(p(70,60),1,false),new Checkpoint(p(70,70),2,false)),2,1000,42);
        var prepared=SegmentedRoutePlanner.prepare(input,System.nanoTime()+1_000_000_000L);
        Plan plan=SegmentedRoutePlanner.assemble(prepared,prepared.legs());
        assertEquals(List.of(0,1),plan.visitOrder());assertTrue(plan.path().length>100);assertTrue(prepared.legs().size()>3);
    }
    @Test public void insideEndpointsDoNotPermitCrossingConcaveFence() {
        List<Point> polygon=List.of(p(0,0),p(0,400),p(160,400),p(160,160),p(400,160),p(400,0));
        Input input=new Input(polygon,p(300,80),List.of(new Checkpoint(p(80,300),1,false),new Checkpoint(p(80,80),2,false)),2,500,42);
        var prepared=SegmentedRoutePlanner.prepare(input,System.nanoTime()+1_000_000_000L);
        Segment leg=prepared.legs().get(0);Point from=leg.route().get(0),to=leg.route().get(leg.route().size()-1);
        assertTrue(AutoRoutePlanner.inside(from,polygon));assertTrue(AutoRoutePlanner.inside(to,polygon));
        assertThrows(IllegalArgumentException.class,()->SegmentedRoutePlanner.validateCandidate(prepared,0,List.of(from,to)));
    }
    @Test public void knownSdkDirectionsRemoveAnUnnecessaryDetourWithNoNewRequest() {
        Input input=new Input(square(),p(60,60),List.of(new Checkpoint(p(60,340),1,false),new Checkpoint(p(340,340),2,false)),2,500,42);
        var prepared=SegmentedRoutePlanner.prepare(input,System.nanoTime()+1_000_000_000L);
        List<Segment> original=List.of(new Segment(List.of(p(60,60),p(340,60),p(60,60),p(60,340)),"roads","",1,false),
                new Segment(List.of(p(60,340),p(340,340)),"roads","",1,false),new Segment(List.of(p(340,340),p(340,60),p(60,60)),"roads","",1,false));
        List<Segment> improved=SegmentedRoutePlanner.improveRoadTopology(prepared,original,System.nanoTime()+1_000_000_000L);
        Plan before=SegmentedRoutePlanner.assemble(prepared,original),after=SegmentedRoutePlanner.assemble(prepared,improved);
        assertTrue(after.path().length<before.path().length-500);assertEquals(before.visitOrder(),after.visitOrder());
        assertEquals("roads",after.mode());
    }
    @Test public void eightyVertexFenceAndTwentyFourOrderedPointsFitLocalBudget() {
        List<Point> polygon=new ArrayList<>();List<Checkpoint> checkpoints=new ArrayList<>();
        for(int i=0;i<80;i++){double angle=i*Math.PI/40,r=i%2==0?450:300;polygon.add(p(500+Math.sin(angle)*r,500+Math.cos(angle)*r));}
        for(int i=0;i<24;i++){double angle=i*Math.PI/12;checkpoints.add(new Checkpoint(p(500+Math.sin(angle)*150,500+Math.cos(angle)*150),24-i,false));}
        Input input=new Input(polygon,p(500,500),checkpoints,2,1000,42);
        var prepared=SegmentedRoutePlanner.prepare(input,System.nanoTime()+3_000_000_000L);
        Plan plan=SegmentedRoutePlanner.assemble(prepared,prepared.legs());
        assertEquals(24,plan.visitOrder().size());assertEquals(Integer.valueOf(23),plan.visitOrder().get(0));assertEquals(Integer.valueOf(0),plan.visitOrder().get(23));
        assertEquals(25,prepared.legs().size());
    }
}
