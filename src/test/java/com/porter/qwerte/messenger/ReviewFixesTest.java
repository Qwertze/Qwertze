package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The dev.20 review items: the wrap cache key, player chat under a System tag, and log escaping. */
class ReviewFixesTest {
    private static boolean console(String tag, String text) {
        return LineKind.isConsole(tag, null, text, LineKind.NOTICE_PREFIXES);
    }

    @Test
    void whisperViewsHitTheCacheWhateverTheHeadsSetting() {
        // heads is "global && showHeads": false in a whisper view whether the setting is on or off, so the key is stable
        assertTrue(BlockCacheKey.same(200, 1f, false, 200, 1f, false));
        assertFalse(BlockCacheKey.same(200, 1f, true, 200, 1f, false));
        assertFalse(BlockCacheKey.same(201, 1f, false, 200, 1f, false));
        assertFalse(BlockCacheKey.same(200, 1.05f, false, 200, 1f, false));
    }

    @Test
    void systemTaggedPlayerChatInOtherFormatsIsPlayerChat() {
        assertFalse(console("System", "Steve: hi"));
        assertFalse(console("System", "[VIP] Steve » hi"));
        assertFalse(console("System", "[Mod][VIP] Steve > hi"));
        assertFalse(console("System", "<15:05> Steve: hi"));
        assertFalse(console("System", "Steve › hello there"));
    }

    @Test
    void systemTaggedConsoleLinesStayConsole() {
        assertTrue(console("System", "Server restarting in 5 minutes"));
        assertTrue(console("System", "Steve joined the game"));
        assertTrue(console("System", "[Server] Restart: soon"));
        assertTrue(console("System", "[Meteor] Toggled Messenger on."));
        assertTrue(console("System", "Steve was slain by Alex"));
    }

    @Test
    void noticeDetection() {
        assertTrue(LineKind.isNotice("[Meteor] Toggled x on.", LineKind.NOTICE_PREFIXES));
        assertTrue(LineKind.isNotice("<15:05> [Baritone] Done", LineKind.NOTICE_PREFIXES));
        assertFalse(LineKind.isNotice("<Steve> [Meteor] hi", LineKind.NOTICE_PREFIXES));
    }

    @Test
    void logEscapingKeepsTextOnOneQuotedLine() {
        assertEquals("a\\\"b\\\\c", ComponentText.escape("a\"b\\c"));
        assertEquals("x\\u000Ay", ComponentText.escape("x\ny"));
        assertEquals("x\\u2028y\\u2029z\\u0085", ComponentText.escape("x y z\u0085"));
        assertEquals("x\\u000Dy", ComponentText.escape("x\ry"));
    }

    @Test
    void logTextIsCut() {
        String s = "a".repeat(600);
        String e = ComponentText.escape(s, 512);
        assertEquals(512 + 3, e.length());
        assertTrue(e.endsWith("..."));
        assertEquals("abc", ComponentText.escape("abc", 512));
        assertEquals("null", ComponentText.escape(null, 512));
    }
}
