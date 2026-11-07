package com.porter.qwerte.manager.gui.frame;

import org.lwjgl.glfw.GLFW;

/**
 * Resize cursors for the window's edges, through GLFW's standard cursors on the current window. The diagonal ones only
 * exist in GLFW 3.4; older libraries get the crosshair there. Nothing here is fatal: if a cursor can't be made, the
 * normal arrow stays. It takes no window handle from the game (its accessor differs between versions): the render thread's
 * current GLFW context is the game window.
 */
public final class WindowCursor {
    private static final int GLFW_RESIZE_NWSE = 0x00036007;
    private static final int GLFW_RESIZE_NESW = 0x00036008;

    private static final long[] CURSORS = new long[CursorShape.values().length];
    private static final boolean[] TRIED = new boolean[CursorShape.values().length];
    private static CursorShape shown = CursorShape.DEFAULT;

    private WindowCursor() {
    }

    /** Shows this shape (nothing happens when it is already shown). */
    public static void set(CursorShape shape) {
        if (shape == shown) return;
        try {
            long window = GLFW.glfwGetCurrentContext();
            if (window == 0) return;
            GLFW.glfwSetCursor(window, shape == CursorShape.DEFAULT ? 0L : cursor(shape));
            shown = shape;
        } catch (RuntimeException | LinkageError ignored) {
            // a cursor is only a nicety
        }
    }

    /** Back to the normal arrow (the window closed or the pointer left the edge). */
    public static void reset() {
        set(CursorShape.DEFAULT);
    }

    private static long cursor(CursorShape shape) {
        int i = shape.ordinal();
        if (!TRIED[i]) {
            TRIED[i] = true;
            long c = GLFW.glfwCreateStandardCursor(shapeCode(shape));
            if (c == 0 && (shape == CursorShape.DIAGONAL_NWSE || shape == CursorShape.DIAGONAL_NESW)) {
                c = GLFW.glfwCreateStandardCursor(GLFW.GLFW_CROSSHAIR_CURSOR);
            }
            CURSORS[i] = c;
        }
        return CURSORS[i];
    }

    private static int shapeCode(CursorShape shape) {
        return switch (shape) {
            case HORIZONTAL -> GLFW.GLFW_HRESIZE_CURSOR;
            case VERTICAL -> GLFW.GLFW_VRESIZE_CURSOR;
            case DIAGONAL_NWSE -> hasDiagonals() ? GLFW_RESIZE_NWSE : GLFW.GLFW_CROSSHAIR_CURSOR;
            case DIAGONAL_NESW -> hasDiagonals() ? GLFW_RESIZE_NESW : GLFW.GLFW_CROSSHAIR_CURSOR;
            case DEFAULT -> GLFW.GLFW_ARROW_CURSOR;
        };
    }

    private static boolean hasDiagonals() {
        int[] major = new int[1];
        int[] minor = new int[1];
        int[] rev = new int[1];
        GLFW.glfwGetVersion(major, minor, rev);
        return major[0] > 3 || (major[0] == 3 && minor[0] >= 4);
    }
}
