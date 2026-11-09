package com.porter.qwerte.printer.loop;

import com.porter.qwerte.printer.materials.MaterialPolicy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the Printer does next, as a state machine kept apart from the game so every path is tested. Each tick the module
 * describes what it sees ({@link Obs}) and gets back one thing to do ({@link Step}); the loop never touches the game.
 *
 * <p>Phases: load the schematic, scan it (the module spreads that over ticks), then build. Building places what is in
 * reach, walks when nothing is, resupplies when the next blocks are not carried, and handles blocks that are wrong. A
 * condition the player has to fix pauses with one message and resumes by itself when it clears. Placed blocks are
 * verified a few ticks later: a block that did not appear is placed again up to {@code retries} times, then given up.
 *
 * <p>Every way out ({@link Act#FINISH}, {@link Act#ABORT}) tells the module to clean up: release sneak, Baritone and the
 * rotation, and give the hotbar selection back.
 */
public final class PrintLoop {
    public enum Phase { IDLE, LOADING, SCANNING, BUILDING, RESUPPLYING, PAUSED, DONE, STOPPED }

    /** What to do with a block that is in the way. */
    public enum Wrong { SKIP_AND_LIST, BREAK_AND_REPLACE, PAUSE }

    public enum Level { INFO, WARN, ERROR }

    public enum Act {
        NONE,
        /** Start loading the schematic (the module keeps the future and reports the result in the next observations). */
        LOAD,
        /** Do a slice of the scan (resolve the palette, then walk the cells). */
        SCAN,
        /** The scan is done: summarise materials and start building. */
        PLAN,
        /** Place what is in reach (one batch). */
        PLACE,
        /** Walk toward the next blocks (or toward chunks that are not loaded yet). */
        WALK,
        /** Release our own Baritone goal (paused, or about to place). */
        STOP_WALK,
        /** Fetch the missing materials. */
        RESUPPLY,
        /** Skip the wrong block, list it. */
        SKIP_WRONG,
        /** Break the wrong block so its cell can be built. */
        BREAK_WRONG,
        /** Break a scaffold block that no open cell needs any more. */
        REMOVE_SCAFFOLD,
        /** Everything that can be built is built. Ends the run. */
        FINISH,
        /** Stopped (cancelled, world change, error). Ends the run. */
        ABORT
    }

    public record Step(Act act, Level level, String msg) {
        public boolean ends() {
            return act == Act.FINISH || act == Act.ABORT;
        }
    }

    /**
     * What the module sees this tick.
     *
     * @param worldChanged     the level is no longer the one the run started in
     * @param cancelled        the user cancelled (key, module off)
     * @param loaded           the schematic is loaded
     * @param loadError        why loading failed, or null
     * @param scanDone         every cell has been seen
     * @param pending          cells still to build (queued or waiting to be tried again)
     * @param wrongPending     blocks found in the way and not yet handled
     * @param wrongInfo        a short description of the first of them (for the pause message)
     * @param breaking         a break is in progress
     * @param wrongMode        what to do with wrong blocks (read live from the settings)
     * @param placeable        a block with its materials in the inventory is ready within reach
     * @param walkTarget       there is a place to walk toward: a ready block with materials out of reach, or chunks not loaded yet
     * @param lacking          blocks are ready but their materials are not carried
     * @param lackingInfo      what is missing (item name), for messages
     * @param supplyPossible   a materials source is set up
     * @param resupplyActive   the resupply flow is running
     * @param resupplyFailed   the resupply flow just ended without getting anything (one observation), with why; null otherwise
     * @param sessionActive    a shulker session is running
     * @param inventoryFull    nothing more can be taken into the inventory
     * @param baritoneBusy     Baritone is busy with something that is not ours
     * @param canWalk          walking is on, Baritone is there and nobody else has confined it
     * @param waiting          something outside the loop is still going on and the run must not end: a search of far blocks that
     *                         was cut short, note clicks still to send, another module's shulker session
     * @param orderWait        what a stash order is still being waited for, as a phrase ("the order from stash X"), or null
     * @param orderProblem     why stash orders can't help right now (not logged in, not allowed, nothing to order from), or null
     * @param layerHold        non-null when nothing is left to build inside Litematica's render layers but blocks remain outside them:
     *                         the message to pause with (the run does not end; it resumes when the layers change)
     * @param scaffoldLeft     scaffold blocks that stand (or may stand: a click is awaiting its check) and must be removed before the run ends
     * @param scaffoldRemovable some of them are not needed any more and can be broken now
     * @param layerMoving      the layer cursor is still going to move on (carry a layer over, try carried ones again): nothing is stuck yet
     * @param disableWhenOut   the setting {@code disable-when-out-of-materials}: when nothing can supply a missing item, turn the module
     *                         off instead of pausing until the item turns up
     */
    public record Obs(boolean worldChanged, boolean cancelled, boolean loaded, String loadError, boolean scanDone, int pending,
                      int wrongPending, String wrongInfo, boolean breaking, Wrong wrongMode, boolean placeable, boolean walkTarget,
                      boolean lacking, String lackingInfo, boolean supplyPossible, boolean resupplyActive, String resupplyFailed,
                      boolean sessionActive, boolean inventoryFull, boolean baritoneBusy, boolean canWalk, boolean waiting, String orderWait, String orderProblem,
                      boolean disableWhenOut, String layerHold, int scaffoldLeft, boolean scaffoldRemovable, boolean layerMoving) {

        /** Without scaffolding or a layer cursor. */
        public Obs(boolean worldChanged, boolean cancelled, boolean loaded, String loadError, boolean scanDone, int pending,
                   int wrongPending, String wrongInfo, boolean breaking, Wrong wrongMode, boolean placeable, boolean walkTarget,
                   boolean lacking, String lackingInfo, boolean supplyPossible, boolean resupplyActive, String resupplyFailed,
                   boolean sessionActive, boolean inventoryFull, boolean baritoneBusy, boolean canWalk, boolean waiting, String orderWait, String orderProblem,
                   boolean disableWhenOut, String layerHold) {
            this(worldChanged, cancelled, loaded, loadError, scanDone, pending, wrongPending, wrongInfo, breaking, wrongMode, placeable, walkTarget,
                lacking, lackingInfo, supplyPossible, resupplyActive, resupplyFailed, sessionActive, inventoryFull, baritoneBusy, canWalk, waiting,
                orderWait, orderProblem, disableWhenOut, layerHold, 0, false, false);
        }

        /** A quiet observation to build tests and the first tick from. */
        public static Obs idle() {
            return new Obs(false, false, false, null, false, 0, 0, null, false, Wrong.SKIP_AND_LIST, false, false, false, null, true,
                false, null, false, false, false, true, false, null, null, false, null);
        }
    }

    /** What a verification found. */
    public enum Verdict { OK, RETRY, FAILED }

    public static final int VERIFY_TICKS = 4;
    public static final int RETRIES = 2;
    /** Ticks with nothing ready, nothing to wait for and nothing to fetch before the run ends. */
    public static final int STUCK_TICKS = 60;
    /** Ticks a failed resupply waits before it is tried again. */
    public static final int RESUPPLY_RETRY = 600;

    private enum Pause { NONE, WRONG, BARITONE, WALK_OFF, FULL, NO_SOURCE, RESUPPLY_FAILED, AWAITING_ORDER, STUCK, LAYER }

    /** The start of the pause message for blocks that can't be built right now; the module lists the cells when it sees it. */
    public static final String STUCK_PREFIX = "Stuck: ";

    /** Ticks a pause or a resume must hold before it is announced (a flapping condition says nothing). */
    public static final int HYSTERESIS = 20;

    private final int verifyTicks, retries, hysteresis;
    private Pause candidate = Pause.NONE;
    private long candidateSince, candidateLast, resumeSince, resumeLast;
    private Phase phase = Phase.IDLE;
    private Pause pause = Pause.NONE;
    /** The pause the player was told about and has not been told is over. */
    private Pause announced = Pause.NONE;
    private long now;
    private int stuck;
    private long retryAt;
    private boolean planned;

    // Placed blocks waiting to be looked at: key to the tick they are due; and how many times each was tried
    private final Map<Long, Long> waiting = new LinkedHashMap<>();
    private final Map<Long, Integer> attempts = new LinkedHashMap<>();

    public PrintLoop() {
        this(VERIFY_TICKS, RETRIES);
    }

    public PrintLoop(int verifyTicks, int retries) {
        this(verifyTicks, retries, 0);
    }

    public PrintLoop(int verifyTicks, int retries, int hysteresis) {
        this.verifyTicks = Math.max(1, verifyTicks);
        this.retries = Math.max(0, retries);
        this.hysteresis = Math.max(0, hysteresis);
    }

    /** Paused for a reason that needs the player or a fix: stuck, no materials source, a failed fetch. (For the error sound.) */
    public boolean errorPause() {
        return phase == Phase.PAUSED && (pause == Pause.STUCK || pause == Pause.NO_SOURCE || pause == Pause.RESUPPLY_FAILED);
    }

    public Phase phase() {
        return phase;
    }

    public boolean paused() {
        return phase == Phase.PAUSED;
    }

    public boolean ended() {
        return phase == Phase.DONE || phase == Phase.STOPPED;
    }

    public long ticks() {
        return now;
    }

    // Verification

    /** A block was placed at this cell (a retry counts again). It is looked at {@code verifyTicks} later. */
    public void placed(long key) {
        waiting.put(key, now + verifyTicks);
        attempts.merge(key, 1, Integer::sum);
    }

    /** The cells due to be looked at now; they leave the waiting list, the module answers each with {@link #verdict}. */
    public List<Long> due() {
        List<Long> out = new ArrayList<>();
        for (Iterator<Map.Entry<Long, Long>> it = waiting.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Long> e = it.next();
            if (e.getValue() <= now) {
                out.add(e.getKey());
                it.remove();
            }
        }
        return out;
    }

    /**
     * The answer for a cell that was due: it is there (OK), it is not and may be placed again (RETRY: the module puts the
     * cell back in the queue), or it is not and the retries are used up (FAILED: the module lists it).
     */
    public Verdict verdict(long key, boolean ok) {
        int tries = attempts.getOrDefault(key, 1);
        if (ok) {
            attempts.remove(key);
            return Verdict.OK;
        }
        if (tries <= retries) return Verdict.RETRY;
        attempts.remove(key);
        return Verdict.FAILED;
    }

    /** Placed blocks that have not been answered yet (waiting or due). */
    public int verifying() {
        return waiting.size();
    }

    /** How many times this cell has been placed so far (0 when unknown). */
    public int attemptsOf(long key) {
        return attempts.getOrDefault(key, 0);
    }

    /** Forgets a cell that left the build in another way (its block turned out to be there, or it was skipped). */
    public void forget(long key) {
        waiting.remove(key);
        attempts.remove(key);
    }

    // The tick

    /** Decides what to do now. */
    public Step tick(Obs o) {
        now++;
        if (ended()) return new Step(Act.NONE, Level.INFO, null);
        if (o.worldChanged()) return end(Phase.STOPPED, Act.ABORT, Level.WARN, "The world changed. Printing stopped.");
        if (o.cancelled()) return end(Phase.STOPPED, Act.ABORT, Level.INFO, "Printing stopped.");
        if (o.loadError() != null) return end(Phase.STOPPED, Act.ABORT, Level.ERROR, o.loadError());

        switch (phase) {
            case IDLE -> {
                phase = Phase.LOADING;
                return new Step(Act.LOAD, Level.INFO, null);
            }
            case LOADING -> {
                if (!o.loaded()) return none();
                phase = Phase.SCANNING;
                return new Step(Act.SCAN, Level.INFO, null);
            }
            case SCANNING -> {
                if (!o.scanDone()) return new Step(Act.SCAN, Level.INFO, null);
                phase = Phase.BUILDING;
                planned = true;
                return new Step(Act.PLAN, Level.INFO, null);
            }
            default -> {
                return build(o);
            }
        }
    }

    private Step build(Obs o) {
        if (!planned) return none();

        if (o.pending() == 0 && o.wrongPending() == 0 && !o.breaking() && waiting.isEmpty() && !o.sessionActive() && !o.resupplyActive() && !o.waiting()
                && o.layerHold() != null) {
            return pause(Pause.LAYER, o.layerHold());
        }
        // Finished only when every layer is done and the scaffold is gone
        if (o.pending() == 0 && o.wrongPending() == 0 && !o.breaking() && waiting.isEmpty() && !o.sessionActive() && !o.resupplyActive() && !o.waiting()
                && o.scaffoldLeft() == 0) {
            return end(Phase.DONE, Act.FINISH, Level.INFO, "Finished: everything that can be built is built.");
        }

        // A shulker or a resupply is doing the walking and the clicking: leave the world alone
        if (o.sessionActive() || o.resupplyActive()) {
            if (phase != Phase.RESUPPLYING) {
                phase = Phase.RESUPPLYING;
                pause = Pause.NONE;
                stuck = 0;
                return new Step(Act.STOP_WALK, Level.INFO, null);
            }
            return none();
        }
        boolean wasPaused = phase == Phase.PAUSED;
        if (phase == Phase.RESUPPLYING) phase = Phase.BUILDING;

        if (o.resupplyFailed() != null) {
            if (MaterialPolicy.decide(o.disableWhenOut(), false, o.supplyPossible(), true, o.orderWait() != null) == MaterialPolicy.Outcome.DISABLE) {
                return end(Phase.STOPPED, Act.ABORT, Level.WARN, "Could not get the materials: " + o.resupplyFailed()
                    + " disable-when-out-of-materials is on, so the Printer turns off.");
            }
            retryAt = now + RESUPPLY_RETRY;
            return pause(Pause.RESUPPLY_FAILED, "Could not get the materials: " + o.resupplyFailed()
                + " Add them to a source or your inventory; it tries again in " + RESUPPLY_RETRY / 20 + " seconds."
                + (o.orderWait() == null ? "" : " Waiting for " + o.orderWait() + "."));
        }

        // Wrong blocks first: they hold cells the rest of the build may depend on
        if (o.wrongPending() > 0 || o.breaking()) {
            stuck = 0;
            switch (o.wrongMode()) {
                case SKIP_AND_LIST -> {
                    return resumed(wasPaused, new Step(Act.SKIP_WRONG, Level.INFO, null));
                }
                case BREAK_AND_REPLACE -> {
                    return resumed(wasPaused, new Step(Act.BREAK_WRONG, Level.INFO, null));
                }
                case PAUSE -> {
                    return pause(Pause.WRONG, "A wrong block is in the way" + (o.wrongInfo() == null ? "" : ": " + o.wrongInfo())
                        + ". Remove it, or set wrong-blocks to skip or break. Printing resumes when it is gone.");
                }
            }
        }

        if (o.placeable()) {
            stuck = 0;
            retryAt = 0;
            return resumed(wasPaused, new Step(Act.PLACE, Level.INFO, null));
        }

        // Scaffold nobody needs is taken down once the placing is done (and before the run can end)
        if (o.scaffoldRemovable()) {
            stuck = 0;
            retryAt = 0;
            return resumed(wasPaused, new Step(Act.REMOVE_SCAFFOLD, Level.INFO, null));
        }

        if (o.walkTarget()) {
            stuck = 0;
            retryAt = 0;
            if (!o.canWalk()) {
                return pause(Pause.WALK_OFF, "The next blocks are out of reach and walking is not available (walk is off, Baritone is missing"
                    + " or another module has confined it). Move closer. Printing resumes when something is in reach.");
            }
            if (o.baritoneBusy()) {
                return pause(Pause.BARITONE, "Baritone is busy with something else. Printing resumes when it is idle.");
            }
            return resumed(wasPaused, new Step(Act.WALK, Level.INFO, null));
        }

        if (o.lacking()) {
            stuck = 0;
            String what = o.lackingInfo() == null ? "materials" : o.lackingInfo();
            // A full inventory only matters when there is a source to fetch from
            if (o.inventoryFull() && o.supplyPossible()) {
                return pause(Pause.FULL, "Your inventory is full, so " + what + " can't be fetched. Free a slot. Printing resumes by itself.");
            }
            if (o.supplyPossible()) {
                // A failed resupply waits out its retry time, whatever else changed in between
                if (now < retryAt) return none();
                return resumed(wasPaused, new Step(Act.RESUPPLY, Level.INFO, null));
            }
            if (MaterialPolicy.decide(o.disableWhenOut(), false, false, false, o.orderWait() != null) == MaterialPolicy.Outcome.DISABLE) {
                return end(Phase.STOPPED, Act.ABORT, Level.WARN, "Out of " + what + " and nothing can supply it"
                    + (o.orderProblem() == null ? "" : " (" + o.orderProblem() + ")") + ". disable-when-out-of-materials is on, so the Printer turns off.");
            }
            if (o.orderWait() != null) {
                return pause(Pause.AWAITING_ORDER, "Out of " + what + ". Waiting for " + o.orderWait()
                    + ": collect the delivery and put it in your inventory. Printing resumes by itself.");
            }
            if (o.orderProblem() != null) {
                return pause(Pause.NO_SOURCE, "Out of " + what + " and stash orders can't help: " + o.orderProblem()
                    + ". Fix that or add some to your inventory. Printing resumes by itself.");
            }
            return pause(Pause.NO_SOURCE, "Out of " + what + " and no materials source is set up. Add some to your inventory,"
                + " or turn on use-shulkers, use-containers, use-ender-chest or order-from-stash.");
        }

        // Nothing ready, nothing to fetch. Placed blocks may still turn up, so wait for those
        // (The layer cursor carries stalled layers over before this counts as being stuck.)
        if (!waiting.isEmpty() || o.waiting() || o.layerMoving()) {
            stuck = 0;
            return resumed(wasPaused, none());
        }
        // Stuck is a pause, not an end: it says so once and goes on by itself when something becomes placeable or walkable
        if (phase == Phase.PAUSED && pause == Pause.STUCK) return none();
        if (wasPaused) {
            phase = Phase.BUILDING;
            pause = Pause.NONE;
        }
        if (++stuck >= STUCK_TICKS) {
            return pause(Pause.STUCK, STUCK_PREFIX + o.pending() + " cell(s) can't be built right now (nothing to place against, blocked, or not loaded);"
                + " the Printer stays on and tries again."
                + " Turn it off yourself if you don't want to wait.");
        }
        return none();
    }

    private static boolean isWork(Act act) {
        return act == Act.PLACE || act == Act.WALK || act == Act.RESUPPLY || act == Act.SKIP_WRONG || act == Act.BREAK_WRONG || act == Act.REMOVE_SCAFFOLD;
    }

    /**
     * Work starts (or goes on). The phase is BUILDING at once, so nothing about the loop says "paused" while it works;
     * only the "Printing resumed." line waits until the work has held for the hysteresis (a flapping condition says nothing).
     */
    private Step resumed(boolean wasPaused, Step step) {
        boolean work = isWork(step.act());
        if (!work) {
            // Leaving a pause because something else is going on says nothing now; the line comes with the next work
            if (wasPaused || pause != Pause.NONE) {
                phase = Phase.BUILDING;
                pause = Pause.NONE;
            }
            return step;
        }
        if (wasPaused || pause != Pause.NONE) {
            phase = Phase.BUILDING;
            pause = Pause.NONE;
        }
        if (announced == Pause.NONE) return step;
        if (hysteresis > 0) {
            if (now - resumeLast > 1) resumeSince = now;
            resumeLast = now;
            if (now - resumeSince < hysteresis) return step;
        }
        announced = Pause.NONE;
        if (step.msg() != null) return step;
        return new Step(step.act(), Level.INFO, "Printing resumed.");
    }

    /**
     * A condition that stops the build. Pauses that must stop the walking ({@link #stopsMovement}) send STOP_WALK at once;
     * only the announcement waits for the hysteresis. A pause the player was told about and that comes back before the
     * resume was announced is not announced again.
     */
    private Step pause(Pause why, String msg) {
        if (phase == Phase.PAUSED && pause == why) {
            candidate = Pause.NONE;
            return none();
        }
        if (hysteresis > 0 && why != Pause.STUCK && why != Pause.LAYER && why != Pause.RESUPPLY_FAILED) {
            // The condition must hold for a while before it is announced; a flapping one says nothing
            if (candidate != why || now - candidateLast > 1) {
                candidate = why;
                candidateSince = now;
            }
            candidateLast = now;
            if (now - candidateSince < hysteresis) return stopsMovement(why) ? new Step(Act.STOP_WALK, Level.INFO, null) : none();
        }
        candidate = Pause.NONE;
        phase = Phase.PAUSED;
        pause = why;
        String shown = announced == why ? null : msg;
        announced = why;
        return new Step(Act.STOP_WALK, Level.WARN, shown);
    }

    private static boolean stopsMovement(Pause why) {
        return why == Pause.FULL || why == Pause.BARITONE || why == Pause.WALK_OFF;
    }

    private Step none() {
        return new Step(Act.NONE, Level.INFO, null);
    }

    private Step end(Phase to, Act act, Level level, String msg) {
        phase = to;
        waiting.clear();
        attempts.clear();
        return new Step(act, level, msg);
    }

    /** The run is over for a reason the loop didn't see (module off, disconnect): nothing may be started again. */
    public void stop() {
        phase = Phase.STOPPED;
        waiting.clear();
        attempts.clear();
    }
}
