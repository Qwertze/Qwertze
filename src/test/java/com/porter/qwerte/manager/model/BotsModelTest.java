package com.porter.qwerte.manager.model;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotsModelTest {
    private static StashRef stash(String json) {
        return StashRef.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    private static AuthState auth(String perms, boolean owner) {
        return AuthState.parse(JsonParser.parseString("{\"loggedIn\":true,\"user\":{\"id\":\"u1\",\"name\":\"bob\",\"owner\":" + owner + ",\"perms\":" + perms + "}}"));
    }

    private static BotList.Bot bot(String caps) {
        String json = "[{\"botId\":\"ct1\",\"player\":\"Ann\",\"caps\":" + caps + "}]";
        return BotList.parse(JsonParser.parseString(json)).find("ct1");
    }

    private static List<NavPage> pages(BotList.Bot b, AuthState a) {
        return BotsModel.jumps(b, a).stream().map(BotsModel.Jump::page).toList();
    }

    @Test
    void stashFieldsOfTheBotOverviewAreRead() {
        StashRef s = stash("{\"server\":\"s\",\"stash\":\"base\",\"owner\":\"Ann\",\"delivery\":{\"x\":10,\"y\":64,\"z\":-5},"
            + "\"orders\":{\"open\":3,\"active\":{\"id\":\"o7\",\"status\":\"in_progress\",\"phase\":\"\"}},"
            + "\"conflict\":{\"player\":\"Bob\",\"at\":1000}}");
        assertEquals(3, s.bot().openOrders());
        assertEquals("Order o7: In progress", s.bot().activeOrder());
        assertEquals("10, 64, -5", BotsModel.delivery(s));
        assertEquals("Bob", s.bot().conflictPlayer());
        assertEquals(1000, s.bot().conflictAt());
    }

    @Test
    void missingOverviewFieldsAreHarmless() {
        StashRef s = stash("{\"server\":\"s\",\"stash\":\"base\",\"orders\":5,\"conflict\":null,\"delivery\":null}");
        assertEquals(0, s.bot().openOrders());
        assertEquals("", s.bot().activeOrder());
        assertEquals("not set", BotsModel.delivery(s));
        assertEquals("", BotsModel.conflict(s, 5));
        assertEquals(new BotsModel.Doing("Idle", false), BotsModel.doing(s));
    }

    @Test
    void doingFollowsTheWebsitesOrder() {
        assertEquals(new BotsModel.Doing("Order o1: Walking", false),
            BotsModel.doing(stash("{\"server\":\"s\",\"stash\":\"a\",\"orderBlocker\":\"no shulkers\",\"orders\":{\"open\":1,\"active\":{\"id\":\"o1\",\"status\":\"accepted\",\"phase\":\"Walking\"}}}")));
        assertEquals(new BotsModel.Doing("Can't take orders: no shulkers", true),
            BotsModel.doing(stash("{\"server\":\"s\",\"stash\":\"a\",\"orderBlocker\":\"no shulkers\",\"rescanPending\":3}")));
        assertEquals(new BotsModel.Doing("Rescan requested", false), BotsModel.doing(stash("{\"server\":\"s\",\"stash\":\"a\",\"rescanPending\":3}")));
        assertEquals(new BotsModel.Doing("Can't rescan: busy", true), BotsModel.doing(stash("{\"server\":\"s\",\"stash\":\"a\",\"rescanBlocked\":\"busy\"}")));
        assertEquals(new BotsModel.Doing("Re-reading containers: 8 / 10", false),
            BotsModel.doing(stash("{\"server\":\"s\",\"stash\":\"a\",\"containers\":{\"total\":10,\"stale\":2}}")));
    }

    @Test
    void conflictShowsForADayOnly() {
        StashRef s = stash("{\"server\":\"s\",\"stash\":\"base\",\"conflict\":{\"player\":\"Bob\",\"at\":1000}}");
        String text = BotsModel.conflict(s, 2000);
        assertTrue(text.contains("Bob") && text.contains("'base'"));
        assertEquals("", BotsModel.conflict(s, 1000 + BotsModel.CONFLICT_SHOW_MS + 1));
    }

    @Test
    void releaseNeedsAnOwnerAndTheLinkPermission() {
        StashRef owned = stash("{\"server\":\"s\",\"stash\":\"base\",\"owner\":\"Ann\"}");
        StashRef free = stash("{\"server\":\"s\",\"stash\":\"base\"}");
        AuthState owner = auth("{}", true);
        AuthState linker = auth("{\"link\":true}", false);
        AuthState plain = auth("{}", false);
        assertTrue(BotsModel.canRelease(owned, owner));
        assertTrue(BotsModel.canRelease(owned, linker));
        assertFalse(BotsModel.canRelease(owned, plain));
        assertFalse(BotsModel.canRelease(free, owner));
        assertFalse(BotsModel.canRelease(owned, null));
        assertEquals("(no owner)", BotsModel.who(free));
        assertEquals("Ann", BotsModel.who(owned));
        assertTrue(BotsModel.releaseWarning(owned).contains("'base'"));
    }

    @Test
    void streamerModeLeavesOutOwnerStashServerAndOtherPlayer() {
        StashRef s = stash("{\"server\":\"play.example.org\",\"stash\":\"basestash\",\"owner\":\"AnnOwner\",\"conflict\":{\"player\":\"BobRival\",\"at\":1000}}");
        String warning = BotsModel.releaseWarning(s, true);
        String conflict = BotsModel.conflict(s, 2000, true);
        String notice = BotsModel.releasedNotice(s, true);
        for (String text : new String[]{warning, conflict, notice, BotsModel.stashName(s, true)}) {
            assertFalse(text.contains("AnnOwner") || text.contains("basestash") || text.contains("BobRival") || text.contains("example"), text);
        }
        assertTrue(warning.contains("this bot"));
        assertFalse(conflict.isEmpty());
        // not hidden: the same texts name them, and the old signatures still do
        assertTrue(BotsModel.releaseWarning(s, false).contains("AnnOwner"));
        assertTrue(BotsModel.conflict(s, 2000, false).contains("BobRival"));
        assertEquals("Released 'basestash'.", BotsModel.releasedNotice(s, false));
        assertEquals("basestash", BotsModel.stashName(s, false));
        assertEquals(BotsModel.releaseWarning(s), BotsModel.releaseWarning(s, false));
        // a conflict that is over stays empty either way
        assertEquals("", BotsModel.conflict(s, 1000 + BotsModel.CONFLICT_SHOW_MS + 1, true));
    }

    @Test
    void cardButtonsNeedTheCapabilityAndThePermission() {
        BotList.Bot all = bot("[\"activity\",\"settings\",\"proxy\",\"console\"]");
        AuthState owner = auth("{}", true);
        assertEquals(List.of(NavPage.ACTIVITY, NavPage.SETTINGS, NavPage.PROXY, NavPage.CONSOLE), pages(all, owner));

        // no proxy and console permission: only activity and settings
        AuthState limited = auth("{\"activity\":true}", false);
        assertEquals(List.of(NavPage.ACTIVITY, NavPage.SETTINGS), pages(all, limited));

        // the bot lacks the capability
        assertEquals(List.of(NavPage.SETTINGS), pages(bot("[\"settings\"]"), owner));
        assertTrue(pages(bot("[]"), owner).isEmpty());
    }

    @Test
    void metaLineIsTheWebsitesLine() {
        BotList.Bot b = BotList.parse(JsonParser.parseString("[{\"botId\":\"ct1\",\"plugin\":{\"id\":\"qs\",\"version\":\"1.2\"},\"proxy\":{\"version\":\"0.9\",\"mc\":\"1.21.4\"}}]")).find("ct1");
        assertEquals("Plugin qs 1.2 · proxy 0.9 (MC 1.21.4) · id ct1", BotsModel.meta(b));
        BotList.Bot bare = BotList.parse(JsonParser.parseString("[{\"botId\":\"ct2\"}]")).find("ct2");
        assertEquals("Plugin ? · proxy ? · id ct2", BotsModel.meta(bare));
    }
}
