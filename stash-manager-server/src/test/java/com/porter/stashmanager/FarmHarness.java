package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A real server on a loopback port with the whole task and farm slice wired in, for the 1.5.0 tests. The task clock is
 * {@link #clk} (tests move it); signed requests carry the real time. Helpers sign bot requests (with a bot id in the
 * header, as the strict routes require) and make a farmer bot the way the owner does: hello, role, first sync.
 */
abstract class FarmHarness {
    static final String OWNER_PW = "owner-password-123";
    static final String PW = "temporary-pass-1";
    /** Every permission except being the owner. */
    static final String ALL_BUT_OWNER = "\"view\":true,\"order\":true,\"ordersAll\":true,\"activity\":true,\"settings\":true,\"proxy\":\"change\","
        + "\"console\":\"full\",\"link\":true,\"viewer\":true,\"rescan\":true,\"planner\":true,\"plannerOrder\":true";

    @TempDir
    Path data;
    HttpServer server;
    String base;
    byte[] k;
    Config config;
    BotHub hub;
    ViewerHub viewer;
    Router router;
    TaskService tasks;
    AuditLog audit;
    String owner;
    final AtomicLong clk = new AtomicLong(System.currentTimeMillis());
    final HttpClient http = HttpClient.newHttpClient();
    /** Extra lines for config.properties before the server starts (for example allowBearer=true). */
    String extraConfig = "";

    @BeforeAll
    static void fastHashes() {
        Auth.iterations = 1000;
    }

    @AfterAll
    static void realHashes() {
        Auth.iterations = Auth.ITERATIONS;
    }

    @BeforeEach
    void startServer() throws Exception {
        startAt(data);
    }

    /** Starts (or restarts, over the same data folder) the server. */
    void startAt(Path dir) throws Exception {
        if (!extraConfig.isEmpty()) {
            Config.load(dir); // writes the file
            Files.writeString(dir.resolve("config.properties"), Files.readString(dir.resolve("config.properties")) + "\n" + extraConfig + "\n");
        }
        config = Config.load(dir);
        config.setDashboardPassword(Auth.hash(OWNER_PW));
        k = Signer.deriveKey(config.apiKey());
        IndexStore store = new IndexStore(dir, CreativeTabs.NONE);
        store.load();
        OrderStore orders = new OrderStore(dir, store);
        orders.load();
        Owners owners = new Owners(dir);
        owners.load();
        IconService icons = new IconService(dir, id -> null, v -> false, new GameIcons(dir.resolve("icons"), null, false), false);
        SessionStore sessions = new SessionStore(dir);
        sessions.load();
        Users users = new Users(dir);
        users.load();
        audit = new AuditLog(dir);
        audit.load();
        hub = new BotHub();
        viewer = new ViewerHub(hub, audit, 20_000, 300, System::currentTimeMillis);
        StashManagerServer.Routes r = StashManagerServer.routes(config, store, orders, owners, icons,
            new LinkState(config.keyState().generation()), List.of("http://192.168.1.50:8765"), sessions, users, audit, hub,
            new LoginLimiter(), new PlanStore(), viewer);
        tasks = r.tasks();
        tasks.clock(clk::get);
        tasks.jitter(() -> 1.0);
        tasks.recoverAfterLoad(); // the service started on the system clock; leases are extended again on the test one
        router = r.router();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", router);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        owner = login(null, OWNER_PW);
    }

    @AfterEach
    void stopServer() {
        StateFile.writeFault = null;
        if (viewer != null) viewer.close();
        if (server != null) server.stop(0);
    }

    void restart() throws Exception {
        viewer.close();
        server.stop(0);
        startAt(data);
    }

    // ---------- HTTP ----------

    static final class Res {
        final int status;
        final String body;

        Res(int status, String body) {
            this.status = status;
            this.body = body;
        }

        int status() {
            return status;
        }

        String body() {
            return body;
        }

        JsonObject obj() {
            return JsonParser.parseString(body).getAsJsonObject();
        }

        String code() {
            return obj().has("code") ? obj().get("code").getAsString() : null;
        }

        String field() {
            return obj().has("field") ? obj().get("field").getAsString() : null;
        }
    }

    Res signed(String method, String path, String body, String botHeader, long ts, byte[] key) throws Exception {
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        byte[] n = new byte[16];
        new java.security.SecureRandom().nextBytes(n);
        String nonce = HexFormat.of().formatHex(n);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(base + path))
            .header(Signer.REQUEST_HEADER, "v1 " + botHeader + " " + ts + " " + nonce + " " + Signer.requestSig(key, method, path, ts, nonce, b))
            .header("Content-Type", "application/json");
        rb = method.equals("GET") ? rb.GET() : rb.POST(HttpRequest.BodyPublishers.ofByteArray(b));
        HttpResponse<String> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    /** A signed bot POST with the bot id in the header (what the task routes need). */
    Res bot(String botId, String path, String body) throws Exception {
        return signed("POST", path, body, botId, System.currentTimeMillis(), k);
    }

    /** A signed POST with "-" as the header bot id (the older routes). */
    Res botAnon(String path, String body) throws Exception {
        return signed("POST", path, body, "-", System.currentTimeMillis(), k);
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

    /** A sub-user with the given permissions JSON, logged in; returns its session token. */
    String userToken(String name, String perms) throws Exception {
        Res r = post(owner, "/api/v1/users", "{\"name\":\"" + name + "\",\"password\":\"" + PW + "\",\"mustChange\":false,\"perms\":{" + perms + "}}");
        assertEquals(200, r.status, r.body);
        return login(name, PW);
    }

    static JsonObject ok(Res r) {
        assertEquals(200, r.status, r.body);
        return r.obj();
    }

    // ---------- bots and farms ----------

    void advance(long ms) {
        clk.addAndGet(ms);
    }

    /** bot/hello for a plugin bot (what makes the server know the id). */
    void hello(String botId) throws Exception {
        ok(botAnon("/api/v1/bot/hello", "{\"botId\":\"" + botId + "\",\"player\":\"Farmer1\",\"proxy\":{\"version\":\"aq-1\",\"mc\":\"1.21.4\"},"
            + "\"caps\":[\"settings\",\"console\",\"activity\"]}"));
    }

    static String syncBody(String botId, String extra) {
        return "{\"botId\":\"" + botId + "\",\"type\":\"plugin\",\"player\":\"Farmer1\",\"server\":\"2b2t.org\",\"contract\":\"1.0\","
            + "\"caps\":[\"farm\"],\"status\":{\"inGame\":true,\"dim\":\"minecraft:overworld\",\"health\":20,\"food\":20,\"freeSlots\":20,\"totems\":1}"
            + (extra == null || extra.isEmpty() ? "" : "," + extra) + "}";
    }

    /** A sync (the clock moves 1.1 s first, so the one-a-second limit never trips). */
    Res sync(String botId, String extra) throws Exception {
        advance(1100);
        return bot(botId, "/api/v1/tasks/sync", syncBody(botId, extra));
    }

    static String farmerPart(String modes, int farmsRev) {
        return "\"farmer\":{\"enabled\":true,\"acceptUploads\":false,\"version\":\"1.20.0\",\"modes\":" + modes + ",\"farmsRev\":" + farmsRev
            + ",\"forgot\":[]},\"programs\":[],\"draft\":null";
    }

    /** hello, the owner gives it the role, then a first sync with a farmer object. */
    void makeFarmer(String botId) throws Exception {
        hello(botId);
        ok(post(owner, "/api/v1/bots/roles", "{\"botId\":\"" + botId + "\",\"roles\":[\"farmer\"]}"));
        ok(sync(botId, farmerPart("[\"custom\",\"afk_still\"]", 0)));
    }

    static final String HASH_A = "a".repeat(64);
    static final String HASH_B = "b".repeat(64);

    /** A sync reporting these programs (farm id, hash, mode) and the draft (or null). */
    Res syncPrograms(String botId, String programs, String draft) throws Exception {
        return sync(botId, "\"farmer\":{\"enabled\":true,\"acceptUploads\":false,\"version\":\"1.20.0\",\"modes\":[\"custom\",\"afk_still\"],"
            + "\"farmsRev\":999,\"forgot\":[]},\"programs\":" + programs + ",\"draft\":" + draft);
    }

    static String program(String farm, String hash, String mode) {
        return "{\"farm\":\"" + farm + "\",\"hash\":\"" + hash + "\",\"mode\":\"" + mode + "\",\"ticks\":2400,\"loop\":2120,\"anchors\":4,\"unsupported\":0}";
    }

    /** Creates a farm as the owner; returns its id. */
    String createFarm(String name, String mode, String botId) throws Exception {
        JsonObject r = ok(post(owner, "/api/v1/farms", "{\"name\":\"" + name + "\",\"server\":\"2b2t.org\",\"mode\":\"" + mode + "\""
            + (botId == null ? "" : ",\"botId\":\"" + botId + "\"") + "}"));
        return r.get("id").getAsString();
    }

    /** A farm with a program (the farmer reported it), a farmer that is online. Returns the farm id. */
    String farmWithProgram(String botId, String mode) throws Exception {
        String id = createFarm("Bone farm", mode, botId);
        ok(syncPrograms(botId, "[" + program(id, HASH_A, mode) + "]", "null"));
        return id;
    }

    JsonArray tasksOf(String botId) throws Exception {
        return ok(get(owner, "/api/v1/tasks?bot=" + botId)).getAsJsonArray("tasks");
    }

    JsonObject farmOf(String id) throws Exception {
        for (var e : ok(get(owner, "/api/v1/farms")).getAsJsonArray("farms")) {
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
        }
        return null;
    }

    /** Claims the task as the bot and returns the claim body. */
    JsonObject claim(String botId, String taskId) throws Exception {
        return ok(bot(botId, "/api/v1/tasks/" + taskId + "/claim", "{\"botId\":\"" + botId + "\"}"));
    }

    static String report(String botId, int epoch, int seq, String state, String extra) {
        return "{\"botId\":\"" + botId + "\",\"epoch\":" + epoch + ",\"seq\":" + seq + ",\"state\":\"" + state + "\""
            + (extra == null || extra.isEmpty() ? "" : "," + extra) + "}";
    }

    Res report(String botId, String taskId, int epoch, int seq, String state, String extra) throws Exception {
        return bot(botId, "/api/v1/tasks/" + taskId + "/report", report(botId, epoch, seq, state, extra));
    }

    static final String RESULT_TIME_UP = "\"result\":{\"loops\":3,\"recoveries\":1,\"runtimeMs\":900000,\"endedBy\":\"time_up\",\"gained\":[]}";

    /** A farmer with a program, a started session and the claim done; returns {farmId, taskId}. */
    String[] runningSession() throws Exception {
        makeFarmer("farmer");
        String farm = farmWithProgram("farmer", "custom");
        JsonObject s = ok(post(owner, "/api/v1/farms/start", "{\"id\":\"" + farm + "\"}"));
        String task = s.get("taskId").getAsString();
        claim("farmer", task);
        return new String[]{farm, task};
    }
}
