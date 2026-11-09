package com.porter.qwerte.manager.gui.widgets;

import com.porter.qwerte.compat.MapTexture;
import com.porter.qwerte.manager.map.MapAtlas;
import com.porter.qwerte.manager.map.MapView;
import com.porter.qwerte.manager.map.ViewerEvent.Ent;
import com.porter.qwerte.manager.map.ViewerEvent.Self;
import com.porter.qwerte.manager.map.ViewerState;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The live map: one textured quad (the atlas, see {@link MapAtlas}) clipped to the widget, and the markers on top. Uses only
 * Meteor's version-independent widget events: a drag (press and move) pans, the wheel zooms around the cursor, a right click
 * without a drag follows the bot again. Left and right drags both pan.
 */
public final class WMapView extends WPressable {
    private static final Color BACKGROUND = new Color(24, 26, 32, 255);
    private static final Color OUTLINE = new Color(0, 0, 0, 255);
    private static final Color PLAYER = new Color(70, 140, 255, 255);
    private static final Color PASSIVE = new Color(80, 190, 100, 255);
    private static final Color HOSTILE = new Color(230, 70, 70, 255);
    private static final Color ITEM = new Color(230, 190, 60, 255);
    private static final Color OTHER = new Color(150, 150, 160, 255);
    private static final Color SELF_FILL = new Color(255, 255, 255, 255);
    private static final Color SELF_EDGE = new Color(70, 140, 255, 255);
    private static final Color LABEL_BACK = new Color(10, 10, 14, 215);
    private static final Color LABEL_TEXT = new Color(255, 255, 255, 255);
    private static final Color HINT = new Color(200, 200, 210, 255);

    private final MapView view;
    private final MapAtlas atlas;
    private final ViewerState state;
    private final Supplier<MapTexture> texture;
    private final BooleanSupplier hideNames;
    private final Supplier<String> hint;

    /** The widest the map may be (its column), in physical pixels; 0 for no cap. */
    public double maxWidth;

    private double cursorX = -1;
    private double cursorY = -1;
    private boolean dragged;

    /**
     * @param texture   the map's texture, or null before it exists
     * @param hideNames true while player names must not be drawn (streamer display)
     * @param hint      a line to draw in the middle while there is nothing to show (or null)
     */
    public WMapView(MapView view, MapAtlas atlas, ViewerState state, Supplier<MapTexture> texture, BooleanSupplier hideNames, Supplier<String> hint) {
        this.view = view;
        this.atlas = atlas;
        this.state = state;
        this.texture = texture;
        this.hideNames = hideNames;
        this.hint = hint;
    }

    @Override
    protected void onCalculateSize() {
        double w = Math.max(1, Utils.getWindowWidth());
        double h = Math.max(1, Utils.getWindowHeight());
        // The window may be smaller than the screen: size from the window when there is one
        for (meteordevelopment.meteorclient.gui.widgets.WWidget up = parent; up != null; up = up.parent) {
            if (up instanceof com.porter.qwerte.manager.gui.frame.WFrame f) {
                w = Math.max(1, f.width);
                h = Math.max(1, f.height);
                break;
            }
        }
        width = Math.max(theme.scale(260), Math.min(theme.scale(640), w * 0.5));
        double fit = h * 0.6;
        for (meteordevelopment.meteorclient.gui.widgets.WWidget up = parent; up != null; up = up.parent) {
            if (up instanceof com.porter.qwerte.manager.gui.frame.WFrame f) {
                // The bot's inventory, hotbar and gear sit under the map (about 102 GUI pixels), with the stat rows and the page
                // padding: the map takes what is left of the page, so all of it fits without scrolling
                int u = com.porter.qwerte.manager.gui.style.SmGuiTheme.unitOf(theme);
                fit = f.pageH - u * 190.0;
                break;
            }
        }
        height = Math.max(theme.scale(120), Math.min(theme.scale(520), fit));
        if (maxWidth > 0) width = Math.max(theme.scale(120), Math.min(width, maxWidth));
    }

    @Override
    public void onMouseMoved(double mouseX, double mouseY, double lastMouseX, double lastMouseY) {
        cursorX = mouseX - x;
        cursorY = mouseY - y;
        if (pressed) {
            double dx = mouseX - lastMouseX;
            double dy = mouseY - lastMouseY;
            if (dx != 0 || dy != 0) {
                view.pan(dx, dy);
                dragged = true;
            }
        }
    }

    @Override
    public boolean onMouseScrolled(double amount) {
        if (!mouseOver || amount == 0) return false;
        view.zoomAt(amount > 0 ? MapView.STEP : 1 / MapView.STEP, cursorX < 0 ? width / 2 : cursorX, cursorY < 0 ? height / 2 : cursorY);
        return true;
    }

    @Override
    protected void onPressed(int button) {
        if (dragged) {
            dragged = false;
            return;
        }
        if (button == 1) view.follow();
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        view.setViewport(width, height);
        Self self = state.self();
        if (self != null) view.track(self.x(), self.z());

        renderer.quad(x, y, width, height, BACKGROUND);

        // The picture first, in a scissor of its own: Meteor draws a texture after the quads of its scissor, so the markers
        // go in a second scissor that is closed later
        MapTexture mt = texture.get();
        if (mt != null && mt.texture() != null && atlas.placed()) {
            double size = MapAtlas.SIZE * view.zoom();
            double px = x + view.toScreenX(atlas.originX() * 16.0);
            double py = y + view.toScreenY(atlas.originZ() * 16.0);
            renderer.scissorStart(x, y, width, height);
            renderer.texture(px, py, size, size, 0, mt.texture());
            renderer.scissorEnd();
        }

        renderer.scissorStart(x, y, width, height);
        drawMarkers(renderer, self);
        String h = hint.get();
        if (h != null && !h.isEmpty()) {
            double w = theme.textWidth(h);
            renderer.text(h, x + (width - w) / 2, y + height / 2 - theme.textHeight() / 2, HINT, false);
        }
        renderer.scissorEnd();

        double t = Math.max(1, theme.scale(1));
        renderer.quad(x, y, width, t, OUTLINE);
        renderer.quad(x, y + height - t, width, t, OUTLINE);
        renderer.quad(x, y, t, height, OUTLINE);
        renderer.quad(x + width - t, y, t, height, OUTLINE);
    }

    private void drawMarkers(GuiRenderer renderer, Self self) {
        double s = theme.scale(1);
        double r = Math.max(2.5, Math.min(6, view.zoom() * 0.9)) * s;
        boolean names = !hideNames.getAsBoolean();
        List<Ent> ents = state.ents();
        for (int i = ents.size() - 1; i >= 0; i--) {
            Ent e = ents.get(i);
            double ex = x + view.toScreenX(e.x());
            double ey = y + view.toScreenY(e.z());
            if (ex < x - 40 || ey < y - 40 || ex > x + width + 40 || ey > y + height + 40) continue;
            switch (e.kind()) {
                case "player" -> {
                    dot(renderer, ex, ey, r * 1.3, PLAYER);
                    if (names && !e.name().isEmpty()) label(renderer, e.name(), ex, ey - r * 1.6 - theme.textHeight());
                }
                case "mob" -> dot(renderer, ex, ey, r * 0.8, e.hostile() ? HOSTILE : PASSIVE);
                case "item" -> {
                    double q = Math.max(2 * s, r * 0.7);
                    renderer.quad(ex - q / 2, ey - q / 2, q, q, ITEM);
                }
                default -> renderer.quad(ex - 1.5 * s, ey - 1.5 * s, 3 * s, 3 * s, OTHER);
            }
        }
        if (self != null) arrow(renderer, x + view.toScreenX(self.x()), y + view.toScreenY(self.z()), self.yaw(), 11 * s);
    }

    private static void dot(GuiRenderer renderer, double cx, double cy, double radius, Color color) {
        renderer.quad(cx - radius - 1, cy - radius - 1, radius * 2 + 2, radius * 2 + 2, GuiRenderer.CIRCLE, OUTLINE);
        renderer.quad(cx - radius, cy - radius, radius * 2, radius * 2, GuiRenderer.CIRCLE, color);
    }

    private void label(GuiRenderer renderer, String text, double cx, double top) {
        double w = theme.textWidth(text);
        double pad = theme.scale(3);
        renderer.quad(cx - w / 2 - pad, top - 1, w + pad * 2, theme.textHeight() + 2, LABEL_BACK);
        renderer.text(text, cx - w / 2, top, LABEL_TEXT, false);
    }

    /** The bot: an arrow along its heading. Minecraft yaw 0 is +z (south, down the screen) and 90 is -x (west, left). */
    private static void arrow(GuiRenderer renderer, double bx, double by, double yawDegrees, double len) {
        double a = Math.toRadians(yawDegrees);
        double dx = -Math.sin(a);
        double dy = Math.cos(a);
        double tipX = bx + dx * len;
        double tipY = by + dy * len;
        double lx = bx - dx * len * 0.6 + dy * len * 0.6;
        double ly = by - dy * len * 0.6 - dx * len * 0.6;
        double rx = bx - dx * len * 0.6 - dy * len * 0.6;
        double ry = by - dy * len * 0.6 + dx * len * 0.6;
        double nx = bx - dx * len * 0.25;
        double ny = by - dy * len * 0.25;
        double grow = 1.18;
        renderer.triangle(bx + (tipX - bx) * grow, by + (tipY - by) * grow, bx + (lx - bx) * grow, by + (ly - by) * grow, nx, ny, SELF_EDGE);
        renderer.triangle(bx + (tipX - bx) * grow, by + (tipY - by) * grow, nx, ny, bx + (rx - bx) * grow, by + (ry - by) * grow, SELF_EDGE);
        renderer.triangle(tipX, tipY, lx, ly, nx, ny, SELF_FILL);
        renderer.triangle(tipX, tipY, nx, ny, rx, ry, SELF_FILL);
    }
}
