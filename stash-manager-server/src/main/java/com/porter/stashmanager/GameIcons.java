package com.porter.stashmanager;

import com.porter.stashmanager.assets.GameAssets;
import com.porter.stashmanager.assets.IconRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Icons rendered from the Minecraft client jar on this machine (the primary source; the wiki pipeline in
 * {@link IconService} is the fallback). One {@link IconRenderer} per Minecraft version, opened on first use
 * and kept open. Renders are cached in data/icons/game-<mcVersion>/<ns>/<path>.png, apart from wiki icons.
 */
final class GameIcons implements AutoCloseable {
    static final int SIZE = 64;

    private final Path iconsDir;
    private final Path override;
    private final boolean enabled;
    private final Map<String, Optional<IconRenderer>> renderers = new ConcurrentHashMap<>();
    /** Per version: ids the renderer can't draw (block-entity items etc.), so they aren't retried. */
    private final Map<String, Set<String>> empty = new ConcurrentHashMap<>();

    GameIcons(Path iconsDir, Path override, boolean enabled) {
        this.iconsDir = iconsDir.toAbsolutePath().normalize();
        this.override = override;
        this.enabled = enabled;
    }

    boolean enabled() {
        return enabled;
    }

    /** Whether a client jar for this version was found and opened (opens it on first call). */
    boolean available(String mcVersion) {
        return renderer(mcVersion).isPresent();
    }

    private Path cacheFile(String mcVersion, String ns, String path) {
        return iconsDir.resolve("game-" + mcVersion).resolve(ns).resolve(path + ".png");
    }

    /** True when a game render for this id is already on disk. */
    boolean cached(String mcVersion, String ns, String path) {
        return enabled && IconService.MC_VERSION.matcher(mcVersion).matches() && Files.isRegularFile(cacheFile(mcVersion, ns, path));
    }

    /**
     * The game-rendered PNG for an id: from the cache, or rendered now (about 5 ms cold) and cached.
     * Null when the source is off, no jar exists for the version, or the renderer can't draw the item.
     * ns, path and mcVersion must already be validated (IconService.ID_PART / MC_VERSION).
     */
    byte[] get(String mcVersion, String ns, String path) {
        if (!enabled || !IconService.MC_VERSION.matcher(mcVersion).matches()) return null;
        Path file = cacheFile(mcVersion, ns, path);
        try {
            if (Files.isRegularFile(file)) return Files.readAllBytes(file);
        } catch (IOException ignored) {
        }
        return render(mcVersion, ns, path, file);
    }

    /** Renders and caches if not cached. Returns false when the game can't provide this icon. */
    boolean ensure(String mcVersion, String ns, String path) {
        if (cached(mcVersion, ns, path)) return true;
        return get(mcVersion, ns, path) != null;
    }

    private byte[] render(String mcVersion, String ns, String path, Path file) {
        Optional<IconRenderer> r = renderer(mcVersion);
        if (r.isEmpty()) return null;
        String id = ns + ":" + path;
        Set<String> none = empty.computeIfAbsent(mcVersion, v -> ConcurrentHashMap.newKeySet());
        if (none.contains(id)) return null;
        Optional<BufferedImage> img = r.get().render(id);
        if (img.isEmpty()) {
            none.add(id);
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(img.get(), "png", out)) return null;
            byte[] png = out.toByteArray();
            AtomicFiles.write(file, png);
            return png;
        } catch (IOException e) {
            System.err.println("Could not cache game icon " + id + ": " + e.getMessage());
            return null;
        }
    }

    private Optional<IconRenderer> renderer(String mcVersion) {
        if (!enabled || !IconService.MC_VERSION.matcher(mcVersion).matches()) return Optional.empty();
        return renderers.computeIfAbsent(mcVersion, this::open);
    }

    /** Runs once per version (inside computeIfAbsent), so the log line is printed once. */
    private Optional<IconRenderer> open(String mcVersion) {
        // Same search order as GameAssets.locate, but a file that exists and can't be opened (not a jar, a
        // pre-1.21.4 jar) doesn't end the search: the next candidate is tried.
        List<Path> tried = GameAssets.candidates(mcVersion, override);
        for (Path candidate : tried) {
            if (!Files.isRegularFile(candidate) || !Files.isReadable(candidate)) continue;
            try {
                IconRenderer r = IconRenderer.open(candidate, SIZE);
                if (override != null && !candidate.startsWith(override)) {
                    System.out.println("Game icons " + mcVersion + ": minecraftJar=" + override + " has no usable client jar for "
                        + mcVersion + ", found one automatically instead.");
                }
                System.out.println("Game icons " + mcVersion + ": rendering from " + candidate);
                return Optional.of(r);
            } catch (IOException | RuntimeException e) {
                System.out.println("Game icons " + mcVersion + ": can't use " + candidate + " (" + e.getMessage() + ")");
            }
        }
        System.out.println("Game icons " + mcVersion + ": no usable client jar, using the Minecraft Wiki. Looked in:");
        for (Path p : tried) System.out.println("    " + p);
        return Optional.empty();
    }

    @Override
    public void close() {
        for (Optional<IconRenderer> r : renderers.values()) {
            r.ifPresent(renderer -> {
                try {
                    renderer.close();
                } catch (IOException ignored) {
                }
            });
        }
    }
}
