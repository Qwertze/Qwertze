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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live viewer through the real routes on a real server (127.0.0.1, a random port, a temp data folder): the viewer
 * permission, bot scope, the cross-site check, the stream caps, frame validation, SSE events, rotation, revocation and
 * the bot/next start/stop signal; plus the hub's coalescing and memory bound on their own.
 */
class ViewerTest {
    static final String OWNER_PW = "owner-password-123";
    static final String TEMP_PW = "temporary-pass-1";
    static final long ROTATE_MS = 3000;
    static final long HEARTBEAT_MS = 400;

    @TempDir
    Path data;
    HttpServer server;
    String base;
    Config config;
    BotHub hub;
    ViewerHub viewer;
    AuditLog audit;
    TaskService tasks;
    byte[] k;
    final HttpClient http = HttpClient.newHttpClient();
    final List<InputStream> open = new ArrayList<>();
    ExecutorService readers = Executors.newCachedThreadPool();

    @BeforeAll
    static void fastHashes() {
        Auth.iterations = 1000;
    }

    @AfterAll
    static void realHashes() {
        Auth.iterations = Auth.ITERATIONS;
    }

    @BeforeEach
    void start() throws Exception {
        config = Config.load(data);
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
        audit = new AuditLog(data);
        hub = new BotHub();
        viewer = new ViewerHub(hub, audit, ROTATE_MS, HEARTBEAT_MS, System::currentTimeMillis);
        StashManagerServer.Routes r = StashManagerServer.routes(config, store, orders, owners, icons,
            new LinkState(config.keyState().generation()), List.of(), sessions, users, audit, hub, new LoginLimiter(),
            new PlanStore(), viewer);
        tasks = r.tasks();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 64);
        server.setExecutor(Executors.newFixedThreadPool(24));
        server.createContext("/", r.router());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        assertEquals(200, botPost("/api/v1/bot/hello", "{\"botId\":\"b1\",\"player\":\"Bob\",\"proxy\":{\"mc\":\"1.21.4\"}}").status);
        assertEquals(200, botPost("/api/v1/bot/hello", "{\"botId\":\"b2\",\"player\":\"Al\"}").status);
    }

    @AfterEach
    void stop() {
        for (InputStream in : open) {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
        readers.shutdownNow();
        viewer.close();
        if (server != null) server.stop(0);
    }

    // ---------- helpers ----------

    record Res(int status, String body) {
        JsonObject obj() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    Res botPost(String path, String body) throws Exception {
        return botPost(path, body.getBytes(StandardCharsets.UTF_8));
    }

    Res botPost(String path, byte[] body) throws Exception {
        long ts = System.currentTimeMillis();
        byte[] n = new byte[16];
        new java.security.SecureRandom().nextBytes(n);
        String nonce = HexFormat.of().formatHex(n);
        String sig = Signer.requestSig(k, "POST", path, ts, nonce, body);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
            .header(Signer.REQUEST_HEADER, "v1 - " + ts + " " + nonce + " " + sig)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    Res get(String token, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (token != null) b.header("Cookie", SessionStore.COOKIE + "=" + token);
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    Res post(String token, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
            .header("X-Stash-Manager", "1").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Cookie", SessionStore.COOKIE + "=" + token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    String login(String username, String password) throws Exception {
        String body = username == null ? "{\"password\":\"" + password + "\"}"
            : "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login"))
            .header("X-Stash-Manager", "1").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        String setCookie = r.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(SessionStore.COOKIE.length() + 1, setCookie.indexOf(';'));
    }

    String createUser(String owner, String name, String perms) throws Exception {
        Res r = post(owner, "/api/v1/users", "{\"name\":\"" + name + "\",\"password\":\"" + TEMP_PW + "\",\"mustChange\":false,"
            + (perms.startsWith("{") ? "\"perms\":" + perms : "\"preset\":\"" + perms + "\"") + "}");
        assertEquals(200, r.status, r.body);
        return r.obj().getAsJsonObject("user").get("id").getAsString();
    }

    record Event(String name, JsonObject data) {
    }

    /** An open SSE stream: its status and a queue of parsed events (a null-named event when it ended). */
    record Stream(int status, BlockingQueue<Event> events, InputStream in, String body) {
        Event next(String name, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (true) {
                long left = end - System.currentTimeMillis();
                Event e = left <= 0 ? null : events.poll(left, TimeUnit.MILLISECONDS);
                if (e == null) return null;
                if (e.name == null) return name == null ? e : null;
                if (name == null || e.name.equals(name)) return e;
            }
        }
    }

    static String vs(int n) {
        return String.format("%016x", n);
    }

    Stream stream(String token, String bot, String vs, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/api/v1/bots/" + bot + "/viewer/stream?vs=" + vs)).GET()
            .header("Cookie", SessionStore.COOKIE + "=" + token).header("Accept", "text/event-stream");
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        HttpResponse<InputStream> r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        InputStream in = r.body();
        open.add(in);
        if (r.statusCode() != 200) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new Stream(r.statusCode(), null, in, body);
        }
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        assertFalse(r.headers().firstValue("Content-Encoding").isPresent(), "never gzip");
        BlockingQueue<Event> q = new LinkedBlockingQueue<>();
        readers.submit(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String name = null;
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("event: ")) name = line.substring(7);
                    else if (line.startsWith("data: ") && name != null) {
                        q.add(new Event(name, JsonParser.parseString(line.substring(6)).getAsJsonObject()));
                        name = null;
                    }
                }
            } catch (Exception ignored) {
            }
            q.add(new Event(null, null));
            return null;
        });
        return new Stream(200, q, in, null);
    }

    static String b64(int n, int fill) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) fill);
        return Base64.getEncoder().encodeToString(b);
    }

    static String tile(int x, int z, int colour) {
        return "{\"x\":" + x + ",\"z\":" + z + ",\"c\":\"" + b64(256, colour) + "\",\"h\":\"" + b64(512, 0) + "\"}";
    }

    static final String HUD = "{\"hp\":18.5,\"food\":17,\"sat\":2.0,\"xp\":30,\"xpP\":0.42,\"armor\":15,\"held\":2,"
        + "\"hotbar\":[{\"id\":\"minecraft:diamond_sword\",\"c\":1},null,null,null,null,null,null,null,null],"
        + "\"armorSlots\":[null,null,null,null],\"offhand\":{\"id\":\"minecraft:totem_of_undying\",\"c\":1}}";

    static String full(String bot, String session, long seq) {
        return "{\"botId\":\"" + bot + "\",\"v\":1,\"session\":\"" + session + "\",\"seq\":" + seq + ",\"t\":" + System.currentTimeMillis()
            + ",\"full\":true,\"world\":{\"dim\":\"minecraft:overworld\",\"minY\":-64,\"height\":384},"
            + "\"self\":{\"x\":123.4,\"y\":64,\"z\":-88.0,\"yaw\":91},\"tiles\":[" + tile(7, -6, 5) + "],"
            + "\"ents\":[{\"id\":311,\"k\":\"player\",\"n\":\"Steve\",\"x\":130.2,\"y\":64,\"z\":-80.5,\"yaw\":180}],"
            + "\"hud\":" + HUD + ",\"chat\":[{\"t\":1,\"k\":\"system\",\"s\":\"\",\"to\":null,\"m\":\"welcome\"}]}";
    }

    static String delta(String bot, String session, long seq, String extra) {
        return "{\"botId\":\"" + bot + "\",\"v\":1,\"session\":\"" + session + "\",\"seq\":" + seq + ",\"t\":" + System.currentTimeMillis()
            + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    // ---------- tests ----------

    @Test
    void permissionScopeAndCrossSite() throws Exception {
        String owner = login(null, OWNER_PW);
        createUser(owner, "vic", "viewer"); // the "viewer" preset: view stashes only, not the live viewer
        createUser(owner, "sam", "{\"viewer\":true,\"bots\":[\"b2\"]}");
        String vic = login("vic", TEMP_PW);
        String sam = login("sam", TEMP_PW);

        Res denied = get(vic, "/api/v1/bots/b1/viewer/stream?vs=" + vs(1));
        assertEquals(403, denied.status);
        assertEquals("viewer", denied.obj().get("permission").getAsString());
        assertEquals(403, get(vic, "/api/v1/bots/b1/viewer").status);
        assertEquals(401, get(null, "/api/v1/bots/b1/viewer/stream?vs=" + vs(1)).status);

        // viewer implies activity: sam sees the bot list, but only b2.
        Res bots = get(sam, "/api/v1/bots");
        assertEquals(200, bots.status);
        assertEquals(1, JsonParser.parseString(bots.body).getAsJsonArray().size());
        assertEquals(404, stream(sam, "b1", vs(2)).status, "out of scope");
        assertEquals(404, get(sam, "/api/v1/bots/b1/viewer").status);
        assertEquals(404, stream(sam, "nobody", vs(2)).status, "unknown bot");
        assertEquals(400, stream(sam, "b2", "xyz").status, "bad vs");

        assertEquals(403, stream(owner, "b1", vs(3), "Sec-Fetch-Site", "cross-site").status);
        assertEquals(403, stream(owner, "b1", vs(3), "Origin", "http://evil.example").status);
        Stream ok = stream(owner, "b1", vs(3), "Sec-Fetch-Site", "same-origin");
        assertEquals(200, ok.status);
        JsonObject hello = ok.next("hello", 2000).data();
        assertEquals("b1", hello.get("botId").getAsString());
        assertEquals("1.21.4", hello.get("mc").getAsString());
        assertEquals(ROTATE_MS, hello.get("rotateMs").getAsLong());
        assertEquals("waiting", ok.next("status", 2000).data().get("state").getAsString());

        JsonObject st = get(owner, "/api/v1/bots/b1/viewer").obj();
        assertEquals(1, st.get("streams").getAsInt());
        assertEquals(ViewerHub.MAX_PER_BOT, st.get("maxStreams").getAsInt());
        assertFalse(st.get("live").getAsBoolean());
        assertEquals("1.21.4", st.get("mc").getAsString());

        // The routes are registered with the right guards.
        assertTrue(Users.PRESETS.get("operator").viewer());
        assertFalse(Users.PRESETS.get("orderer").viewer());
        assertTrue(Users.Perms.parse(json("{\"viewer\":true}")).activity(), "viewer implies activity");
    }

    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void capsAnswer429BeforeTheHeaders() throws Exception {
        String owner = login(null, OWNER_PW);
        String[] tokens = new String[4];
        for (int i = 0; i < 4; i++) {
            createUser(owner, "u" + i, "{\"viewer\":true}");
            tokens[i] = login("u" + i, TEMP_PW);
        }
        // 2 per user.
        for (int i = 0; i < 2; i++) assertEquals(200, stream(tokens[0], "b1", vs(10 + i)).status);
        assertEquals(429, stream(tokens[0], "b2", vs(13)).status, "a 3rd stream of one user");
        // A reconnect of the same page replaces its old stream instead of counting.
        assertEquals(200, stream(tokens[0], "b1", vs(10)).status);
        // 4 per bot: b1 has 2 (u0); 2 more from u1, then the 5th is refused.
        assertEquals(200, stream(tokens[1], "b1", vs(20)).status);
        assertEquals(200, stream(tokens[1], "b1", vs(21)).status);
        assertEquals(429, stream(tokens[2], "b1", vs(30)).status, "a 5th stream of one bot");
        // 8 in all: 4 on b1, then u2 and u3 add 2 each on b2; the 9th is refused.
        assertEquals(200, stream(tokens[2], "b2", vs(31)).status);
        assertEquals(200, stream(tokens[2], "b2", vs(32)).status);
        assertEquals(200, stream(tokens[3], "b2", vs(40)).status);
        assertEquals(200, stream(tokens[3], "b2", vs(41)).status);
        assertEquals(8, viewer.streamCount());
        Stream ninth = stream(owner, "b2", vs(50));
        assertEquals(429, ninth.status, "the 9th stream on the server");
        assertTrue(ninth.body().contains("viewer streams"), ninth.body());
    }

    @Test
    void handlerThreadsHaveHardCaps() {
        // Counted from handler entry to exit, whatever the stream caps say (a replaced stream may still be writing).
        for (int i = 0; i < ViewerHub.MAX_THREADS_PER_USER; i++) assertNull(viewer.enter("u-a"));
        assertNotNull(viewer.enter("u-a"), "one user's threads");
        for (int i = ViewerHub.MAX_THREADS_PER_USER; i < ViewerHub.MAX_THREADS; i++) assertNull(viewer.enter("u-" + i));
        assertNotNull(viewer.enter("u-other"), "the server's threads");
        viewer.exit("u-a");
        assertNull(viewer.enter("u-a"));
        assertEquals(ViewerHub.MAX_THREADS, viewer.threadCount());
    }

    @Test
    void stuckWritersAreCappedPerUserAndInAll() {
        viewer.writerDetached("u-a");
        assertNull(viewer.enter("u-a"), "one stuck write is fine");
        viewer.exit("u-a");
        viewer.writerDetached("u-a");
        assertNotNull(viewer.enter("u-a"), "two stuck writes: that user waits");
        assertNull(viewer.enter("u-b"), "others don't");
        viewer.exit("u-b");
        viewer.writerFinished("u-a");
        assertNull(viewer.enter("u-a"), "one finished: allowed again");
        viewer.exit("u-a");
        for (int i = 1; i < ViewerHub.MAX_DETACHED; i++) viewer.writerDetached("u-" + i);
        assertEquals(ViewerHub.MAX_DETACHED, viewer.detachedCount());
        assertNotNull(viewer.enter("u-z"), "8 stuck writes in all: nobody new, so writer threads stay free");
        viewer.writerFinished("u-1");
        assertNull(viewer.enter("u-z"));
    }

    @Test
    void aReaderThatStopsReadingFreesItsThread() throws Exception {
        viewer.writeTimeoutMs = 1000;
        String owner = login(null, OWNER_PW);
        try (java.net.Socket sock = new java.net.Socket()) {
            sock.setReceiveBufferSize(1024);
            sock.connect(server.getAddress());
            sock.getOutputStream().write(("GET /api/v1/bots/b1/viewer/stream?vs=" + vs(1) + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Cookie: " + SessionStore.COOKIE + "=" + owner + "\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            sock.getOutputStream().flush();
            long end = System.currentTimeMillis() + 5000;
            while (viewer.streamCount() == 0 && System.currentTimeMillis() < end) Thread.sleep(20);
            assertEquals(1, viewer.threadCount());
            String session = hub.viewerSession(hub.bot("b1"));
            // Big snapshots (about 330 KB each) the socket never reads.
            for (int f = 0; f < 4; f++) assertEquals(200, botPost("/api/v1/bot/viewer", bigFull("b1", session, f + 1)).status);
            end = System.currentTimeMillis() + 8000;
            while (viewer.threadCount() > 0 && System.currentTimeMillis() < end) Thread.sleep(50);
            assertEquals(0, viewer.threadCount(), "the handler gave up the stuck write");
            assertEquals(1, viewer.detachedCount(), "the stuck write is counted until it ends");
            assertEquals(0, viewer.streamCount());
            assertFalse(sock.isClosed());
            // The pool still answers.
            assertEquals(200, get(owner, "/api/v1/bots/b1/viewer").status);
        }
        // The socket closed: the stuck write fails and is no longer counted.
        long end = System.currentTimeMillis() + 10_000;
        while (viewer.detachedCount() > 0 && System.currentTimeMillis() < end) Thread.sleep(50);
        assertEquals(0, viewer.detachedCount());
    }

    /** A full frame with 300 tiles around the bot at 0, 0. */
    static String bigFull(String bot, String session, long seq) {
        StringBuilder tiles = new StringBuilder();
        int n = 0;
        for (int x = -9; x <= 9 && n < 300; x++) {
            for (int z = -9; z <= 9 && n < 300; z++, n++) tiles.append(n == 0 ? "" : ",").append(tile(x, z, 5 + (int) (seq % 3)));
        }
        return full(bot, session, seq).replace("\"x\":123.4,\"y\":64,\"z\":-88.0", "\"x\":0,\"y\":64,\"z\":0")
            .replace("\"tiles\":[" + tile(7, -6, 5) + "]", "\"tiles\":[" + tiles + "]");
    }

    @Test
    void framesAreValidated() throws Exception {
        String owner = login(null, OWNER_PW);
        Stream s = stream(owner, "b1", vs(1));
        assertNotNull(s.next("hello", 2000));
        String session = hub.viewerSession(hub.bot("b1"));
        assertNotNull(session);

        assertEquals(409, botPost("/api/v1/bot/viewer", full("ghost", session, 1)).status, "no hello yet");
        String badTile = full("b1", session, 1).replace(b64(256, 5), b64(255, 5));
        assertEquals(400, botPost("/api/v1/bot/viewer", badTile).status, "c is not 256 bytes");
        assertEquals(400, botPost("/api/v1/bot/viewer", full("b1", session, 1).replace("\"v\":1", "\"v\":2")).status);
        assertEquals(400, botPost("/api/v1/bot/viewer", full("b1", session, 1).replace("\"k\":\"player\"", "\"k\":\"ghost\"")).status);
        assertEquals(400, botPost("/api/v1/bot/viewer", full("b1", session, 1).replace("null,null,null,null,null,null,null,null]", "null]")).status,
            "a hotbar needs 9 entries");
        byte[] big = new byte[513 * 1024];
        java.util.Arrays.fill(big, (byte) ' ');
        byte[] head = full("b1", session, 1).getBytes(StandardCharsets.UTF_8);
        System.arraycopy(head, 0, big, 0, head.length);
        assertEquals(413, botPost("/api/v1/bot/viewer", big).status, "over 512 KiB");

        Res wrong = botPost("/api/v1/bot/viewer", full("b1", "0123456789abcdef", 1));
        assertEquals(200, wrong.status);
        assertTrue(wrong.obj().get("resync").getAsBoolean(), "a session the server didn't mint");
        Res early = botPost("/api/v1/bot/viewer", delta("b1", session, 1, ""));
        assertTrue(early.obj().get("resync").getAsBoolean(), "a delta before any full frame");
        Res ok = botPost("/api/v1/bot/viewer", full("b1", session, 2));
        assertEquals(200, ok.status, ok.body);
        assertTrue(ok.obj().get("watching").getAsBoolean());
        assertFalse(ok.obj().get("resync").getAsBoolean());

        // Chat is cut, not refused; an empty sender (system lines) is fine.
        String longChat = delta("b1", session, 3, "\"chat\":[{\"t\":5,\"k\":\"public\",\"s\":\"" + "S".repeat(40) + "\",\"to\":null,\"m\":\""
            + "m".repeat(400) + "\"}]");
        assertEquals(200, botPost("/api/v1/bot/viewer", longChat).status);
        JsonObject line = viewer.live("b1").chat.peekLast();
        assertEquals(16, line.get("s").getAsString().length());
        assertEquals(256, line.get("m").getAsString().length());

        // More than 15 frames a second for one bot: 429 with retryMs. (b2 has its own budget.)
        int limited = 0;
        long retry = 0;
        for (int i = 0; i < 16; i++) {
            Res r = botPost("/api/v1/bot/viewer", delta("b2", session, 100 + i, ""));
            if (r.status == 429) {
                limited++;
                retry = r.obj().get("retryMs").getAsLong();
            }
        }
        assertEquals(1, limited, "the 16th frame within a second");
        assertTrue(retry > 0 && retry <= 1000);
    }

    @Test
    void streamCarriesSnapshotAndChanges() throws Exception {
        String owner = login(null, OWNER_PW);
        Stream s = stream(owner, "b1", vs(1));
        assertNotNull(s.next("hello", 2000));
        String session = hub.viewerSession(hub.bot("b1"));
        assertEquals(200, botPost("/api/v1/bot/viewer", full("b1", session, 1)).status);
        Event snap = s.next("snapshot", 2000);
        assertNotNull(snap);
        JsonObject d = snap.data();
        assertEquals(session, d.get("session").getAsString());
        assertEquals(1, d.getAsJsonArray("tiles").size());
        assertEquals("Steve", d.getAsJsonArray("ents").get(0).getAsJsonObject().get("n").getAsString());
        assertEquals("welcome", d.getAsJsonArray("chat").get(0).getAsJsonObject().get("m").getAsString());
        assertEquals(9, d.getAsJsonObject("hud").getAsJsonArray("hotbar").size());
        assertTrue(d.get("st").getAsLong() >= d.get("rt").getAsLong());
        assertEquals("live", s.next("status", 2000).data().get("state").getAsString());

        botPost("/api/v1/bot/viewer", delta("b1", session, 2, "\"tiles\":[" + tile(8, -6, 9) + "],\"drop\":[[7,-6]]"));
        JsonObject tiles = s.next("tiles", 2000).data();
        assertEquals(8, tiles.getAsJsonArray("tiles").get(0).getAsJsonObject().get("x").getAsInt());
        assertEquals(7, tiles.getAsJsonArray("drop").get(0).getAsJsonArray().get(0).getAsInt());
        assertEquals(2, tiles.get("seq").getAsLong());

        botPost("/api/v1/bot/viewer", delta("b1", session, 3, "\"self\":{\"x\":124.0,\"y\":64,\"z\":-88.0,\"yaw\":90},\"ents\":[]"));
        JsonObject ents = s.next("ents", 2000).data();
        assertEquals(124.0, ents.getAsJsonObject("self").get("x").getAsDouble());
        assertEquals(0, ents.getAsJsonArray("ents").size());

        botPost("/api/v1/bot/viewer", delta("b1", session, 4, "\"hud\":" + HUD.replace("18.5", "4.0")));
        assertEquals(4.0, s.next("hud", 2000).data().getAsJsonObject("hud").get("hp").getAsDouble());
        botPost("/api/v1/bot/viewer", delta("b1", session, 5, "\"chat\":[{\"t\":9,\"k\":\"whisper\",\"s\":\"Al\",\"to\":\"Bob\",\"m\":\"hi\"}]"));
        assertEquals("hi", s.next("chat", 2000).data().getAsJsonArray("lines").get(0).getAsJsonObject().get("m").getAsString());

        // A late or repeated frame changes nothing.
        Res late = botPost("/api/v1/bot/viewer", delta("b1", session, 4, "\"hud\":" + HUD));
        assertFalse(late.obj().get("resync").getAsBoolean());
        assertEquals(4.0, viewer.live("b1").hud.get("hp").getAsDouble());

        // A second stream of the same page gets the whole state at once; the audit has one open for the page.
        Stream again = stream(owner, "b1", vs(1));
        assertEquals("capacity", s.next("bye", 2000).data().get("reason").getAsString(), "the replaced stream ends");
        JsonObject snap2 = again.next("snapshot", 2000).data();
        assertEquals(1, snap2.getAsJsonArray("tiles").size());
        assertEquals(2, snap2.getAsJsonArray("chat").size());
        long opens = 0;
        for (JsonElement e : audit.newest(100)) if (e.getAsJsonObject().get("action").getAsString().equals("viewer.open")) opens++;
        assertEquals(1, opens);
        String all = audit.newest(100).toString();
        assertFalse(all.contains("Steve") || all.contains("welcome") || all.contains("123.4"), "no names, chat or positions in the audit");
    }

    @Test
    void streamsRotateAndRevocationEndsThem() throws Exception {
        String owner = login(null, OWNER_PW);
        String id = createUser(owner, "rita", "{\"viewer\":true}");
        String id2 = createUser(owner, "pete", "{\"viewer\":true}");
        String rita = login("rita", TEMP_PW);
        String pete = login("pete", TEMP_PW);

        long t0 = System.currentTimeMillis();
        Stream s = stream(owner, "b1", vs(1));
        Event bye = s.next("bye", ROTATE_MS + 2000);
        assertNotNull(bye);
        assertEquals("rotate", bye.data().get("reason").getAsString());
        long took = System.currentTimeMillis() - t0;
        assertTrue(took >= ROTATE_MS - 100 && took < ROTATE_MS + 1500, "bye after " + took + " ms");

        Stream r = stream(rita, "b1", vs(2));
        Stream p = stream(pete, "b1", vs(3));
        assertNotNull(r.next("hello", 2000));
        assertNotNull(p.next("hello", 2000));
        assertEquals(200, post(owner, "/api/v1/users/" + id, "{\"disabled\":true}").status);
        assertEquals("revoked", r.next("bye", 2000).data().get("reason").getAsString(), "a disabled user");
        assertEquals(200, post(owner, "/api/v1/users/" + id2 + "/password", "{\"password\":\"another-password-9\"}").status);
        assertEquals("revoked", p.next("bye", 2000).data().get("reason").getAsString(), "a changed password");
    }

    @Test
    void botNextWakesOnWatchAndStopsAfterTheLinger() throws Exception {
        BotHub.Bot b = hub.bot("b1");
        BotHub.Poll first = hub.next(b); // the first poll always says where things stand
        assertFalse(first.body().get("watchingViewer").getAsBoolean());
        assertFalse(first.body().has("viewer"));
        String owner = login(null, OWNER_PW);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<BotHub.Poll> waiting = ex.submit(() -> hub.next(b));
            Thread.sleep(300);
            long t0 = System.currentTimeMillis();
            Stream s = stream(owner, "b1", vs(1));
            BotHub.Poll woke = waiting.get(3, TimeUnit.SECONDS);
            assertTrue(System.currentTimeMillis() - t0 < 2000, "woken at once");
            assertTrue(woke.body().get("watchingViewer").getAsBoolean());
            JsonObject v = woke.body().getAsJsonObject("viewer");
            assertEquals(8, v.get("radius").getAsInt());
            assertEquals(5, v.get("hz").getAsInt());
            assertTrue(v.get("session").getAsString().matches("[0-9a-f]{16}"));

            // The stream ends (rotate) and nobody reconnects: watchingViewer turns false within 11 s.
            Future<BotHub.Poll> stopping = ex.submit(() -> {
                while (true) {
                    BotHub.Poll p = hub.next(b);
                    if (p.status() == 200 && !p.body().get("watchingViewer").getAsBoolean()) return p;
                }
            });
            assertNotNull(s.next("bye", ROTATE_MS + 2000));
            long closed = System.currentTimeMillis();
            BotHub.Poll stopped = stopping.get(13, TimeUnit.SECONDS);
            long after = System.currentTimeMillis() - closed;
            assertFalse(stopped.body().has("viewer"));
            assertTrue(after <= 11_000, "watching ended " + after + " ms after the stream closed");
            Res frame = botPost("/api/v1/bot/viewer", full("b1", v.get("session").getAsString(), 1));
            assertFalse(frame.obj().get("watching").getAsBoolean(), "frames after that are told to stop");
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void aPollFromBeforeHelloDoesNotTakeTheWatchChange() throws Exception {
        BotHub.Bot b = hub.bot("b1");
        hub.next(b); // the first poll answers at once
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            // The bot's previous run left a long-poll waiting; the restarted bot says hello and polls again.
            Future<BotHub.Poll> dangling = ex.submit(() -> hub.next(b));
            Thread.sleep(200);
            assertEquals(200, botPost("/api/v1/bot/hello", "{\"botId\":\"b1\",\"player\":\"Bob\"}").status);
            assertEquals(204, dangling.get(2, TimeUnit.SECONDS).status(), "the old poll gives up at the hello");
            hub.next(b); // the new run's first poll: where things stand
            Future<BotHub.Poll> fresh = ex.submit(() -> hub.next(b));
            Thread.sleep(200);
            String owner = login(null, OWNER_PW);
            assertEquals(200, stream(owner, "b1", vs(1)).status);
            assertTrue(fresh.get(3, TimeUnit.SECONDS).body().get("watchingViewer").getAsBoolean(), "the new run is told");
        } finally {
            ex.shutdownNow();
        }
    }

    // ---------- the hub on its own ----------

    static ViewerHub.Frame frame(String json) throws ViewerHub.BadFrame {
        return ViewerHub.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void coalescingKeepsTheNewestAndBoundsMemory() throws Exception {
        ViewerHub.Live live = new ViewerHub.Live("b1", 0);
        ViewerHub.Client c = new ViewerHub.Client("owner", vs(1), "b1");
        live.clients.add(c);
        String s = "00000000000000aa";
        ViewerHub.apply(live, frame(full("b1", s, 1)), 1);
        assertTrue(c.needSnapshot);
        ViewerHub.drain(live, c, 2);
        assertFalse(c.hasWork());

        // The same tile three times, then dropped and sent again: one pending tile, the newest.
        ViewerHub.apply(live, frame(delta("b1", s, 2, "\"tiles\":[" + tile(1, 1, 3) + "]")), 3);
        ViewerHub.apply(live, frame(delta("b1", s, 3, "\"tiles\":[" + tile(1, 1, 4) + "]")), 4);
        ViewerHub.apply(live, frame(delta("b1", s, 4, "\"drop\":[[1,1]]")), 5);
        ViewerHub.apply(live, frame(delta("b1", s, 5, "\"tiles\":[" + tile(1, 1, 6) + "]")), 6);
        assertEquals(1, c.tiles.size());
        assertTrue(c.drops.isEmpty());
        assertEquals(b64(256, 6), c.tiles.values().iterator().next().c);
        // Entities and HUD: latest only.
        ViewerHub.apply(live, frame(delta("b1", s, 6, "\"ents\":[{\"id\":1,\"k\":\"mob\",\"t\":\"zombie\",\"x\":1,\"y\":2,\"z\":3}]")), 7);
        ViewerHub.apply(live, frame(delta("b1", s, 7, "\"ents\":[{\"id\":2,\"k\":\"other\",\"x\":1,\"y\":2,\"z\":3}]")), 8);
        List<ViewerHub.Ev> out = ViewerHub.drain(live, c, 9);
        assertEquals(2, out.size(), "one tiles and one ents event");
        assertTrue(out.get(1).text().contains("\"id\":2") && !out.get(1).text().contains("zombie"));
        assertFalse(c.hasWork());

        // A reader that never drains: over 2 MiB pending, the queues go and one snapshot is due instead.
        String c64 = b64(256, 7);
        String h64 = b64(512, 0);
        for (int i = 0; i < 4000 && !c.needSnapshot; i++) {
            c.queueTile(new ViewerHub.Tile(i, 0, c64, h64));
            assertTrue(c.pendingBytes <= ViewerHub.MAX_PENDING, "never holds more than 2 MiB");
        }
        assertTrue(c.needSnapshot);
        assertTrue(c.tiles.isEmpty());
        assertEquals(0, c.pendingBytes);
        List<ViewerHub.Ev> snap = ViewerHub.drain(live, c, 100);
        assertEquals(1, snap.size());
        assertTrue(snap.get(0).text().startsWith("event: snapshot\n"));

        // Tiles far from the bot are ignored; past 1024 tiles the farthest go and every stream gets a snapshot.
        long seq = 10;
        ViewerHub.apply(live, frame(delta("b1", s, seq++, "\"tiles\":[" + tile(5000, 5000, 1) + "],\"drop\":[[7,-6]]")), 50);
        assertFalse(live.tiles.containsKey(ViewerHub.Tile.key(5000, 5000)), "a tile 80,000 blocks from the bot");
        assertFalse(live.tiles.containsKey(ViewerHub.Tile.key(7, -6)), "a near drop applies");
        ViewerHub.drain(live, c, 51);
        boolean capped = false;
        for (int f = 0; f < 4; f++) {
            int bx = f * 400; // the bot walks 400 blocks per frame and sends the 21 x 21 tiles around it, no drops
            StringBuilder tiles = new StringBuilder();
            int n = 0;
            for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) tiles.append(n++ == 0 ? "" : ",").append(tile(bx / 16 + x, z, 2));
            ViewerHub.apply(live, frame(delta("b1", s, seq++, "\"self\":{\"x\":" + bx + ",\"y\":64,\"z\":0,\"yaw\":0},\"tiles\":[" + tiles + "]")), 60 + f);
            assertTrue(live.tiles.size() <= ViewerHub.MAX_LIVE_TILES);
            if (c.needSnapshot) capped = true;
        }
        assertTrue(capped, "the cap forced a snapshot");
        assertTrue(live.tiles.containsKey(ViewerHub.Tile.key(1200 / 16, 0)), "the tiles near the bot stay");
        assertFalse(live.tiles.containsKey(ViewerHub.Tile.key(-10, 0)), "the farthest went");
        ViewerHub.drain(live, c, 70);

        // A full frame replaces the state; later frames add tiles to it (a full frame needn't cover the radius).
        ViewerHub.apply(live, frame(full("b1", s, 1000)), 101);
        assertEquals(1, live.tiles.size());
        ViewerHub.apply(live, frame(delta("b1", s, 1001, "\"tiles\":[" + tile(2, 2, 1) + "]")), 102);
        assertEquals(2, live.tiles.size());
    }

    @Test
    void auditClosesAViewerPageAfter15sWithoutAStream() {
        AtomicLong now = new AtomicLong(1_000_000);
        BotHub bh = new BotHub(now::get);
        bh.hello("b1", "Bob", new JsonObject(), new JsonObject(), List.of());
        AuditLog log = new AuditLog(null, now::get);
        try (ViewerHub vh = new ViewerHub(bh, log, ROTATE_MS, HEARTBEAT_MS, now::get)) {
            Users.Principal who = new Users.Principal("owner", "owner", true, Users.Perms.ALL, false);
            BotHub.Bot b = bh.bot("b1");
            ViewerHub.Opened o1 = vh.open(who, vs(1), b, "127.0.0.1");
            vh.close(o1.client(), o1.live(), b);
            now.addAndGet(5000);
            ViewerHub.Opened o2 = vh.open(who, vs(1), b, "127.0.0.1"); // a reconnect: no second open
            now.addAndGet(20_000);
            vh.sweep();
            assertEquals(1, log.size(), "still open while it has a stream");
            vh.close(o2.client(), o2.live(), b);
            now.addAndGet(14_000);
            vh.sweep();
            assertEquals(1, log.size());
            now.addAndGet(1_000);
            vh.sweep();
            assertEquals(2, log.size());
            assertEquals("viewer.close", log.newest(1).get(0).getAsJsonObject().get("action").getAsString());
            // The Live goes 60 s after its last stream.
            assertNotNull(vh.live("b1"));
            now.addAndGet(60_000);
            vh.sweep();
            assertNull(vh.live("b1"));
        }
    }

    @Test
    void toleratesAMissingHudAndUppercaseIds() throws Exception {
        String noHud = full("b1", "00000000000000aa", 1).replaceAll(",\"hud\":\\{.*?\\}\\},\"chat\"", ",\"chat\"");
        assertFalse(noHud.contains("\"hud\""));
        assertNull(frame(noHud).hud());
        ViewerHub.Frame f = frame(full("b1", "00000000000000aa", 1).replace("minecraft:overworld", "Minecraft:Overworld")
            .replace("minecraft:diamond_sword", "MINECRAFT:Diamond_Sword"));
        assertEquals("minecraft:overworld", f.world().get("dim").getAsString());
        assertEquals("minecraft:diamond_sword", f.hud().getAsJsonArray("hotbar").get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    void aHugeSeqJumpResyncs() throws Exception {
        String owner = login(null, OWNER_PW);
        Stream s = stream(owner, "b1", vs(1));
        assertNotNull(s.next("hello", 2000));
        String session = hub.viewerSession(hub.bot("b1"));
        assertEquals(200, botPost("/api/v1/bot/viewer", full("b1", session, 5)).status);
        assertTrue(botPost("/api/v1/bot/viewer", delta("b1", session, 5 + ViewerHub.MAX_SEQ_JUMP + 1, "")).obj().get("resync").getAsBoolean());
        assertFalse(botPost("/api/v1/bot/viewer", delta("b1", session, 6, "")).obj().get("resync").getAsBoolean());
    }

    @Test
    void viewerOnlyUsersGetIcons() throws Exception {
        String owner = login(null, OWNER_PW);
        createUser(owner, "eve", "{\"viewer\":true}");
        createUser(owner, "ned", "{\"activity\":true}");
        assertTrue(get(login("eve", TEMP_PW), "/icon/minecraft/diamond_sword.png").status != 403, "viewer is enough for icons");
        assertEquals(403, get(login("ned", TEMP_PW), "/icon/minecraft/diamond_sword.png").status);
    }

    @Test
    void opensAreRateLimitedAndAuditedOncePer15Minutes() {
        AtomicLong now = new AtomicLong(1_000_000);
        BotHub bh = new BotHub(now::get);
        bh.hello("b1", "Bob", new JsonObject(), new JsonObject(), List.of());
        AuditLog log = new AuditLog(null, now::get);
        try (ViewerHub vh = new ViewerHub(bh, log, ROTATE_MS, HEARTBEAT_MS, now::get)) {
            vh.opensPerMinute = 3;
            vh.newVsPerHour = 4;
            Users.Principal who = new Users.Principal("owner", "owner", true, Users.Perms.ALL, false);
            BotHub.Bot b = bh.bot("b1");
            for (int i = 0; i < 3; i++) {
                ViewerHub.Opened o = vh.open(who, vs(i), b, "127.0.0.1");
                assertNull(o.refused());
                vh.close(o.client(), o.live(), b);
                now.addAndGet(1000);
            }
            assertNotNull(vh.open(who, vs(9), b, "127.0.0.1").refused(), "a 4th open within a minute");
            now.addAndGet(60_000);
            ViewerHub.Opened o4 = vh.open(who, vs(3), b, "127.0.0.1");
            assertNull(o4.refused());
            vh.close(o4.client(), o4.live(), b);
            now.addAndGet(60_000);
            assertNotNull(vh.open(who, vs(4), b, "127.0.0.1").refused(), "a 5th new page within an hour");
            assertNull(vh.open(who, vs(1), b, "127.0.0.1").refused(), "a page seen before is fine");
            long opensAudited = 0;
            for (JsonElement e : log.newest(100)) if (e.getAsJsonObject().get("action").getAsString().equals("viewer.open")) opensAudited++;
            assertEquals(1, opensAudited, "one viewer.open per user and bot per 15 minutes");
        }
    }

    @Test
    void parseCleansAndCaps() throws Exception {
        ViewerHub.Frame f = frame(delta("b1", "00000000000000aa", 1,
            "\"ents\":[{\"id\":9,\"k\":\"item\",\"t\":\"minecraft:diamond\",\"c\":3,\"x\":1.234,\"y\":2,\"z\":3,\"evil\":\"x\"}]"));
        JsonObject e = f.ents().get(0).getAsJsonObject();
        assertEquals(1.2, e.get("x").getAsDouble());
        assertFalse(e.has("evil"), "only known fields are kept");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 21; i++) many.append(i == 0 ? "" : ",").append("{\"t\":1,\"k\":\"public\",\"s\":\"a\",\"m\":\"x\"}");
        try {
            frame(delta("b1", "00000000000000aa", 1, "\"chat\":[" + many + "]"));
            throw new AssertionError("21 chat lines in a delta");
        } catch (ViewerHub.BadFrame expected) {
            // fine
        }
        JsonArray none = new JsonArray();
        assertEquals(none, frame(delta("b1", "00000000000000aa", 1, "\"ents\":[]")).ents());
    }

    /** A HUD with a 27-slot main inventory: slot 0 a damaged pickaxe, slot 26 a stack of dirt. */
    static String invHud(String inv) {
        return HUD.substring(0, HUD.length() - 1) + ",\"inv\":" + inv + "}";
    }

    static String inv27(String first, String last) {
        StringBuilder b = new StringBuilder("[").append(first);
        for (int i = 1; i < 26; i++) b.append(",null");
        return b.append(",").append(last).append("]").toString();
    }

    static void bad(String json, String why) {
        try {
            frame(json);
            throw new AssertionError(why);
        } catch (ViewerHub.BadFrame expected) {
            // fine
        }
    }

    @Test
    void hudInventoryAndDurabilityAreAccepted() throws Exception {
        String inv = inv27("{\"id\":\"minecraft:diamond_pickaxe\",\"c\":1,\"d\":120,\"md\":1561,\"name\":\"Bob's pick\"}",
            "{\"id\":\"minecraft:dirt\",\"c\":64}");
        String hud = invHud(inv).replace("{\"id\":\"minecraft:diamond_sword\",\"c\":1}",
            "{\"id\":\"minecraft:diamond_sword\",\"c\":1,\"d\":0,\"md\":1561}");
        JsonObject h = frame(delta("b1", "00000000000000aa", 1, "\"hud\":" + hud)).hud();
        JsonArray a = h.getAsJsonArray("inv");
        assertEquals(27, a.size());
        JsonObject pick = a.get(0).getAsJsonObject();
        assertEquals(120, pick.get("d").getAsInt());
        assertEquals(1561, pick.get("md").getAsInt());
        assertFalse(pick.has("name"), "no custom names");
        assertTrue(a.get(1).isJsonNull());
        JsonObject dirt = a.get(26).getAsJsonObject();
        assertEquals(64, dirt.get("c").getAsInt());
        assertFalse(dirt.has("d") || dirt.has("md"), "no durability for undamageable items");
        JsonObject sword = h.getAsJsonArray("hotbar").get(0).getAsJsonObject();
        assertEquals(0, sword.get("d").getAsInt());
        assertEquals(1561, sword.get("md").getAsInt());
    }

    @Test
    void hudInventoryAndDurabilityAreValidated() {
        StringBuilder short26 = new StringBuilder("[null");
        for (int i = 1; i < 26; i++) short26.append(",null");
        bad(delta("b1", "00000000000000aa", 1, "\"hud\":" + invHud(short26 + "]")), "26 inventory slots");
        bad(delta("b1", "00000000000000aa", 1, "\"hud\":" + invHud(short26 + ",null,null]")), "28 inventory slots");
        bad(delta("b1", "00000000000000aa", 1, "\"hud\":" + invHud("{}")), "inv not a list");
        String pick = "{\"id\":\"minecraft:diamond_pickaxe\",\"c\":1,";
        bad(delta("b1", "00000000000000aa", 1, "\"hud\":" + invHud(inv27(pick + "\"d\":1562,\"md\":1561}", "null"))), "d above md");
        bad(delta("b1", "00000000000000aa", 1, "\"hud\":" + invHud(inv27(pick + "\"d\":-1,\"md\":1561}", "null"))), "negative d");
        bad(delta("b1", "00000000000000aa", 1, "\"hud\":" + invHud(inv27(pick + "\"d\":1,\"md\":0}", "null"))), "md 0");
        bad(delta("b1", "00000000000000aa", 1, "\"hud\":" + invHud(inv27(pick + "\"d\":1,\"md\":100001}", "null"))), "md too big");
        bad(delta("b1", "00000000000000aa", 1, "\"hud\":" + invHud(inv27(pick + "\"d\":1.5,\"md\":1561}", "null"))), "fractional d");
        bad(delta("b1", "00000000000000aa", 1, "\"hud\":" + invHud(inv27(pick + "\"d\":1}", "null"))), "d without md");
        bad(delta("b1", "00000000000000aa", 1, "\"hud\":" + HUD.replace("totem_of_undying\",\"c\":1}", "totem_of_undying\",\"c\":1,\"md\":5}")), "md without d (offhand)");
        bad(delta("b1", "00000000000000aa", 1, "\"hud\":" + HUD.replace("{\"id\":\"minecraft:diamond_sword\",\"c\":1}",
            "{\"id\":\"minecraft:diamond_sword\",\"c\":1,\"d\":\"5\",\"md\":1561}")), "d as text (hotbar)");
    }

    @Test
    void anOldHudWithoutInventoryIsStillFine() throws Exception {
        JsonObject h = frame(full("b1", "00000000000000aa", 1)).hud();
        assertFalse(h.has("inv"));
        assertEquals(9, h.getAsJsonArray("hotbar").size());
        assertFalse(h.getAsJsonArray("hotbar").get(0).getAsJsonObject().has("d"));
        assertTrue(frame(delta("b1", "00000000000000aa", 1, "\"hud\":" + invHud("null"))).hud().has("hotbar"), "inv: null is the same as missing");
        assertFalse(frame(delta("b1", "00000000000000aa", 1, "\"hud\":" + invHud("null"))).hud().has("inv"));
    }

    @Test
    void forcedStreamerHudKeepsTheInventory() throws Exception {
        String hud = invHud(inv27("{\"id\":\"minecraft:diamond_pickaxe\",\"c\":1,\"d\":120,\"md\":1561}", "null"));
        JsonObject parsed = frame(delta("b1", "00000000000000aa", 1, "\"hud\":" + hud)).hud();
        JsonObject data = new JsonObject();
        data.addProperty("seq", 1);
        data.add("hud", parsed);
        Redact.ViewerMask m = new Redact.ViewerMask();
        ViewerHub.Ev out = m.apply(new ViewerHub.Ev("hud", null, data));
        assertNotNull(out);
        JsonObject inv0 = out.data().getAsJsonObject().getAsJsonObject("hud").getAsJsonArray("inv").get(0).getAsJsonObject();
        assertEquals(120, inv0.get("d").getAsInt());
        // Every key a parsed hud item can have: an id, a count and durability; nothing positional, no names.
        for (JsonElement e : parsed.getAsJsonArray("inv")) {
            if (e.isJsonNull()) continue;
            for (String k : e.getAsJsonObject().keySet()) assertTrue(List.of("id", "c", "d", "md").contains(k), k);
        }
        // The snapshot route of the mask carries the hud too.
        JsonObject snap = new JsonObject();
        snap.addProperty("session", "00000000000000aa");
        snap.add("hud", parsed);
        ViewerHub.Ev s = m.apply(new ViewerHub.Ev("snapshot", null, snap));
        assertNotNull(s);
        assertEquals(27, s.data().getAsJsonObject().getAsJsonObject("hud").getAsJsonArray("inv").size());
    }

    @Test
    void anInventoryChangeReachesTheStream() throws Exception {
        String owner = login(null, OWNER_PW);
        Stream s = stream(owner, "b1", vs(1));
        assertNotNull(s.next("hello", 2000));
        String session = hub.viewerSession(hub.bot("b1"));
        assertEquals(200, botPost("/api/v1/bot/viewer", full("b1", session, 1)).status);
        assertNotNull(s.next("snapshot", 2000));
        String hud = invHud(inv27("{\"id\":\"minecraft:dirt\",\"c\":3}", "null"));
        assertEquals(200, botPost("/api/v1/bot/viewer", delta("b1", session, 2, "\"hud\":" + hud)).status);
        JsonObject got = s.next("hud", 2000).data().getAsJsonObject("hud");
        assertEquals(3, got.getAsJsonArray("inv").get(0).getAsJsonObject().get("c").getAsInt());
    }

    // ---------- a bot that becomes a farmer (FARM-CONTRACT P5c) ----------

    @Test
    void anOpenStreamEndsWhenItsBotBecomesAFarmer() throws Exception {
        String owner = login(null, OWNER_PW);
        assertEquals(200, botPost("/api/v1/bot/viewer", full("b1", vs(1), 1)).status);
        Stream s = stream(owner, "b1", vs(7));
        assertNotNull(s.next("hello", 2000));
        tasks.setRoles("b1", List.of("farmer"));
        Event bye = s.next("bye", 3000);
        assertNotNull(bye, "the stream re-checks the farmer status");
        assertEquals("revoked", bye.data().get("reason").getAsString());
    }
}
