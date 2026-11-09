package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmojiTest {
    private static final String GRIN = "😀";
    private static final String HEART_VS = "❤️";
    private static final String FAMILY = "👨‍👩‍👧‍👦";
    private static final String THUMB_TONE = "👍🏽";
    private static final String US_FLAG = "🇺🇸";
    private static final String KEYCAP_1 = "1️⃣";
    private static final String HEART_FIRE = "❤️‍🔥";
    private static final String RAINBOW = "🏳️‍🌈";
    private static final String ENGLAND = "🏴󠁧󠁢󠁥󠁮󠁧󠁿";

    private static List<String> emojiOf(String s) {
        return EmojiSegmenter.segment(s).stream().filter(EmojiSegmenter.Segment::emoji).map(EmojiSegmenter.Segment::text).collect(Collectors.toList());
    }

    private static String joined(String s) {
        return EmojiSegmenter.segment(s).stream().map(EmojiSegmenter.Segment::text).collect(Collectors.joining());
    }

    // ---------- segmentation ----------

    @Test
    void plainTextHasNoEmoji() {
        assertEquals(List.of(), EmojiSegmenter.segment(""));
        assertEquals(List.of(new EmojiSegmenter.Segment("hello 123 #*", false)), EmojiSegmenter.segment("hello 123 #*"));
        assertEquals(0, EmojiSegmenter.count("(c) 1/2 © ® ™ →"));
    }

    @Test
    void emojiBetweenText() {
        List<EmojiSegmenter.Segment> s = EmojiSegmenter.segment("hi " + GRIN + " there");
        assertEquals(List.of(new EmojiSegmenter.Segment("hi ", false), new EmojiSegmenter.Segment(GRIN, true), new EmojiSegmenter.Segment(" there", false)), s);
    }

    @Test
    void neighbouringEmojiAreSeparate() {
        assertEquals(List.of(GRIN, GRIN), emojiOf(GRIN + GRIN));
        assertEquals(List.of(GRIN, HEART_VS), emojiOf(GRIN + HEART_VS));
    }

    @Test
    void zwjSequencesAreOneEmoji() {
        assertEquals(List.of(FAMILY), emojiOf("a" + FAMILY + "b"));
        assertEquals(List.of(HEART_FIRE), emojiOf(HEART_FIRE));
        assertEquals(List.of(RAINBOW), emojiOf(RAINBOW));
        assertEquals(List.of("🧑‍💻"), emojiOf("🧑‍💻"));
    }

    @Test
    void variationSelectors() {
        assertEquals(List.of(HEART_VS), emojiOf(HEART_VS));
        assertEquals(List.of(), emojiOf("❤"));            // text heart without selector
        assertEquals(List.of(), emojiOf("❤︎"));      // asked to be text
        assertEquals(GRIN + "️", emojiOf(GRIN + "️").get(0)); // the selector stays with its emoji
        assertEquals(List.of("©️"), emojiOf("©️"));
    }

    @Test
    void skinTones() {
        assertEquals(List.of(THUMB_TONE), emojiOf(THUMB_TONE));
        assertEquals(List.of("✌️🏽"), emojiOf("✌️🏽"));
    }

    @Test
    void flagsAndTagSequences() {
        assertEquals(List.of(US_FLAG), emojiOf(US_FLAG));
        assertEquals(List.of(US_FLAG, US_FLAG), emojiOf(US_FLAG + US_FLAG));
        // a lone regional indicator is not a flag
        assertEquals(List.of(US_FLAG), emojiOf(US_FLAG + "🇩"));
        assertEquals(List.of(), emojiOf("🇺"));
        assertEquals(List.of(ENGLAND), emojiOf(ENGLAND));
    }

    @Test
    void keycaps() {
        assertEquals(List.of(KEYCAP_1), emojiOf(KEYCAP_1));
        assertEquals(List.of("#️⃣"), emojiOf("#️⃣"));
        assertEquals(List.of("1⃣"), emojiOf("1⃣"));
        assertEquals(List.of(), emojiOf("12 #"));
    }

    @Test
    void aDanglingJoinerIsLeftAsText() {
        List<EmojiSegmenter.Segment> s = EmojiSegmenter.segment(GRIN + "‍");
        assertEquals(2, s.size());
        assertTrue(s.get(0).emoji());
        assertEquals(GRIN, s.get(0).text());
        assertFalse(s.get(1).emoji());
        assertEquals(1, EmojiSegmenter.count(GRIN + "‍" + "x"));
    }

    @Test
    void aJoinerBetweenTextIsNotAnEmoji() {
        assertEquals(List.of(), emojiOf("a‍b"));
        assertEquals(1, EmojiSegmenter.count(GRIN + "‍" + GRIN)); // joined into one sequence
    }

    @Test
    void loneSurrogatesDoNotBreakIt() {
        assertEquals("\uD83D", joined("\uD83D"));
        assertEquals("a\uDE00b", joined("a\uDE00b"));
        assertEquals(List.of(), emojiOf("\uD83D"));
    }

    @Test
    void segmentsAlwaysJoinBackToTheInput() {
        String[] pieces = {"a", " ", "1", "#", GRIN, HEART_VS, "❤", FAMILY, THUMB_TONE, US_FLAG, "🇺", KEYCAP_1, "‍", "️", "︎", "⃣",
            "\uD83D", "\uDE00", ENGLAND, "©", "©️", "é", RAINBOW, "🏽", "☀️", "✅"};
        Random rnd = new Random(42);
        for (int i = 0; i < 3000; i++) {
            StringBuilder sb = new StringBuilder();
            int n = 1 + rnd.nextInt(8);
            for (int k = 0; k < n; k++) sb.append(pieces[rnd.nextInt(pieces.length)]);
            String s = sb.toString();
            assertEquals(s, joined(s), "input " + s.chars().mapToObj(Integer::toHexString).collect(Collectors.joining(" ")));
        }
    }

    @Test
    void anEmojiSequenceIsNeverCutInTheMiddle() {
        // Whatever follows, the family stays whole
        for (String tail : new String[] {"", "x", GRIN, "‍", "️", "🏽"}) {
            List<String> e = emojiOf(FAMILY + tail);
            assertEquals(FAMILY, e.get(0).substring(0, FAMILY.length()));
        }
    }

    // ---------- keys ----------

    @Test
    void keysIgnoreVariationSelectors() {
        assertEquals("2764", EmojiSegmenter.key("❤"));
        assertEquals("2764", EmojiSegmenter.key(HEART_VS));
        assertEquals("1f600", EmojiSegmenter.key(GRIN));
        assertEquals("1f468-200d-1f469-200d-1f467-200d-1f466", EmojiSegmenter.key(FAMILY));
        assertEquals("1f3f3-200d-1f308", EmojiSegmenter.key(RAINBOW));
    }

    @Test
    void fromKeyTypesWhatAnEmojiKeyboardTypes() {
        assertEquals(GRIN, EmojiSegmenter.fromKey("1f600"));
        assertEquals(HEART_VS, EmojiSegmenter.fromKey("2764"));
        assertEquals(HEART_FIRE, EmojiSegmenter.fromKey("2764-fe0f-200d-1f525"));
        assertEquals(US_FLAG, EmojiSegmenter.fromKey("1f1fa-1f1f8"));
        assertEquals("", EmojiSegmenter.fromKey("zz-110000"));
    }

    // ---------- the bundled set ----------

    @Test
    void bundledSetIsReasonable() {
        EmojiSet set = EmojiSet.bundled();
        assertTrue(set.size() >= 100 && set.size() <= 250, "size " + set.size());
        assertEquals("1f600", set.fileFor(GRIN));
        assertEquals("2764", set.fileFor(HEART_VS));
        assertEquals("2764", set.fileFor("❤"));
        assertEquals("1f3f3-fe0f-200d-1f308", set.fileFor(RAINBOW));
        assertEquals("1f1fa-1f1f8", set.fileFor(US_FLAG));
        assertNull(set.fileFor("🦄")); // unicorn: not bundled
        assertNull(set.fileFor("hello"));
    }

    @Test
    void everyListedPictureExistsAndIs72Square() throws IOException {
        EmojiSet set = EmojiSet.bundled();
        long total = 0;
        for (String file : set.files()) {
            try (InputStream in = EmojiSet.class.getResourceAsStream(EmojiSet.TEXTURE_DIR + file + ".png")) {
                assertNotNull(in, "missing " + file);
                byte[] b = in.readAllBytes();
                total += b.length;
                assertEquals((byte) 0x89, b[0], file);
                assertEquals('P', b[1], file);
                int w = ((b[16] & 0xFF) << 24) | ((b[17] & 0xFF) << 16) | ((b[18] & 0xFF) << 8) | (b[19] & 0xFF);
                int h = ((b[20] & 0xFF) << 24) | ((b[21] & 0xFF) << 16) | ((b[22] & 0xFF) << 8) | (b[23] & 0xFF);
                assertEquals(72, w, file);
                assertEquals(72, h, file);
            }
        }
        assertTrue(total < 1_000_000, "pictures total " + total + " bytes");
    }

    @Test
    void licenceFilesAreBundledNextToThePictures() {
        assertNotNull(EmojiSet.class.getResourceAsStream(EmojiSet.TEXTURE_DIR + "NOTICE.txt"));
        assertNotNull(EmojiSet.class.getResourceAsStream(EmojiSet.TEXTURE_DIR + "LICENSE-GRAPHICS.txt"));
    }

    @Test
    void everyPickerEmojiIsOneSegmentWithItsOwnPicture() {
        EmojiSet set = EmojiSet.bundled();
        for (String file : set.files()) {
            String typed = EmojiSet.textOf(file);
            List<EmojiSegmenter.Segment> segs = EmojiSegmenter.segment(typed);
            assertEquals(1, segs.size(), file + " -> " + typed);
            assertTrue(segs.get(0).emoji(), file);
            assertEquals(file, set.fileFor(segs.get(0).text()), file);
        }
    }

    @Test
    void theFileListCanNotBeChanged() {
        List<String> files = EmojiSet.bundled().files();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> files.add("1f600"));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, files::clear);
    }

    @Test
    void indexRejectsBadNames() {
        EmojiSet set = new EmojiSet(List.of("1f600", "../evil", "1F601", "zz", "", "1f602-", "1f603.png"));
        assertEquals(List.of("1f600", "1f601"), set.files());
    }

    // ---------- the server's copy ----------

    @Test
    void echoComparisonIgnoresEmojiAndPunctuation() {
        assertTrue(EmojiEcho.matches("nice " + GRIN, "nice"));
        assertTrue(EmojiEcho.matches("Nice, work!", "nice work"));
        assertFalse(EmojiEcho.matches("nice", "something else"));
        assertFalse(EmojiEcho.matches(GRIN, ""));
    }

    @Test
    void strippedWhenEmojiWentMissing() {
        assertTrue(EmojiEcho.stripped("a " + GRIN, "a"));
        assertTrue(EmojiEcho.stripped("a " + GRIN + GRIN, "a " + GRIN));
        assertTrue(EmojiEcho.stripped("a " + GRIN, "a ?"));
        assertFalse(EmojiEcho.stripped("a " + GRIN, "a " + GRIN));
        assertFalse(EmojiEcho.stripped("a", "a"));
    }
}
