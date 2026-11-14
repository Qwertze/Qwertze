package com.porter.qwerte.core.farm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Loads farm-vectors.json from the test resources (a copy of qwerte-release/farm-vectors.json; see VectorsSyncTest). */
final class Vectors {
    static final String RESOURCE = "farm-vectors.json";

    private Vectors() {
    }

    static byte[] raw() {
        try (InputStream in = Vectors.class.getResourceAsStream(RESOURCE)) {
            return in.readAllBytes();
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("farm-vectors.json missing from test resources");
        }
    }

    static JsonObject load() {
        return JsonParser.parseString(new String(raw(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static byte[] hex(String s) {
        return HexFormat.of().parseHex(s);
    }

    static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }
}
