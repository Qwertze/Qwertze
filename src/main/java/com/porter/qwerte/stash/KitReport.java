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
     * @param why   (v2) the classifier's verdict, null in a v1 report; {@code found} the kit template the box is a copy of, complete or part built, or null
     */
    public record Shulker(String name, KitScore.Result score, KitClassifier.Result why, KitTemplates.Found found) {
        /** A shulker of the v1 report: no classifier verdict. */
        public Shulker(String name, KitScore.Result score) {
            this(name, score, null, null);
        }
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


    // ---------- the v2 report (KIT-CONTRACT v2, revisions 2 to 4) ----------

    public static final int MAX_OTHERS = 1024;
    public static final int MAX_MISSING = 27;

    /** One kit type of an entry (v2): how many boxes, and the reason of the first of them. */
    private static final class TypeAgg {
        final String type;
        final String template;
        int count = 1;
        final KitClassifier.Result why;

        TypeAgg(KitClassifier.Result why) {
            this.type = why.type();
            this.template = why.template();
            this.why = why;
        }
    }

    /** One entry: the kits of one key (null: unnamed) in one container, or the carried ones of one key (container null). */
    private static final class Agg {
        final String key;
        String name;
        final String container;
        int count = 1;
        KitScore.Result best;
        int minTotal;
        final Map<String, TypeAgg> types = new LinkedHashMap<>();

        Agg(String key, String name, String container, KitScore.Result score) {
            this.key = key;
            this.name = name;
            this.container = container;
            this.best = score;
            this.minTotal = score.total();
        }

        /** The name is the smallest spelling, the score the highest total, minTotal the lowest: stack order never matters. */
        void add(String otherName, KitScore.Result score) {
            count++;
            if (otherName != null && (name == null || otherName.compareTo(name) < 0)) name = otherName;
            if (higher(score, best)) best = score;
            minTotal = Math.min(minTotal, score.total());
        }

        void addType(KitClassifier.Result why) {
            if (why == null || why.type() == null) return;
            String k = why.type() + "|" + (why.template() == null ? "" : why.template());
            TypeAgg t = types.get(k);
            if (t == null) types.put(k, new TypeAgg(why));
            else t.count++;
        }
    }

    /** A named box that is not a kit, per key and container. */
    private static final class Other {
        final String key;
        String name;
        final String container;
        int count = 1;
        final KitClassifier.Result why;

        Other(String key, String name, String container, KitClassifier.Result why) {
            this.key = key;
            this.name = name;
            this.container = container;
            this.why = why;
        }
    }

    /** A box that is a copy of a kit template. {@code container} is null for a carried box. */
    private record TplHit(String template, String container, KitTemplates.State state, List<KitTemplates.Missing> missing) {
    }

    /**
     * The v2 report: boxes that are kits by the classifier, with their reasons; the v1 score only fills {@code score} (display on old servers).
     * Adds {@code classifier}, {@code others} (named boxes that are not kits), {@code templates} (complete and incomplete copies of each kit template)
     * and {@code restock} (the addon has none yet: off). Every shulker needs its {@code why}.
     * @param fingerprint the kit table's fingerprint
     * @param settings    the cached kit settings (templates, categories), or null
     */
    public static Built buildV2(String server, String stash, String player, List<BoxInfo> boxes, List<Shulker> carried, String fingerprint, KitSettings settings) {
        Map<String, String> memo = new java.util.HashMap<>();
        Map<String, Container> containers = new LinkedHashMap<>();
        Map<String, Agg> kits = new LinkedHashMap<>();
        Map<String, Other> others = new LinkedHashMap<>();
        List<TplHit> hits = new ArrayList<>();
        List<KitTemplate> templates = settings == null || settings.types() == null ? List.of() : settings.types().templates();
        for (BoxInfo box : boxes) {
            String id = box.id();
            if (!KitSettings.validId(id) || containers.containsKey(id)) continue;
            int size = box.size() >= 54 ? 54 : 27;
            boolean holds = false;
            for (Shulker sh : box.shulkers()) {
                if (sh.why() == null) continue;
                String key = normalized(memo, sh.name());
                String shown = key == null ? null : name(sh.name(), key);
                if (sh.found() != null) hits.add(new TplHit(sh.found().template().id(), id, sh.found().match().state(), sh.found().match().missing()));
                if (!sh.why().kit()) {
                    // A named box that is not a kit is listed with the reason, one entry per key and container
                    if (key != null) {
                        String k = key + "\t" + id;
                        Other o = others.get(k);
                        if (o == null) others.put(k, new Other(key, shown, id, sh.why()));
                        else {
                            o.count++;
                            if (shown != null && (o.name == null || shown.compareTo(o.name) < 0)) o.name = shown;
                        }
                    }
                    continue;
                }
                holds = true;
                String k = (key == null ? "" : "k" + key) + "\t" + id;
                Agg agg = kits.get(k);
                if (agg == null) kits.put(k, agg = new Agg(key, shown, id, sh.score()));
                else agg.add(shown, sh.score());
                agg.addType(sh.why());
            }
            String label = box.label() == null ? null : clean(box.label(), MAX_LABEL);
            containers.put(id, new Container(id, label == null || label.isEmpty() ? null : label, size, Math.max(0, Math.min(size, box.free())), box.pickable(),
                Math.max(0, box.readAt()), holds));
        }

        Map<String, Agg> carriedAgg = new LinkedHashMap<>();
        for (Shulker sh : carried) {
            if (sh.why() == null) continue;
            if (sh.found() != null) hits.add(new TplHit(sh.found().template().id(), null, sh.found().match().state(), sh.found().match().missing()));
            if (!sh.why().kit()) continue;
            String key = normalized(memo, sh.name());
            String shown = key == null ? null : name(sh.name(), key);
            String k = key == null ? "" : "k" + key;
            Agg agg = carriedAgg.get(k);
            if (agg == null) carriedAgg.put(k, agg = new Agg(key, shown, null, sh.score()));
            else agg.add(shown, sh.score());
            agg.addType(sh.why());
        }

        List<Container> list = new ArrayList<>(containers.values());
        List<Agg> kitList = new ArrayList<>(kits.values());
        list.sort(Comparator.comparing((Container c) -> !c.holdsKits()).thenComparing(c -> !c.pickable()).thenComparing(Container::id));
        int limit = Math.min(MAX_CONTAINERS, list.size());
        List<Other> otherList = new ArrayList<>(others.values());
        otherList.sort(Comparator.comparing((Other o) -> o.key).thenComparing(o -> o.container));
        if (otherList.size() > MAX_OTHERS) otherList = new ArrayList<>(otherList.subList(0, MAX_OTHERS));
        Set<String> pickable = new HashSet<>();
        for (Container c : list) if (c.pickable()) pickable.add(c.id());
        JsonArray templatesJson = templatesJson(templates, hits, pickable, settings);
        Built built = assembleV2(server, stash, player, list, limit, kitList, carriedAgg, fingerprint, otherList, templatesJson);
        while (built.json().length > MAX_BYTES && (limit > 0 || !otherList.isEmpty())) {
            // The named non-kits go first, then containers
            if (!otherList.isEmpty()) otherList = new ArrayList<>(otherList.subList(0, otherList.size() / 2));
            else limit = limit * 3 / 4;
            built = assembleV2(server, stash, player, list, limit, kitList, carriedAgg, fingerprint, otherList, templatesJson);
        }
        return built;
    }

    private static JsonArray templatesJson(List<KitTemplate> templates, List<TplHit> hits, Set<String> pickable, KitSettings settings) {
        JsonArray out = new JsonArray();
        List<KitTemplate> sorted = new ArrayList<>(templates);
        sorted.sort(Comparator.comparing(KitTemplate::id));
        for (KitTemplate t : sorted) {
            String target = settings == null ? null : KitRouting.templateTarget(t, settings);
            int complete = 0, incomplete = 0, elsewhere = 0, carried = 0;
            Map<String, KitTemplates.Missing> missing = new java.util.TreeMap<>();
            for (TplHit h : hits) {
                if (!h.template().equals(t.id())) continue;
                if (h.container() == null) {
                    if (h.state() == KitTemplates.State.COMPLETE) carried++;
                } else if (h.container().equals(target)) {
                    if (h.state() == KitTemplates.State.COMPLETE) complete++;
                    else {
                        incomplete++;
                        for (KitTemplates.Missing m : h.missing()) {
                            String k = m.key() + "\t" + m.match();
                            KitTemplates.Missing old = missing.get(k);
                            missing.put(k, new KitTemplates.Missing(m.key(), m.match(), (old == null ? 0 : old.count()) + m.count()));
                        }
                    }
                } else if (h.state() == KitTemplates.State.COMPLETE && pickable.contains(h.container())) elsewhere++;
            }
            List<KitTemplates.Missing> list = new ArrayList<>(missing.values());
            list.sort(Comparator.comparingInt((KitTemplates.Missing m) -> -m.count()).thenComparing(KitTemplates.Missing::key));
            JsonObject o = new JsonObject();
            o.addProperty("id", t.id());
            o.addProperty("complete", Math.min(4096, complete));
            o.addProperty("incomplete", Math.min(4096, incomplete));
            o.addProperty("elsewhere", Math.min(4096, elsewhere));
            o.addProperty("carried", Math.min(4096, carried));
            JsonArray miss = new JsonArray();
            for (KitTemplates.Missing m : list) {
                if (miss.size() >= MAX_MISSING) break;
                JsonObject mo = new JsonObject();
                mo.addProperty("match", m.match());
                mo.addProperty("key", m.key());
                mo.addProperty("count", Math.max(1, Math.min(4096, m.count())));
                miss.add(mo);
            }
            o.add("missing", miss);
            out.add(o);
        }
        return out;
    }

    private static Built assembleV2(String server, String stash, String player, List<Container> priority, int limit, List<Agg> allKits, Map<String, Agg> carriedAgg,
                                    String fingerprint, List<Other> otherList, JsonArray templates) {
        List<Container> list = new ArrayList<>(priority.subList(0, limit));
        list.sort(Comparator.comparing(Container::id));
        Set<String> ids = new HashSet<>();
        for (Container c : list) ids.add(c.id());

        List<Agg> kitList = new ArrayList<>();
        for (Agg k : allKits) if (ids.contains(k.container)) kitList.add(k);
        kitList.sort(Comparator.comparing((Agg a) -> a.key == null ? "" : "k" + a.key).thenComparing(a -> a.container));
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
        for (Agg k : kitList) {
            JsonObject o = new JsonObject();
            putKey(o, k);
            o.addProperty("container", k.container);
            o.addProperty("count", Math.max(1, Math.min(54, k.count)));
            o.add("score", scoreJson(k.best));
            o.addProperty("minTotal", Math.max(0, Math.min(40, k.minTotal)));
            o.add("types", typesJson(k, 54));
            kits.add(o);
        }
        JsonArray carried = new JsonArray();
        List<String> keys = new ArrayList<>(carriedAgg.keySet());
        keys.sort(null);
        for (String key : keys) {
            if (carried.size() >= MAX_CARRIED) break;
            Agg a = carriedAgg.get(key);
            JsonObject o = new JsonObject();
            putKey(o, a);
            o.addProperty("count", Math.max(1, Math.min(36, a.count)));
            o.add("score", scoreJson(a.best));
            o.add("types", typesJson(a, 36));
            carried.add(o);
        }
        JsonObject classifier = new JsonObject();
        classifier.addProperty("version", 2);
        classifier.addProperty("table", fingerprint);
        JsonArray othersJson = new JsonArray();
        for (Other o : otherList) {
            if (!ids.contains(o.container)) continue;
            JsonObject oo = new JsonObject();
            oo.addProperty("key", o.key);
            oo.addProperty("name", o.name == null ? o.key : o.name);
            oo.addProperty("container", o.container);
            oo.addProperty("count", Math.max(1, Math.min(54, o.count)));
            oo.add("why", whyJson(o.why));
            othersJson.add(oo);
        }
        // The addon has no restock yet (release 2): the state is off
        JsonObject restock = new JsonObject();
        restock.addProperty("state", "off");
        restock.add("template", JsonNull.INSTANCE);
        restock.add("reason", JsonNull.INSTANCE);

        // The hash leaves every readAt out: a read that found nothing changed is not a change
        JsonObject body = new JsonObject();
        body.add("containers", containers);
        body.add("kits", kits);
        body.add("carried", carried);
        body.add("classifier", classifier);
        body.add("others", othersJson);
        body.add("templates", templates);
        body.add("restock", restock);
        String hash = sha1("v2\n" + GSON_NULLS.toJson(body));
        for (int i = 0; i < containers.size(); i++) containers.get(i).getAsJsonObject().addProperty("readAt", reads.get(i));

        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("server", server);
        root.addProperty("stash", stash);
        root.addProperty("player", player);
        root.addProperty("hash", hash);
        root.addProperty("readAt", newest);
        root.add("classifier", classifier);
        root.add("containers", containers);
        root.add("kits", kits);
        root.add("carried", carried);
        root.add("others", othersJson);
        root.add("templates", templates);
        root.add("restock", restock);
        return new Built(server, stash, player, hash, GSON_NULLS.toJson(root).getBytes(StandardCharsets.UTF_8), containers.size(), kits.size(), carried.size());
    }

    /** The entry's types, one per (category, template), in type order (the seven, then custom categories by id), each with the first box's reason. */
    private static JsonArray typesJson(Agg a, int max) {
        List<TypeAgg> list = new ArrayList<>(a.types.values());
        KitTypes table = KitTypes.shared();
        list.sort(Comparator.comparingInt((TypeAgg t) -> table == null || table.rank(t.type) < 0 ? 100 : table.rank(t.type)).thenComparing(t -> t.type)
            .thenComparing(t -> t.template == null ? "" : t.template));
        JsonArray out = new JsonArray();
        for (TypeAgg t : list) {
            JsonObject o = new JsonObject();
            o.addProperty("type", t.type);
            if (t.template != null) o.addProperty("template", t.template);
            o.addProperty("count", Math.max(1, Math.min(max, t.count)));
            o.add("why", whyJson(t.why));
            out.add(o);
        }
        return out;
    }

    private static int n27(int v) {
        return Math.max(0, Math.min(27, v));
    }

    /** The classifier's result as the contract has it: code, type, slots, covered, kinds, need and minKinds for the near misses, items, words, template, missing. */
    static JsonObject whyJson(KitClassifier.Result r) {
        JsonObject o = new JsonObject();
        o.addProperty("code", r.code());
        if (r.type() == null) o.add("type", JsonNull.INSTANCE);
        else o.addProperty("type", r.type());
        o.addProperty("slots", n27(r.slots()));
        o.addProperty("covered", n27(r.covered()));
        o.addProperty("kinds", n27(r.kinds()));
        if (r.code().equals(KitClassifier.MIXED) || r.code().equals(KitClassifier.FEW_KINDS)) {
            o.addProperty("need", n27(r.need()));
            o.addProperty("minKinds", n27(r.minKinds()));
        }
        JsonArray items = new JsonArray();
        for (String i : r.items()) if (items.size() < 3) items.add(i);
        o.add("items", items);
        JsonArray words = new JsonArray();
        for (String w : r.words()) if (words.size() < 4) words.add(w);
        o.add("words", words);
        if (r.template() != null) o.addProperty("template", r.template());
        if (r.code().equals(KitClassifier.TEMPLATE_INCOMPLETE)) {
            JsonArray missing = new JsonArray();
            for (KitTemplates.Missing m : r.missing()) {
                if (missing.size() >= 8) break;
                JsonObject mo = new JsonObject();
                mo.addProperty("key", m.key());
                mo.addProperty("count", Math.max(1, Math.min(4096, m.count())));
                missing.add(mo);
            }
            o.add("missing", missing);
        }
        return o;
    }

    private static void putKey(JsonObject o, Agg a) {
        if (a.key == null) {
            o.add("key", JsonNull.INSTANCE);
            o.add("name", JsonNull.INSTANCE);
        } else {
            o.addProperty("key", a.key);
            o.addProperty("name", a.name == null ? a.key : a.name);
        }
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
