package com.porter.qwerte.manager.net;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The client against a throwaway local server: where the cookie goes, what is not followed, how a 401 is handled. */
class SmClientTest {
    private HttpServer server;
    private HttpServer other;
    private final CopyOnWriteArrayList<String> cookiesSeen = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> otherCookiesSeen = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> postHeaders = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> uploads = new CopyOnWriteArrayList<>();
    private SmClient client;

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }

    @BeforeEach
    void start() throws IOException {
        other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/", ex -> {
            otherCookiesSeen.add(String.valueOf(ex.getRequestHeaders().getFirst("Cookie")));
            reply(ex, 200, "{}");
        });
        other.start();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/echo", ex -> {
            cookiesSeen.add(String.valueOf(ex.getRequestHeaders().getFirst("Cookie")));
            reply(ex, 200, "{\"ok\":true}");
        });
        server.createContext("/api/v1/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + other.getAddress().getPort() + "/stolen");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/api/v1/upload", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            uploads.add(ex.getRequestMethod() + "|" + ex.getRequestURI().getRawQuery() + "|" + ex.getRequestHeaders().getFirst("Content-Type")
                + "|" + ex.getRequestHeaders().getFirst("X-Stash-Manager") + "|" + ex.getRequestHeaders().getFirst("Origin")
                + "|" + ex.getRequestHeaders().getFirst("Sec-Fetch-Site") + "|" + ex.getRequestHeaders().getFirst("Cookie") + "|" + in.length
                + "|" + (in.length > 2 ? in[2] : -1));
            reply(ex, 200, "{\"ok\":true}");
        });
        server.createContext("/api/v1/gone", ex -> reply(ex, 401, "{\"ok\":false,\"error\":\"login required\"}"));
        server.createContext("/api/v1/auth/login", ex -> {
            postHeaders.add(ex.getRequestHeaders().getFirst("X-Stash-Manager") + "|" + ex.getRequestHeaders().getFirst("Content-Type")
                + "|" + ex.getRequestHeaders().getFirst("Origin") + "|" + ex.getRequestHeaders().getFirst("Cookie"));
            ex.getResponseHeaders().add("Set-Cookie", "sm_session=NEWTOKEN_1-2; HttpOnly; SameSite=Strict; Path=/; Max-Age=2592000");
            reply(ex, 200, "{\"ok\":true,\"mustChange\":false}");
        });
        server.start();

        client = new SmClient(Runnable::run);
        client.setBase(ServerUrl.parse("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
        other.stop(0);
    }

    private ApiResult get(String path) throws Exception {
        AtomicReference<ApiResult> out = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        client.get(path, null, r -> {
            out.set(r);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "no answer");
        return out.get();
    }

    @Test
    void aRefusedAddressIsNeverContacted() throws Exception {
        client.setAllowed(u -> false);
        ApiResult r = get("/api/v1/echo");
        assertEquals(ApiError.Kind.NETWORK, r.kind());
        assertTrue(cookiesSeen.isEmpty(), "nothing may be sent to a refused address");
        assertThrows(IOException.class, () -> client.openStream("/api/v1/echo", null));
        // allowed again (the setting changed): it works
        client.setAllowed(u -> true);
        assertTrue(get("/api/v1/echo").ok());
        // a refused address given to setBase is refused too
        client.setBase(ServerUrl.parse("http://127.0.0.1:" + server.getAddress().getPort()));
        client.setAllowed(u -> u.secure());
        assertEquals(ApiError.Kind.NETWORK, get("/api/v1/echo").kind());
    }

    @Test
    void theServersDateHeaderIsExposed() throws Exception {
        ApiResult r = get("/api/v1/echo");
        long now = System.currentTimeMillis();
        assertTrue(r.serverTimeMillis() > 0 && Math.abs(r.serverTimeMillis() - now) < 60_000, "Date header: " + r.serverTimeMillis());
    }

    @Test
    void turningTheClientOffClosesOpenStreams() throws Exception {
        SmClient.Open open = client.openStream("/api/v1/echo", null);
        assertEquals(200, open.status());
        client.setEnabled(false);
        assertThrows(IOException.class, () -> client.openStream("/api/v1/echo", null));
    }

    @Test
    void theCookieIsSentOnlyWhenOneIsSetAndOnlyToItsOrigin() throws Exception {
        assertTrue(get("/api/v1/echo").ok());
        assertEquals("null", cookiesSeen.get(0));

        String origin = ServerUrl.parse("http://127.0.0.1:" + server.getAddress().getPort()).origin();
        client.setToken("TOKEN123", origin);
        assertTrue(get("/api/v1/echo").ok());
        assertEquals("sm_session=TOKEN123", cookiesSeen.get(1));

        // a token issued for another origin is never sent
        client.setToken("TOKEN123", "http://127.0.0.1:1");
        assertTrue(get("/api/v1/echo").ok());
        assertEquals("null", cookiesSeen.get(2));
    }

    @Test
    void redirectsAreNotFollowedAndTheCookieDoesNotLeave() throws Exception {
        String origin = ServerUrl.parse("http://127.0.0.1:" + server.getAddress().getPort()).origin();
        client.setToken("TOKEN123", origin);
        ApiResult r = get("/api/v1/redirect");
        assertEquals(ApiError.Kind.REDIRECT, r.kind());
        assertEquals(302, r.status());
        assertTrue(otherCookiesSeen.isEmpty(), "the other server must not be contacted");
    }

    @Test
    void a401WithTheCookieCallsTheHookAndDropsTheAnswer() throws Exception {
        String origin = ServerUrl.parse("http://127.0.0.1:" + server.getAddress().getPort()).origin();
        client.setToken("TOKEN123", origin);
        CountDownLatch hooked = new CountDownLatch(1);
        client.onUnauthorized(hooked::countDown);
        AtomicReference<ApiResult> delivered = new AtomicReference<>();
        client.get("/api/v1/gone", null, delivered::set);
        assertTrue(hooked.await(10, TimeUnit.SECONDS));
        assertNull(delivered.get());
    }

    @Test
    void anAnswerOvertakenByANewGenerationIsDropped() throws Exception {
        AtomicReference<ApiResult> delivered = new AtomicReference<>();
        client.get("/api/v1/echo", null, delivered::set);
        client.bumpGeneration();
        Thread.sleep(500);
        assertNull(delivered.get());
    }

    @Test
    void loginSendsTheGuardHeadersWithoutOriginOrCookieAndReadsTheNewToken() throws Exception {
        String origin = ServerUrl.parse("http://127.0.0.1:" + server.getAddress().getPort()).origin();
        client.setToken("OLDTOKEN", origin);
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<ApiResult> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        client.login("bob", "pw", (r, t) -> {
            result.set(r);
            token.set(t);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(result.get().ok());
        assertEquals("NEWTOKEN_1-2", token.get());
        assertEquals("1|application/json|null|null", postHeaders.get(0));
    }

    @Test
    void aDisabledClientMakesNoRequestEvenWithAnAddressAndToken() throws Exception {
        String origin = ServerUrl.parse("http://127.0.0.1:" + server.getAddress().getPort()).origin();
        client.setToken("TOKEN123", origin);
        client.setEnabled(false);
        ApiResult r = get("/api/v1/echo");
        assertEquals(ApiError.Kind.NETWORK, r.kind());
        AtomicReference<ApiResult> login = new AtomicReference<>();
        client.login("bob", "pw", (res, t) -> login.set(res));
        Thread.sleep(300);
        assertEquals(ApiError.Kind.NETWORK, login.get().kind());
        assertTrue(cookiesSeen.isEmpty(), "the server must not have been contacted");
        assertTrue(postHeaders.isEmpty(), "the server must not have been contacted");

        client.setEnabled(true);
        assertTrue(get("/api/v1/echo").ok());
        assertEquals(1, cookiesSeen.size());
    }

    @Test
    void anAnnouncedBodyOverTheCapIsRefusedUpFront() throws Exception {
        server.createContext("/api/v1/huge", ex -> {
            ex.sendResponseHeaders(200, 33L * 1024 * 1024);
            ex.close();
        });
        ApiResult r = get("/api/v1/huge");
        assertEquals(ApiError.Kind.NETWORK, r.kind());
    }

    @Test
    void changingTheBaseDropsAnswersStillOnTheirWay() throws Exception {
        AtomicReference<ApiResult> delivered = new AtomicReference<>();
        client.get("/api/v1/echo", null, delivered::set);
        client.setBase(ServerUrl.parse("http://127.0.0.1:" + other.getAddress().getPort()));
        Thread.sleep(500);
        assertNull(delivered.get());
    }

    @Test
    void anUnreachableServerIsANetworkError() throws Exception {
        client.setBase(ServerUrl.parse("http://127.0.0.1:1"));
        ApiResult r = get("/api/v1/echo");
        assertEquals(ApiError.Kind.NETWORK, r.kind());
    }

    private ApiResult postBytes(String path, java.util.Map<String, String> query, byte[] body) throws Exception {
        AtomicReference<ApiResult> out = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        client.postBytes(path, query, body, SmClient.UPLOAD_TIMEOUT, r -> {
            out.set(r);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "no answer");
        return out.get();
    }

    @Test
    void postBytesSendsTheRawBodyWithTheGuardHeadersAndTheCookieButNoOrigin() throws Exception {
        String origin = ServerUrl.parse("http://127.0.0.1:" + server.getAddress().getPort()).origin();
        client.setToken("TOKEN123", origin);
        java.util.Map<String, String> q = new java.util.LinkedHashMap<>();
        q.put("server", "a b&c");
        q.put("name", "my house.litematic");
        assertTrue(postBytes("/api/v1/upload", q, new byte[]{1, 2, 3, 4, 5}).ok());
        assertEquals("POST|server=a%20b%26c&name=my%20house.litematic|application/octet-stream|1|null|null|sm_session=TOKEN123|5|3", uploads.get(0));
    }

    @Test
    void postBytesRefusesABodyOverEightMiBWithoutSendingIt() throws Exception {
        String origin = ServerUrl.parse("http://127.0.0.1:" + server.getAddress().getPort()).origin();
        client.setToken("TOKEN123", origin);
        ApiResult r = postBytes("/api/v1/upload", null, new byte[SmClient.MAX_UPLOAD + 1]);
        assertEquals(ApiError.Kind.BAD_REQUEST, r.kind());
        assertEquals(413, r.status());
        assertTrue(uploads.isEmpty(), "nothing is sent");
        assertTrue(postBytes("/api/v1/upload", null, new byte[SmClient.MAX_UPLOAD]).ok());
    }

    @Test
    void postBytesFollowsNoRedirectAndNeverContactsARefusedAddress() throws Exception {
        String origin = ServerUrl.parse("http://127.0.0.1:" + server.getAddress().getPort()).origin();
        client.setToken("TOKEN123", origin);
        ApiResult r = postBytes("/api/v1/redirect", null, new byte[]{1});
        assertEquals(ApiError.Kind.REDIRECT, r.kind());
        assertTrue(otherCookiesSeen.isEmpty(), "the other server must not be contacted");

        client.setAllowed(u -> false);
        assertEquals(ApiError.Kind.NETWORK, postBytes("/api/v1/upload", null, new byte[]{1}).kind());
        assertTrue(uploads.isEmpty(), "a refused address is never contacted");
    }
}
