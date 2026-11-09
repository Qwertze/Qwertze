package com.porter.qwerte.mixin;

import com.porter.qwerte.events.ShulkerTooltipLinesEvent;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Posts ShulkerTooltipLinesEvent before vanilla adds a shulker box's content lines (1.21.4). Meteor's
 * BetterTooltips mixes into the same method (HEAD, cancellable); both may cancel. 1.21.11 moved this into
 * ItemContainerContents.addToTooltip, so the 1.21.11 build has its own mixin.
 */
@Mixin(ShulkerBoxBlock.class)
public abstract class ShulkerBoxBlockMixin {
    @Inject(method = "appendHoverText(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/Item$TooltipContext;Ljava/util/List;Lnet/minecraft/world/item/TooltipFlag;)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void qwerte$onLines(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag, CallbackInfo ci) {
        if (MeteorClient.EVENT_BUS.post(ShulkerTooltipLinesEvent.get(stack)).isCancelled()) ci.cancel();
    }
}
