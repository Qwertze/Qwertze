package com.porter.qwerte.messenger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs regular expressions on untrusted text with a time limit. Java has no regex timeout, so the text is wrapped in a
 * CharSequence that throws once the deadline has passed; a catastrophic pattern then ends instead of hanging the game.
 * Pure: no Minecraft classes.
 */
public final class SafeRegex {
    /** Time one pattern may take on one line. */
    public static final long LIMIT_NANOS = 5_000_000L;

    private SafeRegex() {
    }

    /** Thrown from inside the matcher when the deadline passed. */
    public static final class Timeout extends RuntimeException {
        Timeout() {
            super("regex timed out", null, false, false);
        }
    }

    private static final class Deadline implements CharSequence {
        private final CharSequence base;
        private final long deadline;

        Deadline(CharSequence base, long deadline) {
            this.base = base;
            this.deadline = deadline;
        }

        @Override
        public int length() {
            return base.length();
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() - deadline > 0) throw new Timeout();
            return base.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Deadline(base.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return base.toString();
        }
    }

    /**
     * Matches the whole text against the pattern.
     *
     * @return the matcher on success (read groups with {@link #group}); null when it does not match
     * @throws Timeout when the pattern took longer than {@code limitNanos}
     */
    public static Matcher matchAll(Pattern p, String text, long limitNanos) {
        Matcher m = p.matcher(new Deadline(text, System.nanoTime() + limitNanos));
        return m.matches() ? m : null;
    }

    /** A named group's text after a match; null if the group did not take part. Safe after the match ended. */
    public static String group(Matcher m, String name) {
        try {
            return m.group(name);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null;
        }
    }
}
