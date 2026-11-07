package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Small helpers for HttpExchange requests and responses. The Router closes every exchange. */
final class Http {
    static final long MAX_BODY = 8L * 1024 * 1024;
    /** bot/* (dashboard) bodies: the proxy config's schema and values are a few hundred KiB at most. */
    static final long MAX_BOT_BODY = 1024L * 1024;
    static final String JSON_TYPE = "application/json; charset=utf-8";

    private Http() {
    }

    /** Thrown by readBody when the body is over the limit. */
    static final class TooLarge extends IOException {
        TooLarge() {
            super("body too large");
        }
    }

    static void securityHeaders(Headers h) {
        h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self'; font-src 'self'; script-src 'self'; "
            + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Frame-Options", "DENY");
    }

    /**
     * Per-request values (session token, key generation, signing state, cached body). Not HttpExchange attributes:
     * the JDK server keeps those in the HttpContext, so they are shared by every request at once. Cleared by the Router
     * when the exchange ends.
     */
    private static final java.util.concurrent.ConcurrentHashMap<HttpExchange, java.util.concurrent.ConcurrentHashMap<String, Object>> ATTRS =
        new java.util.concurrent.ConcurrentHashMap<>();

    static Object attr(HttpExchange ex, String name) {
        java.util.Map<String, Object> m = ATTRS.get(ex);
        return m == null ? null : m.get(name);
    }

    static void attr(HttpExchange ex, String name, Object value) {
        java.util.Map<String, Object> m = ATTRS.computeIfAbsent(ex, k -> new java.util.concurrent.ConcurrentHashMap<>());
        if (value == null) m.remove(name);
        else m.put(name, value);
    }

    static void clearAttrs(HttpExchange ex) {
        ATTRS.remove(ex);
    }

    /**
     * Exchange attribute: a handler handed the exchange to another thread (a viewer stream whose write is stuck),
     * which closes it; the Router must not (closing would block behind that write).
     */
    static final String DETACHED = "stashmanager.detached";
    /** Exchange attribute: the ServerIds that mask this response (a user in forced streamer mode). */
    static final String MASK = "stashmanager.mask";
    /** Exchange attribute: the request body, read once by the Router to check a signature (byte[]). */
    static final String BODY = "stashmanager.body";
    /** Exchange attributes for signing the response to a signed request: the key K (byte[]) and the request nonce. */
    static final String SIGN_KEY = "stashmanager.signKey";
    static final String SIGN_NONCE = "stashmanager.signNonce";
    /** The uncompressed body of the response being sent (byte[]), when it is sent gzip-compressed. */
    private static final String PLAIN_BODY = "stashmanager.plainBody";

    static byte[] readBody(HttpExchange ex, long max) throws IOException {
        if (Http.attr(ex, BODY) instanceof byte[] cached) {
            if (cached.length > max) throw new TooLarge();
            return cached;
        }
        String length = ex.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try {
                if (Long.parseLong(length.trim()) > max) throw new TooLarge();
            } catch (NumberFormatException ignored) {
            }
        }
        InputStream in = ex.getRequestBody();
        byte[] data = IconService.readCapped(in, max);
        if (data == null) throw new TooLarge();
        return data;
    }

    static void send(HttpExchange ex, int status, String contentType, byte[] body, String cacheControl) throws IOException {
        Headers h = ex.getResponseHeaders();
        if (contentType != null) h.set("Content-Type", contentType);
        if (cacheControl != null) h.set("Cache-Control", cacheControl);
        if (h.containsKey("ETag")) {
            // A cached response is per login: never reuse one across users of the same browser.
            String vary = h.getFirst("Vary");
            if (vary == null) h.set("Vary", "Cookie");
            else if (!vary.contains("Cookie")) h.set("Vary", vary + ", Cookie");
        }
        if (Http.attr(ex, SIGN_KEY) instanceof byte[] k && Http.attr(ex, SIGN_NONCE) instanceof String nonce) {
            // A response to a signed request is signed too, over the uncompressed body (empty for 204/304/HEAD).
            byte[] plain = Http.attr(ex, PLAIN_BODY) instanceof byte[] p ? p : body;
            boolean empty = "HEAD".equals(ex.getRequestMethod()) || status == 304 || status == 204 || body == null;
            long ts = System.currentTimeMillis();
            h.set(Signer.RESPONSE_HEADER, "v1 " + ts + " " + Signer.responseSig(k, nonce, status, ts, empty ? new byte[0] : plain));
        }
        boolean noBody = "HEAD".equals(ex.getRequestMethod()) || status == 304 || body == null || body.length == 0;
        if (noBody) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, body.length);
            OutputStream out = ex.getResponseBody();
            out.write(body);
            out.flush();
        }
    }

    static void json(HttpExchange ex, int status, JsonElement json) throws IOException {
        // Forced streamer mode: every "server" and "botId" in the response becomes its opaque id.
        if (Http.attr(ex, MASK) instanceof ServerIds ids) json = ids.mask(json);
        byte[] body = Json.GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
        sendJson(ex, status, body, body.length >= GZIP_MIN ? null : body, "no-store");
    }

    private static final int GZIP_MIN = 1024;

    /**
     * Sends JSON, gzip-compressed when the client accepts it. gzipped may be precomputed (a cached index),
     * or null to compress here; pass the plain body itself as gzipped to never compress.
     * Adds "Vary: Accept-Encoding". Returns true when the gzip variant was sent.
     */
    static boolean sendJson(HttpExchange ex, int status, byte[] body, byte[] gzipped, String cacheControl) throws IOException {
        ex.getResponseHeaders().set("Vary", "Accept-Encoding");
        if (gzipped != body && acceptsGzip(ex)) {
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
            Http.attr(ex, PLAIN_BODY, body);
            send(ex, status, JSON_TYPE, gzipped != null ? gzipped : gzip(body), cacheControl);
            return true;
        }
        send(ex, status, JSON_TYPE, body, cacheControl);
        return false;
    }

    static boolean acceptsGzip(HttpExchange ex) {
        String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (ae == null) return false;
        for (String part : ae.toLowerCase(java.util.Locale.ROOT).split(",")) {
            String[] bits = part.trim().split(";");
            if (!bits[0].trim().equals("gzip")) continue;
            for (int i = 1; i < bits.length; i++) {
                String q = bits[i].trim();
                if (q.startsWith("q=")) {
                    try {
                        return Double.parseDouble(q.substring(2)) > 0;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }

    static byte[] gzip(byte[] data) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(64, data.length / 6));
            try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out, 8192)) {
                gz.write(data);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static void error(HttpExchange ex, int status, String message) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        json(ex, status, o);
    }

    static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            try {
                String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                out.putIfAbsent(key, value);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    /** s cut to at most max chars, never splitting a surrogate pair; null stays null. */
    static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }
}
