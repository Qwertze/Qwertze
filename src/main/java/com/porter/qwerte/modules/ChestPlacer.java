package com.porter.qwerte.modules;

import com.porter.qwerte.compat.Players;
import com.porter.qwerte.utils.ChestStack;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Puts the next row of chests up for the delivery storage, as {@link ChestStack} plans it (the rules are there and are the proxy
 * plugin's): the first half by a sneaking click on the top of the chest below, standing on the side the chest should face; the
 * second half by a sneaking click on the side of the first, which joins it as the other half of the same double chest. Straight
 * on the blocks rather than through the block placer, which neither sneaks nor chooses the way the chest faces.
 */
final class ChestPlacer {
    interface Owner {
        /** Hotbar slots the bot keeps for itself. */
        Set<Integer> keep();

        boolean rotate();

        int delay();

        /** The sorter may walk. */
        boolean walk();

        void walkTo(BlockPos stand);

        void releaseGoal();

        boolean moving();

        /** The walk has left the stash area it started in, plus the margin. */
        boolean leftArea();

        /** The order is still wanted (not cancelled, parked or over). */
        boolean running();

        /** A carried stack is moved to a hotbar slot: its tracking goes with it. */
        void moved(int from, int to, ItemStack was);

        void pause(int ticks);

        void debug(String text);
    }

    /** @param placed the row stands; @param partial only the first half of a double chest does; @param failure why not, when not placed at all */
    record Outcome(boolean placed, boolean partial, String failure) {
    }

    private enum Step { Walk, Hold, Aim, Click, Wait }

    private static final int WALK_TIMEOUT = 20 * 60;
    private static final int WAIT_TICKS = 40;
    private static final int MAX_TRIES = 6;

    private final Owner owner;

    private ChestStack.Plan plan;
    private Step step;
    // 0: the first chest of the row, 1: the second half
    private int half;
    private BlockPos stand;
    private int facingX, facingZ;
    private int stepTicks, tries, openedTries, holdBounces;
    private final Set<BlockPos> skip = new HashSet<>();
    private boolean sneaking, clickIssued, swapped;
    private int heldSlot = -1;
    private Outcome result;

    ChestPlacer(Owner owner) {
        this.owner = owner;
    }

    boolean active() {
        return step != null;
    }

    /** The sneak is ours to start and stop. */
    boolean drivingInput() {
        return sneaking;
    }

    /** Starts on this plan. {@code firstStands}: the first half is not up yet. */
    void begin(ChestStack.Plan plan, boolean firstUp) {
        this.plan = plan;
        half = firstUp ? 1 : 0;
        skip.clear();
        tries = openedTries = holdBounces = 0;
        clickIssued = false;
        result = null;
        if (!pickStand()) {
            end(new Outcome(false, false, "nowhere to stand that can reach it"));
            return;
        }
        step = Step.Walk;
        stepTicks = 0;
    }

    /** One tick. @return how it came out, or null while it goes on */
    Outcome tick() {
        if (step == null) return take();
        stepTicks++;
        if (!owner.running()) {
            // The first half stands: it is kept as a single chest of the storage
            end(new Outcome(half == 1, half == 1, "the order ended"));
            return take();
        }
        switch (step) {
            case Walk -> walk();
            case Hold -> hold();
            case Aim -> aim();
            case Click -> click();
            case Wait -> await();
        }
        return take();
    }

    private Outcome take() {
        Outcome r = result;
        result = null;
        return r;
    }

    /** Giving way: nothing is left sneaking, and it starts again from the walk to its stand. */
    void release() {
        sneak(false);
        clickIssued = false;
        if (step != null) {
            step = Step.Walk;
            stepTicks = 0;
        }
    }

    /** Gives it up (giving way, a new world). */
    void abort(String why) {
        if (step == null) return;
        // The first half, if it stands, is kept
        end(new Outcome(half == 1, half == 1, why));
    }

    /** The player entity changed: nothing of the old inventory or keys is ours to touch. */
    void abandon() {
        if (sneaking) {
            sneaking = false;
            mc.options.keyShift.setDown(Input.isPressed(mc.options.keyShift));
        }
        swapped = false;
        heldSlot = -1;
        step = null;
        plan = null;
        clickIssued = false;
    }

    /** Puts the hand back as it was, once no screen is open and nothing is on the cursor. */
    void restoreHand() {
        if (!swapped) return;
        if (mc.player == null || mc.screen != null || mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.containerMenu.getCarried().isEmpty()) return;
        InvUtils.swapBack();
        swapped = false;
        heldSlot = -1;
    }

    private static BlockPos bp(com.porter.qwerte.stash.DeliveryBook.Pos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    private void sneak(boolean on) {
        KeyMapping key = mc.options.keyShift;
        if (on) {
            sneaking = true;
            key.setDown(true);
        }
        else if (sneaking) {
            sneaking = false;
            key.setDown(Input.isPressed(key));
        }
    }

    private void end(Outcome outcome) {
        sneak(false);
        owner.releaseGoal();
        step = null;
        clickIssued = false;
        result = outcome;
    }

    /** The next stand for the half being put up that is not given up on. */
    private boolean pickStand() {
        stand = null;
        if (half == 0) {
            for (ChestStack.Stand s : plan.firstStands()) {
                BlockPos cell = bp(s.cell());
                if (skip.contains(cell)) continue;
                stand = cell;
                facingX = s.facingX();
                facingZ = s.facingZ();
                return true;
            }
            return false;
        }
        for (var s : plan.secondStands()) {
            BlockPos cell = bp(s);
            if (skip.contains(cell)) continue;
            stand = cell;
            return true;
        }
        return false;
    }

    /** This stand did not work: the next one, or it is given up on. */
    private void nextStand(String why) {
        owner.debug("chest: stand " + (stand == null ? "?" : stand.toShortString()) + " given up on (" + why + ")");
        sneak(false);
        owner.releaseGoal();
        if (stand != null) skip.add(stand);
        clickIssued = false;
        if (++tries > MAX_TRIES || !pickStand()) {
            // The first half stands: it is kept as a single chest of the storage
            if (half == 1) end(new Outcome(true, true, why));
            else end(new Outcome(false, false, why));
            return;
        }
        step = Step.Walk;
        stepTicks = 0;
        openedTries = 0;
    }

    private void walk() {
        if (mc.player.blockPosition().equals(stand)) {
            owner.releaseGoal();
            step = Step.Hold;
            stepTicks = 0;
            return;
        }
        if (!owner.walk()) {
            nextStand("walking is off");
            return;
        }
        if (owner.leftArea()) {
            nextStand("the way there leaves the stash area");
            return;
        }
        if (stepTicks > WALK_TIMEOUT) {
            nextStand("couldn't get to the stand");
            return;
        }
        if (stepTicks == 1 || (stepTicks % 40 == 0 && !owner.moving())) owner.walkTo(stand);
    }

    private static boolean plainChest(ItemStack s) {
        return s.is(Items.CHEST) && !s.has(DataComponents.CONTAINER);
    }

    private int chestSlot() {
        for (int i = 0; i < 36; i++) if (plainChest(mc.player.getInventory().getItem(i))) return i;
        return -1;
    }

    /** A chest in an allowed hotbar slot and in the hand. */
    private void hold() {
        int src = chestSlot();
        if (src == -1) {
            end(new Outcome(half == 1, half == 1, "no chest is carried"));
            return;
        }
        Set<Integer> keep = owner.keep();
        int hotbar = -1;
        if (src < 9 && !keep.contains(src)) hotbar = src;
        else for (int i = 0; i < 9 && hotbar == -1; i++) if (!keep.contains(i) && mc.player.getInventory().getItem(i).isEmpty()) hotbar = i;
        if (hotbar == -1) {
            end(new Outcome(half == 1, half == 1, "no free hotbar slot to place a chest from"));
            return;
        }
        if (hotbar != src) {
            ItemStack was = mc.player.getInventory().getItem(src).copy();
            InvUtils.move().from(src).to(hotbar);
            owner.moved(src, hotbar, was);
            owner.pause(owner.delay());
            return;
        }
        heldSlot = hotbar;
        if (!swapped) {
            InvUtils.swap(hotbar, true);
            swapped = true;
        }
        else InvUtils.swap(hotbar, false);
        step = Step.Aim;
        stepTicks = 0;
        owner.pause(owner.delay());
    }

    private boolean handReady() {
        return heldSlot != -1 && Players.selectedSlot() == heldSlot && plainChest(mc.player.getMainHandItem());
    }

    /** Sneaking for a couple of ticks first: the server needs to have been told before the click, or it opens the chest. */
    private void aim() {
        sneak(true);
        if (stepTicks >= 3) {
            step = Step.Click;
            stepTicks = 0;
        }
    }

    private BlockPos want() {
        return bp(half == 0 ? plan.first() : plan.second());
    }

    private void click() {
        if (clickIssued) {
            if (stepTicks > 40) nextStand("the click never went out");
            return;
        }
        BlockPos want = want();
        if (!mc.level.getBlockState(want).isAir()) {
            nextStand("the cell is not free");
            return;
        }
        if (!handReady()) {
            // Back to holding the chest: bounded, or Hold - Aim - Click could go round for ever
            if (++holdBounces > 5) {
                holdBounces = 0;
                nextStand("the chest would not stay in the hand");
                return;
            }
            step = Step.Hold;
            stepTicks = 0;
            return;
        }

        BlockPos clicked;
        Direction face;
        Vec3 where;
        if (half == 0) {
            clicked = want.below();
            face = Direction.UP;
            BlockState below = mc.level.getBlockState(clicked);
            double top = below.getBlock() instanceof ChestBlock ? ChestStack.CHEST_TOP : 1.0;
            where = new Vec3(clicked.getX() + 0.5, clicked.getY() + top, clicked.getZ() + 0.5);
            // The way the bot looks decides the way the chest faces: the opposite of it
            // The look the click really has: the aim, or (rotation off) where the bot already looks
            float yaw = owner.rotate() ? (float) Rotations.getYaw(where) : mc.player.getYRot();
            Direction look = Direction.fromYRot(yaw);
            // The other way along the same axis is as good for a double chest
            if (Math.abs(look.getStepX()) != Math.abs(facingX) || Math.abs(look.getStepZ()) != Math.abs(facingZ)) {
                nextStand("the look would turn the chest the wrong way");
                return;
            }
        }
        else {
            clicked = bp(plan.first());
            BlockPos two = bp(plan.second());
            int dx = Integer.signum(two.getX() - clicked.getX()), dz = Integer.signum(two.getZ() - clicked.getZ());
            face = dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST : dz > 0 ? Direction.SOUTH : Direction.NORTH;
            where = new Vec3(clicked.getX() + 0.5 + dx * ChestStack.CHEST_SIDE, clicked.getY() + 0.5, clicked.getZ() + 0.5 + dz * ChestStack.CHEST_SIDE);
        }
        BlockHitResult hit = new BlockHitResult(where, face, clicked, false);
        clickIssued = true;
        Runnable action = () -> {
            // The rotation can finish after we have started to give way
            if (step != Step.Click || !owner.running() || !handReady() || !mc.level.getBlockState(want).isAir()) {
                clickIssued = false;
                return;
            }
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            mc.player.swing(InteractionHand.MAIN_HAND);
            clickIssued = false;
            step = Step.Wait;
            stepTicks = 0;
        };
        if (owner.rotate()) Rotations.rotate(Rotations.getYaw(where), Rotations.getPitch(where), action);
        else action.run();
    }

    private void await() {
        BlockPos want = want();
        // Sneaking didn't take and it opened the chest: closed, and another go
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
            if (++openedTries >= 3) nextStand("the click opened the chest instead of placing against it");
            else {
                step = Step.Aim;
                stepTicks = 0;
            }
            return;
        }
        BlockState st = mc.level.getBlockState(want);
        if (st.getBlock() instanceof ChestBlock) {
            if (half == 0 && plan.second() != null) {
                // The second half next, from a stand that sees the side of the first
                half = 1;
                tries = Math.max(0, tries - 1);
                openedTries = 0;
                skip.clear();
                if (!pickStand()) {
                    end(new Outcome(true, true, "no stand for the second half"));
                    return;
                }
                owner.releaseGoal();
                step = mc.player.blockPosition().equals(stand) ? Step.Aim : Step.Walk;
                stepTicks = 0;
                return;
            }
            if (half == 1 && st.hasProperty(ChestBlock.TYPE) && st.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
                owner.debug("chest: the second half did not join the first, two single chests stand");
            }
            end(new Outcome(true, false, null));
            return;
        }
        if (stepTicks > WAIT_TICKS) nextStand("the chest didn't appear");
    }
}
