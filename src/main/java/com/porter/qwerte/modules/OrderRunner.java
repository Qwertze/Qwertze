package com.porter.qwerte.modules;

import com.porter.qwerte.craft.AnvilFinder;
import com.porter.qwerte.craft.CraftPlanner;
import com.porter.qwerte.stash.DeliveryRoom;
import com.porter.qwerte.stash.Kit;
import com.porter.qwerte.stash.Kits;
import com.porter.qwerte.stash.Order;
import com.porter.qwerte.stash.Stash;
import com.porter.qwerte.stash.Stash.Box;
import com.porter.qwerte.stash.SpaceWait;
import com.porter.qwerte.stash.StashIndex;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Carries out an order from the Stash Manager server for the stash sorter: plans the order into shulkers, builds each
 * with the kit builder (as transient exact kits), fetches whole shulkers the order asks for, and delivers every
 * finished shulker to the delivery container straight away, so it never carries more than one finished shulker.
 *
 * The sorter runs the world side; this only decides the next step from the live inventory, like the kit builder. All
 * game objects are touched on the client thread only. The plan is saved with the stash, so a resume after a restart
 * carries on the same plan. The order logic stays here and in {@link Order}, so it can be moved to the proxy plugin.
 */
final class OrderRunner {
    interface Owner {
        Stash stash();

        /** The sorter is on, sorting items, and may use shulkers. */
        boolean gate();

        boolean usable(Box box);

        boolean keepWhole(Box box);

        /** Orders may take items out of shulkers with a custom name. */
        boolean namedShulkers();

        /** Containers last seen before a stop are still being read again, so what the stash holds is not known yet. */
        boolean rereading();

        /** How many containers are waiting to be read again. */
        int staleCount();

        double distance(Box box);

        Set<Integer> carry();

        void say(String text);

        /** Why a shulker can't be named now (no anvil set, not an anvil any more, no XP levels), or null when it can. */
        String anvilProblem();

        /** The anvil position, or null. */
        BlockPos anvilPos();

        /** The crafter of the sorter. */
        Crafter crafter();

        /** enable-crafting is on. */
        boolean craftingOn();

        /** Why nothing can be crafted now (no crafting table can be had, it failed for this order), or null. */
        String craftBlocker();

        /** No anvil is set or remembered, or the one that was is gone: one is to be found or placed before naming. */
        boolean anvilMissing();

        /** What the delivery storage can take now (0.4.0-dev.5): room, a chest to look at, another chest to put up, or waiting. */
        DeliveryRoom deliveryRoom();

        /** How many chests the delivery storage has (at least 1). */
        int deliveryChests();

        /** The delivery chest the next shulker goes into: the lowest with room; with {@code verify} the one the last went into. */
        Box deliveryChest(boolean verify);
    }

    sealed interface Intent permits Idle, Build, Fetch, Deliver, Rename, Craft, FindAnvil, Expand, Recheck {}

    /** Nothing for the sorter to do for the order now. */
    record Idle() implements Intent {}

    /** A kit builder step: take, fetch, unpack or fill. */
    record Build(KitBuilder.Intent intent) implements Intent {}

    /** Take this shulker out of the container. */
    record Fetch(Box box, int slot) implements Intent {}

    /** Open the delivery container and put the order's shulkers in. */
    record Deliver(Box box) implements Intent {}

    /** Walk to the anvil and give the shulker in this inventory slot the name. A failure is told with {@link #nameFailed}. */
    record Rename(BlockPos anvil, String name, int slot) implements Intent {}

    /** Craft this many plain shulker boxes (0: carry on with the crafting in hand). A failure is told with {@link #craftEnded}. */
    record Craft(int count) implements Intent {}

    /** Look for an anvil in the stash area, or take one from the stash and place it. The outcome is told with {@link #anvilFound}. */
    record FindAnvil() implements Intent {}

    /** Every delivery chest is full: put another double chest up above the storage. What came of it shows in the next room. */
    record Expand() implements Intent {}

    /** Open this delivery chest, found full before and perhaps emptied since, and read what it holds. */
    record Recheck(Box box) implements Intent {}

    enum State { Running, Delivered, Failed, Cancelled }

    /** A snapshot of the order for the server. */
    record Report(String id, String status, String phase, int done, int total, String message, int shulkers, Map<String, Integer> delivered) {
        boolean terminal() {
            return !status.equals("in_progress");
        }
    }

    private enum Kind { Shulker, Empty, Bin }

    private static final class Unit {
        final Kind kind;
        final String key;
        final List<Order.Lot> lots;
        Kit kit;
        boolean have, built, confirmed, verified, verifying, replanned;
        int fullTries, starts, fetches, begins, base;
        // How often delivery storage was asked to grow for this unit
        int expands;
        // Expand and Recheck steps in a row (bounded: a sorter that can't start them gets a wait)
        int spins;
        // The name this shulker is to get (null: none), and how naming it went
        String name, unnamedReason;
        boolean nameDone, named;
        int nameTries;
        // How often crafting an empty shulker for this unit was started
        int craftTries;
        // How often an anvil was looked for on behalf of this unit
        int anvilTries;
        // The shulker's own name before it was renamed (null: it had none), to recognise it afterwards
        net.minecraft.network.chat.Component origName;
        // The key the shulker has with its new name (packed and empty shulkers), kept in the saved plan
        String namedKey;
        Map<String, Integer> content = Map.of();
        // Lot totals by key, to recognise a finished shulker before the kit exists
        Map<String, Integer> lotTotals;

        Unit(Kind kind, String key, List<Order.Lot> lots) {
            this.kind = kind;
            this.key = key;
            this.lots = lots;
        }
    }

    /** What the stash can give: a sample stack of each wanted key, how many there are, and keys only found filled. */
    private record Survey(Map<String, ItemStack> samples, Map<String, Long> available, Set<String> filled, Set<String> staleOnly, Map<String, List<Variant>> variants, Map<String, String> why) {}

    /** Stacks of one key that are identical in every component, so they can share a slot. */
    private static final class Variant {
        final ItemStack sample;
        long count;

        Variant(ItemStack sample) {
            this.sample = sample;
        }
    }

    private record RawKey(net.minecraft.world.item.Item item, net.minecraft.core.component.DataComponentMap components) {}

    private static final long PAUSE_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long REREAD_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final int MAX_FULL_TRIES = 2;
    /** Times one shulker asks the delivery storage to grow before the order waits instead. */
    private static final int MAX_EXPANDS = 6;
    private static final int MAX_SPINS = 30;
    private static final long SPIN_HOLD_MS = 60_000L;
    private static final int MAX_STARTS = 8;
    private static final int MAX_NAME_TRIES = 2;
    private static final int MAX_CRAFT_TRIES = 2;
    private static final String SHULKER_BOX = "minecraft:shulker_box";
    /** The server keeps this much of an order's message. */
    private static final int MESSAGE_MAX = 300;

    private final KitBuilder kits;
    private final Owner owner;

    private Order order;
    private State state = State.Running;
    private final List<Unit> units = new ArrayList<>();
    private int index;
    private int shulkersDelivered;
    private final Map<String, Integer> delivered = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    // Shulkers delivered without the name they were meant to get, and the distinct reasons
    private int unnamed;
    private final List<String> unnamedReasons = new ArrayList<>();
    private String message = "";
    // Shulker boxes crafted for this order, and why crafting was given up on for it (null: not)
    private int craftedTotal;
    private String craftFailure;
    // No anvil could be found or placed for this order: later shulkers go unnamed at once
    private boolean anvilNone;
    private String phase = "starting";
    private boolean cancelRequested, parked;
    private long pausedSince;
    private boolean full;
    // The wait for room in the delivery storage
    private final SpaceWait spaceWait = new SpaceWait();
    private long spinHoldUntil;
    private String lastWaitWhy;
    private int skips;
    private boolean planned;
    private long offeredAt, rereadProgressAt;
    private int lastStale = Integer.MAX_VALUE;
    private Survey lastSurvey;
    private int lastSurveyTick = -1;
    private Set<String> lastSurveyWanted;
    private boolean chat = true;

    // Which stacks are the order's, worked out once per tick and unit
    private final IdentityHashMap<ItemStack, Boolean> claimCache = new IdentityHashMap<>();
    private int claimTick = -1, epoch, claimEpoch;

    OrderRunner(KitBuilder kits, Owner owner) {
        this.kits = kits;
        this.owner = owner;
    }

    // State seen from outside

    /** An order is being worked on and planned: it comes before the sorter's own work. */
    boolean planned() {
        return running() && planned;
    }

    /** The order is to be stopped (cancelled) or parked: nothing more is put down for it. */
    boolean calledOff() {
        return cancelRequested || parked;
    }

    /** An order is being worked on. */
    boolean running() {
        return order != null && state == State.Running;
    }

    /** There is an order, finished or not, that the server has not been told the end of yet. */
    boolean holdsOrder() {
        return order != null;
    }

    void chat(boolean on) {
        chat = on;
    }

    /** Forgets the order here (leaving the world, parking). The saved state is kept for a resume. */
    void reset() {
        kits.dropOrderKit();
        order = null;
        units.clear();
        index = 0;
        shulkersDelivered = 0;
        delivered.clear();
        problems.clear();
        unnamed = 0;
        unnamedReasons.clear();
        craftedTotal = 0;
        craftFailure = null;
        anvilNone = false;
        endCrafting("the order was left");
        owner.crafter().release();
        cancelRequested = false;
        parked = false;
        pausedSince = 0;
        full = false;
        spaceWait.reset();
        lastSurvey = null;
        planned = false;
        state = State.Running;
        epoch++;
    }

    Box deliveryBox() {
        Stash stash = owner.stash();
        if (stash == null) return null;
        // The delivery container itself, not the chests put up above it
        for (Box b : stash.boxes) if (b.delivery && !b.deliveryExtra) return b;
        return null;
    }

    // Taking an order

    /**
     * @return null when the order is taken, else why not. While containers are still being read again after a
     *         reconnect the plan waits for them, and nothing is rejected for what is not known yet.
     */
    String offer(Order o) {
        if (order != null) return order.id.equals(o.id) ? null : "already working on another order";
        Stash stash = owner.stash();
        if (stash == null || mc.level == null) return "no stash is loaded";
        if (deliveryBox() == null) return "no delivery container is set";

        order = o;
        state = State.Running;
        units.clear();
        index = 0;
        shulkersDelivered = 0;
        delivered.clear();
        problems.clear();
        unnamed = 0;
        unnamedReasons.clear();
        message = "";
        craftedTotal = 0;
        craftFailure = null;
        anvilNone = false;
        cancelRequested = false;
        parked = false;
        pausedSince = 0;
        full = false;
        spaceWait.reset();
        planned = false;
        offeredAt = System.currentTimeMillis();
        rereadProgressAt = offeredAt;
        lastStale = Integer.MAX_VALUE;
        phase = "re-reading containers";
        epoch++;

        if (!owner.rereading()) {
            String why = completePlan();
            if (why != null) {
                order = null;
                units.clear();
                problems.clear();
                delivered.clear();
                shulkersDelivered = 0;
                unnamed = 0;
                unnamedReasons.clear();
                return why;
            }
        }
        return null;
    }

    /**
     * Plans the order from what the stash holds now (or takes the saved plan of a resume), and saves it.
     * @return null when planned, else why the order can't be done
     */
    private String completePlan() {
        Stash stash = owner.stash();
        Order o = order;
        if (stash == null || o == null) return "no stash is loaded";

        List<Unit> plan = new ArrayList<>();
        int start = 0;
        Stash.ActiveOrder saved = stash.activeOrder;
        List<String> notes = new ArrayList<>();
        boolean resumed = saved != null && saved.id.equals(o.id) && !saved.units.isEmpty();

        if (resumed) {
            for (Stash.ActiveOrder.UnitPlan p : saved.units) {
                List<Order.Lot> lots = new ArrayList<>();
                for (int i = 0; i < p.lotKeys.size(); i++) lots.add(new Order.Lot(p.lotKeys.get(i), p.lotCounts.get(i)));
                Unit restored = new Unit(p.kind == 0 ? Kind.Shulker : p.kind == 1 ? Kind.Empty : Kind.Bin, p.key, lots);
                restored.nameDone = p.nameDone;
                restored.named = p.named;
                restored.unnamedReason = p.nameFailure == null || p.nameFailure.isEmpty() ? null : p.nameFailure;
                restored.namedKey = p.namedKey == null || p.namedKey.isEmpty() ? null : p.namedKey;
                plan.add(restored);
            }
            start = Math.min(saved.done, plan.size());
            // What was delivered before counts even if the rest cannot be done now
            shulkersDelivered = saved.shulkersDelivered;
            delivered.putAll(saved.delivered);
            unnamed = saved.unnamed;
            unnamedReasons.addAll(saved.unnamedReasons);
            craftedTotal = saved.crafted;
        }
        else {
            String why = planFresh(stash, o, plan, notes);
            if (why != null) return why;
        }

        String why = checkEmpties(plan.subList(start, plan.size()));
        if (why != null) return why;

        // The names follow the plan by position; a resume keeps the names it saved
        Order.Names names = resumed && !saved.namesMode.isEmpty() ? Order.Names.of(saved.namesMode, saved.namesAll, saved.namesEach) : o.names;
        for (int i = 0; i < plan.size(); i++) plan.get(i).name = names == null ? null : names.nameFor(i);

        units.clear();
        units.addAll(plan);
        index = start;
        problems.addAll(notes);
        planned = true;
        phase = "starting";
        epoch++;

        if (!resumed) {
            Stash.ActiveOrder active = new Stash.ActiveOrder();
            active.id = o.id;
            for (Order.Line l : o.lines) active.lines.add(new String[] { l.type(), l.key(), String.valueOf(l.count()) });
            for (Unit u : plan) {
                Stash.ActiveOrder.UnitPlan p = new Stash.ActiveOrder.UnitPlan();
                p.kind = u.kind == Kind.Shulker ? 0 : u.kind == Kind.Empty ? 1 : 2;
                p.key = u.key;
                for (Order.Lot lot : u.lots) {
                    p.lotKeys.add(lot.key());
                    p.lotCounts.add(lot.count());
                }
                active.units.add(p);
            }
            if (names != null) {
                active.namesMode = names.mode();
                active.namesAll = names.all();
                active.namesEach.addAll(names.each());
            }
            stash.activeOrder = active;
            stash.save();
        }
        say("Taking order " + o.id + ": " + (units.size() - index) + " shulkers to deliver" + (index > 0 ? ", " + index + " already done." : "."));
        return null;
    }

    /** Plans the order from what the addon can really use. Fills {@code plan}; returns why not, or null. */
    private String planFresh(Stash stash, Order o, List<Unit> plan, List<String> notes) {
        Set<String> wanted = new HashSet<>();
        for (Order.Line l : o.lines) if (l.type().equals(Order.ITEM)) wanted.add(l.key());
        Survey survey = survey(stash, wanted);

        Map<String, Integer> emptiesByKey = new HashMap<>();
        for (ItemStack e : kits.emptyShulkers()) {
            String k = StashIndex.keyOf(e);
            if (k != null) emptiesByKey.merge(k, 1, Integer::sum);
        }

        List<Unit> shulkerUnits = new ArrayList<>(), emptyUnits = new ArrayList<>();
        List<Order.Lot> lots = new ArrayList<>();
        long stacks = 0;
        Map<String, Long> left = new HashMap<>(survey.available());
        Map<Variant, Long> used = new java.util.IdentityHashMap<>();

        for (Order.Line l : o.lines) {
            if (l.type().equals(Order.SHULKER)) {
                for (int i = 0; i < l.count(); i++) shulkerUnits.add(new Unit(Kind.Shulker, l.key(), List.of()));
                continue;
            }

            ItemStack sample = survey.samples().get(l.key());
            if (sample == null) {
                String name = StashIndex.nameOf(l.key());
                notes.add(survey.filled().contains(l.key()) ? name + ": can't order filled containers/bundles"
                    : survey.staleOnly().contains(l.key()) ? name + ": only in containers I couldn't read again"
                    : survey.why().containsKey(l.key()) ? name + ": only " + survey.why().get(l.key())
                    : name + ": none usable in the stash");
                continue;
            }

            // Shulkers can't go in shulkers: an item line for a shulker box is empty shulkers, delivered as they are
            if (Utils.isShulker(sample.getItem())) {
                int have = emptiesByKey.getOrDefault(l.key(), 0);
                int take = Math.min(l.count(), have);
                emptiesByKey.put(l.key(), have - take);
                for (int i = 0; i < take; i++) emptyUnits.add(new Unit(Kind.Empty, l.key(), List.of()));
                if (take < l.count()) notes.add(StashIndex.nameOf(l.key()) + ": only " + take + " of " + l.count() + " free");
                continue;
            }

            long usable = Math.min(l.count(), left.getOrDefault(l.key(), 0L));
            left.put(l.key(), left.getOrDefault(l.key(), 0L) - usable);
            if (usable < l.count()) {
                String reason = survey.why().get(l.key());
                notes.add(StashIndex.nameOf(l.key()) + ": only " + usable + " of " + l.count() + " usable" + (reason == null ? "" : " (the rest " + reason + ")"));
            }
            if (usable <= 0) continue;

            // Stacks that differ in any component (ViaVersion's marker, say) can't share a slot, so each stack of the
            // line is taken from one variant (the smallest that has enough, else the biggest) and may be split over two
            int max = Math.max(1, sample.getMaxStackSize());
            List<Order.Lot> mine = allocate(l.key(), usable, max, survey.variants().getOrDefault(l.key(), List.of()), used);
            stacks += mine.size();
            if (stacks > (long) Order.MAX_BINS * Order.SLOTS) return "too big: that is more than " + Order.MAX_BINS + " shulkers";
            lots.addAll(mine);
        }

        List<List<Order.Lot>> bins = Order.pack(lots);
        if (shulkerUnits.size() + emptyUnits.size() + bins.size() > Order.MAX_BINS) return "too big: that is more than " + Order.MAX_BINS + " shulkers";

        plan.addAll(shulkerUnits);
        plan.addAll(emptyUnits);
        for (List<Order.Lot> bin : bins) plan.add(new Unit(Kind.Bin, "", bin));
        if (plan.isEmpty()) return notes.isEmpty() ? "nothing to deliver" : "nothing can be done: " + notes.get(0);
        return null;
    }

    /**
     * Whether the empty shulkers in the stash are enough for the units still to do, kind by kind. Plain shulker boxes that
     * are short can be crafted, when crafting is on and the plan for them goes through.
     */
    private String checkEmpties(List<Unit> remaining) {
        int bins = 0;
        Map<String, Integer> need = new HashMap<>();
        for (Unit u : remaining) {
            if (u.kind == Kind.Bin) bins++;
            else if (u.kind == Kind.Empty) need.merge(u.key, 1, Integer::sum);
        }
        if (bins == 0 && need.isEmpty()) return null;

        Map<String, Integer> have = new HashMap<>();
        int total = 0;
        for (ItemStack e : kits.emptyShulkers()) {
            String k = StashIndex.keyOf(e);
            if (k != null) have.merge(k, 1, Integer::sum);
            total++;
        }
        int asked = 0, plainShort = 0;
        String old = null;
        for (var e : need.entrySet()) {
            int got = have.getOrDefault(e.getKey(), 0);
            asked += Math.min(e.getValue(), got);
            if (got >= e.getValue()) continue;
            if (!e.getKey().equals(SHULKER_BOX)) return "need " + e.getValue() + " empty shulkers of one kind, have " + got;
            plainShort += e.getValue() - got;
            if (old == null) old = "need " + e.getValue() + " empty shulkers of one kind, have " + got;
        }
        int binShort = Math.max(0, bins - (total - asked));
        int shortfall = plainShort + binShort;
        if (shortfall == 0) return null;
        if (binShort > 0) old = "need " + (bins + asked) + " empty shulkers, have " + total;

        if (!owner.craftingOn()) return old;
        String blocked = owner.craftBlocker();
        if (blocked != null) return old + "; couldn't craft: " + blocked;
        var result = owner.crafter().preview(SHULKER_BOX, shortfall, craftedTotal, craftPool(remaining), true);
        if (!result.ok()) return old + "; couldn't craft: " + result.refusal();
        if (result.plan().count() < shortfall) return old + "; couldn't craft enough: " + (result.plan().limited() == null ? CraftPlanner.NOT_ENOUGH : result.plan().limited());
        return null;
    }

    /**
     * What crafts may use up: the stash, less the items the units still to do need for their shulkers. One pool, used by
     * the plan, the replan and the run.
     */
    Map<String, Long> craftPool() {
        return craftPool(index < units.size() ? units.subList(index, units.size()) : List.of());
    }

    private Map<String, Long> craftPool(List<Unit> remaining) {
        Map<String, Long> pool = owner.crafter().survey();
        for (Unit u : remaining) {
            for (Order.Lot lot : u.lots) {
                String key = lot.key();
                int hash = key.indexOf('#');
                pool.merge(hash < 0 ? key : key.substring(0, hash), -(long) lot.count(), Long::sum);
            }
        }
        pool.values().removeIf(n -> n <= 0);
        return pool;
    }

    /**
     * Crafting in hand is given up on when the order ends: the crafter is aborted, and the sorter drives the session to its
     * end by the normal path (the grid is emptied, what was gathered is put back).
     */
    private void endCrafting(String why) {
        Crafter crafter = owner.crafter();
        if (crafter.active()) crafter.abort(why);
    }

    /** The order cannot go on (the crafting window cannot be closed): it ends with this reason. */
    void abortOrder(String why) {
        if (!running()) return;
        finish(shulkersDelivered > 0 ? State.Delivered : State.Failed, why);
    }

    /** The index keys of the lots the units still to do need: stacks of these are the last to be taken for a craft. */
    Set<String> lotKeys() {
        Set<String> keys = new HashSet<>();
        for (int i = Math.max(0, index); i < units.size(); i++) for (Order.Lot lot : units.get(i).lots) keys.add(lot.key());
        return keys;
    }

    /** An anvil was looked for: found, placed, or not to be had (then the shulkers of this order go unnamed). */
    void anvilFound(boolean ok) {
        if (order == null) return;
        if (!ok) anvilNone = true;
        epoch++;
    }

    /** A crafting table was found, placed or crafted for the order's crafting: the order looks at its next step afresh. */
    void tableReady() {
        if (order == null) return;
        // The try spent on the craft that waited for the table is given back
        if (index >= 0 && index < units.size() && units.get(index).craftTries > 0) units.get(index).craftTries--;
        epoch++;
    }

    /** Shulker boxes crafted for the order so far. */
    int crafted() {
        return craftedTotal;
    }

    /** The crafting the sorter ran for the order is over: this many boxes were made, and this is why not more. */
    void craftEnded(Crafter.Outcome outcome) {
        if (order == null) return;
        craftedTotal += outcome.crafted();
        Stash stash = owner.stash();
        if (stash != null && stash.activeOrder != null && stash.activeOrder.id.equals(order.id)) {
            stash.activeOrder.crafted = craftedTotal;
            stash.markDirty();
            stash.save();
        }
        if (outcome.failure() != null) {
            com.porter.qwerte.QwerteAddon.LOG.info("orders: crafting ended: {}", outcome.failure());
            // Nothing made: not tried again for this order
            if (outcome.crafted() == 0) craftFailure = outcome.failure();
            else problems.add("crafting stopped: " + outcome.failure());
        }
        epoch++;
    }

    /**
     * The craft step for a unit that needs an empty plain shulker box and has none, or null (crafting is off).
     * When crafting was tried and failed, the unit is skipped with the reason and an Idle is returned.
     */
    private Intent craftFor(Unit u, int pending) {
        if (!owner.craftingOn()) return null;
        String why = craftFailure != null ? craftFailure : u.craftTries >= MAX_CRAFT_TRIES ? "tries used up" : owner.craftBlocker();
        if (why != null) {
            skip("no empty shulker; couldn't craft one: " + why);
            return new Idle();
        }
        u.craftTries++;
        phase = "crafting " + (index + 1) + "/" + units.size();
        return new Craft(Math.max(1, pending));
    }

    /** Empty boxes still wanted by the units from here on: bins and plain empty lines. */
    private int pendingEmpties() {
        int n = 0;
        for (int i = index; i < units.size(); i++) {
            Unit u = units.get(i);
            if (u.kind == Kind.Bin && !u.built || u.kind == Kind.Empty && u.key.equals(SHULKER_BOX)) n++;
        }
        return n;
    }

    /** The server wants this order stopped. It is acted on at the next safe point. */
    boolean cancel(String id) {
        if (order == null || !order.id.equals(id)) return false;
        if (state == State.Running) cancelRequested = true;
        return true;
    }

    /**
     * Stops working on the order but keeps it resumable: the server still holds it, and a later claim carries on.
     */
    void park() {
        if (!running()) return;
        parked = true;
        if (!owner.gate() && !kits.active()) reset();
    }

    /** The server has the end of the order: it can be forgotten, and the next one claimed. */
    void ack(String id) {
        if (order == null || !order.id.equals(id) || state == State.Running) return;
        Stash stash = owner.stash();
        if (stash != null && stash.activeOrder != null && stash.activeOrder.id.equals(id)) {
            stash.activeOrder = null;
            stash.save();
        }
        reset();
    }

    /** The saved order the server should be asked about again, or null. */
    String resumeId() {
        Stash stash = owner.stash();
        return stash == null || stash.activeOrder == null ? null : stash.activeOrder.id;
    }

    /** Drops saved state of an order the server no longer holds for this bot. */
    void dropSaved(String keepId) {
        Stash stash = owner.stash();
        if (stash != null && stash.activeOrder != null && !stash.activeOrder.id.equals(keepId)) {
            stash.activeOrder = null;
            stash.save();
        }
    }

    Report report() {
        if (order == null) return null;
        // The wait for the re-read only runs out when it has stopped making progress: a big rescan can take long
        if (state == State.Running && !planned) {
            long now = System.currentTimeMillis();
            int stale = owner.staleCount();
            if (stale < lastStale) {
                lastStale = stale;
                rereadProgressAt = now;
            }
            if (now - rereadProgressAt > REREAD_TIMEOUT_MS) finish(State.Failed, "the containers could not be read again in time");
        }
        if (state == State.Running) {
            if (!owner.gate()) {
                long now = System.currentTimeMillis();
                if (pausedSince == 0) pausedSince = now;
                else if (now - pausedSince > PAUSE_TIMEOUT_MS) finish(State.Failed, "the sorter was stopped or paused for too long");
                if (cancelRequested && !kits.active()) finish(State.Cancelled, "cancelled");
            }
            else pausedSince = 0;
        }
        String status = switch (state) {
            case Running -> "in_progress";
            case Delivered -> "delivered";
            case Failed -> "failed";
            case Cancelled -> "cancelled";
        };
        String shown = state == State.Running && owner.rereading() ? "re-reading containers" : phase;
        return new Report(order.id, status, shown, Math.min(index, units.size()), units.size(), message, shulkersDelivered, new LinkedHashMap<>(delivered));
    }

    /** What the server asked for, per line key. */
    Map<String, Integer> requested() {
        return order == null ? Map.of() : order.requested();
    }

    String info() {
        return order == null ? null : state == State.Running ? "order " + index + "/" + units.size() : state.name().toLowerCase();
    }

    // Shulkers that belong to the order

    /**
     * A finished shulker the order is delivering, or the shulker it fetched. Nothing else may sort, unpack or file it
     * away. A part built shulker is never claimed here: the base being built is held by the kit builder, and shulkers
     * with ingredients must stay usable.
     */
    boolean claims(ItemStack stack) {
        if (!running() || index >= units.size() || stack.isEmpty() || !Utils.isShulker(stack.getItem())) return false;

        int tick = mc.player == null ? 0 : mc.player.tickCount;
        if (tick != claimTick || epoch != claimEpoch) {
            claimCache.clear();
            claimTick = tick;
            claimEpoch = epoch;
        }
        Boolean cached = claimCache.get(stack);
        if (cached == null) {
            cached = matches(units.get(index), stack);
            claimCache.put(stack, cached);
        }
        return cached;
    }

    private boolean matches(Unit u, ItemStack stack) {
        if (stack.isEmpty() || !Utils.isShulker(stack.getItem())) return false;
        // The shulker with the name the order gave it is still the order's
        boolean ours = isOurName(u, stack);
        switch (u.kind) {
            case Bin -> {
                if (stack.has(DataComponents.CUSTOM_NAME) && !ours) return false;
                if (u.kit != null) return u.kit.matchesFinished(Kits.inside(stack));
                return matchesLots(u, stack);
            }
            case Shulker -> {
                return keyMatches(u, stack, ours);
            }
            default -> {
                for (ItemStack s : Kits.inside(stack)) if (s != null && !s.isEmpty()) return false;
                return keyMatches(u, stack, ours);
            }
        }
    }

    private static String keyOf(Unit u, ItemStack stack) {
        return u.kind == Kind.Shulker ? StashIndex.groupKey(stack) : u.kind == Kind.Empty ? StashIndex.keyOf(stack) : null;
    }

    /**
     * The stack is the unit's by key: as planned, as seen after the rename, or (when it has the order's name) as it was
     * before the name: with its own name from before the rename, with none, or already the same as the name.
     */
    private boolean keyMatches(Unit u, ItemStack stack, boolean ours) {
        String k = keyOf(u, stack);
        if (k == null) return false;
        if (u.key.equals(k) || k.equals(u.namedKey)) return true;
        if (!ours) return false;
        ItemStack plain = stack.copy();
        if (u.origName != null) {
            plain.set(DataComponents.CUSTOM_NAME, u.origName);
            if (u.key.equals(keyOf(u, plain))) return true;
        }
        plain.remove(DataComponents.CUSTOM_NAME);
        return u.key.equals(keyOf(u, plain));
    }

    /** The shulker carries its name: noted, and kept in the saved plan. */
    private void noteNamed(Unit u, ItemStack stack) {
        u.nameDone = u.named = true;
        u.namedKey = keyOf(u, stack);
        saveUnit(u);
    }

    /** Keeps how naming went for this unit in the saved plan, for a resume. */
    private void saveUnit(Unit u) {
        Stash stash = owner.stash();
        int i = units.indexOf(u);
        if (stash == null || order == null || stash.activeOrder == null || !stash.activeOrder.id.equals(order.id) || i < 0 || i >= stash.activeOrder.units.size()) return;
        Stash.ActiveOrder.UnitPlan p = stash.activeOrder.units.get(i);
        p.nameDone = u.nameDone;
        p.named = u.named;
        p.nameFailure = u.unnamedReason == null ? "" : u.unnamedReason;
        p.namedKey = u.namedKey == null ? "" : u.namedKey;
        stash.markDirty();
        stash.save();
    }

    /** The id of the order in hand, or null. */
    String orderId() {
        return order == null ? null : order.id;
    }

    /** The stack carries the custom name this unit is to be given. */
    private static boolean isOurName(Unit u, ItemStack stack) {
        if (u.name == null || !Order.valid(u.name)) return false;
        net.minecraft.network.chat.Component custom = stack.get(DataComponents.CUSTOM_NAME);
        return custom != null && custom.getString().equals(u.name);
    }

    /** A finished shulker of a bin whose kit is not built yet (after a restart): exactly the planned totals. */
    private boolean matchesLots(Unit u, ItemStack stack) {
        if (u.lotTotals == null) {
            u.lotTotals = new HashMap<>();
            for (Order.Lot lot : u.lots) u.lotTotals.merge(lot.key(), lot.count(), Integer::sum);
        }
        Map<String, Integer> have = new HashMap<>();
        for (ItemStack s : Kits.inside(stack)) {
            if (s == null || s.isEmpty()) continue;
            String key = StashIndex.keyOf(s);
            if (key == null) return false;
            have.merge(key, s.getCount(), Integer::sum);
        }
        return have.equals(u.lotTotals);
    }

    private int claimedSlot(Unit u) {
        var inv = mc.player.getInventory();
        for (int slot : owner.carry()) {
            if (matches(u, inv.getItem(slot))) return slot;
        }
        return -1;
    }

    // Delivering, as seen by the delivery job

    /** The delivery container is open: how many of the current shulker it holds before anything is put in. */
    void deliveryOpened(ItemStack[] contents) {
        if (!running() || index >= units.size() || units.get(index).verifying) return;
        units.get(index).base = countIn(units.get(index), contents);
    }

    /** The delivery container had no room for the shulker. */
    void deliveryFull() {
        full = true;
    }

    /** The sorter has started putting another chest up for this order's current shulker: only runs really started count towards MAX_EXPANDS. */
    void expandStarted() {
        if (running() && index < units.size()) units.get(index).expands++;
    }

    private Box recheckBox(DeliveryRoom room) {
        Stash stash = owner.stash();
        if (stash == null || room.recheck() == null) return null;
        return stash.boxAt(new BlockPos(room.recheck().x(), room.recheck().y(), room.recheck().z()));
    }

    /** The shulker is no longer carried: the open container is checked for it. */
    void deliveryClosed(ItemStack[] contents) {
        if (!running() || index >= units.size()) return;
        Unit u = units.get(index);
        if (u.have && countIn(u, contents) > u.base) u.confirmed = true;
        // It reached the container with the name: it was named, whatever was noted about it before
        if (u.name != null && !u.named) for (ItemStack s : contents) if (matches(u, s) && isOurName(u, s)) u.named = true;
    }

    private int countIn(Unit u, ItemStack[] contents) {
        int n = 0;
        for (ItemStack s : contents) if (matches(u, s)) n++;
        return n;
    }

    // The next step

    Intent next() {
        if (!running()) return new Idle();
        if (parked) {
            reset();
            return new Idle();
        }
        if (cancelRequested) {
            finish(State.Cancelled, "cancelled");
            return new Idle();
        }
        if (!owner.gate() || mc.player == null) {
            long now = System.currentTimeMillis();
            if (pausedSince == 0) pausedSince = now;
            else if (now - pausedSince > PAUSE_TIMEOUT_MS) {
                finish(State.Failed, "the sorter was stopped or paused for too long");
                return new Idle();
            }
            if (kits.active()) kits.suspend("orders are paused: the sorter can't use shulkers right now.");
            phase = "paused";
            return new Idle();
        }
        pausedSince = 0;

        // Not planned yet: waits until the stash is read again, then plans from what is really there
        if (!planned) {
            if (owner.rereading()) {
                phase = "re-reading containers";
                return new Idle();
            }
            String why = completePlan();
            if (why != null) {
                finish(shulkersDelivered > 0 ? State.Delivered : State.Failed, why);
                return new Idle();
            }
        }

        // A saved kit being built has the kit builder; the order waits for it
        if (kits.active() && !orderKitActive()) {
            phase = "waiting for a kit";
            return new Idle();
        }

        // Crafting in hand carries on until it is over
        if (owner.crafter().active()) {
            phase = "crafting " + (index + 1) + "/" + units.size();
            return new Craft(0);
        }

        skips = 0;
        for (int guard = 0; guard < 64; guard++) {
            // Units that cannot be done are skipped a few per tick, not all at once
            if (skips > 4) return new Idle();
            if (index >= units.size()) {
                finishAll();
                return new Idle();
            }

            Box delivery = deliveryBox();
            if (delivery == null || delivery.unreachable) {
                finish(shulkersDelivered > 0 ? State.Delivered : State.Failed, delivery == null ? "the delivery container is gone" : "couldn't reach the delivery container");
                return new Idle();
            }

            Unit u = units.get(index);
            int slot = claimedSlot(u);

            if (slot != -1) {
                if (!u.have) {
                    u.have = true;
                    u.content = tally(u, mc.player.getInventory().getItem(slot));
                }
                // Back in the inventory: whatever was seen in the container was not it
                u.confirmed = false;
                u.verifying = false;
                // It already shows the name (an earlier try that looked like a failure, a resume): named
                if (u.name != null && !u.named && isOurName(u, mc.player.getInventory().getItem(slot))) noteNamed(u, mc.player.getInventory().getItem(slot));
                if (!u.nameDone) {
                    Intent rename = naming(u, slot);
                    if (rename != null) return rename;
                }
                if (full) {
                    full = false;
                    u.fullTries++;
                }
                // The delivery storage: which chest has room, a chest a player may have emptied to look at, another to put up, or wait
                DeliveryRoom room = owner.deliveryRoom();
                if (room.kind() == DeliveryRoom.Kind.RECHECK || room.kind() == DeliveryRoom.Kind.EXPAND) {
                    long at = System.currentTimeMillis();
                    if (at < spinHoldUntil) room = DeliveryRoom.waitFor("delivery storage full: could not start looking at or building a chest");
                    else if (++u.spins > MAX_SPINS) {
                        spinHoldUntil = at + SPIN_HOLD_MS;
                        u.spins = 0;
                        room = DeliveryRoom.waitFor("delivery storage full: could not start looking at or building a chest");
                    }
                }
                else if (room.kind() == DeliveryRoom.Kind.ROOM) {
                    u.expands = 0;
                    u.spins = 0;
                }
                if (room.kind() == DeliveryRoom.Kind.WAIT) {
                    String why = room.why() == null ? "delivery storage full" : room.why();
                    // Another reason is another chance to put a chest up
                    if (!why.equals(lastWaitWhy)) {
                        if (lastWaitWhy != null) u.expands = 0;
                        lastWaitWhy = why;
                    }
                }
                if (room.kind() == DeliveryRoom.Kind.RECHECK && room.recheck() != null) {
                    Box look = recheckBox(room);
                    if (look != null) {
                        phase = "checking delivery space";
                        return new Recheck(look);
                    }
                    // Not a reason to carry on (that would restart the wait): the chest is not there to look at
                    room = DeliveryRoom.waitFor("delivery storage full: the chest to look at is gone");
                }
                if (room.kind() == DeliveryRoom.Kind.EXPAND) {
                    if (u.expands < MAX_EXPANDS) {
                        phase = "adding delivery storage";
                        return new Expand();
                    }
                    room = DeliveryRoom.waitFor("delivery storage full: could not put another chest up");
                }
                SpaceWait.Decision wait = spaceWait.step(System.currentTimeMillis(), room, shulkersDelivered, units.size());
                if (wait != null) {
                    if (wait.timedOut()) {
                        finish(shulkersDelivered > 0 ? State.Delivered : State.Failed, wait.endMessage());
                        return new Idle();
                    }
                    phase = wait.phase();
                    return new Idle();
                }
                if (u.fullTries >= MAX_FULL_TRIES + Math.max(0, owner.deliveryChests() - 1)) {
                    finish(shulkersDelivered > 0 ? State.Delivered : State.Failed, "delivery container full (" + shulkersDelivered + "/" + units.size() + ")");
                    return new Idle();
                }
                if (++u.starts > MAX_STARTS) {
                    finish(shulkersDelivered > 0 ? State.Delivered : State.Failed, "couldn't put the shulker into the delivery container (" + shulkersDelivered + "/" + units.size() + " delivered)");
                    return new Idle();
                }
                phase = "delivering " + (index + 1) + "/" + units.size();
                Box chest = owner.deliveryChest(false);
                return new Deliver(chest != null ? chest : delivery);
            }

            if (u.have) {
                // It left the inventory: it counts only if the delivery container was seen to hold it
                if (u.confirmed) {
                    for (var e : u.content.entrySet()) delivered.merge(e.getKey(), e.getValue(), Integer::sum);
                    shulkersDelivered++;
                    if (u.name != null && !u.named) {
                        unnamed++;
                        String reason = u.unnamedReason == null ? "naming didn't finish" : u.unnamedReason;
                        if (!unnamedReasons.contains(reason)) unnamedReasons.add(reason);
                    }
                    advance();
                }
                else if (!u.verified) {
                    // Not seen in the container yet: look once more, in a freshly opened window, before giving up on it
                    u.verified = true;
                    u.verifying = true;
                    phase = "checking delivery " + (index + 1) + "/" + units.size();
                    Box chest = owner.deliveryChest(true);
                    return new Deliver(chest != null ? chest : delivery);
                }
                else {
                    skip("a shulker left my inventory but wasn't seen in the delivery container");
                }
                continue;
            }

            if (u.kind == Kind.Bin) {
                if (u.built) {
                    skip("lost a finished shulker");
                    continue;
                }
                if (!kits.active()) {
                    // No empty shulker to build in: one is crafted, when crafting is on
                    if (owner.craftingOn() && owner.crafter().craftedBase() < 0 && kits.emptyShulkers().isEmpty()) {
                        Intent craft = craftFor(u, pendingEmpties());
                        if (craft instanceof Idle) continue;
                        if (craft != null) return craft;
                    }
                    if (u.kit == null) u.kit = buildKit(u);
                    epoch++;
                    if (u.kit == null) {
                        skip("the items for a shulker are no longer in the stash");
                        continue;
                    }
                    if (++u.begins > 3 || !kits.beginOrder(u.kit)) {
                        skip("couldn't start building a shulker (" + u.kit.name + ")");
                        continue;
                    }
                }
                else if (kits.kit() != u.kit) {
                    // Left over from another unit
                    kits.dropOrderKit();
                    continue;
                }
                phase = "building " + (index + 1) + "/" + units.size();
                KitBuilder.Intent step = kits.next();
                if (step instanceof KitBuilder.Done) {
                    kits.finish();
                    u.built = true;
                    epoch++;
                    continue;
                }
                if (step instanceof KitBuilder.Abort abort) {
                    kits.suspend(abort.reason());
                    // A base that could not be taken: another is tried (a few times), nothing is given up on yet
                    if (abort.reason().startsWith("couldn't take a shulker")) continue;
                    // Containers still to be read again may hold what is missing: wait for them
                    if (owner.rereading()) {
                        u.kit = null;
                        u.begins = 0;
                        phase = "re-reading containers";
                        return new Idle();
                    }
                    // Items missing from where they were saved (the containers were read again since): planned once more from
                    // what the stash holds now, trimmed to it, before the unit is given up on
                    if (!u.replanned) {
                        u.replanned = true;
                        u.kit = null;
                        u.begins = 0;
                        problems.add("replanned a shulker: " + abort.reason());
                        epoch++;
                        continue;
                    }
                    skip(abort.reason());
                    continue;
                }
                return new Build(step);
            }

            if (u.fetches >= 2) {
                skip("couldn't take the shulker out of the stash");
                continue;
            }
            Fetch fetch = locate(u);
            if (fetch == null && u.kind == Kind.Empty && u.key.equals(SHULKER_BOX)) {
                Intent craft = craftFor(u, pendingEmpties());
                if (craft instanceof Idle) continue;
                if (craft != null) return craft;
            }
            if (fetch == null) {
                skip(u.kind == Kind.Shulker ? "a packed shulker is no longer in the stash" : "no empty shulker of that kind is left");
                continue;
            }
            u.fetches++;
            phase = "fetching " + (index + 1) + "/" + units.size();
            return fetch;
        }
        return new Idle();
    }

    /**
     * The rename step for a finished shulker that is to be named, or null when it is named already, is not to be named,
     * or can't be. Naming never fails or pauses the order: such a shulker is delivered unnamed, and the reason is noted.
     */
    private Intent naming(Unit u, int slot) {
        if (u.name == null) {
            u.nameDone = true;
            return null;
        }
        ItemStack stack = mc.player.getInventory().getItem(slot);
        if (isOurName(u, stack)) {
            noteNamed(u, stack);
            return null;
        }
        String why = !Order.valid(u.name) ? "a name that breaks the rules" : null;
        // No anvil set or remembered, or it is gone: one is found in the area, or taken from the stash and placed (once per
        // shulker). When that fails the shulker goes unnamed with the reason, and so do the rest of the order's
        if (why == null && owner.anvilMissing()) {
            if (anvilNone || u.anvilTries >= 1) why = AnvilFinder.NONE;
            else {
                u.anvilTries++;
                phase = "finding an anvil " + (index + 1) + "/" + units.size();
                return new FindAnvil();
            }
        }
        if (why == null) why = owner.anvilProblem();
        BlockPos anvil = owner.anvilPos();
        if (why == null && anvil == null) why = AnvilFinder.NONE;
        if (why == null && u.nameTries >= MAX_NAME_TRIES) why = "naming didn't finish";
        if (why != null) {
            u.nameDone = true;
            u.unnamedReason = why;
            saveUnit(u);
            com.porter.qwerte.QwerteAddon.LOG.info("orders: shulker {} goes unnamed: {}", index + 1, why);
            return null;
        }
        u.nameTries++;
        u.origName = stack.get(DataComponents.CUSTOM_NAME);
        phase = "naming " + (index + 1) + "/" + units.size();
        return new Rename(anvil, u.name, slot);
    }

    /** The rename of the current shulker is given up on for this reason: it is delivered unnamed. */
    void nameFailed(String reason) {
        if (!running() || index >= units.size()) return;
        Unit u = units.get(index);
        u.nameDone = true;
        u.unnamedReason = reason;
        saveUnit(u);
        epoch++;
    }

    private boolean orderKitActive() {
        return kits.kit() != null && kits.kit().exact;
    }

    /** The variant to take {@code want} from: the smallest with that much left, else the one with the most left; null if none. */
    private static Variant bestFit(List<Variant> variants, Map<Variant, Long> used, int want) {
        Variant fit = null, biggest = null;
        long fitRest = Long.MAX_VALUE, biggestRest = 0;
        for (Variant v : variants) {
            long rest = v.count - used.getOrDefault(v, 0L);
            if (rest <= 0) continue;
            if (rest >= want && rest < fitRest) {
                fit = v;
                fitRest = rest;
            }
            if (rest > biggestRest) {
                biggest = v;
                biggestRest = rest;
            }
        }
        return fit != null ? fit : biggest;
    }

    /** Slot loads for a line of {@code total} items: one stack at a time, each from the best fitting variant. */
    private static List<Order.Lot> allocate(String key, long total, int max, List<Variant> variants, Map<Variant, Long> used) {
        List<Order.Lot> out = new ArrayList<>();
        long remaining = total;
        while (remaining > 0) {
            int want = (int) Math.min(remaining, max);
            while (want > 0) {
                Variant v = bestFit(variants, used, want);
                if (v == null) return out;
                int take = (int) Math.min(want, v.count - used.getOrDefault(v, 0L));
                used.merge(v, (long) take, Long::sum);
                out.add(new Order.Lot(key, take));
                want -= take;
                remaining -= take;
            }
        }
        return out;
    }

    /** The kit for a planned shulker, from what the stash holds right now: lots are trimmed to what is really there. */
    private Kit buildKit(Unit u) {
        Stash stash = owner.stash();
        if (stash == null) return null;
        Set<String> keys = new HashSet<>();
        for (Order.Lot lot : u.lots) keys.add(lot.key());
        Survey survey = survey(stash, keys);

        // Each slot is filled from one exact variant: the smallest that has enough for the load, else the biggest; a load
        // that outgrows it carries on in another slot from the next variant. What does not fit in the 27 slots, or is not
        // there, is left out and reported, not moved to another shulker, so the shulkers keep the plan
        Map<Variant, Long> used = new java.util.IdentityHashMap<>();
        ItemStack[] stacks = new ItemStack[Kit.SLOTS];
        int n = 0;
        Map<String, Integer> dropped = new LinkedHashMap<>();
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (Order.Lot lot : u.lots) {
            int want = lot.count();
            List<Variant> variants = survey.variants().getOrDefault(lot.key(), List.of());
            while (want > 0) {
                Variant best = bestFit(variants, used, want);
                if (best == null) {
                    missing.merge(lot.key(), want, Integer::sum);
                    break;
                }
                if (n >= Kit.SLOTS) {
                    dropped.merge(lot.key(), want, Integer::sum);
                    break;
                }
                long rest = best.count - used.getOrDefault(best, 0L);
                int take = (int) Math.min(Math.min(want, rest), Math.max(1, best.sample.getMaxStackSize()));
                used.merge(best, (long) take, Long::sum);
                stacks[n++] = best.sample.copyWithCount(take);
                want -= take;
            }
        }
        for (var e : dropped.entrySet()) problems.add(StashIndex.nameOf(e.getKey()) + ": " + e.getValue() + " didn't fit in the shulker (mixed variants)");
        for (var e : missing.entrySet()) {
            String reason = survey.why().get(e.getKey());
            problems.add(StashIndex.nameOf(e.getKey()) + ": " + e.getValue() + " missing, not enough of any one variant left" + (reason == null ? "" : " (some " + reason + ")"));
        }
        if (n == 0) return null;

        // What the kit builder can really take may differ from this survey (it has its own scan of the same stash): a lot it
        // cannot supply is dropped, so the lots that can be supplied still pack and ship short, with a note
        Kit probe = new Kit("order " + order.id + " #" + (index + 1), stacks, true);
        Map<Kit.Key, Integer> supply = kits.supply(probe);
        Map<Kit.Key, Integer> left = new HashMap<>(supply);
        ItemStack[] trimmed = new ItemStack[Kit.SLOTS];
        int m = 0;
        Map<String, Integer> cut = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            ItemStack st = stacks[i];
            Kit.Key key = probe.key(st);
            int have = left.getOrDefault(key, 0);
            int take = Math.min(st.getCount(), have);
            left.put(key, have - take);
            if (take < st.getCount()) cut.merge(StashIndex.nameOf(StashIndex.keyOf(st)), st.getCount() - take, Integer::sum);
            if (take > 0) trimmed[m++] = st.copyWithCount(take);
        }
        for (var e : cut.entrySet()) problems.add(e.getKey() + ": " + e.getValue() + " couldn't be taken out of the stash, shipped short");
        if (m == 0) return null;
        return m == n && cut.isEmpty() ? probe : new Kit(probe.name, trimmed, true);
    }

    private void skip(String why) {
        skips++;
        problems.add(why);
        advance();
    }

    private void advance() {
        index++;
        epoch++;
        Stash stash = owner.stash();
        if (stash != null && stash.activeOrder != null && order != null && stash.activeOrder.id.equals(order.id)) {
            stash.activeOrder.done = index;
            stash.activeOrder.shulkersDelivered = shulkersDelivered;
            stash.activeOrder.unnamed = unnamed;
            stash.activeOrder.unnamedReasons.clear();
            stash.activeOrder.unnamedReasons.addAll(unnamedReasons);
            stash.activeOrder.delivered.clear();
            stash.activeOrder.delivered.putAll(delivered);
            stash.markDirty();
            stash.save();
        }
    }

    private Map<String, Integer> tally(Unit u, ItemStack stack) {
        Map<String, Integer> out = new HashMap<>();
        if (u.kind != Kind.Bin) {
            out.put(u.key, 1);
            return out;
        }
        for (ItemStack s : Kits.inside(stack)) {
            if (s == null || s.isEmpty()) continue;
            String key = StashIndex.keyOf(s);
            if (key != null) out.merge(key, s.getCount(), Integer::sum);
        }
        return out;
    }

    /** The nearest shulker in the stash that is the one this unit asks for. */
    private Fetch locate(Unit u) {
        Stash stash = owner.stash();
        if (stash == null) return null;

        Fetch best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.stale || !owner.usable(box)) continue;
            for (int i = 0; i < box.contents.length; i++) {
                ItemStack s = box.contents[i];
                if (s.isEmpty() || !Utils.isShulker(s.getItem()) || !matches(u, s)) continue;
                double d = owner.distance(box);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = new Fetch(box, i);
                }
            }
        }
        return best;
    }

    /**
     * What the kit builder could take from, for the wanted keys: the same sources it uses (not drop-offs, kit chests,
     * whole-shulker chests, boxes given up on, or named shulkers), loose and inside shulkers. Stacks holding items
     * (bundles, other containers) are told apart only by their contents, which a key cannot say, so they are left out.
     */
    private Survey survey(Stash stash, Set<String> wanted) {
        int tick = mc.player == null ? -1 : mc.player.tickCount;
        if (lastSurvey != null && tick == lastSurveyTick && wanted.equals(lastSurveyWanted)) return lastSurvey;
        Survey result = surveyNow(stash, wanted);
        lastSurvey = result;
        lastSurveyTick = tick;
        lastSurveyWanted = new HashSet<>(wanted);
        return result;
    }

    private Survey surveyNow(Stash stash, Set<String> wanted) {
        Map<String, ItemStack> samples = new HashMap<>();
        Map<String, Long> available = new HashMap<>();
        Set<String> filled = new HashSet<>();
        Set<String> staleSeen = new HashSet<>();
        Map<String, String> why = new HashMap<>();
        if (wanted.isEmpty()) return new Survey(samples, available, filled, new HashSet<>(), new HashMap<>(), why);
        Map<String, Map<RawKey, Variant>> raw = new HashMap<>();

        // Only stacks of the wanted items are worth working a key out for
        Set<String> ids = new HashSet<>();
        for (String key : wanted) {
            int hash = key.indexOf('#');
            ids.add(hash < 0 ? key : key.substring(0, hash));
        }

        boolean named = owner.namedShulkers();
        for (Box box : stash.boxes) {
            // The same sources the kit builder takes from: kit chests and chests for whole shulkers only when named shulkers
            // may be used (an order asks for the item on purpose)
            String skip = null;
            if (box.foreign) skip = "in a container outside the stash area";
            else if (box.excluded) skip = "in an excluded container";
            else if (box.delivery) skip = "in the delivery container";
            else if (box.dropOff) skip = "in a drop-off";
            else if (box.unreachable || !owner.usable(box)) skip = "in a chest I couldn't open";
            else if (!box.known()) skip = "in a container not read yet";
            else if (!named && (Kits.isKitBox(box) || owner.keepWhole(box))) skip = "in a kit chest";
            if (skip != null) {
                if (box.contents != null) for (ItemStack s : box.contents) why(why, wanted, ids, s, skip);
                continue;
            }

            // Boxes not read again yet only tell which items are waiting in them
            Map<String, ItemStack> targetSamples = box.stale ? new HashMap<>() : samples;
            Map<String, Long> targetAvailable = box.stale ? new HashMap<>() : available;
            Map<String, Map<RawKey, Variant>> targetRaw = box.stale ? new HashMap<>() : raw;
            for (ItemStack s : box.contents) {
                if (s.isEmpty()) continue;
                count(targetSamples, targetAvailable, targetRaw, filled, wanted, ids, s);
                if (Utils.isShulker(s.getItem())) {
                    if (s.has(DataComponents.CUSTOM_NAME) && !named) why(why, wanted, ids, s, "in a named shulker (use-named-shulkers is off)");
                    else if (Kits.claims(s) || claims(s) || kits.heldKit(s) != null) why(why, wanted, ids, s, "in a shulker claimed by a saved kit");
                    else {
                        for (ItemStack in : Kits.inside(s)) if (in != null && !in.isEmpty()) count(targetSamples, targetAvailable, targetRaw, filled, wanted, ids, in);
                    }
                }
            }
            if (box.stale) staleSeen.addAll(targetAvailable.keySet());
        }
        staleSeen.removeAll(available.keySet());
        Map<String, List<Variant>> variants = new HashMap<>();
        for (var e : raw.entrySet()) {
            List<Variant> list = new ArrayList<>(e.getValue().values());
            list.sort((a, b) -> Long.compare(b.count, a.count));
            variants.put(e.getKey(), list);
        }
        return new Survey(samples, available, filled, staleSeen, variants, why);
    }

    /** Notes why wanted items in this stack (and in the shulker, if it is one) cannot be used. */
    private static void why(Map<String, String> why, Set<String> wanted, Set<String> ids, ItemStack s, String reason) {
        if (s.isEmpty()) return;
        if (ids.contains(idOf(s))) {
            String key = StashIndex.keyOf(s);
            if (key != null && wanted.contains(key)) why.putIfAbsent(key, reason);
        }
        if (Utils.isShulker(s.getItem())) {
            for (ItemStack in : Kits.inside(s)) {
                if (in == null || in.isEmpty() || !ids.contains(idOf(in))) continue;
                String key = StashIndex.keyOf(in);
                if (key != null && wanted.contains(key)) why.putIfAbsent(key, reason);
            }
        }
    }

    private static final Map<net.minecraft.world.item.Item, String> ITEM_IDS = new HashMap<>();

    private static String idOf(ItemStack s) {
        return ITEM_IDS.computeIfAbsent(s.getItem(), i -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i).toString());
    }

    private static void count(Map<String, ItemStack> samples, Map<String, Long> available, Map<String, Map<RawKey, Variant>> raw, Set<String> filled, Set<String> wanted, Set<String> ids, ItemStack s) {
        if (!ids.contains(idOf(s))) return;
        String key = StashIndex.keyOf(s);
        if (key == null || !wanted.contains(key)) return;

        if (!Utils.isShulker(s.getItem()) && holdsItems(s)) {
            filled.add(key);
            return;
        }
        samples.putIfAbsent(key, s.copyWithCount(1));
        available.merge(key, (long) s.getCount(), Long::sum);

        ItemStack one = s.copyWithCount(1);
        Variant v = raw.computeIfAbsent(key, k -> new HashMap<>()).computeIfAbsent(new RawKey(one.getItem(), one.getComponents()), k -> new Variant(one));
        v.count += s.getCount();
    }

    private static boolean holdsItems(ItemStack s) {
        var container = s.get(DataComponents.CONTAINER);
        if (container != null && container.nonEmptyItems().iterator().hasNext()) return true;
        var bundle = s.get(DataComponents.BUNDLE_CONTENTS);
        return bundle != null && !bundle.isEmpty();
    }

    // Ending

    private void finishAll() {
        Map<String, Integer> asked = order.requested();
        List<String> shortLines = new ArrayList<>();
        for (var e : asked.entrySet()) {
            int got = delivered.getOrDefault(e.getKey(), 0);
            if (got < e.getValue()) shortLines.add(StashIndex.nameOf(e.getKey().startsWith("s:") ? "minecraft:shulker_box" : e.getKey()) + " " + got + "/" + e.getValue());
        }

        String note = shortLines.isEmpty() ? "" : "delivered less than asked: " + String.join(", ", shortLines.size() > 6 ? shortLines.subList(0, 6) : shortLines) + (shortLines.size() > 6 ? ", ..." : "");
        if (!problems.isEmpty()) note += (note.isEmpty() ? "" : "; ") + String.join(", ", problems.size() > 3 ? problems.subList(0, 3) : problems);
        boolean anything = shulkersDelivered > 0;
        finish(anything ? State.Delivered : State.Failed, anything ? note : (note.isEmpty() ? "nothing could be delivered" : "nothing could be delivered: " + note));
    }

    private void finish(State end, String text) {
        if (order == null || state != State.Running) return;
        kits.dropOrderKit();
        state = end;
        endCrafting("the order ended");
        owner.crafter().release();
        if (craftedTotal > 0) text = (text.isEmpty() ? "" : text + "; ") + "crafted " + craftedTotal + " Shulker Box";
        String unnamedNote = Order.unnamedNote(unnamed, unnamedReasons);
        if (!unnamedNote.isEmpty() && !text.contains(unnamedNote)) text = text.isEmpty() ? unnamedNote : text + "; " + unnamedNote;
        text = cut(text);
        message = text;
        phase = end.name().toLowerCase();
        epoch++;
        say("Order " + order.id + " " + phase + (text.isEmpty() ? "." : ": " + text));
    }

    /** The message cut at 300 characters on a word, as the plugin does. */
    private static String cut(String note) {
        if (note.length() <= MESSAGE_MAX) return note;
        int cut = note.lastIndexOf(' ', MESSAGE_MAX - 4);
        return note.substring(0, cut > MESSAGE_MAX / 2 ? cut : MESSAGE_MAX - 4) + " ...";
    }

    private void say(String text) {
        com.porter.qwerte.QwerteAddon.LOG.info("orders: {}", text);
        if (chat) owner.say(text);
    }
}
