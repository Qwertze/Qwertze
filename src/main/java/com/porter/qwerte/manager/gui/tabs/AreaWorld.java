package com.porter.qwerte.manager.gui.tabs;

import com.porter.qwerte.stash.StashIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** What the Area card reads from the game: where the player stands, the dimension, and the block that was in the crosshair when the window opened. */
public final class AreaWorld {
    /** The farthest block "the block I look at" may be. */
    public static final int LOOK_RANGE = 64;

    private AreaWorld() {
    }

    public static boolean haveWorld() {
        return mc.player != null && mc.level != null;
    }

    /** The block the player's feet are in, or null with no world. */
    public static int[] feet() {
        if (!haveWorld()) return null;
        BlockPos p = mc.player.blockPosition();
        return new int[] {p.getX(), p.getY(), p.getZ()};
    }

    /** The player's dimension in the contract's form ("minecraft:overworld"), or "" with no world. */
    public static String dimension() {
        return haveWorld() ? StashIndex.dimension() : "";
    }

    /** The block in the crosshair within {@value #LOOK_RANGE} blocks right now, or null. Call it when the window opens: with a screen open the view no longer follows the mouse. */
    public static int[] lookNow() {
        if (!haveWorld()) return null;
        Vec3 eyes = mc.player.getEyePosition();
        Vec3 end = eyes.add(mc.player.getViewVector(1f).scale(LOOK_RANGE));
        BlockHitResult hit = mc.level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos p = hit.getBlockPos();
        return new int[] {p.getX(), p.getY(), p.getZ()};
    }
}
