package com.porter.qwerte.tweaks;

/**
 * The saved text of an item left in the world: {@code id|x|y|z|dimension|server|name}. Older entries have no server
 * ({@code id|x|y|z|dimension|name}) and match any server. Pure: no game classes.
 */
public final class Orphans {
    private Orphans() {}

    /** All fields as saved text; the coordinates are not parsed here so that a damaged entry can be told apart later. */
    public record Entry(String id, String x, String y, String z, String dimension, String server, String name) {}

    /** The line to save. A pipe in the server or the name is replaced, as it would break the format. */
    public static String format(String id, int x, int y, int z, String dimension, String server, String name) {
        return id + "|" + x + "|" + y + "|" + z + "|" + dimension + "|" + server.replace('|', '/') + "|" + name.replace('|', '/');
    }

    /** The seven-part form, or the old six-part form (server ""), or null if it is neither. */
    public static Entry parse(String entry) {
        String[] part = entry.split("\\|", 7);
        if (part.length == 7) return new Entry(part[0], part[1], part[2], part[3], part[4], part[5], part[6]);
        part = entry.split("\\|", 6);
        if (part.length == 6) return new Entry(part[0], part[1], part[2], part[3], part[4], "", part[5]);
        return null;
    }

    /** True if the entry is for this server; an old entry without a server matches any. */
    public static boolean here(Entry entry, String server) {
        return entry.server().isEmpty() || entry.server().equals(server);
    }
}
