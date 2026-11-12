package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** What the focus key opens, what is remembered per server, and which unread counts the title adds up. */
class FocusAndBadgeTest {
    private static FocusOpens.Target resolve(FocusOpens mode, String last, boolean console, String newest, String specific) {
        return mode.resolve(last, console, newest, specific);
    }

    @Test
    void lastOpenBringsBackWhatWasOpen() {
        assertEquals(FocusOpens.Target.Kind.KEEP, resolve(FocusOpens.LAST_OPEN, null, true, null, "").kind());
        assertEquals(FocusOpens.Target.Kind.GLOBAL, resolve(FocusOpens.LAST_OPEN, LastOpen.GLOBAL, true, null, "").kind());
        assertEquals(FocusOpens.Target.Kind.CONSOLE, resolve(FocusOpens.LAST_OPEN, LastOpen.CONSOLE, true, null, "").kind());
        // the Console is gone when separate-console is off
        assertEquals(FocusOpens.Target.Kind.GLOBAL, resolve(FocusOpens.LAST_OPEN, LastOpen.CONSOLE, false, null, "").kind());
        var dm = resolve(FocusOpens.LAST_OPEN, "steve", true, null, "");
        assertEquals(FocusOpens.Target.Kind.DM, dm.kind());
        assertEquals("steve", dm.name());
        assertFalse(dm.create()); // a conversation that was deleted is not made again
    }

    @Test
    void globalAndConsole() {
        assertEquals(FocusOpens.Target.Kind.GLOBAL, resolve(FocusOpens.GLOBAL, "steve", true, "alex", "bob").kind());
        assertEquals(FocusOpens.Target.Kind.CONSOLE, resolve(FocusOpens.CONSOLE, "steve", true, null, "").kind());
        assertEquals(FocusOpens.Target.Kind.GLOBAL, resolve(FocusOpens.CONSOLE, "steve", false, null, "").kind());
    }

    @Test
    void newestUnreadFallsBackToLastOpen() {
        var t = resolve(FocusOpens.NEWEST_UNREAD, "steve", true, "alex", "");
        assertEquals("alex", t.name());
        assertFalse(t.create());
        var none = resolve(FocusOpens.NEWEST_UNREAD, "steve", true, null, "");
        assertEquals("steve", none.name());
        assertEquals(FocusOpens.Target.Kind.GLOBAL, resolve(FocusOpens.NEWEST_UNREAD, LastOpen.GLOBAL, true, null, "").kind());
    }

    @Test
    void specificDmMakesTheConversationAndFallsBackWhenEmpty() {
        var t = resolve(FocusOpens.SPECIFIC_DM, "steve", true, null, "  Bob_1 ");
        assertEquals(FocusOpens.Target.Kind.DM, t.kind());
        assertEquals("Bob_1", t.name());
        assertTrue(t.create());
        assertEquals("steve", resolve(FocusOpens.SPECIFIC_DM, "steve", true, null, "").name());
        assertEquals("steve", resolve(FocusOpens.SPECIFIC_DM, "steve", true, null, "   ").name());
        assertEquals("steve", resolve(FocusOpens.SPECIFIC_DM, "steve", true, null, null).name());
        // a name that can not be a player name is not opened
        assertEquals("steve", resolve(FocusOpens.SPECIFIC_DM, "steve", true, null, "not a name!").name());
        assertEquals(FocusOpens.Target.Kind.KEEP, resolve(FocusOpens.SPECIFIC_DM, null, true, null, "x").kind());
    }

    @Test
    void lastOpenIsPerServerAndRoundTrips() {
        LastOpen l = new LastOpen();
        assertTrue(l.put("mc.example.net", "steve"));
        assertTrue(l.put("local", LastOpen.GLOBAL));
        assertTrue(l.put("2b2t.org", LastOpen.CONSOLE));
        assertFalse(l.put("2b2t.org", LastOpen.CONSOLE)); // no change
        assertEquals("steve", l.get("mc.example.net"));
        LastOpen back = LastOpen.decode(l.encode());
        assertEquals("steve", back.get("mc.example.net"));
        assertEquals(LastOpen.GLOBAL, back.get("local"));
        assertEquals(LastOpen.CONSOLE, back.get("2b2t.org"));
        assertNull(back.get("other"));
        assertEquals(l.encode(), back.encode());
    }

    @Test
    void lastOpenRefusesBadValuesAndKeepsAtMostTwentyServers() {
        LastOpen l = new LastOpen();
        assertFalse(l.put("bad server", "steve"));
        assertFalse(l.put("a.b", "no good!"));
        assertFalse(l.put("a.b", null));
        assertTrue(LastOpen.decode("garbage;=x;a.b=;a.b=bad name;ok.net=alex").get("ok.net").equals("alex"));
        assertTrue(LastOpen.decode(null).isEmpty());
        for (int i = 0; i < 30; i++) l.put("s" + i + ".net", "alex");
        assertNull(l.get("s0.net"));
        assertEquals("alex", l.get("s29.net"));
        assertEquals(ServerStores.MAX_SERVERS, LastOpen.decode(l.encode()).encode().split(";").length);
    }

    private static ConversationStore store() {
        return new ConversationStore(200);
    }

    @Test
    void newestUnreadIsTheMostRecentUnreadMessage() {
        ConversationStore s = store();
        assertNull(s.newestUnread());
        s.addIncoming("alex", "hi", 1000, false);
        s.addIncoming("steve", "yo", 2000, false);
        assertEquals("steve", s.newestUnread());
        s.markRead("steve");
        assertEquals("alex", s.newestUnread());
        s.addIncoming("alex", "again", 3000, false);
        s.addIncoming("steve", "read", 4000, true); // read at once: not unread
        assertEquals("alex", s.newestUnread());
        s.markRead("alex");
        assertNull(s.newestUnread());
    }

    @Test
    void ourOwnLaterMessageDoesNotMakeAnOlderUnreadNewer() {
        ConversationStore s = store();
        s.addIncoming("alex", "hi", 1000, false);
        s.addIncoming("steve", "yo", 2000, false);
        s.addOutgoing("alex", "answer", 9000, Message.State.SENT);
        assertEquals("steve", s.newestUnread());
    }

    @Test
    void hiddenBadgesLeaveTheTitleTotalButNotTheCounting() {
        ConversationStore s = store();
        s.addIncoming("alex", "hi", 1000, false);
        s.addIncoming("alex", "hi2", 1100, false);
        s.addConsole("line", 1200, false);
        assertEquals(3, s.totalUnread());
        assertEquals(3, s.visibleUnread(true, true, true));
        assertEquals(1, s.visibleUnread(true, true, false));
        assertEquals(2, s.visibleUnread(true, false, true));
        assertEquals(0, s.visibleUnread(true, false, false));
        assertEquals(3, s.visibleUnread(false, true, true)); // Global has no count
        // nothing was lost: turning them back on shows the right numbers
        assertEquals(3, s.visibleUnread(true, true, true));
        assertEquals(2, s.get("alex").unread());
        assertEquals(1, s.consoleUnread());
    }

    @Test
    void aConsoleThatIsOffAddsNothing() {
        ConversationStore s = store();
        s.addConsole("line", 1, false);
        s.consoleVisible(false);
        assertEquals(0, s.visibleUnread(true, true, true));
    }
}
