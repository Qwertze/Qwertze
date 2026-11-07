package com.porter.qwerte.stash;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Shows one item from the stash in a vanilla container screen, so hovering it gives the full tooltip: name, enchants,
 * and everything else the game shows (all components with F3+H advanced tooltips on). Look only: every click is
 * ignored, and the screen never talks to the server.
 */
public class ItemPreviewScreen extends DispenserScreen {
    // Not a container id the server will ever have open for us, in case anything slips through
    private static final int FAKE_CONTAINER_ID = 254;

    // Where Esc goes back to; null closes to the game
    private final Screen returnTo;

    private ItemPreviewScreen(DispenserMenu menu, Component title, Screen returnTo) {
        super(menu, mc.player.getInventory(), title);
        this.returnTo = returnTo;
    }

    public static void open(ItemStack stack, int total, String stashName) {
        show(stack, total, String.format("%s x%,d @ %s", StackInfo.label(stack), total, stashName), null);
    }

    /** Like {@link #open(ItemStack, int, String)} but with the whole title given, and Esc returns to {@code returnTo}. */
    public static void open(ItemStack stack, int total, String title, Screen returnTo) {
        show(stack, total, title, returnTo);
    }

    private static void show(ItemStack stack, int total, String title, Screen returnTo) {
        if (mc.player == null) return;
        SimpleContainer container = new SimpleContainer(9);
        ItemStack shown = stack.copyWithCount(Math.max(1, Math.min(total, stack.getMaxStackSize())));
        container.setItem(4, shown);

        DispenserMenu menu = new DispenserMenu(FAKE_CONTAINER_ID, mc.player.getInventory(), container);
        mc.setScreen(new ItemPreviewScreen(menu, Component.literal(title), returnTo));
    }

    @Override
    public void onClose() {
        // The default also tells the server to close a container it never opened for us
        mc.setScreen(returnTo);
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int button, ClickType type) {
        // Look, don't touch
    }
}
