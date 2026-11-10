package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kit v2 pieces below the routes (KIT-CONTRACT-v2): the type table and its fingerprint, the classifier against the
 * contract's 39 vectors, the shipped presets, the template and settings hash vectors, and the settings store.
 */
class KitV2Test {
    @TempDir
    Path dir;

    static JsonObject resource(String name) throws Exception {
        try (InputStream in = KitV2Test.class.getResourceAsStream("/kits/" + name)) {
            assertNotNull(in, name);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void theTableHasTheContractsFingerprint() {
        KitTypes t = KitTypes.shipped();
        assertNotNull(t);
        assertEquals(KitTypes.FINGERPRINT, t.fingerprint());
        assertEquals(KitTypes.IDS, t.types().stream().map(KitTypes.Type::id).toList());
        assertEquals(List.of("pvp"), t.typesOf("minecraft:totem_of_undying"));
        assertEquals(List.of("travel", "pvp"), t.typesOf("minecraft:ender_pearl"));
        assertEquals(List.of("building"), t.typesOf("minecraft:cyan_concrete_powder"), "a suffix");
        assertEquals(List.of(), t.typesOf("minecraft:cobblestone"), "stock material");
        assertEquals(List.of(), t.typesOf("othermod:totem_of_undying"), "only the minecraft namespace takes suffixes and prefixes");
    }

    @Test
    void aTableThatIsNotTheSevenTypesIsRefused() throws Exception {
        String json = new String(KitV2Test.class.getResourceAsStream("/kits/kit-types.json").readAllBytes(), StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> KitTypes.parse(json.replace("\"id\": \"pvp\"", "\"id\": \"pvp2\"")));
    }

    @Test
    void theClassifierGivesTheContractsVectors() throws Exception {
        KitTypes table = KitTypes.shipped();
        JsonObject root = resource("kit-type-vectors.json");
        assertEquals(KitTypes.FINGERPRINT, root.get("table").getAsString());
        JsonArray vectors = root.getAsJsonArray("vectors");
        assertEquals(39, vectors.size());
        for (JsonElement ve : vectors) {
            JsonObject v = ve.getAsJsonObject();
            String label = v.get("label").getAsString();
            List<String> slots = new ArrayList<>();
            v.getAsJsonArray("slots").forEach(s -> slots.add(s.getAsString()));
            Map<String, String> treat = new TreeMap<>();
            v.getAsJsonObject("treatAs").entrySet().forEach(e -> treat.put(e.getKey(), e.getValue().getAsString()));
            String name = v.get("name").isJsonNull() ? null : v.get("name").getAsString();
            KitClassifier.Result r = KitClassifier.classify(table, slots, name, treat);
            JsonObject x = v.getAsJsonObject("expect");
            assertEquals(x.get("kit").getAsBoolean(), r.kit(), label + " kit");
            assertEquals(x.get("code").getAsString(), r.code(), label + " code");
            assertEquals(x.get("type").isJsonNull() ? null : x.get("type").getAsString(), r.type(), label + " type");
            assertEquals(x.get("slots").getAsInt(), r.slots(), label + " slots");
            if (x.has("covered")) assertEquals(x.get("covered").getAsInt(), r.covered(), label + " covered");
            if (x.has("kinds")) assertEquals(x.get("kinds").getAsInt(), r.kinds(), label + " kinds");
            if (x.has("need")) assertEquals(x.get("need").getAsInt(), r.need(), label + " need");
            if (x.has("minKinds")) assertEquals(x.get("minKinds").getAsInt(), r.minKinds(), label + " minKinds");
            if (x.has("items")) {
                List<String> items = new ArrayList<>();
                x.getAsJsonArray("items").forEach(i -> items.add(i.getAsString()));
                assertEquals(items, r.items(), label + " items");
            }
        }
    }

    @Test
    void eachPresetClassifiesAsItsOwnCategory() throws Exception {
        JsonObject root = resource("kit-presets.json");
        JsonArray presets = root.getAsJsonArray("presets");
        assertEquals(9, presets.size());
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (JsonElement pe : presets) {
            JsonObject p = pe.getAsJsonObject();
            String id = p.get("id").getAsString();
            assertTrue(ids.add(id), "unique preset id " + id);
            String category = p.get("category").getAsString();
            assertTrue(KitTypes.isType(category), id);
            List<String> slots = new ArrayList<>();
            JsonArray raw = p.getAsJsonArray("slots");
            assertEquals(27, raw.size(), id);
            for (JsonElement s : raw) {
                if (s.isJsonNull()) continue;
                String key = s.getAsJsonObject().get("key").getAsString();
                slots.add(key.indexOf('#') < 0 ? key : key.substring(0, key.indexOf('#')));
            }
            KitClassifier.Result r = KitClassifier.classify(KitTypes.shipped(), slots, KitNames.normalize(p.get("name").getAsString()), Map.of());
            assertTrue(r.kit(), id + " is a kit: " + r.code());
            assertEquals(category, r.type(), id);
            // A preset is also a valid template: loading it into the editor and saving must work.
            KitTemplate.Template t = KitTemplate.parse(templateBody(p), "t-0000test", new NoItems());
            assertEquals(category, t.category());
            assertEquals(27, t.slots().size());
        }
    }

    static JsonObject templateBody(JsonObject preset) {
        JsonObject t = new JsonObject();
        t.addProperty("name", preset.get("name").getAsString());
        t.addProperty("category", preset.get("category").getAsString());
        t.add("color", preset.get("color"));
        t.add("slots", preset.get("slots"));
        return t;
    }

    static final class NoItems implements KitTemplate.Items {
        @Override
        public String matchOf(String key) {
            return null;
        }

        @Override
        public int maxStack(String key) {
            return 0;
        }
    }

    static KitTemplate.Template fromVector(JsonObject t) {
        List<KitTemplate.Slot> slots = new ArrayList<>();
        for (JsonElement s : t.getAsJsonArray("slots")) {
            if (s.isJsonNull()) {
                slots.add(null);
                continue;
            }
            JsonObject so = s.getAsJsonObject();
            String key = so.get("key").getAsString();
            slots.add(new KitTemplate.Slot(key, so.has("match") ? so.get("match").getAsString() : key, so.get("count").getAsInt()));
        }
        return new KitTemplate.Template(t.get("id").getAsString(), t.get("name").getAsString(), t.get("category").getAsString(),
            t.has("chest") && !t.get("chest").isJsonNull() ? t.get("chest").getAsString() : null,
            t.has("color") && !t.get("color").isJsonNull() ? t.get("color").getAsString() : null,
            t.has("keep") ? t.get("keep").getAsInt() : 0, slots);
    }

    @Test
    void templateAndSettingsHashVectors() throws Exception {
        JsonObject root = resource("kit-template-vectors.json");
        assertEquals(2, root.get("version").getAsInt());
        Map<String, KitTemplate.Template> byId = new TreeMap<>();
        for (JsonElement he : root.getAsJsonArray("hash")) {
            JsonObject h = he.getAsJsonObject();
            KitTemplate.Template t = fromVector(h.getAsJsonObject("template"));
            assertEquals(h.get("canonical").getAsString(), t.canonical(), t.id());
            assertEquals(h.get("sha1").getAsString(), t.hash(), t.id());
            byId.put(t.id(), t);
        }
        assertEquals("d9cf8cbc680b6b9dccdc308801a082f2b77277c2", byId.get("t-0000mini").hash());
        assertEquals("a46f739d7d37be27edbc8ac9527a15d67b9cb4e2", byId.get("t-0000food").hash());
        JsonObject sh = root.getAsJsonObject("settingsHash");
        KitStore.Types types = KitStore.Types.empty();
        types.chests().put("pvp", "118,64,-30");
        types.chests().put("building", "121,64,-33");
        types.chests().put("c:shop", "125,64,-33");
        types.categories().put("c:shop", "Shop kits");
        types.treatAs().put("crystal kit", "none");
        types.treatAs().put("etc", "building");
        types.templates().putAll(byId);
        assertEquals("a111900f6ee3ecdbca49f5acf4fd9debda0210f8", KitStore.hash2(sh.get("v1").getAsString(), types));
        assertEquals(sh.get("v2").getAsString(), KitStore.hash2(sh.get("v1").getAsString(), types));
        // The v1 hash of the contract's own example, and the empty settings.
        assertEquals("51f1510ce6282be5d046b67bf54619d4bdec7dd3", KitStore.hash("120,64,-33", KitStore.Scoring.DEFAULT, Map.of("pvp kit", "118,64,-33")));
        assertEquals("ae46bc461e485b4ad61f4cf81983e22c21d070d0", KitStore.hash2(KitStore.hash(null, KitStore.Scoring.DEFAULT, Map.of()), KitStore.Types.empty()));
    }

    // ---------- the store ----------

    KitStore open() {
        KitStore s = new KitStore(dir, () -> 1000L);
        s.load();
        return s;
    }

    static JsonObject body(String name, String category, String... keys) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("category", category);
        JsonArray slots = new JsonArray();
        for (int i = 0; i < 27; i++) {
            if (i < keys.length) {
                JsonObject s = new JsonObject();
                s.addProperty("key", keys[i]);
                s.addProperty("count", 1);
                slots.add(s);
            } else {
                slots.add(com.google.gson.JsonNull.INSTANCE);
            }
        }
        t.add("slots", slots);
        return t;
    }

    KitStore.Settings put(KitStore s, String id, JsonObject body, String[] out) throws Exception {
        return s.putTemplate("srv", "st", id, useId -> KitTemplate.parse(body, useId, new NoItems()), out);
    }

    @Test
    void theV2SettingsFollowTheContract() throws Exception {
        KitStore s = open();
        KitStore.Settings g = s.get("srv", "st");
        assertEquals("ae46bc461e485b4ad61f4cf81983e22c21d070d0", g.types().hash());
        assertEquals("cf9dcdb21f6fc7551bde30826e49358bd74d06ef", g.hash(), "the v1 hash never covers types");
        // Type chests, overrides and categories each bump the rev and the v2 hash only.
        KitStore.Settings a = s.setTypeChest("srv", "st", "pvp", "118,64,-30");
        assertEquals(1, a.rev());
        assertEquals("cf9dcdb21f6fc7551bde30826e49358bd74d06ef", a.hash());
        assertFalse(a.types().hash().equals(g.types().hash()));
        assertEquals(1, s.setTypeChest("srv", "st", "pvp", "118,64,-30").rev(), "no change, no write");
        assertThrows(KitStore.LimitException.class, () -> s.setTypeChest("srv", "st", "bogus", "1,2,3"));
        assertEquals(2, s.setTreatAs("srv", "st", "etc", "building").rev());
        assertThrows(KitStore.LimitException.class, () -> s.setTreatAs("srv", "st", "etc", "c:nope"));
        String[] id = new String[1];
        KitStore.Settings c = s.putCategory("srv", "st", null, "Shop kits", id);
        assertEquals("c:shop-kits", id[0]);
        assertEquals("Shop kits", c.types().categories().get("c:shop-kits"));
        assertEquals(c.rev(), s.putCategory("srv", "st", "c:shop-kits", "Shop kits", id).rev());
        s.setTypeChest("srv", "st", "c:shop-kits", "125,64,-33");
        s.setTreatAs("srv", "st", "shop", "c:shop-kits");
        assertEquals(409, assertThrows(KitStore.RefusedException.class, () -> s.deleteCategory("srv", "st", "c:shop-kits")).status);
        s.setTreatAs("srv", "st", "shop", null);
        KitStore.Settings d = s.deleteCategory("srv", "st", "c:shop-kits");
        assertFalse(d.types().chests().containsKey("c:shop-kits"), "its chest goes with it");
        // Settings JSON for the bots: types object with the hash, chests, treatAs, categories, templates.
        JsonObject j = d.toJson().getAsJsonObject("types");
        assertEquals(d.types().hash(), j.get("hash").getAsString());
        assertEquals("118,64,-30", j.getAsJsonObject("chests").get("pvp").getAsString());
        assertEquals("building", j.getAsJsonObject("treatAs").get("etc").getAsString());
        // Round trip through the file.
        KitStore again = open();
        assertEquals(d.rev(), again.get("srv", "st").rev());
        assertEquals(d.types().hash(), again.get("srv", "st").types().hash());
    }

    @Test
    void aSlotsMatchKeyMustBeOfTheSameItem() {
        JsonObject b = body("Match", "pvp", "minecraft:bread", "minecraft:bow#0123abcd");
        b.getAsJsonArray("slots").get(1).getAsJsonObject().addProperty("match", "minecraft:bread");
        assertThrows(IllegalArgumentException.class, () -> KitTemplate.parse(b, "t-1", new NoItems()));
        JsonObject prefix = body("Match", "pvp", "minecraft:bread", "minecraft:bow#0123abcd");
        prefix.getAsJsonArray("slots").get(1).getAsJsonObject().addProperty("match", "minecraft:bowl#99887766");
        assertThrows(IllegalArgumentException.class, () -> KitTemplate.parse(prefix, "t-1", new NoItems()), "a longer id that merely starts with the item's");
        JsonObject plain = body("Match", "pvp", "minecraft:bread", "minecraft:bow#0123abcd");
        plain.getAsJsonArray("slots").get(1).getAsJsonObject().addProperty("match", "minecraft:bow");
        assertEquals("minecraft:bow", KitTemplate.parse(plain, "t-1", new NoItems()).slots().get(1).match(), "the plain id is a match key too");
    }

    @Test
    void theWordPatternsAreCompiledOnce() {
        KitClassifier.classify(KitTypes.shipped(), List.of("minecraft:totem_of_undying", "minecraft:end_crystal"), "crystal kit", Map.of());
        int after = KitClassifier.cachedPatterns();
        assertTrue(after > 0);
        KitClassifier.classify(KitTypes.shipped(), List.of("minecraft:totem_of_undying", "minecraft:end_crystal"), "crystal kit", Map.of());
        assertEquals(after, KitClassifier.cachedPatterns());
    }

    @Test
    void overridesAreCappedAt200() throws Exception {
        KitStore s = open();
        for (int i = 0; i < KitStore.MAX_TREAT_AS; i++) s.setTreatAs("srv", "st", "name " + i, i % 2 == 0 ? "other" : "none");
        assertEquals("at most 200 kit overrides", assertThrows(KitStore.LimitException.class, () -> s.setTreatAs("srv", "st", "one more", "other")).getMessage());
        assertEquals(KitStore.MAX_TREAT_AS + 0, s.get("srv", "st").types().treatAs().size());
        // Changing an existing one is not a new one.
        s.setTreatAs("srv", "st", "name 0", "building");
        s.setTreatAs("srv", "st", "name 1", null);
        s.setTreatAs("srv", "st", "one more", "other");
    }

    @Test
    void categoriesAreCappedAndNamed() throws Exception {
        KitStore s = open();
        for (int i = 0; i < KitTemplate.MAX_CATEGORIES; i++) s.putCategory("srv", "st", null, "Cat " + i, null);
        assertThrows(KitStore.LimitException.class, () -> s.putCategory("srv", "st", null, "One more", null));
        assertThrows(KitStore.RefusedException.class, () -> s.putCategory("srv", "st", "c:ghost", "x", null));
        assertThrows(IllegalArgumentException.class, () -> KitTemplate.checkName("bad§name", 32, "name"));
        assertThrows(IllegalArgumentException.class, () -> KitTemplate.checkName("a".repeat(33), 32, "name"));
        assertThrows(IllegalArgumentException.class, () -> KitTemplate.checkName("  ", 32, "name"));
        assertEquals("c:cat-1-2", KitTemplate.slug("Cat 1", new java.util.TreeSet<>(List.of("c:cat-1"))));
    }

    @Test
    void templatesAreCheckedAndStored() throws Exception {
        KitStore s = open();
        String[] id = new String[1];
        KitStore.Settings a = put(s, null, body("PvP Kit", "pvp", "minecraft:totem_of_undying", "minecraft:end_crystal"), id);
        assertTrue(KitTemplate.ID.matcher(id[0]).matches(), id[0]);
        assertTrue(id[0].matches("^t-[0-9a-f]{8}$"), "made ids are t- and 8 hex digits");
        assertEquals(1, a.types().templates().size());
        // The same contents or the same name (normalised) are refused.
        assertEquals("same contents as PvP Kit", assertThrows(KitStore.LimitException.class,
            () -> put(s, null, body("Other", "pvp", "minecraft:end_crystal", "minecraft:totem_of_undying"), null)).getMessage());
        assertTrue(assertThrows(KitStore.LimitException.class,
            () -> put(s, null, body("  pvp   KIT ", "pvp", "minecraft:bread", "minecraft:apple"), null)).getMessage().contains("already called"));
        // Replace keeps the id; a replace of an unknown id is a 404; an unknown category is a 400.
        String same = id[0];
        put(s, same, body("PvP Kit", "pvp", "minecraft:totem_of_undying", "minecraft:end_crystal", "minecraft:obsidian"), id);
        assertEquals(same, id[0]);
        assertEquals(404, assertThrows(KitStore.RefusedException.class, () -> put(s, "t-0000none", body("x", "pvp", "minecraft:a", "minecraft:b"), null)).status);
        assertThrows(KitStore.LimitException.class, () -> put(s, null, body("Bad", "c:ghost", "minecraft:bread", "minecraft:apple"), null));
        // Shape checks.
        assertTrue(assertThrows(IllegalArgumentException.class, () -> KitTemplate.parse(body("One", "pvp", "minecraft:bread"), "t-1", new NoItems())).getMessage()
            .contains("at least two kinds"));
        assertThrows(IllegalArgumentException.class, () -> KitTemplate.parse(body("Box", "pvp", "minecraft:bread", "minecraft:shulker_box"), "t-1", new NoItems()));
        assertThrows(IllegalArgumentException.class, () -> KitTemplate.parse(body("Key", "pvp", "minecraft:bread", "Not A Key"), "t-1", new NoItems()));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> KitTemplate.parse(body("Variant", "pvp", "minecraft:bread", "minecraft:bow#0123abcd"), "t-1", new NoItems()))
            .getMessage().contains("isn't in the stash index"));
        JsonObject big = body("Big", "pvp", "minecraft:bread", "minecraft:apple");
        big.getAsJsonArray("slots").get(0).getAsJsonObject().addProperty("count", 99);
        assertThrows(IllegalArgumentException.class, () -> KitTemplate.parse(big, "t-1", new NoItems()));
        JsonObject few = body("Few", "pvp", "minecraft:bread", "minecraft:apple");
        few.getAsJsonArray("slots").remove(26);
        assertThrows(IllegalArgumentException.class, () -> KitTemplate.parse(few, "t-1", new NoItems()));
        // An exact variant takes its match from the body when the index has none (an import).
        JsonObject imp = body("Import", "pvp", "minecraft:bread", "minecraft:bow#0123abcd");
        imp.getAsJsonArray("slots").get(1).getAsJsonObject().addProperty("match", "minecraft:bow#99887766");
        assertEquals("minecraft:bow#99887766", KitTemplate.parse(imp, "t-1", new NoItems()).slots().get(1).match());
        // Delete is idempotent; a deleted template's category can be deleted afterwards.
        assertEquals(0, s.deleteTemplate("srv", "st", same).types().templates().size());
        assertEquals(0, s.deleteTemplate("srv", "st", same).types().templates().size());
    }

    @Test
    void atMost50TemplatesAndTheVariantsOfATemplateIdGrammar() throws Exception {
        KitStore s = open();
        for (int i = 0; i < KitTemplate.MAX_TEMPLATES; i++) put(s, null, body("Kit " + i, "building", "minecraft:white_concrete", "minecraft:oak_planks", "minecraft:stone_" + i), null);
        assertEquals("at most 50 kits", assertThrows(KitStore.LimitException.class,
            () -> put(s, null, body("Kit extra", "building", "minecraft:white_concrete", "minecraft:glass"), null)).getMessage());
        // The contract's own vectors use ids like t-0000mini; the grammar takes them.
        assertTrue(KitTemplate.ID.matcher("t-0000mini").matches());
        assertTrue(KitTemplate.ID.matcher("t-3f2a9c1d").matches());
        assertFalse(KitTemplate.ID.matcher("t-3F2A9C1D").matches());
        assertFalse(KitTemplate.ID.matcher("t-3f2a9c1").matches());
        // A new id never collides with one that is taken.
        assertFalse(KitTemplate.newId(java.util.Set.of("t-00000000")).equals("t-00000000"));
    }

    @Test
    void aDamagedTemplateMakesTheStashUnreadableButNotTheOthers() throws Exception {
        KitStore s = open();
        s.putCategory("srv", "st", null, "Shop", null);
        s.setChest("srv", "other", "1,2,3");
        String raw = Files.readString(dir.resolve("kits.json"), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("kits.json"), raw.replace("\"c:shop\"", "\"shop\""), StandardCharsets.UTF_8);
        KitStore again = open();
        assertEquals("1,2,3", again.get("srv", "other").kitsChest());
        assertThrows(KitStore.DisabledException.class, () -> again.get("srv", "st"));
    }
}
