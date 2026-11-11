package com.porter.qwerte.compat;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RawKeysTest {
    @Test
    void modifiersPairLeftAndRight() {
        assertEquals(GLFW.GLFW_KEY_RIGHT_ALT, RawKeys.otherSide(GLFW.GLFW_KEY_LEFT_ALT));
        assertEquals(GLFW.GLFW_KEY_LEFT_ALT, RawKeys.otherSide(GLFW.GLFW_KEY_RIGHT_ALT));
        assertEquals(GLFW.GLFW_KEY_RIGHT_SHIFT, RawKeys.otherSide(GLFW.GLFW_KEY_LEFT_SHIFT));
        assertEquals(GLFW.GLFW_KEY_LEFT_CONTROL, RawKeys.otherSide(GLFW.GLFW_KEY_RIGHT_CONTROL));
        assertEquals(GLFW.GLFW_KEY_RIGHT_SUPER, RawKeys.otherSide(GLFW.GLFW_KEY_LEFT_SUPER));
    }

    @Test
    void otherKeysAreTheirOwnPartner() {
        assertEquals(GLFW.GLFW_KEY_A, RawKeys.otherSide(GLFW.GLFW_KEY_A));
        assertEquals(GLFW.GLFW_KEY_MENU, RawKeys.otherSide(GLFW.GLFW_KEY_MENU));
        assertEquals(GLFW.GLFW_KEY_SPACE, RawKeys.otherSide(GLFW.GLFW_KEY_SPACE));
    }
}
