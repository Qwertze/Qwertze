package com.porter.qwerte.messenger;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/** Hands keys, characters and clicks to a text box, whose input classes differ between versions (Minecraft 1.21.11). */
final class EditInput {
    private EditInput() {
    }

    static boolean key(EditBox box, int key, int scancode, int modifiers) {
        return box.keyPressed(new KeyEvent(key, scancode, modifiers));
    }

    /** One code point (a whole emoji arrives as one). */
    static boolean typed(EditBox box, int codepoint, int modifiers) {
        return box.charTyped(new CharacterEvent(codepoint, modifiers));
    }

    static boolean click(EditBox box, double x, double y, int button) {
        return box.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)), false);
    }
}
