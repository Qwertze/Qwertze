package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orders (PROTOCOL.md, "Orders"). The website places, lists and cancels them (browser-write guard on the POSTs);
 * the addon claims them and reports status (bearer key).
 */
final class OrderRoutes {
    private static final Pattern CANCEL = Pattern.compile("^/api/v1/orders/(o-[0-9a-f]{12})/cancel$");
    private static final Pattern STATUS = Pattern.compile("^/api/v1/orders/(o-[0-9a-f]{12})/status$");
    private static final Pattern RETRY = Pattern.compile("^/api/v1/orders/(o-[0-9a-f]{12})/retry$");
    private static final Pattern FORCE_CANCEL = Pattern.compile("^/api/v1/orders/(o-[0-9a-f]{12})/force-cancel$");
    private static final int MAX_KEY = 256;
    private static final int MAX_RESULT_LINES = 400;

    private final OrderStore orders;
    private final Owners owners;
    private final AuditLog audit;

    OrderRoutes(OrderStore orders, Owners owners, AuditLog audit) {
        this.orders = orders;
        this.owners = owners;
        this.audit = audit;
    }

    /**
     * The user may see this stash (in their stash scope). False after answering 404, as for a stash that doesn't
     * exist, so a scoped user learns nothing about others.
     */
    /**
     * A stash named by a request, checked: the real server address (an opaque id from a user in forced streamer mode
     * is mapped back; a raw address from them is refused) when it is in the user's scope, else null after 404.
     */
    static String scoped(HttpExchange ex, String server, String stash) throws IOException {
        String real = Router.serverParam(ex, server);
        if (real == null) {
            Http.error(ex, 404, "no such stash");
            return null;
        }
        return inScope(ex, real, stash) ? real : null;
    }

    static boolean inScope(HttpExchange ex, String server, String stash) throws IOException {
        Users.Principal p = Router.user(ex);
        if (p == null || p.perms().seesStash(server, stash)) return true;
        Http.error(ex, 404, "no such stash");
        return false;
    }

    /**
     * May the user change this order (cancel, retry, force-cancel)? Their own with order or plannerOrder, any with ordersAll; the
     * order's stash must be in scope. False after answering 404 or 403.
     */
    private boolean mayChange(HttpExchange ex, String id) throws IOException {
        IndexStore.StashKey where = orders.stashOf(id);
        Users.Principal p = Router.user(ex);
        if (where == null || !p.perms().seesStash(where.server(), where.stash())) {
            Http.error(ex, 404, "no such order");
            return false;
        }
        if (p.perms().ordersAll()) return true;
        // order or plannerOrder: the user's own orders (build planner parts included).
        if ((p.perms().order() || p.perms().plannerOrder()) && p.id().equals(orders.placedBy(id))) return true;
        Router.forbid(ex, "change other users' orders", "ordersAll");
        return false;
    }

    void register(Router router) {
        // claim is registered before the id patterns; the patterns only match o-<12 hex>.
        router.exact(Router.POST, "/api/v1/orders/claim", Router.Guard.ADDON_KEY, this::claim)
            .exact(Router.POST, "/api/v1/orders", Router.Guard.BROWSER_WRITE, Router.Perm.ORDER, this::place)
            .exact(Router.POST, "/api/v1/orders/clear", Router.Guard.BROWSER_WRITE, Router.Perm.ORDERS_ALL, this::clear)
            .exact(Router.GET, "/api/v1/orders", Router.Guard.SESSION, Router.Perm.VIEW, this::list)
            .pattern(Router.POST, CANCEL, Router.Guard.BROWSER_WRITE, Router.Perm.ORDER_OWN_OR_ALL, this::cancel)
            .pattern(Router.POST, FORCE_CANCEL, Router.Guard.BROWSER_WRITE, Router.Perm.ORDER_OWN_OR_ALL, this::forceCancel)
            .pattern(Router.POST, RETRY, Router.Guard.BROWSER_WRITE, Router.Perm.ORDER_OWN_OR_ALL, this::retry)
            .pattern(Router.POST, STATUS, Router.Guard.ADDON_KEY, this::status);
    }

    // ---------- browser ----------

    private void place(HttpExchange ex) throws IOException {
        JsonElement json = AddonRoutes.readJson(ex);
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return;
        }
        JsonObject o = json.getAsJsonObject();
        String server = AddonRoutes.string(o, "server");
        String stash = AddonRoutes.string(o, "stash");
        if (server == null || server.isEmpty() || stash == null || stash.isEmpty()) {
            Http.error(ex, 400, "server and stash are required");
            return;
        }
        server = scoped(ex, server, stash);
        if (server == null) return;
        // "note" (removed) is ignored if an old page still sends it.
        JsonObject names;
        try {
            names = OrderStore.normalizeNames(o.get("names"));
        } catch (OrderStore.OrderException e) {
            lineError(ex, e.status, e.getMessage(), -1);
            return;
        }
        if (!o.has("lines") || !o.get("lines").isJsonArray()) {
            Http.error(ex, 400, "lines must be an array");
            return;
        }
        JsonArray raw = o.getAsJsonArray("lines");
        if (raw.size() > OrderStore.MAX_LINES) {
            Http.error(ex, 400, "at most " + OrderStore.MAX_LINES + " lines");
            return;
        }
        List<OrderStore.Line> lines = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String problem = null;
            OrderStore.Line line = null;
            JsonElement e = raw.get(i);
            if (!e.isJsonObject()) {
                problem = "line must be an object";
            } else {
                JsonObject l = e.getAsJsonObject();
                String type = AddonRoutes.string(l, "type");
                String key = AddonRoutes.string(l, "key");
                long count = positiveInt(l.get("count"));
                if (!"item".equals(type) && !"shulker".equals(type)) problem = "type must be item or shulker";
                else if (key == null || key.isEmpty() || key.length() > MAX_KEY) problem = "key must be a non-empty string";
                else if (count < 1) problem = "count must be a whole number from 1 to " + Integer.MAX_VALUE;
                else line = new OrderStore.Line(type, key, count);
            }
            if (problem != null) {
                lineError(ex, 400, problem, i);
                return;
            }
            lines.add(line);
        }
        try {
            Users.Principal who = Router.user(ex);
            OrderStore.Order order = orders.place(server, stash, lines, names, who.id(), who.name());
            JsonObject out = Http.ok();
            out.add("order", shown(ex, order.toJson()));
            Http.json(ex, 200, out);
        } catch (OrderStore.OrderException e) {
            lineError(ex, e.status, e.getMessage(), e.line);
        }
    }

    /** POST /api/v1/orders/clear {server, stash}: drops the stash's finished orders; open ones stay. */
    private void clear(HttpExchange ex) throws IOException {
        JsonElement json = AddonRoutes.readJson(ex);
        JsonObject o = json != null && json.isJsonObject() ? json.getAsJsonObject() : null;
        String server = o == null ? null : AddonRoutes.string(o, "server");
        String stash = o == null ? null : AddonRoutes.string(o, "stash");
        if (server == null || server.isEmpty() || stash == null || stash.isEmpty()) {
            Http.error(ex, 400, "server and stash are required");
            return;
        }
        server = scoped(ex, server, stash);
        if (server == null) return;
        JsonObject out = Http.ok();
        int removed = orders.clearFinished(server, stash);
        out.addProperty("removed", removed);
        audit.add(Router.user(ex), "orders.clear", server + " / " + stash + ": " + removed, AuthRoutes.address(ex));
        Http.json(ex, 200, out);
    }

    private void list(HttpExchange ex) throws IOException {
        Map<String, String> q = Http.query(ex);
        String server = q.get("server");
        String stash = q.get("stash");
        if (server == null || stash == null) {
            Http.error(ex, 400, "server and stash are required");
            return;
        }
        server = scoped(ex, server, stash);
        if (server == null) return;
        JsonObject list = orders.listJson(server, stash);
        if (Redact.on(ex)) Redact.orders(list, Router.user(ex).id());
        Http.json(ex, 200, list);
    }

    /** An order as this user may see it (forced streamer mode: other users' names and position-like text hidden). */
    private static JsonObject shown(HttpExchange ex, JsonObject order) {
        if (Redact.on(ex)) Redact.order(order, Router.user(ex).id());
        return order;
    }

    private void cancel(HttpExchange ex) throws IOException {
        Matcher m = CANCEL.matcher(ex.getRequestURI().getRawPath());
        if (!m.matches()) {
            Http.error(ex, 404, "not found");
            return;
        }
        if (!mayChange(ex, m.group(1))) return;
        try {
            OrderStore.Order order = orders.cancel(m.group(1));
            audit.add(Router.user(ex), "order.cancel", order.id, AuthRoutes.address(ex));
            JsonObject out = Http.ok();
            out.add("order", shown(ex, order.toJson()));
            Http.json(ex, 200, out);
        } catch (OrderStore.OrderException e) {
            Http.error(ex, e.status, e.getMessage());
        }
    }

    /** POST /api/v1/orders/{id}/retry: places a failed or cancelled order again as a new pending order. */
    private void retry(HttpExchange ex) throws IOException {
        Matcher m = RETRY.matcher(ex.getRequestURI().getRawPath());
        if (!m.matches()) {
            Http.error(ex, 404, "not found");
            return;
        }
        if (!mayChange(ex, m.group(1))) return;
        try {
            Users.Principal who = Router.user(ex);
            boolean own = who.id().equals(orders.placedBy(m.group(1)));
            if (!who.perms().order() && !(who.perms().plannerOrder() && own)) {
                Router.forbid(ex, "place orders", "order");
                return;
            }
            OrderStore.Order order = orders.retry(m.group(1), who.id(), who.name());
            audit.add(who, "order.retry", m.group(1) + " as " + order.id, AuthRoutes.address(ex));
            JsonObject out = Http.ok();
            out.add("order", shown(ex, order.toJson()));
            Http.json(ex, 200, out);
        } catch (OrderStore.OrderException e) {
            if (e.status == 400) lineError(ex, e.status, e.getMessage(), e.line);
            else Http.error(ex, e.status, e.getMessage());
        }
    }

    /** The bot never confirmed a requested cancel: mark the order cancelled from the website. */
    private void forceCancel(HttpExchange ex) throws IOException {
        Matcher m = FORCE_CANCEL.matcher(ex.getRequestURI().getRawPath());
        if (!m.matches()) {
            Http.error(ex, 404, "not found");
            return;
        }
        if (!mayChange(ex, m.group(1))) return;
        try {
            OrderStore.Order order = orders.forceCancel(m.group(1));
            audit.add(Router.user(ex), "order.force-cancel", order.id, AuthRoutes.address(ex));
            JsonObject out = Http.ok();
            out.add("order", shown(ex, order.toJson()));
            Http.json(ex, 200, out);
        } catch (OrderStore.OrderException e) {
            Http.error(ex, e.status, e.getMessage());
        }
    }

    // ---------- addon ----------

    private void claim(HttpExchange ex) throws IOException {
        JsonElement json = AddonRoutes.readJson(ex);
        JsonObject o = json != null && json.isJsonObject() ? json.getAsJsonObject() : null;
        String server = o == null ? null : AddonRoutes.string(o, "server");
        String stash = o == null ? null : AddonRoutes.string(o, "stash");
        if (server == null || server.isEmpty() || stash == null || stash.isEmpty()) {
            Http.error(ex, 400, "server and stash are required");
            return;
        }
        String owner = owners.check(server, stash, AddonRoutes.string(o, "player"), false);
        if (owner != null) {
            AddonRoutes.conflict(ex, owner);
            return;
        }
        OrderStore.Order order = orders.claim(server, stash);
        if (order == null) {
            Http.send(ex, 204, null, null, "no-store");
            return;
        }
        JsonObject out = Http.ok();
        out.add("order", shown(ex, order.toJson()));
        Http.json(ex, 200, out);
    }

    private void status(HttpExchange ex) throws IOException {
        Matcher m = STATUS.matcher(ex.getRequestURI().getRawPath());
        if (!m.matches()) {
            Http.error(ex, 404, "not found");
            return;
        }
        JsonElement json = AddonRoutes.readJson(ex);
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return;
        }
        JsonObject o = json.getAsJsonObject();
        IndexStore.StashKey where = orders.stashOf(m.group(1));
        if (where != null) {
            String owner = owners.check(where.server(), where.stash(), AddonRoutes.string(o, "player"), false);
            if (owner != null) {
                AddonRoutes.conflict(ex, owner);
                return;
            }
        }
        String status = AddonRoutes.string(o, "status");
        if (status == null || !(status.equals("accepted") || status.equals("in_progress") || OrderStore.FINISHED.contains(status))) {
            Http.error(ex, 400, "status must be accepted, in_progress, delivered, failed or cancelled");
            return;
        }
        String phase;
        String message;
        JsonObject progress;
        JsonObject result;
        try {
            phase = optString(o, "phase", OrderStore.MAX_PHASE);
            message = optString(o, "message", OrderStore.MAX_MESSAGE);
            progress = progress(o.get("progress"));
            result = result(o.get("result"));
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        try {
            OrderStore.Order order = orders.status(m.group(1), status, phase, progress, message, result);
            JsonObject out = Http.ok();
            out.add("order", shown(ex, order.toJson()));
            Http.json(ex, 200, out);
        } catch (OrderStore.OrderException e) {
            Http.error(ex, e.status, e.getMessage());
        }
    }

    // ---------- validation helpers ----------

    private static void lineError(HttpExchange ex, int status, String message, int line) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        o.addProperty("line", line >= 0 ? line : null);
        Http.json(ex, status, o);
    }

    /** A whole number 1..2^31-1 (1.0 is fine, 1.5 isn't), or -1. */
    static long positiveInt(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !((JsonPrimitive) e).isNumber()) return -1;
        try {
            BigDecimal d = e.getAsBigDecimal();
            if (d.signum() <= 0 || d.stripTrailingZeros().scale() > 0) return -1;
            if (d.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) return -1;
            return d.longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return -1;
        }
    }

    /** A non-negative whole number up to 2^31-1, or -1. */
    private static long nonNegativeInt(JsonElement e) {
        if (e != null && e.isJsonPrimitive() && ((JsonPrimitive) e).isNumber() && e.getAsBigDecimal().signum() == 0) return 0;
        return positiveInt(e);
    }

    /** An optional string, cut to max characters (a wrong type is still an error). */
    private static String optString(JsonObject o, String field, int max) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull()) return null;
        String s = AddonRoutes.string(o, field);
        if (s == null) throw new IllegalArgumentException(field + " must be a string");
        return Http.truncate(s, max);
    }

    private static JsonObject progress(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonObject()) throw new IllegalArgumentException("progress must be {done, total} or null");
        JsonObject p = e.getAsJsonObject();
        long done = nonNegativeInt(p.get("done"));
        long total = nonNegativeInt(p.get("total"));
        if (done < 0 || total < 0) throw new IllegalArgumentException("progress.done and progress.total must be whole numbers >= 0");
        JsonObject out = new JsonObject();
        out.addProperty("done", done);
        out.addProperty("total", total);
        return out;
    }

    private static JsonObject result(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonObject()) throw new IllegalArgumentException("result must be {shulkers, lines} or null");
        JsonObject r = e.getAsJsonObject();
        long shulkers = nonNegativeInt(r.get("shulkers"));
        if (shulkers < 0) throw new IllegalArgumentException("result.shulkers must be a whole number >= 0");
        JsonArray outLines = new JsonArray();
        JsonElement ls = r.get("lines");
        if (ls != null && !ls.isJsonNull()) {
            if (!ls.isJsonArray()) throw new IllegalArgumentException("result.lines must be an array");
            int n = 0;
            for (JsonElement le : ls.getAsJsonArray()) {
                if (n++ >= MAX_RESULT_LINES) break; // extra entries are dropped
                if (!le.isJsonObject()) throw new IllegalArgumentException("result.lines entries must be objects");
                JsonObject l = le.getAsJsonObject();
                String key = AddonRoutes.string(l, "key");
                long delivered = nonNegativeInt(l.get("delivered"));
                if (key == null || key.isEmpty() || delivered < 0) {
                    throw new IllegalArgumentException("result.lines entries need key and delivered");
                }
                key = Http.truncate(key, MAX_KEY);
                JsonObject lo = new JsonObject();
                lo.addProperty("key", key);
                lo.addProperty("delivered", delivered);
                outLines.add(lo);
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("shulkers", shulkers);
        out.add("lines", outLines);
        return out;
    }
}
