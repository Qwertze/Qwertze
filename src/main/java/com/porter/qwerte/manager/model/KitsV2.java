package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwerte.stash.KitNames;
import com.porter.qwerte.stash.KitSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The v2 part of the Kits tab (KIT-CONTRACT v2): kit types, the reason a box is or is not a kit, the chain a kit follows, the named boxes that are
 * sorted normally, and the words for them, exactly as the website words them. Pure. Everything from the server is checked on the way in (ids,
 * keys, container ids), texts are cleaned and the lists capped. Streamer display never shows a kit name, a label or a position in a reason.
 */
public final class KitsV2 {
    private KitsV2() {
    }

    public static final int MAX_ROWS = 500;
    public static final int MAX_OTHERS = 500;

    /** The seven kit types in tie-break order: id, name on pages, type noun in reasons, the short "what goes here". */
    public record TypeWords(String id, String name, String noun, String hint) {
    }

    public static final List<TypeWords> TYPES = List.of(
        new TypeWords("pvp", "PvP / Crystal", "PvP", "armour, weapons, totems, crystals, obsidian"),
        new TypeWords("travel", "Travel", "travel", "elytra, rockets, pearls, boats"),
        new TypeWords("redstone", "Redstone", "redstone", "dust, pistons, observers, hoppers, rails"),
        new TypeWords("farming", "Farming / Food", "farming or food", "cooked food, crops, seeds, bone meal"),
        new TypeWords("building", "Building", "building", "concrete, wool, glass, planks, bricks"),
        new TypeWords("starter", "Starter / Base", "starter", "tools, a bed, a furnace, a crafting table"),
        new TypeWords("other", "Other kit", "collectible", "beacons, books, heads, music discs"));

    public static TypeWords words(String id) {
        for (TypeWords t : TYPES) if (t.id().equals(id)) return t;
        return null;
    }

    /** A kit type or a custom category: its chest, whether that chest is usable, and how many kits are in it. */
    public record TypeInfo(String id, String name, boolean custom, String chest, String chestStatus, long count) {
    }

    public record Missing(String key, long count) {
    }

    /** The classifier's verdict for a box (the report's {@code why}). */
    public record Why(String code, String type, long slots, long covered, long kinds, long need, long minKinds, List<String> items, List<String> words, String template,
                      List<Missing> missing) {
        public boolean kit() {
            return switch (code) {
                case "assortment", "named_assortment", "override", "template", "template_incomplete" -> true;
                default -> false;
            };
        }
    }

    public record Chain(String container, String source, String status) {
    }

    /**
     * One kit row (one per key, type and template).
     * @param key      the normalised name, "" for the unnamed row
     * @param type     the kit's category, "" when none (a rule with no kit stored)
     * @param typeSource auto, override, template, template_incomplete or ""
     * @param rule     the container of the kit's own rule, "" when it has none
     */
    public record Row(String key, String name, String type, String typeSource, Why why, String template, String templateName, long incomplete, long count, long carried,
                      List<KitsModel.Location> locations, String rule, List<Chain> chain, String target, String targetSource, boolean fallback, String status) {
        public boolean unnamed() {
            return key.isEmpty();
        }

        public String title(boolean hide, int index) {
            if (unnamed()) return "(unnamed)";
            if (hide) return "Kit " + (index + 1);
            if (!name.isEmpty()) return name;
            String shown = Text.sanitize(key, 64);
            return shown.isEmpty() ? "(no visible name)" : shown;
        }
    }

    /** A named box that is sorted normally, with the reason. */
    public record Other(String key, String name, long count, List<KitsModel.Location> locations, Why why, String treatAs, String status) {
        public String title(boolean hide, int index) {
            if (hide) return "Box " + (index + 1);
            if (!name.isEmpty()) return name;
            String shown = Text.sanitize(key, 64);
            return shown.isEmpty() ? "(no visible name)" : shown;
        }

        /** Single-item boxes show the reason only: nothing to treat as. */
        public boolean single() {
            return why != null && (why.code().equals("stock") || why.code().equals("stock_neutral"));
        }
    }

    public record TreatAs(String key, String type) {
    }

    /**
     * @param v2 the server sent the v2 fields (a v1 server has none: the tab shows what v1 has)
     */
    public record Data(boolean v2, List<TypeInfo> types, List<TreatAs> treatAs, List<Row> rows, List<Other> others, boolean botSupports, boolean botOnline, boolean botTypes,
                       boolean botTypesSynced, boolean hasReport, boolean v1Reporting, long maxTreatAs, int totalRows) {
        public TypeInfo type(String id) {
            for (TypeInfo t : types) if (t.id().equals(id)) return t;
            return null;
        }

        public String treatAsOf(String key) {
            for (TreatAs t : treatAs) if (t.key().equals(key)) return t.type();
            return "";
        }
    }

    private static String id(JsonObject o, String key) {
        String v = Json.str(o, key);
        return KitSettings.validId(v) ? v : "";
    }

    private static String idOrNull(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && KitSettings.validId(e.getAsString()) ? e.getAsString() : "";
    }

    private static List<String> strings(JsonElement e, int max, int len) {
        List<String> out = new ArrayList<>();
        JsonArray a = Json.arr(e);
        if (a == null) return out;
        for (JsonElement x : a) {
            if (out.size() >= max) break;
            if (x.isJsonPrimitive() && x.getAsJsonPrimitive().isString()) out.add(Text.sanitize(x.getAsString(), len));
        }
        return out;
    }

    private static boolean category(String id) {
        return KitSettings.validCategory(id);
    }

    public static Why why(JsonElement e) {
        JsonObject o = Json.obj(e);
        if (o == null) return null;
        String code = Json.str(o, "code");
        if (code.isEmpty() || code.length() > 24) return null;
        String type = Json.str(o, "type");
        List<Missing> missing = new ArrayList<>();
        JsonArray ma = Json.arr(o.get("missing"));
        if (ma != null) {
            for (JsonElement m : ma) {
                JsonObject mo = Json.obj(m);
                if (mo == null || missing.size() >= 8) continue;
                missing.add(new Missing(Text.sanitize(Json.str(mo, "key"), 160), Math.max(0, Json.lng(mo, "count", 0))));
            }
        }
        return new Why(code, category(type) ? type : "", Json.lng(o, "slots", 0), Json.lng(o, "covered", 0), Json.lng(o, "kinds", 0), Json.lng(o, "need", 0), Json.lng(o, "minKinds", 0),
            strings(o.get("items"), 3, 160), strings(o.get("words"), 4, 32), Text.sanitize(Json.str(o, "template"), 16), missing);
    }

    private static List<KitsModel.Location> locations(JsonElement e) {
        List<KitsModel.Location> out = new ArrayList<>();
        JsonArray ls = Json.arr(e);
        if (ls == null) return out;
        for (JsonElement le : ls) {
            if (out.size() >= KitsModel.MAX_LOCATIONS) break;
            JsonObject l = Json.obj(le);
            if (l != null) out.add(new KitsModel.Location(id(l, "container"), Math.max(0, Json.lng(l, "count", 0))));
        }
        return out;
    }

    public static Data parse(JsonElement body) {
        if (body == null || !body.isJsonObject()) return null;
        JsonObject o = body.getAsJsonObject();
        JsonObject bot = Json.obj(o.get("bot"));
        boolean v2 = o.has("types") && o.get("types").isJsonArray();
        List<TypeInfo> types = new ArrayList<>();
        JsonArray ta = Json.arr(o.get("types"));
        if (ta != null) {
            for (JsonElement e : ta) {
                JsonObject t = Json.obj(e);
                if (t == null || types.size() > 23) continue;
                String tid = Json.str(t, "id");
                if (!category(tid)) continue;
                types.add(new TypeInfo(tid, Text.sanitize(Json.str(t, "name"), 40), Json.bool(t, "custom"), id(t, "chest"), Json.str(t, "chestStatus"), Math.max(0, Json.lng(t, "count", 0))));
            }
        }
        List<TreatAs> treat = new ArrayList<>();
        JsonArray tr = Json.arr(o.get("treatAs"));
        if (tr != null) {
            for (JsonElement e : tr) {
                JsonObject t = Json.obj(e);
                if (t == null || treat.size() >= 200) continue;
                String key = Json.str(t, "key"), type = Json.str(t, "type");
                if (KitNames.validKey(key) && (type.equals("none") || category(type))) treat.add(new TreatAs(key, type));
            }
        }
        List<Row> rows = new ArrayList<>();
        int total = 0;
        JsonArray rs = Json.arr(o.get("rows"));
        if (rs != null) {
            for (JsonElement e : rs) {
                JsonObject r = Json.obj(e);
                if (r == null) continue;
                JsonElement k = r.get("key");
                String key = "";
                if (k != null && !k.isJsonNull()) {
                    if (!k.isJsonPrimitive() || !k.getAsJsonPrimitive().isString() || !KitNames.validKey(k.getAsString())) continue;
                    key = k.getAsString();
                }
                total++;
                if (rows.size() >= MAX_ROWS) continue;
                List<Chain> chain = new ArrayList<>();
                JsonArray ca = Json.arr(r.get("chain"));
                if (ca != null) {
                    for (JsonElement ce : ca) {
                        JsonObject c = Json.obj(ce);
                        if (c != null && chain.size() < 4) chain.add(new Chain(id(c, "container"), Json.str(c, "source"), Json.str(c, "status")));
                    }
                }
                String type = Json.str(r, "type");
                rows.add(new Row(key, key.isEmpty() ? "" : Text.sanitize(Json.str(r, "name"), 64), category(type) ? type : "", Json.str(r, "typeSource"), why(r.get("why")),
                    Text.sanitize(Json.str(r, "template"), 16), Text.sanitize(Json.str(r, "templateName"), 50), Math.max(0, Json.lng(r, "incomplete", 0)),
                    Json.lng(r, "count", 0), Json.lng(r, "carried", 0), locations(r.get("locations")), id(r, "rule"), chain, id(r, "target"), Json.str(r, "targetSource"),
                    Json.bool(r, "fallback"), Json.str(r, "status")));
            }
        }
        List<Other> others = new ArrayList<>();
        JsonArray oa = Json.arr(o.get("others"));
        if (oa != null) {
            for (JsonElement e : oa) {
                JsonObject r = Json.obj(e);
                if (r == null || others.size() >= MAX_OTHERS) continue;
                String key = Json.str(r, "key");
                if (!KitNames.validKey(key)) continue;
                others.add(new Other(key, Text.sanitize(Json.str(r, "name"), 64), Json.lng(r, "count", 0), locations(r.get("locations")), why(r.get("why")), Json.str(r, "treatAs"),
                    Json.str(r, "status")));
            }
        }
        JsonObject limits = Json.obj(o.get("limits"));
        // A v1 bot is reporting when the bot supports kits but not types: the scoring editor is then in "Advanced"
        boolean supports = bot != null && Json.bool(bot, "supports");
        boolean botTypes = bot != null && Json.bool(bot, "types");
        boolean report = o.has("report") && o.get("report").isJsonObject();
        return new Data(v2, types, treat, rows, others, supports, bot != null && Json.bool(bot, "online"), botTypes, bot != null && Json.bool(bot, "typesSynced"), report,
            supports && !botTypes, limits == null ? 200 : Json.lng(limits, "maxTreatAs", 200), total);
    }

    // ---------- words ----------

    /** The page's usual name for an item id, else the path with "_" as spaces. */
    public static String itemName(String id, Function<String, String> usual) {
        String name = usual == null ? null : usual.apply(id);
        if (name != null && !name.isBlank()) return Text.sanitize(name, 60);
        String path = id.indexOf(':') >= 0 ? id.substring(id.indexOf(':') + 1) : id;
        return Text.sanitize(path.replace('_', ' '), 60);
    }

    private static String typeName(Data d, String id) {
        TypeInfo t = d == null ? null : d.type(id);
        if (t != null && !t.name().isEmpty()) return t.name();
        TypeWords w = words(id);
        return w != null ? w.name() : "Custom";
    }

    private static String noun(Data d, String id) {
        TypeWords w = words(id);
        return w != null ? w.noun() : typeName(d, id);
    }

    private static String items(Why w, Function<String, String> usual) {
        List<String> names = new ArrayList<>();
        for (String i : w.items()) names.add(itemName(i, usual));
        return String.join(", ", names);
    }

    private static String stockItem(Why w, Function<String, String> usual) {
        return w.items().isEmpty() ? "item" : itemName(w.items().get(0), usual);
    }

    /**
     * The reason text, as the contract words it (the website and this tab use exactly this).
     * @param templateName the name of the matched kit template, or "" (streamer display: it is left out of the text)
     */
    public static String reason(Data d, Why w, String templateName, boolean hide, Function<String, String> usual) {
        if (w == null) return "";
        String Type = typeName(d, w.type());
        String type = noun(d, w.type());
        return switch (w.code()) {
            case "assortment" -> Type + " kit: " + w.covered() + " of " + w.slots() + " slots are " + type + " items, " + w.kinds() + " kinds (mostly " + items(w, usual) + ").";
            case "named_assortment" -> Type + " kit: " + w.covered() + " of " + w.slots() + " slots are " + type + " items, " + w.kinds() + " kinds (mostly " + items(w, usual) + ")."
                + (w.words().isEmpty() || hide ? "" : " The name says “" + w.words().get(0) + "”, so half is enough.");
            case "override" -> Type + " kit: set by hand for this name.";
            case "template" -> hide || templateName.isEmpty() ? Type + " kit: matches a saved kit." : Type + " kit: matches the kit “" + templateName + "”.";
            case "template_incomplete" -> {
                List<String> short_ = new ArrayList<>();
                for (Missing m : w.missing()) short_.add(itemName(m.key().replaceAll("#.*$", ""), usual) + " ×" + m.count());
                String which = hide || templateName.isEmpty() ? "a saved kit" : "“" + templateName + "”";
                yield Type + " kit: an incomplete " + which + (short_.isEmpty() ? "" : " (short " + String.join(", ", short_) + ")")
                    + ". It is kept and topped up, but not delivered until complete.";
            }
            case "empty" -> "Not a kit: the box is empty.";
            case "stock", "stock_neutral" -> "Not a kit: it only holds " + stockItem(w, usual) + ". A box of one kind of item is stock and goes to the " + stockItem(w, usual) + " chest.";
            case "no_purpose" -> "Not a kit: none of its items has a kit purpose.";
            case "few_kinds" -> "Not a kit: " + w.covered() + " of " + w.slots() + " slots are " + type + " items, but only " + w.kinds() + " kind(s). A " + Type + " kit needs " + w.minKinds() + ".";
            case "mixed" -> "Not a kit: no common purpose. The closest is " + Type + ", with " + w.covered() + " of " + w.slots() + " slots; a kit needs " + w.need() + ".";
            case "override_none" -> "Not a kit: set by hand for this name.";
            default -> "";
        };
    }

    // ---------- where it is and where it goes ----------

    private static String levelName(Data d, Row r, String source) {
        return switch (source) {
            case "rule" -> "Own chest";
            case "template" -> "Kit chest";
            case "typeChest", "category" -> typeName(d, r.type()) + " chest";
            case "kitsChest" -> "Kits chest";
            default -> "Chest";
        };
    }

    /** "Goes to": the target and its level; with {@code fallback} the first entry's problem is named ("Own chest full, using the PvP chest"). */
    public static String goesTo(Data d, KitsModel.Data v1, Row r, boolean hide) {
        if (r.chain().isEmpty()) return "Sorted like any other shulker";
        if (r.target().isEmpty()) {
            Chain first = r.chain().get(0);
            return levelName(d, r, first.source()) + " " + (first.status().equals("missing") ? "missing" : "full") + ", nowhere to go yet";
        }
        String place = KitsModel.place(v1, r.target(), hide);
        String level = switch (r.targetSource()) {
            case "rule" -> "its own chest";
            case "template" -> "the kit's chest";
            case "typeChest", "category" -> "the " + typeName(d, r.type()) + " chest";
            default -> "the kits chest";
        };
        if (r.fallback()) {
            Chain first = r.chain().get(0);
            return levelName(d, r, first.source()) + " " + (first.status().equals("missing") ? "missing" : "full") + ", using " + level + " (" + place + ")";
        }
        return place + " (" + level + ")";
    }

    /** "Where it is now". */
    public static String where(KitsModel.Data v1, Row r, boolean hide) {
        StringBuilder sb = new StringBuilder();
        for (KitsModel.Location l : r.locations()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(KitsModel.place(v1, l.container(), hide)).append(" ×").append(l.count());
        }
        if (r.carried() > 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("carried ×").append(r.carried());
        }
        return sb.length() == 0 ? "–" : sb.toString();
    }

    public static String where(KitsModel.Data v1, Other o, boolean hide) {
        StringBuilder sb = new StringBuilder();
        for (KitsModel.Location l : o.locations()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(KitsModel.place(v1, l.container(), hide)).append(" ×").append(l.count());
        }
        return sb.length() == 0 ? "–" : sb.toString();
    }

    /** The status pill of a row (text and kind), as the website words the chain statuses; an incomplete copy says so. */
    public static KitsModel.Status status(KitsModel.Data v1, Row r) {
        if (!v1.hasReport()) return new KitsModel.Status("Waiting for the bot's report", "");
        KitsModel.Status s = KitsModel.status(r.status());
        if (r.incomplete() > 0 && s.kind().isEmpty()) return new KitsModel.Status(r.incomplete() + " incomplete", "warn");
        return s;
    }

    // ---------- the header and the notes ----------

    public static final String HEADER = "Kits are sorted by type. Pick a chest for each type you use. A kit whose chest is unset, full or missing goes to the general kits chest; "
        + "with neither, it is sorted like any other shulker. A box of one kind of item is never a kit.";
    public static final String OLD_BOT = "This bot is too old for kit types: until it's updated, kits go only to the general kits chest or their own chest, and the type chests may get other items.";
    public static final String WAITING = "Waiting for the bot's report.";
    public static final String GENERAL_HINT = "Kits whose type chest is unset, full or missing";
    public static final String NOT_KITS_HINT = "Named shulker boxes that are sorted like any other shulker, and why. A box with several kinds of item can be treated as a kit of a type.";

    /** The header notes: the old-bot warning and the missing report. */
    public static List<KitsModel.Note> notes(Data d) {
        List<KitsModel.Note> out = new ArrayList<>();
        if (d.botSupports() && !d.botTypes()) out.add(new KitsModel.Note("warn", OLD_BOT));
        if (!d.hasReport()) out.add(new KitsModel.Note("", WAITING));
        return out;
    }

    /** The options of the Type picker of a named kit: Auto, each type and custom category, Not a kit. Values: "" (auto), the category id, "none". */
    public static KitsModel.Options typeOptions(Data d, String current) {
        List<String> texts = new ArrayList<>();
        List<String> values = new ArrayList<>();
        texts.add("Auto");
        values.add("");
        for (TypeInfo t : d.types()) {
            texts.add(t.name().isEmpty() ? typeName(d, t.id()) : t.name());
            values.add(t.id());
        }
        texts.add("Not a kit");
        values.add("none");
        int sel = Math.max(0, values.indexOf(current));
        return new KitsModel.Options(texts.toArray(new String[0]), values.toArray(new String[0]), sel);
    }

    /** The status words of a type chest line. */
    public static String chestStatusText(String status) {
        return switch (status) {
            case "ok" -> "ok";
            case "full" -> "full";
            case "missing" -> "missing";
            default -> "not set";
        };
    }
}
