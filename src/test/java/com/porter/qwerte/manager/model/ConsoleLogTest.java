package com.porter.qwerte.manager.model;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleLogTest {
    static final ZoneId UTC = ZoneId.of("UTC");

    static String entry(int seq, String command, String status, String outputs) {
        return "{\"seq\":" + seq + ",\"rev\":" + seq + ",\"command\":\"" + command + "\",\"hidden\":false,\"at\":1000,\"status\":\"" + status
            + "\",\"outputs\":" + outputs + "}";
    }

    static String answer(int rev, int first, boolean online, String... entries) {
        return "{\"rev\":" + rev + ",\"first\":" + first + ",\"online\":" + online + ",\"entries\":[" + String.join(",", entries) + "]}";
    }

    static boolean apply(ConsoleLog log, String json) {
        return log.apply(JsonParser.parseString(json));
    }

    @Test
    void entriesAreKeptByNumberAndReplaced() {
        ConsoleLog log = new ConsoleLog();
        assertTrue(apply(log, answer(2, 1, true, entry(1, "help", "done", "[]"), entry(2, "stats", "queued", "[]"))));
        assertEquals(2, log.size());
        assertEquals(2, log.rev());
        assertTrue(log.pending());
        // the same entry again: nothing changed; a new status replaces it
        assertFalse(apply(log, answer(2, 1, true, entry(2, "stats", "queued", "[]"))));
        assertTrue(apply(log, answer(3, 1, true, entry(2, "stats", "done", "[]"))));
        assertEquals(2, log.size());
        assertFalse(log.pending());
        assertEquals(3, log.rev());
    }

    @Test
    void entriesBeforeFirstAreDropped() {
        ConsoleLog log = new ConsoleLog();
        apply(log, answer(3, 1, true, entry(1, "a", "done", "[]"), entry(2, "b", "done", "[]"), entry(3, "c", "done", "[]")));
        assertTrue(apply(log, answer(3, 3, true)));
        assertEquals(1, log.size());
        assertEquals(3, log.entries().get(0).seq());
    }

    @Test
    void offlineIsTracked() {
        ConsoleLog log = new ConsoleLog();
        assertTrue(log.online());
        assertTrue(apply(log, answer(0, 0, false)));
        assertFalse(log.online());
        log.clear();
        assertTrue(log.online());
        assertEquals(0, log.rev());
    }

    @Test
    void garbageChangesNothing() {
        ConsoleLog log = new ConsoleLog();
        assertFalse(log.apply(null));
        assertFalse(log.apply(JsonParser.parseString("[1]")));
        assertFalse(apply(log, "{\"entries\":[3,{\"command\":\"no seq\"}]}"));
        assertEquals(0, log.size());
    }

    @Test
    void checksMirrorTheServer() {
        assertEquals("", ConsoleLog.check("  stats  "));
        assertFalse(ConsoleLog.check("   ").isEmpty());
        assertFalse(ConsoleLog.check(null).isEmpty());
        assertEquals("", ConsoleLog.check("x".repeat(500)));
        assertFalse(ConsoleLog.check("x".repeat(501)).isEmpty());
        assertFalse(ConsoleLog.check("a\u0007b").isEmpty());
        assertFalse(ConsoleLog.check("a\nb").isEmpty());
        assertEquals("ab", ConsoleLog.clean("a\u0000b"));
        assertEquals(500, ConsoleLog.clean("y".repeat(900)).length());
    }

    @Test
    void dangerousWordsNeedConfirm() {
        assertTrue(ConsoleLog.needsConfirm("update now"));
        assertTrue(ConsoleLog.needsConfirm("  AUTH login x"));
        assertTrue(ConsoleLog.needsConfirm("disconnect"));
        assertFalse(ConsoleLog.needsConfirm("stats"));
        assertFalse(ConsoleLog.needsConfirm("updates"));
        assertFalse(ConsoleLog.needsConfirm(""));
        assertTrue(ConsoleLog.confirmText("update", "Bot 1").contains("Bot 1"));
    }

    @Test
    void aPrefixOrAChainDoesNotSlipPastTheConfirmation() {
        // a leading slash or bang does not hide the word
        assertTrue(ConsoleLog.needsConfirm("/update now"));
        assertTrue(ConsoleLog.needsConfirm("!disconnect"));
        assertTrue(ConsoleLog.needsConfirm("  //  !auth login"));
        assertFalse(ConsoleLog.needsConfirm("/stats"));
        assertFalse(ConsoleLog.needsConfirm("/"));
        // anything that chains or substitutes another command asks
        assertTrue(ConsoleLog.needsConfirm("stats; disconnect"));
        assertTrue(ConsoleLog.needsConfirm("stats && update"));
        assertTrue(ConsoleLog.needsConfirm("stats | tee"));
        assertTrue(ConsoleLog.needsConfirm("stats $(update)"));
        assertTrue(ConsoleLog.needsConfirm("stats `update`"));
        assertTrue(ConsoleLog.needsConfirm("/stats;x"));
        assertFalse(ConsoleLog.needsConfirm("stats --all"));
    }

    @Test
    void historyWalksLikeTheWebsite() {
        ConsoleLog.History h = new ConsoleLog.History();
        assertNull(h.up());
        assertNull(h.down());
        h.push("a");
        h.push("a");
        h.push("b");
        assertEquals(2, h.size());
        assertNull(h.down());
        assertEquals("b", h.up());
        assertEquals("a", h.up());
        assertEquals("a", h.up());
        assertEquals("b", h.down());
        assertEquals("", h.down());
        assertNull(h.down());
        for (int i = 0; i < 150; i++) h.push("c" + i);
        assertEquals(ConsoleLog.MAX_HISTORY, h.size());
    }

    @Test
    void outputsBecomeColouredPlainLines() {
        String out = "[{\"color\":\"error\",\"title\":\"**Bad** thing\",\"description\":\"line one\\nuse `help`\","
            + "\"fields\":[{\"name\":\"Name\",\"value\":\"v1\"}],\"lines\":[\"raw 1\",\"raw 2\"]},{\"color\":\"#336699\",\"title\":\"T2\"}]";
        ConsoleLog log = new ConsoleLog();
        apply(log, answer(1, 1, true, entry(1, "help", "done", out)));
        List<ConsoleLog.Line> lines = log.lines(false, UTC);
        assertEquals("> help   done  00:00:01", lines.get(0).text());
        assertEquals(ConsoleLog.GOOD, lines.get(0).rgb());
        assertEquals("  Bad thing", lines.get(1).text());
        assertEquals(ConsoleLog.BAD, lines.get(1).rgb());
        assertEquals("  line one", lines.get(2).text());
        assertEquals("  use help", lines.get(3).text());
        assertEquals("  Name", lines.get(4).text());
        assertEquals("    v1", lines.get(5).text());
        assertEquals("  raw 1", lines.get(6).text());
        assertEquals("  raw 2", lines.get(7).text());
        assertEquals("  T2", lines.get(8).text());
        assertEquals(0x336699, lines.get(8).rgb());
    }

    @Test
    void errorsHiddenCommandsAndStreamerDisplay() {
        ConsoleLog log = new ConsoleLog();
        apply(log, answer(2, 1, true,
            "{\"seq\":1,\"command\":\"(hidden)\",\"hidden\":true,\"at\":0,\"status\":\"error\",\"error\":\"no way\",\"outputs\":[]}",
            entry(2, "stats", "sent", "[{\"title\":\"secret\"}]")));
        List<ConsoleLog.Line> shown = log.lines(false, UTC);
        assertEquals("(hidden command)   failed", shown.get(0).text());
        assertEquals("  no way", shown.get(1).text());
        assertEquals("", shown.get(2).text());
        assertEquals("> stats   running  00:00:01", shown.get(3).text());
        for (ConsoleLog.Line l : log.lines(true, UTC)) {
            assertFalse(l.text().contains("stats"));
            assertFalse(l.text().contains("secret"));
            assertFalse(l.text().contains("no way"));
        }
        assertEquals("not picked up", ConsoleLog.statusLabel("expired"));
    }

    @Test
    void ringLimitIsKept() {
        ConsoleLog log = new ConsoleLog();
        String[] many = new String[ConsoleLog.MAX_ENTRIES + 20];
        for (int i = 0; i < many.length; i++) many[i] = entry(i + 1, "c", "done", "[]");
        apply(log, answer(many.length, 0, true, many));
        assertEquals(ConsoleLog.MAX_ENTRIES, log.size());
        assertEquals(21, log.entries().get(0).seq());
    }
}
