package com.porter.qwerte.mixin;

import com.porter.qwerte.events.ShulkerTooltipLinesEvent;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Posts ShulkerTooltipLinesEvent before vanilla adds the "Item x64" / "and N more" lines of a container item.
 * Meteor's BetterTooltips mixes into the same method (it cancels it when its own preview is on); both are
 * HEAD cancellable injects and both may cancel. 1.21.4 uses ShulkerBoxBlock.appendHoverText instead.
 */
@Mixin(ItemContainerContents.class)
public abstract class ItemContainerContentsMixin {
    @Inject(method = "addToTooltip(Lnet/minecraft/world/item/Item$TooltipContext;Ljava/util/function/Consumer;Lnet/minecraft/world/item/TooltipFlag;Lnet/minecraft/core/component/DataComponentGetter;)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void qwerte$onLines(Item.TooltipContext context, Consumer<Component> consumer, TooltipFlag flag, DataComponentGetter components, CallbackInfo ci) {
        ItemStack stack = components instanceof ItemStack s ? s : null;
        if (MeteorClient.EVENT_BUS.post(ShulkerTooltipLinesEvent.get(stack)).isCancelled()) ci.cancel();
    }
}
