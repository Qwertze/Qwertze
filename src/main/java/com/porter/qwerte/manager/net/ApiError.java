package com.porter.qwerte.manager.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Sorts a server answer into what the window has to do about it. Mirrors how the website's page reacts. */
public final class ApiError {
    private ApiError() {
    }

    public enum Kind {
        OK,
        /** 304 to an If-None-Match: keep what you have. */
        NOT_MODIFIED,
        /** 401 on a normal path: the session is gone, show the login. */
        LOGIN,
        /** 401 from login, confirm or password: the password was wrong; the session stays. */
        WRONG_PASSWORD,
        /** 403 with mustChange: only the change-password panel works. */
        MUST_CHANGE,
        /** 403 "confirm password": ask for the password, then try the action once more. */
        CONFIRM,
        /** Any other 403. The body may name the missing permission. */
        NO_PERMISSION,
        NOT_FOUND,
        CONFLICT,
        RATE_LIMIT,
        /** 3xx: never followed, the cookie must not leave the address it was made for. */
        REDIRECT,
        BAD_REQUEST,
        SERVER,
        /** No answer at all. */
        NETWORK,
        /** The user cancelled the password prompt of a write or read that needed it. */
        CANCELLED
    }

    /** {@code status} 0 means the request failed before an answer arrived. */
    public static Kind classify(String path, int status, JsonElement body) {
        if (status <= 0) return Kind.NETWORK;
        if (status == 304) return Kind.NOT_MODIFIED;
        if (status >= 200 && status < 300) return Kind.OK;
        if (status >= 300 && status < 400) return Kind.REDIRECT;
        switch (status) {
            case 401:
                return passwordPath(path) ? Kind.WRONG_PASSWORD : Kind.LOGIN;
            case 403: {
                JsonObject o = body != null && body.isJsonObject() ? body.getAsJsonObject() : null;
                if (o != null && o.has("mustChange") && o.get("mustChange").isJsonPrimitive() && "true".equals(o.get("mustChange").getAsString())) {
                    return Kind.MUST_CHANGE;
                }
                if ("confirm password".equals(string(o, "error"))) return Kind.CONFIRM;
                return Kind.NO_PERMISSION;
            }
            case 404:
                return Kind.NOT_FOUND;
            case 409:
                return Kind.CONFLICT;
            case 429:
                return Kind.RATE_LIMIT;
            default:
                return status >= 500 ? Kind.SERVER : Kind.BAD_REQUEST;
        }
    }

    /** The paths whose 401 means "that password is wrong", not "you are logged out". */
    private static boolean passwordPath(String path) {
        return path != null && (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/confirm")
            || path.equals("/api/v1/auth/password") || path.equals("/api/v1/auth/setup"));
    }

    /** The {@code permission} a 403 names, or null. */
    public static String permission(JsonElement body) {
        return string(body != null && body.isJsonObject() ? body.getAsJsonObject() : null, "permission");
    }

    private static String string(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) return null;
        return o.get(key).getAsString();
    }
}
