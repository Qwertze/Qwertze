package com.porter.qwerte.modules;

import com.porter.qwerte.stash.Categories;
import com.porter.qwerte.stash.StackInfo;
import com.porter.qwerte.stash.StackInfo.BulkKey;
import com.porter.qwerte.stash.Stash;
import com.porter.qwerte.stash.Stash.Box;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Packs a full shulker's worth of one item from the category chests into an empty, unnamed shulker, which is then put
 * away in the item's own chest, so 27 slots become 1. Never mixed shulkers, never a named one, never a kit chest. This
 * class plans it and keeps the plan; the sorter walks, opens, places and breaks.
 */
final class Compactor {
    /**
     * What the compactor needs from the sorter.
     */
    interface Owner {
        Stash stash();

        /** Compacting is on, in Signs mode, and not paused after a shulker problem. */
        boolean on();

        int topUpAt();

        boolean excluded(ItemStack stack);

        List<Box> destinationsFor(ItemStack stack);

        boolean isMisc(Box box);

        boolean isKitBox(Box box);

        boolean isKitShulker(ItemStack stack);

        boolean signPending(Box box);

        boolean gatherBlocked(Box box);

        boolean fetchBlocked(Box box);

        Box nearest(List<Box> boxes);

        boolean closer(Box a, Box b);

        List<Integer> carriedSlots();

        List<Integer> activeCarried();

        Set<Integer> carry();

        int freeCarrySlot();

        int freeCarryCount();

        boolean kitHolds(int slot);

        boolean fetch(Box box, int slot, Predicate<ItemStack> wants);

        boolean gather(Box box);

        boolean pack(Plan plan);

        boolean recover(Plan plan, BlockPos at);

        void held(int slot, ItemStack stack);

        void moved();

        void headway();

        void clearOrphan(BlockPos at);

        /** The shulker standing at this place holds this many stacks now. */
        void progress(BlockPos at, int stacks);

        /** Something changed that planning depends on. */
        int version();

        /** More is still being sorted out of the drop-offs. */
        boolean dropOffsBusy();

        int ticks();

        void say(String text);

        void warn(String text);

        void alert(String text);

        void debug(String text);

        /** What is being done now and next, shown at every log level. */
        void step(String text);

        /** The plan, or where its shulker stands, changed: the sorter saves what a restart needs to finish it. */
        void changed();

        /**
         * No empty shulker is left: crafts one when crafting is on.
         * @return null when a craft was started (the sorter carries it on), "" when crafting is off, else why it can't be done
         */
        String craftEmpty(String purpose);
    }

    private static final int COOLDOWN = 20 * 60 * 10;
    private static final int DONE_COOLDOWN = 20 * 10;
    private static final int GEAR_COOLDOWN = 20 * 120;

    static final class Plan {
        final BulkKey key;
        final ItemStack sample;
        final String name;
        final boolean topUp;
        // Stacks the shulker held when this began, and should hold when it is done
        final int startSize, target;
        // Where the part-full shulker to top up is kept
        Box sourceBox;
        int sourceSlot = -1;
        // The shulker standing in the world between rounds, and what it held when last worked on
        BlockPos placedPos;
        String dimension = "";
        int placedSize;
        int jobs, acquireTries, slotHint = -1;
        boolean aborting;
        // Rebuilt after a restart: only the standing shulker is finished (packed with what is carried, broken, picked up)
        boolean finishOnly;

        Plan(BulkKey key, ItemStack sample, String name, boolean topUp, int startSize, int target) {
            this.key = key;
            this.sample = sample;
            this.name = name;
            this.topUp = topUp;
            this.startSize = startSize;
            this.target = target;
        }
    }

    private record Partial(Box box, int slot, int size) {}

    private record Found(Box box, int slot) {}

    private final Owner owner;
    Plan plan;
    private int gatherLeft;
    private final Map<BulkKey, Integer> cooldown = new HashMap<>();
    private int packed, freed, runPacked, runFreed;
    private String blocker = "";
    // When the stash last gave nothing to pack, so it isn't looked through again until something changes or a while passes
    private int noPlanVersion = -1, noPlanUntil;
    // What compacting last said was holding it back, so it is said again only when that changes
    private String lastWhy = "";

    Compactor(Owner owner) {
        this.owner = owner;
    }

    /**
     * Forgets the plan and the cooldowns: when sorting starts.
     */
    void reset() {
        plan = null;
        gatherLeft = 0;
        cooldown.clear();
        runPacked = runFreed = 0;
        blocker = "";
        lastWhy = "";
        noPlanVersion = -1;
    }

    private static String dimension() {
        return mc.level == null ? "" : mc.level.dimension().toString();
    }

    boolean active() {
        return plan != null;
    }

    /**
     * The plan as it is saved for a restart, or null without one.
     */
    Stash.Compact snapshot() {
        Plan p = plan;
        if (p == null) return null;
        Stash.Compact c = new Stash.Compact();
        c.sample = p.sample.copy();
        c.name = p.name;
        c.topUp = p.topUp;
        c.startSize = p.startSize;
        c.target = p.target;
        c.sourceBox = p.sourceBox == null ? null : p.sourceBox.pos;
        c.sourceSlot = p.sourceSlot;
        c.placedPos = p.placedPos;
        c.placedSize = p.placedSize;
        c.jobs = p.jobs;
        c.aborting = p.aborting;
        return c;
    }

    /**
     * Builds the plan again from what was saved, to finish the shulker it left standing. Null if the item can't be
     * packed any more.
     */
    Plan restore(Stash.Compact c, String dimension) {
        StackInfo info = StackInfo.of(c.sample);
        BulkKey key = info == null ? null : info.bulkKey();
        if (key == null) return null;
        Plan p = new Plan(key, c.sample.copyWithCount(1), c.name, c.topUp, c.startSize, c.target);
        p.sourceSlot = c.sourceSlot;
        p.placedPos = c.placedPos;
        p.dimension = dimension;
        p.placedSize = c.placedSize;
        p.jobs = c.jobs;
        p.aborting = c.aborting;
        p.finishOnly = true;
        plan = p;
        return p;
    }

    /**
     * For the summary at the end of a run: what was packed and freed, once.
     */
    int runPacked() {
        return runPacked;
    }

    String takeRunSummary() {
        if (runPacked == 0) return "";
        String text = String.format(" Packed %d shulkers, freed %d slots.", runPacked, runFreed);
        runPacked = runFreed = 0;
        return text;
    }

    /**
     * What compacting has done and what is holding it back.
     */
    String status() {
        return packed + " shulkers packed, " + freed + " slots freed"
            + (plan != null ? ", packing " + plan.name : blocker.isEmpty() ? "" : ", last blocker: " + blocker);
    }

    // What things are

    private static StackInfo info(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : StackInfo.of(stack);
    }

    private static BulkKey keyOf(ItemStack stack) {
        StackInfo info = info(stack);
        return info == null ? null : info.bulkKey();
    }

    private static boolean shulker(ItemStack stack) {
        return stack != null && !stack.isEmpty() && Utils.isShulker(stack.getItem());
    }

    private static boolean unnamed(ItemStack stack) {
        return !stack.has(DataComponents.CUSTOM_NAME);
    }

    private static boolean full(ItemStack stack) {
        return !stack.isEmpty() && stack.getCount() >= stack.getMaxStackSize();
    }

    /**
     * An unnamed shulker that is empty, or holds only this plan's item.
     */
    private boolean isPlanShulker(ItemStack stack, Plan p) {
        if (!shulker(stack) || !unnamed(stack) || owner.isKitShulker(stack)) return false;
        List<StackInfo> inside = StackInfo.of(stack).contents();
        if (inside.isEmpty()) return !p.topUp;
        for (StackInfo s : inside) if (!p.key.equals(s.bulkKey())) return false;
        return true;
    }

    private boolean matchesPlan(ItemStack stack) {
        return plan != null && full(stack) && plan.key.equals(keyOf(stack));
    }

    private boolean emptyShulker(ItemStack stack) {
        return shulker(stack) && unnamed(stack) && !owner.isKitShulker(stack) && StackInfo.of(stack).contents().isEmpty();
    }

    private static int stacksIn(ItemStack shulker) {
        return StackInfo.of(shulker).contents().size();
    }

    /**
     * Known not to be a shulker any more: the chunk is loaded and the block is something else. Not loaded is not known.
     */
    private static boolean goneAt(BlockPos pos) {
        return mc.level.isLoaded(pos) && !(mc.level.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock);
    }

    /**
     * A container its stacks and shulkers may be taken from: not a drop-off, a kit chest, one marked compactSkip, or one
     * whose sign is changing.
     */
    private boolean source(Box b) {
        return b.sortable() && !b.stale && !b.dropOff && !b.noCompact && !owner.signPending(b) && !owner.isKitBox(b)
            && b.labels.stream().noneMatch(Categories::isKeepWhole);
    }

    /**
     * Compacting only touches what has a real category to go back to.
     */
    private boolean hasHome(ItemStack stack) {
        List<Box> homes = owner.destinationsFor(stack);
        return !homes.isEmpty() && homes.stream().noneMatch(owner::isMisc);
    }

    // Reservation

    /**
     * The carry slot holding the plan's shulker, by what is in it and not where it is: slots change when a shulker is
     * moved to the hotbar or picked up again. -1 if there is none.
     */
    int findPlanShulker() {
        Plan p = plan;
        if (p == null) return -1;
        if (p.slotHint >= 0 && owner.carry().contains(p.slotHint) && isPlanShulker(mc.player.getInventory().getItem(p.slotHint), p)) return p.slotHint;

        int found = -1;
        for (int slot : owner.carriedSlots()) {
            if (owner.kitHolds(slot)) continue;
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!isPlanShulker(stack, p)) continue;
            if (found == -1 || !StackInfo.of(stack).contents().isEmpty()) found = slot;
        }
        p.slotHint = found;
        return found;
    }

    /**
     * Carry slots set aside for compacting: its shulker and the full stacks it is gathering. Nothing else touches them.
     */
    Set<Integer> reserved() {
        if (plan == null) return Set.of();
        Set<Integer> out = new HashSet<>();
        int shulker = findPlanShulker();
        if (shulker >= 0) out.add(shulker);
        for (int slot : owner.carriedSlots()) {
            if (!owner.kitHolds(slot) && matchesPlan(mc.player.getInventory().getItem(slot))) out.add(slot);
        }
        return out;
    }

    // Planning

    private boolean hasFull(Box box, BulkKey key) {
        for (ItemStack stack : box.contents) if (full(stack) && key.equals(keyOf(stack))) return true;
        return false;
    }

    private Box sourceBox(Plan p) {
        return owner.nearest(owner.stash().boxes.stream().filter(b -> source(b) && !owner.gatherBlocked(b) && hasFull(b, p.key)).toList());
    }

    private static final int LEAVE_MIN_STACKS = 3;

    /**
     * How many full stacks of the plan's item are still in the chests it may be gathered from.
     */
    private int fullStacksLeft(Plan p) {
        int n = 0;
        for (Box b : owner.stash().boxes) {
            if (!source(b) || owner.gatherBlocked(b)) continue;
            for (ItemStack stack : b.contents) if (full(stack) && p.key.equals(keyOf(stack))) n++;
        }
        return n;
    }

    private Partial partialOf(Box box, int slot, ItemStack shulker) {
        if (!unnamed(shulker) || owner.isKitShulker(shulker)) return null;
        List<StackInfo> inside = StackInfo.of(shulker).contents();
        if (inside.isEmpty() || inside.size() >= 27) return null;
        BulkKey key = inside.get(0).bulkKey();
        if (key == null) return null;
        for (StackInfo s : inside) if (!key.equals(s.bulkKey())) return null;
        return new Partial(box, slot, inside.size());
    }

    private void why(String text) {
        if (text.equals(lastWhy)) return;
        lastWhy = text;
        owner.say("Compaction: " + text + ".");
    }

    /**
     * Looks for something to compact: first topping up a shulker of an item that is part full, else 27 full stacks of
     * an item with an empty shulker to put them in. Only with the stash otherwise settled and nothing carried.
     * @return true when a plan was made
     */
    boolean start() {
        if (!owner.on()) {
            why("off, or paused for a few minutes after a shulker problem");
            return false;
        }
        if (!owner.activeCarried().isEmpty()) {
            why("still carrying " + owner.activeCarried().size() + " stacks to put away");
            return false;
        }
        if (owner.version() == noPlanVersion && owner.ticks() < noPlanUntil) return false;

        Stash stash = owner.stash();
        Map<BulkKey, Integer> full = new LinkedHashMap<>();
        Map<BulkKey, Map<Box, Integer>> where = new HashMap<>();
        Map<BulkKey, ItemStack> sample = new HashMap<>();
        Map<BulkKey, StackInfo> infos = new HashMap<>();
        Map<BulkKey, Partial> partials = new HashMap<>();
        Map<BulkKey, Boolean> homed = new HashMap<>();
        for (Box box : stash.boxes) {
            if (!source(box)) continue;
            for (int i = 0; i < box.contents.length; i++) {
                ItemStack stack = box.contents[i];
                if (stack.isEmpty()) continue;

                if (shulker(stack)) {
                    Partial part = partialOf(box, i, stack);
                    if (part == null) continue;
                    StackInfo first = StackInfo.of(stack).contents().get(0);
                    BulkKey key = first.bulkKey();
                    if (owner.excluded(first.stack) || !homed.computeIfAbsent(key, k -> hasHome(first.stack))) continue;
                    Partial had = partials.get(key);
                    if (had == null || part.size() < had.size()) partials.put(key, part);
                    sample.putIfAbsent(key, first.stack);
                    infos.putIfAbsent(key, first);
                    continue;
                }

                if (!full(stack)) continue;
                StackInfo item = StackInfo.of(stack);
                BulkKey key = item.bulkKey();
                if (key == null || owner.excluded(stack)) continue;
                if (!homed.computeIfAbsent(key, k -> hasHome(stack))) continue;
                full.merge(key, 1, Integer::sum);
                where.computeIfAbsent(key, k -> new HashMap<>()).merge(box, 1, Integer::sum);
                sample.putIfAbsent(key, stack);
                infos.putIfAbsent(key, item);
            }
        }

        Plan best = null;
        int bestScore = 0;
        String held = "";
        for (var entry : full.entrySet()) {
            BulkKey key = entry.getKey();
            int stacks = entry.getValue();
            Integer cool = cooldown.get(key);
            if (cool != null && owner.ticks() < cool) {
                held = "cooling down for " + infos.get(key).name;
                continue;
            }

            Partial part = partials.get(key);
            Plan candidate;
            int score;
            int room = part == null ? 0 : 27 - part.size();
            StackInfo ref = infos.get(key);
            // Things that don't stack (tools, armor, flint and steel...) are packed as soon as there are two, and added to a
            // part-full shulker as soon as there is one; a shulker of them is filled with as many as there are
            boolean single = ref.maxStack == 1;
            if (part != null && stacks >= (single ? 1 : Math.min(room, owner.topUpAt()))) {
                int add = Math.min(room, stacks);
                // A single loose piece is not worth a trip while more is still being sorted out of the drop-offs
                if (single && add < 2 && owner.dropOffsBusy()) {
                    held = "waiting for the drop-offs to be sorted";
                    continue;
                }
                candidate = new Plan(key, sample.get(key), ref.name, true, part.size(), part.size() + add);
                candidate.sourceBox = part.box();
                candidate.sourceSlot = part.slot();
                score = 1000 + add;
            }
            else if (single ? stacks >= 2 : stacks >= 27) {
                int target = single ? Math.min(27, stacks) : 27;
                candidate = new Plan(key, sample.get(key), ref.name, false, 0, target);
                score = target;
            }
            else continue;
            if (best == null || score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }

        if (best == null) {
            blocker = held;
            noPlanVersion = owner.version();
            noPlanUntil = owner.ticks() + 100;
            String closest = "no stacks of anything with a category";
            int most = 0;
            for (var entry : full.entrySet()) {
                if (entry.getValue() <= most) continue;
                most = entry.getValue();
                Box chest = where.get(entry.getKey()).entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
                closest = infos.get(entry.getKey()).name + " " + most + "/27, most in the chest at " + chest.pos.toShortString();
            }
            why(held.isEmpty() ? "nothing has a full shulker's worth (closest: " + closest + ")" : held + " (closest: " + closest + ")");
            return false;
        }

        // The shulker's slot, a stack to carry and one slot kept free for picking up (the shulker may already be carried)
        plan = best;
        int needFree = findPlanShulker() >= 0 ? 2 : 3;
        plan = null;
        if (owner.freeCarryCount() < needFree) {
            blocker = "carry room " + owner.freeCarryCount() + " < " + needFree;
            why("not enough carry room for " + best.name + " (" + owner.freeCarryCount() + " free slots, need " + needFree + ")");
            return false;
        }
        if (!best.topUp) {
            plan = best;
            boolean available = findPlanShulker() >= 0 || findEmptyShulker() != null;
            plan = null;
            if (!available) {
                String crafting = owner.craftEmpty("compacting " + best.name);
                if (crafting == null) {
                    blocker = "crafting an empty shulker";
                    return false;
                }
                blocker = "no empty shulker";
                why("no empty shulker for " + best.name + " (" + best.target + " stacks waiting)" + (crafting.isEmpty() ? "" : "; couldn't craft one: " + crafting));
                return false;
            }
        }

        plan = best;
        owner.changed();
        blocker = "";
        lastWhy = "";
        int moves = best.target - best.startSize;
        owner.say(String.format("Compacting %d stacks of %s into a shulker, frees %d slots.", moves, best.name, moves - (best.topUp ? 0 : 1)));
        return true;
    }

    /**
     * An unnamed empty shulker in the stash that may be taken: in a chest signed for shulkers first, then the nearest,
     * never one in a kit chest or a container marked compactSkip.
     */
    private Found findEmptyShulker() {
        Found best = null;
        boolean bestSigned = false;
        for (Box box : owner.stash().boxes) {
            if (!box.sortable() || box.stale || box.noCompact || owner.signPending(box) || owner.fetchBlocked(box) || owner.isKitBox(box)) continue;
            if (box.labels.stream().anyMatch(Categories::isKeepWhole)) continue;
            boolean signed = box.labels.stream().anyMatch(l -> l.toLowerCase(Locale.ROOT).contains("shulker"));
            for (int i = 0; i < box.contents.length; i++) {
                if (!emptyShulker(box.contents[i])) continue;
                if (best == null || (signed && !bestSigned) || (signed == bestSigned && owner.closer(box, best.box()))) {
                    best = new Found(box, i);
                    bestSigned = signed;
                }
                break;
            }
        }
        return best;
    }

    private String abortReason(Plan p) {
        if (!owner.on()) return "compacting is off or paused";
        if (p.jobs > 12 + 4 * p.target) return "it took too many trips";
        if (!hasHome(p.sample)) return "its category is gone";
        return null;
    }

    /**
     * Gives the plan up. A shulker standing in the world is still fetched back first.
     */
    void abort(String why) {
        Plan p = plan;
        if (p == null) return;
        cooldown.put(p.key, owner.ticks() + COOLDOWN);
        blocker = why;
        owner.debug("Compacting " + p.name + " given up on: " + why + ".");
        p.aborting = true;
        owner.changed();
    }

    /**
     * Forgets the plan altogether, after the shulker was abandoned.
     */
    void drop() {
        if (plan != null) plan.aborting = true;
        plan = null;
    }

    /**
     * The next job of the plan in hand, or false when it is over (done or given up on) and normal sorting carries on.
     */
    boolean next() {
        Plan p = plan;
        if (p == null) return false;

        // A shulker standing in another dimension is left where it is: it stays in the records
        if (p.placedPos != null && !p.dimension.isEmpty() && !p.dimension.equals(dimension())) {
            owner.alert("The shulker I left standing at " + p.placedPos.toShortString() + " is in another dimension, leaving it there.");
            plan = null;
            return false;
        }

        if (!p.aborting) {
            String why = abortReason(p);
            if (why != null) abort(why);
        }
        if (p.aborting) {
            if (p.placedPos != null && !goneAt(p.placedPos)) return owner.pack(p);
            if (p.placedPos != null) return recoverLost(p);
            plan = null;
            return false;
        }
        if (p.placedPos != null && goneAt(p.placedPos)) return recoverLost(p);

        // Rebuilt after a restart: the standing shulker is packed with what is carried and picked up, then it is over
        if (p.finishOnly) {
            if (p.placedPos != null) {
                packStep(p, 0);
                return owner.pack(p);
            }
            cooldown.put(p.key, owner.ticks() + DONE_COOLDOWN);
            plan = null;
            owner.changed();
            return false;
        }

        boolean placedNow = p.placedPos != null;
        int shulkerSlot = placedNow ? -1 : findPlanShulker();
        int have = placedNow ? p.placedSize : shulkerSlot >= 0 ? stacksIn(mc.player.getInventory().getItem(shulkerSlot)) : -1;
        int carriedMatch = 0;
        for (int slot : owner.carriedSlots()) if (matchesPlan(mc.player.getInventory().getItem(slot))) carriedMatch++;
        int free = owner.freeCarryCount();

        if (have >= p.target) {
            if (placedNow) {
                packStep(p, 0);
                return owner.pack(p);
            }
            complete(p, have);
            return false;
        }
        if (have < 0) {
            if (++p.acquireTries > 2) {
                abort("couldn't get a shulker");
                return next();
            }
            return acquire(p);
        }

        int remaining = p.target - have;
        if (carriedMatch >= remaining || (carriedMatch > 0 && free <= 1)) {
            packStep(p, carriedMatch);
            return owner.pack(p);
        }

        Box from = sourceBox(p);
        if (from == null) {
            if (carriedMatch > 0) {
                packStep(p, carriedMatch);
                return owner.pack(p);
            }
            abort("not enough left to pack");
            return next();
        }

        // However much room there is, in rounds: a batch, into the shulker, the next batch. One slot stays free
        int batch = Math.min(remaining - carriedMatch, free - 1);
        if (batch <= 0) {
            abort("no carry room");
            return next();
        }
        gatherLeft = batch;
        p.jobs++;
        owner.step(String.format("compacting %s: gathering %d stacks (%d/%d packed); next: pack them into %s.", p.name, batch, have, p.target, shulkerText(p)));
        return owner.gather(from);
    }

    private static String xyz(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String shulkerText(Plan p) {
        if (p.placedPos != null) return "the shulker at " + xyz(p.placedPos);
        return mc.player == null ? "a shulker" : "a shulker beside " + xyz(mc.player.blockPosition());
    }

    private void packStep(Plan p, int stacks) {
        owner.step(String.format("compacting %s: packing %d stacks into %s (%d/%d packed); next: break it and pick it up.", p.name,
            stacks, shulkerText(p), Math.max(p.placedSize, p.startSize), p.target));
    }

    private boolean acquire(Plan p) {
        Box box;
        int slot;
        Predicate<ItemStack> wants;
        if (p.topUp) {
            box = p.sourceBox;
            slot = p.sourceSlot;
            if (box == null || !box.known() || slot < 0 || slot >= box.contents.length || !isPlanShulker(box.contents[slot], p)) {
                abort("the shulker to top up has moved");
                return next();
            }
            wants = s -> isPlanShulker(s, p);
        }
        else {
            Found found = findEmptyShulker();
            if (found == null) {
                abort("no empty shulker");
                return next();
            }
            box = found.box();
            slot = found.slot();
            wants = this::emptyShulker;
        }
        p.jobs++;
        owner.step(String.format("compacting %s: fetching %s from the chest at %s; next: gather %d stacks (%d/%d packed).", p.name,
            p.topUp ? "the shulker to top up" : "an empty shulker", xyz(box.pos), p.target - p.startSize, p.startSize, p.target));
        return owner.fetch(box, slot, wants);
    }

    /**
     * The shulker that was put down is gone from where it was: said loudly, and looked for on the ground.
     */
    private boolean recoverLost(Plan p) {
        BlockPos at = p.placedPos;
        p.placedPos = null;
        p.aborting = true;
        owner.changed();
        owner.alert(String.format("The shulker holding %d stacks of %s that I put down at %s is gone. Looking for it on the ground.", p.placedSize, p.name, at.toShortString()));
        return owner.recover(p, at);
    }

    private void complete(Plan p, int size) {
        int stacks = size - p.startSize;
        if (!p.topUp) {
            packed++;
            runPacked++;
        }
        int gain = stacks - (p.topUp ? 0 : 1);
        freed += gain;
        runFreed += gain;
        owner.say(String.format("Packed %d stacks of %s into a shulker.", stacks, p.name));
        // Topping up gear shulkers one piece at a time is not worth the churn: a longer wait
        boolean gear = p.topUp && p.sample.getMaxStackSize() == 1;
        cooldown.put(p.key, owner.ticks() + (gear ? GEAR_COOLDOWN : DONE_COOLDOWN));
        plan = null;
        owner.changed();
        owner.headway();
    }

    // Working

    /**
     * Takes up to the round's number of full stacks of the plan's item into free carry slots, checking each against the
     * plan's key as it is in the window. One slot always stays free.
     */
    boolean gatherOne(Box from) {
        Plan p = plan;
        if (p == null || gatherLeft <= 0 || owner.freeCarryCount() <= 1) return false;

        int into = owner.freeCarrySlot();
        if (into == -1) return false;
        for (int i = 0; i < from.contents.length; i++) {
            ItemStack stack = from.contents[i];
            if (!full(stack) || !p.key.equals(keyOf(stack))) continue;
            owner.held(into, stack);
            InvUtils.move().fromId(i).toId(inventoryToMenu(into, from.contents.length));
            gatherLeft--;
            return true;
        }
        return false;
    }

    private static int inventoryToMenu(int index, int containerSize) {
        return index < 9 ? containerSize + 27 + index : containerSize + index - 9;
    }

    /**
     * Puts one more reserved stack into the open shulker, until it holds what the plan wants. The shulker's own contents
     * are checked first: anything that is not the plan's item and the plan is given up on.
     */
    boolean packOne(AbstractContainerMenu menu) {
        Plan p = plan;
        if (p == null || p.aborting) return false;

        int size = 0;
        for (int i = 0; i < 27; i++) {
            ItemStack here = menu.getSlot(i).getItem();
            if (here.isEmpty()) continue;
            if (!p.key.equals(keyOf(here))) {
                owner.warn("The shulker I'm packing holds something that isn't " + p.name + ", not adding to it.");
                abort("the shulker holds something else");
                return false;
            }
            size++;
        }
        if (size >= p.target) return false;

        for (int slot : owner.carriedSlots()) {
            if (owner.kitHolds(slot) || !matchesPlan(mc.player.getInventory().getItem(slot))) continue;
            InvUtils.shiftClick().slotId(inventoryToMenu(slot, 27));
            owner.moved();
            return true;
        }
        return false;
    }

    /**
     * The round is in. More wanted and more to be had: the window is closed and the shulker stays where it is while the
     * next round is gathered. Otherwise it is broken and picked up.
     * @return true to leave it standing
     */
    boolean afterPacking(AbstractContainerMenu menu, int actions, BlockPos placed) {
        Plan p = plan;
        if (p == null) return false;

        int size = 0;
        for (int i = 0; i < 27; i++) if (!menu.getSlot(i).getItem().isEmpty()) size++;
        p.placedSize = size;
        owner.progress(placed, size);

        // Stacks are waiting and nothing went in: it is not going to, so don't come back for it
        int waiting = 0;
        for (int slot : owner.carriedSlots()) if (matchesPlan(mc.player.getInventory().getItem(slot))) waiting++;
        if (actions == 0 && waiting > 0) {
            abort("nothing could be packed");
            return false;
        }

        // With fewer than 3 stacks left to come the shulker is not left standing for them: it is picked up, and the rest
        // goes in the next time
        boolean more = !p.aborting && !p.finishOnly && size < p.target && sourceBox(p) != null && owner.freeCarryCount() >= 2
            && Math.min(p.target - size, fullStacksLeft(p)) >= LEAVE_MIN_STACKS;
        if (more) {
            p.placedPos = placed;
            p.dimension = dimension();
        }
        return more;
    }

    /**
     * The shulker was picked up again: it is in this carry slot, and the plan knows where.
     */
    void collected(int slot, BlockPos at, ItemStack stack) {
        Plan p = plan;
        if (p == null) {
            owner.clearOrphan(at);
            return;
        }
        p.placedPos = null;
        p.slotHint = slot;
        if (!p.aborting && slot >= 0 && !stack.isEmpty() && stacksIn(stack) != p.placedSize) {
            owner.alert(String.format("The shulker of %s I picked up holds %d stacks, I expected %d (%d missing). Something may have taken from it.",
                p.name, stacksIn(stack), p.placedSize, p.placedSize - stacksIn(stack)));
            cooldown.put(p.key, owner.ticks() + COOLDOWN);
        }
        owner.clearOrphan(at);
    }
}
