package com.porter.qwerte.printer.stash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.stash.StashPicker.Candidate;
import com.porter.qwerte.printer.stash.StashPicker.Result;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class StashPickerTest {
    private static final ToIntFunction<String> STACK64 = id -> 64;

    private static Candidate stash(String key, boolean online, Map<String, Long> available) {
        return new Candidate(key, key, online, false, 0, available);
    }

    private static Map<String, Long> m(Object... kv) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], ((Number) kv[i + 1]).longValue());
        return out;
    }

    @Test
    void anOnlineBotIsPreferredOverAnOfflineOneThatHoldsMore() {
        Result r = StashPicker.pick(m("a", 100), List.of(stash("off", false, m("a", 1000)), stash("on", true, m("a", 100))), STACK64);
        assertEquals(1, r.picks().size());
        assertEquals("on", r.picks().get(0).stash().key());
        assertEquals(m("a", 100), r.picks().get(0).lines());
        assertTrue(r.unmet().isEmpty());
    }

    @Test
    void theStashCoveringMostSlotsComesFirst() {
        Candidate small = stash("s1", true, m("a", 64));
        Candidate big = stash("s2", true, m("a", 640, "b", 640));
        Result r = StashPicker.pick(m("a", 640, "b", 640), List.of(small, big), STACK64);
        assertEquals("s2", r.picks().get(0).stash().key());
        assertEquals(1, r.picks().size());
    }

    @Test
    void whatOneStashLacksComesFromTheNext() {
        Result r = StashPicker.pick(m("a", 100), List.of(stash("s1", true, m("a", 60)), stash("s2", true, m("a", 50))), STACK64);
        assertEquals(2, r.picks().size());
        assertEquals(60L, r.picks().get(0).lines().get("a"));
        assertEquals(40L, r.picks().get(1).lines().get("a"));
        assertTrue(r.unmet().isEmpty());
    }

    @Test
    void whatNoStashHoldsIsUnmet() {
        Result r = StashPicker.pick(m("a", 100, "b", 10), List.of(stash("s1", true, m("a", 100))), STACK64);
        assertEquals(m("b", 10), r.unmet());
        assertEquals(1, r.picks().size());
    }

    @Test
    void aShortStashLeavesTheRestUnmet() {
        Result r = StashPicker.pick(m("a", 100), List.of(stash("s1", true, m("a", 30))), STACK64);
        assertEquals(m("a", 70), r.unmet());
    }

    @Test
    void aStashWithOurOpenOrderOrTwentyOpenOrdersIsLeftOut() {
        Candidate ours = new Candidate("ours", "ours", true, true, 1, m("a", 1000));
        Candidate full = new Candidate("full", "full", true, false, 20, m("a", 1000));
        Candidate fine = new Candidate("fine", "fine", false, false, 19, m("a", 1000));
        assertFalse(StashPicker.usable(ours));
        assertFalse(StashPicker.usable(full));
        assertTrue(StashPicker.usable(fine));
        Result r = StashPicker.pick(m("a", 10), List.of(ours, full, fine), STACK64);
        assertEquals(1, r.picks().size());
        assertEquals("fine", r.picks().get(0).stash().key());
    }

    @Test
    void tiesAreBrokenByKey() {
        Result r = StashPicker.pick(m("a", 10), List.of(stash("b", true, m("a", 10)), stash("a", true, m("a", 10))), STACK64);
        assertEquals("a", r.picks().get(0).stash().key());
    }

    @Test
    void noCandidatesLeavesEverythingUnmet() {
        Result r = StashPicker.pick(m("a", 5), List.of(), STACK64);
        assertTrue(r.picks().isEmpty());
        assertEquals(m("a", 5), r.unmet());
    }

    @Test
    void theServerAddressIsMatchedLoosely() {
        assertTrue(StashPicker.sameServer("2B2T.org", "2b2t.org"));
        assertTrue(StashPicker.sameServer("2b2t.org:25565", "2b2t.org"));
        assertTrue(StashPicker.sameServer(" 2b2t.org. ", "2b2t.org"));
        assertFalse(StashPicker.sameServer("2b2t.org:25566", "2b2t.org"));
        assertFalse(StashPicker.sameServer("other.net", "2b2t.org"));
        assertFalse(StashPicker.sameServer("", ""));
        assertFalse(StashPicker.sameServer(null, "x"));
    }
}
