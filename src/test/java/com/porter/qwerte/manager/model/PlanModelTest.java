package com.porter.qwerte.manager.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanModelTest {
    private static final String PLAN = """
        {"id":"p1","fileName":"House.litematic","format":"litematic","formatVersion":6,"dataVersion":4189,"size":2048,
         "dimensions":{"x":10,"y":20,"z":30},"regions":1,"blocks":999,"tableVersion":"1.21.4","tableDataVersion":4189,"newerThanTable":true,
         "createdAt":1000,"expiresAt":2000,
         "options":{"multiplier":2,"exclude":["minecraft:dirt"],"fluids":true,"containerItems":false,"round":"stack"},
         "fluids":[{"id":"minecraft:water_bucket","count":3}],
         "containerItems":[{"id":"minecraft:torch","count":5}],
         "unknown":[{"id":"mod:thing","count":7}],"unknownOmitted":4,
         "stash":{"server":"s","stash":"main","indexed":true,"mcVersion":"1.21.4"},
         "items":[
           {"id":"minecraft:oak_planks","name":"Oak Planks","icon":true,"base":100,"need":200,"from":{"blocks":190,"fluids":0,"containers":10},
            "inIndex":true,"inStash":150,"available":120,"missing":80,"want":120,"maxStack":64,"variants":2,"excluded":false},
           {"id":"minecraft:dirt","base":5,"need":10,"from":{"blocks":10,"fluids":0,"containers":0},
            "inIndex":false,"inStash":0,"available":0,"missing":10,"want":0,"maxStack":64,"variants":0,"excluded":true},
           {"id":"minecraft:stone","name":"Stone","base":50,"need":100,"from":{},"inIndex":true,"inStash":500,"available":500,"missing":0,"want":100,"maxStack":64}
         ],
         "totals":{"need":300,"available":220,"missing":80,"want":220,"lines":2,"frozen":false,"slots":5,"shulkers":1,"parts":1},
         "ordered":{"partsTotal":3,"placed":{"1":"o1","2":"o2"},"remaining":[3],"again":[],"locked":true,"sameOptions":false,
                    "options":{"multiplier":1,"exclude":[],"fluids":false,"containerItems":false,"round":"none"},"server":"s","stash":"main"}}
        """;

    private static PlanModel.Plan plan() {
        return PlanModel.parsePlan(JsonParser.parseString(PLAN));
    }

    @Test
    void parsesAPlan() {
        PlanModel.Plan p = plan();
        assertEquals("p1", p.id());
        assertEquals("House.litematic", p.fileName());
        assertEquals(10, p.dimX());
        assertEquals(30, p.dimZ());
        assertTrue(p.newerThanTable());
        assertEquals(2, p.options().multiplier());
        assertEquals(List.of("minecraft:dirt"), p.options().exclude());
        assertTrue(p.options().fluids());
        assertEquals("stack", p.options().round());
        assertEquals(new PlanModel.Count("minecraft:torch", 5), p.containerItems().get(0));
        assertEquals(4, p.unknownOmitted());
        assertEquals("main", p.stash().stash());
        assertTrue(p.stash().indexed());
        assertEquals(3, p.items().size());
        PlanModel.Row planks = p.items().get(0);
        assertEquals(190, planks.fromBlocks());
        assertEquals(120, planks.available());
        assertEquals(2, planks.variants());
        assertTrue(p.items().get(1).excluded());
        assertEquals("Dirt", p.items().get(1).title());
        assertEquals(64, p.items().get(2).maxStack());
        assertEquals(220, p.totals().want());
        assertTrue(p.totals().hasSplit());
        assertEquals(1, p.totals().shulkers());
    }

    @Test
    void parsesTheOrderedParts() {
        PlanModel.Ordered o = plan().ordered();
        assertNotNull(o);
        assertEquals(3, o.partsTotal());
        assertEquals(Map.of(1, "o1", 2, "o2"), o.placed());
        assertEquals(List.of(3), o.remaining());
        assertTrue(o.locked());
        assertFalse(o.sameOptions());
        assertEquals(1, o.options().multiplier());
        assertEquals(2, o.lastPart());
        assertEquals("main", o.stash());
    }

    @Test
    void aPlanWithoutAStashOrTotalsStillParses() {
        PlanModel.Plan p = PlanModel.parsePlan(JsonParser.parseString("{\"id\":\"x\",\"stash\":null,\"ordered\":null,\"items\":\"nope\"}"));
        assertNotNull(p);
        assertNull(p.stash());
        assertNull(p.ordered());
        assertTrue(p.items().isEmpty());
        assertFalse(p.totals().hasSplit());
        assertEquals(1, p.options().multiplier());
        assertNull(PlanModel.parsePlan(JsonParser.parseString("{}")));
        assertNull(PlanModel.parsePlan(JsonParser.parseString("[1]")));
        assertNull(PlanModel.parsePlan(null));
    }

    @Test
    void parsesTheListOfPlans() {
        List<PlanModel.Summary> list = PlanModel.parseList(JsonParser.parseString(
            "{\"plans\":[{\"id\":\"a\",\"fileName\":\"A.schem\",\"format\":\"sponge\",\"blocks\":5,\"createdAt\":1,\"expiresAt\":2},{\"fileName\":\"no id\"},7]}"));
        assertEquals(1, list.size());
        assertEquals("a", list.get(0).id());
        assertEquals(5, list.get(0).blocks());
        assertTrue(PlanModel.parseList(JsonParser.parseString("{}")).isEmpty());
    }

    @Test
    void parsesADryRun() {
        PlanModel.Dry d = PlanModel.parseDry(JsonParser.parseString("""
            {"ok":true,"dryRun":true,"parts":[{"part":1,"lines":[],"lineCount":3,"shulkers":2,"slots":50,"items":900,"order":null},
              {"part":2,"lineCount":1,"shulkers":1,"items":10,"order":"o9"}],
             "partsTotal":2,"remaining":[1],"again":[2],"notAvailable":[{"id":"minecraft:dirt","need":10}],"missing":[{"id":"minecraft:stone","missing":4}]}
            """));
        assertEquals(2, d.parts().size());
        assertEquals(900, d.parts().get(0).items());
        assertEquals("", d.parts().get(0).order());
        assertEquals("o9", d.parts().get(1).order());
        assertEquals(2, d.partsTotal());
        assertEquals(1, d.first());
        assertEquals(10, d.notAvailable().get(0).count());
        assertEquals(4, d.missing().get(0).count());
        assertEquals(2, new PlanModel.Dry(List.of(), 2, List.of(), List.of(2), List.of(), List.of()).first());
        assertNull(PlanModel.parseDry(JsonParser.parseString("5")));
    }

    @Test
    void parsesAPlacedOrderAndTheOrderList() {
        PlanModel.Placed p = PlanModel.parsePlaced(JsonParser.parseString("""
            {"ok":true,"order":{"id":"o3","status":"pending"},"part":3,"partsTotal":3,"remaining":[],"again":[],"placed":{"1":"o1","3":"o3"}}
            """));
        assertEquals(3, p.part());
        assertEquals("o3", p.orderId());
        assertEquals("pending", p.orderStatus());
        assertEquals(Map.of(1, "o1", 3, "o3"), p.placed());
        Map<String, String> st = PlanModel.parseOrderStatuses(JsonParser.parseString("{\"orders\":[{\"id\":\"o1\",\"status\":\"delivered\"},{\"id\":\"o3\",\"status\":\"running\"},{}]}"));
        assertEquals(Map.of("o1", "delivered", "o3", "running"), st);
    }

    @Test
    void optionsAreClampedSortedAndSerialised() {
        PlanModel.Options o = new PlanModel.Options(500, List.of("b", "a", "b"), true, false, "weird");
        assertEquals(64, o.multiplier());
        assertEquals(List.of("a", "b"), o.exclude());
        assertEquals("none", o.round());
        assertEquals(1, o.withMultiplier(0).multiplier());
        assertEquals(List.of("a"), o.withExcluded("b", false).exclude());
        assertEquals(List.of("a", "b", "c"), o.withExcluded("c", true).exclude());
        JsonObject body = new JsonObject();
        o.withRound("shulker").addTo(body);
        assertEquals("{\"multiplier\":64,\"exclude\":[\"a\",\"b\"],\"fluids\":true,\"containerItems\":false,\"round\":\"shulker\",\"craft\":false}", body.toString());
    }

    @Test
    void filtersSortsAndPages() {
        List<PlanModel.Row> rows = plan().items();
        assertEquals(3, PlanModel.visible(rows, "", false, PlanModel.Sort.CREATIVE, false).size());
        // only missing: the excluded dirt row does not count
        List<PlanModel.Row> missing = PlanModel.visible(rows, "", true, PlanModel.Sort.CREATIVE, false);
        assertEquals(1, missing.size());
        assertEquals("minecraft:oak_planks", missing.get(0).id());
        // the filter reads the name and the id
        assertEquals(1, PlanModel.visible(rows, " STONE ", false, PlanModel.Sort.CREATIVE, false).size());
        assertEquals(1, PlanModel.visible(rows, "minecraft:dirt", false, PlanModel.Sort.CREATIVE, false).size());
        // sorts
        assertEquals("minecraft:dirt", PlanModel.visible(rows, "", false, PlanModel.Sort.NAME, false).get(0).id());
        assertEquals("minecraft:oak_planks", PlanModel.visible(rows, "", false, PlanModel.Sort.NEED, true).get(0).id());
        assertEquals("minecraft:stone", PlanModel.visible(rows, "", false, PlanModel.Sort.IN_STASH, true).get(0).id());
        assertEquals(PlanModel.Sort.MISSING, PlanModel.Sort.ofLabel("Missing"));
        assertEquals(PlanModel.Sort.CREATIVE, PlanModel.Sort.ofLabel("?"));
        // paging
        assertEquals(1, PlanModel.pageCount(0));
        assertEquals(1, PlanModel.pageCount(PlanModel.PAGE_SIZE));
        assertEquals(2, PlanModel.pageCount(PlanModel.PAGE_SIZE + 1));
        List<PlanModel.Row> many = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) many.add(new PlanModel.Row("minecraft:i" + i, "", 1, 1, 1, 0, 0, true, 0, 0, 1, 0, 64, 0, false));
        assertEquals(PlanModel.PAGE_SIZE, PlanModel.page(many, 0).size());
        assertEquals(10, PlanModel.page(many, 2).size());
        assertEquals(10, PlanModel.page(many, 99).size());
        assertEquals(PlanModel.PAGE_SIZE, PlanModel.page(many, -4).size());
        assertTrue(PlanModel.page(List.of(), 3).isEmpty());
    }

    @Test
    void orderStateHints() {
        PlanModel.Ordered o = plan().ordered();
        assertTrue(PlanModel.pollOrders(o, Map.of()));
        PlanModel.OrderHint checking = PlanModel.hint(o, Map.of());
        assertFalse(checking.nextEnabled());
        assertTrue(checking.text().contains("Checking"));
        assertFalse(PlanModel.hint(o, Map.of("o2", "running")).nextEnabled());
        PlanModel.OrderHint delivered = PlanModel.hint(o, Map.of("o2", "delivered"));
        assertTrue(delivered.nextEnabled());
        assertFalse(delivered.warn());
        PlanModel.OrderHint failed = PlanModel.hint(o, Map.of("o2", "failed"));
        assertTrue(failed.nextEnabled());
        assertTrue(failed.warn());
        assertEquals("", PlanModel.hint(null, Map.of()).text());

        PlanModel.Ordered done = new PlanModel.Ordered(2, Map.of(1, "a", 2, "b"), List.of(), List.of(), true, true, PlanModel.Options.DEFAULT, "s", "t");
        assertTrue(PlanModel.pollOrders(done, Map.of("a", "delivered")));
        assertFalse(PlanModel.pollOrders(done, Map.of("a", "delivered", "b", "cancelled")));
        assertTrue(PlanModel.hint(done, Map.of()).text().startsWith("All 2 parts ordered"));
        assertTrue(PlanModel.needsReload(done, Map.of("a", "failed")));
        assertFalse(PlanModel.needsReload(new PlanModel.Ordered(2, Map.of(1, "a"), List.of(2), List.of(1), true, true, PlanModel.Options.DEFAULT, "s", "t"), Map.of("a", "failed")));
        PlanModel.Ordered again = new PlanModel.Ordered(2, Map.of(1, "a", 2, "b"), List.of(), List.of(2), true, true, PlanModel.Options.DEFAULT, "s", "t");
        assertTrue(PlanModel.hint(again, Map.of()).text().contains("Part 2 failed"));
    }

    @Test
    void formatsStacksAndTheMissingText() {
        assertEquals("0", PlanModel.stacks(0, 64));
        assertEquals("5", PlanModel.stacks(5, 64));
        assertEquals("1 st + 6", PlanModel.stacks(70, 64));
        assertEquals("1 sh + 2 st + 3", PlanModel.stacks(1728 + 128 + 3, 64));
        assertEquals("20", PlanModel.stacks(20, 1));
        assertEquals("Oak Planks", PlanModel.pretty("minecraft:oak_planks"));
        assertEquals("1.5 KiB", PlanModel.size(1536));
        assertEquals("Missing materials (1 kind)\n- Oak Planks: 80 (1 st + 16)\n", PlanModel.missingText(plan()));
        assertEquals("Missing materials (0 kinds)\n", PlanModel.missingText(null));
    }

    @Test
    void errorWords() {
        assertTrue(PlanModel.uploadError(413, "", 0).contains("8 MiB"));
        assertTrue(PlanModel.uploadError(422, "legacy", 0).contains(".litematic"));
        assertEquals("Too many uploads. Try again in 42 s.", PlanModel.uploadError(429, "x", 42));
        assertTrue(PlanModel.uploadError(0, "", 0).contains("unreachable"));
        assertEquals("The file is broken.", PlanModel.uploadError(400, "the file is broken", 0));
        assertEquals("Could not read the file (HTTP 500).", PlanModel.uploadError(500, "", 0));
        assertEquals("Options locked.", PlanModel.orderError(409, "options locked"));
        assertTrue(PlanModel.orderError(409, "").contains("can not change"));
        assertEquals("Failed (HTTP 404).", PlanModel.orderError(404, ""));
    }
}
