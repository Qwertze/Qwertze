package com.porter.qwerte.messenger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One {@link ConversationStore} per server, so a name on one server never mixes with the same name on another. The
 * window shows {@link #current()} only. Singleplayer and LAN are the server "local". Pure: no Minecraft classes.
 */
public final class ServerStores {
    public static final int MAX_SERVERS = 20;
    public static final String LOCAL = "local";
    private static final Pattern VALID = Pattern.compile("[a-z0-9._:\\-\\[\\]]{1,100}");

    private final Map<String, ConversationStore> byServer = new LinkedHashMap<>();
    private int cap;
    private int globalCap = ConversationStore.DEFAULT_GLOBAL_CAP;
    private String current = LOCAL;

    public ServerStores(int cap) {
        this.cap = ConversationStore.clampCap(cap);
        store(LOCAL);
    }

    /** One store under one name, for tests and tools. */
    public static ServerStores single(String server, ConversationStore store) {
        ServerStores s = new ServerStores(store.cap());
        s.byServer.clear();
        s.byServer.put(normalize(server), store);
        s.current = normalize(server);
        return s;
    }

    /** The key of a server address: lower case, no spaces, only address characters, at most 100; else "local" for empty. */
    public static String normalize(String address) {
        if (address == null) return LOCAL;
        String a = address.strip().toLowerCase(Locale.ROOT);
        if (a.isEmpty()) return LOCAL;
        if (!VALID.matcher(a).matches()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length() && sb.length() < 100; i++) {
                char c = a.charAt(i);
                sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == ':' || c == '-' ? c : '_');
            }
            a = sb.toString();
        }
        return a.isEmpty() ? LOCAL : a;
    }

    static boolean validKey(String key) {
        return key != null && VALID.matcher(key).matches();
    }

    private boolean consoleVisible = true;

    /** The Console is on or off for every server (off: no unread count). */
    public void consoleVisible(boolean visible) {
        consoleVisible = visible;
        for (ConversationStore s : byServer.values()) s.consoleVisible(visible);
    }

    public ConversationStore current() {
        return store(current);
    }

    public String currentKey() {
        return current;
    }

    public void switchTo(String address) {
        current = normalize(address);
        store(current);
    }

    /** The store of a server, made if there is none (the oldest idle server's goes when there are too many). */
    public ConversationStore store(String key) {
        ConversationStore s = byServer.get(key);
        if (s == null) {
            if (byServer.size() >= MAX_SERVERS) evict();
            s = new ConversationStore(cap);
            s.globalCap(globalCap);
            s.consoleVisible(consoleVisible);
            byServer.put(key, s);
        }
        return s;
    }

    public List<String> keys() {
        return new ArrayList<>(byServer.keySet());
    }

    public void cap(int cap) {
        this.cap = ConversationStore.clampCap(cap);
        for (ConversationStore s : byServer.values()) s.cap(this.cap);
    }

    public int cap() {
        return cap;
    }

    /** How many public chat lines are kept per server. */
    public void globalCap(int cap) {
        this.globalCap = ConversationStore.clampGlobalCap(cap);
        for (ConversationStore s : byServer.values()) s.globalCap(this.globalCap);
    }

    public int globalCap() {
        return globalCap;
    }

    public boolean dirty() {
        for (ConversationStore s : byServer.values()) {
            if (s.dirty()) return true;
        }
        return false;
    }

    /** The public chat of some server changed (saved on a long interval, or when leaving). */
    public boolean globalDirty() {
        for (ConversationStore s : byServer.values()) {
            if (s.globalDirty()) return true;
        }
        return false;
    }

    public boolean anyDirty() {
        return dirty() || globalDirty();
    }

    public void clean() {
        for (ConversationStore s : byServer.values()) s.clean();
    }

    /** Marks something to save (a message changed state). */
    public void touch() {
        current().touch();
    }

    public void clear() {
        for (ConversationStore s : byServer.values()) s.clear();
        byServer.keySet().removeIf(k -> !k.equals(current));
        store(current);
    }

    private void evict() {
        String oldest = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, ConversationStore> e : byServer.entrySet()) {
            if (e.getKey().equals(current)) continue;
            long t = 0;
            for (Conversation c : e.getValue().all()) t = Math.max(t, c.lastActivity());
            List<Message> pub = e.getValue().global();
            if (!pub.isEmpty()) t = Math.max(t, pub.get(pub.size() - 1).time());
            if (t < oldestTime) {
                oldestTime = t;
                oldest = e.getKey();
            }
        }
        if (oldest != null) byServer.remove(oldest);
    }
}
