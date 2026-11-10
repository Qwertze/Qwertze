package com.porter.qwerte.notify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the server's JSON (PROTOCOL.md) into what the notifier needs. Pure; anything of the wrong shape is skipped, never thrown. */
public final class OrderSnaps {
    private static final int MAX_ORDERS = 200;

    private OrderSnaps() {}

    /** Who is logged in: from {@code GET /api/v1/auth/state}. A server without sub-users sends no user, which is the owner. */
    public record User(boolean loggedIn, String id, boolean owner) {
        public static final User NONE = new User(false, "", false);

        public static User parse(JsonElement body) {
            JsonObject o = obj(body);
            if (o == null || !bool(o, "loggedIn")) return NONE;
            JsonObject u = obj(o.get("user"));
            if (u == null) return new User(true, "owner", true);
            boolean owner = bool(u, "owner");
            String id = str(u, "id");
            return new User(true, id.isEmpty() ? (owner ? "owner" : "?") : id, owner);
        }
    }

    /** An order is the user's own when its placedBy is the user, or (no placedBy: an order from before there were users) the user is the owner. */
    public static boolean mine(JsonObject order, User user) {
        JsonObject by = obj(order.get("placedBy"));
        if (by == null) return user.owner();
        String id = str(by, "id");
        return !id.isEmpty() && id.equals(user.id());
    }

    /** The user's own orders of {@code GET /api/v1/orders}; null when the body is not a valid list (then nothing may be learned from it). */
    public static List<OrderSnap> parseOrders(JsonElement body, User user) {
        JsonObject root = obj(body);
        JsonArray array = root == null ? null : arr(root.get("orders"));
        if (array == null || !user.loggedIn()) return null;
        List<OrderSnap> out = new ArrayList<>();
        for (JsonElement e : array) {
            if (out.size() >= MAX_ORDERS) break;
            JsonObject o = obj(e);
            if (o == null) continue;
            String id = str(o, "id");
            String status = str(o, "status");
            if (id.isEmpty() || id.length() > 64 || status.isEmpty() || !mine(o, user)) continue;
            out.add(new OrderSnap(id, status, str(o, "phase"), str(o, "message"), "delivered".equals(status) ? shortfalls(o) : List.of()));
        }
        return out;
    }

    /**
     * What a delivered order did not deliver: a requested item or craft line whose {@code result.lines} delivered amount is
     * smaller, every {@code result.craft.short} entry and every {@code result.kit.short} entry.
     */
    static List<OrderSnap.Short> shortfalls(JsonObject order) {
        Map<String, Integer> out = new LinkedHashMap<>();
        JsonObject result = obj(order.get("result"));
        if (result == null) return List.of();

        Map<String, Long> wanted = new LinkedHashMap<>();
        JsonArray lines = arr(order.get("lines"));
        if (lines != null) {
            for (JsonElement e : lines) {
                JsonObject l = obj(e);
                if (l == null) continue;
                String type = str(l, "type");
                if (!type.equals("item") && !type.equals("craft")) continue;
                String key = str(l, "key");
                long count = num(l, "count");
                if (!key.isEmpty() && count > 0) wanted.merge(key, count, Long::sum);
            }
        }
        JsonArray delivered = arr(result.get("lines"));
        if (delivered != null && !wanted.isEmpty()) {
            Map<String, Long> got = new LinkedHashMap<>();
            for (JsonElement e : delivered) {
                JsonObject l = obj(e);
                if (l == null) continue;
                String key = str(l, "key");
                if (!key.isEmpty()) got.merge(key, Math.max(0, num(l, "delivered")), Long::sum);
            }
            // Only keys the bot reported on: a bot that sends no line for a key told us nothing about it
            for (Map.Entry<String, Long> g : got.entrySet()) {
                Long want = wanted.get(g.getKey());
                if (want != null && g.getValue() < want) out.merge(g.getKey(), (int) Math.min(Integer.MAX_VALUE, want - g.getValue()), Integer::sum);
            }
        }

        JsonObject craft = obj(result.get("craft"));
        JsonArray craftShort = craft == null ? null : arr(craft.get("short"));
        if (craftShort != null) {
            for (JsonElement e : craftShort) {
                JsonObject s = obj(e);
                if (s == null) continue;
                out.merge(str(s, "key"), (int) Math.min(Integer.MAX_VALUE, Math.max(0, num(s, "count"))), Integer::sum);
            }
        }

        JsonObject kit = obj(result.get("kit"));
        JsonArray kitShort = kit == null ? null : arr(kit.get("short"));
        if (kitShort != null) {
            for (JsonElement e : kitShort) {
                JsonObject s = obj(e);
                if (s == null) continue;
                JsonArray items = arr(s.get("items"));
                boolean any = false;
                if (items != null) {
                    for (JsonElement ie : items) {
                        JsonObject i = obj(ie);
                        if (i == null) continue;
                        any = true;
                        out.merge(str(i, "key"), (int) Math.min(Integer.MAX_VALUE, Math.max(0, num(i, "count"))), Integer::sum);
                    }
                }
                // A short kit with no item list is still a shortfall
                if (!any) out.merge("kit " + str(s, "template"), 0, Integer::sum);
            }
        }

        List<OrderSnap.Short> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : out.entrySet()) list.add(new OrderSnap.Short(e.getKey(), e.getValue()));
        return list;
    }

    /** One entry of {@code GET /api/v1/stashes}: the scope to fetch and how many orders are open there (-1 = not said). */
    public record StashEntry(String scope, String stash, String server, int open) {}

    /** The stashes of {@code GET /api/v1/stashes}. Never null. */
    public static List<StashEntry> parseStashes(JsonElement body) {
        List<StashEntry> out = new ArrayList<>();
        JsonArray array = arr(body);
        if (array == null) return out;
        for (JsonElement e : array) {
            if (out.size() >= 256) break;
            JsonObject o = obj(e);
            if (o == null) continue;
            String server = str(o, "server");
            String stash = str(o, "stash");
            if (server.isEmpty() || stash.isEmpty()) continue;
            JsonObject orders = obj(o.get("orders"));
            int open = orders == null || !orders.has("open") ? -1 : (int) Math.max(0, num(orders, "open"));
            out.add(new StashEntry(scope(server, stash), stash, server, open));
        }
        return out;
    }

    public static String scope(String server, String stash) {
        return server + "\u0000" + stash;
    }

    // ---------- tolerant JSON access ----------

    private static JsonObject obj(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray arr(JsonElement e) {
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return "";
        return clean(e.getAsString());
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static long num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return 0;
        double d = e.getAsDouble();
        return Double.isNaN(d) ? 0 : (long) Math.max(-1e12, Math.min(1e12, d));
    }

    /** No control characters or section signs; length capped. */
    static String clean(String s) {
        StringBuilder sb = new StringBuilder(Math.min(s.length(), 320));
        for (int i = 0; i < s.length() && sb.length() < 320; i++) {
            char c = s.charAt(i);
            if (c < 32 || c == 127 || c == '§') continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }
}
