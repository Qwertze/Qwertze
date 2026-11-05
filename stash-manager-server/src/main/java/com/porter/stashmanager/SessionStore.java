package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Website login sessions. The browser holds a random 32-byte token in the sm_session cookie; sessions.json stores
 * only SHA-256(token) with the created, last-seen and expiry times, so a stolen data folder doesn't hold usable
 * tokens and logins survive a restart. A session lasts 30 days from login.
 */
final class SessionStore {
    static final long LIFETIME_MS = 30L * 24 * 60 * 60 * 1000;
    static final String COOKIE = "sm_session";
    /** How long "sudo mode" lasts after the password was typed (console commands need it). */
    static final long ELEVATED_MS = 15L * 60 * 1000;
    /** lastSeen is written to disk at most this often per session (it only matters for display and pruning). */
    private static final long SEEN_WRITE_MS = 10L * 60 * 1000;
    private static final int MAX_SESSIONS = 200;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final class Session {
        final long created;
        final long expires;
        long lastSeen;
        long writtenSeen;

        Session(long created, long expires, long lastSeen) {
            this.created = created;
            this.expires = expires;
            this.lastSeen = lastSeen;
            this.writtenSeen = lastSeen;
        }
    }

    private final Path file;
    private final LongSupplier clock;
    private final Map<String, Session> sessions = new HashMap<>(); // hex SHA-256(token) -> session
    private final Map<String, Long> elevatedUntil = new HashMap<>(); // in memory only: a restart asks again

    SessionStore(Path dataDir) {
        this(dataDir, System::currentTimeMillis);
    }

    SessionStore(Path dataDir, LongSupplier clock) {
        this.file = dataDir == null ? null : dataDir.resolve("sessions.json");
        this.clock = clock;
    }

    synchronized void load() throws IOException {
        if (file == null || !Files.exists(file)) return;
        try {
            JsonArray list = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement e : list) {
                JsonObject o = e.getAsJsonObject();
                String hash = o.get("hash").getAsString();
                if (!hash.matches("[0-9a-f]{64}")) continue;
                sessions.put(hash, new Session(o.get("created").getAsLong(), o.get("expires").getAsLong(), o.get("lastSeen").getAsLong()));
            }
        } catch (RuntimeException e) {
            System.err.println("Skipping " + file.getFileName() + " (everyone has to log in again): " + e);
        }
        AtomicFiles.ownerOnly(file);
        if (prune()) save();
    }

    /** Starts a session and returns its token (for the cookie). */
    synchronized String create() {
        long now = clock.getAsLong();
        prune();
        if (sessions.size() >= MAX_SESSIONS) {
            // Drop the least recently used session to stay bounded.
            String oldest = null;
            long seen = Long.MAX_VALUE;
            for (Map.Entry<String, Session> e : sessions.entrySet()) {
                if (e.getValue().lastSeen < seen) {
                    seen = e.getValue().lastSeen;
                    oldest = e.getKey();
                }
            }
            sessions.remove(oldest);
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(hash(token), new Session(now, now + LIFETIME_MS, now));
        save();
        return token;
    }

    /** True when the token belongs to a live session (and notes that it was used). */
    synchronized boolean valid(String token) {
        if (token == null || token.isEmpty() || token.length() > 100) return false;
        String h = hash(token);
        Session s = sessions.get(h);
        if (s == null) return false;
        long now = clock.getAsLong();
        if (now >= s.expires) {
            sessions.remove(h);
            save();
            return false;
        }
        s.lastSeen = now;
        if (now - s.writtenSeen >= SEEN_WRITE_MS) save();
        return true;
    }

    /** Ends one session. */
    synchronized void remove(String token) {
        if (token == null) return;
        elevatedUntil.remove(hash(token));
        if (sessions.remove(hash(token)) != null) save();
    }

    /** The password was just typed in this session: dangerous actions are allowed for 15 minutes. */
    synchronized void elevate(String token) {
        if (token != null && sessions.containsKey(hash(token))) elevatedUntil.put(hash(token), clock.getAsLong() + ELEVATED_MS);
    }

    /** Ends sudo mode for this session at once ("Lock now"). */
    synchronized void lock(String token) {
        if (token != null) elevatedUntil.remove(hash(token));
    }

    /** When sudo mode ends for this session (epoch ms), or 0 when it isn't on. */
    synchronized long elevatedUntil(String token) {
        if (!elevated(token)) return 0;
        return elevatedUntil.get(hash(token));
    }

    synchronized boolean elevated(String token) {
        if (token == null) return false;
        Long until = elevatedUntil.get(hash(token));
        return until != null && until > clock.getAsLong() && sessions.containsKey(hash(token));
    }

    /** Ends every session except keepToken's (null ends them all). Returns how many ended. */
    synchronized int removeAllExcept(String keepToken) {
        String keep = keepToken == null ? null : hash(keepToken);
        int removed = 0;
        for (Iterator<String> it = sessions.keySet().iterator(); it.hasNext(); ) {
            if (!it.next().equals(keep)) {
                it.remove();
                removed++;
            }
        }
        elevatedUntil.keySet().removeIf(h -> !sessions.containsKey(h));
        if (removed > 0) save();
        return removed;
    }

    synchronized int size() {
        return sessions.size();
    }

    /** The token from a Cookie header ("a=b; sm_session=..."), or null. */
    static String tokenFrom(java.util.List<String> cookieHeaders) {
        if (cookieHeaders == null) return null;
        for (String header : cookieHeaders) {
            for (String part : header.split(";")) {
                String p = part.trim();
                if (p.startsWith(COOKIE + "=")) {
                    String v = p.substring(COOKIE.length() + 1).trim();
                    return v.isEmpty() ? null : v;
                }
            }
        }
        return null;
    }

    // No Secure flag: the server itself only speaks plain HTTP (an HTTPS reverse proxy forwards to it over http),
    // and a Secure cookie would never be sent back on plain-HTTP LAN access. If one is ever added, add it only when
    // the request is known to have arrived over https, never from a client-supplied header alone.
    static String cookie(String token) {
        return COOKIE + "=" + token + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=" + LIFETIME_MS / 1000;
    }

    static String clearCookie() {
        return COOKIE + "=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0";
    }

    private boolean prune() {
        long now = clock.getAsLong();
        return sessions.values().removeIf(s -> now >= s.expires);
    }

    private void save() {
        if (file == null) return;
        JsonArray list = new JsonArray();
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("hash", e.getKey());
            o.addProperty("created", e.getValue().created);
            o.addProperty("lastSeen", e.getValue().lastSeen);
            o.addProperty("expires", e.getValue().expires);
            list.add(o);
        }
        try {
            AtomicFiles.write(file, Json.GSON.toJson(list).getBytes(StandardCharsets.UTF_8), true);
            for (Session s : sessions.values()) s.writtenSeen = s.lastSeen;
        } catch (IOException ex) {
            System.err.println("Could not save " + file.getFileName() + ": " + ex.getMessage());
        }
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
