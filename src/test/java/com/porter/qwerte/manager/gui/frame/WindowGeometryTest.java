package com.porter.qwerte.manager.gui.frame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WindowGeometryTest {
    @Test
    void roundTrips() {
        for (WindowGeometry g : new WindowGeometry[] {
            new WindowGeometry(10, 20, 300, 200, false), new WindowGeometry(0, 0, 700, 432, true), new WindowGeometry(-5, -7, 1, 1, false)}) {
            assertEquals(g, WindowGeometry.decode(g.encode()));
        }
        assertEquals("v1:10,20,300,200,0", new WindowGeometry(10, 20, 300, 200, false).encode());
        assertEquals("v1:1,2,3,4,1", new WindowGeometry(1, 2, 3, 4, true).encode());
    }

    @Test
    void malformedInputGivesNull() {
        for (String bad : new String[] {"", " ", "v1:", "v1:1,2,3,4", "v1:1,2,3,4,0,9", "v2:1,2,3,4,0", "1,2,3,4,0", "v1:a,b,c,d,e", "v1:1,2,0,4,0", "v1:1,2,3,-4,0",
            "v1:1,2,3,4,2", "v1:NaN,2,3,4,0", "v1:1.5,2,3,4,0", "v1:1,2,999999999,4,0", "v1:99999999999999999999,2,3,4,0", "v1:1,2,3,4,"}) {
            assertNull(WindowGeometry.decode(bad), bad);
        }
        assertNull(WindowGeometry.decode(null));
    }

    @Test
    void decodedGeometryCanBeClampedToAnyScreen() {
        WindowGeometry g = WindowGeometry.decode("v1:5000,5000,90000,90000,0");
        WindowGeometry c = FrameMath.clamp(g, 640, 360);
        assertEquals(new WindowGeometry(0, 0, 640, 360, false), c);
    }
}
