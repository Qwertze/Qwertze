package com.porter.qwerte.manager.model;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashListTest {
    private static final String LIST = """
        [{"server":"2b2t.org","stash":"main","player":"Qwerttze","owner":null,"online":true,"lastSeen":5,"indexedAt":1767225600000,
          "itemTypes":120,"itemTotal":99999,"rescanPending":null,"rescanBlocked":null,"containers":{"known":120,"total":130,"stale":3},"serverLabel":"Server 1"},
         {"server":"oldfag.org","stash":"alt","player":"","online":false,"indexedAt":null,"rescanPending":7,"rescanBlocked":"sorter off"},
         {"stash":"no server"}, 5, null]
        """;

    @Test
    void parsesTheStashList() {
        List<StashRef> list = StashList.parse(JsonParser.parseString(LIST));
        assertEquals(2, list.size());
        StashRef a = list.get(0);
        assertEquals("2b2t.org", a.server());
        assertTrue(a.online());
        assertTrue(a.hasIndex());
        assertEquals(130, a.containersTotal());
        assertEquals(3, a.containersStale());
        assertEquals(-1, a.rescanPending());
        StashRef b = list.get(1);
        assertFalse(b.hasIndex());
        assertEquals(7, b.rescanPending());
        assertEquals("sorter off", b.rescanBlocked());
        assertEquals(-1, b.containersStale());
        assertFalse(a.key().equals(b.key()));
    }

    @Test
    void notAnArrayIsEmpty() {
        assertTrue(StashList.parse(JsonParser.parseString("{}")).isEmpty());
        assertTrue(StashList.parse(com.google.gson.JsonNull.INSTANCE).isEmpty());
    }

    @Test
    void labelsHideTheServerAndPlayerInStreamerDisplay() {
        StashRef a = StashList.parse(JsonParser.parseString(LIST)).get(0);
        assertEquals("[on] main - Qwerttze @ 2b2t.org", a.label(false));
        assertEquals("[on] main @ Server 1", a.label(true));
        StashRef b = StashList.parse(JsonParser.parseString(LIST)).get(1);
        assertEquals("[off] alt @ oldfag.org", b.label(false));
        assertEquals("[off] alt", b.label(true));
    }

    @Test
    void rescanStatusLine() {
        List<StashRef> list = StashList.parse(JsonParser.parseString(LIST));
        assertEquals("Re-reading containers: 127 / 130", list.get(0).rescanStatus());
        assertEquals("Rescan requested", list.get(1).rescanStatus());
        StashRef blocked = StashList.parse(JsonParser.parseString("[{\"server\":\"s\",\"stash\":\"a\",\"rescanBlocked\":\"sorter off\"}]")).get(0);
        assertEquals("Can't rescan: sorter off", blocked.rescanStatus());
        StashRef idle = StashList.parse(JsonParser.parseString("[{\"server\":\"s\",\"stash\":\"a\"}]")).get(0);
        assertEquals("", idle.rescanStatus());
    }

    @Test
    void displayPolicyIsStreamerWhenForcedOrToggled() {
        assertFalse(new DisplayPolicy(false, false, true).streamer());
        assertTrue(new DisplayPolicy(true, false, true).hideServer());
        assertTrue(new DisplayPolicy(false, true, true).hideCoords());
        assertTrue(new DisplayPolicy(true, false, true).hideNames());
        assertTrue(new DisplayPolicy(true, false, true).hideChat());
        assertFalse(new DisplayPolicy(true, false, true).allowLocalSources());
        assertTrue(new DisplayPolicy(false, false, false).allowLocalSources());
    }
}
