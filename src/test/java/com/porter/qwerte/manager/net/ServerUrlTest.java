package com.porter.qwerte.manager.net;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerUrlTest {
    @Test
    void acceptsHttpAndHttpsWithOptionalPortAndSlash() {
        assertEquals("http://127.0.0.1:8765", ServerUrl.parse("http://127.0.0.1:8765").origin());
        assertEquals("http://127.0.0.1:8765", ServerUrl.parse("  http://127.0.0.1:8765/ ").origin());
        assertEquals("https://stash.example.com", ServerUrl.parse("HTTPS://Stash.Example.com").origin());
        assertEquals("https://stash.example.com", ServerUrl.parse("https://stash.example.com:443/").origin());
        assertEquals("http://example.com", ServerUrl.parse("http://example.com:80").origin());
        assertEquals("http://[::1]:8765", ServerUrl.parse("http://[::1]:8765").origin());
        assertTrue(ServerUrl.parse("https://a.b").secure());
    }

    @Test
    void rejectsEverythingElse() {
        for (String bad : new String[] {
            null, "", "   ", "localhost:8765", "ftp://host", "file:///etc/passwd", "javascript:alert(1)",
            "http://", "http://user:pw@host", "http://host?x=1", "http://host#frag", "http://host/path", "http://host:0",
            "http://host:70000", "http://ho st", "http://host\n.evil", "//host"
        }) {
            assertNull(ServerUrl.parse(bad), String.valueOf(bad));
        }
    }

    @Test
    void sameOriginComparisonIgnoresCaseAndDefaultPorts() {
        ServerUrl url = ServerUrl.parse("http://Example.com:80");
        assertEquals(url.origin(), ServerUrl.originOf(URI.create("http://example.com/api/v1/stashes")));
        assertEquals("http://example.com:81", ServerUrl.originOf(URI.create("http://example.com:81/x")));
    }

    @Test
    void resolveEncodesQueryValuesAndRefusesBadPaths() {
        ServerUrl url = ServerUrl.parse("http://127.0.0.1:8765");
        Map<String, String> q = new LinkedHashMap<>();
        q.put("server", "2b2t.org:25565");
        q.put("stash", "my stash&x=1");
        URI uri = url.resolve("/api/v1/index", q);
        assertEquals("http://127.0.0.1:8765/api/v1/index?server=2b2t.org%3A25565&stash=my%20stash%26x%3D1", uri.toString());
        assertEquals("http://127.0.0.1:8765/api/v1/auth/state", url.resolve("/api/v1/auth/state", null).toString());
        assertThrows(IllegalArgumentException.class, () -> url.resolve("api/v1", null));
        assertThrows(IllegalArgumentException.class, () -> url.resolve("/a?b=1", null));
        assertThrows(IllegalArgumentException.class, () -> url.resolve("/a b", null));
        assertThrows(IllegalArgumentException.class, () -> url.resolve("/a#b", null));
    }

    @Test
    void localHostCoversThisPcAndLocalNetworks() {
        for (String ok : new String[] {"http://localhost:8765", "http://127.0.0.1", "http://192.168.1.20:8765", "http://10.0.0.5", "http://172.16.0.1",
            "http://172.31.255.1", "http://169.254.1.1", "http://[::1]:8765", "http://[fd12:3456::1]", "http://nas", "http://stash.local", "http://box.lan:80"}) {
            assertTrue(ServerUrl.parse(ok).localHost(), ok);
        }
        for (String remote : new String[] {"http://8.8.8.8", "http://172.32.0.1", "http://example.com", "http://stash.example.com:8765", "https://203.0.113.9"}) {
            assertFalse(ServerUrl.parse(remote).localHost(), remote);
        }
    }

    @Test
    void segEncodesSlashesAndSpaces() {
        assertEquals("a%2Fb%20c", ServerUrl.seg("a/b c"));
        assertEquals("abc-DEF_1", ServerUrl.seg("abc-DEF_1"));
        assertNotNull(ServerUrl.seg(null));
    }
}
