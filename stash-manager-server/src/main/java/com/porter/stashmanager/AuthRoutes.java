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
 * code printed in the server window), login (the owner, or a sub-user by name), logout, log out everywhere, changing
 * your own password, and confirming it for dangerous actions ("Admin unlocked", 15 minutes).
 */
final class AuthRoutes {
    static final String SETUP_ELSEWHERE = "Set the dashboard password on the server PC (open http://localhost:%d)";
    static final String WRONG = "wrong username or password";
    /** Auth bodies are tiny: a name and a password or two. */
    static final int MAX_BODY = 4096;

    private final Config config;
    private final SessionStore sessions;
    private final LoginLimiter limiter;
    private final Users users;
    private final AuditLog audit;
    /** Run after logout and logout-all (the build planner drops the plans of users with no session left). */
    private volatile Runnable sessionsEnded = () -> { };
    private volatile String setupCode;

    AuthRoutes(Config config, SessionStore sessions, LoginLimiter limiter, Users users, AuditLog audit) {
        this.config = config;
        this.sessions = sessions;
        this.limiter = limiter;
        this.users = users;
        this.audit = audit;
        if (config.dashboardPassword() == null) setupCode = Auth.newSetupCode();
    }

    /** The one-time setup code while no password is set (printed at startup), else null. */
    String setupCode() {
        return setupCode;
    }

    void onSessionsEnded(Runnable r) {
        sessionsEnded = r;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/auth/state", Router.Guard.OPEN, this::state)
            .exact(Router.POST, "/api/v1/auth/setup", Router.Guard.CSRF, this::setup)
            .exact(Router.POST, "/api/v1/auth/login", Router.Guard.CSRF, this::login)
            .exact(Router.POST, "/api/v1/auth/logout", Router.Guard.BROWSER_WRITE, Router.Perm.ANY, this::logout)
            .exact(Router.POST, "/api/v1/auth/logout-all", Router.Guard.BROWSER_WRITE, Router.Perm.ANY, this::logoutAll)
            .exact(Router.POST, "/api/v1/auth/password", Router.Guard.BROWSER_WRITE, Router.Perm.ANY, this::password)
            .exact(Router.POST, "/api/v1/auth/confirm", Router.Guard.BROWSER_WRITE, Router.Perm.ANY, this::confirm)
            .exact(Router.POST, "/api/v1/auth/lock", Router.Guard.BROWSER_WRITE, Router.Perm.ANY, this::lock);
    }

    /**
     * "From the server PC itself": a loopback socket, and a Host header naming this machine (localhost, 127.x or
     * [::1]), so a local reverse proxy that forwards without proxy headers still isn't mistaken for the owner's PC.
     */
    static boolean fromLoopback(HttpExchange ex) {
        InetSocketAddress remote = ex.getRemoteAddress();
        InetAddress a = remote == null ? null : remote.getAddress();
        if (a == null || !a.isLoopbackAddress()) return false;
        // A configured proxy on this machine forwards other people's requests, even without proxy headers.
        if (trusted.contains(a.getHostAddress())) return false;
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null) return false;
        host = host.trim().toLowerCase(java.util.Locale.ROOT);
        return host.matches("(localhost|127(\\.\\d{1,3}){3})(:\\d{1,5})?") || host.matches("\\[::1\\](:\\d{1,5})?");
    }

    /** A request that went through a proxy on the server PC is not "from the server PC". */
    static boolean proxied(Headers h) {
        for (String name : new String[]{"X-Forwarded-For", "Forwarded", "Via", "X-Real-IP", "CF-Connecting-IP"}) {
            if (h.containsKey(name)) return true;
        }
        return false;
    }

    private static volatile java.util.List<String> trusted = java.util.List.of();

    /** Set once at startup from config.properties trustedProxies= (normalised, so ::1 matches 0:0:0:0:0:0:0:1). */
    static void trustProxies(java.util.List<String> addresses) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String a : addresses) {
            InetAddress parsed = literal(a);
            if (parsed != null) out.add(parsed.getHostAddress());
        }
        trusted = java.util.List.copyOf(out);
    }

    private static final java.util.regex.Pattern IPV4 = java.util.regex.Pattern.compile("(\\d{1,3}\\.){3}\\d{1,3}");
    private static final java.util.regex.Pattern IPV6 = java.util.regex.Pattern.compile("[0-9a-fA-F]{0,4}(:[0-9a-fA-F]{0,4}){2,7}");

    /** A plain IPv4 or IPv6 address literal (never a name, so parsing it never asks DNS). */
    static boolean isIpLiteral(String s) {
        if (s == null) return false;
        if (IPV4.matcher(s).matches()) {
            for (String part : s.split("\\.")) if (Integer.parseInt(part) > 255) return false;
            return true;
        }
        return IPV6.matcher(s).matches();
    }

    private static InetAddress literal(String s) {
        if (!isIpLiteral(s)) return null;
        try {
            return InetAddress.getByName(s); // checked to be a literal above: no DNS lookup
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    /**
     * The client's address: the socket's, or, only when the socket is a trusted proxy, the last X-Forwarded-For
     * entry (the one that proxy added; Cloudflare and cloudflared put the real client there, after anything the
     * client sent itself). Used for the rate limit and the audit log only.
     */
    static InetAddress remote(HttpExchange ex) {
        InetSocketAddress remote = ex.getRemoteAddress();
        InetAddress socket = remote == null ? null : remote.getAddress();
        if (socket == null || trusted.isEmpty() || !trusted.contains(socket.getHostAddress())) return socket;
        java.util.List<String> xffs = ex.getRequestHeaders().get("X-Forwarded-For");
        if (xffs == null || xffs.isEmpty()) return socket;
        String[] parts = xffs.get(xffs.size() - 1).split(",");
        InetAddress claimed = literal(parts[parts.length - 1].trim());
        return claimed == null ? socket : claimed;
    }

    static String address(HttpExchange ex) {
        InetAddress a = remote(ex);
        return a == null ? "?" : a.getHostAddress();
    }

    private volatile ServerIds ids;

    /** The opaque ids, so a forced user's own scope lists in auth/state never carry raw addresses or bot ids. */
    void ids(ServerIds ids) {
        this.ids = ids;
    }

    /**
     * The user's own JSON for auth/state. For a user in forced streamer mode the stash scope's server addresses
     * and the bot scope's ids are replaced by their opaque ids (the same ones every other response uses).
     */
    private JsonObject userJson(Users.Principal who) {
        JsonObject u = who.toJson();
        if (!Redact.forced(who)) return u;
        ServerIds idMap = ids;
        JsonObject perms = u.getAsJsonObject("perms");
        if (perms == null) return u;
        JsonElement st = perms.get("stashes");
        if (st != null && st.isJsonArray()) {
            for (JsonElement e : st.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject so = e.getAsJsonObject();
                String server = AddonRoutes.string(so, "server");
                so.addProperty("server", server == null || idMap == null ? "hidden" : idMap.sid(server));
            }
        }
        JsonElement bt = perms.get("bots");
        if (bt != null && bt.isJsonArray()) {
            com.google.gson.JsonArray out = new com.google.gson.JsonArray();
            for (JsonElement e : bt.getAsJsonArray()) {
                String id = e.isJsonPrimitive() ? e.getAsString() : null;
                out.add(id == null || idMap == null ? "hidden" : idMap.bid(id));
            }
            perms.add("bots", out);
        }
        return u;
    }

    private void state(HttpExchange ex) throws IOException {
        boolean setupNeeded = config.dashboardPassword() == null;
        boolean local = fromLoopback(ex) && !proxied(ex.getRequestHeaders());
        JsonObject o = new JsonObject();
        o.addProperty("setupNeeded", setupNeeded);
        o.addProperty("setupAllowed", setupNeeded && local);
        o.addProperty("codeRequired", setupNeeded);
        String token = SessionStore.tokenFrom(ex.getRequestHeaders().get("Cookie"));
        Users.Principal who = setupNeeded ? null : Router.resolve(sessions, users, config, token);
        boolean loggedIn = who != null;
        o.addProperty("loggedIn", loggedIn);
        o.addProperty("elevated", loggedIn && sessions.elevated(token));
        o.addProperty("elevatedUntil", loggedIn ? sessions.elevatedUntil(token) : 0);
        o.addProperty("minLength", Auth.MIN_LENGTH);
        if (loggedIn) o.add("user", userJson(who));
        if (setupNeeded && !local) o.addProperty("message", SETUP_ELSEWHERE.formatted(config.port));
        Http.json(ex, 200, o);
    }

    /** The JSON body (at most 4 KiB), or null after sending the error. */
    static JsonObject body(HttpExchange ex) throws IOException {
        return body(ex, MAX_BODY);
    }

    /** The JSON body (at most max bytes), or null after sending the error. */
    static JsonObject body(HttpExchange ex, int max) throws IOException {
        byte[] bytes;
        try {
            bytes = Http.readBody(ex, max);
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

    /** Saves a new owner password hash and returns it; null after answering 500 when the config file can't be written. */
    private String saveOwner(HttpExchange ex, String password) throws IOException {
        try {
            String h = Auth.hash(password);
            config.setDashboardPassword(h);
            return h;
        } catch (IOException e) {
            System.err.println("Could not save the dashboard password to config.properties: " + e.getMessage());
            Http.error(ex, 500, "could not save");
            return null;
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
        String stored;
        synchronized (config) {
            if (config.dashboardPassword() != null) {
                Http.error(ex, 409, "the password is already set");
                return;
            }
            if (!Auth.codeMatches(AddonRoutes.string(o, "code"), setupCode)) {
                Http.error(ex, 403, "wrong setup code (it is shown in the server window)");
                return;
            }
            String h = saveOwner(ex, password);
            if (h == null) return;
            setupCode = null;
            stored = h;
        }
        System.out.println("Dashboard password set from " + address(ex) + ".");
        audit.add(Users.OWNER_ID, users.ownerName(), "setup", null, address(ex));
        startSession(ex, Users.OWNER_ID, false, stored);
    }

    private void login(HttpExchange ex) throws IOException {
        String ownerHash = config.dashboardPassword();
        if (ownerHash == null) {
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
        JsonElement ue = o.get("username");
        if (ue != null && !ue.isJsonNull() && AddonRoutes.string(o, "username") == null) {
            Http.error(ex, 400, "username must be a string");
            return;
        }
        String username = ue == null || ue.isJsonNull() ? null : AddonRoutes.string(o, "username").trim();
        if (username != null && username.length() > Users.MAX_NAME * 4) username = username.substring(0, Users.MAX_NAME * 4);

        // Failures count per address and account, so a success with one account doesn't wipe the failures run up
        // guessing another's from the same address.
        String key = LoginLimiter.key(remote(ex)) + "|" + (users.isOwnerName(username) ? Users.OWNER_ID : String.valueOf(username).toLowerCase(java.util.Locale.ROOT));
        boolean loopback = fromLoopback(ex) && !proxied(ex.getRequestHeaders());
        long wait = Math.max(limiter.tryAcquire(key, loopback), limiter.tryAcquire(LoginLimiter.key(remote(ex)), loopback));
        if (wait > 0) {
            tooMany(ex, wait);
            return;
        }
        String userId;
        String stored;
        Users.User sub = null;
        if (users.isOwnerName(username)) {
            userId = Users.OWNER_ID;
            stored = ownerHash;
        } else {
            sub = users.byName(username);
            userId = sub == null ? null : sub.id();
            // An unknown name still costs a full hash check, so the answer time doesn't say which names exist.
            stored = sub == null ? Auth.dummyHash() : sub.hash();
        }
        if (!Auth.verify(password, stored) || userId == null) {
            limiter.failure(key, loopback);
            limiter.addressFailure(LoginLimiter.key(remote(ex)), loopback);
            audit.add(sub == null ? null : sub.id(), safeName(username), "login.failed", null, address(ex));
            Http.error(ex, 401, WRONG);
            return;
        }
        limiter.success(key);
        Users.Principal who = users.principal(userId);
        if (who == null) {
            // Right password, disabled account (or deleted a moment ago).
            audit.add(userId, sub.name(), "login.failed", "account disabled", address(ex));
            Http.error(ex, 403, "this account is disabled");
            return;
        }
        if (!who.owner()) users.noteLogin(userId);
        audit.add(who, "login", null, address(ex));
        startSession(ex, userId, who.mustChange(), stored);
    }

    /** A login name as typed, fit for the audit log: the allowed characters only, else "(invalid)". */
    private static String safeName(String name) {
        if (name == null || name.isBlank()) return "(owner)";
        return Users.NAME.matcher(name).matches() ? name : "(invalid)";
    }

    /** The stored hash of this user's password, or null. */
    private String storedHash(Users.Principal who) {
        if (who.owner()) return config.dashboardPassword();
        Users.User u = users.byId(who.id());
        return u == null ? null : u.hash();
    }

    /** Rate limit + verify a logged-in user's own password. False after answering 429 or 401. */
    private boolean checkPassword(HttpExchange ex, String password, String stored) throws IOException {
        String address = LoginLimiter.key(remote(ex));
        Users.Principal who = Router.user(ex);
        String key = address + "|" + (who == null ? "?" : who.id());
        boolean loopback = fromLoopback(ex) && !proxied(ex.getRequestHeaders());
        long wait = Math.max(limiter.tryAcquire(key, loopback), limiter.tryAcquire(address, loopback));
        if (wait > 0) {
            tooMany(ex, wait);
            return false;
        }
        if (!Auth.verify(password, stored)) {
            limiter.failure(key, loopback);
            limiter.addressFailure(address, loopback);
            Http.error(ex, 401, "wrong password");
            return false;
        }
        limiter.success(key);
        return true;
    }

    /** stored: the password hash just verified; a session made with it ends once it changes. */
    private void startSession(HttpExchange ex, String userId, boolean mustChange, String stored) throws IOException {
        String token = sessions.create(userId, Auth.tag(stored));
        sessions.elevate(token); // the password was just typed
        ex.getResponseHeaders().add("Set-Cookie", SessionStore.cookie(token));
        JsonObject out = Http.ok();
        out.addProperty("mustChange", mustChange);
        Http.json(ex, 200, out);
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
        sessionsEnded.run();
        ex.getResponseHeaders().add("Set-Cookie", SessionStore.clearCookie());
        ex.getResponseHeaders().set("Clear-Site-Data", "\"cache\""); // nothing this login saw stays in the browser cache
        Http.json(ex, 200, Http.ok());
    }

    /** The owner ends every session of every user; a sub-user ends their own. */
    private void logoutAll(HttpExchange ex) throws IOException {
        Users.Principal who = Router.user(ex);
        int n = who.owner() ? sessions.removeAllExcept(null) : sessions.removeUser(who.id(), null);
        sessionsEnded.run();
        System.out.println("Logged out " + (who.owner() ? "every website session" : "every session of " + who.name())
            + " (" + n + ") from " + address(ex) + ".");
        audit.add(who, "logout-all", who.owner() ? "every user" : null, address(ex));
        ex.getResponseHeaders().add("Set-Cookie", SessionStore.clearCookie());
        ex.getResponseHeaders().set("Clear-Site-Data", "\"cache\""); // nothing this login saw stays in the browser cache
        JsonObject out = Http.ok();
        out.addProperty("ended", n);
        Http.json(ex, 200, out);
    }

    /** {current, next}: the caller's own password; their other sessions are logged out, and must-change is cleared. */
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
        if (next.equals(current)) {
            Http.error(ex, 400, "pick a new password, not the current one");
            return;
        }
        Users.Principal who = Router.user(ex);
        String token = (String) Http.attr(ex, Router.SESSION_TOKEN);
        // This session is moved to the new password before the password changes, so a request racing the change from
        // this same browser isn't logged out by the credential check; it goes back if the change fails.
        String oldTag = sessions.credOf(token);
        String newHash = Auth.hash(next);
        if (who.owner()) {
            synchronized (config) {
                if (!checkPassword(ex, current, config.dashboardPassword())) return;
                sessions.retag(token, Auth.tag(newHash));
                try {
                    config.setDashboardPassword(newHash);
                } catch (IOException e) {
                    sessions.retag(token, oldTag);
                    System.err.println("Could not save the dashboard password to config.properties: " + e.getMessage());
                    Http.error(ex, 500, "could not save");
                    return;
                }
            }
        } else {
            if (!checkPassword(ex, current, storedHash(who))) return;
            sessions.retag(token, Auth.tag(newHash));
            try {
                users.setPassword(who.id(), newHash, false);
            } catch (Users.UserException e) {
                sessions.retag(token, oldTag);
                Http.error(ex, 401, "login required");
                return;
            } catch (IOException e) {
                sessions.retag(token, oldTag);
                System.err.println("Could not save users.json: " + e.getMessage());
                Http.error(ex, 500, "could not save");
                return;
            }
        }
        int ended = sessions.removeUser(who.id(), token);
        sessions.elevate(token);
        System.out.println("Website password of " + who.name() + " changed from " + address(ex) + "; " + ended + " other session(s) logged out.");
        audit.add(who, "password.change", null, address(ex));
        JsonObject out = Http.ok();
        out.addProperty("ended", ended);
        Http.json(ex, 200, out);
    }

    /** {password}: the caller's own password; "Admin unlocked" for 15 minutes in this session. */
    private void confirm(HttpExchange ex) throws IOException {
        JsonObject o = body(ex);
        if (o == null) return;
        String password = AddonRoutes.string(o, "password");
        if (password == null || password.length() > Auth.MAX_LENGTH) {
            Http.error(ex, 400, "password must be a string");
            return;
        }
        if (!checkPassword(ex, password, storedHash(Router.user(ex)))) return;
        sessions.elevate((String) Http.attr(ex, Router.SESSION_TOKEN));
        JsonObject out = Http.ok();
        out.addProperty("elevatedForSeconds", SessionStore.ELEVATED_MS / 1000);
        out.addProperty("elevatedUntil", sessions.elevatedUntil((String) Http.attr(ex, Router.SESSION_TOKEN)));
        Http.json(ex, 200, out);
    }

    /** Ends Admin unlocked for this session now. */
    private void lock(HttpExchange ex) throws IOException {
        sessions.lock((String) Http.attr(ex, Router.SESSION_TOKEN));
        Http.json(ex, 200, Http.ok());
    }
}
