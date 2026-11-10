package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Whispers that another client mod put a timestamp or icon in front of (the 2b2t log lines of dev.17). */
class ClientPrefixTest {
    private static DmParser parser() {
        return new DmParser(EnumSet.allOf(DmFormat.class), List.of());
    }

    private static void expect(DmParser.Parsed p, Direction d, String name, String text) {
        assertNotNull(p);
        assertEquals(d, p.direction());
        assertEquals(name, p.name());
        assertEquals(text, p.text());
    }

    @Test
    void incomingWithTimestamp() {
        expect(parser().parse("<15:05> Anze1 whispers: hi foes"), Direction.IN, "Anze1", "hi foes");
    }

    @Test
    void outgoingWithTimestamp() {
        expect(parser().parse("<15:03> to Anze1: hi, im testing a new messenging module"), Direction.OUT, "Anze1", "hi, im testing a new messenging module");
    }

    @Test
    void otherTimestampShapes() {
        for (String ts : new String[]{"[15:05] ", "[15:05:30] ", "<3:05 PM> ", "<15:05> [15:05:30] ", "￼ <15:05> ", "  "}) {
            expect(parser().parse(ts + "Steve_9 whispers: yo"), Direction.IN, "Steve_9", "yo");
        }
    }

    @Test
    void colourCodesAndTimestamp() {
        expect(parser().parse("§7<15:05> §dAnze1 §5whispers: §dhi foes"), Direction.IN, "Anze1", "hi foes");
    }

    @Test
    void stashSorterSample() {
        DmParser.Parsed p = parser().parse("<15:05> Qwerttttze whispers: Stash Sorter: The server put the shulker at [1, 2, 3] back after it was broken (1/5). Trying again in 5 s.");
        assertNotNull(p);
        assertEquals("Qwerttttze", p.name());
        assertTrue(p.text().startsWith("Stash Sorter: The server put"));
    }

    @Test
    void withoutPrefixStillWorksAndEssentialsBracketsAreKept() {
        expect(parser().parse("Anze1 whispers: hi"), Direction.IN, "Anze1", "hi");
        expect(parser().parse("[Anze1 -> me] hi"), Direction.IN, "Anze1", "hi");
        expect(parser().parse("[me -> Anze1] hi"), Direction.OUT, "Anze1", "hi");
    }

    @Test
    void publicChatIsNotAWhisper() {
        assertNull(parser().parse("<15:05> <Anze1> hello"));
    }

    @Test
    void stripOnlyTouchesTheFront() {
        assertEquals("a <15:05> b", ChatText.stripClientPrefix("<15:05> a <15:05> b"));
        assertEquals("<Steve> hi", ChatText.stripClientPrefix("<Steve> hi"));
        assertEquals("[1] x", ChatText.stripClientPrefix("[1] x"));
        assertEquals("", ChatText.stripClientPrefix(null));
    }

    @Test
    void outgoingEchoWithTimestampIsNotAddedTwice() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        assertNull(core.send("Anze1", "hi foes", 0));
        core.tick(10, c -> true);
        DmParser.Parsed echo = parser().parse("<15:03> to Anze1: hi foes");
        core.receive(echo, "Me", 500, false);
        assertEquals(1, core.store().get("Anze1").messages().size());
        assertTrue(core.store().get("Anze1").messages().get(0).echoed());
    }

    @Test
    void aTimestampDoesNotLetAPlayerFakeAWhisper() {
        assertNull(parser().parse("<15:05> <Player> Anze1 whispers: x"));
        assertNull(parser().parse("<Player> [12:00] Anze1 whispers: y"));
    }

    @Test
    void malformedTimestampsAreNotStripped() {
        for (String ts : new String[]{"<15:05 ", "<1:2> ", "<15:05:30:11> ", "<" + "1".repeat(30) + ":05> ", "[1] ", "[15:05> ", "<:::> "}) {
            assertNull(parser().parse(ts + "Anze1 whispers: x"), ts);
        }
        assertEquals("<1:2> a", ChatText.stripClientPrefix("<1:2> a"));
    }

    @Test
    void timestampBeforeEssentials() {
        expect(parser().parse("<15:05> [Anze1 -> me] hi"), Direction.IN, "Anze1", "hi");
        expect(parser().parse("[15:05] [me -> Anze1] hi"), Direction.OUT, "Anze1", "hi");
    }

    @Test
    void objectCharacterPrefixByEscape() {
        expect(parser().parse("￼￼ <15:05> Anze1 whispers: hi"), Direction.IN, "Anze1", "hi");
        expect(parser().parse(" Anze1 whispers: hi"), Direction.IN, "Anze1", "hi");
    }

    @Test
    void manyRepeatedPrefixesAreBounded() {
        String line = "<15:05> ".repeat(70) + "Anze1 whispers: hi";
        assertTrue(line.length() < ChatText.MAX_LINE);
        long t = System.nanoTime();
        expect(parser().parse(line), Direction.IN, "Anze1", "hi");
        assertNull(parser().parse("<15:05> ".repeat(200) + "Anze1 whispers: hi")); // over the line cap
        assertTrue(System.nanoTime() - t < 1_000_000_000L);
        assertEquals("x", ChatText.stripClientPrefix("￼ ".repeat(5000) + "x"));
    }
}
