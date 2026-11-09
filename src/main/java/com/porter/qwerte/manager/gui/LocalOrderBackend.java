package com.porter.qwerte.manager.gui;

import com.google.gson.JsonParser;
import com.porter.qwerte.manager.model.BotInfo;
import com.porter.qwerte.manager.model.DeliveryModel;
import com.porter.qwerte.manager.model.IndexModel;
import com.porter.qwerte.manager.model.NamesDraft;
import com.porter.qwerte.manager.model.OrderModel;
import com.porter.qwerte.modules.LocalOrders;
import com.porter.qwerte.stash.Order;
import com.porter.qwerte.stash.RepairEstimate;
import com.porter.qwerte.stash.StashIndex;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Orders carried out by this client with the stash-sorter's engine, no server ({@link LocalOrders}). The cart is built from
 * the index of the sorter's own stash (the one that is read and emptied), and the delivery preview from what the sorter last
 * saw in the delivery chests; both are built the way the bot builds them for a server, then read by the same model classes
 * the server view uses. Client thread only.
 */
final class LocalOrderBackend implements OrderBackend {
    private static final long CHECK_MS = 1_000, INDEX_BUSY_MS = 15_000, DELIVERY_BUSY_MS = 5_000;

    private final LocalOrders orders;

    private IndexModel catalog = IndexModel.empty();
    private DeliveryModel delivery = DeliveryModel.EMPTY;
    private List<OrderModel.Entry> history = List.of();
    private long indexFingerprint, deliveryFingerprint;
    private long lastIndexCheck, lastDeliveryCheck, lastTick;
    private boolean indexBuilt, deliveryBuilt;
    private String builtFor = "";

    LocalOrderBackend(LocalOrders orders) {
        this.orders = orders;
    }

    @Override
    public String cartKey() {
        return "local|" + orders.stashName();
    }

    @Override
    public String title() {
        return "Orders - this client carries them out";
    }

    @Override
    public boolean localRun() {
        return true;
    }

    @Override
    public boolean canPlace() {
        return true;
    }

    @Override
    public IndexModel catalog() {
        refresh(System.currentTimeMillis(), false);
        return catalog;
    }

    @Override
    public long availableTotal(String type, String key) {
        IndexModel index = catalog();
        if (Order.SHULKER.equals(type)) {
            for (IndexModel.Shulker g : index.shulkers()) if (g.key().equals(key)) return g.count();
            return 0;
        }
        IndexModel.Item item = index.item(key);
        if (item == null) return 0;
        return item.isShulkerBox() ? index.emptyShulkerAvailable(key) : item.count();
    }

    @Override
    public long bottles() {
        IndexModel.Item item = catalog().item(RepairEstimate.BOTTLE_KEY);
        return item == null ? 0 : item.count();
    }

    @Override
    public DeliveryModel delivery() {
        refresh(System.currentTimeMillis(), false);
        return delivery;
    }

    @Override
    public BotInfo bot() {
        LocalOrders.Setup s = orders.setup();
        if (s == null) return BotInfo.NONE;
        StashIndex.DeliveryStorage st = orders.storage();
        boolean named = !s.anvilSource().isEmpty();
        return new BotInfo(true, s.anvil() != null, named ? s.anvilSource() : "", s.xpLevels(),
            true, s.craftingOn(), s.table() != null, s.tableSource(),
            st != null, st != null && st.autoExpand(), st == null ? 0 : st.chests(), st == null ? 0 : st.full(), st != null && st.canExpand(),
            st == null || st.blocked() == null ? "" : st.blocked(), "", s.delivery() != null, "");
    }

    @Override
    public boolean online() {
        return true;
    }

    @Override
    public String blocker() {
        String note = orders.note();
        if (!note.isEmpty()) return note;
        String why = orders.blocker();
        return why == null ? "" : why;
    }

    @Override
    public boolean busy() {
        return orders.active();
    }

    @Override
    public List<OrderModel.Entry> orders() {
        return history;
    }

    @Override
    public boolean canCancel(OrderModel.Entry e) {
        // Also after the order ended, while the leftovers are put back (the first cancel then waits for that, the second stops)
        return e.id().equals(orders.activeId());
    }

    @Override
    public boolean canRetry(OrderModel.Entry e) {
        return !orders.active() && (OrderModel.FAILED.equals(e.status()) || OrderModel.CANCELLED.equals(e.status())) && e.retriedAs().isEmpty();
    }

    @Override
    public boolean canForceCancel(OrderModel.Entry e) {
        return false;
    }

    @Override
    public boolean canClear() {
        return true;
    }

    // ---------- reading ----------

    /**
     * Rebuilds the index and the delivery preview when the stash changed. Looked at once a second; while an order runs the
     * index is rebuilt at most every 15 s and the delivery preview at most every 5 s (the stash changes all the time then).
     * Only called from the Orders tab and from adding to the cart, so nothing is rebuilt while neither is in use.
     */
    private void refresh(long now, boolean force) {
        if (mc.level == null || mc.player == null) return;
        boolean busy = orders.active();
        String stashNow = orders.stashName();
        boolean otherStash = !stashNow.equals(builtFor);
        if (force || otherStash || !indexBuilt || now - lastIndexCheck >= (busy ? INDEX_BUSY_MS : CHECK_MS)) {
            lastIndexCheck = now;
            long fp = orders.indexFingerprint();
            if (force || otherStash || !indexBuilt || fp != indexFingerprint) {
                indexFingerprint = fp;
                indexBuilt = true;
                builtFor = stashNow;
                try {
                    byte[] index = orders.indexJson();
                    catalog = index == null ? IndexModel.empty() : IndexModel.parse(JsonParser.parseString(new String(index, StandardCharsets.UTF_8)));
                } catch (RuntimeException e) {
                    catalog = IndexModel.empty();
                }
            }
        }
        if (force || otherStash || !deliveryBuilt || now - lastDeliveryCheck >= (busy ? DELIVERY_BUSY_MS : CHECK_MS)) {
            lastDeliveryCheck = now;
            long fp = orders.deliveryFingerprint();
            if (force || otherStash || !deliveryBuilt || fp != deliveryFingerprint) {
                deliveryFingerprint = fp;
                deliveryBuilt = true;
                try {
                    byte[] d = orders.deliveryJson();
                    delivery = d == null ? DeliveryModel.EMPTY : DeliveryModel.parse(JsonParser.parseString(new String(d, StandardCharsets.UTF_8)));
                } catch (RuntimeException e) {
                    delivery = DeliveryModel.EMPTY;
                }
            }
        }
    }

    @Override
    public boolean tick(long now, boolean visible) {
        if (now - lastTick < 250) return false;
        lastTick = now;
        long before = indexFingerprint * 31 + deliveryFingerprint;
        refresh(now, false);
        List<OrderModel.Entry> next = orders.history();
        boolean changed = before != indexFingerprint * 31 + deliveryFingerprint || !next.equals(history);
        history = next;
        return changed;
    }


    @Override
    public LocalOrders.Setup setup() {
        return orders.setup();
    }

    @Override
    public String select(String what) {
        return orders.select(what);
    }

    @Override
    public boolean clearPick(String what) {
        boolean ok = orders.clear(what);
        refresh(System.currentTimeMillis(), true);
        return ok;
    }

    @Override
    public String warning() {
        return orders.historyWarning();
    }

    // ---------- doing ----------

    @Override
    public void place(List<Order.Line> lines, NamesDraft names, int units, boolean repairAll, Consumer<PlaceResult> done) {
        IndexModel index = catalog();
        RepairEstimate.Estimate estimate = RepairEstimate.estimate(lines, repairAll, key -> {
            IndexModel.Item it = index.item(key);
            return it == null ? null : it.repairFacts();
        }, bottles());
        Order order = new Order(orders.newId(), List.copyOf(lines), names.toNames(units), repairAll, estimate);
        String why = orders.start(order);
        history = orders.history();
        done.accept(why == null ? new PlaceResult(true, "Order started on this client. Stay near the stash; Cancel stops it.", -1) : new PlaceResult(false, why, -1));
    }

    @Override
    public void cancel(OrderModel.Entry e, Consumer<String> done) {
        orders.cancel(null);
        history = orders.history();
        done.accept(orders.note().isEmpty() ? "Order stopped." : orders.note());
    }

    @Override
    public void retry(OrderModel.Entry e, Consumer<String> done) {
        String why = orders.retry(e);
        history = orders.history();
        done.accept(why == null ? "Order started again." : why);
    }

    @Override
    public void forceCancel(OrderModel.Entry e, Consumer<String> done) {
        done.accept("Not available.");
    }

    @Override
    public void clearFinished(Consumer<String> done) {
        int n = orders.clearFinished();
        history = orders.history();
        done.accept("Cleared " + n + (n == 1 ? " order." : " orders."));
    }
}
