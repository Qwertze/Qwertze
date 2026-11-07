package com.porter.qwerte.manager.model;

import com.google.gson.JsonParser;
import com.porter.qwerte.manager.local.LocalSafety;
import com.porter.qwerte.stash.Order;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamesAndModeTest {
    @Test
    void noNamesByDefault() {
        NamesDraft d = new NamesDraft();
        assertNull(d.toNames(5));
        assertNull(d.toJson(5));
        assertEquals(0, d.namedCount(5));
    }

    @Test
    void oneNameForAll() {
        NamesDraft d = new NamesDraft();
        d.setMode(NamesDraft.Mode.ALL);
        d.setAll("  Gems ");
        assertEquals(4, d.namedCount(4));
        assertEquals("{\"mode\":\"all\",\"name\":\"Gems\"}", d.toJson(4).toString());
        d.setAll("   ");
        assertNull(d.toJson(4));
        assertEquals(0, d.namedCount(4));
    }

    @Test
    void aNameForEachByPosition() {
        NamesDraft d = new NamesDraft();
        d.setMode(NamesDraft.Mode.EACH);
        d.setEach(0, "A");
        d.setEach(2, " C ");
        assertEquals(2, d.namedCount(3));
        assertEquals(1, d.namedCount(2), "only the shulkers that exist count");
        assertEquals("{\"mode\":\"each\",\"names\":[\"A\",\"\",\"C\"]}", d.toJson(3).toString());
        // a shorter cart keeps the typed names for later
        assertEquals("{\"mode\":\"each\",\"names\":[\"A\"]}".length() > 0, d.toJson(1) != null);
        assertEquals(" C ", d.each(2));
        d.reset();
        assertNull(d.toJson(3));
        assertEquals("", d.each(2));
    }

    @Test
    void invalidNamesAreReported() {
        NamesDraft d = new NamesDraft();
        d.setMode(NamesDraft.Mode.ALL);
        d.setAll("x".repeat(51));
        assertFalse(d.problem(2).isEmpty());
        d.setAll("with § sign");
        assertFalse(d.problem(2).isEmpty());
        d.setAll("fine");
        assertEquals("", d.problem(2));
        d.setMode(NamesDraft.Mode.EACH);
        d.setEach(1, "ba\u0007d");
        assertTrue(d.problem(2).startsWith("Shulker 2"));
        assertEquals("", d.problem(1), "a name for a shulker that is not planned is not checked");
    }

    @Test
    void thisClientIsTheOnlyChoiceWithoutAServer() {
        assertEquals(OrderMode.THIS_CLIENT, OrderMode.choose(false, null));
        assertEquals(OrderMode.THIS_CLIENT, OrderMode.choose(false, OrderMode.SERVER_BOTS));
        assertFalse(OrderMode.offersChoice(false));
    }

    @Test
    void withAServerTheBotsAreTheDefaultAndTheChoiceIsKept() {
        assertEquals(OrderMode.SERVER_BOTS, OrderMode.choose(true, null));
        assertEquals(OrderMode.THIS_CLIENT, OrderMode.choose(true, OrderMode.THIS_CLIENT));
        assertTrue(OrderMode.offersChoice(true));
    }

    @Test
    void thisClientRefusesWhenItWouldCollide() {
        assertNull(OrderMode.localRefusal(true, false, false, false, true, true));
        assertNotNull(OrderMode.localRefusal(false, false, false, false, true, true));
        assertTrue(OrderMode.localRefusal(true, true, false, false, true, true).contains("stash-manager"));
        assertTrue(OrderMode.localRefusal(true, false, true, false, true, true).contains("already running"));
        assertTrue(OrderMode.localRefusal(true, false, false, true, true, true).contains("busy"));
        assertTrue(OrderMode.localRefusal(true, false, false, false, false, true).contains("No stash"));
        assertTrue(OrderMode.localRefusal(true, false, false, false, true, false).contains("delivery"));
        // stash-manager wins over everything else that is fine
        assertTrue(OrderMode.localRefusal(true, true, true, true, true, true).contains("already running"));
    }

    @Test
    void safetyStopsForAPlayerAHostileOrTheClock() {
        LocalSafety.Limits limits = new LocalSafety.Limits(6, 4, 30);
        assertNull(LocalSafety.check(limits, -1, -1, 1000));
        assertNull(LocalSafety.check(limits, 6.5, 4.5, 1000));
        assertTrue(LocalSafety.check(limits, 6, -1, 1000).contains("player"));
        assertTrue(LocalSafety.check(limits, -1, 3.9, 1000).contains("hostile"));
        assertNull(LocalSafety.check(limits, -1, -1, 30 * 60_000L));
        assertTrue(LocalSafety.check(limits, -1, -1, 30 * 60_000L + 1).contains("30 minutes"));
    }

    @Test
    void zeroTurnsARadiusOff() {
        LocalSafety.Limits limits = new LocalSafety.Limits(0, 0, 5);
        assertFalse(limits.scansEntities());
        assertNull(LocalSafety.check(limits, 1, 1, 0));
        LocalSafety.Limits onlyPlayers = new LocalSafety.Limits(6, 0, 5);
        assertTrue(onlyPlayers.scansEntities());
        assertEquals(6, onlyPlayers.scanRadius());
        assertNull(LocalSafety.check(onlyPlayers, -1, 0.5, 0));
        // the timeout cannot be turned off
        assertEquals(1, new LocalSafety.Limits(0, 0, 0).timeoutMinutes());
        assertNotNull(LocalSafety.check(new LocalSafety.Limits(0, 0, 0), -1, -1, 61_000));
    }

    @Test
    void botInfoWarningsUseTheWebsitesWords() {
        BotInfo none = BotInfo.parse(JsonParser.parseString("{}").getAsJsonObject());
        assertEquals("This bot can't name shulkers; they'll arrive unnamed.", none.namesWarning(2));
        assertEquals("", none.namesWarning(0));
        BotInfo noAnvil = BotInfo.parse(JsonParser.parseString("{\"naming\":{\"anvil\":false,\"xpLevels\":3}}").getAsJsonObject());
        assertTrue(noAnvil.namesWarning(1).startsWith("No anvil set;"));
        BotInfo finds = BotInfo.parse(JsonParser.parseString("{\"naming\":{\"anvil\":false,\"anvilSource\":\"none\",\"xpLevels\":3}}").getAsJsonObject());
        assertTrue(finds.namesWarning(1).startsWith("No anvil yet:"));
        BotInfo xp = BotInfo.parse(JsonParser.parseString("{\"naming\":{\"anvil\":true,\"xpLevels\":3}}").getAsJsonObject());
        assertEquals("May run out of XP: about 1 level per shulker. The bot has 3 levels for 5 named shulkers; any it can't name arrive unnamed.", xp.namesWarning(5));
        assertEquals("", xp.namesWarning(3));
        BotInfo storage = BotInfo.parse(JsonParser.parseString("{\"deliveryStorage\":{\"autoExpand\":false,\"chests\":3,\"full\":1,\"canExpand\":false,\"blocked\":null}}").getAsJsonObject());
        assertEquals("3 delivery chests, 1 full (auto expand is off).", storage.storageText());
        assertTrue(storage.craftInfo().startsWith("3 delivery chests"));
        assertTrue(Order.valid("ok"));
    }

    @Test
    void aParkedServerOrderBlocksThisClient() {
        String why = OrderMode.localRefusal(true, false, false, false, true, true, "o-3f2a9c1d0b12");
        assertNotNull(why);
        assertTrue(why.contains("server order is parked"), why);
        assertTrue(why.contains("stash-manager"), why);
    }

    @Test
    void aSavedOrderOfThisClientDoesNotBlock() {
        assertNull(OrderMode.localRefusal(true, false, false, false, true, true, "L-0a1b2c3d4e5f"));
        assertNull(OrderMode.localRefusal(true, false, false, false, true, true, null));
    }

    @Test
    void onlyOrdersOfThisClientMayBeDropped() {
        assertTrue(OrderMode.isLocalId("L-0a1b2c3d4e5f"));
        assertFalse(OrderMode.isLocalId("o-0a1b2c3d4e5f"));
        assertFalse(OrderMode.isLocalId("l-lowercase"));
        assertFalse(OrderMode.isLocalId(""));
        assertFalse(OrderMode.isLocalId(null));
    }

    @Test
    void driveBlockersAreSaidInPlainWords() {
        assertTrue(OrderMode.driveBlockerText("wrong dimension").contains("another dimension"));
        assertTrue(OrderMode.driveBlockerText("too far from the stash").contains("too far"));
        assertTrue(OrderMode.driveBlockerText("something else").contains("something else"));
    }

    @Test
    void theOtherRefusalsComeBeforeAParkedServerOrder() {
        assertTrue(OrderMode.localRefusal(true, false, false, false, false, true, "o-1").contains("No stash"));
    }
}
