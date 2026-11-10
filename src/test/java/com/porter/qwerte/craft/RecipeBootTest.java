package com.porter.qwerte.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Where the addon's table comes from without a server: the cache, else the game's own data once (and never when the server's is being waited for). */
class RecipeBootTest {
    private Recipes before;

    @BeforeEach
    void setUp() {
        before = Recipes.current();
        Recipes.setCurrent(null);
        RecipeBoot.reset();
    }

    @AfterEach
    void tearDown() {
        Recipes.setCurrent(before);
        RecipeBoot.reset();
    }

    private static RecipeBuilder.Result built(String version) {
        JsonObject recipe = JsonParser.parseString("{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:a\"],\"result\":{\"id\":\"minecraft:b\"}}").getAsJsonObject();
        return RecipeBuilder.build(version, Map.of("b", recipe), Map.of(), Set.of());
    }

    @Test
    void theCachedTableIsLoadedAndNothingIsBuilt(@TempDir Path dir) throws Exception {
        RecipeStore store = new RecipeStore(dir);
        store.accept("1.21.4", TestRecipes.json(), null);
        AtomicInteger builds = new AtomicInteger();
        RecipeBoot.bootstrap("1.21.4", store, v -> {
            builds.incrementAndGet();
            return built(v);
        }, Runnable::run);
        assertNotNull(Recipes.current());
        assertEquals(TestRecipes.table().hash(), Recipes.current().hash());
        assertEquals(0, builds.get());
    }

    @Test
    void withNoCacheTheGamesDataIsBuiltOnceCachedAndUsed(@TempDir Path dir) {
        RecipeStore store = new RecipeStore(dir);
        AtomicInteger builds = new AtomicInteger();
        RecipeBoot.bootstrap("1.21.4", store, v -> {
            builds.incrementAndGet();
            return built(v);
        }, Runnable::run);
        assertNotNull(Recipes.current());
        assertEquals(1, Recipes.current().size());
        assertTrue(RecipeBoot.localDone());
        // Cached for the next start
        assertNotNull(store.load("1.21.4"));
        // Asked again: nothing more is built
        Recipes.setCurrent(null);
        RecipeBoot.bootstrap("1.21.4", store, v -> {
            builds.incrementAndGet();
            return built(v);
        }, Runnable::run);
        assertEquals(1, builds.get());
    }

    @Test
    void whileTheServersTableIsWaitedForNothingIsBuilt(@TempDir Path dir) {
        RecipeStore store = new RecipeStore(dir);
        AtomicInteger builds = new AtomicInteger();
        RecipeBoot.allowLocal(false);
        RecipeBoot.bootstrap("1.21.4", store, v -> {
            builds.incrementAndGet();
            return built(v);
        }, Runnable::run);
        assertNull(Recipes.current());
        assertEquals(0, builds.get());
        assertFalse(RecipeBoot.localDone());
        // The server failed: now it may be
        RecipeBoot.allowLocal(true);
        RecipeBoot.bootstrap("1.21.4", store, v -> {
            builds.incrementAndGet();
            return built(v);
        }, Runnable::run);
        assertNotNull(Recipes.current());
        assertEquals(1, builds.get());
    }

    @Test
    void aBuildThatFailsLeavesNoTableAndIsNotRepeated(@TempDir Path dir) {
        RecipeStore store = new RecipeStore(dir);
        AtomicInteger builds = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            RecipeBoot.bootstrap("1.21.4", store, v -> {
                builds.incrementAndGet();
                return null;
            }, Runnable::run);
        }
        assertNull(Recipes.current());
        assertEquals(1, builds.get());
        assertTrue(RecipeBoot.localDone());
    }

    @Test
    void theServersTableThatCameMeanwhileIsNotReplacedByTheLocalOne(@TempDir Path dir) throws Exception {
        RecipeStore store = new RecipeStore(dir);
        Recipes server = TestRecipes.table();
        // The build runs later (on its own thread): the server's table is set before it finishes
        Runnable[] later = new Runnable[1];
        RecipeBoot.bootstrap("1.21.4", store, RecipeBootTest::built, r -> later[0] = r);
        Recipes.setCurrent(server);
        later[0].run();
        assertSame(server, Recipes.current());
        assertTrue(RecipeBoot.localDone());
    }

    @Test
    void aBadVersionIsIgnored(@TempDir Path dir) {
        RecipeBoot.bootstrap("../x", new RecipeStore(dir), v -> built(v), Runnable::run);
        assertNull(Recipes.current());
        assertFalse(RecipeBoot.localDone());
    }

    @Test
    void theLocalTableIsNotCachedOverTheServersWhenTheServerWon(@TempDir Path dir) throws Exception {
        RecipeStore store = new RecipeStore(dir);
        store.accept("1.21.4", TestRecipes.json(), null);
        Recipes server = TestRecipes.table();
        Runnable[] later = new Runnable[1];
        // The cache has the server's table, but is ignored here: pretend nothing was cached by deleting it first
        java.nio.file.Files.delete(store.file("1.21.4"));
        RecipeBoot.bootstrap("1.21.4", store, RecipeBootTest::built, r -> later[0] = r);
        Recipes.setCurrent(server);
        store.persist("1.21.4", store.validate("1.21.4", TestRecipes.json(), null));
        later[0].run();
        assertSame(server, Recipes.current());
        assertEquals(server.hash(), store.load("1.21.4").hash(), "the server's cached table was not overwritten");
    }

    @Test
    void setIfAbsentIsACompareAndSet() {
        assertTrue(Recipes.setIfAbsent(TestRecipes.table()));
        assertFalse(Recipes.setIfAbsent(Recipes.empty()));
        assertSame(TestRecipes.table(), Recipes.current());
    }
}
