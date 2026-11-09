package com.porter.qwerte.manager.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.manager.map.ViewerEvent.ChatLine;
import com.porter.qwerte.manager.map.ViewerEvent.Ent;
import com.porter.qwerte.manager.map.ViewerEvent.Hud;
import com.porter.qwerte.manager.map.ViewerEvent.Self;
import com.porter.qwerte.manager.map.ViewerEvent.Slot;
import com.porter.qwerte.manager.map.ViewerEvent.Tile;
import com.porter.qwerte.manager.map.ViewerEvent.World;
import com.porter.qwerte.manager.model.Json;
import com.porter.qwerte.manager.model.Text;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns one server-sent event (its name and its data line) into a {@link ViewerEvent}. Forgiving like the website: a field
 * of the wrong type is ignored, a tile that is not exactly what the protocol says is skipped, and an event that is not
 * JSON (or is unknown) gives null. Runs on the stream's thread.
 */
public final class ViewerEvents {
    private static final Pattern ITEM_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    private static final Pattern MC_VERSION = Pattern.compile("^[0-9][0-9.]{0,31}$");
    private static final int MAX_TILES = 4096;
    private static final int MAX_ENTS = 512;
    private static final int MAX_CHAT = 200;
    private static final int COORD_LIMIT = 2_000_000;

    private ViewerEvents() {
    }

    /** @return the event, or null for an unknown or unreadable one */
    public static ViewerEvent parse(String name, String data) {
        if (name == null || data == null) return null;
        JsonObject o;
        try {
            o = Json.obj(JsonParser.parseString(data));
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
        if (o == null) return null;
        switch (name) {
            case "hello": {
                String mc = Json.str(o, "mc");
                return new ViewerEvent.Hello(Text.sanitize(Json.str(o, "botId"), 80), MC_VERSION.matcher(mc).matches() ? mc : "", Json.bool(o, "relative"));
            }
            case "snapshot":
                return new ViewerEvent.Snapshot(Text.sanitize(Json.str(o, "session"), 32), world(Json.obj(o.get("world"))), self(o.get("self")),
                    tiles(o.get("tiles")), ents(o.get("ents")), hud(o.get("hud")), chat(o.get("chat")), Json.bool(o, "relative"));
            case "tiles":
                return new ViewerEvent.Tiles(tiles(o.get("tiles")), drops(o.get("drop")));
            case "ents":
                return new ViewerEvent.Ents(self(o.get("self")), ents(o.get("ents")));
            case "hud":
                return new ViewerEvent.HudUpdate(hud(o.get("hud")));
            case "chat":
                return new ViewerEvent.Chat(chat(o.get("lines")));
            case "status":
                return new ViewerEvent.Status(Text.sanitize(Json.str(o, "state"), 16));
            case "bye":
                return new ViewerEvent.Bye(Text.sanitize(Json.str(o, "reason"), 16));
            default:
                return null;
        }
    }

    private static World world(JsonObject o) {
        if (o == null) return null;
        return new World(Text.sanitize(Json.str(o, "dim"), 64), (int) Json.num(o, "minY", -64), (int) Json.num(o, "height", 384));
    }

    static Self self(JsonElement e) {
        JsonObject o = Json.obj(e);
        if (o == null || !number(o, "x") || !number(o, "z")) return null;
        return new Self(Json.num(o, "x", 0), Json.num(o, "y", 0), Json.num(o, "z", 0), Json.num(o, "yaw", 0));
    }

    private static boolean number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && Double.isFinite(e.getAsDouble());
    }

    static List<Tile> tiles(JsonElement e) {
        JsonArray a = Json.arr(e);
        List<Tile> out = new ArrayList<>();
        if (a == null) return out;
        for (JsonElement t : a) {
            if (out.size() >= MAX_TILES) break;
            Tile tile = tile(Json.obj(t));
            if (tile != null) out.add(tile);
        }
        return out;
    }

    /** One tile; null when its position or its 256 colour bytes are not right. The heights are optional. */
    public static Tile tile(JsonObject o) {
        if (o == null || !number(o, "x") || !number(o, "z")) return null;
        double x = Json.num(o, "x", 0);
        double z = Json.num(o, "z", 0);
        if (x != Math.rint(x) || z != Math.rint(z) || Math.abs(x) > COORD_LIMIT || Math.abs(z) > COORD_LIMIT) return null;
        byte[] colors = decode(Json.str(o, "c"), 256);
        if (colors == null) return null;
        short[] heights = null;
        byte[] h = decode(Json.str(o, "h"), 512);
        if (h != null) {
            heights = new short[256];
            ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(heights);
        }
        return new Tile((int) x, (int) z, colors, heights);
    }

    /** Standard base64 of exactly {@code size} bytes, or null. */
    public static byte[] decode(String b64, int size) {
        if (b64 == null || b64.isEmpty() || b64.length() > size * 2) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(b64);
            return bytes.length == size ? bytes : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<int[]> drops(JsonElement e) {
        JsonArray a = Json.arr(e);
        List<int[]> out = new ArrayList<>();
        if (a == null) return out;
        for (JsonElement p : a) {
            JsonArray pair = Json.arr(p);
            if (pair == null || pair.size() != 2) continue;
            try {
                if (!pair.get(0).isJsonPrimitive() || !pair.get(1).isJsonPrimitive()) continue;
                double x = pair.get(0).getAsDouble();
                double z = pair.get(1).getAsDouble();
                if (x != Math.rint(x) || z != Math.rint(z) || Math.abs(x) > COORD_LIMIT || Math.abs(z) > COORD_LIMIT) continue;
                out.add(new int[] {(int) x, (int) z});
            } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException ignored) {
                // not a coordinate
            }
            if (out.size() >= MAX_TILES) break;
        }
        return out;
    }

    static List<Ent> ents(JsonElement e) {
        JsonArray a = Json.arr(e);
        List<Ent> out = new ArrayList<>();
        if (a == null) return out;
        for (JsonElement x : a) {
            if (out.size() >= MAX_ENTS) break;
            JsonObject o = Json.obj(x);
            if (o == null || !number(o, "x") || !number(o, "z")) continue;
            String kind = Json.str(o, "k");
            if (!kind.equals("player") && !kind.equals("mob") && !kind.equals("item")) kind = "other";
            out.add(new Ent((int) Json.num(o, "id", 0), kind, Text.sanitize(Json.str(o, "n"), 16), Text.sanitize(Json.str(o, "t"), 64),
                (int) Math.max(0, Math.min(100_000, Json.num(o, "c", 1))), Json.num(o, "x", 0), Json.num(o, "y", 0), Json.num(o, "z", 0), Json.num(o, "yaw", 0)));
        }
        return out;
    }

    static Hud hud(JsonElement e) {
        JsonObject o = Json.obj(e);
        if (o == null) return null;
        double held = Json.num(o, "held", -1);
        return new Hud(num(o, "hp"), num(o, "food"), num(o, "sat"), num(o, "xp"), num(o, "xpP"), num(o, "armor"),
            held >= 0 && held <= 8 ? (int) held : -1, slots(o.get("hotbar"), 9), slots(o.get("armorSlots"), 4), slot(o.get("offhand")),
            // like the website: the 27 inventory slots count only when all 27 are there
            Json.arr(o.get("inv")) != null && Json.arr(o.get("inv")).size() == 27 ? slots(o.get("inv"), 27) : slots(null, 27));
    }

    private static double num(JsonObject o, String key) {
        return number(o, key) ? Json.num(o, key, Double.NaN) : Double.NaN;
    }

    private static List<Slot> slots(JsonElement e, int n) {
        JsonArray a = Json.arr(e);
        List<Slot> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(a != null && i < a.size() ? slot(a.get(i)) : null);
        return out;
    }

    /** An item stack, or null for an empty slot or an id that is not {@code namespace:path}. */
    static Slot slot(JsonElement e) {
        JsonObject o = Json.obj(e);
        if (o == null) return null;
        String id = Json.str(o, "id");
        if (id.length() > 64 || !ITEM_ID.matcher(id).matches()) return null;
        return new Slot(id, (int) Math.max(1, Math.min(100_000, Json.num(o, "c", 1))));
    }

    static List<ChatLine> chat(JsonElement e) {
        JsonArray a = Json.arr(e);
        List<ChatLine> out = new ArrayList<>();
        if (a == null) return out;
        for (JsonElement x : a) {
            if (out.size() >= MAX_CHAT) break;
            JsonObject o = Json.obj(x);
            if (o == null || !o.has("m") || !o.get("m").isJsonPrimitive() || !o.get("m").getAsJsonPrimitive().isString()) continue;
            String kind = Json.str(o, "k");
            if (!kind.equals("whisper") && !kind.equals("system") && !kind.equals("death")) kind = "public";
            out.add(new ChatLine(Json.lng(o, "t", 0), kind, Text.sanitize(Json.str(o, "s"), 16), Text.sanitize(Json.str(o, "to"), 16),
                Text.sanitize(Json.str(o, "m"), 256)));
        }
        return out;
    }
}
