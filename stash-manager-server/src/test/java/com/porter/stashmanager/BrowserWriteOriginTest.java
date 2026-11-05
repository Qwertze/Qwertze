package com.porter.stashmanager;

import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The browser-write Origin check, including https origins through a reverse proxy named in allowedHosts. */
class BrowserWriteOriginTest {
    @TempDir
    Path dir;
    AccessControl access;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(dir.resolve("config.properties"), "port=8765\nallowedHosts=stash.qwerttze.com, 10.0.0.19\n");
        access = new AccessControl(Config.load(dir));
    }

    private static Headers write(String origin, String host) {
        Headers h = new Headers();
        h.add("X-Stash-Manager", "1");
        h.add("Content-Type", "application/json; charset=utf-8");
        h.add("Host", host);
        if (origin != null) h.add("Origin", origin);
        h.add("Sec-Fetch-Site", "same-origin");
        return h;
    }

    private boolean ok(String origin, String host) {
        return access.browserWriteAllowed("POST", write(origin, host));
    }

    @Test
    void httpsOriginForAConfiguredProxyNameIsAllowed() {
        assertTrue(ok("https://stash.qwerttze.com", "stash.qwerttze.com"));
        assertTrue(ok("https://stash.qwerttze.com:443", "stash.qwerttze.com"));
        assertTrue(ok("https://STASH.qwerttze.com", "stash.qwerttze.com:80"));
    }

    @Test
    void httpsOriginForAnIpOrUnlistedNameIsRefused() {
        assertFalse(ok("https://10.0.0.19", "10.0.0.19"), "IP literals stay http-only, even when listed");
        assertFalse(ok("https://10.0.0.19:8765", "10.0.0.19:8765"));
        assertFalse(ok("https://localhost", "localhost"));
        assertFalse(ok("https://[::1]", "[::1]"));
        assertFalse(ok("https://other.qwerttze.com", "other.qwerttze.com"), "not in allowedHosts");
        assertFalse(ok("https://stash.qwerttze.com:8443", "stash.qwerttze.com"), "only the default https port");
        assertFalse(ok("https://stash.qwerttze.com/path", "stash.qwerttze.com"));
    }

    @Test
    void httpOriginsAreUnchanged() {
        assertTrue(ok("http://10.0.0.19:8765", "10.0.0.19:8765"));
        assertTrue(ok("http://localhost:8765", "localhost:8765"));
        assertTrue(ok("http://stash.qwerttze.com", "stash.qwerttze.com"));
        assertFalse(ok("http://10.0.0.19:8765", "10.0.0.19:9999"), "the port must still match for http");
        assertFalse(ok("http://10.0.0.20:8765", "10.0.0.19:8765"));
        assertTrue(ok(null, "10.0.0.19:8765"), "no Origin header: the other checks decide");
    }

    @Test
    void crossSiteOriginsAreStillRefused() {
        assertFalse(ok("https://evil.example", "stash.qwerttze.com"));
        assertFalse(ok("https://stash.qwerttze.com.evil.example", "stash.qwerttze.com"));
        assertFalse(ok("https://stash.qwerttze.com", "10.0.0.19:8765"), "the origin's name must be the Host's name");
        assertFalse(ok("null", "stash.qwerttze.com"));
        Headers h = write("https://stash.qwerttze.com", "stash.qwerttze.com");
        h.set("Sec-Fetch-Site", "cross-site");
        assertFalse(access.browserWriteAllowed("POST", h));
    }

    @Test
    void theOtherChecksStillApplyToHttpsOrigins() {
        String origin = "https://stash.qwerttze.com";
        String host = "stash.qwerttze.com";
        assertFalse(access.browserWriteAllowed("GET", write(origin, host)));
        Headers noHeader = write(origin, host);
        noHeader.remove("X-Stash-Manager");
        assertFalse(access.browserWriteAllowed("POST", noHeader));
        Headers form = write(origin, host);
        form.set("Content-Type", "application/x-www-form-urlencoded");
        assertFalse(access.browserWriteAllowed("POST", form));
        Headers two = write(origin, host);
        two.add("Origin", origin);
        assertFalse(access.browserWriteAllowed("POST", two));
    }
}
