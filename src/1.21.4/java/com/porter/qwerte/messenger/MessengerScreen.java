package com.porter.qwerte.messenger;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The focused Messenger window (Minecraft 1.21.4): a screen that does not pause the game, draws no dimmed background
 * and hands everything to {@link MessengerUi}. Closing it returns to the HUD-style drawing.
 */
public final class MessengerScreen extends Screen {
    private final MessengerUi ui;

    public MessengerScreen(MessengerUi ui) {
        super(Component.literal("Messenger"));
        this.ui = ui;
    }

    @Override
    protected void init() {
        ui.focus();
    }

    @Override
    public void removed() {
        ui.unfocus();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // the window decides (it closes the picker or the new-chat box first)
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        // The world stays visible: no blur, no dimming
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        ui.render(g, width, height, mouseX, mouseY, true);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return ui.mouseClicked(x, y, button, width, height);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return ui.mouseReleased();
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return ui.mouseDragged(x, y, width, height);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return ui.mouseScrolled(x, y, scrollY);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        return ui.keyPressed(key, scancode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        return ui.charTyped(c, modifiers);
    }
}
