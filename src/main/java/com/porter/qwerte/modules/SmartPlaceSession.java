package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.compat.Players;
import com.porter.qwerte.tweaks.ReachSpots;
import com.porter.qwerte.tweaks.SmartPlaceFlow;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Act;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Kind;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Level;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Mining;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Move;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Obs;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Plan;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Pre;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Step;
import com.porter.qwerte.utils.AutoHotbar;
import com.porter.qwerte.utils.BaritoneHelper;
import com.porter.qwerte.utils.PlaceSearch;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The world side of "place a shulker box or ender chest, open it, pick it up again": runs a {@link SmartPlaceFlow}, one
 * thing per tick, and cleans up after it. Moved out of Container Tweaks so that other modules can use it; the host
 * supplies the settings and decides what happens with messages and left-behind items.
 *
 * <p>Only one session runs at a time across all hosts: check {@link #busy()} before starting one ({@link #start} and
 * {@link #startFromInventory} also refuse while one runs). {@code busy()} is global, and a session its host stopped
 * ticking (the host was switched off without calling {@link #shutdown()}) stops counting after {@link #STALE_TICKS}
 * game ticks, so it can never block the other host for ever.
 */
final class SmartPlaceSession {
    /** What a session needs from the module that runs it. */
    interface Host {
        /** Prefix for log lines. */
        String tag();
        boolean rotate();
        boolean swing();
        /** Ticks between steps. */
        int actionDelay();
        /** Seconds to get back in reach and to pick the item up. */
        int pickupTimeout();
        /** Whether Baritone may be used to walk (on top of it being installed). */
        boolean walk();
        boolean safeWalk();
        SmartPlaceFlow.NoSilk noSilk();
        /** True if the host (feature switched off, cancel key) wants a running flow of this kind stopped. */
        boolean cancelled(Kind kind);
        void say(Level level, String message);
        /** An item was left in the world: remember it. */
        void addOrphan(Item item, BlockPos at, String dimension);
        /** True if this cell must not be used as the place spot (the printer's target cells). */
        boolean excluded(int x, int y, int z);
        /**
         * True if the host already confined Baritone, so the session neither refuses to walk nor unconfines. The session
         * still sets its own goal when it walks, which REPLACES the host's goal: the host must stop walking itself while
         * {@link #active()} is true, and set its goal again afterwards.
         */
        boolean hostOwnsConfinement();
        /**
         * Called every tick while the session's container (the one its own open click made) is open. Return false while
         * the host still has work in it (taking items, one thing per call), true when it is done: the SESSION then closes
         * the screen after {@link #actionDelay()} ticks, so the host never closes it. A host that returns true is not called
         * again for that screen, and the flow is not ticked while the close is waited for, which is at most one action delay.
         * Container Tweaks returns false: the user closes the screen.
         */
        boolean whileOwnedOpen(AbstractContainerMenu menu);
        /** The flow is over (the session may still be tidying up for a few ticks: wait for {@link #active()} to be false). */
        void onFinished(Result result);
    }

    /** How a flow ended. */
    enum Result {
        /** Placed, opened and picked up again. */
        DONE,
        /** Stopped with nothing out in the world. */
        ABORTED,
        /** The item is out in the world (recorded through {@link Host#addOrphan}). */
        ORPHANED
    }

    static final boolean BARITONE = classExists("baritone.api.BaritoneAPI");
    private static final int LATE_WATCH = 60;
    /** Game ticks without a {@link #tick()} after which a session no longer makes {@link #busy()} true. */
    static final int STALE_TICKS = 400;
    private static final List<SmartPlaceSession> ALL = new CopyOnWriteArrayList<>();

    /** True while any session is running or still cleaning up (a session that was abandoned by its host excepted). Global. */
    static boolean busy() {
        for (SmartPlaceSession s : ALL) if (s.active() && !s.stale()) return true;
        return false;
    }

    private record Swap(int src, int hotbar, ItemStack displaced) {}

    private final Host host;

    // The running flow
    private SmartPlaceFlow flow;
    private Kind kind;
    private Item item;
    private Block target;
    private Mining mining;
    private BlockPos spot;
    private String where, what, dimension;
    private Object level;
    private int hotbarSlot = -1, prevSelected = -1;
    private Swap swap, toolSwap;
    private AbstractContainerMenu ownedMenu;
    private Object goal;
    private boolean usedProtect, usedConfine, hazardWarned;
    private BlockPos dropPos;
    private AbstractContainerMenu startMenu;
    private boolean openClicked;
    private int movedHotbar = -1;
    private boolean ownedDone;
    private int closeWait;
    private long lastSeen;

    // Late block after a place timeout
    private BlockPos lateSpot;
    private Block lateTarget;
    private Item lateItem;
    private String lateDimension, lateWhere, lateWhat;
    private int lateTicks;
    private Object lateLevel;

    // Spread-out cleanup, one step per tick
    private boolean cleaning, leaving;
    private int cleanStep, cleanWait;

    SmartPlaceSession(Host host) {
        this.host = host;
        ALL.add(this);
    }

    /** A flow is running or its cleanup has steps left. */
    boolean active() {
        return flow != null || cleaning;
    }

    /** Nobody has ticked this session for {@link #STALE_TICKS} game ticks (a paused game does not count). */
    private boolean stale() {
        return mc.level != null && Math.abs(mc.level.getGameTime() - lastSeen) > STALE_TICKS;
    }

    private void seen() {
        if (mc.level != null) lastSeen = mc.level.getGameTime();
    }

    /** Ends the session for good: everything is put back at once and it no longer takes part in {@link #busy()}. */
    void close() {
        shutdown();
        ALL.remove(this);
    }

    // Starting

    /** Starts from a slot of an open menu (the right-click). Returns false if it did not start (and said why). */
    boolean start(Kind wanted, Slot slot, int slotId, ItemStack stack, AbstractContainerMenu menu) {
        // Nothing may click before these: one session at a time, and a world to act in
        if (active() || busy() || mc.player == null || mc.level == null || mc.gameMode == null) return false;
        String name = wanted == Kind.ECHEST ? "ender chest" : "shulker box";
        boolean inInventory = slot.container == mc.player.getInventory();
        int containerSlot = slot.getContainerSlot();
        if (inInventory && (containerSlot < 0 || containerSlot > 35)) {
            host.say(Level.ERROR, "Move the " + name + " into your main inventory or hotbar first.");
            return false;
        }
        if (!slot.mayPickup(mc.player)) return false;

        // 1. Everything that can say no, before anything moves
        Vec3 eye = mc.player.getEyePosition();
        double reach = mc.player.blockInteractionRange();
        ReachSpots.Result found = ReachSpots.find(eye.x, eye.y, eye.z,
            new PlaceSearch.Pos(mc.player.blockPosition().getX(), mc.player.blockPosition().getY(), mc.player.blockPosition().getZ()), reach, new WorldView());

        boolean hasSilk = wanted == Kind.ECHEST && InvUtils.find(Restocker::isSilkPickaxe).found();
        boolean hasPick = wanted == Kind.ECHEST && InvUtils.find(s -> s.is(ItemTags.PICKAXES)).found();
        Plan plan = SmartPlaceFlow.plan(new Pre(wanted, host.noSilk(), found.spot() != null, found.why(), hasSilk, hasPick,
            inInventory && containerSlot < 9, inInventory, hotbarIndex(true), hotbarIndex(false)));
        if (!plan.ok()) {
            host.say(Level.ERROR, plan.error());
            return false;
        }
        if (plan.warning() != null) host.say(Level.WARN, plan.warning());

        // 2. The hotbar, with the screen still open
        prevSelected = Players.selectedSlot();
        swap = null;
        movedHotbar = -1;
        if (plan.move() == Move.NONE) hotbarSlot = containerSlot;
        else {
            hotbarSlot = plan.hotbar();
            ItemStack displaced = mc.player.getInventory().getItem(hotbarSlot).copy();
            swapClick(menu, slotId, hotbarSlot);
            if (plan.move() == Move.EMPTY) movedHotbar = hotbarSlot;
            if (plan.move() == Move.SWAP) swap = new Swap(containerSlot, hotbarSlot, displaced);
        }

        // 3. Off it goes
        kind = wanted;
        item = stack.getItem();
        target = wanted == Kind.ECHEST ? Blocks.ENDER_CHEST : ((BlockItem) item).getBlock();
        mining = plan.mining();
        PlaceSearch.Pos at = found.spot();
        spot = new BlockPos(at.x(), at.y(), at.z());
        what = name;
        where = spot.getX() + ", " + spot.getY() + ", " + spot.getZ();
        level = mc.level;
        dimension = dimension();
        ownedMenu = null;
        goal = null;
        usedProtect = false;
        usedConfine = false;
        hazardWarned = false;
        dropPos = null;
        toolSwap = null;
        startMenu = menu;
        openClicked = false;
        ownedDone = false;
        closeWait = 0;
        cleaning = false;
        seen();
        flow = new SmartPlaceFlow(new SmartPlaceFlow.Config(mining, host.actionDelay(), host.pickupTimeout() * 20,
            BARITONE && host.walk(), name, where));
        QwerteAddon.LOG.info("{}: {} at {} ({}, {})", host.tag(), name, where, plan.move(), mining);
        return true;
    }

    /**
     * Starts from an inventory slot (0 to 35) without any screen open: uses the player's own inventory menu. Refuses (false,
     * and says why when it is something the player can fix) when a session runs, a screen is open, something is on the
     * cursor, the player is in creative, the slot does not hold what {@code wanted} is, or (without
     * {@link Host#hostOwnsConfinement()}) Baritone is confined or busy while the host walks.
     */
    boolean startFromInventory(Kind wanted, int invIndex) {
        if (active() || busy() || mc.player == null || mc.level == null || mc.gameMode == null) return false;
        if (mc.player.hasInfiniteMaterials()) return false;
        AbstractContainerMenu menu = mc.player.inventoryMenu;
        if (mc.screen != null || mc.player.containerMenu != menu) {
            host.say(Level.ERROR, "Close the open screen first.");
            return false;
        }
        if (!menu.getCarried().isEmpty()) {
            host.say(Level.ERROR, "Put down the item on your cursor first.");
            return false;
        }
        if (invIndex < 0 || invIndex > 35) return false;
        int id = inventorySlotId(menu, invIndex);
        if (id < 0) return false;
        Slot slot = menu.slots.get(id);
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) return false;
        if (wanted == Kind.SHULKER ? !Utils.isShulker(stack.getItem()) : !stack.is(Items.ENDER_CHEST)) return false;
        if (BARITONE && host.walk() && !host.hostOwnsConfinement()) {
            if (BaritoneHelper.confined()) {
                host.say(Level.ERROR, "Another module has Baritone confined. Not walking.");
                return false;
            }
            if (BaritoneHelper.isActive() || BaritoneHelper.controlledByOther(null)) {
                host.say(Level.ERROR, "Baritone is busy with something else.");
                return false;
            }
        }
        return start(wanted, slot, id, stack, menu);
    }

    // Ticking

    /** The flow part of the tick (and the late block watch). */
    void tick() {
        if (mc.player == null || mc.level == null) return;
        seen();
        lateWatch();
        if (flow == null) {
            if (cleaning) cleanTick();
            return;
        }

        Obs obs = observe(false);
        if (obs.ownedOpen()) {
            if (ownedDone) {
                // The host is done with the screen: closed after one action delay
                if (closeWait-- <= 0) mc.player.closeContainer();
                return;
            }
            if (host.whileOwnedOpen(mc.player.containerMenu)) {
                ownedDone = true;
                closeWait = host.actionDelay();
                return;
            }
        }
        handle(flow.tick(obs));
    }

    /** The module was turned off: stop and put everything back at once. */
    void shutdown() {
        if (flow != null) {
            handle(flow.tick(observe(true)));
            // Whatever the flow was, nothing of ours is left running
            if (flow != null) cleanup();
        }
        finishCleanupNow();
        lateSpot = null;
    }

    /** The world is being left. */
    void gameLeft() {
        leaving = true;
        if (flow != null) {
            handle(flow.tick(observeWorldChange()));
            if (flow != null) cleanup();
        }
        finishCleanupNow();
        leaving = false;
        lateSpot = null;
    }

    private Obs observe(boolean forceCancel) {
        if (mc.player == null || mc.level == null) return observeWorldChange();

        boolean changed = mc.level != level;
        AbstractContainerMenu menu = mc.player.containerMenu;
        boolean menuOpen = mc.screen != null || menu != mc.player.inventoryMenu;
        boolean typeMatches = kind == Kind.SHULKER ? menu instanceof ShulkerBoxMenu : Restocker.isEChestMenu(menu);
        // Only the menu our open click made: never the one the right-click came from
        if (SmartPlaceFlow.ownsMenu(ownedMenu != null, openClicked, typeMatches, menu == startMenu)) ownedMenu = menu;
        boolean ownedOpen = ownedMenu != null && menu == ownedMenu;
        boolean expected = ownedOpen;

        BlockState state = mc.level.getBlockState(spot);
        boolean ours = state.is(target);
        boolean cancelled = forceCancel || host.cancelled(kind);

        boolean inReach = inReachNow();

        boolean dropNearby = false, dropInRange = false;
        if (!ours) {
            AABB pickup = mc.player.getBoundingBox().inflate(1.0, 0.5, 1.0);
            double best = Double.MAX_VALUE;
            for (ItemEntity drop : mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(spot).inflate(3), d -> d.getItem().is(item))) {
                dropNearby = true;
                if (pickup.intersects(drop.getBoundingBox())) dropInRange = true;
                double d = drop.distanceToSqr(mc.player);
                if (d < best) {
                    best = d;
                    dropPos = drop.blockPosition();
                }
            }
        }

        boolean canWalk = BARITONE && host.walk() && !(BaritoneHelper.confined() && !usedConfine && !host.hostOwnsConfinement())
            && !BaritoneHelper.controlledByOther((baritone.api.pathing.goals.Goal) goal);

        return new Obs(changed, cancelled, menuOpen, ownedOpen, expected, menu.getCarried().isEmpty(), ours, state.isAir(),
            count(item), hasRoomFor(item), inReach, hasTool(), dropNearby, dropInRange, canWalk);
    }

    private Obs observeWorldChange() {
        return new Obs(true, false, false, false, false, true, false, false, 0, true, false, true, false, false, false);
    }

    private boolean hasTool() {
        if (mining == Mining.SILK) return InvUtils.find(Restocker::isSilkPickaxe).found();
        if (mining == Mining.LOSSY) return InvUtils.find(s -> s.is(ItemTags.PICKAXES)).found();
        return true;
    }

    private int count(Item item) {
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        ItemStack off = mc.player.getOffhandItem();
        if (off.is(item)) total += off.getCount();
        return total;
    }

    private boolean hasRoomFor(Item item) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || stack.is(item) && stack.getCount() < stack.getMaxStackSize()) return true;
        }
        return false;
    }

    /**
     * Says what the flow said, does what it asked, and cleans up if it is over.
     */
    private void handle(Step step) {
        if (step.msg() != null) host.say(step.level(), step.msg());

        if (step.ends()) {
            // An item moved out of a container into the hotbar stays there; say which slot
            if (step.act() == Act.ABORT && movedHotbar >= 0 && mc.player != null && item != null
                && mc.player.getInventory().getItem(movedHotbar).is(item)) {
                host.say(Level.WARN, "The " + what + " was moved into hotbar slot " + (movedHotbar + 1) + " and stays there.");
            }
            boolean orphaned = step.act() == Act.ORPHAN || step.act() == Act.ABORT && flow != null && flow.placed();
            if (orphaned) host.addOrphan(item, spot, dimension);
            cleanup();
            host.onFinished(orphaned ? Result.ORPHANED : step.act() == Act.FINISH ? Result.DONE : Result.ABORTED);
            return;
        }
        if (step.act() != Act.NONE) perform(step.act());
    }

    private void perform(Act act) {
        switch (act) {
            case CLOSE_SCREEN -> mc.player.closeContainer();
            case PLACE -> place();
            case OPEN -> {
                if (inReachNow()) {
                    Restocker.clickOpen(spot, host.rotate(), host.swing());
                    openClicked = true;
                }
            }
            case RESTORE -> restoreSwap(swap, s -> true, true);
            case MINE -> mine();
            case WALK_REACH -> walk(false);
            case WALK_DROP -> walk(true);
            case STOP_WALK -> releaseWalk();
            default -> {}
        }
    }

    /** The same target the spot search used: the middle of the top face of the support. */
    private boolean inReachNow() {
        Vec3 face = new Vec3(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        return mc.player.getEyePosition().distanceTo(face) <= mc.player.blockInteractionRange() - ReachSpots.MARGIN;
    }

    private void place() {
        if (!BlockUtils.canPlace(spot, true) || !Restocker.isGoodSupport(spot.below())) return;

        if (hotbarSlot < 0 || hotbarSlot > 8 || !mc.player.getInventory().getItem(hotbarSlot).is(item)) {
            handle(new Step(Act.ABORT, Level.ERROR, "The " + what + " is no longer in hotbar slot " + (hotbarSlot + 1) + "."));
            return;
        }
        Restocker.clickPlaceOnTop(spot, hotbarSlot, host.rotate(), host.swing());
    }

    private void mine() {
        if (mining == Mining.NORMAL) {
            FindItemResult tool = InvUtils.findFastestTool(mc.level.getBlockState(spot));
            if (tool.found()) InvUtils.swap(tool.slot(), false);
        }
        else {
            Predicate<ItemStack> wanted = mining == Mining.SILK ? Restocker::isSilkPickaxe : s -> s.is(ItemTags.PICKAXES);
            FindItemResult tool = InvUtils.find(wanted, 0, 8);
            if (!tool.found()) {
                bringToHotbar(wanted);
                return;
            }
            InvUtils.swap(tool.slot(), false);
        }
        Restocker.clickBreak(spot, host.rotate(), host.swing());
    }

    /**
     * Swaps the tool from the main inventory into the hotbar, remembering how to swap it back.
     */
    private void bringToHotbar(Predicate<ItemStack> wanted) {
        FindItemResult tool = InvUtils.find(wanted, 9, 35);
        int h = hotbarIndex(true) >= 0 ? hotbarIndex(true) : hotbarIndex(false);
        if (!tool.found() || h < 0) {
            handle(new Step(Act.ORPHAN, Level.ERROR, "No room in your hotbar for the pickaxe. The " + what + " is left at " + where + "."));
            return;
        }

        AbstractContainerMenu menu = mc.player.containerMenu;
        int id = inventorySlotId(menu, tool.slot());
        if (id < 0) return;
        toolSwap = new Swap(tool.slot(), h, mc.player.getInventory().getItem(h).copy());
        swapClick(menu, id, h);
    }

    // Hotbar and slots

    /**
     * The first hotbar slot that is empty ({@code empty}) or holds something that may be moved out of the way, or -1.
     */
    private int hotbarIndex(boolean empty) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (empty ? stack.isEmpty() : !stack.isEmpty() && !AutoHotbar.isProtected(stack)) return i;
        }
        return -1;
    }

    private void swapClick(AbstractContainerMenu menu, int slotId, int hotbar) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slotId, hotbar, ClickType.SWAP, mc.player);
    }

    /** The slot id in the menu of an inventory slot, or -1 if the menu does not show it. */
    private int inventorySlotId(AbstractContainerMenu menu, int inventoryIndex) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == inventoryIndex) return i;
        }
        return -1;
    }

    /**
     * Puts back what a swap moved, but only if the displaced stack is still where it went. Otherwise the player has moved
     * things and it is left alone.
     */
    private void restoreSwap(Swap s, Predicate<ItemStack> hotbarOk, boolean tellIfLeft) {
        if (s == null || mc.player == null || mc.gameMode == null) return;
        if (s == swap) swap = null;
        if (s == toolSwap) toolSwap = null;

        AbstractContainerMenu menu = mc.player.containerMenu;
        ItemStack at = mc.player.getInventory().getItem(s.src());
        ItemStack hot = mc.player.getInventory().getItem(s.hotbar());
        boolean sameDisplaced = at.isEmpty() && s.displaced().isEmpty()
            || ItemStack.isSameItemSameComponents(at, s.displaced()) && at.getCount() == s.displaced().getCount();
        int id = inventorySlotId(menu, s.src());

        if (s.displaced().isEmpty() && hot.isEmpty()) return;
        if (id >= 0 && sameDisplaced && menu.getCarried().isEmpty() && (hot.isEmpty() || hotbarOk.test(hot))) {
            swapClick(menu, id, s.hotbar());
        }
        else if (tellIfLeft && !s.displaced().isEmpty()) {
            host.say(Level.WARN, "Your " + s.displaced().getHoverName().getString() + " from hotbar slot " + (s.hotbar() + 1) + " is in inventory slot " + (s.src() + 1) + ".");
        }
    }

    // Walking

    private void walk(boolean toDrop) {
        if (!BARITONE) return;
        boolean hostConfines = host.hostOwnsConfinement();
        if (BaritoneHelper.confined() && !usedConfine && !hostConfines) {
            // Another module is walking under its own restrictions: leave its settings alone
            if (!hazardWarned) host.say(Level.WARN, "Another module has Baritone confined. Not walking.");
            hazardWarned = true;
            return;
        }
        if (toDrop) {
            if (dropPos == null) return;
            if (GroundCollector.hazardous(dropPos)) {
                if (!hazardWarned) host.say(Level.WARN, "The dropped " + what + " is somewhere dangerous. Not walking there.");
                hazardWarned = true;
                return;
            }
        }

        // A host that confined Baritone itself keeps its own protect and confine
        if (!hostConfines) {
            if (!usedProtect) {
                BaritoneHelper.protect(List.of());
                usedProtect = true;
            }
            if (!usedConfine) {
                BaritoneHelper.confine(host.safeWalk());
                usedConfine = true;
            }
        }
        if (goal != null) BaritoneHelper.releaseOurs((baritone.api.pathing.goals.Goal) goal);
        goal = toDrop ? BaritoneHelper.moveNear(dropPos, 0) : BaritoneHelper.moveInReach(List.of(spot), mc.player.blockInteractionRange());
    }

    /** Gives Baritone back what this flow took, and only that. */
    private void releaseWalk() {
        if (BARITONE) {
            if (goal != null) BaritoneHelper.releaseOurs((baritone.api.pathing.goals.Goal) goal);
            if (usedConfine) BaritoneHelper.unconfine();
            if (usedProtect) BaritoneHelper.unprotect();
        }
        goal = null;
        usedProtect = false;
        usedConfine = false;
    }

    /**
     * The flow is over: Baritone is released at once, and the rest is put back one step per tick (close the screen, the
     * tool, the displaced item, the selected slot) when the player is still in the world.
     */
    private void cleanup() {
        releaseWalk();
        if (flow != null && flow.placeTimedOut() && !flow.placed() && spot != null) {
            lateSpot = spot;
            lateTarget = target;
            lateItem = item;
            lateDimension = dimension;
            lateWhere = where;
            lateWhat = what;
            lateLevel = level;
            lateTicks = LATE_WATCH;
        }
        boolean live = !leaving && mc.player != null && mc.gameMode != null && mc.level != null && mc.level == level;
        flow = null;
        level = null;
        spot = null;
        startMenu = null;
        if (live) {
            cleaning = true;
            cleanStep = 0;
            cleanWait = 0;
        }
        else dropCleanup();
    }

    private void cleanTick() {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            dropCleanup();
            return;
        }
        if (cleanWait > 0) {
            cleanWait--;
            return;
        }
        if (cleanStepOnce()) cleanWait = host.actionDelay();
    }

    /** Does the next cleanup step that has anything to do. Returns true if it used this tick. */
    private boolean cleanStepOnce() {
        while (cleaning) {
            switch (cleanStep++) {
                case 0 -> {
                    if (ownedMenu != null && mc.player.containerMenu == ownedMenu) {
                        mc.player.closeContainer();
                        return true;
                    }
                }
                case 1 -> {
                    if (toolSwap != null) {
                        restoreSwap(toolSwap, s -> mining == Mining.SILK ? Restocker.isSilkPickaxe(s) : s.is(ItemTags.PICKAXES), false);
                        return true;
                    }
                }
                case 2 -> {
                    if (swap != null) {
                        restoreSwap(swap, s -> s.is(item), true);
                        return true;
                    }
                }
                case 3 -> {
                    if (prevSelected >= 0 && prevSelected <= 8 && Players.selectedSlot() != prevSelected) {
                        InvUtils.swap(prevSelected, false);
                        return true;
                    }
                }
                default -> {
                    dropCleanup();
                    return false;
                }
            }
        }
        return false;
    }

    /** Module off or leaving the world: finish at once (or, when leaving, skip the clicks). */
    private void finishCleanupNow() {
        if (!cleaning) return;
        if (leaving || mc.player == null || mc.gameMode == null) {
            dropCleanup();
            return;
        }
        while (cleaning) cleanStepOnce();
    }

    private void dropCleanup() {
        cleaning = false;
        swap = null;
        toolSwap = null;
        ownedMenu = null;
        prevSelected = -1;
        hotbarSlot = -1;
    }

    /** After a place timeout: the block may still turn up when the server catches up. */
    private void lateWatch() {
        if (lateSpot == null) return;
        if (mc.level != lateLevel || --lateTicks < 0) {
            lateSpot = null;
            return;
        }
        if (mc.level.getBlockState(lateSpot).is(lateTarget)) {
            host.addOrphan(lateItem, lateSpot, lateDimension);
            host.say(Level.WARN, "The " + lateWhat + " did appear at " + lateWhere + " after all. It is left there; it is listed under Left behind.");
            lateSpot = null;
        }
    }

    // World view for the spot search

    private final class WorldView implements ReachSpots.View {
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        @Override public boolean free(int x, int y, int z) { return BlockUtils.canPlace(pos.set(x, y, z), false) && !host.excluded(x, y, z); }
        @Override public boolean support(int x, int y, int z) { return Restocker.isGoodSupport(pos.set(x, y, z)); }
        @Override public boolean headroom(int x, int y, int z) {
            pos.set(x, y, z);
            return mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
        }
        @Override public boolean inPlayer(int x, int y, int z) { return mc.player.getBoundingBox().intersects(new AABB(x, y, z, x + 1, y + 1, z + 1)); }
        @Override public boolean entityIn(int x, int y, int z) {
            return !mc.level.isUnobstructed(Blocks.SHULKER_BOX.defaultBlockState(), pos.set(x, y, z), CollisionContext.empty());
        }
        @Override public boolean lineOfSight(int x, int y, int z, double hx, double hy, double hz) {
            var hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), new Vec3(hx, hy, hz), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos.set(x, y, z));
        }
    }

    // Helpers

    /** The dimension of the current world as a short name, or "?". */
    static String dimension() {
        if (mc.level == null) return "?";
        String key = mc.level.dimension().toString();
        int from = key.lastIndexOf("/ "), to = key.lastIndexOf(']');
        return from >= 0 && to > from ? key.substring(from + 2, to) : key;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, SmartPlaceSession.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
