package com.porter.qwerte.printer.render;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/**
 * What the block model renderer sees while a phantom section is baked: the world, except that every cell still to be placed
 * shows the block the schematic wants there (so faces between two phantoms are left out, and a stair or fence connects to
 * the phantoms beside it). Biome tints come from the real level. Everything is lit at full brightness, so a phantom reads the
 * same at night and in a cave and never needs baking again when the light changes.
 */
final class PhantomView implements BlockAndTintGetter {
    /** The state the schematic wants at a cell that is still to be placed, else null. */
    interface Pending {
        BlockState at(BlockPos pos);
    }

    private final BlockAndTintGetter level;
    private final Pending pending;

    PhantomView(BlockAndTintGetter level, Pending pending) {
        this.level = level;
        this.pending = pending;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState s = pending.at(pos);
        return s != null ? s : level.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return level.getHeight();
    }

    @Override
    public int getMinY() {
        return level.getMinY();
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return level.getShade(direction, shade);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return level.getLightEngine();
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return level.getBlockTint(pos, resolver);
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return 15;
    }
}
