package com.porter.stashmanager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Signed bot requests and responses (PROTOCOL.md, "Signed requests (HMAC), replacing the Bearer key on the wire").
 * The API key never crosses the network: K = HMAC-SHA256(apiKey, "stash-manager v1") signs every request
 * (X-SM-Auth) and every response to a signed request (X-SM-Sig).
 */
final class Signer {
    static final String REQUEST_HEADER = "X-SM-Auth";
    static final String RESPONSE_HEADER = "X-SM-Sig";
    static final long MAX_SKEW_MS = 60_000;
    static final long NONCE_TTL_MS = 5 * 60_000;
    static final int MAX_NONCES = 200_000;
    private static final Pattern NONCE = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SIG = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern BOT = Pattern.compile("^(-|[A-Za-z0-9_-]{1,64})$");

    private final LongSupplier clock;
    /** nonce -> when it may be forgotten; insertion order = time order. Only nonces of valid signatures are kept. */
    private final LinkedHashMap<String, Long> nonces = new LinkedHashMap<>();

    Signer() {
        this(System::currentTimeMillis);
    }

    Signer(LongSupplier clock) {
        this.clock = clock;
    }

    long now() {
        return clock.getAsLong();
    }

    /**
     * The start-time floor (TASK-CONTRACT R2): a request whose ts is older than this is refused, so a request captured
     * before a restart cannot be replayed into the fresh nonce cache. 0 = no floor.
     */
    private volatile long notBefore;

    void notBefore(long ms) {
        this.notBefore = ms;
    }

    /** K = HMAC-SHA256(key = UTF-8(apiKey), msg = "stash-manager v1"). */
    static byte[] deriveKey(String apiKey) {
        return hmac(apiKey.getBytes(StandardCharsets.UTF_8), "stash-manager v1".getBytes(StandardCharsets.UTF_8));
    }

    static byte[] hmac(byte[] key, byte[] msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(msg);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data == null ? new byte[0] : data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** sig = hex(HMAC(K, METHOD \n PATH_WITH_QUERY \n ts \n nonce \n hex(SHA-256(body)))). */
    static String requestSig(byte[] k, String method, String pathWithQuery, long ts, String nonce, byte[] body) {
        String msg = method + "\n" + pathWithQuery + "\n" + ts + "\n" + nonce + "\n" + sha256Hex(body);
        return HexFormat.of().formatHex(hmac(k, msg.getBytes(StandardCharsets.UTF_8)));
    }

    /** sig = hex(HMAC(K, "resp" \n requestNonce \n status \n ts \n hex(SHA-256(responseBody)))). */
    static String responseSig(byte[] k, String requestNonce, int status, long ts, byte[] body) {
        String msg = "resp\n" + requestNonce + "\n" + status + "\n" + ts + "\n" + sha256Hex(body);
        return HexFormat.of().formatHex(hmac(k, msg.getBytes(StandardCharsets.UTF_8)));
    }

    /** A parsed X-SM-Auth header, or null when malformed. */
    record Auth(String botId, long ts, String nonce, String sig) {
        static Auth parse(String header) {
            if (header == null) return null;
            String[] p = header.trim().split(" ");
            if (p.length != 5 || !p[0].equals("v1") || !BOT.matcher(p[1]).matches() || !NONCE.matcher(p[3]).matches()
                || !SIG.matcher(p[4]).matches() || !p[2].matches("^\\d{1,15}$")) return null;
            return new Auth(p[1], Long.parseLong(p[2]), p[3], p[4]);
        }
    }

    /** Cheap checks before the body is read: a well-formed header with a fresh timestamp. */
    boolean precheck(Auth a) {
        return a != null && Math.abs(now() - a.ts()) <= MAX_SKEW_MS && a.ts() >= notBefore;
    }

    /**
     * Checks a signed request against the candidate keys (current first). Returns the index of the key that
     * verified, or -1 (bad time, bad signature, or a nonce seen in the last 5 minutes). A verified nonce is remembered.
     */
    int verify(Auth a, String method, String pathWithQuery, byte[] body, byte[]... keys) {
        long now = now();
        if (a == null || Math.abs(now - a.ts()) > MAX_SKEW_MS || a.ts() < notBefore) return -1;
        byte[] given = a.sig().getBytes(StandardCharsets.US_ASCII);
        int match = -1;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == null) continue;
            byte[] expected = requestSig(keys[i], method, pathWithQuery, a.ts(), a.nonce(), body).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(given, expected) && match < 0) match = i; // constant time per key
        }
        if (match < 0) return -1;
        synchronized (nonces) {
            for (Iterator<Map.Entry<String, Long>> it = nonces.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Long> e = it.next();
                if (e.getValue() > now && nonces.size() <= MAX_NONCES) break;
                it.remove();
            }
            if (nonces.containsKey(a.nonce())) return -1;
            nonces.put(a.nonce(), now + NONCE_TTL_MS);
        }
        return match;
    }
}
