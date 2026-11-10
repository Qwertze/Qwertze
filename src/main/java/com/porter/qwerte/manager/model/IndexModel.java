package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwerte.stash.RepairEstimate;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A stash index as {@code GET /api/v1/index} sends it, with the website's search and sorting. Pure: item ids are
 * strings, nothing of Minecraft is used. Every string from the server is cleaned with {@link Text#sanitize}.
 */
public final class IndexModel {
    public static final int SHULKER_SLOTS = 27;
    private static final int MAX_ITEMS = 200_000;
    private static final int MAX_SHULKERS = 50_000;
    private static final Pattern SHULKER_BOX = Pattern.compile("^minecraft:([a-z_]+_)?shulker_box$");

    /** The creative menu's tabs in the website's order; an unknown tab counts as "other". */
    static final List<String> TABS = List.of("building_blocks", "colored_blocks", "natural_blocks", "functional_blocks",
        "redstone_blocks", "tools_and_utilities", "combat", "food_and_drinks", "ingredients", "spawn_eggs", "op_blocks", "other");

    public enum Sort {
        CREATIVE("Creative"), COUNT("Count"), NAME("Name"), ID("Id");

        private final String label;

        Sort(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** One kind of item. {@code hay} is the lower-case text a search looks in. */
    public record Item(String key, String id, String name, String englishName, long count, int maxStack, List<String> details,
                       boolean enchanted, String tab, int tabOrder, int damage, int maxDamage, String ns, String hay,
                       boolean mending, boolean repairable, int repairBottles) {

        /** An item as before the repair feature: Mending is read from the details, repairable and bottles are worked out here. */
        public Item(String key, String id, String name, String englishName, long count, int maxStack, List<String> details,
                    boolean enchanted, String tab, int tabOrder, int damage, int maxDamage, String ns, String hay) {
            this(key, id, name, englishName, count, maxStack, details, enchanted, tab, tabOrder, damage, maxDamage, ns, hay,
                RepairEstimate.detailsHaveMending(details), RepairEstimate.repairable(RepairEstimate.facts(false, details, damage, maxStack)),
                RepairEstimate.bottlesPerItem(RepairEstimate.facts(false, details, damage, maxStack)));
        }

        /** What the repair rules look at. */
        public RepairEstimate.Facts repairFacts() {
            return new RepairEstimate.Facts(mending, damage, maxStack);
        }

        /** Remaining durability 0 to 1 of a damaged item, or -1 when it isn't damaged (or the bot doesn't say). */
        public double durabilityLeft() {
            if (maxDamage <= 0 || damage <= 0) return -1;
            return Math.max(0, Math.min(1, (double) (maxDamage - damage) / maxDamage));
        }

        public boolean isShulkerBox() {
            return SHULKER_BOX.matcher(id).matches();
        }
    }

    public record Content(int slot, String key, long count) {
    }

    /** A group of identical, filled shulker boxes. */
    public record Shulker(String key, String itemKey, String id, String name, long count, List<Content> contents, String hay) {
    }

    /**
     * What the grid shows: an item or a shulker group, with the count to draw (for a shulker box item that has filled
     * groups, only the empty boxes).
     */
    public record Entry(Item item, Shulker shulker, long count) {
        public String key() {
            return item != null ? item.key() : shulker.key();
        }

        public String id() {
            return item != null ? item.id() : shulker.id();
        }

        public String name() {
            return item != null ? item.name() : shulker.name();
        }
    }

    private final String mcVersion;
    private final String dimension;
    private final long receivedAt;
    private final List<Item> items;
    private final List<Shulker> shulkers;
    private final Map<String, Item> byKey = new HashMap<>();
    private final Map<String, List<Shulker>> groupsByItem = new HashMap<>();
    private final long containersKnown, containersTotal, containersStale;

    private IndexModel(String mcVersion, String dimension, long receivedAt, List<Item> items, List<Shulker> shulkers, long known, long total, long stale) {
        this.mcVersion = mcVersion;
        this.dimension = dimension;
        this.receivedAt = receivedAt;
        this.items = items;
        this.shulkers = shulkers;
        this.containersKnown = known;
        this.containersTotal = total;
        this.containersStale = stale;
        for (Item item : items) byKey.put(item.key(), item);
        for (Shulker g : shulkers) {
            String owner = !g.itemKey().isEmpty() ? g.itemKey() : "id:" + g.id();
            groupsByItem.computeIfAbsent(owner, k -> new ArrayList<>()).add(g);
        }
    }

    public static IndexModel empty() {
        return new IndexModel("", "", 0, List.of(), List.of(), -1, -1, -1);
    }

    /** Never null: a body that isn't an index gives an empty one. */
    public static IndexModel parse(JsonElement body) {
        JsonObject o = Json.obj(body);
        if (o == null) return empty();

        List<Item> items = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        JsonArray rawItems = Json.arr(o.get("items"));
        if (rawItems != null) {
            for (JsonElement e : rawItems) {
                Item item = parseItem(Json.obj(e));
                if (item == null || seen.put(item.key(), true) != null) continue;
                items.add(item);
                if (items.size() >= MAX_ITEMS) break;
            }
        }
        Map<String, Item> keyed = new HashMap<>();
        for (Item item : items) keyed.put(item.key(), item);

        List<Shulker> groups = new ArrayList<>();
        JsonArray rawShulkers = Json.arr(o.get("shulkers"));
        if (rawShulkers != null) {
            Map<String, Boolean> seenGroups = new HashMap<>();
            for (JsonElement e : rawShulkers) {
                Shulker g = parseShulker(Json.obj(e), keyed);
                if (g == null || seenGroups.put(g.key(), true) != null) continue;
                groups.add(g);
                if (groups.size() >= MAX_SHULKERS) break;
            }
        }

        JsonObject c = Json.obj(o.get("containers"));
        String mc = Json.str(o, "mcVersion");
        String dim = Json.str(o, "dimension");
        return new IndexModel(mc.matches("^[0-9][0-9.]{0,31}$") ? mc : "", dim.matches("^[a-z0-9_.:/-]{1,64}$") ? dim : "", Json.lng(o, "receivedAt", 0), items, groups,
            c == null ? -1 : Json.lng(c, "known", -1), c == null ? -1 : Json.lng(c, "total", -1), c == null ? -1 : Json.lng(c, "stale", -1));
    }

    private static Item parseItem(JsonObject o) {
        if (o == null) return null;
        String key = Json.str(o, "key");
        if (key.isEmpty() || key.length() > 200) return null;
        String id = Text.sanitize(Json.str(o, "id"), 100);
        String name = Text.sanitize(Json.str(o, "name"), 128);
        if (name.isEmpty()) name = id;
        String english = Text.sanitize(Json.str(o, "englishName"), 128);
        if (english.isEmpty()) english = name;
        List<String> details = new ArrayList<>();
        JsonArray d = Json.arr(o.get("details"));
        if (d != null) {
            for (JsonElement e : d) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && details.size() < 40) details.add(Text.sanitize(e.getAsString(), 200));
            }
        }
        String tab = Json.str(o, "tab");
        if (!TABS.contains(tab)) tab = "other";
        long count = Math.max(0, (long) Json.num(o, "count", 0));
        int maxStack = (int) Math.max(1, Json.num(o, "maxStack", 64));
        StringBuilder hay = new StringBuilder(name).append('\n').append(english).append('\n').append(id);
        for (String line : details) hay.append('\n').append(line);
        String ns = id.indexOf(':') < 0 ? "minecraft" : id.substring(0, id.indexOf(':'));
        int damage = (int) Math.max(0, Json.num(o, "damage", 0));
        // The bot's flag, else (an older bot) the details; the server's own repairable and repairBottles when it sent them
        RepairEstimate.Facts facts = RepairEstimate.facts(Json.bool(o, "mending"), details, damage, maxStack);
        boolean repairable = o.has("repairable") && o.get("repairable").isJsonPrimitive() && o.get("repairable").getAsJsonPrimitive().isBoolean()
            ? Json.bool(o, "repairable") : RepairEstimate.repairable(facts);
        int bottles = o.has("repairBottles") ? (int) Math.max(0, Math.min(Integer.MAX_VALUE, Json.num(o, "repairBottles", 0))) : RepairEstimate.bottlesPerItem(facts);
        return new Item(key, id, name, english, count, maxStack, List.copyOf(details), Json.bool(o, "enchanted"), tab,
            (int) Json.num(o, "tabOrder", 1_000_000), damage,
            (int) Math.max(0, Json.num(o, "maxDamage", 0)), ns.toLowerCase(Locale.ROOT), hay.toString().toLowerCase(Locale.ROOT),
            facts.mending(), repairable, repairable ? bottles : 0);
    }

    private static Shulker parseShulker(JsonObject o, Map<String, Item> items) {
        if (o == null) return null;
        String key = Json.str(o, "key");
        if (key.isEmpty() || key.length() > 200) return null;
        List<Content> contents = new ArrayList<>();
        JsonArray raw = Json.arr(o.get("contents"));
        if (raw != null) {
            for (JsonElement e : raw) {
                JsonObject c = Json.obj(e);
                if (c == null) continue;
                String ck = Json.str(c, "key");
                int slot = (int) Json.num(c, "slot", -1);
                if (ck.isEmpty() || slot < 0 || slot >= SHULKER_SLOTS || contents.size() >= SHULKER_SLOTS) continue;
                contents.add(new Content(slot, ck, Math.max(0, (long) Json.num(c, "count", 0))));
            }
        }
        String id = Text.sanitize(Json.str(o, "id"), 100);
        String itemKey = Json.str(o, "itemKey");
        Item base = items.get(!itemKey.isEmpty() ? itemKey : id);
        String name = Text.sanitize(Json.str(o, "name"), 128);
        if (name.isEmpty()) name = base != null ? base.name() : id;
        String english = base != null ? base.englishName() : name;

        // The website searches the group's own name and its biggest contents too
        Map<String, Long> totals = new LinkedHashMap<>();
        for (Content c : contents) totals.merge(c.key(), c.count(), Long::sum);
        List<Map.Entry<String, Long>> top = new ArrayList<>(totals.entrySet());
        top.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder hay = new StringBuilder(name).append('\n').append(english).append('\n').append(id);
        for (int i = 0; i < Math.min(4, top.size()); i++) {
            Item it = items.get(top.get(i).getKey());
            hay.append('\n').append(it != null ? it.name() : Text.sanitize(top.get(i).getKey(), 100)).append(" x").append(top.get(i).getValue());
        }
        if (top.size() > 4) hay.append("\n...and ").append(top.size() - 4).append(" more");
        hay.append('\n').append(contents.size()).append(" stacks");
        return new Shulker(key, itemKey, id, name, Math.max(0, (long) Json.num(o, "count", 0)), List.copyOf(contents),
            hay.toString().toLowerCase(Locale.ROOT));
    }

    // ---------- reading ----------

    /** The dimension the index was read in ("minecraft:overworld"), or "" when the server did not say. */
    public String dimension() {
        return dimension;
    }

    public String mcVersion() {
        return mcVersion;
    }

    public long receivedAt() {
        return receivedAt;
    }

    public List<Item> items() {
        return Collections.unmodifiableList(items);
    }

    public List<Shulker> shulkers() {
        return Collections.unmodifiableList(shulkers);
    }

    public Item item(String key) {
        return byKey.get(key);
    }

    /** -1 when the server doesn't say. */
    public long containersKnown() {
        return containersKnown;
    }

    public long containersTotal() {
        return containersTotal;
    }

    public long containersStale() {
        return containersStale;
    }

    /** The filled groups of a shulker box item (matched by item key, or by id for groups of older indexes). */
    public List<Shulker> groupsOf(Item item) {
        List<Shulker> byItemKey = groupsByItem.get(item.key());
        List<Shulker> byId = groupsByItem.get("id:" + item.id());
        if (byId == null) return byItemKey != null ? byItemKey : List.of();
        if (byItemKey == null) return byId;
        List<Shulker> both = new ArrayList<>(byItemKey);
        both.addAll(byId);
        return both;
    }

    /**
     * Boxes of this kind that are empty: all of them minus the filled groups (PROTOCOL: availability of an empty
     * shulker line). 0 for anything that isn't a shulker box or isn't in the index.
     */
    public long emptyShulkerAvailable(String itemKey) {
        Item item = byKey.get(itemKey);
        if (item == null || !item.isShulkerBox()) return 0;
        long filled = 0;
        for (Shulker g : groupsOf(item)) filled += g.count();
        return Math.max(0, item.count() - filled);
    }

    // ---------- search and sort ----------

    private record Query(List<String> terms, List<String> namespaces) {
        boolean matches(String ns, String hay) {
            for (String n : namespaces) if (!ns.startsWith(n)) return false;
            for (String t : terms) if (!hay.contains(t)) return false;
            return true;
        }
    }

    /** Terms must all appear in the name, English name, id or details; {@code @ns} limits to a namespace. */
    private static Query parseQuery(String q) {
        List<String> terms = new ArrayList<>();
        List<String> namespaces = new ArrayList<>();
        if (q != null) {
            for (String t : q.toLowerCase(Locale.ROOT).split("\\s+")) {
                if (t.isEmpty()) continue;
                if (t.charAt(0) == '@') {
                    if (t.length() > 1) namespaces.add(t.substring(1));
                } else {
                    terms.add(t);
                }
            }
        }
        return new Query(terms, namespaces);
    }

    private static String shulkerNs(Shulker g) {
        int colon = g.id().indexOf(':');
        return (colon < 0 ? "minecraft" : g.id().substring(0, colon)).toLowerCase(Locale.ROOT);
    }

    private static Collator collator() {
        Collator c = Collator.getInstance(Locale.ROOT);
        c.setStrength(Collator.PRIMARY);
        return c;
    }

    private Comparator<Item> itemSorter(Sort sort) {
        Collator c = collator();
        Comparator<Item> byKey = (a, b) -> c.compare(a.key(), b.key());
        Comparator<Item> byName = (a, b) -> c.compare(a.name(), b.name());
        Comparator<Item> byCount = (a, b) -> Long.compare(b.count(), a.count());
        switch (sort) {
            case CREATIVE:
                // Damaged copies of the same item: the most worn first, undamaged ones last
                Comparator<Item> wear = (a, b) -> Double.compare(a.durabilityLeft() < 0 ? 2 : a.durabilityLeft(), b.durabilityLeft() < 0 ? 2 : b.durabilityLeft());
                return Comparator.<Item>comparingInt(i -> TABS.indexOf(i.tab())).thenComparingInt(Item::tabOrder)
                    .thenComparing(byName).thenComparing((a, b) -> c.compare(a.id(), b.id())).thenComparing(wear).thenComparing(byKey);
            case NAME:
                return byName.thenComparing(byCount).thenComparing(byKey);
            case ID:
                return ((Comparator<Item>) (a, b) -> c.compare(a.id(), b.id())).thenComparing(byKey);
            case COUNT:
            default:
                return byCount.thenComparing(byName).thenComparing(byKey);
        }
    }

    private Comparator<Shulker> shulkerSorter(Sort sort) {
        Collator c = collator();
        Comparator<Shulker> byKey = (a, b) -> c.compare(a.key(), b.key());
        Comparator<Shulker> byName = (a, b) -> c.compare(a.name(), b.name());
        Comparator<Shulker> byCount = (a, b) -> Long.compare(b.count(), a.count());
        switch (sort) {
            case NAME:
                return byName.thenComparing(byCount).thenComparing(byKey);
            case ID:
                return ((Comparator<Shulker>) (a, b) -> c.compare(a.id(), b.id())).thenComparing(byKey);
            case CREATIVE: // groups have no creative order
            case COUNT:
            default:
                return byCount.thenComparing(byName).thenComparing(byKey);
        }
    }

    /**
     * The entries the grid shows for a search and a sort. Items list first; a shulker box item that has filled groups
     * shows only its empty boxes (when any are left) followed by the groups that match, most first. With
     * {@code shulkersOnly} just the filled groups.
     */
    public List<Entry> view(String queryText, Sort sort, boolean shulkersOnly) {
        return view(queryText, sort, shulkersOnly, null);
    }

    /** The website's special tabs: everything (Search), the filled shulkers, and the Orders page (no items of its own). */
    public static final String TAB_ALL = "all";
    public static final String TAB_SHULKERS = "shulkers";
    public static final String TAB_ORDERS = "orders";
    /** The Kits page (server mode only): shown left of Orders. */
    public static final String TAB_KITS = "kits";
    /** The Area page (the stash's area editor): shown left of Kits and Orders. */
    public static final String TAB_AREA = "area";
    /** The Kit Builder page (server mode only): shown left of Kits. */
    public static final String TAB_BUILDER = "builder";

    /** The creative tab ids in the website's order. */
    public static List<String> creativeTabs() {
        return TABS;
    }

    /**
     * The tabs to show, as on the website: Search, the creative tabs that have items (in the website's order), Shulkers
     * when any filled ones exist, then Orders.
     */
    public List<String> visibleTabs() {
        return visibleTabs(false);
    }

    /** As {@link #visibleTabs()}; {@code kits} adds the Kits tab before Orders (a window with a server, for an account that may view). */
    public List<String> visibleTabs(boolean kits) {
        return visibleTabs(kits, false);
    }

    /** As {@link #visibleTabs(boolean)}; {@code area} adds the Area tab before Kits. */
    public List<String> visibleTabs(boolean kits, boolean area) {
        return visibleTabs(kits, area, false);
    }

    /** As {@link #visibleTabs(boolean, boolean)}; {@code builder} adds the Kit Builder tab before Kits. */
    public List<String> visibleTabs(boolean kits, boolean area, boolean builder) {
        java.util.Set<String> present = new java.util.HashSet<>();
        for (Item item : items) present.add(item.tab());
        List<String> out = new ArrayList<>();
        out.add(TAB_ALL);
        for (String t : TABS) if (present.contains(t)) out.add(t);
        if (!shulkers.isEmpty()) out.add(TAB_SHULKERS);
        if (area) out.add(TAB_AREA);
        if (builder) out.add(TAB_BUILDER);
        if (kits) out.add(TAB_KITS);
        out.add(TAB_ORDERS);
        return out;
    }

    /**
     * Like {@link #view(String, Sort, boolean)} for one tab: a creative tab id shows only the items of that tab;
     * {@code null}, "all" and "orders" show everything; "shulkers" shows the filled groups. A tab the index has never
     * heard of shows nothing.
     */
    public List<Entry> view(String queryText, Sort sort, boolean shulkersOnly, String tab) {
        if (TAB_SHULKERS.equals(tab)) shulkersOnly = true;
        String only = tab == null || TAB_ALL.equals(tab) || TAB_ORDERS.equals(tab) || TAB_SHULKERS.equals(tab) ? null : tab;
        Query q = parseQuery(queryText);
        List<Entry> out = new ArrayList<>();
        if (shulkersOnly) {
            List<Shulker> shown = new ArrayList<>();
            for (Shulker g : shulkers) if (q.matches(shulkerNs(g), g.hay())) shown.add(g);
            shown.sort(shulkerSorter(sort));
            for (Shulker g : shown) out.add(new Entry(null, g, g.count()));
            return out;
        }
        List<Item> shown = new ArrayList<>();
        for (Item item : items) {
            if (only != null && !only.equals(item.tab())) continue;
            if (q.matches(item.ns(), item.hay())) {
                shown.add(item);
            } else if (item.isShulkerBox()) {
                for (Shulker g : groupsOf(item)) {
                    if (q.matches(shulkerNs(g), g.hay())) {
                        shown.add(item);
                        break;
                    }
                }
            }
        }
        shown.sort(itemSorter(sort));
        for (Item item : shown) {
            List<Shulker> groups = item.isShulkerBox() ? groupsOf(item) : List.of();
            if (groups.isEmpty()) {
                out.add(new Entry(item, null, item.count()));
                continue;
            }
            long empty = emptyShulkerAvailable(item.key());
            if (empty > 0 && q.matches(item.ns(), item.hay())) out.add(new Entry(item, null, empty));
            List<Shulker> matching = new ArrayList<>();
            for (Shulker g : groups) if (q.matches(shulkerNs(g), g.hay())) matching.add(g);
            matching.sort(shulkerSorter(Sort.COUNT));
            for (Shulker g : matching) out.add(new Entry(null, g, g.count()));
        }
        return out;
    }

    /** The items of a shulker group, resolved against the index, in slot order. */
    public List<Content> contentsOf(Shulker group) {
        List<Content> sorted = new ArrayList<>(group.contents());
        sorted.sort(Comparator.comparingInt(Content::slot));
        return sorted;
    }
}
