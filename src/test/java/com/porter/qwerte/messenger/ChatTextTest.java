package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTextTest {
    @Test
    void validNames() {
        assertTrue(ChatText.isValidName("Steve"));
        assertTrue(ChatText.isValidName("abc"));
        assertTrue(ChatText.isValidName("A_b_1_2_3_4_5_6")); // 15
        assertTrue(ChatText.isValidName("a".repeat(16)));
        assertTrue(ChatText.isValidName("___"));
    }

    @Test
    void invalidNames() {
        assertFalse(ChatText.isValidName(null));
        assertFalse(ChatText.isValidName(""));
        assertFalse(ChatText.isValidName("ab"));
        assertFalse(ChatText.isValidName("a".repeat(17)));
        assertFalse(ChatText.isValidName("Ste ve"));
        assertFalse(ChatText.isValidName("Ste-ve"));
        assertFalse(ChatText.isValidName("Steve\n"));
        assertFalse(ChatText.isValidName("Stevé"));
        assertFalse(ChatText.isValidName("§aSteve"));
        assertFalse(ChatText.isValidName("Steve "));
        assertFalse(ChatText.isValidName("Ｓteve")); // full-width letter
    }

    @Test
    void cleanRemovesFormattingAndControls() {
        assertEquals("hello", ChatText.clean("§chel§rlo", 100));
        assertEquals("ab", ChatText.clean("a\u0000\u001fb", 100));
        assertEquals("a b", ChatText.clean("a\tb", 100));
        assertEquals("", ChatText.clean("§", 100));
        assertEquals("", ChatText.clean(null, 100));
    }

    @Test
    void cleanRemovesBidiOverrides() {
        assertEquals("ab", ChatText.clean("a‮b", 100));
    }

    @Test
    void cleanCutsWithoutSplittingASurrogatePair() {
        String s = "ab😀cd";
        assertEquals("ab", ChatText.clean(s, 3));
        assertEquals("ab😀", ChatText.clean(s, 4));
    }

    @Test
    void messageIsTrimmedAndCapped() {
        assertEquals("hi", ChatText.message("   hi  "));
        assertEquals(ChatText.MAX_TEXT, ChatText.message("x".repeat(5000)).length());
    }
}
