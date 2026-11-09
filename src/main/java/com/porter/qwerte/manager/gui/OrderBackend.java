package com.porter.qwerte.manager.gui;

import com.porter.qwerte.manager.model.BotInfo;
import com.porter.qwerte.manager.model.DeliveryModel;
import com.porter.qwerte.manager.model.IndexModel;
import com.porter.qwerte.manager.model.NamesDraft;
import com.porter.qwerte.manager.model.OrderModel;
import com.porter.qwerte.modules.LocalOrders;
import com.porter.qwerte.stash.Order;

import java.util.List;
import java.util.function.Consumer;

/**
 * Who carries out the orders of the Orders tab and where its data comes from: the bots of a Stash Manager server
 * ({@link ServerOrderBackend}) or this client itself ({@link LocalOrderBackend}). The tab is the same for both. Client thread
 * only.
 */
public interface OrderBackend {
    /** {@code badLine} is the index of the line the server refused, or -1. */
    record PlaceResult(boolean ok, String message, int badLine) {
    }

    /** Where the cart and names are kept: one per stash and per way of fulfilling. */
    String cartKey();

    String title();

    /** True when this client runs the orders (it shows the setup and a Stop). */
    boolean localRun();

    /** The account may place orders (always true for this client). */
    boolean canPlace();

    /** The index the cart is built from: keys, stack sizes and names. */
    IndexModel catalog();

    /** How many of a line can still be ordered, before the cart. */
    long availableTotal(String type, String key);

    DeliveryModel delivery();

    BotInfo bot();

    /** Whether the bot can be expected to start at once. */
    boolean online();

    /** Why an order can't be placed or started now, or "". */
    String blocker();

    /** The newest first. */
    List<OrderModel.Entry> orders();

    boolean canCancel(OrderModel.Entry e);

    boolean canRetry(OrderModel.Entry e);

    boolean canForceCancel(OrderModel.Entry e);

    boolean canClear();

    /** Places the cart's lines; {@code units} is the number of planned shulkers (for the names). */
    void place(List<Order.Line> lines, NamesDraft names, int units, boolean repairAll, Consumer<PlaceResult> done);

    /** Changes whenever what can still be ordered per item changed (so a picker is refilled only then). */
    default long availabilityVersion() {
        return 0;
    }

    /** Bottles o' Enchanting still free for repairs (the stash count, minus what open orders reserve); -1 when not known. */
    default long bottles() {
        return -1;
    }

    void cancel(OrderModel.Entry e, Consumer<String> done);

    void retry(OrderModel.Entry e, Consumer<String> done);

    void forceCancel(OrderModel.Entry e, Consumer<String> done);

    void clearFinished(Consumer<String> done);

    /** Called every frame while the window is up; {@code visible} is true on the Orders tab. True when something changed. */
    boolean tick(long now, boolean visible);

    /** The delivery container, anvil and table of this client's stash; null when the bots of a server run the orders. */
    default LocalOrders.Setup setup() {
        return null;
    }

    /** Turns on a right-click pick ("delivery", "anvil", "crafting-table"); null when on, else why not. */
    default String select(String what) {
        return "Not available.";
    }

    default boolean clearPick(String what) {
        return false;
    }

    /** An order of this client is running: the setup can't be changed now. */
    default boolean busy() {
        return false;
    }

    /** Something went wrong with the stored history, or "". */
    default String warning() {
        return "";
    }
}
