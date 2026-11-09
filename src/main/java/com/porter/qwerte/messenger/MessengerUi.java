package com.porter.qwerte.messenger;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The Messenger window: a conversation list on the left, the history and an input line on the right, an emoji picker.
 * The same code draws it twice: as a HUD overlay while the game is played ({@code focused} false: nothing can be clicked
 * or typed into) and inside {@link MessengerScreen} ({@code focused} true: it takes the mouse and keyboard). All sizes
 * are GUI pixels. Client thread only.
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

    private record Block(Message message, List<TextLayout.Line> lines, int height) {
    }

    private enum Drag {
        NONE, MOVE, RESIZE
    }

    private static final int TITLE_H = 13;
    private static final int ROW_H = 12;
    private static final int LINE_H = 11;
    private static final int INPUT_H = 16;
    private static final int GRIP = 9;
    private static final int PAD = 3;
    private static final int GAP = 3;
    private static final int CELL = 12;
    private static final int PICK_ROWS = 4;
    private static final int EMOJI_DRAW = 9;
    private static final int EMOJI_ADVANCE = 10;

    private static final int C_PANEL = 0x0E1218;
    private static final int C_TITLE = 0x1B2430;
    private static final int C_LIST = 0x131A22;
    private static final int C_SELECT = 0x24456B;
    private static final int C_HOVER = 0x1E2C3B;
    private static final int C_ACCENT = 0x4FA3FF;
    private static final int C_TEXT = 0xFFE6EAF0;
    private static final int C_DIM = 0xFF8A94A0;
    private static final int C_FRIEND = 0xFFFFD866;
    private static final int C_IN = 0xFFDCEBFF;
    private static final int C_OUT = 0xFFD2F5D8;
    private static final int C_BAR_IN = 0xFF4FA3FF;
    private static final int C_BAR_OUT = 0xFF5BD16E;
    private static final int C_WARN = 0xFFE0C060;
    private static final int C_ERROR = 0xFFE06868;

    private final Host host;
    private final EmojiSet emoji = EmojiSet.bundled();
    private final EmojiTextures textures = new EmojiTextures();
    private EditBox input;

    private boolean focused;
    private String selected;
    private int listScroll;
    private int histScroll;
    private int pickScroll;
    private boolean pickerOpen;
    private boolean newChat;
    private String draft = "";
    private String status;
    private long statusUntil;
    private long deleteArmedUntil;

    private Geometry geo;
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
    private int pickCols = 1;
    private List<Conversation> shownList = List.of();

    // Wrapped history of the open conversation, remade only when it changes
    private List<Block> blocks = List.of();
    private Conversation blocksConv;
    private int blocksSize;
    private Message blocksLast;
    private int blocksWidth;

    // The sorted conversation list, kept until the store changes or the friends list does
    private ConversationStore orderedStore;
    private int orderedVersion;
    private int orderedFriends;
    private long friendsCheckedAt;
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
        drag = Drag.NONE;
        if (input != null) input.setFocused(false);
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

    public void select(String name) {
        selected = Conversation.key(name);
        histScroll = 0;
        blocksConv = null;
        if (focused) host.core().store().markRead(name); // unfocused, a new message keeps its unread badge
    }

    /** The saved place may have changed (settings reloaded): read it again. */
    public void resetGeometry() {
        geo = null;
    }

    /** A message arrived or was sent: show the conversation if none is open. */
    public void onActivity(MessengerCore.Received r) {
        if (r == null) return;
        if (selectedConversation() == null) select(r.conversation().name());
    }

    /** Forgets everything the window shows about a conversation (after the history was cleared). */
    public void reset() {
        selected = null;
        blocks = List.of();
        blocksConv = null;
        orderedStore = null;
        histScroll = 0;
        listScroll = 0;
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
        input.setHint(Component.literal("Message").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
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
        return geo;
    }

    private int alpha(int rgb) {
        int a = Math.round(255f * Math.max(0.2f, Math.min(1f, host.opacity())));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    // ---------- drawing ----------

    public void render(GuiGraphics g, int sw, int sh, int mx, int my, boolean focus) {
        if (sw <= 0 || sh <= 0) return;
        Font font = mc.font;
        Geometry w = current(sw, sh);
        MessengerCore core = host.core();
        ConversationStore store = core.store();
        textures.newFrame();
        long nowMs = System.currentTimeMillis();
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
        if (selected != null && store.get(selected) == null) selected = null;
        if (selected == null && !list.isEmpty()) selected = list.get(0).key();
        Conversation sel = selectedConversation();
        if (focus && sel != null) store.markRead(sel.name());

        layout(w, focus, font);

        // Frame, title, panes
        g.fill(winRect.x1, winRect.y1, winRect.x2, winRect.y2, alpha(C_PANEL));
        g.fill(titleRect.x1, titleRect.y1, titleRect.x2, titleRect.y2, alpha(C_TITLE));
        int total = store.totalUnread();
        String title = "Messenger" + (sel != null ? " - " + sel.name() : "") + (total > 0 ? " (" + total + ")" : "");
        int titleMax = (focus ? btnDelete.x1 : titleRect.x2) - titleRect.x1 - 8;
        g.drawString(font, font.plainSubstrByWidth(title, Math.max(10, titleMax)), titleRect.x1 + 4, titleRect.y1 + 3, C_TEXT, true);
        if (focus) {
            button(g, font, btnNew, newChat ? "Cancel" : "New", mx, my, newChat);
            button(g, font, btnEmoji, "Emoji", mx, my, pickerOpen);
            button(g, font, btnDelete, System.currentTimeMillis() < deleteArmedUntil ? "Sure?" : "Delete", mx, my, false);
        }

        g.fill(listRect.x1, listRect.y1, listRect.x2, listRect.y2, alpha(C_LIST));
        drawList(g, font, list, focus, mx, my);
        drawHistory(g, font, sel);

        if (focus) {
            if (pickerOpen) drawPicker(g, mx, my);
            drawInput(g, font, mx, my);
            // grip
            for (int i = 0; i < 3; i++) {
                int o = 2 + i * 3;
                g.fill(gripRect.x2 - o - 1, gripRect.y2 - 2, gripRect.x2 - o, gripRect.y2 - 1, 0xFF8A94A0);
                g.fill(gripRect.x2 - 2, gripRect.y2 - o - 1, gripRect.x2 - 1, gripRect.y2 - o, 0xFF8A94A0);
            }
        } else {
            g.fill(inputRect.x1, inputRect.y1, inputRect.x2, inputRect.y2, alpha(C_TITLE));
            g.drawString(font, font.plainSubstrByWidth(host.hint(), Math.max(10, inputRect.w() - 8)), inputRect.x1 + 4, inputRect.y1 + 4, C_DIM, false);
        }
    }

    private void layout(Geometry w, boolean focus, Font font) {
        winRect = new Rect(w.x(), w.y(), w.x() + w.w(), w.y() + w.h());
        titleRect = new Rect(winRect.x1, winRect.y1, winRect.x2, winRect.y1 + TITLE_H);
        // Buttons, right to left
        int bx = winRect.x2 - 3;
        int by1 = winRect.y1 + 2;
        int by2 = winRect.y1 + TITLE_H - 2;
        int dw = font.width("Delete") + 8;
        btnDelete = new Rect(bx - dw, by1, bx, by2);
        bx -= dw + 2;
        int ew = font.width("Emoji") + 8;
        btnEmoji = new Rect(bx - ew, by1, bx, by2);
        bx -= ew + 2;
        int nw = font.width("Cancel") + 8;
        btnNew = new Rect(bx - nw, by1, bx, by2);

        int listW = Math.max(60, Math.min(110, winRect.w() * 3 / 10));
        listRect = new Rect(winRect.x1, titleRect.y2, winRect.x1 + listW, winRect.y2);
        inputRect = new Rect(listRect.x2 + 1 + PAD, winRect.y2 - INPUT_H, winRect.x2 - PAD, winRect.y2 - 1);
        int sendW = font.width("Send") + 10;
        sendRect = focus ? new Rect(inputRect.x2 - sendW, inputRect.y1, inputRect.x2, inputRect.y2) : inputRect;
        pickCols = Math.max(1, (winRect.x2 - listRect.x2 - 2 * PAD - 6) / CELL);
        int pickH = focus && pickerOpen ? PICK_ROWS * CELL + 4 : 0;
        pickRect = new Rect(listRect.x2 + 1, winRect.y2 - INPUT_H - pickH, winRect.x2, winRect.y2 - INPUT_H);
        histRect = new Rect(listRect.x2 + 1, titleRect.y2, winRect.x2, pickRect.y1);
        gripRect = new Rect(winRect.x2 - GRIP, winRect.y2 - GRIP, winRect.x2, winRect.y2);
        if (focus) {
            ensureInput();
            input.setX(inputRect.x1);
            input.setY(inputRect.y1);
            input.setWidth(Math.max(20, inputRect.w() - sendW - 3));
        }
    }

    private void button(GuiGraphics g, Font font, Rect r, String label, int mx, int my, boolean active) {
        boolean hover = r.contains(mx, my);
        g.fill(r.x1, r.y1, r.x2, r.y2, hover ? 0xFF3A5A80 : active ? 0xFF2D4C70 : 0xFF2A3644);
        g.drawString(font, label, r.x1 + (r.w() - font.width(label)) / 2, r.y1 + (r.h() - 8) / 2, C_TEXT, false);
    }

    private void drawList(GuiGraphics g, Font font, List<Conversation> list, boolean focus, int mx, int my) {
        g.enableScissor(listRect.x1, listRect.y1, listRect.x2, listRect.y2);
        if (list.isEmpty()) {
            g.drawString(font, "No messages yet", listRect.x1 + 5, listRect.y1 + 5, C_DIM, false);
        }
        int rows = Math.max(1, listRect.h() / ROW_H);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, list.size() - rows)));
        for (int i = listScroll; i < list.size(); i++) {
            int y = listRect.y1 + (i - listScroll) * ROW_H;
            if (y >= listRect.y2) break;
            Conversation c = list.get(i);
            Rect row = new Rect(listRect.x1, y, listRect.x2, y + ROW_H);
            boolean isSel = c.key().equals(selected);
            if (isSel) g.fill(row.x1, row.y1, row.x2, row.y2, alpha(C_SELECT));
            else if (focus && row.contains(mx, my)) g.fill(row.x1, row.y1, row.x2, row.y2, alpha(C_HOVER));
            boolean friend = host.isFriend(c.name());
            int badgeW = 0;
            if (c.unread() > 0) {
                String n = c.unread() > 99 ? "99+" : String.valueOf(c.unread());
                badgeW = font.width(n) + 6;
                g.fill(row.x2 - badgeW - 2, row.y1 + 2, row.x2 - 2, row.y2 - 2, 0xFF000000 | C_ACCENT);
                g.drawString(font, n, row.x2 - badgeW + 1, row.y1 + 2, 0xFF0B1118, false);
                badgeW += 3;
            }
            String name = font.plainSubstrByWidth(c.name(), Math.max(8, row.w() - badgeW - 8));
            g.drawString(font, name, row.x1 + 4, row.y1 + 2, friend ? C_FRIEND : C_TEXT, false);
        }
        g.disableScissor();
    }

    private void drawHistory(GuiGraphics g, Font font, Conversation sel) {
        g.enableScissor(histRect.x1, histRect.y1, histRect.x2, histRect.y2);
        if (sel == null) {
            String msg = focused ? "Pick a conversation, or press New." : "No conversation.";
            g.drawString(font, msg, histRect.x1 + 6, histRect.y1 + 6, C_DIM, false);
            g.disableScissor();
            return;
        }
        int innerW = histRect.w() - 2 * PAD - 8;
        List<Block> bl = blocks(sel, innerW, font);
        int total = 0;
        for (Block b : bl) total += b.height + GAP;
        int areaH = histRect.h() - 2 * PAD;
        int maxScroll = Math.max(0, total - areaH);
        histScroll = Math.max(0, Math.min(histScroll, maxScroll));
        int y = histRect.y2 - PAD - total + histScroll;
        if (total < areaH) y = histRect.y2 - PAD - total; // short history sits at the bottom, like a chat
        for (Block b : bl) {
            if (y + b.height >= histRect.y1 && y <= histRect.y2) drawBlock(g, font, b, y);
            y += b.height + GAP;
        }
        if (maxScroll > 0) {
            // scrollbar
            int trackH = histRect.h() - 2;
            int barH = Math.max(8, trackH * areaH / (total + 1));
            int barY = histRect.y1 + 1 + (trackH - barH) * (maxScroll - histScroll) / maxScroll;
            g.fill(histRect.x2 - 3, barY, histRect.x2 - 1, barY + barH, 0x80FFFFFF);
        }
        if (status != null) {
            if (System.currentTimeMillis() > statusUntil) {
                status = null;
            } else {
                String s = font.plainSubstrByWidth(status, histRect.w() - 8);
                g.fill(histRect.x1, histRect.y2 - 11, histRect.x2, histRect.y2, 0xC0200C0C);
                g.drawString(font, s, histRect.x1 + 4, histRect.y2 - 10, C_ERROR, false);
            }
        }
        g.disableScissor();
    }

    private void drawBlock(GuiGraphics g, Font font, Block b, int top) {
        Message m = b.message;
        Direction d = m.direction();
        int color = d == Direction.IN ? C_IN : d == Direction.OUT ? C_OUT : C_DIM;
        if (m.state() == Message.State.SENDING) color = 0xFF9FB3A5;
        if (m.state() == Message.State.FAILED) color = 0xFFB07070;
        int left = histRect.x1 + PAD;
        int right = histRect.x2 - PAD - 6;
        if (d == Direction.IN) g.fill(left, top, left + 1, top + b.height, C_BAR_IN);
        else if (d == Direction.OUT) g.fill(right - 1, top, right, top + b.height, C_BAR_OUT);
        int y = top;
        boolean first = true;
        for (TextLayout.Line line : b.lines) {
            int x = switch (d) {
                case IN -> left + 4;
                case OUT -> right - 4 - line.width();
                case NOTE -> (left + right - line.width()) / 2;
            };
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
                g.drawString(font, failed ? "not sent" : "sending...", left + 2, y + 1, failed ? C_ERROR : C_WARN, false);
            }
            first = false;
            y += LINE_H;
        }
    }

    /** The wrapped messages of a conversation; remade when the conversation, its length, last message or the width changes. */
    private List<Block> blocks(Conversation c, int width, Font font) {
        List<Message> msgs = c.messages();
        Message last = msgs.isEmpty() ? null : msgs.get(msgs.size() - 1);
        if (c == blocksConv && msgs.size() == blocksSize && last == blocksLast && width == blocksWidth) return blocks;
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
        List<Block> out = new ArrayList<>(msgs.size());
        int bubble = Math.max(40, width * 85 / 100);
        for (Message m : msgs) {
            int wrap = m.direction() == Direction.NOTE ? width : bubble;
            List<TextLayout.Line> lines = TextLayout.wrap(m.text(), wrap, emoji, measure);
            out.add(new Block(m, lines, lines.size() * LINE_H));
        }
        blocks = out;
        blocksConv = c;
        blocksSize = msgs.size();
        blocksLast = last;
        blocksWidth = width;
        return out;
    }

    private void drawPicker(GuiGraphics g, int mx, int my) {
        g.fill(pickRect.x1, pickRect.y1, pickRect.x2, pickRect.y2, alpha(C_LIST));
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
                if (mx >= x && mx < x + CELL && my >= y && my < y + CELL) g.fill(x, y, x + CELL, y + CELL, alpha(C_HOVER));
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

    public boolean mouseClicked(double x, double y, int button, int sw, int sh) {
        Geometry w = current(sw, sh);
        layout(w, true, mc.font);
        if (!winRect.contains(x, y)) {
            if (button == 0) close(); // a click outside lets go of the window
            return true;
        }
        if (button != 0) return true;
        if (gripRect.contains(x, y)) {
            drag = Drag.RESIZE;
            dragDx = winRect.x2 - (int) x;
            dragDy = winRect.y2 - (int) y;
            return true;
        }
        if (btnNew.contains(x, y)) {
            toggleNewChat();
            return true;
        }
        if (btnEmoji.contains(x, y)) {
            pickerOpen = !pickerOpen;
            return true;
        }
        if (btnDelete.contains(x, y)) {
            Conversation c = selectedConversation();
            long now = System.currentTimeMillis();
            if (c == null) {
                // nothing to delete
            } else if (now > deleteArmedUntil) {
                deleteArmedUntil = now + 3000; // asks first: click again within 3 seconds
            } else {
                deleteArmedUntil = 0;
                host.core().store().remove(c.name());
                selected = null;
                blocksConv = null;
                setStatus("Deleted the conversation with " + c.name() + ".");
            }
            return true;
        }
        if (titleRect.contains(x, y)) {
            drag = Drag.MOVE;
            dragDx = (int) x - winRect.x1;
            dragDy = (int) y - winRect.y1;
            return true;
        }
        if (listRect.contains(x, y)) {
            int idx = listScroll + ((int) y - listRect.y1) / ROW_H;
            if (idx >= 0 && idx < shownList.size()) select(shownList.get(idx).name());
            return true;
        }
        if (pickerOpen && pickRect.contains(x, y)) {
            int c = ((int) x - pickRect.x1 - PAD) / CELL;
            int r = ((int) y - pickRect.y1 - 2) / CELL;
            int idx = (pickScroll + r) * pickCols + c;
            List<String> files = emoji.files();
            if (c >= 0 && c < pickCols && r >= 0 && idx >= 0 && idx < files.size()) {
                ensureInput();
                input.insertText(EmojiSet.textOf(files.get(idx)));
                input.setFocused(true);
            }
            return true;
        }
        if (sendRect.contains(x, y)) {
            submit();
            return true;
        }
        if (inputRect.contains(x, y)) {
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

    public boolean mouseScrolled(double x, double y, double dy) {
        int step = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        if (step == 0) return false;
        if (listRect.contains(x, y)) {
            listScroll = Math.max(0, listScroll - step);
        } else if (pickerOpen && pickRect.contains(x, y)) {
            pickScroll = Math.max(0, pickScroll - step);
        } else {
            histScroll = Math.max(0, histScroll + step * LINE_H * 3);
        }
        return true;
    }

    public boolean keyPressed(int key, int scancode, int modifiers) {
        switch (key) {
            case 256 -> { // escape: the picker, then a new-chat box, then the window
                if (pickerOpen) pickerOpen = false;
                else if (newChat) toggleNewChat();
                else close();
                return true;
            }
            case 257, 335 -> { // enter
                submit();
                return true;
            }
            case 266 -> { // page up
                histScroll += Math.max(LINE_H, histRect.h() * 8 / 10);
                return true;
            }
            case 267 -> { // page down
                histScroll = Math.max(0, histScroll - Math.max(LINE_H, histRect.h() * 8 / 10));
                return true;
            }
            default -> {
                ensureInput();
                input.setFocused(true);
                EditInput.key(input, key, scancode, modifiers);
                return true; // nothing reaches the game's own keys while the window is open
            }
        }
    }

    public boolean charTyped(int codepoint, int modifiers) {
        if (codepoint == '§' || codepoint < 32 || codepoint == 127) return true;
        ensureInput();
        input.setFocused(true);
        EditInput.typed(input, codepoint, modifiers);
        return true;
    }

    // ---------- actions ----------

    private void toggleNewChat() {
        ensureInput();
        if (!newChat) {
            draft = input.getValue();
            input.setValue("");
            input.setHint(Component.literal("Player name, then Enter").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            newChat = true;
        } else {
            input.setValue(draft);
            input.setHint(Component.literal("Message").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            newChat = false;
        }
        input.setFocused(true);
    }

    private void submit() {
        ensureInput();
        String text = input.getValue();
        long now = System.currentTimeMillis();
        ConversationStore store = host.core().store();
        if (newChat) {
            String name = text.strip();
            if (!ChatText.isValidName(name)) {
                setStatus("Not a player name (3 to 16 letters, digits or _).");
                return;
            }
            Conversation c = store.open(name, now);
            select(c.name());
            toggleNewChat();
            return;
        }
        Conversation c = selectedConversation();
        if (c == null) {
            setStatus("Pick a conversation, or press New.");
            return;
        }
        if (text.isBlank()) return;
        String error = host.core().send(c.name(), text, now);
        if (error != null) {
            setStatus(error);
        } else {
            input.setValue("");
            histScroll = 0;
        }
    }
}
