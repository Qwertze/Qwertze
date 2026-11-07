package com.porter.qwerte.manager.map;

import com.porter.qwerte.manager.map.ViewerEvent.ChatLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerStateTest {
    static final class Sink implements ViewerState.TileSink {
        final List<String> log = new ArrayList<>();

        @Override
        public void put(int cx, int cz, byte[] colors) {
            log.add("put " + cx + "," + cz);
        }

        @Override
        public void drop(int cx, int cz) {
            log.add("drop " + cx + "," + cz);
        }

        @Override
        public void clear() {
            log.add("clear");
        }
    }

    static ViewerEvent.Tile tile(int x, int z) {
        return new ViewerEvent.Tile(x, z, new byte[256], null);
    }

    static ChatLine line(long t, String text) {
        return new ChatLine(t, "public", "A", "", text);
    }

    @Test
    void snapshotReplacesEverything() {
        Sink sink = new Sink();
        ViewerState s = new ViewerState(sink);
        s.apply(new ViewerEvent.Ents(new ViewerEvent.Self(1, 2, 3, 0), List.of(new ViewerEvent.Ent(1, "mob", "", "cow", 1, 0, 0, 0, 0))));
        sink.log.clear();
        int ch = s.apply(new ViewerEvent.Snapshot("sess", new ViewerEvent.World("minecraft:overworld", -64, 384), new ViewerEvent.Self(9, 8, 7, 0),
            List.of(tile(1, 1), tile(2, 2)), List.of(), null, List.of(line(1, "hi")), true));
        assertEquals(List.of("clear", "put 1,1", "put 2,2"), sink.log);
        assertTrue((ch & ViewerState.TILES) != 0);
        assertEquals(9, s.self().x());
        assertTrue(s.ents().isEmpty());
        assertNull(s.hud());
        assertTrue(s.relative());
        assertEquals("sess", s.session());
        assertEquals(1, s.chat().size());
        assertEquals("waiting", s.state());
    }

    @Test
    void tilesAddAndDrop() {
        Sink sink = new Sink();
        ViewerState s = new ViewerState(sink);
        s.apply(new ViewerEvent.Tiles(List.of(tile(3, 4)), List.<int[]>of(new int[] {5, 6})));
        assertEquals(List.of("put 3,4", "drop 5,6"), sink.log);
    }

    @Test
    void entsMoveTheBotOnlyWhenTheySendSelf() {
        ViewerState s = new ViewerState(new Sink());
        s.apply(new ViewerEvent.Ents(new ViewerEvent.Self(1, 2, 3, 0), List.of()));
        s.apply(new ViewerEvent.Ents(null, List.of(new ViewerEvent.Ent(1, "item", "", "minecraft:dirt", 2, 0, 0, 0, 0))));
        assertEquals(1, s.self().x());
        assertEquals(1, s.ents().size());
    }

    @Test
    void chatAppendsWithoutDuplicatesAndKeepsTheNewest() {
        ViewerState s = new ViewerState(new Sink());
        s.apply(new ViewerEvent.Chat(List.of(line(1, "a"), line(2, "b"))));
        assertEquals(0, s.apply(new ViewerEvent.Chat(List.of(line(1, "a")))));
        assertEquals(2, s.chat().size());
        List<ChatLine> many = new ArrayList<>();
        for (int i = 0; i < ViewerState.CHAT_KEEP + 50; i++) many.add(line(100 + i, "m" + i));
        s.apply(new ViewerEvent.Chat(many));
        assertEquals(ViewerState.CHAT_KEEP, s.chat().size());
        assertEquals("m" + (ViewerState.CHAT_KEEP + 49), s.chat().get(ViewerState.CHAT_KEEP - 1).text());
        assertTrue((s.apply(new ViewerEvent.Chat(List.of(line(1, "a")))) & ViewerState.CHAT) != 0);
    }

    @Test
    void statusByeAndFailures() {
        ViewerState s = new ViewerState(new Sink());
        s.apply(new ViewerEvent.Status("live"));
        assertEquals("live", s.state());
        s.apply(new ViewerEvent.Bye("revoked"));
        assertEquals("closed", s.state());
        assertEquals("revoked", s.byeReason());
        s.apply(new ViewerEvent.Connecting());
        assertEquals("connecting", s.state());
        s.apply(new ViewerEvent.Failed(403, 0));
        assertEquals("closed", s.state());
        assertEquals(403, s.failedStatus());
    }

    @Test
    void resetClearsTheSink() {
        Sink sink = new Sink();
        ViewerState s = new ViewerState(sink);
        s.apply(new ViewerEvent.Hello("b", "1.21.4", true));
        assertTrue(s.relative());
        s.reset();
        assertEquals("clear", sink.log.get(sink.log.size() - 1));
        assertFalse(s.relative());
    }
}
