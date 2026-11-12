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
 * Pearl travel through the real routes (PEARL-CONTRACT v1.0 rev 3): the website's stations and trips, permissions, scope, forced
 * streamers and CSRF, and the heartbeat fields and the plugin loader's signed report.
 */
class PearlRoutesTest {
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


    static final String UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";

    String travelBody(String away, String trip, String local) {
        long at = System.currentTimeMillis();
        return "{\"v\":1,\"enabled\":true,\"at\":" + at + ",\"uuid\":\"" + UUID + "\",\"game\":\"in\",\"joinedAt\":" + (at - 600_000) + ",\"client\":\"plugin\",\"away\":"
            + (away == null ? "null" : "{\"station\":\"" + away + "\",\"trip\":null,\"since\":" + at + "}") + ",\"pearls\":{\"carried\":3},\"trip\":" + (trip == null ? "null" : trip)
            + ",\"local\":" + (local == null ? "null" : local) + "}";
    }

    Res travellerBeat(String away, String trip, String local) throws Exception {
        return heartbeat("\"pearlTravel\":" + travelBody(away, trip, local));
    }

    String loaderBody(String player, String chamberOwner, String presence) {
        long at = System.currentTimeMillis();
        return "{\"v\":1,\"player\":\"" + player + "\",\"at\":" + at + ",\"dropPearlAfterLoad\":true,\"temporaryMode\":false,\"busy\":false,\"playerNear\":false,"
            + "\"chambers\":[{\"id\":\"pearl-1\",\"owner\":\"" + chamberOwner + "\",\"ownerKnown\":true,\"presence\":\"" + presence + "\",\"since\":" + (at - 1000) + "}],\"load\":null}";
    }

    Res loaderBeat(String loader, String stash, String chamberOwner, String presence) throws Exception {
        return botPost("/api/v1/heartbeat", "{\"server\":\"s\",\"stash\":\"" + stash + "\",\"player\":\"" + loader + "\",\"pearlLoader\":" + loaderBody(loader, chamberOwner, presence) + "}");
    }

    String station(String name, String dim, String loader, String stash) throws Exception {
        Res r = post(owner, "/api/v1/pearl/stations", "{\"name\":\"" + name + "\",\"server\":\"s\",\"dim\":\"" + dim + "\",\"loader\":{\"player\":\"" + loader + "\"}"
            + (stash == null ? "" : ",\"stash\":\"" + stash + "\"") + "}");
        assertEquals(200, r.status, r.body);
        return r.obj().get("id").getAsString();
    }

    JsonObject pearl() throws Exception {
        return ok(get(owner, "/api/v1/pearl"));
    }

    // ---------- the website ----------

    @Test
    void theOwnerRegistersStationsAndStartsAndStopsATrip() throws Exception {
        start();
        heartbeat(null);
        String far = station("Nether hub", "minecraft:the_nether", "FarBot", null);
        assertTrue(far.matches("^ps-[0-9a-f]{8}$"), far);
        assertEquals(1, pearl().getAsJsonArray("stations").size());
        station("Home", "minecraft:overworld", "HomeBot", "a");
        // refused: a second home for the stash, bad names, bad dims, bad loaders
        assertEquals(409, post(owner, "/api/v1/pearl/stations", "{\"name\":\"Home 2\",\"server\":\"s\",\"dim\":\"minecraft:overworld\",\"loader\":{\"player\":\"Other\"},\"stash\":\"a\"}").status);
        assertEquals(400, post(owner, "/api/v1/pearl/stations", "{\"name\":\"bad§\",\"server\":\"s\",\"dim\":\"minecraft:overworld\",\"loader\":{\"player\":\"Other\"}}").status);
        assertEquals(400, post(owner, "/api/v1/pearl/stations", "{\"name\":\"X\",\"server\":\"s\",\"dim\":\"nether\",\"loader\":{\"player\":\"Other\"}}").status);
        assertEquals(400, post(owner, "/api/v1/pearl/stations", "{\"name\":\"X\",\"server\":\"s\",\"dim\":\"minecraft:overworld\",\"loader\":{\"player\":\"no\"}}").status);
        assertEquals(404, post(owner, "/api/v1/pearl/stations", "{\"id\":\"ps-00000000\",\"name\":\"X\",\"server\":\"s\",\"dim\":\"minecraft:overworld\",\"loader\":{\"player\":\"Other\"}}").status);
        assertEquals(2, pearl().getAsJsonArray("stations").size());
    }

    @Test
    void aTripNeedsABotThatCanTravelAndValidInput() throws Exception {
        start();
        heartbeat(null);
        String far = station("Nether hub", "minecraft:the_nether", "FarBot", null);
        String go = "{" + KEY + ",\"action\":\"go\",\"station\":\"" + far + "\"}";
        Res no = post(owner, "/api/v1/pearl/trips", go);
        assertEquals(409, no.status);
        assertEquals("this bot can't travel by pearl", no.error());
        assertFalse(travellerBeat(null, null, null).obj().has("pearlTrip"));
        assertEquals(400, post(owner, "/api/v1/pearl/trips", "{" + KEY + ",\"action\":\"go\"}").status, "go needs a station");
        assertEquals(404, post(owner, "/api/v1/pearl/trips", "{" + KEY + ",\"action\":\"go\",\"station\":\"ps-00000000\"}").status);
        assertEquals(400, post(owner, "/api/v1/pearl/trips", "{" + KEY + ",\"action\":\"fly\",\"station\":\"" + far + "\"}").status);
        assertEquals(409, post(owner, "/api/v1/pearl/trips", "{" + KEY + ",\"action\":\"back\"}").status, "not away");
    }

    @Test
    void theOwnerStartsAndStopsATrip() throws Exception {
        start();
        heartbeat(null);
        String far = station("Nether hub", "minecraft:the_nether", "FarBot", null);
        String go = "{" + KEY + ",\"action\":\"go\",\"station\":\"" + far + "\"}";
        travellerBeat(null, null, null);
        JsonObject started = ok(post(owner, "/api/v1/pearl/trips", go));
        String id = started.get("id").getAsString();
        assertTrue(id.matches("^t-[0-9a-f]{12}$"), id);
        JsonObject trip = started.getAsJsonArray("trips").get(0).getAsJsonObject();
        assertEquals("pending", trip.get("state").getAsString());
        assertEquals("go", trip.get("action").getAsString());
        assertEquals("website", trip.get("origin").getAsString());
        assertEquals(200, trip.get("priority").getAsInt());
        assertEquals("travel", trip.get("kind").getAsString());
        assertEquals(2, trip.getAsJsonArray("children").size());
        assertEquals("pearl", trip.getAsJsonObject("params").getAsJsonObject("route").getAsJsonArray("legs").get(0).getAsJsonObject().get("method").getAsString());
        // the bot is offered it in the heartbeat reply
        JsonObject offer = travellerBeat(null, null, null).obj().getAsJsonObject("pearlTrip");
        assertEquals(id, offer.get("id").getAsString());
        assertEquals("FarBot", offer.get("loader").getAsString());
        assertFalse(offer.get("go").getAsBoolean());
        // one open trip per bot
        Res again = post(owner, "/api/v1/pearl/trips", go);
        assertEquals(409, again.status);
        assertEquals("already_open", again.error());
        // stop: cancelled, and T is told once
        JsonObject stopped = ok(post(owner, "/api/v1/pearl/trips/stop", "{\"id\":\"" + id + "\"}"));
        assertEquals("cancelled", stopped.getAsJsonArray("trips").get(0).getAsJsonObject().get("state").getAsString());
        assertTrue(travellerBeat(null, null, null).obj().getAsJsonObject("pearlTrip").get("cancel").getAsBoolean());
        assertEquals(404, post(owner, "/api/v1/pearl/trips/stop", "{\"id\":\"t-000000000000\"}").status);
        String audit = get(owner, "/api/v1/audit").body;
        assertTrue(audit.contains("pearl.station") && audit.contains("pearl.trip") && audit.contains("pearl.stop"), audit);
        // 6 trip starts or stops a minute per user: the rest are 429
        int refused = 0;
        for (int i = 0; i < 6; i++) if (post(owner, "/api/v1/pearl/trips/stop", "{\"id\":\"" + id + "\"}").status == 429) refused++;
        assertTrue(refused >= 1, "the trip limiter answers 429");
    }

    @Test
    void everyPearlRouteIsGuardedAndForcedStreamersAreRefused() throws Exception {
        start();
        heartbeat(null);
        String viewer = userToken("vic", "{\"view\":true}");
        String activity = userToken("act", "{\"view\":true,\"activity\":true}");
        String editor = userToken("edi", "{\"view\":true,\"activity\":true,\"settings\":true}");
        String forced = userToken("fay", "{\"view\":true,\"activity\":true,\"settings\":true,\"streamer\":true}");
        String st = "{\"name\":\"X\",\"server\":\"s\",\"dim\":\"minecraft:the_nether\",\"loader\":{\"player\":\"FarBot\"}}";
        String[][] writes = {{"stations", st}, {"stations/delete", "{\"id\":\"ps-00000000\"}"}, {"assign", "{\"station\":\"ps-00000000\",\"player\":\"Bob\",\"chamber\":null}"},
            {"chambers/reset", "{\"station\":\"ps-00000000\",\"chamber\":\"pearl-1\"}"}, {"pins/reset", "{\"player\":\"Bob\",\"uuid\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\"}"}, {"stations/approve", "{\"id\":\"ps-00000000\",\"source\":\"x\"}"}, {"allowed", "{\"station\":\"ps-00000000\",\"player\":\"Bob\",\"allowed\":true}"}, {"stations/dismiss", "{\"id\":\"ps-00000000\",\"source\":\"x\"}"}, {"trips", "{" + KEY + ",\"action\":\"back\"}"},
            {"trips/stop", "{\"id\":\"t-000000000000\"}"}, {"away/clear", "{\"stash\":\"a\"}"}};
        boolean[] ownerOnly = {true, true, true, true, true, true, true, true, false, false, true};
        for (int i = 0; i < writes.length; i++) {
            String path = "/api/v1/pearl/" + writes[i][0], body = writes[i][1];
            assertEquals(403, post(viewer, path, body).status, writes[i][0] + " viewer");
            if (ownerOnly[i]) assertEquals(403, post(editor, path, body).status, writes[i][0] + " owner only");
            Res f = post(forced, path, body);
            assertEquals(403, f.status, writes[i][0] + " forced streamer");
            assertEquals(ownerOnly[i] ? "owner" : "streamer", f.obj().get("permission").getAsString(), "a forced user is never the owner");
            HttpResponse<String> noHeader = http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .header("Cookie", SessionStore.COOKIE + "=" + owner).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, noHeader.statusCode(), writes[i][0] + " csrf");
        }
        assertEquals(403, get(viewer, "/api/v1/pearl").status, "reading needs activity");
        assertEquals(200, get(activity, "/api/v1/pearl").status);
        Res fr = get(forced, "/api/v1/pearl");
        assertEquals(403, fr.status);
        assertEquals("streamer", fr.obj().get("permission").getAsString());
        assertEquals(413, post(owner, "/api/v1/pearl/stations", "{\"pad\":\"" + "x".repeat(5000) + "\"}").status);
        // a station is visible to users who see a stash on its server; the stash list says only {away, trip}
        station("Hub", "minecraft:the_nether", "FarBot", null);
        assertEquals(1, ok(get(activity, "/api/v1/pearl")).getAsJsonArray("stations").size());
        JsonObject s = JsonParser.parseString(get(forced, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("pearl");
        assertFalse(s.get("away").getAsBoolean());
        assertTrue(s.get("trip").isJsonNull());
        assertEquals(2, s.size(), "no names, no ids");
    }

    // ---------- the bots ----------

    @Test
    void theHeartbeatCarriesTheTravellerAndTheLoaderAndChecksThem() throws Exception {
        start();
        heartbeat(null);
        assertEquals(200, travellerBeat(null, null, null).status);
        for (String bad : new String[]{"\"pearlTravel\":5", "\"pearlTravel\":{}", "\"pearlTravel\":" + travelBody(null, null, null).replace("\"game\":\"in\"", "\"game\":\"afk\""),
            "\"pearlTravel\":" + travelBody(null, null, null).replace("\"carried\":3", "\"carried\":-1"),
            "\"pearlTravel\":" + travelBody("nope", null, null), "\"pearlLoader\":5", "\"pearlLoader\":{}",
            "\"pearlTravel\":" + travelBody(null, "{\"id\":\"t-000000000000\",\"step\":\"flying\",\"pause\":null,\"error\":null,\"whispers\":0}", null)}) {
            assertEquals(400, heartbeat(bad).status, bad);
        }
        assertEquals(400, botPost("/api/v1/heartbeat", "{\"server\":\"s\",\"stash\":\"a\",\"pearlTravel\":" + travelBody(null, null, null) + "}").status, "needs the player");
        // a loader's own heartbeat: its report's player must be its player
        assertEquals(409, botPost("/api/v1/heartbeat", "{\"server\":\"s\",\"stash\":\"lf\",\"player\":\"Impostor\",\"pearlLoader\":" + loaderBody("FarBot", "Bob", "armed") + "}").status);
        String hub = station("Hub", "minecraft:the_nether", "FarBot", null);
        assertEquals(0, loaderBeat("FarBot", "lf", "Bob", "armed").obj().getAsJsonObject("pearlLoader").getAsJsonArray("allow").size(), "nobody is approved yet");
        JsonObject shown = pearl().getAsJsonArray("stations").get(0).getAsJsonObject().getAsJsonObject("loaderState");
        assertEquals("hb:s/lf", shown.getAsJsonArray("pending").get(0).getAsJsonObject().get("source").getAsString(), "the page shows the source waiting for the owner");
        assertEquals("lf", shown.getAsJsonArray("pending").get(0).getAsJsonObject().get("stash").getAsString());
        assertFalse(shown.get("approved").getAsBoolean());
        assertEquals(400, post(owner, "/api/v1/pearl/stations/approve", "{\"id\":\"" + hub + "\"}").status);
        assertEquals(409, post(owner, "/api/v1/pearl/stations/approve", "{\"id\":\"" + hub + "\",\"source\":\"hb:s/elsewhere\"}").status, "only the source shown");
        assertEquals(409, post(owner, "/api/v1/pearl/stations/dismiss", "{\"id\":\"" + hub + "\",\"source\":\"hb:s/elsewhere\"}").status);
        assertEquals(400, post(owner, "/api/v1/pearl/stations/dismiss", "{\"id\":\"" + hub + "\"}").status);
        assertEquals(200, post(owner, "/api/v1/pearl/stations/approve", "{\"id\":\"" + hub + "\",\"source\":\"hb:s/lf\"}").status, "This is my loader");
        Res other = loaderBeat("FarBot", "other-stash", "Bob", "armed");
        assertEquals(409, other.status, "a loader is bound to its approved source");
        assertTrue(other.body.contains("approve the new source on the station"), other.body);
        assertEquals(200, post(owner, "/api/v1/pearl/allowed", "{\"station\":\"" + hub + "\",\"player\":\"Bob\",\"allowed\":true}").status);
        assertEquals(400, post(owner, "/api/v1/pearl/allowed", "{\"station\":\"" + hub + "\",\"player\":\"Bob\"}").status);
        assertEquals(400, post(owner, "/api/v1/pearl/pins/reset", "{\"player\":\"Bob\",\"uuid\":\"nope\"}").status);
        assertEquals(200, post(owner, "/api/v1/pearl/pins/reset", "{\"player\":\"Bob\",\"uuid\":\"" + UUID + "\"}").status);
        assertEquals(400, post(owner, "/api/v1/pearl/pins/reset", "{\"player\":\"Stranger\",\"uuid\":\"" + UUID + "\"}").status, "not an approved name");
        assertEquals(200, post(owner, "/api/v1/pearl/assign", "{\"station\":\"" + hub + "\",\"player\":\"Bob\",\"chamber\":\"pearl-1\"}").status);
        JsonObject ok = loaderBeat("FarBot", "lf", "Bob", "armed").obj();
        JsonArray allow = ok.getAsJsonObject("pearlLoader").getAsJsonArray("allow");
        assertEquals("bob", allow.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(UUID, allow.get(0).getAsJsonObject().get("uuid").getAsString());
        assertEquals(30_000, ok.getAsJsonObject("pearlLoader").get("intervalMs").getAsInt());
        JsonObject st = pearl().getAsJsonArray("stations").get(0).getAsJsonObject();
        assertEquals("armed", st.getAsJsonArray("chambers").get(0).getAsJsonObject().get("state").getAsString());
        assertTrue(st.getAsJsonObject("loaderState").get("online").getAsBoolean());
        // the plugin loader's signed POST: needs a hello, and the report's player must be the hello's
        String hello = "{\"botId\":\"lb1\",\"player\":\"FarBot\",\"plugin\":{\"id\":\"qwerte-stash\",\"version\":\"1.18.0\"},\"proxy\":{\"version\":\"aq\",\"mc\":\"1.21.4\"}}";
        assertEquals(409, botPost("/api/v1/bot/pearls", "{\"botId\":\"lb1\"," + loaderBody("FarBot", "Bob", "armed").substring(1)).status, "say hello first");
        assertEquals(200, botPost("/api/v1/bot/hello", hello).status);
        assertEquals(409, botPost("/api/v1/bot/pearls", "{\"botId\":\"lb1\"," + loaderBody("FarBot", "Bob", "empty").substring(1)).status, "bound to the heartbeat source");
        assertEquals(200, post(owner, "/api/v1/pearl/stations", "{\"id\":\"" + hub + "\",\"name\":\"Hub\",\"server\":\"s\",\"dim\":\"minecraft:the_nether\",\"loader\":{\"player\":\"FarBot\"}}").status);
        assertEquals(409, botPost("/api/v1/bot/pearls", "{\"botId\":\"lb1\"," + loaderBody("FarBot", "Bob", "empty").substring(1)).status, "an edit keeps the approval");
        assertEquals(200, post(owner, "/api/v1/pearl/stations/approve", "{\"id\":\"" + hub + "\",\"source\":\"bot:lb1\"}").status);
        Res posted = botPost("/api/v1/bot/pearls", "{\"botId\":\"lb1\"," + loaderBody("FarBot", "Bob", "empty").substring(1));
        assertEquals(200, posted.status, posted.body);
        assertTrue(posted.obj().getAsJsonArray("allow").size() >= 1);
        assertEquals("empty", pearl().getAsJsonArray("stations").get(0).getAsJsonObject().getAsJsonArray("chambers").get(0).getAsJsonObject().get("state").getAsString());
        assertEquals(409, botPost("/api/v1/bot/pearls", "{\"botId\":\"lb1\"," + loaderBody("SomeoneElse", "Bob", "armed").substring(1)).status, "not the hello's player");
        assertEquals(400, botPost("/api/v1/bot/pearls", "{\"botId\":\"lb1\",\"v\":1}").status);
        assertEquals(400, botPost("/api/v1/bot/pearls", "{\"v\":1}").status);
    }

    @Test
    void awayIsClearedByTheOwnerOrByTheBotsHomeCommandAndBothAreAudited() throws Exception {
        start();
        heartbeat(null);
        String far = station("Hub", "minecraft:the_nether", "FarBot", null);
        String editor = userToken("edi", "{\"view\":true,\"activity\":true,\"settings\":true}");
        travellerBeat(far, null, null);
        JsonObject summary = JsonParser.parseString(get(owner, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("pearl");
        assertTrue(summary.get("away").getAsBoolean());
        assertEquals(403, post(editor, "/api/v1/pearl/away/clear", "{\"stash\":\"a\"}").status);
        assertEquals(200, post(owner, "/api/v1/pearl/away/clear", "{\"stash\":\"a\"}").status);
        assertTrue(travellerBeat(far, null, null).obj().get("pearlAwayClear").getAsBoolean(), "T is told until it reports itself home");
        assertTrue(travellerBeat(far, null, null).obj().get("pearlAwayClear").getAsBoolean(), "a lost reply is repeated");
        assertFalse(travellerBeat(null, null, null).obj().has("pearlAwayClear"));
        // the in-game command
        travellerBeat(far, null, null);
        assertFalse(travellerBeat(far, null, "{\"localId\":null,\"action\":\"home\",\"station\":null,\"oneWay\":false}").obj().has("pearlAwayClear"), "no completed trip: not honoured");
        String audit = get(owner, "/api/v1/audit").body;
        assertTrue(audit.contains("pearl.away.clear") && !audit.contains("(plugin)"), audit);
    }

    @Test
    void anAssignmentNeedsThePearlToBeTheTravellersOwn() throws Exception {
        start();
        heartbeat(null);
        String far = station("Hub", "minecraft:the_nether", "FarBot", null);
        travellerBeat(null, null, null);
        loaderBeat("FarBot", "lf", "Bob", "armed");
        assertEquals(200, post(owner, "/api/v1/pearl/assign", "{\"station\":\"" + far + "\",\"player\":\"Bob\",\"chamber\":\"pearl-1\"}").status);
        assertEquals("pearl-1", pearl().getAsJsonArray("stations").get(0).getAsJsonObject().getAsJsonObject("assign").get("bob").getAsString());
        assertEquals(400, post(owner, "/api/v1/pearl/assign", "{\"station\":\"" + far + "\",\"player\":\"Eve\",\"chamber\":\"pearl-1\"}").status);
        assertEquals(400, post(owner, "/api/v1/pearl/assign", "{\"station\":\"" + far + "\",\"player\":\"Bob\",\"chamber\":\"nope-9\"}").status);
        assertEquals(200, post(owner, "/api/v1/pearl/assign", "{\"station\":\"" + far + "\",\"player\":\"Bob\",\"chamber\":null}").status);
        // a station can only be deleted while no trip uses it; a broken chamber is reset by the owner
        assertEquals(200, post(owner, "/api/v1/pearl/chambers/reset", "{\"station\":\"" + far + "\",\"chamber\":\"pearl-1\"}").status);
        assertEquals(404, post(owner, "/api/v1/pearl/chambers/reset", "{\"station\":\"ps-00000000\",\"chamber\":\"pearl-1\"}").status);
        assertEquals(200, post(owner, "/api/v1/pearl/stations/delete", "{\"id\":\"" + far + "\"}").status);
        assertEquals(0, pearl().getAsJsonArray("stations").size());
        String audit = get(owner, "/api/v1/audit").body;
        assertTrue(audit.contains("pearl.assign") && audit.contains("pearl.chamber.reset") && audit.contains("pearl.station.delete"), audit);
    }

    @Test
    void aTripSurvivesARestartAndThePearlFileIsOwnerOnlyWithABackup() throws Exception {
        start();
        heartbeat(null);
        String far = station("Hub", "minecraft:the_nether", "FarBot", null);
        travellerBeat(null, null, null);
        ok(post(owner, "/api/v1/pearl/trips", "{" + KEY + ",\"action\":\"go\",\"station\":\"" + far + "\",\"oneWay\":true}"));
        assertTrue(java.nio.file.Files.exists(data.resolve("pearl.json")));
        station("Second", "minecraft:overworld", "Other", null);
        assertTrue(java.nio.file.Files.exists(data.resolve("pearl.json.bak")), "the previous clean file is kept");
        stop();
        start();
        JsonObject v = pearl();
        assertEquals(2, v.getAsJsonArray("stations").size());
        JsonObject t = v.getAsJsonArray("trips").get(0).getAsJsonObject();
        assertEquals("failed", t.get("state").getAsString());
        assertEquals("server_restarted", t.getAsJsonObject("error").get("code").getAsString());
    }
}
