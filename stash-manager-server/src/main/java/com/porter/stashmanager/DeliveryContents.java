package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * What is already in each stash's delivery storage (PROTOCOL.md, "Additions: delivery storage contents"): the bot's
 * latest report, one per (server, stash), in memory only. Never written to disk or logged, and never part of the
 * index, search, stock counts or the build planner: it only feeds the Orders tab's delivery chest preview.
 */
final class DeliveryContents {
    static final int MAX_BODY = 256 * 1024;
    static final int MAX_CHESTS = 16;
    static final int MAX_CONTENTS = 27;
    static final int MAX_NAME = 64;
    static final int MAX_COUNT = 10_000;
    static final int MAX_STASHES = 256;
    static final int MAX_FIELD = 256;
    static final Pattern HASH = Pattern.compile("^[0-9a-f]{40}$");
    static final Pattern ID = Pattern.compile("^[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,96}$");

    /** One stored report: the cleaned JSON (as the page gets it), its hash, and the summary numbers. */
    record Snapshot(JsonObject json, String hash, long readAt, long receivedAt, int chests, int usedSlots, int freeSlots,
                    int unknownChests) {
        JsonObject summary() {
            JsonObject o = new JsonObject();
            o.addProperty("readAt", readAt);
            o.addProperty("receivedAt", receivedAt);
            o.addProperty("hash", hash);
            o.addProperty("chests", chests);
            o.addProperty("usedSlots", usedSlots);
            o.addProperty("freeSlots", freeSlots);
            o.addProperty("unknownChests", unknownChests);
            return o;
        }
    }

    private final LongSupplier clock;
    private final LinkedHashMap<String, Snapshot> byStash = new LinkedHashMap<>(); // oldest report first

    DeliveryContents() {
        this(System::currentTimeMillis);
    }

    DeliveryContents(LongSupplier clock) {
        this.clock = clock;
    }

    private static String key(String server, String stash) {
        return server + "\u0000" + stash;
    }

    synchronized Snapshot get(String server, String stash) {
        return byStash.get(key(server, stash));
    }

    synchronized void put(String server, String stash, Snapshot s) {
        String k = key(server, stash);
        byStash.remove(k);
        byStash.put(k, s);
        while (byStash.size() > MAX_STASHES) {
            Iterator<String> it = byStash.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    synchronized void clear(String server, String stash) {
        byStash.remove(key(server, stash));
    }

    /**
     * The heartbeat's answer: whether the bot should send its report. False without a deliveryHash (no delivery
     * container, or an older bot); true when there is no report or its hash differs.
     */
    synchronized boolean needed(String server, String stash, String deliveryHash) {
        if (deliveryHash == null) return false;
        Snapshot s = byStash.get(key(server, stash));
        return s == null || !s.hash().equals(deliveryHash);
    }

    // ---------- validation ----------

    /** Checks a POST /api/v1/delivery body and returns it cleaned (only the known fields), or throws with the reason. */
    Snapshot parse(JsonObject o) {
        if (!(o.get("version") instanceof JsonPrimitive v) || !v.isNumber() || v.getAsDouble() != 1) throw bad("version must be 1");
        String server = text(o, "server", MAX_FIELD, true);
        String stash = text(o, "stash", MAX_FIELD, true);
        String player = text(o, "player", MAX_FIELD, false);
        String hash = text(o, "hash", 40, true);
        if (!HASH.matcher(hash).matches()) throw bad("hash must be 40 lowercase hex digits (sha1)");
        long readAt = whole(o.get("readAt"), "readAt", 0, Long.MAX_VALUE);
        JsonElement ce = o.get("chests");
        if (ce == null || !ce.isJsonArray()) throw bad("chests must be a list");
        JsonArray in = ce.getAsJsonArray();
        if (in.size() > MAX_CHESTS) throw bad("at most " + MAX_CHESTS + " chests");
        List<JsonObject> chests = new ArrayList<>();
        Set<Long> ns = new HashSet<>();
        int used = 0;
        int free = 0;
        int unknown = 0;
        for (JsonElement e : in) {
            if (!e.isJsonObject()) throw bad("every chest must be an object");
            JsonObject c = e.getAsJsonObject();
            long n = whole(c.get("n"), "chest n", 0, MAX_CHESTS - 1);
            if (!ns.add(n)) throw bad("chest n " + n + " appears twice");
            long size = whole(c.get("size"), "chest size", 27, 54);
            if (size != 27 && size != 54) throw bad("chest size must be 27 or 54");
            if (!(c.get("full") instanceof JsonPrimitive fp) || !fp.isBoolean()) throw bad("chest full must be true or false");
            long chestRead = whole(c.get("readAt"), "chest readAt", 0, Long.MAX_VALUE);
            JsonObject out = new JsonObject();
            out.addProperty("n", n);
            out.addProperty("size", size);
            out.addProperty("full", fp.getAsBoolean());
            out.addProperty("readAt", chestRead);
            JsonElement ie = c.get("items");
            if (chestRead == 0 || ie == null || ie.isJsonNull()) {
                // Never read (or left out to fit the size limit): unknown.
                out.add("items", JsonNull.INSTANCE);
                unknown++;
            } else {
                if (!ie.isJsonArray()) throw bad("chest items must be a list or null");
                JsonArray items = ie.getAsJsonArray();
                if (items.size() > size) throw bad("a chest of " + size + " can't hold " + items.size() + " stacks");
                JsonArray cleanItems = new JsonArray();
                Set<Long> slots = new HashSet<>();
                for (JsonElement ite : items) cleanItems.add(item(ite, size, slots));
                out.add("items", cleanItems);
                used += cleanItems.size();
                free += (int) size - cleanItems.size();
            }
            chests.add(out);
        }
        chests.sort((a, b) -> Long.compare(a.get("n").getAsLong(), b.get("n").getAsLong()));
        JsonObject clean = new JsonObject();
        clean.addProperty("version", 1);
        clean.addProperty("server", server);
        clean.addProperty("stash", stash);
        clean.addProperty("player", player);
        clean.addProperty("hash", hash);
        clean.addProperty("readAt", readAt);
        long now = clock.getAsLong();
        clean.addProperty("receivedAt", now);
        JsonArray arr = new JsonArray();
        chests.forEach(arr::add);
        clean.add("chests", arr);
        return new Snapshot(clean, hash, readAt, now, chests.size(), used, free, unknown);
    }

    private static JsonObject item(JsonElement e, long size, Set<Long> slots) {
        if (!e.isJsonObject()) throw bad("every item must be an object");
        JsonObject in = e.getAsJsonObject();
        long slot = whole(in.get("slot"), "item slot", 0, size - 1);
        if (!slots.add(slot)) throw bad("slot " + slot + " appears twice");
        JsonObject o = new JsonObject();
        o.addProperty("slot", slot);
        o.addProperty("id", id(in.get("id"), "item id"));
        String name = in.has("name") && !in.get("name").isJsonNull() ? text(in, "name", Integer.MAX_VALUE, false) : null;
        if (name != null) o.addProperty("name", Http.truncate(stripControls(name), MAX_NAME));
        o.addProperty("count", whole(in.get("count"), "item count", 1, MAX_COUNT));
        o.addProperty("maxStack", in.has("maxStack") && !in.get("maxStack").isJsonNull() ? whole(in.get("maxStack"), "item maxStack", 1, 99) : 64);
        if (in.has("damage") && !in.get("damage").isJsonNull()) o.addProperty("damage", whole(in.get("damage"), "item damage", 0, 1_000_000));
        if (in.has("maxDamage") && !in.get("maxDamage").isJsonNull()) o.addProperty("maxDamage", whole(in.get("maxDamage"), "item maxDamage", 0, 1_000_000));
        JsonElement sh = in.get("shulker");
        boolean shulker = false;
        if (sh != null && !sh.isJsonNull()) {
            if (!(sh instanceof JsonPrimitive sp) || !sp.isBoolean()) throw bad("item shulker must be true or false");
            shulker = sp.getAsBoolean();
        }
        if (shulker) {
            o.addProperty("shulker", true);
            JsonElement ce = in.get("contents");
            if (ce != null && !ce.isJsonNull()) {
                // The bot may leave contents out to fit the size limit: then they are unknown (absent).
                if (!ce.isJsonArray()) throw bad("item contents must be a list");
                JsonArray list = ce.getAsJsonArray();
                if (list.size() > MAX_CONTENTS) throw bad("item contents can have at most " + MAX_CONTENTS + " entries");
                JsonArray out = new JsonArray();
                for (JsonElement ent : list) {
                    if (!ent.isJsonObject()) throw bad("every contents entry must be an object");
                    JsonObject co = new JsonObject();
                    co.addProperty("id", id(ent.getAsJsonObject().get("id"), "contents id"));
                    co.addProperty("count", whole(ent.getAsJsonObject().get("count"), "contents count", 1, MAX_COUNT));
                    out.add(co);
                }
                o.add("contents", out);
            }
        }
        return o;
    }

    private static String id(JsonElement e, String what) {
        if (!(e instanceof JsonPrimitive p) || !p.isString()) throw bad(what + " must be text");
        String id = p.getAsString().toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) throw bad(what + " must be namespace:path");
        return id;
    }

    private static String text(JsonObject o, String key, int max, boolean required) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            if (required) throw bad(key + " is required");
            return null;
        }
        if (!(e instanceof JsonPrimitive p) || !p.isString()) throw bad(key + " must be text");
        String s = p.getAsString();
        if (required && s.isEmpty()) throw bad(key + " must not be empty");
        if (s.length() > max) throw bad(key + " is too long");
        return s;
    }

    private static long whole(JsonElement e, String what, long min, long max) {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber()) throw bad(what + " must be a whole number");
        BigDecimal d;
        try {
            d = p.getAsBigDecimal();
        } catch (NumberFormatException ex) {
            throw bad(what + " must be a whole number");
        }
        if (d.stripTrailingZeros().scale() > 0) throw bad(what + " must be a whole number");
        if (d.compareTo(BigDecimal.valueOf(min)) < 0 || d.compareTo(BigDecimal.valueOf(max)) > 0) throw bad(what + " is out of range");
        return d.longValueExact();
    }

    /** Control characters are dropped (a name is one line of plain text). */
    static String stripControls(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(c -> {
            if (!Character.isISOControl(c) && c != 0x2028 && c != 0x2029) sb.appendCodePoint(c);
        });
        return sb.toString();
    }

    private static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException(message);
    }

    /** For tests: how many reports are held. */
    synchronized int size() {
        return byStash.size();
    }

    synchronized Map<String, Snapshot> all() {
        return new LinkedHashMap<>(byStash);
    }
}
