package com.porter.qwerte.compat;

import com.porter.qwerte.manager.gui.style.TextScale;
import com.mojang.blaze3d.vertex.PoseStack;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.renderer.text.VanillaTextRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Meteor's vanilla text renderer at the Stash Window's own text size. Meteor starts a text batch at the theme's scale
 * (and "title" text at 1.25 times it); this starts it at {@link TextScale#batchScale} instead, so the text size follows the
 * window's text setting, and "title" text is the website's smaller text. Per Minecraft version only because
 * {@code TextRenderer.end} differs between Meteor builds.
 */
public final class ScaledText implements TextRenderer {
    private final VanillaTextRenderer base = VanillaTextRenderer.INSTANCE;
    private final DoubleSupplier ref;
    private final IntSupplier unit;
    private final Supplier<TextScale> setting;

    public ScaledText(DoubleSupplier ref, IntSupplier unit, Supplier<TextScale> setting) {
        this.ref = ref;
        this.unit = unit;
        this.setting = setting;
    }

    @Override
    public void setAlpha(double a) {
        base.setAlpha(a);
    }

    @Override
    public void begin(double scale, boolean scaleOnly, boolean big) {
        base.begin(TextScale.batchScale(scale, ref.getAsDouble(), unit.getAsInt(), setting.get().factor()), scaleOnly, big);
    }

    @Override
    public double getWidth(String text, int length, boolean shadow) {
        return base.getWidth(text, length, shadow) * outside();
    }

    @Override
    public double getHeight(boolean shadow) {
        return base.getHeight(shadow) * outside();
    }

    /** Outside a batch the base renderer measures at its default scale of 2; this window's normal text is bigger or smaller. */
    private double outside() {
        return base.isBuilding() ? 1 : TextScale.physical(unit.getAsInt(), setting.get().factor()) / 2.0;
    }

    @Override
    public double render(String text, double x, double y, Color color, boolean shadow) {
        return base.render(text, x, y, color, shadow);
    }

    @Override
    public boolean isBuilding() {
        return base.isBuilding();
    }

    @Override
    public void end(PoseStack matrices) {
        base.end(matrices);
    }
}
