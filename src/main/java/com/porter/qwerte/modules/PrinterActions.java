package com.porter.qwerte.modules;

import com.porter.qwerte.compat.Players;
import com.porter.qwerte.printer.place.PlaceSimulator;
import com.porter.qwerte.printer.place.StateSpec;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The things the Printer does to the world, one click at a time: place a block the way ChestPlacer does (the real sneak
 * key held first, then {@code gameMode.useItemOn} directly), break a block, check what was placed, and quick-move one
 * stack out of an open container. Stateless apart from the sneak holder; the decisions are the Printer's.
 */
final class PrinterActions {
    private PrinterActions() {}

    /** One placement the Printer has decided on. */
    record Click(BlockPos target, BlockHitResult hit, boolean airplace, int slot, Item item, int cost, int extraPlaces, int useClicks,
                 boolean sneak) {}

    enum Outcome {
        /** The click went out. */
        SENT,
        /** The cell is not free any more. */
        CELL_TAKEN,
        /** The block that was to be clicked is gone. */
        SUPPORT_GONE,
        /** The item is no longer in the hotbar slot (or not enough of it). */
        ITEM_GONE,
        /** There is no world. */
        NO_WORLD
    }

    // The sneak holder: the real key, held for some ticks before a sneaking click (the server reads sneak from the input packet)

    /** Holds the sneak key. The key is ours only while {@link #holding()}; letting go gives it back to what the keyboard says. */
    static final class Sneak {
        private boolean holding;
        private int ticks;

        boolean holding() {
            return holding;
        }

        /** Presses (or keeps pressing) the key; call every tick the sneak is wanted. */
        void press() {
            KeyMapping key = mc.options.keyShift;
            holding = true;
            key.setDown(true);
            ticks++;
        }

        /** The key has been down for at least {@code needed} ticks and the client has applied it. */
        boolean ready(int needed) {
            return holding && ticks >= needed && mc.player != null && mc.player.isShiftKeyDown();
        }

        /** Lets go; the key goes back to whatever the real keyboard says. */
        void release() {
            if (holding) {
                holding = false;
                KeyMapping key = mc.options.keyShift;
                key.setDown(Input.isPressed(key));
            }
            ticks = 0;
        }

        /** The player entity changed or the world is gone: only the bookkeeping is reset. */
        void abandon() {
            if (holding && mc.options != null) mc.options.keyShift.setDown(Input.isPressed(mc.options.keyShift));
            holding = false;
            ticks = 0;
        }
    }

    // Placing

    /**
     * Sends one placement: checks that the world is still as it was when the decision was made (the rotation callback runs
     * later in the tick), selects the hotbar slot, clicks with {@code useItemOn} and swings. The extra placements (candles,
     * the second slab) click the new block itself. Use clicks (repeater delay, door open) follow, never while sneaking.
     */
    static Outcome clickPlace(Click c, boolean swing) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return Outcome.NO_WORLD;
        BlockState at = mc.level.getBlockState(c.target());
        if (!(at.isAir() || at.canBeReplaced())) return Outcome.CELL_TAKEN;
        if (!c.airplace() && mc.level.getBlockState(c.hit().getBlockPos()).isAir()) return Outcome.SUPPORT_GONE;
        if (c.slot() < 0 || c.slot() > 8) return Outcome.ITEM_GONE;
        ItemStack held = mc.player.getInventory().getItem(c.slot());
        if (!held.is(c.item()) || held.getCount() < c.cost()) return Outcome.ITEM_GONE;

        if (Players.selectedSlot() != c.slot()) InvUtils.swap(c.slot(), false);
        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, c.hit());
        if (result.consumesAction()) swing(swing);

        if (c.extraPlaces() > 0) {
            Vec3 centre = Vec3.atCenterOf(c.target());
            BlockHitResult self = new BlockHitResult(new Vec3(centre.x, c.target().getY() + 0.5, centre.z), Direction.UP, c.target(), false);
            for (int i = 0; i < c.extraPlaces(); i++) {
                if (mc.player.getInventory().getItem(c.slot()).isEmpty()) break;
                if (mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, self).consumesAction()) swing(swing);
            }
        }
        if (c.useClicks() > 0 && !c.sneak()) useClicks(c.target(), c.useClicks(), swing);
        return Outcome.SENT;
    }

    /** Use clicks (not placements) on the block at {@code pos}: repeater delay, comparator mode, note, door open. */
    static void useClicks(BlockPos pos, int count, boolean swing) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        Vec3 centre = Vec3.atCenterOf(pos);
        BlockHitResult hit = new BlockHitResult(new Vec3(centre.x, pos.getY() + 0.5, centre.z), Direction.UP, pos, false);
        for (int i = 0; i < count; i++) {
            if (mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit).consumesAction()) swing(swing);
        }
    }

    /** {@code show} false only hides the animation on our screen: the swing packet still goes out. */
    static void swing(boolean show) {
        if (show) mc.player.swing(InteractionHand.MAIN_HAND);
        else if (mc.getConnection() != null) mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
    }

    // Checking

    /** The state in the world has the block and every placed property of the spec. */
    static boolean built(StateSpec spec, BlockState state) {
        return PlaceSimulator.matches(spec, state);
    }

    /** True if a block can be put here (nothing, or something a placement replaces). */
    static boolean free(BlockState state) {
        return state.isAir() || state.canBeReplaced();
    }

    // Breaking

    /** One tick of breaking the block, rotating first if asked. Needs a tick-pre event (Restocker's own helper). */
    static void breakBlock(BlockPos pos, boolean rotate, boolean swing) {
        Restocker.clickBreak(pos, rotate, swing);
    }

    // Taking from a container

    /**
     * Shift-clicks one slot of the open menu. The game moves the stack into stacks of the same item in the player's
     * inventory first and then into empty slots, and never into a slot that holds something else, which is the rule the
     * Printer needs; the caller still checks there is room. Returns false when there was no world.
     */
    static boolean quickMove(AbstractContainerMenu menu, int slotId) {
        if (mc.player == null || mc.gameMode == null) return false;
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slotId, 0, ClickType.QUICK_MOVE, mc.player);
        return true;
    }
}
