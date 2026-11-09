package com.porter.qwerte.messenger;

import java.util.regex.Pattern;

/** One compiled whisper pattern and the direction of the messages it matches. */
public final class DmPattern {
    private final Direction direction;
    private final Pattern pattern;

    public DmPattern(Direction direction, Pattern pattern) {
        this.direction = direction;
        this.pattern = pattern;
    }

    public Direction direction() {
        return direction;
    }

    public Pattern pattern() {
        return pattern;
    }

    /** The regex text. */
    public String source() {
        return pattern.pattern();
    }
}
