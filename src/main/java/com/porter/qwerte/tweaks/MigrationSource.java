package com.porter.qwerte.tweaks;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where Container Tweaks takes its saved state from when it moves to its new module name. Pure, so it is tested without a
 * game.
 *
 * <p>The module used to be called {@code container-tweaks}, which Musheor's module also uses, so Meteor kept only one of
 * them. An old entry is taken over only when it is ours, recognised by at least two of its setting groups and
 * setting names, one of them a name only this module has. An entry with none of them (all defaults, or another addon's module) is never taken.
 */
public final class MigrationSource {
    private MigrationSource() {}

    public static final String NEW_NAME = "qwerte-container-tweaks";
    public static final String OLD_NAME = "container-tweaks";
    public static final String STRAY_NAME = "container-tweaks-porter";

    /** Looked at in this order. */
    private static final List<String> OLD_NAMES = List.of(OLD_NAME, STRAY_NAME);

    /** Names that also occur in other modules, or are default-looking: they only count together with a distinctive one. */
    private static final Set<String> GENERIC = Set.of("Shulker Preview", "Smart Shulker", "Smart Echest", "hide-text-list",
        "hotbar-icon", "icon-scale", "icon-corner");
    /** Settings only this module has. */
    private static final Set<String> DISTINCTIVE = Set.of("smart-shulker", "smart-echest", "pickup-with-baritone",
        "no-silk-touch", "freeze-key");

    /** A saved module entry: its name and the names of the setting groups and settings saved in it. */
    public record Entry(String name, Set<String> groups, Set<String> settings) {}

    /** {@code source} is the name of the entry to take over, or null; {@code reason} says why, for the log. */
    public record Decision(String source, String reason) {}

    /** Ours when at least two distinct markers match and at least one of them is distinctive. */
    public static boolean isOurs(Entry entry) {
        Set<String> markers = new HashSet<>();
        boolean distinctive = false;
        for (String name : entry.groups()) if (GENERIC.contains(name)) markers.add(name);
        for (String name : entry.settings()) {
            if (GENERIC.contains(name)) markers.add(name);
            else if (DISTINCTIVE.contains(name)) {
                markers.add(name);
                distinctive = true;
            }
        }
        return distinctive && markers.size() >= 2;
    }

    public static Decision choose(List<Entry> entries) {
        for (Entry e : entries) {
            if (NEW_NAME.equalsIgnoreCase(e.name())) return new Decision(null, "already saved as " + NEW_NAME);
        }
        StringBuilder skipped = new StringBuilder();
        for (String name : OLD_NAMES) {
            for (Entry e : entries) {
                if (!name.equalsIgnoreCase(e.name())) continue;
                if (isOurs(e)) return new Decision(name, "took over the saved state of '" + name + "'");
                if (skipped.length() > 0) skipped.append("; ");
                skipped.append("'").append(name).append("' has none of our settings (all defaults, or another addon's module)");
            }
        }
        return new Decision(null, skipped.length() > 0 ? skipped + ", starting from defaults" : "no old entry, starting from defaults");
    }
}
