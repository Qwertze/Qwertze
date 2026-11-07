package com.porter.qwerte.manager.model;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthStateTest {
    private static AuthState parse(String json) {
        return AuthState.parse(JsonParser.parseString(json));
    }

    @Test
    void loggedOutState() {
        AuthState s = parse("{\"setupNeeded\":false,\"setupAllowed\":false,\"codeRequired\":false,\"loggedIn\":false,\"elevated\":false,\"minLength\":14}");
        assertFalse(s.loggedIn());
        assertEquals(14, s.minLength());
        assertFalse(s.forcedStreamer());
        assertFalse(AuthState.parse(JsonNull.INSTANCE).loggedIn());
        assertFalse(parse("[]").loggedIn());
    }

    @Test
    void setupNeeded() {
        AuthState s = parse("{\"setupNeeded\":true,\"setupAllowed\":false,\"loggedIn\":false,\"message\":\"Set the dashboard password on the server PC\"}");
        assertTrue(s.setupNeeded());
        assertFalse(s.setupAllowed());
        assertEquals("Set the dashboard password on the server PC", s.message());
    }

    @Test
    void anOwnerCanDoEverythingButIsNeverForced() {
        AuthState s = parse("{\"loggedIn\":true,\"elevated\":true,\"elevatedUntil\":1767225600000,\"user\":{\"id\":\"owner\",\"name\":\"boss\",\"owner\":true,\"mustChange\":false,\"perms\":{\"streamer\":true}}}");
        assertTrue(s.owner());
        assertTrue(s.can("rescan"));
        assertTrue(s.can("order"));
        assertTrue(s.can("viewer"));
        assertFalse(s.can("streamer"));
        assertFalse(s.forcedStreamer());
        assertEquals("change", s.permMode("proxy"));
        assertEquals("full", s.permMode("console"));
        assertEquals(1767225600000L, s.elevatedUntil());
        assertEquals("boss", s.userName());
    }

    @Test
    void aServerWithoutUsersIsTheOwner() {
        AuthState s = parse("{\"loggedIn\":true}");
        assertTrue(s.owner());
        assertTrue(s.can("view"));
        assertFalse(s.mustChange());
    }

    @Test
    void aSubUserHasOnlyItsPermissions() {
        AuthState s = parse("{\"loggedIn\":true,\"user\":{\"id\":\"u-1\",\"name\":\"bob\",\"owner\":false,\"mustChange\":false,"
            + "\"perms\":{\"view\":true,\"order\":true,\"rescan\":false,\"proxy\":\"read\",\"console\":\"none\"}}}");
        assertFalse(s.owner());
        assertTrue(s.can("view"));
        assertTrue(s.can("order"));
        assertFalse(s.can("rescan"));
        assertFalse(s.can("ordersAll"));
        assertEquals("read", s.permMode("proxy"));
        assertEquals("none", s.permMode("console"));
        assertEquals("none", s.permMode("missing"));
        assertFalse(s.forcedStreamer());
    }

    @Test
    void forcedStreamerNeedsASubUserWithTheFlagAndALogin() {
        String user = "\"user\":{\"id\":\"u-2\",\"name\":\"kid\",\"owner\":false,\"perms\":{\"view\":true,\"streamer\":true}}";
        assertTrue(parse("{\"loggedIn\":true," + user + "}").forcedStreamer());
        assertFalse(parse("{\"loggedIn\":false," + user + "}").forcedStreamer());
        // a string "true" is not the flag
        assertFalse(parse("{\"loggedIn\":true,\"user\":{\"id\":\"u-3\",\"owner\":false,\"perms\":{\"streamer\":\"true\"}}}").forcedStreamer());
    }

    @Test
    void mustChangePassword() {
        AuthState s = parse("{\"loggedIn\":true,\"user\":{\"id\":\"u-4\",\"name\":\"new\",\"owner\":false,\"mustChange\":true,\"perms\":{\"view\":true}}}");
        assertTrue(s.mustChange());
    }

    @Test
    void wrongTypesFallBackToDefaults() {
        AuthState s = parse("{\"loggedIn\":true,\"minLength\":\"x\",\"user\":{\"owner\":false,\"perms\":[1,2],\"name\":5}}");
        assertEquals(12, s.minLength());
        assertFalse(s.can("view"));
        assertEquals("", s.userName());
    }
}
