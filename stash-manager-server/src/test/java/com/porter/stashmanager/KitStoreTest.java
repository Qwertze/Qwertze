package com.porter.stashmanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** data/kits.json: round trip, the backup, the disabled state, the hash and the limits (KIT-CONTRACT.md). */
class KitStoreTest {
    @TempDir
    Path dir;

    KitStore open() {
        KitStore s = new KitStore(dir, () -> 1000L);
        s.load();
        return s;
    }

    @Test
    void hashVectors() {
        assertEquals("cf9dcdb21f6fc7551bde30826e49358bd74d06ef", KitStore.hash(null, KitStore.Scoring.DEFAULT, Map.of()));
        // Rules are sorted by key whatever the map's order.
        assertEquals("a3de573e0bbefcda0f237cc52e3133047723f996",
            KitStore.hash("120,64,-33", new KitStore.Scoring(5, 0, 10, 1, 4, 7), Map.of("pvp kit", "118,64,-33", "kit 2", "1,2,3")));
    }

    @Test
    void missingFileIsEmpty() {
        KitStore s = open();
        assertFalse(s.disabled());
        KitStore.Settings g = s.get("srv", "st");
        assertEquals(0, g.rev());
        assertNull(g.kitsChest());
        assertTrue(g.rules().isEmpty());
        assertEquals("cf9dcdb21f6fc7551bde30826e49358bd74d06ef", g.hash());
        assertFalse(Files.exists(dir.resolve("kits.json")), "reading writes nothing");
    }

    @Test
    void roundTripRevAndHash() throws Exception {
        KitStore s = open();
        KitStore.Settings a = s.setChest("srv", "st", "120,64,-33");
        assertEquals(1, a.rev());
        KitStore.Settings b = s.setRule("srv", "st", "pvp kit", "118,64,-33");
        assertEquals(2, b.rev());
        assertEquals(KitStore.hash("120,64,-33", KitStore.Scoring.DEFAULT, Map.of("pvp kit", "118,64,-33")), b.hash());
        // A change that changes nothing keeps the rev and writes nothing.
        assertEquals(2, s.setRule("srv", "st", "pvp kit", "118,64,-33").rev());
        assertEquals(2, s.deleteRule("srv", "st", "nope kit").settings().rev());
        assertEquals(3, s.setRule("srv", "st", "pvp kit", "1,2,3").rev());
        assertEquals(4, s.deleteRule("srv", "st", "pvp kit").settings().rev());
        assertEquals(5, s.setChest("srv", "st", null).rev());
        assertEquals(5, s.setChest("srv", "st", null).rev());
        s.setRule("srv", "st", "kit a", "9,9,9");
        s.setChest("srv", "other", "5,5,5");

        KitStore again = open();
        KitStore.Settings g = again.get("srv", "st");
        assertEquals(6, g.rev());
        assertNull(g.kitsChest());
        assertEquals(Map.of("kit a", "9,9,9"), g.rules());
        assertEquals("5,5,5", again.get("srv", "other").kitsChest());
        assertEquals(0, again.get("srv", "third").rev());
        assertEquals(b.toJson().get("hash").getAsString(), b.hash());
        assertTrue(Files.exists(dir.resolve("kits.json.bak")), "the previous file is kept");
    }

    @Test
    void corruptFileFallsBackToBackupAndKeepsIt() throws Exception {
        KitStore s = open();
        s.setChest("srv", "st", "1,1,1");
        s.setChest("srv", "st", "2,2,2"); // kits.json = 2,2,2; the backup = 1,1,1
        Files.writeString(dir.resolve("kits.json"), "{ not json", StandardCharsets.UTF_8);

        KitStore r = open();
        assertFalse(r.disabled());
        assertEquals("1,1,1", r.get("srv", "st").kitsChest(), "from the backup");
        // The first write must not copy the damaged file over the good backup.
        r.setChest("srv", "st", "3,3,3");
        assertTrue(Files.readString(dir.resolve("kits.json.bak")).contains("1,1,1"));
        assertEquals("3,3,3", open().get("srv", "st").kitsChest());
    }

    @Test
    void aDamagedLiveFileNeverReplacesAGoodBackup() throws Exception {
        KitStore s = open();
        s.setChest("srv", "st", "1,1,1");
        s.setChest("srv", "st", "2,2,2"); // live = 2,2,2; backup = 1,1,1
        Files.writeString(dir.resolve("kits.json"), "{ damaged while running", StandardCharsets.UTF_8);
        s.setChest("srv", "st", "3,3,3"); // would have copied the damaged file over the backup
        assertTrue(Files.readString(dir.resolve("kits.json.bak")).contains("1,1,1"), "the backup is still the last good one");
        assertEquals("3,3,3", open().get("srv", "st").kitsChest());
    }

    @Test
    void backupAloneRestoresWhenTheLiveFileIsMissing() throws Exception {
        KitStore s = open();
        s.setChest("srv", "st", "1,1,1");
        s.setRule("srv", "st", "pvp kit", "5,5,5"); // backup = after the first change
        Files.delete(dir.resolve("kits.json"));
        KitStore r = open();
        assertFalse(r.disabled());
        assertEquals("1,1,1", r.get("srv", "st").kitsChest());
        assertEquals(1, r.get("srv", "st").rev());
        // The next write recreates the live file and keeps the good backup.
        KitStore.Deleted d = r.deleteRule("srv", "st", "pvp kit");
        assertNull(d.previous(), "the restored state had no such rule");
        r.setRule("srv", "st", "pvp kit", "6,6,6");
        assertTrue(Files.exists(dir.resolve("kits.json")));
        KitStore.Deleted removed = open().deleteRule("srv", "st", "pvp kit");
        assertEquals("6,6,6", removed.previous(), "the removed value comes back atomically");
        // A missing live file with an unreadable backup is disabled, not empty.
        Files.delete(dir.resolve("kits.json"));
        Files.writeString(dir.resolve("kits.json.bak"), "nope", StandardCharsets.UTF_8);
        assertTrue(open().disabled());
        assertEquals("nope", Files.readString(dir.resolve("kits.json.bak")));
    }

    @Test
    void scoringIsStoredAndOldFilesGetTheDefaults() throws Exception {
        KitStore s = open();
        assertEquals(KitStore.Scoring.DEFAULT, s.get("srv", "st").scoring());
        KitStore.Scoring custom = new KitStore.Scoring(6, 1, 2, 3, 4, 9);
        assertEquals(1, s.setScoring("srv", "st", custom).rev());
        assertEquals(1, s.setScoring("srv", "st", custom).rev(), "the same values change nothing");
        assertEquals(10, custom.max());
        assertEquals(custom, open().get("srv", "st").scoring());
        assertEquals(KitStore.hash(null, custom, Map.of()), open().get("srv", "st").hash());
        // Back to the defaults: written without a scoring field.
        s.setScoring("srv", "st", KitStore.Scoring.DEFAULT);
        assertFalse(Files.readString(dir.resolve("kits.json")).contains("scoring"));
        assertThrows(KitStore.LimitException.class, () -> s.setScoring("srv", "st", new KitStore.Scoring(4, 2, 3, 1, 4, 0)));
        assertThrows(KitStore.LimitException.class, () -> s.setScoring("srv", "st", new KitStore.Scoring(4, 2, 3, 1, 4, 1729)));
        assertEquals(1728, s.setScoring("srv", "st", new KitStore.Scoring(4, 2, 3, 1, 4, 1728)).scoring().opMin());
        s.setScoring("srv", "st", KitStore.Scoring.DEFAULT);
        // A scoring object saved before opMin existed has none: 3.
        Files.writeString(dir.resolve("kits.json"), "{\"version\":1,\"stashes\":[{\"server\":\"s\",\"stash\":\"a\",\"rev\":3,\"kitsChest\":null,\"rules\":{},"
            + "\"scoring\":{\"threshold\":6,\"name\":1,\"mix\":2,\"full\":3,\"op\":4}}]}", StandardCharsets.UTF_8);
        assertEquals(new KitStore.Scoring(6, 1, 2, 3, 4, 3), open().get("s", "a").scoring());
        assertThrows(KitStore.LimitException.class, () -> s.setScoring("srv", "st", new KitStore.Scoring(0, 2, 3, 1, 4)));
        assertThrows(KitStore.LimitException.class, () -> s.setScoring("srv", "st", new KitStore.Scoring(4, 2, 3, 1, 11)));
        // A kits.json from before scoring has no field: the defaults, and it reads fine.
        Files.writeString(dir.resolve("kits.json"), "{\"version\":1,\"stashes\":[{\"server\":\"s\",\"stash\":\"a\",\"rev\":3,"
            + "\"kitsChest\":\"1,2,3\",\"rules\":{\"pvp kit\":\"4,5,6\"},\"updatedAt\":5}]}", StandardCharsets.UTF_8);
        KitStore old = open();
        assertFalse(old.disabled());
        assertEquals(KitStore.Scoring.DEFAULT, old.get("s", "a").scoring());
        assertEquals(3, old.get("s", "a").rev());
        // A bad scoring in the file counts as unreadable.
        Files.writeString(dir.resolve("kits.json"), "{\"version\":1,\"stashes\":[{\"server\":\"s\",\"stash\":\"a\",\"rev\":3,"
            + "\"kitsChest\":null,\"rules\":{},\"scoring\":{\"threshold\":0,\"name\":2,\"mix\":3,\"full\":1,\"op\":4}}]}", StandardCharsets.UTF_8);
        Files.deleteIfExists(dir.resolve("kits.json.bak"));
        KitStore badScoring = open();
        assertFalse(badScoring.disabled());
        assertThrows(KitStore.DisabledException.class, () -> badScoring.get("s", "a"));
    }

    @Test
    void oneBadStashEntryDoesNotSwitchOffTheOthers() throws Exception {
        String bak = "{\"version\":1,\"stashes\":[]}";
        Files.writeString(dir.resolve("kits.json.bak"), bak, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("kits.json"), "{\"version\":1,\"stashes\":["
            + "{\"server\":\"s\",\"stash\":\"a\",\"rev\":2,\"kitsChest\":\"1,1,1\",\"rules\":{}},"
            + "{\"server\":\"s\",\"stash\":\"b\",\"rev\":1,\"kitsChest\":\"bad id\",\"rules\":{}},"
            + "{\"stash\":\"no server\"}]}", StandardCharsets.UTF_8);
        KitStore s = open();
        assertFalse(s.disabled());
        assertEquals("1,1,1", s.get("s", "a").kitsChest());
        assertTrue(s.knows("s", "b"));
        assertThrows(KitStore.DisabledException.class, () -> s.get("s", "b"));
        assertThrows(KitStore.DisabledException.class, () -> s.setChest("s", "b", "2,2,2"));
        assertThrows(KitStore.DisabledException.class, () -> s.deleteRule("s", "b", "kit"));
        assertThrows(KitStore.DisabledException.class, () -> s.setScoring("s", "b", KitStore.Scoring.DEFAULT));
        // Other stashes still change, and the unreadable entries are written back exactly as they were.
        assertEquals(3, s.setChest("s", "a", "9,9,9").rev());
        s.setChest("s", "c", "3,3,3");
        String file = Files.readString(dir.resolve("kits.json"));
        assertTrue(file.contains("bad id"));
        assertTrue(file.contains("no server"));
        assertEquals(bak, Files.readString(dir.resolve("kits.json.bak")), "a file with unreadable entries doesn't replace the clean backup");
        KitStore again = open();
        assertFalse(again.disabled());
        assertEquals("9,9,9", again.get("s", "a").kitsChest());
        assertEquals("3,3,3", again.get("s", "c").kitsChest());
        assertThrows(KitStore.DisabledException.class, () -> again.get("s", "b"));
    }

    @Test
    void neverWritesAnUnpairedSurrogate() throws Exception {
        KitStore s = open();
        assertThrows(KitStore.LimitException.class, () -> s.setChest("srv", "a" + (char) 0xD800, "1,1,1"));
        assertThrows(KitStore.LimitException.class, () -> s.setScoring((char) 0xDC00 + "srv", "a", KitStore.Scoring.DEFAULT));
        assertFalse(Files.exists(dir.resolve("kits.json")));
        // A file that already has one loads as a damaged entry, not as a stash.
        Files.writeString(dir.resolve("kits.json"), "{\"version\":1,\"stashes\":[{\"server\":\"s\",\"stash\":\"a\\ud800\",\"rev\":1,"
            + "\"kitsChest\":null,\"rules\":{}}]}", StandardCharsets.UTF_8);
        KitStore l = open();
        assertFalse(l.disabled());
        assertEquals(0, l.get("s", "ok").rev());
    }

    @Test
    void rulesNeedNoKitWordInTheKey() throws Exception {
        KitStore s = open();
        assertEquals(1, s.setRule("srv", "st", "mixed concrete", "1,1,1").rev());
        assertEquals("1,1,1", open().get("srv", "st").rules().get("mixed concrete"));
    }

    @Test
    void bothCorruptDisablesAndNeverOverwrites() throws Exception {
        Files.writeString(dir.resolve("kits.json"), "garbage", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("kits.json.bak"), "{\"version\":2,\"stashes\":[]}", StandardCharsets.UTF_8);
        KitStore s = open();
        assertTrue(s.disabled());
        assertThrows(KitStore.DisabledException.class, () -> s.get("a", "b"));
        assertThrows(KitStore.DisabledException.class, () -> s.setChest("a", "b", "1,1,1"));
        assertThrows(KitStore.DisabledException.class, () -> s.setRule("a", "b", "kit", "1,1,1"));
        assertThrows(KitStore.DisabledException.class, () -> s.deleteRule("a", "b", "kit"));
        assertEquals("garbage", Files.readString(dir.resolve("kits.json")));
        assertEquals("{\"version\":2,\"stashes\":[]}", Files.readString(dir.resolve("kits.json.bak")));
    }

    @Test
    void invalidContentCountsAsUnreadable() throws Exception {
        String[] bad = {
            "{\"version\":1,\"stashes\":[{\"server\":\"s\",\"stash\":\"a\",\"rev\":1,\"kitsChest\":\"bad id\",\"rules\":{}}]}",
            "{\"version\":1,\"stashes\":[{\"server\":\"s\",\"stash\":\"a\",\"rev\":1,\"kitsChest\":null,\"rules\":{\"Not Normal Kit\":\"1,1,1\"}}]}",
            "{\"version\":1,\"stashes\":[{\"server\":\"s\",\"stash\":\"a\",\"rev\":1,\"kitsChest\":null,\"rules\":{\"kit\":\"1,1\"}}]}",
            "{\"version\":1,\"stashes\":[{\"server\":\"s\",\"stash\":\"a\",\"rev\":1,\"kitsChest\":null,\"rules\":{}},"
                + "{\"server\":\"s\",\"stash\":\"a\",\"rev\":1,\"kitsChest\":null,\"rules\":{}}]}",
            "{\"version\":1}",
        };
        for (int i = 0; i < bad.length; i++) {
            String b = bad[i];
            Files.writeString(dir.resolve("kits.json"), b, StandardCharsets.UTF_8);
            Files.deleteIfExists(dir.resolve("kits.json.bak"));
            KitStore st = open();
            if (i < 3) {
                // One bad stash entry only switches that stash off.
                assertFalse(st.disabled(), b);
                assertThrows(KitStore.DisabledException.class, () -> st.get("s", "a"), b);
            } else {
                assertTrue(st.disabled(), b);
            }
        }
    }

    @Test
    void limits() throws Exception {
        KitStore s = open();
        for (int i = 0; i < KitStore.MAX_RULES; i++) s.setRule("srv", "st", "kit " + i, "1,1," + i);
        LimitMessage.check(assertThrows(KitStore.LimitException.class, () -> s.setRule("srv", "st", "kit extra", "1,1,1")), "at most 200 kit rules");
        // Changing an existing rule at the cap is fine.
        s.setRule("srv", "st", "kit 0", "2,2,2");
        for (int i = 1; i < KitStore.MAX_STASHES; i++) s.setChest("srv", "stash" + i, "1,1,1");
        assertThrows(KitStore.LimitException.class, () -> s.setChest("srv", "one too many", "1,1,1"));
        // Clearing something that doesn't exist neither creates an entry nor fails.
        assertNull(s.setChest("srv", "one too many", null).kitsChest());
        // The stash that already has an entry can still change.
        s.setChest("srv", "stash1", "3,3,3");
    }

    private static final class LimitMessage {
        static void check(KitStore.LimitException e, String message) {
            assertEquals(message, e.getMessage());
        }
    }
}
