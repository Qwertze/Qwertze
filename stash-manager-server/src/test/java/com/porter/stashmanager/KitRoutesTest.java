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
import java.nio.file.Files;
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
 * Kit sorting through the real routes (KIT-CONTRACT.md): the heartbeat fields, the bot's report, the four browser routes
 * with their permissions, scope, streamer, CSRF, limits and audit entries, the row statuses, and a damaged kits.json.
 */
class KitRoutesTest {
    static final String OWNER_PW = "owner-password-123";
    static final String PW = "temporary-pass-1";
    static final String EMPTY_HASH = "cf9dcdb21f6fc7551bde30826e49358bd74d06ef";
    static final String DJ = "\\"; // the backslash that starts a JSON escape
    static final String A = "10,64,10", B = "20,64,20", C = "30,64,30";

    @TempDir
    Path data;
    HttpServer server;
    String base;
    byte[] k;
    String owner;
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
        StashManagerServer.Routes r = StashManagerServer.routes(config, store, orders, owners, icons,
            new LinkState(config.keyState().generation()), List.of(), sessions, users, new AuditLog(data), new BotHub(), new LoginLimiter());
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

    record Res(int status, String body, java.net.http.HttpHeaders headers) {
        JsonObject obj() {
            return JsonParser.parseString(body).getAsJsonObject();
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
        return new Res(r.statusCode(), r.body(), r.headers());
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

    // ---------- bodies ----------

    static String hash(char c) {
        return String.valueOf(c).repeat(40);
    }

    static String cont(String id, String label, int size, int free, boolean pickable) {
        return "{\"id\":\"" + id + "\",\"label\":" + (label == null ? "null" : "\"" + label + "\"") + ",\"size\":" + size + ",\"free\":" + free
            + ",\"pickable\":" + pickable + ",\"readAt\":1767225500000}";
    }

    static String stored(String key, String name, String container, int count) {
        return "{\"key\":\"" + key + "\",\"name\":\"" + name + "\",\"container\":\"" + container + "\",\"count\":" + count + "," + SCORE + ",\"minTotal\":8}";
    }

    static final String SCORE = "\"score\":{\"total\":10,\"name\":2,\"mix\":3,\"full\":1,\"op\":4}";

    static String report(String player, char hash, String containers, String kits, String carried) {
        return "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"player\":\"" + player + "\",\"hash\":\"" + hash(hash) + "\",\"readAt\":1767225600000,"
            + "\"containers\":[" + containers + "],\"kits\":[" + kits + "],\"carried\":[" + carried + "]}";
    }

    /** A is open, B is full, C isn't pickable; kits spread over them and one carried. */
    String standardReport(char hash) {
        return report("Bob", hash, cont(A, "PvP", 54, 3, true) + "," + cont(B, null, 54, 0, true) + "," + cont(C, "Drop", 27, 5, false),
            stored("pvp kit", "PvP Kit", C, 2) + "," + stored("kit b", "Kit B", A, 1) + "," + stored("kit c", "Kit C", B, 1),
            "{\"key\":\"kit d\",\"name\":\"Kit D\",\"count\":1," + SCORE + "}");
    }

    Res heartbeat(String extra) throws Exception {
        return botPost("/api/v1/heartbeat", "{\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\"" + (extra == null ? "" : "," + extra) + "}");
    }

    void sendReport(String body) throws Exception {
        if (!beat) heartbeat(null); // a report is only taken for a stash the server already knows
        beat = true;
        Res r = botPost("/api/v1/kits/report", body);
        assertEquals(200, r.status, r.body);
    }

    static final String KEY = "\"server\":\"s\",\"stash\":\"a\"";

    JsonObject ok(Res r) {
        assertEquals(200, r.status, r.body);
        return r.obj();
    }

    JsonObject row(JsonObject view, String key) {
        for (JsonElement e : view.getAsJsonArray("rows")) if (!e.getAsJsonObject().get("key").isJsonNull() && e.getAsJsonObject().get("key").getAsString().equals(key)) return e.getAsJsonObject();
        return null;
    }

    // ---------- heartbeat ----------

    @Test
    void heartbeatKitFields() throws Exception {
        start();
        // An older bot: nothing asked, nothing sent, but the server's hash and needKitsReport are there.
        JsonObject o = ok(heartbeat(null));
        assertEquals(EMPTY_HASH, o.get("kitsHash").getAsString());
        assertFalse(o.has("kits"));
        assertFalse(o.get("needKitsReport").getAsBoolean());
        // kitsHash null: the bot has none, so it gets the settings.
        o = ok(heartbeat("\"kitsHash\":null,\"kitsReportHash\":null"));
        assertEquals(0, o.getAsJsonObject("kits").get("rev").getAsInt());
        assertEquals(EMPTY_HASH, o.getAsJsonObject("kits").get("hash").getAsString());
        assertFalse(o.get("needKitsReport").getAsBoolean(), "the bot has no report to send");
        // The same hash: no kits.
        assertFalse(ok(heartbeat("\"kitsHash\":\"" + EMPTY_HASH + "\"")).has("kits"));
        // A report hash with no report on the server: asked. After the report: not asked, until the hash changes.
        assertTrue(ok(heartbeat("\"kitsHash\":\"" + EMPTY_HASH + "\",\"kitsReportHash\":\"" + hash('1') + "\"")).get("needKitsReport").getAsBoolean());
        sendReport(standardReport('1'));
        assertFalse(ok(heartbeat("\"kitsReportHash\":\"" + hash('1') + "\"")).get("needKitsReport").getAsBoolean());
        assertTrue(ok(heartbeat("\"kitsReportHash\":\"" + hash('2') + "\"")).get("needKitsReport").getAsBoolean());
        // New settings: a stale hash gets the new object.
        ok(post(owner, "/api/v1/kits/chest", "{" + KEY + ",\"container\":\"" + A + "\"}"));
        o = ok(heartbeat("\"kitsHash\":\"" + EMPTY_HASH + "\""));
        assertEquals(A, o.getAsJsonObject("kits").get("kitsChest").getAsString());
        assertEquals(KitStore.hash(A, KitStore.Scoring.DEFAULT, java.util.Map.of()), o.get("kitsHash").getAsString());
        assertEquals(KitStore.hash(A, KitStore.Scoring.DEFAULT, java.util.Map.of()), o.getAsJsonObject("kits").get("hash").getAsString());
        // Wrong types are a 400.
        for (String bad : new String[]{"\"kitsHash\":5", "\"kitsHash\":\"abc\"", "\"kitsHash\":\"" + "A".repeat(40) + "\"", "\"kitsHash\":{}",
            "\"kitsReportHash\":true", "\"kitsReportHash\":\"xyz\""}) {
            assertEquals(400, heartbeat(bad).status, bad);
        }
    }

    // ---------- the bot's report ----------

    @Test
    void reportValidation() throws Exception {
        start();
        heartbeat(null);
        String good = standardReport('1');
        assertEquals(200, botPost("/api/v1/kits/report", good).status);
        String[] bad = {
            good.replace("\"version\":1", "\"version\":2"),
            good.replace(hash('1'), "xyz"),
            good.replace("\"id\":\"" + A + "\"", "\"id\":\"1, 2, 3\""),
            good.replace("\"id\":\"" + B + "\"", "\"id\":\"" + A + "\""),                  // duplicate id
            good.replace("\"size\":54,\"free\":3", "\"size\":30,\"free\":3"),
            good.replace("\"free\":3", "\"free\":55"),
            good.replace("\"pickable\":true,\"readAt\":1767225500000},{\"id\":\"" + B, "\"pickable\":\"yes\",\"readAt\":1767225500000},{\"id\":\"" + B),
            good.replace("\"container\":\"" + C + "\",\"count\":2", "\"container\":\"99,64,99\",\"count\":2"),  // not in containers
            good.replace("\"count\":2", "\"count\":0"),
            good.replace("\"count\":2", "\"count\":55"),
            good.replace("\"key\":\"pvp kit\"", "\"key\":\"PvP Kit\""),                       // not normalised
            good.replace("\"key\":\"kit b\"", "\"key\":\"pvp kit\"").replace("\"container\":\"" + A + "\",\"count\":1", "\"container\":\"" + C + "\",\"count\":1"),
            good.replace("\"carried\":[", "\"carried\":[{\"key\":\"kit d\",\"name\":\"x\",\"count\":1," + SCORE + "},"),   // twice
            good.replace("\"readAt\":1767225600000", "\"readAt\":-1"),
            good.replace("\"containers\":[", "\"containers\":{\"x\":["),
        };
        for (String b : bad) assertEquals(400, botPost("/api/v1/kits/report", b).status, b);
        // Too many containers, kits and carried entries.
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 4097; i++) many.append(i == 0 ? "" : ",").append(cont(i + ",64,0", null, 27, 1, true));
        assertEquals(400, botPost("/api/v1/kits/report", report("Bob", '1', many.toString(), "", "")).status);
        StringBuilder carried = new StringBuilder();
        for (int i = 0; i < 37; i++) carried.append(i == 0 ? "" : ",").append("{\"key\":\"kit ").append(i).append("\",\"name\":\"n\",\"count\":1," + SCORE + "}");
        assertEquals(400, botPost("/api/v1/kits/report", report("Bob", '1', cont(A, null, 27, 1, true), "", carried.toString())).status);
        // Over 512 KiB.
        Res big = botPost("/api/v1/kits/report", report("Bob", '1', cont(A, "x".repeat(600 * 1024), 27, 1, true), "", ""));
        assertEquals(413, big.status);
        // Unsigned: refused like every bot route.
        HttpResponse<String> raw = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/kits/report"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(good)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, raw.statusCode());
    }

    @Test
    void reportOwnerConflictAndCleaning() throws Exception {
        start();
        // The first player to speak owns the stash.
        sendReport(standardReport('1'));
        Res r = botPost("/api/v1/kits/report", standardReport('2').replace("\"player\":\"Bob\"", "\"player\":\"Eve\""));
        assertEquals(409, r.status);
        assertEquals("Bob", r.obj().get("owner").getAsString());
        // Labels and names: control characters dropped, label cut to 32, name to 64.
        sendReport(report("Bob", '3', cont(A, "A\\u0007B" + "x".repeat(60), 54, 3, true), stored("kit z", "N\\u0000" + "y".repeat(100), A, 1), ""));
        JsonObject v = ok(get(owner, "/api/v1/kits?" + KEY.replace("\"", "").replace(",", "&").replace(":", "=")));
        String label = v.getAsJsonArray("containers").get(0).getAsJsonObject().get("label").getAsString();
        assertEquals(32, label.length());
        assertTrue(label.startsWith("ABxxx"));
        String name = row(v, "kit z").get("name").getAsString();
        assertEquals(64, name.length());
        assertTrue(name.startsWith("Nyyy"));
    }

    @Test
    void reportHardening() throws Exception {
        start();
        String good = standardReport('1');
        // An unknown stash can't be made up by a report.
        Res unknown = botPost("/api/v1/kits/report", good);
        assertEquals(400, unknown.status);
        assertTrue(unknown.obj().get("error").getAsString().contains("unknown stash"));
        heartbeat(null);
        // The player is required and not empty.
        assertEquals(400, botPost("/api/v1/kits/report", good.replace("\"player\":\"Bob\",", "")).status);
        assertEquals(400, botPost("/api/v1/kits/report", good.replace("\"player\":\"Bob\"", "\"player\":\"\"")).status);
        assertEquals(400, botPost("/api/v1/kits/report", good.replace("\"player\":\"Bob\"", "\"player\":null")).status);
        // Numbers: a huge token is refused before it is parsed.
        assertEquals(400, botPost("/api/v1/kits/report", good.replace("\"count\":2", "\"count\":1e999999999")).status);
        assertEquals(400, botPost("/api/v1/kits/report", good.replace("\"count\":2", "\"count\":" + "1".repeat(25))).status);
        assertEquals(400, botPost("/api/v1/kits/report", good.replace("\"size\":54,\"free\":3", "\"size\":54,\"free\":1.0e1000")).status);
        assertEquals(200, botPost("/api/v1/kits/report", good.replace("\"count\":2", "\"count\":2.0")).status);
        // Format characters (bidi, zero-width) are dropped from labels and names like control characters.
        sendReport(report("Bob", '3', cont(A, "A" + DJ + "u202EB" + DJ + "u200BC" + DJ + "u2066", 54, 3, true),
            stored("kit z", "N" + DJ + "u202Ex" + DJ + "u200D" + DJ + "u0007y", A, 1), ""));
        JsonObject v = ok(get(owner, q()));
        assertEquals("ABC", v.getAsJsonArray("containers").get(0).getAsJsonObject().get("label").getAsString());
        assertEquals("Nxy", row(v, "kit z").get("name").getAsString());
        // A key with a lone surrogate is not a key.
        assertEquals(400, botPost("/api/v1/kits/report", good.replace("\"key\":\"pvp kit\"", "\"key\":\"pvp kit" + DJ + "ud800\"")).status);
        assertEquals(400, post(owner, "/api/v1/kits/rule/delete", "{" + KEY + ",\"key\":\"pvp kit" + DJ + "ud800\"}").status);
    }

    // ---------- scores, unnamed kits, scoring (KIT-CONTRACT.md v1.1) ----------

    static String stored(String key, String name, String container, int count, int total, int minTotal) {
        // The parts must add up to the total: fill OP, then mix, then full, then the name.
        int op = Math.min(4, total), rest = total - op, mix = Math.min(3, rest);
        rest -= mix;
        int full = Math.min(1, rest);
        rest -= full;
        return "{\"key\":" + (key == null ? "null" : "\"" + key + "\"") + ",\"name\":" + (name == null ? "null" : "\"" + name + "\"")
            + ",\"container\":\"" + container + "\",\"count\":" + count + ",\"score\":{\"total\":" + total + ",\"name\":" + rest + ",\"mix\":" + mix + ",\"full\":" + full + ",\"op\":" + op + "},\"minTotal\":" + minTotal + "}";
    }

    @Test
    void unnamedKitsAndScores() throws Exception {
        start();
        String conts = cont(A, "Any", 54, 3, true) + "," + cont(B, null, 54, 5, true);
        // The same name in two containers, an unnamed kit in both: the row shows the highest total and the lowest minTotal.
        sendReport(report("Bob", '1', conts, stored("pvp kit", "PvP Kit", A, 2, 8, 6) + "," + stored("pvp kit", "PvP Kit", B, 1, 10, 7) + ","
            + stored(null, null, A, 2, 8, 5) + "," + stored(null, null, B, 1, 9, 9), "{\"key\":null,\"name\":null,\"count\":1," + SCORE + "}"));
        heartbeat("\"kitsHash\":null");
        JsonObject v = ok(post(owner, "/api/v1/kits/chest", "{" + KEY + ",\"container\":\"" + B + "\"}"));
        JsonObject pvp = row(v, "pvp kit");
        assertEquals(10, pvp.getAsJsonObject("score").get("total").getAsInt());
        assertEquals(6, pvp.get("minTotal").getAsInt());
        assertEquals(3, pvp.get("count").getAsInt());
        JsonObject unnamed = null;
        for (JsonElement e : v.getAsJsonArray("rows")) if (e.getAsJsonObject().get("key").isJsonNull()) unnamed = e.getAsJsonObject();
        assertNotNull(unnamed, "one unnamed row");
        assertEquals(4, unnamed.get("count").getAsInt(), "3 stored plus 1 carried");
        assertTrue(unnamed.get("name").isJsonNull());
        assertTrue(unnamed.get("rule").isJsonNull());
        assertEquals(B, unnamed.get("target").getAsString());
        assertEquals("kitsChest", unnamed.get("targetSource").getAsString());
        assertEquals(10, unnamed.getAsJsonObject("score").get("total").getAsInt(), "the carried box scored highest");
        assertEquals(5, unnamed.get("minTotal").getAsInt());
        assertEquals(1, unnamed.get("carried").getAsInt());
        // A rule needs a name: a null key is refused, a name without "kit" in it is fine.
        assertEquals(400, post(owner, "/api/v1/kits/rule", "{" + KEY + ",\"key\":null,\"container\":\"" + A + "\"}").status);
        assertEquals(400, post(owner, "/api/v1/kits/rule/delete", "{" + KEY + ",\"key\":null}").status);
        v = ok(post(owner, "/api/v1/kits/rule", "{" + KEY + ",\"key\":\"mixed concrete\",\"container\":\"" + A + "\"}"));
        assertEquals("absent", row(v, "mixed concrete").get("status").getAsString());
        assertTrue(row(v, "mixed concrete").get("score").isJsonNull());
        // An unnamed kit is never "absent" and never has a rule status other than the chest's.
        assertEquals("pending", unnamed.get("status").getAsString());
        // Validation of keys and scores in the report.
        String good = report("Bob", '2', conts, stored("pvp kit", "PvP Kit", A, 2, 8, 6) + "," + stored(null, null, A, 2, 8, 5), "");
        assertEquals(200, botPost("/api/v1/kits/report", good).status);
        String[] bad = {
            good.replace("\"key\":null,\"name\":null,\"container\"", "\"name\":null,\"container\""),           // no key at all
            good.replace("\"key\":\"pvp kit\"", "\"key\":\"PvP Kit\""),                                        // not normalised
            good.replace("\"total\":8,\"name\":0,\"mix\":3,\"full\":1,\"op\":4},\"minTotal\":6", "\"total\":41,\"name\":0,\"mix\":3,\"full\":1,\"op\":4},\"minTotal\":6"),
            good.replace("\"total\":8,\"name\":0,\"mix\":3,\"full\":1,\"op\":4},\"minTotal\":6", "\"total\":8,\"name\":0,\"mix\":11,\"full\":1,\"op\":4},\"minTotal\":6"),
            good.replace("\"minTotal\":6", "\"minTotal\":-1"),
            good.replace("\"minTotal\":6", "\"minTotal\":41"),
            good.replace(",\"minTotal\":6", ""),                                                                   // minTotal is required for kits
            good.replace(",\"score\":{\"total\":8,\"name\":0,\"mix\":3,\"full\":1,\"op\":4},\"minTotal\":6", ",\"minTotal\":6"),
            good.replace("\"total\":8,\"name\":0,\"mix\":3,\"full\":1,\"op\":4},\"minTotal\":6", "\"total\":8,\"name\":0,\"mix\":3,\"full\":1},\"minTotal\":6"),
            // two unnamed entries in one container, and two unnamed carried entries
            good.replace(stored("pvp kit", "PvP Kit", A, 2, 8, 6), stored(null, null, A, 1, 8, 5)),
            good.replace("\"carried\":[]", "\"carried\":[{\"key\":null,\"name\":null,\"count\":1," + SCORE + "},{\"key\":null,\"name\":null,\"count\":1," + SCORE + "}]"),
            good.replace("\"carried\":[]", "\"carried\":[{\"key\":\"kit d\",\"name\":\"x\",\"count\":1}]"),   // carried needs a score
        };
        for (String b : bad) assertEquals(400, botPost("/api/v1/kits/report", b).status, b);
        // A null key in carried once is fine.
        assertEquals(200, botPost("/api/v1/kits/report", good.replace("\"carried\":[]", "\"carried\":[{\"key\":null,\"name\":null,\"count\":1," + SCORE + "}]")).status);
    }

    @Test
    void keyAndScoreHardening() throws Exception {
        start();
        heartbeat(null);
        String conts = cont(A, "Any", 54, 3, true) + "," + cont(B, null, 54, 5, true);
        // An unnamed kit and a kit really named "null" in one container are different entries.
        sendReport(report("Bob", '1', conts, stored(null, null, A, 1, 8, 8) + "," + stored("null", "Null", A, 2, 8, 8), ""));
        JsonObject v = ok(get(owner, q()));
        assertEquals(2, v.getAsJsonArray("rows").size());
        assertEquals(2, row(v, "null").get("count").getAsInt());
        // The row's name is the smallest name among its entries, whatever their order.
        sendReport(report("Bob", '2', conts, stored("pvp kit", "Zed Kit", A, 1, 8, 8) + "," + stored("pvp kit", "Alpha Kit", B, 1, 8, 8), ""));
        assertEquals("Alpha Kit", row(ok(get(owner, q())), "pvp kit").get("name").getAsString());
        String good = report("Bob", '3', conts, stored("pvp kit", "PvP Kit", A, 1, 8, 6), "");
        assertEquals(200, botPost("/api/v1/kits/report", good).status);
        // minTotal above the total, a total that isn't the sum of the parts, and a huge version token.
        assertEquals(400, botPost("/api/v1/kits/report", report("Bob", '3', conts, stored("pvp kit", "PvP Kit", A, 1, 8, 9), "")).status);
        assertEquals(400, botPost("/api/v1/kits/report", good.replace("\"total\":8,", "\"total\":9,")).status);
        assertEquals(400, botPost("/api/v1/kits/report", good.replace("\"total\":8,", "\"total\":7,")).status);
        assertEquals(400, botPost("/api/v1/kits/report", good.replace("\"version\":1", "\"version\":1e999")).status);
        assertEquals(400, botPost("/api/v1/kits/report", good.replace("\"version\":1", "\"version\":" + "1".repeat(30))).status);
        assertEquals(200, botPost("/api/v1/kits/report", good.replace("\"version\":1", "\"version\":1.0")).status);
        // A stash name with a lone surrogate or past the length cap is refused, for reports and settings.
        assertEquals(400, botPost("/api/v1/kits/report", good.replace("\"stash\":\"a\"", "\"stash\":\"a" + DJ + "ud800\"")).status);
        assertEquals(400, botPost("/api/v1/kits/report", good.replace("\"stash\":\"a\"", "\"stash\":\"" + "x".repeat(300) + "\"")).status);
        assertEquals(400, post(owner, "/api/v1/kits/chest", "{\"server\":\"s\",\"stash\":\"a" + DJ + "ud800\",\"container\":null}").status);
        assertEquals(400, post(owner, "/api/v1/kits/scoring", "{\"server\":\"s\",\"stash\":\"" + "x".repeat(300) + "\",\"threshold\":4,\"name\":2,\"mix\":3,\"full\":1,\"op\":4}").status);
        // Settings are only made for stashes the server knows: made-up names can't fill the 64-stash limit.
        for (int i = 0; i < 70; i++) {
            assertEquals(404, post(owner, "/api/v1/kits/scoring", "{\"server\":\"s\",\"stash\":\"ghost" + i + "\",\"threshold\":5,\"name\":2,\"mix\":3,\"full\":1,\"op\":4}").status);
        }
        assertFalse(Files.exists(data.resolve("kits.json")), "nothing was stored");
        assertEquals(404, post(owner, "/api/v1/kits/chest", "{\"server\":\"s\",\"stash\":\"ghost\",\"container\":null}").status);
        assertEquals(404, post(owner, "/api/v1/kits/rule/delete", "{\"server\":\"s\",\"stash\":\"ghost\",\"key\":\"kit\"}").status);
        assertEquals(200, post(owner, "/api/v1/kits/scoring", "{" + KEY + ",\"threshold\":5,\"name\":2,\"mix\":3,\"full\":1,\"op\":4}").status);
    }

    @Test
    void scoringOpMin() throws Exception {
        start();
        sendReport(standardReport('1'));
        heartbeat("\"kitsHash\":null");
        // opMin: optional in the request (missing means 3), 1..1728, in the answer, the hash and the heartbeat.
        String five = ",\"threshold\":5,\"name\":0,\"mix\":10,\"full\":1,\"op\":4";
        JsonObject v = ok(post(owner, "/api/v1/kits/scoring", "{" + KEY + five + ",\"opMin\":7}"));
        assertEquals(7, v.getAsJsonObject("scoring").get("opMin").getAsInt());
        assertEquals(1, v.get("rev").getAsInt());
        assertEquals(KitStore.hash(null, new KitStore.Scoring(5, 0, 10, 1, 4, 7), java.util.Map.of()), v.get("hash").getAsString());
        assertEquals(7, ok(heartbeat("\"kitsHash\":null")).getAsJsonObject("kits").getAsJsonObject("scoring").get("opMin").getAsInt());
        for (String bad : new String[]{",\"opMin\":0", ",\"opMin\":1729", ",\"opMin\":-1", ",\"opMin\":\"7\"", ",\"opMin\":2.5", ",\"opMin\":1e999999999"}) {
            assertEquals(400, post(owner, "/api/v1/kits/scoring", "{" + KEY + five + bad + "}").status, bad);
        }
        assertEquals(1, ok(get(owner, q())).get("rev").getAsInt(), "nothing was stored by the refused ones");
        assertEquals(1728, ok(post(owner, "/api/v1/kits/scoring", "{" + KEY + five + ",\"opMin\":1728}")).getAsJsonObject("scoring").get("opMin").getAsInt());
        v = ok(post(owner, "/api/v1/kits/scoring", "{" + KEY + five + "}"));
        assertEquals(3, v.getAsJsonObject("scoring").get("opMin").getAsInt(), "missing means 3");
        assertEquals(3, v.get("rev").getAsInt());
        assertEquals(3, ok(post(owner, "/api/v1/kits/scoring", "{" + KEY + five + ",\"opMin\":null}")).get("rev").getAsInt(), "null means 3, as now");
        assertEquals(3, ok(post(owner, "/api/v1/kits/scoring", "{" + KEY + five + ",\"opMin\":3}")).get("rev").getAsInt());
    }

    @Test
    void scoringRoute() throws Exception {
        start();
        sendReport(standardReport('1'));
        heartbeat("\"kitsHash\":null");
        JsonObject v = ok(get(owner, q()));
        assertEquals(EMPTY_HASH, v.get("hash").getAsString());
        JsonObject sc = v.getAsJsonObject("scoring");
        assertEquals(4, sc.get("threshold").getAsInt());
        assertEquals(2, sc.get("name").getAsInt());
        assertEquals(3, sc.get("mix").getAsInt());
        assertEquals(1, sc.get("full").getAsInt());
        assertEquals(4, sc.get("op").getAsInt());
        assertEquals(3, sc.get("opMin").getAsInt());
        assertEquals(10, sc.get("max").getAsInt());
        v = ok(post(owner, "/api/v1/kits/scoring", "{" + KEY + ",\"threshold\":5,\"name\":0,\"mix\":10,\"full\":1,\"op\":4}"));
        assertEquals(1, v.get("rev").getAsInt());
        assertEquals(15, v.getAsJsonObject("scoring").get("max").getAsInt());
        assertEquals(KitStore.hash(null, new KitStore.Scoring(5, 0, 10, 1, 4), java.util.Map.of()), v.get("hash").getAsString());
        // The bot gets the full scoring object with a stale hash; not with the current one.
        JsonObject hb = ok(heartbeat("\"kitsHash\":\"" + EMPTY_HASH + "\""));
        assertEquals(5, hb.getAsJsonObject("kits").getAsJsonObject("scoring").get("threshold").getAsInt());
        assertEquals(10, hb.getAsJsonObject("kits").getAsJsonObject("scoring").get("mix").getAsInt());
        assertFalse(ok(heartbeat("\"kitsHash\":\"" + v.get("hash").getAsString() + "\"")).has("kits"));
        // The same values again change nothing; back to the defaults gives the empty hash again.
        assertEquals(1, ok(post(owner, "/api/v1/kits/scoring", "{" + KEY + ",\"threshold\":5,\"name\":0,\"mix\":10,\"full\":1,\"op\":4}")).get("rev").getAsInt());
        v = ok(post(owner, "/api/v1/kits/scoring", "{" + KEY + ",\"threshold\":4,\"name\":2,\"mix\":3,\"full\":1,\"op\":4}"));
        assertEquals(2, v.get("rev").getAsInt());
        assertEquals(EMPTY_HASH, v.get("hash").getAsString());
        // Out of range, wrong type, missing: 400, nothing stored.
        for (String b : new String[]{
            "\"threshold\":0,\"name\":2,\"mix\":3,\"full\":1,\"op\":4", "\"threshold\":41,\"name\":2,\"mix\":3,\"full\":1,\"op\":4",
            "\"threshold\":4,\"name\":11,\"mix\":3,\"full\":1,\"op\":4", "\"threshold\":4,\"name\":2,\"mix\":-1,\"full\":1,\"op\":4",
            "\"threshold\":4,\"name\":2,\"mix\":3,\"full\":1.5,\"op\":4", "\"threshold\":\"4\",\"name\":2,\"mix\":3,\"full\":1,\"op\":4",
            "\"threshold\":4,\"name\":2,\"mix\":3,\"full\":1", "\"threshold\":1e999999999,\"name\":2,\"mix\":3,\"full\":1,\"op\":4"}) {
            assertEquals(400, post(owner, "/api/v1/kits/scoring", "{" + KEY + "," + b + "}").status, b);
        }
        assertEquals(2, ok(get(owner, q())).get("rev").getAsInt());
        // Permissions, as for the other writes.
        String viewer = userToken("vie", "{\"view\":true}");
        String forced = userToken("fay", "{\"view\":true,\"settings\":true,\"streamer\":true}");
        String body = "{" + KEY + ",\"threshold\":6,\"name\":2,\"mix\":3,\"full\":1,\"op\":4}";
        Res r = post(viewer, "/api/v1/kits/scoring", body);
        assertEquals(403, r.status);
        assertEquals("settings", r.obj().get("permission").getAsString());
        r = post(forced, "/api/v1/kits/scoring", body);
        assertEquals(403, r.status);
        assertEquals("streamer", r.obj().get("permission").getAsString());
        assertEquals(401, post(null, "/api/v1/kits/scoring", body).status);
        assertEquals(2, ok(get(owner, q())).get("rev").getAsInt());
        assertTrue(get(owner, "/api/v1/audit").body.contains("\"kits.scoring\""));
        // It counts toward the 60 writes a minute.
        int refused = 0;
        for (int i = 0; i < 62; i++) if (post(owner, "/api/v1/kits/scoring", body).status == 429) refused++;
        assertTrue(refused >= 1, "the rate limit applies");
    }

    // ---------- the website ----------

    String q() {
        return "/api/v1/kits?server=s&stash=a";
    }

    @Test
    void browserRoutesStatusesAndAudit() throws Exception {
        start();
        sendReport(standardReport('1'));
        heartbeat("\"kitsHash\":null");

        // Nothing set yet: no target, today's sorting applies; the report's containers are listed.
        JsonObject v = ok(get(owner, q()));
        assertEquals(0, v.get("rev").getAsInt());
        assertEquals("unset", v.get("kitsChestStatus").getAsString());
        assertTrue(v.getAsJsonObject("bot").get("supports").getAsBoolean());
        assertFalse(v.getAsJsonObject("bot").get("synced").getAsBoolean());
        assertTrue(v.getAsJsonObject("bot").get("online").getAsBoolean());
        assertNotNull(v.getAsJsonObject("report"));
        assertEquals(3, v.getAsJsonArray("containers").size());
        assertEquals(4, v.getAsJsonArray("rows").size());
        assertEquals("none", row(v, "pvp kit").get("status").getAsString());
        assertEquals(200, v.getAsJsonObject("limits").get("maxRules").getAsInt());
        assertEquals(64, v.getAsJsonObject("limits").get("maxKey").getAsInt());
        JsonObject pvp = row(v, "pvp kit");
        assertEquals("PvP Kit", pvp.get("name").getAsString());
        assertEquals(2, pvp.get("count").getAsInt());
        assertEquals(C, pvp.getAsJsonArray("locations").get(0).getAsJsonObject().get("container").getAsString());
        assertTrue(pvp.get("target").isJsonNull());
        assertTrue(pvp.get("targetSource").isJsonNull());

        // The kits chest is B (full); pvp kit has a rule to A; "ghost kit" has a rule but no kit.
        v = ok(post(owner, "/api/v1/kits/chest", "{" + KEY + ",\"container\":\"" + B + "\"}"));
        assertEquals(1, v.get("rev").getAsInt());
        assertEquals(B, v.get("kitsChest").getAsString());
        assertEquals("full", v.get("kitsChestStatus").getAsString());
        ok(post(owner, "/api/v1/kits/rule", "{" + KEY + ",\"key\":\"pvp kit\",\"container\":\"" + A + "\"}"));
        v = ok(post(owner, "/api/v1/kits/rule", "{" + KEY + ",\"key\":\"ghost kit\",\"container\":\"" + A + "\"}"));
        assertEquals(3, v.get("rev").getAsInt());
        assertEquals(2, v.getAsJsonArray("rules").size());
        assertEquals("pending", row(v, "pvp kit").get("status").getAsString());
        assertEquals("rule", row(v, "pvp kit").get("targetSource").getAsString());
        assertEquals(A, row(v, "pvp kit").get("target").getAsString());
        assertEquals("full", row(v, "kit b").get("status").getAsString(), "stored in A, target B is full");
        assertTrue(row(v, "kit b").get("target").isJsonNull(), "v2: the target is the first usable chain entry, and none is");
        assertEquals("kitsChest", row(v, "kit b").getAsJsonArray("chain").get(0).getAsJsonObject().get("source").getAsString());
        assertEquals("home", row(v, "kit c").get("status").getAsString());
        assertEquals("full", row(v, "kit d").get("status").getAsString(), "carried, target B is full");
        assertEquals(1, row(v, "kit d").get("carried").getAsInt());
        assertEquals("absent", row(v, "ghost kit").get("status").getAsString());
        assertEquals("ghost kit", row(v, "ghost kit").get("name").getAsString());

        // A rule to a container the new report no longer has: missing. A rule to a non-pickable one is refused.
        sendReport(report("Bob", '2', cont(B, null, 54, 10, true) + "," + cont(C, null, 27, 5, false), stored("pvp kit", "PvP Kit", B, 2), ""));
        v = ok(get(owner, q()));
        // v2 chain: the rule's chest is missing, the kits chest B (where the kit is) is fine: home, via the fallback.
        assertEquals("home", row(v, "pvp kit").get("status").getAsString(), "A is gone from the report");
        assertEquals("missing", row(v, "pvp kit").getAsJsonArray("chain").get(0).getAsJsonObject().get("status").getAsString());
        assertEquals("ok", v.get("kitsChestStatus").getAsString());
        ok(post(owner, "/api/v1/kits/rule", "{" + KEY + ",\"key\":\"pvp kit\",\"container\":\"" + B + "\"}"));
        v = ok(get(owner, q()));
        assertEquals("home", row(v, "pvp kit").get("status").getAsString());
        Res np = post(owner, "/api/v1/kits/rule", "{" + KEY + ",\"key\":\"pvp kit\",\"container\":\"" + C + "\"}");
        assertEquals(400, np.status);
        assertEquals("that container can't take kits", np.obj().get("error").getAsString());
        assertEquals(400, post(owner, "/api/v1/kits/chest", "{" + KEY + ",\"container\":\"99,64,99\"}").status, "not in the report");

        // Deleting and clearing always work (and go through when the report is gone: not needed).
        v = ok(post(owner, "/api/v1/kits/rule/delete", "{" + KEY + ",\"key\":\"ghost kit\"}"));
        assertNull(row(v, "ghost kit"));
        v = ok(post(owner, "/api/v1/kits/chest", "{" + KEY + ",\"container\":null}"));
        assertEquals("unset", v.get("kitsChestStatus").getAsString());
        assertTrue(v.get("kitsChest").isJsonNull());
        ok(post(owner, "/api/v1/kits/rule/delete", "{" + KEY + ",\"key\":\"never was a kit\"}"));

        // The stash list carries a summary of numbers and booleans.
        JsonObject summary = null;
        for (JsonElement e : JsonParser.parseString(get(owner, "/api/v1/stashes").body).getAsJsonArray()) summary = e.getAsJsonObject().getAsJsonObject("kits");
        assertNotNull(summary);
        assertTrue(summary.get("supports").getAsBoolean());
        assertFalse(summary.get("synced").getAsBoolean());
        assertEquals(0, summary.get("flagged").getAsInt());

        // Audit entries.
        String audit = get(owner, "/api/v1/audit").body;
        for (String action : new String[]{"kits.chest", "kits.rule", "kits.rule.delete"}) assertTrue(audit.contains("\"" + action + "\""), action);
        assertTrue(audit.contains("pvp kit"));
    }

    @Test
    void flaggedCountsFullAndMissingRows() throws Exception {
        start();
        sendReport(standardReport('1'));
        heartbeat("\"kitsHash\":null");
        ok(post(owner, "/api/v1/kits/chest", "{" + KEY + ",\"container\":\"" + B + "\"}")); // B is full: kit b and kit d
        JsonObject summary = null;
        for (JsonElement e : JsonParser.parseString(get(owner, "/api/v1/stashes").body).getAsJsonArray()) summary = e.getAsJsonObject().getAsJsonObject("kits");
        assertEquals(3, summary.get("flagged").getAsInt(), "kit b, kit d and pvp kit (in C) all have a full target");
        // pvp kit is in C (not pickable): a chest rule to A, then a report without A makes it missing too.
        ok(post(owner, "/api/v1/kits/rule", "{" + KEY + ",\"key\":\"pvp kit\",\"container\":\"" + A + "\"}"));
        sendReport(report("Bob", '2', cont(B, null, 54, 0, true) + "," + cont(C, null, 27, 5, false), stored("pvp kit", "PvP Kit", C, 2), ""));
        for (JsonElement e : JsonParser.parseString(get(owner, "/api/v1/stashes").body).getAsJsonArray()) summary = e.getAsJsonObject().getAsJsonObject("kits");
        assertEquals(1, summary.get("flagged").getAsInt(), "pvp kit is missing");
    }

    @Test
    void settingsNeedAReport() throws Exception {
        start();
        heartbeat(null); // settings are only made for a stash the server knows
        Res r = post(owner, "/api/v1/kits/chest", "{" + KEY + ",\"container\":\"" + A + "\"}");
        assertEquals(409, r.status);
        assertEquals("the bot hasn't reported its containers yet", r.obj().get("error").getAsString());
        assertEquals(409, post(owner, "/api/v1/kits/rule", "{" + KEY + ",\"key\":\"pvp kit\",\"container\":\"" + A + "\"}").status);
        // Clearing and deleting work without one.
        ok(post(owner, "/api/v1/kits/chest", "{" + KEY + ",\"container\":null}"));
        ok(post(owner, "/api/v1/kits/rule/delete", "{" + KEY + ",\"key\":\"pvp kit\"}"));
        // The GET works too: nothing but the settings.
        JsonObject v = ok(get(owner, q()));
        assertTrue(v.get("report").isJsonNull());
        assertEquals(0, v.getAsJsonArray("containers").size());
        assertFalse(v.getAsJsonObject("bot").get("supports").getAsBoolean());
    }

    @Test
    void badInput() throws Exception {
        start();
        sendReport(standardReport('1'));
        String[] chest = {
            "{" + KEY + ",\"container\":\"1, 2, 3\"}", "{" + KEY + ",\"container\":5}", "{" + KEY + ",\"container\":\"30000001,64,0\"}",
            "{" + KEY + ",\"container\":\"0,2049,0\"}", "{" + KEY + ",\"container\":\"a,b,c\"}", "{\"server\":\"s\",\"container\":null}",
            "[]", "not json", "{" + KEY + ",\"container\":\"" + A + "\",\"x\":\"" + "y".repeat(5000) + "\"}"};
        for (String b : chest) {
            Res r = post(owner, "/api/v1/kits/chest", b);
            assertTrue(r.status == 400 || r.status == 413, b + " -> " + r.status);
        }
        String[] rule = {
            "{" + KEY + ",\"key\":\"PvP Kit\",\"container\":\"" + A + "\"}", "{" + KEY + ",\"key\":null,\"container\":\"" + A + "\"}",
            "{" + KEY + ",\"key\":\"pvp kit\"}", "{" + KEY + ",\"key\":\"pvp kit\",\"container\":null}", "{" + KEY + ",\"container\":\"" + A + "\"}",
            "{" + KEY + ",\"key\":\"kit " + "x".repeat(70) + "\",\"container\":\"" + A + "\"}"};
        for (String b : rule) assertEquals(400, post(owner, "/api/v1/kits/rule", b).status, b);
        for (String b : new String[]{"{" + KEY + "}", "{" + KEY + ",\"key\":\"Kit\"}"}) assertEquals(400, post(owner, "/api/v1/kits/rule/delete", b).status, b);
        assertEquals(400, get(owner, "/api/v1/kits").status);
        assertEquals(0, ok(get(owner, q())).get("rev").getAsInt(), "nothing was stored");
    }

    @Test
    void permissionsScopeCsrfAndStreamer() throws Exception {
        start();
        sendReport(standardReport('1'));
        heartbeat("\"kitsHash\":null"); // makes the stash known to the list
        String viewer = userToken("vie", "{\"view\":true}");
        String settingsOnly = userToken("set", "{\"settings\":true}");
        String editor = userToken("edi", "{\"view\":true,\"settings\":true}");
        String scoped = userToken("sco", "{\"view\":true,\"settings\":true,\"stashes\":[{\"server\":\"s\",\"stash\":\"other\"}]}");
        String forced = userToken("fay", "{\"view\":true,\"settings\":true,\"streamer\":true}");
        String body = "{" + KEY + ",\"container\":\"" + A + "\"}";

        // Not logged in.
        assertEquals(401, get(null, q()).status);
        assertEquals(401, post(null, "/api/v1/kits/chest", body).status);
        // view only: reads, but can't change.
        assertEquals(200, get(viewer, q()).status);
        Res r = post(viewer, "/api/v1/kits/chest", body);
        assertEquals(403, r.status);
        assertEquals("settings", r.obj().get("permission").getAsString());
        assertEquals(403, post(viewer, "/api/v1/kits/rule", "{" + KEY + ",\"key\":\"pvp kit\",\"container\":\"" + A + "\"}").status);
        assertEquals(403, post(viewer, "/api/v1/kits/rule/delete", "{" + KEY + ",\"key\":\"pvp kit\"}").status);
        // settings without view: refused for the view part.
        r = post(settingsOnly, "/api/v1/kits/chest", body);
        assertEquals(403, r.status);
        assertEquals("view", r.obj().get("permission").getAsString());
        assertEquals(403, get(settingsOnly, q()).status);
        // A user outside the stash's scope: 404, as for a stash that doesn't exist.
        assertEquals(404, get(scoped, q()).status);
        assertEquals(404, post(scoped, "/api/v1/kits/chest", body).status);
        assertEquals(200, get(owner, "/api/v1/kits?server=s&stash=nope").status, "the owner may read any name (an empty view)");
        // A forced streamer user: 403 streamer on all four, before anything else.
        for (String[] route : new String[][]{{"/api/v1/kits/chest", body}, {"/api/v1/kits/rule", "{" + KEY + ",\"key\":\"pvp kit\",\"container\":\"" + A + "\"}"},
            {"/api/v1/kits/rule/delete", "{" + KEY + ",\"key\":\"pvp kit\"}"}}) {
            r = post(forced, route[0], route[1]);
            assertEquals(403, r.status, route[0]);
            assertEquals("streamer", r.obj().get("permission").getAsString());
        }
        r = get(forced, q());
        assertEquals(403, r.status);
        assertEquals("streamer", r.obj().get("permission").getAsString());
        // A forced user's stash list holds only booleans and counts for kits.
        JsonObject summary = null;
        for (JsonElement e : JsonParser.parseString(get(forced, "/api/v1/stashes").body).getAsJsonArray()) summary = e.getAsJsonObject().getAsJsonObject("kits");
        assertNotNull(summary);
        assertEquals(3, summary.size());
        for (String f : new String[]{"supports", "synced", "flagged"}) assertTrue(summary.get(f).getAsJsonPrimitive().isBoolean() || summary.get(f).getAsJsonPrimitive().isNumber());
        // CSRF: a write without X-Stash-Manager, or not JSON, or a cross-site origin, is refused.
        HttpResponse<String> noHeader = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/kits/chest"))
            .header("Cookie", SessionStore.COOKIE + "=" + editor).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, noHeader.statusCode());
        HttpResponse<String> notJson = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/kits/chest"))
            .header("Cookie", SessionStore.COOKIE + "=" + editor).header("X-Stash-Manager", "1").header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, notJson.statusCode());
        assertEquals(0, ok(get(owner, q())).get("rev").getAsInt(), "none of the refused writes stored anything");
        // A user with view and settings may.
        assertEquals(1, ok(post(editor, "/api/v1/kits/chest", body)).get("rev").getAsInt());
        // A write route is not a GET route.
        assertTrue(get(editor, "/api/v1/kits/chest").status >= 404);
    }

    @Test
    void writeRateLimit() throws Exception {
        start();
        sendReport(standardReport('1'));
        for (int i = 0; i < 60; i++) ok(post(owner, "/api/v1/kits/rule/delete", "{" + KEY + ",\"key\":\"pvp kit\"}"));
        Res r = post(owner, "/api/v1/kits/rule/delete", "{" + KEY + ",\"key\":\"pvp kit\"}");
        assertEquals(429, r.status);
        int retry = r.obj().get("retryAfter").getAsInt();
        assertTrue(retry >= 1 && retry <= 60, "retryAfter " + retry);
        assertEquals(String.valueOf(retry), r.headers.firstValue("Retry-After").orElseThrow());
        assertEquals(429, post(owner, "/api/v1/kits/chest", "{" + KEY + ",\"container\":null}").status);
        // Another user has their own allowance, and reads are not limited.
        String editor = userToken("edi", "{\"view\":true,\"settings\":true}");
        ok(post(editor, "/api/v1/kits/rule/delete", "{" + KEY + ",\"key\":\"pvp kit\"}"));
        assertEquals(200, get(owner, q()).status);
    }

    @Test
    void ruleLimitAndStashLimit() throws Exception {
        start();
        StringBuilder containers = new StringBuilder();
        for (int i = 0; i < 201; i++) containers.append(i == 0 ? "" : ",").append(cont(i + ",64,0", null, 27, 1, true));
        sendReport(report("Bob", '1', containers.toString(), "", ""));
        // 60 writes per user per minute: spread the 200 over four users.
        String[] who = {owner, userToken("w1", "{\"view\":true,\"settings\":true}"), userToken("w2", "{\"view\":true,\"settings\":true}"),
            userToken("w3", "{\"view\":true,\"settings\":true}")};
        for (int i = 0; i < 200; i++) {
            ok(post(who[i / 50], "/api/v1/kits/rule", "{" + KEY + ",\"key\":\"kit " + i + "\",\"container\":\"" + i + ",64,0\"}"));
        }
        Res r = post(who[0], "/api/v1/kits/rule", "{" + KEY + ",\"key\":\"kit 200\",\"container\":\"200,64,0\"}");
        assertEquals(400, r.status);
        assertEquals("at most 200 kit rules", r.obj().get("error").getAsString());
        // Changing an existing rule at the cap still works.
        ok(post(who[0], "/api/v1/kits/rule", "{" + KEY + ",\"key\":\"kit 0\",\"container\":\"200,64,0\"}"));
    }

    @Test
    void damagedFileDisablesKits() throws Exception {
        Files.writeString(data.resolve("kits.json"), "garbage", StandardCharsets.UTF_8);
        Files.writeString(data.resolve("kits.json.bak"), "also garbage", StandardCharsets.UTF_8);
        start();
        sendReport(standardReport('1'));
        // The heartbeat leaves all three kit fields out, so bots keep their cache.
        JsonObject o = ok(heartbeat("\"kitsHash\":null,\"kitsReportHash\":\"" + hash('1') + "\""));
        assertFalse(o.has("kitsHash"));
        assertFalse(o.has("kits"));
        assertFalse(o.has("needKitsReport"));
        assertEquals(503, get(owner, q()).status);
        assertEquals(503, post(owner, "/api/v1/kits/chest", "{" + KEY + ",\"container\":\"" + A + "\"}").status);
        assertEquals(503, post(owner, "/api/v1/kits/rule", "{" + KEY + ",\"key\":\"pvp kit\",\"container\":\"" + A + "\"}").status);
        assertEquals(503, post(owner, "/api/v1/kits/rule/delete", "{" + KEY + ",\"key\":\"pvp kit\"}").status);
        assertEquals("garbage", Files.readString(data.resolve("kits.json")), "never overwritten");
        // The stash list says null for the summary.
        for (JsonElement e : JsonParser.parseString(get(owner, "/api/v1/stashes").body).getAsJsonArray()) {
            assertTrue(e.getAsJsonObject().get("kits").isJsonNull());
        }
    }

    @Test
    void settingsSurviveARestart() throws Exception {
        start();
        sendReport(standardReport('1'));
        ok(post(owner, "/api/v1/kits/chest", "{" + KEY + ",\"container\":\"" + A + "\"}"));
        ok(post(owner, "/api/v1/kits/rule", "{" + KEY + ",\"key\":\"pvp kit\",\"container\":\"" + B + "\"}"));
        String hashBefore = ok(get(owner, q())).get("hash").getAsString();
        server.stop(0);
        server = null;
        start();
        JsonObject v = ok(get(owner, q()));
        assertEquals(hashBefore, v.get("hash").getAsString());
        assertEquals(2, v.get("rev").getAsInt());
        assertTrue(v.get("report").isJsonNull(), "reports live in memory only");
        // After the restart the server asks for the report again.
        assertTrue(ok(heartbeat("\"kitsReportHash\":\"" + hash('1') + "\"")).get("needKitsReport").getAsBoolean());
        // The report file never holds a report: only kits.json exists and it has no positions of containers or names.
        String file = Files.readString(data.resolve("kits.json"));
        assertFalse(file.contains("PvP"));
        JsonArray stashes = JsonParser.parseString(file).getAsJsonObject().getAsJsonArray("stashes");
        assertEquals(1, stashes.size());
    }
}
