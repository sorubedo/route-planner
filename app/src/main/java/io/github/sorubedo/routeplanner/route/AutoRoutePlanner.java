package io.github.sorubedo.routeplanner.route;

import java.util.*;
import io.github.sorubedo.routeplanner.route.RoutePath.Point;

/** Route planning engine. All geometry is WGS84; no Android/SDK dependency. */
public final class AutoRoutePlanner {
    private static final double RAD=Math.PI/180, R=6371008.8;
    public record Checkpoint(Point position, double sort, boolean nfc) {}
    public record Road(List<Point> points, String highway, String foot, String access, String area, String oneWay) {}
    public record Input(List<Point> polygon, Point start, List<Checkpoint> checkpoints,
                        int clockMode, int targetMetres, long seed) {
        public Input {
            polygon=List.copyOf(polygon); checkpoints=List.copyOf(checkpoints);
            validatePolygon(polygon);
            require(start!=null && inside(start,polygon), "固定起点不在最终围栏内");
            require(checkpoints.size()<=24,"最多支持 24 个打卡点");
            require(targetMetres>=100 && targetMetres<=5000,"期望单圈长度需为 100–5000 米");
            for (Checkpoint p:checkpoints) require(p.position!=null && Double.isFinite(p.sort)
                    && inside(p.position,polygon),"打卡点坐标无效或不在最终围栏内");
        }
        public boolean ordered() { return clockMode==2 || clockMode==3; }
    }
    public record Segment(List<Point> route,String mode,String reason,int attempts,boolean cached) {
        public Segment { route=List.copyOf(route); }
    }
    public record Plan(List<Point> route, List<Integer> visitOrder, String mode, double repeatedMetres,List<Segment> segments) {
        public Plan(List<Point> route,List<Integer> visitOrder,String mode,double repeatedMetres) {
            this(route,visitOrder,mode,repeatedMetres,List.of());
        }
        public Plan { route=List.copyOf(route); visitOrder=List.copyOf(visitOrder); segments=List.copyOf(segments); }
        public RoutePath path() { return RoutePath.linear(route); }
    }
    private static void require(boolean condition,String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    private static boolean same(Point a,Point b) { return RoutePath.distance(a,b)<0.005; }
    private static Point lerp(Point a,Point b,double t) {
        return new Point(a.latitude+(b.latitude-a.latitude)*t,a.longitude+(b.longitude-a.longitude)*t);
    }
    private record Projection(Point point,double t,double distance) {}
    private static Projection project(Point p,Point a,Point b) {
        double scale=Math.cos(p.latitude*RAD),dx=(b.longitude-a.longitude)*scale,dy=b.latitude-a.latitude;
        double den=dx*dx+dy*dy;
        double t=den==0?0:Math.max(0,Math.min(1,((p.longitude-a.longitude)*scale*dx+(p.latitude-a.latitude)*dy)/den));
        Point point=lerp(a,b,t);
        return new Projection(point,t,RoutePath.distance(p,point));
    }
    public static boolean inside(Point p,List<Point> polygon) {
        boolean yes=false;
        for (int i=0,j=polygon.size()-1;i<polygon.size();j=i++) {
            Point a=polygon.get(j),b=polygon.get(i);
            if (project(p,a,b).distance<0.005) return true;
            if ((a.latitude>p.latitude)!=(b.latitude>p.latitude)
                    && p.longitude<(b.longitude-a.longitude)*(p.latitude-a.latitude)/(b.latitude-a.latitude)+a.longitude) yes=!yes;
        }
        return yes;
    }
    private static double intersection(Point a,Point b,Point c,Point d) {
        double rx=b.longitude-a.longitude,ry=b.latitude-a.latitude,sx=d.longitude-c.longitude,sy=d.latitude-c.latitude;
        double den=rx*sy-ry*sx;
        if (Math.abs(den)<1e-16) return Double.NaN;
        double qx=c.longitude-a.longitude,qy=c.latitude-a.latitude,t=(qx*sy-qy*sx)/den,u=(qx*ry-qy*rx)/den;
        return t>=-1e-9 && t<=1+1e-9 && u>=-1e-9 && u<=1+1e-9?Math.max(0,Math.min(1,t)):Double.NaN;
    }
    public static boolean segmentInside(Point a,Point b,List<Point> polygon) {
        if (!inside(a,polygon)||!inside(b,polygon)) return false;
        List<Double> ts=new ArrayList<>(List.of(0.0,1.0));
        for (int i=0;i<polygon.size();i++) {
            double t=intersection(a,b,polygon.get(i),polygon.get((i+1)%polygon.size()));
            if (Double.isFinite(t)) ts.add(t);
        }
        Collections.sort(ts);
        for (int i=1;i<ts.size();i++) if (!inside(lerp(a,b,(ts.get(i-1)+ts.get(i))/2),polygon)) return false;
        return true;
    }
    public static double[] bounds(List<Point> poly) {
        double s=90,w=180,n=-90,e=-180;
        for (Point p:poly) { s=Math.min(s,p.latitude); w=Math.min(w,p.longitude); n=Math.max(n,p.latitude); e=Math.max(e,p.longitude); }
        return new double[]{s,w,n,e};
    }
    private static double cross(Point a,Point b,Point c) {
        return (b.longitude-a.longitude)*(c.latitude-a.latitude)-(b.latitude-a.latitude)*(c.longitude-a.longitude);
    }
    public static void validatePolygon(List<Point> poly) {
        require(poly!=null && poly.size()>=3 && poly.size()<=80,"围栏需要 3–80 个顶点");
        double area=0;
        for (int i=0;i<poly.size();i++) {
            Point a=poly.get(i),b=poly.get((i+1)%poly.size());
            require(RoutePath.distance(a,b)>=0.1,"围栏存在重复或过近顶点");
            area+=cross(poly.get(0),a,b);
            for (int j=i+1;j<poly.size();j++) {
                if (j==i+1 || (i==0 && j==poly.size()-1)) continue;
                Point c=poly.get(j),d=poly.get((j+1)%poly.size());
                require(!Double.isFinite(intersection(a,b,c,d)) && !(Math.abs(cross(a,b,c))<1e-15 && project(c,a,b).distance<0.005)
                        && !(Math.abs(cross(a,b,d))<1e-15 && project(d,a,b).distance<0.005),"围栏边界自交或重叠");
            }
        }
        double[] box=bounds(poly);
        require(box[2]-box[0]<=0.05 && box[3]-box[1]<=0.07,"围栏超出校园计算范围");
        require(Math.abs(area)*R*R*RAD*RAD*Math.cos(poly.get(0).latitude*RAD)/2>=25,"围栏面积小于 25 平方米");
    }
    public static Point randomStart(List<Point> poly,Random random) {
        validatePolygon(poly); double[] b=bounds(poly);
        for (int i=0;i<2500;i++) {
            Point p=new Point(b[0]+random.nextDouble()*(b[2]-b[0]),b[1]+random.nextDouble()*(b[3]-b[1]));
            if (!inside(p,poly)) continue;
            boolean margin=true;
            for (int j=0;j<poly.size();j++) if (project(p,poly.get(j),poly.get((j+1)%poly.size())).distance<3) { margin=false; break; }
            if (margin) return p;
        }
        throw new IllegalArgumentException("围栏过窄，无法取得有边界余量的随机起点");
    }
    private static final class Edge {
        final int a,b,base; final double weight,from,to,baseMetres; final boolean road,directed;
        Edge(int a,int b,double weight,boolean road,boolean directed,int base,double from,double to,double baseMetres) {
            this.a=a; this.b=b; this.weight=weight; this.road=road; this.directed=directed;
            this.base=base; this.from=from; this.to=to; this.baseMetres=baseMetres;
        }
    }
    private record Link(int to,Edge edge) {}
    private record Walk(List<Integer> nodes,List<Edge> edges,double length) {}
    private static final class Usage {
        final Map<Integer,List<double[]>> spans=new HashMap<>();
        double overlap(Edge e) {
            double lo=Math.min(e.from,e.to),hi=Math.max(e.from,e.to),sum=0;
            for (double[] span:spans.getOrDefault(e.base,List.of())) sum+=Math.max(0,Math.min(hi,span[1])-Math.max(lo,span[0]));
            return sum*e.baseMetres;
        }
        double remember(List<Edge> edges) {
            double repeated=0;
            for (Edge e:edges) {
                repeated+=overlap(e);
                List<double[]> xs=new ArrayList<>(spans.getOrDefault(e.base,List.of()));
                xs.add(new double[]{Math.min(e.from,e.to),Math.max(e.from,e.to)});
                xs.sort(Comparator.comparingDouble(x->x[0]));
                List<double[]> merged=new ArrayList<>();
                for (double[] x:xs) {
                    if (!merged.isEmpty() && x[0]<=merged.get(merged.size()-1)[1]+1e-10)
                        merged.get(merged.size()-1)[1]=Math.max(merged.get(merged.size()-1)[1],x[1]);
                    else merged.add(x.clone());
                }
                spans.put(e.base,merged);
            }
            return repeated;
        }
    }
    private static void deadline(long until) {
        if (Thread.currentThread().isInterrupted() || (until>0 && System.nanoTime()>until))
            throw new IllegalStateException("道路规划截止，采用几何回退");
    }
    private static final class Graph {
        final List<Point> points=new ArrayList<>(); final List<List<Link>> adj=new ArrayList<>(); final List<Edge> edges=new ArrayList<>();
        int nextBase; final long until;
        final Map<SegmentKey,Integer> bases=new HashMap<>();
        Graph(long until) { this.until=until; }
        int node(Point p) { points.add(p); adj.add(new ArrayList<>()); return points.size()-1; }
        void edge(int a,int b,boolean road,boolean directed) {
            double metres=RoutePath.distance(points.get(a),points.get(b));
            PointKey x=new PointKey(points.get(a)),y=new PointKey(points.get(b));
            boolean forward=x.lat<y.lat || (x.lat==y.lat && x.lng<=y.lng);
            SegmentKey key=new SegmentKey(forward?x:y,forward?y:x);
            int base=bases.computeIfAbsent(key,k->nextBase++);
            edge(a,b,road,directed,base,forward?0:1,forward?1:0,metres);
        }
        void edge(int a,int b,boolean road,boolean directed,int base,double from,double to,double metres) {
            if (a==b) return;
            Edge e=new Edge(a,b,RoutePath.distance(points.get(a),points.get(b)),road,directed,base,from,to,metres);
            edges.add(e); adj.get(a).add(new Link(b,e)); if (!directed) adj.get(b).add(new Link(a,e));
        }
        Walk shortest(int from,int to,Edge banned,Usage usage) {
            deadline(until);
            double[] cost=new double[points.size()]; Arrays.fill(cost,Double.POSITIVE_INFINITY);
            int[] previous=new int[points.size()]; Edge[] prevEdge=new Edge[points.size()];
            PriorityQueue<double[]> heap=new PriorityQueue<>(Comparator.comparingDouble(x->x[0]));
            cost[from]=0; heap.add(new double[]{0,from}); int scanned=0;
            while (!heap.isEmpty()) {
                if ((scanned++&63)==0) deadline(until);
                double[] v=heap.remove(); int u=(int)v[1]; if (v[0]!=cost[u]) continue;
                if (u==to) {
                    List<Integer> nodes=new ArrayList<>(); List<Edge> es=new ArrayList<>(); nodes.add(u);
                    while (u!=from) { es.add(prevEdge[u]); u=previous[u]; nodes.add(u); }
                    Collections.reverse(nodes); Collections.reverse(es);
                    return new Walk(nodes,es,es.stream().mapToDouble(e->e.weight).sum());
                }
                for (Link link:adj.get(u)) {
                    Edge e=link.edge; double weight=e.weight+(usage==null?0:usage.overlap(e)*4);
                    if (e!=banned && cost[u]+weight<cost[link.to]) {
                        cost[link.to]=cost[u]+weight; previous[link.to]=u; prevEdge[link.to]=e;
                        heap.add(new double[]{cost[link.to],link.to});
                    }
                }
            }
            return null;
        }
    }
    public static boolean walkable(Road road) {
        if (road.highway==null || "yes".equals(road.area) || "no".equals(road.foot) || "private".equals(road.foot)) return false;
        if (("no".equals(road.access) || "private".equals(road.access)) && !("yes".equals(road.foot) || "designated".equals(road.foot) || "permissive".equals(road.foot))) return false;
        return Set.of("footway","path","pedestrian","steps","track","residential","service","living_street","unclassified",
                "tertiary","tertiary_link","secondary","secondary_link","primary","primary_link","cycleway").contains(road.highway);
    }
    private static Graph roadGraph(List<Road> roads,List<Point> poly,long until) {
        Graph g=new Graph(until); Map<PointKey,Integer> ids=new HashMap<>();
        for (Road road:roads) {
            deadline(until); if (!walkable(road)) continue;
            for (int i=1;i<road.points.size();i++) {
                Point a=road.points.get(i-1),b=road.points.get(i);
                if (same(a,b)||!segmentInside(a,b,poly)) continue;
                int x=ids.computeIfAbsent(new PointKey(a),k->g.node(a)),y=ids.computeIfAbsent(new PointKey(b),k->g.node(b));
                boolean reverse="-1".equals(road.oneWay),directed=reverse || "yes".equals(road.oneWay);
                g.edge(reverse?y:x,reverse?x:y,true,directed);
                require(g.points.size()<=6000 && g.edges.size()<=12000,"校园路网过大");
            }
        }
        return g;
    }
    private record SegmentKey(PointKey a,PointKey b) {}
    private record PointKey(long lat,long lng) {
        PointKey(Point p) { this(Math.round(p.latitude*1e9),Math.round(p.longitude*1e9)); }
    }
    private static int attach(Graph g,Point p,List<Point> poly) {
        Edge best=null; Projection snap=null;
        for (Edge e:g.edges) {
            deadline(g.until); if (!e.road) continue;
            Projection q=project(p,g.points.get(e.a),g.points.get(e.b));
            if (q.distance<=60 && (snap==null || q.distance<snap.distance) && segmentInside(p,q.point,poly)) { best=e; snap=q; }
        }
        if (best==null) return -1;
        Edge e=best;
        if (same(p,g.points.get(e.a))) return e.a;
        if (same(p,g.points.get(e.b))) return e.b;
        int q;
        if (same(snap.point,g.points.get(e.a))) q=e.a;
        else if (same(snap.point,g.points.get(e.b))) q=e.b;
        else {
            q=g.node(snap.point); double mid=e.from+(e.to-e.from)*snap.t;
            g.edges.remove(e); g.adj.get(e.a).removeIf(l->l.edge==e); g.adj.get(e.b).removeIf(l->l.edge==e);
            g.edge(e.a,q,true,e.directed,e.base,e.from,mid,e.baseMetres);
            g.edge(q,e.b,true,e.directed,e.base,mid,e.to,e.baseMetres);
        }
        if (same(p,snap.point)) return q;
        int id=g.node(p); g.edge(q,id,false,false); return id;
    }
    private static Graph geometryGraph(List<Point> poly,List<Point> anchors,long until) {
        Graph g=new Graph(until); for (Point p:anchors) g.node(p);
        // Only reflex corners can be bends of an interior shortest path. Convex corners
        // inflate the visibility graph without improving a path between interior points.
        double area=0; for(int i=0;i<poly.size();i++) area+=cross(poly.get(0),poly.get(i),poly.get((i+1)%poly.size()));
        for(int i=0;i<poly.size();i++) if(cross(poly.get((i+poly.size()-1)%poly.size()),poly.get(i),poly.get((i+1)%poly.size()))*area<0) g.node(poly.get(i));
        for (int i=0;i<g.points.size();i++) for (int j=i+1;j<g.points.size();j++) {
            deadline(until); Point a=g.points.get(i),b=g.points.get(j); boolean contains=false;
            for (int k=0;k<g.points.size();k++) {
                Point p=g.points.get(k);
                if (k!=i && k!=j && project(p,a,b).distance<0.05 && RoutePath.distance(a,p)>0.1 && RoutePath.distance(b,p)>0.1) { contains=true; break; }
            }
            if (!contains && segmentInside(a,b,poly)) g.edge(i,j,false,false);
        }
        return g;
    }
    private static double tourCost(List<Integer> order,Walk[][] paths) {
        double sum=0; int last=0;
        for (int i:order) { sum+=paths[last][i+1].length; last=i+1; }
        return sum+paths[last][0].length;
    }
    private static Plan tour(Graph g,List<Integer> ids,boolean ordered,List<Double> sorts,String mode) {
        int n=ids.size(); Walk[][] paths=ordered?null:new Walk[n][n];
        if(!ordered) for (int a=0;a<n;a++) for (int b=0;b<n;b++) {
            paths[a][b]=g.shortest(ids.get(a),ids.get(b),null,null);
            require(paths[a][b]!=null,"路网不连通");
        }
        List<Integer> order=new ArrayList<>(); for (int i=0;i<n-1;i++) order.add(i);
        if (ordered) order.sort(Comparator.comparingDouble(sorts::get));
        else {
            List<Integer> left=new ArrayList<>(order); order.clear(); int last=0;
            while (!left.isEmpty()) {
                final int from=last; left.sort(Comparator.comparingDouble(i->paths[from][i+1].length));
                int index=left.remove(0); order.add(index); last=index+1;
            }
            for (int round=0;round<20;round++) {
                boolean improved=false;
                outer: for (int i=0;i<order.size();i++) for (int j=0;j<order.size();j++) {
                    List<Integer> next=new ArrayList<>(order); next.add(j,next.remove(i));
                    if (tourCost(next,paths)+0.01<tourCost(order,paths)) { order=next; improved=true; break outer; }
                }
                if (!improved) break;
            }
            // Remove crossings as well as single-point ordering mistakes.
            for(int round=0;round<20;round++) {
                boolean improved=false;
                outer: for(int i=0;i<order.size();i++) for(int j=i+1;j<order.size();j++) {
                    List<Integer> next=new ArrayList<>(order); Collections.reverse(next.subList(i,j+1));
                    if(tourCost(next,paths)+0.01<tourCost(order,paths)) {order=next;improved=true;break outer;}
                }
                if(!improved) break;
            }
        }
        List<Point> route=new ArrayList<>(List.of(g.points.get(ids.get(0))));
        Usage usage=new Usage(); double repeated=0; int last=ids.get(0);
        List<Integer> visits=new ArrayList<>(); for (int i:order) visits.add(ids.get(i+1)); visits.add(ids.get(0));
        for (int next:visits) {
            Walk leg=g.shortest(last,next,null,usage); require(leg!=null,"路网不连通");
            repeated+=usage.remember(leg.edges); for (int i=1;i<leg.nodes.size();i++) route.add(g.points.get(leg.nodes.get(i)));
            last=next;
        }
        return new Plan(route,order,mode,repeated);
    }
    private static Plan roadLoop(Graph g,int start,int target,Random random) {
        List<Edge> edges=new ArrayList<>(g.edges); Plan best=null; double bestScore=Double.POSITIVE_INFINITY;
        for (int i=0;i<Math.min(50,edges.size());i++) {
            Edge e=edges.get(edges.size()<=50?i:random.nextInt(edges.size())); if (!e.road) continue;
            Walk outward=g.shortest(start,e.a,null,null); if (outward==null) continue;
            Usage usage=new Usage(); double repeated=usage.remember(outward.edges)+usage.remember(List.of(e));
            Walk back=g.shortest(e.b,start,e,usage); if (back==null) continue;
            repeated+=usage.remember(back.edges);
            List<Point> route=new ArrayList<>(); for (int id:outward.nodes) route.add(g.points.get(id));
            route.add(g.points.get(e.b)); for (int j=1;j<back.nodes.size();j++) route.add(g.points.get(back.nodes.get(j)));
            double length=length(route); if (length<80 || repeated/length>0.49) continue;
            double score=Math.abs(length-target)+repeated*4;
            if (score<bestScore) { bestScore=score; best=new Plan(route,List.of(),"roads",repeated); }
        }
        return best;
    }
    private static Plan geometricLoop(Input input,long until) {
        List<Point> poly=input.polygon; Point start=input.start; double[] b=bounds(poly);
        double scale=Math.min(RoutePath.distance(new Point(b[0],b[1]),new Point(b[2],b[1])),
                RoutePath.distance(new Point(b[0],b[1]),new Point(b[0],b[3])));
        double radius=Math.min(input.targetMetres/(2*Math.PI),scale*0.35);
        for (int k=0;k<9;k++) for (int direction=0;direction<12;direction++) {
            deadline(until); double r=radius*Math.pow(0.75,k),heading=direction*Math.PI/6,latRadius=r/R/RAD,lngRadius=latRadius/Math.cos(start.latitude*RAD);
            Point center=new Point(start.latitude-latRadius*Math.sin(heading),start.longitude-lngRadius*Math.cos(heading));
            List<Point> route=new ArrayList<>(List.of(start)); boolean valid=true;
            for (int i=1;i<24;i++) {
                double a=heading+i*Math.PI/12; route.add(new Point(center.latitude+latRadius*Math.sin(a),center.longitude+lngRadius*Math.cos(a)));
            }
            route.add(start);
            for (int i=1;i<route.size();i++) if (!segmentInside(route.get(i-1),route.get(i),poly)) { valid=false; break; }
            if (valid && length(route)>=50) return new Plan(route,List.of(),"geometry",0);
        }
        List<Point> candidates=new ArrayList<>();
        for (int i=0;i<poly.size();i++) candidates.add(lerp(poly.get(i),poly.get((i+1)%poly.size()),0.5));
        candidates.sort(Comparator.comparingDouble(p->-RoutePath.distance(start,p)));
        List<Point> anchors=new ArrayList<>(List.of(start)); anchors.addAll(candidates.subList(0,Math.min(3,candidates.size())));
        Plan p=tour(geometryGraph(poly,anchors,until),indices(anchors.size()),false,List.of(),"geometry");
        return new Plan(p.route,List.of(),p.mode,p.repeatedMetres);
    }
    private static List<Integer> indices(int n) { List<Integer> xs=new ArrayList<>(); for(int i=0;i<n;i++) xs.add(i); return xs; }
    private static double length(List<Point> route) { double n=0; for(int i=1;i<route.size();i++) n+=RoutePath.distance(route.get(i-1),route.get(i)); return n; }
    public static Plan plan(Input input,List<Road> roads,long deadlineNanos) {
        List<Point> anchors=new ArrayList<>(List.of(input.start)); List<Double> sorts=new ArrayList<>();
        for (Checkpoint cp:input.checkpoints) { anchors.add(cp.position); sorts.add(cp.sort); }
        Plan result=null;
        if (roads!=null && !roads.isEmpty()) {
            Graph g=roadGraph(roads,input.polygon,deadlineNanos); List<Integer> ids=new ArrayList<>();
            for (Point p:anchors) { int id=attach(g,p,input.polygon); require(id>=0,"起点或打卡点无法连接 60 米内步行道路"); ids.add(id); }
            if (anchors.size()>=3) result=tour(g,ids,input.ordered(),sorts,"roads");
            else {
                result=roadLoop(g,ids.get(0),input.targetMetres,new Random(input.seed));
                if (result!=null && anchors.size()==2) {
                    for (int i=1;i<result.route.size()-1;i+=Math.max(1,(result.route.size()-2+5)/6)) {
                        int id=attach(g,result.route.get(i),input.polygon); if (id>=0) ids.add(id);
                    }
                    result=tour(g,ids,false,List.of(),"roads");
                    result=new Plan(result.route,List.of(0),result.mode,result.repeatedMetres);
                }
            }
        }
        if (result==null) {
            if (anchors.size()>=3) result=tour(geometryGraph(input.polygon,anchors,deadlineNanos),indices(anchors.size()),input.ordered(),sorts,"geometry");
            if (result==null || length(result.route)<30) {
                Plan ring=geometricLoop(input,deadlineNanos);
                if (input.checkpoints.isEmpty()) result=ring;
                else {
                    for (int i=1;i<ring.route.size()-1;i+=4) anchors.add(ring.route.get(i));
                    List<Double> augmentedSorts=new ArrayList<>(sorts);
                    while (augmentedSorts.size()<anchors.size()-1) augmentedSorts.add(Double.MAX_VALUE);
                    result=tour(geometryGraph(input.polygon,anchors,deadlineNanos),indices(anchors.size()),input.ordered(),augmentedSorts,"geometry");
                    result=new Plan(result.route,result.visitOrder.stream().filter(i->i<input.checkpoints.size()).collect(java.util.stream.Collectors.toList()),result.mode,result.repeatedMetres);
                }
            }
        }
        List<Point> clean=new ArrayList<>();
        for (Point p:result.route) if (clean.isEmpty() || !same(clean.get(clean.size()-1),p)) clean.add(p);
        require(clean.size()>=3 && same(clean.get(0),clean.get(clean.size()-1)),"自动路线未闭合");
        for (int i=1;i<clean.size();i++) require(segmentInside(clean.get(i-1),clean.get(i),input.polygon),"自动路线越过围栏");
        int scan=0;
        for (int checkpoint:result.visitOrder) {
            Point p=input.checkpoints.get(checkpoint).position;
            while (scan<clean.size() && !same(clean.get(scan),p)) scan++;
            require(scan<clean.size(),"自动路线没有依次到达打卡点");
        }
        for (Checkpoint cp:input.checkpoints) require(clean.stream().anyMatch(p->same(cp.position,p)),"自动路线遗漏打卡点");
        result=new Plan(clean,result.visitOrder,result.mode,result.repeatedMetres); result.path(); return result;
    }
    public static Point wgsToGcj(Point p) {
        double lat=p.latitude,lng=p.longitude;
        if (lng<72.004 || lng>137.8347 || lat<0.8293 || lat>55.8271) return p;
        double x=lng-105,y=lat-35,pi=Math.PI;
        double dlat=-100+2*x+3*y+0.2*y*y+0.1*x*y+0.2*Math.sqrt(Math.abs(x));
        dlat+=(20*Math.sin(6*x*pi)+20*Math.sin(2*x*pi))*2/3+(20*Math.sin(y*pi)+40*Math.sin(y/3*pi))*2/3+(160*Math.sin(y/12*pi)+320*Math.sin(y*pi/30))*2/3;
        double dlng=300+x+2*y+0.1*x*x+0.1*x*y+0.1*Math.sqrt(Math.abs(x));
        dlng+=(20*Math.sin(6*x*pi)+20*Math.sin(2*x*pi))*2/3+(20*Math.sin(x*pi)+40*Math.sin(x/3*pi))*2/3+(150*Math.sin(x/12*pi)+300*Math.sin(x/30*pi))*2/3;
        double rad=lat*RAD,magic=1-0.006693421622965943*Math.pow(Math.sin(rad),2),sqrt=Math.sqrt(magic);
        return new Point(lat+dlat*180/((6378245*(1-0.006693421622965943)/(magic*sqrt))*pi),lng+dlng*180/(6378245/sqrt*Math.cos(rad)*pi));
    }
    public static Point gcjToWgs(Point p) {
        Point q=p;
        for(int i=0;i<8;i++) { Point f=wgsToGcj(q); q=new Point(q.latitude+p.latitude-f.latitude,q.longitude+p.longitude-f.longitude); }
        return q;
    }
}
