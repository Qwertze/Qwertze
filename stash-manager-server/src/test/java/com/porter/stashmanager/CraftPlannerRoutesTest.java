package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.stashmanager.planner.PlannerFixtures;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The build planner with crafting (CRAFT-CONTRACT.md, "Planner additions"): rows, totals, the craft object, the split, the cache. */
class CraftPlannerRoutesTest extends CraftHarness {
    static final int DV = 4189; // 1.21.4

    /** 8 stairs, 6 slabs, 3 fences (all made from spruce) and 5 stone (made in a furnace). */
    static byte[] build() {
        Map<String, Integer> blocks = new LinkedHashMap<>();
        blocks.put("minecraft:spruce_stairs", 8);
        blocks.put("minecraft:spruce_slab", 6);
        blocks.put("minecraft:spruce_fence", 3);
        blocks.put("minecraft:stone", 5);
        return PlannerFixtures.structure(DV, blocks, null);
    }

    String upload(String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/v1/planner/upload?server=s&stash=a&name=Test.nbt"))
            .header("X-Stash-Manager", "1").header("Content-Type", "application/octet-stream")
            .header("Cookie", SessionStore.COOKIE + "=" + token).POST(HttpRequest.BodyPublishers.ofByteArray(build())).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return com.google.gson.JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("plan").get("id").getAsString();
    }

    JsonObject view(String id, String options) throws Exception {
        return ok(post(owner, "/api/v1/planner/" + id + "/view", "{" + KEY + (options == null ? "" : "," + options) + "}")).getAsJsonObject("plan");
    }

    static JsonObject row(JsonObject plan, String id) {
        for (JsonElement e : plan.getAsJsonArray("items")) if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
        return null;
    }

    JsonObject dry(String id, String options) throws Exception {
        return ok(post(owner, "/api/v1/planner/" + id + "/order", "{" + KEY + ",\"dryRun\":true" + (options == null ? "" : "," + options) + "}"));
    }

    // ---------- the view ----------

    @Test
    void rowsAndTotalsCarryTheCraftNumbersAndMissingIsUnchanged() throws Exception {
        start();
        ready(2048);
        JsonObject plan = view(upload(owner), null);
        JsonObject c = plan.getAsJsonObject("craft");
        assertTrue(c.get("available").getAsBoolean());
        assertTrue(c.get("canOrder").getAsBoolean());
        assertTrue(c.get("reason").isJsonNull());
        assertEquals(MC, c.getAsJsonObject("recipes").get("mc").getAsString());
        assertEquals("ready", c.getAsJsonObject("recipes").get("state").getAsString());
        assertEquals("ok", c.get("tableStatus").getAsString());
        assertTrue(c.getAsJsonObject("bot").get("supports").getAsBoolean());
        assertTrue(c.getAsJsonObject("bot").get("recipesMatch").getAsBoolean());
        assertFalse(c.get("truncated").getAsBoolean());
        assertFalse(plan.getAsJsonObject("options").get("craft").getAsBoolean());
        for (String id : new String[]{"minecraft:spruce_stairs", "minecraft:spruce_slab", "minecraft:spruce_fence"}) {
            JsonObject r = row(plan, id);
            assertEquals(r.get("need").getAsLong(), r.get("craftable").getAsLong(), id);
            assertEquals("full", r.get("craftStatus").getAsString(), id);
            assertTrue(r.get("craftReason").isJsonNull());
            assertEquals(0, r.get("missingAfterCraft").getAsLong());
            assertEquals(r.get("need").getAsLong(), r.get("missing").getAsLong(), "missing stays need - available");
            assertFalse(r.getAsJsonArray("craftSteps").isEmpty());
            assertFalse(r.getAsJsonArray("craftUses").isEmpty());
        }
        JsonObject stone = row(plan, "minecraft:stone");
        assertEquals(0, stone.get("craftable").getAsLong());
        assertEquals("station", stone.get("craftStatus").getAsString());
        assertEquals("needs a furnace", stone.get("craftReason").getAsString());
        assertEquals(5, stone.get("missingAfterCraft").getAsLong());
        JsonObject totals = plan.getAsJsonObject("totals");
        assertEquals(17, totals.get("craftable").getAsLong());
        assertEquals(3, totals.get("craftKinds").getAsLong());
        assertEquals(5, totals.get("missingAfterCraft").getAsLong());
        assertEquals(22, totals.get("missing").getAsLong());
        assertTrue(totals.get("craftFills").getAsLong() > 0);
        // The row's own steps (made and fills), its stash takes, and what it leaves over.
        JsonObject slab = row(plan, "minecraft:spruce_slab");
        boolean madeSlab = false;
        for (JsonElement e : slab.getAsJsonArray("craftSteps")) {
            if (e.getAsJsonObject().get("recipe").getAsString().equals("minecraft:spruce_slab")) {
                madeSlab = true;
                assertEquals(6, e.getAsJsonObject().get("made").getAsLong());
                assertEquals(1, e.getAsJsonObject().get("fills").getAsLong());
            }
        }
        assertTrue(madeSlab);
        assertNotNull(slab.get("craftLeftovers"));
    }

    @Test
    void withoutACraftSourceOrAStashThereAreNoCraftFields() throws Exception {
        start();
        ready(2048);
        String id = upload(owner);
        // No stash chosen (an upload without server and stash): no craft object, no row fields.
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/v1/planner/upload?name=Test.nbt"))
            .header("X-Stash-Manager", "1").header("Content-Type", "application/octet-stream")
            .header("Cookie", SessionStore.COOKIE + "=" + owner).POST(HttpRequest.BodyPublishers.ofByteArray(build())).build();
        JsonObject bare = com.google.gson.JsonParser.parseString(http.send(req, HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject().getAsJsonObject("plan");
        assertTrue(bare.get("craft").isJsonNull());
        assertFalse(row(bare, "minecraft:spruce_slab").has("craftable"));
        assertFalse(bare.getAsJsonObject("totals").has("craftable"));
        // Recipes still being built: available false, the rows carry nothing.
        recipes.state = "building";
        JsonObject building = view(id, null);
        assertFalse(building.getAsJsonObject("craft").get("available").getAsBoolean());
        assertEquals("no_recipes", building.getAsJsonObject("craft").get("reason").getAsString());
        assertEquals("building", building.getAsJsonObject("craft").getAsJsonObject("recipes").get("state").getAsString());
        assertFalse(row(building, "minecraft:spruce_slab").has("craftable"));
        assertFalse(building.getAsJsonObject("totals").has("craftable"));
        assertEquals(6, row(building, "minecraft:spruce_slab").get("missing").getAsLong());
        recipes.state = "ready";
        assertTrue(row(view(id, null), "minecraft:spruce_slab").has("craftable"));
    }

    @Test
    void whyCraftingCannotBeOrderedIsInTheCraftObjectWhileTheNumbersStay() throws Exception {
        start();
        String id = upload(owner);
        JsonObject c = view(id, "\"craft\":true").getAsJsonObject("craft");
        assertFalse(c.get("canOrder").getAsBoolean());
        assertEquals("no_bot", c.get("reason").getAsString());
        assertTrue(c.get("message").getAsString().contains("StashSorter 1.15.0"));
        assertEquals(6, row(view(id, "\"craft\":true"), "minecraft:spruce_slab").get("craftable").getAsLong());
        // The option does nothing to the order while the stash can't take it: item lines only (here none).
        JsonObject d = dry(id, "\"craft\":true");
        assertEquals(0, d.getAsJsonArray("parts").size());
        assertEquals("no_bot", d.getAsJsonObject("craft").get("reason").getAsString());
        setTable(1, 2, 3);
        ok(heartbeat("\"craftOrders\":" + cap(tableHash(), "ok", 64)));
        assertEquals("table_bad", view(id, null).getAsJsonObject("craft").get("reason").getAsString(), "the bot has not picked up the table yet");
    }

    // ---------- the order ----------

    @Test
    void theCraftOptionPutsCraftLinesInTheOrderAndTheSplitKeepsThemFirst() throws Exception {
        start();
        ready(2048);
        String id = upload(owner);
        JsonObject withCraft = view(id, "\"craft\":true");
        assertTrue(withCraft.getAsJsonObject("options").get("craft").getAsBoolean());
        assertEquals(1, withCraft.getAsJsonObject("totals").get("parts").getAsInt());
        assertEquals(3, withCraft.getAsJsonObject("totals").get("lines").getAsInt());
        assertEquals(0, view(id, null).getAsJsonObject("totals").get("lines").getAsInt(), "without the option nothing is ordered");
        JsonObject d = dry(id, "\"craft\":true");
        JsonObject part = d.getAsJsonArray("parts").get(0).getAsJsonObject();
        JsonArray lines = part.getAsJsonArray("lines");
        assertEquals(3, lines.size());
        for (JsonElement l : lines) assertEquals("craft", l.getAsJsonObject().get("type").getAsString());
        assertEquals(17, part.get("items").getAsInt());
        JsonObject summary = part.getAsJsonObject("craft");
        assertEquals(3, summary.get("lines").getAsInt());
        assertTrue(summary.get("fills").getAsInt() > 0);
        assertFalse(summary.getAsJsonArray("take").isEmpty());
        boolean takesLogs = false;
        for (JsonElement t : summary.getAsJsonArray("take")) takesLogs |= t.getAsJsonObject().get("id").getAsString().equals("minecraft:spruce_log");
        assertTrue(takesLogs);
        JsonObject stoneMissing = null;
        for (JsonElement e : d.getAsJsonArray("missing")) if (e.getAsJsonObject().get("id").getAsString().equals("minecraft:stone")) stoneMissing = e.getAsJsonObject();
        assertEquals(5, stoneMissing.get("missingAfterCraft").getAsLong());
        // Place it: the order carries the craft plan and reserves the take.
        JsonObject placed = ok(post(owner, "/api/v1/planner/" + id + "/order", "{" + KEY + ",\"craft\":true}")).getAsJsonObject("order");
        assertEquals(3, placed.getAsJsonArray("lines").size());
        JsonObject craft = placed.getAsJsonObject("craft");
        assertEquals(summary.get("fills").getAsInt(), craft.get("fills").getAsInt());
        assertEquals(summary.getAsJsonArray("take"), craft.getAsJsonArray("take"), "the order took what the dry run said");
        assertTrue(availableItem("minecraft:spruce_log").getAsLong() < 10);
        // The options are locked while the part is live: another choice of the craft option is refused.
        Res locked = post(owner, "/api/v1/planner/" + id + "/order", "{" + KEY + ",\"craft\":false,\"dryRun\":true}");
        assertEquals(409, locked.status);
        assertEquals(PlannerRoutes.OPTIONS_LOCKED, locked.error());
        // The view of the placed build shows the same split (frozen) and the lines are craft lines.
        JsonObject after = view(id, "\"craft\":true");
        assertTrue(after.getAsJsonObject("ordered").getAsJsonObject("options").get("craft").getAsBoolean());
        assertTrue(after.getAsJsonObject("ordered").get("sameOptions").getAsBoolean());
    }

    @Test
    void theSplitKeepsEachPartWithinTheBotsFillCapAndEveryPartCanBePlaced() throws Exception {
        start();
        ready(3);
        String id = upload(owner);
        JsonObject d = dry(id, "\"craft\":true");
        JsonArray parts = d.getAsJsonArray("parts");
        assertTrue(parts.size() > 1, "the build needs more than 3 grid fills, so it takes several parts");
        long craftedTotal = 0;
        for (JsonElement p : parts) {
            JsonObject summary = p.getAsJsonObject().getAsJsonObject("craft");
            assertNotNull(summary);
            assertTrue(summary.get("fills").getAsInt() <= 3, summary.toString());
            for (JsonElement l : p.getAsJsonObject().getAsJsonArray("lines")) craftedTotal += l.getAsJsonObject().get("count").getAsLong();
        }
        assertEquals(17, craftedTotal, "every craftable item is in some part");
        // Every part places, one after the other: each is planned with what the earlier ones took.
        for (int i = 1; i <= parts.size(); i++) {
            Res r = post(owner, "/api/v1/planner/" + id + "/order", "{" + KEY + ",\"craft\":true,\"part\":" + i + "}");
            assertEquals(200, r.status, "part " + i + ": " + r.body);
            assertTrue(r.obj().getAsJsonObject("order").getAsJsonObject("craft").get("fills").getAsInt() <= 3);
        }
    }

    @Test
    void aCapOfOneFillStillSplitsLegally() throws Exception {
        start();
        ready(1);
        JsonObject d = dry(upload(owner), "\"craft\":true");
        for (JsonElement p : d.getAsJsonArray("parts")) assertTrue(p.getAsJsonObject().getAsJsonObject("craft").get("fills").getAsInt() <= 1);
    }

    @Test
    void neverUseAndExcludedItemsChangeThePlan() throws Exception {
        start();
        ready(2048);
        String id = upload(owner);
        assertEquals("full", row(view(id, null), "minecraft:spruce_slab").get("craftStatus").getAsString());
        // Logs are not to be used: only the two planks in the stash are left, and a fill of the slab recipe needs three.
        ok(post(owner, "/api/v1/craft/never-use", "{" + KEY + ",\"ids\":[\"minecraft:spruce_log\"]}"));
        JsonObject never = view(id, null);
        assertEquals("short", row(never, "minecraft:spruce_slab").get("craftStatus").getAsString());
        assertEquals(0, row(never, "minecraft:spruce_slab").get("craftable").getAsLong());
        assertEquals(6, row(never, "minecraft:spruce_slab").get("missingAfterCraft").getAsLong());
        assertTrue(row(never, "minecraft:spruce_slab").get("craftReason").getAsString().startsWith("not enough"));
        // Planks too: no recipe is left for a slab at all, since its only ingredient is never used.
        ok(post(owner, "/api/v1/craft/never-use", "{" + KEY + ",\"ids\":[\"minecraft:spruce_log\",\"minecraft:spruce_planks\"]}"));
        never = view(id, null);
        assertEquals("no_recipe", row(never, "minecraft:spruce_slab").get("craftStatus").getAsString());
        assertEquals(0, never.getAsJsonObject("totals").get("craftable").getAsLong());
        ok(post(owner, "/api/v1/craft/never-use", "{" + KEY + ",\"ids\":[]}"));
        JsonObject excluded = view(id, "\"exclude\":[\"minecraft:spruce_stairs\"]");
        assertEquals("excluded", row(excluded, "minecraft:spruce_stairs").get("craftStatus").getAsString());
        assertEquals(0, row(excluded, "minecraft:spruce_stairs").get("craftable").getAsLong());
        assertEquals(9, excluded.getAsJsonObject("totals").get("craftable").getAsLong(), "slabs and fences only");
        assertEquals(2, excluded.getAsJsonObject("totals").get("craftKinds").getAsLong());
    }

    // ---------- the cache ----------

    @Test
    void thePlanIsComputedOnceUntilSomethingItDependsOnChanges() throws Exception {
        start();
        ready(2048);
        String id = upload(owner);
        view(id, null);
        int base = craft.planners.get();
        view(id, null);
        view(id, null);
        ok(get(owner, "/api/v1/planner/" + id + "?server=s&stash=a"));
        assertEquals(base, craft.planners.get(), "the same request is answered from the cache");
        // Other options, a changed index, a new reservation, new settings and another recipe table are each planned again.
        view(id, "\"multiplier\":2");
        assertEquals(base + 1, craft.planners.get());
        view(id, "\"multiplier\":2");
        assertEquals(base + 1, craft.planners.get());
        view(id, null);
        assertEquals(base + 2, craft.planners.get(), "one entry is kept: going back plans again");
        store.putIndex(json(indexJson().replace("\"count\":10,", "\"count\":11,")));
        view(id, null);
        assertEquals(base + 3, craft.planners.get(), "a new index");
        view(id, null);
        assertEquals(base + 3, craft.planners.get());
        ok(placeOrder("[" + craftLine("minecraft:spruce_slab", 6) + "]"));
        int afterOrder = craft.planners.get();
        view(id, null);
        assertEquals(afterOrder + 1, craft.planners.get(), "the order reserved logs");
        ok(post(owner, "/api/v1/craft/never-use", "{" + KEY + ",\"ids\":[\"minecraft:dirt\"]}"));
        view(id, null);
        assertEquals(afterOrder + 2, craft.planners.get(), "a changed never-use list");
        // The status is read fresh every time, even from the cache: the bot going away shows at once.
        ok(heartbeat("\"craftOrders\":null"));
        int n = craft.planners.get();
        JsonObject gone = view(id, null);
        assertEquals("no_bot", gone.getAsJsonObject("craft").get("reason").getAsString());
        assertEquals(n, craft.planners.get(), "no new plan for that");
        assertTrue(gone.getAsJsonObject("totals").has("craftable"));
    }

    @Test
    void anOldPageThatKnowsNothingAboutCraftStillGetsEverythingItReads() throws Exception {
        start();
        String id = upload(owner);
        JsonObject plan = ok(get(owner, "/api/v1/planner/" + id + "?server=s&stash=a")).getAsJsonObject("plan");
        for (String f : new String[]{"id", "items", "totals", "options", "ordered", "stash", "fluids", "containerItems", "unknown"}) assertTrue(plan.has(f), f);
        JsonObject slab = row(plan, "minecraft:spruce_slab");
        for (String f : new String[]{"id", "name", "base", "need", "from", "inIndex", "inStash", "available", "missing", "want", "maxStack", "variants", "excluded"}) {
            assertTrue(slab.has(f), f);
        }
        // An order without the option places only item lines, as before.
        store.putIndex(json(indexJson()));
        Res none = post(owner, "/api/v1/planner/" + id + "/order", "{" + KEY + "}");
        assertEquals(400, none.status, "nothing to order: the stash has none of these");
        assertTrue(none.error().startsWith("nothing to order"));
    }
}
