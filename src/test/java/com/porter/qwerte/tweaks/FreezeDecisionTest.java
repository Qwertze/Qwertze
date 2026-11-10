package com.porter.qwerte.tweaks;

import com.porter.qwerte.tweaks.FreezeDecision.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FreezeDecisionTest {
    @Test
    void holdingOverShulkerFreezes() {
        assertEquals(Action.FREEZE, FreezeDecision.decide(true, true, false, false));
    }

    @Test
    void notHeldDoesNothingWhenNotFrozen() {
        assertEquals(Action.NONE, FreezeDecision.decide(false, true, false, false));
    }

    @Test
    void heldOverNonShulkerDoesNothing() {
        assertEquals(Action.NONE, FreezeDecision.decide(true, false, false, false));
    }

    @Test
    void frozenStaysWhileHeldEvenOffTheBox() {
        assertEquals(Action.NONE, FreezeDecision.decide(true, false, true, true));
        assertEquals(Action.NONE, FreezeDecision.decide(true, true, true, true));
    }

    @Test
    void releasingUnfreezes() {
        assertEquals(Action.UNFREEZE, FreezeDecision.decide(false, true, true, true));
    }

    @Test
    void otherScreenUnfreezes() {
        assertEquals(Action.UNFREEZE, FreezeDecision.decide(true, true, true, false));
    }
}
