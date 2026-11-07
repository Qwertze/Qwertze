package com.porter.qwerte.manager.net;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Reads the website's session cookie from {@code Set-Cookie} headers. */
public final class Cookies {
    public static final String NAME = "sm_session";

    private Cookies() {
    }

    /**
     * The value of the {@code sm_session} cookie the server set, or empty when it set none, cleared it (an empty value
     * or {@code Max-Age=0}) or sent something that is not a plain token. The last header about the cookie wins.
     */
    public static Optional<String> session(List<String> setCookieHeaders) {
        Optional<String> result = Optional.empty();
        if (setCookieHeaders == null) return result;
        for (String header : setCookieHeaders) {
            if (header == null) continue;
            String[] parts = header.split(";");
            String first = parts[0].trim();
            int eq = first.indexOf('=');
            if (eq <= 0 || !first.substring(0, eq).trim().equals(NAME)) continue;
            String value = first.substring(eq + 1).trim();
            boolean cleared = value.isEmpty();
            for (int i = 1; i < parts.length; i++) {
                String attr = parts[i].trim().toLowerCase(Locale.ROOT).replace(" ", "");
                if (attr.equals("max-age=0") || attr.startsWith("max-age=-")) cleared = true;
            }
            result = cleared || !plainToken(value) ? Optional.empty() : Optional.of(value);
        }
        return result;
    }

    /** base64url characters only, so the value can't carry anything into a header. */
    static boolean plainToken(String v) {
        if (v.isEmpty() || v.length() > 256) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '=';
            if (!ok) return false;
        }
        return true;
    }
}
