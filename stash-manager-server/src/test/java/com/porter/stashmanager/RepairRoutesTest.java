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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repair passes through the real routes (REPAIR-CONTRACT v2.0): the formula and hash vectors, the settings store, the heartbeat
 * fields, the pass report, the browser routes with permissions, scope, streamer, CSRF and limits, the chest checks both ways, the
 * estimate and the run and stop requests.
 */
class RepairRoutesTest {
    static final String OWNER_PW = "owner-password-123";
    static final String PW = "temporary-pass-1";
    static final String V1_EMPTY = "cf9dcdb21f6fc7551bde30826e49358bd74d06ef";
    static final String V2_EMPTY = "ae46bc461e485b4ad61f4cf81983e22c21d070d0";
    static final String A = "10,64,10", B = "20,64,20", C = "30,64,30", D = "40,64,40";
    static final String KEY = "\"server\":\"s\",\"stash\":\"a\"";
    static final String SCORE = "\"score\":{\"total\":10,\"name\":2,\"mix\":3,\"full\":1,\"op\":4}";
    static final String KIT_CAP = "\"kitOrders\":{\"v\":1,\"enabled\":true,\"restock\":true,\"craft\":false}";

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


    static final String BOT = "\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\"";
    static final String H_DEFAULT = "cc21b16556872c97d51a1a7d5c9164beae9a7860";

    static String pass(String state, String why, String extra) {
        return "{\"v\":1,\"state\":\"" + state + "\",\"why\":" + (why == null ? "null" : "\"" + why + "\"") + ",\"pass\":17,\"trigger\":\"auto\","
            + "\"progress\":{\"done\":3,\"total\":12},\"candidates\":{\"mend\":12,\"mendBottles\":640,\"needsRepair\":4,\"inShulkers\":9,\"inBoxes\":2,\"boxes\":1},"
            + "\"bottles\":{\"stash\":2400,\"reserve\":1728,\"usable\":672},\"runDone\":0,\"stopDone\":0,\"reportHash\":null" + (extra == null ? "" : "," + extra) + "}";
    }

    JsonObject rview() throws Exception {
        return ok(get(owner, "/api/v1/repair?server=s&stash=a"));
    }

    String items() {
        return item("minecraft:diamond_pickaxe#aa", "minecraft:diamond_pickaxe", 3, 1, null).replace("}", ",\"damage\":800,\"maxDamage\":1561,\"mending\":true}") + ","
            + item("minecraft:iron_sword#bb", "minecraft:iron_sword", 3, 1, null).replace("}", ",\"damage\":200,\"maxDamage\":250,\"mending\":false}") + ","
            + item("minecraft:elytra#cc", "minecraft:elytra", 1, 1, null).replace("}", ",\"damage\":216,\"maxDamage\":432,\"mending\":true}") + ","
            + item("minecraft:experience_bottle", "minecraft:experience_bottle", 100, 64, null);
    }

    static String item(String key, String id, long count, int max, String match) {
        return "{\"key\":\"" + key + "\",\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"count\":" + count + ",\"maxStack\":" + max + "}";
    }

    void putIndex(String items, String shulkers) throws Exception {
        store.putIndex(JsonParser.parseString("{\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\",\"items\":[" + items + "],\"shulkers\":[" + shulkers + "]}").getAsJsonObject());
    }

    String repairReport(String extra) {
        return "{\"version\":1," + BOT + ",\"hash\":\"" + "5".repeat(40) + "\",\"pass\":{\"id\":17,\"trigger\":\"manual\",\"state\":\"done\",\"startedAt\":1767225600000,"
            + "\"endedAt\":1767226200000,\"threshold\":50,\"repaired\":5,\"partial\":1,\"bottlesUsed\":212,\"rehomed\":4,\"toNeedsRepair\":3,\"skipped\":2,\"left\":0,"
            + "\"boxesOpened\":1,\"boxesFailed\":0,\"skippedBy\":{\"reserve\":1,\"interrupted\":1}},"
            + "\"items\":[{\"key\":\"minecraft:diamond_pickaxe#ab12cd34\",\"status\":\"repaired\",\"reason\":null,\"from\":\"" + A + "\",\"to\":\"" + B + "\",\"damage\":0,\"maxDamage\":1561,\"box\":null},"
            + "{\"key\":\"minecraft:iron_sword#bb\",\"status\":\"skipped\",\"reason\":\"in a named shulker\",\"from\":null,\"to\":null,\"damage\":200,\"maxDamage\":250,\"box\":\"s:ab12cd34ef\"}],"
            + "\"containers\":[" + cont(A, 3, true) + "," + cont(B, 0, true) + "," + cont(C, 3, false) + "]" + (extra == null ? "" : "," + extra) + "}";
    }

    // ---------- formula and hash vectors (shared with every codebase) ----------

    @Test
    void theFormulaAndHashVectors() {
        Object[][] v = {{1561, 781, 50, true, 56}, {1561, 780, 50, false, 56}, {432, 216, 50, false, 16}, {432, 217, 50, true, 16},
            {2031, 1016, 50, true, 73}, {592, 300, 50, true, 22}, {250, 1, 50, false, 1}, {250, 1, 100, true, 1}, {1561, 1555, 1, true, 112}};
        // The contract's rows give maxDamage, damage, T, low and bottlesFor(damage).
        long[] damage = {781, 780, 216, 217, 1016, 300, 1, 1, 1555};
        for (int i = 0; i < v.length; i++) {
            assertEquals(v[i][3], Repair.low((int) v[i][0], damage[i], (int) v[i][2]), "low row " + i);
            assertEquals(((Integer) v[i][4]).longValue(), Repair.bottlesFor(damage[i]), "bottles row " + i);
        }
        assertEquals(false, Repair.low(1561, 0, 100), "undamaged is never low");
        assertEquals(false, Repair.low(0, 5, 100), "no durability");
        assertEquals(H_DEFAULT, RepairStore.hash(true, true, 50, 1728, null, true));
        assertEquals("52a63a5332bc3f89e9c9d6431a60864e678e9132", RepairStore.hash(true, false, 40, 0, "120,64,-33", false));
    }

    @Test
    void theStoreKeepsDefaultsRevsAndTheFile() throws Exception {
        start();
        RepairStore rs = new RepairStore(data.resolve("scratch"), () -> 1000L);
        java.nio.file.Files.createDirectories(data.resolve("scratch"));
        rs.load();
        RepairStore.Settings d = rs.get("s", "a");
        assertEquals(0, d.rev());
        assertEquals(H_DEFAULT, d.hash());
        assertTrue(d.enabled() && d.auto() && d.shulkers());
        assertEquals(50, d.threshold());
        assertEquals(1728, d.reserve());
        assertNull(d.needsRepairChest());
        assertEquals(1, rs.set("s", "a", true, false, 40, 0, null).rev());
        assertEquals(1, rs.set("s", "a", true, false, 40, 0, null).rev(), "no change, no write");
        assertEquals(2, rs.setChest("s", "a", "120,64,-33").rev());
        assertEquals(3, rs.set("s", "a", true, false, 40, 0, false).rev());
        assertEquals("52a63a5332bc3f89e9c9d6431a60864e678e9132", rs.get("s", "a").hash());
        assertTrue(java.nio.file.Files.exists(data.resolve("scratch").resolve("repair.json.bak")));
        RepairStore again = new RepairStore(data.resolve("scratch"));
        again.load();
        assertEquals(3, again.get("s", "a").rev());
        assertThrows(RepairStore.LimitException.class, () -> rs.set("s", "a", true, true, 0, 5, null));
        assertThrows(RepairStore.LimitException.class, () -> rs.set("s", "a", true, true, 50, 1_000_001, null));
        // A damaged entry only switches that stash off; a file and a backup both unreadable switch it all off.
        String raw = java.nio.file.Files.readString(data.resolve("scratch").resolve("repair.json"), StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(data.resolve("scratch").resolve("repair.json"), raw.replace("\"threshold\":40", "\"threshold\":400"), StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(data.resolve("scratch").resolve("repair.json.bak"), "nope", StandardCharsets.UTF_8);
        RepairStore damaged = new RepairStore(data.resolve("scratch"));
        damaged.load();
        assertThrows(RepairStore.DisabledException.class, () -> damaged.get("s", "a"));
        assertEquals(0, damaged.get("s", "other").rev());
    }

    // ---------- the heartbeat ----------

    @Test
    void theHeartbeatCarriesTheRepairFields() throws Exception {
        start();
        // A bot without repair passes: only the server's side is there.
        JsonObject o = ok(heartbeat(null));
        assertEquals(H_DEFAULT, o.get("repairHash").getAsString());
        assertFalse(o.has("repairSettings"));
        assertFalse(o.get("needRepairReport").getAsBoolean());
        // repairHash null: the bot has none, so it gets the settings (the full object, with shulkers).
        o = ok(heartbeat("\"repairHash\":null"));
        JsonObject rs = o.getAsJsonObject("repairSettings");
        assertEquals(0, rs.get("rev").getAsInt());
        assertTrue(rs.get("enabled").getAsBoolean() && rs.get("auto").getAsBoolean() && rs.get("shulkers").getAsBoolean());
        assertEquals(50, rs.get("threshold").getAsInt());
        assertEquals(1728, rs.get("reserve").getAsInt());
        assertTrue(rs.get("needsRepairChest").isJsonNull());
        assertFalse(ok(heartbeat("\"repairHash\":\"" + H_DEFAULT + "\"")).has("repairSettings"));
        // A report hash with no report: asked for. After the report: not asked until the hash changes.
        String h5 = "5".repeat(40);
        assertTrue(ok(heartbeat("\"repairPass\":" + pass("idle", null, null).replace("\"reportHash\":null", "\"reportHash\":\"" + h5 + "\""))).get("needRepairReport").getAsBoolean());
        send(report('1', cont(A, 3, true), "", "", null));
        assertEquals(200, botPost("/api/v1/repair/report", repairReport(null)).status);
        assertFalse(ok(heartbeat("\"repairPass\":" + pass("idle", null, null).replace("\"reportHash\":null", "\"reportHash\":\"" + h5 + "\""))).get("needRepairReport").getAsBoolean());
        assertTrue(ok(heartbeat("\"repairPass\":" + pass("idle", null, null).replace("\"reportHash\":null", "\"reportHash\":\"" + "6".repeat(40) + "\""))).get("needRepairReport").getAsBoolean());
        // Wrong shapes are a 400.
        for (String bad : new String[]{"\"repairHash\":5", "\"repairHash\":\"abc\"", "\"repairPass\":5", "\"repairPass\":{}",
            "\"repairPass\":" + pass("sleeping", null, null), "\"repairPass\":" + pass("waiting", "because", null),
            "\"repairPass\":" + pass("idle", null, null).replace("\"mend\":12", "\"mend\":-1"),
            "\"repairPass\":" + pass("idle", null, null).replace("\"trigger\":\"auto\"", "\"trigger\":\"sometimes\""),
            "\"repairPass\":" + pass("idle", null, null).replace("\"reportHash\":null", "\"reportHash\":\"xyz\""),
            "\"repairPass\":" + pass("idle", null, null).replace("\"runDone\":0", "\"runDone\":\"1\"")}) {
            assertEquals(400, heartbeat(bad).status, bad);
        }
        assertEquals(400, botPost("/api/v1/heartbeat", "{\"server\":\"s\",\"stash\":\"a\",\"repairHash\":null}").status, "repair fields need the player");
    }

    @Test
    void runAndStopRequestsReachTheBotUntilItAcknowledgesThem() throws Exception {
        start();
        heartbeat(null);
        // The bot hasn't said it can run passes: 409.
        Res none = post(owner, "/api/v1/repair/run", "{" + KEY + "}");
        assertEquals(409, none.status);
        assertEquals("this bot can't run repair passes", none.error());
        heartbeat("\"repairHash\":\"" + H_DEFAULT + "\",\"repairPass\":" + pass("idle", null, null));
        JsonObject v = ok(post(owner, "/api/v1/repair/run", "{" + KEY + "}"));
        long run = v.getAsJsonObject("bot").get("runPending").getAsLong();
        assertTrue(run > 1_700_000_000L, "an id from Unix seconds: " + run);
        assertEquals(run, ok(heartbeat("\"repairHash\":\"" + H_DEFAULT + "\"")).get("repairRun").getAsLong());
        assertFalse(ok(heartbeat(null)).has("repairStop"));
        // A stop gets a later id.
        long stop = ok(post(owner, "/api/v1/repair/stop", "{" + KEY + "}")).getAsJsonObject("bot").get("stopPending").getAsLong();
        assertTrue(stop > run);
        JsonObject hb = ok(heartbeat(null));
        assertEquals(run, hb.get("repairRun").getAsLong());
        assertEquals(stop, hb.get("repairStop").getAsLong());
        // The bot acknowledges both: nothing pending.
        hb = ok(heartbeat("\"repairPass\":" + pass("idle", null, null).replace("\"runDone\":0,\"stopDone\":0", "\"runDone\":" + run + ",\"stopDone\":" + stop)));
        assertFalse(hb.has("repairRun") || hb.has("repairStop"));
        v = rview();
        assertTrue(v.getAsJsonObject("bot").get("runPending").isJsonNull());
        assertTrue(v.getAsJsonObject("bot").get("supports").getAsBoolean());
        assertTrue(v.getAsJsonObject("bot").get("synced").getAsBoolean());
        assertEquals("idle", v.getAsJsonObject("bot").get("state").getAsString());
        assertEquals(12, v.getAsJsonObject("bot").getAsJsonObject("candidates").get("mend").getAsInt());
        // A second run gets a new, larger id; at most 6 run/stop requests a minute per user.
        long again = ok(post(owner, "/api/v1/repair/run", "{" + KEY + "}")).getAsJsonObject("bot").get("runPending").getAsLong();
        assertTrue(again > stop);
        int seen = 0;
        Res last = null;
        for (int i = 0; i < 8; i++) {
            last = post(owner, "/api/v1/repair/stop", "{" + KEY + "}");
            if (last.status == 429) break;
            seen++;
        }
        assertEquals(429, last.status);
        assertTrue(seen <= 5, "6 a minute in all: " + seen);
        assertTrue(last.obj().has("retryAfter"));
        String audit = get(owner, "/api/v1/audit").body;
        assertTrue(audit.contains("repair.run") && audit.contains("repair.stop"), audit);
    }

    // ---------- the report ----------

    @Test
    void aRepairReportIsCheckedAndShown() throws Exception {
        start();
        // Unknown stash first.
        assertEquals(400, botPost("/api/v1/repair/report", repairReport(null)).status);
        heartbeat("\"repairHash\":null");
        String good = repairReport(null);
        assertEquals(200, botPost("/api/v1/repair/report", good).status);
        String[] bad = {
            good.replace("\"version\":1", "\"version\":2"), good.replace("5".repeat(40), "xyz"),
            good.replace("\"state\":\"done\"", "\"state\":\"finished\""), good.replace("\"trigger\":\"manual\"", "\"trigger\":\"robot\""),
            good.replace("\"threshold\":50", "\"threshold\":0"), good.replace("\"repaired\":5", "\"repaired\":-1"),
            good.replace("\"status\":\"repaired\"", "\"status\":\"fixed\""), good.replace("\"reason\":\"in a named shulker\"", "\"reason\":\"free text\""),
            good.replace("\"reserve\":1", "\"nonsense\":1"), good.replace("minecraft:diamond_pickaxe#ab12cd34", "Not A Key"),
            good.replace("\"from\":\"" + A + "\"", "\"from\":\"1, 2\""), good.replace("\"box\":\"s:ab12cd34ef\"", "\"box\":\"chest\""),
            good.replace("\"size\":54", "\"size\":30"), good.replace("\"player\":\"Bob\"", "\"player\":\"\""),
        };
        for (String b : bad) assertEquals(400, botPost("/api/v1/repair/report", b).status, b);
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 201; i++) many.append(i == 0 ? "" : ",").append("{\"key\":\"minecraft:a\",\"status\":\"skipped\",\"reason\":null,\"from\":null,\"to\":null,\"damage\":1,\"maxDamage\":2,\"box\":null}");
        assertEquals(400, botPost("/api/v1/repair/report", good.replace("\"items\":[", "\"items\":[" + many + ",")).status, "at most 200 items");
        assertEquals(413, botPost("/api/v1/repair/report", repairReport("\"pad\":\"" + "x".repeat(600 * 1024) + "\"")).status);
        // The page body shows it.
        JsonObject v = rview();
        assertEquals(17, v.getAsJsonObject("lastPass").get("id").getAsInt());
        assertEquals(5, v.getAsJsonObject("lastPass").get("repaired").getAsInt());
        assertEquals(1, v.getAsJsonObject("lastPass").getAsJsonObject("skippedBy").get("reserve").getAsInt());
        assertEquals(2, v.getAsJsonArray("items").size());
        assertEquals(3, v.getAsJsonArray("containers").size());
        // The owner check: another player is a 409 { owner }.
        Res other = botPost("/api/v1/repair/report", good.replace("\"player\":\"Bob\"", "\"player\":\"Eve\""));
        assertEquals(409, other.status);
        assertTrue(other.obj().has("owner"));
    }

    // ---------- the browser routes ----------

    @Test
    void settingsAndTheNeedsRepairChest() throws Exception {
        start();
        heartbeat("\"repairHash\":null");
        String body = "{" + KEY + ",\"enabled\":true,\"auto\":false,\"threshold\":40,\"reserve\":500}";
        JsonObject v = ok(post(owner, "/api/v1/repair/settings", body));
        assertEquals(1, v.get("rev").getAsInt());
        assertEquals(40, v.getAsJsonObject("settings").get("threshold").getAsInt());
        assertTrue(v.getAsJsonObject("settings").get("shulkers").getAsBoolean(), "an absent shulkers keeps the default");
        assertEquals(RepairStore.hash(true, false, 40, 500, null, true), v.get("hash").getAsString());
        v = ok(post(owner, "/api/v1/repair/settings", "{" + KEY + ",\"enabled\":true,\"auto\":false,\"threshold\":40,\"reserve\":500,\"shulkers\":false}"));
        assertFalse(v.getAsJsonObject("settings").get("shulkers").getAsBoolean());
        // The bot picks the new settings up with its stale hash.
        assertEquals(2, ok(heartbeat("\"repairHash\":\"" + H_DEFAULT + "\"")).getAsJsonObject("repairSettings").get("rev").getAsInt());
        for (String bad : new String[]{"\"enabled\":1,\"auto\":true,\"threshold\":50,\"reserve\":1", "\"enabled\":true,\"auto\":true,\"threshold\":0,\"reserve\":1",
            "\"enabled\":true,\"auto\":true,\"threshold\":101,\"reserve\":1", "\"enabled\":true,\"auto\":true,\"threshold\":50,\"reserve\":-1",
            "\"enabled\":true,\"auto\":true,\"threshold\":50,\"reserve\":1000001", "\"enabled\":true,\"auto\":true,\"threshold\":5.5,\"reserve\":1",
            "\"enabled\":true,\"auto\":true,\"threshold\":50,\"reserve\":1,\"shulkers\":\"yes\"", "\"auto\":true,\"threshold\":50,\"reserve\":1"}) {
            assertEquals(400, post(owner, "/api/v1/repair/settings", "{" + KEY + "," + bad + "}").status, bad);
        }
        // The chest: no list yet, then a list; not pickable, kit-reserved, then ok, and clearing always works.
        String chest = "{" + KEY + ",\"container\":\"" + A + "\"}";
        assertEquals(409, post(owner, "/api/v1/repair/chest", chest).status);
        send(report('1', cont(A, 3, true) + "," + cont(B, 0, true) + "," + cont(C, 3, false), "", "", null));
        v = ok(post(owner, "/api/v1/repair/chest", chest));
        assertEquals(A, v.getAsJsonObject("settings").get("needsRepairChest").getAsString());
        assertEquals("ok", v.get("needsRepairChestStatus").getAsString());
        assertEquals(400, post(owner, "/api/v1/repair/chest", "{" + KEY + ",\"container\":\"" + C + "\"}").status);
        assertEquals(400, post(owner, "/api/v1/repair/chest", "{" + KEY + ",\"container\":\"99,64,99\"}").status);
        assertEquals(400, post(owner, "/api/v1/repair/chest", "{" + KEY + ",\"container\":\"nope\"}").status);
        ok(post(owner, "/api/v1/kits/chest", "{" + KEY + ",\"container\":\"" + B + "\"}"));
        Res reserved = post(owner, "/api/v1/repair/chest", "{" + KEY + ",\"container\":\"" + B + "\"}");
        assertEquals(400, reserved.status);
        assertEquals("that container is reserved for kits", reserved.error());
        // The reciprocal check: the kit routes refuse the needs-repair chest.
        Res kit = post(owner, "/api/v1/kits/chest", "{" + KEY + ",\"container\":\"" + A + "\"}");
        assertEquals(400, kit.status);
        assertEquals("that container is the needs-repair chest", kit.error());
        assertEquals(400, post(owner, "/api/v1/kits/rule", "{" + KEY + ",\"key\":\"pvp kit\",\"container\":\"" + A + "\"}").status);
        assertEquals(400, post(owner, "/api/v1/kits/typeChest", "{" + KEY + ",\"type\":\"pvp\",\"container\":\"" + A + "\"}").status);
        // A full chest and a chest the next report lacks.
        ok(post(owner, "/api/v1/kits/chest", "{" + KEY + ",\"container\":null}"));
        ok(post(owner, "/api/v1/repair/chest", "{" + KEY + ",\"container\":\"" + B + "\"}"));
        assertEquals("full", rview().get("needsRepairChestStatus").getAsString());
        send(report('2', cont(C, 3, true), "", "", null));
        assertEquals("missing", rview().get("needsRepairChestStatus").getAsString());
        // A kit chest set after the repair chest (a race) shows as reserved; clearing works.
        send(report('3', cont(B, 3, true) + "," + cont(A, 3, true), "", "", null));
        ok(post(owner, "/api/v1/kits/chest", "{" + KEY + ",\"container\":\"" + A + "\"}"));
        assertEquals(400, post(owner, "/api/v1/kits/rule", "{" + KEY + ",\"key\":\"x kit\",\"container\":\"" + B + "\"}").status);
        v = ok(post(owner, "/api/v1/repair/chest", "{" + KEY + ",\"container\":null}"));
        assertEquals("unset", v.get("needsRepairChestStatus").getAsString());
        // The repair report's list is used when there is no kit report newer than it.
        String audit = get(owner, "/api/v1/audit").body;
        assertTrue(audit.contains("repair.settings") && audit.contains("repair.chest"), audit);
    }

    @Test
    void theEstimateComesFromTheIndex() throws Exception {
        start();
        heartbeat("\"repairHash\":null");
        putIndex(items(), "{\"key\":\"s:abc\",\"id\":\"minecraft:red_shulker_box\",\"itemKey\":\"minecraft:red_shulker_box\",\"name\":\"x\",\"count\":2,"
            + "\"contents\":[{\"slot\":0,\"key\":\"minecraft:diamond_pickaxe#aa\",\"count\":1},{\"slot\":1,\"key\":\"minecraft:iron_sword#bb\",\"count\":1}]}");
        JsonObject e = rview().getAsJsonObject("estimate");
        assertEquals(1, e.getAsJsonObject("mend").get("items").getAsInt(), "3 pickaxes, 2 of them in the boxes: one loose; the elytra is at exactly half, not low");
        assertEquals(58, e.getAsJsonObject("mend").get("bottles").getAsInt());
        assertEquals(2, e.getAsJsonObject("mend").get("inShulkers").getAsInt(), "one per box, two boxes");
        assertEquals(1, e.getAsJsonObject("needsRepair").get("items").getAsInt());
        assertEquals(2, e.getAsJsonObject("needsRepair").get("inShulkers").getAsInt());
        assertEquals(100, e.getAsJsonObject("bottles").get("stash").getAsInt());
        assertEquals(0, e.getAsJsonObject("bottles").get("usable").getAsInt(), "below the 1728 reserve");
        // The estimate follows the index: another index (another etag) is counted afresh, the same one comes from the cache.
        assertEquals(e.toString(), rview().getAsJsonObject("estimate").toString());
        putIndex(items().replace("\"damage\":800", "\"damage\":1"), "{\"key\":\"s:abc\",\"id\":\"minecraft:red_shulker_box\",\"itemKey\":\"minecraft:red_shulker_box\",\"name\":\"x\",\"count\":2,"
            + "\"contents\":[{\"slot\":0,\"key\":\"minecraft:diamond_pickaxe#aa\",\"count\":1}]}");
        assertEquals(0, rview().getAsJsonObject("estimate").getAsJsonObject("mend").get("items").getAsInt(), "damage 1 is not below 50%");
        putIndex(items(), "{\"key\":\"s:abc\",\"id\":\"minecraft:red_shulker_box\",\"itemKey\":\"minecraft:red_shulker_box\",\"name\":\"x\",\"count\":2,"
            + "\"contents\":[{\"slot\":0,\"key\":\"minecraft:diamond_pickaxe#aa\",\"count\":1},{\"slot\":1,\"key\":\"minecraft:iron_sword#bb\",\"count\":1}]}");
        // A lower threshold counts fewer; a lower reserve frees bottles.
        ok(post(owner, "/api/v1/repair/settings", "{" + KEY + ",\"enabled\":true,\"auto\":true,\"threshold\":10,\"reserve\":40}"));
        e = rview().getAsJsonObject("estimate");
        assertEquals(0, e.getAsJsonObject("mend").get("items").getAsInt());
        assertEquals(0, e.getAsJsonObject("needsRepair").get("items").getAsInt(), "the sword has 20% left: not below 10%");
        assertEquals(60, e.getAsJsonObject("bottles").get("usable").getAsInt());
    }

    @Test
    void anUnknownStashIs404OnTheGetToo() throws Exception {
        start();
        heartbeat(null);
        assertEquals(200, get(owner, "/api/v1/repair?server=s&stash=a").status);
        assertEquals(404, get(owner, "/api/v1/repair?server=s&stash=nope").status);
    }

    @Test
    void aTemplateChestCannotBeTheNeedsRepairChest() throws Exception {
        start();
        heartbeat("\"repairHash\":null");
        send(report('1', cont(A, 3, true) + "," + cont(B, 3, true), "", "", null));
        ok(post(owner, "/api/v1/repair/chest", "{" + KEY + ",\"container\":\"" + A + "\"}"));
        JsonObject t = KitV2Test.body("Snack", "farming", "minecraft:bread", "minecraft:apple");
        t.addProperty("chest", A);
        Res r = post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + t + "}");
        assertEquals(400, r.status, r.body);
        assertEquals("that container is the needs-repair chest", r.error());
        t.addProperty("chest", B);
        assertEquals(200, post(owner, "/api/v1/kits/template", "{" + KEY + ",\"template\":" + t + "}").status);
    }

    @Test
    void aNullRepairPassClearsTheStoredStatus() throws Exception {
        start();
        heartbeat("\"repairHash\":null,\"repairPass\":" + pass("running", null, null));
        assertEquals("running", rview().getAsJsonObject("bot").get("state").getAsString());
        heartbeat("\"repairPass\":null");
        JsonObject bot = rview().getAsJsonObject("bot");
        assertTrue(bot.get("state").isJsonNull());
        assertTrue(bot.get("candidates").isJsonNull());
        assertTrue(bot.get("supports").getAsBoolean(), "the hash key was sent before; only the status is gone");
        // An absent key keeps what is stored.
        heartbeat("\"repairPass\":" + pass("idle", null, null));
        heartbeat(null);
        assertEquals("idle", rview().getAsJsonObject("bot").get("state").getAsString());
    }

    @Test
    void anAbsentAcknowledgementKeepsTheStoredOneAndALowerOneIsIgnored() throws Exception {
        start();
        heartbeat("\"repairHash\":\"" + H_DEFAULT + "\",\"repairPass\":" + pass("idle", null, null));
        long run = ok(post(owner, "/api/v1/repair/run", "{" + KEY + "}")).getAsJsonObject("bot").get("runPending").getAsLong();
        assertEquals(run, ok(heartbeat(null)).get("repairRun").getAsLong());
        // The bot acknowledges it, then restarts and sends a pass without the id fields: the run is not offered again.
        assertFalse(ok(heartbeat("\"repairPass\":" + pass("idle", null, null).replace("\"runDone\":0,\"stopDone\":0", "\"runDone\":" + run + ",\"stopDone\":0"))).has("repairRun"));
        String bare = pass("idle", null, null).replace("\"runDone\":0,\"stopDone\":0,", "");
        assertFalse(bare.contains("runDone"));
        assertFalse(ok(heartbeat("\"repairPass\":" + bare)).has("repairRun"), "an absent id keeps the stored one");
        // A lower number (an older acknowledgement) is ignored; a higher one replaces it.
        assertFalse(ok(heartbeat("\"repairPass\":" + pass("idle", null, null).replace("\"runDone\":0", "\"runDone\":5"))).has("repairRun"));
        assertEquals(run, rview().getAsJsonObject("bot").get("runPending").isJsonNull() ? run : -1);
        // Clearing the status keeps it too.
        heartbeat("\"repairPass\":null");
        assertFalse(ok(heartbeat(null)).has("repairRun"));
        // A request newer than the acknowledged id is offered; only a higher id acknowledges it.
        long again = ok(post(owner, "/api/v1/repair/run", "{" + KEY + "}")).getAsJsonObject("bot").get("runPending").getAsLong();
        assertTrue(again > run);
        assertEquals(again, ok(heartbeat("\"repairPass\":" + bare)).get("repairRun").getAsLong());
        assertFalse(ok(heartbeat("\"repairPass\":" + pass("idle", null, null).replace("\"runDone\":0", "\"runDone\":" + again))).has("repairRun"));
    }

    @Test
    void aRunRequestDoesNotSurviveARestart() throws Exception {
        start();
        heartbeat("\"repairHash\":\"" + H_DEFAULT + "\",\"repairPass\":" + pass("idle", null, null));
        ok(post(owner, "/api/v1/repair/run", "{" + KEY + "}"));
        assertTrue(ok(heartbeat(null)).has("repairRun"));
        // The server restarts (same data): requests and bot state are in memory only.
        stop();
        start();
        beat = false;
        JsonObject hb = ok(heartbeat("\"repairHash\":\"" + H_DEFAULT + "\""));
        assertFalse(hb.has("repairRun"), "no pending request after a restart");
        assertTrue(rview().getAsJsonObject("bot").get("runPending").isJsonNull());
        // The stored settings did survive.
        ok(post(owner, "/api/v1/repair/settings", "{" + KEY + ",\"enabled\":false,\"auto\":true,\"threshold\":50,\"reserve\":1}"));
        stop();
        start();
        assertEquals(1, rview().get("rev").getAsInt());
        assertEquals(false, rview().getAsJsonObject("settings").get("enabled").getAsBoolean());
        // A new run after the restart is requested and delivered again.
        heartbeat("\"repairHash\":null");
        ok(post(owner, "/api/v1/repair/run", "{" + KEY + "}"));
        assertTrue(ok(heartbeat(null)).has("repairRun"));
    }

    @Test
    void permissionsStreamersCsrfAndScope() throws Exception {
        start();
        heartbeat("\"repairHash\":\"" + H_DEFAULT + "\"");
        String viewer = userToken("vic", "{\"view\":true}");
        String editor = userToken("edi", "{\"view\":true,\"settings\":true}");
        String forced = userToken("fay", "{\"view\":true,\"settings\":true,\"streamer\":true}");
        String[][] writes = {{"settings", "\"enabled\":true,\"auto\":true,\"threshold\":50,\"reserve\":1"}, {"chest", "\"container\":null"}, {"run", "\"x\":1"}, {"stop", "\"x\":1"}};
        for (String[] w : writes) {
            String path = "/api/v1/repair/" + w[0], body = "{" + KEY + "," + w[1] + "}";
            assertEquals(403, post(viewer, path, body).status, w[0] + " viewer");
            Res f = post(forced, path, body);
            assertEquals(403, f.status, w[0] + " forced streamer");
            assertEquals("streamer", f.obj().get("permission").getAsString());
            HttpResponse<String> noHeader = http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .header("Cookie", SessionStore.COOKIE + "=" + editor).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, noHeader.statusCode(), w[0] + " csrf");
            assertEquals(404, post(editor, path, body.replace("\"a\"", "\"nope\"")).status, w[0] + " unknown stash");
        }
        assertEquals(200, get(viewer, "/api/v1/repair?server=s&stash=a").status);
        assertEquals(403, get(forced, "/api/v1/repair?server=s&stash=a").status);
        assertEquals(200, post(editor, "/api/v1/repair/settings", "{" + KEY + ",\"enabled\":false,\"auto\":true,\"threshold\":50,\"reserve\":1}").status);
        // A body over 4 KiB.
        assertEquals(413, post(editor, "/api/v1/repair/settings", "{" + KEY + ",\"pad\":\"" + "x".repeat(5000) + "\"}").status);
        // The stash list: repairPass with no positions, also for a forced streamer.
        JsonObject s = JsonParser.parseString(get(forced, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("repairPass");
        assertTrue(s.get("supports").getAsBoolean());
        assertFalse(s.get("enabled").getAsBoolean());
        assertFalse(s.get("synced").getAsBoolean(), "the bot still has the old hash");
        assertTrue(s.get("state").isJsonNull());
        heartbeat("\"repairPass\":" + pass("blocked", "disabled", null));
        s = JsonParser.parseString(get(owner, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("repairPass");
        assertEquals("blocked", s.get("state").getAsString());
        assertEquals("disabled", s.get("why").getAsString());
    }
}
