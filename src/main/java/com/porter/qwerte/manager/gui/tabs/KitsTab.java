package com.porter.qwerte.manager.gui.tabs;

import com.google.gson.JsonObject;
import com.porter.qwerte.manager.gui.style.PageRoom;
import com.porter.qwerte.manager.gui.style.SmButton;
import com.porter.qwerte.manager.gui.style.SmCard;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import com.porter.qwerte.manager.gui.style.SmPill;
import com.porter.qwerte.manager.gui.style.SmTable;
import com.porter.qwerte.manager.gui.style.Ui;
import com.porter.qwerte.manager.gui.chrome.WFlow;
import com.porter.qwerte.manager.model.KitsModel;
import com.porter.qwerte.manager.model.KitsV2;
import com.porter.qwerte.manager.model.KitsModel.Data;
import com.porter.qwerte.manager.model.KitsModel.Row;
import com.porter.qwerte.manager.model.StashRef;
import com.porter.qwerte.manager.model.Text;
import com.porter.qwerte.manager.net.ApiError;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Kits tab (server mode only): the website's Kits tab, worded as the website words it. A kits-chest picker with Clear, the kit
 * scoring editor, then a table of every kit (name with its score, count, where it is now, where it goes, a status pill and a dropdown
 * that assigns a chest to that kit). The server computes the rows and the statuses ({@code GET /api/v1/kits}); this draws them.
 *
 * <p>Polled every 5 s, and only while this tab is shown (the window calls {@link #tick} for the visible tab alone). The poll never opens
 * the password prompt. The widgets are rebuilt only when what they show changed (a signature kept from the last answer), never while a
 * two-click action is armed, a dropdown list is open or a scoring box has the keyboard. At most {@link KitsModel#MAX_ROWS} rows; the
 * chest choices are built once per answer and shared by the rows. Writes go through {@link ServerTabHost#write} (session cookie and
 * CSRF header, password prompt) and make an answer to an older poll worthless. Streamer display shows "Chest N" instead of a container's
 * label or position; a forced streamer does not get the tab at all.
 */
public final class KitsTab {
    private static final long POLL_MS = 5_000;
    private static final long RETRY_MS = 5_000;
    private static final long CHECK_MS = 500;
    private static final long ARM_MS = 5_000;
    private static final String[] FIELDS = {"Threshold", "Name", "Mix", "Full", "OP", "OP items needed"};

    private final ServerTabHost host;
    private GuiTheme theme;

    private String stashKey = "";
    private Data data;
    private KitsV2.Data d2;
    private String error = "";
    private String dataSig = "";
    private boolean busy;
    private boolean writing;
    private int token;
    private long nextGet;
    private long nextCheck;
    private String armedKey = "";
    private long armedUntil;

    // what the current widgets were built from
    private String builtSig = "";
    private boolean builtHide;
    private boolean builtWriting;
    private boolean builtWrite;
    private String builtArmed = "";
    private String builtStash = "";
    private WContainer root;

    // the choices of the pickers, built once per answer and streamer state and shared by the rows
    private String pickerSig = "";
    private KitsModel.Picker chestPick, ruleStatic;
    private KitsModel.Picker chestUnset;

    // the scoring editor: what is typed, and the server's values it was started from (unsaved edits are kept across polls)
    private final String[] draft = new String[6];
    private String[] draftBase;
    private WLabel maxLabel;
    private WHorizontalList saveHolder, resetHolder;

    public KitsTab(ServerTabHost host) {
        this.host = host;
    }

    /** The window closed or the session ended. */
    public void reset() {
        token++;
        busy = false;
        writing = false;
        data = null;
        d2 = null;
        error = "";
        dataSig = "";
        stashKey = "";
        nextGet = 0;
        nextCheck = 0;
        armedKey = "";
        armedUntil = 0;
        advancedOpen = false;
        builtSig = "";
        builtArmed = "";
        builtStash = "";
        pickerSig = "";
        chestPick = ruleStatic = chestUnset = null;
        draftBase = null;
        root = null;
        maxLabel = null;
        saveHolder = resetHolder = null;
    }

    private boolean canWrite() {
        return host.auth().can("settings") && !host.auth().forcedStreamer();
    }

    // ---------- per frame ----------

    public void tick(long now) {
        if (host.closed() || !host.auth().loggedIn()) return;
        StashRef s = host.selectedStash();
        String key = s == null ? "" : s.key();
        if (!key.equals(stashKey)) {
            // another stash: start over
            token++;
            busy = false;
            writing = false;
            data = null;
            d2 = null;
            error = "";
            dataSig = "";
            stashKey = key;
            nextGet = 0;
            armedKey = "";
            draftBase = null;
            host.later(host::rebuild);
            return;
        }
        if (s == null) return;
        if (!armedKey.isEmpty() && now > armedUntil) {
            armedKey = "";
            host.later(host::rebuild);
            return;
        }
        if (!busy && !writing && now >= nextGet) fetch(now, s);
        if (now < nextCheck) return;
        nextCheck = now + CHECK_MS;
        if (changed() && armedKey.isEmpty() && !busyWidgets()) host.later(host::rebuild);
    }

    /** A rebuild would pull the widget out from under the user: a dropdown list is open, or a scoring box has the keyboard. */
    private boolean busyWidgets() {
        if (theme instanceof SmGuiTheme st && st.overlay != null && st.overlay.isOpen()) return true;
        return root != null && TabFocus.anyFocused(root);
    }

    /** Rebuilds after a write, unless a dropdown list is open or a scoring box has the keyboard: the tick rebuilds once they let go (the signature differs). */
    private void rebuildUnlessBusy() {
        if (!busyWidgets()) host.later(host::rebuild);
    }

    private boolean changed() {
        return !builtSig.equals(dataSig) || builtHide != host.policy().hideCoords() || builtWriting != writing || builtWrite != canWrite()
            || !builtArmed.equals(armedKey) || !builtStash.equals(stashKey);
    }

    private void fetch(long now, StashRef s) {
        busy = true;
        nextGet = now + POLL_MS;
        int t = token;
        Map<String, String> query = new LinkedHashMap<>();
        query.put("server", s.server());
        query.put("stash", s.stash());
        // The poll goes straight to the client: it must never open the password prompt (a CONFIRM answer is just an error here)
        host.client().get("/api/v1/kits", query, r -> {
            if (t != token || host.closed()) return;
            busy = false;
            if (r.ok()) {
                Data d = KitsModel.parse(r.body());
                if (d == null) {
                    error = KitsModel.error(0, null);
                    data = null;
                    d2 = null;
                } else {
                    error = "";
                    data = d;
                    d2 = KitsV2.parse(r.body());
                }
                dataSig = signature(data, d2, error);
            } else if (r.kind() == ApiError.Kind.LOGIN || r.kind() == ApiError.Kind.MUST_CHANGE) {
                nextGet = System.currentTimeMillis() + RETRY_MS;
                host.failed(r);
            } else if (r.kind() == ApiError.Kind.CANCELLED || r.kind() == ApiError.Kind.NOT_MODIFIED) {
                nextGet = System.currentTimeMillis() + RETRY_MS;
            } else {
                nextGet = System.currentTimeMillis() + RETRY_MS;
                data = null;
                d2 = null;
                error = r.kind() == ApiError.Kind.CONFIRM ? KitsModel.error(0, null) : KitsModel.error(r.status(), ApiError.permission(r.body()));
                dataSig = signature(null, null, error);
            }
        });
    }

    /** Everything the page shows from an answer, as one string, made once per answer. */
    private static String signature(Data d, KitsV2.Data x, String error) {
        StringBuilder sb = new StringBuilder(error);
        if (d == null) return sb.toString();
        // Everything the v2 page shows: the records compare by content
        if (x != null) sb.append('~').append(x.hashCode());
        KitsModel.Scoring sc = d.scoring();
        sb.append('#').append(d.rev()).append(d.kitsChest()).append(Arrays.toString(sc.values())).append(d.kitsChestStatus()).append(d.hasReport())
            .append(d.bot().supports()).append(d.bot().synced()).append(d.bot().online()).append(d.totalRows());
        for (KitsModel.Container c : d.containers()) sb.append('|').append(c.id()).append(c.label()).append(c.free()).append(c.pickable());
        for (Row r : d.rows()) {
            sb.append('|').append(r.key()).append(r.name()).append(r.count()).append(r.carried()).append(r.rule()).append(r.target()).append(r.targetSource()).append(r.status()).append(r.minTotal());
            if (r.score() != null) sb.append(r.score().total()).append(r.score().name()).append(r.score().mix()).append(r.score().full()).append(r.score().op());
            for (KitsModel.Location l : r.locations()) sb.append(l.container()).append(l.count());
        }
        return sb.toString();
    }

    // ---------- widgets ----------

    public void build(WContainer parent, GuiTheme theme) {
        if (data != null && d2 != null && d2.v2() && host.selectedStash() != null) {
            buildV2(parent, theme);
            return;
        }
        buildV1(parent, theme);
    }

    private void buildV1(WContainer parent, GuiTheme theme) {
        this.theme = theme;
        root = parent;
        maxLabel = null;
        saveHolder = resetHolder = null;
        builtSig = dataSig;
        builtHide = host.policy().hideCoords();
        builtWriting = writing;
        builtWrite = canWrite();
        builtArmed = armedKey;
        builtStash = stashKey;
        Ui.pageTitle(parent, theme, "kits");
        double inner = PageRoom.width(parent, 16);
        if (host.selectedStash() == null) {
            parent.add(Ui.wrap(theme, "No stash selected.", PageRoom.width(parent, 0))).expandX();
            return;
        }
        if (data == null) {
            boolean failed = !error.isEmpty();
            parent.add(Ui.wrap(theme, failed ? error : "Loading...", PageRoom.width(parent, 0))).expandX().widget().color(failed ? Ui.err(theme) : Ui.dim(theme));
            return;
        }
        Data d = data;
        boolean hide = builtHide;
        boolean can = builtWrite && !writing && d.hasReport();
        makePickers(d, hide);

        SmCard chestCard = Ui.card(parent, "Kits chest");
        for (KitsModel.Note n : KitsModel.banner(d, builtWrite)) {
            WLabel l = chestCard.add(Ui.wrap(theme, n.text(), inner)).expandX().widget();
            if (n.kind().equals("warn")) l.color(Ui.warn(theme));
            else if (n.kind().equals("off")) l.color(Ui.err(theme));
            else l.color(Ui.dim(theme));
        }
        WHorizontalList row = chestCard.add(theme.horizontalList()).expandX().widget();
        row.spacing = 6;
        String chest = d.kitsChest();
        KitsModel.Picker chestPicker = chest.isEmpty() ? chestUnset : chestPick;
        KitsModel.Options opts = chestPicker.select(chest);
        String shown = opts.texts().length == 0 ? "" : opts.texts()[Math.max(0, opts.selected())];
        if (can && chestPicker.size() <= KitsModel.MAX_CHOICES) {
            WDropdown<String> dd = row.add(theme.dropdown(opts.texts(), shown)).widget();
            dd.action = () -> pickChest(d, opts, dd.get(), hide);
        } else {
            row.add(theme.label(shown));
        }
        boolean armedClear = armedKey.equals("clear");
        if (can && !chest.isEmpty()) {
            SmButton clear = (SmButton) row.add(theme.button(armedClear ? "Confirm clear?" : "Clear")).widget();
            clear.pill = true;
            clear.tooltip = "Stop using a kits chest: kits without their own chest go back to normal sorting. Click twice.";
            clear.action = this::clickedClear;
        } else {
            row.add(Ui.muted(theme, "Clear"));
        }
        if (can && chestPicker.size() > KitsModel.MAX_CHOICES) {
            chestCard.add(Ui.muted(theme, "Too many chests to list here: pick one on the website.", inner)).expandX();
        }
        chestCard.add(Ui.muted(theme, KitsModel.chestInfo(d, hide), inner)).expandX();
        if (armedKey.startsWith("chest:")) {
            KitsModel.Container c = d.container(armedKey.substring(6));
            if (c != null) chestCard.add(Ui.wrap(theme, KitsModel.signedWarning(c, hide), inner)).expandX().widget().color(Ui.warn(theme));
        }
        if (armedClear) chestCard.add(Ui.wrap(theme, "Stop using a kits chest? Kits without their own chest go back to normal sorting. Click Confirm clear? to do it.", inner)).expandX().widget().color(Ui.warn(theme));

        scoringCard(parent, d, inner);

        SmCard list = Ui.card(parent, "Kits");
        list.add(Ui.muted(theme, KitsModel.LIST_HINT, inner)).expandX();
        if (d.rows().isEmpty()) {
            list.add(Ui.muted(theme, KitsModel.empty(d), inner)).expandX();
            return;
        }
        SmTable table = list.add(new SmTable("Kit", "Count", "Score", "Where it is now", "Goes to", "Status", "Chest")).expandX().widget();
        table.weights(3, 1, 3, 4, 4, 2, 4);
        table.maxWidth = inner;
        double sum = 21;
        double gaps = theme.scale(10) * 6;
        int index = 0;
        boolean ruleDropdowns = can && ruleStatic.size() <= KitsModel.MAX_CHOICES;
        for (Row r : d.rows()) {
            WLabel name = Ui.wrap(theme, r.title(hide, index++), (inner - gaps) * 3 / sum);
            WLabel count = theme.label(String.valueOf(r.count()));
            WLabel score = Ui.muted(theme, KitsModel.scoreText(r, d.scoring().max()), (inner - gaps) * 3 / sum);
            WLabel where = Ui.wrap(theme, KitsModel.where(d, r, hide), (inner - gaps) * 4 / sum);
            WLabel target = Ui.wrap(theme, KitsModel.target(d, r, hide), (inner - gaps) * 4 / sum);
            KitsModel.Status st = KitsModel.status(d, r);
            SmPill pill = new SmPill(st.text(), switch (st.kind()) {
                case "ok" -> SmPill.Kind.OK;
                case "warn" -> SmPill.Kind.WARN;
                case "off" -> SmPill.Kind.ERROR;
                default -> SmPill.Kind.NEUTRAL;
            });
            WWidget assign;
            if (r.unnamed()) {
                // an unnamed kit has no name to make a rule for: it always goes to the kits chest
                assign = Ui.muted(theme, KitsModel.UNNAMED_CHEST, (inner - gaps) * 4 / sum);
            } else {
                KitsModel.Options ro = ruleStatic.select(r.rule());
                String current = ro.texts()[Math.max(0, ro.selected())];
                if (ruleDropdowns) {
                    WDropdown<String> dd = theme.dropdown(ro.texts(), current);
                    dd.action = () -> assign(r, ro, dd.get(), hide);
                    assign = dd;
                } else {
                    assign = Ui.muted(theme, current, (inner - gaps) * 4 / sum);
                }
            }
            table.addRow(name, count, score, where, target, pill, assign);
        }
        String note = KitsModel.countNote(d);
        if (!note.isEmpty()) list.add(Ui.muted(theme, note, inner)).expandX();
    }

    /** The choices are the same for every row of an answer: built once per answer and streamer state. */
    private void makePickers(Data d, boolean hide) {
        String sig = dataSig + "|" + hide;
        if (sig.equals(pickerSig) && chestPick != null) return;
        pickerSig = sig;
        chestPick = new KitsModel.Picker(d, null, hide);
        chestUnset = new KitsModel.Picker(d, "Pick a chest…", hide);
        ruleStatic = new KitsModel.Picker(d, "Kits chest (default)", hide);
    }

    private void scoringCard(WContainer parent, Data d, double inner) {
        String[] server = new String[6];
        int[] sv = d.scoring().values();
        for (int i = 0; i < 6; i++) server[i] = String.valueOf(sv[i]);
        // Unsaved edits (the boxes differ from the last numbers the server gave) are never overwritten by a poll
        if (draftBase == null || !Arrays.equals(draftBase, server)) {
            if (draftBase == null || Arrays.equals(draft, draftBase)) System.arraycopy(server, 0, draft, 0, 6);
            draftBase = server;
        }
        SmCard card = Ui.card(parent, "Kit scoring");
        card.add(Ui.muted(theme, KitsModel.SCORING_HINT, inner)).expandX();
        boolean can = builtWrite && !writing;
        WHorizontalList row = card.add(theme.horizontalList()).expandX().widget();
        row.spacing = 6;
        for (int i = 0; i < 6; i++) {
            int at = i;
            row.add(Ui.muted(theme, FIELDS[i]));
            if (can) {
                WTextBox box = row.add(theme.textBox(draft[i], "")).minWidth(theme.scale(i == 5 ? 48 : 40)).widget();
                box.action = () -> {
                    draft[at] = box.get();
                    editorChanged();
                };
            } else {
                row.add(theme.label(draft[i]));
            }
        }
        maxLabel = card.add(Ui.muted(theme, maxText(), inner)).expandX().widget();
        WHorizontalList buttons = card.add(theme.horizontalList()).expandX().widget();
        buttons.spacing = 6;
        saveHolder = buttons.add(theme.horizontalList()).widget();
        resetHolder = buttons.add(theme.horizontalList()).widget();
        fillButtons();
        card.add(Ui.muted(theme, KitsModel.SCORING_NOTE, inner)).expandX();
    }

    private int[] typed() {
        return KitsModel.parseScoring(draft[0], draft[1], draft[2], draft[3], draft[4], draft[5]);
    }

    private String maxText() {
        int sum = 0;
        for (int i = 1; i <= 4; i++) {
            try {
                sum += Integer.parseInt(draft[i].trim());
            } catch (NumberFormatException e) {
                // not a number: counts as 0, like the website
            }
        }
        return KitsModel.maxText(typed(), sum);
    }

    private void editorChanged() {
        if (maxLabel != null) maxLabel.set(maxText());
        fillButtons();
    }

    /** Save (disabled when a box is not valid or nothing changed) and Reset to defaults (disabled at the defaults): a button, or its dim text. */
    private void fillButtons() {
        if (saveHolder == null || resetHolder == null || data == null) return;
        boolean can = builtWrite && !writing;
        int[] v = typed();
        boolean same = v != null && Arrays.equals(v, data.scoring().values());
        saveHolder.clear();
        if (can && v != null && !same) {
            SmButton save = (SmButton) saveHolder.add(theme.button("Save")).widget();
            save.pill = true;
            save.action = this::saveScoring;
        } else {
            saveHolder.add(Ui.muted(theme, "Save"));
        }
        resetHolder.clear();
        boolean atDefault = Arrays.equals(data.scoring().values(), KitsModel.Scoring.DEFAULT.values());
        if (can && !atDefault) {
            boolean armed = armedKey.equals("scoring-reset");
            SmButton reset = (SmButton) resetHolder.add(theme.button(armed ? "Confirm reset?" : "Reset to defaults")).widget();
            reset.pill = true;
            reset.tooltip = "Puts the default kit scoring back and saves it. Click twice.";
            reset.action = this::clickedReset;
        } else {
            resetHolder.add(Ui.muted(theme, "Reset to defaults"));
        }
    }

    // ---------- actions ----------

    private static String valueOf(KitsModel.Options o, String text) {
        for (int i = 0; i < o.texts().length; i++) if (o.texts()[i].equals(text)) return o.values()[i];
        return null;
    }

    private void arm(String key, String notice) {
        armedKey = key;
        armedUntil = System.currentTimeMillis() + ARM_MS;
        if (notice != null) host.notice(notice);
        host.later(host::rebuild);
    }

    private void pickChest(Data d, KitsModel.Options opts, String picked, boolean hide) {
        String v = valueOf(opts, picked);
        if (v == null || v.isEmpty() || v.equals(d.kitsChest())) return;
        KitsModel.Container c = d.container(v);
        if (KitsModel.signedNonKit(c) && !armedKey.equals("chest:" + v)) {
            arm("chest:" + v, KitsModel.signedWarning(c, hide));
            return;
        }
        armedKey = "";
        JsonObject body = new JsonObject();
        body.addProperty("container", v);
        write("/api/v1/kits/chest", body);
    }

    private void clickedClear() {
        if (!armedKey.equals("clear")) {
            arm("clear", "Stop using a kits chest? Kits without their own chest go back to normal sorting. Click Confirm clear? to do it.");
            return;
        }
        armedKey = "";
        JsonObject body = new JsonObject();
        body.add("container", com.google.gson.JsonNull.INSTANCE);
        write("/api/v1/kits/chest", body);
    }

    private void assign(Row r, KitsModel.Options opts, String picked, boolean hide) {
        String v = valueOf(opts, picked);
        if (v == null || v.equals(r.rule())) return;
        JsonObject body = new JsonObject();
        body.addProperty("key", r.key());
        if (v.isEmpty()) {
            if (!armedKey.equals("rule:" + r.key())) {
                arm("rule:" + r.key(), KitsModel.assignWarning(r, hide));
                return;
            }
            armedKey = "";
            write("/api/v1/kits/rule/delete", body);
        } else {
            armedKey = "";
            body.addProperty("container", v);
            write("/api/v1/kits/rule", body);
        }
    }


    // ---------- the v2 page (KIT-CONTRACT v2: types, reasons, chains) ----------

    private boolean advancedOpen;

    private static String usualName(String id) {
        try {
            return com.porter.qwerte.stash.StashIndex.nameOf(id);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static String reasonOf(KitsV2.Data x, KitsV2.Why w, String templateName, boolean hide) {
        return KitsV2.reason(x, w, templateName, hide, KitsTab::usualName);
    }

    private static WFlow flow(WContainer parent, double width) {
        WFlow f = parent.add(new WFlow()).expandX().widget();
        f.maxWidth = width;
        f.gapX = 6;
        f.gapY = 4;
        return f;
    }

    private void buildV2(WContainer parent, GuiTheme theme) {
        this.theme = theme;
        root = parent;
        maxLabel = null;
        saveHolder = resetHolder = null;
        builtSig = dataSig;
        builtHide = host.policy().hideCoords();
        builtWriting = writing;
        builtWrite = canWrite();
        builtArmed = armedKey;
        builtStash = stashKey;
        Ui.pageTitle(parent, theme, "kits");
        double inner = PageRoom.width(parent, 16);
        Data d = data;
        KitsV2.Data x = d2;
        boolean hide = builtHide;
        boolean can = builtWrite && !writing && d.hasReport();
        makePickers(d, hide);

        SmCard head = Ui.card(parent, "Kits");
        head.add(Ui.wrap(theme, KitsV2.HEADER, inner)).expandX();
        if (x.botSupports() && !x.botTypes()) head.add(Ui.wrap(theme, KitsV2.OLD_BOT, inner)).expandX().widget().color(Ui.warn(theme));
        for (KitsModel.Note n : KitsModel.banner(d, builtWrite)) {
            WLabel l = head.add(Ui.wrap(theme, n.text(), inner)).expandX().widget();
            if (n.kind().equals("warn")) l.color(Ui.warn(theme));
            else if (n.kind().equals("off")) l.color(Ui.err(theme));
            else l.color(Ui.dim(theme));
        }

        // The chests card: one line per type (and custom category), then the general kits chest
        SmCard chests = Ui.card(parent, "Chests");
        for (KitsV2.TypeInfo t : x.types()) {
            WFlow line = flow(chests, inner);
            line.add(Ui.bold(theme, t.name().isEmpty() ? t.id() : t.name()));
            KitsV2.TypeWords w = KitsV2.words(t.id());
            if (w != null) line.add(Ui.muted(theme, w.hint()));
            KitsModel.Options opts = chestUnset.select(t.chest());
            String shown = opts.texts().length == 0 ? "" : opts.texts()[Math.max(0, opts.selected())];
            if (can && chestUnset.size() <= KitsModel.MAX_CHOICES) {
                WDropdown<String> dd = line.add(theme.dropdown(opts.texts(), shown)).widget();
                dd.action = () -> pickTypeChest(t.id(), opts, dd.get());
            } else {
                line.add(theme.label(shown));
            }
            if (can && !t.chest().isEmpty()) {
                SmButton clear = (SmButton) line.add(theme.button("Clear")).widget();
                clear.pill = true;
                clear.tooltip = "Stop using a chest for this type: its kits go to the kits chest.";
                clear.action = () -> writeTypeChest(t.id(), null);
            }
            WLabel st = line.add(Ui.muted(theme, KitsV2.chestStatusText(t.chestStatus()) + " · " + t.count() + (t.count() == 1 ? " kit" : " kits"))).widget();
            if (t.chestStatus().equals("missing")) st.color(Ui.err(theme));
            else if (t.chestStatus().equals("full")) st.color(Ui.warn(theme));
        }
        WFlow general = flow(chests, inner);
        general.add(Ui.bold(theme, "General kits chest"));
        general.add(Ui.muted(theme, KitsV2.GENERAL_HINT));
        String chest = d.kitsChest();
        KitsModel.Picker chestPicker = chest.isEmpty() ? chestUnset : chestPick;
        KitsModel.Options gopts = chestPicker.select(chest);
        String gshown = gopts.texts().length == 0 ? "" : gopts.texts()[Math.max(0, gopts.selected())];
        if (can && chestPicker.size() <= KitsModel.MAX_CHOICES) {
            WDropdown<String> dd = general.add(theme.dropdown(gopts.texts(), gshown)).widget();
            dd.action = () -> pickChest(d, gopts, dd.get(), hide);
        } else {
            general.add(theme.label(gshown));
        }
        boolean armedClear = armedKey.equals("clear");
        if (can && !chest.isEmpty()) {
            SmButton clear = (SmButton) general.add(theme.button(armedClear ? "Confirm clear?" : "Clear")).widget();
            clear.pill = true;
            clear.tooltip = "Stop using a general kits chest. Click twice.";
            clear.action = this::clickedClear;
        }
        chests.add(Ui.muted(theme, KitsModel.chestInfo(d, hide), inner)).expandX();
        if (armedKey.startsWith("chest:")) {
            KitsModel.Container c = d.container(armedKey.substring(6));
            if (c != null) chests.add(Ui.wrap(theme, KitsModel.signedWarning(c, hide), inner)).expandX().widget().color(Ui.warn(theme));
        }
        if (armedClear) chests.add(Ui.wrap(theme, "Stop using a general kits chest? Kits whose type chest is unset, full or missing go back to normal sorting. Click Confirm clear? to do it.", inner)).expandX().widget().color(Ui.warn(theme));

        // The kits, grouped by type in type order, each group headed with the type's chest
        int index = 0;
        boolean any = false;
        List<String> order = new ArrayList<>();
        for (KitsV2.TypeInfo t : x.types()) order.add(t.id());
        order.add("");
        for (String typeId : order) {
            List<KitsV2.Row> group = new ArrayList<>();
            for (KitsV2.Row r : x.rows()) if (r.type().equals(typeId)) group.add(r);
            if (group.isEmpty()) continue;
            any = true;
            KitsV2.TypeInfo ti = x.type(typeId);
            String title = ti == null ? "Other rules and older bots" : (ti.name().isEmpty() ? ti.id() : ti.name());
            if (ti != null && !ti.chest().isEmpty()) title += " · " + KitsModel.place(d, ti.chest(), hide);
            SmCard card = Ui.card(parent, title);
            for (KitsV2.Row r : group) {
                kitRow(card, d, x, r, index++, inner, hide, can);
            }
        }
        if (!any) parent.add(Ui.muted(theme, KitsModel.empty(d), inner)).expandX();

        // Sorted normally (not kits)
        if (!x.others().isEmpty()) {
            SmCard card = Ui.card(parent, "Sorted normally (not kits)");
            card.add(Ui.muted(theme, KitsV2.NOT_KITS_HINT, inner)).expandX();
            int n = 0;
            for (KitsV2.Other o : x.others()) {
                WFlow line = flow(card, inner);
                line.add(Ui.bold(theme, o.title(hide, n++)));
                line.add(Ui.muted(theme, "×" + o.count() + " · " + KitsV2.where(d, o, hide)));
                card.add(Ui.wrap(theme, reasonOf(x, o.why(), "", hide), inner)).expandX().widget().color(Ui.dim(theme));
                if (!o.single()) {
                    KitsModel.Options topts = KitsV2.typeOptions(x, o.treatAs());
                    if (can && !o.single() && topts.texts().length <= KitsModel.MAX_CHOICES) {
                        WFlow pick = flow(card, inner);
                        pick.add(Ui.muted(theme, "Treat as"));
                        WDropdown<String> dd = pick.add(theme.dropdown(topts.texts(), topts.texts()[topts.selected()])).widget();
                        dd.action = () -> pickTreatAs(o.key(), topts, dd.get());
                    }
                }
            }
        }

        // Advanced: the v1 scoring, only while an older bot is reporting
        if (x.v1Reporting()) {
            SmButton adv = (SmButton) parent.add(theme.button(advancedOpen ? "Advanced ▾" : "Advanced ▸")).widget();
            adv.pill = true;
            adv.tooltip = "Scoring for older bots";
            adv.action = () -> {
                advancedOpen = !advancedOpen;
                host.later(host::rebuild);
            };
            if (advancedOpen) scoringCard(parent, d, inner);
        }
    }

    private void kitRow(WContainer card, Data d, KitsV2.Data x, KitsV2.Row r, int index, double inner, boolean hide, boolean can) {
        WFlow line = flow(card, inner);
        line.add(Ui.bold(theme, r.title(hide, index)));
        line.add(Ui.muted(theme, "×" + r.count() + (r.carried() > 0 ? " (" + r.carried() + " carried)" : "")));
        KitsModel.Status st = KitsV2.status(d, r);
        line.add(new SmPill(st.text(), switch (st.kind()) {
            case "ok" -> SmPill.Kind.OK;
            case "warn" -> SmPill.Kind.WARN;
            case "off" -> SmPill.Kind.ERROR;
            default -> SmPill.Kind.NEUTRAL;
        }));
        card.add(Ui.muted(theme, "Where: " + KitsV2.where(d, r, hide), inner)).expandX();
        card.add(Ui.muted(theme, "Goes to: " + KitsV2.goesTo(x, d, r, hide), inner)).expandX();
        if (r.why() != null) card.add(Ui.wrap(theme, reasonOf(x, r.why(), r.templateName(), hide), inner)).expandX().widget().color(Ui.dim(theme));
        if (!r.unnamed()) {
            WFlow ctl = flow(card, inner);
            // Own chest (the v1.1 rule)
            ctl.add(Ui.muted(theme, "Own chest"));
            KitsModel.Options ro = ruleStatic.select(r.rule());
            String current = ro.texts()[Math.max(0, ro.selected())];
            if (can && ruleStatic.size() <= KitsModel.MAX_CHOICES) {
                WDropdown<String> dd = ctl.add(theme.dropdown(ro.texts(), current)).widget();
                dd.action = () -> assignRule(r.key(), ro, dd.get(), hide);
            } else {
                ctl.add(theme.label(current));
            }
            // Type (Auto / each type / Not a kit): writes treatAs
            String treat = x.treatAsOf(r.key());
            KitsModel.Options topts = KitsV2.typeOptions(x, treat);
            ctl.add(Ui.muted(theme, "Type"));
            if (can && topts.texts().length <= KitsModel.MAX_CHOICES) {
                WDropdown<String> dd = ctl.add(theme.dropdown(topts.texts(), topts.texts()[topts.selected()])).widget();
                dd.action = () -> pickTreatAs(r.key(), topts, dd.get());
            } else {
                ctl.add(theme.label(topts.texts()[topts.selected()]));
            }
        }
    }

    private void pickTypeChest(String type, KitsModel.Options opts, String picked) {
        String v = valueOf(opts, picked);
        if (v == null || v.isEmpty()) return;
        writeTypeChest(type, v);
    }

    private void writeTypeChest(String type, String container) {
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        if (container == null) body.add("container", com.google.gson.JsonNull.INSTANCE);
        else body.addProperty("container", container);
        write("/api/v1/kits/typeChest", body);
    }

    private void pickTreatAs(String key, KitsModel.Options opts, String picked) {
        String v = valueOf(opts, picked);
        if (v == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("key", key);
        if (v.isEmpty()) body.add("type", com.google.gson.JsonNull.INSTANCE);
        else body.addProperty("type", v);
        write("/api/v1/kits/treatAs", body);
    }

    private void assignRule(String key, KitsModel.Options opts, String picked, boolean hide) {
        String v = valueOf(opts, picked);
        if (v == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("key", key);
        if (v.isEmpty()) {
            body.addProperty("key", key);
            write("/api/v1/kits/rule/delete", body);
        } else {
            body.addProperty("container", v);
            write("/api/v1/kits/rule", body);
        }
    }

    private void saveScoring() {
        int[] v = typed();
        if (v == null || data == null || Arrays.equals(v, data.scoring().values())) return;
        write("/api/v1/kits/scoring", scoringBody(v));
    }

    private void clickedReset() {
        if (!armedKey.equals("scoring-reset")) {
            arm("scoring-reset", KitsModel.RESET_WARNING);
            return;
        }
        armedKey = "";
        write("/api/v1/kits/scoring", scoringBody(KitsModel.Scoring.DEFAULT.values()));
    }

    private static JsonObject scoringBody(int[] v) {
        JsonObject body = new JsonObject();
        body.addProperty("threshold", v[0]);
        body.addProperty("name", v[1]);
        body.addProperty("mix", v[2]);
        body.addProperty("full", v[3]);
        body.addProperty("op", v[4]);
        body.addProperty("opMin", v[5]);
        return body;
    }

    /** A write; the answer is the same body as the read, so it is drawn straight away. A poll still on its way is made worthless. */
    private void write(String path, JsonObject body) {
        StashRef s = host.selectedStash();
        if (s == null || writing || !canWrite()) return;
        writing = true;
        // a poll that is still running can't overwrite what this write answers
        int t = ++token;
        busy = false;
        body.addProperty("server", s.server());
        body.addProperty("stash", s.stash());
        host.write(path, body, r -> {
            if (t != token || host.closed()) return;
            writing = false;
            if (r.ok()) {
                Data d = KitsModel.parse(r.body());
                if (d != null) {
                    data = d;
                    d2 = KitsV2.parse(r.body());
                    error = "";
                    dataSig = signature(d, d2, "");
                    // after a scoring write the boxes follow the saved numbers; other writes keep unsaved edits
                    if (path.endsWith("/scoring")) draftBase = null;
                }
                host.notice("Saved. The bot picks it up in a few seconds.");
            } else if (r.kind() == ApiError.Kind.CANCELLED) {
                host.notice("Not saved: the password was not confirmed.");
            } else if (r.kind() == ApiError.Kind.LOGIN || r.kind() == ApiError.Kind.MUST_CHANGE) {
                host.failed(r);
            } else if (r.kind() == ApiError.Kind.RATE_LIMIT) {
                host.notice("Too many changes. Wait " + r.wait429() + " s.");
            } else if (r.kind() == ApiError.Kind.NETWORK) {
                host.notice("Can't reach the server.");
            } else {
                String why = Text.sanitize(r.error(), 120);
                host.notice(why.isEmpty() ? "Couldn't save that." : why);
            }
            // a poll that started while the write ran may have seen the old state
            token++;
            nextGet = 0;
            rebuildUnlessBusy();
        });
        rebuildUnlessBusy();
    }
}
