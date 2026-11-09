package com.porter.qwerte.messenger;

/**
 * Checks what the user typed for the public chat. This class rewrites nothing: the text is handed to the game as typed (only the spaces at its
 * ends are cut, as the game's own chat does), or refused with a reason. A text starting with "/" is a command, sent the
 * way the game's chat sends one (without the slash).
 *
 * <p>Chat text is not sent raw to the server: the game's send hook runs first, where Meteor treats a text starting with its
 * command prefix (a dot by default) as a Meteor command, and listeners such as BetterChat may change the text, just as
 * with the vanilla chat. Pure: no Minecraft classes.
 */
public final class GlobalSend {
    /** What the game's chat accepts in one message. */
    public static final int MAX_LENGTH = 256;

    public enum Kind {
        CHAT, COMMAND
    }

    /**
     * @param payload what is handed to the game: the chat message as typed, or the command without its leading slash
     * @param label   what may be shown about it: the message itself, or "/word ..." for a command (arguments can be secret)
     */
    public record Result(Kind kind, String payload, String label, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private GlobalSend() {
    }

    public static Result prepare(String raw) {
        if (raw == null) return fail("Nothing to send.");
        String text = raw.trim();
        if (text.isEmpty()) return fail("Nothing to send.");
        if (text.length() > MAX_LENGTH) return fail("Too long: " + text.length() + " of " + MAX_LENGTH + " characters.");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
                continue;
            }
            // The same characters the game's chat refuses
            if (c == '§' || c < ' ' || c == 127 || Character.isSurrogate(c)) return fail("The text has a character Minecraft does not allow in chat.");
        }
        if (text.charAt(0) == '/') {
            String command = text.substring(1);
            if (command.isBlank()) return fail("Type a command after the slash.");
            if (command.charAt(0) <= ' ') return fail("Type the command right after the slash (no space).");
            return new Result(Kind.COMMAND, command, commandLabel(command), null);
        }
        return new Result(Kind.CHAT, text, text, null);
    }

    /** "/word ..." (arguments may hold secrets such as a password, so they are never shown). */
    static String commandLabel(String command) {
        String c = command.strip();
        int space = c.indexOf(' ');
        return space < 0 ? "/" + c : "/" + c.substring(0, space) + " ...";
    }

    private static Result fail(String error) {
        return new Result(null, null, null, error);
    }
}
