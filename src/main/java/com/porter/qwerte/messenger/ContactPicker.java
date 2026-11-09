package com.porter.qwerte.messenger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The list behind the New button: who you can start a conversation with. Friends first, then the players online, then
 * the people you already talk to; the typed text filters all three. Pure: no Minecraft classes.
 */
public final class ContactPicker {
    /** A Minecraft username. */
    public static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    public enum Source {
        /** "Message <name>": the typed text, which is a valid name that is not in the list. */
        TYPED,
        FRIEND,
        ONLINE,
        CONVERSATION
    }

    public record Entry(String name, Source source) {
    }

    private ContactPicker() {
    }

    public static boolean validName(String s) {
        return s != null && USERNAME.matcher(s).matches();
    }

    /**
     * @param query         what the user typed (may be empty or null)
     * @param friends       Meteor friends
     * @param online        players on the tab list
     * @param conversations names of existing conversations, in the order to show them
     * @param self          the player's own name (never offered); may be null
     */
    public static List<Entry> build(String query, List<String> friends, List<String> online, List<String> conversations, String self) {
        String q = query == null ? "" : query.strip();
        String ql = q.toLowerCase(Locale.ROOT);
        String selfKey = self == null ? null : self.toLowerCase(Locale.ROOT);

        // One list without doubles (the first group keeps the name), without yourself and without names that can not be messaged
        Set<String> seen = new HashSet<>();
        List<Entry> all = new ArrayList<>();
        addGroup(all, seen, friends, Source.FRIEND, selfKey);
        addGroup(all, seen, online, Source.ONLINE, selfKey);
        addGroup(all, seen, conversations, Source.CONVERSATION, selfKey);

        List<Entry> out = new ArrayList<>();
        if (ql.isEmpty()) {
            out.addAll(all);
            return out;
        }
        // Within a group: names starting with the text first, then the others that contain it (each in its own order)
        for (Source group : new Source[]{Source.FRIEND, Source.ONLINE, Source.CONVERSATION}) {
            List<Entry> prefix = new ArrayList<>();
            List<Entry> inside = new ArrayList<>();
            for (Entry e : all) {
                if (e.source() != group) continue;
                String n = e.name().toLowerCase(Locale.ROOT);
                if (n.startsWith(ql)) prefix.add(e);
                else if (n.contains(ql)) inside.add(e);
            }
            out.addAll(prefix);
            out.addAll(inside);
        }
        // Someone whose name is exactly what was typed comes first: Enter must not pick "Steve2" for "Steve"
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).name().equalsIgnoreCase(q)) {
                out.add(0, out.remove(i));
                break;
            }
        }
        // A valid name that is nowhere in the list can still be messaged
        if (validName(q) && !seen.contains(ql) && !ql.equals(selfKey)) out.add(0, new Entry(q, Source.TYPED));
        return out;
    }

    private static void addGroup(List<Entry> out, Set<String> seen, List<String> names, Source source, String selfKey) {
        if (names == null) return;
        for (String n : names) {
            if (!validName(n)) continue;
            String key = n.toLowerCase(Locale.ROOT);
            if (key.equals(selfKey) || !seen.add(key)) continue;
            out.add(new Entry(n, source));
        }
    }
}
