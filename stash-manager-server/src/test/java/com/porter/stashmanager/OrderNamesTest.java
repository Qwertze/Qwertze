package com.porter.stashmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Order names (normalising and validation), notes removed, and retrying failed or cancelled orders. */
class OrderNamesTest {
    @TempDir
    Path data;
    IndexStore index;
    OrderStore orders;

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static JsonObject names(String s) throws OrderStore.OrderException {
        return OrderStore.normalizeNames(JsonParser.parseString(s));
    }

    private static int status(String s) {
        return assertThrows(OrderStore.OrderException.class, () -> names(s)).status;
    }

    @BeforeEach
    void setUp() throws Exception {
        index = new IndexStore(data, CreativeTabs.NONE);
        index.load();
        index.putIndex(json("{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":["
            + "{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":1000,\"maxStack\":64}]}"));
        orders = new OrderStore(data, index);
        orders.load();
    }

    private static List<OrderStore.Line> diamonds(long n) {
        return List.of(new OrderStore.Line("item", "minecraft:diamond", n));
    }

    @Test
    void namesAreNormalised() throws Exception {
        assertNull(names("null"));
        assertNull(OrderStore.normalizeNames(null));
        assertNull(names("{\"mode\":\"all\",\"name\":\"   \"}"));
        assertEquals(json("{\"mode\":\"all\",\"name\":\"Food\"}"), names("{\"mode\":\"all\",\"name\":\"  Food \"}"));
        assertNull(names("{\"mode\":\"each\",\"names\":[]}"));
        assertNull(names("{\"mode\":\"each\",\"names\":[\"\",\"  \",null]}"));
        // Trimmed, blanks kept as "" for their index, trailing blanks dropped.
        assertEquals(json("{\"mode\":\"each\",\"names\":[\"\",\"B\"]}"), names("{\"mode\":\"each\",\"names\":[\" \",\" B \",\"\",\"  \"]}"));

        String fifty = "x".repeat(50);
        assertEquals(fifty, names("{\"mode\":\"all\",\"name\":\"" + fifty + "\"}").get("name").getAsString());
        assertEquals(fifty, names("{\"mode\":\"all\",\"name\":\"  " + fifty + "  \"}").get("name").getAsString());
        assertEquals(400, status("{\"mode\":\"all\",\"name\":\"" + fifty + "y\"}"));
        assertEquals(400, status("{\"mode\":\"each\",\"names\":[\"" + fifty + "y\"]}"));

        assertEquals(400, status("{\"mode\":\"all\",\"name\":\"a§cb\"}"));
        assertEquals(400, status("{\"mode\":\"all\",\"name\":\"a\\u0007b\"}"));
        assertEquals(400, status("{\"mode\":\"each\",\"names\":[\"ok\",\"a\\nb\"]}"));
        assertEquals(400, status("{\"mode\":\"all\",\"name\":\"a\\u007fb\"}"));
        // Invisible or direction-changing characters and broken surrogates are refused; a real emoji (a surrogate pair) is fine
        assertEquals(400, status("{\"mode\":\"all\",\"name\":\"a\\u202eb\"}"));
        assertEquals(400, status("{\"mode\":\"all\",\"name\":\"a\\u200bb\"}"));
        assertEquals(400, status("{\"mode\":\"all\",\"name\":\"a\\u2028b\"}"));
        assertEquals(400, status("{\"mode\":\"all\",\"name\":\"a\\ud83db\"}"));
        assertEquals("a😀b", names("{\"mode\":\"all\",\"name\":\"a\\ud83d\\ude00b\"}").get("name").getAsString());

        assertEquals(400, status("\"all\""));
        assertEquals(400, status("[]"));
        assertEquals(400, status("{}"));
        assertEquals(400, status("{\"mode\":\"some\",\"name\":\"x\"}"));
        assertEquals(400, status("{\"mode\":1}"));
        assertEquals(400, status("{\"mode\":\"all\"}"));
        assertEquals(400, status("{\"mode\":\"all\",\"name\":5}"));
        assertEquals(400, status("{\"mode\":\"each\",\"names\":\"x\"}"));
        assertEquals(400, status("{\"mode\":\"each\",\"names\":[\"a\",3]}"));
        assertEquals(400, status("{\"mode\":\"each\",\"names\":[[\"a\"]]}"));

        StringBuilder list54 = new StringBuilder("{\"mode\":\"each\",\"names\":[");
        for (int i = 0; i < 54; i++) list54.append(i == 0 ? "" : ",").append("\"n").append(i).append('"');
        assertEquals(54, names(list54 + "]}").getAsJsonArray("names").size());
        assertEquals(400, status(list54 + ",\"n54\"]}"));
    }

    @Test
    void namesAreStoredAndReturned() throws Exception {
        JsonObject each = names("{\"mode\":\"each\",\"names\":[\"A\",\"\",\"C\"]}");
        OrderStore.Order o = orders.place("s", "a", diamonds(64), each);
        assertEquals(each, o.toJson().getAsJsonObject("names"));
        OrderStore.Order plain = orders.place("s", "a", diamonds(1), null);
        assertTrue(plain.toJson().has("names") && plain.toJson().get("names").isJsonNull());

        // Claim gives the bot the names; they survive a restart.
        assertEquals(each, orders.claim("s", "a").toJson().getAsJsonObject("names"));
        OrderStore reloaded = new OrderStore(data, index);
        reloaded.load();
        String list = reloaded.listJson("s", "a").toString();
        assertTrue(list.contains("\"names\":{\"mode\":\"each\",\"names\":[\"A\",\"\",\"C\"]}"), list);
    }

    @Test
    void noteIsGoneEvenFromOldOrders() throws Exception {
        OrderStore.Order o = orders.place("s", "a", diamonds(5), null);
        assertFalse(o.toJson().has("note"));

        // An order saved by an older server, with a note.
        Path dir = data.resolve("orders");
        Path file = dir.resolve(IndexStore.fileName("s", "a"));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject root = json(text);
        root.getAsJsonArray("orders").get(0).getAsJsonObject().addProperty("note", "leave at the door");
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);

        OrderStore reloaded = new OrderStore(data, index);
        reloaded.load();
        JsonObject listed = reloaded.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject();
        assertFalse(listed.has("note"));
        assertTrue(listed.get("names").isJsonNull());
        assertFalse(reloaded.claim("s", "a").toJson().has("note"));
        // The next save drops it from disk too.
        assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("leave at the door"));
    }

    private OrderStore.Order fail(OrderStore.Order o) throws Exception {
        orders.claim("s", "a");
        return orders.status(o.id, "failed", null, null, "stuck", null);
    }

    @Test
    void retryMakesANewPendingOrder() throws Exception {
        JsonObject all = names("{\"mode\":\"all\",\"name\":\"Gems\"}");
        OrderStore.Order failed = fail(orders.place("s", "a", diamonds(100), all));
        assertEquals("failed", failed.status);

        OrderStore.Order again = orders.retry(failed.id);
        assertNotEquals(failed.id, again.id);
        assertEquals("pending", again.status);
        assertEquals("s", again.server);
        assertEquals("a", again.stash);
        assertEquals(failed.lines, again.lines);
        assertEquals(all, again.names);
        assertEquals("failed", failed.status); // the old order is left as it is
        assertNull(again.message);

        // Cancelled orders can be retried too.
        OrderStore.Order cancelled = orders.cancel(orders.place("s", "a", diamonds(1), null).id);
        assertEquals("cancelled", cancelled.status);
        OrderStore.Order again2 = orders.retry(cancelled.id);
        assertEquals("pending", again2.status);
        assertNull(again2.names);

        // Pending, accepted and delivered: 409. Unknown: 404.
        assertEquals(409, assertThrows(OrderStore.OrderException.class, () -> orders.retry(again.id)).status);
        OrderStore.Order claimed = orders.claim("s", "a");
        assertEquals("accepted", claimed.status);
        assertEquals(409, assertThrows(OrderStore.OrderException.class, () -> orders.retry(claimed.id)).status);
        OrderStore.Order delivered = orders.status(claimed.id, "delivered", null, null, null, null);
        assertEquals(409, assertThrows(OrderStore.OrderException.class, () -> orders.retry(delivered.id)).status);
        assertEquals(404, assertThrows(OrderStore.OrderException.class, () -> orders.retry("o-000000000000")).status);
    }

    @Test
    void retryChecksAvailabilityAndTheOpenLimit() throws Exception {
        OrderStore.Order failed = fail(orders.place("s", "a", diamonds(900), null));
        // 900 of 1000 are free again; reserve 200 so only 800 are left.
        OrderStore.Order reserve = orders.place("s", "a", diamonds(200), null);
        OrderStore.OrderException e = assertThrows(OrderStore.OrderException.class, () -> orders.retry(failed.id));
        assertEquals(400, e.status);
        assertTrue(e.getMessage().contains("only 800 available"), e.getMessage());
        assertEquals(0, e.line);

        orders.cancel(reserve.id);
        OrderStore.Order small = fail(orders.place("s", "a", diamonds(1), null));
        List<OrderStore.Order> open = new ArrayList<>();
        for (int i = 0; i < OrderStore.MAX_OPEN; i++) open.add(orders.place("s", "a", diamonds(1), null));
        e = assertThrows(OrderStore.OrderException.class, () -> orders.retry(small.id));
        assertEquals(400, e.status);
        assertTrue(e.getMessage().contains("too many open orders"), e.getMessage());

        // The stash's index shrinks: the item is gone.
        for (OrderStore.Order o : open) orders.cancel(o.id);
        index.putIndex(json("{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":[]}"));
        e = assertThrows(OrderStore.OrderException.class, () -> orders.retry(small.id));
        assertEquals(400, e.status);
        assertTrue(e.getMessage().contains("not in the stash"), e.getMessage());
    }
}
