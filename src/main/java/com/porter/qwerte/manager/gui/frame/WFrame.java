package com.porter.qwerte.manager.gui.frame;

import com.porter.qwerte.manager.gui.chrome.WHeaderBar;
import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WView;

import java.util.function.Consumer;

import static meteordevelopment.meteorclient.utils.Utils.getWindowHeight;
import static meteordevelopment.meteorclient.utils.Utils.getWindowWidth;

/**
 * The Stash Window's own window: a panel that can be moved by its header and resized from its edges and corners, with
 * the website's header bar on top and the page below. It replaces Meteor's {@code WWindow}, which sizes itself to its
 * content.
 *
 * <p>Its children, in this order (the first gets the mouse first): the overlay layer, the resize ring, the header, the
 * page view, and the drag handle over the header. They are drawn in a different order: header, page, resize ring, and
 * the overlay last, in its own scissor. The layout is done here, not by Meteor's cell logic, because a child needs to
 * know the room its parent gives it, which Meteor's bottom-up sizing doesn't pass down.
 *
 * <p>The geometry is in GUI pixels ({@link SmGuiTheme#unit()} physical pixels each) and is clamped to the screen on
 * every layout, so a changed GUI scale or a smaller game window never leaves the window out of reach.
 */
public class WFrame extends WContainer {
    /** Space around the page, in Meteor pixels (5 GUI pixels), and around a page drawn on a panel. */
    public static final double PAGE_PAD = 10;
    public static final double SURFACE_PAD = 20;

    public WOverlayLayer overlay;
    public WHeaderBar header;
    public WView page;

    private WResizeGrip grip;
    private WDragHandle drag;
    private WindowGeometry geom;
    private final Consumer<WindowGeometry> onGeometry;
    private final Runnable onClose;

    /** The room the page's widgets have, in physical pixels; valid after the frame's size is calculated. */
    public double pageW = 1;
    public double pageH = 1;
    /** True when the page is drawn on a panel of its own (every page except the stash page, which draws its own). */
    public boolean surfacePage = true;
    /**
     * The page is kept this far from the right and bottom edge so that its scroll bar is clear of the resize ring (the
     * ring is wider than the window's border), in physical pixels.
     */
    private double ringInset;

    /**
     * @param initial    where the window starts (clamped on the first layout)
     * @param onGeometry told after a move, a resize or a maximize has ended
     * @param onClose    what the Mac theme's red dot does
     */
    public WFrame(WindowGeometry initial, Consumer<WindowGeometry> onGeometry, Runnable onClose) {
        this.geom = initial;
        this.onGeometry = onGeometry;
        this.onClose = onClose;
    }

    @Override
    public void init() {
        overlay = new WOverlayLayer();
        add(overlay);
        grip = new WResizeGrip(this);
        add(grip);
        header = new WHeaderBar();
        add(header);
        page = theme.view();
        add(page);
        drag = new WDragHandle(this);
        add(drag);
        ((SmGuiTheme) theme).overlay = overlay;
    }

    private SmGuiTheme sm() {
        return (SmGuiTheme) theme;
    }

    // ---------- geometry ----------

    /** The rectangle the user asked for (what is saved); it may not fit the screen now. */
    public WindowGeometry geometry() {
        return geom;
    }

    /** The restored rectangle as it fits the screen now: what a resize starts from. */
    public WindowGeometry restored() {
        SmGuiTheme t = sm();
        return FrameMath.clamp(geom, t.screenW(), t.screenH());
    }

    public void setGeometry(WindowGeometry g) {
        if (g.equals(geom)) return;
        geom = g;
        invalidate();
    }

    /** The rectangle in use now, in GUI pixels (the screen when maximized). */
    public WindowGeometry effective() {
        SmGuiTheme t = sm();
        return FrameMath.clamp(geom, t.screenW(), t.screenH()).effective(t.screenW(), t.screenH());
    }

    public boolean maximized() {
        return geom.maximized();
    }

    public void toggleMaximized() {
        geom = FrameMath.toggleMaximized(geom);
        invalidate();
        changed();
    }

    /** Moves the window by whole GUI pixels, keeping it on the screen. */
    public void moveBy(int dx, int dy) {
        if (geom.maximized() || (dx == 0 && dy == 0)) return;
        SmGuiTheme t = sm();
        WindowGeometry before = restored();
        WindowGeometry after = FrameMath.move(before, dx, dy, t.screenW(), t.screenH());
        geom = after;
        int u = t.unit();
        if (after.x() != before.x() || after.y() != before.y()) move((after.x() - before.x()) * u, (after.y() - before.y()) * u);
    }

    /** Tells the owner a drag, resize or maximize ended. */
    public void changed() {
        if (onGeometry != null) onGeometry.accept(geom);
    }

    public void closeWindow() {
        if (onClose != null) onClose.run();
    }

    // ---------- layout ----------

    // ---------- failure guard ----------

    /** Set when the window's layout or drawing threw: the window then shows only an error and takes no input. */
    private Throwable failed;
    private static java.lang.reflect.Field scissorStack;

    /** The renderer's scissor depth (Meteor keeps a stack and doesn't reset it between frames), or -1 if unknown. */
    private static int scissorDepth(GuiRenderer renderer) {
        try {
            if (scissorStack == null) {
                java.lang.reflect.Field f = GuiRenderer.class.getDeclaredField("scissorStack");
                f.setAccessible(true);
                scissorStack = f;
            }
            return scissorStack.get(renderer) instanceof java.util.List<?> list ? list.size() : -1;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    private void fail(String where, Throwable e) {
        if (failed != null) return;
        failed = e;
        com.porter.qwerte.QwerteAddon.LOG.error("stash-window: the window's {} failed; it shows an error until it is reopened", where, e);
        // Nothing under the frame gets events or layout any more
        try {
            clear();
        } catch (RuntimeException ignored) {
            // the error text is still drawn
        }
    }

    public boolean failed() {
        return failed != null;
    }

    @Override
    public void calculateSize() {
        if (failed != null) {
            failedSize();
            return;
        }
        try {
            layoutSize();
        } catch (RuntimeException | LinkageError e) {
            fail("layout", e);
            failedSize();
        }
    }

    private void failedSize() {
        SmGuiTheme t = sm();
        int u = t.unit();
        WindowGeometry eff = restored().effective(t.screenW(), t.screenH());
        width = eff.w() * u;
        height = eff.h() * u;
    }

    @Override
    public void calculateWidgetPositions() {
        if (failed != null) {
            failedPositions();
            return;
        }
        try {
            super.calculateWidgetPositions();
        } catch (RuntimeException | LinkageError e) {
            fail("layout", e);
            failedPositions();
        }
    }

    private void failedPositions() {
        SmGuiTheme t = sm();
        int u = t.unit();
        WindowGeometry eff = restored().effective(t.screenW(), t.screenH());
        x = eff.x() * u;
        y = eff.y() * u;
    }

    private void layoutSize() {
        SmGuiTheme t = sm();
        int u = t.unit();
        // The geometry the user asked for is kept as it is; only what is drawn is fitted to the screen
        WindowGeometry eff = restored().effective(t.screenW(), t.screenH());
        Palette p = t.palette();
        double bw = p.borderWidth * u;
        ringInset = geom.maximized() ? 0 : Math.max(0, FrameMath.RING * u - bw);
        width = eff.w() * u;
        height = eff.h() * u;
        double fullW = width - 2 * bw;
        double innerW = fullW - ringInset;

        ThemeRepair.repair(this);
        header.innerWidth = fullW;
        header.cornerRadius = Math.max(0, p.radius * u - bw);
        header.calculateSize();

        double areaH = Math.max(1, height - 2 * bw - ringInset - header.height);
        double pad = t.scale(surfacePage ? SURFACE_PAD : PAGE_PAD);
        pageW = Math.max(1, innerW - 2 * pad);
        pageH = Math.max(1, areaH - 2 * pad);

        page.maxHeight = areaH;
        page.calculateSize();
        page.width = innerW;

        overlay.width = getWindowWidth();
        overlay.height = getWindowHeight();
        grip.width = width;
        grip.height = height;
        drag.width = fullW;
        drag.height = header.height;
    }

    @Override
    protected void onCalculateWidgetPositions() {
        SmGuiTheme t = sm();
        int u = t.unit();
        WindowGeometry eff = restored().effective(t.screenW(), t.screenH());
        x = eff.x() * u;
        y = eff.y() * u;
        double bw = t.palette().borderWidth * u;
        header.x = x + bw;
        header.y = y + bw;
        page.x = x + bw;
        page.y = header.y + header.height;
        overlay.x = 0;
        overlay.y = 0;
        grip.x = x;
        grip.y = y;
        drag.x = header.x;
        drag.y = header.y;
    }

    // ---------- drawing ----------

    @Override
    public boolean render(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (!visible) return true;
        if (failed != null) return renderFailed(renderer);
        int depth = scissorDepth(renderer);
        try {
            return renderFrame(renderer, mouseX, mouseY, delta);
        } catch (RuntimeException | LinkageError e) {
            // Close the scissors the failed frame left open: Meteor shares one renderer between all its screens
            if (depth >= 0) {
                for (int guard = 0; guard < 64 && scissorDepth(renderer) > depth; guard++) renderer.scissorEnd();
            }
            fail("drawing", e);
            return renderFailed(renderer);
        }
    }

    private boolean renderFailed(GuiRenderer renderer) {
        SmGuiTheme t = sm();
        Palette p = t.palette();
        int u = t.unit();
        Paint.rect(renderer, t, 0, 0, getWindowWidth(), getWindowHeight(), 0x5A000000);
        Paint.panel(renderer, t, x, y, width, height, p.radius * u, p.borderWidth * u, p.bg(), p.border());
        double tx = x + 6 * u, ty = y + 6 * u;
        String[] lines = {
            "The Stash window hit an error and stopped.",
            "Press Esc to close it, then open it again.",
            "The details are in the game log (latest.log): stash-window.",
            failed.getClass().getSimpleName()
        };
        for (String line : lines) {
            renderer.text(line, tx, ty, t.color(p.text()), false);
            ty += t.textHeight() + 2 * u;
        }
        return false;
    }

    private boolean renderFrame(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        SmGuiTheme t = sm();
        Palette p = t.palette();
        int u = t.unit();
        double bw = p.borderWidth * u;
        overlay.beginFrame();

        // A light dim behind the window, like the game's own screens
        Paint.rect(renderer, t, 0, 0, getWindowWidth(), getWindowHeight(), 0x5A000000);

        renderer.scissorStart(x, y, width, height);
        int fill = p.isMac() ? p.glass : p.bg();
        Paint.panel(renderer, t, x, y, width, height, p.radius * u, bw, fill, p.border());
        if (surfacePage) {
            double pad = t.scale(PAGE_PAD);
            double px = page.x + pad;
            double py = page.y + pad;
            double pw = width - 2 * bw - ringInset - 2 * pad;
            double ph = height - 2 * bw - ringInset - header.height - 2 * pad;
            Paint.panel(renderer, t, px, py, pw, ph, p.radius * u, bw, p.surface(), p.border());
            if (p.bevel) Paint.bevel(renderer, t, px + bw, py + bw, pw - 2 * bw, ph - 2 * bw, u, 0xFFFFFFFF, 0xFF555555);
        }
        header.render(renderer, mouseX, mouseY, delta);
        page.render(renderer, mouseX, mouseY, delta);
        drag.render(renderer, mouseX, mouseY, delta);
        grip.render(renderer, mouseX, mouseY, delta);
        renderer.scissorEnd();

        overlay.renderLayer(renderer);
        return false;
    }
}
