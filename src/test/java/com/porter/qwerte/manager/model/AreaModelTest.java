package com.porter.qwerte.manager.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.stash.AreaDraft;
import com.porter.qwerte.stash.AreaEdit;
import org.junit.jupiter.api.Test;

/** The Area card apart from the widgets: the server's answer, its words, the body of a save and why a button is off; and the draft holder. */
class AreaModelTest {
    private static final String ANSWER = """
        {"server":"2b2t.org","stash":"Main","rev":7,"hash":"abc","by":"website","user":"Dustin","updatedAt":1700000000000,
         "area":{"box":[100,60,-40,163,90,23],"dimension":"minecraft:overworld"},"dimension":"minecraft:overworld","home":[130,70,-10],
         "bot":{"supports":true,"online":true,"synced":false,"applied":{"box":[0,0,0,9,9,9],"dimension":"minecraft:overworld"},
                "derived":[1,2,3,4,5,6],"pending":{"hash":"x","why":"busy"},"containersOutside":2},
         "impact":{"reported":10,"outside":3},"limits":{"maxW":256,"maxH":128,"maxD":256,"yMin":-64,"yMax":319,"farBlocks":256}}
        """;

    private static AreaModel.Data data(String json) {
        return AreaModel.parse(JsonParser.parseString(json));
    }

    @Test
    void anAnswerIsReadForgivingly() {
        AreaModel.Data d = data(ANSWER);
        assertNotNull(d);
        assertEquals(7, d.rev());
        assertArrayEquals(new int[] {100, 60, -40, 163, 90, 23}, d.box());
        assertEquals("minecraft:overworld", d.dimension());
        assertArrayEquals(new int[] {130, 70, -10}, d.home());
        assertEquals(Boolean.TRUE, d.bot().supports());
        assertEquals("busy", d.bot().pendingWhy());
        assertEquals(3, d.impact().outside());
        assertArrayEquals(new int[] {100, 60, -40, 163, 90, 23}, d.startBox());
        assertNull(AreaModel.parse(JsonParser.parseString("[]")));
        assertNull(AreaModel.parse(JsonParser.parseString("{\"stash\":\"\"}")));
        AreaModel.Data odd = data("{\"stash\":\"S\",\"area\":{\"box\":[1,2,3]},\"bot\":{\"supports\":\"yes\",\"applied\":5},\"limits\":7,\"home\":\"x\"}");
        assertNotNull(odd);
        assertNull(odd.box(), "a box that is not six numbers is no box");
        assertNull(odd.bot().supports(), "a supports that is not a boolean is unknown");
        assertNull(odd.home());
        assertNull(data("{\"stash\":\"S\",\"area\":{\"box\":[1.5,2,3,4,5,6]}}").box(), "a box with a fraction is no box");
        assertNull(data("{\"stash\":\"S\",\"area\":{\"box\":[5,2,3,4,5,6]}}").box(), "a minimum above its maximum is no box");
        assertEquals(256, odd.limits().maxW(), "default limits");
    }

    @Test
    void theEditorStartsFromTheStoredThenTheAppliedThenTheDerivedBox() {
        AreaModel.Data d = data("{\"stash\":\"S\",\"bot\":{\"applied\":{\"box\":[0,0,0,9,9,9]},\"derived\":[1,1,1,2,2,2]}}");
        assertArrayEquals(new int[] {0, 0, 0, 9, 9, 9}, d.startBox());
        AreaModel.Data e = data("{\"stash\":\"S\",\"bot\":{\"derived\":[1,1,1,2,2,2]}}");
        assertArrayEquals(new int[] {1, 1, 1, 2, 2, 2}, e.startBox());
        assertNull(data("{\"stash\":\"S\"}").startBox());
    }

    @Test
    void theBodyOfASaveCarriesTheRevisionAndAClearIsANull() {
        JsonObject set = AreaModel.saveBody("srv", "S", new int[] {1, 2, 3, 4, 5, 6}, "minecraft:the_nether", 9);
        assertEquals(9, set.get("rev").getAsLong());
        assertEquals("minecraft:the_nether", set.getAsJsonObject("area").get("dimension").getAsString());
        assertEquals(6, set.getAsJsonObject("area").getAsJsonArray("box").size());
        JsonObject clear = AreaModel.saveBody("srv", "S", null, null, 3);
        assertTrue(clear.get("area").isJsonNull());
    }

    @Test
    void theWordsFollowTheBotState() {
        AreaModel.Data d = data(ANSWER);
        assertTrue(AreaModel.sourceLine(d, false).contains("website by Dustin"));
        assertFalse(AreaModel.sourceLine(d, true).contains("Dustin"), "streamer display hides the user");
        assertTrue(AreaModel.botLine(d).startsWith("Waiting to be applied"));
        assertTrue(AreaModel.botLine(data("{\"stash\":\"S\",\"bot\":{\"supports\":false}}")).contains("until it is updated"));
        assertTrue(AreaModel.botLine(data("{\"stash\":\"S\",\"bot\":{\"supports\":true,\"online\":false}}")).contains("offline"));
        assertEquals("The bot has this area.", AreaModel.botLine(data("{\"stash\":\"S\",\"bot\":{\"supports\":true,\"online\":true,\"synced\":true}}")));
        assertTrue(AreaModel.botLine(data("{\"stash\":\"S\",\"bot\":{\"online\":true}}")).contains("Not sure"), "unknown support");
        assertEquals("3 of 10 reported containers would be outside the stored area.", AreaModel.impactLine(d));
        assertEquals("", AreaModel.impactLine(data("{\"stash\":\"S\"}")));
    }

    @Test
    void saveErrorsAreWordedByStatus() {
        assertEquals("Your account may not change the stash area.", AreaModel.saveError(403, "x"));
        assertEquals("too big", AreaModel.saveError(400, "too big"));
        assertTrue(AreaModel.saveError(409, null).contains("changed meanwhile"));
        assertTrue(AreaModel.saveError(429, null).contains("Too many"));
        assertTrue(AreaModel.saveError(500, null).contains("500"));
    }

    @Test
    void aButtonSaysWhyItIsOff() {
        String ow = "minecraft:overworld";
        assertNull(AreaModel.positionReason(true, true, ow, "minecraft:overworld"));
        assertNotNull(AreaModel.positionReason(false, true, ow, ""));
        assertTrue(AreaModel.positionReason(true, false, ow, ow).contains("server"));
        assertTrue(AreaModel.positionReason(true, true, "", ow).contains("dimension"));
        assertTrue(AreaModel.positionReason(true, true, ow, "minecraft:the_nether").contains("minecraft:overworld"));
        assertNull(AreaModel.positionReason(true, true, "ResourceKey[minecraft:dimension / minecraft:overworld]", "minecraft:overworld"), "the raw form matches");
        assertNull(AreaModel.lookReason(null, true));
        assertTrue(AreaModel.lookReason(null, false).contains("crosshair"));
        assertEquals("p", AreaModel.lookReason("p", false), "the position reason comes first");
        assertNotNull(AreaModel.saveReason(false, false, true, true, null));
        assertEquals("Saving...", AreaModel.saveReason(true, true, true, true, null));
        assertNotNull(AreaModel.saveReason(true, false, false, false, null));
        assertEquals("bad", AreaModel.saveReason(true, false, true, true, "bad"));
        assertTrue(AreaModel.saveReason(true, false, true, false, null).contains("same"));
        assertNull(AreaModel.saveReason(true, false, true, true, null));
    }

    @Test
    void theDraftLivesFifteenMinutesAndIsKeptPerStash() {
        AreaDraft drafts = new AreaDraft();
        AreaEdit.Draft box = AreaEdit.Draft.ofBox(new int[] {0, 0, 0, 15, 15, 15}, "minecraft:overworld");
        assertNull(drafts.get(0));
        drafts.set("k", true, "Main", box, 1_000);
        assertNotNull(drafts.forKey("k", 2_000));
        assertNull(drafts.forKey("other", 2_000));
        assertNotNull(drafts.get(1_000 + AreaDraft.LIFE_MS), "still there at exactly 15 minutes");
        assertNull(drafts.get(1_001 + AreaDraft.LIFE_MS));
        assertTrue(drafts.takeExpired(), "the lapse is told once");
        assertFalse(drafts.takeExpired());
        drafts.set("k", true, "Main", box, 5_000_000);
        drafts.clear();
        assertNull(drafts.get(5_000_001));
        assertFalse(drafts.takeExpired(), "a cancel is not a lapse");
        drafts.setClear("k", false, "Main", 6_000_000);
        assertTrue(drafts.get(6_000_001).clear());
        assertNull(drafts.get(6_000_001).draft());
        assertEquals("Area draft 16×16×16: open the Stash window to Save or Cancel.", AreaDraft.hint(box));
    }
}
