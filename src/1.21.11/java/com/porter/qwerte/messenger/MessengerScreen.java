package com.porter.qwerte.messenger;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The focused Messenger window (Minecraft 1.21.11): a screen that does not pause the game, draws no dimmed background
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
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return ui.mouseClicked(event.x(), event.y(), event.button(), width, height);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return ui.mouseReleased();
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return ui.mouseDragged(event.x(), event.y(), width, height);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return ui.mouseScrolled(x, y, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return ui.keyPressed(event.key(), event.scancode(), event.modifiers());
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return ui.charTyped(event.codepoint(), event.modifiers());
    }
}
