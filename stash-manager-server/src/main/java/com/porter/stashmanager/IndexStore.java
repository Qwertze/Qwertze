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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stash indexes and heartbeat state, kept in memory and persisted to data/indexes.
 * Each index is kept as its serialized bytes (served as-is) plus a few derived fields; the JSON tree is not retained.
 */
public final class IndexStore {
    public static final long ONLINE_WINDOW_MS = 45_000;
    public static final int MAX_STASHES = 64;
    private static final int MAX_NAME_PART = 64;

    private final Path dir;
    private final CreativeTabs tabs;
    private final Map<StashKey, Stash> stashes = new ConcurrentHashMap<>();
    private final Object createLock = new Object();

    public record StashKey(String server, String stash) {
    }

    /** What the icon proxy needs to fetch an id's icon. */
    public record IconInfo(String englishName, String mcVersion) {
    }

    /** A prepared index plus the bytes to write to disk (as the bot sent it, without the creative-tab fallback). */
    private record Built(Prepared p, byte[] disk) {
    }

    /** A stored index ready to serve: the bytes and their ETag. */
    public record IndexBytes(byte[] bytes, byte[] gzip, String etag) {
    }

    /** Thrown when a new (server, stash) would go past {@link #MAX_STASHES}. */
    public static final class FullException extends Exception {
        FullException() {
            super("too many stashes (limit " + MAX_STASHES + ")");
        }
    }

    /** An index after validation, merging and serialization, built outside any lock. */
    private record Prepared(String server, String stash, String player, String indexHash, long receivedAt,
                            int itemTypes, long itemTotal, byte[] bytes, byte[] gzip, String etag,
                            Map<String, String> idNames, String mcVersion, OrderCounts orderCounts, long[] containers,
                            Map<String, CreativeTabs.Entry> tabOrder, String dimension) {
    }

    /**
     * What an order may take from one index. items: item key -> count; itemIds: item key -> item id;
     * shulkers: shulker group key -> count; filledByItemKey: shulker item key -> filled shulkers of it (from
     * shulkers[].itemKey); filledById: the same by item id, for groups without itemKey (older indexes).
     */
    public record OrderCounts(Map<String, Long> items, Map<String, String> itemIds, Map<String, Integer> maxStacks,
                              Map<String, Long> shulkers, Map<String, Long> filledByItemKey, Map<String, Long> filledById,
                              Map<String, Repair.Info> repairs, Map<String, String> matches,
                              Map<String, EnchantIndex.Entry> enchant) {
        public OrderCounts(Map<String, Long> items, Map<String, String> itemIds, Map<String, Integer> maxStacks,
                           Map<String, Long> shulkers, Map<String, Long> filledByItemKey, Map<String, Long> filledById) {
            this(items, itemIds, maxStacks, shulkers, filledByItemKey, filledById, Map.of(), Map.of(), Map.of());
        }

        public OrderCounts(Map<String, Long> items, Map<String, String> itemIds, Map<String, Integer> maxStacks,
                           Map<String, Long> shulkers, Map<String, Long> filledByItemKey, Map<String, Long> filledById,
                           Map<String, Repair.Info> repairs) {
            this(items, itemIds, maxStacks, shulkers, filledByItemKey, filledById, repairs, Map.of(), Map.of());
        }

        public OrderCounts(Map<String, Long> items, Map<String, String> itemIds, Map<String, Integer> maxStacks,
                           Map<String, Long> shulkers, Map<String, Long> filledByItemKey, Map<String, Long> filledById,
                           Map<String, Repair.Info> repairs, Map<String, String> matches) {
            this(items, itemIds, maxStacks, shulkers, filledByItemKey, filledById, repairs, matches, Map.of());
        }

        /**
         * The match key of an index key (KIT-CONTRACT-v2, revision 4): the bot's, else the key itself when it is a plain id (an
         * older bot), else null (a variant key from an older bot has none; also null for a key that isn't in the index).
         */
        public String matchOf(String key) {
            String m = matches.get(key);
            if (m != null) return m;
            if (items.containsKey(key) && key.indexOf('#') < 0) return key;
            return null;
        }

        /** The base item id of an index key ("minecraft:bow#ab12cd34" is "minecraft:bow"). */
        public static String baseOf(String key) {
            int h = key.indexOf('#');
            return h < 0 ? key : key.substring(0, h);
        }

        /** Repair info of an item key (not in the index: no Mending). */
        Repair.Info repair(String key) {
            Repair.Info i = repairs.get(key);
            return i != null ? i : new Repair.Info(Repair.NO_MENDING, 0);
        }

        /**
         * Units the index holds for a line, before reservations; -1 when the key isn't in the index.
         * An item line for a shulker box means EMPTY shulkers: its count minus the filled ones of that id.
         */
        public long total(String type, String key) {
            if (type.equals("shulker")) return shulkers.getOrDefault(key, -1L);
            Long count = items.get(key);
            if (count == null) return -1;
            String id = itemIds.get(key);
            if (id != null && isShulkerBox(id)) {
                long filled = filledByItemKey.getOrDefault(key, 0L) + filledById.getOrDefault(id, 0L);
                return Math.max(0, count - filled);
            }
            return count;
        }

        /** The item's stack size from the index (64 when it gave none, at least 1). */
        public int maxStack(String key) {
            return maxStacks.getOrDefault(key, 64);
        }

        /** Whether an item line for this key means empty shulkers. */
        public boolean isShulkerBoxKey(String key) {
            String id = itemIds.get(key);
            return id != null && isShulkerBox(id);
        }

        static boolean isShulkerBox(String id) {
            return id.startsWith("minecraft:") && id.endsWith("shulker_box");
        }
    }

    /** The delivery container the bot reported in its heartbeat. */
    public record Delivery(int x, int y, int z, String dimension) {
    }

    /**
     * Whether the bot can name shulkers: an anvil is set (and looks like one), its XP level (-1 = unknown), and where
     * its anvil comes from ("set", "found", "placed" or "none"; null for an older bot that doesn't report it).
     */
    public record Naming(boolean anvil, int xpLevels, String anvilSource) {
        public static final java.util.Set<String> ANVIL_SOURCES = java.util.Set.of("set", "found", "placed", "none");

        public Naming(boolean anvil, int xpLevels) {
            this(anvil, xpLevels, null);
        }
    }

    /**
     * Whether the bot can craft: auto-crafting is on, a crafting table is set (and, if loaded, really is one), and where
     * its table comes from ("set", "found", "placed", "crafted" or "none"; null for an older bot that doesn't report it).
     */
    public record Crafting(boolean enabled, boolean table, String tableSource) {
        public static final java.util.Set<String> TABLE_SOURCES = java.util.Set.of("set", "found", "placed", "crafted", "none");

        public Crafting(boolean enabled, boolean table) {
            this(enabled, table, null);
        }
    }

    /**
     * The delivery storage (plugin 1.9.0 / addon 0.4.0-dev.5 heartbeat "deliveryStorage"): how many delivery chests
     * there are, how many are full, whether more can be put up, and why not. Never positions.
     */
    public record DeliveryStorage(boolean autoExpand, int chests, int full, boolean canExpand, String blocked) {
    }

    /**
     * The bot-status fields of one heartbeat. A field is only changed when the heartbeat carried it
     * (hasDelivery / hasBlocker / hasNaming / hasCrafting), so an older addon that never sends them leaves them null.
     */
    public record BotStatus(boolean hasDelivery, Delivery delivery, boolean hasBlocker, String orderBlocker,
                            long rescanDone, boolean hasRescanBlocked, String rescanBlocked, boolean hasNaming, Naming naming,
                            boolean hasCrafting, Crafting crafting, boolean hasStorage, DeliveryStorage storage,
                            boolean hasRepair, boolean repair) {
        public static final BotStatus NONE = new BotStatus(false, null, false, null, -1, false, null, false, null, false, null, false, null);

        public BotStatus(boolean hasDelivery, Delivery delivery, boolean hasBlocker, String orderBlocker, long rescanDone,
                         boolean hasRescanBlocked, String rescanBlocked, boolean hasNaming, Naming naming, boolean hasCrafting,
                         Crafting crafting, boolean hasStorage, DeliveryStorage storage) {
            this(hasDelivery, delivery, hasBlocker, orderBlocker, rescanDone, hasRescanBlocked, rescanBlocked, hasNaming, naming,
                hasCrafting, crafting, hasStorage, storage, false, false);
        }

        public BotStatus(boolean hasDelivery, Delivery delivery, boolean hasBlocker, String orderBlocker, long rescanDone,
                         boolean hasRescanBlocked, String rescanBlocked, boolean hasNaming, Naming naming, boolean hasCrafting,
                         Crafting crafting) {
            this(hasDelivery, delivery, hasBlocker, orderBlocker, rescanDone, hasRescanBlocked, rescanBlocked, hasNaming, naming,
                hasCrafting, crafting, false, null);
        }
    }

    /** A stash's stored player and when it was last seen, for adopting owners on load. */
    public record PlayerSeen(String player, long seen) {
    }

    /** Every stash's stored player (from its index or heartbeats). */
    public Map<StashKey, PlayerSeen> players() {
        Map<StashKey, PlayerSeen> out = new HashMap<>();
        for (Map.Entry<StashKey, Stash> e : stashes.entrySet()) {
            Stash s = e.getValue();
            synchronized (s) {
                if (s.player != null) out.put(e.getKey(), new PlayerSeen(s.player, s.lastSeen));
            }
        }
        return out;
    }

    /** Whether the server holds anything (index or heartbeat state) for the pair. */
    /** Every server address the store knows a stash for. */
    public java.util.Set<String> servers() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (StashKey k : stashes.keySet()) out.add(k.server());
        return out;
    }

    public boolean has(String server, String stash) {
        return stashes.containsKey(new StashKey(server, stash));
    }

    /** What a heartbeat reply needs: whether to push the index, and the unacknowledged rescan id (or null). */
    public record HeartbeatResult(boolean needIndex, Long rescan) {
    }

    /** One (server, stash). The monitor guards the state fields; writeLock orders disk writes. */
    private static final class Stash {
        final String server;
        final String stash;
        String player;
        long lastSeen;
        Delivery delivery;
        Naming naming;
        Crafting crafting;
        DeliveryStorage storage;
        boolean repair; // the bot can repair items with XP bottles (heartbeat "repair"); in memory only
        String orderBlocker;
        long rescanRequested; // latest rescan request id, 0 = none
        long rescanDone;      // highest id the bot acknowledged
        String rescanBlocked;
        Prepared index;
        long version;

        final Object writeLock = new Object();
        long writtenVersion; // guarded by writeLock

        Stash(String server, String stash) {
            this.server = server;
            this.stash = stash;
        }
    }

    public IndexStore(Path dataDir) {
        this(dataDir, CreativeTabs.bundled());
    }

    IndexStore(Path dataDir, CreativeTabs tabs) {
        this.dir = dataDir.resolve("indexes");
        this.tabs = tabs;
    }

    // ---------- loading ----------

    public void load() throws IOException {
        Files.createDirectories(dir);
        record Loaded(Path file, Prepared p, byte[] disk) {
        }
        Map<StashKey, Loaded> newest = new HashMap<>();
        List<Path> superseded = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                try {
                    JsonElement json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                    String error = validate(json);
                    if (error != null) {
                        System.err.println("Skipping " + file.getFileName() + ": " + error);
                        continue;
                    }
                    JsonObject index = json.getAsJsonObject();
                    long receivedAt = isNumber(index, "receivedAt") ? index.get("receivedAt").getAsLong()
                        : Files.getLastModifiedTime(file).toMillis();
                    Built b = prepare(index, receivedAt, tabs);
                    Prepared p = b.p;
                    StashKey key = new StashKey(p.server, p.stash);
                    Loaded old = newest.get(key);
                    if (old == null || old.p.receivedAt < p.receivedAt) {
                        if (old != null) superseded.add(old.file);
                        newest.put(key, new Loaded(file, p, b.disk));
                    } else {
                        superseded.add(file);
                    }
                } catch (RuntimeException | IOException e) {
                    System.err.println("Skipping " + file.getFileName() + ": " + e.getMessage());
                }
            }
        }
        java.util.Set<Path> keep = new java.util.HashSet<>();
        for (Loaded l : newest.values()) {
            if (stashes.size() >= MAX_STASHES) {
                System.err.println("Not loading " + l.file.getFileName() + ": more than " + MAX_STASHES + " stashes");
                continue;
            }
            Stash s = new Stash(l.p.server, l.p.stash);
            s.index = l.p;
            s.player = l.p.player;
            s.lastSeen = l.p.receivedAt;
            stashes.put(new StashKey(s.server, s.stash), s);
            // Files from before the hash suffix (or otherwise misnamed) are moved to the current name.
            Path expected = dir.resolve(fileName(s.server, s.stash));
            keep.add(expected.getFileName());
            if (!l.file.getFileName().equals(expected.getFileName())) {
                AtomicFiles.write(expected, l.disk);
                superseded.add(l.file);
            }
        }
        for (Path p : superseded) {
            if (keep.contains(p.getFileName())) continue;
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                System.err.println("Could not delete old index file " + p.getFileName() + ": " + e.getMessage());
            }
        }
    }

    public int size() {
        return stashes.size();
    }

    private Stash getOrCreate(String server, String stash) throws FullException {
        StashKey key = new StashKey(server, stash);
        Stash s = stashes.get(key);
        if (s != null) return s;
        synchronized (createLock) {
            s = stashes.get(key);
            if (s != null) return s;
            if (stashes.size() >= MAX_STASHES) evictOne();
            if (stashes.size() >= MAX_STASHES) throw new FullException();
            s = new Stash(server, stash);
            stashes.put(key, s);
            return s;
        }
    }

    private volatile java.util.function.BiConsumer<String, String> forgetListener = (s, t) -> {
    };

    /** Called when a stash is dropped from memory (so settings kept for it elsewhere can go too). */
    void onForget(java.util.function.BiConsumer<String, String> listener) {
        this.forgetListener = listener;
    }

    /** The dimension the stash's bot reported in its index (null: none). */
    String dimension(String server, String stash) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return null;
        Prepared p;
        synchronized (s) {
            p = s.index;
        }
        return p == null ? null : p.dimension;
    }

    /** What the bot's last heartbeat said about naming (an anvil is set), or null when it never did. */
    Naming naming(String server, String stash) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return null;
        synchronized (s) {
            return s.naming;
        }
    }

    /** When the stash's bot last sent a heartbeat or an index (0 when never). */
    long lastSeen(String server, String stash) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return 0;
        synchronized (s) {
            return s.lastSeen;
        }
    }

    /** The bot's delivery container position, or null. */
    Delivery delivery(String server, String stash) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return null;
        synchronized (s) {
            return s.delivery;
        }
    }

    /** Makes room by dropping an offline stash that never sent an index (heartbeat-only entries). */
    private void evictOne() {
        long now = System.currentTimeMillis();
        for (Map.Entry<StashKey, Stash> e : stashes.entrySet()) {
            Stash s = e.getValue();
            synchronized (s) {
                if (s.index == null && now - s.lastSeen >= ONLINE_WINDOW_MS) {
                    stashes.remove(e.getKey(), s);
                    try {
                        forgetListener.accept(e.getKey().server(), e.getKey().stash());
                    } catch (RuntimeException ignored) {
                        // cleanup of settings elsewhere must never stop a new stash from being added
                    }
                    return;
                }
            }
        }
    }

    // ---------- validation ----------

    /** Returns null when the index has the v1 shape, otherwise a short reason. */
    public static String validate(JsonElement element) {
        if (element == null || !element.isJsonObject()) return "body must be a JSON object";
        JsonObject o = element.getAsJsonObject();
        if (!isNumber(o, "version") || o.get("version").getAsDouble() != 1) return "version must be 1";
        if (!isNonEmptyString(o, "server")) return "server must be a non-empty string";
        if (!isNonEmptyString(o, "stash")) return "stash must be a non-empty string";
        for (String field : new String[]{"player", "mcVersion", "dimension", "indexHash"}) {
            if (o.has(field) && !o.get(field).isJsonNull() && !isString(o, field)) return field + " must be a string";
        }
        if (o.has("generatedAt") && !o.get("generatedAt").isJsonNull() && !isNumber(o, "generatedAt")) return "generatedAt must be a number";
        if (o.has("containers") && !o.get("containers").isJsonNull()) {
            if (!o.get("containers").isJsonObject()) return "containers must be an object";
            JsonObject c = o.getAsJsonObject("containers");
            for (String field : new String[]{"known", "total", "stale"}) {
                if (c.has(field) && !isNumber(c, field)) return "containers." + field + " must be a number";
            }
        }
        if (!o.has("items") || !o.get("items").isJsonArray()) return "items must be an array";
        JsonArray items = o.getAsJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            JsonElement e = items.get(i);
            if (!e.isJsonObject()) return "items[" + i + "] must be an object";
            JsonObject item = e.getAsJsonObject();
            if (!isNonEmptyString(item, "key")) return "items[" + i + "].key must be a non-empty string";
            if (!isNonEmptyString(item, "id")) return "items[" + i + "].id must be a non-empty string";
            for (String field : new String[]{"name", "englishName"}) {
                if (item.has(field) && !isString(item, field)) return "items[" + i + "]." + field + " must be a string";
            }
            if (!isNumber(item, "count")) return "items[" + i + "].count must be a number";
            if (item.has("maxStack") && !isNumber(item, "maxStack")) return "items[" + i + "].maxStack must be a number";
            if (item.has("enchanted") && !(item.get("enchanted").isJsonPrimitive() && item.get("enchanted").getAsJsonPrimitive().isBoolean())) {
                return "items[" + i + "].enchanted must be a boolean";
            }
            if (item.has("mending") && !item.get("mending").isJsonNull()
                && !(item.get("mending").isJsonPrimitive() && item.get("mending").getAsJsonPrimitive().isBoolean())) {
                return "items[" + i + "].mending must be a boolean";
            }
            if (item.has("tab") && !item.get("tab").isJsonNull()
                && (!isString(item, "tab") || item.get("tab").getAsString().length() > 40)) return "items[" + i + "].tab must be a short string";
            if (item.has("tabOrder") && !item.get("tabOrder").isJsonNull() && !isNumber(item, "tabOrder")) return "items[" + i + "].tabOrder must be a number";
            if (item.has("match") && !item.get("match").isJsonNull() && !matchOk(item.get("match"))) {
                return "items[" + i + "].match must be an item key like minecraft:bow#ab12cd34";
            }
            if (item.has("details")) {
                if (!item.get("details").isJsonArray()) return "items[" + i + "].details must be an array";
                for (JsonElement d : item.getAsJsonArray("details")) {
                    if (!(d.isJsonPrimitive() && d.getAsJsonPrimitive().isString())) return "items[" + i + "].details must hold strings";
                }
            }
        }
        if (o.has("shulkers") && !o.get("shulkers").isJsonNull()) {
            if (!o.get("shulkers").isJsonArray()) return "shulkers must be an array";
            JsonArray shulkers = o.getAsJsonArray("shulkers");
            for (int i = 0; i < shulkers.size(); i++) {
                String at = "shulkers[" + i + "]";
                if (!shulkers.get(i).isJsonObject()) return at + " must be an object";
                JsonObject g = shulkers.get(i).getAsJsonObject();
                if (!isNonEmptyString(g, "key") || g.get("key").getAsString().length() > 64) return at + ".key must be a short non-empty string";
                if (!isNonEmptyString(g, "id")) return at + ".id must be a non-empty string";
                if (g.has("name") && !g.get("name").isJsonNull() && !isString(g, "name")) return at + ".name must be a string";
                if (g.has("itemKey") && !g.get("itemKey").isJsonNull() && !isString(g, "itemKey")) return at + ".itemKey must be a string";
                if (!isNumber(g, "count")) return at + ".count must be a number";
                if (g.has("contents") && !g.get("contents").isJsonNull()) {
                    if (!g.get("contents").isJsonArray()) return at + ".contents must be an array";
                    JsonArray contents = g.getAsJsonArray("contents");
                    if (contents.size() > 27) return at + ".contents has more than 27 slots";
                    for (int j = 0; j < contents.size(); j++) {
                        String cat = at + ".contents[" + j + "]";
                        if (!contents.get(j).isJsonObject()) return cat + " must be an object";
                        JsonObject c = contents.get(j).getAsJsonObject();
                        if (!isNumber(c, "slot") || c.get("slot").getAsDouble() < 0 || c.get("slot").getAsDouble() > 26) return cat + ".slot must be 0..26";
                        if (!isNonEmptyString(c, "key")) return cat + ".key must be a non-empty string";
                        if (c.has("match") && !c.get("match").isJsonNull() && !matchOk(c.get("match"))) return cat + ".match must be an item key like minecraft:bow#ab12cd34";
                        if (!isNumber(c, "count")) return cat + ".count must be a number";
                    }
                }
            }
        }
        return null;
    }

    /** A match key (KIT-CONTRACT-v2, revision 4): the item-key grammar, as a string. */
    private static boolean matchOk(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && KitTemplate.MATCH.matcher(e.getAsString()).matches();
    }

    private static boolean isString(JsonObject o, String field) {
        JsonElement e = o.get(field);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    private static boolean isNonEmptyString(JsonObject o, String field) {
        return isString(o, field) && !o.get(field).getAsString().isEmpty();
    }

    private static boolean isNumber(JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return false;
        double d = e.getAsDouble();
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    private static String stringOrNull(JsonObject o, String field) {
        return isString(o, field) ? o.get(field).getAsString() : null;
    }

    // ---------- preparing ----------

    /**
     * Merges items with the same key (counts are summed, the first entry's other fields kept), adds receivedAt,
     * serializes, and derives the fields the server needs. index must already pass {@link #validate}.
     * The disk bytes are the index as the bot sent it; the served bytes also have the creative-tab fallback
     * filled in (PROTOCOL.md, "creative tabs for bots that don't send them"), so a newer table fixes stored indexes.
     */
    private static Built prepare(JsonObject index, long receivedAt, CreativeTabs tabs) {
        JsonArray items = index.getAsJsonArray("items");
        Map<String, JsonObject> byKey = new LinkedHashMap<>();
        Map<String, Long> counts = new HashMap<>();
        Map<String, EnchantIndex.Entry> enchant = new HashMap<>();
        for (JsonElement e : items) {
            JsonObject item = e.getAsJsonObject();
            String key = item.get("key").getAsString();
            long count = Math.max(0, item.get("count").getAsLong());
            // ENCHANT-CONTRACT v1.0: a bad ench / rc / named field is dropped here (the entry stays); the first entry of a key wins.
            EnchantIndex.Entry ee = EnchantIndex.clean(item);
            if (!byKey.containsKey(key) && (ee.known() || ee.rc() >= 0 || ee.named())) enchant.put(key, ee);
            byKey.putIfAbsent(key, item);
            counts.merge(key, count, Long::sum);
        }
        if (byKey.size() != items.size()) {
            JsonArray merged = new JsonArray(byKey.size());
            for (Map.Entry<String, JsonObject> e : byKey.entrySet()) {
                JsonObject item = e.getValue();
                item.addProperty("count", counts.get(e.getKey()));
                merged.add(item);
            }
            index.add("items", merged);
        }

        long total = 0;
        Map<String, String> idNames = new HashMap<>();
        for (Map.Entry<String, JsonObject> e : byKey.entrySet()) {
            total += counts.get(e.getKey());
            JsonObject item = e.getValue();
            String id = item.get("id").getAsString();
            String english = stringOrNull(item, "englishName");
            String existing = idNames.get(id);
            if (existing == null || (existing.isEmpty() && english != null && !english.isEmpty())) {
                idNames.put(id, english == null ? "" : english);
            }
        }

        Map<String, Long> itemCounts = new HashMap<>(counts);
        Map<String, String> itemIds = new HashMap<>();
        Map<String, Integer> maxStacks = new HashMap<>();
        for (Map.Entry<String, JsonObject> e : byKey.entrySet()) {
            JsonObject item = e.getValue();
            itemIds.put(e.getKey(), item.get("id").getAsString());
            // Same rule as the page: floor, at least 1, 64 when missing. (int) of a huge double saturates.
            int max = isNumber(item, "maxStack") ? (int) Math.max(1, Math.floor(item.get("maxStack").getAsDouble())) : 64;
            maxStacks.put(e.getKey(), max);
        }
        Map<String, Long> shulkerCounts = new HashMap<>();
        Map<String, Long> filledByItemKey = new HashMap<>();
        Map<String, Long> filledById = new HashMap<>();
        if (index.has("shulkers") && index.get("shulkers").isJsonArray()) {
            for (JsonElement e : index.getAsJsonArray("shulkers")) {
                JsonObject g = e.getAsJsonObject();
                long n = Math.max(0, g.get("count").getAsLong());
                shulkerCounts.merge(g.get("key").getAsString(), n, Long::sum);
                String itemKey = stringOrNull(g, "itemKey");
                if (itemKey != null && !itemKey.isEmpty()) filledByItemKey.merge(itemKey, n, Long::sum);
                else filledById.merge(g.get("id").getAsString(), n, Long::sum);
            }
        }
        Map<String, Repair.Info> repairs = new HashMap<>();
        for (Map.Entry<String, JsonObject> e : byKey.entrySet()) repairs.put(e.getKey(), Repair.of(e.getValue()));
        Map<String, String> matches = new HashMap<>();
        for (Map.Entry<String, JsonObject> e : byKey.entrySet()) {
            String m = stringOrNull(e.getValue(), "match");
            if (m != null && !m.isEmpty()) matches.put(e.getKey(), m);
        }
        OrderCounts orderCounts = new OrderCounts(Map.copyOf(itemCounts), Map.copyOf(itemIds), Map.copyOf(maxStacks), Map.copyOf(shulkerCounts),
            Map.copyOf(filledByItemKey), Map.copyOf(filledById), Map.copyOf(repairs), Map.copyOf(matches), Map.copyOf(enchant));

        index.addProperty("receivedAt", receivedAt);
        byte[] disk = Json.GSON.toJson(index).getBytes(StandardCharsets.UTF_8);
        tabs.fill(index);
        // Served only (the file keeps what the bot sent): the computed repair fields.
        for (Map.Entry<String, JsonObject> e : byKey.entrySet()) {
            Repair.Info r = repairs.get(e.getKey());
            e.getValue().addProperty("repairable", r.repairable());
            e.getValue().addProperty("repairBottles", r.bottles());
        }
        byte[] bytes = Json.GSON.toJson(index).getBytes(StandardCharsets.UTF_8);
        Map<String, CreativeTabs.Entry> tabOrder = new HashMap<>();
        for (JsonElement e : index.getAsJsonArray("items")) {
            JsonObject item = e.getAsJsonObject();
            JsonElement tab = item.get("tab");
            if (tab == null || !tab.isJsonPrimitive() || !tab.getAsJsonPrimitive().isString()) continue;
            int order = isNumber(item, "tabOrder") ? (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, item.get("tabOrder").getAsDouble())) : 1_000_000;
            tabOrder.putIfAbsent(item.get("key").getAsString(), new CreativeTabs.Entry(tab.getAsString(), order));
        }
        String mc = stringOrNull(index, "mcVersion");
        if (mc == null || !IconService.MC_VERSION.matcher(mc).matches()) mc = IconService.DEFAULT_MC;
        return new Built(new Prepared(
            index.get("server").getAsString(),
            index.get("stash").getAsString(),
            stringOrNull(index, "player"),
            stringOrNull(index, "indexHash"),
            receivedAt,
            byKey.size(),
            total,
            bytes,
            Http.gzip(bytes),
            "\"" + sha1Hex(bytes).substring(0, 20) + "\"",
            Map.copyOf(idNames),
            mc,
            orderCounts,
            containers(index),
            Map.copyOf(tabOrder), stringOrNull(index, "dimension")), disk);
    }

    /** {known, total, stale} from the index's containers object (missing numbers are 0), or null. */
    private static long[] containers(JsonObject index) {
        if (!index.has("containers") || !index.get("containers").isJsonObject()) return null;
        JsonObject c = index.getAsJsonObject("containers");
        long[] out = new long[3];
        String[] fields = {"known", "total", "stale"};
        for (int i = 0; i < 3; i++) out[i] = isNumber(c, fields[i]) ? Math.max(0, c.get(fields[i]).getAsLong()) : 0;
        return out;
    }

    // ---------- updates ----------

    /** Stores a validated index and writes it to disk. Serialization and the disk write happen outside the state lock. */
    public void putIndex(JsonObject index) throws IOException, FullException {
        long now = System.currentTimeMillis();
        Built b = prepare(index, now, tabs);
        Prepared p = b.p;
        Stash s = getOrCreate(p.server, p.stash);
        long version;
        synchronized (s) {
            s.index = p;
            if (p.player != null) s.player = p.player;
            s.lastSeen = now;
            version = ++s.version;
        }
        synchronized (s.writeLock) {
            // A newer index may already be on disk; never let an older one overwrite it.
            if (version <= s.writtenVersion) return;
            AtomicFiles.write(dir.resolve(fileName(s.server, s.stash)), b.disk);
            s.writtenVersion = version;
        }
    }

    /** Records a heartbeat and returns whether the addon should push its index. */
    public HeartbeatResult heartbeat(String server, String stash, String player, String indexHash, BotStatus status) throws FullException {
        Stash s = getOrCreate(server, stash);
        synchronized (s) {
            s.lastSeen = System.currentTimeMillis();
            if (player != null) s.player = player;
            if (status.hasDelivery()) s.delivery = status.delivery();
            if (status.hasBlocker()) s.orderBlocker = status.orderBlocker();
            if (status.hasNaming()) s.naming = status.naming();
            if (status.hasCrafting()) s.crafting = status.crafting();
            if (status.hasStorage()) s.storage = status.storage();
            if (status.hasRepair()) s.repair = status.repair();
            if (status.hasRescanBlocked()) s.rescanBlocked = status.rescanBlocked();
            if (status.rescanDone() > s.rescanDone) s.rescanDone = status.rescanDone();
            boolean needIndex = s.index == null || indexHash == null || !indexHash.equals(s.index.indexHash);
            return new HeartbeatResult(needIndex, rescanPending(s));
        }
    }

    private static Long rescanPending(Stash s) {
        return s.rescanRequested > 0 && s.rescanDone < s.rescanRequested ? s.rescanRequested : null;
    }

    /**
     * Records a manual rescan request and returns its id, or -1 when the server knows no such stash. Ids start at the
     * current Unix time in seconds, so they keep increasing across server restarts (an addon that acknowledged an id
     * before a restart doesn't swallow the next request) and still fit a 32-bit int.
     */
    public long requestRescan(String server, String stash) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return -1;
        synchronized (s) {
            long id = Math.max(Math.max(s.rescanRequested, s.rescanDone) + 1, System.currentTimeMillis() / 1000);
            s.rescanRequested = id;
            return id;
        }
    }

    // ---------- reads ----------

    public JsonArray listJson() {
        long now = System.currentTimeMillis();
        List<Stash> list = new ArrayList<>(stashes.values());
        list.sort(Comparator.comparing((Stash s) -> s.server).thenComparing(s -> s.stash));
        JsonArray out = new JsonArray();
        for (Stash s : list) {
            JsonObject o = new JsonObject();
            synchronized (s) {
                o.addProperty("server", s.server);
                o.addProperty("stash", s.stash);
                o.addProperty("player", s.player);
                o.addProperty("online", s.lastSeen > 0 && now - s.lastSeen < ONLINE_WINDOW_MS);
                o.addProperty("lastSeen", s.lastSeen > 0 ? s.lastSeen : null);
                o.addProperty("indexedAt", s.index != null ? s.index.receivedAt : null);
                o.addProperty("itemTypes", s.index != null ? s.index.itemTypes : 0);
                o.addProperty("itemTotal", s.index != null ? s.index.itemTotal : 0);
                if (s.delivery != null) {
                    JsonObject d = new JsonObject();
                    d.addProperty("x", s.delivery.x());
                    d.addProperty("y", s.delivery.y());
                    d.addProperty("z", s.delivery.z());
                    d.addProperty("dimension", s.delivery.dimension());
                    o.add("delivery", d);
                } else {
                    o.add("delivery", com.google.gson.JsonNull.INSTANCE);
                }
                if (s.naming != null) {
                    JsonObject n = new JsonObject();
                    n.addProperty("anvil", s.naming.anvil());
                    n.addProperty("xpLevels", s.naming.xpLevels());
                    if (s.naming.anvilSource() != null) n.addProperty("anvilSource", s.naming.anvilSource());
                    else n.add("anvilSource", com.google.gson.JsonNull.INSTANCE);
                    o.add("naming", n);
                } else {
                    o.add("naming", com.google.gson.JsonNull.INSTANCE);
                }
                if (s.crafting != null) {
                    JsonObject c = new JsonObject();
                    c.addProperty("enabled", s.crafting.enabled());
                    c.addProperty("table", s.crafting.table());
                    // Only from bots that report it (an older bot's entry stays {enabled, table}).
                    if (s.crafting.tableSource() != null) c.addProperty("tableSource", s.crafting.tableSource());
                    o.add("crafting", c);
                } else {
                    o.add("crafting", com.google.gson.JsonNull.INSTANCE);
                }
                if (s.storage != null) {
                    JsonObject ds = new JsonObject();
                    ds.addProperty("autoExpand", s.storage.autoExpand());
                    ds.addProperty("chests", s.storage.chests());
                    ds.addProperty("full", s.storage.full());
                    ds.addProperty("canExpand", s.storage.canExpand());
                    ds.addProperty("blocked", s.storage.blocked());
                    o.add("deliveryStorage", ds);
                } else {
                    o.add("deliveryStorage", com.google.gson.JsonNull.INSTANCE);
                }
                o.addProperty("repair", s.repair);
                o.addProperty("orderBlocker", s.orderBlocker);
                o.addProperty("rescanPending", rescanPending(s));
                o.addProperty("rescanBlocked", s.rescanBlocked);
                long[] c = s.index != null ? s.index.containers : null;
                if (c != null) {
                    JsonObject co = new JsonObject();
                    co.addProperty("known", c[0]);
                    co.addProperty("total", c[1]);
                    co.addProperty("stale", c[2]);
                    o.add("containers", co);
                } else {
                    o.add("containers", com.google.gson.JsonNull.INSTANCE);
                }
            }
            out.add(o);
        }
        return out;
    }

    /** The stored index as served by GET /api/v1/index, or null. The bytes are shared: don't modify them. */
    public IndexBytes index(String server, String stash) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return null;
        Prepared p;
        synchronized (s) {
            p = s.index;
        }
        return p == null ? null : new IndexBytes(p.bytes, p.gzip, p.etag);
    }

    /** Every item id in one stash's index, with what the icon fetcher needs. Empty when there is no index. */
    public Map<String, IconInfo> iconIds(String server, String stash) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return Map.of();
        Prepared p;
        synchronized (s) {
            p = s.index;
        }
        return p == null ? Map.of() : toIconInfo(p);
    }

    /** Order availability data for a stash's current index, or null when it has none. */
    public OrderCounts orderCounts(String server, String stash) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return null;
        Prepared p;
        synchronized (s) {
            p = s.index;
        }
        return p == null ? null : p.orderCounts;
    }

    /**
     * Each item key's creative tab and tabOrder in a stash's current index (with the creative-tab fallback filled in),
     * for keys that have a tab. Empty when there is no index.
     */
    Map<String, CreativeTabs.Entry> tabOrder(String server, String stash) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return Map.of();
        Prepared p;
        synchronized (s) {
            p = s.index;
        }
        return p == null ? Map.of() : p.tabOrder;
    }

    /** Whether the stash's bot sent a heartbeat or index recently. */
    boolean online(String server, String stash) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return false;
        synchronized (s) {
            return s.lastSeen > 0 && System.currentTimeMillis() - s.lastSeen < ONLINE_WINDOW_MS;
        }
    }

    /** The Minecraft version of a stash's current index, or null when it has none. */
    String mcVersion(String server, String stash) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return null;
        Prepared p;
        synchronized (s) {
            p = s.index;
        }
        return p == null ? null : p.mcVersion;
    }

    /** The mcVersion of any stored index (for icons that are not in an index), or the default. */
    public String anyMcVersion() {
        for (Stash s : stashes.values()) {
            Prepared p;
            synchronized (s) {
                p = s.index;
            }
            if (p != null) return p.mcVersion;
        }
        return IconService.DEFAULT_MC;
    }

    /** The distinct Minecraft versions of the stored indexes, or the default when there are none. */
    public java.util.Set<String> mcVersions() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Stash s : stashes.values()) {
            Prepared p;
            synchronized (s) {
                p = s.index;
            }
            if (p != null) out.add(p.mcVersion);
        }
        if (out.isEmpty()) out.add(IconService.DEFAULT_MC);
        return out;
    }

    /** Whether some stored index was made with this Minecraft version. */
    public boolean hasMcVersion(String mcVersion) {
        for (Stash s : stashes.values()) {
            Prepared p;
            synchronized (s) {
                p = s.index;
            }
            if (p != null && p.mcVersion.equals(mcVersion)) return true;
        }
        return false;
    }

    /** Every item id in every stored index (the first index that has an id wins). */
    public Map<String, IconInfo> allIconIds() {
        Map<String, IconInfo> out = new LinkedHashMap<>();
        for (Stash s : stashes.values()) {
            Prepared p;
            synchronized (s) {
                p = s.index;
            }
            if (p != null) toIconInfo(p).forEach(out::putIfAbsent);
        }
        return out;
    }

    private static Map<String, IconInfo> toIconInfo(Prepared p) {
        Map<String, IconInfo> out = new LinkedHashMap<>();
        p.idNames.forEach((id, name) -> out.put(id, new IconInfo(name, p.mcVersion)));
        return out;
    }

    /** Most extra (not indexed) ids that may be allowed for icons, e.g. materials of loaded plans. */
    static final int MAX_EXTRA_ICON_IDS = 4096;
    /** Access-ordered (LRU): the least recently used id is dropped when full, so new plans always work. */
    private final Map<String, IconInfo> extraLatest = new java.util.HashMap<>(); // id -> info of the latest caller
    /** Keyed by id@mcVersion, so two stashes on different versions never share an entry. */
    private final Map<String, IconInfo> extraIcons = new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, IconInfo> eldest) {
            if (size() <= MAX_EXTRA_ICON_IDS) return false;
            String key = eldest.getKey();
            extraLatest.remove(key.substring(0, key.lastIndexOf('@')), eldest.getValue());
            return true;
        }
    };
    private volatile java.util.function.Function<String, java.util.Set<String>> packItems = v -> null;

    /** Item definition names of the complete icon set of a Minecraft version, or null when there is none (see IconPacks). */
    void setPackItems(java.util.function.Function<String, java.util.Set<String>> packItems) {
        this.packItems = packItems == null ? v -> null : packItems;
    }

    /**
     * Allows an item id that no index has (a material of a loaded plan) to get an icon. The id must be a valid
     * namespaced id; at most {@link #MAX_EXTRA_ICON_IDS} are remembered (least recently used first out, in memory
     * only). When a complete icon set exists for the version, only item ids it lists are accepted. Returns an info
     * carrying the caller's Minecraft version, or null when the id is refused.
     */
    public IconInfo allowIcon(String id, String mcVersion) {
        if (id == null || mcVersion == null) return null;
        int colon = id.indexOf(':');
        if (colon <= 0 || !IconService.ID_PART.matcher(id.substring(0, colon)).matches()
            || !IconService.ID_PART.matcher(id.substring(colon + 1)).matches()
            || !IconService.MC_VERSION.matcher(mcVersion).matches()) return null;
        java.util.Set<String> items = packItems.apply(mcVersion);
        if (items != null && !(id.startsWith("minecraft:") && items.contains(id.substring("minecraft:".length())))) return null;
        IconInfo info = new IconInfo("", mcVersion);
        synchronized (extraIcons) {
            extraIcons.put(id + "@" + mcVersion, info);
            extraLatest.put(id, info);
        }
        return info;
    }

    /** The English name and Minecraft version for an item id that appears in some stored index, or null. */
    public IconInfo iconInfo(String id) {
        for (Stash s : stashes.values()) {
            Prepared p;
            synchronized (s) {
                p = s.index;
            }
            if (p == null) continue;
            String name = p.idNames.get(id);
            if (name != null) return new IconInfo(name, p.mcVersion);
        }
        synchronized (extraIcons) {
            return extraLatest.get(id);
        }
    }

    // ---------- file names ----------

    /** sanitized server + "__" + sanitized stash + "-" + 8 hex of sha1(server \0 stash), so sanitizing can't make two stashes collide. */
    static String fileName(String server, String stash) {
        String hash = sha1Hex((server + "\0" + stash).getBytes(StandardCharsets.UTF_8)).substring(0, 8);
        return sanitize(server) + "__" + sanitize(stash) + "-" + hash + ".json";
    }

    static String sanitize(String value) {
        String lower = Objects.requireNonNullElse(value, "").toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lower.length() && sb.length() < MAX_NAME_PART; i++) {
            char c = lower.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' ? c : '_');
        }
        // A leading dot would make hidden files or "..", so swap it.
        if (sb.length() > 0 && sb.charAt(0) == '.') sb.setCharAt(0, '_');
        return sb.length() == 0 ? "_" : sb.toString();
    }

    private static String sha1Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
