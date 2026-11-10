package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Kit orders (KIT-CONTRACT-v2, "Kit orders" and revision 4): a line {type: "kit", key: template id, count} asks for
 * finished kits. At placement the server works out how many complete copies the stash already has (ready), what it takes
 * to build the rest from the index (needs, net of what incomplete copies already hold), plans plain shortfalls with the
 * craft planner when the bot can craft, and stores a snapshot of the template with the order plus the items it reserves.
 */
final class KitOrders {
    static final int MAX_PER_LINE = 54;
    /** At most this many different kits in one order, and this many index keys reserved by one order. */
    static final int MAX_KIT_LINES = 16;
    static final int MAX_TAKE = 256;

    /** Closed set of reasons a kit was not made (result.kit.short[].reason). */
    static final java.util.Set<String> SHORT_REASONS = java.util.Set.of("items_short", "no_shulker", "inventory_full", "template_mismatch",
        "interrupted", "timeout", "cancelled", "cap");
    static final java.util.Set<String> STORED = java.util.Set.of("incomplete", "returned");

    private final KitStore store;
    private final KitReports reports;

    KitOrders(KitStore store, KitReports reports) {
        this.store = store;
        this.reports = reports;
    }

    /**
     * What planning a kit order gave: the order's kit JSON (snapshot and takes), the item lines and craft lines to hand to the
     * craft planner (the kits' plain direct takes and their plain shortfalls), and the error to throw if that plan fails.
     */
    record Plan(JsonObject kit, List<OrderStore.Line> directLines, List<OrderStore.Line> craftLines, Map<String, Integer> craftAt,
                OrderStore.OrderException craftFailed) {
    }

    private record Need(String key, String match, long count, boolean wildcard, boolean plain) {
    }

    /**
     * The reservations an open order's kit lines make: item:key for every index key the order will take (resolved at
     * placement, so item lines, the planner and the craft stock all see the same numbers), and ready:t for the copies it claims.
     */
    static void reservations(OrderStore.Order o, Map<String, Long> out) {
        if (o.kit == null) return;
        for (JsonElement e : o.kit.getAsJsonArray("take")) {
            JsonObject t = e.getAsJsonObject();
            if (t.has("keys")) {
                for (JsonElement k : t.getAsJsonArray("keys")) {
                    out.merge("item:" + k.getAsJsonObject().get("key").getAsString(), k.getAsJsonObject().get("count").getAsLong(), Long::sum);
                }
            } else {
                String m = t.get("match").getAsString();
                if (m.indexOf('#') < 0) out.merge("item:" + m, t.get("count").getAsLong(), Long::sum);
            }
        }
        for (JsonElement e : o.kit.getAsJsonArray("lines")) {
            JsonObject l = e.getAsJsonObject();
            out.merge("ready:" + l.getAsJsonObject("template").get("id").getAsString(), l.get("ready").getAsLong(), Long::sum);
        }
    }

    /** Whether a heartbeat carried an enabled kitOrders within the last ms milliseconds. */
    boolean capableWithin(String server, String stash, long ms) {
        return reports.kitCapableWithin(server, stash, ms);
    }

    /** Null when the order has no kit line. Throws OrderException (409 kit code, 400 with the short items, 503 unreadable settings). */
    Plan plan(String server, String stash, IndexStore.OrderCounts counts, Map<String, Long> reserved, Collection<OrderStore.Line> merged,
              Map<String, Integer> firstIndex, boolean craftAllowed, java.util.function.Function<String, String> itemName)
        throws OrderStore.OrderException {
        List<OrderStore.Line> kitLines = new ArrayList<>();
        for (OrderStore.Line l : merged) if (l.type().equals("kit")) kitLines.add(l);
        if (kitLines.isEmpty()) return null;
        int firstAt = firstIndex.get(kitLines.get(0).ref());
        KitReports.Beat beat = reports.beat(server, stash);
        KitReports.KitCap cap = beat == null ? null : beat.cap();
        if (cap == null || cap.v() < 1) {
            throw new OrderStore.OrderException(409, "this stash's bot can't build kits (no kit-capable bot has reported since the server started)", firstAt, null, "no_bot");
        }
        if (!cap.enabled()) throw new OrderStore.OrderException(409, "kit building is switched off on this bot", firstAt, null, "disabled");
        KitStore.Settings settings;
        try {
            settings = store.get(server, stash);
        } catch (KitStore.DisabledException e) {
            throw new OrderStore.OrderException(503, "kit settings can't be read (the server's kits.json is damaged)", firstAt);
        }
        KitReports.Report report = reports.get(server, stash);
        // A report only counts while the bot's latest heartbeat (no more than 10 minutes old) carries its hash: a quiet stash keeps
        // its finished kits, a bot that went away or changed its kits since does not. Otherwise the kits are built.
        if (report != null && !reports.current(server, stash, report)) report = null;
        if (kitLines.size() > MAX_KIT_LINES) {
            throw new OrderStore.OrderException(400, "at most " + MAX_KIT_LINES + " different kits in one order", firstAt);
        }

        // This order's own item lines count against the pools as well as the open orders'.
        Map<String, Long> res = new HashMap<>(reserved);
        for (OrderStore.Line l : merged) if (l.type().equals("item")) res.merge("item:" + l.key(), l.count(), Long::sum);

        JsonArray lines = new JsonArray();
        Map<String, Long> needExact = new LinkedHashMap<>(); // match -> count
        Map<String, Long> needWild = new LinkedHashMap<>();  // base id -> count
        Map<String, String> keyOfMatch = new HashMap<>();    // match -> a slot key (for the error text)
        long shulkers = 0;
        Map<String, Long> firstPerKey = new LinkedHashMap<>();
        Map<String, String> firstMatch = new HashMap<>();
        long firstReady = 0;
        for (OrderStore.Line l : kitLines) {
            int at = firstIndex.get(l.ref());
            KitTemplate.Template t = settings.types().templates().get(l.key());
            if (t == null) throw new OrderStore.OrderException(400, "no such kit: " + l.key(), at, null, "deleted");
            if (l.count() < 1 || l.count() > MAX_PER_LINE) {
                throw new OrderStore.OrderException(400, "at most " + MAX_PER_LINE + " of one kit in an order", at);
            }
            int count = (int) l.count();
            KitReports.Stock stock = report == null ? null : report.stock(t.id());
            long copies = stock == null ? 0 : (long) stock.complete() + stock.elsewhere();
            long ready = Math.min(count, Math.max(0, copies - res.getOrDefault("ready:" + t.id(), 0L)));
            long build = count - ready;
            shulkers += build;
            boolean isFirst = l == kitLines.get(0);
            if (isFirst) firstReady = ready;
            // What one copy takes, by slot key.
            Map<String, Long> perKey = new LinkedHashMap<>();
            Map<String, String> matchOfKey = new HashMap<>();
            for (KitTemplate.Slot s : t.slots()) {
                if (s == null) continue;
                perKey.merge(s.key(), (long) s.count(), Long::sum);
                matchOfKey.put(s.key(), s.match());
            }
            // The incomplete copies in the chain target already hold part of it: the fullest ones, up to build of them.
            if (isFirst) {
                firstPerKey.putAll(perKey);
                firstMatch.putAll(matchOfKey);
            }
            long incomplete = stock == null ? 0 : stock.incomplete();
            Map<String, Long> missing = new HashMap<>();
            boolean mapped = true;
            if (stock != null) {
                for (KitReports.Missing m : stock.missing()) {
                    String slotKey = perKey.containsKey(m.key()) ? m.key() : null;
                    if (slotKey == null && m.match() != null) {
                        for (Map.Entry<String, String> mk : matchOfKey.entrySet()) if (mk.getValue().equals(m.match())) slotKey = mk.getKey();
                    }
                    if (slotKey == null) mapped = false;
                    else missing.merge(slotKey, (long) m.count(), Long::sum);
                }
            }
            // A missing list that doesn't fit the template's slots (it was edited since the report): no credit for incomplete copies.
            long used = mapped ? Math.min(build, incomplete) : 0;
            for (Map.Entry<String, Long> e : perKey.entrySet()) {
                long all = e.getValue() * build;
                long held = 0;
                if (used > 0) {
                    long heldAll = Math.max(0, e.getValue() * incomplete - missing.getOrDefault(e.getKey(), 0L));
                    held = Math.min(all, incomplete == 0 ? 0 : heldAll * used / incomplete);
                }
                long need = all - held;
                if (need <= 0) continue;
                String match = matchOfKey.get(e.getKey());
                if (e.getKey().endsWith("#*")) needWild.merge(IndexStore.OrderCounts.baseOf(e.getKey()), need, Long::sum);
                else needExact.merge(match, need, Long::sum);
                keyOfMatch.putIfAbsent(match, e.getKey());
            }
            JsonObject lo = new JsonObject();
            JsonObject to = t.toJson();
            to.remove("chest"); // a snapshot has no positions
            lo.add("template", to);
            lo.addProperty("count", count);
            lo.addProperty("ready", ready);
            lo.addProperty("build", build);
            lines.add(lo);
        }

        // Availability per index key: the index count less every reservation (item lines, craft takes, other kit orders' resolved takes).
        TreeMap<String, Long> keyAvail = new TreeMap<>();
        long emptyShulkers = 0;
        for (String key : counts.items().keySet()) {
            long units = Math.max(0, counts.total("item", key) - res.getOrDefault("item:" + key, 0L));
            if (units == 0) continue;
            String id = counts.itemIds().getOrDefault(key, IndexStore.OrderCounts.baseOf(key));
            if (IndexStore.OrderCounts.isShulkerBox(id)) {
                emptyShulkers += units;
                continue;
            }
            keyAvail.put(key, units);
        }
        Map<String, Long> avail = new HashMap<>(); // match -> units
        Map<String, Long> baseAvail = new HashMap<>();
        // The index keys of every match key and item, plain key first and then in key order (built once for the whole order).
        Map<String, List<String>> byMatch = new HashMap<>(), byBase = new HashMap<>();
        for (String k : keyAvail.keySet()) {
            String m = counts.matchOf(k);
            if (m != null) byMatch.computeIfAbsent(m, x -> new ArrayList<>()).add(k);
            byBase.computeIfAbsent(IndexStore.OrderCounts.baseOf(k), x -> new ArrayList<>()).add(k);
        }
        for (Map<String, List<String>> groups : List.of(byMatch, byBase)) {
            groups.forEach((what, l) -> {
                if (l.remove(what)) l.add(0, what);
            });
        }
        for (Map.Entry<String, Long> e : keyAvail.entrySet()) {
            baseAvail.merge(IndexStore.OrderCounts.baseOf(e.getKey()), e.getValue(), Long::sum);
            String m = counts.matchOf(e.getKey());
            if (m != null) avail.merge(m, e.getValue(), Long::sum);
        }
        // How many of the first kit could be built at most (the message says "only N ... can be made").
        long canBuild = Math.min(emptyShulkers, MAX_PER_LINE);
        for (Map.Entry<String, Long> e : firstPerKey.entrySet()) {
            long pool = e.getKey().endsWith("#*") ? baseAvail.getOrDefault(IndexStore.OrderCounts.baseOf(e.getKey()), 0L) : avail.getOrDefault(firstMatch.get(e.getKey()), 0L);
            canBuild = Math.min(canBuild, Math.max(0, pool) / e.getValue());
        }
        long possible = Math.min(kitLines.get(0).count(), firstReady + canBuild);
        // Resolve every need into index keys, exact needs first (an any-variant need takes what they leave): plain key first, then by key.
        Map<String, Long> shortKeys = new TreeMap<>();
        TreeMap<String, Map<String, Long>> taken = new TreeMap<>(); // take entry (match, or "base#*") -> key -> units
        for (Map.Entry<String, Long> e : needExact.entrySet()) {
            Map<String, Long> got = new TreeMap<>();
            long left = e.getValue();
            for (String k : byMatch.getOrDefault(e.getKey(), List.of())) {
                long n = Math.min(left, keyAvail.get(k));
                if (n <= 0) continue;
                got.put(k, n);
                keyAvail.merge(k, -n, Long::sum);
                left -= n;
            }
            if (left > 0) shortKeys.merge(keyOfMatch.getOrDefault(e.getKey(), e.getKey()), left, Long::sum);
            if (!got.isEmpty()) taken.put(e.getKey(), got);
        }
        for (Map.Entry<String, Long> e : needWild.entrySet()) {
            Map<String, Long> got = new TreeMap<>();
            long left = e.getValue();
            for (String k : byBase.getOrDefault(e.getKey(), List.of())) {
                long n = Math.min(left, keyAvail.get(k));
                if (n <= 0) continue;
                got.put(k, n);
                keyAvail.merge(k, -n, Long::sum);
                left -= n;
            }
            if (left > 0) shortKeys.merge(e.getKey() + "#*", left, Long::sum);
            if (!got.isEmpty()) taken.put(e.getKey() + "#*", got);
        }
        if (shulkers > emptyShulkers) shortKeys.merge("minecraft:shulker_box", shulkers - emptyShulkers, Long::sum);
        int keyCount = 0;
        for (Map<String, Long> g : taken.values()) keyCount += g.size();
        if (taken.size() > MAX_TAKE || keyCount > MAX_TAKE) {
            throw new OrderStore.OrderException(400, "this order needs too many different items (at most " + MAX_TAKE + "); order fewer kits", firstAt);
        }

        List<OrderStore.Line> craftLines = new ArrayList<>();
        Map<String, Long> crafted = new HashMap<>();
        Map<String, Integer> craftAt = new HashMap<>();
        OrderStore.OrderException failed = null;
        if (!shortKeys.isEmpty()) {
            boolean craftable = craftAllowed && cap.craft();
            for (Map.Entry<String, Long> e : shortKeys.entrySet()) {
                if (!craftable || e.getKey().indexOf('#') >= 0 || e.getKey().equals("minecraft:shulker_box")) throw shortError(kitLines, firstIndex, shortKeys, itemName, settings, possible);
                craftLines.add(new OrderStore.Line("craft", e.getKey(), e.getValue()));
                crafted.put(e.getKey(), e.getValue());
                craftAt.put("craft:" + e.getKey(), firstAt);
            }
            failed = shortError(kitLines, firstIndex, shortKeys, itemName, settings, possible);
        }

        // What the bot takes from the stash: what was found (the shortfall is crafted). keys is where each entry comes from.
        List<OrderStore.Line> direct = new ArrayList<>();
        JsonArray takeJson = new JsonArray();
        Map<String, Long> directByKey = new TreeMap<>();
        for (Map.Entry<String, Map<String, Long>> e : taken.entrySet()) {
            JsonObject o = new JsonObject();
            long total = 0;
            JsonArray keys = new JsonArray();
            for (Map.Entry<String, Long> k : e.getValue().entrySet()) {
                total += k.getValue();
                JsonObject ko = new JsonObject();
                ko.addProperty("key", k.getKey());
                ko.addProperty("count", k.getValue());
                keys.add(ko);
                if (k.getKey().indexOf('#') < 0) directByKey.merge(k.getKey(), k.getValue(), Long::sum);
            }
            o.addProperty("match", e.getKey());
            o.addProperty("count", total);
            o.add("keys", keys);
            takeJson.add(o);
        }
        directByKey.forEach((k, n) -> direct.add(new OrderStore.Line("item", k, n)));
        JsonObject kit = new JsonObject();
        kit.addProperty("v", 1);
        kit.add("lines", lines);
        kit.add("take", takeJson);
        return new Plan(kit, direct, craftLines, craftAt, failed);
    }

    /** The 400 for kits that can't all be made: "only N complete X kits can be made: short Item ×n, ...", line and short. */
    private OrderStore.OrderException shortError(List<OrderStore.Line> kitLines, Map<String, Integer> firstIndex, Map<String, Long> shortKeys,
                                                 java.util.function.Function<String, String> itemName, KitStore.Settings settings, long possible) {
        OrderStore.Line first = kitLines.get(0);
        KitTemplate.Template t = settings.types().templates().get(first.key());
        StringBuilder sb = new StringBuilder();
        List<String> parts = new ArrayList<>();
        JsonArray shortList = new JsonArray();
        for (Map.Entry<String, Long> e : shortKeys.entrySet()) {
            parts.add(itemName.apply(e.getKey()) + " ×" + e.getValue());
            JsonObject o = new JsonObject();
            o.addProperty("key", e.getKey());
            o.addProperty("count", e.getValue());
            shortList.add(o);
        }
        sb.append("only ").append(possible).append(" complete ").append(t.name()).append(" kits can be made: short ").append(String.join(", ", parts));
        OrderStore.OrderException ex = new OrderStore.OrderException(400, sb.toString(), firstIndex.get(first.ref()));
        ex.shortItems = shortList;
        ex.kitName = t.name();
        ex.kitNumber = new ArrayList<>(settings.types().templates().keySet()).indexOf(first.key()) + 1;
        return ex;
    }
}
