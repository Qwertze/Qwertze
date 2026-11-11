package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The separate Console: routing of every line type, the store, the history file, and the theme defaults. */
class ConsoleTest {
    private static LineKind.Type type(String tag, String key, String text) {
        return LineKind.type(tag, key, text, LineKind.NOTICE_PREFIXES);
    }

    // ---------- line types ----------

    @Test
    void lineTypes() {
        assertEquals(LineKind.Type.PLAYER, type(null, "chat.type.text", "<Steve> hi"));
        assertEquals(LineKind.Type.PLAYER, type("System", null, "<Steve> hi"));
        assertEquals(LineKind.Type.PLAYER, type("System", null, "Steve: hi"));
        assertEquals(LineKind.Type.NOTICE, type(null, null, "[Meteor] Toggled Messenger on."));
        assertEquals(LineKind.Type.NOTICE, type("System", null, "[Baritone] Done"));
        assertEquals(LineKind.Type.NOTICE, type("Meteor", null, "anything"));
        assertEquals(LineKind.Type.JOIN_LEAVE, type("System", "multiplayer.player.joined", "Steve joined the game"));
        assertEquals(LineKind.Type.JOIN_LEAVE, type("System", "multiplayer.player.left", "Steve left the game"));
        assertEquals(LineKind.Type.JOIN_LEAVE, type("System", null, "Steve joined the game"));
        assertEquals(LineKind.Type.DEATH, type("System", "death.attack.player", "Steve was slain by Alex"));
        assertEquals(LineKind.Type.ADVANCEMENT, type("System", "chat.type.advancement.task", "Steve has made the advancement [Stone Age]"));
        assertEquals(LineKind.Type.ADVANCEMENT, type("System", null, "Steve has made the advancement [Stone Age]"));
        assertEquals(LineKind.Type.SERVER, type("System", null, "Server restarting in 5 minutes"));
        assertEquals(LineKind.Type.SERVER, type(null, "chat.type.admin", "[Server: x]"));
        // a death sent as plain text can not be told from another server line
        assertEquals(LineKind.Type.SERVER, type("System", null, "Katamarzy was demolished by a piglin"));
    }

    @Test
    void playerExactlyWhenNotConsole() {
        String[][] cases = {
            {null, null, "<Steve> hi"}, {"System", null, "Steve: hi"}, {"System", null, "Server restarting"},
            {null, null, "[Meteor] x"}, {"System", "death.x", "x died"}, {null, "chat.type.emote", "* Steve waves"},
        };
        for (String[] c : cases) {
            boolean console = LineKind.isConsole(c[0], c[1], c[2], LineKind.NOTICE_PREFIXES);
            assertEquals(console, type(c[0], c[1], c[2]) != LineKind.Type.PLAYER, c[2]);
        }
    }

    // ---------- routing ----------

    @Test
    void offKeepsEverythingInGlobal() {
        for (LineKind.Type t : LineKind.Type.values()) {
            assertEquals(ConsoleRoute.Target.GLOBAL, ConsoleRoute.route(false, t, ConsoleRoute.Keep.NONE), t.name());
            assertEquals(ConsoleRoute.Target.GLOBAL, ConsoleRoute.route(false, t, new ConsoleRoute.Keep(true, true)), t.name());
        }
        assertEquals(ConsoleRoute.Target.GLOBAL, ConsoleRoute.routeNote(false));
    }

    @Test
    void onSendsConsoleLinesToTheConsoleAndPlayerChatToGlobal() {
        var keep = ConsoleRoute.Keep.NONE;
        assertEquals(ConsoleRoute.Target.GLOBAL, ConsoleRoute.route(true, LineKind.Type.PLAYER, keep));
        for (LineKind.Type t : List.of(LineKind.Type.NOTICE, LineKind.Type.JOIN_LEAVE, LineKind.Type.DEATH, LineKind.Type.ADVANCEMENT, LineKind.Type.SERVER)) {
            assertEquals(ConsoleRoute.Target.CONSOLE, ConsoleRoute.route(true, t, keep), t.name());
        }
        assertEquals(ConsoleRoute.Target.CONSOLE, ConsoleRoute.routeNote(true));
    }

    @Test
    void keepTogglesOnlyAffectJoinsLeavesAndDeaths() {
        var joins = new ConsoleRoute.Keep(true, false);
        assertEquals(ConsoleRoute.Target.GLOBAL, ConsoleRoute.route(true, LineKind.Type.JOIN_LEAVE, joins));
        assertEquals(ConsoleRoute.Target.CONSOLE, ConsoleRoute.route(true, LineKind.Type.DEATH, joins));
        var deaths = new ConsoleRoute.Keep(false, true);
        assertEquals(ConsoleRoute.Target.GLOBAL, ConsoleRoute.route(true, LineKind.Type.DEATH, deaths));
        assertEquals(ConsoleRoute.Target.CONSOLE, ConsoleRoute.route(true, LineKind.Type.JOIN_LEAVE, deaths));
        assertEquals(ConsoleRoute.Target.CONSOLE, ConsoleRoute.route(true, LineKind.Type.NOTICE, new ConsoleRoute.Keep(true, true)));
        assertEquals(ConsoleRoute.Target.CONSOLE, ConsoleRoute.route(true, LineKind.Type.ADVANCEMENT, new ConsoleRoute.Keep(true, true)));
    }

    // ---------- the store ----------

    @Test
    void consoleLinesNeverMixIntoGlobalOrWhispers() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        assertTrue(core.receiveConsole("[Meteor] Toggled x on.", 1000, false));
        assertTrue(core.receiveGlobal("<Steve> hi", 2000, false));
        assertEquals(1, core.store().console().size());
        assertEquals(1, core.store().global().size());
        assertEquals("<Steve> hi", core.store().global().get(0).text());
        assertTrue(core.store().console().get(0).console());
        assertTrue(core.store().all().isEmpty());
    }

    @Test
    void consoleUnreadCountsLikeOtherConversations() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.receiveConsole("line one", 1000, false);
        core.receiveConsole("line two", 2000, false);
        assertEquals(2, core.store().consoleUnread());
        assertEquals(2, core.store().totalUnread());
        core.receiveConsole("line three", 3000, true); // open in front of the user: read
        assertEquals(2, core.store().consoleUnread());
        core.store().markConsoleRead();
        assertEquals(0, core.store().consoleUnread());
        assertEquals(0, core.store().totalUnread());
    }

    @Test
    void sameLineTwiceIsOneLineInTheConsoleToo() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        assertTrue(core.receiveConsole("[Meteor] x", 1000, false));
        assertFalse(core.receiveConsole("<15:05> [Meteor] x", 1100, false));
        assertEquals(1, core.store().console().size());
    }

    @Test
    void notesGoToTheConsoleOnlyWhenSeparate() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.separateConsole(true);
        core.sendGlobal("/home", 0);
        core.tick(10_000, new MessengerCore.Sender() {
            @Override
            public boolean command(String command) {
                return true;
            }

            @Override
            public boolean chat(String text) {
                return true;
            }
        });
        assertEquals(1, core.store().console().size());
        assertEquals(Direction.NOTE, core.store().console().get(0).direction());
        assertTrue(core.store().global().isEmpty());
        assertEquals(0, core.store().consoleUnread()); // notes are never unread
    }

    @Test
    void clearConsoleAndCap() {
        ConversationStore s = new ConversationStore(50);
        s.globalCap(50);
        for (int i = 0; i < 80; i++) s.addConsole("l" + i, i, true);
        assertEquals(50, s.console().size());
        assertEquals("l79", s.console().get(49).text());
        s.clearConsole();
        assertTrue(s.console().isEmpty());
        assertEquals(0, s.consoleUnread());
    }

    // ---------- the history file ----------

    @Test
    void consoleRoundTripsUnderItsOwnKey() {
        ServerStores a = new ServerStores(50);
        a.switchTo("2b2t.org");
        ConversationStore s = a.current();
        s.addGlobal("<Steve> hi", 10);
        s.addConsole("Steve joined the game", 20, true);
        s.addConsoleNote("a note", 30);
        String json = HistoryJson.toJson(a, HistoryJson.MAX_BYTES);
        assertTrue(json.contains("\"console\""));
        assertTrue(json.contains("\"global\""));
        assertFalse(json.contains("a note"));

        ServerStores b = new ServerStores(50);
        HistoryJson.fromJson(json, b, "local");
        assertEquals(1, b.store("2b2t.org").global().size());
        assertEquals("<Steve> hi", b.store("2b2t.org").global().get(0).text());
        List<Message> c = b.store("2b2t.org").console();
        assertEquals(1, c.size());
        assertEquals("Steve joined the game", c.get(0).text());
        assertTrue(c.get(0).console());
        assertFalse(b.dirty());
        assertEquals(0, b.store("2b2t.org").consoleUnread());
    }

    @Test
    void aFileWithoutConsoleStillLoads() {
        ServerStores a = new ServerStores(50);
        a.switchTo("x.org");
        a.current().addGlobal("only global", 10);
        String json = HistoryJson.toJson(a, HistoryJson.MAX_BYTES);
        assertFalse(json.contains("\"console\""));
        ServerStores b = new ServerStores(50);
        HistoryJson.fromJson(json, b, "local");
        assertEquals(1, b.store("x.org").global().size());
        assertTrue(b.store("x.org").console().isEmpty());
    }

    @Test
    void consoleOnlyServerIsSaved() {
        ServerStores a = new ServerStores(50);
        a.switchTo("y.org");
        a.current().addConsole("[Meteor] x", 10, true);
        String json = HistoryJson.toJson(a, HistoryJson.MAX_BYTES);
        ServerStores b = new ServerStores(50);
        HistoryJson.fromJson(json, b, "local");
        assertEquals(1, b.store("y.org").console().size());
    }

    @Test
    void mergeBringsInConsoleLines() {
        ConversationStore x = new ConversationStore(50);
        ConversationStore y = new ConversationStore(50);
        x.addConsole("one", 1, true);
        y.addConsole("one", 1, true);
        y.addConsole("two", 2, true);
        x.mergeFrom(y);
        assertEquals(2, x.console().size());
    }

    // ---------- theme ----------

    @Test
    void consoleThemeColoursAreUnsetByDefaultAndFallBackToTheTextColours() {
        // unset (alpha 0) means "use text / dim-text", so a user who changed those sees no change from the new parts
        assertEquals(ThemeColor.TEXT, ThemeColor.CONSOLE_TEXT.fallback());
        assertEquals(ThemeColor.DIM, ThemeColor.CONSOLE_NOTE.fallback());
        assertEquals(ThemeColor.DIM, ThemeColor.CONSOLE_NAME.fallback());
        for (ThemeColor c : ThemeColor.values()) {
            if (c.fallback() == null) {
                assertEquals(255, c.defaultAlpha(), c.name());
                assertEquals(0xFF, c.defaultArgb() >>> 24, c.name());
            } else {
                assertEquals(0, c.defaultAlpha(), c.name());
                assertEquals(0, c.defaultArgb() >>> 24, c.name());
                assertNull(c.fallback().fallback(), c.name()); // one step only
            }
        }
        // the colours behind the fallback are the old look
        assertEquals(0xFFE6EAF0, ThemeColor.TEXT.defaultArgb());
        assertEquals(0xFF8A94A0, ThemeColor.DIM.defaultArgb());
    }

    @Test
    void staleConsoleUnreadGoesWhenTheConsoleIsTurnedOff() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.separateConsole(true);
        core.receiveConsole("a", 1000, false);
        core.receiveConsole("b", 2000, false);
        assertEquals(2, core.store().totalUnread());
        core.separateConsole(false);
        assertEquals(0, core.store().consoleUnread());
        assertEquals(0, core.store().totalUnread());
        core.receiveConsole("c", 3000, false); // nothing routes here when off, but it must not count if it does
        assertEquals(0, core.store().totalUnread());
        core.separateConsole(true); // turned on again: the old count stays forgotten
        assertEquals(0, core.store().totalUnread());
        core.receiveConsole("d", 4000, false);
        assertEquals(1, core.store().totalUnread());
    }

    @Test
    void aStoreMadeWhileOffInheritsIt() {
        ServerStores s = new ServerStores(50);
        s.consoleVisible(false);
        ConversationStore other = s.store("other.org");
        other.addConsole("x", 1, false);
        assertEquals(0, other.totalUnread());
    }

    @Test
    void overlayMergeKeepsTimeOrderAndTiesGlobalFirst() {
        List<Message> g = List.of(new Message(Direction.IN, "g1", 10, Message.State.SENT), new Message(Direction.IN, "g2", 30, Message.State.SENT));
        List<Message> c = List.of(new Message(Direction.IN, "c1", 20, Message.State.SENT), new Message(Direction.IN, "c2", 30, Message.State.SENT), new Message(Direction.IN, "c3", 40, Message.State.SENT));
        List<Message> out = new java.util.ArrayList<>(List.of(new Message(Direction.IN, "old", 0, Message.State.SENT)));
        TimeMerge.merge(g, c, out);
        assertEquals(List.of("g1", "c1", "g2", "c2", "c3"), out.stream().map(Message::text).toList());
        TimeMerge.merge(List.of(), List.of(), out);
        assertTrue(out.isEmpty());
        TimeMerge.merge(List.of(), c, out);
        assertEquals(3, out.size());
    }

    @Test
    void consoleColoursAreInTheMeteorMapping() {
        var m = MessengerTheme.fromMeteor(MessengerTheme.Palette.meteorDefault());
        assertEquals(m.get(ThemeColor.TEXT), m.get(ThemeColor.CONSOLE_TEXT));
        assertEquals(m.get(ThemeColor.DIM), m.get(ThemeColor.CONSOLE_NOTE));
        assertEquals(m.get(ThemeColor.DIM), m.get(ThemeColor.CONSOLE_NAME));
        var names = new java.util.HashSet<String>();
        for (ThemeColor c : ThemeColor.values()) assertTrue(names.add(c.settingName()), c.settingName());
    }
}
