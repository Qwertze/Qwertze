package com.porter.qwerte.printer.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.place.WaterlogRules.Action;
import org.junit.jupiter.api.Test;

class WaterlogRulesTest {
    @Test
    void aWetTargetThatIsDryGetsTheBucketWhenOneIsCarried() {
        assertEquals(Action.USE_BUCKET, WaterlogRules.decide(true, true, false, true));
    }

    @Test
    void withoutABucketTheBlockStaysDry() {
        assertEquals(Action.NEED_BUCKET, WaterlogRules.decide(true, true, false, false));
    }

    @Test
    void nothingToDoWhenNotWantedWetAlreadyWetOrTheSettingIsOff() {
        assertEquals(Action.NONE, WaterlogRules.decide(true, false, false, true));
        assertEquals(Action.NONE, WaterlogRules.decide(true, true, true, true));
        assertEquals(Action.NONE, WaterlogRules.decide(false, true, false, true));
        assertEquals(Action.NONE, WaterlogRules.decide(false, true, false, false));
    }

    @Test
    void leftDryIsWhatTheOneWarningIsAbout() {
        assertTrue(WaterlogRules.leftDry(true, true, false));
        assertTrue(WaterlogRules.leftDry(false, true, true));
        assertFalse(WaterlogRules.leftDry(true, true, true));
        assertFalse(WaterlogRules.leftDry(true, false, false));
    }

    @Test
    void onlyARealWaterSourceNotInTheSchematicIsARefillSource() {
        assertTrue(WaterlogRules.refillSource(true, true, false));
        assertFalse(WaterlogRules.refillSource(false, true, false)); // a waterlogged block holds water but is not water
        assertFalse(WaterlogRules.refillSource(true, false, false)); // flowing water
        assertFalse(WaterlogRules.refillSource(true, true, true));   // a cell the schematic builds in
    }

    @Test
    void refillNeedsTheSettingAnEmptyBucketNoFullOneWorkAheadAndASourceInReach() {
        assertTrue(WaterlogRules.shouldRefill(true, true, false, true, true));
        assertFalse(WaterlogRules.shouldRefill(false, true, false, true, true));
        assertFalse(WaterlogRules.shouldRefill(true, false, false, true, true));
        assertFalse(WaterlogRules.shouldRefill(true, true, true, true, true));
        assertFalse(WaterlogRules.shouldRefill(true, true, false, false, true));
        assertFalse(WaterlogRules.shouldRefill(true, true, false, true, false));
    }
}
