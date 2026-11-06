package com.porter.stashmanager.assets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Geometry for {@code minecraft:special} item models (block-entity renders such as shulker boxes and chests).
 *
 * <p>Since 1.21.4 these items have no block model with elements: the client draws an entity model in code.
 * This class rebuilds those entity models from vanilla's layer definitions (cube positions, sizes and texture
 * offsets copied from {@code ShulkerModel}, {@code ChestModel}, {@code BedRenderer}, {@code SkullModel},
 * {@code ShieldModel}, {@code ConduitRenderer}, {@code DecoratedPotRenderer}, {@code BannerModel} and
 * {@code BannerFlagModel}; identical in 1.21.4 and 1.21.11) and applies the same pose transforms as the special
 * renderers, so the result goes through the item's {@code display.gui} transform like a block model.
 *
 * <p>Supported types: shulker_box, chest, bed, head (skeleton, wither_skeleton, zombie, creeper, player),
 * player_head, shield, conduit, decorated_pot (plain sides), banner (base colour). Anything else (dragon and
 * piglin heads, copper golem statues, signs, unknown types) returns null so the caller can use another source.
 */
final class SpecialModels {
    private SpecialModels() {
    }

    /**
     * A parallelogram in block space (0..1): {@code P(s,t) = p + s*a + t*b}, texture coordinates
     * {@code u0 + s*(u1-u0), v0 + t*(v1-v0)} in 0..16 units. {@code n} is the outward unit normal.
     */
    record Quad(double[] p, double[] a, double[] b, double[] n, float u0, float v0, float u1, float v1,
                String texture, int tint) {
    }

    /** Model-space face directions, as in {@code net.minecraft.core.Direction}. */
    private enum Dir {
        DOWN(0, -1, 0), UP(0, 1, 0), WEST(-1, 0, 0), NORTH(0, 0, -1), EAST(1, 0, 0), SOUTH(0, 0, 1);

        final double x, y, z;

        Dir(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** {@code CubeListBuilder.texOffs(u, v).addBox(x, y, z, w, h, d, new CubeDeformation(grow))}. */
    private record Cube(int u, int v, float x, float y, float z, float w, float h, float d, float grow, Set<Dir> faces) {
        Cube(int u, int v, float x, float y, float z, float w, float h, float d) {
            this(u, v, x, y, z, w, h, d, 0f, EnumSet.allOf(Dir.class));
        }

        Cube grow(float g) {
            return new Cube(u, v, x, y, z, w, h, d, g, faces);
        }

        Cube only(Dir dir) {
            return new Cube(u, v, x, y, z, w, h, d, grow, EnumSet.of(dir));
        }
    }

    /** {@code DyeColor.getTextureDiffuseColor()}. */
    private static final Map<String, Integer> DYE = Map.ofEntries(
            Map.entry("white", 0xF9FFFE), Map.entry("orange", 0xF9801D), Map.entry("magenta", 0xC74EBD),
            Map.entry("light_blue", 0x3AB3DA), Map.entry("yellow", 0xFED83D), Map.entry("lime", 0x80C71F),
            Map.entry("pink", 0xF38BAA), Map.entry("gray", 0x474F52), Map.entry("light_gray", 0x9D9D97),
            Map.entry("cyan", 0x169C9C), Map.entry("purple", 0x8932B8), Map.entry("blue", 0x3C44AA),
            Map.entry("brown", 0x835432), Map.entry("green", 0x5E7C16), Map.entry("red", 0xB02E26),
            Map.entry("black", 0x1D1D21));

    /** The special model type's name without namespace, or "?" (for diagnostics). */
    static String typeOf(JsonObject special) {
        return special == null ? "?" : stripNs(str(special, "type", "?"));
    }

    /**
     * Quads for a special model node (the {@code "model"} object inside a {@code minecraft:special} item model),
     * or null if the type is unsupported or a texture is missing.
     */
    static List<Quad> build(JsonObject special, Function<String, Optional<IconRenderer.Tex>> textures) {
        if (special == null) return null;
        Builder b = new Builder(textures);
        boolean ok = switch (typeOf(special)) {
            case "shulker_box" -> shulkerBox(b, special);
            case "chest" -> chest(b, special);
            case "bed" -> bed(b, special);
            case "head" -> head(b, stripNs(str(special, "kind", "")));
            case "player_head" -> head(b, "player");
            case "shield" -> shield(b);
            case "conduit" -> conduit(b);
            case "decorated_pot" -> decoratedPot(b);
            case "banner" -> banner(b, special);
            default -> false;
        };
        return ok && !b.missingTexture && !b.out.isEmpty() ? b.out : null;
    }

    // ------------------------------------------------------------------ models

    /** ShulkerBoxRenderer.render with Direction.UP and progress 0; ShulkerModel.createShellMesh. */
    private static boolean shulkerBox(Builder b, JsonObject s) {
        String tex = material(s, "entity/shulker/", "shulker");
        if (tex == null) return false;
        Pose p = new Pose();
        p.translate(0.5, 0.5, 0.5);
        p.scale(0.9995, 0.9995, 0.9995);
        p.scale(1, -1, -1);
        p.translate(0, -1, 0);
        // HashMap order of the parts is lid, then base; base wins where their sides overlap.
        b.part(p, tex, 64, 64, 0, 24, 0, 0, 0, 0, new Cube(0, 0, -8, -16, -8, 16, 12, 16));
        b.part(p, tex, 64, 64, 0, 24, 0, 0, 0, 0, new Cube(0, 28, -8, -8, -8, 16, 8, 16));
        return true;
    }

    /** ChestSpecialRenderer (no pose of its own), ChestModel.createSingleBodyLayer, openness 0. */
    private static boolean chest(Builder b, JsonObject s) {
        String tex = material(s, "entity/chest/", "normal");
        if (tex == null) return false;
        Pose p = new Pose();
        b.part(p, tex, 64, 64, 0, 0, 0, 0, 0, 0, new Cube(0, 19, 1, 0, 1, 14, 10, 14));   // bottom
        b.part(p, tex, 64, 64, 0, 9, 1, 0, 0, 0, new Cube(0, 0, 1, 0, 0, 14, 5, 14));     // lid
        b.part(p, tex, 64, 64, 0, 9, 1, 0, 0, 0, new Cube(0, 0, 7, -2, 14, 2, 4, 1));     // lock
        return true;
    }

    /** BedRenderer.renderInHand: head piece, then the foot piece one block behind it. */
    private static boolean bed(Builder b, JsonObject s) {
        String tex = material(s, "entity/bed/", null);
        if (tex == null) return false;
        float half = (float) (Math.PI / 2), pi = (float) Math.PI;
        for (int piece = 0; piece < 2; piece++) {
            boolean foot = piece == 1;
            Pose p = new Pose();
            p.translate(0, 0.5625, foot ? -1 : 0);
            p.rotX(Math.toRadians(90));
            p.translate(0.5, 0.5, 0.5);
            p.rotZ(Math.toRadians(180)); // 180 + Direction.SOUTH.toYRot() (0)
            p.translate(-0.5, -0.5, -0.5);
            // HashMap order of the parts: right_leg, main, left_leg.
            if (!foot) {
                b.part(p, tex, 64, 64, 0, 0, 0, half, 0, pi, new Cube(50, 18, -16, 6, 0, 3, 3, 3));
                b.part(p, tex, 64, 64, 0, 0, 0, 0, 0, 0, new Cube(0, 0, 0, 0, 0, 16, 16, 6));
                b.part(p, tex, 64, 64, 0, 0, 0, half, 0, half, new Cube(50, 6, 0, 6, 0, 3, 3, 3));
            } else {
                b.part(p, tex, 64, 64, 0, 0, 0, half, 0, 3 * half, new Cube(50, 12, -16, 6, -16, 3, 3, 3));
                b.part(p, tex, 64, 64, 0, 0, 0, 0, 0, 0, new Cube(0, 22, 0, 0, 0, 16, 16, 6));
                b.part(p, tex, 64, 64, 0, 0, 0, half, 0, 0, new Cube(50, 0, 0, 6, -16, 3, 3, 3));
            }
        }
        return true;
    }

    /** SkullBlockRenderer.renderSkull(null direction, yRot 180); SkullModel mob / humanoid head layers. */
    private static boolean head(Builder b, String kind) {
        String tex;
        boolean humanoid;
        switch (kind) {
            case "skeleton" -> { tex = "minecraft:entity/skeleton/skeleton"; humanoid = false; }
            case "wither_skeleton" -> { tex = "minecraft:entity/skeleton/wither_skeleton"; humanoid = false; }
            case "creeper" -> { tex = "minecraft:entity/creeper/creeper"; humanoid = false; }
            case "zombie" -> { tex = "minecraft:entity/zombie/zombie"; humanoid = true; }
            case "player" -> { tex = "minecraft:entity/player/wide/steve"; humanoid = true; }
            default -> { return false; } // dragon and piglin heads have their own models
        }
        Pose p = new Pose();
        p.translate(0.5, 0, 0.5);
        p.scale(-1, -1, 1);
        int th = humanoid ? 64 : 32;
        float yRot = (float) Math.toRadians(180);
        b.part(p, tex, 64, th, 0, 0, 0, 0, yRot, 0, new Cube(0, 0, -4, -8, -4, 8, 8, 8));
        if (humanoid) b.part(p, tex, 64, th, 0, 0, 0, 0, yRot, 0, new Cube(32, 0, -4, -8, -4, 8, 8, 8).grow(0.25f));
        return true;
    }

    /** ShieldSpecialRenderer without patterns: the no-pattern texture on handle and plate. */
    private static boolean shield(Builder b) {
        String tex = "minecraft:entity/shield_base_nopattern";
        Pose p = new Pose();
        p.scale(1, -1, -1);
        b.part(p, tex, 64, 64, 0, 0, 0, 0, 0, 0, new Cube(26, 0, -1, -3, -1, 2, 6, 6));     // handle
        b.part(p, tex, 64, 64, 0, 0, 0, 0, 0, 0, new Cube(0, 0, -6, -11, -2, 12, 22, 1));   // plate
        return true;
    }

    /** ConduitSpecialRenderer: the shell, centred. */
    private static boolean conduit(Builder b) {
        Pose p = new Pose();
        p.translate(0.5, 0.5, 0.5);
        b.part(p, "minecraft:entity/conduit/base", 32, 16, 0, 0, 0, 0, 0, 0, new Cube(0, 0, -3, -3, -3, 6, 6, 6));
        return true;
    }

    /** DecoratedPotRenderer.renderInHand with no sherds: base layer, then the four plain sides. */
    private static boolean decoratedPot(Builder b) {
        String base = "minecraft:entity/decorated_pot/decorated_pot_base";
        String side = "minecraft:entity/decorated_pot/decorated_pot_side";
        float pi = (float) Math.PI, half = (float) (Math.PI / 2);
        Pose p = new Pose();
        b.part(p, base, 32, 32, 0, 37, 16, pi, 0, 0,
                new Cube(0, 0, 4, 17, 4, 8, 3, 8).grow(-0.1f), new Cube(0, 5, 5, 20, 5, 6, 1, 6).grow(0.2f)); // neck
        Cube plane = new Cube(-14, 13, 0, 0, 0, 14, 0, 14);
        b.part(p, base, 32, 32, 1, 16, 1, 0, 0, 0, plane); // top
        b.part(p, base, 32, 32, 1, 0, 1, 0, 0, 0, plane);  // bottom
        Cube face = new Cube(1, 0, 0, 0, 0, 14, 16, 0).only(Dir.NORTH);
        b.part(p, side, 16, 16, 1, 16, 15, pi, 0, 0, face);        // front
        b.part(p, side, 16, 16, 15, 16, 1, 0, 0, pi, face);        // back
        b.part(p, side, 16, 16, 1, 16, 1, 0, -half, pi, face);     // left
        b.part(p, side, 16, 16, 15, 16, 15, 0, half, pi, face);    // right
        return true;
    }

    /** BannerRenderer.renderInHand: standing pole and bar, then the flag in banner_base and the base colour. */
    private static boolean banner(Builder b, JsonObject s) {
        Integer dye = DYE.get(stripNs(str(s, "color", "white")).toLowerCase(Locale.ROOT));
        if (dye == null) return false;
        String tex = "minecraft:entity/banner_base";
        Pose p = new Pose();
        p.translate(0.5, 0, 0.5);
        p.scale(2.0 / 3.0, -2.0 / 3.0, -2.0 / 3.0);
        // HashMap order of the parts: pole, bar.
        b.part(p, tex, 64, 64, 0, 0, 0, 0, 0, 0, new Cube(44, 0, -1, -42, -1, 2, 42, 2));
        b.part(p, tex, 64, 64, 0, 0, 0, 0, 0, 0, new Cube(0, 42, -10, -44, -1, 20, 2, 2));
        float flagRot = (float) ((-0.0125 + 0.01 * Math.cos(0)) * Math.PI); // BannerFlagModel.setupAnim(0)
        Cube flag = new Cube(0, 0, -10, 0, -2, 20, 40, 1);
        b.part(p, tex, 64, 64, 0, -44, 0, flagRot, 0, 0, flag);
        b.tint = 0xFF000000 | dye;
        b.part(p, "minecraft:entity/banner/base", 64, 64, 0, -44, 0, flagRot, 0, 0, flag);
        b.tint = 0xFFFFFFFF;
        return true;
    }

    /** "minecraft:shulker_black" -> "minecraft:entity/shulker/shulker_black"; null if malformed. */
    private static String material(JsonObject s, String folder, String def) {
        String t = str(s, "texture", def);
        if (t == null) return null;
        String[] id = IconRenderer.parseId(t);
        if (id == null) return null;
        return id[0] + ":" + folder + id[1];
    }

    // ------------------------------------------------------------------ geometry

    /** Collects quads; remembers whether a texture was missing. */
    private static final class Builder {
        final Function<String, Optional<IconRenderer.Tex>> textures;
        final List<Quad> out = new ArrayList<>();
        boolean missingTexture;
        int tint = 0xFFFFFFFF;

        Builder(Function<String, Optional<IconRenderer.Tex>> textures) {
            this.textures = textures;
        }

        /**
         * One ModelPart: {@code translateAndRotate} (offset in pixels, then rotationZYX), then its cubes, in a
         * texture of {@code texW x texH} pixels as declared by the layer definition.
         */
        void part(Pose parent, String texture, int texW, int texH, float ox, float oy, float oz,
                  float xRot, float yRot, float zRot, Cube... cubes) {
            if (textures.apply(texture).isEmpty()) {
                missingTexture = true;
                return;
            }
            Pose p = parent.copy();
            p.translate(ox / 16.0, oy / 16.0, oz / 16.0);
            if (zRot != 0) p.rotZ(zRot);
            if (yRot != 0) p.rotY(yRot);
            if (xRot != 0) p.rotX(xRot);
            for (Cube c : cubes) cube(p, texture, texW, texH, c);
        }

        /** ModelPart.Cube: the eight corners, six polygons and their uv rectangles. */
        private void cube(Pose p, String texture, int texW, int texH, Cube c) {
            double x0 = c.x - c.grow, y0 = c.y - c.grow, z0 = c.z - c.grow;
            double x1 = c.x + c.w + c.grow, y1 = c.y + c.h + c.grow, z1 = c.z + c.d + c.grow;
            double[] a = {x0, y0, z0}, bb = {x1, y0, z0}, cc = {x1, y1, z0}, d = {x0, y1, z0};
            double[] e = {x0, y0, z1}, f = {x1, y0, z1}, g = {x1, y1, z1}, h = {x0, y1, z1};
            float u = c.u, v = c.v, w = c.w, hh = c.h, dd = c.d;
            float f4 = u, f5 = u + dd, f6 = u + dd + w, f7 = u + dd + w + w, f8 = u + dd + w + dd, f9 = u + dd + w + dd + w;
            float f10 = v, f11 = v + dd, f12 = v + dd + hh;
            if (c.faces.contains(Dir.DOWN)) poly(p, texture, texW, texH, Dir.DOWN, f, e, a, bb, f5, f10, f6, f11);
            if (c.faces.contains(Dir.UP)) poly(p, texture, texW, texH, Dir.UP, cc, d, h, g, f6, f11, f7, f10);
            if (c.faces.contains(Dir.WEST)) poly(p, texture, texW, texH, Dir.WEST, a, e, h, d, f4, f11, f5, f12);
            if (c.faces.contains(Dir.NORTH)) poly(p, texture, texW, texH, Dir.NORTH, bb, a, d, cc, f5, f11, f6, f12);
            if (c.faces.contains(Dir.EAST)) poly(p, texture, texW, texH, Dir.EAST, f, bb, cc, g, f6, f11, f8, f12);
            if (c.faces.contains(Dir.SOUTH)) poly(p, texture, texW, texH, Dir.SOUTH, e, f, g, h, f8, f11, f9, f12);
        }

        /**
         * ModelPart.Polygon: vertex 0 gets (u2, v1), 1 gets (u1, v1), 2 gets (u1, v2), 3 gets (u2, v2). The
         * quad is a parallelogram, so it is stored as origin vertex 1 with edges towards vertices 0 and 2.
         */
        private void poly(Pose p, String texture, int texW, int texH, Dir dir, double[] q0, double[] q1, double[] q2,
                          double[] q3, float u1, float v1, float u2, float v2) {
            double[] w0 = p.point(q0[0] / 16, q0[1] / 16, q0[2] / 16);
            double[] w1 = p.point(q1[0] / 16, q1[1] / 16, q1[2] / 16);
            double[] w2 = p.point(q2[0] / 16, q2[1] / 16, q2[2] / 16);
            double[] n = p.normal(dir.x, dir.y, dir.z);
            out.add(new Quad(w1, sub(w0, w1), sub(w2, w1), n,
                    u1 * 16f / texW, v1 * 16f / texH, u2 * 16f / texW, v2 * 16f / texH, texture, tint));
        }
    }

    /** An affine pose (PoseStack entry): 3x3 linear part plus translation. */
    private static final class Pose {
        final double[][] m = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        final double[] t = {0, 0, 0};

        Pose copy() {
            Pose c = new Pose();
            for (int i = 0; i < 3; i++) {
                System.arraycopy(m[i], 0, c.m[i], 0, 3);
                c.t[i] = t[i];
            }
            return c;
        }

        void translate(double x, double y, double z) {
            for (int i = 0; i < 3; i++) t[i] += m[i][0] * x + m[i][1] * y + m[i][2] * z;
        }

        void scale(double x, double y, double z) {
            for (int i = 0; i < 3; i++) {
                m[i][0] *= x;
                m[i][1] *= y;
                m[i][2] *= z;
            }
        }

        void rotX(double a) {
            mulRight(new double[][]{{1, 0, 0}, {0, Math.cos(a), -Math.sin(a)}, {0, Math.sin(a), Math.cos(a)}});
        }

        void rotY(double a) {
            mulRight(new double[][]{{Math.cos(a), 0, Math.sin(a)}, {0, 1, 0}, {-Math.sin(a), 0, Math.cos(a)}});
        }

        void rotZ(double a) {
            mulRight(new double[][]{{Math.cos(a), -Math.sin(a), 0}, {Math.sin(a), Math.cos(a), 0}, {0, 0, 1}});
        }

        private void mulRight(double[][] r) {
            double[][] o = new double[3][3];
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++)
                    o[i][j] = m[i][0] * r[0][j] + m[i][1] * r[1][j] + m[i][2] * r[2][j];
            for (int i = 0; i < 3; i++) System.arraycopy(o[i], 0, m[i], 0, 3);
        }

        double[] point(double x, double y, double z) {
            return new double[]{
                    m[0][0] * x + m[0][1] * y + m[0][2] * z + t[0],
                    m[1][0] * x + m[1][1] * y + m[1][2] * z + t[1],
                    m[2][0] * x + m[2][1] * y + m[2][2] * z + t[2]};
        }

        /** Normal transform (inverse transpose of the linear part), normalized. */
        double[] normal(double x, double y, double z) {
            double[][] a = m;
            double det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1])
                    - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
                    + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
            // cofactor matrix = det * inverse transpose; only the direction matters, keep det's sign.
            double[][] cof = {
                    {a[1][1] * a[2][2] - a[1][2] * a[2][1], a[1][2] * a[2][0] - a[1][0] * a[2][2], a[1][0] * a[2][1] - a[1][1] * a[2][0]},
                    {a[0][2] * a[2][1] - a[0][1] * a[2][2], a[0][0] * a[2][2] - a[0][2] * a[2][0], a[0][1] * a[2][0] - a[0][0] * a[2][1]},
                    {a[0][1] * a[1][2] - a[0][2] * a[1][1], a[0][2] * a[1][0] - a[0][0] * a[1][2], a[0][0] * a[1][1] - a[0][1] * a[1][0]}};
            double s = det < 0 ? -1 : 1;
            double nx = s * (cof[0][0] * x + cof[0][1] * y + cof[0][2] * z);
            double ny = s * (cof[1][0] * x + cof[1][1] * y + cof[1][2] * z);
            double nz = s * (cof[2][0] * x + cof[2][1] * y + cof[2][2] * z);
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            return len < 1e-12 ? new double[]{0, 0, 1} : new double[]{nx / len, ny / len, nz / len};
        }
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static String stripNs(String s) {
        return s.startsWith("minecraft:") ? s.substring(10) : s;
    }

    private static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }
}
