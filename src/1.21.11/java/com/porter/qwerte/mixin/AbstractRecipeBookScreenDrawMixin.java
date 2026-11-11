package com.porter.qwerte.mixin;

import com.porter.qwerte.events.ContainerScreenDrawEvent;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The draw-only point of the recipe book screens (player inventory, crafting table, furnaces) in 1.21.11. They draw
 * through their own render, which does not call AbstractContainerScreen.render, and put the recipe book, the carried item
 * and the tooltips on later layers than the slots. Posting at the end of it lets Container Tweaks draw its frozen
 * preview above all of that.
 */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class AbstractRecipeBookScreenDrawMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"), require = 1)
    private void qwerte$afterRender(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(ContainerScreenDrawEvent.get((AbstractContainerScreen<?>) (Object) this, graphics, mouseX, mouseY));
    }
}
