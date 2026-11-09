package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Item icons, cached on disk as static PNGs under data/icons/<ns>/<path>.png.
 *
 * <p>Pipeline: ids from stored indexes are queued (deduped) by {@link #prefetch} or by a browser request.
 * One resolver thread takes up to 50 wiki titles at a time and resolves them with a single MediaWiki
 * {@code api.php?action=query&prop=imageinfo} call; 4 download threads then fetch the image URLs, or fall back
 * to the mcasset.cloud item/block textures when the wiki definitely has no icon. Every image is decoded and re-encoded as a PNG of its first frame,
 * at most 64 px, so animated GIFs never reach the browser.
 *
 * <p>Rate limits make work wait; they never write a marker. {@code .retry} (2 min) is written only after real
 * network errors, 5xx or 429; {@code .miss} (24 h) only when every source said "not found".
 * Requests never block: a queued or in-progress icon answers 503 with Retry-After.
 */
public final class IconService implements AutoCloseable {
    public static final Pattern ID_PART = Pattern.compile("^[a-z0-9_][a-z0-9_.-]{0,127}$");
    public static final Pattern MC_VERSION = Pattern.compile("^[0-9][0-9.]{0,31}$");
    public static final String DEFAULT_MC = "1.21.11";

    private static final String USER_AGENT = "QwerteStashManager/1.0 (local stash viewer)";
    private static final String WIKI_API = "https://minecraft.wiki/api.php?action=query&format=json&formatversion=2"
        + "&prop=imageinfo&iiprop=url&redirects=1&titles=";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long MAX_ICON_BYTES = 2L * 1024 * 1024;
    private static final long MAX_API_BYTES = 2L * 1024 * 1024;
    private static final long MISS_TTL_MS = 24L * 60 * 60 * 1000;
    private static final long RETRY_TTL_MS = 2L * 60 * 1000;
    private static final int MAX_REDIRECTS = 3;
    private static final int TITLES_PER_CALL = 50;
    private static final int MAX_SIZE = 64;
    /** Characters that can't appear in a MediaWiki title (| would also split the titles list). */
    private static final Pattern BAD_TITLE = Pattern.compile("[|#<>\\[\\]{}\\x00-\\x1f]");

    static final String CACHE_ICON = "private, max-age=604800";
    static final String CACHE_MISS = "private, max-age=3600";
    static final String CACHE_UNKNOWN = "private, max-age=60";
    static final String CACHE_PENDING = "no-store";

    /** Icon bytes, status, content type and Cache-Control. status 503 means "being fetched, ask again soon". */
    public record Icon(int status, byte[] data, String contentType, String cacheControl) {
    }

    private enum Kind { OK, DEFINITE_MISS, TRANSIENT }

    private record Fetched(Kind kind, byte[] data) {
        static final Fetched DEFINITE = new Fetched(Kind.DEFINITE_MISS, null);
        static final Fetched TRANSIENT = new Fetched(Kind.TRANSIENT, null);
    }

    private static final AtomicLong SEQ = new AtomicLong();

    /**
     * One id waiting for (or going through) the pipeline. wanted = a browser asked for it, so it jumps
     * ahead of prefetch work in both the resolver queue and the download queue.
     */
    private static final class Job {
        final String id;
        final String ns;
        final String path;
        final Path nsDir;
        final IndexStore.IconInfo info;
        final long seq = SEQ.incrementAndGet();
        volatile boolean wanted;
        volatile DownloadTask task; // set once the job reached the download queue

        Job(String id, String ns, String path, Path nsDir, IndexStore.IconInfo info) {
            this.id = id;
            this.ns = ns;
            this.path = path;
            this.nsDir = nsDir;
            this.info = info;
        }

        List<String> titles() {
            List<String> out = new ArrayList<>(2);
            String name = info.englishName();
            if (name != null && !name.isBlank() && !BAD_TITLE.matcher(name).find()) {
                out.add("File:Invicon_" + name.trim().replace(' ', '_') + ".png");
            }
            String fromId = "File:Invicon_" + titleCase(path) + ".png";
            if (!out.contains(fromId)) out.add(fromId);
            return out;
        }
    }

    private final Path dir;
    private final Function<String, IndexStore.IconInfo> known;
    private final Predicate<String> knownVersion;
    private final GameIcons game;
    private final boolean wikiEnabled;
    private final ThreadPoolExecutor prefetchPool;
    private final HttpClient http;
    private final Map<String, Job> pending = new ConcurrentHashMap<>();
    private final BlockingDeque<Job> queue = new LinkedBlockingDeque<>();
    private final ThreadPoolExecutor downloaders;
    private final Thread resolver;
    private final RateLimiter apiLimiter = new RateLimiter(10, 1.0);     // ~1 call per 50 ids
    private final RateLimiter imageLimiter = new RateLimiter(10, 10.0);  // ~10 images a second
    private volatile boolean closed;
    // Counters for the log line printed when the queue runs empty.
    private final AtomicInteger statWiki = new AtomicInteger();
    private final AtomicInteger statAssets = new AtomicInteger();
    private final AtomicInteger statMiss = new AtomicInteger();
    private final AtomicInteger statRetry = new AtomicInteger();
    private final AtomicInteger prefetching = new AtomicInteger();
    private final Icon placeholderMiss;
    private final Icon placeholderUnknown;
    private final Icon placeholderPending;

    public IconService(Path dataDir, Function<String, IndexStore.IconInfo> known, Predicate<String> knownVersion,
                       GameIcons game, boolean wikiEnabled) {
        this.knownVersion = knownVersion;
        this.game = game;
        this.wikiEnabled = wikiEnabled;
        this.prefetchPool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(),
            r -> daemon(r, "stash-manager-icon-prefetch"));
        this.dir = dataDir.resolve("icons").toAbsolutePath().normalize();
        this.known = known;
        this.http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(TIMEOUT)
            .build();
        AtomicInteger n = new AtomicInteger();
        // A priority queue so icons a browser is waiting for are downloaded before prefetch work.
        this.downloaders = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(),
            r -> daemon(r, "stash-manager-icon-dl-" + n.incrementAndGet()));
        byte[] png = makePlaceholder();
        this.placeholderMiss = new Icon(200, png, "image/png", CACHE_MISS);
        this.placeholderUnknown = new Icon(200, png, "image/png", CACHE_UNKNOWN);
        this.placeholderPending = new Icon(503, png, "image/png", CACHE_PENDING);
        this.resolver = daemon(this::resolveLoop, "stash-manager-icon-resolver");
        this.resolver.start();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // ---------- startup migration ----------

    /**
     * Version of the on-disk wiki cache. Bump it when cached wiki-pipeline icons can be wrong, so they are
     * fetched again; {@link #migrateCache} then removes them once.
     * <ul>
     *   <li>1 (no marker file): original.</li>
     *   <li>2: a failed wiki image download used to fall through to mcasset's flat block texture
     *       (block/&lt;id&gt;.png) and cache it for good, so block items, shulker boxes in particular, showed a
     *       flat face. Every wiki-cache PNG and .miss marker is removed. Game renders (game-&lt;version&gt;/)
     *       are kept: shulker boxes and other block-entity items were never cached there.</li>
     * </ul>
     */
    static final int CACHE_VERSION = 2;
    static final String CACHE_VERSION_FILE = ".cache-version";

    /**
     * Removes .retry markers and non-PNG icons (from before every icon was normalized to PNG) so they get
     * fetched again. If the cache predates {@link #CACHE_VERSION}, also removes every wiki-cache PNG and .miss
     * marker (not the game-&lt;version&gt; renders), then records the new version. Returns the number of files
     * removed.
     */
    public int migrateCache() {
        if (!Files.isDirectory(dir)) {
            // A new cache starts at the current version, so the next start doesn't treat it as outdated.
            writeCacheVersion();
            return 0;
        }
        boolean outdated = cacheVersion() < CACHE_VERSION;
        List<Path> stale;
        try (Stream<Path> walk = Files.walk(dir, 2)) {
            stale = walk.filter(Files::isRegularFile).filter(p -> {
                if (p.getParent().equals(dir)) return false; // .cache-version and other top-level files
                if (p.getParent().getFileName().toString().startsWith("game-")) return false; // never wiki icons
                String name = p.getFileName().toString();
                if (name.endsWith(".retry") || name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".jpg")) return true;
                return outdated && (name.endsWith(".png") || name.endsWith(".miss"));
            }).toList();
        } catch (IOException e) {
            return 0;
        }
        int removed = 0;
        for (Path p : stale) {
            try {
                if (Files.deleteIfExists(p)) removed++;
            } catch (IOException ignored) {
            }
        }
        if (outdated) {
            writeCacheVersion();
            System.out.println("Icon cache: upgraded to version " + CACHE_VERSION + " (wiki icons are fetched again).");
        }
        return removed;
    }

    private void writeCacheVersion() {
        try {
            AtomicFiles.write(dir.resolve(CACHE_VERSION_FILE), Integer.toString(CACHE_VERSION).getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            System.err.println("Could not write " + dir.resolve(CACHE_VERSION_FILE) + ": " + e.getMessage());
        }
    }

    /** The cache version on disk: 1 when there is no marker (or it is unreadable). */
    int cacheVersion() {
        try {
            Path f = dir.resolve(CACHE_VERSION_FILE);
            if (!Files.isRegularFile(f)) return 1;
            return Integer.parseInt(Files.readString(f, StandardCharsets.US_ASCII).trim());
        } catch (IOException | NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Items drawn by a block-entity renderer in game. Their mcasset block texture is a flat face or particle
     * sprite (block/black_shulker_box.png is one side of the box), never the inventory icon, so the wiki
     * pipeline does not use mcasset for them.
     */
    private static final Pattern BLOCK_ENTITY_ITEM = Pattern.compile(
        "^(?:[a-z_]+_)?(?:shulker_box|chest|bed|banner|head|skull|copper_golem_statue)$|^(?:shield|conduit|decorated_pot)$");

    static boolean blockEntityItem(String path) {
        return BLOCK_ENTITY_ITEM.matcher(path).matches();
    }

    // ---------- requests ----------

    /** ns and path must already match {@link #ID_PART}. Never blocks on upstream. */
    public Icon get(String ns, String path, String requestedMc) {
        Path nsDir = nsDir(ns);
        if (nsDir == null) return placeholderUnknown;
        String id = ns + ":" + path;
        IndexStore.IconInfo info = known.apply(id);
        if (info == null) {
            // Not in any index: no render, no fetch, nothing written. Old wiki files are still served.
            Icon cached = wikiEnabled ? readCache(nsDir, path) : null;
            return cached != null && cached.status() == 200 && cached != placeholderMiss ? cached : placeholderUnknown;
        }

        // 1. The game jar for the page's Minecraft version (when some index has that version), else the index's.
        String mc = requestedMc != null && MC_VERSION.matcher(requestedMc).matches() && knownVersion.test(requestedMc)
            ? requestedMc : info.mcVersion();
        game.requestPack(mc);
        // The full icon set for this version is being downloaded/rendered: wait for it rather than hit the wiki.
        if (game.packPending(mc) && !game.cached(mc, ns, path) && !game.available(mc)) return placeholderPending;
        byte[] rendered = game.get(mc, ns, path);
        if (rendered != null) return new Icon(200, rendered, "image/png", CACHE_ICON);
        if (!wikiEnabled) return placeholderMiss;

        // 2. The wiki cache, then the wiki pipeline.
        Icon cached = readCache(nsDir, path);
        if (cached != null) return cached;
        Job job = new Job(id, ns, path, nsDir, info);
        job.wanted = true;
        if (!enqueue(job, true)) bump(pending.get(id));
        return placeholderPending;
    }

    /** A browser is waiting for this queued job: move it to the front of whichever queue it is in. */
    private void bump(Job job) {
        if (job == null || job.wanted) return;
        job.wanted = true;
        if (queue.remove(job)) {
            queue.addFirst(job);
            return;
        }
        DownloadTask task = job.task;
        if (task != null && downloaders.getQueue().remove(task)) {
            DownloadTask again = new DownloadTask(job, task.wikiUrl, task.wikiTransient);
            job.task = again;
            downloaders.execute(again);
        }
    }

    /**
     * Makes every id's icon ready before the browser asks, in the background: renders what the game jar can
     * draw (CPU only, a few ms each) and queues only the rest for the wiki.
     */
    public void prefetch(Map<String, IndexStore.IconInfo> ids) {
        if (closed || ids.isEmpty()) return;
        try {
            prefetchPool.execute(() -> prefetchNow(ids));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void prefetchNow(Map<String, IndexStore.IconInfo> ids) {
        prefetching.incrementAndGet();
        try {
            prefetchIds(ids);
        } finally {
            prefetching.decrementAndGet();
            logWikiSummary();
        }
    }

    private void prefetchIds(Map<String, IndexStore.IconInfo> ids) {
        long start = System.nanoTime();
        int rendered = 0, alreadyRendered = 0, wikiCached = 0, queued = 0;
        for (Map.Entry<String, IndexStore.IconInfo> e : ids.entrySet()) {
            if (closed) return;
            String id = e.getKey();
            int colon = id.indexOf(':');
            String ns = colon < 0 ? "minecraft" : id.substring(0, colon);
            String path = colon < 0 ? id : id.substring(colon + 1);
            if (!ID_PART.matcher(ns).matches() || !ID_PART.matcher(path).matches()) continue;
            Path nsDir = nsDir(ns);
            if (nsDir == null) continue;
            String mc = e.getValue().mcVersion();
            game.requestPack(mc);
            if (game.packPending(mc) && !game.available(mc)) continue; // the set being built covers it; later requests fill the rest
            if (game.cached(mc, ns, path)) {
                alreadyRendered++;
                continue;
            }
            if (game.ensure(mc, ns, path)) {
                rendered++;
                continue;
            }
            if (!wikiEnabled) continue;
            if (pending.containsKey(ns + ":" + path)) continue;
            if (settled(nsDir, path)) {
                wikiCached++;
                continue;
            }
            if (enqueue(new Job(ns + ":" + path, ns, path, nsDir, e.getValue()), false)) queued++;
        }
        if (rendered > 0 || queued > 0) {
            System.out.printf("Icons for %d item ids: %d rendered from the game now, %d already rendered, %d from the wiki cache, "
                + "%d queued for the wiki (%.1f s).%n", ids.size(), rendered, alreadyRendered, wikiCached, queued,
                (System.nanoTime() - start) / 1e9);
        }
    }

    /** Number of ids queued or in progress. */
    public int pendingCount() {
        return pending.size();
    }

    private boolean enqueue(Job job, boolean front) {
        if (closed || pending.putIfAbsent(job.id, job) != null) return false;
        if (front) queue.addFirst(job);
        else queue.addLast(job);
        return true;
    }

    private void done(Job job) {
        pending.remove(job.id, job);
        logWikiSummary();
    }

    /** One summary line once the wiki queue is empty and no prefetch is still adding to it. */
    private synchronized void logWikiSummary() {
        if (pending.isEmpty() && prefetching.get() == 0 && statWiki.get() + statAssets.get() + statMiss.get() + statRetry.get() > 0) {
            System.out.println("Icons fetched: " + statWiki.getAndSet(0) + " from the Minecraft Wiki, " + statAssets.getAndSet(0)
                + " from mcasset.cloud, " + statMiss.getAndSet(0) + " not found, " + statRetry.getAndSet(0) + " to retry later.");
        }
    }

    private Path nsDir(String ns) {
        Path nsDir = dir.resolve(ns).normalize();
        // game-<version> folders hold game renders (see GameIcons), never wiki icons.
        if (ns.startsWith("game-")) return null;
        return nsDir.startsWith(dir) && dir.equals(nsDir.getParent()) ? nsDir : null;
    }

    private Icon readCache(Path nsDir, String path) {
        try {
            Path file = nsDir.resolve(path + ".png");
            if (Files.isRegularFile(file)) return new Icon(200, Files.readAllBytes(file), "image/png", CACHE_ICON);
            if (freshMarker(nsDir.resolve(path + ".miss"), MISS_TTL_MS)) return placeholderMiss;
            if (freshMarker(nsDir.resolve(path + ".retry"), RETRY_TTL_MS)) return placeholderPending;
        } catch (IOException ignored) {
        }
        return null;
    }

    /** Cached, or a fresh marker: like readCache without reading the image bytes. */
    private static boolean settled(Path nsDir, String path) {
        try {
            return Files.isRegularFile(nsDir.resolve(path + ".png"))
                || freshMarker(nsDir.resolve(path + ".miss"), MISS_TTL_MS)
                || freshMarker(nsDir.resolve(path + ".retry"), RETRY_TTL_MS);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean freshMarker(Path marker, long ttl) throws IOException {
        if (!Files.isRegularFile(marker)) return false;
        if (System.currentTimeMillis() - Files.getLastModifiedTime(marker).toMillis() < ttl) return true;
        Files.deleteIfExists(marker);
        return false;
    }

    // ---------- resolver: batched wiki lookups ----------

    private void resolveLoop() {
        while (!closed) {
            List<Job> batch = new ArrayList<>();
            try {
                Job first = queue.take();
                batch.add(first);
                int titles = first.titles().size();
                // Gather whatever else is queued (briefly), up to the 50-title limit of one api.php call.
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150);
                while (titles < TITLES_PER_CALL) {
                    Job next = queue.peek();
                    if (next == null) {
                        long left = until - System.nanoTime();
                        if (left <= 0) break;
                        next = queue.poll(left, TimeUnit.NANOSECONDS);
                        if (next == null) break;
                        if (titles + next.titles().size() > TITLES_PER_CALL) {
                            queue.addFirst(next); // goes into the next batch
                            break;
                        }
                    } else {
                        if (titles + next.titles().size() > TITLES_PER_CALL) break;
                        queue.poll();
                    }
                    batch.add(next);
                    titles += next.titles().size();
                }
                resolveBatch(batch);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                System.err.println("Icon resolver error: " + e);
                for (Job j : batch) finish(j, Kind.TRANSIENT, null);
            }
        }
    }

    private void resolveBatch(List<Job> batch) throws InterruptedException {
        List<Job> vanilla = new ArrayList<>();
        for (Job j : batch) {
            if (Files.isRegularFile(j.nsDir.resolve(j.path + ".png"))) {
                done(j);
            } else if (!j.ns.equals("minecraft")) {
                finish(j, Kind.DEFINITE_MISS, null); // only vanilla items have wiki icons and vanilla textures
            } else {
                vanilla.add(j);
            }
        }
        if (vanilla.isEmpty()) return;

        Set<String> titles = new LinkedHashSet<>();
        for (Job j : vanilla) titles.addAll(j.titles());
        Map<String, String> urls = new HashMap<>();
        boolean apiOk = wikiLookup(titles, urls);

        for (Job j : vanilla) {
            String url = null;
            for (String t : j.titles()) {
                url = urls.get(t);
                if (url != null) break;
            }
            String found = url;
            // If the API call failed, the wiki is skipped for this round; mcasset may still have the texture.
            boolean wikiTransient = !apiOk;
            DownloadTask task = new DownloadTask(j, found, wikiTransient);
            j.task = task;
            downloaders.execute(task);
        }
    }

    /** A queued download. Wanted (browser-requested) tasks sort first, then oldest first. */
    private final class DownloadTask implements Runnable, Comparable<DownloadTask> {
        final Job job;
        final String wikiUrl;
        final boolean wikiTransient;
        final boolean wanted;

        DownloadTask(Job job, String wikiUrl, boolean wikiTransient) {
            this.job = job;
            this.wikiUrl = wikiUrl;
            this.wikiTransient = wikiTransient;
            this.wanted = job.wanted;
        }

        @Override
        public void run() {
            download(job, wikiUrl, wikiTransient);
        }

        @Override
        public int compareTo(DownloadTask o) {
            if (wanted != o.wanted) return wanted ? -1 : 1;
            return Long.compare(job.seq, o.job.seq);
        }
    }

    /** Fills requestedTitle -> image URL. Returns false on a network error, 5xx or 429. */
    private boolean wikiLookup(Set<String> titles, Map<String, String> out) throws InterruptedException {
        apiLimiter.acquire();
        StringBuilder joined = new StringBuilder();
        for (String t : titles) {
            if (!joined.isEmpty()) joined.append('|');
            joined.append(t);
        }
        Response r = get(WIKI_API + URLEncoder.encode(joined.toString(), StandardCharsets.UTF_8), MAX_API_BYTES);
        if (r.kind != Kind.OK) return r.kind != Kind.TRANSIENT;
        if (!r.type.equals("application/json")) return true;
        JsonObject query;
        try {
            JsonElement root = JsonParser.parseString(new String(r.data, StandardCharsets.UTF_8));
            query = root.getAsJsonObject().getAsJsonObject("query");
        } catch (RuntimeException e) {
            return true;
        }
        if (query == null) return true;

        // requested title -> normalized title -> redirect target -> page
        Map<String, String> normalized = fromTo(query.get("normalized"));
        Map<String, String> redirects = fromTo(query.get("redirects"));
        Map<String, String> pageUrls = new HashMap<>();
        JsonElement pages = query.get("pages");
        if (pages != null && pages.isJsonArray()) {
            for (JsonElement p : pages.getAsJsonArray()) {
                if (!p.isJsonObject()) continue;
                JsonObject page = p.getAsJsonObject();
                if (!page.has("title") || !page.has("imageinfo") || !page.get("imageinfo").isJsonArray()) continue;
                JsonArray info = page.getAsJsonArray("imageinfo");
                if (info.isEmpty() || !info.get(0).isJsonObject()) continue;
                JsonElement url = info.get(0).getAsJsonObject().get("url");
                if (url != null && url.isJsonPrimitive()) pageUrls.put(page.get("title").getAsString(), url.getAsString());
            }
        }
        for (String t : titles) {
            String title = normalized.getOrDefault(t, t);
            for (int i = 0; i < 3 && redirects.containsKey(title); i++) title = redirects.get(title);
            String url = pageUrls.get(title);
            if (url != null) out.put(t, url);
        }
        return true;
    }

    private static Map<String, String> fromTo(JsonElement list) {
        Map<String, String> out = new HashMap<>();
        if (list == null || !list.isJsonArray()) return out;
        for (JsonElement e : list.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (o.has("from") && o.has("to")) out.put(o.get("from").getAsString(), o.get("to").getAsString());
        }
        return out;
    }

    // ---------- downloads ----------

    /**
     * The wiki icon first. mcasset textures are a last resort, used only when the wiki definitely has no icon:
     * a temporary wiki failure (API or image download) writes a .retry marker instead, because the mcasset
     * block texture of a block is a flat face and would otherwise be cached for good. Block-entity items never
     * use mcasset (see {@link #BLOCK_ENTITY_ITEM}).
     */
    private void download(Job j, String wikiUrl, boolean wikiTransient) {
        try {
            if (wikiUrl != null) {
                imageLimiter.acquire();
                Fetched f = fetchImage(wikiUrl);
                if (f.kind == Kind.OK) {
                    statWiki.incrementAndGet();
                    finish(j, Kind.OK, f.data);
                    return;
                }
                if (f.kind == Kind.TRANSIENT) wikiTransient = true;
            }
            if (wikiTransient) {
                finish(j, Kind.TRANSIENT, null);
                return;
            }
            if (blockEntityItem(j.path)) {
                finish(j, Kind.DEFINITE_MISS, null);
                return;
            }
            boolean anyTransient = false;
            String assets = "https://assets.mcasset.cloud/" + j.info.mcVersion() + "/assets/minecraft/textures/";
            for (String url : List.of(assets + "item/" + j.path + ".png", assets + "block/" + j.path + ".png")) {
                imageLimiter.acquire();
                Fetched f = fetchImage(url);
                if (f.kind == Kind.OK) {
                    statAssets.incrementAndGet();
                    finish(j, Kind.OK, f.data);
                    return;
                }
                if (f.kind == Kind.TRANSIENT) anyTransient = true;
            }
            finish(j, anyTransient ? Kind.TRANSIENT : Kind.DEFINITE_MISS, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            done(j);
        } catch (RuntimeException e) {
            System.err.println("Icon download for " + j.id + " failed: " + e);
            finish(j, Kind.TRANSIENT, null);
        }
    }

    /** Stores the result: a PNG, a .miss (24 h) or a .retry (2 min) marker. Then the id leaves the queue. */
    private void finish(Job j, Kind kind, byte[] png) {
        try {
            switch (kind) {
                case OK -> {
                    AtomicFiles.write(j.nsDir.resolve(j.path + ".png"), png);
                    Files.deleteIfExists(j.nsDir.resolve(j.path + ".retry"));
                    Files.deleteIfExists(j.nsDir.resolve(j.path + ".miss"));
                }
                case DEFINITE_MISS -> {
                    statMiss.incrementAndGet();
                    AtomicFiles.write(j.nsDir.resolve(j.path + ".miss"), new byte[0]);
                }
                case TRANSIENT -> {
                    statRetry.incrementAndGet();
                    AtomicFiles.write(j.nsDir.resolve(j.path + ".retry"), new byte[0]);
                }
            }
        } catch (IOException e) {
            System.err.println("Could not write icon cache for " + j.id + ": " + e.getMessage());
        } finally {
            done(j);
        }
    }

    private Fetched fetchImage(String url) {
        Response r = get(url, MAX_ICON_BYTES);
        if (r.kind != Kind.OK) return r.kind == Kind.TRANSIENT ? Fetched.TRANSIENT : Fetched.DEFINITE;
        if (!r.type.startsWith("image/") || r.type.contains("svg")) return Fetched.DEFINITE;
        byte[] png = toStaticPng(r.data);
        return png == null ? Fetched.DEFINITE : new Fetched(Kind.OK, png);
    }

    /**
     * Decodes PNG/GIF (ImageIO.read gives the first frame of an animated GIF) and re-encodes as PNG.
     * Vertical animation strips (16x256 textures) are cropped to their first square frame, and anything
     * larger than 64 px is scaled down with nearest-neighbour. Returns null when the image can't be decoded
     * (WebP has no ImageIO reader in the JDK, so it is skipped).
     */
    static byte[] toStaticPng(byte[] data) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return null;
        int w = img.getWidth();
        int h = img.getHeight();
        if (h > w && h % w == 0) {
            img = img.getSubimage(0, 0, w, w);
            h = w;
        }
        // Always re-encoded, even small PNGs: that also flattens an animated PNG to its default frame.
        if (w > MAX_SIZE || h > MAX_SIZE) {
            double scale = Math.min((double) MAX_SIZE / w, (double) MAX_SIZE / h);
            int nw = Math.max(1, (int) Math.round(w * scale));
            int nh = Math.max(1, (int) Math.round(h * scale));
            BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < nh; y++) {
                int sy = Math.min(h - 1, (int) ((y + 0.5) * h / nh));
                for (int x = 0; x < nw; x++) {
                    int sx = Math.min(w - 1, (int) ((x + 0.5) * w / nw));
                    out.setRGB(x, y, img.getRGB(sx, sy));
                }
            }
            img = out;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(img, "png", out)) return null;
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    // ---------- HTTP ----------

    private record Response(Kind kind, byte[] data, String type) {
        static final Response DEFINITE = new Response(Kind.DEFINITE_MISS, null, "");
        static final Response TRANSIENT = new Response(Kind.TRANSIENT, null, "");
    }

    static boolean allowedTarget(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) return false;
        if (uri.getPort() != -1 && uri.getPort() != 443) return false;
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (host.equals("minecraft.wiki")) return path.startsWith("/images/") || path.equals("/api.php");
        return host.equals("assets.mcasset.cloud");
    }

    /** GET with manual redirects (max 3, each checked against the allowlist) and a size cap. */
    private Response get(String url, long maxBytes) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return Response.DEFINITE;
        }
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                if (!allowedTarget(uri)) {
                    System.err.println("Icon fetch refused " + uri.getScheme() + "://" + uri.getHost() + uri.getRawPath());
                    return Response.DEFINITE;
                }
                HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "image/png,image/gif;q=0.9,application/json;q=0.8,*/*;q=0.1")
                    .GET()
                    .build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = response.body()) {
                    int status = response.statusCode();
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        String location = response.headers().firstValue("Location").orElse(null);
                        if (location == null) return Response.TRANSIENT;
                        uri = uri.resolve(location.trim());
                        continue;
                    }
                    if (status == 404 || status == 410) return Response.DEFINITE;
                    if (status == 429 || status >= 500) return Response.TRANSIENT;
                    if (status != 200) return Response.DEFINITE;
                    String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                    int semi = type.indexOf(';');
                    if (semi >= 0) type = type.substring(0, semi);
                    long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                    if (declared > maxBytes) return Response.DEFINITE;
                    byte[] data = readCapped(in, maxBytes);
                    if (data == null || data.length == 0) return Response.DEFINITE;
                    return new Response(Kind.OK, data, type.trim());
                }
            }
            return Response.TRANSIENT; // too many redirects
        } catch (IOException | IllegalArgumentException | UncheckedIOException e) {
            return Response.TRANSIENT; // timeouts, DNS, connection resets
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.TRANSIENT;
        }
    }

    static byte[] readCapped(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static String titleCase(String path) {
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("_")) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append('_');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    /** A 16x16 purple/black missing-texture checker. */
    private static byte[] makePlaceholder() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                boolean purple = ((x / 8) + (y / 8)) % 2 == 0;
                img.setRGB(x, y, purple ? 0xFFF800F8 : 0xFF000000);
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        closed = true;
        resolver.interrupt();
        downloaders.shutdownNow();
        prefetchPool.shutdownNow();
        game.close();
    }

    /** A token bucket. acquire() waits for a token; throttling only delays work, it never fails it. */
    static final class RateLimiter {
        private final double capacity;
        private final double perMs;
        private double tokens;
        private long last = System.nanoTime();

        RateLimiter(int burst, double perSecond) {
            this.capacity = burst;
            this.perMs = perSecond / 1000.0;
            this.tokens = burst;
        }

        synchronized boolean tryAcquire() {
            refill();
            if (tokens < 1) return false;
            tokens -= 1;
            return true;
        }

        void acquire() throws InterruptedException {
            while (true) {
                long waitMs;
                synchronized (this) {
                    refill();
                    if (tokens >= 1) {
                        tokens -= 1;
                        return;
                    }
                    waitMs = (long) Math.ceil((1 - tokens) / perMs);
                }
                Thread.sleep(Math.max(1, waitMs));
            }
        }

        private void refill() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - last) / 1_000_000.0 * perMs);
            last = now;
        }
    }
}
