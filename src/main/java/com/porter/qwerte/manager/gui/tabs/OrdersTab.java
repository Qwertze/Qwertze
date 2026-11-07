package com.porter.qwerte.manager.gui.tabs;

import com.porter.qwerte.manager.gui.ItemStacks;
import com.porter.qwerte.manager.gui.OrderBackend;
import com.porter.qwerte.manager.gui.widgets.WItemGrid;
import com.porter.qwerte.manager.gui.widgets.WSlotGrid;
import com.porter.qwerte.manager.model.BotInfo;
import com.porter.qwerte.manager.model.Cart;
import com.porter.qwerte.manager.model.CartPlan;
import com.porter.qwerte.manager.model.DeliveryModel;
import com.porter.qwerte.manager.model.IndexModel;
import com.porter.qwerte.manager.model.NamesDraft;
import com.porter.qwerte.manager.model.OrderMode;
import com.porter.qwerte.manager.model.OrderModel;
import com.porter.qwerte.manager.model.Text;
import com.porter.qwerte.modules.LocalOrders;
import com.porter.qwerte.stash.Order;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.utils.render.color.Color;
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
 * The Orders tab: pick items into a cart, see the shulkers they are packed into laid out in the delivery chest next to what
 * is already there, place the order, and follow the orders. It is the same for the bots of a server and for this client; the
 * {@link OrderBackend} says which. It keeps no data of its own beyond what is on screen: the cart and names live in the
 * module (per stash), the orders in the backend.
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

    private static final Color WARN = new Color(255, 190, 80);
    private static final Color BAD = new Color(255, 110, 110);
    private static final Color GOOD = new Color(120, 220, 140);
    private static final long ARM_MS = 4_000;
    private static final int SHOWN_ORDERS = 30;
    private static final int PICKER_MAX = 3_000;
    private static final long SEARCH_DEBOUNCE_MS = 150;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d HH:mm");

    private final Host host;
    private OrderBackend backend;
    private GuiTheme theme;

    private WVerticalList header, cartCol, planCol, ordersCol;
    private WItemGrid picker;
    private WLabel pickerHint;
    private WSlotGrid planGrid, binGrid;
    private WLabel fitLabel, warnLabel, binTitle, namesWarn, cartStatus;

    private String searchText = "";
    private boolean searchDirty;
    private long searchAt;
    private int selectedUnit = -1;
    private int badLine = -1;
    private boolean placing;
    private String armedKey = "";
    private long armedUntil;
    private String ordersSignature = "";
    private String headerSignature = "";

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
        header = parent.add(theme.verticalList()).expandX().widget();
        WHorizontalList main = parent.add(theme.horizontalList()).expandX().widget();

        WVerticalList pickCol = main.add(theme.verticalList()).top().widget();
        if (backend.canPlace()) {
            pickCol.add(theme.label("Add to the cart", true));
            WTextBox search = pickCol.add(theme.textBox(searchText, "Search items")).minWidth(theme.scale(200)).expandX().widget();
            search.action = () -> {
                searchText = search.get();
                searchDirty = true;
                searchAt = System.currentTimeMillis();
            };
            picker = pickCol.add(new WItemGrid(host::graphics).compact(6, 7)).widget();
            picker.onCell = (cell, button) -> {
                if (!(cell.ref() instanceof IndexModel.Entry e)) return;
                String type = e.item() != null ? Order.ITEM : Order.SHULKER;
                String msg = addToCart(type, e.key(), button == 1);
                host.status(msg);
            };
            pickerHint = pickCol.add(theme.label("Click: one stack. Right-click: a shulker's worth.", theme.scale(220))).expandX().widget();
        } else {
            picker = null;
            pickerHint = null;
        }

        cartCol = main.add(theme.verticalList()).top().minWidth(theme.scale(340)).widget();
        planCol = main.add(theme.verticalList()).top().widget();
        ordersCol = parent.add(theme.verticalList()).expandX().widget();
        refreshAll();
    }

    private void refreshAll() {
        buildHeader();
        refreshPicker();
        onCartChanged();
        buildOrders();
    }

    /** Every frame: applies a typed search, expires the confirm click, and redraws what the backend changed. */
    public void tick(long now, boolean changed) {
        if (header == null) return;
        if (searchDirty && now - searchAt >= SEARCH_DEBOUNCE_MS) {
            searchDirty = false;
            refreshPicker();
        }
        if (!armedKey.isEmpty() && now > armedUntil) {
            armedKey = "";
            ordersSignature = "";
            buildOrders();
        }
        if (changed) {
            // Only what changed is redrawn. The cart column holds text boxes, so it is rebuilt only when what it shows changed
            // and none of its boxes has the keyboard (held back while one has focus); the order list and the preview have none
            buildHeader();
            refreshPicker();
            buildOrders();
            recomputeUnits();
            refreshPlan();
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
        for (Order.Line l : host.cart(backend.cartKey()).lines()) sb.append(l.type()).append(l.key()).append(l.count()).append(lineName(l, idx)).append(';');
        sb.append(host.names(backend.cartKey()).mode()).append(units.size()).append(badLine);
        return sb.toString();
    }

    private void recomputeUnits() {
        if (!backend.canPlace()) {
            units = List.of();
            return;
        }
        List<CartPlan.Unit> plan = CartPlan.plan(host.cart(backend.cartKey()).lines(), backend.catalog());
        units = plan == null ? List.of() : plan;
    }

    // ---------- header ----------

    private void buildHeader() {
        BotInfo bot = backend.bot();
        LocalOrders.Setup setup = backend.setup();
        String sig = backend.title() + "|" + backend.blocker() + "|" + bot + "|" + backend.warning() + "|" + host.mode() + "|" + host.hideNames() + "|" + backend.busy() + "|"
            + (setup == null ? "-" : setup.stash() + java.util.Arrays.toString(setup.delivery()) + java.util.Arrays.toString(setup.anvil()) + java.util.Arrays.toString(setup.table())
                + setup.anvilSource() + setup.tableSource() + setup.craftingOn() + setup.xpLevels());
        if (sig.equals(headerSignature)) return;
        headerSignature = sig;
        header.clear();
        double wrap = theme.scale(640);
        header.add(theme.label(backend.title(), true));
        if (host.offersModeChoice()) {
            WHorizontalList row = header.add(theme.horizontalList()).widget();
            row.add(theme.label("Who carries out new orders: "));
            WDropdown<OrderMode> mode = row.add(theme.dropdown(host.mode())).widget();
            mode.action = () -> {
                OrderMode picked = mode.get();
                host.later(() -> host.setMode(picked));
            };
        }
        if (!backend.canPlace()) header.add(theme.label("Your account can't place orders. You can see the orders of this stash.", wrap)).expandX();
        String blocker = backend.blocker();
        if (!blocker.isEmpty()) header.add(theme.label(Text.sanitize(blocker, 200), wrap)).expandX().widget().color(WARN);
        String info = bot.craftInfo();
        if (!info.isEmpty()) header.add(theme.label(info, wrap)).expandX();
        String warning = backend.warning();
        if (!warning.isEmpty()) header.add(theme.label(warning, wrap)).expandX().widget().color(BAD);
        if (backend.localRun()) buildSetup(setup, wrap);
    }

    private void buildSetup(LocalOrders.Setup setup, double wrap) {
        if (setup == null) {
            header.add(theme.label("No stash is loaded. Set stash-name in stash-sorter and scan the stash first.", wrap)).expandX().widget().color(WARN);
            return;
        }
        header.add(theme.label("Stash: " + (host.hideNames() ? "(hidden)" : Text.sanitize(setup.stash(), 40)) + ". The order is taken from it and delivered to its delivery container.", wrap)).expandX();
        pickRow("Delivery container", setup.delivery(), "delivery", "");
        pickRow("Anvil (to name shulkers)", setup.anvil(), "anvil", setup.anvilSource().isEmpty() || setup.anvilSource().equals("none") ? "" : setup.anvilSource());
        pickRow("Crafting table", setup.table(), "crafting-table", setup.tableSource().isEmpty() || setup.tableSource().equals("none") ? "" : setup.tableSource());
        header.add(theme.label("Crafting empty shulkers is " + (setup.craftingOn() ? "on" : "off") + ", and shulkers with a custom name are "
            + (setup.namedShulkers() ? "used" : "left alone") + " (stash-manager settings: enable-crafting, craft-never-use, use-named-shulkers, auto-expand-delivery).", wrap)).expandX();
    }

    private void pickRow(String name, int[] pos, String what, String source) {
        WHorizontalList row = header.add(theme.horizontalList()).expandX().widget();
        String where = pos == null ? "not set" : host.hideNames() ? "set" : pos[0] + " " + pos[1] + " " + pos[2];
        row.add(theme.label(name + ": " + where + (source.isEmpty() || pos == null ? "" : " (" + source + ")"))).expandX();
        // Not while an order of this client runs: the sorter is using these
        if (backend.busy()) return;
        WButton set = row.add(theme.button(pos == null ? "Set" : "Change")).widget();
        set.tooltip = "Closes the window; then right-click the block in the world. Right-click the current one to clear it.";
        set.action = () -> host.later(() -> {
            String err = backend.select(what);
            if (err != null) host.status(err);
            else host.closeWindow("Right-click the " + name.toLowerCase(java.util.Locale.ROOT).replace(" (to name shulkers)", "") + " now.");
        });
        if (pos != null) {
            WButton clear = row.add(theme.button("Clear")).widget();
            clear.action = () -> host.later(() -> {
                headerSignature = "";
                host.status(backend.clearPick(what) ? name + " cleared." : "Nothing to clear.");
                buildHeader();
            });
        }
    }

    // ---------- picker ----------

    private void refreshPicker() {
        if (picker == null) return;
        IndexModel idx = backend.catalog();
        List<IndexModel.Entry> entries = idx.view(searchText, IndexModel.Sort.COUNT, false);
        List<WItemGrid.Cell> cells = new ArrayList<>();
        for (IndexModel.Entry e : entries) {
            if (cells.size() >= PICKER_MAX) break;
            long avail = backend.availableTotal(e.item() != null ? Order.ITEM : Order.SHULKER, e.key());
            if (avail < 1) continue;
            ItemStack stack = e.item() != null ? ItemStacks.display(e.item()) : ItemStacks.display(e.shulker());
            cells.add(new WItemGrid.Cell(stack, e.count() == 1 ? "" : Text.compact(avail), e.name() + " x" + Text.grouped(avail) + " available", e));
        }
        picker.setCells(cells);
        if (pickerHint != null && idx.items().isEmpty()) pickerHint.set(backend.localRun() ? "The sorter's stash has nothing indexed yet." : "No index for this stash yet.");
        else if (pickerHint != null) pickerHint.set("Click: one stack. Right-click: a shulker's worth.");
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

    private void buildCart() {
        if (cartCol == null) return;
        cartCol.clear();
        cartBoxes.clear();
        namesWarn = null;
        cartStatus = null;
        if (!backend.canPlace()) return;
        IndexModel idx = backend.catalog();
        Cart cart = host.cart(backend.cartKey());
        NamesDraft names = host.names(backend.cartKey());
        double wrap = theme.scale(330);

        cartCol.add(theme.label("Cart", true));
        if (cart.isEmpty()) cartCol.add(theme.label("The cart is empty. Pick items on the left, or right-click items in Browse.", wrap)).expandX();
        List<Order.Line> lines = cart.lines();
        long packed = 0, empties = 0, items = 0;
        for (int i = 0; i < lines.size(); i++) {
            Order.Line l = lines.get(i);
            int index = i;
            WHorizontalList row = cartCol.add(theme.horizontalList()).expandX().widget();
            WLabel name = row.add(theme.label(Text.sanitize(lineName(l, idx), 36))).expandX().widget();
            if (i == badLine) name.color(BAD);
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
            WButton remove = row.add(theme.button("x")).widget();
            remove.tooltip = "Remove this line";
            remove.action = () -> {
                cart.remove(index);
                badLine = -1;
                host.later(this::onCartChanged);
            };
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
            WLabel t = cartCol.add(theme.label(totals, wrap)).expandX().widget();
            if (tooMany) t.color(WARN);
        }

        // Names, like the website: none, one for all, one for each planned shulker
        if (!lines.isEmpty()) {
            WHorizontalList nameRow = cartCol.add(theme.horizontalList()).widget();
            nameRow.add(theme.label("Shulker names: "));
            WDropdown<NamesDraft.Mode> mode = nameRow.add(theme.dropdown(names.mode())).widget();
            mode.action = () -> {
                names.setMode(mode.get());
                host.later(this::onCartChanged);
            };
            if (names.mode() == NamesDraft.Mode.ALL) {
                WTextBox all = cartCol.add(theme.textBox(names.all(), "Name for every shulker (up to 50 characters)")).expandX().widget();
                cartBoxes.add(all);
                all.action = () -> {
                    names.setAll(all.get());
                    updateNamesWarn(names);
                };
            } else if (names.mode() == NamesDraft.Mode.EACH) {
                for (int i = 0; i < units.size(); i++) {
                    int index = i;
                    WHorizontalList row = cartCol.add(theme.horizontalList()).expandX().widget();
                    row.add(theme.label("Shulker " + (i + 1) + ": " + Text.sanitize(unitSummary(units.get(i), idx), 40))).expandX();
                    WTextBox each = row.add(theme.textBox(names.each(i), "unnamed")).minWidth(theme.scale(140)).widget();
                    cartBoxes.add(each);
                    each.action = () -> {
                        names.setEach(index, each.get());
                        updateNamesWarn(names);
                    };
                }
            }
            namesWarn = cartCol.add(theme.label("", wrap)).expandX().widget();
            namesWarn.color(WARN);
            updateNamesWarn(names);
        }

        cartStatus = cartCol.add(theme.label("", wrap)).expandX().widget();
        cartStatus.color(WARN);
        if (!lines.isEmpty()) {
            WHorizontalList buttons = cartCol.add(theme.horizontalList()).widget();
            WButton place = buttons.add(theme.button(backend.localRun() ? "Start order here" : "Place order")).widget();
            place.action = () -> host.later(() -> place(cart, names));
            WButton clear = buttons.add(theme.button(armed("cart") ? "Confirm clear?" : "Clear cart")).widget();
            clear.action = () -> host.later(() -> {
                if (armOrRun("cart", clear, "Confirm clear?", "Clear cart")) {
                    cart.clear();
                    names.reset();
                    badLine = -1;
                    onCartChanged();
                }
            });
        }
    }

    private void updateNamesWarn(NamesDraft names) {
        if (namesWarn == null) return;
        String problem = names.problem(units.size());
        namesWarn.set(problem.isEmpty() ? backend.bot().namesWarning(names.namedCount(units.size())) : problem);
        if (!problem.isEmpty()) namesWarn.color(BAD);
        else namesWarn.color(WARN);
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
        backend.place(cart.lines(), names, plan.size(), r -> {
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
        fitLabel = warnLabel = binTitle = null;
        if (!backend.canPlace()) return;
        planCol.add(theme.label("Planned delivery chest", true));
        planGrid = planCol.add(new WSlotGrid(host::graphics, DeliveryModel.GRID)).widget();
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
        fitLabel = planCol.add(theme.label("", theme.scale(330))).expandX().widget();
        warnLabel = planCol.add(theme.label("", theme.scale(330))).expandX().widget();
        warnLabel.color(WARN);
        binTitle = planCol.add(theme.label("")).expandX().widget();
        binGrid = planCol.add(new WSlotGrid(host::graphics, IndexModel.SHULKER_SLOTS)).widget();
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
                String where = "Shulker " + (ui + 1) + " of " + units.size();
                cells.add(new WItemGrid.Cell(unitStack(u, idx), "", where + ": " + unitSummary(u, idx) + (u.kind() == CartPlan.Kind.BIN ? " (" + u.used() + "/27 slots)" : "")
                    + (layout.overflow()[i] ? ". Doesn't fit in this chest: goes to the next one" : ""), null));
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
        if (shown >= 0) {
            CartPlan.Unit u = units.get(shown);
            if (u.kind() == CartPlan.Kind.BIN) {
                for (int s = 0; s < u.lots().size() && s < inside.size(); s++) {
                    Order.Lot lot = u.lots().get(s);
                    inside.set(s, contentCell(lot.key(), lot.count(), idx));
                }
            } else if (u.kind() == CartPlan.Kind.SHULKER) {
                IndexModel.Shulker g = findGroup(idx, u.key());
                if (g != null) for (IndexModel.Content c : idx.contentsOf(g)) if (c.slot() < inside.size()) inside.set(c.slot(), contentCell(c.key(), c.count(), idx));
            }
            binTitle.set("Shulker " + (shown + 1) + " of " + units.size() + (u.kind() == CartPlan.Kind.BIN ? " - " + u.used() + "/27 slots" : u.kind() == CartPlan.Kind.EMPTY ? " - empty" : " - packed"));
        } else {
            binTitle.set("Shulker box - nothing planned yet");
        }
        binGrid.setCells(inside);
    }

    private static WItemGrid.Cell contentCell(String key, long count, IndexModel idx) {
        IndexModel.Item it = idx.item(key);
        ItemStack stack = it != null ? ItemStacks.display(it) : ItemStacks.displayId(key);
        String name = it != null ? it.name() : Text.sanitize(key, 40);
        return new WItemGrid.Cell(stack, count == 1 ? "" : Text.compact(count), name + " x" + Text.grouped(count), null);
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
        StringBuilder sb = new StringBuilder("Already in the delivery chest: ").append(Text.grouped(it.count())).append(" x ").append(name);
        if (it.shulker()) {
            if (it.contents() == null) sb.append(". Contents not reported");
            else if (it.contents().isEmpty()) sb.append(". Empty");
            else {
                sb.append(": ");
                for (int i = 0; i < Math.min(6, it.contents().size()); i++) {
                    DeliveryModel.Inner in = it.contents().get(i);
                    IndexModel.Item k = idx.item(in.id());
                    if (i > 0) sb.append(", ");
                    sb.append(Text.grouped(in.count())).append(" x ").append(k != null ? k.name() : in.id().replaceFirst("^minecraft:", ""));
                }
                if (it.contents().size() > 6) sb.append(", and ").append(it.contents().size() - 6).append(" more");
            }
        }
        return sb.toString();
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

    private String signature(List<OrderModel.Entry> list) {
        StringBuilder sb = new StringBuilder(armedKey).append(host.hideNames());
        for (OrderModel.Entry e : list) {
            sb.append(e.id()).append(e.status()).append(e.phase()).append(e.done()).append('/').append(e.total()).append(e.cancelRequested())
                .append(e.message()).append(e.retriedAs()).append(backend.canCancel(e)).append(backend.canRetry(e)).append(backend.canForceCancel(e)).append(';');
        }
        return sb.toString();
    }

    private void buildOrders() {
        if (ordersCol == null) return;
        List<OrderModel.Entry> list = backend.orders();
        String sig = signature(list);
        if (sig.equals(ordersSignature)) return;
        ordersSignature = sig;
        ordersCol.clear();
        double wrap = theme.scale(640);
        IndexModel idx = backend.catalog();
        WHorizontalList title = ordersCol.add(theme.horizontalList()).expandX().widget();
        title.add(theme.label("Orders", true)).expandX();
        int finished = 0;
        for (OrderModel.Entry e : list) if (e.finished()) finished++;
        if (backend.canClear() && finished > 0) {
            WButton clear = title.add(theme.button(armed("clear") ? "Confirm clear?" : "Clear finished (" + finished + ")")).widget();
            clear.tooltip = "Removes the finished orders from this list. Open orders are kept.";
            clear.action = () -> host.later(() -> {
                if (armOrRun("clear", clear, "Confirm clear?", "Clear finished")) {
                    backend.clearFinished(host::status);
                    ordersSignature = "";
                } else {
                    ordersSignature = "";
                    buildOrders();
                }
            });
        }
        if (list.isEmpty()) ordersCol.add(theme.label("No orders yet for this stash.")).expandX();
        int shownCount = 0;
        for (OrderModel.Entry e : list) {
            if (shownCount++ >= SHOWN_ORDERS) {
                ordersCol.add(theme.label("...and " + (list.size() - SHOWN_ORDERS) + " older orders")).expandX();
                break;
            }
            WHorizontalList head = ordersCol.add(theme.horizontalList()).expandX().widget();
            String status = OrderModel.statusLabel(e.status()) + (e.cancelRequested() && e.open() ? " (cancel requested)" : "");
            WLabel badge = head.add(theme.label("[" + status + "]")).widget();
            badge.color(OrderModel.DELIVERED.equals(e.status()) ? GOOD : OrderModel.FAILED.equals(e.status()) ? BAD : e.open() ? WARN : new Color(160, 160, 160));
            String when = e.createdAt() > 0 ? Instant.ofEpochMilli(e.createdAt()).atZone(ZoneId.systemDefault()).format(WHEN) + " " : "";
            String by = !host.hideNames() && !e.placedByName().isEmpty() ? " by " + e.placedByName() : "";
            head.add(theme.label(when + Text.sanitize(e.id(), 24) + by)).expandX();
            if (backend.canCancel(e)) {
                String key = "cancel:" + e.id();
                boolean running = !OrderModel.PENDING.equals(e.status());
                WButton b = head.add(theme.button(armed(key) ? "Confirm?" : "Cancel")).widget();
                b.tooltip = backend.localRun() ? "Stops the order now; the sorter lets go of everything." : running ? "Asks the bot to stop this order at a safe point." : "Cancels this order.";
                b.action = () -> host.later(() -> {
                    if (armOrRun(key, b, "Confirm?", "Cancel")) backend.cancel(e, msg -> afterAction(msg));
                    else ordersSignature = "";
                });
            }
            if (backend.canRetry(e)) {
                WButton b = head.add(theme.button("Retry")).widget();
                b.tooltip = "Places this order again as a new order.";
                b.action = () -> host.later(() -> backend.retry(e, msg -> afterAction(msg)));
            }
            if (backend.canForceCancel(e)) {
                String key = "force:" + e.id();
                WButton b = head.add(theme.button(armed(key) ? "Confirm?" : "Force cancel")).widget();
                b.tooltip = "The bot hasn't confirmed. Marks the order cancelled anyway; if the bot is still working on it, it stops when it next reports.";
                b.action = () -> host.later(() -> {
                    if (armOrRun(key, b, "Confirm?", "Force cancel")) backend.forceCancel(e, msg -> afterAction(msg));
                    else ordersSignature = "";
                });
            }
            if (!e.phase().isEmpty() && e.open()) ordersCol.add(theme.label(e.phase(), wrap)).expandX();
            if (e.total() > 0) ordersCol.add(theme.label(OrderModel.progressText(e.done(), e.total(), 20))).expandX();
            if (!e.message().isEmpty()) {
                WLabel msg = ordersCol.add(theme.label(e.message(), wrap)).expandX().widget();
                if (OrderModel.FAILED.equals(e.status())) msg.color(BAD);
            }
            if (Order.Names.ALL.equals(e.namesMode()) && !e.namesAll().isEmpty()) {
                ordersCol.add(theme.label("Named: " + e.namesAll())).expandX();
            } else if (Order.Names.EACH.equals(e.namesMode())) {
                int named = 0;
                for (String n : e.namesEach()) if (!n.trim().isEmpty()) named++;
                if (named > 0) ordersCol.add(theme.label("Named each (" + named + ")")).expandX();
            }
            List<String> lineTexts = new ArrayList<>();
            for (Order.Line l : e.lines()) lineTexts.add(Text.grouped(l.count()) + " x " + Text.sanitize(lineName(l, idx), 40));
            String summary = String.join(", ", lineTexts.size() > 3 ? lineTexts.subList(0, 3) : lineTexts) + (lineTexts.size() > 3 ? ", +" + (lineTexts.size() - 3) + " more" : "");
            ordersCol.add(theme.label(summary, wrap)).expandX();
            if (e.hasResult()) ordersCol.add(theme.label("Delivered in " + e.shulkers() + (e.shulkers() == 1 ? " shulker" : " shulkers"))).expandX();
        }
    }

    private void afterAction(String message) {
        host.status(message);
        ordersSignature = "";
        host.later(() -> {
            buildOrders();
            buildHeader();
        });
    }
}
