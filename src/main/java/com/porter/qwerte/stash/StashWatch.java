package com.porter.qwerte.stash;

import com.porter.qwerte.stash.Stash.Box;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Watches the containers of a stash and their signs from the client's own copy of the world, without any packets: when
 * somebody else opens a chest and closes it again, and when a sign's text really changes. Only the text of a sign counts
 * as a change, never a block update next to the container, so neighbouring chests and stacked columns change nothing.
 * Times are the caller's tick count.
 */
public final class StashWatch {
    private static final int OPEN_FORGET_TICKS = 20 * 60 * 10;
    private static final int REMOVAL_GRACE_TICKS = 20 * 60;

    private static final class Lid {
        boolean open;
        boolean foreign;
        long openedAt;
        long closedAt = -1;
    }

    private final Map<Box, Lid> lids = new HashMap<>();
    // Watching the chests of the delivery storage (excluded from sorting, so the other watch skips them) and nothing else
    private final boolean deliveryOnly;

    public StashWatch() {
        this(false);
    }

    public StashWatch(boolean deliveryOnly) {
        this.deliveryOnly = deliveryOnly;
    }

    // Signs whose text differs from the container's labels: since when it last changed, and what it said then
    private final Map<Box, Long> signDirtyAt = new HashMap<>();
    private final Map<Box, List<String>> signSeen = new HashMap<>();
    // Containers whose sign has been gone or blank, and since when
    private final Map<Box, Long> signGoneSince = new HashMap<>();
    // Containers whose signs have been read once since the watch started: the first read is adopted silently
    private final java.util.Set<Box> primed = new java.util.HashSet<>();
    private Stash stash;

    public void clear() {
        primed.clear();
        lids.clear();
        signDirtyAt.clear();
        signSeen.clear();
        signGoneSince.clear();
    }

    // Lids

    /**
     * Notices lids being opened by someone else. One that somebody other than {@code byUs} looked into and that has stayed
     * closed for {@code delay} ticks is handed to {@code reread}.
     */
    public void tickLids(Stash stash, long now, long delay, Predicate<Box> byUs, Consumer<Box> reread) {
        for (Box box : new ArrayList<>(stash.boxes)) {
            if (deliveryOnly ? !box.delivery : box.excluded) continue;
            Boolean open = isOpen(box);
            if (open == null) continue;

            Lid lid = lids.get(box);
            if (open) {
                if (lid == null) {
                    lid = new Lid();
                    lids.put(box, lid);
                }
                if (!lid.open) {
                    lid.open = true;
                    lid.openedAt = now;
                    lid.closedAt = -1;
                    // Somebody new looking in that isn't the bot, nor the connected player
                    if (!byUs.test(box)) lid.foreign = true;
                }
                // Still shown as open after ten minutes: the close was missed. Forgotten
                else if (now - lid.openedAt > OPEN_FORGET_TICKS) lids.remove(box);
            }
            else if (lid != null) {
                if (lid.open) {
                    lid.open = false;
                    lid.closedAt = now;
                }
                if (!lid.foreign) lids.remove(box);
                else if (now - lid.closedAt >= delay) {
                    lids.remove(box);
                    reread.accept(box);
                }
            }
        }
        lids.keySet().removeIf(box -> !stash.boxes.contains(box));
    }

    /**
     * Whether anyone has it open (a lid up, a barrel open), or null if it isn't loaded.
     */
    private static Boolean isOpen(Box box) {
        if (mc.level == null) return null;
        boolean open = false;
        for (BlockPos part : box.parts) {
            if (!mc.level.isLoaded(part)) return null;
            if (mc.level.getBlockEntity(part) instanceof ChestBlockEntity chest) {
                if (chest.getOpenNess(1.0f) > 0) open = true;
            }
            else {
                var state = mc.level.getBlockState(part);
                if (state.getBlock() instanceof BarrelBlock && state.getValue(BarrelBlock.OPEN)) open = true;
            }
        }
        return open;
    }

    // Signs

    /**
     * A sign on this container is being changed, so what it takes is not settled.
     */
    public boolean pending(Box box) {
        return signDirtyAt.containsKey(box);
    }

    public boolean anyPending() {
        return !signDirtyAt.isEmpty();
    }

    /**
     * Compares what the signs say now with what the containers have. A different text marks the container as pending;
     * a pending one is only pushed back by a text that differs from the last one seen.
     */
    public void pollSigns(Stash stash, long now, Predicate<Box> skip) {
        this.stash = stash;
        for (Box box : stash.boxes) {
            if (skip.test(box)) continue;
            Containers.Signs read = Containers.read(stash, box);
            if (read == null) continue;

            // What the signs say when first looked at is taken as it is, whatever was saved or read before
            if (primed.add(box)) {
                if (!read.labels().equals(box.labels)) {
                    box.labels.clear();
                    box.labels.addAll(read.labels());
                }
                continue;
            }

            if (signDirtyAt.containsKey(box)) {
                if (!read.labels().equals(signSeen.get(box))) {
                    signDirtyAt.put(box, now);
                    signSeen.put(box, read.labels());
                }
            }
            else if (!read.labels().equals(box.labels)) {
                // A sign that went away is waited on separately, for a minute, before it counts as removed
                if (read.labels().isEmpty() && signGoneSince.containsKey(box)) continue;
                signDirtyAt.put(box, now);
                signSeen.put(box, read.labels());
            }
        }
        signDirtyAt.keySet().removeIf(box -> !stash.boxes.contains(box));
        signSeen.keySet().removeIf(box -> !stash.boxes.contains(box));
        signGoneSince.keySet().removeIf(box -> !stash.boxes.contains(box));
    }

    /**
     * This container's sign is known to say what its labels say: nothing is pending for it.
     */
    public void settleNow(Box box) {
        signDirtyAt.remove(box);
        signSeen.remove(box);
        signGoneSince.remove(box);
    }

    /**
     * The containers whose sign has been left alone for {@code settle} ticks. They are no longer pending.
     */
    public List<Box> settled(long now, long settle) {
        List<Box> done = new ArrayList<>();
        for (Map.Entry<Box, Long> entry : new ArrayList<>(signDirtyAt.entrySet())) {
            if (now - entry.getValue() < settle) continue;
            signDirtyAt.remove(entry.getKey());
            signSeen.remove(entry.getKey());
            done.add(entry.getKey());
        }
        return done;
    }

    /**
     * The labels this container should have now, or null if they stay as they are. A different text is used as it is. No
     * text is only used after a minute of it: a sign taken down and put up again (to fix a typo, say) is not a removal.
     * Nothing is changed for a container that isn't in range.
     */
    public List<String> labelChange(Box box, long now) {
        Containers.Signs read = Containers.read(stash, box);
        if (read == null) return null;

        List<String> labels = read.labels();
        if (labels.equals(box.labels)) {
            signGoneSince.remove(box);
            return null;
        }

        if (labels.isEmpty()) {
            Long since = signGoneSince.get(box);
            if (since == null) {
                signGoneSince.put(box, now);
                return null;
            }
            if (now - since < REMOVAL_GRACE_TICKS) return null;
        }

        signGoneSince.remove(box);
        return labels;
    }

    /**
     * Containers whose sign has been gone long enough to count as removed, to be looked at again.
     */
    public List<Box> goneLongEnough(long now) {
        List<Box> list = new ArrayList<>();
        for (Map.Entry<Box, Long> entry : signGoneSince.entrySet()) {
            if (now - entry.getValue() >= REMOVAL_GRACE_TICKS) list.add(entry.getKey());
        }
        return list;
    }
}
