package com.porter.qwerte.messenger;

/** Which side of the Messenger window the conversation list (and the narrow-layout toggle) is on. Pure. */
public enum ListSide {
    LEFT, RIGHT;

    /** The letter saved with the window's {@link Geometry}. */
    char code() {
        return this == RIGHT ? 'R' : 'L';
    }

    static ListSide fromCode(String s) {
        if ("L".equals(s)) return LEFT;
        if ("R".equals(s)) return RIGHT;
        return null;
    }
}
