package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Text wrapping with emoji, image shrinking and the window's place. */
class LayoutTest {
    private static final TextLayout.Measure SIX = new TextLayout.Measure() {
        @Override
        public int width(String text) {
            return text.codePointCount(0, text.length()) * 6;
        }

        @Override
        public int emojiWidth() {
            return 10;
        }
    };
    private static final EmojiSet SET = EmojiSet.bundled();
    private static final String GRIN = "😀";

    private static List<String> rows(String text, int width) {
        return TextLayout.wrap(text, width, SET, SIX).stream()
            .map(l -> l.pieces().stream().map(p -> p.emojiFile() != null ? "<" + p.emojiFile() + ">" : p.text()).collect(Collectors.joining()))
            .collect(Collectors.toList());
    }

    @Test
    void shortTextIsOneLine() {
        assertEquals(List.of("hello"), rows("hello", 100));
        assertEquals(List.of(""), rows("", 100));
    }

    @Test
    void wrapsAtWords() {
        // 6 px per character, 60 px = 10 characters
        assertEquals(List.of("hello", "world foo", "bar"), rows("hello world foo bar", 60));
    }

    @Test
    void noSpaceAtTheStartOrEndOfALine() {
        for (String line : rows("aaaa bbbb cccc dddd eeee", 60)) {
            assertFalse(line.startsWith(" "), line);
            assertFalse(line.endsWith(" "), line);
        }
    }

    @Test
    void aWordWiderThanTheLineIsCut() {
        List<String> r = rows("x".repeat(25), 60);
        assertEquals(List.of("x".repeat(10), "x".repeat(10), "x".repeat(5)), r);
    }

    @Test
    void lineWidthsFit() {
        for (TextLayout.Line l : TextLayout.wrap("the quick brown fox " + GRIN + " jumps over the lazy dog " + GRIN + GRIN, 70, SET, SIX)) {
            assertTrue(l.width() <= 70, "width " + l.width());
        }
    }

    @Test
    void emojiBecomePictures() {
        assertEquals(List.of("hi <1f600> there"), rows("hi " + GRIN + " there", 200));
    }

    @Test
    void emojiWithoutAPictureStayText() {
        String unicorn = "🦄";
        assertEquals(List.of("a " + unicorn + " b"), rows("a " + unicorn + " b", 200));
    }

    @Test
    void aJoinedSequenceIsOnePictureNotSplit() {
        String technologist = "🧑‍💻";
        assertEquals(List.of("<1f9d1-200d-1f4bb>"), rows(technologist, 200));
        // even when the line is narrow, it is not cut apart
        assertEquals(List.of("ab", "<1f9d1-200d-1f4bb>"), rows("ab " + technologist, 20));
    }

    @Test
    void leftOverJoinersAreNotShown() {
        String unknownJoined = "🦄‍🦄"; // two unicorns, joined: no picture
        List<String> r = rows(unknownJoined, 200);
        assertFalse(r.get(0).contains("‍"));
    }

    @Test
    void emojiWiderThanTheLineStillGetsALine() {
        List<String> r = rows(GRIN, 4);
        assertEquals(List.of("<1f600>"), r);
    }

    @Test
    void manyEmojiWrap() {
        List<String> r = rows(GRIN.repeat(10), 50); // 5 per line
        assertEquals(2, r.size());
        assertEquals("<1f600>".repeat(5), r.get(0));
    }

    // ---------- image shrinking ----------

    @Test
    void boxFilterAveragesBlocks() {
        int[] src = new int[16];
        for (int i = 0; i < 16; i++) src[i] = 0xFF000000;
        // top-left 2x2 block: two white, two black -> grey
        src[0] = 0xFFFFFFFF;
        src[1] = 0xFFFFFFFF;
        int[] out = ImageScale.box(src, 4, 4, 2, 2);
        assertEquals(0xFF7F7F7F, out[0]);
        assertEquals(0xFF000000, out[1]);
        assertEquals(0xFF000000, out[3]);
    }

    @Test
    void boxFilterKeepsColourOfTransparentEdges() {
        int[] src = {0xFFFF0000, 0x00000000, 0x00000000, 0x00000000};
        int[] out = ImageScale.box(src, 2, 2, 1, 1);
        // a quarter opaque red: still pure red, quarter alpha
        assertEquals(0x3FFF0000, out[0]);
    }

    @Test
    void boxFilterSameSizeIsIdentity() {
        int[] src = {0xFF112233, 0x80445566, 0x00000000, 0xFFFFFFFF};
        assertEquals(List.of(0xFF112233, 0x80445566, 0, 0xFFFFFFFF), java.util.Arrays.stream(ImageScale.box(src, 2, 2, 2, 2)).boxed().collect(Collectors.toList()));
    }

    @Test
    void boxFilter72To36() {
        int[] src = new int[72 * 72];
        java.util.Arrays.fill(src, 0xFF336699);
        int[] out = ImageScale.box(src, 72, 72, 36, 36);
        assertEquals(36 * 36, out.length);
        for (int p : out) assertEquals(0xFF336699, p);
    }

    @Test
    void boxFilterRejectsBadSizes() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> ImageScale.box(new int[4], 2, 2, 0, 1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> ImageScale.box(new int[3], 2, 2, 1, 1));
    }

    // ---------- the window's place ----------

    @Test
    void geometryRoundTrip() {
        Geometry g = new Geometry(10, 20, 300, 200);
        assertEquals(g, Geometry.decode(g.encode()));
    }

    @Test
    void geometryRejectsGarbage() {
        assertNull(Geometry.decode(null));
        assertNull(Geometry.decode(""));
        assertNull(Geometry.decode("v1:1,2,3"));
        assertNull(Geometry.decode("v1:a,b,c,d"));
        assertNull(Geometry.decode("v2:1,2,3,4"));
        assertNull(Geometry.decode("v1:1,2,0,4"));
        assertNull(Geometry.decode("v1:1,2,3,-4"));
        assertNull(Geometry.decode("v1:1,2,99999999999,4"));
        assertNull(Geometry.decode("v1:1,2,3,4,5"));
    }

    @Test
    void geometryStaysReachable() {
        Geometry far = new Geometry(5000, 5000, 300, 200).clamp(800, 600);
        assertTrue(far.x() < 800 && far.y() < 600);
        Geometry gone = new Geometry(-5000, -5000, 300, 200).clamp(800, 600);
        assertTrue(gone.x() + gone.w() > 0 && gone.y() >= 0);
    }

    @Test
    void geometrySizeIsLimited() {
        Geometry small = new Geometry(0, 0, 10, 10).clamp(800, 600);
        assertEquals(Geometry.MIN_W, small.w());
        assertEquals(Geometry.MIN_H, small.h());
        Geometry huge = new Geometry(0, 0, 9000, 9000).clamp(800, 600);
        assertEquals(800, huge.w());
        assertEquals(600, huge.h());
        // a tiny screen wins over the minimum
        Geometry tiny = new Geometry(0, 0, 500, 500).clamp(100, 80);
        assertEquals(100, tiny.w());
        assertEquals(80, tiny.h());
    }

    @Test
    void initialGeometryFitsTheScreen() {
        Geometry g = Geometry.initial(427, 240);
        assertTrue(g.x() >= 0 && g.y() >= 0 && g.x() + g.w() <= 427 && g.y() + g.h() <= 240);
        assertTrue(g.contains(g.x() + 1, g.y() + 1));
        assertFalse(g.contains(g.x() - 1, g.y()));
    }
}
