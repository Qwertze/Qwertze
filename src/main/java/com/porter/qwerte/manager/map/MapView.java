package com.porter.qwerte.manager.map;

/**
 * Where the map is looking: the world block at the middle of the view, the zoom (pixels per block) and whether the view
 * follows the bot. North is up and +x is to the right, like the website. Pure.
 */
public final class MapView {
    public static final double ZOOM_MIN = 0.5;
    public static final double ZOOM_MAX = 16;
    public static final double ZOOM_DEFAULT = 4;
    /** One zoom button press or wheel notch. */
    public static final double STEP = 1.25;

    private double zoom = ZOOM_DEFAULT;
    private double cx;
    private double cz;
    private boolean follow = true;
    private double width = 1;
    private double height = 1;

    public void setViewport(double width, double height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    public double zoom() {
        return zoom;
    }

    public double centreX() {
        return cx;
    }

    public double centreZ() {
        return cz;
    }

    public boolean following() {
        return follow;
    }

    /** Follow the bot again (and jump to it on the next {@link #track}). */
    public void follow() {
        follow = true;
    }

    /** The bot moved: the view goes with it while following. */
    public void track(double x, double z) {
        if (follow) {
            cx = x;
            cz = z;
        }
    }

    /** Looks at this block and stops following. */
    public void lookAt(double x, double z) {
        cx = x;
        cz = z;
        follow = false;
    }

    /** Drags the picture by pixels: the view moves the other way and stops following. */
    public void pan(double dxPx, double dyPx) {
        if (dxPx == 0 && dyPx == 0) return;
        cx -= dxPx / zoom;
        cz -= dyPx / zoom;
        follow = false;
    }

    /**
     * Zooms by {@code factor} (clamped to 0.5..16). When not following, the block under (px, py), pixels from the top left of
     * the view, stays where it is.
     */
    public void zoomAt(double factor, double px, double py) {
        double z = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom * factor));
        if (z == zoom) return;
        if (!follow) {
            double bx = toWorldX(px);
            double bz = toWorldZ(py);
            zoom = z;
            cx = bx - (px - width / 2) / zoom;
            cz = bz - (py - height / 2) / zoom;
        } else {
            zoom = z;
        }
    }

    /** Zoom around the middle of the view. */
    public void zoomBy(double factor) {
        zoomAt(factor, width / 2, height / 2);
    }

    public double toScreenX(double wx) {
        return (wx - cx) * zoom + width / 2;
    }

    public double toScreenY(double wz) {
        return (wz - cz) * zoom + height / 2;
    }

    public double toWorldX(double sx) {
        return (sx - width / 2) / zoom + cx;
    }

    public double toWorldZ(double sy) {
        return (sy - height / 2) / zoom + cz;
    }
}
