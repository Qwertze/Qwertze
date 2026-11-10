package com.porter.qwerte.modules;

import com.porter.qwerte.compat.GameVersion;
import com.porter.qwerte.craft.CraftPlanner;
import com.porter.qwerte.craft.RecipeBoot;
import com.porter.qwerte.craft.Recipes;
import com.porter.qwerte.stash.Kits;
import com.porter.qwerte.stash.Stash;
import com.porter.qwerte.stash.Stash.Box;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Crafts things out of the stash for the stash sorter (phase 1: plain shulker boxes). A session plans the craft with
 * {@link CraftPlanner}, gathers the ingredients (loose stacks, and the contents of shulkers), walks to the crafting table,
 * and clicks the ingredients into the table's grid. The sorter runs the world side (walking, opening, placing shulkers);
 * this decides the next step from the live inventory and menu, so a click the server refused only costs a retry.
 *
 * Ingredients are counted by their base item, whatever their name or enchantments. Damaged items, stacks that hold
 * something (a filled shulker, bundle, chest) and shulker boxes are never used. What a craft leaves over, and the
 * intermediates, are handed back to the sorter as held stacks, which puts them away by where they came from.
 */
final class Crafter {
    interface Owner {
        Stash stash();

        /** The inventory slots the sorter may carry stash items in. */
        Set<Integer> carry();

        int freeCarrySlot();

        int freeCarryCount();

        boolean usable(Box box);

        boolean keepWhole(Box box);

        boolean namedShulkers();

        /** A shulker that belongs to a saved kit or an order. */
        boolean claimed(ItemStack shulker);

        /** A stack the crafter put in this carry slot, so it counts as the stash's and not the player's. */
        void held(int slot, ItemStack stack);

        double distance(Box box);

        Set<String> neverUse();

        CraftPlanner.Caps caps();

        void say(String text);
    }

    sealed interface Intent permits Take, Fetch, Unpack, Table, Done, Abort {}

    /** Open this container and take the ingredients out of it. */
    record Take(Box box) implements Intent {}

    /** Take this shulker (it holds ingredients) out of the container. */
    record Fetch(Box box, int slot) implements Intent {}

    /** Place the shulker in this carry slot and take the ingredients out of it. */
    record Unpack(int slot) implements Intent {}

    /** Walk to the crafting table and craft there. */
    record Table() implements Intent {}

    record Done() implements Intent {}

    record Abort(String reason) implements Intent {}

    /** What a session came to: how many of the item were crafted, and why not more (null when it went through). */
    record Outcome(String item, int crafted, String failure, int crafts, Map<String, Integer> gathered, Set<Integer> gatheredSlots) {
        Outcome(String item, int crafted, String failure) {
            this(item, crafted, failure, 0, Map.of(), Set.of());
        }
    }

    /** The work one session is asked for. {@code pool} is what the crafts may use up (the stash's, less what others need). */
    record Request(String item, int count, String purpose, boolean reserve, int doneOrder, Supplier<Map<String, Long>> pool,
                   Supplier<Set<String>> reservedKeys, boolean gatherOnly, boolean plainOnly) {
        Request(String item, int count, String purpose, boolean reserve, int doneOrder, Supplier<Map<String, Long>> pool) {
            this(item, count, purpose, reserve, doneOrder, pool, Set::of, false, false);
        }

        Request(String item, int count, String purpose, boolean reserve, int doneOrder, Supplier<Map<String, Long>> pool,
                Supplier<Set<String>> reservedKeys, boolean gatherOnly) {
            this(item, count, purpose, reserve, doneOrder, pool, reservedKeys, gatherOnly, false);
        }
    }

    /** One thing to do in the crafting window. */
    record Act(int kind, int slot, int button, String why) {
        static final int CLICK = 0, WAIT = 1, DONE = 2, FAIL = 3;

        static Act click(int slot, int button) {
            return new Act(CLICK, slot, button, null);
        }

        static Act waiting() {
            return new Act(WAIT, -1, 0, null);
        }

        static Act done() {
            return new Act(DONE, -1, 0, null);
        }

        static Act fail(String why) {
            return new Act(FAIL, -1, 0, why);
        }
    }

    private record Spot(Box box, int slot) {}

    private static final long TIME_CAP_MS = 10 * 60 * 1000L;
    private static final long HOUR_MS = 60 * 60 * 1000L;
    private static final int RESULT_WAIT_TICKS = 60;
    private static final int MAX_TAKE_TRIES = 6;

    /** Why nothing is crafted while there is no recipe table (it is fetched from the Stash Manager server, or built from the game's own data). */
    static final String NO_RECIPES = "no recipe table (neither the Stash Manager server nor the game's own data gave one)";
    /** The same while the table is still on its way: a wait, not a refusal (nothing is claimed or started meanwhile). */
    static final String WAITING_FOR_RECIPES = "waiting for the recipe table";

    /**
     * No table yet, but one is still on its way: the cache or the build from the game's data has not finished, or the server's fetch has
     * not yet succeeded or failed. Bounded: once both are over with no table, this is false and {@link #NO_RECIPES} is the answer.
     */
    static boolean recipesPending() {
        if (recipes() != null) return false;
        StashManager manager = meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
        return (manager != null && manager.waitingForServerRecipes()) || !RecipeBoot.localDone();
    }

    private final Owner owner;

    // The session
    private Request req;
    private CraftPlanner.Plan plan;
    private boolean active, tableDone;
    // The session crafts in the 2x2 grid of the inventory window (a crafting table is made that way), not at a table
    private boolean inventoryGrid;
    private long startedAt;
    private int replans;
    private String failure;
    private final Set<Integer> mine = new LinkedHashSet<>();
    private final Set<Spot> badSpots = new HashSet<>();
    private Spot lastFetch;
    private final Set<Integer> shulkersBefore = new HashSet<>();
    private final Set<Integer> unpacked = new HashSet<>();

    // Crafted shulker boxes waiting to be used, by inventory slot, over sessions
    private final Set<Integer> reserved = new LinkedHashSet<>();
    private int craftedTarget, stamped;

    // The crafts of the last hour (the time of each)
    private final ArrayDeque<Long> stamps = new ArrayDeque<>();

    // The window
    private int stepIdx, craftIdx, fills, resultWait, takeTries, homeStuck, srcSlot = -1;
    private String[] layout;
    private int layoutStep = -1, layoutCraft = -1;
    private boolean lastTake, crafting;
    // A craft-order chunk (0.4.0-dev.18): the plan is the server's sub-plan and is never planned again; what was gathered from the stash and the
    // crafts completed are counted for the order's ledger; slots kept for the order's crafted loads and surplus are not put away
    private boolean fixedPlan;
    private long timeCapMs = TIME_CAP_MS;
    private int craftsDone;
    private final Map<String, Integer> gathered = new HashMap<>();
    // The slots the session took stacks from the stash into: what is left in them is stock, not something crafted
    private final Set<Integer> gatheredSlots = new LinkedHashSet<>();
    // A request for plain stacks is being planned (the pool of its survey leaves out named and patched stacks)
    private boolean surveyPlain;
    private final Set<Integer> orderKeep = new LinkedHashSet<>();

    Crafter(Owner owner) {
        this.owner = owner;
    }

    // The recipe table

    /**
     * The recipes of the running game version: the table fetched from the Stash Manager server (or built from the game's own jar), or
     * null while there is none. Nothing is shipped in the addon (0.4.0-dev.18), so with null everything that crafts is off.
     */
    static Recipes recipes() {
        Recipes table = Recipes.current();
        if (table != null) return table;
        // Crafting does not depend on stash-manager being on: the cache, or the game's own data, give the table (cheap once tried)
        try {
            RecipeBoot.bootstrap(GameVersion.name());
        } catch (RuntimeException | LinkageError e) {
            // the version is not known yet
        }
        return Recipes.current();
    }

    private static Map<String, Integer> stacks;

    /** The most of an item one stack holds (64 for an id that isn't known). */
    static synchronized int stackOf(String id) {
        if (stacks == null) {
            Map<String, Integer> m = new HashMap<>();
            for (net.minecraft.world.item.Item item : BuiltInRegistries.ITEM) m.put(BuiltInRegistries.ITEM.getKey(item).toString(), item.getDefaultInstance().getMaxStackSize());
            stacks = m;
        }
        return stacks.getOrDefault(Recipes.ns(id), 64);
    }

    static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    // State

    boolean active() {
        return active;
    }

    /** The running session crafts in the inventory's 2x2 grid: the sorter opens no table for it. */
    void useInventoryGrid() {
        if (active) inventoryGrid = true;
    }

    boolean inventoryGrid() {
        return active && inventoryGrid;
    }

    boolean holds(int slot) {
        return active && mine.contains(slot);
    }

    /** A crafted shulker box waiting for its use, or a stack kept for a craft order (a crafted load for its bin, the surplus between chunks). */
    boolean reserved(int slot) {
        return reserved.contains(slot) || orderKeep.contains(slot);
    }

    /** The slots a craft order keeps (crafted loads and surplus): the sorter does not put them away. Replaces the set. */
    void keepForOrder(Set<Integer> slots) {
        orderKeep.clear();
        orderKeep.addAll(slots);
    }

    void clearOrderKeep() {
        orderKeep.clear();
    }

    /** What the last chunk gathered from the stash, by item id (whole stacks as taken). */
    Map<String, Integer> gatheredFromStash() {
        return gathered;
    }

    Set<Integer> reservedSlots() {
        pruneReserved();
        return reserved;
    }

    /** The carry slot of a crafted, unused, empty shulker box, or -1. */
    int craftedBase() {
        pruneReserved();
        for (int slot : reserved) {
            ItemStack s = mc.player.getInventory().getItem(slot);
            if (Utils.isShulker(s.getItem()) && !s.has(DataComponents.CUSTOM_NAME) && emptyShulker(s)) return slot;
        }
        return -1;
    }

    private void pruneReserved() {
        if (mc.player == null) {
            reserved.clear();
            return;
        }
        reserved.removeIf(slot -> {
            ItemStack s = mc.player.getInventory().getItem(slot);
            // Used (gone, or being filled) boxes are not waiting any more
            return !Utils.isShulker(s.getItem()) || !emptyShulker(s);
        });
    }

    private static boolean emptyShulker(ItemStack s) {
        for (ItemStack in : Kits.inside(s)) if (in != null && !in.isEmpty()) return false;
        return true;
    }

    /** Crafted shulkers are not wanted any more (the order is over): they are carried like anything else and put away. */
    void release() {
        pruneReserved();
        for (int slot : reserved) {
            ItemStack s = mc.player.getInventory().getItem(slot);
            if (!s.isEmpty()) owner.held(slot, s);
        }
        if (!reserved.isEmpty()) owner.say("Keeping " + reserved.size() + " crafted Shulker Box in my inventory, they are put away like anything carried.");
        reserved.clear();
    }

    /** Forgets everything (turning off, leaving the world). Crafted shulkers stay where they are in the inventory. */
    void reset() {
        clearSession();
        reserved.clear();
        stamps.clear();
    }

    private void clearSession() {
        active = false;
        inventoryGrid = false;
        req = null;
        plan = null;
        tableDone = false;
        mine.clear();
        badSpots.clear();
        lastFetch = null;
        unpacked.clear();
        shulkersBefore.clear();
        fixedPlan = false;
        timeCapMs = TIME_CAP_MS;
        craftsDone = 0;
        gathered.clear();
        gatheredSlots.clear();
        stepIdx = craftIdx = fills = resultWait = takeTries = homeStuck = 0;
        srcSlot = -1;
        layout = null;
        layoutStep = layoutCraft = -1;
        lastTake = crafting = false;
        craftedTarget = stamped = 0;
        replans = 0;
        failure = null;
    }

    /** How many were crafted in the last hour. */
    int craftedLastHour() {
        long now = System.currentTimeMillis();
        while (!stamps.isEmpty() && now - stamps.peekFirst() > HOUR_MS) stamps.pollFirst();
        return stamps.size();
    }

    // Planning

    /**
     * Works out a craft without starting it.
     * @return the plan, or a refusal with the reason
     */
    CraftPlanner.Result preview(String item, int count, int doneOrder, Map<String, Long> pool, boolean ignoreOps) {
        Recipes recipes = recipes();
        if (recipes == null) return new CraftPlanner.Result(null, NO_RECIPES);
        CraftPlanner.Caps caps = owner.caps();
        // The caps are for shulker boxes: chests for the delivery storage (and the anvil) only keep the grid fill limit
        if (!item.equals("minecraft:shulker_box")) caps = new CraftPlanner.Caps(Integer.MAX_VALUE, caps.opsPerTask(), Integer.MAX_VALUE);
        if (ignoreOps) caps = new CraftPlanner.Caps(caps.perOrder(), Integer.MAX_VALUE, caps.perHour());
        return CraftPlanner.plan(recipes, item, count, pool, owner.neverUse(), caps, doneOrder, craftedLastHour());
    }

    /** @return null when the session started, else why not */
    String begin(Request r) {
        if (active) return "already crafting";
        // A plain-only request is planned from plain stacks only (the pool and the plan must agree with what is gathered)
        surveyPlain = r.plainOnly();
        Map<String, Long> pool;
        try {
            pool = r.pool().get();
        } finally {
            surveyPlain = false;
        }
        if (r.gatherOnly()) {
            // Only an item to be taken out of the stash (an anvil to place): no recipe, no table
            if (pool.getOrDefault(r.item(), 0L) < 1) return "none in the stash";
            clearSession();
            req = r;
            int n = Math.max(1, r.count());
            if (pool.getOrDefault(r.item(), 0L) < n) return "not enough in the stash";
            plan = new CraftPlanner.Plan(r.item(), n, List.of(), Map.of(r.item(), n), 0, null);
            active = true;
            startedAt = System.currentTimeMillis();
            snapshotBefore();
            return null;
        }
        CraftPlanner.Result result = preview(r.item(), r.count(), r.doneOrder(), pool, false);
        if (!result.ok()) return result.refusal();
        clearSession();
        req = r;
        plan = result.plan();
        active = true;
        startedAt = System.currentTimeMillis();
        snapshotBefore();
        owner.say(String.format("Crafting %d %s for %s: %s.", plan.count(), CraftPlanner.pretty(r.item()), r.purpose(), describe(plan)));
        return null;
    }

    /**
     * Starts a session for a craft-order chunk from the server's sub-plan: the plan is fixed (no planning of its own, no replan), only plain
     * stacks are taken, and the slots of {@code carried} (crafted surplus from earlier chunks) are the session's from the start, so the plan can use them.
     * @return null when it started, else why not
     */
    String beginPlan(Request r, CraftPlanner.Plan fixed, Set<Integer> carried) {
        if (active) return "already crafting";
        clearSession();
        req = r;
        plan = fixed;
        fixedPlan = true;
        mine.addAll(carried);
        // Gathering, then 2 minutes and 20 seconds a fill at the table (CRAFT-CONTRACT.md); the walk and the gathering get 5 minutes more
        timeCapMs = 5 * 60_000L + 120_000L + 20_000L * Math.max(1, fixed.ops());
        active = true;
        startedAt = System.currentTimeMillis();
        snapshotBefore();
        return null;
    }

    private static String describe(CraftPlanner.Plan plan) {
        StringBuilder sb = new StringBuilder();
        for (CraftPlanner.Step s : plan.steps()) {
            if (sb.length() > 0) sb.append(", then ");
            sb.append(s.crafts()).append("x ").append(CraftPlanner.pretty(s.recipe().result()));
        }
        return sb.toString();
    }

    private void replan() {
        replans++;
        // A craft order's plan is the server's: a stash that no longer holds it is a shortfall, not a reason to plan again
        if (fixedPlan) return;
        Map<String, Long> pool = new HashMap<>(req.pool().get());
        for (var e : carried().entrySet()) pool.merge(e.getKey(), (long) e.getValue(), Long::sum);
        CraftPlanner.Result result = preview(req.item(), plan.count(), req.doneOrder(), pool, false);
        if (result.ok()) plan = result.plan();
        else failure = result.refusal();
    }

    /** What the session ends with. */
    Outcome finish() {
        Outcome out = new Outcome(req == null ? "" : req.item(), craftedTarget, failure, craftsDone, new HashMap<>(gathered), new LinkedHashSet<>(gatheredSlots));
        adoptStrays();
        syncHeld();
        lastHeld.clear();
        lastHeld.addAll(mine);
        clearSession();
        return out;
    }

    /** Why the session is to be given up, or null. */
    String failure() {
        return failure;
    }

    /** The slots the last session held at its end (for the caller that wants the gathered stack). */
    Set<Integer> lastHeld() {
        return lastHeld;
    }

    void clearLastHeld() {
        lastHeld.clear();
    }

    private final Set<Integer> lastHeld = new LinkedHashSet<>();
    // What each slot held when the session began, to tell a stack the server put back into the inventory from one that was there
    private final Map<Integer, String> before = new HashMap<>();

    private void snapshotBefore() {
        before.clear();
        if (mc.player == null) return;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (!s.isEmpty()) before.put(i, idOf(s));
        }
    }

    /**
     * Items of the craft (ingredients, intermediates, results) now in a slot that did not hold that item when the session began:
     * the server put them there when it closed the table on a full grid. They are taken as the session's own, so they go back
     * to the stash like the rest.
     */
    void adoptStrays() {
        if (mc.player == null || plan == null) return;
        Set<String> ids = new HashSet<>(plan.take().keySet());
        for (CraftPlanner.Step st : plan.steps()) ids.add(st.recipe().result());
        Set<Integer> carry = owner.carry();
        for (int i = 0; i < 36; i++) {
            if (mine.contains(i) || !carry.contains(i)) continue;
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.isEmpty() || !ids.contains(idOf(s)) || idOf(s).equals(before.get(i))) continue;
            mine.add(i);
        }
    }

    /** Gives the session up for this reason. */
    void abort(String why) {
        if (active && failure == null) failure = why;
    }

    private void syncHeld() {
        if (mc.player == null) return;
        Inventory inv = mc.player.getInventory();
        for (int slot : mine) {
            ItemStack s = inv.getItem(slot);
            if (!s.isEmpty() && !reserved.contains(slot)) owner.held(slot, s);
        }
    }

    // Ingredients

    /** Whether the stack may be used as an ingredient: not damaged, holding nothing, not a shulker box, not on the never-use list. */
    boolean eligible(ItemStack s) {
        if (s.isEmpty() || s.isDamaged() || Utils.isShulker(s.getItem()) || holdsItems(s)) return false;
        // A craft order counts plain keys only: a stack with a component patch (a name, enchantments) is not an ingredient
        if (!com.porter.qwerte.craft.CraftPlain.allowed(surveyPlain || (active && req != null && req.plainOnly()), s.getComponentsPatch().isEmpty())) return false;
        return !owner.neverUse().contains(idOf(s));
    }

    private static boolean holdsItems(ItemStack s) {
        var container = s.get(DataComponents.CONTAINER);
        if (container != null && container.nonEmptyItems().iterator().hasNext()) return true;
        var bundle = s.get(DataComponents.BUNDLE_CONTENTS);
        return bundle != null && !bundle.isEmpty();
    }

    /** A container the stash may take ingredients from: the same sources orders use. */
    boolean source(Box box) {
        if (box.excluded || box.foreign || box.delivery || box.dropOff || box.unreachable || box.stale || !box.known() || !owner.usable(box)) return false;
        // Kit chests and chests for whole shulkers are never taken from; use-named-shulkers is only about unpacking named shulkers
        return !Kits.isKitBox(box) && !owner.keepWhole(box);
    }

    private boolean shulkerOk(ItemStack s) {
        if (!Utils.isShulker(s.getItem())) return false;
        if (s.has(DataComponents.CUSTOM_NAME) && !owner.namedShulkers()) return false;
        return !owner.claimed(s);
    }

    /** As {@link #survey()} for plain stacks only (no name, no enchantments) when {@code plain}: what a craft order's own table may be made of. */
    Map<String, Long> survey(boolean plain) {
        boolean before = surveyPlain;
        surveyPlain = plain;
        try {
            return survey();
        } finally {
            surveyPlain = before;
        }
    }

    /** Everything the stash could give, by item: loose stacks and the contents of shulkers, by the sources and rules above. */
    Map<String, Long> survey() {
        Map<String, Long> out = new HashMap<>();
        Stash stash = owner.stash();
        if (stash == null) return out;
        for (Box box : stash.boxes) {
            if (!source(box)) continue;
            for (ItemStack s : box.contents) {
                if (s.isEmpty()) continue;
                if (Utils.isShulker(s.getItem())) {
                    if (!shulkerOk(s)) continue;
                    for (ItemStack in : Kits.inside(s)) if (in != null && eligible(in)) out.merge(idOf(in), (long) in.getCount(), Long::sum);
                }
                else if (eligible(s)) out.merge(idOf(s), (long) s.getCount(), Long::sum);
            }
        }
        return out;
    }

    /** What the session carries, by item, in the slots it holds. */
    private Map<String, Integer> carried() {
        Map<String, Integer> out = new HashMap<>();
        if (mc.player == null) return out;
        Inventory inv = mc.player.getInventory();
        for (int slot : mine) {
            ItemStack s = inv.getItem(slot);
            if (!s.isEmpty()) out.merge(idOf(s), s.getCount(), Integer::sum);
        }
        return out;
    }

    /** What the plan still needs from the stash, by item. */
    private Map<String, Integer> missing() {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (plan == null) return out;
        Map<String, Integer> have = carried();
        for (var e : plan.take().entrySet()) {
            int left = e.getValue() - have.getOrDefault(e.getKey(), 0);
            if (left > 0) out.put(e.getKey(), left);
        }
        return out;
    }

    // The next step

    Intent next() {
        if (!active) return new Done();
        if (failure != null) return new Abort(failure);
        if (System.currentTimeMillis() - startedAt > timeCapMs) return new Abort("it took too long");
        if (mc.player == null || owner.stash() == null) return new Abort("no stash is loaded");
        if (tableDone) return new Done();
        // Once crafting has begun nothing more is gathered: what is in the grid or consumed is not missing
        if (crafting) return new Table();

        Map<String, Integer> missing = missing();

        // A shulker fetched for its contents is unpacked once
        if (lastFetch != null) {
            // The shulker asked for: where it landed
            int landed = -1;
            for (int slot : owner.carry()) {
                if (!shulkersBefore.contains(slot) && Utils.isShulker(mc.player.getInventory().getItem(slot).getItem())) landed = slot;
            }
            Spot asked = lastFetch;
            lastFetch = null;
            if (landed < 0) badSpots.add(asked);
            else {
                mine.add(landed);
                ItemStack s = mc.player.getInventory().getItem(landed);
                if (!missing.isEmpty() && holdsAny(s, missing) && unpacked.add(landed)) return new Unpack(landed);
            }
        }

        if (missing.isEmpty()) return req.gatherOnly() ? new Done() : new Table();

        if (owner.freeCarryCount() < 2) return new Abort("no room to carry what the craft needs (no free inventory slots)");

        // Loose stacks first, then shulkers; the nearest
        Spot loose = null, packed = null;
        double looseD = Double.MAX_VALUE, packedD = Double.MAX_VALUE;
        Stash stash = owner.stash();
        for (Box box : stash.boxes) {
            if (!source(box)) continue;
            double d = owner.distance(box);
            for (int i = 0; i < box.contents.length; i++) {
                ItemStack s = box.contents[i];
                if (s.isEmpty()) continue;
                if (Utils.isShulker(s.getItem())) {
                    if (!shulkerOk(s) || badSpots.contains(new Spot(box, i)) || !holdsAny(s, missing)) continue;
                    if (d < packedD) {
                        packedD = d;
                        packed = new Spot(box, i);
                    }
                }
                else if (missing.containsKey(idOf(s)) && eligible(s) && d < looseD) {
                    looseD = d;
                    loose = new Spot(box, i);
                }
            }
        }
        if (loose != null) return new Take(loose.box());
        if (packed != null) {
            lastFetch = packed;
            shulkersBefore.clear();
            for (int slot : owner.carry()) if (Utils.isShulker(mc.player.getInventory().getItem(slot).getItem())) shulkersBefore.add(slot);
            return new Fetch(packed.box(), packed.slot());
        }

        // The stash changed since it was planned: planned once more from what it holds now
        if (replans < 1) {
            replan();
            return next();
        }
        return new Abort(CraftPlanner.NOT_ENOUGH);
    }

    private boolean holdsAny(ItemStack shulker, Map<String, Integer> missing) {
        for (ItemStack in : Kits.inside(shulker)) if (in != null && !in.isEmpty() && eligible(in) && missing.containsKey(idOf(in))) return true;
        return false;
    }

    // Taking ingredients out of containers

    private static int inventoryToMenu(int index, int containerSize) {
        return index < 9 ? containerSize + 27 + index : containerSize + index - 9;
    }

    private Set<String> reservedKeys() {
        return req == null ? Set.of() : req.reservedKeys().get();
    }

    private int bestStack(int size, java.util.function.IntFunction<ItemStack> at, Map<String, Integer> missing) {
        Set<String> reserved = reservedKeys();
        int best = -1;
        long bestScore = Long.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            ItemStack s = at.apply(i);
            if (s.isEmpty() || !eligible(s)) continue;
            Integer deficit = missing.get(idOf(s));
            if (deficit == null) continue;
            // The smallest stack that covers what is missing, else the biggest; a stack another bin lot needs is the last choice
            long score = s.getCount() >= deficit ? s.getCount() : 1000 + (64 - s.getCount());
            String key = com.porter.qwerte.stash.StashIndex.keyOf(s);
            if (key != null && reserved.contains(key)) score += 100_000;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Takes one stack the craft needs out of the open container into a free carry slot. */
    boolean takeOne(Box box) {
        if (!active) return false;
        Map<String, Integer> missing = missing();
        if (missing.isEmpty()) return false;
        int into = owner.freeCarrySlot();
        if (into == -1) return false;
        int slot = bestStack(box.contents.length, i -> box.contents[i], missing);
        if (slot == -1) return false;

        owner.held(into, box.contents[slot]);
        mine.add(into);
        gathered.merge(idOf(box.contents[slot]), box.contents[slot].getCount(), Integer::sum);
        gatheredSlots.add(into);
        InvUtils.move().fromId(slot).toId(inventoryToMenu(into, box.contents.length));
        return true;
    }

    /** Takes one stack the craft needs out of the open shulker into a free carry slot (one stays free for the shulker). */
    boolean unpackOne(AbstractContainerMenu menu) {
        if (!active || menu.slots.size() != 27 + 36) return false;
        Map<String, Integer> missing = missing();
        if (missing.isEmpty()) return false;
        int into = owner.freeCarrySlot();
        if (into == -1 || owner.freeCarryCount() <= 1) return false;
        int slot = bestStack(27, i -> menu.getSlot(i).getItem(), missing);
        if (slot == -1) return false;

        owner.held(into, menu.getSlot(slot).getItem());
        mine.add(into);
        gathered.merge(idOf(menu.getSlot(slot).getItem()), menu.getSlot(slot).getItem().getCount(), Integer::sum);
        gatheredSlots.add(into);
        InvUtils.move().fromId(slot).toId(inventoryToMenu(into, 27));
        return true;
    }

    /** A shulker that was unpacked ended up in another slot of the inventory. */
    void relocated(int from, int to) {
        if (mine.remove(from)) mine.add(to);
    }

    /** The unpacking got nothing out of the shulker. */
    void unpackFailed() {
        if (lastFetch != null) badSpots.add(lastFetch);
    }

    // The crafting window

    /** The table job is over: it went through, or for this reason it did not. */
    void tableEnded(String why) {
        if (!active) return;
        // Only shulker boxes count towards the hourly cap
        for (; stamped < craftedTarget; stamped++) if (req != null && req.item().equals("minecraft:shulker_box")) stamps.addLast(System.currentTimeMillis());
        adoptStrays();
        syncHeld();
        if (why != null) {
            failure = why;
            return;
        }
        tableDone = true;
    }

    /** Whether the plan has been carried out in the window. */
    private CraftPlanner.Step currentStep() {
        if (plan == null) return null;
        while (stepIdx < plan.steps().size() && craftIdx >= plan.steps().get(stepIdx).crafts()) {
            stepIdx++;
            craftIdx = 0;
        }
        return stepIdx < plan.steps().size() ? plan.steps().get(stepIdx) : null;
    }

    // The 3x3 table window has the grid in slots 1-9 and the inventory in 10-45; the inventory window the 2x2 grid in 1-4 and the
    // inventory in 9-44 (armor 5-8, off hand 45 are never touched)
    private static boolean small(AbstractContainerMenu menu) {
        return menu instanceof InventoryMenu;
    }

    private static int gridSlots(boolean small) {
        return small ? 4 : 9;
    }

    private static int invFirst(boolean small) {
        return small ? 9 : 10;
    }

    private static int menuToInv(boolean small, int menuSlot) {
        if (small) return menuSlot >= 36 ? menuSlot - 36 : menuSlot;
        return menuSlot >= 37 ? menuSlot - 37 : menuSlot - 1;
    }

    private static boolean inventorySlot(boolean small, int menuSlot) {
        return menuSlot >= invFirst(small) && menuSlot <= invFirst(small) + 35;
    }

    /** The cell (0 to 8, in row order of the 3x3 grid) a grid slot of the window stands for. */
    private static int gridIndexOf(boolean small, int gridSlot) {
        return small ? ((gridSlot - 1) / 2) * 3 + (gridSlot - 1) % 2 : gridSlot - 1;
    }

    /** The next click in the open crafting table, or why it can't go on. Works the same from any point. */
    Act window(AbstractContainerMenu menu) {
        if (!active || plan == null || menu.slots.size() != 46) return Act.fail("couldn't open the crafting table");
        crafting = true;
        ItemStack cursor = menu.getCarried();
        CraftPlanner.Step step = currentStep();

        if (!cursor.isEmpty()) {
            if (credit(cursor)) step = currentStep();
            if (step != null && layout != null && layoutStep == stepIdx) {
                String id = idOf(cursor);
                for (int k = 1; k <= gridSlots(small(menu)); k++) {
                    if (id.equals(layout[gridIndexOf(small(menu), k)]) && menu.getSlot(k).getItem().isEmpty()) {
                        fills++;
                        if (fills > plan.ops() * 2 + 16) return Act.fail(com.porter.qwerte.craft.CraftHealth.GRID);
                        return Act.click(k, 1);
                    }
                }
            }
            return putAway(menu, cursor);
        }

        if (lastTake) {
            // The take did not give the product (yet): looked at again, a few times
            lastTake = false;
            if (++takeTries > MAX_TAKE_TRIES) return Act.fail(com.porter.qwerte.craft.CraftHealth.NOT_TAKEN);
        }
        homeStuck = 0;

        boolean small = small(menu);
        if (step == null) {
            for (int k = 1; k <= gridSlots(small); k++) if (!menu.getSlot(k).getItem().isEmpty()) return Act.click(k, 0);
            return Act.done();
        }

        if (layout == null || layoutStep != stepIdx || layoutCraft != craftIdx) {
            layout = layoutFor(step, small);
            layoutStep = stepIdx;
            layoutCraft = craftIdx;
            if (layout == null) return Act.fail(com.porter.qwerte.craft.CraftHealth.MISSING);
            resultWait = 0;
        }

        // Wrong things in the grid come out
        for (int k = 1; k <= gridSlots(small); k++) {
            ItemStack here = menu.getSlot(k).getItem();
            if (here.isEmpty()) continue;
            String want = layout[gridIndexOf(small, k)];
            if (want == null || !want.equals(idOf(here)) || here.getCount() > 1) return Act.click(k, 0);
        }
        // Then each empty cell that needs something gets it, from a held stack
        for (int k = 1; k <= gridSlots(small); k++) {
            String want = layout[gridIndexOf(small, k)];
            if (want == null || !menu.getSlot(k).getItem().isEmpty()) continue;
            int src = findSource(menu, want);
            if (src < 0) return Act.fail(com.porter.qwerte.craft.CraftHealth.MISSING);
            srcSlot = src;
            return Act.click(src, 0);
        }

        ItemStack result = menu.getSlot(0).getItem();
        if (result.isEmpty()) {
            if (++resultWait > RESULT_WAIT_TICKS) return Act.fail(com.porter.qwerte.craft.CraftHealth.NO_RESULT);
            return Act.waiting();
        }
        if (!idOf(result).equals(step.recipe().result())) return Act.fail(com.porter.qwerte.craft.CraftHealth.GRID);
        lastTake = true;
        return Act.click(0, 0);
    }

    /** A result taken with the click just made is on the cursor: it counts as crafted, whatever happens to it next. */
    private boolean credit(ItemStack cursor) {
        CraftPlanner.Step step = currentStep();
        if (!lastTake || step == null || !idOf(cursor).equals(step.recipe().result())) return false;
        lastTake = false;
        takeTries = 0;
        craftIdx++;
        craftsDone++;
        if (step.recipe().result().equals(req.item())) craftedTarget += step.recipe().count();
        layout = null;
        return true;
    }

    /** Whether the table is clean: nothing on the cursor, nothing in the grid. */
    boolean clean(AbstractContainerMenu menu) {
        if (!menu.getCarried().isEmpty()) return false;
        if (menu.slots.size() != 46) return true;
        for (int i = 1; i <= gridSlots(small(menu)); i++) if (!menu.getSlot(i).getItem().isEmpty()) return false;
        return true;
    }

    /**
     * One click towards a clean table (cursor and grid back in the inventory), used before the window is closed.
     * @return the click, or null when it is clean or nothing more can be done
     */
    Act drain(AbstractContainerMenu menu) {
        if (menu.slots.size() != 46) return null;
        ItemStack cursor = menu.getCarried();
        if (!cursor.isEmpty()) {
            credit(cursor);
            Act put = putAway(menu, cursor);
            return put.kind() == Act.CLICK ? put : null;
        }
        for (int i = 1; i <= gridSlots(small(menu)); i++) if (!menu.getSlot(i).getItem().isEmpty()) return Act.click(i, 0);
        return null;
    }

    /** Where a stack on the cursor goes: back where it was taken from, onto a stack it fits on, or an empty carry slot. */
    private Act putAway(AbstractContainerMenu menu, ItemStack cursor) {
        Set<Integer> carry = owner.carry();
        boolean small = small(menu);
        int first = invFirst(small), last = first + 35;
        int spot = -1;
        if (srcSlot >= 0 && inventorySlot(small, srcSlot) && usableSlot(carry, menuToInv(small, srcSlot))) {
            ItemStack there = menu.getSlot(srcSlot).getItem();
            if (there.isEmpty() || (ItemStack.isSameItemSameComponents(there, cursor) && there.getCount() < there.getMaxStackSize())) spot = srcSlot;
        }
        if (spot < 0) {
            for (int m = first; m <= last && spot < 0; m++) {
                ItemStack there = menu.getSlot(m).getItem();
                if (usableSlot(carry, menuToInv(small, m)) && !there.isEmpty() && ItemStack.isSameItemSameComponents(there, cursor) && there.getCount() < there.getMaxStackSize()) spot = m;
            }
        }
        if (spot < 0) {
            for (int m = first; m <= last && spot < 0; m++) {
                if (carry.contains(menuToInv(small, m)) && menu.getSlot(m).getItem().isEmpty()) spot = m;
            }
        }
        if (spot < 0) return ++homeStuck > 40 ? Act.fail(com.porter.qwerte.craft.CraftHealth.NO_ROOM) : Act.waiting();
        int inv = menuToInv(small, spot);
        mine.add(inv);
        // A crafted box of the wanted kind is kept for its use
        if (req != null && req.reserve() && Utils.isShulker(cursor.getItem()) && idOf(cursor).equals(req.item())) reserved.add(inv);
        owner.held(inv, cursor);
        return Act.click(spot, 0);
    }

    private boolean usableSlot(Set<Integer> carry, int inv) {
        return carry.contains(inv) || mine.contains(inv);
    }

    /** A held stack of the item in the inventory part of the menu, the smallest first. */
    private int findSource(AbstractContainerMenu menu, String id) {
        int best = -1;
        boolean small = small(menu);
        for (int m = invFirst(small); m <= invFirst(small) + 35; m++) {
            if (!mine.contains(menuToInv(small, m))) continue;
            ItemStack s = menu.getSlot(m).getItem();
            if (s.isEmpty() || !idOf(s).equals(id)) continue;
            if (best < 0 || s.getCount() < menu.getSlot(best).getItem().getCount()) best = m;
        }
        return best;
    }

    /** Which item goes in each grid cell for the next craft of the step: of the alternatives, the one held most of. */
    private String[] layoutFor(CraftPlanner.Step step, boolean small) {
        Map<String, Integer> have = carried();
        String[] out = new String[9];
        Recipes.Recipe r = step.recipe();
        for (int i = 0; i < r.cells().size(); i++) {
            List<String> alts = r.cells().get(i);
            if (alts == null) continue;
            // The inventory window's grid is 2x2: a cell in the third row or column has no place
            int g = r.gridIndex(i);
            if (small && (g % 3 > 1 || g / 3 > 1)) return null;
            String pick = null;
            int most = 0;
            for (String a : alts) {
                if (owner.neverUse().contains(a)) continue;
                int n = have.getOrDefault(a, 0);
                if (n > most || (n == most && n > 0 && pick != null && a.compareTo(pick) < 0)) {
                    pick = a;
                    most = n;
                }
            }
            if (pick == null) return null;
            have.put(pick, most - 1);
            out[r.gridIndex(i)] = pick;
        }
        return out;
    }

    /** Test hook: how many grid cells were filled so far. */
    int fills() {
        return fills;
    }
}
