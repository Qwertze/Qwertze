package com.porter.qwerte.printer.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LayerFilterTest {
    @Test
    void allAcceptsEverythingAndIsNotActive() {
        assertFalse(LayerFilter.ALL.active());
        assertTrue(LayerFilter.ALL.contains(-30000000, -64, 30000000));
        assertSame(LayerFilter.ALL, LayerFilter.of("ALL", 1, 5, 5, 5, 5, 5));
    }

    @Test
    void singleLayerAcceptsOnlyThatCoordinateOnTheAxis() {
        LayerFilter f = LayerFilter.of("SINGLE_LAYER", 1, 64, 0, 0, 0, 0);
        assertTrue(f.active());
        assertTrue(f.contains(100, 64, -100));
        assertFalse(f.contains(100, 63, -100));
        assertFalse(f.contains(100, 65, -100));
    }

    @Test
    void allAboveIncludesTheLayerItselfAndAllBelowDoesToo() {
        LayerFilter above = LayerFilter.of("ALL_ABOVE", 1, 0, 70, 0, 0, 0);
        assertTrue(above.contains(0, 70, 0));
        assertTrue(above.contains(0, 200, 0));
        assertFalse(above.contains(0, 69, 0));
        LayerFilter below = LayerFilter.of("ALL_BELOW", 1, 0, 0, 70, 0, 0);
        assertTrue(below.contains(0, 70, 0));
        assertTrue(below.contains(0, -64, 0));
        assertFalse(below.contains(0, 71, 0));
    }

    @Test
    void layerRangeIncludesBothEnds() {
        LayerFilter f = LayerFilter.of("LAYER_RANGE", 1, 0, 0, 0, 10, 12);
        assertFalse(f.contains(0, 9, 0));
        assertTrue(f.contains(0, 10, 0));
        assertTrue(f.contains(0, 11, 0));
        assertTrue(f.contains(0, 12, 0));
        assertFalse(f.contains(0, 13, 0));
    }

    @Test
    void theAxisPicksTheCoordinate() {
        LayerFilter x = LayerFilter.of("SINGLE_LAYER", 0, 5, 0, 0, 0, 0);
        assertTrue(x.contains(5, 0, 99));
        assertFalse(x.contains(0, 5, 5));
        LayerFilter z = LayerFilter.of("LAYER_RANGE", 2, 0, 0, 0, -3, 3);
        assertTrue(z.contains(99, 99, -3));
        assertTrue(z.contains(-99, -99, 3));
        assertFalse(z.contains(0, 0, 4));
        assertEquals('x', x.axis());
        assertEquals('z', z.axis());
    }

    @Test
    void anUnknownModeMeansNoFilter() {
        assertSame(LayerFilter.ALL, LayerFilter.of(null, 1, 0, 0, 0, 0, 0));
        assertSame(LayerFilter.ALL, LayerFilter.of("SOMETHING_NEW", 1, 0, 0, 0, 0, 0));
    }

    @Test
    void equalValuesAreEqualSoAChangeCanBeSeen() {
        assertEquals(LayerFilter.of("SINGLE_LAYER", 1, 64, 0, 0, 0, 0), LayerFilter.of("SINGLE_LAYER", 1, 64, 0, 0, 0, 0));
        assertFalse(LayerFilter.of("SINGLE_LAYER", 1, 64, 0, 0, 0, 0).equals(LayerFilter.of("SINGLE_LAYER", 1, 65, 0, 0, 0, 0)));
    }
}
