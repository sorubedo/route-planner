package io.github.sorubedo.routeplanner.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Closed cubic path with bounded tangents, sampled and indexed by metres. */
public final class RoutePath {
    public static final class Point {
        public final double latitude, longitude;
        public Point(double latitude, double longitude) {
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || Math.abs(latitude) > 85 || Math.abs(longitude) > 180) {
                throw new IllegalArgumentException("Invalid coordinate");
            }
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    public final List<Point> points;
    public final List<Point> visiblePoints;
    public final double length;
    private final double[] metres;

    /** Automatic plans already contain the validated corners and an explicit closing vertex. */
    public static RoutePath linear(List<Point> vertices) { return new RoutePath(vertices, true); }

    private RoutePath(List<Point> vertices, boolean linear) {
        if (vertices.size() < 3 || vertices.size() > 20000)
            throw new IllegalArgumentException("Invalid linear route size");
        if (distance(vertices.get(0), vertices.get(vertices.size()-1)) > 0.01)
            throw new IllegalArgumentException("Linear route must be closed");
        List<Point> samples = new ArrayList<>();
        for (Point point : vertices) {
            if (samples.isEmpty() || distance(samples.get(samples.size()-1), point) > 0.001) samples.add(point);
        }
        if (samples.size() < 3) throw new IllegalArgumentException("Points overlap");
        samples.set(samples.size()-1, samples.get(0));
        points = Collections.unmodifiableList(samples);
        visiblePoints = points;
        metres = new double[samples.size()];
        for (int i=1; i<samples.size(); i++) metres[i]=metres[i-1]+distance(samples.get(i-1),samples.get(i));
        length=metres[metres.length-1];
        if (length<30 || length>20000) throw new IllegalArgumentException("Closed route must be 30–20000 metres");
    }

    public RoutePath(List<Point> anchors) {
        if (anchors.size() < 2 || anchors.size() > 200) throw new IllegalArgumentException("Need 2–200 points");
        List<Point> clean = new ArrayList<>();
        for (Point point : anchors) {
            if (clean.isEmpty() || distance(clean.get(clean.size() - 1), point) >= 0.5) clean.add(point);
        }
        if (clean.size() > 2 && distance(clean.get(0), clean.get(clean.size() - 1)) < 0.5) clean.remove(clean.size() - 1);
        if (clean.size() < 2) throw new IllegalArgumentException("Points overlap");
        List<Point> samples = new ArrayList<>();
        int visibleEnd = 0;
        int n = clean.size();
        for (int i = 0; i < n; i++) {
            Point prev = clean.get((i + n - 1) % n), from = clean.get(i);
            Point to = clean.get((i + 1) % n), next = clean.get((i + 2) % n);
            double[] start = tangent(prev, from, to);
            double[] end = tangent(from, to, next);
            int count = Math.max(8, Math.min(250, (int) Math.ceil(distance(from, to) / 1.5)));
            for (int k = 0; k < count; k++) {
                double t = k / (double) count, t2 = t*t, t3 = t2*t;
                double a = 2*t3 - 3*t2 + 1, b = t3 - 2*t2 + t;
                double c = -2*t3 + 3*t2, d = t3 - t2;
                samples.add(new Point(a*from.latitude + b*start[0] + c*to.latitude + d*end[0],
                        a*from.longitude + b*start[1] + c*to.longitude + d*end[1]));
            }
            if (i == n - 2) visibleEnd = samples.size();
        }
        samples.add(clean.get(0));
        List<Point> visible = new ArrayList<>(samples.subList(0, visibleEnd));
        visible.add(clean.get(n - 1));
        points = Collections.unmodifiableList(samples);
        visiblePoints = Collections.unmodifiableList(visible);
        metres = new double[samples.size()];
        for (int i = 1; i < samples.size(); i++) metres[i] = metres[i-1] + distance(samples.get(i-1), samples.get(i));
        length = metres[metres.length-1];
        if (length < 30 || length > 20000) throw new IllegalArgumentException("Closed route must be 30–20000 metres");
    }

    // Half-length tangent avoids long/short neighbouring segments overshooting sharply.
    private static double[] tangent(Point prev, Point at, Point next) {
        double scale = Math.cos(Math.toRadians(at.latitude));
        double x = (next.longitude-prev.longitude)*scale, y = next.latitude-prev.latitude;
        double norm = Math.hypot(x,y);
        double magnitude = 0.5*Math.min(distance(prev,at),distance(at,next))/111195.0;
        if (norm < 1e-12) return new double[]{0,0};
        return new double[]{y/norm*magnitude, x/norm*magnitude/scale};
    }

    public Point at(double distance) {
        if (!Double.isFinite(distance)) throw new IllegalArgumentException("Invalid distance");
        double target = ((distance % length) + length) % length;
        int low=1, high=metres.length-1;
        while (low<high) { int mid=(low+high)>>>1; if (metres[mid]<target) low=mid+1; else high=mid; }
        Point a=points.get(low-1), b=points.get(low);
        double span=metres[low]-metres[low-1];
        double t=span<=0?0:(target-metres[low-1])/span;
        return new Point(a.latitude+(b.latitude-a.latitude)*t, a.longitude+(b.longitude-a.longitude)*t);
    }

    /** Absolute progress at the next corner, including the seam of subsequent laps. */
    public double nextVertexDistance(double distance) {
        if(!Double.isFinite(distance) || distance<0) throw new IllegalArgumentException("Invalid distance");
        double lap=Math.floor(distance/length), local=distance-lap*length;
        for(int i=1;i<metres.length;i++) if(metres[i]>local+0.001) return lap*length+metres[i];
        return (lap+1)*length+metres[1];
    }

    public float bearing(double distance) {
        Point from=at(distance), to=at(distance+1);
        double a=Math.toRadians(from.latitude), b=Math.toRadians(to.latitude);
        double lng=Math.toRadians(to.longitude-from.longitude);
        double degrees=Math.toDegrees(Math.atan2(Math.sin(lng)*Math.cos(b),
                Math.cos(a)*Math.sin(b)-Math.sin(a)*Math.cos(b)*Math.cos(lng)));
        return (float)((degrees+360)%360);
    }

    public static double distance(Point a, Point b) {
        double lat1=Math.toRadians(a.latitude), lat2=Math.toRadians(b.latitude);
        double x=Math.sin((lat2-lat1)/2), y=Math.sin(Math.toRadians(b.longitude-a.longitude)/2);
        double h=x*x+Math.cos(lat1)*Math.cos(lat2)*y*y;
        return 6371000*2*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h)));
    }
}
