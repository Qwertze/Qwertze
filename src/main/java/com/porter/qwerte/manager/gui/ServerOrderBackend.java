package com.porter.qwerte.manager.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.porter.qwerte.manager.model.AuthState;
import com.porter.qwerte.manager.model.BotInfo;
import com.porter.qwerte.manager.model.DeliveryModel;
import com.porter.qwerte.manager.model.IndexModel;
import com.porter.qwerte.manager.model.NamesDraft;
import com.porter.qwerte.manager.model.OrderModel;
import com.porter.qwerte.manager.model.StashRef;
import com.porter.qwerte.manager.model.Text;
import com.porter.qwerte.manager.net.ApiError;
import com.porter.qwerte.manager.net.ApiResult;
import com.porter.qwerte.manager.net.SmClient;
import com.porter.qwerte.stash.Order;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Orders carried out by the bots of the Stash Manager server: the website's orders API. It polls the order list (every 3
 * seconds on the Orders tab, every 15 otherwise) and the delivery contents (when the stash's summary changes), and sends the
 * place, cancel, retry, force-cancel and clear requests with the permissions of the logged-in user.
 */
final class ServerOrderBackend implements OrderBackend {
    /** What the window gives this backend. */
    interface Host {
        StashRef stash();

        IndexModel index();

        AuthState auth();

        /** A write that asks for the password again when the server wants it. */
        void write(String path, JsonObject body, Consumer<ApiResult> done);

        void failed(ApiResult r);

        boolean closed();
    }

    private static final long FAST_MS = 3_000, SLOW_MS = 15_000;

    private final SmClient client;
    private final Host host;

    private OrderModel.Listing listing = OrderModel.Listing.EMPTY;
    private DeliveryModel delivery = DeliveryModel.EMPTY;
    private String loadedKey = "";
    private String deliveryKey = "";
    private long nextOrders;
    private boolean ordersBusy, deliveryBusy, dirty;
    private long deliveryRetryAt, deliveryBackoff;
    /** Server time minus local time (ms), when known (the website reads it from the Date header). */
    private volatile long clockSkew;
    private int token;

    ServerOrderBackend(SmClient client, Host host) {
        this.client = client;
        this.host = host;
    }

    /** Forgets everything the server told (another stash, logout). */
    void clear() {
        listing = OrderModel.Listing.EMPTY;
        delivery = DeliveryModel.EMPTY;
        loadedKey = "";
        deliveryKey = "";
        ordersBusy = deliveryBusy = false;
        deliveryRetryAt = deliveryBackoff = 0;
        nextOrders = 0;
        token++;
        dirty = true;
    }

    /** Server time minus local time in ms (from the Date header of an answer), used for the force-cancel wait. 0 until known. */
    void setClockSkew(long skewMs) {
        this.clockSkew = skewMs;
    }

    /** Asks for the orders again at once. */
    void refreshNow() {
        nextOrders = 0;
    }

    private StashRef stash() {
        return host.stash();
    }

    @Override
    public String cartKey() {
        StashRef s = stash();
        return s == null ? "server|none" : "server|" + s.key();
    }

    @Override
    public String title() {
        return "Orders - the server's bots carry them out";
    }

    @Override
    public boolean localRun() {
        return false;
    }

    @Override
    public boolean canPlace() {
        AuthState a = host.auth();
        return a != null && OrderModel.canPlace(a);
    }

    @Override
    public IndexModel catalog() {
        return host.index();
    }

    @Override
    public long availableTotal(String type, String key) {
        Long server = listing.available(type, key);
        if (server != null) return server;
        IndexModel index = host.index();
        if (Order.SHULKER.equals(type)) {
            for (IndexModel.Shulker g : index.shulkers()) if (g.key().equals(key)) return g.count();
            return 0;
        }
        IndexModel.Item item = index.item(key);
        if (item == null) return 0;
        return item.isShulkerBox() ? index.emptyShulkerAvailable(key) : item.count();
    }

    @Override
    public DeliveryModel delivery() {
        return delivery;
    }

    @Override
    public BotInfo bot() {
        StashRef s = stash();
        return s == null ? BotInfo.NONE : s.bot();
    }

    @Override
    public boolean online() {
        StashRef s = stash();
        return s != null && s.online();
    }

    @Override
    public String blocker() {
        StashRef s = stash();
        if (s == null) return "";
        BotInfo b = s.bot();
        if (!b.orderBlocker().isEmpty()) return "The bot can't take orders right now: " + b.orderBlocker();
        if (!s.online()) return "The bot is offline. An order starts when it reconnects.";
        return "";
    }

    @Override
    public List<OrderModel.Entry> orders() {
        return listing.orders();
    }

    @Override
    public boolean canCancel(OrderModel.Entry e) {
        AuthState a = host.auth();
        return a != null && OrderModel.canCancel(e, a);
    }

    @Override
    public boolean canRetry(OrderModel.Entry e) {
        AuthState a = host.auth();
        return a != null && OrderModel.canRetry(e, a);
    }

    @Override
    public boolean canForceCancel(OrderModel.Entry e) {
        AuthState a = host.auth();
        return a != null && OrderModel.canForceCancel(e, a, online(), System.currentTimeMillis() + clockSkew);
    }

    @Override
    public boolean canClear() {
        AuthState a = host.auth();
        return a != null && OrderModel.canClear(a);
    }

    // ---------- reading ----------

    @Override
    public boolean tick(long now, boolean visible) {
        StashRef s = stash();
        AuthState a = host.auth();
        if (s == null || a == null || !a.can("view")) {
            if (!loadedKey.isEmpty()) clear();
            return take();
        }
        String key = s.key();
        if (!key.equals(loadedKey)) {
            clear();
            loadedKey = key;
        }
        if (now >= nextOrders && !ordersBusy) pollOrders(s, now, visible);
        pollDelivery(s, now);
        return take();
    }

    private boolean take() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    private void pollOrders(StashRef s, long now, boolean visible) {
        ordersBusy = true;
        nextOrders = now + (visible ? FAST_MS : SLOW_MS);
        int mine = token;
        Map<String, String> query = new LinkedHashMap<>();
        query.put("server", s.server());
        query.put("stash", s.stash());
        client.get("/api/v1/orders", query, null, false, SmClient.DEFAULT_TIMEOUT, r -> OrderModel.parse(r.body()), (r, parsed) -> {
            if (mine != token) return;
            ordersBusy = false;
            if (host.closed()) return;
            if (!r.ok()) {
                host.failed(r);
                return;
            }
            if (!sameOrders(listing, parsed)) dirty = true;
            listing = parsed;
        });
    }

    private static boolean sameOrders(OrderModel.Listing a, OrderModel.Listing b) {
        return a.orders().equals(b.orders()) && a.availableItems().equals(b.availableItems()) && a.availableShulkers().equals(b.availableShulkers());
    }

    private void pollDelivery(StashRef s, long now) {
        String want = s.bot().contentsKey();
        if (want.isEmpty()) {
            if (!deliveryKey.isEmpty() || !delivery.isEmpty()) {
                deliveryKey = "";
                delivery = DeliveryModel.EMPTY;
                dirty = true;
            }
            return;
        }
        if (want.equals(deliveryKey) || deliveryBusy || now < deliveryRetryAt) return;
        deliveryBusy = true;
        int mine = token;
        Map<String, String> query = new LinkedHashMap<>();
        query.put("server", s.server());
        query.put("stash", s.stash());
        client.get("/api/v1/delivery", query, null, false, SmClient.DEFAULT_TIMEOUT, r -> DeliveryModel.parse(r.body()), (r, parsed) -> {
            if (mine != token) return;
            deliveryBusy = false;
            if (host.closed()) return;
            if (!r.ok()) {
                // Not marked as loaded: asked again after 5 s, doubling to 60 s; the preview stays as it is meanwhile
                deliveryBackoff = DeliveryModel.retryDelay(deliveryBackoff);
                deliveryRetryAt = System.currentTimeMillis() + deliveryBackoff;
                return;
            }
            deliveryBackoff = 0;
            deliveryRetryAt = 0;
            deliveryKey = want;
            delivery = parsed;
            dirty = true;
        });
    }

    // ---------- writing ----------

    @Override
    public void place(List<Order.Line> lines, NamesDraft names, int units, Consumer<PlaceResult> done) {
        StashRef s = stash();
        if (s == null) {
            done.accept(new PlaceResult(false, "No stash is selected.", -1));
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("server", s.server());
        body.addProperty("stash", s.stash());
        JsonObject n = names.toJson(units);
        if (n != null) body.add("names", n);
        JsonArray array = new JsonArray();
        for (Order.Line l : lines) {
            JsonObject o = new JsonObject();
            o.addProperty("type", l.type());
            o.addProperty("key", l.key());
            o.addProperty("count", l.count());
            array.add(o);
        }
        body.add("lines", array);
        host.write("/api/v1/orders", body, r -> {
            if (host.closed()) return;
            refreshNow();
            if (r.ok()) {
                done.accept(new PlaceResult(true, s.online() ? "Order placed." : "Order placed. It will start when the bot reconnects.", -1));
                return;
            }
            int line = -1;
            if (r.obj().has("line") && r.obj().get("line").isJsonPrimitive() && r.obj().get("line").getAsJsonPrimitive().isNumber()) {
                line = r.obj().get("line").getAsInt();
            }
            done.accept(new PlaceResult(false, failure(r, "Could not place the order"), line));
        });
    }

    private static String failure(ApiResult r, String what) {
        return switch (r.kind()) {
            case RATE_LIMIT -> what + ": try again in " + r.wait429() + " s.";
            case NETWORK -> what + ": server unreachable.";
            case NO_PERMISSION -> what + ": your account can't do that.";
            default -> {
                String text = Text.sentence(Text.sanitize(r.error(), 160));
                yield text.isEmpty() ? what + " (HTTP " + r.status() + ")." : text;
            }
        };
    }

    private void act(String path, JsonObject body, String ok, String what, Consumer<String> done) {
        host.write(path, body, r -> {
            if (host.closed()) return;
            refreshNow();
            if (r.kind() == ApiError.Kind.LOGIN) {
                host.failed(r);
                return;
            }
            done.accept(r.ok() ? ok : failure(r, what));
        });
    }

    private static String path(String id, String action) {
        return "/api/v1/orders/" + com.porter.qwerte.manager.net.ServerUrl.seg(id) + "/" + action;
    }

    @Override
    public void cancel(OrderModel.Entry e, Consumer<String> done) {
        boolean running = !OrderModel.PENDING.equals(e.status());
        act(path(e.id(), "cancel"), new JsonObject(), running ? "Cancel requested." : "Order cancelled.", "Could not cancel", done);
    }

    @Override
    public void retry(OrderModel.Entry e, Consumer<String> done) {
        act(path(e.id(), "retry"), new JsonObject(), "Order placed again.", "Could not retry", done);
    }

    @Override
    public void forceCancel(OrderModel.Entry e, Consumer<String> done) {
        act(path(e.id(), "force-cancel"), new JsonObject(), "Order cancelled.", "Could not force cancel", done);
    }

    @Override
    public void clearFinished(Consumer<String> done) {
        StashRef s = stash();
        if (s == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("server", s.server());
        body.addProperty("stash", s.stash());
        host.write("/api/v1/orders/clear", body, r -> {
            if (host.closed()) return;
            refreshNow();
            if (r.ok()) {
                int n = r.obj().has("removed") && r.obj().get("removed").isJsonPrimitive() ? r.obj().get("removed").getAsInt() : 0;
                done.accept("Cleared " + n + (n == 1 ? " order." : " orders."));
            } else {
                done.accept(failure(r, "Could not clear the history"));
            }
        });
    }
}
