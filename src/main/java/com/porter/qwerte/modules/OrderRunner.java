package com.porter.qwerte.modules;

import com.porter.qwerte.craft.AnvilFinder;
import com.porter.qwerte.craft.CraftBatch;
import com.porter.qwerte.craft.CraftCapability;
import com.porter.qwerte.craft.CraftCharge;
import com.porter.qwerte.craft.CraftEnd;
import com.porter.qwerte.craft.CraftFills;
import com.porter.qwerte.craft.CraftLedger;
import com.porter.qwerte.craft.CraftLots;
import com.porter.qwerte.craft.CraftOrder;
import com.porter.qwerte.craft.CraftPlan;
import com.porter.qwerte.craft.CraftPlanner;
import com.porter.qwerte.craft.CraftResume;
import com.porter.qwerte.craft.Recipes;
import com.porter.qwerte.stash.DeliveryRoom;
import com.porter.qwerte.stash.Kit;
import com.porter.qwerte.stash.Kits;
import com.porter.qwerte.stash.Mending;
import com.porter.qwerte.stash.RepairEstimate;
import com.porter.qwerte.stash.RepairRun;
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

        /** The repair job is given up on (a repair the sorter never reported on): it puts the gear back and ends. */
        default void stopRepair() {}

        /** The delivery chest the next shulker goes into: the lowest with room; with {@code verify} the one the last went into. */
        Box deliveryChest(boolean verify);

        /** Free carry slots now. */
        default int freeCarryCount() {
            return 0;
        }

        /** Craft orders (0.4.0-dev.18): the setting and its caps. */
        default int craftMaxFills() {
            return 2048;
        }

        default int craftMaxPerHour() {
            return 4096;
        }

        /** Why the website's crafting table can't be used now (a {@link CraftOrder} reason: no_table, table_blocked, table_missing), or null. */
        default String craftTableProblem() {
            return null;
        }

        /** A table trouble remembered for this order is forgotten, so a chunk that met it is tried once more. */
        default void retryTable() {}

        /** A crafting table is being gathered, crafted or placed for the order: the chunk waiting for it is not stuck. */
        default boolean craftBusy() {
            return false;
        }
    }

    sealed interface Intent permits Idle, Build, Fetch, Deliver, Rename, Craft, FindAnvil, Expand, Recheck, Repair, CraftChunk {}

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

    /**
     * Make {@code count} of {@code key} for a craft line (0.4.0-dev.18): one chunk of the server's plan, from its sub-plan (a legacy plan for the
     * crafter), with the carry slots of crafted surplus the plan may use. The sorter runs it at the website's table and tells the end with
     * {@link #craftEnded}. Nothing is planned again: the plan is the order's.
     */
    record CraftChunk(String key, int count, CraftPlanner.Plan plan, Set<Integer> carried) implements Intent {}

    /** Look for an anvil in the stash area, or take one from the stash and place it. The outcome is told with {@link #anvilFound}. */
    record FindAnvil() implements Intent {}

    /** Every delivery chest is full: put another double chest up above the storage. What came of it shows in the next room. */
    record Expand() implements Intent {}

    /** Open this delivery chest, found full before and perhaps emptied since, and read what it holds. */
    record Recheck(Box box) implements Intent {}

    /**
     * Repair the damaged Mending items now carried (the index keys, in order) with the XP bottles carried too. The sorter runs it and
     * tells the outcome with {@link #repairEnded}; it always ends, whatever happens.
     */
    record Repair(List<String> keys) implements Intent {}

    enum State { Running, Delivered, Failed, Cancelled }

    /** A snapshot of the order for the server. */
    record Report(String id, String status, String phase, int done, int total, String message, int shulkers, Map<String, Integer> delivered,
                  RepairEstimate.Result repair, com.google.gson.JsonObject craft) {
        Report(String id, String status, String phase, int done, int total, String message, int shulkers, Map<String, Integer> delivered) {
            this(id, status, phase, done, total, message, shulkers, delivered, null, null);
        }

        Report(String id, String status, String phase, int done, int total, String message, int shulkers, Map<String, Integer> delivered,
               RepairEstimate.Result repair) {
            this(id, status, phase, done, total, message, shulkers, delivered, repair, null);
        }

        boolean terminal() {
            return !status.equals("in_progress");
        }
    }

    private enum Kind { Shulker, Empty, Bin }

    private enum RState { NEW, GATHER, THROW, DONE }

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
        // Repair of this unit's items (see repairStep)
        RState rstate = RState.NEW;
        Kit repairKit;
        List<Integer> repairPending = new ArrayList<>();
        List<Integer> repairBatch = new ArrayList<>();
        // Damage of each pending lot (by lot index), and the keys the lots were asked for with (null until a lot's key changes)
        Map<Integer, Integer> repairDamage = new HashMap<>();
        List<String> asked;
        int repairBegins, repairBottleNeed;
        // Lots (by index) whose items were gathered for a repair: they are carried, not in the stash any more
        Set<Integer> carriedLots = new HashSet<>();
        // Index key of a repaired item (its new key) -> the key the order asked for, for this unit only
        Map<String, String> repairedAs = new HashMap<>();
        long repairAt;
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
    /** A repair job the sorter never reports on is given up on after this long. */
    private static final long REPAIR_LOST_MS = 6 * 60 * 1000L;
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
    // Repairs (REPAIR-CONTRACT.md): the keys wanted for repair, how it went, why it stopped (null: not), and the throwing time used
    private final Set<String> repairKeys = new HashSet<>();
    private RepairRun.Tally repairTally = new RepairRun.Tally();
    private String repairStopped;
    private int repairTicksUsed, repairRetriesUsed;
    // The keys of item lines with their own repair toggle (they are always counted); the others are wanted only when damaged
    private final Set<String> repairExplicit = new HashSet<>();
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

    // Craft orders (0.4.0-dev.18, CRAFT-CONTRACT.md "Bot execution"): the order's craft ledger (null: no craft lines), the chunk being made, the
    // carry slots the order keeps (crafted loads waiting for their bin, surplus between chunks) or just carries (unused ingredients), the
    // fills of the last hour, and the end-of-order wait for the put-back
    private CraftLedger ledger;
    private String craftRefusal;
    private Chunk chunk;
    private int tableTries, interruptTries;
    private final Map<Integer, String> craftLotSlots = new LinkedHashMap<>(), craftSurplus = new LinkedHashMap<>(), craftLeftover = new LinkedHashMap<>();
    private final CraftFills craftFills = new CraftFills();
    private CraftEnd craftEnd;
    private boolean craftFinal, purgedCraft;

    /** A craft chunk was asked of the sorter and has not ended: a table may be on its way for it. */
    boolean craftChunkPending() {
        return chunk != null && running();
    }
    private final com.porter.qwerte.craft.CraftBreaker breaker = new com.porter.qwerte.craft.CraftBreaker();
    private Stash fillsStash;
    private String craftEndReason = CraftOrder.INTERRUPTED;
    private com.google.gson.JsonObject craftResult;
    private static final Idle CONTINUE = new Idle();
    // A chunk the sorter has not started: walking to a table, gathering and placing one may take a while
    private static final long CHUNK_START_MS = 10 * 60_000L;


    /** The chunk in hand: which load of which unit, what the batch chose, the plan, and when the sorter was asked. */
    private static final class Chunk {
        final Unit unit;
        final int lot;
        final CraftBatch.Choice choice;
        final CraftChunk intent;
        // Unused ingredients of earlier chunks this one took over (they were not charged when they were put aside)
        final Map<String, Long> adopted;
        // The slots those unused ingredients were in: what is left in them (and in the slots gathered into) is stock, not something crafted
        final Set<Integer> adoptedSlots;
        long issuedAt = System.currentTimeMillis();

        Chunk(Unit unit, int lot, CraftBatch.Choice choice, CraftChunk intent, Map<String, Long> adopted, Set<Integer> adoptedSlots) {
            this.unit = unit;
            this.lot = lot;
            this.choice = choice;
            this.intent = intent;
            this.adopted = adopted;
            this.adoptedSlots = adoptedSlots;
        }
    }

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
        clearRepair();
        clearCraft();
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

    /** Ticks of throwing used so far by this order's repairs. */
    int repairTicks() {
        return repairTicksUsed;
    }

    /** Retries of the throwing used so far by this order (one is allowed per order). */
    int repairRetries() {
        return repairRetriesUsed;
    }

    private void clearRepair() {
        repairKeys.clear();
        repairTally = new RepairRun.Tally();
        repairStopped = null;
        repairTicksUsed = 0;
        repairRetriesUsed = 0;
        repairExplicit.clear();
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
        // A craft block that is missing, can't be read, or has no craft line is refused (the server then releases its reservations). Everything else
        // about the craft lines (the bot's own table, the caps) is checked in the plan and makes the lines short instead
        if ((o.hasCraft() || o.craft != null) && (o.craft == null || o.craft.malformed() || !o.hasCraft())) return "the craft block can't be read";
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
        clearRepair();
        clearCraft();
        for (Order.Line l : o.lines) {
            if (!Order.ITEM.equals(l.type())) continue;
            if (l.repair()) {
                repairExplicit.add(l.key());
                repairKeys.add(l.key());
            }
            else if (o.repairAll) repairKeys.add(l.key());
        }
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
                clearCraft();
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
            // A save of a craft order that lists no craft states is from before they were kept: its loads of a craft line's item (that no item line
            // asks for) are dropped, never taken from the stash as plain items, and its ledger is not trusted
            Set<String> craftOnly = new HashSet<>();
            for (Order.Line l : o.craftLines()) craftOnly.add(l.key());
            for (Order.Line l : o.lines) if (!l.type().equals(Order.CRAFT)) craftOnly.remove(l.key());
            boolean oldFormat = false;
            for (Stash.ActiveOrder.UnitPlan p : saved.units) {
                List<Order.Lot> lots = new ArrayList<>();
                boolean listed = p.lotCraft.size() == p.lotKeys.size();
                for (int i = 0; i < p.lotKeys.size(); i++) {
                    int craftState = listed ? p.lotCraft.get(i) : 0;
                    int count = p.lotCounts.get(i);
                    // A state that is none of the three is a damaged record: the load is dropped. A save from before the states were kept
                    // (none listed) has its craft lines' loads still to make, never taken as plain items from the stash
                    if (craftState < Order.Lot.NONE || craftState > Order.Lot.CRAFTED) {
                        craftState = Order.Lot.NONE;
                        count = 0;
                    } else if (CraftLots.oldFormatLot(craftState, p.lotKeys.get(i), craftOnly, !saved.craftLedger.isEmpty())) {
                        // Detected by content: a plain load of an item only a craft line asks for, in a save that has a craft ledger, is from before the
                        // states were kept (or damaged). It is dropped, never taken from the stash as a plain item, and the ledger is not trusted
                        count = 0;
                        oldFormat = true;
                    }
                    lots.add(new Order.Lot(p.lotKeys.get(i), count, craftState));
                }
                Unit restored = new Unit(p.kind == 0 ? Kind.Shulker : p.kind == 1 ? Kind.Empty : Kind.Bin, p.key, lots);
                restored.nameDone = p.nameDone;
                restored.named = p.named;
                restored.unnamedReason = p.nameFailure == null || p.nameFailure.isEmpty() ? null : p.nameFailure;
                restored.namedKey = p.namedKey == null || p.namedKey.isEmpty() ? null : p.namedKey;
                // Lots repaired before a restart carry their new key; the key the order asked for is kept to count the delivery
                if (!p.lotAsked.isEmpty() && p.lotAsked.size() == p.lotKeys.size()) {
                    restored.asked = new ArrayList<>(p.lotAsked);
                    for (int i = 0; i < p.lotKeys.size(); i++) if (!p.lotKeys.get(i).equals(p.lotAsked.get(i))) restored.repairedAs.put(p.lotKeys.get(i), p.lotAsked.get(i));
                }
                restored.carriedLots.addAll(p.lotCarried);
                plan.add(restored);
            }
            start = Math.min(saved.done, plan.size());
            // What was delivered before counts even if the rest cannot be done now
            shulkersDelivered = saved.shulkersDelivered;
            delivered.putAll(saved.delivered);
            unnamed = saved.unnamed;
            unnamedReasons.addAll(saved.unnamedReasons);
            craftedTotal = saved.crafted;
            loadRepair(saved);
            // The craft ledger of the order comes back with what it made and spent; the stacks it carried are set aside again
            CraftLots.Prepared prep = CraftLots.prepare(o, tableOrEmpty(), owner.craftMaxFills(), Crafter::stackOf);
            if (prep != null) {
                ledger = prep.ledger();
                craftRefusal = prep.refusal();
                // A ledger that is missing or fails any check is never replaced by a fresh one with a full budget: the craft lines are refused
                if (craftRefusal == null && (oldFormat || !restoreLedger(saved.craftLedger))) {
                    ledger = CraftLedger.refused(o.craftLines(), tableOrEmpty(), owner.craftMaxFills(), CraftOrder.RECIPE_MISMATCH);
                    craftRefusal = "the saved craft ledger is missing or damaged";
                    com.porter.qwerte.QwerteAddon.LOG.warn("orders: order {}: {}", o.id, craftRefusal);
                }
                if (craftRefusal == null) resumeCraft(plan.subList(start, plan.size()));
            }
            // A load still to make is never taken from the stash as a plain item: with no usable ledger every one of them is short
            if (craftRefusal != null || ledger == null) purgedCraft = purgeCraftLots(plan);
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
        if (purgedCraft) for (Unit u : units) saveLots(u);
        purgedCraft = false;
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
                    if (ledger != null) p.lotCraft.add(lot.craftState());
                }
                active.units.add(p);
            }
            if (names != null) {
                active.namesMode = names.mode();
                active.namesAll = names.all();
                active.namesEach.addAll(names.each());
            }
            if (ledger != null) active.craftLedger = ledger.toSaved().toString();
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
                if (repairExplicit.contains(l.key())) repairTally.skip(l.key(), RepairEstimate.NOT_SINGLE, l.count());
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

        // The craft lines join the packer as loads of their result (CRAFT-CONTRACT.md "Bot execution", point 2); any failure of the check makes
        // every craft line short with recipe_mismatch and nothing is crafted
        CraftLots.Prepared prep = CraftLots.prepare(o, tableOrEmpty(), owner.craftMaxFills(), Crafter::stackOf);
        if (prep != null) {
            ledger = prep.ledger();
            craftRefusal = prep.refusal();
            if (prep.refused()) com.porter.qwerte.QwerteAddon.LOG.warn("orders: the craft lines of order {} are not made: {}", o.id, prep.refusal());
            else {
                stacks += prep.lots().size();
                if (stacks > (long) Order.MAX_BINS * Order.SLOTS) return "too big: that is more than " + Order.MAX_BINS + " shulkers";
                lots.addAll(prep.lots());
            }
        }

        List<List<Order.Lot>> bins = Order.pack(lots);
        if (shulkerUnits.size() + emptyUnits.size() + bins.size() > Order.MAX_BINS) return "too big: that is more than " + Order.MAX_BINS + " shulkers";

        plan.addAll(shulkerUnits);
        plan.addAll(emptyUnits);
        for (List<Order.Lot> bin : bins) plan.add(new Unit(Kind.Bin, "", bin));
        if (plan.isEmpty()) {
            String craftNote = ledger == null ? null : ledger.note();
            if (craftNote != null) return craftNote;
            return notes.isEmpty() ? "nothing to deliver" : "nothing can be done: " + notes.get(0);
        }
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
        return craftPool(index < units.size() ? units.subList(index, units.size()) : List.of(), false);
    }

    /** As above; with {@code plain} only plain stacks (no name, no enchantments) count: what the bot's own table for a craft order may be made of. */
    Map<String, Long> craftPool(boolean plain) {
        return craftPool(index < units.size() ? units.subList(index, units.size()) : List.of(), plain);
    }

    private Map<String, Long> craftPool(List<Unit> remaining) {
        return craftPool(remaining, false);
    }

    private Map<String, Long> craftPool(List<Unit> remaining, boolean plain) {
        Map<String, Long> pool = owner.crafter().survey(plain);
        for (Unit u : remaining) {
            for (Order.Lot lot : u.lots) {
                String key = lot.key();
                int hash = key.indexOf('#');
                pool.merge(hash < 0 ? key : key.substring(0, hash), -(long) lot.count(), Long::sum);
            }
        }
        // What the craft order still may take is reserved for its crafts: the bot's own crafting table (planks, a table) never eats into it
        if (ledger != null) for (var e : ledger.budget().entrySet()) pool.merge(e.getKey(), -e.getValue(), Long::sum);
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
        // The end of a craft-order chunk (the legacy shulker craft below has no chunk)
        if (chunk != null) {
            chunkEnded(outcome);
            return;
        }
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
        // The recipe table is still on its way: wait for it, don't give the unit up
        if (Crafter.WAITING_FOR_RECIPES.equals(why)) return new Idle();
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
        // A craft order is not over until what it carried has gone back (a little while), so the report can say what did
        if (ledger != null && state != State.Running && !craftFinished()) {
            return new Report(order.id, "in_progress", "putting crafted items back", Math.min(index, units.size()), units.size(), message, shulkersDelivered,
                new LinkedHashMap<>(delivered));
        }
        return new Report(order.id, status, shown, Math.min(index, units.size()), units.size(), message, shulkersDelivered, new LinkedHashMap<>(delivered),
            state == State.Running ? null : repairTally.result(), state == State.Running ? null : craftResult);
    }

    /**
     * A saved order the server may still hold with craft lines (its ledger is kept through two {@code 204} claim answers).
     */
    boolean resumeHasCraft() {
        return heldCraftOrderId() != null;
    }

    /**
     * The id of the craft order the bot holds (in hand, or saved with its ledger and the same id as the saved plan), or null. The claim carries
     * the craft flag for it whatever the table is for the moment; a ledger of another order does not count.
     */
    String heldCraftOrderId() {
        Stash stash = owner.stash();
        if (stash == null || stash.activeOrder == null) return null;
        return CraftCapability.heldId(stash.activeOrder.id, !stash.activeOrder.craftLedger.isEmpty(), order == null ? null : order.id);
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
        if (chunk != null && (owner.crafter().active() || owner.craftBusy())) chunk.issuedAt = System.currentTimeMillis();
        if (owner.crafter().active()) {
            if (chunk == null) phase = "crafting " + (index + 1) + "/" + units.size();
            return new Craft(0);
        }
        // The sorter was asked for a chunk and has not started it: asked again, and given up on after a while
        if (chunk != null) {
            if (System.currentTimeMillis() - chunk.issuedAt > CHUNK_START_MS) chunkEnded(new Crafter.Outcome(chunk.intent.key(), 0, "the crafting did not start"));
            else return chunk.intent;
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
                // Items wanted for repair are repaired before the shulker is built (never fails the order)
                Intent craftStep = craftLotsStep(u);
                if (craftStep == CONTINUE) continue;
                if (craftStep != null) return craftStep;
                Intent repair = repairStep(u);
                if (repair != null) return repair;
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
        Set<Integer> carriedUsed = new HashSet<>();
        for (int li = 0; li < u.lots.size(); li++) {
            Order.Lot lot = u.lots.get(li);
            int want = lot.count();
            if (want <= 0) continue;
            // A crafted load is the stack in hand: never topped up from the stash, and left out (with a note) when it is not there any more
            if (lot.crafted()) {
                ItemStack made = craftedStack(lot.key(), want, carriedUsed);
                if (made != null && n < Kit.SLOTS) stacks[n++] = made.copyWithCount(Math.max(1, Math.min(want, made.getCount())));
                else problems.add(StashIndex.nameOf(lot.key()) + ": " + want + " crafted but no longer in hand");
                continue;
            }
            // An item gathered for a repair is carried (repaired or not, it is no longer in the stash)
            if (u.carriedLots.contains(li)) {
                ItemStack carried = carriedStack(lot.key(), carriedUsed);
                if (carried != null && n < Kit.SLOTS) {
                    stacks[n++] = carried.copyWithCount(Math.max(1, Math.min(want, carried.getCount())));
                    continue;
                }
            }
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

    // ---- Craft orders (CRAFT-CONTRACT.md "Bot execution")

    private Recipes tableOrEmpty() {
        Recipes t = Recipes.current();
        return t == null ? Recipes.empty() : t;
    }

    private void clearCraft() {
        ledger = null;
        craftRefusal = null;
        chunk = null;
        tableTries = 0;
        interruptTries = 0;
        craftLotSlots.clear();
        craftSurplus.clear();
        craftLeftover.clear();
        craftEnd = null;
        craftFinal = false;
        craftResult = null;
        craftEndReason = CraftOrder.INTERRUPTED;
        breaker.reset();
        purgedCraft = false;
        owner.crafter().clearOrderKeep();
    }

    /** Forgets slots that no longer hold what was set aside in them (used in a bin, put back, taken). */
    private void pruneTracking() {
        if (mc.player == null) {
            craftLotSlots.clear();
            craftSurplus.clear();
            craftLeftover.clear();
            return;
        }
        var inv = mc.player.getInventory();
        for (Map<Integer, String> m : List.of(craftLotSlots, craftSurplus, craftLeftover)) {
            m.entrySet().removeIf(e -> {
                ItemStack st = inv.getItem(e.getKey());
                return st.isEmpty() || !e.getValue().equals(idOf(st));
            });
        }
    }

    @SafeVarargs
    private final Map<String, Long> countsOf(Map<Integer, String>... maps) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (mc.player == null) return out;
        var inv = mc.player.getInventory();
        for (Map<Integer, String> m : maps) {
            for (var e : m.entrySet()) {
                ItemStack st = inv.getItem(e.getKey());
                if (!st.isEmpty() && e.getValue().equals(idOf(st))) out.merge(e.getValue(), (long) st.getCount(), Long::sum);
            }
        }
        return out;
    }

    /** Everything the order carries of crafted items and unused ingredients, by item id. */
    private Map<String, Long> trackedCounts() {
        return countsOf(craftLotSlots, craftSurplus, craftLeftover);
    }

    private Map<String, Long> surplusCounts() {
        return countsOf(craftSurplus);
    }

    /** The crafted loads and the surplus are the order's: the sorter does not put them away until it ends. */
    private void keepSlots() {
        Set<Integer> keep = new java.util.LinkedHashSet<>(craftLotSlots.keySet());
        keep.addAll(craftSurplus.keySet());
        owner.crafter().keepForOrder(keep);
    }

    private int stashLoads(Map<String, Long> fromStock) {
        long n = 0;
        for (var e : fromStock.entrySet()) {
            int stack = Math.max(1, Crafter.stackOf(e.getKey()));
            n += (e.getValue() + stack - 1) / stack;
        }
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    /** The load of a craft line is given up on for this reason. */
    private void failLot(Unit u, int li, String reason) {
        Order.Lot lot = u.lots.get(li);
        ledger.addShort(lot.key(), lot.count(), reason);
        u.lots.set(li, lot.withCount(0));
        u.lotTotals = null;
        saveLots(u);
        epoch++;
    }

    /** No craft line can be made (no usable table): every load still to make is short with this reason, and the rest of the order goes on. */
    private void failAllCraft(String reason) {
        for (int i = Math.max(0, index); i < units.size(); i++) {
            Unit u = units.get(i);
            for (int li = 0; li < u.lots.size(); li++) {
                if (u.lots.get(li).craft() && u.lots.get(li).count() > 0) failLot(u, li, reason);
            }
        }
    }

    private static boolean allEmpty(Unit u) {
        if (u.lots.isEmpty()) return false;
        for (Order.Lot lot : u.lots) if (lot.count() > 0) return false;
        return true;
    }

    /**
     * The craft step of a bin that has loads of craft lines to make (CRAFT-CONTRACT.md "Bot execution", point 3): the next load in line order is
     * made in one chunk, as much as the budget, the caps and the free carry slots allow ({@link CraftBatch}), at the website's table; what comes
     * out waits in the inventory for the bin. Never fails the order: a load that can't be made is short with a reason.
     * @return null when the bin has nothing (more) to make, {@link #CONTINUE} to look at the unit again, else the intent
     */
    private Intent craftLotsStep(Unit u) {
        if (ledger == null || craftRefusal != null || chunk != null || cancelRequested) return null;
        int li = CraftLots.next(u.lots, ledger);
        if (li < 0) {
            // Every load of the bin came to nothing: there is no shulker to build
            if (allEmpty(u)) {
                advance();
                return CONTINUE;
            }
            return null;
        }
        Order.Lot lot = u.lots.get(li);
        Stash fs = owner.stash();
        if (fs != null && fs != fillsStash) {
            fillsStash = fs;
            craftFills.load(fs.craftFills);
        }
        String problem = owner.craftTableProblem();
        if (problem != null) {
            com.porter.qwerte.QwerteAddon.LOG.info("orders: craft lines not made: {}", problem);
            failAllCraft(problem);
            return CONTINUE;
        }
        long now = System.currentTimeMillis();
        pruneTracking();
        ledger.setSurplus(surplusCounts());
        long left = Math.min((long) ledger.maxFills() - ledger.fills(), (long) owner.craftMaxPerHour() - craftFills.lastHour(now));
        CraftBatch.Choice c = CraftBatch.choose(ledger, lot.key(), lot.count(), owner.freeCarryCount(), left, Crafter::stackOf, this::stashLoads);
        if (!c.ok()) {
            com.porter.qwerte.QwerteAddon.LOG.info("orders: {} not made: {} ({})", StashIndex.nameOf(lot.key()), c.reason(), c.detail());
            failLot(u, li, c.reason() == null ? CraftOrder.INGREDIENTS_SHORT : c.reason());
            return CONTINUE;
        }

        CraftPlan.Plan p = c.plan();
        int ops = 0;
        List<CraftPlanner.Step> steps = new ArrayList<>();
        for (CraftPlan.Step st : p.steps()) {
            ops += st.fills() * st.recipe().filled();
            steps.add(new CraftPlanner.Step(st.recipe(), st.fills()));
        }
        Map<String, Integer> take = new LinkedHashMap<>();
        for (var e : p.fromStock().entrySet()) take.put(e.getKey(), (int) Math.min(Integer.MAX_VALUE, e.getValue()));
        CraftPlanner.Plan legacy = new CraftPlanner.Plan(lot.key(), c.count(), steps, take, ops, null);

        // Surplus and unused ingredients from earlier chunks are the session's from the start, so the plan can use them
        // Crafted surplus and unused ingredients are both adopted; only the unused ingredients (taken from the stash) are charged when they are used up
        Set<Integer> carried = new java.util.LinkedHashSet<>(), adoptedSlots = new java.util.LinkedHashSet<>();
        Map<String, Long> adopted = new LinkedHashMap<>();
        for (Map<Integer, String> m : List.of(craftSurplus, craftLeftover)) {
            for (var e : m.entrySet()) {
                if (e.getValue().equals(lot.key())) continue;
                carried.add(e.getKey());
                if (m != craftLeftover) continue;
                adoptedSlots.add(e.getKey());
                ItemStack st = mc.player.getInventory().getItem(e.getKey());
                if (!st.isEmpty()) adopted.merge(e.getValue(), (long) st.getCount(), Long::sum);
            }
        }
        CraftChunk intent = new CraftChunk(lot.key(), c.count(), legacy, carried);
        chunk = new Chunk(u, li, c, intent, adopted, adoptedSlots);
        ledger.chunkStarted();
        saveLots(u);
        phase = "crafting " + ledger.fills() + "/" + ledger.plannedFills() + " fills: " + Recipes.pretty(lot.key());
        epoch++;
        return intent;
    }

    /** The sorter's craft session for the chunk is over (or never began). */
    private void chunkEnded(Crafter.Outcome o) {
        Chunk c = chunk;
        chunk = null;
        if (c == null || ledger == null) return;
        Unit u = c.unit;
        int li = c.lot;
        if (li >= u.lots.size()) return;
        Order.Lot lot = u.lots.get(li);
        String key = c.intent.key();
        boolean running = running() && !cancelRequested;
        var inv = mc.player == null ? null : mc.player.getInventory();

        // What the session held at its end: the made stack for the load, the rest is leftover ingredients or surplus of what the steps made
        Set<Integer> held = new java.util.LinkedHashSet<>(owner.crafter().lastHeld());
        craftSurplus.keySet().removeAll(held);
        craftLeftover.keySet().removeAll(held);
        Set<String> madeIds = new HashSet<>();
        for (CraftPlan.Step st : c.choice.plan().steps()) madeIds.add(st.recipe().result());
        int lotSlot = -1, best = 0;
        boolean lotFromStock = false;
        // Taken from the stash (or unused ingredients taken over from an earlier chunk): stock. Everything else the session holds was made by it.
        // Stock stays stock and made stays made, whatever the item: a made surplus never offsets what was gathered, and a surplus taken over
        // later is never charged
        Set<Integer> stockSlots = new HashSet<>(o.gatheredSlots());
        stockSlots.addAll(c.adoptedSlots);
        if (inv != null) {
            List<com.porter.qwerte.craft.CraftSplit.Slot> carriedNow = new ArrayList<>();
            for (int slot : held) {
                ItemStack st = inv.getItem(slot);
                if (!st.isEmpty()) carriedNow.add(new com.porter.qwerte.craft.CraftSplit.Slot(slot, idOf(st), st.getCount()));
            }
            com.porter.qwerte.craft.CraftSplit.Sorted sorted = com.porter.qwerte.craft.CraftSplit.sort(key, carriedNow, stockSlots);
            lotSlot = sorted.lotSlot();
            best = sorted.lotCount();
            lotFromStock = sorted.lotFromStock();
            craftLeftover.putAll(sorted.stock());
            craftSurplus.putAll(sorted.made());
        }

        int made = lotSlot < 0 ? 0 : Math.min(c.choice.count(), best);
        int fillsMade = Math.max(0, o.crafts());
        // Charged: what was gathered from the stash (and what earlier chunks left unused and this one took over), less what is still carried of it
        Map<String, Long> gathered = new LinkedHashMap<>(c.adopted);
        for (var e : o.gathered().entrySet()) gathered.merge(Recipes.ns(e.getKey()), (long) e.getValue(), Long::sum);
        // ... less what is still carried of it, from the stock slots only (the surplus the steps made is not stock and is never counted here)
        Map<String, Long> back = countsOf(craftLeftover);
        // The load can be a stack taken from the stash as itself (the order's take names the line's own item): what it holds beyond the load goes back
        if (lotFromStock && lotSlot >= 0 && best > made) back.merge(Recipes.ns(key), (long) (best - made), Long::sum);
        Map<String, Long> charge = CraftCharge.charge(gathered, fillsMade, back);
        charge.keySet().retainAll(ledger.take().keySet());
        ledger.madeChunk(key, made, fillsMade, charge);
        long stamp = System.currentTimeMillis();
        craftFills.add(stamp, fillsMade);
        Stash fillStash = owner.stash();
        if (fillStash != null && fillsMade > 0) {
            fillStash.craftFills.clear();
            fillStash.craftFills.addAll(craftFills.export(stamp));
            fillStash.markDirty();
        }

        boolean cancelled = !running && (state == State.Cancelled || cancelRequested);
        String reason = o.failure() == null ? null : CraftOrder.reasonOf(o.failure(), false, cancelled);
        // Nothing made and the table was the trouble (or the chunk was cut short): tried once more
        if (running && made == 0 && reason != null) {
            boolean again = false;
            if (CraftOrder.TABLE_BLOCKED.equals(reason) && tableTries < 1) {
                tableTries++;
                owner.retryTable();
                again = true;
            } else if (CraftOrder.INTERRUPTED.equals(reason) && interruptTries < 1) {
                interruptTries++;
                again = true;
            }
            if (again) {
                com.porter.qwerte.QwerteAddon.LOG.info("orders: a craft chunk of {} is tried once more ({})", StashIndex.nameOf(key), reason);
                pruneTracking();
                ledger.setSurplus(surplusCounts());
                saveLots(u);
                keepSlots();
                epoch++;
                return;
            }
        }
        int shortBy = lot.count() - made;
        if (shortBy > 0) ledger.addShort(key, shortBy, reason != null ? reason : cancelled ? CraftOrder.CANCELLED : CraftOrder.INGREDIENTS_SHORT);
        if (made > 0) {
            u.lots.set(li, lot.made(made));
            u.carriedLots.add(li);
            craftLotSlots.put(lotSlot, key);
        } else {
            u.lots.set(li, lot.withCount(0));
        }
        u.lotTotals = null;
        // The surplus is what is really carried now, so a restart finds it in the saved ledger
        pruneTracking();
        ledger.setSurplus(surplusCounts());
        saveLots(u);
        if (state == State.Running) keepSlots();
        else if (craftEnd != null) craftEnd.raise(trackedCounts());
        // A circuit breaker: chunks that keep making nothing are not tried for ever
        if (running && breaker.record(made)) {
            com.porter.qwerte.QwerteAddon.LOG.warn("orders: {} craft chunks in a row made nothing, the rest of the craft lines are given up on", com.porter.qwerte.craft.CraftBreaker.MAX_ZERO);
            failAllCraft(reason != null ? reason : CraftOrder.INGREDIENTS_SHORT);
        }
        if (o.failure() != null) com.porter.qwerte.QwerteAddon.LOG.info("orders: craft chunk of {} ended: {}", StashIndex.nameOf(key), o.failure());
        epoch++;
    }

    /** The report of a finished craft order waits for the put-back (at most 90 s); true when it can be made now. */
    private boolean craftFinished() {
        if (craftFinal) return true;
        if (craftEnd == null || order == null) {
            craftFinal = true;
            return true;
        }
        long now = System.currentTimeMillis();
        if (chunk != null && !owner.crafter().active()) chunk = null;
        pruneTracking();
        Map<String, Long> still = trackedCounts();
        boolean busy = owner.crafter().active() || chunk != null;
        if (busy ? !craftEnd.timeUp(now) : !craftEnd.done(now, still)) return false;
        ledger.setReturned(craftEnd.returned(still));
        ledger.setDelivered(delivered, order.requested());
        ledger.closeOpen(craftEndReason);
        craftResult = ledger.toResult();
        String note = ledger.note();
        if (note != null && !message.contains(note)) message = cut(message.isEmpty() ? note : message + "; " + note);
        craftFinal = true;
        return true;
    }

    /** Reads the saved ledger back; false when it is missing or fails any check (nothing is then taken from it). */
    private boolean restoreLedger(String text) {
        if (text == null || text.isEmpty()) return false;
        try {
            com.google.gson.JsonElement e = com.porter.qwerte.craft.SafeJson.parse(text);
            return e.isJsonObject() && ledger.restore(e.getAsJsonObject());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * With no usable craft ledger every load still to make is short (recipe_mismatch) and emptied, so it can never go down the plain-item path.
     * @return true when something was changed (the units are saved)
     */
    private boolean purgeCraftLots(List<Unit> plan) {
        boolean any = false;
        for (Unit u : plan) {
            for (int li = 0; li < u.lots.size(); li++) {
                Order.Lot lot = u.lots.get(li);
                if (!lot.craft() || lot.count() <= 0) continue;
                if (ledger != null) ledger.addShort(lot.key(), lot.count(), CraftOrder.RECIPE_MISMATCH);
                u.lots.set(li, lot.withCount(0));
                u.lotTotals = null;
                any = true;
            }
        }
        return any;
    }

    /** Sets aside again, after a restart, the stacks a craft order carried (crafted loads, surplus), and gives back what is gone. */
    private void resumeCraft(List<Unit> remaining) {
        if (ledger == null || mc.player == null) return;
        var inv = mc.player.getInventory();
        List<CraftResume.Load> loads = new ArrayList<>();
        for (Unit u : remaining) for (Order.Lot lot : u.lots) if (lot.crafted() && lot.count() > 0) loads.add(new CraftResume.Load(lot.key(), lot.count()));
        List<CraftResume.Stack> carried = new ArrayList<>();
        for (int slot : owner.carry()) {
            ItemStack st = inv.getItem(slot);
            if (!st.isEmpty() && st.getComponentsPatch().isEmpty()) carried.add(new CraftResume.Stack(slot, idOf(st), st.getCount()));
        }
        CraftResume.Result r = CraftResume.remark(loads, ledger.savedSurplus(), carried);
        int i = 0;
        for (Unit u : remaining) {
            for (Order.Lot lot : u.lots) {
                if (!lot.crafted() || lot.count() <= 0) continue;
                int slot = r.lotSlots().get(i++);
                if (slot >= 0) craftLotSlots.put(slot, lot.key());
            }
        }
        craftSurplus.putAll(r.surplusSlots());
        ledger.refund(r.refund());
        keepSlots();
    }

    /** The carried stack for a crafted load: the stack set aside for it with exactly its count, else any of that count, else any of the key. */
    private ItemStack craftedStack(String key, int count, Set<Integer> used) {
        if (mc.player == null) return null;
        var inv = mc.player.getInventory();
        for (int pass = 0; pass < 3; pass++) {
            Iterable<Integer> slots = pass == 0 ? new ArrayList<>(craftLotSlots.keySet()) : owner.carry();
            for (int slot : slots) {
                if (used.contains(slot)) continue;
                ItemStack s = inv.getItem(slot);
                if (s.isEmpty() || !key.equals(StashIndex.keyOf(s))) continue;
                if (pass < 2 && s.getCount() != count) continue;
                used.add(slot);
                return s;
            }
        }
        return null;
    }

    // ---- Repair (REPAIR-CONTRACT.md)

    /** The carried stack with this index key in a carry slot not in {@code used} (its slot is added), or null. */
    private ItemStack carriedStack(String key, Set<Integer> used) {
        if (mc.player == null) return null;
        for (int slot : owner.carry()) {
            if (used.contains(slot)) continue;
            ItemStack s = mc.player.getInventory().getItem(slot);
            if (!s.isEmpty() && key.equals(StashIndex.keyOf(s))) {
                used.add(slot);
                return s;
            }
        }
        return null;
    }

    private int carriedBottles() {
        int n = 0;
        if (mc.player == null) return 0;
        for (int slot : owner.carry()) {
            ItemStack s = mc.player.getInventory().getItem(slot);
            if (s.is(net.minecraft.world.item.Items.EXPERIENCE_BOTTLE)) n += s.getCount();
        }
        return n;
    }

    private String askedKey(Unit u, int lot) {
        return u.asked == null ? u.lots.get(lot).key() : u.asked.get(lot);
    }

    /**
     * The repair step of a shulker that is still to be built: null when there is nothing (more) to repair for it and the build goes
     * on. It takes the unit's damaged Mending items and bottles out of the stash with the kit builder (no shulker), has the sorter
     * throw the bottles ({@link Repair}), and swaps the repaired items' keys into the unit's lots. Repair never fails the order:
     * whatever goes wrong the items are delivered as they are, and the reason is noted.
     */
    private Intent repairStep(Unit u) {
        if (u.rstate == RState.DONE || order == null) return null;
        if (!order.wantsRepair()) {
            u.rstate = RState.DONE;
            return null;
        }
        if (u.rstate == RState.NEW) {
            if (u.repairPending.isEmpty() && !prepareRepair(u)) {
                u.rstate = RState.DONE;
                return null;
            }
            if (!startBatch(u)) {
                u.rstate = RState.DONE;
                return null;
            }
        }
        if (u.rstate == RState.GATHER) {
            // Another kit is let go of and this one begun in the same call, so what is carried is never without a kit
            if (kits.active() && kits.kit() != u.repairKit) kits.dropOrderKit();
            if (!kits.active() && (++u.repairBegins > 3 || !kits.beginGather(u.repairKit))) {
                abandonBatch(u, RepairEstimate.INTERRUPTED);
                return null;
            }
            phase = "fetching repair items " + (index + 1) + "/" + units.size();
            KitBuilder.Intent step = kits.next();
            if (step instanceof KitBuilder.Done) {
                // The kit stays active (so the sorter does not put what it gathered back) until the repair is over
                List<String> keys = new ArrayList<>();
                for (int i : u.repairBatch) keys.add(u.lots.get(i).key());
                u.carriedLots.addAll(u.repairBatch);
                u.rstate = RState.THROW;
                u.repairAt = System.currentTimeMillis();
                phase = "repairing items " + (index + 1) + "/" + units.size();
                saveLots(u);
                epoch++;
                return new Repair(keys);
            }
            if (step instanceof KitBuilder.Abort abort) {
                kits.suspend(abort.reason());
                com.porter.qwerte.QwerteAddon.LOG.info("orders: repair: couldn't gather the items and bottles: {}", abort.reason());
                abandonBatch(u, bottlesShort(u) ? RepairEstimate.NO_BOTTLES : RepairEstimate.INTERRUPTED);
                return null;
            }
            return new Build(step);
        }
        if (u.rstate == RState.THROW) {
            // The sorter is on it and tells repairEnded; one that never does is given up on after a while
            if (System.currentTimeMillis() - u.repairAt > REPAIR_LOST_MS) {
                owner.stopRepair();
                repairEnded(new RepairRun.Outcome(0, List.of(), repairTicksUsed));
                return null;
            }
            phase = "repairing items " + (index + 1) + "/" + units.size();
            return new Idle();
        }
        return null;
    }

    /** Whether the stash and the inventory together hold fewer bottles than the batch was to have. */
    private boolean bottlesShort(Unit u) {
        Stash stash = owner.stash();
        long have = carriedBottles();
        if (stash != null) have += survey(stash, Set.of(RepairEstimate.BOTTLE_KEY)).available().getOrDefault(RepairEstimate.BOTTLE_KEY, 0L);
        return have < u.repairBottleNeed;
    }

    /**
     * Works out which lots of the unit are to be repaired, checking the stack live (the stash's, or a carried one for items already
     * gathered). Lots with their line's own toggle are always counted, with a reason when they can't be repaired; under the cart-wide
     * switch only damaged items are wanted. Counts are per item. @return false when none is to be repaired
     */
    private boolean prepareRepair(Unit u) {
        Stash stash = owner.stash();
        if (stash == null) return false;
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < u.lots.size(); i++) {
            if (repairKeys.contains(askedKey(u, i)) && askedKey(u, i).equals(u.lots.get(i).key())) keys.add(u.lots.get(i).key());
        }
        if (keys.isEmpty()) return false;
        Survey sv = survey(stash, keys);
        Set<Integer> carriedUsed = new HashSet<>();
        for (int i = 0; i < u.lots.size(); i++) {
            String key = u.lots.get(i).key();
            if (!keys.contains(key)) continue;
            int count = u.lots.get(i).count();
            boolean explicit = repairExplicit.contains(askedKey(u, i));
            ItemStack sample = u.carriedLots.contains(i) ? carriedStack(key, carriedUsed) : null;
            if (sample == null) sample = sv.samples().get(key);
            if (sample == null) sample = carriedStack(key, carriedUsed);
            if (sample == null) {
                if (explicit) repairTally.skip(key, RepairEstimate.INTERRUPTED, count);
                continue;
            }
            RepairEstimate.Facts facts = new RepairEstimate.Facts(Mending.has(sample), sample.getDamageValue(), sample.getMaxStackSize());
            // Under the cart-wide switch an item that is not damaged is simply not wanted
            if (!explicit && facts.damage() <= 0) continue;
            String reason = RepairEstimate.reason(key, facts);
            if (reason == null && repairStopped != null) reason = repairStopped;
            if (reason != null) {
                repairTally.skip(key, reason, count);
                continue;
            }
            u.repairPending.add(i);
            u.repairDamage.put(i, facts.damage());
        }
        saveLots(u);
        return !u.repairPending.isEmpty();
    }

    /** Picks the next batch from the pending items and builds the gathering kit for it. @return false when nothing is left to do */
    private boolean startBatch(Unit u) {
        Stash stash = owner.stash();
        if (stash == null || u.repairPending.isEmpty()) return false;
        long stashBottles = survey(stash, Set.of(RepairEstimate.BOTTLE_KEY)).available().getOrDefault(RepairEstimate.BOTTLE_KEY, 0L);
        List<Integer> needs = new ArrayList<>();
        for (int i : u.repairPending) needs.add(RepairEstimate.bottlesFor(u.repairDamage.getOrDefault(i, 1)));
        RepairRun.Batch batch = RepairRun.pickBatch(needs, stashBottles + carriedBottles());
        if (batch.items() == 0) {
            for (int i : u.repairPending) repairTally.skip(u.lots.get(i).key(), RepairEstimate.NO_BOTTLES, u.lots.get(i).count());
            u.repairPending.clear();
            return false;
        }

        Set<String> keys = new HashSet<>();
        for (int k = 0; k < batch.items(); k++) keys.add(u.lots.get(u.repairPending.get(k)).key());
        Survey sv = survey(stash, keys);
        Map<Variant, Long> used = new java.util.IdentityHashMap<>();
        List<ItemStack> stacks = new ArrayList<>();
        List<Integer> chosen = new ArrayList<>();
        for (int k = 0; k < batch.items(); k++) {
            int lot = u.repairPending.get(k);
            Variant v = bestFit(sv.variants().getOrDefault(u.lots.get(lot).key(), List.of()), used, 1);
            if (v == null) {
                repairTally.skip(u.lots.get(lot).key(), RepairEstimate.INTERRUPTED, u.lots.get(lot).count());
                continue;
            }
            used.merge(v, 1L, Long::sum);
            stacks.add(v.sample.copyWithCount(1));
            chosen.add(lot);
        }
        u.repairPending.subList(0, batch.items()).clear();
        if (chosen.isEmpty()) return !u.repairPending.isEmpty() && startBatch(u);
        for (int left = batch.bottles(); left > 0; left -= 64) stacks.add(new ItemStack(net.minecraft.world.item.Items.EXPERIENCE_BOTTLE, Math.min(64, left)));
        if (stacks.size() > Kit.SLOTS) return false;

        u.repairBatch = chosen;
        u.repairBottleNeed = batch.bottles();
        u.repairKit = new Kit("repair " + order.id + " #" + (index + 1), stacks.toArray(new ItemStack[0]), true);
        u.repairBegins = 0;
        u.rstate = RState.GATHER;
        epoch++;
        return true;
    }

    /** The batch could not be gathered: its items (and the rest of the unit's) go as they are, and the unit carries on. */
    private void abandonBatch(Unit u, String reason) {
        for (int i : u.repairBatch) repairTally.skip(u.lots.get(i).key(), reason, u.lots.get(i).count());
        // Whatever of the batch was already picked up is carried, so the shulker is built from it
        u.carriedLots.addAll(u.repairBatch);
        for (int i : u.repairPending) repairTally.skip(u.lots.get(i).key(), reason, u.lots.get(i).count());
        u.repairPending.clear();
        u.repairBatch = new ArrayList<>();
        u.repairKit = null;
        kits.dropOrderKit();
        u.rstate = RState.DONE;
        saveLots(u);
        epoch++;
    }

    /** The sorter's repair job is over: the repaired items' keys go into the unit's lots, and the next batch (if any) is set up. */
    void repairEnded(RepairRun.Outcome outcome) {
        if (order == null || index >= units.size()) return;
        Unit u = units.get(index);
        if (u.rstate != RState.THROW) return;
        repairTally.bottles(outcome.bottlesUsed());
        repairTicksUsed = Math.max(repairTicksUsed, outcome.throwTicks());
        repairRetriesUsed = Math.max(repairRetriesUsed, outcome.retries());
        boolean interrupted = false;
        List<Integer> open = new ArrayList<>(u.repairBatch);
        for (RepairRun.ItemResult r : outcome.items()) {
            repairTally.add(r);
            if (RepairEstimate.INTERRUPTED.equals(r.reason()) && r.status() != RepairRun.Status.FULL) interrupted = true;
            int lot = -1;
            for (int i : open) {
                if (u.lots.get(i).key().equals(r.key())) {
                    lot = i;
                    break;
                }
            }
            if (lot < 0) continue;
            open.remove((Integer) lot);
            if (r.newKey() != null && !r.newKey().equals(r.key())) {
                if (u.asked == null) {
                    u.asked = new ArrayList<>();
                    for (Order.Lot l : u.lots) u.asked.add(l.key());
                }
                String asked = u.asked.get(lot);
                u.lots.set(lot, new Order.Lot(r.newKey(), u.lots.get(lot).count()));
                u.repairedAs.put(r.newKey(), asked);
                u.lotTotals = null;
            }
        }
        // Items the job gave no result for (it was lost) go as they are
        for (int lot : open) repairTally.skip(u.lots.get(lot).key(), RepairEstimate.INTERRUPTED, u.lots.get(lot).count());
        u.repairBatch = new ArrayList<>();
        u.repairKit = null;
        if (repairTicksUsed >= RepairRun.Guard.CAP_TICKS) interrupted = true;
        if (interrupted && repairStopped == null) repairStopped = RepairEstimate.INTERRUPTED;
        if (repairStopped != null) {
            for (int i : u.repairPending) repairTally.skip(u.lots.get(i).key(), repairStopped, u.lots.get(i).count());
            u.repairPending.clear();
        }
        u.rstate = u.repairPending.isEmpty() ? RState.DONE : RState.NEW;
        saveLots(u);
        epoch++;
    }

    /** Keeps the unit's lot keys (repaired ones changed) and the keys they were asked with in the saved plan, for a resume. */
    private void saveLots(Unit u) {
        Stash stash = owner.stash();
        int i = units.indexOf(u);
        if (stash == null || order == null || stash.activeOrder == null || !stash.activeOrder.id.equals(order.id) || i < 0 || i >= stash.activeOrder.units.size()) return;
        Stash.ActiveOrder.UnitPlan p = stash.activeOrder.units.get(i);
        p.lotKeys.clear();
        p.lotCounts.clear();
        for (Order.Lot lot : u.lots) {
            p.lotKeys.add(lot.key());
            p.lotCounts.add(lot.count());
        }
        p.lotAsked.clear();
        if (u.asked != null) p.lotAsked.addAll(u.asked);
        p.lotCarried.clear();
        p.lotCarried.addAll(u.carriedLots);
        p.lotCraft.clear();
        if (ledger != null) for (Order.Lot lot : u.lots) p.lotCraft.add(lot.craftState());
        // The order's repair tally, so a resume goes on counting
        Stash.ActiveOrder a = stash.activeOrder;
        if (ledger != null) a.craftLedger = ledger.toSaved().toString();
        RepairEstimate.Result r = repairTally.result();
        a.repairBottles = r == null ? 0 : r.bottlesUsed();
        a.repaired = r == null ? 0 : r.repaired();
        a.repairTicks = repairTicksUsed;
        a.repairRetries = repairRetriesUsed;
        a.repairStopped = repairStopped == null ? "" : repairStopped;
        a.repairReasons.clear();
        a.repairReasons.addAll(repairTally.reasons());
        a.repairPartial.clear();
        a.repairSkipped.clear();
        if (r != null) {
            for (RepairEstimate.Partial x : r.partial()) a.repairPartial.add(new String[] { x.key(), String.valueOf(x.damage()), String.valueOf(x.maxDamage()) });
            for (RepairEstimate.Skipped x : r.skipped()) a.repairSkipped.add(new String[] { x.key(), x.reason(), "" });
        }
        stash.markDirty();
        stash.save();
    }

    /** Takes the repair tally, the throwing time and the stop reason of a saved order back. */
    private void loadRepair(Stash.ActiveOrder saved) {
        List<RepairEstimate.Partial> partial = new ArrayList<>();
        for (String[] e : saved.repairPartial) {
            try {
                partial.add(new RepairEstimate.Partial(e[0], Integer.parseInt(e[1]), Integer.parseInt(e[2])));
            } catch (NumberFormatException ex) {
                // a damaged entry is left out
            }
        }
        List<RepairEstimate.Skipped> skipped = new ArrayList<>();
        for (String[] e : saved.repairSkipped) skipped.add(new RepairEstimate.Skipped(e[0], e[1]));
        repairTally.load(saved.repairBottles, saved.repaired, partial, skipped, saved.repairReasons);
        repairTicksUsed = saved.repairTicks;
        repairRetriesUsed = saved.repairRetries;
        repairStopped = saved.repairStopped.isEmpty() ? null : saved.repairStopped;
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
            // A repaired item counts for the line that asked for it
            if (key != null) out.merge(u.repairedAs.getOrDefault(key, key), s.getCount(), Integer::sum);
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
        if (ledger != null) {
            // What is carried now is put back by the normal put-back; the report waits for that (CraftEnd) and says what went back
            pruneTracking();
            craftEnd = new CraftEnd(System.currentTimeMillis(), trackedCounts());
            craftEndReason = end == State.Cancelled ? CraftOrder.CANCELLED : CraftOrder.INTERRUPTED;
            owner.crafter().clearOrderKeep();
        }
        if (craftedTotal > 0) text = (text.isEmpty() ? "" : text + "; ") + "crafted " + craftedTotal + " Shulker Box";
        String repairNote = repairTally.note();
        if (!repairNote.isEmpty() && !text.contains(repairNote)) text = text.isEmpty() ? repairNote : text + "; " + repairNote;
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
