package com.porter.qwerte.tweaks;

/**
 * Where things are in the frozen shulker panel: Meteor's 176 x 67 container image, 9 x 3 cells of 16 px on an 18 px
 * pitch starting 8 px right and 7 px down. Pure, so it is tested without a game.
 */
public final class FrozenGrid {
    private FrozenGrid() {}

    public static final int WIDTH = 176, HEIGHT = 67;
    public static final int COLS = 9, ROWS = 3, CELL = 16, PITCH = 18, LEFT = 8, TOP = 7;
    /** How far the panel sits from the mouse. */
    public static final int OFFSET = 12;

    /** The cell (0 to 26, row by row) under the point, or -1 on a gap, the border or outside the panel. */
    public static int cellAt(int panelX, int panelY, int mouseX, int mouseY) {
        int dx = mouseX - panelX - LEFT, dy = mouseY - panelY - TOP;
        if (dx < 0 || dy < 0) return -1;

        int col = dx / PITCH, row = dy / PITCH;
        if (col >= COLS || row >= ROWS) return -1;
        if (dx % PITCH >= CELL || dy % PITCH >= CELL) return -1;
        return row * COLS + col;
    }

    /** The cell's top-left corner. */
    public static int cellX(int panelX, int cell) {
        return panelX + LEFT + (cell % COLS) * PITCH;
    }

    public static int cellY(int panelY, int cell) {
        return panelY + TOP + (cell / COLS) * PITCH;
    }

    public static boolean inside(int panelX, int panelY, int mouseX, int mouseY) {
        return mouseX >= panelX && mouseX < panelX + WIDTH && mouseY >= panelY && mouseY < panelY + HEIGHT;
    }

    /** The panel's left edge for a mouse position: right of the mouse, kept on the screen. */
    public static int placeX(int mouseX, int screenWidth) {
        return clamp(mouseX + OFFSET, WIDTH, screenWidth);
    }

    /** The panel's top edge for a mouse position: just above it, kept on the screen. */
    public static int placeY(int mouseY, int screenHeight) {
        return clamp(mouseY - OFFSET, HEIGHT, screenHeight);
    }

    private static int clamp(int pos, int size, int screen) {
        return Math.max(0, Math.min(pos, screen - size));
    }
}
