package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.stashmanager.assets.GameAssets;
import com.porter.stashmanager.assets.IconRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One complete icon set per Minecraft version, built once in the background and then served from disk.
 *
 * <p>For a version: if {@code icons/game-<v>/.complete} and {@code manifest.json} verify (every file present, same
 * SHA-256), nothing happens. Otherwise the first client jar that opens is used ({@link GameAssets} candidates, the
 * same ones {@link GameIcons} tries) or, when downloading is on, the official jar is fetched once by
 * {@link ClientJarFetcher} into {@code jars/<v>/client.jar}. Every item definition is rendered into a temp folder, the
 * files are moved into {@code icons/game-<v>/minecraft/}, and the manifest and the completion marker are written last.
 * A downloaded jar is always deleted afterwards; a jar from this machine is never touched.
 *
 * <p>Failures are classed: permanent ones (nothing to build from, version not listed by Mojang, no item definitions,
 * a jar the renderer can't open) go straight to FAILED; transient ones (network, I/O, 5xx) are retried on a schedule
 * (no thread sleeps) and only BUILDING counts as pending for the icon route, so while waiting or after failing the
 * wiki fallback is used. FAILED lasts until the next launch.
 */
final class IconPacks implements AutoCloseable {
    enum State { BUILDING, WAITING, READY, FAILED }

    static final String MARKER = ".complete";
    static final String MANIFEST = "manifest.json";
    /** Versions that may be queued, building, waiting or ready at once (failed ones free their slot). */
    static final int MAX_VERSIONS = 8;
    /** Versions remembered at all (including failed ones), so garbage versions can't grow the map. */
    static final int MAX_REMEMBERED = 64;
    static final int MAX_ITEMS = 20_000;
    static final int MAX_ENTRIES = 200_000;
    static final long MAX_UNCOMPRESSED = 2L * 1024 * 1024 * 1024;
    static final long MAX_PACK_BYTES = 256L * 1024 * 1024;
    private static final Pattern ITEM_ENTRY = Pattern.compile("^assets/minecraft/items/([a-z0-9_]{1,128})\\.json$");
    private static final Pattern ITEM_NAME = Pattern.compile("^[a-z0-9_]{1,128}$");

    private final Path iconsDir;
    private final Path jarsDir;
    private final Path override;
    private final ClientJarFetcher fetcher; // null = never download
    private final int size;
    private final long[] retryDelaysMs;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> defsCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stash-manager-icon-pack");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;

    IconPacks(Path dataDir, Path override, ClientJarFetcher fetcher, int size, long... retryDelaysMs) {
        this.iconsDir = dataDir.resolve("icons").toAbsolutePath().normalize();
        this.jarsDir = dataDir.resolve("jars").toAbsolutePath().normalize();
        this.override = override;
        this.fetcher = fetcher;
        this.size = size;
        this.retryDelaysMs = retryDelaysMs;
        int swept = sweep(dataDir, SWEEP_MIN_AGE_MS);
        if (swept > 0) System.out.println("Icon sets: removed " + swept + " leftover temp file(s) from an interrupted run.");
    }

    /** Leftovers younger than this are skipped: they may belong to a live build of another instance. */
    static final long SWEEP_MIN_AGE_MS = 10 * 60 * 1000L;

    private static boolean oldEnough(Path p, long minAgeMs) {
        try {
            return System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis() >= minAgeMs;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Removes {@code icons/game-*.build-*} folders and {@code jars/<v>/client*.part} / {@code client.jar} files that
     * are at least minAgeMs old. Returns how many.
     */
    static int sweep(Path dataDir, long minAgeMs) {
        int n = 0;
        Path icons = dataDir.resolve("icons");
        if (Files.isDirectory(icons)) {
            try (Stream<Path> s = Files.list(icons)) {
                for (Path p : s.toList()) {
                    String name = p.getFileName().toString();
                    if (name.startsWith("game-") && name.contains(".build-") && Files.isDirectory(p) && oldEnough(p, minAgeMs)) {
                        deleteTree(p);
                        n++;
                    }
                }
            } catch (IOException ignored) {
            }
        }
        Path jars = dataDir.resolve("jars");
        if (Files.isDirectory(jars)) {
            try (Stream<Path> versions = Files.list(jars)) {
                for (Path v : versions.toList()) {
                    if (!Files.isDirectory(v)) continue;
                    try (Stream<Path> files = Files.list(v)) {
                        for (Path f : files.toList()) {
                            String name = f.getFileName().toString();
                            if (Files.isRegularFile(f) && oldEnough(f, minAgeMs) && ((name.startsWith("client") && name.endsWith(".part")) || name.equals("client.jar"))) {
                                Files.deleteIfExists(f);
                                n++;
                            }
                        }
                    }
                    try {
                        Files.deleteIfExists(v); // only when empty
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return n;
    }

    /** Starts the check/build for a version once per process. Cheap and non-blocking; bad versions and surplus versions are ignored. */
    void request(String mcVersion) {
        if (closed || mcVersion == null || !IconService.MC_VERSION.matcher(mcVersion).matches()) return;
        if (states.containsKey(mcVersion)) return;
        if (states.size() >= MAX_REMEMBERED || activeCount() >= MAX_VERSIONS) return;
        if (states.putIfAbsent(mcVersion, State.BUILDING) != null) return;
        try {
            worker.execute(() -> run(mcVersion, 0));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            states.put(mcVersion, State.FAILED);
        }
    }

    /** Versions using a slot: building, waiting for a retry, or ready. */
    int activeCount() {
        int n = 0;
        for (State s : states.values()) if (s != State.FAILED) n++;
        return n;
    }

    /** True only while the set is actually being checked, downloaded or rendered: icons should answer "pending". */
    boolean pending(String mcVersion) {
        return states.get(mcVersion) == State.BUILDING;
    }

    State state(String mcVersion) {
        return states.get(mcVersion);
    }

    /** Waits until the version's task is finished, retries included (tests). */
    boolean await(String mcVersion, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            State s = states.get(mcVersion);
            if (s != State.BUILDING && s != State.WAITING) return true;
            if (System.currentTimeMillis() > end) return false;
            Thread.sleep(10);
        }
    }

    /**
     * The item definition names of a ready/complete set (every definition in the jar, drawable or not), or null when
     * there is no readable manifest for the version (or it has no list).
     */
    Set<String> knownItems(String mcVersion) {
        if (mcVersion == null || !IconService.MC_VERSION.matcher(mcVersion).matches()) return null;
        Set<String> cached = defsCache.get(mcVersion);
        if (cached != null) return cached.isEmpty() ? null : cached; // an empty set caches "this manifest has no defs"
        if (states.get(mcVersion) != State.READY) return null;
        try {
            JsonObject m = JsonParser.parseString(Files.readString(packDir(mcVersion).resolve(MANIFEST), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!m.has("defs")) {
                defsCache.put(mcVersion, Set.of());
                return null;
            }
            Set<String> out = new HashSet<>();
            for (var e : m.getAsJsonArray("defs")) {
                String n = e.getAsString();
                if (ITEM_NAME.matcher(n).matches()) out.add(n);
            }
            if (out.isEmpty()) {
                defsCache.put(mcVersion, Set.of());
                return null;
            }
            Set<String> frozen = Set.copyOf(out);
            defsCache.put(mcVersion, frozen);
            return frozen;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private Path packDir(String v) {
        return iconsDir.resolve("game-" + v);
    }

    // ---------- the task ----------

    private void run(String v, int attempt) {
        states.put(v, State.BUILDING);
        try {
            if (closed) return;
            if (verify(packDir(v))) {
                System.out.println("Icon set " + v + ": complete on disk, nothing to do.");
                states.put(v, State.READY);
                return;
            }
            build(v);
            states.put(v, State.READY);
        } catch (ClientJarFetcher.PermanentException e) {
            System.err.println("Icon set " + v + ": " + e.getMessage() + ". Using the wiki for this version until the next launch.");
        } catch (IOException | RuntimeException e) {
            if (!closed && attempt < retryDelaysMs.length) {
                System.err.println("Icon set " + v + ": failed (" + e.getMessage() + "), trying again in " + retryDelaysMs[attempt] / 1000.0 + " s.");
                states.put(v, State.WAITING);
                try {
                    worker.schedule(() -> run(v, attempt + 1), retryDelaysMs[attempt], TimeUnit.MILLISECONDS);
                    return;
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    states.put(v, State.FAILED); // closing
                }
            } else {
                System.err.println("Icon set " + v + ": failed (" + e.getMessage() + "). It will be tried again at the next launch; the wiki is used meanwhile.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) { // an Error must not leave the version stuck as BUILDING
            System.err.println("Icon set " + v + ": " + t);
        } finally {
            State s = states.get(v);
            if (s == State.BUILDING) states.put(v, State.FAILED);
        }
    }

    // ---------- verify ----------

    /** The completion marker, a readable manifest, and every listed file present with the same SHA-256. */
    boolean verify(Path dir) {
        try {
            if (!Files.isRegularFile(dir.resolve(MARKER)) || !Files.isRegularFile(dir.resolve(MANIFEST))) return false;
            JsonObject m = JsonParser.parseString(Files.readString(dir.resolve(MANIFEST), StandardCharsets.UTF_8)).getAsJsonObject();
            int count = m.get("count").getAsInt();
            JsonObject files = m.getAsJsonObject("files");
            if (count <= 0 || files.size() != count || count > MAX_ITEMS) return false;
            if (!m.has("defs") || !m.get("defs").isJsonArray() || m.getAsJsonArray("defs").isEmpty()) return false; // older set: build it again once
            for (Map.Entry<String, com.google.gson.JsonElement> e : files.entrySet()) {
                if (!ITEM_NAME.matcher(e.getKey()).matches()) return false;
                Path f = dir.resolve("minecraft").resolve(e.getKey() + ".png");
                if (!Files.isRegularFile(f)) return false;
                if (!sha256(Files.readAllBytes(f)).equals(e.getValue().getAsString())) return false;
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ---------- build ----------

    private void build(String v) throws IOException, InterruptedException {
        long start = System.nanoTime();
        Path downloaded = jarsDir.resolve(v).resolve("client.jar");
        Path jar = null;
        IconRenderer renderer = null;
        boolean weDownloaded = false;
        try {
            // The same candidates GameIcons tries; the first one that opens wins, a bad one does not stop the search.
            for (Path candidate : GameAssets.candidates(v, override)) {
                if (!Files.isRegularFile(candidate) || !Files.isReadable(candidate)) continue;
                try {
                    renderer = IconRenderer.open(candidate, size);
                    jar = candidate;
                    break;
                } catch (IOException | RuntimeException e) {
                    System.out.println("Icon set " + v + ": can't use " + candidate + " (" + e.getMessage() + ")");
                }
            }
            if (renderer == null) {
                if (fetcher == null) throw new ClientJarFetcher.PermanentException("no usable client jar for " + v + " on this machine and iconDownload=false");
                Files.deleteIfExists(downloaded); // a leftover is never trusted; the fetch verifies a fresh one
                System.out.println("Icon set " + v + ": downloading the official client jar from Mojang (once)...");
                weDownloaded = true;
                fetcher.fetch(v, downloaded);
                System.out.printf("Icon set %s: downloaded %.1f MB (sha1 verified) in %.1f s.%n", v, Files.size(downloaded) / 1048576.0, (System.nanoTime() - start) / 1e9);
                try {
                    renderer = IconRenderer.open(downloaded, size);
                } catch (IOException | RuntimeException e) {
                    throw new ClientJarFetcher.PermanentException("the downloaded jar has no item definitions the renderer can use (" + e.getMessage() + ")");
                }
                jar = downloaded;
            }
            render(v, jar, renderer, start);
        } finally {
            if (renderer != null) renderer.close();
            if (weDownloaded) {
                Files.deleteIfExists(downloaded); // the set is complete without it; it is downloaded again only if the set goes missing
                try {
                    Files.deleteIfExists(downloaded.getParent());
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void render(String v, Path jar, IconRenderer r, long start) throws IOException, InterruptedException {
        List<String> names = itemNames(jar);
        if (names.isEmpty()) throw new ClientJarFetcher.PermanentException("the jar has no item definitions");
        Path tmp = Files.createDirectories(iconsDir).resolve("game-" + v + ".build-" + Long.toHexString(System.nanoTime()));
        try {
            Files.createDirectories(tmp.resolve("minecraft"));
            Map<String, String> hashes = new TreeMap<>();
            long bytes = 0;
            for (String name : names) {
                if (closed) throw new InterruptedException();
                Optional<BufferedImage> img;
                try {
                    img = r.render("minecraft:" + name);
                } catch (RuntimeException e) {
                    continue; // one broken definition does not stop the set
                }
                if (img.isEmpty()) continue; // block-entity items etc.: the wiki fallback covers them
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!ImageIO.write(img.get(), "png", out)) continue;
                byte[] png = out.toByteArray();
                bytes += png.length;
                if (bytes > MAX_PACK_BYTES) throw new ClientJarFetcher.PermanentException("icon set larger than " + MAX_PACK_BYTES + " bytes");
                Files.write(tmp.resolve("minecraft").resolve(name + ".png"), png);
                hashes.put(name, sha256(png));
            }
            if (hashes.isEmpty()) throw new ClientJarFetcher.PermanentException("the jar rendered no icons");
            Path target = packDir(v);
            Path targetNs = target.resolve("minecraft");
            Files.createDirectories(targetNs);
            Files.deleteIfExists(target.resolve(MARKER));
            for (String name : hashes.keySet()) {
                Path src = tmp.resolve("minecraft").resolve(name + ".png");
                Path dst = targetNs.resolve(name + ".png");
                try {
                    Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            JsonObject files = new JsonObject();
            hashes.forEach(files::addProperty);
            JsonArray defs = new JsonArray();
            names.forEach(defs::add);
            JsonObject manifest = new JsonObject();
            manifest.addProperty("version", v);
            manifest.addProperty("count", hashes.size());
            manifest.addProperty("bytes", bytes);
            manifest.add("files", files);
            manifest.add("defs", defs);
            AtomicFiles.write(target.resolve(MANIFEST), manifest.toString().getBytes(StandardCharsets.UTF_8));
            AtomicFiles.write(target.resolve(MARKER), new byte[0]); // last: the set is complete only now
            defsCache.remove(v);
            System.out.printf("Icon set %s: %d icons (%d item definitions, %.1f MB on disk) in %.1f s.%n", v, hashes.size(), names.size(),
                bytes / 1048576.0, (System.nanoTime() - start) / 1e9);
        } finally {
            deleteTree(tmp);
        }
    }

    /** Item definition names in the jar; checks the entry count and the total uncompressed size first. Reads names only. */
    static List<String> itemNames(Path jar) throws IOException {
        List<String> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            long total = 0;
            int n = 0;
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (++n > MAX_ENTRIES) throw new ClientJarFetcher.PermanentException("jar has too many entries");
                if (e.getSize() > 0) total += e.getSize();
                if (total > MAX_UNCOMPRESSED) throw new ClientJarFetcher.PermanentException("jar is too large when unpacked");
                java.util.regex.Matcher m = ITEM_ENTRY.matcher(e.getName());
                if (m.matches() && !e.isDirectory()) {
                    if (out.size() >= MAX_ITEMS) throw new ClientJarFetcher.PermanentException("jar has too many item definitions");
                    out.add(m.group(1));
                }
            }
        }
        return out;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
        try {
            worker.awaitTermination(3, TimeUnit.SECONDS); // so no half-written file is left behind
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        states.replaceAll((v, s) -> s == State.READY ? s : State.FAILED); // a scheduled retry that will never run
    }
}
