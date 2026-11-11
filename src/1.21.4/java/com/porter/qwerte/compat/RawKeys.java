package com.porter.qwerte.compat;

import com.porter.qwerte.tweaks.DownKeys;
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
        if (!bind.isKey()) return GLFW.glfwGetMouseButton(window, value) == GLFW.GLFW_PRESS;
        if (GLFW.glfwGetKey(window, value) == GLFW.GLFW_PRESS) return true;
        // The left and right Shift, Control, Alt and Super keys count as the same key: some layouts (AltGr) and keyboards
        // report the other one
        int other = otherSide(value);
        return other != value && GLFW.glfwGetKey(window, other) == GLFW.GLFW_PRESS;
    }

    /** The key on the other side of a modifier key, or the key itself. */
    public static int otherSide(int key) {
        if (key >= GLFW.GLFW_KEY_LEFT_SHIFT && key <= GLFW.GLFW_KEY_LEFT_SUPER) return key + 4;
        if (key >= GLFW.GLFW_KEY_RIGHT_SHIFT && key <= GLFW.GLFW_KEY_RIGHT_SUPER) return key - 4;
        return key;
    }

    /** The modifier keys and the freeze key GLFW reports down, and how many other keys, for the freeze debug log. */
    public static String pressed(int freezeKey) {
        if (mc.getWindow() == null) return "[] others=0";
        long window = mc.getWindow().getWindow();
        return DownKeys.describe(key -> GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS, freezeKey);
    }
}
