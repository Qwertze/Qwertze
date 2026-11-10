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
    static final Pattern TABLE = Pattern.compile("^[0-9a-f]{64}$");
    static final Pattern ITEM_ID = Pattern.compile("^[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}$");
    static final Pattern WORD = Pattern.compile("^[a-z]{1,32}$");
    static final int MAX_OTHERS = 1024;
    static final int MAX_STOCK = 100;
    static final java.util.Set<String> KIT_CODES = java.util.Set.of("assortment", "named_assortment", "override", "template", "template_incomplete");
    static final java.util.Set<String> NOT_KIT_CODES = java.util.Set.of("empty", "stock", "stock_neutral", "no_purpose", "few_kinds", "mixed", "override_none");
    static final java.util.Set<String> RESTOCK_STATES = java.util.Set.of("off", "idle", "building", "waiting", "blocked");
    static final java.util.Set<String> RESTOCK_REASONS = java.util.Set.of("orders", "items_short", "bottle_reserve", "no_shulker", "chest_full",
        "chest_missing", "cap", "naming", "topping_up");

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

    /** One item the box lacks for its template (at most 8 in a why, 27 in a stock entry). */
    record Missing(String key, String match, int count) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("key", key);
            if (match != null) o.addProperty("match", match);
            o.addProperty("count", count);
            return o;
        }
    }

    /** The classifier's reason for a box (KIT-CONTRACT-v2, "Result"). Optional numbers are null when the contract leaves them out. */
    record Why(String code, String type, int slots, Integer covered, Integer kinds, Integer need, Integer minKinds,
               List<String> items, List<String> words, String template, List<Missing> missing) {
        boolean kit() {
            return KIT_CODES.contains(code);
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("code", code);
            o.addProperty("type", type);
            o.addProperty("slots", slots);
            if (covered != null) o.addProperty("covered", covered);
            if (kinds != null) o.addProperty("kinds", kinds);
            if (need != null) o.addProperty("need", need);
            if (minKinds != null) o.addProperty("minKinds", minKinds);
            JsonArray it = new JsonArray();
            items.forEach(it::add);
            o.add("items", it);
            JsonArray w = new JsonArray();
            words.forEach(w::add);
            o.add("words", w);
            if (template != null) o.addProperty("template", template);
            if (missing != null && !missing.isEmpty()) {
                JsonArray m = new JsonArray();
                missing.forEach(x -> m.add(x.toJson()));
                o.add("missing", m);
            }
            return o;
        }
    }

    /** One kit type (or category) of a kits or carried entry: how many of its boxes, and why. template is why.template (a box of a template). */
    record TypeCount(String type, int count, Why why, String template) {
    }

    /** key and name are null for unnamed kits; score is the highest-total box of the entry, minTotal the lowest total. types is empty for a v1 bot. */
    record Stored(String key, String name, String container, int count, Score score, int minTotal, List<TypeCount> types) {
        Stored(String key, String name, String container, int count, Score score, int minTotal) {
            this(key, name, container, count, score, minTotal, List.of());
        }
    }

    record Carried(String key, String name, int count, Score score, List<TypeCount> types) {
        Carried(String key, String name, int count, Score score) {
            this(key, name, count, score, List.of());
        }
    }

    /** A named box that is not a kit (one per key and container). */
    record Other(String key, String name, String container, int count, Why why) {
    }

    /** What the bot has of one template (KIT-CONTRACT-v2, revision 4, "Report templates"). */
    record Stock(String id, int complete, int incomplete, int elsewhere, int carried, List<Missing> missing) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("complete", complete);
            o.addProperty("incomplete", incomplete);
            o.addProperty("elsewhere", elsewhere);
            o.addProperty("carried", carried);
            JsonArray m = new JsonArray();
            missing.forEach(x -> m.add(x.toJson()));
            o.add("missing", m);
            return o;
        }
    }

    record Restock(String state, String template, String reason) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("state", state);
            o.addProperty("template", template);
            o.addProperty("reason", reason);
            return o;
        }
    }

    /** The report's classifier: version 2 and the fingerprint of the type table the bot used. */
    record Classifier(int version, String table) {
    }

    record Report(String hash, long receivedAt, long readAt, List<Container> containers, List<Stored> kits, List<Carried> carried,
                  Classifier classifier, List<Other> others, List<Stock> templates, Restock restock) {
        Report(String hash, long receivedAt, long readAt, List<Container> containers, List<Stored> kits, List<Carried> carried) {
            this(hash, receivedAt, readAt, containers, kits, carried, null, List.of(), List.of(), null);
        }

        Container container(String id) {
            for (Container c : containers) if (c.id().equals(id)) return c;
            return null;
        }

        Stock stock(String templateId) {
            for (Stock t : templates) if (t.id().equals(templateId)) return t;
            return null;
        }
    }

    /** What a bot says about kit orders in a heartbeat (kitOrders): it carries them out, may restock, may craft shortfalls, may import local kits. */
    record KitCap(int v, boolean enabled, boolean restock, boolean craft, boolean importer) {
    }

    /**
     * What a stash's latest heartbeat said: whether it had a kitsHash key, which hash, and when; whether it had a kitsHash2
     * key (a v2 bot) and which hash; and its kitOrders capability (null: none this run).
     */
    record Beat(boolean supports, String hash, long at, boolean types, String hash2, KitCap cap, long capAt, String reportHash) {
        Beat(boolean supports, String hash, long at) {
            this(supports, hash, at, false, null, null, 0, null);
        }
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
        beat(server, stash, supports, hash, false, null, false, null);
    }

    /**
     * A heartbeat. hasHash2 says the body had a kitsHash2 key (a v2 bot; hash2 may be null); hasCap that it had a kitOrders key
     * (cap null: the bot says it can't); an absent kitOrders keeps what was stored, like craftOrders.
     */
    synchronized void beat(String server, String stash, boolean supports, String hash, boolean hasHash2, String hash2, boolean hasCap, KitCap cap) {
        beat(server, stash, supports, hash, hasHash2, hash2, hasCap, cap, false, null);
    }

    /**
     * The same with the bot's kitsReportHash (the hash of the kit report it holds): hasReportHash says the body had the key (null: the
     * bot has none); an absent key keeps the stored one.
     */
    synchronized void beat(String server, String stash, boolean supports, String hash, boolean hasHash2, String hash2, boolean hasCap, KitCap cap,
                           boolean hasReportHash, String reportHash) {
        IndexStore.StashKey key = new IndexStore.StashKey(server, stash);
        Beat old = beats.get(key);
        KitCap c = hasCap ? cap : old == null ? null : old.cap();
        long now = clock.getAsLong();
        // capAt: when a heartbeat last carried an enabled kitOrders (a flagless claim is only believed to be a downgrade after that goes quiet).
        long capAt = hasCap ? (cap != null && cap.v() >= 1 && cap.enabled() ? now : 0) : old == null ? 0 : old.capAt();
        String rh = hasReportHash ? reportHash : old == null ? null : old.reportHash();
        beats.put(key, new Beat(supports, hash, now, hasHash2, hash2, c, capAt, rh));
        if (beats.size() > 1024) {
            beats.values().removeIf(b -> now - b.at() > 60 * 60 * 1000L);
        }
    }

    /** A report older than this since the bot's last heartbeat says nothing: the bot has gone quiet. */
    static final long LIVE_MS = 10 * 60 * 1000L;

    /**
     * Whether the stash's report still describes what the bot holds: the bot's latest heartbeat carries the report's hash
     * (kitsReportHash) and is no more than 10 minutes old. A bot sends a report only when its kit state changes, so the age of
     * the report itself does not matter.
     */
    synchronized boolean current(String server, String stash, Report r) {
        Beat b = beats.get(new IndexStore.StashKey(server, stash));
        return r != null && b != null && r.hash().equals(b.reportHash()) && clock.getAsLong() - b.at() <= LIVE_MS;
    }

    /** Whether a heartbeat carried an enabled kitOrders within the last ms milliseconds. */
    synchronized boolean kitCapableWithin(String server, String stash, long ms) {
        Beat b = beats.get(new IndexStore.StashKey(server, stash));
        return b != null && b.cap() != null && b.cap().v() >= 1 && b.cap().enabled() && b.capAt() > 0 && clock.getAsLong() - b.capAt() < ms;
    }

    /** Whether the stash's last kitOrders (this run) says the bot builds kits. */
    synchronized boolean kitCapable(String server, String stash) {
        Beat b = beats.get(new IndexStore.StashKey(server, stash));
        return b != null && b.cap() != null && b.cap().v() >= 1 && b.cap().enabled();
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

    /** Checks a POST /api/v1/kits/report body (a stash without custom categories) and returns it cleaned, or throws with the reason. */
    Report parse(JsonObject o) {
        return parse(o, java.util.Set.of());
    }

    /** The same; custom are the stash's custom category ids (a v2 report may name them as a kit's type). */
    Report parse(JsonObject o, java.util.Set<String> custom) {
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
            List<TypeCount> tcs = typeCounts(k.get("types"), (int) count, true, custom);
            kits.add(new Stored(key, key == null ? null : name(k, key), container, (int) count, score, minTotal, tcs));
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
                int ccount = (int) whole(k.get("count"), "carried count", 1, MAX_CARRIED);
                carried.add(new Carried(key, key == null ? null : name(k, key), ccount, score(k.get("score")),
                    typeCounts(k.get("types"), ccount, true, custom)));
            }
        }
        containers.sort((a, b) -> KitNames.compareIds(a.id(), b.id()));
        Classifier classifier = null;
        JsonElement cl = o.get("classifier");
        if (cl != null && !cl.isJsonNull()) {
            if (!cl.isJsonObject()) throw bad("classifier must be {version, table}");
            JsonObject co = cl.getAsJsonObject();
            long version = whole(co.get("version"), "classifier version", 2, 2);
            String table = text(co, "table", 64, true);
            if (!TABLE.matcher(table).matches()) throw bad("classifier table must be 64 lowercase hex digits");
            classifier = new Classifier((int) version, table);
        }
        List<Other> others = new ArrayList<>();
        JsonElement oe = o.get("others");
        if (oe != null && !oe.isJsonNull()) {
            if (!oe.isJsonArray()) throw bad("others must be a list");
            if (oe.getAsJsonArray().size() > MAX_OTHERS) throw bad("at most " + MAX_OTHERS + " others");
            Set<String> seenOthers = new HashSet<>();
            for (JsonElement e : oe.getAsJsonArray()) {
                if (!e.isJsonObject()) throw bad("every others entry must be an object");
                JsonObject x = e.getAsJsonObject();
                String key = key(x);
                if (key == null) throw bad("an others entry needs a key");
                String container = text(x, "container", 40, true);
                if (!byId.containsKey(container)) throw bad("an others entry's container must be in containers");
                if (!seenOthers.add(key + "\t" + container)) throw bad("an others entry appears twice in one container");
                int count = (int) whole(x.get("count"), "others count", 1, 54);
                Why why = why(x.get("why"), custom, false);
                if (why.kit()) throw bad("an others entry can't have the reason of a kit");
                others.add(new Other(key, name(x, key), container, count, why));
            }
        }
        List<Stock> templates = new ArrayList<>();
        JsonElement te = o.get("templates");
        if (te != null && !te.isJsonNull()) {
            if (!te.isJsonArray()) throw bad("templates must be a list");
            if (te.getAsJsonArray().size() > MAX_STOCK) throw bad("at most " + MAX_STOCK + " templates");
            Set<String> ids = new HashSet<>();
            for (JsonElement e : te.getAsJsonArray()) {
                if (!e.isJsonObject()) throw bad("every templates entry must be an object");
                JsonObject x = e.getAsJsonObject();
                String id = text(x, "id", 16, true);
                if (!KitTemplate.ID.matcher(id).matches()) throw bad("a template id looks like t-3f2a9c1d");
                if (!ids.add(id)) throw bad("a template appears twice");
                templates.add(new Stock(id, (int) whole(x.get("complete"), "templates complete", 0, 4096),
                    (int) whole(x.get("incomplete"), "templates incomplete", 0, 4096), (int) whole(x.get("elsewhere"), "templates elsewhere", 0, 4096),
                    (int) whole(x.get("carried"), "templates carried", 0, 4096), missing(x.get("missing"), 27)));
            }
        }
        Restock restock = null;
        JsonElement re2 = o.get("restock");
        if (re2 != null && !re2.isJsonNull()) {
            if (!re2.isJsonObject()) throw bad("restock must be {state, template, reason}");
            JsonObject x = re2.getAsJsonObject();
            String state = text(x, "state", 16, true);
            if (!RESTOCK_STATES.contains(state)) throw bad("restock state must be off, idle, building, waiting or blocked");
            String tpl = x.has("template") && !x.get("template").isJsonNull() ? text(x, "template", 16, true) : null;
            if (tpl != null && !KitTemplate.ID.matcher(tpl).matches()) throw bad("restock template looks like t-3f2a9c1d");
            String reason = x.has("reason") && !x.get("reason").isJsonNull() ? text(x, "reason", 32, true) : null;
            if (reason != null && !RESTOCK_REASONS.contains(reason)) throw bad("restock reason is not one of the contract's");
            restock = new Restock(state, tpl, reason);
        }
        return new Report(hash, clock.getAsLong(), readAt, List.copyOf(containers), List.copyOf(kits), List.copyOf(carried), classifier,
            List.copyOf(others), List.copyOf(templates), restock);
    }

    /** A kits or carried entry's types: one per category (and template), counts summing to the entry's count; absent for a v1 bot. */
    private List<TypeCount> typeCounts(JsonElement e, int total, boolean requireKit, Set<String> custom) {
        if (e == null || e.isJsonNull()) return List.of();
        if (!e.isJsonArray()) throw bad("types must be a list");
        JsonArray a = e.getAsJsonArray();
        if (a.size() > 64) throw bad("a kit has at most 64 types");
        List<TypeCount> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long sum = 0;
        for (JsonElement x : a) {
            if (!x.isJsonObject()) throw bad("every types entry must be an object");
            JsonObject t = x.getAsJsonObject();
            String type = text(t, "type", 40, true);
            if (!KitTypes.isType(type) && !custom.contains(type)) throw bad("a kit's type must be one of the kit types or a category of the stash");
            int count = (int) whole(t.get("count"), "types count", 1, 54);
            Why why = why(t.get("why"), custom, true);
            if (requireKit && !why.kit()) throw bad("a kit's why must have the code of a kit");
            if (!type.equals(why.type())) throw bad("a types entry's why.type must be its type");
            String template = t.has("template") && !t.get("template").isJsonNull() ? text(t, "template", 16, true) : why.template();
            if (template != null && !KitTemplate.ID.matcher(template).matches()) throw bad("a template id looks like t-3f2a9c1d");
            if (!seen.add(type + "\t" + template)) throw bad("a type appears twice in one kit");
            sum += count;
            out.add(new TypeCount(type, count, why, template));
        }
        if (!out.isEmpty() && sum != total) throw bad("the counts of a kit's types must add up to its count");
        return List.copyOf(out);
    }

    private Why why(JsonElement e, Set<String> custom, boolean kitTypeRequired) {
        if (!(e instanceof JsonObject w)) throw bad("why must be an object");
        String code = text(w, "code", 32, true);
        if (!KIT_CODES.contains(code) && !NOT_KIT_CODES.contains(code)) throw bad("why.code is not one of the contract's");
        String type = w.has("type") && !w.get("type").isJsonNull() ? text(w, "type", 40, true) : null;
        if (type != null && !KitTypes.isType(type) && !custom.contains(type)) throw bad("why.type must be a kit type or a category of the stash");
        if (kitTypeRequired && KIT_CODES.contains(code) && type == null) throw bad("a kit's why needs a type");
        int slots = (int) whole(w.get("slots"), "why slots", 0, 27);
        Integer covered = optWhole(w, "covered"), kinds = optWhole(w, "kinds"), need = optWhole(w, "need"), minKinds = optWhole(w, "minKinds");
        List<String> items = new ArrayList<>();
        JsonElement ie = w.get("items");
        if (ie != null && !ie.isJsonNull()) {
            if (!ie.isJsonArray() || ie.getAsJsonArray().size() > 3) throw bad("why.items must be a list of at most 3 item ids");
            for (JsonElement i : ie.getAsJsonArray()) {
                if (!(i instanceof JsonPrimitive p) || !p.isString() || !ITEM_ID.matcher(p.getAsString()).matches()) throw bad("why.items must be item ids like minecraft:bread");
                items.add(p.getAsString());
            }
        }
        List<String> words = new ArrayList<>();
        JsonElement we = w.get("words");
        if (we != null && !we.isJsonNull()) {
            if (!we.isJsonArray() || we.getAsJsonArray().size() > 4) throw bad("why.words must be a list of at most 4 words");
            for (JsonElement i : we.getAsJsonArray()) {
                if (!(i instanceof JsonPrimitive p) || !p.isString() || !WORD.matcher(p.getAsString()).matches()) throw bad("why.words must be lowercase words");
                words.add(p.getAsString());
            }
        }
        String template = null;
        if (w.has("template") && !w.get("template").isJsonNull()) {
            template = text(w, "template", 16, true);
            if (!KitTemplate.ID.matcher(template).matches()) throw bad("why.template looks like t-3f2a9c1d");
        }
        if ((code.equals("template") || code.equals("template_incomplete")) && template == null) throw bad("a template reason needs a template id");
        List<Missing> missing = missing(w.get("missing"), 8);
        return new Why(code, type, slots, covered, kinds, need, minKinds, List.copyOf(items), List.copyOf(words), template, missing);
    }

    private static Integer optWhole(JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull()) return null;
        return (int) whole(e, "why " + field, 0, 27);
    }

    /** A list of at most max {key, count} (and an optional match key) items. */
    private static List<Missing> missing(JsonElement e, int max) {
        if (e == null || e.isJsonNull()) return List.of();
        if (!e.isJsonArray() || e.getAsJsonArray().size() > max) throw bad("missing must be a list of at most " + max + " items");
        List<Missing> out = new ArrayList<>();
        for (JsonElement x : e.getAsJsonArray()) {
            if (!x.isJsonObject()) throw bad("every missing entry must be an object");
            JsonObject m = x.getAsJsonObject();
            String key = text(m, "key", 256, false);
            String match = m.has("match") && !m.get("match").isJsonNull() ? text(m, "match", 256, true) : null;
            if (key == null) key = match;
            if (key == null || !KitTemplate.KEY.matcher(key).matches()) throw bad("a missing key must be an item key");
            if (match != null && !KitTemplate.KEY.matcher(match).matches()) throw bad("a missing match must be an item key");
            out.add(new Missing(key, match, (int) whole(m.get("count"), "missing count", 1, 100_000)));
        }
        return List.copyOf(out);
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

    /** "full" or "missing" rows of the stash and set chests of the categories (GET /api/v1/stashes kits.flagged). */
    static int flagged(JsonObject view) {
        int n = 0;
        for (JsonElement e : view.getAsJsonArray("rows")) {
            String s = e.getAsJsonObject().get("status").getAsString();
            if (s.equals("full") || s.equals("missing")) n++;
        }
        if (view.has("types")) {
            for (JsonElement e : view.getAsJsonArray("types")) {
                String s = e.getAsJsonObject().get("chestStatus").getAsString();
                if (s.equals("full") || s.equals("missing")) n++;
            }
        }
        return n;
    }

    /** The GET /api/v1/kits body (KIT-CONTRACT.md and KIT-CONTRACT-v2). report may be null; beat may be null. */
    JsonObject view(String server, String stash, KitStore.Settings s, Report report, Beat beat) {
        return KitView.build(clock.getAsLong(), server, stash, s, report, beat);
    }
}
