package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * The website login (PROTOCOL.md, "Website login"): state, first-run setup (from the server PC only, with the one-time
 * code printed in the server window), login, logout, log out everywhere, changing the password, and confirming the
 * password for dangerous actions ("sudo mode", 15 minutes).
 */
final class AuthRoutes {
    static final String SETUP_ELSEWHERE = "Set the dashboard password on the server PC (open http://localhost:%d)";
    /** Auth bodies are tiny: a password or two. */
    static final int MAX_BODY = 4096;

    private final Config config;
    private final SessionStore sessions;
    private final LoginLimiter limiter;
    private volatile String setupCode;

    AuthRoutes(Config config, SessionStore sessions, LoginLimiter limiter) {
        this.config = config;
        this.sessions = sessions;
        this.limiter = limiter;
        if (config.dashboardPassword() == null) setupCode = Auth.newSetupCode();
    }

    /** The one-time setup code while no password is set (printed at startup), else null. */
    String setupCode() {
        return setupCode;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/auth/state", Router.Guard.OPEN, this::state)
            .exact(Router.POST, "/api/v1/auth/setup", Router.Guard.CSRF, this::setup)
            .exact(Router.POST, "/api/v1/auth/login", Router.Guard.CSRF, this::login)
            .exact(Router.POST, "/api/v1/auth/logout", Router.Guard.BROWSER_WRITE, this::logout)
            .exact(Router.POST, "/api/v1/auth/logout-all", Router.Guard.BROWSER_WRITE, this::logoutAll)
            .exact(Router.POST, "/api/v1/auth/password", Router.Guard.BROWSER_WRITE, this::password)
            .exact(Router.POST, "/api/v1/auth/confirm", Router.Guard.BROWSER_WRITE, this::confirm)
            .exact(Router.POST, "/api/v1/auth/lock", Router.Guard.BROWSER_WRITE, this::lock);
    }

    static boolean fromLoopback(HttpExchange ex) {
        InetSocketAddress remote = ex.getRemoteAddress();
        InetAddress a = remote == null ? null : remote.getAddress();
        return a != null && a.isLoopbackAddress();
    }

    /** A request that went through a proxy on the server PC is not "from the server PC". */
    static boolean proxied(Headers h) {
        for (String name : new String[]{"X-Forwarded-For", "Forwarded", "Via", "X-Real-IP"}) {
            if (h.containsKey(name)) return true;
        }
        return false;
    }

    private static InetAddress remote(HttpExchange ex) {
        InetSocketAddress remote = ex.getRemoteAddress();
        return remote == null ? null : remote.getAddress();
    }

    private static String address(HttpExchange ex) {
        InetAddress a = remote(ex);
        return a == null ? "?" : a.getHostAddress();
    }

    private void state(HttpExchange ex) throws IOException {
        boolean setupNeeded = config.dashboardPassword() == null;
        boolean local = fromLoopback(ex) && !proxied(ex.getRequestHeaders());
        JsonObject o = new JsonObject();
        o.addProperty("setupNeeded", setupNeeded);
        o.addProperty("setupAllowed", setupNeeded && local);
        o.addProperty("codeRequired", setupNeeded);
        String token = SessionStore.tokenFrom(ex.getRequestHeaders().get("Cookie"));
        boolean loggedIn = !setupNeeded && sessions.valid(token);
        o.addProperty("loggedIn", loggedIn);
        o.addProperty("elevated", loggedIn && sessions.elevated(token));
        o.addProperty("elevatedUntil", loggedIn ? sessions.elevatedUntil(token) : 0);
        o.addProperty("minLength", Auth.MIN_LENGTH);
        if (setupNeeded && !local) o.addProperty("message", SETUP_ELSEWHERE.formatted(config.port));
        Http.json(ex, 200, o);
    }

    /** The JSON body (at most 4 KiB), or null after sending the error. */
    private static JsonObject body(HttpExchange ex) throws IOException {
        byte[] bytes;
        try {
            bytes = Http.readBody(ex, MAX_BODY);
        } catch (Http.TooLarge e) {
            Http.error(ex, 413, "body too large");
            return null;
        }
        try {
            JsonElement json = JsonParser.parseString(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString());
            if (json.isJsonObject()) return json.getAsJsonObject();
        } catch (java.nio.charset.CharacterCodingException | JsonParseException | IllegalStateException e) {
            // falls through to 400
        }
        Http.error(ex, 400, "body must be a JSON object");
        return null;
    }

    /** Saves a new password hash; false after answering 500 when the config file can't be written. */
    private boolean save(HttpExchange ex, String password) throws IOException {
        try {
            config.setDashboardPassword(Auth.hash(password));
            return true;
        } catch (IOException e) {
            System.err.println("Could not save the dashboard password to config.properties: " + e.getMessage());
            Http.error(ex, 500, "could not save");
            return false;
        }
    }

    private void setup(HttpExchange ex) throws IOException {
        if (!fromLoopback(ex) || proxied(ex.getRequestHeaders())) {
            Http.error(ex, 403, SETUP_ELSEWHERE.formatted(config.port));
            return;
        }
        JsonObject o = body(ex);
        if (o == null) return;
        String password = AddonRoutes.string(o, "password");
        String problem = Auth.checkNew(password);
        if (problem != null) {
            Http.error(ex, 400, problem);
            return;
        }
        synchronized (config) {
            if (config.dashboardPassword() != null) {
                Http.error(ex, 409, "the password is already set");
                return;
            }
            if (!Auth.codeMatches(AddonRoutes.string(o, "code"), setupCode)) {
                Http.error(ex, 403, "wrong setup code (it is shown in the server window)");
                return;
            }
            if (!save(ex, password)) return;
            setupCode = null;
        }
        System.out.println("Dashboard password set from " + address(ex) + ".");
        startSession(ex);
    }

    private void login(HttpExchange ex) throws IOException {
        String stored = config.dashboardPassword();
        if (stored == null) {
            Http.error(ex, 409, "no password set yet");
            return;
        }
        JsonObject o = body(ex);
        if (o == null) return;
        String password = AddonRoutes.string(o, "password");
        if (password == null || password.length() > Auth.MAX_LENGTH) {
            Http.error(ex, 400, "password must be a string");
            return;
        }
        if (!checkPassword(ex, password, stored)) return;
        startSession(ex);
    }

    /** Rate limit + verify. False after answering 429 or 401. */
    private boolean checkPassword(HttpExchange ex, String password, String stored) throws IOException {
        String key = LoginLimiter.key(remote(ex));
        boolean loopback = fromLoopback(ex) && !proxied(ex.getRequestHeaders());
        long wait = limiter.tryAcquire(key, loopback);
        if (wait > 0) {
            tooMany(ex, wait);
            return false;
        }
        if (!Auth.verify(password, stored)) {
            limiter.failure(key, loopback);
            Http.error(ex, 401, "wrong password");
            return false;
        }
        limiter.success(key);
        return true;
    }

    private void startSession(HttpExchange ex) throws IOException {
        String token = sessions.create();
        sessions.elevate(token); // the password was just typed
        ex.getResponseHeaders().add("Set-Cookie", SessionStore.cookie(token));
        Http.json(ex, 200, Http.ok());
    }

    static void tooMany(HttpExchange ex, long waitMs) throws IOException {
        long seconds = Math.max(1, (waitMs + 999) / 1000);
        ex.getResponseHeaders().set("Retry-After", String.valueOf(seconds));
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", "too many attempts, try again in " + seconds + " s");
        o.addProperty("retryAfter", seconds);
        Http.json(ex, 429, o);
    }

    private void logout(HttpExchange ex) throws IOException {
        sessions.remove((String) Http.attr(ex, Router.SESSION_TOKEN));
        ex.getResponseHeaders().add("Set-Cookie", SessionStore.clearCookie());
        Http.json(ex, 200, Http.ok());
    }

    private void logoutAll(HttpExchange ex) throws IOException {
        int n = sessions.removeAllExcept(null);
        System.out.println("Logged out every website session (" + n + ") from " + address(ex) + ".");
        ex.getResponseHeaders().add("Set-Cookie", SessionStore.clearCookie());
        JsonObject out = Http.ok();
        out.addProperty("ended", n);
        Http.json(ex, 200, out);
    }

    /** {current, next}: needs the current password; every other session is logged out. */
    private void password(HttpExchange ex) throws IOException {
        JsonObject o = body(ex);
        if (o == null) return;
        String current = AddonRoutes.string(o, "current");
        String next = AddonRoutes.string(o, "next");
        if (current == null || current.length() > Auth.MAX_LENGTH) {
            Http.error(ex, 400, "current must be a string");
            return;
        }
        String problem = Auth.checkNew(next);
        if (problem != null) {
            Http.error(ex, 400, problem);
            return;
        }
        String token = (String) Http.attr(ex, Router.SESSION_TOKEN);
        synchronized (config) {
            if (!checkPassword(ex, current, config.dashboardPassword())) return;
            if (!save(ex, next)) return;
        }
        int ended = sessions.removeAllExcept(token);
        sessions.elevate(token);
        System.out.println("Dashboard password changed from " + address(ex) + "; " + ended + " other session(s) logged out.");
        JsonObject out = Http.ok();
        out.addProperty("ended", ended);
        Http.json(ex, 200, out);
    }

    /** {password}: "sudo mode" for 15 minutes in this session (console commands need it). */
    private void confirm(HttpExchange ex) throws IOException {
        JsonObject o = body(ex);
        if (o == null) return;
        String password = AddonRoutes.string(o, "password");
        if (password == null || password.length() > Auth.MAX_LENGTH) {
            Http.error(ex, 400, "password must be a string");
            return;
        }
        if (!checkPassword(ex, password, config.dashboardPassword())) return;
        sessions.elevate((String) Http.attr(ex, Router.SESSION_TOKEN));
        JsonObject out = Http.ok();
        out.addProperty("elevatedForSeconds", SessionStore.ELEVATED_MS / 1000);
        out.addProperty("elevatedUntil", sessions.elevatedUntil((String) Http.attr(ex, Router.SESSION_TOKEN)));
        Http.json(ex, 200, out);
    }

    /** Ends sudo mode for this session now. */
    private void lock(HttpExchange ex) throws IOException {
        sessions.lock((String) Http.attr(ex, Router.SESSION_TOKEN));
        Http.json(ex, 200, Http.ok());
    }
}
