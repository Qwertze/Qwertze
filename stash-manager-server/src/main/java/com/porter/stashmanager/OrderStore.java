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
    static final int MAX_NOTE = 200;
    static final int MAX_PHASE = 100;
    static final int MAX_MESSAGE = 300;
    /** The most shulkers one order may need: one double chest (the addon's Order.MAX_BINS). */
    static final int MAX_SHULKERS = 54;
    static final int SHULKER_SLOTS = 27;
    static final Set<String> OPEN = Set.of("pending", "accepted", "in_progress");
    static final Set<String> FINISHED = Set.of("delivered", "failed", "cancelled");
    private static final SecureRandom RANDOM = new SecureRandom();

    record Line(String type, String key, long count) {
        String ref() {
            return type + ":" + key;
        }
    }

    /** A rejected request: HTTP status, message, and the index of the offending line (or -1). */
    static final class OrderException extends Exception {
        final int status;
        final int line;

        OrderException(int status, String message, int line) {
            super(message);
            this.status = status;
            this.line = line;
        }
    }

    static final class Order {
        String id;
        String server;
        String stash;
        List<Line> lines;
        String note;
        long createdAt;
        long updatedAt;
        String status;
        String phase;
        JsonObject progress;
        String message;
        boolean cancelRequested;
        long cancelRequestedAt; // 0 when unknown (orders from before this field)
        JsonObject result;

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
                ls.add(lo);
            }
            o.add("lines", ls);
            o.addProperty("note", note);
            o.addProperty("createdAt", createdAt);
            o.addProperty("updatedAt", updatedAt);
            o.addProperty("status", status);
            o.addProperty("phase", phase);
            o.add("progress", progress == null ? null : progress.deepCopy());
            o.addProperty("message", message);
            o.addProperty("cancelRequested", cancelRequested);
            o.addProperty("cancelRequestedAt", cancelRequestedAt > 0 ? cancelRequestedAt : null);
            o.add("result", result == null ? null : result.deepCopy());
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
                r.lines.add(new Line(l.get("type").getAsString(), l.get("key").getAsString(), l.get("count").getAsLong()));
            }
            r.note = str(o, "note");
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

        Book(String server, String stash) {
            this.server = server;
            this.stash = stash;
        }
    }

    private final Path dir;
    private final IndexStore index;
    private final Map<IndexStore.StashKey, Book> books = new ConcurrentHashMap<>();
    private final Map<String, Book> byOrderId = new ConcurrentHashMap<>();

    OrderStore(Path dataDir, IndexStore index) {
        this.dir = dataDir.resolve("orders");
        this.index = index;
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
        }
        return out;
    }

    // ---------- browser ----------

    /** Validates (against the current index and open orders), merges duplicate lines, stores and returns the order. */
    Order place(String server, String stash, List<Line> requested, String note) throws OrderException, IOException {
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
                merged.put(l.ref(), new Line(l.type, l.key, prev.count + l.count));
            }
        }

        Book b = book(server, stash);
        synchronized (b) {
            long open = b.orders.stream().filter(o -> OPEN.contains(o.status)).count();
            if (open >= MAX_OPEN) throw new OrderException(400, "too many open orders for this stash (limit " + MAX_OPEN + ")", -1);
            Map<String, Long> reserved = reserved(b);
            for (Line l : merged.values()) {
                int at = firstIndex.get(l.ref());
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
            o.note = note == null ? "" : note;
            o.createdAt = now;
            o.updatedAt = now;
            o.status = "pending";
            b.orders.add(o);
            byOrderId.put(o.id, b);
            prune(b);
            save(b);
            return o;
        }
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
            if (l.type.equals("shulker") || counts.isShulkerBoxKey(l.key)) {
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

    /** { pending: n, cancel: [ids of held orders with a cancel requested] } for the heartbeat reply. */
    JsonObject heartbeatJson(String server, String stash) {
        JsonObject out = new JsonObject();
        JsonArray cancel = new JsonArray();
        int pending = 0;
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b != null) {
            synchronized (b) {
                for (Order o : b.orders) {
                    if (o.status.equals("pending")) pending++;
                    else if (o.cancelRequested && (o.status.equals("accepted") || o.status.equals("in_progress"))) cancel.add(o.id);
                }
            }
        }
        out.addProperty("pending", pending);
        out.add("cancel", cancel);
        return out;
    }

    /** The held (accepted/in_progress) order if there is one, else the oldest pending one, now accepted. Null = none. */
    Order claim(String server, String stash) throws IOException {
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b == null) return null;
        synchronized (b) {
            for (Order o : b.orders) {
                if (o.status.equals("accepted") || o.status.equals("in_progress")) return o;
            }
            for (Order o : b.orders) {
                if (o.status.equals("pending")) {
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
