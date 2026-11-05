package com.porter.stashmanager.assets;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Finds the vanilla Minecraft client jar on this machine. Nothing is opened or extracted here; pass the
 * result to {@link IconRenderer#open(Path)}.
 */
public final class GameAssets {
    private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$");

    private GameAssets() {
    }

    /**
     * Locates the client jar for {@code mcVersion}.
     *
     * <p>Search order:
     * <ol>
     *   <li>{@code override} (may be null): a jar file, or a launcher directory (a {@code .minecraft} folder,
     *       a Prism data folder, or a Prism {@code libraries} folder) that is searched like the defaults;</li>
     *   <li>Prism Launcher: {@code <prism>/libraries/com/mojang/minecraft/<v>/minecraft-<v>-client.jar};</li>
     *   <li>vanilla launcher: {@code <.minecraft>/versions/<v>/<v>.jar}.</li>
     * </ol>
     * Default roots: %APPDATA% on Windows; ~/.minecraft, ~/.local/share/PrismLauncher and the Prism flatpak on
     * Linux; ~/Library/Application Support on macOS.
     *
     * @return the first existing regular file, or empty (also for a malformed version string)
     */
    public static Optional<Path> locate(String mcVersion, Path override) {
        for (Path p : candidates(mcVersion, override)) {
            try {
                if (Files.isRegularFile(p) && Files.isReadable(p)) return Optional.of(p);
            } catch (SecurityException ignored) {
                // unreadable location; try the next one
            }
        }
        return Optional.empty();
    }

    /** Every path {@link #locate} would try, in order. Useful for "client jar not found" log messages. */
    public static List<Path> candidates(String mcVersion, Path override) {
        List<Path> out = new ArrayList<>();
        if (mcVersion == null || !VERSION.matcher(mcVersion).matches()) return out;
        if (override != null) {
            if (Files.isDirectory(override)) {
                addLayouts(out, override, mcVersion);
            } else {
                out.add(override);
            }
        }
        String home = System.getProperty("user.home", "");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<Path> prismRoots = new ArrayList<>();
        List<Path> vanillaRoots = new ArrayList<>();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                prismRoots.add(path(appData, "PrismLauncher"));
                vanillaRoots.add(path(appData, ".minecraft"));
            }
        } else if (os.contains("mac")) {
            prismRoots.add(path(home, "Library", "Application Support", "PrismLauncher"));
            vanillaRoots.add(path(home, "Library", "Application Support", "minecraft"));
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            if (xdg != null && !xdg.isBlank()) prismRoots.add(path(xdg, "PrismLauncher"));
            prismRoots.add(path(home, ".local", "share", "PrismLauncher"));
            prismRoots.add(path(home, ".var", "app", "org.prismlauncher.PrismLauncher", "data", "PrismLauncher"));
            vanillaRoots.add(path(home, ".minecraft"));
        }
        for (Path root : prismRoots) if (root != null) out.add(prismJar(root.resolve("libraries"), mcVersion));
        for (Path root : vanillaRoots) if (root != null) out.add(vanillaJar(root, mcVersion));
        out.removeIf(p -> p == null);
        return out;
    }

    private static void addLayouts(List<Path> out, Path dir, String v) {
        out.add(prismJar(dir.resolve("libraries"), v)); // Prism data dir
        out.add(prismJar(dir, v));                      // Prism libraries dir
        out.add(vanillaJar(dir, v));                    // .minecraft dir
        out.add(dir.resolve(v + ".jar"));               // versions/<v> dir
        out.add(dir.resolve("minecraft-" + v + "-client.jar"));
    }

    private static Path prismJar(Path libraries, String v) {
        return libraries.resolve("com").resolve("mojang").resolve("minecraft").resolve(v)
                .resolve("minecraft-" + v + "-client.jar");
    }

    private static Path vanillaJar(Path dotMinecraft, String v) {
        return dotMinecraft.resolve("versions").resolve(v).resolve(v + ".jar");
    }

    private static Path path(String first, String... more) {
        try {
            return Path.of(first, more);
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
