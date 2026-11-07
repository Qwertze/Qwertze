package com.porter.qwerte.manager.net;

import com.porter.qwerte.manager.map.ViewerEvent;
import com.porter.qwerte.manager.map.ViewerEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The live viewer's event stream for one bot: one daemon thread (named {@code qwerte-sm-viewer}) that opens
 * {@code GET /api/v1/bots/<id>/viewer/stream?vs=<16 hex>}, parses the events and puts them in a bounded queue that the
 * client thread drains while it renders. It reconnects by itself ({@link ReconnectPolicy}): at once after the server's 45 s
 * rotation, after 2 s at capacity, with a growing wait after errors, never less than a second between two connects. A
 * connection that completes no line for 15 s, or lives longer than 90 s, is dropped, and a full queue is cleared and the stream
 * reopened for a fresh snapshot.
 *
 * <p>The thread ends for good on 401, 403, 404, {@code bye revoked}, an unexpected error, a changed client generation and
 * {@link #stop()}; {@link #end()} says which, so the owner can decide whether and when to start a new one
 * ({@link StreamRestart}).
 *
 * <p>The thread never touches the game; it only uses {@link SmClient#openStream}. Reconnecting reuses the same {@code vs}:
 * the server allows only about 20 new ones per hour.
 */
public final class ViewerStream {
    /** Why the thread is not running. */
    public enum End { NONE, STOPPED, AUTH, FORBIDDEN, NOT_FOUND, REVOKED, ERROR }

    public static final int QUEUE = 512;
    public static final long IDLE_MS = 15_000;
    public static final long LIFETIME_MS = 90_000;
    private static final Logger LOG = LoggerFactory.getLogger("Qwerte/stash-window");

    /** One shared timer for the idle checks (a thread only while a stream is open). */
    private static final ScheduledThreadPoolExecutor WATCHDOG = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "qwerte-sm-viewer-watchdog");
        t.setDaemon(true);
        return t;
    });

    static {
        WATCHDOG.setKeepAliveTime(10, TimeUnit.SECONDS);
        WATCHDOG.allowCoreThreadTimeOut(true);
        WATCHDOG.setRemoveOnCancelPolicy(true);
    }

    private final SmClient client;
    private final String botId;
    private final String vs;
    private final ArrayBlockingQueue<ViewerEvent> queue = new ArrayBlockingQueue<>(QUEUE);
    private volatile boolean closed;
    private volatile InputStream current;
    private volatile long lastLineAt;
    private volatile boolean overflow;
    private volatile End end = End.NONE;
    private Thread thread;

    public ViewerStream(SmClient client, String botId, String vs) {
        this.client = client;
        this.botId = botId;
        this.vs = vs;
    }

    public String botId() {
        return botId;
    }

    public String vs() {
        return vs;
    }

    /** The thread is alive and not asked to stop. */
    public boolean running() {
        return !closed && thread != null && thread.isAlive();
    }

    /** Why the thread ended; {@link End#NONE} while it runs. */
    public End end() {
        return end;
    }

    public synchronized void start() {
        if (thread != null || closed) return;
        thread = new Thread(this::run, "qwerte-sm-viewer");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops the thread and closes the connection. Safe to call twice, from any thread. */
    public void stop() {
        closed = true;
        closeCurrent();
        Thread t = thread;
        if (t != null) t.interrupt();
        queue.clear();
    }

    /** Hands up to {@code max} events to {@code sink}, oldest first. Client thread. */
    public int drain(Consumer<ViewerEvent> sink, int max) {
        int n = 0;
        ViewerEvent e;
        while (n < max && (e = queue.poll()) != null) {
            sink.accept(e);
            n++;
        }
        return n;
    }

    private void closeCurrent() {
        InputStream in = current;
        current = null;
        close(in);
    }

    private void emit(ViewerEvent e) {
        if (closed) return;
        if (!queue.offer(e)) {
            // The client can't keep up: start over with a fresh snapshot rather than apply a gap
            queue.clear();
            overflow = true;
            closeCurrent();
        }
    }

    private void finish(End why) {
        end = why;
    }

    // ---------- the thread ----------

    private void run() {
        final int generation = client.generation();
        ReconnectPolicy policy = new ReconnectPolicy();
        try {
            while (!closed && client.generation() == generation) {
                emit(new ViewerEvent.Connecting());
                policy.started(System.currentTimeMillis());
                long requested = 0;
                boolean gotSnapshot = false;
                boolean failed = false;
                try {
                    SmClient.Open open = client.openStream("/api/v1/bots/" + ServerUrl.seg(botId) + "/viewer/stream", Map.of("vs", vs));
                    if (closed) {
                        close(open.body());
                        break;
                    }
                    int status = open.status();
                    if (status != 200) {
                        close(open.body());
                        if (status == 401) {
                            client.notifyUnauthorized(generation);
                            emit(new ViewerEvent.Failed(status, 0));
                            finish(End.AUTH);
                            return;
                        }
                        if (status == 403 || status == 404) {
                            emit(new ViewerEvent.Failed(status, 0));
                            finish(status == 403 ? End.FORBIDDEN : End.NOT_FOUND);
                            return;
                        }
                        failed = true;
                        requested = status == 429 ? Math.max(ReconnectPolicy.BACKOFF_START_MS, open.retryAfterSec() * 1000) : 0;
                    } else {
                        Outcome outcome = read(open.body());
                        gotSnapshot = outcome.gotSnapshot;
                        if (outcome.revoked) {
                            finish(End.REVOKED);
                            return;
                        }
                        requested = outcome.waitMs;
                        if (!outcome.clean) {
                            // Ended without a bye (dropped, idle, lifetime): soon again if it worked, else as a failure
                            if (gotSnapshot) requested = 1_000;
                            else failed = true;
                        }
                    }
                } catch (IOException e) {
                    if (closed) break;
                    failed = true;
                }
                long wait = policy.next(System.currentTimeMillis(), requested, gotSnapshot, failed);
                if (failed) emit(new ViewerEvent.Failed(0, wait));
                if (wait > 0) Thread.sleep(wait);
            }
            finish(End.STOPPED); // asked to stop, or the client generation moved on (logout, another address)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(End.STOPPED);
        } catch (RuntimeException | OutOfMemoryError e) {
            // Logged once, by class only: nothing from the stream goes in the log
            LOG.warn("stash-window: the live viewer stream stopped ({})", e.getClass().getSimpleName());
            finish(End.ERROR);
        } finally {
            closeCurrent();
            if (end == End.NONE) end = End.ERROR;
        }
    }

    private static void close(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
            // closing only frees the connection
        }
    }

    private static final class Outcome {
        boolean gotSnapshot;
        boolean revoked;
        /** A bye or an end the server chose (rotation, capacity): not an error. */
        boolean clean;
        long waitMs;
    }

    /** Reads one connection until it ends. */
    private Outcome read(InputStream body) {
        Outcome out = new Outcome();
        current = body;
        long began = System.currentTimeMillis();
        lastLineAt = began;
        overflow = false;
        SseParser parser = new SseParser();
        ScheduledFuture<?> dog = WATCHDOG.scheduleWithFixedDelay(() -> {
            long now = System.currentTimeMillis();
            if (now - lastLineAt > IDLE_MS || now - began > LIFETIME_MS) closeCurrent();
        }, 5, 5, TimeUnit.SECONDS);
        try (InputStreamReader in = new InputStreamReader(body, StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int n;
            while (!closed && (n = in.read(buffer)) >= 0) {
                java.nio.CharBuffer chunk = java.nio.CharBuffer.wrap(buffer, 0, n);
                // Only a finished line counts as the server talking: a trickle of bytes without a line end does not keep it open
                for (int i = 0; i < n; i++) {
                    if (buffer[i] == '\n' || buffer[i] == '\r') {
                        lastLineAt = System.currentTimeMillis();
                        break;
                    }
                }
                java.util.List<SseParser.Event> events = parser.feedChunk(chunk);
                if (parser.overflowed()) {
                    // Too big to be ours: refused before anything is parsed
                    overflow = true;
                    break;
                }
                for (SseParser.Event e : events) {
                    ViewerEvent event = ViewerEvents.parse(e.name(), e.data());
                    if (event == null) continue;
                    if (event instanceof ViewerEvent.Snapshot) out.gotSnapshot = true;
                    emit(event);
                    if (event instanceof ViewerEvent.Bye bye) {
                        out.clean = true;
                        switch (bye.reason()) {
                            case "revoked" -> out.revoked = true;
                            case "capacity" -> out.waitMs = 2_000;
                            default -> out.waitMs = 0; // rotate: straight back
                        }
                        return out;
                    }
                }
                if (overflow) break;
            }
        } catch (IOException e) {
            // closed by the watchdog or stop(), or the connection dropped
        } finally {
            dog.cancel(false);
            closeCurrent();
        }
        if (overflow) {
            // The queue was cleared or a line was too big: reopen for a fresh snapshot
            out.clean = true;
            out.waitMs = 0;
        }
        return out;
    }
}
