package com.porter.qwerte.manager.gui.style;

import com.porter.qwerte.manager.gui.frame.WFrame;
import meteordevelopment.meteorclient.gui.widgets.WWidget;

/**
 * How much room a page has for its content, in physical pixels. A page asks for it when it builds and hands the result to
 * SmColumns/SmGrid/SmTable/WFlow maxWidth, SmCard.fill and wrapped labels. The window rebuilds the page when its width
 * changes (see StashManagerScreen), so a width asked at build time stays right.
 */
public final class PageRoom {
    /** The page scrolls: its bar and the border and resize ring take this much (Meteor pixels). */
    private static final double MARGIN = 14;

    private PageRoom() {
    }

    /** The width of the page body (what a card laid straight in the page can fill). */
    public static double body(WWidget under) {
        SmGuiTheme t = (SmGuiTheme) under.theme;
        for (WWidget w = under; w != null; w = w.parent) {
            if (w instanceof WFrame f) {
                return Math.max(t.scale(240), t.g(f.effective().w()) - 2 * t.scale(WFrame.SURFACE_PAD) - t.scale(MARGIN));
            }
        }
        return t.scale(600);
    }

    /** {@link #body} minus {@code inner} Meteor pixels (the padding of the cards around the widget, both sides together). */
    public static double width(WWidget under, double inner) {
        SmGuiTheme t = (SmGuiTheme) under.theme;
        return Math.max(t.scale(120), body(under) - t.scale(inner));
    }

    /** The height of the page body (the room a page that fills the window has), in physical pixels. */
    public static double height(WWidget under) {
        SmGuiTheme t = (SmGuiTheme) under.theme;
        for (WWidget w = under; w != null; w = w.parent) {
            if (w instanceof WFrame f) return Math.max(t.scale(160), f.pageH);
        }
        return t.scale(400);
    }
}
