package com.porter.qwerte.stash;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The kit report (KIT-CONTRACT.md, "POST /api/v1/kits/report"): the containers of the stash with how much room each has and whether it
 * can take kits, where each kit shulker is, and which ones the bot carries. Built from plain data ({@link StashIndex#kitReport} reads
 * the stash and the inventory into it), so it needs no game. Names and labels are cut and cleaned here, the server does it again.
 * Mirrors the plugin's KitReport.
 */
public final class KitReport {
    private KitReport() {
    }

    public static final int MAX_CONTAINERS = 4096;
    public static final int MAX_KITS = 4096;
    public static final int MAX_CARRIED = 36;
    public static final int MAX_LABEL = 32;
    public static final int MAX_NAME = 64;
    /** The server takes 512 KiB; stay under it. */
    public static final int MAX_BYTES = 480_000;

    private static final Gson GSON_NULLS = new GsonBuilder().serializeNulls().create();

    /**
     * One shulker box and its kit score. Only boxes that score as kits are reported.
     * @param name  the plain custom name, null when it has none
     */
    public record Shulker(String name, KitScore.Result score) {
    }

    /**
     * One container the index covers (delivery containers already left out).
     * @param id        the container id: the box's smallest part
     * @param label     the first sign line, or null
     * @param size      27 or 54
     * @param free      empty slots (0 when never read)
     * @param pickable  read, sortable, not excluded, not a drop-off, not a delivery container
     * @param readAt    epoch ms, 0 unknown
     * @param shulkers  the shulker boxes in it, each with its score
     */
    public record BoxInfo(String id, String label, int size, int free, boolean pickable, long readAt, List<Shulker> shulkers) {
    }

    /** A finished report: the JSON body, the hash of its content (the readAt fields left out) and what is in it. */
    public record Built(String server, String stash, String player, String hash, byte[] json, int containers, int kits, int carried) {
    }

    private record Container(String id, String label, int size, int free, boolean pickable, long readAt, boolean holdsKits) {
    }

    /** One (key, container) entry, key null for unnamed kits; the score is the highest total's, minTotal the lowest total. */
    private record Kit(String key, String name, String container, int count, KitScore.Result score, int minTotal) {
        Kit add(String otherName, KitScore.Result r, int cap) {
            String n = name == null ? otherName : otherName == null ? name : otherName.compareTo(name) < 0 ? otherName : name;
            return new Kit(key, n, container, Math.min(cap, count + 1), higher(r, score) ? r : score, Math.min(minTotal, r.total()));
        }
    }

    /** Whether a is the better breakdown to show: the higher total, then the higher name, mix, full and op points. */
    static boolean higher(KitScore.Result a, KitScore.Result b) {
        if (a.total() != b.total()) return a.total() > b.total();
        if (a.name() != b.name()) return a.name() > b.name();
        if (a.mix() != b.mix()) return a.mix() > b.mix();
        if (a.full() != b.full()) return a.full() > b.full();
        return a.op() > b.op();
    }

    /**
     * @param boxes   the containers in scope, in stash order
     * @param carried the shulker boxes in the bot's inventory (only kits are looked at)
     */
    public static Built build(String server, String stash, String player, List<BoxInfo> boxes, List<Shulker> carried) {
        // Each distinct custom name is normalised once per report
        Map<String, String> memo = new java.util.HashMap<>();
        Map<String, Container> containers = new LinkedHashMap<>();
        Map<String, Kit> kits = new LinkedHashMap<>();
        for (BoxInfo box : boxes) {
            String id = box.id();
            if (!KitSettings.validId(id) || containers.containsKey(id)) continue;
            int size = box.size() >= 54 ? 54 : 27;
            boolean holds = false;
            for (Shulker sh : box.shulkers()) {
                if (!sh.score().kit()) continue;
                holds = true;
                String key = normalized(memo, sh.name());
                String k = (key == null ? "" : "k" + key) + "	" + id;
                String shown = key == null ? null : name(sh.name(), key);
                Kit old = kits.get(k);
                kits.put(k, old == null ? new Kit(key, shown, id, 1, sh.score(), sh.score().total()) : old.add(shown, sh.score(), 54));
            }
            String label = box.label() == null ? null : clean(box.label(), MAX_LABEL);
            containers.put(id, new Container(id, label == null || label.isEmpty() ? null : label, size, Math.max(0, Math.min(size, box.free())), box.pickable(),
                Math.max(0, box.readAt()), holds));
        }

        Map<String, Kit> carriedKits = new LinkedHashMap<>();
        for (Shulker sh : carried) {
            if (!sh.score().kit()) continue;
            String key = normalized(memo, sh.name());
            String k = key == null ? "" : "k" + key;
            String shown = key == null ? null : name(sh.name(), key);
            Kit old = carriedKits.get(k);
            carriedKits.put(k, old == null ? new Kit(key, shown, "", 1, sh.score(), sh.score().total()) : old.add(shown, sh.score(), MAX_CARRIED));
        }

        List<Container> list = new ArrayList<>(containers.values());
        List<Kit> kitList = new ArrayList<>(kits.values());
        // Over the limits, the ones that matter stay: those holding kits first, then those that can take them
        list.sort(Comparator.comparing((Container c) -> !c.holdsKits()).thenComparing(c -> !c.pickable()).thenComparing(Container::id));
        int limit = Math.min(MAX_CONTAINERS, list.size());
        Built built = assemble(server, stash, player, list, limit, kitList, new ArrayList<>(carriedKits.values()));
        while (built.json().length > MAX_BYTES && limit > 0) {
            limit = limit * 3 / 4;
            built = assemble(server, stash, player, list, limit, kitList, new ArrayList<>(carriedKits.values()));
        }
        return built;
    }

    private static JsonObject scoreJson(KitScore.Result r) {
        JsonObject o = new JsonObject();
        o.addProperty("total", r.total());
        o.addProperty("name", r.name());
        o.addProperty("mix", r.mix());
        o.addProperty("full", r.full());
        o.addProperty("op", r.op());
        return o;
    }

    private static final Comparator<Kit> BY_KEY = Comparator.comparing((Kit k) -> k.key() == null ? "" : "k" + k.key());

    private static Built assemble(String server, String stash, String player, List<Container> priority, int limit, List<Kit> allKits,
                                  List<Kit> carriedKits) {
        List<Container> list = new ArrayList<>(priority.subList(0, limit));
        list.sort(Comparator.comparing(Container::id));
        Set<String> ids = new HashSet<>();
        for (Container c : list) ids.add(c.id());

        List<Kit> kitList = new ArrayList<>();
        for (Kit k : allKits) if (ids.contains(k.container())) kitList.add(k);
        kitList.sort(BY_KEY.thenComparing(Kit::container));
        if (kitList.size() > MAX_KITS) kitList = new ArrayList<>(kitList.subList(0, MAX_KITS));

        JsonArray containers = new JsonArray();
        long newest = 0;
        List<Long> reads = new ArrayList<>();
        for (Container c : list) {
            JsonObject o = new JsonObject();
            o.addProperty("id", c.id());
            if (c.label() == null) o.add("label", JsonNull.INSTANCE);
            else o.addProperty("label", c.label());
            o.addProperty("size", c.size());
            o.addProperty("free", c.free());
            o.addProperty("pickable", c.pickable());
            containers.add(o);
            reads.add(c.readAt());
            newest = Math.max(newest, c.readAt());
        }
        JsonArray kits = new JsonArray();
        for (Kit k : kitList) {
            JsonObject o = new JsonObject();
            o.addProperty("key", k.key());
            o.addProperty("name", k.name());
            o.addProperty("container", k.container());
            o.addProperty("count", k.count());
            o.add("score", scoreJson(k.score()));
            o.addProperty("minTotal", k.minTotal());
            kits.add(o);
        }
        JsonArray carried = new JsonArray();
        List<Kit> sortedCarried = new ArrayList<>(carriedKits);
        sortedCarried.sort(BY_KEY);
        for (Kit k : sortedCarried) {
            if (carried.size() >= MAX_CARRIED) break;
            JsonObject o = new JsonObject();
            o.addProperty("key", k.key());
            o.addProperty("name", k.name());
            o.addProperty("count", k.count());
            o.add("score", scoreJson(k.score()));
            carried.add(o);
        }

        // The hash leaves every readAt out: a read that found nothing changed is not a change
        JsonObject body = new JsonObject();
        body.add("containers", containers);
        body.add("kits", kits);
        body.add("carried", carried);
        String hash = sha1("v1\n" + GSON_NULLS.toJson(body));
        for (int i = 0; i < containers.size(); i++) containers.get(i).getAsJsonObject().addProperty("readAt", reads.get(i));

        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("server", server);
        root.addProperty("stash", stash);
        root.addProperty("player", player);
        root.addProperty("hash", hash);
        root.addProperty("readAt", newest);
        root.add("containers", containers);
        root.add("kits", kits);
        root.add("carried", carried);
        return new Built(server, stash, player, hash, GSON_NULLS.toJson(root).getBytes(StandardCharsets.UTF_8), containers.size(), kits.size(), carried.size());
    }

    private static String normalized(Map<String, String> memo, String text) {
        if (text == null) return null;
        if (memo.containsKey(text)) return memo.get(text);
        // A name with an unpaired surrogate cannot be a key: that kit is unnamed
        String key = KitNames.keyOf(text);
        memo.put(text, key);
        return key;
    }

    private static String name(String text, String key) {
        String s = clean(text, MAX_NAME);
        return s.isEmpty() ? clean(key, MAX_NAME) : s;
    }

    /** Control and format characters dropped, tab and line breaks as a space, cut to {@code max} UTF-16 units (ending in an ellipsis when cut). */
    static String clean(String text, int max) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(text.length(), max + 8));
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (cp == '\t' || cp == '\n' || cp == '\r') cp = ' ';
            else if (type == Character.CONTROL || type == Character.FORMAT) continue;
            sb.appendCodePoint(cp);
            if (sb.length() > max * 2) break;
        }
        String s = sb.toString().strip();
        if (s.length() <= max) return s;
        int end = max - 1;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end).stripTrailing() + "…";
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
