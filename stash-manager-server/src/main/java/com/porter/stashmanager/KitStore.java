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
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Kit settings per (server, stash) (KIT-CONTRACT.md, "Kit settings" and "Server storage"): the kits chest and one rule
 * per kit name. Stored in data/kits.json, written atomically; the previous file is copied to kits.json.bak first.
 * When the file and its backup are both unreadable, kits are disabled: nothing is ever written over them.
 */
final class KitStore {
    static final int MAX_RULES = 200;
    static final int MAX_STASHES = 64;

    /** The kit score settings (KIT-CONTRACT.md v1.1): the threshold (1..40) and four point values (0..10). */
    record Scoring(int threshold, int name, int mix, int full, int op, int opMin) {
        static final Scoring DEFAULT = new Scoring(4, 2, 3, 1, 4, 3);

        /** Without opMin (an older request or file): 3 OP items. */
        Scoring(int threshold, int name, int mix, int full, int op) {
            this(threshold, name, mix, full, op, 3);
        }

        boolean valid() {
            return threshold >= 1 && threshold <= 40 && ok(name) && ok(mix) && ok(full) && ok(op) && opMin >= 1 && opMin <= 1728;
        }

        private static boolean ok(int v) {
            return v >= 0 && v <= 10;
        }

        int max() {
            return name + mix + full + op;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("threshold", threshold);
            o.addProperty("name", name);
            o.addProperty("mix", mix);
            o.addProperty("full", full);
            o.addProperty("op", op);
            o.addProperty("opMin", opMin);
            return o;
        }
    }

    /** One stash's settings. rules is sorted by key and must not be changed. */
    record Settings(long rev, String hash, String kitsChest, Scoring scoring, TreeMap<String, String> rules) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("rev", rev);
            o.addProperty("hash", hash);
            o.addProperty("kitsChest", kitsChest);
            o.add("scoring", scoring.toJson());
            JsonObject r = new JsonObject();
            rules.forEach(r::addProperty);
            o.add("rules", r);
            return o;
        }
    }

    /** The store can't read its file (and backup): routes answer 503. */
    static final class DisabledException extends RuntimeException {
        DisabledException() {
            super("the kit settings file can't be read");
        }
    }

    /** A change the contract refuses (a 201st rule, a 65th stash). */
    static final class LimitException extends RuntimeException {
        LimitException(String message) {
            super(message);
        }
    }

    private record Entry(long rev, String kitsChest, Scoring scoring, TreeMap<String, String> rules, long updatedAt) {
    }

    private final Path file;
    private final Path backup;
    private final LongSupplier clock;
    private final LinkedHashMap<IndexStore.StashKey, Entry> entries = new LinkedHashMap<>();
    private boolean disabled;
    /** Entries of the file that couldn't be read: kept as they are in every write, and their stashes answer 503. */
    private final java.util.List<JsonObject> damaged = new java.util.ArrayList<>();
    private final java.util.Set<IndexStore.StashKey> damagedKeys = new java.util.HashSet<>();
    private boolean loadedFromBackup;

    KitStore(Path dataDir) {
        this(dataDir, System::currentTimeMillis);
    }

    KitStore(Path dataDir, LongSupplier clock) {
        this.file = dataDir.resolve("kits.json");
        this.backup = dataDir.resolve("kits.json.bak");
        this.clock = clock;
    }

    /** The contract's hash: SHA-1 (lowercase hex) of "v1\n" + chest + "\n" + "key\tcontainer\n" per rule, sorted by key. */
    static String hash(String kitsChest, Scoring sc, Map<String, String> rules) {
        StringBuilder sb = new StringBuilder("v1\n").append(kitsChest == null ? "" : kitsChest).append('\n')
            .append("score ").append(sc.threshold()).append(' ').append(sc.name()).append(' ').append(sc.mix()).append(' ')
            .append(sc.full()).append(' ').append(sc.op()).append(' ').append(sc.opMin()).append('\n');
        new TreeMap<>(rules).forEach((k, v) -> sb.append(k).append('\t').append(v).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- loading ----------

    /** What a file held: the stashes that read fine, and the entries that didn't (raw, with their stash when it is named). */
    private record Loaded(Map<IndexStore.StashKey, Entry> entries, java.util.List<JsonObject> damaged, java.util.Set<IndexStore.StashKey> damagedKeys) {
    }

    private void take(Loaded l) {
        entries.putAll(l.entries());
        damaged.addAll(l.damaged());
        damagedKeys.addAll(l.damagedKeys());
        for (JsonObject o : l.damaged()) System.err.println("Kit settings: one stash entry in " + file.getFileName() + " can't be read and is left as it is (kits are off for that stash).");
    }

    synchronized void load() {
        entries.clear();
        damaged.clear();
        damagedKeys.clear();
        disabled = false;
        loadedFromBackup = false;
        if (!Files.exists(file)) {
            if (!Files.exists(backup)) return;
            // The live file is gone but the backup is there: restore from it (the next write recreates the live file).
            try {
                take(read(backup));
                loadedFromBackup = true;
                System.err.println("Kit settings restored from " + backup.getFileName() + " (" + file.getFileName() + " was missing).");
            } catch (IOException | RuntimeException e) {
                disabled = true;
                entries.clear();
                System.err.println("Kit sorting is off: " + file.getFileName() + " is missing and its backup can't be read; it will not be overwritten.");
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
            System.err.println("Kit settings restored from " + backup.getFileName() + ".");
        } catch (IOException | RuntimeException e) {
            disabled = true;
            entries.clear();
            System.err.println("Kit sorting is off: " + file.getFileName() + " and its backup can't be read, and neither will be overwritten.");
        }
    }

    /**
     * The file's stashes. A damaged file (not JSON, unknown version, too many stashes, a stash twice) throws; one bad
     * stash entry is only set aside, so the other stashes keep working.
     */
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
                if (!named.add(key)) throw new IOException("duplicate stash"); // ambiguous: the whole file is suspect
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

    private static Entry entry(JsonObject o) throws IOException {
        {
            long rev = o.get("rev").getAsBigDecimal().longValueExact();
            if (rev < 0) throw new IOException("bad rev");
            String chest = o.get("kitsChest").isJsonNull() ? null : o.get("kitsChest").getAsString();
            if (chest != null && !KitNames.validId(chest)) throw new IOException("bad kits chest");
            JsonObject r = o.getAsJsonObject("rules");
            if (r.size() > MAX_RULES) throw new IOException("too many rules");
            TreeMap<String, String> rules = new TreeMap<>();
            for (Map.Entry<String, JsonElement> re : r.entrySet()) {
                String c = re.getValue().getAsString();
                if (!KitNames.validKey(re.getKey()) || !KitNames.validId(c)) throw new IOException("bad rule");
                rules.put(re.getKey(), c);
            }
            Scoring sc = Scoring.DEFAULT; // no scoring field: the defaults (files from before scoring)
            if (o.has("scoring") && !o.get("scoring").isJsonNull()) {
                JsonObject so = o.getAsJsonObject("scoring");
                sc = new Scoring(so.get("threshold").getAsBigDecimal().intValueExact(), so.get("name").getAsBigDecimal().intValueExact(),
                    so.get("mix").getAsBigDecimal().intValueExact(), so.get("full").getAsBigDecimal().intValueExact(),
                    so.get("op").getAsBigDecimal().intValueExact(),
                    so.has("opMin") && !so.get("opMin").isJsonNull() ? so.get("opMin").getAsBigDecimal().intValueExact() : 3); // none: 3
                if (!sc.valid()) throw new IOException("bad scoring");
            }
            long at = o.has("updatedAt") && o.get("updatedAt").isJsonPrimitive() ? o.get("updatedAt").getAsLong() : 0;
            return new Entry(rev, chest, sc, rules, at);
        }
    }

    // ---------- reads ----------

    /** Whether the file and its backup were unreadable (kits are off until someone repairs the file and restarts). */
    synchronized boolean disabled() {
        return disabled;
    }

    /** The stash's settings (empty, rev 0, when it has none). Throws DisabledException when kits are off. */
    synchronized Settings get(String server, String stash) {
        if (disabled || damagedKeys.contains(new IndexStore.StashKey(server, stash))) throw new DisabledException();
        return settings(entries.get(new IndexStore.StashKey(server, stash)));
    }

    /** Whether the store holds settings (or an unreadable entry) for the stash. */
    synchronized boolean knows(String server, String stash) {
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        return entries.containsKey(k) || damagedKeys.contains(k);
    }

    private static Settings settings(Entry e) {
        if (e == null) return new Settings(0, hash(null, Scoring.DEFAULT, Map.of()), null, Scoring.DEFAULT, new TreeMap<>());
        return new Settings(e.rev, hash(e.kitsChest, e.scoring, e.rules), e.kitsChest, e.scoring, new TreeMap<>(e.rules));
    }

    // ---------- changes (each answers the new settings; a change that changes nothing writes nothing) ----------

    synchronized Settings setChest(String server, String stash, String container) throws IOException {
        return change(server, stash, e -> container == null ? e.kitsChest == null : container.equals(e.kitsChest),
            e -> new Entry(0, container, e.scoring, new TreeMap<>(e.rules), 0));
    }

    synchronized Settings setRule(String server, String stash, String key, String container) throws IOException {
        return change(server, stash, e -> container.equals(e.rules.get(key)), e -> {
            TreeMap<String, String> rules = new TreeMap<>(e.rules);
            if (!rules.containsKey(key) && rules.size() >= MAX_RULES) throw new LimitException("at most " + MAX_RULES + " kit rules");
            rules.put(key, container);
            return new Entry(0, e.kitsChest, e.scoring, rules, 0);
        });
    }

    synchronized Settings setScoring(String server, String stash, Scoring scoring) throws IOException {
        if (!scoring.valid()) throw new LimitException("scoring out of range");
        return change(server, stash, e -> scoring.equals(e.scoring), e -> new Entry(0, e.kitsChest, scoring, new TreeMap<>(e.rules), 0));
    }

    /** The settings after a rule delete, and the container the rule had (null when there was none). */
    record Deleted(Settings settings, String previous) {
    }

    synchronized Deleted deleteRule(String server, String stash, String key) throws IOException {
        if (disabled || damagedKeys.contains(new IndexStore.StashKey(server, stash))) throw new DisabledException();
        Entry old = entries.get(new IndexStore.StashKey(server, stash));
        String previous = old == null ? null : old.rules.get(key);
        return new Deleted(change(server, stash, e -> !e.rules.containsKey(key), e -> {
            TreeMap<String, String> rules = new TreeMap<>(e.rules);
            rules.remove(key);
            return new Entry(0, e.kitsChest, e.scoring, rules, 0);
        }), previous);
    }

    private Settings change(String server, String stash, java.util.function.Predicate<Entry> same,
                            java.util.function.UnaryOperator<Entry> mutate) throws IOException {
        if (disabled) throw new DisabledException();
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        if (damagedKeys.contains(k)) throw new DisabledException(); // its entry in the file is unreadable: not overwritten
        if (!KitNames.wellFormed(server) || !KitNames.wellFormed(stash)) throw new LimitException("invalid stash name");
        Entry old = entries.get(k);
        Entry base = old != null ? old : new Entry(0, null, Scoring.DEFAULT, new TreeMap<>(), 0);
        if (same.test(base)) return settings(old);
        if (old == null && entries.size() + damaged.size() >= MAX_STASHES) throw new LimitException("kit settings exist for too many stashes (at most " + MAX_STASHES + ")");
        Entry m = mutate.apply(base);
        Entry next = new Entry(base.rev + 1, m.kitsChest, m.scoring, m.rules, clock.getAsLong());
        Map<IndexStore.StashKey, Entry> copy = new LinkedHashMap<>(entries);
        copy.put(k, next);
        write(copy); // memory changes only once the file is down
        entries.put(k, next);
        return settings(next);
    }

    private void write(Map<IndexStore.StashKey, Entry> all) throws IOException {
        JsonArray list = new JsonArray();
        for (Map.Entry<IndexStore.StashKey, Entry> e : all.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("server", e.getKey().server());
            o.addProperty("stash", e.getKey().stash());
            o.addProperty("rev", e.getValue().rev);
            o.addProperty("kitsChest", e.getValue().kitsChest);
            JsonObject r = new JsonObject();
            e.getValue().rules.forEach(r::addProperty);
            o.add("rules", r);
            if (!e.getValue().scoring.equals(Scoring.DEFAULT)) o.add("scoring", e.getValue().scoring.toJson()); // none: the defaults
            o.addProperty("updatedAt", e.getValue().updatedAt);
            list.add(o);
        }
        for (JsonObject raw : damaged) list.add(raw); // unreadable entries stay exactly as they were
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("stashes", list);
        // The unreadable file we restored from must not replace the good backup.
        // The live file becomes the backup only while it still parses, so a damaged one never replaces a good backup.
        if (Files.exists(file) && !loadedFromBackup) {
            boolean good;
            try {
                good = read(file).damaged().isEmpty(); // a file with unreadable entries doesn't replace a clean backup
            } catch (IOException | RuntimeException e) {
                good = false;
            }
            if (good) {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                AtomicFiles.ownerOnly(backup);
            }
        }
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        for (int attempt = 1; ; attempt++) {
            try {
                AtomicFiles.write(file, bytes, true);
                break;
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
        loadedFromBackup = false;
    }
}
