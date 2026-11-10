package com.porter.stashmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Recipe sets built from synthetic jars, together with the icon pass: no Mojang data is used or stored. */
class RecipePacksTest {
    @TempDir
    Path tmp;

    static final String V = IconPacksTest.V;

    static byte[] png() throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) img.setRGB(x, y, 0xFF336699);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    Path writeJar(String name, boolean items) throws Exception {
        Path jar = tmp.resolve(name);
        Files.write(jar, RecipeFixtures.jar(items, png()));
        return jar;
    }

    static JsonObject manifest(Path data) throws IOException {
        return JsonParser.parseString(Files.readString(data.resolve("recipes").resolve(V).resolve("manifest.json"))).getAsJsonObject();
    }

    /** Packs with the recipe pass attached and icons on, from a local jar. */
    static IconPacks packs(Path data, Path jar, ClientJarFetcher iconFetcher, ClientJarFetcher recipeFetcher, RecipePacks rp, long... delays) {
        IconPacks p = new IconPacks(data, jar, iconFetcher, 64, delays);
        p.attachRecipes(rp, recipeFetcher);
        return p;
    }

    @Test
    void buildsFromALocalJarAndWritesTheFilesInOrder() throws Exception {
        Path jar = writeJar("local.jar", true);
        Path data = tmp.resolve("data");
        RecipePacks rp = new RecipePacks(data);
        try (IconPacks p = packs(data, jar, null, null, rp)) {
            rp.request(V);
            assertTrue(p.await(V, 20_000));
            assertEquals("ready", rp.state(V));
            assertEquals(IconPacks.State.READY, p.state(V));
        }
        Path dir = data.resolve("recipes").resolve(V);
        assertTrue(Files.isRegularFile(dir.resolve("raw/recipe/spruce_stairs.json")));
        assertTrue(Files.isRegularFile(dir.resolve("raw/tags/item/spruce_logs.json")));
        assertTrue(Files.isRegularFile(dir.resolve("table.json")));
        assertTrue(Files.isRegularFile(dir.resolve(".complete")));
        assertFalse(Files.exists(dir.resolve("evil.json")));
        assertFalse(Files.exists(dir.resolve("raw/recipe/Upper.json")));
        assertFalse(Files.exists(dir.resolve("raw/recipe/abs.json")));
        assertFalse(Files.exists(dir.resolve("raw/recipe/foreign.json")));
        assertFalse(Files.exists(dir.resolve("raw/tags/item/not_items.json")));
        try (var s = Files.walk(dir)) {
            assertTrue(s.noneMatch(x -> x.toString().contains("evil") || x.toString().contains("foreign")));
        }
        JsonObject m = manifest(data);
        assertEquals(V, m.get("mc").getAsString());
        assertEquals(RecipeTable.FORMAT, m.get("format").getAsInt());
        assertEquals(40, m.get("jarSha1").getAsString().length());
        assertEquals(rp.table(V).tableHash(), m.get("tableHash").getAsString());
        assertEquals(RecipeFixtures.table().tableHash(), m.get("tableHash").getAsString());
        assertEquals(rp.table(V).recipes().size(), m.get("recipes").getAsInt());
        assertEquals(rp.table(V).otherStations().size(), m.get("otherStations").getAsInt());
        assertEquals(2, m.get("dropped").getAsInt());
        assertTrue(Files.readString(dir.resolve("raw/items.json")).contains("firework_star"));
        assertTrue(Files.isRegularFile(jar), "a jar from this machine is never deleted");
        // A second launch finds both sets complete and needs no jar at all.
        Files.delete(jar);
        RecipePacks again = new RecipePacks(data);
        try (IconPacks p = packs(data, jar, null, null, again)) {
            again.request(V);
            assertTrue(p.await(V, 20_000));
            assertEquals("ready", again.state(V));
            assertEquals(rp.table(V).tableHash(), again.table(V).tableHash());
        }
    }

    @Test
    void recipesOnlyWhenTheIconSourceIsOff() throws Exception {
        Path jar = writeJar("local.jar", false);
        Path data = tmp.resolve("data");
        RecipePacks rp = new RecipePacks(data);
        try (IconPacks p = packs(data, jar, null, null, rp)) {
            p.iconsEnabled(false);
            rp.request(V);
            assertTrue(p.await(V, 20_000));
            assertEquals("ready", rp.state(V));
        }
        assertFalse(Files.exists(data.resolve("icons/game-" + V)), "no icon set was made");
    }

    @Test
    void aSetOfAnOlderFormatIsRebuiltFromRawWithoutAJar() throws Exception {
        Path jar = writeJar("local.jar", false);
        Path data = tmp.resolve("data");
        RecipePacks rp = new RecipePacks(data);
        try (IconPacks p = packs(data, jar, null, null, rp)) {
            p.iconsEnabled(false);
            rp.request(V);
            assertTrue(p.await(V, 20_000));
        }
        String hash = rp.table(V).tableHash();
        Path dir = data.resolve("recipes").resolve(V);
        JsonObject m = manifest(data);
        m.addProperty("format", 0);
        Files.writeString(dir.resolve("manifest.json"), m.toString());
        Files.writeString(dir.resolve("table.json"), "{\"version\":\"" + V + "\",\"recipes\":[]}"); // stale
        Files.delete(jar);
        RecipePacks again = new RecipePacks(data);
        assertFalse(again.complete(V), "format 0 is not current");
        try (IconPacks p = packs(data, jar, null, null, again)) {
            p.iconsEnabled(false);
            again.request(V);
            assertTrue(p.await(V, 20_000));
            assertEquals("ready", again.state(V), "rebuilt with no download and no jar");
            assertEquals(hash, again.table(V).tableHash());
        }
        assertEquals(RecipeTable.FORMAT, manifest(data).get("format").getAsInt());
    }

    @Test
    void aDamagedTableIsBuiltAgain() throws Exception {
        Path jar = writeJar("local.jar", false);
        Path data = tmp.resolve("data");
        RecipePacks rp = new RecipePacks(data);
        try (IconPacks p = packs(data, jar, null, null, rp)) {
            p.iconsEnabled(false);
            rp.request(V);
            assertTrue(p.await(V, 20_000));
        }
        Path dir = data.resolve("recipes").resolve(V);
        String text = Files.readString(dir.resolve("table.json"));
        Files.writeString(dir.resolve("table.json"), text.replace("\"count\":4", "\"count\":5"));
        RecipePacks again = new RecipePacks(data);
        assertFalse(again.complete(V), "the manifest's hash no longer matches the table");
        try (IconPacks p = packs(data, jar, null, null, again)) {
            p.iconsEnabled(false);
            again.request(V);
            assertTrue(p.await(V, 20_000));
            assertEquals("ready", again.state(V));
            assertEquals(rp.table(V).tableHash(), again.table(V).tableHash());
        }
    }

    @Test
    void noJarAndNoDownloadMeansNoRecipes() throws Exception {
        Path data = tmp.resolve("data");
        RecipePacks rp = new RecipePacks(data);
        try (IconPacks p = packs(data, null, null, null, rp)) {
            rp.request(V);
            assertEquals("building", rp.state(V));
            assertTrue(p.await(V, 20_000));
            assertEquals("none", rp.state(V));
            assertNull(rp.table(V));
        }
        assertFalse(Files.exists(data.resolve("recipes").resolve(V).resolve(".complete")));
    }

    @Test
    void badVersionsAreIgnored() throws Exception {
        RecipePacks rp = new RecipePacks(tmp.resolve("d"));
        try (IconPacks p = packs(tmp.resolve("d"), null, null, null, rp)) {
            rp.request("../../etc");
            rp.request(null);
            assertEquals("none", rp.state("../../etc"));
            assertEquals(0, p.activeCount());
        }
    }

    // ---------- the caps ----------

    static byte[] zip(java.util.function.Consumer<ZipOutputStream> fill) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            fill.accept(z);
        }
        return out.toByteArray();
    }

    static void put(ZipOutputStream z, String name, byte[] data) {
        try {
            RecipeFixtures.entry(z, name, data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void anOversizedEntryTooManyEntriesAndTooMuchDataAreRefused() throws Exception {
        byte[] ok = "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:a\"],\"result\":{\"id\":\"minecraft:b\"}}".getBytes(StandardCharsets.UTF_8);
        Path big = tmp.resolve("big.jar");
        Files.write(big, zip(z -> {
            put(z, "data/minecraft/recipe/ok.json", ok);
            put(z, "data/minecraft/recipe/huge.json", new byte[RecipePacks.MAX_ENTRY + 1]);
        }));
        assertThrows(ClientJarFetcher.PermanentException.class, () -> RecipePacks.readJar(big));

        Path many = tmp.resolve("many.jar");
        Files.write(many, zip(z -> {
            for (int i = 0; i <= RecipePacks.MAX_RECIPES; i++) put(z, "data/minecraft/recipe/r" + i + ".json", ok);
        }));
        assertThrows(ClientJarFetcher.PermanentException.class, () -> RecipePacks.readJar(many));

        Path manyTags = tmp.resolve("tags.jar");
        Files.write(manyTags, zip(z -> {
            put(z, "data/minecraft/recipe/ok.json", ok);
            for (int i = 0; i <= RecipePacks.MAX_TAGS; i++) put(z, "data/minecraft/tags/item/t" + i + ".json", "{\"values\":[]}".getBytes(StandardCharsets.UTF_8));
        }));
        assertThrows(ClientJarFetcher.PermanentException.class, () -> RecipePacks.readJar(manyTags));

        Path total = tmp.resolve("total.jar");
        byte[] chunk = new byte[60 * 1024];
        java.util.Arrays.fill(chunk, (byte) ' ');
        Files.write(total, zip(z -> {
            put(z, "data/minecraft/recipe/ok.json", ok);
            for (int i = 0; i < 300; i++) put(z, "data/minecraft/tags/item/pad" + i + ".json", chunk); // 17+ MiB, under 1024 entries
        }));
        assertThrows(ClientJarFetcher.PermanentException.class, () -> RecipePacks.readJar(total));

        Path none = tmp.resolve("none.jar");
        Files.write(none, zip(z -> put(z, "data/minecraft/tags/item/t.json", "{\"values\":[]}".getBytes(StandardCharsets.UTF_8))));
        assertThrows(ClientJarFetcher.PermanentException.class, () -> RecipePacks.readJar(none), "no recipes at all");

        Path fine = tmp.resolve("fine.jar");
        Files.write(fine, zip(z -> put(z, "data/minecraft/recipe/ok.json", ok)));
        assertEquals(1, RecipePacks.readJar(fine).recipes().size());
        // Through the pass: an oversized entry leaves no set and the state is failed, not stuck.
        Path data = tmp.resolve("data");
        RecipePacks rp = new RecipePacks(data);
        try (IconPacks p = packs(data, big, null, null, rp)) {
            p.iconsEnabled(false);
            rp.request(V);
            assertTrue(p.await(V, 20_000));
            assertEquals("failed", rp.state(V));
        }
        assertFalse(Files.exists(data.resolve("recipes").resolve(V).resolve(".complete")));
    }

    @Test
    void entryNamesNeverEscapeTheFolder() throws Exception {
        byte[] ok = "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:a\"],\"result\":{\"id\":\"minecraft:b\"}}".getBytes(StandardCharsets.UTF_8);
        Path jar = tmp.resolve("names.jar");
        Files.write(jar, zip(z -> {
            put(z, "data/minecraft/recipe/ok.json", ok);
            put(z, "data/minecraft/recipe/sub/dir/deep.json", ok);
            put(z, "data/minecraft/recipe//rooted.json", ok);
            put(z, "data/minecraft/recipe/trailing/.json", ok);
            put(z, "data/minecraft/recipe/a\\b.json", ok);
            put(z, "data/minecraft/recipe/a/../b.json", ok);
            put(z, "data/minecraft/recipe/C:/x.json", ok);
        }));
        RecipePacks.Raw raw = RecipePacks.readJar(jar);
        assertEquals(java.util.Set.of("ok", "sub/dir/deep"), raw.recipes().keySet());
    }

    // ---------- the shared jar ----------

    @Test
    void oneDownloadBuildsBothSetsAndIsDeleted() throws Exception {
        byte[] jar = RecipeFixtures.jar(true, png());
        IconPacksTest.Fake f = new IconPacksTest().fake(jar, IconPacksTest.sha1(jar), null);
        try {
            Path data = tmp.resolve("data");
            IconPacksTest.Flaky counting = new IconPacksTest.Flaky(IconPacksTest.fetcher(f), null);
            RecipePacks rp = new RecipePacks(data);
            try (IconPacks p = packs(data, null, counting, counting, rp)) {
                rp.request(V);
                assertTrue(p.await(V, 30_000));
                assertEquals(IconPacks.State.READY, p.state(V));
                assertEquals("ready", rp.state(V));
            }
            assertEquals(1, counting.calls.get(), "one download for both sets");
            assertTrue(Files.isRegularFile(data.resolve("icons/game-" + V + "/minecraft/stone.png")));
            assertTrue(Files.isRegularFile(data.resolve("recipes/" + V + "/.complete")));
            assertFalse(Files.exists(data.resolve("jars").resolve(V).resolve("client.jar")), "deleted once both were built");
        } finally {
            f.server().stop(0);
        }
    }

    @Test
    void completeIconsButMissingRecipesDownloadOnceMore() throws Exception {
        byte[] jar = RecipeFixtures.jar(true, png());
        IconPacksTest.Fake f = new IconPacksTest().fake(jar, IconPacksTest.sha1(jar), null);
        try {
            Path data = tmp.resolve("data");
            // First run: icons only (no recipe pass attached, as an older server).
            IconPacksTest.Flaky first = new IconPacksTest.Flaky(IconPacksTest.fetcher(f), null);
            try (IconPacks p = new IconPacks(data, null, first, 64)) {
                p.request(V);
                assertTrue(p.await(V, 30_000));
                assertEquals(IconPacks.State.READY, p.state(V));
            }
            assertEquals(1, first.calls.get());
            long stoneStamp = Files.getLastModifiedTime(data.resolve("icons/game-" + V + "/minecraft/stone.png")).toMillis();
            // Upgrade: the icon set is complete, the recipe set is missing: one more download, for recipes only.
            IconPacksTest.Flaky second = new IconPacksTest.Flaky(IconPacksTest.fetcher(f), null);
            RecipePacks rp = new RecipePacks(data);
            try (IconPacks p = packs(data, null, second, second, rp)) {
                rp.request(V);
                assertTrue(p.await(V, 30_000));
                assertEquals("ready", rp.state(V));
            }
            assertEquals(1, second.calls.get());
            assertEquals(stoneStamp, Files.getLastModifiedTime(data.resolve("icons/game-" + V + "/minecraft/stone.png")).toMillis(), "icons were not rebuilt");
            // And a third launch needs nothing.
            IconPacksTest.Flaky third = new IconPacksTest.Flaky(IconPacksTest.fetcher(f), null);
            RecipePacks again = new RecipePacks(data);
            try (IconPacks p = packs(data, null, third, third, again)) {
                again.request(V);
                assertTrue(p.await(V, 30_000));
                assertEquals("ready", again.state(V));
            }
            assertEquals(0, third.calls.get());
        } finally {
            f.server().stop(0);
        }
    }

    @Test
    void recipeDownloadIsSeparateFromIconDownload() throws Exception {
        byte[] jar = RecipeFixtures.jar(true, png());
        IconPacksTest.Fake f = new IconPacksTest().fake(jar, IconPacksTest.sha1(jar), null);
        try {
            // Icons may not download, recipes may: the jar is fetched for recipes.
            Path data = tmp.resolve("a");
            IconPacksTest.Flaky c1 = new IconPacksTest.Flaky(IconPacksTest.fetcher(f), null);
            RecipePacks rp = new RecipePacks(data);
            try (IconPacks p = packs(data, null, null, c1, rp)) {
                rp.request(V);
                assertTrue(p.await(V, 30_000));
                assertEquals("ready", rp.state(V));
            }
            assertEquals(1, c1.calls.get());
            // Recipes may not download and icons may: still one download, and both sets come out of it.
            Path data2 = tmp.resolve("b");
            IconPacksTest.Flaky c2 = new IconPacksTest.Flaky(IconPacksTest.fetcher(f), null);
            RecipePacks rp2 = new RecipePacks(data2);
            try (IconPacks p = packs(data2, null, c2, null, rp2)) {
                rp2.request(V);
                assertTrue(p.await(V, 30_000));
                assertEquals("ready", rp2.state(V));
            }
            assertEquals(1, c2.calls.get());
            // Neither may: no recipes, and nothing was fetched.
            Path data3 = tmp.resolve("c");
            RecipePacks rp3 = new RecipePacks(data3);
            try (IconPacks p = packs(data3, null, null, null, rp3)) {
                rp3.request(V);
                assertTrue(p.await(V, 30_000));
                assertEquals("none", rp3.state(V));
            }
        } finally {
            f.server().stop(0);
        }
    }

    @Test
    void aTransientRecipeFailureIsRetriedWithoutTouchingTheIcons() throws Exception {
        byte[] jar = RecipeFixtures.jar(true, png());
        IconPacksTest.Fake f = new IconPacksTest().fake(jar, IconPacksTest.sha1(jar), null);
        try {
            Path data = tmp.resolve("data");
            IconPacksTest.Flaky flaky = new IconPacksTest.Flaky(IconPacksTest.fetcher(f), new IOException("network down"));
            RecipePacks rp = new RecipePacks(data);
            try (IconPacks p = packs(data, null, flaky, flaky, rp, 200)) {
                rp.request(V);
                assertTrue(p.await(V, 30_000));
                for (int i = 0; i < 400 && !"ready".equals(rp.state(V)); i++) Thread.sleep(25);
                assertEquals("ready", rp.state(V));
                assertEquals(IconPacks.State.READY, p.state(V));
            }
            assertEquals(2, flaky.calls.get());
        } finally {
            f.server().stop(0);
        }
    }

    @Test
    void configParsesRecipeDownloadStrictly() {
        assertTrue(Config.parseRecipeDownload(null, true));
        assertFalse(Config.parseRecipeDownload(null, false));
        assertFalse(Config.parseRecipeDownload("  ", false));
        assertTrue(Config.parseRecipeDownload("true", false));
        assertFalse(Config.parseRecipeDownload("FALSE", true));
        assertFalse(Config.parseRecipeDownload("yes", true));
    }

    @Test
    void theConfigTemplateAndAnOlderConfigGetTheKey() throws Exception {
        Path d1 = tmp.resolve("c1");
        Config c = Config.load(d1);
        assertTrue(c.recipeDownload, "follows iconDownload=true");
        assertTrue(Files.readString(d1.resolve("config.properties")).contains("recipeDownload="));
        // An older file without the key keeps its other lines and gains the key once.
        Path d2 = tmp.resolve("c2");
        Config.load(d2);
        String text = Files.readString(d2.resolve("config.properties")).replace("recipeDownload=", "# removed=");
        text = text.replace("iconDownload=true", "iconDownload=false");
        Files.writeString(d2.resolve("config.properties"), text);
        Config old = Config.load(d2);
        assertFalse(old.iconDownload);
        assertFalse(old.recipeDownload, "the default follows iconDownload");
        String after = Files.readString(d2.resolve("config.properties"));
        assertTrue(after.contains("iconDownload=false"));
        assertEquals(1, after.split("\nrecipeDownload=", -1).length - 1);
        Config.load(d2);
        assertEquals(1, Files.readString(d2.resolve("config.properties")).split("\nrecipeDownload=", -1).length - 1, "not added twice");
        Files.writeString(d2.resolve("config.properties"), Files.readString(d2.resolve("config.properties")).replace("\nrecipeDownload=", "\nrecipeDownload=true"));
        assertTrue(Config.load(d2).recipeDownload, "set apart from iconDownload");
    }

    @Test
    void completeIconsStayReadyWhenTheRecipesCannotBeObtained() throws Exception {
        Path jar = writeJar("local.jar", true);
        Path data = tmp.resolve("data");
        try (IconPacks p = new IconPacks(data, jar, null, 64)) {
            p.request(V);
            assertTrue(p.await(V, 20_000));
        }
        Files.delete(jar); // no jar left, and downloading is off
        RecipePacks rp = new RecipePacks(data);
        try (IconPacks p = packs(data, jar, null, null, rp)) {
            rp.request(V);
            assertTrue(p.await(V, 20_000));
            assertEquals(IconPacks.State.READY, p.state(V), "the icon set is complete: the recipes' failure is not its failure");
            assertFalse(p.pending(V));
            assertEquals("none", rp.state(V));
        }
    }

    @Test
    void theIconStateStaysReadyWhileTheRecipesDownload() throws Exception {
        byte[] jar = RecipeFixtures.jar(true, png());
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        IconPacksTest.Fake f = new IconPacksTest().fake(jar, IconPacksTest.sha1(jar), gate);
        try {
            Path data = tmp.resolve("data");
            IconPacksTest.Flaky first = new IconPacksTest.Flaky(IconPacksTest.fetcher(f), null);
            gate.countDown();
            try (IconPacks p = new IconPacks(data, null, first, 64)) {
                p.request(V);
                assertTrue(p.await(V, 30_000));
            }
            java.util.concurrent.CountDownLatch gate2 = new java.util.concurrent.CountDownLatch(1);
            IconPacksTest.Fake slow = new IconPacksTest().fake(jar, IconPacksTest.sha1(jar), gate2);
            try {
                RecipePacks rp = new RecipePacks(data);
                IconPacksTest.Flaky second = new IconPacksTest.Flaky(IconPacksTest.fetcher(slow), null);
                try (IconPacks p = packs(data, null, null, second, rp)) {
                    rp.request(V);
                    for (int i = 0; i < 400 && p.state(V) != IconPacks.State.READY; i++) Thread.sleep(10);
                    assertEquals(IconPacks.State.READY, p.state(V));
                    for (int i = 0; i < 400 && second.calls.get() == 0; i++) Thread.sleep(10);
                    assertEquals("building", rp.state(V), "the recipe download is under way (held by the gate)");
                    assertEquals(IconPacks.State.READY, p.state(V));
                    assertFalse(p.pending(V), "the icon route must not answer pending while only recipes are fetched");
                    gate2.countDown();
                    assertTrue(rp.await(V, 30_000));
                    assertEquals("ready", rp.state(V));
                    assertEquals(IconPacks.State.READY, p.state(V));
                }
            } finally {
                gate2.countDown();
                slow.server().stop(0);
            }
        } finally {
            f.server().stop(0);
        }
    }

    @Test
    void aFailedRecipeFetchNeverFailsCompleteIcons() throws Exception {
        byte[] jar = RecipeFixtures.jar(true, png());
        IconPacksTest.Fake f = new IconPacksTest().fake(jar, "0".repeat(40), null); // wrong sha1: every download is refused
        try {
            Path data = tmp.resolve("data");
            Path local = writeJar("local.jar", true);
            try (IconPacks p = new IconPacks(data, local, null, 64)) {
                p.request(V);
                assertTrue(p.await(V, 20_000));
            }
            Files.delete(local);
            RecipePacks rp = new RecipePacks(data);
            try (IconPacks p = packs(data, null, null, IconPacksTest.fetcher(f), rp)) {
                rp.request(V);
                assertTrue(p.await(V, 20_000));
                assertEquals(IconPacks.State.READY, p.state(V));
                assertEquals("failed", rp.state(V));
            }
        } finally {
            f.server().stop(0);
        }
    }

    @Test
    void theRawFilesAreWrittenThroughTheAtomicWriter() throws Exception {
        Path jar = writeJar("local.jar", false);
        Path data = tmp.resolve("data");
        RecipePacks rp = new RecipePacks(data);
        try (IconPacks p = packs(data, jar, null, null, rp)) {
            p.iconsEnabled(false);
            rp.request(V);
            assertTrue(p.await(V, 20_000));
        }
        // No temp files are left behind by the atomic writes, and the files are there.
        try (var s = Files.walk(data.resolve("recipes"))) {
            assertTrue(s.noneMatch(x -> x.getFileName().toString().endsWith(".tmp")));
        }
        assertTrue(Files.isRegularFile(data.resolve("recipes/" + V + "/raw/recipe/spruce_planks.json")));
    }

    @Test
    void deepEntryNamesAreSkippedInTheJarAndTheRawWalkAlike() throws Exception {
        byte[] ok = "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:a\"],\"result\":{\"id\":\"minecraft:b\"}}".getBytes(StandardCharsets.UTF_8);
        Path jar = tmp.resolve("deep.jar");
        Files.write(jar, zip(z -> {
            put(z, "data/minecraft/recipe/ok.json", ok);
            put(z, "data/minecraft/recipe/a/b/c/d/e/f/g/x.json", ok); // 7 folders: the deepest accepted
            put(z, "data/minecraft/recipe/a/b/c/d/e/f/g/h/x.json", ok); // 8 folders: refused
        }));
        assertEquals(java.util.Set.of("ok", "a/b/c/d/e/f/g/x"), RecipePacks.readJar(jar).recipes().keySet());
    }

}
