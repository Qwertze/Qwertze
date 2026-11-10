package com.porter.qwerte.craft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** What a 204 to a claim means for a saved order (the plugin keeps its craft ledger through two of them). */
class ClaimWaitTest {
    @Test
    void aSavedCraftOrderIsKeptThroughTwo204sAndDroppedAtTheThird() {
        ClaimWait w = new ClaimWait();
        assertTrue(w.keepSaved(true));
        assertTrue(w.keepSaved(true));
        assertFalse(w.keepSaved(true));
        // and the count starts again
        assertTrue(w.keepSaved(true));
    }

    @Test
    void anythingElseIsDroppedAtOnceAndAnOtherAnswerStartsTheCountAgain() {
        ClaimWait w = new ClaimWait();
        assertFalse(w.keepSaved(false));
        assertTrue(w.keepSaved(true));
        w.other();
        assertTrue(w.keepSaved(true));
        assertTrue(w.keepSaved(true));
        assertFalse(w.keepSaved(true));
    }
}
