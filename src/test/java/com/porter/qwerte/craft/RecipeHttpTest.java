package com.porter.qwerte.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/** The signed GET of the recipe table over a real socket: the signature is checked both ways, If-None-Match is sent, the ETag is read whatever its casing. */
class RecipeHttpTest {
    private static final String KEY = "k".repeat(32);
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private interface Answer {
        void send(com.sun.net.httpserver.HttpExchange ex, String nonce) throws Exception;
    }

    private static HttpServer serve(Answer answer, AtomicReference<String> auth, AtomicReference<String> match) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/bot/recipes", ex -> {
            try {
                auth.set(ex.getRequestHeaders().getFirst("X-SM-Auth"));
                match.set(ex.getRequestHeaders().getFirst("If-None-Match"));
                answer.send(ex, auth.get().split(" ")[3]);
            } catch (Exception e) {
                ex.sendResponseHeaders(500, -1);
            }
            ex.close();
        });
        server.start();
        return server;
    }

    private static URI url(HttpServer s) {
        return URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/api/v1/bot/recipes?mc=1.21.4");
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String nonce, int code, byte[] signed, byte[] wire, String etag, String encoding) throws Exception {
        if (etag != null) ex.getResponseHeaders().add("Etag", "\"" + etag + "\"");
        if (encoding != null) ex.getResponseHeaders().add("Content-Encoding", encoding);
        ex.getResponseHeaders().add("X-SM-Sig", RecipeHttp.responseHeader(RecipeHttp.signingKey(KEY), nonce, code, System.currentTimeMillis(), signed));
        ex.sendResponseHeaders(code, wire.length == 0 ? -1 : wire.length);
        if (wire.length > 0) ex.getResponseBody().write(wire);
    }

    @Test
    void aSignedGetCarriesNoBodyAndTheEtagIsReadWhateverItsCasing() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>(), match = new AtomicReference<>();
        String body = TestRecipes.json(), hash = TestRecipes.table().hash();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpServer server = serve((ex, nonce) -> reply(ex, nonce, 200, bytes, bytes, hash, null), auth, match);
        try {
            RecipeHttp.Response r = RecipeHttp.get(HTTP, url(server), KEY, RecipeStore.quote("cd".repeat(20)), Duration.ofSeconds(5));
            assertEquals(200, r.code());
            assertEquals(body, r.body());
            assertEquals(hash, RecipeStore.hashOfEtag(r.etag()));
            assertEquals(RecipeStore.quote("cd".repeat(20)), match.get());
            // v1 - <ts> <nonce> <sig>: the addon has no bot id; the key itself is nowhere in it
            String[] a = auth.get().split(" ");
            assertEquals(5, a.length);
            assertEquals("v1", a[0]);
            assertEquals("-", a[1]);
            assertFalse(auth.get().contains(KEY));
            // The signature is over GET, the path with its query, the time, the nonce and the hash of an empty body
            assertEquals(RecipeHttp.authHeader(RecipeHttp.signingKey(KEY), "GET", "/api/v1/bot/recipes?mc=1.21.4", Long.parseLong(a[2]), a[3]), auth.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anAnswerNotSignedForThisRequestIsDropped() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>(), match = new AtomicReference<>();
        byte[] b = "{}".getBytes(StandardCharsets.UTF_8);
        // Signed with another key
        HttpServer wrongKey = serve((ex, nonce) -> {
            ex.getResponseHeaders().add("X-SM-Sig", RecipeHttp.responseHeader(RecipeHttp.signingKey("other".repeat(8)), nonce, 200, System.currentTimeMillis(), b));
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
        }, auth, match);
        // No signature at all
        HttpServer none = serve((ex, nonce) -> {
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
        }, auth, match);
        // Signed for another nonce (a replayed answer)
        HttpServer replay = serve((ex, nonce) -> {
            ex.getResponseHeaders().add("X-SM-Sig", RecipeHttp.responseHeader(RecipeHttp.signingKey(KEY), "0".repeat(32), 200, System.currentTimeMillis(), b));
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
        }, auth, match);
        try {
            for (HttpServer s : new HttpServer[] {wrongKey, none, replay}) {
                RecipeHttp.Response r = RecipeHttp.get(HTTP, url(s), KEY, null, Duration.ofSeconds(5));
                assertEquals(RecipeHttp.BAD_SIGNATURE, r.code());
                assertEquals("", r.body());
                assertNull(r.etag());
            }
        } finally {
            wrongKey.stop(0);
            none.stop(0);
            replay.stop(0);
        }
    }

    @Test
    void aRefusalOrAServerErrorMayComeUnsignedButAnythingElseMustNot() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>(), match = new AtomicReference<>();
        for (int code : new int[] {401, 403, 503}) {
            HttpServer s = serve((ex, nonce) -> ex.sendResponseHeaders(code, -1), auth, match);
            try {
                assertEquals(code, RecipeHttp.get(HTTP, url(s), KEY, null, Duration.ofSeconds(5)).code());
            } finally {
                s.stop(0);
            }
        }
        // 404 must be signed
        HttpServer s = serve((ex, nonce) -> ex.sendResponseHeaders(404, -1), auth, match);
        try {
            assertEquals(RecipeHttp.BAD_SIGNATURE, RecipeHttp.get(HTTP, url(s), KEY, null, Duration.ofSeconds(5)).code());
        } finally {
            s.stop(0);
        }
    }

    @Test
    void notModifiedHasNoBodyAndAGzippedTableIsSignedAsItIsUncompressed() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>(), match = new AtomicReference<>();
        HttpServer notModified = serve((ex, nonce) -> reply(ex, nonce, 304, new byte[0], new byte[0], null, null), auth, match);
        String body = TestRecipes.json();
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(raw);
        }
        HttpServer zipped = serve((ex, nonce) -> reply(ex, nonce, 200, raw, gz.toByteArray(), "ab".repeat(20), "gzip"), auth, match);
        try {
            RecipeHttp.Response r = RecipeHttp.get(HTTP, url(notModified), KEY, RecipeStore.quote("ab".repeat(20)), Duration.ofSeconds(5));
            assertEquals(304, r.code());
            assertEquals("", r.body());
            RecipeHttp.Response z = RecipeHttp.get(HTTP, url(zipped), KEY, null, Duration.ofSeconds(5));
            assertEquals(200, z.code());
            assertEquals(body, z.body());
            assertNotNull(z.etag());
        } finally {
            notModified.stop(0);
            zipped.stop(0);
        }
    }

    @Test
    void anAnswerBeyondTheCapAndAServerThatIsNotThereAreFailures() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>(), match = new AtomicReference<>();
        byte[] big = new byte[(int) RecipeStore.MAX_BYTES + 10];
        java.util.Arrays.fill(big, (byte) ' ');
        HttpServer s = serve((ex, nonce) -> reply(ex, nonce, 200, big, big, null, null), auth, match);
        try {
            assertEquals(-1, RecipeHttp.get(HTTP, url(s), KEY, null, Duration.ofSeconds(10)).code());
        } finally {
            s.stop(0);
        }
        assertEquals(-1, RecipeHttp.get(HTTP, URI.create("http://127.0.0.1:1/api/v1/bot/recipes?mc=1.21.4"), KEY, null, Duration.ofSeconds(2)).code());
    }

    @Test
    void aBodyThatTricklesInIsCutAtTheDeadline() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>(), match = new AtomicReference<>();
        HttpServer s = serve((ex, nonce) -> {
            ex.sendResponseHeaders(200, 100_000);
            ex.getResponseBody().write(new byte[10]);
            ex.getResponseBody().flush();
            Thread.sleep(15_000);
        }, auth, match);
        try {
            long t0 = System.nanoTime();
            assertEquals(-1, RecipeHttp.get(HTTP, url(s), KEY, null, Duration.ofSeconds(2)).code());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            org.junit.jupiter.api.Assertions.assertTrue(ms < 8_000, "cut after " + ms + " ms");
        } finally {
            s.stop(0);
        }
    }
}
