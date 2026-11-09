package com.porter.qwerte.manager.gui.tabs;

import com.porter.qwerte.manager.gui.chrome.WThemeCard;
import com.porter.qwerte.manager.gui.style.PageRoom;
import com.porter.qwerte.manager.gui.style.SmCard;
import com.porter.qwerte.manager.gui.style.SmGrid;
import com.porter.qwerte.manager.gui.style.SmPill;
import com.porter.qwerte.manager.gui.style.Ui;
import com.porter.qwerte.manager.gui.style.WindowTheme;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;

import java.util.function.Consumer;

/** The Settings page's Appearance card: the window's themes as cards in two columns, the current one marked. Works without a server. */
public final class AppearanceCard {
    private AppearanceCard() {
    }

    private static final WindowTheme[] ORDER = {WindowTheme.CLASSIC, WindowTheme.MODERN, WindowTheme.LIGHT, WindowTheme.DARK,
        WindowTheme.MIDNIGHT, WindowTheme.MAC, WindowTheme.CONTRAST};

    private static String blurb(WindowTheme t) {
        return switch (t) {
            case CLASSIC -> "Blocky bevels, like now";
            case MODERN -> "Soft rounded cards";
            case LIGHT -> "Flat and calm";
            case DARK -> "Easy on the eyes";
            case MIDNIGHT -> "OLED black, stream-safe";
            case CONTRAST -> "Maximum legibility";
            case MAC -> "Smoky glass, peach pills";
        };
    }

    public static void build(WContainer parent, GuiTheme theme, WindowTheme current, Consumer<WindowTheme> pick) {
        SmCard card = Ui.card(parent, "Appearance");
        card.addRight(new SmPill("This game only", SmPill.Kind.NEUTRAL));
        card.add(Ui.muted(theme, "The theme is saved in this game, not on the bot. It applies at once and is not part of Save / Revert below.",
            PageRoom.width(card, 16)));
        SmGrid grid = card.add(new SmGrid()).expandX().widget();
        grid.columns = 2;
        grid.gapX = 8;
        grid.gapY = 8;
        grid.maxWidth = PageRoom.width(card, 16);
        for (WindowTheme t : ORDER) {
            WThemeCard c = grid.add(new WThemeCard(t, blurb(t), t == current)).widget();
            c.action = () -> pick.accept(t);
        }
    }
}
