package com.porter.qwerte.printer.source;

import com.porter.qwerte.printer.schematic.SchematicFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Printer's schematic queue: a list of entries built one after the other. An entry is a Litematica placement name,
 * or {@code file:<name>} (a .litematic below the schematics folder), optionally {@code file:<name>@x,y,z} to give that
 * file its own origin. Parsing is strict; a bad entry is reported and left out. The queue only holds the position:
 * checking that an entry can be built is the caller's resolver. Pure (no Minecraft objects).
 */
public final class BuildQueue {
    public static final int MAX_ENTRIES = 50;
    public static final int MAX_PLACEMENT_NAME = 100;
    private static final String FILE_PREFIX = "file:";
    private static final Pattern ORIGIN = Pattern.compile("(-?\\d{1,8}),(-?\\d{1,8}),(-?\\d{1,8})");

    public enum Kind { PLACEMENT, FILE }

    /**
     * @param name   the placement name, or the file name as typed (the .litematic ending is optional)
     * @param origin x, y, z for a file entry that has its own origin; null otherwise
     */
    public record Entry(Kind kind, String name, int[] origin, String raw) {
        public String label() {
            return kind == Kind.FILE ? stripExtension(name) : name;
        }
    }

    /** The entries that parsed, and one message per entry that did not. */
    public record Parsed(List<Entry> entries, List<String> errors) {
    }

    public static Parsed parse(List<String> raw) {
        List<Entry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (raw == null) return new Parsed(entries, errors);
        int n = 0;
        for (String line : raw) {
            n++;
            if (line == null || line.isBlank()) continue;
            if (entries.size() >= MAX_ENTRIES) {
                errors.add("Entry " + n + " ignored: at most " + MAX_ENTRIES + " entries are used.");
                continue;
            }
            String s = line.strip();
            String problem = null;
            if (s.regionMatches(true, 0, FILE_PREFIX, 0, FILE_PREFIX.length())) {
                String rest = s.substring(FILE_PREFIX.length()).strip();
                int[] origin = null;
                int at = rest.lastIndexOf('@');
                if (at >= 0) {
                    Matcher m = ORIGIN.matcher(rest.substring(at + 1).strip());
                    if (!m.matches()) {
                        problem = "the origin after @ must be x,y,z (whole numbers)";
                    } else {
                        origin = new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)) };
                        rest = rest.substring(0, at).strip();
                    }
                }
                if (problem == null) {
                    String[] norm = SchematicFile.normalize(rest);
                    if (norm[0] == null) problem = norm[1];
                    else entries.add(new Entry(Kind.FILE, rest, origin, s));
                }
            } else if (s.length() > MAX_PLACEMENT_NAME) {
                problem = "the placement name is too long";
            } else if (hasControl(s)) {
                problem = "the name has control characters";
            } else {
                entries.add(new Entry(Kind.PLACEMENT, s, null, s));
            }
            if (problem != null) errors.add("Entry " + n + " ignored (" + shown(s) + "): " + problem);
        }
        return new Parsed(entries, errors);
    }

    private static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) return true;
        }
        return false;
    }

    private static String shown(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 40; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != 0x7f && c != '%' && c != '§') sb.append(c);
        }
        return sb.toString();
    }

    private static String stripExtension(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(SchematicFile.EXTENSION) ? name.substring(0, name.length() - SchematicFile.EXTENSION.length()) : name;
    }

    // ---------- position ----------

    private final List<Entry> entries;
    private int index;
    private int completed;
    private int skipped;

    public BuildQueue(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    /** The zero-based position of the current entry; {@code size()} when the queue is used up. */
    public int index() {
        return index;
    }

    public boolean hasCurrent() {
        return index < entries.size();
    }

    public Entry current() {
        return hasCurrent() ? entries.get(index) : null;
    }

    /** Entries that were built to the end. */
    public int completed() {
        return completed;
    }

    /** Entries that were passed over because they could not be found or loaded. */
    public int skipped() {
        return skipped;
    }

    /** The current entry is done: counts it and moves on. */
    public void complete() {
        if (hasCurrent()) {
            completed++;
            index++;
        }
    }

    /** The current entry can't be built: counts it as skipped and moves on. */
    public void skip() {
        if (hasCurrent()) {
            skipped++;
            index++;
        }
    }

    /**
     * Moves past entries the resolver refuses, to the first one it accepts (the current one when that is fine).
     * The resolver gives null for "can be built" or the reason it can't; {@code onSkip} hears each refused entry.
     * Returns false when nothing is left.
     */
    public boolean seek(Function<Entry, String> resolver, BiConsumer<Entry, String> onSkip) {
        while (hasCurrent()) {
            Entry e = current();
            String why = resolver.apply(e);
            if (why == null) return true;
            onSkip.accept(e, why);
            skip();
        }
        return false;
    }

    /** "Queue 2/5: name"; the last entry once the queue is used up. */
    public String label() {
        if (entries.isEmpty()) return "Queue 0/0";
        int shown = Math.min(index, entries.size() - 1);
        return "Queue " + (shown + 1) + "/" + entries.size() + ": " + entries.get(shown).label();
    }
}
