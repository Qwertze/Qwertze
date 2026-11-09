package com.porter.qwerte.mixin;

import com.porter.qwerte.events.ContainerSlotRenderEvent;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Posts ContainerSlotRenderEvent after vanilla drew one slot. The 1.21.4 signature is
 * renderSlot(GuiGraphics, Slot); 1.21.11 has (GuiGraphics, Slot, int, int), so each version has its own copy.
 * Other mods hooking renderSlot (item overlay and inventory mods) inject independently; TAIL keeps ours
 * after vanilla's item and decorations.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenSlotMixin {
    @Inject(method = "renderSlot(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/inventory/Slot;)V", at = @At("TAIL"), require = 1)
    private void qwerte$afterSlot(GuiGraphics graphics, Slot slot, CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(ContainerSlotRenderEvent.get(graphics, slot));
    }
}
