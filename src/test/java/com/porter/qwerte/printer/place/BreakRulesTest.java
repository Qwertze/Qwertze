package com.porter.qwerte.printer.place;

import static com.porter.qwerte.printer.place.BreakRules.Verdict.FLUID;
import static com.porter.qwerte.printer.place.BreakRules.Verdict.NEXT_TO_WATER;
import static com.porter.qwerte.printer.place.BreakRules.Verdict.OK;
import static com.porter.qwerte.printer.place.BreakRules.fluidVerdict;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BreakRulesTest {
    @Test
    void nothingWetNearbyIsFine() {
        assertEquals(OK, fluidVerdict(false, false, true, false, false));
        assertEquals(OK, fluidVerdict(false, false, false, false, false));
    }

    @Test
    void aRealLiquidBlockIsAlwaysRisky() {
        assertEquals(FLUID, fluidVerdict(true, false, false, true, true));
        assertEquals(FLUID, fluidVerdict(true, true, false, true, true));
    }

    @Test
    void waterloggedWaterAllowsTheBreakOnlyWhenTheReplacementCanFollow() {
        assertEquals(OK, fluidVerdict(false, true, false, true, true));
        assertEquals(NEXT_TO_WATER, fluidVerdict(false, true, true, true, true), "wanted air: nothing refills the cell");
        assertEquals(NEXT_TO_WATER, fluidVerdict(false, true, false, false, true), "item not carried");
        assertEquals(NEXT_TO_WATER, fluidVerdict(false, true, false, true, false), "no recipe from here");
    }
}
