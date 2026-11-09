package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwerte.stash.KitNames;
import com.porter.qwerte.stash.KitSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * The Kits tab's data and words, as the website's Kits tab says them (app.js "kits"; the website is the reference for the wording). Pure.
 * The server computes the rows and the statuses; this reads them and words them. Everything the server supplies is checked on the way in:
 * container ids must be real ids (an invalid one becomes ""), keys must be valid kit keys (a row with another is dropped), texts are
 * cleaned, and the lists are capped. Streamer display never shows a container label or a coordinate.
 */
public final class KitsModel {
    private KitsModel() {
    }

    public static final int MAX_ROWS = 500;
    public static final int MAX_CONTAINERS = 4096;
    public static final int MAX_LOCATIONS = 64;
    /** Over this many choices a plain label is shown instead of a dropdown. */
    public static final int MAX_CHOICES = 200;

    public record Container(String id, String label, long free, boolean pickable, boolean full) {
    }

    public record Location(String container, long count) {
    }

    /** A kit score breakdown (the highest-total box of a row). */
    public record Score(long total, long name, long mix, long full, long op) {
    }

    /** The scoring settings: threshold, points, how many OP items the op points need; {@code max} is the sum of the four point values. */
    public record Scoring(int threshold, int name, int mix, int full, int op, int opMin, int max) {
        public static final Scoring DEFAULT = new Scoring(4, 2, 3, 1, 4, 3, 10);

        /** The values in the order of the editor: threshold, name, mix, full, op, opMin. */
        public int[] values() {
            return new int[] {threshold, name, mix, full, op, opMin};
        }
    }

    /**
     * One kit row.
     * @param key      the normalised name, "" for the unnamed row (no rule, no assign control)
     * @param rule     the container of the kit's own rule, or "" when it has none
     * @param target   the container it goes to, or "" when none
     * @param score    null when the server sent none
     * @param minTotal the lowest total in the row
     */
    public record Row(String key, String name, long count, long carried, List<Location> locations, String rule, String target, String targetSource, String status,
                      Score score, long minTotal) {
        public boolean unnamed() {
            return key.isEmpty();
        }

        /** The name to show: "(unnamed)", the original display name, else the key; with {@code hide} (streamer display) "Kit N" ({@code index} from 0). */
        public String title(boolean hide, int index) {
            if (unnamed()) return "(unnamed)";
            if (hide) return "Kit " + (index + 1);
            if (!name.isEmpty()) return name;
            String shown = Text.sanitize(key, 64);
            return shown.isEmpty() ? "(no visible name)" : shown;
        }
    }

    /** "10/10: name 2, mix 3, full 1, OP 4" with only the parts above 0 (or "no points"), plus " (lowest 8)" when the lowest total differs; "" without a score. */
    public static String scoreText(Row r, int max) {
        Score s = r.score();
        if (s == null) return "";
        List<String> parts = new ArrayList<>(4);
        if (s.name() > 0) parts.add("name " + s.name());
        if (s.mix() > 0) parts.add("mix " + s.mix());
        if (s.full() > 0) parts.add("full " + s.full());
        if (s.op() > 0) parts.add("OP " + s.op());
        String t = s.total() + "/" + max + ": " + (parts.isEmpty() ? "no points" : String.join(", ", parts));
        return r.minTotal() != s.total() ? t + " (lowest " + r.minTotal() + ")" : t;
    }

    public record Bot(boolean supports, boolean synced, boolean online) {
    }

    public record Data(long rev, String kitsChest, Scoring scoring, Bot bot, boolean hasReport, List<Container> containers, String kitsChestStatus, List<Row> rows, int totalRows) {
        public Container container(String id) {
            for (Container c : containers) if (c.id().equals(id)) return c;
            return null;
        }
    }

    /** A container id the server supplied: the id itself when it is a real one, else "". */
    private static String id(JsonObject o, String key) {
        String v = Json.str(o, key);
        return KitSettings.validId(v) ? v : "";
    }

    public static Data parse(JsonElement body) {
        if (body == null || !body.isJsonObject()) return null;
        JsonObject o = body.getAsJsonObject();
        JsonObject bot = Json.obj(o.get("bot"));
        Bot b = new Bot(bot != null && Json.bool(bot, "supports"), bot != null && Json.bool(bot, "synced"), bot != null && Json.bool(bot, "online"));
        List<Container> containers = new ArrayList<>();
        JsonArray cs = Json.arr(o.get("containers"));
        if (cs != null) {
            for (JsonElement e : cs) {
                if (containers.size() >= MAX_CONTAINERS) break;
                JsonObject c = Json.obj(e);
                String id = c == null ? "" : id(c, "id");
                if (id.isEmpty()) continue;
                containers.add(new Container(id, Text.sanitize(Json.str(c, "label"), 32), Math.max(0, Math.min(54, Json.lng(c, "free", 0))), Json.bool(c, "pickable"), Json.bool(c, "full")));
            }
        }
        List<Row> rows = new ArrayList<>();
        int total = 0;
        JsonArray rs = Json.arr(o.get("rows"));
        if (rs != null) {
            for (JsonElement e : rs) {
                JsonObject r = Json.obj(e);
                if (r == null) continue;
                // A null key is the unnamed row; any other key has to be a real kit key
                JsonElement k = r.get("key");
                String key = "";
                if (k != null && !k.isJsonNull()) {
                    if (!k.isJsonPrimitive() || !k.getAsJsonPrimitive().isString() || !KitNames.validKey(k.getAsString())) continue;
                    // Kept exactly as validated: it is what writes send back and what the shulker's rule is stored under. Only shown cleaned.
                    key = k.getAsString();
                }
                total++;
                if (rows.size() >= MAX_ROWS) continue;
                List<Location> locs = new ArrayList<>();
                JsonArray ls = Json.arr(r.get("locations"));
                if (ls != null) {
                    for (JsonElement le : ls) {
                        if (locs.size() >= MAX_LOCATIONS) break;
                        JsonObject l = Json.obj(le);
                        if (l != null) locs.add(new Location(id(l, "container"), Math.max(0, Json.lng(l, "count", 0))));
                    }
                }
                JsonObject sc = Json.obj(r.get("score"));
                Score score = sc == null ? null : new Score(Json.lng(sc, "total", 0), Json.lng(sc, "name", 0), Json.lng(sc, "mix", 0), Json.lng(sc, "full", 0), Json.lng(sc, "op", 0));
                rows.add(new Row(key, key.isEmpty() ? "" : Text.sanitize(Json.str(r, "name"), 64), Json.lng(r, "count", 0), Json.lng(r, "carried", 0), locs,
                    id(r, "rule"), id(r, "target"), Json.str(r, "targetSource"), Json.str(r, "status"), score,
                    Json.lng(r, "minTotal", score == null ? 0 : score.total())));
            }
        }
        boolean report = o.has("report") && o.get("report").isJsonObject();
        return new Data(Json.lng(o, "rev", 0), id(o, "kitsChest"), scoring(Json.obj(o.get("scoring"))), b, report, containers, Json.str(o, "kitsChestStatus"), rows, total);
    }

    private static Scoring scoring(JsonObject o) {
        if (o == null) return Scoring.DEFAULT;
        int t = (int) Json.lng(o, "threshold", 4), n = (int) Json.lng(o, "name", 2), m = (int) Json.lng(o, "mix", 3), f = (int) Json.lng(o, "full", 1), p = (int) Json.lng(o, "op", 4);
        int opMin = (int) Json.lng(o, "opMin", 3);
        return new Scoring(t, n, m, f, p, opMin, (int) Json.lng(o, "max", n + m + f + p));
    }

    /**
     * The values of the scoring editor's boxes as numbers (threshold, name, mix, full, op, opMin), or null when one is not a whole number
     * of up to four digits in range (threshold 1 to 40, points 0 to 10, OP items needed 1 to 1728).
     */
    public static int[] parseScoring(String threshold, String name, String mix, String full, String op, String opMin) {
        String[] raw = {threshold, name, mix, full, op, opMin};
        int[] v = new int[6];
        for (int i = 0; i < 6; i++) {
            String t = raw[i] == null ? "" : raw[i].trim();
            if (!t.matches("[0-9]{1,4}")) return null;
            v[i] = Integer.parseInt(t);
        }
        if (v[0] < 1 || v[0] > 40 || v[5] < 1 || v[5] > 1728) return null;
        for (int i = 1; i < 5; i++) if (v[i] > 10) return null;
        return v;
    }

    /** The editor's "Highest possible score" line; a hint about the ranges when a box is not valid. */
    public static String maxText(int[] valid, int pointsSum) {
        return "Highest possible score: " + pointsSum + (valid != null ? "" : " (check the numbers: threshold 1 to 40, points 0 to 10, OP items needed 1 to 1728)");
    }

    public static final String SCORING_HINT = "The bot scores every shulker box: points for a name with \"kit\" in it, a mix of different useful items, all 27 slots filled, and OP items "
        + "(totems, crystals, elytra and so on). A box with at least the threshold in points is a kit. A part-filled box of one item is never a kit.";
    public static final String SCORING_NOTE = "The bots re-score every box on their next pass after you save.";
    public static final String RESET_WARNING = "Put the kit scoring back to the defaults (threshold 4; name 2, mix 3, full 1, OP 4 for 3 or more OP items)? Click Reset to defaults again to confirm.";
    public static final String LIST_HINT = "Every kit the bot found, with its score. Unnamed kits always go to the kits chest. Name a shulker (like \"PvP Kit\") and you can give that name its own chest. "
        + "The chests you pick hold nothing but kits.";

    /** The status pill: text and kind (ok, warn, off or "" for neutral), as the website's KIT_STATUS. */
    public record Status(String text, String kind) {
    }

    public static Status status(String status) {
        return switch (status) {
            case "pending" -> new Status("Waiting for the sorter", "");
            case "home" -> new Status("In place", "ok");
            case "full" -> new Status("Target full", "warn");
            case "missing" -> new Status("Target missing", "off");
            case "none" -> new Status("Normal sorting", "");
            case "absent" -> new Status("None in the stash", "");
            default -> new Status(Text.sanitize(status, 32), "");
        };
    }

    /** The status of a row: without a report from the bot, "Waiting for the bot's report" (the website does the same). */
    public static Status status(Data d, Row r) {
        return d.hasReport() ? status(r.status()) : new Status("Waiting for the bot's report", "");
    }

    /** A note of the banner: kind (warn, off or "") and text. */
    public record Note(String kind, String text) {
    }

    /** The reasons things may not work yet, from the server's view of the bot (website kitsBanner). */
    public static List<Note> banner(Data d, boolean canWrite) {
        List<Note> notes = new ArrayList<>();
        if (!d.bot().supports()) {
            notes.add(new Note("warn", "This stash's bot doesn't know about kits yet (update the plugin or addon). Your settings are saved and apply once it does."));
        } else if (!d.bot().online()) {
            notes.add(new Note("warn", "The bot is offline. It picks the settings up when it is back."));
        } else if (!d.bot().synced()) {
            notes.add(new Note("", "The bot hasn't picked up the latest settings yet. This takes a few seconds."));
        }
        if (!d.hasReport()) notes.add(new Note("warn", "The bot hasn't reported its containers yet, so no chest can be picked. This needs a bot that supports kits and a stash it has read."));
        if (d.kitsChestStatus().equals("missing") && d.hasReport()) notes.add(new Note("off", "The kits chest isn't among the bot's containers any more, or can't take kits. Pick another one."));
        if (d.kitsChestStatus().equals("full")) notes.add(new Note("warn", "The kits chest is full. Kits that go there wait until it has room."));
        if (!canWrite) notes.add(new Note("", "You can look, but changing kit settings needs the \"settings\" permission."));
        return notes;
    }

    /** "x, y, z" for an id like "120,64,-33" (cleaned: only what a real id can hold gets through). */
    public static String coords(String id) {
        return Text.sanitize(id, 40).replace(",", ", ");
    }

    private static int indexOf(Data d, String id) {
        for (int i = 0; i < d.containers().size(); i++) if (d.containers().get(i).id().equals(id)) return i;
        return -1;
    }

    /** "Chest N" for a container (N from 1), "a chest" for one that is not listed: what streamer display shows, never a label or a position. */
    private static String chestName(Data d, String id) {
        int i = indexOf(d, id);
        return i < 0 ? "a chest" : "Chest " + (i + 1);
    }

    /** The place of a container: "Label (x, y, z)" or the coordinates; with {@code hide} (streamer display) only "Chest N". */
    public static String place(Data d, String id, boolean hide) {
        if (id.isEmpty()) return "?";
        if (hide) return chestName(d, id);
        Container c = d.container(id);
        return c != null && !c.label().isEmpty() ? c.label() + " (" + coords(id) + ")" : coords(id);
    }

    /** The text of a picker option: place and room; "Chest N · free" when coordinates are hidden. */
    public static String optionText(Container c, int index, boolean hide) {
        String free = c.free() + " free";
        if (hide) return "Chest " + (index + 1) + " · " + free;
        return (c.label().isEmpty() ? coords(c.id()) : c.label() + " (" + coords(c.id()) + ")") + " · " + free;
    }

    /**
     * The picker's options, in order: an optional first entry (value ""), then the pickable containers (and the current value when it is
     * not pickable, or not listed). Parallel arrays: {@code texts[i]} picks {@code values[i]}. Texts are unique.
     */
    public record Options(String[] texts, String[] values, int selected) {
    }

    /**
     * The options a rule or the kits chest can pick from, built once for an answer and shared by every row ({@link #select} only
     * picks the current entry). Rebuilt when the answer or the streamer display changes.
     */
    public static final class Picker {
        private final Data d;
        private final String first;
        private final boolean hide;
        private final String[] texts;
        private final String[] values;

        public Picker(Data d, String first, boolean hide) {
            this.d = d;
            this.first = first;
            this.hide = hide;
            List<String> t = new ArrayList<>();
            List<String> v = new ArrayList<>();
            if (first != null) {
                t.add(first);
                v.add("");
            }
            for (int i = 0; i < d.containers().size(); i++) {
                Container c = d.containers().get(i);
                if (!c.pickable()) continue;
                t.add(optionText(c, i, hide));
                v.add(c.id());
            }
            this.texts = t.toArray(new String[0]);
            this.values = v.toArray(new String[0]);
        }

        /** The number of entries a dropdown would list. */
        public int size() {
            return texts.length;
        }

        /** The options with {@code current} selected; the arrays are shared unless current is a container that cannot take kits or is not listed. */
        public Options select(String current) {
            if (current.isEmpty()) return new Options(texts, values, first != null ? 0 : -1);
            for (int i = 0; i < values.length; i++) if (values[i].equals(current)) return new Options(texts, values, i);
            return options(d, current, first, hide);
        }
    }

    public static Options options(Data d, String current, String first, boolean hide) {
        List<String> texts = new ArrayList<>();
        List<String> values = new ArrayList<>();
        if (first != null) {
            texts.add(first);
            values.add("");
        }
        boolean seen = false;
        for (int i = 0; i < d.containers().size(); i++) {
            Container c = d.containers().get(i);
            if (!c.pickable() && !c.id().equals(current)) continue;
            texts.add(optionText(c, i, hide) + (!c.pickable() ? " · can't take kits" : ""));
            values.add(c.id());
            if (c.id().equals(current)) seen = true;
        }
        if (!current.isEmpty() && !seen) {
            texts.add((hide ? "Chest" : coords(current)) + " · not reported");
            values.add(current);
        }
        int sel = current.isEmpty() ? 0 : Math.max(0, values.indexOf(current));
        if (current.isEmpty() && first == null) sel = -1;
        return new Options(texts.toArray(new String[0]), values.toArray(new String[0]), sel);
    }

    /** True when the container has a sign and it does not say kit (picking it as a kit chest needs a second look). */
    public static boolean signedNonKit(Container c) {
        if (c == null || c.label().isEmpty()) return false;
        return !KitNames.isKit(KitNames.normalize(c.label()));
    }

    /** The warning shown for picking a signed non-kit container (the website's confirm text; streamer display leaves the label out). */
    public static String signedWarning(Container c, boolean hide) {
        return (hide ? "This chest is signed for other items. " : "This chest is signed \"" + c.label() + "\". ")
            + "As the kits chest it only takes kit shulkers; nothing else is sorted into it. Pick it again to use it anyway.";
    }

    /** The warning shown for sending a kit back to the kits chest (removes its rule). */
    public static String assignWarning(Row r, boolean hide) {
        return (hide ? "Send this kit to the kits chest again?" : "Send \"" + r.title(false, 0) + "\" to the kits chest again?")
            + " Its own chest rule is removed. Choose it again to confirm.";
    }

    /** The line under the picker (website kits-chest-info). */
    public static String chestInfo(Data d, boolean hide) {
        if (d.kitsChest().isEmpty()) return "Kits are sorted like any other shulker until you pick a chest.";
        Container c = d.container(d.kitsChest());
        String s = "Kits without their own chest go to " + place(d, d.kitsChest(), hide) + (c != null ? " · " + c.free() + " free." : " (not reported).");
        if (signedNonKit(c)) s += " Its sign doesn't say kit: while it is the kits chest, only kit shulkers go in it.";
        return s;
    }

    /** "Where it is now" of a row. */
    public static String where(Data d, Row r, boolean hide) {
        StringBuilder sb = new StringBuilder();
        for (Location l : r.locations()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(place(d, l.container(), hide)).append(" ×").append(l.count());
        }
        if (r.carried() > 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("carried ×").append(r.carried());
        }
        return sb.length() == 0 ? "–" : sb.toString();
    }

    /** "Goes to" of a row. */
    public static String target(Data d, Row r, boolean hide) {
        if (r.target().isEmpty()) return "–";
        return place(d, r.target(), hide) + (r.targetSource().equals("rule") ? " (its chest)" : " (kits chest)");
    }

    /** What the Chest column says for the unnamed row: it has no rule, it always goes to the kits chest. */
    public static final String UNNAMED_CHEST = "(kits chest)";

    /** The empty-list text under the table. */
    public static String empty(Data d) {
        return d.hasReport() ? "No kits found. A shulker box that scores enough points (see Kit scoring) counts as a kit." : "Waiting for the bot's report.";
    }

    /** The text shown instead of the page when the read failed. */
    public static String error(int status, String permission) {
        if (status == 503) return "The server can't read its kit settings (kits.json is damaged), so kit sorting is off. Fix or remove the file and restart the server.";
        if (status == 404) return "That stash isn't available. (A server without kits answers the same: update the Stash Manager server.)";
        if (status == 403 && "streamer".equals(permission)) return "Kits are hidden in streamer mode.";
        if (status == 403) return "Your account can't see kits.";
        return "Can't reach the server. Trying again.";
    }

    public static String countNote(Data d) {
        return d.totalRows() > MAX_ROWS ? "Showing the first " + MAX_ROWS + " of " + d.totalRows() + " kits." : "";
    }
}
