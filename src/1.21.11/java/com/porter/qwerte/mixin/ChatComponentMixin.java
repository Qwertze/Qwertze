package com.porter.qwerte.mixin;

import com.porter.qwerte.messenger.ChatReplace;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the vanilla chat list while the Messenger replaces it (Messenger setting replace-chat). Cancellable HEAD
 * injects only, no redirect or overwrite, all doing nothing while {@link ChatReplace#hidden()} is false (1.21.11):
 * <ul>
 *   <li>{@code render(GuiGraphics, Font, int, int, int, boolean, boolean)} is cancelled, so no line is drawn;</li>
 *   <li>{@code captureClickableText(ActiveTextCollector, int, int, boolean)} is cancelled, so a hidden line can not be
 *       clicked (the chat screen reaches the private render through it).</li>
 * </ul>
 * Messages are still added, logged and passed to listeners. Priority 1100 so that setup of other mods at HEAD
 * (lower priority) is not skipped.
 */
@Mixin(value = ChatComponent.class, priority = 1100)
public abstract class ChatComponentMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void qwerte$hideForMessenger(GuiGraphics graphics, Font font, int tickCount, int mouseX, int mouseY, boolean focused, boolean changeCursorOnInsertions, CallbackInfo ci) {
        if (ChatReplace.hidden()) ci.cancel();
    }

    @Inject(method = "captureClickableText(Lnet/minecraft/client/gui/ActiveTextCollector;IIZ)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void qwerte$noClickableText(ActiveTextCollector collector, int mouseX, int mouseY, boolean focused, CallbackInfo ci) {
        if (ChatReplace.hidden()) ci.cancel();
    }
}
