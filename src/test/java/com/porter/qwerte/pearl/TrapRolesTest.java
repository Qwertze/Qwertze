package com.porter.qwerte.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.pearl.TrapRoles.Armed;
import com.porter.qwerte.pearl.TrapRoles.Role;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrapRolesTest {
    @Test
    void oneStasisOneCover() {
        TrapRoles r = TrapRoles.classify(57, List.of(58, 63));
        assertNull(r.error());
        assertEquals(Role.STASIS, r.roleOf(58));
        assertEquals(Role.COVER, r.roleOf(63));
        assertEquals(List.of(63), r.coverYs());
    }

    @Test
    void stasisAndTwoCovers() {
        TrapRoles r = TrapRoles.classify(57, List.of(63, 58, 64));
        assertEquals(58, r.stasisY());
        assertEquals(List.of(63, 64), r.coverYs());
    }

    @Test
    void coverOnlyIsAPlainWaterColumn() {
        TrapRoles r = TrapRoles.classify(57, List.of(63));
        assertNull(r.error());
        assertFalse(r.hasStasis());
        assertEquals(Role.COVER, r.roleOf(63));
    }

    @Test
    void stasisOnlyHasNoCover() {
        TrapRoles r = TrapRoles.classify(57, List.of(58));
        assertTrue(r.hasStasis());
        assertTrue(r.coverYs().isEmpty());
    }

    @Test
    void waterlogged_stasisAtTheSurfaceOrInTheWater() {
        // At the surface block itself, and directly above it
        assertEquals(57, TrapRoles.classify(57, List.of(57, 63)).stasisY());
        assertEquals(58, TrapRoles.classify(57, List.of(58, 63)).stasisY());
        // Two block above the surface is already a cover
        assertFalse(TrapRoles.classify(57, List.of(59)).hasStasis());
    }

    @Test
    void twoAtTheWaterIsAmbiguousAndNothingIsClassified() {
        TrapRoles r = TrapRoles.classify(57, List.of(57, 58, 63));
        assertNotNull(r.error());
        assertEquals(Role.NONE, r.roleOf(58));
        assertEquals(Role.NONE, r.roleOf(63));
        assertTrue(r.coverPlan(Set.of(58, 63)).isEmpty());
    }

    @Test
    void theCoverPlanNeverHoldsTheStasisTrapdoor() {
        TrapRoles r = TrapRoles.classify(57, List.of(58, 63, 64));
        // Everything was opened on the way out, the stasis one included
        assertEquals(List.of(63, 64), r.coverPlan(Set.of(58, 63, 64)));
        // Only what was opened by us, and never one that wasn't
        assertEquals(List.of(64), r.coverPlan(Set.of(64)));
        assertTrue(r.coverPlan(Set.of(58)).isEmpty());
        assertTrue(TrapRoles.classify(57, List.of(58)).coverPlan(Set.of(58)).isEmpty());
    }

    @Test
    void afterTheCover() {
        // Pearl there, slow, at the water, long enough: armed
        assertEquals(Armed.ARMED, TrapRoles.decide(15, 15, true, true, true, false));
        assertEquals(Armed.WAIT, TrapRoles.decide(5, 15, true, true, true, false));
        // The player was pulled
        assertEquals(Armed.TRIGGERED, TrapRoles.decide(5, 15, true, true, true, true));
        // The pearl is gone
        assertEquals(Armed.TRIGGERED, TrapRoles.decide(5, 15, false, false, false, false));
        // Present but never settling
        assertEquals(Armed.WAIT, TrapRoles.decide(20, 15, true, false, true, false));
        assertEquals(Armed.NOT_SETTLED, TrapRoles.decide(31, 15, true, false, true, false));
    }
}
