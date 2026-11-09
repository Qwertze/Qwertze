package com.porter.qwerte.stash;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared kit-items table: it is the contract's table (fingerprint) and it loads. */
class KitItemsTest {
    @Test
    void theTableInTheJarHasTheContractsFingerprint() throws Exception {
        try (InputStream in = KitItemsTest.class.getResourceAsStream("/kit-items.json")) {
            assertNotNull(in, "src/main/resources/kit-items.json");
            KitItems items = KitItems.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertEquals(KitItems.FINGERPRINT_V1, items.fingerprint());
            assertEquals("ed47369c84e5a477e55669f4a9be7f47217a50499d7955d2da7c1959d07e1bf7", items.fingerprint());
        }
    }

    @Test
    void theLoadedTableIsTheOneInTheJar() {
        KitItems t = KitItems.table();
        assertEquals(KitItems.FINGERPRINT_V1, t.fingerprint());
        assertTrue(t.op().contains("minecraft:totem_of_undying"));
        assertFalse(t.groups().isEmpty());
        assertTrue(t.groups().stream().anyMatch(g -> g.id().equals("gear") && g.min() == 3));
    }

    @Test
    void theFingerprintFollowsTheContractsFormula() {
        KitItems a = new KitItems(Set.of("b", "a"), List.of(new KitItems.Group("z", 2, Set.of("y", "x")), new KitItems.Group("g", 1, Set.of("m"))));
        KitItems sameOtherOrder = new KitItems(Set.of("a", "b"), List.of(new KitItems.Group("g", 1, Set.of("m")), new KitItems.Group("z", 2, Set.of("x", "y"))));
        assertEquals(a.fingerprint(), sameOtherOrder.fingerprint(), "sorted ids, groups sorted by id");
        assertEquals(sha256("op:a,b\ng:1:m\nz:2:x,y\n"), a.fingerprint());
        KitItems other = new KitItems(Set.of("a", "b"), List.of(new KitItems.Group("g", 2, Set.of("m"))));
        assertFalse(a.fingerprint().equals(other.fingerprint()));
    }

    private static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aBrokenTableIsRefused() {
        assertThrows(java.io.IOException.class, () -> KitItems.parse(new java.io.StringReader("{}")));
        assertThrows(java.io.IOException.class, () -> KitItems.parse(new java.io.StringReader("[1]")));
    }

    @Test
    void theJarCopyIsByteForByteTheReleaseFileWhenThatFileIsAround() throws Exception {
        Path release = Path.of(System.getProperty("user.home"), "Desktop", "qwerte-release", "kit-items.json");
        if (!Files.isRegularFile(release)) return; // only on the developer machine
        try (InputStream in = KitItemsTest.class.getResourceAsStream("/kit-items.json")) {
            assertTrue(java.util.Arrays.equals(Files.readAllBytes(release), in.readAllBytes()));
        }
    }

    @Test
    void aGroupNeedsAMinOfAtLeastOne() throws Exception {
        assertThrows(java.io.IOException.class, () -> KitItems.parse(new java.io.StringReader("{\"op\":[],\"groups\":[{\"id\":\"g\",\"min\":0,\"items\":[\"a\"]}]}")));
        assertEquals(1, KitItems.parse(new java.io.StringReader("{\"op\":[],\"groups\":[{\"id\":\"g\",\"min\":1,\"items\":[\"a\"]}]}")).groups().size());
    }
}
