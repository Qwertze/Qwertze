package com.porter.stashmanager.planner;

import java.util.Map;

/**
 * What a schematic contains, independent of its file format.
 *
 * @param format        file format
 * @param formatVersion the format's own version field (Litematica 4-7, Sponge 1-3; 0 for structures)
 * @param dataVersion   Minecraft data version the file was saved with (0 when the file has none)
 * @param sizeX         bounding box of all regions, in blocks
 * @param regionCount   Litematica regions (1 for the other formats)
 * @param volume        sum of the region volumes (air included)
 * @param nonAirBlocks  blocks that are not air, cave_air, void_air or structure_void
 * @param blockCounts   count per block state, air states left out
 * @param containerItems items inside block entities ({@code Items[{id, count|Count}]}), by item id; components ignored
 */
public record Schematic(Format format, int formatVersion, int dataVersion, int sizeX, int sizeY, int sizeZ, int regionCount,
                        long volume, long nonAirBlocks, Map<BlockState, Long> blockCounts, Map<String, Long> containerItems) {
    public enum Format { LITEMATIC, SPONGE, STRUCTURE }

    public Schematic {
        blockCounts = Map.copyOf(blockCounts);
        containerItems = Map.copyOf(containerItems);
    }
}
