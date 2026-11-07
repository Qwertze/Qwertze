package com.porter.qwerte.manager.net;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseParserTest {
    @Test
    void oneEvent() {
        SseParser p = new SseParser();
        List<SseParser.Event> e = p.feedChunk("event: hello\ndata: {\"a\":1}\n\n");
        assertEquals(1, e.size());
        assertEquals("hello", e.get(0).name());
        assertEquals("{\"a\":1}", e.get(0).data());
    }

    @Test
    void splitAnywhereGivesTheSameEvents() {
        String text = "retry: 1000\n\n: hb\nid: s:1\nevent: tiles\ndata: x\r\n\r\nevent: bye\r\ndata: y\r\r";
        List<SseParser.Event> whole = new SseParser().feedChunk(text);
        for (int cut = 1; cut < text.length(); cut++) {
            SseParser p = new SseParser();
            List<SseParser.Event> got = new ArrayList<>(p.feedChunk(text.substring(0, cut)));
            got.addAll(p.feedChunk(text.substring(cut)));
            assertEquals(whole, got, "cut at " + cut);
        }
        assertEquals(2, whole.size());
        assertEquals("tiles", whole.get(0).name());
        assertEquals("s:1", whole.get(0).id());
        assertEquals("bye", whole.get(1).name());
    }

    @Test
    void crlfSplitBetweenChunksIsOneBreak() {
        SseParser p = new SseParser();
        assertEquals(0, p.feedChunk("data: a\r").size());
        assertEquals(0, p.feedChunk("\ndata: b\r\n").size());
        List<SseParser.Event> e = p.feedChunk("\r\n");
        assertEquals("a\nb", e.get(0).data());
    }

    @Test
    void multiLineDataIsJoinedAndCommentsIgnored() {
        SseParser p = new SseParser();
        List<SseParser.Event> e = p.feedChunk(": ping\ndata: one\n:more\ndata:two\ndata\n\n");
        assertEquals(1, e.size());
        assertEquals("message", e.get(0).name());
        assertEquals("one\ntwo\n", e.get(0).data());
    }

    @Test
    void retryAndIdAreRemembered() {
        SseParser p = new SseParser();
        p.feedChunk("retry: 2500\nid: a:7\n\n");
        assertEquals(2500, p.retryMillis());
        assertEquals("a:7", p.lastEventId());
        p.feedChunk("retry: soon\n\n");
        assertEquals(2500, p.retryMillis());
        List<SseParser.Event> e = p.feedChunk("data: x\n\n");
        assertEquals("a:7", e.get(0).id());
    }

    @Test
    void blankLineWithoutDataDispatchesNothingAndResetsTheName() {
        SseParser p = new SseParser();
        assertTrue(p.feedChunk("event: x\n\n").isEmpty());
        assertEquals("message", p.feedChunk("data: 1\n\n").get(0).name());
    }

    @Test
    void feedLineAndBom() {
        SseParser p = new SseParser();
        assertTrue(p.feedLine("﻿event: a").isEmpty());
        assertTrue(p.feedLine("data: b").isEmpty());
        assertEquals("a", p.feedLine("").get(0).name());
        assertFalse(p.overflowed());
    }

    @Test
    void anHugeLineMarksOverflow() {
        SseParser p = new SseParser();
        StringBuilder sb = new StringBuilder("data: ");
        for (int i = 0; i < SseParser.MAX + 10; i++) sb.append('a');
        p.feedChunk(sb);
        assertTrue(p.overflowed());
    }

    @Test
    void manyDataLinesPastTheCapAreRefusedAndNoEventComes() {
        SseParser p = new SseParser();
        String line = "data: " + "a".repeat(1_000_000) + "\n";
        int events = 0;
        for (int i = 0; i < 7; i++) events += p.feedChunk(line).size();
        assertTrue(p.overflowed());
        events += p.feedChunk("\n").size();
        assertEquals(0, events);
        assertEquals(5 * 1024 * 1024, SseParser.MAX);
    }
}
