package com.porter.qwerte.modules;

import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.EChestMemory;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Restocks an item for a module, either from a storage block the player picked, or from a shulker kept in their ender
 * chest. Runs one small step per tick while the player stands still; travelling is left to the module.
 */
class Restocker {
    enum Result { Busy, Done, Failed }

    /**
     * What the restocker needs from the module using it.
     */
    interface Owner {
        boolean rotates();

        void moveToHotbar(Predicate<ItemStack> predicate);
    }

    private enum Step {
        OpenStorage, TakeFromStorage,
        PlaceEChest, OpenEChest, TakeShulker, PlaceShulker, OpenShulker, TakeItems, BreakShulker, CollectShulker,
        ReopenEChest, ReturnShulker, BreakEChest, CollectEChest
    }

    // Generous limits, 2b2t can be laggy
    private static final int OPEN_TIMEOUT = 20 * 4;
    private static final int PLACE_TIMEOUT = 20 * 3;
    private static final int BREAK_TIMEOUT = 20 * 15;
    private static final int COLLECT_TIMEOUT = 20 * 5;
    private static final int CLICK_DELAY = 2;

    private final Owner module;
    private final Predicate<ItemStack> wanted;
    private final String what;

    private Step step;
    private int stepTicks;
    private int wait;
    private String failure;

    private BlockPos storage;
    private BlockPos echestSpot, shulkerSpot;
    private int shulkerSlot = -1;
    private Item shulkerItem;
    private int startCount;
    // Most items to take in one go and how to tell the best from the rest, none by default (take everything)
    private IntSupplier limit;
    private ToIntFunction<ItemStack> rank;
    private int echestCount, shulkerCount;

    // How many wanted items are left in the source once we're done, -1 while unknown
    private int sourceLeft;
    private int othersLeft;

    /**
     * @param wanted the items to restock
     * @param what   what to call them in messages, like "seeds"
     */
    Restocker(Owner module, Predicate<ItemStack> wanted, String what) {
        this.module = module;
        this.wanted = wanted;
        this.what = what;
    }

    /**
     * Stops taking once {@code amount} more of the items are in the inventory, the highest ranked first, instead of
     * emptying the container. A stack is taken whole, so stackable items can overshoot; this is meant for items that
     * don't stack. The count is per container, so each storage visited can add that many.
     */
    Restocker limit(IntSupplier amount, ToIntFunction<ItemStack> rank) {
        this.limit = amount;
        this.rank = rank;
        return this;
    }

    String failure() {
        return failure;
    }

    /**
     * True once we've seen that the storage, or every shulker in the ender chest, has none of the items left.
     */
    boolean sourceEmpty() {
        return sourceLeft == 0;
    }

    /**
     * Restock from a container the player picked, which must already be in reach.
     */
    void startManual(BlockPos storage) {
        reset();
        this.storage = storage;
        go(Step.OpenStorage);
    }

    /**
     * Restock from a shulker kept in the ender chest.
     * @return a reason it can't start, or null if it started
     */
    String startEnderChest() {
        reset();

        if (!InvUtils.find(Items.ENDER_CHEST).found()) return "No ender chest in your inventory.";
        if (!InvUtils.find(Restocker::isSilkPickaxe).found()) return "No silk touch pickaxe to pick the ender chest back up with.";
        if (emptySlots() < 2) return "Need 2 free inventory slots to restock from the ender chest.";

        // Meteor remembers what was in the ender chest last time it was opened, check that before placing anything
        if (EChestMemory.isKnown() && bestShulkerIn(EChestMemory.ITEMS::get, EChestMemory.ITEMS.size()) == -1) {
            sourceLeft = 0;
            return "No shulker with " + what + " in your ender chest.";
        }

        echestSpot = findSpot(null);
        if (echestSpot == null) return "No room next to you to place the ender chest.";

        echestCount = InvUtils.find(Items.ENDER_CHEST).count();
        go(Step.PlaceEChest);
        return null;
    }

    Result tick() {
        stepTicks++;
        if (wait > 0) {
            wait--;
            return Result.Busy;
        }

        return switch (step) {
            case OpenStorage -> {
                if (open(storage, menu -> menu != mc.player.inventoryMenu)) go(Step.TakeFromStorage);
                yield timedOut(OPEN_TIMEOUT) ? fail("Couldn't open the " + what + " storage.") : Result.Busy;
            }
            case TakeFromStorage -> takeItems(0) ? Result.Busy : finishStorage();

            case PlaceEChest -> {
                if (mc.level.getBlockState(echestSpot).is(Blocks.ENDER_CHEST)) go(Step.OpenEChest);
                else if (stepTicks == 1 || stepTicks % 20 == 0) placeOnTop(echestSpot, stack -> stack.is(Items.ENDER_CHEST));
                yield timedOut(PLACE_TIMEOUT) ? fail("Couldn't place the ender chest.") : Result.Busy;
            }
            case OpenEChest -> {
                if (open(echestSpot, Restocker::isEChestMenu)) go(Step.TakeShulker);
                else if (timedOut(OPEN_TIMEOUT)) cleanUp("Couldn't open the ender chest.");
                yield Result.Busy;
            }
            case TakeShulker -> {
                takeShulker();
                yield Result.Busy;
            }
            case PlaceShulker -> {
                if (mc.level.getBlockState(shulkerSpot).getBlock() instanceof ShulkerBoxBlock) go(Step.OpenShulker);
                else if (stepTicks == 1 || stepTicks % 20 == 0) placeOnTop(shulkerSpot, stack -> stack.is(shulkerItem));
                else if (timedOut(PLACE_TIMEOUT)) cleanUp("Couldn't place the shulker.", Step.ReopenEChest);
                yield Result.Busy;
            }
            case OpenShulker -> {
                if (open(shulkerSpot, menu -> menu instanceof ShulkerBoxMenu)) go(Step.TakeItems);
                else if (timedOut(OPEN_TIMEOUT)) {
                    failure = "Couldn't open the shulker.";
                    go(Step.BreakShulker);
                }
                yield Result.Busy;
            }
            case TakeItems -> {
                // Keep one slot free so the shulker has somewhere to go when we break it
                if (!takeItems(1)) {
                    sourceLeft = othersLeft + countInContainer();
                    mc.player.closeContainer();
                    go(Step.BreakShulker);
                }
                yield Result.Busy;
            }
            case BreakShulker -> {
                if (mc.level.getBlockState(shulkerSpot).isAir()) go(Step.CollectShulker);
                else if (timedOut(BREAK_TIMEOUT)) cleanUp("Couldn't break the shulker, it's still placed at " + shulkerSpot.toShortString() + ".");
                else breakWith(shulkerSpot, null);
                yield Result.Busy;
            }
            case CollectShulker -> {
                if (InvUtils.find(shulkerItem).count() >= shulkerCount) go(Step.ReopenEChest);
                else if (timedOut(COLLECT_TIMEOUT)) cleanUp("Couldn't pick the shulker back up, it's on the ground at " + shulkerSpot.toShortString() + ".");
                yield Result.Busy;
            }
            case ReopenEChest -> {
                if (open(echestSpot, Restocker::isEChestMenu)) go(Step.ReturnShulker);
                else if (timedOut(OPEN_TIMEOUT)) cleanUp("Couldn't reopen the ender chest to put the shulker back, it's in your inventory.");
                yield Result.Busy;
            }
            case ReturnShulker -> {
                returnShulker();
                yield Result.Busy;
            }
            case BreakEChest -> {
                if (mc.level.getBlockState(echestSpot).isAir()) go(Step.CollectEChest);
                else if (timedOut(BREAK_TIMEOUT)) yield fail(prefix() + "Couldn't break the ender chest, it's still placed at " + echestSpot.toShortString() + ".");
                else breakWith(echestSpot, Restocker::isSilkPickaxe);
                yield Result.Busy;
            }
            case CollectEChest -> {
                if (InvUtils.find(Items.ENDER_CHEST).count() >= echestCount) yield failure == null ? Result.Done : Result.Failed;
                if (timedOut(COLLECT_TIMEOUT)) yield fail(prefix() + "Couldn't pick the ender chest back up, it's on the ground at " + echestSpot.toShortString() + ".");
                yield Result.Busy;
            }
        };
    }

    /**
     * Stops mid-way, closing any open container. Anything placed stays placed.
     */
    void cancel() {
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        if (mc.gameMode != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
        step = null;
    }

    // Steps

    private Result finishStorage() {
        sourceLeft = countInContainer();
        boolean tookAny = InvUtils.find(wanted).count() > startCount;
        mc.player.closeContainer();

        if (sourceLeft == 0 && !tookAny) {
            failure = "The " + what + " storage is empty.";
            return Result.Failed;
        }
        return Result.Done;
    }

    private void takeShulker() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!isEChestMenu(menu)) {
            go(Step.OpenEChest);
            return;
        }

        shulkerSlot = bestShulkerIn(i -> menu.getSlot(i).getItem(), 27);
        if (shulkerSlot == -1) {
            sourceLeft = 0;
            cleanUp("No shulker with " + what + " in your ender chest.");
            return;
        }

        // Whatever the other shulkers hold, so we know if the ender chest runs dry once this one's emptied
        othersLeft = 0;
        for (int i = 0; i < 27; i++) {
            if (i != shulkerSlot) othersLeft += countInShulker(menu.getSlot(i).getItem());
        }

        shulkerSpot = findSpot(echestSpot);
        if (shulkerSpot == null) {
            cleanUp("No room next to you to place the shulker.");
            return;
        }

        shulkerItem = menu.getSlot(shulkerSlot).getItem().getItem();
        shulkerCount = InvUtils.find(shulkerItem).count() + 1;

        InvUtils.shiftClick().slotId(shulkerSlot);
        mc.player.closeContainer();
        wait = CLICK_DELAY;
        go(Step.PlaceShulker);
    }

    private void returnShulker() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!isEChestMenu(menu)) {
            go(Step.ReopenEChest);
            return;
        }

        int index = -1;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).is(shulkerItem)) {
                index = i;
                break;
            }
        }

        if (index != -1) {
            // Put it back exactly where it came from, falling back to any free slot if that one got used
            if (menu.getSlot(shulkerSlot).getItem().isEmpty()) InvUtils.move().from(index).toId(shulkerSlot);
            else InvUtils.shiftClick().slot(index);
        }

        mc.player.closeContainer();
        wait = CLICK_DELAY;
        go(Step.BreakEChest);
    }

    /**
     * Shift-clicks one stack of the wanted items out of the open container.
     * @return false once there's nothing left to take or no room left to take it into
     */
    private boolean takeItems(int keepFree) {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == mc.player.inventoryMenu) return false;
        if (emptySlots() <= keepFree) return false;

        if (limit != null && InvUtils.find(wanted).count() - startCount >= limit.getAsInt()) return false;

        int containerSlots = menu.slots.size() - 36;
        int best = -1, bestRank = Integer.MIN_VALUE;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!wanted.test(stack)) continue;

            int stackRank = rank != null ? rank.applyAsInt(stack) : 0;
            if (best == -1 || stackRank > bestRank) {
                best = i;
                bestRank = stackRank;
            }
            if (rank == null) break;
        }

        if (best == -1) return false;
        InvUtils.shiftClick().slotId(best);
        wait = CLICK_DELAY;
        return true;
    }

    /**
     * How many of the wanted items the open container still holds.
     */
    private int countInContainer() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == mc.player.inventoryMenu) return -1;

        int count = 0;
        int containerSlots = menu.slots.size() - 36;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (wanted.test(stack)) count += stack.getCount();
        }
        return count;
    }

    // Helpers

    private void reset() {
        failure = null;
        sourceLeft = -1;
        othersLeft = 0;
        startCount = InvUtils.find(wanted).count();
        storage = echestSpot = shulkerSpot = null;
        shulkerSlot = -1;
        shulkerItem = null;
        wait = 0;
    }

    private void go(Step next) {
        step = next;
        stepTicks = 0;
    }

    private boolean timedOut(int ticks) {
        return stepTicks > ticks;
    }

    private Result fail(String reason) {
        if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        failure = reason;
        return Result.Failed;
    }

    /**
     * Something went wrong after the ender chest was placed: remember why, then still put everything away.
     */
    private void cleanUp(String reason) {
        cleanUp(reason, Step.BreakEChest);
    }

    private void cleanUp(String reason, Step next) {
        if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        if (failure == null) failure = reason;
        go(next);
    }

    private String prefix() {
        return failure != null ? failure + " " : "";
    }

    /**
     * Clicks a container open, retrying now and then.
     * @return true once the expected screen is open
     */
    private boolean open(BlockPos pos, Predicate<AbstractContainerMenu> expected) {
        if (expected.test(mc.player.containerMenu)) {
            wait = CLICK_DELAY;
            return true;
        }

        if (stepTicks == 1 || stepTicks % 20 == 0) {
            clickOpen(pos, module.rotates(), true);
        }

        return false;
    }

    /**
     * One click on a container to open it, rotating first if asked. Shared with Container Tweaks.
     * @param swing false skips the client-side swing animation only, the swing packet is sent either way
     */
    static void clickOpen(BlockPos pos, boolean rotate, boolean swing) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        Runnable click = () -> BlockUtils.interact(hit, InteractionHand.MAIN_HAND, swing);

        if (rotate) Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), click);
        else click.run();
    }

    /**
     * Places a block by clicking the top of the block under {@code spot}, so shulkers always face up and have room to open.
     */
    private void placeOnTop(BlockPos spot, Predicate<ItemStack> item) {
        module.moveToHotbar(item);
        FindItemResult result = InvUtils.findInHotbar(item);
        if (!result.found()) return;

        clickPlaceOnTop(spot, result.slot(), module.rotates(), true);
    }

    /**
     * Selects the hotbar slot and clicks the top of the block under {@code spot}. Shared with Container Tweaks.
     */
    static void clickPlaceOnTop(BlockPos spot, int hotbarSlot, boolean rotate, boolean swing) {
        BlockPos below = spot.below();
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false);

        Runnable click = () -> {
            InvUtils.swap(hotbarSlot, false);
            BlockUtils.interact(hit, InteractionHand.MAIN_HAND, swing);
        };

        if (rotate) Rotations.rotate(Rotations.getYaw(hit.getLocation()), Rotations.getPitch(hit.getLocation()), click);
        else click.run();
    }

    private void breakWith(BlockPos pos, Predicate<ItemStack> tool) {
        if (tool != null) {
            module.moveToHotbar(tool);
            FindItemResult result = InvUtils.findInHotbar(tool);
            if (result.found()) InvUtils.swap(result.slot(), false);
        }
        else {
            FindItemResult best = InvUtils.findFastestTool(mc.level.getBlockState(pos));
            if (best.found()) InvUtils.swap(best.slot(), false);
        }

        clickBreak(pos, module.rotates(), true);
    }

    /**
     * One tick of breaking a block with whatever is held, rotating first if asked. Needs a tick-pre event. Shared with
     * Container Tweaks.
     */
    static void clickBreak(BlockPos pos, boolean rotate, boolean swing) {
        if (rotate) Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos));
        BlockUtils.breakBlock(pos, swing);
    }

    /**
     * A free spot right beside the player, on solid ground with air above. Right beside means the drop lands within
     * pickup range when we break it again.
     */
    private BlockPos findSpot(BlockPos exclude) {
        BlockPos feet = mc.player.blockPosition();
        BlockPos fallback = null;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;

                BlockPos pos = feet.offset(dx, 0, dz);
                if (pos.equals(exclude) || !isGoodSpot(pos)) continue;

                // Straight sides are closest, so drops land right next to us
                if (dx == 0 || dz == 0) return pos;
                if (fallback == null) fallback = pos;
            }
        }

        return fallback;
    }

    static boolean isGoodSpot(BlockPos pos) {
        if (!mc.level.getBlockState(pos).isAir() || !mc.level.getBlockState(pos.above()).isAir()) return false;
        if (mc.player.getBoundingBox().intersects(new AABB(pos))) return false;

        return isGoodSupport(pos.below());
    }

    /**
     * Solid to place on, and nothing that would open a screen instead when clicked.
     */
    static boolean isGoodSupport(BlockPos below) {
        BlockState ground = mc.level.getBlockState(below);
        return ground.isCollisionShapeFullBlock(mc.level, below) && ground.getFluidState().isEmpty()
            && mc.level.getBlockEntity(below) == null && !BlockUtils.isClickable(ground.getBlock());
    }

    /**
     * The slot of the shulker holding the most of the wanted items, or -1.
     */
    private int bestShulkerIn(IntFunction<ItemStack> slots, int size) {
        int best = -1, bestCount = 0;

        for (int i = 0; i < size; i++) {
            int count = countInShulker(slots.apply(i));
            if (count > bestCount) {
                best = i;
                bestCount = count;
            }
        }

        return best;
    }

    private int countInShulker(ItemStack stack) {
        if (!Utils.isShulker(stack.getItem())) return 0;

        ItemStack[] contents = new ItemStack[27];
        Utils.getItemsInContainerItem(stack, contents);

        int count = 0;
        for (ItemStack content : contents) {
            if (content != null && wanted.test(content)) count += content.getCount();
        }
        return count;
    }

    static boolean isEChestMenu(AbstractContainerMenu menu) {
        return menu instanceof ChestMenu chest && chest.getRowCount() == 3;
    }

    static boolean isSilkPickaxe(ItemStack stack) {
        return stack.is(ItemTags.PICKAXES) && Utils.hasEnchantment(stack, Enchantments.SILK_TOUCH);
    }

    static int emptySlots() {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) count++;
        }
        return count;
    }
}
