package com.porter.qwerte.manager.local;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.manager.local.LocalStore.Entry;
import com.porter.qwerte.manager.local.LocalStore.Slot;
import com.porter.qwerte.manager.model.IndexModel;
import com.porter.qwerte.stash.Kits;
import com.porter.qwerte.stash.StashIndex;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The local index of the server the player is on: owns the {@link LocalStore}, reads and writes its file off the client
 * thread, and builds the {@link IndexModel} the window browses. This is the only class (with {@link LocalCapture}) that
 * reads the game world for the window. Client thread unless noted.
 *
 * <p>Memory: stored containers are not kept as decoded stacks. Each one is summarised once (item key to count, filled
 * shulker kind to count) and the stacks are dropped; the index is the sum of the summaries, and "where" reads them. The
 * work of summarising containers loaded from disk is spread over frames with a time budget.
 */
public final class LocalHub {
    private static final int SAVE_DELAY_TICKS = 100;
    /** A change that has waited this long is written even if changes keep coming. */
    private static final int MAX_UNSAVED_TICKS = 1200;
    private static final int MAX_DEPTH = 4;
    private static final int WHERE_MAX = 50;

    /** The browsable result: the model plus a real stack for each key to draw and inspect. */
    public record Built(IndexModel index, Map<String, ItemStack> samples) {}

    /** What one container holds: index key to count, for items and for filled shulker kinds. */
    private record Summary(Map<String, Long> items, Map<String, Long> shulkers) {}

    private final IntSupplier cap;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qwerte-local-index-io");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger inflight = new AtomicInteger();
    private final IdentityHashMap<Entry, Summary> summaries = new IdentityHashMap<>();
    // The first-seen JSON of each item and shulker kind (without its count), and a real stack of each key
    private final Map<String, JsonObject> itemTemplates = new HashMap<>();
    private final Map<String, JsonObject> shulkerTemplates = new HashMap<>();
    private final Map<String, ItemStack> samples = new HashMap<>();

    private LocalStore store;
    private SaveBackoff backoff = new SaveBackoff();
    private long ticks;
    private long changedAtTick;
    private long unsavedSince = -1;
    private int seenVersion = -1;
    private volatile String loadWarning = "";
    private volatile String saveWarning = "";
    private boolean decodeWarned;
    private boolean encodeWarned;

    public LocalHub(IntSupplier cap) {
        this.cap = cap;
    }

    private static Path folder() {
        return MeteorClient.FOLDER.toPath().resolve("qwerte").resolve("local-index");
    }

    /** A problem to show in the window (a file that could not be read or written), or "". */
    public String warning() {
        String save = saveWarning;
        return save.isEmpty() ? loadWarning : save;
    }

    public int cap() {
        return Math.max(LocalStore.MIN_CAP, Math.min(LocalStore.DEFAULT_CAP, cap.getAsInt()));
    }

    // ---------- the store of the current server ----------

    /**
     * The store for the world the game is in now, loading its file in the background the first time. Null with no world
     * or while the world's identity isn't known yet.
     */
    public LocalStore store() {
        if (mc.level == null) return null;
        String server = ServerId.current();
        if (server == null) return null;
        if (store == null || !store.server().equals(server)) {
            if (store != null) save(store, true);
            store = new LocalStore(server);
            summaries.clear();
            itemTemplates.clear();
            shulkerTemplates.clear();
            samples.clear();
            backoff = new SaveBackoff();
            seenVersion = -1;
            unsavedSince = -1;
            loadWarning = "";
            saveWarning = "";
            load(store);
        }
        return store;
    }

    /** The store of the last world, even after leaving it (for flushing). */
    public LocalStore current() {
        return store;
    }

    private void load(LocalStore target) {
        Path folder = folder();
        String server = target.server();
        Path file = LocalFile.fileFor(folder, server);
        int limit = cap();
        io.execute(() -> {
            LocalFile.Loaded loaded;
            try {
                LocalFile.migrate(folder, server);
                loaded = LocalFile.read(file, server, limit);
            } catch (Throwable t) {
                // Anything unexpected: the file is left alone and the store still becomes ready
                loaded = new LocalFile.Loaded(LocalFile.Status.UNREADABLE, List.of(), 0);
            }
            LocalFile.Loaded result = loaded;
            mc.execute(() -> apply(target, file, result, limit));
        });
    }

    private void apply(LocalStore target, Path file, LocalFile.Loaded loaded, int limit) {
        List<Entry> entries = List.of();
        try {
            switch (loaded.status()) {
                case UNREADABLE -> {
                    target.blockSaving();
                    loadWarning = "The local index file can't be read; nothing will be saved for this server until it can.";
                    QwerteAddon.LOG.warn("stash-window: couldn't read {}", file.getFileName());
                }
                case TOO_BIG -> {
                    target.blockSaving();
                    loadWarning = "The local index file is too big to read; it was left alone and nothing will be saved for this server.";
                    QwerteAddon.LOG.warn("stash-window: {} is over the size limit, left alone", file.getFileName());
                }
                case NEWER -> {
                    target.blockSaving();
                    entries = loaded.entries();
                    loadWarning = "The local index was written by a newer version; it is shown but not changed on disk.";
                    QwerteAddon.LOG.warn("stash-window: {} is from a newer version, read-only", file.getFileName());
                }
                case CORRUPT -> {
                    loadWarning = "The saved local index was damaged and was set aside; starting over.";
                    QwerteAddon.LOG.warn("stash-window: {} was damaged, moved aside", file.getFileName());
                }
                default -> {
                    entries = loaded.entries();
                    if (loaded.skipped() > 0) QwerteAddon.LOG.warn("stash-window: {} entries of {} were skipped", loaded.skipped(), file.getFileName());
                }
            }
        } catch (Throwable t) {
            target.blockSaving();
            entries = List.of();
        } finally {
            target.mergeLoaded(entries, limit);
        }
    }

    // ---------- saving ----------

    /** Called every client tick: writes the file a few seconds after the last change, or a minute after the first. */
    public void tick() {
        ticks++;
        LocalStore s = store;
        if (s == null) return;
        if (s.version() != seenVersion) {
            seenVersion = s.version();
            changedAtTick = ticks;
        }
        if (!s.needsSave()) {
            unsavedSince = -1;
            return;
        }
        if (unsavedSince < 0) unsavedSince = ticks;
        boolean due = ticks - changedAtTick >= SAVE_DELAY_TICKS || ticks - unsavedSince >= MAX_UNSAVED_TICKS;
        if (due && inflight.get() == 0 && backoff.allowed(ticks, s.size())) save(s, false);
    }

    /** Writes the current store now (in the background), if it changed. Used when leaving a world and turning off. */
    public void save() {
        save(store, true);
    }

    /**
     * Queues a write of {@code s}. A forced one is always queued, behind any write already running, so the last state
     * is never dropped (the writes run one after another on one thread, each with the state it was queued with).
     */
    private void save(LocalStore s, boolean force) {
        if (s == null || !s.needsSave()) return;
        if (!force && inflight.get() > 0) return;
        inflight.incrementAndGet();
        List<Entry> entries = s.snapshot();
        int version = s.version();
        int count = entries.size();
        Path file = LocalFile.fileFor(folder(), s.server());
        String server = s.server();
        io.execute(() -> {
            LocalFile.WriteStatus status;
            try {
                status = LocalFile.write(file, LocalFile.toTag(server, entries));
            } catch (Throwable e) {
                status = LocalFile.WriteStatus.ERROR;
            }
            LocalFile.WriteStatus result = status;
            mc.execute(() -> {
                inflight.decrementAndGet();
                if (result == LocalFile.WriteStatus.OK) {
                    s.markSaved(version);
                    if (s == store) {
                        backoff.succeeded();
                        saveWarning = "";
                    }
                    return;
                }
                boolean tooBig = result == LocalFile.WriteStatus.TOO_BIG;
                if (s != store) {
                    QwerteAddon.LOG.warn("stash-window: couldn't write the local index of {}", server);
                    return;
                }
                boolean first = backoff.failed(ticks, count, tooBig);
                if (tooBig) saveWarning = "The local index is too big to save. Forget some containers; saving resumes when there are fewer.";
                if (first) {
                    if (tooBig) QwerteAddon.LOG.warn("stash-window: the local index is too big to save, not saving until it shrinks");
                    else QwerteAddon.LOG.warn("stash-window: couldn't write the local index, trying again later");
                }
            });
        });
    }

    // ---------- changes ----------

    /**
     * Remembers a container's contents in {@code s}, the store that was current when the container was opened.
     * {@code stacks} are copies this class may keep.
     */
    public void record(LocalStore s, ContainerKey key, List<int[]> parts, String block, int size, ItemStack[] stacks) {
        if (s == null || mc.level == null) return;
        RegistryOps<Tag> ops = mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < stacks.length && i < size; i++) {
            if (stacks[i].isEmpty()) continue;
            int slot = i;
            var encoded = ItemStack.CODEC.encodeStart(ops, stacks[i]).result();
            if (encoded.isPresent()) {
                slots.add(new Slot(slot, encoded.get()));
            } else {
                // Kept as the item and its count only, so the counts survive a restart
                net.minecraft.nbt.CompoundTag plain = new net.minecraft.nbt.CompoundTag();
                plain.putString("id", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stacks[i].getItem()).toString());
                plain.putInt("count", stacks[i].getCount());
                slots.add(new Slot(slot, plain));
                if (!encodeWarned) {
                    encodeWarned = true;
                    QwerteAddon.LOG.warn("stash-window: an item could not be fully saved to the local index ({}); only its id and count are kept", stacks[i].getItem());
                }
            }
        }
        Entry e = new Entry(key, List.copyOf(parts), block, size, System.currentTimeMillis(), List.copyOf(slots));
        Entry old = s.get(key);
        if (s.put(e, cap())) {
            if (old != null) summaries.remove(old);
            if (s == store) {
                Summary summary = summarize(stacks);
                if (summary != null) summaries.put(e, summary);
            }
        }
    }

    public boolean forget(ContainerKey key) {
        LocalStore s = store;
        if (s == null) return false;
        Entry old = s.get(key);
        boolean gone = s.remove(key);
        if (old != null) summaries.remove(old);
        return gone;
    }

    /** Forgets every container of this server and deletes its file. */
    public void clearAll() {
        LocalStore s = store();
        if (s == null) return;
        s.clear();
        summaries.clear();
        Path file = LocalFile.fileFor(folder(), s.server());
        // Behind any save already queued, so the file is gone afterwards
        io.execute(() -> LocalFile.delete(file));
        s.markSaved(s.version());
        loadWarning = "";
        saveWarning = "";
    }

    // ---------- reading back ----------

    private RegistryOps<Tag> ops() {
        RegistryAccess access = mc.level != null ? mc.level.registryAccess() : null;
        return access == null ? null : access.createSerializationContext(NbtOps.INSTANCE);
    }

    private ItemStack[] decode(RegistryOps<Tag> ops, Entry e) {
        ItemStack[] out = new ItemStack[e.size()];
        java.util.Arrays.fill(out, ItemStack.EMPTY);
        for (Slot s : e.slots()) {
            try {
                ItemStack.CODEC.parse(ops, s.item()).result().ifPresent(stack -> out[s.slot()] = stack);
            } catch (RuntimeException ex) {
                if (!decodeWarned) {
                    decodeWarned = true;
                    QwerteAddon.LOG.warn("stash-window: a stored item could not be read: {}", ex.toString());
                }
            }
        }
        return out;
    }

    /** Works out what one container holds from its stacks; the stacks are not kept. Null with no world. */
    private Summary summarize(ItemStack[] stacks) {
        List<ItemStack> all = new ArrayList<>();
        for (ItemStack s : stacks) if (!s.isEmpty()) all.add(s);
        Map<String, Long> items = new HashMap<>();
        Map<String, Long> shulkers = new HashMap<>();
        if (!all.isEmpty()) {
            JsonObject o = StashIndex.aggregate(all);
            if (o == null) return null;
            read(o.getAsJsonArray("items"), items, itemTemplates);
            read(o.getAsJsonArray("shulkers"), shulkers, shulkerTemplates);
            for (ItemStack s : all) sample(s, 0);
        }
        return new Summary(items, shulkers);
    }

    private static void read(JsonArray array, Map<String, Long> counts, Map<String, JsonObject> templates) {
        if (array == null) return;
        for (JsonElement el : array) {
            JsonObject o = el.getAsJsonObject();
            String key = o.get("key").getAsString();
            counts.merge(key, o.get("count").getAsLong(), Long::sum);
            templates.putIfAbsent(key, o);
        }
    }

    private void sample(ItemStack s, int depth) {
        if (s == null || s.isEmpty()) return;
        String key = StashIndex.keyOf(s);
        if (key != null) samples.putIfAbsent(key, s.copyWithCount(1));
        if (depth == 0 && Utils.isShulker(s.getItem())) {
            String group = StashIndex.groupKey(s);
            if (group != null) samples.putIfAbsent(group, s.copyWithCount(1));
        }
        if (depth >= MAX_DEPTH) return;
        if (Utils.isShulker(s.getItem())) {
            for (ItemStack inner : Kits.inside(s)) sample(inner, depth + 1);
        }
        var bundle = s.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            for (ItemStack inner : bundle.items()) sample(inner, depth + 1);
        }
    }

    /**
     * Summarises containers that aren't yet, until {@code budgetNanos} have passed (at least one per call). Returns
     * how many still need it.
     */
    public int prepare(Collection<Entry> entries, long budgetNanos) {
        RegistryOps<Tag> ops = ops();
        if (ops == null) return entries.size();
        long start = System.nanoTime();
        int left = 0;
        for (Entry e : entries) {
            if (summaries.containsKey(e)) continue;
            if (System.nanoTime() - start > budgetNanos) {
                left++;
                continue;
            }
            Summary summary = summarize(decode(ops, e));
            if (summary == null) {
                left++;
                continue;
            }
            summaries.put(e, summary);
        }
        return left;
    }

    /** Drops summaries of containers that are gone. */
    public void prune(Collection<Entry> live) {
        if (summaries.size() <= live.size() + 64) return;
        Map<Entry, Boolean> keep = new IdentityHashMap<>();
        for (Entry e : live) keep.put(e, Boolean.TRUE);
        summaries.keySet().removeIf(e -> !keep.containsKey(e));
    }

    /**
     * The index of these containers in the shape the server sends, plus real stacks to draw. Only sums the summaries
     * made by {@link #prepare}. Null with no world.
     */
    public Built build(Collection<Entry> entries) {
        if (mc.level == null) return null;
        Map<String, Long> items = new TreeMap<>();
        Map<String, Long> shulkers = new TreeMap<>();
        int known = 0;
        for (Entry e : entries) {
            Summary sm = summaries.get(e);
            if (sm == null) continue;
            known++;
            sm.items().forEach((k, v) -> items.merge(k, v, Long::sum));
            sm.shulkers().forEach((k, v) -> shulkers.merge(k, v, Long::sum));
        }
        JsonObject o = new JsonObject();
        o.add("items", emit(items, itemTemplates));
        o.add("shulkers", emit(shulkers, shulkerTemplates));
        JsonObject containers = new JsonObject();
        containers.addProperty("known", known);
        containers.addProperty("total", entries.size());
        containers.addProperty("stale", 0);
        o.add("containers", containers);
        o.addProperty("receivedAt", System.currentTimeMillis());
        return new Built(IndexModel.parse(o), Collections.unmodifiableMap(samples));
    }

    private static JsonArray emit(Map<String, Long> counts, Map<String, JsonObject> templates) {
        JsonArray out = new JsonArray();
        for (Map.Entry<String, Long> c : counts.entrySet()) {
            JsonObject t = templates.get(c.getKey());
            if (t == null) continue;
            JsonObject copy = t.deepCopy();
            copy.addProperty("count", c.getValue());
            out.add(copy);
        }
        return out;
    }

    /** The containers among {@code entries} that hold {@code key}, most first, with their distance from the player. */
    public List<Where> where(Collection<Entry> entries, String key) {
        List<Where> hits = new ArrayList<>();
        String dimension = com.porter.qwerte.stash.StashIndex.dimension();
        for (Entry e : entries) {
            Summary sm = summaries.get(e);
            if (sm == null) continue;
            long n = sm.items().getOrDefault(key, sm.shulkers().getOrDefault(key, 0L));
            if (n <= 0) continue;
            int distance = -1;
            if (mc.player != null && e.key().dimension().equals(dimension)) {
                double dx = mc.player.getX() - e.key().x(), dy = mc.player.getY() - e.key().y(), dz = mc.player.getZ() - e.key().z();
                distance = (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
            hits.add(new Where(e.key(), e.block(), n, distance));
        }
        hits.sort(Comparator.comparingLong(Where::count).reversed());
        return hits.size() > WHERE_MAX ? new ArrayList<>(hits.subList(0, WHERE_MAX)) : hits;
    }
}
