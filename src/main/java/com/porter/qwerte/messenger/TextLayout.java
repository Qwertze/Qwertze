package com.porter.qwerte.messenger;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps a message into lines of text pieces and emoji pictures, for a given width. The font's measuring is passed in, so
 * this is testable. Emoji that have no picture stay text. No Minecraft classes.
 */
public final class TextLayout {
    /** How wide text is, and how wide one emoji picture is. */
    public interface Measure {
        int width(String text);

        int emojiWidth();
    }

    /** A run of text ({@code emojiFile} null) or one emoji picture. */
    public record Piece(String text, String emojiFile, int width) {
    }

    public record Line(List<Piece> pieces, int width) {
    }

    private TextLayout() {
    }

    public static List<Line> wrap(String text, int maxWidth, EmojiSet set, Measure m) {
        int max = Math.max(m.emojiWidth(), maxWidth);
        List<Line> lines = new ArrayList<>();
        List<Piece> cur = new ArrayList<>();
        int curW = 0;
        for (Piece token : tokens(text, set, m)) {
            boolean space = token.emojiFile() == null && token.text().isBlank();
            if (curW + token.width() > max) {
                if (!cur.isEmpty()) {
                    lines.add(finish(cur, curW));
                    cur = new ArrayList<>();
                    curW = 0;
                }
                if (space) continue; // no space at the start of a wrapped line
                if (token.width() > max && token.emojiFile() == null) {
                    // one word wider than the line: cut it
                    for (Piece part : cut(token.text(), max, m)) {
                        if (curW + part.width() > max && !cur.isEmpty()) {
                            lines.add(finish(cur, curW));
                            cur = new ArrayList<>();
                            curW = 0;
                        }
                        cur.add(part);
                        curW += part.width();
                    }
                    continue;
                }
            }
            cur.add(token);
            curW += token.width();
        }
        if (!cur.isEmpty() || lines.isEmpty()) lines.add(finish(cur, curW));
        return lines;
    }

    private static Line finish(List<Piece> pieces, int width) {
        // Trailing spaces do not count
        List<Piece> out = new ArrayList<>(pieces);
        int w = width;
        while (!out.isEmpty()) {
            Piece last = out.get(out.size() - 1);
            if (last.emojiFile() == null && last.text().isBlank()) {
                w -= last.width();
                out.remove(out.size() - 1);
            } else {
                break;
            }
        }
        return new Line(out, Math.max(0, w));
    }

    /** Words, spaces and emoji, each its own piece. */
    private static List<Piece> tokens(String text, EmojiSet set, Measure m) {
        List<Piece> out = new ArrayList<>();
        for (EmojiSegmenter.Segment seg : EmojiSegmenter.segment(text)) {
            if (seg.emoji()) {
                String file = set.fileFor(seg.text());
                if (file != null) {
                    out.add(new Piece(seg.text(), file, m.emojiWidth()));
                    continue;
                }
            }
            splitWords(visible(seg.text()), out, m);
        }
        return out;
    }

    private static void splitWords(String s, List<Piece> out, Measure m) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            int j = i;
            boolean space = s.charAt(i) == ' ';
            while (j < n && (s.charAt(j) == ' ') == space) j++;
            String part = s.substring(i, j);
            out.add(new Piece(part, null, m.width(part)));
            i = j;
        }
    }

    /** Splits a long word into pieces that each fit. */
    private static List<Piece> cut(String word, int max, Measure m) {
        List<Piece> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < word.length()) {
            int cp = word.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            if (sb.length() > 0 && m.width(sb + ch) > max) {
                out.add(new Piece(sb.toString(), null, m.width(sb.toString())));
                sb.setLength(0);
            }
            sb.append(ch);
            i += Character.charCount(cp);
        }
        if (sb.length() > 0) out.add(new Piece(sb.toString(), null, m.width(sb.toString())));
        return out;
    }

    /** Text without the invisible joiners and selectors that are left over from an emoji that has no picture. */
    private static String visible(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (!EmojiSegmenter.isInvisible(cp)) sb.appendCodePoint(cp);
        }
        return sb.toString();
    }
}
