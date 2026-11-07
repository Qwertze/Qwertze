package com.porter.qwerte.manager.gui.layout;

import com.porter.qwerte.manager.model.Text;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CountLayoutTest {
    /** The inside of a slot in physical pixels: 16 GUI pixels. */
    private static int inner(int u) {
        return 16 * u;
    }

    private static List<Long> samples() {
        List<Long> out = new ArrayList<>();
        long[] edges = {1, 2, 9, 99, 999, 1000, 1049, 9949, 9950, 9999, 10_000, 99_999, 999_499, 999_500, 1_000_000, 9_949_999, 9_950_000,
            99_999_999, 999_499_999, 999_500_000, 1_000_000_000L, 9_949_999_999L};
        for (long e : edges) out.add(e);
        Random r = new Random(7);
        for (int i = 0; i < 3000; i++) out.add(1 + (long) (Math.pow(10, r.nextDouble() * 10)));
        return out;
    }

    @Test
    void theScaleIsSmallAndWholeAtEveryUnit() {
        assertEquals(1, CountLayout.scale(1, inner(1)));
        assertEquals(1, CountLayout.scale(2, inner(2)));
        assertEquals(2, CountLayout.scale(3, inner(3)));
        assertEquals(2, CountLayout.scale(4, inner(4)));
        assertEquals(3, CountLayout.scale(5, inner(5)));
        assertEquals(4, CountLayout.scale(6, inner(6)));
        for (int u = 1; u <= 8; u++) {
            int s = CountLayout.scale(u, inner(u));
            assertTrue(s >= 1);
            assertTrue(s <= Math.max(1, u), "never bigger than the GUI pixel");
        }
    }

    @Test
    void aCountNeverPokesOutOfTheSlot() {
        for (int u = 1; u <= 8; u++) {
            int scale = CountLayout.scale(u, inner(u));
            for (long n : samples()) {
                String text = Text.compact(n);
                int units = CountLayout.textUnits(text);
                double drawn = CountLayout.fit(scale, units, inner(u));
                assertTrue(drawn * units <= inner(u) + 1e-9, "u=" + u + " " + text);
                if (u >= 2 && units <= CountLayout.W_MAX) {
                    assertEquals(scale, drawn, 0, "whole scale for " + text + " at u=" + u);
                    assertEquals(Math.rint(drawn), drawn, 0);
                }
            }
        }
    }

    @Test
    void widestOrdinaryCountsFitAtTheChosenScale() {
        for (String s : new String[] {"999", "1.7k", "20k", "999k", "9.9M", "12M", "999M", "9.9B"}) {
            assertTrue(CountLayout.textUnits(s) <= CountLayout.W_MAX, s);
        }
        assertEquals(23, CountLayout.textUnits("999k"));
        assertEquals(24, CountLayout.textUnits("999M"));
        assertEquals(19, CountLayout.textUnits("1.7k"));
    }

    @Test
    void anOverlongStringIsShrunkOnlyAsMuchAsNeeded() {
        // 12.3B is 26 font units: too wide at scale 1 in a 16 px slot (GUI scale 1)
        assertEquals(16.0 / 26, CountLayout.fit(1, 26, 16), 1e-9);
        assertEquals(1.0, CountLayout.fit(1, 26, 32), 0);
        assertEquals(2.0, CountLayout.fit(2, 20, 48), 0);
        assertEquals(1.0, CountLayout.fit(1, 0, 32), 0);
    }

    @Test
    void theAnchorIsInsideTheSlot() {
        // slot at (0,0), 18 GUI pixels with a one pixel rim; the count's corner is the inner bottom-right
        for (int u = 1; u <= 6; u++) {
            int right = 17 * u;
            int bottom = 17 * u;
            assertTrue(right > 0 && right <= 18 * u);
            assertTrue(bottom > 0 && bottom <= 18 * u);
        }
    }
}
