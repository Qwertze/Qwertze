package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Crafting from the stash, as the server's planner and orders describe it (CRAFT-CONTRACT.md, "Planner additions" and
 * "Orders"): the plan-level {@code craft} object, the per-row craft fields, and an order's craft lines and result. The
 * window only shows what the server computed; nothing is planned here. Every reader is forgiving (a wrong type gives the
 * default) and an absent {@code craft} means an old server: the callers then look exactly as they did before. Pure.
 */
public final class CraftModel {
    private CraftModel() {
    }

    private static final int MAX_LIST = 64;
    private static final long MAX_COUNT = 1_000_000_000L;
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,128}$");

    // ---------- records ----------

    /** {@code craft} of the plan JSON. {@code state} is the recipes state: ready, building, failed or none. */
    public record Info(boolean available, boolean canOrder, String reason, String message, String mc, String state, String tableStatus,
                       boolean botSupports, boolean recipesMatch, boolean truncated) {
        public boolean building() {
            return "building".equals(state);
        }

        /** The Craft column is shown: recipes are ready, or being loaded. */
        public boolean showsColumn() {
            return available || building();
        }
    }

    public record Step(String recipe, String result, long made, long fills, List<PlanModel.Count> inputs) {
    }

    /** The craft fields of one row. */
    public record RowCraft(long craftable, long missingAfterCraft, String status, String reason, List<PlanModel.Count> uses,
                           List<Step> steps, List<PlanModel.Count> leftovers) {
    }

    /** The craft totals of the plan. */
    public record Totals(long craftable, long craftKinds, long missingAfterCraft, long craftFills, long wantWithCraft) {
    }

    /** One craft line of an order: key is an item id. */
    public record Line(String key, long count) {
    }

    public record Made(String key, long wanted, long made) {
    }

    public record Shortfall(String key, long count, String reason) {
    }

    /** {@code result.craft} of a finished (or running) order. */
    public record Result(long fills, List<Made> made, List<Shortfall> shorts, List<PlanModel.Count> used, List<PlanModel.Count> returned) {
        public boolean any() {
            return !made.isEmpty() || !shorts.isEmpty() || !used.isEmpty() || !returned.isEmpty();
        }
    }

    /** What an order says about crafting; null when it has no craft lines. */
    public record OrderCraft(List<Line> lines, long fills, List<PlanModel.Count> take, List<PlanModel.Count> leftovers, Result result) {
        public OrderCraft withResult(Result r) {
            return new OrderCraft(lines, fills, take, leftovers, r);
        }
    }

    // ---------- parsing ----------

    private static List<PlanModel.Count> counts(JsonElement e, int max) {
        List<PlanModel.Count> out = new ArrayList<>();
        JsonArray a = Json.arr(e);
        if (a == null) return out;
        for (JsonElement el : a) {
            if (out.size() >= max) break;
            JsonObject o = Json.obj(el);
            if (o == null) continue;
            String id = Json.str(o, "id");
            if (ID.matcher(id).matches()) out.add(new PlanModel.Count(id, Math.max(0, Math.min(MAX_COUNT, Json.lng(o, "count", 0)))));
        }
        return out;
    }

    /** Null when the plan has no {@code craft} object (an old server). */
    public static Info parseInfo(JsonElement e) {
        JsonObject o = Json.obj(e);
        if (o == null) return null;
        JsonObject rec = Json.obj(o.get("recipes"));
        JsonObject bot = Json.obj(o.get("bot"));
        return new Info(Json.bool(o, "available"), Json.bool(o, "canOrder"), Text.sanitize(Json.str(o, "reason"), 40),
            Text.sanitize(Json.str(o, "message"), 200), Text.sanitize(Json.str(rec, "mc"), 24), Text.sanitize(Json.str(rec, "state"), 20),
            Text.sanitize(Json.str(o, "tableStatus"), 24), bot != null && Json.bool(bot, "supports"), bot == null || !bot.has("recipesMatch") || Json.bool(bot, "recipesMatch"),
            Json.bool(o, "truncated"));
    }

    /** Null when the row has no craft fields (old server, or recipes not available). */
    public static RowCraft parseRow(JsonObject r) {
        if (r == null || !r.has("craftable")) return null;
        List<Step> steps = new ArrayList<>();
        JsonArray sa = Json.arr(r.get("craftSteps"));
        if (sa != null) {
            for (JsonElement el : sa) {
                JsonObject s = Json.obj(el);
                if (s == null || steps.size() >= MAX_LIST) continue;
                String result = Json.str(s, "result");
                if (result.isEmpty()) continue;
                steps.add(new Step(Json.str(s, "recipe"), result, Math.max(0, Json.lng(s, "made", 0)), Math.max(0, Json.lng(s, "fills", 0)),
                    counts(s.get("inputs"), MAX_LIST)));
            }
        }
        long craftable = Math.max(0, Json.lng(r, "craftable", 0));
        long mac = r.has("missingAfterCraft") ? Math.max(0, Json.lng(r, "missingAfterCraft", 0))
            : Math.max(0, Json.lng(r, "missing", 0) - craftable);
        return new RowCraft(craftable, mac, Text.sanitize(Json.str(r, "craftStatus"), 20), Text.sanitize(Json.str(r, "craftReason"), 200),
            counts(r.get("craftUses"), MAX_LIST), steps, counts(r.get("craftLeftovers"), MAX_LIST));
    }

    /** Null when the totals have no craft fields. */
    public static Totals parseTotals(JsonObject t) {
        if (t == null || !t.has("craftable")) return null;
        long craftable = Math.max(0, Json.lng(t, "craftable", 0));
        return new Totals(craftable, Math.max(0, Json.lng(t, "craftKinds", 0)), Math.max(0, Json.lng(t, "missingAfterCraft", Json.lng(t, "missing", 0))),
            Math.max(0, Json.lng(t, "craftFills", 0)), Math.max(0, Json.lng(t, "wantWithCraft", Json.lng(t, "want", 0) + craftable)));
    }

    /** The craft lines of an order's {@code lines} array, merged by key is the server's job; here they are only read. */
    public static List<Line> parseLines(JsonArray lines) {
        List<Line> out = new ArrayList<>();
        if (lines == null) return out;
        for (JsonElement el : lines) {
            JsonObject l = Json.obj(el);
            if (l == null || out.size() >= 200 || !"craft".equals(Json.str(l, "type"))) continue;
            String key = Json.str(l, "key");
            double count = Json.num(l, "count", 0);
            if (!key.isEmpty() && key.length() <= 256 && count >= 1) out.add(new Line(key, (long) Math.min(Integer.MAX_VALUE, count)));
        }
        return out;
    }

    /** The order's craft view, or null when it has no craft lines and no craft object. */
    public static OrderCraft parseOrder(JsonObject order, List<Line> lines) {
        JsonObject c = Json.obj(order == null ? null : order.get("craft"));
        JsonObject result = Json.obj(order == null ? null : order.get("result"));
        JsonObject res = result == null ? null : Json.obj(result.get("craft"));
        if (lines.isEmpty() && c == null && res == null) return null;
        return new OrderCraft(List.copyOf(lines), c == null ? 0 : Math.max(0, Json.lng(c, "fills", 0)), c == null ? List.of() : counts(c.get("take"), MAX_LIST),
            c == null ? List.of() : counts(c.get("leftovers"), MAX_LIST), parseResult(res));
    }

    /** Null when the object is absent. */
    public static Result parseResult(JsonObject r) {
        if (r == null) return null;
        List<Made> made = new ArrayList<>();
        JsonArray ma = Json.arr(r.get("made"));
        if (ma != null) {
            for (JsonElement el : ma) {
                JsonObject m = Json.obj(el);
                if (m == null || made.size() >= 200 || Json.str(m, "key").isEmpty()) continue;
                made.add(new Made(Text.sanitize(Json.str(m, "key"), 256), Math.max(0, Json.lng(m, "wanted", 0)), Math.max(0, Json.lng(m, "made", 0))));
            }
        }
        List<Shortfall> shorts = new ArrayList<>();
        JsonArray sa = Json.arr(r.get("short"));
        if (sa != null) {
            for (JsonElement el : sa) {
                JsonObject s = Json.obj(el);
                if (s == null || shorts.size() >= 200 || Json.str(s, "key").isEmpty()) continue;
                shorts.add(new Shortfall(Text.sanitize(Json.str(s, "key"), 256), Math.max(0, Json.lng(s, "count", 0)), Text.sanitize(Json.str(s, "reason"), 60)));
            }
        }
        return new Result(Math.max(0, Json.lng(r, "fills", 0)), List.copyOf(made), List.copyOf(shorts), counts(r.get("used"), 200), counts(r.get("returned"), 200));
    }

    // ---------- words ----------

    /** Plain words for a failure reason code of {@code result.craft.short}. */
    public static String shortReason(String code) {
        return switch (code == null ? "" : code) {
            case "no_table" -> "no crafting table is set";
            case "table_missing" -> "the crafting table is gone";
            case "table_blocked" -> "the bot could not reach the crafting table";
            case "ingredients_short" -> "the ingredients ran short";
            case "inventory_full" -> "the bot's inventory was full";
            case "recipe_mismatch" -> "the bot's recipes differ from the server's";
            case "grid_stuck" -> "the crafting grid got stuck";
            case "timeout" -> "it took too long";
            case "interrupted" -> "it was interrupted";
            case "cap" -> "it was over the bot's crafting limit";
            case "cancelled" -> "the order was cancelled";
            case "(hidden)" -> "(hidden)";
            case "" -> "unknown reason";
            default -> Text.sanitize(code.replace('_', ' '), 60);
        };
    }

    /** "6 Spruce Log, 2 Stick" (at most {@code max} entries, then "and N more"). */
    public static String countList(List<PlanModel.Count> list, int max) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (PlanModel.Count c : list) {
            if (n++ >= max) break;
            sb.append(n > 1 ? ", " : "").append(Text.grouped(c.count())).append(' ').append(PlanModel.pretty(c.id()));
        }
        if (list.size() > max) sb.append(" and ").append(list.size() - max).append(" more");
        return sb.toString();
    }

    /** "Craft: 8 Spruce Stairs" */
    public static String lineText(Line l) {
        return "Craft: " + Text.grouped(l.count()) + " " + PlanModel.pretty(l.key());
    }

    // ---------- the planner view ----------

    /** A banner under the options. {@code warn} is false for the info about recipes loading. */
    public record Banner(String text, boolean warn) {
    }

    /** The banners for the plan's craft state (empty: none). Mirrors the website. */
    public static List<Banner> banners(Info c) {
        List<Banner> out = new ArrayList<>();
        if (c == null) return out;
        String mc = c.mc().isEmpty() ? "this Minecraft version" : c.mc();
        if (!c.available()) {
            if (c.building()) out.add(new Banner("Loading recipes for " + mc + "...", false));
            else out.add(new Banner("Crafting info isn't available for " + mc + ".", false));
            return out;
        }
        if (!c.canOrder()) out.add(new Banner(cannotOrder(c), true));
        if (c.truncated()) out.add(new Banner("The crafting plan was cut short; some items show as missing.", true));
        return out;
    }

    /** Why craft lines can't be ordered, in plain words. */
    public static String cannotOrder(Info c) {
        return switch (c.reason()) {
            case "no_bot" -> "This stash's bot can't craft orders (needs StashSorter 1.15.0+).";
            case "recipes_differ" -> "The bot's recipe table differs from the server's.";
            case "no_table" -> "Set a crafting table for this stash on the website to order crafted items.";
            case "table_bad" -> "The crafting table isn't usable: " + (c.tableStatus().isEmpty() ? "unknown" : c.tableStatus().replace('_', ' ')) + ".";
            case "no_recipes" -> "Crafting info isn't available for " + (c.mc().isEmpty() ? "this Minecraft version" : c.mc()) + ".";
            default -> c.message().isEmpty() ? "Crafted items can't be ordered from this stash right now." : Text.sentence(c.message());
        };
    }

    /** The craft box is ticked and usable: the server can take craft lines. */
    public static boolean orderable(Info c) {
        return c != null && c.available() && c.canOrder();
    }

    /** The Craft column: the number made, "-" when there is nothing to make or nothing can be, "..." while recipes load. */
    public static String craftCell(PlanModel.Row r, Info c) {
        RowCraft k = r.craft();
        if (k == null) return c != null && c.building() ? "..." : "";
        return k.craftable() > 0 ? Text.grouped(k.craftable()) : "short".equals(k.status()) ? "0" : "-";
    }

    /**
     * The Missing cell. Without craft fields it is the plain missing count; with them: "craftable" when the stash can make all
     * of it, otherwise what stays missing after crafting with "(+N craftable)".
     */
    public static String missingCell(PlanModel.Row r) {
        RowCraft k = r.craft();
        if (k == null) return PlanModel.stacks(r.missing(), r.maxStack());
        if (k.craftable() > 0 && k.missingAfterCraft() == 0) return "craftable";
        String base = PlanModel.stacks(k.missingAfterCraft(), r.maxStack());
        return k.craftable() > 0 ? base + " (+" + Text.grouped(k.craftable()) + " craftable)" : base;
    }

    /** The row is shown in red: something stays missing even after crafting. */
    public static boolean stillMissing(PlanModel.Row r) {
        return r.craft() == null ? r.missing() > 0 : r.craft().missingAfterCraft() > 0;
    }

    /** The hover text of the Craft cell; empty when there is nothing to say. */
    public static String tooltip(PlanModel.Row r) {
        RowCraft k = r.craft();
        if (k == null) return "";
        List<String> parts = new ArrayList<>();
        if (k.craftable() > 0) {
            if (!k.uses().isEmpty()) parts.add("Uses from the stash: " + countList(k.uses(), 8));
            if (!k.steps().isEmpty()) {
                List<String> steps = new ArrayList<>();
                int n = 0;
                for (Step s : k.steps()) {
                    if (n++ >= 6) break;
                    steps.add(Text.grouped(s.made()) + " " + PlanModel.pretty(s.result()) + (s.inputs().isEmpty() ? "" : " from " + countList(s.inputs(), 4)));
                }
                parts.add(String.join("; ", steps) + (k.steps().size() > 6 ? "; and " + (k.steps().size() - 6) + " more" : ""));
            }
            if (!k.leftovers().isEmpty()) parts.add("Back to the stash: " + countList(k.leftovers(), 8));
        }
        if (!k.reason().isEmpty() && (k.craftable() <= 0 || k.missingAfterCraft() > 0)) parts.add(Text.sentence(k.reason()));
        return String.join(" | ", parts);
    }

    /** The craft tile's second line. */
    public static String tileSub(Totals t, Info c) {
        if (c == null) return "";
        if (!c.available()) return c.building() ? "loading recipes" : "no recipe info";
        String made = t == null ? "0 kinds" : t.craftKinds() + (t.craftKinds() == 1 ? " kind" : " kinds");
        if (!c.canOrder()) return made + ", can't be ordered: " + shortCannot(c);
        return made + ", from the stash";
    }

    private static String shortCannot(Info c) {
        return switch (c.reason()) {
            case "no_bot" -> "bot can't craft";
            case "recipes_differ" -> "recipes differ";
            case "no_table" -> "no crafting table";
            case "table_bad" -> "table unusable";
            case "no_recipes" -> "no recipes";
            default -> "see the note above";
        };
    }

    /** The label of the craft box. */
    public static String boxLabel(Info c, Totals t) {
        if (c == null || !c.canOrder() || !c.available()) return "Craft what the stash can make";
        if (t == null || t.craftKinds() == 0) return "Craft what the stash can make (nothing to make)";
        return "Craft what the stash can make (" + Text.grouped(t.craftable()) + " " + (t.craftable() == 1 ? "item" : "items") + " from the stash)";
    }

    /** The "Missing" shown for a row/total: after crafting when the server gave it. */
    public static long missingNow(PlanModel.Row r) {
        return r.craft() == null ? r.missing() : r.craft().missingAfterCraft();
    }

    // ---------- the orders view ----------

    /** The order card's craft lines ("Craft: 8 Spruce Stairs"), at most {@code max}; empty when none. */
    public static List<String> orderLines(OrderCraft c, int max) {
        List<String> out = new ArrayList<>();
        if (c == null) return out;
        for (Line l : c.lines()) {
            if (out.size() >= max) break;
            out.add(lineText(l));
        }
        return out;
    }

    /** The "made" sentence of a finished order, or "" when no result. */
    public static String madeText(Result r) {
        if (r == null || r.made().isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (Made m : r.made()) {
            parts.add(m.made() >= m.wanted() ? Text.grouped(m.made()) + " " + PlanModel.pretty(m.key())
                : Text.grouped(m.made()) + " of " + Text.grouped(m.wanted()) + " " + PlanModel.pretty(m.key()));
        }
        return "Crafted: " + String.join(", ", parts.size() > 6 ? parts.subList(0, 6) : parts) + (parts.size() > 6 ? ", +" + (parts.size() - 6) + " more" : "");
    }

    /** What could not be made and why, in plain words; empty when nothing was short. */
    public static String shortText(Result r) {
        if (r == null || r.shorts().isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (Shortfall s : r.shorts()) parts.add(Text.grouped(s.count()) + " " + PlanModel.pretty(s.key()) + " (" + shortReason(s.reason()) + ")");
        return "Not crafted: " + String.join(", ", parts.size() > 6 ? parts.subList(0, 6) : parts) + (parts.size() > 6 ? ", +" + (parts.size() - 6) + " more" : "");
    }

    /** What went back to the stash; empty when nothing did. */
    public static String returnedText(Result r) {
        if (r == null || r.returned().isEmpty()) return "";
        return "Returned to the stash: " + countList(r.returned(), 6);
    }
}
