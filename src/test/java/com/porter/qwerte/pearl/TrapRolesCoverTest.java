package com.porter.qwerte.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TrapRolesCoverTest {
    @Test
    void withNoTrapdoorAtTheWaterAndTwoOrMoreTheLowestIsStasisOnlyJustAboveTheWater() {
        // 62 is two above the water top: where a stasis trapdoor sits
        TrapRoles r = TrapRoles.classify(60, List.of(62, 75));
        assertTrue(r.hasStasis());
        assertEquals(62, r.stasisY());
        assertEquals(List.of(75), r.coverYs());
        assertEquals(TrapRoles.Role.STASIS, r.roleOf(62));
        assertEquals(TrapRoles.Role.COVER, r.roleOf(75));
        TrapRoles three = TrapRoles.classify(60, List.of(80, 62, 75));
        assertEquals(62, three.stasisY());
        assertEquals(List.of(75, 80), three.coverYs());
    }

    @Test
    void twoHighCoversOverAPlainWaterColumnAreBothCovers() {
        TrapRoles r = TrapRoles.classify(60, List.of(70, 75));
        assertFalse(r.hasStasis());
        assertEquals(List.of(70, 75), r.coverYs());
        assertEquals(TrapRoles.Role.COVER, r.roleOf(70));
        // both close; nothing is left open on purpose
        assertEquals(List.of(70, 75), r.coverPlan(List.of(70, 75), null));
        assertEquals(List.of(), r.leftOpen(List.of(70, 75), null));
    }

    @Test
    void theCoversLeftOpenForTheRestingPearlAreNamed() {
        TrapRoles r = TrapRoles.classify(60, List.of(61, 70, 75));
        assertEquals(List.of(70), r.leftOpen(List.of(61, 70, 75), 69));
        assertEquals(List.of(70, 75), r.leftOpen(List.of(61, 70, 75), 80));
        assertEquals(List.of(), r.leftOpen(List.of(61, 70, 75), null));
    }

    @Test
    void aSingleHighTrapdoorIsStillAPlainCoverAndOneAtTheWaterIsStasis() {
        TrapRoles one = TrapRoles.classify(60, List.of(70));
        assertFalse(one.hasStasis());
        assertEquals(List.of(70), one.coverYs());
        TrapRoles water = TrapRoles.classify(60, List.of(61, 70));
        assertEquals(61, water.stasisY());
        assertEquals(List.of(70), water.coverYs());
    }

    @Test
    void theCoverPlanNeverTouchesTheLowestOrAnythingAtOrBelowTheRestingPearl() {
        TrapRoles r = TrapRoles.classify(60, List.of(61, 70, 75));
        assertEquals(List.of(70, 75), r.coverPlan(List.of(61, 70, 75), null));
        // the pearl rests at y 69: the cover at 70 is directly above it, so only the one above is closed
        assertEquals(List.of(75), r.coverPlan(List.of(61, 70, 75), 69));
        assertEquals(List.of(), r.coverPlan(List.of(61, 70, 75), 80));
    }

    @Test
    void theStasisTrapdoorOfASeveralTrapdoorShaftIsNeverInThePlan() {
        TrapRoles r = TrapRoles.classify(60, List.of(62, 75));
        assertEquals(List.of(75), r.coverPlan(List.of(62, 75), null));
        assertEquals(List.of(75), r.coverPlan(List.of(62, 75, 99), null));
    }

    @Test
    void thePearlMarginIsAtLeastItsHeight() {
        assertFalse(Hang.pearlBelow(69.8, 70)); // its top would still touch the trapdoor
        assertFalse(Hang.pearlBelow(69.71, 70));
        assertTrue(Hang.pearlBelow(69.6, 70));
    }
}
