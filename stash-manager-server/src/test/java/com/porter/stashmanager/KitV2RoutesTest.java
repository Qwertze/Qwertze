package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kit sorting v2 through the real routes (KIT-CONTRACT-v2): the heartbeat fields, the v2 report, type chests, overrides,
 * categories, templates, the chain and statuses on the page body, the Kit Builder list, forced streamers, and kit orders.
 */
class KitV2RoutesTest {
    static final String OWNER_PW = "owner-password-123";
    static final String PW = "temporary-pass-1";
    static final String V1_EMPTY = "cf9dcdb21f6fc7551bde30826e49358bd74d06ef";
    static final String V2_EMPTY = "ae46bc461e485b4ad61f4cf81983e22c21d070d0";
    static final String A = "10,64,10", B = "20,64,20", C = "30,64,30", D = "40,64,40";
    static final String KEY = "\"server\":\"s\",\"stash\":\"a\"";
    static final String SCORE = "\"score\":{\"total\":10,\"name\":2,\"mix\":3,\"full\":1,\"op\":4}";
    static final String CAP = "\"kitOrders\":{\"v\":1,\"enabled\":true,\"restock\":true,\"craft\":false}";

    @TempDir
    Path data;
    HttpServer server;
    String base;
    byte[] k;
    String owner;
    IndexStore store;
    OrderStore orders;
    /** The clock of the order book and the kit reports (tests move it). */
    final java.util.concurrent.atomic.AtomicLong clk = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    boolean beat;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void fastHashes() {
        Auth.iterations = 1000;
    }

    @AfterAll
    static void realHashes() {
        Auth.iterations = Auth.ITERATIONS;
    }

    void start() throws Exception {
        Config config = Config.load(data);
        config.setDashboardPassword(Auth.hash(OWNER_PW));
        k = Signer.deriveKey(config.apiKey());
        store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        orders = new OrderStore(data, store);
        orders.clock(clk::get);
        orders.load();
        Owners owners = new Owners(data);
        owners.load();
        IconService icons = new IconService(data, id -> null, v -> false, new GameIcons(data.resolve("icons"), null, false), false);
        SessionStore sessions = new SessionStore(data);
        sessions.load();
        Users users = new Users(data);
        users.load();
        AuditLog audit = new AuditLog(data);
        BotHub hub = new BotHub();
        StashManagerServer.Routes r = StashManagerServer.routes(config, store, orders, owners, icons,
            new LinkState(config.keyState().generation()), List.of(), sessions, users, audit, hub, new LoginLimiter(), new PlanStore(),
            new ViewerHub(hub, audit), RecipeSource.NONE, new CraftBots(clk::get), null, new KitReports(clk::get));
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

    record Res(int status, String body) {
        JsonObject obj() {
            return JsonParser.parseString(body).getAsJsonObject();
        }

        String error() {
            return obj().get("error").getAsString();
        }
    }

    Res botPost(String path, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        long ts = System.currentTimeMillis();
        byte[] n = new byte[16];
        new java.security.SecureRandom().nextBytes(n);
        String nonce = HexFormat.of().formatHex(n);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
            .header(Signer.REQUEST_HEADER, "v1 - " + ts + " " + nonce + " " + Signer.requestSig(k, "POST", path, ts, nonce, b))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(b)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    Res get(String token, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (token != null) b.header("Cookie", SessionStore.COOKIE + "=" + token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    Res post(String token, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
            .header("X-Stash-Manager", "1").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Cookie", SessionStore.COOKIE + "=" + token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
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

    Res heartbeat(String extra) throws Exception {
        return botPost("/api/v1/heartbeat", "{\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\"" + (extra == null ? "" : "," + extra) + "}");
    }

    // ---------- bodies ----------

    static String cont(String id, int free, boolean pickable) {
        return "{\"id\":\"" + id + "\",\"label\":null,\"size\":54,\"free\":" + free + ",\"pickable\":" + pickable + ",\"readAt\":1767225500000}";
    }

    static String why(String code, String type, String template) {
        boolean kit = KitReports.KIT_CODES.contains(code);
        return "{\"code\":\"" + code + "\",\"type\":" + (type == null ? "null" : "\"" + type + "\"") + ",\"slots\":10,\"covered\":" + (kit ? 10 : 3)
            + ",\"kinds\":4,\"items\":[\"minecraft:totem_of_undying\"],\"words\":[]" + (template == null ? "" : ",\"template\":\"" + template + "\"") + "}";
    }

    static String typed(String type, int count, String code, String template) {
        return "{\"type\":\"" + type + "\",\"count\":" + count + ",\"why\":" + why(code, type, template) + (template == null ? "" : ",\"template\":\"" + template + "\"") + "}";
    }

    static String kit(String key, String container, int count, String types) {
        return "{\"key\":" + (key == null ? "null" : "\"" + key + "\"") + ",\"name\":" + (key == null ? "null" : "\"" + key + "\"") + ",\"container\":\"" + container
            + "\",\"count\":" + count + "," + SCORE + ",\"minTotal\":8" + (types == null ? "" : ",\"types\":[" + types + "]") + "}";
    }

    /** A v2 report; extra is appended to the top level (others, templates, restock). */
    static String report(char hash, String containers, String kits, String carried, String extra) {
        return "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\",\"hash\":\"" + String.valueOf(hash).repeat(40) + "\",\"readAt\":1767225600000,"
            + "\"classifier\":{\"version\":2,\"table\":\"" + KitTypes.FINGERPRINT + "\"},"
            + "\"containers\":[" + containers + "],\"kits\":[" + kits + "],\"carried\":[" + carried + "]" + (extra == null ? "" : "," + extra) + "}";
    }

    void send(String body) throws Exception {
        if (!beat) heartbeat(null);
        beat = true;
        Res r = botPost("/api/v1/kits/report", body);
        assertEquals(200, r.status, r.body);
        // The bot's next heartbeat carries the hash of the report it holds (what makes the report current).
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"hash\":\"([0-9a-f]{40})\"").matcher(body);
        if (m.find()) heartbeat("\"kitsReportHash\":\"" + m.group(1) + "\"");
    }

    JsonObject view() throws Exception {
        return ok(get(owner, "/api/v1/kits?server=s&stash=a"));
    }

    JsonObject row(JsonObject v, String key, String type) {
        for (JsonElement e : v.getAsJsonArray("rows")) {
            JsonObject r = e.getAsJsonObject();
            boolean k = key == null ? r.get("key").isJsonNull() : !r.get("key").isJsonNull() && r.get("key").getAsString().equals(key);
            boolean t = type == null ? r.get("type").isJsonNull() : !r.get("type").isJsonNull() && r.get("type").getAsString().equals(type);
            if (k && t) return r;
        }
        return null;
    }

    JsonObject type(JsonObject v, String id) {
        for (JsonElement e : v.getAsJsonArray("types")) if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
        return null;
    }

    // ---------- heartbeat ----------

    @Test
    void heartbeatKitsHash2() throws Exception {
        start();
        // A v1 bot: kitsHash2 is in the reply, kits only when its v1 hash differs (and then with a types object, which it ignores).
        JsonObject o = ok(heartbeat("\"kitsHash\":\"" + V1_EMPTY + "\""));
        assertEquals(V1_EMPTY, o.get("kitsHash").getAsString());
        assertEquals(V2_EMPTY, o.get("kitsHash2").getAsString());
        assertFalse(o.has("kits"));
        // A v2 bot with no cache gets everything, including types.
        o = ok(heartbeat("\"kitsHash\":null,\"kitsHash2\":null"));
        assertEquals(V2_EMPTY, o.getAsJsonObject("kits").getAsJsonObject("types").get("hash").getAsString());
        assertEquals(0, o.getAsJsonObject("kits").getAsJsonObject("types").getAsJsonArray("templates").size());
        // The same v2 hash: nothing sent; a v1 hash that differs does not matter when kitsHash2 is there.
        assertFalse(ok(heartbeat("\"kitsHash\":\"" + "e".repeat(40) + "\",\"kitsHash2\":\"" + V2_EMPTY + "\"")).has("kits"));
        // A type chest changes the v2 hash only: a v1 bot sees nothing, a v2 bot the new object.
        send(report('1', cont(A, 3, true) + "," + cont(B, 3, true), "", "", null));
        ok(post(owner, "/api/v1/kits/typeChest", "{" + KEY + ",\"type\":\"pvp\",\"container\":\"" + A + "\"}"));
        assertFalse(ok(heartbeat("\"kitsHash\":\"" + V1_EMPTY + "\"")).has("kits"));
        o = ok(heartbeat("\"kitsHash\":\"" + V1_EMPTY + "\",\"kitsHash2\":\"" + V2_EMPTY + "\""));
        assertEquals(A, o.getAsJsonObject("kits").getAsJsonObject("types").getAsJsonObject("chests").get("pvp").getAsString());
        assertEquals(V1_EMPTY, o.getAsJsonObject("kits").get("hash").getAsString(), "the v1 hash never covers types");
        assertEquals(o.get("kitsHash2").getAsString(), o.getAsJsonObject("kits").getAsJsonObject("types").get("hash").getAsString());
        for (String bad : new String[]{"\"kitsHash2\":5", "\"kitsHash2\":\"abc\"", "\"kitsHash2\":{}", "\"kitOrders\":5", "\"kitOrders\":{}",
            "\"kitOrders\":{\"v\":0,\"enabled\":true}", "\"kitOrders\":{\"v\":1}", "\"kitOrders\":{\"v\":1,\"enabled\":1}",
            "\"kitOrders\":{\"v\":1,\"enabled\":true,\"restock\":\"yes\"}"}) {
            assertEquals(400, heartbeat(bad).status, bad);
        }
        // kitOrders needs the player.
        assertEquals(400, botPost("/api/v1/heartbeat", "{\"server\":\"s\",\"stash\":\"a\"," + CAP + "}").status);
    }

    @Test
    void theBotStateOnThePage() throws Exception {
        start();
        heartbeat("\"kitsHash\":\"" + V1_EMPTY + "\"");
        JsonObject bot = view().getAsJsonObject("bot");
        assertTrue(bot.get("supports").getAsBoolean());
        assertTrue(bot.get("synced").getAsBoolean());
        assertFalse(bot.get("types").getAsBoolean(), "a v1 bot");
        assertFalse(bot.get("typesSynced").getAsBoolean());
        heartbeat("\"kitsHash\":\"" + V1_EMPTY + "\",\"kitsHash2\":null");
        bot = view().getAsJsonObject("bot");
        assertTrue(bot.get("types").getAsBoolean());
        assertFalse(bot.get("typesSynced").getAsBoolean());
        heartbeat("\"kitsHash\":\"" + V1_EMPTY + "\",\"kitsHash2\":\"" + V2_EMPTY + "\"");
        assertTrue(view().getAsJsonObject("bot").get("typesSynced").getAsBoolean());
        JsonObject caps = ok(get(owner, "/api/v1/kits/templates?server=s&stash=a")).getAsJsonObject("capability");
        assertFalse(caps.get("kitOrders").getAsBoolean());
        heartbeat(CAP);
        caps = ok(get(owner, "/api/v1/kits/templates?server=s&stash=a")).getAsJsonObject("capability");
        assertTrue(caps.get("kitOrders").getAsBoolean());
        assertTrue(caps.get("restock").getAsBoolean());
        assertFalse(caps.get("craft").getAsBoolean());
        assertFalse(caps.get("import").getAsBoolean());
        heartbeat("\"kitOrders\":{\"v\":1,\"enabled\":true,\"restock\":false,\"craft\":true,\"import\":true}");
        caps = ok(get(owner, "/api/v1/kits/templates?server=s&stash=a")).getAsJsonObject("capability");
        assertTrue(caps.get("import").getAsBoolean());
        assertFalse(caps.get("craft").getAsBoolean(), "craft only counts when the bot also carries out craft orders");
        heartbeat("\"kitOrders\":null");
        assertFalse(ok(get(owner, "/api/v1/kits/templates?server=s&stash=a")).getAsJsonObject("capability").get("kitOrders").getAsBoolean());
    }

    // ---------- the v2 report ----------

    @Test
    void aV2ReportIsCheckedAndShown() throws Exception {
        start();
        heartbeat(null);
        String c = cont(A, 3, true) + "," + cont(B, 3, true);
        String good = report('1', c, kit("pvp kit", A, 3, typed("pvp", 2, "assortment", null) + "," + typed("building", 1, "named_assortment", null)),
            "{\"key\":null,\"name\":null,\"count\":1," + SCORE + ",\"types\":[" + typed("travel", 1, "assortment", null) + "]}",
            "\"others\":[{\"key\":\"etc\",\"name\":\"Etc\",\"container\":\"" + B + "\",\"count\":2,\"why\":" + why("mixed", "pvp", null) + "}],"
                + "\"templates\":[{\"id\":\"t-0000mini\",\"complete\":1,\"incomplete\":2,\"elsewhere\":0,\"carried\":0,\"missing\":[{\"key\":\"minecraft:end_crystal\",\"count\":12}]}],"
                + "\"restock\":{\"state\":\"idle\",\"template\":null,\"reason\":null}");
        assertEquals(200, botPost("/api/v1/kits/report", good).status);
        String[] bad = {
            good.replace("\"version\":2", "\"version\":1"),                                                  // classifier version
            good.replace(KitTypes.FINGERPRINT, "xyz"),
            good.replace("\"type\":\"pvp\",\"count\":2", "\"type\":\"pvp\",\"count\":1"),                      // counts must sum
            good.replace("\"type\":\"travel\",\"count\":1", "\"type\":\"nether\",\"count\":1"),                 // not a type
            good.replace("\"code\":\"assortment\",\"type\":\"travel\"", "\"code\":\"mixed\",\"type\":\"travel\""), // a kit with a not-kit reason
            good.replace("\"code\":\"mixed\"", "\"code\":\"assortment\""),                                      // an other with a kit reason
            good.replace("\"code\":\"mixed\"", "\"code\":\"bogus\""),
            good.replace("\"slots\":10", "\"slots\":28"),
            good.replace("\"items\":[\"minecraft:totem_of_undying\"]", "\"items\":[\"a\",\"b\",\"c\",\"d\"]"),
            good.replace("\"items\":[\"minecraft:totem_of_undying\"]", "\"items\":[\"Bad Item\"]"),
            good.replace("\"words\":[]", "\"words\":[\"Kit\"]"),
            good.replace("\"words\":[]", "\"words\":[\"a\",\"b\",\"c\",\"d\",\"e\"]"),
            good.replace("\"container\":\"" + B + "\",\"count\":2", "\"container\":\"99,64,99\",\"count\":2"),   // other in an unknown container
            good.replace("\"key\":\"etc\"", "\"key\":\"Etc\""),
            good.replace("\"id\":\"t-0000mini\"", "\"id\":\"mini\""),
            good.replace("\"complete\":1", "\"complete\":5000"),
            good.replace("\"state\":\"idle\"", "\"state\":\"sleeping\""),
            good.replace("\"reason\":null", "\"reason\":\"because\""),
        };
        for (String b : bad) assertEquals(400, botPost("/api/v1/kits/report", b).status, b);
        // The same other twice in one container, and a type twice in one kit.
        String dupOther = good.replace("\"others\":[", "\"others\":[{\"key\":\"etc\",\"name\":\"Etc\",\"container\":\"" + B + "\",\"count\":1,\"why\":" + why("mixed", "pvp", null) + "},");
        assertEquals(400, botPost("/api/v1/kits/report", dupOther).status);
        // A custom category is a valid type once the stash has it.
        ok(post(owner, "/api/v1/kits/category", "{" + KEY + ",\"name\":\"Shop\"}"));
        String custom = report('2', c, kit("shop kit", A, 1, typed("c:shop", 1, "override", null)), "", null);
        assertEquals(200, botPost("/api/v1/kits/report", custom).status);
        assertEquals(400, botPost("/api/v1/kits/report", custom.replace("c:shop", "c:other")).status);
        // An old (v1) report is still taken: no types, no classifier.
        assertEquals(200, botPost("/api/v1/kits/report", "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\",\"hash\":\"" + "3".repeat(40)
            + "\",\"readAt\":1767225600000,\"containers\":[" + c + "],\"kits\":[" + kit("old kit", A, 1, null) + "],\"carried\":[]}").status);
        JsonObject v = view();
        assertTrue(v.getAsJsonObject("report").get("classifier").isJsonNull());
        assertNotNull(row(v, "old kit", null));
        assertTrue(row(v, "old kit", null).get("why").isJsonNull());
    }

    @Test
    void rowsGroupByTypeWithTheChainAndStatus() throws Exception {
        start();
        heartbeat(null);
        String c = cont(A, 3, true) + "," + cont(B, 0, true) + "," + cont(C, 3, true) + "," + cont(D, 3, false);
        send(report('1', c,
            kit("pvp kit", A, 3, typed("pvp", 2, "assortment", null) + "," + typed("building", 1, "named_assortment", null)) + ","
                + kit(null, C, 1, typed("travel", 1, "assortment", null)) + ","
                + kit("mini crystal", B, 2, typed("pvp", 2, "template", "t-0000mini")),
            "{\"key\":\"red kit\",\"name\":\"Red Kit\",\"count\":1," + SCORE + ",\"types\":[" + typed("redstone", 1, "assortment", null) + "]}",
            "\"others\":[{\"key\":\"etc\",\"name\":\"Etc\",\"container\":\"" + C + "\",\"count\":2,\"why\":" + why("mixed", "pvp", null) + "}]"));
        ok(post(owner, "/api/v1/kits/chest", "{" + KEY + ",\"container\":\"" + C + "\"}"));
        ok(post(owner, "/api/v1/kits/typeChest", "{" + KEY + ",\"type\":\"pvp\",\"container\":\"" + B + "\"}"));
        ok(post(owner, "/api/v1/kits/typeChest", "{" + KEY + ",\"type\":\"travel\",\"container\":\"" + A + "\"}"));
        ok(post(owner, "/api/v1/kits/rule", "{" + KEY + ",\"key\":\"pvp kit\",\"container\":\"" + A + "\"}"));
        JsonObject v = view();
        // One row per (key, type): the same name in two types is two rows.
        JsonObject pvp = row(v, "pvp kit", "pvp"), bld = row(v, "pvp kit", "building");
        assertEquals(2, pvp.get("count").getAsInt());
        assertEquals(1, bld.get("count").getAsInt());
        assertEquals("auto", pvp.get("typeSource").getAsString());
        assertEquals("assortment", pvp.getAsJsonObject("why").get("code").getAsString());
        assertEquals("named_assortment", bld.getAsJsonObject("why").get("code").getAsString());
        // pvp kit, pvp: rule A (ok), pvp chest B (full), kits chest C (ok); the kits sit in A: home.
        JsonArray chain = pvp.getAsJsonArray("chain");
        assertEquals(List.of("rule", "typeChest", "kitsChest"), List.of(chain.get(0).getAsJsonObject().get("source").getAsString(),
            chain.get(1).getAsJsonObject().get("source").getAsString(), chain.get(2).getAsJsonObject().get("source").getAsString()));
        assertEquals(List.of("ok", "full", "ok"), List.of(chain.get(0).getAsJsonObject().get("status").getAsString(),
            chain.get(1).getAsJsonObject().get("status").getAsString(), chain.get(2).getAsJsonObject().get("status").getAsString()));
        assertEquals(A, pvp.get("target").getAsString());
        assertEquals("rule", pvp.get("targetSource").getAsString());
        assertFalse(pvp.get("fallback").getAsBoolean());
        assertEquals("home", pvp.get("status").getAsString(), "the kits are in A, the first entry of the chain");
        assertEquals("home", bld.get("status").getAsString());
        // The template kit sits in B, the (full) pvp chest, which is the first entry of its chain: home, typeSource template.
        JsonObject mini = row(v, "mini crystal", "pvp");
        assertEquals("template", mini.get("typeSource").getAsString());
        assertEquals("t-0000mini", mini.get("template").getAsString());
        assertEquals("home", mini.get("status").getAsString());
        // The unnamed travel kit sits in the kits chest C, below the travel chest A (ok): pending, to A, no fallback.
        JsonObject travel = row(v, null, "travel");
        assertEquals("pending", travel.get("status").getAsString());
        assertEquals(A, travel.get("target").getAsString());
        assertEquals("typeChest", travel.get("targetSource").getAsString());
        assertFalse(travel.get("fallback").getAsBoolean());
        // The carried redstone kit has only the kits chest: pending, to C.
        JsonObject red = row(v, "red kit", "redstone");
        assertEquals(1, red.get("carried").getAsInt());
        assertEquals(C, red.get("target").getAsString());
        assertEquals("kitsChest", red.get("targetSource").getAsString());
        // The pvp chest B is full: a carried pvp kit falls back to C and says so.
        send(report('2', c, kit("pvp kit", A, 1, typed("pvp", 1, "assortment", null)),
            "{\"key\":\"blue kit\",\"name\":\"Blue Kit\",\"count\":1," + SCORE + ",\"types\":[" + typed("pvp", 1, "assortment", null) + "]}", null));
        JsonObject blue = row(view(), "blue kit", "pvp");
        assertEquals(C, blue.get("target").getAsString());
        assertEquals("kitsChest", blue.get("targetSource").getAsString());
        assertTrue(blue.get("fallback").getAsBoolean());
        assertEquals("full", blue.getAsJsonArray("chain").get(0).getAsJsonObject().get("status").getAsString());
        assertEquals("pending", blue.get("status").getAsString());
        // Every chain container missing: missing. A rule and nothing stored: absent. Nothing set: none.
        ok(post(owner, "/api/v1/kits/typeChest", "{" + KEY + ",\"type\":\"pvp\",\"container\":null}"));
        ok(post(owner, "/api/v1/kits/chest", "{" + KEY + ",\"container\":null}"));
        v = view();
        assertEquals("none", row(v, "blue kit", "pvp").get("status").getAsString());
        // The per-category counts.
        assertEquals(2, type(view(), "pvp").get("count").getAsInt());
    }

    // ---------- type chests and overrides ----------

    @Test
    void typeChestAndTreatAsRoutes() throws Exception {
        start();
        heartbeat(null);
        send(report('1', cont(A, 3, true) + "," + cont(B, 3, false), "", "", null));
        JsonObject v = ok(post(owner, "/api/v1/kits/typeChest", "{" + KEY + ",\"type\":\"pvp\",\"container\":\"" + A + "\"}"));
        assertEquals(7, v.getAsJsonArray("types").size());
        assertEquals(A, type(v, "pvp").get("chest").getAsString());
        assertEquals("ok", type(v, "pvp").get("chestStatus").getAsString());
        assertEquals("unset", type(v, "travel").get("chestStatus").getAsString());
        assertEquals(List.of("pvp", "travel", "redstone", "farming", "building", "starter", "other"),
            v.getAsJsonArray("types").asList().stream().map(e -> e.getAsJsonObject().get("id").getAsString()).toList());
        // Refusals: a container that can't take kits, an unknown type, a bad id, then clearing.
        assertEquals(400, post(owner, "/api/v1/kits/typeChest", "{" + KEY + ",\"type\":\"pvp\",\"container\":\"" + B + "\"}").status);
        assertEquals(400, post(owner, "/api/v1/kits/typeChest", "{" + KEY + ",\"type\":\"nether\",\"container\":\"" + A + "\"}").status);
        assertEquals(400, post(owner, "/api/v1/kits/typeChest", "{" + KEY + ",\"container\":\"" + A + "\"}").status);
        assertEquals(400, post(owner, "/api/v1/kits/typeChest", "{" + KEY + ",\"type\":\"pvp\",\"container\":\"nope\"}").status);
        v = ok(post(owner, "/api/v1/kits/typeChest", "{" + KEY + ",\"type\":\"pvp\",\"container\":null}"));
        assertEquals("unset", type(v, "pvp").get("chestStatus").getAsString());
        // treatAs: a type, none, a bad key, a bad type, removal, and the cap.
        v = ok(post(owner, "/api/v1/kits/treatAs", "{" + KEY + ",\"key\":\"etc\",\"type\":\"building\"}"));
        assertEquals("building", v.getAsJsonArray("treatAs").get(0).getAsJsonObject().get("type").getAsString());
        ok(post(owner, "/api/v1/kits/treatAs", "{" + KEY + ",\"key\":\"crystal kit\",\"type\":\"none\"}"));
        assertEquals(400, post(owner, "/api/v1/kits/treatAs", "{" + KEY + ",\"key\":\"Crystal Kit\",\"type\":\"none\"}").status);
        assertEquals(400, post(owner, "/api/v1/kits/treatAs", "{" + KEY + ",\"key\":\"x\",\"type\":\"nether\"}").status);
        v = ok(post(owner, "/api/v1/kits/treatAs", "{" + KEY + ",\"key\":\"etc\",\"type\":null}"));
        assertEquals(1, v.getAsJsonArray("treatAs").size());
        // The "none" override of a name the stash doesn't have is an absent row in others.
        JsonObject other = v.getAsJsonArray("others").get(0).getAsJsonObject();
        assertEquals("crystal kit", other.get("key").getAsString());
        assertEquals("absent", other.get("status").getAsString());
        // Audit.
        String audit = get(owner, "/api/v1/audit").body;
        assertTrue(audit.contains("kits.typeChest") && audit.contains("kits.treatAs"), audit);
    }

    @Test
    void permissionsStreamersAndCsrf() throws Exception {
        start();
        heartbeat(null);
        send(report('1', cont(A, 3, true), "", "", null));
        String viewer = userToken("vic", "{\"view\":true}");
        String editor = userToken("edi", "{\"view\":true,\"settings\":true}");
        String forced = userToken("fay", "{\"view\":true,\"settings\":true,\"streamer\":true}");
        String[][] writes = {{"typeChest", "\"type\":\"pvp\",\"container\":\"" + A + "\""}, {"treatAs", "\"key\":\"etc\",\"type\":\"other\""},
            {"template", "\"template\":" + KitV2Test.body("T", "pvp", "minecraft:bread", "minecraft:apple")}, {"template/delete", "\"id\":\"t-00000000\""},
            {"category", "\"name\":\"Shop\""}, {"category/delete", "\"id\":\"c:shop\""}};
        for (String[] w : writes) {
            String path = "/api/v1/kits/" + w[0], body = "{" + KEY + "," + w[1] + "}";
            assertEquals(403, post(viewer, path, body).status, w[0] + " viewer");
            Res f = post(forced, path, body);
            assertEquals(403, f.status, w[0] + " forced streamer");
            assertEquals("streamer", f.obj().get("permission").getAsString());
            // CSRF: no header, no write.
            HttpResponse<String> noHeader = http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .header("Cookie", SessionStore.COOKIE + "=" + editor).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, noHeader.statusCode(), w[0] + " csrf");
        }
        assertEquals(200, post(editor, "/api/v1/kits/typeChest", "{" + KEY + ",\"type\":\"pvp\",\"container\":\"" + A + "\"}").status);
        assertEquals(403, get(forced, "/api/v1/kits?server=s&stash=a").status);
        // The Kit Builder list: a viewer sees it; a forced streamer gets it with every container id hidden.
        ok(post(editor, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + withChest(KitV2Test.body("Crystal", "pvp", "minecraft:totem_of_undying", "minecraft:end_crystal"), A) + "}"));
        JsonObject asViewer = ok(get(viewer, "/api/v1/kits/templates?server=s&stash=a"));
        assertEquals(A, asViewer.getAsJsonArray("templates").get(0).getAsJsonObject().get("chest").getAsString());
        String sid = JsonParser.parseString(get(forced, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().get("server").getAsString();
        Res asForced = get(forced, "/api/v1/kits/templates?server=" + sid + "&stash=a");
        assertEquals(200, asForced.status);
        assertFalse(asForced.body.contains(A), asForced.body);
        assertEquals("hidden", asForced.obj().getAsJsonArray("templates").get(0).getAsJsonObject().get("chest").getAsString());
        assertEquals("hidden", asForced.obj().getAsJsonArray("templates").get(0).getAsJsonObject().get("effectiveChest").getAsString());
    }

    static JsonObject withChest(JsonObject t, String chest) {
        t.addProperty("chest", chest);
        return t;
    }

    // ---------- templates and categories ----------

    String indexWith(String items) {
        return "{\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\",\"mcVersion\":\"1.21.4\",\"items\":[" + items + "],\"shulkers\":[]}";
    }

    static String item(String key, String id, long count, int max, String match) {
        return "{\"key\":\"" + key + "\",\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"count\":" + count + ",\"maxStack\":" + max
            + (match == null ? "" : ",\"match\":\"" + match + "\"") + "}";
    }

    void putIndex(String items) throws Exception {
        store.putIndex(JsonParser.parseString(indexWith(items)).getAsJsonObject());
    }

    @Test
    void templatesThroughTheRoutes() throws Exception {
        start();
        heartbeat(null);
        putIndex(item("minecraft:bread", "minecraft:bread", 200, 64, null) + "," + item("minecraft:bow#aa11bb22", "minecraft:bow", 2, 1, "minecraft:bow#cc33dd44")
            + "," + item("minecraft:totem_of_undying", "minecraft:totem_of_undying", 5, 64, null));
        send(report('1', cont(A, 3, true), "", "", null));
        JsonObject t = KitV2Test.body("Food and Bow", "farming", "minecraft:bread", "minecraft:bow#aa11bb22", "minecraft:totem_of_undying#*");
        t.getAsJsonArray("slots").get(0).getAsJsonObject().addProperty("count", 64);
        JsonObject saved = ok(post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + t + "}"));
        String id = saved.get("id").getAsString();
        assertTrue(id.matches("^t-[0-9a-f]{8}$"), id);
        JsonObject tpl = saved.getAsJsonArray("templates").get(0).getAsJsonObject();
        assertEquals(id, tpl.get("id").getAsString());
        // The server fills the slot's match key from the index; a #* slot's match is its key.
        assertEquals("minecraft:bow#cc33dd44", tpl.getAsJsonArray("slots").get(1).getAsJsonObject().get("match").getAsString());
        assertEquals("minecraft:totem_of_undying#*", tpl.getAsJsonArray("slots").get(2).getAsJsonObject().get("match").getAsString());
        assertTrue(tpl.getAsJsonArray("slots").get(1).getAsJsonObject().get("resolved").getAsBoolean());
        assertEquals(27, tpl.getAsJsonArray("slots").size());
        assertTrue(tpl.getAsJsonArray("slots").get(5).isJsonNull());
        assertEquals(9, saved.getAsJsonArray("presets").size());
        assertEquals(50, saved.getAsJsonObject("limits").get("maxTemplates").getAsInt());
        JsonObject twin = KitV2Test.body("Twin", "farming", "minecraft:totem_of_undying#*", "minecraft:bow#aa11bb22", "minecraft:bread");
        twin.getAsJsonArray("slots").get(2).getAsJsonObject().addProperty("count", 64);
        Res same = post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + twin + "}");
        assertEquals(400, same.status, same.body);
        assertEquals("same contents as Food and Bow", same.error());
        // Drift: the bow variant leaves the index; the slot is no longer resolved.
        putIndex(item("minecraft:bread", "minecraft:bread", 200, 64, null));
        tpl = ok(get(owner, "/api/v1/kits/templates?server=s&stash=a")).getAsJsonArray("templates").get(0).getAsJsonObject();
        assertFalse(tpl.getAsJsonArray("slots").get(1).getAsJsonObject().get("resolved").getAsBoolean());
        assertTrue(tpl.getAsJsonArray("slots").get(0).getAsJsonObject().get("resolved").getAsBoolean());
        // Refusals.
        Res variant = post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + KitV2Test.body("Gone", "pvp", "minecraft:bread", "minecraft:bow#00000000") + "}");
        assertEquals(400, variant.status);
        assertTrue(variant.error().contains("isn't in the stash index"), variant.error());
        assertEquals(400, post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + KitV2Test.body("One", "pvp", "minecraft:bread") + "}").status);
        assertEquals(400, post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + KitV2Test.body("Cat", "c:ghost", "minecraft:bread", "minecraft:apple") + "}").status);
        assertEquals(400, post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + withChest(KitV2Test.body("Chest", "pvp", "minecraft:bread", "minecraft:apple"), C) + "}").status,
            "a chest the report doesn't have");
        assertEquals(404, post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + KitV2Test.body("x", "pvp", "minecraft:bread", "minecraft:apple").toString().replace("{\"name\"", "{\"id\":\"t-0000none\",\"name\"") + "}").status);
        // Replace by id; the chain of a template with a chest of its own.
        JsonObject again = KitV2Test.body("Food and Bow 2", "farming", "minecraft:bread", "minecraft:apple");
        again.addProperty("id", id);
        again.addProperty("chest", A);
        again.addProperty("keep", 3);
        again.addProperty("color", "red");
        JsonObject replaced = ok(post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + again + "}"));
        tpl = replaced.getAsJsonArray("templates").get(0).getAsJsonObject();
        assertEquals(1, replaced.getAsJsonArray("templates").size());
        assertEquals("Food and Bow 2", tpl.get("name").getAsString());
        assertEquals(A, tpl.get("effectiveChest").getAsString());
        assertEquals("template", tpl.get("chestSource").getAsString());
        assertEquals("red", tpl.get("color").getAsString());
        assertEquals(400, post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + again.toString().replace("\"red\"", "\"pink-ish\"") + "}").status);
        assertEquals(400, post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + again.toString().replace("\"keep\":3", "\"keep\":28") + "}").status);
        // Over 16 KiB.
        assertEquals(413, post(owner, "/api/v1/kits/template", "{" + KEY + ",\"pad\":\"" + "x".repeat(17 * 1024) + "\"}").status);
        // The v2 hash in the heartbeat covers the template.
        String hash2 = ok(heartbeat("\"kitsHash2\":null")).getAsJsonObject("kits").getAsJsonObject("types").get("hash").getAsString();
        assertEquals(hash2, ok(heartbeat(null)).get("kitsHash2").getAsString());
        // Delete.
        JsonObject deleted = ok(post(owner, "/api/v1/kits/template/delete", "{" + KEY + ",\"id\":\"" + id + "\"}"));
        assertEquals(0, deleted.getAsJsonArray("templates").size());
        assertEquals(400, post(owner, "/api/v1/kits/template/delete", "{" + KEY + ",\"id\":\"nope\"}").status);
        String audit = get(owner, "/api/v1/audit").body;
        assertTrue(audit.contains("kits.template") && audit.contains("kits.template.delete"), audit);
    }

    @Test
    void categoriesAndTheirUse() throws Exception {
        start();
        heartbeat(null);
        send(report('1', cont(A, 3, true), "", "", null));
        JsonObject c = ok(post(owner, "/api/v1/kits/category", "{" + KEY + ",\"name\":\"Shop kits\"}"));
        assertEquals("c:shop-kits", c.get("id").getAsString());
        assertEquals("Shop kits", c.getAsJsonArray("categories").get(0).getAsJsonObject().get("name").getAsString());
        // It shows with the types, gets a chest, and falls back to the general kits chest.
        JsonObject v = ok(post(owner, "/api/v1/kits/typeChest", "{" + KEY + ",\"type\":\"c:shop-kits\",\"container\":\"" + A + "\"}"));
        assertEquals(8, v.getAsJsonArray("types").size());
        assertTrue(type(v, "c:shop-kits").get("custom").getAsBoolean());
        assertEquals(A, type(v, "c:shop-kits").get("chest").getAsString());
        // A template and an override use it: it can't be deleted.
        ok(post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + KitV2Test.body("Shop 1", "c:shop-kits", "minecraft:bread", "minecraft:apple") + "}"));
        ok(post(owner, "/api/v1/kits/treatAs", "{" + KEY + ",\"key\":\"mixed bag\",\"type\":\"c:shop-kits\"}"));
        Res used = post(owner, "/api/v1/kits/category/delete", "{" + KEY + ",\"id\":\"c:shop-kits\"}");
        assertEquals(409, used.status);
        assertEquals("used by 2 kits", used.error());
        // Rename, then free it and delete.
        c = ok(post(owner, "/api/v1/kits/category", "{" + KEY + ",\"id\":\"c:shop-kits\",\"name\":\"Shop\"}"));
        assertEquals("Shop", c.getAsJsonArray("categories").get(0).getAsJsonObject().get("name").getAsString());
        ok(post(owner, "/api/v1/kits/treatAs", "{" + KEY + ",\"key\":\"mixed bag\",\"type\":null}"));
        String tplId = ok(get(owner, "/api/v1/kits/templates?server=s&stash=a")).getAsJsonArray("templates").get(0).getAsJsonObject().get("id").getAsString();
        ok(post(owner, "/api/v1/kits/template/delete", "{" + KEY + ",\"id\":\"" + tplId + "\"}"));
        assertEquals(0, ok(post(owner, "/api/v1/kits/category/delete", "{" + KEY + ",\"id\":\"c:shop-kits\"}")).getAsJsonArray("categories").size());
        assertEquals(400, post(owner, "/api/v1/kits/category", "{" + KEY + ",\"name\":\"bad§\"}").status);
        assertEquals(400, post(owner, "/api/v1/kits/category", "{" + KEY + ",\"name\":\"\"}").status);
        assertEquals(404, post(owner, "/api/v1/kits/category", "{" + KEY + ",\"id\":\"c:ghost\",\"name\":\"x\"}").status);
        assertEquals(400, post(owner, "/api/v1/kits/category/delete", "{" + KEY + ",\"id\":\"pvp\"}").status);
    }

    // ---------- kit orders ----------

    /** A stash with bread, apples, shulkers, a template and a capable bot. Returns the template id. */
    String orderSetup(int shulkers) throws Exception {
        heartbeat(CAP);
        putIndex(item("minecraft:bread", "minecraft:bread", 100, 64, null) + "," + item("minecraft:apple", "minecraft:apple", 40, 64, null) + ","
            + item("minecraft:shulker_box", "minecraft:shulker_box", shulkers, 1, null));
        send(report('1', cont(A, 3, true), "", "", null));
        JsonObject t = KitV2Test.body("Snack Pack", "farming", "minecraft:bread", "minecraft:apple");
        t.getAsJsonArray("slots").get(0).getAsJsonObject().addProperty("count", 32);
        t.getAsJsonArray("slots").get(1).getAsJsonObject().addProperty("count", 16);
        return ok(post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + t + "}")).get("id").getAsString();
    }

    Res order(String id, int count) throws Exception {
        return post(owner, "/api/v1/orders", "{" + KEY + ",\"lines\":[{\"type\":\"kit\",\"key\":\"" + id + "\",\"count\":" + count + "}]}");
    }

    @Test
    void kitOrdersNeedACapableBot() throws Exception {
        start();
        heartbeat(null);
        putIndex(item("minecraft:bread", "minecraft:bread", 100, 64, null) + "," + item("minecraft:apple", "minecraft:apple", 40, 64, null));
        send(report('1', cont(A, 3, true), "", "", null));
        String id = ok(post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + KitV2Test.body("Snack", "farming", "minecraft:bread", "minecraft:apple") + "}")).get("id").getAsString();
        Res none = order(id, 1);
        assertEquals(409, none.status);
        assertEquals("no_bot", none.obj().get("kit").getAsString());
        heartbeat("\"kitOrders\":{\"v\":1,\"enabled\":false,\"restock\":false,\"craft\":false}");
        Res off = order(id, 1);
        assertEquals(409, off.status);
        assertEquals("disabled", off.obj().get("kit").getAsString());
        heartbeat(CAP);
        // Line checks.
        assertEquals(400, order("nope", 1).status);
        assertEquals(400, order(id, 0).status);
        assertEquals(400, order(id, 55).status);
        Res ghost = order("t-deadbeef", 1);
        assertEquals(400, ghost.status);
        assertEquals("deleted", ghost.obj().get("kit").getAsString());
    }

    @Test
    void aKitOrderReservesItsItemsAndKeepsASnapshot() throws Exception {
        start();
        String id = orderSetup(5);
        JsonObject o = ok(order(id, 2)).getAsJsonObject("order");
        JsonObject kit = o.getAsJsonObject("kit");
        assertEquals(1, kit.get("v").getAsInt());
        JsonObject line = kit.getAsJsonArray("lines").get(0).getAsJsonObject();
        assertEquals(2, line.get("count").getAsInt());
        assertEquals(0, line.get("ready").getAsInt());
        assertEquals(2, line.get("build").getAsInt());
        JsonObject snap = line.getAsJsonObject("template");
        assertEquals("Snack Pack", snap.get("name").getAsString());
        assertEquals(27, snap.getAsJsonArray("slots").size());
        assertFalse(snap.has("chest"), "no positions in a snapshot");
        // take is by match key: 2 kits x 32 bread and x 16 apples.
        JsonArray take = kit.getAsJsonArray("take");
        assertEquals(2, take.size());
        assertEquals("minecraft:apple", take.get(0).getAsJsonObject().get("match").getAsString());
        assertEquals(32, take.get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals(64, take.get(1).getAsJsonObject().get("count").getAsInt());
        // The reservation shows in the available counts: 100 - 64 bread and 40 - 32 apples.
        JsonObject avail = ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonObject("available").getAsJsonObject("items");
        assertEquals(36, avail.get("minecraft:bread").getAsInt());
        assertEquals(8, avail.get("minecraft:apple").getAsInt());
        // A second order for the same kit: only 8 apples are left, 16 are needed.
        Res second = order(id, 1);
        assertEquals(400, second.status, second.body);
        assertTrue(second.error().contains("complete Snack Pack kits can be made"), second.error());
        assertEquals("minecraft:apple", second.obj().getAsJsonArray("short").get(0).getAsJsonObject().get("key").getAsString());
        // Cancelling releases it.
        ok(post(owner, "/api/v1/orders/" + o.get("id").getAsString() + "/cancel", "{}"));
        ok(order(id, 1));
    }

    @Test
    void kitShortfallsAndTheShulkerSupply() throws Exception {
        start();
        String id = orderSetup(0);
        Res r = order(id, 1);
        assertEquals(400, r.status, r.body);
        JsonArray shorts = r.obj().getAsJsonArray("short");
        assertEquals("minecraft:shulker_box", shorts.get(0).getAsJsonObject().get("key").getAsString());
        assertEquals(1, shorts.get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals("only 0 complete Snack Pack kits can be made: short Shulker Box ×1", r.error());
        assertEquals(0, r.obj().get("line").getAsInt());
        // Items: 40 apples make two kits of 16, not three.
        putIndex(item("minecraft:bread", "minecraft:bread", 100, 64, null) + "," + item("minecraft:apple", "minecraft:apple", 40, 64, null) + ","
            + item("minecraft:shulker_box", "minecraft:shulker_box", 5, 1, null));
        Res apples = order(id, 3);
        assertEquals(400, apples.status, apples.body);
        assertEquals("only 2 complete Snack Pack kits can be made: short Apple ×8", apples.error());
        assertEquals(8, apples.obj().getAsJsonArray("short").get(0).getAsJsonObject().get("count").getAsInt());
        // Nothing was stored by the refused orders.
        assertEquals(0, ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").size());
    }

    @Test
    void readyCopiesAndIncompleteCopiesCountTowardsAnOrder() throws Exception {
        start();
        String id = orderSetup(5);
        // The bot reports 3 complete copies (2 in the chain target, 1 elsewhere) and 1 incomplete copy missing 8 apples.
        send(report('2', cont(A, 3, true), "", "", "\"templates\":[{\"id\":\"" + id + "\",\"complete\":2,\"incomplete\":1,\"elsewhere\":1,\"carried\":0,"
            + "\"missing\":[{\"key\":\"minecraft:apple\",\"count\":8}]}]"));
        JsonObject line = ok(order(id, 2)).getAsJsonObject("order").getAsJsonObject("kit").getAsJsonArray("lines").get(0).getAsJsonObject();
        assertEquals(2, line.get("ready").getAsInt());
        assertEquals(0, line.get("build").getAsInt());
        // A second order claims the third copy only: 1 ready, 1 to build, and the incomplete copy supplies 8 apples and all 32 bread.
        JsonObject o2 = ok(order(id, 2)).getAsJsonObject("order").getAsJsonObject("kit");
        JsonObject l2 = o2.getAsJsonArray("lines").get(0).getAsJsonObject();
        assertEquals(1, l2.get("ready").getAsInt());
        assertEquals(1, l2.get("build").getAsInt());
        JsonArray take = o2.getAsJsonArray("take");
        assertEquals(1, take.size(), take.toString());
        assertEquals("minecraft:apple", take.get(0).getAsJsonObject().get("match").getAsString());
        assertEquals(8, take.get(0).getAsJsonObject().get("count").getAsInt());
    }

    @Test
    void theClaimFlagAndTheHeartbeatCountOfKitOrders() throws Exception {
        start();
        String id = orderSetup(5);
        String orderId = ok(order(id, 1)).getAsJsonObject("order").get("id").getAsString();
        // A bot that doesn't carry kit orders is not told about them, and is not given them.
        JsonObject hb = ok(heartbeat("\"kitOrders\":null"));
        assertEquals(0, hb.getAsJsonObject("orders").get("pending").getAsInt());
        assertEquals(204, botPost("/api/v1/orders/claim", "{" + KEY + ",\"player\":\"Bob\"}").status);
        hb = ok(heartbeat(CAP));
        assertEquals(1, hb.getAsJsonObject("orders").get("pending").getAsInt());
        Res claimed = botPost("/api/v1/orders/claim", "{" + KEY + ",\"player\":\"Bob\",\"kitOrders\":1}");
        assertEquals(200, claimed.status, claimed.body);
        assertEquals(orderId, claimed.obj().getAsJsonObject("order").get("id").getAsString());
        // Claiming the held order without the flag does not fail it at once (a bot that restarted needs time): it stays held.
        assertEquals(204, botPost("/api/v1/orders/claim", "{" + KEY + ",\"player\":\"Bob\"}").status);
        JsonObject listed = ok(get(owner, "/api/v1/orders?server=s&stash=a"));
        assertEquals("accepted", listed.getAsJsonArray("orders").get(0).getAsJsonObject().get("status").getAsString());
        assertEquals(68, listed.getAsJsonObject("available").getAsJsonObject("items").get("minecraft:bread").getAsInt(), "still reserved: one kit takes 32");
    }

    @Test
    void resultKitIsValidated() throws Exception {
        start();
        String id = orderSetup(5);
        String orderId = ok(order(id, 2)).getAsJsonObject("order").get("id").getAsString();
        assertEquals(200, botPost("/api/v1/orders/claim", "{" + KEY + ",\"player\":\"Bob\",\"kitOrders\":1}").status);
        String path = "/api/v1/orders/" + orderId + "/status";
        String good = "{\"template\":\"" + id + "\",\"kits\":1,\"reason\":\"items_short\",\"stored\":\"incomplete\",\"items\":[{\"match\":\"minecraft:apple\",\"key\":\"minecraft:apple\",\"count\":16}]}";
        String made = "{\"template\":\"" + id + "\",\"name\":\"Snack Pack\",\"wanted\":2,\"ready\":0,\"built\":1,\"delivered\":1}";
        String[] bad = {
            "\"kit\":5", "\"kit\":{\"made\":{}}", "\"kit\":{\"short\":[{\"template\":\"x\",\"kits\":1,\"reason\":\"items_short\"}]}",
            "\"kit\":{\"short\":[" + good.replace("items_short", "bored") + "]}",
            "\"kit\":{\"short\":[" + good.replace("incomplete", "lost") + "]}",
            "\"kit\":{\"short\":[" + good.replace("\"count\":16", "\"count\":-1") + "]}",
            "\"kit\":{\"short\":[" + good.replace("minecraft:apple\",\"count", "Not An Item\",\"count") + "]}",
            "\"kit\":{\"made\":[" + made.replace("\"built\":1", "\"built\":\"one\"") + "]}",
            "\"kit\":{\"made\":[{\"name\":\"x\"}]}",
            "\"kit\":{\"notes\":[\"Colour Substituted\"]}",
        };
        for (String b : bad) {
            assertEquals(400, botPost(path, "{\"status\":\"in_progress\",\"result\":{\"shulkers\":0,\"lines\":[]," + b + "}}").status, b);
        }
        Res okRes = botPost(path, "{\"status\":\"delivered\",\"message\":\"1 kit(s) not made: items_short\",\"result\":{\"shulkers\":1,\"lines\":[],"
            + "\"kit\":{\"made\":[" + made + "],\"short\":[" + good + "],\"notes\":[\"colour_substituted\"]}}}");
        assertEquals(200, okRes.status, okRes.body);
        JsonObject kit = okRes.obj().getAsJsonObject("order").getAsJsonObject("result").getAsJsonObject("kit");
        assertEquals(1, kit.getAsJsonArray("made").size());
        assertEquals("incomplete", kit.getAsJsonArray("short").get(0).getAsJsonObject().get("stored").getAsString());
        assertEquals("colour_substituted", kit.getAsJsonArray("notes").get(0).getAsString());
    }

    @Test
    void aRetryReplansAgainstTheCurrentTemplate() throws Exception {
        start();
        String id = orderSetup(5);
        JsonObject o = ok(order(id, 1)).getAsJsonObject("order");
        ok(post(owner, "/api/v1/orders/" + o.get("id").getAsString() + "/cancel", "{}"));
        // Edit the template (a bigger bread slot), then retry: the new order has the new snapshot.
        JsonObject t = KitV2Test.body("Snack Pack", "farming", "minecraft:bread", "minecraft:apple");
        t.addProperty("id", id);
        t.getAsJsonArray("slots").get(0).getAsJsonObject().addProperty("count", 10);
        ok(post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + t + "}"));
        JsonObject again = ok(post(owner, "/api/v1/orders/" + o.get("id").getAsString() + "/retry", "{}")).getAsJsonObject("order");
        assertEquals(10, again.getAsJsonObject("kit").getAsJsonArray("lines").get(0).getAsJsonObject().getAsJsonObject("template")
            .getAsJsonArray("slots").get(0).getAsJsonObject().get("count").getAsInt());
        // A deleted template: the retry of a cancelled order is a 409.
        ok(post(owner, "/api/v1/orders/" + again.get("id").getAsString() + "/cancel", "{}"));
        ok(post(owner, "/api/v1/kits/template/delete", "{" + KEY + ",\"id\":\"" + id + "\"}"));
        Res gone = post(owner, "/api/v1/orders/" + again.get("id").getAsString() + "/retry", "{}");
        assertEquals(409, gone.status, gone.body);
        // The old order stays and can be retried later (the failed retry did not use it up).
        assertNotNull(gone.obj().get("error"));
    }

    @Test
    void forcedStreamersSeeKitOrdersWithoutPositionsOrBotText() throws Exception {
        start();
        String id = orderSetup(5);
        String forced = userToken("fay", "{\"view\":true,\"order\":true,\"streamer\":true}");
        String sid = JsonParser.parseString(get(forced, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().get("server").getAsString();
        Res placed = post(forced, "/api/v1/orders", "{\"server\":\"" + sid + "\",\"stash\":\"a\",\"lines\":[{\"type\":\"kit\",\"key\":\"" + id + "\",\"count\":1}]}");
        assertEquals(200, placed.status, placed.body);
        String orderId = placed.obj().getAsJsonObject("order").get("id").getAsString();
        assertEquals(200, botPost("/api/v1/orders/claim", "{" + KEY + ",\"player\":\"Bob\",\"kitOrders\":1}").status);
        botPost("/api/v1/orders/" + orderId + "/status", "{\"status\":\"delivered\",\"result\":{\"shulkers\":1,\"lines\":[],\"kit\":{\"made\":[{\"template\":\"" + id
            + "\",\"name\":\"at 10 64 10\",\"wanted\":1,\"ready\":0,\"built\":1,\"delivered\":1}],\"short\":[],\"notes\":[]}}}");
        Res list = get(forced, "/api/v1/orders?server=" + sid + "&stash=a");
        assertEquals(200, list.status);
        assertFalse(list.body.contains("at 10 64 10"), list.body);
        assertFalse(list.body.contains(A), list.body);
        JsonObject made = list.obj().getAsJsonArray("orders").get(0).getAsJsonObject().getAsJsonObject("result").getAsJsonObject("kit")
            .getAsJsonArray("made").get(0).getAsJsonObject();
        assertEquals("(hidden)", made.get("name").getAsString());
    }

    @Test
    void theV1HashOfTheStashStaysPutWhenOnlyV2Changes() throws Exception {
        start();
        heartbeat(null);
        send(report('1', cont(A, 3, true), "", "", null));
        JsonObject before = view();
        ok(post(owner, "/api/v1/kits/typeChest", "{" + KEY + ",\"type\":\"pvp\",\"container\":\"" + A + "\"}"));
        ok(post(owner, "/api/v1/kits/treatAs", "{" + KEY + ",\"key\":\"etc\",\"type\":\"other\"}"));
        JsonObject after = view();
        assertEquals(before.get("hash").getAsString(), after.get("hash").getAsString());
        assertEquals(2, after.get("rev").getAsInt());
        assertFalse(before.get("typesHash").getAsString().equals(after.get("typesHash").getAsString()));
        // GET /api/v1/stashes counts full or missing type chests as flagged.
        send(report('3', cont(B, 3, true), "", "", null));
        JsonObject kits = JsonParser.parseString(get(owner, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("kits");
        assertEquals(1, kits.get("flagged").getAsInt(), "the pvp chest is gone from the report");
    }

    // ---------- review fixes ----------

    /** Writes an order into the book before the server starts (the book loads it): a kit order, with or without craft steps. */
    void seedOrder(String status, boolean withCraft, String templateId) throws Exception {
        java.nio.file.Files.createDirectories(data.resolve("orders"));
        String order = "{\"id\":\"o-aaaaaaaaaaaa\",\"server\":\"s\",\"stash\":\"a\",\"lines\":[{\"type\":\"kit\",\"key\":\"" + templateId + "\",\"count\":1}],"
            + "\"names\":null,\"repairAll\":false,"
            + (withCraft ? "\"craft\":{\"v\":1,\"lines\":[],\"steps\":[],\"take\":[],\"leftovers\":[],\"fills\":0}," : "")
            + "\"kit\":{\"v\":1,\"lines\":[],\"take\":[]},"
            + "\"createdAt\":1000,\"updatedAt\":1000,\"status\":\"" + status + "\",\"cancelRequested\":false}";
        String file = "{\"server\":\"s\",\"stash\":\"a\",\"orders\":[" + order + "]}";
        java.nio.file.Files.writeString(data.resolve("orders").resolve(IndexStore.fileName("s", "a")), file, StandardCharsets.UTF_8);
    }

    static final String CRAFT_CAP = "\"craftOrders\":{\"v\":1,\"enabled\":true,\"recipes\":\"" + "a".repeat(40) + "\",\"table\":\"unset\",\"maxFills\":100}";

    @Test
    void anyVariantAndVariantTakesBlockPlainLinesAndShowInTheStock() throws Exception {
        start();
        heartbeat(CAP);
        putIndex(item("minecraft:bread", "minecraft:bread", 100, 64, null) + "," + item("minecraft:bow", "minecraft:bow", 2, 1, null) + ","
            + item("minecraft:bow#aa11bb22", "minecraft:bow", 1, 1, "minecraft:bow#cc33dd44") + ","
            + item("minecraft:crossbow#aa11bb22", "minecraft:crossbow", 1, 1, "minecraft:crossbow#cc33dd44") + ","
            + item("minecraft:crossbow#ee55ff66", "minecraft:crossbow", 1, 1, "minecraft:crossbow#cc33dd44") + ","
            + item("minecraft:shulker_box", "minecraft:shulker_box", 5, 1, null));
        send(report('1', cont(A, 3, true), "", "", null));
        // Three bows of any variant: the plain key first (2), then the variant (1).
        String wild = ok(post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":"
            + KitV2Test.body("Bows", "pvp", "minecraft:bread", "minecraft:bow#*", "minecraft:bow#*", "minecraft:bow#*") + "}")).get("id").getAsString();
        JsonObject placed = ok(order(wild, 1)).getAsJsonObject("order");
        JsonObject bows = null;
        for (JsonElement e : placed.getAsJsonObject("kit").getAsJsonArray("take")) if (e.getAsJsonObject().get("match").getAsString().equals("minecraft:bow#*")) bows = e.getAsJsonObject();
        assertNotNull(bows);
        assertEquals(3, bows.get("count").getAsInt());
        assertEquals("minecraft:bow", bows.getAsJsonArray("keys").get(0).getAsJsonObject().get("key").getAsString());
        assertEquals(2, bows.getAsJsonArray("keys").get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals("minecraft:bow#aa11bb22", bows.getAsJsonArray("keys").get(1).getAsJsonObject().get("key").getAsString());
        // A plain item line for a bow is refused now, and the stock numbers everything else reads agree.
        Res line = post(owner, "/api/v1/orders", "{" + KEY + ",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:bow\",\"count\":1}]}");
        assertEquals(400, line.status, line.body);
        assertTrue(line.error().contains("only 0 available"), line.error());
        assertEquals(400, post(owner, "/api/v1/orders", "{" + KEY + ",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:bow#aa11bb22\",\"count\":1}]}").status);
        JsonObject items = ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonObject("available").getAsJsonObject("items");
        assertEquals(0, items.get("minecraft:bow").getAsInt());
        assertEquals(0, items.get("minecraft:bow#aa11bb22").getAsInt());
        assertEquals(99, items.get("minecraft:bread").getAsInt());
        java.util.Map<String, Long> reserved = orders.reservedFor("s", "a");
        assertEquals(2L, reserved.get("item:minecraft:bow"));
        assertEquals(1L, reserved.get("item:minecraft:bow#aa11bb22"));
        IndexStore.OrderCounts counts = store.orderCounts("s", "a");
        assertNull(CraftService.stock(counts, reserved).get("minecraft:bow"), "the craft stock has no bows left to use as ingredients");
        assertEquals(99L, CraftService.stock(counts, reserved).get("minecraft:bread"));
        // Variants that share a match key: an exact slot reserves both keys, lowest key first.
        String exact = ok(post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":"
            + KitV2Test.body("Crossbows", "pvp", "minecraft:bread", "minecraft:crossbow#aa11bb22", "minecraft:crossbow#aa11bb22") + "}")).get("id").getAsString();
        JsonObject o2 = ok(order(exact, 1)).getAsJsonObject("order");
        java.util.Map<String, Long> r2 = orders.reservedFor("s", "a");
        assertEquals(1L, r2.get("item:minecraft:crossbow#aa11bb22"));
        assertEquals(1L, r2.get("item:minecraft:crossbow#ee55ff66"), "the other key with the same match key");
        assertNotNull(o2);
    }

    @Test
    void kitOrdersWithCraftedShortfallsAreCraftOrdersToo() throws Exception {
        seedOrder("pending", true, "t-0000mini");
        start();
        // The bot builds kits but doesn't craft: the order isn't offered to it, and a claim without craftOrders skips it.
        assertEquals(0, ok(heartbeat(CAP)).getAsJsonObject("orders").get("pending").getAsInt());
        assertEquals(204, botPost("/api/v1/orders/claim", "{" + KEY + ",\"player\":\"Bob\",\"kitOrders\":1}").status);
        assertEquals(1, ok(heartbeat(CAP + "," + CRAFT_CAP)).getAsJsonObject("orders").get("pending").getAsInt());
        assertEquals(200, botPost("/api/v1/orders/claim", "{" + KEY + ",\"player\":\"Bob\",\"kitOrders\":1,\"craftOrders\":1}").status);
    }

    @Test
    void aHeldKitOrderIsDowngradedOnlyAfterTheGrace() throws Exception {
        seedOrder("accepted", false, "t-0000mini");
        start();
        heartbeat(CAP);
        clk.addAndGet(200_000);   // the server has been up for a while
        String claim = "{" + KEY + ",\"player\":\"Bob\"}";
        assertEquals(204, botPost("/api/v1/orders/claim", claim).status, "first flagless claim");
        clk.addAndGet(5_000);
        assertEquals(204, botPost("/api/v1/orders/claim", claim).status, "second");
        assertEquals("accepted", ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").get(0).getAsJsonObject().get("status").getAsString());
        // The third, more than 120 s after the first, with no kit-capable heartbeat since: failed.
        clk.addAndGet(130_000);
        assertEquals(204, botPost("/api/v1/orders/claim", claim).status);
        JsonObject failed = ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals("failed", failed.get("status").getAsString());
        assertEquals(OrderStore.KIT_DOWNGRADED, failed.get("message").getAsString());
    }

    @Test
    void aKitCapableHeartbeatKeepsAHeldOrderAlive() throws Exception {
        seedOrder("accepted", false, "t-0000mini");
        start();
        heartbeat(CAP);
        clk.addAndGet(200_000);
        String claim = "{" + KEY + ",\"player\":\"Bob\"}";
        botPost("/api/v1/orders/claim", claim);
        clk.addAndGet(5_000);
        botPost("/api/v1/orders/claim", claim);
        clk.addAndGet(100_000);
        heartbeat(CAP);          // the bot is there and capable
        clk.addAndGet(30_000);
        assertEquals(204, botPost("/api/v1/orders/claim", claim).status);
        assertEquals("accepted", ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").get(0).getAsJsonObject().get("status").getAsString());
        // With the flag the held order is simply handed back.
        assertEquals(200, botPost("/api/v1/orders/claim", "{" + KEY + ",\"player\":\"Bob\",\"kitOrders\":1}").status);
    }

    @Test
    void aKitAndCraftOrderNeedsBothFlagsAndBothHeartbeats() throws Exception {
        seedOrder("accepted", true, "t-0000mini");
        start();
        heartbeat(CAP + "," + CRAFT_CAP);
        clk.addAndGet(200_000);
        String kitOnly = "{" + KEY + ",\"player\":\"Bob\",\"kitOrders\":1}";
        assertEquals(204, botPost("/api/v1/orders/claim", kitOnly).status);
        clk.addAndGet(5_000);
        assertEquals(204, botPost("/api/v1/orders/claim", kitOnly).status);
        clk.addAndGet(130_000);
        assertEquals(204, botPost("/api/v1/orders/claim", kitOnly).status);
        JsonObject failed = ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals("failed", failed.get("status").getAsString());
        assertEquals(OrderStore.DOWNGRADED, failed.get("message").getAsString(), "the craft message when craft is what is missing");
    }

    @Test
    void anOrderIsCappedInKindsAndInReservedKeys() throws Exception {
        start();
        heartbeat(CAP);
        StringBuilder items = new StringBuilder(item("minecraft:shulker_box", "minecraft:shulker_box", 60, 1, null));
        List<String> ids = new java.util.ArrayList<>();
        send(report('1', cont(A, 3, true), "", "", null));
        for (int t = 0; t < 17; t++) {
            String[] keys = new String[27];
            for (int sIdx = 0; sIdx < 27; sIdx++) {
                keys[sIdx] = "minecraft:i" + t + "_" + sIdx;
                items.append(",").append(item(keys[sIdx], keys[sIdx], 10, 64, null));
            }
            ids.add(ok(post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + KitV2Test.body("Big " + t, "other", keys) + "}")).get("id").getAsString());
        }
        putIndex(items.toString());
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 17; i++) lines.append(i == 0 ? "" : ",").append("{\"type\":\"kit\",\"key\":\"").append(ids.get(i)).append("\",\"count\":1}");
        Res tooMany = post(owner, "/api/v1/orders", "{" + KEY + ",\"lines\":[" + lines + "]}");
        assertEquals(400, tooMany.status, tooMany.body);
        assertTrue(tooMany.error().contains("at most 16 different kits"), tooMany.error());
        // 16 kits of 27 different items each reserve 432 index keys: over the 256 a single order may reserve.
        StringBuilder sixteen = new StringBuilder();
        for (int i = 0; i < 16; i++) sixteen.append(i == 0 ? "" : ",").append("{\"type\":\"kit\",\"key\":\"").append(ids.get(i)).append("\",\"count\":1}");
        Res tooBig = post(owner, "/api/v1/orders", "{" + KEY + ",\"lines\":[" + sixteen + "]}");
        assertEquals(400, tooBig.status, tooBig.body);
        assertTrue(tooBig.error().contains("too many different items"), tooBig.error());
        // Eight of them (216 keys) are fine.
        StringBuilder eight = new StringBuilder();
        for (int i = 0; i < 8; i++) eight.append(i == 0 ? "" : ",").append("{\"type\":\"kit\",\"key\":\"").append(ids.get(i)).append("\",\"count\":1}");
        assertEquals(200, post(owner, "/api/v1/orders", "{" + KEY + ",\"lines\":[" + eight + "]}").status);
    }

    String readyOf(String id) throws Exception {
        Res r = order(id, 1);
        assertEquals(200, r.status, r.body);
        JsonObject line = r.obj().getAsJsonObject("order").getAsJsonObject("kit").getAsJsonArray("lines").get(0).getAsJsonObject();
        ok(post(owner, "/api/v1/orders/" + r.obj().getAsJsonObject("order").get("id").getAsString() + "/cancel", "{}"));
        return line.get("ready").getAsInt() + "/" + line.get("build").getAsInt();
    }

    @Test
    void aQuietStashKeepsItsReadyCopiesWhileTheBotIsAlive() throws Exception {
        start();
        String id = orderSetup(5);
        String stock = "\"templates\":[{\"id\":\"" + id + "\",\"complete\":3,\"incomplete\":0,\"elsewhere\":0,\"carried\":0,\"missing\":[]}]";
        send(report('2', cont(A, 3, true), "", "", stock));
        assertEquals("1/0", readyOf(id));
        // The report is 11 minutes old, but the bot kept sending heartbeats with its hash: still current.
        for (int i = 0; i < 11; i++) {
            clk.addAndGet(60_000);
            heartbeat("\"kitsReportHash\":\"" + "2".repeat(40) + "\"");
        }
        assertEquals("1/0", readyOf(id), "a report only changes when the kits do, so its age doesn't matter");
        // The bot goes quiet for 11 minutes: the report is ignored and the kit is built.
        clk.addAndGet(11 * 60_000L);
        assertEquals("0/1", readyOf(id));
        // It comes back with the same hash: current again.
        heartbeat("\"kitsReportHash\":\"" + "2".repeat(40) + "\"");
        assertEquals("1/0", readyOf(id));
    }

    @Test
    void aReportWhoseHashTheBotNoLongerHoldsIsIgnored() throws Exception {
        start();
        String id = orderSetup(5);
        send(report('2', cont(A, 3, true), "", "", "\"templates\":[{\"id\":\"" + id + "\",\"complete\":3,\"incomplete\":0,\"elsewhere\":0,\"carried\":0,\"missing\":[]}]"));
        assertEquals("1/0", readyOf(id));
        // The bot's kits changed: its heartbeat now has another hash, and the new report hasn't arrived yet.
        heartbeat("\"kitsReportHash\":\"" + "9".repeat(40) + "\"");
        assertEquals("0/1", readyOf(id));
        // A heartbeat without the key keeps what was stored; null says the bot has no report.
        heartbeat(null);
        assertEquals("0/1", readyOf(id));
        heartbeat("\"kitsReportHash\":\"" + "2".repeat(40) + "\"");
        assertEquals("1/0", readyOf(id));
        heartbeat("\"kitsReportHash\":null");
        assertEquals("0/1", readyOf(id));
    }

    @Test
    void flaglessClaimsFailAnOrderAfterTenMinutesWhateverTheHeartbeatsSay() throws Exception {
        seedOrder("accepted", false, "t-0000mini");
        start();
        heartbeat(CAP);
        clk.addAndGet(200_000);
        String claim = "{" + KEY + ",\"player\":\"Bob\"}";
        botPost("/api/v1/orders/claim", claim);
        for (int i = 0; i < 5; i++) {      // five minutes: the bot keeps heartbeating as capable
            clk.addAndGet(60_000);
            heartbeat(CAP);
            assertEquals(204, botPost("/api/v1/orders/claim", claim).status);
        }
        assertEquals("accepted", ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").get(0).getAsJsonObject().get("status").getAsString());
        for (int i = 0; i < 6; i++) {      // past ten minutes since the first flagless claim
            clk.addAndGet(60_000);
            heartbeat(CAP);
            botPost("/api/v1/orders/claim", claim);
        }
        JsonObject failed = ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals("failed", failed.get("status").getAsString());
        assertEquals(OrderStore.KIT_DOWNGRADED, failed.get("message").getAsString());
    }

    @Test
    void aLongGapBetweenFlaglessClaimsStartsANewEpisode() throws Exception {
        seedOrder("accepted", false, "t-0000mini");
        start();
        heartbeat(CAP);
        clk.addAndGet(200_000);
        String claim = "{" + KEY + ",\"player\":\"Bob\"}";
        assertEquals(204, botPost("/api/v1/orders/claim", claim).status);      // one flagless claim long ago
        clk.addAndGet(15 * 60_000L);                                           // the bot restarts; no kit heartbeat meanwhile
        assertEquals(204, botPost("/api/v1/orders/claim", claim).status);
        clk.addAndGet(1_000);
        assertEquals(204, botPost("/api/v1/orders/claim", claim).status);
        assertEquals("accepted", ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").get(0).getAsJsonObject().get("status").getAsString(),
            "two quick claims after a long gap are a new episode with a fresh grace");
        // It still fails after the normal grace in the new episode.
        clk.addAndGet(130_000);
        botPost("/api/v1/orders/claim", claim);
        assertEquals("failed", ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").get(0).getAsJsonObject().get("status").getAsString());
    }

    @Test
    void aClaimEvery11MinutesWithoutTheFlagCannotHoldAnOrderForever() throws Exception {
        seedOrder("accepted", false, "t-0000mini");
        start();
        heartbeat(CAP);
        clk.addAndGet(200_000);
        String claim = "{" + KEY + ",\"player\":\"Bob\"}";
        assertEquals(204, botPost("/api/v1/orders/claim", claim).status);
        clk.addAndGet(11 * 60_000L);
        heartbeat(CAP);
        assertEquals(204, botPost("/api/v1/orders/claim", claim).status);
        assertEquals("accepted", ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").get(0).getAsJsonObject().get("status").getAsString());
        clk.addAndGet(11 * 60_000L);
        heartbeat(CAP);
        assertEquals(204, botPost("/api/v1/orders/claim", claim).status);
        assertEquals("failed", ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").get(0).getAsJsonObject().get("status").getAsString(),
            "the third flagless claim 11 minutes apart fails it");
    }

    @Test
    void aCombinedOrderIsHeldOnlyWhileBothCapabilitiesReturn() throws Exception {
        seedOrder("accepted", true, "t-0000mini");
        start();
        heartbeat(CAP + "," + CRAFT_CAP);
        clk.addAndGet(200_000);
        String claim = "{" + KEY + ",\"player\":\"Bob\"}";     // neither flag
        botPost("/api/v1/orders/claim", claim);
        clk.addAndGet(5_000);
        botPost("/api/v1/orders/claim", claim);
        clk.addAndGet(100_000);
        heartbeat(CRAFT_CAP);                                       // only the craft capability returns
        clk.addAndGet(30_000);
        assertEquals(204, botPost("/api/v1/orders/claim", claim).status);
        JsonObject failed = ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals("failed", failed.get("status").getAsString(), "the kit capability never came back");
        assertEquals(OrderStore.DOWNGRADED, failed.get("message").getAsString());
    }

    @Test
    void missingItemsThatDoNotFitTheTemplateGiveNoCredit() throws Exception {
        start();
        String id = orderSetup(5);
        send(report('2', cont(A, 3, true), "", "", "\"templates\":[{\"id\":\"" + id + "\",\"complete\":0,\"incomplete\":1,\"elsewhere\":0,\"carried\":0,"
            + "\"missing\":[{\"key\":\"minecraft:ghost\",\"count\":8}]}]"));
        JsonArray take = ok(order(id, 1)).getAsJsonObject("order").getAsJsonObject("kit").getAsJsonArray("take");
        assertEquals(2, take.size(), "the incomplete copy is not counted on: both items are taken in full");
        assertEquals(16, take.get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals(32, take.get(1).getAsJsonObject().get("count").getAsInt());
    }
}
