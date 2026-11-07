# Block -> item table generator (Build Planner)

Generates `src/main/resources/block-items/<mc>.json`, which the Build Planner (`planner/BlockItems`) uses to turn a
schematic's block states into survival materials. It runs the real game code headlessly (`SharedConstants`,
`Bootstrap`) and reads every block in `BuiltInRegistries.BLOCK`.

Output: `{version, dataVersion, blocks:{blockId:{item?, rule?}}, maxStack:{itemId:n}}`

- `item` = `block.asItem()`, omitted when air (potted plants get `minecraft:flower_pot`, candle cakes `minecraft:cake`,
  and `map` entries from `rules.json` supply the rest).
- `rule`, from the block class or its properties:
  - `lower`: `DoorBlock`, `DoublePlantBlock`, or any block with `half=lower|upper` (count only the lower half)
  - `head`: `BedBlock` (count only `part=head`)
  - `slab`: `SlabBlock` (`type=double` counts 2)
  - `amount:<prop>`: integer property `candles`, `pickles`, `eggs`, `flower_amount`, `segment_amount` or `layers`
    is the item count (candles, sea pickles, turtle eggs, pink petals/wildflowers, leaf litter, snow)
  - `pot:<plantItem>`: `FlowerPotBlock` with a plant (flower pot + the plant)
  - `with:<item>`: candle cakes (cake + the matching candle)
  - `ignore` / `fluid`: from `rules.json`
  - Multiface blocks (glow lichen, sculk vein, vines, resin clump) have no rule: 1 item per block, whatever the faces.
- `maxStack`: `Item.getDefaultMaxStackSize()` for every item.

`rules.json` is hand-made. It classifies every block whose `asItem()` is air: `ignore` (never counted), `fluid`
(water, lava; source blocks become buckets) or `map` (block -> item to count, e.g. `kelp_plant` -> `kelp`). The run
**fails and writes nothing** if an air-item block is in none of these and has no class rule; add it to `rules.json`
and rerun. Entries for blocks that do have an item are ignored with a note.

Needs: JDK 21+ on PATH, Git Bash, and the addon's Gradle/Loom caches populated (build the addon once for each version:
`gradlew build` and `gradlew build -Pmc=1.21.4`). Like `../creative-tabs`, it reads the Mojang-mapped game jar from the
addon repo's `.gradle/loom-cache/minecraftMaven/` and libraries from `~/.gradle/caches/modules-2/`.

    cd stash-manager-server/dev/block-items
    ./run.sh 1.21.4
    ./run.sh 1.21.11

`run.sh` writes `out/` (compiled class, classpath argfiles, the game's `logs/`; safe to delete) and the JSON. The game
redirects stderr into its logger, so the summary line (block count, rule counts) and any `ERROR:` lines are printed
with a log prefix and also land in `out/logs/latest.log`:

    grep -E "ERROR|note|blocks=" out/logs/latest.log

For another version, add its Mojang library versions (DFU, authlib, logging) to the `case` line in `run.sh`, run it,
and add the file name to `BlockItems.VERSIONS` in the server.
