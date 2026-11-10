package com.porter.qwerte.messenger;

/**
 * Whether the vanilla chat list is hidden because the Messenger replaces it. Set by the Messenger module (on only while
 * the module is on and replace-chat is on), read by the chat mixin on every frame. Messages still arrive, are logged and
 * reach other mods; only the drawing of the list is skipped.
 */
public final class ChatReplace {
    private static volatile boolean hidden;

    private ChatReplace() {
    }

    public static boolean hidden() {
        return hidden;
    }

    public static void hidden(boolean value) {
        hidden = value;
    }
}
