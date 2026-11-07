package com.porter.qwerte.manager.local;

import com.porter.qwerte.manager.model.OrderModel.Entry;
import com.porter.qwerte.stash.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalOrderStoreTest {
    private static Entry entry(String id, String status, long created) {
        return new Entry(id, status, "phase", "msg", 2, 5, false, 0, created, created, false, "", "", "",
            List.of(new Order.Line("item", "minecraft:diamond", 640), new Order.Line("shulker", "s:abc", 2)), "each", "", List.of("A", ""), 3, true);
    }

    @Test
    void whatWasSavedIsReadBack(@TempDir Path dir) {
        LocalOrderStore a = new LocalOrderStore(dir);
        a.load("2b2t.org");
        a.put("stash", entry("L-1", "delivered", 100));
        a.put("stash", entry("L-2", "failed", 200));
        a.put("other", entry("L-3", "cancelled", 300));
        a.flush();

        LocalOrderStore b = new LocalOrderStore(dir);
        b.load("2b2t.org");
        List<Entry> list = b.list("stash");
        assertEquals(List.of("L-2", "L-1"), list.stream().map(Entry::id).toList(), "newest first");
        Entry e = list.get(1);
        assertEquals("delivered", e.status());
        assertEquals(2, e.done());
        assertEquals(5, e.total());
        assertEquals(2, e.lines().size());
        assertEquals("shulker", e.lines().get(1).type());
        assertEquals("each", e.namesMode());
        assertEquals(List.of("A", ""), e.namesEach());
        assertEquals(3, e.shulkers());
        assertTrue(e.hasResult());
        assertEquals(1, b.list("other").size());
        assertEquals("", b.warning());
    }

    @Test
    void anOrderStillOpenWhenTheGameStoppedIsMarkedFailed(@TempDir Path dir) {
        LocalOrderStore a = new LocalOrderStore(dir);
        a.load("s");
        a.put("stash", entry("L-1", "in_progress", 100));
        a.flush();
        LocalOrderStore b = new LocalOrderStore(dir);
        b.load("s");
        Entry e = b.list("stash").get(0);
        assertEquals("failed", e.status());
        assertTrue(e.message().startsWith("interrupted"));
        assertFalse(e.open());
    }

    @Test
    void aReplacedOrderKeepsOneEntry(@TempDir Path dir) {
        LocalOrderStore a = new LocalOrderStore(dir);
        a.load("s");
        a.put("stash", entry("L-1", "in_progress", 100));
        a.put("stash", entry("L-1", "delivered", 100));
        assertEquals(1, a.list("stash").size());
        assertEquals("delivered", a.list("stash").get(0).status());
    }

    @Test
    void aBrokenFileIsSetAsideAndTheHistoryStartsEmpty(@TempDir Path dir) throws Exception {
        Path file = LocalOrderStore.fileFor(dir, "srv");
        Files.writeString(file, "{ this is not json");
        LocalOrderStore s = new LocalOrderStore(dir);
        s.load("srv");
        assertTrue(s.list("stash").isEmpty());
        assertFalse(s.warning().isEmpty());
        assertFalse(Files.exists(file));
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().contains(".bad-")));
        }
        // and it can be written afterwards
        s.put("stash", entry("L-1", "delivered", 1));
        s.flush();
        assertTrue(Files.exists(file));
    }

    @Test
    void aFileOfTheWrongShapeIsSetAside(@TempDir Path dir) throws Exception {
        Files.writeString(LocalOrderStore.fileFor(dir, "srv"), "[1,2,3]");
        LocalOrderStore s = new LocalOrderStore(dir);
        s.load("srv");
        assertTrue(s.list("x").isEmpty());
        assertFalse(s.warning().isEmpty());
    }

    @Test
    void aFileOverTheLimitIsNotRead(@TempDir Path dir) throws Exception {
        Path file = LocalOrderStore.fileFor(dir, "srv");
        Files.write(file, new byte[LocalOrderStore.MAX_BYTES + 1]);
        LocalOrderStore s = new LocalOrderStore(dir);
        s.load("srv");
        assertTrue(s.list("x").isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test
    void theNumberOfOrdersIsBoundedAndOpenOnesAreKept() {
        List<LocalOrderStore.Item> list = new ArrayList<>();
        list.add(new LocalOrderStore.Item("s", entry("open", "in_progress", 1)));
        for (int i = 0; i < LocalOrderStore.MAX_ORDERS + 20; i++) list.add(new LocalOrderStore.Item("s", entry("L-" + i, "delivered", 10 + i)));
        LocalOrderStore.prune(list);
        assertEquals(LocalOrderStore.MAX_ORDERS, list.size());
        assertTrue(list.stream().anyMatch(i -> i.entry().id().equals("open")), "an open order is never dropped, however old");
        assertFalse(list.stream().anyMatch(i -> i.entry().id().equals("L-0")), "the oldest finished one goes first");
        assertTrue(list.stream().anyMatch(i -> i.entry().id().equals("L-" + (LocalOrderStore.MAX_ORDERS + 19))));
    }

    @Test
    void theWrittenTextNeverExceedsTheLimitAndItIsReadBack() {
        List<LocalOrderStore.Item> list = new ArrayList<>();
        String huge = "m".repeat(300);
        for (int i = 0; i < 100; i++) {
            List<Order.Line> lines = new ArrayList<>();
            for (int k = 0; k < 200; k++) lines.add(new Order.Line("item", "minecraft:some_long_item_name_" + k + "#" + "f".repeat(8), 1 + k));
            list.add(new LocalOrderStore.Item("s", new Entry("L-" + i, "delivered", "p", huge, 1, 1, false, 0, i, i, false, "", "", "", lines, "", "", List.of(), 1, true)));
        }
        String text = LocalOrderStore.encodeCapped(list);
        assertNotNull(text);
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= LocalOrderStore.MAX_BYTES);
        List<LocalOrderStore.Item> back = LocalOrderStore.decode(text);
        assertNotNull(back);
        assertTrue(back.size() < 100, "the oldest finished orders were dropped to fit");
        assertTrue(back.stream().anyMatch(i -> i.entry().id().equals("L-99")), "the newest stays");
    }

    @Test
    void openOrdersAloneOverTheLimitAreNotWritten() {
        List<LocalOrderStore.Item> list = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            List<Order.Line> lines = new ArrayList<>();
            for (int k = 0; k < 200; k++) lines.add(new Order.Line("item", "minecraft:some_long_item_name_" + k + "#" + "f".repeat(40), 1 + k));
            list.add(new LocalOrderStore.Item("s", new Entry("L-" + i, "in_progress", "p", "", 1, 1, false, 0, i, i, false, "", "", "", lines, "", "", List.of(), 0, false)));
        }
        assertNull(LocalOrderStore.encodeCapped(list));
    }

    @Test
    void removeFinishedKeepsOpenOrdersAndOtherStashes(@TempDir Path dir) {
        LocalOrderStore s = new LocalOrderStore(dir);
        s.load("s");
        s.put("a", entry("L-1", "delivered", 1));
        s.put("a", entry("L-2", "in_progress", 2));
        s.put("b", entry("L-3", "failed", 3));
        assertEquals(1, s.removeFinished("a"));
        assertEquals(List.of("L-2"), s.list("a").stream().map(Entry::id).toList());
        assertEquals(1, s.list("b").size());
    }

    @Test
    void junkEntriesAreLeftOut() {
        List<LocalOrderStore.Item> back = LocalOrderStore.decode("{\"orders\":[1,{\"stash\":\"s\"},{\"stash\":\"s\",\"id\":\"L-1\",\"status\":\"failed\",\"lines\":[{\"type\":\"item\",\"key\":\"a\",\"count\":1}]},{\"id\":\"L-2\",\"lines\":[]}]}");
        assertNotNull(back);
        assertEquals(1, back.size());
        assertEquals("L-1", back.get(0).entry().id());
        assertNull(LocalOrderStore.decode("nope"));
    }

    @Test
    void theFileNameCarriesAHashOfTheExactAddress() {
        Path a = LocalOrderStore.fileFor(Path.of("x"), "Play.Example.com");
        Path b = LocalOrderStore.fileFor(Path.of("x"), "play.example.com");
        Path c = LocalOrderStore.fileFor(Path.of("x"), "play-example.com");
        assertEquals(ContainerKey.fileName("Play.Example.com") + ".json", a.getFileName().toString());
        assertTrue(a.getFileName().toString().matches(".*-[0-9a-f]{8}\\.json"), a.getFileName().toString());
        assertFalse(a.equals(b), "addresses that differ only in case don't share a history");
        assertFalse(b.equals(c));
    }

    @Test
    void aHistoryUnderTheOldNameIsMoved(@TempDir Path dir) throws Exception {
        Path old = LocalOrderStore.legacyFileFor(dir, "srv.example.com");
        Files.writeString(old, LocalOrderStore.encode(List.of(new LocalOrderStore.Item("stash", entry("L-7", "delivered", 5)))));
        LocalOrderStore s = new LocalOrderStore(dir);
        s.load("srv.example.com");
        assertEquals(1, s.list("stash").size());
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(LocalOrderStore.fileFor(dir, "srv.example.com")));
    }

    @Test
    void onlyTheLastThreeSetAsideFilesAreKept(@TempDir Path dir) throws Exception {
        Path file = LocalOrderStore.fileFor(dir, "srv");
        for (int i = 0; i < 5; i++) {
            Files.writeString(file, "{ broken " + i);
            LocalOrderStore s = new LocalOrderStore(dir);
            s.load("srv");
            Thread.sleep(3);
        }
        try (var files = Files.list(dir)) {
            assertEquals(3, files.filter(f -> f.getFileName().toString().contains(".bad-")).count());
        }
    }

    @Test
    void whatCouldNotBeWrittenSurvivesASwitchToAnotherServer(@TempDir Path dir) throws Exception {
        // The "folder" is a file, so nothing can be written
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "x");
        LocalOrderStore s = new LocalOrderStore(blocked);
        s.load("a.example.com");
        s.put("stash", entry("L-1", "delivered", 1));
        s.load("b.example.com");
        assertTrue(s.list("stash").isEmpty(), "the other server has its own history");
        s.put("stash", entry("L-2", "delivered", 2));
        s.load("a.example.com");
        assertEquals(List.of("L-1"), s.list("stash").stream().map(Entry::id).toList(), "the unsaved history of the first server is still there");
        s.load("b.example.com");
        assertEquals(List.of("L-2"), s.list("stash").stream().map(Entry::id).toList());
    }
}
