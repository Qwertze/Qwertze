package com.porter.qwerte.manager.gui.widgets;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextWrapTest {
    @Test
    void anEmojiIsNeverSplitBetweenRows() {
        // every char is 1 wide and a row holds 3: "ab" plus an emoji (two chars) would split the pair at 3
        String text = "ab" + new String(Character.toChars(0x1F600)) + "cd";
        List<String> rows = TextWrap.wrap(text, 3, String::length);
        assertEquals(text, String.join("", rows));
        for (String r : rows) assertTrue(r.isEmpty() || !Character.isHighSurrogate(r.charAt(r.length() - 1)), r);
    }

    /** Every character is 1 wide. */
    static double mono(String s) {
        return s.length();
    }

    @Test
    void shortTextIsOneRowAndEmptyIsOneEmptyRow() {
        assertEquals(List.of("hello"), TextWrap.wrap("hello", 10, TextWrapTest::mono));
        assertEquals(List.of(""), TextWrap.wrap("", 10, TextWrapTest::mono));
    }

    @Test
    void breaksAtSpacesAndKeepsEveryWord() {
        List<String> rows = TextWrap.wrap("the quick brown fox jumps over the lazy dog", 12, TextWrapTest::mono);
        assertEquals(List.of("the quick", "brown fox", "jumps over", "the lazy dog"), rows);
        for (String r : rows) assertTrue(r.length() <= 12);
    }

    @Test
    void aWordLongerThanTheRowIsCutAndNothingIsLost() {
        List<String> rows = TextWrap.wrap("abcdefghijklmnopqrstuvwxyz", 10, TextWrapTest::mono);
        assertEquals(List.of("abcdefghij", "klmnopqrst", "uvwxyz"), rows);
        assertEquals(List.of("a", "b", "c"), TextWrap.wrap("abc", 0.5, TextWrapTest::mono));
    }

    @Test
    void aLongLineCostsFarFewerMeasurementsThanItsLengthSquared() {
        String text = "word ".repeat(60).trim(); // 299 characters
        AtomicInteger calls = new AtomicInteger();
        List<String> rows = TextWrap.wrap(text, 40, s -> {
            calls.incrementAndGet();
            return s.length();
        });
        assertEquals(text, String.join(" ", rows));
        assertTrue(calls.get() < 120, "measurements: " + calls.get());
    }

    @Test
    void textIsNeverLostWhateverTheWidth() {
        String text = "A somewhat longer sentence, with punctuation; and a veryveryveryverylongtoken inside.";
        for (int limit = 1; limit < 100; limit += 7) {
            List<String> rows = TextWrap.wrap(text, limit, TextWrapTest::mono);
            assertEquals(text.replace(" ", ""), String.join("", rows).replace(" ", ""), "limit " + limit);
        }
    }
}
