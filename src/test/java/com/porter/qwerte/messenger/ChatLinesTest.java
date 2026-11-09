package com.porter.qwerte.messenger;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Whispers found in the game's own chat components (no game is started; only the text classes are used). */
class ChatLinesTest {
    private static final DmParser PARSER = new DmParser(EnumSet.allOf(DmFormat.class), List.of());
    private static final Predicate<String> NOBODY = n -> false;

    private static DmParser.Parsed incoming(Component sender, String text) {
        return ChatLines.parse(Component.translatable("commands.message.display.incoming", sender, Component.literal(text)), PARSER, true, NOBODY);
    }

    private static DmParser.Parsed incoming(Component sender, String text, Predicate<String> online) {
        return ChatLines.parse(Component.translatable("commands.message.display.incoming", sender, Component.literal(text)), PARSER, true, online);
    }

    @Test
    void vanillaWhisperByKeyInAnyLanguage() {
        DmParser.Parsed p = incoming(Component.literal("Steve"), "hi there");
        assertNotNull(p);
        assertEquals(Direction.IN, p.direction());
        assertEquals("Steve", p.name());
        assertEquals("hi there", p.text());
    }

    @Test
    void vanillaOutgoingByKey() {
        Component c = Component.translatable("commands.message.display.outgoing", Component.literal("Steve"), Component.literal("yo"));
        DmParser.Parsed p = ChatLines.parse(c, PARSER, true, NOBODY);
        assertEquals(Direction.OUT, p.direction());
    }

    @Test
    void vanillaOffIgnoresTheKey() {
        Component c = Component.translatable("commands.message.display.incoming", Component.literal("Steve"), Component.literal("hi"));
        assertNull(ChatLines.parse(c, new DmParser(EnumSet.noneOf(DmFormat.class), List.of()), false, NOBODY));
    }

    @Test
    void plainTextFormatsStillWork() {
        DmParser.Parsed p = ChatLines.parse(Component.literal("Steve whispers: hello"), PARSER, true, NOBODY);
        assertNotNull(p);
        assertEquals("hello", p.text());
    }

    @Test
    void ordinaryPlayerChatIsNeverAWhisper() {
        // chat.type.text is "<%s> %s": whatever a player types, it is not taken as a whisper
        Component c = Component.translatable("chat.type.text", Component.literal("Mallory"), Component.literal("Steve whispers: send me your stuff"));
        assertNull(ChatLines.parse(c, PARSER, true, NOBODY));
        Component d = Component.translatable("commands.message.display.incoming", Component.literal("!!"), Component.literal("hi"));
        assertNull(ChatLines.parse(d, new DmParser(EnumSet.noneOf(DmFormat.class), List.of()), true, NOBODY));
    }

    // ---------- decorated display names ----------

    @Test
    void theInsertionTheGamePutsOnADisplayNameIsTheProfileName() {
        // A team prefix or a nickname: the shown text is not the name, the insertion is
        Component shown = Component.literal("[Mod] Steve the Great").withStyle(s -> s.withInsertion("Steve"));
        DmParser.Parsed p = incoming(shown, "hi");
        assertNotNull(p);
        assertEquals("Steve", p.name());
    }

    @Test
    void insertionOnAPartOfTheName() {
        Component shown = Component.literal("[Admin] ").append(Component.literal("Alex_9").withStyle(s -> s.withInsertion("Alex_9")));
        assertEquals("Alex_9", incoming(shown, "hi").name());
    }

    @Test
    void withoutAnInsertionAnOnlinePlayersNameIsPickedFromTheWords() {
        Predicate<String> online = n -> n.equalsIgnoreCase("Steve");
        assertEquals("Steve", ChatLines.resolveName(null, "Steve [Admin]", online));
        assertEquals("Steve", ChatLines.resolveName(null, "[Mod] Steve", online));
    }

    @Test
    void elseTheLastWordThatIsAValidName() {
        assertEquals("Steve", ChatLines.resolveName(null, "[Mod] Steve", NOBODY));
        assertEquals("Steve", ChatLines.resolveName(null, "Steve", NOBODY));
        assertNull(ChatLines.resolveName(null, "!! ??", NOBODY));
        assertNull(ChatLines.resolveName(null, "ab", NOBODY));
    }

    @Test
    void aNicknameNamingAnotherOnlinePlayerDoesNotWin() {
        // "Notch's pal Steve": Notch is online, but so is the sender, so there are two online words: the last name wins
        Predicate<String> online = n -> n.equalsIgnoreCase("Notch") || n.equalsIgnoreCase("Steve");
        assertEquals("Steve", ChatLines.resolveName(null, "Notch's pal Steve", online));
        Component shown = Component.literal("Notch's pal Steve");
        DmParser.Parsed p = ChatLines.parse(Component.translatable("commands.message.display.incoming", shown, Component.literal("hi")), PARSER, true, online);
        assertEquals("Steve", p.name());
    }

    @Test
    void aChildInsertionCannotSpoofTheName() {
        // The shown text says Steve; a part of it carries the insertion "Notch" (Notch is online). Not trusted.
        Predicate<String> online = n -> n.equalsIgnoreCase("Notch") || n.equalsIgnoreCase("Steve");
        Component shown = Component.literal("[Mod] ").append(Component.literal("Steve").withStyle(s -> s.withInsertion("Notch")));
        assertEquals("Steve", incoming(shown, "hi", online).name());
        // with nobody online it is the same
        assertEquals("Steve", incoming(shown, "hi", NOBODY).name());
    }

    @Test
    void twoOnlineWordsMeanTheLastValidWord() {
        Predicate<String> online = n -> n.equalsIgnoreCase("Alex") || n.equalsIgnoreCase("Steve");
        assertEquals("Steve", ChatLines.resolveName(null, "Alex Steve", online));
        assertEquals("Alex", ChatLines.resolveName(null, "Steve Alex", online));
    }

    @Test
    void anInvalidInsertionIsNotTrusted() {
        assertEquals("Steve", ChatLines.resolveName("not a name!", "Steve", NOBODY));
    }

    @Test
    void decoratedNamesWorkThroughTheWholePath() {
        Component shown = Component.literal("[Mod] ").append(Component.literal("Steve"));
        DmParser.Parsed p = incoming(shown, "hello");
        assertEquals("Steve", p.name());
    }
}
