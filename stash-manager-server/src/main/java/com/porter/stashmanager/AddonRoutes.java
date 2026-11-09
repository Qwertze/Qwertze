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
    private final DeliveryContents delivery;
    private final KitStore kits;       // null: this server has no kit support (the reply leaves the kit fields out)
    private final KitReports kitReports;

    AddonRoutes(IndexStore store, LinkState link, IconService icons, OrderStore orders, Owners owners) {
        this(store, link, icons, orders, owners, new DeliveryContents());
    }

    AddonRoutes(IndexStore store, LinkState link, IconService icons, OrderStore orders, Owners owners, DeliveryContents delivery) {
        this(store, link, icons, orders, owners, delivery, null, null);
    }

    AddonRoutes(IndexStore store, LinkState link, IconService icons, OrderStore orders, Owners owners, DeliveryContents delivery,
                KitStore kits, KitReports kitReports) {
        this.kits = kits;
        this.kitReports = kitReports;
        this.delivery = delivery;
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
        return readJson(ex, Http.MAX_BODY);
    }

    /** The same with a body limit (Http.TooLarge, answered 413, above it). */
    static JsonElement readJson(HttpExchange ex, long max) throws IOException {
        byte[] body = Http.readBody(ex, max);
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
        // PROTOCOL.md, "Additions: delivery storage contents": absent = an older bot, null = no delivery container.
        boolean hasDeliveryHash = o.has("deliveryHash");
        String deliveryHash = string(o, "deliveryHash");
        if (hasDeliveryHash && !o.get("deliveryHash").isJsonNull()
            && (deliveryHash == null || !DeliveryContents.HASH.matcher(deliveryHash).matches())) {
            Http.error(ex, 400, "deliveryHash must be 40 lowercase hex digits (sha1) or null");
            return;
        }
        // KIT-CONTRACT.md: kitsHash and kitsReportHash are a hash, null (the bot has none) or absent (an older bot).
        boolean hasKitsHash = o.has("kitsHash");
        String kitsHash = string(o, "kitsHash");
        String kitsReportHash = string(o, "kitsReportHash");
        if ((hasKitsHash && !o.get("kitsHash").isJsonNull() && (kitsHash == null || !KitReports.HASH.matcher(kitsHash).matches()))
            || (o.has("kitsReportHash") && !o.get("kitsReportHash").isJsonNull()
            && (kitsReportHash == null || !KitReports.HASH.matcher(kitsReportHash).matches()))) {
            Http.error(ex, 400, "kitsHash and kitsReportHash must be 40 lowercase hex digits or null");
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
        // Always sent, so a bot knows this server takes delivery reports (it only sends them once it has seen the key).
        if (hasDeliveryHash && deliveryHash == null) delivery.clear(server, stash); // the container was unset
        out.addProperty("needDelivery", delivery.needed(server, stash, deliveryHash));
        out.add("orders", orders.heartbeatJson(server, stash));
        if (result.rescan() != null) out.addProperty("rescan", result.rescan());
        if (kits != null && kitReports != null) kitFields(out, server, stash, hasKitsHash, kitsHash, kitsReportHash);
        Http.json(ex, 200, out);
    }

    /**
     * The kit part of the reply (KIT-CONTRACT.md, "Heartbeat"): kitsHash always, kits when the bot sent a different
     * kitsHash, needKitsReport always. When the settings file can't be read all three are left out.
     */
    private void kitFields(JsonObject out, String server, String stash, boolean hasKitsHash, String kitsHash, String kitsReportHash) {
        kitReports.beat(server, stash, hasKitsHash, kitsHash);
        KitStore.Settings s;
        try {
            s = kits.get(server, stash);
        } catch (KitStore.DisabledException e) {
            return;
        }
        out.addProperty("kitsHash", s.hash());
        if (hasKitsHash && !s.hash().equals(kitsHash)) out.add("kits", s.toJson());
        out.addProperty("needKitsReport", kitReports.needed(server, stash, kitsReportHash));
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
            String tableSource = null; // absent or null: an older bot that doesn't report it
            if (c.has("tableSource") && !c.get("tableSource").isJsonNull()) {
                JsonElement src = c.get("tableSource");
                tableSource = src.isJsonPrimitive() && src.getAsJsonPrimitive().isString() ? src.getAsString() : null;
                if (tableSource == null || !IndexStore.Crafting.TABLE_SOURCES.contains(tableSource)) {
                    throw new IllegalArgumentException("crafting.tableSource must be \"set\", \"found\", \"placed\", \"crafted\" or \"none\"");
                }
            }
            crafting = new IndexStore.Crafting(enabled.getAsBoolean(), table.getAsBoolean(), tableSource);
        }
        boolean hasStorage = o.has("deliveryStorage");
        IndexStore.DeliveryStorage storage = null;
        if (hasStorage && !o.get("deliveryStorage").isJsonNull()) {
            if (!o.get("deliveryStorage").isJsonObject()) {
                throw new IllegalArgumentException("deliveryStorage must be {autoExpand, chests, full, canExpand, blocked} or null");
            }
            JsonObject d = o.getAsJsonObject("deliveryStorage");
            JsonElement auto = d.get("autoExpand"), can = d.get("canExpand");
            if (auto == null || !auto.isJsonPrimitive() || !auto.getAsJsonPrimitive().isBoolean()
                || can == null || !can.isJsonPrimitive() || !can.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("deliveryStorage.autoExpand and canExpand must be true or false");
            }
            Integer chests = wholeInt(d.get("chests")), full = wholeInt(d.get("full"));
            if (chests == null || full == null || chests < 0 || full < 0 || chests > 100_000 || full > chests) {
                throw new IllegalArgumentException("deliveryStorage.chests and full must be whole numbers, 0 <= full <= chests");
            }
            String blocked = null;
            if (d.has("blocked") && !d.get("blocked").isJsonNull()) {
                blocked = string(d, "blocked");
                if (blocked == null) throw new IllegalArgumentException("deliveryStorage.blocked must be a string or null");
                blocked = blocked.isEmpty() ? null : Http.truncate(blocked, MAX_STATUS);
            }
            storage = new IndexStore.DeliveryStorage(auto.getAsBoolean(), chests, full, can.getAsBoolean(), blocked);
        }
        boolean hasRepair = o.has("repair");
        boolean repair = false;
        if (hasRepair && !o.get("repair").isJsonNull()) {
            JsonElement r = o.get("repair");
            if (!r.isJsonPrimitive() || !r.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("repair must be true or false");
            repair = r.getAsBoolean();
        }
        return new IndexStore.BotStatus(hasDelivery, delivery, hasBlocker, blocker, rescanDone, hasRescanBlocked, rescanBlocked,
            hasNaming, naming, hasCrafting, crafting, hasStorage, storage, hasRepair, repair);
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
