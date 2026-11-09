package com.porter.qwerte.printer.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwerte.manager.model.AuthState;
import com.porter.qwerte.manager.model.DisplayPolicy;
import com.porter.qwerte.manager.model.OrderModel;
import com.porter.qwerte.manager.model.StashList;
import com.porter.qwerte.manager.model.StashRef;
import com.porter.qwerte.manager.net.ApiError;
import com.porter.qwerte.manager.net.ApiResult;
import com.porter.qwerte.manager.net.Session;
import com.porter.qwerte.manager.net.SmClient;
import com.porter.qwerte.modules.StashWindow;
import com.porter.qwerte.stash.Order;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Orders the Printer's missing materials from the stash bot that holds them, through the Stash Manager server (its own
 * background client, so closing the Stash Window never drops an answer). Nothing is asked of the player: it is automatic, in
 * batches, ahead of need, and it never opens a password prompt (a server that wants the password again is reported and waited
 * out).
 *
 * <p>Threading: {@link #tick} runs on the client thread. The network callbacks only queue a {@link Reply}; the next tick reads
 * it. One request is in flight at a time. A token guards every callback, and a watchdog frees a request that never came back.
 *
 * <p>One cycle: low stock found, then log-in state, then the stash list, then each candidate stash availability, then the
 * stash picker and the batch limits, then one POST per chosen stash. An order whose POST had no clear answer is looked for
 * on the server before anything is ordered again, so it is not placed twice.
 */
public final class StashOrders {
    /** What the Printer lets this class see and say. */
    public interface View {
        /** Item id to how many the build still needs. */
        Map<String, Long> need();

        /** Item id to how many are carried, shulker box contents included. The Printer refreshes it about once a second. */
        Map<String, Long> carried();

        int maxStack(String itemId);

        /** The Printer itself is taking things out of containers and boxes right now. */
        boolean localFetch();

        /** A readable, already cleaned name. */
        String itemName(String itemId);

        /** The game server address (the stash index uses the same form). */
        String server();

        /** Says it in chat; the text is already cleaned and is never used as a format string. */
        void say(boolean warn, String text);
    }

    /** The Printer settings, read live. */
    public interface Config {
        int threshold();

        int maxShulkersPerOrder();

        int maxShulkersPerRun();
    }

    private enum Goal { NONE, CYCLE, ADOPT, POLL }

    private enum Req { AUTH, STASHES, AVAIL, POST, ADOPT_GET, POLL_GET }

    private record Reply(int token, Req req, ApiResult result, Object parsed) {}

    /** A POST whose outcome is not known. */
    private record Sent(StashRef ref, LinkedHashMap<String, Integer> lines, long sentAt) {}

    private static final long FRESH_MS = 60_000;
    private static final long POLL_MS = 10_000;
    private static final long WATCHDOG_MS = 70_000;
    private static final long SKIP_MS = 5 * 60_000;
    private static final long MIN_BACKOFF = 15_000;
    private static final long MAX_BACKOFF = 5 * 60_000;
    private static final int CHECK_TICKS = 20;
    private static final int MAX_TARGETS = 12;

    private final Config cfg;
    private final OrderLedger ledger = new OrderLedger();
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    private View view;

    private Goal goal = Goal.NONE;
    private Req flying;
    private boolean inFlight;
    private long flightAt, nowMs, retryAt, backoff = MIN_BACKOFF;
    private static final long RATE_FLOOR_MS = 15_000;
    private int token, ticks;
    private boolean denied, transientFail;
    private String lastServer;
    private String sessionKey;

    private AuthState auth;
    private long authAt = -FRESH_MS * 2;
    private List<StashRef> stashes;
    private long stashesAt = -FRESH_MS * 2;

    private final Set<String> warned = new HashSet<>();
    private final Map<String, Long> skipStash = new HashMap<>();
    private final Map<String, Long> skipLine = new HashMap<>();
    private final Map<String, Long> noStash = new HashMap<>();
    private final Map<String, Long> nextPoll = new HashMap<>();

    // The cycle in progress
    private Map<String, Long> want;
    private List<StashRef> targets;
    private int targetIdx;
    private final List<StashPicker.Candidate> cands = new ArrayList<>();
    private final Map<String, StashRef> refs = new HashMap<>();
    private boolean sawBusy;
    private List<PlannedOrder> queue;
    private int queueIdx;
    private String pollKey;
    private Sent adopt;
    private int adoptFails;
    private boolean targetsCut;

    private record PlannedOrder(StashRef ref, LinkedHashMap<String, Integer> lines) {}

    public StashOrders(Config cfg) {
        this.cfg = cfg;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Outside view

    /** The orders placed so far (shared by runs of the Printer). */
    public OrderLedger ledger() {
        return ledger;
    }

    /** A new build: the per-run order count and the keys given up on start over. Orders still open are kept. */
    public void newBuild() {
        ledger.resetRun();
    }

    /** A new run (or the end of one): drops the cycle and what was learned this run. The ledger and an unknown POST stay. */
    public void reset() {
        // A POST that was on its way may have been taken: look for it before ordering anything again
        if (inFlight && flying == Req.POST && queue != null && queueIdx < queue.size()) {
            PlannedOrder p = queue.get(queueIdx);
            adopt = new Sent(p.ref(), p.lines(), flightAt);
        }
        token++;
        inFlight = false;
        flying = null;
        goal = Goal.NONE;
        replies.clear();
        denied = false;
        transientFail = false;
        backoff = MIN_BACKOFF;
        retryAt = 0;
        authAt = -FRESH_MS * 2;
        stashesAt = -FRESH_MS * 2;
        auth = null;
        stashes = null;
        sessionKey = null;
        warned.clear();
        skipStash.clear();
        skipLine.clear();
        noStash.clear();
        nextPoll.clear();
        clearCycle();
    }

    private void clearCycle() {
        want = null;
        targets = null;
        targetIdx = 0;
        cands.clear();
        queue = null;
        queueIdx = 0;
        pollKey = null;
    }

    /** Why orders can't be placed at all right now (not logged in, not allowed, the window missing), or null. */
    private String blocker() {
        if (denied) return "your Stash Manager account may not place orders";
        StashWindow w = StashWindow.get();
        if (w == null) return "the Stash Window module is missing";
        if (!w.connectToServer()) return "connect-to-server is off in the Stash Window";
        if (w.url() == null) return "no Stash Manager server address is set in the Stash Window";
        if (w.session() == null) return "you are not logged in to the Stash Manager server";
        return null;
    }

    private long clock() {
        return nowMs == 0 ? System.currentTimeMillis() : nowMs;
    }

    /**
     * What an out-of-stock item is waiting for, as a phrase that stays the same while it holds ("the order from stash X",
     * "your open stash order(s) to finish", "the stash order to be placed"), or null when orders can't help (see
     * {@link #problemFor}).
     */
    public String waitingFor(String itemId) {
        if (problemFor(itemId) != null) return null;
        String key = ledger.stashFor(itemId, clock());
        if (key != null) return "the order from stash " + stashName(key);
        if (ledger.hasOpen()) return "your open stash order(s) to finish";
        return "the stash order to be placed";
    }

    /** Why stash orders can't supply this item now (a short reason for the pause message), or null when they can. */
    public String problemFor(String itemId) {
        String b = blocker();
        if (b != null) return b;
        if (ledger.stashFor(itemId, clock()) != null) return null;
        if (noStash.getOrDefault(itemId, 0L) > clock()) return "no stash bot for this server has it";
        if (cfg.maxShulkersPerRun() - ledger.shulkersThisRun() <= 0) return "order-max-shulkers-per-run was reached";
        return null;
    }

    /** Lines for the materials summary: how orders are set up, and what is in the way right now. */
    public void summary(Map<String, Long> missing, Consumer<String> out) {
        out.accept("Stash orders: on. Missing materials are ordered automatically from the stash bot that holds them when you carry fewer than "
            + cfg.threshold() + " (up to " + cfg.maxShulkersPerOrder() + " shulkers per order, " + cfg.maxShulkersPerRun() + " per run). Collect the deliveries yourself.");
        StashWindow w = StashWindow.get();
        if (w == null) out.accept("Stash orders need the Stash Window module.");
        else if (!w.connectToServer()) out.accept("Stash orders need connect-to-server on in the Stash Window.");
        else if (w.url() == null) out.accept("Stash orders need the Stash Manager server address set in the Stash Window.");
        else if (w.session() == null) out.accept("Stash orders need you logged in to the Stash Manager server in the Stash Window.");
        else if (!missing.isEmpty()) out.accept("The shortfall above will be ordered when it is needed.");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The tick

    public void tick(long now, View v) {
        this.view = v;
        this.nowMs = now;
        ticks++;
        String server = v.server();
        if (server != null && !server.isBlank()) {
            if (lastServer != null && !StashPicker.sameServer(lastServer, server)) {
                // Another game server: its orders and an unknown POST are not ours to follow; the run's count stays
                ledger.clear();
                adopt = null;
            }
            lastServer = server;
        }
        ledger.observeCarried(v.carried(), v.localFetch(), now);

        for (Reply r = replies.poll(); r != null; r = replies.poll()) {
            if (r.token() != token) continue;
            inFlight = false;
            flying = null;
            handle(r);
        }

        if (inFlight) {
            StashWindow w = StashWindow.get();
            boolean lost = w == null || !w.connectToServer() || w.session() == null;
            if (lost || now - flightAt > WATCHDOG_MS) {
                Req was = flying;
                token++;
                inFlight = false;
                flying = null;
                abandoned(was, lost);
            }
        }
        if (inFlight || denied || now < retryAt) return;
        advance();
    }

    /** A request never came back (or the session vanished under it). */
    private void abandoned(Req was, boolean lost) {
        if (was == Req.POST && queue != null && queueIdx < queue.size()) {
            PlannedOrder p = queue.get(queueIdx);
            adopt = new Sent(p.ref(), p.lines(), flightAt);
        }
        if (lost) {
            authAt = -FRESH_MS * 2;
            warnOnce("login", "The Stash Manager session is gone. Log in again in the Stash Window; stash orders try again every minute.");
            retryAt = nowMs + FRESH_MS;
        } else {
            backoffNow();
        }
        endGoal();
    }

    private void advance() {
        if (goal == Goal.NONE && !chooseGoal()) return;
        StashWindow w = StashWindow.get();
        SmClient client = client(w);
        if (client == null) {
            endGoal();
            return;
        }
        switch (goal) {
            case POLL -> sendOrders(client, pollKey, Req.POLL_GET);
            case ADOPT -> {
                if (needAuth()) sendAuth(client);
                else sendOrders(client, adopt.ref().key(), Req.ADOPT_GET);
            }
            case CYCLE -> stepCycle(client);
            default -> { }
        }
    }

    private boolean chooseGoal() {
        if (adopt != null) {
            goal = Goal.ADOPT;
            return true;
        }
        for (String key : ledger.stashesToPoll()) {
            if (nowMs >= nextPoll.getOrDefault(key, 0L)) {
                goal = Goal.POLL;
                pollKey = key;
                return true;
            }
        }
        if (ticks % CHECK_TICKS != 0) return false;
        Map<String, Long> need = view.need();
        Map<String, Long> carried = view.carried();
        Map<String, Long> covered = ledger.covered(nowMs);
        int threshold = Math.max(1, cfg.threshold());
        if (!LowStock.triggered(need, carried, covered, threshold)) return false;
        Map<String, Long> short_ = LowStock.shortfall(need, carried, covered, threshold);
        Map<String, Long> wanted = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : short_.entrySet()) {
            if (noStash.getOrDefault(e.getKey(), 0L) > nowMs) continue;
            wanted.put(e.getKey(), e.getValue());
        }
        if (wanted.isEmpty()) return false;
        int left = cfg.maxShulkersPerRun() - ledger.shulkersThisRun();
        if (left <= 0) {
            warnOnce("runcap", "Stash orders reached order-max-shulkers-per-run (" + cfg.maxShulkersPerRun() + "). No more are placed this run.");
            return false;
        }
        want = wanted;
        goal = Goal.CYCLE;
        targets = null;
        targetIdx = 0;
        cands.clear();
        queue = null;
        queueIdx = 0;
        return true;
    }

    /** The background client when everything needed to use it is set, else null (and one warning says what is missing). */
    private SmClient client(StashWindow w) {
        if (w == null) {
            warnOnce("window", "order-from-stash needs the Stash Window module.");
            retryAt = nowMs + FRESH_MS;
            return null;
        }
        if (!w.connectToServer()) {
            warnOnce("connect", "order-from-stash needs connect-to-server on in the Stash Window.");
            retryAt = nowMs + FRESH_MS;
            return null;
        }
        if (w.url() == null) {
            warnOnce("address", "order-from-stash needs the Stash Manager server address set in the Stash Window.");
            retryAt = nowMs + FRESH_MS;
            return null;
        }
        if (w.session() == null) {
            warnOnce("login", "order-from-stash needs you logged in to the Stash Manager server. Log in in the Stash Window; it tries again every minute.");
            sessionKey = null;
            auth = null;
            stashes = null;
            retryAt = nowMs + FRESH_MS;
            return null;
        }
        Session s = w.session();
        String key = s.origin() + "\u0000" + s.token();
        if (!key.equals(sessionKey)) {
            // Another account or a new login: what was learned under the old one is not good for this one
            sessionKey = key;
            auth = null;
            authAt = -FRESH_MS * 2;
            stashes = null;
            stashesAt = -FRESH_MS * 2;
        }
        return w.backgroundClient();
    }

    private boolean needAuth() {
        return auth == null || nowMs - authAt > FRESH_MS;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Requests

    private void fly(Req req) {
        inFlight = true;
        flying = req;
        flightAt = nowMs;
    }

    private void sendAuth(SmClient client) {
        int tok = token;
        fly(Req.AUTH);
        client.get("/api/v1/auth/state", null, null, false, SmClient.DEFAULT_TIMEOUT, r -> AuthState.parse(r.body()),
            (r, parsed) -> replies.add(new Reply(tok, Req.AUTH, r, parsed)));
    }

    private void sendStashes(SmClient client) {
        int tok = token;
        fly(Req.STASHES);
        client.get("/api/v1/stashes", null, null, false, SmClient.DEFAULT_TIMEOUT, r -> StashList.parse(r.body()),
            (r, parsed) -> replies.add(new Reply(tok, Req.STASHES, r, parsed)));
    }

    private void sendOrders(SmClient client, String stashKey, Req req) {
        int cut = stashKey.indexOf('\u0000');
        if (cut < 0) {
            endGoal();
            return;
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("server", stashKey.substring(0, cut));
        query.put("stash", stashKey.substring(cut + 1));
        int tok = token;
        fly(req);
        client.get("/api/v1/orders", query, null, false, SmClient.DEFAULT_TIMEOUT, r -> OrderModel.parse(r.body()),
            (r, parsed) -> replies.add(new Reply(tok, req, r, parsed)));
    }

    private void sendPost(SmClient client, PlannedOrder p) {
        JsonObject body = new JsonObject();
        body.addProperty("server", p.ref().server());
        body.addProperty("stash", p.ref().stash());
        JsonArray array = new JsonArray();
        for (Map.Entry<String, Integer> e : p.lines().entrySet()) {
            JsonObject line = new JsonObject();
            line.addProperty("type", Order.ITEM);
            line.addProperty("key", e.getKey());
            line.addProperty("count", e.getValue());
            array.add(line);
        }
        body.add("lines", array);
        int tok = token;
        fly(Req.POST);
        client.post("/api/v1/orders", body, r -> replies.add(new Reply(tok, Req.POST, r, null)));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The cycle

    private void stepCycle(SmClient client) {
        if (needAuth()) {
            sendAuth(client);
            return;
        }
        if (stashes == null || nowMs - stashesAt > FRESH_MS) {
            sendStashes(client);
            return;
        }
        if (targets == null && !buildTargets()) return;
        while (targetIdx < targets.size()) {
            StashRef ref = targets.get(targetIdx);
            if (skipStash.getOrDefault(ref.key(), 0L) > nowMs || ledger.hasOpen(ref.key())) {
                // Not asked: an order of ours is open there already, or it was refused lately
                sawBusy = true;
                targetIdx++;
                continue;
            }
            sendOrders(client, ref.key(), Req.AVAIL);
            return;
        }
        if (queue == null) {
            planOrders();
            if (queue == null) return;
        }
        if (queueIdx >= queue.size()) {
            retryAt = nowMs + 5_000;
            transientFail = false;
            endGoal();
            return;
        }
        PlannedOrder next = clip(queue.get(queueIdx));
        if (next == null) {
            // What was wanted is covered by now (something arrived, or another order took it)
            queueIdx++;
            return;
        }
        queue.set(queueIdx, next);
        sendPost(client, next);
    }

    /** The order cut to what is still short right now; null when nothing is. */
    private PlannedOrder clip(PlannedOrder p) {
        Map<String, Long> short_ = LowStock.shortfall(view.need(), view.carried(), ledger.covered(nowMs));
        LinkedHashMap<String, Integer> lines = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : p.lines().entrySet()) {
            long n = Math.min(e.getValue(), short_.getOrDefault(e.getKey(), 0L));
            if (n > 0) lines.put(e.getKey(), (int) n);
        }
        return lines.isEmpty() ? null : new PlannedOrder(p.ref(), lines);
    }

    private boolean buildTargets() {
        List<StashRef> out = new ArrayList<>();
        String here = view.server();
        boolean forced = auth != null && auth.forcedStreamer();
        StashWindow w = StashWindow.get();
        String last = w == null ? null : w.lastStash();
        boolean sawMatch = false;
        for (StashRef ref : stashes) {
            boolean match = forced ? ref.key().equals(last) : StashPicker.sameServer(ref.server(), here);
            if (!match) continue;
            sawMatch = true;
            if (!ref.hasIndex() || !ref.bot().orderBlocker().isEmpty()) continue;
            out.add(ref);
        }
        if (forced) warnOnce("forced", "Streamer mode hides the server address, so orders go only to the stash chosen in the Stash Window.");
        if (out.isEmpty()) {
            warnOnce(sawMatch ? "blocked" : "nostash", sawMatch
                ? "No stash bot for this server can take orders right now (no index, or the bot reports a problem). Trying again later."
                : "No stash on the Stash Manager server matches this game server, so nothing can be ordered. Trying again later.");
            retryAt = nowMs + FRESH_MS;
            transientFail = true;
            endGoal();
            return false;
        }
        out.sort(Comparator.comparing((StashRef r) -> !r.online()).thenComparing(StashRef::key));
        if (out.size() > MAX_TARGETS) {
            warnOnce("targets", out.size() + " stashes can take orders for this server; only the first " + MAX_TARGETS + " are asked.");
            targets = new ArrayList<>(out.subList(0, MAX_TARGETS));
        } else {
            targets = out;
        }
        refs.clear();
        for (StashRef r : targets) refs.put(r.key(), r);
        targetIdx = 0;
        cands.clear();
        sawBusy = false;
        return true;
    }

    private void planOrders() {
        StashPicker.Result result = StashPicker.pick(want, cands, view::maxStack);
        boolean busy = sawBusy;
        for (StashPicker.Candidate c : cands) if (!StashPicker.usable(c)) busy = true;
        List<String> missingNames = new ArrayList<>();
        for (String item : result.unmet().keySet()) {
            // A stash that is busy with an order of ours may hold it: that is waiting, not a missing item
            if (!busy && !anyCandidateHolds(item)) {
                if (noStash.getOrDefault(item, 0L) <= nowMs) missingNames.add(ChatText.safe(view.itemName(item), 40));
                noStash.put(item, nowMs + SKIP_MS);
            }
        }
        if (!missingNames.isEmpty()) {
            view.say(false, "No stash bot for this server has: " + String.join(", ", missingNames.subList(0, Math.min(8, missingNames.size())))
                + (missingNames.size() > 8 ? " and " + (missingNames.size() - 8) + " more" : "") + ".");
        }
        List<PlannedOrder> planned = new ArrayList<>();
        int left = cfg.maxShulkersPerRun() - ledger.shulkersThisRun();
        for (StashPicker.Pick pick : result.picks()) {
            OrderBatch.Limits limits = new OrderBatch.Limits(cfg.maxShulkersPerOrder(), left);
            LinkedHashMap<String, Integer> lines = OrderBatch.cap(pick.lines(), view::maxStack, limits);
            if (lines.isEmpty()) continue;
            StashRef ref = refs.get(pick.stash().key());
            if (ref == null) continue;
            planned.add(new PlannedOrder(ref, lines));
            left -= OrderBatch.shulkers(lines, view::maxStack);
        }
        if (planned.isEmpty()) {
            if (left <= 0) warnOnce("runcap", "Stash orders reached order-max-shulkers-per-run (" + cfg.maxShulkersPerRun() + "). No more are placed this run.");
            retryAt = nowMs + 30_000;
            transientFail = false;
            endGoal();
            return;
        }
        queue = planned;
        queueIdx = 0;
    }

    private boolean anyCandidateHolds(String item) {
        for (StashPicker.Candidate c : cands) if (c.available().getOrDefault(item, 0L) > 0) return true;
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Answers

    @SuppressWarnings("unchecked")
    private void handle(Reply r) {
        ApiResult res = r.result();
        if (r.req() == Req.POLL_GET) {
            String key = pollKey;
            if (key != null) nextPoll.put(key, nowMs + (res.ok() ? POLL_MS : 3 * POLL_MS));
        }
        if (!res.ok()) {
            failed(r.req(), res);
            return;
        }
        backoff = MIN_BACKOFF;
        switch (r.req()) {
            case AUTH -> {
                auth = (AuthState) r.parsed();
                authAt = nowMs;
                if (!auth.loggedIn()) {
                    authAt = -FRESH_MS * 2;
                    warnOnce("login", "Stash orders need you logged in to the Stash Manager server. Log in in the Stash Window; it tries again every minute.");
                    retryAt = nowMs + FRESH_MS;
                    endGoal();
                } else if (auth.mustChange()) {
                    authAt = -FRESH_MS * 2;
                    warnOnce("mustchange", "The Stash Manager server wants a new password for your account. Change it in the Stash Window; stash orders try again every minute.");
                    retryAt = nowMs + FRESH_MS;
                    endGoal();
                } else if (goal == Goal.CYCLE && !auth.can("order")) {
                    denied = true;
                    warnOnce("denied", "Your Stash Manager account may not place orders, so stash orders are off for this run.");
                    endGoal();
                }
            }
            case STASHES -> {
                stashes = (List<StashRef>) r.parsed();
                stashesAt = nowMs;
            }
            case AVAIL -> availability((OrderModel.Listing) r.parsed());
            case POST -> posted(res);
            case ADOPT_GET -> adopted((OrderModel.Listing) r.parsed());
            case POLL_GET -> polled((OrderModel.Listing) r.parsed());
            default -> { }
        }
    }

    private void availability(OrderModel.Listing listing) {
        StashRef ref = targets.get(targetIdx);
        targetIdx++;
        Map<String, Long> avail = new HashMap<>();
        for (String item : want.keySet()) {
            Long n = listing.available(Order.ITEM, item);
            if (n == null || n <= 0) continue;
            if (ledger.blocked(ref.key(), item) || skipLine.getOrDefault(ref.key() + "\u0000" + item, 0L) > nowMs) continue;
            avail.put(item, n);
        }
        int open = 0;
        boolean ours = ledger.hasOpen(ref.key());
        String me = auth == null ? "" : auth.userId();
        for (OrderModel.Entry e : listing.orders()) {
            if (!e.open()) continue;
            open++;
            if (e.hasPlacedBy() && !me.isEmpty() && e.placedById().equals(me)) ours = true;
        }
        cands.add(new StashPicker.Candidate(ref.key(), ref.stash(), ref.online(), ours, open, avail));
    }

    private void posted(ApiResult res) {
        PlannedOrder p = queue.get(queueIdx);
        JsonElement raw = res.obj().get("order");
        OrderModel.Entry entry = raw != null && raw.isJsonObject() ? OrderModel.parseEntry(raw.getAsJsonObject()) : null;
        if (entry == null) {
            // Accepted, but without an order we can follow: look for it before ordering anything again
            adopt = new Sent(p.ref(), p.lines(), flightAt);
            endGoal();
            retryAt = nowMs + 5_000;
            return;
        }
        record(p.ref(), p.lines(), entry.id(), flightAt, "Ordered ");
        queueIdx++;
    }

    private void record(StashRef ref, Map<String, Integer> lines, String id, long sentAt, String verb) {
        Map<String, Long> asLong = new LinkedHashMap<>();
        long items = 0;
        for (Map.Entry<String, Integer> e : lines.entrySet()) {
            asLong.put(e.getKey(), (long) e.getValue());
            items += e.getValue();
        }
        ledger.placed(new OrderLedger.Placed(id, ref.key(), asLong, sentAt));
        int shulkers = OrderBatch.shulkers(asLong, view::maxStack);
        ledger.addShulkers(shulkers);
        StringBuilder sb = new StringBuilder(verb).append(shulkers).append(shulkers == 1 ? " shulker" : " shulkers")
            .append(" [").append(lines.size()).append(lines.size() == 1 ? " kind, " : " kinds, ").append(items).append(" items] from stash ")
            .append(ChatText.safe(ref.stash(), 48));
        if (!streamer()) sb.append(" @ ").append(ChatText.safe(ref.server(), 48));
        sb.append(" [order ").append(ChatText.safe(id, 24)).append("].");
        if (!ref.online()) sb.append(" The bot is offline; it starts when it reconnects.");
        view.say(false, sb.toString());
    }

    private void adopted(OrderModel.Listing listing) {
        Sent sent = adopt;
        adopt = null;
        adoptFails = 0;
        if (sent == null) {
            endGoal();
            return;
        }
        List<OrderAdopt.Listed> listed = new ArrayList<>();
        for (OrderModel.Entry e : listing.orders()) {
            Map<String, Long> lines = new HashMap<>();
            boolean plain = true;
            for (Order.Line l : e.lines()) {
                if (!Order.ITEM.equals(l.type())) plain = false;
                lines.merge(l.key(), (long) l.count(), Long::sum);
            }
            if (plain) listed.add(new OrderAdopt.Listed(e.id(), e.open(), e.hasPlacedBy(), e.placedById(), lines));
        }
        Map<String, Long> asLong = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : sent.lines().entrySet()) asLong.put(e.getKey(), (long) e.getValue());
        OrderAdopt.Outcome out = OrderAdopt.resolve(listed, auth == null ? "" : auth.userId(), asLong, ledger::knows,
            OrderBatch.shulkers(asLong, view::maxStack));
        if (out.id() != null) {
            record(sent.ref(), sent.lines(), out.id(), sent.sentAt(), "Order found: ");
        } else {
            unconfirmed(sent);
        }
        endGoal();
    }

    /** The order could not be found or confirmed: it may still exist, so it counts against the run and the stash rests. */
    private void unconfirmed(Sent sent) {
        Map<String, Long> asLong = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : sent.lines().entrySet()) asLong.put(e.getKey(), (long) e.getValue());
        OrderAdopt.Outcome out = OrderAdopt.resolve(List.of(), "", asLong, id -> false, OrderBatch.shulkers(asLong, view::maxStack));
        ledger.addShulkers(out.chargeShulkers());
        skipStash.put(sent.ref().key(), nowMs + SKIP_MS);
        view.say(true, "Could not confirm the order to stash " + ChatText.safe(sent.ref().stash(), 48)
            + ". It is counted against the run limit and that stash is skipped for 5 minutes.");
    }

    private void polled(OrderModel.Listing listing) {
        String key = pollKey;
        endGoal();
        if (key == null) return;
        List<OrderLedger.Seen> seen = new ArrayList<>();
        for (OrderModel.Entry e : listing.orders()) seen.add(new OrderLedger.Seen(e.id(), e.status(), e.message()));
        for (OrderLedger.Event ev : ledger.update(key, seen, nowMs)) tellEvent(ev);
    }

    private void tellEvent(OrderLedger.Event ev) {
        OrderLedger.Placed p = ev.order();
        String id = ChatText.safe(p.id(), 24);
        String stash = stashName(p.stashKey());
        String note = ChatText.safe(ev.message() == null ? "" : ev.message(), 160);
        switch (ev.kind()) {
            case DELIVERED -> {
                int n = OrderBatch.shulkers(p.lines(), view::maxStack);
                view.say(false, "Order " + id + " from stash " + stash + " delivered [" + n + (n == 1 ? " shulker" : " shulkers")
                    + "]. Collect it from the bot's delivery chest.");
            }
            case FAILED -> view.say(true, "Order " + id + " from stash " + stash + " failed" + (note.isEmpty() ? "" : ": " + note)
                + ". Those items are not ordered from there again this run.");
            case CANCELLED -> view.say(true, "Order " + id + " from stash " + stash + " was cancelled" + (note.isEmpty() ? "" : ": " + note)
                + ". Those items are not ordered again this run.");
            case VANISHED -> view.say(false, "Order " + id + " from stash " + stash + " is no longer listed on the server. If it was delivered, collect it from the bot's delivery chest.");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Failures

    private void failed(Req req, ApiResult r) {
        ApiError.Kind kind = r.kind();
        boolean ambiguous = req == Req.POST && (kind == ApiError.Kind.NETWORK || kind == ApiError.Kind.SERVER);
        if (ambiguous && queue != null && queueIdx < queue.size()) {
            PlannedOrder p = queue.get(queueIdx);
            adopt = new Sent(p.ref(), p.lines(), flightAt);
        }
        if (req == Req.ADOPT_GET && adopt != null) {
            adoptFails++;
            if (OrderAdopt.giveUp(kind == ApiError.Kind.NOT_FOUND, adoptFails)) {
                Sent sent = adopt;
                adopt = null;
                adoptFails = 0;
                unconfirmed(sent);
                retryAt = nowMs + 2_000;
                endGoal();
                return;
            }
        }
        switch (kind) {
            case LOGIN -> {
                authAt = -FRESH_MS * 2;
                warnOnce("login", "The Stash Manager session ended. Log in again in the Stash Window; stash orders try again every minute.");
                retryAt = nowMs + FRESH_MS;
            }
            case MUST_CHANGE -> {
                authAt = -FRESH_MS * 2;
                warnOnce("mustchange", "The Stash Manager server wants a new password for your account. Change it in the Stash Window; stash orders try again every minute.");
                retryAt = nowMs + FRESH_MS;
            }
            case NO_PERMISSION -> {
                denied = true;
                warnOnce("denied", "Your Stash Manager account may not place orders, so stash orders are off for this run.");
            }
            case CONFIRM -> {
                warnOnce("confirm", "The Stash Manager server wants your password again before it takes orders. Open the Stash Window and confirm it there;"
                    + " stash orders wait 5 minutes.");
                retryAt = nowMs + MAX_BACKOFF;
            }
            case RATE_LIMIT -> retryAt = nowMs + Math.max(RATE_FLOOR_MS, r.wait429() * 1000L);
            case BAD_REQUEST -> badRequest(req, r);
            case NOT_FOUND, CONFLICT -> {
                // Only that stash is left alone; the other stashes carry on at once
                skipTarget(req);
                retryAt = nowMs + 2_000;
            }
            case CANCELLED -> { }
            default -> {
                warnOnce("network", "The Stash Manager server could not be reached. Stash orders try again soon.");
                backoffNow();
            }
        }
        if (goal == Goal.CYCLE) transientFail = kind != ApiError.Kind.NO_PERMISSION;
        endGoal();
    }

    /** The stash the failed request was about is left alone for a while. */
    private void skipTarget(Req req) {
        if (req == Req.POST && queue != null && queueIdx < queue.size()) skipStash.put(queue.get(queueIdx).ref().key(), nowMs + SKIP_MS);
        else if (req == Req.AVAIL && targets != null && targetIdx < targets.size()) skipStash.put(targets.get(targetIdx).key(), nowMs + SKIP_MS);
    }

    private void badRequest(Req req, ApiResult r) {
        String text = ChatText.safe(r.error(), 160);
        if (req == Req.POST && queue != null && queueIdx < queue.size()) {
            PlannedOrder p = queue.get(queueIdx);
            JsonElement line = r.obj().get("line");
            if (line != null && line.isJsonPrimitive() && line.getAsJsonPrimitive().isNumber()) {
                int idx = line.getAsInt();
                List<String> keys = new ArrayList<>(p.lines().keySet());
                if (idx >= 0 && idx < keys.size()) {
                    skipLine.put(p.ref().key() + "\u0000" + keys.get(idx), nowMs + SKIP_MS);
                    view.say(true, "The server refused " + view.itemName(keys.get(idx)) + " from stash " + ChatText.safe(p.ref().stash(), 48)
                        + (text.isEmpty() ? "" : ": " + text) + ". It is left out for 5 minutes.");
                    backoffNow();
                    return;
                }
            }
            skipStash.put(p.ref().key(), nowMs + SKIP_MS);
            view.say(true, "The server refused the order to stash " + ChatText.safe(p.ref().stash(), 48) + (text.isEmpty() ? "" : ": " + text)
                + ". That stash is skipped for 5 minutes.");
        } else {
            skipTarget(req);
        }
        backoffNow();
    }

    private void backoffNow() {
        retryAt = nowMs + backoff;
        backoff = Math.min(MAX_BACKOFF, backoff * 2);
    }

    /** Ends what is being done. An unknown POST ({@link #adopt}) is kept: it is looked for before anything is ordered again. */
    private void endGoal() {
        goal = Goal.NONE;
        flying = null;
        clearCycle();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Text

    private boolean streamer() {
        StashWindow w = StashWindow.get();
        return new DisplayPolicy(auth != null && auth.forcedStreamer(), w != null && w.streamerDisplay(), true).streamer();
    }

    private static String stashName(String stashKey) {
        int cut = stashKey.indexOf('\u0000');
        return ChatText.safe(cut < 0 ? stashKey : stashKey.substring(cut + 1), 48);
    }

    private void warnOnce(String key, String text) {
        if (warned.add(key) && view != null) view.say(true, text);
    }
}
