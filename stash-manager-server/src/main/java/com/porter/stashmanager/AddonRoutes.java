package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** The endpoints the addon calls with its bearer key: POST /api/v1/index and /api/v1/heartbeat. */
final class AddonRoutes {
    private static final int MAX_FIELD = 256;
    private static final int MAX_STATUS = 100;

    private final IndexStore store;
    private final LinkState link;
    private final IconService icons;
    private final OrderStore orders;
    private final Owners owners;

    AddonRoutes(IndexStore store, LinkState link, IconService icons, OrderStore orders, Owners owners) {
        this.orders = orders;
        this.owners = owners;
        this.icons = icons;
        this.store = store;
        this.link = link;
    }

    void register(Router router) {
        router.exact(Router.POST, "/api/v1/index", Router.Guard.ADDON_KEY, this::postIndex)
            .exact(Router.POST, "/api/v1/heartbeat", Router.Guard.ADDON_KEY, this::heartbeat);
    }

    /** Strict UTF-8 JSON body, or null when it doesn't parse. */
    static JsonElement readJson(HttpExchange ex) throws IOException {
        byte[] body = Http.readBody(ex, Http.MAX_BODY);
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString();
            return JsonParser.parseString(text);
        } catch (CharacterCodingException | JsonParseException | StackOverflowError e) {
            return null;
        }
    }

    private void postIndex(HttpExchange ex) throws IOException {
        JsonElement json = readJson(ex);
        if (json == null) {
            Http.error(ex, 400, "body is not valid JSON");
            return;
        }
        String error = IndexStore.validate(json);
        if (error == null) error = checkLengths(json.getAsJsonObject());
        if (error != null) {
            Http.error(ex, 400, error);
            return;
        }
        JsonObject index = json.getAsJsonObject();
        String server = index.get("server").getAsString();
        String stash = index.get("stash").getAsString();
        String player = string(index, "player");
        String owner = owners.check(server, stash, player, true);
        if (owner != null) {
            conflict(ex, owner);
            return;
        }
        try {
            store.putIndex(index);
        } catch (IndexStore.FullException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        link.seen(generation(ex), server, stash, player);
        // Start fetching icons for new item ids now, so they are usually cached before the page asks.
        icons.prefetch(store.iconIds(server, stash));
        Http.json(ex, 200, Http.ok());
    }

    private void heartbeat(HttpExchange ex) throws IOException {
        JsonElement json = readJson(ex);
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return;
        }
        JsonObject o = json.getAsJsonObject();
        String server = string(o, "server");
        String stash = string(o, "stash");
        if (server == null || server.isEmpty() || stash == null || stash.isEmpty()) {
            Http.error(ex, 400, "server and stash must be non-empty strings");
            return;
        }
        if (badOptionalString(o, "player") || badOptionalString(o, "indexHash")) {
            Http.error(ex, 400, "player and indexHash must be strings");
            return;
        }
        String error = checkLengths(o);
        if (error != null) {
            Http.error(ex, 400, error);
            return;
        }
        IndexStore.BotStatus status;
        try {
            status = botStatus(o);
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        String owner = owners.check(server, stash, string(o, "player"), true);
        if (owner != null) {
            conflict(ex, owner);
            return;
        }
        IndexStore.HeartbeatResult result;
        try {
            result = store.heartbeat(server, stash, string(o, "player"), string(o, "indexHash"), status);
        } catch (IndexStore.FullException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        link.seen(generation(ex), server, stash, string(o, "player"));
        JsonObject out = Http.ok();
        out.addProperty("needIndex", result.needIndex());
        out.add("orders", orders.heartbeatJson(server, stash));
        if (result.rescan() != null) out.addProperty("rescan", result.rescan());
        Http.json(ex, 200, out);
    }

    /**
     * The optional delivery / orderBlocker fields (PROTOCOL.md, "Additions: delivery status and order size") and
     * naming (PROTOCOL.md, "Additions: shulker names") and crafting (PROTOCOL.md, "Additions: crafting").
     * A field that is absent leaves the stored value alone; an explicit null clears it.
     */
    static IndexStore.BotStatus botStatus(JsonObject o) {
        boolean hasDelivery = o.has("delivery");
        IndexStore.Delivery delivery = null;
        if (hasDelivery && !o.get("delivery").isJsonNull()) {
            if (!o.get("delivery").isJsonObject()) throw new IllegalArgumentException("delivery must be {x, y, z, dimension} or null");
            JsonObject d = o.getAsJsonObject("delivery");
            Integer x = wholeInt(d.get("x")), y = wholeInt(d.get("y")), z = wholeInt(d.get("z"));
            String dim = string(d, "dimension");
            if (x == null || y == null || z == null) throw new IllegalArgumentException("delivery.x, y and z must be whole numbers");
            if (dim == null) throw new IllegalArgumentException("delivery.dimension must be a string");
            delivery = new IndexStore.Delivery(x, y, z, Http.truncate(dim, MAX_STATUS));
        }
        boolean hasBlocker = o.has("orderBlocker");
        String blocker = null;
        if (hasBlocker && !o.get("orderBlocker").isJsonNull()) {
            blocker = string(o, "orderBlocker");
            if (blocker == null) throw new IllegalArgumentException("orderBlocker must be a string or null");
            blocker = blocker.isEmpty() ? null : Http.truncate(blocker, MAX_STATUS);
        }
        long rescanDone = -1;
        if (o.has("rescanDone") && !o.get("rescanDone").isJsonNull()) {
            JsonElement e = o.get("rescanDone");
            try {
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
                rescanDone = e.getAsBigDecimal().longValueExact();
                if (rescanDone < 0) throw new ArithmeticException();
            } catch (ArithmeticException | NumberFormatException ex) {
                throw new IllegalArgumentException("rescanDone must be a whole number >= 0, or null");
            }
        }
        boolean hasRescanBlocked = o.has("rescanBlocked");
        String rescanBlocked = null;
        if (hasRescanBlocked && !o.get("rescanBlocked").isJsonNull()) {
            rescanBlocked = string(o, "rescanBlocked");
            if (rescanBlocked == null) throw new IllegalArgumentException("rescanBlocked must be a string or null");
            rescanBlocked = rescanBlocked.isEmpty() ? null : Http.truncate(rescanBlocked, MAX_STATUS);
        }
        boolean hasNaming = o.has("naming");
        IndexStore.Naming naming = null;
        if (hasNaming && !o.get("naming").isJsonNull()) {
            if (!o.get("naming").isJsonObject()) throw new IllegalArgumentException("naming must be {anvil, xpLevels} or null");
            JsonObject n = o.getAsJsonObject("naming");
            JsonElement anvil = n.get("anvil");
            if (anvil == null || !anvil.isJsonPrimitive() || !anvil.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("naming.anvil must be true or false");
            }
            int xp = -1;
            if (n.has("xpLevels") && !n.get("xpLevels").isJsonNull()) {
                Integer x = wholeInt(n.get("xpLevels"));
                if (x == null || x < -1) throw new IllegalArgumentException("naming.xpLevels must be a whole number >= -1");
                xp = x;
            }
            String anvilSource = null; // absent or null: an older bot that doesn't report it
            if (n.has("anvilSource") && !n.get("anvilSource").isJsonNull()) {
                JsonElement src = n.get("anvilSource");
                anvilSource = src.isJsonPrimitive() && src.getAsJsonPrimitive().isString() ? src.getAsString() : null;
                if (anvilSource == null || !IndexStore.Naming.ANVIL_SOURCES.contains(anvilSource)) {
                    throw new IllegalArgumentException("naming.anvilSource must be \"set\", \"found\", \"placed\" or \"none\"");
                }
            }
            naming = new IndexStore.Naming(anvil.getAsBoolean(), xp, anvilSource);
        }
        boolean hasCrafting = o.has("crafting");
        IndexStore.Crafting crafting = null;
        if (hasCrafting && !o.get("crafting").isJsonNull()) {
            if (!o.get("crafting").isJsonObject()) throw new IllegalArgumentException("crafting must be {enabled, table} or null");
            JsonObject c = o.getAsJsonObject("crafting");
            JsonElement enabled = c.get("enabled"), table = c.get("table");
            if (enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("crafting.enabled must be true or false");
            }
            if (table == null || !table.isJsonPrimitive() || !table.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("crafting.table must be true or false");
            }
            crafting = new IndexStore.Crafting(enabled.getAsBoolean(), table.getAsBoolean());
        }
        return new IndexStore.BotStatus(hasDelivery, delivery, hasBlocker, blocker, rescanDone, hasRescanBlocked, rescanBlocked,
            hasNaming, naming, hasCrafting, crafting);
    }

    /** A whole number in int range (1.0 is fine, 1.5 isn't), or null. */
    private static Integer wholeInt(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        try {
            return e.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }

    /** 409 for a player using a stash another bot owns (PROTOCOL.md, "multiple bots, stash ownership"). */
    static void conflict(HttpExchange ex, String owner) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", "stash name in use");
        o.addProperty("owner", owner);
        Http.json(ex, 409, o);
    }

    private static long generation(HttpExchange ex) {
        return Http.attr(ex, Router.KEY_GENERATION) instanceof Long g ? g : -1;
    }

    private static boolean badOptionalString(JsonObject o, String field) {
        return o.has(field) && !o.get(field).isJsonNull() && string(o, field) == null;
    }

    /** server/stash/player are shown in the page and used as map keys, so keep them short. */
    private static String checkLengths(JsonObject o) {
        for (String field : new String[]{"server", "stash", "player", "indexHash"}) {
            String v = string(o, field);
            if (v != null && v.length() > MAX_FIELD) return field + " is too long";
        }
        return null;
    }

    static String string(JsonObject o, String field) {
        JsonElement e = o.get(field);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }
}
