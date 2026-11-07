package com.porter.qwerte.manager.gui.style;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextScaleTest {
    @Test
    void autoIsSmallerThanTheGameFontAndNormalIsTheGameFont() {
        assertEquals(1.5, TextScale.physical(2, TextScale.AUTO.factor()), 1e-9);
        assertEquals(2.0, TextScale.physical(2, TextScale.NORMAL.factor()), 1e-9);
        assertEquals(3.0, TextScale.physical(2, TextScale.LARGE.factor()), 1e-9);
        assertEquals(1.2, TextScale.physical(2, TextScale.SMALL.factor()), 1e-9);
    }

    @Test
    void biggerUnitsSnapToWholePixelsSoTheFontStaysCrisp() {
        // 3 * 0.75 = 2.25 -> 2, 4 * 0.75 = 3, 6 * 0.75 = 4.5 -> 5 (rounded half up)
        assertEquals(2.0, TextScale.physical(3, 0.75), 1e-9);
        assertEquals(3.0, TextScale.physical(4, 0.75), 1e-9);
        assertEquals(5.0, TextScale.physical(6, 0.75), 1e-9);
        for (int u = 3; u <= 8; u++) {
            for (TextScale s : TextScale.values()) {
                double p = TextScale.physical(u, s.factor());
                if (u * s.factor() >= 2) assertEquals(Math.rint(p), p, 1e-9, "u=" + u + " " + s);
            }
        }
    }

    @Test
    void textIsNeverSmallerThanOnePixelPerFontPixel() {
        assertEquals(1.0, TextScale.physical(1, 0.6), 1e-9);
        assertEquals(1.0, TextScale.physical(0, 0.6), 1e-9);
        for (int u = 1; u <= 8; u++) for (TextScale s : TextScale.values()) assertTrue(TextScale.physical(u, s.factor()) >= 1);
    }

    @Test
    void sizesGrowWithTheSetting() {
        for (int u = 1; u <= 8; u++) {
            double last = 0;
            for (TextScale s : new TextScale[] {TextScale.SMALL, TextScale.AUTO, TextScale.NORMAL, TextScale.LARGE}) {
                double p = TextScale.physical(u, s.factor());
                assertTrue(p >= last, "u=" + u + " " + s);
                last = p;
            }
        }
    }

    @Test
    void theNormalBatchAndTheTitleBatchGetTheRightScales() {
        // Meteor asks for the theme's scale of 1 (u / 2) for normal text and 1.25 times that for "title" text
        int u = 2;
        double ref = u / 2.0;
        double normal = TextScale.batchScale(ref, ref, u, TextScale.AUTO.factor());
        double small = TextScale.batchScale(ref * 1.25, ref, u, TextScale.AUTO.factor());
        assertEquals(TextScale.physical(u, 0.75) / 2, normal, 1e-9);
        assertEquals(TextScale.physical(u, 0.75 * TextScale.SMALL_TEXT) / 2, small, 1e-9);
        assertTrue(small < normal);
        // the same at another unit
        u = 3;
        ref = u / 2.0;
        assertEquals(TextScale.physical(u, 1.0) / 2, TextScale.batchScale(ref, ref, u, 1.0), 1e-9);
    }

    @Test
    void autoIsTheDefaultAndNamesAreTheLabels() {
        assertEquals("Auto", TextScale.AUTO.toString());
        assertEquals("Normal", TextScale.NORMAL.toString());
        assertEquals(TextScale.AUTO, TextScale.values()[0]);
    }
}
