package com.porter.qwerte.manager.gui.style;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;

/**
 * The website's card (.inset, and .order-card with Style.TILE): a bordered, rounded box with a bold title at its top-left,
 * an optional widget at the title row's right (addRight), and its children stacked under it. Children are as wide as the
 * card's inside; use expandX() on a cell to make a child fill it. Sizes are in Meteor pixels (CSS pixels): the card pads 8
 * and spaces its children 8, like the website.
 */
public class SmCard extends WContainer {
    public enum Style {
        /** The inner tile: the theme's surface-2 fill (a section inside a panel). */
        INSET,
        /** The raised tile: the theme's surface fill (an order card, a theme card). */
        TILE,
        /** A tile with the accent colour as a thick border (the picked one). */
        SELECTED,
        /** A warning tile (the website's warn pair). */
        WARN,
        /** A danger tile. */
        DANGER
    }

    private static final String BOLD = "§l";

    public Style style = Style.INSET;
    /** Space around the children and between them, in Meteor pixels. */
    public double padding = 8;
    public double spacing = 8;
    /** Draws the title in the muted colour and normal weight (a quiet label). */
    public boolean quietTitle;
    /** The card is at least this wide, in physical pixels (a card that fills the page; see PageRoom). */
    public double fill;

    private String title;
    private String boldTitle;
    private Cell<?> right;

    public SmCard(String title) {
        this.title = title == null ? "" : title;
        this.boldTitle = BOLD + this.title;
    }

    public SmCard(String title, Style style) {
        this(title);
        this.style = style;
    }

    public String title() {
        return title;
    }

    public void setTitle(String title) {
        String t = title == null ? "" : title;
        if (!t.equals(this.title)) {
            this.title = t;
            this.boldTitle = BOLD + t;
            invalidate();
        }
    }

    /** Adds a widget at the right end of the title row (a pill, a button). Only one is kept. */
    public <T extends WWidget> Cell<T> addRight(T widget) {
        Cell<T> c = add(widget);
        right = c;
        return c;
    }

    @Override
    public void clear() {
        right = null;
        super.clear();
    }

    private String shownTitle() {
        return quietTitle ? title : boldTitle;
    }

    private boolean hasTitleRow() {
        return !title.isEmpty() || right != null;
    }

    private double titleRowHeight() {
        double h = title.isEmpty() ? 0 : theme.textHeight();
        if (right != null) h = Math.max(h, right.padTop() + right.widget().height + right.padBottom());
        return h;
    }

    @Override
    protected void onCalculateSize() {
        double pad = theme.scale(padding);
        double gap = theme.scale(spacing);
        double w = 0;
        double h = pad;
        if (hasTitleRow()) {
            double tw = title.isEmpty() ? 0 : theme.textWidth(shownTitle());
            if (right != null) tw += theme.scale(10) + right.padLeft() + right.widget().width + right.padRight();
            w = Math.max(w, tw);
            h += titleRowHeight();
        }
        boolean first = !hasTitleRow();
        for (Cell<?> cell : cells) {
            if (cell == right) continue;
            if (!first) h += gap;
            first = false;
            w = Math.max(w, cell.padLeft() + cell.widget().width + cell.padRight());
            h += cell.padTop() + cell.widget().height + cell.padBottom();
        }
        width = Math.max(w + 2 * pad, fill);
        height = h + pad;
    }

    @Override
    protected void onCalculateWidgetPositions() {
        double pad = theme.scale(padding);
        double gap = theme.scale(spacing);
        double yy = y + pad;
        if (hasTitleRow()) {
            double rowH = titleRowHeight();
            if (right != null) {
                right.width = right.widget().width;
                right.height = right.widget().height;
                right.x = x + width - pad - right.padRight() - right.width;
                right.y = yy + (rowH - right.height) / 2;
                right.widget().x = right.x;
                right.widget().y = right.y;
            }
            yy += rowH;
        }
        boolean first = !hasTitleRow();
        for (Cell<?> cell : cells) {
            if (cell == right) continue;
            if (!first) yy += gap;
            first = false;
            yy += cell.padTop();
            cell.x = x + pad + cell.padLeft();
            cell.y = yy;
            cell.width = width - 2 * pad - cell.padLeft() - cell.padRight();
            cell.height = cell.widget().height;
            cell.alignWidget();
            yy += cell.height + cell.padBottom();
        }
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (!(theme instanceof SmGuiTheme t)) return;
        Palette p = t.palette();
        double u = t.unit();
        int fill = switch (style) {
            case INSET -> p.surface2();
            case TILE, SELECTED -> p.surface();
            case WARN -> p.warnBg();
            case DANGER -> p.errBg();
        };
        double bw = (style == Style.SELECTED ? Math.max(2, p.borderWidth * 2) : p.borderWidth) * u;
        int border = style == Style.SELECTED ? p.accent() : style == Style.WARN ? p.warnFg() : style == Style.DANGER ? p.errFg() : p.border();
        Paint.panel(renderer, t, x, y, width, height, p.radius * u, bw, fill, border);
        if (p.bevel && style != Style.SELECTED) Paint.bevel(renderer, t, x + bw, y + bw, width - 2 * bw, height - 2 * bw, u, 0xFFFFFFFF, 0xFF555555);
        if (!title.isEmpty()) {
            double pad = theme.scale(padding);
            int ink = quietTitle ? p.muted() : style == Style.WARN ? p.warnFg() : style == Style.DANGER ? p.errFg() : p.text();
            double rowH = titleRowHeight();
            renderer.text(shownTitle(), x + pad, y + pad + (rowH - theme.textHeight()) / 2, t.color(ink), false);
        }
    }
}
