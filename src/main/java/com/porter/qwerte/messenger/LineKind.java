package com.porter.qwerte.messenger;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Tells a line of player chat from a "console" line (a server system message, a death or join message, a notice from
 * Meteor or an addon). The Global view draws console lines at a lower opacity. Pure: no Minecraft classes; the caller
 * reads the translation key and the chat tag out of the game's message.
 *
 * <p>What is known for sure and what is a guess:
 * <ul>
 *   <li>The translation key of the message: {@code chat.type.text} and its relatives are player chat; any other key
 *       ({@code death.*}, {@code multiplayer.player.joined}, {@code chat.type.admin}, ...) is a console line. Sure.</li>
 *   <li>The chat tag the game attaches ({@code System} for what arrives as a system or game-info message). Reliable
 *       for vanilla, but a server that sends its player chat as a system message (2b2t does) is tagged System too,
 *       so a tag alone is not enough.</li>
 *   <li>The text: {@code <name> text} is player chat even under a System tag; a known prefix such as {@code [Meteor]} is
 *       a notice. A guess for everything else, so a rank format like {@code [VIP] Steve: hi} on a plugin server counts
 *       as player chat (the safe side: it is shown at full opacity).</li>
 * </ul>
 */
public final class LineKind {
    /** Notices of the client itself: Meteor ("[Meteor] Toggled ... on.") and Baritone. */
    public static final List<String> NOTICE_PREFIXES = List.of("[Meteor]", "[Baritone]");

    private LineKind() {
    }

    /** {@code <Name> text} or {@code <[Rank] Name> text}: the angle-bracket format of vanilla and of 2b2t. */
    private static final Pattern ANGLE_NAME = Pattern.compile("^<[^<>\\s][^<>]{0,40}> \\S.*", Pattern.DOTALL);
    /** {@code Steve: hi}, {@code [VIP] Steve » hi}, {@code [Mod][VIP] Steve > hi}: optional rank tags, a name, a separator, then text. */
    private static final Pattern NAME_THEN_SEPARATOR = Pattern.compile(
        "^(?:\\[[^\\]\\r\\n]{1,24}\\]\\s*)*[A-Za-z0-9_]{3,16}\\s*[:»›>]\\s+\\S.*", Pattern.DOTALL);
    /** Lines every server words the same way and no player types. */
    private static final Pattern CONSOLE_TEXT = Pattern.compile(
        "^\\S{1,32} (joined|left) the game$|^\\S{1,32} has (made the advancement|completed the challenge|reached the goal) \\[.*|^\\[Server\\].*",
        Pattern.DOTALL);

    /**
     * @param tag      the game's chat tag name ({@code GuiMessageTag.logTag()}, e.g. "System"), or null when there is none
     * @param key      the translation key of the message, or null for a plain text message
     * @param text     the line as shown (formatting codes allowed; they are skipped)
     * @param prefixes notice prefixes to treat as console lines, like "[Meteor]"
     * @return true for a console line
     */
    public static boolean isConsole(String tag, String key, String text, List<String> prefixes) {
        if (key != null) return !isPlayerKey(key);
        String t = ChatText.stripClientPrefix(ChatText.clean(text, 200)).strip();
        if (t.isEmpty()) return false;
        for (String p : prefixes) {
            if (!p.isEmpty() && t.startsWith(p)) return true;
        }
        if (CONSOLE_TEXT.matcher(t).matches()) return true;
        if (ANGLE_NAME.matcher(t).matches()) return false;
        // 2b2t-style player chat in other formats, even when the server sends it as a system message
        if (NAME_THEN_SEPARATOR.matcher(t).matches()) return false;
        if (tag != null && tag.regionMatches(true, 0, "System", 0, 6)) return true;
        if (tag != null && tag.equalsIgnoreCase("Meteor")) return true;
        return false;
    }

    /** What a public chat line is, for the Console routing ({@link ConsoleRoute}). */
    public enum Type {
        /** Player chat. */
        PLAYER,
        /** A notice of the client: Meteor, Baritone, a module or addon. */
        NOTICE,
        /** A player joined or left. */
        JOIN_LEAVE,
        /** A death message. Only recognised by its translation key (2b2t sends deaths as plain text). */
        DEATH,
        /** An advancement, challenge or goal. */
        ADVANCEMENT,
        /** Any other server or system line. */
        SERVER
    }

    private static final Pattern JOIN_LEAVE_TEXT = Pattern.compile("^\\S{1,32} (joined|left) the game$");
    private static final Pattern ADVANCEMENT_TEXT = Pattern.compile("^\\S{1,32} has (made the advancement|completed the challenge|reached the goal) \\[.*", Pattern.DOTALL);

    /** The type of a line; {@link Type#PLAYER} exactly when {@link #isConsole} is false. */
    public static Type type(String tag, String key, String text, List<String> prefixes) {
        if (!isConsole(tag, key, text, prefixes)) return Type.PLAYER;
        if (key != null) {
            if (key.startsWith("multiplayer.player.joined") || key.startsWith("multiplayer.player.left")) return Type.JOIN_LEAVE;
            if (key.startsWith("death.")) return Type.DEATH;
            if (key.startsWith("chat.type.advancement.")) return Type.ADVANCEMENT;
            return Type.SERVER;
        }
        String t = ChatText.stripClientPrefix(ChatText.clean(text, 200)).strip();
        if (isNotice(t, prefixes) || (tag != null && tag.equalsIgnoreCase("Meteor"))) return Type.NOTICE;
        if (JOIN_LEAVE_TEXT.matcher(t).matches()) return Type.JOIN_LEAVE;
        if (ADVANCEMENT_TEXT.matcher(t).matches()) return Type.ADVANCEMENT;
        return Type.SERVER;
    }

    /** Whether the line starts with one of the client's notice prefixes ("[Meteor]", ...): a line the client itself made. */
    public static boolean isNotice(String text, List<String> prefixes) {
        String t = ChatText.stripClientPrefix(ChatText.clean(text, 200)).strip();
        for (String p : prefixes) {
            if (!p.isEmpty() && t.startsWith(p)) return true;
        }
        return false;
    }

    /**
     * The player who said a public line written {@code <Name> text} (also {@code <[Rank] Name> text}), after any
     * timestamp or icon in front; null for any other line. The last name-like word in the brackets is the name, as in
     * {@link ChatLines#resolveName}.
     */
    public static String sender(String text) {
        if (text == null) return null;
        String t = ChatText.stripClientPrefix(ChatText.stripGlyphs(text)).strip();
        if (!ANGLE_NAME.matcher(t).matches()) return null;
        int end = t.indexOf('>');
        String name = ChatLines.resolveName(null, t.substring(1, end), null);
        return ChatText.isValidName(name) ? name : null;
    }

    /** Text-only guess, for lines loaded from the history file (the tag and key were not saved). */
    public static boolean guessConsole(String text, List<String> prefixes) {
        return isConsole(null, null, text, prefixes);
    }

    /** Player chat in the game's own translations (signed chat, /me, /say, team chat). */
    static boolean isPlayerKey(String key) {
        return key.equals("chat.type.text") || key.equals("chat.type.announcement") || key.equals("chat.type.emote")
            || key.equals("chat.type.team.text") || key.equals("chat.type.team.sent");
    }
}
