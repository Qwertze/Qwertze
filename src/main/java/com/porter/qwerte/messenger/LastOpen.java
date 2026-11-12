package com.porter.qwerte.messenger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What was open in the Messenger window last, per server, so the focus key can bring it back (also after a restart).
 * Saved with the module as one string, {@code server=what;server=what}, where "what" is {@link #GLOBAL},
 * {@link #CONSOLE} or a player name. At most {@link ServerStores#MAX_SERVERS} servers are kept. Pure.
 */
public final class LastOpen {
    public static final String GLOBAL = "#global";
    public static final String CONSOLE = "#console";

    private final Map<String, String> byServer = new LinkedHashMap<>();

    /** The remembered selection of a server, or null. */
    public String get(String server) {
        return byServer.get(server);
    }

    /** Remembers it ({@code what}: {@link #GLOBAL}, {@link #CONSOLE} or a player name); anything else is ignored. Returns whether it changed. */
    public boolean put(String server, String what) {
        if (!ServerStores.validKey(server) || !validWhat(what)) return false;
        String key = ServerStores.normalize(server);
        if (what.equals(byServer.get(key))) return false;
        byServer.remove(key); // the newest goes last
        byServer.put(key, what);
        while (byServer.size() > ServerStores.MAX_SERVERS) byServer.remove(byServer.keySet().iterator().next());
        return true;
    }

    public boolean isEmpty() {
        return byServer.isEmpty();
    }

    private static boolean validWhat(String what) {
        return GLOBAL.equals(what) || CONSOLE.equals(what) || ChatText.isValidName(what);
    }

    public String encode() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : byServer.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Reads {@link #encode()}; a malformed entry is skipped. Null or empty gives an empty memory. */
    public static LastOpen decode(String s) {
        LastOpen l = new LastOpen();
        if (s == null || s.isBlank() || s.length() > 10_000) return l;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            l.put(part.substring(0, eq), part.substring(eq + 1));
        }
        return l;
    }
}
