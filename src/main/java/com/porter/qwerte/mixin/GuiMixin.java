package com.porter.qwerte.mixin;

import com.porter.qwerte.events.HotbarSlotRenderEvent;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Posts HotbarSlotRenderEvent after vanilla drew one hotbar item. Gui.renderSlot is private with the same
 * descriptor in 1.21.4 and 1.21.11. It is also used for the offhand slot. Meteor and other HUD mods
 * hook the same method or its caller; a TAIL inject coexists with them.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "renderSlot(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/client/DeltaTracker;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;I)V", at = @At("TAIL"), require = 1)
    private void qwerte$afterHotbarSlot(GuiGraphics graphics, int x, int y, DeltaTracker delta, Player player, ItemStack stack, int seed, CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(HotbarSlotRenderEvent.get(graphics, x, y, stack));
    }
}
