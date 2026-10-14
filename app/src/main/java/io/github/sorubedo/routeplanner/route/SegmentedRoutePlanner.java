package io.github.sorubedo.routeplanner.route;

import java.util.*;
import io.github.sorubedo.routeplanner.route.RoutePath.Point;
import io.github.sorubedo.routeplanner.route.AutoRoutePlanner.*;

/** Local visit ordering and independently replaceable, fence-checked walking legs. */
public final class SegmentedRoutePlanner {
    public record Prepared(Input input,Plan geometry,List<Segment> legs) {}
    public static Prepared prepare(Input input,long until) {
        Plan geometry=AutoRoutePlanner.plan(input,null,until);
        List<Point> shape=new ArrayList<>();
        // Short checkpoint tours need a loop shape too, rather than an out-and-back
        // between adjacent points. These are local shaping anchors, not extra check-ins.
        if(input.checkpoints().size()>=2 && input.checkpoints().size()<=22 && geometry.path().length<input.targetMetres()*.7) {
            Plan ring=AutoRoutePlanner.plan(new Input(input.polygon(),input.start(),List.of(),0,input.targetMetres(),input.seed()),null,until);
            List<Checkpoint> augmented=new ArrayList<>(input.checkpoints());
            for(int i:new int[]{ring.route().size()/3,ring.route().size()*2/3}) {
                Point p=ring.route().get(i); shape.add(p); augmented.add(new Checkpoint(p,Double.MAX_VALUE,false));
            }
            Plan shaped=AutoRoutePlanner.plan(new Input(input.polygon(),input.start(),augmented,input.clockMode(),input.targetMetres(),input.seed()),null,until);
            geometry=new Plan(shaped.route(),shaped.visitOrder().stream().filter(i->i<input.checkpoints().size()).collect(java.util.stream.Collectors.toList()),"geometry",shaped.repeatedMetres());
        }
        TreeSet<Integer> cuts=new TreeSet<>(List.of(0,geometry.route().size()-1));
        int cursor=0;
        for(int index:geometry.visitOrder()) {
            Point p=input.checkpoints().get(index).position();
            while(cursor<geometry.route().size()-1 && RoutePath.distance(p,geometry.route().get(cursor))>.005) cursor++;
            cuts.add(cursor);
        }
        for(Point p:shape) for(int i=1;i<geometry.route().size()-1;i++) if(RoutePath.distance(p,geometry.route().get(i))<.005) {cuts.add(i);break;}
        if(input.checkpoints().size()<=1 || cuts.size()<3) {
            cuts.add(geometry.route().size()/3); cuts.add(geometry.route().size()*2/3);
        }
        List<Segment> legs=new ArrayList<>(); int last=0;
        for(int cut:cuts) {
            if(cut>last) legs.add(new Segment(geometry.route().subList(last,cut+1),"geometry","未请求道路",0,false));
            last=cut;
        }
        return new Prepared(input,geometry,List.copyOf(legs));
    }
    public static List<Point> validateCandidate(Prepared prepared,int leg,List<Point> supplied) {
        if(supplied==null || supplied.size()<2 || supplied.size()>5000) throw new IllegalArgumentException("腾讯未返回有效步行折线");
        List<Point> fallback=prepared.legs().get(leg).route();
        Point from=fallback.get(0),to=fallback.get(fallback.size()-1);
        if(RoutePath.distance(from,supplied.get(0))>60 || RoutePath.distance(to,supplied.get(supplied.size()-1))>60)
            throw new IllegalArgumentException("道路吸附距离超过 60 米");
        List<Point> clean=new ArrayList<>(List.of(from));
        for(Point p:supplied) {
            if(p==null || !Double.isFinite(p.latitude+p.longitude)) throw new IllegalArgumentException("道路坐标无效");
            if(RoutePath.distance(p,clean.get(clean.size()-1))>.005) clean.add(p);
        }
        if(RoutePath.distance(clean.get(clean.size()-1),to)>.005) clean.add(to); else clean.set(clean.size()-1,to);
        // Check entire segments against concave fences, not just sampled vertices.
        for(int i=1;i<clean.size();i++) if(!AutoRoutePlanner.segmentInside(clean.get(i-1),clean.get(i),prepared.input().polygon()))
            throw new IllegalArgumentException("该路段越过围栏");
        if(length(clean)>Math.max(300,length(fallback)*4+200)) throw new IllegalArgumentException("该路段绕行过远");
        return List.copyOf(clean);
    }
    public static Plan assemble(Prepared prepared,List<Segment> legs) {
        if(legs.size()!=prepared.legs().size()) throw new IllegalArgumentException("路段数量不一致");
        List<Point> route=new ArrayList<>(); int roads=0;
        for(int i=0;i<legs.size();i++) {
            Segment leg=legs.get(i); List<Point> expected=prepared.legs().get(i).route();
            if(RoutePath.distance(leg.route().get(0),expected.get(0))>.005 || RoutePath.distance(leg.route().get(leg.route().size()-1),expected.get(expected.size()-1))>.005)
                throw new IllegalArgumentException("路段端点不一致");
            if("roads".equals(leg.mode())) roads++;
            for(Point p:leg.route()) if(route.isEmpty() || RoutePath.distance(route.get(route.size()-1),p)>.005) route.add(p);
        }
        String mode=roads==0?"geometry":roads==legs.size()?"roads":"mixed";
        Plan plan=new Plan(route,prepared.geometry().visitOrder(),mode,overlap(route,List.of()),legs);
        if(RoutePath.distance(route.get(0),route.get(route.size()-1))>.005) throw new IllegalArgumentException("路线未闭合");
        int cursor=0;
        for(int cp:plan.visitOrder()) {
            Point p=prepared.input().checkpoints().get(cp).position();
            while(cursor<route.size() && RoutePath.distance(route.get(cursor),p)>.005) cursor++;
            if(cursor>=route.size()) throw new IllegalArgumentException("路线遗漏或打乱打卡点");
        }
        plan.path(); return plan;
    }
    /** Reuse only directions actually returned by walking requests. No extra network
     * calls or invented reverse edges; keep the same ordered segment anchors. */
    public static List<Segment> improveRoadTopology(Prepared prepared,List<Segment> legs,long until) {
        if(legs.stream().anyMatch(s->!"roads".equals(s.mode())))return legs;
        try {
            List<Road> roads=new ArrayList<>();List<Checkpoint> anchors=new ArrayList<>();
            for(int i=0;i<legs.size();i++) {
                Segment s=legs.get(i);roads.add(new Road(s.route(),"footway","yes","","","yes"));
                if(i<legs.size()-1)anchors.add(new Checkpoint(s.route().get(s.route().size()-1),i,false));
            }
            Input original=prepared.input();
            Plan candidate=AutoRoutePlanner.plan(new Input(original.polygon(),original.start(),anchors,2,original.targetMetres(),original.seed()),roads,until);
            if(!"roads".equals(candidate.mode()))return legs;
            List<Segment> improved=new ArrayList<>();int cursor=0;
            for(int i=0;i<legs.size();i++) {
                Segment old=legs.get(i);Point end=old.route().get(old.route().size()-1);int next=cursor+1;
                while(next<candidate.route().size() && RoutePath.distance(candidate.route().get(next),end)>.005)next++;
                if(next>=candidate.route().size())return legs;
                List<Point> path=validateCandidate(prepared,i,candidate.route().subList(cursor,next+1));
                improved.add(new Segment(path,"roads",old.reason(),old.attempts(),old.cached()));cursor=next;
            }
            if(cursor!=candidate.route().size()-1)return legs;
            Plan before=assemble(prepared,legs),after=assemble(prepared,improved);
            return after.path().length+after.repeatedMetres()*4+.01<before.path().length+before.repeatedMetres()*4?improved:legs;
        }catch(RuntimeException unusable) {return legs;}
    }
    public static double length(List<Point> route) {
        double n=0; for(int i=1;i<route.size();i++) n+=RoutePath.distance(route.get(i-1),route.get(i)); return n;
    }
    // Direction-independent corridor cells estimate repeat distance even when the
    // SDK splits the same physical road into different vertices. Linear in metres.
    private record Cell(long x,long y,int heading) {}
    public static double overlap(List<Point> route,List<Point> previous) {
        Set<Cell> seen=new HashSet<>(); sample(previous,seen,false);
        return sample(route,seen,true);
    }
    private static double sample(List<Point> route,Set<Cell> seen,boolean count) {
        double repeated=0;
        for(int i=1;i<route.size();i++) {
            Point a=route.get(i-1),b=route.get(i); double metres=RoutePath.distance(a,b);
            if(metres<.005) continue;
            int steps=Math.max(1,(int)Math.ceil(metres/5));
            double dx=(b.longitude-a.longitude)*Math.cos(Math.toRadians(a.latitude)),dy=b.latitude-a.latitude;
            double heading=(Math.atan2(dy,dx)+Math.PI)%Math.PI; int direction=(int)Math.floor(heading/(Math.PI/12));
            Set<Cell> current=new HashSet<>();
            for(int j=0;j<steps;j++) {
                double t=(j+.5)/steps,lat=a.latitude+(b.latitude-a.latitude)*t,lng=a.longitude+(b.longitude-a.longitude)*t;
                Cell cell=new Cell(Math.round(lng*111195*Math.cos(Math.toRadians(lat))/5),Math.round(lat*111195/5),direction);
                if(count && seen.contains(cell)) repeated+=metres/steps;
                current.add(cell);
            }
            seen.addAll(current);
        }
        return repeated;
    }
}
