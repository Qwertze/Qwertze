package com.porter.qwerte.manager.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * What a stash entry of {@code GET /api/v1/stashes} says about its bot for ordering: whether it can name shulkers, craft, how
 * its delivery storage is doing and why it cannot take orders now. Free text is cleaned. Pure; the wording of the warnings is the
 * website's.
 */
public record BotInfo(boolean hasNaming, boolean anvil, String anvilSource, int xpLevels,
                      boolean hasCrafting, boolean craftingOn, boolean table, String tableSource,
                      boolean hasStorage, boolean autoExpand, int chests, int full, boolean canExpand, String blocked,
                      String orderBlocker, boolean hasDelivery, String contentsKey,
                      int openOrders, String activeOrder, String deliveryAt, String conflictPlayer, long conflictAt) {

    public static final BotInfo NONE = new BotInfo(false, false, "", -1, false, false, false, "", false, false, 0, 0, false, "", "", false, "");

    /** Without the bot overview's fields (open orders, the active order, the delivery spot, a stash-name conflict). */
    public BotInfo(boolean hasNaming, boolean anvil, String anvilSource, int xpLevels,
                   boolean hasCrafting, boolean craftingOn, boolean table, String tableSource,
                   boolean hasStorage, boolean autoExpand, int chests, int full, boolean canExpand, String blocked,
                   String orderBlocker, boolean hasDelivery, String contentsKey) {
        this(hasNaming, anvil, anvilSource, xpLevels, hasCrafting, craftingOn, table, tableSource, hasStorage, autoExpand, chests, full,
            canExpand, blocked, orderBlocker, hasDelivery, contentsKey, 0, "", "", "", 0);
    }

    public static BotInfo parse(JsonObject o) {
        if (o == null) return NONE;
        JsonObject naming = Json.obj(o.get("naming"));
        JsonObject crafting = Json.obj(o.get("crafting"));
        JsonObject storage = Json.obj(o.get("deliveryStorage"));
        JsonObject delivery = Json.obj(o.get("delivery"));
        JsonObject contents = Json.obj(o.get("deliveryContents"));
        JsonObject orders = Json.obj(o.get("orders"));
        JsonObject active = orders == null ? null : Json.obj(orders.get("active"));
        JsonObject conflict = Json.obj(o.get("conflict"));
        String activeText = "";
        if (active != null) {
            String phase = Text.sanitize(Json.str(active, "phase"), 60);
            String status = Text.sanitize(Json.str(active, "status"), 24);
            activeText = "Order " + Text.sanitize(Json.str(active, "id"), 24) + ": " + (!phase.isEmpty() ? phase : statusLabel(status));
        }
        boolean deliverySet = delivery != null && isNumber(delivery, "x") && isNumber(delivery, "y") && isNumber(delivery, "z");
        return new BotInfo(
            naming != null, naming == null || !(isBool(naming, "anvil") && !Json.bool(naming, "anvil")),
            naming == null ? "" : Json.str(naming, "anvilSource"),
            naming == null ? -1 : (int) Json.num(naming, "xpLevels", -1),
            crafting != null, Json.bool(crafting, "enabled"), Json.bool(crafting, "table"),
            crafting == null ? "" : Json.str(crafting, "tableSource"),
            storage != null, Json.bool(storage, "autoExpand"), (int) Math.max(0, Json.num(storage, "chests", 0)),
            (int) Math.max(0, Json.num(storage, "full", 0)), Json.bool(storage, "canExpand"),
            Text.sanitize(Json.str(storage, "blocked"), 160),
            Text.sanitize(Json.str(o, "orderBlocker"), 120), deliverySet,
            contents == null ? "" : Json.str(contents, "hash") + "|" + (long) Json.num(contents, "receivedAt", 0),
            orders == null ? 0 : (int) Math.max(0, Math.min(Integer.MAX_VALUE, Json.num(orders, "open", 0))), activeText,
            deliverySet ? (long) Json.num(delivery, "x", 0) + ", " + (long) Json.num(delivery, "y", 0) + ", " + (long) Json.num(delivery, "z", 0) : "",
            conflict == null ? "" : Text.sanitize(Json.str(conflict, "player"), 32), conflict == null ? 0 : Json.lng(conflict, "at", 0));
    }

    /** The website's STATUS_LABEL for an order status. */
    static String statusLabel(String status) {
        return switch (status) {
            case "pending" -> "Pending";
            case "accepted" -> "Accepted";
            case "in_progress" -> "In progress";
            case "delivered" -> "Delivered";
            case "failed" -> "Failed";
            case "cancelled" -> "Cancelled";
            default -> status;
        };
    }

    private static boolean isBool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean();
    }

    private static boolean isNumber(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    /** True when the server holds a delivery report for the stash. */
    public boolean hasContents() {
        return !contentsKey.isEmpty();
    }

    /** The naming warning for {@code n} named shulkers, or "" (the website's {@code namesWarnText}). */
    public String namesWarning(int n) {
        if (n <= 0) return "";
        if (!hasNaming) return "This bot can't name shulkers; they'll arrive unnamed.";
        if (!anvil && !anvilSource.isEmpty()) {
            return "No anvil yet: the bot will look for one in the stash area, or place one from the stash. If it can't, the shulkers arrive unnamed.";
        }
        if (!anvil) {
            return "No anvil set; the shulkers will arrive unnamed. Set one on the bot: in Meteor, stash-manager -> select-anvil; on the proxy, stash anvil here.";
        }
        if (xpLevels >= 0 && xpLevels < n) {
            return "May run out of XP: about 1 level per shulker. The bot has " + plural(xpLevels, "level") + " for " + plural(n, "named shulker")
                + "; any it can't name arrive unnamed.";
        }
        return "";
    }

    /** Crafting and delivery storage notes, "" when none (the website's {@code renderCraftInfo}). */
    public String craftInfo() {
        String text = "";
        if (hasCrafting && craftingOn) {
            boolean named = tableSource.equals("set") || tableSource.equals("found") || tableSource.equals("placed") || tableSource.equals("crafted");
            if (table) {
                text = "The bot may craft missing empty shulker boxes from the stash's materials" + (named ? " (crafting table: " + tableSource + ")." : ".");
            } else if (!tableSource.isEmpty()) {
                text = "No crafting table yet: the bot will look for one in the stash area, place one from the stash, or craft one from planks or logs.";
            } else {
                text = "Crafting is on, but no crafting table is set: the bot can't craft shulker boxes.";
            }
        }
        String st = storageText();
        if (!st.isEmpty()) text = text.isEmpty() ? st : text + " " + st;
        return text;
    }

    /** "3 delivery chests, 1 full, can put up more when full", or "". */
    public String storageText() {
        if (!hasStorage || chests <= 0) return "";
        String text = plural(chests, "delivery chest") + (full > 0 ? ", " + Text.grouped(full) + " full" : "");
        if (canExpand) text += ", can put up more when full";
        else if (!blocked.isEmpty()) text += full >= chests ? ": waiting for a player to empty one (" + blocked + ")" : " (can't put up more: " + blocked + ")";
        else if (!autoExpand) text += " (auto expand is off)";
        return text + ".";
    }

    static String plural(long n, String word) {
        return Text.grouped(n) + " " + word + (n == 1 ? "" : "s");
    }
}
