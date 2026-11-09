package com.porter.qwerte.messenger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Turns the user's extra-pattern lines into patterns, refusing bad ones. A line is {@code in: regex} (the default when
 * there is no prefix) or {@code out: regex}; the regex must match the whole line and have the named groups
 * {@code (?<name>...)} and {@code (?<msg>...)}. A pattern that takes too long on a few nasty probe lines is refused too.
 * Pure: no Minecraft classes.
 */
public final class UserPatterns {
    public static final int MAX_PATTERNS = 10;
    public static final int MAX_LENGTH = 200;

    /** The patterns that were accepted and one message per line that was not. */
    public record Result(List<DmPattern> patterns, List<String> errors) {
    }

    private static final long PROBE_NANOS = 20_000_000L;

    private UserPatterns() {
    }

    public static Result parse(List<String> lines) {
        List<DmPattern> ok = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (lines == null) return new Result(ok, errors);
        for (String raw : lines) {
            if (raw == null || raw.isBlank()) continue;
            if (ok.size() >= MAX_PATTERNS) {
                errors.add("Only " + MAX_PATTERNS + " extra patterns are used; ignored: " + shorten(raw));
                continue;
            }
            String line = raw.strip();
            Direction dir = Direction.IN;
            if (line.regionMatches(true, 0, "in:", 0, 3)) {
                line = line.substring(3).strip();
            } else if (line.regionMatches(true, 0, "out:", 0, 4)) {
                dir = Direction.OUT;
                line = line.substring(4).strip();
            }
            String error = check(line);
            if (error != null) {
                errors.add(error + ": " + shorten(raw));
                continue;
            }
            ok.add(new DmPattern(dir, Pattern.compile(line)));
        }
        return new Result(ok, errors);
    }

    /** Why a regex is refused, or null if it is fine. */
    static String check(String regex) {
        if (regex.isEmpty()) return "empty pattern";
        if (regex.length() > MAX_LENGTH) return "longer than " + MAX_LENGTH + " characters";
        Pattern p;
        try {
            p = Pattern.compile(regex);
        } catch (PatternSyntaxException | StackOverflowError e) {
            return "not a valid regex";
        }
        if (!p.namedGroups().containsKey("name") || !p.namedGroups().containsKey("msg")) return "needs the groups (?<name>...) and (?<msg>...)";
        for (String probe : probes()) {
            try {
                SafeRegex.matchAll(p, probe, PROBE_NANOS);
            } catch (SafeRegex.Timeout | StackOverflowError e) {
                return "takes too long on some text";
            }
        }
        return null;
    }

    /** Lines that make a badly written pattern (repeats inside repeats, several ".*" in a row) take far too long. */
    private static List<String> probes() {
        return List.of(
            "a".repeat(48) + "!",
            "1".repeat(48) + "x",
            " ".repeat(48) + "!",
            "ab".repeat(24) + "!",
            "a b ".repeat(16) + "!",
            "_".repeat(48) + ":");
    }

    private static String shorten(String s) {
        String t = s.strip();
        return t.length() > 40 ? t.substring(0, 40) + "..." : t;
    }
}
