package com.porter.qwerte.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The shared kit-items table (KIT-CONTRACT.md "kit-items.json"): the OP item ids and the item-mix groups. It is data, copied byte for
 * byte from {@code Desktop\qwerte-release\kit-items.json} into {@code src/main/resources/kit-items.json}; the plugin has the same file.
 * Loaded once. Ids unknown to a game version never match. Immutable, any thread.
 */
public final class KitItems {
    /** One mix group: a box has the mix when it holds at least {@code min} distinct ids of {@code items}. */
    public record Group(String id, int min, Set<String> items) {
    }

    private final Set<String> op;
    private final List<Group> groups;

    public KitItems(Set<String> op, List<Group> groups) {
        this.op = Collections.unmodifiableSet(new TreeSet<>(op));
        this.groups = List.copyOf(groups);
    }

    public Set<String> op() {
        return op;
    }

    public List<Group> groups() {
        return groups;
    }

    /** Reads a table; throws {@link IOException} for anything that is not one. */
    public static KitItems parse(Reader reader) throws IOException {
        try {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            Set<String> op = new TreeSet<>();
            for (JsonElement e : root.getAsJsonArray("op")) op.add(e.getAsString());
            List<Group> groups = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("groups")) {
                JsonObject g = e.getAsJsonObject();
                Set<String> items = new TreeSet<>();
                JsonArray array = g.getAsJsonArray("items");
                for (JsonElement i : array) items.add(i.getAsString());
                int min = g.get("min").getAsInt();
                if (min < 1) throw new IllegalArgumentException("a group needs a min of at least 1");
                groups.add(new Group(g.get("id").getAsString(), min, items));
            }
            return new KitItems(op, groups);
        } catch (RuntimeException ex) {
            throw new IOException("not a kit-items table: " + ex.getClass().getSimpleName(), ex);
        }
    }

    /**
     * SHA-256 hex of {@code "op:" + sorted(op).join(",") + "\n"}, then {@code id + ":" + min + ":" + sorted(items).join(",") + "\n"} for
     * each group sorted by id. The value of table version 1 is {@link #FINGERPRINT_V1}.
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder("op:").append(String.join(",", new TreeSet<>(op))).append('\n');
        List<Group> sorted = new ArrayList<>(groups);
        sorted.sort((a, b) -> a.id().compareTo(b.id()));
        for (Group g : sorted) sb.append(g.id()).append(':').append(g.min()).append(':').append(String.join(",", new TreeSet<>(g.items()))).append('\n');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final String FINGERPRINT_V1 = "ed47369c84e5a477e55669f4a9be7f47217a50499d7955d2da7c1959d07e1bf7";

    private static volatile KitItems table;

    /** The table in the jar, read once. With no readable table nothing has a mix or an OP item (names and fullness still count). */
    public static KitItems table() {
        KitItems t = table;
        if (t == null) {
            synchronized (KitItems.class) {
                t = table;
                if (t == null) {
                    try (InputStream in = KitItems.class.getResourceAsStream("/kit-items.json")) {
                        if (in == null) throw new IOException("kit-items.json is missing");
                        t = parse(new InputStreamReader(in, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        com.mojang.logging.LogUtils.getLogger().error("[kit-sorting] couldn't read kit-items.json: {}", e.getMessage());
                        t = new KitItems(Set.of(), List.of());
                    }
                    table = t;
                }
            }
        }
        return t;
    }
}
