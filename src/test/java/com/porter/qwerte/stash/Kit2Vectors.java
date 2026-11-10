package com.porter.qwerte.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Test helper: the shared vector files of the kit contract (test resources {@code kit2/}, copies of the contract's files byte for byte) and the
 * conversion of the structured stacks in them into what the addon keys. A real item stack needs the game, so here the match key is made from the
 * stack's structured components with the contract's strip set applied by name (custom name, damage, repair cost, custom data, container and
 * bundle contents) and the rest hashed in a fixed order; the vectors then judge the matching by meaning. The real strip over the game's own
 * component patch is tested in {@code KitMatchKeyTest}.
 */
public final class Kit2Vectors {
    private Kit2Vectors() {
    }

    /** The components the match key leaves out (the contract's strip set; block_entity_data only for shulker boxes). */
    private static final Set<String> STRIPPED = Set.of("minecraft:custom_name", "minecraft:damage", "minecraft:repair_cost", "minecraft:custom_data", "minecraft:container",
        "minecraft:bundle_contents");

    public static JsonObject read(String name) {
        try (InputStream in = Kit2Vectors.class.getResourceAsStream("/kit2/" + name)) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The match key of a vector stack. */
    public static String matchKey(JsonObject stack) {
        String id = stack.get("id").getAsString();
        TreeMap<String, String> kept = new TreeMap<>();
        if (stack.has("components")) {
            for (var e : stack.getAsJsonObject("components").entrySet()) {
                if (!STRIPPED.contains(e.getKey())) kept.put(e.getKey(), e.getValue().toString());
            }
        }
        if (kept.isEmpty()) return id;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            for (var e : kept.entrySet()) sha.update((e.getKey() + "\0" + e.getValue() + "ÿ").getBytes(StandardCharsets.UTF_8));
            return id + "#" + HexFormat.of().formatHex(sha.digest()).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The occupied slots of a vector box, in slot order, as the classifier and the template match take them. */
    public static List<KitTemplates.BoxStack> box(JsonArray slots) {
        List<KitTemplates.BoxStack> out = new ArrayList<>();
        for (JsonElement e : slots) {
            if (e.isJsonNull()) continue;
            JsonObject s = e.getAsJsonObject();
            out.add(new KitTemplates.BoxStack(s.get("id").getAsString(), matchKey(s), s.get("count").getAsInt()));
        }
        return out;
    }

    /** A template made of a vector's slots: an {@code any} slot takes any variant, the others the exact match key. */
    public static KitTemplate template(String name, JsonArray slots) {
        List<KitTemplate.Slot> out = new ArrayList<>();
        for (JsonElement e : slots) {
            if (e.isJsonNull()) {
                out.add(null);
                continue;
            }
            JsonObject s = e.getAsJsonObject();
            String id = s.get("id").getAsString();
            if (s.has("any") && s.get("any").getAsBoolean()) out.add(new KitTemplate.Slot(id + "#*", id + "#*", s.get("count").getAsInt()));
            else {
                String match = matchKey(s);
                out.add(new KitTemplate.Slot(match, match, s.get("count").getAsInt()));
            }
        }
        return new KitTemplate("t-00000001", name, "pvp", null, null, 0, java.util.Collections.unmodifiableList(out));
    }
}
