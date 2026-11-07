package com.porter.qwerte.manager.model;

import java.util.regex.Pattern;

/** The shape of the ids the server hands out (bot ids, user ids): anything else is dropped when parsed, and never reaches a request path. */
public final class Ids {
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private Ids() {
    }

    public static boolean valid(String id) {
        return id != null && ID.matcher(id).matches();
    }
}
