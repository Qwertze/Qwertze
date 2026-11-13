package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A real server for the enchant tests (ENCHANT-CONTRACT v1.0): stash s/a on Minecraft 1.21.11 owned by Bob, holding diamond swords
 * (fresh, repair cost 0), two Sharpness V books, a Looting III book, 3000 experience bottles (the repair reserve is 1,728) and 3 anvils.
 * The clock of the enchant capability, the user limits and the plan cache is {@link #enchantClock}.
 */
abstract class EnchantHarness extends CraftHarness {
    static final String MC11 = "1.21.11";
    static final String SHARP = "minecraft:enchanted_book#a1111111";
    static final String LOOT = "minecraft:enchanted_book#a2222222";
    static final String USER_PERMS = "{\"view\":true,\"order\":true}";
    final AtomicLong enchantClock = new AtomicLong(1_800_000_000_000L);
    /** The clock of the per-user limits; placeEnchant moves it 6 s a call so a test that places many orders stays under 12 a minute. */
    final AtomicLong limitClock = new AtomicLong(1_800_000_000_000L);

    static String e(String id, int lvl) {
        return "{\"id\":\"" + (id.contains(":") ? id : "minecraft:" + id) + "\",\"lvl\":" + lvl + "}";
    }

    static String ench(String... entries) {
        return "[" + String.join(",", entries) + "]";
    }

    /** An index item entry. extra is appended inside the object (ench, rc, named). */
    static String it(String key, String id, long count, int maxStack, String extra) {
        return "{\"key\":\"" + key + "\",\"id\":\"" + id + "\",\"count\":" + count + ",\"maxStack\":" + maxStack
            + (extra == null || extra.isEmpty() ? "" : "," + extra) + "}";
    }

    static String sword(long count) {
        return it("minecraft:diamond_sword", "minecraft:diamond_sword", count, 1, "\"ench\":[],\"rc\":0");
    }

    static String book(String key, long count, int rc, String... entries) {
        return it(key, "minecraft:enchanted_book", count, 1, "\"ench\":" + ench(entries) + ",\"rc\":" + rc);
    }

    static String bottles(long n) {
        return it("minecraft:experience_bottle", "minecraft:experience_bottle", n, 64, null);
    }

    static String anvils(long n) {
        return it("minecraft:anvil", "minecraft:anvil", n, 64, null);
    }

    List<String> items() {
        List<String> l = new ArrayList<>();
        l.add(sword(3));
        l.add(book(SHARP, 2, 0, e("sharpness", 5)));
        l.add(book(LOOT, 1, 0, e("looting", 3)));
        l.add(bottles(3000));
        l.add(anvils(3));
        l.add(it("minecraft:dirt", "minecraft:dirt", 100, 64, null));
        return l;
    }

    static String index(String mc, String player, List<String> items) {
        return "{\"version\":1,\"server\":\"s\",\"stash\":\"a\"" + (player == null ? "" : ",\"player\":\"" + player + "\"")
            + (mc == null ? "" : ",\"mcVersion\":\"" + mc + "\"") + ",\"items\":[" + String.join(",", items) + "]}";
    }

    @Override
    String indexJson() {
        return index(MC11, "Bob", items());
    }

    /** Starts the server with the default index and puts the enchant clocks under test control. */
    void startEnchant() throws Exception {
        start();
        routes.enchant().bots().clock(enchantClock::get);
        routes.enchantLimits().clock(limitClock::get);
        routes.enchantRoutes().clock(enchantClock::get);
    }

    /** Replaces the stash's index (as the bot would post it). */
    void reindex(List<String> items) throws Exception {
        ok(botPost("/api/v1/index", index(MC11, "Bob", items)));
    }

    static String block(boolean enabled, String mc, Integer carry) {
        return "{\"v\":1,\"enabled\":" + enabled + ",\"mc\":\"" + mc + "\"" + (carry == null ? "" : ",\"carry\":" + carry) + "}";
    }

    /** A heartbeat of the owner bot with the enchant block. */
    Res enchantBeat(String blockJson) throws Exception {
        return heartbeat("\"enchant\":" + blockJson);
    }

    /** The capable bot: an enabled block for 1.21.11 with 30 free carry slots (the owner is Bob, who becomes it with this heartbeat). */
    JsonObject ready() throws Exception {
        return ok(enchantBeat(block(true, MC11, 30)));
    }

    static String line(String key, int count, String... enchants) {
        return "{\"type\":\"enchant\",\"key\":\"" + key + "\",\"count\":" + count + ",\"enchants\":" + ench(enchants) + "}";
    }

    Res placeEnchant(String... lines) throws Exception {
        limitClock.addAndGet(6_000);
        return placeOrder("[" + String.join(",", lines) + "]");
    }

    JsonObject placedOrder(String... lines) throws Exception {
        return ok(placeEnchant(lines)).getAsJsonObject("order");
    }

    static long takeCount(JsonObject order, String key) {
        for (JsonElement t : order.getAsJsonObject("enchant").getAsJsonArray("take")) {
            if (t.getAsJsonObject().get("key").getAsString().equals(key)) return t.getAsJsonObject().get("count").getAsLong();
        }
        return 0;
    }

    JsonObject orderById(String id) throws Exception {
        for (JsonElement o : ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders")) {
            if (o.getAsJsonObject().get("id").getAsString().equals(id)) return o.getAsJsonObject();
        }
        return null;
    }

    Res claim(String extra) throws Exception {
        return botPost("/api/v1/orders/claim", "{" + KEY + ",\"player\":\"Bob\"" + (extra == null ? "" : "," + extra) + "}");
    }

    Res claimEnchant() throws Exception {
        return claim("\"enchantOrders\":1");
    }

    Res status(String id, String player, String body) throws Exception {
        return botPost("/api/v1/orders/" + id + "/status", "{" + (player == null ? "" : "\"player\":\"" + player + "\",") + body + "}");
    }

    static JsonArray arr(JsonElement e) {
        return e.getAsJsonArray();
    }
}
