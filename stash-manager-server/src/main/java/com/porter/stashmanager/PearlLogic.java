package com.porter.stashmanager;

/**
 * The pure rules of pearl travel (PEARL-CONTRACT v1.0 rev 2): what a chamber's state is, whether a pull is confirmed by both
 * sides, and whether a trip's pearl count needs a look. Tested against pearl-vectors.json.
 */
final class PearlLogic {
    /** A loader report older than this says nothing (the chamber is unknown, the loader is stale). */
    static final long FRESH_MS = 60_000;
    /** The pull deadline after the whisper (PearlPlus aborts a job after 120 s; T waits 150 s). */
    static final long PULL_WAIT_MS = 150_000;
    /** L's pearlGoneAt and requesterNearAt must be this close to T's teleport. */
    static final long AGREE_MS = 10_000;
    /** No loader entry for the load within this long: it was denied or ignored. */
    static final long LOAD_ANSWER_MS = 15_000;
    /** A teleport T reported must be confirmed by the loader within this long. */
    static final long CONFIRM_MS = 20_000;
    /** After a re-arm the server waits this long for the loader to say armed. */
    static final long REARM_VERIFY_MS = 60_000;
    static final long RETRY_MS = 30_000;
    static final long PAUSE_MAX_MS = 10 * 60_000;
    static final long COOLDOWN_MS = 5 * 60_000;
    static final int LOADS_PER_HOUR = 20;
    static final int MAX_TRIES = 3;
    /** T must have been in the game this long before a trip. */
    static final long JOIN_SETTLE_MS = 10_000;

    private PearlLogic() {
    }

    /**
     * The server's view of a chamber: broken if broken; unknown when the report is older than 60 s; owner_offline when the loader
     * said empty but the owner (a traveller of this install) is not in the game; else what the loader said.
     */
    static String chamberState(String presence, long ageMs, boolean broken, String ownerGame) {
        if (broken) return "broken";
        if (ageMs > FRESH_MS) return "unknown";
        if (presence.equals("empty") && ownerGame != null && !ownerGame.equals("in")) return "owner_offline";
        return presence;
    }

    /**
     * Whether the pull is confirmed: the pearl was seen gone and T seen near the chamber, both after the whisper and each within
     * 10 s of T's teleport, with every bot time moved to the server's clock first (time + the sender's skew). The teleport must be no
     * later than the trip's pull deadline: loadSentAt + 150 s, or loadingAt + 150 s once the loader reported loading (loadingAt is L's
     * load.at, null before that), whichever is later. A missing time never confirms.
     */
    static boolean arrivalConfirmed(long loadSentAt, Long loadingAt, long teleportAt, Long pearlGoneAt, Long requesterNearAt, long tSkew, long lSkew) {
        if (pearlGoneAt == null || requesterNearAt == null) return false;
        long sent = loadSentAt + tSkew, tele = teleportAt + tSkew;
        long gone = pearlGoneAt + lSkew, near = requesterNearAt + lSkew;
        long deadline = pullDeadline(sent, loadingAt == null ? null : loadingAt + lSkew);
        if (tele < sent || tele > deadline) return false;
        if (gone < sent || near < sent) return false;
        return Math.abs(gone - tele) <= AGREE_MS && Math.abs(near - tele) <= AGREE_MS;
    }

    /** The pull deadline on the server's clock: the whisper + 150 s, or the loader's loading time + 150 s when that is later. */
    static long pullDeadline(long loadSent, Long loadingAt) {
        long d = loadSent + PULL_WAIT_MS;
        return loadingAt == null ? d : Math.max(d, loadingAt + PULL_WAIT_MS);
    }

    /**
     * Whether the pearl count at the end of a trip is worth a warning. Expected: before, less one when T armed the home chamber
     * first, plus one when the loader dropped a pearl, less one for the re-arm; one fewer (a missed pickup) is fine only when the
     * loader drops a pearl.
     */
    static boolean countWarning(int before, boolean dropAfterLoad, boolean armedHome, int after) {
        int exp = before - (armedHome ? 1 : 0) + (dropAfterLoad ? 1 : 0) - 1;
        return after != exp && !(dropAfterLoad && after == exp - 1);
    }
}
