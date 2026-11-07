package com.porter.stashmanager.planner;

import java.util.Map;

/**
 * Survival materials for a schematic. All maps are sorted by id.
 *
 * @param tableVersion   the block-items table used (e.g. "1.21.11")
 * @param items          item id -> count for the blocks themselves
 * @param fluids         {@code minecraft:water_bucket} / {@code minecraft:lava_bucket} -> source blocks (level=0)
 * @param containerItems item id -> count found inside chests and other block entities (not part of {@code items})
 * @param unknown        block id -> count for ids the table does not know (at most {@link BlockItems#MAX_UNKNOWN} ids)
 * @param unknownOmitted blocks with unknown ids beyond the {@link BlockItems#MAX_UNKNOWN} listed ones
 */
public record MaterialList(String tableVersion, Map<String, Long> items, Map<String, Long> fluids, Map<String, Long> containerItems,
                           Map<String, Long> unknown, long unknownOmitted) {
}
