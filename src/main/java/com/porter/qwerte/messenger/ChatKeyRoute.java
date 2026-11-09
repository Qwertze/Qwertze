package com.porter.qwerte.messenger;

/**
 * Decides who gets the chat screen that the game is about to open. The chat key opens a chat with an empty first text,
 * the command key one with "/" in it; a screen opened any other way (a clicked link, another mod, a restored draft)
 * has some other text or class and is left alone. Pure: no Minecraft classes.
 */
public final class ChatKeyRoute {
    public enum Route {
        MESSENGER, VANILLA
    }

    private ChatKeyRoute() {
    }

    /**
     * @param settingOn          the take-chat-key setting
     * @param initialText        the text the chat screen starts with, or null if unknown
     * @param messengerAvailable the Messenger can take the focus now (module on, in a world, no other screen)
     */
    public static Route route(boolean settingOn, String initialText, boolean messengerAvailable) {
        if (settingOn && messengerAvailable && initialText != null && initialText.isEmpty()) return Route.MESSENGER;
        return Route.VANILLA;
    }
}
