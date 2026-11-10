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
        /** The matched kit template's own chest (v2). */
        TEMPLATE,
        /** The chest of the kit's category (v2). */
        CATEGORY,
        /** A container was chosen but is missing or can't take kits: no destination. */
        MISSING,
        /** Every chest in the kit's chain is read but full (or not usable now): no destination, no warning (v2). */
        FULL
    }

    /** @param container the chosen container id (a rule's or the kits chest), null for NOT_KIT and NONE */
    public record Decision(Kind kind, String key, String container) {
        static final Decision NOT_KIT = new Decision(Kind.NOT_KIT, null, null);

        /** The kit has a say in where it goes (a target, or none at all): today's sorting does not apply. */
        public boolean routed() {
            return kind == Kind.RULE || kind == Kind.KITS_CHEST || kind == Kind.TEMPLATE || kind == Kind.CATEGORY || kind == Kind.MISSING || kind == Kind.FULL;
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

    /** One level of a kit's chain: where the container comes from ("rule", "template", "category", "kitsChest") and its id. */
    public record Level(String source, String container) {
    }

    /**
     * A kit's chain (KIT-CONTRACT v2, "What the bot does"): the levels that are set, in order: (a) its rule's container, for a named kit with a rule;
     * (b) the matched template's chest; (c) its category's chest; (d) the general kits chest. A container that comes twice is listed at its first
     * level. Empty: today's sorting.
     * @param key the normalised name of the kit, null when unnamed
     */
    public static List<Level> chain(String key, KitClassifier.Result why, KitSettings settings) {
        List<Level> out = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        String rule = key == null ? null : settings.rules().get(key);
        if (rule != null && seen.add(rule)) out.add(new Level("rule", rule));
        KitSettings.Types types = settings.types();
        if (types != null) {
            if (why.template() != null) {
                KitTemplate t = types.template(why.template());
                if (t != null && t.chest() != null && seen.add(t.chest())) out.add(new Level("template", t.chest()));
            }
            String category = why.kit() ? why.type() : null;
            String chest = category == null ? null : types.chests().get(category);
            if (chest != null && seen.add(chest)) out.add(new Level("category", chest));
        }
        if (settings.kitsChest() != null && seen.add(settings.kitsChest())) out.add(new Level("kitsChest", settings.kitsChest()));
        return out;
    }

    /** The container the copies of a template are kept in: the first level of its chain (its name's rule, its chest, its category's chest, the kits chest), or null. */
    public static String templateTarget(KitTemplate t, KitSettings settings) {
        KitClassifier.Result as = new KitClassifier.Result(true, KitClassifier.TEMPLATE, t.category(), 27, 0, 0, 0, 0, List.of(), List.of(), t.id(), List.of());
        List<Level> chain = chain(KitNames.normalize(t.name()), as, settings);
        return chain.isEmpty() ? null : chain.get(0).container();
    }

    /**
     * Where a kit goes along its chain.
     * @param level index into the chain of the chosen container, or -1 for none; {@code kind} what it is; {@code stays}: the kit is home and is not moved
     */
    public record Pick(Kind kind, int level, boolean stays) {
    }

    private static Kind kindOf(String source) {
        return switch (source) {
            case "rule" -> Kind.RULE;
            case "template" -> Kind.TEMPLATE;
            case "category" -> Kind.CATEGORY;
            default -> Kind.KITS_CHEST;
        };
    }

    /**
     * The destination along a chain:
     * <ul>
     *     <li>a kit to place, or stored elsewhere (current rank -1): the first usable container;</li>
     *     <li>a stored kit already in a chain container: the first usable container ranked above its current one; none, and it stays (it is home):
     *     a kit is never moved down its chain because its chest filled up;</li>
     *     <li>no usable container: MISSING when none of the chain's containers can take kits, else FULL.</li>
     * </ul>
     * @param pickable    the container resolves to a box that can take kits
     * @param usable      pickable, with at least one empty slot by the bot's current read
     * @param currentRank the chain index of the container the kit is stored in, or -1
     */
    public static Pick pick(List<Level> chain, Predicate<String> pickable, Predicate<String> usable, int currentRank) {
        if (chain.isEmpty()) return new Pick(Kind.NONE, -1, false);
        int upper = currentRank >= 0 ? currentRank : chain.size();
        for (int i = 0; i < upper; i++) if (usable.test(chain.get(i).container())) return new Pick(kindOf(chain.get(i).source()), i, false);
        if (currentRank >= 0) return new Pick(kindOf(chain.get(currentRank).source()), currentRank, true);
        boolean any = false;
        for (Level l : chain) any |= pickable.test(l.container());
        return new Pick(any ? Kind.FULL : Kind.MISSING, -1, false);
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

        /** Pickable, with at least one empty slot by the bot's current read (v2: a full chest is skipped for the next level). */
        default boolean usable(B box) {
            return pickable(box);
        }
    }

    /** What a decision is for one stack in a stash, plus the box it names. */
    public record Route<B>(Decision decision, B box, boolean fallback) {
        public Route(Decision decision, B box) {
            this(decision, box, false);
        }

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
        private Set<B> reserved;
        /** The answer for everything that is not a kit: one object, so planning allocates nothing per stack. */
        private final Route<B> notKit = new Route<>(Decision.NOT_KIT, null);

        public View(Resolver<B> resolver, KitSettings settings) {
            this.resolver = resolver;
            this.settings = settings;
            Set<B> r = Collections.newSetFromMap(new IdentityHashMap<>());
            this.reserved = null;
        }

        /** The kit-reserved boxes, resolved on first use (a view is made again often; most never ask). */
        private Set<B> reservedSet() {
            Set<B> r = reserved;
            if (r == null) {
                r = Collections.newSetFromMap(new IdentityHashMap<>());
                for (String id : settings.rules().values()) add(r, id);
                if (settings.kitsChest() != null) add(r, settings.kitsChest());
                // v2: every category chest and every template chest is kit-reserved too
                if (settings.types() != null) {
                    for (String id : settings.types().chests().values()) add(r, id);
                    for (KitTemplate t : settings.types().templates()) if (t.chest() != null) add(r, t.chest());
                }
                reserved = r;
            }
            return r;
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

        /** The kits chest, every rule target, every category chest and every template chest: nothing but kit shulkers goes in. */
        public boolean reserved(B box) {
            return reservedSet().contains(box);
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

        /**
         * v2: where a kit goes along its chain (rule, template chest, category chest, kits chest).
         * @param key     the kit's normalised name, null when unnamed
         * @param why     the classifier's verdict for the shulker (a kit)
         * @param current the container the kit is stored in, or null (carried, or from a drop-off)
         */
        public Route<B> route(String key, KitClassifier.Result why, B current) {
            if (!why.kit() || settings.isEmpty()) return notKit;
            List<Level> chain = chain(key, why, settings);
            if (chain.isEmpty()) return new Route<>(new Decision(Kind.NONE, key, null), null);
            int rank = -1;
            if (current != null) {
                for (int i = 0; i < chain.size() && rank < 0; i++) if (box(chain.get(i).container()) == current) rank = i;
            }
            Pick pick = pick(chain, this::pickable, id -> {
                B b = box(id);
                return b != null && resolver.usable(b);
            }, rank);
            if (pick.level() < 0) return new Route<>(new Decision(pick.kind(), key, chain.get(0).container()), null);
            String container = chain.get(pick.level()).container();
            return new Route<>(new Decision(pick.kind(), key, container), box(container), pick.level() > 0 && !pick.stays());
        }

        /** The boxes of a kit's chain that resolve, in order. */
        public List<B> chainBoxes(String key, KitClassifier.Result why) {
            if (!why.kit() || settings.isEmpty()) return List.of();
            List<B> out = new java.util.ArrayList<>();
            for (Level l : chain(key, why, settings)) {
                B b = box(l.container());
                if (b != null) out.add(b);
            }
            return out;
        }

        /** Where a routed kit goes: the target alone, or nothing for MISSING and FULL. */
        public List<B> destinations(Route<B> route) {
            return route.box() != null && route.kind() != Kind.MISSING && route.kind() != Kind.FULL ? List.of(route.box()) : List.of();
        }
    }
}
