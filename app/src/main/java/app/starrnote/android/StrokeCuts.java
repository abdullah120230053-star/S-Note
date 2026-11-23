package app.starrnote.android;

import java.util.ArrayList;
import java.util.List;

final class StrokeCuts {
    private StrokeCuts() {}

    static boolean supports(Notebook.Mark mark) {
        return Brushes.isInk(mark.tool) || Brushes.isMarker(mark.tool) || mark.tool.equals("Tape");
    }

    /** Returns null if the eraser misses, or the surviving pieces of the stroke. */
    static List<Notebook.Mark> cut(Notebook.Mark mark, float x, float y, float radius) {
        List<Notebook.Point> points = mark.points;
        if (points.isEmpty()) return null;
        double radiusSquared = (double) radius * radius;
        if (points.size() == 1) return inside(points.get(0), x, y, radiusSquared) ? new ArrayList<>() : null;
        List<Notebook.Mark> pieces = new ArrayList<>();
        List<Notebook.Point> outside = new ArrayList<>();
        boolean hit = false;
        for (int i = 1; i < points.size(); i++) {
            Notebook.Point a = points.get(i - 1), b = points.get(i);
            double dx = b.x() - a.x(), dy = b.y() - a.y();
            double lengthSquared = dx * dx + dy * dy;
            if (lengthSquared == 0) {
                if (inside(a, x, y, radiusSquared)) hit = true;
                else append(outside, a);
                continue;
            }
            double ax = a.x() - x, ay = a.y() - y;
            double nearest = -(ax * dx + ay * dy) / lengthSquared;
            double closestX = ax + nearest * dx, closestY = ay + nearest * dy;
            double depth = (radiusSquared - closestX * closestX - closestY * closestY) / lengthSquared;
            double half = Math.sqrt(Math.max(0, depth));
            double start = Math.max(0, nearest - half), end = Math.min(1, nearest + half);
            if (depth <= 0 || start >= end) {
                append(outside, a);
                append(outside, b);
                continue;
            }
            hit = true;
            if (start > 0) {
                append(outside, a);
                append(outside, interpolate(a, b, start));
            }
            addPiece(mark, pieces, outside);
            if (end < 1) {
                append(outside, interpolate(a, b, end));
                append(outside, b);
            }
        }
        if (!hit) return null;
        addPiece(mark, pieces, outside);
        return pieces;
    }

    private static boolean inside(Notebook.Point point, float x, float y, double limit) {
        double dx = point.x() - x, dy = point.y() - y;
        return dx * dx + dy * dy < limit;
    }

    private static Notebook.Point interpolate(Notebook.Point a, Notebook.Point b, double t) {
        return new Notebook.Point((float) (a.x() + (b.x() - a.x()) * t),
                (float) (a.y() + (b.y() - a.y()) * t),
                (float) (a.pressure() + (b.pressure() - a.pressure()) * t));
    }

    private static void append(List<Notebook.Point> points, Notebook.Point point) {
        if (points.isEmpty() || !points.get(points.size() - 1).equals(point)) points.add(point);
    }

    private static void addPiece(Notebook.Mark mark, List<Notebook.Mark> pieces, List<Notebook.Point> points) {
        if (points.isEmpty()) return;
        Notebook.Mark piece = mark.copy();
        piece.points.clear();
        piece.points.addAll(points);
        pieces.add(piece);
        points.clear();
    }
}
