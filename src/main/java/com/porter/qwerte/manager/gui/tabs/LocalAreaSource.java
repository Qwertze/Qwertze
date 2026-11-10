package com.porter.qwerte.manager.gui.tabs;

import com.porter.qwerte.manager.model.AreaModel;
import com.porter.qwerte.modules.StashSorter;
import com.porter.qwerte.stash.AreaPending;
import com.porter.qwerte.stash.AreaRules;
import com.porter.qwerte.stash.CraftBounds;
import com.porter.qwerte.stash.Stash;
import com.porter.qwerte.stash.StashIndex;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The Area card for this client's own stash (the Stash sorter module's): the area is read from the sorter, and a change is queued as a local edit
 * (applied between tasks, like the commands). There is no server, so there is no revision and nothing to be refused but the caps.
 */
public final class LocalAreaSource implements AreaSource {
    private final BooleanSupplier hide;

    public LocalAreaSource(BooleanSupplier hide) {
        this.hide = hide;
    }

    private static StashSorter sorter() {
        return Modules.get().get(StashSorter.class);
    }

    private static Stash stash() {
        StashSorter s = sorter();
        return s == null ? null : s.currentStash();
    }

    @Override
    public boolean server() {
        return false;
    }

    @Override
    public boolean ready() {
        return stash() != null;
    }

    @Override
    public String notReady() {
        return sorter() == null ? "The stash sorter module isn't available." : "Join a world: the area belongs to the stash the stash sorter works on.";
    }

    @Override
    public String key() {
        Stash s = stash();
        return s == null ? "" : "local:" + s.server + "/" + s.name;
    }

    @Override
    public String label() {
        Stash s = stash();
        return s == null ? "" : s.name;
    }

    @Override
    public boolean sameServer() {
        return true;
    }

    @Override
    public boolean canWrite() {
        return true;
    }

    @Override
    public boolean hide() {
        return hide.getAsBoolean();
    }

    @Override
    public String botPlayer() {
        return "";
    }

    @Override
    public void load(Consumer<Result> done) {
        StashSorter sorter = sorter();
        Stash s = stash();
        if (sorter == null || s == null) {
            done.accept(new Result(null, ""));
            return;
        }
        StashIndex.AreaReport r = sorter.areaReport();
        AreaPending.Request waiting = sorter.areaPending().get(sorter.areaKey());
        String dimension = r == null || r.dimension() == null ? "" : r.dimension();
        String stashDimension = CraftBounds.fullId(s.dimension);
        String why = waiting == null ? null : sorter.areaPending().why();
        AreaModel.Bot bot = new AreaModel.Bot(Boolean.TRUE, true, waiting == null, r == null ? null : r.box(), dimension, r == null ? null : r.derived(), why,
            r == null ? 0 : r.containersOutside());
        int[] y = AreaRules.yLimits(stashDimension.isEmpty() ? dimension : stashDimension);
        AreaModel.Limits limits = new AreaModel.Limits(AreaRules.MAX_W, AreaRules.MAX_H, AreaRules.MAX_D, y[0], y[1], AreaRules.FAR_BLOCKS);
        done.accept(new Result(new AreaModel.Data("", s.name, 0, r == null ? "" : r.hash() == null ? "" : r.hash(), r == null ? null : r.box(), dimension, "", "", 0,
            stashDimension, null, bot, null, limits), ""));
    }

    @Override
    public void save(int[] box, String dimension, long rev, Consumer<Result> done) {
        StashSorter sorter = sorter();
        if (sorter == null) {
            done.accept(new Result(null, "The stash sorter module isn't available."));
            return;
        }
        if (box != null) {
            String refused = AreaRules.validate(box, dimension, dimension);
            if (refused != null) {
                done.accept(new Result(null, "That area can't be used: " + refused + "."));
                return;
            }
        }
        String refused = sorter.queueLocalArea(box, box == null ? null : dimension);
        if (refused != null) {
            done.accept(new Result(null, "That area can't be used: " + refused + "."));
            return;
        }
        load(r -> done.accept(new Result(r.data(), "")));
    }
}
