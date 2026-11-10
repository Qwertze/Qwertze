package com.porter.qwerte.stash;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The stash area editor's pure parts (AREA-CONTRACT.md): the hash, the checks, the derived box, the pending request and its acknowledgement, the sync decision and the heartbeat. */
class AreaTest {
    private static final String OW = "minecraft:overworld";
    private static final int[] BOX = {100, 60, -40, 163, 90, 23};

    // The hash

    @Test
    void theHashesAreTheContractsVectors() {
        assertEquals("2a5139e37493cc1588efdc9d86155c55374e0fc1", AreaRules.hashOf(BOX, OW));
        assertEquals("382410b4646b2c35b8ce6911faf42958ec60b6e2", AreaRules.hashOf(null, null));
        assertEquals(AreaRules.hashOf(null, null), AreaRules.hashOf(null, OW), "no box: no area");
    }

    // The checks

    @Test
    void anAreaIsCheckedForShapeCapsHeightAndDimension() {
        assertNull(AreaRules.validate(BOX, OW, OW));
        assertNull(AreaRules.validate(BOX, OW, "overworld"), "with or without the prefix");
        assertNotNull(AreaRules.validate(new int[] {5, 0, 0, 4, 1, 1}, OW, OW), "min above max");
        assertEquals("that area is 300x20x40; the largest is 256x128x256", AreaRules.validate(new int[] {0, 0, 0, 299, 19, 39}, OW, OW));
        assertNull(AreaRules.validate(new int[] {0, 0, 0, 255, 127, 255}, OW, OW));
        assertNotNull(AreaRules.validate(new int[] {0, 0, 0, 256, 0, 0}, OW, OW));
        assertNotNull(AreaRules.validate(new int[] {0, 0, 0, 0, 128, 0}, OW, OW));
        assertNotNull(AreaRules.validate(new int[] {0, -65, 0, 0, 0, 0}, OW, OW));
        assertNull(AreaRules.validate(new int[] {0, -64, 0, 0, 319, 0}.length == 6 ? new int[] {0, -64, 0, 0, 63, 0} : null, OW, OW));
        assertNotNull(AreaRules.validate(new int[] {0, 0, 0, 0, 256, 0}, "minecraft:the_nether", "minecraft:the_nether"), "the nether stops at 255");
        assertNotNull(AreaRules.validate(new int[] {0, -1, 0, 0, 5, 0}, "minecraft:the_end", "minecraft:the_end"));
        assertNotNull(AreaRules.validate(new int[] {30_000_001, 0, 0, 30_000_001, 0, 0}, OW, OW));
        assertEquals("bad dimension", AreaRules.validate(BOX, "Over World", "Over World"));
        assertNotNull(AreaRules.validate(BOX, OW, "minecraft:the_nether"), "not the stash's dimension");
        assertNotNull(AreaRules.validate(BOX, OW, "other:overworld"), "namespaces are not stripped");
        assertNotNull(AreaRules.validate(BOX, OW, null), "a stash with no known dimension accepts none");
    }

    @Test
    void theAreaObjectOfAReplyIsReadStrictly() throws Exception {
        AreaRules.Parsed p = AreaRules.parse(JsonParser.parseString("{\"box\":[1,2,3,4,5,6],\"dimension\":\"minecraft:overworld\"}"));
        assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6}, p.box());
        for (String bad : new String[] {"[]", "{\"box\":[1,2,3],\"dimension\":\"a:b\"}", "{\"box\":[1,2,3,4,5,\"6\"],\"dimension\":\"a:b\"}",
            "{\"box\":[1,2,3,4,5,6.5],\"dimension\":\"a:b\"}", "{\"box\":[1,2,3,4,5,6],\"dimension\":5}", "{\"box\":[1,2,3,4,5,6]}", "{\"box\":[1,2,3,4,5,99999999999],\"dimension\":\"a:b\"}"}) {
            final String text = bad;
            try {
                AreaRules.parse(JsonParser.parseString(text));
                throw new AssertionError("accepted " + text);
            } catch (AreaRules.Invalid expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    // The derived box

    @Test
    void theDerivedBoxIsTheContainersWidenedAndClamped() {
        assertNull(AreaRules.derived(List.of()));
        assertArrayEquals(new int[] {8, 63, 8, 14, 67, 14}, AreaRules.derived(List.of(new int[] {10, 64, 10}, new int[] {12, 66, 12})), "2 on x and z, 1 on y");
        List<int[]> wide = new ArrayList<>();
        wide.add(new int[] {0, 64, 0});
        wide.add(new int[] {500, 64, 500});
        int[] d = AreaRules.derived(wide);
        assertEquals(256, d[3] - d[0] + 1);
        assertEquals(256, d[5] - d[2] + 1);
        assertNull(AreaRules.validate(d, OW, OW), "what is reported is always a box of the contract");
    }

    // Applying: dimension, far, busy

    @Test
    void anAreaWaitsForTheDimensionTheDistanceAndForTheBotToBeFree() {
        assertEquals(AreaRules.DIMENSION, AreaRules.pendingWhy(BOX, OW, "minecraft:the_nether", true, 120, 0, null));
        assertEquals(AreaRules.FAR, AreaRules.pendingWhy(BOX, OW, OW, true, 164 + 129, 0, null), "more than 128 blocks away");
        assertNull(AreaRules.pendingWhy(BOX, OW, OW, true, 164 + 128, 0, null), "exactly 128 is near enough");
        assertNull(AreaRules.pendingWhy(BOX, OW, OW, false, 5000, 5000, null), "someone who stands at a corner knows where it is");
        assertEquals(AreaRules.BUSY, AreaRules.pendingWhy(BOX, OW, OW, true, 120, 0, AreaRules.BUSY));
        assertNull(AreaRules.pendingWhy(null, null, OW, true, 5000, 5000, null), "a clear has no place");
        assertEquals(0.0, AreaRules.horizontalDistance(BOX, 120, 0));
    }

    // Pending and the edited hash

    private static final String KEY = "2b2t.org/Main";

    @Test
    void aLocalEditIsReportedUntilTheServerAcknowledgesThatVeryHash() {
        AreaPending p = new AreaPending();
        AreaPending.Request r = new AreaPending.Request(BOX, OW, false);
        p.queue(r, KEY);
        assertSame(r, p.get(KEY));
        assertEquals(AreaRules.BUSY, p.why());
        p.setWhy(AreaRules.FAR);
        assertEquals(AreaRules.FAR, p.why());
        assertTrue(p.clear(r));
        assertNull(p.get(KEY));
        p.markEdited(r.hash(), KEY);
        assertTrue(p.edited(KEY));
        assertFalse(p.acknowledge(KEY, "0".repeat(40)), "an older reply does not end it");
        assertFalse(p.acknowledge(KEY, null));
        assertTrue(p.edited(KEY));
        assertTrue(p.acknowledge(KEY, r.hash()));
        assertFalse(p.edited(KEY));
        // A request queued while another was being applied stays
        AreaPending.Request a = new AreaPending.Request(BOX, OW, false), b = new AreaPending.Request(null, null, false);
        p.queue(a, KEY);
        p.queue(b, KEY);
        assertFalse(p.clear(a));
        assertSame(b, p.get(KEY));
        p.discard();
        assertNull(p.get(KEY));
    }

    @Test
    void aRequestAndAnEditBelongToTheirStash() {
        AreaPending p = new AreaPending();
        AreaPending.Request r = new AreaPending.Request(BOX, OW, true);
        p.queue(r, KEY);
        p.markEdited(r.hash(), KEY);
        // Asked about another stash (or another server) they are not there, and an acknowledgement for another stash does nothing
        assertNull(p.get("other.server/Main"));
        assertNull(p.get("2b2t.org/Other"));
        assertNull(p.get(null));
        assertFalse(p.edited("other.server/Main"));
        assertNull(p.editedHash("other.server/Main"));
        assertFalse(p.acknowledge("other.server/Main", r.hash()));
        assertTrue(p.edited(KEY));
        // Moving to another stash forgets the request, but the unacknowledged edit stays with its own stash
        p.dropOthers("other.server/Main");
        assertNull(p.get(KEY));
        assertTrue(p.edited(KEY), "the edit survives a stash switch");
        assertFalse(p.edited("other.server/Main"));
        p.markEdited("1".repeat(40), "other.server/Main");
        assertTrue(p.edited(KEY) && p.edited("other.server/Main"), "one edit per stash");
        // Staying keeps the request
        p.queue(r, KEY);
        p.dropOthers(KEY);
        assertSame(r, p.get(KEY));
        // Teardown and leaving the world: the request goes, the edit is still reported for its stash until acknowledged
        p.reset();
        assertNull(p.get(KEY));
        assertTrue(p.edited(KEY));
        assertEquals(r.hash(), p.editedHash(KEY));
        assertEquals(AreaRules.BUSY, p.why());
        assertTrue(p.acknowledge(KEY, r.hash()));
        assertFalse(p.edited(KEY));
        assertTrue(p.edited("other.server/Main"), "the other stash's edit is untouched");
    }

    @Test
    void leftoversOnlyHoldAnInGameChangeForTenMinutes() {
        AreaHeld h = new AreaHeld();
        long t = 1_000_000;
        assertTrue(h.busy(true, false, false, t), "a task is busy");
        assertFalse(h.busy(false, false, false, t), "nothing is going on");
        assertTrue(h.busy(false, true, false, t), "leftovers hold it at first");
        assertTrue(h.busy(false, true, false, t + AreaHeld.HELD_ONLY_MS - 1));
        assertFalse(h.busy(false, true, false, t + AreaHeld.HELD_ONLY_MS), "then an in-game change goes through");
        assertTrue(h.busy(false, true, true, t + AreaHeld.HELD_ONLY_MS * 5), "the website one always waits");
        // A task in between starts the wait again
        assertTrue(h.busy(true, true, false, t + AreaHeld.HELD_ONLY_MS * 6));
        assertTrue(h.busy(false, true, false, t + AreaHeld.HELD_ONLY_MS * 6 + 1));
        // So does queueing a request (the timer is part of the pending object)
        AreaPending p = new AreaPending();
        p.held().busy(false, true, false, t);
        p.queue(new AreaPending.Request(BOX, OW, false), KEY);
        assertTrue(p.held().busy(false, true, false, t + AreaHeld.HELD_ONLY_MS + 5), "a stale timestamp cannot skip the wait");
    }

    @Test
    void theRequestHashIsMadeOnce() {
        AreaPending.Request r = new AreaPending.Request(BOX, OW, false);
        assertSame(r.hash(), r.hash());
        assertEquals(AreaRules.hashOf(BOX, OW), r.hash());
    }

    @Test
    void aBoxIsWholeNumbersInOrder() throws Exception {
        assertArrayEquals(BOX, AreaRules.boxOf(JsonParser.parseString("[100,60,-40,163,90,23]"), true));
        org.junit.jupiter.api.Assertions.assertThrows(AreaRules.Invalid.class, () -> AreaRules.boxOf(JsonParser.parseString("[1.5,0,0,2,1,1]"), true));
        org.junit.jupiter.api.Assertions.assertThrows(AreaRules.Invalid.class, () -> AreaRules.boxOf(JsonParser.parseString("[5,0,0,4,1,1]"), true));
        assertArrayEquals(new int[] {5, 0, 0, 4, 1, 1}, AreaRules.boxOf(JsonParser.parseString("[5,0,0,4,1,1]"), false), "unordered only when not asked");
    }

    // Dimension forms: "overworld", "minecraft:overworld" and the addon's own "ResourceKey[...]" are one dimension everywhere

    private static final String RAW = "ResourceKey[minecraft:dimension / minecraft:overworld]";

    @Test
    void everyDimensionComparisonIgnoresThePrefix() {
        String want = AreaRules.hashOf(BOX, OW);
        assertEquals(want, AreaRules.hashOf(BOX, "overworld"));
        assertEquals(want, AreaRules.hashOf(BOX, RAW));
        assertEquals(want, AreaRules.hashOf(BOX, "MINECRAFT:overworld".toLowerCase()));
        assertFalse(want.equals(AreaRules.hashOf(BOX, "minecraft:the_nether")));
        assertNull(AreaRules.validate(BOX, "overworld", OW));
        assertNull(AreaRules.validate(BOX, OW, "overworld"));
        assertNull(AreaRules.validate(BOX, OW, RAW));
        assertNotNull(AreaRules.validate(BOX, OW, "the_nether"));
        // The bot's dimension against the area's, in every pairing
        for (String area : new String[] {"overworld", OW, RAW}) {
            for (String bot : new String[] {"overworld", OW, RAW}) {
                assertNull(AreaRules.pendingWhy(BOX, area, bot, true, 130, 0, null), area + " vs " + bot);
            }
            assertEquals(AreaRules.DIMENSION, AreaRules.pendingWhy(BOX, area, "minecraft:the_nether", true, 130, 0, null));
        }
        assertTrue(CraftBounds.sameDimension(RAW, "overworld"));
        assertFalse(CraftBounds.sameDimension("other:overworld", "overworld"));
    }

    @Test
    void anAreaFromTheServerInAnotherFormIsTheSameAreaAndKeepsItsFullId() {
        String inUse = AreaRules.hashOf(BOX, OW);
        // The bot already has this area, the server writes the dimension bare: nothing to do
        assertNull(AreaSync.react(reply(BOX, "overworld"), inUse, null, false, RAW).queue());
        // A new area written bare is queued under the full id, and checked against a stash that stored the bare id
        AreaSync.Action a = AreaSync.react(reply(new int[] {0, 60, 0, 9, 70, 9}, "overworld"), inUse, null, false, "overworld");
        assertNotNull(a.queue());
        assertEquals(OW, a.queue().dimension());
        assertNull(a.warn());
    }

    // The sync decision

    private static StashIndex.AreaReply reply(int[] box, String dim) {
        return new StashIndex.AreaReply(AreaRules.hashOf(box, dim), true, box == null ? null : new AreaRules.Parsed(box, dim), null);
    }

    @Test
    void anAreaFromTheServerIsQueuedOnlyWhenItIsNewValidAndNoLocalEditIsInFlight() {
        String inUse = AreaRules.hashOf(null, null);
        AreaSync.Action a = AreaSync.react(reply(BOX, OW), inUse, null, false, OW);
        assertNotNull(a.queue());
        assertTrue(a.queue().website());
        assertArrayEquals(BOX, a.queue().box());
        // The same as in use, or as already waiting: nothing
        assertNull(AreaSync.react(reply(BOX, OW), AreaRules.hashOf(BOX, OW), null, false, OW).queue());
        assertNull(AreaSync.react(reply(BOX, OW), inUse, a.queue(), false, OW).queue());
        // A local edit waiting, or applied and not acknowledged: the server's area is ignored
        assertNull(AreaSync.react(reply(BOX, OW), inUse, new AreaPending.Request(BOX, OW, false), false, OW).queue());
        assertNull(AreaSync.react(reply(BOX, OW), inUse, null, true, OW).queue());
        // Cleared on the website
        AreaSync.Action clear = AreaSync.react(reply(null, null), AreaRules.hashOf(BOX, OW), null, false, OW);
        assertNotNull(clear.queue());
        assertNull(clear.queue().box());
        // An older server (no area key) says nothing
        assertNull(AreaSync.react(new StashIndex.AreaReply("a".repeat(40), false, null, null), inUse, null, false, OW).queue());
        // Invalid: ignored and said once
        AreaSync.Action bad = AreaSync.react(new StashIndex.AreaReply(null, true, null, "box is not 6 numbers"), inUse, null, false, OW);
        assertNull(bad.queue());
        assertEquals("box is not 6 numbers", bad.warn());
        AreaSync.Action tall = AreaSync.react(reply(new int[] {0, 0, 0, 0, 200, 0}, OW), inUse, null, false, OW);
        assertNull(tall.queue());
        assertNotNull(tall.warn());
        AreaSync.Action other = AreaSync.react(reply(BOX, "minecraft:the_nether"), inUse, null, false, OW);
        assertNull(other.queue(), "another dimension than the stash's");
        assertEquals("c", AreaSync.resolveDimension(null, "c"));
        assertEquals("q", AreaSync.resolveDimension("q", "c"));
    }

    // The heartbeat

    private static final StashIndex.Built BUILT = new StashIndex.Built("2b2t.org", "stash", "Bot", "ix", new byte[0]);

    private static JsonObject beat(StashIndex.Built built, StashIndex.AreaReport area) {
        byte[] body = StashIndex.heartbeat(built, null, null, -1, null, null, null, null, null, null, null, null, null, area);
        return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void theHeartbeatCarriesTheAreaFieldsExactly() {
        StashIndex.AreaReport set = new StashIndex.AreaReport(AreaRules.hashOf(BOX, OW), BOX, OW, null, true, "b".repeat(40), AreaRules.FAR, 3);
        JsonObject a = beat(BUILT, set).getAsJsonObject("area");
        assertEquals(1, a.get("v").getAsInt());
        assertEquals(AreaRules.hashOf(BOX, OW), a.get("hash").getAsString());
        assertEquals(OW, a.getAsJsonObject("area").get("dimension").getAsString());
        assertEquals(6, a.getAsJsonObject("area").getAsJsonArray("box").size());
        assertTrue(a.get("derived").isJsonNull());
        assertTrue(a.get("edited").getAsBoolean());
        assertEquals("far", a.getAsJsonObject("pending").get("why").getAsString());
        assertEquals(3, a.get("containersOutside").getAsInt());
        StashIndex.AreaReport none = new StashIndex.AreaReport(AreaRules.hashOf(null, null), null, null, new int[] {1, 2, 3, 4, 5, 6}, false, null, null, -4);
        JsonObject b = beat(BUILT, none).getAsJsonObject("area");
        assertTrue(b.get("area").isJsonNull());
        assertEquals(6, b.getAsJsonArray("derived").size());
        assertFalse(b.get("edited").getAsBoolean());
        assertTrue(b.get("pending").isJsonNull());
        assertEquals(0, b.get("containersOutside").getAsInt());
        // Without a player the area is left out; without area support nothing is said
        assertFalse(beat(new StashIndex.Built("s", "t", "", "ix", new byte[0]), set).has("area"));
        assertFalse(beat(BUILT, null).has("area"));
    }

    @Test
    void aReplyIsReadForItsHashAndItsArea() {
        String h = AreaRules.hashOf(BOX, OW);
        StashIndex.AreaReply r = StashIndex.areaReply("{\"areaHash\":\"" + h + "\",\"area\":{\"box\":[100,60,-40,163,90,23],\"dimension\":\"minecraft:overworld\"}}");
        assertEquals(h, r.hash());
        assertTrue(r.areaPresent());
        assertArrayEquals(BOX, r.area().box());
        StashIndex.AreaReply cleared = StashIndex.areaReply("{\"areaHash\":\"" + h + "\",\"area\":null}");
        assertTrue(cleared.areaPresent());
        assertNull(cleared.area());
        StashIndex.AreaReply onlyHash = StashIndex.areaReply("{\"areaHash\":\"" + h + "\"}");
        assertFalse(onlyHash.areaPresent());
        assertEquals(h, onlyHash.hash());
        assertNotNull(StashIndex.areaReply("{\"area\":{\"box\":[1]}}").invalid());
        assertFalse(StashIndex.areaReply("not json").areaPresent());
        assertNull(StashIndex.areaReply("{\"areaHash\":\"XYZ\"}").hash());
    }

    // The editing operations

    private static final AreaEdit.Draft D = AreaEdit.Draft.ofBox(new int[] {0, 60, 0, 31, 75, 31}, OW);

    @Test
    void cornersKeepTheOtherCornerAndAnOverCapResultIsRefusedWithTheDraftUnchanged() {
        AreaEdit.Result r = AreaEdit.corner(D, 1, 10, 62, 5);
        assertTrue(r.ok());
        assertArrayEquals(new int[] {10, 62, 5}, r.draft().c1());
        assertArrayEquals(new int[] {31, 75, 31}, r.draft().c2());
        assertArrayEquals(new int[] {10, 62, 5, 31, 75, 31}, r.draft().box());
        // Corner 2 beyond corner 1: the box is the span
        assertArrayEquals(new int[] {0, 55, 0, 31, 60, 31}, AreaEdit.corner(D, 2, 31, 55, 31).draft().box().length == 6 ? new int[] {0, 55, 0, 31, 60, 31} : null);
        AreaEdit.Result far = AreaEdit.corner(D, 2, 400, 70, 20);
        assertFalse(far.ok());
        assertTrue(far.refused().contains("the largest is 256x128x256"), far.refused());
        assertSame(D, far.draft(), "unchanged");
        assertFalse(AreaEdit.corner(D, 1, 0, 400, 0).ok(), "above the build limit");
        assertFalse(AreaEdit.corner(D, 1, 0, -65, 0).ok());
    }

    @Test
    void expandGrowsAndShrinksPerSideOrAllSidesWithinTheLimits() {
        assertArrayEquals(new int[] {-5, 60, 0, 31, 75, 31}, AreaEdit.expand(D, AreaEdit.Side.NEG_X, 5).draft().box());
        assertArrayEquals(new int[] {0, 60, 0, 36, 75, 31}, AreaEdit.expand(D, AreaEdit.Side.POS_X, 5).draft().box());
        assertArrayEquals(new int[] {0, 60, -5, 31, 75, 31}, AreaEdit.expand(D, AreaEdit.Side.NEG_Z, 5).draft().box());
        assertArrayEquals(new int[] {0, 60, 0, 31, 75, 36}, AreaEdit.expand(D, AreaEdit.Side.POS_Z, 5).draft().box());
        assertArrayEquals(new int[] {0, 55, 0, 31, 75, 31}, AreaEdit.expand(D, AreaEdit.Side.DOWN, 5).draft().box());
        assertArrayEquals(new int[] {0, 60, 0, 31, 80, 31}, AreaEdit.expand(D, AreaEdit.Side.UP, 5).draft().box());
        assertArrayEquals(new int[] {-5, 55, -5, 36, 80, 36}, AreaEdit.expand(D, AreaEdit.Side.ALL, 5).draft().box());
        // Shrink
        assertArrayEquals(new int[] {3, 60, 0, 31, 75, 31}, AreaEdit.expand(D, AreaEdit.Side.NEG_X, -3).draft().box());
        assertArrayEquals(new int[] {3, 63, 3, 28, 72, 28}, AreaEdit.expand(D, AreaEdit.Side.ALL, -3).draft().box());
        // Never below one block on an axis
        int[] one = AreaEdit.expand(D, AreaEdit.Side.POS_X, -255).draft().box();
        assertEquals(one[0], one[3]);
        assertEquals(0, one[0]);
        int[] all = AreaEdit.expand(D, AreaEdit.Side.ALL, -255).draft().box();
        assertEquals(all[0], all[3]);
        assertEquals(all[1], all[4]);
        assertEquals(all[2], all[5]);
        // N out of range, and over the caps
        assertFalse(AreaEdit.expand(D, AreaEdit.Side.ALL, 256).ok());
        assertFalse(AreaEdit.expand(D, AreaEdit.Side.ALL, -256).ok());
        AreaEdit.Result big = AreaEdit.expand(D, AreaEdit.Side.ALL, 200);
        assertFalse(big.ok());
        assertSame(D, big.draft());
        assertFalse(AreaEdit.expand(D, AreaEdit.Side.UP, 120).ok(), "128 blocks high at most");
        assertTrue(AreaEdit.expand(D, AreaEdit.Side.UP, 48).ok());
    }

    @Test
    void includeWhereIStandHoldsTheFeetBlockAndTheOneAbove() {
        assertSame(D.box().length, 6);
        AreaEdit.Result inside = AreaEdit.include(D, 5, 70, 5);
        assertArrayEquals(D.box(), inside.draft().box(), "already inside: nothing grows");
        assertArrayEquals(new int[] {0, 60, 0, 31, 76, 31}, AreaEdit.include(D, 5, 75, 5).draft().box(), "the block above the feet is in too");
        assertArrayEquals(new int[] {-10, 60, 0, 31, 75, 40}, AreaEdit.include(D, -10, 70, 40).draft().box());
        assertArrayEquals(new int[] {0, 50, 0, 31, 75, 31}, AreaEdit.include(D, 5, 50, 5).draft().box());
        assertFalse(AreaEdit.include(D, 500, 70, 5).ok());
    }

    @Test
    void aMapDragSetsXAndZAndKeepsY() {
        AreaEdit.Result r = AreaEdit.mapDrag(D, 100, 40, 50, -20);
        assertArrayEquals(new int[] {50, 60, -20, 100, 75, 40}, r.draft().box());
        assertFalse(AreaEdit.mapDrag(D, 0, 0, 300, 10).ok());
    }

    @Test
    void anEditorStartsFromTheSetAreaThenTheDerivedOneThenTheBoxAroundThePlayer() {
        assertArrayEquals(BOX, AreaEdit.start(BOX, new int[] {1, 1, 1, 2, 2, 2}, OW, 0, 64, 0).box());
        assertArrayEquals(new int[] {1, 1, 1, 2, 2, 2}, AreaEdit.start(null, new int[] {1, 1, 1, 2, 2, 2}, OW, 0, 64, 0).box());
        int[] around = AreaEdit.start(null, null, OW, 100, 64, -200).box();
        assertEquals(32, around[3] - around[0] + 1);
        assertEquals(16, around[4] - around[1] + 1);
        assertEquals(32, around[5] - around[2] + 1);
        assertTrue(around[0] <= 100 && around[3] >= 100 && around[1] <= 64 && around[4] >= 64 && around[2] <= -200 && around[5] >= -200);
        // Near the floor of the nether the box is moved inside the limits
        int[] nether = AreaEdit.start(null, null, "minecraft:the_nether", 0, 2, 0).box();
        assertTrue(nether[1] >= 0);
        assertNull(AreaEdit.check(nether, "minecraft:the_nether"));
        int[] top = AreaEdit.start(null, null, OW, 0, 319, 0).box();
        assertTrue(top[4] <= 319);
        assertEquals("32×16×32", AreaEdit.Draft.ofBox(around, OW).size());
    }
}
