package com.porter.qwerte.manager.gui.style;

import com.porter.qwerte.manager.gui.layout.RoundRect;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;

import java.util.List;

/**
 * Drawing helpers for the window's chrome: rectangles, rounded panels, borders, Classic's bevel, pills and dots. Every
 * size is in physical pixels (multiply GUI pixels by {@link SmGuiTheme#unit()} first). Colours are ARGB ints.
 */
public final class Paint {
    private Paint() {
    }

    public static void rect(GuiRenderer r, SmGuiTheme t, double x, double y, double w, double h, int argb) {
        if (Palette.alpha(argb) == 0 || w <= 0 || h <= 0) return;
        r.quad(x, y, w, h, t.color(argb));
    }

    /** A filled rectangle with rounded corners (radius in physical pixels; 0 is a plain rectangle). */
    public static void roundRect(GuiRenderer r, SmGuiTheme t, double x, double y, double w, double h, double radius, int argb) {
        if (Palette.alpha(argb) == 0 || w <= 0 || h <= 0) return;
        double rad = RoundRect.clampRadius(radius, w, h);
        if (rad < 1) {
            r.quad(x, y, w, h, t.color(argb));
            return;
        }
        var c = t.color(argb);
        r.quad(x + rad, y, w - 2 * rad, h, c);
        r.quad(x, y + rad, rad, h - 2 * rad, c);
        r.quad(x + w - rad, y + rad, rad, h - 2 * rad, c);
        List<double[]> tris = RoundRect.corners(x, y, w, h, rad);
        for (double[] p : tris) r.triangle(p[0], p[1], p[2], p[3], p[4], p[5], c);
    }

    /** A rectangle with a border and a fill; the border is drawn as the outer shape and the fill inset by it. */
    public static void panel(GuiRenderer r, SmGuiTheme t, double x, double y, double w, double h, double radius, double border, int fill, int borderColor) {
        if (border > 0 && Palette.alpha(borderColor) != 0 && Palette.alpha(fill) < 255) {
            // A see-through fill (Mac's glass) must not sit on a filled border colour: draw the border as a ring
            roundRect(r, t, x + border, y + border, w - 2 * border, h - 2 * border, Math.max(0, radius - border), fill);
            outline(r, t, x, y, w, h, border, borderColor);
        } else if (border > 0 && Palette.alpha(borderColor) != 0) {
            roundRect(r, t, x, y, w, h, radius, borderColor);
            roundRect(r, t, x + border, y + border, w - 2 * border, h - 2 * border, Math.max(0, radius - border), fill);
        } else {
            roundRect(r, t, x, y, w, h, radius, fill);
        }
    }

    /** Only the border line, as four strips (the inside stays as it is). */
    public static void outline(GuiRenderer r, SmGuiTheme t, double x, double y, double w, double h, double border, int argb) {
        rect(r, t, x, y, w, border, argb);
        rect(r, t, x, y + h - border, w, border, argb);
        rect(r, t, x, y + border, border, h - 2 * border, argb);
        rect(r, t, x + w - border, y + border, border, h - 2 * border, argb);
    }

    /** The vanilla GUI bevel: light on the top and left edges, dark on the bottom and right, inside the rectangle. */
    public static void bevel(GuiRenderer r, SmGuiTheme t, double x, double y, double w, double h, double thickness, int light, int dark) {
        rect(r, t, x, y, w - thickness, thickness, light);
        rect(r, t, x, y + thickness, thickness, h - thickness, light);
        rect(r, t, x + thickness, y + h - thickness, w - thickness, thickness, dark);
        rect(r, t, x + w - thickness, y, thickness, h - thickness, dark);
    }

    /** A round dot (a circle of triangles), centred on ({@code cx, cy}). */
    public static void dot(GuiRenderer r, SmGuiTheme t, double cx, double cy, double radius, int argb) {
        roundRect(r, t, cx - radius, cy - radius, radius * 2, radius * 2, radius, argb);
    }

    /** A small triangle pointing down (a select's arrow); its box is {@code size} wide. */
    public static void chevronDown(GuiRenderer r, SmGuiTheme t, double x, double y, double size, int argb) {
        double h = size / 2;
        // Same winding as the quads (the UI pipeline culls back faces)
        r.triangle(x, y, x + size / 2, y + h, x + size, y, t.color(argb));
    }
}
