package com.porter.stashmanager;

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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Force streamer mode" on a real server: a forced sub-user gets the redacted form of every sensitive endpoint (checked
 * on the raw JSON and the raw SSE text), the owner and an unforced user the full data.
 */
class StreamerTest {
    static final String OWNER_PW = "owner-password-123";
    static final String PW = "temporary-pass-1";
    static final String FULL_PERMS = "\"view\":true,\"order\":true,\"activity\":true,\"settings\":true,\"proxy\":\"read\","
        + "\"console\":\"full\",\"link\":true,\"viewer\":true";

    @TempDir
    Path data;
    HttpServer server;
    String base;
    byte[] k;
    BotHub hub;
    ViewerHub viewer;
    Router router;
    final HttpClient http = HttpClient.newHttpClient();
    final List<InputStream> open = new ArrayList<>();
    final ExecutorService readers = Executors.newCachedThreadPool();
    String owner;
    String fay; // forced
    String uma; // not forced
    String umaId;

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
        server.setExecutor(Executors.newFixedThreadPool(16));
        router = r.router();
        server.createContext("/", router);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        // The bot's data: index, heartbeat with a delivery container, a dashboard bot with activity.
        ok(botPost("/api/v1/index", "{\"version\":1,\"server\":\"2b2t.org\",\"stash\":\"a\",\"player\":\"Bobby\",\"dimension\":\"minecraft:the_nether\","
            + "\"items\":[{\"key\":\"minecraft:dirt\",\"id\":\"minecraft:dirt\",\"name\":\"Steve's Dirt\",\"englishName\":\"Dirt\","
            + "\"details\":[\"by Steve\"],\"count\":1000,\"maxStack\":64}],"
            + "\"shulkers\":[{\"key\":\"s:abc\",\"id\":\"minecraft:red_shulker_box\",\"itemKey\":\"minecraft:red_shulker_box\","
            + "\"name\":\"Steve's Kit\",\"count\":1,\"contents\":[{\"slot\":0,\"key\":\"minecraft:dirt\",\"count\":1}]}]}"));
        ok(botPost("/api/v1/heartbeat", "{\"server\":\"2b2t.org\",\"stash\":\"a\",\"player\":\"Bobby\","
            + "\"delivery\":{\"x\":1234,\"y\":64,\"z\":-5678,\"dimension\":\"minecraft:the_nether\"},\"orderBlocker\":\"chest full at 1234, 64, -5678\"}"));
        ok(botPost("/api/v1/bot/hello", "{\"botId\":\"b1\",\"player\":\"Bobby\",\"proxy\":{\"version\":\"aq-1\",\"mc\":\"1.21.4\"},\"caps\":[\"settings\",\"console\",\"activity\"]}"));
        long now = System.currentTimeMillis();
        JsonObject raw = JsonParser.parseString("{\"at\":" + now + ",\"connection\":{\"state\":\"online\",\"server\":\"2b2t.org\","
            + "\"dimension\":\"minecraft:the_nether\",\"x\":1234,\"y\":64,\"z\":-5678,\"controllingPlayer\":\"Steve\",\"health\":20},"
            + "\"current\":[{\"source\":\"Pathfinder\",\"kind\":\"process\",\"title\":\"Walking to 1234, 64, -5678\",\"detail\":\"near Steve\"}],"
            + "\"modules\":[{\"name\":\"AutoEat\",\"enabled\":true,\"status\":\"at 1234 64 -5678\"}],"
            + "\"events\":[{\"at\":" + now + ",\"source\":\"chat\",\"text\":\"Steve joined at 1234 64 -5678\"}]}").getAsJsonObject();
        hub.activity(hub.bot("b1"), Activity.clean(raw, now), Activity.events(raw, now));

        owner = login(null, OWNER_PW);
        createUser("fay", "{" + FULL_PERMS + ",\"streamer\":true}");
        umaId = createUser("uma", "{" + FULL_PERMS + "}");
        fay = login("fay", PW);
        uma = login("uma", PW);
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

    String createUser(String name, String perms) throws Exception {
        Res r = post(owner, "/api/v1/users", "{\"name\":\"" + name + "\",\"password\":\"" + PW + "\",\"mustChange\":false,\"perms\":" + perms + "}");
        ok(r);
        return r.obj().getAsJsonObject("user").get("id").getAsString();
    }

    record Event(String name, String data) {
        JsonObject obj() {
            return JsonParser.parseString(data).getAsJsonObject();
        }
    }

    /** An SSE stream as raw events (name, raw data line); a null-named event at the end. */
    BlockingQueue<Event> stream(String token, String bot, String vs) throws Exception {
        HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/bots/" + bot + "/viewer/stream?vs=" + vs)).GET()
            .header("Cookie", SessionStore.COOKIE + "=" + token).build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, r.statusCode());
        InputStream in = r.body();
        open.add(in);
        BlockingQueue<Event> q = new LinkedBlockingQueue<>();
        readers.submit(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String name = null;
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("event: ")) name = line.substring(7);
                    else if (line.startsWith("data: ") && name != null) {
                        q.add(new Event(name, line.substring(6)));
                        name = null;
                    }
                }
            } catch (Exception ignored) {
            }
            q.add(new Event(null, null));
            return null;
        });
        return q;
    }

    static Event next(BlockingQueue<Event> q, String name, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            long left = end - System.currentTimeMillis();
            Event e = left <= 0 ? null : q.poll(left, TimeUnit.MILLISECONDS);
            if (e == null || e.name == null) return null;
            if (e.name.equals(name)) return e;
        }
    }

    /** Every event that arrives within ms. */
    static List<Event> drainFor(BlockingQueue<Event> q, long ms) throws InterruptedException {
        List<Event> out = new ArrayList<>();
        long end = System.currentTimeMillis() + ms;
        while (true) {
            long left = end - System.currentTimeMillis();
            Event e = left <= 0 ? null : q.poll(left, TimeUnit.MILLISECONDS);
            if (e == null || e.name == null) return out;
            out.add(e);
        }
    }

    static String b64(int n) {
        return Base64.getEncoder().encodeToString(new byte[n]);
    }

    static String tile(int x, int z) {
        return "{\"x\":" + x + ",\"z\":" + z + ",\"c\":\"" + b64(256) + "\",\"h\":\"" + b64(512) + "\"}";
    }

    static final String HUD = "{\"hp\":18.5,\"food\":17,\"hotbar\":[null,null,null,null,null,null,null,null,null]}";

    // ---------- REST ----------

    /** The forced user's opaque id for the one stash's server, from /stashes. */
    String sid() throws Exception {
        return JsonParser.parseString(get(fay, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().get("server").getAsString();
    }

    String bid(String token) throws Exception {
        return JsonParser.parseString(get(token, "/api/v1/bots").body).getAsJsonArray().get(0).getAsJsonObject().get("botId").getAsString();
    }

    @Test
    void stashesIndexAndDelivery() throws Exception {
        String forced = get(fay, "/api/v1/stashes").body;
        assertFalse(forced.contains("1234") || forced.contains("-5678") || forced.contains("Bobby") || forced.contains("the_nether")
            || forced.contains("2b2t"), forced);
        JsonObject f = JsonParser.parseString(forced).getAsJsonArray().get(0).getAsJsonObject();
        assertTrue(f.get("delivery").isJsonNull());
        assertTrue(f.get("player").isJsonNull() && f.get("owner").isJsonNull());
        assertEquals("Server 1", f.get("serverLabel").getAsString());
        String sid = f.get("server").getAsString();
        assertEquals(12, sid.length(), "an opaque id instead of the address");
        assertEquals(Redact.HIDDEN, f.get("orderBlocker").getAsString());
        for (String t : new String[]{owner, uma}) {
            JsonObject full = JsonParser.parseString(get(t, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject();
            assertEquals(1234, full.getAsJsonObject("delivery").get("x").getAsInt());
            assertEquals("Bobby", full.get("player").getAsString());
            assertEquals("2b2t.org", full.get("server").getAsString());
        }

        Res ir = get(fay, "/api/v1/index?server=" + sid + "&stash=a");
        assertEquals(200, ir.status);
        String idx = ir.body;
        assertFalse(idx.contains("Bobby") || idx.contains("the_nether") || idx.contains("2b2t") || idx.contains("Steve"), idx);
        JsonObject io = JsonParser.parseString(idx).getAsJsonObject();
        JsonObject dirt = io.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("Dirt", dirt.get("name").getAsString(), "the English name, not the custom one");
        assertEquals(0, dirt.getAsJsonArray("details").size());
        assertEquals("Red Shulker Box", io.getAsJsonArray("shulkers").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(404, get(fay, "/api/v1/index?server=2b2t.org&stash=a").status, "a raw address is not accepted from a forced user");
        String full = get(uma, "/api/v1/index?server=2b2t.org&stash=a").body;
        assertTrue(full.contains("Bobby") && full.contains("Steve's Dirt") && full.contains("Steve's Kit"));

        String report = "{\"version\":1,\"server\":\"2b2t.org\",\"stash\":\"a\",\"player\":\"Bobby\",\"hash\":\"" + "a".repeat(40)
            + "\",\"readAt\":1,\"chests\":[{\"n\":0,\"size\":27,\"full\":false,\"readAt\":1,\"items\":["
            + "{\"slot\":0,\"id\":\"minecraft:red_shulker_box\",\"name\":\"Steve's loot\",\"count\":1,\"maxStack\":1}]}]}";
        ok(botPost("/api/v1/delivery", report));
        String d = get(fay, "/api/v1/delivery?server=" + sid + "&stash=a").body;
        assertFalse(d.contains("Bobby") || d.contains("Steve") || d.contains("2b2t"), d);
        assertTrue(d.contains("Red Shulker Box"));
        assertEquals(404, get(fay, "/api/v1/delivery?server=2b2t.org&stash=a").status);
        assertTrue(get(uma, "/api/v1/delivery?server=2b2t.org&stash=a").body.contains("Steve's loot"));
    }

    @Test
    void ordersHideOtherUsersAndAddresses() throws Exception {
        ok(post(uma, "/api/v1/orders", "{\"server\":\"2b2t.org\",\"stash\":\"a\",\"names\":{\"mode\":\"all\",\"name\":\"Uma's kit\"},"
            + "\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":1}]}"));
        String sid = sid();
        assertEquals(404, post(fay, "/api/v1/orders", "{\"server\":\"2b2t.org\",\"stash\":\"a\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":1}]}").status);
        Res mine = post(fay, "/api/v1/orders", "{\"server\":\"" + sid + "\",\"stash\":\"a\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":1}]}");
        ok(mine);
        assertEquals("fay", mine.obj().getAsJsonObject("order").getAsJsonObject("placedBy").get("name").getAsString());
        assertEquals(sid, mine.obj().getAsJsonObject("order").get("server").getAsString());
        String forced = get(fay, "/api/v1/orders?server=" + sid + "&stash=a").body;
        assertFalse(forced.contains("\"uma\"") || forced.contains("Uma's kit") || forced.contains("2b2t"), forced);
        assertTrue(forced.contains(Redact.ANOTHER_USER) && forced.contains("\"fay\""));
        String full = get(uma, "/api/v1/orders?server=2b2t.org&stash=a").body;
        assertTrue(full.contains("\"fay\"") && full.contains("Uma's kit"));
    }

    @Test
    void botsActivityAndLink() throws Exception {
        String bots = get(fay, "/api/v1/bots").body;
        assertFalse(bots.contains("Bobby") || bots.contains("aq-1") || bots.contains("1234") || bots.contains("\"b1\""), bots);
        JsonObject b = JsonParser.parseString(bots).getAsJsonArray().get(0).getAsJsonObject();
        assertTrue(b.get("player").isJsonNull() && b.get("summary").isJsonNull());
        assertEquals(0, b.getAsJsonObject("proxy").size());
        String bid = b.get("botId").getAsString();
        assertTrue(bid.startsWith("b") && bid.length() == 13, bid);
        assertTrue(get(uma, "/api/v1/bots").body.contains("Bobby"));

        assertEquals(404, get(fay, "/api/v1/bots/b1/activity").status, "a raw bot id is not accepted from a forced user");
        String act = get(fay, "/api/v1/bots/" + bid + "/activity").body;
        assertFalse(act.contains("1234") || act.contains("5678") || act.contains("Steve") || act.contains("the_nether")
            || act.contains("2b2t") || act.contains("AutoEat") || act.contains("chat"), act);
        JsonObject a = JsonParser.parseString(act).getAsJsonObject().getAsJsonObject("activity");
        assertEquals("online", a.getAsJsonObject("connection").get("state").getAsString());
        assertEquals("Pathfinder", a.getAsJsonArray("current").get(0).getAsJsonObject().get("source").getAsString(), "a known source");
        assertEquals(0, a.getAsJsonArray("modules").size());
        assertEquals(Redact.HIDDEN, JsonParser.parseString(act).getAsJsonObject().getAsJsonArray("events").get(0).getAsJsonObject()
            .get("source").getAsString(), "an unknown source");
        String full = get(uma, "/api/v1/bots/b1/activity").body;
        assertTrue(full.contains("Walking to 1234, 64, -5678") && full.contains("Steve"));

        String link = get(fay, "/api/v1/link").body;
        assertFalse(link.contains("192.168.1.50") || link.contains("Bobby") || link.contains("2b2t"), link);
        assertTrue(get(uma, "/api/v1/link").body.contains("192.168.1.50"));
        assertEquals(200, get(fay, "/api/v1/bots/" + bid + "/viewer").status);
    }

    @Test
    void settingsConsoleAndProxyAre403() throws Exception {
        String bid = bid(fay);
        String[] gets = {"/settings", "/settings?scope=proxy", "/console", "/jobs?ids=x"};
        for (String p : gets) {
            Res r = get(fay, "/api/v1/bots/" + bid + p);
            assertEquals(403, r.status, p);
            assertEquals("streamer", r.obj().get("permission").getAsString());
        }
        assertEquals(403, post(fay, "/api/v1/bots/" + bid + "/settings", "{\"changes\":[{\"path\":\"x\",\"value\":1}]}").status);
        assertEquals(403, post(fay, "/api/v1/bots/" + bid + "/console", "{\"command\":\"help\"}").status);
        assertNotEquals(403, get(uma, "/api/v1/bots/b1/settings").status, "an unforced user isn't refused");
    }

    @Test
    void authStateNeverShowsAForcedUsersRawScope() throws Exception {
        createUser("sco", "{\"view\":true,\"activity\":true,\"viewer\":true,\"streamer\":true,"
            + "\"stashes\":[{\"server\":\"2b2t.org\",\"stash\":\"a\"}],\"bots\":[\"b1\"]}");
        String sco = login("sco", PW);
        Res st = get(sco, "/api/v1/auth/state");
        assertEquals(200, st.status());
        assertFalse(st.body().contains("2b2t.org"), st.body());
        assertFalse(st.body().contains("\"b1\""), st.body());
        JsonObject perms = st.obj().getAsJsonObject("user").getAsJsonObject("perms");
        assertEquals(1, perms.getAsJsonArray("stashes").size());
        assertEquals(1, perms.getAsJsonArray("bots").size());
        // The same ids the other responses use.
        Res stashes = get(sco, "/api/v1/stashes");
        String sid = perms.getAsJsonArray("stashes").get(0).getAsJsonObject().get("server").getAsString();
        assertTrue(stashes.body().contains(sid), stashes.body());
        // An unforced scoped user still sees the real values.
        createUser("una", "{\"view\":true,\"stashes\":[{\"server\":\"2b2t.org\",\"stash\":\"a\"}],\"bots\":[\"b1\"]}");
        assertTrue(get(login("una", PW), "/api/v1/auth/state").body().contains("2b2t.org"));
    }

    @Test
    void forcedBotsListHasNoPluginDetailsAndTheViewerWorldIsNormalised() throws Exception {
        Res bots = get(fay, "/api/v1/bots");
        assertFalse(bots.body().contains("aq-1"), bots.body());
        JsonObject b = JsonParser.parseString(bots.body()).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(0, b.getAsJsonArray("scopes").size());
        assertEquals(0, b.getAsJsonObject("plugin").size());
    }

    @Test
    void theOwnerIsNeverForcedAndTheFlagIsShown() throws Exception {
        assertFalse(Users.Perms.ALL.streamer());
        Users.Perms withFlag = Users.Perms.parse(JsonParser.parseString("{\"streamer\":true}"));
        assertTrue(withFlag.streamer());
        assertFalse(Redact.forced(new Users.Principal("owner", "o", true, withFlag, false)), "the owner is never forced");
        JsonObject state = get(fay, "/api/v1/auth/state").obj();
        assertTrue(state.getAsJsonObject("user").getAsJsonObject("perms").get("streamer").getAsBoolean());
        assertFalse(get(owner, "/api/v1/auth/state").obj().getAsJsonObject("user").getAsJsonObject("perms").get("streamer").getAsBoolean());
        assertTrue(UserRoutes.describe(withFlag).contains("forced streamer mode"));
        for (JsonElement p : get(owner, "/api/v1/users").obj().getAsJsonObject("presets").entrySet().stream()
            .map(java.util.Map.Entry::getValue).toList()) {
            assertFalse(p.getAsJsonObject().get("streamer").getAsBoolean(), "off in every preset");
        }
    }

    @Test
    void theFlagChangesOnlyExplicitlyAndIsAudited() throws Exception {
        String fayId = JsonParser.parseString(get(owner, "/api/v1/users").body).getAsJsonObject().getAsJsonArray("users").asList().stream()
            .map(JsonElement::getAsJsonObject).filter(u -> u.get("name").getAsString().equals("fay")).findFirst().orElseThrow().get("id").getAsString();
        // A preset, or perms without the key: the flag stays on.
        ok(post(owner, "/api/v1/users/" + fayId, "{\"preset\":\"operator\"}"));
        assertTrue(get(fay, "/api/v1/auth/state").obj().getAsJsonObject("user").getAsJsonObject("perms").get("streamer").getAsBoolean());
        ok(post(owner, "/api/v1/users/" + fayId, "{\"perms\":{\"view\":true}}"));
        assertTrue(get(fay, "/api/v1/auth/state").obj().getAsJsonObject("user").getAsJsonObject("perms").get("streamer").getAsBoolean());
        // Explicitly off, then on: audited as transitions.
        ok(post(owner, "/api/v1/users/" + fayId, "{\"perms\":{\"view\":true,\"streamer\":false}}"));
        assertFalse(get(fay, "/api/v1/auth/state").obj().getAsJsonObject("user").getAsJsonObject("perms").get("streamer").getAsBoolean());
        ok(post(owner, "/api/v1/users/" + fayId, "{\"perms\":{\"view\":true,\"streamer\":true}}"));
        String auditLog = get(owner, "/api/v1/audit").body;
        assertTrue(auditLog.contains("forced streamer mode OFF") && auditLog.contains("forced streamer mode ON"), auditLog);
    }

    @Test
    void cachedResponsesVaryByCookieAndLogoutClearsTheCache() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/index?server=2b2t.org&stash=a")).GET()
            .header("Cookie", SessionStore.COOKIE + "=" + uma).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(r.headers().firstValue("ETag").isPresent());
        assertTrue(r.headers().firstValue("Vary").orElse("").contains("Cookie"), r.headers().map().toString());
        HttpResponse<String> out = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/logout")).header("X-Stash-Manager", "1")
            .header("Content-Type", "application/json").header("Cookie", SessionStore.COOKIE + "=" + uma)
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("\"cache\"", out.headers().firstValue("Clear-Site-Data").orElse(""));
    }

    @Test
    void viewerMaskDeniesUnknownEvents() {
        Redact.ViewerMask m = new Redact.ViewerMask();
        assertEquals(null, m.apply(new ViewerHub.Ev("something-new", null, JsonParser.parseString("{\"x\":1}"))));
        assertEquals(null, m.apply(new ViewerHub.Ev("chat", null, JsonParser.parseString("{\"lines\":[]}"))));
        assertNotNull(m.apply(new ViewerHub.Ev("hud", null, JsonParser.parseString("{\"hud\":{}}"))));
    }

    /**
     * Every logged-in route must be classified for forced streamer mode, so a new one can't slip through: redacted (its
     * answers go through Redact and the opaque-id mask), no data (nothing sensitive in it), forbidden for forced users,
     * or owner only.
     */
    @Test
    void everyLoggedInRouteIsClassified() throws Exception {
        java.util.Set<String> redacted = java.util.Set.of(
            "[GET, HEAD] /api/v1/stashes SESSION VIEW",
            "[GET, HEAD] /api/v1/index SESSION VIEW",
            "[GET, HEAD] /api/v1/link SESSION LINK",
            "[GET, HEAD] /api/v1/bots SESSION ACTIVITY",
            "[GET, HEAD] ^/api/v1/bots/([A-Za-z0-9_-]{1,64})/(settings|jobs|console|activity)$ SESSION ACTIVITY",
            "[GET, HEAD] /api/v1/delivery SESSION VIEW",
            "[GET, HEAD] /api/v1/orders SESSION VIEW",
            "[POST] /api/v1/orders BROWSER_WRITE ORDER",
            "[POST] ^/api/v1/orders/(o-[0-9a-f]{12})/cancel$ BROWSER_WRITE ORDER_OWN_OR_ALL",
            "[POST] ^/api/v1/orders/(o-[0-9a-f]{12})/force-cancel$ BROWSER_WRITE ORDER_OWN_OR_ALL",
            "[POST] ^/api/v1/orders/(o-[0-9a-f]{12})/retry$ BROWSER_WRITE ORDER_OWN_OR_ALL",
            "[GET] ^/api/v1/bots/([A-Za-z0-9_-]{1,64})/viewer/stream$ SESSION VIEWER",
            "[GET, HEAD] ^/api/v1/bots/([A-Za-z0-9_-]{1,64})/viewer$ SESSION VIEWER",
            "[POST] /api/v1/planner/upload BROWSER_UPLOAD PLANNER",
            "[GET, HEAD] /api/v1/planner SESSION PLANNER",
            "[GET, HEAD] ^/api/v1/planner/(p-[0-9a-f]{12})$ SESSION PLANNER",
            "[POST] ^/api/v1/planner/(p-[0-9a-f]{12})/view$ BROWSER_WRITE PLANNER",
            "[POST] ^/api/v1/planner/(p-[0-9a-f]{12})/order$ BROWSER_WRITE PLANNER_ORDER");
        java.util.Set<String> noData = java.util.Set.of(
            "[POST] /api/v1/auth/logout BROWSER_WRITE ANY",
            "[POST] /api/v1/auth/logout-all BROWSER_WRITE ANY",
            "[POST] /api/v1/auth/password BROWSER_WRITE ANY",
            "[POST] /api/v1/auth/confirm BROWSER_WRITE ANY",
            "[POST] /api/v1/auth/lock BROWSER_WRITE ANY",
            "[POST] /api/v1/stashes/release BROWSER_WRITE LINK",
            "[POST] /api/v1/rescan BROWSER_WRITE RESCAN",
            "[POST] /api/v1/orders/clear BROWSER_WRITE ORDERS_ALL",
            "[POST] ^/api/v1/planner/(p-[0-9a-f]{12})/delete$ BROWSER_WRITE PLANNER",
            "[GET, HEAD] /icon/ SESSION ICONS");
        java.util.Set<String> forbidden = java.util.Set.of(
            "[POST] ^/api/v1/bots/([A-Za-z0-9_-]{1,64})/(settings|jobs|console|activity)$ BROWSER_WRITE ACTIVITY",
            // Kits: positions and names a player typed (KIT-CONTRACT.md, "Forced streamer mode"); see KitRoutesTest.
            "[GET, HEAD] /api/v1/kits SESSION VIEW",
            "[POST] /api/v1/kits/chest BROWSER_WRITE SETTINGS",
            "[POST] /api/v1/kits/rule BROWSER_WRITE SETTINGS",
            "[POST] /api/v1/kits/rule/delete BROWSER_WRITE SETTINGS",
            "[POST] /api/v1/kits/scoring BROWSER_WRITE SETTINGS");
        java.util.Set<String> ownerOnly = java.util.Set.of(
            "[POST] /api/v1/link/regenerate BROWSER_WRITE OWNER",
            "[GET, HEAD] /api/v1/users SESSION OWNER",
            "[POST] /api/v1/users BROWSER_WRITE OWNER",
            "[POST] /api/v1/users/owner BROWSER_WRITE OWNER",
            "[POST] ^/api/v1/users/(u-[0-9a-f]{12})$ BROWSER_WRITE OWNER",
            "[POST] ^/api/v1/users/(u-[0-9a-f]{12})/(password|logout|delete)$ BROWSER_WRITE OWNER",
            "[GET, HEAD] /api/v1/audit SESSION OWNER");
        List<String> unclassified = new ArrayList<>();
        for (String r : router.describe()) {
            boolean loggedIn = r.contains(" SESSION ") || r.contains(" BROWSER_WRITE ") || r.contains(" BROWSER_UPLOAD ");
            if (!loggedIn) continue;
            if (!redacted.contains(r) && !noData.contains(r) && !forbidden.contains(r) && !ownerOnly.contains(r)) unclassified.add(r);
        }
        assertTrue(unclassified.isEmpty(), "classify these routes for forced streamer mode (StreamerTest): " + unclassified);
    }

    // ---------- the live viewer ----------

    String fullFrame(String session, long seq) {
        return "{\"botId\":\"b1\",\"v\":1,\"session\":\"" + session + "\",\"seq\":" + seq + ",\"t\":" + System.currentTimeMillis()
            + ",\"full\":true,\"world\":{\"dim\":\"minecraft:the_nether\",\"minY\":0,\"height\":256},"
            + "\"self\":{\"x\":123.4,\"y\":70,\"z\":-88.6,\"yaw\":91},\"tiles\":[" + tile(7, -6) + "],"
            + "\"ents\":[{\"id\":311,\"k\":\"player\",\"n\":\"Steve\",\"x\":130.2,\"y\":64,\"z\":-80.5,\"yaw\":180}],"
            + "\"hud\":" + HUD + ",\"chat\":[{\"t\":1,\"k\":\"public\",\"s\":\"Steve\",\"to\":null,\"m\":\"secret hello\"}]}";
    }

    @Test
    void viewerStreamIsRebasedWithoutNamesChatOrDimension() throws Exception {
        String bid = bid(fay);
        BlockingQueue<Event> q = stream(fay, bid, "00000000000000f1");
        JsonObject hello = next(q, "hello", 2000).obj();
        assertTrue(hello.get("relative").getAsBoolean());
        assertEquals(bid, hello.get("botId").getAsString(), "the opaque bot id");
        String session = hub.viewerSession(hub.bot("b1"));
        ok(botPost("/api/v1/bot/viewer", fullFrame(session, 1)));
        Event snap = next(q, "snapshot", 2000);
        assertNotNull(snap);
        String raw = snap.data();
        assertFalse(raw.contains("Steve") || raw.contains("secret hello") || raw.contains("the_nether"), raw);
        JsonObject d = snap.obj();
        assertEquals("hidden", d.getAsJsonObject("world").get("dim").getAsString());
        assertEquals(0, d.getAsJsonArray("chat").size());
        JsonObject self = d.getAsJsonObject("self");
        JsonObject t = d.getAsJsonArray("tiles").get(0).getAsJsonObject();
        JsonObject ent = d.getAsJsonArray("ents").get(0).getAsJsonObject();
        assertFalse(ent.has("n"));
        assertEquals(70, self.get("y").getAsDouble(), "y is kept");
        // One whole-chunk shift (uniform over +-2^20 chunks) for everything: tiles, self and entities.
        long sx = 7 - t.get("x").getAsLong();
        long sz = -6 - t.get("z").getAsLong();
        assertTrue(Math.abs(sx - 7) <= Redact.OFFSET_CHUNKS && Math.abs(sz + 6) <= Redact.OFFSET_CHUNKS);
        assertEquals(123.4 - sx * 16, self.get("x").getAsDouble(), 0.051);
        assertEquals(-88.6 - sz * 16, self.get("z").getAsDouble(), 0.051);
        assertEquals(130.2 - sx * 16, ent.get("x").getAsDouble(), 0.051);
        assertEquals(-80.5 - sz * 16, ent.get("z").getAsDouble(), 0.051);

        ok(botPost("/api/v1/bot/viewer", "{\"botId\":\"b1\",\"v\":1,\"session\":\"" + session + "\",\"seq\":2,\"t\":" + System.currentTimeMillis()
            + ",\"tiles\":[" + tile(8, -6) + "],\"drop\":[[7,-6]],\"chat\":[{\"t\":2,\"k\":\"whisper\",\"s\":\"Steve\",\"to\":\"Bobby\",\"m\":\"psst\"}]}"));
        List<Event> later = drainFor(q, 1200);
        for (Event e : later) {
            assertNotEquals("chat", e.name(), "no chat events at all");
            assertFalse(e.data().contains("Steve") || e.data().contains("psst"));
        }
        Event tiles = later.stream().filter(e -> e.name().equals("tiles")).findFirst().orElseThrow();
        assertEquals(8 - sx, tiles.obj().getAsJsonArray("tiles").get(0).getAsJsonObject().get("x").getAsLong());
        assertEquals(7 - sx, tiles.obj().getAsJsonArray("drop").get(0).getAsJsonArray().get(0).getAsLong());

        // An unforced user gets the true data.
        BlockingQueue<Event> q2 = stream(uma, "b1", "00000000000000e1");
        String rawFull = next(q2, "snapshot", 2000).data();
        assertTrue(rawFull.contains("123.4") && rawFull.contains("Steve") && rawFull.contains("the_nether"));
    }

    @Test
    void turningTheFlagOnEndsTheStream() throws Exception {
        BlockingQueue<Event> q = stream(uma, "b1", "00000000000000e2");
        assertFalse(next(q, "hello", 2000).obj().get("relative").getAsBoolean());
        long t0 = System.currentTimeMillis();
        ok(post(owner, "/api/v1/users/" + umaId, "{\"perms\":{" + FULL_PERMS + ",\"streamer\":true}}"));
        Event bye = next(q, "bye", 3000);
        assertNotNull(bye);
        assertEquals("revoked", bye.obj().get("reason").getAsString());
        assertTrue(System.currentTimeMillis() - t0 < 1500, "at the next write, not the next check");
        BlockingQueue<Event> again = stream(uma, bid(uma), "00000000000000e2");
        assertTrue(next(again, "hello", 2000).obj().get("relative").getAsBoolean(), "the reconnect is redacted");
    }
}
