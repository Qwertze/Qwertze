package com.porter.qwerte.messenger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;

/**
 * Finds whispers in chat lines. The line is capped in length, stripped of formatting codes, and matched against the
 * built-in formats that are on and then the user's extra patterns. The built-in patterns are anchored and linear, so
 * they run as they are; the user's patterns run under a time limit, and one that times out {@link #MAX_STRIKES} times is
 * switched off in this parser (a new parser, made when the settings change, starts afresh). Anything that does not
 * match, or whose player name is not a valid name, is not a whisper. Pure: no Minecraft classes.
 */
public final class DmParser {
    public static final int MAX_STRIKES = 3;

    /** What was found in one line. */
    public record Parsed(Direction direction, String name, String text) {
    }

    private final List<DmPattern> builtin = new ArrayList<>();
    private final List<DmPattern> user;
    private final int[] strikes;
    private final long limitNanos;
    private final Consumer<DmPattern> onDisabled;

    public DmParser(Set<DmFormat> formats, List<DmPattern> extra) {
        this(formats, extra, SafeRegex.LIMIT_NANOS, null);
    }

    public DmParser(Set<DmFormat> formats, List<DmPattern> extra, Consumer<DmPattern> onDisabled) {
        this(formats, extra, SafeRegex.LIMIT_NANOS, onDisabled);
    }

    DmParser(Set<DmFormat> formats, List<DmPattern> extra, long limitNanos, Consumer<DmPattern> onDisabled) {
        this.limitNanos = limitNanos;
        this.onDisabled = onDisabled;
        for (DmFormat f : DmFormat.values()) {
            if (formats.contains(f)) builtin.addAll(f.patterns());
        }
        this.user = extra == null ? List.of() : List.copyOf(extra);
        this.strikes = new int[user.size()];
    }

    /** How many of the user's patterns were switched off for being too slow. */
    public int disabledCount() {
        int n = 0;
        for (int s : strikes) {
            if (s >= MAX_STRIKES) n++;
        }
        return n;
    }

    /** The whisper in a chat line as plain text, or null if it is not one. */
    public Parsed parse(String line) {
        if (line == null || line.isEmpty() || line.length() > ChatText.MAX_LINE) return null;
        String text = ChatText.clean(line, ChatText.MAX_LINE);
        for (DmPattern p : builtin) {
            Matcher m = p.pattern().matcher(text);
            if (!m.matches()) continue;
            Parsed r = result(p, m);
            if (r != null) return r;
        }
        for (int i = 0; i < user.size(); i++) {
            if (strikes[i] >= MAX_STRIKES) continue;
            Parsed r = tryUser(i, text);
            if (r != null) return r;
        }
        return null;
    }

    /**
     * A vanilla whisper by its translation key and the two arguments' text (the translated sentence depends on the
     * player's language, the key does not). Null if the key is not a whisper's or the arguments are not usable.
     */
    public static Parsed parseVanilla(String key, String name, String message) {
        Direction dir;
        if ("commands.message.display.incoming".equals(key)) dir = Direction.IN;
        else if ("commands.message.display.outgoing".equals(key)) dir = Direction.OUT;
        else return null;
        if (name == null || !ChatText.isValidName(name)) return null;
        String m = ChatText.message(message);
        if (m.isEmpty()) return null;
        return new Parsed(dir, name, m);
    }

    private Parsed tryUser(int i, String text) {
        DmPattern p = user.get(i);
        try {
            Matcher m = SafeRegex.matchAll(p.pattern(), text, limitNanos);
            return m == null ? null : result(p, m);
        } catch (SafeRegex.Timeout | StackOverflowError e) {
            if (++strikes[i] == MAX_STRIKES && onDisabled != null) onDisabled.accept(p);
            return null;
        }
    }

    private static Parsed result(DmPattern p, Matcher m) {
        String name = SafeRegex.group(m, "name");
        String msg = SafeRegex.group(m, "msg");
        if (name == null || msg == null || !ChatText.isValidName(name)) return null;
        String clean = ChatText.message(msg);
        if (clean.isEmpty()) return null;
        return new Parsed(p.direction(), name, clean);
    }
}
