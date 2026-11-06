# Creative tab table generator

Generates `src/main/resources/creative-tabs/<mc>.json` by running the real game code headlessly
(`SharedConstants`, `Bootstrap`, the vanilla data pack for registries and tags, then
`CreativeModeTabs.tryRebuildTabContents(FeatureFlags.DEFAULT_FLAGS, false, registries)`).
Rules match the addon's `StashIndex.tabOf`: CATEGORY tabs only, vanilla tab order, first tab wins, order = first index
in that tab's `getDisplayItems()`, `hasPermissions=false` (so `op_blocks` is empty).

Needs: JDK 21+ on PATH, Git Bash, and the addon's Gradle/Loom caches populated (build the addon once for each version:
`gradlew build` and `gradlew build -Pmc=1.21.4`). It reads the Mojang-mapped game jar from the addon repo's
`.gradle/loom-cache/minecraftMaven/` and libraries from `~/.gradle/caches/modules-2/`.

    cd stash-manager-server/dev/creative-tabs
    ./run.sh 1.21.4
    ./run.sh 1.21.11

`run.sh` writes `out/` (compiled class, classpath argfiles; safe to delete) and the JSON. Per-tab counts go to stderr.
For another version, add its Mojang library versions (DFU, authlib, logging) to the `case` line in `run.sh`.
Note: `VanillaRegistries.createLookup()` alone fails ("Tags not bound" while building the painting variants), so
`GenCreativeTabs.loadRegistries()` loads the registries and tags from the vanilla server data pack like a real server.
