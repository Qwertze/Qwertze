package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The pages' view of a stash's kits: GET /api/v1/kits and GET /api/v1/kits/templates (KIT-CONTRACT.md and
 * KIT-CONTRACT-v2): settings, the latest report, the sort chain of every row and its status. Pure functions of their inputs.
 */
final class KitView {
    private KitView() {
    }

    // ---------- chain ----------

    record ChainEntry(String container, String source, String status) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("container", container);
            o.addProperty("source", source);
            o.addProperty("status", status);
            return o;
        }
    }

    static String chestStatus(String chest, Map<String, KitReports.Container> byId) {
        if (chest == null) return "unset";
        KitReports.Container c = byId.get(chest);
        if (c == null || !c.pickable()) return "missing";
        return c.free() == 0 ? "full" : "ok";
    }

    /**
     * The levels that are set, in order (KIT-CONTRACT-v2, "Sorting chain change"): the rule's container, the template's own
     * chest, the category's chest, the general kits chest. A level whose container is the same as the one before still counts.
     */
    static List<ChainEntry> chain(KitStore.Settings s, String key, String category, KitTemplate.Template template,
                                  Map<String, KitReports.Container> byId) {
        List<ChainEntry> out = new ArrayList<>();
        String rule = key == null ? null : s.rules().get(key);
        if (rule != null) out.add(new ChainEntry(rule, "rule", chestStatus(rule, byId)));
        if (template != null && template.chest() != null) out.add(new ChainEntry(template.chest(), "template", chestStatus(template.chest(), byId)));
        String cat = category == null ? null : s.types().chests().get(category);
        if (cat != null) out.add(new ChainEntry(cat, "typeChest", chestStatus(cat, byId)));
        if (s.kitsChest() != null) out.add(new ChainEntry(s.kitsChest(), "kitsChest", chestStatus(s.kitsChest(), byId)));
        return out;
    }

    /** The first usable entry, or -1. */
    private static int firstOk(List<ChainEntry> chain) {
        for (int i = 0; i < chain.size(); i++) if (chain.get(i).status().equals("ok")) return i;
        return -1;
    }

    private static String status(boolean wanted, boolean any, int carried, Map<String, Integer> locations, List<ChainEntry> chain) {
        if (wanted && !any) return "absent";
        if (chain.isEmpty()) return "none";
        boolean allMissing = true, anyFull = false;
        for (ChainEntry c : chain) {
            if (!c.status().equals("missing")) allMissing = false;
            if (c.status().equals("full")) anyFull = true;
        }
        if (allMissing) return "missing";
        if (carried == 0 && !locations.isEmpty()) {
            boolean home = true;
            for (String loc : locations.keySet()) {
                int at = -1;
                for (int i = 0; i < chain.size(); i++) {
                    if (chain.get(i).container().equals(loc)) {
                        at = i;
                        break;
                    }
                }
                if (at < 0) {
                    home = false;
                    break;
                }
                for (int i = 0; i < at; i++) {
                    if (chain.get(i).status().equals("ok")) {
                        home = false;
                        break;
                    }
                }
                if (!home) break;
            }
            if (home) return "home";
        }
        if (firstOk(chain) < 0 && anyFull) return "full";
        return "pending";
    }

    // ---------- GET /api/v1/kits ----------

    private static final class Row {
        String key;
        String name;
        String type;
        String template;
        int stored;
        int carried;
        int incomplete;
        KitReports.Score score;
        int minTotal = Integer.MAX_VALUE;
        KitReports.Why why;
        final TreeMap<String, Integer> locations = new TreeMap<>(KitNames::compareIds);

        void seeName(String n) {
            if (n != null && (name == null || n.compareTo(name) < 0)) name = n;
        }

        void see(KitReports.Score sc, int min) {
            if (score == null || sc.total() > score.total()) score = sc;
            minTotal = Math.min(minTotal, Math.min(min, sc.total()));
        }
    }

    static JsonObject build(long now, String server, String stash, KitStore.Settings s, KitReports.Report report, KitReports.Beat beat) {
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
        bot.addProperty("online", beat != null && now - beat.at() < IndexStore.ONLINE_WINDOW_MS);
        boolean typed = beat != null && beat.types();
        bot.addProperty("types", typed);
        bot.addProperty("typesSynced", typed && s.types().hash().equals(beat.hash2()));
        o.add("bot", bot);
        o.addProperty("typesHash", s.types().hash());
        if (report == null) {
            o.add("report", JsonNull.INSTANCE);
        } else {
            JsonObject r = new JsonObject();
            r.addProperty("receivedAt", report.receivedAt());
            r.addProperty("readAt", report.readAt());
            if (report.classifier() == null) {
                r.add("classifier", JsonNull.INSTANCE);
            } else {
                JsonObject c = new JsonObject();
                c.addProperty("version", report.classifier().version());
                c.addProperty("table", report.classifier().table());
                r.add("classifier", c);
            }
            o.add("report", r);
        }
        Map<String, KitReports.Container> byId = byId(report);
        JsonArray containers = new JsonArray();
        if (report != null) {
            for (KitReports.Container c : report.containers()) {
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

        // Rows: one per (key, type, template).
        Map<String, Row> rows = new HashMap<>();
        if (report != null) {
            for (KitReports.Stored k : report.kits()) {
                if (k.types().isEmpty()) {
                    Row r = row(rows, k.key(), null, null);
                    r.stored += k.count();
                    r.seeName(k.name());
                    r.see(k.score(), k.minTotal());
                    r.locations.merge(k.container(), k.count(), Integer::sum);
                } else {
                    for (KitReports.TypeCount tc : k.types()) {
                        Row r = row(rows, k.key(), tc.type(), tc.template());
                        r.stored += tc.count();
                        r.seeName(k.name());
                        r.see(k.score(), k.minTotal());
                        if (r.why == null) r.why = tc.why();
                        if (tc.why().code().equals("template_incomplete")) r.incomplete += tc.count();
                        r.locations.merge(k.container(), tc.count(), Integer::sum);
                    }
                }
            }
            for (KitReports.Carried k : report.carried()) {
                if (k.types().isEmpty()) {
                    Row r = row(rows, k.key(), null, null);
                    r.carried += k.count();
                    r.seeName(k.name());
                    r.see(k.score(), k.score().total());
                } else {
                    for (KitReports.TypeCount tc : k.types()) {
                        Row r = row(rows, k.key(), tc.type(), tc.template());
                        r.carried += tc.count();
                        r.seeName(k.name());
                        r.see(k.score(), k.score().total());
                        if (r.why == null) r.why = tc.why();
                        if (tc.why().code().equals("template_incomplete")) r.incomplete += tc.count();
                    }
                }
            }
        }
        // A rule or an override for a kit that isn't in the stash: a row of its own (status absent).
        java.util.Set<String> wantedKeys = new java.util.TreeSet<>(s.rules().keySet());
        s.types().treatAs().forEach((k, v) -> {
            if (!v.equals("none")) wantedKeys.add(k);
        });
        for (String key : wantedKeys) {
            boolean seen = false;
            for (Row r : rows.values()) if (key.equals(r.key)) seen = true;
            if (seen) continue;
            String as = s.types().treatAs().get(key);
            Row r = row(rows, key, as == null || as.equals("none") ? null : as, null);
            r.name = key;
        }
        List<Row> sorted = new ArrayList<>(rows.values());
        List<String> order = new ArrayList<>(KitTypes.IDS);
        order.addAll(s.types().categories().keySet());
        sorted.sort(Comparator.<Row>comparingInt(r -> r.type == null ? Integer.MAX_VALUE : order.indexOf(r.type))
            .thenComparing(r -> r.key, Comparator.nullsLast(Comparator.<String>naturalOrder()))
            .thenComparing(r -> r.template, Comparator.nullsLast(Comparator.<String>naturalOrder())));
        JsonArray out = new JsonArray();
        Map<String, Integer> perCategory = new HashMap<>();
        for (Row r : sorted) {
            String rule = r.key == null ? null : s.rules().get(r.key); // an unnamed kit can't have a rule
            KitTemplate.Template tpl = r.template == null ? null : s.types().templates().get(r.template);
            List<ChainEntry> chain = chain(s, r.key, r.type, tpl, byId);
            int ok = firstOk(chain);
            JsonObject ro = new JsonObject();
            ro.addProperty("key", r.key);
            ro.addProperty("name", r.name);
            ro.addProperty("type", r.type);
            String source = r.type == null ? null : r.why == null ? null : switch (r.why.code()) {
                case "override" -> "override";
                case "template" -> "template";
                case "template_incomplete" -> "template_incomplete";
                default -> "auto";
            };
            ro.addProperty("typeSource", source);
            ro.add("why", r.why == null ? JsonNull.INSTANCE : r.why.toJson());
            ro.addProperty("template", r.template);
            ro.addProperty("templateName", tpl == null ? null : tpl.name());
            ro.addProperty("count", r.stored + r.carried);
            ro.addProperty("carried", r.carried);
            ro.addProperty("incomplete", r.incomplete);
            ro.addProperty("treatAs", r.key == null ? null : s.types().treatAs().get(r.key));
            if (r.score == null) {
                ro.add("score", JsonNull.INSTANCE); // a rule for a kit that isn't in the stash
                ro.add("minTotal", JsonNull.INSTANCE);
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
            JsonArray ca = new JsonArray();
            chain.forEach(c -> ca.add(c.toJson()));
            ro.add("chain", ca);
            ro.addProperty("target", ok < 0 ? null : chain.get(ok).container());
            ro.addProperty("targetSource", ok < 0 ? null : chain.get(ok).source());
            ro.addProperty("fallback", ok > 0);
            ro.addProperty("status", status(rule != null || (r.key != null && s.types().treatAs().containsKey(r.key)),
                r.stored + r.carried > 0, r.carried, r.locations, chain));
            out.add(ro);
            if (r.type != null) perCategory.merge(r.type, r.stored + r.carried, Integer::sum);
        }
        o.add("rows", out);
        o.add("types", typesArray(s, byId, perCategory));
        JsonArray as = new JsonArray();
        s.types().treatAs().forEach((k, v) -> {
            JsonObject a = new JsonObject();
            a.addProperty("key", k);
            a.addProperty("type", v);
            as.add(a);
        });
        o.add("treatAs", as);
        o.add("others", others(s, report));
        JsonObject limits = new JsonObject();
        limits.addProperty("maxRules", KitStore.MAX_RULES);
        limits.addProperty("maxKey", KitNames.MAX_KEY);
        limits.addProperty("maxTreatAs", KitStore.MAX_TREAT_AS);
        o.add("limits", limits);
        return o;
    }

    private static Row row(Map<String, Row> rows, String key, String type, String template) {
        return rows.computeIfAbsent((key == null ? "n" : "k" + key) + "\u0000" + type + "\u0000" + template, x -> {
            Row r = new Row();
            r.key = key;
            r.type = type;
            r.template = template;
            return r;
        });
    }

    private static Map<String, KitReports.Container> byId(KitReports.Report report) {
        Map<String, KitReports.Container> byId = new HashMap<>();
        if (report != null) for (KitReports.Container c : report.containers()) byId.put(c.id(), c);
        return byId;
    }

    private static JsonArray typesArray(KitStore.Settings s, Map<String, KitReports.Container> byId, Map<String, Integer> counts) {
        JsonArray a = new JsonArray();
        List<String> ids = new ArrayList<>(KitTypes.IDS);
        ids.addAll(s.types().categories().keySet());
        for (String id : ids) {
            boolean custom = !KitTypes.isType(id);
            String chest = s.types().chests().get(id);
            JsonObject t = new JsonObject();
            t.addProperty("id", id);
            t.addProperty("name", custom ? s.types().categories().get(id) : KitTypes.name(id));
            t.addProperty("custom", custom);
            t.addProperty("chest", chest);
            t.addProperty("chestStatus", chestStatus(chest, byId));
            t.addProperty("count", counts.getOrDefault(id, 0));
            a.add(t);
        }
        return a;
    }

    /** Named boxes that are not kits (one row per key), plus an override "none" for a name the stash doesn't have. */
    private static JsonArray others(KitStore.Settings s, KitReports.Report report) {
        Map<String, JsonObject> byKey = new TreeMap<>();
        Map<String, TreeMap<String, Integer>> locs = new HashMap<>();
        if (report != null) {
            for (KitReports.Other x : report.others()) {
                JsonObject o = byKey.get(x.key());
                if (o == null) {
                    o = new JsonObject();
                    o.addProperty("key", x.key());
                    o.addProperty("name", x.name());
                    o.addProperty("count", 0);
                    o.add("why", x.why().toJson());
                    o.addProperty("treatAs", s.types().treatAs().get(x.key()));
                    o.addProperty("status", "present");
                    byKey.put(x.key(), o);
                    locs.put(x.key(), new TreeMap<>(KitNames::compareIds));
                }
                o.addProperty("count", o.get("count").getAsInt() + x.count());
                locs.get(x.key()).merge(x.container(), x.count(), Integer::sum);
            }
        }
        for (var e : s.types().treatAs().entrySet()) {
            if (!e.getValue().equals("none") || byKey.containsKey(e.getKey())) continue;
            JsonObject o = new JsonObject();
            o.addProperty("key", e.getKey());
            o.addProperty("name", e.getKey());
            o.addProperty("count", 0);
            o.add("why", JsonNull.INSTANCE);
            o.addProperty("treatAs", "none");
            o.addProperty("status", "absent");
            byKey.put(e.getKey(), o);
            locs.put(e.getKey(), new TreeMap<>(KitNames::compareIds));
        }
        JsonArray out = new JsonArray();
        for (var e : byKey.entrySet()) {
            JsonArray la = new JsonArray();
            locs.get(e.getKey()).forEach((c, n) -> {
                JsonObject l = new JsonObject();
                l.addProperty("container", c);
                l.addProperty("count", n);
                la.add(l);
            });
            e.getValue().add("locations", la);
            out.add(e.getValue());
        }
        return out;
    }

    // ---------- presets ----------

    private static JsonArray presets;

    /** The shipped Kit Builder presets (kit-presets.json), each slot with its match key filled in. Empty when the file is missing. */
    static synchronized JsonArray presets() {
        if (presets != null) return presets;
        JsonArray out = new JsonArray();
        try (InputStream in = KitView.class.getResourceAsStream("/kits/kit-presets.json")) {
            if (in != null) {
                JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                for (JsonElement p : root.getAsJsonArray("presets")) {
                    JsonObject po = p.getAsJsonObject().deepCopy();
                    for (JsonElement slot : po.getAsJsonArray("slots")) {
                        if (slot.isJsonObject() && !slot.getAsJsonObject().has("match")) {
                            slot.getAsJsonObject().addProperty("match", slot.getAsJsonObject().get("key").getAsString());
                        }
                    }
                    out.add(po);
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Kit presets can't be read (" + e.getMessage() + "); the Kit Builder starts empty.");
        }
        presets = out;
        return out;
    }

    // ---------- GET /api/v1/kits/templates ----------

    /** What the index says about item keys, for the "resolved" flag of an exact slot. counts may be null (no index). */
    static JsonObject templates(String server, String stash, KitStore.Settings s, KitReports.Report report, KitReports.Beat beat,
                                IndexStore.OrderCounts counts) {
        JsonObject o = new JsonObject();
        o.addProperty("server", server);
        o.addProperty("stash", stash);
        o.addProperty("rev", s.rev());
        Map<String, KitReports.Container> byId = byId(report);
        JsonArray list = new JsonArray();
        JsonObject stock = new JsonObject();
        for (KitTemplate.Template t : s.types().templates().values()) {
            JsonObject to = t.toJson();
            JsonArray slots = to.getAsJsonArray("slots");
            for (JsonElement e : slots) {
                if (!e.isJsonObject()) continue;
                JsonObject so = e.getAsJsonObject();
                String key = so.get("key").getAsString();
                boolean resolved = key.indexOf('#') < 0 || key.endsWith("#*") || (counts != null && counts.items().containsKey(key));
                so.addProperty("resolved", resolved);
            }
            List<ChainEntry> chain = chain(s, KitNames.normalize(t.name()), t.category(), t, byId);
            to.addProperty("effectiveChest", chain.isEmpty() ? null : chain.get(0).container());
            // The first chain entry (what the kit would be filed to if it were usable), not the first usable one; "typeChest" is "category" here.
            String src = chain.isEmpty() ? null : chain.get(0).source();
            to.addProperty("chestSource", "typeChest".equals(src) ? "category" : src);
            to.addProperty("effectiveChestStatus", chain.isEmpty() ? "unset" : chain.get(0).status());
            list.add(to);
            KitReports.Stock st = report == null ? null : report.stock(t.id());
            stock.add(t.id(), st == null ? new KitReports.Stock(t.id(), 0, 0, 0, 0, List.of()).toJson() : st.toJson());
        }
        o.add("templates", list);
        JsonArray cats = new JsonArray();
        for (var e : s.types().categories().entrySet()) {
            JsonObject c = new JsonObject();
            c.addProperty("id", e.getKey());
            c.addProperty("name", e.getValue());
            cats.add(c);
        }
        o.add("categories", cats);
        o.add("presets", presets().deepCopy());
        JsonObject cap = new JsonObject();
        KitReports.KitCap kc = beat == null ? null : beat.cap();
        boolean on = kc != null && kc.v() >= 1 && kc.enabled();
        cap.addProperty("kitOrders", on);
        cap.addProperty("restock", on && kc.restock());
        cap.addProperty("craft", on && kc.craft());
        cap.addProperty("import", kc != null && kc.importer());
        o.add("capability", cap);
        o.add("stock", stock);
        o.add("restock", report == null || report.restock() == null ? JsonNull.INSTANCE : report.restock().toJson());
        JsonObject limits = new JsonObject();
        limits.addProperty("maxTemplates", KitTemplate.MAX_TEMPLATES);
        limits.addProperty("maxCategories", KitTemplate.MAX_CATEGORIES);
        o.add("limits", limits);
        return o;
    }

    /**
     * Streamer mode: every container id of a templates body becomes "hidden", the names of the kits are "Kit 1", "Kit 2" ... and
     * the custom categories "Category 1" ... with neutral ids (c:1 ...; the templates' category follows), because their names and the
     * slugs made from them are text a user typed. The read-only Builder still groups by category.
     */
    static void hideContainers(JsonObject body) {
        java.util.Map<String, String> ids = new java.util.HashMap<>();
        JsonArray cats = body.getAsJsonArray("categories");
        int c = 0;
        for (JsonElement e : cats) {
            JsonObject co = e.getAsJsonObject();
            c++;
            ids.put(co.get("id").getAsString(), "c:" + c);
            co.addProperty("id", "c:" + c);
            co.addProperty("name", "Category " + c);
        }
        int n = 0;
        for (JsonElement e : body.getAsJsonArray("templates")) {
            JsonObject t = e.getAsJsonObject();
            n++;
            t.addProperty("name", "Kit " + n);
            String cat = t.get("category").getAsString();
            if (ids.containsKey(cat)) t.addProperty("category", ids.get(cat));
        }
        for (JsonElement e : body.getAsJsonArray("templates")) {
            JsonObject t = e.getAsJsonObject();
            for (String f : new String[]{"chest", "effectiveChest"}) {
                if (t.has(f) && !t.get(f).isJsonNull()) t.addProperty(f, "hidden");
            }
        }
    }
}
