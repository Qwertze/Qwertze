package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.stashmanager.planner.PlannerFixtures;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build planner through the real routes on a real server (127.0.0.1, a random port, a temp data folder), with
 * an injected clock for plan expiry and the upload rate limit. The splitter is also tested on its own.
 */
class PlannerTest {
    static final String OWNER_PW = "owner-password-123";
    static final String TEMP_PW = "temporary-pass-1";
    static final int DV = 4189; // 1.21.4

    @TempDir
    Path data;
    HttpServer server;
    String base;
    Users users;
    SessionStore sessions;
    OrderStore orders;
    IndexStore store;
    AuditLog audit;
    PlanStore plans;
    final AtomicLong now = new AtomicLong(1_000_000_000_000L);
    final HttpClient http = HttpClient.newHttpClient();

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
        store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        store.putIndex(json("{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"mcVersion\":\"1.21.4\",\"items\":["
            + "{\"key\":\"minecraft:stone\",\"id\":\"minecraft:stone\",\"count\":200000,\"maxStack\":64,\"tab\":\"building_blocks\",\"tabOrder\":5},"
            + "{\"key\":\"minecraft:oak_planks\",\"id\":\"minecraft:oak_planks\",\"count\":40,\"maxStack\":64,\"tab\":\"building_blocks\",\"tabOrder\":1},"
            + "{\"key\":\"minecraft:glass\",\"id\":\"minecraft:glass\",\"count\":10,\"maxStack\":64,\"tab\":\"colored_blocks\",\"tabOrder\":1},"
            + "{\"key\":\"minecraft:glass#abc\",\"id\":\"minecraft:glass\",\"count\":7,\"maxStack\":64},"
            + "{\"key\":\"minecraft:ender_pearl\",\"id\":\"minecraft:ender_pearl\",\"count\":100,\"maxStack\":16}]}"));
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
        plans = new PlanStore(now::get);
        StashManagerServer.Routes r = StashManagerServer.routes(config, store, orders, owners, icons,
            new LinkState(config.keyState().generation()), List.of(), sessions, users, audit, new BotHub(), new LoginLimiter(), plans);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", r.router());
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

    Res upload(String token, String query, byte[] file, String contentType) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/api/v1/planner/upload" + query))
            .header("X-Stash-Manager", "1").header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(file));
        if (token != null) b.header("Cookie", SessionStore.COOKIE + "=" + token);
        return send(b.build());
    }

    Res upload(String token, String stash, byte[] file) throws Exception {
        return upload(token, "?server=s&stash=" + stash + "&name=" + URLEncoder.encode("C:\\builds\\My House.nbt", StandardCharsets.UTF_8),
            file, "application/octet-stream");
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

    /** Creates a sub-user that doesn't have to change the password; returns {id, token}. */
    String[] user(String owner, String name, String perms) throws Exception {
        Res r = post(owner, "/api/v1/users", "{\"name\":\"" + name + "\",\"password\":\"" + TEMP_PW + "\",\"mustChange\":false"
            + (perms.startsWith("{") ? ",\"perms\":" + perms : ",\"preset\":\"" + perms + "\"") + "}");
        assertEquals(200, r.status, r.raw.body());
        return new String[] { r.obj().getAsJsonObject("user").get("id").getAsString(), login(name, TEMP_PW) };
    }

    static byte[] house() {
        Map<String, Integer> blocks = new LinkedHashMap<>();
        blocks.put("minecraft:stone", 100);
        blocks.put("minecraft:oak_planks", 60);
        blocks.put("minecraft:glass", 4);
        blocks.put("minecraft:diamond_block", 2);
        return PlannerFixtures.structure(DV, blocks, null);
    }

    static byte[] stones(int n) {
        return PlannerFixtures.structure(DV, Map.of("minecraft:stone", n), null);
    }

    static String planId(Res r) {
        assertEquals(200, r.status, r.raw.body());
        return r.obj().getAsJsonObject("plan").get("id").getAsString();
    }

    static JsonObject row(JsonObject plan, String id) {
        for (JsonElement e : plan.getAsJsonArray("items")) if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
        return null;
    }

    List<String> auditActions() {
        List<String> out = new ArrayList<>();
        for (JsonElement e : audit.newest(200)) out.add(e.getAsJsonObject().get("action").getAsString());
        return out;
    }

    int orderCount(String stash) {
        return orders.listJson("s", stash).getAsJsonArray("orders").size();
    }

    // ---------- upload and view ----------

    @Test
    void uploadShowsTheMaterialsAgainstTheStash() throws Exception {
        String owner = owner();
        Res r = upload(owner, "a", house());
        String id = planId(r);
        assertTrue(id.matches("p-[0-9a-f]{12}"));
        JsonObject plan = r.obj().getAsJsonObject("plan");
        assertEquals("My House.nbt", plan.get("fileName").getAsString(), "path parts are stripped");
        assertEquals("structure", plan.get("format").getAsString());
        assertEquals(DV, plan.get("dataVersion").getAsInt());
        assertEquals(166, plan.get("blocks").getAsLong());
        assertFalse(plan.get("newerThanTable").getAsBoolean());

        JsonObject stone = row(plan, "minecraft:stone");
        assertEquals(100, stone.get("need").getAsLong());
        assertEquals(200000, stone.get("inStash").getAsLong());
        assertEquals(100, stone.get("want").getAsLong());
        assertEquals(0, stone.get("missing").getAsLong());
        JsonObject planks = row(plan, "minecraft:oak_planks");
        assertEquals(40, planks.get("available").getAsLong());
        assertEquals(20, planks.get("missing").getAsLong());
        JsonObject glass = row(plan, "minecraft:glass");
        assertEquals(10, glass.get("inStash").getAsLong(), "only the plain id key counts as have");
        assertEquals(7, glass.get("variants").getAsLong());
        JsonObject diamond = row(plan, "minecraft:diamond_block");
        assertFalse(diamond.get("inIndex").getAsBoolean());
        // A material that no index has still gets a real icon (and the stash's Minecraft version for the URL).
        assertTrue(diamond.get("icon").getAsBoolean());
        assertEquals("1.21.4", diamond.get("mc").getAsString());
        assertTrue(row(plan, "minecraft:stone").get("icon").getAsBoolean());
        assertEquals(2, diamond.get("missing").getAsLong());
        // creative order: building_blocks (planks 1, stone 5), colored_blocks (glass), then the rest
        JsonArray items = plan.getAsJsonArray("items");
        assertEquals("minecraft:oak_planks", items.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("minecraft:stone", items.get(1).getAsJsonObject().get("id").getAsString());
        assertEquals("minecraft:glass", items.get(2).getAsJsonObject().get("id").getAsString());
        JsonObject totals = plan.getAsJsonObject("totals");
        assertEquals(166, totals.get("need").getAsLong());
        assertEquals(22, totals.get("missing").getAsLong());
        assertEquals(1, totals.get("parts").getAsInt());
        assertTrue(auditActions().contains("planner.upload"));

        // Another stash, other options, no new upload.
        Res v = get(owner, "/api/v1/planner/" + id + "?server=s&stash=b&multiplier=3&exclude=minecraft:glass&round=stack");
        assertEquals(200, v.status, v.raw.body());
        JsonObject vp = v.obj().getAsJsonObject("plan");
        JsonObject vs = row(vp, "minecraft:stone");
        assertEquals(300, vs.get("need").getAsLong());
        assertEquals(0, vs.get("inStash").getAsLong());
        assertTrue(row(vp, "minecraft:glass").get("excluded").getAsBoolean());
        assertEquals(300 + 180 + 6, vp.getAsJsonObject("totals").get("need").getAsLong(), "excluded rows are not counted");
        assertEquals(400, get(owner, "/api/v1/planner/" + id + "?server=s&stash=a&multiplier=65").status);
        assertEquals(400, get(owner, "/api/v1/planner/" + id + "?server=s&stash=a&round=half").status);
        assertEquals(1, get(owner, "/api/v1/planner").obj().getAsJsonArray("plans").size());
    }

    @Test
    void fluidsAndChestContentsAreOptional() throws Exception {
        String owner = owner();
        byte[] f = PlannerFixtures.structure(DV, Map.of("minecraft:stone", 3), Map.of("minecraft:ender_pearl", 20), null);
        String id = planId(upload(owner, "a", f));
        Res off = get(owner, "/api/v1/planner/" + id + "?server=s&stash=a");
        assertNotNull(row(off.obj().getAsJsonObject("plan"), "minecraft:chest"));
        assertEquals(null, row(off.obj().getAsJsonObject("plan"), "minecraft:ender_pearl"));
        assertEquals(1, off.obj().getAsJsonObject("plan").getAsJsonArray("containerItems").size());
        Res on = get(owner, "/api/v1/planner/" + id + "?server=s&stash=a&containerItems=1&round=stack");
        JsonObject pearl = row(on.obj().getAsJsonObject("plan"), "minecraft:ender_pearl");
        assertEquals(20, pearl.get("need").getAsLong());
        assertEquals(16, pearl.get("maxStack").getAsInt());
        assertEquals(32, pearl.get("want").getAsLong(), "rounded up to 2 stacks of 16");
    }

    // ---------- permissions, scope and ownership ----------

    @Test
    void permissionsAreEnforced() throws Exception {
        String owner = owner();
        String[] viewer = user(owner, "viewer1", "viewer");
        Res r = upload(viewer[1], "a", house());
        assertEquals(403, r.status);
        assertEquals("planner", r.obj().get("permission").getAsString());

        String[] orderOnly = user(owner, "orderer1", "{\"order\":true}");
        assertEquals(403, upload(orderOnly[1], "a", house()).status, "order alone doesn't give the planner");

        String[] plannerOnly = user(owner, "planner1", "{\"planner\":true,\"order\":true}");
        String id = planId(upload(plannerOnly[1], "a", house()));
        Res denied = post(plannerOnly[1], "/api/v1/planner/" + id + "/order", "{\"server\":\"s\",\"stash\":\"a\"}");
        assertEquals(403, denied.status, "order is not plannerOrder");
        assertEquals("plannerOrder", denied.obj().get("permission").getAsString());

        String[] plannerOrder = user(owner, "planner2", "{\"plannerOrder\":true}");
        Users.Perms p = users.byId(plannerOrder[0]).perms();
        assertTrue(p.planner() && p.view() && !p.order(), "plannerOrder implies planner and view, not order");
        String id2 = planId(upload(plannerOrder[1], "a", house()));
        Res placed = post(plannerOrder[1], "/api/v1/planner/" + id2 + "/order", "{\"server\":\"s\",\"stash\":\"a\"}");
        assertEquals(200, placed.status, placed.raw.body());
        assertEquals(403, post(plannerOrder[1], "/api/v1/orders", "{\"server\":\"s\",\"stash\":\"a\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:stone\",\"count\":1}]}").status);

        assertTrue(Users.PRESETS.get("orderer").plannerOrder() && Users.PRESETS.get("operator").plannerOrder());
        assertFalse(Users.PRESETS.get("viewer").planner());
        assertTrue(UserRoutes.describe(p).contains("planner order"));
    }

    @Test
    void outsideTheStashScopeIs404() throws Exception {
        String owner = owner();
        String[] scoped = user(owner, "scoped", "{\"plannerOrder\":true,\"stashes\":[{\"server\":\"s\",\"stash\":\"a\"}]}");
        assertEquals(404, upload(scoped[1], "b", house()).status);
        String id = planId(upload(scoped[1], "a", house()));
        assertEquals(404, get(scoped[1], "/api/v1/planner/" + id + "?server=s&stash=b").status);
        assertEquals(404, post(scoped[1], "/api/v1/planner/" + id + "/order", "{\"server\":\"s\",\"stash\":\"b\",\"dryRun\":true}").status);
        assertEquals(200, get(scoped[1], "/api/v1/planner/" + id + "?server=s&stash=a").status);
    }

    @Test
    void anotherUsersPlanIs404() throws Exception {
        String owner = owner();
        String[] a = user(owner, "alice", "orderer");
        String[] b = user(owner, "bob", "orderer");
        String id = planId(upload(a[1], "a", house()));
        assertEquals(404, get(b[1], "/api/v1/planner/" + id + "?server=s&stash=a").status);
        assertEquals(404, post(b[1], "/api/v1/planner/" + id + "/order", "{\"server\":\"s\",\"stash\":\"a\",\"dryRun\":true}").status);
        assertEquals(404, post(b[1], "/api/v1/planner/" + id + "/delete", "{}").status);
        assertEquals(404, get(owner, "/api/v1/planner/" + id + "?server=s&stash=a").status, "not even the owner's");
        assertEquals(0, get(b[1], "/api/v1/planner").obj().getAsJsonArray("plans").size());
        assertEquals(200, get(a[1], "/api/v1/planner/" + id + "?server=s&stash=a").status);
    }

    // ---------- upload limits ----------

    @Test
    void uploadGuardAndLimits() throws Exception {
        String owner = owner();
        assertEquals(403, upload(owner, "?server=s&stash=a", house(), "application/json").status, "JSON is not an upload");
        assertEquals(403, upload(owner, "?server=s&stash=a", house(), "text/plain").status);
        HttpRequest noHeader = HttpRequest.newBuilder(URI.create(base + "/api/v1/planner/upload?server=s&stash=a"))
            .header("Content-Type", "application/octet-stream").header("Cookie", SessionStore.COOKIE + "=" + owner)
            .POST(HttpRequest.BodyPublishers.ofByteArray(house())).build();
        assertEquals(403, send(noHeader).status);
        HttpRequest crossSite = HttpRequest.newBuilder(URI.create(base + "/api/v1/planner/upload?server=s&stash=a"))
            .header("X-Stash-Manager", "1").header("Content-Type", "application/octet-stream").header("Origin", "http://evil.example")
            .header("Cookie", SessionStore.COOKIE + "=" + owner).POST(HttpRequest.BodyPublishers.ofByteArray(house())).build();
        assertEquals(403, send(crossSite).status);
        assertEquals(401, upload(null, "a", house()).status);

        Res big = upload(owner, "a", new byte[(int) PlannerRoutes.MAX_UPLOAD + 1]);
        assertEquals(413, big.status);
        Res legacy = upload(owner, "a", PlannerFixtures.legacy());
        assertEquals(422, legacy.status);
        assertTrue(legacy.error().contains("pre-1.13"), legacy.error());
        Res junk = upload(owner, "a", "not a schematic".getBytes(StandardCharsets.UTF_8));
        assertEquals(400, junk.status);
        assertNotNull(junk.error());
        assertEquals(400, upload(owner, "a", new byte[0]).status);
    }

    @Test
    void uploadsAreRateLimitedPerUser() throws Exception {
        String owner = owner();
        String[] other = user(owner, "other", "orderer");
        byte[] f = stones(1);
        for (int i = 0; i < PlannerRoutes.PER_MINUTE; i++) {
            assertEquals(200, upload(owner, "a", f).status, "upload " + i);
            now.addAndGet(1000);
        }
        Res limited = upload(owner, "a", f);
        assertEquals(429, limited.status);
        assertTrue(limited.raw.headers().firstValue("Retry-After").isPresent());
        assertEquals(200, upload(other[1], "a", f).status, "per user");
        now.addAndGet(61_000);
        assertEquals(200, upload(owner, "a", f).status);
        // 60 an hour
        for (int i = 0; i < 60; i++) {
            now.addAndGet(7_000);
            Res r = upload(owner, "a", f);
            if (r.status == 429) {
                assertEquals(49, i, "the hour limit applies at the 61st upload");
                return;
            }
        }
        throw new AssertionError("the hourly limit never applied");
    }

    // ---------- what is kept ----------

    @Test
    void plansExpireAndAreCapped() throws Exception {
        String owner = owner();
        String id = planId(upload(owner, "a", house()));
        now.addAndGet(PlanStore.IDLE_MS - 1000);
        assertEquals(200, get(owner, "/api/v1/planner/" + id + "?server=s&stash=a").status, "use keeps it");
        now.addAndGet(PlanStore.IDLE_MS + 1);
        assertEquals(404, get(owner, "/api/v1/planner/" + id + "?server=s&stash=a").status, "30 minutes idle");

        String id2 = planId(upload(owner, "a", house()));
        for (int i = 0; i < 5; i++) {
            now.addAndGet(20 * 60 * 1000);
            assertEquals(200, get(owner, "/api/v1/planner/" + id2 + "?server=s&stash=a").status);
        }
        now.addAndGet(20 * 60 * 1000 + 1);
        assertEquals(404, get(owner, "/api/v1/planner/" + id2 + "?server=s&stash=a").status, "2 hours at most");

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(planId(upload(owner, "a", house())));
            now.addAndGet(1000);
        }
        assertEquals(404, get(owner, "/api/v1/planner/" + ids.get(0) + "?server=s&stash=a").status, "the oldest is evicted");
        assertEquals(3, get(owner, "/api/v1/planner").obj().getAsJsonArray("plans").size());
        assertEquals(3, plans.size());
    }

    @Test
    void deleteAndEndedSessionsDropPlans() throws Exception {
        String owner = owner();
        String id = planId(upload(owner, "a", house()));
        assertEquals(200, post(owner, "/api/v1/planner/" + id + "/delete", "{}").status);
        assertEquals(404, get(owner, "/api/v1/planner/" + id + "?server=s&stash=a").status);
        assertEquals(404, post(owner, "/api/v1/planner/" + id + "/delete", "{}").status);

        String[] u = user(owner, "dave", "orderer");
        planId(upload(u[1], "a", house()));
        assertEquals(1, plans.list(u[0]).size());
        assertEquals(200, post(owner, "/api/v1/users/" + u[0], "{\"disabled\":true}").status);
        assertEquals(0, plans.list(u[0]).size(), "disabling the user drops the plan");

        String[] e = user(owner, "erin", "orderer");
        planId(upload(e[1], "a", house()));
        assertEquals(200, post(owner, "/api/v1/users/" + e[0] + "/password", "{\"password\":\"another-pass-123\"}").status);
        assertEquals(0, plans.list(e[0]).size(), "a password reset drops it");

        String[] f = user(owner, "frank", "orderer");
        planId(upload(f[1], "a", house()));
        assertEquals(200, post(f[1], "/api/v1/auth/logout", "{}").status);
        assertEquals(0, plans.dropUser(f[0]), "the logout itself dropped it (not only the next prune)");
        assertEquals(0, plans.list(f[0]).size(), "the last session ending drops it");

        String[] g = user(owner, "gina", "orderer");
        planId(upload(g[1], "a", house()));
        assertEquals(200, post(g[1], "/api/v1/auth/logout-all", "{}").status);
        assertEquals(0, plans.dropUser(g[0]), "logout-all dropped it at once");
    }

    @Test
    void theFileIsNeverWrittenToTheDataFolder() throws Exception {
        String owner = owner();
        String marker = "PLANNER-MARKER-7f3a91c2";
        byte[] f = PlannerFixtures.structure(DV, Map.of("minecraft:stone", 5), marker);
        assertTrue(new String(f, StandardCharsets.ISO_8859_1).contains(marker));
        Map<Path, Long> before = listing();
        planId(upload(owner, "a", f));
        Map<Path, Long> after = listing();
        assertEquals(before.keySet(), after.keySet(), "no file was created");
        try (Stream<Path> all = Files.walk(data)) {
            for (Path p : (Iterable<Path>) all.filter(Files::isRegularFile)::iterator) {
                assertFalse(new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1).contains(marker), p + " holds the upload");
            }
        }
    }

    private Map<Path, Long> listing() throws IOException {
        Map<Path, Long> out = new TreeMap<>();
        try (Stream<Path> all = Files.walk(data)) {
            all.filter(Files::isRegularFile).forEach(p -> out.put(data.relativize(p), 0L));
        }
        return out;
    }

    // ---------- ordering ----------

    @Test
    void dryRunPlacesNothingAndPartsPlaceTheirLines() throws Exception {
        String owner = owner();
        String[] u = user(owner, "builder", "{\"plannerOrder\":true}");
        // 2000 stone × 64 = 128000 = 2000 stacks: 1458 stacks (54 shulkers) in part 1, 542 in part 2.
        String id = planId(upload(u[1], "a", stones(2000)));
        String body = "{\"server\":\"s\",\"stash\":\"a\",\"multiplier\":64,\"names\":true";
        Res dry = post(u[1], "/api/v1/planner/" + id + "/order", body + ",\"dryRun\":true}");
        assertEquals(200, dry.status, dry.raw.body());
        assertEquals(0, orderCount("a"), "a dry run places nothing");
        JsonArray parts = dry.obj().getAsJsonArray("parts");
        assertEquals(2, parts.size());
        assertEquals(54, parts.get(0).getAsJsonObject().get("shulkers").getAsLong());
        assertEquals(1458L * 64, parts.get(0).getAsJsonObject().getAsJsonArray("lines").get(0).getAsJsonObject().get("count").getAsLong());
        assertEquals(542L * 64, parts.get(1).getAsJsonObject().getAsJsonArray("lines").get(0).getAsJsonObject().get("count").getAsLong());

        Res second = post(u[1], "/api/v1/planner/" + id + "/order", body + ",\"part\":2}");
        assertEquals(200, second.status, second.raw.body());
        JsonObject order = second.obj().getAsJsonObject("order");
        assertEquals(542L * 64, order.getAsJsonArray("lines").get(0).getAsJsonObject().get("count").getAsLong());
        assertEquals(u[0], order.getAsJsonObject("placedBy").get("id").getAsString());
        assertEquals("all", order.getAsJsonObject("names").get("mode").getAsString());
        assertEquals("Build: My House.nbt 2/2", order.getAsJsonObject("names").get("name").getAsString());
        assertEquals(2, second.obj().get("part").getAsInt());
        assertEquals(2, second.obj().get("partsTotal").getAsInt());
        assertEquals("[1]", second.obj().getAsJsonArray("remaining").toString());
        assertEquals(409, post(u[1], "/api/v1/planner/" + id + "/order", body + ",\"part\":2}").status, "a part is ordered once");

        // The split stays as confirmed: part 1 is still 54 shulkers although part 2 now reserves stone.
        Res first = post(u[1], "/api/v1/planner/" + id + "/order", body + "}");
        assertEquals(200, first.status, first.raw.body());
        assertEquals(1458L * 64, first.obj().getAsJsonObject("order").getAsJsonArray("lines").get(0).getAsJsonObject().get("count").getAsLong());
        assertEquals("[]", first.obj().getAsJsonArray("remaining").toString());
        assertEquals(2, orderCount("a"));
        assertEquals(400, post(u[1], "/api/v1/planner/" + id + "/order", body + ",\"part\":3}").status);
        assertTrue(auditActions().contains("planner.order"));
        JsonObject view = get(u[1], "/api/v1/planner/" + id + "?server=s&stash=a&multiplier=64").obj().getAsJsonObject("plan");
        assertEquals(2, view.getAsJsonObject("ordered").getAsJsonObject("placed").size());
    }

    @Test
    void wantIsCappedAtAvailableAndZeroLinesAreNotAvailable() throws Exception {
        String owner = owner();
        String id = planId(upload(owner, "a", house()));
        Res dry = post(owner, "/api/v1/planner/" + id + "/order",
            "{\"server\":\"s\",\"stash\":\"a\",\"round\":\"shulker\",\"exclude\":[\"minecraft:glass\"],\"dryRun\":true}");
        assertEquals(200, dry.status, dry.raw.body());
        Map<String, Long> lines = new HashMap<>();
        for (JsonElement p : dry.obj().getAsJsonArray("parts")) {
            for (JsonElement l : p.getAsJsonObject().getAsJsonArray("lines")) {
                lines.merge(l.getAsJsonObject().get("key").getAsString(), l.getAsJsonObject().get("count").getAsLong(), Long::sum);
            }
        }
        assertEquals(27L * 64, lines.get("minecraft:stone"), "rounded up to one shulker");
        assertEquals(40L, lines.get("minecraft:oak_planks"), "a shulker rounded, but capped at what is available");
        assertFalse(lines.containsKey("minecraft:glass"), "excluded");
        assertFalse(lines.containsKey("minecraft:diamond_block"));
        assertEquals("minecraft:diamond_block", dry.obj().getAsJsonArray("notAvailable").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(2, dry.obj().getAsJsonArray("missing").size(), "planks and diamond blocks");

        // An order of a plan that has nothing available
        String none = planId(upload(owner, "b", house()));
        assertEquals(400, post(owner, "/api/v1/planner/" + none + "/order", "{\"server\":\"s\",\"stash\":\"b\"}").status);
        Res opened = post(owner, "/api/v1/planner/" + id + "/order", "{\"server\":\"s\",\"stash\":\"a\",\"multiplier\":2,\"names\":false}");
        assertEquals(200, opened.status, opened.raw.body());
        assertTrue(opened.obj().getAsJsonObject("order").get("names").isJsonNull());
        assertEquals(400, post(owner, "/api/v1/planner/" + id + "/order", "{\"server\":\"s\",\"stash\":\"a\",\"multiplier\":0}").status);
    }

    // ---------- the splitter ----------

    static IndexStore.OrderCounts counts(int items) {
        Map<String, Long> n = new HashMap<>();
        Map<String, String> ids = new HashMap<>();
        Map<String, Integer> stacks = new HashMap<>();
        for (int i = 0; i < items; i++) {
            String k = "minecraft:item" + i;
            n.put(k, 1_000_000L);
            ids.put(k, k);
            stacks.put(k, 64);
        }
        n.put("minecraft:shulker_box", 100L);
        ids.put("minecraft:shulker_box", "minecraft:shulker_box");
        stacks.put("minecraft:shulker_box", 1);
        return new IndexStore.OrderCounts(n, ids, stacks, Map.of(), Map.of(), Map.of());
    }

    static OrderStore.Line line(String key, long n) {
        return new OrderStore.Line("item", key, n);
    }

    @Test
    void exactly54ShulkersIsOnePart() {
        IndexStore.OrderCounts c = counts(2);
        List<PlannerRoutes.Part> parts = PlannerRoutes.split(List.of(line("minecraft:item0", 54L * 27 * 64)), c);
        assertEquals(1, parts.size());
        assertEquals(54, parts.get(0).shulkers());
        parts = PlannerRoutes.split(List.of(line("minecraft:item0", 54L * 27 * 64), line("minecraft:item1", 1)), c);
        assertEquals(2, parts.size());
        assertEquals(1, parts.get(1).lines().size());
        assertEquals(1, parts.get(1).shulkers());
    }

    @Test
    void aLineIsSplitAtTheRemainingRoom() {
        IndexStore.OrderCounts c = counts(2);
        // 1000 stacks + 500 stacks: part 1 holds 1458 stacks, so item1 gets 458 there and 42 in part 2.
        List<PlannerRoutes.Part> parts = PlannerRoutes.split(List.of(line("minecraft:item0", 1000L * 64), line("minecraft:item1", 500L * 64)), c);
        assertEquals(2, parts.size());
        assertEquals(List.of(line("minecraft:item0", 1000L * 64), line("minecraft:item1", 458L * 64)), parts.get(0).lines());
        assertEquals(List.of(line("minecraft:item1", 42L * 64)), parts.get(1).lines());
        assertEquals(54, OrderStore.shulkersNeeded(parts.get(0).lines(), c));
        // A partial stack: 100 items take 2 slots.
        parts = PlannerRoutes.split(List.of(line("minecraft:item0", 1457L * 64), line("minecraft:item1", 100)), c);
        assertEquals(2, parts.size());
        assertEquals(64, parts.get(0).lines().get(1).count(), "only one slot left: one stack");
        assertEquals(36, parts.get(1).lines().get(0).count());
        // Empty shulkers count one shulker each.
        parts = PlannerRoutes.split(List.of(line("minecraft:item0", 53L * 27 * 64), line("minecraft:shulker_box", 3)), c);
        assertEquals(2, parts.size());
        assertEquals(1, parts.get(0).lines().get(1).count());
        assertEquals(2, parts.get(1).lines().get(0).count());
    }

    @Test
    void twoHundredLinesPerPart() {
        IndexStore.OrderCounts c = counts(401);
        List<OrderStore.Line> lines = new ArrayList<>();
        for (int i = 0; i < 200; i++) lines.add(line("minecraft:item" + i, 1));
        assertEquals(1, PlannerRoutes.split(lines, c).size());
        lines.add(line("minecraft:item200", 1));
        List<PlannerRoutes.Part> parts = PlannerRoutes.split(lines, c);
        assertEquals(2, parts.size());
        assertEquals(200, parts.get(0).lines().size());
        assertEquals(1, parts.get(1).lines().size());
        for (int i = 201; i < 401; i++) lines.add(line("minecraft:item" + i, 1));
        assertEquals(3, PlannerRoutes.split(lines, c).size());
    }

    @Test
    void roundingAndNames() {
        assertEquals(64, PlannerRoutes.round(1, 64, "stack"));
        assertEquals(128, PlannerRoutes.round(65, 64, "stack"));
        assertEquals(27 * 16, PlannerRoutes.round(17, 16, "shulker"));
        assertEquals(17, PlannerRoutes.round(17, 16, "none"));
        assertEquals("Build: house 1/3", PlannerRoutes.namesFor("house", 1, 3).get("name").getAsString());
        assertEquals("Build: house", PlannerRoutes.namesFor("ho§use", 1, 1).get("name").getAsString());
        String longName = PlannerRoutes.namesFor("x".repeat(64), 12, 12).get("name").getAsString();
        assertEquals(OrderStore.MAX_NAME, longName.length());
        assertTrue(longName.endsWith(" 12/12"));
        assertEquals("a b.litematic", PlanStore.cleanFileName("../../etc/x\\a\u202e\u0000 b.litematic"));
        assertEquals("schematic", PlanStore.cleanFileName("dir/"));
        assertEquals(64, PlanStore.cleanFileName("y".repeat(100)).length());
    }

    // ---------- review fixes ----------

    @Test
    void aFileBetweenTwoTablesGetsTheNewerOne() throws Exception {
        String owner = owner();
        // data version 4300 (between 1.21.4 = 4189 and 1.21.11 = 4671): blocks added in 1.21.5 must be known
        byte[] f = PlannerFixtures.structure(4300, Map.of("minecraft:firefly_bush", 2, "minecraft:stone", 1), null);
        JsonObject plan = upload(owner, "a", f).obj().getAsJsonObject("plan");
        assertEquals("1.21.11", plan.get("tableVersion").getAsString());
        assertFalse(plan.get("newerThanTable").getAsBoolean());
        assertEquals(0, plan.getAsJsonArray("unknown").size());
        assertEquals(2, row(plan, "minecraft:firefly_bush").get("need").getAsLong());
        JsonObject newer = upload(owner, "a", PlannerFixtures.structure(9999, Map.of("minecraft:stone", 1), null)).obj().getAsJsonObject("plan");
        assertEquals("1.21.11", newer.get("tableVersion").getAsString());
        assertTrue(newer.get("newerThanTable").getAsBoolean());
    }

    @Test
    void optionsAreLockedOnceAPartIsOrdered() throws Exception {
        String owner = owner();
        String[] u = user(owner, "locker", "{\"plannerOrder\":true}");
        String id = planId(upload(u[1], "a", stones(2000)));
        String same = "{\"server\":\"s\",\"stash\":\"a\",\"multiplier\":64";
        Res first = post(u[1], "/api/v1/planner/" + id + "/order", same + "}");
        assertEquals(200, first.status, first.raw.body());
        String orderId = first.obj().getAsJsonObject("order").get("id").getAsString();

        Res other = post(u[1], "/api/v1/planner/" + id + "/order", "{\"server\":\"s\",\"stash\":\"a\",\"multiplier\":32,\"dryRun\":true}");
        assertEquals(409, other.status);
        assertEquals(PlannerRoutes.OPTIONS_LOCKED, other.error());
        assertEquals(409, post(u[1], "/api/v1/planner/" + id + "/order", "{\"server\":\"s\",\"stash\":\"b\",\"multiplier\":64}").status,
            "another stash is other options too");
        // The view may preview other options, and says which ones are locked.
        Res preview = get(u[1], "/api/v1/planner/" + id + "?server=s&stash=a&multiplier=32");
        assertEquals(200, preview.status);
        JsonObject ordered = preview.obj().getAsJsonObject("plan").getAsJsonObject("ordered");
        assertTrue(ordered.get("locked").getAsBoolean());
        assertFalse(ordered.get("sameOptions").getAsBoolean());
        assertEquals(64, ordered.getAsJsonObject("options").get("multiplier").getAsInt());
        // The same options carry on with the confirmed split; the totals come from it.
        JsonObject view = get(u[1], "/api/v1/planner/" + id + "?server=s&stash=a&multiplier=64").obj().getAsJsonObject("plan");
        assertTrue(view.getAsJsonObject("totals").get("frozen").getAsBoolean());
        assertEquals(2, view.getAsJsonObject("totals").get("parts").getAsInt());
        assertEquals(54 + 21, view.getAsJsonObject("totals").get("shulkers").getAsLong());
        assertEquals(2000L * 64, view.getAsJsonObject("totals").get("want").getAsLong(), "what the confirmed parts order");
        assertEquals(200, post(u[1], "/api/v1/planner/" + id + "/order", same + ",\"dryRun\":true}").status);

        // With the only placed order cancelled nothing was ordered: other options may start a new split.
        assertEquals(200, post(u[1], "/api/v1/orders/" + orderId + "/cancel", "{}").status, "plannerOrder cancels its own order");
        Res fresh = post(u[1], "/api/v1/planner/" + id + "/order", "{\"server\":\"s\",\"stash\":\"a\",\"multiplier\":32,\"dryRun\":true}");
        assertEquals(200, fresh.status, fresh.raw.body());
    }

    @Test
    void aFailedOrCancelledPartCanBeOrderedAgain() throws Exception {
        String owner = owner();
        String[] u = user(owner, "again", "{\"plannerOrder\":true}");
        String id = planId(upload(u[1], "a", stones(2000)));
        String body = "{\"server\":\"s\",\"stash\":\"a\",\"multiplier\":64";
        String o1 = post(u[1], "/api/v1/planner/" + id + "/order", body + "}").obj().getAsJsonObject("order").get("id").getAsString();
        assertEquals(409, post(u[1], "/api/v1/planner/" + id + "/order", body + ",\"part\":1}").status, "a pending part is not ordered twice");
        assertEquals(200, post(u[1], "/api/v1/orders/" + o1 + "/cancel", "{}").status);
        Res dry = post(u[1], "/api/v1/planner/" + id + "/order", body + ",\"dryRun\":true}");
        assertEquals("[1]", dry.obj().getAsJsonArray("again").toString());
        assertEquals("[2]", dry.obj().getAsJsonArray("remaining").toString());
        Res again = post(u[1], "/api/v1/planner/" + id + "/order", body + ",\"part\":1}");
        assertEquals(200, again.status, again.raw.body());
        String o2 = again.obj().getAsJsonObject("order").get("id").getAsString();
        assertFalse(o1.equals(o2));
        assertEquals(o2, again.obj().getAsJsonObject("placed").get("1").getAsString(), "the entry is replaced");
        assertEquals("[]", again.obj().getAsJsonArray("again").toString());
        assertEquals(1458L * 64, again.obj().getAsJsonObject("order").getAsJsonArray("lines").get(0).getAsJsonObject().get("count").getAsLong());
    }

    @Test
    void plannerOrderManagesItsOwnOrders() throws Exception {
        String owner = owner();
        String[] u = user(owner, "builder2", "{\"plannerOrder\":true}");
        String id = planId(upload(u[1], "a", house()));
        String mine = post(u[1], "/api/v1/planner/" + id + "/order", "{\"server\":\"s\",\"stash\":\"a\"}").obj().getAsJsonObject("order").get("id").getAsString();
        assertEquals(200, post(u[1], "/api/v1/orders/" + mine + "/cancel", "{}").status);
        Res retry = post(u[1], "/api/v1/orders/" + mine + "/retry", "{}");
        assertEquals(200, retry.status, retry.raw.body());
        assertEquals(u[0], retry.obj().getAsJsonObject("order").getAsJsonObject("placedBy").get("id").getAsString());
        // Someone else's order stays out of reach.
        Res placed = post(owner, "/api/v1/orders", "{\"server\":\"s\",\"stash\":\"a\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:stone\",\"count\":1}]}");
        String theirs = placed.obj().getAsJsonObject("order").get("id").getAsString();
        assertEquals(403, post(u[1], "/api/v1/orders/" + theirs + "/cancel", "{}").status);
        assertEquals(403, post(u[1], "/api/v1/orders/" + theirs + "/force-cancel", "{}").status);
        // A planner-only user can't.
        String[] viewOnly = user(owner, "plannerview", "{\"planner\":true}");
        assertEquals(403, post(viewOnly[1], "/api/v1/orders/" + theirs + "/cancel", "{}").status);
    }

    @Test
    void theViewTakesItsOptionsAsJsonToo() throws Exception {
        String owner = owner();
        String id = planId(upload(owner, "a", house()));
        StringBuilder ex = new StringBuilder("[\"minecraft:glass\"");
        for (int i = 0; i < 1999; i++) ex.append(",\"minecraft:some_long_item_name_number_").append(i).append('"');
        ex.append(']');
        Res r = post(owner, "/api/v1/planner/" + id + "/view", "{\"server\":\"s\",\"stash\":\"a\",\"multiplier\":2,\"exclude\":" + ex + "}");
        assertEquals(200, r.status, r.raw.body());
        JsonObject plan = r.obj().getAsJsonObject("plan");
        assertTrue(row(plan, "minecraft:glass").get("excluded").getAsBoolean());
        assertEquals(200, row(plan, "minecraft:stone").get("need").getAsLong());
        assertEquals(404, post(owner, "/api/v1/planner/p-000000000000/view", "{\"server\":\"s\",\"stash\":\"a\"}").status);
        assertEquals(400, post(owner, "/api/v1/planner/" + id + "/view", "{\"server\":\"s\"}").status);
    }
}
