package com.porter.stashmanager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The checks every stored stash area passes, from the website and from a bot's heartbeat alike (AREA-CONTRACT.md): the
 * stash's dimension, and the distance from the stash's <em>home</em>. The home is a stable point set once and never moved by
 * bot reports, so no series of small steps can walk an area away. It comes from the delivery container; until there is one it
 * can be <em>provisional</em>: the centre of the containers of the first kit report (only when they lie within 256 blocks of
 * each other in X and Z) or the centre of the first area a bot has. A provisional home is replaced once, when a delivery
 * container first appears, and dropped when the stash's dimension changes. A heartbeat that carries an in-game edit never sets
 * a home. Only the home, and the delivery container when it lies within the limit of the home, count as spots.
 */
final class AreaGuard {
    /** A refusal: 400 for an area that is not acceptable, 409 when the stash has no usable home. */
    record Problem(int status, String message) {
    }

    static final String NO_HOME = "the bot hasn't reported where the stash is yet";
    static final String STALE_HOME = "the stash's dimension changed since its home point was set; the owner must reset the home point first";
    /** The containers of a kit report make a home only when they lie within this span (X and Z) of each other. */
    static final int MAX_BOOTSTRAP_SPAN = 256;

    private final AreaStore store;
    private final IndexStore index;
    private final KitReports kitReports;

    AreaGuard(AreaStore store, IndexStore index, KitReports kitReports) {
        this.store = store;
        this.index = index;
        this.kitReports = kitReports;
    }

    private static int[] centre(AreaStore.Area a) {
        return new int[]{Math.floorDiv(a.minX() + a.maxX(), 2), Math.floorDiv(a.minY() + a.maxY(), 2), Math.floorDiv(a.minZ() + a.maxZ(), 2)};
    }

    /** The stash's dimension, namespaced, or null while the index has none. */
    String dimension(String server, String stash) {
        return AreaStore.normalizeDimension(index.dimension(server, stash));
    }

    /** The delivery container's position when it is in the stash's dimension, else null. */
    int[] delivery(String server, String stash) {
        IndexStore.Delivery d = index.delivery(server, stash);
        String dim = dimension(server, stash);
        if (d == null || d.dimension() == null || dim == null || !dim.equals(AreaStore.normalizeDimension(d.dimension()))) return null;
        return new int[]{d.x(), d.y(), d.z()};
    }

    /** The centre of the first kit report's containers, or null when there is none or they spread over more than 256 blocks. */
    private int[] kitReportCentre(String server, String stash) {
        KitReports.Report kr = kitReports.get(server, stash);
        if (kr == null || kr.containers().isEmpty()) return null;
        long x = 0, y = 0, z = 0;
        long minX = Long.MAX_VALUE, maxX = Long.MIN_VALUE, minZ = Long.MAX_VALUE, maxZ = Long.MIN_VALUE;
        for (KitReports.Container c : kr.containers()) {
            String[] s = c.id().split(",");
            long cx = Long.parseLong(s[0]), cz = Long.parseLong(s[2]);
            x += cx;
            y += Long.parseLong(s[1]);
            z += cz;
            minX = Math.min(minX, cx);
            maxX = Math.max(maxX, cx);
            minZ = Math.min(minZ, cz);
            maxZ = Math.max(maxZ, cz);
        }
        if (maxX - minX > MAX_BOOTSTRAP_SPAN || maxZ - minZ > MAX_BOOTSTRAP_SPAN) return null;
        int n = kr.containers().size();
        return new int[]{(int) Math.floorDiv(x, n), (int) Math.floorDiv(y, n), (int) Math.floorDiv(z, n)};
    }

    /**
     * The stash's home (null when there is none). With create, a missing home is set now and a provisional one is replaced by
     * the delivery container once it exists; without it (a heartbeat carrying an in-game edit) nothing is ever set. adopted:
     * the area a bot has, for the centre fallback (never from the website). A provisional home whose dimension no longer
     * matches the stash's is dropped first.
     */
    AreaStore.Home ensureHome(String server, String stash, AreaStore.Area adopted, boolean create) throws IOException {
        String dim = dimension(server, stash);
        AreaStore.Home h = store.home(server, stash);
        if (h != null && h.provisional() && h.staleFor(dim)) {
            if (create) store.clearHome(server, stash);
            h = create ? null : h;
        }
        if (!create || dim == null) return h;
        int[] d = delivery(server, stash);
        if (h != null) {
            if (h.provisional() && d != null) return store.replaceProvisional(server, stash, new AreaStore.Home(d[0], d[1], d[2], false, dim));
            return h;
        }
        if (d != null) return store.setHomeIfAbsent(server, stash, new AreaStore.Home(d[0], d[1], d[2], false, dim));
        int[] p = kitReportCentre(server, stash);
        if (p == null && adopted != null) p = centre(adopted);
        if (p == null) return null;
        return store.setHomeIfAbsent(server, stash, new AreaStore.Home(p[0], p[1], p[2], true, dim));
    }

    /** The spots a new area is measured against: the home, and the delivery container when it is within the limit of the home. */
    List<int[]> spots(String server, String stash, int[] home) {
        List<int[]> out = new ArrayList<>();
        out.add(home);
        int[] d = delivery(server, stash);
        if (d != null && Math.hypot(d[0] - home[0], d[2] - home[2]) <= AreaStore.FAR_BLOCKS) out.add(d);
        return out;
    }

    /**
     * Null when the area may be stored, else why not: the stash's dimension must be known and the area's; and its box must be
     * within 256 blocks horizontally of a spot. allowCentreHome lets a bot's first area be the stash's provisional home when
     * nothing else says where the stash is (never from the website); create false (an in-game edit) never sets a home.
     */
    Problem check(String server, String stash, AreaStore.Area area, boolean allowCentreHome, boolean create) throws IOException {
        String dim = dimension(server, stash);
        if (dim == null) return new Problem(400, "this stash's dimension isn't known yet (its bot has not sent an index), so an area can't be set");
        if (!dim.equals(AreaStore.normalizeDimension(area.dimension()))) return new Problem(400, "area.dimension is " + area.dimension() + ", but this stash is in " + dim);
        AreaStore.Home home = ensureHome(server, stash, allowCentreHome ? area : null, create);
        if (home == null) return new Problem(409, NO_HOME);
        if (home.staleFor(dim)) return new Problem(409, STALE_HOME);
        double best = Double.MAX_VALUE;
        for (int[] s : spots(server, stash, home.pos())) best = Math.min(best, area.horizontalTo(s[0], s[2]));
        if (best > AreaStore.FAR_BLOCKS) return new Problem(400, "that area is " + Math.round(best) + " blocks from the stash (the limit is " + AreaStore.FAR_BLOCKS + ")");
        return null;
    }

    /** A bot's derived box, or null when it is not a box of the contract's size and height in this dimension. */
    static int[] cleanDerived(int[] d, String dimension) {
        if (d == null) return null;
        if (d[0] > d[3] || d[1] > d[4] || d[2] > d[5]) return null;
        if (d[3] - d[0] + 1 > AreaStore.MAX_W || d[4] - d[1] + 1 > AreaStore.MAX_H || d[5] - d[2] + 1 > AreaStore.MAX_D) return null;
        int[] y = AreaStore.yRange(dimension == null ? "minecraft:overworld" : dimension);
        if (d[1] < y[0] || d[4] > y[1]) return null;
        return d;
    }
}
