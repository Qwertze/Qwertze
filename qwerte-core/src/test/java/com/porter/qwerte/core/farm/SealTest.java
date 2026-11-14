package com.porter.qwerte.core.farm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SealTest {
    private static final JsonObject S = Vectors.load().getAsJsonObject("seal");
    private static final byte[] KEY = Vectors.hex(S.get("keyHex").getAsString());
    private static final byte[] OTHER_KEY = Vectors.hex("ff".repeat(32));

    private static byte[] plain() {
        return FarmRecording.sha256("a program".getBytes());
    }

    @Test
    void keyIdIsTheFirstFourBytesOfTheKeyHash() {
        assertEquals(S.get("keyIdHex").getAsString(), Vectors.hex(Seal.keyId(KEY)));
        assertEquals(Vectors.hex(Arrays.copyOf(FarmRecording.sha256(KEY), 4)), Vectors.hex(Seal.keyId(KEY)));
    }

    @Test
    void knownAnswers() {
        for (JsonElement e : S.getAsJsonArray("cases")) {
            JsonObject c = e.getAsJsonObject();
            int kind = c.get("kind").getAsInt();
            String id = c.get("storedId").getAsString();
            byte[] plain = Vectors.hex(c.get("plainHex").getAsString());
            byte[] nonce = Vectors.hex(c.get("nonceHex").getAsString());
            byte[] hash = kind == 1 ? Vectors.hex(c.get("programHash").getAsString()) : null;
            assertEquals(c.get("aadHex").getAsString(), Vectors.hex(Seal.aad(kind, 1, id, hash)), c.get("id").getAsString());
            Seal.Result sealed = Seal.seal0(KEY, kind, 1, id, hash, plain, nonce);
            assertTrue(sealed.ok(), c.get("id").getAsString());
            assertEquals(c.get("fileHex").getAsString(), Vectors.hex(sealed.data()), c.get("id").getAsString());
            Seal.Result open = kind == 1 ? Seal.openProgram(KEY, id, hash, Vectors.hex(c.get("fileHex").getAsString()))
                    : Seal.openLocations(KEY, Vectors.hex(c.get("fileHex").getAsString()));
            assertTrue(open.ok());
            assertArrayEquals(plain, open.data());
            if (kind == 1) {
                assertEquals(c.get("programHash").getAsString(), FarmRecording.sha256Hex(plain));
            }
        }
    }

    @Test
    void roundTripsAndFreshNonce() {
        byte[] p = plain();
        byte[] hash = FarmRecording.sha256(p);
        Seal.Result a = Seal.sealProgram(KEY, "f-1a2b3c4d", p);
        Seal.Result b = Seal.sealProgram(KEY, "f-1a2b3c4d", p);
        assertTrue(a.ok() && b.ok());
        assertFalse(Arrays.equals(a.data(), b.data()), "two seals of the same plaintext differ (fresh nonce)");
        assertFalse(Arrays.equals(Arrays.copyOfRange(a.data(), 9, 21), Arrays.copyOfRange(b.data(), 9, 21)), "nonce differs");
        assertArrayEquals(p, Seal.openProgram(KEY, "f-1a2b3c4d", hash, a.data()).data());
        assertArrayEquals(p, Seal.openProgram(KEY, "f-1a2b3c4d", hash, b.data()).data());
        Seal.Result l = Seal.sealLocations(KEY, p);
        assertArrayEquals(p, Seal.openLocations(KEY, l.data()).data());
        Seal.Result d = Seal.sealProgram(KEY, "draft", p);
        assertArrayEquals(p, Seal.openProgram(KEY, "draft", hash, d.data()).data());
        assertArrayEquals(Seal.keyId(KEY), Seal.fileKeyId(a.data()));
        assertNull(Seal.fileKeyId(new byte[10]));
    }

    private static byte[] flip(byte[] f, int at) {
        byte[] g = f.clone();
        g[at] ^= 0x01;
        return g;
    }

    @Test
    void everyTamperedByteFailsAndTheRightCodeIsReported() {
        byte[] p = plain();
        byte[] hash = FarmRecording.sha256(p);
        byte[] kind1 = Seal.sealProgram(KEY, "f-1a2b3c4d", p).data();
        byte[] kind2 = Seal.sealLocations(KEY, p).data();
        int tag = kind1.length - 1;
        for (JsonElement e : S.getAsJsonArray("rejects")) {
            JsonObject r = e.getAsJsonObject();
            String id = r.get("id").getAsString();
            String expect = r.get("expect").getAsString();
            if (r.has("mutate")) {
                byte[] f = switch (r.get("mutate").getAsString()) {
                    case "ciphertext" -> flip(kind1, 21);
                    case "tag" -> flip(kind1, tag);
                    case "nonce" -> flip(kind1, 10);
                    case "keyId" -> flip(kind1, 6);
                    case "kind" -> flip(kind1, 4);
                    case "magic" -> flip(kind1, 0);
                    case "truncate" -> Arrays.copyOf(kind1, 21);
                    case "cut_to_20" -> Arrays.copyOf(kind1, 20);
                    case "empty" -> new byte[0];
                    default -> throw new IllegalStateException(id);
                };
                assertEquals(expect, Seal.openProgram(KEY, "f-1a2b3c4d", hash, f).code(), id);
            } else {
                JsonObject o = r.getAsJsonObject("open");
                if (o.has("format")) {
                    assertEquals(expect, Seal.open0(KEY, 1, o.get("format").getAsInt(), "f-1a2b3c4d", hash, kind1).code(), id);
                    assertEquals(expect, Seal.open0(KEY, 2, o.get("format").getAsInt(), "locations", null, kind2).code(), id);
                } else if (o.has("id")) {
                    assertEquals(expect, Seal.openProgram(KEY, o.get("id").getAsString(), hash, kind1).code(), id);
                } else if (o.has("programHash")) {
                    assertEquals(expect, Seal.openProgram(KEY, "f-1a2b3c4d", flip(hash, 0), kind1).code(), id);
                } else if (o.has("key")) {
                    assertEquals(expect, Seal.openProgram(OTHER_KEY, "f-1a2b3c4d", hash, kind1).code(), id);
                    assertEquals(expect, Seal.openLocations(OTHER_KEY, kind2).code(), id);
                } else if (o.has("keyLength")) {
                    byte[] k = new byte[o.get("keyLength").getAsInt()];
                    assertEquals(expect, Seal.openProgram(k, "f-1a2b3c4d", hash, kind1).code(), id);
                    assertEquals(expect, Seal.sealProgram(k, "f-1a2b3c4d", p).code(), id);
                }
            }
        }
    }

    @Test
    void everySingleByteFlipOfAFileIsRefused() {
        byte[] p = plain();
        byte[] hash = FarmRecording.sha256(p);
        byte[] f = Seal.sealProgram(KEY, "f-1a2b3c4d", p).data();
        for (int i = 0; i < f.length; i++) {
            Seal.Result r = Seal.openProgram(KEY, "f-1a2b3c4d", hash, flip(f, i));
            assertFalse(r.ok(), "byte " + i);
            assertTrue(r.code().equals("seal_failed") || r.code().equals("key_mismatch"), "byte " + i);
        }
    }

    @Test
    void swapAndRollbackAreRefusedAndKindsDoNotCross() {
        byte[] oldP = FarmRecording.sha256("old".getBytes());
        byte[] newP = FarmRecording.sha256("new".getBytes());
        byte[] fileOld = Seal.sealProgram(KEY, "f-1a2b3c4d", oldP).data();
        byte[] fileNew = Seal.sealProgram(KEY, "f-1a2b3c4d", newP).data();
        // moved to another farm id
        assertEquals("seal_failed", Seal.openProgram(KEY, "f-0a0a0a0a", FarmRecording.sha256(oldP), fileOld).code());
        assertEquals("seal_failed", Seal.openProgram(KEY, "draft", FarmRecording.sha256(oldP), fileOld).code());
        // an older file for the same farm while the location store expects the new hash
        assertEquals("seal_failed", Seal.openProgram(KEY, "f-1a2b3c4d", FarmRecording.sha256(newP), fileOld).code());
        assertTrue(Seal.openProgram(KEY, "f-1a2b3c4d", FarmRecording.sha256(newP), fileNew).ok());
        // kind 1 file opened as the location store, and the reverse
        assertEquals("seal_failed", Seal.openLocations(KEY, fileNew).code());
        byte[] loc = Seal.sealLocations(KEY, newP).data();
        assertEquals("seal_failed", Seal.openProgram(KEY, "f-1a2b3c4d", FarmRecording.sha256(newP), loc).code());
        // a missing expected hash never opens a program
        assertEquals("seal_failed", Seal.openProgram(KEY, "f-1a2b3c4d", null, fileNew).code());
        assertEquals("seal_failed", Seal.openProgram(KEY, "f-1a2b3c4d", new byte[31], fileNew).code());
    }

    @Test
    void badInputsNeverThrowAndNeverLeak() {
        assertEquals("key_invalid", Seal.sealProgram(null, "f-1a2b3c4d", plain()).code());
        assertEquals("seal_failed", Seal.sealProgram(KEY, "../evil", plain()).code());
        assertEquals("seal_failed", Seal.sealProgram(KEY, null, plain()).code());
        assertEquals("seal_failed", Seal.sealProgram(KEY, "f-1a2b3c4d", null).code());
        assertEquals("seal_failed", Seal.openLocations(KEY, null).code());
        Seal.Result ok = Seal.sealLocations(KEY, plain());
        assertEquals("Result[ok]", ok.toString());
        assertEquals("Result[seal_failed]", Seal.openLocations(KEY, new byte[3]).toString());
        FarmException ex = new FarmException("seal_failed");
        assertEquals("seal_failed", ex.getMessage());
        assertNotEquals(null, Seal.fileKeyId(ok.data()));
    }

    @Test
    void largePlaintextRoundTrips() {
        byte[] big = new byte[3 * 1024 * 1024];
        new java.util.Random(7).nextBytes(big);
        byte[] hash = FarmRecording.sha256(big);
        Seal.Result r = Seal.sealProgram(KEY, "draft", big);
        assertTrue(r.ok());
        assertArrayEquals(big, Seal.openProgram(KEY, "draft", hash, r.data()).data());
    }
}
