import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Dumps vanilla shaped/shapeless crafting recipes (plain results, no remainder ingredients) plus an other-station map. Args: mcVersion outFile. */
public class GenRecipes {
    static HolderLookup.Provider loadRegistries(MultiPackResourceManager rm) throws Exception {
        LayeredRegistryAccess<RegistryLayer> layered = RegistryLayer.createRegistryAccess();
        var staticAccess = layered.getAccessForLoading(RegistryLayer.WORLDGEN);
        var pending1 = TagLoader.loadTagsForExistingRegistries(rm, staticAccess);
        var lookups = TagLoader.buildUpdatedLookups(staticAccess, pending1);
        var worldgen = RegistryDataLoader.load(rm, lookups, RegistryDataLoader.WORLDGEN_REGISTRIES);
        layered = layered.replaceFrom(RegistryLayer.WORLDGEN, worldgen);
        var pending2 = TagLoader.loadTagsForExistingRegistries(rm, layered.getAccessForLoading(RegistryLayer.RELOADABLE));
        pending1.forEach(Registry.PendingTags::apply);
        pending2.forEach(Registry.PendingTags::apply);
        return layered.compositeAccess();
    }

    static String id(Item i) { return BuiltInRegistries.ITEM.getKey(i).toString(); }

    /** Alternatives of an ingredient as sorted item ids; null if any alternative leaves a crafting remainder. */
    static List<String> alts(Ingredient ing, Set<String> remainderItems) {
        List<String> out = new ArrayList<>();
        ing.items().forEach(h -> out.add(id(h.value())));
        Collections.sort(out);
        for (String s : out) if (remainderItems.contains(s)) return null;
        return out;
    }

    static String jsonList(List<String> l) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(','); sb.append('"').append(l.get(i)).append('"'); }
        return sb.append(']').toString();
    }

    static String norm(String s) { return s.contains(":") ? s : "minecraft:" + s; }

    static String resultId(JsonObject o) {
        JsonElement r = o.get("result");
        if (r == null) return null;
        if (r.isJsonPrimitive()) return norm(r.getAsString());
        JsonObject ro = r.getAsJsonObject();
        if (ro.has("id")) return norm(ro.get("id").getAsString());
        if (ro.has("item")) return norm(ro.get("item").getAsString());
        return null;
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var rm = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource()));
        HolderLookup.Provider lookup = loadRegistries(rm);
        var ops = RegistryOps.create(JsonOps.INSTANCE, lookup);

        Set<String> remainderItems = new HashSet<>();
        Set<String> itemNames = new HashSet<>();
        for (Item it : BuiltInRegistries.ITEM) {
            itemNames.add(id(it));
            if (!it.getCraftingRemainder().isEmpty()) remainderItems.add(id(it));
        }
        System.err.println("items with crafting remainder: " + new TreeSet<>(remainderItems));

        var res = new TreeMap<>(rm.listResources("recipe", l -> l.getPath().endsWith(".json")));
        List<String> recipeLines = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        Set<String> included = new HashSet<>();
        Map<String, String> otherAll = new TreeMap<>();
        Map<String, String> craftingExcluded = new TreeMap<>();
        List<String> skipped = new ArrayList<>();
        List<String> priority = List.of("smelting", "blasting", "smoking", "campfire_cooking", "stonecutting", "smithing", "transmute", "special");
        Map<String, Set<String>> stations = new HashMap<>();

        for (var e : res.entrySet()) {
            String rid = e.getKey().getNamespace() + ":" + e.getKey().getPath().substring("recipe/".length(), e.getKey().getPath().length() - 5);
            JsonObject json;
            try (Reader r = e.getValue().openAsReader()) { json = JsonParser.parseReader(r).getAsJsonObject(); }
            String type = norm(json.get("type").getAsString());
            String path = type.substring(type.indexOf(':') + 1);
            String station = null;
            String res0 = resultId(json);
            if (path.equals("smelting") || path.equals("blasting") || path.equals("smoking") || path.equals("campfire_cooking") || path.equals("stonecutting")) station = path;
            else if (path.startsWith("smithing")) station = "smithing";
            else if (path.equals("crafting_transmute")) station = "transmute";
            else if (path.startsWith("crafting_special_") || path.equals("crafting_decorated_pot")) {
                station = "special";
                if (res0 == null) {
                    String suffix = path.equals("crafting_decorated_pot") ? "decorated_pot" : path.substring("crafting_special_".length());
                    if (itemNames.contains("minecraft:" + suffix)) res0 = "minecraft:" + suffix;
                    else skipped.add("special without item mapping: " + rid);
                }
            }
            if (station != null) {
                if (res0 != null) stations.computeIfAbsent(res0, k -> new HashSet<>()).add(station);
                continue;
            }
            if (!path.equals("crafting_shaped") && !path.equals("crafting_shapeless")) { skipped.add("unhandled type " + type + " " + rid); continue; }

            final String frid = rid;
            Recipe<?> recipe = Recipe.CODEC.parse(ops, json).getOrThrow(m -> new RuntimeException(frid + ": " + m));
            ItemStack result = ((net.minecraft.world.item.crafting.CraftingRecipe) recipe).assemble((CraftingInput) null, lookup);
            String resItem = id(result.getItem());
            if (!result.getComponentsPatch().isEmpty()) { craftingExcluded.putIfAbsent(resItem, "components"); counts.merge("excluded_components", 1, Integer::sum); continue; }
            StringBuilder sb = new StringBuilder();
            boolean bad = false;
            if (recipe instanceof ShapedRecipe sr) {
                List<String> cells = new ArrayList<>();
                for (Optional<Ingredient> oi : sr.getIngredients()) {
                    if (oi.isEmpty()) { cells.add("null"); continue; }
                    List<String> a = alts(oi.get(), remainderItems);
                    if (a == null || a.isEmpty()) { bad = true; break; }
                    cells.add(jsonList(a));
                }
                if (!bad) sb.append("{\"id\":\"").append(rid).append("\",\"type\":\"shaped\",\"w\":").append(sr.getWidth()).append(",\"h\":").append(sr.getHeight())
                    .append(",\"cells\":[").append(String.join(",", cells)).append("]");
            } else if (recipe instanceof ShapelessRecipe sl) {
                List<String> ings = new ArrayList<>();
                for (Ingredient ing : sl.placementInfo().ingredients()) {
                    List<String> a = alts(ing, remainderItems);
                    if (a == null || a.isEmpty()) { bad = true; break; }
                    ings.add(jsonList(a));
                }
                if (!bad) sb.append("{\"id\":\"").append(rid).append("\",\"type\":\"shapeless\",\"ingredients\":[").append(String.join(",", ings)).append("]");
            } else { skipped.add("unexpected class " + recipe.getClass() + " " + rid); continue; }
            if (bad) { craftingExcluded.putIfAbsent(resItem, "remainder"); counts.merge("excluded_remainder", 1, Integer::sum); continue; }
            sb.append(",\"result\":\"").append(resItem).append("\",\"count\":").append(result.getCount()).append('}');
            recipeLines.add(sb.toString());
            included.add(resItem);
            counts.merge(path, 1, Integer::sum);
        }
        for (var e : new TreeMap<>(stations).entrySet()) {
            if (included.contains(e.getKey())) continue;
            for (String p : priority) if (e.getValue().contains(p)) { otherAll.put(e.getKey(), p); break; }
        }
        StringBuilder out = new StringBuilder();
        out.append("{\"version\":\"").append(args[0]).append("\",\"recipes\":[\n");
        out.append(String.join(",\n", recipeLines));
        out.append("\n],\"otherStations\":{\n");
        boolean first = true;
        for (var e : otherAll.entrySet()) {
            if (!first) out.append(",\n");
            first = false;
            out.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
        }
        out.append("\n}}\n");
        Files.writeString(Path.of(args[1]), out.toString(), StandardCharsets.UTF_8);
        System.err.println("counts=" + counts + " recipes=" + recipeLines.size() + " otherStations=" + otherAll.size());
        System.err.println("skipped=" + skipped);
        var ce = new TreeMap<>(craftingExcluded);
        ce.keySet().removeAll(included);
        System.err.println("crafting-excluded items with no included recipe:");
        for (var e : ce.entrySet())
            System.err.println("  " + e.getKey() + " " + e.getValue() + (otherAll.containsKey(e.getKey()) ? " (other=" + otherAll.get(e.getKey()) + ")" : ""));
    }
}
