package com.porter.qwerte.manager.gui.frame;

import com.porter.qwerte.manager.gui.frame.FrameMath.Edge;
import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;

/**
 * The resize ring of the window: it is "over" only on the 3 GUI pixel ring around the window's edge and the bottom-right
 * grip square, so it takes those clicks before anything under it and nothing else. It uses no click handler of its own
 * (their signatures differ between Minecraft versions): a press is Meteor's {@code WPressable} press, and the drag is
 * followed from the pointer position Meteor passes to {@code render} every frame. A drag ends when the press does, or
 * as soon as the left mouse button is seen up (a release outside the game window, or after alt-tab; see {@link Buttons}).
 */
public class WResizeGrip extends WPressable {
    private final WFrame frame;
    private Edge edge = Edge.NONE;
    private boolean resizing;
    private WindowGeometry start;
    private double startX;
    private double startY;
    private double lastX;
    private double lastY;

    public WResizeGrip(WFrame frame) {
        this.frame = frame;
    }

    private int u() {
        return SmGuiTheme.unitOf(theme);
    }

    @Override
    public boolean isOver(double mx, double my) {
        if (resizing) return true;
        if (frame.maximized() || frame.failed()) return false;
        int u = u();
        return FrameMath.edgeAt(frame.effective(), mx / u, my / u) != Edge.NONE;
    }

    @Override
    public void onMouseMoved(double mouseX, double mouseY, double lastMouseX, double lastMouseY) {
        lastX = mouseX;
        lastY = mouseY;
    }

    @Override
    protected void onPressed(int button) {
        end();
    }

    private void end() {
        if (resizing) {
            resizing = false;
            edge = Edge.NONE;
            frame.changed();
        }
        pressed = false;
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        SmGuiTheme t = (SmGuiTheme) theme;
        int u = t.unit();
        Edge hover = Edge.NONE;
        if (!pressed) {
            lastX = mouseX;
            lastY = mouseY;
            if (resizing) end();
            boolean blocked = t.overlay != null && t.overlay.isOpen();
            if (!blocked && !frame.maximized()) hover = FrameMath.edgeAt(frame.effective(), mouseX / u, mouseY / u);
        } else if (!resizing) {
            edge = frame.maximized() ? Edge.NONE : FrameMath.edgeAt(frame.effective(), lastX / u, lastY / u);
            if (edge == Edge.NONE) {
                pressed = false;
            } else {
                resizing = true;
                start = frame.restored();
                startX = lastX;
                startY = lastY;
            }
        } else if (!Buttons.leftDown()) {
            end();
        } else {
            int dx = (int) Math.round((mouseX - startX) / u);
            int dy = (int) Math.round((mouseY - startY) / u);
            frame.setGeometry(FrameMath.resize(start, edge, dx, dy, t.screenW(), t.screenH()));
        }
        WindowCursor.set(CursorShape.of(resizing ? edge : hover));

        // The grip in the bottom-right corner: a triangle of dots, lit while it is under the pointer or dragged
        if (!frame.maximized()) {
            Palette p = t.palette();
            boolean lit = resizing || hover == Edge.BOTTOM_RIGHT;
            int c = lit ? p.accent() : Palette.withAlpha(p.muted(), 230);
            double right = frame.x + frame.width - (p.borderWidth + 2) * u;
            double bottom = frame.y + frame.height - (p.borderWidth + 2) * u;
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col <= row; col++) {
                    Paint.rect(renderer, t, right - (col * 2 + 1) * u, bottom - ((2 - row) * 2 + 1) * u, u, u, c);
                }
            }
        }
    }
}
