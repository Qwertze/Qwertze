package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Repair-pass settings per (server, stash) (REPAIR-CONTRACT v2.0, "Repair settings"): on/off, automatic passes, the durability
 * threshold, the bottle reserve, the needs-repair chest and whether unnamed shulker boxes are opened. Stored in data/repair.json,
 * written atomically (owner only); the previous file is copied to repair.json.bak first. A stash entry that can't be read is kept
 * as it is and answers 503 for that stash only; when the file and its backup are both unreadable, repair settings are off and
 * nothing is written over them.
 */
final class RepairStore {
    static final int MAX_STASHES = 64;
    static final int DEFAULT_THRESHOLD = 50;
    static final int DEFAULT_RESERVE = 1728;
    static final int MAX_RESERVE = 1_000_000;

    /** One stash's settings. hash is the contract's SHA-1; rev goes up by one on every change. */
    record Settings(long rev, String hash, boolean enabled, boolean auto, int threshold, int reserve, String needsRepairChest, boolean shulkers) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("rev", rev);
            o.addProperty("hash", hash);
            o.addProperty("enabled", enabled);
            o.addProperty("auto", auto);
            o.addProperty("threshold", threshold);
            o.addProperty("reserve", reserve);
            o.addProperty("needsRepairChest", needsRepairChest);
            o.addProperty("shulkers", shulkers);
            return o;
        }
    }

    static final class DisabledException extends RuntimeException {
        DisabledException() {
            super("the repair settings file can't be read");
        }
    }

    static final class LimitException extends RuntimeException {
        LimitException(String message) {
            super(message);
        }
    }

    private record Entry(long rev, boolean enabled, boolean auto, int threshold, int reserve, String chest, boolean shulkers, long updatedAt) {
    }

    private static final Entry DEFAULT = new Entry(0, true, true, DEFAULT_THRESHOLD, DEFAULT_RESERVE, null, true, 0);

    private final Path file;
    private final Path backup;
    private final LongSupplier clock;
    private final LinkedHashMap<IndexStore.StashKey, Entry> entries = new LinkedHashMap<>();
    private boolean disabled;
    private final java.util.List<JsonObject> damaged = new java.util.ArrayList<>();
    private final java.util.Set<IndexStore.StashKey> damagedKeys = new java.util.HashSet<>();
    private boolean loadedFromBackup;

    RepairStore(Path dataDir) {
        this(dataDir, System::currentTimeMillis);
    }

    RepairStore(Path dataDir, LongSupplier clock) {
        this.file = dataDir.resolve("repair.json");
        this.backup = dataDir.resolve("repair.json.bak");
        this.clock = clock;
    }

    /**
     * The contract's hash: SHA-1 (lowercase hex) of "repair-v1\n" + enabled + "\n" + auto + "\n" + threshold + "\n" + reserve + "\n" +
     * (chest or "") + "\n" + shulkers + "\n".
     */
    static String hash(boolean enabled, boolean auto, int threshold, int reserve, String chest, boolean shulkers) {
        return KitTemplate.sha1("repair-v1\n" + enabled + "\n" + auto + "\n" + threshold + "\n" + reserve + "\n" + (chest == null ? "" : chest) + "\n"
            + shulkers + "\n");
    }

    // ---------- loading ----------

    private record Loaded(Map<IndexStore.StashKey, Entry> entries, java.util.List<JsonObject> damaged, java.util.Set<IndexStore.StashKey> damagedKeys) {
    }

    private void take(Loaded l) {
        entries.putAll(l.entries());
        damaged.addAll(l.damaged());
        damagedKeys.addAll(l.damagedKeys());
        for (int i = 0; i < l.damaged().size(); i++) {
            System.err.println("Repair settings: one stash entry in " + file.getFileName() + " can't be read and is left as it is (repair is off for that stash).");
        }
    }

    synchronized void load() {
        entries.clear();
        damaged.clear();
        damagedKeys.clear();
        disabled = false;
        loadedFromBackup = false;
        if (Files.exists(backup)) AtomicFiles.ownerOnly(backup);
        if (!Files.exists(file)) {
            if (!Files.exists(backup)) return;
            try {
                take(read(backup));
                loadedFromBackup = true;
                System.err.println("Repair settings restored from " + backup.getFileName() + " (" + file.getFileName() + " was missing).");
            } catch (IOException | RuntimeException e) {
                disabled = true;
                entries.clear();
                System.err.println("Repair settings are off: " + file.getFileName() + " is missing and its backup can't be read; it will not be overwritten.");
            }
            return;
        }
        try {
            take(read(file));
            AtomicFiles.ownerOnly(file);
            return;
        } catch (IOException | RuntimeException e) {
            System.err.println("Can't read " + file.getFileName() + " (" + e.getMessage() + "); trying the backup.");
        }
        try {
            take(read(backup));
            loadedFromBackup = true;
            System.err.println("Repair settings restored from " + backup.getFileName() + ".");
        } catch (IOException | RuntimeException e) {
            disabled = true;
            entries.clear();
            System.err.println("Repair settings are off: " + file.getFileName() + " and its backup can't be read, and neither will be overwritten.");
        }
    }

    private static Loaded read(Path path) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!(root.get("version") instanceof JsonPrimitive v) || !v.isNumber() || v.getAsInt() != 1) throw new IOException("unknown version");
        JsonArray list = root.getAsJsonArray("stashes");
        if (list.size() > MAX_STASHES) throw new IOException("too many stashes");
        Map<IndexStore.StashKey, Entry> out = new LinkedHashMap<>();
        java.util.List<JsonObject> bad = new java.util.ArrayList<>();
        java.util.Set<IndexStore.StashKey> badKeys = new java.util.HashSet<>();
        java.util.Set<IndexStore.StashKey> named = new java.util.HashSet<>();
        for (JsonElement e : list) {
            JsonObject o = e.isJsonObject() ? e.getAsJsonObject() : null;
            IndexStore.StashKey key = null;
            try {
                if (o == null) throw new IOException("not an object");
                String s = o.get("server").getAsString(), t = o.get("stash").getAsString();
                if (!KitNames.wellFormed(s) || !KitNames.wellFormed(t)) throw new IOException("bad name");
                key = new IndexStore.StashKey(s, t);
                if (!named.add(key)) throw new IOException("duplicate stash");
                out.put(key, entry(o));
            } catch (IOException ex) {
                if ("duplicate stash".equals(ex.getMessage())) throw ex;
                out.remove(key);
                bad.add(o == null ? new JsonObject() : o.deepCopy());
                if (key != null) badKeys.add(key);
            } catch (RuntimeException ex) {
                out.remove(key);
                bad.add(o == null ? new JsonObject() : o.deepCopy());
                if (key != null) badKeys.add(key);
            }
        }
        return new Loaded(out, bad, badKeys);
    }

    private static boolean bool(JsonObject o, String f) throws IOException {
        if (!(o.get(f) instanceof JsonPrimitive p) || !p.isBoolean()) throw new IOException("bad " + f);
        return p.getAsBoolean();
    }

    private static Entry entry(JsonObject o) throws IOException {
        long rev = o.get("rev").getAsBigDecimal().longValueExact();
        if (rev < 0) throw new IOException("bad rev");
        int threshold = o.get("threshold").getAsBigDecimal().intValueExact();
        int reserve = o.get("reserve").getAsBigDecimal().intValueExact();
        if (threshold < 1 || threshold > 100 || reserve < 0 || reserve > MAX_RESERVE) throw new IOException("out of range");
        String chest = o.get("needsRepairChest").isJsonNull() ? null : o.get("needsRepairChest").getAsString();
        if (chest != null && !KitNames.validId(chest)) throw new IOException("bad chest");
        boolean shulkers = !o.has("shulkers") || bool(o, "shulkers"); // files from before the setting: on
        long at = o.has("updatedAt") && o.get("updatedAt").isJsonPrimitive() ? o.get("updatedAt").getAsLong() : 0;
        return new Entry(rev, bool(o, "enabled"), bool(o, "auto"), threshold, reserve, chest, shulkers, at);
    }

    // ---------- reads ----------

    synchronized boolean disabled() {
        return disabled;
    }

    /** The stash's settings (the defaults, rev 0, when it has none). Throws DisabledException when repair settings are off. */
    synchronized Settings get(String server, String stash) {
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        if (disabled || damagedKeys.contains(k)) throw new DisabledException();
        return settings(entries.get(k));
    }

    synchronized boolean knows(String server, String stash) {
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        return entries.containsKey(k) || damagedKeys.contains(k);
    }

    private static Settings settings(Entry e) {
        Entry x = e == null ? DEFAULT : e;
        return new Settings(x.rev, hash(x.enabled, x.auto, x.threshold, x.reserve, x.chest, x.shulkers), x.enabled, x.auto, x.threshold, x.reserve, x.chest, x.shulkers);
    }

    // ---------- changes ----------

    /** Sets the four switches and numbers (shulkers null: unchanged). A change that changes nothing writes nothing. */
    synchronized Settings set(String server, String stash, boolean enabled, boolean auto, int threshold, int reserve, Boolean shulkers) throws IOException {
        if (threshold < 1 || threshold > 100) throw new LimitException("threshold must be 1 to 100");
        if (reserve < 0 || reserve > MAX_RESERVE) throw new LimitException("reserve must be 0 to " + MAX_RESERVE);
        return change(server, stash, e -> e.enabled == enabled && e.auto == auto && e.threshold == threshold && e.reserve == reserve
                && (shulkers == null || e.shulkers == shulkers),
            e -> new Entry(0, enabled, auto, threshold, reserve, e.chest, shulkers == null ? e.shulkers : shulkers, 0));
    }

    synchronized Settings setChest(String server, String stash, String container) throws IOException {
        return change(server, stash, e -> container == null ? e.chest == null : container.equals(e.chest),
            e -> new Entry(0, e.enabled, e.auto, e.threshold, e.reserve, container, e.shulkers, 0));
    }

    private Settings change(String server, String stash, java.util.function.Predicate<Entry> same, java.util.function.UnaryOperator<Entry> mutate)
        throws IOException {
        if (disabled) throw new DisabledException();
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        if (damagedKeys.contains(k)) throw new DisabledException();
        if (!KitNames.wellFormed(server) || !KitNames.wellFormed(stash)) throw new LimitException("invalid stash name");
        Entry old = entries.get(k);
        Entry base = old != null ? old : DEFAULT;
        if (same.test(base)) return settings(old);
        if (old == null && entries.size() + damaged.size() >= MAX_STASHES) throw new LimitException("repair settings exist for too many stashes (at most " + MAX_STASHES + ")");
        Entry m = mutate.apply(base);
        Entry next = new Entry(base.rev + 1, m.enabled, m.auto, m.threshold, m.reserve, m.chest, m.shulkers, clock.getAsLong());
        Map<IndexStore.StashKey, Entry> copy = new LinkedHashMap<>(entries);
        copy.put(k, next);
        write(copy); // memory changes only once the file is down
        entries.put(k, next);
        return settings(next);
    }

    private void write(Map<IndexStore.StashKey, Entry> all) throws IOException {
        JsonArray list = new JsonArray();
        for (Map.Entry<IndexStore.StashKey, Entry> e : all.entrySet()) {
            Entry x = e.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("server", e.getKey().server());
            o.addProperty("stash", e.getKey().stash());
            o.addProperty("rev", x.rev);
            o.addProperty("enabled", x.enabled);
            o.addProperty("auto", x.auto);
            o.addProperty("threshold", x.threshold);
            o.addProperty("reserve", x.reserve);
            o.addProperty("needsRepairChest", x.chest);
            o.addProperty("shulkers", x.shulkers);
            o.addProperty("updatedAt", x.updatedAt);
            list.add(o);
        }
        for (JsonObject raw : damaged) list.add(raw); // unreadable entries stay exactly as they were
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("stashes", list);
        // The live file becomes the backup only while it still parses cleanly, so a damaged one never replaces a good backup.
        if (Files.exists(file) && !loadedFromBackup) {
            boolean good;
            try {
                good = read(file).damaged().isEmpty();
            } catch (IOException | RuntimeException e) {
                good = false;
            }
            if (good) writeRetry(backup, Files.readAllBytes(file)); // owner only from the first byte, like the live file
        }
        writeRetry(file, root.toString().getBytes(StandardCharsets.UTF_8));
        loadedFromBackup = false;
    }

    private static void writeRetry(Path path, byte[] bytes) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                AtomicFiles.write(path, bytes, true);
                return;
            } catch (java.nio.file.AccessDeniedException e) {
                // Windows: a virus scanner or indexer can hold the file for a moment while it is replaced.
                if (attempt >= 5) throw e;
                try {
                    Thread.sleep(25L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Drops a stash's entry (the stash was forgotten). */
    synchronized void forget(String server, String stash) throws IOException {
        if (disabled) return;
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        if (!entries.containsKey(k) || damagedKeys.contains(k)) return;
        Map<IndexStore.StashKey, Entry> copy = new LinkedHashMap<>(entries);
        copy.remove(k);
        write(copy);
        entries.remove(k);
    }
}
