package com.porter.qwerte.manager.gui.tabs;

import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;

/**
 * Whether a text box under a widget has the keyboard. A tab that rebuilds its widgets waits while this is true, so the box keeps
 * its cursor and what was typed in it.
 */
final class TabFocus {
    private TabFocus() {
    }

    static boolean anyFocused(WWidget w) {
        if (w instanceof WTextBox tb) return tb.isFocused();
        if (w instanceof WContainer c) {
            for (Cell<?> cell : c.cells) {
                if (anyFocused(cell.widget())) return true;
            }
        }
        return false;
    }
}
