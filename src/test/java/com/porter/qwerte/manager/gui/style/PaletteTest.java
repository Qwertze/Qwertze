package com.porter.qwerte.manager.gui.style;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteTest {
    @Test
    void parsesHexRgbaAndTransparent() {
        assertEquals(0xFF3355CC, Palette.parse("#3355cc"));
        assertEquals(0xFFAABBCC, Palette.parse("#ABC"));
        assertEquals(0, Palette.parse("transparent"));
        assertEquals(0xCC1E1E21, Palette.parse("rgba(30,30,33,.80)"));
        assertEquals(0xFF0A141E, Palette.parse("rgb(10, 20, 30)"));
        assertEquals(0x00000000, Palette.parse("rgba(0,0,0,0)"));
        assertEquals(0xFF000000, Palette.parse("rgba(0,0,0,5)"));
    }

    @Test
    void rejectsGarbage() {
        for (String bad : new String[] {"", "red", "#12", "#gggggg", "rgba(1,2)", "rgba(a,b,c,d)", "rgba(1,2,3"}) {
            assertThrows(IllegalArgumentException.class, () -> Palette.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Palette.parse(null));
    }

    @Test
    void mixesChannelByChannel() {
        assertEquals(0xFF000000, Palette.mix(0xFF000000, 0xFFFFFFFF, 0));
        assertEquals(0xFFFFFFFF, Palette.mix(0xFF000000, 0xFFFFFFFF, 1));
        assertEquals(0xFF808080, Palette.mix(0xFF000000, 0xFFFFFFFF, 0.5));
        assertEquals(0xFFFFFFFF, Palette.mix(0xFF000000, 0xFFFFFFFF, 7));
        assertEquals(0x80FFFFFF, Palette.withAlpha(0xFFFFFFFF, 128));
    }

    @Test
    void classicIsTheDefaultAndEveryThemeHasEveryToken() {
        assertEquals(WindowTheme.CLASSIC, WindowTheme.DEFAULT);
        assertEquals(WindowTheme.CLASSIC, WindowTheme.byId("nonsense"));
        assertEquals(WindowTheme.CLASSIC, WindowTheme.byId(null));
        assertEquals(WindowTheme.MAC, WindowTheme.byId(" MAC "));
        assertEquals(7, WindowTheme.values().length);
        for (WindowTheme t : WindowTheme.values()) {
            Map<String, Integer> tokens = t.palette().tokens();
            assertEquals(Palette.TOKENS.length, tokens.size(), t.id());
            for (String name : Palette.TOKENS) assertTrue(tokens.containsKey(name), t.id() + " " + name);
            assertEquals(255, Palette.alpha(t.palette().surface()), t.id());
            assertEquals(t, WindowTheme.byId(t.id()));
            assertEquals(t.palette().isMac(), t == WindowTheme.MAC);
        }
        assertTrue(WindowTheme.CLASSIC.palette().bevel);
        assertFalse(WindowTheme.DARK.palette().bevel);
        // cycling visits every theme once and comes back
        WindowTheme t = WindowTheme.DEFAULT;
        for (int i = 0; i < WindowTheme.values().length; i++) t = t.next();
        assertEquals(WindowTheme.DEFAULT, t);
    }

    @Test
    void macHasGlassAndDots() {
        Palette mac = WindowTheme.MAC.palette();
        assertEquals(0xCC1E1E21, mac.glass);
        assertEquals(0xFFFF5F57, mac.dotRed);
        assertEquals(0xFFFEBC2E, mac.dotYellow);
        assertEquals(0xFF28C840, mac.dotGreen);
        assertEquals(0, mac.nav(), "Mac's nav is transparent");
    }

    /** The Java enum and dev/themes-palette.json (made from themes.css) must say the same. */
    @Test
    void matchesTheWebsitePaletteJson() throws IOException {
        Path file = Path.of("stash-manager-server", "dev", "themes-palette.json");
        if (!Files.isRegularFile(file)) return; // not part of every checkout
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray themes = root.getAsJsonArray("themes");
        int seen = 0;
        for (JsonElement e : themes) {
            JsonObject o = e.getAsJsonObject();
            String id = o.get("id").getAsString();
            WindowTheme t = WindowTheme.byId(id);
            assertEquals(id, t.id(), "unknown theme in the JSON: " + id);
            seen++;
            Palette p = t.palette();
            assertEquals(o.get("default").getAsBoolean(), t == WindowTheme.DEFAULT, id + " default");
            assertEquals(o.get("radiusPx").getAsInt(), p.radiusCss, id + " radius");
            assertEquals(o.get("borderWidthPx").getAsInt(), p.borderCss, id + " border width");
            JsonObject colors = o.getAsJsonObject("colors");
            for (Map.Entry<String, Integer> token : p.tokens().entrySet()) {
                assertTrue(colors.has(token.getKey()), id + " JSON lacks " + token.getKey());
                assertEquals(Palette.parse(colors.get(token.getKey()).getAsString()), token.getValue(), id + " " + token.getKey());
            }
            if (o.has("extra")) {
                JsonObject extra = o.getAsJsonObject("extra");
                assertEquals(Palette.parse(extra.get("panelGlass").getAsString()), p.glass);
                assertEquals(Palette.parse(extra.get("dotRed").getAsString()), p.dotRed);
                assertEquals(Palette.parse(extra.get("dotYellow").getAsString()), p.dotYellow);
                assertEquals(Palette.parse(extra.get("dotGreen").getAsString()), p.dotGreen);
            }
        }
        assertEquals(WindowTheme.values().length, seen, "every theme is in the JSON");
    }
}
