package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Forgiving readers for server JSON: a wrong type gives the default, never an exception. */
public final class Json {
    private Json() {
    }

    public static JsonObject obj(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonArray arr(JsonElement e) {
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    public static String str(JsonObject o, String key) {
        if (o == null) return "";
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : "";
    }

    public static boolean bool(JsonObject o, String key) {
        if (o == null) return false;
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    public static double num(JsonObject o, String key, double def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return def;
        double d = e.getAsDouble();
        return Double.isFinite(d) ? d : def;
    }

    public static long lng(JsonObject o, String key, long def) {
        return (long) num(o, key, def);
    }
}
