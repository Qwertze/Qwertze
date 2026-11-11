package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The collapse rule and the window's size bounds. */
class MessengerLayoutTest {
    private static MessengerLayout.Plan plan(int w, int h, boolean focused, boolean listView) {
        return MessengerLayout.plan(w, h, focused, listView);
    }

    @Test
    void defaultWindowShowsEverything() {
        var p = plan(330, 190, true, false);
        assertTrue(p.sidebar() && p.buttons() && p.history() && p.input());
        assertFalse(p.toggle() || p.listView());
    }

    @Test
    void theListCollapsesFirstWhenNarrow() {
        var wide = plan(MessengerLayout.SIDEBAR_MIN_W, 190, true, false);
        assertTrue(wide.sidebar());
        var narrow = plan(MessengerLayout.SIDEBAR_MIN_W - 1, 190, true, false);
        assertFalse(narrow.sidebar());
        assertTrue(narrow.toggle());
        assertTrue(narrow.history() && narrow.input()); // messages and input are kept
        assertTrue(narrow.buttons()); // the buttons go later than the list
    }

    @Test
    void theToggleShowsTheListInsteadOfTheMessages() {
        var p = plan(150, 190, true, true);
        assertTrue(p.listView() && p.toggle());
        assertFalse(p.history());
        assertFalse(p.sidebar());
        // a wide window ignores the request: the list is beside the messages
        var wide = plan(300, 190, true, true);
        assertFalse(wide.listView());
        assertTrue(wide.sidebar() && wide.history());
    }

    @Test
    void headerButtonsGoWhenVeryNarrow() {
        assertTrue(plan(MessengerLayout.BUTTONS_MIN_W, 190, true, false).buttons());
        assertFalse(plan(MessengerLayout.BUTTONS_MIN_W - 1, 190, true, false).buttons());
        assertFalse(plan(30, 190, true, false).buttons());
    }

    @Test
    void veryShortShowsOnlyTheMessagesOrOnlyTheInput() {
        int h = MessengerLayout.BOTH_MIN_H - 1;
        var typing = plan(330, h, true, false);
        assertTrue(typing.input());
        assertFalse(typing.history());
        var reading = plan(330, h, false, false);
        assertTrue(reading.history());
        assertFalse(reading.input());
        var both = plan(330, MessengerLayout.BOTH_MIN_H, false, false);
        assertTrue(both.history() && both.input());
    }

    @Test
    void listViewNeverShowsMessagesAndNeverBreaks() {
        for (int w : new int[] {1, 10, 24, 100, 189, 190, 400}) {
            for (int h : new int[] {1, 12, 59, 60, 200}) {
                for (boolean focused : new boolean[] {false, true}) {
                    for (boolean lv : new boolean[] {false, true}) {
                        var p = plan(w, h, focused, lv);
                        assertEquals(w >= MessengerLayout.SIDEBAR_MIN_W, p.sidebar());
                        assertEquals(!p.sidebar(), p.toggle());
                        if (p.listView()) assertFalse(p.history());
                        assertFalse(p.sidebar() && p.listView());
                        // something is always shown besides the header
                        assertTrue(p.sidebar() || p.listView() || p.history() || p.input());
                    }
                }
            }
        }
    }

    @Test
    void geometryHasOnlyATinyFloor() {
        Geometry tiny = new Geometry(10, 10, 1, 1).clamp(800, 600);
        assertEquals(Geometry.MIN_W, tiny.w());
        assertEquals(Geometry.MIN_H, tiny.h());
        Geometry smallOk = new Geometry(10, 10, 60, 30).clamp(800, 600);
        assertEquals(60, smallOk.w());
        assertEquals(30, smallOk.h());
        // a small window can be placed anywhere on screen and still be grabbed
        Geometry far = new Geometry(5000, 5000, 24, 12).clamp(800, 600);
        assertTrue(far.x() < 800 && far.x() + far.w() > 790 - 0 && far.y() < 600);
        Geometry gone = new Geometry(-5000, -5000, 24, 12).clamp(800, 600);
        assertTrue(gone.x() + gone.w() > 0 && gone.y() >= 0);
        assertTrue(gone.x() >= 0 || gone.x() + gone.w() >= Math.min(40, gone.w()));
    }

    @Test
    void hitAreasAreAtLeastEightRealPixels() {
        // 13 logical at 0.1 is 1.3 real pixels: grown to 80 logical (8 real)
        assertEquals(80, MessengerLayout.hitSize(13, 0.1f, 1000));
        assertEquals(27, MessengerLayout.hitSize(9, 0.3f, 1000)); // ceil(8 / 0.3)
        // at normal and large scales the drawn size already is more than 8 real pixels
        assertEquals(13, MessengerLayout.hitSize(13, 1f, 1000));
        assertEquals(13, MessengerLayout.hitSize(13, 2f, 1000));
        // never larger than the window
        assertEquals(50, MessengerLayout.hitSize(13, 0.1f, 50));
        assertEquals(1, MessengerLayout.hitSize(13, 0.1f, 0));
        for (float s = MessengerScale.MIN; s <= MessengerScale.MAX; s += 0.05f) {
            assertTrue(MessengerLayout.hitSize(9, s, 100_000) * s >= MessengerLayout.MIN_HIT_PX - 1e-3, "scale " + s);
        }
    }

    @Test
    void titleHitIsTightAboveTheGrabScale() {
        assertEquals(15, MessengerLayout.titleHitHeight(13, 1f, 1000));
        assertEquals(15, MessengerLayout.titleHitHeight(13, 0.45f, 1000));
        assertEquals(10, MessengerLayout.titleHitHeight(13, 1f, 10));
        // at or below 0.4 it is the 8 real pixel area
        assertEquals(20, MessengerLayout.titleHitHeight(13, 0.4f, 1000));
        assertEquals(80, MessengerLayout.titleHitHeight(13, 0.1f, 1000));
    }
}
