package com.porter.qwerte.modules;

import com.porter.qwerte.stash.Kit;
import com.porter.qwerte.stash.Kits;
import com.porter.qwerte.stash.Order;
import com.porter.qwerte.stash.Stash;
import com.porter.qwerte.stash.Stash.Box;
import com.porter.qwerte.stash.StashIndex;
import meteordevelopment.meteorclient.utils.Utils;
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
    }

    sealed interface Intent permits Idle, Build, Fetch, Deliver {}

    /** Nothing for the sorter to do for the order now. */
    record Idle() implements Intent {}

    /** A kit builder step: take, fetch, unpack or fill. */
    record Build(KitBuilder.Intent intent) implements Intent {}

    /** Take this shulker out of the container. */
    record Fetch(Box box, int slot) implements Intent {}

    /** Open the delivery container and put the order's shulkers in. */
    record Deliver(Box box) implements Intent {}

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
    private static final int MAX_STARTS = 8;

    private final KitBuilder kits;
    private final Owner owner;

    private Order order;
    private State state = State.Running;
    private final List<Unit> units = new ArrayList<>();
    private int index;
    private int shulkersDelivered;
    private final Map<String, Integer> delivered = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private String message = "";
    private String phase = "starting";
    private boolean cancelRequested, parked;
    private long pausedSince;
    private boolean full;
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
        cancelRequested = false;
        parked = false;
        pausedSince = 0;
        full = false;
        lastSurvey = null;
        planned = false;
        state = State.Running;
        epoch++;
    }

    Box deliveryBox() {
        Stash stash = owner.stash();
        if (stash == null) return null;
        for (Box b : stash.boxes) if (b.delivery) return b;
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
        message = "";
        cancelRequested = false;
        parked = false;
        pausedSince = 0;
        full = false;
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
                plan.add(new Unit(p.kind == 0 ? Kind.Shulker : p.kind == 1 ? Kind.Empty : Kind.Bin, p.key, lots));
            }
            start = Math.min(saved.done, plan.size());
            // What was delivered before counts even if the rest cannot be done now
            shulkersDelivered = saved.shulkersDelivered;
            delivered.putAll(saved.delivered);
        }
        else {
            String why = planFresh(stash, o, plan, notes);
            if (why != null) return why;
        }

        String why = checkEmpties(plan.subList(start, plan.size()));
        if (why != null) return why;

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

    /** Whether the empty shulkers in the stash are enough for the units still to do, kind by kind. */
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
        int asked = 0;
        for (var e : need.entrySet()) {
            asked += e.getValue();
            if (have.getOrDefault(e.getKey(), 0) < e.getValue()) return "need " + e.getValue() + " empty shulkers of one kind, have " + have.getOrDefault(e.getKey(), 0);
        }
        if (bins > total - asked) return "need " + (bins + asked) + " empty shulkers, have " + total;
        return null;
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
        switch (u.kind) {
            case Bin -> {
                if (stack.has(DataComponents.CUSTOM_NAME)) return false;
                if (u.kit != null) return u.kit.matchesFinished(Kits.inside(stack));
                return matchesLots(u, stack);
            }
            case Shulker -> {
                return u.key.equals(StashIndex.groupKey(stack));
            }
            default -> {
                for (ItemStack s : Kits.inside(stack)) if (s != null && !s.isEmpty()) return false;
                return u.key.equals(StashIndex.keyOf(stack));
            }
        }
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

    /** The shulker is no longer carried: the open container is checked for it. */
    void deliveryClosed(ItemStack[] contents) {
        if (!running() || index >= units.size()) return;
        Unit u = units.get(index);
        if (u.have && countIn(u, contents) > u.base) u.confirmed = true;
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
                if (full) {
                    full = false;
                    u.fullTries++;
                }
                if (u.fullTries >= MAX_FULL_TRIES) {
                    finish(shulkersDelivered > 0 ? State.Delivered : State.Failed, "delivery container full (" + shulkersDelivered + "/" + units.size() + ")");
                    return new Idle();
                }
                if (++u.starts > MAX_STARTS) {
                    finish(shulkersDelivered > 0 ? State.Delivered : State.Failed, "couldn't put the shulker into the delivery container (" + shulkersDelivered + "/" + units.size() + " delivered)");
                    return new Idle();
                }
                phase = "delivering " + (index + 1) + "/" + units.size();
                return new Deliver(delivery);
            }

            if (u.have) {
                // It left the inventory: it counts only if the delivery container was seen to hold it
                if (u.confirmed) {
                    for (var e : u.content.entrySet()) delivered.merge(e.getKey(), e.getValue(), Integer::sum);
                    shulkersDelivered++;
                    advance();
                }
                else if (!u.verified) {
                    // Not seen in the container yet: look once more, in a freshly opened window, before giving up on it
                    u.verified = true;
                    u.verifying = true;
                    phase = "checking delivery " + (index + 1) + "/" + units.size();
                    return new Deliver(delivery);
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
        return n == 0 ? null : new Kit("order " + order.id + " #" + (index + 1), stacks, true);
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
            if (box.excluded) skip = "in an excluded container";
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
        message = text;
        phase = end.name().toLowerCase();
        epoch++;
        say("Order " + order.id + " " + phase + (text.isEmpty() ? "." : ": " + text));
    }

    private void say(String text) {
        com.porter.qwerte.QwerteAddon.LOG.info("orders: {}", text);
        if (chat) owner.say(text);
    }
}
