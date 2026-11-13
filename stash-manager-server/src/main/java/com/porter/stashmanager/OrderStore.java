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
    /** The states that reserve (an order the bot may be working on, or waits to claim). */
    static final Set<String> OPEN = Set.of("pending", "accepted", "in_progress");
    /** An order waiting for the orderer's curse approval (ENCHANT-CONTRACT v1.0, D1/D2): reserves nothing, counts toward MAX_OPEN. */
    static final String HELD_CURSE = "held_curse";
    /** What counts toward MAX_OPEN: the reserving states and the awaiting-approval one. */
    static final Set<String> COUNTED = Set.of("pending", "accepted", "in_progress", HELD_CURSE);
    static final int MAX_HELD_CURSE_PER_USER = 3;
    /** After the bot's heartbeat goes stale, a force-cancelled enchant order keeps its reservations this much longer. */
    static final long HOLD_AFTER_STALE_MS = 120_000;
    /** The longest a force-cancelled enchant order's reservations are held, whatever the bot's heartbeats say (coordinator decision, 10 minutes). */
    static final long HOLD_MAX_MS = 600_000;
    static final Set<String> FINISHED = Set.of("delivered", "failed", "cancelled");
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * type is item, shulker, craft (CRAFT-CONTRACT.md: a craft line makes the item from stash materials), kit
     * (KIT-CONTRACT-v2: a finished kit of a template; key is the template id) or enchant (ENCHANT-CONTRACT v1.0: key is the item,
     * enchants the ordered enchantments; null for the other types). repair: the
     * line's "Repair" toggle (PROTOCOL.md, "Additions: repair with XP bottles"). */
    record Line(String type, String key, long count, boolean repair, List<EnchantWant> enchants) {
        Line(String type, String key, long count, boolean repair) {
            this(type, key, count, repair, null);
        }

        Line(String type, String key, long count) {
            this(type, key, count, false, null);
        }

        String ref() {
            return type + ":" + key;
        }
    }

    /** One ordered enchantment: id as the request wrote it (namespaced or plain), lvl 1..255. */
    record EnchantWant(String id, int lvl) {
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
        /** The code of a refused enchant line (ENCHANT-CONTRACT v1.0): no_bot / mc_unsupported (409) or an order-check or planning code (400), else null. */
        final String enchant;
        /** Accept-curse: the curses the order needs now (a 409 for a list that differs), or null. */
        List<String> curseList;

        OrderException(int status, String message, int line) {
            this(status, message, line, null, null);
        }

        OrderException(int status, String message, int line, String craft) {
            this(status, message, line, craft, null);
        }

        OrderException(int status, String message, int line, String craft, String kit) {
            this(status, message, line, craft, kit, null);
        }

        private OrderException(int status, String message, int line, String craft, String kit, String enchant) {
            super(message);
            this.status = status;
            this.line = line;
            this.craft = craft;
            this.kit = kit;
            this.enchant = enchant;
        }

        /** A refused enchant line: status 409 with no_bot / mc_unsupported, or 400 with a code of the contract. */
        static OrderException enchant(int status, String message, int line, String code) {
            return new OrderException(status, message, line, null, null, code);
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
        JsonObject enchant; // ENCHANT-CONTRACT v1.0: {v, mc, lines, take, curse}, made at placement; null when there are no enchant lines
        JsonObject hold; // {reason: "curse", curse: [ids]} while the order is awaiting curse approval, else null
        /** The player of the claim that took an enchant order; status calls must come from it (409 otherwise). Never sent to a browser. */
        String claimedBy;
        /** When an enchant order was force-cancelled and its reservations are held until the bot acknowledges (0 = no hold). Never sent to a browser. */
        long forceHoldAt;
        /** Asks whether the force-cancel hold is still active (set when the order is held or loaded with a hold); null for orders without one. */
        java.util.function.BooleanSupplier holdProbe;

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
                if (l.enchants != null) lo.add("enchants", EnchantOrders.wantsJson(l.enchants));
                ls.add(lo);
            }
            o.add("lines", ls);
            o.add("names", names == null ? null : names.deepCopy());
            o.addProperty("repairAll", repairAll);
            o.add("repairEstimate", repairEstimate == null ? com.google.gson.JsonNull.INSTANCE : repairEstimate.deepCopy());
            o.add("craft", craft == null ? com.google.gson.JsonNull.INSTANCE : craft.deepCopy());
            if (kit != null) o.add("kit", kit.deepCopy());
            if (enchant != null) o.add("enchant", enchant.deepCopy());
            if (hold != null) o.add("hold", hold.deepCopy());
            if (forceHoldAt > 0 && holdProbe != null && holdProbe.getAsBoolean()) o.addProperty("reservationsHeld", true); // a page note; never stored
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

        /** What is written to disk: the order as a browser sees it, plus the bot-binding and hold state that no page may see. */
        JsonObject toDiskJson() {
            JsonObject o = toJson();
            if (claimedBy != null) o.addProperty("claimedBy", claimedBy);
            o.remove("reservationsHeld");
            if (forceHoldAt > 0) o.addProperty("forceHoldAt", forceHoldAt);
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
                List<EnchantWant> wants = null;
                if (l.has("enchants") && l.get("enchants").isJsonArray()) {
                    wants = new ArrayList<>();
                    for (JsonElement w : l.getAsJsonArray("enchants")) {
                        JsonObject wo = w.getAsJsonObject();
                        wants.add(new EnchantWant(wo.get("id").getAsString(), wo.get("lvl").getAsInt()));
                    }
                }
                r.lines.add(new Line(l.get("type").getAsString(), l.get("key").getAsString(), l.get("count").getAsLong(),
                    l.has("repair") && l.get("repair").isJsonPrimitive() && l.get("repair").getAsBoolean(), wants));
            }
            r.enchant = o.has("enchant") && o.get("enchant").isJsonObject() ? o.getAsJsonObject("enchant") : null;
            r.hold = o.has("hold") && o.get("hold").isJsonObject() ? o.getAsJsonObject("hold") : null;
            r.claimedBy = str(o, "claimedBy");
            r.forceHoldAt = o.has("forceHoldAt") && o.get("forceHoldAt").isJsonPrimitive() ? o.get("forceHoldAt").getAsLong() : 0;
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
    private volatile EnchantOrders enchantOrders; // null: this server takes no enchant lines
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

    /** Gives the store the enchant planner (set once at start). Without it an enchant line is refused. */
    void enchant(EnchantOrders enchant) {
        this.enchantOrders = enchant;
    }

    EnchantOrders enchantOrders() {
        return enchantOrders;
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
                            if (o.forceHoldAt > 0) o.holdProbe = () -> holdActive(o);
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
        for (Order o : b.orders) list.add(o.toDiskJson());
        root.add("orders", list);
        AtomicFiles.write(dir.resolve(IndexStore.fileName(b.server, b.stash)), Json.GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    /** Drops the oldest finished orders beyond KEEP. Open orders are never pruned. */
    private void prune(Book b) {
        int excess = b.orders.size() - KEEP;
        for (int i = 0; i < b.orders.size() && excess > 0; ) {
            Order o = b.orders.get(i);
            if (FINISHED.contains(o.status) && !holdActive(o)) { // a force-cancelled enchant order still reserves until the hold ends
                b.orders.remove(i);
                byOrderId.remove(o.id, b);
                excess--;
            } else {
                i++;
            }
        }
    }

    private Map<String, Long> reserved(Book b) {
        Map<String, Long> out = new HashMap<>();
        for (Order o : b.orders) {
            if (!OPEN.contains(o.status) && !holdActive(o)) continue; // held_curse reserves nothing (D2)
            addReservations(o, out);
        }
        return out;
    }

    /** What one order reserves: its lines (the enchant lines through their take instead), craft and kit takes, the enchant take. */
    static void addReservations(Order o, Map<String, Long> out) {
        // An enchant line has no ref of its own: what it takes is the order's enchant take, by exact index key (TL2-B4).
        for (Line l : o.lines) if (!l.type.equals("enchant")) out.merge(l.ref(), l.count, Long::sum);
        // The ingredients of a craft order are taken from the stash like item lines (CRAFT-CONTRACT.md, "The pool").
        CraftService.take(o.craft).forEach((id, n) -> out.merge("item:" + id, n, Long::sum));
        KitOrders.reservations(o, out);
        enchantTake(o).forEach((key, n) -> out.merge("item:" + key, n, Long::sum));
    }

    /** The enchant take of an order by index key (empty for an order with none, and for one awaiting curse approval). */
    static Map<String, Long> enchantTake(Order o) {
        return takeOf(o.enchant);
    }

    /** The {@code take} of an order's enchant object by index key. */
    static Map<String, Long> takeOf(JsonObject enchant) {
        Map<String, Long> out = new HashMap<>();
        if (enchant == null || !enchant.has("take") || !enchant.get("take").isJsonArray()) return out;
        for (JsonElement e : enchant.getAsJsonArray("take")) {
            if (!e.isJsonObject()) continue;
            JsonObject t = e.getAsJsonObject();
            if (t.has("key") && t.has("count")) out.merge(t.get("key").getAsString(), t.get("count").getAsLong(), Long::sum);
        }
        return out;
    }

    /**
     * Whether a force-cancelled enchant order still reserves (SR2-N1): until the bot acknowledges at its next step boundary (its next
     * status call, which clears forceHoldAt), or 2 minutes after its heartbeat goes stale ({@link EnchantBots#FRESH_MS} old), counted from
     * the force-cancel when the bot was never heard.
     */
    private boolean holdActive(Order o) {
        EnchantOrders eo = enchantOrders;
        if (o.forceHoldAt <= 0 || !"cancelled".equals(o.status) || eo == null) return false;
        long now = eo.bots().now();
        EnchantBots.Beat beat = eo.bots().get(o.server, o.stash);
        long ref = Math.max(o.forceHoldAt, beat == null ? 0 : beat.at());
        boolean active = now - o.forceHoldAt < HOLD_MAX_MS && now - ref < EnchantBots.FRESH_MS + HOLD_AFTER_STALE_MS;
        // Sticky: once the hold has ended it never switches back on (a later heartbeat doesn't revive it). Written with the next save.
        if (!active) o.forceHoldAt = 0;
        return active;
    }

    /** The bottles every open enchant order of the stash reserves (the heartbeat reply's enchant.bottlesReserved), held ones included. */
    long enchantBottlesReserved(String server, String stash) {
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b == null) return 0;
        synchronized (b) {
            long n = 0;
            for (Order o : b.orders) {
                if (!OPEN.contains(o.status) && !holdActive(o)) continue;
                n += enchantTake(o).getOrDefault(Repair.BOTTLE_KEY, 0L);
            }
            return n;
        }
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
        // Enchant lines never merge (ENCHANT-CONTRACT v1.0): each keeps its own enchantments and plans.
        Map<String, Line> merged = new LinkedHashMap<>();
        Map<String, Integer> firstIndex = new HashMap<>();
        List<Line> enchantLines = new ArrayList<>();
        List<EnchantOrders.Req> enchantReqs = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            Line l = requested.get(i);
            if (l.type.equals("enchant")) {
                if (l.count < 1 || l.count > EnchantOrders.MAX_PER_LINE) {
                    throw OrderException.enchant(400, "count of an enchant line must be a whole number from 1 to " + EnchantOrders.MAX_PER_LINE, i, "bad_request");
                }
                enchantLines.add(l);
                enchantReqs.add(new EnchantOrders.Req(i, l.key, (int) l.count, l.enchants == null ? List.of() : l.enchants));
                continue;
            }
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
        EnchantOrders.Result enchantPlan = null;
        EnchantOrders.Budget enchantBudget = new EnchantOrders.Budget(); // one clock and one unit total over the re-plans of this placement
        long planVersion = -1;
        // A craft plan can take seconds: it is made outside the lock, against the orders as they were (the book's version),
        // and made again if they changed before the order is stored. The lock is never held for a full plan.
        for (int attempt = 0; ; attempt++) {
            boolean planned = firstCraft != null || firstKit != null || !enchantReqs.isEmpty();
            if (planned) {
                craftPlan = null; // made again on every attempt (an earlier attempt's base craft must not linger)
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
                if (!enchantReqs.isEmpty()) {
                    EnchantOrders eo = enchantOrders;
                    if (eo == null) throw new OrderException(400, "this server can't plan enchant orders", enchantReqs.get(0).line());
                    // The rest of this order (its item lines, craft and kit takes) is reserved before the enchant take is drawn.
                    Map<String, Long> forEnchant = new HashMap<>(snapshot);
                    Order own = new Order();
                    own.lines = new ArrayList<>(merged.values());
                    own.craft = craftPlan;
                    own.kit = kitPlan == null ? null : kitPlan.kit();
                    addReservations(own, forEnchant);
                    // ENCHANT-CONTRACT draft 3.4: an item with no usable base in the stash gets a crafted one when the stash can craft.
                    // This first pass finds how many bases are missing; the craft is then planned (its take reserved), and the enchant
                    // plan is made again around it, so the two never use the same stock.
                    EnchantOrders.BaseCraft bc = eo.baseCraft(server, stash);
                    enchantPlan = eo.planOrder(server, stash, counts, forEnchant, enchantReqs, true, enchantBudget, bc);
                    Map<String, Integer> bases = bc == null ? Map.of() : bc.used();
                    if (!bases.isEmpty()) {
                        if (cs == null) throw new OrderException(400, "this server can't craft orders", enchantReqs.get(0).line());
                        JsonObject withoutBases = craftPlan; // the craft lines' and kit shortfalls' plan alone
                        Map<String, Long> forCraft2 = new HashMap<>(snapshot);
                        takeOf(enchantPlan.enchant()).forEach((k, n) -> forCraft2.merge("item:" + k, n, Long::sum)); // the craft can't eat the enchant stock
                        for (int round = 0; ; round++) {
                            craftPlan = planWithBases(cs, server, stash, counts, forCraft2, forCraft, craftIndex, bases, enchantReqs);
                            EnchantOrders.BaseCraft fixed = EnchantOrders.BaseCraft.fixed(bases);
                            own.craft = craftPlan;
                            Map<String, Long> forEnchant2 = new HashMap<>(snapshot);
                            addReservations(own, forEnchant2);
                            enchantPlan = eo.planOrder(server, stash, counts, forEnchant2, enchantReqs, true, enchantBudget, fixed);
                            Map<String, Integer> got = fixed.used();
                            if (got.equals(bases)) break;
                            if (got.isEmpty()) { // a real base turned up: nothing to craft
                                craftPlan = withoutBases;
                                break;
                            }
                            if (round >= 1) throw new OrderException(409, "the stash changed while this order was being planned; try again", -1);
                            bases = got;
                        }
                    }
                }
                Runnable hook = afterPlan;
                if (hook != null) hook.run();
            }
            synchronized (b) {
                if (planned && b.version != planVersion) {
                    if (attempt >= 2) throw new OrderException(409, "other orders changed while this one was being planned; try again", -1);
                    continue;
                }
                return storeLocked(b, server, stash, merged, firstIndex, names, repairAll, byId, byName, counts, craftPlan,
                    kitPlan == null ? null : kitPlan.kit(), enchantLines, enchantPlan);
            }
        }
    }

    /**
     * The craft plan of an order that also crafts enchant bases (ENCHANT-CONTRACT draft 3.4): the order's craft lines, the kits' shortfalls and
     * one craft line per missing base (an item with no usable base), planned together so they share ingredients and the caps. The result's
     * {@code forEnchant} lists how many of each item are bases for the enchant plans (they are made, not delivered). A shortage or a refusal
     * is the craft planner's own error, at the enchant line that needs the base.
     */
    private static JsonObject planWithBases(CraftService cs, String server, String stash, IndexStore.OrderCounts counts, Map<String, Long> reserved,
                                            Collection<Line> forCraft, Map<String, Integer> craftIndex, Map<String, Integer> bases,
                                            List<EnchantOrders.Req> reqs) throws OrderException {
        Map<String, Line> all = new LinkedHashMap<>();
        for (Line l : forCraft) all.put(l.ref(), l);
        Map<String, Integer> at = new HashMap<>(craftIndex);
        JsonArray forEnchant = new JsonArray();
        for (Map.Entry<String, Integer> e : bases.entrySet()) {
            Line c = new Line("craft", e.getKey(), e.getValue());
            Line prev = all.get(c.ref());
            all.put(c.ref(), prev == null ? c : new Line("craft", e.getKey(), prev.count + e.getValue(), prev.repair));
            int line = reqs.get(0).line();
            for (EnchantOrders.Req r : reqs) {
                if (EnchantIndex.namespaced(com.porter.qwerte.core.enchant.McData.norm(r.key())).equals(e.getKey())) {
                    line = r.line();
                    break;
                }
            }
            at.putIfAbsent(c.ref(), line);
            JsonObject fo = new JsonObject();
            fo.addProperty("key", e.getKey());
            fo.addProperty("count", e.getValue());
            forEnchant.add(fo);
        }
        JsonObject craft = cs.planOrder(server, stash, counts, reserved, all.values(), at);
        craft.add("forEnchant", forEnchant);
        return craft;
    }

    /** The enchant bases an order's craft block makes ({@code craft.forEnchant}), by namespaced item id; empty for an order with none. */
    static Map<String, Integer> forEnchant(JsonObject craft) {
        Map<String, Integer> out = new java.util.TreeMap<>();
        if (craft == null || !craft.has("forEnchant") || !craft.get("forEnchant").isJsonArray()) return out;
        for (JsonElement e : craft.getAsJsonArray("forEnchant")) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (o.has("key") && o.has("count")) out.merge(o.get("key").getAsString(), o.get("count").getAsInt(), Integer::sum);
        }
        return out;
    }

    private Order storeLocked(Book b, String server, String stash, Map<String, Line> merged, Map<String, Integer> firstIndex,
                              JsonObject names, boolean repairAll, String byId, String byName, IndexStore.OrderCounts counts,
                              JsonObject craftPlan, JsonObject kitPlan, List<Line> enchantLines, EnchantOrders.Result enchantPlan)
        throws OrderException, IOException {
        {
            long open = b.orders.stream().filter(o -> COUNTED.contains(o.status)).count();
            if (open >= MAX_OPEN) throw new OrderException(400, "too many open orders for this stash (limit " + MAX_OPEN + ")", -1);
            boolean awaitingCurse = enchantPlan != null && !enchantPlan.curse().isEmpty();
            if (awaitingCurse) {
                String who = byId == null ? Users.OWNER_ID : byId;
                long waiting = b.orders.stream().filter(o -> HELD_CURSE.equals(o.status)
                    && who.equals(o.placedById == null ? Users.OWNER_ID : o.placedById)).count();
                if (waiting >= MAX_HELD_CURSE_PER_USER) {
                    throw new OrderException(400, "you already have " + MAX_HELD_CURSE_PER_USER
                        + " orders waiting for curse approval; answer or cancel one first", -1);
                }
            }
            Map<String, Long> reserved = reserved(b);
            // The enchant take of this order (reserved only when it is not awaiting curse approval) comes out of the item lines' stock too.
            Map<String, Long> ownTake = enchantPlan == null || awaitingCurse ? Map.of() : takeOf(enchantPlan.enchant());
            for (Line l : merged.values()) {
                int at = firstIndex.get(l.ref());
                if (l.type.equals("craft")) {
                    if (l.count > Integer.MAX_VALUE) throw new OrderException(400, "count too large", at);
                    continue; // planned below, with the item lines as the direct takes
                }
                if (l.type.equals("kit")) continue; // planned with the kit's template, before the lock
                long total = counts.total(l.type, l.key);
                if (total < 0) throw new OrderException(400, "not in the stash: " + l.key, at);
                long available = Math.max(0, total - reserved.getOrDefault(l.ref(), 0L) - (l.type.equals("item") ? ownTake.getOrDefault(l.key, 0L) : 0L));
                if (l.count > Integer.MAX_VALUE) throw new OrderException(400, "count too large", at);
                if (l.count > available) {
                    throw new OrderException(400, "only " + available + " available for " + l.key + " (asked for " + l.count + ")", at);
                }
            }
            List<Line> all = new ArrayList<>(merged.values());
            all.addAll(enchantLines);
            long shulkers = shulkersNeeded(all, counts);
            if (shulkers > MAX_SHULKERS) {
                throw new OrderException(400, "too big: this order needs " + shulkers + " shulkers, and one order can fill at most "
                    + MAX_SHULKERS + " (one double chest)", -1);
            }
            long now = System.currentTimeMillis();
            Order o = new Order();
            o.id = newId();
            o.server = server;
            o.stash = stash;
            o.lines = all;
            o.names = names == null ? null : names.deepCopy();
            o.repairAll = repairAll;
            o.repairEstimate = repairEstimate(o.lines, repairAll, counts, bottlesAvailable(counts, reserved));
            o.craft = craftPlan;
            o.kit = kitPlan;
            o.createdAt = now;
            o.updatedAt = now;
            o.status = "pending";
            if (enchantPlan != null) {
                o.enchant = enchantPlan.enchant();
                if (awaitingCurse) {
                    // D1/D2: it reserves nothing until the orderer says yes; accept re-plans and reserves then.
                    o.enchant.add("take", new JsonArray());
                    o.status = HELD_CURSE;
                    o.hold = holdJson(enchantPlan.curse());
                }
            }
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

    /**
     * The accept-curse checks that need no planning: 404 for an unknown order, 409 unless the order is awaiting curse approval, and 409
     * (the exception carries the stored list) when asked is not that list.
     */
    void checkAcceptable(String id, List<String> asked) throws OrderException {
        Book b = byOrderId.get(id);
        if (b == null) throw new OrderException(404, "no such order", -1);
        List<String> sorted = asked == null ? null : new ArrayList<>(asked);
        if (sorted != null) java.util.Collections.sort(sorted);
        synchronized (b) {
            Order o = find(b, id);
            if (o == null) throw new OrderException(404, "no such order", -1);
            if (!HELD_CURSE.equals(o.status)) throw new OrderException(409, "this order isn't waiting for curse approval", -1);
            List<String> stored = holdCurse(o);
            if (sorted != null && !stored.equals(sorted)) {
                OrderException e = new OrderException(409, "the curses this order needs have changed; ask again", -1);
                e.curseList = stored;
                throw e;
            }
        }
    }

    /**
     * POST /api/v1/orders/{id}/accept-curse (ENCHANT-CONTRACT v1.0, D1): the orderer says yes to the curses the order names. The caller
     * (the route) has checked scope and that the caller placed the order. Here: the state must be held_curse (409) and the consented
     * list must equal the stored one (409, the exception carries the current list); then the order is planned again OUTSIDE the lock, and the
     * book's version is checked under it. A curse-free plan, or one that needs only the consented curses, makes it pending with its
     * reservations; a plan that needs another curse keeps it awaiting with the new list (409); an infeasible plan fails the order with the
     * plan's reason (like retry).
     */
    Order acceptCurse(String id, List<String> consented) throws OrderException, IOException {
        Book b = byOrderId.get(id);
        if (b == null) throw new OrderException(404, "no such order", -1);
        List<String> asked = new ArrayList<>(consented);
        java.util.Collections.sort(asked);
        EnchantOrders eo = enchantOrders;
        if (eo == null) throw new OrderException(400, "this server can't plan enchant orders", -1);
        EnchantOrders.Budget budget = new EnchantOrders.Budget(); // one clock and one unit total over every attempt
        for (int attempt = 0; ; attempt++) {
            List<Line> lines;
            Map<String, Long> snapshot;
            long version;
            String server;
            String stash;
            JsonObject craftPlan;
            JsonObject kitPlan;
            synchronized (b) {
                Order o = find(b, id);
                if (o == null) throw new OrderException(404, "no such order", -1);
                if (!HELD_CURSE.equals(o.status)) throw new OrderException(409, "this order isn't waiting for curse approval", -1);
                List<String> stored = holdCurse(o);
                if (!stored.equals(asked)) {
                    OrderException e = new OrderException(409, "the curses this order needs have changed; ask again", -1);
                    e.curseList = stored;
                    throw e;
                }
                lines = new ArrayList<>(o.lines);
                craftPlan = o.craft;
                kitPlan = o.kit;
                snapshot = reserved(b);
                version = b.version;
                server = o.server;
                stash = o.stash;
            }
            IndexStore.OrderCounts counts = index.orderCounts(server, stash);
            EnchantOrders.Result res = null;
            OrderException failure = null;
            if (counts == null) {
                failure = new OrderException(400, "no index for that stash", -1);
            } else {
                List<EnchantOrders.Req> reqs = new ArrayList<>();
                Order own = new Order();
                own.lines = new ArrayList<>();
                own.craft = craftPlan; // the order's craft and kit takes are reserved once it runs, so the enchant take is drawn around them
                own.kit = kitPlan;
                for (int i = 0; i < lines.size(); i++) {
                    Line l = lines.get(i);
                    if (l.type.equals("enchant")) reqs.add(new EnchantOrders.Req(i, l.key, (int) l.count, l.enchants == null ? List.of() : l.enchants));
                    else own.lines.add(l);
                }
                Map<String, Long> forEnchant = new HashMap<>(snapshot);
                addReservations(own, forEnchant); // the order's other lines are reserved once it runs
                try {
                    // The order keeps its craft (draft 3.4): the bases it crafts are offered again, no more than craft.forEnchant says.
                    Map<String, Integer> bases = forEnchant(craftPlan);
                    res = eo.planOrder(server, stash, counts, forEnchant, reqs, false, budget, bases.isEmpty() ? null : EnchantOrders.BaseCraft.fixed(bases));
                } catch (OrderException e) {
                    failure = e;
                }
            }
            Runnable hook = afterPlan;
            if (hook != null) hook.run();
            synchronized (b) {
                Order o = find(b, id);
                if (o == null) throw new OrderException(404, "no such order", -1);
                if (!HELD_CURSE.equals(o.status)) throw new OrderException(409, "this order isn't waiting for curse approval", -1);
                if (b.version != version) {
                    if (attempt >= 2) throw new OrderException(409, "other orders changed while this one was being planned; try again", -1);
                    continue;
                }
                if (failure == null) {
                    // What the order reserves once it runs (its other lines, craft and kit takes, the new enchant take) must still be there.
                    Order probe = new Order();
                    probe.lines = o.lines;
                    probe.craft = o.craft;
                    probe.kit = o.kit;
                    probe.enchant = res.enchant();
                    failure = shortage(probe, counts, snapshot);
                }
                if (failure != null) {
                    o.status = "failed";
                    o.hold = null;
                    o.message = Http.truncate(failure.getMessage(), MAX_MESSAGE);
                    o.updatedAt = System.currentTimeMillis();
                    prune(b);
                    save(b);
                    return o;
                }
                if (!asked.containsAll(res.curse())) {
                    o.enchant = res.enchant();
                    o.enchant.add("take", new JsonArray());
                    o.hold = holdJson(res.curse());
                    o.updatedAt = System.currentTimeMillis();
                    save(b);
                    OrderException e = new OrderException(409, "this order now needs a curse you haven't accepted", -1);
                    e.curseList = new ArrayList<>(res.curse());
                    throw e;
                }
                o.enchant = res.enchant();
                o.hold = null;
                o.status = "pending";
                o.updatedAt = System.currentTimeMillis();
                save(b);
                return o;
            }
        }
    }

    /** The first line the stash can no longer cover for what the order would reserve (item and shulker refs), else null. */
    private static OrderException shortage(Order o, IndexStore.OrderCounts counts, Map<String, Long> reservedByOthers) {
        Map<String, Long> need = new HashMap<>();
        addReservations(o, need);
        for (Map.Entry<String, Long> e : need.entrySet()) {
            String ref = e.getKey();
            int colon = ref.indexOf(':');
            String type = ref.substring(0, colon);
            if (!type.equals("item") && !type.equals("shulker")) continue; // ready:<template> and the like
            String key = ref.substring(colon + 1);
            long total = counts.total(type, key);
            long free = total < 0 ? 0 : Math.max(0, total - reservedByOthers.getOrDefault(ref, 0L));
            if (e.getValue() > free) return new OrderException(400, "the stash no longer holds enough of " + key + " for this order", -1);
        }
        return null;
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
            if (l.type.equals("enchant")) {
                slots += l.count; // each finished item takes a slot of its own (ENCHANT-CONTRACT v1.0)
            } else if (l.type.equals("craft")) {
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

    private static JsonObject holdJson(List<String> curse) {
        JsonObject hold = new JsonObject();
        hold.addProperty("reason", "curse");
        JsonArray list = new JsonArray();
        curse.forEach(list::add);
        hold.add("curse", list);
        return hold;
    }

    private static List<String> holdCurse(Order o) {
        List<String> out = new ArrayList<>();
        if (o.hold != null && o.hold.has("curse") && o.hold.get("curse").isJsonArray()) {
            for (JsonElement e : o.hold.getAsJsonArray("curse")) out.add(e.getAsString());
        }
        java.util.Collections.sort(out);
        return out;
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
                if (holdActive(o)) continue; // a force-cancelled enchant order still reserves until the bot acknowledges
                it.remove();
                byOrderId.remove(o.id, b);
                removed++;
            }
            if (removed > 0) save(b);
            return removed;
        }
    }

    /** pending or awaiting curse approval -> cancelled now; accepted/in_progress -> cancelRequested; finished -> 409. */
    Order cancel(String id) throws OrderException, IOException {
        Book b = byOrderId.get(id);
        if (b == null) throw new OrderException(404, "no such order", -1);
        synchronized (b) {
            Order o = find(b, id);
            if (o == null) throw new OrderException(404, "no such order", -1);
            if (FINISHED.contains(o.status)) throw new OrderException(409, "order is already " + o.status, -1);
            if (o.status.equals("pending") || o.status.equals(HELD_CURSE)) {
                o.status = "cancelled";
                o.hold = null;
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
        return forceCancel(id, false);
    }

    /**
     * The same. An enchant order (SR2-N1) needs confirm (the route's strict {@code confirm: true}): the bot may be mid-enchant with the
     * books, so the order's reservations stay until the bot acknowledges at its next step boundary (its next status call, answered 409)
     * or until 2 minutes after its heartbeat goes stale. The state check comes first; the guards before it are the route's.
     */
    Order forceCancel(String id, boolean confirmed) throws OrderException, IOException {
        Book b = byOrderId.get(id);
        if (b == null) throw new OrderException(404, "no such order", -1);
        synchronized (b) {
            Order o = find(b, id);
            if (o == null) throw new OrderException(404, "no such order", -1);
            if (FINISHED.contains(o.status)) throw new OrderException(409, "order is already " + o.status, -1);
            if (!o.cancelRequested || o.status.equals("pending") || o.status.equals(HELD_CURSE)) {
                throw new OrderException(409, "request a cancel first; force cancel is for orders the bot hasn't confirmed", -1);
            }
            boolean enchant = hasEnchant(o);
            if (enchant && !confirmed) {
                throw new OrderException(400, "confirm: true is required to force-cancel an enchant order (the bot may be mid-enchant)", -1);
            }
            o.status = "cancelled";
            o.message = "cancelled from the website (bot didn't confirm)";
            EnchantOrders eo = enchantOrders;
            if (enchant && eo != null) {
                o.forceHoldAt = eo.bots().now();
                o.holdProbe = () -> holdActive(o);
            }
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
                    if (!COUNTED.contains(o.status)) continue;
                    open++;
                    if (active == null && !o.status.equals("pending") && !o.status.equals(HELD_CURSE)) {
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

    /** Whether the bot must carry out enchant orders for this one. */
    static boolean hasEnchant(Order o) {
        for (Line l : o.lines) if (l.type.equals("enchant")) return true;
        return false;
    }

    /** Whether an order has an enchant line (false for an unknown order). */
    boolean hasEnchantLine(String id) {
        Book b = byOrderId.get(id);
        if (b == null) return false;
        synchronized (b) {
            Order o = find(b, id);
            return o != null && hasEnchant(o);
        }
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
        return heartbeatJson(server, stash, craftCapable, kitCapable, false);
    }

    /**
     * The same; with enchantCapable false the pending orders with enchant lines are not counted either. An order awaiting curse approval
     * is never counted (it is not pending).
     */
    JsonObject heartbeatJson(String server, String stash, boolean craftCapable, boolean kitCapable, boolean enchantCapable) {
        JsonObject out = new JsonObject();
        JsonArray cancel = new JsonArray();
        int pending = 0;
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b != null) {
            synchronized (b) {
                for (Order o : b.orders) {
                    if (o.status.equals("pending")) {
                        if ((craftCapable || !hasCraft(o)) && (kitCapable || !hasKit(o)) && (enchantCapable || !hasEnchant(o))) pending++;
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
        return claim(server, stash, new Claim(craftOrders, kitFlag, false, null));
    }

    static final String ENCHANT_DOWNGRADED = "the bot can no longer enchant; retry the order";

    /**
     * What a claim says its bot can do. craft and kit are the claim's flags (craftOrders / kitOrders >= 1). enchant is true only when
     * the route verified everything the contract demands for an enchant order (ENCHANT-CONTRACT v1.0, "Heartbeat, claim and
     * capability": the enchantOrders flag, a fresh enabled heartbeat block, mc equal to the index's version, and the claim's player equal
     * to both the stash owner and the heartbeat's player); player is that claim player, recorded as {@code claimedBy}.
     */
    record Claim(boolean craft, boolean kit, boolean enchant, String player) {
    }

    /** The held order if there is one, else the oldest pending one this claim may take, now accepted. Null = none. */
    Order claim(String server, String stash, Claim caps) throws IOException {
        boolean craftOrders = caps.craft();
        boolean kitFlag = caps.kit();
        boolean enchantFlag = caps.enchant();
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b == null) return null;
        synchronized (b) {
            boolean changed = false;
            long now = clock.getAsLong();
            try {
                for (Order o : b.orders) {
                    if (!(o.status.equals("accepted") || o.status.equals("in_progress"))) continue;
                    // An enchant order is only ever handed back to the player that claimed it.
                    if (hasEnchant(o) && enchantFlag && o.claimedBy != null && !o.claimedBy.equalsIgnoreCase(caps.player())) continue;
                    boolean needCraft = hasCraft(o) && !craftOrders;
                    boolean needKit = hasKit(o) && !kitFlag;
                    boolean needEnchant = hasEnchant(o) && !enchantFlag;
                    if (!needCraft && !needKit && !needEnchant) {
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
                    EnchantOrders eo = enchantOrders;
                    long window = now - o.flaglessSince;
                    boolean alive = (!needCraft || (cs != null && cs.capableWithin(server, stash, window)))
                        && (!needKit || (ko != null && ko.capableWithin(server, stash, window)))
                        && (!needEnchant || (eo != null && eo.bots().enabledWithin(server, stash, window)));
                    boolean expired = o.flaglessClaims >= 3 && window > DOWNGRADE_GRACE_MS && now - startedAt > DOWNGRADE_GRACE_MS
                        && (!alive || window > DOWNGRADE_ABSOLUTE_MS);
                    // A claim every 11 minutes would restart the episode each time: the third such claim fails it.
                    if (o.flaglessEpisodes >= 2 && now - startedAt > DOWNGRADE_GRACE_MS) expired = true;
                    if (!expired) return null;
                    o.status = "failed";
                    o.message = needEnchant && !needCraft && !needKit ? ENCHANT_DOWNGRADED : needKit && !needCraft ? KIT_DOWNGRADED : DOWNGRADED;
                    o.updatedAt = System.currentTimeMillis();
                    changed = true;
                }
            } finally {
                if (changed) save(b);
            }
            for (Order o : b.orders) {
                if (o.status.equals("pending") && (craftOrders || !hasCraft(o)) && (kitFlag || !hasKit(o)) && (enchantFlag || !hasEnchant(o))) {
                    o.status = "accepted";
                    if (hasEnchant(o)) o.claimedBy = caps.player(); // status calls must come from this player
                    o.updatedAt = System.currentTimeMillis();
                    save(b);
                    return o;
                }
            }
            return null;
        }
    }

    /** A status report from the bot, without the claim's player (an order claimed by a player can't be reported on this way). */
    Order status(String id, String status, String phase, JsonObject progress, String message, JsonObject result)
        throws OrderException, IOException {
        return status(id, status, phase, progress, message, result, null);
    }

    /**
     * A status report from the bot. Fields are validated by the route; the move is checked here. An order with a claimedBy (an enchant
     * order) accepts a report only from that player (409 otherwise, nothing changes). A report on a force-cancelled enchant order is the
     * bot's acknowledgement: its held reservations are released, and it is answered 409 like any report on a finished order.
     */
    Order status(String id, String status, String phase, JsonObject progress, String message, JsonObject result, String player)
        throws OrderException, IOException {
        Book b = byOrderId.get(id);
        if (b == null) throw new OrderException(404, "no such order", -1);
        synchronized (b) {
            Order o = find(b, id);
            if (o == null) throw new OrderException(404, "no such order", -1);
            if (o.claimedBy != null && (player == null || !o.claimedBy.equalsIgnoreCase(player))) {
                throw new OrderException(409, "this order was claimed by another bot", -1);
            }
            if (o.forceHoldAt > 0 && o.status.equals("cancelled")) {
                o.forceHoldAt = 0;
                o.updatedAt = System.currentTimeMillis();
                save(b);
            }
            if (!allowed(o.status, status)) {
                throw new OrderException(409, "can't go from " + o.status + " to " + status, -1);
            }
            if (result != null) EnchantResults.crossCheck(o, result, enchantOrders == null ? RepairStore.DEFAULT_RESERVE : enchantOrders.reserve(o.server, o.stash));
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
