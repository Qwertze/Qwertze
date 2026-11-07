package com.porter.qwerte.stash;

import com.porter.qwerte.stash.StashSafety.Pt;
import com.porter.qwerte.stash.StashSafety.Spot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashSafetyTest {
    private static final Pt PLAYER = new Pt(0, 0);
    private static final Pt FAR = new Pt(2_100_000, 0);

    @Test
    void neverReadNeedsNoContentsAndNoTime() {
        assertTrue(StashSafety.neverRead(false, 0));
        assertFalse(StashSafety.neverRead(true, 0));
        assertFalse(StashSafety.neverRead(false, 5));
    }

    @Test
    void unloadedFarSpotIsRefused() {
        assertFalse(StashSafety.spotSettable(false, FAR, PLAYER));
        assertTrue(StashSafety.spotSettable(false, new Pt(50, 50), PLAYER));
        assertFalse(StashSafety.spotSettable(false, new Pt(50, 50), null));
        assertTrue(StashSafety.spotSettable(true, FAR, PLAYER));
    }

    private static List<Spot> refs(int n) {
        List<Spot> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(new Spot(new Pt(i * 10, 0), true, false));
        return l;
    }

    @Test
    void strayIsAFarNeverReadBox() {
        List<Spot> l = refs(5);
        l.add(new Spot(FAR, false, false));
        l.add(new Spot(new Pt(500, 0), false, false));
        assertEquals(List.of(5), StashSafety.strays(l));
    }

    @Test
    void needsFiveReferences() {
        List<Spot> l = refs(4);
        l.add(new Spot(FAR, false, false));
        assertTrue(StashSafety.strays(l).isEmpty());
    }

    @Test
    void deliveryAndReadBoxesAreNeverStrays() {
        List<Spot> l = refs(5);
        l.add(new Spot(FAR, false, true));
        l.add(new Spot(new Pt(-3_000_000, 0), true, false));
        assertTrue(StashSafety.strays(l).isEmpty());
    }

    @Test
    void deliveryBoxDoesNotCountAsReference() {
        List<Spot> l = refs(4);
        l.add(new Spot(new Pt(5, 5), true, true));
        l.add(new Spot(FAR, false, false));
        assertTrue(StashSafety.strays(l).isEmpty());
    }

    @Test
    void shrinkingTheAreaKeepsAndSkipsReadBoxes() {
        assertEquals(StashSafety.Outside.SKIP, StashSafety.outside(false, true, false));
        assertEquals(StashSafety.Outside.DROP, StashSafety.outside(false, true, true));
        assertEquals(StashSafety.Outside.INSIDE, StashSafety.outside(true, true, true));
        assertEquals(StashSafety.Outside.INSIDE, StashSafety.outside(false, false, true));
    }
}
