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
    /** Exchange attribute holding who a SESSION / BROWSER_WRITE request is from (a Users.Principal). */
    static final String USER = "stashmanager.user";

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
        BROWSER_WRITE,
        /**
         * A raw file upload from a logged-in browser: the BROWSER_WRITE checks, except that Content-Type must be
         * application/octet-stream instead of JSON.
         */
        BROWSER_UPLOAD
    }

    /**
     * The permission a logged-in route needs before its handler runs. Handlers check the finer rules themselves
     * (own orders, stash and bot scope, proxy and console levels, Admin unlocked).
     */
    enum Perm {
        /** Any logged-in user, even one that must change the password first (only the auth routes). */
        ANY("any", "do that"),
        VIEW("view", "view stashes"),
        /** Item icons: the stash views, and the live viewer's hotbar. */
        ICONS("view", "see item icons"),
        ORDER("order", "place orders"),
        /** order, ordersAll or plannerOrder (cancel and retry: own orders, or any with ordersAll). */
        ORDER_OWN_OR_ALL("order", "change orders"),
        ORDERS_ALL("ordersAll", "change other users' orders"),
        /** A craft quote for an order: order or plannerOrder (CRAFT-CONTRACT.md). */
        CRAFT_QUOTE("order", "ask for a crafting quote"),
        /** Stash settings (the kit chests and rules). */
        SETTINGS("settings", "change stash settings"),
        ACTIVITY("activity", "view bots"),
        RESCAN("rescan", "rescan stashes"),
        LINK("link", "link bots"),
        /** The build planner: upload schematics and see their material list. */
        PLANNER("planner", "use the build planner"),
        /** Order the materials of a build plan (does not need ORDER). */
        PLANNER_ORDER("plannerOrder", "order from the build planner"),
        /** The live viewer: a bot's map, position, nearby entities, HUD and chat (implies ACTIVITY). */
        VIEWER("viewer", "use the live viewer"),
        /** The owner only: user management and the audit log. */
        OWNER("owner", "manage users (owner only)");

        final String key;
        final String what;

        Perm(String key, String what) {
            this.key = key;
            this.what = what;
        }

        boolean allows(Users.Principal p) {
            Users.Perms x = p.perms();
            return switch (this) {
                case ANY -> true;
                case VIEW -> x.view();
                case ICONS -> x.view() || x.viewer();
                case ORDER -> x.order();
                case ORDER_OWN_OR_ALL -> x.order() || x.ordersAll() || x.plannerOrder();
                case ORDERS_ALL -> x.ordersAll();
                case CRAFT_QUOTE -> x.order() || x.plannerOrder();
                case SETTINGS -> x.settings();
                case ACTIVITY -> x.activity();
                case RESCAN -> x.rescan();
                case LINK -> x.link();
                case PLANNER -> x.planner();
                case PLANNER_ORDER -> x.plannerOrder();
                case VIEWER -> x.viewer();
                case OWNER -> p.owner();
            };
        }
    }

    interface Route {
        void handle(HttpExchange ex) throws IOException;
    }

    private record Entry(Set<String> methods, String path, boolean prefix, boolean fallback, Guard guard, Perm perm,
                         Route route, Pattern pattern) {
        boolean matches(String p) {
            if (pattern != null) return pattern.matcher(p).matches();
            return prefix ? p.startsWith(path) : p.equals(path);
        }
    }

    private final AccessControl access;
    private final Signer signer = new Signer();
    private final SessionStore sessions;
    private final Config config;
    private final Users users;
    private final List<Entry> entries = new ArrayList<>();
    private ServerIds ids;

    /** The opaque ids for users in forced streamer mode. */
    Router ids(ServerIds ids) {
        this.ids = ids;
        return this;
    }

    ServerIds ids() {
        return ids;
    }

    /**
     * A server address from a request: as given, or for a user in forced streamer mode the address of a known opaque
     * id (a raw address is not accepted from them). Null when it isn't one: answer 404.
     */
    static String serverParam(HttpExchange ex, String value) {
        if (value == null) return null;
        return Http.attr(ex, Http.MASK) instanceof ServerIds ids ? ids.server(value) : value;
    }

    /** The same for a bot id. */
    static String botParam(HttpExchange ex, String value) {
        if (value == null) return null;
        return Http.attr(ex, Http.MASK) instanceof ServerIds ids ? ids.bot(value) : value;
    }

    Router(AccessControl access, SessionStore sessions, Config config, Users users) {
        this.access = access;
        this.sessions = sessions;
        this.config = config;
        this.users = users;
    }

    /** The access rules (the same-origin checks of a long-lived browser GET such as the viewer stream). */
    AccessControl access() {
        return access;
    }

    /**
     * Whether a session may still use something it was let in for: the session is live (its user not deleted or
     * disabled, its password unchanged), no password change is pending, the permission is still granted and, with a
     * botId, the bot is still in the user's scope. For long-lived responses (the viewer stream) that re-check while
     * they run, with the same resolve every request goes through.
     */
    /** Who a session token belongs to now (the same resolve as every request), or null. */
    Users.Principal current(String token) {
        return resolve(sessions, users, config, token);
    }

    boolean stillAllowed(String token, Perm perm, String botId) {
        Users.Principal p = resolve(sessions, users, config, token);
        if (p == null || (p.mustChange() && perm != Perm.ANY) || !perm.allows(p)) return false;
        return botId == null || p.perms().seesBot(botId);
    }

    /** Who made this logged-in request (set before every SESSION / BROWSER_WRITE handler runs). */
    static Users.Principal user(HttpExchange ex) {
        return (Users.Principal) Http.attr(ex, USER);
    }

    /** Answers 403 for a missing permission. */
    static void forbid(HttpExchange ex, String what, String key) throws IOException {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", "you don't have permission to " + what);
        o.addProperty("permission", key);
        Http.json(ex, 403, o);
    }

    private record LoggedIn(String token, Users.Principal user) {
    }

    /**
     * The session token and its user when the request carries a live session (and a password is set), else null.
     * A session whose user was deleted or disabled is ended here.
     */
    private LoggedIn session(HttpExchange ex) {
        String token = SessionStore.tokenFrom(ex.getRequestHeaders().get("Cookie"));
        Users.Principal p = resolve(sessions, users, config, token);
        return p == null ? null : new LoggedIn(token, p);
    }

    /**
     * Who a session token belongs to now, or null. Ends the session when its user is gone or disabled, or when it
     * was made with a password that has changed since. Sessions from before sub-users (no tag) are the owner's only.
     */
    static Users.Principal resolve(SessionStore sessions, Users users, Config config, String token) {
        if (config.dashboardPassword() == null) return null;
        String userId = sessions.userOf(token);
        if (userId == null) return null;
        Users.Principal p = users.principal(userId);
        String cred = sessions.credOf(token);
        boolean credOk = cred == null ? p != null && p.owner() : cred.equals(users.credTag(userId, config.dashboardPassword()));
        if (p == null || !credOk) {
            sessions.remove(token);
            return null;
        }
        return p;
    }

    private static void checkPerm(Guard guard, Perm perm) {
        boolean loggedIn = guard == Guard.SESSION || guard == Guard.BROWSER_WRITE || guard == Guard.BROWSER_UPLOAD;
        if (loggedIn != (perm != null)) {
            throw new IllegalArgumentException("logged-in routes need a permission, other routes none: " + guard + " " + perm);
        }
    }

    Router exact(Set<String> methods, String path, Guard guard, Route route) {
        return exact(methods, path, guard, null, route);
    }

    Router exact(Set<String> methods, String path, Guard guard, Perm perm, Route route) {
        checkPerm(guard, perm);
        entries.add(new Entry(methods, path, false, false, guard, perm, route, null));
        return this;
    }

    Router prefix(Set<String> methods, String path, Guard guard, Perm perm, Route route) {
        checkPerm(guard, perm);
        entries.add(new Entry(methods, path, true, false, guard, perm, route, null));
        return this;
    }

    /** A path matching a regex over the raw path, e.g. /api/v1/orders/(o-[0-9a-f]{12})/cancel. */
    Router pattern(Set<String> methods, Pattern pattern, Guard guard, Route route) {
        return pattern(methods, pattern, guard, null, route);
    }

    Router pattern(Set<String> methods, Pattern pattern, Guard guard, Perm perm, Route route) {
        checkPerm(guard, perm);
        entries.add(new Entry(methods, null, false, false, guard, perm, route, pattern));
        return this;
    }

    /** A catch-all (the static files). Unlike other routes it never turns a wrong method into 405. */
    Router fallback(Set<String> methods, String path, Route route) {
        entries.add(new Entry(methods, path, true, true, Guard.OPEN, null, route, null));
        return this;
    }

    /** Every registered route as "[METHODS] path-or-pattern GUARD [PERM]" (for tests). */
    List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) {
            out.add(new TreeSet<>(e.methods) + " " + (e.pattern != null ? e.pattern.pattern() : e.path) + " " + e.guard
                + (e.perm == null ? "" : " " + e.perm));
        }
        return out;
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
                    if (!loggedIn(ex, match.perm)) return;
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
                    if (!loggedIn(ex, match.perm)) return;
                }
                case BROWSER_UPLOAD -> {
                    if (!access.browserWriteAllowed(ex, AccessControl.OCTET_STREAM)) {
                        Http.error(ex, 403, "upload refused (needs POST, X-Stash-Manager: 1, application/octet-stream, same origin)");
                        return;
                    }
                    if (!loggedIn(ex, match.perm)) return;
                }
                case OPEN -> {
                }
            }
            match.route.handle(ex);
        } catch (Http.TooLarge e) {
            safeError(ex, 413, "body too large (bot/* 1 MiB, others 8 MiB)");
        } catch (CraftService.BusyException e) {
            try {
                ex.getResponseHeaders().set("Retry-After", Long.toString(e.retryAfter));
                com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                o.addProperty("ok", false);
                o.addProperty("error", e.getMessage());
                o.addProperty("retryAfter", e.retryAfter);
                Http.json(ex, e.status, o);
            } catch (IOException | RuntimeException ignored) {
                // headers already sent
            }
        } catch (IOException e) {
            // Usually the client went away mid-request or mid-response. Nothing useful to send back.
        } catch (RuntimeException e) {
            System.err.println("Error handling " + ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath() + ": " + e);
            safeError(ex, 500, "internal error");
        } finally {
            boolean detached = Http.attr(ex, Http.DETACHED) != null; // then another thread closes it
            Http.clearAttrs(ex);
            if (!detached) ex.close();
        }
    }

    /**
     * The session check of a logged-in route: who it is, the must-change-password gate, and the route's permission.
     * False after answering 401 or 403.
     */
    private boolean loggedIn(HttpExchange ex, Perm perm) throws IOException {
        LoggedIn s = session(ex);
        if (s == null) {
            Http.error(ex, 401, "login required");
            return false;
        }
        Http.attr(ex, SESSION_TOKEN, s.token());
        Http.attr(ex, USER, s.user());
        if (s.user().mustChange() && perm != Perm.ANY) {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("ok", false);
            o.addProperty("error", "change your password first");
            o.addProperty("mustChange", true);
            Http.json(ex, 403, o);
            return false;
        }
        if (Redact.forced(s.user()) && ids != null) Http.attr(ex, Http.MASK, ids); // before any answer
        if (!perm.allows(s.user())) {
            forbid(ex, perm.what, perm.key);
            return false;
        }
        return true;
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
