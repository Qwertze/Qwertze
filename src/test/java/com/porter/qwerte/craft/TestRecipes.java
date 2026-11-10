package com.porter.qwerte.craft;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The small recipe table the tests craft by ({@code src/test/resources/recipes-test.json}: 26 hand-picked crafting recipes in the shape the
 * server sends, enough for shulker boxes, chests, planks, stairs, slabs, fences, ladders, iron bars and beds; the same fixture the plugin's
 * tests use). The real table is fetched from the Stash Manager server (or built from the game) at run time and is not part of the repository.
 */
public final class TestRecipes {
    private TestRecipes() {
    }

    private static Recipes table;

    public static synchronized Recipes table() {
        if (table == null) table = Recipes.parse(json());
        return table;
    }

    public static String json() {
        try (InputStream in = TestRecipes.class.getResourceAsStream("/recipes-test.json")) {
            if (in == null) throw new IllegalStateException("recipes-test.json is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
