package com.porter.stashmanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** The shared Gson instance: nulls are written (the API lists null fields), no HTML escaping. */
final class Json {
    static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private Json() {
    }
}
