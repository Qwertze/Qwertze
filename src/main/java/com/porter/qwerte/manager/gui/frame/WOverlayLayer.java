package com.porter.qwerte.manager.gui.frame;

import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;

import java.util.List;
import java.util.function.IntConsumer;

import static meteordevelopment.meteorclient.utils.Utils.getWindowHeight;
import static meteordevelopment.meteorclient.utils.Utils.getWindowWidth;

/**
 * The window's top layer: the open list of a select and the hover card of an item. It is the frame's first child, so it
 * is offered every mouse event before anything else and, while a list is open, claims the whole screen: the click that
 * picks a row or closes the list never reaches the widgets underneath. It is drawn last, in its own scissor, with solid
 * colours. Item icons are drawn above everything Meteor draws, so it also keeps an {@link OverlayMask} that every icon
 * consults.
 *
 * <p>It overrides no click or key handler (their signatures differ between Minecraft versions): a press is Meteor's
 * {@code WPressable} press, handled on release in {@link #onPressed}.
 */
public class WOverlayLayer extends WPressable {
    /** One open list: the select's rectangle (physical pixels), its rows, the chosen row and what to do on a pick. */
    public record Popup(double ax, double ay, double aw, double ah, List<String> rows, int selected, IntConsumer pick) {
    }

    public final OverlayMask mask = new OverlayMask();

    private Popup popup;
    private PopupLayout.Placement place;
    private double rowH;
    private int scroll;
    private int hoverRow = -1;
    private double lastX;
    private double lastY;

    private List<String> cardLines;
    private List<String> wrapSrc;
    private List<String> wrapOut = List.of();
    private double wrapRoom;
    private double cardX;
    private double cardY;
    private double cardW;
    private double cardH;

    private SmGuiTheme sm() {
        return (SmGuiTheme) theme;
    }

    // ---------- the open list ----------

    public boolean isOpen() {
        return popup != null;
    }

    /** True when the open list belongs to the select at this position. */
    public boolean anchoredAt(double x, double y) {
        return popup != null && popup.ax() == x && popup.ay() == y;
    }

    public void open(Popup p) {
        SmGuiTheme t = sm();
        double u = t.unit();
        rowH = t.textHeight() + 4 * u;
        double widest = 0;
        for (String row : p.rows()) widest = Math.max(widest, t.textWidth(row));
        double rowW = widest + 14 * u;
        popup = p;
        place = PopupLayout.place(p.ax(), p.ay(), p.aw(), p.ah(), rowW, rowH, p.rows().size(), getWindowWidth(), getWindowHeight());
        scroll = PopupLayout.clampScroll(PopupLayout.scrollTo(Math.max(0, p.selected()), 0, place.visible()), p.rows().size(), place.visible());
        mask.set(OverlayMask.POPUP, place.x(), place.y(), place.w(), place.h());
        hoverRow = PopupLayout.rowAt(place, rowH, scroll, p.rows().size(), lastX, lastY);
        // The pointer hasn't moved since the click that opened the list, so Meteor hasn't told this widget it is over
        // anything yet; without this the next click would fall through to the widgets underneath
        mouseOver = true;
        pressed = false;
        cardLines = null;
        mask.clear(OverlayMask.HOVER);
        for (WWidget w = parent; w != null; w = w.parent) {
            if (w.parent == null && w instanceof WContainer root) unfocusTextBoxes(root);
        }
    }

    public void close() {
        popup = null;
        place = null;
        hoverRow = -1;
        mouseOver = false;
        pressed = false;
        mask.clear(OverlayMask.POPUP);
    }

    @Override
    public boolean isOver(double mx, double my) {
        return popup != null;
    }

    @Override
    public void onMouseMoved(double mouseX, double mouseY, double lastMouseX, double lastMouseY) {
        lastX = mouseX;
        lastY = mouseY;
        if (popup != null) hoverRow = PopupLayout.rowAt(place, rowH, scroll, popup.rows().size(), mouseX, mouseY);
    }

    @Override
    public boolean onMouseScrolled(double amount) {
        if (popup == null) return false;
        scroll = PopupLayout.clampScroll(scroll - (int) Math.signum(amount) * 2, popup.rows().size(), place.visible());
        hoverRow = PopupLayout.rowAt(place, rowH, scroll, popup.rows().size(), lastX, lastY);
        return true;
    }

    /** The release of a press that began while the list was open: a row picks it, anything else closes the list. */
    @Override
    protected void onPressed(int button) {
        Popup p = popup;
        if (p == null) return;
        int row = PopupLayout.rowAt(place, rowH, scroll, p.rows().size(), lastX, lastY);
        if (row >= 0 && button == 0) {
            close();
            p.pick().accept(row);
        } else if (place.inside(lastX, lastY)) {
            // a right click on a row, or the list's border: keep it open
            pressed = false;
        } else {
            close();
        }
    }

    // ---------- the hover card ----------

    private double[] tooltipNext;

    /**
     * Called every frame before the widgets draw: forgets last frame's card (its mask stays until this frame's card, if
     * any, replaces it, so an icon drawn before the card is asked for is still kept clear of it) and takes over the
     * rectangle of the Meteor tooltip drawn last frame, which is drawn after the icons and so can only be masked a
     * frame late; the pointer is still while a tooltip shows, so that is exact.
     */
    public void beginFrame() {
        cardLines = null;
        if (popup == null) mouseOver = false;
        if (tooltipNext != null) mask.set(OverlayMask.TOOLTIP, tooltipNext[0], tooltipNext[1], tooltipNext[2], tooltipNext[3]);
        else mask.clear(OverlayMask.TOOLTIP);
        tooltipNext = null;
    }

    /** A Meteor tooltip was drawn at this rectangle. */
    public void tooltipShown(double x, double y, double w, double h) {
        tooltipNext = new double[] {x, y, w, h};
    }

    /** True while the pointer is on the open list (widgets under it must not react). */
    public boolean coversPointer(double mx, double my) {
        return popup != null;
    }

    /**
     * Shows a card (a name, a count, an id...) beside the hovered slot, never over it: right of the slot, or left of it
     * when there is no room, moved up or down to stay on the screen. Call from a widget's render; the card is registered
     * in the mask at once (so icons under it are skipped) and drawn with the overlay.
     *
     * @param sx, sy, sw, sh the hovered slot (or tab), in physical pixels
     */
    public void hoverCard(List<String> lines, double sx, double sy, double sw, double sh) {
        if (popup != null || lines == null || lines.isEmpty()) return;
        SmGuiTheme t = sm();
        double u = t.unit();
        double widest = 0;
        for (String line : lines) widest = Math.max(widest, t.textWidth(line));
        double pad = 3 * u;
        // The card is never wider than the screen: longer lines wrap (made again only when the lines or the room change)
        double room = getWindowWidth() - 4 * u - 2 * pad;
        if (widest > room) {
            if (lines != wrapSrc || room != wrapRoom) {
                List<String> out = new java.util.ArrayList<>();
                for (String line : lines) out.addAll(com.porter.qwerte.manager.gui.widgets.TextWrap.wrap(line, room, t::textWidth));
                wrapSrc = lines;
                wrapRoom = room;
                wrapOut = out;
            }
            lines = wrapOut;
            widest = room;
        }
        double w = widest + 2 * pad;
        double h = lines.size() * t.textHeight() + (lines.size() - 1) * u + 2 * pad;
        double screenW = getWindowWidth();
        double screenH = getWindowHeight();
        double x = sx + sw + 2 * u;
        double y = sy;
        if (x + w > screenW) x = Math.max(0, sx - 2 * u - w);
        if (y + h > screenH) y = screenH - h;
        if (y < 0) y = 0;
        cardLines = lines;
        cardX = x;
        cardY = y;
        cardW = w;
        cardH = h;
        mask.set(OverlayMask.HOVER, x, y, w, h);
    }

    /** Typed characters go to the focused text box, so none may be focused under an open list. */
    private void unfocusTextBoxes(WContainer c) {
        for (Cell<?> cell : c.cells) {
            WWidget w = cell.widget();
            if (w instanceof WTextBox tb) tb.setFocused(false);
            else if (w instanceof WContainer inner) unfocusTextBoxes(inner);
        }
    }

    // ---------- drawing ----------

    /** Draws the list and the card in one full-screen scissor, after everything else of the frame. */
    public void renderLayer(GuiRenderer renderer) {
        if (cardLines == null) mask.clear(OverlayMask.HOVER);
        if (popup == null && cardLines == null) return;
        SmGuiTheme t = sm();
        Palette p = t.palette();
        double u = t.unit();
        renderer.scissorStart(0, 0, getWindowWidth(), getWindowHeight());
        if (cardLines != null) {
            // The game's own item tooltip colours
            Paint.panel(renderer, t, cardX, cardY, cardW, cardH, 0, u, 0xF0100010, 0xFF2D0A63);
            double ty = cardY + 3 * u;
            for (int i = 0; i < cardLines.size(); i++) {
                renderer.text(cardLines.get(i), cardX + 3 * u, ty, t.color(i == 0 ? 0xFFFFFFFF : 0xFFAAAAAA), false);
                ty += t.textHeight() + u;
            }
        }
        if (popup != null) {
            double bw = p.borderWidth * u;
            Paint.panel(renderer, t, place.x(), place.y(), place.w(), place.h(), Math.min(p.radius, 3) * u, bw, p.surface2(), p.border());
            int rows = popup.rows().size();
            renderer.scissorStart(place.x() + bw, place.y() + bw, place.w() - 2 * bw, place.h() - 2 * bw);
            for (int i = 0; i < place.visible() && scroll + i < rows; i++) {
                int row = scroll + i;
                double ry = place.y() + i * rowH;
                boolean hot = row == hoverRow;
                if (hot) Paint.rect(renderer, t, place.x() + bw, ry, place.w() - 2 * bw, rowH, p.accent());
                else if (row == popup.selected()) Paint.rect(renderer, t, place.x() + bw, ry, 2 * u, rowH, p.accent());
                int ink = hot ? p.onAccent() : p.text();
                renderer.text(popup.rows().get(row), place.x() + 6 * u, ry + 2 * u, t.color(ink), false);
            }
            if (rows > place.visible()) {
                double trackH = place.h() - 2 * bw;
                double barH = Math.max(6 * u, trackH * place.visible() / rows);
                double barY = place.y() + bw + (trackH - barH) * scroll / Math.max(1, PopupLayout.maxScroll(rows, place.visible()));
                Paint.rect(renderer, t, place.x() + place.w() - bw - 3 * u, barY, 3 * u, barH, p.trackOff());
            }
            renderer.scissorEnd();
        }
        renderer.scissorEnd();
    }
}
