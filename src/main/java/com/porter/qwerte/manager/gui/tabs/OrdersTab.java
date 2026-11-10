package com.porter.qwerte.manager.gui.tabs;

import com.porter.qwerte.manager.gui.ItemStacks;
import com.porter.qwerte.manager.gui.OrderBackend;
import com.porter.qwerte.manager.gui.chrome.WCreativeTabs;
import com.porter.qwerte.manager.gui.chrome.WDot;
import com.porter.qwerte.manager.gui.chrome.WTypeButton;
import com.porter.qwerte.manager.gui.style.PageRoom;
import com.porter.qwerte.manager.gui.style.SmButton;
import com.porter.qwerte.manager.gui.style.SmCard;
import com.porter.qwerte.manager.gui.style.SmColumns;
import com.porter.qwerte.manager.gui.style.SmGrid;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import com.porter.qwerte.manager.gui.style.SmPill;
import com.porter.qwerte.manager.gui.style.Ui;
import com.porter.qwerte.manager.gui.widgets.ItemTip;
import com.porter.qwerte.manager.gui.widgets.WItemGrid;
import com.porter.qwerte.manager.gui.widgets.WSlotGrid;
import com.porter.qwerte.manager.model.BotInfo;
import com.porter.qwerte.manager.model.Cart;
import com.porter.qwerte.manager.model.CartPlan;
import com.porter.qwerte.manager.model.CraftModel;
import com.porter.qwerte.manager.model.DeliveryModel;
import com.porter.qwerte.manager.model.IndexModel;
import com.porter.qwerte.manager.model.NamesDraft;
import com.porter.qwerte.manager.model.OrderMode;
import com.porter.qwerte.manager.model.OrderModel;
import com.porter.qwerte.manager.model.Text;
import com.porter.qwerte.modules.LocalOrders;
import com.porter.qwerte.stash.Order;
import com.porter.qwerte.stash.RepairEstimate;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Orders tab, laid out like the website's Orders page: the delivery strip, then two columns (the Order maker with the
 * item-type buttons, and the Cart with the delivery chest, the cart lines, the shulker names and the repair switch), then the
 * order history. It is the same for the bots of a server and for this client; the {@link OrderBackend} says which. It keeps no
 * data of its own beyond what is on screen: the cart and names live in the module (per stash), the orders in the backend.
 *
 * <p>Nothing is rebuilt on every poll: each part has a signature and is rebuilt only when what it shows changed, and the
 * parts that hold text boxes wait while one has the keyboard.
 */
public final class OrdersTab {
    /** What the tab needs from the window. */
    public interface Host {
        GuiGraphics graphics();

        /** Runs after the current event (safe for rebuilding the widget that sent it). */
        void later(Runnable r);

        void status(String text);

        /** Streamer display: no coordinates or user names. */
        boolean hideNames();

        /** Closes the window (to right-click a block in the world), saying {@code hint} in chat. */
        void closeWindow(String hint);

        Cart cart(String key);

        NamesDraft names(String key);

        OrderMode mode();

        void setMode(OrderMode mode);

        boolean offersModeChoice();
    }

    private static final long ARM_MS = 4_000;
    private static final int SHOWN_ORDERS = 30;
    private static final int PICKER_MAX = 3_000;
    private static final long SEARCH_DEBOUNCE_MS = 150;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d HH:mm");
    private static final int CHEST_GUI_W = 9 * 18 + 4;

    private final Host host;
    private OrderBackend backend;
    private GuiTheme theme;

    private WVerticalList header, cartCol, planCol, makerBox;
    private SmCard ordersCard, binCard;
    private WLabel cartCount;
    private WItemGrid picker;
    private WLabel pickerHint, pickerNote;
    private WTextBox searchBox;
    private WSlotGrid planGrid, binGrid;
    private WLabel fitLabel, warnLabel, namesWarn, cartStatus, repairInfo, repairWarn;

    private String searchText = "";
    private boolean searchDirty;
    private long searchAt;
    /** The picked item type of the Order maker (a creative tab id, or "shulkers"); null shows the type buttons. */
    private String pickType;
    private String makerSig = "";
    private int selectedUnit = -1;
    private int badLine = -1;
    private boolean placing;
    private String armedKey = "";
    private long armedUntil;
    private String ordersSignature = "";
    private String headerSignature = "";
    private double leftInner, rightInner, bodyW;

    private IndexModel groupsFor;
    private final Map<String, IndexModel.Shulker> groups = new HashMap<>();
    private List<CartPlan.Unit> units = List.of();
    private DeliveryModel.Layout layout;

    public OrdersTab(Host host) {
        this.host = host;
    }

    public void setBackend(OrderBackend backend) {
        this.backend = backend;
    }

    public OrderBackend backend() {
        return backend;
    }

    // ---------- adding from other tabs ----------

    /** True when items can be put in the cart (the account may order and a stash is known). */
    public boolean cartEnabled() {
        return backend != null && backend.canPlace();
    }

    /**
     * Puts one stack (or a shulker's worth) of an index key in the cart.
     * @return "" when added, else what to tell the user
     */
    public String addToCart(String type, String key, boolean shulkerWorth) {
        if (!cartEnabled()) return "Your account can't place orders.";
        IndexModel idx = backend.catalog();
        if (type.equals(Order.ITEM) && idx.item(key) == null) return "That item is not in the stash this order would be taken from.";
        if (type.equals(Order.SHULKER) && findGroup(idx, key) == null) return "That shulker is not in the stash this order would be taken from.";
        Cart cart = host.cart(backend.cartKey());
        Cart.Result r = cart.addStacks(type, key, shulkerWorth, idx, backend::availableTotal);
        if (!r.ok()) return r.message();
        badLine = -1;
        if (cartCol != null) host.later(this::onCartChanged);
        return "";
    }

    // ---------- building ----------

    public void build(WContainer parent, GuiTheme theme) {
        this.theme = theme;
        SmGuiTheme st = (SmGuiTheme) theme;
        header = null;
        cartCol = planCol = makerBox = null;
        ordersCard = binCard = null;
        cartCount = null;
        picker = null;
        pickerHint = pickerNote = null;
        searchBox = null;
        planGrid = binGrid = null;
        fitLabel = warnLabel = namesWarn = cartStatus = repairInfo = repairWarn = null;
        makerSig = "";
        headerSignature = "";
        ordersSignature = "";
        cartBoxes.clear();

        Ui.pageTitle(parent, theme, "orders");
        SmCard strip = Ui.card(parent, "");
        strip.padding = 6;
        header = strip.add(theme.verticalList()).expandX().widget();
        header.spacing = 4;

        bodyW = PageRoom.body(parent);
        double gap = theme.scale(10);
        double half = (bodyW - gap) / 2;
        leftInner = half - theme.scale(16);
        rightInner = half - theme.scale(16);
        if (backend.canPlace()) {
            SmColumns cols = parent.add(new SmColumns()).expandX().widget();
            cols.maxWidth = bodyW;
            cols.gap = 10;
            cols.stretch = true;
            cols.weights(1, 1);
            SmCard maker = cols.add(new SmCard("Order maker")).widget();
            makerBox = maker.add(theme.verticalList()).expandX().widget();
            makerBox.spacing = 6;
            SmCard cart = cols.add(new SmCard("Cart")).widget();
            cartCount = cart.addRight(theme.label("", true)).widget();
            double chestW = st.g(CHEST_GUI_W);
            boolean sideBySide = rightInner >= chestW + theme.scale(12) + theme.scale(250);
            planCol = new WVerticalList();
            cartCol = new WVerticalList();
            planCol.spacing = 6;
            cartCol.spacing = 6;
            if (sideBySide) {
                SmColumns inner = cart.add(new SmColumns()).expandX().widget();
                inner.maxWidth = rightInner;
                inner.gap = 12;
                inner.weights(chestW, rightInner - chestW - theme.scale(12));
                inner.add(planCol);
                inner.add(cartCol);
            } else {
                cart.add(planCol).expandX();
                cart.add(cartCol).expandX();
            }
        }
        ordersCard = Ui.card(parent, "Orders");
        refreshAll();
    }

    private void refreshAll() {
        buildHeader();
        buildMaker();
        onCartChanged();
        buildOrders();
    }

    /** Every frame: applies a typed search, expires the confirm click, and redraws what the backend changed. */
    public void tick(long now, boolean changed) {
        if (header == null) return;
        if (searchDirty && now - searchAt >= SEARCH_DEBOUNCE_MS) {
            searchDirty = false;
            refreshPicker(true);
        }
        if (!armedKey.isEmpty() && now > armedUntil) {
            // The cart's Clear button shows "Confirm clear?": it is drawn again with the cart
            if (armedKey.equals("cart")) cartDirty = true;
            armedKey = "";
            ordersSignature = "";
            buildOrders();
        }
        if (changed) {
            // Only what changed is redrawn. The cart column holds text boxes, so it is rebuilt only when what it shows changed
            // and none of its boxes has the keyboard (held back while one has focus); the order list and the preview have none
            buildHeader();
            if (backend.canPlace() && !makerSignature().equals(makerSig) && !(searchBox != null && searchBox.isFocused())) buildMaker();
            else refreshPicker(false);
            buildOrders();
            recomputeUnits();
            refreshPlan();
            refreshRepair();
            if (namesWarn != null) updateNamesWarn(host.names(backend.cartKey()));
            if (!cartSignature().equals(cartSig)) cartDirty = true;
        }
        if (cartDirty && cartCol != null && !cartBoxFocused()) {
            cartDirty = false;
            onCartChanged();
        }
    }

    private String cartSig = "";
    private boolean cartDirty;

    /** Everything the cart column shows except the text typed in it. */
    private String cartSignature() {
        if (!backend.canPlace()) return "-";
        IndexModel idx = backend.catalog();
        StringBuilder sb = new StringBuilder();
        Cart cart = host.cart(backend.cartKey());
        for (Order.Line l : cart.lines()) sb.append(l.type()).append(l.key()).append(l.count()).append(l.repair()).append(lineName(l, idx)).append(';');
        sb.append(cart.repairAll()).append(backend.bot().repair()).append(host.names(backend.cartKey()).mode()).append(units.size()).append(badLine).append(backend.online());
        return sb.toString();
    }

    private void recomputeUnits() {
        if (!backend.canPlace()) {
            units = List.of();
            return;
        }
        List<CartPlan.Unit> plan = CartPlan.plan(host.cart(backend.cartKey()).lines(), backend.catalog());
        units = plan == null ? List.of() : plan;
        if (cartCount != null) {
            long need = CartPlan.shulkersNeeded(host.cart(backend.cartKey()).lines(), backend.catalog());
            cartCount.set(need + " / " + CartPlan.MAX_SHULKERS + " shulkers");
        }
    }

    // ---------- header ----------

    private String deliveryText(LocalOrders.Setup setup, BotInfo bot) {
        if (backend.localRun()) {
            int[] d = setup == null ? null : setup.delivery();
            if (d == null) return null;
            return host.hideNames() ? "set" : d[0] + ", " + d[1] + ", " + d[2];
        }
        if (!bot.hasDelivery() && bot.deliveryAt().isEmpty()) return null;
        return host.hideNames() ? "set" : bot.deliveryAt().isEmpty() ? "set" : bot.deliveryAt();
    }

    private void buildHeader() {
        BotInfo bot = backend.bot();
        LocalOrders.Setup setup = backend.setup();
        String sig = backend.title() + "|" + backend.blocker() + "|" + bot + "|" + backend.warning() + "|" + host.mode() + "|" + host.hideNames() + "|" + backend.busy() + "|"
            + (setup == null ? "-" : setup.stash() + java.util.Arrays.toString(setup.delivery()) + java.util.Arrays.toString(setup.anvil()) + java.util.Arrays.toString(setup.table())
                + setup.anvilSource() + setup.tableSource() + setup.craftingOn() + setup.xpLevels());
        if (sig.equals(headerSignature)) return;
        headerSignature = sig;
        header.clear();
        double wrap = bodyW - theme.scale(12 + 16);
        SmGuiTheme st = (SmGuiTheme) theme;
        String where = deliveryText(setup, bot);
        WHorizontalList line = header.add(theme.horizontalList()).expandX().widget();
        line.add(new WDot(where != null ? st.palette().okFg() : st.palette().warnFg())).widget();
        line.add(theme.label("Delivery container: " + (where == null ? "not set" : where), true));
        header.add(Ui.muted(theme, backend.title()));
        if (host.offersModeChoice()) {
            WHorizontalList row = header.add(theme.horizontalList()).widget();
            row.add(theme.label("Who carries out new orders: "));
            WDropdown<OrderMode> mode = row.add(theme.dropdown(host.mode())).widget();
            mode.action = () -> {
                OrderMode picked = mode.get();
                host.later(() -> host.setMode(picked));
            };
        }
        if (!backend.canPlace()) header.add(Ui.wrap(theme, "Your account can't place orders. You can see the orders of this stash.", wrap)).expandX();
        String blocker = backend.blocker();
        if (!blocker.isEmpty()) header.add(Ui.wrap(theme, Text.sanitize(blocker, 200), wrap)).expandX().widget().color(Ui.warn(theme));
        String info = bot.craftInfo();
        if (!info.isEmpty()) header.add(Ui.muted(theme, info, wrap)).expandX();
        String warning = backend.warning();
        if (!warning.isEmpty()) header.add(Ui.wrap(theme, warning, wrap)).expandX().widget().color(Ui.err(theme));
        if (backend.localRun()) buildSetup(setup, wrap);
    }

    private void buildSetup(LocalOrders.Setup setup, double wrap) {
        if (setup == null) {
            header.add(Ui.wrap(theme, "No stash is loaded. Set stash-name in stash-sorter and scan the stash first.", wrap)).expandX().widget().color(Ui.warn(theme));
            return;
        }
        header.add(Ui.muted(theme, "Stash: " + (host.hideNames() ? "(hidden)" : Text.sanitize(setup.stash(), 40)) + ". The order is taken from it and delivered to its delivery container.", wrap)).expandX();
        pickRow("Delivery container", setup.delivery(), "delivery", "");
        pickRow("Anvil (to name shulkers)", setup.anvil(), "anvil", setup.anvilSource().isEmpty() || setup.anvilSource().equals("none") ? "" : setup.anvilSource());
        pickRow("Crafting table", setup.table(), "crafting-table", setup.tableSource().isEmpty() || setup.tableSource().equals("none") ? "" : setup.tableSource());
        header.add(Ui.muted(theme, "Crafting empty shulkers is " + (setup.craftingOn() ? "on" : "off") + ", and shulkers with a custom name are "
            + (setup.namedShulkers() ? "used" : "left alone") + " (stash-manager settings: enable-crafting, craft-never-use, use-named-shulkers, auto-expand-delivery).", wrap)).expandX();
    }

    private void pickRow(String name, int[] pos, String what, String source) {
        WHorizontalList row = header.add(theme.horizontalList()).expandX().widget();
        String where = pos == null ? "not set" : host.hideNames() ? "set" : pos[0] + " " + pos[1] + " " + pos[2];
        row.add(theme.label(name + ": " + where + (source.isEmpty() || pos == null ? "" : " (" + source + ")"))).expandX();
        // Not while an order of this client runs: the sorter is using these
        if (backend.busy()) return;
        SmButton set = (SmButton) row.add(theme.button(pos == null ? "Set" : "Change")).widget();
        set.pill = true;
        set.tooltip = "Closes the window; then right-click the block in the world. Right-click the current one to clear it.";
        set.action = () -> host.later(() -> {
            String err = backend.select(what);
            if (err != null) host.status(err);
            else host.closeWindow("Right-click the " + name.toLowerCase(java.util.Locale.ROOT).replace(" (to name shulkers)", "") + " now.");
        });
        if (pos != null) {
            SmButton clear = (SmButton) row.add(theme.button("Clear")).widget();
            clear.pill = true;
            clear.action = () -> host.later(() -> {
                headerSignature = "";
                host.status(backend.clearPick(what) ? name + " cleared." : "Nothing to clear.");
                buildHeader();
            });
        }
    }

    // ---------- order maker ----------

    private String makerSignature() {
        IndexModel idx = backend.catalog();
        return idx.visibleTabs() + "|" + idx.items().isEmpty() + "|" + pickType + "|" + backend.localRun();
    }

    /** The item-type buttons (the website's picker level one) or, once a type is picked, its items. */
    private void buildMaker() {
        if (makerBox == null) return;
        makerBox.clear();
        picker = null;
        pickerHint = pickerNote = null;
        searchBox = null;
        makerSig = makerSignature();
        pickerKey = "";
        IndexModel idx = backend.catalog();
        if (idx.items().isEmpty() && idx.shulkers().isEmpty()) {
            makerBox.add(Ui.muted(theme, backend.localRun() ? "The sorter's stash has nothing indexed yet." : "No index for this stash yet.", leftInner)).expandX();
            return;
        }
        List<String> types = new ArrayList<>();
        for (String t : idx.visibleTabs()) if (!IndexModel.TAB_ALL.equals(t) && !IndexModel.TAB_ORDERS.equals(t)) types.add(t);
        if (pickType != null && !types.contains(pickType)) pickType = null;
        if (pickType == null) {
            makerBox.add(theme.label("Item type", true));
            SmGrid grid = makerBox.add(new SmGrid()).expandX().widget();
            grid.columns = 2;
            grid.gapX = 6;
            grid.gapY = 6;
            grid.maxWidth = leftInner;
            for (String t : types) {
                String name = IndexModel.TAB_SHULKERS.equals(t) ? "Shulker boxes (packed)" : WCreativeTabs.nameOf(t);
                WTypeButton b = grid.add(new WTypeButton(host::graphics, WCreativeTabs.iconOf(t), name)).widget();
                b.action = () -> host.later(() -> {
                    pickType = t;
                    searchText = "";
                    buildMaker();
                });
            }
        } else {
            WHorizontalList crumbs = makerBox.add(theme.horizontalList()).expandX().widget();
            SmButton back = (SmButton) crumbs.add(theme.button("Item type")).widget();
            back.pill = true;
            back.action = () -> host.later(() -> {
                pickType = null;
                buildMaker();
            });
            crumbs.add(Ui.muted(theme, " > "));
            crumbs.add(theme.label(IndexModel.TAB_SHULKERS.equals(pickType) ? "Shulker boxes (packed)" : WCreativeTabs.nameOf(pickType), true));
            searchBox = makerBox.add(theme.textBox(searchText, "Filter")).expandX().widget();
            searchBox.action = () -> {
                searchText = searchBox.get();
                searchDirty = true;
                searchAt = System.currentTimeMillis();
            };
            picker = makerBox.add(new WItemGrid(host::graphics)).widget();
            int u = SmGuiTheme.unitOf(theme);
            picker.setViewport(Math.max(60, (int) (leftInner / u)), 9 * 18 + 4);
            picker.tipFor = cell -> cell.ref() instanceof IndexModel.Entry en
                ? ItemTip.lines(backend.catalog(), en, backend.availableTotal(en.item() != null ? Order.ITEM : Order.SHULKER, en.key())) : null;
            picker.onCell = (cell, button) -> {
                if (!(cell.ref() instanceof IndexModel.Entry e)) return;
                String type = e.item() != null ? Order.ITEM : Order.SHULKER;
                String msg = addToCart(type, e.key(), button == 1);
                host.status(msg);
            };
            pickerNote = makerBox.add(Ui.muted(theme, "", leftInner)).expandX().widget();
            refreshPicker(true);
        }
        makerBox.add(Ui.muted(theme, "Click an item: +1 stack. Right-click: +1 shulker of it. Change the amount in the cart.", leftInner)).expandX();
    }

    /** What the picker's cells were made from: refilled only when it changes (up to 3000 cells). */
    private String pickerKey = "";

    private void refreshPicker(boolean force) {
        if (picker == null || pickType == null) return;
        IndexModel idx = backend.catalog();
        String key = System.identityHashCode(idx) + "|" + backend.availabilityVersion() + "|" + pickType + "|" + searchText;
        if (!force && key.equals(pickerKey)) return;
        pickerKey = key;
        boolean packed = IndexModel.TAB_SHULKERS.equals(pickType);
        List<IndexModel.Entry> entries = idx.view(searchText, packed ? IndexModel.Sort.COUNT : IndexModel.Sort.CREATIVE, false, pickType);
        List<WItemGrid.Cell> cells = new ArrayList<>();
        for (IndexModel.Entry e : entries) {
            if (cells.size() >= PICKER_MAX) break;
            long avail = backend.availableTotal(e.item() != null ? Order.ITEM : Order.SHULKER, e.key());
            if (avail < 1) continue;
            ItemStack stack = e.item() != null ? ItemStacks.display(e.item()) : ItemStacks.display(e.shulker());
            cells.add(new WItemGrid.Cell(stack, e.count() == 1 ? "" : Text.compact(avail), e.name() + " x" + Text.grouped(avail) + " available", e));
        }
        picker.setCells(cells);
        if (pickerNote != null) pickerNote.set(cells.isEmpty() ? "Nothing here can be ordered." : "");
    }

    // ---------- cart ----------

    private final List<WTextBox> cartBoxes = new ArrayList<>();

    /** One of the cart's text boxes has the keyboard: the cart is not rebuilt under the cursor. */
    private boolean cartBoxFocused() {
        for (WTextBox b : cartBoxes) if (b.isFocused()) return true;
        return false;
    }

    private void onCartChanged() {
        cartDirty = false;
        recomputeUnits();
        cartSig = cartSignature();
        buildCart();
        buildPlanColumn();
    }

    private IndexModel.Shulker findGroup(IndexModel idx, String key) {
        if (groupsFor != idx) {
            groups.clear();
            for (IndexModel.Shulker g : idx.shulkers()) groups.put(g.key(), g);
            groupsFor = idx;
        }
        return groups.get(key);
    }

    private String lineName(Order.Line l, IndexModel idx) {
        if (Order.SHULKER.equals(l.type())) {
            IndexModel.Shulker g = findGroup(idx, l.key());
            return g == null ? Text.sanitize(l.key(), 40) + " (no longer in the stash)" : g.name() + " (packed)";
        }
        IndexModel.Item it = idx.item(l.key());
        if (it == null) return Text.sanitize(l.key(), 40) + " (no longer in the stash)";
        return it.isShulkerBox() ? "Empty " + it.name() : it.name();
    }

    private SmButton seg(WContainer row, String text, boolean active) {
        SmButton b = (SmButton) row.add(theme.button(text)).widget();
        b.pill = true;
        b.primary = active;
        return b;
    }

    private void buildCart() {
        if (cartCol == null) return;
        cartCol.clear();
        cartBoxes.clear();
        namesWarn = null;
        cartStatus = null;
        repairInfo = null;
        repairWarn = null;
        if (!backend.canPlace()) return;
        IndexModel idx = backend.catalog();
        Cart cart = host.cart(backend.cartKey());
        NamesDraft names = host.names(backend.cartKey());
        double wrap = Math.max(theme.scale(180), rightInner - ((SmGuiTheme) theme).g(CHEST_GUI_W) - theme.scale(12));
        if (wrap > rightInner) wrap = rightInner;
        BotInfo bot = backend.bot();
        boolean canRepair = bot.repair();

        List<Order.Line> lines = cart.lines();
        if (cart.isEmpty()) cartCol.add(Ui.muted(theme, "The cart is empty. Pick an item type on the left, or right-click items in Stash.", wrap)).expandX();
        long packed = 0, empties = 0, items = 0;
        for (int i = 0; i < lines.size(); i++) {
            Order.Line l = lines.get(i);
            int index = i;
            WHorizontalList row = cartCol.add(theme.horizontalList()).expandX().widget();
            WLabel name = row.add(theme.label(Text.sanitize(lineName(l, idx), 36))).expandX().widget();
            if (i == badLine) name.color(Ui.err(theme));
            WTextBox qty = row.add(theme.textBox(String.valueOf(l.count()), "1", (t, c) -> Character.isDigit(c))).minWidth(theme.scale(70)).widget();
            cartBoxes.add(qty);
            qty.actionOnUnfocused = () -> {
                long wanted;
                try {
                    wanted = Long.parseLong(qty.get().trim());
                } catch (NumberFormatException e) {
                    wanted = l.count();
                }
                Cart.Result r = cart.setCount(index, wanted, idx, backend::availableTotal);
                host.status(r.message());
                badLine = -1;
                host.later(this::onCartChanged);
            };
            SmButton remove = (SmButton) row.add(theme.button("x")).widget();
            remove.pill = true;
            remove.tooltip = "Remove this line";
            remove.action = () -> {
                cart.remove(index);
                badLine = -1;
                host.later(this::onCartChanged);
            };
            if (canRepair && Order.ITEM.equals(l.type())) repairLine(row, cart, index, l, idx);
            if (Order.SHULKER.equals(l.type())) packed += l.count();
            else if (CartPlan.isEmptyShulkerLine(l, idx)) empties += l.count();
            else items += l.count();
        }

        long need = CartPlan.shulkersNeeded(lines, idx);
        boolean tooMany = need > CartPlan.MAX_SHULKERS;
        if (!lines.isEmpty()) {
            List<String> parts = new ArrayList<>();
            if (items > 0) parts.add(Text.grouped(items) + (items == 1 ? " item" : " items"));
            if (packed > 0) parts.add(packed + (packed == 1 ? " packed shulker" : " packed shulkers"));
            if (empties > 0) parts.add(empties + (empties == 1 ? " empty shulker" : " empty shulkers"));
            String totals = String.join(" + ", parts) + " - " + need + (need == 1 ? " shulker" : " shulkers") + " to deliver"
                + (tooMany ? ". Too many: one order can fill at most " + CartPlan.MAX_SHULKERS + " (one double chest)." : "");
            WLabel t = cartCol.add(Ui.muted(theme, totals, wrap)).expandX().widget();
            if (tooMany) t.color(Ui.warn(theme));
        }

        // Names, like the website: none, one for all, one for each planned shulker
        if (!lines.isEmpty()) {
            SmCard box = cartCol.add(new SmCard("Shulker names")).expandX().widget();
            box.padding = 6;
            box.spacing = 6;
            box.quietTitle = true;
            WHorizontalList nameRow = box.add(theme.horizontalList()).widget();
            for (NamesDraft.Mode m : NamesDraft.Mode.values()) {
                SmButton b = seg(nameRow, m == NamesDraft.Mode.NONE ? "No names" : m == NamesDraft.Mode.ALL ? "Same name for all" : "Name each shulker", names.mode() == m);
                b.action = () -> {
                    names.setMode(m);
                    host.later(this::onCartChanged);
                };
            }
            if (names.mode() == NamesDraft.Mode.ALL) {
                WTextBox all = box.add(theme.textBox(names.all(), "Name for every shulker (up to 50 characters)")).expandX().widget();
                cartBoxes.add(all);
                all.action = () -> {
                    names.setAll(all.get());
                    updateNamesWarn(names);
                };
            } else if (names.mode() == NamesDraft.Mode.EACH) {
                for (int i = 0; i < units.size(); i++) {
                    int index = i;
                    WHorizontalList row = box.add(theme.horizontalList()).expandX().widget();
                    row.add(Ui.muted(theme, "Shulker " + (i + 1) + ": " + Text.sanitize(unitSummary(units.get(i), idx), 40))).expandX();
                    WTextBox each = row.add(theme.textBox(names.each(i), "unnamed")).minWidth(theme.scale(140)).widget();
                    cartBoxes.add(each);
                    each.action = () -> {
                        names.setEach(index, each.get());
                        updateNamesWarn(names);
                    };
                }
            }
            namesWarn = box.add(Ui.muted(theme, "", wrap - theme.scale(16))).expandX().widget();
            updateNamesWarn(names);
        }

        if (canRepair && !lines.isEmpty()) buildRepairBox(cart, wrap);
        else if (!lines.isEmpty()) {
            boolean damaged = false;
            for (Order.Line l : lines) {
                IndexModel.Item it = Order.ITEM.equals(l.type()) ? idx.item(l.key()) : null;
                if (it != null && it.damage() > 0) damaged = true;
            }
            if (damaged) cartCol.add(Ui.muted(theme, bot.repairWarning(true), wrap)).expandX();
        }

        cartStatus = cartCol.add(Ui.muted(theme, "", wrap)).expandX().widget();
        cartStatus.color(Ui.warn(theme));
        if (!lines.isEmpty()) {
            WHorizontalList buttons = cartCol.add(theme.horizontalList()).widget();
            SmButton place = (SmButton) buttons.add(((SmGuiTheme) theme).primaryButton(backend.localRun() ? "Start order here" : "Place order")).widget();
            place.action = () -> host.later(() -> place(cart, names));
            SmButton clear = (SmButton) buttons.add(theme.button(armed("cart") ? "Confirm clear?" : "Clear cart")).widget();
            clear.danger = true;
            clear.action = () -> host.later(() -> {
                if (armOrRun("cart", clear, "Confirm clear?", "Clear cart")) {
                    cart.clear();
                    names.reset();
                    badLine = -1;
                    onCartChanged();
                }
            });
            if (!backend.localRun() && !backend.online()) {
                cartCol.add(Ui.muted(theme, "Bot offline. The order will start when it reconnects.", wrap)).expandX().widget().color(Ui.warn(theme));
            }
        }
        refreshRepair();
    }

    // ---------- repair ----------

    /** The per-line Repair toggle of an item line that can be repaired, or why it can't be when it is damaged. */
    private void repairLine(WHorizontalList row, Cart cart, int index, Order.Line l, IndexModel idx) {
        IndexModel.Item it = idx.item(l.key());
        if (it == null) return;
        if (it.repairable()) {
            WCheckbox c = row.add(theme.checkbox(l.repair())).widget();
            c.tooltip = "Repair this item with Bottles o' Enchanting from the stash (about " + it.repairBottles() + " per item)";
            c.action = () -> {
                cart.setRepair(index, c.checked);
                cartSig = cartSignature();
                host.later(this::refreshRepair);
            };
            row.add(Ui.muted(theme, "Repair"));
        } else if (it.damage() > 0) {
            String why = RepairEstimate.reason(l.key(), it.repairFacts());
            row.add(Ui.muted(theme, "can't repair (" + (why == null ? "no Mending" : why) + ")"));
        }
    }

    /** v1.2: under the cart-wide switch only damaged lines are wanted for repair. */
    private static boolean wantedRepair(Order.Line l, boolean repairAll, IndexModel idx) {
        IndexModel.Item it = Order.ITEM.equals(l.type()) ? idx.item(l.key()) : null;
        return RepairEstimate.wanted(l, repairAll, it == null ? null : it.repairFacts());
    }

    private void buildRepairBox(Cart cart, double wrap) {
        SmCard box = cartCol.add(new SmCard("Repair")).expandX().widget();
        box.padding = 6;
        box.spacing = 6;
        box.quietTitle = true;
        WHorizontalList row = box.add(theme.horizontalList()).widget();
        WCheckbox all = row.add(theme.checkbox(cart.repairAll())).widget();
        all.action = () -> {
            cart.setRepairAll(all.checked);
            cartSig = cartSignature();
            host.later(this::refreshRepair);
        };
        row.add(theme.label("Repair all items"));
        box.add(Ui.muted(theme, "Only items with Mending are repaired, using Bottles o' Enchanting from the stash. Tick \"Repair\" on a single line to repair just that item.", wrap - theme.scale(16))).expandX();
        repairInfo = box.add(Ui.muted(theme, "", wrap - theme.scale(16))).expandX().widget();
        repairWarn = box.add(Ui.muted(theme, "", wrap - theme.scale(16))).expandX().widget();
    }

    /** The live estimate under the Repair switch: bottles needed against what the stash has, and a warning that never blocks. */
    private void refreshRepair() {
        if (repairInfo == null || repairWarn == null || backend == null) return;
        IndexModel idx = backend.catalog();
        Cart cart = host.cart(backend.cartKey());
        RepairEstimate.Estimate est = RepairEstimate.estimate(cart.lines(), cart.repairAll(), key -> {
            IndexModel.Item it = idx.item(key);
            return it == null ? null : it.repairFacts();
        }, backend.bottles());
        if (est == null) {
            repairInfo.set("");
            repairWarn.set("");
            return;
        }
        int cant = 0;
        for (RepairEstimate.LineEstimate le : est.lines()) if (!le.repairable()) cant++;
        boolean unknown = backend.bottles() < 0;
        repairInfo.set("About " + Text.grouped(est.needed()) + (est.needed() == 1 ? " bottle" : " bottles") + " needed, "
            + (unknown ? "bottles in stash: unknown." : Text.grouped(est.available()) + " in stash.")
            + (cant > 0 ? " " + cant + (cant == 1 ? " line can't be repaired." : " lines can't be repaired.") : ""));
        repairWarn.color(Ui.warn(theme));
        repairWarn.set(!unknown && est.isShort() ? "Not enough bottles: some items will arrive not fully repaired. The order is still placed." : "");
    }

    private void updateNamesWarn(NamesDraft names) {
        if (namesWarn == null) return;
        String problem = names.problem(units.size());
        namesWarn.set(problem.isEmpty() ? backend.bot().namesWarning(names.namedCount(units.size())) : problem);
        namesWarn.color(problem.isEmpty() ? Ui.warn(theme) : Ui.err(theme));
    }

    private void place(Cart cart, NamesDraft names) {
        if (placing || cart.isEmpty()) return;
        IndexModel idx = backend.catalog();
        List<CartPlan.Unit> plan = CartPlan.plan(cart.lines(), idx);
        if (plan == null || plan.isEmpty()) {
            say(Cart.capMessage(CartPlan.shulkersNeeded(cart.lines(), idx), CartPlan.MAX_SHULKERS));
            return;
        }
        String problem = names.problem(plan.size());
        if (!problem.isEmpty()) {
            say(problem);
            return;
        }
        placing = true;
        say("Placing the order...");
        boolean repairAll = cart.repairAll() && backend.bot().repair();
        List<Order.Line> sent = cart.lines();
        if (!backend.bot().repair()) {
            // A bot that can't repair is not asked to: the toggles are hidden, so nothing is sent
            List<Order.Line> plain = new ArrayList<>();
            for (Order.Line l : sent) plain.add(new Order.Line(l.type(), l.key(), l.count(), false));
            sent = plain;
        }
        backend.place(sent, names, plan.size(), repairAll, r -> {
            placing = false;
            if (r.ok()) {
                cart.clear();
                names.reset();
                badLine = -1;
                host.status(r.message());
                ordersSignature = "";
                headerSignature = "";
                host.later(this::refreshAll);
            } else {
                badLine = r.badLine();
                host.status(r.message());
                host.later(this::onCartChanged);
            }
        });
    }

    private void say(String text) {
        if (cartStatus != null) cartStatus.set(text);
        host.status(text);
    }

    private static String unitSummary(CartPlan.Unit u, IndexModel idx) {
        switch (u.kind()) {
            case SHULKER -> {
                for (IndexModel.Shulker g : idx.shulkers()) if (g.key().equals(u.key())) return "Packed: " + g.name();
                return "Packed shulker";
            }
            case EMPTY -> {
                IndexModel.Item it = idx.item(u.key());
                return "Empty " + (it == null ? "shulker" : it.name());
            }
            default -> {
                Map<String, Long> totals = new java.util.LinkedHashMap<>();
                for (Order.Lot lot : u.lots()) totals.merge(lot.key(), (long) lot.count(), Long::sum);
                List<Map.Entry<String, Long>> top = new ArrayList<>(totals.entrySet());
                top.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(3, top.size()); i++) {
                    IndexModel.Item it = idx.item(top.get(i).getKey());
                    if (i > 0) sb.append(", ");
                    sb.append(it == null ? Text.sanitize(top.get(i).getKey(), 30) : it.name()).append(" x").append(Text.grouped(top.get(i).getValue()));
                }
                if (top.size() > 3) sb.append(", +").append(top.size() - 3).append(" more");
                return sb.toString();
            }
        }
    }

    // ---------- the delivery chest preview ----------

    private void buildPlanColumn() {
        if (planCol == null) return;
        planCol.clear();
        planGrid = null;
        binGrid = null;
        binCard = null;
        fitLabel = warnLabel = null;
        if (!backend.canPlace()) return;
        double wrap = Math.min(rightInner, ((SmGuiTheme) theme).g(CHEST_GUI_W) + theme.scale(16)) - theme.scale(12);
        SmCard chest = planCol.add(new SmCard("Delivery chest (planned layout)")).expandX().widget();
        chest.padding = 6;
        chest.spacing = 4;
        planGrid = chest.add(new WSlotGrid(host::graphics, DeliveryModel.GRID)).widget();
        planGrid.onSlot = (slot, button) -> {
            if (layout == null || slot < 0 || slot >= layout.slotUnit().length) return;
            int ui = layout.slotUnit()[slot];
            if (ui < 0 || ui >= units.size()) return;
            CartPlan.Unit u = units.get(ui);
            if (button == 1 && u.kind() != CartPlan.Kind.BIN) {
                host.cart(backend.cartKey()).removeAmount(u.kind() == CartPlan.Kind.SHULKER ? Order.SHULKER : Order.ITEM, u.key(), 1);
                host.later(this::onCartChanged);
            } else {
                selectedUnit = ui;
                refreshPlan();
            }
        };
        fitLabel = chest.add(Ui.muted(theme, "", wrap)).expandX().widget();
        warnLabel = chest.add(Ui.muted(theme, "", wrap)).expandX().widget();
        warnLabel.color(Ui.warn(theme));
        binCard = planCol.add(new SmCard("Shulker Box")).expandX().widget();
        binCard.padding = 6;
        binGrid = binCard.add(new WSlotGrid(host::graphics, IndexModel.SHULKER_SLOTS)).widget();
        planCol.add(Ui.muted(theme, "Click a shulker in the chest to look inside. Right-click a stack to remove it. The bot may deliver less if it can't reach everything.", wrap)).expandX();
        refreshPlan();
    }

    private int shown() {
        if (units.isEmpty()) return -1;
        return selectedUnit >= 0 && selectedUnit < units.size() ? selectedUnit : units.size() - 1;
    }

    private void refreshPlan() {
        if (planGrid == null) return;
        IndexModel idx = backend.catalog();
        DeliveryModel delivery = backend.delivery();
        layout = delivery.layout(units);
        int shown = shown();
        List<WItemGrid.Cell> cells = new ArrayList<>();
        WSlotGrid.Mark[] marks = new WSlotGrid.Mark[DeliveryModel.GRID];
        for (int i = 0; i < DeliveryModel.GRID; i++) {
            DeliveryModel.Item ex = layout.existing()[i];
            int ui = layout.slotUnit()[i];
            if (ex != null) {
                cells.add(new WItemGrid.Cell(ItemStacks.displayId(ex.id()), ex.count() > 1 ? Text.compact(ex.count()) : "", existingTip(ex, idx), null));
                marks[i] = WSlotGrid.Mark.EXISTING;
            } else if (ui >= 0 && ui < units.size()) {
                CartPlan.Unit u = units.get(ui);
                cells.add(new WItemGrid.Cell(unitStack(u, idx), "", unitTip(u, ui, idx, layout.overflow()[i]), null));
                marks[i] = ui == shown ? WSlotGrid.Mark.SELECTED : layout.overflow()[i] ? WSlotGrid.Mark.OVERFLOW : WSlotGrid.Mark.NONE;
            } else {
                cells.add(null);
                if (layout.size() > 0 && i >= layout.size()) marks[i] = WSlotGrid.Mark.OUTSIDE;
            }
        }
        planGrid.setCells(cells);
        planGrid.setMarks(marks);

        BotInfo bot = backend.bot();
        DeliveryModel.Fit fit = delivery.fit(units.size(), layout, bot.autoExpand(), System.currentTimeMillis());
        fitLabel.set(fit.text());
        warnLabel.set(fit.warning());

        List<WItemGrid.Cell> inside = new ArrayList<>(java.util.Collections.nCopies(IndexModel.SHULKER_SLOTS, (WItemGrid.Cell) null));
        String binTitle;
        if (shown >= 0) {
            CartPlan.Unit u = units.get(shown);
            if (u.kind() == CartPlan.Kind.BIN) {
                for (int s = 0; s < u.lots().size() && s < inside.size(); s++) {
                    Order.Lot lot = u.lots().get(s);
                    inside.set(s, contentCell(lot.key(), lot.count(), idx, true));
                }
            } else if (u.kind() == CartPlan.Kind.SHULKER) {
                IndexModel.Shulker g = findGroup(idx, u.key());
                if (g != null) for (IndexModel.Content c : idx.contentsOf(g)) if (c.slot() < inside.size()) inside.set(c.slot(), contentCell(c.key(), c.count(), idx, false));
            }
            binTitle = "Shulker " + (shown + 1) + " of " + units.size() + (u.kind() == CartPlan.Kind.BIN ? " - " + u.used() + "/27 slots" : u.kind() == CartPlan.Kind.EMPTY ? " - empty" : " - packed");
        } else {
            binTitle = "Shulker Box - nothing planned yet";
        }
        if (binCard != null) binCard.setTitle(binTitle);
        binGrid.setCells(inside);
    }

    private static WItemGrid.Cell contentCell(String key, long count, IndexModel idx, boolean removable) {
        IndexModel.Item it = idx.item(key);
        ItemStack stack = it != null ? ItemStacks.display(it) : ItemStacks.displayId(key);
        // The website's tooltip for the stack, with its count here and, in a shulker being packed, how to remove it
        String tip = it != null
            ? String.join("\n", ItemTip.item(idx, it, count, removable ? List.of(ItemTip.HINT + "Right-click to remove this stack") : List.of(), false))
            : ItemTip.plain(Text.sanitize(key, 40), removable ? List.of("Count: " + Text.grouped(count), ItemTip.HINT + "Right-click to remove this stack") : List.of("Count: " + Text.grouped(count)), key.split("#")[0]);
        return new WItemGrid.Cell(stack, count == 1 ? "" : Text.compact(count), tip, null);
    }

    /** The website's tooltip of a planned shulker in the chest (unitInfo): name, where it is, what is in it, how to use it, slots used. */
    private String unitTip(CartPlan.Unit u, int ui, IndexModel idx, boolean overflow) {
        String where = "Shulker " + (ui + 1) + " of " + units.size();
        String title;
        String id;
        int used;
        List<String> details = new ArrayList<>();
        details.add(where);
        if (u.kind() == CartPlan.Kind.SHULKER) {
            IndexModel.Shulker g = findGroup(idx, u.key());
            title = (g != null ? g.name() : u.key()) + " (packed, as it is)";
            id = g != null ? g.id() : "minecraft:shulker_box";
            used = g != null ? g.contents().size() : 0;
            details.add(ItemTip.HINT + "Right-click to remove it");
        } else if (u.kind() == CartPlan.Kind.EMPTY) {
            IndexModel.Item it = idx.item(u.key());
            title = "Empty " + (it != null ? it.name() : "Shulker Box");
            id = it != null ? it.id() : "minecraft:shulker_box";
            used = 0;
            details.add(ItemTip.HINT + "Right-click to remove it");
        } else {
            title = "Shulker Box (packed for you)";
            id = "minecraft:shulker_box";
            used = u.lots().size();
            details.addAll(ItemTip.summary(idx, u.lots()));
            details.add(ItemTip.HINT + "Click to look inside");
        }
        details.add(used + "/27 slots");
        if (overflow) details.add("Doesn't fit in this chest: goes to the next one");
        return ItemTip.plain(title, details, id);
    }

    private ItemStack unitStack(CartPlan.Unit u, IndexModel idx) {
        if (u.kind() == CartPlan.Kind.SHULKER) {
            IndexModel.Shulker g = findGroup(idx, u.key());
            return g != null ? ItemStacks.display(g) : ItemStacks.displayId("minecraft:shulker_box");
        }
        if (u.kind() == CartPlan.Kind.EMPTY) {
            IndexModel.Item it = idx.item(u.key());
            return it != null ? ItemStacks.display(it) : ItemStacks.displayId("minecraft:shulker_box");
        }
        return ItemStacks.displayId("minecraft:shulker_box");
    }

    private static String existingTip(DeliveryModel.Item it, IndexModel idx) {
        IndexModel.Item known = idx.item(it.id());
        String name = !it.name().isEmpty() ? it.name() : known != null ? known.name() : it.id().replaceFirst("^minecraft:", "");
        List<String> details = new ArrayList<>();
        details.add("Already in the delivery chest");
        details.add(Text.grouped(it.count()) + " \u00d7 " + name);
        if (it.shulker()) {
            if (it.contents() == null) {
                details.add("Contents not reported");
            } else {
                if (it.contents().isEmpty()) details.add("Empty");
                for (int i = 0; i < Math.min(12, it.contents().size()); i++) {
                    DeliveryModel.Inner in = it.contents().get(i);
                    IndexModel.Item k = idx.item(in.id());
                    details.add(Text.grouped(in.count()) + " \u00d7 " + (k != null ? k.name() : in.id().replaceFirst("^minecraft:", "")));
                }
                if (it.contents().size() > 12) details.add("and " + (it.contents().size() - 12) + " more");
            }
        }
        if (it.damage() > 0 && it.maxDamage() > 0) details.add("Durability: " + Text.grouped(it.maxDamage() - it.damage()) + " / " + Text.grouped(it.maxDamage()));
        return ItemTip.plain(name, details, it.id());
    }

    // ---------- the order list ----------

    private boolean armed(String key) {
        return key.equals(armedKey) && System.currentTimeMillis() < armedUntil;
    }

    /** First click arms the button (its text changes); a second click within a few seconds runs. @return true on the second click */
    private boolean armOrRun(String key, WButton button, String confirmText, String normalText) {
        long now = System.currentTimeMillis();
        if (key.equals(armedKey) && now < armedUntil) {
            armedKey = "";
            return true;
        }
        armedKey = key;
        armedUntil = now + ARM_MS;
        button.set(confirmText);
        return false;
    }

    /** What the list as a whole shows: the order ids, the confirm state of the buttons, the display mode. A change rebuilds the list. */
    private String listSignature(List<OrderModel.Entry> list) {
        StringBuilder sb = new StringBuilder(armedKey).append(host.hideNames());
        for (OrderModel.Entry e : list) sb.append(e.id()).append(e.finished()).append(backend.canCancel(e)).append(backend.canRetry(e)).append(backend.canForceCancel(e)).append(';');
        return sb.toString();
    }

    /** What one order's card shows. A change redraws that card only. */
    private String entrySignature(OrderModel.Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.status()).append(e.phase()).append(e.done()).append('/').append(e.total()).append(e.cancelRequested())
            .append(e.message()).append(e.retriedAs()).append(e.shulkers()).append(e.hasResult()).append(e.repairAll());
        RepairEstimate.Estimate est = e.repairEstimate();
        if (est != null) sb.append(est.needed()).append(',').append(est.available()).append(',').append(est.isShort());
        RepairEstimate.Result res = e.repairResult();
        if (res != null) sb.append(res.bottlesUsed()).append(',').append(res.repaired()).append(',').append(res.partial().size()).append(',').append(res.skipped().size());
        for (Order.Line l : e.lines()) sb.append(l.key()).append(l.count()).append(l.repair());
        CraftModel.OrderCraft craft = e.craft();
        if (craft != null) {
            for (CraftModel.Line l : craft.lines()) sb.append('c').append(l.key()).append(l.count());
            CraftModel.Result cr = craft.result();
            if (cr != null) sb.append(cr.fills()).append(',').append(cr.made().size()).append(',').append(cr.shorts().size()).append(',').append(cr.returned().size());
        }
        return sb.toString();
    }

    private final List<SmCard> entryCards = new ArrayList<>();
    private final List<String> entrySigs = new ArrayList<>();
    private String listSig = "";

    private SmPill.Kind kindOf(OrderModel.Entry e) {
        if (OrderModel.DELIVERED.equals(e.status())) return SmPill.Kind.OK;
        if (OrderModel.FAILED.equals(e.status())) return SmPill.Kind.ERROR;
        return e.open() ? SmPill.Kind.WARN : SmPill.Kind.NEUTRAL;
    }

    private void buildOrders() {
        if (ordersCard == null) return;
        List<OrderModel.Entry> list = backend.orders();
        int shownN = Math.min(list.size(), SHOWN_ORDERS);
        String lsig = listSignature(list) + "#" + shownN;
        IndexModel idx = backend.catalog();
        double wrap = bodyW - theme.scale(16 + 12 + 16);
        if (lsig.equals(listSig) && entryCards.size() == shownN) {
            // Same orders: only the cards whose content changed are drawn again
            for (int i = 0; i < shownN; i++) {
                String es = entrySignature(list.get(i));
                if (!es.equals(entrySigs.get(i))) {
                    entrySigs.set(i, es);
                    fillEntry(entryCards.get(i), list.get(i), idx, wrap);
                }
            }
            return;
        }
        listSig = lsig;
        ordersSignature = lsig;
        ordersCard.clear();
        entryCards.clear();
        entrySigs.clear();
        int finished = 0;
        for (OrderModel.Entry e : list) if (e.finished()) finished++;
        if (backend.canClear() && finished > 0) {
            SmButton clear = (SmButton) ordersCard.addRight(theme.button(armed("clear") ? "Confirm clear?" : "Clear history")).widget();
            clear.pill = true;
            clear.tooltip = "Removes the " + finished + " finished orders from this list. Open orders are kept.";
            clear.action = () -> host.later(() -> {
                if (armOrRun("clear", clear, "Confirm clear?", "Clear history")) {
                    backend.clearFinished(host::status);
                    ordersSignature = "";
                    listSig = "";
                } else {
                    listSig = "";
                    buildOrders();
                }
            });
        }
        if (list.isEmpty()) ordersCard.add(Ui.muted(theme, "No orders yet for this stash.")).expandX();
        for (int i = 0; i < shownN; i++) {
            OrderModel.Entry e = list.get(i);
            SmCard card = ordersCard.add(new SmCard("", SmCard.Style.TILE)).expandX().widget();
            card.padding = 6;
            card.spacing = 4;
            entryCards.add(card);
            entrySigs.add(entrySignature(e));
            fillEntry(card, e, idx, wrap);
        }
        if (list.size() > SHOWN_ORDERS) ordersCard.add(Ui.muted(theme, "...and " + (list.size() - SHOWN_ORDERS) + " older orders")).expandX();
    }

    private void fillEntry(SmCard card, OrderModel.Entry e, IndexModel idx, double wrap) {
        card.clear();
        {
            WHorizontalList head = card.add(theme.horizontalList()).expandX().widget();
            String status = OrderModel.statusLabel(e.status()) + (e.cancelRequested() && e.open() ? " (cancel requested)" : "");
            head.add(new SmPill(status, kindOf(e)));
            String when = e.createdAt() > 0 ? Instant.ofEpochMilli(e.createdAt()).atZone(ZoneId.systemDefault()).format(WHEN) + " " : "";
            String by = !host.hideNames() && !e.placedByName().isEmpty() ? " by " + e.placedByName() : "";
            head.add(theme.label(when + Text.sanitize(e.id(), 24) + by)).expandX();
            if (backend.canCancel(e)) {
                String key = "cancel:" + e.id();
                boolean running = !OrderModel.PENDING.equals(e.status());
                SmButton b = (SmButton) head.add(theme.button(armed(key) ? "Confirm?" : "Cancel")).widget();
                b.pill = true;
                b.tooltip = backend.localRun() ? "Stops the order now; the sorter lets go of everything." : running ? "Asks the bot to stop this order at a safe point." : "Cancels this order.";
                b.action = () -> host.later(() -> {
                    if (armOrRun(key, b, "Confirm?", "Cancel")) backend.cancel(e, msg -> afterAction(msg));
                    else listSig = "";
                });
            }
            if (backend.canRetry(e)) {
                SmButton b = (SmButton) head.add(theme.button("Retry")).widget();
                b.pill = true;
                b.tooltip = "Places this order again as a new order.";
                b.action = () -> host.later(() -> backend.retry(e, msg -> afterAction(msg)));
            }
            if (backend.canForceCancel(e)) {
                String key = "force:" + e.id();
                SmButton b = (SmButton) head.add(theme.button(armed(key) ? "Confirm?" : "Force cancel")).widget();
                b.pill = true;
                b.tooltip = "The bot hasn't confirmed. Marks the order cancelled anyway; if the bot is still working on it, it stops when it next reports.";
                b.action = () -> host.later(() -> {
                    if (armOrRun(key, b, "Confirm?", "Force cancel")) backend.forceCancel(e, msg -> afterAction(msg));
                    else listSig = "";
                });
            }
            if (!e.phase().isEmpty() && e.open()) card.add(Ui.muted(theme, e.phase(), wrap)).expandX();
            if (e.total() > 0) card.add(Ui.muted(theme, OrderModel.progressText(e.done(), e.total(), 20))).expandX();
            if (!e.message().isEmpty()) {
                WLabel msg = card.add(Ui.wrap(theme, e.message(), wrap)).expandX().widget();
                if (OrderModel.FAILED.equals(e.status())) msg.color(Ui.err(theme));
            }
            if (Order.Names.ALL.equals(e.namesMode()) && !e.namesAll().isEmpty()) {
                card.add(Ui.muted(theme, "Named: " + e.namesAll())).expandX();
            } else if (Order.Names.EACH.equals(e.namesMode())) {
                int named = 0;
                for (String n : e.namesEach()) if (!n.trim().isEmpty()) named++;
                if (named > 0) card.add(Ui.muted(theme, "Named each (" + named + ")")).expandX();
            }
            List<String> lineTexts = new ArrayList<>();
            for (Order.Line l : e.lines()) lineTexts.add(Text.grouped(l.count()) + " x " + Text.sanitize(lineName(l, idx), 40) + (wantedRepair(l, e.repairAll(), idx) ? " (repair)" : ""));
            String summary = String.join(", ", lineTexts.size() > 3 ? lineTexts.subList(0, 3) : lineTexts) + (lineTexts.size() > 3 ? ", +" + (lineTexts.size() - 3) + " more" : "");
            card.add(Ui.wrap(theme, summary, wrap)).expandX();
            craftLines(card, e, wrap);
            if (e.hasResult()) card.add(Ui.muted(theme, "Delivered in " + e.shulkers() + (e.shulkers() == 1 ? " shulker" : " shulkers"))).expandX();
            craftResult(card, e, wrap);
            repairSummary(card, e, wrap);
        }
    }

    /** The craft lines of an order: "Craft: 8 Spruce Stairs". */
    private void craftLines(SmCard card, OrderModel.Entry e, double wrap) {
        CraftModel.OrderCraft c = e.craft();
        if (c == null || c.lines().isEmpty()) return;
        List<String> texts = CraftModel.orderLines(c, 3);
        String more = c.lines().size() > 3 ? ", +" + (c.lines().size() - 3) + " more" : "";
        card.add(Ui.wrap(theme, String.join(", ", texts) + more, wrap)).expandX();
        if (e.open() && !c.take().isEmpty()) {
            card.add(Ui.muted(theme, "Crafting takes " + CraftModel.countList(c.take(), 4) + " from the stash.", wrap)).expandX();
        }
    }

    /** How the crafting of an order went: what was made, what was short (and why, in plain words), what went back. */
    private void craftResult(SmCard card, OrderModel.Entry e, double wrap) {
        CraftModel.Result r = e.craft() == null ? null : e.craft().result();
        if (r == null) return;
        String made = CraftModel.madeText(r);
        if (!made.isEmpty()) card.add(Ui.muted(theme, made, wrap)).expandX();
        String shortText = CraftModel.shortText(r);
        if (!shortText.isEmpty()) card.add(Ui.wrap(theme, shortText, wrap)).expandX().widget().color(Ui.warn(theme));
        String back = CraftModel.returnedText(r);
        if (!back.isEmpty()) card.add(Ui.muted(theme, back, wrap)).expandX();
    }

    /** What the repair of a finished (or placed) order says: the estimate it was placed with, and how it went. */
    private void repairSummary(SmCard card, OrderModel.Entry e, double wrap) {
        RepairEstimate.Result res = e.repairResult();
        if (res != null && res.any()) {
            card.add(Ui.muted(theme, "Repair: " + Text.grouped(res.bottlesUsed()) + (res.bottlesUsed() == 1 ? " bottle" : " bottles") + " used, "
                + res.repaired() + (res.repaired() == 1 ? " item" : " items") + " repaired", wrap)).expandX();
            String note = res.note();
            if (!note.isEmpty()) card.add(Ui.wrap(theme, note, wrap)).expandX().widget().color(Ui.warn(theme));
        } else if (e.repairEstimate() != null && e.open()) {
            RepairEstimate.Estimate est = e.repairEstimate();
            card.add(Ui.muted(theme, "Repair: about " + Text.grouped(est.needed()) + " bottles needed" + (est.isShort() ? " (the stash had " + Text.grouped(est.available()) + ")" : ""), wrap)).expandX();
        }
    }

    private void afterAction(String message) {
        host.status(message);
        ordersSignature = "";
        listSig = "";
        host.later(() -> {
            buildOrders();
            buildHeader();
        });
    }
}
