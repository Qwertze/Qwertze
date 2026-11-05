package com.porter.stashmanager;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The HMAC signing of bot requests and responses, with the shared test vector from PROTOCOL.md. */
class SignerTest {
    static final String NONCE = "00112233445566778899aabbccddeeff";
    static final byte[] BODY = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

    @Test
    void testVector() {
        byte[] k = Signer.deriveKey("test-key");
        assertEquals("d4e20f4ea5ec22c2b66d810c6c95cddc10ca254d4ead1c5904bfdf3c69410706", HexFormat.of().formatHex(k));
        assertEquals("015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862", Signer.sha256Hex(BODY));
        String sig = Signer.requestSig(k, "POST", "/api/v1/heartbeat", 1700000000000L, NONCE, BODY);
        System.out.println("test vector sig = " + sig);
        assertEquals("7d2f41009d9f0c6c61216ee00811bc643c646652c3d96b36fb89df09bc9356f1", sig);
        assertEquals("6ca0727bb6e3f5d8a8a11285906bb2078e6e899efe39e86cfc0926b3bb2a9dce",
            Signer.responseSig(k, NONCE, 200, 1700000000123L, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Signer.sha256Hex(new byte[0]));
    }

    private static Signer.Auth auth(byte[] k, long ts, String nonce, byte[] body) {
        return Signer.Auth.parse("v1 - " + ts + " " + nonce + " " + Signer.requestSig(k, "POST", "/api/v1/heartbeat", ts, nonce, body));
    }

    @Test
    void verifyChecksTimeSignatureAndReplays() {
        AtomicLong now = new AtomicLong(1700000000000L);
        Signer s = new Signer(now::get);
        byte[] k = Signer.deriveKey("test-key");
        byte[] wrong = Signer.deriveKey("other-key");

        assertEquals(0, s.verify(auth(k, now.get(), NONCE, BODY), "POST", "/api/v1/heartbeat", BODY, k));
        assertEquals(-1, s.verify(auth(k, now.get(), NONCE, BODY), "POST", "/api/v1/heartbeat", BODY, k), "a replayed nonce");
        String n2 = "ffeeddccbbaa99887766554433221100";
        assertEquals(-1, s.verify(auth(k, now.get() - 60_001, n2, BODY), "POST", "/api/v1/heartbeat", BODY, k), "too old");
        assertEquals(-1, s.verify(auth(k, now.get() + 60_001, n2, BODY), "POST", "/api/v1/heartbeat", BODY, k), "too far ahead");
        assertEquals(-1, s.verify(auth(wrong, now.get(), n2, BODY), "POST", "/api/v1/heartbeat", BODY, k), "a wrong key");
        assertEquals(-1, s.verify(auth(k, now.get(), n2, BODY), "POST", "/api/v1/heartbeat", "{\"a\":2}".getBytes(StandardCharsets.UTF_8), k), "a changed body");
        assertEquals(-1, s.verify(auth(k, now.get(), n2, BODY), "POST", "/api/v1/index", BODY, k), "another path");
        assertEquals(1, s.verify(auth(wrong, now.get(), n2, BODY), "POST", "/api/v1/heartbeat", BODY, k, wrong), "the previous key");
        now.addAndGet(Signer.NONCE_TTL_MS + 1);
        assertEquals(0, s.verify(auth(k, now.get(), NONCE, BODY), "POST", "/api/v1/heartbeat", BODY, k), "a nonce is forgotten after 5 minutes");
    }

    @Test
    void headerParsing() {
        assertNotNull(Signer.Auth.parse("v1 bot-1 1700000000000 " + NONCE + " " + "a".repeat(64)));
        assertNull(Signer.Auth.parse("v2 - 1 " + NONCE + " " + "a".repeat(64)));
        assertNull(Signer.Auth.parse("v1 - 1 short " + "a".repeat(64)));
        assertNull(Signer.Auth.parse("v1 - abc " + NONCE + " " + "a".repeat(64)));
        assertNull(Signer.Auth.parse("v1 bad/id 1 " + NONCE + " " + "a".repeat(64)));
        assertNull(Signer.Auth.parse(null));
    }
}
