package com.porter.qwerte.printer.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.render.PhantomCache.Action;
import com.porter.qwerte.printer.render.PhantomCache.Entry;
import com.porter.qwerte.printer.render.PhantomSelect.Cover;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhantomCacheTest {
    private final List<String> freed = new ArrayList<>();
    private final PhantomCache<String> cache = new PhantomCache<>(freed::add);

    private Entry<String> built(int sx, int sy, int sz, Cover c, int ox, int oy, int oz, long lim, Integer layer) {
        Entry<String> e = cache.getOrCreate(sx, sy, sz);
        e.built = true;
        e.cover = c;
        e.ox = ox;
        e.oy = oy;
        e.oz = oz;
        e.limit2 = lim;
        e.layer = layer;
        e.mesh = "m" + sx + sy + sz;
        return e;
    }

    @Test
    void anUnbuiltOrDirtySectionIsBuilt() {
        assertEquals(Action.BUILD, PhantomCache.decide(null, Cover.ALL, 0, 0, 0, 100, null));
        Entry<String> e = cache.getOrCreate(0, 0, 0);
        assertEquals(Action.BUILD, PhantomCache.decide(e, Cover.ALL, 0, 0, 0, 100, null));
        built(0, 0, 0, Cover.ALL, 0, 0, 0, 100, null);
        assertEquals(Action.KEEP, PhantomCache.decide(e, Cover.ALL, 0, 0, 0, 100, null));
        e.dirty = true;
        assertEquals(Action.BUILD, PhantomCache.decide(e, Cover.ALL, 0, 0, 0, 100, null));
    }

    @Test
    void aWhollyCoveredSectionIgnoresPlayerMovementAndTheLimit() {
        Entry<String> e = built(0, 0, 0, Cover.ALL, 0, 0, 0, 1000, null);
        assertEquals(Action.KEEP, PhantomCache.decide(e, Cover.ALL, 5, 0, 5, 900, null));
    }

    @Test
    void aCutSectionIsKeptForSmallMovesAndRebuiltAfterABigOne() {
        Entry<String> e = built(3, 0, 0, Cover.PARTIAL, 0, 0, 0, 1000, null);
        assertEquals(Action.KEEP, PhantomCache.decide(e, Cover.PARTIAL, 0, 0, 0, 1000, null));
        assertEquals(Action.KEEP, PhantomCache.decide(e, Cover.PARTIAL, 6, 0, 0, 1000, null));
        assertEquals(Action.KEEP, PhantomCache.decide(e, Cover.PARTIAL, 10, 0, 10, 1000, null));
        assertEquals(Action.BUILD, PhantomCache.decide(e, Cover.PARTIAL, 16, 0, 0, 1000, null));
        assertEquals(Action.BUILD, PhantomCache.decide(e, Cover.PARTIAL, 0, -12, 12, 1000, null));
        assertEquals(Action.BUILD, PhantomCache.decide(e, Cover.PARTIAL, -17, 0, 0, 1000, null));
    }

    @Test
    void aCutSectionIsRebuiltWhenTheLimitRadiusChangesALot() {
        Entry<String> e = built(3, 0, 0, Cover.PARTIAL, 0, 0, 0, 1000, null);
        // radius 31.6 -> 28.3: small
        assertEquals(Action.KEEP, PhantomCache.decide(e, Cover.PARTIAL, 0, 0, 0, 800, null));
        // radius 31.6 -> 20: 11.6 less
        assertEquals(Action.BUILD, PhantomCache.decide(e, Cover.PARTIAL, 0, 0, 0, 400, null));
        assertEquals(Action.BUILD, PhantomCache.decide(e, Cover.PARTIAL, 0, 0, 0, 2000, null));
    }

    @Test
    void theCoverKindFlippingWithinTheSlackDoesNotRebuild() {
        Entry<String> w = built(1, 0, 0, Cover.ALL, 0, 0, 0, 1000, null);
        assertEquals(Action.KEEP, PhantomCache.decide(w, Cover.PARTIAL, 3, 0, 0, 1000, null));
        Entry<String> p = built(2, 0, 0, Cover.PARTIAL, 0, 0, 0, 1000, null);
        assertEquals(Action.KEEP, PhantomCache.decide(p, Cover.ALL, 3, 0, 0, 1000, null));
        assertEquals(Action.BUILD, PhantomCache.decide(p, Cover.ALL, 20, 0, 0, 1000, null));
    }

    @Test
    void slackIsMeasuredFromWhereItWasBakedNotFromThePreviousPass() {
        Entry<String> e = built(3, 0, 0, Cover.PARTIAL, 0, 0, 0, 1000, null);
        // the entry is not touched by KEEP, so creeping 6 blocks at a time still reaches the threshold
        assertEquals(Action.KEEP, PhantomCache.decide(e, Cover.PARTIAL, 6, 0, 0, 1000, null));
        assertEquals(Action.KEEP, PhantomCache.decide(e, Cover.PARTIAL, 12, 0, 0, 1000, null));
        assertEquals(Action.BUILD, PhantomCache.decide(e, Cover.PARTIAL, 18, 0, 0, 1000, null));
    }

    @Test
    void forgetEmptiesWithoutFreeing() {
        built(0, 0, 0, Cover.ALL, 0, 0, 0, 100, null);
        cache.forget();
        assertEquals(0, cache.size());
        assertEquals(0, freed.size());
    }

    @Test
    void aLayerChangeRebuildsEvenAWhollyCoveredSection() {
        Entry<String> e = built(0, 0, 0, Cover.ALL, 0, 0, 0, 1000, 64);
        assertEquals(Action.KEEP, PhantomCache.decide(e, Cover.ALL, 0, 0, 0, 1000, 64));
        assertEquals(Action.BUILD, PhantomCache.decide(e, Cover.ALL, 0, 0, 0, 1000, 65));
        assertEquals(Action.BUILD, PhantomCache.decide(e, Cover.ALL, 0, 0, 0, 1000, null));
    }

    @Test
    void aSectionOutsideTheLimitIsDroppedAndNothingIsDoneForAMissingOne() {
        Entry<String> e = built(0, 0, 0, Cover.ALL, 0, 0, 0, 1000, null);
        assertEquals(Action.DROP, PhantomCache.decide(e, Cover.NONE, 0, 0, 0, 1000, null));
        assertEquals(Action.KEEP, PhantomCache.decide(null, Cover.NONE, 0, 0, 0, 1000, null));
    }

    @Test
    void markDirtyFlagsOnlyExistingCleanSectionsOnce() {
        assertNull(cache.markDirty(1, 1, 1));
        Entry<String> e = built(1, 1, 1, Cover.ALL, 0, 0, 0, 100, null);
        assertSame(e, cache.markDirty(1, 1, 1));
        assertNull(cache.markDirty(1, 1, 1));
        e.dirty = false;
        assertSame(e, cache.markDirty(1, 1, 1));
    }

    @Test
    void markAllDirtyReturnsTheCleanOnes() {
        Entry<String> a = built(0, 0, 0, Cover.ALL, 0, 0, 0, 100, null);
        built(1, 0, 0, Cover.ALL, 0, 0, 0, 100, null).dirty = true;
        List<Entry<String>> flagged = cache.markAllDirty();
        assertEquals(1, flagged.size());
        assertSame(a, flagged.get(0));
        assertTrue(a.dirty);
    }

    @Test
    void retrySectionsAreFlaggedByTheirOwnPass() {
        Entry<String> a = built(0, 0, 0, Cover.ALL, 0, 0, 0, 100, null);
        a.retry = true;
        built(1, 0, 0, Cover.ALL, 0, 0, 0, 100, null);
        List<Entry<String>> flagged = cache.markRetryDirty();
        assertEquals(1, flagged.size());
        assertSame(a, flagged.get(0));
    }

    @Test
    void dropAndClearFreeTheMeshesOnce() {
        Entry<String> e = built(0, 0, 0, Cover.ALL, 0, 0, 0, 100, null);
        e.wrongMesh = "w000";
        built(1, 0, 0, Cover.ALL, 0, 0, 0, 100, null);
        cache.drop(e.key);
        assertEquals(List.of("m000", "w000"), freed);
        assertNull(cache.get(e.key));
        cache.drop(e.key);
        assertEquals(2, freed.size());
        cache.clear();
        assertEquals(3, freed.size());
        assertEquals(0, cache.size());
    }

    @Test
    void releaseKeepsTheEntryButEmptiesIt() {
        Entry<String> e = built(0, 0, 0, Cover.ALL, 0, 0, 0, 100, null);
        cache.release(e);
        assertNull(e.mesh);
        assertNotNull(cache.get(e.key));
        cache.release(e);
        assertEquals(1, freed.size());
    }

    @Test
    void keysKeepNegativeSectionsApart() {
        cache.getOrCreate(-1, -4, -1);
        cache.getOrCreate(1, 4, 1);
        cache.getOrCreate(-1, 4, 1);
        assertEquals(3, cache.size());
        assertNotNull(cache.get(PhantomCache.key(-1, -4, -1)));
    }
}
