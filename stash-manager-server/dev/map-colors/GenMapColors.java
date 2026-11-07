import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The vanilla map palette for the live viewer (map-palette.json). Two modes, so the game jar is only on the classpath
 * of the mode that needs it:
 *
 * <pre>
 *   GenMapColors dump  &lt;mcVersion&gt; &lt;out.txt&gt;            (game jar on the classpath)
 *   GenMapColors merge &lt;palette.json&gt; &lt;dump.txt&gt;...      (no game jar)
 * </pre>
 *
 * <p>dump reads {@code MapColor.byId(i)} for i in 0..63 and every {@code MapColor.Brightness} ({@code id},
 * {@code modifier}); rgb = channel * modifier / 255, at index id * 4 + brightness.id (the packed map byte). Ids 0-3
 * (NONE) and ids the version doesn't define are written as -1 (transparent). merge checks that every dump is the same
 * table and writes {"v":1,"mc":[versions],"rgb":[256 ints]} (-1 = transparent).
 */
public class GenMapColors {
    public static void main(String[] args) throws Exception {
        if (args.length >= 3 && args[0].equals("dump")) {
            Dump.run(args[1], Path.of(args[2]));
        } else if (args.length >= 3 && args[0].equals("merge")) {
            merge(Path.of(args[1]), args);
        } else {
            System.err.println("usage: GenMapColors dump <mc> <out.txt> | merge <palette.json> <dump.txt>...");
            System.exit(2);
        }
    }

    /** Only loaded in dump mode (it links against the game's MapColor). */
    static final class Dump {
        static void run(String version, Path out) throws Exception {
            int[] rgb = new int[256];
            java.util.Arrays.fill(rgb, -1);
            int defined = 0;
            net.minecraft.world.level.material.MapColor.Brightness[] bs = net.minecraft.world.level.material.MapColor.Brightness.values();
            if (bs.length != 4) throw new IllegalStateException("expected 4 brightness levels, got " + bs.length);
            for (int i = 0; i < 64; i++) {
                net.minecraft.world.level.material.MapColor c = net.minecraft.world.level.material.MapColor.byId(i);
                if (c == null || c.id != i || i == 0) continue; // byId gives NONE for an id the version lacks
                defined++;
                for (net.minecraft.world.level.material.MapColor.Brightness b : bs) {
                    int r = (c.col >> 16 & 0xFF) * b.modifier / 255;
                    int g = (c.col >> 8 & 0xFF) * b.modifier / 255;
                    int bl = (c.col & 0xFF) * b.modifier / 255;
                    int idx = i * 4 + b.id;
                    rgb[idx] = r << 16 | g << 8 | bl;
                    // Cross-check against the game's own packed id and colour lookup.
                    if ((c.getPackedId(b) & 0xFF) != idx) throw new IllegalStateException("packed id mismatch at " + i + "/" + b);
                    int argb = net.minecraft.world.level.material.MapColor.getColorFromPackedId(idx);
                    // getColorFromPackedId returns ABGR in 1.21.x (for NativeImage); compare the channels both ways.
                    int abgrAsRgb = (argb & 0xFF) << 16 | (argb >> 8 & 0xFF) << 8 | (argb >> 16 & 0xFF);
                    if (abgrAsRgb != rgb[idx] && (argb & 0xFFFFFF) != rgb[idx]) {
                        throw new IllegalStateException("colour mismatch at " + idx + ": " + Integer.toHexString(argb) + " vs " + Integer.toHexString(rgb[idx]));
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append(version).append('\n');
            for (int v : rgb) sb.append(v).append('\n');
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(version + ": " + defined + " map colours (+ NONE), written to " + out);
        }
    }

    static void merge(Path out, String[] args) throws Exception {
        List<String> versions = new ArrayList<>();
        int[] first = null;
        for (int a = 2; a < args.length; a++) {
            List<String> lines = Files.readAllLines(Path.of(args[a]), StandardCharsets.UTF_8);
            if (lines.size() != 257) throw new IllegalStateException(args[a] + ": expected 257 lines, got " + lines.size());
            versions.add(lines.get(0).trim());
            int[] rgb = new int[256];
            for (int i = 0; i < 256; i++) rgb[i] = Integer.parseInt(lines.get(i + 1).trim());
            if (first == null) {
                first = rgb;
            } else {
                for (int i = 0; i < 256; i++) {
                    if (first[i] != rgb[i]) {
                        throw new IllegalStateException("palettes differ between " + versions.get(0) + " and " + lines.get(0)
                            + " at index " + i + ": " + first[i] + " vs " + rgb[i]);
                    }
                }
            }
        }
        if (first == null) throw new IllegalStateException("no dumps given");
        StringBuilder sb = new StringBuilder("{\"v\":1,\"mc\":[");
        for (int i = 0; i < versions.size(); i++) sb.append(i == 0 ? "" : ",").append('"').append(versions.get(i)).append('"');
        sb.append("],\"rgb\":[");
        for (int i = 0; i < 256; i++) sb.append(i == 0 ? "" : ",").append(first[i]);
        sb.append("]}\n");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("versions " + versions + " match; wrote " + out);
    }
}
