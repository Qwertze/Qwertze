package com.porter.qwerte.stash;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the heartbeat says about kits, and how the reply is read. */
class KitHeartbeatTest {
    private static final String HASH = "a".repeat(40);
    private static final StashIndex.Built BUILT = new StashIndex.Built("2b2t.org", "stash", "Qwerttze", "ix", new byte[0]);

    private static JsonObject beat(StashIndex.KitsHashes kits) {
        byte[] body = StashIndex.heartbeat(BUILT, null, null, -1, null, null, null, null, null, null, null, kits);
        return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void withKitSortingOffNoKitKeysAreSent() {
        JsonObject o = beat(null);
        assertFalse(o.has("kitsHash"));
        assertFalse(o.has("kitsReportHash"));
        assertEquals("stash", o.get("stash").getAsString(), "the rest of the heartbeat is as before");
    }

    @Test
    void bothKeysAreAlwaysSentWhenOnWithNullForNothing() {
        JsonObject none = beat(new StashIndex.KitsHashes(null, null));
        assertTrue(none.has("kitsHash") && none.get("kitsHash").isJsonNull());
        assertTrue(none.has("kitsReportHash") && none.get("kitsReportHash").isJsonNull());
        JsonObject both = beat(new StashIndex.KitsHashes(HASH, "b".repeat(40)));
        assertEquals(HASH, both.get("kitsHash").getAsString());
        assertEquals("b".repeat(40), both.get("kitsReportHash").getAsString());
    }

    @Test
    void theOlderOverloadsStillWork() {
        byte[] body = StashIndex.heartbeat(BUILT, null, null, -1, null, null, null, null);
        assertFalse(new String(body, StandardCharsets.UTF_8).contains("kitsHash"));
    }

    @Test
    void aReplyWithoutKitFieldsSaysNothing() {
        StashIndex.KitsReply r = StashIndex.kitsReply("{\"needIndex\":false}");
        assertFalse(r.reportKnown());
        assertFalse(r.needReport());
        assertNull(r.settings());
        assertNull(r.invalid());
        assertFalse(StashIndex.kitsReply("not json").reportKnown());
        assertFalse(StashIndex.kitsReply("[]").reportKnown());
    }

    @Test
    void theNeedKitsReportKeyMakesTheReportRouteKnown() {
        StashIndex.KitsReply no = StashIndex.kitsReply("{\"kitsHash\":\"" + HASH + "\",\"needKitsReport\":false}");
        assertTrue(no.reportKnown());
        assertFalse(no.needReport());
        StashIndex.KitsReply yes = StashIndex.kitsReply("{\"needKitsReport\":true}");
        assertTrue(yes.reportKnown() && yes.needReport());
        assertFalse(StashIndex.kitsReply("{\"needKitsReport\":\"yes\"}").reportKnown(), "a wrong type says nothing");
    }

    @Test
    void aKitsObjectIsReadAndAnInvalidOneRefusedWhole() {
        String okHash = KitSettings.hashOf("1,64,1", new KitSettings.Scoring(5, 1, 2, 3, 4, 3), java.util.Map.of("pvp kit", "2,64,2"));
        String ok = "{\"rev\":3,\"hash\":\"" + okHash + "\",\"kitsChest\":\"1,64,1\",\"rules\":{\"pvp kit\":\"2,64,2\"},"
            + "\"scoring\":{\"threshold\":5,\"name\":1,\"mix\":2,\"full\":3,\"op\":4}}";
        StashIndex.KitsReply r = StashIndex.kitsReply("{\"needKitsReport\":false,\"kits\":" + ok + "}");
        assertNotNull(r.settings());
        assertEquals(3, r.settings().rev());
        assertEquals(new KitSettings.Scoring(5, 1, 2, 3, 4, 3), r.settings().scoring(), "no opMin means 3");
        assertNull(r.invalid());

        StashIndex.KitsReply bad = StashIndex.kitsReply("{\"needKitsReport\":true,\"kits\":{\"rev\":3,\"hash\":\"" + HASH + "\",\"kitsChest\":\"nope\",\"rules\":{}}}");
        assertNull(bad.settings());
        assertNotNull(bad.invalid());
        assertTrue(bad.reportKnown(), "the rest of the reply still counts");

        StashIndex.KitsReply range = StashIndex.kitsReply("{\"kits\":{\"rev\":1,\"hash\":\"" + HASH + "\",\"kitsChest\":null,\"rules\":{},\"scoring\":{\"threshold\":0,\"name\":2,\"mix\":3,\"full\":1,\"op\":4}}}");
        assertNull(range.settings());
        assertNotNull(range.invalid());
    }

    @Test
    void settingsWhoseHashIsNotTheirOwnAreRefused() {
        StashIndex.KitsReply r = StashIndex.kitsReply("{\"kits\":{\"rev\":1,\"hash\":\"" + HASH + "\",\"kitsChest\":null,\"rules\":{}}}");
        assertNull(r.settings());
        assertNotNull(r.invalid());
    }

    @Test
    void settingsWithoutScoringGetTheDefaults() {
        StashIndex.KitsReply r = StashIndex.kitsReply("{\"kits\":{\"rev\":1,\"hash\":\"" + KitSettings.EMPTY.hash() + "\",\"kitsChest\":null,\"rules\":{}}}");
        assertEquals(KitSettings.Scoring.DEFAULT, r.settings().scoring());
    }
}
