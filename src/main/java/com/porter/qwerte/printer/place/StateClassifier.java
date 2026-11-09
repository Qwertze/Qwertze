package com.porter.qwerte.printer.place;

import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.DirtPathBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.FungusBlock;
import net.minecraft.world.level.block.GlazedTerracottaBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.RootsBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.StructureBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Runtime: a {@link BlockState} to a pure {@link StateSpec}. Decided by block class first (all of these classes exist
 * under the same name in 1.21.4 and 1.21.11), then by {@link PropertyRules}. A block with an orientation property that
 * no rule covers is skipped and listed rather than guessed.
 */
public final class StateClassifier {
    private StateClassifier() {}

    public static String idOf(Block b) {
        return BuiltInRegistries.BLOCK.getKey(b).toString();
    }

    /** Every property of the state as name to value name, sorted. */
    public static Map<String, String> props(BlockState s) {
        Map<String, String> m = new TreeMap<>();
        for (Property<?> p : s.getProperties()) m.put(p.getName(), valueName(s, p));
        return m;
    }

    private static <T extends Comparable<T>> String valueName(BlockState s, Property<T> p) {
        return p.getName(s.getValue(p));
    }

    public static StateSpec classify(BlockState s) {
        Block b = s.getBlock();
        String id = idOf(b);
        Map<String, String> props = props(s);
        String skip = skipReason(b);
        if (skip != null) return StateSpec.skipped(id, props, skip);
        StateKind k = kindOf(b, props);
        if (k == StateKind.SKIP) return StateSpec.skipped(id, props, "no placement recipe for " + id + " in this version of the printer");
        return StateSpec.of(id, k, props, b instanceof FallingBlock);
    }

    private static String skipReason(Block b) {
        if (b instanceof LiquidBlock) return "fluids cannot be placed";
        if (b instanceof BubbleColumnBlock) return "bubble columns cannot be placed";
        if (b instanceof BaseFireBlock) return "fire cannot be placed";
        if (b instanceof NetherPortalBlock || b instanceof EndPortalBlock || b instanceof EndGatewayBlock) return "portals cannot be placed";
        if (b instanceof VineBlock || b instanceof MultifaceBlock) return "multiface blocks (vine, glow lichen, sculk vein) are not supported";
        if (b instanceof PointedDripstoneBlock) return "pointed dripstone thickness is not supported";
        if (b instanceof CocoaBlock) return "cocoa pods are not supported";
        if (b instanceof AbstractCauldronBlock) return "cauldrons are not supported";
        if (b instanceof ComposterBlock) return "composters are not supported";
        if (b instanceof RespawnAnchorBlock) return "respawn anchor charges are not supported";
        if (b instanceof CakeBlock) return null; // bites are handled by PropertyRules
        if (b instanceof FarmBlock || b instanceof DirtPathBlock) return "farmland and dirt paths are made by tools, not placed";
        if (b instanceof CommandBlock || b instanceof StructureBlock || b instanceof JigsawBlock) return "creative-only blocks are not supported";
        if (b instanceof PistonHeadBlock || b instanceof MovingPistonBlock) return "piston heads and moving pistons belong to an extended piston";
        if (b instanceof CeilingHangingSignBlock || b instanceof WallHangingSignBlock) return "hanging signs are not supported in v1";
        if (b instanceof WaterlilyBlock) return "lily pads need a click on water";
        return null;
    }

    private static StateKind kindOf(Block b, Map<String, String> props) {
        // two-part and special placement first
        if (b instanceof BedBlock) return StateKind.BED;
        if (b instanceof DoorBlock) return StateKind.DOOR;
        if (b instanceof TrapDoorBlock) return StateKind.TRAPDOOR;
        if (b instanceof FenceGateBlock) return StateKind.FENCE_GATE;
        if (b instanceof StairBlock) return StateKind.STAIRS;
        if (b instanceof SlabBlock) return StateKind.SLAB;
        if (b instanceof ChestBlock) return StateKind.CHEST;
        if (b instanceof HopperBlock) return StateKind.HOPPER;
        if (b instanceof BellBlock) return StateKind.BELL;
        if (b instanceof RepeaterBlock) return StateKind.REPEATER;
        if (b instanceof ComparatorBlock) return StateKind.COMPARATOR;
        if (b instanceof NoteBlock) return StateKind.NOTE;
        if (b instanceof DaylightDetectorBlock) return StateKind.DAYLIGHT;
        if (b instanceof CandleBlock || b instanceof SeaPickleBlock || b instanceof TurtleEggBlock || b instanceof SnowLayerBlock) return StateKind.COUNT;
        // facing from the look
        if (b instanceof AnvilBlock) return StateKind.HFACING_CW;
        if (b instanceof CampfireBlock || b instanceof DecoratedPotBlock) return StateKind.HFACING_LOOK;
        if (b instanceof AbstractFurnaceBlock || b instanceof CarvedPumpkinBlock || b instanceof BeehiveBlock || b instanceof GlazedTerracottaBlock
            || b instanceof ChiseledBookShelfBlock || b instanceof EnderChestBlock || b instanceof LoomBlock || b instanceof StonecutterBlock
            || b instanceof LecternBlock || b instanceof EndPortalFrameBlock) return StateKind.HFACING;
        if (b instanceof PistonBaseBlock || b instanceof DispenserBlock || b instanceof BarrelBlock) return StateKind.FACING6_LOOK;
        if (b instanceof ObserverBlock) return StateKind.FACING6_LOOK_SAME;
        // facing from the clicked face
        if (b instanceof EndRodBlock || b instanceof LightningRodBlock || b instanceof AmethystClusterBlock || b instanceof ShulkerBoxBlock) return StateKind.FACING6_FACE;
        if (b instanceof FaceAttachedHorizontalDirectionalBlock) return StateKind.ATTACH_FACE;
        if (b instanceof LadderBlock || b instanceof WallTorchBlock || b instanceof WallSignBlock || b instanceof WallBannerBlock || b instanceof WallSkullBlock) {
            return StateKind.WALL_OR_STANDING;
        }
        if (b instanceof StandingSignBlock || b instanceof BannerBlock || b instanceof SkullBlock) return StateKind.ROTATION16;
        if (b instanceof LanternBlock) return StateKind.HANGING;
        if (b instanceof RotatedPillarBlock || b instanceof ChainBlock) return StateKind.AXIS;
        // default state, needs a block below
        if (b instanceof CarpetBlock || b instanceof BasePressurePlateBlock || b instanceof BaseRailBlock || b instanceof RedStoneWireBlock
            || b instanceof BaseTorchBlock || b instanceof DoublePlantBlock || b instanceof FlowerBlock || b instanceof SaplingBlock
            || b instanceof CropBlock || b instanceof TallGrassBlock || b instanceof MushroomBlock || b instanceof NetherWartBlock
            || b instanceof SweetBerryBushBlock || b instanceof FungusBlock || b instanceof RootsBlock || b instanceof StemBlock
            || b instanceof SugarCaneBlock || b instanceof CactusBlock) return StateKind.SUPPORTED_FLOOR;
        // anything with an orientation we have no recipe for is listed, not guessed
        for (String k : new String[] { "facing", "axis", "face", "rotation", "orientation", "attachment", "hanging", "part", "half", "type" }) {
            if (props.containsKey(k)) return StateKind.SKIP;
        }
        return StateKind.FULL;
    }
}
