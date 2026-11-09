package com.porter.qwerte.modules;

import com.porter.qwerte.printer.move.FlySteer;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Flies the player to a hover point with a flight module that obeys the movement keys (Meteor's Flight and the like).
 *
 * <p>It does not move the player itself and does not touch the look: it switches the module on, holds forward, back, left,
 * right, jump or sneak as {@link FlySteer} says, and lets go of them again. The keys it holds are always given back to
 * what the real keyboard says, on {@link #hold()}, {@link #shutdown()}, when a screen opens, after a damage spike, and by the
 * {@link #endTick(int)} watchdog if nobody drove it this tick. A module is switched off afterwards only when this
 * controller switched it on, and only when the player would not fall far (otherwise it is left on and the Printer says so).
 */
final class FlyController {
    /** Health lost in one tick that pauses the flying, and for how long. */
    private static final float DAMAGE_SPIKE = 3f;
    private static final int DAMAGE_PAUSE = 40;
    /** Free-falling this many blocks or less is not dangerous. */
    private static final int SAFE_DROP = 3;

    private final Supplier<List<Module>> modules;
    /** The Printer is holding sneak for a sneak-place (or a tool click): the shift key is its, not ours. */
    private final BooleanSupplier sneakActive;
    private final Set<KeyMapping> down = new HashSet<>();
    private Module enabledByUs;
    private int lastDrive = -1000, pausedUntil = -1;
    private float lastHealth = -1;
    private String notice;

    FlyController(Supplier<List<Module>> modules, BooleanSupplier sneakActive) {
        this.modules = modules;
        this.sneakActive = sneakActive;
    }

    /** The module to fly with: one of the list that is already on, otherwise the first. Null when the list is empty. */
    private Module source() {
        Module first = null;
        for (Module m : modules.get()) {
            if (m == null) continue;
            if (m.isActive()) return m;
            if (first == null) first = m;
        }
        return first;
    }

    /** A flight module is chosen, so flying is possible. */
    boolean available() {
        return mc.player != null && source() != null;
    }

    /** The name of the module that is used, for messages. */
    String sourceName() {
        Module m = source();
        return m == null ? "none" : m.title;
    }

    /** True while keys are held by this controller. */
    boolean holding() {
        return !down.isEmpty();
    }

    /** True when this controller switched a flight module on and it is still on. */
    boolean engaged() {
        return enabledByUs != null && enabledByUs.isActive();
    }

    /** A line for the player, once: why flying stopped or what was left on. Null when there is none. */
    String takeNotice() {
        String n = notice;
        notice = null;
        return n;
    }

    /**
     * Flies one tick toward the hover point (x, z the centre, y the feet). Returns false when it did not move this tick:
     * no module, a screen is open, the pause after a damage spike.
     */
    boolean drive(int tick, double hx, double hy, double hz) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            forget();
            return false;
        }
        lastDrive = tick;
        float health = p.getHealth();
        if (lastHealth >= 0 && health < lastHealth - DAMAGE_SPIKE && tick >= pausedUntil) {
            pausedUntil = tick + DAMAGE_PAUSE;
            notice = "Hurt while flying: holding still for " + DAMAGE_PAUSE / 20 + " seconds.";
        }
        lastHealth = health;
        // Never fly with a screen open (a container, the inventory, chat): the keys would be read by the screen's owner
        if (mc.screen != null || tick < pausedUntil) {
            releaseKeys();
            return false;
        }
        Module m = source();
        if (m == null) {
            releaseKeys();
            return false;
        }
        if (!m.isActive()) {
            m.toggle(); // not enable(): Meteor for 1.21.4 has no such method
            if (!m.isActive()) {
                // The toggle did not switch it on (something vetoed it): it is not ours to switch off later
                releaseKeys();
                return false;
            }
            enabledByUs = m;
        }
        FlySteer.Keys k = FlySteer.steer(p.getX(), p.getY(), p.getZ(), p.getYRot(), hx, hy, hz);
        set(mc.options.keyUp, k.forward());
        set(mc.options.keyDown, k.back());
        set(mc.options.keyLeft, k.left());
        set(mc.options.keyRight, k.right());
        set(mc.options.keyJump, k.up());
        // Shift belongs to the Printer while it sneaks to place: neither pressed nor let go of here
        if (sneakActive.getAsBoolean()) down.remove(mc.options.keyShift);
        else set(mc.options.keyShift, k.down());
        return true;
    }

    /** What {@link #land(int)} found. */
    enum Landing { DOWN, LANDED, STUCK }

    /** The player is in the air with a flight module on (or one this controller switched on): a trip on foot would start from there. */
    boolean airborne() {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || p.onGround() || p.isInWater() || p.isCreative() || p.isSpectator()) return false;
        Module m = source();
        return m != null && m.isActive() || enabledByUs != null;
    }

    /**
     * Brings the player down: holds the descend key and nothing else until they stand on something. Once down, a module
     * this controller switched on is switched off again (a module that was on before stays as it is). STUCK when there is no
     * safe ground to come down to (nothing solid or water below within {@link #LAND_SEARCH} blocks, or lava first) or a screen is open.
     */
    Landing land(int tick) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            forget();
            return Landing.STUCK;
        }
        lastDrive = tick;
        if (p.onGround() || p.isInWater()) {
            releaseKeys();
            shutdown();
            return Landing.LANDED;
        }
        if (mc.screen != null) {
            releaseKeys();
            return Landing.STUCK;
        }
        if (!landingBelow(p)) {
            releaseKeys();
            return Landing.STUCK;
        }
        Module m = source();
        if (m == null || !m.isActive()) {
            // Not flying: it falls by itself (the ground below was checked above)
            releaseKeys();
            return Landing.DOWN;
        }
        set(mc.options.keyUp, false);
        set(mc.options.keyDown, false);
        set(mc.options.keyLeft, false);
        set(mc.options.keyRight, false);
        set(mc.options.keyJump, false);
        if (sneakActive.getAsBoolean()) down.remove(mc.options.keyShift);
        else set(mc.options.keyShift, true);
        return Landing.DOWN;
    }

    private static final int LAND_SEARCH = 64;

    /** Something solid or water below, found before any lava or other fluid. */
    private boolean landingBelow(LocalPlayer p) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= LAND_SEARCH; i++) {
            pos.set(p.getBlockX(), p.getBlockY() - i, p.getBlockZ());
            BlockState s = mc.level.getBlockState(pos);
            if (s.getFluidState().is(FluidTags.WATER)) return true;
            if (!s.getFluidState().isEmpty()) return false;
            if (!s.getCollisionShape(mc.level, pos).isEmpty()) return true;
        }
        return false;
    }

    /** Stops moving, flight stays on (the player hovers). */
    void hold() {
        releaseKeys();
    }

    /** Called at the end of every tick the Printer runs: keys nobody drove this tick are let go of. */
    void endTick(int tick) {
        if (!down.isEmpty() && tick != lastDrive) releaseKeys();
    }

    /**
     * Lets go of every key and switches the module off if this controller switched it on and the player would not fall far.
     * Safe to call at any time, in or out of a world.
     */
    void shutdown() {
        releaseKeys();
        lastHealth = -1;
        pausedUntil = -1;
        Module m = enabledByUs;
        enabledByUs = null;
        if (m == null || !m.isActive()) return;
        // Out of the world nothing can be switched off cleanly (the module's own cleanup needs the player)
        if (!Utils.canUpdate()) return;
        if (safeToDrop()) {
            m.toggle();
        } else {
            notice = m.title + " is left on: you are high up and would fall. Land, then switch it off yourself.";
        }
    }

    private void forget() {
        releaseKeys();
        enabledByUs = null;
        lastHealth = -1;
    }

    private void set(KeyMapping key, boolean want) {
        if (want) {
            key.setDown(true);
            down.add(key);
        } else if (down.remove(key)) {
            key.setDown(Input.isPressed(key));
        }
    }

    private void releaseKeys() {
        if (down.isEmpty()) return;
        boolean shiftIsTheirs = sneakActive.getAsBoolean();
        for (KeyMapping key : down) {
            if (shiftIsTheirs && key == mc.options.keyShift) continue;
            key.setDown(Input.isPressed(key));
        }
        down.clear();
    }

    /** On the ground, in creative, or no more than a few blocks above something solid or water (not lava or another fluid). */
    private boolean safeToDrop() {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return false;
        if (p.onGround() || p.isCreative() || p.isSpectator()) return true;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= SAFE_DROP + 1; i++) {
            pos.set(p.getBlockX(), p.getBlockY() - i, p.getBlockZ());
            BlockState s = mc.level.getBlockState(pos);
            if (!s.getCollisionShape(mc.level, pos).isEmpty() || s.getFluidState().is(FluidTags.WATER)) return true;
        }
        return false;
    }
}
