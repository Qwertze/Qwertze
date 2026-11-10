package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwerte.stash.AreaEdit;
import com.porter.qwerte.stash.AreaRules;
import com.porter.qwerte.stash.CraftBounds;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The window's Stash area card, apart from the widgets: the server's answer ({@code GET /api/v1/area}), what is said about it, the body of a
 * save, and why a button is off (AREA-CONTRACT.md "In-game Stash window"). Nothing here touches Minecraft or the network, and everything from the
 * server is read forgivingly: a wrong type is the default, never an exception.
 */
public final class AreaModel {
    private AreaModel() {
    }

    /** The bot's side: {@code supports} is true, false or null (unknown); {@code applied}/{@code derived} are boxes or null. */
    public record Bot(Boolean supports, boolean online, boolean synced, int[] applied, String appliedDimension, int[] derived, String pendingWhy, int containersOutside) {
    }

    public record Impact(int reported, int outside) {
    }

    public record Limits(int maxW, int maxH, int maxD, int yMin, int yMax, int farBlocks) {
        static final Limits DEFAULT = new Limits(AreaRules.MAX_W, AreaRules.MAX_H, AreaRules.MAX_D, -64, 319, 256);
    }

    /**
     * @param box          the stored area (min and max corners) or null; {@code dimension} its dimension
     * @param by           "website", "bot" or ""
     * @param stashDimension the stash's namespaced dimension or ""
     * @param home         the stash's home point or null
     */
    public record Data(String server, String stash, long rev, String hash, int[] box, String dimension, String by, String user, long updatedAt,
                       String stashDimension, int[] home, Bot bot, Impact impact, Limits limits) {
        /** The box an editor starts from: the stored area, else the bot's applied one, else its derived one; null with none of them. */
        public int[] startBox() {
            if (box != null) return box;
            if (bot.applied() != null) return bot.applied();
            return bot.derived();
        }
    }

    private static int[] box6(JsonElement e) {
        // Whole numbers only, each minimum at most its maximum (the same reading as the bot's)
        try {
            return AreaRules.boxOf(e, true);
        } catch (AreaRules.Invalid bad) {
            return null;
        }
    }

    private static int[] areaBox(JsonObject o, String key) {
        JsonObject a = Json.obj(o == null ? null : o.get(key));
        return a == null ? null : box6(a.get("box"));
    }

    private static String areaDim(JsonObject o, String key) {
        JsonObject a = Json.obj(o == null ? null : o.get(key));
        return a == null ? "" : Text.sanitize(Json.str(a, "dimension"), 120);
    }

    /** Reads an answer; null when it is not one. */
    public static Data parse(JsonElement body) {
        JsonObject o = Json.obj(body);
        if (o == null || Json.str(o, "stash").isEmpty()) return null;
        JsonObject b = Json.obj(o.get("bot"));
        Boolean supports = null;
        if (b != null && b.get("supports") != null && b.get("supports").isJsonPrimitive() && b.get("supports").getAsJsonPrimitive().isBoolean()) supports = b.get("supports").getAsBoolean();
        JsonObject pend = b == null ? null : Json.obj(b.get("pending"));
        Bot bot = new Bot(supports, Json.bool(b, "online"), Json.bool(b, "synced"), areaBox(b, "applied"), areaDim(b, "applied"),
            b == null ? null : box6(b.get("derived")), pend == null ? null : Text.sanitize(Json.str(pend, "why"), 20), (int) Json.lng(b, "containersOutside", 0));
        JsonObject im = Json.obj(o.get("impact"));
        Impact impact = im == null ? null : new Impact((int) Json.lng(im, "reported", 0), (int) Json.lng(im, "outside", 0));
        JsonObject l = Json.obj(o.get("limits"));
        Limits limits = l == null ? Limits.DEFAULT : new Limits((int) Json.lng(l, "maxW", AreaRules.MAX_W), (int) Json.lng(l, "maxH", AreaRules.MAX_H),
            (int) Json.lng(l, "maxD", AreaRules.MAX_D), (int) Json.lng(l, "yMin", -64), (int) Json.lng(l, "yMax", 319), (int) Json.lng(l, "farBlocks", 256));
        int[] home = null;
        JsonArray h = Json.arr(o.get("home"));
        if (h != null && h.size() == 3 && h.get(0).isJsonPrimitive() && h.get(1).isJsonPrimitive() && h.get(2).isJsonPrimitive()) {
            home = new int[] {(int) h.get(0).getAsDouble(), (int) h.get(1).getAsDouble(), (int) h.get(2).getAsDouble()};
        }
        return new Data(Text.sanitize(Json.str(o, "server"), 200), Text.sanitize(Json.str(o, "stash"), 100), Json.lng(o, "rev", 0), Json.str(o, "hash"), areaBox(o, "area"),
            areaDim(o, "area"), Json.str(o, "by"), Text.sanitize(Json.str(o, "user"), 40), Json.lng(o, "updatedAt", 0), Text.sanitize(Json.str(o, "dimension"), 120), home, bot, impact, limits);
    }

    /** The body of a save: {@code box} null clears; {@code rev} is the revision the card is showing. */
    public static JsonObject saveBody(String server, String stash, int[] box, String dimension, long rev) {
        JsonObject o = new JsonObject();
        o.addProperty("server", server);
        o.addProperty("stash", stash);
        if (box == null) o.add("area", com.google.gson.JsonNull.INSTANCE);
        else {
            JsonObject a = new JsonObject();
            JsonArray arr = new JsonArray();
            for (int v : box) arr.add(v);
            a.add("box", arr);
            a.addProperty("dimension", dimension);
            o.add("area", a);
        }
        o.addProperty("rev", rev);
        return o;
    }

    // ---------- words ----------

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public static String when(long ms) {
        return ms <= 0 ? "" : WHEN.format(Instant.ofEpochMilli(ms));
    }

    /** "Set on the website by <user> at <time>", "changed in game at <time>", or "No area is set". */
    public static String sourceLine(Data d, boolean hide) {
        if (d.box() == null && d.by().isEmpty()) return "No area is set: the bot works in a box around the containers it has read, or around itself.";
        String at = when(d.updatedAt());
        String user = hide || d.user().isEmpty() ? "" : " by " + d.user();
        if (d.by().equals("website")) return (d.box() == null ? "Cleared" : "Set") + " on the website" + user + (at.isEmpty() ? "" : " at " + at) + ".";
        if (d.by().equals("bot")) return (d.box() == null ? "Cleared" : "Changed") + " in game" + (at.isEmpty() ? "" : " at " + at) + ".";
        return d.box() == null ? "No area is set." : "An area is set.";
    }

    /** The bot's side in words: applied, waiting (and why), or not supported. */
    public static String botLine(Data d) {
        Bot b = d.bot();
        if (Boolean.FALSE.equals(b.supports())) return "This bot will keep its own area until it is updated.";
        if (!b.online()) return "The bot is offline: it takes the area when it is back.";
        if (b.pendingWhy() != null) {
            return "Waiting to be applied: " + switch (b.pendingWhy()) {
                case "busy" -> "the bot is busy with a task, it applies it between tasks.";
                case "far" -> "the bot is more than 128 blocks away, it applies it when it is nearer.";
                case "dimension" -> "the bot is in another dimension.";
                default -> b.pendingWhy() + ".";
            };
        }
        if (Boolean.TRUE.equals(b.supports()) && b.synced()) return "The bot has this area.";
        if (b.supports() == null) return "Not sure the bot supports areas yet (no report for a while).";
        return "The bot has not taken this area yet.";
    }

    public static String impactLine(Data d) {
        if (d.impact() == null) return "";
        if (d.impact().outside() <= 0) return "All " + d.impact().reported() + " reported containers are inside the stored area.";
        return d.impact().outside() + " of " + d.impact().reported() + " reported containers would be outside the stored area.";
    }

    /** What a refused save says (the server's reason, cut), by status. */
    public static String saveError(int status, String reason) {
        String r = reason == null ? "" : Text.sanitize(reason, 160);
        return switch (status) {
            case 400 -> r.isEmpty() ? "The server refused that area." : r;
            case 403 -> "Your account may not change the stash area.";
            case 404 -> "The server doesn't know that stash.";
            case 409 -> r.isEmpty() ? "The area changed meanwhile. Reload and try again." : r;
            case 413 -> "That request was too big.";
            case 429 -> "Too many changes. Try again in a minute.";
            case 503 -> "The server can't read its area file right now.";
            default -> "Couldn't save the area (" + status + ").";
        };
    }

    // ---------- why a button is off ----------

    /**
     * Why the position buttons (corner 1/2 here, include where I stand, expand) can't be used from where the player is, or null when they can: the
     * player must be on the stash's server and in its dimension.
     * @param sameServer the world is the stash's server (always true for the local stash)
     * @param stashDimension the stash's dimension, "" when not known
     * @param currentDimension the player's dimension, "" with no world
     */
    public static String positionReason(boolean haveWorld, boolean sameServer, String stashDimension, String currentDimension) {
        if (!haveWorld) return "Join a world to use your position.";
        if (!sameServer) return "You are not on this stash's server.";
        if (stashDimension == null || stashDimension.isEmpty()) return "The stash's dimension isn't known yet.";
        if (!CraftBounds.sameDimension(stashDimension, currentDimension)) return "You are not in the stash's dimension (" + CraftBounds.fullId(stashDimension) + ").";
        return null;
    }

    /** Why "at the block I look at" is off: the position buttons' reason, else no block was looked at within 64 blocks when the window was opened. */
    public static String lookReason(String positionReason, boolean haveCrosshair) {
        if (positionReason != null) return positionReason;
        return haveCrosshair ? null : "No block was in your crosshair, within 64 blocks, when the window was opened.";
    }

    /** Why Save is off, or null. */
    public static String saveReason(boolean canWrite, boolean busy, boolean haveDraft, boolean changed, String invalid) {
        if (!canWrite) return "Your account may not change the stash area.";
        if (busy) return "Saving...";
        if (!haveDraft) return "There is no draft to save.";
        if (invalid != null) return invalid;
        if (!changed) return "The draft is the same as the stored area.";
        return null;
    }

    /** The draft in words: "x 100..163, y 60..90, z -40..23 (64×31×64)". */
    public static String describe(int[] box) {
        return "x " + box[0] + ".." + box[3] + ", y " + box[1] + ".." + box[4] + ", z " + box[2] + ".." + box[5] + " (" + AreaEdit.sizeOf(box) + ")";
    }
}
