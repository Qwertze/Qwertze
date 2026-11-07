package com.porter.qwerte.manager.local;

import com.porter.qwerte.manager.local.CaptureRules.Kind;
import com.porter.qwerte.manager.local.LocalStore.Entry;
import com.porter.qwerte.stash.StashIndex;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Writes down the contents of the chests, barrels and shulker boxes the player opens, into the {@link LocalHub}. Passive:
 * no packets are sent and nothing in the container is touched. A menu counts only if it opened right after the player
 * clicked one container block (clicks on two different containers in the window make it ambiguous and nothing is
 * taken), with the number of slots that block has ({@link CaptureRules}). Subscribed to the event bus while the
 * stash-window module is on.
 */
public final class LocalCapture {
    private static final int SWEEP_EVERY = 100;
    private static final int SWEEP_COUNT = 64;

    private final LocalHub hub;
    private final BooleanSupplier enabled;
    private final ClickLog clicks = new ClickLog();

    private long clock;

    private AbstractContainerMenu tracked;
    private LocalStore trackedStore;
    private ContainerKey trackedKey;
    private List<int[]> trackedParts;
    private String trackedBlock;
    private int trackedSize;
    private Long lastSignature;
    private int openTicks;
    private int sweepAt;

    public LocalCapture(LocalHub hub, BooleanSupplier enabled) {
        this.hub = hub;
        this.enabled = enabled;
    }

    /** The kind of container a block is, or null (ender chests and everything else are left out). */
    static Kind kindOf(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof ChestBlock) return Kind.CHEST;
        if (b instanceof BarrelBlock) return Kind.BARREL;
        if (b instanceof ShulkerBoxBlock) return Kind.SHULKER_BOX;
        return null;
    }

    /** The block positions of the container at {@code pos}: two for a double chest. */
    static List<int[]> partsOf(BlockState state, BlockPos pos) {
        List<int[]> parts = new ArrayList<>();
        parts.add(new int[] { pos.getX(), pos.getY(), pos.getZ() });
        if (state.getBlock() instanceof ChestBlock && ChestBlock.getBlockType(state) != DoubleBlockCombiner.BlockType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            parts.add(new int[] { other.getX(), other.getY(), other.getZ() });
        }
        return parts;
    }

    @EventHandler
    private void onInteract(InteractBlockEvent event) {
        if (mc.player == null || mc.level == null) return;
        BlockPos pos = event.result.getBlockPos();
        // Only container clicks are kept, so unrelated clicks can't hide which container a menu belongs to
        if (kindOf(mc.level.getBlockState(pos)) == null) return;
        clicks.add(pos.getX(), pos.getY(), pos.getZ(), clock, mc.player.containerMenu);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        finish();
        clicks.take(clock);
        hub.save();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        clock++;
        hub.tick();
        if (mc.player == null || mc.level == null) return;
        if (!enabled.getAsBoolean()) {
            finish();
            return;
        }
        clicks.expire(clock);

        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu) {
            if (menu != tracked) {
                // The last state of the container left behind is written before the next one is looked at
                finish();
                tracked = menu;
                resolve(menu);
            }
            if (trackedKey != null) read(menu);
        } else if (tracked != null) {
            finish();
        }

        if (clock % SWEEP_EVERY == 0) sweep();
    }

    /** The tracked menu went away (closed, replaced, or capture turned off): its final state is written, then it is let go. */
    private void finish() {
        if (tracked != null && trackedKey != null) read(tracked);
        tracked = null;
        trackedKey = null;
        trackedStore = null;
        lastSignature = null;
    }

    /** Works out which block a freshly opened menu belongs to, or leaves {@code trackedKey} null. */
    private void resolve(AbstractContainerMenu menu) {
        trackedKey = null;
        trackedStore = null;
        lastSignature = null;
        openTicks = 0;
        // Taken every time so an old click can't be matched to a later menu
        ClickLog.Click click = clicks.take(clock);
        String server = ServerId.current();
        if (click == null || server == null) return;
        LocalStore store = hub.store();
        if (store == null || !store.server().equals(server)) return;

        BlockPos pos = new BlockPos(click.x(), click.y(), click.z());
        BlockState state = mc.level.getBlockState(pos);
        Kind kind = kindOf(state);
        if (kind == null) return;
        if ((kind == Kind.SHULKER_BOX) != (menu instanceof ShulkerBoxMenu)) return;
        List<int[]> parts = partsOf(state, pos);
        int expected = CaptureRules.expectedSize(kind, parts.size() > 1);
        if (!CaptureRules.shouldCapture(true, click.menu() == menu, clock - click.tick(), menu.slots.size(), expected)) return;

        trackedKey = ContainerKey.of(server, StashIndex.dimension(), parts);
        trackedStore = store;
        trackedParts = parts;
        trackedBlock = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        trackedSize = expected;
    }

    /**
     * Writes the contents down when they changed. A signature is worked out first, without copying anything; stacks are
     * copied only when a record is made.
     */
    private void read(AbstractContainerMenu menu) {
        openTicks++;
        int n = Math.min(trackedSize, menu.slots.size() - CaptureRules.PLAYER_SLOTS);
        long signature = 0;
        boolean empty = true;
        for (int i = 0; i < n; i++) {
            ItemStack s = menu.slots.get(i).getItem();
            if (s.isEmpty()) continue;
            empty = false;
            long h = CaptureRules.mix(BuiltInRegistries.ITEM.getId(s.getItem()) + 1, s.getCount());
            h = CaptureRules.mix(h, s.getComponentsPatch().hashCode());
            signature = CaptureRules.mix(signature, h + i);
        }
        if (!CaptureRules.shouldRecord(lastSignature, signature, empty, openTicks)) return;
        lastSignature = signature;
        ItemStack[] stacks = new ItemStack[trackedSize];
        for (int i = 0; i < trackedSize; i++) {
            ItemStack s = i < n ? menu.slots.get(i).getItem() : ItemStack.EMPTY;
            stacks[i] = s.isEmpty() ? ItemStack.EMPTY : s.copy();
        }
        hub.record(trackedStore, trackedKey, trackedParts, trackedBlock, trackedSize, stacks);
    }

    /** Forgets stored containers whose block is gone (broken, or a chest that is no longer double). */
    private void sweep() {
        LocalStore store = hub.current();
        String server = ServerId.current();
        if (store == null || server == null || !store.server().equals(server)) return;
        if (!store.loaded() || store.size() == 0) return;
        String dimension = StashIndex.dimension();
        List<Entry> list = store.snapshot();
        for (int i = 0; i < Math.min(SWEEP_COUNT, list.size()); i++) {
            Entry e = list.get((sweepAt + i) % list.size());
            if (!e.key().dimension().equals(dimension) || e.key().equals(trackedKey)) continue;
            if (gone(e)) hub.forget(e.key());
        }
        sweepAt = (sweepAt + SWEEP_COUNT) % Math.max(1, list.size());
    }

    private static boolean gone(Entry e) {
        for (int[] p : e.parts()) {
            if (!mc.level.isLoaded(new BlockPos(p[0], p[1], p[2]))) return false;
        }
        BlockPos pos = new BlockPos(e.key().x(), e.key().y(), e.key().z());
        BlockState state = mc.level.getBlockState(pos);
        if (kindOf(state) == null) return true;
        if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(e.block())) return true;
        List<int[]> now = partsOf(state, pos);
        int[] canon = ContainerKey.canonical(now);
        return now.size() != e.parts().size() || canon == null || canon[0] != pos.getX() || canon[1] != pos.getY() || canon[2] != pos.getZ();
    }
}
