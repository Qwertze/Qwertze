package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.compat.Players;
import com.porter.qwerte.compat.RightClick;
import com.porter.qwerte.modules.AutoTill.RestockMode;
import com.porter.qwerte.utils.AutoHotbar;
import com.porter.qwerte.utils.BaritoneHelper;
import com.porter.qwerte.utils.WorkPattern;
import com.porter.qwerte.utils.WorkPattern.RowDirection;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Covers the ground around you with dirt: places a dirt block on top of every exposed block that isn't already dirt,
 * so the area can be tilled.
 */
public class AutoDirt extends Module implements Restocker.Owner {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRestock = settings.createGroup("Restock");
    private final SettingGroup sgHotbar = settings.createGroup("Hotbar");
    private final SettingGroup sgAutomation = settings.createGroup("Automation");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach")
        .description("Maximum distance from your eyes to the center of the spot to place dirt in.")
        .defaultValue(4.5)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait between placing batches.")
        .defaultValue(1)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many dirt blocks to place per batch.")
        .defaultValue(6)
        .range(1, 8)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Item> dirtItem = sgGeneral.add(new ItemSetting.Builder()
        .name("dirt-item")
        .description("The block to cover the ground with.")
        .defaultValue(Items.DIRT)
        .filter(item -> item == Items.DIRT || item == Items.COARSE_DIRT || item == Items.ROOTED_DIRT || item == Items.GRASS_BLOCK)
        .build()
    );

    private final Setting<Boolean> belowFeet = sgGeneral.add(new BoolSetting.Builder()
        .name("below-feet-only")
        .description("Only covers blocks below your feet, so walls and ledges around you are left alone.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> depth = sgGeneral.add(new IntSetting.Builder()
        .name("depth")
        .description("How many blocks below your feet to look for ground to cover.")
        .defaultValue(1)
        .range(1, 6)
        .sliderRange(1, 6)
        .visible(belowFeet::get)
        .build()
    );

    private final Setting<Boolean> skipSelf = sgGeneral.add(new BoolSetting.Builder()
        .name("skip-own-column")
        .description("Doesn't cover the block you're standing on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Block>> ignore = sgGeneral.add(new BlockListSetting.Builder()
        .name("ignore")
        .description("Blocks that are never covered.")
        .defaultValue(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST, Blocks.BARREL, Blocks.CRAFTING_TABLE, Blocks.FURNACE)
        .build()
    );

    private final Setting<Boolean> skipDirt = sgGeneral.add(new BoolSetting.Builder()
        .name("skip-dirt-blocks")
        .description("Never covers dirt-like blocks: dirt, grass, coarse or rooted dirt, podzol, mycelium, moss, mud, dirt paths and farmland. Turn off to cover them too, for example to pave mycelium with grass blocks, and put the ones to keep in ignore. The block you're placing is never covered either way.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Faces the block you're placing against.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Switches back to your previous slot after placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disableWhenOut = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-when-out")
        .description("Turns the module off when you run out of dirt and can't restock.")
        .defaultValue(true)
        .build()
    );

    // Restock

    private final Setting<Boolean> restock = sgRestock.add(new BoolSetting.Builder()
        .name("restock")
        .description("Restocks dirt when you run low.")
        .defaultValue(false)
        .build()
    );

    private final Setting<RestockMode> restockMode = sgRestock.add(new EnumSetting.Builder<RestockMode>()
        .name("restock-mode")
        .description("Storage: take dirt from a container you pick. Ender Chest: place your ender chest, take dirt from a shulker inside, put everything back and pick the ender chest up with a silk touch pickaxe.")
        .defaultValue(RestockMode.Storage)
        .onChanged(mode -> restockUnavailable = false)
        .visible(restock::get)
        .build()
    );

    private final Setting<Integer> restockAt = sgRestock.add(new IntSetting.Builder()
        .name("restock-at")
        .description("Restock when you're down to this much dirt or less.")
        .defaultValue(0)
        .range(0, 2304)
        .sliderRange(0, 64)
        .visible(restock::get)
        .build()
    );

    // The storages are listed in the module's GUI, this only holds them for saving
    private final Setting<List<String>> storageData = sgRestock.add(new StringListSetting.Builder()
        .name("dirt-storages")
        .description("The containers to take dirt from, managed with pick-storage and the list below the settings.")
        .visible(() -> false)
        .build()
    );

    // Older versions kept a single storage here, it's folded into the list when the settings load
    private final Setting<BlockPos> legacyStorage = sgRestock.add(new BlockPosSetting.Builder()
        .name("dirt-storage")
        .description("Old single dirt storage, moved into dirt-storages.")
        .defaultValue(BlockPos.ZERO)
        .visible(() -> false)
        .build()
    );

    private final Setting<Boolean> pickStorage = sgRestock.add(new BoolSetting.Builder()
        .name("pick-storage")
        .description("Turn on, then right-click a container to add it to the dirt storages, or to remove it if it's already one. Turns itself off after one click. Works while the module is off.")
        .defaultValue(false)
        .visible(this::usingStorage)
        .build()
    );

    private final Setting<Boolean> walkToStorage = sgRestock.add(new BoolSetting.Builder()
        .name("walk-to-storage")
        .description("Uses Baritone to walk to a dirt storage when it's out of reach, then walks back.")
        .defaultValue(false)
        .visible(this::usingStorage)
        .build()
    );

    // Hotbar

    private final Setting<Boolean> autoHotbar = sgHotbar.add(new BoolSetting.Builder()
        .name("auto-hotbar")
        .description("Keeps the dirt in your hotbar: pulls it out of your inventory when it's missing from the hotbar, and tops the hotbar stack up when it runs low. Never drops anything, and never moves anything while a screen is open. When off, dirt is only pulled into the hotbar when it's missing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> dirtSlot = sgHotbar.add(new IntSetting.Builder()
        .name("dirt-slot")
        .description("The hotbar slot (1-9) to keep the dirt in. 0 uses any free slot.")
        .defaultValue(0)
        .range(0, 9)
        .sliderRange(0, 9)
        .visible(autoHotbar::get)
        .build()
    );

    // Automation

    private final Setting<Boolean> autoWalk = sgAutomation.add(new BoolSetting.Builder()
        .name("auto-walk")
        .description("Uses Baritone to walk to more uncovered ground when nothing is left in reach.")
        .defaultValue(false)
        .build()
    );

    private final Setting<WorkPattern> workPattern = sgAutomation.add(new EnumSetting.Builder<WorkPattern>()
        .name("work-pattern")
        .description("How to pick the next ground to cover when nothing is left in reach. Nearest: always the closest uncovered spot. Rows: sweeps back and forth along parallel lanes, like mowing a lawn. Spiral Out: rings from where you enabled it outwards. Spiral In: the outer ring first, working towards the middle.")
        .defaultValue(WorkPattern.Nearest)
        .onChanged(pattern -> resetSweep())
        .visible(autoWalk::get)
        .build()
    );

    private final Setting<RowDirection> rowDirection = sgAutomation.add(new EnumSetting.Builder<RowDirection>()
        .name("row-direction")
        .description("Which way the lanes run. Auto runs them the way you were facing when you enabled the module.")
        .defaultValue(RowDirection.Auto)
        .onChanged(direction -> resetSweep())
        .visible(() -> autoWalk.get() && workPattern.get() == WorkPattern.Rows)
        .build()
    );

    private final Setting<Integer> laneWidth = sgAutomation.add(new IntSetting.Builder()
        .name("lane-width")
        .description("How wide each lane or ring is in blocks. 0 works it out from your reach, so one pass covers everything you can reach.")
        .defaultValue(0)
        .range(0, 32)
        .sliderRange(0, 16)
        .onChanged(width -> resetSweep())
        .visible(() -> autoWalk.get() && (workPattern.get() == WorkPattern.Rows || workPattern.get() == WorkPattern.SpiralOut || workPattern.get() == WorkPattern.SpiralIn))
        .build()
    );

    private final Setting<Integer> searchRadius = sgAutomation.add(new IntSetting.Builder()
        .name("search-radius")
        .description("How far from where you enabled the module to look for ground to cover.")
        .defaultValue(64)
        .range(8, 128)
        .sliderRange(8, 64)
        .visible(autoWalk::get)
        .build()
    );

    private final Setting<Integer> searchHeight = sgAutomation.add(new IntSetting.Builder()
        .name("search-height")
        .description("How many blocks above and below you to search. With below-feet-only on, depth is used below you instead.")
        .defaultValue(1)
        .range(1, 16)
        .sliderRange(1, 8)
        .visible(autoWalk::get)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlights the spots dirt will be placed in.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the highlight.")
        .defaultValue(new SettingColor(110, 75, 40, 50))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the highlight.")
        .defaultValue(new SettingColor(110, 75, 40, 255))
        .visible(render::get)
        .build()
    );

    private static final int MAX_ATTEMPTS = 5;
    private static final int RESTOCK_RETRY_TICKS = 20 * 60;
    private static final int PATH_GRACE_TICKS = 10;
    private static final int PATH_TIMEOUT_TICKS = 20 * 60 * 5;

    // Baritone is optional, BaritoneHelper must only be touched when it's installed
    private static final boolean BARITONE = classExists("baritone.api.BaritoneAPI");

    private static final int STUCK_TICKS = 20 * 5;

    private enum State { Working, Exploring, GoingToStorage, Restocking, Returning }

    private final List<BlockPos> targets = new ArrayList<>();
    private final Map<BlockPos, Integer> attempts = new HashMap<>();
    private final Set<BlockPos> failed = new HashSet<>();
    private int timer;
    private boolean warnedInventory;

    private final Restocker restocker = new Restocker(this, this::isDirtItem, "dirt");

    private final AutoHotbar.Need dirtNeed = new AutoHotbar.Need("dirt", this::isDirtItem, dirtSlot::get, true, () -> true);
    private final AutoHotbar hotbar = new AutoHotbar(autoHotbar::get, stack -> false, message -> warning("%s", message), message -> info("%s", message), List.of(dirtNeed));
    private final StorageList storages = new StorageList(storageData, () -> restockUnavailable = false);
    private State state = State.Working;
    private boolean restockUnavailable;
    private int restockRetryTicks;
    private boolean usedBaritone;
    private BlockPos returnPos;
    private int slotBefore = -1;
    private int stateTicks;
    private int pathRetries;

    // Auto-walk
    private BlockPos home;
    private final WorkPattern.Sweep sweep = new WorkPattern.Sweep();
    private BlockPos pathTarget;
    private int pathRange;
    private int exploreCooldown;
    private final Set<BlockPos> unreachable = new HashSet<>();
    private int goalTicks;
    private int stuckTicks;
    private Vec3 lastPos = Vec3.ZERO;

    public AutoDirt() {
        super(QwerteAddon.CATEGORY, "auto-dirt", "Covers the ground around you with dirt, never stacking dirt on dirt.");

        // Always listening so the storage can be picked while the module is off
        RightClick.listen(this::onRightClick);
    }

    /**
     * Adds or removes a dirt storage while pick-storage is on.
     * @return true when picking, so the click doesn't open the container
     */
    private boolean onRightClick() {
        if (!pickStorage.get()) return false;

        // The mouse is only grabbed in game, never while a screen is open
        if (mc.player == null || mc.level == null || !mc.mouseHandler.isMouseGrabbed()) return false;

        Vec3 eyes = mc.player.getEyePosition();
        Vec3 end = eyes.add(mc.player.getViewVector(1f).scale(64));
        BlockHitResult hit = mc.level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));

        if (hit.getType() != HitResult.Type.BLOCK || !(mc.level.getBlockEntity(hit.getBlockPos()) instanceof Container)) {
            warning("That isn't a container, try again.");
            return true;
        }

        storages.migrate(legacyStorage);
        BlockPos pos = hit.getBlockPos().immutable();
        boolean added = storages.toggle(pos);
        pickStorage.set(false);
        info("%s storage at %s (%d storages).", added ? "Added" : "Removed", pos.toShortString(), storages.size());
        return true;
    }

    @Override
    public Module fromTag(CompoundTag tag) {
        Module module = super.fromTag(tag);
        storages.migrate(legacyStorage);
        return module;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        storages.migrate(legacyStorage);
        return storages.widget(theme, "Dirt storages", this::usingStorage);
    }

    @Override
    public void onActivate() {
        hotbar.reset();
        targets.clear();
        attempts.clear();
        failed.clear();
        timer = 0;
        warnedInventory = false;

        state = State.Working;
        restockUnavailable = false;
        restockRetryTicks = 0;
        storages.migrate(legacyStorage);
        storages.resetSession();
        returnPos = null;
        slotBefore = -1;

        home = mc.player != null ? mc.player.blockPosition() : null;
        resetSweep();
        pathTarget = null;
        exploreCooldown = 0;
        unreachable.clear();
        goalTicks = 0;
        stuckTicks = 0;
    }

    @Override
    public void onDeactivate() {
        if (state == State.Restocking) restocker.cancel();

        // Only touch Baritone if we actually drove it this session
        if (usedBaritone) {
            if (state != State.Working) BaritoneHelper.stop();
            BaritoneHelper.unprotect();
            usedBaritone = false;
        }

        finishRestock();
        targets.clear();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (state == State.Restocking) restocker.cancel();

        if (usedBaritone) {
            BaritoneHelper.stop();
            BaritoneHelper.unprotect();
            usedBaritone = false;
        }

        // The world is gone, so is everything we knew about it. onActivate resets the rest.
        slotBefore = -1;
        onActivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        targets.clear();
        storages.tick();
        busy = false;

        // onActivate can run before the player exists
        if (home == null) home = mc.player.blockPosition();

        // A failed restock only pauses restocking, try again after a while
        if (restockUnavailable && restockRetryTicks > 0 && --restockRetryTicks == 0) restockUnavailable = false;

        // Restocking, and walking to and from the storage, take over the whole tick
        if (state != State.Working) {
            busy = true;
            tickRestock();
            return;
        }

        // The stash sorter is in the middle of a job: no new action starts until it's done
        if (sorterBusy()) return;

        if (autoWalk.get() && !BARITONE) {
            error("Baritone isn't installed, turning off auto-walk.");
            autoWalk.set(false);
        }

        // Low on dirt comes first, then we cover the ground with a full inventory
        if (canRestock() && InvUtils.find(this::isDirtItem).count() <= restockAt.get()) {
            busy = true;
            startRestock();
            return;
        }

        // Keep the dirt in the hotbar. A move takes the tick, so it never lands in the same tick as a placement.
        if (hotbar.tick()) return;

        // Places that keep failing are forgotten once we're out of reach of them, so they get another go later
        double forgetSq = (reach.get() + 2) * (reach.get() + 2);
        Vec3 eyes = mc.player.getEyePosition();
        failed.removeIf(pos -> eyes.distanceToSqr(Vec3.atCenterOf(pos)) > forgetSq);
        attempts.keySet().removeIf(pos -> eyes.distanceToSqr(Vec3.atCenterOf(pos)) > forgetSq);

        findTargets(eyes);
        if (targets.isEmpty()) {
            // Nothing left in reach, go find more ground to cover (only worth it if we've got dirt to place)
            if (walking() && InvUtils.find(this::isDirtItem).found()) {
                busy = true;
                startExplore(eyes);
            }
            return;
        }
        busy = true;

        FindItemResult dirt = InvUtils.findInHotbar(this::isDirtItem);
        if (!dirt.found()) {
            FindItemResult anywhere = InvUtils.find(this::isDirtItem);
            if (anywhere.found()) {
                if (autoHotbar.get()) hotbar.ensure(dirtNeed);
                // Pull a stack into a free hotbar slot, or the selected one if the hotbar is full
                else InvUtils.move().from(anywhere.slot()).toHotbar(freeHotbarSlot());
                return;
            }

            // Restocking is tried first, above, so here we've truly got none left
            if (disableWhenOut.get()) {
                error("Out of %s, disabling.", dirtItem.get().getName().getString());
                toggle();
            }
            else if (!warnedInventory) {
                warning("Out of %s.", dirtItem.get().getName().getString());
                warnedInventory = true;
            }
            return;
        }
        warnedInventory = false;

        if (timer < delay.get()) {
            timer++;
            return;
        }

        int count = Math.min(blocksPerTick.get(), targets.size());
        for (int i = 0; i < count; i++) {
            BlockPos pos = targets.get(i);

            if (attempts.merge(pos, 1, Integer::sum) > MAX_ATTEMPTS) {
                failed.add(pos);
                // failed is pruned once we're away, unreachable lasts the session so auto-walk never goes back for it
                unreachable.add(pos);
                continue;
            }

            // Meteor's placement: picks a face to click, rotates to it, swaps to the dirt and swings
            BlockUtils.place(pos, dirt, rotate.get(), 50, true, true, swapBack.get());
        }

        timer = 0;
    }

    // Whether the last tick did something: walking, restocking or placing, as opposed to waiting for ground to cover
    private boolean busy;

    /**
     * True only while it's actually walking, placing or restocking, not while it's on and waiting for something to do.
     */
    public boolean working() {
        return isActive() && busy;
    }

    private static boolean sorterBusy() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        return sorter != null && sorter.busyWithJob();
    }

    private void findTargets(Vec3 eyes) {
        double reachSq = reach.get() * reach.get();
        int r = (int) Math.ceil(reach.get());
        BlockPos feet = mc.player.blockPosition();

        int minY = belowFeet.get() ? feet.getY() - depth.get() : feet.getY() - r;
        int maxY = belowFeet.get() ? feet.getY() : feet.getY() + r;

        // pos is the empty spot the dirt goes in, the block it covers is the one below it
        for (BlockPos p : BlockPos.betweenClosed(feet.getX() - r, minY, feet.getZ() - r, feet.getX() + r, maxY, feet.getZ() + r)) {
            if (eyes.distanceToSqr(Vec3.atCenterOf(p)) > reachSq) continue;

            BlockPos pos = p.immutable();
            if (failed.contains(pos)) continue;
            if (skipSelf.get() && pos.getX() == feet.getX() && pos.getZ() == feet.getZ()) continue;
            if (!isTarget(pos)) continue;

            targets.add(pos);
        }

        Comparator<BlockPos> nearestFirst = Comparator.comparingDouble(pos -> eyes.distanceToSqr(Vec3.atCenterOf(pos)));

        // With a work pattern the strip in reach is finished in the sweep's order, the nearest only breaks ties
        if (walking() && workPattern.get() != WorkPattern.Nearest) {
            prepareSweep();
            targets.sort(Comparator.comparingLong((BlockPos pos) -> sweep.key(pos.getX(), pos.getZ(), Long.MAX_VALUE)).thenComparing(nearestFirst));
        }
        else targets.sort(nearestFirst);
    }

    /**
     * Starts the sweep over, facing the way the player is now.
     */
    private void resetSweep() {
        sweep.reset(mc.player != null ? mc.player.getYRot() : Float.NaN);
    }

    private void prepareSweep() {
        if (home == null) home = mc.player.blockPosition();
        sweep.prepare(workPattern.get(), rowDirection.get(), laneWidth.get(), reach.get(), home.getX(), home.getZ(), searchRadius.get(), mc.player.getYRot());
    }

    /**
     * An empty spot sitting on a solid block that isn't dirt.
     */
    private boolean isTarget(BlockPos pos) {
        BlockState space = mc.level.getBlockState(pos);
        if (!space.canBeReplaced() || !space.getFluidState().isEmpty()) return false;

        BlockPos belowPos = pos.below();
        BlockState below = mc.level.getBlockState(belowPos);
        // Never the block we're placing, so it never stacks on itself
        if (below.is(dirtItem.get() instanceof BlockItem blockItem ? blockItem.getBlock() : Blocks.DIRT)) return false;
        if (skipDirt.get() && isDirt(below)) return false;
        if (ignore.get().contains(below.getBlock())) return false;
        if (!below.isFaceSturdy(mc.level, belowPos, Direction.UP)) return false;

        // Also checks for entities, including us, in the way
        return BlockUtils.canPlace(pos, true);
    }

    private static boolean isDirt(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND);
    }

    private boolean isDirtItem(ItemStack stack) {
        return stack.is(dirtItem.get());
    }

    // Restocking

    private boolean usingStorage() {
        return restock.get() && restockMode.get() == RestockMode.Storage;
    }

    private boolean canRestock() {
        return restock.get() && !restockUnavailable;
    }

    private void startRestock() {
        if (restockMode.get() == RestockMode.EnderChest) {
            String problem = restocker.startEnderChest();
            if (problem != null) {
                restockFailed(problem);
                return;
            }

            startRestockingHere();
            return;
        }

        storages.beginAttempt();
        if (storages.isEmpty()) {
            blockRestock("No dirt storage set, use pick-storage.");
            return;
        }

        if (!tryNextStorage()) noStorageLeft();
    }

    /**
     * Starts on the next storage that's worth trying, in reach first and then nearest.
     * @return false when there's none left to try
     */
    private boolean tryNextStorage() {
        BlockPos storage = storages.next(mc.player.getEyePosition(), this::inReach, walkToStorage.get() && BARITONE);
        if (storage == null) return false;

        if (inReach(storage)) {
            restocker.startManual(storage);
            startRestockingHere();
            return true;
        }

        // Several storages in one go still head back to where we started from
        if (returnPos == null) returnPos = mc.player.blockPosition();
        if (slotBefore < 0) slotBefore = Players.selectedSlot();
        walkTo(storage, 2, State.GoingToStorage);
        return true;
    }

    /**
     * No storage is left to try: turns restocking off if they're all empty, pauses it otherwise.
     */
    private void noStorageLeft() {
        if (storages.allEmpty()) {
            error("All %d dirt storages are empty. Turning off restock.", storages.size());
            restock.set(false);
            storages.forgetEmpty();
        }
        else if (storages.skippedOutOfReach() && !walkToStorage.get()) {
            blockRestock("The dirt storages are out of reach, turn on walk-to-storage to walk there.");
        }
        else if (storages.skippedOutOfReach()) {
            blockRestock("Baritone isn't installed, so I can't walk to the dirt storages.");
        }
        else blockRestock("Couldn't use any of the dirt storages.");
    }

    /**
     * A storage failed and there's none left to try. If an earlier one got us enough dirt, that still counts.
     */
    private void noStorageFinished(String reason) {
        int count = InvUtils.find(this::isDirtItem).count();

        if (storages.allEmpty()) noStorageLeft();
        else if (count > restockAt.get()) info("Restocked dirt, you now have %d.", count);
        else blockRestock("%s", reason);
    }

    private void startRestockingHere() {
        // Restocking swaps items around, so put back what we were holding afterwards
        if (slotBefore < 0) slotBefore = Players.selectedSlot();
        state = State.Restocking;
        stateTicks = 0;
    }

    private void tickRestock() {
        stateTicks++;

        switch (state) {
            case Restocking -> {
                switch (restocker.tick()) {
                    case Busy -> {}
                    case Done -> {
                        int count = InvUtils.find(this::isDirtItem).count();

                        if (restockMode.get() == RestockMode.Storage) {
                            if (restocker.sourceEmpty()) storages.markEmpty(storages.current());

                            // Still low, so carry on with the next storage before heading back
                            if (count <= restockAt.get() && tryNextStorage()) return;

                            if (storages.allEmpty()) {
                                warning("Restocked to %d dirt, but that emptied all %d dirt storages. Turning off restock.", count, storages.size());
                                restock.set(false);
                                storages.forgetEmpty();
                            }
                            // Restocking goes first, so storages that have run dry would otherwise send us straight back
                            else if (count <= restockAt.get()) blockRestock("Restocked, but you only have %d dirt. Are the storages running low?", count);
                            else info("Restocked dirt, you now have %d.", count);
                        }
                        else {
                            if (restocker.sourceEmpty()) {
                                warning("Restocked to %d dirt, but that emptied your %s. Turning off restock.", count, sourceName());
                                restock.set(false);
                            }
                            else if (count <= restockAt.get()) blockRestock("Restocked, but you only have %d dirt. Is the storage running low?", count);
                            else info("Restocked dirt, you now have %d.", count);
                        }

                        goBack();
                    }
                    case Failed -> {
                        if (restockMode.get() == RestockMode.Storage) {
                            if (restocker.sourceEmpty()) storages.markEmpty(storages.current());
                            else storages.markBad(storages.current());

                            if (tryNextStorage()) return;
                            noStorageFinished(restocker.failure());
                        }
                        else restockFailed(restocker.failure());
                        goBack();
                    }
                }
            }
            case GoingToStorage -> {
                if (stillWalking()) return;

                BlockPos storage = storages.current();
                if (storage != null && inReach(storage)) {
                    restocker.startManual(storage);
                    state = State.Restocking;
                    stateTicks = 0;
                }
                else {
                    storages.markBad(storage);
                    if (tryNextStorage()) return;
                    noStorageFinished(storage == null ? "Couldn't reach the dirt storage." : "Couldn't reach the dirt storage at " + storage.toShortString() + ".");
                    goBack();
                }
            }
            case Returning -> {
                if (stillWalking()) return;
                finishRestock();
            }
            case Exploring -> {
                if (!walking()) {
                    if (BARITONE) BaritoneHelper.stop();
                    finishRestock();
                    return;
                }

                // Close enough to start working, no need to walk the last step
                if (stateTicks > PATH_GRACE_TICKS && inReach(pathTarget)) BaritoneHelper.stop();
                else if (stillWalking()) return;

                if (!inReach(pathTarget)) unreachable.add(pathTarget);
                finishRestock();
            }
            default -> finishRestock();
        }
    }

    /**
     * A storage seen to be empty turns restocking off, anything else only pauses it for a while.
     */
    private void restockFailed(String reason) {
        if (restocker.sourceEmpty()) {
            error("%s Turning off restock.", reason);
            restock.set(false);
        }
        else blockRestock(reason);
    }

    /**
     * Pauses restocking for a while after it fails, it's tried again after {@link #RESTOCK_RETRY_TICKS}.
     */
    private void blockRestock(String message, Object... args) {
        warning(message, args);
        restockUnavailable = true;
        restockRetryTicks = RESTOCK_RETRY_TICKS;
    }

    private String sourceName() {
        return restockMode.get() == RestockMode.EnderChest ? "ender chest's dirt shulkers" : "dirt storage";
    }

    private void goBack() {
        if (returnPos != null && BARITONE) walkTo(returnPos, 1, State.Returning);
        else finishRestock();
        returnPos = null;
    }

    /**
     * Back to placing, holding whatever we held before restocking.
     */
    private void finishRestock() {
        if (state != State.Working && slotBefore >= 0 && mc.player != null
            && Players.selectedSlot() != slotBefore) {
            InvUtils.swap(slotBefore, false);
        }

        slotBefore = -1;
        state = State.Working;
    }

    private void walkTo(BlockPos pos, int range, State next) {
        // Keep Baritone off farmland and out of water on the way
        BaritoneHelper.protect(List.of(Blocks.FARMLAND));
        usedBaritone = true;

        // Baritone picks its own tools and blocks while it walks, remember what we were holding
        if (state == State.Working && slotBefore < 0) slotBefore = Players.selectedSlot();

        pathTarget = pos;
        pathRange = range;
        BaritoneHelper.moveNear(pos, range);

        state = next;
        stateTicks = 0;
        goalTicks = 0;
        stuckTicks = 0;
        pathRetries = 0;
        lastPos = mc.player.position();
    }

    /**
     * Waits for Baritone to finish the current trip, nudging it if it stalls.
     * @return true while still on the way
     */
    private boolean stillWalking() {
        if (stateTicks > PATH_TIMEOUT_TICKS) {
            BaritoneHelper.stop();
            return false;
        }

        // Baritone needs a moment to start pathing after a goal is set
        if (goalTicks++ < PATH_GRACE_TICKS) return true;

        Vec3 pos = mc.player.position();
        if (pos.distanceToSqr(lastPos) > 0.25) {
            lastPos = pos;
            stuckTicks = 0;
        }
        else if (!BaritoneHelper.isCalculating()) stuckTicks++;

        // Baritone sometimes stalls, or quietly gives up short of the goal. Give it a few nudges before calling it a failure.
        boolean moving = BaritoneHelper.isMoving();
        if ((moving && stuckTicks > STUCK_TICKS) || (!moving && !atGoal())) {
            if (pathRetries++ < 3) {
                BaritoneHelper.moveNear(pathTarget, pathRange);
                goalTicks = 0;
                stuckTicks = 0;
                return true;
            }

            if (moving) BaritoneHelper.stop();
            return false;
        }

        return moving;
    }

    private boolean atGoal() {
        // Being in reach of the storage or the spot to cover is as good as arriving
        if ((state == State.GoingToStorage || state == State.Exploring) && inReach(pathTarget)) return true;

        BlockPos feet = mc.player.blockPosition();
        int dx = feet.getX() - pathTarget.getX();
        int dy = feet.getY() - pathTarget.getY();
        int dz = feet.getZ() - pathTarget.getZ();
        return dx * dx + dy * dy + dz * dz <= pathRange * pathRange;
    }

    // Exploring

    private boolean walking() {
        return autoWalk.get() && BARITONE;
    }

    /**
     * How close Baritone needs to get to a spot before it's comfortably in reach.
     */
    private int exploreRange() {
        return (int) Math.max(1, Math.floor(reach.get() - 1.5));
    }

    private void startExplore(Vec3 eyes) {
        if (exploreCooldown > 0) {
            exploreCooldown--;
            return;
        }

        BlockPos target = findGround(eyes);
        if (target == null) {
            info("No more ground to cover within %d blocks, waiting.", searchRadius.get());
            exploreCooldown = 200;
            return;
        }

        walkTo(target, exploreRange(), State.Exploring);
    }

    /**
     * The next spot to cover in the search area around home that's out of reach and hasn't failed us: the nearest, or
     * the first one in the work pattern's order (nearest breaking ties).
     */
    private BlockPos findGround(Vec3 eyes) {
        if (home == null) home = mc.player.blockPosition();

        int radius = searchRadius.get();
        BlockPos feet = mc.player.blockPosition();
        int minY = belowFeet.get() ? feet.getY() - depth.get() : feet.getY() - searchHeight.get();
        int maxY = belowFeet.get() ? feet.getY() : feet.getY() + searchHeight.get();
        double reachSq = reach.get() * reach.get();

        prepareSweep();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        long bestKey = Long.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Rows are laid over the box the work sits in, so the first scan of a run only measures that box
        for (int pass = sweep.needsBounds() ? 0 : 1; pass < 2; pass++) {
            boolean measuring = pass == 0;
            if (measuring) sweep.beginBounds();
            boolean patterned = sweep.active();

            // Column by column: unloaded chunks are skipped, and the distance is worked out without allocating anything.
            // The pattern key is the same for a whole column, only equal keys fall back to the distance.
            for (int x = home.getX() - radius; x <= home.getX() + radius; x++) {
                double dx = x + 0.5 - eyes.x;
                double dx2 = dx * dx;
                if (!patterned && !measuring && dx2 >= bestDist) continue;

                for (int z = home.getZ() - radius; z <= home.getZ() + radius; z++) {
                    double dz = z + 0.5 - eyes.z;
                    double dxz = dx2 + dz * dz;
                    if (!mc.level.hasChunk(x >> 4, z >> 4)) continue;

                    long key = sweep.key(x, z, bestKey);
                    if (key > bestKey || (key == bestKey && dxz >= bestDist)) continue;

                    for (int y = minY; y <= maxY; y++) {
                        double dy = y + 0.5 - eyes.y;
                        double dist = dxz + dy * dy;
                        if (dist <= reachSq || (key == bestKey && dist >= bestDist)) continue;

                        pos.set(x, y, z);
                        if (failed.contains(pos) || unreachable.contains(pos)) continue;
                        if (!isTarget(pos)) continue;

                        if (measuring) {
                            sweep.include(x, z);
                            break;
                        }

                        best = pos.immutable();
                        bestDist = dist;
                        bestKey = key;
                    }
                }
            }

            if (measuring) sweep.endBounds(feet.getX(), feet.getZ());
        }

        if (best != null) sweep.picked(bestKey);
        return best;
    }

    private boolean inReach(BlockPos pos) {
        return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= reach.get() * reach.get();
    }

    @Override
    public boolean rotates() {
        return rotate.get();
    }

    @Override
    public void moveToHotbar(Predicate<ItemStack> predicate) {
        if (InvUtils.findInHotbar(predicate).found()) return;

        FindItemResult result = InvUtils.find(predicate);
        if (!result.found() || result.isHotbar()) return;

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                InvUtils.move().from(result.slot()).toHotbar(i);
                return;
            }
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, AutoDirt.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private int freeHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        }
        return Players.selectedSlot();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        for (BlockPos pos : targets) {
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
}
