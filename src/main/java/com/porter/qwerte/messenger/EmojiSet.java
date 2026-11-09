package com.porter.qwerte.messenger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The emoji that have a picture in the jar: Twemoji PNGs under {@code assets/qwerte/textures/emoji/<file>.png}, listed in
 * {@code assets/qwerte/emoji/index.txt} (one file name without ".png" per line, in the picker's order). Anything not in
 * the set is shown as text. No Minecraft classes.
 */
public final class EmojiSet {
    public static final String INDEX = "/assets/qwerte/emoji/index.txt";
    public static final String TEXTURE_DIR = "/assets/qwerte/textures/emoji/";

    private static EmojiSet bundled;

    // normalized key (no variation selectors) -> file name
    private final Map<String, String> byKey = new LinkedHashMap<>();
    private final List<String> view;

    public EmojiSet(List<String> files) {
        for (String f : files) {
            String file = f.strip().toLowerCase(java.util.Locale.ROOT);
            if (!file.matches("[0-9a-f]{2,6}(-[0-9a-f]{2,6}){0,9}")) continue;
            String key = EmojiSegmenter.key(EmojiSegmenter.fromKey(file));
            byKey.putIfAbsent(key, file);
        }
        view = Collections.unmodifiableList(new ArrayList<>(byKey.values()));
    }

    /** The set in the jar (read once). Empty if the index is missing. */
    public static synchronized EmojiSet bundled() {
        if (bundled == null) {
            List<String> lines = new ArrayList<>();
            try (InputStream in = EmojiSet.class.getResourceAsStream(INDEX)) {
                if (in != null) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    String line;
                    while ((line = r.readLine()) != null && lines.size() < 1000) lines.add(line);
                }
            } catch (IOException ignored) {
                // an empty set: every emoji is shown as text
            }
            bundled = new EmojiSet(lines);
        }
        return bundled;
    }

    /** The picture file of an emoji sequence (as {@link EmojiSegmenter} cuts them), or null if there is none. */
    public String fileFor(String emoji) {
        return byKey.get(EmojiSegmenter.key(emoji));
    }

    /** The files in the picker's order. */
    public List<String> files() {
        return view;
    }

    public int size() {
        return byKey.size();
    }

    /** The characters the picker inserts for a file. */
    public static String textOf(String file) {
        return EmojiSegmenter.fromKey(file);
    }
}
