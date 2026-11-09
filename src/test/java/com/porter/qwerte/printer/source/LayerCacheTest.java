package com.porter.qwerte.printer.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class LayerCacheTest {
    @Test
    void noFilterUntilARead_hasEverSucceeded() {
        assertSame(LayerFilter.ALL, new LayerCache().fallback());
    }

    @Test
    void aFailedReadKeepsTheLastGoodFilter() {
        LayerCache c = new LayerCache();
        LayerFilter single = LayerFilter.of("SINGLE_LAYER", 1, 64, 0, 0, 0, 0);
        c.ok(single);
        assertEquals(single, c.fallback());
        assertEquals(single, c.fallback());
        LayerFilter range = LayerFilter.of("LAYER_RANGE", 1, 0, 0, 0, 10, 12);
        c.ok(range);
        assertEquals(range, c.fallback());
    }
}
