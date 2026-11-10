package com.porter.qwerte.manager.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.stash.KitTypes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Kit Builder apart from the widgets: the server's list, the draft and its live checks, the body of a save, the words and "Import local kits". */
class BuilderModelTest {
    private static final String ANSWER = """
        {"server":"2b2t.org","stash":"Main","rev":4,
         "templates":[
           {"id":"t-0000mini","name":"Mini Crystal","category":"pvp","chest":"118,64,-30","color":"red","keep":3,
            "slots":[{"key":"minecraft:totem_of_undying","match":"minecraft:totem_of_undying","count":1,"resolved":true},
                     {"key":"minecraft:end_crystal","match":"minecraft:end_crystal","count":64,"resolved":true},
                     {"key":"minecraft:netherite_sword#0123456789ab","match":"minecraft:netherite_sword#0123456789ab","count":1,"resolved":false},
                     null],
            "effectiveChest":"118,64,-30","chestSource":"template","effectiveChestStatus":"ok"}],
         "categories":[{"id":"c:shop","name":"Shop kits"}],
         "presets":[{"id":"p-crystal","category":"pvp","name":"Crystal PvP","color":null,"slots":[{"key":"minecraft:end_crystal","count":64},{"key":"minecraft:obsidian#*","count":64}]}],
         "capability":{"kitOrders":true,"restock":false,"craft":false,"import":true},
         "stock":{"t-0000mini":{"complete":2,"incomplete":1,"elsewhere":3,"carried":0,"missing":[{"match":"minecraft:end_crystal","key":"minecraft:end_crystal","count":12}]}},
         "restock":{"state":"waiting","template":"t-0000mini","reason":"topping_up"},
         "limits":{"maxTemplates":50,"maxCategories":16}}
        """;

    private static BuilderModel.Data data() {
        return BuilderModel.parse(JsonParser.parseString(ANSWER));
    }

    @Test
    void theServersListIsReadAndChecked() {
        BuilderModel.Data d = data();
        assertNotNull(d);
        assertEquals(1, d.templates().size());
        BuilderModel.Template t = d.templates().get(0);
        assertEquals("Mini Crystal", t.name());
        assertEquals(27, t.slots().size(), "always 27 slots");
        assertEquals(3, t.keep());
        assertEquals("118,64,-30", t.effectiveChest());
        assertFalse(t.slots().get(2).resolved(), "a variant the index lost");
        assertTrue(t.slots().get(0).resolved());
        assertEquals(3, t.kinds());
        assertEquals("Shop kits", d.categoryName("c:shop"));
        assertEquals("PvP / Crystal", d.categoryName("pvp"));
        assertTrue(d.capability().importer());
        assertEquals(12, d.stock("t-0000mini").missing().get(0).count());
        assertEquals(0, d.stock("t-nothere0").complete(), "no stock is zero");
        // A template with a bad id, a bad category or a bad slot key is dropped or emptied
        BuilderModel.Data odd = BuilderModel.parse(JsonParser.parseString("{\"templates\":[{\"id\":\"nope\",\"name\":\"x\",\"category\":\"pvp\",\"slots\":[]},"
            + "{\"id\":\"t-0000abcd\",\"name\":\"y\",\"category\":\"bad cat\",\"slots\":[]},{\"id\":\"t-0000abce\",\"name\":\"z\",\"category\":\"pvp\",\"slots\":[{\"key\":\"x y\",\"count\":1}]}]}"));
        assertEquals(1, odd.templates().size());
        assertNull(odd.templates().get(0).slots().get(0), "a slot with a key that is not an item key is empty");
        assertNull(BuilderModel.parse(JsonParser.parseString("[]")));
    }

    private static BuilderModel.Draft draft(String name, String... keyCount) {
        BuilderModel.Draft d = new BuilderModel.Draft();
        d.name = name;
        for (int i = 0; i < keyCount.length; i += 2) d.slots[i / 2] = new BuilderModel.EditSlot(keyCount[i], Integer.parseInt(keyCount[i + 1]));
        return d;
    }

    @Test
    void theLiveChecksNameKindsCountsAndSameContents() {
        BuilderModel.Data data = data();
        java.util.function.Function<String, Integer> stack = id -> id.contains("sword") ? 1 : 64;
        assertTrue(BuilderModel.problems(draft("New Kit", "minecraft:totem_of_undying", "1", "minecraft:end_crystal", "64"), data.templates(), stack).isEmpty());
        assertTrue(BuilderModel.problems(draft("", "minecraft:totem_of_undying", "1", "minecraft:end_crystal", "64"), data.templates(), stack).get(0).contains("name"));
        assertTrue(BuilderModel.problems(draft("one", "minecraft:totem_of_undying", "1", "minecraft:totem_of_undying", "1"), data.templates(), stack).stream().anyMatch(p -> p.contains("two kinds")));
        assertTrue(BuilderModel.problems(draft("big", "minecraft:totem_of_undying", "1", "minecraft:netherite_sword", "5"), data.templates(), stack).stream().anyMatch(p -> p.contains("holds 1 to 1")));
        // The name must be unique by its normalised form, except for the kit being edited
        assertEquals("Another kit already has that name.", BuilderModel.nameProblem("MINI crystal", null, data.templates()));
        assertNull(BuilderModel.nameProblem("MINI crystal", "t-0000mini", data.templates()));
        assertNotNull(BuilderModel.nameProblem("a§b", null, List.of()));
        assertNotNull(BuilderModel.nameProblem("x".repeat(51), null, List.of()));
        // The same items as a stored template, whatever the slot order
        BuilderModel.Draft same = draft("Copy", "minecraft:end_crystal", "64", "minecraft:netherite_sword#0123456789ab", "1", "minecraft:totem_of_undying", "1");
        assertTrue(BuilderModel.problems(same, data.templates(), stack).stream().anyMatch(p -> p.startsWith("Same contents as Mini Crystal")));
    }

    @Test
    void whatCanBeBuiltNowAndWhatIsShort() {
        BuilderModel.Draft d = draft("K", "minecraft:totem_of_undying", "1", "minecraft:end_crystal", "64", "minecraft:obsidian#*", "32");
        // 3 totems, 100 crystals, obsidian of two variants 20 + 30
        BuilderModel.Builds b = BuilderModel.builds(d, key -> key.equals("minecraft:totem_of_undying") ? 3 : key.equals("minecraft:end_crystal") ? 100 : 0, base -> 50);
        assertEquals(1, b.now(), "50 obsidian makes one kit of 32; crystals make one of 64");
        BuilderModel.Builds none = BuilderModel.builds(d, key -> key.contains("totem") ? 3 : 10, base -> 50);
        assertEquals(0, none.now());
        assertEquals("minecraft:end_crystal", none.shortKeys().get(0).key());
        assertEquals(54, none.shortKeys().get(0).count());
        assertTrue(BuilderModel.buildsText(none, id -> id.substring(id.indexOf(':') + 1)).startsWith("Builds now: 0 (short: end_crystal ×54"));
        assertEquals(0, BuilderModel.builds(new BuilderModel.Draft(), k -> 5, b2 -> 5).now(), "an empty draft builds nothing");
    }

    @Test
    void theBodyOfASaveHas27SlotsAndNullsForNoChestOrColour() {
        BuilderModel.Draft d = draft(" Kit ", "minecraft:totem_of_undying", "1", "minecraft:end_crystal#*", "64");
        d.category = "pvp";
        JsonObject body = BuilderModel.saveBody("srv", "Main", d);
        JsonObject t = body.getAsJsonObject("template");
        assertFalse(t.has("id"), "a new kit has no id");
        assertEquals("Kit", t.get("name").getAsString(), "trimmed");
        assertTrue(t.get("chest").isJsonNull());
        assertTrue(t.get("color").isJsonNull());
        assertEquals(0, t.get("keep").getAsInt());
        assertEquals(27, t.getAsJsonArray("slots").size());
        assertEquals("minecraft:end_crystal#*", t.getAsJsonArray("slots").get(1).getAsJsonObject().get("key").getAsString());
        assertTrue(t.getAsJsonArray("slots").get(2).isJsonNull());
        // Editing a stored one sends its id, chest and colour
        BuilderModel.Draft e = BuilderModel.Draft.of(data().templates().get(0));
        JsonObject et = BuilderModel.saveBody("srv", "Main", e).getAsJsonObject("template");
        assertEquals("t-0000mini", et.get("id").getAsString());
        assertEquals("118,64,-30", et.get("chest").getAsString());
        assertEquals("red", et.get("color").getAsString());
    }

    @Test
    void theListWordsStockAndRestock() {
        BuilderModel.Data d = data();
        assertEquals("2 complete, 1 incomplete (3 elsewhere): short end_crystal ×12", BuilderModel.stockText(d.stock("t-0000mini"), id -> id.substring(id.indexOf(':') + 1)));
        assertEquals("topping up", BuilderModel.restockText(d.restock(), "t-0000mini"));
        assertEquals("", BuilderModel.restockText(d.restock(), "t-other000"));
        assertEquals("0 complete", BuilderModel.stockText(d.stock("t-nothere0"), id -> id));
    }

    // Import local kits

    private static BuilderModel.ImportSlot slot(String id, int count) {
        return new BuilderModel.ImportSlot("minecraft:" + id, "minecraft:" + id, "minecraft:" + id, count);
    }

    private static BuilderModel.LocalKit local(String name, BuilderModel.ImportSlot... slots) {
        List<BuilderModel.ImportSlot> list = new ArrayList<>(List.of(slots));
        while (list.size() < 27) list.add(null);
        return new BuilderModel.LocalKit(name, list);
    }

    @Test
    void importSkipsOneKindAndCopiesNamesClashesAndPicksTheCategory() {
        KitTypes table = KitTypes.shared();
        BuilderModel.Data d = data();
        List<BuilderModel.LocalKit> kits = List.of(
            local("Crystal Pack", slot("totem_of_undying", 1), slot("end_crystal", 64), slot("obsidian", 64)),
            local("Mini Crystal", slot("totem_of_undying", 1), slot("end_crystal", 64),
                new BuilderModel.ImportSlot("minecraft:netherite_sword", "minecraft:netherite_sword#0123456789ab", "minecraft:netherite_sword#0123456789ab", 1)),
            local("Stack of totems", slot("totem_of_undying", 1), slot("totem_of_undying", 1)),
            local("Dirt and stone", slot("dirt", 64), slot("stone", 64)),
            local("Crystal Pack", slot("redstone", 64), slot("repeater", 64), slot("comparator", 64), slot("piston", 64)));
        List<BuilderModel.ImportItem> plan = BuilderModel.planImport(kits, d.templates(), table, "srv", "Main");
        assertEquals(5, plan.size());
        // a kit of three kinds is a pvp assortment
        assertFalse(plan.get(0).skipped());
        assertEquals("Crystal Pack", plan.get(0).name());
        JsonObject t = plan.get(0).template().getAsJsonObject("template");
        assertEquals("pvp", t.get("category").getAsString());
        assertTrue(t.get("chest").isJsonNull());
        assertTrue(t.get("color").isJsonNull());
        assertEquals(0, t.get("keep").getAsInt());
        assertEquals("minecraft:end_crystal", t.getAsJsonArray("slots").get(1).getAsJsonObject().get("match").getAsString());
        // the same items as a template that exists (the exact sword included) is not new
        assertTrue(plan.get(1).skipped(), "the same items as Mini Crystal, with no variant slot, is not new");
        assertEquals("one kind of item", plan.get(2).skip());
        // a mixed set of unrelated stock is kept, with the category "other"
        assertFalse(plan.get(3).skipped());
        assertEquals("other", plan.get(3).template().getAsJsonObject("template").get("category").getAsString());
        // the second "Crystal Pack" clashes with the first of this import
        assertEquals("Crystal Pack (2)", plan.get(4).name());
        assertEquals("redstone", plan.get(4).template().getAsJsonObject("template").get("category").getAsString());
        assertEquals("Imported 3, skipped 2: same contents as Mini Crystal; one kind of item", BuilderModel.summary(3, plan, List.of()));
        assertTrue(BuilderModel.summary(1, plan, List.of("too many changes")).endsWith("Not saved: too many changes"));
    }

    @Test
    void importSkipsABatchDuplicateStopsAtTheStashLimitAndNeverNamesKitsWhenHidden() {
        KitTypes table = KitTypes.shared();
        List<BuilderModel.LocalKit> kits = List.of(
            local("First", slot("dirt", 64), slot("stone", 64)),
            local("Second", slot("stone", 64), slot("dirt", 64)),
            local("Third", slot("sand", 64), slot("gravel", 64)),
            local("Fourth", slot("redstone", 64), slot("piston", 64)));
        List<BuilderModel.ImportItem> plan = BuilderModel.planImport(kits, List.of(), table, "s", "t", 5);
        assertFalse(plan.get(0).skipped());
        assertEquals("same contents as First", plan.get(1).skip(), "the same items in another order, in the same batch");
        assertFalse(plan.get(2).skipped());
        // Room for two: the third that would be imported is not
        List<BuilderModel.ImportItem> limited = BuilderModel.planImport(kits, List.of(), table, "s", "t", 2);
        assertFalse(limited.get(0).skipped());
        assertEquals("same contents as First", limited.get(1).skip());
        assertFalse(limited.get(2).skipped());
        assertEquals("the stash is at its limit of kits", limited.get(3).skip());
        assertEquals("the stash is at its limit of kits", BuilderModel.planImport(kits, List.of(), table, "s", "t", 0).get(0).skip());
        // The summary of a hidden window never names a kit
        assertTrue(BuilderModel.summary(2, limited, List.of()).contains("same contents as First"));
        assertFalse(BuilderModel.summary(2, limited, List.of(), true).contains("First"));
        assertTrue(BuilderModel.summary(2, limited, List.of(), true).contains("same contents as another kit"));
    }

    @Test
    void anImportedNameIsCutToFiftyCharactersWithItsSuffix() {
        String longName = "A".repeat(50);
        List<BuilderModel.ImportItem> plan = BuilderModel.planImport(List.of(local(longName, slot("dirt", 1), slot("stone", 1)), local(longName, slot("sand", 1), slot("gravel", 1))), List.of(),
            KitTypes.shared(), "s", "t");
        assertEquals(longName, plan.get(0).name());
        assertEquals(50, plan.get(1).name().length());
        assertTrue(plan.get(1).name().endsWith(" (2)"));
        // a name with a section sign or control character has them removed; an empty one can't be used
        assertEquals("ab", BuilderModel.planImport(List.of(local("a§b", slot("dirt", 1), slot("stone", 1))), List.of(), KitTypes.shared(), "s", "t").get(0).name());
        assertEquals("the name can't be used", BuilderModel.planImport(List.of(local("§", slot("dirt", 1), slot("stone", 1))), List.of(), KitTypes.shared(), "s", "t").get(0).skip());
    }
}
