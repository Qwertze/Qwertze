package com.porter.qwerte.manager.local;

import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The containers the player has opened on one server, as the game showed them (items kept as their saved NBT, so the
 * store needs no game registries and can be tested). Holds the caps: at most {@code cap} containers, 54 slots each.
 * Client thread only; the file side is {@link LocalFile}.
 */
public final class LocalStore {
    /** An item in a slot, as {@code ItemStack.CODEC} wrote it. */
    public record Slot(int slot, Tag item) {}

    /**
     * One container. {@code parts} are its block positions (two for a double chest), {@code block} the block id,
     * {@code size} the number of slots, {@code readAt} the time (ms) of the last change seen. Immutable: a change is a
     * new entry, which is how caches keyed on the entry know to refresh.
     */
    public record Entry(ContainerKey key, List<int[]> parts, String block, int size, long readAt, List<Slot> slots) {}

    public static final int DEFAULT_CAP = 4096;
    public static final int MIN_CAP = 256;

    private final String server;
    private final Map<ContainerKey, Entry> entries = new LinkedHashMap<>();
    private int version;
    private boolean loaded;
    private boolean saveBlocked;
    private boolean changedSinceSave;
    private boolean ignoreLoad;

    public LocalStore(String server) {
        this.server = server;
    }

    public String server() {
        return server;
    }

    /** Counts up with every change; for noticing that something needs redoing. */
    public int version() {
        return version;
    }

    public int size() {
        return entries.size();
    }

    public Entry get(ContainerKey key) {
        return entries.get(key);
    }

    /** A copy of the entries, oldest insertion first. */
    public List<Entry> snapshot() {
        return new ArrayList<>(entries.values());
    }

    public Set<String> dimensions() {
        Set<String> out = new LinkedHashSet<>();
        for (ContainerKey k : entries.keySet()) out.add(k.dimension());
        return out;
    }

    // ---------- loading and saving state ----------

    /** True once the file was read (or found missing or broken): until then nothing may be written, or it would overwrite what we haven't read. */
    public boolean loaded() {
        return loaded;
    }

    /** The file couldn't be read and couldn't be moved aside: saving would destroy it. */
    public boolean saveBlocked() {
        return saveBlocked;
    }

    public void blockSaving() {
        saveBlocked = true;
    }

    public boolean canSave() {
        return loaded && !saveBlocked;
    }

    /** True when something changed since {@link #markSaved}. */
    public boolean needsSave() {
        return canSave() && changedSinceSave;
    }

    public void markSaved(int savedVersion) {
        if (savedVersion == version) changedSinceSave = false;
    }

    // ---------- changes ----------

    /**
     * Stores or replaces a container. Rejected (false) when it is malformed: no parts, a size outside 1..54, a slot
     * outside the container, or more than 54 stacks. Over {@code cap} containers, the ones read longest ago go.
     */
    public boolean put(Entry entry, int cap) {
        if (!valid(entry)) return false;
        entries.put(entry.key(), entry);
        touch();
        enforce(cap, entry.key());
        return true;
    }

    static boolean valid(Entry e) {
        if (e == null || e.key() == null || e.parts() == null || e.parts().isEmpty() || e.slots() == null) return false;
        if (e.size() < 1 || e.size() > CaptureRules.MAX_SLOTS || e.slots().size() > CaptureRules.MAX_SLOTS) return false;
        for (Slot s : e.slots()) if (s == null || s.item() == null || s.slot() < 0 || s.slot() >= e.size()) return false;
        return true;
    }

    public boolean remove(ContainerKey key) {
        if (entries.remove(key) == null) return false;
        touch();
        return true;
    }

    /** Forgets everything. */
    public void clear() {
        // A file still being read must not bring back what was just forgotten
        if (!loaded) {
            ignoreLoad = true;
            changedSinceSave = true;
        }
        if (entries.isEmpty()) return;
        entries.clear();
        touch();
    }

    /**
     * Adds what a file held, except containers captured since the load began (newer). Not a change that needs saving
     * unless something had to be dropped for the cap.
     */
    public void mergeLoaded(Collection<Entry> loadedEntries, int cap) {
        Map<ContainerKey, Entry> merged = new LinkedHashMap<>();
        if (!ignoreLoad) for (Entry e : loadedEntries) if (valid(e)) merged.put(e.key(), e);
        // Captures made while loading go last and win
        for (Map.Entry<ContainerKey, Entry> e : entries.entrySet()) merged.put(e.getKey(), e.getValue());
        boolean hadNew = !entries.isEmpty();
        entries.clear();
        entries.putAll(merged);
        loaded = true;
        version++;
        int before = entries.size();
        enforce(cap, null);
        changedSinceSave = hadNew || ignoreLoad || entries.size() != before;
    }

    /** The file was missing or unusable and has been dealt with: the store is ready. */
    public void markLoadedEmpty() {
        loaded = true;
        version++;
    }

    private void enforce(int cap, ContainerKey keep) {
        int limit = Math.max(1, cap);
        if (entries.size() <= limit) return;
        List<Entry> candidates = new ArrayList<>(entries.values());
        if (keep != null) candidates.removeIf(e -> e.key().equals(keep));
        // size() - cap entries must go; "keep" is not a candidate, and counts toward the cap
        int excess = entries.size() - limit;
        for (Entry drop : Evict.oldest(candidates, candidates.size() - excess, Entry::readAt)) entries.remove(drop.key());
        touch();
    }

    private void touch() {
        version++;
        changedSinceSave = true;
    }
}
