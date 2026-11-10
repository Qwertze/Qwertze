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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The stash area per (server, stash) (AREA-CONTRACT.md): the box the bots keep their stash in, set from the website or taken
 * over from a bot. Stored in data/area.json, written atomically; the previous file is copied to area.json.bak first.
 * Loaded like kits.json and craft.json: a damaged stash entry is kept as it is and only that stash answers 503; when the file
 * and its backup are both unreadable the store is disabled and never writes.
 */
final class AreaStore {
    static final int MAX_STASHES = 64;
    static final int MAX_W = 256, MAX_H = 128, MAX_D = 256;
    static final int OVERWORLD_MIN_Y = -64, OVERWORLD_MAX_Y = 319;
    static final int NETHER_MIN_Y = 0, NETHER_MAX_Y = 255;
    static final int FAR_BLOCKS = 256;
    static final Pattern DIMENSION = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,100}$");

    /** An inclusive box and its dimension. */
    record Area(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String dimension) {
        int w() {
            return maxX - minX + 1;
        }

        int h() {
            return maxY - minY + 1;
        }

        int d() {
            return maxZ - minZ + 1;
        }

        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            JsonArray box = new JsonArray();
            for (int v : new int[]{minX, minY, minZ, maxX, maxY, maxZ}) box.add(v);
            o.add("box", box);
            o.addProperty("dimension", dimension);
            return o;
        }

        /** Horizontal distance in blocks from this box to a point (0 inside the footprint). */
        double horizontalTo(int x, int z) {
            double dx = Math.max(0, Math.max(minX - x, x - maxX));
            double dz = Math.max(0, Math.max(minZ - z, z - maxZ));
            return Math.hypot(dx, dz);
        }

        /** Horizontal distance between two boxes' footprints. */
        double horizontalTo(Area o) {
            double dx = Math.max(0, Math.max(minX - o.maxX, o.minX - maxX));
            double dz = Math.max(0, Math.max(minZ - o.maxZ, o.minZ - maxZ));
            return Math.hypot(dx, dz);
        }
    }

    static String normalizeDimension(String d) {
        return d == null ? null : d.contains(":") ? d : "minecraft:" + d;
    }

    /** The Y range a dimension allows. */
    static int[] yRange(String dimension) {
        String d = normalizeDimension(dimension);
        return "minecraft:the_nether".equals(d) || "minecraft:the_end".equals(d)
            ? new int[]{NETHER_MIN_Y, NETHER_MAX_Y} : new int[]{OVERWORLD_MIN_Y, OVERWORLD_MAX_Y};
    }

    /**
     * Reads {box: [6 whole numbers], dimension} and checks the contract: order of the corners, |x| and |z| up to 30,000,000,
     * the size caps and the Y limits of the dimension. Throws IllegalArgumentException with the reason for a 400.
     */
    static Area parse(JsonElement e) {
        if (e == null || !e.isJsonObject()) throw new IllegalArgumentException("area must be {box, dimension} or null");
        JsonObject o = e.getAsJsonObject();
        if (!o.has("box") || !o.get("box").isJsonArray() || o.getAsJsonArray("box").size() != 6) {
            throw new IllegalArgumentException("area.box must be [minX, minY, minZ, maxX, maxY, maxZ]");
        }
        long[] v = new long[6];
        for (int i = 0; i < 6; i++) {
            JsonElement x = o.getAsJsonArray("box").get(i);
            if (!(x instanceof JsonPrimitive p) || !p.isNumber() || p.getAsString().length() > 12) {
                throw new IllegalArgumentException("area.box must hold six whole numbers");
            }
            try {
                v[i] = p.getAsBigDecimal().longValueExact();
            } catch (ArithmeticException | NumberFormatException ex) {
                throw new IllegalArgumentException("area.box must hold six whole numbers");
            }
        }
        String dim = AddonRoutes.string(o, "dimension");
        if (dim == null || !DIMENSION.matcher(dim).matches()) throw new IllegalArgumentException("area.dimension must be a dimension like minecraft:overworld");
        if (v[0] > v[3] || v[1] > v[4] || v[2] > v[5]) throw new IllegalArgumentException("area.box: each minimum must be at most its maximum");
        for (int i : new int[]{0, 2, 3, 5}) {
            if (Math.abs(v[i]) > 30_000_000) throw new IllegalArgumentException("area.box: x and z must be within 30,000,000 of the origin");
        }
        long w = v[3] - v[0] + 1, h = v[4] - v[1] + 1, d = v[5] - v[2] + 1;
        if (w > MAX_W || h > MAX_H || d > MAX_D) {
            throw new IllegalArgumentException("that area is " + w + "×" + h + "×" + d + "; the largest is " + MAX_W + "×" + MAX_H + "×" + MAX_D);
        }
        int[] y = yRange(dim);
        if (v[1] < y[0] || v[4] > y[1]) {
            throw new IllegalArgumentException("that area's height is " + v[1] + " to " + v[4] + "; " + dim + " allows " + y[0] + " to " + y[1]);
        }
        return new Area((int) v[0], (int) v[1], (int) v[2], (int) v[3], (int) v[4], (int) v[5], dim);
    }

    /** SHA-1 (lowercase hex) of "area-v1\n" + (area ? "minX,minY,minZ,maxX,maxY,maxZ,dimension" : "") + "\n". */
    static String hash(Area a) {
        String s = "area-v1\n" + (a == null ? "" : a.minX() + "," + a.minY() + "," + a.minZ() + "," + a.maxX() + "," + a.maxY() + "," + a.maxZ() + "," + a.dimension()) + "\n";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One stash's area. by is "website" or "bot"; user is the website user's name (null for a bot); at is epoch ms (0 none). */
    record Settings(long rev, String hash, Area area, String by, String user, long updatedAt) {
        boolean set() {
            return area != null;
        }
    }

    static final class DisabledException extends RuntimeException {
        DisabledException() {
            super("the area settings file can't be read");
        }
    }

    static final class LimitException extends RuntimeException {
        LimitException(String message) {
            super(message);
        }
    }

    private record Entry(long rev, Area area, String by, String user, long updatedAt) {
    }

    private final Path file;
    private final Path backup;
    private final LongSupplier clock;
    private final LinkedHashMap<IndexStore.StashKey, Entry> entries = new LinkedHashMap<>();
    private boolean disabled;
    /** The stable anchor of each stash (x, y, z): where its stash is, set once and never moved by a bot (see {@link #setHomeIfAbsent}). */
    private final LinkedHashMap<IndexStore.StashKey, Home> homes = new LinkedHashMap<>();
    /** When the owner last reset each stash's home point (the once-an-hour cooldown survives a restart). */
    private final LinkedHashMap<IndexStore.StashKey, Long> homeResets = new LinkedHashMap<>();
    private final java.util.List<JsonObject> damaged = new java.util.ArrayList<>();
    private final java.util.Set<IndexStore.StashKey> damagedKeys = new java.util.HashSet<>();
    private boolean loadedFromBackup;

    AreaStore(Path dataDir) {
        this(dataDir, System::currentTimeMillis);
    }

    AreaStore(Path dataDir, LongSupplier clock) {
        this.file = dataDir.resolve("area.json");
        this.backup = dataDir.resolve("area.json.bak");
        this.clock = clock;
    }

    // ---------- loading ----------

    /**
     * A stash's home point and the dimension it was set in. provisional: it came from a bot's first report (a kit report's
     * containers or the bot's own first area), not from the delivery container; it is replaced once, when a delivery
     * container first appears, and dropped when the stash's dimension changes.
     */
    record Home(int x, int y, int z, boolean provisional, String dimension) {
        int[] pos() {
            return new int[]{x, y, z};
        }

        /** The stash's dimension is no longer the one the home was set in. */
        boolean staleFor(String stashDimension) {
            return dimension != null && stashDimension != null && !dimension.equals(stashDimension);
        }
    }

    private record Loaded(Map<IndexStore.StashKey, Entry> entries, java.util.List<JsonObject> damaged, java.util.Set<IndexStore.StashKey> damagedKeys,
                          Map<IndexStore.StashKey, Home> homes, Map<IndexStore.StashKey, Long> resets) {
    }

    private void take(Loaded l) {
        entries.putAll(l.entries());
        homes.putAll(l.homes());
        // A time more than a day ahead of this clock is not a cooldown anyone set here (a corrupt or tampered file): dropped.
        long limit = clock.getAsLong() + 24L * 60 * 60 * 1000;
        l.resets().forEach((k, at) -> {
            if (at <= limit) homeResets.put(k, at);
        });
        damaged.addAll(l.damaged());
        damagedKeys.addAll(l.damagedKeys());
        for (JsonObject o : l.damaged()) System.err.println("Stash areas: one stash entry in " + file.getFileName() + " can't be read and is left as it is (areas are off for that stash).");
    }

    synchronized void load() {
        entries.clear();
        homes.clear();
        homeResets.clear();
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
                System.err.println("Stash areas restored from " + backup.getFileName() + " (" + file.getFileName() + " was missing).");
            } catch (IOException | RuntimeException e) {
                disabled = true;
                entries.clear();
                System.err.println("Stash areas are off: " + file.getFileName() + " is missing and its backup can't be read; it will not be overwritten.");
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
            System.err.println("Stash areas restored from " + backup.getFileName() + ".");
        } catch (IOException | RuntimeException e) {
            disabled = true;
            entries.clear();
            System.err.println("Stash areas are off: " + file.getFileName() + " and its backup can't be read, and neither will be overwritten.");
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
        Map<IndexStore.StashKey, Home> homes = new LinkedHashMap<>();
        if (root.has("homes") && root.get("homes").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("homes")) {
                try {
                    JsonObject h = e.getAsJsonObject();
                    String s = h.get("server").getAsString(), t = h.get("stash").getAsString();
                    long x = h.get("x").getAsBigDecimal().longValueExact(), y = h.get("y").getAsBigDecimal().longValueExact(), z = h.get("z").getAsBigDecimal().longValueExact();
                    if (!KitNames.wellFormed(s) || !KitNames.wellFormed(t) || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || y < -2048 || y > 2048) continue;
                    boolean prov = h.has("provisional") && h.get("provisional").isJsonPrimitive() && h.get("provisional").getAsBoolean();
                    String hd = h.has("dimension") && h.get("dimension").isJsonPrimitive() ? h.get("dimension").getAsString() : null;
                    if (hd != null && !DIMENSION.matcher(hd).matches()) hd = null;
                    if (homes.size() < MAX_STASHES) homes.put(new IndexStore.StashKey(s, t), new Home((int) x, (int) y, (int) z, prov, hd));
                } catch (RuntimeException ex) {
                    // an unreadable home is dropped: it is set again from the delivery container
                }
            }
        }
        Map<IndexStore.StashKey, Long> resets = new LinkedHashMap<>();
        if (root.has("homeResets") && root.get("homeResets").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("homeResets")) {
                try {
                    JsonObject h = e.getAsJsonObject();
                    String s = h.get("server").getAsString(), t = h.get("stash").getAsString();
                    long at = h.get("at").getAsLong();
                    if (KitNames.wellFormed(s) && KitNames.wellFormed(t) && at > 0 && resets.size() < MAX_STASHES) resets.put(new IndexStore.StashKey(s, t), at);
                } catch (RuntimeException ex) {
                    // dropped: the cooldown starts over
                }
            }
        }
        return new Loaded(out, bad, badKeys, homes, resets);
    }

    private static Entry entry(JsonObject o) throws IOException {
        long rev = o.get("rev").getAsBigDecimal().longValueExact();
        if (rev < 0) throw new IOException("bad rev");
        Area area = null;
        JsonElement ae = o.get("area");
        if (ae != null && !ae.isJsonNull()) area = parse(ae);
        String by = o.get("by").getAsString();
        if (!by.equals("website") && !by.equals("bot")) throw new IOException("bad by");
        String user = o.has("user") && o.get("user").isJsonPrimitive() ? o.get("user").getAsString() : null;
        long at = o.has("updatedAt") && o.get("updatedAt").isJsonPrimitive() ? o.get("updatedAt").getAsLong() : 0;
        return new Entry(rev, area, by, user, at);
    }

    // ---------- reads ----------

    synchronized boolean disabled() {
        return disabled;
    }

    /** Whether the store holds an entry (or an unreadable one) for the stash. */
    synchronized boolean knows(String server, String stash) {
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        return entries.containsKey(k) || damagedKeys.contains(k);
    }

    /** The stored settings, or null when the stash has none yet. Throws DisabledException when off or damaged for the stash. */
    synchronized Settings find(String server, String stash) {
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        if (disabled || damagedKeys.contains(k)) throw new DisabledException();
        Entry e = entries.get(k);
        return e == null ? null : settings(e);
    }

    private static Settings settings(Entry e) {
        return new Settings(e.rev, hash(e.area), e.area, e.by, e.user, e.updatedAt);
    }

    // ---------- changes ----------

    // ---------- the home anchor ----------

    /** The stash's home, or null. */
    synchronized Home home(String server, String stash) {
        return homes.get(new IndexStore.StashKey(server, stash));
    }

    /** Sets the home when the stash has none; returns the home it has afterwards. Never moves an existing one. */
    synchronized Home setHomeIfAbsent(String server, String stash, Home home) throws IOException {
        if (disabled) throw new DisabledException();
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        Home old = homes.get(k);
        if (old != null) return old;
        return putHome(k, home);
    }

    /**
     * Replaces a provisional home by one from the delivery container (once: the new one is not provisional); anything else is
     * left as it is. Returns the home afterwards.
     */
    synchronized Home replaceProvisional(String server, String stash, Home home) throws IOException {
        if (disabled) throw new DisabledException();
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        Home old = homes.get(k);
        if (old == null || !old.provisional() || home.provisional()) return old;
        return putHome(k, home);
    }

    /** When the stash's home was last reset by the owner (0: never, or too long ago to matter). */
    synchronized long lastHomeReset(String server, String stash) {
        Long at = homeResets.get(new IndexStore.StashKey(server, stash));
        return at == null ? 0 : at;
    }

    /**
     * Starts the cooldown atomically: refused (-2) when the last reset is less than cooldownMs ago, else the time is stored
     * (in area.json) and the previous value (0: none) is answered, for {@link #rollbackHomeReset}.
     */
    synchronized long reserveHomeReset(String server, String stash, long now, long cooldownMs) throws IOException {
        if (disabled) throw new DisabledException();
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        Long last = homeResets.get(k);
        if (last != null && now - last < cooldownMs) return -2;
        homeResets.values().removeIf(at -> now - at >= cooldownMs && now - at > 0);
        homeResets.put(k, now);
        try {
            write(entries, homes);
        } catch (IOException | RuntimeException e) {
            if (last == null) homeResets.remove(k);
            else homeResets.put(k, last);
            throw e;
        }
        return last == null ? 0 : last;
    }

    /** Puts the cooldown back to what it was (the reset it was reserved for failed). */
    synchronized void rollbackHomeReset(String server, String stash, long previous) {
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        if (previous <= 0) homeResets.remove(k);
        else homeResets.put(k, previous);
        try {
            write(entries, homes);
        } catch (IOException | RuntimeException e) {
            // memory is right; the file catches up with the next write
        }
    }

    /** Sets the home whatever it was (an explicit reset by the owner). */
    synchronized Home resetHome(String server, String stash, Home home) throws IOException {
        if (disabled) throw new DisabledException();
        return putHome(new IndexStore.StashKey(server, stash), home);
    }

    /** Drops a home (a provisional one whose dimension changed). */
    synchronized void clearHome(String server, String stash) throws IOException {
        if (disabled) throw new DisabledException();
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        if (!homes.containsKey(k)) return;
        LinkedHashMap<IndexStore.StashKey, Home> copy = new LinkedHashMap<>(homes);
        copy.remove(k);
        write(entries, copy);
        homes.remove(k);
    }

    private Home putHome(IndexStore.StashKey k, Home h) throws IOException {
        if (!KitNames.wellFormed(k.server()) || !KitNames.wellFormed(k.stash())) throw new LimitException("invalid stash name");
        if (!homes.containsKey(k) && homes.size() >= MAX_STASHES) throw new LimitException("area homes exist for too many stashes (at most " + MAX_STASHES + ")");
        LinkedHashMap<IndexStore.StashKey, Home> copy = new LinkedHashMap<>(homes);
        copy.put(k, h);
        write(entries, copy);
        homes.put(k, h);
        return h;
    }

    /** A save made against an older revision than the stored one (the page shows "changed meanwhile"). */
    static final class StaleException extends RuntimeException {
        StaleException() {
            super("the area changed meanwhile");
        }
    }

    /** Stores the area only when the stash has no entry yet; otherwise returns what is there (adoption without a race). */
    synchronized Settings setIfAbsent(String server, String stash, Area area, String by, String user) throws IOException {
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        if (!disabled && !damagedKeys.contains(k) && entries.containsKey(k)) return settings(entries.get(k));
        return set(server, stash, area, by, user);
    }

    /** The same as {@link #set} but refused (StaleException) unless the stored revision is expectRev (0: no entry). */
    synchronized Settings setChecked(String server, String stash, Area area, String by, String user, long expectRev) throws IOException {
        if (disabled) throw new DisabledException();
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        if (damagedKeys.contains(k)) throw new DisabledException();
        Entry old = entries.get(k);
        if ((old == null ? 0 : old.rev) != expectRev) throw new StaleException();
        return set(server, stash, area, by, user);
    }

    /**
     * Stores the area (null clears it) unless the stash already holds exactly that; returns the settings after. by is
     * "website" or "bot".
     */
    synchronized Settings set(String server, String stash, Area area, String by, String user) throws IOException {
        if (disabled) throw new DisabledException();
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        if (damagedKeys.contains(k)) throw new DisabledException();
        if (!KitNames.wellFormed(server) || !KitNames.wellFormed(stash)) throw new LimitException("invalid stash name");
        Entry old = entries.get(k);
        if (old != null && java.util.Objects.equals(old.area, area)) return settings(old);
        if (old == null && entries.size() + damaged.size() >= MAX_STASHES) {
            throw new LimitException("area settings exist for too many stashes (at most " + MAX_STASHES + ")");
        }
        Entry next = new Entry(old == null ? 1 : old.rev + 1, area, by, user, clock.getAsLong());
        Map<IndexStore.StashKey, Entry> copy = new LinkedHashMap<>(entries);
        copy.put(k, next);
        write(copy, homes);
        entries.put(k, next);
        return settings(next);
    }

    /** Drops a stash's entry (the stash was forgotten). */
    synchronized void forget(String server, String stash) throws IOException {
        if (disabled) return;
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        if ((!entries.containsKey(k) && !homes.containsKey(k) && !homeResets.containsKey(k)) || damagedKeys.contains(k)) return;
        Map<IndexStore.StashKey, Entry> copy = new LinkedHashMap<>(entries);
        copy.remove(k);
        LinkedHashMap<IndexStore.StashKey, Home> hcopy = new LinkedHashMap<>(homes);
        hcopy.remove(k);
        Long resetAt = homeResets.remove(k);
        try {
            write(copy, hcopy);
        } catch (IOException | RuntimeException e) {
            if (resetAt != null) homeResets.put(k, resetAt);
            throw e;
        }
        entries.remove(k);
        homes.remove(k);
    }

    private void write(Map<IndexStore.StashKey, Entry> all, Map<IndexStore.StashKey, Home> homeMap) throws IOException {
        JsonArray list = new JsonArray();
        for (Map.Entry<IndexStore.StashKey, Entry> e : all.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("server", e.getKey().server());
            o.addProperty("stash", e.getKey().stash());
            o.addProperty("rev", e.getValue().rev);
            o.add("area", e.getValue().area == null ? com.google.gson.JsonNull.INSTANCE : e.getValue().area.toJson());
            o.addProperty("by", e.getValue().by);
            if (e.getValue().user != null) o.addProperty("user", e.getValue().user);
            o.addProperty("updatedAt", e.getValue().updatedAt);
            list.add(o);
        }
        for (JsonObject raw : damaged) list.add(raw);
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("stashes", list);
        JsonArray hs = new JsonArray();
        for (Map.Entry<IndexStore.StashKey, Home> e : homeMap.entrySet()) {
            JsonObject h = new JsonObject();
            h.addProperty("server", e.getKey().server());
            h.addProperty("stash", e.getKey().stash());
            h.addProperty("x", e.getValue().x());
            h.addProperty("y", e.getValue().y());
            h.addProperty("z", e.getValue().z());
            h.addProperty("provisional", e.getValue().provisional());
            if (e.getValue().dimension() != null) h.addProperty("dimension", e.getValue().dimension());
            hs.add(h);
        }
        root.add("homes", hs);
        JsonArray rs = new JsonArray();
        for (Map.Entry<IndexStore.StashKey, Long> e : homeResets.entrySet()) {
            JsonObject h = new JsonObject();
            h.addProperty("server", e.getKey().server());
            h.addProperty("stash", e.getKey().stash());
            h.addProperty("at", e.getValue());
            rs.add(h);
        }
        root.add("homeResets", rs);
        if (Files.exists(file) && !loadedFromBackup) {
            boolean good;
            try {
                good = read(file).damaged().isEmpty();
            } catch (IOException | RuntimeException e) {
                good = false;
            }
            if (good) {
                byte[] old = Files.readAllBytes(file);
                for (int attempt = 1; ; attempt++) {
                    try {
                        AtomicFiles.write(backup, old, true);
                        break;
                    } catch (java.nio.file.AccessDeniedException e) {
                        // Windows: a virus scanner or indexer can hold the backup for a moment while it is replaced.
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
        }
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        for (int attempt = 1; ; attempt++) {
            try {
                AtomicFiles.write(file, bytes, true);
                break;
            } catch (java.nio.file.AccessDeniedException e) {
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
