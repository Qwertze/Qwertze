package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.porter.qwerte.stash.KitClassifier;
import com.porter.qwerte.stash.KitNames;
import com.porter.qwerte.stash.KitSettings;
import com.porter.qwerte.stash.KitTemplate;
import com.porter.qwerte.stash.KitTemplates;
import com.porter.qwerte.stash.KitTypes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * The Kit Builder (KIT-CONTRACT v2, revisions 3 and 4) apart from the widgets: the server's template list ({@code GET /api/v1/kits/templates}), the
 * editor's draft with its live checks, the body of a save, the words of the list, and the planning of "Import local kits". Pure. Everything from
 * the server is checked on the way in (keys, ids, container ids), texts are cleaned and the lists capped.
 */
public final class BuilderModel {
    private BuilderModel() {
    }

    public static final int SLOTS = 27;
    public static final int MAX_TEMPLATES = 50;
    public static final List<String> COLORS = List.of("plain", "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue",
        "brown", "green", "red", "black");

    /** One slot of a template: an index key or {@code <id>#*}; {@code resolved} false when the exact variant is not in the index any more. */
    public record Slot(String key, String match, int count, boolean resolved) {
        public boolean any() {
            return key.endsWith("#*");
        }

        public String base() {
            return KitTemplate.baseOf(key);
        }
    }

    public record Template(String id, String name, String category, String chest, String color, int keep, List<Slot> slots, String effectiveChest, String chestSource,
                           String effectiveChestStatus) {
        /** The distinct base ids of the slots. */
        public int kinds() {
            return bases(slots).size();
        }
    }

    public record Category(String id, String name) {
    }

    public record Preset(String id, String category, String name, String color, List<Slot> slots) {
    }

    public record Stock(long complete, long incomplete, long elsewhere, long carried, List<KitsV2.Missing> missing) {
        static final Stock NONE = new Stock(0, 0, 0, 0, List.of());
    }

    public record Capability(boolean kitOrders, boolean restock, boolean craft, boolean importer) {
    }

    public record Restock(String state, String template, String reason) {
    }

    public record Data(long rev, List<Template> templates, List<Category> categories, List<Preset> presets, Capability capability, Map<String, Stock> stock, Restock restock,
                       int maxTemplates, int maxCategories) {
        public Stock stock(String id) {
            Stock s = stock.get(id);
            return s == null ? Stock.NONE : s;
        }

        public Template template(String id) {
            for (Template t : templates) if (t.id().equals(id)) return t;
            return null;
        }

        public String categoryName(String id) {
            KitsV2.TypeWords w = KitsV2.words(id);
            if (w != null) return w.name();
            for (Category c : categories) if (c.id().equals(id)) return c.name();
            return "Custom";
        }
    }

    // ---------- reading the server's answer ----------

    private static final java.util.regex.Pattern KEY = java.util.regex.Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,128}(#([0-9a-f]{8,40}|\\*))?$");

    private static List<Slot> slots(JsonElement e) {
        List<Slot> out = new ArrayList<>(SLOTS);
        JsonArray a = Json.arr(e);
        if (a == null) return out;
        for (JsonElement s : a) {
            if (out.size() >= SLOTS) break;
            JsonObject o = Json.obj(s);
            if (o == null) {
                out.add(null);
                continue;
            }
            String key = Json.str(o, "key");
            if (!KEY.matcher(key).matches()) {
                out.add(null);
                continue;
            }
            String match = Json.str(o, "match");
            if (!KEY.matcher(match).matches()) match = key;
            long count = Json.lng(o, "count", 1);
            boolean resolved = !o.has("resolved") || Json.bool(o, "resolved");
            out.add(new Slot(key, match, (int) Math.max(1, Math.min(99, count)), resolved));
        }
        while (out.size() < SLOTS) out.add(null);
        return out;
    }

    private static String container(JsonObject o, String key) {
        String v = Json.str(o, key);
        return KitSettings.validId(v) ? v : "";
    }

    public static Data parse(JsonElement body) {
        JsonObject o = Json.obj(body);
        if (o == null) return null;
        List<Template> templates = new ArrayList<>();
        JsonArray ta = Json.arr(o.get("templates"));
        if (ta != null) {
            for (JsonElement e : ta) {
                JsonObject t = Json.obj(e);
                if (t == null || templates.size() >= MAX_TEMPLATES) continue;
                String id = Json.str(t, "id");
                String cat = Json.str(t, "category");
                if (!id.matches("^t-[0-9a-z]{8}$") || !KitSettings.validCategory(cat)) continue;
                String color = Json.str(t, "color");
                templates.add(new Template(id, Text.sanitize(Json.str(t, "name"), 50), cat, container(t, "chest"), COLORS.contains(color) ? color : "", (int) Math.max(0, Math.min(27, Json.lng(t, "keep", 0))),
                    slots(t.get("slots")), container(t, "effectiveChest"), Json.str(t, "chestSource"), Json.str(t, "effectiveChestStatus")));
            }
        }
        List<Category> cats = new ArrayList<>();
        JsonArray ca = Json.arr(o.get("categories"));
        if (ca != null) {
            for (JsonElement e : ca) {
                JsonObject c = Json.obj(e);
                if (c == null || cats.size() >= 16) continue;
                String id = Json.str(c, "id");
                if (KitSettings.validCategory(id) && !KitTypes.TYPE_IDS.contains(id)) cats.add(new Category(id, Text.sanitize(Json.str(c, "name"), 32)));
            }
        }
        List<Preset> presets = new ArrayList<>();
        JsonArray pa = Json.arr(o.get("presets"));
        if (pa != null) {
            for (JsonElement e : pa) {
                JsonObject p = Json.obj(e);
                if (p == null || presets.size() >= 40) continue;
                String cat = Json.str(p, "category");
                if (!KitSettings.validCategory(cat)) continue;
                String color = Json.str(p, "color");
                presets.add(new Preset(Text.sanitize(Json.str(p, "id"), 32), cat, Text.sanitize(Json.str(p, "name"), 50), COLORS.contains(color) ? color : "", slots(p.get("slots"))));
            }
        }
        JsonObject cap = Json.obj(o.get("capability"));
        Capability capability = new Capability(cap != null && Json.bool(cap, "kitOrders"), cap != null && Json.bool(cap, "restock"), cap != null && Json.bool(cap, "craft"),
            cap != null && Json.bool(cap, "import"));
        Map<String, Stock> stock = new LinkedHashMap<>();
        JsonObject so = Json.obj(o.get("stock"));
        if (so != null) {
            for (Map.Entry<String, JsonElement> e : so.entrySet()) {
                JsonObject s = Json.obj(e.getValue());
                if (s == null || !e.getKey().matches("^t-[0-9a-z]{8}$")) continue;
                List<KitsV2.Missing> missing = new ArrayList<>();
                JsonArray ma = Json.arr(s.get("missing"));
                if (ma != null) {
                    for (JsonElement m : ma) {
                        JsonObject mo = Json.obj(m);
                        if (mo != null && missing.size() < 27) missing.add(new KitsV2.Missing(Text.sanitize(Json.str(mo, "key"), 160), Math.max(0, Json.lng(mo, "count", 0))));
                    }
                }
                stock.put(e.getKey(), new Stock(Math.max(0, Json.lng(s, "complete", 0)), Math.max(0, Json.lng(s, "incomplete", 0)), Math.max(0, Json.lng(s, "elsewhere", 0)),
                    Math.max(0, Json.lng(s, "carried", 0)), missing));
            }
        }
        JsonObject ro = Json.obj(o.get("restock"));
        Restock restock = ro == null ? null : new Restock(Json.str(ro, "state"), Json.str(ro, "template"), Json.str(ro, "reason"));
        JsonObject limits = Json.obj(o.get("limits"));
        return new Data(Json.lng(o, "rev", 0), templates, cats, presets, capability, stock, restock, limits == null ? MAX_TEMPLATES : (int) Json.lng(limits, "maxTemplates", MAX_TEMPLATES),
            limits == null ? 16 : (int) Json.lng(limits, "maxCategories", 16));
    }

    public static java.util.Set<String> bases(List<Slot> slots) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Slot s : slots) if (s != null) out.add(s.base());
        return out;
    }

    // ---------- the editor's draft ----------

    /** A slot being edited: the key (an index key or {@code <id>#*}) and the count. */
    public record EditSlot(String key, int count) {
        public boolean any() {
            return key.endsWith("#*");
        }

        public String base() {
            return KitTemplate.baseOf(key);
        }
    }

    /** The template being edited: a new one ({@code id} null) or a copy of a stored one. Mutable; the widgets read and change it. */
    public static final class Draft {
        public String id;
        public String name = "";
        public String category = "pvp";
        /** A container id, or "" for the category's chest. */
        public String chest = "";
        /** "", "plain" or a dye colour: "" is any colour. */
        public String color = "";
        public int keep;
        public final EditSlot[] slots = new EditSlot[SLOTS];

        public static Draft of(Template t) {
            Draft d = new Draft();
            d.id = t.id();
            d.name = t.name();
            d.category = t.category();
            d.chest = t.chest();
            d.color = t.color();
            d.keep = t.keep();
            for (int i = 0; i < SLOTS && i < t.slots().size(); i++) {
                Slot s = t.slots().get(i);
                if (s != null) d.slots[i] = new EditSlot(s.key(), s.count());
            }
            return d;
        }

        public static Draft ofPreset(Preset p) {
            Draft d = new Draft();
            d.name = p.name();
            d.category = p.category();
            d.color = p.color();
            for (int i = 0; i < SLOTS && i < p.slots().size(); i++) {
                Slot s = p.slots().get(i);
                if (s != null) d.slots[i] = new EditSlot(s.key(), s.count());
            }
            return d;
        }

        /** A new unsaved copy (the name gets " (copy)"). */
        public Draft copy() {
            Draft d = new Draft();
            d.id = null;
            d.name = name;
            d.category = category;
            d.chest = chest;
            d.color = color;
            d.keep = keep;
            System.arraycopy(slots, 0, d.slots, 0, SLOTS);
            return d;
        }

        public int kinds() {
            java.util.Set<String> b = new java.util.HashSet<>();
            for (EditSlot s : slots) if (s != null) b.add(s.base());
            return b.size();
        }

        public boolean empty() {
            for (EditSlot s : slots) if (s != null) return false;
            return true;
        }

        /** The same contents as {@code other}: the multiset of (key, count) over the slots, slot order ignored. */
        public boolean sameContents(Template other) {
            Map<String, Integer> mine = new java.util.TreeMap<>(), theirs = new java.util.TreeMap<>();
            for (EditSlot s : slots) if (s != null) mine.merge(s.key(), s.count(), Integer::sum);
            for (Slot s : other.slots()) if (s != null) theirs.merge(s.key(), s.count(), Integer::sum);
            return mine.equals(theirs);
        }
    }

    /** Why a name can't be used, or null: 1..50 characters, no section sign or control character, a valid key, unique among the stash's templates. */
    public static String nameProblem(String name, String ownId, List<Template> all) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return "The kit needs a name.";
        if (n.length() > 50) return "The name is at most 50 characters.";
        if (n.indexOf('§') >= 0 || hidden(n)) return "The name can't hold a section sign or a control character.";
        String key = KitNames.normalize(n);
        if (!KitNames.validKey(key)) return "That name can't be used.";
        for (Template t : all) if (!t.id().equals(ownId) && KitNames.normalize(t.name()).equals(key)) return "Another kit already has that name.";
        return null;
    }

    private static boolean hidden(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) return true;
        }
        return false;
    }

    /** The live checks under the grid (website wording): what stops Save. Empty when the draft can be saved. */
    public static List<String> problems(Draft d, List<Template> all, Function<String, Integer> maxStack) {
        List<String> out = new ArrayList<>();
        String np = nameProblem(d.name, d.id, all);
        if (np != null) out.add(np);
        if (d.kinds() < 2) out.add("A kit needs at least two kinds of item.");
        for (EditSlot s : d.slots) {
            if (s == null) continue;
            int max = Math.max(1, maxStack.apply(s.base()));
            if (s.count() < 1 || s.count() > max) {
                out.add("A slot holds 1 to " + max + " of that item.");
                break;
            }
        }
        if (d.kinds() >= 2) {
            for (Template t : all) {
                if (t.id().equals(d.id)) continue;
                if (d.sameContents(t)) {
                    out.add("Same contents as " + t.name() + ".");
                    break;
                }
            }
        }
        return out;
    }

    /** What the stash could build right now of this draft, and what is short for one more kit. */
    public record Builds(long now, List<KitsV2.Missing> shortKeys) {
    }

    /**
     * "Builds now: N (short: ...)": per slot key the needs of one kit against the stash's counts. {@code have} gives the count of an exact index key;
     * {@code haveBase} the count of every key of a base id together (for an any-variant slot).
     */
    public static Builds builds(Draft d, ToLongFunction<String> have, ToLongFunction<String> haveBase) {
        Map<String, Long> need = new LinkedHashMap<>();
        for (EditSlot s : d.slots) if (s != null) need.merge(s.key(), (long) s.count(), Long::sum);
        if (need.isEmpty()) return new Builds(0, List.of());
        long min = Long.MAX_VALUE;
        List<KitsV2.Missing> shortKeys = new ArrayList<>();
        for (Map.Entry<String, Long> e : need.entrySet()) {
            String key = e.getKey();
            long got = key.endsWith("#*") ? haveBase.applyAsLong(KitTemplate.baseOf(key)) : have.applyAsLong(key);
            min = Math.min(min, got / e.getValue());
            if (got < e.getValue()) shortKeys.add(new KitsV2.Missing(key, e.getValue() - got));
        }
        shortKeys.sort(java.util.Comparator.comparingLong((KitsV2.Missing m) -> -m.count()).thenComparing(KitsV2.Missing::key));
        return new Builds(min == Long.MAX_VALUE ? 0 : min, shortKeys);
    }

    public static String buildsText(Builds b, Function<String, String> itemName) {
        StringBuilder sb = new StringBuilder("Builds now: ").append(b.now());
        if (!b.shortKeys().isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (KitsV2.Missing m : b.shortKeys()) {
                if (parts.size() >= 4) break;
                parts.add(itemName.apply(m.key()) + " ×" + m.count());
            }
            sb.append(" (short: ").append(String.join(", ", parts)).append(b.shortKeys().size() > 4 ? ", ..." : "").append(')');
        }
        return sb.toString();
    }

    /** The body of a save: {@code POST /api/v1/kits/template}. */
    public static JsonObject saveBody(String server, String stash, Draft d) {
        JsonObject body = new JsonObject();
        body.addProperty("server", server);
        body.addProperty("stash", stash);
        JsonObject t = new JsonObject();
        if (d.id != null) t.addProperty("id", d.id);
        t.addProperty("name", d.name.trim());
        t.addProperty("category", d.category);
        if (d.chest.isEmpty()) t.add("chest", JsonNull.INSTANCE);
        else t.addProperty("chest", d.chest);
        if (d.color.isEmpty()) t.add("color", JsonNull.INSTANCE);
        else t.addProperty("color", d.color);
        t.addProperty("keep", d.keep);
        JsonArray slots = new JsonArray();
        for (EditSlot s : d.slots) {
            if (s == null) {
                slots.add(JsonNull.INSTANCE);
                continue;
            }
            JsonObject so = new JsonObject();
            so.addProperty("key", s.key());
            so.addProperty("count", s.count());
            slots.add(so);
        }
        t.add("slots", slots);
        body.add("template", t);
        return body;
    }

    // ---------- the list's words ----------

    /** "N in stock (M elsewhere)", with the incomplete copies and what they are short of. */
    public static String stockText(Stock s, Function<String, String> itemName) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.complete()).append(" complete");
        if (s.incomplete() > 0) sb.append(", ").append(s.incomplete()).append(" incomplete");
        if (s.elsewhere() > 0) sb.append(" (").append(s.elsewhere()).append(" elsewhere)");
        if (s.carried() > 0) sb.append(", ").append(s.carried()).append(" carried");
        if (s.incomplete() > 0 && !s.missing().isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (KitsV2.Missing m : s.missing()) {
                if (parts.size() >= 3) break;
                parts.add(itemName.apply(m.key()) + " ×" + m.count());
            }
            sb.append(": short ").append(String.join(", ", parts)).append(s.missing().size() > 3 ? ", ..." : "");
        }
        return sb.toString();
    }

    public static String restockText(Restock r, String templateId) {
        if (r == null || !templateId.equals(r.template())) return "";
        return switch (r.state()) {
            case "building" -> "restocking";
            case "waiting" -> r.reason().equals("topping_up") ? "topping up" : "waiting for items";
            case "blocked" -> "restock blocked";
            default -> "";
        };
    }

    public static final String NO_KIT_ORDERS = "This bot can't build kits yet: the kit is saved and sorted, but can't be ordered or restocked.";
    public static final String NO_CRAFT = "Short items won't be crafted.";
    public static final String MATCH_NOTE = "Matches boxes with the same items, enchantments and contents; item names, damage and repair cost don't matter.";
    public static final String UNRESOLVED = "This variant isn't in the stash any more: pick it again, or use any variant.";

    // ---------- Import local kits ----------

    /** A saved local kit as the import sees it: the name and its 27 slots (null for an empty one). */
    public record LocalKit(String name, List<ImportSlot> slots) {
    }

    /** @param id the item id with its namespace; {@code key} its index key, {@code match} its match key; {@code count} the stack size */
    public record ImportSlot(String id, String key, String match, int count) {
    }

    /** What to do with one local kit: send {@code body} (the template), or skip it for {@code skip}. */
    public record ImportItem(String name, JsonObject template, String skip) {
        public boolean skipped() {
            return skip != null;
        }
    }

    private static String cleanName(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '§' || Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /**
     * Plans the import of local kits (revision 4, "Retiring the addon's local Kit Builder"): the name is cut to 50 and gets " (2)", " (3)" on a clash with
     * a template or an earlier import; the category comes from the v2 classifier (the assortment's type, else {@code other}); chest, colour and keep
     * are null, null and 0. A kit of one kind of item, and one that is a complete copy of an existing template, are skipped.
     */
    public static List<ImportItem> planImport(List<LocalKit> kits, List<Template> existing, KitTypes table, String server, String stash) {
        return planImport(kits, existing, table, server, stash, Integer.MAX_VALUE);
    }

    /** What the match of two local kits is judged on: the match keys with their totals, slot order and splits ignored. */
    private static String contentsOf(List<ImportSlot> slots) {
        Map<String, Integer> totals = new java.util.TreeMap<>();
        for (ImportSlot s : slots) if (s != null) totals.merge(s.match(), s.count(), Integer::sum);
        return totals.toString();
    }

    /**
     * As above, and: a kit with the same contents as an earlier one of this batch is skipped too ("same contents as X"), and after {@code room} kits
     * (the stash's limit less the templates it has) the rest are skipped ("the stash is at its limit of kits").
     */
    public static List<ImportItem> planImport(List<LocalKit> kits, List<Template> existing, KitTypes table, String server, String stash, int room) {
        List<ImportItem> out = new ArrayList<>();
        Map<String, String> batch = new LinkedHashMap<>();
        int planned = 0;
        List<String> usedNames = new ArrayList<>();
        for (Template t : existing) usedNames.add(KitNames.normalize(t.name()));
        List<KitTemplate> parsed = new ArrayList<>();
        for (Template t : existing) {
            try {
                parsed.add(KitTemplate.parse(toJson(t)));
            } catch (KitTemplate.Invalid ex) {
                // a stored template this build can't read is left out of the duplicate check
            }
        }
        for (LocalKit k : kits) {
            String base = cleanName(k.name());
            List<KitTemplates.BoxStack> stacks = new ArrayList<>();
            java.util.Set<String> kinds = new java.util.HashSet<>();
            for (ImportSlot s : k.slots()) {
                if (s == null) continue;
                stacks.add(new KitTemplates.BoxStack(s.id(), s.match(), s.count()));
                kinds.add(s.id());
            }
            if (base.isEmpty() || !KitNames.validKey(KitNames.normalize(base))) {
                out.add(new ImportItem(k.name(), null, "the name can't be used"));
                continue;
            }
            if (kinds.size() < 2) {
                out.add(new ImportItem(base, null, "one kind of item"));
                continue;
            }
            String same = null;
            for (KitTemplate t : parsed) {
                if (KitTemplates.match(t, stacks, null).state() == KitTemplates.State.COMPLETE) {
                    same = t.name();
                    break;
                }
            }
            if (same != null) {
                out.add(new ImportItem(base, null, "same contents as " + same));
                continue;
            }
            String contents = contentsOf(k.slots());
            if (batch.containsKey(contents)) {
                out.add(new ImportItem(base, null, "same contents as " + batch.get(contents)));
                continue;
            }
            if (planned >= room) {
                out.add(new ImportItem(base, null, "the stash is at its limit of kits"));
                continue;
            }
            String name = uniqueName(base, usedNames);
            usedNames.add(KitNames.normalize(name));
            batch.put(contents, name);
            planned++;
            String category = "other";
            if (table != null) {
                KitClassifier.Result r = KitClassifier.classify(table, stacks, KitNames.normalize(name), null);
                if (r.kit() && r.type() != null) category = r.type();
            }
            JsonObject t = new JsonObject();
            t.addProperty("name", name);
            t.addProperty("category", category);
            t.add("chest", JsonNull.INSTANCE);
            t.add("color", JsonNull.INSTANCE);
            t.addProperty("keep", 0);
            JsonArray slots = new JsonArray();
            for (ImportSlot s : k.slots()) {
                if (s == null) {
                    slots.add(JsonNull.INSTANCE);
                    continue;
                }
                JsonObject so = new JsonObject();
                so.addProperty("key", s.key());
                so.addProperty("match", s.match());
                so.addProperty("count", s.count());
                slots.add(so);
            }
            t.add("slots", slots);
            JsonObject body = new JsonObject();
            body.addProperty("server", server);
            body.addProperty("stash", stash);
            body.add("template", t);
            out.add(new ImportItem(name, body, null));
        }
        return out;
    }

    private static String uniqueName(String base, List<String> usedNormalized) {
        String name = base.length() > 50 ? base.substring(0, 50).trim() : base;
        if (!usedNormalized.contains(KitNames.normalize(name))) return name;
        for (int n = 2; n < 1000; n++) {
            String suffix = " (" + n + ")";
            String cut = base.length() + suffix.length() > 50 ? base.substring(0, 50 - suffix.length()).trim() : base;
            String candidate = cut + suffix;
            if (!usedNormalized.contains(KitNames.normalize(candidate))) return candidate;
        }
        return name;
    }

    private static JsonObject toJson(Template t) {
        JsonObject o = new JsonObject();
        o.addProperty("id", t.id());
        o.addProperty("name", t.name());
        o.addProperty("category", t.category());
        if (t.chest().isEmpty()) o.add("chest", JsonNull.INSTANCE);
        else o.addProperty("chest", t.chest());
        if (t.color().isEmpty()) o.add("color", JsonNull.INSTANCE);
        else o.addProperty("color", t.color());
        o.addProperty("keep", t.keep());
        JsonArray slots = new JsonArray();
        for (Slot s : t.slots()) {
            if (s == null) {
                slots.add(JsonNull.INSTANCE);
                continue;
            }
            JsonObject so = new JsonObject();
            so.addProperty("key", s.key());
            so.addProperty("match", s.match());
            so.addProperty("count", s.count());
            slots.add(so);
        }
        o.add("slots", slots);
        return o;
    }

    /** "Imported 4, skipped 1: one kind of item" (the reasons, distinct, after the colon). */
    public static String summary(int imported, List<ImportItem> items, List<String> failed) {
        return summary(imported, items, failed, false);
    }

    /** As above; with {@code hide} (streamer display) a reason never names a kit ("same contents as another kit"). */
    public static String summary(int imported, List<ImportItem> items, List<String> failed, boolean hide) {
        List<String> reasons = new ArrayList<>();
        int skipped = 0;
        for (ImportItem i : items) {
            if (!i.skipped()) continue;
            skipped++;
            String why = hide && i.skip().startsWith("same contents as ") ? "same contents as another kit" : i.skip();
            if (!reasons.contains(why)) reasons.add(why);
        }
        StringBuilder sb = new StringBuilder("Imported ").append(imported);
        if (skipped > 0) sb.append(", skipped ").append(skipped).append(": ").append(String.join("; ", reasons));
        if (!failed.isEmpty()) sb.append(". Not saved: ").append(String.join("; ", failed));
        return sb.toString();
    }
}
