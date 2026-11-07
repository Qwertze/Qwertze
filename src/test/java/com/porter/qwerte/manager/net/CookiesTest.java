package com.porter.qwerte.manager.net;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CookiesTest {
    @Test
    void readsTheSessionTokenOfTheServersCookie() {
        String header = "sm_session=AbC-_123; HttpOnly; SameSite=Strict; Path=/; Max-Age=2592000";
        assertEquals(Optional.of("AbC-_123"), Cookies.session(List.of(header)));
    }

    @Test
    void ignoresOtherCookies() {
        assertEquals(Optional.empty(), Cookies.session(List.of("other=1; Path=/", "xsm_session=2")));
        assertEquals(Optional.empty(), Cookies.session(List.of()));
        assertEquals(Optional.empty(), Cookies.session(null));
    }

    @Test
    void aClearedCookieGivesNothing() {
        assertEquals(Optional.empty(), Cookies.session(List.of("sm_session=; Max-Age=0; Path=/")));
        assertEquals(Optional.empty(), Cookies.session(List.of("sm_session=abc; Max-Age=0")));
        assertEquals(Optional.empty(), Cookies.session(List.of("sm_session=abc; max-age = 0")));
    }

    @Test
    void theLastHeaderWins() {
        assertEquals(Optional.of("new"), Cookies.session(List.of("sm_session=old", "sm_session=new")));
        assertEquals(Optional.empty(), Cookies.session(List.of("sm_session=old", "sm_session=; Max-Age=0")));
    }

    @Test
    void refusesValuesThatAreNotPlainTokens() {
        assertEquals(Optional.empty(), Cookies.session(List.of("sm_session=a b")));
        assertEquals(Optional.empty(), Cookies.session(List.of("sm_session=abc\r\nX-Evil: 1")));
        assertEquals(Optional.empty(), Cookies.session(List.of("sm_session=" + "a".repeat(300))));
    }
}
