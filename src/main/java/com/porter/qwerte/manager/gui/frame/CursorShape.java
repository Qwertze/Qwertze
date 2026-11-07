package com.porter.qwerte.manager.gui.frame;

import com.porter.qwerte.manager.gui.frame.FrameMath.Edge;

/**
 * The mouse cursor to show over a part of the window's edge. Pure: {@link WindowCursor} turns a shape into a GLFW cursor.
 */
public enum CursorShape {
    DEFAULT, HORIZONTAL, VERTICAL, DIAGONAL_NWSE, DIAGONAL_NESW;

    public static CursorShape of(Edge edge) {
        return switch (edge) {
            case LEFT, RIGHT -> HORIZONTAL;
            case TOP, BOTTOM -> VERTICAL;
            case TOP_LEFT, BOTTOM_RIGHT -> DIAGONAL_NWSE;
            case TOP_RIGHT, BOTTOM_LEFT -> DIAGONAL_NESW;
            case NONE -> DEFAULT;
        };
    }
}
