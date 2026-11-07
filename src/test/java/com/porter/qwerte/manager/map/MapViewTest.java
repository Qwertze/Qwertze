package com.porter.qwerte.manager.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapViewTest {
    @Test
    void zoomIsClamped() {
        MapView v = new MapView();
        v.setViewport(400, 300);
        for (int i = 0; i < 50; i++) v.zoomBy(2);
        assertEquals(MapView.ZOOM_MAX, v.zoom());
        for (int i = 0; i < 50; i++) v.zoomBy(0.5);
        assertEquals(MapView.ZOOM_MIN, v.zoom());
    }

    @Test
    void mappingRoundTripsAndNorthIsUp() {
        MapView v = new MapView();
        v.setViewport(400, 300);
        v.track(100, 200);
        assertEquals(200, v.toScreenX(100));
        assertEquals(150, v.toScreenY(200));
        assertTrue(v.toScreenY(190) < v.toScreenY(200));
        assertTrue(v.toScreenX(110) > v.toScreenX(100));
        assertEquals(123.5, v.toWorldX(v.toScreenX(123.5)), 1e-9);
    }

    @Test
    void panStopsFollowingAndFollowComesBack() {
        MapView v = new MapView();
        v.setViewport(400, 300);
        v.track(0, 0);
        v.pan(40, -20);
        assertFalse(v.following());
        assertEquals(-10, v.centreX());
        assertEquals(5, v.centreZ());
        v.track(50, 50);
        assertEquals(-10, v.centreX());
        v.follow();
        v.track(50, 50);
        assertEquals(50, v.centreX());
    }

    @Test
    void zoomKeepsTheBlockUnderThePointerWhenNotFollowing() {
        MapView v = new MapView();
        v.setViewport(400, 300);
        v.lookAt(0, 0);
        double bx = v.toWorldX(300);
        double bz = v.toWorldZ(100);
        v.zoomAt(2, 300, 100);
        assertEquals(bx, v.toWorldX(300), 1e-9);
        assertEquals(bz, v.toWorldZ(100), 1e-9);
        assertEquals(8, v.zoom());
    }
}
