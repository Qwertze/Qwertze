package com.porter.qwerte.messenger;

import net.minecraft.client.gui.components.EditBox;

/** Hands keys, characters and clicks to a text box, whose input methods differ between versions (Minecraft 1.21.4). */
final class EditInput {
    private EditInput() {
    }

    static boolean key(EditBox box, int key, int scancode, int modifiers) {
        return box.keyPressed(key, scancode, modifiers);
    }

    /** One code point; 1.21.4 takes chars, so a pair outside the BMP goes in as its two halves. */
    static boolean typed(EditBox box, int codepoint, int modifiers) {
        boolean any = false;
        for (char c : Character.toChars(codepoint)) any |= box.charTyped(c, modifiers);
        return any;
    }

    static boolean click(EditBox box, double x, double y, int button) {
        return box.mouseClicked(x, y, button);
    }
}
