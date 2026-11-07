package com.porter.qwerte.manager.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.manager.model.UsersModel.Data;
import com.porter.qwerte.manager.model.UsersModel.Perms;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsersModelTest {
    static final String LIST = "{\"owner\":{\"name\":\"Boss\"},\"maxUsers\":50,\"minLength\":14,\"users\":["
        + "{\"id\":\"u-1\",\"name\":\"alice\",\"disabled\":false,\"mustChange\":true,\"perms\":{\"view\":true,\"order\":true,\"proxy\":\"read\",\"stashes\":[{\"server\":\"s\",\"stash\":\"x\"}],\"bots\":null},\"createdAt\":1,\"lastLoginAt\":null,\"sessions\":2},"
        + "{\"id\":\"u-2\",\"name\":\"Bob\",\"disabled\":true,\"perms\":{\"view\":true,\"streamer\":true},\"lastLoginAt\":99,\"sessions\":0}],"
        + "\"presets\":{\"viewer\":{\"view\":true},\"orderer\":{\"view\":true,\"order\":true,\"planner\":true,\"plannerOrder\":true},\"operator\":{\"view\":true,\"order\":true,\"ordersAll\":true,\"activity\":true,\"settings\":true,\"rescan\":true,\"proxy\":\"read\",\"planner\":true,\"plannerOrder\":true,\"viewer\":true}}}";

    static Perms perms(String json) {
        return UsersModel.norm(JsonParser.parseString(json));
    }

    @Test
    void normFillsEveryKeyAndCleansValues() {
        Perms p = perms("{\"view\":true,\"order\":\"yes\",\"proxy\":\"admin\",\"console\":\"list\",\"consoleAllow\":[\"stash orders\",5],\"stashes\":[{\"server\":\"s\",\"stash\":\"t\"},3]}");
        assertTrue(p.flag("view"));
        assertFalse(p.flag("order"));
        assertEquals(UsersModel.SWITCHES.size(), p.flags.size());
        assertEquals("none", p.proxy);
        assertEquals("list", p.console);
        assertEquals(List.of("stash orders"), p.consoleAllow);
        assertEquals(1, p.stashes.size());
        assertNull(p.bots);
        assertNull(UsersModel.norm(null).stashes);
    }

    @Test
    void impliedPermissionsFollowTheServersRule() {
        Perms p = perms("{\"plannerOrder\":true}");
        UsersModel.withImplied(p);
        assertTrue(p.flag("planner"));
        assertTrue(p.flag("view"));
        assertFalse(p.flag("activity"));
        Perms v = perms("{\"viewer\":true}");
        UsersModel.withImplied(v);
        assertTrue(v.flag("activity"));
        Perms c = perms("{\"console\":\"full\"}");
        assertEquals(Set.of("activity"), UsersModel.implied(c));
        Perms o = perms("{\"order\":true,\"settings\":true}");
        assertEquals(Set.of("view", "activity"), UsersModel.implied(o));
        assertTrue(UsersModel.implied(perms("{\"view\":true}")).isEmpty());
    }

    @Test
    void jsonAlwaysWritesStreamerExplicitly() {
        JsonObject off = UsersModel.toJson(perms("{\"view\":true}"));
        assertTrue(off.has("streamer"));
        assertFalse(off.get("streamer").getAsBoolean());
        JsonObject on = UsersModel.toJson(perms("{\"view\":true,\"streamer\":true,\"viewer\":true}"));
        assertTrue(on.get("streamer").getAsBoolean());
        assertTrue(on.get("viewer").getAsBoolean());
        assertTrue(on.get("stashes").isJsonNull());
        assertTrue(on.get("bots").isJsonNull());
        assertEquals("none", on.get("proxy").getAsString());
        Perms scoped = perms("{\"bots\":[\"b1\"],\"stashes\":[]}");
        JsonObject j = UsersModel.toJson(scoped);
        assertEquals(1, j.getAsJsonArray("bots").size());
        assertEquals(0, j.getAsJsonArray("stashes").size());
        // round trip
        assertEquals(scoped, UsersModel.norm(j));
    }

    @Test
    void parsesTheListAndLabelsPresets() {
        Data d = UsersModel.parse(JsonParser.parseString(LIST));
        assertEquals("Boss", d.ownerName());
        assertEquals(14, d.minLength());
        assertEquals(2, d.users().size());
        assertEquals(-1, d.users().get(0).lastLoginAt());
        assertEquals(99, d.users().get(1).lastLoginAt());
        assertTrue(d.users().get(0).mustChange());
        assertTrue(d.users().get(1).disabled());
        assertEquals(List.of("viewer", "orderer", "operator"), List.copyOf(d.presets().keySet()));
        // alice has view+order+proxy read: not a preset
        assertEquals("Custom", UsersModel.presetLabel(d.users().get(0).perms(), d));
        assertEquals("Viewer", UsersModel.presetLabel(perms("{\"view\":true,\"stashes\":[]}"), d));
        assertEquals("Orderer", UsersModel.presetLabel(perms("{\"order\":true,\"plannerOrder\":true}"), d));
        // forced streamer is not part of any preset
        assertEquals("Viewer + forced streamer", UsersModel.presetLabel(d.users().get(1).perms(), d));
        assertEquals("only 1 stash", UsersModel.scopeText(d.users().get(0).perms()));
        assertEquals("", UsersModel.scopeText(perms("{}")));
        assertEquals(Data.EMPTY, UsersModel.parse(JsonParser.parseString("[]")));
    }

    @Test
    void nameAndPasswordChecks() {
        Data d = UsersModel.parse(JsonParser.parseString(LIST));
        assertEquals("", UsersModel.checkName("carol", null, d));
        assertFalse(UsersModel.checkName("bad name", null, d).isEmpty());
        assertFalse(UsersModel.checkName("", null, d).isEmpty());
        assertFalse(UsersModel.checkName("x".repeat(33), null, d).isEmpty());
        assertEquals("That is the owner's name.", UsersModel.checkName("boss", null, d));
        assertEquals("", UsersModel.checkName("boss", "owner", d));
        assertEquals("Another user already has that name.", UsersModel.checkName("ALICE", "u-2", d));
        assertEquals("", UsersModel.checkName("ALICE", "u-1", d));
        assertFalse(UsersModel.checkPassword("short", "short", 12).isEmpty());
        assertFalse(UsersModel.checkPassword("long enough password", "other", 12).isEmpty());
        assertEquals("", UsersModel.checkPassword("long enough password", "long enough password", 12));
    }

    @Test
    void allowListParsing() {
        assertEquals(List.of("stash orders", "stash status"), UsersModel.parseAllow("  stash   orders ; Stash Orders;stash status;;\n"));
        assertEquals("a; b", UsersModel.joinAllow(List.of("a", "b")));
        assertTrue(UsersModel.parseAllow(null).isEmpty());
    }

    @Test
    void auditEntriesAreCleaned() {
        List<UsersModel.AuditEntry> e = UsersModel.parseAudit(JsonParser.parseString(
            "{\"entries\":[{\"at\":5,\"user\":\"a§b\",\"action\":\"login.failed\",\"detail\":\"x\",\"address\":\"1.2.3.4\"},3]}"));
        assertEquals(1, e.size());
        assertEquals("ab", e.get(0).user());
        assertTrue(e.get(0).failed());
        assertTrue(UsersModel.parseAudit(null).isEmpty());
    }

    @Test
    void describeListsWhatIsOn() {
        assertEquals("View stashes, Place orders, Proxy settings: read.", UsersModel.describe(perms("{\"order\":true,\"proxy\":\"read\"}")).replace(", Bots and activity", ""));
        assertEquals("Nothing.", UsersModel.describe(perms("{}")));
    }
}
