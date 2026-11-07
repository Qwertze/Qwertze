# Map palette generator (live viewer)

Generates `src/main/resources/web/map-palette.json`, the 256-entry vanilla map palette the live viewer uses to draw
the bot's packed map bytes. It reads the game's own `net.minecraft.world.level.material.MapColor` from each version's
Mojang-mapped jar (no Bootstrap needed): for every id 0-63 and every `MapColor.Brightness`,
`rgb = channel * brightness.modifier / 255` at index `id * 4 + brightness.id`, cross-checked against the game's
`getPackedId` and `getColorFromPackedId`. Ids 0-3 (NONE) and ids the version doesn't define are `-1` (transparent).
The run fails if the versions' palettes differ.

Output: `{"v":1,"mc":["1.21.4","1.21.11"],"rgb":[256 ints]}` (`0xRRGGBB`, or -1).

Needs: JDK 21+ on PATH, Git Bash, and the addon's Gradle/Loom caches populated (build the addon once for each version:
`gradlew build` and `gradlew build -Pmc=1.21.4`), like `../block-items` and `../creative-tabs`.

    cd stash-manager-server/dev/map-colors
    ./run.sh                 # 1.21.4 and 1.21.11
    ./run.sh 1.21.4 1.21.11  # the same, explicitly

`run.sh` writes `out/` (compiled classes and one dump per version; safe to delete) and the JSON. For another version,
add its Mojang library versions (DFU, authlib, logging) to the `case` line in `run.sh`.
