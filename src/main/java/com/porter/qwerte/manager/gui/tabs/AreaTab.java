package com.porter.qwerte.manager.gui.tabs;

import com.porter.qwerte.manager.gui.chrome.WFlow;
import com.porter.qwerte.manager.gui.style.PageRoom;
import com.porter.qwerte.manager.gui.style.SmButton;
import com.porter.qwerte.manager.gui.style.SmCard;
import com.porter.qwerte.manager.gui.style.Ui;
import com.porter.qwerte.manager.model.AreaModel;
import com.porter.qwerte.stash.AreaDraft;
import com.porter.qwerte.stash.AreaEdit;
import com.porter.qwerte.stash.CraftBounds;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * The Area page of the Stash window (AREA-CONTRACT.md "In-game Stash window"): the stash's area as stored, what the bot has done with it, and an
 * editor whose draft is only a draft until Save. It works for a server stash and for the local one through an {@link AreaSource}. The draft lives in
 * {@link AreaDraft} (so the world outline and the map see it); nothing is sent or applied until Save. A button that cannot be used is drawn as a
 * plain word with the reason as its tooltip, and the reasons are listed under the buttons.
 */
public final class AreaTab {
    /** What the page needs from the window. */
    public interface Host {
        void later(Runnable r);

        void rebuild();

        void notice(String text);

        boolean closed();
    }

    private static final long POLL_MS = 5_000;
    private static final long LOCAL_POLL_MS = 1_000;
    private static final long RETRY_MS = 10_000;
    private static final long CHECK_MS = 250;

    private final AreaSource source;
    private final Host host;
    private final AreaDraft drafts = AreaDraft.shared();
    /** The block in the crosshair (within 64 blocks) when the window was opened, or null. */
    private final int[] look = AreaWorld.lookNow();

    private GuiTheme theme;
    private WContainer root;
    private AreaModel.Data data;
    private String error = "";
    private String note = "";
    private boolean busy;
    private boolean saving;
    private long nextGet;
    private long nextCheck;
    private int token;
    private String key = "";
    private String builtSig = "";
    private String nText = "8";

    public AreaTab(AreaSource source, Host host) {
        this.source = source;
        this.host = host;
    }

    public void reset() {
        token++;
        data = null;
        error = "";
        note = "";
        busy = false;
        saving = false;
        nextGet = 0;
        nextCheck = 0;
        key = "";
        builtSig = "";
        root = null;
    }

    // ---------- per frame ----------

    /** Called every frame while the page (or the map's area editing) is shown. */
    public void tick(long now) {
        tick(now, true);
    }

    /** {@code page} false: only the data is kept fresh (the map uses it); the page is not on screen, so nothing is rebuilt for it. */
    public void tick(long now, boolean page) {
        if (host.closed()) return;
        String k = source.ready() ? source.key() : "";
        if (!k.equals(key)) {
            // another stash: start over (a draft for the old one stays until cancelled or it lapses)
            token++;
            busy = false;
            saving = false;
            data = null;
            error = "";
            note = "";
            key = k;
            nextGet = 0;
            if (page) host.later(host::rebuild);
            return;
        }
        if (k.isEmpty()) return;
        if (!busy && !saving && now >= nextGet) fetch(now);
        if (!page || now < nextCheck) return;
        nextCheck = now + CHECK_MS;
        if (!sig(now).equals(builtSig) && !(root != null && TabFocus.anyFocused(root))) host.later(host::rebuild);
    }

    private void fetch(long now) {
        busy = true;
        nextGet = now + (source.server() ? POLL_MS : LOCAL_POLL_MS);
        int t = token;
        source.load(r -> {
            if (t != token || host.closed()) return;
            busy = false;
            if (r.data() != null) {
                data = r.data();
                error = "";
            } else if (!r.error().isEmpty()) {
                data = null;
                error = r.error();
                nextGet = System.currentTimeMillis() + RETRY_MS;
            } else {
                nextGet = System.currentTimeMillis() + RETRY_MS;
            }
        });
    }

    // ---------- what the page is made of ----------

    private AreaDraft.State state(long now) {
        return drafts.forKey(key, now);
    }

    private static String full(String dimension) {
        return dimension == null ? "" : CraftBounds.fullId(dimension);
    }

    /** The dimension the draft is in: the stash's, else the stored area's, else the player's. */
    private String baseDimension(AreaModel.Data d) {
        if (!d.stashDimension().isEmpty()) return d.stashDimension();
        if (!d.dimension().isEmpty()) return d.dimension();
        return AreaWorld.dimension();
    }

    private String positionReason(AreaModel.Data d) {
        return AreaModel.positionReason(AreaWorld.haveWorld(), source.sameServer(), baseDimension(d), AreaWorld.dimension());
    }

    private boolean draftChanged(AreaModel.Data d, AreaDraft.State st) {
        if (st == null) return false;
        if (st.clear()) return d.box() != null;
        if (d.box() == null) return true;
        return !Arrays.equals(st.draft().box(), d.box()) || !CraftBounds.sameDimension(st.draft().dimension(), d.dimension());
    }

    private String sig(long now) {
        StringBuilder sb = new StringBuilder();
        sb.append(key).append('|').append(error).append('|').append(note).append('|').append(saving).append('|').append(source.canWrite()).append(source.hide())
            .append(AreaWorld.haveWorld()).append(AreaWorld.dimension()).append(source.sameServer());
        AreaModel.Data d = data;
        if (d != null) {
            sb.append('#').append(d.rev()).append(d.hash()).append(AreaModel.sourceLine(d, source.hide())).append(AreaModel.botLine(d)).append(AreaModel.impactLine(d))
                .append(d.box() == null ? "-" : Arrays.toString(d.box())).append(d.dimension()).append(d.stashDimension())
                .append(d.bot().applied() == null ? "-" : Arrays.toString(d.bot().applied())).append(d.bot().derived() == null ? "-" : Arrays.toString(d.bot().derived()));
        }
        AreaDraft.State st = state(now);
        if (st != null) sb.append('@').append(st.clear()).append(st.draft() == null ? "" : Arrays.toString(st.draft().box()) + st.draft().dimension());
        return sb.toString();
    }

    // ---------- editing ----------

    /** Applies an edit to the draft (started from the stored, applied or derived area, else a box around the player). */
    private void edit(Function<AreaEdit.Draft, AreaEdit.Result> op) {
        AreaModel.Data d = data;
        if (d == null) return;
        long now = System.currentTimeMillis();
        AreaDraft.State st = state(now);
        AreaEdit.Draft base;
        if (st != null && st.draft() != null) {
            base = st.draft();
        } else {
            int[] feet = AreaWorld.feet();
            int[] around = d.bot().applied() != null ? d.bot().applied() : d.bot().derived();
            if (d.box() == null && around == null && feet == null) {
                note = "Join the world to start a box around yourself.";
                host.later(host::rebuild);
                return;
            }
            String dim = baseDimension(d);
            if (dim.isEmpty()) {
                note = "The stash's dimension isn't known yet.";
                host.later(host::rebuild);
                return;
            }
            base = AreaEdit.start(d.box(), around, dim, feet == null ? 0 : feet[0], feet == null ? 64 : feet[1], feet == null ? 0 : feet[2]);
        }
        AreaEdit.Result r = op.apply(base);
        if (r.ok()) {
            drafts.set(key, source.server(), source.label(), r.draft(), now);
            note = "";
        } else {
            note = r.refused();
        }
        host.later(host::rebuild);
    }

    private void cornerAt(int which, int[] p) {
        if (p == null) return;
        edit(dr -> AreaEdit.corner(dr, which, p[0], p[1], p[2]));
    }

    private void expand(AreaEdit.Side side, int n) {
        edit(dr -> AreaEdit.expand(dr, side, n));
    }

    private void include() {
        int[] f = AreaWorld.feet();
        if (f == null) return;
        edit(dr -> AreaEdit.include(dr, f[0], f[1], f[2]));
    }

    private void useBots() {
        AreaModel.Data d = data;
        if (d == null) return;
        int[] box = d.bot().applied() != null ? d.bot().applied() : d.bot().derived();
        if (box == null) return;
        String dim = baseDimension(d);
        String why = AreaEdit.check(box, dim);
        if (why != null) {
            note = why;
        } else {
            drafts.set(key, source.server(), source.label(), AreaEdit.Draft.ofBox(box, dim), System.currentTimeMillis());
            note = "";
        }
        host.later(host::rebuild);
    }

    private void clear() {
        drafts.setClear(key, source.server(), source.label(), System.currentTimeMillis());
        note = "";
        host.later(host::rebuild);
    }

    private static boolean sameAsSent(AreaDraft.State st, int[] sent) {
        if (st.clear()) return sent == null;
        return sent != null && Arrays.equals(st.draft().box(), sent);
    }

    private void cancel() {
        drafts.clear();
        note = "";
        host.notice("Draft dropped.");
        host.later(host::rebuild);
    }

    private void save() {
        AreaModel.Data d = data;
        long now = System.currentTimeMillis();
        AreaDraft.State st = state(now);
        if (d == null || st == null || saving) return;
        int[] box = st.clear() ? null : st.draft().box();
        String dim = st.clear() ? null : st.draft().dimension();
        saving = true;
        note = "";
        int t = ++token;
        String saveKey = key;
        busy = false;
        source.save(box, dim, d.rev(), r -> {
            // A save that went through ends the draft whatever became of the window meanwhile
            // Only the draft that was sent ends: one changed while the save ran (the map) stays
            if (r.data() != null) {
                AreaDraft.State kept = drafts.forKey(saveKey, System.currentTimeMillis());
                if (kept != null && sameAsSent(kept, box)) drafts.clear();
            }
            if (t != token || host.closed()) return;
            saving = false;
            if (r.data() != null) {
                data = r.data();
                error = "";
                host.notice(source.server() ? "Saved. The bot takes it on its next report, between tasks." : "Queued. The bot applies it between tasks.");
                if (!r.error().isEmpty()) note = r.error();
            } else if (!r.error().isEmpty()) {
                note = r.error();
            }
            // the page may have moved on (409): read it again
            token++;
            nextGet = 0;
            host.later(host::rebuild);
        });
        host.later(host::rebuild);
    }

    // ---------- the map's part (AA5) ----------

    /** The map may edit the draft: a server stash, whose bot is the player the map shows, and an account that may change it. */
    public boolean mapEditable(String mapPlayer, String mapDimension) {
        if (data == null || !mapCandidate(mapPlayer) || mapDimension == null || mapDimension.isEmpty()) return false;
        String stash = baseDimension(data);
        return !stash.isEmpty() && CraftBounds.sameDimension(mapDimension, stash);
    }

    /** The map shows the bot of this stash and the account may change the area (the data is not needed yet: this decides whether to load it). */
    public boolean mapCandidate(String mapPlayer) {
        return source.server() && source.ready() && source.canWrite() && !source.botPlayer().isEmpty() && source.botPlayer().equalsIgnoreCase(mapPlayer == null ? "" : mapPlayer);
    }

    /** The stored area (min and max corners) and its dimension, or null. */
    public int[] mapStored() {
        return data == null ? null : data.box();
    }

    /** The draft's box, or null (none, or a clear). */
    public int[] mapDraft() {
        AreaDraft.State st = state(System.currentTimeMillis());
        return st == null || st.draft() == null ? null : st.draft().box();
    }

    /** A rectangle dragged on the map sets the draft's X and Z; the refusal text, or null when it was taken. */
    public String mapDrag(int x1, int z1, int x2, int z2) {
        if (data == null) return "The area isn't loaded yet.";
        if (saving) return "Saving...";
        note = "";
        edit(dr -> AreaEdit.mapDrag(dr, x1, z1, x2, z2));
        return note.isEmpty() ? null : note;
    }

    // ---------- widgets ----------

    private SmButton button(WFlow row, String text, String reason, String tip, Runnable action, Set<String> reasons) {
        if (reason == null) {
            SmButton b = (SmButton) row.add(theme.button(text)).widget();
            b.pill = true;
            b.tooltip = tip;
            b.action = action;
            return b;
        }
        WLabel l = row.add(Ui.muted(theme, text)).widget();
        l.tooltip = reason;
        reasons.add(reason);
        return null;
    }

    private static WFlow flow(WContainer parent, double width) {
        WFlow f = parent.add(new WFlow()).expandX().widget();
        f.maxWidth = width;
        f.gapX = 6;
        f.gapY = 4;
        return f;
    }

    private static String shown(int[] box, boolean hide) {
        return hide ? AreaEdit.sizeOf(box) : AreaModel.describe(box);
    }

    public void build(WContainer parent, GuiTheme theme) {
        this.theme = theme;
        this.root = parent;
        long now = System.currentTimeMillis();
        builtSig = sig(now);
        Ui.pageTitle(parent, theme, "area");
        double inner = PageRoom.width(parent, 16);
        if (!source.ready()) {
            parent.add(Ui.wrap(theme, source.notReady(), PageRoom.width(parent, 0))).expandX();
            return;
        }
        if (data == null) {
            boolean failed = !error.isEmpty();
            parent.add(Ui.wrap(theme, failed ? error : "Loading...", PageRoom.width(parent, 0))).expandX().widget().color(failed ? Ui.err(theme) : Ui.dim(theme));
            return;
        }
        AreaModel.Data d = data;
        boolean hide = source.hide();

        SmCard stored = Ui.card(parent, "Stash area");
        stored.add(Ui.wrap(theme, AreaModel.sourceLine(d, hide), inner)).expandX();
        stored.add(Ui.wrap(theme, d.box() == null ? "Stored area: none." : "Stored area: " + shown(d.box(), hide) + (hide || d.dimension().isEmpty() ? "" : " in " + d.dimension()) + ".", inner)).expandX();
        if (d.box() == null && d.bot().derived() != null) {
            stored.add(Ui.muted(theme, "The bot's own box (around the containers it has read): " + shown(d.bot().derived(), hide) + ".", inner)).expandX();
        }
        WLabel botLine = stored.add(Ui.wrap(theme, AreaModel.botLine(d), inner)).expandX().widget();
        if (d.bot().pendingWhy() != null) botLine.color(Ui.warn(theme));
        String impact = AreaModel.impactLine(d);
        if (!impact.isEmpty() && source.server()) stored.add(Ui.muted(theme, impact, inner)).expandX();
        stored.add(Ui.muted(theme, "Largest area " + d.limits().maxW() + "x" + d.limits().maxH() + "x" + d.limits().maxD() + ", y " + d.limits().yMin() + ".." + d.limits().yMax() + ".", inner)).expandX();

        SmCard card = Ui.card(parent, "Edit");
        AreaDraft.State st = state(now);
        AreaDraft.State other = st == null ? drafts.get(now) : null;
        if (other != null) {
            card.add(Ui.wrap(theme, "A draft for another stash (" + (hide ? "hidden" : other.label()) + ") is open. Starting an edit here replaces it.", inner)).expandX().widget().color(Ui.warn(theme));
        }
        if (st == null) {
            card.add(Ui.muted(theme, "No draft. Use a button below to start one: nothing is sent or applied until you press Save.", inner)).expandX();
        } else if (st.clear()) {
            card.add(Ui.wrap(theme, "Draft: remove the area (unsaved).", inner)).expandX().widget().color(Ui.warn(theme));
        } else {
            card.add(Ui.wrap(theme, "Draft: " + (hide ? st.draft().size() : AreaModel.describe(st.draft().box())) + " (unsaved)" + (draftChanged(d, st) ? "." : ", the same as the stored area."), inner)).expandX()
                .widget().color(Ui.warn(theme));
        }

        Set<String> reasons = new LinkedHashSet<>();
        String position = positionReason(d);
        String lookReason = AreaModel.lookReason(position, look != null);
        String off = !source.canWrite() ? "Your account may not change the stash area." : saving ? "Saving..." : null;

        WFlow corners = flow(card, inner);
        button(corners, "Corner 1 at my feet", off != null ? off : position, "Sets corner 1 to the block you stand in.", () -> cornerAt(1, AreaWorld.feet()), reasons);
        button(corners, "Corner 2 at my feet", off != null ? off : position, "Sets corner 2 to the block you stand in.", () -> cornerAt(2, AreaWorld.feet()), reasons);
        button(corners, "Corner 1 at the block I look at", off != null ? off : lookReason, "Sets corner 1 to the block that was in your crosshair when the window was opened.", () -> cornerAt(1, look), reasons);
        button(corners, "Corner 2 at the block I look at", off != null ? off : lookReason, "Sets corner 2 to the block that was in your crosshair when the window was opened.", () -> cornerAt(2, look), reasons);

        WFlow grow = flow(card, inner);
        grow.add(Ui.muted(theme, "Expand by"));
        WTextBox n = grow.add(theme.textBox(nText, "")).minWidth(theme.scale(44)).widget();
        n.action = () -> nText = n.get();
        Integer nv = parseN(nText);
        String nReason = off != null ? off : nv == null ? "N must be a whole number from -" + AreaEdit.MAX_N + " to " + AreaEdit.MAX_N + " (negative shrinks)." : null;
        int amount = nv == null ? 0 : nv;
        button(grow, "West (-X)", nReason, "Moves the west side by N blocks.", () -> expand(AreaEdit.Side.NEG_X, amount), reasons);
        button(grow, "East (+X)", nReason, "Moves the east side by N blocks.", () -> expand(AreaEdit.Side.POS_X, amount), reasons);
        button(grow, "North (-Z)", nReason, "Moves the north side by N blocks.", () -> expand(AreaEdit.Side.NEG_Z, amount), reasons);
        button(grow, "South (+Z)", nReason, "Moves the south side by N blocks.", () -> expand(AreaEdit.Side.POS_Z, amount), reasons);
        button(grow, "Down", nReason, "Moves the bottom by N blocks.", () -> expand(AreaEdit.Side.DOWN, amount), reasons);
        button(grow, "Up", nReason, "Moves the top by N blocks.", () -> expand(AreaEdit.Side.UP, amount), reasons);
        button(grow, "All sides", nReason, "Moves all six sides by N blocks.", () -> expand(AreaEdit.Side.ALL, amount), reasons);

        WFlow more = flow(card, inner);
        button(more, "Include where I stand", off != null ? off : position, "Grows the box just enough to hold your feet block and the one above it.", this::include, reasons);
        boolean haveBots = d.bot().applied() != null || d.bot().derived() != null;
        button(more, "Use the bot's current area", off != null ? off : haveBots ? null : "The bot has not reported an area or a box of its own yet.",
            "Starts the draft from the area the bot works in now.", this::useBots, reasons);
        button(more, "Clear", off != null ? off : d.box() == null ? "No area is stored, nothing to clear." : null, "Draft: remove the stored area. Press Save to do it.", this::clear, reasons);

        AreaDraft.State now2 = st;
        String invalid = now2 == null || now2.clear() ? null : AreaEdit.check(now2.draft().box(), now2.draft().dimension());
        String saveReason = AreaModel.saveReason(source.canWrite(), saving, now2 != null, draftChanged(d, now2), invalid);
        WFlow end = flow(card, inner);
        SmButton saveBtn = button(end, "Save", saveReason, source.server() ? "Sends the draft to the Stash Manager server; the bot takes it between tasks." : "Applies the draft to this stash between tasks.", this::save, reasons);
        if (saveBtn != null) saveBtn.primary = true;
        button(end, "Cancel", st == null ? "There is no draft." : saving ? "Saving..." : null, "Drops the draft.", this::cancel, reasons);

        if (!note.isEmpty()) card.add(Ui.wrap(theme, note, inner)).expandX().widget().color(Ui.err(theme));
        for (String why : reasons) card.add(Ui.muted(theme, "Off: " + why, inner)).expandX();
        card.add(Ui.muted(theme, "The draft is drawn in the world while it exists. It is dropped on Cancel, when you leave the world, or after 15 minutes. The last Save wins.", inner)).expandX();
    }

    private static Integer parseN(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty() || t.length() > 5) return null;
        try {
            int v = Integer.parseInt(t);
            return Math.abs(v) <= AreaEdit.MAX_N ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
