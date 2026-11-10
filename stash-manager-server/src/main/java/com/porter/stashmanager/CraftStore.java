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
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Craft settings per (server, stash) (CRAFT-CONTRACT.md, "Craft settings"): the crafting table the bot uses for craft
 * orders, and the server-side never-use list. Stored in data/craft.json, written atomically; the previous file is copied
 * to craft.json.bak first. Loaded like kits.json: a damaged stash entry is kept as it is and only that stash answers 503;
 * when the file and its backup are both unreadable the store is disabled and never writes.
 */
final class CraftStore {
    static final int MAX_NEVER_USE = 200;
    static final int MAX_STASHES = 64;
    static final Pattern ITEM_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,128}$");
    static final Pattern DIMENSION = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,100}$");

    /** The crafting table's position. */
    record Table(int x, int y, int z, String dimension) {
        static boolean valid(long x, long y, long z, String dimension) {
            return Math.abs(x) <= 30_000_000 && Math.abs(z) <= 30_000_000 && y >= -2048 && y <= 2048
                && dimension != null && DIMENSION.matcher(dimension).matches();
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            o.addProperty("dimension", dimension);
            return o;
        }
    }

    /** One stash's settings. neverUse is sorted and must not be changed. */
    record Settings(long rev, String hash, Table table, TreeSet<String> neverUse) {
        /** What the bot gets: no never-use list (server side only). */
        JsonObject toBotJson() {
            JsonObject o = new JsonObject();
            o.addProperty("rev", rev);
            o.addProperty("hash", hash);
            o.add("table", table == null ? com.google.gson.JsonNull.INSTANCE : table.toJson());
            return o;
        }
    }

    /** The store can't read its file (and backup): routes answer 503. */
    static final class DisabledException extends RuntimeException {
        DisabledException() {
            super("the craft settings file can't be read");
        }
    }

    /** A change the contract refuses (a 201st id, a 65th stash). */
    static final class LimitException extends RuntimeException {
        LimitException(String message) {
            super(message);
        }
    }

    private record Entry(long rev, Table table, TreeSet<String> neverUse, long updatedAt) {
    }

    private final Path file;
    private final Path backup;
    private final LongSupplier clock;
    private final LinkedHashMap<IndexStore.StashKey, Entry> entries = new LinkedHashMap<>();
    private boolean disabled;
    private final java.util.List<JsonObject> damaged = new java.util.ArrayList<>();
    private final java.util.Set<IndexStore.StashKey> damagedKeys = new java.util.HashSet<>();
    private boolean loadedFromBackup;

    CraftStore(Path dataDir) {
        this(dataDir, System::currentTimeMillis);
    }

    CraftStore(Path dataDir, LongSupplier clock) {
        this.file = dataDir.resolve("craft.json");
        this.backup = dataDir.resolve("craft.json.bak");
        this.clock = clock;
    }

    /** SHA-1 (lowercase hex) of "craft-v1\n" + (table ? "x,y,z,dimension" : "") + "\n". The never-use list is not part of it. */
    static String hash(Table table) {
        String s = "craft-v1\n" + (table == null ? "" : table.x() + "," + table.y() + "," + table.z() + "," + table.dimension()) + "\n";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- loading ----------

    private record Loaded(Map<IndexStore.StashKey, Entry> entries, java.util.List<JsonObject> damaged, java.util.Set<IndexStore.StashKey> damagedKeys) {
    }

    private void take(Loaded l) {
        entries.putAll(l.entries());
        damaged.addAll(l.damaged());
        damagedKeys.addAll(l.damagedKeys());
        for (JsonObject o : l.damaged()) System.err.println("Craft settings: one stash entry in " + file.getFileName() + " can't be read and is left as it is (crafting is off for that stash).");
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
                System.err.println("Craft settings restored from " + backup.getFileName() + " (" + file.getFileName() + " was missing).");
            } catch (IOException | RuntimeException e) {
                disabled = true;
                entries.clear();
                System.err.println("Crafting settings are off: " + file.getFileName() + " is missing and its backup can't be read; it will not be overwritten.");
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
            System.err.println("Craft settings restored from " + backup.getFileName() + ".");
        } catch (IOException | RuntimeException e) {
            disabled = true;
            entries.clear();
            System.err.println("Crafting settings are off: " + file.getFileName() + " and its backup can't be read, and neither will be overwritten.");
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

    private static Entry entry(JsonObject o) throws IOException {
        long rev = o.get("rev").getAsBigDecimal().longValueExact();
        if (rev < 0) throw new IOException("bad rev");
        Table table = null;
        JsonElement te = o.get("table");
        if (te != null && !te.isJsonNull()) {
            JsonObject t = te.getAsJsonObject();
            long x = t.get("x").getAsBigDecimal().longValueExact(), y = t.get("y").getAsBigDecimal().longValueExact(), z = t.get("z").getAsBigDecimal().longValueExact();
            String dim = t.get("dimension").getAsString();
            if (!Table.valid(x, y, z, dim)) throw new IOException("bad table");
            table = new Table((int) x, (int) y, (int) z, dim);
        }
        TreeSet<String> never = new TreeSet<>();
        JsonElement ne = o.get("neverUse");
        if (ne != null && !ne.isJsonNull()) {
            JsonArray a = ne.getAsJsonArray();
            if (a.size() > MAX_NEVER_USE) throw new IOException("too many never-use ids");
            for (JsonElement id : a) {
                String s = id.getAsString();
                if (!ITEM_ID.matcher(s).matches()) throw new IOException("bad never-use id");
                never.add(s);
            }
        }
        long at = o.has("updatedAt") && o.get("updatedAt").isJsonPrimitive() ? o.get("updatedAt").getAsLong() : 0;
        return new Entry(rev, table, never, at);
    }

    // ---------- reads ----------

    synchronized boolean disabled() {
        return disabled;
    }

    /** The stash's settings (rev 0, no table, nothing never-used when it has none). Throws DisabledException when off. */
    synchronized Settings get(String server, String stash) {
        if (disabled || damagedKeys.contains(new IndexStore.StashKey(server, stash))) throw new DisabledException();
        return settings(entries.get(new IndexStore.StashKey(server, stash)));
    }

    synchronized boolean knows(String server, String stash) {
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        return entries.containsKey(k) || damagedKeys.contains(k);
    }

    private static Settings settings(Entry e) {
        if (e == null) return new Settings(0, hash(null), null, new TreeSet<>());
        return new Settings(e.rev, hash(e.table), e.table, new TreeSet<>(e.neverUse));
    }

    // ---------- changes ----------

    synchronized Settings setTable(String server, String stash, Table table) throws IOException {
        return change(server, stash, e -> java.util.Objects.equals(table, e.table), e -> new Entry(0, table, new TreeSet<>(e.neverUse), 0));
    }

    synchronized Settings setNeverUse(String server, String stash, java.util.Collection<String> ids) throws IOException {
        TreeSet<String> set = new TreeSet<>(ids);
        if (set.size() > MAX_NEVER_USE) throw new LimitException("at most " + MAX_NEVER_USE + " never-use items");
        for (String id : set) if (!ITEM_ID.matcher(id).matches()) throw new LimitException("not an item id: " + id);
        return change(server, stash, e -> set.equals(e.neverUse), e -> new Entry(0, e.table, set, 0));
    }

    /** Drops a stash's settings (the stash was forgotten), so orphans don't fill the 64 entries. No-op when there are none. */
    synchronized void forget(String server, String stash) throws IOException {
        if (disabled) return;
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        if (!entries.containsKey(k) || damagedKeys.contains(k)) return;
        Map<IndexStore.StashKey, Entry> copy = new LinkedHashMap<>(entries);
        copy.remove(k);
        write(copy);
        entries.remove(k);
    }

    private Settings change(String server, String stash, java.util.function.Predicate<Entry> same,
                            java.util.function.UnaryOperator<Entry> mutate) throws IOException {
        if (disabled) throw new DisabledException();
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        if (damagedKeys.contains(k)) throw new DisabledException();
        if (!KitNames.wellFormed(server) || !KitNames.wellFormed(stash)) throw new LimitException("invalid stash name");
        Entry old = entries.get(k);
        Entry base = old != null ? old : new Entry(0, null, new TreeSet<>(), 0);
        if (same.test(base)) return settings(old);
        if (old == null && entries.size() + damaged.size() >= MAX_STASHES) {
            throw new LimitException("craft settings exist for too many stashes (at most " + MAX_STASHES + ")");
        }
        Entry m = mutate.apply(base);
        Entry next = new Entry(base.rev + 1, m.table, m.neverUse, clock.getAsLong());
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
            o.add("table", e.getValue().table == null ? com.google.gson.JsonNull.INSTANCE : e.getValue().table.toJson());
            JsonArray never = new JsonArray();
            e.getValue().neverUse.forEach(never::add);
            o.add("neverUse", never);
            o.addProperty("updatedAt", e.getValue().updatedAt);
            list.add(o);
        }
        for (JsonObject raw : damaged) list.add(raw); // unreadable entries stay exactly as they were
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("stashes", list);
        if (Files.exists(file) && !loadedFromBackup) {
            boolean good;
            try {
                good = read(file).damaged().isEmpty();
            } catch (IOException | RuntimeException e) {
                good = false;
            }
            if (good) {
                AtomicFiles.write(backup, Files.readAllBytes(file), true);
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
