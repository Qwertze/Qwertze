package com.porter.qwerte.manager.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * One answer from the server. {@code status} is 0 when none arrived. The body is never logged: it can hold names and
 * messages from the server.
 */
public record ApiResult(int status, JsonElement body, String etag, long retryAfterSec, ApiError.Kind kind, long serverTime) {
    public ApiResult(int status, JsonElement body, String etag, long retryAfterSec, ApiError.Kind kind) {
        this(status, body, etag, retryAfterSec, kind, 0);
    }

    /** The server's clock (ms since 1970) from the Date header of the answer, or 0 when it sent none. */
    public long serverTimeMillis() {
        return serverTime;
    }

    public static ApiResult network() {
        return new ApiResult(0, JsonNull.INSTANCE, null, 0, ApiError.Kind.NETWORK, 0);
    }

    public static ApiResult cancelled() {
        return new ApiResult(0, JsonNull.INSTANCE, null, 0, ApiError.Kind.CANCELLED, 0);
    }

    public boolean ok() {
        return kind == ApiError.Kind.OK;
    }

    public JsonObject obj() {
        return body != null && body.isJsonObject() ? body.getAsJsonObject() : new JsonObject();
    }

    /** The server's {@code error} text, or "". */
    public String error() {
        JsonObject o = obj();
        return o.has("error") && o.get("error").isJsonPrimitive() ? o.get("error").getAsString() : "";
    }

    /** The longest wait shown for a 429 (an hour). */
    public static final long MAX_WAIT = 3600;

    /** Seconds to wait after a 429 ({@code Retry-After}, or the body's {@code retryAfter}), 1..3600; 60 when unknown. */
    public long wait429() {
        if (retryAfterSec > 0) return Math.min(MAX_WAIT, retryAfterSec);
        JsonObject o = obj();
        try {
            if (o.has("retryAfter") && o.get("retryAfter").isJsonPrimitive()) {
                double d = o.get("retryAfter").getAsDouble();
                if (!Double.isNaN(d)) return (long) Math.max(1, Math.min(MAX_WAIT, Math.ceil(d)));
            }
        } catch (NumberFormatException | ArithmeticException | UnsupportedOperationException | IllegalStateException ignored) {
            // not a number
        }
        return 60;
    }

    @Override
    public String toString() {
        return "ApiResult[status=" + status + ", kind=" + kind + "]";
    }
}
