package com.porter.stashmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.porter.qwerte.core.enchant.McData;

import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

/**
 * What each stash's bot said about enchanting in its heartbeats (ENCHANT-CONTRACT v1.0, "Heartbeat, claim and capability"): the
 * {@code enchant} block {@code {v: 1, enabled, mc, carry?}} with the heartbeat's player. In memory only, like the craft capability:
 * after a server restart a bot is unknown until its next heartbeat, so no enchant order is placed or handed out meanwhile.
 */
final class EnchantBots {
    /** An enchant order is handed out only to a bot whose block is newer than this ("about 2 minutes"). */
    static final long FRESH_MS = 120_000;
    static final int MAX_STASHES = 256;
    /** The free carry slots assumed when the bot reports none (plan time). */
    static final int DEFAULT_CARRY = 20;
    static final int MAX_CARRY = 36;

    /** The block. carry is -1 when the heartbeat had none. */
    record Cap(int v, boolean enabled, String mc, int carry) {
    }

    /** cap is null when the bot cleared it; at is when a heartbeat last carried the block; player is that heartbeat's player. */
    record Beat(Cap cap, String player, long at) {
    }

    private volatile LongSupplier clock;
    private final LinkedHashMap<IndexStore.StashKey, Beat> beats = new LinkedHashMap<>();

    EnchantBots() {
        this(System::currentTimeMillis);
    }

    EnchantBots(LongSupplier clock) {
        this.clock = clock;
    }

    long now() {
        return clock.getAsLong();
    }

    /** Tests move the clock. */
    void clock(LongSupplier clock) {
        this.clock = clock;
    }

    /** The heartbeat's enchant object. Throws IllegalArgumentException (a 400) for a wrong shape; the message never repeats the input. */
    static Cap parse(JsonObject o) {
        if (EnchantIndex.whole(o.get("v"), 1, 1) != 1) throw new IllegalArgumentException("enchant.v must be 1");
        if (!(o.get("enabled") instanceof JsonPrimitive en) || !en.isBoolean()) throw new IllegalArgumentException("enchant.enabled must be true or false");
        String mc = AddonRoutes.string(o, "mc");
        // Checked before McData.load: only a version with a bundled table is ever loaded.
        if (mc == null || !McData.SUPPORTED.contains(mc)) throw new IllegalArgumentException("enchant.mc must be one of " + McData.SUPPORTED);
        int carry = -1;
        if (o.has("carry") && !o.get("carry").isJsonNull()) {
            long c = EnchantIndex.whole(o.get("carry"), 0, MAX_CARRY);
            if (c < 0) throw new IllegalArgumentException("enchant.carry must be a whole number from 0 to " + MAX_CARRY);
            carry = (int) c;
        }
        return new Cap(1, en.getAsBoolean(), mc, carry);
    }

    /**
     * A heartbeat: hasKey says the body had an enchant key (cap null = cleared); an absent key keeps what is stored. player is the
     * heartbeat's player (required by the route whenever the key is sent).
     */
    synchronized void beat(String server, String stash, boolean hasKey, Cap cap, String player) {
        if (!hasKey) return;
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        beats.remove(k); // re-inserted last: the oldest stash is dropped first
        beats.put(k, new Beat(cap, player, clock.getAsLong()));
        while (beats.size() > MAX_STASHES) {
            var it = beats.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    synchronized Beat get(String server, String stash) {
        return beats.get(new IndexStore.StashKey(server, stash));
    }

    synchronized void forget(String server, String stash) {
        beats.remove(new IndexStore.StashKey(server, stash));
    }

    /** Whether a bot ever sent the block (even a disabled one) since the start. */
    synchronized boolean known(String server, String stash) {
        Beat b = beats.get(new IndexStore.StashKey(server, stash));
        return b != null && b.cap() != null;
    }

    /**
     * Whether an enchant order may be handed out or placed: a stored block with enabled true, newer than {@link #FRESH_MS}, whose mc
     * is the index's mcVersion, from a heartbeat whose player is the stash owner ({@code owner}) and the claiming player when one is
     * given (PearlBodies.PLAYER grammar).
     */
    synchronized boolean capable(String server, String stash, String owner, String indexMc, String claimPlayer) {
        Beat b = beats.get(new IndexStore.StashKey(server, stash));
        if (b == null || b.cap() == null || b.cap().v() != 1 || !b.cap().enabled()) return false;
        if (clock.getAsLong() - b.at() >= FRESH_MS) return false;
        if (indexMc == null || !indexMc.equals(b.cap().mc())) return false;
        if (owner == null || b.player() == null || !PearlBodies.PLAYER.matcher(b.player()).matches() || !owner.equalsIgnoreCase(b.player())) return false;
        return claimPlayer == null || (PearlBodies.PLAYER.matcher(claimPlayer).matches() && claimPlayer.equalsIgnoreCase(owner));
    }

    /** Whether a heartbeat carried an enabled block within the last ms milliseconds (the held-order downgrade rule). */
    synchronized boolean enabledWithin(String server, String stash, long ms) {
        Beat b = beats.get(new IndexStore.StashKey(server, stash));
        return b != null && b.cap() != null && b.cap().enabled() && clock.getAsLong() - b.at() < ms;
    }

    /** The bot's free carry slots after its gear is stowed: its report, else 20. */
    synchronized int carry(String server, String stash) {
        Beat b = beats.get(new IndexStore.StashKey(server, stash));
        return b == null || b.cap() == null || b.cap().carry() < 0 ? DEFAULT_CARRY : b.cap().carry();
    }

    /** GET /api/v1/stashes: {supports, mc, canOrder}. No positions and no names. */
    JsonObject summary(String server, String stash, String owner, String indexMc) {
        Beat b;
        synchronized (this) {
            b = beats.get(new IndexStore.StashKey(server, stash));
        }
        JsonObject o = new JsonObject();
        boolean supports = b != null && b.cap() != null && b.cap().enabled() && clock.getAsLong() - b.at() < FRESH_MS;
        o.addProperty("supports", supports);
        o.addProperty("mc", b != null && b.cap() != null ? b.cap().mc() : null);
        o.addProperty("canOrder", capable(server, stash, owner, indexMc, null));
        return o;
    }
}
