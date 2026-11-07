package com.porter.qwerte.manager.net;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.porter.qwerte.manager.net.ApiError.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiErrorTest {
    private static Kind kind(String path, int status, String json) {
        return ApiError.classify(path, status, json == null ? JsonNull.INSTANCE : JsonParser.parseString(json));
    }

    @Test
    void successAndNotModified() {
        assertEquals(Kind.OK, kind("/api/v1/stashes", 200, "[]"));
        assertEquals(Kind.OK, kind("/api/v1/orders", 204, null));
        assertEquals(Kind.NOT_MODIFIED, kind("/api/v1/index", 304, null));
        assertEquals(Kind.NETWORK, kind("/api/v1/index", 0, null));
    }

    @Test
    void redirectsAreNeverFollowed() {
        assertEquals(Kind.REDIRECT, kind("/api/v1/stashes", 301, null));
        assertEquals(Kind.REDIRECT, kind("/api/v1/auth/login", 302, null));
        assertEquals(Kind.REDIRECT, kind("/api/v1/auth/state", 307, null));
    }

    @Test
    void a401OnANormalPathMeansLoggedOut() {
        assertEquals(Kind.LOGIN, kind("/api/v1/stashes", 401, "{\"ok\":false,\"error\":\"login required\"}"));
        assertEquals(Kind.LOGIN, kind("/api/v1/auth/logout", 401, null));
        assertEquals(Kind.LOGIN, kind("/api/v1/rescan", 401, null));
    }

    @Test
    void a401FromAPasswordPathMeansWrongPasswordAndKeepsTheSession() {
        assertEquals(Kind.WRONG_PASSWORD, kind("/api/v1/auth/login", 401, null));
        assertEquals(Kind.WRONG_PASSWORD, kind("/api/v1/auth/confirm", 401, null));
        assertEquals(Kind.WRONG_PASSWORD, kind("/api/v1/auth/password", 401, null));
    }

    @Test
    void a403IsMustChangeConfirmOrNoPermission() {
        assertEquals(Kind.MUST_CHANGE, kind("/api/v1/stashes", 403, "{\"ok\":false,\"error\":\"change your password first\",\"mustChange\":true}"));
        assertEquals(Kind.CONFIRM, kind("/api/v1/rescan", 403, "{\"error\":\"confirm password\"}"));
        assertEquals(Kind.NO_PERMISSION, kind("/api/v1/orders", 403, "{\"error\":\"you don't have permission to order\",\"permission\":\"order\"}"));
        assertEquals(Kind.NO_PERMISSION, kind("/api/v1/auth/login", 403, "{\"error\":\"this account is disabled\"}"));
        assertEquals(Kind.NO_PERMISSION, kind("/api/v1/x", 403, null));
        assertEquals(Kind.NO_PERMISSION, kind("/api/v1/x", 403, "[1]"));
    }

    @Test
    void otherStatuses() {
        assertEquals(Kind.NOT_FOUND, kind("/x", 404, null));
        assertEquals(Kind.CONFLICT, kind("/x", 409, null));
        assertEquals(Kind.RATE_LIMIT, kind("/x", 429, "{\"retryAfter\":30}"));
        assertEquals(Kind.BAD_REQUEST, kind("/x", 400, null));
        assertEquals(Kind.BAD_REQUEST, kind("/x", 413, null));
        assertEquals(Kind.SERVER, kind("/x", 500, null));
        assertEquals(Kind.SERVER, kind("/x", 503, null));
    }

    @Test
    void namesTheMissingPermission() {
        assertEquals("streamer", ApiError.permission(JsonParser.parseString("{\"permission\":\"streamer\"}")));
        assertNull(ApiError.permission(JsonParser.parseString("{}")));
        assertNull(ApiError.permission(JsonNull.INSTANCE));
    }

    @Test
    void retryAfterComesFromTheHeaderOrTheBody() {
        ApiResult withHeader = new ApiResult(429, JsonNull.INSTANCE, null, 45, Kind.RATE_LIMIT);
        assertEquals(45, withHeader.wait429());
        ApiResult withBody = new ApiResult(429, JsonParser.parseString("{\"retryAfter\":30}"), null, 0, Kind.RATE_LIMIT);
        assertEquals(30, withBody.wait429());
        assertEquals(60, new ApiResult(429, JsonNull.INSTANCE, null, 0, Kind.RATE_LIMIT).wait429());
    }
}
