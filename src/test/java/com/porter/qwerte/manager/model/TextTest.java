package com.porter.qwerte.manager.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TextTest {
    @Test
    void sanitizeStripsFormattingAndControlCharacters() {
        assertEquals("cRed Stick", Text.sanitize("§cRed\u0000 Stick", 64));
        assertEquals("a b", Text.sanitize("a\nb", 64));
        assertEquals("ab", Text.sanitize("a\u007fb\u009f", 64));
        assertEquals("", Text.sanitize(null, 10));
        assertEquals("", Text.sanitize("abc", 0));
        assertFalse(Text.sanitize("x§ky§r", 64).contains("§"));
    }

    @Test
    void sanitizeCutsAndKeepsSurrogatePairsWhole() {
        assertEquals("abc", Text.sanitize("a" + (char) 0x202E + "b" + (char) 0x202A + "c", 64));
        String pair = "😀"; // one emoji, two chars
        assertEquals("a" + pair, Text.sanitize("a" + pair + "b", 3));
        assertEquals("a", Text.sanitize("a" + pair + "b", 2));
        // a lone surrogate is dropped
        assertEquals("ab", Text.sanitize("a\uD83Db", 10));
    }

    @Test
    void sanitizeStripsBidiAndInvisibleCharacters() {
        // right-to-left override and isolates would reorder what is drawn
        assertEquals("abc", Text.sanitize("a" + (char) 0x202E + "b" + (char) 0x202A + "c", 64));
        for (char c = 0x202a; c <= 0x202e; c++) assertEquals("xy", Text.sanitize("x" + c + "y", 64));
        for (char c = 0x2066; c <= 0x2069; c++) assertEquals("xy", Text.sanitize("x" + c + "y", 64));
        // format characters: zero width space, soft hyphen, byte order mark go; the joiner stays
        assertEquals("xy", Text.sanitize("x" + (char) 0x200B + "y" + (char) 0xAD + (char) 0xFEFF, 64));
        assertEquals("a" + (char) 0x200D + "b", Text.sanitize("a" + (char) 0x200D + "b", 64));
        // variation selectors stay
        assertEquals("x" + (char) 0xFE0F, Text.sanitize("x" + (char) 0xFE0F, 64));
        // private use goes; a code point the JDK does not know is not judged
        assertEquals("xy", Text.sanitize("x" + (char) 0xE000 + "y", 64));
        assertEquals("xy", Text.sanitize("x" + new String(Character.toChars(0xF0000)) + "y", 64));
        // unpaired surrogates, either order
        assertEquals("xy", Text.sanitize("x\uD800y\uDC00", 64));
        // ordinary text in other scripts stays
        assertEquals("Ünï 日本", Text.sanitize("Ünï 日本", 64));
    }

    @Test
    void compactMatchesTheWebsite() {
        assertEquals("0", Text.compact(0));
        assertEquals("999", Text.compact(999));
        assertEquals("1k", Text.compact(1000));
        assertEquals("1.2k", Text.compact(1234));
        assertEquals("9.9k", Text.compact(9900));
        assertEquals("10k", Text.compact(9950));
        assertEquals("100k", Text.compact(99_999));
        assertEquals("1M", Text.compact(1_000_000));
        assertEquals("3.4M", Text.compact(3_400_000));
        assertEquals("12M", Text.compact(12_000_000));
        assertEquals("1.5B", Text.compact(1_500_000_000L));
    }

    @Test
    void groupedAndAgoAndSentence() {
        assertEquals("1,234,567", Text.grouped(1_234_567));
        assertEquals("just now", Text.ago(2000));
        assertEquals("30 s ago", Text.ago(30_000));
        assertEquals("3 min ago", Text.ago(190_000));
        assertEquals("2 h ago", Text.ago(2 * 3_600_000L + 5));
        assertEquals("4 d ago", Text.ago(4 * 86_400_000L));
        assertEquals("Wrong username or password.", Text.sentence("wrong username or password"));
        assertEquals("Done!", Text.sentence("done!"));
        assertEquals("", Text.sentence("  "));
    }
}
