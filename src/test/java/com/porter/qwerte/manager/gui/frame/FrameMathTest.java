package com.porter.qwerte.manager.gui.frame;

import com.porter.qwerte.manager.gui.frame.FrameMath.Edge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameMathTest {
    private static final WindowGeometry G = new WindowGeometry(100, 50, 400, 300, false);

    @Test
    void findsEveryEdgeAndCorner() {
        assertEquals(Edge.LEFT, FrameMath.edgeAt(G, 101, 200));
        assertEquals(Edge.RIGHT, FrameMath.edgeAt(G, 498, 200));
        assertEquals(Edge.TOP, FrameMath.edgeAt(G, 300, 51));
        assertEquals(Edge.BOTTOM, FrameMath.edgeAt(G, 300, 348));
        assertEquals(Edge.TOP_LEFT, FrameMath.edgeAt(G, 101, 51));
        assertEquals(Edge.TOP_LEFT, FrameMath.edgeAt(G, 105, 51));
        assertEquals(Edge.TOP_RIGHT, FrameMath.edgeAt(G, 498, 52));
        assertEquals(Edge.BOTTOM_LEFT, FrameMath.edgeAt(G, 100, 349));
        assertEquals(Edge.BOTTOM_RIGHT, FrameMath.edgeAt(G, 499, 349));
        assertEquals(Edge.BOTTOM_RIGHT, FrameMath.edgeAt(G, 494, 349));
    }

    @Test
    void insideAndOutsideAreNone() {
        assertEquals(Edge.NONE, FrameMath.edgeAt(G, 300, 200));
        // inside the corner square but off both rings: window content
        assertEquals(Edge.NONE, FrameMath.edgeAt(G, 106, 56));
        assertEquals(Edge.NONE, FrameMath.edgeAt(G, 99, 200));
        assertEquals(Edge.NONE, FrameMath.edgeAt(G, 500, 200));
        assertEquals(Edge.NONE, FrameMath.edgeAt(G, 300, 49));
        assertEquals(Edge.NONE, FrameMath.edgeAt(G, 300, 350));
    }

    @Test
    void resizingKeepsTheOppositeEdgeFixed() {
        WindowGeometry r = FrameMath.resize(G, Edge.RIGHT, 30, 99, 1000, 700);
        assertEquals(new WindowGeometry(100, 50, 430, 300, false), r);
        r = FrameMath.resize(G, Edge.LEFT, -20, 0, 1000, 700);
        assertEquals(80, r.x());
        assertEquals(420, r.w());
        assertEquals(500, r.x() + r.w());
        r = FrameMath.resize(G, Edge.TOP, 0, -10, 1000, 700);
        assertEquals(40, r.y());
        assertEquals(310, r.h());
        assertEquals(350, r.y() + r.h());
        r = FrameMath.resize(G, Edge.BOTTOM_RIGHT, 10, 20, 1000, 700);
        assertEquals(new WindowGeometry(100, 50, 410, 320, false), r);
        r = FrameMath.resize(G, Edge.TOP_LEFT, 10, 20, 1000, 700);
        assertEquals(110, r.x());
        assertEquals(70, r.y());
        assertEquals(500, r.x() + r.w());
        assertEquals(350, r.y() + r.h());
    }

    @Test
    void resizingRespectsTheMinimumSize() {
        WindowGeometry r = FrameMath.resize(G, Edge.RIGHT, -5000, 0, 1000, 700);
        assertEquals(WindowGeometry.MIN_W, r.w());
        assertEquals(100, r.x());
        r = FrameMath.resize(G, Edge.LEFT, 5000, 0, 1000, 700);
        assertEquals(WindowGeometry.MIN_W, r.w());
        assertEquals(500, r.x() + r.w());
        r = FrameMath.resize(G, Edge.TOP, 0, 5000, 1000, 700);
        assertEquals(WindowGeometry.MIN_H, r.h());
        assertEquals(350, r.y() + r.h());
        r = FrameMath.resize(G, Edge.BOTTOM_LEFT, 5000, -5000, 1000, 700);
        assertEquals(WindowGeometry.MIN_W, r.w());
        assertEquals(WindowGeometry.MIN_H, r.h());
    }

    @Test
    void resizingStaysOnTheScreen() {
        WindowGeometry r = FrameMath.resize(G, Edge.RIGHT, 5000, 0, 800, 600);
        assertEquals(800, r.x() + r.w());
        r = FrameMath.resize(G, Edge.LEFT, -5000, 0, 800, 600);
        assertEquals(0, r.x());
        assertEquals(500, r.x() + r.w());
        r = FrameMath.resize(G, Edge.BOTTOM, 0, 5000, 800, 600);
        assertEquals(600, r.y() + r.h());
        r = FrameMath.resize(G, Edge.TOP, 0, -5000, 800, 600);
        assertEquals(0, r.y());
    }

    @Test
    void aScreenSmallerThanTheMinimumIsFilled() {
        WindowGeometry r = FrameMath.clamp(G, 200, 120);
        assertEquals(new WindowGeometry(0, 0, 200, 120, false), r);
        assertEquals(200, FrameMath.minW(200));
        assertEquals(WindowGeometry.MIN_W, FrameMath.minW(5000));
    }

    @Test
    void movingKeepsTheWholeWindowOnScreen() {
        assertEquals(new WindowGeometry(110, 60, 400, 300, false), FrameMath.move(G, 10, 10, 1000, 700));
        assertEquals(0, FrameMath.move(G, -500, 0, 1000, 700).x());
        assertEquals(0, FrameMath.move(G, 0, -500, 1000, 700).y());
        assertEquals(600, FrameMath.move(G, 5000, 0, 1000, 700).x());
        assertEquals(400, FrameMath.move(G, 0, 5000, 1000, 700).y());
    }

    @Test
    void maximizeTogglesAndRestores() {
        WindowGeometry max = FrameMath.toggleMaximized(G);
        assertTrue(max.maximized());
        assertEquals(new WindowGeometry(0, 0, 800, 600, true), max.effective(800, 600));
        WindowGeometry back = FrameMath.toggleMaximized(max);
        assertEquals(G, back);
        assertEquals(G, back.effective(800, 600));
    }

    @Test
    void clampsAfterTheScreenShrinks() {
        WindowGeometry big = new WindowGeometry(300, 200, 700, 500, false);
        WindowGeometry r = FrameMath.clamp(big, 640, 360);
        assertEquals(new WindowGeometry(0, 0, 640, 360, false), r);
        r = FrameMath.clamp(new WindowGeometry(-50, -20, 300, 250, false), 640, 360);
        assertEquals(new WindowGeometry(0, 0, 300, 250, false), r);
    }

    @Test
    void physicalToGuiPixelsDoesNotDrift() {
        for (int unit : new int[] {1, 2, 3, 4, 6}) {
            FrameMath.Accumulator acc = new FrameMath.Accumulator(unit);
            int gui = 0;
            int steps = 5000;
            for (int i = 0; i < steps; i++) gui += acc.feed(1);
            assertEquals(Math.round(steps / (double) unit), gui, "unit " + unit);
        }
        // movement back and forth ends where it started
        FrameMath.Accumulator acc = new FrameMath.Accumulator(3);
        int sum = 0;
        for (int i = 0; i < 100; i++) sum += acc.feed(1);
        for (int i = 0; i < 100; i++) sum += acc.feed(-1);
        assertEquals(0, sum);
        // a big jump counts at once
        assertEquals(10, new FrameMath.Accumulator(3).feed(30));
    }

    @Test
    void initialGeometryIsCentredAndCapped() {
        WindowGeometry g = WindowGeometry.initial(960, 540);
        assertEquals(700, g.w());
        assertEquals(432, g.h());
        assertEquals(130, g.x());
        assertEquals(54, g.y());
        WindowGeometry small = WindowGeometry.initial(300, 200);
        assertEquals(240, small.w());
        assertEquals(200, small.h());
    }
}
