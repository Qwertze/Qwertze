package com.porter.qwerte.craft;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Gets the addon a recipe table without a server (CRAFT-CONTRACT.md "Recipe tables for the bots"; nothing is shipped in the jar). Called from
 * whatever needs the table ({@code Crafter}, {@code StashManager}), cheap once there is one:
 * <ol>
 *     <li>the table cached in {@code meteor-client/qwerte/recipes-<mc>.json} (its hash is computed again and must be the stored one);</li>
 *     <li>else, once, a table built from the game's own jar ({@link RecipeBuilder}; its tableHash is the server's), on a thread of its own.</li>
 * </ol>
 * The Stash Manager server's table is fetched by {@code StashManager} and replaces whatever is held when the heartbeat reply says the server
 * has another. While a server is set and has not failed yet, {@link #allowLocal} is false so its table is waited for instead of built.
 * Thread-safe.
 */
public final class RecipeBoot {
    private RecipeBoot() {
    }

    private static final Logger LOG = LogUtils.getLogger();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qwerte-recipes");
        t.setDaemon(true);
        return t;
    });

    private static boolean cacheTried, localTried, localBuilding;
    private static volatile boolean localAllowed = true;

    /** Whether the table may be built from the game's own jar now (false while the server's table is being waited for). */
    public static void allowLocal(boolean on) {
        localAllowed = on;
    }

    /** The build from the game's own jar was started and is over (it gave a table or it did not). */
    public static synchronized boolean localDone() {
        return localTried && !localBuilding;
    }

    /** Loads the cached table, or starts building one from the game's jar. A no-op once there is a table or nothing more can be tried. */
    public static void bootstrap(String version) {
        bootstrap(version, RecipeStore.shared(), RecipeBuilder::fromGameJar, IO);
    }

    static synchronized void bootstrap(String version, RecipeStore store, Function<String, RecipeBuilder.Result> builder, Executor io) {
        if (Recipes.current() != null || !RecipeStore.validMc(version)) return;
        if (!cacheTried) {
            cacheTried = true;
            Recipes cached = store.load(version);
            if (cached != null) {
                Recipes.setIfAbsent(cached);
                LOG.info("[recipes] recipe table for {} loaded from the cache ({} recipes, hash {}).", version, cached.size(), cached.hash().substring(0, 8));
                return;
            }
        }
        if (!localAllowed || localTried) return;
        localTried = true;
        localBuilding = true;
        io.execute(() -> {
            RecipeBuilder.Result built = null;
            try {
                built = builder.apply(version);
            } catch (Throwable t) {
                LOG.warn("[recipes] building the recipe table for {} from the game's own data failed: {}: {}", version, t.getClass().getSimpleName(), t.getMessage());
            }
            finish(version, store, built);
        });
    }

    private static void finish(String version, RecipeStore store, RecipeBuilder.Result built) {
        try {
            if (built == null) {
                LOG.warn("[recipes] couldn't build the recipe table for {} from the game's own data", version);
                return;
            }
            RecipeStore.Checked checked = store.validate(version, built.table().toString(), null);
            Recipes table = checked.table();
            // Set only if there is still none (atomic): the server's table, which may have come meanwhile, always wins. Cached only when this one won,
            // so it never overwrites the server's cache file
            if (Recipes.setIfAbsent(table)) {
                store.persist(version, checked);
                LOG.info("[recipes] recipe table for {} built from the game's own data ({} recipes, hash {}).", version, table.size(), table.hash().substring(0, 8));
            }
        } catch (RecipeStore.Invalid e) {
            LOG.warn("[recipes] the recipe table built from the game's own data was refused: {}", e.getMessage());
        } finally {
            synchronized (RecipeBoot.class) {
                localBuilding = false;
            }
        }
    }

    /** For tests: forgets what was tried. */
    static synchronized void reset() {
        cacheTried = localTried = localBuilding = false;
        localAllowed = true;
    }
}
