package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Inline player heads: their fallback text "[Name head]" must not reach the displayed text or the name matcher. */
class ComponentTextTest {
    private static ComponentText.Part t(String s) {
        return new ComponentText.Part(false, s);
    }

    private static ComponentText.Part head(String name) {
        return new ComponentText.Part(true, "[" + name + " head]");
    }

    private static String shown(ComponentText.Part... parts) {
        return ChatText.message(ComponentText.join(List.of(parts)));
    }

    @Test
    void headAndNameThenText() {
        // <[vbkk head]vbkk> i blew it up
        String s = shown(t("<"), head("vbkk"), t("vbkk"), t("> "), t("i blew it up"));
        assertEquals("<vbkk> i blew it up", s);
        assertEquals("vbkk", LineKind.sender(s));
    }

    @Test
    void headBeforeNameOutsideTheBrackets() {
        assertEquals("<vbkk> hi", shown(head("vbkk"), t("<vbkk> hi")));
    }

    @Test
    void deathMessage() {
        assertEquals("Katamarzy was demolished by a piglin wielding Golden Sword",
            shown(head("Katamarzy"), t("Katamarzy was demolished by a piglin wielding Golden Sword")));
    }

    @Test
    void withTimestamp() {
        String s = shown(t("<22:30> "), t("<"), head("Katamarzy"), t("Katamarzy"), t("> vbkk take my art"));
        assertEquals("<22:30> <Katamarzy> vbkk take my art", s);
        assertEquals("Katamarzy", LineKind.sender(s));
    }

    @Test
    void withTheOldGlyphToo() {
        String s = shown(t("<22:30> "), t("￼<"), head("Pineapple_123"), t("Pineapple_123"), t("> does it update?"));
        assertEquals("<22:30> <Pineapple_123> does it update?", s);
        assertEquals("Pineapple_123", LineKind.sender(s));
    }

    @Test
    void ownLineIsNotDoubledAndIsOneLine() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        String a = ComponentText.join(List.of(t("<21:48> "), t("<"), head("Qwertttze"), t("Qwertttze"), t("> doh")));
        String b = ComponentText.join(List.of(t("<"), t("Qwertttze"), t("> doh")));
        assertTrue(core.receiveGlobal(a, 1000, false));
        assertFalse(core.receiveGlobal(b, 1050, false)); // the same line without the timestamp or the head, a moment later
        assertEquals(1, core.store().global().size());
        assertEquals("<21:48> <Qwertttze> doh", core.store().global().get(0).text());
    }

    @Test
    void whitespaceDifferencesDoNotHideARepeat() {
        MessengerCore core = new MessengerCore(new ServerStores(50));
        assertTrue(core.receiveGlobal("<A_b> two  words", 1000, false));
        assertFalse(core.receiveGlobal("<A_b> two words", 1010, false));
    }

    @Test
    void nullAndEmpty() {
        assertEquals("", ComponentText.join(List.of()));
        assertEquals("", ComponentText.plain(null));
    }
}
