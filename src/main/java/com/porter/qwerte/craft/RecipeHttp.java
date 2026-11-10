package com.porter.qwerte.craft;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * The one signed GET the bot makes: {@code GET /api/v1/bot/recipes?mc=} (PROTOCOL.md "Signed requests (HMAC)"). The API key itself is never
 * sent: the request carries {@code X-SM-Auth: v1 - <ts> <nonce> <sig>} (the addon has no bot id, as with its other requests), and the answer
 * is only believed if its {@code X-SM-Sig} proves the server knows the key and answers this very request (nonce, status, body, time). A
 * refusal before the key was checked (401, 403) or a server error (5xx) may come unsigned; the code is shown and nothing in it is acted on.
 * The body is read up to {@link RecipeStore#MAX_BYTES} (after un-gzipping) and no further. No redirects are followed. Blocking: not for the
 * game thread. The same signing as {@code StashManager}'s POSTs, so the two must keep agreeing (the test signs with a server's view of it).
 */
public final class RecipeHttp {
    private RecipeHttp() {
    }

    /** The code of an answer whose signature did not verify (or was missing). */
    public static final int BAD_SIGNATURE = -2;
    private static final long MAX_SKEW_MS = 60_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ScheduledThreadPoolExecutor WATCHDOG = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "qwerte-recipes-watchdog");
        t.setDaemon(true);
        return t;
    });

    static {
        WATCHDOG.setKeepAliveTime(10, TimeUnit.SECONDS);
        WATCHDOG.allowCoreThreadTimeOut(true);
        WATCHDOG.setRemoveOnCancelPolicy(true);
    }

    /** @param code the status, -1 when the server could not be reached (or the answer was too big), {@link #BAD_SIGNATURE} for an unsigned answer; body and etag are null/empty then */
    public record Response(int code, String body, String etag) {
    }

    /** K = HMAC-SHA256(key = the API key, message = "stash-manager v1"). */
    static byte[] signingKey(String apiKey) {
        return hmac(apiKey.getBytes(StandardCharsets.UTF_8), "stash-manager v1".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The value of the X-SM-Auth header for a request with no body. */
    static String authHeader(byte[] signingKey, String method, String pathWithQuery, long ts, String nonce) {
        String input = method + "\n" + pathWithQuery + "\n" + ts + "\n" + nonce + "\n" + sha256Hex(new byte[0]);
        return "v1 - " + ts + " " + nonce + " " + HexFormat.of().formatHex(hmac(signingKey, input.getBytes(StandardCharsets.UTF_8)));
    }

    /** The X-SM-Sig a server sends for an answer (body: the uncompressed bytes). For tests. */
    static String responseHeader(byte[] signingKey, String nonce, int status, long ts, byte[] body) {
        String input = "resp\n" + nonce + "\n" + status + "\n" + ts + "\n" + sha256Hex(body);
        return "v1 " + ts + " " + HexFormat.of().formatHex(hmac(signingKey, input.getBytes(StandardCharsets.UTF_8)));
    }

    /** Whether an X-SM-Sig header proves this answer to this nonce (constant-time comparison, within 60 s). */
    static boolean verify(byte[] signingKey, String header, String nonce, int status, byte[] body, long now) {
        if (header == null) return false;
        String[] parts = header.trim().split(" +");
        if (parts.length != 3 || !parts[0].equals("v1")) return false;
        long ts;
        try {
            ts = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(now - ts) > MAX_SKEW_MS) return false;
        String input = "resp\n" + nonce + "\n" + status + "\n" + ts + "\n" + sha256Hex(body);
        byte[] expected = HexFormat.of().formatHex(hmac(signingKey, input.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, parts[2].toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * GET {@code uri} signed with the key. Never throws: a failure is code -1.
     * @param ifNoneMatch sent as If-None-Match when not null (a 304 answer has no body)
     */
    public static Response get(HttpClient http, URI uri, String apiKey, String ifNoneMatch, Duration timeout) {
        try {
            byte[] k = signingKey(apiKey);
            byte[] nonceBytes = new byte[16];
            RANDOM.nextBytes(nonceBytes);
            String nonce = HexFormat.of().formatHex(nonceBytes);
            long ts = System.currentTimeMillis();
            String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(timeout).header("X-SM-Auth", authHeader(k, "GET", path, ts, nonce)).GET();
            if (ifNoneMatch != null && !ifNoneMatch.isEmpty()) rb.header("If-None-Match", ifNoneMatch);
            long started = System.nanoTime();
            HttpResponse<InputStream> response = http.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
            int code = response.statusCode();
            byte[] bytes;
            // One deadline for the whole exchange: a body that trickles in is cut (its stream closed) when the time is up, so the thread is freed
            long left = Math.max(1, timeout.toMillis() - (System.nanoTime() - started) / 1_000_000);
            InputStream body = response.body();
            ScheduledFuture<?> cut = WATCHDOG.schedule(() -> {
                try {
                    body.close();
                } catch (IOException ignored) {
                    // the read fails anyway
                }
            }, left, TimeUnit.MILLISECONDS);
            try (InputStream raw = body) {
                boolean gzip = response.headers().firstValue("Content-Encoding").map(v -> v.toLowerCase(java.util.Locale.ROOT).contains("gzip")).orElse(false);
                try (InputStream in = gzip && code != 304 ? new GZIPInputStream(raw) : raw) {
                    bytes = in.readNBytes((int) RecipeStore.MAX_BYTES + 1);
                }
            } finally {
                cut.cancel(false);
            }
            if (bytes.length > RecipeStore.MAX_BYTES) return new Response(-1, "", null);
            boolean unsignedOk = code == 401 || code == 403 || code >= 500;
            if (!unsignedOk && !verify(k, response.headers().firstValue("X-SM-Sig").orElse(null), nonce, code, bytes, System.currentTimeMillis())) {
                return new Response(BAD_SIGNATURE, "", null);
            }
            return new Response(code, new String(bytes, StandardCharsets.UTF_8), response.headers().firstValue("ETag").orElse(null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(-1, "", null);
        } catch (IOException | RuntimeException e) {
            return new Response(-1, "", null);
        }
    }
}
