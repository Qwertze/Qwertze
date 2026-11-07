package com.porter.stashmanager.planner;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** A block id plus its properties, sorted by name. Equal states from different palettes compare equal. */
public record BlockState(String name, SortedMap<String, String> properties) implements Comparable<BlockState> {
    /**
     * Comparable so a HashMap bin of states with colliding hash codes (a hostile palette) is a sorted tree:
     * O(log n) per lookup instead of a linear walk.
     */
    @Override
    public int compareTo(BlockState o) {
        int c = name.compareTo(o.name);
        if (c != 0) return c;
        java.util.Iterator<Map.Entry<String, String>> a = properties.entrySet().iterator(), b = o.properties.entrySet().iterator();
        while (a.hasNext() && b.hasNext()) {
            Map.Entry<String, String> x = a.next(), y = b.next();
            c = x.getKey().compareTo(y.getKey());
            if (c != 0) return c;
            c = x.getValue().compareTo(y.getValue());
            if (c != 0) return c;
        }
        return Boolean.compare(a.hasNext(), b.hasNext());
    }

    public BlockState {
        name = normalizeId(name);
        properties = Collections.unmodifiableSortedMap(new TreeMap<>(properties == null ? Map.of() : properties));
    }

    public static BlockState of(String name) {
        return new BlockState(name, null);
    }

    /** A property value, or null. */
    public String property(String key) {
        return properties.get(key);
    }

    /**
     * Parses {@code minecraft:stone[facing=north,half=top]} (Sponge palette keys). Malformed property parts are
     * skipped rather than failing the whole schematic.
     */
    public static BlockState parse(String s) {
        int open = s.indexOf('[');
        if (open < 0) return of(s);
        String name = s.substring(0, open);
        int close = s.lastIndexOf(']');
        String body = close > open ? s.substring(open + 1, close) : s.substring(open + 1);
        TreeMap<String, String> props = new TreeMap<>();
        for (String part : body.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            props.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return new BlockState(name, props);
    }

    /** Adds the {@code minecraft:} namespace when there is none. */
    static String normalizeId(String id) {
        String s = id == null ? "" : id.trim();
        return s.indexOf(':') < 0 ? "minecraft:" + s : s;
    }

    @Override
    public String toString() {
        if (properties.isEmpty()) return name;
        StringBuilder b = new StringBuilder(name).append('[');
        boolean first = true;
        for (var e : properties.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append(e.getKey()).append('=').append(e.getValue());
        }
        return b.append(']').toString();
    }
}
