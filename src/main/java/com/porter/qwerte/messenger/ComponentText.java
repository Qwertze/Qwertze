package com.porter.qwerte.messenger;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The plain text of a chat message as the Messenger shows and parses it. {@code Component.getString()} also gives the
 * fallback text of an inline picture: since 1.21.9 a player head in chat is an "object" component whose text is
 * "[Name head]", which read as the name twice ("&lt;[vbkk head]vbkk&gt;"). Here every piece is looked at with its style, and
 * a piece drawn with a sprite font (player head, atlas sprite) is left out. The head the Messenger draws is taken from
 * the parsed sender instead.
 */
public final class ComponentText {
    private ComponentText() {
    }

    /** One piece of text with whether it is a picture (a sprite font) in the game. */
    public record Part(boolean sprite, String text) {
    }

    /** The text of the pieces, without the pictures. Pure part of {@link #plain}. */
    public static String join(List<Part> parts) {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            if (!p.sprite() && p.text() != null) sb.append(p.text());
        }
        return sb.toString();
    }

    public static List<Part> parts(Component c) {
        List<Part> parts = new ArrayList<>();
        if (c == null) return parts;
        c.visit((Style style, String text) -> {
            parts.add(new Part(FontKinds.isSprite(style), text));
            return Optional.empty();
        }, Style.EMPTY);
        return parts;
    }

    /** The plain text of a message without inline pictures; "" for null. */
    public static String plain(Component c) {
        return join(parts(c));
    }

    /** A compact outline of the component tree for the debug log, cut at {@code max} characters. */
    public static String structure(Component c, int max) {
        StringBuilder sb = new StringBuilder();
        describe(c, sb, max, 0);
        if (sb.length() > max) {
            sb.setLength(max);
            sb.append("...");
        }
        return sb.toString();
    }

    private static void describe(Component c, StringBuilder sb, int max, int depth) {
        if (sb.length() > max || depth > 8) return;
        if (c == null) {
            sb.append("null");
            return;
        }
        var contents = c.getContents();
        sb.append(contents.getClass().getSimpleName().replace("Contents", ""));
        if (contents instanceof PlainTextContents lc) {
            sb.append('"').append(escape(lc.text(), 512)).append('"');
        } else if (contents instanceof TranslatableContents tc) {
            sb.append('(').append(tc.getKey());
            for (Object arg : tc.getArgs()) {
                if (sb.length() > max) break;
                sb.append(", ");
                if (arg instanceof Component ac) describe(ac, sb, max, depth + 1);
                else sb.append('"').append(escape(String.valueOf(arg), 512)).append('"');
            }
            sb.append(')');
        } else {
            sb.append('"').append(escape(plainOfContents(c), 512)).append('"');
        }
        Style style = c.getStyle();
        if (style != null && !style.isEmpty()) {
            String font = String.valueOf(style.getFont());
            if (!font.contains("minecraft:default")) sb.append(" font=").append(font);
            if (style.getInsertion() != null) sb.append(" ins=").append(escape(style.getInsertion(), 64));
        }
        List<Component> sib = c.getSiblings();
        if (!sib.isEmpty()) {
            sb.append(" [");
            for (int i = 0; i < sib.size() && sb.length() <= max; i++) {
                if (i > 0) sb.append(" | ");
                describe(sib.get(i), sb, max, depth + 1);
            }
            sb.append(']');
        }
    }

    /** The text of just this component's own contents (not its siblings), for kinds that are not literal or translatable. */
    private static String plainOfContents(Component c) {
        StringBuilder sb = new StringBuilder();
        c.getContents().visit(s -> {
            sb.append(s);
            return Optional.empty();
        });
        return sb.toString();
    }

    /** Printable text; control characters and private-use glyphs as a hex escape so a hidden glyph shows in the log. */
    public static String escape(String s) {
        return escape(s, Integer.MAX_VALUE);
    }

    /**
     * Like {@link #escape(String)} for a log line: at most {@code max} characters of the text are taken (then "..."),
     * and a quote, a backslash, a line break (also U+2028, U+2029, U+0085) or another control character is escaped, so
     * the text can not end the quoted value or start a log line of its own.
     */
    public static String escape(String s, int max) {
        if (s == null) return "null";
        int n = Math.min(s.length(), max);
        StringBuilder sb = new StringBuilder(n + 8);
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch < 0x20 || ch == 0x7F || ch == 0x85 || ch == 0x2028 || ch == 0x2029 || ch == 0xFFFC || (ch >= 0xE000 && ch <= 0xF8FF)) sb.append(String.format("\\u%04X", (int) ch));
            else sb.append(ch);
        }
        if (s.length() > max) sb.append("...");
        return sb.toString();
    }
}
