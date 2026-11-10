package com.porter.qwerte.craft;

import com.google.gson.JsonObject;
import com.porter.qwerte.stash.StashIndex;

import java.util.List;

/**
 * Whether the bot says it can carry out craft orders, and how it says so: one decision, used by both the claim ({@code craftOrders: 1}) and
 * the heartbeat ({@code craftOrders: {...}}), so the two can never disagree (CRAFT-CONTRACT.md "Bot capability and routing"). Without the
 * flag the server skips orders with craft lines and fails a held one; with it the bot must be able to do them. Pure.
 */
public final class CraftCapability {
    private CraftCapability() {
    }

    /**
     * @param craftingOn          enable-crafting
     * @param craftOrdersSetting  craft-orders
     * @param haveRecipes         a recipe table is held
     * @param tableOk             a usable website table, or the auto path available ({@link CraftAuto#tableOk})
     */
    public static boolean capable(boolean craftingOn, boolean craftOrdersSetting, boolean haveRecipes, boolean tableOk) {
        return CraftOrder.EXECUTES && craftingOn && craftOrdersSetting && haveRecipes && tableOk;
    }

    /** Puts {@code craftOrders: 1} in a claim when the bot is capable, and always while it holds a craft order (the server never fails it as "downgraded"). */
    public static void claimFlag(JsonObject claim, boolean capable, boolean holdsCraftOrder) {
        if (capable || holdsCraftOrder) claim.addProperty("craftOrders", 1);
    }

    /**
     * The id of the craft order the bot holds: the saved plan's id when it has a craft ledger and, with an order in hand, the same id. A ledger
     * of another order is not held. Null when none.
     */
    public static String heldId(String savedId, boolean savedHasLedger, String inHandId) {
        if (savedId == null || !savedHasLedger) return null;
        if (inHandId != null && !inHandId.equals(savedId)) return null;
        return savedId;
    }

    /**
     * Whether the heartbeat carries a craftOrders object: crafting is on and a recipe table is held, with the table state and the reason even
     * when no table route is available, so the website shows why, and with {@code enabled: false} when craft-orders is off (the plugin does the
     * same). Otherwise it is an explicit null.
     */
    public static boolean sendOrders(boolean craftingOn, boolean haveRecipes) {
        return CraftOrder.EXECUTES && craftingOn && haveRecipes;
    }

    /**
     * The craft part of the heartbeat: the capability with the hash of the bot's table, its version, the state of the website's table, the
     * fills cap and the tables seen; or an explicit "cannot" (craftOrders null) when not capable.
     */
    public static StashIndex.Craft heartbeat(boolean capable, String tableHash, String mc, String tableStatus, int maxFills, List<String> seen, String settingsHash) {
        return heartbeat(capable, tableHash, mc, tableStatus, maxFills, seen, settingsHash, null, null);
    }

    /** As above with the auto table's fields: {@code tableWhy} (null while usable) and {@code botTable} (null without one). */
    public static StashIndex.Craft heartbeat(boolean capable, String tableHash, String mc, String tableStatus, int maxFills, List<String> seen, String settingsHash,
                                             String tableWhy, StashIndex.BotTable botTable) {
        return heartbeat(capable, capable, tableHash, mc, tableStatus, maxFills, seen, settingsHash, tableWhy, botTable);
    }

    /** @param send the craftOrders object is sent at all; @param enabled it says the bot takes craft orders (false with the setting off) */
    public static StashIndex.Craft heartbeat(boolean send, boolean enabled, String tableHash, String mc, String tableStatus, int maxFills, List<String> seen, String settingsHash,
                                             String tableWhy, StashIndex.BotTable botTable) {
        if (!send) return new StashIndex.Craft(null, null);
        return new StashIndex.Craft(new StashIndex.CraftOrders(enabled, tableHash, mc, tableStatus, maxFills, seen, tableWhy, botTable), settingsHash);
    }
}
