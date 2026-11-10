package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.TreeMap;

/**
 * "Force streamer mode" (PROTOCOL.md, "Additions: website users", the redaction table): what a sub-user whose perms
 * have streamer=true never receives. Applied by every route that returns positions, player names, server addresses or
 * free text from the bots, on the server: the page's blur is only a second layer. The owner is never forced.
 *
 * <p>Every method works on a JSON tree that belongs to this response (built fresh per request) or returns a new one;
 * shared state is never changed.
 */
final class Redact {
    static final String HIDDEN = "(hidden)";
    static final String ANOTHER_USER = "another user";
    /** Activity sources shown as they are (the plugin's own subsystems); any other source reads "(hidden)". */
    static final java.util.Set<String> SOURCES = java.util.Set.of("Stash sorter", "Stash order", "Pathfinder", "Pearl loader",
        "Queue", "Stash manager", "Dashboard", "Auto eat", "Auto totem", "Delivery");
    private static final java.util.regex.Pattern SAFE_KEY = java.util.regex.Pattern.compile("[a-z0-9_.:/-]+(#([0-9a-f]+|[*]))?");
    private static final SecureRandom RANDOM = new SecureRandom();
    /** The viewer offset's range per axis, in chunks (uniform in -2^20..2^20). */
    static final int OFFSET_CHUNKS = 1 << 20;

    private Redact() {
    }

    /** Whether this request's user is forced into streamer mode. */
    static boolean on(HttpExchange ex) {
        return forced(Router.user(ex));
    }

    static boolean forced(Users.Principal p) {
        return p != null && !p.owner() && p.perms().streamer();
    }

    /** Free text from a bot (it can hold positions or names): always "(hidden)" when present. */
    private static void scrubField(JsonObject o, String key) {
        if (o == null) return;
        JsonElement e = o.get(key);
        if (e != null && !e.isJsonNull()) o.addProperty(key, HIDDEN);
    }

    /** "minecraft:red_shulker_box" to "Red Shulker Box": a name that can't hold anything a player typed. */
    static String prettyId(String id) {
        if (id == null) return null;
        String path = id.substring(id.indexOf(':') + 1).split("#", 2)[0];
        StringBuilder sb = new StringBuilder();
        for (String w : path.split("[_/.]+")) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static JsonObject obj(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    // ---------- server labels ----------

    /** "Server 1", "Server 2", ... by sorted server address (of the stashes given: the visible ones). */
    static Map<String, String> serverLabels(Iterable<JsonElement> stashes) {
        TreeMap<String, String> labels = new TreeMap<>();
        for (JsonElement e : stashes) labels.put(e.getAsJsonObject().get("server").getAsString(), "");
        int i = 1;
        for (Map.Entry<String, String> en : labels.entrySet()) en.setValue("Server " + i++);
        return labels;
    }

    // ---------- GET /api/v1/stashes ----------

    /** One stash entry: no delivery position, no player or owner names, no position-like bot text. */
    static void stash(JsonObject o) {
        o.add("delivery", JsonNull.INSTANCE);
        o.add("player", JsonNull.INSTANCE);
        o.add("owner", JsonNull.INSTANCE);
        JsonObject c = obj(o, "conflict");
        if (c != null) c.add("player", JsonNull.INSTANCE);
        scrubField(o, "orderBlocker");
        scrubField(o, "rescanBlocked");
        scrubField(obj(o, "deliveryStorage"), "blocked");
        JsonObject orders = obj(o, "orders");
        if (orders != null) scrubField(obj(orders, "active"), "phase");
    }

    // ---------- GET /api/v1/index ----------

    /**
     * The index without the bot's player name and dimension, and without names players gave items: an item shows its
     * English name, details (lore, custom text) are dropped, a shulker shows its item's name.
     */
    static JsonObject index(byte[] json) {
        JsonObject o = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        o.remove("player");
        o.remove("dimension");
        JsonElement items = o.get("items");
        if (items != null && items.isJsonArray()) {
            for (JsonElement e : items.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject it = e.getAsJsonObject();
                String english = text(it, "englishName");
                it.addProperty("name", english != null ? english : prettyId(text(it, "id")));
                if (it.has("details")) it.add("details", new JsonArray());
            }
        }
        JsonElement shulkers = o.get("shulkers");
        if (shulkers != null && shulkers.isJsonArray()) {
            for (JsonElement e : shulkers.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject sh = e.getAsJsonObject();
                String item = text(sh, "itemKey");
                sh.addProperty("name", prettyId(item != null ? item : text(sh, "id")));
            }
        }
        return o;
    }

    // ---------- orders ----------

    /** An order: other users' names and shulker names hidden; the bot's phase and message text hidden. */
    static void order(JsonObject o, String selfId) {
        JsonObject by = obj(o, "placedBy");
        boolean mine = by != null && by.has("id") && !by.get("id").isJsonNull() && by.get("id").getAsString().equals(selfId);
        if (by != null && !mine) by.addProperty("name", ANOTHER_USER);
        if (!mine && o.has("names")) o.add("names", JsonNull.INSTANCE);
        scrubField(o, "phase");
        scrubField(o, "message");
        // result.repair is numbers and item keys; only a skipped item's reason is bot text, so an unknown one is hidden.
        JsonObject repair = obj(obj(o, "result"), "repair");
        JsonElement skipped = repair == null ? null : repair.get("skipped");
        if (skipped != null && skipped.isJsonArray()) {
            for (JsonElement s : skipped.getAsJsonArray()) {
                if (!s.isJsonObject()) continue;
                String reason = text(s.getAsJsonObject(), "reason");
                if (reason == null || !Repair.SKIP_REASONS.contains(reason)) s.getAsJsonObject().addProperty("reason", HIDDEN);
            }
        }
        craftResult(o);
        kitResult(o);
        kitNames(o);
        // Keys are item keys; anything else (free text) is hidden.
        for (String list : new String[]{"partial", "skipped"}) {
            JsonElement arr = repair == null ? null : repair.get(list);
            if (arr == null || !arr.isJsonArray()) continue;
            for (JsonElement s : arr.getAsJsonArray()) {
                if (!s.isJsonObject()) continue;
                String key = text(s.getAsJsonObject(), "key");
                if (key == null || !SAFE_KEY.matcher(key).matches()) s.getAsJsonObject().addProperty("key", HIDDEN);
            }
        }
    }

    /**
     * result.craft is numbers and item ids; only a short entry's reason is bot text, so one that is not a code of the
     * contract (CraftService.SHORT_REASONS) is hidden, and a key that is not an item key too.
     */
    static void craftResult(JsonObject order) {
        JsonObject craft = obj(obj(order, "result"), "craft");
        if (craft == null) return;
        JsonElement shorts = craft.get("short");
        if (shorts != null && shorts.isJsonArray()) {
            for (JsonElement s : shorts.getAsJsonArray()) {
                if (!s.isJsonObject()) continue;
                String reason = text(s.getAsJsonObject(), "reason");
                if (reason == null || !CraftService.SHORT_REASONS.contains(reason)) s.getAsJsonObject().addProperty("reason", HIDDEN);
            }
        }
        for (String list : new String[]{"made", "short", "used", "returned"}) {
            JsonElement arr = craft.get(list);
            if (arr == null || !arr.isJsonArray()) continue;
            for (JsonElement s : arr.getAsJsonArray()) {
                if (!s.isJsonObject()) continue;
                JsonObject so = s.getAsJsonObject();
                for (String field : new String[]{"key", "id"}) {
                    String v = text(so, field);
                    if (so.has(field) && (v == null || !SAFE_KEY.matcher(v).matches())) so.addProperty(field, HIDDEN);
                }
            }
        }
    }

    /**
     * The snapshot's kit names are text a user typed: "Kit 1", "Kit 2" ... (in order) instead, and a custom category id (a slug of the
     * category's name) becomes "custom".
     */
    interface KitNumbers {
        /** The template's 1-based position in the stash's current template list (the order the templates body lists them), 0 if it is gone. */
        int of(String server, String stash, String templateId);
    }

    /** Set when the server starts (StashManagerServer): where the kit numbers come from. */
    static volatile KitNumbers kitNumbers = (s, t, id) -> 0;

    static void kitNames(JsonObject order) {
        JsonObject kit = obj(order, "kit");
        JsonElement lines = kit == null ? null : kit.get("lines");
        if (lines == null || !lines.isJsonArray()) return;
        String server = text(order, "server"), stash = text(order, "stash");
        for (JsonElement l : lines.getAsJsonArray()) {
            JsonObject t = l.isJsonObject() ? obj(l.getAsJsonObject(), "template") : null;
            if (t == null) continue;
            String id = text(t, "id");
            int n = server == null || stash == null || id == null ? 0 : kitNumbers.of(server, stash, id);
            t.addProperty("name", n > 0 ? "Kit " + n : "Kit");
            String cat = text(t, "category");
            if (cat != null && cat.startsWith("c:")) t.addProperty("category", "custom");
        }
    }

    /**
     * result.kit is template ids, numbers and item keys, and the names the bot gave the kits (text): those are hidden, and
     * so is a key that is not an item key. Reasons are a closed set (checked when the bot reported them).
     */
    static void kitResult(JsonObject order) {
        JsonObject kit = obj(obj(order, "result"), "kit");
        if (kit == null) return;
        JsonElement made = kit.get("made");
        if (made != null && made.isJsonArray()) {
            for (JsonElement s : made.getAsJsonArray()) if (s.isJsonObject()) scrubField(s.getAsJsonObject(), "name");
        }
        JsonElement shorts = kit.get("short");
        if (shorts != null && shorts.isJsonArray()) {
            for (JsonElement s : shorts.getAsJsonArray()) {
                if (!s.isJsonObject()) continue;
                String reason = text(s.getAsJsonObject(), "reason");
                if (reason == null || !KitOrders.SHORT_REASONS.contains(reason)) s.getAsJsonObject().addProperty("reason", HIDDEN);
                JsonElement items = s.getAsJsonObject().get("items");
                if (items == null || !items.isJsonArray()) continue;
                for (JsonElement i : items.getAsJsonArray()) {
                    if (!i.isJsonObject()) continue;
                    for (String field : new String[]{"key", "match"}) {
                        String v = text(i.getAsJsonObject(), field);
                        if (i.getAsJsonObject().has(field) && (v == null || !SAFE_KEY.matcher(v).matches())) i.getAsJsonObject().addProperty(field, HIDDEN);
                    }
                }
            }
        }
    }

    /** GET /api/v1/orders: every order. */
    static void orders(JsonObject list, String selfId) {
        JsonElement e = list.get("orders");
        if (e == null || !e.isJsonArray()) return;
        for (JsonElement o : e.getAsJsonArray()) if (o.isJsonObject()) order(o.getAsJsonObject(), selfId);
    }

    // ---------- bots ----------

    /** A bot in the list: no player, no proxy details (server address, version), no "now doing" text. */
    static void bot(JsonObject o) {
        o.add("player", JsonNull.INSTANCE);
        o.add("proxy", new JsonObject());
        o.add("summary", JsonNull.INSTANCE);
        // Plugin details and the bot's settings scopes (plugin ids) are of no use to a forced user.
        o.add("plugin", new JsonObject());
        o.add("scopes", new com.google.gson.JsonArray());
    }

    /**
     * GET /api/v1/bots/{id}/activity: only structured states, kinds, levels and numbers. No positions, server,
     * dimension or player names; every free-text title, detail, status and event line is dropped.
     */
    static JsonObject activity(JsonObject in) {
        JsonObject out = new JsonObject();
        out.add("receivedAt", in.get("receivedAt"));
        out.add("online", in.get("online"));
        JsonElement ae = in.get("activity");
        if (ae == null || !ae.isJsonObject()) {
            out.add("activity", JsonNull.INSTANCE);
        } else {
            JsonObject a = ae.getAsJsonObject();
            JsonObject ra = new JsonObject();
            ra.add("at", a.get("at"));
            JsonObject c = obj(a, "connection");
            if (c != null) {
                JsonObject rc = new JsonObject();
                for (String f : new String[]{"state", "queuePosition", "health", "food", "ping", "uptimeSeconds", "spectators"}) {
                    if (c.has(f)) rc.add(f, c.get(f));
                }
                ra.add("connection", rc);
            }
            JsonArray current = new JsonArray();
            JsonElement ce = a.get("current");
            if (ce != null && ce.isJsonArray()) {
                for (JsonElement e : ce.getAsJsonArray()) {
                    JsonObject ci = e.getAsJsonObject();
                    JsonObject rc = new JsonObject();
                    for (String f : new String[]{"kind", "progress", "since", "level"}) if (ci.has(f)) rc.add(f, ci.get(f));
                    rc.addProperty("source", source(ci));
                    rc.addProperty("title", HIDDEN);
                    current.add(rc);
                }
            }
            ra.add("current", current);
            // Module and plugin names and versions: dropped (they say what the proxy runs).
            ra.add("modules", new JsonArray());
            ra.add("plugins", new JsonArray());
            out.add("activity", ra);
        }
        JsonArray events = new JsonArray();
        JsonElement ee = in.get("events");
        if (ee != null && ee.isJsonArray()) {
            for (JsonElement e : ee.getAsJsonArray()) {
                JsonObject v = e.getAsJsonObject();
                JsonObject rv = new JsonObject();
                rv.add("at", v.get("at"));
                rv.addProperty("source", source(v));
                rv.add("level", v.get("level"));
                rv.addProperty("text", HIDDEN);
                events.add(rv);
            }
        }
        out.add("events", events);
        return out;
    }

    /** A known activity source as it is, anything else "(hidden)". */
    private static String source(JsonObject o) {
        String s = text(o, "source");
        return s != null && SOURCES.contains(s) ? s : HIDDEN;
    }

    // ---------- link ----------

    /** GET /api/v1/link: no LAN addresses, no player names. */
    static void link(JsonObject o) {
        o.add("urls", new JsonArray());
        JsonObject l = obj(o, "linked");
        if (l != null) l.add("player", JsonNull.INSTANCE);
        JsonElement b = o.get("bots");
        if (b != null && b.isJsonArray()) for (JsonElement e : b.getAsJsonArray()) e.getAsJsonObject().add("player", JsonNull.INSTANCE);
    }

    // ---------- delivery contents ----------

    /** GET /api/v1/delivery: a copy without the bot's player name, and items named after their id (not as players named them). */
    static JsonElement delivery(JsonObject stored) {
        JsonObject o = stored.deepCopy();
        o.add("player", JsonNull.INSTANCE);
        JsonElement chests = o.get("chests");
        if (chests != null && chests.isJsonArray()) {
            for (JsonElement c : chests.getAsJsonArray()) {
                JsonElement items = c.getAsJsonObject().get("items");
                if (items == null || !items.isJsonArray()) continue;
                for (JsonElement it : items.getAsJsonArray()) {
                    JsonObject io = it.getAsJsonObject();
                    io.addProperty("name", prettyId(text(io, "id")));
                }
            }
        }
        return o;
    }

    // ---------- the live viewer ----------

    /**
     * Per-stream coordinate rebasing: every x/z the stream sends is shifted by the same secret amount, a whole number of
     * chunks (so tiles stay aligned): origin = the bot's chunk when the stream first sees it, plus a random shift of
     * up to 2^20 chunks per axis. y is kept. The true position can't be worked out from what the stream shows.
     */
    static final class ViewerMask {
        private boolean ready;
        private long dx; // chunks subtracted
        private long dz;

        /** Fixes the offset from the bot's position, once. */
        void anchor(JsonObject self) {
            if (ready || self == null || !self.has("x") || !self.has("z")) return;
            long cx = (long) Math.floor(self.get("x").getAsDouble() / 16);
            long cz = (long) Math.floor(self.get("z").getAsDouble() / 16);
            // Uniform in +-2^20 chunks per axis (no value is ruled out: that would tell something).
            dx = cx + RANDOM.nextInt(2 * OFFSET_CHUNKS + 1) - OFFSET_CHUNKS;
            dz = cz + RANDOM.nextInt(2 * OFFSET_CHUNKS + 1) - OFFSET_CHUNKS;
            ready = true;
        }

        boolean ready() {
            return ready;
        }

        private double bx(double x) {
            return Math.round((x - dx * 16) * 10) / 10.0;
        }

        private double bz(double z) {
            return Math.round((z - dz * 16) * 10) / 10.0;
        }

        private JsonElement pos(JsonElement e) {
            if (e == null || !e.isJsonObject()) return e == null ? JsonNull.INSTANCE : e;
            JsonObject in = e.getAsJsonObject();
            JsonObject o = in.deepCopy();
            if (in.has("x")) o.addProperty("x", bx(in.get("x").getAsDouble()));
            if (in.has("z")) o.addProperty("z", bz(in.get("z").getAsDouble()));
            o.remove("n"); // player names
            return o;
        }

        private JsonArray ents(JsonElement e) {
            JsonArray out = new JsonArray();
            if (e != null && e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) out.add(pos(x));
            return out;
        }

        private JsonArray tiles(JsonElement e) {
            JsonArray out = new JsonArray();
            if (e == null || !e.isJsonArray()) return out;
            for (JsonElement x : e.getAsJsonArray()) {
                JsonObject t = x.getAsJsonObject();
                JsonObject o = new JsonObject();
                o.addProperty("x", t.get("x").getAsLong() - dx);
                o.addProperty("z", t.get("z").getAsLong() - dz);
                o.add("c", t.get("c"));
                o.add("h", t.get("h"));
                out.add(o);
            }
            return out;
        }

        private JsonArray drops(JsonElement e) {
            JsonArray out = new JsonArray();
            if (e == null || !e.isJsonArray()) return out;
            for (JsonElement x : e.getAsJsonArray()) {
                JsonArray p = x.getAsJsonArray();
                JsonArray o = new JsonArray();
                o.add(p.get(0).getAsLong() - dx);
                o.add(p.get(1).getAsLong() - dz);
                out.add(o);
            }
            return out;
        }

        /**
         * The event as a forced user gets it, or null. Default deny: only the event types listed here pass (chat never;
         * position data not before the offset is known).
         */
        ViewerHub.Ev apply(ViewerHub.Ev ev) {
            if (!(ev.data() instanceof JsonObject d)) return null;
            switch (ev.name()) {
                case "hud":
                    return ev; // health, food, XP, items: nothing positional
                case "snapshot": {
                    anchor(d.has("self") && d.get("self").isJsonObject() ? d.getAsJsonObject("self") : null);
                    JsonObject o = new JsonObject();
                    for (String f : new String[]{"session", "seq", "t", "rt", "st", "hud"}) if (d.has(f)) o.add(f, d.get(f));
                    JsonObject world = d.has("world") && d.get("world").isJsonObject() ? d.getAsJsonObject("world").deepCopy() : null;
                    if (world != null) {
                        // minY and height would tell the dimension apart (-64/384 is the overworld): fixed values.
                        world.addProperty("dim", "hidden");
                        world.addProperty("minY", -64);
                        world.addProperty("height", 384);
                    }
                    o.add("world", world == null ? JsonNull.INSTANCE : world);
                    if (!ready) { // no position to rebase from yet: nothing positional at all
                        o.add("self", JsonNull.INSTANCE);
                        o.add("tiles", new JsonArray());
                        o.add("ents", new JsonArray());
                    } else {
                        o.add("self", pos(d.get("self")));
                        o.add("tiles", tiles(d.get("tiles")));
                        o.add("ents", ents(d.get("ents")));
                    }
                    o.add("chat", new JsonArray());
                    o.addProperty("relative", true);
                    return new ViewerHub.Ev(ev.name(), ev.id(), o);
                }
                case "tiles": {
                    if (!ready) return null;
                    JsonObject o = new JsonObject();
                    for (String f : new String[]{"seq", "t", "rt", "st"}) if (d.has(f)) o.add(f, d.get(f));
                    o.add("tiles", tiles(d.get("tiles")));
                    o.add("drop", drops(d.get("drop")));
                    return new ViewerHub.Ev(ev.name(), ev.id(), o);
                }
                case "ents": {
                    anchor(d.has("self") && d.get("self").isJsonObject() ? d.getAsJsonObject("self") : null);
                    if (!ready) return null;
                    JsonObject o = new JsonObject();
                    for (String f : new String[]{"seq", "t", "rt", "st"}) if (d.has(f)) o.add(f, d.get(f));
                    o.add("self", pos(d.get("self")));
                    o.add("ents", ents(d.get("ents")));
                    return new ViewerHub.Ev(ev.name(), ev.id(), o);
                }
                default:
                    return null; // chat, and anything new until it is reviewed here
            }
        }
    }
}
