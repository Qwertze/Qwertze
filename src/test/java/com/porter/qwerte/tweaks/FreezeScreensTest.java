package com.porter.qwerte.tweaks;

import com.porter.qwerte.tweaks.FreezeScreens.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FreezeScreensTest {
    @Test
    void inventoryScreensFollowTheSetting() {
        assertTrue(FreezeScreens.allowed(Kind.INVENTORY, true));
        assertFalse(FreezeScreens.allowed(Kind.INVENTORY, false));
    }

    @Test
    void containersAlwaysFreeze() {
        assertTrue(FreezeScreens.allowed(Kind.CONTAINER, true));
        assertTrue(FreezeScreens.allowed(Kind.CONTAINER, false));
    }
}
