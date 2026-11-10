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

/**
 * What each stash's bot said about repair passes (REPAIR-CONTRACT v2.0): the settings hash it has cached, its pass status from
 * the heartbeat, the website's run and stop requests, and its latest pass report (POST /api/v1/repair/report). In memory only
 * and never logged: container labels and item keys come from the game. After a server restart a bot is unknown until its next
 * heartbeat.
 */
final class RepairBots {
    static final int MAX_STASHES = 64;
    static final int MAX_BODY = 512 * 1024;
    static final int MAX_ITEMS = 200;
    static final int MAX_CONTAINERS = 4096;
    static final long MAX_COUNT = 1_000_000_000L;
    static final Set<String> STATES = Set.of("off", "idle", "waiting", "running", "yielding", "blocked");
    static final Set<String> WHY = Set.of("disabled", "no_settings", "reserve", "no_candidates", "order", "player_near", "foreign", "sorter_off",
        "offline", "cooldown", "inventory_full", "kit_conflict");
    static final Set<String> PASS_STATES = Set.of("running", "done", "yielded", "stopped", "failed");
    static final Set<String> TRIGGERS = Set.of("auto", "manual");
    static final Set<String> STATUSES = Set.of("repaired", "partial", "skipped", "needs_repair");
    /** Why an item was not repaired or moved (REPAIR-CONTRACT v2.0 and its revision for shulker boxes). */
    static final Set<String> REASONS = Set.of("no bottles", "reserve", "interrupted", "in a shulker", "no room", "stopped", "order", "cooldown",
        "in a named shulker", "in a kit", "box cap", "box failed", "player near");

    /** What a bot's heartbeat said: whether it had a repairHash key, the hash, the pass status (null: none), when. */
    record Beat(boolean supports, String hash, JsonObject pass, long at) {
        long runDone() {
            return num(pass, "runDone");
        }

        long stopDone() {
            return num(pass, "stopDone");
        }

        String reportHash() {
            return pass == null || !pass.has("reportHash") || pass.get("reportHash").isJsonNull() ? null : pass.get("reportHash").getAsString();
        }

        String state() {
            return pass == null ? null : pass.get("state").getAsString();
        }
    }

    private static long num(JsonObject o, String f) {
        return o == null || !o.has(f) || o.get(f).isJsonNull() ? 0 : o.get(f).getAsLong();
    }

    record Container(String id, String label, int size, int free, boolean pickable, long readAt) {
    }

    /** A pass report (already validated and cleaned). pass and items are the JSON the page gets. */
    record Report(String hash, long receivedAt, JsonObject pass, JsonArray items, List<Container> containers) {
    }

    private final LongSupplier clock;
    private final LinkedHashMap<IndexStore.StashKey, Beat> beats = new LinkedHashMap<>();
    private final Map<IndexStore.StashKey, long[]> requests = new HashMap<>(); // {run, stop}
    /** The highest run and stop ids each bot acknowledged: only ever raised, and kept when a heartbeat leaves the ids out or clears the pass. */
    private final Map<IndexStore.StashKey, long[]> acks = new HashMap<>();
    private final LinkedHashMap<IndexStore.StashKey, Report> reports = new LinkedHashMap<>();

    RepairBots() {
        this(System::currentTimeMillis);
    }

    RepairBots(LongSupplier clock) {
        this.clock = clock;
    }

    long now() {
        return clock.getAsLong();
    }

    // ---------- the heartbeat ----------

    /**
     * The heartbeat's repairPass object (or throws IllegalArgumentException, a 400): v >= 1, state, why, pass, trigger, progress,
     * candidates, bottles, runDone, stopDone, reportHash. Whole numbers, no text from the bot except the closed codes.
     */
    static JsonObject parsePass(JsonObject o) {
        JsonObject out = new JsonObject();
        out.addProperty("v", (int) whole(o.get("v"), "repairPass.v", 1, 1000));
        String state = text(o, "state", 16);
        if (state == null || !STATES.contains(state)) throw bad("repairPass.state must be off, idle, waiting, running, yielding or blocked");
        out.addProperty("state", state);
        String why = o.has("why") && !o.get("why").isJsonNull() ? text(o, "why", 24) : null;
        if (o.has("why") && !o.get("why").isJsonNull() && (why == null || !WHY.contains(why))) throw bad("repairPass.why is not one of the contract's");
        out.addProperty("why", why);
        out.addProperty("pass", o.has("pass") && !o.get("pass").isJsonNull() ? whole(o.get("pass"), "repairPass.pass", 0, MAX_COUNT) : 0);
        String trigger = o.has("trigger") && !o.get("trigger").isJsonNull() ? text(o, "trigger", 8) : null;
        if (o.has("trigger") && !o.get("trigger").isJsonNull() && (trigger == null || !TRIGGERS.contains(trigger))) throw bad("repairPass.trigger must be auto or manual");
        out.addProperty("trigger", trigger);
        if (o.has("progress") && !o.get("progress").isJsonNull()) {
            JsonObject p = obj(o.get("progress"), "repairPass.progress");
            JsonObject po = new JsonObject();
            po.addProperty("done", whole(p.get("done"), "repairPass.progress.done", 0, MAX_COUNT));
            po.addProperty("total", whole(p.get("total"), "repairPass.progress.total", 0, MAX_COUNT));
            out.add("progress", po);
        } else {
            out.add("progress", com.google.gson.JsonNull.INSTANCE);
        }
        out.add("candidates", counts(o.get("candidates"), "repairPass.candidates", "mend", "mendBottles", "needsRepair", "inShulkers", "inBoxes", "boxes"));
        out.add("bottles", counts(o.get("bottles"), "repairPass.bottles", "stash", "reserve", "usable"));
        // Left out when the heartbeat has none: an absent id keeps what the server already has (see beat()).
        if (o.has("runDone") && !o.get("runDone").isJsonNull()) out.addProperty("runDone", whole(o.get("runDone"), "repairPass.runDone", 0, Long.MAX_VALUE / 2));
        if (o.has("stopDone") && !o.get("stopDone").isJsonNull()) out.addProperty("stopDone", whole(o.get("stopDone"), "repairPass.stopDone", 0, Long.MAX_VALUE / 2));
        String rh = o.has("reportHash") && !o.get("reportHash").isJsonNull() ? text(o, "reportHash", 40) : null;
        if (o.has("reportHash") && !o.get("reportHash").isJsonNull() && (rh == null || !KitReports.HASH.matcher(rh).matches())) {
            throw bad("repairPass.reportHash must be 40 lowercase hex digits or null");
        }
        out.addProperty("reportHash", rh);
        return out;
    }

    /** An object of whole numbers >= 0 with the given optional fields (missing ones are left out), or null when the value is null/absent. */
    private static JsonElement counts(JsonElement e, String what, String... fields) {
        if (e == null || e.isJsonNull()) return com.google.gson.JsonNull.INSTANCE;
        JsonObject in = obj(e, what);
        JsonObject out = new JsonObject();
        for (String f : fields) {
            if (in.has(f) && !in.get(f).isJsonNull()) out.addProperty(f, whole(in.get(f), what + "." + f, 0, MAX_COUNT));
        }
        return out;
    }

    /** A heartbeat: hasHash says the body had a repairHash key; hasPass that it had a repairPass key (pass null = cleared). */
    synchronized void beat(String server, String stash, boolean hasHash, String hash, boolean hasPass, JsonObject pass) {
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        Beat old = beats.remove(k);
        boolean supports = hasHash || (old != null && old.supports());
        String h = hasHash ? hash : old == null ? null : old.hash();
        JsonObject p = hasPass ? pass : old == null ? null : old.pass();
        long[] ack = acks.computeIfAbsent(k, x -> new long[2]);
        if (hasPass && pass != null) {
            if (pass.has("runDone")) ack[0] = Math.max(ack[0], pass.get("runDone").getAsLong());
            if (pass.has("stopDone")) ack[1] = Math.max(ack[1], pass.get("stopDone").getAsLong());
            pass.addProperty("runDone", ack[0]); // what the server holds, also for the page
            pass.addProperty("stopDone", ack[1]);
        }
        beats.put(k, new Beat(supports, h, p, clock.getAsLong()));
        while (beats.size() > MAX_STASHES * 4) {
            Iterator<IndexStore.StashKey> it = beats.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    synchronized Beat beat(String server, String stash) {
        return beats.get(new IndexStore.StashKey(server, stash));
    }

    // ---------- the website's requests ----------

    /** A new run (index 0) or stop (index 1) request id: increasing integers from Unix seconds, like the rescan request. */
    synchronized long request(String server, String stash, int index) {
        long[] r = requests.computeIfAbsent(new IndexStore.StashKey(server, stash), x -> new long[2]);
        r[index] = Math.max(clock.getAsLong() / 1000, Math.max(r[0], r[1]) + 1);
        if (requests.size() > 1024) requests.keySet().removeIf(x -> !beats.containsKey(x));
        return r[index];
    }

    /** The pending run (0) or stop (1) request id: one newer than the bot's acknowledged id, else 0. */
    synchronized long pending(String server, String stash, int index) {
        long[] r = requests.get(new IndexStore.StashKey(server, stash));
        if (r == null || r[index] == 0) return 0;
        long[] a = acks.get(new IndexStore.StashKey(server, stash));
        long done = a == null ? 0 : a[index];
        return r[index] > done ? r[index] : 0;
    }

    // ---------- the pass report ----------

    synchronized Report report(String server, String stash) {
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

    /** Whether the bot should send its report: its heartbeat names a hash and the server's is missing or differs. */
    synchronized boolean needReport(String server, String stash, String reportHash) {
        if (reportHash == null) return false;
        Report r = reports.get(new IndexStore.StashKey(server, stash));
        return r == null || !r.hash().equals(reportHash);
    }

    synchronized void forget(String server, String stash) {
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        beats.remove(k);
        requests.remove(k);
        acks.remove(k);
        reports.remove(k);
    }

    /** Checks a POST /api/v1/repair/report body and returns it cleaned, or throws with the reason (a 400). */
    Report parse(JsonObject o) {
        whole(o.get("version"), "version", 1, 1);
        String hash = text(o, "hash", 40);
        if (hash == null || !KitReports.HASH.matcher(hash).matches()) throw bad("hash must be 40 lowercase hex digits");
        JsonObject p = obj(o.get("pass"), "pass");
        JsonObject pass = new JsonObject();
        pass.addProperty("id", whole(p.get("id"), "pass.id", 0, MAX_COUNT));
        String trigger = text(p, "trigger", 8);
        if (trigger == null || !TRIGGERS.contains(trigger)) throw bad("pass.trigger must be auto or manual");
        pass.addProperty("trigger", trigger);
        String state = text(p, "state", 8);
        if (state == null || !PASS_STATES.contains(state)) throw bad("pass.state must be running, done, yielded, stopped or failed");
        pass.addProperty("state", state);
        pass.addProperty("startedAt", whole(p.get("startedAt"), "pass.startedAt", 0, Long.MAX_VALUE / 2));
        pass.addProperty("endedAt", whole(p.get("endedAt"), "pass.endedAt", 0, Long.MAX_VALUE / 2));
        pass.addProperty("threshold", whole(p.get("threshold"), "pass.threshold", 1, 100));
        for (String f : new String[]{"repaired", "partial", "bottlesUsed", "rehomed", "toNeedsRepair", "skipped", "left"}) {
            pass.addProperty(f, whole(p.get(f), "pass." + f, 0, MAX_COUNT));
        }
        for (String f : new String[]{"boxesOpened", "boxesFailed"}) { // the shulker revision: optional
            pass.addProperty(f, p.has(f) && !p.get(f).isJsonNull() ? whole(p.get(f), "pass." + f, 0, MAX_COUNT) : 0);
        }
        JsonObject by = new JsonObject();
        if (p.has("skippedBy") && !p.get("skippedBy").isJsonNull()) {
            JsonObject sb = obj(p.get("skippedBy"), "pass.skippedBy");
            if (sb.size() > 16) throw bad("pass.skippedBy has too many reasons");
            Map<String, Long> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> e : sb.entrySet()) {
                if (!REASONS.contains(e.getKey())) throw bad("pass.skippedBy has a reason that is not one of the contract's");
                sorted.put(e.getKey(), whole(e.getValue(), "pass.skippedBy count", 0, MAX_COUNT));
            }
            sorted.forEach(by::addProperty);
        }
        pass.add("skippedBy", by);

        List<Container> containers = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        JsonElement ce = o.get("containers");
        if (ce != null && !ce.isJsonNull()) {
            if (!ce.isJsonArray() || ce.getAsJsonArray().size() > MAX_CONTAINERS) throw bad("containers must be a list of at most " + MAX_CONTAINERS);
            for (JsonElement e : ce.getAsJsonArray()) {
                JsonObject c = obj(e, "every container");
                String id = text(c, "id", 40);
                if (id == null || !KitNames.validId(id)) throw bad("bad container id");
                String label = c.has("label") && !c.get("label").isJsonNull() ? text(c, "label", Integer.MAX_VALUE) : null;
                if (c.has("label") && !c.get("label").isJsonNull() && label == null) throw bad("container label must be text");
                if (label != null) label = KitReports.clean(label, KitReports.MAX_LABEL);
                long size = whole(c.get("size"), "container size", 27, 54);
                if (size != 27 && size != 54) throw bad("container size must be 27 or 54");
                long free = whole(c.get("free"), "container free", 0, size);
                if (!(c.get("pickable") instanceof JsonPrimitive pp) || !pp.isBoolean()) throw bad("container pickable must be true or false");
                if (!ids.add(id)) throw bad("container id appears twice");
                containers.add(new Container(id, label == null || label.isEmpty() ? null : label, (int) size, (int) free, pp.getAsBoolean(),
                    whole(c.get("readAt"), "container readAt", 0, Long.MAX_VALUE / 2)));
            }
        }
        containers.sort((a, b) -> KitNames.compareIds(a.id(), b.id()));

        JsonArray items = new JsonArray();
        JsonElement ie = o.get("items");
        if (ie != null && !ie.isJsonNull()) {
            if (!ie.isJsonArray() || ie.getAsJsonArray().size() > MAX_ITEMS) throw bad("items must be a list of at most " + MAX_ITEMS);
            for (JsonElement e : ie.getAsJsonArray()) {
                JsonObject in = obj(e, "every item");
                JsonObject it = new JsonObject();
                String key = text(in, "key", 256);
                if (key == null || !KitTemplate.MATCH.matcher(key).matches()) throw bad("an item key must be an item key like minecraft:diamond_pickaxe#ab12cd34");
                it.addProperty("key", key);
                String status = text(in, "status", 16);
                if (status == null || !STATUSES.contains(status)) throw bad("item status must be repaired, partial, skipped or needs_repair");
                it.addProperty("status", status);
                String reason = in.has("reason") && !in.get("reason").isJsonNull() ? text(in, "reason", 60) : null;
                if (in.has("reason") && !in.get("reason").isJsonNull() && (reason == null || !REASONS.contains(reason))) throw bad("item reason is not one of the contract's");
                it.addProperty("reason", reason);
                for (String f : new String[]{"from", "to"}) {
                    String c = in.has(f) && !in.get(f).isJsonNull() ? text(in, f, 40) : null;
                    if (in.has(f) && !in.get(f).isJsonNull() && (c == null || !KitNames.validId(c))) throw bad("item " + f + " must be a container id or null");
                    it.addProperty(f, c);
                }
                it.addProperty("damage", whole(in.get("damage"), "item damage", 0, 1_000_000));
                it.addProperty("maxDamage", whole(in.get("maxDamage"), "item maxDamage", 0, 1_000_000));
                String box = in.has("box") && !in.get("box").isJsonNull() ? text(in, "box", 40) : null;
                if (in.has("box") && !in.get("box").isJsonNull() && (box == null || !box.matches("^s:[0-9a-f]{1,32}$"))) throw bad("item box must be a shulker key like s:ab12cd34ef or null");
                it.addProperty("box", box);
                items.add(it);
            }
        }
        return new Report(hash, clock.getAsLong(), pass, items, List.copyOf(containers));
    }

    // ---------- small helpers ----------

    private static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException(message);
    }

    private static JsonObject obj(JsonElement e, String what) {
        if (!(e instanceof JsonObject o)) throw bad(what + " must be an object");
        return o;
    }

    private static String text(JsonObject o, String key, int max) {
        JsonElement e = o.get(key);
        if (!(e instanceof JsonPrimitive p) || !p.isString() || p.getAsString().length() > max) return null;
        return p.getAsString();
    }

    private static long whole(JsonElement e, String what, long min, long max) {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber() || p.getAsString().length() > 20) throw bad(what + " must be a whole number");
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
}
