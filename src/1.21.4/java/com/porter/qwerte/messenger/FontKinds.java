package com.porter.qwerte.messenger;

import net.minecraft.network.chat.Style;

/** Whether a text style draws a picture. Minecraft 1.21.4 has no sprite text (no object components): never. */
final class FontKinds {
    private FontKinds() {
    }

    static boolean isSprite(Style style) {
        return false;
    }
}
