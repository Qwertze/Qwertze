package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The console of one bot: the entries {@code GET /api/v1/bots/{id}/console?after=<rev>} gave so far, the command checks the
 * website makes before sending, and the command history. Pure.
 *
 * <p>The server keeps a 200-entry ring: an answer carries the entries changed after {@code rev}, and {@code first}, the oldest
 * one still kept (older ones are dropped here too).
 */
public final class ConsoleLog {
    /** The longest command the server takes. */
    public static final int MAX_COMMAND = 500;
    public static final int MAX_ENTRIES = 200;
    public static final int MAX_HISTORY = 100;
    private static final int MAX_OUTPUTS = 20;
    private static final int MAX_LINES = 200;
    private static final int MAX_FIELDS = 50;

    /** Commands that can disconnect the bot, restart the proxy or change its login (the website CONSOLE_CONFIRM). */
    public static final List<String> CONFIRM_WORDS = List.of("shutdown", "update", "disconnect", "auth", "jvmargs", "plugins",
        "releasechannel", "discordmanage");

    public record Field(String name, String value) {
    }

    /** {@code color}: error, success, primary, queue, #RRGGBB or empty. */
    public record Output(String color, String title, String description, List<Field> fields, List<String> lines) {
    }

    public record Entry(long seq, String command, boolean hidden, long at, String status, String error, List<Output> outputs) {
        /** The server still waits for the bot (the console polls fast then). */
        public boolean pending() {
            return status.equals("queued") || status.equals("sent");
        }
    }

    /** One drawn line; {@code rgb} is 0xRRGGBB. */
    public record Line(String text, int rgb) {
    }

    public static final int TEXT = 0xDDDDDD;
    public static final int DIM = 0xAAAAB4;
    public static final int GOOD = 0x78DC8C;
    public static final int BAD = 0xFF6E6E;
    public static final int WARN = 0xFFBE50;
    public static final int BLUE = 0x7FA6FF;

    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    // bold marks (two stars) and code marks (a backtick, written as a unicode escape)
    private static final Pattern RICH = Pattern.compile("\\*\\*([^*\\n]+?)\\*\\*|`([^`\\n]+?)`");

    private final TreeMap<Long, Entry> entries = new TreeMap<>();
    private long rev;
    private boolean online = true;

    public long rev() {
        return rev;
    }

    /** False once an answer said the bot is offline. */
    public boolean online() {
        return online;
    }

    public int size() {
        return entries.size();
    }

    public List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    public void clear() {
        entries.clear();
        rev = 0;
        online = true;
    }

    /** True while some entry waits for the bot. */
    public boolean pending() {
        for (Entry e : entries.values()) if (e.pending()) return true;
        return false;
    }

    /** Takes an answer; returns true when what is shown changed. Anything that is not an answer changes nothing. */
    public boolean apply(JsonElement body) {
        JsonObject o = Json.obj(body);
        if (o == null) return false;
        boolean changed = false;
        JsonArray arr = Json.arr(o.get("entries"));
        if (arr != null) {
            for (JsonElement e : arr) {
                Entry entry = parseEntry(Json.obj(e));
                if (entry == null) continue;
                Entry old = entries.put(entry.seq(), entry);
                if (!entry.equals(old)) changed = true;
            }
        }
        long first = Json.lng(o, "first", 0);
        while (!entries.isEmpty() && entries.firstKey() < first) {
            entries.pollFirstEntry();
            changed = true;
        }
        while (entries.size() > MAX_ENTRIES) {
            entries.pollFirstEntry();
            changed = true;
        }
        if (o.has("rev")) rev = Math.max(0, Json.lng(o, "rev", rev));
        boolean on = !o.has("online") || Json.bool(o, "online");
        if (on != online) {
            online = on;
            changed = true;
        }
        return changed;
    }

    static Entry parseEntry(JsonObject o) {
        if (o == null || !o.has("seq")) return null;
        long seq = Json.lng(o, "seq", -1);
        if (seq < 0) return null;
        boolean hidden = Json.bool(o, "hidden");
        List<Output> outs = new ArrayList<>();
        JsonArray oa = Json.arr(o.get("outputs"));
        if (oa != null) {
            for (JsonElement e : oa) {
                Output out = parseOutput(Json.obj(e));
                if (out != null && outs.size() < MAX_OUTPUTS) outs.add(out);
            }
        }
        return new Entry(seq, hidden ? "" : Text.sanitize(Json.str(o, "command"), MAX_COMMAND), hidden, Json.lng(o, "at", 0),
            Text.sanitize(Json.str(o, "status"), 16), Text.sanitize(Json.str(o, "error"), 300), List.copyOf(outs));
    }

    private static Output parseOutput(JsonObject o) {
        if (o == null) return null;
        List<Field> fields = new ArrayList<>();
        JsonArray fa = Json.arr(o.get("fields"));
        if (fa != null) {
            for (JsonElement e : fa) {
                JsonObject f = Json.obj(e);
                if (f == null || fields.size() >= MAX_FIELDS) continue;
                fields.add(new Field(Text.sanitize(Json.str(f, "name"), 120), cut(Json.str(f, "value"))));
            }
        }
        List<String> lines = new ArrayList<>();
        JsonArray la = Json.arr(o.get("lines"));
        if (la != null) {
            for (JsonElement e : la) {
                if (lines.size() >= MAX_LINES) break;
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) lines.add(Text.sanitize(e.getAsString(), 400));
            }
        }
        String color = Json.str(o, "color");
        return new Output(color.matches("[a-z]{1,12}|#[0-9a-fA-F]{6}") ? color : "", Text.sanitize(Json.str(o, "title"), 300),
            cut(Json.str(o, "description")), List.copyOf(fields), List.copyOf(lines));
    }

    /** Values may have line breaks, so they stay raw (cut) here and are cleaned per line when drawn. */
    private static String cut(String s) {
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    // ---------- the command box ----------

    /** Empty when {@code raw} may be sent (after trimming), else why not. Mirrors the server checks (500 characters, no control characters). */
    public static String check(String raw) {
        String c = raw == null ? "" : raw.trim();
        if (c.isEmpty()) return "Type a command first.";
        if (c.length() > MAX_COMMAND) return "At most " + MAX_COMMAND + " characters.";
        if (c.chars().anyMatch(Character::isISOControl)) return "The command has control characters.";
        return "";
    }

    /**
     * True when the first word of the command is one the website asks about first. A leading "/" or "!" does not hide the word, and
     * a command that chains or substitutes another ("; & | $" or a backtick anywhere) always asks.
     */
    public static boolean needsConfirm(String raw) {
        String c = raw == null ? "" : raw.trim();
        int start = 0;
        while (start < c.length() && (c.charAt(start) == '/' || c.charAt(start) == '!' || Character.isWhitespace(c.charAt(start)))) start++;
        c = c.substring(start);
        if (c.isEmpty()) return false;
        if (c.indexOf(';') >= 0 || c.indexOf('&') >= 0 || c.indexOf('|') >= 0 || c.indexOf('$') >= 0 || c.indexOf('`') >= 0) return true;
        String word = c.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return CONFIRM_WORDS.contains(word);
    }

    /** The warning for the second click. */
    public static String confirmText(String raw, String botName) {
        String c = raw == null ? "" : raw.trim();
        return "Run '" + Text.sanitize(c.length() > 80 ? c.substring(0, 80) : c, 80) + "' on " + botName
            + "? This command can disconnect the bot, restart the proxy or change how it logs in.";
    }

    /** What the box may hold: control characters never get in, and not more than 500 characters. */
    public static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < MAX_COMMAND; i++) {
            char c = s.charAt(i);
            if (!Character.isISOControl(c)) sb.append(c);
        }
        return sb.toString();
    }

    // ---------- history ----------

    /** The commands sent, oldest first, and an Up/Down position (-1: not browsing). Like the website. */
    public static final class History {
        private final List<String> items = new ArrayList<>();
        private int pos = -1;

        public void push(String command) {
            if (items.isEmpty() || !items.get(items.size() - 1).equals(command)) items.add(command);
            if (items.size() > MAX_HISTORY) items.remove(0);
            pos = -1;
        }

        public int size() {
            return items.size();
        }

        /** The older command, or null when there is none. */
        public String up() {
            if (items.isEmpty()) return null;
            pos = pos < 0 ? items.size() - 1 : Math.max(0, pos - 1);
            return items.get(pos);
        }

        /** The newer command; empty after the newest (back to an empty box); null when not browsing. */
        public String down() {
            if (items.isEmpty() || pos < 0) return null;
            pos++;
            if (pos >= items.size()) {
                pos = -1;
                return "";
            }
            return items.get(pos);
        }

        public void reset() {
            pos = -1;
        }

        public void clear() {
            items.clear();
            pos = -1;
        }
    }

    // ---------- drawing ----------

    public static String statusLabel(String status) {
        return switch (status) {
            case "queued" -> "waiting";
            case "sent" -> "running";
            case "done" -> "done";
            case "error" -> "failed";
            case "expired" -> "not picked up";
            case "unknown" -> "no answer";
            default -> status;
        };
    }

    private static int statusColor(String status) {
        return switch (status) {
            case "done" -> GOOD;
            case "error", "expired", "unknown" -> BAD;
            default -> WARN;
        };
    }

    /** The colour of an output: the named ones, #RRGGBB, else the text colour. */
    public static int outputColor(String color) {
        switch (color) {
            case "error":
                return BAD;
            case "success":
                return GOOD;
            case "primary":
                return BLUE;
            case "queue":
                return WARN;
            default:
                return HEX.matcher(color).matches() ? Integer.parseInt(color.substring(1), 16) : TEXT;
        }
    }

    /** Bold and code marks are dropped: the window draws plain text. */
    public static String plain(String s) {
        return RICH.matcher(s == null ? "" : s).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(1) != null ? m.group(1) : m.group(2)));
    }

    private static void text(List<Line> out, String raw, int rgb, String indent) {
        for (String part : plain(raw).split("\\r?\\n", -1)) {
            String clean = Text.sanitize(part, 400);
            if (!clean.isEmpty()) out.add(new Line(indent + clean, rgb));
        }
    }

    /** The drawn lines of one entry. {@code hide}: streamer display, the command and the output are not shown. */
    public static List<Line> lines(Entry e, boolean hide, ZoneId zone) {
        List<Line> out = new ArrayList<>();
        String time = e.at() > 0 ? DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(e.at()).atZone(zone)) : "";
        String cmd = hide ? "(hidden)" : e.hidden() ? "(hidden command)" : "> " + e.command();
        out.add(new Line(cmd + "   " + statusLabel(e.status()) + (time.isEmpty() ? "" : "  " + time), statusColor(e.status())));
        if (hide) {
            if (!e.outputs().isEmpty() || !e.error().isEmpty()) out.add(new Line("  (output hidden in streamer display)", DIM));
            return out;
        }
        for (Output o : e.outputs()) {
            int rgb = outputColor(o.color());
            if (!o.title().isEmpty()) text(out, o.title(), rgb, "  ");
            if (!o.description().isEmpty()) text(out, o.description(), TEXT, "  ");
            for (Field f : o.fields()) {
                if (!f.name().isEmpty()) text(out, f.name(), DIM, "  ");
                text(out, f.value(), TEXT, "    ");
            }
            for (String l : o.lines()) if (!l.isEmpty()) out.add(new Line("  " + l, TEXT));
        }
        if (e.outputs().isEmpty() && !e.error().isEmpty()) out.add(new Line("  " + e.error(), BAD));
        return out;
    }

    /** All entries, oldest first, with a blank line between entries. */
    public List<Line> lines(boolean hide, ZoneId zone) {
        List<Line> out = new ArrayList<>();
        for (Map.Entry<Long, Entry> e : entries.entrySet()) {
            if (!out.isEmpty()) out.add(new Line("", TEXT));
            out.addAll(lines(e.getValue(), hide, zone));
        }
        return out;
    }
}
