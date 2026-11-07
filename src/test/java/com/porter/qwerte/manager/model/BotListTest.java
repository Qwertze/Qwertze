package com.porter.qwerte.manager.model;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotListTest {
    static final String BOTS = "[{\"botId\":\"ct1\",\"player\":\"Pl§ayer\",\"online\":true,\"lastSeen\":5,\"plugin\":{\"id\":\"qs\",\"version\":\"1.2\"},"
        + "\"caps\":[\"settings\",\"viewer\"],\"scopes\":[\"plugin\",\"proxy\",\"Bad Scope\",\"plugin:other\"]},{\"botId\":\"\"},5,{\"botId\":\"ct2\",\"online\":false}]";

    @Test
    void parsesAndCleans() {
        BotList l = BotList.parse(JsonParser.parseString(BOTS));
        assertEquals(2, l.bots().size());
        BotList.Bot b = l.find("ct1");
        assertEquals("Player", b.player());
        assertTrue(b.online());
        assertTrue(b.hasCap("viewer"));
        assertEquals(java.util.List.of("plugin", "proxy", "plugin:other"), b.scopes());
        assertEquals("qs", b.pluginId());
        assertNull(l.find("nope"));
        assertEquals(1, l.indexOf("ct2"));
        assertTrue(BotList.parse(JsonParser.parseString("{}")).bots().isEmpty());
    }

    @Test
    void parsesTheCardFields() {
        String json = "[{\"botId\":\"ct1\",\"player\":\"Ann\",\"online\":true,\"lastSeen\":9,\"summary\":\"Sorting\","
            + "\"plugin\":{\"id\":\"qs\",\"version\":\"1.2\"},\"proxy\":{\"version\":\"0.9\",\"mc\":\"1.21.4\"},"
            + "\"stashes\":[{\"server\":\"s.net\",\"stash\":\"base\"},{\"server\":\"\",\"stash\":\"x\"},7,{\"server\":\"s.net\"}]},"
            + "{\"botId\":\"ct2\",\"proxy\":5,\"stashes\":\"no\"}]";
        BotList l = BotList.parse(JsonParser.parseString(json));
        BotList.Bot b = l.find("ct1");
        assertEquals("0.9", b.proxyVersion());
        assertEquals("1.21.4", b.proxyMc());
        assertEquals(1, b.stashes().size());
        assertEquals("s.net", b.stashes().get(0).server());
        assertEquals("base", b.stashes().get(0).stash());
        assertEquals("s.net\u0000base", b.stashes().get(0).key());
        BotList.Bot c = l.find("ct2");
        assertEquals("", c.proxyVersion());
        assertEquals("", c.proxyMc());
        assertTrue(c.stashes().isEmpty());
    }

    @Test
    void namesAreHiddenInStreamerDisplay() {
        BotList l = BotList.parse(JsonParser.parseString(BOTS));
        assertEquals("Player", l.name(l.find("ct1"), false));
        assertEquals("ct2", l.name(l.find("ct2"), false));
        assertEquals("Bot 1", l.name(l.find("ct1"), true));
        assertEquals("Bot 2", l.name(l.find("ct2"), true));
        assertFalse(l.name(l.find("ct1"), true).contains("Player"));
    }
}
