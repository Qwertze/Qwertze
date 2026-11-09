package com.porter.qwerte.manager.map;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerEventsTest {
    static String colors(int fill) {
        byte[] b = new byte[256];
        for (int i = 0; i < 256; i++) b[i] = (byte) (fill + (i == 17 ? 1 : 0));
        return Base64.getEncoder().encodeToString(b);
    }

    static String heights() {
        ByteBuffer bb = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 256; i++) bb.putShort(i == 0 ? Short.MIN_VALUE : (short) (60 + i));
        return Base64.getEncoder().encodeToString(bb.array());
    }

    static String tile(int x, int z, int fill) {
        return "{\"x\":" + x + ",\"z\":" + z + ",\"c\":\"" + colors(fill) + "\",\"h\":\"" + heights() + "\"}";
    }

    @Test
    void tileDecodesColoursAndLittleEndianHeights() {
        ViewerEvent.Snapshot s = (ViewerEvent.Snapshot) ViewerEvents.parse("snapshot", "{\"session\":\"abc\",\"tiles\":[" + tile(-3, 7, 24) + "]}");
        assertEquals(1, s.tiles().size());
        ViewerEvent.Tile t = s.tiles().get(0);
        assertEquals(-3, t.x());
        assertEquals(7, t.z());
        assertEquals(24, t.colors()[0]);
        assertEquals(25, t.colors()[17]);
        assertEquals(Short.MIN_VALUE, t.heights()[0]);
        assertEquals(61, t.heights()[1]);
    }

    @Test
    void aTileWithTheWrongSizeIsSkipped() {
        String shortC = Base64.getEncoder().encodeToString(new byte[255]);
        String json = "{\"tiles\":[{\"x\":0,\"z\":0,\"c\":\"" + shortC + "\"},{\"x\":1,\"z\":1,\"c\":\"!!notbase64\"}," + tile(2, 2, 8)
            + ",{\"x\":1.5,\"z\":0,\"c\":\"" + colors(4) + "\"}]}";
        ViewerEvent.Tiles t = (ViewerEvent.Tiles) ViewerEvents.parse("tiles", json);
        assertEquals(1, t.tiles().size());
        assertEquals(2, t.tiles().get(0).x());
    }

    @Test
    void heightsAreOptional() {
        ViewerEvent.Tiles t = (ViewerEvent.Tiles) ViewerEvents.parse("tiles",
            "{\"tiles\":[{\"x\":0,\"z\":0,\"c\":\"" + colors(8) + "\"}],\"drop\":[[5,-9],[1],[\"a\",2]]}");
        assertEquals(1, t.tiles().size());
        assertNull(t.tiles().get(0).heights());
        assertEquals(1, t.drop().size());
        assertArrayEquals(new int[] {5, -9}, t.drop().get(0));
    }

    @Test
    void entitiesHudAndChatAreCleaned() {
        String json = "{\"self\":{\"x\":1.5,\"y\":64,\"z\":-2,\"yaw\":90},\"ents\":["
            + "{\"id\":1,\"k\":\"player\",\"n\":\"Ste\\u00a7ve\",\"x\":1,\"y\":2,\"z\":3},"
            + "{\"id\":2,\"k\":\"mob\",\"t\":\"zombie\",\"x\":1,\"z\":3},"
            + "{\"id\":3,\"k\":\"weird\",\"x\":1,\"z\":3},{\"id\":4,\"k\":\"mob\"}]}";
        ViewerEvent.Ents e = (ViewerEvent.Ents) ViewerEvents.parse("ents", json);
        assertEquals(90, e.self().yaw());
        assertEquals(3, e.ents().size());
        assertEquals("Steve", e.ents().get(0).name());
        assertTrue(e.ents().get(1).hostile());
        assertEquals("other", e.ents().get(2).kind());

        ViewerEvent.HudUpdate h = (ViewerEvent.HudUpdate) ViewerEvents.parse("hud",
            "{\"hud\":{\"hp\":18.5,\"food\":17,\"held\":2,\"hotbar\":[{\"id\":\"minecraft:diamond\",\"c\":3},{\"id\":\"BAD ID\"},null],"
                + "\"offhand\":{\"id\":\"minecraft:totem_of_undying\"}}}");
        assertEquals(18.5, h.hud().hp());
        assertTrue(Double.isNaN(h.hud().sat()));
        assertEquals(9, h.hud().hotbar().size());
        assertEquals(3, h.hud().hotbar().get(0).count());
        assertNull(h.hud().hotbar().get(1));
        assertEquals(4, h.hud().armorSlots().size());
        assertNotNull(h.hud().offhand());
        assertEquals(2, h.hud().held());
        // no inv sent: 27 empty slots
        assertEquals(27, h.hud().inv().size());
        assertNull(h.hud().inv().get(0));
        StringBuilder inv = new StringBuilder("[{\"id\":\"minecraft:stone\",\"c\":5}");
        for (int i = 1; i < 27; i++) inv.append(",null");
        ViewerEvent.HudUpdate full = (ViewerEvent.HudUpdate) ViewerEvents.parse("hud", "{\"hud\":{\"hp\":1,\"inv\":" + inv + "]}}");
        assertEquals(5, full.hud().inv().get(0).count());
        ViewerEvent.HudUpdate shortInv = (ViewerEvent.HudUpdate) ViewerEvents.parse("hud", "{\"hud\":{\"hp\":1,\"inv\":[{\"id\":\"minecraft:stone\"}]}}");
        assertNull(shortInv.hud().inv().get(0));

        ViewerEvent.Chat c = (ViewerEvent.Chat) ViewerEvents.parse("chat",
            "{\"lines\":[{\"t\":5,\"k\":\"whisper\",\"s\":\"A\",\"to\":\"B\",\"m\":\"hi\\u00a7k\"},{\"k\":\"public\"},{\"m\":\"x\",\"k\":\"zzz\"}]}");
        assertEquals(2, c.lines().size());
        assertEquals("hik", c.lines().get(0).text());
        assertEquals("public", c.lines().get(1).kind());
    }

    @Test
    void unknownOrBrokenEventsGiveNull() {
        assertNull(ViewerEvents.parse("nope", "{}"));
        assertNull(ViewerEvents.parse("hud", "not json"));
        assertNull(ViewerEvents.parse("hud", "[1]"));
        assertEquals("rotate", ((ViewerEvent.Bye) ViewerEvents.parse("bye", "{\"reason\":\"rotate\"}")).reason());
        ViewerEvent.Hello hello = (ViewerEvent.Hello) ViewerEvents.parse("hello", "{\"botId\":\"b1\",\"mc\":\"1.21.4\",\"relative\":true}");
        assertTrue(hello.relative());
        assertFalse(((ViewerEvent.Hello) ViewerEvents.parse("hello", "{\"mc\":\"<script>\"}")).relative());
        assertEquals("", ((ViewerEvent.Hello) ViewerEvents.parse("hello", "{\"mc\":\"<script>\"}")).mc());
    }
}
