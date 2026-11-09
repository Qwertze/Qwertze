package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmParserTest {
    private static final Set<DmFormat> ALL = EnumSet.allOf(DmFormat.class);

    private static DmParser all() {
        return new DmParser(ALL, List.of());
    }

    private static void expect(DmParser.Parsed p, Direction dir, String name, String text) {
        assertNotNull(p);
        assertEquals(dir, p.direction());
        assertEquals(name, p.name());
        assertEquals(text, p.text());
    }

    // ---------- the built-in formats ----------

    @Test
    void vanillaBothWays() {
        expect(all().parse("Steve whispers to you: hello there"), Direction.IN, "Steve", "hello there");
        expect(all().parse("You whisper to Alex_99: on my way"), Direction.OUT, "Alex_99", "on my way");
    }

    @Test
    void vanillaByTranslationKey() {
        expect(DmParser.parseVanilla("commands.message.display.incoming", "Steve", "hi"), Direction.IN, "Steve", "hi");
        expect(DmParser.parseVanilla("commands.message.display.outgoing", "Steve", "hi"), Direction.OUT, "Steve", "hi");
        assertNull(DmParser.parseVanilla("commands.message.display.other", "Steve", "hi"));
        assertNull(DmParser.parseVanilla("commands.message.display.incoming", "not a name", "hi"));
        assertNull(DmParser.parseVanilla("commands.message.display.incoming", "Steve", "   "));
    }

    @Test
    void twoB2tBothWays() {
        expect(all().parse("Steve whispers: meet at spawn"), Direction.IN, "Steve", "meet at spawn");
        expect(all().parse("to Steve: ok see you"), Direction.OUT, "Steve", "ok see you");
    }

    @Test
    void twoB2tOnlyWhenEnabled() {
        DmParser none = new DmParser(EnumSet.of(DmFormat.VANILLA), List.of());
        assertNull(none.parse("Steve whispers: meet at spawn"));
        assertNull(none.parse("to Steve: ok"));
        DmParser b = new DmParser(EnumSet.of(DmFormat.TWO_B2T), List.of());
        expect(b.parse("Steve whispers: x y"), Direction.IN, "Steve", "x y");
    }

    @Test
    void essentialsBothWays() {
        expect(all().parse("[Steve -> me] you there?"), Direction.IN, "Steve", "you there?");
        expect(all().parse("[me -> Steve] yes"), Direction.OUT, "Steve", "yes");
    }

    @Test
    void fromAndTo() {
        DmParser p = new DmParser(EnumSet.of(DmFormat.FROM_TO), List.of());
        expect(p.parse("From Steve: hey"), Direction.IN, "Steve", "hey");
        expect(p.parse("To Steve: hey back"), Direction.OUT, "Steve", "hey back");
    }

    @Test
    void messageMayContainColonsAndBrackets() {
        expect(all().parse("Steve whispers: a: b [c] -> d"), Direction.IN, "Steve", "a: b [c] -> d");
    }

    @Test
    void formattingCodesAreStripped() {
        expect(all().parse("§dSteve§r whispers: §ahi §lthere"), Direction.IN, "Steve", "hi there");
    }

    @Test
    void ordinaryChatIsNotAWhisper() {
        assertNull(all().parse("<Steve> hello"));
        assertNull(all().parse("<Steve> whispers: hello"));
        assertNull(all().parse("<Steve> From Alex: hello"));
        assertNull(all().parse("Steve joined the game"));
        assertNull(all().parse(""));
        assertNull(all().parse(null));
    }

    @Test
    void emptyMessageIsNotKept() {
        assertNull(all().parse("Steve whispers: "));
        assertNull(all().parse("Steve whispers: §a"));
    }

    @Test
    void emojiSurvivesParsing() {
        expect(all().parse("Steve whispers: hi 😀"), Direction.IN, "Steve", "hi 😀");
    }

    // ---------- names ----------

    @Test
    void badNamesAreRejected() {
        assertNull(all().parse("St whispers: hi"));
        assertNull(all().parse("Abcdefghijklmnopq whispers: hi")); // 17
        assertNull(all().parse("Ste.ve whispers: hi"));
        assertNull(all().parse("[Admin] Steve whispers: hi"));
        expect(all().parse("Abcdefghijklmnop whispers: hi"), Direction.IN, "Abcdefghijklmnop", "hi"); // 16
    }

    // ---------- hostile input ----------

    @Test
    void veryLongLinesAreNotLookedAt() {
        String line = "Steve whispers: " + "a".repeat(ChatText.MAX_LINE);
        assertNull(all().parse(line));
        assertNull(all().parse("x".repeat(1_000_000)));
    }

    @Test
    void longButAllowedLineIsCut() {
        DmParser.Parsed p = all().parse("Steve whispers: " + "b".repeat(ChatText.MAX_LINE - 16));
        assertNotNull(p);
        assertTrue(p.text().length() <= ChatText.MAX_TEXT);
    }

    @Test
    void hostileLinesAreFast() {
        String[] lines = {
            "to " + "a".repeat(590),
            "[" + "a".repeat(590),
            "From " + "_".repeat(300) + ":",
            ("Steve whispers: " + "§".repeat(280)),
            "Steve whispers to you:" + " ".repeat(500),
            "(".repeat(500),
        };
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 200; i++) {
                for (String l : lines) all().parse(l);
            }
        });
    }

    @Test
    void aSlowPatternTimesOutAndIsSwitchedOff() {
        // Written by hand to get past UserPatterns' own check: nested repeats take exponential time
        DmPattern evil = new DmPattern(Direction.IN, Pattern.compile("(?<name>(.*a){20})b(?<msg>.*)"));
        List<DmPattern> switchedOff = new java.util.ArrayList<>();
        DmParser parser = new DmParser(EnumSet.noneOf(DmFormat.class), List.of(evil), switchedOff::add);
        String line = "a".repeat(45) + "!";
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            for (int i = 0; i < DmParser.MAX_STRIKES + 2; i++) assertNull(parser.parse(line));
        });
        assertEquals(1, switchedOff.size()); // told once
        assertEquals(1, parser.disabledCount());
        assertEquals(0, new DmParser(EnumSet.noneOf(DmFormat.class), List.of(evil)).disabledCount()); // a new parser starts afresh
    }

    @Test
    void builtInPatternsAreNotTimeLimited() {
        // A 1 ns limit would stop any user pattern at once, but the built-in ones are linear and run as they are
        DmParser p = new DmParser(ALL, List.of(), 1L, null);
        for (int i = 0; i < 10; i++) expect(p.parse("Steve whispers: hi"), Direction.IN, "Steve", "hi");
        assertEquals(0, p.disabledCount());
    }

    @Test
    void deepRecursionDoesNotEscape() {
        // Java's regex recurses on (x|y)*: a long line can overflow the stack, which must not reach the caller
        DmPattern deep = new DmPattern(Direction.IN, Pattern.compile("(?<name>[a-z]{3,16}) (?<msg>(a|b)*)"));
        DmParser parser = new DmParser(EnumSet.noneOf(DmFormat.class), List.of(deep));
        assertNull(parser.parse("abc " + "a".repeat(590) + "!"));
    }

    @Test
    void nullBytesAndControlCharactersAreDropped() {
        expect(all().parse("Steve whispers: a\u0000b\u0007c\u001bd"), Direction.IN, "Steve", "abcd");
    }

    // ---------- extra patterns ----------

    @Test
    void extraPatternWorks() {
        UserPatterns.Result r = UserPatterns.parse(List.of(
            "in:\\[(?<name>\\w{3,16}) says\\] (?<msg>.*)",
            "out: \\[you say to (?<name>\\w{3,16})\\] (?<msg>.*)"));
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertEquals(2, r.patterns().size());
        DmParser p = new DmParser(EnumSet.noneOf(DmFormat.class), r.patterns());
        expect(p.parse("[Steve says] hello"), Direction.IN, "Steve", "hello");
        expect(p.parse("[you say to Steve] hello"), Direction.OUT, "Steve", "hello");
    }

    @Test
    void extraPatternWithoutPrefixIsIncoming() {
        UserPatterns.Result r = UserPatterns.parse(List.of("(?<name>\\w+) tells you (?<msg>.*)"));
        assertEquals(1, r.patterns().size());
        assertEquals(Direction.IN, r.patterns().get(0).direction());
    }

    @Test
    void extraPatternNeedsBothGroups() {
        assertEquals(1, UserPatterns.parse(List.of("in:(?<name>\\w+) (.*)")).errors().size());
        assertEquals(1, UserPatterns.parse(List.of("in:(\\w+) (?<msg>.*)")).errors().size());
        assertTrue(UserPatterns.parse(List.of("in:(\\w+) (.*)")).patterns().isEmpty());
    }

    @Test
    void extraPatternWithBadSyntaxIsRefused() {
        UserPatterns.Result r = UserPatterns.parse(List.of("in:(?<name>[a-z+) (?<msg>.*)", "in:(?<name>\\w+)(?<msg>.*", "in:\\"));
        assertEquals(3, r.errors().size());
        assertTrue(r.patterns().isEmpty());
    }

    @Test
    void extraPatternThatCanHangIsRefused() {
        assertFalse(UserPatterns.parse(List.of("in:(?<name>(.*a){20})b(?<msg>.*)")).errors().isEmpty());
        assertFalse(UserPatterns.parse(List.of("in:(?<name>\\w{3})(?<msg>.*.*.*.*.*.*)x")).errors().isEmpty());
        assertFalse(UserPatterns.parse(List.of("in:(?<name>((a*)*)*)b(?<msg>.*)")).errors().isEmpty());
    }

    @Test
    void extraPatternLimits() {
        String tooLong = "in:(?<name>\\w+) (?<msg>.*)" + "x".repeat(UserPatterns.MAX_LENGTH);
        assertFalse(UserPatterns.parse(List.of(tooLong)).errors().isEmpty());
        assertTrue(UserPatterns.parse(List.of("", "   ")).errors().isEmpty());
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < UserPatterns.MAX_PATTERNS + 3; i++) many.add("in:x" + i + " (?<name>\\w+) (?<msg>.*)");
        UserPatterns.Result r = UserPatterns.parse(many);
        assertEquals(UserPatterns.MAX_PATTERNS, r.patterns().size());
        assertEquals(3, r.errors().size());
    }

    @Test
    void extraPatternStillValidatesTheName() {
        UserPatterns.Result r = UserPatterns.parse(List.of("in:(?<name>\\S+) says (?<msg>.*)"));
        DmParser p = new DmParser(EnumSet.noneOf(DmFormat.class), r.patterns());
        assertNull(p.parse("a;b says hi"));
        assertNull(p.parse("ab says hi"));
        assertNotNull(p.parse("abc says hi"));
    }
}
