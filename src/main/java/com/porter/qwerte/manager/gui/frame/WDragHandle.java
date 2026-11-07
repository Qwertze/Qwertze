package com.porter.qwerte.manager.gui.frame;

import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;


/**
 * The part of the header that moves the window. It is the last child of the frame, so a button or select in the header
 * takes a click first and only a click on bare header gets here. Like {@link WResizeGrip} it overrides no click
 * handler: the drag follows the pointer position that Meteor passes to {@code render}, converted to whole GUI pixels
 * without drift, and ends with the press or when the left button is seen up. Two quick clicks maximize and restore.
 */
public class WDragHandle extends WPressable {
    private static final long DOUBLE_MS = 350;

    private final WFrame frame;
    private boolean dragging;
    private boolean moved;
    private double lastX;
    private double lastY;
    private FrameMath.Accumulator accX;
    private FrameMath.Accumulator accY;
    private long lastClick;

    public WDragHandle(WFrame frame) {
        this.frame = frame;
    }

    @Override
    public void onMouseMoved(double mouseX, double mouseY, double lastMouseX, double lastMouseY) {
        if (!dragging) {
            lastX = mouseX;
            lastY = mouseY;
        }
    }

    @Override
    public boolean isOver(double mx, double my) {
        return dragging || super.isOver(mx, my);
    }

    @Override
    protected void onPressed(int button) {
        boolean wasMoved = moved;
        endDrag();
        if (wasMoved || button != 0) return;
        long now = System.currentTimeMillis();
        if (now - lastClick <= DOUBLE_MS) {
            lastClick = 0;
            frame.toggleMaximized();
        } else {
            lastClick = now;
        }
    }

    private void endDrag() {
        boolean was = dragging && moved;
        dragging = false;
        moved = false;
        pressed = false;
        if (was) frame.changed();
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        int u = SmGuiTheme.unitOf(theme);
        if (!pressed) {
            lastX = mouseX;
            lastY = mouseY;
            if (dragging) endDrag();
            return;
        }
        if (!dragging) {
            dragging = true;
            moved = false;
            accX = new FrameMath.Accumulator(u);
            accY = new FrameMath.Accumulator(u);
            return;
        }
        if (!Buttons.leftDown()) {
            endDrag();
            return;
        }
        int dx = accX.feed(mouseX - lastX);
        int dy = accY.feed(mouseY - lastY);
        lastX = mouseX;
        lastY = mouseY;
        if (dx != 0 || dy != 0) {
            moved = true;
            frame.moveBy(dx, dy);
        }
    }
}
