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
        JsonElement ra = o.get("repairAll");
        if (ra != null && !ra.isJsonNull() && !(ra.isJsonPrimitive() && ra.getAsJsonPrimitive().isBoolean())) {
            Http.error(ex, 400, "repairAll must be true or false");
            return;
        }
        boolean repairAll = ra != null && ra.isJsonPrimitive() && ra.getAsBoolean();
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
                if (!"item".equals(type) && !"shulker".equals(type) && !"craft".equals(type) && !"kit".equals(type)) problem = "type must be item, shulker, craft or kit";
                else if ("kit".equals(type) && (key == null || !KitTemplate.ID.matcher(key).matches())) {
                    problem = "key of a kit line must be a kit id like t-3f2a9c1d";
                } else if ("kit".equals(type) && (count < 1 || count > KitOrders.MAX_PER_LINE)) {
                    problem = "count of a kit line must be a whole number from 1 to " + KitOrders.MAX_PER_LINE;
                } else if ("craft".equals(type) && (key == null || key.length() > MAX_KEY || !CraftStore.ITEM_ID.matcher(key).matches())) {
                    problem = "key of a craft line must be an item id like minecraft:stick";
                } else if (key == null || key.isEmpty() || key.length() > MAX_KEY) problem = "key must be a non-empty string";
                else if (count < 1) problem = "count must be a whole number from 1 to " + Integer.MAX_VALUE;
                else {
                    JsonElement r = l.get("repair");
                    if (r != null && !r.isJsonNull() && !(r.isJsonPrimitive() && r.getAsJsonPrimitive().isBoolean())) {
                        problem = "repair must be true or false";
                    } else {
                        line = new OrderStore.Line(type, key, count, r != null && r.isJsonPrimitive() && r.getAsBoolean());
                    }
                }
            }
            if (problem != null) {
                lineError(ex, 400, problem, i);
                return;
            }
            lines.add(line);
        }
        try {
            Users.Principal who = Router.user(ex);
            OrderStore.Order order = orders.place(server, stash, lines, names, repairAll, who.id(), who.name());
            JsonObject out = Http.ok();
            out.add("order", shown(ex, order.toJson()));
            Http.json(ex, 200, out);
        } catch (OrderStore.OrderException e) {
            lineError(ex, e);
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
    static JsonObject shown(HttpExchange ex, JsonObject order) {
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
            if (e.status == 400 || e.craft != null || e.kit != null) lineError(ex, e);
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
        // craftOrders >= 1: the bot carries out craft orders (CRAFT-CONTRACT.md). Absent or anything else: an older bot.
        boolean craftOrders = o.get("craftOrders") instanceof JsonPrimitive cp && cp.isNumber() && positiveInt(cp) >= 1;
        // kitOrders >= 1: the bot builds kits (KIT-CONTRACT-v2). Without it a held kit order fails and pending ones are skipped.
        boolean kitOrders = o.get("kitOrders") instanceof JsonPrimitive kp && kp.isNumber() && positiveInt(kp) >= 1;
        OrderStore.Order order = orders.claim(server, stash, craftOrders, kitOrders);
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
        lineError(ex, status, message, line, null);
    }

    /** The same; craft is the code of a 409 from the craft checks (CRAFT-CONTRACT.md, "Bot capability and routing"). */
    private static void lineError(HttpExchange ex, int status, String message, int line, String craft) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        o.addProperty("line", line >= 0 ? line : null);
        if (craft != null) o.addProperty("craft", craft);
        Http.json(ex, status, o);
    }

    /** The error of a refused order: line, the craft code (409), the kit code (409 no_bot / disabled, deleted) and the short items of a kit order. */
    private static void lineError(HttpExchange ex, OrderStore.OrderException e) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        String message = e.getMessage();
        // A kit name is text a user typed; a forced streamer's copy calls the (first) kit "Kit <n>", n being its place in the template list, like everywhere else.
        if (e.kitName != null && Redact.on(ex)) message = message.replace(e.kitName, e.kitNumber > 0 ? "Kit " + e.kitNumber : "Kit");
        o.addProperty("error", message);
        o.addProperty("line", e.line >= 0 ? e.line : null);
        if (e.craft != null) o.addProperty("craft", e.craft);
        if (e.kit != null) o.addProperty("kit", e.kit);
        if (e.shortItems != null) o.add("short", e.shortItems);
        Http.json(ex, e.status, o);
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
        JsonObject repair = repairResult(r.get("repair"));
        if (repair != null) out.add("repair", repair);
        JsonObject craft = craftResult(r.get("craft"));
        if (craft != null) out.add("craft", craft);
        JsonObject kit = kitResult(r.get("kit"));
        if (kit != null) out.add("kit", kit);
        return out;
    }

    private static final int MAX_KIT_LIST = 200;

    /**
     * result.kit (KIT-CONTRACT-v2, "Bot execution" 5): {made: [{template, name, wanted, ready, built, delivered}], short: [{template,
     * kits, items: [{key, match, count}], stored, reason}], notes: [text]}. Template ids and item keys are checked, names cleaned and
     * cut to 50, reasons must be the contract's, lists cut at 200. A wrong shape is an error. Null/absent = none.
     */
    static JsonObject kitResult(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonObject()) throw new IllegalArgumentException("result.kit must be an object or null");
        JsonObject r = e.getAsJsonObject();
        JsonObject out = new JsonObject();
        JsonArray made = new JsonArray();
        JsonElement me = r.get("made");
        if (me != null && !me.isJsonNull()) {
            if (!me.isJsonArray()) throw new IllegalArgumentException("result.kit.made must be an array");
            int n = 0;
            for (JsonElement x : me.getAsJsonArray()) {
                if (n++ >= MAX_KIT_LIST) break;
                if (!x.isJsonObject()) throw new IllegalArgumentException("result.kit.made entries must be objects");
                JsonObject in = x.getAsJsonObject();
                JsonObject o = new JsonObject();
                o.addProperty("template", kitTemplate(in, "result.kit.made"));
                String name = AddonRoutes.string(in, "name");
                o.addProperty("name", name == null ? "" : KitReports.clean(name, OrderStore.MAX_NAME));
                for (String num : new String[]{"wanted", "ready", "built", "delivered"}) {
                    long v = nonNegativeInt(in.get(num));
                    if (v < 0) throw new IllegalArgumentException("result.kit.made." + num + " must be a whole number >= 0");
                    o.addProperty(num, v);
                }
                made.add(o);
            }
        }
        out.add("made", made);
        JsonArray shorts = new JsonArray();
        JsonElement se = r.get("short");
        if (se != null && !se.isJsonNull()) {
            if (!se.isJsonArray()) throw new IllegalArgumentException("result.kit.short must be an array");
            int n = 0;
            for (JsonElement x : se.getAsJsonArray()) {
                if (n++ >= MAX_KIT_LIST) break;
                if (!x.isJsonObject()) throw new IllegalArgumentException("result.kit.short entries must be objects");
                JsonObject in = x.getAsJsonObject();
                JsonObject o = new JsonObject();
                o.addProperty("template", kitTemplate(in, "result.kit.short"));
                long kits = nonNegativeInt(in.get("kits"));
                if (kits < 0) throw new IllegalArgumentException("result.kit.short.kits must be a whole number >= 0");
                o.addProperty("kits", kits);
                String reason = AddonRoutes.string(in, "reason");
                if (reason == null || !KitOrders.SHORT_REASONS.contains(reason)) {
                    throw new IllegalArgumentException("result.kit.short.reason must be one of " + new java.util.TreeSet<>(KitOrders.SHORT_REASONS));
                }
                o.addProperty("reason", reason);
                if (in.has("stored") && !in.get("stored").isJsonNull()) {
                    String stored = AddonRoutes.string(in, "stored");
                    if (stored == null || !KitOrders.STORED.contains(stored)) throw new IllegalArgumentException("result.kit.short.stored must be incomplete or returned");
                    o.addProperty("stored", stored);
                }
                JsonArray items = new JsonArray();
                JsonElement ie = in.get("items");
                if (ie != null && !ie.isJsonNull()) {
                    if (!ie.isJsonArray()) throw new IllegalArgumentException("result.kit.short.items must be an array");
                    int k = 0;
                    for (JsonElement y : ie.getAsJsonArray()) {
                        if (k++ >= MAX_KIT_LIST) break;
                        if (!y.isJsonObject()) throw new IllegalArgumentException("result.kit.short.items entries must be objects");
                        JsonObject item = y.getAsJsonObject();
                        JsonObject io = new JsonObject();
                        String key = AddonRoutes.string(item, "key");
                        String match = item.has("match") && !item.get("match").isJsonNull() ? AddonRoutes.string(item, "match") : null;
                        if (key == null) key = match;
                        if (key == null || !KitTemplate.KEY.matcher(key).matches() || (match != null && !KitTemplate.KEY.matcher(match).matches())) {
                            throw new IllegalArgumentException("result.kit.short.items entries need an item key");
                        }
                        io.addProperty("key", key);
                        if (match != null) io.addProperty("match", match);
                        long c = nonNegativeInt(item.get("count"));
                        if (c < 0) throw new IllegalArgumentException("result.kit.short.items.count must be a whole number >= 0");
                        io.addProperty("count", c);
                        items.add(io);
                    }
                }
                o.add("items", items);
                shorts.add(o);
            }
        }
        out.add("short", shorts);
        JsonArray notes = new JsonArray();
        JsonElement ne = r.get("notes");
        if (ne != null && !ne.isJsonNull()) {
            if (!ne.isJsonArray()) throw new IllegalArgumentException("result.kit.notes must be an array");
            int n = 0;
            for (JsonElement x : ne.getAsJsonArray()) {
                if (n++ >= 20) break;
                if (!(x instanceof JsonPrimitive p) || !p.isString() || !p.getAsString().matches("^[a-z_]{1,32}$")) {
                    throw new IllegalArgumentException("result.kit.notes must be codes like colour_substituted");
                }
                notes.add(p.getAsString());
            }
        }
        out.add("notes", notes);
        return out;
    }

    private static String kitTemplate(JsonObject in, String where) {
        String id = AddonRoutes.string(in, "template");
        if (id == null || !KitTemplate.ID.matcher(id).matches()) throw new IllegalArgumentException(where + " entries need a template id like t-3f2a9c1d");
        return id;
    }

    private static final int MAX_CRAFT_LIST = 200;

    /**
     * result.craft (CRAFT-CONTRACT.md, "Result from the bot"): {fills, made: [{key, wanted, made}], short: [{key, count,
     * reason}], used: [{id, count}], returned: [{id, count}]}. Whole numbers >= 0, at most 200 entries per list (more are
     * dropped), keys cut to 256 characters. A wrong shape is an error. Null/absent = none.
     */
    static JsonObject craftResult(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonObject()) throw new IllegalArgumentException("result.craft must be an object or null");
        JsonObject r = e.getAsJsonObject();
        JsonObject out = new JsonObject();
        long fills = r.has("fills") && !r.get("fills").isJsonNull() ? nonNegativeInt(r.get("fills")) : 0;
        if (fills < 0) throw new IllegalArgumentException("result.craft.fills must be a whole number >= 0");
        out.addProperty("fills", fills);
        out.add("made", craftList(r.get("made"), "made", "key", new String[]{"wanted", "made"}, false));
        out.add("short", craftList(r.get("short"), "short", "key", new String[]{"count"}, true));
        out.add("used", craftList(r.get("used"), "used", "id", new String[]{"count"}, false));
        out.add("returned", craftList(r.get("returned"), "returned", "id", new String[]{"count"}, false));
        return out;
    }

    private static JsonArray craftList(JsonElement e, String name, String keyField, String[] numbers, boolean reason) {
        JsonArray out = new JsonArray();
        if (e == null || e.isJsonNull()) return out;
        if (!e.isJsonArray()) throw new IllegalArgumentException("result.craft." + name + " must be an array");
        int n = 0;
        for (JsonElement x : e.getAsJsonArray()) {
            if (n++ >= MAX_CRAFT_LIST) break;
            if (!x.isJsonObject()) throw new IllegalArgumentException("result.craft." + name + " entries must be objects");
            JsonObject in = x.getAsJsonObject();
            String key = AddonRoutes.string(in, keyField);
            if (key == null || key.isEmpty()) throw new IllegalArgumentException("result.craft." + name + " entries need " + keyField);
            JsonObject o = new JsonObject();
            o.addProperty(keyField, Http.truncate(key, MAX_KEY));
            for (String num : numbers) {
                long v = nonNegativeInt(in.get(num));
                if (v < 0) throw new IllegalArgumentException("result.craft." + name + "." + num + " must be a whole number >= 0");
                o.addProperty(num, v);
            }
            if (reason) {
                String why = AddonRoutes.string(in, "reason");
                if (why == null) throw new IllegalArgumentException("result.craft.short entries need a reason");
                o.addProperty("reason", Http.truncate(why, Repair.MAX_REASON));
            }
            out.add(o);
        }
        return out;
    }

    /**
     * result.repair (PROTOCOL.md, "Additions: repair with XP bottles"): {bottlesUsed, repaired, partial: [{key, damage,
     * maxDamage}], skipped: [{key, reason}]}. Null/absent = none. Entries past 400 are dropped, reasons cut to 60.
     */
    static JsonObject repairResult(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonObject()) throw new IllegalArgumentException("result.repair must be an object or null");
        JsonObject r = e.getAsJsonObject();
        long used = r.has("bottlesUsed") && !r.get("bottlesUsed").isJsonNull() ? nonNegativeInt(r.get("bottlesUsed")) : 0;
        long repaired = r.has("repaired") && !r.get("repaired").isJsonNull() ? nonNegativeInt(r.get("repaired")) : 0;
        if (used < 0 || repaired < 0) throw new IllegalArgumentException("result.repair.bottlesUsed and repaired must be whole numbers >= 0");
        JsonArray partial = new JsonArray();
        JsonElement pe = r.get("partial");
        if (pe != null && !pe.isJsonNull()) {
            if (!pe.isJsonArray()) throw new IllegalArgumentException("result.repair.partial must be an array");
            int n = 0;
            for (JsonElement x : pe.getAsJsonArray()) {
                if (n++ >= MAX_RESULT_LINES) break;
                if (!x.isJsonObject()) throw new IllegalArgumentException("result.repair.partial entries must be objects");
                JsonObject p = x.getAsJsonObject();
                String key = AddonRoutes.string(p, "key");
                long damage = nonNegativeInt(p.get("damage"));
                boolean hasMax = p.has("maxDamage") && !p.get("maxDamage").isJsonNull();
                long maxDamage = hasMax ? nonNegativeInt(p.get("maxDamage")) : 0;
                if (key == null || key.isEmpty() || damage < 0 || maxDamage < 0) {
                    throw new IllegalArgumentException("result.repair.partial entries need key, damage and maxDamage");
                }
                JsonObject po = new JsonObject();
                po.addProperty("key", Http.truncate(key, MAX_KEY));
                po.addProperty("damage", damage);
                if (hasMax) po.addProperty("maxDamage", maxDamage);
                partial.add(po);
            }
        }
        JsonArray skipped = new JsonArray();
        JsonElement se = r.get("skipped");
        if (se != null && !se.isJsonNull()) {
            if (!se.isJsonArray()) throw new IllegalArgumentException("result.repair.skipped must be an array");
            int n = 0;
            for (JsonElement x : se.getAsJsonArray()) {
                if (n++ >= MAX_RESULT_LINES) break;
                if (!x.isJsonObject()) throw new IllegalArgumentException("result.repair.skipped entries must be objects");
                JsonObject s = x.getAsJsonObject();
                String key = AddonRoutes.string(s, "key");
                String reason = AddonRoutes.string(s, "reason");
                if (key == null || key.isEmpty() || reason == null) {
                    throw new IllegalArgumentException("result.repair.skipped entries need key and reason");
                }
                JsonObject so = new JsonObject();
                so.addProperty("key", Http.truncate(key, MAX_KEY));
                so.addProperty("reason", Http.truncate(reason, Repair.MAX_REASON));
                skipped.add(so);
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("bottlesUsed", used);
        out.addProperty("repaired", repaired);
        out.add("partial", partial);
        out.add("skipped", skipped);
        return out;
    }
}
