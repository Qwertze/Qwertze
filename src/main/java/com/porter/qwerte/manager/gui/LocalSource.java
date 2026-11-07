package com.porter.qwerte.manager.gui;

import com.porter.qwerte.manager.local.ContainerKey;
import com.porter.qwerte.manager.local.LocalHub;
import com.porter.qwerte.manager.local.LocalStore;
import com.porter.qwerte.manager.local.LocalStore.Entry;
import com.porter.qwerte.manager.local.Where;
import com.porter.qwerte.manager.model.IndexModel;
import com.porter.qwerte.manager.model.StashRef;
import com.porter.qwerte.stash.StashIndex;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * What the game has seen on its own: the containers the player opened on the server they are on now (see
 * {@link LocalHub}). One pseudo-stash per dimension, plus "all dimensions" when there is more than one. Client thread
 * only.
 */
public final class LocalSource implements DataSource {
    /** The id of the "all dimensions" pseudo-stash. */
    public static final String ALL = "*";
    /** Time per frame spent reading stored containers back. */
    private static final long PREPARE_BUDGET_NANOS = 6_000_000L;
    private static final long REBUILD_MS = 1000;

    private final LocalHub hub;
    private List<StashRef> stashes = List.of();
    private IndexModel index = IndexModel.empty();
    private Map<String, ItemStack> samples = Map.of();
    private String selected;
    private String builtDimension;
    private int builtVersion = -1;
    private long lastBuild;
    private int decodeLeft;
    private String dimensionSignature = "";

    public LocalSource(LocalHub hub) {
        this.hub = hub;
    }

    @Override
    public List<StashRef> stashes() {
        return stashes;
    }

    @Override
    public IndexModel index() {
        return index;
    }

    @Override
    public boolean ordersSupported() {
        return false;
    }

    @Override
    public ItemStack stackFor(String key) {
        return samples.get(key);
    }

    @Override
    public boolean supportsWhere() {
        return true;
    }

    @Override
    public List<Where> where(String key) {
        LocalStore store = hub.current();
        if (store == null) return List.of();
        return hub.where(entries(store), key);
    }

    @Override
    public boolean forget(ContainerKey key) {
        lastBuild = 0;
        builtVersion = -1;
        return hub.forget(key);
    }

    public String selected() {
        return selected;
    }

    public void select(String dimension) {
        if (dimension == null || dimension.equals(selected)) return;
        selected = dimension;
        builtVersion = -1;
    }

    /** False with no world: the registries needed to read the stored items are the world's. */
    public boolean available() {
        return mc.level != null && mc.player != null;
    }

    /** What to tell the user while there is nothing to show yet, or "". */
    public String notice() {
        if (!available()) return "Join a world or server to browse what this game has seen there.";
        LocalStore s = hub.current();
        if (s != null && !s.loaded()) return "Loading the saved index...";
        if (decodeLeft > 0) return "Reading stored containers... " + decodeLeft + " left";
        String warning = hub.warning();
        if (!warning.isEmpty()) return warning;
        if (s != null && s.size() == 0) return "Nothing stored yet. Open chests, barrels or shulker boxes and they are remembered here.";
        return "";
    }

    /** Forgets everything stored for this server (and its file). */
    public void clearAll() {
        hub.clearAll();
        builtVersion = -1;
    }

    /** Changes when the dimensions to pick from change. */
    public String dimensionSignature() {
        return dimensionSignature;
    }

    private List<Entry> entries(LocalStore store) {
        List<Entry> all = store.snapshot();
        if (selected == null || ALL.equals(selected)) return all;
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) if (e.key().dimension().equals(selected)) out.add(e);
        return out;
    }

    /** Called each frame while the window shows. Returns true when {@link #index()} changed. */
    public boolean tick(long now) {
        if (!available()) return false;
        LocalStore store = hub.store();
        if (store == null || !store.loaded()) return false;

        TreeSet<String> dims = new TreeSet<>(store.dimensions());
        String current = StashIndex.dimension();
        if (!current.isEmpty() && dims.isEmpty()) dims.add(current);
        String signature = String.join("|", dims);
        if (!signature.equals(dimensionSignature) || stashes.isEmpty()) {
            dimensionSignature = signature;
            stashes = buildStashes(store, dims);
            if (selected == null || (!ALL.equals(selected) && !dims.contains(selected))) {
                selected = dims.contains(current) ? current : (dims.isEmpty() ? null : dims.first());
                builtVersion = -1;
            }
        }

        if (store.version() == builtVersion && Objects.equals(selected, builtDimension)) return false;
        // Nothing is copied or worked out while the last rebuild is too recent
        if (decodeLeft == 0 && now - lastBuild < REBUILD_MS) return false;
        List<Entry> entries = entries(store);
        decodeLeft = hub.prepare(entries, PREPARE_BUDGET_NANOS);
        if (decodeLeft > 0) return false;
        if (now - lastBuild < REBUILD_MS) return false;

        LocalHub.Built built = hub.build(entries);
        if (built == null) return false;
        index = built.index();
        samples = built.samples();
        builtVersion = store.version();
        builtDimension = selected;
        lastBuild = now;
        hub.prune(store.snapshot());
        return true;
    }

    private List<StashRef> buildStashes(LocalStore store, TreeSet<String> dims) {
        List<StashRef> out = new ArrayList<>();
        List<Entry> all = store.snapshot();
        for (String dim : dims) {
            int n = 0;
            long last = 0;
            for (Entry e : all) {
                if (!e.key().dimension().equals(dim)) continue;
                n++;
                last = Math.max(last, e.readAt());
            }
            out.add(new StashRef(store.server(), dim, "This server", "", "", true, last, last, 0, 0, -1, "", n, 0));
        }
        if (dims.size() > 1) {
            out.add(new StashRef(store.server(), ALL, "This server", "", "", true, 0, 0, 0, 0, -1, "", all.size(), 0));
        }
        return Collections.unmodifiableList(out);
    }

    /** "Overworld (12)" for a pseudo-stash; the server address is added unless hidden. */
    public static String label(StashRef s, boolean hideServer) {
        String name = ALL.equals(s.stash()) ? "All dimensions" : prettyDimension(s.stash());
        String count = s.containersTotal() >= 0 ? " (" + s.containersTotal() + ")" : "";
        return name + count + (hideServer ? "" : " @ " + s.server());
    }

    static String prettyDimension(String id) {
        String path = id.substring(id.indexOf(':') + 1).replace('_', ' ');
        return path.isEmpty() ? id : Character.toUpperCase(path.charAt(0)) + path.substring(1);
    }
}
