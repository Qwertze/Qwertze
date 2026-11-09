package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.compat.Players;
import com.porter.qwerte.stash.Mending;
import com.porter.qwerte.stash.RepairEstimate;
import com.porter.qwerte.stash.RepairRun;
import com.porter.qwerte.stash.RepairRun.ItemResult;
import com.porter.qwerte.stash.RepairRun.Status;
import com.porter.qwerte.stash.StashIndex;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The world side of repairing: with the damaged Mending items and the XP bottles already carried, it stows the bot's own Mending gear,
 * holds one item at a time in the main hand and the bottles in the offhand, throws the bottles at its own feet (looking straight down,
 * standing still) and checks the item after each batch. Everything it moved is put back, also when it is stopped. The rules of when to
 * give up are {@link RepairRun.Guard}. All game objects are touched on the client thread only; one {@link #tick} per game tick.
 * <p>
 * Deviation from the contract's wording: the offhand holds the bottles (a bottle can only be thrown from a hand), so one damaged
 * item is repaired at a time, in the main hand.
 */
final class Repairer {
    interface Owner {
        /** The slots the sorter carries order stacks in. */
        Set<Integer> carry();

        /** The order was cancelled or parked: stop at once (everything is put back). */
        boolean calledOff();

        /** Ticks between two inventory clicks (the sorter's click delay, so the container-delay pacing is kept). */
        int delay();

        /** The stowed gear's menu slot pairs {from, to}, to be kept (saved) until put back; an empty list when nothing is stowed. */
        void saveGear(List<int[]> pairs);
    }

    private enum Phase { STOW, NEXT_ITEM, BOTTLES, THROW, WAIT, BACK, RESTORE, DONE }

    private record Click(int slotId, int button, ClickType type) {}

    private record Stowed(int from, int to, ItemStack stack) {}

    private static final int THROW_GAP = 2, BATCH_MAX = 8, WAIT_TICKS = 30, ORB_WAIT_EXTRA = 60, EQUIP_WAIT = 30, SCREEN_WAIT = 200;
    private static final int OFFHAND = 45;

    private final Owner owner;
    private List<String> keys = List.of();
    private final List<ItemResult> results = new ArrayList<>();
    private final Deque<Click> clicks = new ArrayDeque<>();
    private final List<Stowed> stowed = new ArrayList<>();
    private RepairRun.Guard guard = new RepairRun.Guard();
    private Phase phase = Phase.DONE;
    private int idx, cooldown, bottlesUsed, blocked;
    // The item being repaired
    private Item curItem;
    private int curSlot = -1, curSwapSel = -1, startDamage, thrownInBatch, sinceThrow, waitTicks, damageBefore, equipWait;
    private boolean orbWaited;
    // The hotbar slot selected before we changed it (-1: unchanged), and where the bottle stack came from (the offhand's other end)
    private int originalSelected = -1, bottleOrigin = -1;
    // Everything left is given up on for this reason (null: carry on)
    private String giveUp;
    private boolean started, stopping;
    // Slots whose item was taken up already, so two lots with the same key do not get the same stack
    private final Set<Integer> usedSlots = new java.util.HashSet<>();

    Repairer(Owner owner) {
        this.owner = owner;
    }

    boolean active() {
        return started && phase != Phase.DONE;
    }

    /** @param itemKeys the index keys of the damaged items to repair, carried in the inventory (in this order) */
    void start(List<String> itemKeys, int throwTicksUsed, int retriesUsed) {
        keys = List.copyOf(itemKeys);
        results.clear();
        clicks.clear();
        stowed.clear();
        usedSlots.clear();
        stopping = false;
        guard = new RepairRun.Guard();
        guard.preload(throwTicksUsed);
        guard.preloadRetries(retriesUsed);
        idx = 0;
        cooldown = 0;
        bottlesUsed = 0;
        blocked = 0;
        curItem = null;
        curSlot = curSwapSel = -1;
        originalSelected = bottleOrigin = -1;
        giveUp = null;
        started = true;
        phase = Phase.STOW;
    }

    RepairRun.Outcome outcome() {
        // Items never reached are given up on
        List<ItemResult> out = new ArrayList<>(results);
        for (int i = out.size(); i < keys.size(); i++) out.add(untouched(keys.get(i), giveUp == null ? RepairEstimate.INTERRUPTED : giveUp));
        return new RepairRun.Outcome(bottlesUsed, out, guard.throwTicks(), guard.retries());
    }

    /** Stops: everything is put back, then {@link #tick} returns true. */
    void stop(String why) {
        if (!active()) return;
        if (giveUp == null) giveUp = why;
        // Done once the clicks already queued have been made, so the restore is worked out from how things really stand
        if (phase != Phase.RESTORE) stopping = true;
    }

    private void runStop() {
        stopping = false;
        if (phase == Phase.BOTTLES || phase == Phase.THROW || phase == Phase.WAIT) recordCurrent(giveUp);
        if (phase != Phase.RESTORE) beginRestore();
    }

    private static void click(Click c) {
        mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, c.slotId(), c.button(), c.type(), mc.player);
    }

    private boolean screenClear() {
        return mc.player.containerMenu == mc.player.inventoryMenu && mc.player.containerMenu.getCarried().isEmpty();
    }

    /**
     * Puts the gear stowed by an earlier run (saved, e.g. before a death or a disconnect) back: each pair is only undone when the
     * stowed piece still sits where it was put and its old place is empty. Call with no window open and nothing on the cursor.
     */
    static void undoGear(List<int[]> pairs) {
        var menu = mc.player.inventoryMenu;
        for (int[] g : pairs) {
            int from = g[0], to = g[1];
            if (from < 5 || from > OFFHAND || to < 9 || to > 35) continue;
            if (!Mending.has(menu.getSlot(to).getItem()) || !menu.getSlot(from).getItem().isEmpty()) continue;
            click(new Click(to, 0, ClickType.PICKUP));
            click(new Click(from, 0, ClickType.PICKUP));
        }
    }

    /** Puts everything back at once, with no pacing (the module is being turned off, the order is over). */
    void abortNow() {
        if (!active()) return;
        if (giveUp == null) giveUp = RepairEstimate.INTERRUPTED;
        if (mc.player == null || mc.level == null) {
            // The stowed gear stays saved and is put back at the next chance
            clicks.clear();
            phase = Phase.DONE;
            return;
        }
        // The clicks already queued are made first, then the restore is worked out from how things really stand
        flush();
        if (phase == Phase.BOTTLES || phase == Phase.THROW || phase == Phase.WAIT) recordCurrent(giveUp);
        if (phase != Phase.RESTORE) beginRestore();
        flush();
        clicks.clear();
        finishRestore();
    }

    /** Makes every queued click now (a half-done stow leaves a stack on the cursor: it is put in the slot meant for it). */
    private void flush() {
        if (mc.screen != null || mc.player.containerMenu != mc.player.inventoryMenu) return;
        if (!mc.player.containerMenu.getCarried().isEmpty()) placeCursor();
        while (!clicks.isEmpty()) click(clicks.poll());
        if (!mc.player.containerMenu.getCarried().isEmpty()) placeCursor();
    }

    /** A stack of the bot's gear left on the cursor by a stow cut short goes to the slot it was being put in, else any free one. */
    private void placeCursor() {
        int slot = -1;
        for (Stowed s : stowed) if (mc.player.inventoryMenu.getSlot(s.to()).getItem().isEmpty()) slot = s.to();
        if (slot < 0) slot = freeSlot(Set.of());
        if (slot >= 0) click(new Click(slot, 0, ClickType.PICKUP));
    }

    /**
     * @return true when the job is over (and everything is put back)
     */
    boolean tick() {
        if (!active()) return true;
        if (mc.player == null || mc.level == null) {
            // Dead or gone: nothing can be put back through this player any more; the stowed gear stays saved for the next chance
            clicks.clear();
            phase = Phase.DONE;
            return true;
        }
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        // A stow cut short can leave a stack on the cursor: it goes where it was being put
        if (clicks.isEmpty() && mc.screen == null && mc.player.containerMenu == mc.player.inventoryMenu && !mc.player.containerMenu.getCarried().isEmpty()) {
            placeCursor();
            cooldown = Math.max(0, owner.delay());
            return false;
        }
        boolean screenOk = screenClear();
        if (!screenOk) {
            // A window of ours or someone's is open, or a stack is on the cursor: wait, then give up (restore waits too)
            if (++blocked > SCREEN_WAIT) {
                clicks.clear();
                if (phase == Phase.RESTORE) {
                    QwerteAddon.LOG.warn("orders: repair: couldn't put your gear back, a window stayed open");
                    phase = Phase.DONE;
                    return true;
                }
                stop(RepairEstimate.INTERRUPTED);
            }
            return false;
        }
        blocked = 0;

        if (!clicks.isEmpty()) {
            click(clicks.poll());
            cooldown = Math.max(0, owner.delay());
            return false;
        }
        if (phase != Phase.RESTORE && phase != Phase.DONE && owner.calledOff()) stop(RepairEstimate.INTERRUPTED);
        if (stopping) {
            runStop();
            return false;
        }

        switch (phase) {
            case STOW -> stow();
            case NEXT_ITEM -> nextItem();
            case BOTTLES -> bottles();
            case THROW -> throwing();
            case WAIT -> waiting();
            case BACK -> phase = Phase.NEXT_ITEM;
            case RESTORE -> finishRestore();
            case DONE -> { }
        }
        return phase == Phase.DONE;
    }

    // ---- stowing the bot's own gear

    private void stow() {
        List<Integer> sources = new ArrayList<>();
        for (int id = 5; id <= 8; id++) if (Mending.has(mc.player.inventoryMenu.getSlot(id).getItem())) sources.add(id);
        if (Mending.has(mc.player.inventoryMenu.getSlot(OFFHAND).getItem())) sources.add(OFFHAND);

        Set<Integer> taken = new java.util.HashSet<>();
        for (int from : sources) {
            int free = freeSlot(taken);
            if (free < 0) {
                // Nowhere to put it: the gear would take the XP, so nothing is thrown
                // (the stow clicks already queued are made, then everything is put back)
                giveUp = RepairEstimate.INTERRUPTED;
                stopping = true;
                return;
            }
            taken.add(free);
            ItemStack stack = mc.player.inventoryMenu.getSlot(from).getItem().copy();
            clicks.add(new Click(from, 0, ClickType.PICKUP));
            clicks.add(new Click(free, 0, ClickType.PICKUP));
            stowed.add(new Stowed(from, free, stack));
        }
        // Saved before the clicks are made, so a disconnect or a death in between can still be undone
        List<int[]> pairs = new ArrayList<>();
        for (Stowed st : stowed) pairs.add(new int[] { st.from(), st.to() });
        owner.saveGear(pairs);
        phase = Phase.NEXT_ITEM;
    }

    /** An empty main inventory slot (menu id 9 to 35) not in {@code taken}; slots we do not carry in come first. */
    private int freeSlot(Set<Integer> taken) {
        int any = -1;
        for (int id = 9; id <= 35; id++) {
            if (taken.contains(id) || !mc.player.inventoryMenu.getSlot(id).getItem().isEmpty()) continue;
            if (!owner.carry().contains(id)) return id;
            if (any < 0) any = id;
        }
        return any;
    }

    // ---- the items

    private static int menuId(int invIndex) {
        return invIndex < 9 ? 36 + invIndex : invIndex;
    }

    private int findItem(String key) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (usedSlots.contains(i) || s.isEmpty() || !Mending.has(s) || !s.isDamaged()) continue;
            if (key.equals(StashIndex.keyOf(s))) return i;
        }
        return -1;
    }

    /** The inventory index the damaged item is held in now: the selected hotbar slot after a swap, else its own slot. */
    private int itemHeldSlot() {
        return curSwapSel >= 0 ? curSwapSel : curSlot;
    }

    private int bottleSlot() {
        int held = itemHeldSlot();
        for (int i = 0; i < 36; i++) {
            if (i == held) continue;
            if (mc.player.getInventory().getItem(i).is(Items.EXPERIENCE_BOTTLE)) return i;
        }
        return -1;
    }

    private boolean anyBottles() {
        return mc.player.getOffhandItem().is(Items.EXPERIENCE_BOTTLE) || bottleSlot() >= 0;
    }

    private static ItemResult untouched(String key, String reason) {
        return new ItemResult(key, null, Status.UNTOUCHED, 0, 0, reason);
    }

    private void nextItem() {
        if (idx >= keys.size()) {
            beginRestore();
            return;
        }
        String key = keys.get(idx);
        if (giveUp != null) {
            results.add(untouched(key, giveUp));
            idx++;
            return;
        }
        int slot = findItem(key);
        if (slot < 0) {
            results.add(untouched(key, RepairEstimate.INTERRUPTED));
            idx++;
            return;
        }
        if (!anyBottles()) {
            giveUp = RepairEstimate.NO_BOTTLES;
            return;
        }

        ItemStack stack = mc.player.getInventory().getItem(slot);
        curItem = stack.getItem();
        startDamage = stack.getDamageValue();
        thrownInBatch = 0;
        sinceThrow = THROW_GAP;
        orbWaited = false;
        equipWait = 0;
        curSlot = slot;
        usedSlots.add(slot);
        curSwapSel = -1;
        if (slot < 9) {
            if (originalSelected < 0) originalSelected = Players.selectedSlot();
            if (Players.selectedSlot() != slot) InvUtils.swap(slot, false);
        }
        else {
            int selected = Players.selectedSlot();
            curSwapSel = selected;
            clicks.add(new Click(menuId(slot), selected, ClickType.SWAP));
        }
        phase = Phase.BOTTLES;
    }

    private void bottles() {
        if (mc.player.getOffhandItem().is(Items.EXPERIENCE_BOTTLE)) {
            phase = Phase.THROW;
            return;
        }
        int b = bottleSlot();
        if (b < 0) {
            finishItem(RepairEstimate.NO_BOTTLES);
            return;
        }
        if (bottleOrigin < 0) bottleOrigin = b;
        clicks.add(new Click(menuId(b), OFFHAND_BUTTON, ClickType.SWAP));
        equipWait = 0;
        phase = Phase.THROW;
    }

    private static final int OFFHAND_BUTTON = 40;

    private boolean inHand() {
        ItemStack main = mc.player.getMainHandItem();
        return !main.isEmpty() && main.getItem() == curItem && Mending.has(main);
    }

    private boolean nearbyPlayer() {
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator()) continue;
            if (p.distanceTo(mc.player) <= RepairRun.Guard.PLAYER_RADIUS) return true;
        }
        return false;
    }

    private int orbsNear() {
        return mc.level.getEntitiesOfClass(ExperienceOrb.class, mc.player.getBoundingBox().inflate(6)).size();
    }

    private void throwing() {
        guard.countThrowTick();
        if (!inHand()) {
            if (++equipWait > EQUIP_WAIT) finishItem(RepairEstimate.INTERRUPTED);
            return;
        }
        if (!mc.player.getOffhandItem().is(Items.EXPERIENCE_BOTTLE)) {
            // The stack in the offhand is used up: the next one comes in at once
            if (anyBottles()) phase = Phase.BOTTLES;
            else finishItem(RepairEstimate.NO_BOTTLES);
            return;
        }
        equipWait = 0;
        int damage = mc.player.getMainHandItem().getDamageValue();
        if (damage <= 0) {
            finishItem(null);
            return;
        }
        if (guard.capped()) {
            giveUp = RepairEstimate.INTERRUPTED;
            finishItem(RepairEstimate.INTERRUPTED);
            return;
        }
        switch (guard.playerNear(nearbyPlayer())) {
            case PAUSE -> {
                return;
            }
            case GIVE_UP -> {
                giveUp = RepairEstimate.INTERRUPTED;
                finishItem(RepairEstimate.INTERRUPTED);
                return;
            }
            default -> { }
        }
        if (++sinceThrow < THROW_GAP) return;
        sinceThrow = 0;

        double yaw = mc.player.getYRot();
        Rotations.rotate(yaw, 90, () -> {
            if (mc.player == null || phase != Phase.THROW) return;
            var result = mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            if (result.consumesAction()) {
                bottlesUsed++;
                thrownInBatch++;
            }
        });
        if (thrownInBatch >= Math.min(BATCH_MAX, Math.max(1, RepairEstimate.bottlesFor(damage)))) {
            damageBefore = damage;
            waitTicks = 0;
            orbWaited = false;
            phase = Phase.WAIT;
        }
    }

    private void waiting() {
        guard.countThrowTick();
        waitTicks++;
        if (guard.playerNear(nearbyPlayer()) == RepairRun.Verdict.GIVE_UP || guard.capped()) {
            giveUp = RepairEstimate.INTERRUPTED;
            finishItem(RepairEstimate.INTERRUPTED);
            return;
        }
        if (!inHand()) {
            finishItem(RepairEstimate.INTERRUPTED);
            return;
        }
        int damage = mc.player.getMainHandItem().getDamageValue();
        if (damage <= 0) {
            finishItem(null);
            return;
        }
        if (waitTicks < WAIT_TICKS) return;
        if (damage < damageBefore) {
            thrownInBatch = 0;
            phase = Phase.THROW;
            return;
        }
        // No better: orbs still flying get a little longer, orbs gone mean somebody took them
        if (orbsNear() > 0 && !orbWaited && waitTicks < WAIT_TICKS + ORB_WAIT_EXTRA) return;
        orbWaited = true;
        thrownInBatch = 0;
        switch (guard.orbsVanished()) {
            case GIVE_UP -> {
                giveUp = RepairEstimate.INTERRUPTED;
                finishItem(RepairEstimate.INTERRUPTED);
            }
            default -> phase = Phase.THROW;
        }
    }

    /** The item in hand is done with: its result is noted and it goes back to its slot. */
    private void finishItem(String why) {
        recordCurrent(why);
        if (curSwapSel >= 0) clicks.add(new Click(menuId(curSlot), curSwapSel, ClickType.SWAP));
        curSlot = curSwapSel = -1;
        if (why != null && why.equals(RepairEstimate.NO_BOTTLES) && giveUp == null) giveUp = RepairEstimate.NO_BOTTLES;
        phase = Phase.BACK;
    }

    /** Notes how the item in hand ended up and moves on to the next one (it is not moved back here). */
    private void recordCurrent(String why) {
        if (idx >= keys.size() || curItem == null) return;
        String key = keys.get(idx);
        ItemStack main = mc.player.getMainHandItem();
        boolean here = !main.isEmpty() && main.getItem() == curItem;
        if (here) {
            int damage = main.getDamageValue();
            String newKey = StashIndex.keyOf(main);
            if (damage <= 0) results.add(new ItemResult(key, newKey, Status.FULL, 0, main.getMaxDamage(), null));
            else if (damage < startDamage) results.add(new ItemResult(key, newKey, Status.PARTIAL, damage, main.getMaxDamage(), why == null ? RepairEstimate.NO_BOTTLES : why));
            else results.add(new ItemResult(key, newKey, Status.UNTOUCHED, damage, main.getMaxDamage(), why == null ? RepairEstimate.INTERRUPTED : why));
        }
        else results.add(untouched(key, RepairEstimate.INTERRUPTED));
        curItem = null;
        idx++;
    }

    // ---- putting everything back

    private void beginRestore() {
        clicks.clear();
        // The item in hand goes home first (when we swapped it in), then the bottles, then the gear
        if (curSwapSel >= 0 && curSlot >= 0) clicks.add(new Click(menuId(curSlot), curSwapSel, ClickType.SWAP));
        curSlot = curSwapSel = -1;
        boolean offhandHasOurs = mc.player.getOffhandItem().is(Items.EXPERIENCE_BOTTLE) || (bottleOrigin >= 0 && !mc.player.getInventory().getItem(bottleOrigin).isEmpty());
        if (bottleOrigin >= 0 && offhandHasOurs) clicks.add(new Click(menuId(bottleOrigin), OFFHAND_BUTTON, ClickType.SWAP));
        for (int i = stowed.size() - 1; i >= 0; i--) {
            Stowed s = stowed.get(i);
            ItemStack there = mc.player.inventoryMenu.getSlot(s.to()).getItem();
            if (there.isEmpty() || !ItemStack.isSameItemSameComponents(there, s.stack())) {
                QwerteAddon.LOG.warn("orders: repair: your stowed {} is no longer where it was put, left alone", s.stack().getItem());
                continue;
            }
            clicks.add(new Click(s.to(), 0, ClickType.PICKUP));
            clicks.add(new Click(s.from(), 0, ClickType.PICKUP));
        }
        stowed.clear();
        phase = Phase.RESTORE;
    }

    private void finishRestore() {
        owner.saveGear(List.of());
        if (originalSelected >= 0 && Players.selectedSlot() != originalSelected) InvUtils.swap(originalSelected, false);
        originalSelected = -1;
        phase = Phase.DONE;
    }
}
