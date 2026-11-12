package com.porter.qwerte.messenger;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The Messenger window: a list on the left (Global first, then the conversations), the history and an input line on the
 * right, an emoji picker and a contact picker. The same code draws it twice: as a HUD overlay while the game is played
 * ({@code focused} false: nothing can be clicked or typed into) and inside {@link MessengerScreen} ({@code focused}
 * true: it takes the mouse and keyboard). All sizes are GUI pixels. Client thread only.
 *
 * <p>{@code selected == null} means Global, the public chat.
 */
public final class MessengerUi {
    /** What the window needs from the module. */
    public interface Host {
        MessengerCore core();

        /** The saved place, or null for the default one. */
        Geometry geometry();

        void geometryChanged(Geometry geometry);

        /** How opaque the window's panels are, 0.2 to 1. */
        float opacity();

        boolean isFriend(String name);

        /** Changes whenever the friends list does (a count or hash); looked at once a second. */
        int friendsStamp();

        /** Shown in the HUD-style window where the input line would be, like "Press RSHIFT to type". */
        String hint();

        /** The names of the Meteor friends. */
        List<String> friendNames();

        /** The names on the tab list now. */
        List<String> onlineNames();

        /** The player's own name, or null. */
        String selfName();

        /** Draw the public chat in the always-on overlay (otherwise it shows whispers there and Global only when focused). */
        boolean overlayGlobal();

        /** Meteor's command prefix (a chat text starting with it runs a Meteor command), or null. */
        String meteorPrefix();

        /** A theme colour, 0xAARRGGBB. */
        int color(ThemeColor color);

        /** The text scale follows the window size. */
        boolean autoFontScale();

        /** The text scale used when {@link #autoFontScale} is off. */
        float fontScale();

        /** How opaque console lines (system messages, notices) are drawn in Global, 0 to 1. Player chat is always full. */
        float consoleOpacity();

        /** The Console entry (console lines apart from Global) is on. */
        boolean separateConsole();

        /** Draw console lines in italics. */
        boolean consoleItalic();

        /** The always-on overlay also shows the Console lines, dimmed and interleaved by time with Global (separate-console on). */
        boolean overlayShowsConsole();

        /** Draw the sender's head in front of a public chat line. */
        boolean showHeads();

        /** list-side: where the conversation list goes. */
        ListSideMode listSide();

        /** corner-radius, 0 to 8 GUI pixels (0: plain rectangles). */
        int cornerRadius();

        /** snap-to-edges. */
        boolean snapToEdges();

        /** snap-distance, in GUI pixels. */
        int snapDistance();

        /** The unread badges of the Global row, the Console row and the conversation rows are drawn. They only hide the drawing. */
        boolean badgeGlobal();

        boolean badgeConsole();

        boolean badgeDms();

        /** The user opened Global ({@link LastOpen#GLOBAL}), the Console ({@link LastOpen#CONSOLE}) or a conversation (its key). */
        void selectionChanged(String what);
    }

    private record Rect(int x1, int y1, int x2, int y2) {
        boolean contains(double x, double y) {
            return x >= x1 && x < x2 && y >= y1 && y < y2;
        }

        int w() {
            return x2 - x1;
        }

        int h() {
            return y2 - y1;
        }
    }

    private record Block(Message message, List<TextLayout.Line> lines, int height, boolean console, String head) {
    }

    private enum Drag {
        NONE, MOVE, RESIZE
    }

    private enum Confirm {
        NONE, DELETE, CLEAR
    }

    private static final int TITLE_H = 13;
    private static final int ROW_H = 12;
    private static final int LINE_H = 11;
    private static final int INPUT_H = 16;
    private static final int GRIP = 9;
    private static final int PAD = 3;
    private static final int GAP = 3;
    private static final int GLOBAL_GAP = 1;
    private static final int CELL = 12;
    private static final int PICK_ROWS = 4;
    private static final int EMOJI_DRAW = 9;
    private static final int EMOJI_ADVANCE = 10;
    private static final String GLOBAL_DRAFT = "#global";
    /** The selection key of the Console entry; no player name can start with a hash, so it never meets a conversation. */
    static final String CONSOLE_KEY = "#console";
    private static final long WRAP_SETTLE_MS = 100;
    private static final int HEAD = 8;
    private static final int HEAD_ADVANCE = 10;



    private final Host host;
    private final EmojiSet emoji = EmojiSet.bundled();
    private final EmojiTextures textures = new EmojiTextures();
    private EditBox input;
    private EditBox search;

    private boolean focused;
    /** The key of the open conversation; null is Global. */
    private String selected;
    private int listScroll;
    private int histScroll;
    private int pickScroll;
    private boolean pickerOpen;
    private final Map<String, String> drafts = new HashMap<>();
    private String status;
    private long statusUntil;

    // The contact picker (New)
    private boolean contactOpen;
    private int contactScroll;
    private int contactSel;
    private String contactsQuery;
    private long contactsAt;
    private List<ContactPicker.Entry> contacts = List.of();
    /** The entries as last drawn: a click or Enter picks from what the user saw, not from a list refreshed since. */
    private List<ContactPicker.Entry> drawn = List.of();

    // Delete / clear, asked first
    private Confirm confirm = Confirm.NONE;
    private String confirmName;

    private Geometry geo;
    /** The screen size (GUI pixels) at the last frame; a change (also from the GUI scale) puts the list side up for a new look. */
    private int lastSw = -1;
    private int lastSh = -1;
    private ListSideMode lastSideMode;
    /** The rounded corners: logical pixels of the window, set by {@link #layout}. */
    private int rWin;
    private int rIn1;
    private int rIn2;
    private GuiGraphics roundG;
    private int roundColor;
    private final RoundRect.Sink roundSink = (x1, y1, x2, y2) -> roundG.fill(x1, y1, x2, y2, roundColor);
    /** Cuts every span to the window's rounded shape before it is drawn, so nothing paints over the window's corners. */
    private final RoundRect.Sink clipSink = (x1, y1, x2, y2) -> RoundRect.spansIn(this.winRect.x1, this.winRect.y1, this.winRect.x2, this.winRect.y2, this.rWin, x1, y1, x2, y2, roundSink);
    /** The right edge of the header's left part: the leftmost of the buttons and the list toggle, or the window's edge. */
    private int headRight;
    /**
     * The scale the window is drawn with (see {@link MessengerScale}). Everything inside is laid out in logical pixels:
     * the window's real size divided by this, drawn through a pose scale about the window's corner. The geometry
     * ({@link #geo}), the screen size and the dragging stay in real GUI pixels.
     */
    private float scale = 1f;
    private Drag drag = Drag.NONE;
    private int dragDx;
    private int dragDy;

    // Where things were drawn last, for the mouse
    private Rect winRect = new Rect(0, 0, 0, 0);
    private Rect titleRect = winRect;
    private Rect btnNew = winRect;
    private Rect btnEmoji = winRect;
    private Rect btnDelete = winRect;
    private Rect listRect = winRect;
    private Rect histRect = winRect;
    private Rect pickRect = winRect;
    private Rect inputRect = winRect;
    /** The rounded fill behind the input box (corner-radius above 1), else empty. */
    private Rect inputBox = winRect;
    private Rect sendRect = winRect;
    private Rect gripRect = winRect;
    private Rect btnList = winRect;
    /** The title bar as the mouse sees it: at least 8 real pixels tall, however small the text is. */
    private Rect titleHit = winRect;
    private static final Rect NONE = new Rect(0, 0, 0, 0);
    // The last plan, kept while the size and the flags are the same
    private int planW = -1;
    private int planH = -1;
    private boolean planFocus;
    private boolean planListView;
    /** What fits at the current size (see {@link MessengerLayout}). */
    private MessengerLayout.Plan plan = MessengerLayout.plan(MessengerScale.DEFAULT_W, MessengerScale.DEFAULT_H, false, false);
    /** The list is collapsed (narrow window) and the user switched to it: it shows instead of the messages. */
    private boolean listView;
    private Rect searchRect = winRect;
    private Rect contactRect = winRect;
    private Rect dlgRect = winRect;
    private Rect dlgYes = winRect;
    private Rect dlgNo = winRect;
    private int pickCols = 1;
    private List<Conversation> shownList = List.of();
    /** The Console entry is in the list (separate-console on). */
    private boolean consoleRow;
    /** The Console is what the history area shows now. */
    private boolean consoleShown;
    /** The window is focused as this frame is drawn (the overlay is not). */
    private boolean drawFocus;
    // Global and Console lines in one list by time, for the overlay (one list object, changed in place so the wrapped blocks are kept)
    private final List<Message> merged = new ArrayList<>();
    private ConversationStore mergedStore;
    private int mergedGlobalSize = -1;
    private int mergedConsoleSize = -1;
    private Message mergedGlobalLast;
    private Message mergedConsoleLast;
    // The italic form of each wrapped piece, made once (not every frame)
    private final Map<TextLayout.Piece, Component> italics = new IdentityHashMap<>();

    // Wrapped history of the open list, remade only when it changes (a block is kept while its message is)
    private List<Block> blocks = List.of();
    private List<Message> blocksSrc;
    private int blocksSize;
    private Message blocksLast;
    private int blocksWidth;
    private float blocksScale;
    private boolean blocksHeads;
    private Map<Message, Block> blockCache = new IdentityHashMap<>();
    private List<Message> scrollSrc;
    private int scrollTotal;
    private int pendingWidth = -1;
    private long pendingSince;

    // The sorted conversation list, kept until the store changes or the friends list does
    private ConversationStore orderedStore;
    private int orderedVersion;
    private int orderedFriends;
    private long friendsCheckedAt;
    // Online/offline dots: the tab list names, refreshed about every 10 ticks (500 ms)
    private static final int DOT = 5;
    private static final int DOT_GAP = 3;
    private final OnlineStatus onlineStatus = new OnlineStatus();
    /** Lowercase of each name shown, so a lookup per frame allocates nothing. */
    private final java.util.HashMap<String, String> lowerNames = new java.util.HashMap<>();
    private long onlineAt;
    private String dotTip;
    private int dotTipX;
    private int dotTipY;
    private List<Conversation> ordered = List.of();

    public MessengerUi(Host host) {
        this.host = host;
    }

    // ---------- state ----------

    public boolean focused() {
        return focused;
    }

    /** Called when the screen opens. */
    public void focus() {
        focused = true;
        reevaluateSide(); // the window appears in front of the user
        ensureInput();
        input.setFocused(true);
        Conversation c = selectedConversation();
        if (c != null) host.core().store().markRead(c.name());
        if (consoleSelected()) host.core().store().markConsoleRead();
    }

    /** Called when the screen closes. */
    public void unfocus() {
        focused = false;
        listView = false;
        drag = Drag.NONE;
        contactOpen = false;
        confirm = Confirm.NONE;
        if (input != null) input.setFocused(false);
        if (search != null) search.setFocused(false);
        if (geo != null) host.geometryChanged(geo);
    }

    /** Closes the focused window (back to the HUD-style drawing). */
    public void close() {
        if (mc.screen instanceof MessengerScreen) mc.setScreen(null);
    }

    /** Frees the emoji textures (module off). */
    public void release() {
        textures.close();
    }

    /** Shows a conversation. A new message from them then counts as read while the window is focused. */
    public void select(String name) {
        setSelected(Conversation.key(name));
        if (focused) host.core().store().markRead(name); // unfocused, a new message keeps its unread badge
    }

    /** Shows the public chat. */
    public void selectGlobal() {
        setSelected(null);
    }

    /** Shows Global without remembering it as Last open: the chat key does this, it is not a choice made in the window. */
    public void selectGlobalQuiet() {
        setSelected(null, false);
    }

    /**
     * Shows what the focus key was asked to open (focus-opens). A conversation that is not there is made empty, like New
     * does, only for a target that says so; otherwise it falls back to Global.
     */
    public void show(FocusOpens.Target target) {
        switch (target.kind()) {
            case GLOBAL -> selectGlobal();
            case CONSOLE -> {
                if (host.separateConsole()) selectConsole();
                else selectGlobal();
            }
            case DM -> {
                ConversationStore store = host.core().store();
                Conversation c = store.get(target.name());
                if (c == null && target.create()) c = store.open(target.name(), System.currentTimeMillis());
                if (c != null) select(c.name());
                else selectGlobal();
            }
            default -> {
                // KEEP: whatever is open stays
            }
        }
    }

    private void setSelected(String key) {
        setSelected(key, true);
    }

    /** {@code record}: the user chose this, so the focus key's Last open remembers it. */
    private void setSelected(String key, boolean record) {
        if (input != null) drafts.put(draftKey(selected), input.getValue());
        selected = key;
        if (record) host.selectionChanged(key == null ? LastOpen.GLOBAL : CONSOLE_KEY.equals(key) ? LastOpen.CONSOLE : key);
        histScroll = 0;
        blocksSrc = null;
        scrollSrc = null;
        if (input != null) {
            input.setValue(drafts.getOrDefault(draftKey(selected), ""));
            updateHint();
        }
    }

    private static String draftKey(String selected) {
        return selected == null ? GLOBAL_DRAFT : selected;
    }

    /** The Console entry is open (and exists). */
    private boolean consoleSelected() {
        return CONSOLE_KEY.equals(selected) && host.separateConsole();
    }

    /** Shows the Console. */
    public void selectConsole() {
        setSelected(CONSOLE_KEY);
        if (focused) host.core().store().markConsoleRead();
    }

    /** The Console is open in front of the user (a new console line counts as read). */
    public boolean isReadingConsole() {
        return focused && consoleSelected();
    }

    private void updateHint() {
        String hint = consoleSelected() ? "The Console is read only" : selected == null ? "Say it in public chat (/ for a command)" : "Message";
        input.setHint(Component.literal(hint).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }

    /** The saved place may have changed (settings reloaded): read it again. */
    public void resetGeometry() {
        geo = null;
    }

    /** Forgets everything the window shows about a conversation (after the history was cleared, or on another server). */
    public void reset() {
        selected = null;
        blocks = List.of();
        blocksSrc = null;
        scrollSrc = null;
        blockCache.clear();
        orderedStore = null;
        histScroll = 0;
        listScroll = 0;
        drafts.clear();
        contactOpen = false;
        confirm = Confirm.NONE;
        if (input != null) {
            input.setValue("");
            updateHint();
        }
    }

    /** The focused window shows this player's conversation (so a new message from them counts as read). */
    public boolean isReading(String name) {
        return focused && selected != null && selected.equals(Conversation.key(name));
    }

    public boolean isOver(double x, double y, int screenW, int screenH) {
        return current(screenW, screenH).contains(x, y);
    }

    private Conversation selectedConversation() {
        return selected == null ? null : host.core().store().get(selected);
    }

    private void ensureInput() {
        if (input != null) return;
        input = new EditBox(mc.font, 0, 0, 100, 14, Component.literal("Message"));
        input.setMaxLength(256);
        input.setValue(drafts.getOrDefault(draftKey(selected), ""));
        updateHint();
    }

    private void ensureSearch() {
        if (search != null) return;
        search = new EditBox(mc.font, 0, 0, 100, 14, Component.literal("Search"));
        search.setMaxLength(32);
        search.setHint(Component.literal("Search, or type a player name").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }

    private void setStatus(String s) {
        status = s;
        statusUntil = System.currentTimeMillis() + 4500;
    }

    private Geometry current(int sw, int sh) {
        boolean fresh = false;
        if (geo == null) {
            Geometry saved = host.geometry();
            geo = saved != null ? saved : Geometry.initial(sw, sh);
            fresh = true;
        }
        // Snapped edges stay on their screen edge when the size changes; with snapping off it is the plain clamp
        geo = host.snapToEdges() ? geo.fit(sw, sh) : geo.clamp(sw, sh);
        boolean screenChanged = sw != lastSw || sh != lastSh;
        ListSideMode mode = host.listSide();
        boolean modeChanged = mode != lastSideMode;
        lastSw = sw;
        lastSh = sh;
        lastSideMode = mode;
        // Auto looks again when the window appears, when the screen size or GUI scale changes and when the setting does; never mid-drag
        if ((fresh || screenChanged || modeChanged) && drag == Drag.NONE) applySide(sw);
        scale = MessengerScale.effective(host.autoFontScale(), host.fontScale(), geo.w(), geo.h());
        return geo;
    }

    /** Auto: picks the side from where the window is (with the hysteresis of {@link ListSideMode}); the result is saved with the geometry. */
    private void applySide(int sw) {
        if (geo == null) return;
        ListSide next = host.listSide().resolve(geo.side(), geo.x(), geo.w(), sw);
        if (next != geo.side()) {
            geo = geo.withSide(next);
            host.geometryChanged(geo);
        }
    }

    private void reevaluateSide() {
        if (geo != null && lastSw > 0 && drag == Drag.NONE) applySide(lastSw);
    }

    /** The side the list is on now: the setting, or for Auto the one that was worked out and saved. */
    private ListSide listSide() {
        ListSideMode mode = host.listSide();
        if (mode == ListSideMode.LEFT) return ListSide.LEFT;
        if (mode == ListSideMode.RIGHT) return ListSide.RIGHT;
        return geo == null ? ListSide.LEFT : geo.side();
    }

    /** A real GUI x as a logical one (the window's corner stays where it is). */
    private double lx(double x) {
        return geo == null ? x : geo.x() + (x - geo.x()) / scale;
    }

    private double ly(double y) {
        return geo == null ? y : geo.y() + (y - geo.y()) / scale;
    }

    /** A theme colour as it is (its own alpha). */
    private int col(ThemeColor c) {
        return host.color(c);
    }

    /** A background colour: its own alpha times the window opacity (at least 20%). */
    private int bg(ThemeColor c) {
        float o = Math.max(0.2f, Math.min(1f, host.opacity()));
        return MessengerTheme.scaleAlpha(host.color(c), o);
    }

    // ---------- rounded corners ----------

    /** A radius in GUI pixels as logical pixels of the scaled window (0 stays 0, anything else at least 1). */
    private int logicalRadius(int guiPx) {
        return guiPx <= 0 ? 0 : Math.max(1, Math.round(guiPx / scale));
    }

    /**
     * A filled rectangle with the given radius at each corner (top left, top right, bottom right, bottom left). With all
     * radii 0 it is one plain {@code fill}, exactly as before rounding existed; otherwise the spans of {@link RoundRect}.
     */
    private void fillRound(GuiGraphics g, int x1, int y1, int x2, int y2, int tl, int tr, int br, int bl, int color) {
        if (rWin == 0) { // no rounding anywhere: one plain fill
            g.fill(x1, y1, x2, y2, color);
            return;
        }
        roundG = g;
        roundColor = color;
        RoundRect.spans(x1, y1, x2, y2, tl, tr, br, bl, clipSink); // also a square-cornered shape is cut to the window's
        roundG = null;
    }

    private void rounded(GuiGraphics g, int x1, int y1, int x2, int y2, int radius, int color) {
        fillRound(g, x1, y1, x2, y2, radius, radius, radius, radius, color);
    }

    /**
     * A panel of the window (the window itself, the header, the list, ...): only the part inside the window's rounded
     * shape is filled, so a panel never paints over the window's corners. With radius 0 it is one plain {@code fill}.
     */
    private void pane(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        if (rWin == 0) {
            g.fill(x1, y1, x2, y2, color);
            return;
        }
        roundG = g;
        roundColor = color;
        RoundRect.spansIn(winRect.x1, winRect.y1, winRect.x2, winRect.y2, rWin, x1, y1, x2, y2, roundSink);
        roundG = null;
    }

    // ---------- drawing ----------

    /** Draws the window at its text scale. {@code mx}, {@code my} are real GUI pixels (negative: no mouse). */
    public void render(GuiGraphics g, int sw, int sh, int mx, int my, boolean focus) {
        if (sw <= 0 || sh <= 0) return;
        Geometry real = current(sw, sh);
        int lmx = mx < 0 ? mx : (int) Math.floor(lx(mx));
        int lmy = my < 0 ? my : (int) Math.floor(ly(my));
        PoseScale.push(g, real.x(), real.y(), scale);
        try {
            renderLogical(g, sw, sh, lmx, lmy, focus);
        } finally {
            PoseScale.pop(g);
        }
    }

    private void renderLogical(GuiGraphics g, int sw, int sh, int mx, int my, boolean focus) {
        Font font = mc.font;
        Geometry w = current(sw, sh);
        MessengerCore core = host.core();
        ConversationStore store = core.store();
        textures.newFrame();
        long nowMs = System.currentTimeMillis();
        if (nowMs - onlineAt > 500) {
            onlineAt = nowMs;
            onlineStatus.update(host.onlineNames());
        }
        dotTip = null;
        if (nowMs - friendsCheckedAt > 1000) {
            friendsCheckedAt = nowMs;
            int stamp = host.friendsStamp();
            if (stamp != orderedFriends) {
                orderedFriends = stamp;
                orderedStore = null;
            }
        }
        if (orderedStore != store || orderedVersion != store.version()) {
            orderedStore = store;
            orderedVersion = store.version();
            ordered = store.ordered(host::isFriend);
        }
        List<Conversation> list = ordered;
        shownList = list;
        consoleRow = host.separateConsole();
        drawFocus = focus;
        if (CONSOLE_KEY.equals(selected) && !consoleRow) setSelected(null, false); // separate-console was turned off
        if (selected != null && !CONSOLE_KEY.equals(selected) && store.get(selected) == null) { // it was deleted (or this is another server)
            String dead = selected;
            setSelected(null, false);
            drafts.remove(dead);
        }
        Conversation sel = selectedConversation();
        if (focus && sel != null) store.markRead(sel.name());
        // The always-on overlay keeps to whispers unless overlay-global is on: a busy public chat is not shown over the game
        Conversation shown = sel;
        boolean globalShown = sel == null; // Global, or the Console (drawn like it)
        if (!focus && sel == null && !host.overlayGlobal()) {
            shown = list.isEmpty() ? null : list.get(0);
            globalShown = false;
        }
        // The overlay (not focused) never shows the Console alone: it shows Global, with the console lines mixed in when overlay-shows-console is on
        consoleShown = focus && globalShown && consoleSelected();
        if (focus && consoleShown) store.markConsoleRead();

        layout(w, focus, font);

        // Frame, title, panes
        pane(g, winRect.x1, winRect.y1, winRect.x2, winRect.y2, bg(ThemeColor.WINDOW));
        pane(g, titleRect.x1, titleRect.y1, titleRect.x2, titleRect.y2, bg(ThemeColor.HEADER));
        // The title: the text, cut with "..." (without "Messenger - " first) to leave room for the unread badge at its right end
        int total = store.visibleUnread(host.badgeGlobal(), host.badgeConsole(), host.badgeDms());
        String subject = shown != null ? shown.name() : consoleShown ? "Console" : globalShown ? "Global" : "";
        if (globalShown && !consoleShown && core.globalQueued()) subject += " (sending...)";
        String prefix = TitleFit.PREFIX;
        if (!plan.history() && status != null && System.currentTimeMillis() <= statusUntil) { // the messages (where it is shown) are hidden
            subject = status;
            prefix = "";
        }
        int titleX = btnList.w() > 0 && btnList.x1 < titleRect.x1 + titleRect.w() / 2 ? btnList.x2 + 3 : titleRect.x1 + 4;
        int areaRight = headRight - 4;
        String count = total > 99 ? "99+" : String.valueOf(total);
        int badgeW = total > 0 ? font.width(count) + 6 : 0;
        if (areaRight - titleX > 6 && titleRect.h() >= 9) {
            TitleFit.Result fit = TitleFit.fit(font::width, prefix, subject, badgeW, areaRight - titleX);
            if (!fit.text().isEmpty()) g.drawString(font, fit.text(), titleX, titleRect.y1 + 3, col(ThemeColor.TEXT), true);
            if (fit.badge()) {
                int bx2 = areaRight;
                int by1 = titleRect.y1 + 2;
                int by2 = titleRect.y2 - 2;
                rounded(g, bx2 - badgeW, by1, bx2, by2, rIn2, col(ThemeColor.BADGE));
                g.drawString(font, count, bx2 - badgeW + 3, titleRect.y1 + 3, MessengerTheme.textOn(col(ThemeColor.BADGE)), false);
            }
        }
        if (btnList.w() > 0) button(g, font, btnList, listView ? "<" : "=", mx, my, listView);
        if (focus && btnNew.w() > 0) {
            button(g, font, btnNew, contactOpen ? "Cancel" : "New", mx, my, contactOpen);
            button(g, font, btnEmoji, "Emoji", mx, my, pickerOpen);
            button(g, font, btnDelete, sel == null ? "Clear" : "Delete", mx, my, confirm != Confirm.NONE);
        }

        if (listRect.w() > 0) {
            pane(g, listRect.x1, listRect.y1, listRect.x2, listRect.y2, bg(ThemeColor.LEFT_PANE));
            drawList(g, font, list, focus, mx, my, shown == null ? null : shown.key(), globalShown && !consoleShown);
        }
        if (plan.history()) {
            if (focus && contactOpen) drawContacts(g, font, mx, my);
            else drawHistory(g, font, shown, globalShown, store);
            drawStatus(g, font);
        }

        if (focus) {
            if (pickerOpen && pickRect.w() > 0) drawPicker(g, mx, my);
            if (plan.input()) drawInput(g, font, mx, my);
            if (confirm != Confirm.NONE) drawConfirm(g, font, mx, my);
            // grip
            // the grip is at least 8 real pixels; its dots grow with it
            int k = Math.max(1, Math.round(gripRect.w() / (float) GRIP));
            int gx = gripRect.x2 - rWin / 2; // the dots keep inside a rounded corner
            int gy = gripRect.y2 - rWin / 2;
            for (int i = 0; i < 3; i++) {
                int o = (2 + i * 3) * k;
                g.fill(gx - o - k, gy - 2 * k, gx - o, gy - k, col(ThemeColor.DIM));
                g.fill(gx - 2 * k, gy - o - k, gx - k, gy - o, col(ThemeColor.DIM));
            }
        } else if (plan.input()) {
            rounded(g, inputRect.x1, inputRect.y1, inputRect.x2, inputRect.y2, rIn1, bg(ThemeColor.INPUT));
            g.drawString(font, font.plainSubstrByWidth(host.hint(), Math.max(10, inputRect.w() - 8)), inputRect.x1 + 4, inputRect.y1 + 4, col(ThemeColor.DIM), false);
        }
        if (dotTip != null) drawDotTip(g, font, (int) Math.floor(lx(0)), (int) Math.floor(ly(0)), (int) Math.ceil(lx(sw)), (int) Math.ceil(ly(sh))); // last, so nothing covers it
    }

    private void layout(Geometry w, boolean focus, Font font) {
        winRect = new Rect(w.x(), w.y(), w.x() + MessengerScale.logical(w.w(), scale), w.y() + MessengerScale.logical(w.h(), scale));
        titleRect = new Rect(winRect.x1, winRect.y1, winRect.x2, winRect.y1 + Math.min(TITLE_H, winRect.h()));
        // What fits at this size. The New list needs the messages' area, so it shows them whatever the size.
        boolean planFocus2 = contactOpen && focus ? false : focus;
        boolean planList2 = contactOpen && focus ? false : listView;
        if (winRect.w() != planW || winRect.h() != planH || planFocus2 != planFocus || planList2 != planListView) {
            planW = winRect.w();
            planH = winRect.h();
            planFocus = planFocus2;
            planListView = planList2;
            plan = MessengerLayout.plan(planW, planH, planFocus, planListView);
        }
        Rect none = NONE;
        if (plan.sidebar()) listView = false; // the list is beside the messages again: forget the switch
        if (!plan.history() && confirm != Confirm.NONE) {
            confirm = Confirm.NONE;
            setStatus("Cancelled: window too small");
        }
        // Rounded corners (logical pixels). The panels are cut to the window's shape, so the radius is the window's own.
        int guiR = Math.max(0, Math.min(8, host.cornerRadius()));
        rWin = logicalRadius(guiR);
        rIn1 = logicalRadius(guiR - 1);
        rIn2 = logicalRadius(guiR - 2);
        boolean listRight = listSide() == ListSide.RIGHT;
        // Header: the list toggle is on the list's side (the left end, or the right end with the buttons to its left); the buttons are on the right
        int by1 = titleRect.y1 + 2;
        int by2 = titleRect.y2 - 2;
        int toggleW = (by2 - by1) + 4;
        if (focus && plan.toggle() && !contactOpen) {
            btnList = listRight ? new Rect(titleRect.x2 - 2 - toggleW, by1, titleRect.x2 - 2, by2) : new Rect(titleRect.x1 + 2, by1, titleRect.x1 + 2 + toggleW, by2);
        } else {
            btnList = none;
        }
        if (focus && plan.buttons()) {
            int bx = listRight && btnList.w() > 0 ? btnList.x1 - 2 : winRect.x2 - 3;
            int dw = font.width("Delete") + 8;
            btnDelete = new Rect(bx - dw, by1, bx, by2);
            bx -= dw + 2;
            int ew = font.width("Emoji") + 8;
            btnEmoji = new Rect(bx - ew, by1, bx, by2);
            bx -= ew + 2;
            int nw = font.width("Cancel") + 8;
            btnNew = new Rect(bx - nw, by1, bx, by2);
        } else {
            btnDelete = none;
            btnEmoji = none;
            btnNew = none;
        }

        // The list: beside the messages (on the left or the right), or (collapsed and asked for) instead of them
        int listW = Math.max(60, Math.min(110, winRect.w() * 3 / 10));
        if (plan.sidebar()) listRect = listRight ? new Rect(winRect.x2 - listW, titleRect.y2, winRect.x2, winRect.y2) : new Rect(winRect.x1, titleRect.y2, winRect.x1 + listW, winRect.y2);
        else if (plan.listView()) listRect = new Rect(winRect.x1, titleRect.y2, winRect.x2, winRect.y2);
        else listRect = none;
        int left = plan.sidebar() && !listRight ? listRect.x2 + 1 : winRect.x1; // where the messages and the input start
        int right = plan.sidebar() && listRight ? listRect.x1 - 1 : winRect.x2; // and where they end
        int inputH = plan.input() ? INPUT_H : 0;
        if (plan.input()) inputRect = new Rect(left + PAD, winRect.y2 - INPUT_H, right - PAD, winRect.y2 - 1);
        else inputRect = none;
        int sendW = font.width("Send") + 10;
        sendRect = !plan.input() ? none : focus ? new Rect(inputRect.x2 - sendW, inputRect.y1, inputRect.x2, inputRect.y2) : inputRect;
        pickCols = Math.max(1, (right - left - 2 * PAD - 6) / CELL);
        int pickH = focus && pickerOpen && plan.input() && plan.history() ? PICK_ROWS * CELL + 4 : 0;
        pickRect = pickH > 0 ? new Rect(left, winRect.y2 - INPUT_H - pickH, right, winRect.y2 - INPUT_H) : none;
        histRect = plan.history() ? new Rect(left, titleRect.y2, right, winRect.y2 - inputH - pickH) : none;
        int gripSize = MessengerLayout.hitSize(GRIP, scale, Math.min(winRect.w(), winRect.h()));
        gripRect = new Rect(winRect.x2 - gripSize, winRect.y2 - gripSize, winRect.x2, winRect.y2);
        // Above scale 0.4 only the part of the header left of the buttons starts a drag; at tiny scales all of it, and taller
        headRight = btnNew.w() > 0 ? btnNew.x1 : listRight && btnList.w() > 0 ? btnList.x1 : titleRect.x2;
        int titleRight = scale > MessengerLayout.GRAB_SCALE ? headRight : winRect.x2;
        titleHit = new Rect(winRect.x1, winRect.y1, titleRight, winRect.y1 + MessengerLayout.titleHitHeight(TITLE_H, scale, winRect.h()));
        searchRect = new Rect(histRect.x1 + PAD, histRect.y1 + PAD, histRect.x2 - PAD, histRect.y1 + PAD + 14);
        contactRect = new Rect(histRect.x1, searchRect.y2 + PAD, histRect.x2, histRect.y2);
        if (focus) {
            ensureInput();
            int boxW = Math.max(20, inputRect.w() - sendW - 3);
            if (rIn1 > 0) {
                // Rounded: the box draws no border of its own (the text then starts at its x), and a rounded fill is drawn behind it
                input.setBordered(false);
                input.setX(inputRect.x1 + 4);
                input.setY(inputRect.y1 + (inputRect.h() - 8) / 2);
                input.setHeight(8);
                input.setWidth(Math.max(12, boxW - 8));
                inputBox = new Rect(inputRect.x1, inputRect.y1, inputRect.x1 + boxW, inputRect.y2);
            } else {
                input.setBordered(true);
                input.setX(inputRect.x1);
                input.setY(inputRect.y1);
                input.setHeight(14);
                input.setWidth(boxW);
                inputBox = none;
            }
            if (contactOpen) {
                ensureSearch();
                search.setX(searchRect.x1);
                search.setY(searchRect.y1);
                search.setWidth(Math.max(20, searchRect.w()));
            }
        }
    }

    private void button(GuiGraphics g, Font font, Rect r, String label, int mx, int my, boolean active) {
        boolean hover = r.contains(mx, my);
        rounded(g, r.x1, r.y1, r.x2, r.y2, rIn2, col(hover ? ThemeColor.BUTTON_HOVER : active ? ThemeColor.BUTTON_ACTIVE : ThemeColor.BUTTON));
        g.drawString(font, label, r.x1 + (r.w() - font.width(label)) / 2, r.y1 + (r.h() - 8) / 2, col(ThemeColor.TEXT), false);
    }

    private void drawList(GuiGraphics g, Font font, List<Conversation> list, boolean focus, int mx, int my, String shownKey, boolean globalShown) {
        g.enableScissor(listRect.x1, listRect.y1, listRect.x2, listRect.y2);
        int specials = consoleRow ? 2 : 1; // Global is the first row, then the Console (when separate)
        int rowsTotal = specials + list.size();
        int rows = Math.max(1, listRect.h() / ROW_H);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, rowsTotal - rows)));
        for (int i = listScroll; i < rowsTotal; i++) {
            int y = listRect.y1 + (i - listScroll) * ROW_H;
            if (y >= listRect.y2) break;
            boolean consoleEntry = consoleRow && i == 1;
            Conversation c = i < specials ? null : list.get(i - specials);
            Rect row = new Rect(listRect.x1, y, listRect.x2, y + ROW_H);
            boolean isSel = consoleEntry ? consoleShown : c == null ? globalShown : c.key().equals(shownKey);
            if (isSel) rounded(g, row.x1, row.y1, row.x2, row.y2, rIn2, bg(ThemeColor.SELECTED));
            else if (focus && row.contains(mx, my)) rounded(g, row.x1, row.y1, row.x2, row.y2, rIn2, bg(ThemeColor.HOVER));
            if (consoleEntry) {
                int unread = host.badgeConsole() ? host.core().store().consoleUnread() : 0; // the badge setting only hides the drawing
                int badgeW = 0;
                if (unread > 0) {
                    String n = unread > 99 ? "99+" : String.valueOf(unread);
                    int bw = font.width(n) + 6;
                    rounded(g, row.x2 - bw - 2, row.y1 + 2, row.x2 - 2, row.y2 - 2, rIn2, col(ThemeColor.BADGE));
                    g.drawString(font, n, row.x2 - bw + 1, row.y1 + 2, MessengerTheme.textOn(col(ThemeColor.BADGE)), false);
                    badgeW = bw + 3;
                }
                g.drawString(font, font.plainSubstrByWidth("Console", Math.max(8, row.w() - badgeW - 8)), row.x1 + 4, row.y1 + 2, col(ThemeColor.CONSOLE_NAME), false);
                continue;
            }
            if (c == null) {
                g.drawString(font, "Global", row.x1 + 4, row.y1 + 2, col(ThemeColor.ACCENT), false);
                continue;
            }
            boolean friend = host.isFriend(c.name());
            int dotX = row.x1 + 4;
            drawDot(g, dotX, row.y1 + (ROW_H - DOT) / 2, online(c.name()), focus ? listRect : null, mx, my);
            int badgeW = DOT + DOT_GAP;
            if (c.unread() > 0 && host.badgeDms()) {
                String n = c.unread() > 99 ? "99+" : String.valueOf(c.unread());
                int bw = font.width(n) + 6;
                rounded(g, row.x2 - bw - 2, row.y1 + 2, row.x2 - 2, row.y2 - 2, rIn2, col(ThemeColor.BADGE));
                g.drawString(font, n, row.x2 - bw + 1, row.y1 + 2, MessengerTheme.textOn(col(ThemeColor.BADGE)), false);
                badgeW += bw + 3;
            }
            String name = font.plainSubstrByWidth(c.name(), Math.max(8, row.w() - badgeW - 8));
            g.drawString(font, name, dotX + DOT + DOT_GAP, row.y1 + 2, friend ? col(ThemeColor.FRIEND) : col(online(c.name()) ? ThemeColor.ONLINE_NAME : ThemeColor.TEXT), false);
        }
        if (list.isEmpty()) {
            int y = listRect.y1 + (specials - listScroll) * ROW_H + 3;
            if (y < listRect.y2) g.drawString(font, "No whispers yet", listRect.x1 + 5, y, col(ThemeColor.DIM), false);
        }
        g.disableScissor();
    }

    private boolean online(String name) {
        String key = lowerNames.get(name);
        if (key == null) {
            if (lowerNames.size() > 4000) lowerNames.clear();
            key = name.toLowerCase(java.util.Locale.ROOT);
            lowerNames.put(name, key);
        }
        return onlineStatus.isOnlineKey(key);
    }

    /** Filled round dot when online, hollow ring when offline (so the state does not rest on colour alone). */
    private void drawDot(GuiGraphics g, int x, int y, boolean online, Rect area, int mx, int my) {
        int c = col(online ? ThemeColor.ONLINE_DOT : ThemeColor.OFFLINE_DOT);
        if (online) {
            g.fill(x + 1, y, x + 4, y + 1, c);
            g.fill(x, y + 1, x + 5, y + 4, c);
            g.fill(x + 1, y + 4, x + 4, y + 5, c);
        } else {
            g.fill(x + 1, y, x + 4, y + 1, c);
            g.fill(x, y + 1, x + 1, y + 4, c);
            g.fill(x + 4, y + 1, x + 5, y + 4, c);
            g.fill(x + 1, y + 4, x + 4, y + 5, c);
        }
        boolean covered = confirm != Confirm.NONE || (pickerOpen && pickRect.contains(mx, my));
        if (!covered && area != null && area.contains(mx, my) && mx >= x - 1 && mx < x + DOT + 1 && my >= y - 2 && my < y + DOT + 2) {
            dotTip = online ? "online (in tab list)" : "not in tab list";
            dotTipX = mx;
            dotTipY = my;
        }
    }

    private void drawDotTip(GuiGraphics g, Font font, int left, int top, int right, int bottom) {
        int w = font.width(dotTip) + 6;
        int x = Math.max(left, Math.min(dotTipX + 8, right - w));
        int y = Math.max(top, Math.min(dotTipY + 10, bottom - 12));
        g.fill(x, y, x + w, y + 12, 0xF0101820);
        g.fill(x, y, x + w, y + 1, col(ThemeColor.ACCENT));
        g.drawString(font, dotTip, x + 3, y + 2, col(ThemeColor.TEXT), false);
    }

    private void drawHistory(GuiGraphics g, Font font, Conversation sel, boolean global, ConversationStore store) {
        g.enableScissor(histRect.x1, histRect.y1, histRect.x2, histRect.y2);
        if (sel == null && !global) { // the overlay, with no whisper yet
            g.drawString(font, "No whispers yet.", histRect.x1 + 6, histRect.y1 + 6, col(ThemeColor.DIM), false);
            g.disableScissor();
            return;
        }
        List<Message> msgs = consoleShown ? store.console() : global ? globalOrMerged(store) : sel.messages();
        int gap = global ? GLOBAL_GAP : GAP;
        int innerW = histRect.w() - 2 * PAD - 8;
        List<Block> bl = blocks(msgs, global, innerW, font);
        if (msgs.isEmpty() && global) {
            g.drawString(font, consoleShown ? "Console lines (server messages, notices) show up here." : "Public chat shows up here.", histRect.x1 + 6, histRect.y1 + 6, col(ThemeColor.DIM), false);
        }
        int total = 0;
        for (Block b : bl) total += b.height + gap;
        int areaH = histRect.h() - 2 * PAD;
        // Scrolled up, new lines must not move what is being read
        if (histScroll > 0 && scrollSrc == msgs && total > scrollTotal) histScroll += total - scrollTotal;
        scrollSrc = msgs;
        scrollTotal = total;
        int maxScroll = Math.max(0, total - areaH);
        histScroll = Math.max(0, Math.min(histScroll, maxScroll));
        int y = histRect.y2 - PAD - total + histScroll;
        if (total < areaH) y = histRect.y2 - PAD - total; // short history sits at the bottom, like a chat
        for (Block b : bl) {
            if (y + b.height >= histRect.y1 && y <= histRect.y2) drawBlock(g, font, b, y, global);
            y += b.height + gap;
        }
        if (maxScroll > 0) {
            // scrollbar
            int trackH = histRect.h() - 2;
            int barH = Math.max(8, trackH * areaH / (total + 1));
            int barY = histRect.y1 + 1 + (trackH - barH) * (maxScroll - histScroll) / maxScroll;
            g.fill(histRect.x2 - 3, barY, histRect.x2 - 1, barY + barH, 0x80FFFFFF);
        }
        g.disableScissor();
    }

    /** Global, or (in the overlay with overlay-shows-console) Global and the Console by time. */
    private List<Message> globalOrMerged(ConversationStore store) {
        if (drawFocus || !host.separateConsole() || !host.overlayShowsConsole()) return store.global();
        List<Message> g = store.global();
        List<Message> c = store.console();
        Message gl = g.isEmpty() ? null : g.get(g.size() - 1);
        Message cl = c.isEmpty() ? null : c.get(c.size() - 1);
        if (store == mergedStore && g.size() == mergedGlobalSize && c.size() == mergedConsoleSize && gl == mergedGlobalLast && cl == mergedConsoleLast) return merged;
        TimeMerge.merge(g, c, merged);
        mergedStore = store;
        mergedGlobalSize = g.size();
        mergedConsoleSize = c.size();
        mergedGlobalLast = gl;
        mergedConsoleLast = cl;
        return merged;
    }

    private void drawStatus(GuiGraphics g, Font font) {
        if (status == null) return;
        if (System.currentTimeMillis() > statusUntil) {
            status = null;
            return;
        }
        g.enableScissor(histRect.x1, histRect.y1, histRect.x2, histRect.y2);
        String s = font.plainSubstrByWidth(status, histRect.w() - 8);
        pane(g, histRect.x1, histRect.y2 - 11, histRect.x2, histRect.y2, 0xC0200C0C);
        g.drawString(font, s, histRect.x1 + 4, histRect.y2 - 10, col(ThemeColor.ERROR), false);
        g.disableScissor();
    }

    /** Draws a piece of message text with a shadow, optionally in italics. */
    private void text(GuiGraphics g, Font font, TextLayout.Piece p, int x, int y, int color, boolean italic) {
        if (italic) {
            if (italics.size() > 4000) italics.clear();
            Component c = italics.computeIfAbsent(p, k -> Component.literal(k.text()).withStyle(net.minecraft.ChatFormatting.ITALIC));
            g.drawString(font, c, x, y, color, true);
        } else {
            g.drawString(font, p.text(), x, y, color, true);
        }
    }

    private void drawBlock(GuiGraphics g, Font font, Block b, int top, boolean global) {
        Message m = b.message;
        Direction d = m.direction();
        int color = d == Direction.IN ? col(ThemeColor.INCOMING) : d == Direction.OUT ? col(ThemeColor.OWN) : col(ThemeColor.DIM);
        if (global && d == Direction.IN) color = col(b.console ? ThemeColor.CONSOLE_TEXT : ThemeColor.TEXT); // public chat is plain text, no bubbles
        if (b.console && d == Direction.NOTE) color = col(ThemeColor.CONSOLE_NOTE);
        boolean italic = b.console && host.consoleItalic();
        if (m.state() == Message.State.SENDING) color = 0xFF9FB3A5;
        if (m.state() == Message.State.FAILED) color = 0xFFB07070;
        if (b.console) color = MessengerTheme.scaleAlpha(color, host.consoleOpacity());
        int left = histRect.x1 + PAD;
        int right = histRect.x2 - PAD - 6;
        if (!global) {
            if (d == Direction.IN) g.fill(left, top, left + 1, top + b.height, col(ThemeColor.INCOMING_BAR));
            else if (d == Direction.OUT) g.fill(right - 1, top, right, top + b.height, col(ThemeColor.OWN_BAR));
        }
        int y = top;
        boolean first = true;
        for (TextLayout.Line line : b.lines) {
            int x = switch (d) {
                case IN -> left + (global ? 1 : 4) + (b.head != null ? HEAD_ADVANCE : 0);
                case OUT -> right - 4 - line.width();
                case NOTE -> (left + right - line.width()) / 2;
            };
            if (first && b.head != null) PlayerHeads.draw(g, b.head, left + 1, y + 1, HEAD);
            for (TextLayout.Piece p : line.pieces()) {
                if (p.emojiFile() != null) {
                    if (!textures.draw(g, p.emojiFile(), x, y + 1, EMOJI_DRAW)) text(g, font, p, x, y + 1, color, italic);
                } else {
                    text(g, font, p, x, y + 1, color, italic);
                }
                x += p.width();
            }
            if (first && d == Direction.OUT && m.state() != Message.State.SENT) {
                boolean failed = m.state() == Message.State.FAILED;
                g.drawString(font, failed ? "not sent" : "sending...", left + 2, y + 1, failed ? col(ThemeColor.ERROR) : col(ThemeColor.WARNING), false);
            }
            first = false;
            y += LINE_H;
        }
    }

    /** A public chat line that is not player chat: dimmed in Global. Known from the chat tag when it arrived, else guessed from the text. */
    private static boolean isConsole(Message m) {
        if (m.direction() == Direction.NOTE) return true; // our own notices
        if (m.direction() != Direction.IN) return false;
        Boolean known = m.console();
        return known != null ? known : LineKind.guessConsole(m.text(), LineKind.NOTICE_PREFIXES);
    }

    /**
     * The wrapped messages of a list; remade when the list, its length, last message or the width changes. A message
     * that was wrapped before keeps its block, so a busy public chat does not wrap everything again for each new line.
     */
    private List<Block> blocks(List<Message> msgs, boolean global, int width, Font font) {
        boolean consoleView = consoleShown;
        Message last = msgs.isEmpty() ? null : msgs.get(msgs.size() - 1);
        boolean heads = global && host.showHeads(); // what the blocks are made with (whisper views never have heads)
        if (msgs == blocksSrc && msgs.size() == blocksSize && last == blocksLast && BlockCacheKey.same(width, scale, heads, blocksWidth, blocksScale, blocksHeads)) return blocks;
        // While the window is being resized the old wrapping is drawn; it is redone once the width has been still a moment
        long nowMs = System.currentTimeMillis();
        if (msgs == blocksSrc && !BlockCacheKey.same(width, scale, heads, blocksWidth, blocksScale, blocksHeads) && !blocks.isEmpty()) {
            if (width != pendingWidth) {
                pendingWidth = width;
                pendingSince = nowMs;
            }
            if (nowMs - pendingSince < WRAP_SETTLE_MS) return blocks;
        }
        pendingWidth = -1;
        TextLayout.Measure measure = new TextLayout.Measure() {
            @Override
            public int width(String text) {
                return font.width(text);
            }

            @Override
            public int emojiWidth() {
                return EMOJI_ADVANCE;
            }
        };
        if (!BlockCacheKey.same(width, scale, heads, blocksWidth, blocksScale, blocksHeads) || msgs != blocksSrc) {
            blockCache = new IdentityHashMap<>();
            italics.clear(); // its pieces are gone with the old blocks
        }
        Map<Message, Block> used = new IdentityHashMap<>();
        List<Block> out = new ArrayList<>(msgs.size());
        int bubble = global ? width : Math.max(40, width * 85 / 100);
        for (Message m : msgs) {
            Block b = blockCache.get(m);
            if (b == null) {
                int wrap = m.direction() == Direction.NOTE ? width : bubble;
                boolean console = global && (consoleView || isConsole(m));
                String head = heads && !console && m.direction() == Direction.IN ? LineKind.sender(m.text()) : null;
                List<TextLayout.Line> lines = TextLayout.wrap(m.text(), head != null ? wrap - HEAD_ADVANCE : wrap, emoji, measure);
                b = new Block(m, lines, lines.size() * LINE_H, console, head);
            }
            used.put(m, b);
            out.add(b);
        }
        blockCache = used;
        blocks = out;
        blocksSrc = msgs;
        blocksSize = msgs.size();
        blocksLast = last;
        blocksWidth = width;
        blocksScale = scale;
        blocksHeads = heads;
        return out;
    }

    // ----- contact picker (New) -----

    private List<ContactPicker.Entry> contacts() {
        String query = search == null ? "" : search.getValue();
        long now = System.currentTimeMillis();
        if (!query.equals(contactsQuery) || now - contactsAt > 1000) {
            if (!query.equals(contactsQuery)) {
                contactSel = 0;
                contactScroll = 0;
            }
            List<String> convs = new ArrayList<>();
            for (Conversation c : ordered) convs.add(c.name());
            contacts = ContactPicker.build(query, host.friendNames(), host.onlineNames(), convs, host.selfName());
            contactsQuery = query;
            contactsAt = now;
        }
        return contacts;
    }

    private void drawContacts(GuiGraphics g, Font font, int mx, int my) {
        ensureSearch();
        pane(g, histRect.x1, histRect.y1, histRect.x2, histRect.y2, bg(ThemeColor.WINDOW));
        search.render(g, mx, my, 0f);
        List<ContactPicker.Entry> list = contacts();
        drawn = list;
        contactSel = Math.max(0, Math.min(contactSel, Math.max(0, list.size() - 1)));
        int rows = Math.max(1, contactRect.h() / ROW_H);
        contactScroll = Math.max(0, Math.min(contactScroll, Math.max(0, list.size() - rows)));
        g.enableScissor(contactRect.x1, contactRect.y1, contactRect.x2, contactRect.y2);
        if (list.isEmpty()) {
            String q = search.getValue().strip();
            g.drawString(font, q.isEmpty() ? "Nobody to list yet. Type a player name." : "No match, and not a player name.", contactRect.x1 + 6, contactRect.y1 + 4, col(ThemeColor.DIM), false);
        }
        for (int i = contactScroll; i < list.size(); i++) {
            int y = contactRect.y1 + (i - contactScroll) * ROW_H;
            if (y >= contactRect.y2) break;
            ContactPicker.Entry e = list.get(i);
            Rect row = new Rect(contactRect.x1 + 1, y, contactRect.x2 - 1, y + ROW_H);
            if (i == contactSel) rounded(g, row.x1, row.y1, row.x2, row.y2, rIn2, bg(ThemeColor.SELECTED));
            else if (row.contains(mx, my)) rounded(g, row.x1, row.y1, row.x2, row.y2, rIn2, bg(ThemeColor.HOVER));
            String tag = switch (e.source()) {
                case FRIEND -> "friend";
                case ONLINE -> "online";
                case CONVERSATION -> "chat";
                case TYPED -> "";
            };
            int tagW = tag.isEmpty() ? 0 : font.width(tag) + 8;
            boolean typed = e.source() == ContactPicker.Source.TYPED;
            int textX = row.x1 + 4;
            if (!typed) {
                drawDot(g, textX, row.y1 + (ROW_H - DOT) / 2, online(e.name()), contactRect, mx, my);
                textX += DOT + DOT_GAP;
            }
            String label = e.source() == ContactPicker.Source.TYPED ? "Message " + e.name() : e.name();
            int color = e.source() == ContactPicker.Source.TYPED ? col(ThemeColor.ACCENT) : e.source() == ContactPicker.Source.FRIEND ? col(ThemeColor.FRIEND) : col(ThemeColor.TEXT);
            g.drawString(font, font.plainSubstrByWidth(label, Math.max(8, row.w() - tagW - 8 - (textX - row.x1 - 4))), textX, row.y1 + 2, color, false);
            if (tagW > 0) g.drawString(font, tag, row.x2 - tagW + 2, row.y1 + 2, col(ThemeColor.DIM), false);
        }
        g.disableScissor();
    }

    /** Opens (or makes) the conversation with the entry and shows it. This sends nothing. */
    private void pickContact(int index) {
        List<ContactPicker.Entry> list = drawn;
        if (index < 0 || index >= list.size()) return;
        String name = list.get(index).name();
        if (!ContactPicker.validName(name)) return;
        Conversation c = host.core().store().open(name, System.currentTimeMillis());
        closeContacts();
        select(c.name());
    }

    private void openContacts() {
        ensureSearch();
        search.setValue("");
        contactsQuery = null;
        drawn = List.of();
        contactSel = 0;

        contactScroll = 0;
        contactOpen = true;
        search.setFocused(true);
        if (input != null) input.setFocused(false);
    }

    private void closeContacts() {
        contactOpen = false;
        if (search != null) search.setFocused(false);
        if (input != null) input.setFocused(true);
    }

    // ----- delete / clear -----

    private void drawConfirm(GuiGraphics g, Font font, int mx, int my) {
        String question = confirm == Confirm.CLEAR ? (consoleSelected() ? "Clear the Console log?" : "Clear the Global log?") : "Delete the conversation with " + confirmName + "?";
        String yes = confirm == Confirm.CLEAR ? "Clear" : "Delete";
        String no = "Cancel";
        int w = Math.min(histRect.w() - 8, Math.max(font.width(question) + 16, 120));
        int h = 44;
        int x1 = histRect.x1 + (histRect.w() - w) / 2;
        int y1 = histRect.y1 + Math.max(2, (histRect.h() - h) / 2);
        dlgRect = new Rect(x1, y1, x1 + w, y1 + h);
        int bw = Math.max(font.width(yes), font.width(no)) + 12;
        dlgYes = new Rect(x1 + w / 2 - bw - 3, y1 + h - 17, x1 + w / 2 - 3, y1 + h - 4);
        dlgNo = new Rect(x1 + w / 2 + 3, y1 + h - 17, x1 + w / 2 + 3 + bw, y1 + h - 4);
        g.fill(dlgRect.x1 - 1, dlgRect.y1 - 1, dlgRect.x2 + 1, dlgRect.y2 + 1, col(ThemeColor.ACCENT));
        g.fill(dlgRect.x1, dlgRect.y1, dlgRect.x2, dlgRect.y2, 0xF0101820);
        g.drawString(font, font.plainSubstrByWidth(question, w - 8), x1 + 5, y1 + 6, col(ThemeColor.TEXT), false);
        button(g, font, dlgYes, yes, mx, my, false);
        button(g, font, dlgNo, no, mx, my, false);
    }

    private void doConfirm() {
        ConversationStore store = host.core().store();
        if (confirm == Confirm.CLEAR) {
            boolean console = consoleSelected();
            if (console) store.clearConsole();
            else store.clearGlobal();
            blocksSrc = null;
            setStatus(console ? "Cleared the Console log." : "Cleared the Global log.");
        } else if (confirm == Confirm.DELETE && confirmName != null) {
            store.remove(confirmName);
            if (selected != null && selected.equals(Conversation.key(confirmName))) setSelected(null);
            drafts.remove(Conversation.key(confirmName)); // after setSelected, which files the old draft
            setStatus("Deleted the conversation with " + confirmName + ".");
        }
        confirm = Confirm.NONE;
        confirmName = null;
    }

    private void drawPicker(GuiGraphics g, int mx, int my) {
        pane(g, pickRect.x1, pickRect.y1, pickRect.x2, pickRect.y2, bg(ThemeColor.LEFT_PANE));
        List<String> files = emoji.files();
        int rowsTotal = (files.size() + pickCols - 1) / pickCols;
        pickScroll = Math.max(0, Math.min(pickScroll, Math.max(0, rowsTotal - PICK_ROWS)));
        g.enableScissor(pickRect.x1, pickRect.y1, pickRect.x2, pickRect.y2);
        for (int r = 0; r < PICK_ROWS; r++) {
            for (int c = 0; c < pickCols; c++) {
                int idx = (pickScroll + r) * pickCols + c;
                if (idx >= files.size()) break;
                int x = pickRect.x1 + PAD + c * CELL;
                int y = pickRect.y1 + 2 + r * CELL;
                if (mx >= x && mx < x + CELL && my >= y && my < y + CELL) g.fill(x, y, x + CELL, y + CELL, bg(ThemeColor.HOVER));
                textures.draw(g, files.get(idx), x + 1, y + 1, CELL - 2);
            }
        }
        g.disableScissor();
    }

    private void drawInput(GuiGraphics g, Font font, int mx, int my) {
        ensureInput();
        if (inputBox.w() > 0) rounded(g, inputBox.x1, inputBox.y1, inputBox.x2, inputBox.y2, rIn1, bg(ThemeColor.INPUT));
        input.render(g, mx, my, 0f);
        button(g, font, sendRect, "Send", mx, my, false);
    }

    // ---------- mouse and keys (called by MessengerScreen) ----------

    public boolean mouseClicked(double realX, double realY, int button, int sw, int sh) {
        Geometry w = current(sw, sh);
        double x = lx(realX);
        double y = ly(realY);
        layout(w, true, mc.font);
        if (!winRect.contains(x, y)) {
            if (button == 0) close(); // a click outside lets go of the window
            return true;
        }
        if (button != 0) return true;
        if (confirm != Confirm.NONE) {
            // Asked first: the one button answers yes, any other click says no
            if (dlgYes.contains(x, y)) {
                doConfirm();
            } else {
                confirm = Confirm.NONE;
                confirmName = null;
            }
            return true;
        }
        if (gripRect.contains(x, y)) {
            drag = Drag.RESIZE;
            dragDx = w.x() + w.w() - (int) realX; // dragging works in real pixels
            dragDy = w.y() + w.h() - (int) realY;
            return true;
        }
        if (btnList.contains(x, y)) {
            listView = !listView;
            return true;
        }
        if (btnNew.contains(x, y)) {
            if (contactOpen) closeContacts();
            else openContacts();
            return true;
        }
        if (btnEmoji.contains(x, y)) {
            pickerOpen = !pickerOpen;
            return true;
        }
        if (btnDelete.contains(x, y)) {
            Conversation c = selectedConversation();
            if (c == null) {
                boolean none = consoleSelected() ? host.core().store().console().isEmpty() : host.core().store().global().isEmpty();
                if (none) setStatus("Nothing to clear.");
                else confirm = Confirm.CLEAR;
            } else {
                confirm = Confirm.DELETE;
                confirmName = c.name();
            }
            return true;
        }
        if (titleHit.contains(x, y)) {
            drag = Drag.MOVE;
            dragDx = (int) realX - w.x();
            dragDy = (int) realY - w.y();
            return true;
        }
        if (listRect.contains(x, y)) {
            int idx = listScroll + ((int) y - listRect.y1) / ROW_H;
            int specials = consoleRow ? 2 : 1;
            if (idx == 0) {
                if (contactOpen) closeContacts();
                selectGlobal();
                listView = false;
            } else if (consoleRow && idx == 1) {
                if (contactOpen) closeContacts();
                selectConsole();
                listView = false;
            } else if (idx - specials < shownList.size()) {
                if (contactOpen) closeContacts();
                select(shownList.get(idx - specials).name());
                listView = false;
            }
            return true;
        }
        if (contactOpen && histRect.contains(x, y)) {
            if (searchRect.contains(x, y)) {
                ensureSearch();
                search.setFocused(true);
                EditInput.click(search, x, y, button);
            } else if (contactRect.contains(x, y)) {
                pickContact(contactScroll + ((int) y - contactRect.y1) / ROW_H);
            }
            return true;
        }
        if (pickerOpen && pickRect.contains(x, y)) {
            int c = ((int) x - pickRect.x1 - PAD) / CELL;
            int r = ((int) y - pickRect.y1 - 2) / CELL;
            int idx = (pickScroll + r) * pickCols + c;
            List<String> files = emoji.files();
            if (c >= 0 && c < pickCols && r >= 0 && idx >= 0 && idx < files.size()) {
                if (contactOpen) closeContacts();
                ensureInput();
                input.insertText(EmojiSet.textOf(files.get(idx)));
                input.setFocused(true);
            }
            return true;
        }
        if (sendRect.contains(x, y)) {
            if (contactOpen) closeContacts();
            submit();
            return true;
        }
        if (inputRect.contains(x, y)) {
            if (contactOpen) closeContacts();
            ensureInput();
            input.setFocused(true);
            // A rounded box is only 8 pixels tall: a click anywhere in its area still puts the cursor in it
            double cx = inputBox.w() > 0 ? Math.max(input.getX(), Math.min(x, input.getX() + input.getWidth() - 1)) : x;
            double cy = inputBox.w() > 0 ? Math.max(input.getY(), Math.min(y, input.getY() + input.getHeight() - 1)) : y;
            EditInput.click(input, cx, cy, button);
            return true;
        }
        return true;
    }

    public boolean mouseReleased() {
        boolean wasDragging = drag != Drag.NONE;
        drag = Drag.NONE;
        if (wasDragging && geo != null) {
            reevaluateSide(); // Auto looks at where the window was left, never while it moves
            host.geometryChanged(geo);
        }
        return true;
    }

    public boolean mouseDragged(double x, double y, int sw, int sh) {
        if (drag == Drag.NONE) return false;
        Geometry w = current(sw, sh);
        // The edges snap by where the mouse would put the window, not by where it is (see EdgeSnap)
        if (drag == Drag.MOVE) {
            geo = EdgeSnap.move(w, (int) x - dragDx, (int) y - dragDy, sw, sh, host.snapToEdges(), host.snapDistance());
        } else {
            geo = EdgeSnap.resize(w, (int) x + dragDx, (int) y + dragDy, sw, sh, host.snapToEdges(), host.snapDistance());
        }
        return true;
    }

    public boolean mouseScrolled(double realX, double realY, double dy) {
        double x = lx(realX);
        double y = ly(realY);
        int step = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        if (step == 0) return false;
        if (listRect.contains(x, y)) {
            listScroll = Math.max(0, listScroll - step);
        } else if (pickerOpen && pickRect.contains(x, y)) {
            pickScroll = Math.max(0, pickScroll - step);
        } else if (contactOpen && histRect.contains(x, y)) {
            contactScroll = Math.max(0, contactScroll - step);
        } else {
            histScroll = Math.max(0, histScroll + step * LINE_H * 3);
        }
        return true;
    }

    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (confirm != Confirm.NONE) {
            if (key == 256) { // escape says no; nothing else reaches the input while asking
                confirm = Confirm.NONE;
                confirmName = null;
            }
            return true;
        }
        switch (key) {
            case 256 -> { // escape: the contact picker, the emoji picker, then the window
                if (contactOpen) closeContacts();
                else if (pickerOpen) pickerOpen = false;
                else close();
                return true;
            }
            case 257, 335 -> { // enter
                if (inputHidden()) return true;
                if (contactOpen) pickContact(contactSel);
                else submit();
                return true;
            }
            case 264 -> { // down
                if (!contactOpen) return typeKey(key, scancode, modifiers);
                contactSel = Math.min(Math.max(0, drawn.size() - 1), contactSel + 1);
                followContactSel();
                return true;
            }
            case 265 -> { // up
                if (!contactOpen) return typeKey(key, scancode, modifiers);
                contactSel = Math.max(0, contactSel - 1);
                followContactSel();
                return true;
            }
            case 266 -> { // page up
                if (!contactOpen) histScroll += Math.max(LINE_H, histRect.h() * 8 / 10);
                return true;
            }
            case 267 -> { // page down
                if (!contactOpen) histScroll = Math.max(0, histScroll - Math.max(LINE_H, histRect.h() * 8 / 10));
                return true;
            }
            default -> {
                return typeKey(key, scancode, modifiers);
            }
        }
    }

    /** The window is focused but the input line is not drawn (the list is showing in a short window). */
    private boolean inputHidden() {
        return focused && !plan.input() && !contactOpen;
    }

    private boolean typeKey(int key, int scancode, int modifiers) {
        if (inputHidden()) return true; // not into a box the user can not see
        EditBox box = activeBox();
        box.setFocused(true);
        EditInput.key(box, key, scancode, modifiers);
        return true; // nothing reaches the game's own keys while the window is open
    }

    private EditBox activeBox() {
        if (contactOpen) {
            ensureSearch();
            return search;
        }
        ensureInput();
        return input;
    }

    private void followContactSel() {
        int rows = Math.max(1, contactRect.h() / ROW_H);
        if (contactSel < contactScroll) contactScroll = contactSel;
        else if (contactSel >= contactScroll + rows) contactScroll = contactSel - rows + 1;
    }

    public boolean charTyped(int codepoint, int modifiers) {
        if (confirm != Confirm.NONE) return true;
        if (codepoint == '§' || codepoint < 32 || codepoint == 127) return true;
        if (inputHidden()) { // the first printable key brings the input back: the messages show again, with the input line
            listView = false;
            planW = -1; // plan again at the next frame
        }
        EditBox box = activeBox();
        box.setFocused(true);
        EditInput.typed(box, codepoint, modifiers);
        return true;
    }

    // ---------- actions ----------

    /**
     * Enter or Send in the input line: what was typed goes to the open conversation as a whisper, or in Global to the
     * public chat (a text starting with / is a command there). It is only queued; the module sends it, one per interval.
     */
    private void submit() {
        ensureInput();
        String text = input.getValue();
        if (text.isBlank()) return;
        long now = System.currentTimeMillis();
        if (consoleSelected()) {
            setStatus("The Console is read only. Pick Global or a player to write.");
            return;
        }
        Conversation c = selectedConversation();
        String error = c == null ? host.core().sendGlobal(text, now, host.meteorPrefix()) : host.core().send(c.name(), text, now);
        if (error != null) {
            setStatus(error);
        } else {
            input.setValue("");
            histScroll = 0;
        }
    }
}
