package com.porter.qwerte.mixin;

import com.porter.qwerte.events.ContainerScreenDrawEvent;
import com.porter.qwerte.events.ContainerScreenRenderEvent;
import com.porter.qwerte.events.ContainerSlotClickEvent;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Posts ContainerScreenRenderEvent (and records the mouse position for click events) once the screen has drawn its
 * slots, for Container Tweaks. Hooks render: in 1.21.4 every container screen, the player inventory included (its recipe book parent calls
 * AbstractContainerScreen.render), draws through it.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenRenderMixin {
    @Shadow
    protected Slot hoveredSlot;

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"), require = 1)
    private void qwerte$afterRender(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ContainerSlotClickEvent.lastMouseX = mouseX;
        ContainerSlotClickEvent.lastMouseY = mouseY;
        MeteorClient.EVENT_BUS.post(ContainerScreenRenderEvent.get((AbstractContainerScreen<?>) (Object) this, graphics, mouseX, mouseY, hoveredSlot));
        // The overlay is lifted in z above the recipe book and the tooltips, so it can be drawn right here
        MeteorClient.EVENT_BUS.post(ContainerScreenDrawEvent.get((AbstractContainerScreen<?>) (Object) this, graphics, mouseX, mouseY));
    }
}
