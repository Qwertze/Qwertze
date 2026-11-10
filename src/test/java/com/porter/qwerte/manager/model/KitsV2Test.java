package com.porter.qwerte.manager.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Kits tab's v2 part: what the server says, the reason texts (the contract's wording), the chain and the options. */
class KitsV2Test {
    private static final String BODY = """
        {"rev":3,"kitsChest":"120,64,-33","kitsChestStatus":"ok",
         "bot":{"supports":true,"synced":true,"online":true,"types":true,"typesSynced":true},
         "report":{"receivedAt":1,"readAt":1,"classifier":{"version":2,"table":"c3ff"}},
         "containers":[{"id":"118,64,-33","label":"PvP","free":3,"pickable":true,"full":false},{"id":"120,64,-33","label":"","free":9,"pickable":true,"full":false}],
         "types":[{"id":"pvp","name":"PvP / Crystal","custom":false,"chest":"118,64,-33","chestStatus":"full","count":5},
                  {"id":"travel","name":"Travel","custom":false,"chest":null,"chestStatus":"unset","count":0},
                  {"id":"c:shop","name":"Shop kits","custom":true,"chest":null,"chestStatus":"unset","count":0}],
         "treatAs":[{"key":"etc","type":"building"},{"key":"old","type":"none"},{"key":"Bad KEY","type":"pvp"}],
         "rows":[{"key":"pvp kit","name":"PvP Kit","type":"pvp","typeSource":"auto",
                  "why":{"code":"assortment","type":"pvp","slots":27,"covered":26,"kinds":11,"items":["minecraft:end_crystal","minecraft:totem_of_undying"],"words":[]},
                  "count":3,"carried":0,"locations":[{"container":"118,64,-33","count":3}],"rule":"",
                  "chain":[{"container":"118,64,-33","source":"typeChest","status":"full"},{"container":"120,64,-33","source":"kitsChest","status":"ok"}],
                  "target":"120,64,-33","targetSource":"kitsChest","fallback":true,"status":"home"},
                 {"key":null,"name":null,"type":"travel","typeSource":"auto","why":{"code":"assortment","type":"travel","slots":27,"covered":27,"kinds":2,"items":["minecraft:elytra"],"words":[]},
                  "count":1,"carried":0,"locations":[],"rule":"","chain":[],"target":"","targetSource":null,"fallback":false,"status":"none"}],
         "others":[{"key":"etc","name":"Etc","count":1,"locations":[],"why":{"code":"mixed","type":"pvp","slots":13,"covered":3,"kinds":1,"need":10,"minKinds":3,"items":[],"words":[]},"treatAs":"","status":"present"},
                   {"key":"cobble","name":"Bob's Cobble","count":1,"locations":[],"why":{"code":"stock_neutral","type":null,"slots":27,"items":["minecraft:cobblestone"],"words":[]},"treatAs":"","status":"present"}],
         "limits":{"maxRules":200,"maxKey":64,"maxTreatAs":200}}
        """;

    private static KitsV2.Data data() {
        return KitsV2.parse(JsonParser.parseString(BODY));
    }

    private static KitsModel.Data v1() {
        return KitsModel.parse(JsonParser.parseString(BODY));
    }

    @Test
    void theV2AnswerIsReadAndCheckedAndAV1AnswerIsNotV2() {
        KitsV2.Data d = data();
        assertNotNull(d);
        assertTrue(d.v2());
        assertEquals(3, d.types().size());
        assertEquals("full", d.type("pvp").chestStatus());
        assertEquals("", d.type("travel").chest());
        assertTrue(d.type("c:shop").custom());
        assertEquals(2, d.treatAs().size(), "a key that is not a kit key is dropped");
        assertEquals("building", d.treatAsOf("etc"));
        assertEquals("", d.treatAsOf("nothing"));
        assertEquals(2, d.rows().size());
        assertTrue(d.rows().get(1).unnamed());
        assertEquals(2, d.rows().get(0).chain().size());
        assertTrue(d.rows().get(0).fallback());
        assertTrue(d.botTypes() && d.botTypesSynced() && d.hasReport());
        assertFalse(d.v1Reporting());
        assertTrue(d.others().get(1).single(), "a single-item box has no Treat as");
        assertFalse(d.others().get(0).single());
        // A v1 server (no types) is not v2; a v1 bot is the one the scoring editor is for
        KitsV2.Data old = KitsV2.parse(JsonParser.parseString("{\"bot\":{\"supports\":true,\"online\":true},\"rows\":[]}"));
        assertFalse(old.v2());
        assertTrue(old.v1Reporting());
        assertNull(KitsV2.parse(JsonParser.parseString("[]")));
    }

    private static String reason(String json) {
        KitsV2.Why w = KitsV2.why(JsonParser.parseString(json));
        return KitsV2.reason(data(), w, "Mini Crystal", false, id -> null);
    }

    @Test
    void theReasonTextsAreTheContractsWording() {
        assertEquals("PvP / Crystal kit: 26 of 27 slots are PvP items, 11 kinds (mostly end crystal, totem of undying).",
            reason("{\"code\":\"assortment\",\"type\":\"pvp\",\"slots\":27,\"covered\":26,\"kinds\":11,\"items\":[\"minecraft:end_crystal\",\"minecraft:totem_of_undying\"]}"));
        assertEquals("Redstone kit: 5 of 10 slots are redstone items, 5 kinds (mostly redstone). The name says “kit”, so half is enough.",
            reason("{\"code\":\"named_assortment\",\"type\":\"redstone\",\"slots\":10,\"covered\":5,\"kinds\":5,\"items\":[\"minecraft:redstone\"],\"words\":[\"kit\"]}"));
        assertEquals("Building kit: set by hand for this name.", reason("{\"code\":\"override\",\"type\":\"building\"}"));
        assertEquals("Not a kit: set by hand for this name.", reason("{\"code\":\"override_none\"}"));
        assertEquals("Not a kit: the box is empty.", reason("{\"code\":\"empty\"}"));
        assertEquals("Not a kit: it only holds beacon. A box of one kind of item is stock and goes to the beacon chest.", reason("{\"code\":\"stock\",\"type\":\"other\",\"items\":[\"minecraft:beacon\"]}"));
        assertEquals("Not a kit: none of its items has a kit purpose.", reason("{\"code\":\"no_purpose\"}"));
        assertEquals("Not a kit: 26 of 27 slots are travel items, but only 1 kind(s). A Travel kit needs 2.",
            reason("{\"code\":\"few_kinds\",\"type\":\"travel\",\"slots\":27,\"covered\":26,\"kinds\":1,\"need\":21,\"minKinds\":2}"));
        assertEquals("Not a kit: no common purpose. The closest is PvP / Crystal, with 3 of 13 slots; a kit needs 10.",
            reason("{\"code\":\"mixed\",\"type\":\"pvp\",\"slots\":13,\"covered\":3,\"kinds\":1,\"need\":10}"));
        assertEquals("PvP / Crystal kit: matches the kit “Mini Crystal”.", reason("{\"code\":\"template\",\"type\":\"pvp\",\"template\":\"t-0000mini\"}"));
        assertEquals("PvP / Crystal kit: an incomplete “Mini Crystal” (short end crystal ×128). It is kept and topped up, but not delivered until complete.",
            reason("{\"code\":\"template_incomplete\",\"type\":\"pvp\",\"template\":\"t-0000mini\",\"missing\":[{\"key\":\"minecraft:end_crystal\",\"count\":128}]}"));
    }

    @Test
    void streamerDisplayLeavesTheNameWordOutOfANamedAssortment() {
        KitsV2.Why w = KitsV2.why(JsonParser.parseString("{\"code\":\"named_assortment\",\"type\":\"redstone\",\"slots\":10,\"covered\":5,\"kinds\":5,\"items\":[\"minecraft:redstone\"],\"words\":[\"kit\"]}"));
        assertTrue(KitsV2.reason(data(), w, "", false, id -> null).contains("The name says"));
        assertFalse(KitsV2.reason(data(), w, "", true, id -> null).contains("The name says"));
    }

    @Test
    void streamerDisplayNeverNamesTheKitInAReason() {
        KitsV2.Why w = KitsV2.why(JsonParser.parseString("{\"code\":\"template\",\"type\":\"pvp\",\"template\":\"t-0000mini\"}"));
        String hidden = KitsV2.reason(data(), w, "Mini Crystal", true, id -> null);
        assertFalse(hidden.contains("Mini Crystal"));
        assertEquals("PvP / Crystal kit: matches a saved kit.", hidden);
        KitsV2.Why inc = KitsV2.why(JsonParser.parseString("{\"code\":\"template_incomplete\",\"type\":\"pvp\",\"template\":\"t-0000mini\",\"missing\":[]}"));
        assertFalse(KitsV2.reason(data(), inc, "Mini Crystal", true, id -> null).contains("Mini Crystal"));
        // The item names stay: the page's usual name wins over the path
        assertEquals("Block of Gold", KitsV2.itemName("minecraft:gold_block", id -> "Block of Gold"));
        assertEquals("gold block", KitsV2.itemName("minecraft:gold_block", id -> null));
    }

    @Test
    void whereItGoesNamesAFallbackAndAnEmptyChain() {
        KitsV2.Data d = data();
        KitsModel.Data v = v1();
        assertEquals("PvP / Crystal chest full, using the kits chest (Chest 2)", KitsV2.goesTo(d, v, d.rows().get(0), true), "streamer display: no position");
        assertEquals("PvP / Crystal chest full, using the kits chest (120, 64, -33)", KitsV2.goesTo(d, v, d.rows().get(0), false));
        assertEquals("Sorted like any other shulker", KitsV2.goesTo(d, v, d.rows().get(1), false));
        assertEquals("PvP (118, 64, -33) ×3", KitsV2.where(v, d.rows().get(0), false));
        assertEquals("Chest 1 ×3", KitsV2.where(v, d.rows().get(0), true));
    }

    @Test
    void theTypePickerOffersAutoEveryTypeAndNotAKit() {
        KitsV2.Data d = data();
        KitsModel.Options o = KitsV2.typeOptions(d, "building");
        assertEquals("Auto", o.texts()[0]);
        assertEquals("Not a kit", o.texts()[o.texts().length - 1]);
        assertEquals("none", o.values()[o.values().length - 1]);
        assertEquals("c:shop", o.values()[3]);
        // "building" is not one of the three types listed in this answer: nothing is selected but Auto
        assertEquals(0, o.selected());
        assertEquals(1, KitsV2.typeOptions(d, "pvp").selected());
        assertEquals(o.values().length - 1, KitsV2.typeOptions(d, "none").selected());
    }

    @Test
    void theHeaderNotesAndTheStatusWords() {
        KitsV2.Data d = data();
        assertTrue(KitsV2.notes(d).isEmpty());
        KitsV2.Data old = KitsV2.parse(JsonParser.parseString("{\"types\":[],\"bot\":{\"supports\":true,\"types\":false},\"rows\":[]}"));
        assertEquals(List.of("warn", "").size(), KitsV2.notes(old).size());
        assertEquals(KitsV2.OLD_BOT, KitsV2.notes(old).get(0).text());
        assertEquals("Waiting for the bot's report", KitsV2.status(KitsModel.parse(JsonParser.parseString("{\"rows\":[]}")), d.rows().get(0)).text());
        assertEquals("In place", KitsV2.status(v1(), d.rows().get(0)).text());
        assertEquals("not set", KitsV2.chestStatusText("unset"));
        assertEquals("full", KitsV2.chestStatusText("full"));
    }
}
