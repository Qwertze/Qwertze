package com.porter.qwerte.stash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * An addon-built v2 report against the checks of the server (PROTOCOL.md, kit types, templates and kit orders): the same rules the server applies to
 * the kit report route, written out here because the server is another project. Also the limits of the settings and the reply.
 */
class Kit2ReportShapeTest {
    private static final Set<String> KIT_CODES = Set.of("assortment", "named_assortment", "override", "template", "template_incomplete");
    private static final Set<String> NOT_CODES = Set.of("empty", "stock", "stock_neutral", "no_purpose", "few_kinds", "mixed", "override_none");
    private static final Pattern ITEM = Pattern.compile("^[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}$");
    private static final Pattern WORD = Pattern.compile("^[a-z]{1,32}$");
    private static final Pattern KEY = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,128}(#([0-9a-f]{8,40}|\\*))?$");

    private static KitScore.Result score() {
        return new KitScore.Result(1, 1, 1, 1, 4, true);
    }

    private static void checkWhy(JsonObject why, boolean kit, Set<String> types) {
        String code = why.get("code").getAsString();
        assertTrue(kit ? KIT_CODES.contains(code) : NOT_CODES.contains(code), code);
        if (!why.get("type").isJsonNull()) assertTrue(types.contains(why.get("type").getAsString()));
        for (String n : List.of("slots", "covered", "kinds")) {
            int v = why.get(n).getAsInt();
            assertTrue(v >= 0 && v <= 27, n);
        }
        if (why.has("need")) assertTrue(why.get("need").getAsInt() <= 27 && why.get("minKinds").getAsInt() <= 27);
        assertTrue(why.getAsJsonArray("items").size() <= 3);
        for (JsonElement i : why.getAsJsonArray("items")) assertTrue(ITEM.matcher(i.getAsString()).matches());
        assertTrue(why.getAsJsonArray("words").size() <= 4);
        for (JsonElement w : why.getAsJsonArray("words")) assertTrue(WORD.matcher(w.getAsString()).matches());
        if (why.has("missing")) {
            assertTrue(why.getAsJsonArray("missing").size() <= 8);
            for (JsonElement m : why.getAsJsonArray("missing")) assertTrue(KEY.matcher(m.getAsJsonObject().get("key").getAsString()).matches());
        }
    }

    @Test
    void anAddonBuiltReportPassesTheChecksOfTheServer() {
        List<KitTemplate.Slot> slots = new java.util.ArrayList<>();
        slots.add(new KitTemplate.Slot("minecraft:totem_of_undying", "minecraft:totem_of_undying", 1));
        slots.add(new KitTemplate.Slot("minecraft:end_crystal", "minecraft:end_crystal", 64));
        while (slots.size() < 27) slots.add(null);
        KitTemplate t = new KitTemplate("t-0000aaaa", "Crystal Pack", "pvp", null, null, 0, slots);
        KitSettings s = new KitSettings(1, "0".repeat(40), null, Map.of(), KitSettings.Scoring.DEFAULT,
            new KitSettings.Types("0".repeat(40), List.of(), Map.of("pvp", "2,64,2"), Map.of(), List.of(t)));
        KitClassifier.Result pvp = new KitClassifier.Result(true, KitClassifier.ASSORTMENT, "pvp", 22, 22, 3, 0, 0, List.of("minecraft:totem_of_undying"), List.of(), null, List.of());
        KitClassifier.Result named = new KitClassifier.Result(true, KitClassifier.NAMED_ASSORTMENT, "redstone", 10, 5, 5, 0, 0, List.of("minecraft:redstone"), List.of("kit"), null, List.of());
        List<KitTemplates.Missing> missing = List.of(new KitTemplates.Missing("minecraft:end_crystal", "minecraft:end_crystal", 63));
        KitClassifier.Result inc = new KitClassifier.Result(true, KitClassifier.TEMPLATE_INCOMPLETE, "pvp", 1, 0, 0, 0, 0, List.of(), List.of(), "t-0000aaaa", missing);
        KitClassifier.Result stock = new KitClassifier.Result(false, KitClassifier.STOCK, "pvp", 27, 27, 1, 0, 0, List.of("minecraft:totem_of_undying"), List.of(), null, List.of());
        KitClassifier.Result mixed = new KitClassifier.Result(false, KitClassifier.MIXED, "pvp", 13, 3, 1, 10, 3, List.of("minecraft:totem_of_undying"), List.of(), null, List.of());
        List<KitReport.Shulker> shulkers = List.of(
            new KitReport.Shulker(null, score(), pvp, null), new KitReport.Shulker(null, score(), pvp, null),
            new KitReport.Shulker("Redstone Kit", score(), named, null),
            new KitReport.Shulker("Crystal Pack", score(), inc, new KitTemplates.Found(t, new KitTemplates.Match(KitTemplates.State.INCOMPLETE, missing))),
            new KitReport.Shulker("Etc", score(), mixed, null), new KitReport.Shulker("Etc", score(), mixed, null), new KitReport.Shulker("Totems", score(), stock, null));
        KitReport.Built b = KitReport.buildV2("srv", "stash", "Bot", List.of(new KitReport.BoxInfo("2,64,2", "Kits", 27, 20, true, 1, shulkers)), List.of(), KitTypes.FINGERPRINT_V1, s);
        JsonObject root = JsonParser.parseString(new String(b.json(), StandardCharsets.UTF_8)).getAsJsonObject();
        Set<String> types = new HashSet<>(KitTypes.TYPE_IDS);

        assertEquals(2, root.getAsJsonObject("classifier").get("version").getAsInt());
        assertTrue(root.getAsJsonObject("classifier").get("table").getAsString().matches("[0-9a-f]{64}"));
        for (JsonElement ke : root.getAsJsonArray("kits")) {
            JsonObject k = ke.getAsJsonObject();
            int sum = 0;
            Set<String> seen = new HashSet<>();
            for (JsonElement te : k.getAsJsonArray("types")) {
                JsonObject to = te.getAsJsonObject();
                assertTrue(types.contains(to.get("type").getAsString()));
                assertTrue(seen.add(to.get("type").getAsString() + "|" + (to.has("template") ? to.get("template").getAsString() : "")), "one element per (category, template)");
                if (to.has("template")) assertTrue(to.get("template").getAsString().matches("^t-[0-9a-z]{8}$"));
                sum += to.get("count").getAsInt();
                checkWhy(to.getAsJsonObject("why"), true, types);
            }
            assertEquals(k.get("count").getAsInt(), sum, "the counts add up");
        }
        Set<String> others = new HashSet<>();
        for (JsonElement oe : root.getAsJsonArray("others")) {
            JsonObject o = oe.getAsJsonObject();
            assertTrue(others.add(o.get("key").getAsString() + "|" + o.get("container").getAsString()), "one per key and container");
            checkWhy(o.getAsJsonObject("why"), false, types);
        }
        assertEquals(2, others.size());
        JsonArray templates = root.getAsJsonArray("templates");
        for (JsonElement te : templates) {
            JsonObject to = te.getAsJsonObject();
            for (String n : List.of("complete", "incomplete", "elsewhere", "carried")) assertTrue(to.get(n).getAsInt() >= 0 && to.get(n).getAsInt() <= 4096);
            for (JsonElement m : to.getAsJsonArray("missing")) {
                assertTrue(KEY.matcher(m.getAsJsonObject().get("key").getAsString()).matches());
                assertTrue(KEY.matcher(m.getAsJsonObject().get("match").getAsString()).matches());
            }
        }
        assertEquals(1, templates.get(0).getAsJsonObject().get("incomplete").getAsInt());
        assertTrue(b.json().length < KitReport.MAX_BYTES);
    }

    @Test
    void aCategoryNameWithAFormatCharacterOrAnUnpairedSurrogateIsRefused() {
        String v1 = KitSettings.hashOf(null, KitSettings.Scoring.DEFAULT, Map.of());
        for (String name : new String[] {"Shop​kits", "Shop\ud800kits", "Shop\u0007kits", "Shop§kits"}) {
            JsonObject cat = new JsonObject();
            cat.addProperty("id", "c:shop");
            cat.addProperty("name", name);
            JsonArray cats = new JsonArray();
            cats.add(cat);
            JsonObject types = new JsonObject();
            types.addProperty("hash", "0".repeat(40));
            types.add("categories", cats);
            JsonObject o = JsonParser.parseString("{\"rev\":1,\"hash\":\"" + v1 + "\",\"kitsChest\":null,\"rules\":{}}").getAsJsonObject();
            o.add("types", types);
            assertThrows(KitSettings.Invalid.class, () -> KitSettings.parse(o), name);
        }
    }

    @Test
    void aTooDeeplyNestedReplyIsNoKitFieldsAtAll() {
        String deep = "[".repeat(5000) + "]".repeat(5000);
        StashIndex.KitsReply r = StashIndex.kitsReply(deep);
        assertFalse(r.reportKnown());
        assertNull(r.settings());
    }
}
