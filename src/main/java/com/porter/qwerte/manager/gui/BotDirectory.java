package com.porter.qwerte.manager.gui;

import com.porter.qwerte.manager.model.AuthState;
import com.porter.qwerte.manager.model.BotList;
import com.porter.qwerte.manager.net.ApiResult;
import com.porter.qwerte.manager.net.SmClient;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The dashboard bots the account may see ({@code GET /api/v1/bots}, which needs the {@code activity} permission) and which
 * one the Map and Settings tabs are looking at. Polled every 10 seconds, but only while a tab asks for it. Client thread only.
 */
public final class BotDirectory {
    private static final long POLL_MS = 10_000;

    private final SmClient client;
    private final Supplier<AuthState> auth;
    private final Consumer<ApiResult> onFail;
    private final Consumer<String> remember;

    private BotList list = BotList.EMPTY;
    private String selected;
    private boolean loaded;
    private boolean busy;
    private long next;
    private String signature = "";

    /**
     * @param remember told the bot id when the user picks one (the module keeps it for the next time)
     * @param initial  the bot picked last time, or null
     */
    public BotDirectory(SmClient client, Supplier<AuthState> auth, Consumer<ApiResult> onFail, Consumer<String> remember, String initial) {
        this.client = client;
        this.auth = auth;
        this.onFail = onFail;
        this.remember = remember;
        this.selected = initial;
    }

    public BotList list() {
        return list;
    }

    /** True once an answer has come (an empty list is then really empty). */
    public boolean loaded() {
        return loaded;
    }

    /** The bot id in use: the picked one if it is still listed, else the first, else null. */
    public String selected() {
        if (list.find(selected) != null) return selected;
        return list.bots().isEmpty() ? null : list.bots().get(0).botId();
    }

    public BotList.Bot selectedBot() {
        return list.find(selected());
    }

    public void select(String botId) {
        if (botId == null || botId.equals(selected)) return;
        selected = botId;
        remember.accept(botId);
    }

    /** Asks again at the next {@link #tick}. */
    public void refreshSoon() {
        next = 0;
    }

    /** Forgets the list (session lost). */
    public void clear() {
        list = BotList.EMPTY;
        loaded = false;
        busy = false;
        signature = "";
        next = 0;
    }

    /**
     * Polls while {@code wanted}.
     * The list is replaced when the answer comes; {@link #takeChanged} says whether it differs from before.
     */
    public void tick(long now, boolean wanted) {
        if (!wanted || busy || now < next) return;
        AuthState a = auth.get();
        if (a == null || !a.loggedIn() || !a.can("activity")) {
            loaded = true;
            return;
        }
        busy = true;
        next = now + POLL_MS;
        client.get("/api/v1/bots", null, r -> {
            busy = false;
            if (!r.ok()) {
                onFail.accept(r);
                return;
            }
            BotList parsed = BotList.parse(r.body());
            loaded = true;
            String sig = signatureOf(parsed);
            list = parsed;
            if (!sig.equals(signature)) {
                signature = sig;
                changed = true;
            }
        });

    }

    private boolean changed;

    /** True once after the list changed (the tab rebuilds its picker then). */
    public boolean takeChanged() {
        boolean c = changed;
        changed = false;
        return c;
    }

    private static String signatureOf(BotList l) {
        StringBuilder sb = new StringBuilder();
        for (BotList.Bot b : l.bots()) sb.append(b.botId()).append(b.online() ? '+' : '-').append(b.player()).append('|').append(b.scopes()).append(b.caps()).append(';');
        return sb.toString();
    }
}
