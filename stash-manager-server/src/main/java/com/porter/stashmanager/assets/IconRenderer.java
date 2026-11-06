package com.porter.stashmanager.assets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Renders inventory-style item icons straight from a vanilla client jar (1.21.4+ layout, i.e. with
 * {@code assets/minecraft/items/*.json} item model definitions).
 *
 * <p>The jar is opened read-only with {@link ZipFile}; single entries are read on demand and parsed JSON and
 * decoded textures are cached in memory (a few MB for a full stash). Instances are thread-safe: any number of
 * threads may call {@link #render} concurrently. Close the renderer to release the jar.
 *
 * <p>Block-entity ("special") item models are drawn from their entity models by {@link SpecialModels}: shulker
 * boxes, chests, beds, banners (base colour), skeleton/wither/zombie/creeper/player heads, shields, the conduit
 * and the decorated pot. {@link #render} returns empty for the rest (dragon and piglin heads, copper golem
 * statues): callers should fall back to another icon source for those.
 */
public final class IconRenderer implements AutoCloseable {
    public static final int DEFAULT_SIZE = 64;

    private static final Pattern ID = Pattern.compile("^(?:([a-z0-9_.-]{1,64}):)?([a-z0-9_.-]{1,128}(?:/[a-z0-9_.-]{1,128}){0,8})$");
    private static final int MAX_DEPTH = 32;

    private final ZipFile zip;
    private final int size;
    private final Map<String, Optional<JsonObject>> jsonCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<Tex>> texCache = new ConcurrentHashMap<>();

    private IconRenderer(ZipFile zip, int size) {
        this.zip = zip;
        this.size = size;
    }

    /** Opens {@code clientJar} for {@value #DEFAULT_SIZE}px icons. */
    public static IconRenderer open(Path clientJar) throws IOException {
        return open(clientJar, DEFAULT_SIZE);
    }

    /**
     * Opens {@code clientJar} for {@code size}x{@code size} icons (16..512; multiples of 16 keep textures
     * pixel-exact).
     *
     * @throws IOException if the file is not a readable zip, or has no {@code assets/minecraft/items/} folder
     *                     (pre-1.21.4 jars are not supported)
     */
    public static IconRenderer open(Path clientJar, int size) throws IOException {
        if (size < 16 || size > 512) throw new IllegalArgumentException("size must be 16..512: " + size);
        ZipFile zip = new ZipFile(clientJar.toFile(), ZipFile.OPEN_READ);
        if (zip.getEntry("assets/minecraft/items/stone.json") == null) {
            zip.close();
            throw new IOException("not a 1.21.4+ client jar (no assets/minecraft/items/): " + clientJar);
        }
        return new IconRenderer(zip, size);
    }

    public int size() {
        return size;
    }

    /**
     * Renders the item's inventory icon.
     *
     * @param itemId {@code minecraft:stone} or {@code stone}
     * @return a {@code size x size} {@link BufferedImage#TYPE_INT_ARGB} image (a fresh copy per call), or empty
     *         if the id is unknown/malformed or the item needs a renderer this class does not have
     */
    public Optional<BufferedImage> render(String itemId) {
        try {
            return Optional.ofNullable(renderOrNull(itemId));
        } catch (RuntimeException e) {
            return Optional.empty(); // malformed asset JSON etc.: let the caller use another source
        }
    }

    /** Why {@link #render} would return empty for this id, or null if it renders. For diagnostics. */
    public String explain(String itemId) {
        try {
            String[] id = parseId(itemId);
            if (id == null) return "malformed id";
            Optional<JsonObject> def = json(id[0], "items/" + id[1] + ".json");
            if (def.isEmpty()) return "no item definition";
            List<Part> parts = new ArrayList<>();
            String why = collect(def.get().getAsJsonObject("model"), parts, 0);
            if (why != null) return why;
            if (parts.isEmpty()) return "item definition has no models";
            return renderParts(parts) == null ? "model has no drawable geometry (or missing textures)" : null;
        } catch (RuntimeException e) {
            return "error: " + e;
        }
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }

    // ------------------------------------------------------------------ item definitions

    private BufferedImage renderOrNull(String itemId) {
        String[] id = parseId(itemId);
        if (id == null) return null;
        Optional<JsonObject> def = json(id[0], "items/" + id[1] + ".json");
        if (def.isEmpty() || !def.get().has("model")) return null;
        List<Part> parts = new ArrayList<>();
        if (collect(def.get().getAsJsonObject("model"), parts, 0) != null || parts.isEmpty()) return null;
        return renderParts(parts);
    }

    private BufferedImage renderParts(List<Part> parts) {
        int[] out = null;
        for (Part part : parts) {
            Model m = resolveModel(part.modelId);
            if (m == null) return null;
            int[] img;
            if (part.special != null) {
                List<SpecialModels.Quad> quads = SpecialModels.build(part.special, this::texture);
                if (quads == null) return null;
                img = ModelRasterizer.quads(quads, m, this::texture, size);
            } else {
                img = ModelRasterizer.render(m, part.tints, this::texture, size);
            }
            if (img == null) return null;
            if (out == null) out = img;
            else ModelRasterizer.over(out, img);
        }
        if (out == null) return null;
        boolean any = false;
        for (int px : out) if ((px >>> 24) != 0) { any = true; break; }
        if (!any) return null;
        BufferedImage bi = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        bi.setRGB(0, 0, size, size, out, 0, size);
        return bi;
    }

    /**
     * One layer of an item: a model id plus the definition's tint colours (index = tintindex). For a special
     * model, modelId is its "base" model (display transforms only) and special is its "model" object.
     */
    private record Part(String modelId, int[] tints, JsonObject special) {
        Part(String modelId, int[] tints) {
            this(modelId, tints, null);
        }
    }

    /**
     * Flattens an item model definition into drawable parts, picking the GUI / default branch of every
     * switch. Returns null on success, or the reason the item cannot be drawn.
     */
    private String collect(JsonObject node, List<Part> out, int depth) {
        if (node == null) return "missing model node";
        if (depth > MAX_DEPTH) return "definition nested too deep";
        String type = stripNs(str(node, "type", ""));
        switch (type) {
            case "model" -> {
                String model = str(node, "model", null);
                if (model == null) return "model node without model";
                out.add(new Part(model, tints(node.getAsJsonArray("tints"))));
                return null;
            }
            case "composite" -> {
                JsonArray models = node.getAsJsonArray("models");
                if (models == null) return "composite without models";
                for (JsonElement e : models) {
                    String why = collect(e.getAsJsonObject(), out, depth + 1);
                    if (why != null) return why;
                }
                return null;
            }
            case "select" -> {
                JsonArray cases = node.getAsJsonArray("cases");
                if ("display_context".equals(stripNs(str(node, "property", ""))) && cases != null) {
                    for (JsonElement c : cases) {
                        JsonObject co = c.getAsJsonObject();
                        if (whenMatches(co.get("when"), "gui")) return collect(co.getAsJsonObject("model"), out, depth + 1);
                    }
                }
                if (node.has("fallback")) return collect(node.getAsJsonObject("fallback"), out, depth + 1);
                if (cases != null && !cases.isEmpty()) {
                    return collect(cases.get(0).getAsJsonObject().getAsJsonObject("model"), out, depth + 1);
                }
                return "select without fallback";
            }
            case "condition" -> {
                return collect(node.getAsJsonObject("on_false"), out, depth + 1);
            }
            case "range_dispatch" -> {
                // Evaluate at property value 0: the highest threshold <= 0, else the fallback, else the first entry.
                JsonArray entries = node.getAsJsonArray("entries");
                JsonObject pick = null;
                float best = Float.NEGATIVE_INFINITY;
                if (entries != null) {
                    for (JsonElement e : entries) {
                        JsonObject eo = e.getAsJsonObject();
                        float t = eo.has("threshold") ? eo.get("threshold").getAsFloat() : 0f;
                        if (t <= 0f && t >= best) { best = t; pick = eo.getAsJsonObject("model"); }
                    }
                }
                if (pick == null && node.has("fallback")) pick = node.getAsJsonObject("fallback");
                if (pick == null && entries != null && !entries.isEmpty()) {
                    pick = entries.get(0).getAsJsonObject().getAsJsonObject("model");
                }
                return pick == null ? "empty range_dispatch" : collect(pick, out, depth + 1);
            }
            case "special" -> {
                JsonObject sm = node.getAsJsonObject("model");
                String base = str(node, "base", null);
                if (sm == null || base == null) return "special renderer (" + SpecialModels.typeOf(sm) + ") without base";
                if (SpecialModels.build(sm, this::texture) == null) {
                    return "special renderer (" + SpecialModels.typeOf(sm) + ") not supported";
                }
                out.add(new Part(base, new int[0], sm));
                return null;
            }
            case "empty", "bundle/selected_item" -> {
                return null; // draws nothing on its own
            }
            default -> {
                return "unknown item model type " + type;
            }
        }
    }

    private static boolean whenMatches(JsonElement when, String value) {
        if (when == null) return false;
        if (when.isJsonArray()) {
            for (JsonElement e : when.getAsJsonArray()) if (value.equals(stripNs(e.getAsString()))) return true;
            return false;
        }
        return when.isJsonPrimitive() && value.equals(stripNs(when.getAsString()));
    }

    // ------------------------------------------------------------------ tints

    private int[] tints(JsonArray arr) {
        if (arr == null || arr.isEmpty()) return new int[0];
        int[] out = new int[arr.size()];
        for (int i = 0; i < out.length; i++) out[i] = tint(arr.get(i).getAsJsonObject());
        return out;
    }

    private int tint(JsonObject t) {
        String type = stripNs(str(t, "type", ""));
        switch (type) {
            case "constant" -> {
                return t.has("value") ? color(t.get("value")) : 0xFFFFFFFF;
            }
            case "grass" -> {
                return colormap("grass", num(t, "temperature", 0.5f), num(t, "downfall", 1.0f), 0xFF48B518);
            }
            case "foliage", "dry_foliage" -> {
                return colormap(type, num(t, "temperature", 0.5f), num(t, "downfall", 1.0f), 0xFF48B518);
            }
            default -> {
                // dye, potion, firework, map_color, custom_model_data, team, ...: use the declared default.
                if (t.has("default")) return 0xFF000000 | color(t.get("default"));
                return 0xFFFFFFFF;
            }
        }
    }

    private static int color(JsonElement e) {
        if (e.isJsonArray()) { // [r, g, b] floats
            JsonArray a = e.getAsJsonArray();
            int r = Math.round(a.get(0).getAsFloat() * 255), g = Math.round(a.get(1).getAsFloat() * 255),
                    b = Math.round(a.get(2).getAsFloat() * 255);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return e.getAsInt();
    }

    /** Vanilla GrassColor/FoliageColor lookup in textures/colormap/<name>.png. */
    private int colormap(String name, float temperature, float downfall, int fallback) {
        Optional<Tex> map = texture("minecraft:colormap/" + name, false);
        if (map.isEmpty() || map.get().w() != 256 || map.get().h() != 256) return fallback;
        double temp = Math.max(0, Math.min(1, temperature));
        double rain = Math.max(0, Math.min(1, downfall)) * temp;
        int x = (int) ((1.0 - temp) * 255.0);
        int y = (int) ((1.0 - rain) * 255.0);
        return 0xFF000000 | map.get().argb()[y * 256 + x];
    }

    // ------------------------------------------------------------------ block/item models

    /** A model with its parent chain merged. */
    record Model(Map<String, String> textures, JsonArray elements, float[] guiRotation, float[] guiTranslation,
                 float[] guiScale, boolean frontLight, boolean generated) {
        /** Resolves "#ref" chains; returns a texture id or null. */
        String texture(String ref) {
            String r = ref;
            for (int i = 0; i < 16 && r != null; i++) {
                if (!r.startsWith("#")) return r;
                r = textures.get(r.substring(1));
            }
            return null;
        }
    }

    private Model resolveModel(String modelId) {
        List<JsonObject> chain = new ArrayList<>();
        boolean generated = false;
        String cur = modelId;
        for (int i = 0; i < MAX_DEPTH && cur != null; i++) {
            String[] id = parseId(cur);
            if (id == null) return null;
            if (id[0].equals("minecraft") && id[1].equals("builtin/generated")) { generated = true; break; }
            if (id[0].equals("minecraft") && id[1].startsWith("builtin/")) return null; // builtin/entity etc.
            Optional<JsonObject> j = json(id[0], "models/" + id[1] + ".json");
            if (j.isEmpty()) return null;
            chain.add(j.get());
            cur = str(j.get(), "parent", null);
        }
        Map<String, String> textures = new HashMap<>();
        JsonArray elements = null;
        JsonObject gui = null;
        String guiLight = null;
        for (int i = chain.size() - 1; i >= 0; i--) { // root first, child overrides
            JsonObject m = chain.get(i);
            JsonObject tex = m.getAsJsonObject("textures");
            if (tex != null) {
                for (Map.Entry<String, JsonElement> e : tex.entrySet()) {
                    JsonElement v = e.getValue();
                    if (v.isJsonPrimitive()) textures.put(e.getKey(), v.getAsString());
                    else if (v.isJsonObject() && v.getAsJsonObject().has("sprite")) {
                        textures.put(e.getKey(), v.getAsJsonObject().get("sprite").getAsString());
                    }
                }
            }
            if (m.has("elements")) elements = m.getAsJsonArray("elements");
            JsonObject display = m.getAsJsonObject("display");
            if (display != null && display.has("gui")) gui = display.getAsJsonObject("gui");
            if (m.has("gui_light")) guiLight = m.get("gui_light").getAsString();
        }
        float[] rot = vec(gui, "rotation", 0f), tr = vec(gui, "translation", 0f), sc = vec(gui, "scale", 1f);
        for (int k = 0; k < 3; k++) tr[k] = Math.max(-80f, Math.min(80f, tr[k]));
        for (int k = 0; k < 3; k++) sc[k] = Math.max(-4f, Math.min(4f, sc[k]));
        boolean front = guiLight != null ? "front".equals(guiLight) : false;
        if (elements != null && !elements.isEmpty()) generated = false;
        return new Model(textures, elements, rot, tr, sc, front, generated);
    }

    private static float[] vec(JsonObject o, String key, float def) {
        float[] v = {def, def, def};
        if (o != null && o.has(key) && o.get(key).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(key);
            for (int i = 0; i < 3 && i < a.size(); i++) v[i] = a.get(i).getAsFloat();
        }
        return v;
    }

    // ------------------------------------------------------------------ jar access

    private Optional<JsonObject> json(String ns, String path) {
        String entry = "assets/" + ns + "/" + path;
        return jsonCache.computeIfAbsent(entry, e -> {
            ZipEntry ze = zip.getEntry(e);
            if (ze == null) return Optional.empty();
            try (Reader r = new InputStreamReader(zip.getInputStream(ze), StandardCharsets.UTF_8)) {
                JsonElement el = JsonParser.parseReader(r);
                return el.isJsonObject() ? Optional.of(el.getAsJsonObject()) : Optional.empty();
            } catch (IOException | RuntimeException ex) {
                return Optional.empty();
            }
        });
    }

    /** Texture lookup by id ("minecraft:block/stone" or "block/stone"); first animation frame only. */
    Optional<Tex> texture(String textureId) {
        return texture(textureId, true);
    }

    private Optional<Tex> texture(String textureId, boolean firstFrame) {
        String[] id = parseId(textureId);
        if (id == null) return Optional.empty();
        String entry = "assets/" + id[0] + "/textures/" + id[1] + ".png";
        return texCache.computeIfAbsent(entry + (firstFrame ? "" : "#full"), k -> loadTexture(entry, firstFrame));
    }

    private Optional<Tex> loadTexture(String entry, boolean firstFrame) {
        ZipEntry ze = zip.getEntry(entry);
        if (ze == null) return Optional.empty();
        BufferedImage img;
        try (InputStream in = zip.getInputStream(ze)) {
            img = ImageIO.read(in);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        if (img == null) return Optional.empty();
        int w = img.getWidth(), h = img.getHeight();
        int fx = 0, fy = 0, fw = w, fh = h;
        if (firstFrame) {
            JsonObject anim = null;
            Optional<JsonObject> meta = mcmeta(entry + ".mcmeta");
            if (meta.isPresent() && meta.get().has("animation")) anim = meta.get().getAsJsonObject("animation");
            if (anim != null || h > w) {
                int def = Math.min(w, h);
                fw = anim != null && anim.has("width") ? anim.get("width").getAsInt() : def;
                fh = anim != null && anim.has("height") ? anim.get("height").getAsInt() : def;
                if (fw <= 0 || fh <= 0 || fw > w || fh > h) { fw = def; fh = def; }
                int index = 0;
                if (anim != null && anim.has("frames") && anim.getAsJsonArray("frames").size() > 0) {
                    JsonElement f0 = anim.getAsJsonArray("frames").get(0);
                    index = f0.isJsonObject() ? f0.getAsJsonObject().get("index").getAsInt() : f0.getAsInt();
                }
                int cols = Math.max(1, w / fw);
                fx = (index % cols) * fw;
                fy = (index / cols) * fh;
                if (fx + fw > w || fy + fh > h) { fx = 0; fy = 0; }
            }
        }
        return Optional.of(new Tex(fw, fh, pixels(img, fx, fy, fw, fh)));
    }

    /**
     * ARGB pixels of a region. Grayscale PNGs (stone, anvil, grass_block_top, ...) are read from the raster:
     * {@link BufferedImage#getRGB} treats their gray as linear and gamma-brightens it.
     */
    private static int[] pixels(BufferedImage img, int x0, int y0, int w, int h) {
        int[] px = new int[w * h];
        var cm = img.getColorModel();
        if (cm.getColorSpace().getType() == java.awt.color.ColorSpace.TYPE_GRAY && !(cm instanceof java.awt.image.IndexColorModel)) {
            var raster = img.getRaster();
            int bands = raster.getNumBands();
            int grayMax = (1 << cm.getComponentSize(0)) - 1;
            int alphaMax = bands > 1 ? (1 << cm.getComponentSize(1)) - 1 : 1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int g = raster.getSample(x0 + x, y0 + y, 0) * 255 / grayMax;
                    int a = bands > 1 ? raster.getSample(x0 + x, y0 + y, 1) * 255 / alphaMax : 255;
                    px[y * w + x] = (a << 24) | (g << 16) | (g << 8) | g;
                }
            }
            return px;
        }
        img.getRGB(x0, y0, w, h, px, 0, w);
        return px;
    }

    private Optional<JsonObject> mcmeta(String entry) {
        return jsonCache.computeIfAbsent(entry, e -> {
            ZipEntry ze = zip.getEntry(e);
            if (ze == null) return Optional.empty();
            try (Reader r = new InputStreamReader(zip.getInputStream(ze), StandardCharsets.UTF_8)) {
                JsonElement el = JsonParser.parseReader(r);
                return el.isJsonObject() ? Optional.of(el.getAsJsonObject()) : Optional.empty();
            } catch (IOException | RuntimeException ex) {
                return Optional.empty();
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Splits "ns:path" / "path" into {ns, path}; null if malformed (also rejects ".." segments). */
    static String[] parseId(String id) {
        if (id == null) return null;
        var m = ID.matcher(id.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) return null;
        String path = m.group(2);
        for (String seg : path.split("/")) if (seg.equals("..") || seg.equals(".")) return null;
        return new String[]{m.group(1) == null ? "minecraft" : m.group(1), path};
    }

    private static String stripNs(String s) {
        return s.startsWith("minecraft:") ? s.substring(10) : s;
    }

    private static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static float num(JsonObject o, String k, float def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsFloat() : def;
    }

    /** A decoded texture (single frame), ARGB pixels row-major. */
    record Tex(int w, int h, int[] argb) {
    }
}
