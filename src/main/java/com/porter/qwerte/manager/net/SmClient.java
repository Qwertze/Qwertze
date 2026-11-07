package com.porter.qwerte.manager.net;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * The window's connection to the Stash Manager server.
 *
 * <ul>
 *   <li>The session cookie is only added to requests for the origin it was issued for, and redirects are never
 *   followed, so the cookie cannot be sent anywhere else. No proxy is used for the same reason.</li>
 *   <li>Answers are read and parsed on this client's own threads. Callbacks run on the {@code main} executor (the
 *   Minecraft client thread) and are dropped when the {@link #generation()} changed since the request was made.</li>
 *   <li>Nothing about a request except its method, path and status is ever logged: no body, header, query or
 *   cookie.</li>
 * </ul>
 */
public final class SmClient {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(8);
    public static final Duration INDEX_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_BODY = 32 * 1024 * 1024;
    /** The largest body {@link #postBytes} sends (the schematic limit of the server). */
    public static final int MAX_UPLOAD = 8 * 1024 * 1024;
    private static final Gson GSON = new Gson();
    private static final Logger LOG = LoggerFactory.getLogger("Qwerte/stash-window");

    private final HttpClient http;
    private final ThreadPoolExecutor pool;
    private final Executor main;
    private final AtomicInteger generation = new AtomicInteger();

    private volatile ServerUrl base;
    private volatile String token;
    private volatile String tokenOrigin;
    private volatile Runnable unauthorized;
    private volatile boolean enabled = true;
    private volatile java.util.function.Predicate<ServerUrl> allowed = u -> true;
    private final java.util.Set<InputStream> streams = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** @param main runs a task on the client thread */
    public SmClient(Executor main) {
        this.main = main;
        AtomicInteger n = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "qwerte-sm-window-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.pool.allowCoreThreadTimeOut(true);
        this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(5))
            .executor(pool)
            .build();
    }

    /**
     * Off (the stash-window module's connect-to-server setting is off): no request is ever made, whatever address or
     * session is known; every call is answered at once with a network error.
     */
    public void setEnabled(boolean on) {
        this.enabled = on;
        bumpGeneration();
        if (!on) closeStreams();
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Which addresses may be used at all (the window refuses plain http to a foreign address unless allowed). Checked when the address
     * is set and again before every request, so a refused address is never contacted whatever the caller did.
     */
    public void setAllowed(java.util.function.Predicate<ServerUrl> allowed) {
        this.allowed = allowed == null ? u -> true : allowed;
        if (base != null && !usableAddress(base)) closeStreams();
    }

    private boolean usableAddress(ServerUrl u) {
        try {
            return allowed.test(u);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private ServerUrl usable() {
        ServerUrl b = enabled ? base : null;
        return b != null && usableAddress(b) ? b : null;
    }

    /** Closes every event stream that is open. */
    public void closeStreams() {
        for (InputStream s : streams.toArray(new InputStream[0])) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing only frees the connection
            }
        }
        streams.clear();
    }

    /** Another address: answers to requests made for the old one are dropped. */
    public void setBase(ServerUrl url) {
        this.base = url;
        bumpGeneration();
        if (url != null && !usableAddress(url)) closeStreams();
    }

    public ServerUrl base() {
        return base;
    }

    /** The cookie is sent only to {@code origin}. Null token: no cookie at all. */
    public void setToken(String token, String origin) {
        this.token = token;
        this.tokenOrigin = token == null ? null : origin;
    }

    public boolean hasToken() {
        return token != null;
    }

    /** Called on the client thread when a request that carried the cookie got 401. */
    public void onUnauthorized(Runnable hook) {
        this.unauthorized = hook;
    }

    public int generation() {
        return generation.get();
    }

    /** Makes every answer still on its way be dropped. */
    public void bumpGeneration() {
        generation.incrementAndGet();
    }

    // ---------- requests ----------

    public void get(String path, Map<String, String> query, Consumer<ApiResult> done) {
        get(path, query, null, false, DEFAULT_TIMEOUT, null, (r, ignored) -> done.accept(r));
    }

    /**
     * @param etag   sent as {@code If-None-Match} when not null
     * @param parser runs on a client thread for a successful answer, so big bodies don't stall the game
     */
    public <T> void get(String path, Map<String, String> query, String etag, boolean gzip, Duration timeout,
                        Parser<T> parser, BiConsumer<ApiResult, T> done) {
        run("GET", path, query, null, null, etag, gzip, timeout, true, false, parser, (r, t, token) -> done.accept(r, t));
    }

    public void post(String path, JsonObject body, Consumer<ApiResult> done) {
        run("POST", path, null, body, null, null, false, DEFAULT_TIMEOUT, true, false, null, (r, t, token) -> done.accept(r));
    }

    /**
     * A POST of raw bytes (a file, as {@code application/octet-stream}) with the session cookie and {@code X-Stash-Manager: 1},
     * under the same rules as {@link #post}: same origin only, no redirect followed, refused addresses never contacted. The
     * query values are encoded here. The caller keeps the array unchanged until the answer arrives.
     */
    public void postBytes(String path, Map<String, String> query, byte[] body, Duration timeout, Consumer<ApiResult> done) {
        if (body != null && body.length > MAX_UPLOAD) {
            // Nothing is sent: the server refuses more than this anyway
            done.accept(new ApiResult(413, com.google.gson.JsonNull.INSTANCE, null, 0, ApiError.Kind.BAD_REQUEST));
            return;
        }
        run("POST", path, query, null, body == null ? new byte[0] : body, null, false, timeout == null ? UPLOAD_TIMEOUT : timeout,
            true, false, null, (r, t, token) -> done.accept(r));
    }

    /** Logs in without sending any old cookie. {@code token} is the new session value, or null. */
    public void login(String user, String password, BiConsumer<ApiResult, String> done) {
        JsonObject body = new JsonObject();
        if (user != null && !user.isBlank()) body.addProperty("username", user.trim());
        body.addProperty("password", password);
        run("POST", "/api/v1/auth/login", null, body, null, null, false, DEFAULT_TIMEOUT, false, true, null, (r, t, token) -> done.accept(r, token));
    }

    /** An opened event stream: the status, and for any status a body to read (or close). */
    public record Open(int status, long retryAfterSec, InputStream body) {
    }

    /**
     * Opens a long-lived GET (the live viewer's event stream) and returns once the headers are in. Blocks: call it from your
     * own thread, never the client thread. Only the cookie of the origin it was issued for is sent, nothing is followed and
     * nothing about the request is logged. The caller reads and closes {@link Open#body()}.
     * @throws IOException when disabled, not configured or the server could not be reached
     */
    public Open openStream(String path, Map<String, String> query) throws IOException, InterruptedException {
        ServerUrl b = usable();
        if (b == null) throw new IOException("no server");
        HttpRequest.Builder rb;
        try {
            URI uri = b.resolve(path, query);
            rb = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Accept", "text/event-stream").GET();
            String t = token;
            if (t != null && b.origin().equals(tokenOrigin) && b.origin().equals(ServerUrl.originOf(uri))) {
                rb.header("Cookie", Cookies.NAME + "=" + t);
            }
        } catch (RuntimeException e) {
            throw new IOException("bad request");
        }
        HttpResponse<InputStream> response = http.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        long retry = 0;
        try {
            retry = Long.parseLong(response.headers().firstValue("retry-after").orElse("0").trim());
        } catch (NumberFormatException ignored) {
            // an HTTP date or junk
        }
        LOG.debug("stash-window: GET {} -> {}", path, response.statusCode());
        InputStream tracked = new java.io.FilterInputStream(response.body()) {
            @Override
            public void close() throws IOException {
                streams.remove(this);
                super.close();
            }
        };
        streams.add(tracked);
        if (!enabled || !usableAddress(b)) {
            tracked.close();
            throw new IOException("closed");
        }
        return new Open(response.statusCode(), Math.max(0, Math.min(ApiResult.MAX_WAIT, retry)), tracked);
    }

    /** From any thread: a request that carried the cookie got 401. Runs the unauthorized hook on the client thread, unless the generation moved on. */
    public void notifyUnauthorized(int generationOfRequest) {
        main.execute(() -> {
            Runnable hook = unauthorized;
            if (generationOfRequest == generation.get() && hook != null) hook.run();
        });
    }

    public interface Parser<T> {
        T parse(ApiResult result);
    }

    private interface Delivery<T> {
        void deliver(ApiResult result, T parsed, String loginToken);
    }

    private <T> void run(String method, String path, Map<String, String> query, JsonObject body, byte[] raw, String etag, boolean gzip,
                         Duration timeout, boolean cookie, boolean readCookie, Parser<T> parser, Delivery<T> delivery) {
        final int gen = generation.get();
        ServerUrl b = usable();
        HttpRequest request = null;
        boolean sentCookie = false;
        if (b != null) {
            try {
                URI uri = b.resolve(path, query);
                HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json");
                String t = token;
                if (cookie && t != null && b.origin().equals(tokenOrigin) && b.origin().equals(ServerUrl.originOf(uri))) {
                    rb.header("Cookie", Cookies.NAME + "=" + t);
                    sentCookie = true;
                }
                if (etag != null && !etag.isEmpty()) rb.header("If-None-Match", etag);
                if (gzip) rb.header("Accept-Encoding", "gzip");
                if (raw != null) {
                    rb.header("X-Stash-Manager", "1").header("Content-Type", "application/octet-stream");
                    rb.POST(HttpRequest.BodyPublishers.ofByteArray(raw));
                } else if (body != null) {
                    rb.header("X-Stash-Manager", "1").header("Content-Type", "application/json");
                    rb.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
                } else {
                    rb.GET();
                }
                request = rb.build();
            } catch (RuntimeException e) {
                request = null;
                sentCookie = false;
            }
        }
        if (request == null) {
            main.execute(() -> {
                if (gen == generation.get()) delivery.deliver(ApiResult.network(), null, null);
            });
            return;
        }

        final boolean sent = sentCookie;
        CompletableFuture<HttpResponse<InputStream>> future;
        try {
            future = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (RuntimeException e) {
            main.execute(() -> {
                if (gen == generation.get()) delivery.deliver(ApiResult.network(), null, null);
            });
            return;
        }
        future.handleAsync((response, error) -> {
            ApiResult result;
            String loginToken = null;
            if (error != null || response == null) {
                result = ApiResult.network();
            } else {
                try {
                    result = read(path, response);
                } catch (OutOfMemoryError e) {
                    result = ApiResult.network();
                }
                if (readCookie && result.ok()) loginToken = Cookies.session(response.headers().allValues("set-cookie")).orElse(null);
            }
            LOG.debug("stash-window: {} {} -> {}", method, path, result.status());
            T parsed = null;
            if (result.ok() && parser != null) {
                try {
                    parsed = parser.parse(result);
                } catch (RuntimeException | OutOfMemoryError | StackOverflowError e) {
                    result = new ApiResult(result.status(), JsonNull.INSTANCE, result.etag(), result.retryAfterSec(), ApiError.Kind.SERVER, result.serverTime());
                }
            }
            ApiResult finalResult = result;
            T finalParsed = parsed;
            String finalToken = loginToken;
            main.execute(() -> {
                if (finalResult.kind() == ApiError.Kind.LOGIN && sent && gen == generation.get()) {
                    Runnable hook = unauthorized;
                    if (hook != null) hook.run();
                    return;
                }
                if (gen == generation.get()) delivery.deliver(finalResult, finalParsed, finalToken);
            });
            return null;
        }, pool);
    }

    private static ApiResult read(String path, HttpResponse<InputStream> response) {
        int status = response.statusCode();
        JsonElement json = JsonNull.INSTANCE;
        // A body announced as too big is refused before reading any of it
        if (response.headers().firstValueAsLong("content-length").orElse(0) > MAX_BODY) {
            try {
                response.body().close();
            } catch (IOException ignored) {
                // closing only frees the connection
            }
            return ApiResult.network();
        }
        try (InputStream raw = response.body()) {
            InputStream in = raw;
            String encoding = response.headers().firstValue("content-encoding").orElse("");
            if (encoding.equalsIgnoreCase("gzip")) in = new GZIPInputStream(raw);
            byte[] bytes = readCapped(in);
            if (bytes.length > 0) {
                try {
                    json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
                    json = JsonNull.INSTANCE;
                }
            }
        } catch (IOException e) {
            return ApiResult.network();
        }
        String etag = response.headers().firstValue("etag").orElse(null);
        long retry = 0;
        try {
            retry = Long.parseLong(response.headers().firstValue("retry-after").orElse("0").trim());
        } catch (NumberFormatException ignored) {
            // an HTTP date or junk: use the body's number instead
        }
        long date = 0;
        try {
            date = java.time.ZonedDateTime.parse(response.headers().firstValue("date").orElse(""), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            // no or odd Date header: no clock skew
        }
        return new ApiResult(status, json, etag, retry, ApiError.classify(path, status, json), date);
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) {
            if (out.size() + n > MAX_BODY) throw new IOException("too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
