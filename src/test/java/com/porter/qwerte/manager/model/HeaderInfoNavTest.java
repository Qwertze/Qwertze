package com.porter.qwerte.manager.model;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeaderInfoNavTest {
    private static StashRef stash(boolean online, String player, long indexedAt, long lastSeen, long total, long stale) {
        return new StashRef("srv", "base", "Server 1", player, "", online, indexedAt, lastSeen, 0, 0, -1, "", total, stale);
    }

    private static IndexModel index(String dim, String mc, long known, long total, long stale) {
        String json = "{\"mcVersion\":\"" + mc + "\",\"dimension\":\"" + dim + "\",\"containers\":{\"known\":" + known + ",\"total\":" + total
            + ",\"stale\":" + stale + "},\"items\":[],\"shulkers\":[]}";
        return IndexModel.parse(JsonParser.parseString(json));
    }

    private static AuthState auth(String json) {
        return AuthState.parse(JsonParser.parseString(json));
    }

    @Test
    void indexedSaysAgoOnlyOnce() {
        long now = 10_000_000L;
        assertEquals("Indexed 1 h ago", HeaderInfo.indexed(stash(true, "", now - 3_600_000, now, -1, -1), now));
        assertEquals("Indexed just now", HeaderInfo.indexed(stash(true, "", now - 1000, now, -1, -1), now));
        assertEquals("No index yet", HeaderInfo.indexed(stash(true, "", -1, now, -1, -1), now));
        assertFalse(HeaderInfo.indexed(stash(true, "", now - 90_000, now, -1, -1), now).contains("ago ago"));
    }

    @Test
    void theMetaLineIsTheWebsitesLine() {
        long now = 10_000_000L;
        StashRef s = stash(true, "", now - 3_600_000, now, 48, 1);
        IndexModel idx = index("minecraft:overworld", "1.21.4", 47, 48, 1);
        assertEquals("Indexed 1 h ago · Containers: 47 / 48 known · 1 stale · minecraft:overworld · MC 1.21.4",
            HeaderInfo.indexLine(s, idx, false, now));
        // a user the owner forced into streamer mode doesn't see the dimension
        assertEquals("Indexed 1 h ago · Containers: 47 / 48 known · 1 stale · MC 1.21.4", HeaderInfo.indexLine(s, idx, true, now));
        // before the index is loaded the stash entry's counts are used
        assertEquals("Indexed 1 h ago · Containers: 48 · 1 stale", HeaderInfo.indexLine(s, null, false, now));
        assertEquals("", HeaderInfo.indexLine(null, idx, false, now));
    }

    @Test
    void aBadDimensionIsDropped() {
        assertEquals("", index("<b>x</b>", "1.21.4", 1, 1, 0).dimension());
        assertEquals("minecraft:the_nether", index("minecraft:the_nether", "1.21.4", 1, 1, 0).dimension());
        assertEquals("", IndexModel.empty().dimension());
    }

    @Test
    void botStatusFollowsTheWebsite() {
        long now = 10_000_000L;
        assertEquals("Steve online · last seen 30 s ago", HeaderInfo.botStatus(stash(true, "Steve", 1, now - 30_000, -1, -1), false, now));
        assertEquals("Bot offline · last seen 3 min ago", HeaderInfo.botStatus(stash(false, "Steve", 1, now - 190_000, -1, -1), true, now));
        assertEquals("Bot online · last seen never", HeaderInfo.botStatus(stash(true, "", 1, -1, -1, -1), false, now));
        assertEquals("No stash", HeaderInfo.botStatus(null, false, now));
    }

    @Test
    void anOwnerSeesTheWebsitesPagesInOrder() {
        AuthState owner = auth("{\"loggedIn\":true,\"user\":{\"id\":\"owner\",\"name\":\"boss\",\"owner\":true,\"perms\":{}}}");
        assertEquals(List.of(NavPage.STASH, NavPage.ORDERS, NavPage.PLANNER, NavPage.BOTS, NavPage.ACTIVITY, NavPage.LIVE, NavPage.SETTINGS,
            NavPage.USERS, NavPage.PROXY, NavPage.CONSOLE), NavPage.visible(owner, true, true));
        // no bot: no Proxy; a bot without activity: no Activity page
        assertEquals(List.of(NavPage.STASH, NavPage.ORDERS, NavPage.PLANNER, NavPage.BOTS, NavPage.LIVE, NavPage.SETTINGS, NavPage.USERS, NavPage.CONSOLE),
            NavPage.visible(owner, false, false));
    }

    @Test
    void aLimitedUserSeesOnlyWhatTheirPermissionsAllow() {
        AuthState user = auth("{\"loggedIn\":true,\"user\":{\"id\":\"u1\",\"name\":\"bob\",\"owner\":false,\"perms\":{\"view\":true,\"viewer\":true,\"proxy\":\"read\"}}}");
        assertEquals(List.of(NavPage.STASH, NavPage.ORDERS, NavPage.LIVE, NavPage.SETTINGS, NavPage.PROXY), NavPage.visible(user, true, true));
        assertTrue(NavPage.visible(null, true, true).isEmpty());
    }

    @Test
    void proxyAndConsoleAreHiddenForAForcedStreamer() {
        AuthState forced = auth("{\"loggedIn\":true,\"user\":{\"id\":\"u1\",\"name\":\"bob\",\"owner\":false,\"perms\":{\"streamer\":true,\"proxy\":\"read\",\"console\":\"full\"}}}");
        assertEquals(List.of(NavPage.STASH, NavPage.ORDERS, NavPage.SETTINGS), NavPage.visible(forced, true, true));
    }

    @Test
    void websitePagesSayWhereTheyAre() {
        assertTrue(NavPage.PLANNER.inGame());
        assertTrue(NavPage.ACTIVITY.inGame());
        assertTrue(NavPage.CONSOLE.inGame());
        assertTrue(NavPage.STASH.inGame());
        assertTrue(NavPage.PROXY.inGame());
        assertTrue(NavPage.BOTS.inGame());
        assertEquals("Build Planner is only on the website. Open http://10.0.0.2:8765 in a browser.", NavPage.PLANNER.websiteNote("http://10.0.0.2:8765"));
        assertEquals("Build Planner is only on the website.", NavPage.PLANNER.websiteNote(null));
    }
}
