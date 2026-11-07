package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Parses the answer of {@code GET /api/v1/stashes}. */
public final class StashList {
    private static final int MAX = 256;

    private StashList() {
    }

    /** Never null. Entries without a server and stash name are skipped. */
    public static List<StashRef> parse(JsonElement body) {
        List<StashRef> out = new ArrayList<>();
        JsonArray array = Json.arr(body);
        if (array == null) return out;
        for (JsonElement e : array) {
            JsonObject o = Json.obj(e);
            if (o == null) continue;
            StashRef ref = StashRef.parse(o);
            if (ref != null) out.add(ref);
            if (out.size() >= MAX) break;
        }
        return out;
    }
}
