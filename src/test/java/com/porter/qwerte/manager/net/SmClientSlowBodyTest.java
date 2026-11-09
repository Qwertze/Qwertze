package com.porter.qwerte.manager.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bodies that arrive slowly (headers first, bytes trickled). The client must not park its own threads on such a body: with more
 * requests than threads the window used to sit on "Connecting..." forever.
 */
class SmClientSlowBodyTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService serverPool;
    private SmClient client;

    private static void trickle(HttpExchange ex, byte[] body, int chunks, long sleepMs) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        OutputStream out = ex.getResponseBody();
        out.flush();
        int step = Math.max(1, body.length / chunks);
        try {
            for (int i = 0; i < body.length; i += step) {
                Thread.sleep(sleepMs);
                out.write(body, i, Math.min(step, body.length - i));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // the client gave up
        }
        ex.close();
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "slow-body-test-server");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(serverPool);
        server.createContext("/api/v1/slow", ex -> {
            String q = ex.getRequestURI().getRawQuery();
            String body = "{\"ok\":true,\"q\":\"" + q + "\",\"pad\":\"" + "x".repeat(400) + "\"}";
            trickle(ex, body.getBytes(StandardCharsets.UTF_8), 8, 120);
        });
        server.createContext("/api/v1/fast", ex -> {
            byte[] b = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/api/v1/stall", ex -> {
            ex.sendResponseHeaders(200, 1000);
            ex.getResponseBody().write("{\"ok\":".getBytes(StandardCharsets.UTF_8));
            ex.getResponseBody().flush();
            try {
                Thread.sleep(20_000);
            } catch (InterruptedException ignored) {
                // test over
            }
            ex.close();
        });
        server.createContext("/api/v1/bomb", ex -> {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos)) {
                byte[] chunk = new byte[1024 * 1024];
                java.util.Arrays.fill(chunk, (byte) ' ');
                for (int i = 0; i < 40; i++) gz.write(chunk);
            }
            byte[] z = bos.toByteArray();
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
            ex.sendResponseHeaders(200, z.length);
            ex.getResponseBody().write(z);
            ex.close();
        });
        server.createContext("/api/v1/announced", ex -> {
            ex.sendResponseHeaders(200, 100L * 1024 * 1024);
            try (OutputStream out = ex.getResponseBody()) {
                byte[] chunk = new byte[64 * 1024];
                for (int i = 0; i < 1600; i++) out.write(chunk);
            } catch (IOException ignored) {
                // the client aborted
            }
        });
        server.createContext("/api/v1/slow401", ex -> {
            byte[] body = ("{\"ok\":false,\"error\":\"login required\",\"pad\":\"" + "x".repeat(300) + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(401, body.length);
            OutputStream out = ex.getResponseBody();
            out.flush();
            try {
                for (int i = 0; i < body.length; i += 100) {
                    Thread.sleep(100);
                    out.write(body, i, Math.min(100, body.length - i));
                    out.flush();
                }
            } catch (InterruptedException | IOException ignored) {
                // gone
            }
            ex.close();
        });
        server.createContext("/api/v1/huge", ex -> {
            // announced size unknown (chunked), more than the cap
            ex.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[1024 * 1024];
            java.util.Arrays.fill(chunk, (byte) ' ');
            try (OutputStream out = ex.getResponseBody()) {
                for (int i = 0; i < 40; i++) out.write(chunk);
            } catch (IOException ignored) {
                // the client gave up at the cap
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        serverPool.shutdownNow();
    }

    private SmClient newClient(Duration bodyDeadline) {
        SmClient c = bodyDeadline == null ? new SmClient(Runnable::run) : new SmClient(Runnable::run, bodyDeadline);
        c.setBase(ServerUrl.parse("http://127.0.0.1:" + server.getAddress().getPort()));
        return c;
    }

    private ApiResult await(SmClient c, String path, String query) throws Exception {
        AtomicReference<ApiResult> out = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        c.get(path, query == null ? null : java.util.Map.of("n", query), r -> {
            out.set(r);
            done.countDown();
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "no answer for " + path);
        return out.get();
    }

    /** Request timeout 300 ms, so the whole answer must be in 1 s. */
    private ApiResult awaitShort(SmClient c, String path) throws Exception {
        AtomicReference<ApiResult> out = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        c.get(path, null, null, false, Duration.ofMillis(300), null, (r, t) -> {
            out.set(r);
            done.countDown();
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "no answer for " + path);
        return out.get();
    }

    @Test
    void manyConcurrentSlowBodiesAllCompleteAndLaterRequestsStillWork() throws Exception {
        client = newClient(null);
        int n = 6;
        java.util.List<AtomicReference<ApiResult>> results = new java.util.ArrayList<>();
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            AtomicReference<ApiResult> ref = new AtomicReference<>();
            results.add(ref);
            client.get("/api/v1/slow", java.util.Map.of("n", "r" + i), r -> {
                ref.set(r);
                done.countDown();
            });
        }
        assertTrue(done.await(15, TimeUnit.SECONDS), "slow bodies never completed");
        for (int i = 0; i < n; i++) {
            ApiResult r = results.get(i).get();
            assertTrue(r.ok(), "request " + i + " kind " + r.kind());
            assertEquals("n=r" + i, r.body().getAsJsonObject().get("q").getAsString());
            assertEquals(400, r.body().getAsJsonObject().get("pad").getAsString().length());
        }
        assertTrue(await(client, "/api/v1/fast", null).ok(), "a later request must still work");
        // and again after a second wave, as with a window opened twice
        CountDownLatch second = new CountDownLatch(n);
        for (int i = 0; i < n; i++) client.get("/api/v1/slow", java.util.Map.of("n", "s" + i), r -> second.countDown());
        assertTrue(second.await(15, TimeUnit.SECONDS), "second wave never completed");
        assertTrue(await(client, "/api/v1/fast", null).ok());
    }

    @Test
    void aBodyThatStallsForeverHitsTheDeadlineAsANetworkError() throws Exception {
        client = newClient(Duration.ofMillis(700));
        long t0 = System.nanoTime();
        ApiResult r = awaitShort(client, "/api/v1/stall");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(ApiError.Kind.NETWORK, r.kind());
        assertTrue(ms < 8_000, "took " + ms + " ms");
        // the client is not wedged
        assertTrue(await(client, "/api/v1/fast", null).ok());
    }

    @Test
    void stalledBodiesDoNotUseUpTheThreads() throws Exception {
        client = newClient(Duration.ofMillis(700));
        int n = 5;
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) client.get("/api/v1/stall", null, null, false, Duration.ofMillis(300), null, (r, t) -> done.countDown());
        assertTrue(done.await(15, TimeUnit.SECONDS), "stalled requests never failed");
        assertTrue(await(client, "/api/v1/fast", null).ok());
    }

    @Test
    void aGzipBombFailsAsANetworkError() throws Exception {
        client = newClient(null);
        assertEquals(ApiError.Kind.NETWORK, await(client, "/api/v1/bomb", null).kind());
        assertTrue(await(client, "/api/v1/fast", null).ok());
    }

    @Test
    void anAnnouncedLengthOverTheCapIsRefusedFast() throws Exception {
        client = newClient(null);
        long t0 = System.nanoTime();
        assertEquals(ApiError.Kind.NETWORK, await(client, "/api/v1/announced", null).kind());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 5_000, "took " + ms + " ms");
        assertTrue(await(client, "/api/v1/fast", null).ok());
    }

    @Test
    void a401WithASlowBodyStillFiresTheUnauthorizedHook() throws Exception {
        client = newClient(null);
        client.setToken("TOKEN_1", "http://127.0.0.1:" + server.getAddress().getPort());
        CountDownLatch hooked = new CountDownLatch(1);
        client.onUnauthorized(hooked::countDown);
        client.get("/api/v1/slow401", null, r -> {
        });
        assertTrue(hooked.await(15, TimeUnit.SECONDS), "hook never fired");
    }

    @Test
    void aBodyOverTheCapFailsAsANetworkError() throws Exception {
        client = newClient(null);
        ApiResult r = await(client, "/api/v1/huge", null);
        assertEquals(ApiError.Kind.NETWORK, r.kind());
        assertTrue(await(client, "/api/v1/fast", null).ok());
    }
}
