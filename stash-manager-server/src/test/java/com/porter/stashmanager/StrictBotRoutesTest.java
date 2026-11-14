package com.porter.stashmanager;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-CONTRACT R1 and R2: bot routes are strict by flag, with a test that enumerates every bot route and its class, and a
 * signed request older than the server's start is refused. allowBearer is on, so a lenient route is visibly lenient.
 */
class StrictBotRoutesTest extends FarmHarness {
    {
        extraConfig = "allowBearer=true";
    }

    /** The bot routes that existed before the strict flag: the only ones that may stay lenient. A new one must not join without a reason. */
    static final List<String> LEGACY = List.of(
        "[POST] /api/v1/index ADDON_KEY_LEGACY",
        "[POST] /api/v1/heartbeat ADDON_KEY_LEGACY",
        "[POST] /api/v1/delivery ADDON_KEY_LEGACY",
        "[POST] /api/v1/orders/claim ADDON_KEY_LEGACY",
        "[POST] ^/api/v1/orders/(o-[0-9a-f]{12})/status$ ADDON_KEY_LEGACY",
        "[POST] /api/v1/kits/report ADDON_KEY_LEGACY",
        "[POST] /api/v1/repair/report ADDON_KEY_LEGACY");

    private static boolean isBotRoute(String line) {
        return line.endsWith(" ADDON_KEY") || line.endsWith(" ADDON_KEY_LEGACY");
    }

    @Test
    void everyBotRouteIsStrictOrExplicitlyLegacy() {
        List<String> bot = new ArrayList<>();
        List<String> unlisted = new ArrayList<>();
        for (String line : router.describe()) {
            if (!isBotRoute(line)) continue;
            bot.add(line);
            if (line.endsWith(" ADDON_KEY_LEGACY") && !LEGACY.contains(line)) unlisted.add(line);
            // The prefixes that were always strict, and the task routes, can never be legacy.
            if (line.contains(" /api/v1/bot/") || line.contains("/api/v1/tasks/")) {
                assertTrue(line.endsWith(" ADDON_KEY"), "must be strict: " + line);
            }
        }
        assertTrue(unlisted.isEmpty(), "a lenient bot route that is not in the legacy list: " + unlisted);
        // Every legacy entry is really registered (no stale list), and the new routes are there and strict.
        for (String l : LEGACY) assertTrue(bot.contains(l), "not registered: " + l);
        for (String strict : List.of("[POST] /api/v1/tasks/sync ADDON_KEY", "[POST] ^/api/v1/tasks/(t-[0-9a-f]{12})/claim$ ADDON_KEY",
            "[POST] ^/api/v1/tasks/(t-[0-9a-f]{12})/report$ ADDON_KEY", "[POST] /api/v1/bot/activity ADDON_KEY", "[POST] /api/v1/bot/viewer ADDON_KEY")) {
            assertTrue(bot.contains(strict), "missing strict route: " + strict);
        }
        // Nothing else is a bot route under another guard name (a guard we forgot to class would show up here).
        for (String line : router.describe()) {
            if (line.contains("ADDON_KEY")) assertTrue(isBotRoute(line), line);
        }
    }

    private HttpResponse<String> bearer(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + config.apiKey())
            .header("Content-Type", "application/json");
        b = method.equals("GET") ? b.GET() : b.POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aStrictRouteNeverTakesBearerEvenWithAllowBearerOn() throws Exception {
        int checked = 0;
        for (String line : router.describe()) {
            if (!line.endsWith(" ADDON_KEY")) continue;
            Matcher m = Pattern.compile("^\\[([A-Z, ]+)\\] (/\\S+) ADDON_KEY$").matcher(line);
            if (!m.matches()) continue; // a pattern route: the exact ones cover the class
            String method = m.group(1).contains("POST") ? "POST" : "GET";
            HttpResponse<String> r = bearer(method, m.group(2), "{}");
            assertEquals(401, r.statusCode(), line + " " + r.body());
            checked++;
        }
        assertTrue(checked >= 7, "checked " + checked);
        // The patterns too.
        assertEquals(401, bearer("POST", "/api/v1/tasks/t-0123456789ab/claim", "{}").statusCode());
        assertEquals(401, bearer("POST", "/api/v1/tasks/t-0123456789ab/report", "{}").statusCode());
        // The lenient legacy route does accept it (that is the difference the flag makes).
        assertNotEquals(401, bearer("POST", "/api/v1/heartbeat", "{\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\"}").statusCode());
    }

    @Test
    void thePreviousKeyIsNotAcceptedOnTaskRoutes() throws Exception {
        byte[] old = k;
        config.regenerateKey();
        byte[] fresh = Signer.deriveKey(config.apiKey());
        String body = syncBody("plug", null);
        long ts = System.currentTimeMillis();
        assertEquals(401, signed("POST", "/api/v1/tasks/sync", body, "plug", ts, old).status());
        assertEquals(401, signed("POST", "/api/v1/tasks/t-0123456789ab/report", "{}", "plug", ts, old).status());
        assertEquals(401, signed("POST", "/api/v1/bot/hello", "{}", "-", ts, old).status());
        // The legacy class still takes it for 10 minutes after a regenerate; the new key works everywhere.
        assertEquals(200, signed("POST", "/api/v1/heartbeat", "{\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\"}", "-", ts, old).status());
        assertEquals(200, signed("POST", "/api/v1/tasks/sync", body, "plug", ts, fresh).status());
    }

    @Test
    void aTwoMebibyteBodyIsRefusedOnTaskRoutesButNotOnLegacyOnes() throws Exception {
        String big = "{\"botId\":\"plug\",\"pad\":\"" + "a".repeat(2 * 1024 * 1024) + "\"}";
        assertEquals(413, refused(() -> bot("plug", "/api/v1/tasks/sync", big)));
        assertEquals(413, refused(() -> bot("plug", "/api/v1/tasks/t-0123456789ab/report", big)));
        assertEquals(413, refused(() -> botAnon("/api/v1/bot/results", big)));
        assertNotEquals(413, botAnon("/api/v1/heartbeat", big).status(), "legacy routes keep their 8 MiB");
        // Between 64 KiB and 1 MiB the handler's own limit applies.
        String mid = "{\"botId\":\"plug\",\"pad\":\"" + "a".repeat(100 * 1024) + "\"}";
        assertEquals(413, bot("plug", "/api/v1/tasks/sync", mid).status());
    }

    interface Call {
        Res run() throws Exception;
    }

    /**
     * 413, or the connection cut: the server answers a body over the limit from its Content-Length without reading it, so the JDK client
     * can see the connection close while it is still sending 2 MiB.
     */
    private static int refused(Call c) throws Exception {
        try {
            return c.run().status();
        } catch (java.io.IOException e) {
            return 413;
        }
    }

    @Test
    void aSignedRequestOlderThanTheServersStartIsRefused() throws Exception {
        hello("plug");
        long start = System.currentTimeMillis() + 5_000; // the process "started" five seconds from now
        router.signer().notBefore(start);
        String body = syncBody("plug", null);
        Res old = signed("POST", "/api/v1/tasks/sync", body, "plug", System.currentTimeMillis(), k);
        assertEquals(401, old.status());
        assertTrue(old.body().contains("bad signature"), "the same text as any bad signature: no new oracle");
        // On every signed route, the legacy ones too.
        assertEquals(401, signed("POST", "/api/v1/heartbeat", "{}", "-", System.currentTimeMillis(), k).status());
        assertEquals(401, signed("POST", "/api/v1/bot/hello", "{}", "-", System.currentTimeMillis(), k).status());
        // A request stamped at or after the start passes (the skew limit is 60 s, so this one is still fresh).
        assertEquals(200, signed("POST", "/api/v1/tasks/sync", body, "plug", start, k).status());
        router.signer().notBefore(0);
        advance(1100); // one sync a second
        assertEquals(200, signed("POST", "/api/v1/tasks/sync", body, "plug", System.currentTimeMillis(), k).status());
    }

    @Test
    void theSignerFloorIsAUnitRule() {
        long[] now = {100_000};
        Signer s = new Signer(() -> now[0]);
        s.notBefore(100_000);
        byte[] key = Signer.deriveKey("k");
        String nonce = "0123456789abcdef0123456789abcdef";
        Signer.Auth older = new Signer.Auth("b", 99_999, nonce, Signer.requestSig(key, "POST", "/p", 99_999, nonce, new byte[0]));
        Signer.Auth same = new Signer.Auth("b", 100_000, nonce, Signer.requestSig(key, "POST", "/p", 100_000, nonce, new byte[0]));
        assertEquals(false, s.precheck(older));
        assertEquals(-1, s.verify(older, "POST", "/p", new byte[0], key));
        assertEquals(true, s.precheck(same));
        assertEquals(0, s.verify(same, "POST", "/p", new byte[0], key));
    }
}
