package com.porter.qwerte.manager.gui.tabs;

import com.porter.qwerte.manager.gui.DataSource;
import com.porter.qwerte.manager.gui.ItemStacks;
import com.porter.qwerte.manager.gui.style.Ui;
import com.porter.qwerte.manager.gui.chrome.WCreativeTabs;
import com.porter.qwerte.manager.gui.widgets.WItemGrid;
import com.porter.qwerte.manager.gui.widgets.WSlotGrid;
import com.porter.qwerte.manager.local.Where;
import com.porter.qwerte.manager.model.IndexModel;
import com.porter.qwerte.manager.model.IndexModel.Entry;
import com.porter.qwerte.manager.model.IndexModel.Sort;
import com.porter.qwerte.manager.model.Text;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Browse tab: search, sort, an item grid, and the details of the selected item or shulker group. Reads only the
 * {@link DataSource}; everything it shows came from there. The search text, sort and selection are kept when the tab is
 * rebuilt.
 */
public final class BrowseTab {
    /** What the tab needs from the window. */
    public interface Host {
        GuiGraphics graphics();

        /** False when nothing may be shown that isn't in the data (no player, no world). */
        boolean canInspect();

        void inspect(ItemStack stack, long total, String title);

        /** True while coordinates must not be drawn (streamer display). */
        boolean hideCoords();

        /** A short message for the user (the window's status line). */
        default void notice(String text) {
        }

        /** The Orders tab of the creative tabs was clicked: show the Orders page. */
        default void openOrders() {
        }

        /** True when right-clicking an item can put it in the order cart (for the hint under the grid). */
        default boolean cartEnabled() {
            return false;
        }
    }

    /** Puts items in the order cart (the Orders tab). */
    public interface CartAdder {
        boolean enabled();

        /** @return "" when added, else why not */
        String add(String type, String key, boolean shulkerWorth);
    }

    private CartAdder adder;

    public void setCartAdder(CartAdder adder) {
        this.adder = adder;
    }

    /** The sort select's entries, in the website's words, in the order of {@link #SORTS}. */
    private static final String[] SORT_LABELS = {"Creative order", "Count", "Name A-Z", "ID"};
    private static final Sort[] SORTS = {Sort.CREATIVE, Sort.COUNT, Sort.NAME, Sort.ID};

    private static final long SEARCH_DEBOUNCE_MS = 100;

    private final Host host;
    private final DataSource source;

    private String searchText = "";
    /** Like the website: Search and Shulkers sort by count, a creative tab by creative order, each remembered. */
    private Sort sortAll = Sort.COUNT;
    private Sort sortTab = Sort.CREATIVE;
    private String tab = IndexModel.TAB_ALL;
    private String selectedKey;
    private boolean dirty;
    private long dirtyAt;

    private GuiTheme theme;
    private WStashPage page;
    private WItemGrid grid;
    private WVerticalList details;
    private List<Entry> entries = List.of();
    private final Map<String, Entry> byKey = new HashMap<>();

    private IndexModel countedIndex;
    private String countedTab;
    private int countedAll;

    public BrowseTab(Host host, DataSource source) {
        this.host = host;
        this.source = source;
    }

    public void build(WContainer parent, GuiTheme theme) {
        this.theme = theme;

        page = parent.add(new WStashPage()).widget();
        page.tabs = page.add(new WCreativeTabs(host::graphics)).widget();
        page.tabs.onTab = id -> {
            if (IndexModel.TAB_ORDERS.equals(id)) {
                host.openOrders();
                return;
            }
            if (!id.equals(tab)) {
                tab = id;
                refresh();
            }
        };
        grid = page.grid = page.add(new WItemGrid(host::graphics)).widget();
        grid.tipFor = cell -> cell.ref() instanceof String key && byKey.get(key) != null ? com.porter.qwerte.manager.gui.widgets.ItemTip.lines(source.index(), byKey.get(key)) : null;
        grid.onCell = (cell, button) -> {
            // Right-click puts a stack in the order cart when orders are possible
            if (button == 1 && adder != null && adder.enabled() && cell.ref() instanceof String key && byKey.get(key) != null) {
                Entry picked = byKey.get(key);
                host.notice(adder.add(picked.item() != null ? "item" : "shulker", key, false));
                return;
            }
            selectedKey = cell.ref() instanceof String key ? key : null;
            grid.select(selectedKey);
            showDetails();
        };
        WTextBox search = page.add(theme.textBox(searchText, "Search (e.g. diamond sword, @minecraft)")).widget();
        page.search = search;
        search.action = () -> {
            searchText = search.get();
            dirty = true;
            dirtyAt = System.currentTimeMillis();
        };
        WDropdown<String> sortSelect = page.add(theme.dropdown(SORT_LABELS, SORT_LABELS[sortIndex(currentSort())])).widget();
        page.sort = sortSelect;
        sortSelect.action = () -> {
            Sort picked = SORTS[Math.max(0, java.util.Arrays.asList(SORT_LABELS).indexOf(sortSelect.get()))];
            if (isCreativeTab()) sortTab = picked;
            else sortAll = picked;
            refresh();
        };
        page.drawer = page.add(new WDrawer()).widget();
        page.details = page.add(theme.view()).widget();
        details = page.details.add(theme.verticalList()).expandX().widget();
        page.closeButton = page.add(theme.button("x")).widget();
        page.closeButton.tooltip = "Close the details";
        page.closeButton.action = () -> {
            selectedKey = null;
            grid.select(null);
            showDetails();
        };
        refresh();
    }

    private boolean isCreativeTab() {
        return !IndexModel.TAB_ALL.equals(tab) && !IndexModel.TAB_SHULKERS.equals(tab);
    }

    private Sort currentSort() {
        return isCreativeTab() ? sortTab : sortAll;
    }

    private static int sortIndex(Sort s) {
        for (int i = 0; i < SORTS.length; i++) if (SORTS[i] == s) return i;
        return 1;
    }

    /** Called from the window every frame: applies a typed search after a short pause. */
    /** The drawer width the details were wrapped for. */
    private double detailsFor = -1;

    public void tick(long now) {
        if (page != null && page.drawer != null && page.drawerOpen() && page.drawer.width > 0 && Math.abs(page.drawer.width - detailsFor) > 2) showDetails();
        if (dirty && now - dirtyAt >= SEARCH_DEBOUNCE_MS) {
            dirty = false;
            refresh();
        }
    }

    /** The index changed (or another stash was picked). */
    public void dataChanged(boolean otherStash) {
        if (otherStash) {
            selectedKey = null;
            if (grid != null) grid.resetScroll();
        }
        refresh();
    }

    private void refresh() {
        if (grid == null) return;
        dirty = false;
        IndexModel index = source.index();
        List<String> ids = index.visibleTabs();
        if (!ids.contains(tab)) tab = IndexModel.TAB_ALL;
        page.tabs.setTabs(ids, tab);
        page.label = IndexModel.TAB_ALL.equals(tab) ? "Search Items" : WCreativeTabs.nameOf(tab);
        // the select shows the sort of the tab now up
        @SuppressWarnings("unchecked")
        WDropdown<String> select = (WDropdown<String>) page.sort;
        String wantedLabel = SORT_LABELS[sortIndex(currentSort())];
        if (!wantedLabel.equals(select.get())) select.set(wantedLabel);

        boolean shulkers = IndexModel.TAB_SHULKERS.equals(tab);
        entries = index.view(searchText, currentSort(), false, tab);
        byKey.clear();
        List<WItemGrid.Cell> cells = new ArrayList<>(entries.size());
        long total = 0;
        for (Entry e : entries) {
            byKey.put(e.key(), e);
            total += e.count();
            cells.add(cell(e));
        }
        grid.setCells(cells);
        grid.select(selectedKey);

        if (countedIndex != index || !tab.equals(countedTab)) {
            countedAll = index.view("", Sort.COUNT, false, tab).size();
            countedIndex = index;
            countedTab = tab;
        }
        if (countedAll == 0) {
            page.summaryLong = index.items().isEmpty() ? "No index yet for this stash, or it is empty." : "";
            page.summaryShort = page.summaryLong;
        } else {
            page.summaryLong = "Showing " + Text.grouped(entries.size()) + " of " + Text.grouped(countedAll)
                + (shulkers ? " filled shulker kinds · total " : " slots · total ") + Text.grouped(total)
                + (shulkers ? " shulkers" : " items");
            page.summaryShort = Text.grouped(entries.size()) + " of " + Text.grouped(countedAll) + " · " + Text.grouped(total);
        }
        page.hint = host.cartEnabled()
            ? "Click an item for its details. Right-click puts a stack in the order cart."
            : "Click an item for its details.";
        page.invalidate();
        showDetails();
    }

    private WItemGrid.Cell cell(Entry e) {
        ItemStack real = source.stackFor(e.key());
        ItemStack stack = real != null ? real : e.item() != null ? ItemStacks.display(e.item()) : ItemStacks.display(e.shulker());
        String count = e.count() == 1 ? "" : Text.compact(e.count());
        String tip = e.name() + " x" + Text.grouped(e.count());
        return new WItemGrid.Cell(stack, count, tip, e.key());
    }

    // ---------- details ----------

    private void showDetails() {
        if (details == null) return;
        details.clear();
        // The drawer's real width comes from the layout (0 until the first one: tick() shows the details again then)
        int du = com.porter.qwerte.manager.gui.style.SmGuiTheme.unitOf(theme);
        detailsFor = page.drawer != null ? page.drawer.width : 0;
        double wrap = detailsFor > 0 ? Math.max(theme.scale(100), detailsFor - 8 * du - theme.scale(16)) : theme.scale(240);
        Entry e = selectedKey == null ? null : byKey.get(selectedKey);
        if (e == null) {
            selectedKey = null;
            page.setDrawerOpen(false);
            return;
        }
        page.setDrawerOpen(true);
        if (e.item() != null) {
            IndexModel.Item it = e.item();
            details.add(Ui.wrap(theme, "§l" + it.name(), wrap)).expandX();
            if (!it.id().equals(it.name())) details.add(Ui.wrap(theme, it.id(), wrap)).expandX();
            details.add(Ui.wrap(theme, countLine(it), wrap)).expandX();
            if (e.count() != it.count()) details.add(Ui.wrap(theme, Text.grouped(e.count()) + " empty, " + Text.grouped(it.count() - e.count()) + " filled (listed next to it)", wrap)).expandX();
            boolean hasDurabilityLine = false;
            for (String line : it.details()) {
                details.add(Ui.wrap(theme, line, wrap)).expandX();
                hasDurabilityLine |= line.toLowerCase(Locale.ROOT).startsWith("durability");
            }
            if (it.durabilityLeft() >= 0 && !hasDurabilityLine) {
                details.add(Ui.wrap(theme, "Durability: " + Text.grouped(it.maxDamage() - it.damage()) + " / " + Text.grouped(it.maxDamage()), wrap)).expandX();
            }
            if (host.canInspect()) {
                WButton inspect = details.add(theme.button("Inspect")).widget();
                inspect.tooltip = "Opens the game's own item tooltip for this item. Esc comes back here.";
                inspect.action = () -> {
                    ItemStack real = source.stackFor(it.key());
                    host.inspect(real != null ? real : ItemStacks.display(it), it.count(), it.name() + " x" + Text.grouped(it.count()));
                };
            }
            addWhere(it.key(), wrap);
        } else {
            IndexModel.Shulker g = e.shulker();
            IndexModel index = source.index();
            details.add(Ui.wrap(theme, "§l" + g.name(), wrap)).expandX();
            details.add(Ui.wrap(theme, Text.grouped(g.count()) + (g.count() == 1 ? " shulker" : " identical shulkers"), wrap)).expandX();
            WSlotGrid contents = details.add(new WSlotGrid(host::graphics, IndexModel.SHULKER_SLOTS)).widget();
            List<WItemGrid.Cell> cells = new ArrayList<>(java.util.Collections.nCopies(IndexModel.SHULKER_SLOTS, (WItemGrid.Cell) null));
            for (IndexModel.Content c : index.contentsOf(g)) {
                IndexModel.Item it = index.item(c.key());
                ItemStack real = source.stackFor(c.key());
                ItemStack stack = real != null ? real : it != null ? ItemStacks.display(it) : ItemStacks.displayId(c.key());
                String name = it != null ? it.name() : Text.sanitize(c.key(), 60);
                cells.set(c.slot(), new WItemGrid.Cell(stack, c.count() == 1 ? "" : Text.compact(c.count()), name + " x" + Text.grouped(c.count()), c.key()));
            }
            contents.setCells(cells);
            addWhere(g.key(), wrap);
        }
        addCartButtons(e);
    }

    private void addCartButtons(Entry e) {
        if (adder == null || !adder.enabled()) return;
        String type = e.item() != null ? "item" : "shulker";
        WHorizontalList row = details.add(theme.horizontalList()).widget();
        WButton one = row.add(theme.button(e.item() != null ? "Add a stack to cart" : "Add one to cart")).widget();
        one.tooltip = "Puts it in the order cart (right-click works too). Orders tab.";
        one.action = () -> host.notice(adder.add(type, e.key(), false));
        if (e.item() != null) {
            WButton worth = row.add(theme.button("Add a shulker's worth")).widget();
            worth.tooltip = "27 stacks.";
            worth.action = () -> host.notice(adder.add(type, e.key(), true));
        }
    }

    private static final int WHERE_SHOWN = 8;

    /** Local mode: which stored containers hold the selected item, with a button to forget each. */
    private void addWhere(String key, double wrap) {
        if (!source.supportsWhere()) return;
        details.add(theme.label("Where", true)).expandX();
        if (host.hideCoords()) {
            details.add(Ui.wrap(theme, "Locations are hidden while streamer display is on.", wrap)).expandX();
            return;
        }
        List<Where> hits = source.where(key);
        if (hits.isEmpty()) {
            details.add(Ui.wrap(theme, "Not in any stored container.", wrap)).expandX();
            return;
        }
        for (int i = 0; i < Math.min(WHERE_SHOWN, hits.size()); i++) {
            Where w = hits.get(i);
            WHorizontalList row = details.add(theme.horizontalList()).expandX().widget();
            String block = w.block().substring(w.block().indexOf(':') + 1).replace('_', ' ');
            String away = w.distance() >= 0 ? ", " + w.distance() + " m" : "";
            WButton forget = null;
            double room = Math.max(theme.scale(60), wrap - (theme.textWidth("Forget") + 2 * theme.scale(10)) - theme.scale(10));
            row.add(Ui.wrap(theme, w.key().x() + " " + w.key().y() + " " + w.key().z() + " - " + block + " x" + Text.grouped(w.count()) + away, room)).expandX();
            forget = row.add(theme.button("Forget")).widget();
            forget.tooltip = "Removes this container from the local index. It is remembered again when you open it.";
            forget.action = () -> source.forget(w.key());
        }
        if (hits.size() > WHERE_SHOWN) details.add(Ui.wrap(theme, "...and " + (hits.size() - WHERE_SHOWN) + " more", wrap)).expandX();
    }

    private static String countLine(IndexModel.Item it) {
        double stacks = (double) it.count() / it.maxStack();
        double shulkers = stacks / IndexModel.SHULKER_SLOTS;
        return "Count: " + Text.grouped(it.count()) + " (about " + approx(stacks) + " stacks, " + approx(shulkers) + " shulkers)";
    }

    private static String approx(double v) {
        if (v > 0 && v < 0.05) return "<0.1";
        if (v >= 10) return Long.toString(Math.round(v));
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
