package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A real server on 127.0.0.1 (random port, temp data folder) with a synthetic recipe source, for the crafting tests:
 * stash s/a on Minecraft 1.21.4 holding spruce logs and a few other materials.
 */
abstract class CraftHarness {
    static final String OWNER_PW = "owner-password-123";
    static final String PW = "temporary-pass-1";
    static final String MC = "1.21.4";
    static final String KEY = "\"server\":\"s\",\"stash\":\"a\"";

    /** A recipe source that serves the synthetic table for MC, with a state the tests can change. */
    static final class FakeRecipes implements RecipeSource {
        volatile RecipeTable table;
        volatile String state = "ready";
        /** The Minecraft version the table is served for (the enchant tests use 1.21.11). */
        volatile String mc = MC;
        final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();

        FakeRecipes(RecipeTable table) {
            this.table = table;
        }

        @Override
        public RecipeTable table(String mcVersion) {
            return mc.equals(mcVersion) && state.equals("ready") ? table : null;
        }

        @Override
        public String state(String mcVersion) {
            return mc.equals(mcVersion) ? state : "none";
        }

        @Override
        public void request(String mcVersion) {
            requests.incrementAndGet();
        }
    }

    @TempDir
    Path data;
    HttpServer server;
    String base;
    byte[] k;
    String owner;
    IndexStore store;
    OrderStore orders;
    CraftService craft;
    /** The routes built by start(): the enchant tests reach their capability store, limits and plan route through it. */
    StashManagerServer.Routes routes;
    FakeRecipes recipes;
    Users users;
    final AtomicLong now = new AtomicLong(1_000_000_000_000L);
    /** The clock of the bots' capability memory (tests move it to age a heartbeat). */
    final AtomicLong botClock = new AtomicLong(System.currentTimeMillis());
    /** The clock of the area reports (support is unknown for 2 minutes after a start). */
    final AtomicLong areaClock = new AtomicLong(System.currentTimeMillis());
    /** The clock of the order store's claim rules. */
    final AtomicLong orderClock = new AtomicLong(System.currentTimeMillis());
    BotHub hub;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void fastHashes() {
        Auth.iterations = 1000;
    }

    @AfterAll
    static void realHashes() {
        Auth.iterations = Auth.ITERATIONS;
    }

    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    /** The index of s/a: logs, planks, sticks and a few more (all plain keys), and a damaged-name item with a component key. */
    String indexJson() {
        return "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\",\"mcVersion\":\"" + MC + "\",\"items\":["
            + item("minecraft:spruce_log", 10, 64) + "," + item("minecraft:oak_log", 3, 64) + "," + item("minecraft:birch_log", 1, 64) + ","
            + item("minecraft:bamboo", 14, 64) + "," + item("minecraft:iron_nugget", 54, 64) + ","
            + item("minecraft:spruce_planks", 2, 64) + ","
            + "{\"key\":\"minecraft:spruce_planks#ab12\",\"id\":\"minecraft:spruce_planks\",\"count\":500,\"maxStack\":64}]}";
    }

    static String item(String id, int count, int max) {
        return "{\"key\":\"" + id + "\",\"id\":\"" + id + "\",\"count\":" + count + ",\"maxStack\":" + max + ",\"englishName\":\"" + Redact.prettyId(id) + "\"}";
    }

    void start() throws Exception {
        start(true);
    }

    void start(boolean withIndex) throws Exception {
        Config config = Config.load(data);
        config.setDashboardPassword(Auth.hash(OWNER_PW));
        k = Signer.deriveKey(config.apiKey());
        store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        if (withIndex) store.putIndex(json(indexJson()));
        orders = new OrderStore(data, store);
        orders.load();
        orders.clock(orderClock::get);
        Owners owners = new Owners(data);
        owners.load();
        IconService icons = new IconService(data, id -> null, v -> false, new GameIcons(data.resolve("icons"), null, false), false);
        SessionStore sessions = new SessionStore(data);
        sessions.load();
        users = new Users(data);
        users.load();
        recipes = new FakeRecipes(RecipeFixtures.table());
        hub = new BotHub();
        AuditLog audit = new AuditLog(data);
        StashManagerServer.Routes r = StashManagerServer.routes(config, store, orders, owners, icons,
            new LinkState(config.keyState().generation()), List.of(), sessions, users, audit, hub, new LoginLimiter(),
            new PlanStore(now::get), new ViewerHub(hub, audit), recipes, new CraftBots(botClock::get), new AreaBots(areaClock::get));
        craft = r.craft();
        routes = r;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", r.router());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        owner = login(null, OWNER_PW);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    static final class Res {
        final int status;
        final String body;
        final java.net.http.HttpHeaders headers;

        Res(int status, String body, java.net.http.HttpHeaders headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        JsonObject obj() {
            return JsonParser.parseString(body).getAsJsonObject();
        }

        String error() {
            return JsonParser.parseString(body).getAsJsonObject().get("error").getAsString();
        }
    }

    Res botSend(String method, String path, String body) throws Exception {
        return botSend(method, path, body, "-", null);
    }

    Res botSend(String method, String path, String body, String botId, String ifNoneMatch) throws Exception {
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        long ts = System.currentTimeMillis();
        byte[] n = new byte[16];
        new java.security.SecureRandom().nextBytes(n);
        String nonce = HexFormat.of().formatHex(n);
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + path))
            .header(Signer.REQUEST_HEADER, "v1 " + botId + " " + ts + " " + nonce + " " + Signer.requestSig(k, method, path, ts, nonce, b));
        if (ifNoneMatch != null) req.header("If-None-Match", ifNoneMatch);
        if (method.equals("POST")) req.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(b));
        else req.GET();
        HttpResponse<String> r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body(), r.headers());
    }

    Res botPost(String path, String body) throws Exception {
        return botSend("POST", path, body);
    }

    Res get(String token, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (token != null) b.header("Cookie", SessionStore.COOKIE + "=" + token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body(), r.headers());
    }

    Res post(String token, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
            .header("X-Stash-Manager", "1").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Cookie", SessionStore.COOKIE + "=" + token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body(), r.headers());
    }

    String login(String username, String password) throws Exception {
        String body = username == null ? "{\"password\":\"" + password + "\"}" : "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login"))
            .header("X-Stash-Manager", "1").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        String c = r.headers().firstValue("Set-Cookie").orElseThrow();
        return c.substring(SessionStore.COOKIE.length() + 1, c.indexOf(';'));
    }

    String userToken(String name, String perms) throws Exception {
        Res r = post(owner, "/api/v1/users", "{\"name\":\"" + name + "\",\"password\":\"" + PW + "\",\"mustChange\":false,\"perms\":" + perms + "}");
        assertEquals(200, r.status, r.body);
        return login(name, PW);
    }

    JsonObject ok(Res r) {
        assertEquals(200, r.status, r.body);
        return r.obj();
    }

    // ---------- the bot side ----------

    static String hash40(char c) {
        return String.valueOf(c).repeat(40);
    }

    String tableHash() {
        return recipes.table.tableHash();
    }

    /** craftOrders as a capable bot sends it. */
    String cap(String recipesHash, String tableStatus, int maxFills) {
        return "{\"v\":1,\"enabled\":true,\"recipes\":\"" + recipesHash + "\",\"mc\":\"" + MC + "\",\"table\":\"" + tableStatus
            + "\",\"maxFills\":" + maxFills + ",\"seen\":[\"118,64,-30\"]}";
    }

    Res heartbeat(String extra) throws Exception {
        return botPost("/api/v1/heartbeat", "{" + KEY + ",\"player\":\"Bob\"" + (extra == null ? "" : "," + extra) + "}");
    }

    /** A bot that can craft: capability sent, and the settings hash of the server cached (so the table counts as synced). */
    JsonObject capableBeat(String tableStatus, int maxFills) throws Exception {
        JsonObject first = ok(heartbeat("\"craftOrders\":" + cap(tableHash(), tableStatus, maxFills) + ",\"craftHash\":null"));
        String h = first.get("craftHash").getAsString();
        return ok(heartbeat("\"craftOrders\":" + cap(tableHash(), tableStatus, maxFills) + ",\"craftHash\":\"" + h + "\""));
    }

    /** Tells the server where the bot's delivery container is (an anchor for table positions), without touching craft state. */
    void anchor(int x, int y, int z, String dimension) throws Exception {
        ok(heartbeat("\"delivery\":{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + ",\"dimension\":\"" + dimension + "\"}"));
    }

    void setTable(int x, int y, int z) throws Exception {
        anchor(x, y, z, "minecraft:overworld");
        ok(post(owner, "/api/v1/craft/table", "{" + KEY + ",\"table\":{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + ",\"dimension\":\"minecraft:overworld\"}}"));
    }

    /** Everything a craft order needs: a table set on the website, a capable bot that has it. */
    void ready(int maxFills) throws Exception {
        setTable(118, 64, -30);
        capableBeat("ok", maxFills);
    }

    Res placeOrder(String lines) throws Exception {
        return post(owner, "/api/v1/orders", "{" + KEY + ",\"lines\":" + lines + "}");
    }

    static String craftLine(String id, int count) {
        return "{\"type\":\"craft\",\"key\":\"" + id + "\",\"count\":" + count + "}";
    }

    static String itemLine(String id, int count) {
        return "{\"type\":\"item\",\"key\":\"" + id + "\",\"count\":" + count + "}";
    }

    JsonElement availableItem(String key) throws Exception {
        return ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonObject("available").getAsJsonObject("items").get(key);
    }

    static JsonArray arr(JsonObject o, String f) {
        return o.getAsJsonArray(f);
    }
}
