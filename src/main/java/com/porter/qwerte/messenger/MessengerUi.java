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

        /** Draw the sender's head in front of a public chat line. */
        boolean showHeads();
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
        ensureInput();
        input.setFocused(true);
        Conversation c = selectedConversation();
        if (c != null) host.core().store().markRead(c.name());
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

    private void setSelected(String key) {
        if (input != null) drafts.put(draftKey(selected), input.getValue());
        selected = key;
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

    private void updateHint() {
        String hint = selected == null ? "Say it in public chat (/ for a command)" : "Message";
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
        if (geo == null) {
            Geometry saved = host.geometry();
            geo = saved != null ? saved : Geometry.initial(sw, sh);
        }
        geo = geo.clamp(sw, sh);
        scale = MessengerScale.effective(host.autoFontScale(), host.fontScale(), geo.w(), geo.h());
        return geo;
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
        if (selected != null && store.get(selected) == null) { // it was deleted (or this is another server)
            String dead = selected;
            setSelected(null);
            drafts.remove(dead);
        }
        Conversation sel = selectedConversation();
        if (focus && sel != null) store.markRead(sel.name());
        // The always-on overlay keeps to whispers unless overlay-global is on: a busy public chat is not shown over the game
        Conversation shown = sel;
        boolean globalShown = sel == null;
        if (!focus && sel == null && !host.overlayGlobal()) {
            shown = list.isEmpty() ? null : list.get(0);
            globalShown = false;
        }

        layout(w, focus, font);

        // Frame, title, panes
        g.fill(winRect.x1, winRect.y1, winRect.x2, winRect.y2, bg(ThemeColor.WINDOW));
        g.fill(titleRect.x1, titleRect.y1, titleRect.x2, titleRect.y2, bg(ThemeColor.HEADER));
        int total = store.totalUnread();
        String title = "Messenger" + (shown != null ? " - " + shown.name() : globalShown ? " - Global" : "")
            + (globalShown && core.globalQueued() ? " (sending...)" : "") + (total > 0 ? " (" + total + ")" : "");
        if (!plan.history() && status != null && System.currentTimeMillis() <= statusUntil) title = status; // the messages (where it is shown) are hidden
        int titleX = btnList.w() > 0 ? btnList.x2 + 3 : titleRect.x1 + 4;
        int titleMax = (btnNew.w() > 0 ? btnNew.x1 : titleRect.x2) - titleX - 4;
        if (titleMax > 6 && titleRect.h() >= 9) g.drawString(font, font.plainSubstrByWidth(title, titleMax), titleX, titleRect.y1 + 3, col(ThemeColor.TEXT), true);
        if (btnList.w() > 0) button(g, font, btnList, listView ? "<" : "=", mx, my, listView);
        if (focus && btnNew.w() > 0) {
            button(g, font, btnNew, contactOpen ? "Cancel" : "New", mx, my, contactOpen);
            button(g, font, btnEmoji, "Emoji", mx, my, pickerOpen);
            button(g, font, btnDelete, sel == null ? "Clear" : "Delete", mx, my, confirm != Confirm.NONE);
        }

        if (listRect.w() > 0) {
            g.fill(listRect.x1, listRect.y1, listRect.x2, listRect.y2, bg(ThemeColor.LEFT_PANE));
            drawList(g, font, list, focus, mx, my, shown == null ? null : shown.key(), globalShown);
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
            for (int i = 0; i < 3; i++) {
                int o = (2 + i * 3) * k;
                g.fill(gripRect.x2 - o - k, gripRect.y2 - 2 * k, gripRect.x2 - o, gripRect.y2 - k, col(ThemeColor.DIM));
                g.fill(gripRect.x2 - 2 * k, gripRect.y2 - o - k, gripRect.x2 - k, gripRect.y2 - o, col(ThemeColor.DIM));
            }
        } else if (plan.input()) {
            g.fill(inputRect.x1, inputRect.y1, inputRect.x2, inputRect.y2, bg(ThemeColor.INPUT));
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
        // Header, left to right: the list toggle; right to left: the buttons
        int by1 = titleRect.y1 + 2;
        int by2 = titleRect.y2 - 2;
        btnList = focus && plan.toggle() && !contactOpen ? new Rect(titleRect.x1 + 2, by1, titleRect.x1 + 2 + (by2 - by1) + 4, by2) : none;
        if (focus && plan.buttons()) {
            int bx = winRect.x2 - 3;
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

        // The list: beside the messages, or (collapsed and asked for) instead of them
        int listW = Math.max(60, Math.min(110, winRect.w() * 3 / 10));
        if (plan.sidebar()) listRect = new Rect(winRect.x1, titleRect.y2, winRect.x1 + listW, winRect.y2);
        else if (plan.listView()) listRect = new Rect(winRect.x1, titleRect.y2, winRect.x2, winRect.y2);
        else listRect = none;
        int left = plan.sidebar() ? listRect.x2 + 1 : winRect.x1; // where the messages and the input start
        int inputH = plan.input() ? INPUT_H : 0;
        if (plan.input()) inputRect = new Rect(left + PAD, winRect.y2 - INPUT_H, winRect.x2 - PAD, winRect.y2 - 1);
        else inputRect = none;
        int sendW = font.width("Send") + 10;
        sendRect = !plan.input() ? none : focus ? new Rect(inputRect.x2 - sendW, inputRect.y1, inputRect.x2, inputRect.y2) : inputRect;
        pickCols = Math.max(1, (winRect.x2 - left - 2 * PAD - 6) / CELL);
        int pickH = focus && pickerOpen && plan.input() && plan.history() ? PICK_ROWS * CELL + 4 : 0;
        pickRect = pickH > 0 ? new Rect(left, winRect.y2 - INPUT_H - pickH, winRect.x2, winRect.y2 - INPUT_H) : none;
        histRect = plan.history() ? new Rect(left, titleRect.y2, winRect.x2, winRect.y2 - inputH - pickH) : none;
        int gripSize = MessengerLayout.hitSize(GRIP, scale, Math.min(winRect.w(), winRect.h()));
        gripRect = new Rect(winRect.x2 - gripSize, winRect.y2 - gripSize, winRect.x2, winRect.y2);
        // Above scale 0.4 only the part of the header left of the buttons starts a drag; at tiny scales all of it, and taller
        int titleRight = scale > MessengerLayout.GRAB_SCALE && btnNew.w() > 0 ? btnNew.x1 : winRect.x2;
        titleHit = new Rect(winRect.x1, winRect.y1, titleRight, winRect.y1 + MessengerLayout.titleHitHeight(TITLE_H, scale, winRect.h()));
        searchRect = new Rect(histRect.x1 + PAD, histRect.y1 + PAD, histRect.x2 - PAD, histRect.y1 + PAD + 14);
        contactRect = new Rect(histRect.x1, searchRect.y2 + PAD, histRect.x2, histRect.y2);
        if (focus) {
            ensureInput();
            input.setX(inputRect.x1);
            input.setY(inputRect.y1);
            input.setWidth(Math.max(20, inputRect.w() - sendW - 3));
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
        g.fill(r.x1, r.y1, r.x2, r.y2, col(hover ? ThemeColor.BUTTON_HOVER : active ? ThemeColor.BUTTON_ACTIVE : ThemeColor.BUTTON));
        g.drawString(font, label, r.x1 + (r.w() - font.width(label)) / 2, r.y1 + (r.h() - 8) / 2, col(ThemeColor.TEXT), false);
    }

    private void drawList(GuiGraphics g, Font font, List<Conversation> list, boolean focus, int mx, int my, String shownKey, boolean globalShown) {
        g.enableScissor(listRect.x1, listRect.y1, listRect.x2, listRect.y2);
        int rowsTotal = 1 + list.size(); // Global is the first row
        int rows = Math.max(1, listRect.h() / ROW_H);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, rowsTotal - rows)));
        for (int i = listScroll; i < rowsTotal; i++) {
            int y = listRect.y1 + (i - listScroll) * ROW_H;
            if (y >= listRect.y2) break;
            Conversation c = i == 0 ? null : list.get(i - 1);
            Rect row = new Rect(listRect.x1, y, listRect.x2, y + ROW_H);
            boolean isSel = c == null ? globalShown : c.key().equals(shownKey);
            if (isSel) g.fill(row.x1, row.y1, row.x2, row.y2, bg(ThemeColor.SELECTED));
            else if (focus && row.contains(mx, my)) g.fill(row.x1, row.y1, row.x2, row.y2, bg(ThemeColor.HOVER));
            if (c == null) {
                g.drawString(font, "Global", row.x1 + 4, row.y1 + 2, col(ThemeColor.ACCENT), false);
                continue;
            }
            boolean friend = host.isFriend(c.name());
            int dotX = row.x1 + 4;
            drawDot(g, dotX, row.y1 + (ROW_H - DOT) / 2, online(c.name()), focus ? listRect : null, mx, my);
            int badgeW = DOT + DOT_GAP;
            if (c.unread() > 0) {
                String n = c.unread() > 99 ? "99+" : String.valueOf(c.unread());
                int bw = font.width(n) + 6;
                g.fill(row.x2 - bw - 2, row.y1 + 2, row.x2 - 2, row.y2 - 2, col(ThemeColor.BADGE));
                g.drawString(font, n, row.x2 - bw + 1, row.y1 + 2, MessengerTheme.textOn(col(ThemeColor.BADGE)), false);
                badgeW += bw + 3;
            }
            String name = font.plainSubstrByWidth(c.name(), Math.max(8, row.w() - badgeW - 8));
            g.drawString(font, name, dotX + DOT + DOT_GAP, row.y1 + 2, friend ? col(ThemeColor.FRIEND) : col(online(c.name()) ? ThemeColor.ONLINE_NAME : ThemeColor.TEXT), false);
        }
        if (list.isEmpty()) {
            int y = listRect.y1 + (1 - listScroll) * ROW_H + 3;
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
        List<Message> msgs = global ? store.global() : sel.messages();
        int gap = global ? GLOBAL_GAP : GAP;
        int innerW = histRect.w() - 2 * PAD - 8;
        List<Block> bl = blocks(msgs, global, innerW, font);
        if (msgs.isEmpty() && global) {
            g.drawString(font, "Public chat shows up here.", histRect.x1 + 6, histRect.y1 + 6, col(ThemeColor.DIM), false);
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

    private void drawStatus(GuiGraphics g, Font font) {
        if (status == null) return;
        if (System.currentTimeMillis() > statusUntil) {
            status = null;
            return;
        }
        g.enableScissor(histRect.x1, histRect.y1, histRect.x2, histRect.y2);
        String s = font.plainSubstrByWidth(status, histRect.w() - 8);
        g.fill(histRect.x1, histRect.y2 - 11, histRect.x2, histRect.y2, 0xC0200C0C);
        g.drawString(font, s, histRect.x1 + 4, histRect.y2 - 10, col(ThemeColor.ERROR), false);
        g.disableScissor();
    }

    private void drawBlock(GuiGraphics g, Font font, Block b, int top, boolean global) {
        Message m = b.message;
        Direction d = m.direction();
        int color = d == Direction.IN ? col(ThemeColor.INCOMING) : d == Direction.OUT ? col(ThemeColor.OWN) : col(ThemeColor.DIM);
        if (global && d == Direction.IN) color = col(ThemeColor.TEXT); // public chat is plain text, no bubbles
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
                    if (!textures.draw(g, p.emojiFile(), x, y + 1, EMOJI_DRAW)) g.drawString(font, p.text(), x, y + 1, color, true);
                } else {
                    g.drawString(font, p.text(), x, y + 1, color, true);
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
        if (!BlockCacheKey.same(width, scale, heads, blocksWidth, blocksScale, blocksHeads) || msgs != blocksSrc) blockCache = new IdentityHashMap<>();
        Map<Message, Block> used = new IdentityHashMap<>();
        List<Block> out = new ArrayList<>(msgs.size());
        int bubble = global ? width : Math.max(40, width * 85 / 100);
        for (Message m : msgs) {
            Block b = blockCache.get(m);
            if (b == null) {
                int wrap = m.direction() == Direction.NOTE ? width : bubble;
                boolean console = global && isConsole(m);
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
        g.fill(histRect.x1, histRect.y1, histRect.x2, histRect.y2, bg(ThemeColor.WINDOW));
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
            if (i == contactSel) g.fill(row.x1, row.y1, row.x2, row.y2, bg(ThemeColor.SELECTED));
            else if (row.contains(mx, my)) g.fill(row.x1, row.y1, row.x2, row.y2, bg(ThemeColor.HOVER));
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
        String question = confirm == Confirm.CLEAR ? "Clear the Global log?" : "Delete the conversation with " + confirmName + "?";
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
            store.clearGlobal();
            blocksSrc = null;
            setStatus("Cleared the Global log.");
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
        g.fill(pickRect.x1, pickRect.y1, pickRect.x2, pickRect.y2, bg(ThemeColor.LEFT_PANE));
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
                if (host.core().store().global().isEmpty()) setStatus("Nothing to clear.");
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
            if (idx == 0) {
                if (contactOpen) closeContacts();
                selectGlobal();
                listView = false;
            } else if (idx <= shownList.size()) {
                if (contactOpen) closeContacts();
                select(shownList.get(idx - 1).name());
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
            EditInput.click(input, x, y, button);
            return true;
        }
        return true;
    }

    public boolean mouseReleased() {
        if (drag != Drag.NONE && geo != null) host.geometryChanged(geo);
        drag = Drag.NONE;
        return true;
    }

    public boolean mouseDragged(double x, double y, int sw, int sh) {
        if (drag == Drag.NONE) return false;
        Geometry w = current(sw, sh);
        if (drag == Drag.MOVE) {
            geo = new Geometry((int) x - dragDx, (int) y - dragDy, w.w(), w.h()).clamp(sw, sh);
        } else {
            int nw = (int) x + dragDx - w.x();
            int nh = (int) y + dragDy - w.y();
            geo = new Geometry(w.x(), w.y(), Math.max(Geometry.MIN_W, nw), Math.max(Geometry.MIN_H, nh)).clamp(sw, sh);
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
