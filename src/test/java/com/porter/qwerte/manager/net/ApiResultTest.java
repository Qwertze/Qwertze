package com.porter.qwerte.manager.net;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiResultTest {
    private static ApiResult with(long header, String body) {
        return new ApiResult(429, JsonParser.parseString(body), null, header, ApiError.Kind.RATE_LIMIT);
    }

    @Test
    void headerWinsAndIsClamped() {
        assertEquals(30, with(30, "{}").wait429());
        assertEquals(3600, with(Long.MAX_VALUE, "{}").wait429());
    }

    @Test
    void bodyNumberIsClampedAndMayBeFractional() {
        assertEquals(3600, with(0, "{\"retryAfter\":1e30}").wait429());
        assertEquals(3600, with(0, "{\"retryAfter\":99999999999999999999}").wait429());
        assertEquals(1, with(0, "{\"retryAfter\":-5}").wait429());
        assertEquals(3, with(0, "{\"retryAfter\":2.1}").wait429());
        assertEquals(1, with(0, "{\"retryAfter\":0}").wait429());
    }

    @Test
    void junkFallsBackToSixty() {
        assertEquals(60, with(0, "{\"retryAfter\":\"soon\"}").wait429());
        assertEquals(60, with(0, "{\"retryAfter\":{\"a\":1}}").wait429());
        assertEquals(60, with(0, "{}").wait429());
        assertEquals(60, with(0, "[1]").wait429());
    }
}
