package com.porter.qwerte.manager.gui.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * The pieces of a rounded rectangle: three plain rectangles and a triangle fan per corner. Pure.
 */
public final class RoundRect {
    private RoundRect() {
    }

    /** A radius no bigger than half of the shorter side, and never negative. */
    public static double clampRadius(double radius, double w, double h) {
        return Math.max(0, Math.min(radius, Math.min(w, h) / 2));
    }

    /** Segments per corner arc: 4 to 6, more for bigger corners; 0 for a square corner. */
    public static int segments(double radius) {
        if (radius <= 0) return 0;
        return (int) Math.max(4, Math.min(6, Math.ceil(radius / 3)));
    }

    /**
     * The corner triangles of a rounded rectangle as {@code {x1,y1,x2,y2,x3,y3}} arrays; empty for radius 0. The
     * triangles cover exactly the four corner squares' rounded parts.
     */
    public static List<double[]> corners(double x, double y, double w, double h, double radius) {
        double r = clampRadius(radius, w, h);
        List<double[]> out = new ArrayList<>();
        int n = segments(r);
        if (n == 0) return out;
        // Centre of each corner arc and the start angle (degrees, y down): top-left, top-right, bottom-right, bottom-left
        double[][] centres = {{x + r, y + r}, {x + w - r, y + r}, {x + w - r, y + h - r}, {x + r, y + h - r}};
        double[] start = {180, 270, 0, 90};
        for (int k = 0; k < 4; k++) {
            double cx = centres[k][0];
            double cy = centres[k][1];
            double px = cx + r * Math.cos(Math.toRadians(start[k]));
            double py = cy + r * Math.sin(Math.toRadians(start[k]));
            for (int i = 1; i <= n; i++) {
                double a = Math.toRadians(start[k] + 90.0 * i / n);
                double nx = cx + r * Math.cos(a);
                double ny = cy + r * Math.sin(a);
                // Same winding as Meteor's quads (the UI pipeline culls back faces)
                out.add(new double[] {cx, cy, nx, ny, px, py});
                px = nx;
                py = ny;
            }
        }
        return out;
    }
}
