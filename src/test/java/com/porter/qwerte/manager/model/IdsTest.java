package com.porter.qwerte.manager.model;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdsTest {
    @Test
    void onlyPlainIdsPass() {
        assertTrue(Ids.valid("ct113"));
        assertTrue(Ids.valid("u-0123456789ab"));
        assertTrue(Ids.valid("b_Abc-9"));
        assertTrue(Ids.valid("x".repeat(64)));
        assertFalse(Ids.valid("x".repeat(65)));
        assertFalse(Ids.valid(""));
        assertFalse(Ids.valid(null));
        assertFalse(Ids.valid("../etc"));
        assertFalse(Ids.valid("a/b"));
        assertFalse(Ids.valid("a b"));
        assertFalse(Ids.valid("a?x=1"));
        assertFalse(Ids.valid("a%2f"));
        assertFalse(Ids.valid("café"));
    }

    @Test
    void botsAndUsersWithOddIdsAreDropped() {
        BotList bots = BotList.parse(JsonParser.parseString("[{\"botId\":\"ok1\"},{\"botId\":\"../x\"},{\"botId\":\"a/b\"},{\"botId\":\"good_2\"}]"));
        assertEquals(2, bots.bots().size());
        assertEquals("good_2", bots.bots().get(1).botId());
        UsersModel.Data d = UsersModel.parse(JsonParser.parseString(
            "{\"users\":[{\"id\":\"u-1\",\"name\":\"a\"},{\"id\":\"u/1\",\"name\":\"b\"},{\"id\":\"\",\"name\":\"c\"}]}"));
        assertEquals(1, d.users().size());
        assertEquals("u-1", d.users().get(0).id());
    }
}
