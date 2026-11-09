package com.porter.qwerte.messenger;

/**
 * Compares a message the user sent with the copy the server shows back ("to X: ..."), to tell whether the server removed
 * the emoji. No Minecraft classes.
 */
public final class EmojiEcho {
    private EmojiEcho() {
    }

    /** Only the letters and digits, in lower case: what stays the same when a server drops or replaces symbols. */
    static String core(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(Character.toLowerCase(cp));
        }
        return sb.toString();
    }

    /** True when the message has no letters or digits at all. */
    static boolean symbolsOnly(String s) {
        return core(s).isEmpty();
    }

    /** The server's line is the message we sent: same letters and digits, or for a message without any the same text. */
    public static boolean matches(String sent, String echoed) {
        String a = core(sent);
        String b = core(echoed);
        if (a.isEmpty() || b.isEmpty()) {
            // Only symbols or emoji (":)", "???"): compare the cleaned texts themselves
            String x = ChatText.message(sent);
            return a.equals(b) && !x.isEmpty() && x.equals(ChatText.message(echoed));
        }
        return a.equals(b);
    }

    /** The message had emoji and the server's copy has fewer of them. */
    public static boolean stripped(String sent, String echoed) {
        int a = EmojiSegmenter.count(sent);
        return a > 0 && EmojiSegmenter.count(echoed) < a;
    }
}
