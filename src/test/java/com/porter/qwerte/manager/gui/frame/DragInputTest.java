package com.porter.qwerte.manager.gui.frame;

import com.porter.qwerte.manager.gui.frame.FrameMath.Edge;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-dev.10.2: the window could not be moved or resized, and the grid's scroll bar could not be dragged. Every drag asked
 * the game's {@code MouseHandler.isLeftPressed()} whether the button was still down, and that flag is only updated while
 * no screen is open (the game's MouseHandler.onButton skips it otherwise), so inside the window it was always false and each
 * drag ended on its second frame. Drags must read the button from {@link Buttons} (Meteor's input state, fed by its mixin
 * whatever screen is up).
 */
class DragInputTest {
    private static final Path GUI = Path.of("src", "main", "java", "com", "porter", "qwerte", "manager", "gui");

    @Test
    void noDragAsksTheGamesScreenBlindMouseFlag() throws IOException {
        try (Stream<Path> files = Files.walk(GUI)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                String code = src.lines().filter(l -> !l.trim().startsWith("*") && !l.trim().startsWith("//") && !l.trim().startsWith("/*")).collect(java.util.stream.Collectors.joining(" "));
                assertFalse(code.contains("isLeftPressed"), f + " reads MouseHandler.isLeftPressed(), which is never updated while a screen is open");
            }
        }
    }

    @Test
    void everyDragReadsTheButtonFromButtons() throws IOException {
        for (Path f : new Path[] {GUI.resolve("frame/WDragHandle.java"), GUI.resolve("frame/WResizeGrip.java"), GUI.resolve("widgets/WItemGrid.java")}) {
            assertTrue(Files.readString(f, StandardCharsets.UTF_8).contains("Buttons.leftDown()"), f + " must follow the button through Buttons");
        }
        String buttons = Files.readString(GUI.resolve("frame/Buttons.java"), StandardCharsets.UTF_8);
        assertTrue(buttons.contains("Input.isButtonPressed"));
    }

    // ---------- the grip ----------

    private static final WindowGeometry G = new WindowGeometry(100, 50, 400, 300, false);

    @Test
    void theBottomRightGripIsAWholeSquare() {
        // inside the square, away from both edge rings: still the corner
        assertEquals(Edge.BOTTOM_RIGHT, FrameMath.edgeAt(G, 492, 342));
        assertEquals(Edge.BOTTOM_RIGHT, FrameMath.edgeAt(G, 490, 340));
        assertEquals(Edge.BOTTOM_RIGHT, FrameMath.edgeAt(G, 499, 349));
        // just outside it: window content, or the plain edges
        assertEquals(Edge.NONE, FrameMath.edgeAt(G, 489, 339));
        assertEquals(Edge.NONE, FrameMath.edgeAt(G, 489, 345));
        assertEquals(Edge.RIGHT, FrameMath.edgeAt(G, 498, 339));
        assertEquals(Edge.BOTTOM, FrameMath.edgeAt(G, 489, 348));
        // outside the window
        assertEquals(Edge.NONE, FrameMath.edgeAt(G, 500, 350));
    }

    @Test
    void draggingTheGripGrowsTheWindowFromItsTopLeft() {
        WindowGeometry r = FrameMath.resize(G, FrameMath.edgeAt(G, 495, 345), 40, 25, 1000, 700);
        assertEquals(new WindowGeometry(100, 50, 440, 325, false), r);
    }

    @Test
    void everyEdgeHasTheRightCursor() {
        assertEquals(CursorShape.DEFAULT, CursorShape.of(Edge.NONE));
        assertEquals(CursorShape.HORIZONTAL, CursorShape.of(Edge.LEFT));
        assertEquals(CursorShape.HORIZONTAL, CursorShape.of(Edge.RIGHT));
        assertEquals(CursorShape.VERTICAL, CursorShape.of(Edge.TOP));
        assertEquals(CursorShape.VERTICAL, CursorShape.of(Edge.BOTTOM));
        assertEquals(CursorShape.DIAGONAL_NWSE, CursorShape.of(Edge.TOP_LEFT));
        assertEquals(CursorShape.DIAGONAL_NWSE, CursorShape.of(Edge.BOTTOM_RIGHT));
        assertEquals(CursorShape.DIAGONAL_NESW, CursorShape.of(Edge.TOP_RIGHT));
        assertEquals(CursorShape.DIAGONAL_NESW, CursorShape.of(Edge.BOTTOM_LEFT));
        for (Edge e : Edge.values()) assertTrue(CursorShape.of(e) != null);
    }
}
