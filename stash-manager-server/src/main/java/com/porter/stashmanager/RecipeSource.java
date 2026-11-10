package com.porter.stashmanager;

/**
 * Where the routes get a version's crafting recipes from: {@link RecipePacks} on a real server, a fake in tests.
 * Both calls are cheap and never block on a build.
 */
interface RecipeSource {
    /** The version's table when its set is ready, else null. */
    RecipeTable table(String mcVersion);

    /** "ready", "building", "failed" or "none" (nothing built and nothing building, for example downloading is off). */
    String state(String mcVersion);

    /** Makes sure the version's set is checked or built (once per process, in the background). */
    void request(String mcVersion);

    /** No recipes at all (older tests and servers started without the recipe pass). */
    RecipeSource NONE = new RecipeSource() {
        @Override
        public RecipeTable table(String mcVersion) {
            return null;
        }

        @Override
        public String state(String mcVersion) {
            return "none";
        }

        @Override
        public void request(String mcVersion) {
        }
    };
}
