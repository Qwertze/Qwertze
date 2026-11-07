package com.porter.qwerte.manager.map;

import java.util.List;
import java.util.Set;

/**
 * What the live viewer's stream says, after it was parsed and cleaned on the stream's own thread. Every string here has
 * been through {@code Text.sanitize}; nothing is a raw server string. The first group are the server's events
 * (PROTOCOL.md, "Additions: live viewer"), then the stream's own notices, then the data they carry.
 */
public sealed interface ViewerEvent {
    record Hello(String botId, String mc, boolean relative) implements ViewerEvent {
    }

    record Snapshot(String session, World world, Self self, List<Tile> tiles, List<Ent> ents, Hud hud, List<ChatLine> chat,
                    boolean relative) implements ViewerEvent {
    }

    record Tiles(List<Tile> tiles, List<int[]> drop) implements ViewerEvent {
    }

    /** {@code self} is null when the event carried none. */
    record Ents(Self self, List<Ent> ents) implements ViewerEvent {
    }

    /** {@code hud} may be null: the bot has not sent one yet. */
    record HudUpdate(Hud hud) implements ViewerEvent {
    }

    record Chat(List<ChatLine> lines) implements ViewerEvent {
    }

    /** waiting, live, stale or offline. */
    record Status(String state) implements ViewerEvent {
    }

    /** rotate, revoked or capacity. */
    record Bye(String reason) implements ViewerEvent {
    }

    /** The stream is being opened. */
    record Connecting() implements ViewerEvent {
    }

    /** The stream could not be opened or ended: the HTTP status (0 = no answer) and when it will try again (0 = never). */
    record Failed(int status, long retryMs) implements ViewerEvent {
    }

    record World(String dim, int minY, int height) {
    }

    record Self(double x, double y, double z, double yaw) {
    }

    /** One 16x16 chunk tile: {@code colors} are 256 packed map bytes (column i = lz * 16 + lx); {@code heights} 256 values or null. */
    record Tile(int x, int z, byte[] colors, short[] heights) {
    }

    /** {@code kind}: player, mob, item or other. {@code name} is a player's, {@code type} a mob type or an item id. */
    record Ent(int id, String kind, String name, String type, int count, double x, double y, double z, double yaw) {
        private static final Set<String> HOSTILE = Set.of("zombie", "zombie_villager", "husk", "drowned", "skeleton", "stray", "bogged",
            "wither_skeleton", "creeper", "spider", "cave_spider", "enderman", "endermite", "silverfish", "witch", "slime", "magma_cube",
            "blaze", "ghast", "phantom", "pillager", "vindicator", "evoker", "illusioner", "ravager", "vex", "guardian", "elder_guardian",
            "shulker", "hoglin", "zoglin", "piglin_brute", "warden", "breeze", "creaking", "wither", "ender_dragon");

        /** A mob type the website draws red. */
        public boolean hostile() {
            if (!kind.equals("mob")) return false;
            String t = type.startsWith("minecraft:") ? type.substring(10) : type;
            return HOSTILE.contains(t);
        }
    }

    record Slot(String id, int count) {
    }

    /** Numbers the bot did not send are NaN. {@code held} is -1 when unknown. Slot lists have 9 and 4 entries; entries may be null. */
    record Hud(double hp, double food, double sat, double xp, double xpP, double armor, int held, List<Slot> hotbar, List<Slot> armorSlots,
               Slot offhand) {
    }

    /** {@code kind}: public, whisper, system or death. */
    record ChatLine(long time, String kind, String sender, String to, String text) {
    }
}
