package com.porter.qwerte.tweaks;

/**
 * The steps of Smart Shulker and Smart Echest as a state machine, kept apart from the game so every path is tested.
 * Each tick the module describes what it sees ({@link Obs}) and gets back at most one thing to do ({@link Step}); that is
 * what keeps it to one world action per tick. The flow never touches the game itself.
 *
 * <p>Order: close the screen, place, open, put the hotbar back, wait for the screen to close, mine, collect. A place that
 * is left in the world (cancelled, world change, no way to mine it) ends with {@link Act#ORPHAN} so the module can
 * remember it and tell the player.
 */
public final class SmartPlaceFlow {
    public enum Kind { SHULKER, ECHEST }

    /** What to do with an ender chest when there is no silk touch pickaxe. */
    public enum NoSilk { REFUSE, PLACE_AND_LEAVE, MINE_ANYWAY }

    /** How the block is taken back: normally, with a silk touch pickaxe, not at all, or without silk (the chest is lost). */
    public enum Mining { NORMAL, SILK, LEAVE, LOSSY }

    /** How the item gets to the hotbar before it is placed. */
    public enum Move { NONE, EMPTY, SWAP, REFUSE }

    public enum Act {
        NONE, CLOSE_SCREEN, PLACE, OPEN, RESTORE, MINE, WALK_REACH, WALK_DROP, STOP_WALK,
        /** Done, nothing left behind. */
        FINISH,
        /** Done, but the item is out in the world: the module records where. */
        ORPHAN,
        /** Stopped with nothing out in the world. */
        ABORT
    }

    public enum Level { INFO, WARN, ERROR }

    public record Step(Act act, Level level, String msg) {
        static final Step IDLE = new Step(Act.NONE, Level.INFO, null);

        public boolean ends() {
            return act == Act.FINISH || act == Act.ORPHAN || act == Act.ABORT;
        }
    }

    /** What the module knows before it moves anything. */
    public record Pre(Kind kind, NoSilk noSilk, boolean spotFound, String spotWhy, boolean hasSilk, boolean hasPickaxe,
                      boolean inHotbar, boolean fromInventory, int emptyHotbar, int displaceableHotbar) {}

    /** What to do, or why not. {@code hotbar} is the slot to use (0 to 8) when {@code move} is not NONE. */
    public record Plan(boolean ok, Move move, int hotbar, Mining mining, String error, String warning) {}

    public record Config(Mining mining, int actionDelay, int pickupTimeout, boolean walk, String what, String where) {}

    public record Obs(boolean worldChanged, boolean cancelled, boolean menuOpen, boolean ownedOpen, boolean expectedOpen,
                      boolean cursorEmpty, boolean blockOurs, boolean blockClear, int itemCount, boolean freeSlot,
                      boolean inReach, boolean hasTool, boolean dropNearby, boolean dropInRange, boolean canWalk) {}

    public static final int PLACE_TIMEOUT = 60, OPEN_TIMEOUT = 80, RETRY = 20, CLEAR_TICKS = 10, BREAK_TIMEOUT = 300,
        FULL_WARN = 200, REACH_WARN = 100, FULL_LIMIT = 20 * 60 * 5, DROP_WAIT = 15, NO_DROP = 40, REPATH = 60, MAX_REMINES = 3;

    private enum Stage { CLOSE, PLACE, OPEN, RESTORE, WAIT_CLOSE, MINE, COLLECT, DONE }

    private final Config cfg;
    private Stage stage = Stage.CLOSE;
    private int ticks, stageStart, cooldown, lastAct = -1000, baseline;
    private boolean placed, restored, walkingReach, walkingDrop;
    private int clearTicks, fullTicks, lastWalk, reachWait, collectStart, remines, mineTicks;
    private boolean placeTimedOut;

    public SmartPlaceFlow(Config cfg) {
        this.cfg = cfg;
    }

    /**
     * Decides, before anything moves, whether the click can go ahead and how: the spot, the silk touch pickaxe for an ender
     * chest, and a hotbar slot.
     */
    public static Plan plan(Pre p) {
        String what = p.kind() == Kind.ECHEST ? "ender chest" : "shulker box";
        if (!p.spotFound()) return fail("No place for the " + what + ": " + p.spotWhy() + ".");

        Mining mining = Mining.NORMAL;
        String warning = null;
        if (p.kind() == Kind.ECHEST) {
            if (p.hasSilk()) mining = Mining.SILK;
            else switch (p.noSilk()) {
                case REFUSE -> {
                    return fail("No silk touch pickaxe in your inventory, so the ender chest could not be picked up again.");
                }
                case PLACE_AND_LEAVE -> {
                    mining = Mining.LEAVE;
                    warning = "No silk touch pickaxe: the ender chest will be left where it is placed.";
                }
                case MINE_ANYWAY -> {
                    if (p.hasPickaxe()) {
                        mining = Mining.LOSSY;
                        warning = "No silk touch pickaxe: mining it drops 8 obsidian and the ender chest is lost.";
                    }
                    else {
                        mining = Mining.LEAVE;
                        warning = "No pickaxe to mine it with either: the ender chest will be left where it is placed.";
                    }
                }
            }
        }

        if (p.inHotbar()) return new Plan(true, Move.NONE, -1, mining, null, warning);
        if (p.emptyHotbar() >= 0) return new Plan(true, Move.EMPTY, p.emptyHotbar(), mining, null, warning);
        if (p.fromInventory() && p.displaceableHotbar() >= 0) return new Plan(true, Move.SWAP, p.displaceableHotbar(), mining, null, warning);
        return fail(p.fromInventory() ? "Your hotbar holds nothing that can be moved out of the way."
            : "Your hotbar is full, and nothing of yours is moved into someone else's container.");
    }

    private static Plan fail(String error) {
        return new Plan(false, Move.REFUSE, -1, Mining.NORMAL, error, null);
    }

    public boolean done() {
        return stage == Stage.DONE;
    }

    /** True once the block has been put in the world and not yet taken back or given up on. */
    public boolean placed() {
        return placed;
    }

    /** The place step gave up without seeing the block; the module keeps watching in case it turns up late. */
    public boolean placeTimedOut() {
        return placeTimedOut;
    }

    /**
     * Whether the menu now open is the one our open click made: only after that click, never the menu that was open when
     * the flow started (the screen the right-click came from), and only the first one.
     */
    public static boolean ownsMenu(boolean alreadyOwned, boolean openClicked, boolean expectedType, boolean isStartMenu) {
        return !alreadyOwned && openClicked && expectedType && !isStartMenu;
    }

    public String stageName() {
        return stage.name();
    }

    /** The reading of the world to compare the item count against: the count right after placing. */
    public int baseline() {
        return baseline;
    }

    public Step tick(Obs o) {
        if (stage == Stage.DONE) return Step.IDLE;
        ticks++;
        if (cooldown > 0) cooldown--;

        if (o.worldChanged()) return leave(o, "The world changed");
        if (o.cancelled()) return leave(o, "Cancelled");

        return switch (stage) {
            case CLOSE -> close(o);
            case PLACE -> place(o);
            case OPEN -> open(o);
            case RESTORE -> restore(o);
            case WAIT_CLOSE -> waitClose(o);
            case MINE -> mine(o);
            case COLLECT -> collect(o);
            case DONE -> Step.IDLE;
        };
    }

    // Stages

    private Step close(Obs o) {
        if (!o.menuOpen()) {
            enter(Stage.PLACE);
            return Step.IDLE;
        }
        return act(Act.CLOSE_SCREEN);
    }

    private Step place(Obs o) {
        if (o.blockOurs()) {
            placed = true;
            baseline = o.itemCount();
            enter(Stage.OPEN);
            return Step.IDLE;
        }
        if (ticks - stageStart > PLACE_TIMEOUT) {
            placeTimedOut = true;
            return end(Act.ABORT, Level.ERROR, "Could not place the " + cfg.what() + " there: no block appeared.");
        }
        if (cooldown == 0 && ticks - lastAct >= RETRY) return act(Act.PLACE);
        return Step.IDLE;
    }

    private Step open(Obs o) {
        if (!o.blockOurs()) return vanished(o);
        if (o.expectedOpen()) {
            enter(Stage.RESTORE);
            return Step.IDLE;
        }
        if (ticks - stageStart > OPEN_TIMEOUT) {
            Step next = afterClosed(o);
            if (next.ends()) return next;
            return new Step(next.act(), Level.WARN, "Could not open the " + cfg.what() + ", mining it instead.");
        }
        if (cooldown == 0 && ticks - lastAct >= RETRY) return act(Act.OPEN);
        return Step.IDLE;
    }

    private Step restore(Obs o) {
        if (!o.blockOurs()) return vanished(o);
        // Closed before the slots were put back: do it now, from the inventory screen
        if (!o.ownedOpen()) {
            restored = true;
            enter(Stage.WAIT_CLOSE);
            return act(Act.RESTORE);
        }
        if (o.cursorEmpty() && cooldown == 0) {
            restored = true;
            enter(Stage.WAIT_CLOSE);
            return act(Act.RESTORE);
        }
        return Step.IDLE;
    }

    private Step waitClose(Obs o) {
        if (!o.blockOurs()) return vanished(o);
        if (o.ownedOpen()) return Step.IDLE;
        if (!restored) {
            restored = true;
            return act(Act.RESTORE);
        }
        return afterClosed(o);
    }

    /** The screen is gone: leave it, or go and take it back. */
    private Step afterClosed(Obs o) {
        if (cfg.mining() == Mining.LEAVE) {
            return end(Act.FINISH, Level.INFO, "The " + cfg.what() + " is placed at " + cfg.where() + " and left there.");
        }
        enter(Stage.MINE);
        return Step.IDLE;
    }

    private Step mine(Obs o) {
        if (!o.blockOurs()) {
            if (!o.blockClear()) return vanished(o);
            if (++clearTicks == 1) baseline = o.itemCount();
            if (clearTicks >= CLEAR_TICKS) return afterBreak();
            return Step.IDLE;
        }
        clearTicks = 0;

        // Nowhere to put it: wait, and say so now and then
        if (!o.freeSlot()) {
            fullTicks++;
            if (fullTicks > FULL_LIMIT) {
                return end(Act.ORPHAN, Level.WARN, "Your inventory stayed full, so the " + cfg.what() + " is left at " + cfg.where() + ".");
            }
            if (fullTicks == 1 || fullTicks % FULL_WARN == 0) {
                return new Step(Act.NONE, Level.WARN, "Your inventory is full. Free a slot to pick the " + cfg.what() + " up again.");
            }
            return Step.IDLE;
        }
        fullTicks = 0;

        if (!o.inReach()) {
            if (!cfg.walk()) {
                return end(Act.ORPHAN, Level.WARN, "The " + cfg.what() + " at " + cfg.where() + " is out of reach, and Baritone is not there to walk back.");
            }
            reachWait++;
            if (reachWait > cfg.pickupTimeout()) {
                return end(Act.ORPHAN, Level.WARN, "Could not get back within reach of the " + cfg.what() + " at " + cfg.where() + ".");
            }
            if (o.canWalk() && (!walkingReach || ticks - lastWalk >= REPATH)) {
                walkingReach = true;
                lastWalk = ticks;
                return act(Act.WALK_REACH);
            }
            if (reachWait == REACH_WARN) {
                return new Step(Act.NONE, Level.WARN, "The " + cfg.what() + " at " + cfg.where() + " is out of reach and I cannot walk to it right now. Still waiting.");
            }
            return Step.IDLE;
        }
        reachWait = 0;
        if (walkingReach) {
            walkingReach = false;
            return act(Act.STOP_WALK);
        }

        if (!o.hasTool()) {
            return end(Act.ORPHAN, Level.ERROR, "The pickaxe to mine the " + cfg.what() + " with is gone. It is left at " + cfg.where() + ".");
        }
        if (++mineTicks > BREAK_TIMEOUT) {
            return end(Act.ORPHAN, Level.ERROR, "Could not break the " + cfg.what() + " at " + cfg.where() + ".");
        }
        if (cooldown == 0) return new Step(Act.MINE, Level.INFO, null);
        return Step.IDLE;
    }

    private Step afterBreak() {
        if (cfg.mining() == Mining.LOSSY) {
            return end(Act.FINISH, Level.WARN, "The ender chest is broken. It dropped 8 obsidian and the chest itself is gone.");
        }
        enter(Stage.COLLECT);
        collectStart = ticks;
        return Step.IDLE;
    }

    private Step vanished(Obs o) {
        // Someone else broke it or changed it: just collect whatever fell
        if (stage == Stage.COLLECT) return Step.IDLE;
        enter(Stage.COLLECT);
        collectStart = ticks;
        baseline = o.itemCount();
        return new Step(Act.NONE, Level.WARN, "The " + cfg.what() + " at " + cfg.where() + " was broken or changed by something else. Looking for the drop.");
    }

    private Step collect(Obs o) {
        if (o.itemCount() > baseline) {
            placed = false;
            return end(Act.FINISH, Level.INFO, "Picked the " + cfg.what() + " back up.");
        }

        if (o.blockOurs()) {
            // Put back (or never really gone): take it again, a few times
            if (remines >= MAX_REMINES) {
                return end(Act.ORPHAN, Level.WARN, "The " + cfg.what() + " at " + cfg.where() + " keeps coming back. Left it there.");
            }
            remines++;
            enter(Stage.MINE);
            return new Step(Act.NONE, Level.WARN, "The " + cfg.what() + " is back at " + cfg.where() + ", mining it again (" + remines + " of " + MAX_REMINES + ").");
        }

        int waited = ticks - collectStart;
        if (!o.dropNearby() && waited >= NO_DROP) {
            placed = false;
            return end(Act.ABORT, Level.WARN, "No " + cfg.what() + " dropped at " + cfg.where() + ". Someone may have taken it.");
        }
        if (waited > cfg.pickupTimeout()) {
            return end(Act.ORPHAN, Level.WARN, "Could not pick up the " + cfg.what() + " at " + cfg.where() + ".");
        }
        if (waited >= DROP_WAIT && o.dropNearby() && !o.dropInRange() && cfg.walk() && o.canWalk()) {
            if (!walkingDrop || ticks - lastWalk >= REPATH) {
                walkingDrop = true;
                lastWalk = ticks;
                return act(Act.WALK_DROP);
            }
        }
        return Step.IDLE;
    }

    // Helpers

    private void enter(Stage next) {
        stage = next;
        stageStart = ticks;
        cooldown = Math.max(0, cfg.actionDelay());
        lastAct = -1000;
        mineTicks = 0;
        reachWait = 0;
        clearTicks = 0;
        fullTicks = 0;
        if (next == Stage.MINE) {
            walkingReach = false;
            lastWalk = 0;
        }
    }

    private Step act(Act act) {
        lastAct = ticks;
        cooldown = Math.max(0, cfg.actionDelay());
        return new Step(act, Level.INFO, null);
    }

    private Step end(Act act, Level level, String msg) {
        stage = Stage.DONE;
        return new Step(act, level, msg);
    }

    /** Cancelled, or the world changed: stop at once, remembering the item if it is out there. */
    private Step leave(Obs o, String why) {
        if (stage == Stage.COLLECT && o.itemCount() > baseline) {
            placed = false;
            return end(Act.FINISH, Level.INFO, "Picked the " + cfg.what() + " back up.");
        }
        if (placed || o.blockOurs()) {
            placed = true;
            return end(Act.ORPHAN, Level.WARN, why + ": your " + cfg.what() + " is left at " + cfg.where()
                + ". Dropped items despawn after 5 minutes.");
        }
        return end(Act.ABORT, Level.INFO, why + ".");
    }
}
