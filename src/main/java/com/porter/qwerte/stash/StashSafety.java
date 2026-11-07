package com.porter.qwerte.stash;

import java.util.ArrayList;
import java.util.List;

/**
 * Guards against a stash file that points at nothing real: a delivery spot or a container millions of blocks away.
 * Pure logic on plain numbers (horizontal distance; height does not matter for a stray), so it can be tested.
 */
public final class StashSafety {
    /** An unloaded position farther than this from the player (or the stash) is not trusted. */
    public static final int FAR_LIMIT = 128;
    /** A never-read container this far from every read one is a stray. */
    public static final long STRAY_DISTANCE = 20000L;
    /** Fewer read, non-delivery containers than this and nothing is judged a stray. */
    public static final int STRAY_MIN_REFS = 5;

    private StashSafety() {}

    public record Pt(int x, int z) {}

    /** A container as the stray check sees it. */
    public record Spot(Pt at, boolean read, boolean delivery) {}

    /** What an area check does with a container: nothing, keep it but skip it (read), or delete it (never read). */
    public enum Outside { INSIDE, SKIP, DROP }

    public static Outside outside(boolean delivery, boolean outsideArea, boolean neverRead) {
        if (delivery || !outsideArea) return Outside.INSIDE;
        return neverRead ? Outside.DROP : Outside.SKIP;
    }

    public static boolean within(Pt a, Pt b, long limit) {
        long dx = (long) a.x() - b.x(), dz = (long) a.z() - b.z();
        return dx * dx + dz * dz <= limit * limit;
    }

    /** A box with no contents and no read time: never read. */
    public static boolean neverRead(boolean hasContents, long lastRead) {
        return !hasContents && lastRead == 0L;
    }

    /** Whether a delivery spot may be set at a place: a loaded one always, an unloaded one only near the player. */
    public static boolean spotSettable(boolean loaded, Pt spot, Pt player) {
        return loaded || (player != null && within(spot, player, FAR_LIMIT));
    }

    /**
     * The never-read, non-delivery containers more than {@link #STRAY_DISTANCE} from the nearest read, non-delivery one.
     * Nothing is judged with fewer than {@link #STRAY_MIN_REFS} reference containers.
     * @return indices into {@code boxes}
     */
    public static List<Integer> strays(List<Spot> boxes) {
        List<Pt> refs = new ArrayList<>();
        for (Spot s : boxes) if (s.read() && !s.delivery()) refs.add(s.at());
        List<Integer> out = new ArrayList<>();
        if (refs.size() < STRAY_MIN_REFS) return out;
        for (int i = 0; i < boxes.size(); i++) {
            Spot s = boxes.get(i);
            if (s.read() || s.delivery()) continue;
            boolean near = false;
            for (Pt r : refs) {
                if (within(s.at(), r, STRAY_DISTANCE)) {
                    near = true;
                    break;
                }
            }
            if (!near) out.add(i);
        }
        return out;
    }
}
