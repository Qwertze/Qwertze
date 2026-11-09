package com.porter.qwerte.messenger;

import java.util.regex.Pattern;

/**
 * Makes the command that sends a whisper, from the template, the player and what the user typed. Refuses instead of
 * guessing: a bad name, an empty text, a command over 256 characters, a template that could turn the text into a
 * command of its own. Pure: no Minecraft classes.
 */
public final class SendText {
    public static final String DEFAULT_TEMPLATE = "/msg {name} {text}";
    /** Minecraft's limit for one chat message or command typed by a player. */
    public static final int MAX_COMMAND = 256;

    /** The command without its leading slash (what the game's sendCommand takes) or the reason it was refused. */
    public record Result(String command, String text, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    // Exactly: a fixed command word, one space, {name}, one space, {text}. Nothing between the word and {name}, nothing after.
    private static final Pattern TEMPLATE = Pattern.compile("/[A-Za-z0-9_:.\\-]{1,32} \\{name\\} \\{text\\}");

    private SendText() {
    }

    /** True if the template is exactly {@code /<word> {name} {text}}: the typed text can only fill the last argument. */
    public static boolean validTemplate(String template) {
        return template != null && template.length() <= 100 && TEMPLATE.matcher(template).matches();
    }

    private static final java.util.Set<String> WHISPER_COMMANDS = java.util.Set.of("msg", "w", "tell", "whisper", "t", "m", "pm", "dm", "r",
        "minecraft:msg", "minecraft:tell", "minecraft:w", "emsg", "ewhisper", "etell");

    /** The template's command word is a known whisper command. Others are allowed, but worth a warning. */
    public static boolean isWhisperCommand(String template) {
        if (template == null || template.length() < 2) return false;
        int end = template.indexOf(' ');
        String word = template.substring(1, end < 0 ? template.length() : end);
        return WHISPER_COMMANDS.contains(word.toLowerCase(java.util.Locale.ROOT));
    }

    /** Newlines become spaces, section signs and control characters go, edges are trimmed. */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') sb.append(' ');
            else if (c == '§') continue; // only the sign: the character after it is ordinary text
            else if (Character.isHighSurrogate(c) && i + 1 < raw.length() && Character.isLowSurrogate(raw.charAt(i + 1))) {
                sb.append(c).append(raw.charAt(++i)); // a whole pair (emoji)
            } else if (Character.isSurrogate(c)) continue; // half a pair: the server would reject the message
            else if (c < ' ' || c == 127 || (c >= 0x80 && c < 0xA0)) continue;
            else sb.append(c);
        }
        return sb.toString().strip();
    }

    public static Result prepare(String template, String name, String rawText) {
        String text = sanitize(rawText);
        if (text.isEmpty()) return fail("Nothing to send.");
        if (!ChatText.isValidName(name)) return fail("Not a valid player name.");
        if (!validTemplate(template)) return fail("The command template in the settings is not valid (like /msg {name} {text}).");
        String full = template.replace("{name}", name).replace("{text}", text);
        String command = full.substring(1); // no leading slash for sendCommand
        if (full.length() > MAX_COMMAND) return fail("Too long: " + full.length() + " of " + MAX_COMMAND + " characters (including the command).");
        return new Result(command, text, null);
    }

    private static Result fail(String error) {
        return new Result(null, null, error);
    }
}
