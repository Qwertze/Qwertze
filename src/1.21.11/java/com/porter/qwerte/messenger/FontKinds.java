package com.porter.qwerte.messenger;

import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;

/**
 * Whether a text style draws a picture. Since 1.21.9 an "object" component (a player head or an atlas sprite) is text
 * in a sprite font ({@code ObjectContents} gives its pieces the style with {@code withFont(fontDescription)}), and its
 * text is only the fallback "[Name head]".
 */
final class FontKinds {
    private FontKinds() {
    }

    static boolean isSprite(Style style) {
        FontDescription font = style.getFont();
        return font instanceof FontDescription.PlayerSprite || font instanceof FontDescription.AtlasSprite;
    }
}
