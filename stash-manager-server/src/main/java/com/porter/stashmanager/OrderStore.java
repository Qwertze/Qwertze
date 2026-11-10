package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orders per (server, stash), persisted to data/orders/<same name as the index file>.json.
 * Each stash's orders are guarded by their own lock; every change is written to disk before the call returns.
 * The status rules are the ones in PROTOCOL.md ("Orders").
 */
final class OrderStore {
    static final int MAX_LINES = 200;
    static final int MAX_OPEN = 20;
    static final int KEEP = 100;
    /** The anvil's limit for a custom name. */
    static final int MAX_NAME = 50;
    static final int MAX_PHASE = 100;
    static final int MAX_MESSAGE = 300;
    /** The most shulkers one order may need: one double chest (the addon's Order.MAX_BINS). */
    static final int MAX_SHULKERS = 54;
    static final int SHULKER_SLOTS = 27;
    static final Set<String> OPEN = Set.of("pending", "accepted", "in_progress");
    static final Set<String> FINISHED = Set.of("delivered", "failed", "cancelled");
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * type is item, shulker, craft (CRAFT-CONTRACT.md: a craft line makes the item from stash materials) or kit
     * (KIT-CONTRACT-v2: a finished kit of a template; key is the template id). repair: the
     * line's "Repair" toggle (PROTOCOL.md, "Additions: repair with XP bottles"). */
    record Line(String type, String key, long count, boolean repair) {
        Line(String type, String key, long count) {
            this(type, key, count, false);
        }

        String ref() {
            return type + ":" + key;
        }
    }

    /**
     * A rejected request: HTTP status, message, and the index of the offending line (or -1). craft is the code of a 409
     * from the craft checks (no_recipes, no_bot, recipes_differ, no_table, table_bad), else null.
     */
    static final class OrderException extends Exception {
        final int status;
        final int line;
        final String craft;
        /** The code of a 409 from the kit checks (no_bot, disabled) or "deleted" (the kit's template is gone), else null. */
        final String kit;
        /** The items a kit order is short of ([{key, count}]), or null. */
        JsonArray shortItems;
        /** The name of the first kit of a kit order's error text, so a forced streamer's copy can leave it out. */
        String kitName;
        /** That kit's number in the stash's template list (what a forced streamer sees instead of its name), 0 if unknown. */
        int kitNumber;

        OrderException(int status, String message, int line) {
            this(status, message, line, null, null);
        }

        OrderException(int status, String message, int line, String craft) {
            this(status, message, line, craft, null);
        }

        OrderException(int status, String message, int line, String craft, String kit) {
            super(message);
            this.status = status;
            this.line = line;
            this.craft = craft;
            this.kit = kit;
        }
    }

    static final class Order {
        String id;
        String server;
        String stash;
        List<Line> lines;
        JsonObject names; // normalized (see normalizeNames), or null for no naming
        boolean repairAll; // the cart-wide "Repair all items" switch
        JsonObject repairEstimate; // computed at placement; null when no line is wanted for repair
        JsonObject craft; // the craft plan, computed at placement and not changed later; null when there are no craft lines
        JsonObject kit; // the kit snapshot ({v, lines, take}), made at placement; null when there are no kit lines
        long createdAt;
        long updatedAt;
        String status;
        String phase;
        JsonObject progress;
        String message;
        boolean cancelRequested;
        /** First claim without the craftOrders flag while this order was held (0 = none), and how many came since. Memory only. */
        long flaglessSince;
        /** When the last flagless claim came (0 = none): a long gap starts a new episode with a fresh grace. */
        long lastFlagless;
        /** How many earlier episodes of flagless claims this order has had (a bot claiming once in a while without the flag). Memory only. */
        int flaglessEpisodes;
        int flaglessClaims;
        long cancelRequestedAt; // 0 when unknown (orders from before this field)
        JsonObject result;
        String placedById; // Users.OWNER_ID or a sub-user's id; null for orders from before accounts (the owner's)
        String placedByName; // the name at the time
        String retriedAs; // the order this one was placed again as (Retry, or the planner's "order part again"), or null

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("server", server);
            o.addProperty("stash", stash);
            JsonArray ls = new JsonArray();
            for (Line l : lines) {
                JsonObject lo = new JsonObject();
                lo.addProperty("type", l.type);
                lo.addProperty("key", l.key);
                lo.addProperty("count", l.count);
                if (l.repair) lo.addProperty("repair", true);
                ls.add(lo);
            }
            o.add("lines", ls);
            o.add("names", names == null ? null : names.deepCopy());
            o.addProperty("repairAll", repairAll);
            o.add("repairEstimate", repairEstimate == null ? com.google.gson.JsonNull.INSTANCE : repairEstimate.deepCopy());
            o.add("craft", craft == null ? com.google.gson.JsonNull.INSTANCE : craft.deepCopy());
            if (kit != null) o.add("kit", kit.deepCopy());
            o.addProperty("createdAt", createdAt);
            o.addProperty("updatedAt", updatedAt);
            o.addProperty("status", status);
            o.addProperty("phase", phase);
            o.add("progress", progress == null ? null : progress.deepCopy());
            o.addProperty("message", message);
            o.addProperty("cancelRequested", cancelRequested);
            o.addProperty("cancelRequestedAt", cancelRequestedAt > 0 ? cancelRequestedAt : null);
            o.add("result", result == null ? null : result.deepCopy());
            if (retriedAs != null && !PENDING.equals(retriedAs)) o.addProperty("retriedAs", retriedAs);
            if (placedById == null) {
                o.add("placedBy", null);
            } else {
                JsonObject by = new JsonObject();
                by.addProperty("id", placedById);
                by.addProperty("name", placedByName);
                o.add("placedBy", by);
            }
            return o;
        }

        static Order fromJson(JsonObject o) {
            Order r = new Order();
            r.id = o.get("id").getAsString();
            r.server = o.get("server").getAsString();
            r.stash = o.get("stash").getAsString();
            r.lines = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("lines")) {
                JsonObject l = e.getAsJsonObject();
                r.lines.add(new Line(l.get("type").getAsString(), l.get("key").getAsString(), l.get("count").getAsLong(),
                    l.has("repair") && l.get("repair").isJsonPrimitive() && l.get("repair").getAsBoolean()));
            }
            r.repairAll = o.has("repairAll") && o.get("repairAll").isJsonPrimitive() && o.get("repairAll").getAsBoolean();
            r.repairEstimate = o.has("repairEstimate") && o.get("repairEstimate").isJsonObject() ? o.getAsJsonObject("repairEstimate") : null;
            r.craft = o.has("craft") && o.get("craft").isJsonObject() ? o.getAsJsonObject("craft") : null;
            r.kit = o.has("kit") && o.get("kit").isJsonObject() ? o.getAsJsonObject("kit") : null;
            // "note" from orders stored before notes were removed is dropped here and never written again.
            try {
                r.names = normalizeNames(o.get("names"));
            } catch (OrderException e) {
                r.names = null;
            }
            r.createdAt = o.get("createdAt").getAsLong();
            r.updatedAt = o.get("updatedAt").getAsLong();
            r.status = o.get("status").getAsString();
            r.phase = str(o, "phase");
            r.progress = o.has("progress") && o.get("progress").isJsonObject() ? o.getAsJsonObject("progress") : null;
            r.message = str(o, "message");
            r.cancelRequested = o.has("cancelRequested") && o.get("cancelRequested").getAsBoolean();
            r.cancelRequestedAt = o.has("cancelRequestedAt") && o.get("cancelRequestedAt").isJsonPrimitive()
                ? o.get("cancelRequestedAt").getAsLong() : 0;
            r.result = o.has("result") && o.get("result").isJsonObject() ? o.getAsJsonObject("result") : null;
            r.retriedAs = str(o, "retriedAs");
            if (o.has("placedBy") && o.get("placedBy").isJsonObject()) {
                JsonObject by = o.getAsJsonObject("placedBy");
                r.placedById = str(by, "id");
                r.placedByName = str(by, "name");
            }
            return r;
        }

        private static String str(JsonObject o, String f) {
            JsonElement e = o.get(f);
            return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
        }
    }

    /** One stash's orders, oldest first. */
    private static final class Book {
        final String server;
        final String stash;
        final List<Order> orders = new ArrayList<>();
        /** Counts every save, so a craft plan made outside the lock can tell whether the orders changed meanwhile. */
        long version;

        Book(String server, String stash) {
            this.server = server;
            this.stash = stash;
        }
    }

    private final Path dir;
    private final IndexStore index;
    private volatile CraftService craftService; // null: this server plans no craft lines
    private volatile KitOrders kitOrders; // null: this server takes no kit lines
    private final Map<IndexStore.StashKey, Book> books = new ConcurrentHashMap<>();
    private final Map<String, Book> byOrderId = new ConcurrentHashMap<>();

    OrderStore(Path dataDir, IndexStore index) {
        this.dir = dataDir.resolve("orders");
        this.index = index;
    }

    private volatile java.util.function.LongSupplier clock = System::currentTimeMillis;
    /** When this process started (the claim grace after a restart counts from here). */
    private volatile long startedAt = System.currentTimeMillis();
    /** Test hook: runs after a craft plan is made and before the order book is locked again. */
    volatile Runnable afterPlan;

    /** The clock of the claim rules (tests move it); the start time is taken from it now. */
    void clock(java.util.function.LongSupplier clock) {
        this.clock = clock;
        this.startedAt = clock.getAsLong();
    }

    /** A held craft order claimed without the flag is failed only after this long, and not before the third such claim. */
    static final long DOWNGRADE_GRACE_MS = 120_000;
    /** Flagless claims that go on this long since the first fail the order whatever the heartbeats say: the claim flag is what counts. */
    static final long DOWNGRADE_ABSOLUTE_MS = 10 * 60_000;

    /** Gives the store the craft planner (set once at start). Without it a craft line is refused. */
    void craft(CraftService service) {
        this.craftService = service;
    }

    /** Gives the store the kit planner (set once at start). Without it a kit line is refused. */
    void kits(KitOrders kits) {
        this.kitOrders = kits;
    }

    void load() throws IOException {
        Files.createDirectories(dir);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                try {
                    JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                    Book b = book(root.get("server").getAsString(), root.get("stash").getAsString());
                    synchronized (b) {
                        for (JsonElement e : root.getAsJsonArray("orders")) {
                            Order o = Order.fromJson(e.getAsJsonObject());
                            b.orders.add(o);
                            byOrderId.put(o.id, b);
                        }
                        b.orders.sort((x, y) -> Long.compare(x.createdAt, y.createdAt));
                    }
                } catch (RuntimeException e) {
                    System.err.println("Skipping orders file " + file.getFileName() + ": " + e);
                }
            }
        }
    }

    private Book book(String server, String stash) {
        return books.computeIfAbsent(new IndexStore.StashKey(server, stash), k -> new Book(server, stash));
    }

    private void save(Book b) throws IOException {
        b.version++;
        JsonObject root = new JsonObject();
        root.addProperty("server", b.server);
        root.addProperty("stash", b.stash);
        JsonArray list = new JsonArray();
        for (Order o : b.orders) list.add(o.toJson());
        root.add("orders", list);
        AtomicFiles.write(dir.resolve(IndexStore.fileName(b.server, b.stash)), Json.GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    /** Drops the oldest finished orders beyond KEEP. Open orders are never pruned. */
    private void prune(Book b) {
        int excess = b.orders.size() - KEEP;
        for (int i = 0; i < b.orders.size() && excess > 0; ) {
            Order o = b.orders.get(i);
            if (FINISHED.contains(o.status)) {
                b.orders.remove(i);
                byOrderId.remove(o.id, b);
                excess--;
            } else {
                i++;
            }
        }
    }

    private static Map<String, Long> reserved(Book b) {
        Map<String, Long> out = new HashMap<>();
        for (Order o : b.orders) {
            if (!OPEN.contains(o.status)) continue;
            for (Line l : o.lines) out.merge(l.ref(), l.count, Long::sum);
            // The ingredients of a craft order are taken from the stash like item lines (CRAFT-CONTRACT.md, "The pool").
            CraftService.take(o.craft).forEach((id, n) -> out.merge("item:" + id, n, Long::sum));
            KitOrders.reservations(o, out);
        }
        return out;
    }

    /**
     * What the stash's open orders reserve, by line ref ("item:&lt;key&gt;" / "shulker:&lt;key&gt;"): the same numbers
     * {@link #place} subtracts from the index. A copy; empty when the stash has no orders.
     */
    Map<String, Long> reservedFor(String server, String stash) {
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b == null) return Map.of();
        synchronized (b) {
            return Map.copyOf(reserved(b));
        }
    }

    // ---------- browser ----------

    /** Validates (against the current index and open orders), merges duplicate lines, stores and returns the order. */
    Order place(String server, String stash, List<Line> requested, JsonObject names) throws OrderException, IOException {
        return place(server, stash, requested, names, null, null);
    }

    /** The same, recording who placed it (byId null: unknown, counted as the owner's). */
    Order place(String server, String stash, List<Line> requested, JsonObject names, String byId, String byName)
        throws OrderException, IOException {
        return place(server, stash, requested, names, false, byId, byName);
    }

    /** The same with the cart-wide repair switch (repairAll) and the lines' own repair flags. */
    Order place(String server, String stash, List<Line> requested, JsonObject names, boolean repairAll, String byId, String byName)
        throws OrderException, IOException {
        if (requested.isEmpty()) throw new OrderException(400, "an order needs at least one line", -1);
        if (requested.size() > MAX_LINES) throw new OrderException(400, "at most " + MAX_LINES + " lines", -1);
        IndexStore.OrderCounts counts = index.orderCounts(server, stash);
        if (counts == null) throw new OrderException(400, "no index for that stash", -1);

        // Merge lines with the same type and key, remembering where each first appeared (for error reporting).
        Map<String, Line> merged = new LinkedHashMap<>();
        Map<String, Integer> firstIndex = new HashMap<>();
        for (int i = 0; i < requested.size(); i++) {
            Line l = requested.get(i);
            Line prev = merged.get(l.ref());
            if (prev == null) {
                merged.put(l.ref(), l);
                firstIndex.put(l.ref(), i);
            } else {
                merged.put(l.ref(), new Line(l.type, l.key, prev.count + l.count, prev.repair || l.repair));
            }
        }

        Book b = book(server, stash);
        Line firstCraft = merged.values().stream().filter(l -> l.type.equals("craft")).findFirst().orElse(null);
        Line firstKit = merged.values().stream().filter(l -> l.type.equals("kit")).findFirst().orElse(null);
        JsonObject craftPlan = null;
        KitOrders.Plan kitPlan = null;
        long planVersion = -1;
        // A craft plan can take seconds: it is made outside the lock, against the orders as they were (the book's version),
        // and made again if they changed before the order is stored. The lock is never held for a full plan.
        for (int attempt = 0; ; attempt++) {
            boolean planned = firstCraft != null || firstKit != null;
            if (planned) {
                CraftService cs = craftService;
                if (firstCraft != null && cs == null) throw new OrderException(400, "this server can't craft orders", firstIndex.get(firstCraft.ref()));
                KitOrders ko = kitOrders;
                if (firstKit != null && ko == null) throw new OrderException(400, "this server can't build kits", firstIndex.get(firstKit.ref()));
                Map<String, Long> snapshot;
                synchronized (b) {
                    snapshot = reserved(b);
                    planVersion = b.version;
                }
                kitPlan = firstKit == null ? null : ko.plan(server, stash, counts, snapshot, merged.values(), firstIndex, cs != null, Redact::prettyId);
                // The kits' plain direct takes and shortfalls join the order's own lines for the craft planner.
                Collection<Line> forCraft = merged.values();
                Map<String, Integer> craftIndex = firstIndex;
                boolean anyCraft = firstCraft != null || (kitPlan != null && !kitPlan.craftLines().isEmpty());
                if (kitPlan != null && anyCraft) {
                    Map<String, Line> all = new LinkedHashMap<>(merged);
                    for (Line d : kitPlan.directLines()) {
                        Line prev = all.get(d.ref());
                        all.put(d.ref(), prev == null ? d : new Line(d.type, d.key, prev.count + d.count, prev.repair));
                    }
                    for (Line c : kitPlan.craftLines()) {
                        Line prev = all.get(c.ref());
                        all.put(c.ref(), prev == null ? c : new Line(c.type, c.key, prev.count + c.count, prev.repair));
                    }
                    forCraft = all.values();
                    craftIndex = new HashMap<>(firstIndex);
                    kitPlan.craftAt().forEach(craftIndex::putIfAbsent);
                }
                if (anyCraft) {
                    try {
                        craftPlan = cs.planOrder(server, stash, counts, snapshot, forCraft, craftIndex);
                    } catch (OrderException e) {
                        if (kitPlan != null && !kitPlan.craftLines().isEmpty() && kitPlan.craftFailed() != null) throw kitPlan.craftFailed();
                        throw e;
                    }
                }
                Runnable hook = afterPlan;
                if (hook != null) hook.run();
            }
            synchronized (b) {
                if ((firstCraft != null || firstKit != null) && b.version != planVersion) {
                    if (attempt >= 2) throw new OrderException(409, "other orders changed while this one was being planned; try again", -1);
                    continue;
                }
                return storeLocked(b, server, stash, merged, firstIndex, names, repairAll, byId, byName, counts, craftPlan,
                    kitPlan == null ? null : kitPlan.kit());
            }
        }
    }

    private Order storeLocked(Book b, String server, String stash, Map<String, Line> merged, Map<String, Integer> firstIndex,
                              JsonObject names, boolean repairAll, String byId, String byName, IndexStore.OrderCounts counts,
                              JsonObject craftPlan, JsonObject kitPlan) throws OrderException, IOException {
        {
            long open = b.orders.stream().filter(o -> OPEN.contains(o.status)).count();
            if (open >= MAX_OPEN) throw new OrderException(400, "too many open orders for this stash (limit " + MAX_OPEN + ")", -1);
            Map<String, Long> reserved = reserved(b);
            for (Line l : merged.values()) {
                int at = firstIndex.get(l.ref());
                if (l.type.equals("craft")) {
                    if (l.count > Integer.MAX_VALUE) throw new OrderException(400, "count too large", at);
                    continue; // planned below, with the item lines as the direct takes
                }
                if (l.type.equals("kit")) continue; // planned with the kit's template, before the lock
                long total = counts.total(l.type, l.key);
                if (total < 0) throw new OrderException(400, "not in the stash: " + l.key, at);
                long available = Math.max(0, total - reserved.getOrDefault(l.ref(), 0L));
                if (l.count > Integer.MAX_VALUE) throw new OrderException(400, "count too large", at);
                if (l.count > available) {
                    throw new OrderException(400, "only " + available + " available for " + l.key + " (asked for " + l.count + ")", at);
                }
            }
            long shulkers = shulkersNeeded(merged.values(), counts);
            if (shulkers > MAX_SHULKERS) {
                throw new OrderException(400, "too big: this order needs " + shulkers + " shulkers, and one order can fill at most "
                    + MAX_SHULKERS + " (one double chest)", -1);
            }
            long now = System.currentTimeMillis();
            Order o = new Order();
            o.id = newId();
            o.server = server;
            o.stash = stash;
            o.lines = new ArrayList<>(merged.values());
            o.names = names == null ? null : names.deepCopy();
            o.repairAll = repairAll;
            o.repairEstimate = repairEstimate(o.lines, repairAll, counts, bottlesAvailable(counts, reserved));
            o.craft = craftPlan;
            o.kit = kitPlan;
            o.createdAt = now;
            o.updatedAt = now;
            o.status = "pending";
            o.placedById = byId;
            o.placedByName = byId == null ? null : byName;
            b.orders.add(o);
            byOrderId.put(o.id, b);
            prune(b);
            save(b);
            return o;
        }
    }

    /** Bottles o' Enchanting in the index minus what open orders reserve (the cart estimate's "in stash"). */
    static long bottlesAvailable(IndexStore.OrderCounts counts, Map<String, Long> reserved) {
        if (counts == null) return 0;
        long total = counts.total("item", Repair.BOTTLE_KEY);
        if (total < 0) return 0;
        return Math.max(0, total - reserved.getOrDefault("item:" + Repair.BOTTLE_KEY, 0L));
    }

    /**
     * The repair estimate stored with an order (PROTOCOL.md): null when no line is wanted for repair, else
     * {needed, available, short, lines: [{key, count, repairable, reason, bottles}]} with one entry per wanted line.
     */
    static JsonObject repairEstimate(List<Line> lines, boolean repairAll, IndexStore.OrderCounts counts, long available) {
        JsonArray out = new JsonArray();
        long needed = 0;
        for (Line l : lines) {
            if (!l.type.equals("item")) continue;
            Repair.Info info = counts.repair(l.key);
            if (!(l.repair || (repairAll && info.damaged()))) continue;
            long bottles = info.repairable() ? Math.min(l.count * info.bottles(), Integer.MAX_VALUE) : 0;
            needed += bottles;
            JsonObject lo = new JsonObject();
            lo.addProperty("key", l.key);
            lo.addProperty("count", l.count);
            lo.addProperty("repairable", info.repairable());
            lo.addProperty("reason", info.reason());
            lo.addProperty("bottles", bottles);
            out.add(lo);
        }
        if (out.isEmpty()) return null;
        JsonObject e = new JsonObject();
        e.addProperty("needed", needed);
        e.addProperty("available", available);
        e.addProperty("short", available < needed);
        e.add("lines", out);
        return e;
    }

    /**
     * POST /api/v1/orders/{id}/retry: a new pending order with the old one's server, stash, lines and names, checked
     * like any new order. Only failed and cancelled orders (409 otherwise, 404 for an unknown id). The old one stays.
     */
    Order retry(String id) throws OrderException, IOException {
        return retry(id, null, null);
    }

    /** The same; the new order is recorded as placed by byId (who pressed Retry). */
    Order retry(String id, String byId, String byName) throws OrderException, IOException {
        Book b = byOrderId.get(id);
        if (b == null) throw new OrderException(404, "no such order", -1);
        List<Line> lines;
        JsonObject names;
        boolean repairAll;
        Order old;
        synchronized (b) {
            old = find(b, id);
            if (old == null) throw new OrderException(404, "no such order", -1);
            if (!old.status.equals("failed") && !old.status.equals("cancelled")) {
                throw new OrderException(409, "only failed or cancelled orders can be retried (this one is " + old.status + ")", -1);
            }
            // Once only: a second Retry (or a planner part ordered again) would order the same lines twice.
            if (old.retriedAs != null) {
                throw new OrderException(409, PENDING.equals(old.retriedAs) ? "this order is being retried right now"
                    : "this order was already placed again as " + old.retriedAs, -1);
            }
            old.retriedAs = PENDING;
            lines = new ArrayList<>(old.lines);
            names = old.names;
            repairAll = old.repairAll;
        }
        Order again;
        try {
            again = place(b.server, b.stash, lines, names, repairAll, byId, byName);
        } catch (OrderException | IOException | RuntimeException e) {
            synchronized (b) {
                if (PENDING.equals(old.retriedAs)) old.retriedAs = null;
            }
            if (e instanceof OrderException oe && "deleted".equals(oe.kit)) {
                throw new OrderException(409, "that kit was deleted; the order can't be placed again", oe.line, null, "deleted");
            }
            throw e;
        }
        markRetried(id, again.id);
        return again;
    }

    private static final String PENDING = "\u0000pending";

    /** Records that order id was placed again as newId (so it can't be retried again, and its status follows newId). */
    void markRetried(String id, String newId) throws IOException {
        Book b = byOrderId.get(id);
        if (b == null) return;
        synchronized (b) {
            Order o = find(b, id);
            if (o == null) return;
            o.retriedAs = newId;
            save(b);
        }
    }

    /**
     * Validates and normalizes an order's names (PROTOCOL.md, "Additions: shulker names"). Returns null for no naming
     * (absent, null, a blank "all" name, or an "each" list that is all blank), otherwise {mode: "all", name} or
     * {mode: "each", names: [...]} with every name trimmed, blank entries as "", and trailing blank entries dropped.
     * Throws 400 for a wrong type, an unknown mode, a name over 50 characters or with § or a control character, or
     * an "each" list of more than 54 entries.
     */
    static JsonObject normalizeNames(JsonElement e) throws OrderException {
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonObject()) throw new OrderException(400, "names must be an object or null", -1);
        JsonObject o = e.getAsJsonObject();
        JsonElement mode = o.get("mode");
        String m = mode != null && mode.isJsonPrimitive() && mode.getAsJsonPrimitive().isString() ? mode.getAsString() : null;
        if ("all".equals(m)) {
            JsonElement n = o.get("name");
            if (n == null || !n.isJsonPrimitive() || !n.getAsJsonPrimitive().isString()) {
                throw new OrderException(400, "names.name must be a string", -1);
            }
            String name = checkName(n.getAsString(), "names.name");
            if (name.isEmpty()) return null;
            JsonObject out = new JsonObject();
            out.addProperty("mode", "all");
            out.addProperty("name", name);
            return out;
        }
        if ("each".equals(m)) {
            JsonElement list = o.get("names");
            if (list == null || !list.isJsonArray()) throw new OrderException(400, "names.names must be an array", -1);
            JsonArray a = list.getAsJsonArray();
            if (a.size() > MAX_SHULKERS) throw new OrderException(400, "names.names has more than " + MAX_SHULKERS + " entries", -1);
            List<String> names = new ArrayList<>();
            int last = -1;
            for (int i = 0; i < a.size(); i++) {
                JsonElement n = a.get(i);
                String name;
                if (n.isJsonNull()) {
                    name = "";
                } else if (n.isJsonPrimitive() && n.getAsJsonPrimitive().isString()) {
                    name = checkName(n.getAsString(), "names.names[" + i + "]");
                } else {
                    throw new OrderException(400, "names.names[" + i + "] must be a string", -1);
                }
                names.add(name);
                if (!name.isEmpty()) last = i;
            }
            if (last < 0) return null;
            JsonArray outList = new JsonArray();
            for (int i = 0; i <= last; i++) outList.add(names.get(i));
            JsonObject out = new JsonObject();
            out.addProperty("mode", "each");
            out.add("names", outList);
            return out;
        }
        throw new OrderException(400, "names.mode must be \"all\" or \"each\"", -1);
    }

    /** The trimmed name ("" when blank), or 400 when it is too long or has § or a control character. */
    private static String checkName(String raw, String field) throws OrderException {
        String name = raw.strip();
        if (name.length() > MAX_NAME) throw new OrderException(400, field + " is longer than " + MAX_NAME + " characters", -1);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '§') throw new OrderException(400, field + " can't contain §", -1);
            if (Character.isISOControl(c)) throw new OrderException(400, field + " can't contain control characters", -1);
            // Invisible or direction-changing characters (bidi overrides, zero-width, line separators) and broken surrogates
            int type = Character.getType(c);
            if (type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                || (type == Character.SURROGATE && !(Character.isHighSurrogate(c) && i + 1 < name.length() && Character.isLowSurrogate(name.charAt(i + 1)))
                    && !(Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(name.charAt(i - 1))))) {
                throw new OrderException(400, field + " can't contain invisible or direction-changing characters", -1);
            }
        }
        return name;
    }

    /**
     * How many shulkers the addon's packer (Order.pack) needs for these lines: every item line takes
     * ceil(count / maxStack) slots, the slots of all lines fill 27-slot shulkers in sequence, and every shulker line
     * and empty-shulker line (an item line for a shulker box) adds one shulker per unit.
     */
    static long shulkersNeeded(Iterable<Line> lines, IndexStore.OrderCounts counts) {
        long slots = 0;
        long units = 0;
        for (Line l : lines) {
            if (l.type.equals("craft")) {
                long max = CraftService.maxStack(counts, l.key);
                slots += (l.count + max - 1) / max;
            } else if (l.type.equals("kit")) {
                units += l.count; // one shulker per kit
            } else if (l.type.equals("shulker") || counts.isShulkerBoxKey(l.key)) {
                units += l.count;
            } else {
                long max = counts.maxStack(l.key);
                slots += (l.count + max - 1) / max;
            }
        }
        return units + (slots + SHULKER_SLOTS - 1) / SHULKER_SLOTS;
    }

    private String newId() {
        while (true) {
            byte[] bytes = new byte[6];
            RANDOM.nextBytes(bytes);
            String id = "o-" + HexFormat.of().formatHex(bytes);
            if (!byOrderId.containsKey(id)) return id;
        }
    }

    /** { orders: [newest first], available: { items: {key: n}, shulkers: {key: n} } } */
    JsonObject listJson(String server, String stash) {
        JsonObject out = new JsonObject();
        JsonArray list = new JsonArray();
        Book b = books.get(new IndexStore.StashKey(server, stash));
        Map<String, Long> reserved = Map.of();
        if (b != null) {
            synchronized (b) {
                for (int i = b.orders.size() - 1; i >= 0; i--) list.add(b.orders.get(i).toJson());
                reserved = reserved(b);
            }
        }
        out.add("orders", list);
        JsonObject available = new JsonObject();
        IndexStore.OrderCounts counts = index.orderCounts(server, stash);
        JsonObject items = new JsonObject();
        JsonObject shulkers = new JsonObject();
        if (counts != null) {
            for (String key : counts.items().keySet()) {
                items.addProperty(key, Math.max(0, counts.total("item", key) - reserved.getOrDefault("item:" + key, 0L)));
            }
            for (String key : counts.shulkers().keySet()) {
                shulkers.addProperty(key, Math.max(0, counts.total("shulker", key) - reserved.getOrDefault("shulker:" + key, 0L)));
            }
        }
        available.add("items", items);
        available.add("shulkers", shulkers);
        available.addProperty("bottles", bottlesAvailable(counts, reserved));
        out.add("available", available);
        return out;
    }

    /**
     * Removes every finished order (delivered, failed, cancelled) of a stash and returns how many. Open orders are
     * never touched: they hold reservations and the bot may be working on them.
     */
    int clearFinished(String server, String stash) throws IOException {
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b == null) return 0;
        synchronized (b) {
            int removed = 0;
            for (java.util.Iterator<Order> it = b.orders.iterator(); it.hasNext(); ) {
                Order o = it.next();
                if (!FINISHED.contains(o.status)) continue;
                it.remove();
                byOrderId.remove(o.id, b);
                removed++;
            }
            if (removed > 0) save(b);
            return removed;
        }
    }

    /** pending -> cancelled now; accepted/in_progress -> cancelRequested; finished -> 409. */
    Order cancel(String id) throws OrderException, IOException {
        Book b = byOrderId.get(id);
        if (b == null) throw new OrderException(404, "no such order", -1);
        synchronized (b) {
            Order o = find(b, id);
            if (o == null) throw new OrderException(404, "no such order", -1);
            if (FINISHED.contains(o.status)) throw new OrderException(409, "order is already " + o.status, -1);
            if (o.status.equals("pending")) {
                o.status = "cancelled";
                o.message = "Cancelled before the bot started.";
            } else if (!o.cancelRequested) {
                o.cancelRequested = true;
                o.cancelRequestedAt = System.currentTimeMillis();
            }
            o.updatedAt = System.currentTimeMillis();
            save(b);
            return o;
        }
    }

    /**
     * Marks an accepted/in_progress order whose cancel was requested as cancelled without the bot's confirmation,
     * which releases its reservations. The bot's later status reports for it get 409 (finished orders can't change).
     * 409 when no cancel was requested or the order isn't held by the bot.
     */
    Order forceCancel(String id) throws OrderException, IOException {
        Book b = byOrderId.get(id);
        if (b == null) throw new OrderException(404, "no such order", -1);
        synchronized (b) {
            Order o = find(b, id);
            if (o == null) throw new OrderException(404, "no such order", -1);
            if (FINISHED.contains(o.status)) throw new OrderException(409, "order is already " + o.status, -1);
            if (!o.cancelRequested || o.status.equals("pending")) {
                throw new OrderException(409, "request a cancel first; force cancel is for orders the bot hasn't confirmed", -1);
            }
            o.status = "cancelled";
            o.message = "cancelled from the website (bot didn't confirm)";
            o.updatedAt = System.currentTimeMillis();
            prune(b);
            save(b);
            return o;
        }
    }

    /** Who placed an order: Users.OWNER_ID for old orders without a record; null for an unknown order. */
    String placedBy(String id) {
        Book b = byOrderId.get(id);
        if (b == null) return null;
        synchronized (b) {
            Order o = find(b, id);
            if (o == null) return null;
            return o.placedById == null ? Users.OWNER_ID : o.placedById;
        }
    }

    /** An order's status, or null for an unknown order (never placed, or cleared from the history). */
    String statusOf(String id) {
        // An order placed again stands for its successor: a failed part that was retried is live again.
        for (int hops = 0; hops < 64 && id != null; hops++) {
            Book b = byOrderId.get(id);
            if (b == null) return null;
            String next;
            synchronized (b) {
                Order o = find(b, id);
                if (o == null) return null;
                if (o.retriedAs == null) return o.status;
                if (PENDING.equals(o.retriedAs)) return "pending";
                next = o.retriedAs;
            }
            id = next;
        }
        return "pending";
    }

    /** The (server, stash) an order belongs to, or null. */
    IndexStore.StashKey stashOf(String id) {
        Book b = byOrderId.get(id);
        return b == null ? null : new IndexStore.StashKey(b.server, b.stash);
    }

    /** For the website's bot overview: { open: n, active: { id, status, phase, progress } | null }. */
    JsonObject summaryJson(String server, String stash) {
        JsonObject out = new JsonObject();
        int open = 0;
        JsonObject active = null;
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b != null) {
            synchronized (b) {
                for (Order o : b.orders) {
                    if (!OPEN.contains(o.status)) continue;
                    open++;
                    if (active == null && !o.status.equals("pending")) {
                        active = new JsonObject();
                        active.addProperty("id", o.id);
                        active.addProperty("status", o.status);
                        active.addProperty("phase", o.phase);
                        active.add("progress", o.progress == null ? null : o.progress.deepCopy());
                    }
                }
            }
        }
        out.addProperty("open", open);
        out.add("active", active);
        return out;
    }

    private static Order find(Book b, String id) {
        for (Order o : b.orders) if (o.id.equals(id)) return o;
        return null;
    }

    // ---------- addon ----------

    /** Whether the bot must carry out craft orders for this one: a craft line, or craft steps planned for a kit's shortfalls. */
    private static boolean hasCraft(Order o) {
        if (o.craft != null) return true;
        for (Line l : o.lines) if (l.type.equals("craft")) return true;
        return false;
    }

    private static boolean hasKit(Order o) {
        for (Line l : o.lines) if (l.type.equals("kit")) return true;
        return false;
    }

    /** { pending: n, cancel: [ids of held orders with a cancel requested] } for the heartbeat reply. */
    JsonObject heartbeatJson(String server, String stash) {
        return heartbeatJson(server, stash, true);
    }

    /**
     * The same; with craftCapable false (a bot that did not say it carries out craft orders) the pending orders with craft
     * lines are not counted, so an older bot is not told to claim what it will not be given.
     */
    JsonObject heartbeatJson(String server, String stash, boolean craftCapable) {
        return heartbeatJson(server, stash, craftCapable, true);
    }

    /** The same; with kitCapable false the pending orders with kit lines are not counted either. */
    JsonObject heartbeatJson(String server, String stash, boolean craftCapable, boolean kitCapable) {
        JsonObject out = new JsonObject();
        JsonArray cancel = new JsonArray();
        int pending = 0;
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b != null) {
            synchronized (b) {
                for (Order o : b.orders) {
                    if (o.status.equals("pending")) {
                        if ((craftCapable || !hasCraft(o)) && (kitCapable || !hasKit(o))) pending++;
                    } else if (o.cancelRequested && (o.status.equals("accepted") || o.status.equals("in_progress"))) cancel.add(o.id);
                }
            }
        }
        out.addProperty("pending", pending);
        out.add("cancel", cancel);
        return out;
    }

    /** The held (accepted/in_progress) order if there is one, else the oldest pending one, now accepted. Null = none. */
    Order claim(String server, String stash) throws IOException {
        return claim(server, stash, true);
    }

    static final String DOWNGRADED = "the bot can no longer craft (was it downgraded?); retry the order";

    /**
     * The same. craftOrders is the claim's flag (craftOrders >= 1): without it (an older bot) a held order with craft lines
     * becomes failed, which releases its reservations, and pending orders with craft lines are skipped and stay pending.
     */
    Order claim(String server, String stash, boolean craftOrders) throws IOException {
        return claim(server, stash, craftOrders, true);
    }

    static final String KIT_DOWNGRADED = "the bot can no longer build kits; retry the order";

    /**
     * The same, with the claim's kitOrders flag: without it a held order with kit lines becomes failed (which releases its
     * reservations) and pending orders with kit lines are skipped and stay pending.
     */
    Order claim(String server, String stash, boolean craftOrders, boolean kitFlag) throws IOException {
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b == null) return null;
        synchronized (b) {
            boolean changed = false;
            long now = clock.getAsLong();
            try {
                for (Order o : b.orders) {
                    if (!(o.status.equals("accepted") || o.status.equals("in_progress"))) continue;
                    boolean needCraft = hasCraft(o) && !craftOrders;
                    boolean needKit = hasKit(o) && !kitFlag;
                    if (!needCraft && !needKit) {
                        o.flaglessSince = 0;
                        o.flaglessClaims = 0;
                        o.lastFlagless = 0;
                        o.flaglessEpisodes = 0;
                        return o;
                    }
                    // A bot that restarted (its recipe fetch may have got a 503 or 429, or its kit settings not loaded yet) must not lose a
                    // held order that needs a capability the claim lacks. It is failed only when the third or a later flagless claim comes
                    // more than 120 s after the first one, this server has been up for more than 120 s (a restart gives the bots that long
                    // to come back), and no heartbeat with the missing capability(ies) came since the first. Until then the claim answers
                    // 204 and the order stays held. Craft and kit orders (and the two combined) follow the same rule.
                    // Flagless claims more than 10 minutes apart are not one episode (a bot that restarted gets a fresh grace).
                    if (o.lastFlagless != 0 && now - o.lastFlagless > DOWNGRADE_ABSOLUTE_MS) {
                        o.flaglessEpisodes++;
                        o.flaglessSince = 0;
                        o.flaglessClaims = 0;
                    }
                    o.lastFlagless = now;
                    if (o.flaglessSince == 0) o.flaglessSince = now;
                    o.flaglessClaims++;
                    CraftService cs = craftService;
                    KitOrders ko = kitOrders;
                    long window = now - o.flaglessSince;
                    boolean alive = (!needCraft || (cs != null && cs.capableWithin(server, stash, window)))
                        && (!needKit || (ko != null && ko.capableWithin(server, stash, window)));
                    boolean expired = o.flaglessClaims >= 3 && window > DOWNGRADE_GRACE_MS && now - startedAt > DOWNGRADE_GRACE_MS
                        && (!alive || window > DOWNGRADE_ABSOLUTE_MS);
                    // A claim every 11 minutes would restart the episode each time: the third such claim fails it.
                    if (o.flaglessEpisodes >= 2 && now - startedAt > DOWNGRADE_GRACE_MS) expired = true;
                    if (!expired) return null;
                    o.status = "failed";
                    o.message = needKit && !needCraft ? KIT_DOWNGRADED : DOWNGRADED;
                    o.updatedAt = System.currentTimeMillis();
                    changed = true;
                }
            } finally {
                if (changed) save(b);
            }
            for (Order o : b.orders) {
                if (o.status.equals("pending") && (craftOrders || !hasCraft(o)) && (kitFlag || !hasKit(o))) {
                    o.status = "accepted";
                    o.updatedAt = System.currentTimeMillis();
                    save(b);
                    return o;
                }
            }
            return null;
        }
    }

    /** A status report from the bot. Fields are validated by the route; the move is checked here. */
    Order status(String id, String status, String phase, JsonObject progress, String message, JsonObject result)
        throws OrderException, IOException {
        Book b = byOrderId.get(id);
        if (b == null) throw new OrderException(404, "no such order", -1);
        synchronized (b) {
            Order o = find(b, id);
            if (o == null) throw new OrderException(404, "no such order", -1);
            if (!allowed(o.status, status)) {
                throw new OrderException(409, "can't go from " + o.status + " to " + status, -1);
            }
            o.status = status;
            if (phase != null) o.phase = phase;
            if (progress != null) o.progress = progress;
            if (message != null) o.message = message;
            if (result != null) o.result = result;
            o.updatedAt = System.currentTimeMillis();
            prune(b);
            save(b);
            return o;
        }
    }

    /**
     * Forward moves only: accepted -> accepted/in_progress/finished, in_progress -> in_progress/finished.
     * (Repeating the current status is how the bot reports phase and progress.) Pending and finished orders
     * can't be changed by the bot.
     */
    static boolean allowed(String from, String to) {
        if (from.equals("accepted")) return to.equals("accepted") || to.equals("in_progress") || FINISHED.contains(to);
        if (from.equals("in_progress")) return to.equals("in_progress") || FINISHED.contains(to);
        return false;
    }
}
