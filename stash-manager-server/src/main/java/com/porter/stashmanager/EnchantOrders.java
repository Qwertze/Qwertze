package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.porter.qwerte.core.enchant.McData;
import com.porter.qwerte.core.enchant.Plan;
import com.porter.qwerte.core.enchant.PlanRequest;
import com.porter.qwerte.core.enchant.Planner;
import com.porter.qwerte.core.enchant.Stack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;

/**
 * Enchant lines in orders and the plan route (ENCHANT-CONTRACT v1.0): checks the request against the game rules, plans every
 * item with qwerte-core (the planner of record), turns each plan material into index keys (the stock allocation, limited to count
 * minus reservations: TL2-B3 / SR2-N6), verifies the plan, and reports what the order reserves ({@code take}). Planning never
 * happens under an order book's lock; the callers hold no lock while they call in here.
 */
final class EnchantOrders {
    static final int V = 1;
    static final int MAX_ITEMS = 16;
    static final int MAX_LINES = 8;
    static final int MAX_PER_LINE = 4;
    static final int MAX_TAKE = 256;
    /** Per order (create, retry, accept-curse): summed over the items. The clock is the real ceiling. */
    static final long ORDER_UNITS = 24_000_000L;
    static final long ORDER_MILLIS = 4000;
    /** The plan route's clock (Limits.maxMillis). */
    static final long PLAN_MILLIS = 1000;
    /** 2 anvils is an upper bound of the slots anvils take (fresh anvils stack). */
    static final int ANVIL_SLOTS = 2;
    static final Set<String> ANVILS = Set.of("minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil");
    /** Codes of a refused order or plan (the order-check codes, the planning codes and the server's own). */
    static final Set<String> CODES = Set.of("nothing_wanted", "unknown_enchant", "invalid_level", "duplicate", "wrong_item", "conflict",
        "bad_request", "no_base", "base_unusable", "books_short", "too_expensive", "too_complex", "xp_short", "inventory_full",
        "mc_unsupported", "busy");

    /** One enchant line asked for: line is its index in the request (error reporting), wants are plain ids with levels. */
    record Req(int line, String key, int count, List<OrderStore.EnchantWant> wants) {
    }

    /** The outcome of planning an order's enchant lines: the order's {@code enchant} JSON (take included), and the curses it needs. */
    record Result(JsonObject enchant, List<String> curse, long units) {
    }

    /** The pool key of a crafted base while planning (never an index key: it holds a NUL). */
    private static final String CRAFTED = "\u0000crafted";

    /**
     * The bases an order may craft instead of withdrawing (ENCHANT-CONTRACT draft 3.4, item 1.9). Discovery mode ({@link #discover}):
     * when no usable base is in stock, a fresh one (no enchantments, repair cost 0, undamaged, not named) is offered, one per item that
     * needs it, unless {@code refusal} says the item can't be crafted; {@link #used} counts them by namespaced item id. Fixed mode
     * ({@link #fixed}): at most the given number of fresh bases per item is offered, whether or not a real base exists (the craft plan
     * already exists: accept-curse, and the second pass of a placement). Not thread safe: one planning call owns it.
     */
    static final class BaseCraft {
        private final java.util.function.Function<String, String> refusal;
        private final Map<String, Integer> allowed;
        private final Map<String, Integer> used = new TreeMap<>();

        private BaseCraft(java.util.function.Function<String, String> refusal, Map<String, Integer> allowed) {
            this.refusal = refusal;
            this.allowed = allowed;
        }

        static BaseCraft discover(java.util.function.Function<String, String> refusal) {
            return new BaseCraft(refusal, null);
        }

        static BaseCraft fixed(Map<String, Integer> allowed) {
            return new BaseCraft(null, new TreeMap<>(allowed));
        }

        boolean isFixed() {
            return allowed != null;
        }

        /** In fixed mode how many more may be used for the item (a namespaced id); in discovery mode unlimited. */
        int remaining(String item) {
            return allowed == null ? Integer.MAX_VALUE : Math.max(0, allowed.getOrDefault(item, 0) - used.getOrDefault(item, 0));
        }

        /** Why the item can't be crafted as a base (discovery mode), or null when it can. */
        String refusal(String item) {
            return refusal == null ? null : refusal.apply(item);
        }

        void use(String item) {
            used.merge(item, 1, Integer::sum);
        }

        /** The bases the plans used, by item (a copy, sorted). */
        Map<String, Integer> used() {
            return new TreeMap<>(used);
        }
    }

    /** A planning refusal (not an exception for the caller to log): code is one of {@link #CODES}. */
    static final class Refused extends Exception {
        final String code;
        final long units;
        final boolean truncated;
        final boolean timedOut;

        Refused(String code, String message, long units, boolean truncated) {
            this(code, message, units, truncated, false);
        }

        Refused(String code, String message, long units, boolean truncated, boolean timedOut) {
            super(message);
            this.code = code;
            this.units = units;
            this.truncated = truncated;
            this.timedOut = timedOut;
        }
    }

    /** One planned item: the library's plan, the index key drawn for each material, and what the answer needs. */
    static final class Item {
        Plan plan;
        List<String> keys = new ArrayList<>();
        boolean estimate;
        long baseHave;
        final Map<String, Long> draws = new LinkedHashMap<>();
        final Map<String, Long> haveBefore = new HashMap<>();
        /** Whether the base (material 0) is crafted: its key is the plain item id and nothing is drawn for it. */
        boolean craftedBase;
        /** The inventory slots the bot needs at the start of this item (before crafted bases held for later items). */
        long slots;
    }

    private final IndexStore index;
    private final EnchantBots bots;
    private final Owners owners;
    private volatile RepairStore repair; // null: the repair reserve default is used
    private volatile CraftService craft; // null: a missing base is never crafted
    /** Order create, retry and accept plan on the calling thread through their own slots (a plan flood can't block orders). */
    private final Semaphore placeSlots = new Semaphore(2);
    /** Tests lower these. */
    volatile long orderUnits = ORDER_UNITS;
    volatile long orderMillis = ORDER_MILLIS;

    EnchantOrders(IndexStore index, EnchantBots bots, Owners owners) {
        this.index = index;
        this.bots = bots;
        this.owners = owners;
        // The tables are read once, here, so no request pays for (or races on) the first load.
        for (String v : McData.SUPPORTED) {
            try {
                McData.load(v);
            } catch (RuntimeException e) {
                System.err.println("Enchant table for " + v + " could not be loaded: " + e.getClass().getName());
            }
        }
    }

    void repair(RepairStore repair) {
        this.repair = repair;
    }

    /** Gives the planner the craft service (set once at start): without it a missing base is always {@code no_base}. */
    void craft(CraftService craft) {
        this.craft = craft;
    }

    /**
     * A discovery-mode {@link BaseCraft} when the stash can take craft orders right now (the craft service's own check: recipes, a capable
     * bot, a usable table), else null (a missing base is then {@code no_base}, as before). Whether one item can be crafted is decided per item.
     */
    BaseCraft baseCraft(String server, String stash) {
        CraftService cs = craft;
        if (cs == null) return null;
        CraftService.Status st = cs.status(server, stash);
        if (!st.canOrder() || st.recipes() == null || st.settings() == null) return null;
        CraftPlanner.Eligible el = cs.eligible(st.recipes(), st.settings().neverUse());
        return BaseCraft.discover(item -> baseRefusal(el, item));
    }

    /** Why a base of this item (a namespaced id) can't be crafted at a crafting table, or null when it can. */
    static String baseRefusal(CraftPlanner.Eligible el, String item) {
        if (McData.norm(item).startsWith("netherite_")) {
            return "it can't be crafted at a crafting table (a netherite item needs a smithing table); put one in the stash";
        }
        if (el.making(item).isEmpty()) {
            String station = el.table.otherStations().get(item);
            return station != null ? "it can't be crafted at a crafting table (it needs " + CraftPlanner.stationName(station) + "); put one in the stash"
                : "it has no crafting recipe; put one in the stash";
        }
        return null;
    }

    EnchantBots bots() {
        return bots;
    }

    IndexStore index() {
        return index;
    }

    Owners owners() {
        return owners;
    }

    // ---------- shared pieces ----------

    /** The stash's Minecraft version when a table exists for it, else null. */
    String supportedMc(String server, String stash) {
        String mc = index.mcVersion(server, stash);
        return mc != null && McData.SUPPORTED.contains(mc) ? mc : null;
    }

    /** Whether an enchant-capable bot serves the stash right now (a fresh enabled block, the index's version, the owner's player). */
    boolean botReady(String server, String stash, String mc) {
        return bots.capable(server, stash, owners.owner(server, stash), mc, null);
    }

    /**
     * The claim rule (ENCHANT-CONTRACT v1.0): a stored heartbeat block with enabled true, newer than about 2 minutes, whose mc equals the
     * index's mcVersion, from a heartbeat whose player is the stash owner, and a claim whose player (PearlBodies.PLAYER grammar) is that
     * owner too. The claim flag is the caller's check.
     */
    boolean claimReady(String server, String stash, String claimPlayer) {
        String mc = supportedMc(server, stash);
        return mc != null && bots.capable(server, stash, owners.owner(server, stash), mc, claimPlayer);
    }

    /**
     * Bottles the stash holds above what open orders reserve and the repair reserve (REPAIR-CONTRACT v2.0 {@code usable}), before
     * the order being planned reserves its own. An unreadable settings file uses the default reserve 1,728.
     */
    /** The stash's repair reserve (default 1,728 when the settings are unreadable). */
    long reserve(String server, String stash) {
        RepairStore rs = repair;
        if (rs != null) {
            try {
                return rs.get(server, stash).reserve();
            } catch (RepairStore.DisabledException e) {
                // the default applies
            }
        }
        return RepairStore.DEFAULT_RESERVE;
    }

    long bottlesUsable(String server, String stash, IndexStore.OrderCounts counts, Map<String, Long> reserved) {
        long stashBottles = OrderStore.bottlesAvailable(counts, reserved);
        int reserve = RepairStore.DEFAULT_RESERVE;
        RepairStore rs = repair;
        if (rs != null) {
            try {
                reserve = rs.get(server, stash).reserve();
            } catch (RepairStore.DisabledException e) {
                // the default applies
            }
        }
        return Math.max(0, stashBottles - reserve);
    }

    private static Planner.Limits limits(long maxMillis) {
        Planner.Limits d = Planner.Limits.DEFAULT;
        return new Planner.Limits(d.maxWork(), Math.max(1, maxMillis), d.maxBases(), d.maxStockEntries(), d.maxSets());
    }

    /** What one planning call works on: the stash's counts, what is left of every key, and the sorted key list (the request order). */
    private static final class Ctx {
        final IndexStore.OrderCounts counts;
        final Map<String, Long> reserved;
        final Map<String, Long> avail = new HashMap<>();
        final List<String> keys;
        final McData d;
        final boolean forOrder;

        Ctx(IndexStore.OrderCounts counts, Map<String, Long> reserved, McData d, boolean forOrder) {
            this.counts = counts;
            this.reserved = reserved;
            this.d = d;
            this.forOrder = forOrder;
            // Sorted by key first: the same index gives the same plan and the same allocation on every run.
            this.keys = new ArrayList<>(counts.items().keySet());
            java.util.Collections.sort(keys);
        }

        long have(String key) {
            Long v = avail.get(key);
            if (v != null) return v;
            long total = counts.total("item", key);
            long n = total < 0 ? 0 : Math.max(0, total - reserved.getOrDefault("item:" + key, 0L));
            avail.put(key, n);
            return n;
        }

        void take(String key) {
            avail.put(key, have(key) - 1);
        }
    }

    private static final class Pool {
        final List<PlanRequest.Stock> stock = new ArrayList<>();
        /** The base candidates by key while the pool is being built (undamaged ones first). */
        final List<String> entries = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        final List<Boolean> unknown = new ArrayList<>();
        Map<String, List<Integer>> groups;
        long have;
        int noData;

        String groupKey(int i) {
            Stack s = stock.get(i).stack();
            return s.enchants() + "#" + s.repairCost();
        }

        List<Integer> group(int i) {
            if (groups == null) {
                groups = new HashMap<>();
                for (int j = 0; j < stock.size(); j++) groups.computeIfAbsent(groupKey(j), g -> new ArrayList<>()).add(j);
            }
            return groups.getOrDefault(groupKey(i), List.of(i));
        }
    }

    private static boolean sameItem(String idInIndex, String item) {
        return idInIndex != null && McData.norm(idInIndex).equals(item);
    }

    /**
     * Plans one item. item is the plain item id, wants carry plain enchantment ids. Throws Refused for a refusal (a plan that cannot be
     * made, or that the bot could not carry out). Draws the allocated keys from ctx.
     */
    private Item planItem(Ctx ctx, String item, List<PlanRequest.Wanted> wanted, long maxMillis, int carry, BaseCraft bc) throws Refused {
        String itemId = EnchantIndex.namespaced(item);
        Pool bases = new Pool();
        Pool books = new Pool();
        List<String> damagedBases = new ArrayList<>();
        for (String key : ctx.keys) {
            String id = ctx.counts.itemIds().get(key);
            if (id == null) continue;
            boolean isBook = McData.norm(id).equals(EnchantIndex.BOOK);
            boolean isBase = !isBook && sameItem(id, item);
            if (!isBook && !isBase) continue;
            long have = ctx.have(key);
            if (have <= 0) continue;
            EnchantIndex.Entry e = ctx.counts.enchant().get(key);
            if (isBook) {
                if (e == null || !e.known()) continue; // a book of unknown enchantments is no use
                books.stock.add(new PlanRequest.Stock(Stack.book(e.ench(), Math.max(0, e.rc())), (int) Math.min(have, 1_000_000L)));
                books.keys.add(key);
                books.unknown.add(e.rc() < 0);
            } else {
                if (e != null && e.named()) continue; // custom-named: never offered (the library has no name field)
                if (e == null || !e.known()) {
                    // No ench: unplannable. The plan route plans it as a fresh item (an estimate); an order refuses it.
                    if (ctx.forOrder) {
                        bases.noData++;
                        continue;
                    }
                }
                // An undamaged base is preferred: a damaged one comes after every undamaged one (the library keeps this order for ties).
                (ctx.counts.repair(key).damaged() ? damagedBases : bases.entries).add(key);
            }
        }
        for (String key : damagedBases) bases.entries.add(key);
        for (String key : bases.entries) {
            EnchantIndex.Entry e = ctx.counts.enchant().get(key);
            long have = ctx.have(key);
            if (e == null || !e.known()) {
                bases.stock.add(new PlanRequest.Stock(Stack.fresh(), (int) Math.min(have, 1_000_000L)));
                bases.keys.add(key);
                bases.unknown.add(true);
            } else {
                bases.stock.add(new PlanRequest.Stock(Stack.item(e.ench(), Math.max(0, e.rc())), (int) Math.min(have, 1_000_000L)));
                bases.keys.add(key);
                bases.unknown.add(e.rc() < 0);
            }
            bases.have += have;
        }
        // A crafted base is a fresh one (no enchantments, repair cost 0): the library sees it as one more stock entry, after the real ones
        // (so a real base of the same kind is drawn first). Fixed mode always offers what the order's craft plan makes; discovery mode
        // offers one only when the real stock has no usable base.
        boolean offered = false;
        if (bc != null && bc.isFixed() && bc.remaining(itemId) > 0) {
            bases.stock.add(new PlanRequest.Stock(Stack.fresh(), bc.remaining(itemId)));
            bases.keys.add(CRAFTED);
            bases.unknown.add(false);
            offered = true;
        }
        Plan p = Planner.plan(ctx.d, new PlanRequest(item, wanted, bases.stock, books.stock), limits(maxMillis));
        if (!p.ok() && !offered && bc != null && !bc.isFixed() && bases.noData == 0
            && (Planner.NO_BASE.equals(p.code()) || Planner.BASE_UNUSABLE.equals(p.code()))) {
            // No usable base in the stash: plan with one crafted base (one per item; the order's craft plan makes it).
            String why = bc.refusal(itemId);
            if (why != null) {
                if (Planner.NO_BASE.equals(p.code())) {
                    throw new Refused(Planner.NO_BASE, "There is no " + item + " in stock to enchant, and " + why, p.workUnits(), false);
                }
            } else {
                bases.stock.add(new PlanRequest.Stock(Stack.fresh(), 1));
                bases.keys.add(CRAFTED);
                bases.unknown.add(false);
                offered = true;
                p = Planner.plan(ctx.d, new PlanRequest(item, wanted, bases.stock, books.stock), limits(maxMillis));
            }
        }
        if (!p.ok()) {
            String msg = p.message();
            if (Planner.NO_BASE.equals(p.code()) && bases.noData > 0) {
                msg = "The " + item + " in stock has no enchantment data in the stash index (an older bot?); rescan with an updated bot";
            }
            // A search the clock cut short is never a plain "no": it is too_complex.
            throw new Refused(p.timedOut() ? Planner.TOO_COMPLEX : p.code(), msg, p.workUnits(), p.truncated(), p.timedOut());
        }
        if (p.timedOut() && ctx.forOrder) {
            throw new Refused(Planner.TOO_COMPLEX, "Planning took too long; try fewer enchantments or fewer books", p.workUnits(), true);
        }
        List<String> problems = Planner.verify(ctx.d, item, p);
        if (!problems.isEmpty()) {
            System.err.println("Enchant plan failed its own check: " + problems);
            throw new Refused(Planner.BAD_REQUEST, "The plan could not be verified", p.workUnits(), false);
        }
        Item out = new Item();
        out.plan = p;
        out.baseHave = bases.have;
        for (Plan.Material m : p.materials()) {
            boolean base = "base".equals(m.kind());
            Pool pool = base ? bases : books;
            String drawn = null;
            int at = -1;
            for (int i : pool.group(m.stockIndex())) {
                String k = pool.keys.get(i);
                if (CRAFTED.equals(k) ? bc != null && bc.remaining(itemId) > 0 : ctx.have(k) > 0) {
                    drawn = k;
                    at = i;
                    break;
                }
            }
            if (drawn == null) {
                throw new Refused(base ? Planner.NO_BASE : Planner.BOOKS_SHORT, base ? "The stash no longer holds the base item this plan needs"
                    : "The stash no longer holds the books this plan needs", p.workUnits(), p.truncated());
            }
            if (CRAFTED.equals(drawn)) {
                // Made by the order's craft plan: nothing is drawn from the stash, and it is not in the take.
                bc.use(itemId);
                out.craftedBase = true;
                out.keys.add(itemId);
                continue;
            }
            out.haveBefore.putIfAbsent(drawn, ctx.have(drawn));
            ctx.take(drawn);
            out.draws.merge(drawn, 1L, Long::sum);
            out.keys.add(drawn);
            if (pool.unknown.get(at)) out.estimate = true;
        }
        // The slots the bot needs at the start of this item: base, books, anvils, one source shulker, the bottles (D4, TL3-B-b).
        int bookCount = 0;
        for (Plan.Material m : p.materials()) if ("book".equals(m.kind())) bookCount++;
        long bottleSlots = (p.bottles() + 63L) / 64;
        long slots = 1 + bookCount + ANVIL_SLOTS + 1 + bottleSlots;
        out.slots = slots;
        if (slots > carry) {
            throw new Refused("inventory_full", "This needs " + slots + " inventory slots (the base, " + bookCount + " book" + (bookCount == 1 ? "" : "s")
                + ", " + ANVIL_SLOTS + " anvils, a shulker and " + bottleSlots + " for the " + p.bottles() + " bottles) but the bot has " + carry
                + " free; try fewer enchantments", p.workUnits(), p.truncated());
        }
        return out;
    }

    private static JsonObject planJson(Plan p, List<String> keys, boolean craftedBase) {
        JsonObject o = new JsonObject();
        JsonArray mats = new JsonArray();
        for (int i = 0; i < p.materials().size(); i++) {
            Plan.Material m = p.materials().get(i);
            JsonObject mo = new JsonObject();
            mo.addProperty("kind", m.kind());
            mo.addProperty("key", keys.get(i));
            mo.add("ench", EnchantIndex.list(m.enchants()));
            mo.addProperty("rc", m.repairCost());
            if (craftedBase && "base".equals(m.kind())) mo.addProperty("crafted", true); // draft 3.4: made by the order's craft block
            mats.add(mo);
        }
        o.add("materials", mats);
        JsonArray steps = new JsonArray();
        for (Plan.Step s : p.steps()) {
            JsonObject so = new JsonObject();
            so.addProperty("left", s.left());
            so.addProperty("right", s.right());
            so.addProperty("cost", s.cost());
            so.addProperty("rc", s.repairCost());
            steps.add(so);
        }
        o.add("steps", steps);
        o.add("levels", EnchantIndex.list(new TreeMap<>(p.levels())));
        o.add("extras", EnchantIndex.list(new TreeMap<>(p.extras())));
        JsonArray curse = new JsonArray();
        for (String c : p.cursed()) curse.add(EnchantIndex.namespaced(c));
        o.add("curse", curse);
        o.addProperty("totalLevels", p.totalLevels());
        o.addProperty("points", p.points());
        o.addProperty("bottles", p.bottles());
        o.addProperty("finalRc", p.finalRepairCost());
        return o;
    }

    /**
     * The request's {@code enchants}: an array of at most 64 {id, lvl} objects (the game rules are checked later, by the library: this is
     * the syntax), ids of the id grammar, levels whole 1..255. Ids are returned namespaced. Null when the shape is wrong.
     */
    static List<OrderStore.EnchantWant> parseWants(com.google.gson.JsonElement e) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() > 64) return null;
        List<OrderStore.EnchantWant> out = new ArrayList<>();
        for (com.google.gson.JsonElement x : e.getAsJsonArray()) {
            if (!(x instanceof JsonObject o)) return null;
            if (!(o.get("id") instanceof com.google.gson.JsonPrimitive p) || !p.isString() || !EnchantIndex.ID.matcher(p.getAsString()).matches()) return null;
            long lvl = EnchantIndex.whole(o.get("lvl"), 1, EnchantIndex.MAX_LEVEL);
            if (lvl < 0) return null;
            out.add(new OrderStore.EnchantWant(EnchantIndex.namespaced(McData.norm(p.getAsString())), (int) lvl));
        }
        return out;
    }

    /** A line or request item key: a namespaced plain item id (no #hash). */
    static boolean validItem(String key) {
        return key != null && key.length() <= 256 && CraftStore.ITEM_ID.matcher(key).matches();
    }

    private static List<PlanRequest.Wanted> wanted(List<OrderStore.EnchantWant> wants) {
        List<PlanRequest.Wanted> out = new ArrayList<>();
        for (OrderStore.EnchantWant w : wants) out.add(new PlanRequest.Wanted(McData.norm(w.id()), w.lvl()));
        return out;
    }

    private static OrderStore.OrderException refusal(Refused r, int line) {
        return OrderStore.OrderException.enchant(400, r.getMessage(), line, r.code);
    }

    // ---------- orders ----------

    /**
     * Plans every enchant line of an order (create, retry, accept-curse). reserved is what the stash's other open orders reserve plus
     * the rest of this order (its item lines, craft and kit takes), by "item:&lt;key&gt;". requireBot: an order being placed needs an
     * enchant-capable bot (409 no_bot); accept-curse does not. Over {@link #orderUnits} work units or {@link #orderMillis} ms summed
     * over the items the order is refused as too_complex and nothing is stored.
     */
    Result planOrder(String server, String stash, IndexStore.OrderCounts counts, Map<String, Long> reserved, List<Req> reqs,
                     boolean requireBot) throws OrderStore.OrderException {
        return planOrder(server, stash, counts, reserved, reqs, requireBot, new Budget(), null);
    }

    /** The clock and the work units of one order, kept across the attempts of one placement (a re-plan after a change is not a fresh budget). */
    static final class Budget {
        final long startNs = System.nanoTime();
        long units;
    }

    /** Test hook: runs where the library is about to be called (a test throws an Error from it). */
    volatile Runnable beforePlan;

    /**
     * The same with the order's budget and the bases it may craft (null: none; ENCHANT-CONTRACT draft 3.4). Anything the library throws that is not a refusal (an Error) never reaches the caller as such: it is a clean
     * 503 and nothing is stored; only a VirtualMachineError is rethrown.
     */
    Result planOrder(String server, String stash, IndexStore.OrderCounts counts, Map<String, Long> reserved, List<Req> reqs,
                     boolean requireBot, Budget budget, BaseCraft bc) throws OrderStore.OrderException {
        try {
            return planOrder0(server, stash, counts, reserved, reqs, requireBot, budget, bc);
        } catch (OrderStore.OrderException | CraftService.BusyException e) {
            throw e;
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            System.err.println("Enchant planning failed: " + t.getClass().getName());
            throw new CraftService.BusyException(503, 2, "the server could not plan this order; try again in a moment");
        }
    }

    private Result planOrder0(String server, String stash, IndexStore.OrderCounts counts, Map<String, Long> reserved, List<Req> reqs,
                              boolean requireBot, Budget budget, BaseCraft bc) throws OrderStore.OrderException {
        // Caps, before any planning.
        if (reqs.size() > MAX_LINES) {
            throw OrderStore.OrderException.enchant(400, "at most " + MAX_LINES + " enchant lines in one order", reqs.get(MAX_LINES).line(), "too_complex");
        }
        long items = 0;
        for (Req r : reqs) {
            if (r.count() < 1 || r.count() > MAX_PER_LINE) {
                throw OrderStore.OrderException.enchant(400, "count of an enchant line must be a whole number from 1 to " + MAX_PER_LINE, r.line(), "bad_request");
            }
            items += r.count();
            if (items > MAX_ITEMS) {
                throw OrderStore.OrderException.enchant(400, "at most " + MAX_ITEMS + " enchanted items in one order", r.line(), "too_complex");
            }
        }
        String mc = supportedMc(server, stash);
        if (mc == null) {
            throw OrderStore.OrderException.enchant(409, "this stash's Minecraft version has no enchantment table here", reqs.get(0).line(), "mc_unsupported");
        }
        McData d = McData.load(mc);
        for (Req r : reqs) {
            Plan bad = Planner.validate(d, McData.norm(r.key()), wanted(r.wants()));
            if (bad != null) throw OrderStore.OrderException.enchant(400, bad.message(), r.line(), bad.code());
        }
        if (requireBot && !botReady(server, stash, mc)) {
            throw OrderStore.OrderException.enchant(409, "no stash bot can enchant here right now", reqs.get(0).line(), "no_bot");
        }
        if (!placeSlots.tryAcquire()) throw new CraftService.BusyException(503, 2, "the server is busy planning enchant orders; try again in a moment");
        try {
            return planLocked(server, stash, counts, reserved, reqs, d, mc, budget, bc);
        } finally {
            placeSlots.release();
        }
    }

    private Result planLocked(String server, String stash, IndexStore.OrderCounts counts, Map<String, Long> reserved, List<Req> reqs,
                              McData d, String mc, Budget budget, BaseCraft bc) throws OrderStore.OrderException {
        Runnable hook = beforePlan;
        if (hook != null) hook.run();
        Ctx ctx = new Ctx(counts, reserved, d, true);
        int carry = bots.carry(server, stash);
        long startNs = budget.startNs;
        long bottles = 0;
        Map<String, Long> take = new LinkedHashMap<>();
        java.util.TreeSet<String> curses = new java.util.TreeSet<>();
        JsonArray lines = new JsonArray();
        List<Item> planned = new ArrayList<>();
        List<Integer> plannedAt = new ArrayList<>();
        for (Req r : reqs) {
            JsonObject lo = new JsonObject();
            lo.addProperty("key", r.key());
            lo.addProperty("count", r.count());
            lo.add("enchants", wantsJson(r.wants()));
            JsonArray plans = new JsonArray();
            for (int i = 0; i < r.count(); i++) {
                long left = orderMillis - (System.nanoTime() - startNs) / 1_000_000L;
                if (left <= 0) throw tooComplex(r.line());
                Item it;
                try {
                    it = planItem(ctx, McData.norm(r.key()), wanted(r.wants()), left, carry, bc);
                } catch (Refused ref) {
                    throw refusal(ref, r.line());
                }
                budget.units += it.plan.workUnits();
                if (budget.units > orderUnits) throw tooComplex(r.line());
                if (it.estimate) {
                    throw OrderStore.OrderException.enchant(400, "the stash index lacks repair-cost data for items this plan uses (an older bot?); "
                        + "rescan with an updated bot", r.line(), "books_short");
                }
                it.draws.forEach((k, n) -> take.merge(k, n, Long::sum));
                bottles += it.plan.bottles();
                curses.addAll(it.plan.cursed());
                plans.add(planJson(it.plan, it.keys, it.craftedBase));
                planned.add(it);
                plannedAt.add(r.line());
            }
            lo.add("plans", plans);
            lines.add(lo);
        }
        // Crafted bases are all made before the first item, so the bot carries the later items' bases (one slot each: unenchanted gear
        // does not stack) while it works on an earlier one (draft 3.4 formula: slots(item) + crafted bases of the items after it).
        for (int i = 0; i < planned.size(); i++) {
            long later = 0;
            for (int j = i + 1; j < planned.size(); j++) if (planned.get(j).craftedBase) later++;
            if (later > 0 && planned.get(i).slots + later > carry) {
                throw OrderStore.OrderException.enchant(400, "This needs " + (planned.get(i).slots + later) + " inventory slots at once (" + planned.get(i).slots
                    + " for the item and " + later + " crafted base" + (later == 1 ? "" : "s") + " held for the items after it) but the bot has " + carry
                    + " free; order fewer items at once", plannedAt.get(i), "inventory_full");
            }
        }
        long usable = bottlesUsable(server, stash, counts, reserved);
        if (bottles > usable) {
            throw OrderStore.OrderException.enchant(400, "This order needs " + bottles + " bottles of enchanting, but only " + usable
                + " are free above the repair reserve; add bottles to the stash or lower the reserve", reqs.get(0).line(), "xp_short");
        }
        if (bottles > 0) take.merge(Repair.BOTTLE_KEY, bottles, Long::sum);
        // Anvils (D4): 2 when none stands at the stash, otherwise 1 spare. Chipped and damaged anvils count.
        IndexStore.Naming naming = index.naming(server, stash);
        long needAnvils = naming != null && naming.anvil() ? 1 : 2;
        for (String key : ctx.keys) {
            if (needAnvils <= 0) break;
            String id = counts.itemIds().get(key);
            if (id == null || !ANVILS.contains(id)) continue;
            long n = Math.min(needAnvils, ctx.have(key));
            if (n <= 0) continue;
            take.merge(key, n, Long::sum);
            needAnvils -= n;
        }
        if (take.size() > MAX_TAKE) throw tooComplex(reqs.get(0).line());
        JsonObject enchant = new JsonObject();
        enchant.addProperty("v", V);
        enchant.addProperty("mc", mc);
        enchant.add("lines", lines);
        JsonArray takeJson = new JsonArray();
        take.forEach((k, n) -> {
            JsonObject t = new JsonObject();
            t.addProperty("key", k);
            t.addProperty("count", n);
            takeJson.add(t);
        });
        enchant.add("take", takeJson);
        JsonArray curseJson = new JsonArray();
        for (String c : curses) curseJson.add(EnchantIndex.namespaced(c));
        enchant.add("curse", curseJson);
        return new Result(enchant, new ArrayList<>(curses.stream().map(EnchantIndex::namespaced).toList()), budget.units);
    }

    private static OrderStore.OrderException tooComplex(int line) {
        return OrderStore.OrderException.enchant(400, "This order is too big to plan; try fewer items, enchantments or books", line, "too_complex");
    }

    static JsonArray wantsJson(List<OrderStore.EnchantWant> wants) {
        JsonArray a = new JsonArray();
        for (OrderStore.EnchantWant w : wants) {
            JsonObject o = new JsonObject();
            o.addProperty("id", EnchantIndex.namespaced(McData.norm(w.id())));
            o.addProperty("lvl", w.lvl());
            a.add(o);
        }
        return a;
    }

    // ---------- the plan route ----------

    /** What the plan route computed (before it is turned into JSON): a plan with its availability, or a refusal. */
    record Answer(boolean ok, String code, String message, JsonObject plan, boolean estimate, boolean canOrder, String mc,
                  JsonObject availability, boolean truncated, boolean timedOut, long units) {
    }

    /**
     * One item for the plan route (advisory: order create re-plans). Computes against the stash's counts and reservations; the
     * answer carries a refusal as data. Runs on the plan pool's thread with the route's clock.
     */
    /**
     * The cheap checks of the plan route (the stash's version, the game rules): a refusal answer, or null when planning is needed. Run before the
     * user's rate and any permit, so a refusal costs neither.
     */
    Answer precheck(String server, String stash, String key, List<OrderStore.EnchantWant> wants) {
        String mc = supportedMc(server, stash);
        if (mc == null) return refused("mc_unsupported", "This stash's Minecraft version has no enchantment table here", null, 0, false, false);
        Plan bad = Planner.validate(McData.load(mc), McData.norm(key), wanted(wants));
        return bad == null ? null : refused(bad.code(), bad.message(), mc, 0, false, false);
    }

    Answer planOne(String server, String stash, IndexStore.OrderCounts counts, Map<String, Long> reserved, String key,
                   List<OrderStore.EnchantWant> wants, long maxMillis) {
        String mc = supportedMc(server, stash);
        if (mc == null) return refused("mc_unsupported", "This stash's Minecraft version has no enchantment table here", null, 0, false, false);
        McData d = McData.load(mc);
        String item = McData.norm(key);
        Plan bad = Planner.validate(d, item, wanted(wants));
        if (bad != null) return refused(bad.code(), bad.message(), mc, 0, false, false);
        Ctx ctx = new Ctx(counts, reserved, d, false);
        BaseCraft bc = baseCraft(server, stash);
        int carry = bots.carry(server, stash);
        Item it;
        try {
            it = planItem(ctx, item, wanted(wants), maxMillis, carry, bc);
        } catch (Refused r) {
            return refused(r.timedOut ? Planner.TOO_COMPLEX : r.code, r.getMessage(), mc, r.units, r.truncated, r.timedOut);
        }
        long usable = bottlesUsable(server, stash, counts, reserved);
        if (it.plan.bottles() > usable) {
            return refused("xp_short", "This needs " + it.plan.bottles() + " bottles of enchanting, but only " + usable
                + " are free above the repair reserve", mc, it.plan.workUnits(), it.plan.truncated(), it.plan.timedOut());
        }
        boolean ready = botReady(server, stash, mc);
        // The plan route is advisory: with no capable bot it is an estimate and cannot be ordered (TL-L4).
        boolean estimate = it.estimate || !ready;
        JsonObject avail = new JsonObject();
        JsonArray bookList = new JsonArray();
        for (Map.Entry<String, Long> e : it.draws.entrySet()) {
            String id = counts.itemIds().get(e.getKey());
            if (id == null || !McData.norm(id).equals(EnchantIndex.BOOK)) continue;
            JsonObject b = new JsonObject();
            b.addProperty("key", e.getKey());
            b.addProperty("need", e.getValue());
            b.addProperty("have", it.haveBefore.getOrDefault(e.getKey(), 0L));
            bookList.add(b);
        }
        avail.add("books", bookList);
        JsonObject base = new JsonObject();
        base.addProperty("have", it.baseHave);
        if (it.craftedBase) base.addProperty("craft", true); // no usable base in the stash: the order would craft one (draft 3.4)
        avail.add("base", base);
        IndexStore.Naming naming = index.naming(server, stash);
        long needAnvils = naming != null && naming.anvil() ? 1 : 2;
        long haveAnvils = 0;
        for (String k : counts.items().keySet()) {
            String id = counts.itemIds().get(k);
            if (id != null && ANVILS.contains(id)) haveAnvils += ctx.have(k);
        }
        JsonObject anvil = new JsonObject();
        anvil.addProperty("need", needAnvils);
        anvil.addProperty("have", haveAnvils);
        avail.add("anvil", anvil);
        JsonObject bottles = new JsonObject();
        bottles.addProperty("need", it.plan.bottles());
        bottles.addProperty("usable", usable);
        avail.add("bottles", bottles);
        return new Answer(true, null, null, planJson(it.plan, it.keys, it.craftedBase), estimate, ready && !estimate, mc, avail, it.plan.truncated(),
            it.plan.timedOut(), it.plan.workUnits());
    }

    private static Answer refused(String code, String message, String mc, long units, boolean truncated, boolean timedOut) {
        return new Answer(false, code, message, null, false, false, mc, null, truncated, timedOut, units);
    }
}
