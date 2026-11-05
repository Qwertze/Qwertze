package com.porter.stashmanager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The single HttpServer context. Runs the access check on every request, then dispatches by method and path,
 * then applies the route's guard. New endpoints (orders later on) are added with {@link #exact} or {@link #prefix}.
 */
final class Router implements HttpHandler {
    /** Exchange attribute holding the API key generation that an ADDON_KEY request was made with (a Long). */
    static final String KEY_GENERATION = "stashmanager.keyGeneration";
    /** Exchange attribute holding the session token of a SESSION / BROWSER_WRITE request (a String). */
    static final String SESSION_TOKEN = "stashmanager.session";

    static final Set<String> GET = Set.of("GET", "HEAD");
    static final Set<String> POST = Set.of("POST");

    enum Guard {
        /** Only the access check (LAN address + Host allowlist): static files, auth state. */
        OPEN,
        /** A bot: Authorization: Bearer <apiKey>, otherwise 401. Never needs a website login. */
        ADDON_KEY,
        /** A logged-in browser (sm_session cookie), otherwise 401 {"error":"login required"}. Every browser GET. */
        SESSION,
        /** The CSRF checks in PROTOCOL.md without a session (login and setup), otherwise 403. */
        CSRF,
        /** A state-changing request from a logged-in browser: SESSION plus CSRF. */
        BROWSER_WRITE
    }

    interface Route {
        void handle(HttpExchange ex) throws IOException;
    }

    private record Entry(Set<String> methods, String path, boolean prefix, boolean fallback, Guard guard, Route route,
                         Pattern pattern) {
        boolean matches(String p) {
            if (pattern != null) return pattern.matcher(p).matches();
            return prefix ? p.startsWith(path) : p.equals(path);
        }
    }

    private final AccessControl access;
    private final Signer signer = new Signer();
    private final SessionStore sessions;
    private final Config config;
    private final List<Entry> entries = new ArrayList<>();

    Router(AccessControl access, SessionStore sessions, Config config) {
        this.access = access;
        this.sessions = sessions;
        this.config = config;
    }

    /** The session token when the request carries a live session (and a password is set), else null. */
    private String session(HttpExchange ex) {
        if (config.dashboardPassword() == null) return null;
        String token = SessionStore.tokenFrom(ex.getRequestHeaders().get("Cookie"));
        return sessions.valid(token) ? token : null;
    }

    Router exact(Set<String> methods, String path, Guard guard, Route route) {
        entries.add(new Entry(methods, path, false, false, guard, route, null));
        return this;
    }

    Router prefix(Set<String> methods, String path, Guard guard, Route route) {
        entries.add(new Entry(methods, path, true, false, guard, route, null));
        return this;
    }

    /** A path matching a regex over the raw path, e.g. /api/v1/orders/(o-[0-9a-f]{12})/cancel. */
    Router pattern(Set<String> methods, Pattern pattern, Guard guard, Route route) {
        entries.add(new Entry(methods, null, false, false, guard, route, pattern));
        return this;
    }

    /** A catch-all (the static files). Unlike other routes it never turns a wrong method into 405. */
    Router fallback(Set<String> methods, String path, Route route) {
        entries.add(new Entry(methods, path, true, true, Guard.OPEN, route, null));
        return this;
    }

    @Override
    public void handle(HttpExchange ex) {
        try {
            Http.securityHeaders(ex.getResponseHeaders());
            if (!access.isAllowed(ex)) {
                Http.error(ex, 403, "forbidden");
                return;
            }
            String path = ex.getRequestURI().getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            String method = ex.getRequestMethod();
            if ("OPTIONS".equals(method)) {
                // CORS preflights always fail: no Access-Control-* headers, ever.
                Http.error(ex, 405, "method not allowed");
                return;
            }

            Entry match = null;
            Set<String> allowed = new TreeSet<>();
            for (Entry e : entries) {
                if (!e.matches(path)) continue;
                if (e.methods.contains(method)) {
                    match = e;
                    break;
                }
                if (!e.fallback) allowed.addAll(e.methods);
            }
            if (match == null) {
                if (allowed.isEmpty()) {
                    Http.error(ex, 404, "not found");
                } else {
                    ex.getResponseHeaders().set("Allow", String.join(", ", allowed));
                    Http.error(ex, 405, "method not allowed");
                }
                return;
            }

            switch (match.guard) {
                case ADDON_KEY -> {
                    if (!botAuth(ex, path)) return;
                }
                case SESSION -> {
                    String token = session(ex);
                    if (token == null) {
                        Http.error(ex, 401, "login required");
                        return;
                    }
                    Http.attr(ex, SESSION_TOKEN, token);
                }
                case CSRF -> {
                    if (!access.browserWriteAllowed(ex)) {
                        Http.error(ex, 403, "browser write refused (needs POST, X-Stash-Manager: 1, JSON, same origin)");
                        return;
                    }
                }
                case BROWSER_WRITE -> {
                    // CSRF first: a cross-site request learns nothing about the login state.
                    if (!access.browserWriteAllowed(ex)) {
                        Http.error(ex, 403, "browser write refused (needs POST, X-Stash-Manager: 1, JSON, same origin)");
                        return;
                    }
                    String token = session(ex);
                    if (token == null) {
                        Http.error(ex, 401, "login required");
                        return;
                    }
                    Http.attr(ex, SESSION_TOKEN, token);
                }
                case OPEN -> {
                }
            }
            match.route.handle(ex);
        } catch (Http.TooLarge e) {
            safeError(ex, 413, "body too large (bot/* 1 MiB, others 8 MiB)");
        } catch (IOException e) {
            // Usually the client went away mid-request or mid-response. Nothing useful to send back.
        } catch (RuntimeException e) {
            System.err.println("Error handling " + ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath() + ": " + e);
            safeError(ex, 500, "internal error");
        } finally {
            Http.clearAttrs(ex);
            ex.close();
        }
    }

    /**
     * A bot request: X-SM-Auth (HMAC, PROTOCOL.md "Signed requests"); the response is then signed too. The old Bearer
     * header only with allowBearer=true and never on bot/*. False after answering 401.
     */
    private boolean botAuth(HttpExchange ex, String path) throws IOException {
        boolean dashboard = path.startsWith("/api/v1/bot/");
        String header = ex.getRequestHeaders().getFirst(Signer.REQUEST_HEADER);
        if (header != null) {
            // Format, timestamp and nonce syntax first, so unauthenticated junk never makes the server read a body.
            Signer.Auth pre = Signer.Auth.parse(header);
            if (!signer.precheck(pre)) {
                Http.error(ex, 401, "bad signature");
                return false;
            }
            byte[] body = Http.readBody(ex, dashboard ? Http.MAX_BOT_BODY : Http.MAX_BODY);
            Http.attr(ex, Http.BODY, body);
            String raw = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
            Config.KeyState current = config.keyState();
            Config.KeyState previous = dashboard ? null : config.previousKey();
            byte[] kCurrent = current.signingKey();
            byte[] kPrevious = previous == null ? null : previous.signingKey();
            Signer.Auth a = Signer.Auth.parse(header);
            int which = signer.verify(a, ex.getRequestMethod(), raw, body, kCurrent, kPrevious);
            if (which < 0) {
                Http.error(ex, 401, "bad signature");
                return false;
            }
            Http.attr(ex, KEY_GENERATION, which == 0 ? current.generation() : previous.generation());
            Http.attr(ex, Http.SIGN_KEY, which == 0 ? kCurrent : kPrevious);
            Http.attr(ex, Http.SIGN_NONCE, a.nonce());
            return true;
        }
        if (!dashboard && config.allowBearer) {
            long generation = access.keyGeneration(ex);
            if (generation >= 0) {
                Http.attr(ex, KEY_GENERATION, generation);
                return true;
            }
        }
        Http.error(ex, 401, ex.getRequestHeaders().containsKey("Authorization")
            ? (dashboard || !config.allowBearer ? "Bearer is not accepted: update the bot (requests must be signed)" : "missing or wrong API key")
            : "missing signature (X-SM-Auth)");
        return false;
    }

    private static void safeError(HttpExchange ex, int status, String message) {
        try {
            Http.error(ex, status, message);
        } catch (IOException | RuntimeException ignored) {
            // Headers were probably already sent.
        }
    }
}
