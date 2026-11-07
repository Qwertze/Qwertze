package com.porter.qwerte.manager.local;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.manager.model.OrderModel;
import com.porter.qwerte.manager.model.OrderModel.Entry;
import com.porter.qwerte.stash.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The history of the orders this client carried out itself, one JSON file per game server. Bounded: at most
 * {@link #MAX_ORDERS} orders and {@link #MAX_BYTES} bytes, and the same byte limit applies to what is written and to what is
 * read, so a file this class wrote is always read back. Written to a temp file and moved into place; a file that cannot be
 * parsed is set aside (name.bad-time) and the history starts empty. A failed write is logged once, then tried again after a
 * growing wait. Orders that were still open when the game stopped are marked failed when the file is loaded. Thread safe;
 * the file is written on a daemon thread.
 */
public final class LocalOrderStore {
    public static final int VERSION = 1;
    public static final int MAX_ORDERS = 100;
    public static final int MAX_BYTES = 1024 * 1024;
    private static final long BACKOFF_MIN_MS = 30_000, BACKOFF_MAX_MS = 600_000;
    private static final Logger LOG = LoggerFactory.getLogger("Qwerte/stash-window");

    /** An order and the stash (by name) it was for. */
    public record Item(String stash, Entry entry) {
    }

    private final Path folder;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qwerte-local-orders-io");
        t.setDaemon(true);
        return t;
    });
    private final Object lock = new Object();
    private final List<Item> items = new ArrayList<>();
    private static final int MAX_PENDING = 4;
    private static final int KEEP_BAD = 3;
    private final Map<String, List<Item>> pending = new LinkedHashMap<>();
    private String server;
    private boolean dirty;
    private boolean loggedFailure;
    private long backoff, retryAt;
    private volatile String warning = "";

    public LocalOrderStore(Path folder) {
        this.folder = folder;
    }

    /** The same readable-plus-hash name as the local index files, so two addresses never share a history. */
    public static Path fileFor(Path folder, String server) {
        return folder.resolve(ContainerKey.fileName(server) + ".json");
    }

    /** The name files had before the hash was added to it. */
    static Path legacyFileFor(Path folder, String server) {
        return folder.resolve(ContainerKey.safeFileName(server) + ".json");
    }

    /** Renames a file with the old name to the new one, if only the old one exists. */
    static void migrate(Path folder, String server) {
        Path now = fileFor(folder, server);
        Path old = legacyFileFor(folder, server);
        try {
            if (!Files.exists(now) && Files.exists(old)) Files.move(old, now);
        } catch (IOException | RuntimeException e) {
            // the old file is simply not used
        }
    }

    /** A problem to show (the file could not be written or was set aside), or "". */
    public String warning() {
        return warning;
    }

    // ---------- reading ----------

    /** Loads the history of a server (once; asking again for the same server does nothing). */
    public void load(String forServer) {
        synchronized (lock) {
            if (forServer == null || forServer.equals(server)) return;
            if (server != null && dirty) {
                // One last try now; what still can't be written is kept in memory for that server until it can
                flushLocked(true);
                if (dirty) {
                    pending.put(server, new ArrayList<>(items));
                    while (pending.size() > MAX_PENDING) pending.remove(pending.keySet().iterator().next());
                }
            }
            server = forServer;
            items.clear();
            dirty = false;
            List<Item> held = pending.remove(forServer);
            if (held != null) {
                items.addAll(held);
                dirty = true;
                return;
            }
            migrate(folder, forServer);
            Path file = fileFor(folder, forServer);
            if (!Files.exists(file)) return;
            try {
                if (Files.size(file) > MAX_BYTES) throw new IOException("too big");
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                List<Item> read = decode(text);
                if (read == null) throw new IOException("not a history");
                long now = System.currentTimeMillis();
                for (Item i : read) {
                    Entry e = i.entry();
                    if (e.open()) {
                        e = withEnd(e, OrderModel.FAILED, "interrupted: the game was closed", now);
                        dirty = true;
                    }
                    items.add(new Item(i.stash(), e));
                }
            } catch (Throwable t) {
                setAside(file);
                warning = "The saved local order history could not be read and was set aside.";
                LOG.warn("stash-window: local order history unreadable ({})", t.toString());
            }
        }
    }

    /** The orders of a stash, newest first. */
    public List<Entry> list(String stash) {
        synchronized (lock) {
            List<Entry> out = new ArrayList<>();
            for (Item i : items) if (i.stash().equals(stash)) out.add(i.entry());
            out.sort(Comparator.comparingLong(Entry::createdAt).reversed());
            return out;
        }
    }

    // ---------- changing ----------

    /** Adds an order or replaces the one with the same id. Does not write until {@link #save()} or {@link #flush()}. */
    public void put(String stash, Entry entry) {
        synchronized (lock) {
            boolean replaced = false;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).entry().id().equals(entry.id()) && items.get(i).stash().equals(stash)) {
                    items.set(i, new Item(stash, entry));
                    replaced = true;
                    break;
                }
            }
            if (!replaced) items.add(new Item(stash, entry));
            prune(items);
            dirty = true;
        }
    }

    /** Removes the finished orders of a stash. @return how many */
    public int removeFinished(String stash) {
        synchronized (lock) {
            int before = items.size();
            items.removeIf(i -> i.stash().equals(stash) && i.entry().finished());
            if (items.size() != before) dirty = true;
            return before - items.size();
        }
    }

    /** Drops the oldest finished orders beyond the cap; open orders are never dropped. */
    static void prune(List<Item> list) {
        while (list.size() > MAX_ORDERS) {
            int oldest = -1;
            for (int i = 0; i < list.size(); i++) {
                if (!list.get(i).entry().finished()) continue;
                if (oldest < 0 || list.get(i).entry().createdAt() < list.get(oldest).entry().createdAt()) oldest = i;
            }
            if (oldest < 0) return;
            list.remove(oldest);
        }
    }

    public static Entry withRetried(Entry e, String newId) {
        return new Entry(e.id(), e.status(), e.phase(), e.message(), e.done(), e.total(), e.cancelRequested(), e.cancelRequestedAt(), e.updatedAt(), e.createdAt(),
            e.hasPlacedBy(), e.placedById(), e.placedByName(), newId, e.lines(), e.namesMode(), e.namesAll(), e.namesEach(), e.shulkers(), e.hasResult());
    }

    public static Entry withEnd(Entry e, String status, String message, long now) {
        return new Entry(e.id(), status, status, message, e.done(), e.total(), e.cancelRequested(), e.cancelRequestedAt(), now, e.createdAt(),
            e.hasPlacedBy(), e.placedById(), e.placedByName(), e.retriedAs(), e.lines(), e.namesMode(), e.namesAll(), e.namesEach(),
            e.shulkers(), e.hasResult());
    }

    // ---------- writing ----------

    /** Writes soon, off the calling thread. */
    public void save() {
        io.execute(() -> {
            try {
                synchronized (lock) {
                    flushLocked(false);
                }
            } catch (Throwable t) {
                failed(t);
            }
        });
    }

    /** Writes now if anything changed (for leaving the game or turning the module off). */
    public void flush() {
        synchronized (lock) {
            flushLocked(false);
        }
    }

    /** @param force try even while waiting after a failure */
    private void flushLocked(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now < retryAt) return;
        // The history of a server left earlier that could not be written then
        for (Iterator<Map.Entry<String, List<Item>>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, List<Item>> p = it.next();
            try {
                String text = encodeCapped(p.getValue());
                if (text != null) write(fileFor(folder, p.getKey()), text);
                it.remove();
            } catch (Throwable t) {
                failed(t);
                return;
            }
        }
        if (!dirty || server == null) return;
        try {
            List<Item> copy = new ArrayList<>(items);
            String text = encodeCapped(copy);
            if (text == null) throw new IOException("history over " + MAX_BYTES + " bytes");
            write(fileFor(folder, server), text);
            dirty = false;
            backoff = 0;
            retryAt = 0;
            loggedFailure = false;
            warning = "";
        } catch (Throwable t) {
            failed(t);
        }
    }

    private void failed(Throwable t) {
        backoff = backoff == 0 ? BACKOFF_MIN_MS : Math.min(BACKOFF_MAX_MS, backoff * 2);
        retryAt = System.currentTimeMillis() + backoff;
        warning = "The local order history could not be saved.";
        if (!loggedFailure) {
            loggedFailure = true;
            LOG.warn("stash-window: could not save the local order history ({}); trying again later", t.toString());
        }
    }

    private static void write(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(temp, text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // a leftover temp file is overwritten next time
            }
        }
    }

    private static void setAside(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
            // Only the last few are kept
            String prefix = file.getFileName() + ".bad-";
            try (var files = Files.list(file.getParent())) {
                List<Path> old = files.filter(f -> f.getFileName().toString().startsWith(prefix)).sorted().toList();
                for (int i = 0; i < old.size() - KEEP_BAD; i++) Files.deleteIfExists(old.get(i));
            }
        } catch (Throwable ignored) {
            // the file is overwritten by the next save
        }
    }

    // ---------- the JSON shape ----------

    /** The text of the history, or null when even the open orders alone are over the limit. Drops the oldest finished ones to fit. */
    static String encodeCapped(List<Item> list) {
        List<Item> work = new ArrayList<>(list);
        while (true) {
            String text = encode(work);
            if (text.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES) return text;
            int oldest = -1;
            for (int i = 0; i < work.size(); i++) {
                if (!work.get(i).entry().finished()) continue;
                if (oldest < 0 || work.get(i).entry().createdAt() < work.get(oldest).entry().createdAt()) oldest = i;
            }
            if (oldest < 0) return null;
            work.remove(oldest);
        }
    }

    static String encode(List<Item> list) {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonArray array = new JsonArray();
        for (Item i : list) {
            Entry e = i.entry();
            JsonObject o = new JsonObject();
            o.addProperty("stash", i.stash());
            o.addProperty("id", e.id());
            o.addProperty("status", e.status());
            o.addProperty("phase", e.phase());
            o.addProperty("message", e.message());
            o.addProperty("done", e.done());
            o.addProperty("total", e.total());
            o.addProperty("cancelRequested", e.cancelRequested());
            o.addProperty("updatedAt", e.updatedAt());
            o.addProperty("createdAt", e.createdAt());
            o.addProperty("retriedAs", e.retriedAs());
            o.addProperty("shulkers", e.shulkers());
            o.addProperty("hasResult", e.hasResult());
            o.addProperty("namesMode", e.namesMode());
            o.addProperty("namesAll", e.namesAll());
            JsonArray each = new JsonArray();
            for (String s : e.namesEach()) each.add(s);
            o.add("namesEach", each);
            JsonArray lines = new JsonArray();
            for (Order.Line l : e.lines()) {
                JsonObject lo = new JsonObject();
                lo.addProperty("type", l.type());
                lo.addProperty("key", l.key());
                lo.addProperty("count", l.count());
                lines.add(lo);
            }
            o.add("lines", lines);
            array.add(o);
        }
        root.add("orders", array);
        return root.toString();
    }

    /** Null when the text is not a history. Orders that cannot be read are left out. */
    static List<Item> decode(String text) {
        try {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("orders") || !root.getAsJsonObject().get("orders").isJsonArray()) return null;
            List<Item> out = new ArrayList<>();
            for (JsonElement e : root.getAsJsonObject().getAsJsonArray("orders")) {
                if (!e.isJsonObject() || out.size() >= MAX_ORDERS) continue;
                JsonObject o = e.getAsJsonObject();
                // The same reader as the server's orders: the stored object has the same fields
                JsonObject asServer = o.deepCopy();
                JsonObject progress = new JsonObject();
                progress.addProperty("done", num(o, "done"));
                progress.addProperty("total", num(o, "total"));
                asServer.add("progress", progress);
                if (o.has("hasResult") && o.get("hasResult").getAsBoolean()) {
                    JsonObject result = new JsonObject();
                    result.addProperty("shulkers", num(o, "shulkers"));
                    asServer.add("result", result);
                }
                String mode = o.has("namesMode") ? o.get("namesMode").getAsString() : "";
                if (!mode.isEmpty()) {
                    JsonObject names = new JsonObject();
                    names.addProperty("mode", mode);
                    names.addProperty("name", o.has("namesAll") ? o.get("namesAll").getAsString() : "");
                    if (o.has("namesEach")) names.add("names", o.get("namesEach"));
                    asServer.add("names", names);
                }
                Entry entry = OrderModel.parseEntry(asServer);
                String stash = o.has("stash") ? o.get("stash").getAsString() : "";
                if (entry != null && !stash.isEmpty()) out.add(new Item(stash, entry));
            }
            return out;
        } catch (RuntimeException | StackOverflowError ex) {
            return null;
        }
    }

    private static long num(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : 0;
    }
}
