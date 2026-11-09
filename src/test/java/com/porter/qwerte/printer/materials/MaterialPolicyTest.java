package com.porter.qwerte.printer.materials;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.porter.qwerte.printer.materials.MaterialPolicy.Outcome;
import org.junit.jupiter.api.Test;

class MaterialPolicyTest {
    // decide(disableWhenOut, otherWorkLeft, supplyPossible, fetchFailed, orderWaiting)

    @Test
    void otherWorkAlwaysComesFirstWhateverTheSetting() {
        assertEquals(Outcome.CONTINUE, MaterialPolicy.decide(false, true, false, false, false));
        assertEquals(Outcome.CONTINUE, MaterialPolicy.decide(true, true, false, false, false));
        assertEquals(Outcome.CONTINUE, MaterialPolicy.decide(true, true, true, true, true));
    }

    @Test
    void aSourceIsTriedBeforeAnythingElse() {
        assertEquals(Outcome.FETCH, MaterialPolicy.decide(false, false, true, false, false));
        assertEquals(Outcome.FETCH, MaterialPolicy.decide(true, false, true, false, true));
    }

    @Test
    void aStashOrderMakesItWaitWhateverTheSetting() {
        assertEquals(Outcome.WAIT_ORDER, MaterialPolicy.decide(false, false, false, false, true));
        assertEquals(Outcome.WAIT_ORDER, MaterialPolicy.decide(true, false, false, false, true));
    }

    @Test
    void withNothingToSupplyItTheSettingDecides() {
        assertEquals(Outcome.WAIT, MaterialPolicy.decide(false, false, false, false, false));
        assertEquals(Outcome.DISABLE, MaterialPolicy.decide(true, false, false, false, false));
    }

    @Test
    void aFailedFetchPausesUnlessTheSettingIsOnAndNoOrderIsOpen() {
        assertEquals(Outcome.WAIT, MaterialPolicy.decide(false, false, true, true, false));
        assertEquals(Outcome.WAIT, MaterialPolicy.decide(true, false, true, true, true));
        assertEquals(Outcome.DISABLE, MaterialPolicy.decide(true, false, true, true, false));
    }

    @Test
    void withTheSettingOffNothingEverDisables() {
        for (int mask = 0; mask < 16; mask++) {
            boolean other = (mask & 1) != 0, supply = (mask & 2) != 0, failed = (mask & 4) != 0, order = (mask & 8) != 0;
            assertEquals(false, MaterialPolicy.decide(false, other, supply, failed, order) == Outcome.DISABLE, "mask " + mask);
        }
    }
}
