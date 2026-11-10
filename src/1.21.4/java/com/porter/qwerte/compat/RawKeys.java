package com.porter.qwerte.compat;

import meteordevelopment.meteorclient.utils.misc.Keybind;
import org.lwjgl.glfw.GLFW;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Reads a keybind straight from GLFW, for Minecraft 1.21.4. Meteor's own key state ignores keys while its GUI has a text
 * box focused and misses keys another mod swallows, so it is not reliable inside container screens.
 */
public final class RawKeys {
    private RawKeys() {}

    public static boolean down(Keybind bind) {
        if (bind == null || !bind.isSet() || mc.getWindow() == null) return false;
        long window = mc.getWindow().getWindow();
        int value = bind.getValue();
        if (bind.isKey()) return GLFW.glfwGetKey(window, value) == GLFW.GLFW_PRESS;
        return GLFW.glfwGetMouseButton(window, value) == GLFW.GLFW_PRESS;
    }
}
