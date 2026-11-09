package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Global (public chat) view: what may be sent and how, the log and its cap, saving and loading. */
class GlobalTest {
    /** Records what would go to the game, in order. */
    private static final class Recorder implements MessengerCore.Sender {
        final List<String> sent = new ArrayList<>();
        boolean connected = true;

        @Override
        public boolean command(String command) {
            if (!connected) return false;
            sent.add("cmd:" + command);
            return true;
        }

        @Override
        public boolean chat(String text) {
            if (!connected) return false;
            sent.add("chat:" + text);
            return true;
        }
    }

    // ---------- what is sent ----------

    @Test
    void chatIsSentAsTyped() {
        GlobalSend.Result r = GlobalSend.prepare("  Hello  world! :) 😀 ");
        assertTrue(r.ok());
        assertEquals(GlobalSend.Kind.CHAT, r.kind());
        assertEquals("Hello  world! :) 😀", r.payload()); // only the ends are cut, inner spaces stay
    }

    @Test
    void aSlashMakesACommandWithoutTheSlash() {
        GlobalSend.Result r = GlobalSend.prepare("/kit starter");
        assertEquals(GlobalSend.Kind.COMMAND, r.kind());
        assertEquals("kit starter", r.payload());
        assertEquals("/kit ...", r.label()); // arguments are never shown
        assertEquals("/spawn", GlobalSend.prepare("/spawn").label());
    }

    @Test
    void emptyOrBareSlashIsRefused() {
        assertFalse(GlobalSend.prepare("").ok());
        assertFalse(GlobalSend.prepare("   ").ok());
        assertFalse(GlobalSend.prepare(null).ok());
        assertFalse(GlobalSend.prepare("/").ok());
        assertFalse(GlobalSend.prepare("/   ").ok());
    }

    @Test
    void textMinecraftWouldRefuseIsRefusedNotChanged() {
        assertFalse(GlobalSend.prepare("a§cb").ok());
        assertFalse(GlobalSend.prepare("a\nb").ok());
        assertFalse(GlobalSend.prepare("a\u0000b").ok());
        assertFalse(GlobalSend.prepare("a\u007fb").ok());
        assertFalse(GlobalSend.prepare("a\uD83Dc").ok()); // half an emoji
        assertTrue(GlobalSend.prepare("ok 😀").ok());
    }

    @Test
    void lengthLimitIs256() {
        assertTrue(GlobalSend.prepare("x".repeat(256)).ok());
        assertFalse(GlobalSend.prepare("x".repeat(257)).ok());
        assertFalse(GlobalSend.prepare("/" + "x".repeat(256)).ok());
    }

    @Test
    void globalGoesThroughTheSameRateLimitAsWhispers() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.intervalMs(1500);
        assertNull(core.sendGlobal("hello", 0));
        assertNull(core.send("Steve", "psst", 1));
        assertNull(core.sendGlobal("/home", 2));
        Recorder rec = new Recorder();
        core.tick(0, rec);
        core.tick(1000, rec);
        assertEquals(List.of("chat:hello"), rec.sent); // one per interval, whatever it is
        core.tick(1500, rec);
        core.tick(2999, rec);
        core.tick(3000, rec);
        assertEquals(List.of("chat:hello", "cmd:msg Steve psst", "cmd:home"), rec.sent);
    }

    @Test
    void nothingIsSentWithoutBeingQueuedAndNothingIsSentTwice() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        Recorder rec = new Recorder();
        core.tick(0, rec);
        core.tick(10_000, rec);
        assertTrue(rec.sent.isEmpty());
        core.sendGlobal("once", 0);
        core.tick(0, rec);
        core.tick(10_000, rec);
        core.tick(20_000, rec);
        assertEquals(List.of("chat:once"), rec.sent);
    }

    @Test
    void aFailedSendIsNotRetried() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        Recorder rec = new Recorder();
        rec.connected = false;
        core.sendGlobal("hi", 0);
        core.tick(0, rec);
        rec.connected = true;
        core.tick(10_000, rec);
        assertTrue(rec.sent.isEmpty());
        List<Message> g = core.store().global();
        assertEquals(Direction.NOTE, g.get(g.size() - 1).direction());
        assertTrue(g.get(g.size() - 1).text().startsWith("Not sent"));
    }

    @Test
    void cancelledMessagesAreNotSentLater() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.sendGlobal("a", 0);
        core.sendGlobal("/b", 0);
        assertTrue(core.globalQueued());
        core.cancelQueued();
        assertFalse(core.globalQueued());
        Recorder rec = new Recorder();
        core.tick(100_000, rec);
        assertTrue(rec.sent.isEmpty());
        assertEquals(2, core.store().global().size()); // a "not sent" note for each
    }

    @Test
    void theQueueIsSharedAndLimited() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        for (int i = 0; i < Outbox.MAX_QUEUE; i++) assertNull(core.sendGlobal("m" + i, i));
        assertNotNull(core.sendGlobal("extra", 10));
        assertNotNull(core.send("Steve", "extra", 10));
    }

    @Test
    void aSentCommandLeavesOnlyItsWordInTheLog() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.sendGlobal("/login hunter2", 0);
        core.tick(0, new Recorder());
        assertFalse(core.store().global().isEmpty());
        for (Message m : core.store().global()) assertFalse(m.text().contains("hunter2"));
    }

    @Test
    void aSentChatMessageIsNotAddedLocally() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.sendGlobal("hello", 0);
        core.tick(0, new Recorder());
        assertTrue(core.store().global().isEmpty()); // the server's own copy is what appears
    }

    // ---------- the log ----------

    @Test
    void linesArriveAsPlainTextAndTheOldestGoAtTheCap() {
        ConversationStore s = new ConversationStore(50);
        s.globalCap(50);
        for (int i = 0; i < 80; i++) s.addGlobal("line " + i, i);
        assertEquals(50, s.global().size());
        assertEquals("line 30", s.global().get(0).text());
        assertEquals("line 79", s.global().get(49).text());
        s.globalCap(1); // clamped to the minimum
        assertEquals(ConversationStore.MIN_GLOBAL_CAP, s.globalCap());
        s.globalCap(5000);
        assertEquals(ConversationStore.MAX_GLOBAL_CAP, s.globalCap());
    }

    @Test
    void loweringTheCapCutsAtOnce() {
        ConversationStore s = new ConversationStore(50);
        s.globalCap(200);
        for (int i = 0; i < 150; i++) s.addGlobal("l" + i, i);
        s.globalCap(60);
        assertEquals(60, s.global().size());
        assertEquals("l149", s.global().get(59).text());
    }

    @Test
    void receiveGlobalCleansAndSkipsEmpty() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.receiveGlobal("§a<Steve>§r hi", 1);
        core.receiveGlobal("", 2);
        core.receiveGlobal("   ", 3);
        assertEquals(1, core.store().global().size());
        assertEquals("<Steve> hi", core.store().global().get(0).text());
    }

    @Test
    void globalIsPerServerAndNotAPlayerNamedGlobal() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.switchServer("a.example");
        core.receiveGlobal("on a", 1);
        core.switchServer("b.example");
        assertTrue(core.store().global().isEmpty());
        core.switchServer("a.example");
        assertEquals(1, core.store().global().size());
        assertNull(core.store().get("Global")); // a player named Global is a different thing than the Global view
        assertNotNull(core.store().open("Global", 1));
        assertEquals(1, core.store().global().size());
    }

    @Test
    void clearGlobalKeepsTheConversations() {
        ConversationStore s = new ConversationStore(50);
        s.addIncoming("Steve", "hi", 1, false);
        s.addGlobal("x", 2);
        s.clearGlobal();
        assertTrue(s.global().isEmpty());
        assertNotNull(s.get("Steve"));
        s.clear();
        assertTrue(s.isEmpty());
    }

    // ---------- saving ----------

    @Test
    void globalRoundTripsOnlyArrivedLines() {
        ServerStores a = new ServerStores(50);
        a.switchTo("2b2t.org");
        ConversationStore s = a.current();
        s.addGlobal("first", 10);
        s.addGlobal("second", 20);
        s.addGlobalNote("a note", 30);
        s.addIncoming("Steve", "hi", 15, false);
        String json = HistoryJson.toJson(a, HistoryJson.MAX_BYTES);
        assertTrue(json.contains("\"global\""));
        assertFalse(json.contains("a note"));

        ServerStores b = new ServerStores(50);
        assertEquals(1, HistoryJson.fromJson(json, b, "local"));
        List<Message> g = b.store("2b2t.org").global();
        assertEquals(2, g.size());
        assertEquals("first", g.get(0).text());
        assertEquals(Direction.IN, g.get(0).direction());
        assertFalse(b.dirty());
    }

    @Test
    void aServerWithOnlyGlobalIsSavedAndLoaded() {
        ServerStores a = new ServerStores(50);
        a.switchTo("only.global");
        a.current().addGlobal("hello", 5);
        String json = HistoryJson.toJson(a, HistoryJson.MAX_BYTES);
        ServerStores b = new ServerStores(50);
        HistoryJson.fromJson(json, b, "local");
        assertEquals(1, b.store("only.global").global().size());
    }

    @Test
    void oldFilesWithoutGlobalStillLoad() {
        String v2 = "{\"version\":2,\"servers\":[{\"server\":\"x.org\",\"conversations\":[{\"name\":\"Steve\",\"messages\":[{\"d\":\"in\",\"t\":\"hi\",\"at\":5}]}]}]}";
        ServerStores s = new ServerStores(50);
        assertEquals(1, HistoryJson.fromJson(v2, s, "local"));
        assertTrue(s.store("x.org").global().isEmpty());
        String v1 = "{\"version\":1,\"conversations\":[{\"name\":\"Steve\",\"messages\":[{\"d\":\"in\",\"t\":\"hi\",\"at\":5}]}]}";
        ServerStores t = new ServerStores(50);
        assertEquals(1, HistoryJson.fromJson(v1, t, "y.org"));
        assertTrue(t.store("y.org").global().isEmpty());
    }

    @Test
    void badGlobalEntriesAreSkipped() {
        String json = "{\"version\":2,\"servers\":[{\"server\":\"x.org\",\"conversations\":[],\"global\":["
            + "{\"d\":\"in\",\"t\":\"good\",\"at\":5},{\"d\":\"out\",\"t\":\"mine\",\"at\":6},{\"d\":\"in\",\"t\":3,\"at\":7},"
            + "{\"d\":\"in\",\"t\":\"neg\",\"at\":-1},5,{\"d\":\"in\",\"t\":\"  \",\"at\":8}]}]}";
        ServerStores s = new ServerStores(50);
        HistoryJson.fromJson(json, s, "local");
        assertEquals(1, s.store("x.org").global().size());
        assertEquals("good", s.store("x.org").global().get(0).text());
        // "global" of the wrong type is ignored, the conversations still load
        String odd = "{\"version\":2,\"servers\":[{\"server\":\"x.org\",\"conversations\":[{\"name\":\"Steve\",\"messages\":[{\"d\":\"in\",\"t\":\"hi\",\"at\":5}]}],\"global\":{}}]}";
        ServerStores t = new ServerStores(50);
        assertEquals(1, HistoryJson.fromJson(odd, t, "local"));
    }

    @Test
    void loadedGlobalIsCutToTheCap() {
        ServerStores a = new ServerStores(50);
        a.globalCap(2000);
        a.switchTo("x.org");
        for (int i = 0; i < 300; i++) a.current().addGlobal("l" + i, i);
        String json = HistoryJson.toJson(a, HistoryJson.MAX_BYTES);
        ServerStores b = new ServerStores(50);
        b.globalCap(50);
        HistoryJson.fromJson(json, b, "local");
        assertEquals(50, b.store("x.org").global().size());
        assertEquals("l299", b.store("x.org").global().get(49).text());
    }

    @Test
    void whenTooBigTheChatGoesBeforeTheWhispers() {
        ServerStores a = new ServerStores(500);
        a.globalCap(2000);
        a.switchTo("x.org");
        a.current().addIncoming("Steve", "an important whisper", 1, false); // the oldest of all
        for (int i = 0; i < 400; i++) a.current().addGlobal("some public chat line number " + i, 100 + i);
        String json = HistoryJson.toJson(a, 6000);
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= 6000);
        ServerStores b = new ServerStores(500);
        b.globalCap(2000);
        HistoryJson.fromJson(json, b, "local");
        assertNotNull(b.store("x.org").get("Steve"));
        assertTrue(b.store("x.org").global().size() < 400);
        assertFalse(b.store("x.org").global().isEmpty());
    }

    @Test
    void mergingKeepsBothSidesWithoutDoubles() {
        ConversationStore a = new ConversationStore(50);
        ConversationStore b = new ConversationStore(50);
        a.addGlobal("one", 1);
        a.addGlobal("two", 2);
        b.addGlobal("two", 2);
        b.addGlobal("three", 3);
        a.mergeFrom(b);
        assertEquals(3, a.global().size());
        assertEquals("three", a.global().get(2).text());
    }

    @Test
    void aSpaceAfterTheSlashIsRefusedNotSent() {
        assertFalse(GlobalSend.prepare("/ msg Steve hi").ok());
        assertFalse(GlobalSend.prepare("/\tmsg Steve hi").ok());
        assertTrue(GlobalSend.prepare("/msg Steve hi").ok());
        assertEquals("msg Steve hi", GlobalSend.prepare("  /msg Steve hi  ").payload());
    }

    @Test
    void aMeteorCommandIsSaidSoAndItsTextIsNotKept() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        assertNull(core.sendGlobal(".bind secret", 0, "."));
        Recorder rec = new Recorder();
        core.tick(0, rec);
        assertEquals(List.of("chat:.bind secret"), rec.sent); // handed to the game's chat send, where Meteor takes it
        List<Message> g = core.store().global();
        assertEquals("Ran Meteor command", g.get(g.size() - 1).text());
        for (Message m : g) assertFalse(m.text().contains("secret"));
        // not a Meteor command without the prefix, or with none given
        core.sendGlobal("hello . there", 10_000, ".");
        core.tick(10_000, rec);
        assertEquals(1, core.store().global().size());
    }

    // ---------- saving cost ----------

    @Test
    void publicChatAloneDoesNotMarkTheWhispersDirty() {
        ServerStores s = new ServerStores(50);
        s.current().addGlobal("line", 1);
        assertFalse(s.dirty());
        assertTrue(s.globalDirty());
        assertTrue(s.anyDirty());
        s.current().addIncoming("Steve", "hi", 2, false);
        assertTrue(s.dirty());
        s.clean();
        assertFalse(s.anyDirty());
    }

    @Test
    void aSnapshotIsNotAffectedByLaterChanges() {
        ServerStores s = new ServerStores(50);
        s.current().addGlobal("before", 1);
        s.current().addIncoming("Steve", "hi", 2, false);
        HistoryJson.Snapshot snap = HistoryJson.snapshot(s);
        s.current().addGlobal("after", 3);
        s.current().clear();
        String json = snap.toJson(HistoryJson.MAX_BYTES);
        assertTrue(json.contains("before"));
        assertTrue(json.contains("Steve"));
        assertFalse(json.contains("after"));
        assertEquals(json, snap.toJson(HistoryJson.MAX_BYTES)); // can be rendered twice
    }

    @Test
    void theServerWithTheNewestChatIsNotTheOneToForget() {
        ServerStores s = new ServerStores(50);
        for (int i = 0; i < ServerStores.MAX_SERVERS; i++) {
            s.switchTo("s" + i + ".org");
            s.current().addIncoming("Steve", "hi", 100 + i, false);
        }
        s.store("s0.org").addGlobal("recent chat", 10_000); // oldest whisper, but the newest activity
        s.switchTo("current.org");
        assertTrue(s.keys().contains("s0.org"));
        assertFalse(s.keys().contains("s1.org"));
    }

    @Test
    void savingLeavesNoTemporaryFilesBehind(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws java.io.IOException {
        ServerStores s = new ServerStores(50);
        s.current().addGlobal("x", 1);
        java.nio.file.Path file = dir.resolve("h.json");
        assertTrue(HistoryJson.write(file, HistoryJson.snapshot(s)));
        try (var files = java.nio.file.Files.list(dir)) {
            assertEquals(List.of("h.json"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    // ---------- unread (per conversation) ----------

    @Test
    void aWhisperWhileTheConversationIsOpenDoesNotCount() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        DmParser.Parsed p = new DmParser.Parsed(Direction.IN, "Steve", "hi");
        core.receive(p, "Me_Self", 1, false);
        core.receive(p, "Me_Self", 2, true); // read at once
        assertEquals(1, core.store().get("Steve").unread());
        core.store().markRead("Steve");
        assertEquals(0, core.store().totalUnread());
    }
}
