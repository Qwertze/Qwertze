package com.porter.stashmanager.assets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Software rasterizer for resolved models. Stateless; every call allocates its own buffers.
 *
 * <p>Coordinate pipeline (matches vanilla GUI item rendering): element space 0..16 -> element rotation ->
 * unit cube centred on the origin -> display.gui scale, rotation (X*Y*Z, i.e. Z applied first), translation/16
 * -> orthographic screen (x right, y up, +z towards the viewer). Faces are parallelograms after the affine
 * transform, so every output pixel is inverse-mapped to exact (s,t) face coordinates and sampled
 * nearest-neighbour. A z-buffer resolves visibility; translucent texels are blended back-to-front in a second
 * pass over the opaque result.
 */
final class ModelRasterizer {
    private static final float ALPHA_CUTOFF = 0.1f;
    /** Vanilla's 3D GUI item lights, expressed in this class's view space. */
    private static final double[] LIGHT0, LIGHT1;

    static {
        LIGHT0 = guiLight(0.2, 1.0, -0.7);
        LIGHT1 = guiLight(-0.2, 1.0, 0.7);
    }

    private ModelRasterizer() {
    }

    /** Lighting.ITEMS_3D: rotateYXZ(1.0821041, 3.2375858, 0).rotateYXZ(-0.3926991, 2.3561945, 0) applied to the light. */
    private static double[] guiLight(double x, double y, double z) {
        double[] v = normalize(new double[]{x, y, z});
        v = rotX(2.3561945, v);
        v = rotY(-0.3926991, v);
        v = rotX(3.2375858, v);
        v = rotY(1.0821041, v);
        return v;
    }

    /** Renders a model to size*size ARGB pixels, or null if nothing can be drawn. */
    static int[] render(IconRenderer.Model m, int[] tints, Function<String, Optional<IconRenderer.Tex>> textures,
                        int size) {
        if (m.generated()) return flat(m, tints, textures, size);
        if (m.elements() == null || m.elements().isEmpty()) return null;
        return elements(m, tints, textures, size);
    }

    // ------------------------------------------------------------------ item/generated

    private static int[] flat(IconRenderer.Model m, int[] tints, Function<String, Optional<IconRenderer.Tex>> textures,
                              int size) {
        int[] out = new int[size * size];
        int drawn = 0;
        for (int layer = 0; layer < 16; layer++) {
            String ref = m.textures().get("layer" + layer);
            if (ref == null) break;
            String id = m.texture(ref);
            Optional<IconRenderer.Tex> t = id == null ? Optional.empty() : textures.apply(id);
            if (t.isEmpty()) return null; // vanilla would draw the missing-texture checkerboard
            IconRenderer.Tex tex = t.get();
            int tint = layer < tints.length ? tints[layer] : 0xFFFFFFFF;
            for (int y = 0; y < size; y++) {
                int ty = Math.min(tex.h() - 1, (int) ((y + 0.5) * tex.h() / size));
                for (int x = 0; x < size; x++) {
                    int tx = Math.min(tex.w() - 1, (int) ((x + 0.5) * tex.w() / size));
                    int c = tex.argb()[ty * tex.w() + tx];
                    if ((c >>> 24) == 0) continue;
                    out[y * size + x] = blend(out[y * size + x], multiply(c, tint, 1f));
                }
            }
            drawn++;
        }
        return drawn == 0 ? null : out;
    }

    // ------------------------------------------------------------------ block-style models

    private static final String[] DIRS = {"down", "up", "north", "south", "west", "east"};

    /** A face projected to screen space: P(s,t) = p + s*a + t*b. */
    private static final class Face {
        double px, py, pz, ax, ay, az, bx, by, bz;
        float u0, v0, u1, v1;
        int rotation;
        IconRenderer.Tex tex;
        int tint;
        float shade;
        boolean translucent;
        double depth;
    }

    private static int[] elements(IconRenderer.Model m, int[] tints, Function<String, Optional<IconRenderer.Tex>> textures,
                                  int size) {
        double[][] rot = mul(mul(rotXm(Math.toRadians(m.guiRotation()[0])), rotYm(Math.toRadians(m.guiRotation()[1]))),
                rotZm(Math.toRadians(m.guiRotation()[2])));
        float[] sc = m.guiScale(), tr = m.guiTranslation();
        List<Face> faces = new ArrayList<>();
        for (JsonElement ee : m.elements()) {
            JsonObject el = ee.getAsJsonObject();
            float[] from = vec3(el.getAsJsonArray("from")), to = vec3(el.getAsJsonArray("to"));
            if (from == null || to == null) continue;
            ElementRotation er = ElementRotation.of(el.getAsJsonObject("rotation"));
            JsonObject fs = el.getAsJsonObject("faces");
            if (fs == null) continue;
            for (String dir : DIRS) {
                JsonObject f = fs.getAsJsonObject(dir);
                if (f == null) continue;
                String texRef = f.has("texture") ? f.get("texture").getAsString() : null;
                // face textures are always references; vanilla accepts them with or without the leading #
                String texId = texRef == null ? null : m.texture(texRef.startsWith("#") ? texRef : "#" + texRef);
                Optional<IconRenderer.Tex> tex = texId == null ? Optional.empty() : textures.apply(texId);
                if (tex.isEmpty()) continue;
                double[][] corners = corners(dir, from, to); // P00, P10, P01 in 0..16 space
                double[][] v = new double[3][];
                for (int i = 0; i < 3; i++) {
                    double[] p = er.apply(corners[i]);
                    double[] q = {(p[0] / 16 - 0.5) * sc[0], (p[1] / 16 - 0.5) * sc[1], (p[2] / 16 - 0.5) * sc[2]};
                    q = mul(rot, q);
                    v[i] = new double[]{q[0] + tr[0] / 16.0, q[1] + tr[1] / 16.0, q[2] + tr[2] / 16.0};
                }
                double[] a = sub(v[1], v[0]), b = sub(v[2], v[0]);
                double[] n = cross(b, a); // outward normal in view space
                double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
                if (len < 1e-12) continue;
                n[0] /= len; n[1] /= len; n[2] /= len;
                if (n[2] <= 1e-6) continue; // back face: vanilla item render types cull (leaves, glass too)
                Face face = new Face();
                face.px = (v[0][0] + 0.5) * size; face.py = (0.5 - v[0][1]) * size; face.pz = v[0][2];
                face.ax = a[0] * size; face.ay = -a[1] * size; face.az = a[2];
                face.bx = b[0] * size; face.by = -b[1] * size; face.bz = b[2];
                float[] uv = uv(f.getAsJsonArray("uv"), dir, from, to);
                face.u0 = uv[0]; face.v0 = uv[1]; face.u1 = uv[2]; face.v1 = uv[3];
                face.rotation = f.has("rotation") ? Math.floorMod(f.get("rotation").getAsInt() / 90, 4) : 0;
                face.tex = tex.get();
                int ti = f.has("tintindex") ? f.get("tintindex").getAsInt() : -1;
                face.tint = ti >= 0 && ti < tints.length ? tints[ti] : 0xFFFFFFFF;
                face.shade = m.frontLight() ? 1f : light(n);
                face.translucent = hasTranslucency(face.tex);
                face.depth = face.pz + 0.5 * face.az + 0.5 * face.bz;
                faces.add(face);
            }
        }
        return rasterAll(faces, size);
    }

    // ------------------------------------------------------------------ special (entity) models

    /**
     * Renders {@link SpecialModels} quads (block space 0..1) with a base model's display.gui transform and
     * light setting. Returns null if nothing can be drawn.
     */
    static int[] quads(List<SpecialModels.Quad> quads, IconRenderer.Model base,
                       Function<String, Optional<IconRenderer.Tex>> textures, int size) {
        double[][] rot = mul(mul(rotXm(Math.toRadians(base.guiRotation()[0])), rotYm(Math.toRadians(base.guiRotation()[1]))),
                rotZm(Math.toRadians(base.guiRotation()[2])));
        float[] sc = base.guiScale(), tr = base.guiTranslation();
        List<Face> faces = new ArrayList<>();
        for (SpecialModels.Quad q : quads) {
            Optional<IconRenderer.Tex> tex = textures.apply(q.texture());
            if (tex.isEmpty()) continue;
            double[] p0 = view(rot, sc, tr, q.p());
            double[] a = mul(rot, new double[]{q.a()[0] * sc[0], q.a()[1] * sc[1], q.a()[2] * sc[2]});
            double[] b = mul(rot, new double[]{q.b()[0] * sc[0], q.b()[1] * sc[1], q.b()[2] * sc[2]});
            if (Math.abs(cross(a, b)[0]) + Math.abs(cross(a, b)[1]) + Math.abs(cross(a, b)[2]) < 1e-12) continue; // degenerate
            // Normals transform with the inverse scale; a negative scale component flips them like the geometry.
            double[] n = mul(rot, new double[]{q.n()[0] / nz(sc[0]), q.n()[1] / nz(sc[1]), q.n()[2] / nz(sc[2])});
            n = normalize(n);
            if (n[2] <= 1e-6) continue; // facing away from the viewer
            Face face = new Face();
            face.px = (p0[0] + 0.5) * size; face.py = (0.5 - p0[1]) * size; face.pz = p0[2];
            face.ax = a[0] * size; face.ay = -a[1] * size; face.az = a[2];
            face.bx = b[0] * size; face.by = -b[1] * size; face.bz = b[2];
            face.u0 = q.u0(); face.v0 = q.v0(); face.u1 = q.u1(); face.v1 = q.v1();
            face.rotation = 0;
            face.tex = tex.get();
            face.tint = q.tint();
            face.shade = base.frontLight() ? 1f : light(n);
            face.translucent = hasTranslucency(face.tex);
            face.depth = face.pz + 0.5 * face.az + 0.5 * face.bz;
            faces.add(face);
        }
        return rasterAll(faces, size);
    }

    /** Block-space point (0..1) -> centred, display-scaled, rotated and translated view space. */
    private static double[] view(double[][] rot, float[] sc, float[] tr, double[] p) {
        double[] q = mul(rot, new double[]{(p[0] - 0.5) * sc[0], (p[1] - 0.5) * sc[1], (p[2] - 0.5) * sc[2]});
        return new double[]{q[0] + tr[0] / 16.0, q[1] + tr[1] / 16.0, q[2] + tr[2] / 16.0};
    }

    private static double nz(float s) {
        return Math.abs(s) < 1e-6 ? 1e-6 : s;
    }

    /** Z-buffered opaque pass in list order (later faces win ties), then translucent faces back to front. */
    private static int[] rasterAll(List<Face> faces, int size) {
        if (faces.isEmpty()) return null;

        int[] color = new int[size * size];
        float[] zbuf = new float[size * size];
        java.util.Arrays.fill(zbuf, Float.NEGATIVE_INFINITY);
        for (Face f : faces) raster(f, size, color, zbuf, false);
        List<Face> sorted = new ArrayList<>(faces);
        sorted.sort((x, y) -> Double.compare(x.depth, y.depth)); // far to near
        for (Face f : sorted) if (f.translucent) raster(f, size, color, zbuf, true);
        return color;
    }

    private static void raster(Face f, int size, int[] color, float[] zbuf, boolean translucentPass) {
        double det = f.ax * f.by - f.ay * f.bx;
        if (Math.abs(det) < 1e-9) return;
        double minX = Math.min(Math.min(f.px, f.px + f.ax), Math.min(f.px + f.bx, f.px + f.ax + f.bx));
        double maxX = Math.max(Math.max(f.px, f.px + f.ax), Math.max(f.px + f.bx, f.px + f.ax + f.bx));
        double minY = Math.min(Math.min(f.py, f.py + f.ay), Math.min(f.py + f.by, f.py + f.ay + f.by));
        double maxY = Math.max(Math.max(f.py, f.py + f.ay), Math.max(f.py + f.by, f.py + f.ay + f.by));
        int x0 = Math.max(0, (int) Math.floor(minX)), x1 = Math.min(size - 1, (int) Math.ceil(maxX));
        int y0 = Math.max(0, (int) Math.floor(minY)), y1 = Math.min(size - 1, (int) Math.ceil(maxY));
        final double eps = 1e-6;
        IconRenderer.Tex tex = f.tex;
        for (int y = y0; y <= y1; y++) {
            double dy = y + 0.5 - f.py;
            for (int x = x0; x <= x1; x++) {
                double dx = x + 0.5 - f.px;
                double s = (dx * f.by - dy * f.bx) / det;
                double t = (f.ax * dy - f.ay * dx) / det;
                if (s < -eps || s > 1 + eps || t < -eps || t > 1 + eps) continue;
                s = Math.max(0, Math.min(1, s));
                t = Math.max(0, Math.min(1, t));
                float z = (float) (f.pz + s * f.az + t * f.bz);
                int i = y * size + x;
                if (z < zbuf[i] - 1e-5f) continue;
                double ss, tt;
                switch (f.rotation) { // texture rotated clockwise by 90 * rotation degrees
                    case 1 -> { ss = t; tt = 1 - s; }
                    case 2 -> { ss = 1 - s; tt = 1 - t; }
                    case 3 -> { ss = 1 - t; tt = s; }
                    default -> { ss = s; tt = t; }
                }
                double u = f.u0 + ss * (f.u1 - f.u0), v = f.v0 + tt * (f.v1 - f.v0);
                int tx = clamp((int) Math.floor(u * tex.w() / 16.0), tex.w());
                int ty = clamp((int) Math.floor(v * tex.h() / 16.0), tex.h());
                int c = tex.argb()[ty * tex.w() + tx];
                int a = c >>> 24;
                if (a < ALPHA_CUTOFF * 255) continue;
                boolean opaqueTexel = a >= 250;
                if (translucentPass == opaqueTexel) continue;
                int shaded = multiply(c, f.tint, f.shade);
                if (opaqueTexel) {
                    color[i] = shaded | 0xFF000000;
                    zbuf[i] = z;
                } else {
                    color[i] = blend(color[i], shaded);
                }
            }
        }
    }

    /** Vanilla entity-shader diffuse: min(1, 0.4 + 0.6 * (max(0, L0.n) + max(0, L1.n))). */
    private static float light(double[] n) {
        double l0 = Math.max(0, LIGHT0[0] * n[0] + LIGHT0[1] * n[1] + LIGHT0[2] * n[2]);
        double l1 = Math.max(0, LIGHT1[0] * n[0] + LIGHT1[1] * n[1] + LIGHT1[2] * n[2]);
        return (float) Math.min(1.0, 0.4 + 0.6 * (l0 + l1));
    }

    /** True if a meaningful share of the texture's visible texels are semi-transparent (glass, ice, slime). */
    private static boolean hasTranslucency(IconRenderer.Tex t) {
        int semi = 0, visible = 0;
        for (int c : t.argb()) {
            int a = c >>> 24;
            if (a < ALPHA_CUTOFF * 255) continue;
            visible++;
            if (a < 250) semi++;
        }
        return semi > 0 && semi * 20 >= visible; // >= 5%
    }

    /** Corners P00, P10, P01 of a face: s runs along texture u, t along texture v, as seen from outside. */
    private static double[][] corners(String dir, float[] f, float[] t) {
        return switch (dir) {
            case "north" -> new double[][]{{t[0], t[1], f[2]}, {f[0], t[1], f[2]}, {t[0], f[1], f[2]}};
            case "south" -> new double[][]{{f[0], t[1], t[2]}, {t[0], t[1], t[2]}, {f[0], f[1], t[2]}};
            case "west" -> new double[][]{{f[0], t[1], f[2]}, {f[0], t[1], t[2]}, {f[0], f[1], f[2]}};
            case "east" -> new double[][]{{t[0], t[1], t[2]}, {t[0], t[1], f[2]}, {t[0], f[1], t[2]}};
            case "up" -> new double[][]{{f[0], t[1], f[2]}, {t[0], t[1], f[2]}, {f[0], t[1], t[2]}};
            default -> new double[][]{{f[0], f[1], t[2]}, {t[0], f[1], t[2]}, {f[0], f[1], f[2]}}; // down
        };
    }

    /** Explicit uv, or vanilla's default uv derived from the element bounds. */
    private static float[] uv(JsonArray arr, String dir, float[] f, float[] t) {
        if (arr != null && arr.size() >= 4) {
            return new float[]{arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat(), arr.get(3).getAsFloat()};
        }
        return switch (dir) {
            case "down" -> new float[]{f[0], 16 - t[2], t[0], 16 - f[2]};
            case "up" -> new float[]{f[0], f[2], t[0], t[2]};
            case "north" -> new float[]{16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]};
            case "south" -> new float[]{f[0], 16 - t[1], t[0], 16 - f[1]};
            case "west" -> new float[]{f[2], 16 - t[1], t[2], 16 - f[1]};
            default -> new float[]{16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]}; // east
        };
    }

    /** Element "rotation": origin, axis, angle, rescale. */
    private record ElementRotation(double[][] m, double[] origin, double[] scale) {
        static final ElementRotation NONE = new ElementRotation(null, null, null);

        static ElementRotation of(JsonObject r) {
            if (r == null || !r.has("angle") || !r.has("axis")) return NONE;
            double angle = r.get("angle").getAsDouble();
            if (angle == 0) return NONE;
            double rad = Math.toRadians(angle);
            float[] o = r.has("origin") ? vec3(r.getAsJsonArray("origin")) : new float[]{8, 8, 8};
            if (o == null) o = new float[]{8, 8, 8};
            String axis = r.get("axis").getAsString();
            double[][] m = switch (axis) {
                case "x" -> rotXm(rad);
                case "z" -> rotZm(rad);
                default -> rotYm(rad);
            };
            double[] scale = {1, 1, 1};
            if (r.has("rescale") && r.get("rescale").getAsBoolean()) {
                double s = 1.0 / Math.cos(rad);
                if (axis.equals("x")) { scale[1] = s; scale[2] = s; }
                else if (axis.equals("z")) { scale[0] = s; scale[1] = s; }
                else { scale[0] = s; scale[2] = s; }
            }
            return new ElementRotation(m, new double[]{o[0], o[1], o[2]}, scale);
        }

        double[] apply(double[] p) {
            if (m == null) return p;
            double[] d = mul(m, new double[]{p[0] - origin[0], p[1] - origin[1], p[2] - origin[2]});
            return new double[]{d[0] * scale[0] + origin[0], d[1] * scale[1] + origin[1], d[2] * scale[2] + origin[2]};
        }
    }

    // ------------------------------------------------------------------ colour

    /** texel * tint * shade (rgb), alpha = texel alpha * tint alpha. */
    private static int multiply(int c, int tint, float shade) {
        int a = (c >>> 24) * (tint >>> 24) / 255;
        int r = Math.round(((c >> 16) & 255) * ((tint >> 16) & 255) / 255f * shade);
        int g = Math.round(((c >> 8) & 255) * ((tint >> 8) & 255) / 255f * shade);
        int b = Math.round((c & 255) * (tint & 255) / 255f * shade);
        return (a << 24) | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    /** Non-premultiplied "src over dst". */
    static int blend(int dst, int src) {
        int sa = src >>> 24;
        if (sa == 255) return src;
        if (sa == 0) return dst;
        int da = dst >>> 24;
        if (da == 0) return src;
        float fs = sa / 255f, fd = da / 255f * (1 - fs), oa = fs + fd;
        int r = Math.round((((src >> 16) & 255) * fs + ((dst >> 16) & 255) * fd) / oa);
        int g = Math.round((((src >> 8) & 255) * fs + ((dst >> 8) & 255) * fd) / oa);
        int b = Math.round(((src & 255) * fs + (dst & 255) * fd) / oa);
        return (Math.round(oa * 255) << 24) | (r << 16) | (g << 8) | b;
    }

    /** Composites {@code top} over {@code base} in place. */
    static void over(int[] base, int[] top) {
        for (int i = 0; i < base.length; i++) base[i] = blend(base[i], top[i]);
    }

    // ------------------------------------------------------------------ math

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }

    private static float[] vec3(JsonArray a) {
        if (a == null || a.size() < 3) return null;
        return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }

    private static double[][] rotXm(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}};
    }

    private static double[][] rotYm(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
    }

    private static double[][] rotZm(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
    }

    private static double[] rotX(double a, double[] v) {
        return mul(rotXm(a), v);
    }

    private static double[] rotY(double a, double[] v) {
        return mul(rotYm(a), v);
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
        return r;
    }

    private static double[] mul(double[][] m, double[] v) {
        return new double[]{
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] normalize(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[]{v[0] / l, v[1] / l, v[2] / l};
    }
}
