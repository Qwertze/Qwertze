package com.porter.stashmanager.planner;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.porter.stashmanager.planner.NbtWriter.compound;
import static com.porter.stashmanager.planner.NbtWriter.compounds;
import static com.porter.stashmanager.planner.NbtWriter.state;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Materials against the real generated tables in src/main/resources/block-items. */
class BlockItemsTest {
    private static Schematic schematic(Map<String, Long> states) {
        Map<BlockState, Long> m = new HashMap<>();
        states.forEach((k, v) -> m.merge(BlockState.parse(k), v, Long::sum));
        return new Schematic(Schematic.Format.SPONGE, 2, 4189, 1, 1, 1, 1, 0, 0, m, Map.of());
    }

    private static MaterialList materials(String version, Object... stateCounts) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < stateCounts.length; i += 2) m.merge((String) stateCounts[i], ((Number) stateCounts[i + 1]).longValue(), Long::sum);
        return BlockItems.forVersion(version).materials(schematic(m));
    }

    private static long item(MaterialList l, String id) {
        return l.items().getOrDefault(id, 0L);
    }

    @Test
    void aBlockTheTableLacksIsLookedUpInTheOtherTables() {
        MaterialList l = materials("1.21.4", "minecraft:firefly_bush", 2, "minecraft:leaf_litter[segment_amount=3]", 1);
        assertEquals(2, item(l, "minecraft:firefly_bush"));
        assertEquals(3, item(l, "minecraft:leaf_litter"));
        assertTrue(l.unknown().isEmpty(), l.unknown().toString());
        assertEquals(64, BlockItems.forVersion("1.21.4").maxStack("minecraft:firefly_bush"));
    }

    @Test
    void doorsAndTallPlantsCountTheLowerHalf() {
        for (String v : BlockItems.VERSIONS) {
            MaterialList l = materials(v, "minecraft:oak_door[half=lower,facing=north,hinge=left,open=false,powered=false]", 3,
                "minecraft:oak_door[half=upper,facing=north,hinge=left,open=false,powered=false]", 3,
                "minecraft:sunflower[half=lower]", 2, "minecraft:sunflower[half=upper]", 2,
                "minecraft:tall_seagrass[half=lower]", 1, "minecraft:tall_seagrass[half=upper]", 1);
            assertEquals(3, item(l, "minecraft:oak_door"), v);
            assertEquals(2, item(l, "minecraft:sunflower"), v);
            assertEquals(1, item(l, "minecraft:seagrass"), v);
        }
    }

    @Test
    void bedsCountTheHead() {
        MaterialList l = materials("1.21.11", "minecraft:red_bed[part=head,facing=east,occupied=false]", 4, "minecraft:red_bed[part=foot,facing=east,occupied=false]", 4);
        assertEquals(Map.of("minecraft:red_bed", 4L), l.items());
    }

    @Test
    void doubleSlabsCountTwoAndWaterloggingIsIgnored() {
        MaterialList l = materials("1.21.4", "minecraft:oak_slab[type=double,waterlogged=false]", 3, "minecraft:oak_slab[type=bottom,waterlogged=true]", 1,
            "minecraft:oak_slab[type=top,waterlogged=false]", 2);
        assertEquals(Map.of("minecraft:oak_slab", 9L), l.items());
        assertTrue(l.fluids().isEmpty());
    }

    @Test
    void amountsCandlesPicklesSnow() {
        MaterialList l = materials("1.21.11", "minecraft:candle[candles=3,lit=false,waterlogged=false]", 2, "minecraft:red_candle[candles=1,lit=true,waterlogged=false]", 5,
            "minecraft:sea_pickle[pickles=4,waterlogged=true]", 1, "minecraft:snow[layers=3]", 2, "minecraft:turtle_egg[eggs=2,hatch=0]", 1,
            "minecraft:pink_petals[flower_amount=4,facing=north]", 1, "minecraft:leaf_litter[segment_amount=2,facing=north]", 3);
        assertEquals(6, item(l, "minecraft:candle"));
        assertEquals(5, item(l, "minecraft:red_candle"));
        assertEquals(4, item(l, "minecraft:sea_pickle"));
        assertEquals(6, item(l, "minecraft:snow"));
        assertEquals(2, item(l, "minecraft:turtle_egg"));
        assertEquals(4, item(l, "minecraft:pink_petals"));
        assertEquals(6, item(l, "minecraft:leaf_litter"));
        // leaf litter does not exist in 1.21.4: it is found in the 1.21.11 table instead of being unknown
        MaterialList old = materials("1.21.4", "minecraft:leaf_litter[segment_amount=2,facing=north]", 3);
        assertEquals(6, item(old, "minecraft:leaf_litter"));
        assertTrue(old.unknown().isEmpty());
    }

    @Test
    void pottedPlantsAndCandleCakes() {
        MaterialList l = materials("1.21.4", "minecraft:potted_poppy", 2, "minecraft:potted_bamboo", 1, "minecraft:flower_pot", 1,
            "minecraft:white_candle_cake[lit=false]", 2, "minecraft:candle_cake[lit=true]", 1);
        assertEquals(4, item(l, "minecraft:flower_pot"));
        assertEquals(2, item(l, "minecraft:poppy"));
        assertEquals(1, item(l, "minecraft:bamboo"));
        assertEquals(3, item(l, "minecraft:cake"));
        assertEquals(2, item(l, "minecraft:white_candle"));
        assertEquals(1, item(l, "minecraft:candle"));
    }

    @Test
    void cropsAndPlantsMapToTheirSeeds() {
        MaterialList l = materials("1.21.11", "minecraft:wheat[age=7]", 10, "minecraft:carrots[age=3]", 2, "minecraft:attached_melon_stem[facing=north]", 1,
            "minecraft:pumpkin_stem[age=7]", 1, "minecraft:kelp_plant", 4, "minecraft:kelp[age=3]", 1, "minecraft:cave_vines_plant[berries=true]", 2,
            "minecraft:redstone_wire[power=0,north=side,south=side,east=none,west=none]", 3, "minecraft:tripwire[attached=false,disarmed=false,east=false,north=false,powered=false,south=false,west=false]", 2,
            "minecraft:wall_torch[facing=north]", 1, "minecraft:glow_lichen[north=true,up=true,down=false,east=false,south=false,west=false,waterlogged=false]", 2);
        assertEquals(10, item(l, "minecraft:wheat_seeds"));
        assertEquals(2, item(l, "minecraft:carrot"));
        assertEquals(1, item(l, "minecraft:melon_seeds"));
        assertEquals(1, item(l, "minecraft:pumpkin_seeds"));
        assertEquals(5, item(l, "minecraft:kelp"));
        assertEquals(2, item(l, "minecraft:glow_berries"));
        assertEquals(3, item(l, "minecraft:redstone"));
        assertEquals(2, item(l, "minecraft:string"));
        assertEquals(1, item(l, "minecraft:torch"));
        assertEquals(2, item(l, "minecraft:glow_lichen")); // multiface: one per block
        assertTrue(l.unknown().isEmpty());
    }

    @Test
    void onlyFluidSourcesBecomeBuckets() {
        MaterialList l = materials("1.21.4", "minecraft:water[level=0]", 5, "minecraft:water[level=3]", 4, "minecraft:water[level=8]", 2,
            "minecraft:lava[level=0]", 1, "minecraft:lava[level=6]", 9, "minecraft:bubble_column[drag=true]", 3);
        assertEquals(Map.of("minecraft:water_bucket", 5L, "minecraft:lava_bucket", 1L), l.fluids());
        assertTrue(l.items().isEmpty());
        assertTrue(l.unknown().isEmpty());
    }

    @Test
    void airFireAndPortalsAreIgnored() {
        MaterialList l = materials("1.21.11", "minecraft:air", 100, "minecraft:cave_air", 5, "minecraft:void_air", 1, "minecraft:fire[age=0]", 2,
            "minecraft:nether_portal[axis=x]", 6, "minecraft:piston_head[facing=up,short=false,type=normal]", 1, "minecraft:stone", 1);
        assertEquals(Map.of("minecraft:stone", 1L), l.items());
        assertTrue(l.unknown().isEmpty());
    }

    @Test
    void aliasesForRenamedBlocks() {
        MaterialList l = materials("1.21.11", "minecraft:grass", 3, "minecraft:grass_path", 2, "minecraft:chain[axis=y]", 1);
        assertEquals(3, item(l, "minecraft:short_grass"));
        assertEquals(2, item(l, "minecraft:dirt_path"));
        assertEquals(1, item(l, "minecraft:iron_chain"));
        MaterialList old = materials("1.21.4", "minecraft:iron_chain[axis=y]", 1, "minecraft:chain[axis=x]", 1);
        assertEquals(2, item(old, "minecraft:chain"));
        assertTrue(l.unknown().isEmpty());
        assertTrue(old.unknown().isEmpty());
    }

    @Test
    void unknownIdsAreListedNotGuessed() {
        MaterialList l = materials("1.21.11", "minecraft:not_a_block", 7, "somemod:machine[facing=up]", 2, "minecraft:stone", 1);
        assertEquals(Map.of("minecraft:not_a_block", 7L, "somemod:machine", 2L), l.unknown());
        assertEquals(Map.of("minecraft:stone", 1L), l.items());
        Map<String, Long> many = new HashMap<>();
        for (int i = 0; i < 600; i++) many.put("mod:block_" + i, 2L);
        MaterialList capped = BlockItems.latest().materials(schematic(many));
        assertEquals(BlockItems.MAX_UNKNOWN, capped.unknown().size());
        assertEquals(200, capped.unknownOmitted());
    }

    @Test
    void containerItemsArePassedThrough() {
        Schematic s = new Schematic(Schematic.Format.LITEMATIC, 6, 4189, 1, 1, 1, 1, 1, 1, Map.of(BlockState.parse("minecraft:chest[facing=north,type=single]"), 1L),
            Map.of("minecraft:diamond", 9L));
        MaterialList l = BlockItems.forVersion("1.21.4").materials(s);
        assertEquals(Map.of("minecraft:chest", 1L), l.items());
        assertEquals(Map.of("minecraft:diamond", 9L), l.containerItems());
        assertEquals("1.21.4", l.tableVersion());
    }

    @Test
    void versionFallback() {
        assertEquals("1.21.4", BlockItems.forVersion("1.21.4").version());
        assertEquals("1.21.11", BlockItems.forVersion("1.21.8").version()); // the oldest table not older than it
        assertEquals("1.21.11", BlockItems.forVersion("1.21.11").version());
        assertEquals("1.21.11", BlockItems.forVersion("1.22").version());
        assertEquals("1.21.4", BlockItems.forVersion("1.20.1").version()); // older than every table: the oldest
        assertEquals("1.21.4", BlockItems.forVersion("garbage").version()); // parses as 0.0.0
        assertEquals(4189, BlockItems.forVersion("1.21.4").dataVersion());
        assertEquals(4671, BlockItems.forVersion("1.21.11").dataVersion());
        assertEquals("1.21.4", BlockItems.forDataVersion(4189).version());
        assertEquals("1.21.11", BlockItems.forDataVersion(4670).version());
        assertEquals("1.21.11", BlockItems.forDataVersion(4300).version()); // 1.21.5-1.21.10 files: the table that has their blocks
        assertEquals("1.21.4", BlockItems.forDataVersion(4000).version());
        assertEquals("1.21.11", BlockItems.forDataVersion(4671).version());
        assertEquals("1.21.11", BlockItems.forDataVersion(9999).version());
        assertEquals("1.21.4", BlockItems.forDataVersion(0).version());
        assertEquals(1, BlockItems.latest().maxStack("minecraft:red_bed"));
        assertEquals(16, BlockItems.latest().maxStack("minecraft:ender_pearl"));
        assertEquals(64, BlockItems.latest().maxStack("minecraft:stone"));
    }

    @Test
    void everyTableBlockHasAnItemOrARule() {
        Set<String> rules = Set.of("lower", "head", "slab", "amount", "pot", "with", "fluid", "ignore");
        for (String v : BlockItems.VERSIONS) {
            BlockItems t = BlockItems.forVersion(v);
            assertEquals(v, t.version());
            assertTrue(t.blocks().size() > 1000, v + " has " + t.blocks().size() + " blocks");
            for (var e : t.blocks().entrySet()) {
                BlockItems.Entry en = e.getValue();
                String where = v + " " + e.getKey();
                assertTrue(en.item() != null || en.rule() != null, where);
                if (en.item() != null) assertTrue(t.maxStack(en.item()) > 0 && isItem(t, en.item()), where + " item " + en.item());
                if (en.rule() == null) continue;
                String kind = en.rule().contains(":") ? en.rule().substring(0, en.rule().indexOf(':')) : en.rule();
                assertTrue(rules.contains(kind), where + " rule " + en.rule());
                if (kind.equals("pot") || kind.equals("with")) assertTrue(isItem(t, en.rule().substring(kind.length() + 1)), where);
                if (kind.equals("ignore") || kind.equals("fluid")) assertNull(en.item(), where);
                else assertNotNull(en.item(), where);
            }
            assertEquals("fluid", t.entry("minecraft:water").rule());
            assertEquals("ignore", t.entry("minecraft:air").rule());
            assertFalse(t.blocks().containsKey("minecraft:grass"));
        }
    }

    private static boolean isItem(BlockItems t, String id) {
        return t.knowsItem(id);
    }

    @Test
    void endToEndLitematicToMaterials() throws Exception {
        var palette = compounds(state("minecraft:air"), state("minecraft:oak_door", "half", "lower"), state("minecraft:oak_door", "half", "upper"),
            state("minecraft:water", "level", "0"), state("minecraft:stone_slab", "type", "double", "waterlogged", "false"));
        int[] idx = { 1, 2, 3, 4, 4, 0, 0, 3 };
        byte[] file = NbtWriter.gzip(NbtWriter.write(NbtWriter.litematic(6, compound("r",
            NbtWriter.region(0, 0, 0, 2, 2, 2, palette, NbtWriter.packLitematica(idx, 3))))));
        Schematic s = SchematicReader.read(file, Duration.ofSeconds(10));
        MaterialList l = BlockItems.forDataVersion(s.dataVersion()).materials(s);
        assertEquals(new TreeMap<>(Map.of("minecraft:oak_door", 1L, "minecraft:stone_slab", 4L)), l.items());
        assertEquals(Map.of("minecraft:water_bucket", 2L), l.fluids());
    }
}
