package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatKeyRouteTest {
    @Test
    void theChatKeyGoesToTheMessengerWhenOnAndAvailable() {
        assertEquals(ChatKeyRoute.Route.MESSENGER, ChatKeyRoute.route(true, "", true));
    }

    @Test
    void theCommandKeyStaysVanilla() {
        assertEquals(ChatKeyRoute.Route.VANILLA, ChatKeyRoute.route(true, "/", true));
    }

    @Test
    void anyOtherPrefilledTextStaysVanilla() {
        assertEquals(ChatKeyRoute.Route.VANILLA, ChatKeyRoute.route(true, "/msg Steve ", true));
        assertEquals(ChatKeyRoute.Route.VANILLA, ChatKeyRoute.route(true, "hello", true));
        assertEquals(ChatKeyRoute.Route.VANILLA, ChatKeyRoute.route(true, " ", true));
    }

    @Test
    void settingOffIsAlwaysVanilla() {
        assertEquals(ChatKeyRoute.Route.VANILLA, ChatKeyRoute.route(false, "", true));
        assertEquals(ChatKeyRoute.Route.VANILLA, ChatKeyRoute.route(false, "/", true));
    }

    @Test
    void fallsBackToVanillaWhenTheMessengerCanNotTakeFocus() {
        assertEquals(ChatKeyRoute.Route.VANILLA, ChatKeyRoute.route(true, "", false));
    }

    @Test
    void unknownTextIsVanilla() {
        assertEquals(ChatKeyRoute.Route.VANILLA, ChatKeyRoute.route(true, null, true));
    }
}
