package com.porter.qwerte.mixin;

import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the text a ChatScreen was opened with: "" from the chat key, "/" from the command key. The field is named
 * {@code initial} in 1.21.4 (private) and 1.21.11 (protected). Only a getter is generated; it injects nothing, so
 * it can not change what the game does. Used by the Messenger's take-chat-key setting.
 */
@Mixin(ChatScreen.class)
public interface ChatScreenAccessor {
    @Accessor("initial")
    String qwerte$getInitial();
}
