package com.porter.qwerte.manager.model;

import com.google.gson.JsonParser;
import com.porter.qwerte.manager.model.ActivityModel.Data;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityModelTest {
    static final String BODY = "{\"receivedAt\":1000,\"online\":true,\"activity\":{\"connection\":{\"state\":\"queue\",\"queuePosition\":5,\"queueEta\":\"10m\","
        + "\"server\":\"2b2t.org\",\"dimension\":\"minecraft:overworld\",\"x\":10.4,\"y\":64,\"z\":-3,\"health\":18,\"food\":20,\"ping\":55,"
        + "\"uptimeSeconds\":3700,\"spectators\":2,\"controllingPlayer\":\"Steve\"},"
        + "\"current\":[{\"source\":\"orders\",\"title\":\"Order 7\",\"detail\":\"chest 1\",\"level\":\"warn\",\"since\":500,\"progress\":{\"done\":3,\"total\":12}},"
        + "{\"source\":\"x\",\"title\":\"t\",\"level\":\"weird\"}],"
        + "\"modules\":[{\"name\":\"KillAura\",\"plugin\":\"\",\"status\":\"idle\",\"enabled\":true},{\"name\":\"Fly\",\"plugin\":\"extra\",\"status\":\"\",\"enabled\":false}],"
        + "\"plugins\":[{\"id\":\"qs\",\"name\":\"Qwerte\",\"version\":\"1.2\"}]},"
        + "\"events\":[{\"at\":300,\"source\":\"b\",\"level\":\"error\",\"text\":\"newer\"},{\"at\":100,\"source\":\"a\",\"level\":\"info\",\"text\":\"older\"}]}";

    static Data data() {
        return ActivityModel.parse(JsonParser.parseString(BODY));
    }

    @Test
    void parsesTheWebsiteFields() {
        Data d = data();
        assertTrue(d.online());
        assertTrue(d.hasActivity());
        assertEquals(1000, d.receivedAt());
        ActivityModel.Connection c = d.connection();
        assertEquals("queue", c.state());
        assertEquals(5, c.queuePosition());
        assertEquals("In queue #5 · ETA 10m", ActivityModel.badge(c));
        assertTrue(c.hasPosition());
        assertEquals(18, c.health());
        assertEquals("overworld", ActivityModel.dimension(c.dimension()));
        assertEquals(2, d.current().size());
        ActivityModel.Task t = d.current().get(0);
        assertEquals("warn", t.level());
        assertTrue(t.hasProgress());
        assertEquals(3, t.done());
        assertEquals(12, t.total());
        assertEquals("info", d.current().get(1).level());
        assertFalse(d.current().get(1).hasProgress());
        assertEquals(1, d.modulesOn());
        assertEquals(1, d.plugins().size());
    }

    @Test
    void eventsAreOldestFirst() {
        List<ActivityModel.Event> ev = data().events();
        assertEquals("older", ev.get(0).text());
        assertEquals("newer", ev.get(1).text());
    }

    @Test
    void garbageGivesDefaults() {
        assertFalse(ActivityModel.parse(null).hasActivity());
        assertFalse(ActivityModel.parse(JsonParser.parseString("[1,2]")).hasActivity());
        Data d = ActivityModel.parse(JsonParser.parseString("{\"activity\":null,\"events\":\"x\"}"));
        assertFalse(d.hasActivity());
        assertTrue(d.events().isEmpty());
        Data e = ActivityModel.parse(JsonParser.parseString("{\"activity\":{\"connection\":{\"health\":\"bad\"},\"modules\":[3,{\"name\":5}]}}"));
        assertTrue(Double.isNaN(e.connection().health()));
        assertEquals("offline", e.connection().state());
        assertEquals(1, e.modules().size());
    }

    @Test
    void textsAreCleaned() {
        Data d = ActivityModel.parse(JsonParser.parseString("{\"events\":[{\"at\":1,\"source\":\"s\",\"level\":\"info\",\"text\":\"a\u00a7cb\\nc\"}]}"));
        assertFalse(d.events().get(0).text().contains("§"));
        assertEquals("acb c".length(), d.events().get(0).text().length());
    }

    @Test
    void durationsAndStaleness() {
        assertEquals("5s", ActivityModel.duration(5_000));
        assertEquals("3m", ActivityModel.duration(190_000));
        assertEquals("2h 5m", ActivityModel.duration(7_500_000));
        assertEquals("3d 4h", ActivityModel.duration((3 * 24 + 4) * 3_600_000L));
        assertEquals("", ActivityModel.updated(0, 5000));
        assertEquals("last update 5s ago", ActivityModel.updated(1000, 6000));
        assertTrue(ActivityModel.updated(1000, 100_000).contains("stale"));
        assertTrue(ActivityModel.stale(1000, 100_000));
        assertFalse(ActivityModel.stale(1000, 30_000));
    }

    @Test
    void filtersEventsAndModules() {
        Data d = data();
        assertEquals(List.of("a", "b"), ActivityModel.sources(d.events()));
        assertEquals(1, ActivityModel.filterEvents(d.events(), "a", "").size());
        assertEquals(1, ActivityModel.filterEvents(d.events(), "", "error").size());
        assertEquals(0, ActivityModel.filterEvents(d.events(), "a", "error").size());
        assertEquals(2, ActivityModel.filterEvents(d.events(), "", "").size());
        var all = ActivityModel.filterModules(d.modules(), "", false);
        assertEquals(List.of("aquarius", "extra"), List.copyOf(all.keySet()));
        assertEquals(List.of("aquarius"), List.copyOf(ActivityModel.filterModules(d.modules(), "", true).keySet()));
        assertEquals(List.of("extra"), List.copyOf(ActivityModel.filterModules(d.modules(), "FLY", false).keySet()));
        assertEquals(List.of("aquarius"), List.copyOf(ActivityModel.filterModules(d.modules(), "idle", false).keySet()));
        assertTrue(ActivityModel.filterModules(d.modules(), "nothing", false).isEmpty());
        assertEquals("AquariusProxy", ActivityModel.groupTitle(""));
        assertEquals("extra", ActivityModel.groupTitle("extra"));
    }

    @Test
    void signaturesChangeOnlyWithContent() {
        Data d = data();
        String s = ActivityModel.structureSignature(d, 10_000, 30_000);
        assertEquals(s, ActivityModel.structureSignature(data(), 10_000, 30_000));
        // later, within the same age bucket of the task: no change
        assertEquals(s, ActivityModel.structureSignature(d, 20_000, 30_000));
        assertNotEquals(s, ActivityModel.structureSignature(d, 100_000, 30_000));
        assertEquals(ActivityModel.structureSignature(d, 10_000, 0), ActivityModel.structureSignature(d, 900_000, 0));
        String other = BODY.replace("\"done\":3", "\"done\":4");
        assertNotEquals(s, ActivityModel.structureSignature(ActivityModel.parse(JsonParser.parseString(other)), 10_000, 30_000));
        // events do not touch the structure, but the events signature
        String moreEvents = BODY.replace("\"text\":\"older\"", "\"text\":\"older2\"");
        Data m = ActivityModel.parse(JsonParser.parseString(moreEvents));
        assertEquals(s, ActivityModel.structureSignature(m, 10_000, 30_000));
        assertNotEquals(ActivityModel.eventsSignature(d), ActivityModel.eventsSignature(m));
    }

    @Test
    void theSignatureIgnoresTheNumbersThatMoveAllTheTime() {
        String s = ActivityModel.structureSignature(data(), 10_000, 30_000);
        // position, health, food, ping, uptime and spectators change with every report: no rebuild
        String moved = BODY.replace("\"x\":10.4", "\"x\":99.9").replace("\"y\":64", "\"y\":70").replace("\"z\":-3", "\"z\":8")
            .replace("\"health\":18", "\"health\":11").replace("\"food\":20", "\"food\":6").replace("\"ping\":55", "\"ping\":120")
            .replace("\"uptimeSeconds\":3700", "\"uptimeSeconds\":9000").replace("\"spectators\":2", "\"spectators\":5");
        assertNotEquals(BODY, moved);
        assertEquals(s, ActivityModel.structureSignature(ActivityModel.parse(JsonParser.parseString(moved)), 10_000, 30_000));
        // what the layout does depend on still counts: the state, the server, the dimension, the controller, a stat appearing
        for (String[] change : new String[][]{{"\"state\":\"queue\"", "\"state\":\"online\""}, {"2b2t.org", "other.org"},
            {"minecraft:overworld", "minecraft:the_nether"}, {"Steve", "Alex"}, {"\"ping\":55,", ""}}) {
            Data other = ActivityModel.parse(JsonParser.parseString(BODY.replace(change[0], change[1])));
            assertNotEquals(s, ActivityModel.structureSignature(other, 10_000, 30_000), change[0]);
        }
    }

    @Test
    void clockUsesTheZone() {
        assertEquals("00:00:01", ActivityModel.clock(1000, ZoneId.of("UTC")));
    }
}
