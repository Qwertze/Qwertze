package com.porter.qwerte.manager.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The texts of the window's header, worded like the website's: the bot status ("Bot online - last seen 5 s ago") and the
 * stash index line ("Indexed 1 h ago - Containers: 47 / 48 known - 1 stale - minecraft:overworld - MC 1.21.4"). Pure.
 */
public final class HeaderInfo {
    public static final String SEP = " · ";

    private HeaderInfo() {
    }

    /** "Bot online, last seen 5 s ago", with the bot's name instead of "Bot" when names are not hidden. */
    public static String botStatus(StashRef s, boolean hideNames, long now) {
        if (s == null) return "No stash";
        String who = !hideNames && !s.player().isEmpty() ? Text.sanitize(s.player(), 24) + " " : "Bot ";
        return who + (s.online() ? "online" : "offline") + SEP + "last seen " + seen(s.lastSeen(), now);
    }

    /** "5 s ago", or "never" when the server has no time. */
    public static String seen(long lastSeen, long now) {
        return lastSeen < 0 ? "never" : Text.ago(Math.max(0, now - lastSeen));
    }

    /** "Indexed 5 min ago"; the word "ago" comes from {@link Text#ago}, once. */
    public static String indexed(StashRef s, long now) {
        if (s == null) return "";
        return s.hasIndex() ? "Indexed " + Text.ago(Math.max(0, now - s.indexedAt())) : "No index yet";
    }

    /**
     * The whole meta line. {@code idx} may be null (not loaded yet); the container counts then come from the stash entry
     * when it has them. The dimension is left out when {@code hideDimension} (the owner forced streamer mode).
     */
    public static String indexLine(StashRef s, IndexModel idx, boolean hideDimension, long now) {
        if (s == null) return "";
        List<String> parts = new ArrayList<>();
        parts.add(indexed(s, now));
        if (idx != null && idx.containersTotal() >= 0 && idx.containersKnown() >= 0) {
            parts.add("Containers: " + Text.grouped(idx.containersKnown()) + " / " + Text.grouped(idx.containersTotal()) + " known");
            if (idx.containersStale() >= 0) parts.add(Text.grouped(idx.containersStale()) + " stale");
        } else if (s.containersTotal() >= 0) {
            parts.add("Containers: " + Text.grouped(s.containersTotal()));
            if (s.containersStale() >= 0) parts.add(Text.grouped(s.containersStale()) + " stale");
        }
        if (idx != null) {
            if (!hideDimension && !idx.dimension().isEmpty()) parts.add(idx.dimension());
            if (!idx.mcVersion().isEmpty()) parts.add("MC " + idx.mcVersion());
        }
        return String.join(SEP, parts);
    }
}
