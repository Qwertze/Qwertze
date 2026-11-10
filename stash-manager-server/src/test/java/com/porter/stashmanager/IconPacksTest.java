package com.porter.stashmanager;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Icon sets built from synthetic jars: no Mojang data is used or stored. */
class IconPacksTest {
    @TempDir
    Path tmp;

    static final String V = "1.99.9"; // a version no launcher on the test machine has

    static byte[] syntheticJar() throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) img.setRGB(x, y, 0xFF336699);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            String item = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/test\"}}";
            entry(z, "assets/minecraft/items/stone.json", item.getBytes(StandardCharsets.UTF_8));
            entry(z, "assets/minecraft/items/dirt_path.json", item.getBytes(StandardCharsets.UTF_8));
            entry(z, "assets/minecraft/items/../evil.json", item.getBytes(StandardCharsets.UTF_8)); // never matches the name pattern
            entry(z, "assets/minecraft/models/item/test.json",
                "{\"parent\":\"minecraft:builtin/generated\",\"textures\":{\"layer0\":\"minecraft:item/test\"}}".getBytes(StandardCharsets.UTF_8));
            entry(z, "assets/minecraft/textures/item/test.png", png.toByteArray());
        }
        return out.toByteArray();
    }

    static void entry(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    static String sha1(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(b));
    }

    @Test
    void buildsFromALocalJarOnceAndKeepsTheJar() throws Exception {
        Path jar = tmp.resolve("local.jar");
        Files.write(jar, syntheticJar());
        Path data = tmp.resolve("data");
        try (IconPacks packs = new IconPacks(data, jar, null, 64)) {
            packs.request(V);
            assertTrue(packs.await(V, 20_000));
            assertEquals(IconPacks.State.READY, packs.state(V));
        }
        Path dir = data.resolve("icons").resolve("game-" + V);
        assertTrue(Files.isRegularFile(dir.resolve("minecraft/stone.png")));
        assertTrue(Files.isRegularFile(dir.resolve("minecraft/dirt_path.png")));
        assertFalse(Files.exists(dir.resolve("evil.png")));
        assertTrue(Files.isRegularFile(dir.resolve(IconPacks.MARKER)));
        assertTrue(Files.isRegularFile(jar), "a jar from this machine is never deleted");
        try (var s = Files.list(data.resolve("icons"))) {
            assertEquals(1, s.count(), "the temp build folder is gone");
        }
        // A second launch finds the set complete: works even with the jar gone and no downloader.
        Files.delete(jar);
        try (IconPacks again = new IconPacks(data, jar, null, 64)) {
            assertTrue(again.verify(dir));
            again.request(V);
            assertTrue(again.await(V, 20_000));
            assertEquals(IconPacks.State.READY, again.state(V));
        }
    }

    @Test
    void aCorruptSetIsBuiltAgainAndAMissingJarWithoutDownloadFails() throws Exception {
        Path jar = tmp.resolve("local.jar");
        Files.write(jar, syntheticJar());
        Path data = tmp.resolve("data");
        try (IconPacks packs = new IconPacks(data, jar, null, 64)) {
            packs.request(V);
            assertTrue(packs.await(V, 20_000));
        }
        Path dir = data.resolve("icons").resolve("game-" + V);
        Files.writeString(dir.resolve("minecraft/stone.png"), "garbage");
        try (IconPacks packs = new IconPacks(data, jar, null, 64)) {
            assertFalse(packs.verify(dir));
            packs.request(V);
            assertTrue(packs.await(V, 20_000));
            assertEquals(IconPacks.State.READY, packs.state(V));
            assertTrue(packs.verify(dir));
        }
        Files.delete(dir.resolve(IconPacks.MARKER));
        Files.delete(jar);
        try (IconPacks packs = new IconPacks(data, jar, null, 64)) {
            packs.request(V);
            assertTrue(packs.await(V, 20_000));
            assertEquals(IconPacks.State.FAILED, packs.state(V), "no jar and iconDownload=false: nothing to build from");
        }
    }

    @Test
    void ignoresBadVersions() throws Exception {
        try (IconPacks packs = new IconPacks(tmp.resolve("d"), null, null, 64)) {
            packs.request("../../etc");
            packs.request(null);
            assertNull(packs.state("../../etc"));
            assertEquals(0, packs.activeCount());
        }
    }

    @Test
    void aPermanentFailureGoesStraightToFailedWithoutPending() throws Exception {
        // Long retry delays: if a permanent failure were retried, this would time out.
        try (IconPacks packs = new IconPacks(tmp.resolve("d"), null, null, 64, 60_000, 60_000)) {
            packs.request(V);
            assertTrue(packs.await(V, 5_000));
            assertEquals(IconPacks.State.FAILED, packs.state(V));
            assertFalse(packs.pending(V), "not pending, so the icon route uses the wiki fallback");
            assertEquals(0, packs.activeCount(), "a failed version frees its slot");
        }
    }

    static class Flaky extends ClientJarFetcher {
        final ClientJarFetcher real;
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        final Throwable first;

        Flaky(ClientJarFetcher real, Throwable first) {
            super("http://127.0.0.1:1/none", u -> false);
            this.real = real;
            this.first = first;
        }

        @Override
        void fetch(String v, Path target) throws IOException, InterruptedException {
            if (calls.getAndIncrement() == 0 && first != null) {
                if (first instanceof Error e) throw e;
                throw (IOException) first;
            }
            real.fetch(v, target);
        }
    }

    @Test
    void aTransientFailureIsRetriedAndNotPendingWhileWaiting() throws Exception {
        byte[] jar = syntheticJar();
        Fake f = fake(jar, sha1(jar), null);
        try {
            Flaky flaky = new Flaky(fetcher(f), new IOException("network down"));
            try (IconPacks packs = new IconPacks(tmp.resolve("data"), null, flaky, 64, 300)) {
                packs.request(V);
                boolean sawWaiting = false;
                for (int i = 0; i < 400 && packs.state(V) != IconPacks.State.READY; i++) {
                    if (packs.state(V) == IconPacks.State.WAITING) {
                        sawWaiting = true;
                        assertFalse(packs.pending(V));
                    }
                    Thread.sleep(5);
                }
                assertTrue(packs.await(V, 20_000));
                assertTrue(sawWaiting);
                assertEquals(IconPacks.State.READY, packs.state(V));
                assertEquals(2, flaky.calls.get());
            }
        } finally {
            f.server.stop(0);
        }
    }

    @Test
    void anErrorLeavesTheStateFailedNotStuck() throws Exception {
        Flaky flaky = new Flaky(null, new StackOverflowError("simulated"));
        try (IconPacks packs = new IconPacks(tmp.resolve("data"), null, flaky, 64)) {
            packs.request(V);
            assertTrue(packs.await(V, 10_000));
            assertEquals(IconPacks.State.FAILED, packs.state(V));
            assertFalse(packs.pending(V));
        }
    }

    @Test
    void anUnusableLocalJarFallsBackToTheDownload() throws Exception {
        Path bad = tmp.resolve("bad.jar");
        Files.writeString(bad, "this is not a zip");
        byte[] jar = syntheticJar();
        Fake f = fake(jar, sha1(jar), null);
        try {
            Path data = tmp.resolve("data");
            try (IconPacks packs = new IconPacks(data, bad, fetcher(f), 64)) {
                packs.request(V);
                assertTrue(packs.await(V, 20_000));
                assertEquals(IconPacks.State.READY, packs.state(V));
            }
            assertTrue(Files.isRegularFile(data.resolve("icons/game-" + V + "/minecraft/stone.png")));
            assertTrue(Files.isRegularFile(bad), "the file from the user is left alone");
        } finally {
            f.server.stop(0);
        }
    }

    @Test
    void aVersionMojangDoesNotListTakesNoSlot() throws Exception {
        byte[] jar = syntheticJar();
        Fake f = fake(jar, sha1(jar), null);
        try (IconPacks packs = new IconPacks(tmp.resolve("data"), null, fetcher(f), 64, 60_000)) {
            packs.request("1.97.3");
            assertTrue(packs.await("1.97.3", 10_000));
            assertEquals(IconPacks.State.FAILED, packs.state("1.97.3"));
            assertEquals(0, packs.activeCount());
            packs.request("1.97.3"); // once per launch: no second attempt
            assertEquals(IconPacks.State.FAILED, packs.state("1.97.3"));
        } finally {
            f.server.stop(0);
        }
    }

    @Test
    void aUsableLocalJarStillRendersOnDemandNeverPending() throws Exception {
        Path jar = tmp.resolve("local.jar");
        Files.write(jar, syntheticJar());
        Path data = tmp.resolve("data");
        GameIcons game = new GameIcons(data.resolve("icons"), jar, true);
        game.attachPacks(new IconPacks(data, jar, null, 64));
        try (IconService icons = new IconService(data, id -> new IndexStore.IconInfo("", V), v -> true, game, false)) {
            assertEquals(200, icons.get("minecraft", "dirt_path", V).status(), "rendered on demand from the local jar, not 503");
        }
    }

    @Test
    void leftoversFromAnInterruptedRunAreSwept() throws Exception {
        Path data = tmp.resolve("data");
        Files.createDirectories(data.resolve("icons/game-1.2.3.build-abc/minecraft"));
        Files.writeString(data.resolve("icons/game-1.2.3.build-abc/minecraft/x.png"), "x");
        Files.createDirectories(data.resolve("icons/game-1.2.3/minecraft"));
        Files.writeString(data.resolve("icons/game-1.2.3/minecraft/keep.png"), "k");
        Files.createDirectories(data.resolve("jars/1.2.3"));
        Files.writeString(data.resolve("jars/1.2.3/client123.part"), "p");
        Files.writeString(data.resolve("jars/1.2.3/client.jar"), "j");
        assertEquals(0, IconPacks.sweep(data, IconPacks.SWEEP_MIN_AGE_MS), "fresh leftovers may belong to a live instance");
        assertTrue(Files.exists(data.resolve("icons/game-1.2.3.build-abc")));
        assertEquals(3, IconPacks.sweep(data, 0));
        assertFalse(Files.exists(data.resolve("icons/game-1.2.3.build-abc")));
        assertTrue(Files.exists(data.resolve("icons/game-1.2.3/minecraft/keep.png")));
        assertFalse(Files.exists(data.resolve("jars/1.2.3")));
    }

    @Test
    void aRepeatedRequestWhileWaitingDoesNothingAndCloseDuringARetryEndsFailed() throws Exception {
        Flaky flaky = new Flaky(null, new IOException("network down"));
        IconPacks packs = new IconPacks(tmp.resolve("data"), null, flaky, 64, 60_000);
        packs.request(V);
        for (int i = 0; i < 400 && packs.state(V) != IconPacks.State.WAITING; i++) Thread.sleep(10);
        assertEquals(IconPacks.State.WAITING, packs.state(V));
        assertFalse(packs.pending(V));
        packs.request(V);
        assertEquals(IconPacks.State.WAITING, packs.state(V));
        assertEquals(1, packs.activeCount());
        assertEquals(1, flaky.calls.get(), "the repeated request started no second attempt");
        packs.close();
        assertEquals(IconPacks.State.FAILED, packs.state(V));
        assertEquals(1, flaky.calls.get(), "the scheduled retry never ran");
    }

    @Test
    void anOldManifestWithoutDefsIsBuiltAgain() throws Exception {
        Path jar = tmp.resolve("local.jar");
        Files.write(jar, syntheticJar());
        Path data = tmp.resolve("data");
        try (IconPacks packs = new IconPacks(data, jar, null, 64)) {
            packs.request(V);
            assertTrue(packs.await(V, 20_000));
            assertNotNull(packs.knownItems(V));
            assertTrue(packs.knownItems(V).contains("dirt_path"));
        }
        Path dir = data.resolve("icons/game-" + V);
        com.google.gson.JsonObject m = com.google.gson.JsonParser.parseString(Files.readString(dir.resolve(IconPacks.MANIFEST))).getAsJsonObject();
        m.remove("defs");
        Files.writeString(dir.resolve(IconPacks.MANIFEST), m.toString());
        try (IconPacks packs = new IconPacks(data, jar, null, 64)) {
            assertFalse(packs.verify(dir), "no defs: the set counts as incomplete");
            packs.request(V);
            assertTrue(packs.await(V, 20_000));
            assertEquals(IconPacks.State.READY, packs.state(V));
            assertTrue(packs.verify(dir));
            assertTrue(packs.knownItems(V).contains("stone"));
        }
    }

    @Test
    void iconDownloadIsParsedStrictly() {
        assertTrue(Config.parseIconDownload(null));
        assertTrue(Config.parseIconDownload(" TRUE "));
        assertFalse(Config.parseIconDownload("false"));
        assertFalse(Config.parseIconDownload("yes"));
        assertFalse(Config.parseIconDownload("1"));
        assertFalse(Config.parseIconDownload(""));
    }

    // ---------- the download path, against a local fake Mojang ----------

    record Fake(HttpServer server, String base) {
    }

    Fake fake(byte[] jar, String sha1, CountDownLatch gate) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4);
        String base = "http://127.0.0.1:" + s.getAddress().getPort();
        s.createContext("/manifest", ex -> {
            byte[] b = ("{\"versions\":[{\"id\":\"" + V + "\",\"url\":\"" + base + "/v.json\"}]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.createContext("/v.json", ex -> {
            byte[] b = ("{\"downloads\":{\"client\":{\"url\":\"" + base + "/client.jar\",\"sha1\":\"" + sha1 + "\",\"size\":" + jar.length + "}}}")
                .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.createContext("/client.jar", ex -> {
            try {
                if (gate != null) gate.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            ex.sendResponseHeaders(200, jar.length);
            ex.getResponseBody().write(jar);
            ex.close();
        });
        s.start();
        return new Fake(s, base);
    }

    static ClientJarFetcher fetcher(Fake f) {
        return new ClientJarFetcher(f.base + "/manifest", u -> "127.0.0.1".equals(u.getHost()));
    }

    @Test
    void downloadsOnceVerifiesRendersAndDeletesTheJar() throws Exception {
        byte[] jar = syntheticJar();
        Fake f = fake(jar, sha1(jar), null);
        try {
            Path data = tmp.resolve("data");
            try (IconPacks packs = new IconPacks(data, null, fetcher(f), 64)) {
                packs.request(V);
                assertTrue(packs.await(V, 20_000));
                assertEquals(IconPacks.State.READY, packs.state(V));
            }
            assertTrue(Files.isRegularFile(data.resolve("icons/game-" + V + "/minecraft/stone.png")));
            assertFalse(Files.exists(data.resolve("jars").resolve(V).resolve("client.jar")), "the jar is deleted after rendering");
        } finally {
            f.server.stop(0);
        }
    }

    @Test
    void aWrongSha1IsRefusedAndNothingIsLeftBehind() throws Exception {
        byte[] jar = syntheticJar();
        Fake f = fake(jar, "0".repeat(40), null);
        try {
            Path data = tmp.resolve("data");
            IOException e = assertThrows(IOException.class, () -> fetcher(f).fetch(V, data.resolve("jars/x/client.jar")));
            assertTrue(e.getMessage().contains("sha1"));
            assertFalse(Files.exists(data.resolve("jars/x/client.jar")));
            try (var s = Files.list(data.resolve("jars/x"))) {
                assertEquals(0, s.count(), "temp file removed");
            }
            try (IconPacks packs = new IconPacks(data, null, fetcher(f), 64)) {
                packs.request(V);
                assertTrue(packs.await(V, 20_000));
                assertEquals(IconPacks.State.FAILED, packs.state(V));
            }
            assertFalse(Files.exists(data.resolve("icons/game-" + V + "/" + IconPacks.MARKER)));
        } finally {
            f.server.stop(0);
        }
    }

    @Test
    void iconsAnswerPendingWhileTheSetIsBuilding() throws Exception {
        byte[] jar = syntheticJar();
        CountDownLatch gate = new CountDownLatch(1);
        Fake f = fake(jar, sha1(jar), gate);
        Path data = tmp.resolve("data");
        GameIcons game = new GameIcons(data.resolve("icons"), null, true);
        game.attachPacks(new IconPacks(data, null, fetcher(f), 64));
        try (IconService icons = new IconService(data, id -> new IndexStore.IconInfo("", V), v -> true, game, false)) {
            IconService.Icon first = icons.get("minecraft", "dirt_path", V);
            assertEquals(503, first.status(), "pending while the download runs");
            gate.countDown();
            IconService.Icon last = first;
            for (int i = 0; i < 400 && last.status() == 503; i++) {
                Thread.sleep(50);
                last = icons.get("minecraft", "dirt_path", V);
            }
            assertEquals(200, last.status());
            assertEquals("private, max-age=604800", last.cacheControl());
        } finally {
            f.server.stop(0);
        }
    }

    // ---------- the fetcher's host rules ----------

    @Test
    void onlyMojangHostsOverHttpsAreAllowed() {
        assertTrue(ClientJarFetcher.allowedTarget(URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")));
        assertTrue(ClientJarFetcher.allowedTarget(URI.create("https://piston-data.mojang.com/v1/objects/abc/client.jar")));
        assertFalse(ClientJarFetcher.allowedTarget(URI.create("http://piston-data.mojang.com/x")));
        assertFalse(ClientJarFetcher.allowedTarget(URI.create("https://evil.example/x")));
        assertFalse(ClientJarFetcher.allowedTarget(URI.create("https://piston-data.mojang.com.evil.example/x")));
        assertFalse(ClientJarFetcher.allowedTarget(URI.create("https://piston-data.mojang.com:8443/x")));
        assertFalse(ClientJarFetcher.allowedTarget(URI.create("https://u@piston-data.mojang.com/x")));
    }

    // ---------- planner materials that no index has ----------

    @Test
    void unindexedPlannerIdsAreAllowedValidatedAndBounded() throws Exception {
        IndexStore store = new IndexStore(tmp.resolve("s"), CreativeTabs.NONE);
        store.load();
        assertNull(store.iconInfo("minecraft:dirt_path"));
        IndexStore.IconInfo info = store.allowIcon("minecraft:dirt_path", "1.21.4");
        assertNotNull(info);
        assertEquals("1.21.4", info.mcVersion());
        assertEquals(info, store.iconInfo("minecraft:dirt_path"));
        assertNull(store.allowIcon("minecraft:../x", "1.21.4"));
        assertNull(store.allowIcon("minecraft:Dirt", "1.21.4"));
        assertNull(store.allowIcon("dirt", "1.21.4"));
        assertNull(store.allowIcon(":dirt", "1.21.4"));
        assertNull(store.allowIcon("minecraft:dirt", "1.21.4/../x"));
        assertNull(store.iconInfo("minecraft:../x"));
        // LRU: the oldest id makes room, so a new plan always works.
        store.allowIcon("minecraft:item_first", "1.21.4");
        for (int i = 0; i < IndexStore.MAX_EXTRA_ICON_IDS + 10; i++) store.allowIcon("minecraft:item_" + i, "1.21.4");
        assertNotNull(store.allowIcon("minecraft:one_more", "1.21.4"));
        assertNull(store.iconInfo("minecraft:item_first"), "evicted");
        assertNull(store.iconInfo("minecraft:dirt_path"), "evicted too");
        assertNotNull(store.iconInfo("minecraft:one_more"));
        // The info carries the caller's version, not the first-seen one.
        assertEquals("1.21.11", store.allowIcon("minecraft:one_more", "1.21.11").mcVersion());
        assertEquals("1.21.4", store.allowIcon("minecraft:one_more", "1.21.4").mcVersion());
        // Two stashes on different versions never see each other's version.
        assertEquals("1.21.4", store.allowIcon("minecraft:shared", "1.21.4").mcVersion());
        assertEquals("1.21.11", store.allowIcon("minecraft:shared", "1.21.11").mcVersion());
        assertEquals("1.21.4", store.allowIcon("minecraft:shared", "1.21.4").mcVersion());
        // With a complete icon set for a version, only its item ids are accepted.
        store.setPackItems(v -> v.equals("1.21.4") ? java.util.Set.of("dirt_path") : null);
        assertNotNull(store.allowIcon("minecraft:dirt_path", "1.21.4"));
        assertNull(store.allowIcon("minecraft:not_an_item", "1.21.4"));
        assertNull(store.allowIcon("other:dirt_path", "1.21.4"));
        assertNotNull(store.allowIcon("minecraft:not_an_item", "1.21.11"), "no pack for that version: the regex rules");
    }

    @Test
    void anIdNoIndexHasIsStillRefusedByTheIconService() throws Exception {
        Path data = tmp.resolve("d2");
        try (IconService icons = new IconService(data, id -> null, v -> false, new GameIcons(data.resolve("icons"), null, false), false)) {
            assertEquals(IconService.CACHE_UNKNOWN, icons.get("minecraft", "never_allowed", null).cacheControl());
        }
    }

    @Test
    void versionsAreStrictlyNumericWithDots() {
        for (String ok : new String[]{"1.21.4", "1.21.11", "26.2", "1.2.3.4", "1.99.9"}) assertTrue(IconService.MC_VERSION.matcher(ok).matches(), ok);
        for (String bad : new String[]{"1", "1.21.", "1.21..4", "1.21.4.", ".1.2", "1.2.3.4.5", "1.21.4 ", "1.21.4/../x", "1.21.4:x", "1.21.4\n",
            "1.2a", "", "1." + "1".repeat(40)}) {
            assertFalse(IconService.MC_VERSION.matcher(bad).matches(), "'" + bad + "'");
        }
    }

}
