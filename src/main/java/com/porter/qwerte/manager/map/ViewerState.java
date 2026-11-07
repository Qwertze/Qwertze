package com.porter.qwerte.manager.map;

import com.porter.qwerte.manager.map.ViewerEvent.ChatLine;
import com.porter.qwerte.manager.map.ViewerEvent.Ent;
import com.porter.qwerte.manager.map.ViewerEvent.Hud;
import com.porter.qwerte.manager.map.ViewerEvent.Self;
import com.porter.qwerte.manager.map.ViewerEvent.Tile;
import com.porter.qwerte.manager.map.ViewerEvent.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the live viewer knows about the bot, built up from the stream's events: the same rules as the website (a snapshot
 * replaces everything, tiles add and drop, ents replace the entity list and may move the bot, hud replaces the hud, chat
 * appends without duplicates). The map's pixels live in a {@link TileSink}. Pure; client thread only.
 */
public final class ViewerState {
    /** Where tiles go. */
    public interface TileSink {
        void put(int cx, int cz, byte[] colors);

        void drop(int cx, int cz);

        void clear();
    }

    /** What an applied event changed (bit set). */
    public static final int TILES = 1, SELF = 2, ENTS = 4, HUD = 8, CHAT = 16, STATUS = 32, META = 64;
    public static final int CHAT_KEEP = 200;

    private final TileSink sink;
    private boolean relative;
    private String mc = "";
    private String session = "";
    private World world;
    private Self self;
    private List<Ent> ents = List.of();
    private Hud hud;
    private String state = "connecting";
    private String byeReason = "";
    private int failedStatus;
    private final Deque<ChatLine> chat = new ArrayDeque<>();
    private final Set<String> chatKeys = new HashSet<>();
    private int chatAdded;

    public ViewerState(TileSink sink) {
        this.sink = sink;
    }

    /** Forgets everything (another bot, or the stream was restarted). */
    public void reset() {
        sink.clear();
        relative = false;
        mc = "";
        session = "";
        world = null;
        self = null;
        ents = List.of();
        hud = null;
        state = "connecting";
        byeReason = "";
        failedStatus = 0;
        chat.clear();
        chatKeys.clear();
        chatAdded = 0;
    }

    /** @return the {@code TILES}.. bits that changed, 0 for nothing */
    public int apply(ViewerEvent e) {
        int changed = 0;
        if (e instanceof ViewerEvent.Hello h) {
            relative = h.relative();
            mc = h.mc();
            return META;
        }
        if (e instanceof ViewerEvent.Snapshot s) {
            sink.clear();
            for (Tile t : s.tiles()) sink.put(t.x(), t.z(), t.colors());
            session = s.session();
            world = s.world();
            self = s.self();
            ents = s.ents();
            hud = s.hud();
            if (s.relative()) relative = true;
            chat.clear();
            chatKeys.clear();
            chatAdded = 0;
            addChat(s.chat());
            chatAdded = 0; // a snapshot's lines are not "new"
            if (state.equals("connecting") || state.equals("closed")) state = "waiting";
            return TILES | SELF | ENTS | HUD | CHAT | META | STATUS;
        }
        if (e instanceof ViewerEvent.Tiles t) {
            for (Tile tile : t.tiles()) sink.put(tile.x(), tile.z(), tile.colors());
            for (int[] d : t.drop()) sink.drop(d[0], d[1]);
            return TILES;
        }
        if (e instanceof ViewerEvent.Ents en) {
            if (en.self() != null) self = en.self();
            ents = en.ents();
            return SELF | ENTS;
        }
        if (e instanceof ViewerEvent.HudUpdate u) {
            hud = u.hud();
            return HUD;
        }
        if (e instanceof ViewerEvent.Chat c) {
            chatAdded = addChat(c.lines());
            return chatAdded > 0 ? CHAT : 0;
        }
        if (e instanceof ViewerEvent.Status st) {
            if (!st.state().isEmpty()) state = st.state();
            return STATUS;
        }
        if (e instanceof ViewerEvent.Bye b) {
            byeReason = b.reason();
            if (b.reason().equals("revoked")) state = "closed";
            return STATUS;
        }
        if (e instanceof ViewerEvent.Connecting) {
            state = "connecting";
            failedStatus = 0;
            return STATUS;
        }
        if (e instanceof ViewerEvent.Failed f) {
            state = "closed";
            failedStatus = f.status();
            return STATUS;
        }
        return changed;
    }

    /** The website's duplicate key for a chat line. */
    static String key(ChatLine l) {
        return l.time() + "\u0000" + l.kind() + "\u0000" + l.sender() + "\u0000" + l.text();
    }

    private int addChat(List<ChatLine> lines) {
        int added = 0;
        for (ChatLine l : lines) {
            if (!chatKeys.add(key(l))) continue;
            chat.addLast(l);
            added++;
        }
        while (chat.size() > CHAT_KEEP) chatKeys.remove(key(chat.removeFirst()));
        return added;
    }

    public boolean relative() {
        return relative;
    }

    public String mc() {
        return mc;
    }

    public String session() {
        return session;
    }

    public World world() {
        return world;
    }

    public Self self() {
        return self;
    }

    public List<Ent> ents() {
        return ents;
    }

    public Hud hud() {
        return hud;
    }

    /** connecting, waiting, live, stale, offline or closed. */
    public String state() {
        return state;
    }

    public String byeReason() {
        return byeReason;
    }

    /** The HTTP status of the last failure to open the stream (0 = none or no answer). */
    public int failedStatus() {
        return failedStatus;
    }

    /** Oldest first. */
    public List<ChatLine> chat() {
        return Collections.unmodifiableList(new ArrayList<>(chat));
    }

    /** Lines the last chat event added. */
    public int lastChatAdded() {
        return chatAdded;
    }
}
