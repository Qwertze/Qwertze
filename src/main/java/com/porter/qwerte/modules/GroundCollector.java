package com.porter.qwerte.modules;

import com.porter.qwerte.compat.Drops;
import com.porter.qwerte.stash.StackInfo;
import com.porter.qwerte.stash.Stash;
import com.porter.qwerte.stash.Stash.Box;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Picks up what other people left on the ground in the stash: dropped items lying still inside the stash area, when
 * nobody else is around. Items the player dropped or threw, or that fell from a block the player dug, are never
 * touched, and it never goes outside the area. Walks onto an item with the sorter's own Baritone goal, one item at a
 * time, between sorting jobs, and the sorter then puts away what it picked up like anything else it carries.
 */
final class GroundCollector {
    interface Owner {
        Stash stash();

        /** Collecting is on and possible right now: the setting, not scan-only, Baritone installed, module running. */
        boolean enabled();

        int range();

        /** The index of a free slot to carry stash items in, or -1. */
        int freeCarrySlot();

        boolean carries(int slot);

        /** Moves the stack in an inventory slot into a free carry slot (no screen, no cursor stack), returning that slot or -1. */
        int moveToCarry(int slot);

        /** The sorter put this stack in the slot itself, so it's stash cargo and not the player's own. */
        void held(int slot, ItemStack stack);

        void walkTo(BlockPos pos);

        void releaseGoal();

        /** Baritone may neither break nor place blocks until {@link #unconfine()}. */
        void confine();

        void unconfine();

        /** The trip is over: the sorter is between jobs again. */
        void finished(boolean pickedUp);

        void say(String text);

        void debug(String text);

        /** What is being done now and next, shown at every log level. */
        void step(String text);
    }

    // Ticks an item has to lie still before it is picked up
    static final int STILL_TICKS = 20 * 3;
    // The entity scan runs this often
    private static final int SCAN_TICKS = 4;
    // A trip that hasn't picked the item up by now is given up on, and the item left alone for a while
    private static final int TRIP_TIMEOUT = 20 * 20;
    private static final int SKIP_TICKS = 20 * 60 * 5;
    private static final int REPATH_TICKS = 20 * 3;
    private static final int STANDING_TICKS = 20 * 2;
    // How long after the item is gone to look for it in the inventory
    private static final int PICKUP_WAIT = 15;
    // Own items: a drop matches spawns for 5 s, and for half a second after its first match; digging for 8 s
    private static final int DROP_TICKS = 20 * 5;
    private static final int DROP_AFTER_MATCH = 10;
    private static final int DIG_TICKS = 20 * 8;
    private static final double DROP_RADIUS = 2.0;
    private static final double DROP_MIN_ABOVE = 0.3, DROP_MAX_ABOVE = 2.1;
    private static final double DIG_RADIUS_SQ = 1.5 * 1.5;
    private static final int OWN_CAP = 4096;
    private static final double MOVED_SQ = 0.05 * 0.05;
    private static final int VERTICAL = 4;
    private static final int OUTSIDE_MARGIN = 2;

    /** What the packet hook saw, handed over to the tick thread. */
    private record Event(boolean drop, BlockPos dug) {}

    private static final class Drop {
        final Vec3 feet;
        final int at;
        int firstMatch = -1;

        Drop(Vec3 feet, int at) {
            this.feet = feet;
            this.at = at;
        }
    }

    private record Dig(BlockPos pos, int at) {}

    private static final class Seen {
        Vec3 pos;
        int since;

        Seen(Vec3 pos, int since) {
            this.pos = pos;
            this.since = since;
        }
    }

    private final Owner owner;
    private int now;

    private final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
    private final Map<Integer, Seen> seen = new HashMap<>();
    private final Set<Integer> present = new HashSet<>();
    // Dropped by the player or from their dug blocks. Kept for the whole session, even when the entity leaves range
    private final Map<Integer, Boolean> mine = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
            return size() > OWN_CAP;
        }
    };
    private final Map<Integer, Integer> skipUntil = new HashMap<>();
    private final ArrayDeque<Drop> drops = new ArrayDeque<>();
    private final ArrayDeque<Dig> digs = new ArrayDeque<>();

    private boolean playerNoted;
    private String status = "nothing on the ground";

    // The trip in hand
    private boolean tripping, confined, startedInside;
    private int targetId;
    private ItemStack targetStack = ItemStack.EMPTY;
    private String targetName = "";
    private int tripStart, lastIssue, goneAt, standingSince;
    private ItemStack[] before;

    GroundCollector(Owner owner) {
        this.owner = owner;
    }

    /** Leaving the world or changing dimension: everything is forgotten. */
    void reset() {
        events.clear();
        seen.clear();
        present.clear();
        mine.clear();
        skipUntil.clear();
        drops.clear();
        digs.clear();
        playerNoted = false;
        abort();
        status = "nothing on the ground";
    }

    String status() {
        return status;
    }

    // Telling whose items they are

    /**
     * A packet the client is sending, possibly from another thread: queued for the tick thread.
     */
    void onPacket(Packet<?> packet) {
        if (Drops.isDrop(packet)) events.add(new Event(true, null));
        BlockPos dug = Drops.brokenBlock(packet);
        if (dug != null) events.add(new Event(false, dug.immutable()));
    }

    /**
     * Every tick: takes in what the packet hook saw, and every few ticks notes the item entities in the world, when
     * they last moved, and which are the player's.
     */
    void update() {
        now++;
        if (mc.level == null || mc.player == null) return;

        Event event;
        while ((event = events.poll()) != null) {
            if (event.drop) drops.addLast(new Drop(mc.player.position(), now));
            else digs.addLast(new Dig(event.dug, now));
        }
        if (now % SCAN_TICKS != 0) return;

        while (!drops.isEmpty() && now - drops.peekFirst().at > DROP_TICKS) drops.pollFirst();
        while (!digs.isEmpty() && now - digs.peekFirst().at > DIG_TICKS) digs.pollFirst();
        skipUntil.values().removeIf(until -> until <= now);

        present.clear();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity item) || item.isRemoved()) continue;
            int id = item.getId();
            present.add(id);

            Vec3 pos = item.position();
            Seen s = seen.get(id);
            if (s == null) {
                seen.put(id, new Seen(pos, now));
                if (!mine.containsKey(id) && playersOwn(pos)) mine.put(id, Boolean.TRUE);
            }
            else if (s.pos.distanceToSqr(pos) > MOVED_SQ) {
                s.pos = pos;
                s.since = now;
            }
        }

        // Entities out of range are forgotten as seen, but not as the player's: the same id coming back is theirs still
        if (seen.size() != present.size()) seen.keySet().retainAll(present);
    }

    private static boolean near(Vec3 spawn, Vec3 feet) {
        double dx = spawn.x - feet.x, dz = spawn.z - feet.z, dy = spawn.y - feet.y;
        return dx * dx + dz * dz <= DROP_RADIUS * DROP_RADIUS && dy >= DROP_MIN_ABOVE && dy <= DROP_MAX_ABOVE;
    }

    /**
     * Whether an item that just turned up is where and when the player dropped something, or dug a block.
     */
    private boolean playersOwn(Vec3 pos) {
        Vec3 here = mc.player.position();
        for (Drop drop : drops) {
            if (drop.firstMatch >= 0 && now - drop.firstMatch > DROP_AFTER_MATCH) continue;
            if (near(pos, drop.feet) || near(pos, here)) {
                if (drop.firstMatch < 0) drop.firstMatch = now;
                return true;
            }
        }

        BlockPos cell = BlockPos.containing(pos);
        for (Dig dig : digs) {
            if (Vec3.atCenterOf(dig.pos).distanceToSqr(pos) <= DIG_RADIUS_SQ) return true;
            if (Math.abs(cell.getX() - dig.pos.getX()) <= 1 && Math.abs(cell.getY() - dig.pos.getY()) <= 1
                && Math.abs(cell.getZ() - dig.pos.getZ()) <= 1) return true;
        }
        return false;
    }

    // Who is around

    /**
     * The name of another player that is loaded, or null if there is none.
     */
    private static String otherPlayer() {
        if (mc.level == null) return null;
        for (AbstractClientPlayer player : mc.level.players()) {
            if (player == mc.player || player.isRemoved() || player instanceof FakePlayerEntity) continue;
            return player.getName().getString();
        }
        return null;
    }

    // Where to look

    /**
     * The stash's containers with the range added around them, a few blocks up and down. Null without containers.
     */
    private AABB area() {
        Stash stash = owner.stash();
        if (stash == null) return null;

        // A perimeter is the area, grown by the range
        if (stash.hasArea()) {
            BlockPos lo = stash.areaMin(), hi = stash.areaMax();
            int range = owner.range();
            return new AABB(lo.getX() - range, lo.getY() - VERTICAL, lo.getZ() - range, hi.getX() + 1 + range, hi.getY() + 1 + VERTICAL, hi.getZ() + 1 + range);
        }
        if (stash.boxes.isEmpty()) return null;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Box box : stash.boxes) {
            for (BlockPos part : box.parts) {
                minX = Math.min(minX, part.getX());
                minY = Math.min(minY, part.getY());
                minZ = Math.min(minZ, part.getZ());
                maxX = Math.max(maxX, part.getX());
                maxY = Math.max(maxY, part.getY());
                maxZ = Math.max(maxZ, part.getZ());
            }
        }
        int r = owner.range();
        return new AABB(minX - r, minY - VERTICAL, minZ - r, maxX + 1 + r, maxY + 1 + VERTICAL, maxZ + 1 + r);
    }

    /**
     * Lava, fire, a cactus, magma, a berry bush or powder snow in the item's cell or the one below, or a chunk that
     * isn't loaded.
     */
    static boolean hazardous(BlockPos cell) {
        for (BlockPos pos : new BlockPos[] { cell, cell.below() }) {
            if (!mc.level.isLoaded(pos)) return true;
            BlockState state = mc.level.getBlockState(pos);
            if (state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)) return true;
        }
        return false;
    }

    /**
     * The items nobody asked us to leave alone: not the player's, lying still long enough, inside the area, not
     * somewhere dangerous, and not given up on lately.
     */
    private List<ItemEntity> candidates(AABB area) {
        List<ItemEntity> list = new ArrayList<>();
        if (area == null || mc.level == null) return list;

        for (Map.Entry<Integer, Seen> entry : seen.entrySet()) {
            int id = entry.getKey();
            if (mine.containsKey(id) || skipUntil.containsKey(id) || now - entry.getValue().since < STILL_TICKS) continue;
            if (!(mc.level.getEntity(id) instanceof ItemEntity item) || item.isRemoved() || item.getItem().isEmpty()) continue;
            if (area.contains(item.position()) && !hazardous(item.blockPosition())) list.add(item);
        }
        return list;
    }

    // Trips

    /**
     * Between jobs: starts a trip to the nearest item that is up for it.
     * @return true when a trip has begun, and the sorter should hand its ticks to {@link #tick()}
     */
    boolean next() {
        tripping = false;
        if (!owner.enabled()) {
            status = "nothing on the ground";
            return false;
        }

        AABB area = area();
        List<ItemEntity> found = candidates(area);
        String player = otherPlayer();
        if (found.isEmpty()) {
            if (player == null) playerNoted = false;
            status = "nothing on the ground";
            return false;
        }

        if (player != null) {
            status = String.format("waiting for %s to leave (%d item%s)", player, found.size(), found.size() == 1 ? "" : "s");
            if (!playerNoted) {
                playerNoted = true;
                owner.debug(String.format("Ground items waiting: %s is nearby.", player));
            }
            return false;
        }
        playerNoted = false;

        if (owner.freeCarrySlot() == -1) {
            status = "no free carry slot";
            return false;
        }

        Vec3 here = mc.player.position();
        ItemEntity item = found.stream().min(Comparator.comparingDouble(e -> e.position().distanceToSqr(here))).orElse(null);
        if (item == null) return false;

        targetId = item.getId();
        targetStack = item.getItem().copy();
        targetName = StackInfo.label(targetStack);
        BlockPos at = item.blockPosition();
        startedInside = area.contains(here);
        tripStart = now;
        lastIssue = now;
        goneAt = -1;
        standingSince = -1;
        before = snapshot();
        tripping = true;
        status = String.format("picking up %d %s", targetStack.getCount(), targetName);
        owner.say(String.format("Picking up %d %s from the ground at [%d, %d, %d].", targetStack.getCount(), targetName, at.getX(), at.getY(), at.getZ()));
        owner.step(String.format("ground: picking up %d stacks near %d %d %d; next: put them away.", found.size(), at.getX(), at.getY(), at.getZ()));
        confined = true;
        owner.confine();
        owner.walkTo(at);
        return true;
    }

    /**
     * One tick of the trip.
     */
    void tick() {
        if (!tripping) {
            owner.finished(false);
            return;
        }
        if (!owner.enabled()) {
            end(false);
            return;
        }

        // Someone turned up: stop at once, and carry on when everybody is gone
        String player = otherPlayer();
        if (player != null) {
            playerNoted = true;
            owner.debug(String.format("Ground items waiting: %s is nearby.", player));
            end(false);
            return;
        }

        Entity entity = mc.level.getEntity(targetId);
        if (!(entity instanceof ItemEntity item) || item.isRemoved()) {
            if (goneAt < 0) goneAt = now;
            // Picked up, if the inventory shows it a moment later
            if (gained() || now - goneAt >= PICKUP_WAIT) end(true);
            return;
        }

        BlockPos at = item.blockPosition();
        AABB area = area();
        if (area == null || !area.contains(item.position())) {
            giveUp(at, "it left the stash area");
            return;
        }
        if (hazardous(at)) {
            giveUp(at, "it is somewhere dangerous");
            return;
        }
        if (startedInside && !area.inflate(OUTSIDE_MARGIN).contains(mc.player.position())) {
            giveUp(at, "I ended up outside the stash area");
            return;
        }
        if (now - tripStart > TRIP_TIMEOUT) {
            giveUp(at, "it took longer than 20 seconds");
            return;
        }

        // Standing on it and still not picked up
        if (mc.player.getBoundingBox().intersects(item.getBoundingBox())) {
            if (standingSince < 0) standingSince = now;
            else if (now - standingSince >= STANDING_TICKS) {
                giveUp(at, "standing on it didn't pick it up");
                return;
            }
        }
        else standingSince = -1;

        // Told again every few seconds, following the item if it moved
        if (now - lastIssue >= REPATH_TICKS) {
            lastIssue = now;
            owner.walkTo(at);
        }
        status = String.format("picking up %d %s", item.getItem().getCount(), targetName);
    }

    /**
     * Drops the trip without blame: the sorter was told to give way, the module went off, or the world changed. The
     * item is tried again.
     */
    void abort() {
        tripping = false;
        before = null;
        release();
        status = "nothing on the ground";
    }

    private void release() {
        if (confined) {
            confined = false;
            owner.unconfine();
        }
    }

    private void giveUp(BlockPos at, String reason) {
        skipUntil.put(targetId, now + SKIP_TICKS);
        owner.say(String.format("Couldn't pick up the %s from the ground at [%d, %d, %d]: %s. Leaving it alone for 5 minutes.",
            targetName, at.getX(), at.getY(), at.getZ(), reason));
        end(false);
    }

    private void end(boolean tried) {
        boolean got = tried && claim();
        tripping = false;
        owner.releaseGoal();
        release();
        before = null;
        status = "nothing on the ground";
        owner.finished(got);
    }

    // The inventory

    private static ItemStack[] snapshot() {
        ItemStack[] stacks = new ItemStack[36];
        for (int slot = 0; slot < 36; slot++) stacks[slot] = mc.player.getInventory().getItem(slot).copy();
        return stacks;
    }

    /**
     * The slot gained the item we went for: it was empty, held something else or had fewer of it. Wear on a tool, or a
     * swap of armor or a totem, isn't that item turning up.
     */
    private boolean grew(ItemStack was, ItemStack now) {
        if (now.isEmpty() || !ItemStack.isSameItemSameComponents(now, targetStack)) return false;
        return was.isEmpty() || !ItemStack.isSameItemSameComponents(was, now) || now.getCount() > was.getCount();
    }

    private boolean gained() {
        if (before == null) return false;
        for (int slot = 0; slot < 36; slot++) {
            if (grew(before[slot], mc.player.getInventory().getItem(slot))) return true;
        }
        return false;
    }

    /**
     * What came into the inventory is stash cargo now: marked as held by the sorter, so a tool or food from the ground
     * isn't taken for the player's own, and moved into a carry slot if the pickup put it somewhere else, unless it
     * joined a stack of the player's.
     * @return whether anything was picked up
     */
    private boolean claim() {
        if (before == null) return false;
        boolean any = false;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack was = before[slot];
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!grew(was, stack)) continue;
            any = true;

            boolean merged = !was.isEmpty() && ItemStack.isSameItemSameComponents(was, stack);
            if (owner.carries(slot)) {
                owner.held(slot, stack);
            }
            else if (!merged) {
                int to = owner.moveToCarry(slot);
                if (to >= 0) owner.held(to, stack);
            }
        }
        return any;
    }
}
