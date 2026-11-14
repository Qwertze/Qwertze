package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The server's coordinate guard (FARM-CONTRACT P3, TASK-CONTRACT G1 and G2). No world position may reach the server in a
 * task, sync, report or farm body. G1 refuses a key named like a coordinate at any depth; G2 refuses a string that looks
 * like one. A rejected value is never echoed, logged or audited: the answer names the code (and for G1 the fixed key
 * name from {@link #KEYS}, never the caller's spelling or value). There are no exceptions: a checkpoint names its anchor
 * with the key {@code anchorId} (draft 2.4).
 */
final class PositionGuard {
    /** G1: whole key names, compared case-insensitively. */
    static final Set<String> KEYS = Set.of("x", "y", "z", "pos", "position", "origin", "coords", "anchor");
    /** G2: the plugin's ActivityFeed.POSITION (the same text as farm-vectors.json serverGuard.stringPattern). */
    static final Pattern POSITION = Pattern.compile(
        "x=-?\\d+,?\\s*y=-?\\d+,?\\s*z=-?\\d+|(?<![\\w.\\-])-?\\d{1,8}(?:\\s*,\\s*|\\s+)-?\\d{1,4}(?:\\s*,\\s*|\\s+)-?\\d{1,8}(?![\\w.])");
    /** Nesting deeper than this is refused outright (a coordinate could hide below a depth nobody checks). */
    static final int MAX_DEPTH = 32;

    static final String FIELD = "position_field";
    static final String LIKE = "position_like";
    static final String DEEP = "bad_field";

    /** What a check found: a code and, for G1, the matched constant key. */
    record Hit(String code, String key) {
    }

    private PositionGuard() {
    }

    /** Whether one user string looks like a position (G2). */
    static boolean looksLikePosition(String s) {
        return s != null && POSITION.matcher(s).find();
    }

    /** G1 and G2 over a whole parsed body: null when clean. Key names are checked before values. */
    static Hit check(JsonElement body) {
        return walk(body, 0);
    }

    private static Hit walk(JsonElement e, int depth) {
        if (e == null || e.isJsonNull()) return null;
        if (depth > MAX_DEPTH) return new Hit(DEEP, null);
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            for (Map.Entry<String, JsonElement> en : o.entrySet()) {
                String k = en.getKey().toLowerCase(Locale.ROOT);
                if (KEYS.contains(k)) return new Hit(FIELD, k);
            }
            for (String key : o.keySet()) {
                if (looksLikePosition(key)) return new Hit(LIKE, null);
            }
            for (Map.Entry<String, JsonElement> en : o.entrySet()) {
                Hit h = walk(en.getValue(), depth + 1);
                if (h != null) return h;
            }
        } else if (e.isJsonArray()) {
            for (JsonElement c : e.getAsJsonArray()) {
                Hit h = walk(c, depth + 1);
                if (h != null) return h;
            }
        } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            if (looksLikePosition(e.getAsString())) return new Hit(LIKE, null);
        }
        return null;
    }
}
