package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Player-head glyphs of chat-head mods, timestamps, and the same line arriving twice. */
class ChatGlyphTest {
    private static final String HEAD = "￼";
    private static final String PUA = "";

    @Test
    void glyphsAreRemovedFromShownText() {
        assertEquals("<Qwertttze> hello", ChatText.message(HEAD + "<Qwertttze> hello"));
        assertEquals("<Qwertttze> hello", ChatText.message(PUA + " <Qwertttze> hello").strip());
        assertEquals("hi there", ChatText.message("hi" + HEAD + " there"));
        assertEquals("<Qwertttze> hello", ChatText.message("<Qwertttze> hello"));
    }

    @Test
    void glyphWithAndWithoutTimestamp() {
        assertEquals("<15:05> <Qwertttze> hello", ChatText.message("<15:05> " + HEAD + "<Qwertttze> hello"));
        assertEquals("<15:05> <Qwertttze> hello", ChatText.message(HEAD + "<15:05> <Qwertttze> hello"));
        assertEquals("<Qwertttze> hello", ChatText.message(HEAD + "<Qwertttze> hello"));
    }

    @Test
    void senderOfAChatLine() {
        assertEquals("Qwertttze", LineKind.sender("<Qwertttze> hello"));
        assertEquals("Qwertttze", LineKind.sender("<15:05> <Qwertttze> hello"));
        assertEquals("Qwertttze", LineKind.sender(HEAD + "<15:05> " + HEAD + "<Qwertttze> hello"));
        assertEquals("Steve", LineKind.sender("<[Mod] Steve> hi"));
        assertNull(LineKind.sender("Steve joined the game"));
        assertNull(LineKind.sender("[Meteor] Toggled x on."));
        assertNull(LineKind.sender(null));
    }

    @Test
    void doubledNameIsShownOnce() {
        assertEquals("<Qwertttze> hello", ChatText.message("<Qwertttze> <Qwertttze> hello"));
        assertEquals("<15:05> <Qwertttze> hello", ChatText.message("<15:05> <Qwertttze> <Qwertttze> hello"));
        assertEquals("<Qwertttze> <Alex> hello", ChatText.message("<Qwertttze> <Alex> hello"));
        // a repeat later in the text is the player's own words
        assertEquals("<Steve> hi <Alex> <Alex> there", ChatText.message("<Steve> hi <Alex> <Alex> there"));
    }

    @Test
    void sameLineTwiceInAMomentIsOneLine() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.receiveGlobal(HEAD + "<Qwertttze> hello", 1000);
        core.receiveGlobal("<15:05> " + HEAD + "<Qwertttze> hello", 1001); // the same line with a timestamp, delivered again
        core.receiveGlobal("<15:05> <Qwertttze> hello", 1002);
        assertEquals(1, core.store().global().size());
        assertEquals("<Qwertttze> hello", core.store().global().get(0).text());
    }

    @Test
    void timestampedCopyFirstThenPlainIsStillOne() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.receiveGlobal("<15:05> " + HEAD + "<Qwertttze> hello", 1000);
        core.receiveGlobal(HEAD + "<Qwertttze> hello", 1100);
        assertEquals(1, core.store().global().size());
    }

    @Test
    void theSameTextLaterIsAnotherLine() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        core.receiveGlobal("<Qwertttze> hello", 1000);
        core.receiveGlobal("<Qwertttze> hello", 1000 + MessengerCore.DUPLICATE_WINDOW_MS + 1);
        core.receiveGlobal("<Alex> hello", 5000);
        assertEquals(3, core.store().global().size());
    }

    @Test
    void whisperTextHasNoGlyph() {
        DmParser p = new DmParser(java.util.EnumSet.allOf(DmFormat.class), java.util.List.of());
        DmParser.Parsed r = p.parse("<15:05> " + HEAD + "Anze1 whispers: hi" + HEAD + " foes");
        assertNotNull(r);
        assertEquals("Anze1", r.name());
        assertEquals("hi foes", r.text());
    }
}
