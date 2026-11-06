import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Dumps vanilla creative tab data using the same rules as the addon's StashIndex.tabOf. Args: mcVersion outFile. */
public class GenCreativeTabs {
    static final List<String> TABS = List.of("building_blocks", "colored_blocks", "natural_blocks", "functional_blocks", "redstone_blocks",
        "tools_and_utilities", "combat", "food_and_drinks", "ingredients", "spawn_eggs", "op_blocks");

    /**
     * Registries as a real server/client holds them: built-in registries, worldgen/data registries loaded from the vanilla
     * data pack, and all tags bound. VanillaRegistries.createLookup() alone is not enough: the painting variants in the
     * Decorations part of the tabs read the placeable tag and fail with "Tags not bound".
     */
    static HolderLookup.Provider loadRegistries() throws Exception {
        var rm = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource()));
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

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider lookup = loadRegistries();
        CreativeModeTabs.tryRebuildTabContents(FeatureFlags.DEFAULT_FLAGS, false, lookup);

        Map<String, CreativeModeTab> byPath = new HashMap<>();
        for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            if (tab.getType() != CreativeModeTab.Type.CATEGORY) continue;
            var id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            if (id != null) byPath.put(id.getPath(), tab);
        }
        Map<String, Object[]> items = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String path : TABS) {
            CreativeModeTab tab = byPath.get(path);
            if (tab == null) { System.err.println("missing tab " + path); continue; }
            int n = 0, added = 0;
            for (ItemStack shown : tab.getDisplayItems()) {
                String id = BuiltInRegistries.ITEM.getKey(shown.getItem()).toString();
                if (!items.containsKey(id)) { items.put(id, new Object[] { path, n }); added++; }
                n++;
            }
            counts.put(path, added);
            System.err.println(path + ": display=" + n + " new=" + added);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"version\":\"").append(args[0]).append("\",\"tabs\":[");
        boolean first = true;
        for (String t : TABS) { if (!first) sb.append(','); first = false; sb.append('"').append(t).append('"'); }
        sb.append("],\"items\":{\n");
        first = true;
        for (var e : items.entrySet()) {
            if (!first) sb.append(",\n"); first = false;
            sb.append('"').append(e.getKey()).append("\":[\"").append(e.getValue()[0]).append("\",").append(e.getValue()[1]).append(']');
        }
        sb.append("\n}}\n");
        Files.writeString(Path.of(args[1]), sb.toString(), StandardCharsets.UTF_8);
        System.err.println("items=" + items.size() + " counts=" + counts);
    }
}
