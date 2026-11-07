package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwerte.stash.Order;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The orders of a stash as {@code GET /api/v1/orders} sends them, with the rules the website uses for who may cancel, retry
 * or force-cancel which order. The same {@link Entry} also describes an order run by this client (see the local order
 * store), so one list shows both. Everything the bot wrote (phase, message) is cleaned. Pure.
 */
public final class OrderModel {
    public static final long FORCE_CANCEL_AFTER_MS = 60_000;
    private static final int MAX_ORDERS = 200;
    private static final int MAX_LINES = 200;

    public static final String PENDING = "pending", ACCEPTED = "accepted", IN_PROGRESS = "in_progress",
        DELIVERED = "delivered", FAILED = "failed", CANCELLED = "cancelled";

    /**
     * One order. {@code total} is 0 when there is no progress; {@code hasPlacedBy} is false for an order from before there
     * were users (it belongs to the owner).
     */
    public record Entry(String id, String status, String phase, String message, int done, int total,
                        boolean cancelRequested, long cancelRequestedAt, long updatedAt, long createdAt,
                        boolean hasPlacedBy, String placedById, String placedByName, String retriedAs,
                        List<Order.Line> lines, String namesMode, String namesAll, List<String> namesEach,
                        int shulkers, boolean hasResult) {

        public boolean open() {
            return OrderModel.isOpen(status);
        }

        public boolean finished() {
            return OrderModel.isFinished(status);
        }
    }

    /** The orders (newest first) and what can still be ordered per key. */
    public record Listing(List<Entry> orders, Map<String, Long> availableItems, Map<String, Long> availableShulkers) {
        public static final Listing EMPTY = new Listing(List.of(), Map.of(), Map.of());

        /** Null when the server did not say (then the index count is the best guess). */
        public Long available(String type, String key) {
            return (Order.SHULKER.equals(type) ? availableShulkers : availableItems).get(key);
        }

        public int finishedCount() {
            int n = 0;
            for (Entry e : orders) if (e.finished()) n++;
            return n;
        }
    }

    private OrderModel() {
    }

    public static boolean isOpen(String status) {
        return PENDING.equals(status) || ACCEPTED.equals(status) || IN_PROGRESS.equals(status);
    }

    public static boolean isFinished(String status) {
        return DELIVERED.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
    }

    public static String statusLabel(String status) {
        return switch (status) {
            case PENDING -> "Pending";
            case ACCEPTED -> "Accepted";
            case IN_PROGRESS -> "In progress";
            case DELIVERED -> "Delivered";
            case FAILED -> "Failed";
            case CANCELLED -> "Cancelled";
            default -> Text.sanitize(status, 20);
        };
    }

    // ---------- parsing ----------

    /** Never null. */
    public static Listing parse(JsonElement body) {
        JsonObject o = Json.obj(body);
        if (o == null) return Listing.EMPTY;
        List<Entry> orders = new ArrayList<>();
        JsonArray array = Json.arr(o.get("orders"));
        if (array != null) {
            for (JsonElement e : array) {
                Entry entry = parseEntry(Json.obj(e));
                if (entry != null) orders.add(entry);
                if (orders.size() >= MAX_ORDERS) break;
            }
        }
        JsonObject available = Json.obj(o.get("available"));
        return new Listing(List.copyOf(orders), numbers(available == null ? null : Json.obj(available.get("items"))),
            numbers(available == null ? null : Json.obj(available.get("shulkers"))));
    }

    private static Map<String, Long> numbers(JsonObject o) {
        Map<String, Long> out = new HashMap<>();
        if (o == null) return out;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            JsonElement v = e.getValue();
            if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) out.put(e.getKey(), Math.max(0, v.getAsLong()));
        }
        return out;
    }

    /** Null when the object has no id or no lines array. */
    public static Entry parseEntry(JsonObject o) {
        if (o == null) return null;
        String id = Json.str(o, "id");
        JsonArray rawLines = Json.arr(o.get("lines"));
        if (id.isEmpty() || id.length() > 64 || rawLines == null) return null;
        List<Order.Line> lines = new ArrayList<>();
        for (JsonElement e : rawLines) {
            JsonObject l = Json.obj(e);
            if (l == null || lines.size() >= MAX_LINES) continue;
            String type = Json.str(l, "type");
            String key = Json.str(l, "key");
            double count = Json.num(l, "count", 0);
            if ((type.equals(Order.ITEM) || type.equals(Order.SHULKER)) && !key.isEmpty() && key.length() <= 256 && count >= 1) {
                lines.add(new Order.Line(type, key, (int) Math.min(Integer.MAX_VALUE, count)));
            }
        }
        JsonObject progress = Json.obj(o.get("progress"));
        JsonObject by = Json.obj(o.get("placedBy"));
        JsonObject result = Json.obj(o.get("result"));
        JsonObject names = Json.obj(o.get("names"));
        String mode = names == null ? "" : Json.str(names, "mode");
        List<String> each = new ArrayList<>();
        JsonArray eachRaw = names == null ? null : Json.arr(names.get("names"));
        if (eachRaw != null) {
            for (JsonElement e : eachRaw) {
                if (each.size() >= Order.MAX_NAMES) break;
                each.add(e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? Text.sanitize(e.getAsString(), 60) : "");
            }
        }
        return new Entry(id, Text.sanitize(Json.str(o, "status"), 20), Text.sanitize(Json.str(o, "phase"), 120),
            Text.sanitize(Json.str(o, "message"), 320),
            progress == null ? 0 : (int) Math.max(0, Json.num(progress, "done", 0)), progress == null ? 0 : (int) Math.max(0, Json.num(progress, "total", 0)),
            Json.bool(o, "cancelRequested"), Json.lng(o, "cancelRequestedAt", 0), Json.lng(o, "updatedAt", 0), Json.lng(o, "createdAt", 0),
            by != null, by == null ? "" : Json.str(by, "id"), by == null ? "" : Text.sanitize(Json.str(by, "name"), 40),
            Json.str(o, "retriedAs"), List.copyOf(lines), mode, names == null ? "" : Text.sanitize(Json.str(names, "name"), 60),
            List.copyOf(each), result == null ? 0 : (int) Math.max(0, Json.num(result, "shulkers", 0)), result != null);
    }

    // ---------- who may do what (the website's canManageOrder and friends) ----------

    /** An order is the user's own when they placed it; one with no placedBy is from before users: the owner's. */
    public static boolean ownOrder(Entry o, AuthState auth) {
        return o.hasPlacedBy() ? o.placedById().equals(auth.userId()) : auth.owner();
    }

    public static boolean canManage(Entry o, AuthState auth) {
        return auth.can("ordersAll") || ((auth.can("order") || auth.can("plannerOrder")) && ownOrder(o, auth));
    }

    public static boolean canCancel(Entry o, AuthState auth) {
        return canManage(o, auth) && o.open() && !o.cancelRequested();
    }

    /** A failed or cancelled order that was not retried already. */
    public static boolean canRetry(Entry o, AuthState auth) {
        return canManage(o, auth) && (FAILED.equals(o.status()) || CANCELLED.equals(o.status())) && o.retriedAs().isEmpty();
    }

    /** A cancel the bot has not confirmed within a minute, or the bot is offline. */
    public static boolean canForceCancel(Entry o, AuthState auth, boolean botOnline, long now) {
        if (!canManage(o, auth) || !o.cancelRequested()) return false;
        if (!ACCEPTED.equals(o.status()) && !IN_PROGRESS.equals(o.status())) return false;
        if (!botOnline) return true;
        long since = o.cancelRequestedAt() > 0 ? o.cancelRequestedAt() : o.updatedAt();
        return now - since >= FORCE_CANCEL_AFTER_MS;
    }

    /** Clear finished needs ordersAll. */
    public static boolean canClear(AuthState auth) {
        return auth.can("ordersAll");
    }

    /** Placing an order needs the order permission. */
    public static boolean canPlace(AuthState auth) {
        return auth.can("order");
    }

    // ---------- text ----------

    /** "[#####-----] 5/10" for a progress, "" without one. */
    public static String progressText(int done, int total, int width) {
        if (total <= 0) return "";
        int d = Math.max(0, Math.min(done, total));
        int filled = (int) Math.round((double) d / total * width);
        return "[" + "#".repeat(filled) + "-".repeat(Math.max(0, width - filled)) + "] " + d + "/" + total;
    }
}
