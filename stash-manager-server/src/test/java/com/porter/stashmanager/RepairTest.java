package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Repair with XP bottles (PROTOCOL.md, "Additions: repair with XP bottles"): index, heartbeat, orders, result, redaction. */
class RepairTest {
    static final String OWNER_PW = "owner-password-123";
    static final String PW = "temporary-pass-1";
    static final String PERMS = "\"view\":true,\"order\":true,\"activity\":true,\"settings\":true,\"proxy\":\"read\","
        + "\"console\":\"full\",\"link\":true,\"viewer\":true";

    @TempDir
    Path data;
    HttpServer server;
    String base;
    byte[] k;
    BotHub hub;
    ViewerHub viewer;
    final HttpClient http = HttpClient.newHttpClient();
    String owner;
    String fay; // forced streamer

    @BeforeAll
    static void fastHashes() {
        Auth.iterations = 1000;
    }

    @AfterAll
    static void realHashes() {
        Auth.iterations = Auth.ITERATIONS;
    }

    // pick: Mending, 900 damage (65 bottles); old: Mending only in details, 14 damage (1); plain: no Mending;
    // fresh: Mending, no damage; sword: damaged with Mending 15 damage (2 bottles).
    static final String INDEX = "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":["
        + "{\"key\":\"pick\",\"id\":\"minecraft:diamond_pickaxe\",\"count\":2,\"maxStack\":1,\"damage\":900,\"maxDamage\":1561,\"mending\":true},"
        + "{\"key\":\"old\",\"id\":\"minecraft:iron_axe\",\"count\":1,\"maxStack\":1,\"damage\":14,\"maxDamage\":250,\"details\":[\" mENDING \"]},"
        + "{\"key\":\"plain\",\"id\":\"minecraft:iron_sword\",\"count\":1,\"maxStack\":1,\"damage\":50,\"maxDamage\":250},"
        + "{\"key\":\"fresh\",\"id\":\"minecraft:bow\",\"count\":1,\"maxStack\":1,\"mending\":true},"
        + "{\"key\":\"sword\",\"id\":\"minecraft:diamond_sword\",\"count\":1,\"maxStack\":1,\"damage\":15,\"mending\":true},"
        + "{\"key\":\"stack\",\"id\":\"minecraft:dirt\",\"count\":500,\"maxStack\":64,\"mending\":true,\"damage\":5},"
        + "{\"key\":\"minecraft:experience_bottle\",\"id\":\"minecraft:experience_bottle\",\"count\":50,\"maxStack\":64}]}";

    @BeforeEach
    void start() throws Exception {
        Config config = Config.load(data);
        config.setDashboardPassword(Auth.hash(OWNER_PW));
        k = Signer.deriveKey(config.apiKey());
        IndexStore store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        OrderStore orders = new OrderStore(data, store);
        orders.load();
        Owners owners = new Owners(data);
        owners.load();
        IconService icons = new IconService(data, id -> null, v -> false, new GameIcons(data.resolve("icons"), null, false), false);
        SessionStore sessions = new SessionStore(data);
        sessions.load();
        Users users = new Users(data);
        users.load();
        AuditLog audit = new AuditLog(data);
        hub = new BotHub();
        viewer = new ViewerHub(hub, audit, 20_000, 300, System::currentTimeMillis);
        StashManagerServer.Routes r = StashManagerServer.routes(config, store, orders, owners, icons,
            new LinkState(config.keyState().generation()), List.of("http://192.168.1.50:8765"), sessions, users, audit, hub,
            new LoginLimiter(), new PlanStore(), viewer);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", r.router());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        ok(botPost("/api/v1/index", INDEX));
        owner = login(null, OWNER_PW);
        ok(post(owner, "/api/v1/users", "{\"name\":\"fay\",\"password\":\"" + PW + "\",\"mustChange\":false,\"perms\":{" + PERMS + ",\"streamer\":true}}"));
        fay = login("fay", PW);
    }

    @AfterEach
    void stop() {
        viewer.close();
        server.stop(0);
    }

    // ---------- helpers ----------

    record Res(int status, String body) {
        JsonObject obj() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    static void ok(Res r) {
        assertEquals(200, r.status, r.body);
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
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).GET().header("Cookie", SessionStore.COOKIE + "=" + token).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    Res post(String token, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
            .header("X-Stash-Manager", "1").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Cookie", SessionStore.COOKIE + "=" + token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    String login(String user, String pw) throws Exception {
        String body = user == null ? "{\"password\":\"" + pw + "\"}" : "{\"username\":\"" + user + "\",\"password\":\"" + pw + "\"}";
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login")).header("X-Stash-Manager", "1")
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        String c = r.headers().firstValue("Set-Cookie").orElseThrow();
        return c.substring(SessionStore.COOKIE.length() + 1, c.indexOf(';'));
    }

    static String line(String key, int count, String extra) {
        return "{\"type\":\"item\",\"key\":\"" + key + "\",\"count\":" + count + extra + "}";
    }

    Res place(String lines, String extra) throws Exception {
        return post(owner, "/api/v1/orders", "{\"server\":\"s\",\"stash\":\"a\"" + extra + ",\"lines\":[" + lines + "]}");
    }

    static JsonObject find(JsonArray a, String key) {
        for (JsonElement e : a) if (e.getAsJsonObject().get("key").getAsString().equals(key)) return e.getAsJsonObject();
        throw new AssertionError("no " + key);
    }

    // ---------- index ----------

    @Test
    void indexGainsComputedRepairFields() throws Exception {
        JsonArray items = get(owner, "/api/v1/index?server=s&stash=a").obj().getAsJsonArray("items");
        JsonObject pick = find(items, "pick");
        assertTrue(pick.get("repairable").getAsBoolean());
        assertEquals(65, pick.get("repairBottles").getAsInt()); // ceil(900 / 14)
        assertTrue(find(items, "old").get("repairable").getAsBoolean()); // the details fallback
        assertEquals(1, find(items, "old").get("repairBottles").getAsInt());
        assertEquals(2, find(items, "sword").get("repairBottles").getAsInt()); // ceil(15 / 14)
        for (String key : new String[]{"plain", "fresh", "stack", "minecraft:experience_bottle"}) {
            assertFalse(find(items, key).get("repairable").getAsBoolean(), key);
            assertEquals(0, find(items, key).get("repairBottles").getAsInt(), key);
        }
        // Forced streamer: the same fields (numbers only).
        assertEquals(65, find(get(fay, "/api/v1/index?server=" + fayServer() + "&stash=a").obj().getAsJsonArray("items"), "pick").get("repairBottles").getAsInt());
    }

    @Test
    void indexRejectsAMendingThatIsNotABoolean() throws Exception {
        assertEquals(400, botPost("/api/v1/index", INDEX.replace("\"mending\":true},{\"key\":\"old\"", "\"mending\":\"yes\"},{\"key\":\"old\"")).status);
        assertNull(IndexStore.validate(JsonParser.parseString(INDEX)));
    }

    @Test
    void theFileKeepsWhatTheBotSent() throws Exception {
        String file = java.nio.file.Files.readString(data.resolve("indexes").resolve(IndexStore.fileName("s", "a")));
        assertFalse(file.contains("repairBottles"));
        assertTrue(file.contains("\"mending\": true") || file.contains("\"mending\":true"));
    }

    @Test
    void formula() {
        assertEquals(0, Repair.bottlesFor(0));
        assertEquals(1, Repair.bottlesFor(1));
        assertEquals(1, Repair.bottlesFor(14));
        assertEquals(2, Repair.bottlesFor(15));
        assertEquals(112, Repair.bottlesFor(1561));
        JsonObject item = JsonParser.parseString("{\"maxStack\":1,\"damage\":28,\"mending\":true}").getAsJsonObject();
        assertEquals(2, Repair.of(item).bottles());
        assertEquals(Repair.NOT_SINGLE, Repair.of(JsonParser.parseString("{\"maxStack\":16,\"damage\":28,\"mending\":true}").getAsJsonObject()).reason());
        assertEquals(Repair.NO_MENDING, Repair.of(JsonParser.parseString("{\"maxStack\":16,\"damage\":28}").getAsJsonObject()).reason());
        assertEquals(Repair.NOT_DAMAGED, Repair.of(JsonParser.parseString("{\"maxStack\":16,\"mending\":true}").getAsJsonObject()).reason());
        for (String d : new String[]{"Mending", " mENDING ", "Mending I", "mending iii", "Mending 1", "MENDING 12"}) {
            assertTrue(Repair.hasMending(JsonParser.parseString("{\"details\":[\"" + d + "\"]}").getAsJsonObject()), d);
        }
        for (String d : new String[]{"Mending  I", "Mending II extra", "Mendingx", "Unmending", "Mending I1", "Mending -1"}) {
            assertFalse(Repair.hasMending(JsonParser.parseString("{\"details\":[\"" + d + "\"]}").getAsJsonObject()), d);
        }
        assertEquals(Repair.NOT_SINGLE, Repair.of(JsonParser.parseString("{\"id\":\"minecraft:red_shulker_box\",\"maxStack\":1,\"mending\":true,\"damage\":5}").getAsJsonObject()).reason());
        assertEquals(Repair.NO_MENDING, Repair.of(JsonParser.parseString("{\"maxStack\":1,\"damage\":28}").getAsJsonObject()).reason());
        assertEquals(Repair.NO_MENDING, Repair.of(JsonParser.parseString("{\"maxStack\":1,\"damage\":28,\"mending\":false,\"details\":[]}").getAsJsonObject()).reason());
        assertEquals(Repair.NOT_DAMAGED, Repair.of(JsonParser.parseString("{\"maxStack\":1,\"mending\":true}").getAsJsonObject()).reason());
    }

    // ---------- heartbeat ----------

    @Test
    void heartbeatRepairFlag() throws Exception {
        assertFalse(stash().get("repair").getAsBoolean()); // never sent
        ok(botPost("/api/v1/heartbeat", "{\"server\":\"s\",\"stash\":\"a\",\"repair\":true}"));
        assertTrue(stash().get("repair").getAsBoolean());
        ok(botPost("/api/v1/heartbeat", "{\"server\":\"s\",\"stash\":\"a\"}")); // absent leaves it alone
        assertTrue(stash().get("repair").getAsBoolean());
        ok(botPost("/api/v1/heartbeat", "{\"server\":\"s\",\"stash\":\"a\",\"repair\":false}"));
        assertFalse(stash().get("repair").getAsBoolean());
        assertEquals(400, botPost("/api/v1/heartbeat", "{\"server\":\"s\",\"stash\":\"a\",\"repair\":\"yes\"}").status);
        assertThrows(IllegalArgumentException.class, () -> AddonRoutes.botStatus(JsonParser.parseString("{\"repair\":1}").getAsJsonObject()));
    }

    JsonObject stash() throws Exception {
        for (JsonElement e : JsonParser.parseString(get(owner, "/api/v1/stashes").body).getAsJsonArray()) {
            if (e.getAsJsonObject().get("stash").getAsString().equals("a")) return e.getAsJsonObject();
        }
        throw new AssertionError("no stash");
    }

    // ---------- placing ----------

    @Test
    void placeWithoutRepairHasNoEstimate() throws Exception {
        JsonObject o = place(line("pick", 1, ""), "").obj().getAsJsonObject("order");
        assertFalse(o.get("repairAll").getAsBoolean());
        assertTrue(o.get("repairEstimate").isJsonNull());
        assertFalse(o.getAsJsonArray("lines").get(0).getAsJsonObject().has("repair"));
    }

    @Test
    void estimateForRepairAllAndPerLine() throws Exception {
        JsonObject o = place(line("pick", 2, "") + "," + line("plain", 1, "") + "," + line("fresh", 1, "") + ","
            + line("stack", 10, ""), ",\"repairAll\":true").obj().getAsJsonObject("order");
        assertTrue(o.get("repairAll").getAsBoolean());
        JsonObject est = o.getAsJsonObject("repairEstimate");
        assertEquals(130, est.get("needed").getAsInt()); // 2 x 65
        assertEquals(50, est.get("available").getAsInt());
        assertTrue(est.get("short").getAsBoolean());
        JsonArray lines = est.getAsJsonArray("lines");
        assertEquals(3, lines.size()); // "fresh" is undamaged: not wanted under repairAll
        assertEquals(130, find(lines, "pick").get("bottles").getAsInt());
        assertTrue(find(lines, "pick").get("reason").isJsonNull());
        assertEquals("no Mending", find(lines, "plain").get("reason").getAsString());
        assertThrows(AssertionError.class, () -> find(lines, "fresh"));
        assertEquals("not a single item", find(lines, "stack").get("reason").getAsString());
        assertEquals(0, find(lines, "stack").get("bottles").getAsInt());

        // Per line only: the order of lines is kept, one entry per wanted line, a shulker line is never wanted.
        JsonObject p = place(line("old", 1, ",\"repair\":true") + "," + line("sword", 1, ""), "").obj().getAsJsonObject("order");
        assertFalse(p.get("repairAll").getAsBoolean());
        JsonObject pe = p.getAsJsonObject("repairEstimate");
        assertEquals(1, pe.getAsJsonArray("lines").size());
        assertEquals(1, pe.get("needed").getAsInt());
        assertFalse(pe.get("short").getAsBoolean());
    }

    @Test
    void explicitLineRepairIsWantedEvenIfItCannotBeRepaired() throws Exception {
        // An explicit line repair is wanted even when it can't be repaired.
        JsonObject ex = place(line("fresh", 1, ",\"repair\":true"), "").obj().getAsJsonObject("order").getAsJsonObject("repairEstimate");
        assertEquals("not damaged", find(ex.getAsJsonArray("lines"), "fresh").get("reason").getAsString());

    }

    @Test
    void availableBottlesAreReservedByOpenOrders() throws Exception {
        assertEquals(50, get(owner, "/api/v1/orders?server=s&stash=a").obj().getAsJsonObject("available").get("bottles").getAsInt());
        ok(place(line("minecraft:experience_bottle", 20, ""), ""));
        assertEquals(30, get(owner, "/api/v1/orders?server=s&stash=a").obj().getAsJsonObject("available").get("bottles").getAsInt());
        JsonObject est = place(line("pick", 1, ",\"repair\":true"), "").obj().getAsJsonObject("order").getAsJsonObject("repairEstimate");
        assertEquals(30, est.get("available").getAsInt());
        assertEquals(65, est.get("needed").getAsInt());
        assertTrue(est.get("short").getAsBoolean());
    }

    @Test
    void mergedLinesKeepRepairIfAnyCopyHadIt() throws Exception {
        JsonObject o = place(line("pick", 1, "") + "," + line("pick", 1, ",\"repair\":true"), "").obj().getAsJsonObject("order");
        JsonArray lines = o.getAsJsonArray("lines");
        assertEquals(1, lines.size());
        assertEquals(2, lines.get(0).getAsJsonObject().get("count").getAsInt());
        assertTrue(lines.get(0).getAsJsonObject().get("repair").getAsBoolean());
        assertEquals(130, o.getAsJsonObject("repairEstimate").get("needed").getAsInt());
    }

    @Test
    void wrongTypesAre400() throws Exception {
        assertEquals(400, place(line("pick", 1, ""), ",\"repairAll\":\"yes\"").status);
        assertEquals(400, place(line("pick", 1, ""), ",\"repairAll\":1").status);
        assertEquals(400, place(line("pick", 1, ",\"repair\":\"yes\""), "").status);
        assertEquals(400, place(line("pick", 1, ",\"repair\":1"), "").status);
        // null means the default, and asking to repair something that can't be repaired is fine.
        ok(place(line("plain", 1, ",\"repair\":null"), ",\"repairAll\":null"));
        ok(place(line("fresh", 1, ",\"repair\":true"), ""));
    }

    @Test
    void retryCopiesRepairAndRecomputes() throws Exception {
        String id = place(line("pick", 1, ",\"repair\":true"), ",\"repairAll\":true").obj().getAsJsonObject("order").get("id").getAsString();
        ok(post(owner, "/api/v1/orders/" + id + "/cancel", "{}"));
        JsonObject again = post(owner, "/api/v1/orders/" + id + "/retry", "{}").obj().getAsJsonObject("order");
        assertTrue(again.get("repairAll").getAsBoolean());
        assertTrue(again.getAsJsonArray("lines").get(0).getAsJsonObject().get("repair").getAsBoolean());
        assertEquals(65, again.getAsJsonObject("repairEstimate").get("needed").getAsInt());
    }

    @Test
    void orderSurvivesARestart() throws Exception {
        place(line("pick", 1, ",\"repair\":true"), ",\"repairAll\":true");
        IndexStore store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        OrderStore again = new OrderStore(data, store);
        again.load();
        JsonObject o = again.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject();
        assertTrue(o.get("repairAll").getAsBoolean());
        assertEquals(65, o.getAsJsonObject("repairEstimate").get("needed").getAsInt());
        assertTrue(o.getAsJsonArray("lines").get(0).getAsJsonObject().get("repair").getAsBoolean());
    }

    // ---------- the bot's result ----------

    static JsonObject rr(String s) {
        return OrderRoutes.repairResult(JsonParser.parseString(s));
    }

    @Test
    void resultRepairValidation() {
        assertNull(OrderRoutes.repairResult(null));
        assertNull(rr("null"));
        JsonObject r = rr("{\"bottlesUsed\":10,\"repaired\":2,\"partial\":[{\"key\":\"pick\",\"damage\":120,\"maxDamage\":1561}],"
            + "\"skipped\":[{\"key\":\"plain\",\"reason\":\"no Mending\"}]}");
        assertEquals(10, r.get("bottlesUsed").getAsInt());
        assertEquals(2, r.get("repaired").getAsInt());
        assertEquals(120, r.getAsJsonArray("partial").get(0).getAsJsonObject().get("damage").getAsInt());
        assertEquals("no Mending", r.getAsJsonArray("skipped").get(0).getAsJsonObject().get("reason").getAsString());
        // Missing counts are 0 and lists empty.
        JsonObject e = rr("{}");
        assertEquals(0, e.get("bottlesUsed").getAsInt());
        assertEquals(0, e.getAsJsonArray("partial").size());

        for (String bad : new String[]{"[]", "5", "{\"bottlesUsed\":-1}", "{\"bottlesUsed\":1.5}", "{\"repaired\":\"2\"}",
            "{\"partial\":{}}", "{\"partial\":[1]}", "{\"partial\":[{\"damage\":1}]}", "{\"partial\":[{\"key\":\"k\"}]}",
            "{\"partial\":[{\"key\":\"k\",\"damage\":-1}]}", "{\"skipped\":\"x\"}", "{\"skipped\":[{\"key\":\"k\"}]}",
            "{\"skipped\":[{\"reason\":\"r\"}]}", "{\"skipped\":[{\"key\":\"k\",\"reason\":5}]}"}) {
            assertThrows(IllegalArgumentException.class, () -> rr(bad), bad);
        }
    }

    @Test
    void resultRepairCaps() {
        StringBuilder skipped = new StringBuilder("[");
        for (int i = 0; i < 450; i++) skipped.append(i == 0 ? "" : ",").append("{\"key\":\"").append("k".repeat(300)).append("\",\"reason\":\"").append("r".repeat(100)).append("\"}");
        JsonObject r = rr("{\"skipped\":" + skipped + "]}");
        assertEquals(400, r.getAsJsonArray("skipped").size());
        JsonObject first = r.getAsJsonArray("skipped").get(0).getAsJsonObject();
        assertEquals(60, first.get("reason").getAsString().length());
        assertEquals(256, first.get("key").getAsString().length());
    }

    @Test
    void statusStoresRepairAndRedactsFreeText() throws Exception {
        JsonObject placed = place(line("pick", 1, ",\"repair\":true"), "").obj().getAsJsonObject("order");
        String id = placed.get("id").getAsString();
        assertEquals(200, botPost("/api/v1/orders/claim", "{\"server\":\"s\",\"stash\":\"a\"}").status);
        String body = "{\"status\":\"delivered\",\"message\":\"1 item(s) not fully repaired: interrupted\","
            + "\"result\":{\"shulkers\":1,\"lines\":[{\"key\":\"pick\",\"delivered\":1}],"
            + "\"repair\":{\"bottlesUsed\":10,\"repaired\":0,\"partial\":[{\"key\":\"pick\",\"damage\":120,\"maxDamage\":1561}],"
            + "\"skipped\":[{\"key\":\"pick\",\"reason\":\"interrupted\"},{\"key\":\"x\",\"reason\":\"player at 1234, 64, -5678\"},"
            + "{\"key\":\"Steve at 12 64 -9\",\"reason\":\"no bottles\"},{\"key\":\"minecraft:bow#ab12\",\"reason\":\"no bottles\"}]}}}";
        ok(botPost("/api/v1/orders/" + id + "/status", body));
        assertEquals(400, botPost("/api/v1/orders/" + id + "/status", body.replace("\"bottlesUsed\":10", "\"bottlesUsed\":-3")).status);

        JsonObject mine = find2(get(owner, "/api/v1/orders?server=s&stash=a").obj(), id);
        JsonObject rep = mine.getAsJsonObject("result").getAsJsonObject("repair");
        assertEquals(10, rep.get("bottlesUsed").getAsInt());
        assertEquals("player at 1234, 64, -5678", rep.getAsJsonArray("skipped").get(1).getAsJsonObject().get("reason").getAsString());

        // Forced streamer: numbers and keys as they are, a reason that is not one of the known ones is hidden.
        Res res = get(fay, "/api/v1/orders?server=" + fayServer() + "&stash=a");
        assertFalse(res.body.contains("1234"), res.body);
        JsonObject theirs = find2(res.obj(), id).getAsJsonObject("result").getAsJsonObject("repair");
        assertEquals(10, theirs.get("bottlesUsed").getAsInt());
        assertEquals(120, theirs.getAsJsonArray("partial").get(0).getAsJsonObject().get("damage").getAsInt());
        assertEquals("interrupted", theirs.getAsJsonArray("skipped").get(0).getAsJsonObject().get("reason").getAsString());
        assertEquals("(hidden)", theirs.getAsJsonArray("skipped").get(1).getAsJsonObject().get("reason").getAsString());
        assertEquals("(hidden)", theirs.getAsJsonArray("skipped").get(2).getAsJsonObject().get("key").getAsString());
        assertEquals("minecraft:bow#ab12", theirs.getAsJsonArray("skipped").get(3).getAsJsonObject().get("key").getAsString());
        assertEquals("Steve at 12 64 -9", rep.getAsJsonArray("skipped").get(2).getAsJsonObject().get("key").getAsString());
        assertEquals("pick", find(find2(res.obj(), id).getAsJsonObject("repairEstimate").getAsJsonArray("lines"), "pick").get("key").getAsString());
    }

    /** The opaque server id a forced user has to use. */
    String fayServer() throws Exception {
        return JsonParser.parseString(get(fay, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().get("server").getAsString();
    }

    static JsonObject find2(JsonObject list, String id) {
        for (JsonElement e : list.getAsJsonArray("orders")) if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
        throw new AssertionError("no order " + id);
    }
}
