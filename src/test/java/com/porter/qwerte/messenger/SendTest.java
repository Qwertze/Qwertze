package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sanitising of what is typed, the command template, the rate limiter and the queue. */
class SendTest {
    // ---------- sanitising ----------

    @Test
    void newlinesBecomeSpacesAndSectionSignsGo() {
        assertEquals("a b c", SendText.sanitize("a\nb\r\nc").replace("  ", " "));
        assertEquals("hello", SendText.sanitize("he§llo"));
        assertEquals("ac", SendText.sanitize("a\u0000c"));
        assertEquals("hi", SendText.sanitize("  hi \n"));
        assertEquals("", SendText.sanitize(null));
    }

    @Test
    void emojiIsKept() {
        assertEquals("hi 😀 ❤️", SendText.sanitize("hi 😀 ❤️"));
    }

    // ---------- the command ----------

    @Test
    void defaultTemplateMakesAWhisperWithoutTheSlash() {
        SendText.Result r = SendText.prepare(SendText.DEFAULT_TEMPLATE, "Steve", "hello there");
        assertTrue(r.ok());
        assertEquals("msg Steve hello there", r.command());
        assertEquals("hello there", r.text());
    }

    @Test
    void twoB2tTemplate() {
        SendText.Result r = SendText.prepare("/w {name} {text}", "Steve", "hi");
        assertEquals("w Steve hi", r.command());
    }

    @Test
    void aTypedSlashIsNotAnotherCommand() {
        SendText.Result r = SendText.prepare(SendText.DEFAULT_TEMPLATE, "Steve", "/op me");
        assertEquals("msg Steve /op me", r.command());
    }

    @Test
    void aPlaceholderInTheTextIsNotExpanded() {
        SendText.Result r = SendText.prepare(SendText.DEFAULT_TEMPLATE, "Steve", "{name} {text}");
        assertEquals("msg Steve {name} {text}", r.command());
    }

    @Test
    void newlinesCanNotSmuggleAsecondCommand() {
        SendText.Result r = SendText.prepare(SendText.DEFAULT_TEMPLATE, "Steve", "hi\n/stop");
        assertFalse(r.command().contains("\n"));
        assertTrue(r.command().startsWith("msg Steve hi"));
    }

    @Test
    void refusesWhatIsNotSendable() {
        assertFalse(SendText.prepare(SendText.DEFAULT_TEMPLATE, "Steve", "   \n ").ok());
        assertFalse(SendText.prepare(SendText.DEFAULT_TEMPLATE, "Ste ve", "hi").ok());
        assertFalse(SendText.prepare(SendText.DEFAULT_TEMPLATE, "x", "hi").ok());
        assertFalse(SendText.prepare(SendText.DEFAULT_TEMPLATE, null, "hi").ok());
    }

    @Test
    void refusesOver256CharactersOfCommand() {
        String name = "Steve";
        int prefix = ("/msg " + name + " ").length();
        assertTrue(SendText.prepare(SendText.DEFAULT_TEMPLATE, name, "x".repeat(SendText.MAX_COMMAND - prefix)).ok());
        SendText.Result r = SendText.prepare(SendText.DEFAULT_TEMPLATE, name, "x".repeat(SendText.MAX_COMMAND - prefix + 1));
        assertFalse(r.ok());
        assertNull(r.command());
    }

    @Test
    void templateRules() {
        assertTrue(SendText.validTemplate("/msg {name} {text}"));
        assertTrue(SendText.validTemplate("/w {name} {text}"));
        assertFalse(SendText.validTemplate("/tell {text} to {name}")); // the text must not come before the name
        assertTrue(SendText.validTemplate("/minecraft:msg {name} {text}"));
        assertFalse(SendText.validTemplate(null));
        assertFalse(SendText.validTemplate(""));
        assertFalse(SendText.validTemplate("msg {name} {text}"));        // chat, not a command
        assertFalse(SendText.validTemplate("{text}"));
        assertFalse(SendText.validTemplate("/{name} {text}"));           // the player would pick the command
        assertFalse(SendText.validTemplate("/msg {name}"));              // no text
        assertFalse(SendText.validTemplate("/msg {text}"));              // no name
        assertFalse(SendText.validTemplate("/msg {name} {text} {text}"));
        assertFalse(SendText.validTemplate("/msg {name} {text}\n/stop"));
        assertFalse(SendText.validTemplate("/msg x {name} {text}"));      // something between the word and {name}
        assertFalse(SendText.validTemplate("/msg -s {name} {text}"));
        assertFalse(SendText.validTemplate("/msg  {name} {text}"));
        assertFalse(SendText.validTemplate("/msg {name} {text} please")); // nothing after {text}
        assertFalse(SendText.validTemplate("/msg {name}  {text}"));
        assertFalse(SendText.validTemplate("/" + "a".repeat(200) + " {name} {text}"));
    }

    @Test
    void badTemplateIsRefusedNotReplaced() {
        SendText.Result r = SendText.prepare("say {text}", "Steve", "hi");
        assertFalse(r.ok());
    }

    // ---------- the queue and the rate limit ----------

    @Test
    void firstMessageGoesAtOnceThenOnePerInterval() {
        Outbox<String> box = new Outbox<>();
        assertTrue(box.offer("a"));
        assertTrue(box.offer("b"));
        assertEquals("a", box.next(10_000, 1500));
        assertNull(box.next(10_001, 1500));
        assertNull(box.next(11_499, 1500));
        assertEquals("b", box.next(11_500, 1500));
        assertNull(box.next(20_000, 1500));
    }

    @Test
    void queueHoldsAFewAndRefusesMore() {
        Outbox<Integer> box = new Outbox<>();
        for (int i = 0; i < Outbox.MAX_QUEUE; i++) assertTrue(box.offer(i));
        assertFalse(box.offer(99));
        assertEquals(Outbox.MAX_QUEUE, box.size());
        assertEquals(0, box.next(0, 1500));
        assertTrue(box.offer(99)); // room again
    }

    @Test
    void intervalIsClamped() {
        Outbox<String> box = new Outbox<>();
        box.offer("a");
        box.offer("b");
        assertEquals("a", box.next(1000, 1)); // 1 ms becomes the minimum
        assertNull(box.next(1000 + Outbox.MIN_INTERVAL - 1, 1));
        assertEquals("b", box.next(1000 + Outbox.MIN_INTERVAL, 1));
    }

    @Test
    void aClockThatGoesBackDoesNotBlockForever() {
        Outbox<String> box = new Outbox<>();
        box.offer("a");
        box.offer("b");
        box.next(1_000_000, 1500);
        assertEquals("b", box.next(5, 1500));
    }

    @Test
    void clearReturnsWhatWasWaiting() {
        Outbox<String> box = new Outbox<>();
        box.offer("a");
        box.offer("b");
        assertEquals(List.of("a", "b"), box.clear());
        assertEquals(0, box.size());
        assertNull(box.next(0, 1500));
    }

    // ---------- the core: typed text to a sent message ----------

    @Test
    void nothingIsSentUntilTheTickAndThenOnePerInterval() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.intervalMs(2000);
        List<String> sent = new ArrayList<>();
        assertNull(core.send("Steve", "one", 0));
        assertNull(core.send("Steve", "two", 1));
        assertTrue(sent.isEmpty());
        Message first = core.store().get("Steve").messages().get(0);
        assertEquals(Message.State.SENDING, first.state());

        core.tick(100, c -> sent.add(c));
        assertEquals(List.of("msg Steve one"), sent);
        assertEquals(Message.State.SENT, first.state());
        core.tick(1000, c -> sent.add(c));
        assertEquals(1, sent.size());
        core.tick(2100, c -> sent.add(c));
        assertEquals(List.of("msg Steve one", "msg Steve two"), sent);
    }

    @Test
    void aFullQueueRefusesAndRecordsNothing() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        for (int i = 0; i < Outbox.MAX_QUEUE; i++) assertNull(core.send("Steve", "m" + i, i));
        assertNotNull(core.send("Steve", "extra", 10));
        assertEquals(Outbox.MAX_QUEUE, core.store().get("Steve").messages().size());
    }

    @Test
    void aRefusedMessageIsNotRecorded() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        assertNotNull(core.send("Steve", "x".repeat(300), 0));
        assertNull(core.store().get("Steve"));
    }

    @Test
    void notConnectedMarksTheMessageFailed() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.send("Steve", "hi", 0);
        core.tick(10, c -> false);
        assertEquals(Message.State.FAILED, core.store().get("Steve").messages().get(0).state());
    }

    @Test
    void cancelMarksQueuedMessagesFailed() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.send("Steve", "hi", 0);
        core.cancelQueued();
        assertEquals(Message.State.FAILED, core.store().get("Steve").messages().get(0).state());
        assertEquals(0, core.queued());
        List<String> sent = new ArrayList<>();
        core.tick(10_000, sent::add);
        assertTrue(sent.isEmpty());
    }

    @Test
    void theTemplateIsUsed() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.template("/w {name} {text}");
        core.send("Steve", "hi", 0);
        List<String> sent = new ArrayList<>();
        core.tick(0, sent::add);
        assertEquals(List.of("w Steve hi"), sent);
    }

    // ---------- the core: incoming and echoes ----------

    @Test
    void incomingIsRecordedAndCountsAsUnread() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        MessengerCore.Received r = core.receive(new DmParser.Parsed(Direction.IN, "Steve", "hi"), "Me_", 5, false);
        assertTrue(r.fromOther());
        assertEquals(1, r.conversation().unread());
        core.receive(new DmParser.Parsed(Direction.IN, "Steve", "again"), "Me_", 6, true);
        assertEquals(1, core.store().get("Steve").unread()); // read while open
        assertEquals(2, core.store().get("Steve").messages().size());
    }

    @Test
    void whispersFromOneselfAreIgnored() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        assertNull(core.receive(new DmParser.Parsed(Direction.IN, "me_", "hi"), "Me_", 5, false));
        assertTrue(core.store().isEmpty());
    }

    @Test
    void theServersCopyOfAWindowMessageIsNotRecordedTwice() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.send("Steve", "hello there", 1000);
        core.tick(1000, c -> true);
        MessengerCore.Received r = core.receive(new DmParser.Parsed(Direction.OUT, "Steve", "hello there"), "Me_", 1500, false);
        assertNull(r.message());
        assertEquals(1, core.store().get("Steve").messages().size());
        assertTrue(core.store().get("Steve").messages().get(0).echoed());
    }

    @Test
    void aWhisperTypedInChatIsRecorded() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        MessengerCore.Received r = core.receive(new DmParser.Parsed(Direction.OUT, "Steve", "typed in chat"), "Me_", 1500, false);
        assertNotNull(r.message());
        assertEquals(Direction.OUT, r.message().direction());
        assertFalse(r.fromOther());
    }

    @Test
    void anOldMessageIsNotTakenForTheCopy() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.send("Steve", "same", 0);
        core.tick(0, c -> true);
        core.receive(new DmParser.Parsed(Direction.OUT, "Steve", "same"), "Me_", MessengerCore.ECHO_WINDOW_MS + 5, false);
        assertEquals(2, core.store().get("Steve").messages().size());
    }

    @Test
    void strippedEmojiGivesOneNoteOnce() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.send("Steve", "nice 😀", 0);
        core.tick(0, c -> true);
        core.receive(new DmParser.Parsed(Direction.OUT, "Steve", "nice"), "Me_", 100, false);
        long notes = core.store().get("Steve").messages().stream().filter(m -> m.direction() == Direction.NOTE).count();
        assertEquals(1, notes);
        assertEquals(MessengerCore.STRIPPED_NOTE, core.store().get("Steve").messages().stream().filter(m -> m.direction() == Direction.NOTE).findFirst().get().text());

        core.send("Steve", "again 😀", 200);
        core.tick(5000, c -> true);
        core.receive(new DmParser.Parsed(Direction.OUT, "Steve", "again"), "Me_", 5100, false);
        notes = core.store().get("Steve").messages().stream().filter(m -> m.direction() == Direction.NOTE).count();
        assertEquals(1, notes);
    }

    // ---------- fixes from review ----------

    @Test
    void messagesWithoutLettersAreNotRecordedTwice() {
        for (String text : new String[] {"😂", ":)", "???", "❤️!!"}) {
            MessengerCore core = new MessengerCore(new ServerStores(50));
            core.send("Steve", text, 0);
            core.tick(0, c -> true);
            MessengerCore.Received r = core.receive(new DmParser.Parsed(Direction.OUT, "Steve", text), "Me_", 200, false);
            assertNull(r.message(), text);
            assertEquals(1, core.store().get("Steve").messages().size(), text);
        }
    }

    @Test
    void aSymbolMessageTurnedIntoSomethingElseByTheServerIsStillMatched() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.send("Steve", "😂", 0);
        core.tick(0, c -> true);
        MessengerCore.Received r = core.receive(new DmParser.Parsed(Direction.OUT, "Steve", "?"), "Me_", 200, false);
        assertNull(r.message());
        // one real message, plus the note that the server removed the emoji
        assertEquals(1, core.store().get("Steve").messages().stream().filter(m -> m.direction() == Direction.OUT).count());
        assertEquals(1, core.store().get("Steve").messages().stream().filter(m -> m.direction() == Direction.NOTE).count());
    }

    @Test
    void differentSymbolMessagesAreNotConfused() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.receive(new DmParser.Parsed(Direction.OUT, "Steve", ":)"), "Me_", 0, false); // typed in chat
        MessengerCore.Received r = core.receive(new DmParser.Parsed(Direction.OUT, "Steve", ":("), "Me_", 10, false);
        assertNotNull(r.message());
        assertEquals(2, core.store().get("Steve").messages().size());
    }

    @Test
    void theEchoWindowStartsAtSendTimeNotQueueTime() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.intervalMs(10_000);
        core.send("Steve", "first", 0);
        core.send("Steve", "second", 0);
        core.tick(0, c -> true);
        core.tick(40_000, c -> true); // queued at 0, sent at 40 s
        // 20 s after sending: still its copy, though 60 s after it was typed
        MessengerCore.Received r = core.receive(new DmParser.Parsed(Direction.OUT, "Steve", "second"), "Me_", 60_000, false);
        assertNull(r.message());
        assertEquals(2, core.store().get("Steve").messages().size());
    }

    @Test
    void anUnsentMessageIsNotTakenForACopy() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.send("Steve", "waiting", 0);
        MessengerCore.Received r = core.receive(new DmParser.Parsed(Direction.OUT, "Steve", "waiting"), "Me_", 5, false);
        assertNotNull(r.message()); // recorded as typed in chat: the queued one has not been sent
    }

    @Test
    void lonelySurrogatesAreRemovedFromWhatIsSent() {
        assertEquals("ab", SendText.sanitize("a\uD83Db"));
        assertEquals("ab", SendText.sanitize("a\uDE00b"));
        assertEquals("a😀b", SendText.sanitize("a😀b"));
        assertEquals("a", SendText.sanitize("a\uD83D"));
        assertEquals("msg Steve ab", SendText.prepare(SendText.DEFAULT_TEMPLATE, "Steve", "a\uD83Db").command());
    }

    @Test
    void templateCommandAllowList() {
        assertTrue(SendText.isWhisperCommand("/msg {name} {text}"));
        assertTrue(SendText.isWhisperCommand("/W {name} {text}"));
        assertTrue(SendText.isWhisperCommand("/minecraft:tell {name} {text}"));
        assertTrue(SendText.isWhisperCommand("/etell {name} {text}"));
        assertFalse(SendText.isWhisperCommand("/say {name} {text}"));
        assertFalse(SendText.isWhisperCommand("/op {name} {text}"));
        assertFalse(SendText.isWhisperCommand(null));
        // still accepted as a template, only warned about
        assertTrue(SendText.validTemplate("/say {name} {text}"));
    }

    @Test
    void conversationsAreKeptPerServer() {
        ServerStores stores = new ServerStores(50);
        MessengerCore core = new MessengerCore(stores);
        core.switchServer("2b2t.org");
        core.receive(new DmParser.Parsed(Direction.IN, "Steve", "on 2b"), "Me_", 1, false);
        core.switchServer("Other.Server:25566");
        assertTrue(core.store().isEmpty());
        core.receive(new DmParser.Parsed(Direction.IN, "Steve", "elsewhere"), "Me_", 2, false);
        assertEquals("elsewhere", core.store().get("Steve").messages().get(0).text());
        core.switchServer("2B2T.ORG");
        assertEquals("on 2b", core.store().get("Steve").messages().get(0).text());
        assertEquals("other.server:25566", stores.keys().get(2));
    }

    @Test
    void leavingResetsTheEmojiNoteAndTheQueue() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.send("Steve", "nice 😀", 0);
        core.tick(0, c -> true);
        core.receive(new DmParser.Parsed(Direction.OUT, "Steve", "nice"), "Me_", 10, false);
        core.send("Steve", "queued", 20);
        core.leave();
        assertEquals(0, core.queued());
        core.switchServer("other.example");
        core.send("Steve", "again 😀", 30_000);
        core.tick(30_000, c -> true);
        core.receive(new DmParser.Parsed(Direction.OUT, "Steve", "again"), "Me_", 30_100, false);
        assertEquals(1, core.store().get("Steve").messages().stream().filter(m -> m.direction() == Direction.NOTE).count());
    }

    @Test
    void emojiThatSurvivesGivesNoNote() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.send("Steve", "nice 😀", 0);
        core.tick(0, c -> true);
        core.receive(new DmParser.Parsed(Direction.OUT, "Steve", "nice 😀"), "Me_", 100, false);
        assertTrue(core.store().get("Steve").messages().stream().noneMatch(m -> m.direction() == Direction.NOTE));
    }
}
