package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
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
    private volatile PearlEngine pearl; // null: this server has no pearl travel
    private volatile RepairStore repairStore; // null: this server has no repair passes (the reply leaves the repair fields out)
    private volatile RepairBots repairBots;
    private final CraftService craft;  // null: this server has no crafting support (the reply leaves the craft fields out)
    private final AreaStore areas;     // null: no stash area support (the reply leaves areaHash out)
    private final AreaBots areaBots;
    private final AuditLog audit;
    private final AreaGuard areaGuard;

    AddonRoutes(IndexStore store, LinkState link, IconService icons, OrderStore orders, Owners owners) {
        this(store, link, icons, orders, owners, new DeliveryContents());
    }

    AddonRoutes(IndexStore store, LinkState link, IconService icons, OrderStore orders, Owners owners, DeliveryContents delivery) {
        this(store, link, icons, orders, owners, delivery, null, null);
    }

    AddonRoutes(IndexStore store, LinkState link, IconService icons, OrderStore orders, Owners owners, DeliveryContents delivery,
                KitStore kits, KitReports kitReports) {
        this(store, link, icons, orders, owners, delivery, kits, kitReports, null);
    }

    AddonRoutes(IndexStore store, LinkState link, IconService icons, OrderStore orders, Owners owners, DeliveryContents delivery,
                KitStore kits, KitReports kitReports, CraftService craft) {
        this(store, link, icons, orders, owners, delivery, kits, kitReports, craft, null, null, null);
    }

    AddonRoutes(IndexStore store, LinkState link, IconService icons, OrderStore orders, Owners owners, DeliveryContents delivery,
                KitStore kits, KitReports kitReports, CraftService craft, AreaStore areas, AreaBots areaBots, AuditLog audit) {
        this(store, link, icons, orders, owners, delivery, kits, kitReports, craft, areas, areaBots, audit, null);
    }

    AddonRoutes(IndexStore store, LinkState link, IconService icons, OrderStore orders, Owners owners, DeliveryContents delivery,
                KitStore kits, KitReports kitReports, CraftService craft, AreaStore areas, AreaBots areaBots, AuditLog audit, AreaGuard areaGuard) {
        this.areaGuard = areaGuard;
        this.areas = areas;
        this.areaBots = areaBots;
        this.audit = audit;
        this.craft = craft;
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
        router.exact(Router.POST, "/api/v1/index", Router.Guard.ADDON_KEY_LEGACY, this::postIndex)
            .exact(Router.POST, "/api/v1/heartbeat", Router.Guard.ADDON_KEY_LEGACY, this::heartbeat);
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
        // ENCHANT-CONTRACT v1.0: an index that carries ench, rc or named must come from the stash owner (an index value is a claim the
        // planner builds orders on). The fields themselves are checked when the index is prepared (a bad one is dropped, the entry stays).
        if (EnchantIndex.carries(index)) {
            if (player == null || player.isEmpty()) {
                Http.error(ex, 400, "player is required when an index carries ench, rc or named");
                return;
            }
            String own = owners.owner(server, stash);
            if (own == null || !own.equalsIgnoreCase(player)) {
                conflict(ex, own == null ? "" : own);
                return;
            }
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
        // KIT-CONTRACT-v2: kitsHash2 is the cached v2 hash, null (no cache, or none with types) or absent (a v1 bot).
        boolean hasKitsHash2 = o.has("kitsHash2");
        String kitsHash2 = string(o, "kitsHash2");
        if (hasKitsHash2 && !o.get("kitsHash2").isJsonNull() && (kitsHash2 == null || !KitReports.HASH.matcher(kitsHash2).matches())) {
            Http.error(ex, 400, "kitsHash2 must be 40 lowercase hex digits or null");
            return;
        }
        // kitOrders: an object (the bot can carry out kit orders), null (it can't) or absent (an older bot).
        boolean hasKitOrders = o.has("kitOrders");
        KitReports.KitCap kitCap = null;
        if (hasKitOrders && !o.get("kitOrders").isJsonNull()) {
            if (!o.get("kitOrders").isJsonObject()) {
                Http.error(ex, 400, "kitOrders must be an object or null");
                return;
            }
            try {
                kitCap = kitCap(o.getAsJsonObject("kitOrders"));
            } catch (IllegalArgumentException e) {
                Http.error(ex, 400, e.getMessage());
                return;
            }
        }
        if (hasKitOrders && (string(o, "player") == null || string(o, "player").isEmpty())) {
            Http.error(ex, 400, "player is required when kitOrders is sent");
            return;
        }
        // REPAIR-CONTRACT v2.0: repairHash is the cached settings hash (null: none; absent: a bot without repair passes);
        // repairPass is the pass status (an object), null (cleared) or absent (kept).
        boolean hasRepairHash = o.has("repairHash");
        String repairHash = string(o, "repairHash");
        if (hasRepairHash && !o.get("repairHash").isJsonNull() && (repairHash == null || !KitReports.HASH.matcher(repairHash).matches())) {
            Http.error(ex, 400, "repairHash must be 40 lowercase hex digits or null");
            return;
        }
        boolean hasRepairPass = o.has("repairPass");
        JsonObject repairPass = null;
        if (hasRepairPass && !o.get("repairPass").isJsonNull()) {
            if (!o.get("repairPass").isJsonObject()) {
                Http.error(ex, 400, "repairPass must be an object or null");
                return;
            }
            try {
                repairPass = RepairBots.parsePass(o.getAsJsonObject("repairPass"));
            } catch (IllegalArgumentException e) {
                Http.error(ex, 400, e.getMessage());
                return;
            }
        }
        if ((hasRepairHash || hasRepairPass) && (string(o, "player") == null || string(o, "player").isEmpty())) {
            Http.error(ex, 400, "player is required when repairHash or repairPass is sent");
            return;
        }
        // PEARL-CONTRACT v1.0: pearlTravel (a traveller's state) and pearlLoader (a loader's report): an object, null or absent. Both need
        // the player; a loader report must be the heartbeat's own player's.
        boolean hasPearlTravel = o.has("pearlTravel");
        JsonObject pearlTravel = null;
        boolean hasPearlLoader = o.has("pearlLoader");
        JsonObject pearlLoader = null;
        if (hasPearlTravel || hasPearlLoader) {
            String who = string(o, "player");
            if (who == null || who.isEmpty()) {
                Http.error(ex, 400, "player is required when pearlTravel or pearlLoader is sent");
                return;
            }
            if (!PearlBodies.PLAYER.matcher(who).matches()) {
                Http.error(ex, 400, "player must be a player name when pearlTravel or pearlLoader is sent");
                return;
            }
            try {
                if (hasPearlTravel && !o.get("pearlTravel").isJsonNull()) {
                    if (!o.get("pearlTravel").isJsonObject()) throw new IllegalArgumentException("pearlTravel must be an object or null");
                    pearlTravel = o.getAsJsonObject("pearlTravel");
                    PearlBodies.travel(pearlTravel);
                }
                if (hasPearlLoader && !o.get("pearlLoader").isJsonNull()) {
                    if (!o.get("pearlLoader").isJsonObject()) throw new IllegalArgumentException("pearlLoader must be an object or null");
                    pearlLoader = o.getAsJsonObject("pearlLoader");
                    JsonObject clean = PearlBodies.loader(pearlLoader);
                    if (!clean.get("player").getAsString().equalsIgnoreCase(who)) {
                        Http.error(ex, 409, "the report's player isn't this bot's player");
                        return;
                    }
                }
            } catch (IllegalArgumentException e) {
                Http.error(ex, 400, e.getMessage());
                return;
            }
        }
        // CRAFT-CONTRACT.md: craftOrders is an object (the bot can carry out craft orders), null (it can't any more) or absent
        // (an older bot); craftHash is the hash of the craft settings it has cached, null (none) or absent.
        // AREA-CONTRACT.md: area is the bot's report of its stash area (object), absent for an older bot. player is required.
        boolean hasArea = o.has("area");
        AreaBots.Report areaReport = null;
        if (hasArea) {
            if (string(o, "player") == null || string(o, "player").isEmpty()) {
                Http.error(ex, 400, "player is required when area is sent");
                return;
            }
            if (!o.get("area").isJsonObject()) {
                Http.error(ex, 400, "area must be an object");
                return;
            }
            if (areaBots != null) {
                try {
                    areaReport = areaBots.parse(o.getAsJsonObject("area"));
                } catch (IllegalArgumentException e) {
                    Http.error(ex, 400, e.getMessage());
                    return;
                }
            }
        }
        // ENCHANT-CONTRACT v1.0: enchant is {v: 1, enabled, mc, carry?}, null (cleared) or absent (kept). It needs the player, who is the
        // one the claim and the owner checks compare with; memory only.
        boolean hasEnchant = o.has("enchant");
        EnchantBots.Cap enchantCap = null;
        if (hasEnchant) {
            String who = string(o, "player");
            if (who == null || who.isEmpty()) {
                Http.error(ex, 400, "player is required when enchant is sent");
                return;
            }
            if (!PearlBodies.PLAYER.matcher(who).matches()) {
                Http.error(ex, 400, "player must be a player name when enchant is sent");
                return;
            }
            if (!o.get("enchant").isJsonNull()) {
                if (!o.get("enchant").isJsonObject()) {
                    Http.error(ex, 400, "enchant must be an object or null");
                    return;
                }
                try {
                    enchantCap = EnchantBots.parse(o.getAsJsonObject("enchant"));
                } catch (IllegalArgumentException e) {
                    Http.error(ex, 400, e.getMessage());
                    return;
                }
            }
        }
        boolean hasCraftOrders = o.has("craftOrders");
        if ((hasCraftOrders || o.has("craftHash")) && (string(o, "player") == null || string(o, "player").isEmpty())) {
            Http.error(ex, 400, "player is required when craftOrders or craftHash is sent");
            return;
        }
        CraftBots.Capability craftCap = null;
        if (hasCraftOrders && !o.get("craftOrders").isJsonNull()) {
            if (!o.get("craftOrders").isJsonObject()) {
                Http.error(ex, 400, "craftOrders must be an object or null");
                return;
            }
            try {
                craftCap = CraftBots.parse(o.getAsJsonObject("craftOrders"));
            } catch (IllegalArgumentException e) {
                Http.error(ex, 400, e.getMessage());
                return;
            }
        }
        boolean hasCraftHash = o.has("craftHash");
        String craftHash = string(o, "craftHash");
        if (hasCraftHash && !o.get("craftHash").isJsonNull() && (craftHash == null || !KitReports.HASH.matcher(craftHash).matches())) {
            Http.error(ex, 400, "craftHash must be 40 lowercase hex digits or null");
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
        if (craft != null) craft.bots().beat(server, stash, hasCraftOrders, craftCap, hasCraftHash, craftHash);
        EnchantOrders eo = enchant;
        if (eo != null) eo.bots().beat(server, stash, hasEnchant, enchantCap, string(o, "player")); // before the reply: the pending count depends on it
        if (kits != null && kitReports != null) {
            // craft is true only when the bot also carries out craft orders (this heartbeat's craftOrders, else the stored one).
            if (kitCap != null && kitCap.craft()) {
                boolean craftOn = hasCraftOrders ? craftCap != null && craftCap.enabled() : craft != null && craft.bots().capable(server, stash);
                if (!craftOn) kitCap = new KitReports.KitCap(kitCap.v(), kitCap.enabled(), kitCap.restock(), false, kitCap.importer());
            }
            // Stored before the reply is made: the pending count below depends on this heartbeat's kitOrders.
            kitReports.beat(server, stash, hasKitsHash, kitsHash, hasKitsHash2, kitsHash2, hasKitOrders, kitCap, o.has("kitsReportHash"), kitsReportHash);
        }
        if (repairBots != null) repairBots.beat(server, stash, hasRepairHash, repairHash, hasRepairPass, repairPass); // before the reply: it reads the bot's acknowledgements
        String enchantMc = eo == null ? null : eo.supportedMc(server, stash);
        boolean enchantCapable = eo != null && enchantMc != null && eo.botReady(server, stash, enchantMc);
        out.add("orders", orders.heartbeatJson(server, stash, craft == null || craft.bots().capable(server, stash),
            kitReports == null || kitReports.kitCapable(server, stash), enchantCapable));
        if (result.rescan() != null) out.addProperty("rescan", result.rescan());
        if (kits != null && kitReports != null) kitFields(out, server, stash, hasKitsHash, kitsHash, kitsReportHash, hasKitsHash2, kitsHash2);
        if (repairBots != null) {
            RepairBots.Beat rbeat = repairBots.beat(server, stash);
            repairFields(out, server, stash, hasRepairHash, repairHash, rbeat == null ? null : rbeat.pass());
        }
        // TL4-B1: to an enchant-capable or repair-capable bot, the bottles every open enchant order of the stash reserves; the bot passes
        // them as heldOrderBottles to RepairRules.usable, so repair can't spend them.
        if (eo != null) {
            boolean repairCapable = repairBots != null && repairBots.beat(server, stash) != null && repairBots.beat(server, stash).supports();
            if (eo.bots().known(server, stash) || repairCapable) {
                JsonObject en = new JsonObject();
                en.addProperty("bottlesReserved", orders.enchantBottlesReserved(server, stash));
                out.add("enchant", en);
            }
        }
        if (pearl != null && !pearl.disabled() && (hasPearlTravel || hasPearlLoader || pearl.knows(server, stash))) {
            try {
                if (pearlLoader != null) {
                    JsonObject lr = pearl.onLoader(string(o, "player"), "hb:" + server + "/" + stash, pearlLoader);
                    JsonObject reply = new JsonObject();
                    reply.add("allow", lr.get("allow"));
                    reply.add("intervalMs", lr.get("intervalMs"));
                    out.add("pearlLoader", reply);
                }
                int pending = out.get("orders") instanceof JsonObject oj && oj.get("pending") != null ? oj.get("pending").getAsInt() : 0;
                JsonObject r = pearl.onTraveller(server, stash, string(o, "player"), hasPearlTravel, pearlTravel, pending);
                r.entrySet().forEach(e -> out.add(e.getKey(), e.getValue()));
            } catch (PearlEngine.Refused e) {
                Http.error(ex, e.status, e.getMessage());
                return;
            }
            if (audit != null) for (String[] a : pearl.drainAudits()) audit.add(null, "bot " + string(o, "player"), a[0], a[1], AuthRoutes.address(ex));
        }
        if (craft != null) craftFields(out, server, stash, hasCraftHash, craftHash);
        if (areas != null && areaBots != null) areaFields(ex, out, server, stash, string(o, "player"), hasArea, areaReport);
        // TASK-CONTRACT "Heartbeat reply": { stop, rev }. Everything-stop only: a heartbeat names no bot id, so a per-bot STOP reaches
        // a bot through tasks/sync. Older bots ignore the field.
        if (opsField != null) out.add("ops", opsField.get());
        Http.json(ex, 200, out);
    }

    private java.util.function.Supplier<JsonObject> opsField;

    /** Gives the heartbeat the "ops" field (the global STOP and its counter). */
    void ops(java.util.function.Supplier<JsonObject> ops) {
        this.opsField = ops;
    }

    /** Gives the heartbeat pearl travel (set once at start). */
    void pearl(PearlEngine engine) {
        this.pearl = engine;
    }

    private volatile EnchantOrders enchant; // null: this server has no enchant orders (no enchant fields in the reply)

    /** Gives the heartbeat the enchant capability store (set once at start). */
    void enchant(EnchantOrders enchant) {
        this.enchant = enchant;
    }

    /** Gives the heartbeat the repair passes (set once at start). */
    void repair(RepairStore store, RepairBots bots) {
        this.repairStore = store;
        this.repairBots = bots;
    }

    /**
     * The repair part of the reply (REPAIR-CONTRACT v2.0, "Heartbeat"): repairHash always, repairSettings when the body had a
     * repairHash that differs (null counts as different), repairRun / repairStop while a website request is newer than what the bot
     * acknowledged, needRepairReport (true only for a non-null reportHash the server doesn't hold). An unreadable settings file leaves
     * out repairHash and repairSettings.
     */
    private void repairFields(JsonObject out, String server, String stash, boolean hasHash, String hash, JsonObject pass) {
        RepairStore rs = repairStore;
        RepairBots rb = repairBots;
        if (rs == null || rb == null) return;
        try {
            RepairStore.Settings s = rs.get(server, stash);
            out.addProperty("repairHash", s.hash());
            if (hasHash && !s.hash().equals(hash)) out.add("repairSettings", s.toJson());
        } catch (RepairStore.DisabledException e) {
            // left out: the bot keeps what it has
        }
        long run = rb.pending(server, stash, 0), stop = rb.pending(server, stash, 1);
        if (run > 0) out.addProperty("repairRun", run);
        if (stop > 0) out.addProperty("repairStop", stop);
        String reportHash = pass == null || pass.get("reportHash").isJsonNull() ? null : pass.get("reportHash").getAsString();
        out.addProperty("needRepairReport", rb.needReport(server, stash, reportHash));
    }

    /**
     * The area part of the reply (AREA-CONTRACT.md, "Heartbeat"), for a heartbeat that passed the owner check. A bot that
     * edited its area in game (edited, with a hash not stored from it before) is stored; otherwise a stored area the bot doesn't
     * have yet is sent back (area + areaHash); a bot with an area and nothing stored is adopted (atomically). Every area from a
     * bot passes the website's checks (shape, the stash's dimension, the distance from the stash's home) and at most 3 stored
     * changes a minute per stash are taken; one that fails is ignored, logged once, and the rest of the heartbeat goes on.
     * areaHash is always sent; an unreadable file leaves both out.
     */
    private void areaFields(HttpExchange ex, JsonObject out, String server, String stash, String player, boolean hasArea, AreaBots.Report report) {
        AreaStore.Settings stored;
        try {
            stored = areas.find(server, stash);
        } catch (AreaStore.DisabledException e) {
            return;
        }
        String dim = AreaStore.normalizeDimension(index().dimension(server, stash));
        try {
            // The delivery container (or the first kit report) fixes the home - but never a heartbeat that carries an in-game edit.
            if (areaGuard != null) areaGuard.ensureHome(server, stash, null, !(report != null && report.edited()));
        } catch (IOException | AreaStore.LimitException | AreaStore.DisabledException e) {
            // no home yet: set from the next heartbeat
        }
        if (report != null) {
            report = report.withDerived(AreaGuard.cleanDerived(report.derived(), dim));
            if (report.area() != null && dim != null && !dim.equals(AreaStore.normalizeDimension(report.area().dimension()))) {
                report = report.invalidArea();
                ignored(server, stash, "its area is not in this stash's dimension");
            } else if (report.invalid()) {
                ignored(server, stash, "its area is outside the contract (shape, size or height)");
            }
            areaBots.put(server, stash, report);
        }
        boolean sendArea = false;
        boolean handled = false;
        try {
            if (report != null && report.edited() && !report.invalid() && !report.hash().equals(areaBots.lastEdit(server, stash))) {
                AreaStore.Settings after = storeFromBot(ex, server, stash, player, report.area(), stored, true);
                if (after != null) {
                    stored = after;
                    areaBots.lastEdit(server, stash, report.hash());
                    handled = true;
                }
            }
            if (!handled) {
                if (stored != null && report != null && !stored.hash().equals(report.hash())) {
                    sendArea = true;
                } else if (stored == null && report != null && !report.invalid() && !report.edited() && report.area() != null) {
                    AreaStore.Settings after = storeFromBot(ex, server, stash, player, report.area(), null, false);
                    if (after != null) stored = after;
                }
            }
        } catch (IOException | AreaStore.LimitException | AreaStore.DisabledException e) {
            ignored(server, stash, "it could not be stored (" + e.getMessage() + ")");
            return;
        }
        out.addProperty("areaHash", stored != null ? stored.hash() : report != null ? report.hash() : AreaStore.hash(null));
        if (sendArea) out.add("area", stored.area() == null ? com.google.gson.JsonNull.INSTANCE : stored.area().toJson());
    }

    private IndexStore index() {
        return store;
    }

    private void ignored(String server, String stash, String why) {
        if (areaBots.firstTime(server, stash, why)) System.err.println("Stash area from the bot of " + stash + " ignored: " + why + ".");
    }

    /** Stores a bot's area when it passes the checks and the rate limit; null when it is ignored. edit: an in-game edit (audited). */
    private AreaStore.Settings storeFromBot(HttpExchange ex, String server, String stash, String player, AreaStore.Area area,
                                            AreaStore.Settings before, boolean edit) throws IOException {
        if (area != null && areaGuard != null) {
            AreaGuard.Problem p = areaGuard.check(server, stash, area, !edit, !edit);
            if (p != null) {
                ignored(server, stash, p.message());
                return null;
            }
        } else if (area == null && edit && index().dimension(server, stash) == null) {
            return null;
        }
        if (!areaBots.allowStore(server, stash)) {
            ignored(server, stash, "more than " + AreaBots.STORES_PER_MINUTE + " changes a minute");
            return null;
        }
        AreaStore.Settings after = edit ? areas.set(server, stash, area, "bot", null) : areas.setIfAbsent(server, stash, area, "bot", null);
        long beforeRev = before == null ? 0 : before.rev();
        if (edit && audit != null && after.rev() != beforeRev) {
            audit.add(null, "bot " + player, "area.bot", stash + ": " + (area == null ? "(none)" : area.minX() + "," + area.minY() + "," + area.minZ()
                + " to " + area.maxX() + "," + area.maxY() + "," + area.maxZ() + " " + area.dimension()), AuthRoutes.address(ex));
        }
        return after;
    }

    /**
     * The craft part of the reply (CRAFT-CONTRACT.md, "Heartbeat"): craftHash always, craft when the bot sent a different
     * craftHash (null counts as different), and recipesHash (the server's tableHash for the stash's version) when those
     * recipes are ready. When the settings file can't be read craftHash and craft are left out.
     */
    private void craftFields(JsonObject out, String server, String stash, boolean hasCraftHash, String craftHash) {
        String mc = store.mcVersion(server, stash);
        if (mc != null) {
            craft.recipes().request(mc);
            RecipeTable t = craft.recipes().table(mc);
            if (t != null) out.addProperty("recipesHash", t.tableHash());
        }
        CraftStore.Settings s;
        try {
            s = craft.store().get(server, stash);
        } catch (CraftStore.DisabledException e) {
            return;
        }
        out.addProperty("craftHash", s.hash());
        if (hasCraftHash && !s.hash().equals(craftHash)) out.add("craft", s.toBotJson());
    }

    /**
     * The kit part of the reply (KIT-CONTRACT.md, "Heartbeat"): kitsHash always, kits when the bot sent a different
     * kitsHash, needKitsReport always. When the settings file can't be read all three are left out.
     */
    private void kitFields(JsonObject out, String server, String stash, boolean hasKitsHash, String kitsHash, String kitsReportHash,
                           boolean hasKitsHash2, String kitsHash2) {
        KitStore.Settings s;
        try {
            s = kits.get(server, stash);
        } catch (KitStore.DisabledException e) {
            return;
        }
        out.addProperty("kitsHash", s.hash());
        out.addProperty("kitsHash2", s.types().hash());
        // A v2 bot (it sent kitsHash2) gets the settings when its v2 hash differs; any other bot when its v1 hash does.
        boolean send = hasKitsHash2 ? !s.types().hash().equals(kitsHash2) : hasKitsHash && !s.hash().equals(kitsHash);
        if (send) out.add("kits", s.toJson());
        out.addProperty("needKitsReport", kitReports.needed(server, stash, kitsReportHash));
    }

    /** The heartbeat's kitOrders object ({v, enabled, restock, craft, import?}); a wrong shape throws (a 400). */
    static KitReports.KitCap kitCap(JsonObject o) {
        if (!(o.get("v") instanceof JsonPrimitive vp) || !vp.isNumber() || vp.getAsString().length() > 9) {
            throw new IllegalArgumentException("kitOrders.v must be a whole number >= 1");
        }
        int v;
        try {
            v = vp.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("kitOrders.v must be a whole number >= 1");
        }
        if (v < 1) throw new IllegalArgumentException("kitOrders.v must be a whole number >= 1");
        boolean[] flags = new boolean[4];
        String[] names = {"enabled", "restock", "craft", "import"};
        for (int i = 0; i < 4; i++) {
            JsonElement e = o.get(names[i]);
            if (e == null || e.isJsonNull()) {
                if (i == 0) throw new IllegalArgumentException("kitOrders.enabled must be true or false");
                continue;
            }
            if (!(e instanceof JsonPrimitive p) || !p.isBoolean()) throw new IllegalArgumentException("kitOrders." + names[i] + " must be true or false");
            flags[i] = p.getAsBoolean();
        }
        return new KitReports.KitCap(v, flags[0], flags[1], flags[2], flags[3]);
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
