package com.porter.qwerte.manager.model;

import com.google.gson.JsonParser;
import com.porter.qwerte.manager.model.KitsModel.Data;
import com.porter.qwerte.manager.model.KitsModel.Row;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Kits tab's data and words. */
class KitsModelTest {
    private static final String BODY = "{\"server\":\"s\",\"stash\":\"x\",\"rev\":7,\"hash\":\"h\",\"kitsChest\":\"120,64,-33\","
        + "\"scoring\":{\"threshold\":4,\"name\":2,\"mix\":3,\"full\":1,\"op\":4,\"opMin\":3,\"max\":10},"
        + "\"rules\":[{\"key\":\"pvp kit\",\"container\":\"118,64,-33\"}],"
        + "\"bot\":{\"supports\":true,\"synced\":true,\"online\":true},\"report\":{\"receivedAt\":5,\"readAt\":4},"
        + "\"containers\":[{\"id\":\"118,64,-33\",\"label\":\"PvP\",\"size\":54,\"free\":3,\"pickable\":true,\"full\":false},"
        + "{\"id\":\"120,64,-33\",\"label\":null,\"size\":27,\"free\":0,\"pickable\":true,\"full\":true},"
        + "{\"id\":\"130,64,-33\",\"label\":\"Drop\",\"size\":27,\"free\":9,\"pickable\":false,\"full\":false}],"
        + "\"kitsChestStatus\":\"full\","
        + "\"rows\":[{\"key\":\"pvp kit\",\"name\":\"PvP Kit\",\"count\":4,\"carried\":1,\"score\":{\"total\":10,\"name\":2,\"mix\":3,\"full\":1,\"op\":4},\"minTotal\":8,"
        + "\"locations\":[{\"container\":\"118,64,-33\",\"count\":3}],\"rule\":\"118,64,-33\",\"target\":\"118,64,-33\",\"targetSource\":\"rule\",\"status\":\"pending\"},"
        + "{\"key\":null,\"name\":null,\"count\":2,\"carried\":0,\"score\":{\"total\":8,\"name\":0,\"mix\":3,\"full\":1,\"op\":4},\"minTotal\":5,"
        + "\"locations\":[{\"container\":\"118,64,-33\",\"count\":2}],\"rule\":null,\"target\":\"120,64,-33\",\"targetSource\":\"kitsChest\",\"status\":\"full\"}],"
        + "\"limits\":{\"maxRules\":200,\"maxKey\":64}}";

    private static Data data() {
        return KitsModel.parse(JsonParser.parseString(BODY));
    }

    private static Data parse(String json) {
        return KitsModel.parse(JsonParser.parseString(json));
    }

    @Test
    void theGetBodyIsRead() {
        Data d = data();
        assertEquals(7, d.rev());
        assertEquals("120,64,-33", d.kitsChest());
        assertEquals("full", d.kitsChestStatus());
        assertTrue(d.hasReport());
        assertTrue(d.bot().supports() && d.bot().synced() && d.bot().online());
        assertEquals(new KitsModel.Scoring(4, 2, 3, 1, 4, 3, 10), d.scoring());
        assertEquals(3, d.containers().size());
        assertEquals(2, d.rows().size());
        Row r = d.rows().get(0);
        assertEquals("pvp kit", r.key());
        assertEquals(4, r.count());
        assertEquals(1, r.carried());
        assertEquals(new KitsModel.Location("118,64,-33", 3), r.locations().get(0));
        assertEquals("118,64,-33", r.rule());
        assertEquals("rule", r.targetSource());
        assertEquals(8, r.minTotal());
    }

    @Test
    void theUnnamedRowHasNoKeyNameOrRule() {
        Row u = data().rows().get(1);
        assertTrue(u.unnamed());
        assertEquals("", u.key());
        assertEquals("", u.rule());
        assertEquals("(unnamed)", u.title(false, 1));
        assertEquals("(unnamed)", u.title(true, 1), "streamer display has nothing to hide in it");
        assertEquals("(kits chest)", KitsModel.UNNAMED_CHEST);
    }

    @Test
    void streamerDisplayHidesKitNames() {
        Row r = data().rows().get(0);
        assertEquals("PvP Kit", r.title(false, 0));
        assertEquals("Kit 1", r.title(true, 0));
    }

    @Test
    void scoreTextShowsOnlyThePartsAboveZeroOrNoPoints() {
        Data d = data();
        assertEquals("10/10: name 2, mix 3, full 1, OP 4 (lowest 8)", KitsModel.scoreText(d.rows().get(0), d.scoring().max()));
        assertEquals("8/10: mix 3, full 1, OP 4 (lowest 5)", KitsModel.scoreText(d.rows().get(1), 10), "name 0 is left out");
        Row same = new Row("a kit", "A Kit", 1, 0, List.of(), "", "", "", "none", new KitsModel.Score(10, 2, 3, 1, 4), 10);
        assertEquals("10/10: name 2, mix 3, full 1, OP 4", KitsModel.scoreText(same, 10));
        Row none = new Row("a kit", "A Kit", 1, 0, List.of(), "", "", "", "none", new KitsModel.Score(0, 0, 0, 0, 0), 0);
        assertEquals("0/10: no points", KitsModel.scoreText(none, 10));
        assertEquals("", KitsModel.scoreText(new Row("a kit", "", 1, 0, List.of(), "", "", "", "none", null, 0), 10));
    }

    @Test
    void aBodyWithoutScoringGetsTheDefaultsAndWithoutAReportSaysSo() {
        Data d = parse("{\"kitsChest\":null,\"report\":null,\"rows\":[],\"containers\":[]}");
        assertNotNull(d);
        assertEquals(KitsModel.Scoring.DEFAULT, d.scoring());
        assertEquals(3, d.scoring().opMin());
        assertFalse(d.hasReport());
        assertEquals("", d.kitsChest());
        assertNull(KitsModel.parse(JsonParser.parseString("[]")));
        assertEquals("Waiting for the bot's report.", KitsModel.empty(d));
        assertTrue(KitsModel.empty(data()).startsWith("No kits found."));
    }

    @Test
    void statusPillsFollowTheWebsiteAndWaitForTheReport() {
        assertEquals(new KitsModel.Status("Waiting for the sorter", ""), KitsModel.status("pending"));
        assertEquals(new KitsModel.Status("In place", "ok"), KitsModel.status("home"));
        assertEquals(new KitsModel.Status("Target full", "warn"), KitsModel.status("full"));
        assertEquals(new KitsModel.Status("Target missing", "off"), KitsModel.status("missing"));
        assertEquals(new KitsModel.Status("Normal sorting", ""), KitsModel.status("none"));
        assertEquals(new KitsModel.Status("None in the stash", ""), KitsModel.status("absent"));
        Data noReport = parse("{\"report\":null,\"rows\":[{\"key\":\"a kit\",\"status\":\"missing\"}]}");
        assertEquals(new KitsModel.Status("Waiting for the bot's report", ""), KitsModel.status(noReport, noReport.rows().get(0)));
        assertEquals(new KitsModel.Status("In place", "ok"), KitsModel.status(data(), new Row("a kit", "", 1, 0, List.of(), "", "", "", "home", null, 0)));
    }

    @Test
    void theBannerWordsTheBotsState() {
        Data d = data();
        List<KitsModel.Note> notes = KitsModel.banner(d, true);
        assertEquals(1, notes.size());
        assertEquals("warn", notes.get(0).kind());
        assertTrue(notes.get(0).text().contains("kits chest is full"));

        Data old = parse("{\"bot\":{\"supports\":false,\"synced\":false,\"online\":true},\"report\":null,\"kitsChestStatus\":\"missing\"}");
        List<KitsModel.Note> o = KitsModel.banner(old, false);
        assertTrue(o.get(0).text().contains("doesn't know about kits"));
        assertTrue(o.stream().anyMatch(n -> n.text().contains("hasn't reported its containers")));
        assertTrue(o.stream().noneMatch(n -> n.text().contains("isn't among")), "the missing banner waits for a report");
        assertTrue(o.stream().anyMatch(n -> n.text().contains("\"settings\" permission")));

        Data offline = parse("{\"bot\":{\"supports\":true,\"synced\":false,\"online\":false},\"report\":{}}");
        assertTrue(KitsModel.banner(offline, true).get(0).text().contains("offline"));
        Data unsynced = parse("{\"bot\":{\"supports\":true,\"synced\":false,\"online\":true},\"report\":{}}");
        assertTrue(KitsModel.banner(unsynced, true).get(0).text().contains("hasn't picked up"));
        Data missing = parse("{\"bot\":{\"supports\":true,\"synced\":true,\"online\":true},\"report\":{},\"kitsChestStatus\":\"missing\"}");
        assertEquals("off", KitsModel.banner(missing, true).get(0).kind());
    }

    @Test
    void placesShowLabelAndCoordinatesOrOnlyChestNWhenHidden() {
        Data d = data();
        assertEquals("PvP (118, 64, -33)", KitsModel.place(d, "118,64,-33", false));
        assertEquals("120, 64, -33", KitsModel.place(d, "120,64,-33", false), "no label: the coordinates");
        assertEquals("Chest 1", KitsModel.place(d, "118,64,-33", true), "streamer display never shows a label");
        assertEquals("Chest 2", KitsModel.place(d, "120,64,-33", true));
        assertEquals("a chest", KitsModel.place(d, "9,9,9", true), "not listed: no Chest 0");
        assertEquals("?", KitsModel.place(d, "", false));
        for (String text : List.of(KitsModel.where(d, d.rows().get(0), true), KitsModel.target(d, d.rows().get(0), true), KitsModel.chestInfo(d, true))) {
            assertFalse(text.contains("PvP") || text.contains("118") || text.contains("120"), text);
        }
        assertEquals("PvP (118, 64, -33) \u00d73, carried \u00d71", KitsModel.where(d, d.rows().get(0), false));
        assertEquals("PvP (118, 64, -33) (its chest)", KitsModel.target(d, d.rows().get(0), false));
        assertEquals("120, 64, -33 (kits chest)", KitsModel.target(d, d.rows().get(1), false));
    }

    @Test
    void pickerOptionsListPickableContainersWithRoomAndKeepTheCurrentOne() {
        Data d = data();
        KitsModel.Options o = KitsModel.options(d, "", "Kits chest (default)", false);
        assertArrayEquals(new String[] {"", "118,64,-33", "120,64,-33"}, o.values());
        assertEquals("Kits chest (default)", o.texts()[0]);
        assertEquals("PvP (118, 64, -33) \u00b7 3 free", o.texts()[1]);
        assertEquals(0, o.selected());

        KitsModel.Options cur = KitsModel.options(d, "130,64,-33", null, false);
        assertTrue(cur.texts()[cur.selected()].endsWith("can't take kits"), "the current value stays although it can't take kits");
        KitsModel.Options gone = KitsModel.options(d, "9,9,9", null, false);
        assertTrue(gone.texts()[gone.selected()].endsWith("not reported"));

        KitsModel.Options hidden = KitsModel.options(d, "", null, true);
        assertEquals("Chest 1 \u00b7 3 free", hidden.texts()[0]);
        assertEquals(-1, hidden.selected(), "no first entry and nothing set");
    }

    @Test
    void thePickerIsBuiltOnceAndSharedByTheRows() {
        Data d = data();
        KitsModel.Picker p = new KitsModel.Picker(d, "Kits chest (default)", false);
        KitsModel.Options none = p.select(""), rule = p.select("118,64,-33");
        assertSame(none.texts(), rule.texts(), "the same arrays, whatever the row");
        assertSame(none.values(), rule.values());
        assertEquals(0, none.selected());
        assertEquals("118,64,-33", rule.values()[rule.selected()]);
        assertEquals(3, p.size());
        // a rule whose chest cannot take kits any more still shows it
        KitsModel.Options odd = p.select("130,64,-33");
        assertTrue(odd.texts()[odd.selected()].endsWith("can't take kits"));
        assertEquals(-1, new KitsModel.Picker(d, null, false).select("").selected());
    }

    @Test
    void aSignedNonKitContainerIsWarnedAbout() {
        Data d = data();
        assertTrue(KitsModel.signedNonKit(d.container("118,64,-33")));
        assertFalse(KitsModel.signedNonKit(d.container("120,64,-33")), "no sign");
        assertFalse(KitsModel.signedNonKit(null));
        KitsModel.Container kitSign = new KitsModel.Container("1,64,1", "PvP Kits", 1, true, false);
        assertFalse(KitsModel.signedNonKit(kitSign));
        assertTrue(KitsModel.signedWarning(d.container("118,64,-33"), false).contains("PvP"));
        assertFalse(KitsModel.signedWarning(d.container("118,64,-33"), true).contains("PvP"), "no label in streamer display");
        assertTrue(KitsModel.chestInfo(d, false).contains("Kits without their own chest go to 120, 64, -33"));
        assertTrue(KitsModel.chestInfo(parse("{\"kitsChest\":\"118,64,-33\",\"containers\":[{\"id\":\"118,64,-33\",\"label\":\"PvP\",\"free\":3,\"pickable\":true}]}"), false)
            .contains("sign doesn't say kit"));
        assertEquals("Kits are sorted like any other shulker until you pick a chest.", KitsModel.chestInfo(parse("{\"kitsChest\":null}"), false));
        Row r = d.rows().get(0);
        assertTrue(KitsModel.assignWarning(r, false).contains("PvP Kit"));
        assertFalse(KitsModel.assignWarning(r, true).contains("PvP"));
    }

    @Test
    void errorsAreWordedPerStatus() {
        assertTrue(KitsModel.error(404, null).startsWith("That stash isn't available."));
        assertTrue(KitsModel.error(404, null).contains("update the Stash Manager server"));
        assertEquals("Kits are hidden in streamer mode.", KitsModel.error(403, "streamer"));
        assertTrue(KitsModel.error(503, null).contains("kits.json"));
        assertTrue(KitsModel.error(0, null).contains("Can't reach"));
    }

    @Test
    void rowsAreCappedAtFiveHundred() {
        StringBuilder rows = new StringBuilder("[");
        for (int i = 0; i < 520; i++) rows.append(i > 0 ? "," : "").append("{\"key\":\"kit ").append(i).append("\",\"count\":1,\"locations\":[],\"status\":\"none\"}");
        Data d = parse("{\"rows\":" + rows + "]}");
        assertEquals(500, d.rows().size());
        assertEquals(520, d.totalRows());
        assertEquals("Showing the first 500 of 520 kits.", KitsModel.countNote(d));
        assertEquals("", KitsModel.countNote(data()));
    }

    @Test
    void containersAndLocationsAreCapped() {
        StringBuilder cs = new StringBuilder("[");
        for (int i = 0; i < 5000; i++) cs.append(i > 0 ? "," : "").append("{\"id\":\"").append(i).append(",64,0\",\"pickable\":true,\"free\":1}");
        StringBuilder ls = new StringBuilder("[");
        for (int i = 0; i < 100; i++) ls.append(i > 0 ? "," : "").append("{\"container\":\"").append(i).append(",64,0\",\"count\":1}");
        Data d = parse("{\"containers\":" + cs + "],\"rows\":[{\"key\":\"a kit\",\"locations\":" + ls + "]}]}");
        assertEquals(KitsModel.MAX_CONTAINERS, d.containers().size());
        assertEquals(KitsModel.MAX_LOCATIONS, d.rows().get(0).locations().size());
        assertTrue(new KitsModel.Picker(d, null, false).size() > KitsModel.MAX_CHOICES, "such a list is shown as a plain label");
    }

    @Test
    void whatTheServerSuppliesIsCheckedOnTheWayIn() {
        Data d = parse("{\"kitsChest\":\"not an id\",\"containers\":[{\"id\":\"1,64,1\",\"label\":\"A\\u00a7cB\\u0007\",\"pickable\":true,\"free\":99},{\"id\":\"evil\"},{\"id\":\"1,64,99999999999\"}],"
            + "\"rows\":[{\"key\":\"ok kit\",\"name\":\"\\u00a7aOk\",\"rule\":\"bad\",\"target\":\"1,64,1\",\"locations\":[{\"container\":\"nope\",\"count\":2},{\"container\":\"1,64,1\",\"count\":1}]},"
            + "{\"key\":\"Not Normal\"},{\"key\":\"\"},{\"key\":5},{\"key\":\"fine\"}]}");
        assertEquals("", d.kitsChest(), "an invalid id is replaced by an empty one");
        assertEquals(1, d.containers().size(), "containers with a bad id are dropped");
        assertEquals("AcB", d.containers().get(0).label(), "labels are cleaned (the section sign and control characters go)");
        assertEquals(54, d.containers().get(0).free(), "room is within the size of a container");
        assertEquals(2, d.rows().size(), "rows with an invalid key are dropped: Not Normal, the empty key and the number");
        Row r = d.rows().get(0);
        assertEquals("aOk", r.name());
        assertEquals("", r.rule());
        assertEquals("1,64,1", r.target());
        assertEquals("", r.locations().get(0).container());
        assertEquals("?", KitsModel.place(d, r.locations().get(0).container(), false));
        assertEquals("fine", d.rows().get(1).key());
    }

    @Test
    void scoringEditorValuesAreChecked() {
        assertArrayEquals(new int[] {4, 2, 3, 1, 4, 3}, KitsModel.parseScoring("4", " 2", "3", "1 ", "4", "3"));
        assertNull(KitsModel.parseScoring("0", "2", "3", "1", "4", "3"));
        assertNull(KitsModel.parseScoring("41", "2", "3", "1", "4", "3"));
        assertNull(KitsModel.parseScoring("4", "11", "3", "1", "4", "3"));
        assertNull(KitsModel.parseScoring("4", "-1", "3", "1", "4", "3"));
        assertNull(KitsModel.parseScoring("4", "x", "3", "1", "4", "3"));
        assertNull(KitsModel.parseScoring("4", "", "3", "1", "4", "3"));
        assertNull(KitsModel.parseScoring("4", "2", "3", "1", "4", "0"), "OP items needed is at least 1");
        assertNull(KitsModel.parseScoring("4", "2", "3", "1", "4", "1729"));
        assertNull(KitsModel.parseScoring("4", "2", "3", "1", "4", "12345"), "up to four digits, like the website");
        assertArrayEquals(new int[] {40, 10, 10, 10, 10, 1728}, KitsModel.parseScoring("40", "10", "10", "10", "10", "1728"));
        assertEquals("Highest possible score: 10", KitsModel.maxText(new int[6], 10));
        assertTrue(KitsModel.maxText(null, 10).contains("check the numbers"));
        assertArrayEquals(new int[] {4, 2, 3, 1, 4, 3}, KitsModel.Scoring.DEFAULT.values());
    }

    @Test
    void aKeyIsKeptVerbatimForWritesAndOnlyCleanedForDisplay() {
        String key = "pvp " + (char) 0xE000 + " kit";
        Data d = parse("{\"rows\":[{\"key\":\"" + key + "\"}]}");
        assertEquals(1, d.rows().size());
        Row r = d.rows().get(0);
        assertEquals(key, r.key(), "what a rule or a delete is sent under, so it matches the shulker's rule");
        assertEquals("pvp  kit", r.title(false, 0), "the private-use character is not shown");
        String only = "" + (char) 0xE000;
        Row hidden = parse("{\"rows\":[{\"key\":\"" + only + "\"}]}").rows().get(0);
        assertEquals(only, hidden.key());
        assertEquals("(no visible name)", hidden.title(false, 0));
    }
}
