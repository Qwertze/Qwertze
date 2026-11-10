package com.porter.qwerte.mixin;

import com.porter.qwerte.messenger.ChatReplace;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides the vanilla chat list while the Messenger replaces it (Messenger setting replace-chat). Cancellable HEAD
 * injects only, no redirect or overwrite, all doing nothing while {@link ChatReplace#hidden()} is false (1.21.4):
 * <ul>
 *   <li>{@code render(GuiGraphics, int, int, int, boolean)} is cancelled, so no line is drawn;</li>
 *   <li>{@code handleChatQueueClicked(DD)Z}, {@code getClickedComponentStyleAt(DD)Style} and
 *       {@code getMessageTagAt(DD)GuiMessageTag} answer false / null, so a hidden line can not be clicked or hovered.</li>
 * </ul>
 * Messages are still added, logged and passed to listeners. Priority 1100 so that setup of other mods at HEAD
 * (lower priority) is not skipped.
 */
@Mixin(value = ChatComponent.class, priority = 1100)
public abstract class ChatComponentMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void qwerte$hideForMessenger(GuiGraphics graphics, int tickCount, int mouseX, int mouseY, boolean focused, CallbackInfo ci) {
        if (ChatReplace.hidden()) ci.cancel();
    }

    @Inject(method = "handleChatQueueClicked(DD)Z", at = @At("HEAD"), cancellable = true, require = 1)
    private void qwerte$noQueueClick(double x, double y, CallbackInfoReturnable<Boolean> cir) {
        if (ChatReplace.hidden()) cir.setReturnValue(false);
    }

    @Inject(method = "getClickedComponentStyleAt(DD)Lnet/minecraft/network/chat/Style;", at = @At("HEAD"), cancellable = true, require = 1)
    private void qwerte$noClickedStyle(double x, double y, CallbackInfoReturnable<Style> cir) {
        if (ChatReplace.hidden()) cir.setReturnValue(null);
    }

    @Inject(method = "getMessageTagAt(DD)Lnet/minecraft/client/GuiMessageTag;", at = @At("HEAD"), cancellable = true, require = 1)
    private void qwerte$noTag(double x, double y, CallbackInfoReturnable<GuiMessageTag> cir) {
        if (ChatReplace.hidden()) cir.setReturnValue(null);
    }
}
