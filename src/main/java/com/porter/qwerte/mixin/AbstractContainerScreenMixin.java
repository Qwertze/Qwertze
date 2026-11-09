package com.porter.qwerte.mixin;

import com.porter.qwerte.events.ContainerScreenRenderEvent;
import com.porter.qwerte.events.ContainerSlotClickEvent;
import com.porter.qwerte.events.ContainerTooltipEvent;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Container screen hooks for Container Tweaks: a render event (which also records the mouse position for
 * click events), a cancellable tooltip event and a cancellable slot click event. The signatures used are
 * identical in 1.21.4 and 1.21.11. Each hook only posts a reusable singleton event; with no subscribers the
 * vanilla behaviour is unchanged. Other mods that touch the same methods (Meteor itself, inventory mods)
 * inject alongside this one; the two HEAD hooks only cancel when a Qwerte subscriber asks for it.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    @Shadow
    protected Slot hoveredSlot;

    @Shadow
    @Final
    protected AbstractContainerMenu menu;

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"), require = 1)
    private void qwerte$afterRender(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ContainerSlotClickEvent.lastMouseX = mouseX;
        ContainerSlotClickEvent.lastMouseY = mouseY;
        MeteorClient.EVENT_BUS.post(ContainerScreenRenderEvent.get((AbstractContainerScreen<?>) (Object) this, graphics, mouseX, mouseY, hoveredSlot));
    }

    @Inject(method = "renderTooltip(Lnet/minecraft/client/gui/GuiGraphics;II)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void qwerte$onTooltip(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (MeteorClient.EVENT_BUS.post(ContainerTooltipEvent.get((AbstractContainerScreen<?>) (Object) this, graphics, mouseX, mouseY, hoveredSlot)).isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ClickType;)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void qwerte$onSlotClicked(Slot slot, int slotId, int button, ClickType type, CallbackInfo ci) {
        if (MeteorClient.EVENT_BUS.post(ContainerSlotClickEvent.get((AbstractContainerScreen<?>) (Object) this, menu, slot, slotId, button, type)).isCancelled()) {
            ci.cancel();
        }
    }
}
