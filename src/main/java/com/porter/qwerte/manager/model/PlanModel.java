package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The Build Planner's data: the plan JSON of {@code PlannerRoutes.planJson}, the list of plans, the dry run and order replies,
 * the rows the table shows (filter, sort, page) and the "copy missing" text. Forgiving readers: a wrong type gives the default.
 * Pure (no Minecraft classes).
 */
public final class PlanModel {
    private PlanModel() {
    }

    public static final int MAX_MULTIPLIER = 64;
    public static final String[] ROUNDS = {"none", "stack", "shulker"};
    /** Rows of the materials table per page: keeps the number of widgets per frame small. */
    public static final int PAGE_SIZE = 25;

    // ---------- records ----------

    /** One option set. {@code exclude} is sorted. */
    public record Options(int multiplier, List<String> exclude, boolean fluids, boolean containerItems, String round) {
        public static final Options DEFAULT = new Options(1, List.of(), false, false, "none");

        public Options {
            multiplier = Math.max(1, Math.min(MAX_MULTIPLIER, multiplier));
            exclude = List.copyOf(new TreeSet<>(exclude));
            round = PlanModel.round(round);
        }

        public Options withMultiplier(int m) {
            return new Options(m, exclude, fluids, containerItems, round);
        }

        public Options withRound(String r) {
            return new Options(multiplier, exclude, fluids, containerItems, r);
        }

        public Options withFluids(boolean on) {
            return new Options(multiplier, exclude, on, containerItems, round);
        }

        public Options withContainers(boolean on) {
            return new Options(multiplier, exclude, fluids, on, round);
        }

        public Options withExcluded(String id, boolean excluded) {
            TreeSet<String> set = new TreeSet<>(exclude);
            if (excluded) set.add(id);
            else set.remove(id);
            return new Options(multiplier, new ArrayList<>(set), fluids, containerItems, round);
        }

        /** The body fields the server reads for the view and the order. */
        public void addTo(JsonObject body) {
            body.addProperty("multiplier", multiplier);
            JsonArray ex = new JsonArray();
            exclude.forEach(ex::add);
            body.add("exclude", ex);
            body.addProperty("fluids", fluids);
            body.addProperty("containerItems", containerItems);
            body.addProperty("round", round);
        }
    }

    /** "none", "stack" or "shulker"; anything else is "none". */
    public static String round(String r) {
        for (String s : ROUNDS) if (s.equals(r)) return s;
        return "none";
    }

    public record Count(String id, long count) {
    }

    public record Row(String id, String name, long base, long need, long fromBlocks, long fromFluids, long fromContainers,
                      boolean inIndex, long inStash, long available, long missing, long want, int maxStack, long variants,
                      boolean excluded) {
        /** The server's English name, else the id made readable (minecraft:oak_planks is Oak Planks). */
        public String title() {
            return name.isEmpty() ? pretty(id) : name;
        }
    }

    public record Totals(long need, long available, long missing, long want, boolean hasSplit, boolean frozen, long lines, long slots,
                         long shulkers, long parts) {
    }

    public record StashInfo(String server, String stash, boolean indexed, String mcVersion) {
    }

    /** The parts that were ordered. {@code placed} maps part number to order id. */
    public record Ordered(int partsTotal, Map<Integer, String> placed, List<Integer> remaining, List<Integer> again, boolean locked,
                          boolean sameOptions, Options options, String server, String stash) {
        public boolean started() {
            return !placed.isEmpty();
        }

        /** The highest placed part number, or 0. */
        public int lastPart() {
            int last = 0;
            for (int p : placed.keySet()) last = Math.max(last, p);
            return last;
        }
    }

    public record Plan(String id, String fileName, String format, long size, long dimX, long dimY, long dimZ, long regions, long blocks,
                       boolean newerThanTable, long createdAt, long expiresAt, Options options, List<Count> fluids,
                       List<Count> containerItems, List<Count> unknown, long unknownOmitted, StashInfo stash, List<Row> items,
                       Totals totals, Ordered ordered) {
        public Plan withOrdered(Ordered o) {
            return new Plan(id, fileName, format, size, dimX, dimY, dimZ, regions, blocks, newerThanTable, createdAt, expiresAt, options,
                fluids, containerItems, unknown, unknownOmitted, stash, items, totals, o);
        }
    }

    /** One entry of {@code GET /api/v1/planner}. */
    public record Summary(String id, String fileName, String format, long blocks, long createdAt, long expiresAt) {
    }

    public record Part(int part, long lineCount, long shulkers, long items, String order) {
    }

    /** The answer of a dry run. */
    public record Dry(List<Part> parts, int partsTotal, List<Integer> remaining, List<Integer> again, List<Count> notAvailable,
                      List<Count> missing) {
        /** The part an order would place first: the first remaining one, else the first to order again, else 1. */
        public int first() {
            return !remaining.isEmpty() ? remaining.get(0) : !again.isEmpty() ? again.get(0) : 1;
        }
    }

    /** The answer of a real order. */
    public record Placed(int part, int partsTotal, List<Integer> remaining, List<Integer> again, Map<Integer, String> placed,
                         String orderId, String orderStatus) {
    }

    // ---------- parsing ----------

    public static Options parseOptions(JsonObject o) {
        if (o == null) return Options.DEFAULT;
        List<String> ex = new ArrayList<>();
        JsonArray a = Json.arr(o.get("exclude"));
        if (a != null) for (JsonElement e : a) if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) ex.add(e.getAsString());
        return new Options((int) Json.lng(o, "multiplier", 1), ex, Json.bool(o, "fluids"), Json.bool(o, "containerItems"),
            Json.str(o, "round"));
    }

    private static List<Count> counts(JsonElement e) {
        List<Count> out = new ArrayList<>();
        JsonArray a = Json.arr(e);
        if (a == null) return out;
        for (JsonElement el : a) {
            JsonObject o = Json.obj(el);
            if (o == null) continue;
            String id = Json.str(o, "id");
            if (!id.isEmpty()) out.add(new Count(id, Math.max(0, Json.lng(o, "count", Json.lng(o, "need", Json.lng(o, "missing", 0))))));
        }
        return out;
    }

    private static List<Integer> ints(JsonElement e) {
        List<Integer> out = new ArrayList<>();
        JsonArray a = Json.arr(e);
        if (a == null) return out;
        for (JsonElement el : a) {
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) out.add(el.getAsInt());
        }
        return out;
    }

    private static Map<Integer, String> placed(JsonElement e) {
        Map<Integer, String> out = new TreeMap<>();
        JsonObject o = Json.obj(e);
        if (o == null) return out;
        for (Map.Entry<String, JsonElement> en : o.entrySet()) {
            try {
                int part = Integer.parseInt(en.getKey());
                if (en.getValue().isJsonPrimitive() && en.getValue().getAsJsonPrimitive().isString()) out.put(part, en.getValue().getAsString());
            } catch (NumberFormatException ignored) {
                // not a part number
            }
        }
        return out;
    }

    /** The plan object of an upload or view answer; null when it has no id. */
    public static Plan parsePlan(JsonElement element) {
        JsonObject o = Json.obj(element);
        if (o == null) return null;
        String id = Json.str(o, "id");
        if (id.isEmpty()) return null;
        JsonObject dim = Json.obj(o.get("dimensions"));
        List<Row> items = new ArrayList<>();
        JsonArray ia = Json.arr(o.get("items"));
        if (ia != null) {
            for (JsonElement el : ia) {
                JsonObject r = Json.obj(el);
                if (r == null || Json.str(r, "id").isEmpty()) continue;
                JsonObject from = Json.obj(r.get("from"));
                items.add(new Row(Json.str(r, "id"), Text.sanitize(Json.str(r, "name"), 64), Json.lng(r, "base", 0), Json.lng(r, "need", 0),
                    Json.lng(from, "blocks", 0), Json.lng(from, "fluids", 0), Json.lng(from, "containers", 0), Json.bool(r, "inIndex"),
                    Json.lng(r, "inStash", 0), Json.lng(r, "available", 0), Json.lng(r, "missing", 0), Json.lng(r, "want", 0),
                    (int) Math.max(1, Math.min(64, Json.lng(r, "maxStack", 64))), Json.lng(r, "variants", 0), Json.bool(r, "excluded")));
            }
        }
        JsonObject t = Json.obj(o.get("totals"));
        Totals totals = new Totals(Json.lng(t, "need", 0), Json.lng(t, "available", 0), Json.lng(t, "missing", 0), Json.lng(t, "want", 0),
            t != null && t.has("parts"), Json.bool(t, "frozen"), Json.lng(t, "lines", 0), Json.lng(t, "slots", 0), Json.lng(t, "shulkers", 0),
            Json.lng(t, "parts", 0));
        JsonObject so = Json.obj(o.get("stash"));
        StashInfo stash = so == null ? null : new StashInfo(Json.str(so, "server"), Json.str(so, "stash"), Json.bool(so, "indexed"),
            Json.str(so, "mcVersion"));
        JsonObject od = Json.obj(o.get("ordered"));
        Ordered ordered = od == null ? null : new Ordered((int) Math.max(0, Json.lng(od, "partsTotal", 0)), placed(od.get("placed")),
            ints(od.get("remaining")), ints(od.get("again")), Json.bool(od, "locked"), Json.bool(od, "sameOptions"),
            parseOptions(Json.obj(od.get("options"))), Json.str(od, "server"), Json.str(od, "stash"));
        return new Plan(id, Text.sanitize(Json.str(o, "fileName"), 120), Json.str(o, "format"), Json.lng(o, "size", 0), Json.lng(dim, "x", 0),
            Json.lng(dim, "y", 0), Json.lng(dim, "z", 0), Json.lng(o, "regions", 0), Json.lng(o, "blocks", 0), Json.bool(o, "newerThanTable"),
            Json.lng(o, "createdAt", 0), Json.lng(o, "expiresAt", 0), parseOptions(Json.obj(o.get("options"))), counts(o.get("fluids")),
            counts(o.get("containerItems")), counts(o.get("unknown")), Json.lng(o, "unknownOmitted", 0), stash,
            Collections.unmodifiableList(items), totals, ordered);
    }

    /** The answer of GET /api/v1/planner: oldest first, as the server sends it. */
    public static List<Summary> parseList(JsonElement body) {
        List<Summary> out = new ArrayList<>();
        JsonObject o = Json.obj(body);
        JsonArray a = o == null ? null : Json.arr(o.get("plans"));
        if (a == null) return out;
        for (JsonElement el : a) {
            JsonObject p = Json.obj(el);
            if (p == null || Json.str(p, "id").isEmpty()) continue;
            out.add(new Summary(Json.str(p, "id"), Text.sanitize(Json.str(p, "fileName"), 120), Json.str(p, "format"), Json.lng(p, "blocks", 0),
                Json.lng(p, "createdAt", 0), Json.lng(p, "expiresAt", 0)));
        }
        return out;
    }

    public static Dry parseDry(JsonElement body) {
        JsonObject o = Json.obj(body);
        if (o == null) return null;
        List<Part> parts = new ArrayList<>();
        JsonArray pa = Json.arr(o.get("parts"));
        if (pa != null) {
            for (JsonElement el : pa) {
                JsonObject p = Json.obj(el);
                if (p == null) continue;
                parts.add(new Part((int) Json.lng(p, "part", parts.size() + 1), Json.lng(p, "lineCount", 0), Json.lng(p, "shulkers", 0),
                    Json.lng(p, "items", 0), Json.str(p, "order")));
            }
        }
        return new Dry(parts, (int) Json.lng(o, "partsTotal", parts.size()), ints(o.get("remaining")), ints(o.get("again")),
            counts(o.get("notAvailable")), counts(o.get("missing")));
    }

    public static Placed parsePlaced(JsonElement body) {
        JsonObject o = Json.obj(body);
        if (o == null) return null;
        JsonObject order = Json.obj(o.get("order"));
        return new Placed((int) Json.lng(o, "part", 0), (int) Json.lng(o, "partsTotal", 1), ints(o.get("remaining")), ints(o.get("again")),
            placed(o.get("placed")), Json.str(order, "id"), Json.str(order, "status"));
    }

    /** The answer of GET /api/v1/orders: order id to status. */
    public static Map<String, String> parseOrderStatuses(JsonElement body) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonObject o = Json.obj(body);
        JsonArray a = o == null ? null : Json.arr(o.get("orders"));
        if (a == null) return out;
        for (JsonElement el : a) {
            JsonObject ord = Json.obj(el);
            if (ord == null) continue;
            String id = Json.str(ord, "id");
            if (!id.isEmpty()) out.put(id, Json.str(ord, "status"));
        }
        return out;
    }

    // ---------- order state ----------

    public static boolean finished(String status) {
        return "delivered".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    public static boolean failed(String status) {
        return "failed".equals(status) || "cancelled".equals(status);
    }

    /** True while parts are placed and not all finished, or more parts wait: the order list is polled. */
    public static boolean pollOrders(Ordered o, Map<String, String> statuses) {
        if (o == null || !o.started()) return false;
        if (!o.remaining().isEmpty()) return true;
        for (String id : o.placed().values()) if (!finished(statuses.get(id))) return true;
        return false;
    }

    /** A placed part ended failed or cancelled that the plan does not list as orderable again: ask the server again. */
    public static boolean needsReload(Ordered o, Map<String, String> statuses) {
        if (o == null) return false;
        for (Map.Entry<Integer, String> e : o.placed().entrySet()) {
            if (failed(statuses.get(e.getValue())) && !o.again().contains(e.getKey())) return true;
        }
        return false;
    }

    /** What the order area says about the last placed part; warn marks trouble. Empty text: nothing to say. */
    public record OrderHint(String text, boolean warn, boolean nextEnabled) {
    }

    public static OrderHint hint(Ordered o, Map<String, String> statuses) {
        if (o == null || !o.started()) return new OrderHint("", false, false);
        int total = o.partsTotal();
        if (o.remaining().isEmpty()) {
            return new OrderHint(o.again().isEmpty()
                ? "All " + total + (total == 1 ? " part" : " parts") + " ordered. Dismiss and pick the file again for a new order."
                : "Part " + joined(o.again()) + " failed or was cancelled; order it again when the bot is ready.", !o.again().isEmpty(), false);
        }
        int last = o.lastPart();
        String id = o.placed().get(last);
        String st = statuses.get(id);
        if (st == null || st.isEmpty()) return new OrderHint("Part " + last + " ordered (" + Text.sanitize(id, 24) + "). Checking its status...", false, false);
        if (!finished(st)) {
            return new OrderHint("Part " + last + " is " + Text.sanitize(st, 24) + ". One order fills the delivery chest, so order the next part once this one is delivered.", false, false);
        }
        if (!"delivered".equals(st)) return new OrderHint("Part " + last + " " + Text.sanitize(st, 24) + ". You can still order the next part.", true, true);
        return new OrderHint("Part " + last + " delivered. Empty the delivery chest, then order the next part.", false, true);
    }

    private static String joined(List<Integer> list) {
        StringBuilder sb = new StringBuilder();
        for (int i : list) sb.append(sb.length() == 0 ? "" : ", ").append(i);
        return sb.toString();
    }

    // ---------- the table ----------

    public enum Sort {
        CREATIVE("Creative order"), NAME("Name"), NEED("Need"), IN_STASH("In stash"), AVAILABLE("Available"), MISSING("Missing");

        private final String label;

        Sort(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Sort ofLabel(String label) {
            for (Sort s : values()) if (s.label.equals(label)) return s;
            return CREATIVE;
        }
    }

    /** The rows the filter box and only-missing keep, in the chosen order. CREATIVE keeps the server's order. */
    public static List<Row> visible(List<Row> rows, String filter, boolean onlyMissing, Sort sort, boolean descending) {
        String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (onlyMissing && !(r.missing() > 0 && !r.excluded())) continue;
            if (!f.isEmpty() && !r.title().toLowerCase(Locale.ROOT).contains(f) && !r.id().toLowerCase(Locale.ROOT).contains(f)) continue;
            out.add(r);
        }
        Comparator<Row> c = switch (sort) {
            case NAME -> Comparator.comparing(r -> r.title().toLowerCase(Locale.ROOT));
            case NEED -> Comparator.comparingLong(Row::need);
            case IN_STASH -> Comparator.comparingLong(Row::inStash);
            case AVAILABLE -> Comparator.comparingLong(Row::available);
            case MISSING -> Comparator.comparingLong(Row::missing);
            default -> null;
        };
        // List.sort is stable: equal rows stay in the server's order
        if (c != null) out.sort(descending ? c.reversed() : c);
        return out;
    }

    public static int pageCount(int rows) {
        return Math.max(1, (rows + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** The rows of page (0-based; clamped). */
    public static List<Row> page(List<Row> rows, int page) {
        int p = Math.max(0, Math.min(page, pageCount(rows.size()) - 1));
        return rows.subList(Math.min(rows.size(), p * PAGE_SIZE), Math.min(rows.size(), (p + 1) * PAGE_SIZE));
    }

    // ---------- text ----------

    /** minecraft:oak_planks is Oak Planks. */
    public static String pretty(String id) {
        String path = id == null ? "" : id;
        int colon = path.indexOf(':');
        if (colon >= 0) path = path.substring(colon + 1);
        StringBuilder sb = new StringBuilder();
        for (String w : path.split("[_/]")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.length() == 0 ? Text.sanitize(id, 64) : Text.sanitize(sb.toString(), 64);
    }

    /** Shulkers (27 stacks), stacks and the rest, like the website: 2 sh + 3 st + 5. */
    public static String stacks(long n, int maxStack) {
        n = Math.max(0, n);
        int ms = Math.max(1, maxStack);
        if (n == 0) return "0";
        long sh = 27L * ms;
        long a = n / sh;
        long r = n - a * sh;
        List<String> parts = new ArrayList<>();
        if (a > 0) parts.add(Text.grouped(a) + " sh");
        if (ms > 1) {
            long b = r / ms;
            r -= b * ms;
            if (b > 0) parts.add(b + " st");
        }
        if (r > 0) parts.add(Long.toString(r));
        return String.join(" + ", parts);
    }

    /** The rows still missing (not excluded). */
    public static List<Row> missingRows(Plan p) {
        List<Row> out = new ArrayList<>();
        if (p != null) for (Row r : p.items()) if (!r.excluded() && r.missing() > 0) out.add(r);
        return out;
    }

    /** The Copy missing text, as the website text export says it. */
    public static String missingText(Plan p) {
        List<Row> rows = missingRows(p);
        StringBuilder sb = new StringBuilder("Missing materials (").append(rows.size()).append(rows.size() == 1 ? " kind)" : " kinds)");
        for (Row r : rows) {
            sb.append("\n- ").append(r.title()).append(": ").append(Text.grouped(r.missing())).append(" (").append(stacks(r.missing(), r.maxStack())).append(")");
        }
        return sb.append("\n").toString();
    }

    /** 12.3 KiB */
    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }

    // ---------- errors ----------

    /** The words for a failed upload (status 0: no answer at all; retryAfter is for a 429). */
    public static String uploadError(int status, String serverError, long retryAfter) {
        String err = Text.sanitize(serverError, 200);
        switch (status) {
            case 0:
                return "Could not upload: the server is unreachable or too slow.";
            case 413:
                return "That file is empty or larger than 8 MiB.";
            case 422:
                return "That is a legacy schematic format. Open it in Litematica or WorldEdit and save it again as .litematic or .schem.";
            case 429:
                return "Too many uploads. Try again in " + Math.max(1, retryAfter) + " s.";
            default:
                return err.isEmpty() ? "Could not read the file (HTTP " + status + ")." : Text.sentence(err);
        }
    }

    /** The words for a failed order or update. 409 is the locked options. */
    public static String orderError(int status, String serverError) {
        String err = Text.sanitize(serverError, 200);
        if (status == 409) return err.isEmpty() ? "The options can not change after a part is ordered." : Text.sentence(err);
        if (status == 0) return "The server is unreachable or too slow.";
        return err.isEmpty() ? "Failed (HTTP " + status + ")." : Text.sentence(err);
    }
}
