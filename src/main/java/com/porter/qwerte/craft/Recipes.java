package com.porter.qwerte.craft;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The crafting recipes of one Minecraft version, from the table generated offline from the game's data pack (see
 * stash-manager-server/dev/recipes). Clients get no full recipes from a server since 1.21.2, so the game's own recipe
 * manager is never used. Pure: no Minecraft classes, so it can be tested on its own.
 *
 * Only crafting_shaped and crafting_shapeless recipes are in the table. Items that have no such recipe but can be made
 * another way are listed with the station that makes them.
 */
public final class Recipes {
    /**
     * @param cells shaped: w*h cells in row order, null for an empty cell; shapeless: one entry per ingredient. Each cell is the list
     *              of items that may be put there.
     */
    public record Recipe(String id, boolean shaped, int w, int h, List<List<String>> cells, String result, int count) {
        /** How many cells hold an ingredient. */
        public int filled() {
            int n = 0;
            for (List<String> c : cells) if (c != null) n++;
            return n;
        }

        /** Cells that take the same alternatives, with how many there are of each. */
        public Map<List<String>, Integer> groups() {
            Map<List<String>, Integer> out = new LinkedHashMap<>();
            for (List<String> c : cells) if (c != null) out.merge(c, 1, Integer::sum);
            return out;
        }

        /** Where cell {@code i} sits in the 3x3 grid, 0 to 8 in row order. */
        public int gridIndex(int i) {
            return shaped ? (i / w) * 3 + (i % w) : i;
        }
    }

    private final String version;
    private final Map<String, List<Recipe>> byResult = new HashMap<>();
    private final Map<String, String> stations = new HashMap<>();
    private int size;

    private Recipes(String version) {
        this.version = version;
    }

    public String version() {
        return version;
    }

    public int size() {
        return size;
    }

    /** The recipes that make this item, in the table's order. */
    public List<Recipe> producing(String item) {
        return byResult.getOrDefault(item, List.of());
    }

    /** The other station that makes the item (smelting, blasting, smoking, campfire_cooking, stonecutting, smithing, transmute, special), or null. */
    public String station(String item) {
        return stations.get(item);
    }

    /** The table for a game version ("1.21.4"), from the resources, or null when there is none. */
    public static Recipes load(String version) {
        String path = "/assets/qwerte/recipes/" + version + ".json";
        try (InputStream in = Recipes.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Reads a table. @throws IllegalArgumentException when it is not one */
    public static Recipes parse(String json) {
        Object root = new Parser(json).value();
        if (!(root instanceof Map<?, ?> top)) throw new IllegalArgumentException("not an object");
        Recipes out = new Recipes(top.get("version") instanceof String s ? s : "");

        if (top.get("recipes") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> r)) continue;
                String id = str(r.get("id")), result = str(r.get("result"));
                if (id == null || result == null) continue;
                boolean shaped = "shaped".equals(r.get("type"));
                List<List<String>> cells = new ArrayList<>();
                Object raw = shaped ? r.get("cells") : r.get("ingredients");
                if (!(raw instanceof List<?> rawCells)) continue;
                for (Object c : rawCells) cells.add(c instanceof List<?> alts ? List.copyOf(strings(alts)) : null);
                int w = shaped ? num(r.get("w")) : cells.size(), h = shaped ? num(r.get("h")) : 1;
                if (shaped && (w < 1 || h < 1 || w > 3 || h > 3 || cells.size() != w * h)) continue;
                if (!shaped && (cells.isEmpty() || cells.size() > 9)) continue;
                int count = Math.max(1, num(r.get("count")));
                Recipe recipe = new Recipe(id, shaped, w, h, java.util.Collections.unmodifiableList(cells), result, count);
                out.byResult.computeIfAbsent(result, k -> new ArrayList<>()).add(recipe);
                out.size++;
            }
        }
        if (top.get("otherStations") instanceof Map<?, ?> others) {
            for (Map.Entry<?, ?> e : others.entrySet()) if (e.getKey() instanceof String k && e.getValue() instanceof String v) out.stations.put(k, v);
        }
        return out;
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static List<String> strings(List<?> in) {
        List<String> out = new ArrayList<>();
        for (Object o : in) if (o instanceof String s) out.add(s);
        return out;
    }

    /** A small JSON reader: objects, arrays, strings, numbers, booleans and null. */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(i);
            switch (c) {
                case '{': {
                    i++;
                    Map<String, Object> map = new LinkedHashMap<>();
                    ws();
                    if (peek('}')) {
                        i++;
                        return map;
                    }
                    while (true) {
                        ws();
                        String key = string();
                        ws();
                        expect(':');
                        map.put(key, value());
                        ws();
                        if (peek(',')) {
                            i++;
                            continue;
                        }
                        expect('}');
                        return map;
                    }
                }
                case '[': {
                    i++;
                    List<Object> list = new ArrayList<>();
                    ws();
                    if (peek(']')) {
                        i++;
                        return list;
                    }
                    while (true) {
                        list.add(value());
                        ws();
                        if (peek(',')) {
                            i++;
                            continue;
                        }
                        expect(']');
                        return list;
                    }
                }
                case '"':
                    return string();
                case 't':
                    i += 4;
                    return Boolean.TRUE;
                case 'f':
                    i += 5;
                    return Boolean.FALSE;
                case 'n':
                    i += 4;
                    return null;
                default: {
                    int start = i;
                    while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
                    if (start == i) throw new IllegalArgumentException("bad character " + c + " at " + i);
                    return Double.valueOf(s.substring(start, i));
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> out.append(e);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private void expect(char c) {
            if (!peek(c)) throw new IllegalArgumentException("expected " + c + " at " + i);
            i++;
        }
    }
}
