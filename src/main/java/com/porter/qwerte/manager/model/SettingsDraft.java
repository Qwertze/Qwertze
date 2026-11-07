package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The unsaved edits of one settings form, by path (the website's {@code F.edits}). An edit equal to the server's value is
 * not an edit; an edit the field could not turn into a value is "invalid" and blocks saving. Saving sends every edit in one
 * batch, each with the value the page showed ({@code expect}) so the bot can refuse a change made elsewhere meanwhile. Pure.
 */
public final class SettingsDraft {
    private final Map<String, JsonElement> edits = new LinkedHashMap<>();
    private final Set<String> invalid = new HashSet<>();

    /**
     * Records the value of a field. {@code server} is what the server has now (null means unset).
     * @return true when this is a change from the server's value
     */
    public boolean set(String path, JsonElement value, JsonElement server) {
        invalid.remove(path);
        if (same(value, server)) {
            edits.remove(path);
            return false;
        }
        edits.put(path, value == null ? JsonNull.INSTANCE : value);
        return true;
    }

    /** The field holds something that is not a value; saving is blocked until it is fixed or reverted. */
    public void markInvalid(String path) {
        edits.remove(path);
        invalid.add(path);
    }

    public void remove(String path) {
        edits.remove(path);
        invalid.remove(path);
    }

    public void clear() {
        edits.clear();
        invalid.clear();
    }

    public boolean has(String path) {
        return edits.containsKey(path) || invalid.contains(path);
    }

    public boolean isInvalid(String path) {
        return invalid.contains(path);
    }

    public JsonElement get(String path) {
        return edits.get(path);
    }

    /** Edits plus invalid fields. */
    public int size() {
        return edits.size() + invalid.size();
    }

    public boolean hasInvalid() {
        return !invalid.isEmpty();
    }

    public Set<String> paths() {
        Set<String> all = new java.util.LinkedHashSet<>(edits.keySet());
        all.addAll(invalid);
        return all;
    }

    /** Keeps only the edits whose path {@code stillEditable} accepts (the tree changed: a setting went or became read-only). */
    public void retain(java.util.function.Predicate<String> stillEditable) {
        edits.keySet().removeIf(p -> !stillEditable.test(p));
        invalid.removeIf(p -> !stillEditable.test(p));
    }

    /**
     * The body's {@code changes}: {@code [{path, value, expect}]}. {@code server} gives the value the page showed for a path
     * (null for unset).
     */
    public JsonArray changes(Function<String, JsonElement> server) {
        JsonArray out = new JsonArray();
        for (Map.Entry<String, JsonElement> e : edits.entrySet()) {
            JsonObject c = new JsonObject();
            c.addProperty("path", e.getKey());
            c.add("value", e.getValue());
            JsonElement expect = server.apply(e.getKey());
            c.add("expect", expect == null ? JsonNull.INSTANCE : expect);
            out.add(c);
        }
        return out;
    }

    /** True when both mean the same setting value (unset, a number written two ways, equal lists). */
    public static boolean same(JsonElement a, JsonElement b) {
        JsonElement x = a == null ? JsonNull.INSTANCE : a;
        JsonElement y = b == null ? JsonNull.INSTANCE : b;
        return x.equals(y);
    }
}
