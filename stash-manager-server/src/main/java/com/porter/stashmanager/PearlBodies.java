package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation of what the bots send for pearl travel (PEARL-CONTRACT v1.0 rev 2): the traveller's heartbeat field pearlTravel and the
 * loader's report. Both return a cleaned copy (only known fields, whole numbers, closed codes) or throw IllegalArgumentException with
 * the reason of the 400. Nothing from a bot is free text; no coordinates exist in these shapes.
 */
final class PearlBodies {
    static final Pattern STATION = Pattern.compile("^ps-[0-9a-f]{8}$");
    static final Pattern TRIP = Pattern.compile("^t-[0-9a-f]{12}$");
    static final Pattern PEARL_ID = Pattern.compile("^[A-Za-z0-9_.-]{1,32}$");
    static final Pattern PLAYER = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    static final Pattern LOCAL_ID = Pattern.compile("^l-[0-9a-f]{8}$");
    static final Pattern DIM = Pattern.compile("^[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}$");
    static final List<String> STEPS = List.of("precheck", "arm_home", "ready", "load_sent", "awaiting_pull", "arrived", "pickup", "rearm", "done");
    static final Set<String> PAUSES = Set.of("player_near", "loader_player_near", "loader_busy", "owner_offline", "loader_stale", "chamber_locked",
        "loader_locked", "low_health", "low_food", "just_joined", "busy_order", "busy_repair", "sorter_busy", "bot_offline");
    /** The codes a traveller may report as its own error (a trip it gave up on). */
    static final Set<String> BOT_ERRORS = Set.of("not_ingame", "in_queue", "low_pearls", "player_near", "loader_player_near", "loader_busy", "owner_offline",
        "loader_stale", "low_health", "low_food", "just_joined", "pull_timeout", "unexpected_location", "stopped", "order_arrived", "arrival_unconfirmed");
    static final Set<String> PRESENCE = Set.of("armed", "empty", "owner_offline", "unknown");
    static final Set<String> LOAD_STATES = Set.of("queued", "loading", "loaded", "aborted");
    static final Set<String> ABORT_REASONS = Set.of("bot_offline", "busy", "path_failed", "timeout", "not_authorised", "disconnected", "player_active",
        "wrong_dimension", "no_such_pearl", "reopen_failed");
    static final Set<String> REARM_RESULTS = Set.of("SUCCESS", "PARTIAL", "NO_PEARLS", "NO_RIM", "NO_CHAMBER", "OCCUPIED", "ABORTED");
    static final int MAX_CHAMBERS = 32;

    private PearlBodies() {
    }

    static IllegalArgumentException bad(String m) {
        return new IllegalArgumentException(m);
    }

    private static long whole(JsonElement e, String what, long min, long max) {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber() || p.getAsString().length() > 20) throw bad(what + " must be a whole number");
        BigDecimal d;
        try {
            d = p.getAsBigDecimal();
        } catch (NumberFormatException ex) {
            throw bad(what + " must be a whole number");
        }
        if (d.stripTrailingZeros().scale() > 0 || d.compareTo(BigDecimal.valueOf(min)) < 0 || d.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw bad(what + " is out of range");
        }
        return d.longValueExact();
    }

    private static Long optTime(JsonObject o, String f) {
        JsonElement e = o.get(f);
        return e == null || e.isJsonNull() ? null : whole(e, f, 1, Long.MAX_VALUE / 4);
    }

    private static String text(JsonObject o, String f, Pattern p, boolean required) {
        JsonElement e = o.get(f);
        if (e == null || e.isJsonNull()) {
            if (required) throw bad(f + " is required");
            return null;
        }
        if (!(e instanceof JsonPrimitive pr) || !pr.isString() || !p.matcher(pr.getAsString()).matches()) throw bad(f + " is not valid");
        return pr.getAsString();
    }

    private static boolean flag(JsonObject o, String f, boolean required, boolean dflt) {
        JsonElement e = o.get(f);
        if (e == null || e.isJsonNull()) {
            if (required) throw bad(f + " must be true or false");
            return dflt;
        }
        if (!(e instanceof JsonPrimitive p) || !p.isBoolean()) throw bad(f + " must be true or false");
        return p.getAsBoolean();
    }

    private static void put(JsonObject o, String f, Long v) {
        if (v == null) o.add(f, JsonNull.INSTANCE);
        else o.addProperty(f, v);
    }

    /** The heartbeat's pearlTravel object, cleaned. Times stay in the bot's clock (the engine normalises them). */
    static JsonObject travel(JsonObject in) {
        JsonObject o = new JsonObject();
        if (whole(in.get("v"), "pearlTravel.v", 1, 1) != 1) throw bad("pearlTravel.v must be 1");
        o.addProperty("v", 1);
        o.addProperty("enabled", flag(in, "enabled", true, false));
        o.addProperty("at", whole(in.get("at"), "pearlTravel.at", 1, Long.MAX_VALUE / 4));
        String uuid = text(in, "uuid", UUID, false);
        o.addProperty("uuid", uuid == null ? null : uuid.toLowerCase());
        String game = in.has("game") && !in.get("game").isJsonNull() ? text(in, "game", Pattern.compile("^(in|queue|out)$"), true) : "in";
        o.addProperty("game", game);
        put(o, "joinedAt", optTime(in, "joinedAt"));
        String client = text(in, "client", Pattern.compile("^(plugin|addon)$"), false);
        o.addProperty("client", client == null ? "plugin" : client);
        JsonElement ae = in.get("away");
        if (ae == null || ae.isJsonNull()) {
            o.add("away", JsonNull.INSTANCE);
        } else {
            if (!(ae instanceof JsonObject a)) throw bad("pearlTravel.away must be an object or null");
            JsonObject ao = new JsonObject();
            ao.addProperty("station", text(a, "station", STATION, true));
            String trip = text(a, "trip", TRIP, false);
            ao.addProperty("trip", trip);
            put(ao, "since", optTime(a, "since"));
            o.add("away", ao);
        }
        JsonElement pe = in.get("pearls");
        if (!(pe instanceof JsonObject p)) throw bad("pearlTravel.pearls must be {carried}");
        JsonObject po = new JsonObject();
        po.addProperty("carried", whole(p.get("carried"), "pearlTravel.pearls.carried", 0, 2304));
        o.add("pearls", po);
        JsonElement te = in.get("trip");
        if (te == null || te.isJsonNull()) {
            o.add("trip", JsonNull.INSTANCE);
        } else {
            if (!(te instanceof JsonObject t)) throw bad("pearlTravel.trip must be an object or null");
            JsonObject to = new JsonObject();
            to.addProperty("id", text(t, "id", TRIP, true));
            String step = text(t, "step", Pattern.compile("^[a-z_]{1,16}$"), true);
            if (!STEPS.contains(step)) throw bad("pearlTravel.trip.step is not one of the contract's");
            to.addProperty("step", step);
            String pause = text(t, "pause", Pattern.compile("^[a-z_]{1,24}$"), false);
            if (pause != null && !PAUSES.contains(pause)) throw bad("pearlTravel.trip.pause is not one of the contract's");
            to.addProperty("pause", pause);
            String error = text(t, "error", Pattern.compile("^[a-z_]{1,24}$"), false);
            if (error != null && !BOT_ERRORS.contains(error)) throw bad("pearlTravel.trip.error is not one of the contract's");
            to.addProperty("error", error);
            to.addProperty("whispers", t.has("whispers") && !t.get("whispers").isJsonNull() ? whole(t.get("whispers"), "whispers", 0, 2) : 0);
            put(to, "loadSentAt", optTime(t, "loadSentAt"));
            put(to, "teleportAt", optTime(t, "teleportAt"));
            to.addProperty("teleportDim", text(t, "teleportDim", DIM, false));
            JsonElement re = t.get("rearm");
            if (re == null || re.isJsonNull()) {
                to.add("rearm", JsonNull.INSTANCE);
            } else {
                if (!(re instanceof JsonObject r)) throw bad("pearlTravel.trip.rearm must be {result} or null");
                String res = text(r, "result", Pattern.compile("^[A-Z_]{1,16}$"), true);
                if (!REARM_RESULTS.contains(res)) throw bad("pearlTravel.trip.rearm.result is not one of the contract's");
                JsonObject ro = new JsonObject();
                ro.addProperty("result", res);
                to.add("rearm", ro);
            }
            JsonElement pl = t.get("pearls");
            JsonObject plo = new JsonObject();
            if (pl instanceof JsonObject plj) {
                for (String f : new String[]{"before", "after"}) {
                    if (plj.has(f) && !plj.get(f).isJsonNull()) plo.addProperty(f, whole(plj.get(f), "pearls." + f, 0, 2304));
                    else plo.add(f, JsonNull.INSTANCE);
                }
            } else {
                plo.add("before", JsonNull.INSTANCE);
                plo.add("after", JsonNull.INSTANCE);
            }
            to.add("pearls", plo);
            o.add("trip", to);
        }
        JsonElement le = in.get("local");
        if (le == null || le.isJsonNull()) {
            o.add("local", JsonNull.INSTANCE);
        } else {
            if (!(le instanceof JsonObject l)) throw bad("pearlTravel.local must be an object or null");
            JsonObject lo = new JsonObject();
            String action = text(l, "action", Pattern.compile("^(go|back|home)$"), true);
            lo.addProperty("action", action);
            lo.addProperty("localId", text(l, "localId", LOCAL_ID, !action.equals("home") ? true : false));
            lo.addProperty("station", text(l, "station", STATION, false));
            lo.addProperty("oneWay", flag(l, "oneWay", false, false));
            o.add("local", lo);
        }
        return o;
    }

    /** The loader report (heartbeat pearlLoader, or the body of POST /api/v1/bot/pearls), cleaned. Times stay in the loader's clock. */
    static JsonObject loader(JsonObject in) {
        JsonObject o = new JsonObject();
        if (whole(in.get("v"), "report.v", 1, 1) != 1) throw bad("report.v must be 1");
        o.addProperty("v", 1);
        o.addProperty("player", text(in, "player", PLAYER, true));
        o.addProperty("at", whole(in.get("at"), "report.at", 1, Long.MAX_VALUE / 4));
        o.addProperty("dropPearlAfterLoad", flag(in, "dropPearlAfterLoad", true, false));
        o.addProperty("temporaryMode", flag(in, "temporaryMode", false, false));
        o.addProperty("busy", flag(in, "busy", false, false));
        o.addProperty("playerNear", flag(in, "playerNear", false, false));
        JsonArray chambers = new JsonArray();
        JsonElement ce = in.get("chambers");
        if (!(ce instanceof JsonArray ca) || ca.size() > MAX_CHAMBERS) throw bad("chambers must be a list of at most " + MAX_CHAMBERS);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (JsonElement e : ca) {
            if (!(e instanceof JsonObject c)) throw bad("every chamber must be an object");
            JsonObject co = new JsonObject();
            String id = text(c, "id", PEARL_ID, true);
            if (!ids.add(id)) throw bad("a chamber appears twice");
            co.addProperty("id", id);
            String owner = text(c, "owner", PLAYER, false);
            boolean known = flag(c, "ownerKnown", false, owner != null);
            co.addProperty("owner", known ? owner : null);
            co.addProperty("ownerKnown", known && owner != null);
            String presence = text(c, "presence", Pattern.compile("^[a-z_]{1,16}$"), true);
            if (!PRESENCE.contains(presence)) throw bad("a chamber's presence must be armed, empty, owner_offline or unknown");
            co.addProperty("presence", presence);
            put(co, "since", optTime(c, "since"));
            chambers.add(co);
        }
        o.add("chambers", chambers);
        JsonElement le = in.get("load");
        if (le == null || le.isJsonNull()) {
            o.add("load", JsonNull.INSTANCE);
        } else {
            if (!(le instanceof JsonObject l)) throw bad("load must be an object or null");
            JsonObject lo = new JsonObject();
            lo.addProperty("id", text(l, "id", PEARL_ID, true));
            lo.addProperty("requester", text(l, "requester", PLAYER, true));
            String state = text(l, "state", Pattern.compile("^[a-z_]{1,16}$"), true);
            if (!LOAD_STATES.contains(state)) throw bad("load.state must be queued, loading, loaded or aborted");
            lo.addProperty("state", state);
            String reason = text(l, "reason", Pattern.compile("^[a-z_]{1,24}$"), false);
            if (reason != null && !ABORT_REASONS.contains(reason)) throw bad("load.reason is not one of the contract's");
            lo.addProperty("reason", reason);
            put(lo, "at", optTime(l, "at"));
            put(lo, "pearlGoneAt", optTime(l, "pearlGoneAt"));
            put(lo, "requesterNearAt", optTime(l, "requesterNearAt"));
            lo.addProperty("pearlPresent", flag(l, "pearlPresent", false, false));
            JsonElement re = l.get("reopened");
            if (re == null || re.isJsonNull()) lo.add("reopened", JsonNull.INSTANCE);
            else lo.addProperty("reopened", flag(l, "reopened", true, false));
            o.add("load", lo);
        }
        return o;
    }
}
