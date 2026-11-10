package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.porter.stashmanager.planner.BlockItems;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Crafting from the stash, put together (CRAFT-CONTRACT.md): the settings, what the bot said, the recipes of the stash's
 * Minecraft version, and the planner. The routes, the order store and the build planner all ask this one class whether a
 * stash can take craft orders, and plan with it.
 */
final class CraftService {
    static final int MAX_STEPS = 64;
    static final int MAX_TAKE = 64;
    static final Set<String> SHORT_REASONS = Set.of("no_table", "table_missing", "table_blocked", "ingredients_short", "inventory_full",
        "recipe_mismatch", "grid_stuck", "timeout", "interrupted", "cap", "cancelled");

    /** Where a stash stands. reason is a placement code (no_recipes, no_bot, recipes_differ, no_table, table_bad) or null. */
    record Status(String mc, String recipesState, RecipeTable recipes, CraftStore.Settings settings, CraftBots.Beat beat, boolean online,
                  boolean synced, boolean recipesMatch, String tableStatus, boolean canOrder, String reason, String message) {
        boolean supports() {
            return beat != null && beat.cap() != null && beat.cap().v() >= 1;
        }

        boolean enabled() {
            return supports() && beat.cap().enabled();
        }

        int maxFills() {
            return supports() ? beat.cap().maxFills() : 0;
        }
    }

    /** Too much planning at once, or a user planning too often: 503 or 429 with Retry-After (the Router answers it). */
    static final class BusyException extends RuntimeException {
        final int status;
        final long retryAfter;

        BusyException(int status, long retryAfterSeconds, String message) {
            super(message, null, false, false);
            this.status = status;
            this.retryAfter = Math.max(1, retryAfterSeconds);
        }
    }

    /** A planning slot; close it when the plan is made. */
    interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    /** At most this many full plans are computed at once, server wide. */
    static final int PLAN_SLOTS = 3;
    private final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(PLAN_SLOTS);
    private final java.util.Map<String, java.util.ArrayDeque<Long>> windows = new java.util.HashMap<>();

    /** A planning slot, or BusyException (503) when all are taken. */
    Permit acquire() {
        if (!slots.tryAcquire()) throw new BusyException(503, 2, "the server is busy planning crafts; try again in a moment");
        return slots::release;
    }

    /** Counts a use of key; throws BusyException (429) above limit a minute. */
    synchronized void rateLimit(String key, int limit) {
        long now = System.currentTimeMillis();
        java.util.ArrayDeque<Long> q = windows.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() >= 60_000) q.pollFirst();
        if (q.size() >= limit) throw new BusyException(429, (60_000 - (now - q.peekFirst()) + 999) / 1000, "too many crafting plans, wait a moment");
        q.addLast(now);
        if (windows.size() > 1024) windows.values().removeIf(d -> d.isEmpty() || now - d.peekLast() >= 60_000);
    }

    /** The budget of one craft computation in nanoseconds (tests lower it). */
    volatile long planNanos = CraftPlanner.PLAN_NANOS;

    private volatile AreaStore areas; // the stored stash area is a known stash spot too

    void areas(AreaStore areas) {
        this.areas = areas;
    }

    private volatile KitStore kits; // anchors for the table position check (null in tests that have none)

    void kits(KitStore kits) {
        this.kits = kits;
    }

    /** Whether a heartbeat with an enabled capability came in the last ms milliseconds (the claim's downgrade rule). */
    /** How long a truncated plan is reused for the same request, so waiting requests don't each recompute it. */
    volatile long truncatedTtlMs = 15_000;
    boolean capableWithin(String server, String stash, long ms) {
        return bots.capableWithin(server, stash, ms);
    }

    /** The bot's reason a table is unusable (craftOrders.tableWhy) as a short phrase. */
    static String whyText(String why) {
        return switch (why) {
            case "no_area" -> "no stash area is recorded yet, so the bot can't check the table's distance";
            case "no_dimension" -> "the stash's dimension isn't recorded yet";
            case "wrong_dimension" -> "the table is in a different dimension than the stash";
            case "too_far" -> "the table is more than 64 blocks from the stash";
            case "not_table" -> "there is no crafting table at that spot";
            case "no_stand" -> "the bot can't find a spot to stand next to it";
            case "path_failed" -> "the bot can't find a path to it";
            case "not_loaded" -> "its chunk isn't loaded";
            case "no_material" -> "the bot found no crafting table nearby and the stash has no crafting table, planks or logs to make one";
            default -> why;
        };
    }

    private static String dim(String d) {
        return d == null ? null : d.contains(":") ? d : "minecraft:" + d;
    }

    /** A known spot of the stash: a point (min = max) or a box. */
    private record Anchor(int[] pos, int[] max, String label) {
        Anchor(int[] pos, String label) {
            this(pos, pos, label);
        }
    }

    /**
     * Why a crafting table position is refused, or null when it is fine: y in -64..320; the dimension is the stash index's
     * (or overworld, the_nether, the_end when it has none); and within 64 blocks of something the stash is known by (a kits
     * chest or rule container, the delivery container, a table the bot saw). With no kit or delivery position to go by,
     * only a position the bot saw is accepted. The reasons name the anchor, the distance and the dimensions.
     */
    String checkTable(String server, String stash, CraftStore.Table t) {
        if (t.y() < -64 || t.y() > 320) return "table.y is " + t.y() + ", but a crafting table must be at a height from -64 to 320";
        String dim = dim(index.dimension(server, stash));
        if (dim != null ? !dim.equals(dim(t.dimension()))
            : !java.util.Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end").contains(t.dimension())) {
            return dim != null ? "table.dimension is " + t.dimension() + ", but this stash is in " + dim
                : "table.dimension is " + t.dimension() + ", which is not minecraft:overworld, minecraft:the_nether or minecraft:the_end";
        }
        List<Anchor> anchors = new ArrayList<>();
        KitStore ks = kits;
        if (ks != null) {
            try {
                KitStore.Settings s = ks.get(server, stash);
                if (s.kitsChest() != null) anchors.add(new Anchor(position(s.kitsChest()), "the kits chest"));
                for (String c : s.rules().values()) anchors.add(new Anchor(position(c), "a kit container"));
            } catch (KitStore.DisabledException ignored) {
                // no kit anchors
            }
        }
        IndexStore.Delivery d = index.delivery(server, stash);
        if (d != null && d.dimension() != null && dim(d.dimension()).equals(dim(t.dimension()))) {
            anchors.add(new Anchor(new int[]{d.x(), d.y(), d.z()}, "the delivery container"));
        }
        AreaStore as = areas;
        if (as != null) {
            try {
                AreaStore.Settings a = as.find(server, stash);
                if (a != null && a.area() != null && dim(a.area().dimension()).equals(dim(t.dimension()))) {
                    AreaStore.Area b = a.area();
                    anchors.add(new Anchor(new int[]{b.minX(), b.minY(), b.minZ()}, new int[]{b.maxX(), b.maxY(), b.maxZ()}, "the stash area"));
                }
            } catch (AreaStore.DisabledException ignored) {
                // no area anchor
            }
        }
        List<Anchor> seen = new ArrayList<>();
        CraftBots.Beat beat = bots.get(server, stash);
        if (beat != null && beat.cap() != null) for (String s : beat.cap().seen()) seen.add(new Anchor(position(s), "a table the bot saw"));
        if (anchors.isEmpty()) {
            for (Anchor p : seen) if (p.pos()[0] == t.x() && p.pos()[1] == t.y() && p.pos()[2] == t.z()) return null;
            return "no stash area is known yet (no kits chest or delivery container is set), so the table's distance can't be checked: "
                + "only a crafting table the bot has seen is accepted"
                + (seen.isEmpty() ? " (it has not reported one)" : " (it saw " + seen.size() + ", none at " + t.x() + "," + t.y() + "," + t.z() + ")");
        }
        anchors.addAll(seen);
        Anchor nearest = null;
        double best = Double.MAX_VALUE;
        for (Anchor a : anchors) {
            // The distance to the box (0 inside it); for a single spot that is the distance to the spot.
            long dx = Math.max(0, Math.max(a.pos()[0] - t.x(), t.x() - a.max()[0]));
            long dy = Math.max(0, Math.max(a.pos()[1] - t.y(), t.y() - a.max()[1]));
            long dz = Math.max(0, Math.max(a.pos()[2] - t.z(), t.z() - a.max()[2]));
            double dist = Math.sqrt((double) (dx * dx + dy * dy + dz * dz));
            if (dist < best) {
                best = dist;
                nearest = a;
            }
        }
        if (best <= 64) return null;
        return "table is " + Math.round(best) + " blocks from the nearest of the stash's known spots (" + nearest.label() + " at "
            + nearest.pos()[0] + "," + nearest.pos()[1] + "," + nearest.pos()[2] + "); it must be within 64 blocks";
    }

    private static int[] position(String id) {
        String[] p = id.split(",");
        return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
    }

    private final CraftStore store;
    private final CraftBots bots;
    private final RecipeSource recipes;
    private final IndexStore index;
    private final LinkedHashMap<String, CraftPlanner.Eligible> eligibleCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CraftPlanner.Eligible> eldest) {
            return size() > 16;
        }
    };

    CraftService(CraftStore store, CraftBots bots, RecipeSource recipes, IndexStore index) {
        this.store = store;
        this.bots = bots;
        this.recipes = recipes;
        this.index = index;
    }

    CraftStore store() {
        return store;
    }

    CraftBots bots() {
        return bots;
    }

    RecipeSource recipes() {
        return recipes;
    }

    // ---------- status ----------

    Status status(String server, String stash) {
        String mc = index.mcVersion(server, stash);
        RecipeTable table = null;
        String state = "none";
        if (mc != null) {
            recipes.request(mc);
            table = recipes.table(mc);
            state = recipes.state(mc);
        }
        CraftStore.Settings s = null;
        try {
            s = store.get(server, stash);
        } catch (CraftStore.DisabledException ignored) {
            // answered below as a stash that can't take craft orders
        }
        CraftBots.Beat beat = bots.get(server, stash);
        CraftBots.Capability cap = beat == null ? null : beat.cap();
        boolean supports = cap != null && cap.v() >= 1;
        boolean synced = s != null && beat != null && beat.hasHash() && s.hash().equals(beat.hash());
        boolean match = table != null && cap != null && table.tableHash().equals(cap.recipes());
        String tableStatus;
        boolean wouldAuto = s != null && s.table() == null && supports && cap.enabled()
            && (cap.table().equals("auto") || (cap.table().equals("ok") && cap.botTable() != null));
        boolean auto = wouldAuto && synced; // an unsynced bot's word may be about the settings before the last change
        if (s == null) tableStatus = "unknown";
        else if (s.table() == null) tableStatus = auto ? "auto" : "unset";
        else if (!supports || !synced) tableStatus = "unknown";
        else if (cap.table().equals("auto")) tableStatus = "unknown"; // a website table is set, but the bot says it has none to use
        else tableStatus = cap.table();
        String reason;
        String message = null;
        if (table == null) {
            reason = "no_recipes";
            message = "Crafting info isn't available for " + (mc == null ? "this stash" : mc) + ".";
        } else if (!supports || !cap.enabled()) {
            reason = "no_bot";
            message = supports ? "The bot has crafting turned off (turn on its crafting and craft-orders settings)."
                : "This stash's bot can't craft orders (needs StashSorter 1.15.0+).";
        } else if (!match) {
            reason = "recipes_differ";
            message = "The bot's recipe table differs from the server's.";
        } else if (s == null) {
            reason = "no_table";
            message = "The crafting settings can't be read.";
        } else if (s.table() == null && auto) {
            reason = null; // no website table: the bot finds, places or crafts one
            message = "The bot will find, place or craft a crafting table.";
        } else if (s.table() == null) {
            reason = "no_table";
            message = wouldAuto ? "Waiting for the bot to pick up the latest settings."
                : supports && cap.tableWhy() != null ? "No crafting table is available: " + whyText(cap.tableWhy()) + "."
                : "Set a crafting table for this stash to order crafted items (or use a bot that can find, place or craft one).";
        } else if (!tableStatus.equals("ok") && !tableStatus.equals("unloaded")) {
            reason = "table_bad";
            message = "The crafting table isn't usable: " + tableStatus + (supports && cap.tableWhy() != null ? ", " + whyText(cap.tableWhy()) : "") + ".";
        } else if (tableStatus.equals("unloaded") && !s.hash().equals(beat.okHash())) {
            reason = "table_bad";
            message = "The bot has not yet seen the crafting table (its chunk is not loaded): it must report it as ok once.";
        } else {
            reason = null;
        }
        boolean online = beat != null && index.online(server, stash);
        return new Status(mc, state, table, s, beat, online, synced, match, tableStatus, reason == null, reason, message);
    }

    // ---------- eligibility and pool ----------

    CraftPlanner.Eligible eligible(RecipeTable table, Set<String> neverUse) {
        String key = table.version + "|" + table.tableHash() + "|" + new TreeSet<>(neverUse);
        synchronized (eligibleCache) {
            CraftPlanner.Eligible e = eligibleCache.get(key);
            if (e == null) {
                e = new CraftPlanner.Eligible(table, neverUse);
                eligibleCache.put(key, e);
            }
            return e;
        }
    }

    /** How many planners were made (tests check that a cached plan is not planned again). */
    final java.util.concurrent.atomic.AtomicInteger planners = new java.util.concurrent.atomic.AtomicInteger();

    CraftPlanner planner(CraftPlanner.Eligible e) {
        planners.incrementAndGet();
        return new CraftPlanner(e, this::name);
    }

    /** The English name from any index, else one made from the id. */
    String name(String id) {
        IndexStore.IconInfo info = index.iconInfo(id);
        return info != null && info.englishName() != null && !info.englishName().isEmpty() ? info.englishName() : Redact.prettyId(id);
    }

    /**
     * The pool before the direct takes, by plain item id: the index count minus what open orders reserve (item lines and
     * craft.take). Component-patched keys (id#hash) never count.
     */
    static Map<String, Long> stock(IndexStore.OrderCounts counts, Map<String, Long> reserved) {
        Map<String, Long> out = new HashMap<>();
        if (counts == null) return out;
        for (String key : counts.items().keySet()) {
            String id = counts.itemIds().get(key);
            if (id == null || !id.equals(key)) continue;
            long avail = Math.max(0, counts.total("item", key) - reserved.getOrDefault("item:" + key, 0L));
            if (avail > 0) out.put(key, avail);
        }
        return out;
    }

    /** The stack size of an item for the slot check: the index, else the block-items table, else 64. */
    static int maxStack(IndexStore.OrderCounts counts, String key) {
        if (counts != null && counts.maxStacks().containsKey(key)) return Math.max(1, counts.maxStack(key));
        try {
            return Math.max(1, BlockItems.latest().maxStack(key));
        } catch (RuntimeException e) {
            return 64;
        }
    }

    // ---------- orders ----------

    /**
     * Plans the craft lines of an order being placed. merged: the order's merged lines (item, shulker and craft), in order;
     * firstIndex: each line's first position in the request. Returns the order's craft JSON, or null when there are no
     * craft lines. Throws OrderException: 409 with a craft code when the stash can't take craft orders, 400 for a line that
     * is not fully craftable or a plan the bot would refuse.
     */
    JsonObject planOrder(String server, String stash, IndexStore.OrderCounts counts, Map<String, Long> reserved,
                         Collection<OrderStore.Line> merged, Map<String, Integer> firstIndex) throws OrderStore.OrderException {
        List<OrderStore.Line> craftLines = new ArrayList<>();
        for (OrderStore.Line l : merged) if (l.type().equals("craft")) craftLines.add(l);
        if (craftLines.isEmpty()) return null;
        Status st = status(server, stash);
        if (!st.canOrder()) {
            throw new OrderStore.OrderException(409, "this stash can't take craft orders: " + st.message(), firstIndex.get(craftLines.get(0).ref()), st.reason());
        }
        try (Permit permit = acquire()) {
            return planOrder(st, craftLines, counts, reserved, merged, firstIndex);
        }
    }

    private JsonObject planOrder(Status st, List<OrderStore.Line> craftLines, IndexStore.OrderCounts counts, Map<String, Long> reserved,
                                 Collection<OrderStore.Line> merged, Map<String, Integer> firstIndex) throws OrderStore.OrderException {
        CraftPlanner.Eligible e = eligible(st.recipes(), st.settings().neverUse());
        Map<String, Long> direct = new HashMap<>();
        for (OrderStore.Line l : merged) {
            if (!l.type().equals("item")) continue;
            String id = counts.itemIds().get(l.key());
            if (id != null && id.equals(l.key())) direct.merge(l.key(), l.count(), Long::sum);
        }
        List<CraftPlanner.Row> rows = new ArrayList<>();
        for (OrderStore.Line l : craftLines) {
            int at = firstIndex.get(l.ref());
            if (!CraftStore.ITEM_ID.matcher(l.key()).matches()) throw new OrderStore.OrderException(400, "not an item id: " + l.key(), at);
            if (e.making(l.key()).isEmpty()) {
                throw new OrderStore.OrderException(400, "can't be crafted here: " + name(l.key()), at);
            }
            rows.add(new CraftPlanner.Row(l.key(), l.count(), false));
        }
        CraftPlanner.Plan plan = planner(e).plan(stock(counts, reserved), direct, rows);
        for (int i = 0; i < craftLines.size(); i++) {
            CraftPlanner.RowResult r = plan.rows().get(i);
            if (r.craftable() < craftLines.get(i).count()) {
                throw new OrderStore.OrderException(400, "only " + r.craftable() + " craftable for " + name(r.id()) + ": "
                    + (r.reason() == null ? "not enough materials" : r.reason()), firstIndex.get(craftLines.get(i).ref()));
            }
        }
        if (plan.fills() > st.maxFills()) {
            throw new OrderStore.OrderException(400, "this bot crafts at most " + st.maxFills() + " grid fills per order", -1);
        }
        if (plan.steps().size() > MAX_STEPS || plan.take().size() > MAX_TAKE) {
            throw new OrderStore.OrderException(400, "this craft is too big for one order (at most " + MAX_STEPS + " steps and " + MAX_TAKE + " ingredients); order fewer items", -1);
        }
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("mc", st.mc());
        o.addProperty("recipes", st.recipes().tableHash());
        JsonArray lines = new JsonArray();
        for (OrderStore.Line l : craftLines) {
            JsonObject lo = new JsonObject();
            lo.addProperty("key", l.key());
            lo.addProperty("count", l.count());
            lines.add(lo);
        }
        o.add("lines", lines);
        o.add("steps", stepsJson(plan.steps(), true));
        o.add("take", countsJson(plan.take()));
        o.add("leftovers", countsJson(plan.leftovers()));
        o.addProperty("fills", plan.fills());
        return o;
    }

    /** The order's craft take, by plain item id, for the reservations. */
    static Map<String, Long> take(JsonObject craft) {
        Map<String, Long> out = new HashMap<>();
        if (craft == null || !craft.has("take") || !craft.get("take").isJsonArray()) return out;
        for (JsonElement e : craft.getAsJsonArray("take")) {
            if (!e.isJsonObject()) continue;
            JsonObject t = e.getAsJsonObject();
            if (t.has("id") && t.has("count")) out.merge(t.get("id").getAsString(), t.get("count").getAsLong(), Long::sum);
        }
        return out;
    }

    // ---------- JSON ----------

    static JsonArray countsJson(List<CraftPlanner.Count> list) {
        JsonArray a = new JsonArray();
        for (CraftPlanner.Count c : list) {
            JsonObject o = new JsonObject();
            o.addProperty("id", c.id());
            o.addProperty("count", c.count());
            a.add(o);
        }
        return a;
    }

    /** Steps for an order ({recipe, result, count, fills, inputs}) or for a row ({recipe, result, made, fills}). */
    static JsonArray stepsJson(List<CraftPlanner.Step> steps, boolean forOrder) {
        JsonArray a = new JsonArray();
        for (CraftPlanner.Step s : steps) {
            JsonObject o = new JsonObject();
            o.addProperty("recipe", s.recipe());
            o.addProperty("result", s.result());
            if (forOrder) {
                o.addProperty("count", s.count());
                o.addProperty("fills", s.fills());
            } else {
                o.addProperty("made", s.fills() * s.count());
                o.addProperty("fills", s.fills());
            }
            JsonArray in = new JsonArray();
            s.inputs().forEach((id, n) -> {
                JsonObject io = new JsonObject();
                io.addProperty("id", id);
                io.addProperty("count", n);
                in.add(io);
            });
            o.add("inputs", in);
            a.add(o);
        }
        return a;
    }

    /** The plan-level {@code craft} object of the build planner and the quote (CRAFT-CONTRACT.md, "Planner additions"). */
    JsonObject statusJson(Status st, boolean truncated) {
        JsonObject o = new JsonObject();
        o.addProperty("available", st.recipes() != null && st.settings() != null);
        o.addProperty("canOrder", st.canOrder());
        o.addProperty("reason", st.reason());
        o.addProperty("message", st.message());
        JsonObject r = new JsonObject();
        r.addProperty("mc", st.mc());
        r.addProperty("state", st.recipesState());
        o.add("recipes", r);
        o.addProperty("tableStatus", st.tableStatus());
        o.addProperty("tableWhy", st.supports() ? st.beat().cap().tableWhy() : null);
        JsonObject bot = new JsonObject();
        bot.addProperty("supports", st.supports());
        bot.addProperty("recipesMatch", st.recipesMatch());
        o.add("bot", bot);
        o.addProperty("truncated", truncated);
        return o;
    }

    /** The {@code craft} entry of GET /api/v1/stashes. */
    JsonElement summaryJson(String server, String stash) {
        if (store.disabled()) return JsonNull.INSTANCE;
        Status st = status(server, stash);
        if (st.settings() == null) return JsonNull.INSTANCE;
        JsonObject o = new JsonObject();
        o.addProperty("supports", st.supports());
        o.addProperty("enabled", st.enabled());
        o.addProperty("synced", st.synced());
        o.addProperty("recipesMatch", st.recipesMatch());
        o.addProperty("tableStatus", st.tableStatus());
        o.addProperty("tableWhy", st.supports() ? st.beat().cap().tableWhy() : null);
        o.addProperty("canOrder", st.canOrder());
        o.addProperty("reason", st.reason());
        o.addProperty("maxFills", st.supports() ? st.maxFills() : null);
        return o;
    }
}
