package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Player chat against console lines in Global. */
class LineKindTest {
    private static boolean console(String tag, String key, String text) {
        return LineKind.isConsole(tag, key, text, LineKind.NOTICE_PREFIXES);
    }

    @Test
    void signedPlayerChatKeyIsPlayer() {
        assertFalse(console(null, "chat.type.text", "<Steve> hello"));
        assertFalse(console("System", "chat.type.text", "<Steve> hello"));
        assertFalse(console(null, "chat.type.emote", "* Steve waves"));
        assertFalse(console(null, "chat.type.announcement", "[Steve] hi"));
    }

    @Test
    void otherTranslationKeysAreConsole() {
        assertTrue(console("System", "death.attack.player", "Steve was slain by Alex"));
        assertTrue(console("System", "multiplayer.player.joined", "Steve joined the game"));
        assertTrue(console(null, "chat.type.admin", "[Server: x]"));
        assertTrue(console(null, "commands.message.display.incoming", "x"));
    }

    @Test
    void angleBracketChatIsPlayerEvenUnderSystemTag() {
        // 2b2t sends player chat as a system message
        assertFalse(console("System", null, "<Steve> hello there"));
        assertFalse(console("System", null, "<[Mod] Steve> hello"));
        assertFalse(console("System", null, "<15:05> <Steve> hi"));
    }

    @Test
    void systemTaggedPlainTextIsConsole() {
        assertTrue(console("System", null, "Steve joined the game"));
        assertTrue(console("System", null, "Server restarting in 5 minutes"));
        assertTrue(console("System-Singleplayer", null, "Saved the game"));
    }

    @Test
    void meteorAndBaritoneNoticesAreConsole() {
        assertTrue(console(null, null, "[Meteor] Toggled Messenger on."));
        assertTrue(console("System", null, "[Meteor] Toggled Messenger on."));
        assertTrue(console(null, null, "§7[Meteor] §fhello"));
        assertTrue(console(null, null, "[Baritone] Done"));
        assertTrue(console("Meteor", null, "anything"));
    }

    @Test
    void untaggedPlainTextIsPlayerUnlessItLooksLikeConsole() {
        assertFalse(console(null, null, "Steve: hello"));
        assertFalse(console(null, null, "[VIP] Steve: hi"));
        assertTrue(console(null, null, "Steve joined the game"));
        assertTrue(console(null, null, "Steve left the game"));
        assertTrue(console(null, null, "Steve has made the advancement [Stone Age]"));
    }

    @Test
    void textGuessMatchesForLoadedHistory() {
        assertTrue(LineKind.guessConsole("Steve joined the game", LineKind.NOTICE_PREFIXES));
        assertFalse(LineKind.guessConsole("<Steve> hi", LineKind.NOTICE_PREFIXES));
        assertFalse(LineKind.guessConsole("", LineKind.NOTICE_PREFIXES));
    }

    @Test
    void anAngleBracketLineIsNotMadeConsoleByAClaimedJoin() {
        assertFalse(console(null, null, "<Steve> Alex joined the game"));
    }
}
