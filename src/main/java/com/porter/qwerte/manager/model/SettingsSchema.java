package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A bot's settings tree as {@code GET /api/v1/bots/{id}/settings} sends it (PROTOCOL.md, "Dashboard"), flattened into the
 * settings that can be shown, and the checks the window runs on an edit before it sends it (the website's field rules, and
 * the server's, which refuse anything else). Pure.
 */
public final class SettingsSchema {
    private SettingsSchema() {
    }

    public static final int MAX_LEAVES = 6000;
    private static final int MAX_DEPTH = 14;

    /** One setting. {@code min}/{@code max} are NaN when the schema gave none. */
    public record Node(String path, String type, String label, String help, String reason, String section, double min, double max,
                       List<String> options, boolean nullable, boolean readOnly, boolean secret, boolean set) {
        public String title() {
            return label.isEmpty() ? path : label;
        }

        public boolean numeric() {
            return type.equals("int") || type.equals("long") || type.equals("double");
        }

        /** A setting the window may not edit. */
        public boolean locked() {
            return readOnly || secret;
        }
    }

    /** A setting with where it sits: the section it is listed under and the group titles above it. */
    public record Leaf(Node node, String section, List<String> trail, String hay) {
    }

    /** A section name and how many settings it holds. */
    public record Section(String name, int count) {
    }

    /** What a text field's content means. */
    public record Parsed(boolean ok, JsonElement value, String error) {
        static Parsed good(JsonElement v) {
            return new Parsed(true, v, "");
        }

        static Parsed bad(String why) {
            return new Parsed(false, JsonNull.INSTANCE, why);
        }
    }

    // ---------- the tree ----------

    /** The settings in the order of the tree, groups flattened (at most {@link #MAX_LEAVES}). */
    public static List<Leaf> leaves(JsonElement schema) {
        List<Leaf> out = new ArrayList<>();
        JsonArray a = Json.arr(schema);
        if (a != null) collect(a, List.of(), null, out, 0);
        return out;
    }

    private static void collect(JsonArray nodes, List<String> trail, String top, List<Leaf> out, int depth) {
        if (depth > MAX_DEPTH) return;
        for (JsonElement e : nodes) {
            if (out.size() >= MAX_LEAVES) return;
            JsonObject o = Json.obj(e);
            if (o == null || Json.str(o, "path").isEmpty()) continue;
            String type = Json.str(o, "type");
            if (type.equals("group")) {
                String label = Text.sanitize(Json.str(o, "label"), 80);
                if (label.isEmpty()) label = Text.sanitize(Json.str(o, "path"), 80);
                List<String> next = trail;
                if (top != null) {
                    next = new ArrayList<>(trail);
                    next.add(label);
                }
                JsonArray children = Json.arr(o.get("children"));
                if (children != null) collect(children, next, top == null ? label : top, out, depth + 1);
                continue;
            }
            Node n = node(o, type);
            String section = n.section().isEmpty() ? (top != null ? top : "Other") : n.section();
            out.add(new Leaf(n, section, trail, (n.path() + "\n" + n.label() + "\n" + n.help() + "\n" + section).toLowerCase(Locale.ROOT)));
        }
    }

    private static Node node(JsonObject o, String type) {
        List<String> options = new ArrayList<>();
        JsonArray oa = Json.arr(o.get("options"));
        if (oa != null) {
            for (JsonElement x : oa) {
                if (x.isJsonPrimitive() && options.size() < 500) options.add(Text.sanitize(x.getAsString(), 100));
            }
        }
        return new Node(Text.sanitize(Json.str(o, "path"), 200), type, Text.sanitize(Json.str(o, "label"), 100), Text.sanitize(Json.str(o, "help"), 300),
            Text.sanitize(Json.str(o, "reason"), 100), Text.sanitize(Json.str(o, "section"), 80), number(o, "min"), number(o, "max"), options,
            Json.bool(o, "nullable"), Json.bool(o, "readOnly"), Json.bool(o, "secret"), Json.bool(o, "set"));
    }

    private static double number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return Double.NaN;
        double d = e.getAsDouble();
        return Double.isFinite(d) ? d : Double.NaN;
    }

    /** The sections in order of first appearance. */
    public static List<Section> sections(List<Leaf> leaves) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Leaf l : leaves) counts.merge(l.section(), 1, Integer::sum);
        List<Section> out = new ArrayList<>();
        counts.forEach((k, v) -> out.add(new Section(k, v)));
        return out;
    }

    /** The settings of one section. */
    public static List<Leaf> inSection(List<Leaf> leaves, String section) {
        List<Leaf> out = new ArrayList<>();
        for (Leaf l : leaves) if (l.section().equals(section)) out.add(l);
        return out;
    }

    /** The settings whose path, label, help or section contain {@code query} (case-insensitive), at most {@code limit}. */
    public static List<Leaf> search(List<Leaf> leaves, String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Leaf> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        for (Leaf l : leaves) {
            if (l.hay().contains(q)) {
                out.add(l);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    // ---------- edits ----------

    /**
     * What a number or text field holds, as the website reads it. {@code unset} is the "unset" box of a nullable setting. A
     * list, a switch or a choice have their own controls and never come here.
     */
    public static Parsed parse(Node n, String raw, boolean unset) {
        if (unset && n.nullable()) return Parsed.good(JsonNull.INSTANCE);
        String text = raw == null ? "" : raw;
        if (!n.numeric()) {
            if (text.length() > 1000) return Parsed.bad("at most 1000 characters");
            return Parsed.good(new JsonPrimitive(text));
        }
        String t = text.trim();
        if (t.isEmpty()) return n.nullable() ? Parsed.good(JsonNull.INSTANCE) : Parsed.bad("enter a number");
        if (n.type().equals("double")) {
            double d;
            try {
                d = Double.parseDouble(t);
            } catch (NumberFormatException e) {
                return Parsed.bad("not a number");
            }
            if (!Double.isFinite(d)) return Parsed.bad("not a number");
            String range = range(n, d);
            return range.isEmpty() ? Parsed.good(new JsonPrimitive(d)) : Parsed.bad(range);
        }
        if (!t.matches("-?\\d{1,19}")) return Parsed.bad("a whole number");
        long v;
        try {
            v = Long.parseLong(t);
        } catch (NumberFormatException e) {
            return Parsed.bad("too large");
        }
        if (n.type().equals("int") && (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE)) return Parsed.bad("too large for a whole number setting");
        String range = range(n, v);
        return range.isEmpty() ? Parsed.good(new JsonPrimitive(v)) : Parsed.bad(range);
    }

    private static String range(Node n, double v) {
        if (!Double.isNaN(n.min()) && v < n.min()) return "at least " + fmt(n.min());
        if (!Double.isNaN(n.max()) && v > n.max()) return "at most " + fmt(n.max());
        return "";
    }

    private static String fmt(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }

    /**
     * The server's check of a value for this setting (PROTOCOL.md): the error text, or "" when it is acceptable. Used on the
     * whole batch before it is sent so a refusal is shown beside the field instead of after a round trip.
     */
    public static String validate(Node n, JsonElement v) {
        if (n.locked() || n.type().equals("group")) return "can't be changed here";
        if (v == null || v.isJsonNull()) return n.nullable() ? "" : "can't be unset";
        switch (n.type()) {
            case "bool":
                return v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() ? "" : "on or off";
            case "int":
            case "long": {
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) return "a whole number";
                double d = v.getAsDouble();
                if (d != Math.rint(d) || Double.isInfinite(d)) return "a whole number";
                if (n.type().equals("int") && (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE)) return "too large for a whole number setting";
                return range(n, d);
            }
            case "double": {
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) return "not a number";
                double d = v.getAsDouble();
                if (!Double.isFinite(d)) return "not a number";
                return range(n, d);
            }
            case "string":
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) return "text";
                return v.getAsString().length() > 1000 ? "at most 1000 characters" : "";
            case "enum":
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) return "one of the options";
                return n.options().contains(v.getAsString()) ? "" : "one of the options";
            case "stringList": {
                if (!v.isJsonArray()) return "a list";
                JsonArray a = v.getAsJsonArray();
                if (a.size() > 500) return "at most 500 entries";
                for (JsonElement x : a) {
                    if (!x.isJsonPrimitive() || !x.getAsJsonPrimitive().isString()) return "a list of text";
                    if (x.getAsString().length() > 1000) return "an entry is longer than 1000 characters";
                }
                return "";
            }
            default:
                return "unknown kind of setting";
        }
    }

    /** How a value reads in a short note ("ON", "(none)", "a, b"). */
    public static String show(JsonElement v) {
        if (v == null || v.isJsonNull()) return "(none)";
        if (v.isJsonArray()) {
            JsonArray a = v.getAsJsonArray();
            if (a.isEmpty()) return "(empty)";
            StringBuilder sb = new StringBuilder();
            for (JsonElement x : a) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(x.isJsonPrimitive() ? x.getAsString() : "?");
                if (sb.length() > 120) {
                    sb.append("...");
                    break;
                }
            }
            return Text.sanitize(sb.toString(), 140);
        }
        if (v.isJsonPrimitive()) {
            JsonPrimitive p = v.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean() ? "ON" : "OFF";
            return Text.sanitize(p.getAsString(), 140);
        }
        return "?";
    }
}
