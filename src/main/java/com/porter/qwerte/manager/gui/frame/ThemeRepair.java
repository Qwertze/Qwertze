package com.porter.qwerte.manager.gui.frame;

import com.porter.qwerte.QwerteAddon;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;

/**
 * A safety net for a Meteor quirk: WContainer.add gives the child the container's theme, so a container built with
 * "new WVerticalList()" (theme null) and filled before it is attached leaves its whole subtree without a theme, and the
 * first theme.pad() in the layout throws. Build such containers with theme.verticalList() or attach them first; this
 * walks the tree once per layout and gives a themeless widget its parent's theme, so a slip does not kill the window.
 * It does not re-run init(): widgets that use the theme in init() (WView, the root and values of a WDropdown, which are not in
 * cells) are not repaired, so build containers with theme.xList() or attach them before filling.
 */
public final class ThemeRepair {
    private static boolean logged;

    private ThemeRepair() {
    }

    /** Fills every missing theme under root from its parent, top down. Returns how many widgets were fixed. */
    public static int repair(WWidget root) {
        int fixed = walk(root);
        if (fixed > 0 && !logged) {
            logged = true;
            QwerteAddon.LOG.warn("stash-window: {} widget(s) had no theme (a container was filled before it was attached); repaired. "
                + "Build such containers with theme.verticalList() or attach them first.", fixed);
        }
        return fixed;
    }

    private static int walk(WWidget w) {
        return fill(w, n -> n instanceof WContainer c ? children(c) : java.util.List.of(), n -> n.theme, (n, t) -> n.theme = (meteordevelopment.meteorclient.gui.GuiTheme) t);
    }

    private static java.util.List<WWidget> children(WContainer c) {
        java.util.List<WWidget> out = new java.util.ArrayList<>(c.cells.size());
        for (Cell<?> cell : c.cells) {
            if (cell.widget() != null) out.add(cell.widget());
        }
        return out; // a copy: the walk may not touch cells while it runs
    }

    /** The tree walk itself, independent of Meteor's classes so a test can run it on a fake tree. */
    static <N> int fill(N node, java.util.function.Function<N, java.util.List<N>> children, java.util.function.Function<N, Object> theme,
                        java.util.function.BiConsumer<N, Object> setTheme) {
        int fixed = 0;
        Object own = theme.apply(node);
        for (N child : children.apply(node)) {
            if (own != null && theme.apply(child) == null) {
                setTheme.accept(child, own);
                fixed++;
            }
            fixed += fill(child, children, theme, setTheme);
        }
        return fixed;
    }
}
