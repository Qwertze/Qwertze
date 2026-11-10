package com.porter.stashmanager;

import com.porter.stashmanager.assets.GameAssets;
import com.porter.stashmanager.assets.IconRenderer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/**
 * Where the one-time per-version pass gets the client jar from, shared by the icon sets and the recipe sets: the first
 * jar on this machine that opens ({@link GameAssets} candidates), else the official one downloaded once by
 * {@link ClientJarFetcher} into {@code jars/<v>/client.jar}. A lease is closed when every set has been built from it:
 * a downloaded jar is then deleted, a jar from this machine is never touched.
 */
final class JarSources {
    /** A jar in use. {@code renderer} is open only when icons were asked for and the jar could be used for them. */
    static final class Lease implements AutoCloseable {
        final Path jar;
        final boolean downloaded;
        final IconRenderer renderer;
        /** Why icons can't be made from this jar (a downloaded jar without item definitions), or null. */
        final String iconProblem;

        private Lease(Path jar, boolean downloaded, IconRenderer renderer, String iconProblem) {
            this.jar = jar;
            this.downloaded = downloaded;
            this.renderer = renderer;
            this.iconProblem = iconProblem;
        }

        @Override
        public void close() throws IOException {
            if (renderer != null) renderer.close();
            if (downloaded) {
                Files.deleteIfExists(jar); // every set is complete without it; it is downloaded again only if one goes missing
                try {
                    Files.deleteIfExists(jar.getParent());
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Nothing to build from: no usable jar on this machine and downloading is off for what is being built. */
    static final class NoJarException extends ClientJarFetcher.PermanentException {
        NoJarException(String m) {
            super(m);
        }
    }

    private final Path jarsDir;
    private final Path override;

    JarSources(Path jarsDir, Path override) {
        this.jarsDir = jarsDir;
        this.override = override;
    }

    /**
     * A jar for version v. needIcons: the candidate must also open for the renderer. fetcher is null when downloading
     * is not allowed for what is being built. Throws PermanentException when nothing usable exists and no download is
     * allowed; transient errors from the fetch pass through.
     */
    Lease acquire(String v, boolean needIcons, int iconSize, ClientJarFetcher fetcher) throws IOException, InterruptedException {
        long start = System.nanoTime();
        String label = needIcons ? "Icon set " : "Recipes ";
        Path zipOnly = null; // opens as a zip but not for the renderer: still good for the recipe pass
        // The same candidates GameIcons tries; the first one that opens wins, a bad one does not stop the search.
        for (Path candidate : GameAssets.candidates(v, override)) {
            if (!Files.isRegularFile(candidate) || !Files.isReadable(candidate)) continue;
            try {
                if (needIcons) {
                    try {
                        return new Lease(candidate, false, IconRenderer.open(candidate, iconSize), null);
                    } catch (IOException | RuntimeException e) {
                        new ZipFile(candidate.toFile()).close();
                        if (zipOnly == null) zipOnly = candidate;
                        throw e;
                    }
                }
                new ZipFile(candidate.toFile()).close();
                return new Lease(candidate, false, null, null);
            } catch (IOException | RuntimeException e) {
                System.out.println(label + v + ": can't use " + candidate + " (" + e.getMessage() + ")");
            }
        }
        if (fetcher == null && zipOnly != null) return new Lease(zipOnly, false, null, "no client jar on this machine that the icon renderer can use");
        if (fetcher == null) throw new NoJarException("no usable client jar for " + v + " on this machine and downloading is off");
        Path downloaded = jarsDir.resolve(v).resolve("client.jar");
        Files.deleteIfExists(downloaded); // a leftover is never trusted; the fetch verifies a fresh one
        System.out.println(label + v + ": downloading the official client jar from Mojang (once)...");
        try {
            fetcher.fetch(v, downloaded);
            System.out.printf("%s%s: downloaded %.1f MB (sha1 verified) in %.1f s.%n", label, v, Files.size(downloaded) / 1048576.0,
                (System.nanoTime() - start) / 1e9);
            new ZipFile(downloaded.toFile()).close();
        } catch (IOException | RuntimeException | InterruptedException e) {
            try {
                Files.deleteIfExists(downloaded);
                Files.deleteIfExists(downloaded.getParent());
            } catch (IOException ignored) {
            }
            throw e;
        }
        IconRenderer renderer = null;
        String problem = null;
        if (needIcons) {
            try {
                renderer = IconRenderer.open(downloaded, iconSize);
            } catch (IOException | RuntimeException e) {
                problem = "the downloaded jar has no item definitions the renderer can use (" + e.getMessage() + ")";
            }
        }
        return new Lease(downloaded, true, renderer, problem);
    }
}
