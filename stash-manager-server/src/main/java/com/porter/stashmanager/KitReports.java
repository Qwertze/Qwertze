package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * What each bot last reported about its kit shulkers (POST /api/v1/kits/report, KIT-CONTRACT.md), and what its last
 * heartbeat said about kit settings. In memory only, never written to disk or logged: container labels and kit names
 * are text a player typed, and the ids are positions.
 */
final class KitReports {
    static final int MAX_BODY = 512 * 1024;
    static final int MAX_CONTAINERS = 4096;
    static final int MAX_KITS = 4096;
    static final int MAX_CARRIED = 36;
    static final int MAX_LABEL = 32;
    static final int MAX_NAME = 64;
    static final int MAX_STASHES = 64;
    static final int MAX_FIELD = 256;
    static final Pattern HASH = Pattern.compile("^[0-9a-f]{40}$");

    record Container(String id, String label, int size, int free, boolean pickable, long readAt) {
    }

    /** The points a box got (KIT-CONTRACT.md v1.1): total 0..40, each part 0..10. The bots compute it; this only shows it. */
    record Score(int total, int name, int mix, int full, int op) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("total", total);
            o.addProperty("name", name);
            o.addProperty("mix", mix);
            o.addProperty("full", full);
            o.addProperty("op", op);
            return o;
        }
    }

    /** key and name are null for unnamed kits; score is the highest-total box of the entry, minTotal the lowest total. */
    record Stored(String key, String name, String container, int count, Score score, int minTotal) {
    }

    record Carried(String key, String name, int count, Score score) {
    }

    record Report(String hash, long receivedAt, long readAt, List<Container> containers, List<Stored> kits, List<Carried> carried) {
        Container container(String id) {
            for (Container c : containers) if (c.id().equals(id)) return c;
            return null;
        }
    }

    /** What a stash's latest heartbeat said: whether it had a kitsHash key, which hash, and when. */
    record Beat(boolean supports, String hash, long at) {
    }

    private final LongSupplier clock;
    private final LinkedHashMap<IndexStore.StashKey, Report> reports = new LinkedHashMap<>(); // oldest first
    private final Map<IndexStore.StashKey, Beat> beats = new HashMap<>();

    KitReports() {
        this(System::currentTimeMillis);
    }

    KitReports(LongSupplier clock) {
        this.clock = clock;
    }

    synchronized Report get(String server, String stash) {
        return reports.get(new IndexStore.StashKey(server, stash));
    }

    synchronized void put(String server, String stash, Report r) {
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        reports.remove(k);
        reports.put(k, r);
        while (reports.size() > MAX_STASHES) {
            Iterator<IndexStore.StashKey> it = reports.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    synchronized void beat(String server, String stash, boolean supports, String hash) {
        beats.put(new IndexStore.StashKey(server, stash), new Beat(supports, hash, clock.getAsLong()));
        if (beats.size() > 1024) {
            long now = clock.getAsLong();
            beats.values().removeIf(b -> now - b.at() > 60 * 60 * 1000L);
        }
    }

    synchronized Beat beat(String server, String stash) {
        return beats.get(new IndexStore.StashKey(server, stash));
    }

    /** Whether the bot should send its report: it has one (a hash) and the server's is missing or differs. */
    synchronized boolean needed(String server, String stash, String reportHash) {
        if (reportHash == null) return false;
        Report r = reports.get(new IndexStore.StashKey(server, stash));
        return r == null || !r.hash().equals(reportHash);
    }

    long now() {
        return clock.getAsLong();
    }

    // ---------- validation ----------

    /** Checks a POST /api/v1/kits/report body and returns it cleaned, or throws with the reason. */
    Report parse(JsonObject o) {
        whole(o.get("version"), "version", 1, 1);
        String hash = text(o, "hash", 40, true);
        if (!HASH.matcher(hash).matches()) throw bad("hash must be 40 lowercase hex digits");
        long readAt = whole(o.get("readAt"), "readAt", 0, Long.MAX_VALUE);
        JsonElement ce = o.get("containers");
        if (ce == null || !ce.isJsonArray()) throw bad("containers must be a list");
        if (ce.getAsJsonArray().size() > MAX_CONTAINERS) throw bad("at most " + MAX_CONTAINERS + " containers");
        List<Container> containers = new ArrayList<>();
        Map<String, Container> byId = new HashMap<>();
        for (JsonElement e : ce.getAsJsonArray()) {
            if (!e.isJsonObject()) throw bad("every container must be an object");
            JsonObject c = e.getAsJsonObject();
            String id = text(c, "id", 40, true);
            if (!KitNames.validId(id)) throw bad("bad container id");
            String label = c.has("label") && !c.get("label").isJsonNull() ? text(c, "label", Integer.MAX_VALUE, false) : null;
            if (label != null) label = clean(label, MAX_LABEL);
            long size = whole(c.get("size"), "container size", 27, 54);
            if (size != 27 && size != 54) throw bad("container size must be 27 or 54");
            long free = whole(c.get("free"), "container free", 0, size);
            if (!(c.get("pickable") instanceof JsonPrimitive p) || !p.isBoolean()) throw bad("container pickable must be true or false");
            long cRead = whole(c.get("readAt"), "container readAt", 0, Long.MAX_VALUE);
            Container k = new Container(id, label == null || label.isEmpty() ? null : label, (int) size, (int) free, p.getAsBoolean(), cRead);
            if (byId.put(id, k) != null) throw bad("container id appears twice");
            containers.add(k);
        }
        JsonElement ke = o.get("kits");
        if (ke == null || !ke.isJsonArray()) throw bad("kits must be a list");
        if (ke.getAsJsonArray().size() > MAX_KITS) throw bad("at most " + MAX_KITS + " kits");
        List<Stored> kits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement e : ke.getAsJsonArray()) {
            if (!e.isJsonObject()) throw bad("every kit must be an object");
            JsonObject k = e.getAsJsonObject();
            String key = key(k);
            String container = text(k, "container", 40, true);
            if (!byId.containsKey(container)) throw bad("a kit's container must be in containers");
            long count = whole(k.get("count"), "kit count", 1, 54);
            if (!seen.add((key == null ? "\u0000" : key) + "\t" + container)) throw bad("a kit appears twice in one container");
            Score score = score(k.get("score"));
            int minTotal = (int) whole(k.get("minTotal"), "kit minTotal", 0, 40);
            if (minTotal > score.total()) throw bad("kit minTotal can't be above the score total");
            kits.add(new Stored(key, key == null ? null : name(k, key), container, (int) count, score, minTotal));
        }
        JsonElement re = o.get("carried");
        List<Carried> carried = new ArrayList<>();
        if (re != null && !re.isJsonNull()) {
            if (!re.isJsonArray()) throw bad("carried must be a list");
            if (re.getAsJsonArray().size() > MAX_CARRIED) throw bad("at most " + MAX_CARRIED + " carried kits");
            Set<String> keys = new HashSet<>();
            for (JsonElement e : re.getAsJsonArray()) {
                if (!e.isJsonObject()) throw bad("every carried kit must be an object");
                JsonObject k = e.getAsJsonObject();
                String key = key(k);
                if (!keys.add(key == null ? "\u0000" : key)) throw bad("a carried kit appears twice");
                carried.add(new Carried(key, key == null ? null : name(k, key), (int) whole(k.get("count"), "carried count", 1, MAX_CARRIED),
                    score(k.get("score"))));
            }
        }
        containers.sort((a, b) -> KitNames.compareIds(a.id(), b.id()));
        return new Report(hash, clock.getAsLong(), readAt, List.copyOf(containers), List.copyOf(kits), List.copyOf(carried));
    }

    /** A kit's key: a normalised name, or null for an unnamed kit. */
    private static String key(JsonObject k) {
        if (!k.has("key")) throw bad("key is required (null for an unnamed kit)");
        if (k.get("key").isJsonNull()) return null;
        String key = text(k, "key", KitNames.MAX_KEY, true);
        if (!KitNames.validKey(key)) throw bad("a kit key must be a normalised name");
        return key;
    }

    private static Score score(JsonElement e) {
        if (!(e instanceof JsonObject o)) throw bad("score must be an object");
        Score sc = new Score((int) whole(o.get("total"), "score total", 0, 40), (int) whole(o.get("name"), "score name", 0, 10),
            (int) whole(o.get("mix"), "score mix", 0, 10), (int) whole(o.get("full"), "score full", 0, 10),
            (int) whole(o.get("op"), "score op", 0, 10));
        if (sc.total() != sc.name() + sc.mix() + sc.full() + sc.op()) throw bad("score total must be the sum of its parts");
        return sc;
    }

    private static String name(JsonObject k, String fallback) {
        String n = k.has("name") && !k.get("name").isJsonNull() ? text(k, "name", Integer.MAX_VALUE, false) : null;
        if (n == null) return fallback;
        n = clean(n, MAX_NAME);
        return n.isEmpty() ? fallback : n;
    }

    /** Control characters dropped, cut to max UTF-16 units (never inside a surrogate pair). */
    static String clean(String s, int max) {
        StringBuilder sb = new StringBuilder(s.length());
        DeliveryContents.stripControls(s).codePoints().forEach(c -> {
            if (Character.getType(c) != Character.FORMAT) sb.appendCodePoint(c); // bidi marks, zero-width characters
        });
        return Http.truncate(sb.toString(), max);
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
        if (p.getAsString().length() > 20) throw bad(what + " must be a whole number"); // no huge exponents into BigDecimal
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

    private static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException(message);
    }

    // ---------- the page's view ----------

    /** One row of the kit table. */
    private static final class Row {
        String key;
        String name;
        int stored;
        int carried;
        Score score;      // the highest total across the row's entries
        int minTotal = Integer.MAX_VALUE;
        /** The row's name is the smallest name among its entries (String order). */
        void seeName(String n) {
            if (n != null && (name == null || n.compareTo(name) < 0)) name = n;
        }

        void see(Score sc, int min) {
            if (score == null || sc.total() > score.total()) score = sc;
            minTotal = Math.min(minTotal, Math.min(min, sc.total()));
        }
        final TreeMap<String, Integer> locations = new TreeMap<>(KitNames::compareIds);
    }

    /** "full" or "missing" count of the stash's rows (GET /api/v1/stashes kits.flagged). */
    static int flagged(JsonObject view) {
        int n = 0;
        for (JsonElement e : view.getAsJsonArray("rows")) {
            String s = e.getAsJsonObject().get("status").getAsString();
            if (s.equals("full") || s.equals("missing")) n++;
        }
        return n;
    }

    /** The GET /api/v1/kits body (KIT-CONTRACT.md). report may be null; beat may be null. */
    JsonObject view(String server, String stash, KitStore.Settings s, Report report, Beat beat) {
        JsonObject o = new JsonObject();
        o.addProperty("server", server);
        o.addProperty("stash", stash);
        o.addProperty("rev", s.rev());
        o.addProperty("hash", s.hash());
        o.addProperty("kitsChest", s.kitsChest());
        JsonObject sc = s.scoring().toJson();
        sc.addProperty("max", s.scoring().max());
        o.add("scoring", sc);
        JsonArray rules = new JsonArray();
        s.rules().forEach((k, c) -> {
            JsonObject r = new JsonObject();
            r.addProperty("key", k);
            r.addProperty("container", c);
            rules.add(r);
        });
        o.add("rules", rules);
        JsonObject bot = new JsonObject();
        boolean supports = beat != null && beat.supports();
        bot.addProperty("supports", supports);
        bot.addProperty("synced", supports && s.hash().equals(beat.hash()));
        bot.addProperty("online", beat != null && clock.getAsLong() - beat.at() < IndexStore.ONLINE_WINDOW_MS);
        o.add("bot", bot);
        if (report == null) {
            o.add("report", com.google.gson.JsonNull.INSTANCE);
        } else {
            JsonObject r = new JsonObject();
            r.addProperty("receivedAt", report.receivedAt());
            r.addProperty("readAt", report.readAt());
            o.add("report", r);
        }
        Map<String, Container> byId = new HashMap<>();
        JsonArray containers = new JsonArray();
        if (report != null) {
            for (Container c : report.containers()) {
                byId.put(c.id(), c);
                JsonObject co = new JsonObject();
                co.addProperty("id", c.id());
                co.addProperty("label", c.label());
                co.addProperty("size", c.size());
                co.addProperty("free", c.free());
                co.addProperty("pickable", c.pickable());
                co.addProperty("full", c.free() == 0);
                containers.add(co);
            }
        }
        o.add("containers", containers);
        o.addProperty("kitsChestStatus", chestStatus(s.kitsChest(), byId));

        TreeMap<String, Row> rows = new TreeMap<>(java.util.Comparator.nullsLast(java.util.Comparator.<String>naturalOrder()));
        if (report != null) {
            for (Stored k : report.kits()) {
                Row r = rows.computeIfAbsent(k.key(), x -> row(k.key(), k.name()));
                r.stored += k.count();
                r.seeName(k.name());
                r.see(k.score(), k.minTotal());
                r.locations.merge(k.container(), k.count(), Integer::sum);
            }
            for (Carried k : report.carried()) {
                Row r = rows.computeIfAbsent(k.key(), x -> row(k.key(), k.name()));
                r.carried += k.count();
                r.seeName(k.name());
                r.see(k.score(), k.score().total());
            }
        }
        for (String key : s.rules().keySet()) rows.computeIfAbsent(key, x -> row(key, key));
        JsonArray out = new JsonArray();
        for (Row r : rows.values()) {
            String rule = r.key == null ? null : s.rules().get(r.key); // an unnamed kit can't have a rule
            String target = rule != null ? rule : s.kitsChest();
            JsonObject ro = new JsonObject();
            ro.addProperty("key", r.key);
            ro.addProperty("name", r.name);
            ro.addProperty("count", r.stored + r.carried);
            ro.addProperty("carried", r.carried);
            if (r.score == null) {
                ro.add("score", com.google.gson.JsonNull.INSTANCE); // a rule for a kit that isn't in the stash
                ro.add("minTotal", com.google.gson.JsonNull.INSTANCE);
            } else {
                ro.add("score", r.score.toJson());
                ro.addProperty("minTotal", r.minTotal);
            }
            JsonArray locs = new JsonArray();
            r.locations.forEach((c, n) -> {
                JsonObject l = new JsonObject();
                l.addProperty("container", c);
                l.addProperty("count", n);
                locs.add(l);
            });
            ro.add("locations", locs);
            ro.addProperty("rule", rule);
            ro.addProperty("target", target);
            ro.addProperty("targetSource", rule != null ? "rule" : target != null ? "kitsChest" : null);
            ro.addProperty("status", status(r, rule != null, target, byId));
            out.add(ro);
        }
        o.add("rows", out);
        JsonObject limits = new JsonObject();
        limits.addProperty("maxRules", KitStore.MAX_RULES);
        limits.addProperty("maxKey", KitNames.MAX_KEY);
        o.add("limits", limits);
        return o;
    }

    private static Row row(String key, String name) {
        Row r = new Row();
        r.key = key;
        r.name = name;
        return r;
    }

    private static String chestStatus(String chest, Map<String, Container> byId) {
        if (chest == null) return "unset";
        Container c = byId.get(chest);
        if (c == null || !c.pickable()) return "missing";
        return c.free() == 0 ? "full" : "ok";
    }

    private static String status(Row r, boolean hasRule, String target, Map<String, Container> byId) {
        if (hasRule && r.stored + r.carried == 0) return "absent";
        if (target == null) return "none";
        Container c = byId.get(target);
        if (c == null || !c.pickable()) return "missing";
        if (r.carried == 0 && r.locations.size() == 1 && r.locations.containsKey(target)) return "home";
        if (c.free() == 0) return "full";
        return "pending";
    }
}
