package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.porter.qwerte.manager.model.SettingsSchema.Leaf;
import com.porter.qwerte.manager.model.SettingsSchema.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsSchemaTest {
    static final String TREE = "[{\"path\":\"a\",\"type\":\"group\",\"label\":\"Sorting\",\"children\":["
        + "{\"path\":\"a.enabled\",\"type\":\"bool\",\"label\":\"Enabled\"},"
        + "{\"path\":\"a.sub\",\"type\":\"group\",\"label\":\"Sub\",\"children\":[{\"path\":\"a.sub.n\",\"type\":\"int\",\"min\":1,\"max\":10,\"nullable\":true}]}]},"
        + "{\"path\":\"b\",\"type\":\"group\",\"label\":\"Delivery\",\"children\":[{\"path\":\"b.mode\",\"type\":\"enum\",\"options\":[\"x\",\"y\"]},"
        + "{\"path\":\"b.key\",\"type\":\"string\",\"secret\":true,\"set\":true},{\"path\":\"b.list\",\"type\":\"stringList\",\"section\":\"Lists\"}]}]";

    static Node node(String json) {
        List<Leaf> l = SettingsSchema.leaves(JsonParser.parseString("[" + json + "]"));
        return l.get(0).node();
    }

    @Test
    void flattensGroupsIntoSectionsAndTrails() {
        List<Leaf> leaves = SettingsSchema.leaves(JsonParser.parseString(TREE));
        assertEquals(5, leaves.size());
        assertEquals("Sorting", leaves.get(0).section());
        assertTrue(leaves.get(0).trail().isEmpty());
        assertEquals(List.of("Sub"), leaves.get(1).trail());
        assertEquals("Lists", leaves.get(4).section());
        List<SettingsSchema.Section> sections = SettingsSchema.sections(leaves);
        assertEquals(List.of("Sorting", "Delivery", "Lists"), sections.stream().map(SettingsSchema.Section::name).toList());
        assertEquals(2, sections.get(0).count());
        assertEquals(2, SettingsSchema.inSection(leaves, "Delivery").size());
        assertEquals(1, SettingsSchema.search(leaves, "MODE", 10).size());
        assertTrue(SettingsSchema.search(leaves, "  ", 10).isEmpty());
        assertTrue(leaves.get(3).node().secret());
    }

    @Test
    void numbersAreParsedAndRangeChecked() {
        Node n = node("{\"path\":\"p\",\"type\":\"int\",\"min\":1,\"max\":10}");
        assertEquals(new JsonPrimitive(5L), SettingsSchema.parse(n, " 5 ", false).value());
        assertEquals("at least 1", SettingsSchema.parse(n, "0", false).error());
        assertEquals("at most 10", SettingsSchema.parse(n, "11", false).error());
        assertEquals("a whole number", SettingsSchema.parse(n, "1.5", false).error());
        assertEquals("enter a number", SettingsSchema.parse(n, "", false).error());
        assertFalse(SettingsSchema.parse(n, "99999999999", false).ok());
        Node d = node("{\"path\":\"p\",\"type\":\"double\"}");
        assertEquals(0.5, SettingsSchema.parse(d, "0.5", false).value().getAsDouble());
        assertEquals("not a number", SettingsSchema.parse(d, "NaN", false).error());
        assertEquals("not a number", SettingsSchema.parse(d, "abc", false).error());
    }

    @Test
    void nullableAllowsUnsetOrEmpty() {
        Node n = node("{\"path\":\"p\",\"type\":\"int\",\"nullable\":true}");
        assertTrue(SettingsSchema.parse(n, "7", true).value().isJsonNull());
        assertTrue(SettingsSchema.parse(n, "", false).value().isJsonNull());
        Node s = node("{\"path\":\"p\",\"type\":\"string\"}");
        assertEquals("", SettingsSchema.parse(s, "", false).value().getAsString());
        assertFalse(SettingsSchema.parse(s, "x".repeat(1001), false).ok());
        // unset on a setting that can't be unset is just the text
        assertFalse(SettingsSchema.parse(s, "q", true).value().isJsonNull());
    }

    @Test
    void validateMirrorsTheServer() {
        Node bool = node("{\"path\":\"p\",\"type\":\"bool\"}");
        assertEquals("", SettingsSchema.validate(bool, new JsonPrimitive(true)));
        assertFalse(SettingsSchema.validate(bool, new JsonPrimitive("yes")).isEmpty());
        assertEquals("can't be unset", SettingsSchema.validate(bool, JsonNull.INSTANCE));
        Node en = node("{\"path\":\"p\",\"type\":\"enum\",\"options\":[\"a\",\"b\"],\"nullable\":true}");
        assertEquals("", SettingsSchema.validate(en, new JsonPrimitive("b")));
        assertFalse(SettingsSchema.validate(en, new JsonPrimitive("c")).isEmpty());
        assertEquals("", SettingsSchema.validate(en, JsonNull.INSTANCE));
        Node i = node("{\"path\":\"p\",\"type\":\"int\",\"max\":5}");
        assertFalse(SettingsSchema.validate(i, new JsonPrimitive(6)).isEmpty());
        assertFalse(SettingsSchema.validate(i, new JsonPrimitive(1.5)).isEmpty());
        assertFalse(SettingsSchema.validate(i, new JsonPrimitive(3_000_000_000L)).isEmpty());
        Node list = node("{\"path\":\"p\",\"type\":\"stringList\"}");
        JsonArray ok = new JsonArray();
        ok.add("a");
        assertEquals("", SettingsSchema.validate(list, ok));
        JsonArray bad = new JsonArray();
        bad.add(3);
        assertFalse(SettingsSchema.validate(list, bad).isEmpty());
        JsonArray big = new JsonArray();
        for (int k = 0; k < 501; k++) big.add("x");
        assertFalse(SettingsSchema.validate(list, big).isEmpty());
        Node secret = node("{\"path\":\"p\",\"type\":\"string\",\"secret\":true}");
        assertFalse(SettingsSchema.validate(secret, new JsonPrimitive("x")).isEmpty());
        assertFalse(SettingsSchema.validate(node("{\"path\":\"p\",\"type\":\"string\",\"readOnly\":true}"), new JsonPrimitive("x")).isEmpty());
    }

    @Test
    void draftKeepsOnlyRealChanges() {
        SettingsDraft d = new SettingsDraft();
        assertTrue(d.set("a", new JsonPrimitive(2), new JsonPrimitive(1)));
        assertEquals(1, d.size());
        // back to the server's value (written as 1.0): not a change any more
        assertFalse(d.set("a", new JsonPrimitive(1.0), new JsonPrimitive(1)));
        assertEquals(0, d.size());
        assertTrue(d.set("n", new JsonPrimitive(1), null));
        assertFalse(d.set("n", JsonNull.INSTANCE, null));
        d.markInvalid("x");
        assertTrue(d.hasInvalid());
        assertEquals(1, d.size());
        assertTrue(d.has("x"));
        d.remove("x");
        assertFalse(d.hasInvalid());
    }

    @Test
    void draftBuildsChangesWithExpect() {
        SettingsDraft d = new SettingsDraft();
        JsonObject server = new JsonObject();
        server.addProperty("a", 1);
        d.set("a", new JsonPrimitive(2), server.get("a"));
        d.set("b", new JsonPrimitive("x"), JsonNull.INSTANCE);
        JsonArray c = d.changes(server::get);
        assertEquals(2, c.size());
        JsonObject first = c.get(0).getAsJsonObject();
        assertEquals("a", first.get("path").getAsString());
        assertEquals(2, first.get("value").getAsInt());
        assertEquals(1, first.get("expect").getAsInt());
        JsonElement expectB = c.get(1).getAsJsonObject().get("expect");
        assertTrue(expectB.isJsonNull());
    }

    @Test
    void draftDropsEditsOfSettingsThatWentAway() {
        SettingsDraft d = new SettingsDraft();
        d.set("keep", new JsonPrimitive(1), JsonNull.INSTANCE);
        d.set("gone", new JsonPrimitive(1), JsonNull.INSTANCE);
        d.markInvalid("gone2");
        d.retain("keep"::equals);
        assertEquals(1, d.size());
        assertTrue(d.has("keep"));
    }

    @Test
    void showFormatsValues() {
        assertEquals("(none)", SettingsSchema.show(JsonNull.INSTANCE));
        assertEquals("ON", SettingsSchema.show(new JsonPrimitive(true)));
        assertEquals("(empty)", SettingsSchema.show(new JsonArray()));
        JsonArray a = new JsonArray();
        a.add("x");
        a.add("y");
        assertEquals("x, y", SettingsSchema.show(a));
    }
}
