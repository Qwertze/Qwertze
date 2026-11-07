package com.porter.qwerte.modules;

import com.porter.qwerte.compat.Players;
import com.porter.qwerte.compat.SignEditors;
import com.porter.qwerte.stash.Categories;
import com.porter.qwerte.stash.Containers;
import com.porter.qwerte.stash.StackInfo;
import com.porter.qwerte.stash.Stash;
import com.porter.qwerte.stash.Stash.Box;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The librarian. An item no container has a sign for gets a new category on an empty chest next to the signed ones, with
 * a sign the sorter puts up itself: it holds a sign, sneaks, right clicks the side of the chest, takes over the editor
 * the server opens, writes the text and reads it back. It never places or breaks a chest, and never breaks a sign.
 */
final class StashLabeller {
    /**
     * What the labeller needs from the sorter.
     */
    interface Owner {
        Stash stash();

        Set<Integer> carry();

        Set<Integer> keep();

        List<Integer> activeCarried();

        List<Integer> carriedSlots();

        List<Box> destinationsFor(ItemStack stack);

        boolean isMisc(Box box);

        boolean mixedShulker(ItemStack stack);

        boolean keepWholeExists();

        int misplacedCount(Box box);

        boolean signPending(Box box);

        /** Someone used it and it's waiting to be read again. */
        boolean rereadPending(Box box);

        /** Taking signs out of it is not worth a trip right now. */
        boolean fetchBlocked(Box box);

        Box nearest(List<Box> boxes);

        int freeCarrySlot();

        boolean autoLabel();

        boolean signsMode();

        int gapTicks();

        boolean walk();

        boolean rotate();

        int delay();

        int clock();

        int version();

        /** The sorter is running its own jobs: not giving way to anyone. */
        boolean running();

        /** Someone else is using the client right now. */
        boolean foreign();

        void beginLabel(Box target);

        void endLabel();

        boolean fetchSigns(Box from);

        void walkTo(BlockPos pos);

        void releaseGoal();

        boolean moving();

        /** The walk has left the stash area it started in, plus the margin. */
        boolean leftArea();

        /** Chest, trapped chest or barrel, for messages. */
        String blockName(Box box);

        void pause(int ticks);

        /** Closes the container window that is open, if it is one of ours. */
        void closeOurWindow();

        boolean containerOpen();

        void held(int slot, ItemStack stack);

        void labelled(Box box);

        void say(String text);

        void warn(String text);

        /** Needs a person: shown at every log level, and whispered to the whitelist. */
        void alert(String text);

        /** What is being done now and next, shown at every log level. */
        void step(String text);

        void debug(String text);
    }

    private static final int MAX_LABELS_PER_RUN = 20;
    private static final int WALK_TIMEOUT = 20 * 60;
    private static final int WAIT_TICKS = 100;
    private static final int MAX_CLICKS = 2;
    private static final int MAX_FAILURES = 2;
    private static final int SIGN_LINE_LENGTH = 15;
    private static final DateTimeFormatter SIGN_DATE = DateTimeFormatter.ofPattern("MM-dd-yyyy");

    private enum Step { Walk, Hold, Aim, Click, Editor, Write, Verify, Reopen }

    private record Plan(String text, Box box, Containers.Face face, String item) {
        Plan(String text, Box box, Containers.Face face) {
            this(text, box, face, "");
        }
    }

    private static final class Group {
        final ItemStack sample;
        int stacks;
        boolean sameLabel;

        Group(ItemStack sample) {
            this.sample = sample;
        }
    }

    private final Owner owner;

    // The sign being put up, if any
    private Step step;
    private Step resume;
    private Box box;
    private String text;
    private String[] lines;
    private Containers.Face face;
    private int stepTicks;
    private int clicks;
    private boolean clicked, clickIssued;
    private boolean reopened, reopenRequested;
    private boolean editorReady, editorFront = true;
    private BlockPos editorAt;
    private int heldFrom = -1, heldTo = -1, heldSlot = -1;
    // The slot a user's item was moved into when the sign took its hotbar slot: not cargo
    private int displacedSlot = -1;
    private boolean swapped;
    private boolean sneaking;
    // The hand and hotbar are put back only when no screen is open and nothing is on the cursor
    private boolean restorePending;
    private int age, clickAge = -1000;
    private boolean reopenAfterHold;
    private final Set<BlockPos> preexisting = new HashSet<>();

    // Signs kept for labelling, not to be put away; when the last sign went up; how many this run; failures this run
    private final Set<Integer> reserved = new HashSet<>();
    private int lastLabel = -100000;
    private int placed;
    private int failures;
    private boolean failWarned, capWarned, noSignsWarned;
    // Signs the sorter put up itself and could not write on, and how often writing them has been tried
    private final Map<BlockPos, Plan> blank = new LinkedHashMap<>();
    private final Map<BlockPos, Integer> blankTries = new HashMap<>();
    private final Set<BlockPos> ours = new HashSet<>();
    private int nextScan;
    private int scanVersion = -1;
    private boolean hadGroups, hadCandidates;
    private Set<Box> anchored = Set.of();
    private String summary = "Nothing looked at yet.";

    StashLabeller(Owner owner) {
        this.owner = owner;
    }

    /**
     * Forgets everything about the run: when sorting starts.
     */
    void reset() {
        release();
        // The world is gone or changing: indices into the old inventory mean nothing now
        swapped = false;
        heldFrom = heldTo = heldSlot = displacedSlot = -1;
        restorePending = false;
        reopenAfterHold = false;
        clickAge = -1000;
        step = null;
        resume = null;
        box = null;
        reserved.clear();
        lastLabel = -100000;
        placed = 0;
        failures = 0;
        failWarned = capWarned = noSignsWarned = false;
        blank.clear();
        blankTries.clear();
        ours.clear();
        nextScan = 0;
        scanVersion = -1;
        summary = "Nothing looked at yet.";
    }

    boolean drivingInput() {
        return sneaking;
    }

    /**
     * The slot holds something of the player's that was moved out of the way of the sign.
     */
    boolean displaced(int slot) {
        return slot == displacedSlot;
    }

    /**
     * A chest window that is the answer to our own click on the target, soon after it: never one the player opened.
     */
    boolean ownsChest(AbstractContainerMenu menu) {
        return box != null && (step == Step.Editor || step == Step.Reopen) && clicked && age - clickAge <= 20
            && menu instanceof ChestMenu && menu.slots.size() - 36 == box.parts.size() * 27;
    }

    /**
     * The player entity changed (died, changed dimension): nothing of the old inventory or the old keys is ours to touch.
     */
    void abandon() {
        if (sneaking) {
            sneaking = false;
            mc.options.keyShift.setDown(Input.isPressed(mc.options.keyShift));
        }
        swapped = false;
        heldFrom = heldTo = heldSlot = displacedSlot = -1;
        restorePending = false;
        reopenAfterHold = false;
        step = null;
        box = null;
        face = null;
        clickIssued = false;
    }

    boolean reservedSlot(int slot) {
        return reserved.contains(slot) && mc.player.getInventory().getItem(slot).is(ItemTags.SIGNS);
    }

    /**
     * After the click on the chest, until the text is written, the sign must not be left half done: nothing is given way
     * to in those few ticks, none of which clicks anything.
     */
    boolean critical() {
        return step == Step.Editor || step == Step.Write || step == Step.Verify;
    }

    /**
     * Sneaking is ours to start and stop: the key is pressed through the options, which the player's own key check
     * (the physical state) never sees.
     */
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

    /**
     * Lets go of what a sign in progress holds: the sneak.
     */
    void release() {
        sneak(false);
    }

    // Choosing what to do

    /**
     * Between the jobs that read the stash: if something is homeless, a chest to label and a sign to write it on exist,
     * start on it.
     * @return true when a job was started
     */
    boolean next() {
        if (!owner.autoLabel() || !owner.signsMode()) {
            reserved.clear();
            return false;
        }

        Stash stash = owner.stash();
        int now = owner.clock();
        if (!blank.isEmpty() && now - lastLabel >= owner.gapTicks()) {
            var it = blank.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                Plan old = entry.getValue();
                BlockPos at = entry.getKey();
                Containers.Signs signs = Containers.read(stash, old.box());
                // Gone, or written on by someone: nothing left to do
                if (!signThere(at) || signs == null || !signs.labels().isEmpty() || !old.box().labels.isEmpty()) {
                    it.remove();
                    continue;
                }
                it.remove();
                owner.say(String.format("Going back to the blank sign at %s to write \"%s\" on it.", at.toShortString(), old.text()));
                start(old);
                resume = Step.Hold;
                reopenAfterHold = true;
                return true;
            }
        }
        if (failures >= MAX_FAILURES) {
            if (!failWarned) {
                failWarned = true;
                owner.warn(String.format("Putting up signs failed %d times this run, not labelling any more until sorting is restarted. Stacks with no home go to a misc or unsigned container.", failures));
            }
            reserved.clear();
            return false;
        }
        if (placed >= MAX_LABELS_PER_RUN) {
            if (!capWarned) {
                capWarned = true;
                owner.warn(String.format("Put up %d signs this run, which is the most in one run. Not labelling any more until sorting is restarted.", placed));
            }
            reserved.clear();
            return false;
        }
        if (now - lastLabel < owner.gapTicks()) return false;

        // Looking at everything costs: only when something changed, or now and then
        if (reserved.isEmpty() && now < nextScan && owner.version() == scanVersion) return false;
        nextScan = now + 100;
        scanVersion = owner.version();

        Plan plan = plan();
        if (plan == null) {
            // Signs fetched for this are kept while there is still homeless things and chests to put them on, and put away
            // once, when there is not: otherwise they go back in a container only to be fetched again
            if (!(hadGroups && hadCandidates)) reserved.clear();
            return false;
        }

        // Signs fetched from the stash or carried before the sorter's own, which are the last thing to use
        int signSlot = findSignSlot(false);
        if (signSlot == -1) {
            List<Box> holding = stash.boxes.stream().filter(b -> b.sortable() && !owner.fetchBlocked(b) && !owner.signPending(b) && holdsSigns(b)).toList();
            Box from = owner.nearest(holding);
            if (from == null || owner.freeCarrySlot() == -1) {
                if (findSignSlot(true) != -1) {
                    noSignsWarned = false;
                    return start(plan);
                }
                if (from != null) return false;
                if (!noSignsWarned) {
                    noSignsWarned = true;
                    owner.alert(String.format("No signs to label new categories (\"%s\" and others), so those items go to the catch-all container. Put signs in your inventory or in any container of the stash.", plan.text()));
                }
                return false;
            }
            return owner.fetchSigns(from);
        }
        noSignsWarned = false;
        if (owner.carry().contains(signSlot)) reserved.add(signSlot);

        return start(plan);
    }

    private static boolean holdsSigns(Box box) {
        for (ItemStack stack : box.contents) if (stack.is(ItemTags.SIGNS)) return true;
        return false;
    }

    /**
     * Takes a stack of signs out of the container the job is at into a free carry slot, where they are kept for labelling.
     */
    boolean fetchSignsOne(Box from, int actions) {
        if (actions > 0) return false;

        int into = owner.freeCarrySlot();
        if (into == -1) return false;
        for (int i = 0; i < from.contents.length; i++) {
            ItemStack stack = from.contents[i];
            if (!stack.is(ItemTags.SIGNS)) continue;
            owner.held(into, stack);
            InvUtils.move().fromId(i).toId(inventoryToMenu(into, from.contents.length));
            reserved.add(into);
            return true;
        }
        return false;
    }

    private static int inventoryToMenu(int index, int containerSize) {
        return index < 9 ? containerSize + 27 + index : containerSize + index - 9;
    }

    /**
     * A slot with signs in it that isn't kept, carry slots first.
     */
    private int findSignSlot(boolean own) {
        Set<Integer> keep = owner.keep();
        for (int slot : reserved) if (mc.player.getInventory().getItem(slot).is(ItemTags.SIGNS)) return slot;
        for (int slot : owner.carry()) if (mc.player.getInventory().getItem(slot).is(ItemTags.SIGNS)) return slot;
        // The player's own signs, in any other slot, only as a last resort
        if (!own) return -1;
        for (int slot = 0; slot < 36; slot++) {
            if (!keep.contains(slot) && mc.player.getInventory().getItem(slot).is(ItemTags.SIGNS)) return slot;
        }
        return -1;
    }

    // Planning

    /**
     * What is homeless, grouped by the sign it would get, and the chest to put it on: the largest group first, on the
     * nearest empty unsigned chest that has signed ones next to it (a double one for a lot of stacks).
     */
    private Plan plan() {
        Stash stash = owner.stash();
        hadGroups = hadCandidates = false;
        anchored = anchoredBoxes();
        Map<Box, Containers.Face> candidates = candidateBoxes();
        hadCandidates = !candidates.isEmpty();
        if (candidates.isEmpty()) {
            summary = "No empty, unsigned chest within 2 blocks of a signed one to put a new category on.";
            return null;
        }

        Map<String, ItemStack> known = knownItems();
        Map<String, Integer> current = new HashMap<>();
        for (Map.Entry<String, ItemStack> entry : known.entrySet()) current.put(entry.getKey(), signedScore(entry.getValue()));
        Map<String, String> chosen = new HashMap<>();

        List<ItemStack> sources = new ArrayList<>();
        for (int slot : owner.activeCarried()) sources.add(mc.player.getInventory().getItem(slot));
        for (Box b : stash.boxes) {
            if (!b.known() || b.unreachable || b.excluded || b.foreign) continue;
            if (!(b.dropOff || b.labels.isEmpty() || owner.isMisc(b))) continue;
            for (ItemStack stack : b.contents) if (!stack.isEmpty()) sources.add(stack);
        }

        Map<String, Group> groups = new HashMap<>();
        for (ItemStack stack : sources) {
            if (stack.is(ItemTags.SIGNS)) continue;
            ItemStack subject = labelSubject(stack);
            if (owner.mixedShulker(stack) && owner.keepWholeExists()) continue;

            List<Box> dests = owner.destinationsFor(stack);
            boolean homeless = dests.isEmpty() || dests.stream().allMatch(owner::isMisc);
            String label;
            boolean same = false;
            if (homeless) {
                String id = id(subject);
                if (!chosen.containsKey(id)) chosen.put(id, chooseLabel(subject, known, current));
                label = chosen.get(id);
            }
            else {
                // A container for it exists, but every one is full: another chest with the same sign
                if (dests.stream().anyMatch(b -> b.free() > 0 || owner.misplacedCount(b) > 0)) continue;
                label = signTextOf(dests);
                same = true;
            }
            if (label == null) continue;

            Group group = groups.computeIfAbsent(label, t -> new Group(subject));
            group.stacks++;
            group.sameLabel |= same;
        }

        StringBuilder text = new StringBuilder();
        for (var entry : groups.entrySet()) text.append(entry.getKey()).append(" x").append(entry.getValue().stacks).append(", ");
        summary = groups.isEmpty() ? "Nothing is homeless." : "Homeless: " + text.substring(0, text.length() - 2) + ". "
            + candidates.size() + " chest(s) to label.";
        hadGroups = !groups.isEmpty();
        if (groups.isEmpty()) return null;

        String best = null;
        for (var entry : groups.entrySet()) {
            if (best == null || entry.getValue().stacks > groups.get(best).stacks
                || (entry.getValue().stacks == groups.get(best).stacks && entry.getKey().compareTo(best) < 0)) {
                best = entry.getKey();
            }
        }
        Group group = groups.get(best);

        List<Box> options = new ArrayList<>(candidates.keySet());
        if (group.stacks > 27) {
            List<Box> doubles = options.stream().filter(b -> b.parts.size() > 1).toList();
            if (!doubles.isEmpty()) options = new ArrayList<>(doubles);
        }
        Box chest = owner.nearest(options);
        return chest == null ? null : new Plan(best, chest, candidates.get(chest), com.porter.qwerte.stash.StackInfo.label(group.sample));
    }

    private static String id(ItemStack stack) {
        return StackInfo.of(stack).id;
    }

    /**
     * What a stack is labelled by: what a shulker is mostly full of, else the stack itself.
     */
    private ItemStack labelSubject(ItemStack stack) {
        if (Utils.isShulker(stack.getItem())) {
            ItemStack inside = Categories.dominantContent(stack);
            if (inside != null && !inside.isEmpty()) return inside;
        }
        return stack;
    }

    /**
     * Empty chests with no sign at all (a blank one counts) that a new category can go on: close to a signed one, not
     * reserved by anything else, with a free side to put the sign on.
     */
    private Map<Box, Containers.Face> candidateBoxes() {
        Stash stash = owner.stash();
        Map<Box, Containers.Face> out = new LinkedHashMap<>();
        for (Box b : stash.boxes) {
            if (!b.sortable() || b.dropOff || b.labelFailed || !b.labels.isEmpty() || b.autoLabel != null) continue;
            if (owner.signPending(b) || owner.rereadPending(b)) continue;

            boolean empty = true;
            for (ItemStack stack : b.contents) if (!stack.isEmpty()) empty = false;
            if (!empty || !nearSigned(b)) continue;

            Containers.Signs signs = Containers.read(stash, b);
            if (signs == null || signs.anyBlock()) continue;
            List<Containers.Face> faces = Containers.signFaces(stash, b);
            if (faces == null || faces.isEmpty()) continue;
            out.put(b, faces.getFirst());
        }
        return out;
    }

    /**
     * A chest only gets a sign when it is next to the stash the player built: within 2 blocks of a container the player
     * signed, or of one the sorter signed that is itself linked, a step of 2 blocks at a time, to one the player signed.
     * A stash grows from the player's own signs, never from something the sorter started on its own.
     */
    private boolean nearSigned(Box b) {
        for (Box other : anchored) {
            if (other != b && chebyshev(b, other) <= 2) return true;
        }
        return false;
    }

    private Set<Box> anchoredBoxes() {
        Stash stash = owner.stash();
        Set<Box> found = new HashSet<>();
        List<Box> queue = new ArrayList<>();
        for (Box b : stash.boxes) {
            if (!b.labels.isEmpty() && b.autoLabel == null) {
                found.add(b);
                queue.add(b);
            }
        }
        for (int i = 0; i < queue.size(); i++) {
            Box from = queue.get(i);
            for (Box b : stash.boxes) {
                if (b.autoLabel != null && !b.labels.isEmpty() && !found.contains(b) && chebyshev(from, b) <= 2) {
                    found.add(b);
                    queue.add(b);
                }
            }
        }
        return found;
    }

    private static int chebyshev(Box a, Box b) {
        int best = Integer.MAX_VALUE;
        for (BlockPos p : a.parts) {
            for (BlockPos q : b.parts) {
                best = Math.min(best, Math.max(Math.abs(p.getX() - q.getX()), Math.max(Math.abs(p.getY() - q.getY()), Math.abs(p.getZ() - q.getZ()))));
            }
        }
        return best;
    }

    /**
     * Every kind of stack in the stash and being carried, one of each.
     */
    private Map<String, ItemStack> knownItems() {
        Map<String, ItemStack> known = new HashMap<>();
        for (Box b : owner.stash().boxes) {
            if (!b.known()) continue;
            for (ItemStack stack : b.contents) {
                if (stack.isEmpty()) continue;
                known.putIfAbsent(id(stack), stack);
                if (Utils.isShulker(stack.getItem())) {
                    ItemStack inside = Categories.dominantContent(stack);
                    if (inside != null && !inside.isEmpty()) known.putIfAbsent(id(inside), inside);
                }
            }
        }
        for (int slot : owner.carriedSlots()) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.isEmpty()) known.putIfAbsent(id(stack), stack);
        }
        return known;
    }

    /**
     * The best any container with a sign scores an item, 0 if none does. Catch-all containers score 1.
     */
    private int signedScore(ItemStack stack) {
        Categories.Described described = Categories.describe(stack);
        int best = 0;
        for (Box b : owner.stash().boxes) {
            if (!b.sortable() || b.dropOff) continue;
            for (String label : b.labels) best = Math.max(best, Categories.signScore(label, described));
        }
        return best;
    }

    /**
     * The first line of a new sign for this item, or null if nothing it could say is safe. In turn the category names it
     * fits, then its own name. A name must come back from reading the sign as exactly this item, a category as that
     * category, and it must not pull an item that already has a container (a real match, 40 or more) to another: those
     * must not score higher under the new sign than under their own. Short enough to fit a line of a sign.
     */
    private String chooseLabel(ItemStack subject, Map<String, ItemStack> known, Map<String, Integer> current) {
        StackInfo info = StackInfo.of(subject);
        Categories.Described described = Categories.describe(info);

        for (String candidate : Categories.labelCandidates(info)) {
            if (candidate.length() > SIGN_LINE_LENGTH) continue;
            boolean byName = candidate.equals(info.name);
            List<String> labels = Containers.labelsOf(List.of(candidate, signDate()));

            int got = 0;
            for (String label : labels) got = Math.max(got, Categories.signScore(label, described));
            if (got < (byName ? 100 : 40)) continue;

            boolean steals = false;
            for (Map.Entry<String, ItemStack> other : known.entrySet()) {
                int home = current.getOrDefault(other.getKey(), 0);
                // Only a real home is protected: a weak match or the catch-all would just as well be somewhere else
                if (home < 40 || other.getKey().equals(info.id)) continue;
                Categories.Described d = Categories.describe(other.getValue());
                int now = 0;
                for (String label : labels) now = Math.max(now, Categories.signScore(label, d));
                if (now > home) {
                    steals = true;
                    break;
                }
            }
            if (!steals) return candidate;
        }
        return null;
    }

    /**
     * The words the signs of these full containers have in common to copy: a line that is a word we know, not a date.
     */
    private String signTextOf(List<Box> boxes) {
        for (Box b : boxes) {
            for (String label : b.labels) {
                if (label.length() <= SIGN_LINE_LENGTH && !label.matches(".*\\d+[-/.]\\d+[-/.]\\d+.*") && Categories.recognises(label)) return label;
            }
        }
        return null;
    }

    private static String signDate() {
        return LocalDate.now().format(SIGN_DATE);
    }

    // Doing it

    private boolean start(Plan plan) {
        box = plan.box();
        text = plan.text();
        lines = new String[] { plan.text(), signDate(), "", "" };
        face = plan.face();
        step = Step.Walk;
        resume = null;
        stepTicks = 0;
        clicks = 0;
        clicked = clickIssued = false;
        reopened = reopenRequested = false;
        reopenAfterHold = false;
        editorReady = false;
        editorAt = null;
        BlockPos at = plan.box().pos;
        owner.step(String.format("labelling: putting up \"%s\" on the chest at %d %d %d; next: %s.", plan.text(), at.getX(), at.getY(), at.getZ(),
            plan.item().isEmpty() ? "sort what belongs there into it" : "sort " + plan.item() + " into it"));
        owner.beginLabel(plan.box());
        return true;
    }

    void tick() {
        age++;
        stepTicks++;
        if (step == null) return;

        switch (step) {
            case Walk -> walk();
            case Hold -> hold();
            case Aim -> aim();
            case Click -> click();
            case Editor -> editor();
            case Write -> write();
            case Verify -> verify();
            case Reopen -> reopen();
        }
    }

    /**
     * To the block the sign will be put up from: from there the side of the chest is within reach and in view.
     */
    private void walk() {
        BlockPos stand = face.stand();
        if (mc.player.blockPosition().equals(stand)) {
            owner.releaseGoal();
            step = resume != null ? resume : Step.Hold;
            resume = null;
            stepTicks = 0;
            return;
        }
        if (owner.leftArea()) {
            fail(String.format("Skipping the %s at [%d, %d, %d]: the way there leaves the stash area.", owner.blockName(box), box.pos.getX(), box.pos.getY(), box.pos.getZ()));
            return;
        }
        if (!owner.walk()) {
            fail("Walking is off and the bot isn't where the sign at " + face.sign().toShortString() + " can be put up from.");
            return;
        }
        if (stepTicks > WALK_TIMEOUT) {
            fail("Couldn't get to where the sign at " + face.sign().toShortString() + " can be put up from (" + stand.toShortString() + ").");
            return;
        }
        if (stepTicks == 1 || (stepTicks % 40 == 0 && !owner.moving())) owner.walkTo(stand);
    }

    /**
     * A sign in a hotbar slot that isn't a kept one, and in the hand: where it already is, else swapped in (and back
     * afterwards).
     */
    private void hold() {
        // Put back whatever an earlier hold left, before taking the hand again
        restoreHand();
        if (restorePending) return;

        int src = findSignSlot(true);
        if (src == -1) {
            fail("The signs ran out.");
            return;
        }

        Set<Integer> keep = owner.keep();
        int hotbar = -1;
        if (src < 9) hotbar = src;
        else {
            // An empty slot first: nothing of the player's has to move
            for (int i = 0; i < 9 && hotbar == -1; i++) if (!keep.contains(i) && mc.player.getInventory().getItem(i).isEmpty()) hotbar = i;
            for (int i = 0; i < 9 && hotbar == -1; i++) if (!keep.contains(i)) hotbar = i;
        }
        if (hotbar == -1) {
            fail("No hotbar slot I'm allowed to hold a sign in.");
            return;
        }

        if (hotbar != src) {
            // What was in the hotbar slot ends up where the sign was: it's the player's, never cargo
            if (!mc.player.getInventory().getItem(hotbar).isEmpty()) displacedSlot = src;
            InvUtils.move().from(src).to(hotbar);
            heldFrom = src;
            heldTo = hotbar;
        }
        heldSlot = hotbar;
        if (!swapped) {
            InvUtils.swap(hotbar, true);
            swapped = true;
        }
        else InvUtils.swap(hotbar, false);

        step = reopenAfterHold ? Step.Reopen : Step.Aim;
        if (reopenAfterHold) {
            reopened = true;
            reopenRequested = false;
        }
        reopenAfterHold = false;
        stepTicks = 0;
        owner.pause(owner.delay());
    }

    /**
     * A sign is in the main hand, in the slot the hold chose.
     */
    private boolean handReady() {
        return heldSlot != -1 && Players.selectedSlot() == heldSlot && mc.player.getMainHandItem().is(ItemTags.SIGNS);
    }

    /**
     * Puts the hotbar and the hand back as they were, once no screen is open and nothing is on the cursor: moving items
     * under a player who is using their inventory would be taking their items out of their hands.
     */
    void restoreHand() {
        if (!restorePending) return;
        if (mc.player == null || mc.screen != null || mc.player.containerMenu != mc.player.inventoryMenu
            || !mc.player.containerMenu.getCarried().isEmpty()) return;

        restorePending = false;
        if (swapped) {
            InvUtils.swapBack();
            swapped = false;
        }
        // Only if the slots are still as the hold left them: the signs still in the hotbar slot go back and the player's
        // item comes out of the carry slot, or, with the signs all used up, the player's item goes back by itself
        if (heldFrom != -1 && heldTo != -1) {
            if (mc.player.getInventory().getItem(heldTo).is(ItemTags.SIGNS)) {
                InvUtils.move().from(heldTo).to(heldFrom);
            }
            else if (mc.player.getInventory().getItem(heldTo).isEmpty() && displacedSlot == heldFrom
                && !mc.player.getInventory().getItem(heldFrom).isEmpty()) {
                InvUtils.move().from(heldFrom).to(heldTo);
            }
        }
        heldFrom = heldTo = heldSlot = displacedSlot = -1;
    }

    /**
     * Sneaking for a couple of ticks first: the server needs to have been told we sneak before the click, or the click
     * opens the chest.
     */
    private void aim() {
        sneak(true);
        if (stepTicks >= 3) {
            step = Step.Click;
            stepTicks = 0;
        }
    }

    /**
     * The right click on the chest's side, sneaking, with the sign in the hand: the sign goes against that side. Straight
     * on the chest rather than through the block placer, which would pick a side that isn't the chest's.
     */
    private void click() {
        if (clickIssued) {
            if (stepTicks > 40) fail("The click on the chest at " + box.pos.toShortString() + " never went out.");
            return;
        }

        // Never put a sign where one is: it would go one block further out, or on the sign
        if (!mc.level.getBlockState(face.sign()).isAir()) {
            if (clicked) {
                step = Step.Editor;
                stepTicks = 0;
            }
            else fail("Something is already at " + face.sign().toShortString() + ", where the sign was to go.");
            return;
        }
        if (clicks >= MAX_CLICKS) {
            fail("Couldn't put a sign on the chest at " + box.pos.toShortString() + ".");
            return;
        }

        // Only with a sign in the hand: anything else would open the chest or do something else to it
        if (!handReady()) {
            step = Step.Hold;
            stepTicks = 0;
            return;
        }

        Direction dir = face.dir();
        Vec3 where = Vec3.atCenterOf(face.part()).add(dir.getStepX() * 0.5, 0, dir.getStepZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(where, dir, face.part(), false);
        BlockPos spot = face.sign();

        clickIssued = true;
        Runnable action = () -> {
            // The rotation can finish after we have started to give way, or after the sign went up some other way
            if (step != Step.Click || !owner.running() || !mc.level.getBlockState(spot).isAir() || !handReady()) {
                clickIssued = false;
                return;
            }
            preexisting.clear();
            for (BlockPos p : Containers.signSpots(box)) if (signThere(p)) preexisting.add(p);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            mc.player.swing(InteractionHand.MAIN_HAND);
            clickAge = age;
            clicks++;
            clicked = true;
            ours.add(spot);
            editorReady = false;
            step = Step.Editor;
            stepTicks = 0;
        };
        if (owner.rotate()) Rotations.rotate(Rotations.getYaw(where), Rotations.getPitch(where), action);
        else action.run();
    }

    /**
     * Has the server opened the editor for the sign it put up? The screen itself is taken over in {@link #onScreen}.
     */
    private void editor() {
        if (owner.containerOpen()) {
            // Sneaking didn't take and it opened the chest: close it and go again, if no sign is up
            owner.closeOurWindow();
            clickAgain("the chest opened instead of taking a sign");
            return;
        }

        if (editorReady) {
            step = Step.Write;
            stepTicks = 0;
            return;
        }

        if (stepTicks > WAIT_TICKS) {
            if (!signThere(face.sign())) clickAgain("no sign appeared");
            else if (!reopened) startReopen();
            else fail("The sign at " + face.sign().toShortString() + " was put up but its editor never opened.");
        }
    }

    private void clickAgain(String why) {
        // Only when nothing is up: a second click with a sign there puts one further out
        if (signThere(face.sign()) || clicks >= MAX_CLICKS) {
            if (signThere(face.sign()) && !reopened) startReopen();
            else fail("Couldn't put a sign on the chest at " + box.pos.toShortString() + ": " + why + ".");
            return;
        }
        clickIssued = false;
        step = Step.Aim;
        stepTicks = 0;
    }

    /**
     * Whether any sign is at this place.
     */
    private boolean signThere(BlockPos spot) {
        return mc.level.getBlockEntity(spot) instanceof SignBlockEntity;
    }

    /**
     * The editor that opened is for the sign we put up: at the place we expected, or at another that is a sign on the
     * target container (it is then the sign that is written). One anywhere else is none of ours and is left alone.
     * Cancels the screen when it takes it, the text is sent from here.
     */
    void onScreen(OpenScreenEvent event) {
        if (box == null || (step != Step.Editor && step != Step.Reopen) || editorReady || !owner.running()) return;
        if (!(event.screen instanceof AbstractSignEditScreen screen)) return;

        SignBlockEntity sign = SignEditors.signOf(screen);
        BlockPos at = sign.getBlockPos();
        editorAt = at;
        // The sign we meant, one we put up, or a new sign on the target (the click can land a block off)
        boolean mine = at.equals(face.sign()) || ours.contains(at)
            || (clicked && Containers.signSpots(box).contains(at) && !preexisting.contains(at));
        if (!mine || !signThere(at)) {
            owner.debug(String.format("A sign editor opened at %s, not on the chest at %s: left alone.", at.toShortString(), box.pos.toShortString()));
            return;
        }

        if (!at.equals(face.sign())) {
            owner.debug(String.format("The sign editor opened at %s, not %s: writing there, it is on the chest at %s.", at.toShortString(), face.sign().toShortString(), box.pos.toShortString()));
            face = new Containers.Face(at, face.dir(), face.part(), face.stand());
        }
        ours.add(at);
        editorFront = sign.isFacingFrontText(mc.player);
        editorReady = true;
        event.cancel();
    }

    /**
     * A sign that's there but whose editor didn't open: once, clicked again without sneaking, which opens it for its
     * author.
     */
    private void startReopen() {
        reopened = true;
        reopenRequested = false;
        step = Step.Reopen;
        stepTicks = 0;
    }

    private void reopen() {
        BlockPos spot = face.sign();
        if (editorReady) {
            step = Step.Write;
            stepTicks = 0;
            return;
        }

        if (!reopenRequested) {
            // Never over what someone else is doing: wait for them, and give up if it goes on
            if (!owner.running() || owner.foreign()) {
                if (stepTicks > WAIT_TICKS) fail("The sign at " + spot.toShortString() + " was put up but another module kept the bot busy, so it couldn't be opened.");
                return;
            }

            // With the sign in the hand again first (the hand may have been given back while giving way)
            if (!handReady()) {
                reopenAfterHold = true;
                step = Step.Hold;
                stepTicks = 0;
                return;
            }

            // A plain right click on the sign itself, not sneaking: a sign in the hand is not put against it then, and
            // the author's click opens the editor
            sneak(false);
            Direction dir = face.dir();
            Vec3 where = Vec3.atCenterOf(spot).subtract(dir.getStepX() * 0.375, 0, dir.getStepZ() * 0.375);
            BlockHitResult hit = new BlockHitResult(where, dir, spot, false);
            Runnable action = () -> {
                if (step != Step.Reopen || !owner.running() || !handReady()) return;
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                mc.player.swing(InteractionHand.MAIN_HAND);
                clickAge = age;
                reopenRequested = true;
                stepTicks = 0;
            };
            // Asked for once: the callback sets the flag, so a late rotation cannot make a second click
            reopenRequested = true;
            if (owner.rotate()) Rotations.rotate(Rotations.getYaw(where), Rotations.getPitch(where), action);
            else action.run();
            stepTicks = 0;
        }
        else if (stepTicks > WAIT_TICKS) fail("The sign at " + spot.toShortString() + " was put up but its editor never opened.");
    }

    /**
     * The text, in the one packet the editor sends when it's closed.
     */
    private void write() {
        BlockPos spot = face.sign();
        owner.releaseGoal();
        sneak(false);
        mc.player.connection.send(new ServerboundSignUpdatePacket(spot, editorFront, lines[0], lines[1], lines[2], lines[3]));
        owner.debug(String.format("Writing \"%s\" on the sign at %s (%s side).", text, spot.toShortString(), editorFront ? "front" : "back"));
        step = Step.Verify;
        stepTicks = 0;
    }

    /**
     * Reads the sign back, and only then is the chest a category.
     */
    private void verify() {
        Containers.Signs read = Containers.read(owner.stash(), box);
        if (read != null && read.labels().contains(text)) {
            Box done = box;
            done.labels.clear();
            done.labels.addAll(read.labels());
            done.autoLabel = text;
            done.autoSignPos = face.sign();
            ours.remove(face.sign());
            placed++;
            lastLabel = owner.clock();
            owner.labelled(done);
            owner.say(String.format("Labelled the chest at %s as \"%s\".", done.pos.toShortString(), text));
            end();
        }
        else if (stepTicks > WAIT_TICKS) {
            if (!reopened) startReopen();
            else fail("The sign at " + face.sign().toShortString() + " never showed what was written on it.");
        }
    }

    /**
     * Gives up on this chest for the run. A sign left blank is said so: the sorter never breaks one.
     */
    private void fail(String why) {
        BlockPos spot = face.sign();
        // Only a sign this run put up is written on again, and each at most twice
        boolean blankOurs = ours.contains(spot) && signThere(spot);
        if (blankOurs && text != null && blankTries.merge(spot, 1, Integer::sum) <= 2) blank.put(spot, new Plan(text, box, face));
        box.labelFailed = true;
        if (editorAt != null && !editorAt.equals(spot)) why += " (an editor opened at " + editorAt.toShortString() + ")";
        owner.warn(why + (blankOurs ? " A blank sign is left at " + spot.toShortString() + ", write on it or remove it." : ""));
        failures++;
        lastLabel = owner.clock();
        end();
    }

    /**
     * The job is over: the hand and the hotbar are put back as they were, and nothing of ours is left going.
     */
    private void end() {
        sneak(false);
        // Whatever request was still going (the walk) is let go of, so it is not taken for someone else's
        owner.releaseGoal();
        restorePending = swapped || heldFrom != -1;
        restoreHand();

        step = null;
        box = null;
        face = null;
        lines = null;
        clickIssued = false;
        owner.endLabel();
    }

    /**
     * Giving way: nothing is left sneaking. Before the click that leaves nothing behind but the hand, and the job is
     * planned again. After it, a sign is up, so it goes back to it when resumed.
     */
    void yielded() {
        sneak(false);
        if (step != null && !clicked) {
            end();
            return;
        }
        // A sign is up: the hand goes back to the player (when it's safe to), and is taken again on resuming
        restorePending = swapped || heldFrom != -1;
        restoreHand();
    }

    /**
     * The module was switched off: nothing is left sneaking, and the hand and the hotbar are put back.
     */
    void deactivate() {
        sneak(false);
        restorePending = swapped || heldFrom != -1;
        restoreHand();
        step = null;
        box = null;
        face = null;
        clickIssued = false;
    }

    void resume() {
        if (step == null) {
            owner.endLabel();
            return;
        }
        // Through the hold again, then back to opening the sign
        resume = Step.Hold;
        reopenAfterHold = true;
        step = Step.Walk;
        stepTicks = 0;
        reopenRequested = false;
        editorReady = false;
    }

    /**
     * What is homeless, what is labelled, what failed and what is waiting.
     */
    List<String> report() {
        List<String> out = new ArrayList<>();
        out.add(summary);
        Stash stash = owner.stash();
        if (stash == null) {
            out.add("No stash yet.");
            return out;
        }

        int auto = 0;
        StringBuilder failed = new StringBuilder();
        for (Box b : stash.boxes) {
            if (b.autoLabel != null) auto++;
            if (b.labelFailed) failed.append(failed.isEmpty() ? "" : ", ").append(b.pos.getX()).append(' ').append(b.pos.getY()).append(' ').append(b.pos.getZ());
        }
        out.add(String.format("Labelled by the sorter: %d, this run: %d/%d.", auto, placed, MAX_LABELS_PER_RUN));
        if (!failed.isEmpty()) out.add("Gave up on: " + failed);
        out.add(owner.autoLabel() ? "Auto labelling is on." : "Auto labelling is off.");
        return out;
    }
}
