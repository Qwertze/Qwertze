package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.porter.qwerte.stash.Order;

import java.util.ArrayList;
import java.util.List;

/**
 * What the shulkers of an order being put together are to be called: nothing, one name for all, or a name for each planned
 * shulker (by position in the delivery). The website's {@code names} state; the body it sends is the same. Pure.
 */
public final class NamesDraft {
    public enum Mode {
        NONE("No names"), ALL("One name for all"), EACH("A name for each");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private Mode mode = Mode.NONE;
    private String all = "";
    private final List<String> each = new ArrayList<>();

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.NONE : mode;
    }

    public String all() {
        return all;
    }

    public void setAll(String text) {
        this.all = text == null ? "" : text;
    }

    public String each(int i) {
        return i >= 0 && i < each.size() ? each.get(i) : "";
    }

    /** Names typed for a shulker are kept by its position while the cart changes. */
    public void setEach(int i, String text) {
        if (i < 0 || i >= Order.MAX_NAMES) return;
        while (each.size() <= i) each.add("");
        each.set(i, text == null ? "" : text);
    }

    public void reset() {
        mode = Mode.NONE;
        all = "";
        each.clear();
    }

    /** How many of {@code units} planned shulkers would get a name. */
    public int namedCount(int units) {
        if (mode == Mode.ALL) return all.trim().isEmpty() ? 0 : units;
        if (mode != Mode.EACH) return 0;
        int n = 0;
        for (int i = 0; i < Math.min(units, each.size()); i++) if (!each.get(i).trim().isEmpty()) n++;
        return n;
    }

    /** The first name the anvil would refuse, as a message, or "". */
    public String problem(int units) {
        if (mode == Mode.ALL) {
            String t = all.trim();
            return t.isEmpty() || Order.valid(t) ? "" : "The name can be up to " + Order.MAX_NAME + " characters with no section sign or control characters.";
        }
        if (mode != Mode.EACH) return "";
        for (int i = 0; i < Math.min(units, each.size()); i++) {
            String t = each.get(i).trim();
            if (!t.isEmpty() && !Order.valid(t)) return "Shulker " + (i + 1) + ": a name can be up to " + Order.MAX_NAME + " characters with no section sign or control characters.";
        }
        return "";
    }

    /** The names as the order engine takes them, or null when nothing is to be named. */
    public Order.Names toNames(int units) {
        if (mode == Mode.ALL) return Order.Names.of(Order.Names.ALL, all, null);
        if (mode != Mode.EACH) return null;
        List<String> list = new ArrayList<>();
        for (int i = 0; i < Math.min(units, Order.MAX_NAMES); i++) list.add(each(i).trim());
        return Order.Names.of(Order.Names.EACH, "", list);
    }

    /** The {@code names} field of {@code POST /api/v1/orders}, or null for none. */
    public JsonObject toJson(int units) {
        Order.Names n = toNames(units);
        if (n == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("mode", n.mode());
        if (Order.Names.ALL.equals(n.mode())) {
            o.addProperty("name", n.all());
        } else {
            JsonArray array = new JsonArray();
            for (String s : n.each()) array.add(s);
            o.add("names", array);
        }
        return o;
    }
}
