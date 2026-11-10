package com.porter.qwerte.printer.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetBitsSectionTest {
    private static List<String> cells(TargetBits t, int sx, int sy, int sz, int y) {
        List<String> out = new ArrayList<>();
        t.forEach(sx, sy, sz, y, (x, yy, z) -> out.add(x + "," + yy + "," + z));
        return out;
    }

    @Test
    void forEachVisitsTheCellsOfOneSectionWithWorldCoordinates() {
        TargetBits t = new TargetBits();
        t.add(-1, -1, -1);
        t.add(-16, -16, -16);
        t.add(0, 0, 0);
        t.add(15, 15, 15);
        t.add(16, 0, 0);
        assertEquals(List.of("-16,-16,-16", "-1,-1,-1"), cells(t, -1, -1, -1, TargetBits.ANY_Y));
        assertEquals(List.of("0,0,0", "15,15,15"), cells(t, 0, 0, 0, TargetBits.ANY_Y));
        assertEquals(List.of("16,0,0"), cells(t, 1, 0, 0, TargetBits.ANY_Y));
        assertEquals(List.of(), cells(t, 2, 0, 0, TargetBits.ANY_Y));
    }

    @Test
    void aRowFilterGivesOnlyThatY() {
        TargetBits t = new TargetBits();
        t.add(1, 70, 2);
        t.add(3, 70, 4);
        t.add(1, 71, 2);
        assertEquals(List.of("1,70,2", "3,70,4"), cells(t, 0, 4, 0, 70));
        assertEquals(List.of("1,71,2"), cells(t, 0, 4, 0, 71));
        assertEquals(List.of(), cells(t, 0, 4, 0, 72));
        // a y that belongs to another section yields nothing
        assertEquals(List.of(), cells(t, 0, 5, 0, 70));
    }

    @Test
    void countInMatchesWhatForEachVisits() {
        TargetBits t = new TargetBits();
        for (int i = 0; i < 40; i++) t.add(i % 16, 32 + i % 3, i / 16);
        assertEquals(40, t.countIn(0, 2, 0, TargetBits.ANY_Y));
        assertEquals(cells(t, 0, 2, 0, 33).size(), t.countIn(0, 2, 0, 33));
        assertEquals(0, t.countIn(5, 2, 0, TargetBits.ANY_Y));
        t.remove(0, 32, 0);
        assertEquals(39, t.countIn(0, 2, 0, TargetBits.ANY_Y));
    }

    @Test
    void theListenerHearsOnlyRealAddsAndRemoves() {
        TargetBits t = new TargetBits();
        List<String> heard = new ArrayList<>();
        t.setListener((x, y, z) -> heard.add(x + "," + y + "," + z));
        t.add(1, 2, 3);
        t.add(1, 2, 3);
        t.remove(9, 9, 9);
        t.remove(1, 2, 3);
        assertEquals(List.of("1,2,3", "1,2,3"), heard);
        t.setListener(null);
        t.add(4, 5, 6);
        assertEquals(2, heard.size());
    }
}
