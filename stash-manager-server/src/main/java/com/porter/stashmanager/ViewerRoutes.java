package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The live viewer's routes (PROTOCOL.md, "Additions: live viewer"):
 * <ul>
 *   <li>POST /api/v1/bot/viewer: a signed frame from the bot (at most 512 KiB, 15 per second);</li>
 *   <li>GET /api/v1/bots/{id}/viewer/stream?vs=: the browser's Server-Sent Events stream;</li>
 *   <li>GET /api/v1/bots/{id}/viewer: the viewer's state (why a stream fails, and the clock for the latency figures).</li>
 * </ul>
 * Never logs a frame: positions, names and chat stay in memory.
 */
final class ViewerRoutes {
    private static final Pattern STREAM = Pattern.compile("^/api/v1/bots/([A-Za-z0-9_-]{1,64})/viewer/stream$");
    private static final Pattern STATE = Pattern.compile("^/api/v1/bots/([A-Za-z0-9_-]{1,64})/viewer$");
    private static final Pattern VS = Pattern.compile("^[0-9a-f]{16}$");

    private final ViewerHub viewer;
    private final BotHub hub;
    private Router router;

    ViewerRoutes(ViewerHub viewer) {
        this.viewer = viewer;
        this.hub = viewer.bots();
    }

    void register(Router router) {
        this.router = router;
        router.exact(Router.POST, "/api/v1/bot/viewer", Router.Guard.ADDON_KEY, this::frame)
            .pattern(Set.of("GET"), STREAM, Router.Guard.SESSION, Router.Perm.VIEWER, this::stream)
            .pattern(Router.GET, STATE, Router.Guard.SESSION, Router.Perm.VIEWER, this::state);
    }

    // ---------- bot side ----------

    /** "botId":"..." near the start of a frame: found without parsing the whole body (for the rate limit). */
    private static final Pattern BOT_FIELD = Pattern.compile("\"botId\"[ \\t\\r\\n]*:[ \\t\\r\\n]*\"([A-Za-z0-9_-]{1,64})\"");

    /** 429 {retryMs} when this bot is over 15 frames a second; true after answering. */
    private boolean limited(HttpExchange ex, String botId) throws IOException {
        long wait = viewer.rateLimit(botId);
        if (wait <= 0) return false;
        JsonObject r = new JsonObject();
        r.addProperty("ok", false);
        r.addProperty("error", "more than " + ViewerHub.MAX_FRAMES_PER_S + " frames per second");
        r.addProperty("retryMs", wait);
        Http.json(ex, 429, r);
        return true;
    }

    private void frame(HttpExchange ex) throws IOException {
        byte[] bytes = Http.readBody(ex, ViewerHub.MAX_BODY); // 413 above 512 KiB
        // The rate limit comes before the JSON parse: the bot id is looked up in the first 4 KiB.
        Matcher pre = BOT_FIELD.matcher(new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.UTF_8));
        String preId = pre.find() ? pre.group(1) : null;
        if (preId != null) {
            if (hub.bot(preId) == null) {
                Http.error(ex, 409, "say hello first");
                return;
            }
            if (limited(ex, preId)) return;
        }
        JsonElement json;
        try {
            json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonParseException | StackOverflowError e) {
            json = null;
        }
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return;
        }
        JsonObject o = json.getAsJsonObject();
        String botId = AddonRoutes.string(o, "botId");
        if (botId == null || !BotRoutes.BOT_ID.matcher(botId).matches()) {
            Http.error(ex, 400, "botId must be 1-64 letters, digits, - or _");
            return;
        }
        if (preId != null && !preId.equals(botId)) {
            Http.error(ex, 400, "botId must be the frame's first botId field");
            return;
        }
        BotHub.Bot b = hub.bot(botId);
        if (b == null) {
            Http.error(ex, 409, "say hello first");
            return;
        }
        if (preId == null && limited(ex, botId)) return;
        ViewerHub.Frame f;
        try {
            f = ViewerHub.parse(o);
        } catch (ViewerHub.BadFrame e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        ViewerHub.FrameResult r = viewer.frame(b, f);
        JsonObject out = Http.ok();
        out.addProperty("watching", r.watching());
        out.addProperty("resync", r.resync());
        Http.json(ex, 200, out);
    }

    // ---------- browser side ----------

    /** The bot, or null after answering 404 (unknown, or outside the user's bot scope). */
    private BotHub.Bot bot(HttpExchange ex, String raw) throws IOException {
        String id = Router.botParam(ex, raw); // forced streamer mode: an opaque bot id
        BotHub.Bot b = id != null && Router.user(ex).perms().seesBot(id) ? hub.bot(id) : null;
        if (b == null) Http.error(ex, 404, "no such bot");
        return b;
    }

    private static String mc(BotHub.Bot b) {
        synchronized (b) {
            return AddonRoutes.string(b.proxy, "mc");
        }
    }

    private void state(HttpExchange ex) throws IOException {
        Matcher m = STATE.matcher(ex.getRequestURI().getRawPath());
        if (!m.matches()) {
            Http.error(ex, 404, "not found");
            return;
        }
        BotHub.Bot b = bot(ex, m.group(1));
        if (b == null) return;
        long now = viewer.now();
        JsonObject o = new JsonObject();
        o.addProperty("now", now);
        ViewerHub.Live live = viewer.live(b.botId);
        boolean isLive = false;
        Long lastFrameAt = null;
        if (live != null) {
            synchronized (live) {
                isLive = live.hasSnapshot && now - live.lastFrameAt <= ViewerHub.STALE_MS;
                if (live.lastFrameAt > 0) lastFrameAt = live.lastFrameAt;
            }
        }
        o.addProperty("live", isLive);
        o.addProperty("lastFrameAt", lastFrameAt);
        o.addProperty("streams", viewer.streamCount(b.botId));
        o.addProperty("maxStreams", ViewerHub.MAX_PER_BOT);
        o.addProperty("mc", mc(b));
        // Beyond the contract, to explain a 429: this user's streams and the server-wide count.
        o.addProperty("yours", viewer.userStreamCount(Router.user(ex).id()));
        o.addProperty("maxPerUser", ViewerHub.MAX_PER_USER);
        o.addProperty("total", viewer.streamCount());
        o.addProperty("maxTotal", ViewerHub.MAX_STREAMS);
        o.addProperty("online", hub.online(b));
        Http.json(ex, 200, o);
    }

    private void stream(HttpExchange ex) throws IOException {
        Matcher m = STREAM.matcher(ex.getRequestURI().getRawPath());
        if (!m.matches()) {
            Http.error(ex, 404, "not found");
            return;
        }
        // Like a browser write: another site must not be able to open the stream with the user's cookie.
        if (!router.access().sameOriginRead(ex)) {
            Http.error(ex, 403, "cross-site viewer request refused");
            return;
        }
        BotHub.Bot b = bot(ex, m.group(1));
        if (b == null) return;
        String vs = Http.query(ex).get("vs");
        if (vs == null || !VS.matcher(vs).matches()) {
            Http.error(ex, 400, "vs must be 16 hex digits");
            return;
        }
        Users.Principal who = Router.user(ex);
        String token = (String) Http.attr(ex, Router.SESSION_TOKEN);
        // Pool threads held by viewer handlers, counted until this handler returns (a replaced stream may still be
        // finishing): hard caps a little over the stream caps.
        String busy = viewer.enter(who.id());
        if (busy != null) {
            ex.getResponseHeaders().set("Retry-After", "5");
            Http.error(ex, 429, busy);
            return;
        }
        try {
            ViewerHub.Opened opened = viewer.open(who, vs, b, AuthRoutes.address(ex));
            if (opened.refused() != null) {
                ex.getResponseHeaders().set("Retry-After", "5");
                Http.error(ex, 429, opened.refused());
                return;
            }
            ViewerHub.Client c = opened.client();
            ViewerHub.Live live = opened.live();
            try {
                ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                ex.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
                ex.getResponseHeaders().set("X-Accel-Buffering", "no");
                ex.sendResponseHeaders(200, 0); // chunked; never gzip
                Writer w = new Writer(ex, who.id(), viewer.now() + viewer.rotateMs + ViewerHub.ROTATE_GRACE_MS);
                // Forced streamer mode: coordinates rebased by a secret per-stream offset, no names, no chat, no dimension.
                run(w, c, live, b, token, Redact.forced(who) ? new Redact.ViewerMask() : null);
            } finally {
                viewer.close(c, live, b);
            }
        } finally {
            viewer.exit(who.id());
        }
    }

    /**
     * Writes a stream's text through the viewer's writer threads, 8 KiB at a time, waiting at most the write timeout
     * (8 s) for each piece. A piece that doesn't finish in time (a reader that stopped reading) detaches the exchange:
     * the handler returns at once and frees its pool thread, and the writer thread closes the exchange once its write
     * finishes or fails (the JDK server drops the connection after 60 s at the latest). A stream past its rotate time
     * plus 15 s (a very slow reader) is ended the same way.
     */
    private final class Writer {
        private static final int PIECE = 8192;
        private final HttpExchange ex;
        private final String userId;
        private final OutputStream out;
        private final long deadline;
        long lastWrite;

        Writer(HttpExchange ex, String userId, long deadline) {
            this.ex = ex;
            this.userId = userId;
            this.out = ex.getResponseBody();
            this.deadline = deadline;
        }

        void write(String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            for (int off = 0; off < bytes.length; off += PIECE) {
                if (viewer.now() > deadline) throw new IOException("viewer stream past its time (slow reader)");
                int start = off;
                int len = Math.min(PIECE, bytes.length - off);
                // 0 running, 1 done, 2 detached (the handler gave up; the writer closes the exchange).
                java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.Future<?> f;
                try {
                    f = viewer.writers().submit(() -> {
                        try {
                            out.write(bytes, start, len);
                            out.flush();
                        } finally {
                            if (!state.compareAndSet(0, 1)) {
                                try {
                                    ex.close();
                                } finally {
                                    viewer.writerFinished(userId);
                                }
                            }
                        }
                        return null;
                    });
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    throw new IOException("no viewer writer free");
                }
                try {
                    f.get(viewer.writeTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    viewer.writerDetached(userId); // counted until the writer thread is done with it
                    if (state.compareAndSet(0, 2)) {
                        Http.attr(ex, Http.DETACHED, Boolean.TRUE); // the Router must not close it (it would block)
                        throw new IOException("viewer write timed out");
                    }
                    viewer.writerFinished(userId); // it finished just now after all
                } catch (java.util.concurrent.ExecutionException e) {
                    throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
            lastWrite = viewer.now();
        }
    }

    /**
     * The SSE loop: hello and the snapshot, then whatever the frames queue, one batch per write. Before a batch is
     * written (at most every 5 s) the session and permission are checked again; a ping comment goes out every 3 s
     * when nothing else did (so a closed tab is noticed quickly), a heartbeat comment every 10 s, and the bot's viewer
     * watch is refreshed every few seconds. Ends with bye after the rotate time (45 s; the JDK server kills any
     * response after 60 s), on revocation, or when a newer stream of the same viewer page took over.
     */
    private void run(Writer out, ViewerHub.Client c, ViewerHub.Live live, BotHub.Bot b, String token, Redact.ViewerMask mask)
        throws IOException {
        long start = viewer.now();
        long rotateAt = start + viewer.rotateMs;
        long nextHb = start + viewer.heartbeatMs;
        long nextWatch = start + ViewerHub.WATCH_REFRESH_MS;
        long authMs = Math.min(ViewerHub.AUTH_MS, viewer.heartbeatMs);
        long nextAuth = start + authMs;
        StringBuilder first = new StringBuilder("retry: 1000\n\n");
        JsonObject hello = new JsonObject();
        hello.addProperty("botId", mask != null && router.ids() != null ? router.ids().bid(b.botId) : b.botId);
        hello.addProperty("mc", mc(b));
        hello.addProperty("st", start);
        hello.addProperty("rotateMs", viewer.rotateMs);
        hello.addProperty("relative", mask != null); // forced streamer mode: coordinates are relative
        first.append(ViewerHub.event(null, "hello", hello));
        out.write(first.toString());
        while (true) {
            List<ViewerHub.Ev> events;
            String status;
            String bye;
            boolean online = hub.online(b);
            synchronized (live) {
                while (true) {
                    long now = viewer.now();
                    String st = ViewerHub.status(live, online, now);
                    long until = Math.min(Math.min(rotateAt, nextHb), Math.min(nextWatch, Math.min(nextAuth, out.lastWrite + ViewerHub.PING_MS)));
                    if (c.hasWork() || !st.equals(c.statusSent) || now >= until) break;
                    // Wake when the bot would turn stale, to say so.
                    if (live.hasSnapshot && st.equals("live")) until = Math.min(until, live.lastFrameAt + ViewerHub.STALE_MS + 1);
                    try {
                        live.wait(Math.max(1, until - now));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                long now = viewer.now();
                bye = c.bye;
                events = bye != null ? List.of() : ViewerHub.drain(live, c, now);
                status = ViewerHub.status(live, online, now);
                if (status.equals(c.statusSent)) status = null;
                else c.statusSent = status;
            }
            if (bye != null) {
                sendBye(out, bye);
                return;
            }
            long now = viewer.now();
            // Re-checked before the data goes out: a revoked session gets only the bye.
            if (now >= nextAuth || (now >= nextHb)) {
                nextAuth = now + authMs;
                if (!router.stillAllowed(token, Router.Perm.VIEWER, b.botId)
                    || (mask == null && Redact.forced(router.current(token)))) {
                    // Revoked, or forced into streamer mode since this stream began: the page reconnects and then
                    // gets the redacted form.
                    sendBye(out, "revoked");
                    return;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (ViewerHub.Ev e : events) { // serialized outside the Live's monitor
                ViewerHub.Ev shown = mask == null ? e : mask.apply(e);
                if (shown != null) sb.append(shown.text());
            }
            if (status != null) { // after the data, so "live" follows the snapshot it describes
                JsonObject s = new JsonObject();
                s.addProperty("state", status);
                sb.append(ViewerHub.event(null, "status", s));
            }
            if (now >= nextWatch) {
                hub.watchViewer(b, false);
                nextWatch = now + ViewerHub.WATCH_REFRESH_MS;
            }
            if (now >= nextHb) {
                nextHb = now + viewer.heartbeatMs;
                hub.watchViewer(b, false);
                sb.append(": hb\n\n");
            }
            if (sb.isEmpty() && now - out.lastWrite >= ViewerHub.PING_MS) sb.append(":\n\n");
            // Before every write: forced into streamer mode since this stream began? (In memory, cheap.) Then only the
            // bye goes out; the page reconnects and gets the redacted form.
            if (mask == null && Redact.forced(router.current(token))) {
                sendBye(out, "revoked");
                return;
            }
            if (!sb.isEmpty()) out.write(sb.toString());
            if (now >= rotateAt) {
                sendBye(out, "rotate");
                return;
            }
        }
    }

    private static void sendBye(Writer out, String reason) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("reason", reason);
        out.write(ViewerHub.event(null, "bye", o));
    }
}
