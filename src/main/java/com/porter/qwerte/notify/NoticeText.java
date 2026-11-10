package com.porter.qwerte.notify;

import java.util.List;
import java.util.Locale;

/**
 * The words of a notification. Pure. It holds order ids and item names, never a coordinate of its own; in streamer mode it also
 * holds no bot text (which could name a position) and no stash name.
 */
public final class NoticeText {
    private NoticeText() {}

    public record Text(String title, String body) {
        /** The chat line: title and body in one. */
        public String line() {
            return body.isEmpty() ? title : title + ": " + body;
        }
    }

    public static Text of(OrderWatch.Notice n, String stash, boolean streamer) {
        OrderSnap o = n.order();
        String title = switch (n.kind()) {
            case COMPLETE -> "Order delivered";
            case PARTIAL -> "Order delivered, with shortfalls";
            case FAILED -> "Order failed";
            case CANCELLED -> "Order cancelled";
            case ARRIVING -> "Delivery arriving";
        };
        StringBuilder b = new StringBuilder(safe(o.id(), 40));
        if (!streamer && stash != null && !stash.isEmpty()) b.append(" at ").append(safe(stash, 40));
        if (n.kind() == Kind.PARTIAL) {
            String s = shorts(o.shorts());
            if (!s.isEmpty()) b.append(". Short: ").append(s);
        } else if ((n.kind() == Kind.FAILED || n.kind() == Kind.CANCELLED) && !streamer && !o.message().isEmpty()) {
            b.append(". ").append(safe(o.message(), 100));
        }
        return new Text(title, b.toString());
    }

    /** "oak planks x12, stone x3, dirt and 2 more" */
    static String shorts(List<OrderSnap.Short> list) {
        StringBuilder b = new StringBuilder();
        int shown = 0;
        for (OrderSnap.Short s : list) {
            if (shown == 3) break;
            if (shown > 0) b.append(", ");
            b.append(itemName(s.key()));
            if (s.count() > 0) b.append(" x").append(s.count());
            shown++;
        }
        if (list.size() > shown) b.append(" and ").append(list.size() - shown).append(" more");
        return b.toString();
    }

    /** "minecraft:oak_planks#1a2b3c4d" to "oak planks". */
    static String itemName(String key) {
        String k = key;
        int hash = k.indexOf('#');
        if (hash >= 0) k = k.substring(0, hash);
        if (k.startsWith("minecraft:")) k = k.substring("minecraft:".length());
        return safe(k.replace('_', ' ').toLowerCase(Locale.ROOT), 40);
    }

    /**
     * Safe for a chat line: chat messages here go through String.format, and Meteor reads parentheses as styling, so no percent
     * signs or parentheses; no control characters; capped.
     */
    static String safe(String s, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            if (c < 32 || c == 127 || c == '§') continue;
            if (c == '%') b.append("pct");
            else if (c == '(' || c == ')') b.append(' ');
            else b.append(c);
        }
        return b.toString().trim();
    }
}
