package com.porter.qwerte.modules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashManagerUrlDefaultTest {
    @Test
    void anOldSetUpConfigOnTheOldDefaultKeepsIt() {
        assertTrue(StashManager.OldDefaultUrl.shouldRestore(false, false, "key"));
        assertEquals("http://127.0.0.1:8765", StashManager.OLD_DEFAULT_URL);
    }

    @Test
    void nothingElseIsChanged() {
        // Saved by this version: a blank URL is the user's choice
        assertFalse(StashManager.OldDefaultUrl.shouldRestore(true, false, "key"));
        // An old config with its own URL keeps it (Meteor loads it)
        assertFalse(StashManager.OldDefaultUrl.shouldRestore(false, true, "key"));
        // Never set up: stays blank, nothing is sent
        assertFalse(StashManager.OldDefaultUrl.shouldRestore(false, false, ""));
        assertFalse(StashManager.OldDefaultUrl.shouldRestore(false, false, "  "));
        assertFalse(StashManager.OldDefaultUrl.shouldRestore(false, false, null));
    }
}
