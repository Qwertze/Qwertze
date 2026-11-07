package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Website sub-users through the real routes on a real server (127.0.0.1, a random port, a temp data folder):
 * migration of a single-password setup, the user lifecycle, permission enforcement per endpoint, stash scope, own vs
 * all orders, sessions ending on disable / password reset / delete, and the audit log.
 */
class UsersTest {
    static final String OWNER_PW = "owner-password-123";
    static final String TEMP_PW = "temporary-pass-1";
    static final String NEW_PW = "brand-new-pass-22";

    @TempDir
    Path data;
    HttpServer server;
    String base;
    Users users;
    SessionStore sessions;
    OrderStore orders;
    AuditLog audit;
    Router router;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void fastHashes() {
        Auth.iterations = 1000;
    }

    @AfterAll
    static void realHashes() {
        Auth.iterations = Auth.ITERATIONS;
    }

    /** A pre-sub-users setup: the owner password in config.properties and a session in sessions.json without a user. */
    String legacyOwnerSession;

    @BeforeEach
    void start() throws Exception {
        Config config = Config.load(data);
        config.setDashboardPassword(Auth.hash(OWNER_PW));
        SessionStore old = new SessionStore(data);
        legacyOwnerSession = old.create();
        // Strip the user field, as an older server wrote it.
        Path sf = data.resolve("sessions.json");
        Files.writeString(sf, Files.readString(sf).replaceAll("\"user\":\\s*\"owner\",?", ""));
        startServer(config);
    }

    private void startServer(Config config) throws Exception {
        IndexStore store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        store.putIndex(json("{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":["
            + "{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":1000,\"maxStack\":64}]}"));
        store.putIndex(json("{\"version\":1,\"server\":\"s\",\"stash\":\"b\",\"items\":["
            + "{\"key\":\"minecraft:dirt\",\"id\":\"minecraft:dirt\",\"count\":1000,\"maxStack\":64}]}"));
        orders = new OrderStore(data, store);
        orders.load();
        Owners owners = new Owners(data);
        owners.load();
        IconService icons = new IconService(data, id -> null, v -> false, new GameIcons(data.resolve("icons"), null, false), false);
        sessions = new SessionStore(data);
        sessions.load();
        users = new Users(data);
        users.load();
        audit = new AuditLog(data);
        audit.load();
        StashManagerServer.Routes r = StashManagerServer.routes(config, store, orders, owners, icons,
            new LinkState(config.keyState().generation()), List.of(), sessions, users, audit, new BotHub(), new LoginLimiter());
        router = r.router();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", router);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    // ---------- helpers ----------

    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    record Res(int status, JsonElement body, HttpResponse<String> raw) {
        JsonObject obj() {
            return body.getAsJsonObject();
        }

        String error() {
            return body != null && body.isJsonObject() && body.getAsJsonObject().has("error") ? body.getAsJsonObject().get("error").getAsString() : null;
        }
    }

    Res get(String token, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (token != null) b.header("Cookie", SessionStore.COOKIE + "=" + token);
        return send(b.build());
    }

    Res post(String token, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
            .header("X-Stash-Manager", "1").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        if (token != null) b.header("Cookie", SessionStore.COOKIE + "=" + token);
        return send(b.build());
    }

    private Res send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonElement body = null;
        try {
            body = r.body().isEmpty() ? null : JsonParser.parseString(r.body());
        } catch (RuntimeException ignored) {
        }
        return new Res(r.statusCode(), body, r);
    }

    /** Logs in; returns the session token. */
    String login(String username, String password) throws Exception {
        Res r = post(null, "/api/v1/auth/login", username == null
            ? "{\"password\":\"" + password + "\"}"
            : "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, r.status, "login " + username + ": " + r.raw.body());
        String setCookie = r.raw.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(SessionStore.COOKIE.length() + 1, setCookie.indexOf(';'));
    }

    String owner() throws Exception {
        return login(null, OWNER_PW);
    }

    /** Creates a sub-user (as the owner, who is Admin unlocked right after login); returns its id. */
    String create(String owner, String name, String perms, boolean mustChange) throws Exception {
        Res r = post(owner, "/api/v1/users", "{\"name\":\"" + name + "\",\"password\":\"" + TEMP_PW + "\",\"mustChange\":" + mustChange
            + (perms.startsWith("{") ? ",\"perms\":" + perms : ",\"preset\":\"" + perms + "\"") + "}");
        assertEquals(200, r.status, r.raw.body());
        return r.obj().getAsJsonObject("user").get("id").getAsString();
    }

    String placeOrder(String token, String stash, String key, int n) throws Exception {
        Res r = post(token, "/api/v1/orders", "{\"server\":\"s\",\"stash\":\"" + stash + "\",\"lines\":[{\"type\":\"item\",\"key\":\"" + key + "\",\"count\":" + n + "}]}");
        assertEquals(200, r.status, r.raw.body());
        return r.obj().getAsJsonObject("order").get("id").getAsString();
    }

    // ---------- tests ----------

    @Test
    void anExistingSinglePasswordSetupKeepsWorking() throws Exception {
        // The session made before sub-users is the owner's.
        Res state = get(legacyOwnerSession, "/api/v1/auth/state");
        assertTrue(state.obj().get("loggedIn").getAsBoolean());
        JsonObject user = state.obj().getAsJsonObject("user");
        assertEquals("owner", user.get("id").getAsString());
        assertTrue(user.get("owner").getAsBoolean());
        assertEquals(200, get(legacyOwnerSession, "/api/v1/users").status);
        // The owner logs in as before (no username), or with the owner's name.
        assertNotNull(owner());
        assertNotNull(login("owner", OWNER_PW));
        assertNotNull(login(" OWNER ", OWNER_PW));
        assertFalse(Files.exists(data.resolve("users.json")), "nothing written until a user is added");
    }

    @Test
    void everyLoggedInRouteDeclaresAPermission() {
        List<String> routes = router.describe();
        for (String r : routes) {
            if (r.contains(" SESSION") || r.contains(" BROWSER_WRITE")) {
                assertTrue(r.matches(".* (SESSION|BROWSER_WRITE) [A-Z_]+$"), r);
            }
        }
        // The only routes any user may use whatever their permissions are the auth ones.
        for (String r : routes) {
            if (r.endsWith(" ANY")) assertTrue(r.contains("/api/v1/auth/"), r);
        }
        assertThrows(IllegalArgumentException.class, () -> router.exact(Router.GET, "/x", Router.Guard.SESSION, ex -> { }));
        assertTrue(routes.stream().anyMatch(r -> r.contains("/api/v1/users") && r.endsWith(" OWNER")));
    }

    @Test
    void aNewUserMustChangeThePasswordFirst() throws Exception {
        String owner = owner();
        create(owner, "alice", "viewer", true);
        Res login = post(null, "/api/v1/auth/login", "{\"username\":\"alice\",\"password\":\"" + TEMP_PW + "\"}");
        assertEquals(200, login.status);
        assertTrue(login.obj().get("mustChange").getAsBoolean());
        String alice = login("Alice", TEMP_PW); // names are case-insensitive
        Res blocked = get(alice, "/api/v1/stashes");
        assertEquals(403, blocked.status);
        assertTrue(blocked.obj().get("mustChange").getAsBoolean());
        assertTrue(get(alice, "/api/v1/auth/state").obj().getAsJsonObject("user").get("mustChange").getAsBoolean());

        assertEquals(400, post(alice, "/api/v1/auth/password", "{\"current\":\"" + TEMP_PW + "\",\"next\":\"" + TEMP_PW + "\"}").status,
            "the same password again is refused");
        assertEquals(401, post(alice, "/api/v1/auth/password", "{\"current\":\"wrong-wrong-wrong\",\"next\":\"" + NEW_PW + "\"}").status);
        Res changed = post(alice, "/api/v1/auth/password", "{\"current\":\"" + TEMP_PW + "\",\"next\":\"" + NEW_PW + "\"}");
        assertEquals(200, changed.status, changed.raw.body());
        assertEquals(1, changed.obj().get("ended").getAsInt(), "alice's other session ended");
        assertEquals(200, get(alice, "/api/v1/stashes").status);
        assertEquals(401, post(null, "/api/v1/auth/login", "{\"username\":\"alice\",\"password\":\"" + TEMP_PW + "\"}").status);
        assertNotNull(login("alice", NEW_PW));
        // The owner's own session was untouched.
        assertEquals(200, get(owner, "/api/v1/users").status);
    }

    @Test
    void loginErrorsDontTellWhichNamesExist() throws Exception {
        String owner = owner();
        String id = create(owner, "bob", "viewer", false);
        Res unknown = post(null, "/api/v1/auth/login", "{\"username\":\"nobody\",\"password\":\"whatever-password\"}");
        Res wrong = post(null, "/api/v1/auth/login", "{\"username\":\"bob\",\"password\":\"whatever-password\"}");
        assertEquals(401, unknown.status);
        assertEquals(401, wrong.status);
        assertEquals(unknown.error(), wrong.error());
        assertEquals(400, post(null, "/api/v1/auth/login", "{\"username\":5,\"password\":\"x\"}").status);

        assertEquals(200, post(owner, "/api/v1/users/" + id, "{\"disabled\":true}").status);
        Res disabled = post(null, "/api/v1/auth/login", "{\"username\":\"bob\",\"password\":\"" + TEMP_PW + "\"}");
        assertEquals(403, disabled.status);
        assertEquals("this account is disabled", disabled.error());
        // A wrong password for a disabled account looks like any wrong password.
        assertEquals(401, post(null, "/api/v1/auth/login", "{\"username\":\"bob\",\"password\":\"whatever-password\"}").status);
    }

    @Test
    void aViewerCanOnlyLook() throws Exception {
        String owner = owner();
        String ownerOrder = placeOrder(owner, "a", "minecraft:diamond", 10);
        create(owner, "viewer1", "viewer", false);
        String v = login("viewer1", TEMP_PW);

        assertEquals(200, get(v, "/api/v1/stashes").status);
        assertEquals(200, get(v, "/api/v1/index?server=s&stash=a").status);
        assertEquals(200, get(v, "/api/v1/orders?server=s&stash=a").status);

        String order = "{\"server\":\"s\",\"stash\":\"a\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:diamond\",\"count\":1}]}";
        Res refused = post(v, "/api/v1/orders", order);
        assertEquals(403, refused.status);
        assertEquals("order", refused.obj().get("permission").getAsString());
        assertEquals(403, post(v, "/api/v1/orders/" + ownerOrder + "/cancel", null).status);
        assertEquals(403, post(v, "/api/v1/orders/" + ownerOrder + "/retry", null).status);
        assertEquals(403, post(v, "/api/v1/orders/" + ownerOrder + "/force-cancel", null).status);
        assertEquals(403, post(v, "/api/v1/orders/clear", "{\"server\":\"s\",\"stash\":\"a\"}").status);
        assertEquals(403, post(v, "/api/v1/rescan", "{\"server\":\"s\",\"stash\":\"a\"}").status);
        assertEquals(403, post(v, "/api/v1/stashes/release", "{\"server\":\"s\",\"stash\":\"a\"}").status);
        assertEquals(403, get(v, "/api/v1/link").status);
        assertEquals(403, post(v, "/api/v1/link/regenerate", null).status);
        assertEquals(403, get(v, "/api/v1/bots").status);
        assertEquals(403, get(v, "/api/v1/bots/bot1/activity").status);
        assertEquals(403, get(v, "/api/v1/bots/bot1/console").status);
        assertEquals(403, post(v, "/api/v1/bots/bot1/console", "{\"command\":\"help\"}").status);
        assertEquals(403, post(v, "/api/v1/bots/bot1/settings", "{\"scope\":\"plugin\",\"changes\":[]}").status);
        assertEquals(403, get(v, "/api/v1/users").status);
        assertEquals(403, post(v, "/api/v1/users", "{\"name\":\"x\",\"password\":\"" + TEMP_PW + "\"}").status);
        assertEquals(403, post(v, "/api/v1/users/owner", "{\"name\":\"me\"}").status);
        assertEquals(403, get(v, "/api/v1/audit").status);
        // The order is untouched.
        assertTrue(orders.listJson("s", "a").toString().contains("\"pending\""));
    }

    @Test
    void anOrdererChangesOnlyTheirOwnOrders() throws Exception {
        String owner = owner();
        String ownerOrder = placeOrder(owner, "a", "minecraft:diamond", 10);
        create(owner, "carol", "orderer", false);
        String c = login("carol", TEMP_PW);
        String mine = placeOrder(c, "a", "minecraft:diamond", 5);

        JsonArray list = get(c, "/api/v1/orders?server=s&stash=a").obj().getAsJsonArray("orders");
        boolean sawMine = false;
        for (JsonElement e : list) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("id").getAsString().equals(mine)) {
                sawMine = true;
                assertEquals("carol", o.getAsJsonObject("placedBy").get("name").getAsString());
            }
            if (o.get("id").getAsString().equals(ownerOrder)) assertEquals("owner", o.getAsJsonObject("placedBy").get("id").getAsString());
        }
        assertTrue(sawMine);

        Res other = post(c, "/api/v1/orders/" + ownerOrder + "/cancel", null);
        assertEquals(403, other.status);
        assertEquals("ordersAll", other.obj().get("permission").getAsString());
        assertEquals(200, post(c, "/api/v1/orders/" + mine + "/cancel", null).status);
        Res retried = post(c, "/api/v1/orders/" + mine + "/retry", null);
        assertEquals(200, retried.status, retried.raw.body());
        assertEquals("carol", retried.obj().getAsJsonObject("order").getAsJsonObject("placedBy").get("name").getAsString());
        assertEquals(403, post(c, "/api/v1/orders/clear", "{\"server\":\"s\",\"stash\":\"a\"}").status);
        assertEquals(403, get(c, "/api/v1/bots").status);
        // The owner may change anyone's.
        assertEquals(200, post(owner, "/api/v1/orders/" + retried.obj().getAsJsonObject("order").get("id").getAsString() + "/cancel", null).status);
    }

    @Test
    void anOperatorWithAllOrdersCanChangeOthersButNotManageUsers() throws Exception {
        String owner = owner();
        String ownerOrder = placeOrder(owner, "a", "minecraft:diamond", 10);
        create(owner, "dave", "operator", false);
        String d = login("dave", TEMP_PW);
        assertEquals(200, post(d, "/api/v1/orders/" + ownerOrder + "/cancel", null).status);
        assertEquals(200, post(d, "/api/v1/orders/clear", "{\"server\":\"s\",\"stash\":\"a\"}").status);
        assertEquals(200, get(d, "/api/v1/bots").status);
        assertEquals(200, post(d, "/api/v1/rescan", "{\"server\":\"s\",\"stash\":\"a\"}").status);
        assertEquals(403, get(d, "/api/v1/link").status);
        assertEquals(403, get(d, "/api/v1/users").status);
        // Console is "none" for an operator: refused before the password check.
        Res console = get(d, "/api/v1/bots/bot1/console");
        assertEquals(403, console.status);
        assertEquals("console", console.obj().get("permission").getAsString());
    }

    @Test
    void aScopedUserSeesOnlyTheirStash() throws Exception {
        String owner = owner();
        String otherOrder = placeOrder(owner, "b", "minecraft:dirt", 10);
        create(owner, "erin", "{\"view\":true,\"order\":true,\"ordersAll\":true,\"rescan\":true,\"stashes\":[{\"server\":\"s\",\"stash\":\"a\"}]}", false);
        String e = login("erin", TEMP_PW);

        JsonArray stashes = get(e, "/api/v1/stashes").body.getAsJsonArray();
        assertEquals(1, stashes.size());
        assertEquals("a", stashes.get(0).getAsJsonObject().get("stash").getAsString());
        assertEquals(2, get(owner, "/api/v1/stashes").body.getAsJsonArray().size());

        assertEquals(200, get(e, "/api/v1/index?server=s&stash=a").status);
        assertEquals(404, get(e, "/api/v1/index?server=s&stash=b").status);
        assertEquals(404, get(e, "/api/v1/orders?server=s&stash=b").status);
        assertEquals(404, post(e, "/api/v1/orders", "{\"server\":\"s\",\"stash\":\"b\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":1}]}").status);
        assertEquals(404, post(e, "/api/v1/orders/" + otherOrder + "/cancel", null).status, "even with ordersAll, out of scope");
        assertEquals(404, post(e, "/api/v1/orders/clear", "{\"server\":\"s\",\"stash\":\"b\"}").status);
        assertEquals(404, post(e, "/api/v1/rescan", "{\"server\":\"s\",\"stash\":\"b\"}").status);
        assertEquals(200, post(e, "/api/v1/rescan", "{\"server\":\"s\",\"stash\":\"a\"}").status);
    }

    @Test
    void consoleListModeAndBotScope() {
        Users.Perms p = Users.Perms.parse(json("{\"console\":\"list\",\"consoleAllow\":[\"stash  Orders\",\"status\"],\"bots\":[\"zenith2\"]}"));
        assertTrue(p.activity(), "console implies the Bots view");
        assertEquals(List.of("stash orders", "status"), p.consoleAllow());
        assertTrue(p.consoleAllows("stash orders"));
        assertTrue(p.consoleAllows("  STASH   orders on "));
        assertTrue(p.consoleAllows("status"));
        assertFalse(p.consoleAllows("stash ordersx"));
        assertFalse(p.consoleAllows("stash"));
        assertFalse(p.consoleAllows("statuses"));
        assertFalse(p.consoleAllows("stash orders; whitelist add x"));
        assertFalse(p.consoleAllows("status | x"));
        assertFalse(p.consoleAllows("status && x"));
        assertFalse(p.consoleAllows("status `x`"));
        assertTrue(Users.Perms.parse(json("{\"console\":\"full\"}")).consoleAllows("anything; at all"));
        assertFalse(Users.Perms.parse(json("{\"console\":\"none\"}")).consoleAllows("status"));
        assertTrue(p.seesBot("zenith2"));
        assertFalse(p.seesBot("other"));
        assertTrue(Users.Perms.ALL.seesBot("other"));

        assertThrows(IllegalArgumentException.class, () -> Users.Perms.parse(json("{\"console\":\"some\"}")));
        assertThrows(IllegalArgumentException.class, () -> Users.Perms.parse(json("{\"view\":\"yes\"}")));
        assertThrows(IllegalArgumentException.class, () -> Users.Perms.parse(json("{\"consoleAllow\":[\"a;b\"]}")));
        assertThrows(IllegalArgumentException.class, () -> Users.Perms.parse(json("{\"bots\":[\"../x\"]}")));
        assertThrows(IllegalArgumentException.class, () -> Users.Perms.parse(json("{\"stashes\":[{\"server\":\"s\"}]}")));
        Users.Perms implied = Users.Perms.parse(json("{\"order\":true,\"settings\":true}"));
        assertTrue(implied.view());
        assertTrue(implied.activity());
    }

    @Test
    void consoleNeedsTheRightModeThenAdminUnlockedThenAnAllowedCommand() throws Exception {
        String owner = owner();
        create(owner, "frank", "{\"console\":\"list\",\"consoleAllow\":[\"stash orders\"]}", false);
        String f = login("frank", TEMP_PW); // just logged in: Admin unlocked
        // No such bot: 404 (a scoped or unscoped user learns nothing more).
        assertEquals(404, post(f, "/api/v1/bots/nobot/console", "{\"command\":\"stash orders\"}").status);
        assertEquals(404, get(f, "/api/v1/bots/nobot/console").status);
        // Settings: plugin changes need "settings"; reading proxy settings needs proxy read.
        create(owner, "gina", "{\"activity\":true,\"bots\":[\"zenith2\"]}", false);
        String g = login("gina", TEMP_PW);
        assertEquals(404, get(g, "/api/v1/bots/other/activity").status, "a bot outside the scope is 'no such bot'");
        assertEquals(0, get(g, "/api/v1/bots").body.getAsJsonArray().size());
    }

    @Test
    void disablingResettingAndDeletingEndSessionsAtOnce() throws Exception {
        String owner = owner();
        String id = create(owner, "hank", "viewer", false);
        String h = login("hank", TEMP_PW);
        String h2 = login("hank", TEMP_PW);
        assertEquals(200, get(h, "/api/v1/stashes").status);

        // Permissions change on the next request.
        assertEquals(200, post(owner, "/api/v1/users/" + id, "{\"perms\":{\"view\":false}}").status);
        assertEquals(403, get(h, "/api/v1/stashes").status);
        assertEquals(200, post(owner, "/api/v1/users/" + id, "{\"preset\":\"viewer\"}").status);
        assertEquals(200, get(h, "/api/v1/stashes").status);

        Res disabled = post(owner, "/api/v1/users/" + id, "{\"disabled\":true}");
        assertEquals(200, disabled.status);
        assertEquals(2, disabled.obj().get("ended").getAsInt());
        assertEquals(401, get(h, "/api/v1/stashes").status);
        assertEquals(401, get(h2, "/api/v1/stashes").status);
        assertFalse(get(h, "/api/v1/auth/state").obj().get("loggedIn").getAsBoolean());
        assertEquals(200, post(owner, "/api/v1/users/" + id, "{\"disabled\":false}").status);
        assertEquals(401, get(h, "/api/v1/stashes").status, "re-enabling doesn't bring old sessions back");

        h = login("hank", TEMP_PW);
        Res reset = post(owner, "/api/v1/users/" + id + "/password", "{\"password\":\"" + NEW_PW + "\",\"mustChange\":true}");
        assertEquals(200, reset.status);
        assertEquals(1, reset.obj().get("ended").getAsInt());
        assertEquals(401, get(h, "/api/v1/stashes").status);
        h = login("hank", NEW_PW);
        assertEquals(403, get(h, "/api/v1/stashes").status, "must change the reset password first");

        Res logout = post(owner, "/api/v1/users/" + id + "/logout", null);
        assertEquals(1, logout.obj().get("ended").getAsInt());
        assertEquals(401, get(h, "/api/v1/auth/state").obj().get("loggedIn").getAsBoolean() ? 200 : 401);

        h = login("hank", NEW_PW);
        assertEquals(200, post(owner, "/api/v1/users/" + id + "/delete", null).status);
        assertEquals(401, post(h, "/api/v1/auth/logout", null).status);
        assertNull(users.byId(id));
        assertEquals(401, post(null, "/api/v1/auth/login", "{\"username\":\"hank\",\"password\":\"" + NEW_PW + "\"}").status);
        assertEquals(404, post(owner, "/api/v1/users/" + id + "/delete", null).status);
    }

    @Test
    void userManagementNeedsAdminUnlockedAndValidInput() throws Exception {
        String owner = owner();
        assertEquals(200, post(owner, "/api/v1/auth/lock", null).status);
        Res locked = post(owner, "/api/v1/users", "{\"name\":\"ivy\",\"password\":\"" + TEMP_PW + "\"}");
        assertEquals(403, locked.status);
        assertEquals("confirm password", locked.error());
        assertEquals(200, get(owner, "/api/v1/users").status, "reading the list needs no unlock");
        assertEquals(200, post(owner, "/api/v1/auth/confirm", "{\"password\":\"" + OWNER_PW + "\"}").status);

        assertEquals(400, post(owner, "/api/v1/users", "{\"name\":\"bad name\",\"password\":\"" + TEMP_PW + "\"}").status);
        assertEquals(400, post(owner, "/api/v1/users", "{\"name\":\"ivy\",\"password\":\"short\"}").status);
        assertEquals(400, post(owner, "/api/v1/users", "{\"name\":\"ivy\",\"password\":\"" + TEMP_PW + "\",\"preset\":\"god\"}").status);
        assertEquals(409, post(owner, "/api/v1/users", "{\"name\":\"OWNER\",\"password\":\"" + TEMP_PW + "\"}").status);
        create(owner, "ivy", "viewer", true);
        assertEquals(409, post(owner, "/api/v1/users", "{\"name\":\"IVY\",\"password\":\"" + TEMP_PW + "\"}").status);

        // Renaming the owner: the new name logs in, and no sub-user may take it.
        assertEquals(409, post(owner, "/api/v1/users/owner", "{\"name\":\"ivy\"}").status);
        assertEquals(200, post(owner, "/api/v1/users/owner", "{\"name\":\"boss\"}").status);
        assertNotNull(login("boss", OWNER_PW));
        assertNotNull(login(null, OWNER_PW));
        assertEquals(401, post(null, "/api/v1/auth/login", "{\"username\":\"owner\",\"password\":\"" + OWNER_PW + "\"}").status);

        JsonObject list = get(owner, "/api/v1/users").obj();
        assertEquals("boss", list.getAsJsonObject("owner").get("name").getAsString());
        assertEquals(1, list.getAsJsonArray("users").size());
        assertTrue(list.getAsJsonObject("presets").has("operator"));
        assertFalse(list.toString().contains("pbkdf2"), "hashes never leave the server");
    }

    @Test
    void usersSurviveARestartAndTheAuditLogRecordsChanges() throws Exception {
        String owner = owner();
        String id = create(owner, "jill", "{\"view\":true,\"console\":\"list\",\"consoleAllow\":[\"status\"]}", false);
        post(null, "/api/v1/auth/login", "{\"username\":\"nobody\",\"password\":\"whatever-password\"}");
        String text = Files.readString(data.resolve("users.json"));
        assertTrue(text.contains("jill") && text.contains("pbkdf2-sha256$"));
        assertFalse(text.contains(TEMP_PW));

        Users reloaded = new Users(data);
        reloaded.load();
        Users.Principal p = reloaded.principal(id);
        assertEquals("jill", p.name());
        assertTrue(p.perms().consoleAllows("status"));

        JsonArray entries = get(owner, "/api/v1/audit?limit=50").obj().getAsJsonArray("entries");
        String all = entries.toString();
        assertTrue(all.contains("\"user.create\""), all);
        assertTrue(all.contains("\"login.failed\"") && all.contains("nobody"), all);
        assertTrue(all.contains("\"login\""), all);
        assertEquals("user.create", entries.get(1).getAsJsonObject().get("action").getAsString(), "newest first");
        assertFalse(all.contains(TEMP_PW));
    }

    @Test
    void aBrokenUsersFileStopsTheServerInsteadOfDroppingUsers() throws Exception {
        Files.writeString(data.resolve("users.json"), "{not json");
        assertThrows(IOException.class, () -> new Users(data).load());
    }

    @Test
    void badPresetsAndEmptyUpdatesAre400NotDroppedConnections() throws Exception {
        String owner = owner();
        String id = create(owner, "lou", "viewer", false);
        assertEquals(400, post(owner, "/api/v1/users/" + id, "{\"preset\":5}").status);
        assertEquals(400, post(owner, "/api/v1/users/" + id, "{\"preset\":\"god\"}").status);
        assertEquals(400, post(owner, "/api/v1/users/" + id, "{\"preset\":\"viewer\",\"perms\":{\"view\":true}}").status);
        assertEquals(400, post(owner, "/api/v1/users/" + id, "{}").status);
        assertEquals(400, post(owner, "/api/v1/users/" + id, "{\"preset\":null}").status, "null preset alone: nothing to change");
        assertEquals(400, post(owner, "/api/v1/users", "{\"name\":\"m\",\"password\":\"" + TEMP_PW + "\",\"preset\":7}").status);
        // perms replaces the whole object, and the implications are applied again.
        assertEquals(200, post(owner, "/api/v1/users/" + id, "{\"perms\":{\"order\":true,\"rescan\":true}}").status);
        assertEquals(200, post(owner, "/api/v1/users/" + id, "{\"perms\":{\"ordersAll\":true}}").status);
        Users.Perms p = users.byId(id).perms();
        assertTrue(p.view() && p.ordersAll());
        assertFalse(p.order() || p.rescan(), "not merged with the old permissions");
    }

    @Test
    void oneAccountCantPushTheOwnerOut() throws Exception {
        String owner = owner();
        String id = create(owner, "nia", "viewer", false);
        for (int i = 0; i < 230; i++) sessions.create(id);
        assertEquals(SessionStore.MAX_PER_USER, sessions.countByUser().get(id));
        assertEquals(200, get(owner, "/api/v1/users").status, "the owner's session survived");
        assertEquals(200, get(legacyOwnerSession, "/api/v1/stashes").status);
    }

    @Test
    void theLinkPanelListsOnlyStashesInScope() throws Exception {
        String owner = owner();
        create(owner, "oz", "{\"link\":true,\"stashes\":[{\"server\":\"s\",\"stash\":\"a\"}]}", false);
        String o = login("oz", TEMP_PW);
        Res link = get(o, "/api/v1/link");
        assertEquals(200, link.status);
        assertFalse(link.obj().has("apiKey"), "the key is the owner's only, even Admin unlocked");
        assertTrue(link.obj().get("apiKeyOwnerOnly").getAsBoolean());
        assertEquals(403, post(o, "/api/v1/link/regenerate", null).status);
        assertTrue(get(owner, "/api/v1/link").obj().has("apiKey"), "the owner, just logged in, sees it");
        assertEquals(200, post(o, "/api/v1/stashes/release", "{\"server\":\"s\",\"stash\":\"a\"}").status, "release in scope is allowed");
        assertEquals(404, post(o, "/api/v1/stashes/release", "{\"server\":\"s\",\"stash\":\"b\"}").status);
    }

    @Test
    void consoleListModeRefusesLineBreaksAndSubstitution() {
        Users.Perms p = Users.Perms.parse(json("{\"console\":\"list\",\"consoleAllow\":[\"say\"]}"));
        assertTrue(p.consoleAllows("say hi"));
        assertFalse(p.consoleAllows("say a\n;x"));
        assertFalse(p.consoleAllows("say a\nstop"));
        assertFalse(p.consoleAllows("say $(stop)"));
        assertFalse(p.consoleAllows("sayx"));
        assertEquals("User[u-000000000000 x]", new Users.User("u-000000000000", "x", "pbkdf2-sha256$1$a$b", p, false, false, 0, 0, 0).toString());
    }

    @Test
    void aSessionMadeWithAnOldPasswordEndsEvenIfItWasCreatedAfterTheChange() throws Exception {
        String owner = owner();
        String id = create(owner, "pat", "viewer", false);
        String oldHash = users.byId(id).hash();
        // A login that verified the old password, but only creates its session after the reset went through.
        assertEquals(200, post(owner, "/api/v1/users/" + id + "/password", "{\"password\":\"" + NEW_PW + "\",\"mustChange\":false}").status);
        String late = sessions.create(id, Auth.tag(oldHash));
        assertEquals(401, get(late, "/api/v1/stashes").status);
        assertFalse(get(late, "/api/v1/auth/state").obj().get("loggedIn").getAsBoolean());
        // The same for the owner: changing the owner password ends a session made with the old one.
        String ownerLate = sessions.create(Users.OWNER_ID, Auth.tag(Config.load(data).dashboardPassword()));
        Res changed = post(owner, "/api/v1/auth/password", "{\"current\":\"" + OWNER_PW + "\",\"next\":\"" + NEW_PW + "\"}");
        assertEquals(200, changed.status, changed.raw.body());
        assertEquals(401, get(ownerLate, "/api/v1/users").status);
        assertEquals(200, get(owner, "/api/v1/users").status, "the session that changed it stays");
        // A sub-user session without a tag (never made by this server) is refused; untagged owner sessions are old ones.
        String untagged = sessions.create(id);
        assertEquals(401, get(untagged, "/api/v1/stashes").status);
    }

    @Test
    void namesDontMatchThroughUnicodeCaseFolding() throws Exception {
        String owner = owner();
        create(owner, "kai", "viewer", false);
        // U+212A KELVIN SIGN folds to k with equalsIgnoreCase; it must not name "kai" or the owner.
        assertEquals(401, post(null, "/api/v1/auth/login", "{\"username\":\"\u212Aai\",\"password\":\"" + TEMP_PW + "\"}").status);
        assertFalse(users.isOwnerName("o\u0175ner"));
        assertNotNull(login("KAI", TEMP_PW));
    }

    @Test
    void aListModeUserSeesOnlyTheirOwnConsoleEntries() {
        BotHub hub = new BotHub();
        hub.hello("b1", "P", new JsonObject(), new JsonObject(), List.of("console"));
        BotHub.Bot b = hub.bot("b1");
        hub.queueCommand(b, "status", "owner");
        hub.queueCommand(b, "stash orders", "u-000000000001");
        assertEquals(2, hub.console(b, 0, null).getAsJsonArray("entries").size());
        JsonArray mine = hub.console(b, 0, "u-000000000001").getAsJsonArray("entries");
        assertEquals(1, mine.size());
        assertEquals("stash orders", mine.get(0).getAsJsonObject().get("command").getAsString());
        Users.Perms p = Users.Perms.parse(json("{\"console\":\"list\",\"consoleAllow\":[\"stash orders\"]}"));
        assertFalse(p.consoleAllows("stash orders \u2028stop"), "non-ASCII is refused in list mode");
        assertFalse(p.consoleAllows("stash orders > file"));
        assertTrue(p.consoleAllows("stash orders on"));
    }

    @Test
    void auditHidesLoginStyleCommands() {
        assertTrue(AuditLog.hideCommand("login hunter2hunter2"));
        assertTrue(AuditLog.hideCommand("/register pw pw"));
        assertTrue(AuditLog.hideCommand("auth something"));
        assertTrue(AuditLog.hideCommand("stash manager key abc"));
        assertFalse(AuditLog.hideCommand("stash orders on"));
        assertFalse(AuditLog.hideCommand("list"));
    }

    @Test
    void addressFailuresAcrossAccountsLockTheAddressAndASuccessDoesntResetThem() {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(0);
        LoginLimiter l = new LoginLimiter(now::get);
        for (int i = 0; i < LoginLimiter.ADDRESS_FAILURES - 1; i++) {
            l.addressFailure("10.0.0.9", false);
            l.success("10.0.0.9|u-000000000001"); // a good login with another account in between
        }
        assertEquals(0, l.tryAcquire("10.0.0.9", false));
        l.addressFailure("10.0.0.9", false);
        assertEquals(LoginLimiter.FIRST_LOCK_MS, l.tryAcquire("10.0.0.9", false));
        assertEquals(0, l.tryAcquire("10.0.0.10", false));
    }

    @Test
    void onlyRealIpLiteralsAreTrustedFromProxyHeaders() {
        assertTrue(AuthRoutes.isIpLiteral("203.0.113.7"));
        assertTrue(AuthRoutes.isIpLiteral("::1"));
        assertTrue(AuthRoutes.isIpLiteral("2001:db8::5"));
        assertFalse(AuthRoutes.isIpLiteral("999.1.1.1"));
        assertFalse(AuthRoutes.isIpLiteral("beef"));
        assertFalse(AuthRoutes.isIpLiteral("......."));
        assertFalse(AuthRoutes.isIpLiteral("example.com"));
        assertFalse(AuthRoutes.isIpLiteral("1.2.3.4.5"));
    }

    @Test
    void aSubUserLogsOutOnlyThemselvesEverywhere() throws Exception {
        String owner = owner();
        create(owner, "kim", "viewer", false);
        String k = login("kim", TEMP_PW);
        Res r = post(k, "/api/v1/auth/logout-all", null);
        assertEquals(200, r.status);
        assertEquals(1, r.obj().get("ended").getAsInt());
        assertEquals(200, get(owner, "/api/v1/users").status, "the owner stays logged in");
        assertEquals(401, get(k, "/api/v1/stashes").status);
    }
}
