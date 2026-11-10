package com.porter.qwerte.manager.gui.tabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.porter.qwerte.manager.gui.ItemStacks;
import com.porter.qwerte.manager.gui.chrome.WFlow;
import com.porter.qwerte.manager.gui.style.PageRoom;
import com.porter.qwerte.manager.gui.style.SmButton;
import com.porter.qwerte.manager.gui.style.SmCard;
import com.porter.qwerte.manager.gui.style.Ui;
import com.porter.qwerte.manager.gui.widgets.WItemGrid;
import com.porter.qwerte.manager.model.BuilderModel;
import com.porter.qwerte.manager.model.BuilderModel.Draft;
import com.porter.qwerte.manager.model.BuilderModel.EditSlot;
import com.porter.qwerte.manager.model.IndexModel;
import com.porter.qwerte.manager.model.KitsModel;
import com.porter.qwerte.manager.model.KitsV2;
import com.porter.qwerte.manager.model.StashRef;
import com.porter.qwerte.manager.model.Text;
import com.porter.qwerte.manager.net.ApiError;
import com.porter.qwerte.stash.Kit;
import com.porter.qwerte.stash.KitTypes;
import com.porter.qwerte.stash.Kits;
import com.porter.qwerte.stash.StashIndex;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The Kit Builder tab (server mode; KIT-CONTRACT v2, revisions 3 and 4): the stash's kit templates listed by category with their stock, and an editor
 * with a 27-slot grid. Uses the Stash Manager server's routes with the window's login ({@code GET /api/v1/kits/templates}, {@code POST
 * /api/v1/kits/template}, {@code /template/delete}, {@code /category}, {@code POST /api/v1/orders}). Permissions: viewing needs {@code view}, changing
 * needs {@code settings}, ordering {@code order}. A forced streamer sees it read-only, the names the server gives ("Kit n"); a voluntary streamer sees
 * "Kit n" and "Chest n" in place of names, labels and positions. The poll never opens the password prompt; the page is rebuilt only when what it
 * shows changed, and never from a poll while the editor is open (it would pull a text box out from under the user).
 */
public final class BuilderTab {
    private static final long POLL_MS = 5_000;
    private static final long RETRY_MS = 5_000;
    private static final long CHECK_MS = 500;
    private static final long ARM_MS = 5_000;

    private final ServerTabHost host;
    private final Supplier<IndexModel> indexSupplier;
    private GuiTheme theme;
    private WContainer root;

    private String stashKey = "";
    private BuilderModel.Data data;
    private KitsModel.Data kits;
    private String error = "";
    private String dataSig = "";
    private String builtSig = "";
    private boolean busy;
    private boolean writing;
    private int token;
    private long nextGet;
    private long nextCheck;

    // the editor
    private Draft editing;
    private int selected = -1;
    private String search = "";
    private boolean allItems;
    private String message = "";
    private String newCategory;
    private String keepText = "0";
    private String armedKey = "";
    private long armedUntil;
    private final Map<String, String> orderQty = new LinkedHashMap<>();
    private String importNote = "";
    private WTextBox countBox;

    public BuilderTab(ServerTabHost host, Supplier<IndexModel> index) {
        this.host = host;
        this.indexSupplier = index;
    }

    public void reset() {
        token++;
        busy = false;
        writing = false;
        data = null;
        kits = null;
        error = "";
        dataSig = "";
        builtSig = "";
        stashKey = "";
        nextGet = 0;
        nextCheck = 0;
        editing = null;
        selected = -1;
        message = "";
        armedKey = "";
        root = null;
        importNote = "";
    }

    private boolean canWrite() {
        return host.auth().can("settings") && !host.auth().forcedStreamer();
    }

    private boolean hide() {
        return host.policy().hideNames();
    }

    private boolean hideCoords() {
        return host.policy().hideCoords();
    }

    private static String itemName(String key) {
        try {
            return StashIndex.nameOf(key);
        } catch (RuntimeException | LinkageError e) {
            return key;
        }
    }

    // ---------- per frame ----------

    public void tick(long now) {
        if (host.closed() || !host.auth().loggedIn()) return;
        StashRef s = host.selectedStash();
        String key = s == null ? "" : s.key();
        if (!key.equals(stashKey)) {
            token++;
            busy = false;
            writing = false;
            data = null;
            kits = null;
            error = "";
            dataSig = "";
            stashKey = key;
            nextGet = 0;
            editing = null;
            selected = -1;
            armedKey = "";
            host.later(host::rebuild);
            return;
        }
        if (s == null) return;
        if (!armedKey.isEmpty() && now > armedUntil) {
            armedKey = "";
            host.later(host::rebuild);
            return;
        }
        if (!busy && !writing && now >= nextGet) fetch(now, s);
        if (now < nextCheck) return;
        nextCheck = now + CHECK_MS;
        // While the editor is open only the user's own actions rebuild the page
        if (editing == null && !builtSig.equals(sig()) && !(root != null && TabFocus.anyFocused(root))) host.later(host::rebuild);
    }

    private String sig() {
        return dataSig + "|" + hide() + hideCoords() + canWrite() + writing + "|" + armedKey + "|" + importNote + "|" + message;
    }

    private void fetch(long now, StashRef s) {
        busy = true;
        nextGet = now + POLL_MS;
        int t = token;
        Map<String, String> query = new LinkedHashMap<>();
        query.put("server", s.server());
        query.put("stash", s.stash());
        host.client().get("/api/v1/kits/templates", query, r -> {
            if (t != token || host.closed()) return;
            busy = false;
            if (r.ok()) {
                BuilderModel.Data d = BuilderModel.parse(r.body());
                if (d == null) {
                    error = "The server's answer wasn't a kit list.";
                    data = null;
                } else {
                    error = "";
                    data = d;
                }
                dataSig = dataSig();
            } else if (r.kind() == ApiError.Kind.LOGIN || r.kind() == ApiError.Kind.MUST_CHANGE) {
                nextGet = System.currentTimeMillis() + RETRY_MS;
                host.failed(r);
            } else if (r.kind() == ApiError.Kind.CANCELLED || r.kind() == ApiError.Kind.NOT_MODIFIED) {
                nextGet = System.currentTimeMillis() + RETRY_MS;
            } else {
                nextGet = System.currentTimeMillis() + RETRY_MS;
                data = null;
                error = r.status() == 404 ? "That stash isn't available. (A server without the Kit Builder answers the same: update the Stash Manager server.)"
                    : r.status() == 403 ? "Your account can't see kits." : "Can't reach the server. Trying again.";
                dataSig = dataSig();
            }
        });
        // The containers a kit may be kept in come from the Kits page's answer (only needed while editing)
        if (editing != null) {
            host.client().get("/api/v1/kits", query, r -> {
                if (t != token || host.closed() || !r.ok()) return;
                kits = KitsModel.parse(r.body());
            });
        }
    }

    private String dataSig() {
        return (error.isEmpty() ? "" : error) + (data == null ? "-" : Integer.toHexString(data.hashCode()));
    }

    // ---------- widgets ----------

    private static WFlow flow(WContainer parent, double width) {
        WFlow f = parent.add(new WFlow()).expandX().widget();
        f.maxWidth = width;
        f.gapX = 6;
        f.gapY = 4;
        return f;
    }

    public void build(WContainer parent, GuiTheme theme) {
        this.theme = theme;
        this.root = parent;
        builtSig = sig();
        Ui.pageTitle(parent, theme, "kit builder");
        double inner = PageRoom.width(parent, 16);
        if (host.selectedStash() == null) {
            parent.add(Ui.wrap(theme, "No stash selected.", PageRoom.width(parent, 0))).expandX();
            return;
        }
        if (data == null) {
            boolean failed = !error.isEmpty();
            parent.add(Ui.wrap(theme, failed ? error : "Loading...", PageRoom.width(parent, 0))).expandX().widget().color(failed ? Ui.err(theme) : Ui.dim(theme));
            return;
        }
        if (editing != null) buildEditor(parent, inner);
        else buildList(parent, inner);
    }

    // ----- the list -----

    private void buildList(WContainer parent, double inner) {
        BuilderModel.Data d = data;
        boolean can = canWrite() && !writing;
        SmCard top = Ui.card(parent, "Kits");
        top.add(Ui.wrap(theme, "Kit templates of this stash. A box with the same items is a kit of its category and goes to that category's chest; a part-built one with the kit's name is kept and topped up.", inner)).expandX();
        if (!d.capability().kitOrders()) top.add(Ui.wrap(theme, BuilderModel.NO_KIT_ORDERS, inner)).expandX().widget().color(Ui.warn(theme));
        else if (!d.capability().craft()) top.add(Ui.muted(theme, BuilderModel.NO_CRAFT, inner)).expandX();
        if (!canWrite()) top.add(Ui.muted(theme, host.auth().forcedStreamer() ? "Streamer mode: the kit list is read-only." : "You can look, but changing kits needs the \"settings\" permission.", inner)).expandX();
        WFlow bar = flow(top, inner);
        if (can && d.templates().size() < d.maxTemplates()) {
            SmButton fresh = (SmButton) bar.add(theme.button("New kit")).widget();
            fresh.pill = true;
            fresh.primary = true;
            fresh.action = () -> startEditing(new Draft());
            if (!d.presets().isEmpty()) {
                List<String> names = new ArrayList<>();
                names.add("From a preset...");
                for (BuilderModel.Preset p : d.presets()) names.add(d.categoryName(p.category()) + ": " + p.name());
                WDropdown<String> dd = bar.add(theme.dropdown(names.toArray(new String[0]), names.get(0))).widget();
                dd.action = () -> {
                    int i = names.indexOf(dd.get()) - 1;
                    if (i >= 0) startEditing(Draft.ofPreset(d.presets().get(i)));
                };
            }
            if (!Kits.all().isEmpty()) {
                boolean armedImport = armedKey.equals("import");
                SmButton imp = (SmButton) bar.add(theme.button(armedImport ? "Confirm import?" : "Import local kits")).widget();
                imp.pill = true;
                imp.tooltip = "Makes a kit template of every kit saved in this client (Kit builder), skipping kits of one kind of item and copies of an existing template. Local kits are not deleted.";
                imp.action = () -> {
                    if (!armedKey.equals("import")) {
                        StashRef st = host.selectedStash();
                        armedKey = "import";
                        armedUntil = System.currentTimeMillis() + ARM_MS;
                        host.notice("Import " + Kits.all().size() + " local kit" + (Kits.all().size() == 1 ? "" : "s") + " into "
                            + (hide() || st == null ? "this stash" : Text.sanitize(st.stash(), 40)) + "? Click Confirm import? to do it. (Local kits are saved per client, not per server.)");
                        host.later(host::rebuild);
                        return;
                    }
                    armedKey = "";
                    importLocal();
                };
            }
        }
        if (!importNote.isEmpty()) top.add(Ui.wrap(theme, importNote, inner)).expandX().widget().color(Ui.dim(theme));

        if (d.templates().isEmpty()) {
            parent.add(Ui.muted(theme, "No kits yet. Make one with New kit, or from a preset.", inner)).expandX();
            return;
        }
        int number = 0;
        List<String> order = new ArrayList<>();
        for (KitsV2.TypeWords t : KitsV2.TYPES) order.add(t.id());
        for (BuilderModel.Category c : d.categories()) order.add(c.id());
        for (String cat : order) {
            List<BuilderModel.Template> group = new ArrayList<>();
            for (BuilderModel.Template t : d.templates()) if (t.category().equals(cat)) group.add(t);
            if (group.isEmpty()) continue;
            SmCard card = Ui.card(parent, d.categoryName(cat));
            for (BuilderModel.Template t : group) {
                templateRow(card, d, t, number++, inner, can);
            }
        }
    }

    private void templateRow(WContainer card, BuilderModel.Data d, BuilderModel.Template t, int index, double inner, boolean can) {
        boolean hide = hide();
        WFlow line = flow(card, inner);
        line.add(Ui.bold(theme, hide ? "Kit " + (index + 1) : t.name()));
        BuilderModel.Stock stock = d.stock(t.id());
        String restock = BuilderModel.restockText(d.restock(), t.id());
        line.add(Ui.muted(theme, BuilderModel.stockText(stock, BuilderTab::itemName) + (restock.isEmpty() ? "" : " · " + restock)));
        // a small preview of the kinds in the kit
        WItemGrid preview = card.add(new WItemGrid(host::graphics).compact(9, 1)).widget();
        List<WItemGrid.Cell> cells = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (BuilderModel.Slot s : t.slots()) {
            if (s == null || !seen.add(s.base()) || cells.size() >= 9) continue;
            cells.add(new WItemGrid.Cell(ItemStacks.displayId(s.key()), "", itemName(s.base()), s.base()));
        }
        preview.setCells(cells);
        if (!stock.missing().isEmpty() && stock.incomplete() > 0) card.add(Ui.muted(theme, "Needs to complete: " + shortList(stock.missing()), inner)).expandX();
        String where = t.effectiveChest().isEmpty() ? "no chest set" : KitsModel.place(kits == null ? emptyKits() : kits, t.effectiveChest(), hideCoords());
        card.add(Ui.muted(theme, "Kept in: " + (hideCoords() ? "chest" : where) + (t.effectiveChestStatus().equals("full") ? " (full)" : t.effectiveChestStatus().equals("missing") ? " (missing)" : "")
            + (t.keep() > 0 ? " · restock keeps " + t.keep() : ""), inner)).expandX();
        for (BuilderModel.Slot s : t.slots()) {
            if (s != null && !s.resolved()) {
                card.add(Ui.wrap(theme, BuilderModel.UNRESOLVED, inner)).expandX().widget().color(Ui.err(theme));
                break;
            }
        }
        WFlow act = flow(card, inner);
        boolean orders = host.auth().can("order") && !host.auth().forcedStreamer() && d.capability().kitOrders() && !writing;
        if (orders) {
            act.add(Ui.muted(theme, "Order"));
            WTextBox qty = act.add(theme.textBox(orderQty.getOrDefault(t.id(), "1"), "")).minWidth(theme.scale(36)).widget();
            qty.action = () -> orderQty.put(t.id(), qty.get());
            SmButton order = (SmButton) act.add(theme.button("Order")).widget();
            order.pill = true;
            order.tooltip = "Orders this many complete kits (ready copies first, the rest built).";
            order.action = () -> placeOrder(t);
        }
        if (can) {
            SmButton edit = (SmButton) act.add(theme.button("Edit")).widget();
            edit.pill = true;
            edit.action = () -> startEditing(Draft.of(t));
            SmButton dup = (SmButton) act.add(theme.button("Duplicate")).widget();
            dup.pill = true;
            dup.action = () -> {
                Draft c = Draft.of(t).copy();
                c.name = uniqueCopyName(t.name());
                startEditing(c);
            };
            boolean armed = armedKey.equals("delete:" + t.id());
            SmButton del = (SmButton) act.add(theme.button(armed ? "Confirm delete?" : "Delete")).widget();
            del.pill = true;
            del.danger = true;
            del.tooltip = "Deletes the kit template (boxes of it stay, but are no longer a kit of it). Click twice.";
            del.action = () -> {
                if (!armedKey.equals("delete:" + t.id())) {
                    armedKey = "delete:" + t.id();
                    armedUntil = System.currentTimeMillis() + ARM_MS;
                    host.notice("Delete \"" + (hide ? "this kit" : t.name()) + "\"? Click Confirm delete? to do it.");
                    host.later(host::rebuild);
                    return;
                }
                armedKey = "";
                delete(t);
            };
        }
    }

    private static KitsModel.Data emptyKits() {
        return new KitsModel.Data(0, "", KitsModel.Scoring.DEFAULT, new KitsModel.Bot(false, false, false), false, List.of(), "", List.of(), 0);
    }

    private String shortList(List<KitsV2.Missing> missing) {
        List<String> parts = new ArrayList<>();
        for (KitsV2.Missing m : missing) {
            if (parts.size() >= 4) break;
            parts.add(itemName(m.key()) + " ×" + m.count());
        }
        return String.join(", ", parts) + (missing.size() > 4 ? ", ..." : "");
    }

    private String uniqueCopyName(String base) {
        String name = base.length() > 43 ? base.substring(0, 43) : base;
        String candidate = name + " (copy)";
        for (int n = 2; BuilderModel.nameProblem(candidate, null, data.templates()) != null && n < 100; n++) candidate = name + " (copy " + n + ")";
        return candidate;
    }

    private void startEditing(Draft draft) {
        editing = draft;
        selected = -1;
        message = "";
        search = "";
        allItems = false;
        newCategory = null;
        keepText = String.valueOf(draft.keep);
        // the containers a kit may be kept in
        nextGet = 0;
        host.later(host::rebuild);
    }

    // ----- the editor -----

    private static Map<String, net.minecraft.world.item.Item> itemsById;

    private int maxStack(String base) {
        IndexModel index = indexSupplier.get();
        for (IndexModel.Item i : index.items()) if (i.id().equals(base)) return Math.max(1, i.maxStack());
        try {
            if (itemsById == null) {
                itemsById = new java.util.HashMap<>();
                for (net.minecraft.world.item.Item item : BuiltInRegistries.ITEM) itemsById.put(BuiltInRegistries.ITEM.getKey(item).toString(), item);
            }
            net.minecraft.world.item.Item item = itemsById.get(base);
            return item == null ? 64 : Math.max(1, new ItemStack(item).getMaxStackSize());
        } catch (RuntimeException | LinkageError e) {
            return 64;
        }
    }

    private void buildEditor(WContainer parent, double inner) {
        Draft d = editing;
        BuilderModel.Data data = this.data;
        boolean hide = hide();
        SmCard card = Ui.card(parent, d.id == null ? "New kit" : "Edit kit");

        // Name
        WFlow name = flow(card, inner);
        name.add(Ui.muted(theme, "Name"));
        if (hide && d.id != null) {
            name.add(theme.label("Kit"));
        } else {
            WTextBox nb = name.add(theme.textBox(d.name, "Kit name")).minWidth(theme.scale(180)).widget();
            nb.action = () -> d.name = nb.get();
        }

        // Kind of kit
        WFlow kind = flow(card, inner);
        kind.add(Ui.muted(theme, "Kind of kit"));
        List<String> cats = new ArrayList<>();
        List<String> catIds = new ArrayList<>();
        for (KitsV2.TypeWords t : KitsV2.TYPES) {
            cats.add(t.name());
            catIds.add(t.id());
        }
        for (BuilderModel.Category c : data.categories()) {
            cats.add(c.name());
            catIds.add(c.id());
        }
        cats.add("+ New category...");
        catIds.add("+");
        int ci = Math.max(0, catIds.indexOf(d.category));
        WDropdown<String> catDd = kind.add(theme.dropdown(cats.toArray(new String[0]), cats.get(ci))).widget();
        catDd.action = () -> {
            int i = cats.indexOf(catDd.get());
            if (i < 0) return;
            if (catIds.get(i).equals("+")) {
                newCategory = "";
            } else {
                d.category = catIds.get(i);
                newCategory = null;
            }
            host.later(host::rebuild);
        };
        if (newCategory != null) {
            WFlow nc = flow(card, inner);
            nc.add(Ui.muted(theme, "New category"));
            WTextBox ncb = nc.add(theme.textBox(newCategory, "Name")).minWidth(theme.scale(140)).widget();
            ncb.action = () -> newCategory = ncb.get();
            SmButton create = (SmButton) nc.add(theme.button("Create")).widget();
            create.pill = true;
            create.action = this::createCategory;
        }

        // Kept in, colour, restock
        WFlow more = flow(card, inner);
        more.add(Ui.muted(theme, "Kept in"));
        List<String> chestTexts = new ArrayList<>();
        List<String> chestIds = new ArrayList<>();
        chestTexts.add(data.categoryName(d.category) + " chest");
        chestIds.add("");
        if (kits != null && !hideCoords()) {
            for (int i = 0; i < kits.containers().size(); i++) {
                KitsModel.Container c = kits.containers().get(i);
                if (!c.pickable() && !c.id().equals(d.chest)) continue;
                chestTexts.add(KitsModel.optionText(c, i, false));
                chestIds.add(c.id());
            }
        }
        if (!d.chest.isEmpty() && !chestIds.contains(d.chest)) {
            chestTexts.add(hideCoords() ? "A chest" : KitsModel.coords(d.chest));
            chestIds.add(d.chest);
        }
        int chi = Math.max(0, chestIds.indexOf(d.chest));
        WDropdown<String> chestDd = more.add(theme.dropdown(chestTexts.toArray(new String[0]), chestTexts.get(chi))).widget();
        chestDd.action = () -> {
            int i = chestTexts.indexOf(chestDd.get());
            if (i >= 0) d.chest = chestIds.get(i);
        };
        more.add(Ui.muted(theme, "Shulker colour"));
        List<String> colours = new ArrayList<>();
        colours.add("Any");
        for (String c : BuilderModel.COLORS) colours.add(c.equals("plain") ? "Plain" : title(c));
        List<String> colourIds = new ArrayList<>();
        colourIds.add("");
        colourIds.addAll(BuilderModel.COLORS);
        WDropdown<String> colDd = more.add(theme.dropdown(colours.toArray(new String[0]), colours.get(Math.max(0, colourIds.indexOf(d.color))))).widget();
        colDd.action = () -> {
            int i = colours.indexOf(colDd.get());
            if (i >= 0) d.color = colourIds.get(i);
        };
        WFlow keep = flow(card, inner);
        keep.add(Ui.muted(theme, "Restock: keep (0 = off)"));
        WTextBox kb = keep.add(theme.textBox(keepText, "0")).minWidth(theme.scale(36)).widget();
        kb.action = () -> {
            keepText = kb.get();
            try {
                int v = Integer.parseInt(keepText.trim());
                if (v >= 0 && v <= 27) d.keep = v;
            } catch (NumberFormatException e) {
                // kept as it was until it is a number
            }
        };
        if (!data.capability().restock()) keep.add(Ui.muted(theme, "This bot can't restock yet."));

        // The 27-slot grid
        card.add(Ui.muted(theme, "The kit's 27 slots: click a slot to pick it, right-click to empty it. Click an item below to put it in the picked slot.", inner)).expandX();
        WItemGrid grid = card.add(new WItemGrid(host::graphics).compact(9, 3)).widget();
        List<WItemGrid.Cell> cells = new ArrayList<>();
        for (int i = 0; i < BuilderModel.SLOTS; i++) {
            EditSlot s = d.slots[i];
            if (s == null) cells.add(new WItemGrid.Cell(ItemStack.EMPTY, "", "Empty slot " + (i + 1), i));
            else cells.add(new WItemGrid.Cell(ItemStacks.displayId(s.key()), s.count() > 1 ? String.valueOf(s.count()) : "",
                itemName(s.base()) + " ×" + s.count() + (s.any() ? " (any variant)" : ""), i));
        }
        grid.setCells(cells);
        if (selected >= 0) grid.select(selected);
        grid.red = cell -> cell.ref() instanceof Integer i && d.slots[i] != null && !resolved(d.slots[i]);
        grid.onCell = (cell, button) -> {
            if (!(cell.ref() instanceof Integer i)) return;
            if (button == 1) {
                d.slots[i] = null;
                selected = i;
            } else {
                selected = i;
            }
            host.later(host::rebuild);
        };

        // The picked slot: count and any-variant
        if (selected >= 0 && d.slots[selected] != null) {
            EditSlot s = d.slots[selected];
            WFlow sel = flow(card, inner);
            sel.add(Ui.muted(theme, "Slot " + (selected + 1) + ": " + itemName(s.base()) + " ×"));
            countBox = sel.add(theme.textBox(String.valueOf(s.count()), "1")).minWidth(theme.scale(36)).widget();
            WTextBox cb = countBox;
            int at = selected;
            cb.action = () -> {
                try {
                    int v = Integer.parseInt(cb.get().trim());
                    int max = maxStack(d.slots[at].base());
                    d.slots[at] = new EditSlot(d.slots[at].key(), Math.max(1, Math.min(max, v)));
                } catch (NumberFormatException e) {
                    // kept until it is a number
                }
            };
            SmButton any = (SmButton) sel.add(theme.button(s.any() ? "Exact variant" : "Any variant")).widget();
            any.pill = true;
            any.tooltip = "Any variant: enchanted or plain, any potion, any firework. Exact: the very item picked from the stash.";
            any.action = () -> {
                EditSlot cur = d.slots[at];
                if (cur == null) return;
                if (cur.any()) host.notice("Pick the item again from the list below to make this slot exact.");
                else d.slots[at] = new EditSlot(cur.base() + "#*", cur.count());
                host.later(host::rebuild);
            };
            SmButton clear = (SmButton) sel.add(theme.button("Empty slot")).widget();
            clear.pill = true;
            clear.action = () -> {
                d.slots[at] = null;
                host.later(host::rebuild);
            };
        }

        // The item picker: the stash's items, or every item of the game
        WFlow find = flow(card, inner);
        find.add(Ui.muted(theme, "Items"));
        WTextBox sb = find.add(theme.textBox(search, "Search")).minWidth(theme.scale(140)).widget();
        sb.action = () -> {
            search = sb.get();
            refillPicker();
        };
        SmButton all = (SmButton) find.add(theme.button(allItems ? "Stash items" : "All items")).widget();
        all.pill = true;
        all.tooltip = allItems ? "Show only what the stash holds" : "Show every item of the game";
        all.action = () -> {
            allItems = !allItems;
            host.later(host::rebuild);
        };
        picker = card.add(new WItemGrid(host::graphics).compact(9, 5)).widget();
        picker.onCell = (cell, button) -> {
            if (!(cell.ref() instanceof String key)) return;
            int slot = selected >= 0 ? selected : firstFree(d);
            if (slot < 0) {
                host.notice("All 27 slots are in use: empty one first.");
                return;
            }
            d.slots[slot] = new EditSlot(key, 1);
            selected = slot;
            host.later(host::rebuild);
        };
        refillPicker();

        // Live checks
        List<String> problems = BuilderModel.problems(d, data.templates(), this::maxStack);
        for (EditSlot s : d.slots) {
            if (s != null && !resolved(s)) {
                card.add(Ui.wrap(theme, BuilderModel.UNRESOLVED, inner)).expandX().widget().color(Ui.err(theme));
                break;
            }
        }
        for (String p : problems) card.add(Ui.wrap(theme, p, inner)).expandX().widget().color(Ui.err(theme));
        IndexModel index = indexSupplier.get();
        BuilderModel.Builds b = BuilderModel.builds(d, key -> {
            IndexModel.Item it = index.item(key);
            return it == null ? 0 : it.count();
        }, base -> {
            long n = 0;
            for (IndexModel.Item it : index.items()) if (it.id().equals(base)) n += it.count();
            return n;
        });
        card.add(Ui.muted(theme, BuilderModel.buildsText(b, BuilderTab::itemName), inner)).expandX();
        card.add(Ui.muted(theme, BuilderModel.MATCH_NOTE, inner)).expandX();
        if (!data.capability().kitOrders()) card.add(Ui.muted(theme, BuilderModel.NO_KIT_ORDERS, inner)).expandX();
        if (!message.isEmpty()) card.add(Ui.wrap(theme, message, inner)).expandX().widget().color(Ui.err(theme));

        WFlow end = flow(card, inner);
        if (problems.isEmpty() && canWrite() && !writing) {
            SmButton save = (SmButton) end.add(theme.button("Save")).widget();
            save.pill = true;
            save.primary = true;
            save.action = this::save;
        } else {
            end.add(Ui.muted(theme, writing ? "Saving..." : !canWrite() ? "Save (no permission)" : "Save"));
        }
        SmButton cancel = (SmButton) end.add(theme.button("Cancel")).widget();
        cancel.pill = true;
        cancel.action = () -> {
            editing = null;
            selected = -1;
            message = "";
            host.later(host::rebuild);
        };
    }

    private WItemGrid picker;

    /** An exact variant key the stash index lists; a plain id and an any-variant slot always resolve. */
    private boolean resolved(EditSlot s) {
        if (s.any() || s.key().indexOf('#') < 0) return true;
        return indexSupplier.get().item(s.key()) != null;
    }

    private static int firstFree(Draft d) {
        for (int i = 0; i < BuilderModel.SLOTS; i++) if (d.slots[i] == null) return i;
        return -1;
    }

    private static String title(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split("_")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** The picker's cells: the stash's items (or every item) matching the search, shulker boxes left out (a kit can't hold one). */
    private void refillPicker() {
        if (picker == null) return;
        String q = search.trim().toLowerCase(java.util.Locale.ROOT);
        List<WItemGrid.Cell> cells = new ArrayList<>();
        if (allItems) {
            for (net.minecraft.world.item.Item item : BuiltInRegistries.ITEM) {
                String id = BuiltInRegistries.ITEM.getKey(item).toString();
                if (id.endsWith("shulker_box") || id.equals("minecraft:air") || (!q.isEmpty() && !id.contains(q))) continue;
                if (cells.size() >= 600) break;
                cells.add(new WItemGrid.Cell(ItemStacks.displayId(id), "", itemName(id), id));
            }
        } else {
            for (IndexModel.Item it : indexSupplier.get().items()) {
                if (it.isShulkerBox() || (!q.isEmpty() && !it.hay().contains(q))) continue;
                if (cells.size() >= 600) break;
                cells.add(new WItemGrid.Cell(ItemStacks.display(it), it.count() > 1 ? String.valueOf(it.count()) : "", it.name() + " (" + it.count() + ")", it.key()));
            }
        }
        picker.setCells(cells);
    }

    // ---------- writes ----------

    private void save() {
        StashRef s = host.selectedStash();
        if (s == null || writing || editing == null || !canWrite()) return;
        // the typed restock count and slot count are applied as typed
        Draft d = editing;
        writing = true;
        int t = ++token;
        busy = false;
        host.write("/api/v1/kits/template", BuilderModel.saveBody(s.server(), s.stash(), d), r -> {
            if (t != token || host.closed()) return;
            writing = false;
            if (r.ok()) {
                BuilderModel.Data nd = BuilderModel.parse(r.body());
                if (nd != null) {
                    data = nd;
                    dataSig = dataSig();
                }
                editing = null;
                selected = -1;
                message = "";
                host.notice("Saved. The bot sorts boxes of this kit from its next report.");
            } else if (r.kind() == ApiError.Kind.CANCELLED) {
                message = "Not saved: the password was not confirmed.";
            } else if (r.kind() == ApiError.Kind.LOGIN || r.kind() == ApiError.Kind.MUST_CHANGE) {
                host.failed(r);
            } else if (r.kind() == ApiError.Kind.RATE_LIMIT) {
                message = "Too many changes. Wait " + r.wait429() + " s.";
            } else if (r.kind() == ApiError.Kind.NETWORK) {
                message = "Can't reach the server.";
            } else {
                String why = Text.sanitize(r.error(), 160);
                message = why.isEmpty() ? "Couldn't save the kit." : why;
            }
            token++;
            nextGet = 0;
            host.later(host::rebuild);
        });
        host.later(host::rebuild);
    }

    private void delete(BuilderModel.Template t) {
        StashRef s = host.selectedStash();
        if (s == null || writing || !canWrite()) return;
        writing = true;
        int tok = ++token;
        JsonObject body = new JsonObject();
        body.addProperty("server", s.server());
        body.addProperty("stash", s.stash());
        body.addProperty("id", t.id());
        host.write("/api/v1/kits/template/delete", body, r -> {
            if (tok != token || host.closed()) return;
            writing = false;
            if (r.ok()) {
                BuilderModel.Data nd = BuilderModel.parse(r.body());
                if (nd != null) {
                    data = nd;
                    dataSig = dataSig();
                }
                host.notice("Deleted.");
            } else if (r.kind() == ApiError.Kind.LOGIN || r.kind() == ApiError.Kind.MUST_CHANGE) {
                host.failed(r);
            } else {
                String why = Text.sanitize(r.error(), 120);
                host.notice(why.isEmpty() ? "Couldn't delete the kit." : why);
            }
            token++;
            nextGet = 0;
            host.later(host::rebuild);
        });
        host.later(host::rebuild);
    }

    private void createCategory() {
        StashRef s = host.selectedStash();
        String name = newCategory == null ? "" : newCategory.trim();
        if (s == null || writing || !canWrite() || name.isEmpty()) {
            message = name.isEmpty() ? "The category needs a name." : message;
            host.later(host::rebuild);
            return;
        }
        writing = true;
        int tok = ++token;
        JsonObject body = new JsonObject();
        body.addProperty("server", s.server());
        body.addProperty("stash", s.stash());
        body.addProperty("name", name);
        host.write("/api/v1/kits/category", body, r -> {
            if (tok != token || host.closed()) return;
            writing = false;
            if (r.ok()) {
                BuilderModel.Data nd = BuilderModel.parse(r.body());
                if (nd != null) {
                    data = nd;
                    dataSig = dataSig();
                    // the new category is the one just made: the response carries its id
                    String id = r.obj().has("id") && r.obj().get("id").isJsonPrimitive() ? r.obj().get("id").getAsString() : "";
                    if (editing != null && nd.categories().stream().anyMatch(c -> c.id().equals(id))) editing.category = id;
                }
                newCategory = null;
                message = "";
            } else if (r.kind() == ApiError.Kind.LOGIN || r.kind() == ApiError.Kind.MUST_CHANGE) {
                host.failed(r);
            } else {
                String why = Text.sanitize(r.error(), 160);
                message = why.isEmpty() ? "Couldn't make the category." : why;
            }
            token++;
            nextGet = 0;
            host.later(host::rebuild);
        });
    }

    private void placeOrder(BuilderModel.Template t) {
        StashRef s = host.selectedStash();
        if (s == null || writing) return;
        int count;
        try {
            count = Integer.parseInt(orderQty.getOrDefault(t.id(), "1").trim());
        } catch (NumberFormatException e) {
            host.notice("The number of kits must be 1 to 54.");
            return;
        }
        if (count < 1 || count > 54) {
            host.notice("The number of kits must be 1 to 54.");
            return;
        }
        writing = true;
        int tok = ++token;
        JsonObject body = new JsonObject();
        body.addProperty("server", s.server());
        body.addProperty("stash", s.stash());
        JsonArray lines = new JsonArray();
        JsonObject line = new JsonObject();
        line.addProperty("type", "kit");
        line.addProperty("key", t.id());
        line.addProperty("count", count);
        lines.add(line);
        body.add("lines", lines);
        host.write("/api/v1/orders", body, r -> {
            if (tok != token || host.closed()) return;
            writing = false;
            if (r.ok()) host.notice("Order placed: " + count + (count == 1 ? " kit." : " kits."));
            else if (r.kind() == ApiError.Kind.LOGIN || r.kind() == ApiError.Kind.MUST_CHANGE) host.failed(r);
            else if (r.kind() == ApiError.Kind.CANCELLED) host.notice("Not ordered: the password was not confirmed.");
            else {
                String why = Text.sanitize(r.error(), 200);
                host.notice(why.isEmpty() ? "Couldn't place the order." : why);
            }
            token++;
            nextGet = 0;
            host.later(host::rebuild);
        });
        host.later(host::rebuild);
    }

    // ---------- Import local kits ----------

    private void importLocal() {
        StashRef s = host.selectedStash();
        if (s == null || writing || data == null || !canWrite()) return;
        List<BuilderModel.LocalKit> local = new ArrayList<>();
        for (Kit k : Kits.all()) {
            List<BuilderModel.ImportSlot> slots = new ArrayList<>();
            for (int i = 0; i < Kit.SLOTS; i++) {
                ItemStack stack = k.stackAt(i);
                if (stack == null || stack.isEmpty()) {
                    slots.add(null);
                    continue;
                }
                String key = StashIndex.keyOf(stack), match = StashIndex.matchKeyOf(stack);
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (key == null || match == null) {
                    slots.add(null);
                    continue;
                }
                slots.add(new BuilderModel.ImportSlot(id, key, match, Math.max(1, Math.min(stack.getMaxStackSize(), stack.getCount()))));
            }
            local.add(new BuilderModel.LocalKit(k.name, slots));
        }
        List<BuilderModel.ImportItem> plan = BuilderModel.planImport(local, data.templates(), KitTypes.shared(), s.server(), s.stash(), data.maxTemplates() - data.templates().size());
        writing = true;
        importNote = "Importing...";
        // A poll still on its way is made worthless, and polling carries on after the import
        int tok = ++token;
        busy = false;
        importNext(plan, 0, 0, new ArrayList<>(), tok);
        host.later(host::rebuild);
    }

    /** Sends the planned templates one after the other (the server takes 60 writes a minute); the summary is shown at the end. */
    private void importNext(List<BuilderModel.ImportItem> plan, int at, int done, List<String> failed, int tok) {
        while (at < plan.size() && plan.get(at).skipped()) at++;
        if (at >= plan.size()) {
            writing = false;
            importNote = BuilderModel.summary(done, plan, failed, hide());
            token++;
            busy = false;
            nextGet = 0;
            host.later(host::rebuild);
            return;
        }
        BuilderModel.ImportItem item = plan.get(at);
        // Under streamer display the kits are "Kit N", never the local names
        String shownName = hide() ? "Kit " + (at + 1) : Text.sanitize(item.name(), 40);
        int next = at + 1;
        host.write("/api/v1/kits/template", item.template(), r -> {
            if (tok != token || host.closed()) return;
            if (r.ok()) {
                BuilderModel.Data nd = BuilderModel.parse(r.body());
                if (nd != null) {
                    data = nd;
                    dataSig = dataSig();
                }
                importNext(plan, next, done + 1, failed, tok);
            } else if (r.kind() == ApiError.Kind.LOGIN || r.kind() == ApiError.Kind.MUST_CHANGE) {
                host.failed(r);
                writing = false;
            } else if (r.kind() == ApiError.Kind.RATE_LIMIT || r.kind() == ApiError.Kind.NETWORK || r.kind() == ApiError.Kind.CANCELLED) {
                // stop here: what was sent stays, the rest can be imported again (copies of what exists are skipped then)
                failed.add(r.kind() == ApiError.Kind.RATE_LIMIT ? "too many changes, try again in " + r.wait429() + " s" : r.kind() == ApiError.Kind.CANCELLED ? "password not confirmed" : "server unreachable");
                writing = false;
                importNote = BuilderModel.summary(done, plan, failed, hide());
                token++;
                busy = false;
                nextGet = 0;
                host.later(host::rebuild);
            } else {
                String why = Text.sanitize(r.error(), 100);
                failed.add(shownName + (why.isEmpty() ? "" : ": " + why));
                importNext(plan, next, done, failed, tok);
            }
        });
    }
}
