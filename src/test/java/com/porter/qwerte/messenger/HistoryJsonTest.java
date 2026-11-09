package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryJsonTest {
    private static ServerStores wrap(ConversationStore s) {
        return ServerStores.single("local", s);
    }

    private static String toJson(ConversationStore s, int max) {
        return HistoryJson.toJson(wrap(s), max);
    }

    private static int fromJson(String json, ConversationStore into) {
        return HistoryJson.fromJson(json, wrap(into), "local");
    }

    @Test
    void serversAreSavedSeparately() {
        ServerStores a = new ServerStores(50);
        a.store("2b2t.org").addIncoming("Steve", "on 2b", 1, false);
        a.store("play.example.net").addIncoming("Steve", "elsewhere", 2, false);
        a.store("play.example.net").addOutgoing("Alex", "yo", 3, Message.State.SENT);
        String json = HistoryJson.toJson(a, HistoryJson.MAX_BYTES);
        assertTrue(json.contains("\"version\":2"));
        ServerStores b = new ServerStores(50);
        assertEquals(3, HistoryJson.fromJson(json, b, "ignored"));
        assertEquals("on 2b", b.store("2b2t.org").get("Steve").messages().get(0).text());
        assertEquals("elsewhere", b.store("play.example.net").get("Steve").messages().get(0).text());
        assertEquals(1, b.store("play.example.net").get("Alex").messages().size());
        assertNull(b.store("local").get("Steve"));
    }

    @Test
    void anOldFileGoesToTheServerGivenAndIsWrittenAsVersion2() {
        String v1 = "{\"version\":1,\"conversations\":[{\"name\":\"Alice\",\"messages\":[{\"d\":\"in\",\"t\":\"old\",\"at\":5}]}]}";
        ServerStores s = new ServerStores(50);
        assertEquals(1, HistoryJson.fromJson(v1, s, "2B2T.org"));
        assertEquals("old", s.store("2b2t.org").get("Alice").messages().get(0).text());
        assertTrue(HistoryJson.toJson(s, HistoryJson.MAX_BYTES).contains("\"version\":2"));
    }

    @Test
    void badServerNamesAndFutureVersionsAreSkipped() {
        String json = "{\"version\":2,\"servers\":[{\"server\":\"bad name!\",\"conversations\":[{\"name\":\"Alice\",\"messages\":[{\"d\":\"in\",\"t\":\"x\",\"at\":1}]}]},"
            + "{\"server\":\"ok.example\",\"conversations\":[{\"name\":\"Bob_1\",\"messages\":[{\"d\":\"in\",\"t\":\"y\",\"at\":1}]}]}, 5, {\"server\":3}]}";
        ServerStores s = new ServerStores(50);
        assertEquals(1, HistoryJson.fromJson(json, s, "local"));
        assertNull(s.store("local").get("Alice"));
        assertNotNull(s.store("ok.example").get("Bob_1"));
        assertEquals(-1, HistoryJson.fromJson("{\"version\":3,\"servers\":[]}", new ServerStores(50), "local"));
    }

    @Test
    void tooManyMessagesAreTrimmedByCountFirstThenSize() {
        ServerStores a = new ServerStores(500);
        for (int s = 0; s < 6; s++) {
            ConversationStore st = a.store("server" + s + ".example");
            for (int c = 0; c < 20; c++) {
                for (int m = 0; m < 200; m++) st.addIncoming("User" + c, "m", s * 1_000_000L + c * 1000L + m, false);
            }
        }
        // 6 x 20 x 200 = 24000 messages, over HistoryJson.MAX_MESSAGES
        String json = HistoryJson.toJson(a, HistoryJson.MAX_BYTES);
        ServerStores b = new ServerStores(500);
        HistoryJson.fromJson(json, b, "local");
        int total = 0;
        for (String key : b.keys()) {
            for (Conversation c : b.store(key).all()) total += c.messages().size();
        }
        assertTrue(total <= HistoryJson.MAX_MESSAGES && total > HistoryJson.MAX_MESSAGES - 500, "kept " + total);
        // the newest survive
        assertNotNull(b.store("server5.example").get("User19"));
    }

    private static ConversationStore sample() {
        ConversationStore s = new ConversationStore(50);
        s.addIncoming("Alice", "hello 😀 ❤️", 1000, false);
        s.addOutgoing("Alice", "hi \"quoted\" \\ backé", 2000, Message.State.SENT);
        s.addIncoming("Bob_2", "second", 3000, false);
        return s;
    }

    private static List<String> texts(ConversationStore s, String name) {
        return s.get(name).messages().stream().map(m -> m.direction() + ":" + m.text() + "@" + m.time()).collect(Collectors.toList());
    }

    @Test
    void roundTrip() {
        ConversationStore a = sample();
        String json = toJson(a, HistoryJson.MAX_BYTES);
        ConversationStore b = new ConversationStore(50);
        assertEquals(2, fromJson(json, b));
        assertEquals(texts(a, "Alice"), texts(b, "Alice"));
        assertEquals(texts(a, "Bob_2"), texts(b, "Bob_2"));
        assertEquals("Bob_2", b.get("bob_2").name());
        assertFalse(b.dirty());
    }

    @Test
    void loadedMessagesAreNotUnread() {
        ConversationStore b = new ConversationStore(50);
        fromJson(toJson(sample(), HistoryJson.MAX_BYTES), b);
        assertEquals(0, b.totalUnread());
    }

    @Test
    void notesAndUnsentMessagesAreNotSaved() {
        ConversationStore a = new ConversationStore(50);
        a.addIncoming("Alice", "kept", 1, false);
        a.addNote("Alice", "a note", 2);
        a.addOutgoing("Alice", "queued", 3, Message.State.SENDING);
        a.addOutgoing("Alice", "failed", 4, Message.State.FAILED);
        String json = toJson(a, HistoryJson.MAX_BYTES);
        assertFalse(json.contains("a note"));
        assertFalse(json.contains("queued"));
        assertFalse(json.contains("failed"));
        assertTrue(json.contains("kept"));
    }

    @Test
    void tooBigIsTrimmedFromTheOldestEnd() {
        ConversationStore a = new ConversationStore(500);
        for (int i = 0; i < 400; i++) a.addIncoming("Alice", "message number " + i + " " + "x".repeat(40), i, false);
        String json = toJson(a, 8000);
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= 8000);
        ConversationStore b = new ConversationStore(500);
        assertEquals(1, fromJson(json, b));
        List<Message> m = b.get("Alice").messages();
        assertFalse(m.isEmpty());
        assertTrue(m.get(m.size() - 1).text().startsWith("message number 399 "));
        assertTrue(m.size() < 400);
    }

    @Test
    void garbageIsNotAHistory() {
        ConversationStore s = new ConversationStore(50);
        assertEquals(-1, fromJson("", s));
        assertEquals(-1, fromJson("not json", s));
        assertEquals(-1, fromJson("[1,2,3]", s));
        assertEquals(-1, fromJson("{\"version\":2,\"conversations\":[]}", s));
        assertEquals(-1, fromJson("{\"version\":1}", s));
        assertEquals(-1, fromJson("{\"version\":1,\"conversations\":{}}", s));
        assertEquals(-1, fromJson("{\"version\":\"1\",\"conversations\":[]}", s));
        assertEquals(-1, fromJson("[".repeat(100_000), s));
        assertTrue(s.isEmpty());
    }

    @Test
    void badEntriesAreSkippedAndGoodOnesKept() {
        String json = "{\"version\":1,\"conversations\":["
            + "{\"name\":\"bad name\",\"messages\":[{\"d\":\"in\",\"t\":\"x\",\"at\":1}]},"
            + "{\"name\":\"Alice\",\"messages\":["
            + "{\"d\":\"in\",\"t\":\"good\",\"at\":5},"
            + "{\"d\":\"sideways\",\"t\":\"bad dir\",\"at\":6},"
            + "{\"d\":\"out\",\"t\":123,\"at\":7},"
            + "{\"d\":\"out\",\"t\":\"neg\",\"at\":-1},"
            + "{\"d\":\"out\",\"t\":\"frac\",\"at\":1.5},"
            + "{\"d\":\"out\",\"t\":\"\",\"at\":8},"
            + "\"junk\",null,"
            + "{\"d\":\"out\",\"t\":\"also good\",\"at\":4}]},"
            + "42,"
            + "{\"name\":\"Carol\",\"messages\":\"nope\"},"
            + "{\"name\":\"Dave\",\"messages\":[]}"
            + "]}";
        ConversationStore s = new ConversationStore(50);
        assertEquals(1, fromJson(json, s));
        assertEquals(1, s.size());
        // sorted by time
        assertEquals(List.of("OUT:also good@4", "IN:good@5"), texts(s, "Alice"));
    }

    @Test
    void textsAreCleanedOnLoad() {
        String json = "{\"version\":1,\"conversations\":[{\"name\":\"Alice\",\"messages\":[{\"d\":\"in\",\"t\":\"\\u00a7chi\\u0000\",\"at\":1}]}]}";
        ConversationStore s = new ConversationStore(50);
        fromJson(json, s);
        assertEquals("hi", s.get("Alice").messages().get(0).text());
    }

    @Test
    void loadAppliesTheCap() {
        ConversationStore a = new ConversationStore(500);
        for (int i = 0; i < 100; i++) a.addIncoming("Alice", "m" + i, i + 1, false);
        ConversationStore b = new ConversationStore(ConversationStore.MIN_CAP);
        assertEquals(1, fromJson(toJson(a, HistoryJson.MAX_BYTES), b));
        assertEquals(ConversationStore.MIN_CAP, b.get("Alice").messages().size());
        assertEquals("m99", b.get("Alice").messages().get(ConversationStore.MIN_CAP - 1).text());
    }

    // ---------- the file ----------

    @Test
    void fileRoundTrip(@TempDir Path dir) {
        Path file = dir.resolve("sub").resolve("messenger-history.json");
        assertTrue(HistoryJson.save(file, wrap(sample())));
        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(dir.resolve("sub").resolve("messenger-history.json.tmp")));
        ConversationStore b = new ConversationStore(50);
        HistoryJson.Loaded r = HistoryJson.load(file, wrap(b), "local");
        assertEquals(HistoryJson.Status.OK, r.status());
        assertEquals(2, r.conversations());
        assertEquals(3, b.get("Alice").messages().size() + b.get("Bob_2").messages().size());
    }

    @Test
    void savingAgainReplacesTheFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("h.json");
        assertTrue(HistoryJson.save(file, wrap(sample())));
        ConversationStore small = new ConversationStore(50);
        small.addIncoming("Zed", "only", 1, false);
        assertTrue(HistoryJson.save(file, wrap(small)));
        String text = Files.readString(file);
        assertTrue(text.contains("only"));
        assertFalse(text.contains("Alice"));
    }

    @Test
    void missingFile(@TempDir Path dir) {
        ConversationStore b = new ConversationStore(50);
        assertEquals(HistoryJson.Status.MISSING, HistoryJson.load(dir.resolve("nope.json"), wrap(b), "local").status());
        assertTrue(b.isEmpty());
    }

    @Test
    void corruptFileIsMovedAsideAndIgnored(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("h.json");
        Files.writeString(file, "{\"version\":1,\"conversations\":[{\"name\":\"Alice\",\"mess");
        ConversationStore b = new ConversationStore(50);
        HistoryJson.Loaded r = HistoryJson.load(file, wrap(b), "local");
        assertEquals(HistoryJson.Status.CORRUPT, r.status());
        assertTrue(b.isEmpty());
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(dir.resolve("h.json.corrupt")));
        // and the next save works
        assertTrue(HistoryJson.save(file, wrap(sample())));
        assertEquals(HistoryJson.Status.OK, HistoryJson.load(file, wrap(new ConversationStore(50)), "local").status());
    }

    @Test
    void binaryGarbageIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("h.json");
        byte[] junk = new byte[2000];
        new java.util.Random(1).nextBytes(junk);
        Files.write(file, junk);
        assertEquals(HistoryJson.Status.CORRUPT, HistoryJson.load(file, wrap(new ConversationStore(50)), "local").status());
    }

    @Test
    void hugeFileIsNotRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("h.json");
        try (var out = Files.newOutputStream(file)) {
            byte[] chunk = new byte[1024 * 1024];
            java.util.Arrays.fill(chunk, (byte) ' ');
            for (int i = 0; i < 5; i++) out.write(chunk);
        }
        ConversationStore b = new ConversationStore(50);
        assertEquals(HistoryJson.Status.TOO_BIG, HistoryJson.load(file, wrap(b), "local").status());
        assertTrue(Files.exists(file)); // left alone
    }

    @Test
    void aDirectoryWhereTheFileShouldBeIsHandled(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("h.json");
        Files.createDirectory(file);
        assertEquals(HistoryJson.Status.MISSING, HistoryJson.load(file, wrap(new ConversationStore(50)), "local").status());
        Files.writeString(file.resolve("inner.txt"), "x");
        assertFalse(HistoryJson.save(file, wrap(sample()))); // a non-empty directory can not be replaced; reported, not thrown
    }

    @Test
    void loadMergesIntoWhatIsInMemory(@TempDir Path dir) {
        Path file = dir.resolve("h.json");
        HistoryJson.save(file, wrap(sample()));
        ConversationStore live = new ConversationStore(50);
        live.addIncoming("Alice", "hello 😀 ❤️", 1000, false); // same as in the file
        live.addIncoming("Alice", "newer", 5000, false);
        HistoryJson.load(file, wrap(live), "local");
        assertEquals(3, live.get("Alice").messages().size());
        assertTrue(live.dirty()); // memory had unsaved changes: still to be saved
    }

    @Test
    void deleteRemovesTheFile(@TempDir Path dir) {
        Path file = dir.resolve("h.json");
        HistoryJson.save(file, wrap(sample()));
        assertTrue(HistoryJson.delete(file));
        assertFalse(Files.exists(file));
        assertTrue(HistoryJson.delete(file)); // nothing to delete is fine
    }
}
