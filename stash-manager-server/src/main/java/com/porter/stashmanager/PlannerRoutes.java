package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.porter.stashmanager.planner.BlockItems;
import com.porter.stashmanager.planner.MaterialList;
import com.porter.stashmanager.planner.Schematic;
import com.porter.stashmanager.planner.SchematicException;
import com.porter.stashmanager.planner.SchematicReader;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The build planner (PROTOCOL.md, "Additions: build planner"): upload a schematic, see its materials against a stash
 * (have / available / missing), and order what the stash has, split into orders of at most 54 shulkers.
 * The uploaded bytes live only for the request: they are parsed on a small dedicated pool and dropped; only the
 * computed list is kept, in memory ({@link PlanStore}).
 */
final class PlannerRoutes {
    private static final Pattern PLAN = Pattern.compile("^/api/v1/planner/(p-[0-9a-f]{12})$");
    private static final Pattern DELETE = Pattern.compile("^/api/v1/planner/(p-[0-9a-f]{12})/delete$");
    private static final Pattern ORDER = Pattern.compile("^/api/v1/planner/(p-[0-9a-f]{12})/order$");
    private static final Pattern VIEW = Pattern.compile("^/api/v1/planner/(p-[0-9a-f]{12})/view$");

    static final long MAX_UPLOAD = Http.MAX_BODY; // 8 MiB
    static final Duration PARSE_TIMEOUT = Duration.ofSeconds(10);
    static final int PARSERS = 2;
    static final int PER_MINUTE = 10;
    static final int PER_HOUR = 60;
    static final int MAX_MULTIPLIER = 64;
    static final int MAX_EXCLUDE = 2000;
    static final int MAX_ID = 200;
    static final Set<String> ROUNDS = Set.of("none", "stack", "shulker");
    static final String NAME_PREFIX = "Build: ";
    static final String OPTIONS_LOCKED = "options can't change after a part is ordered; dismiss and upload again, or keep the options";
    /** The creative tabs in menu order; anything else sorts after them. */
    static final List<String> TABS = List.of("building_blocks", "colored_blocks", "natural_blocks", "functional_blocks",
        "redstone_blocks", "tools_and_utilities", "combat", "food_and_drinks", "ingredients", "spawn_eggs", "op_blocks");
    private static final long MINUTE = 60_000;
    private static final long HOUR = 3_600_000;
    private static final AtomicInteger THREADS = new AtomicInteger();

    private final IndexStore index;
    private final OrderStore orders;
    private final PlanStore plans;
    private final AuditLog audit;
    private final CraftService craft; // null: no crafting (rows carry no craft fields)
    private final ExecutorService parsers = Executors.newFixedThreadPool(PARSERS, r -> {
        Thread t = new Thread(r, "stash-manager-planner-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private final Semaphore parsing = new Semaphore(PARSERS);
    /** Upload bodies being received at once (each up to 8 MiB in memory). */
    private final Semaphore reading = new Semaphore(4);
    private final Map<String, Deque<Long>> uploads = new HashMap<>();

    PlannerRoutes(IndexStore index, OrderStore orders, PlanStore plans, AuditLog audit) {
        this(index, orders, plans, audit, null);
    }

    PlannerRoutes(IndexStore index, OrderStore orders, PlanStore plans, AuditLog audit, CraftService craft) {
        this.craft = craft;
        this.index = index;
        this.orders = orders;
        this.plans = plans;
        this.audit = audit;
    }

    void register(Router router) {
        router.exact(Router.POST, "/api/v1/planner/upload", Router.Guard.BROWSER_UPLOAD, Router.Perm.PLANNER, this::upload)
            .exact(Router.GET, "/api/v1/planner", Router.Guard.SESSION, Router.Perm.PLANNER, this::list)
            .pattern(Router.GET, PLAN, Router.Guard.SESSION, Router.Perm.PLANNER, this::view)
            .pattern(Router.POST, VIEW, Router.Guard.BROWSER_WRITE, Router.Perm.PLANNER, this::viewPost)
            .pattern(Router.POST, DELETE, Router.Guard.BROWSER_WRITE, Router.Perm.PLANNER, this::delete)
            .pattern(Router.POST, ORDER, Router.Guard.BROWSER_WRITE, Router.Perm.PLANNER_ORDER, this::order);
    }

    // ---------- options ----------

    /** What the view and the order are computed with. exclude is sorted, so key() is the same for the same choices. */
    record Options(int multiplier, Set<String> exclude, boolean fluids, boolean containerItems, String round, boolean craft) {
        static final Options DEFAULT = new Options(1, Set.of(), false, false, "none", false);

        /** Without the craft option (an older page): the order has no craft lines. */
        Options(int multiplier, Set<String> exclude, boolean fluids, boolean containerItems, String round) {
            this(multiplier, exclude, fluids, containerItems, round, false);
        }

        String key(String server, String stash) {
            return multiplier + "|" + exclude + "|" + fluids + "|" + containerItems + "|" + round + "|" + craft + "|" + server.length() + ":" + server + "|" + stash;
        }

        static Options fromQuery(Map<String, String> q) {
            int m = 1;
            String ms = q.get("multiplier");
            if (ms != null && !ms.isEmpty()) {
                try {
                    m = Integer.parseInt(ms.trim());
                } catch (NumberFormatException e) {
                    m = -1;
                }
            }
            Set<String> exclude = new TreeSet<>();
            String ex = q.get("exclude");
            if (ex != null && !ex.isEmpty()) for (String id : ex.split(",")) if (!id.isBlank()) exclude.add(id.trim());
            return check(m, exclude, flag(q.get("fluids")), flag(q.get("containerItems")), q.getOrDefault("round", "none"), flag(q.get("craft")));
        }

        static Options fromJson(JsonObject o) {
            int m = 1;
            JsonElement me = o.get("multiplier");
            if (me != null && !me.isJsonNull()) {
                long v = OrderRoutes.positiveInt(me);
                m = v < 1 || v > MAX_MULTIPLIER ? -1 : (int) v;
            }
            Set<String> exclude = new TreeSet<>();
            JsonElement ee = o.get("exclude");
            if (ee != null && !ee.isJsonNull()) {
                if (!ee.isJsonArray()) throw new IllegalArgumentException("exclude must be a list of item ids");
                for (JsonElement e : ee.getAsJsonArray()) {
                    if (!(e instanceof JsonPrimitive p) || !p.isString()) throw new IllegalArgumentException("exclude must be a list of item ids");
                    exclude.add(p.getAsString().trim());
                }
            }
            JsonElement re = o.get("round");
            String round = re == null || re.isJsonNull() ? "none" : re instanceof JsonPrimitive p && p.isString() ? p.getAsString() : "?";
            return check(m, exclude, bool(o, "fluids"), bool(o, "containerItems"), round, bool(o, "craft"));
        }

        private static Options check(int multiplier, Set<String> exclude, boolean fluids, boolean containers, String round, boolean craft) {
            if (multiplier < 1 || multiplier > MAX_MULTIPLIER) throw new IllegalArgumentException("multiplier must be a whole number from 1 to " + MAX_MULTIPLIER);
            if (exclude.size() > MAX_EXCLUDE) throw new IllegalArgumentException("at most " + MAX_EXCLUDE + " excluded items");
            for (String id : exclude) if (id.length() > MAX_ID) throw new IllegalArgumentException("excluded item ids are at most " + MAX_ID + " characters");
            if (!ROUNDS.contains(round)) throw new IllegalArgumentException("round must be none, stack or shulker");
            return new Options(multiplier, java.util.Collections.unmodifiableSet(new TreeSet<>(exclude)), fluids, containers, round, craft);
        }

        private static boolean flag(String v) {
            return v != null && (v.equals("1") || v.equalsIgnoreCase("true"));
        }

        private static boolean bool(JsonObject o, String key) {
            JsonElement e = o.get(key);
            if (e == null || e.isJsonNull()) return false;
            if (e instanceof JsonPrimitive p && p.isBoolean()) return p.getAsBoolean();
            throw new IllegalArgumentException(key + " must be true or false");
        }
    }

    // ---------- the join ----------

    /** One material against one stash. need is base × multiplier; want is what an order would ask for. */
    record Row(String id, long base, long need, long fromBlocks, long fromFluids, long fromContainers, boolean inIndex,
               long inStash, long available, long missing, long want, int maxStack, long variants, boolean excluded,
               int tab, int tabOrder) {
    }

    static final Comparator<Row> ORDER_OF = Comparator.comparingInt(Row::tab).thenComparingInt(Row::tabOrder).thenComparing(Row::id);

    /** Rows for every material of the plan (with the chosen sources), in creative order; counts null = no index. */
    List<Row> rows(PlanStore.Plan p, Options opt, IndexStore.OrderCounts counts, Map<String, Long> reserved,
                   Map<String, CreativeTabs.Entry> tabs) {
        Map<String, long[]> sources = new TreeMap<>(); // id -> {blocks, fluids, containers}
        p.items.forEach((id, n) -> sources.computeIfAbsent(id, k -> new long[3])[0] += n);
        if (opt.fluids()) p.fluids.forEach((id, n) -> sources.computeIfAbsent(id, k -> new long[3])[1] += n);
        if (opt.containerItems()) p.containerItems.forEach((id, n) -> sources.computeIfAbsent(id, k -> new long[3])[2] += n);
        Map<String, Long> variants = new HashMap<>();
        if (counts != null) {
            counts.items().forEach((key, n) -> {
                String id = counts.itemIds().get(key);
                if (id != null && !key.equals(id) && sources.containsKey(id)) variants.merge(id, n, Long::sum);
            });
        }
        List<Row> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : sources.entrySet()) {
            String id = e.getKey();
            long[] s = e.getValue();
            long base = s[0] + s[1] + s[2];
            long need = mul(base, opt.multiplier());
            long total = counts == null ? -1 : counts.total("item", id);
            boolean inIndex = total >= 0;
            long inStash = Math.max(0, total);
            long available = inIndex ? Math.max(0, inStash - reserved.getOrDefault("item:" + id, 0L)) : 0;
            int maxStack = counts != null && counts.maxStacks().containsKey(id) ? counts.maxStack(id) : p.maxStack(id);
            boolean excluded = opt.exclude().contains(id);
            long want = excluded ? 0 : Math.min(round(need, maxStack, opt.round()), available);
            long missing = Math.max(0, need - available);
            CreativeTabs.Entry t = tabs.get(id);
            int tab = t == null ? TABS.size() : TABS.indexOf(t.tab());
            if (tab < 0) tab = TABS.size();
            out.add(new Row(id, base, need, mul(s[0], opt.multiplier()), mul(s[1], opt.multiplier()), mul(s[2], opt.multiplier()),
                inIndex, inStash, available, missing, want, maxStack, variants.getOrDefault(id, 0L), excluded, tab,
                t == null ? 1_000_000 : t.order()));
        }
        out.sort(ORDER_OF);
        return out;
    }

    private static long mul(long a, int b) {
        long r = a * b;
        return a != 0 && r / a != b ? Long.MAX_VALUE : r;
    }

    /** need rounded up to whole stacks or whole shulkers (27 stacks), as asked. */
    static long round(long need, int maxStack, String round) {
        long unit = switch (round) {
            case "stack" -> maxStack;
            case "shulker" -> (long) OrderStore.SHULKER_SLOTS * maxStack;
            default -> 1;
        };
        if (unit <= 1 || need <= 0) return need;
        long r = (need + unit - 1) / unit;
        return r > Long.MAX_VALUE / unit ? Long.MAX_VALUE : r * unit;
    }

    // ---------- splitting into orders ----------

    /** One order's worth: at most 54 shulkers (OrderStore.shulkersNeeded) and 200 lines. */
    record Part(List<OrderStore.Line> lines, long shulkers, long slots, JsonObject craft) {
        Part(List<OrderStore.Line> lines, long shulkers, long slots) {
            this(lines, shulkers, slots, null);
        }

        long items() {
            long n = 0;
            for (OrderStore.Line l : lines) n += l.count();
            return n;
        }
    }

    /**
     * The plan's split, for the options and stash it was made with. placed: part number (1-based) -> order id. Once a
     * part is placed the split is kept as it is (later parts are the ones that were confirmed), and while any placed
     * part's order is not failed or cancelled the options can't change ({@link #locked}). Guarded by the plan.
     */
    static final class Split {
        final String key;
        final Options options;
        final String server;
        final String stash;
        final List<Part> parts;
        final Map<Integer, String> placed = new TreeMap<>();

        Split(String key, Options options, String server, String stash, List<Part> parts) {
            this.key = key;
            this.options = options;
            this.server = server;
            this.stash = stash;
            this.parts = List.copyOf(parts);
        }

        /** Parts never placed. */
        List<Integer> remaining() {
            List<Integer> out = new ArrayList<>();
            for (int i = 1; i <= parts.size(); i++) if (!placed.containsKey(i)) out.add(i);
            return out;
        }

        /** Placed parts whose order failed or was cancelled: they may be ordered again. */
        List<Integer> again(OrderStore orders) {
            List<Integer> out = new ArrayList<>();
            placed.forEach((part, id) -> {
                if (failed(orders.statusOf(id))) out.add(part);
            });
            return out;
        }

        /**
         * Some placed part's order is pending, running or delivered (or gone from the history, which can't be told
         * apart from delivered): ordering with other options could order those items twice.
         */
        boolean locked(OrderStore orders) {
            for (String id : placed.values()) if (!failed(orders.statusOf(id))) return true;
            return false;
        }
    }

    /** Order statuses after which the part was not delivered and may be ordered again. */
    static final Set<String> FAILED = Set.of("failed", "cancelled");

    static boolean failed(String status) {
        return status != null && FAILED.contains(status);
    }

    /**
     * Splits item lines (in the given order) into parts. Each item line is worth ceil(count / maxStack) slots, and an
     * empty-shulker line one shulker per unit; a part may need at most 54 shulkers and hold at most 200 lines. A line
     * that doesn't fit is split at the part's remaining room (whole stacks) and the rest starts the next part.
     */
    static List<Part> split(List<OrderStore.Line> lines, IndexStore.OrderCounts counts) {
        return splitAfter(new ArrayList<>(), List.of(), null, lines, counts);
    }

    /**
     * The same, continuing: parts already made (done), then the lines of an open part (init, with its craft summary) that
     * item lines may still fill up, then the item lines. Used to put the craft lines first.
     */
    static List<Part> splitAfter(List<Part> done, List<OrderStore.Line> init, JsonObject initCraft, List<OrderStore.Line> lines,
                                 IndexStore.OrderCounts counts) {
        List<Part> parts = new ArrayList<>(done);
        List<OrderStore.Line> cur = new ArrayList<>(init);
        long[] used = new long[2]; // slots, shulker units
        for (OrderStore.Line l : init) {
            if (l.type().equals("craft") || !counts.isShulkerBoxKey(l.key())) used[0] += ceilDiv(l.count(), Math.max(1, stackOf(counts, l)));
            else used[1] += l.count();
        }
        JsonObject[] craftOfCur = {initCraft};
        Runnable flush = () -> {
            if (cur.isEmpty()) return;
            parts.add(new Part(List.copyOf(cur), OrderStore.shulkersNeeded(cur, counts), used[0], craftOfCur[0]));
            craftOfCur[0] = null;
            cur.clear();
            used[0] = 0;
            used[1] = 0;
        };
        for (OrderStore.Line l : lines) {
            long left = l.count();
            boolean box = counts.isShulkerBoxKey(l.key());
            long max = Math.max(1, counts.maxStack(l.key()));
            while (left > 0) {
                if (cur.size() >= OrderStore.MAX_LINES) flush.run();
                long room = box
                    ? OrderStore.MAX_SHULKERS - used[1] - ceilDiv(used[0], OrderStore.SHULKER_SLOTS)
                    : ((OrderStore.MAX_SHULKERS - used[1]) * OrderStore.SHULKER_SLOTS - used[0]) * max;
                long take = Math.min(left, room);
                if (take <= 0) {
                    flush.run();
                    continue;
                }
                cur.add(new OrderStore.Line("item", l.key(), take));
                if (box) used[1] += take;
                else used[0] += ceilDiv(take, max);
                left -= take;
            }
        }
        flush.run();
        for (Part p : parts) {
            if (p.lines.size() > OrderStore.MAX_LINES || OrderStore.shulkersNeeded(p.lines, counts) > OrderStore.MAX_SHULKERS) {
                throw new IllegalStateException("planner split produced an order the store would refuse");
            }
        }
        return parts;
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    private static int stackOf(IndexStore.OrderCounts counts, OrderStore.Line l) {
        return l.type().equals("craft") ? CraftService.maxStack(counts, l.key()) : counts.maxStack(l.key());
    }

    private static List<OrderStore.Line> wantLines(List<Row> rows) {
        List<OrderStore.Line> out = new ArrayList<>();
        for (Row r : rows) if (r.want > 0) out.add(new OrderStore.Line("item", r.id, r.want));
        return out;
    }

    // ---------- crafting ----------

    /**
     * What the craft planner made of a build against a stash (cached on the plan). parts: set only when ordering with
     * crafting. truncated: the plan or the split ran out of its budget (such a result is never cached).
     */
    record Crafted(CraftPlanner.Plan plan, Map<String, CraftPlanner.RowResult> byId, List<Part> parts, boolean truncated) {
    }

    /** counts is the index data the plan was made from (compared by identity, so a new index is always planned again). */
    record CraftCacheEntry(String key, IndexStore.OrderCounts counts, Crafted crafted, long at) {
    }

    /** status is read fresh every time; crafted is null when the recipes (or the settings) aren't there. */
    record CraftView(CraftService.Status status, Crafted crafted) {
        List<Part> parts() {
            return crafted == null ? null : crafted.parts();
        }
    }

    static final int MAX_PART_RUNS = 600;
    static final int PLANS_PER_MINUTE = 30;

    /**
     * The craft view of a plan for a stash: null without crafting or a stash. The planner's numbers (and the parts when the
     * option is on and the stash can take craft orders) are cached on the plan until the index, the reservations, the
     * settings, the recipes or the options change. Identical requests at once wait for one computation (single flight); a
     * computation needs a planning slot (503 when all are taken) and counts against the user's 30 a minute (429); one
     * deadline of about 3 s covers the planner and the split together.
     */
    private CraftView craftView(PlanStore.Plan p, String server, String stash, Options opt, List<Row> rows, IndexStore.OrderCounts counts) {
        if (craft == null || server == null || counts == null) return null;
        CraftService.Status st = craft.status(server, stash);
        if (st.recipes() == null || st.settings() == null) return new CraftView(st, null);
        java.util.Set<String> never = st.settings().neverUse();
        boolean ordering = opt.craft() && st.canOrder();
        Map<String, Long> reserved = orders.reservedFor(server, stash);
        String key = p.id + "|" + opt.key(server, stash) + "|" + new TreeMap<>(reserved) + "|"
            + st.recipes().tableHash() + "|" + never + "|" + ordering + "|" + (ordering ? st.maxFills() : 0);
        synchronized (p.craftLock) {
            CraftCacheEntry cached = p.craftCache;
            // A complete plan is reused as long as nothing changed; a truncated one only for a few seconds (a negative cache), so
            // the requests waiting behind the one that ran out of time don't each spend the whole budget again.
            if (cached != null && cached.counts() == counts && cached.key().equals(key)
                && (!cached.crafted().truncated() || System.currentTimeMillis() - cached.at() < craft.truncatedTtlMs)) {
                return new CraftView(st, cached.crafted());
            }
            Crafted crafted;
            try (CraftService.Permit permit = craft.acquire()) {
                craft.rateLimit("plan|" + p.owner, PLANS_PER_MINUTE); // only once a slot is held: a 503 costs no quota
                long deadline = System.nanoTime() + craft.planNanos;
                CraftPlanner.Eligible e = craft.eligible(st.recipes(), never);
                Map<String, Long> stock = CraftService.stock(counts, reserved);
                Map<String, Long> direct = new HashMap<>();
                List<CraftPlanner.Row> crows = new ArrayList<>();
                for (Row r : rows) {
                    if (!r.excluded) direct.merge(r.id, r.want, Long::sum);
                    crows.add(new CraftPlanner.Row(r.id, Math.max(0, r.need - Math.min(r.need, r.available)), r.excluded));
                }
                CraftPlanner.Plan plan = craft.planner(e).plan(stock, direct, crows, deadline);
                Map<String, CraftPlanner.RowResult> byId = new HashMap<>();
                for (CraftPlanner.RowResult rr : plan.rows()) byId.put(rr.id(), rr);
                boolean[] cut = {plan.truncated()};
                List<Part> parts = ordering ? craftParts(e, stock, direct, rows, byId, st.maxFills(), counts, deadline, cut) : null;
                crafted = new Crafted(plan, byId, parts, cut[0]);
            }
            p.craftCache = new CraftCacheEntry(key, counts, crafted, System.currentTimeMillis());
            return new CraftView(st, crafted);
        }
    }

    private static long slotsOf(List<OrderStore.Line> lines, IndexStore.OrderCounts counts) {
        long n = 0;
        for (OrderStore.Line l : lines) n += ceilDiv(l.count(), Math.max(1, stackOf(counts, l)));
        return n;
    }

    private static JsonObject craftSummary(List<OrderStore.Line> lines, CraftPlanner.Plan plan) {
        JsonObject o = new JsonObject();
        o.addProperty("lines", lines.size());
        o.addProperty("fills", plan.fills());
        o.add("take", CraftService.countsJson(plan.take()));
        return o;
    }

    /**
     * The split with craft lines first (CRAFT-CONTRACT.md, "Planner additions"): the rows' craftable amounts become craft
     * lines in plan order, packed into parts that stay within the shulker and line limits and the bot's grid-fill cap, each part
     * planned with what the earlier parts take. Item lines then fill the last craft part and go on in parts of their own.
     * Every try counts against MAX_PART_RUNS and the one shared deadline; when either runs out the rest of the craft lines
     * are left out ("plan too large": cut[0] is set), never added unchecked.
     */
    private List<Part> craftParts(CraftPlanner.Eligible e, Map<String, Long> stock, Map<String, Long> direct, List<Row> rows,
                                  Map<String, CraftPlanner.RowResult> byId, int maxFills, IndexStore.OrderCounts counts,
                                  long deadline, boolean[] cut) {
        List<OrderStore.Line> candidates = new ArrayList<>();
        for (Row r : rows) {
            CraftPlanner.RowResult rr = byId.get(r.id);
            if (!r.excluded && rr != null && rr.craftable() > 0) candidates.add(new OrderStore.Line("craft", r.id, rr.craftable()));
        }
        List<Part> done = new ArrayList<>();
        Map<String, Long> used = new HashMap<>();
        List<OrderStore.Line> cur = new ArrayList<>();
        CraftPlanner.Plan[] curPlan = {null};
        int[] runs = {0};
        Runnable flush = () -> {
            if (cur.isEmpty()) return;
            List<OrderStore.Line> lines = List.copyOf(cur);
            done.add(new Part(lines, OrderStore.shulkersNeeded(lines, counts), slotsOf(lines, counts), craftSummary(lines, curPlan[0])));
            for (CraftPlanner.Count c : curPlan[0].take()) used.merge(c.id(), c.count(), Long::sum);
            cur.clear();
            curPlan[0] = null;
        };
        outer:
        for (OrderStore.Line c : candidates) {
            long remaining = c.count();
            while (remaining > 0) {
                CraftPlanner.Plan whole = fits(e, stock, direct, used, cur, c.key(), remaining, maxFills, counts, runs, deadline, cut);
                long k;
                CraftPlanner.Plan best;
                if (whole != null) {
                    k = remaining;
                    best = whole;
                } else {
                    long lo = 0, hi = remaining - 1;
                    CraftPlanner.Plan found = null;
                    while (lo < hi && !cut[0]) {
                        long mid = lo + (hi - lo + 1) / 2;
                        CraftPlanner.Plan t = fits(e, stock, direct, used, cur, c.key(), mid, maxFills, counts, runs, deadline, cut);
                        if (t != null) {
                            lo = mid;
                            found = t;
                        } else {
                            hi = mid - 1;
                        }
                    }
                    k = lo;
                    best = found; // the plan of the last amount that fitted, which is lo
                }
                if (k > 0 && best != null) {
                    cur.add(new OrderStore.Line("craft", c.key(), k));
                    curPlan[0] = best;
                    remaining -= k;
                } else if (!cut[0]) {
                    if (cur.isEmpty()) break; // not even one fits a part of its own: left out
                    flush.run();
                    continue;
                }
                if (cut[0]) break outer;
            }
        }
        JsonObject lastSummary = null;
        List<OrderStore.Line> init = List.of();
        if (!cur.isEmpty()) {
            init = List.copyOf(cur);
            lastSummary = craftSummary(init, curPlan[0]);
        }
        return splitAfter(done, init, lastSummary, wantLines(rows), counts);
    }

    /**
     * The plan of the open part plus one more craft line, or null when that does not fit or is not fully craftable. Sets
     * cut[0] (and returns null) when the try budget or the shared deadline is used up, or the planner itself ran out.
     */
    private CraftPlanner.Plan fits(CraftPlanner.Eligible e, Map<String, Long> stock, Map<String, Long> direct, Map<String, Long> used,
                                   List<OrderStore.Line> cur, String key, long count, int maxFills, IndexStore.OrderCounts counts,
                                   int[] runs, long deadline, boolean[] cut) {
        if (++runs[0] > MAX_PART_RUNS || System.nanoTime() - deadline > 0) {
            cut[0] = true;
            return null;
        }
        List<OrderStore.Line> trial = new ArrayList<>(cur);
        trial.add(new OrderStore.Line("craft", key, count));
        if (trial.size() > OrderStore.MAX_LINES || OrderStore.shulkersNeeded(trial, counts) > OrderStore.MAX_SHULKERS) return null;
        Map<String, Long> pool = new HashMap<>();
        stock.forEach((id, n) -> {
            long left = n - used.getOrDefault(id, 0L);
            if (left > 0) pool.put(id, left);
        });
        List<CraftPlanner.Row> rs = new ArrayList<>();
        for (OrderStore.Line l : trial) rs.add(new CraftPlanner.Row(l.key(), l.count(), false));
        CraftPlanner.Plan plan = craft.planner(e).plan(pool, direct, rs, deadline);
        if (plan.truncated()) {
            cut[0] = true;
            return null;
        }
        for (int i = 0; i < trial.size(); i++) if (plan.rows().get(i).craftable() < trial.get(i).count()) return null;
        if (plan.fills() > maxFills || plan.steps().size() > CraftService.MAX_STEPS || plan.take().size() > CraftService.MAX_TAKE) return null;
        return plan;
    }

    /** The parts of an order: with crafting when the view has them, else the item lines only. */
    private static List<Part> parts(CraftView cv, List<Row> rows, IndexStore.OrderCounts counts) {
        List<Part> crafted = cv == null ? null : cv.parts();
        return crafted != null ? crafted : split(wantLines(rows), counts);
    }

    // ---------- routes ----------

    /** The upload's limits per user: 10 a minute and 60 an hour. Returns 0 (and counts it) or ms until allowed. */
    synchronized long tryUpload(String user) {
        long now = plans.now();
        uploads.values().removeIf(d -> {
            while (!d.isEmpty() && now - d.peekFirst() >= HOUR) d.pollFirst();
            return d.isEmpty();
        });
        Deque<Long> d = uploads.computeIfAbsent(user, k -> new ArrayDeque<>());
        if (d.size() >= PER_HOUR) return Math.max(1, d.peekFirst() + HOUR - now);
        if (d.size() >= PER_MINUTE) {
            long tenthNewest = new ArrayList<>(d).get(d.size() - PER_MINUTE);
            if (now - tenthNewest < MINUTE) return Math.max(1, tenthNewest + MINUTE - now);
        }
        d.addLast(now);
        return 0;
    }

    private static void tooMany(HttpExchange ex, long ms, String message) throws IOException {
        long seconds = Math.max(1, (ms + 999) / 1000);
        ex.getResponseHeaders().set("Retry-After", String.valueOf(seconds));
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        o.addProperty("retryAfter", seconds);
        Http.json(ex, 429, o);
    }

    /** Wall-clock limit for receiving an upload body, and for draining one that is refused. */
    static final long UPLOAD_READ_MS = 30_000;

    private static void drain(HttpExchange ex, long max, long timeoutMs) {
        long end = System.nanoTime() + timeoutMs * 1_000_000;
        try (java.io.InputStream in = ex.getRequestBody()) {
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while (total < max && System.nanoTime() - end < 0 && (n = in.read(buf)) != -1) total += n;
        } catch (IOException ignored) {
            // the client went away: nothing to drain
        }
    }

    /** POST /api/v1/planner/upload?server=..&stash=..&name=..: the raw schematic as the body. */
    private void upload(HttpExchange ex) throws IOException {
        Users.Principal who = Router.user(ex);
        Map<String, String> q = Http.query(ex);
        String server = q.get("server");
        String stash = q.get("stash");
        if ((server == null) != (stash == null) || (server != null && (server.isEmpty() || stash.isEmpty()))) {
            Http.error(ex, 400, "give both server and stash, or neither");
            return;
        }
        if (server != null) {
            server = OrderRoutes.scoped(ex, server, stash);
            if (server == null) return;
        }
        long wait = tryUpload(who.id());
        if (wait > 0) {
            tooMany(ex, wait, "too many uploads (at most " + PER_MINUTE + " a minute and " + PER_HOUR + " an hour); try again later");
            return;
        }
        // At most a few bodies are read at once, so slow or many uploads can't hold many threads or buffers.
        if (!reading.tryAcquire()) {
            tooMany(ex, 5000, "the server is busy receiving other schematics; try again in a few seconds");
            return;
        }
        byte[] body;
        try {
            body = Http.readBody(ex, MAX_UPLOAD, UPLOAD_READ_MS);
        } catch (Http.TooLarge e) {
            // The rest of the body is never read, so the connection is closed, and a reply body could be cut off by that:
            // only the status (the page says "larger than 8 MiB" for 413).
            // Take (and drop) some of what the client is still sending before answering and closing: closing with unread data
            // makes the TCP stack reset the connection, and the client would see a reset instead of the 413. Bounded.
            drain(ex, 12L * 1024 * 1024, UPLOAD_READ_MS);
            ex.getResponseHeaders().set("Connection", "close");
            Http.send(ex, 413, null, null, "no-store");
            return;
        } finally {
            reading.release();
        }
        if (body.length == 0) {
            Http.error(ex, 400, "the file is empty");
            return;
        }
        long size = body.length;
        if (!parsing.tryAcquire()) {
            tooMany(ex, 5000, "the server is busy reading other schematics; try again in a few seconds");
            return;
        }
        final byte[] file = body;
        body = null;
        // done() runs exactly once, when the task ends or is cancelled: the permit follows the work.
        FutureTask<Schematic> task = new FutureTask<>(() -> SchematicReader.read(file, PARSE_TIMEOUT)) {
            @Override
            protected void done() {
                parsing.release();
            }
        };
        try {
            parsers.execute(task);
        } catch (RuntimeException | Error e) {
            task.cancel(false); // runs done(), which gives the permit back
            Http.error(ex, 503, "the planner is not available");
            return;
        }
        Schematic s;
        try {
            s = task.get(PARSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            Http.error(ex, 400, "reading the file took too long (limit " + PARSE_TIMEOUT.toSeconds() + " s)");
            return;
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            Http.error(ex, 503, "interrupted");
            return;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof SchematicException se) {
                Http.error(ex, se.code() == SchematicException.Code.LEGACY ? 422 : 400, se.getMessage());
            } else {
                // Never the file or the exception message: only what kind of failure it was.
                System.err.println("Build planner: could not read an upload (" + (e.getCause() == null ? "?" : e.getCause().getClass().getSimpleName()) + ")");
                Http.error(ex, 400, "could not read this file");
            }
            return;
        }
        BlockItems table = s.dataVersion() > 0 ? BlockItems.forDataVersion(s.dataVersion()) : BlockItems.latest();
        MaterialList m = table.materials(s);
        Map<String, Integer> stacks = new HashMap<>();
        for (Map<String, Long> map : List.of(m.items(), m.fluids(), m.containerItems())) {
            for (String id : map.keySet()) stacks.put(id, table.maxStack(id));
        }
        String fileName = PlanStore.cleanFileName(q.get("name"));
        PlanStore.Plan p = new PlanStore.Plan(plans.newId(), who.id(), fileName, s.format().name().toLowerCase(java.util.Locale.ROOT),
            s.formatVersion(), s.dataVersion(), size, s.sizeX(), s.sizeY(), s.sizeZ(), s.regionCount(), s.nonAirBlocks(),
            m.tableVersion(), table.dataVersion(), m.items(), m.fluids(), m.containerItems(), m.unknown(), m.unknownOmitted(),
            stacks, plans.now());
        plans.add(p);
        audit.add(who, "planner.upload", fileName + " (" + p.format + ", " + p.blocks + " blocks)", AuthRoutes.address(ex));
        JsonObject out = Http.ok();
        out.add("plan", planJson(p, server, stash, Options.DEFAULT));
        Http.json(ex, 200, out);
    }

    /** GET /api/v1/planner: the user's plans (to pick up again after a page reload). */
    private void list(HttpExchange ex) throws IOException {
        JsonArray list = new JsonArray();
        for (PlanStore.Plan p : plans.list(Router.user(ex).id())) {
            JsonObject o = new JsonObject();
            o.addProperty("id", p.id);
            o.addProperty("fileName", p.fileName);
            o.addProperty("format", p.format);
            o.addProperty("blocks", p.blocks);
            o.addProperty("createdAt", p.createdAt);
            o.addProperty("expiresAt", p.expiresAt());
            list.add(o);
        }
        JsonObject out = new JsonObject();
        out.add("plans", list);
        Http.json(ex, 200, out);
    }

    /** GET /api/v1/planner/{id}?server=..&stash=..&multiplier=..&exclude=a,b&fluids=1&containerItems=1&round=.. */
    private void view(HttpExchange ex) throws IOException {
        Matcher mt = PLAN.matcher(ex.getRequestURI().getRawPath());
        if (!mt.matches()) {
            Http.error(ex, 404, "not found");
            return;
        }
        Map<String, String> q = Http.query(ex);
        String server = q.get("server");
        String stash = q.get("stash");
        if (server == null || server.isEmpty() || stash == null || stash.isEmpty()) {
            Http.error(ex, 400, "server and stash are required");
            return;
        }
        server = OrderRoutes.scoped(ex, server, stash);
        if (server == null) return;
        Options opt;
        try {
            opt = Options.fromQuery(q);
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        sendView(ex, mt.group(1), server, stash, opt);
    }

    private void sendView(HttpExchange ex, String id, String server, String stash, Options opt) throws IOException {
        PlanStore.Plan p = plans.get(id, Router.user(ex).id());
        if (p == null) {
            Http.error(ex, 404, "no such plan (plans end after 30 minutes unused)");
            return;
        }
        JsonObject out = Http.ok();
        out.add("plan", planJson(p, server, stash, opt));
        Http.json(ex, 200, out);
    }

    /**
     * POST /api/v1/planner/{id}/view {server, stash, multiplier, exclude, fluids, containerItems, round}: the same as
     * the GET, with the options in a JSON body (a long exclude list doesn't fit a URL). Changes nothing.
     */
    private void viewPost(HttpExchange ex) throws IOException {
        Matcher mt = VIEW.matcher(ex.getRequestURI().getRawPath());
        if (!mt.matches()) {
            Http.error(ex, 404, "not found");
            return;
        }
        JsonElement json = AddonRoutes.readJson(ex);
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return;
        }
        JsonObject o = json.getAsJsonObject();
        String server = AddonRoutes.string(o, "server");
        String stash = AddonRoutes.string(o, "stash");
        if (server == null || server.isEmpty() || stash == null || stash.isEmpty()) {
            Http.error(ex, 400, "server and stash are required");
            return;
        }
        server = OrderRoutes.scoped(ex, server, stash);
        if (server == null) return;
        Options opt;
        try {
            opt = Options.fromJson(o);
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        sendView(ex, mt.group(1), server, stash, opt);
    }

    private void delete(HttpExchange ex) throws IOException {
        Matcher mt = DELETE.matcher(ex.getRequestURI().getRawPath());
        if (!mt.matches() || !plans.delete(mt.group(1), Router.user(ex).id())) {
            Http.error(ex, 404, "no such plan");
            return;
        }
        Http.json(ex, 200, Http.ok());
    }

    /** POST /api/v1/planner/{id}/order {server, stash, multiplier, exclude, fluids, containerItems, round, names, dryRun, part}. */
    private void order(HttpExchange ex) throws IOException {
        Matcher mt = ORDER.matcher(ex.getRequestURI().getRawPath());
        if (!mt.matches()) {
            Http.error(ex, 404, "not found");
            return;
        }
        JsonElement json = AddonRoutes.readJson(ex);
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return;
        }
        JsonObject o = json.getAsJsonObject();
        String server = AddonRoutes.string(o, "server");
        String stash = AddonRoutes.string(o, "stash");
        if (server == null || server.isEmpty() || stash == null || stash.isEmpty()) {
            Http.error(ex, 400, "server and stash are required");
            return;
        }
        server = OrderRoutes.scoped(ex, server, stash);
        if (server == null) return;
        Users.Principal who = Router.user(ex);
        Options opt;
        boolean names;
        boolean dryRun;
        int part;
        try {
            opt = Options.fromJson(o);
            names = Options.bool(o, "names");
            dryRun = Options.bool(o, "dryRun");
            JsonElement pe = o.get("part");
            long pv = pe == null || pe.isJsonNull() ? 1 : OrderRoutes.positiveInt(pe);
            if (pv < 1) throw new IllegalArgumentException("part must be a whole number from 1");
            part = (int) Math.min(Integer.MAX_VALUE, pv);
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        PlanStore.Plan p = plans.get(mt.group(1), who.id());
        if (p == null) {
            Http.error(ex, 404, "no such plan (plans end after 30 minutes unused)");
            return;
        }
        IndexStore.OrderCounts counts = index.orderCounts(server, stash);
        if (counts == null) {
            Http.error(ex, 400, "no index for that stash");
            return;
        }
        List<Row> rows = rows(p, opt, counts, orders.reservedFor(server, stash), index.tabOrder(server, stash));
        CraftView cv = craftView(p, server, stash, opt, rows, counts);
        String key = opt.key(server, stash);
        synchronized (p) {
            Split split = p.split;
            if (split != null && !split.key.equals(key) && split.locked(orders)) {
                Http.error(ex, 409, OPTIONS_LOCKED);
                return;
            }
            // Same options with parts placed: the confirmed split. Otherwise a fresh one from the stash as it is now.
            if (split == null || !split.key.equals(key) || split.placed.isEmpty()) {
                split = new Split(key, opt, server, stash, parts(cv, rows, counts));
                p.split = split;
            }
            if (dryRun) {
                JsonObject out = Http.ok();
                out.addProperty("dryRun", true);
                JsonArray parts = new JsonArray();
                for (int i = 0; i < split.parts.size(); i++) parts.add(partJson(split.parts.get(i), i + 1, split.placed.get(i + 1)));
                out.add("parts", parts);
                out.addProperty("partsTotal", split.parts.size());
                out.add("remaining", ints(split.remaining()));
                out.add("again", ints(split.again(orders)));
                addShortfalls(out, rows, cv);
                if (cv != null) out.add("craft", craft.statusJson(cv.status(), cv.crafted() != null && cv.crafted().truncated()));
                Http.json(ex, 200, out);
                return;
            }
            if (split.parts.isEmpty()) {
                Http.error(ex, 400, "nothing to order: the stash has none of these materials available");
                return;
            }
            if (part > split.parts.size()) {
                Http.error(ex, 400, "there are only " + split.parts.size() + " part(s)");
                return;
            }
            String before = split.placed.get(part);
            if (before != null && !failed(orders.statusOf(before))) {
                Http.error(ex, 409, "part " + part + " is already ordered (" + before + ")");
                return;
            }
            Part chosen = split.parts.get(part - 1);
            JsonObject nameJson;
            OrderStore.Order order;
            try {
                nameJson = names ? OrderStore.normalizeNames(namesFor(p.fileName, part, split.parts.size())) : null;
                order = orders.place(server, stash, chosen.lines, nameJson, who.id(), who.name());
            } catch (OrderStore.OrderException e) {
                JsonObject err = new JsonObject();
                err.addProperty("ok", false);
                err.addProperty("error", e.getMessage());
                err.addProperty("part", part);
                Http.json(ex, e.status, err);
                return;
            }
            // Recorded first: whatever fails after this, the part is not offered again while its order is live.
            split.placed.put(part, order.id);
            if (before != null) {
                try {
                    orders.markRetried(before, order.id); // Retry on the old order now says it was placed again
                } catch (IOException e) {
                    System.err.println("Could not save an order book: " + e.getMessage());
                }
            }
            audit.add(who, "planner.order", p.fileName + ": part " + part + "/" + split.parts.size() + " as " + order.id
                + (before != null ? " (again, was " + before + ")" : "") + " (" + server + " / " + stash + ")", AuthRoutes.address(ex));
            JsonObject out = Http.ok();
            out.add("order", OrderRoutes.shown(ex, order.toJson()));
            out.addProperty("part", part);
            out.addProperty("partsTotal", split.parts.size());
            out.add("remaining", ints(split.remaining()));
            out.add("again", ints(split.again(orders)));
            out.add("placed", placedJson(split));
            Http.json(ex, 200, out);
        }
    }

    /** {"mode":"all","name":"Build: <file>"} plus " i/n" with several parts, cut to the anvil's 50 characters. */
    static JsonObject namesFor(String fileName, int part, int parts) {
        String suffix = parts > 1 ? " " + part + "/" + parts : "";
        StringBuilder clean = new StringBuilder();
        fileName.codePoints().filter(c -> c != '§').forEach(clean::appendCodePoint);
        String name = Http.truncate(NAME_PREFIX + clean.toString().strip(), OrderStore.MAX_NAME - suffix.length()).strip() + suffix;
        JsonObject o = new JsonObject();
        o.addProperty("mode", "all");
        o.addProperty("name", name);
        return o;
    }

    // ---------- JSON ----------

    private JsonObject planJson(PlanStore.Plan p, String server, String stash, Options opt) {
        JsonObject o = new JsonObject();
        o.addProperty("id", p.id);
        o.addProperty("fileName", p.fileName);
        o.addProperty("format", p.format);
        o.addProperty("formatVersion", p.formatVersion);
        o.addProperty("dataVersion", p.dataVersion);
        o.addProperty("size", p.size);
        JsonObject dim = new JsonObject();
        dim.addProperty("x", p.sizeX);
        dim.addProperty("y", p.sizeY);
        dim.addProperty("z", p.sizeZ);
        o.add("dimensions", dim);
        o.addProperty("regions", p.regions);
        o.addProperty("blocks", p.blocks);
        o.addProperty("tableVersion", p.tableVersion);
        o.addProperty("tableDataVersion", p.tableDataVersion);
        o.addProperty("newerThanTable", p.dataVersion > p.tableDataVersion);
        o.addProperty("createdAt", p.createdAt);
        o.addProperty("expiresAt", p.expiresAt());
        o.add("options", optionsJson(opt));
        o.add("fluids", countList(p.fluids));
        o.add("containerItems", countList(p.containerItems));
        o.add("unknown", countList(p.unknown));
        o.addProperty("unknownOmitted", p.unknownOmitted);

        IndexStore.OrderCounts counts = server == null ? null : index.orderCounts(server, stash);
        List<Row> rows = rows(p, opt, counts, server == null ? Map.of() : orders.reservedFor(server, stash),
            server == null ? Map.of() : index.tabOrder(server, stash));
        CraftView cv = craftView(p, server, stash, opt, rows, counts);
        Crafted crafted = cv == null ? null : cv.crafted();
        if (server == null) {
            o.add("stash", null);
        } else {
            JsonObject so = new JsonObject();
            so.addProperty("server", server);
            so.addProperty("stash", stash);
            so.addProperty("indexed", counts != null);
            so.addProperty("mcVersion", index.mcVersion(server, stash));
            o.add("stash", so);
        }
        JsonArray items = new JsonArray();
        String stashMc = server == null ? null : index.mcVersion(server, stash);
        long need = 0, have = 0, missing = 0, want = 0, craftable = 0, craftKinds = 0, missingAfterCraft = 0;
        for (Row r : rows) {
            JsonObject ro = new JsonObject();
            ro.addProperty("id", r.id);
            IndexStore.IconInfo info = index.iconInfo(r.id);
            // A material no index has (never stocked) still gets its real icon: allow the (validated, bounded) id.
            if (info == null) info = index.allowIcon(r.id, stashMc != null ? stashMc : index.anyMcVersion());
            ro.addProperty("name", info == null || info.englishName().isEmpty() ? null : info.englishName());
            ro.addProperty("icon", info != null);
            ro.addProperty("mc", info == null ? null : info.mcVersion());
            ro.addProperty("base", r.base);
            ro.addProperty("need", r.need);
            JsonObject src = new JsonObject();
            src.addProperty("blocks", r.fromBlocks);
            src.addProperty("fluids", r.fromFluids);
            src.addProperty("containers", r.fromContainers);
            ro.add("from", src);
            ro.addProperty("inIndex", r.inIndex);
            ro.addProperty("inStash", r.inStash);
            ro.addProperty("available", r.available);
            ro.addProperty("missing", r.missing);
            ro.addProperty("want", r.want);
            ro.addProperty("maxStack", r.maxStack);
            ro.addProperty("variants", r.variants);
            ro.addProperty("excluded", r.excluded);
            CraftPlanner.RowResult rr = crafted == null ? null : crafted.byId().get(r.id);
            long rowCraftable = rr == null ? 0 : rr.craftable();
            long rowMissingAfter = Math.max(0, r.need - r.available - rowCraftable);
            if (rr != null) {
                ro.addProperty("craftable", rowCraftable);
                ro.addProperty("missingAfterCraft", rowMissingAfter);
                ro.addProperty("craftStatus", rr.status());
                ro.addProperty("craftReason", rr.reason());
                ro.add("craftUses", CraftService.countsJson(rr.uses().size() > 32 ? rr.uses().subList(0, 32) : rr.uses()));
                ro.add("craftSteps", CraftService.stepsJson(rr.steps().size() > 16 ? rr.steps().subList(0, 16) : rr.steps(), false));
                ro.add("craftLeftovers", CraftService.countsJson(rr.leftovers()));
            }
            items.add(ro);
            if (r.excluded) continue;
            craftable = sat(craftable, rowCraftable);
            if (rowCraftable > 0) craftKinds++;
            missingAfterCraft = sat(missingAfterCraft, rowMissingAfter);
            need = sat(need, r.need);
            have = sat(have, Math.min(r.need, r.available));
            missing = sat(missing, r.missing);
            want = sat(want, r.want);
        }
        o.add("items", items);
        JsonObject totals = new JsonObject();
        totals.addProperty("need", need);
        totals.addProperty("available", have);
        totals.addProperty("missing", missing);
        totals.addProperty("want", want);
        if (crafted != null) {
            totals.addProperty("craftable", craftable);
            totals.addProperty("craftKinds", craftKinds);
            totals.addProperty("missingAfterCraft", missingAfterCraft);
            totals.addProperty("craftFills", crafted.plan().fills());
            totals.addProperty("wantWithCraft", sat(want, craftable)); // want (item lines) plus the craft lines
        }
        o.add("craft", cv == null ? com.google.gson.JsonNull.INSTANCE : craft.statusJson(cv.status(), crafted != null && crafted.truncated()));
        Split frozen;
        synchronized (p) {
            frozen = server != null && p.split != null && !p.split.placed.isEmpty() && p.split.key.equals(opt.key(server, stash)) ? p.split : null;
        }
        if (frozen != null || counts != null) {
            // Once a part is placed, the confirmed split (what "Order next part" works through), else a fresh one.
            List<Part> parts = frozen != null ? frozen.parts : parts(cv, rows, counts);
            long lines = 0;
            for (Part part : parts) lines += part.lines.size();
            if (frozen != null) {
                long ordering = 0;
                for (Part part : parts) ordering = sat(ordering, part.items());
                totals.addProperty("want", ordering); // what the confirmed parts order, not what would be ordered now
            }
            long shulkers = 0, slots = 0;
            for (Part part : parts) {
                shulkers += part.shulkers;
                slots += part.slots;
            }
            totals.addProperty("lines", lines);
            totals.addProperty("frozen", frozen != null);
            totals.addProperty("slots", slots);
            totals.addProperty("shulkers", shulkers);
            totals.addProperty("parts", parts.size());
        }
        o.add("totals", totals);
        synchronized (p) {
            Split split = p.split;
            if (split != null && !split.placed.isEmpty()) {
                // Always sent once a part is placed, also while other options are previewed: the page shows the
                // ordered parts and puts the options back (they can't change while locked).
                JsonObject ord = new JsonObject();
                ord.addProperty("partsTotal", split.parts.size());
                ord.add("placed", placedJson(split));
                ord.add("remaining", ints(split.remaining()));
                ord.add("again", ints(split.again(orders)));
                ord.addProperty("locked", split.locked(orders));
                ord.addProperty("sameOptions", server != null && split.key.equals(opt.key(server, stash)));
                ord.add("options", optionsJson(split.options));
                ord.addProperty("server", split.server);
                ord.addProperty("stash", split.stash);
                o.add("ordered", ord);
            } else {
                o.add("ordered", null);
            }
        }
        return o;
    }

    private static JsonObject optionsJson(Options opt) {
        JsonObject opts = new JsonObject();
        opts.addProperty("multiplier", opt.multiplier());
        JsonArray ex = new JsonArray();
        opt.exclude().stream().sorted().forEach(ex::add);
        opts.add("exclude", ex);
        opts.addProperty("fluids", opt.fluids());
        opts.addProperty("containerItems", opt.containerItems());
        opts.addProperty("round", opt.round());
        opts.addProperty("craft", opt.craft());
        return opts;
    }

    private static long sat(long a, long b) {
        long r = a + b;
        return r < 0 ? Long.MAX_VALUE : r;
    }

    private static JsonArray countList(Map<String, Long> m) {
        JsonArray a = new JsonArray();
        m.forEach((id, n) -> {
            JsonObject e = new JsonObject();
            e.addProperty("id", id);
            e.addProperty("count", n);
            a.add(e);
        });
        return a;
    }

    private static JsonObject partJson(Part p, int index, String orderId) {
        JsonObject o = new JsonObject();
        o.addProperty("part", index);
        JsonArray lines = new JsonArray();
        for (OrderStore.Line l : p.lines) {
            JsonObject lo = new JsonObject();
            lo.addProperty("type", l.type());
            lo.addProperty("key", l.key());
            lo.addProperty("count", l.count());
            lines.add(lo);
        }
        o.add("lines", lines);
        o.addProperty("lineCount", p.lines.size());
        o.addProperty("shulkers", p.shulkers);
        o.addProperty("slots", p.slots);
        o.addProperty("items", p.items());
        o.addProperty("order", orderId);
        o.add("craft", p.craft == null ? com.google.gson.JsonNull.INSTANCE : p.craft.deepCopy()); // never share the cached object
        return o;
    }

    private static void addShortfalls(JsonObject out, List<Row> rows, CraftView cv) {
        Map<String, CraftPlanner.RowResult> byId = cv == null || cv.crafted() == null ? Map.of() : cv.crafted().byId();
        JsonArray notAvailable = new JsonArray();
        JsonArray missing = new JsonArray();
        for (Row r : rows) {
            if (r.excluded || r.need <= 0) continue;
            if (r.want == 0) {
                JsonObject e = new JsonObject();
                e.addProperty("id", r.id);
                e.addProperty("need", r.need);
                notAvailable.add(e);
            }
            if (r.missing > 0) {
                JsonObject e = new JsonObject();
                e.addProperty("id", r.id);
                e.addProperty("missing", r.missing);
                CraftPlanner.RowResult rr = byId.get(r.id);
                if (rr != null) e.addProperty("missingAfterCraft", Math.max(0, r.need - r.available - rr.craftable()));
                missing.add(e);
            }
        }
        out.add("notAvailable", notAvailable);
        out.add("missing", missing);
    }

    private static JsonObject placedJson(Split s) {
        JsonObject o = new JsonObject();
        s.placed.forEach((k, v) -> o.addProperty(String.valueOf(k), v));
        return o;
    }

    private static JsonArray ints(List<Integer> list) {
        JsonArray a = new JsonArray();
        list.forEach(a::add);
        return a;
    }
}
