package com.porter.qwerte.printer.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwerte.manager.model.Json;
import com.porter.qwerte.manager.model.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The server's quote for crafting what the stash can make ({@code POST /api/v1/craft/plan}): the request body and the answer.
 * The Printer plans nothing itself; it asks what the stash can make of the items no stash holds, then orders exactly that as
 * craft lines. Pure.
 */
public final class CraftQuote {
    private CraftQuote() {}

    /** The request lists at most this many ids (the server takes 200). */
    public static final int MAX_IDS = 200;

    /** No count read from the server is taken above this: products of two counts stay inside a long. */
    public static final long MAX_COUNT = 1_000_000_000L;

    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,128}$");

    /** A plain item id as the server writes it. */
    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public record Count(String id, long count) {}

    /** One wanted item: what was asked, what can be made, why not all, and the stash items it takes. */
    public record Line(String id, long want, long craftable, String reason, List<Count> uses) {}

    public record Quote(boolean canOrder, String reason, String message, List<Line> lines, List<Count> take, List<Count> leftovers,
                        long fills, boolean truncated) {}

    /** The body of the quote request; {@code direct} is what the same order takes as itself, so both see the same stash. */
    public static JsonObject body(String server, String stash, Map<String, Long> want, Map<String, ? extends Number> direct) {
        JsonObject b = new JsonObject();
        b.addProperty("server", server);
        b.addProperty("stash", stash);
        b.add("want", array(want));
        if (direct != null && !direct.isEmpty()) b.add("direct", array(direct));
        return b;
    }

    private static JsonArray array(Map<String, ? extends Number> counts) {
        JsonArray a = new JsonArray();
        for (Map.Entry<String, ? extends Number> e : counts.entrySet()) {
            if (a.size() >= MAX_IDS) break;
            long n = Math.min(Integer.MAX_VALUE, e.getValue().longValue());
            if (n <= 0 || e.getKey() == null || e.getKey().isEmpty()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", e.getKey());
            o.addProperty("count", n);
            a.add(o);
        }
        return a;
    }

    private static List<Count> counts(JsonElement e) {
        List<Count> out = new ArrayList<>();
        JsonArray a = Json.arr(e);
        if (a == null) return out;
        for (JsonElement el : a) {
            JsonObject o = Json.obj(el);
            if (o == null || out.size() >= 256) continue;
            String id = Json.str(o, "id");
            long n = Math.min(MAX_COUNT, Json.lng(o, "count", 0));
            if (validId(id) && n > 0) out.add(new Count(id, n));
        }
        return out;
    }

    /** The quote in an answer body; null when it has no {@code craft} object and no lines (not a quote). */
    public static Quote parse(JsonObject o) {
        if (o == null) return null;
        JsonObject craft = Json.obj(o.get("craft"));
        JsonArray la = Json.arr(o.get("lines"));
        if (craft == null && la == null) return null;
        List<Line> lines = new ArrayList<>();
        if (la != null) {
            for (JsonElement el : la) {
                JsonObject l = Json.obj(el);
                if (l == null || lines.size() >= MAX_IDS) continue;
                String id = Json.str(l, "id");
                if (!validId(id)) continue;
                lines.add(new Line(id, clamp(Json.lng(l, "want", 0)), clamp(Json.lng(l, "craftable", 0)),
                    Text.sanitize(Json.str(l, "reason"), 120), counts(l.get("uses"))));
            }
        }
        return new Quote(Json.bool(craft, "canOrder"), Text.sanitize(Json.str(craft, "reason"), 40), Text.sanitize(Json.str(craft, "message"), 200),
            List.copyOf(lines), counts(o.get("take")), counts(o.get("leftovers")), clamp(Json.lng(o, "fills", 0)), Json.bool(o, "truncated"));
    }

    private static long clamp(long n) {
        return Math.max(0, Math.min(MAX_COUNT, n));
    }

    /** Plain words for a craft refusal code (the 409 {@code craft} code of an order, or the quote's {@code reason}). */
    public static String codeText(String code) {
        return switch (code == null ? "" : code) {
            case "no_recipes" -> "the server has no recipes for this Minecraft version yet";
            case "no_bot" -> "the stash bot cannot craft orders (it needs StashSorter 1.15.0 or newer)";
            case "recipes_differ" -> "the bot's recipe table differs from the server's";
            case "no_table" -> "no crafting table is set for this stash on the website";
            case "table_bad" -> "the crafting table is not usable right now";
            case "" -> "crafting is not available for this stash";
            // Anything else is the server's own text: never shown in chat
            default -> "crafting is not available for this stash";
        };
    }

    /** What the answer of a refused order says in {@code craft} (a code), or "". */
    public static String refusalCode(JsonObject body) {
        JsonElement c = body == null ? null : body.get("craft");
        return c != null && c.isJsonPrimitive() && c.getAsJsonPrimitive().isString() ? Text.sanitize(c.getAsString(), 40) : "";
    }
}
