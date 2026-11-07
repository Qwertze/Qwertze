import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Dumps the block -> item table the Stash Manager Build Planner uses to turn a schematic's block states into survival
 * materials. Args: mcVersion rulesJson outFile.
 *
 * <p>Per block: {@code item} = {@code block.asItem()} (omitted when air), {@code rule} from the block class/properties
 * (lower, head, slab, amount:prop, pot:plant, with:item). Blocks whose item is air must be classified in rules.json
 * (ignore / fluid / map) or by class (potted plants, candle cakes); otherwise the run fails.
 */
public class GenBlockItems {
    /** Integer properties whose value is the number of items in the block. */
    static final List<String> AMOUNT_PROPS = List.of("candles", "pickles", "eggs", "flower_amount", "segment_amount", "layers");

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        String version = args[0];
        JsonObject rules = JsonParser.parseString(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8)).getAsJsonObject();
        Set<String> ignore = strings(rules.getAsJsonArray("ignore"));
        Set<String> fluid = strings(rules.getAsJsonArray("fluid"));
        Map<String, String> map = new TreeMap<>();
        for (var e : rules.getAsJsonObject("map").entrySet()) map.put(e.getKey(), e.getValue().getAsString());

        Map<String, Item> itemsById = new HashMap<>();
        for (Item it : BuiltInRegistries.ITEM) itemsById.put(BuiltInRegistries.ITEM.getKey(it).toString(), it);
        Map<String, Block> blocksById = new HashMap<>();
        for (Block b : BuiltInRegistries.BLOCK) blocksById.put(BuiltInRegistries.BLOCK.getKey(b).toString(), b);
        Set<String> blockIds = blocksById.keySet();

        List<String> errors = new ArrayList<>();
        for (String id : union(ignore, fluid, map.keySet())) {
            if (!blockIds.contains(id)) System.err.println("note: rules.json block not in " + version + ": " + id);
        }
        for (var e : map.entrySet()) {
            if (!itemsById.containsKey(e.getValue())) errors.add("rules.json map target is not an item: " + e.getKey() + " -> " + e.getValue());
        }

        JsonObject blocks = new JsonObject();
        Map<String, Integer> ruleCounts = new TreeMap<>();
        int withItem = 0;
        List<String> sortedIds = new ArrayList<>(blockIds);
        Collections.sort(sortedIds);
        for (String id : sortedIds) {
            Block block = blocksById.get(id);
            Item asItem = block.asItem();
            String item = asItem == Items.AIR ? null : BuiltInRegistries.ITEM.getKey(asItem).toString();
            List<String> found = new ArrayList<>();

            if (block instanceof BedBlock) found.add("head");
            if (block instanceof SlabBlock) found.add("slab");
            if (block instanceof DoorBlock || block instanceof DoublePlantBlock || hasValue(block, "half", "lower")) found.add("lower");
            for (Property<?> p : block.getStateDefinition().getProperties()) {
                if (p instanceof IntegerProperty && AMOUNT_PROPS.contains(p.getName())) found.add("amount:" + p.getName());
            }
            if (block instanceof FlowerPotBlock pot && pot.getPotted() != Blocks.AIR) {
                Item plant = pot.getPotted().asItem();
                if (plant == Items.AIR) errors.add("potted plant has no item: " + id);
                else found.add("pot:" + BuiltInRegistries.ITEM.getKey(plant));
                if (item == null) item = "minecraft:flower_pot";
            }
            if (block instanceof CandleCakeBlock) {
                String candle = id.substring(0, id.length() - "_cake".length());
                if (!itemsById.containsKey(candle)) errors.add("candle cake without candle item: " + id + " -> " + candle);
                else found.add("with:" + candle);
                if (item == null) item = "minecraft:cake";
            }

            if (item == null) {
                int hits = (ignore.contains(id) ? 1 : 0) + (fluid.contains(id) ? 1 : 0) + (map.containsKey(id) ? 1 : 0);
                if (hits > 1) errors.add("block in more than one rules.json section: " + id);
                if (ignore.contains(id)) { found.clear(); found.add("ignore"); }
                else if (fluid.contains(id)) { found.clear(); found.add("fluid"); }
                else if (map.containsKey(id)) item = map.get(id);
                else { errors.add("unclassified block with no item (add it to rules.json): " + id); continue; }
            } else if (ignore.contains(id) || fluid.contains(id) || map.containsKey(id)) {
                System.err.println("note: rules.json entry ignored, block has an item: " + id + " -> " + item);
            }
            if (found.size() > 1) { errors.add("block matches more than one rule: " + id + " " + found); continue; }

            JsonObject o = new JsonObject();
            if (item != null) { o.addProperty("item", item); withItem++; }
            if (!found.isEmpty()) {
                o.addProperty("rule", found.get(0));
                ruleCounts.merge(found.get(0).replaceAll(":.*", ""), 1, Integer::sum);
            }
            blocks.add(id, o);
        }

        JsonObject maxStack = new JsonObject();
        List<String> itemIds = new ArrayList<>(itemsById.keySet());
        Collections.sort(itemIds);
        for (String id : itemIds) {
            Item it = itemsById.get(id);
            if (it == Items.AIR) continue;
            maxStack.addProperty(id, it.getDefaultMaxStackSize());
        }

        if (!errors.isEmpty()) {
            errors.forEach(e -> System.err.println("ERROR: " + e));
            System.err.println(errors.size() + " error(s); nothing written");
            System.exit(1);
        }

        JsonObject out = new JsonObject();
        out.addProperty("version", version);
        out.addProperty("dataVersion", SharedConstants.WORLD_VERSION);
        out.add("blocks", blocks);
        out.add("maxStack", maxStack);
        // one entry per line keeps diffs between regenerations readable
        StringBuilder sb = new StringBuilder();
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        sb.append("{\"version\":").append(gson.toJson(version)).append(",\"dataVersion\":").append(SharedConstants.WORLD_VERSION).append(",\n\"blocks\":{\n");
        appendLines(sb, gson, blocks);
        sb.append("},\n\"maxStack\":{\n");
        appendLines(sb, gson, maxStack);
        sb.append("}}\n");
        Files.writeString(Path.of(args[2]), sb.toString(), StandardCharsets.UTF_8);
        System.err.println(version + ": dataVersion=" + SharedConstants.WORLD_VERSION + " blocks=" + blocks.size() + " withItem=" + withItem
            + " rules=" + ruleCounts + " items=" + maxStack.size());
    }

    static void appendLines(StringBuilder sb, Gson gson, JsonObject o) {
        boolean first = true;
        for (var e : o.entrySet()) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append(gson.toJson(e.getKey())).append(':').append(gson.toJson(e.getValue()));
        }
        sb.append('\n');
    }

    static boolean hasValue(Block block, String propName, String value) {
        for (Property<?> p : block.getStateDefinition().getProperties()) {
            if (p.getName().equals(propName) && valueNames(p).contains(value)) return true;
        }
        return false;
    }

    static <T extends Comparable<T>> List<String> valueNames(Property<T> p) {
        List<String> names = new ArrayList<>();
        for (T v : p.getPossibleValues()) names.add(p.getName(v));
        return names;
    }

    static Set<String> strings(JsonArray a) {
        Set<String> s = new TreeSet<>();
        for (JsonElement e : a) s.add(e.getAsString());
        return s;
    }

    @SafeVarargs
    static Set<String> union(Set<String>... sets) {
        Set<String> s = new TreeSet<>();
        for (Set<String> x : sets) s.addAll(x);
        return s;
    }
}
