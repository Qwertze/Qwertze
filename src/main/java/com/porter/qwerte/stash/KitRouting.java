package com.porter.qwerte.stash;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Where a kit shulker goes (KIT-CONTRACT.md, "What the bot does"). {@link #decide} is the pure decision on a name and the settings;
 * {@link View} puts the settings against a stash: which box an id is, which boxes are kit-reserved. No game access, any thread (the
 * boxes are whatever type the caller resolves ids to: the sorter uses {@code Stash.Box}).
 */
public final class KitRouting {
    private KitRouting() {
    }

    public enum Kind {
        /** Not a shulker, or its name is not a kit's: this feature has nothing to say. */
        NOT_KIT,
        /** A kit with neither a rule nor a kits chest: today's sorting. */
        NONE,
        /** The rule's container. */
        RULE,
        /** The kits chest. */
        KITS_CHEST,
        /** A container was chosen but is missing or can't take kits: no destination. */
        MISSING
    }

    /** @param container the chosen container id (a rule's or the kits chest), null for NOT_KIT and NONE */
    public record Decision(Kind kind, String key, String container) {
        static final Decision NOT_KIT = new Decision(Kind.NOT_KIT, null, null);

        /** The kit has a say in where it goes (a target, or none at all): today's sorting does not apply. */
        public boolean routed() {
            return kind == Kind.RULE || kind == Kind.KITS_CHEST || kind == Kind.MISSING;
        }
    }

    /**
     * Order: a rule for the key (its container, if it resolves and is pickable: the only destination), else the kits chest the same way,
     * else nothing set. A chosen container that is missing or not pickable is MISSING.
     * @param key      the normalised custom name of the shulker, null when it has none (an unnamed kit has no rule)
     * @param kit      whether the shulker scores as a kit ({@link KitScore}); anything else is NOT_KIT
     * @param pickable whether a container id resolves to a box that can take kits
     */
    public static Decision decide(String key, boolean kit, KitSettings settings, Predicate<String> pickable) {
        if (!kit) return Decision.NOT_KIT;
        String chosen = key == null ? null : settings.rules().get(key);
        Kind kind = Kind.RULE;
        if (chosen == null) {
            chosen = settings.kitsChest();
            kind = Kind.KITS_CHEST;
        }
        if (chosen == null) return new Decision(Kind.NONE, key, null);
        return new Decision(pickable.test(chosen) ? kind : Kind.MISSING, key, chosen);
    }

    /** The id of a box: its smallest part (x, then y, then z). {@code parts} is never empty. */
    public static String canonicalId(List<KitSettings.Pos> parts) {
        KitSettings.Pos best = null;
        for (KitSettings.Pos p : parts) if (best == null || before(p, best)) best = p;
        return KitSettings.id(best.x(), best.y(), best.z());
    }

    private static boolean before(KitSettings.Pos a, KitSettings.Pos b) {
        if (a.x() != b.x()) return a.x() < b.x();
        if (a.y() != b.y()) return a.y() < b.y();
        return a.z() < b.z();
    }

    /** How a view finds boxes. */
    public interface Resolver<B> {
        /** The box that has this position among its parts (any part), or null. */
        B boxAt(KitSettings.Pos pos);

        /** Read, sortable, not excluded, not a drop-off and not a delivery container. */
        boolean pickable(B box);
    }

    /** What a decision is for one stack in a stash, plus the box it names. */
    public record Route<B>(Decision decision, B box) {
        public Kind kind() {
            return decision.kind();
        }
    }

    /**
     * The settings against one stash at one moment. Ids are resolved with {@code boxAt}, which matches any part, so an id keeps working
     * when a single chest becomes a double. Build a new one when the settings or the boxes change.
     */
    public static final class View<B> {
        private final Resolver<B> resolver;
        private final KitSettings settings;
        private final Map<String, B> resolved = new HashMap<>();
        private final Set<B> reserved;
        /** The answer for everything that is not a kit: one object, so planning allocates nothing per stack. */
        private final Route<B> notKit = new Route<>(Decision.NOT_KIT, null);

        public View(Resolver<B> resolver, KitSettings settings) {
            this.resolver = resolver;
            this.settings = settings;
            Set<B> r = Collections.newSetFromMap(new IdentityHashMap<>());
            for (String id : settings.rules().values()) add(r, id);
            if (settings.kitsChest() != null) add(r, settings.kitsChest());
            this.reserved = r;
        }

        private void add(Set<B> into, String id) {
            B box = box(id);
            if (box != null) into.add(box);
        }

        public KitSettings settings() {
            return settings;
        }

        /** The box an id names, or null when there is none. */
        public B box(String id) {
            if (resolved.containsKey(id)) return resolved.get(id);
            KitSettings.Pos pos = KitSettings.position(id);
            B box = pos == null ? null : resolver.boxAt(pos);
            resolved.put(id, box);
            return box;
        }

        private boolean pickable(String id) {
            B box = box(id);
            return box != null && resolver.pickable(box);
        }

        /** The kits chest and every rule target: nothing but kit shulkers goes in. */
        public boolean reserved(B box) {
            return reserved.contains(box);
        }

        public boolean isEmpty() {
            return settings.isEmpty();
        }

        /** What happens to a shulker with this key (null when unnamed) that scores as a kit or not. */
        public Route<B> route(String key, boolean kit) {
            if (!kit || settings.isEmpty()) return notKit;
            Decision d = decide(key, true, settings, this::pickable);
            return new Route<>(d, d.routed() && d.container() != null ? box(d.container()) : null);
        }

        /** Where a routed kit goes: the target alone, or nothing for MISSING. */
        public List<B> destinations(Route<B> route) {
            return route.box() != null && route.kind() != Kind.MISSING ? List.of(route.box()) : List.of();
        }
    }
}
