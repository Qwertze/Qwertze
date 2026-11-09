package com.porter.qwerte.printer.source;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BuildQueueTest {
    private static BuildQueue.Parsed parse(String... lines) {
        return BuildQueue.parse(List.of(lines));
    }

    @Test
    void placementNamesAndFileEntriesParse() {
        BuildQueue.Parsed p = parse("  Base wall ", "file:towers/a", "FILE:b.litematic", "", "  ");
        assertTrue(p.errors().isEmpty());
        assertEquals(3, p.entries().size());
        assertEquals(BuildQueue.Kind.PLACEMENT, p.entries().get(0).kind());
        assertEquals("Base wall", p.entries().get(0).name());
        assertEquals(BuildQueue.Kind.FILE, p.entries().get(1).kind());
        assertEquals("towers/a", p.entries().get(1).name());
        assertNull(p.entries().get(1).origin());
        assertEquals("b", p.entries().get(2).label());
    }

    @Test
    void fileOriginIsParsedStrictly() {
        BuildQueue.Parsed p = parse("file:a@10,-64,300", "file:b@ 1, 2, 3", "file:c@1,2", "file:d@x,y,z", "file:e@1,2,3,4", "file:f@1.5,2,3", "file:g@");
        assertEquals(1, p.entries().size());
        assertArrayEquals(new int[] { 10, -64, 300 }, p.entries().get(0).origin());
        assertEquals(6, p.errors().size());
    }

    @Test
    void traversalAndAbsolutePathsAreRefused() {
        BuildQueue.Parsed p = parse("file:../x", "file:a/../../x", "file:C:\\x", "file:C:/x", "file:/etc/x", "file:\\\\host\\share\\x", "file:a\\b",
            "file:", "file:.", "file:a/b/c/d", "file:x.txt", "file:CON", "file:a//b");
        assertTrue(p.entries().isEmpty(), p.entries().toString());
        assertEquals(13, p.errors().size());
    }

    @Test
    void traversalBehindAnOriginIsStillRefused() {
        assertTrue(parse("file:../x@1,2,3").entries().isEmpty());
    }

    @Test
    void badPlacementNamesAreRefused() {
        BuildQueue.Parsed p = parse("a\nb", "x".repeat(101), "ok");
        assertEquals(1, p.entries().size());
        assertEquals(2, p.errors().size());
    }

    @Test
    void errorsNameTheEntryNumber() {
        BuildQueue.Parsed p = parse("fine", "file:../x");
        assertTrue(p.errors().get(0).startsWith("Entry 2 "));
    }

    @Test
    void tooManyEntriesAreCut() {
        List<String> raw = new ArrayList<>();
        for (int i = 0; i < BuildQueue.MAX_ENTRIES + 3; i++) raw.add("p" + i);
        BuildQueue.Parsed p = BuildQueue.parse(raw);
        assertEquals(BuildQueue.MAX_ENTRIES, p.entries().size());
        assertEquals(3, p.errors().size());
    }

    @Test
    void nullListIsEmpty() {
        assertTrue(BuildQueue.parse(null).entries().isEmpty());
    }

    private static BuildQueue queue(String... lines) {
        return new BuildQueue(parse(lines).entries());
    }

    @Test
    void advancingWalksTheListAndLabelsIt() {
        BuildQueue q = queue("one", "file:two", "three");
        assertEquals("Queue 1/3: one", q.label());
        q.complete();
        assertEquals("Queue 2/3: two", q.label());
        q.complete();
        q.complete();
        assertFalse(q.hasCurrent());
        assertNull(q.current());
        assertEquals(3, q.completed());
        assertEquals("Queue 3/3: three", q.label());
        q.complete();
        assertEquals(3, q.completed());
    }

    @Test
    void seekSkipsEntriesTheResolverRefuses() {
        BuildQueue q = queue("a", "b", "c", "d");
        Set<String> missing = Set.of("a", "b", "d");
        List<String> heard = new ArrayList<>();
        assertTrue(q.seek(e -> missing.contains(e.name()) ? "missing" : null, (e, why) -> heard.add(e.name() + ":" + why)));
        assertEquals("c", q.current().name());
        assertEquals(List.of("a:missing", "b:missing"), heard);
        assertEquals(2, q.skipped());
        assertTrue(q.seek(e -> missing.contains(e.name()) ? "missing" : null, (e, why) -> heard.add(e.name())));
        assertEquals("c", q.current().name(), "an accepted entry stays current");
        q.complete();
        assertFalse(q.seek(e -> missing.contains(e.name()) ? "missing" : null, (e, why) -> heard.add(e.name() + ":" + why)));
        assertEquals(3, q.skipped());
        assertEquals(1, q.completed());
        assertFalse(q.hasCurrent());
    }

    @Test
    void seekOnAnEmptyQueueIsFalse() {
        assertFalse(new BuildQueue(List.of()).seek(e -> null, (e, w) -> {}));
        assertEquals("Queue 0/0", new BuildQueue(List.of()).label());
    }

    @Test
    void skipMovesOnWithoutCountingAsDone() {
        BuildQueue q = queue("a", "b");
        q.skip();
        assertEquals("b", q.current().name());
        assertEquals(0, q.completed());
        assertEquals(1, q.skipped());
    }
}
