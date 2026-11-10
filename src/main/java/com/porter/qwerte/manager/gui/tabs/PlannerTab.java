package com.porter.qwerte.manager.gui.tabs;

import com.google.gson.JsonObject;
import com.porter.qwerte.manager.gui.ItemStacks;
import com.porter.qwerte.manager.gui.style.PageRoom;
import com.porter.qwerte.manager.gui.style.SmButton;
import com.porter.qwerte.manager.gui.style.SmCard;
import com.porter.qwerte.manager.gui.style.SmGrid;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import com.porter.qwerte.manager.gui.style.SmTable;
import com.porter.qwerte.manager.gui.style.Ui;
import com.porter.qwerte.manager.gui.widgets.WItemIcon;
import com.porter.qwerte.manager.model.CraftModel;
import com.porter.qwerte.manager.model.PlanModel;
import com.porter.qwerte.manager.model.SchematicFiles;
import com.porter.qwerte.manager.model.StashRef;
import com.porter.qwerte.manager.model.Text;
import com.porter.qwerte.manager.net.ApiError;
import com.porter.qwerte.manager.net.ApiResult;
import com.porter.qwerte.manager.net.ServerUrl;
import com.porter.qwerte.manager.net.SmClient;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The Build Planner tab: the website Build Planner page. Pick a schematic from the game folder (schematics), upload it to the
 * server, see the materials against a stash (need, in stash, available, missing) with options (copies, rounding, fluids, chest
 * contents, skipped items), and order them part by part.
 *
 * <p>The plan lives on the server for 30 minutes; this tab only keeps the last answer in memory (nothing is saved to disk) and
 * picks the newest plan up again when it is shown. Files are listed and read on a background thread, never the client thread,
 * and are checked for size before they are read. Answers carry a token and are dropped when the tab was reset since (or, for the
 * plan views, when a newer view was asked for). Ordering is a dry run first, then a confirmation that lists the parts.
 * {@link #clearSecrets()} drops the plan and any file bytes.
 */
public final class PlannerTab {
    private static final long POLL_MS = 10_000;
    private static final long LOAD_DEBOUNCE_MS = 250;
    private static final long FILTER_DEBOUNCE_MS = 250;
    private static final int PICK_SHOWN = 8;
    private static final int LIST_SHOWN = 30;
    private static final String PLANNER = "/api/v1/planner";
    private static final String[] ROUND_LABELS = {"No rounding", "Whole stacks", "Whole shulkers"};
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qwerte-planner-io");
        t.setDaemon(true);
        return t;
    });

    private final ServerTabHost host;
    private GuiTheme theme;

    // the plan and what it was asked with
    private PlanModel.Plan plan;
    private PlanModel.Options options = PlanModel.Options.DEFAULT;
    private String stashKey;
    private boolean names = true;
    private PlanModel.Dry dry;
    private final Map<String, String> statuses = new HashMap<>();
    private String lockMark = "";
    private String status = "";
    private boolean statusWarn;
    private String orderNote = "";
    private boolean orderWarn;

    // requests
    private int token;
    private int seq;
    private boolean uploading;
    private boolean loading;
    private boolean ordering;
    private boolean resumed;
    private boolean polling;
    private long loadAt;
    private long nextPoll;
    private byte[] uploadBytes;
    /** A rebuild is wanted; it runs once no text box has the keyboard. */
    private boolean redrawPending;
    /** The plan was asked for before the stash list arrived: ask again when it has. */
    private boolean waitingForStash;
    /** The stash the dry run was planned for: the order is only placed against the same one. */
    private String dryKey;
    private WContainer root;
    private WLabel rangeLabel;
    private double wrapW = 500;
    private double tableW = 500;

    // the table
    private String filter = "";
    private boolean onlyMissing;
    private PlanModel.Sort sort = PlanModel.Sort.CREATIVE;
    private boolean desc;
    private int page;
    private boolean filterPending;
    private long filterDirtyAt;

    // the picker
    private List<SchematicFiles.Entry> files = List.of();
    private String pickQuery = "";
    private boolean scanning;
    private boolean scanned;
    private String scanNote = "";

    // what the widgets were built from
    private String builtHint = "";
    private WVerticalList pickBox;
    private WVerticalList tableBox;
    private WLabel statusLabel;
    private WLabel orderLabel;

    public PlannerTab(ServerTabHost host) {
        this.host = host;
    }

    /** The window closed or the session ended: forget the plan, the files and everything asked. */
    public void reset() {
        token++;
        seq++;
        uploading = false;
        loading = false;
        ordering = false;
        resumed = false;
        polling = false;
        scanning = false;
        scanned = false;
        loadAt = 0;
        nextPoll = 0;
        filterPending = false;
        stashKey = null;
        names = true;
        options = PlanModel.Options.DEFAULT;
        filter = "";
        onlyMissing = false;
        sort = PlanModel.Sort.CREATIVE;
        desc = false;
        page = 0;
        pickQuery = "";
        status = "";
        orderNote = "";
        scanNote = "";
        lockMark = "";
        builtHint = "";
        clearSecrets();
        root = null;
        rangeLabel = null;
        pickBox = null;
        tableBox = null;
        statusLabel = null;
        orderLabel = null;
    }

    /** The window is closing: drop the plan, the listed files and any file bytes. */
    public void clearSecrets() {
        // Answers still on their way are dropped, so every in-flight flag goes with them
        token++;
        seq++;
        uploading = false;
        loading = false;
        ordering = false;
        polling = false;
        scanning = false;
        resumed = false;
        scanned = false;
        loadAt = 0;
        nextPoll = 0;
        filterPending = false;
        redrawPending = false;
        waitingForStash = false;
        dryKey = null;
        plan = null;
        dry = null;
        uploadBytes = null;
        files = List.of();
        statuses.clear();
        if (pickBox != null) pickBox.clear();
        if (tableBox != null) tableBox.clear();
    }

    /** Asks for a rebuild; it runs from {@link #tick} once no text box has the keyboard (a rebuild would take the cursor and the typed text). */
    private void redraw() {
        redrawPending = true;
    }

    // ---------- per frame ----------

    public void tick(long now) {
        if (host.closed() || !host.auth().can("planner")) return;
        if (redrawPending && !(root != null && TabFocus.anyFocused(root))) {
            redrawPending = false;
            host.later(host::rebuild);
        }
        if (!scanned && !scanning) scan();
        if (plan == null && !resumed && !uploading) resume();
        if (waitingForStash && plan != null && stash() != null) {
            waitingForStash = false;
            load();
        }
        if (dry != null && (stash() == null || !stash().key().equals(dryKey))) {
            // the stash changed (or went away) under a shown dry run: it would order from another stash
            dry = null;
            setOrderNote("The stash changed. Plan the order again.", true);
            redraw();
        }
        if (loadAt > 0 && now >= loadAt && !uploading) {
            loadAt = 0;
            load();
        }
        if (filterPending && now - filterDirtyAt >= FILTER_DEBOUNCE_MS) {
            filterPending = false;
            page = 0;
            fillTable();
        }
        // the order list needs the view permission: without it the poll would fail every 10 s
        if (plan != null && !polling && now >= nextPoll && host.auth().can("view") && PlanModel.pollOrders(plan.ordered(), statuses)) pollOrders(now);
    }

    private StashRef find(String key) {
        if (key == null) return null;
        for (StashRef s : host.stashes()) if (s.key().equals(key)) return s;
        return null;
    }

    /** The stash the materials are checked against: the one picked here, else the window stash, else the first. */
    private StashRef stash() {
        StashRef found = find(stashKey);
        if (found != null) return found;
        StashRef selected = host.selectedStash();
        StashRef fromList = selected == null ? null : find(selected.key());
        if (fromList == null && !host.stashes().isEmpty()) fromList = host.stashes().get(0);
        stashKey = fromList == null ? null : fromList.key();
        return fromList;
    }

    private void setStatus(String text, boolean warn) {
        status = text;
        statusWarn = warn;
        if (statusLabel != null) {
            statusLabel.set(text);
            statusLabel.color(warn ? Ui.warn(theme) : Ui.dim(theme));
        }
    }

    private void setOrderNote(String text, boolean warn) {
        orderNote = text;
        orderWarn = warn;
        if (orderLabel != null) {
            orderLabel.set(text);
            orderLabel.color(warn ? Ui.warn(theme) : Ui.dim(theme));
        }
    }

    /** True when the answer was a failure the window handles for every tab (login lost, no permission...). */
    private boolean handled(ApiResult r) {
        if (r.kind() == ApiError.Kind.LOGIN || r.kind() == ApiError.Kind.MUST_CHANGE || r.kind() == ApiError.Kind.NO_PERMISSION) {
            host.failed(r);
            return true;
        }
        return false;
    }

    // ---------- the schematic folder ----------

    private void scan() {
        scanning = true;
        scanNote = "";
        int t = token;
        Path dir = schematicsDir();
        fillPicker();
        IO.execute(() -> {
            List<SchematicFiles.Entry> list = List.of();
            String note = "";
            try {
                list = SchematicFiles.scan(dir);
            } catch (java.io.IOException | RuntimeException e) {
                note = "Could not read the schematics folder.";
            }
            List<SchematicFiles.Entry> found = list;
            String error = note;
            host.later(() -> {
                if (t != token) return;
                scanning = false;
                if (host.closed()) {
                    // shown again later (the preview): look again then
                    scanned = false;
                    return;
                }
                files = found;
                scanned = true;
                scanNote = error;
                fillPicker();
            });
        });
    }

    private static Path schematicsDir() {
        try {
            return FabricLoader.getInstance().getGameDir().resolve("schematics");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void upload(SchematicFiles.Entry entry) {
        if (uploading || entry == null) return;
        StashRef s = stash();
        uploading = true;
        setStatus("Reading " + Text.sanitize(entry.name(), 60) + "...", false);
        int t = token;
        Path path = entry.path();
        Path dir = schematicsDir();
        String fileName = entry.name();
        IO.execute(() -> {
            // only a file that really lies inside the schematics folder is read (a junction below it may point anywhere)
            SchematicFiles.Loaded loaded = SchematicFiles.read(dir, path);
            host.later(() -> {
                if (t != token) return;
                if (host.closed()) {
                    uploading = false;
                    return;
                }
                if (!loaded.ok()) {
                    uploading = false;
                    setStatus(loaded.error(), true);
                    return;
                }
                send(t, fileName, s, loaded.bytes());
            });
        });
    }

    private void send(int t, String fileName, StashRef s, byte[] bytes) {
        uploadBytes = bytes;
        setStatus("Uploading " + Text.sanitize(fileName, 60) + " and reading it...", false);
        Map<String, String> q = new LinkedHashMap<>();
        q.put("name", fileName.length() > 200 ? fileName.substring(0, 200) : fileName);
        if (s != null) {
            q.put("server", s.server());
            q.put("stash", s.stash());
        }
        host.client().postBytes(PLANNER + "/upload", q, bytes, SmClient.UPLOAD_TIMEOUT, r -> {
            if (t != token) return;
            uploadBytes = null;
            uploading = false;
            if (host.closed()) return;
            if (r.ok()) {
                PlanModel.Plan p = PlanModel.parsePlan(r.obj().get("plan"));
                if (p == null) {
                    setStatus("The server's answer had no material list.", true);
                    return;
                }
                seq++;
                plan = p;
                options = PlanModel.Options.DEFAULT;
                dry = null;
                statuses.clear();
                lockMark = "";
                filter = "";
                onlyMissing = false;
                page = 0;
                setStatus("", false);
                setOrderNote("", false);
                host.notice("Material list ready.");
                redraw();
            } else if (!handled(r)) {
                setStatus(PlanModel.uploadError(r.status(), r.error(), r.wait429()), true);
            }
        });
    }

    // ---------- the plan on the server ----------

    /** Picks up the newest plan again (the window was closed, or the session came back). */
    private void resume() {
        resumed = true;
        int t = token;
        // client().get, not host.read: picking a plan up again must never open the password prompt by itself
        host.client().get(PLANNER, null, r -> {
            if (t != token) return;
            if (host.closed()) {
                resumed = false;
                return;
            }
            if (plan != null || uploading) return;
            if (!r.ok()) {
                if (!handled(r) && r.kind() != ApiError.Kind.NETWORK) setStatus(PlanModel.orderError(r.status(), r.error()), true);
                return;
            }
            List<PlanModel.Summary> list = PlanModel.parseList(r.body());
            if (list.isEmpty()) return;
            PlanModel.Summary last = list.get(list.size() - 1);
            // a stub until the view arrives: the id and the name
            plan = new PlanModel.Plan(last.id(), last.fileName(), last.format(), 0, 0, 0, 0, 0, last.blocks(), false, last.createdAt(),
                last.expiresAt(), options, List.of(), List.of(), List.of(), 0, null, List.of(),
                new PlanModel.Totals(0, 0, 0, 0, false, false, 0, 0, 0, 0), null);
            load();
        });
    }

    private void scheduleLoad() {
        loadAt = System.currentTimeMillis() + LOAD_DEBOUNCE_MS;
    }

    private JsonObject body(StashRef s) {
        JsonObject b = new JsonObject();
        b.addProperty("server", s.server());
        b.addProperty("stash", s.stash());
        options.addTo(b);
        return b;
    }

    /** Asks the server for the plan with the options and the stash shown. */
    private void load() {
        PlanModel.Plan p = plan;
        StashRef s = stash();
        if (p == null || s == null) {
            // no stash list yet: the plan stays a stub until it arrives
            if (p != null) waitingForStash = true;
            redraw();
            return;
        }
        waitingForStash = false;
        int t = token;
        int q = ++seq;
        loading = true;
        host.write(PLANNER + "/" + ServerUrl.seg(p.id()) + "/view", body(s), r -> {
            if (t != token || q != seq) return;
            loading = false;
            if (host.closed()) {
                scheduleLoad();
                return;
            }
            if (r.ok()) {
                PlanModel.Plan np = PlanModel.parsePlan(r.obj().get("plan"));
                if (np == null) {
                    setOrderNote("The answer had no material list.", true);
                    return;
                }
                plan = np;
                if (takeLockedOptions(np)) return;
                nextPoll = 0;
                redraw();
            } else if (r.kind() == ApiError.Kind.NOT_FOUND) {
                plan = null;
                dry = null;
                setStatus("That material list has ended (they end after 30 minutes unused). Pick the file again.", true);
                redraw();
            } else if (r.kind() == ApiError.Kind.CANCELLED) {
                setOrderNote("Not updated: the password was not confirmed.", true);
            } else if (!handled(r)) {
                setOrderNote("Could not update the list: " + PlanModel.orderError(r.status(), r.error()), true);
            }
        });
    }

    /**
     * Parts were ordered with other options than the ones shown (after the window was closed, or from the website): put the
     * locked options and stash back and ask again. Once per locked split, so a stash that is gone cannot loop.
     */
    private boolean takeLockedOptions(PlanModel.Plan p) {
        PlanModel.Ordered o = p.ordered();
        if (o == null || !o.locked() || o.sameOptions()) return false;
        String mark = p.id() + "|" + o.placed();
        if (mark.equals(lockMark)) return false;
        lockMark = mark;
        options = o.options();
        for (StashRef s : host.stashes()) {
            if (s.server().equals(o.server()) && s.stash().equals(o.stash())) stashKey = s.key();
        }
        load();
        return true;
    }

    private boolean locked() {
        return plan != null && plan.ordered() != null && plan.ordered().locked();
    }

    /** An option changed: ask again soon (a burst of changes is one request). */
    private void changed(PlanModel.Options next) {
        if (locked()) return;
        options = next;
        dry = null;
        page = 0;
        scheduleLoad();
    }

    // ---------- the orders of the parts ----------

    private void pollOrders(long now) {
        StashRef s = stash();
        if (s == null) return;
        polling = true;
        nextPoll = now + POLL_MS;
        int t = token;
        Map<String, String> q = new LinkedHashMap<>();
        q.put("server", s.server());
        q.put("stash", s.stash());
        host.client().get("/api/v1/orders", q, r -> {
            if (t != token) return;
            polling = false;
            if (host.closed()) return;
            if (!r.ok()) {
                handled(r);
                return;
            }
            statuses.putAll(PlanModel.parseOrderStatuses(r.body()));
            PlanModel.Ordered o = plan == null ? null : plan.ordered();
            if (PlanModel.needsReload(o, statuses)) scheduleLoad();
            if (!hintKey().equals(builtHint)) redraw();
        });
    }

    private String hintKey() {
        PlanModel.OrderHint h = PlanModel.hint(plan == null ? null : plan.ordered(), statuses);
        return h.text() + "|" + h.nextEnabled();
    }

    /** The dry run: what the order would be, to be confirmed. */
    private void planOrder() {
        StashRef s = stash();
        if (ordering || plan == null || s == null) return;
        ordering = true;
        setOrderNote("Planning the order...", false);
        int t = token;
        JsonObject b = body(s);
        b.addProperty("dryRun", true);
        String forKey = s.key();
        host.write(PLANNER + "/" + ServerUrl.seg(plan.id()) + "/order", b, r -> {
            if (t != token) return;
            ordering = false;
            if (host.closed()) return;
            if (r.ok()) {
                dry = PlanModel.parseDry(r.body());
                dryKey = forKey;
                setOrderNote("", false);
            } else {
                dry = null;
                orderFailed(r, "Could not plan the order: ");
            }
            redraw();
        });
        redraw();
    }

    private void place(int part) {
        StashRef s = stash();
        if (ordering || plan == null || s == null) return;
        if (dry != null && !s.key().equals(dryKey)) {
            // the order shown was planned for another stash
            dry = null;
            setOrderNote("The stash changed. Plan the order again.", true);
            redraw();
            return;
        }
        ordering = true;
        setOrderNote("Placing part " + part + "...", false);
        int t = token;
        JsonObject b = body(s);
        b.addProperty("names", names);
        b.addProperty("part", part);
        host.write(PLANNER + "/" + ServerUrl.seg(plan.id()) + "/order", b, r -> {
            if (t != token) return;
            ordering = false;
            if (host.closed()) return;
            if (r.ok()) {
                PlanModel.Placed placed = PlanModel.parsePlaced(r.body());
                if (placed != null && plan != null) {
                    plan = plan.withOrdered(new PlanModel.Ordered(placed.partsTotal(), placed.placed(), placed.remaining(), placed.again(),
                        true, true, options, s.server(), s.stash()));
                    if (!placed.orderId().isEmpty()) statuses.put(placed.orderId(), placed.orderStatus());
                    host.notice("Part " + placed.part() + " of " + placed.partsTotal() + " ordered.");
                }
                dry = null;
                setOrderNote("", false);
                nextPoll = 0;
                scheduleLoad();
            } else {
                // the shown plan is stale after a refusal (409: the list or the bot changed): plan it again
                dry = null;
                orderFailed(r, "Could not place the order: ");
                if (r.kind() == ApiError.Kind.CONFLICT) scheduleLoad();
            }
            redraw();
        });
        redraw();
    }

    private void orderFailed(ApiResult r, String prefix) {
        if (r.kind() == ApiError.Kind.CANCELLED) {
            setOrderNote("Not done: the password was not confirmed.", true);
        } else if (!handled(r)) {
            setOrderNote(prefix + PlanModel.orderError(r.status(), r.error()), true);
        }
    }

    private void dismiss() {
        PlanModel.Plan p = plan;
        if (p == null || ordering) return;
        ordering = true;
        // a view that is still on its way must not bring the plan back
        seq++;
        loading = false;
        loadAt = 0;
        int t = token;
        host.write(PLANNER + "/" + ServerUrl.seg(p.id()) + "/delete", new JsonObject(), r -> {
            if (t != token) return;
            ordering = false;
            if (host.closed()) return;
            if (handled(r)) return;
            seq++;
            plan = null;
            dry = null;
            options = PlanModel.Options.DEFAULT;
            statuses.clear();
            lockMark = "";
            setOrderNote("", false);
            setStatus(r.ok() ? "Dismissed. The material list was deleted from the server." : "Dismissed here; the server could not be reached to delete it.", !r.ok());
            redraw();
        });
    }

    private void copyMissing() {
        if (PlanModel.missingRows(plan).isEmpty()) {
            host.notice("Nothing is missing.");
            return;
        }
        mc.keyboardHandler.setClipboard(PlanModel.missingText(plan));
        host.notice("Copied the missing materials.");
    }

    // ---------- widgets ----------

    public void build(WContainer parent, GuiTheme theme) {
        this.theme = theme;
        root = parent;
        redrawPending = false;
        rangeLabel = null;
        pickBox = null;
        tableBox = null;
        statusLabel = null;
        orderLabel = null;
        double wrap = PageRoom.width(parent, 16);
        wrapW = wrap;
        tableW = PageRoom.width(parent, 16);
        if (!host.auth().can("planner")) {
            Ui.pageTitle(parent, theme, "build planner");
            parent.add(Ui.wrap(theme, "Your account can not use the Build Planner; ask the owner.", wrap)).expandX().widget().color(Ui.warn(theme));
            return;
        }
        builtHint = hintKey();
        // "material list", with Dismiss at the right end once there is a plan, like the website
        WHorizontalList titleRow = parent.add(theme.horizontalList()).expandX().widget();
        titleRow.add(Ui.muted(theme, plan == null ? "build planner" : "material list")).expandX();
        if (plan != null) {
            SmButton dismiss = (SmButton) titleRow.add(theme.button("Dismiss")).widget();
            dismiss.pill = true;
            dismiss.tooltip = "Delete this material list from the server";
            dismiss.action = this::dismiss;
        }

        SmCard schem = Ui.card(parent, "Schematic");
        schem.add(Ui.muted(theme, "Pick a schematic from the schematics folder of your game and upload it. Only its material list is kept, in memory, on the server.", wrap)).expandX();
        picker(schem, wrap);
        statusLabel = schem.add(Ui.muted(theme, status, wrap)).expandX().widget();
        statusLabel.color(statusWarn ? Ui.warn(theme) : Ui.dim(theme));
        if (plan == null) return;
        if (loading && plan.items().isEmpty() && plan.stash() == null) {
            parent.add(Ui.muted(theme, "Loading the material list...", wrap)).expandX();
            return;
        }
        fileCard(parent, wrap);
        SmCard opts = Ui.card(parent, "Options");
        stashPicker(opts);
        optionsRow(opts, wrap);
        craftBanners(opts, wrap);
        tiles(parent, wrap);
        tableSection(parent, wrap);
        lists(parent, wrap);
    }

    private static String uniqueLabel(StashRef s, boolean streamer, Set<String> used) {
        String base = s.label(streamer);
        String label = base;
        for (int i = 2; !used.add(label); i++) label = base + " #" + i;
        return label;
    }

    private void stashPicker(WContainer parent) {
        List<StashRef> all = host.stashes();
        StashRef cur = stash();
        boolean streamer = host.policy().streamer();
        WHorizontalList row = parent.add(theme.horizontalList()).expandX().widget();
        row.add(theme.label("Stash"));
        if (all.isEmpty() || cur == null) {
            row.add(theme.label("No stash yet: the list can not be checked against one.")).widget().color(Ui.warn(theme));
            return;
        }
        if (locked()) {
            row.add(theme.label(cur.label(streamer)));
            row.add(theme.label("(fixed while parts are ordered)")).widget().color(Ui.dim(theme));
            return;
        }
        Set<String> used = new HashSet<>();
        String[] labels = new String[all.size()];
        String curLabel = null;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = uniqueLabel(all.get(i), streamer, used);
            if (all.get(i).key().equals(cur.key())) curLabel = labels[i];
        }
        WDropdown<String> pick = row.add(theme.dropdown(labels, curLabel == null ? labels[0] : curLabel)).widget();
        pick.action = () -> {
            for (int i = 0; i < labels.length; i++) {
                if (labels[i].equals(pick.get()) && !all.get(i).key().equals(stashKey)) {
                    stashKey = all.get(i).key();
                    if (plan != null) {
                        dry = null;
                        scheduleLoad();
                    }
                }
            }
        };
    }

    private void picker(WContainer parent, double wrap) {
        wrapW = wrap;
        WHorizontalList bar = parent.add(theme.horizontalList()).expandX().widget();
        WTextBox box = bar.add(theme.textBox(pickQuery, "Search schematics")).minWidth(theme.scale(220)).expandX().widget();
        box.action = () -> {
            if (box.get().equals(pickQuery)) return;
            pickQuery = box.get();
            fillPicker();
        };
        WButton refresh = bar.add(theme.button("Refresh")).widget();
        refresh.tooltip = "Look in the schematics folder again";
        refresh.action = () -> {
            if (!scanning) scan();
        };
        pickBox = parent.add(theme.verticalList()).expandX().widget();
        fillPicker();
    }

    /** Fills the list of files in place, so the search box keeps its cursor. */
    private void fillPicker() {
        if (pickBox == null || theme == null) return;
        pickBox.clear();
        double wrap = wrapW;
        if (scanning) {
            pickBox.add(Ui.muted(theme, "Looking for schematics...", wrap)).expandX();
            return;
        }
        if (!scanNote.isEmpty()) pickBox.add(Ui.wrap(theme, scanNote, wrap)).expandX().widget().color(Ui.warn(theme));
        List<SchematicFiles.Entry> list = SchematicFiles.filter(files, pickQuery);
        if (list.isEmpty()) {
            pickBox.add(Ui.muted(theme, files.isEmpty()
                ? "No schematics found. Put .litematic, .schem, .schematic or .nbt files in the schematics folder of your game (at most 2 folders deep), then press Refresh."
                : "No schematic matches.", wrap)).expandX();
            return;
        }
        SmTable table = pickBox.add(new SmTable("File", "Size", "Changed", "")).expandX().widget();
        table.weights(5, 1, 1, 1);
        table.rightAlign(3);
        table.maxWidth = tableW;
        long now = System.currentTimeMillis();
        int shown = 0;
        for (SchematicFiles.Entry e : list) {
            if (shown++ >= PICK_SHOWN) break;
            WWidget action;
            if (e.empty() || e.tooBig()) {
                WLabel w = theme.label(e.empty() ? "empty" : "over 8 MiB");
                w.color(Ui.warn(theme));
                action = w;
            } else {
                SmButton up = (SmButton) theme.button("Upload");
                up.pill = true;
                up.tooltip = "Upload this file and list its materials";
                up.action = () -> upload(e);
                action = up;
            }
            table.addRow(theme.label(Text.sanitize(e.relative(), 60)), Ui.muted(theme, PlanModel.size(e.size())), Ui.muted(theme, Text.ago(Math.max(0, now - e.modified()))), action);
        }
        if (list.size() > PICK_SHOWN) {
            pickBox.add(Ui.muted(theme, "Showing the first " + PICK_SHOWN + " of " + list.size() + ". Type in the search box to narrow it.", wrap)).expandX();
        }
    }

    private void kv(SmCard card, String key, String value) {
        WHorizontalList row = card.add(theme.horizontalList()).expandX().widget();
        row.add(Ui.muted(theme, key)).minWidth(theme.scale(90));
        row.add(theme.label(value));
    }

    /** The website's File card: what was uploaded, as name and value rows. */
    private void fileCard(WContainer parent, double wrap) {
        PlanModel.Plan p = plan;
        SmCard card = Ui.card(parent, "File");
        card.spacing = 3;
        kv(card, "File", Text.sanitize(p.fileName(), 80));
        kv(card, "Format", p.format());
        kv(card, "Size", PlanModel.size(p.size()));
        kv(card, "Dimensions", p.dimX() + " x " + p.dimY() + " x " + p.dimZ() + (p.regions() > 1 ? " (" + p.regions() + " regions)" : ""));
        kv(card, "Blocks", Text.grouped(p.blocks()));
        if (p.newerThanTable()) {
            card.add(Ui.wrap(theme, "This schematic is from a newer Minecraft than the item table, so a few blocks may be missing from the list.", wrap)).expandX().widget().color(Ui.warn(theme));
        }
        PlanModel.StashInfo st = p.stash();
        if (st == null) {
            card.add(Ui.wrap(theme, "Pick a stash to compare the list with it.", wrap)).expandX().widget().color(Ui.warn(theme));
        } else if (!st.indexed()) {
            card.add(Ui.wrap(theme, "This stash has no index yet, so nothing counts as in stock.", wrap)).expandX().widget().color(Ui.warn(theme));
        }
    }

    private void tile(SmGrid grid, String name, String value, String sub, boolean danger) {
        int cols = Math.max(1, grid.columns);
        double cellW = (grid.maxWidth - (cols - 1) * theme.scale(6)) / cols - theme.scale(12);
        SmCard t = grid.add(new SmCard(name, danger ? SmCard.Style.DANGER : SmCard.Style.INSET)).widget();
        t.quietTitle = true;
        t.padding = 6;
        t.spacing = 1;
        t.add(theme.label(value, true));
        t.add(Ui.muted(theme, sub, Math.max(theme.scale(60), cellW)));
    }

    /** The five summary tiles, then the order buttons. */
    private void tiles(WContainer parent, double wrap) {
        PlanModel.Plan p = plan;
        PlanModel.Totals t = p.totals();
        SmCard card = Ui.card(parent, "");
        SmGrid grid = card.add(new SmGrid()).expandX().widget();
        CraftModel.Info ci = p.craft();
        boolean craftTile = ci != null && ci.showsColumn();
        grid.columns = craftTile ? 6 : 5;
        grid.gapX = 6;
        grid.maxWidth = PageRoom.width(card, 16);
        tile(grid, "Needed", Text.grouped(t.need()), p.items().size() + (p.items().size() == 1 ? " kind" : " kinds"), false);
        tile(grid, "Available", Text.grouped(t.available()), "in the stash, after open orders", false);
        if (craftTile) tile(grid, "Craftable", ci.available() ? Text.grouped(t.craft() == null ? 0 : t.craft().craftable()) : "...",
            CraftModel.tileSub(t.craft(), ci), false);
        tile(grid, "Missing", Text.grouped(t.missingNow()), t.craft() != null ? "after crafting" : "not in the stash", t.missingNow() > 0);
        tile(grid, "To order", Text.grouped(toOrder()), t.lines() + (t.lines() == 1 ? " line" : " lines"), false);
        tile(grid, "Shulkers", Text.grouped(t.shulkers()), t.parts() + (t.parts() == 1 ? " order" : " orders") + " of up to 54", false);
        if (t.frozen()) card.add(Ui.muted(theme, "The order is fixed: parts are ordered.", wrap)).expandX();
        orderArea(card, wrap);
    }

    /** What an order would take: the item lines, plus the craftable amounts when the craft box is on and the stash can take craft lines. */
    private long toOrder() {
        PlanModel.Totals t = plan.totals();
        return options.craft() && CraftModel.orderable(plan.craft()) && t.craft() != null ? t.craft().wantWithCraft() : t.want();
    }

    /** The banner lines of the plan's craft state (see the contract's UI table). */
    private void craftBanners(WContainer parent, double wrap) {
        for (CraftModel.Banner b : CraftModel.banners(plan.craft())) {
            parent.add(Ui.wrap(theme, b.text(), wrap)).expandX().widget().color(b.warn() ? Ui.warn(theme) : Ui.dim(theme));
        }
    }

    /** The craft box: on when the stash can take craft orders, else off and unusable, with the reason beside it. */
    private void craftBox(WHorizontalList checks) {
        CraftModel.Info ci = plan.craft();
        if (ci == null || !ci.showsColumn()) return;
        boolean usable = CraftModel.orderable(ci);
        WCheckbox box = checks.add(theme.checkbox(usable && options.craft())).widget();
        if (usable) {
            box.tooltip = "Order the items the stash can make from what it holds; the bot crafts them at the crafting table";
            box.action = () -> changed(options.withCraft(box.checked));
        } else {
            box.tooltip = ci.building() ? "Recipes are still loading" : CraftModel.cannotOrder(ci);
            box.action = () -> box.checked = false;
        }
        checks.add(theme.label(CraftModel.boxLabel(ci, plan.totals().craft()))).widget().color(Ui.dim(theme));
    }

    private static String yesNo(boolean on) {
        return on ? "yes" : "no";
    }

    private void optionsRow(WContainer parent, double wrap) {
        if (locked()) {
            parent.add(theme.label("Parts are ordered, so the options are fixed. Copies " + options.multiplier() + " | rounding " + options.round()
                + " | fluids " + yesNo(options.fluids()) + " | chest contents " + yesNo(options.containerItems()) + " | skipped "
                + options.exclude().size() + (plan.craft() != null && plan.craft().available() ? " | crafting " + yesNo(options.craft() && CraftModel.orderable(plan.craft())) : "")
                + ". Dismiss the list to start over.", wrap)).expandX().widget().color(Ui.dim(theme));
            return;
        }
        WHorizontalList row = parent.add(theme.horizontalList()).expandX().widget();
        row.add(theme.label("Copies"));
        WTextBox mult = row.add(theme.textBox(String.valueOf(options.multiplier()), "1", (t, c) -> Character.isDigit(c))).minWidth(theme.scale(60)).widget();
        mult.action = () -> {
            String typed = mult.get().trim();
            boolean tooMany = false;
            try {
                int m = Integer.parseInt(typed);
                tooMany = m > PlanModel.MAX_MULTIPLIER;
                if (m >= 1 && m <= PlanModel.MAX_MULTIPLIER && m != options.multiplier()) changed(options.withMultiplier(m));
            } catch (NumberFormatException ignored) {
                // empty or too long: wait for a number (a long run of digits is too many)
                tooMany = !typed.isEmpty() && typed.chars().allMatch(Character::isDigit);
            }
            if (rangeLabel != null) {
                rangeLabel.set(tooMany ? "At most " + PlanModel.MAX_MULTIPLIER + " copies: that number is not used" : "(1-" + PlanModel.MAX_MULTIPLIER + ")");
                rangeLabel.color(tooMany ? Ui.warn(theme) : Ui.dim(theme));
            }
        };
        rangeLabel = row.add(theme.label("(1-" + PlanModel.MAX_MULTIPLIER + ")")).widget();
        rangeLabel.color(Ui.dim(theme));
        int ri = 0;
        for (int i = 0; i < PlanModel.ROUNDS.length; i++) if (PlanModel.ROUNDS[i].equals(options.round())) ri = i;
        WDropdown<String> round = row.add(theme.dropdown(ROUND_LABELS, ROUND_LABELS[ri])).widget();
        round.action = () -> {
            for (int i = 0; i < ROUND_LABELS.length; i++) {
                if (ROUND_LABELS[i].equals(round.get()) && !PlanModel.ROUNDS[i].equals(options.round())) changed(options.withRound(PlanModel.ROUNDS[i]));
            }
        };
        WHorizontalList checks = parent.add(theme.horizontalList()).expandX().widget();
        WCheckbox fluids = checks.add(theme.checkbox(options.fluids())).widget();
        fluids.tooltip = "Count buckets for the water and lava in the build";
        fluids.action = () -> changed(options.withFluids(fluids.checked));
        checks.add(theme.label("Fluids"));
        WCheckbox chests = checks.add(theme.checkbox(options.containerItems())).widget();
        chests.tooltip = "Also count the items stored in the build chests";
        chests.action = () -> changed(options.withContainers(chests.checked));
        checks.add(theme.label("Chest contents"));
        if (!options.exclude().isEmpty()) {
            checks.add(theme.label(options.exclude().size() + " skipped")).widget().color(Ui.dim(theme));
        }
        craftBox(checks);
    }

    // ---------- ordering ----------

    private void orderArea(WContainer parent, double wrap) {
        StashRef s = stash();
        PlanModel.Ordered o = plan.ordered();
        boolean started = o != null && o.started() && (o.sameOptions() || o.locked());
        orderLabel = parent.add(Ui.wrap(theme, orderNote, wrap)).expandX().widget();
        orderLabel.color(orderWarn ? Ui.warn(theme) : Ui.dim(theme));
        if (!host.auth().can("plannerOrder")) {
            parent.add(Ui.wrap(theme, "Your account can look at the list but not order from it.", wrap)).expandX().widget().color(Ui.dim(theme));
        } else if (dry != null) {
            confirmBox(parent, s, wrap);
        } else if (!started) {
            String why = s == null || plan.stash() == null ? "Pick a stash first."
                : !plan.stash().indexed() ? "This stash has no index yet."
                : toOrder() <= 0 ? "Nothing in this stash is available for the list." : "";
            SmButton order = (SmButton) parent.add(((SmGuiTheme) theme).primaryButton(ordering ? "Planning..." : "Order build materials now")).widget();
            order.tooltip = why.isEmpty() ? "Plan the order and show the parts before anything is ordered" : why;
            order.action = why.isEmpty() ? this::planOrder : () -> host.notice(why);
        } else {
            partButtons(parent, o, wrap);
        }
        WHorizontalList extra = parent.add(theme.horizontalList()).expandX().widget();
        SmButton copy = (SmButton) extra.add(theme.button("Copy missing")).widget();
        copy.pill = true;
        copy.tooltip = "Copy the list of missing materials to the clipboard";
        copy.action = this::copyMissing;
    }

    private void partButtons(WContainer parent, PlanModel.Ordered o, double wrap) {
        PlanModel.OrderHint hint = PlanModel.hint(o, statuses);
        if (!hint.text().isEmpty()) parent.add(Ui.wrap(theme, hint.text(), wrap)).expandX().widget().color(hint.warn() ? Ui.warn(theme) : Ui.dim(theme));
        WHorizontalList buttons = parent.add(theme.horizontalList()).expandX().widget();
        if (!o.remaining().isEmpty()) {
            int next = o.remaining().get(0);
            String label = "Order next part (" + next + "/" + o.partsTotal() + ")";
            if (hint.nextEnabled() && !ordering) {
                WButton b = buttons.add(theme.button(label)).widget();
                b.action = () -> place(next);
            } else {
                buttons.add(theme.label(label + ": wait for the last part")).widget().color(Ui.dim(theme));
            }
        }
        if (!o.again().isEmpty() && !ordering) {
            int again = o.again().get(0);
            WButton b = buttons.add(theme.button("Order part " + again + " again (" + again + "/" + o.partsTotal() + ")")).widget();
            b.action = () -> place(again);
        }
    }

    private void confirmBox(WContainer parent, StashRef s, double wrap) {
        PlanModel.Dry d = dry;
        int total = d.partsTotal();
        parent.add(theme.label("Order the materials", true));
        parent.add(theme.label(total > 1
            ? "This needs " + total + " orders. One order fills at most 54 shulkers (one double chest), so the bot delivers it part by part: order the next part once the previous one is delivered."
            : "This fits in one order.", wrap)).expandX();
        if (!d.parts().isEmpty()) {
            SmTable table = parent.add(new SmTable("Part", "Lines", "Shulkers", "Items")).expandX().widget();
            table.maxWidth = tableW - theme.scale(16);
            for (PlanModel.Part part : d.parts()) {
                table.addRow(theme.label(part.part() + (part.order().isEmpty() ? "" : " (ordered)")), theme.label(Text.grouped(part.lineCount())),
                    theme.label(Text.grouped(part.shulkers())), theme.label(Text.grouped(part.items())));
            }
        }
        long craftLines = 0;
        long craftFills = 0;
        for (PlanModel.Part part : d.parts()) {
            craftLines += part.craftLines();
            craftFills += part.craftFills();
        }
        if (craftLines > 0) {
            parent.add(Ui.wrap(theme, "Crafting: " + craftLines + (craftLines == 1 ? " item" : " items") + " will be made from the stash (" + craftFills
                + (craftFills == 1 ? " fill" : " fills") + " at the crafting table).", wrap)).expandX().widget().color(Ui.dim(theme));
        }
        if (!d.notAvailable().isEmpty()) {
            StringBuilder sb = new StringBuilder("Not available in this stash, left out: ");
            int n = 0;
            for (PlanModel.Count c : d.notAvailable()) {
                if (n++ >= 12) break;
                sb.append(n > 1 ? ", " : "").append(PlanModel.pretty(c.id())).append(" x ").append(Text.grouped(c.count()));
            }
            if (d.notAvailable().size() > 12) sb.append(" and ").append(d.notAvailable().size() - 12).append(" more");
            parent.add(Ui.wrap(theme, sb.append(".").toString(), wrap)).expandX().widget().color(Ui.warn(theme));
        }
        if (!d.missing().isEmpty()) {
            parent.add(theme.label(d.missing().size() + (d.missing().size() == 1 ? " kind" : " kinds") + " of material will still be missing (see the list; Copy missing).", wrap)).expandX().widget().color(Ui.dim(theme));
        }
        confirmButtons(parent, s, d, total, wrap);
    }

    private void confirmButtons(WContainer parent, StashRef s, PlanModel.Dry d, int total, double wrap) {
        WHorizontalList naming = parent.add(theme.horizontalList()).expandX().widget();
        WCheckbox nm = naming.add(theme.checkbox(names)).widget();
        nm.action = () -> {
            names = nm.checked;
            redraw();
        };
        naming.add(theme.label("Name the shulkers after the file"));
        List<String> warnings = new ArrayList<>();
        if (s != null) {
            if (!s.online()) warnings.add("The bot is offline. The order starts when it reconnects.");
            if (!s.bot().orderBlocker().isEmpty()) warnings.add("The bot cannot take orders right now: " + s.bot().orderBlocker() + ".");
            if (!s.bot().hasDelivery()) warnings.add("No delivery container is set on the bot.");
            int first = d.first();
            long shulkers = first >= 1 && first <= d.parts().size() ? d.parts().get(first - 1).shulkers() : 0;
            if (names) {
                String w = s.bot().namesWarning((int) Math.min(Integer.MAX_VALUE, shulkers));
                if (!w.isEmpty()) warnings.add(w);
            }
        }
        if (!warnings.isEmpty()) parent.add(Ui.wrap(theme, String.join(" ", warnings), wrap)).expandX().widget().color(Ui.warn(theme));
        if (d.parts().isEmpty()) {
            parent.add(Ui.wrap(theme, "Nothing to order: the stash has none of these materials available.", wrap)).expandX().widget().color(Ui.warn(theme));
        }
        WHorizontalList buttons = parent.add(theme.horizontalList()).expandX().widget();
        if (!d.parts().isEmpty()) {
            int first = d.first();
            WButton go = buttons.add(theme.button(total > 1 ? "Order part " + first + " of " + total : "Place order")).widget();
            go.action = () -> place(first);
        }
        WButton cancel = buttons.add(theme.button("Cancel")).widget();
        cancel.action = () -> {
            dry = null;
            redraw();
        };
    }

    // ---------- the materials ----------

    private void tableSection(WContainer parent, double wrap) {
        SmCard card = Ui.card(parent, "Materials");
        tableW = PageRoom.width(card, 16);
        WHorizontalList f = card.addRight(theme.horizontalList()).widget();
        WTextBox fb = f.add(theme.textBox(filter, "Filter")).minWidth(theme.scale(160)).widget();
        fb.action = () -> {
            if (fb.get().equals(filter)) return;
            filter = fb.get();
            filterPending = true;
            filterDirtyAt = System.currentTimeMillis();
        };
        WCheckbox only = f.add(theme.checkbox(onlyMissing)).widget();
        only.action = () -> {
            onlyMissing = only.checked;
            page = 0;
            fillTable();
        };
        f.add(theme.label("only missing"));
        PlanModel.Sort[] sorts = PlanModel.Sort.values();
        String[] labels = new String[sorts.length];
        for (int i = 0; i < labels.length; i++) labels[i] = sorts[i].label();
        WDropdown<String> sortBox = f.add(theme.dropdown(labels, sort.label())).widget();
        sortBox.action = () -> {
            sort = PlanModel.Sort.ofLabel(sortBox.get());
            page = 0;
            fillTable();
        };
        WCheckbox d = f.add(theme.checkbox(desc)).widget();
        d.action = () -> {
            desc = d.checked;
            page = 0;
            fillTable();
        };
        f.add(theme.label("descending"));
        tableBox = card.add(theme.verticalList()).expandX().widget();
        fillTable();
        card.add(Ui.muted(theme, "sh = a shulker (27 stacks), st = a stack. Untick a row to leave it out. Items with extra data (enchantments, names) are shown but never counted or ordered.", tableW)).expandX();
    }

    private Color needColor(PlanModel.Row r) {
        if (r.excluded()) return Ui.dim(theme);
        if (CraftModel.stillMissing(r)) return Ui.err(theme);
        // craftable: the plain text colour, neither red nor "all there"
        return r.craft() != null && r.missing() > 0 ? null : Ui.ok(theme);
    }

    /** Fills one page of the table in place (the filter box keeps its cursor). */
    private void fillTable() {
        if (tableBox == null || plan == null) return;
        tableBox.clear();
        List<PlanModel.Row> rows = PlanModel.visible(plan.items(), filter, onlyMissing, sort, desc);
        int pages = PlanModel.pageCount(rows.size());
        page = Math.max(0, Math.min(page, pages - 1));
        WHorizontalList pager = tableBox.add(theme.horizontalList()).expandX().widget();
        pager.add(Ui.muted(theme, rows.size() + " of " + plan.items().size() + " materials")).expandX();
        SmButton prev = (SmButton) pager.add(theme.button("<")).widget();
        prev.pill = true;
        prev.action = () -> host.later(() -> {
            if (page > 0) page--;
            fillTable();
        });
        pager.add(theme.label("Page " + (page + 1) + " / " + pages));
        SmButton next = (SmButton) pager.add(theme.button(">")).widget();
        next.pill = true;
        next.action = () -> host.later(() -> {
            page++;
            fillTable();
        });
        if (rows.isEmpty()) {
            tableBox.add(Ui.muted(theme, "Nothing matches.")).expandX();
            return;
        }
        CraftModel.Info ci = plan.craft();
        boolean craftCol = ci != null && ci.showsColumn();
        SmTable table = tableBox.add(craftCol ? new SmTable("", "Name", "Need", "In stash", "Available", "Craft", "Missing")
            : new SmTable("", "Name", "Need", "In stash", "Available", "Missing")).expandX().widget();
        if (craftCol) {
            table.weights(1, 6, 2, 2, 2, 2, 3);
            table.rightAlign(2, 3, 4, 5, 6);
        } else {
            table.weights(1, 6, 2, 2, 2, 2);
            table.rightAlign(2, 3, 4, 5);
        }
        table.maxWidth = tableW;
        for (PlanModel.Row r : PlanModel.page(rows, page)) {
            WHorizontalList first = theme.horizontalList();
            WCheckbox include = first.add(theme.checkbox(!options.exclude().contains(r.id()))).widget();
            include.tooltip = "Untick to leave this item out of the order";
            String id = r.id();
            include.action = () -> changed(options.withExcluded(id, !include.checked));
            WHorizontalList name = theme.horizontalList();
            name.add(new WItemIcon(host::graphics, ItemStacks.displayId(r.id())));
            WVerticalList names2 = name.add(theme.verticalList()).widget();
            names2.spacing = 0;
            WLabel title = names2.add(theme.label(Text.sanitize(r.title(), 40), true)).widget();
            title.tooltip = r.id() + ": blocks " + Text.grouped(r.fromBlocks()) + ", fluids " + Text.grouped(r.fromFluids()) + ", chests "
                + Text.grouped(r.fromContainers()) + (r.variants() > 0 ? ", " + Text.grouped(r.variants()) + " renamed or damaged variants in the stash" : "");
            names2.add(Ui.muted(theme, r.id()));
            WLabel need = theme.label(PlanModel.stacks(r.need(), r.maxStack()));
            need.tooltip = Text.grouped(r.need());
            WLabel inStash = theme.label(r.inIndex() ? PlanModel.stacks(r.inStash(), r.maxStack()) : "-");
            inStash.color(Ui.dim(theme));
            WLabel avail = theme.label(r.inIndex() ? PlanModel.stacks(r.available(), r.maxStack()) : "-");
            avail.color(Ui.dim(theme));
            WLabel missing = theme.label(CraftModel.missingCell(r));
            Color missingColor = needColor(r);
            if (missingColor != null) missing.color(missingColor);
            if (!craftCol) {
                table.addRow(first, name, need, inStash, avail, missing);
                continue;
            }
            WLabel craft = theme.label(CraftModel.craftCell(r, ci));
            craft.color(Ui.dim(theme));
            String tip = CraftModel.tooltip(r);
            if (!tip.isEmpty()) {
                craft.tooltip = tip;
                missing.tooltip = tip;
            }
            table.addRow(first, name, need, inStash, avail, craft, missing);
        }
    }

    private void lists(WContainer parent, double wrap) {
        if (!plan.unknown().isEmpty() || plan.unknownOmitted() > 0) {
            SmCard card = Ui.card(parent, "Unknown blocks");
            card.add(Ui.muted(theme, "These blocks have no item the planner knows (a mod, or a newer version).", wrap)).expandX();
            SmTable t = card.add(new SmTable("Block", "Count")).expandX().widget();
            t.weights(5, 1);
            t.rightAlign(1);
            t.maxWidth = PageRoom.width(card, 16);
            int n = 0;
            for (PlanModel.Count c : plan.unknown()) {
                if (n++ >= LIST_SHOWN) break;
                t.addRow(theme.label(Text.sanitize(c.id(), 60)), theme.label(Text.grouped(c.count())));
            }
            long more = Math.max(0, plan.unknown().size() - LIST_SHOWN) + plan.unknownOmitted();
            if (more > 0) card.add(Ui.muted(theme, "and " + Text.grouped(more) + " more")).expandX();
        }
        if (!plan.containerItems().isEmpty()) {
            SmCard card = Ui.card(parent, "Chest contents");
            card.add(Ui.muted(theme, options.containerItems()
                ? "Counted in the list above."
                : "Items stored in the build chests. Turn on Chest contents to count them.", wrap)).expandX();
            SmTable t = card.add(new SmTable("Item", "Count")).expandX().widget();
            t.weights(5, 1);
            t.rightAlign(1);
            t.maxWidth = PageRoom.width(card, 16);
            int n = 0;
            for (PlanModel.Count c : plan.containerItems()) {
                if (n++ >= LIST_SHOWN) break;
                WHorizontalList name = theme.horizontalList();
                name.add(new WItemIcon(host::graphics, ItemStacks.displayId(c.id())));
                name.add(theme.label(PlanModel.pretty(c.id())));
                t.addRow(name, theme.label(Text.grouped(c.count())));
            }
            if (plan.containerItems().size() > LIST_SHOWN) {
                card.add(Ui.muted(theme, "and " + (plan.containerItems().size() - LIST_SHOWN) + " more")).expandX();
            }
        }
    }
}
