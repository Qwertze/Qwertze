# Qwerte
A Meteor Addon For 2b2t Dedicated to Regrowing &amp; Creating Farmland

Qwerte adds eleven modules to [Meteor Client](https://meteorclient.com):

- **Auto Till** turns nearby dirt and grass into farmland, digs and fills water holes so the farmland stays hydrated,
  plants and bone meals crops, and restocks its own seeds. With [Baritone](https://github.com/dekrom/baritone)
  installed it can also walk to new land and to water by itself, so a whole field can be built hands-free.
- **Auto Dirt** covers the ground around you with dirt, so stone, sand or netherrack can be turned into farmland. It
  never places dirt on top of dirt, and restocks its own dirt. With Baritone installed it can also walk to uncovered
  ground by itself.
- **Stash Sorter** sorts the chests and barrels around you, either into tiers or by the signs on them. It repacks
  mixed shulkers, merges partial stacks, empties drop-off chests, builds saved kits, sends kit shulkers to their own chests and can be searched. Want it on a
  proxy bot instead?
  See [Stash Sorter for AquariusProxy](https://github.com/Qwertze/Stash-Sorter).
- **Stash Overlay** shows what each container in a saved stash mostly holds, as an item icon on the container.
- **Stash Manager** sends what's in the stash saved by Stash Sorter to a [Stash Manager server](stash-manager-server/README.md)
  on your network: a small website that shows the stash and takes orders for the bot to fill.
- **Order Notifications** plays a sound and shows a toast when one of your Stash Manager orders is delivered, comes up short, fails or is cancelled.
- **Reset Pearl** climbs out of a saved stasis chamber after you're pulled through it, throws a fresh pearl into the water
  and closes the cover again.
- **Messenger** is a small chat window over the game: a Global view of public chat you can type into, a conversation per player for whispers with history and emoji, and a picker to start a new one.
- **Stash Window** is a window to search your stash in game, and with a Stash Manager server, the server's pages too.
- **Container Tweaks** shows what is inside a shulker box when you hover it, and draws its most common item on it. With
  Smart Shulker or Smart Echest turned on, right-clicking a shulker box or ender chest places it, opens it and picks it up again.
- **Printer** builds a Litematica schematic (or a `.litematic` file) in the world, block by block, with each block's facing
  and state. It walks with Baritone, fetches the materials from your inventory, shulker boxes, listed containers and your
  ender chest, or orders what it runs short of from the stash bots (which can craft what their stash has the materials for), and lists what it had to skip. Litematica is optional.

| | |
|---|---|
| **Minecraft** | 1.21.11 or 1.21.4, each with its own jar |
| **Meteor Client** | The build for your Minecraft version |
| **Fabric Loader** | 0.18.2 or newer |
| **Java** | 21 or newer |
| **Optional** | [dekrom's Baritone fork](https://github.com/dekrom/baritone/releases) for walking, refilling and storage trips. Litematica for the Printer's schematic placements (the Printer can read a file without it) |

## Features

All ten modules are in Meteor's **Qwerte** category. Every feature can be
switched on and off on its own.

### Auto Till

#### Tilling
- Tills every dirt, grass block or dirt path in reach that has air above it, nearest first.
- Pick which blocks it tills, how far it reaches, how many blocks per tick and how long to wait between batches.
- Switches to a hoe from your hotbar (or pulls one out of your main inventory) and switches back afterwards.
- Skips hoes that are about to break, and only turns itself off if you truly have no usable hoe left.
- Optionally only tills blocks that would end up hydrated.
- Highlights the blocks it's about to till.

#### Watering
- When tillable land has no water within 4 blocks, it finds the spot that would water the most of it, digs a hole there
  with your best tool and fills it from a water bucket.
- Only digs where the water can't spill: solid on all four sides and underneath, and never the block you stand on.
- Checks where the bucket will land right before using it, so water never ends up on the wrong block.
- Holds dry land back from tilling until it's watered, so new farmland doesn't dry out.
- **Fills holes in passing:** pours water into any empty 1x1 hole in reach that would water dry farmland or dirt, even
  while Baritone is walking, so holes missed earlier get filled on the way past.

#### Planting and bone meal
- Plants your chosen seeds on empty farmland: wheat, beetroot, melon, pumpkin and torchflower seeds, carrots, potatoes
  and pitcher pods.
- Uses bone meal on crops growing on farmland until they're fully grown.
- Works in order: till first, then plant what's tilled, then grow what's planted.

#### Seed restocking
- **Storage mode:** takes seeds from a chest, barrel or any other container you pick, walking there and back with
  Baritone if it's out of reach. You can add several storages.
- **Ender Chest mode:** places your ender chest, takes the shulker holding the most of your seeds, places and empties
  the shulker into your inventory, breaks it, puts it back in the exact ender chest slot it came from, then breaks the
  ender chest with a silk touch pickaxe and picks it back up. It checks Meteor's memory of your ender chest first, keeps
  a slot free for the shulker, and always puts everything back even if something goes wrong.
- Restocks before doing anything else when you're low, so it never tills land it has nothing to plant on.
- **Several storages:** storages are tried in reach first, then nearest. It moves on when one is empty or can't be
  reached, and tops up from the next one if it's still low.
- Notices when all the storages, or every shulker in your ender chest, has run out of seeds, and turns seed restocking
  off with a message instead of trying again and again. Turn seed restocking back on after refilling.

#### Automation (needs Baritone)
- **Auto walk:** when nothing is left in reach, walks to the nearest untilled land within a radius of where you turned
  the module on.
- **Water refills:** when your buckets run dry, walks to the nearest 4x4 infinite water source (or a refill station you
  choose), fills every empty bucket, then walks back to where it was working.
- Fills only from water that grows back and waits for it to refill between buckets, so pools never drain.
- Stands on the bank to fill, and turns off Baritone's block placing in water while it drives, so pools aren't filled
  in on the way out.
- Keeps Baritone from breaking your farmland or using your water buckets to break falls. Your own Baritone settings
  are restored when the module turns off, and Baritone is stopped and reset when you leave a server.
- Remembers the last pool it used, recovers when Baritone stalls, and puts your held item back after every trip.
- **Combined trips:** after filling buckets it restocks seeds before walking back if you're running low, and after a
  seed trip it refills any empty buckets, so one trip covers both.

### Auto Dirt

#### Covering
- Places dirt on every empty spot in reach that sits on a solid block that isn't dirt, nearest first.
- Never places dirt on dirt, grass, coarse or rooted dirt, podzol, mycelium, mud, dirt paths or farmland, so it never
  stacks.
- **Covering dirt-like ground:** the blocks it skips are in `dirt-blocks`, remove one to cover it too. To turn a
  mushroom island into grass, set `dirt-item` to Grass Block and remove Mycelium from `dirt-blocks`.
- **Grass on dirt:** with Grass Block as the `dirt-item`, plain dirt is always covered, so when you mine mycelium (or
  anything else) and dirt is uncovered, grass goes on top of it. It never places a block on the same block, so grass
  never goes on grass.
- Never places into water or lava, or where you or another entity is standing.
- Only covers ground below your feet by default, so walls and ledges are left alone.
- Pick which dirt block to place, how far it reaches, how fast it places, and blocks it should never cover.
- Pulls dirt from your main inventory into the hotbar, and switches back to your previous slot afterwards.
- Works alongside auto-till, which tills the dirt as it's laid.

#### Dirt restocking
- **Storage** and **Ender Chest** modes, the same as seed restocking.
- **Several storages:** storages are tried in reach first, then nearest. It moves on when one is empty or can't be
  reached, and tops up from the next one if it's still low.
- Can walk to a storage that's out of reach with Baritone, and back again.
- Restocks before placing when you're low, and turns restocking off only when all the storages have run out of dirt.
  Turn restocking back on after refilling.

#### Automation (needs Baritone)
- **Auto walk** (off by default): when nothing is left in reach, walks to the nearest uncovered ground within
  `search-radius` of where you turned the module on, searching `search-height` blocks up and down. It skips spots it
  can't reach or place on, and says "No more ground to cover" when there's none left.
- Keeps Baritone off farmland while it walks, and stops and resets Baritone when you leave a server.

### Stash Sorter

#### A saved stash
- The first time, it reads every chest, trapped chest and barrel within `radius` (a double chest counts as one) and
  saves the stash under `stash-name`. After that it only reads containers it hasn't seen before.
- The saved stash stays up to date: whenever you open one of its containers yourself, even with the module off, what's
  inside is saved.
- Every start reads every container again, because players may have changed things while it was off or you were
  disconnected. What is known stays in use (searching, planning, putting away) until each container is read, but
  nothing is taken out of a container that hasn't been read since the start.
- Each stash name on each server is its own stash, so you can keep several. `Forget contents` and `Forget stash`
  buttons in the module's settings start over: the first makes the next run read every container again, the second deletes the saved stash.
- The **Unstuck** button drops whatever the sorter is doing and starts over. A shulker step that was under way is not
  resumed: a shulker of ours still standing is left where it is and listed with the orphaned shulkers.

#### Sorting
- **Tiers mode** fills the containers nearest to where you turned it on first, in this order: filled shulkers, empty
  shulkers, items that stack to 64, items that stack to 16, then singles and everything else.
- **Signs mode** reads the signs on the side of each container or standing on top of it. Each item goes to the
  container whose signs describe it best. Items no sign describes stay where they are.
- **Shulkers are sorted by what's inside.** A shulker goes where the item filling most of its slots goes, so a shulker
  that's mostly totems ends up in the `Totems` chest. Only when no sign describes its contents does it go to a
  `shulkers` or `kits` sign.

#### What signs understand
Write signs the way you'd talk. Each line is read on its own, a sign's lines are also read together, and lists like
`Logs, Planks & Wood` are split up. Filler like "stuff", "items" or "supplies" is ignored, so `Redstone Stuff` works.

- **Fancy letters** (like `𝖲𝗐𝗂𝖿𝗍 𝖲𝗇𝖾𝖺𝗄`, fullwidth, circled or small capitals) are read as the plain letters they stand for.
- **Enchantments with a level**, like `Protection IV` or `Efficiency 5` (I to X or 1 to 10): enchanted books holding exactly that level go there, gear with it at that level scores lower than the gear categories, other levels never match. Without a level any level matches.
- **Exact names** win over everything: `Diamond Block`, `Totem of Undying`.
- **Words** in an item's name, so a shorter name finds the longer one: `rockets` finds firework rockets, `crystals`
  finds end crystals, `diamonds`, `ores`, `gold` (golden items too), `oak`, `copper`.
- **Nicknames** players use: `obby`, `gaps`, `egaps`, `tots`, `pearls`, `xp`, `echests`, `cobble`, `steak`, `gp`,
  `scrap`, `debris`, `dias`, `ems` and more.
- **Enchantments and potions**: `Mending Books`, `Protection`, `Strength Potions`, `Fire Resistance`.
- **Hundreds of categories and slang**, for example:

| Kind | Sign words |
|---|---|
| Shulkers | `shulkers`, `full shulkers`, `kits`, `empty shulkers` |
| PvP | `pvp`, `cpvp`, `crystals`, `obby`, `totems`, `gapples`, `egaps`, `pearls`, `anchors`, `xp bottles` |
| Gear | `armor`, `helmets`, `boots`, `elytra`, `weapons`, `swords`, `bows`, `arrows`, `tools`, `pickaxes`, `enchanted`, `godset`, `damaged` |
| Valuables | `valuables`, `loot`, `treasure`, `rare`, `diamond`, `netherite`, `gems`, `ingots`, `ores`, `storage blocks` |
| Building | `building blocks`, `stone`, `bricks`, `wood`, `logs`, `planks`, `glass`, `concrete`, `terracotta`, `wool`, `dirt`, `sand`, `stairs`, `slabs`, `walls`, `fences`, `doors` |
| Farming | `food`, `meat`, `fish`, `crops`, `farming`, `seeds`, `saplings`, `flowers`, `plants`, `bone meal` |
| Redstone | `redstone`, `tech`, `circuits`, `contraptions`, `rails`, `pistons` |
| Mob drops | `mob drops`, `bones`, `string`, `gunpowder`, `slime`, `blaze` |
| Brewing | `potions`, `brewing`, `alchemy`, `enchanted books`, `books` |
| Places | `nether`, `end`, `ocean` |
| Decor | `decoration`, `lighting`, `banners`, `carpets`, `beds`, `candles`, `heads`, `dyes`, `colored` |
| Other | `transport`, `fireworks`, `music discs`, `spawn eggs`, `maps`, `workstations`, `templates`, `named`, `junk` |
| By stack size | `x64` / `stackables`, `x16`, `singles` |
| Catch-all | `misc`, `other`, `overflow`, `unsorted`, `*` |

#### Repacking shulkers
In Signs mode, with `repack` on, it sorts what's inside shulkers too:

- **Mixed shulkers are cleaned out.** A shulker is kept for whatever it's mostly full of. Anything else in it that a
  sign has a home for is taken out, so a shulker of totems with a few rockets and some obsidian becomes a shulker of
  totems, and the rockets and obsidian go to their own chests.
- **Loose items are packed into bulk shulkers.** Before putting loose items away, it tops up a shulker that already
  holds only that item and has room, one it's carrying or one from the stash. With `new-bulk-at` stacks or more of one
  item, it starts a new bulk shulker from an empty one.
- Then the shulkers go where their contents belong, like any other shulker.

To do this it puts the shulker down beside you, opens it, moves the items, then breaks it and picks it back up. It
needs a free spot beside you on solid ground with air above, and a pickaxe in the hotbar makes breaking quicker.

Named shulkers are never taken apart. To keep kits whole, sign a chest `Kits` (or `Mixed Shulkers`): mixed shulkers
then go there as they are instead of being repacked. A chest signed `Shulkers` is just for shulkers that don't belong
anywhere else, and doesn't stop repacking. Kit shulkers that have a chest under kit sorting are kept whole as well (see
Kit sorting below).

Once everything is in place, partial stacks of the same item in each container are merged ("downstacked"), so a
chest with three stacks of 20 cobblestone ends up with one of 60.

#### Crafting
New in 0.4.0-dev.18 and 0.4.0-dev.19. Not tested in game yet.

The bot can craft at a crafting table: the one you set, or one it finds, places or crafts near the stash. Its settings
are in the [Stash Manager](#stash-manager) module's Crafting and Crafting table groups.

- **Empty shulker boxes** (`enable-crafting`, off by default): when an order or compaction needs a shulker box and none is
  left, the bot crafts a plain one from shulker shells and a chest (made of planks, or of logs). Shulker shells can't be
  crafted, so the shells must be in the stash. Kits never craft. `craft-max-per-order` (27 by default, at least 1; the slider goes
  to 54) is the most boxes for one order, `craft-max-ops-per-task` (128 by default, at least 8) the most grid fills in one trip to the table,
  and `craft-max-per-hour` (54 by default, at least 1) the most boxes in an hour. `craft-never-use` lists items crafting
  never uses as ingredients (none by default).
- **The crafting table.** Turn on `select-crafting-table`, then right-click a crafting table to make it the one crafting is
  done at. Right-click the current one to clear it, or turn on `clear-crafting-table`. Without one set, the bot finds a
  table in the stash area, takes one out of the stash and places it, or crafts one. `show-crafting-table` (on) outlines
  it while the module is on, and the chat says whether it was set, found, placed or crafted.
- **The recipe table.** Nothing is shipped in the addon. The bot fetches the table from the Stash Manager server (set
  `server-url` and `api-key`), keeps a copy and checks it against its hash. With no server set, or when the fetch fails,
  it builds the table once from the game's own client jar. Until the table is there, crafting waits. If there is none,
  crafting reports that there is no recipe table.
- **Craft orders** (`craft-orders`, on by default): the bot also takes orders from the website for items it can make from
  what its stash holds (see [Stash orders](#stash-orders) in the Printer section). It needs `enable-crafting` on and a
  crafting table, the website's or its own.
  - Checks the order before any click. If the recipes or steps don't match the bot's own table, each craft line is
    left short and the rest of the order goes on.
  - Crafts bin by bin. The ingredients come from the stash, within the order's budget, in as many table trips as needed,
    and then the bin is packed.
  - A budget ledger keeps what each order has used. It is saved with the order, so a restart carries on with what is left.
  - **Crafting table for orders.** The website's table comes first. With none set, the bot finds, places (from plain stash
    items) or crafts its own table, and uses it for that stash. It puts down at most 1 of its own tables per order and 3
    per hour. A table you set by hand outside those limits is left for shulker-box crafting, and craft orders use a table of their own.
  - **Results.** The order's report says what was made, what was short and why, and what went back to the stash. The
    final report waits up to 90 seconds for the put-back. A circuit breaker gives up on the remaining craft lines after 3
    empty chunks in a row.
  - `craft-order-max-fills` (2048 by default, from 1 to 100000) is the most grid fills for one order, and
    `craft-order-max-per-hour` (4096 by default, at least 1) the most in an hour. An order that needs more is refused when
    it is placed, and past the hourly limit the rest is left short.
  - **Known limits:**
    - Gathering takes whole stacks from the stash and puts back what isn't used. The budget is charged exactly.
    - A broken website table does not fall back to the bot's own table.

#### Kits
A kit is a set of items packed together in one shulker, slot for slot with the same counts, like a PvP kit. These are the
saved kits that `build-kits` builds. To send kit shulkers that are already in your stash to their own chests, see
[Kit sorting](#kit-sorting) below.

- **Saving:** put the kit in your 27 main inventory slots (not the hotbar, offhand or armor), type a name in the **Kits**
  section of the module's settings and press **Save kit**. The name needs letters or numbers, must be unique on the
  server (case and punctuation don't count), and a kit can't contain shulker boxes.
- **The list:** each kit shows its slot count, how many are built and how many more the stash could build. **View**
  opens a look-only preview. Click the minus twice to delete a kit.
- Kits are saved per server in `meteor-client/qwerte/kits/`. A file that this version can't read (for example one saved
  on the other Minecraft version) is kept untouched and its kits are unavailable. A file that can't be read at all is
  moved aside as `<server>.corrupt-<time>.nbt`.
- **Building** with `build-kits` (off by default, Signs mode only): it builds as many copies as the stash allows. It
  takes an empty shulker, preferring boxes signed "empty shulkers", gathers the items and fills the shulker with the
  exact saved counts. Durability and anvil cost are ignored, but everything else must match: names, enchantments,
  potions and trims. A saved kit whose name is a Kit Builder template is skipped here (from 0.6.0); the Kit Builder's
  **Import local kits** button can turn it into a template instead.
- **Where kits go:** a finished kit goes to the chest whose sign is the kit's name, "<name> kit" or "<name> kits". With
  no such chest the kit isn't built, and the chat says so once.
- **Kit chests are reserved:** a sign naming a kit's chest keeps loose items out of it, even with `build-kits` off. So
  don't name kits after categories, like `pvp` or `totems`.
- Finished kits are never unpacked or topped up, and neither is a part-built kit holding more than one kind of item. A
  part-built kit in its own chest is carried on with. A part-built kit holding only one kind of item, outside its kit's
  chest, is treated as an ordinary bulk shulker.
- Each step makes up to two inventory clicks, then waits `delay` ticks. Raise `delay` if the server complains about
  clicks.

#### Kit sorting
New in 0.6.0 (kit sorting v2) and not tested in game yet. With the `kit-sorting` setting on (the default), a shulker box that
is a kit goes to a chest you pick on the [Stash Manager](#stash-manager) website or in the Stash Window's Kits tab, and the
sorter moves it there when it finds it somewhere else. Stash Manager server 1.1.0 or newer is needed for kit v2.

- **What is a kit.** The bot works out a box's kit type from its contents, with the shared `kit-types.json` table. The seven
  types are PvP / Crystal, Travel, Redstone, Farming / Food, Building, Starter / Base and Other kit. A box is a kit of the type
  most of its items belong to, when it holds enough different kinds of item (three for PvP, Redstone and Starter, two for the
  others). Three quarters of its items must belong to the type. That drops to half when the name has a word for the type
  (a whole word, like "pvp" or "travel") or the word "kit" or "kits". A box of one kind of item is never a kit, whatever its
  name, and a mix with no type in the majority isn't one either.
- **Kit templates.** A box that matches a kit template from the Kit Builder is a kit of that template's type. A complete copy
  has the template's items, counts and components. Slot order, how stacks are split, custom names, damage and repair cost
  don't matter, and a template slot set to any variant takes any version of its item. A part-built copy carries the
  template's name and holds some of the template's items; the Kits tab shows it as incomplete.
- **Treat as.** In the Kits tab, a kit's name can be set to a type, or to "not a kit". A name set to "not a kit" is never
  sorted as a kit.
- **Where kits go.** A kit goes along its chain and into the first chest on it that can take kits: the chest of its name's
  rule, then its template's chest, then its type's chest, then the kits chest. A kit that is already in one of these chests
  only moves up the chain, never down: when its chest is full it stays where it is. A kit whose chain has no chest set sorts
  as before. A shulker that KitBuilder already routes (a finished or part-built kit, above) keeps KitBuilder's routing.
- **Kit-reserved chests.** The kits chest and every kit's chest hold only kits. Nothing else is sorted into them, and they
  are left out of overflow, sign scoring, Tiers, compacting, repacking and auto-labelling. A put-back into one happens
  only for a kit routed to it.
- **Kits kept whole.** A kit with a target (a rule or the kits chest, including one that is missing right now) is never
  unpacked, repacked or compacted. A kit with no target is repacked as before.
- **Missing or full chests.** A chest that is missing or can't take kits (not read yet, excluded, a drop-off, unreachable)
  gives the kit no destination: a kit already in the stash stays where it is, one from a drop-off or your inventory goes
  to overflow, and you get one warning per kit and chest until the settings change. A full chest takes the normal path,
  so kits in the stash move once it has room.
- **Settings.** The bot takes its settings from the Stash Manager server: the kits chest, a chest per kit type and per kit
  name, the name rules, the Treat as choices and the kit templates. It keeps the last ones it received in
  `meteor-client/qwerte/kit-settings.json`, so it keeps routing while the server is offline. Settings that fail their check
  are ignored, and a warning is logged once.
- **Off.** Turn `kit-sorting` off to sort kits like any other shulker box. Nothing about kits is sent to the server then,
  and the saved settings are kept.
- **Older bots.** A full box of one OP item, for example 27 totems (the way the compactor may make them), is one kind of
  item, so it is stock and not a kit. While an older bot reports, the Kits tab's Advanced section still shows the v1 points.
- The settings and the report travel in the [stash-manager](#stash-manager) module's heartbeat, so that module needs to be
  on, with `server-url` and `api-key` set and a stash loaded. Without it the bot has no kit settings and sorts kits as before.

#### Excluded and drop-off containers
- Set `pick` to **Exclude** and right-click containers it should never sort into or out of. Right-click again to undo.
- Set `pick` to **Drop-off** and right-click containers to empty into the stash, for example a chest where friends
  leave items. Drop-offs are always emptied completely: anything without a home, or whose chest is full, goes to a
  chest signed `Misc` (or `Other`, `Overflow`...), and failing that to any container with room.
- Marks are saved with the stash and shown in red and blue while picking or sorting.

#### Bot mode
- With `stay-on`, it doesn't turn off when the stash is sorted. It sorts again whenever a container in the stash
  changes, and checks drop-off containers every `recheck` seconds. With `smart-recheck` (on) only drop-offs that can
  change without anyone opening them (a hopper, dropper or dispenser next to or above them, or outside loaded range) are
  checked, and for each one the wait grows from `recheck` up to 5 minutes while its checks find nothing new. Drop-offs someone opens
  are noticed from their lids.

#### Messages
- `log-level` Quiet shows only what it is doing now and next (`Stash: sorting: taking 5 stacks from "Diamonds" at x y
  z; next: put them away ...`), errors and alerts. Alerts are the things that need a person (a shulker left standing, a
  shulker that can't be broken, nowhere to put stacks, signs missing, carry slots full, a container that can't be
  reached); they are also whispered (`/msg`) to everyone on the `whitelist` (whether or not `whisper-search` is on), the
  same text, digits aside, at most once in 10 minutes, at most 5 waiting and one sent every 5 seconds, and never while you
  are disconnected. Normal is the usual
  output. Debug adds why it decides what it does, shown as `[debug]` lines. Whatever a level hides is still written to
  the log file.

#### If the connection drops or you stop it
- A shulker the sorter put down (repacking, compacting, kits) is written to the stash file with what it is for. If you
  disconnect, or stop the module, while one is standing, that is said (`Disconnected mid-job: shulker at x y z kept...`)
  and the next start walks back to it, checks it is the shulker it put down and holds what was expected, finishes
  packing it, breaks it, picks it up and puts it away, before reading the containers again. A shulker in another
  dimension is kept in the record and no new shulkers are put down until you are back there. Kit shulkers are just
  picked up again. What it carries is saved too, so it is put away after a restart.
- After a disconnect the module switches itself off; turn it on again to resume.

#### Stash perimeter
- Set a perimeter with `pick` modes **Area corner 1** and **Area corner 2** (the block you right-click becomes the
  corner) or the **Set corner 1/2 here** buttons in the module's GUI. **Clear area** removes it. The two corners are
  saved with the stash. Boxes bigger than 256x128x256 are refused. The outline is drawn in `area-color` while
  stash-sorter or stash-overlay is on.
- With a perimeter, `radius` and `height` aren't used: every chest, trapped chest and barrel (if `barrels` is on)
  inside it belongs to the stash, none outside does.
- It explores first: for every 16x16 chunk column of the perimeter not seen loaded, it walks towards the middle of the
  part of the column inside the perimeter (X and Z only) until the chunk loads, nearest first, and adds the containers
  in it. A column is skipped after 40 seconds, 15 seconds without getting closer, or 2 seconds at the spot with it
  still unloaded. This runs when you turn it on and after the perimeter changes, the stash changes or **Forget
  contents**; the usual checks (enough containers, signs) follow it.
- It walks inside the perimeter (and 2 blocks around it) on its own, even with `walk` off. Needs Baritone.
- While the sorter walks, Baritone isn't allowed to break or place blocks, so it never digs or bridges to a
  destination. A container it can't reach that way is reported once and skipped until the perimeter changes or you
  press **Forget contents**.

#### Bundles
- Nothing is ever packed into a bundle, and bundles are never used as containers: compacting, repacking and kits
  leave them alone.
- A bundle with things in it that the sorter finds in the stash (in a chest, a drop-off, picked up from the ground, or
  inside a mixed shulker it is cleaning) is taken out and emptied into free carry slots, one stack at a time, with the
  plain inventory open and nothing on the cursor. The contents are sorted like anything else, and the empty bundle goes
  to a chest signed `Bundles`, else one signed for shulkers or containers, else by the usual rules. If there aren't
  enough free slots it empties what fits and carries on later. Bundles in your own slots are never touched.
- It logs "Emptied a bundle: N stacks taken out."

#### Scan only
- With `sort-items` off, the module only keeps the saved stash current: it reads containers it doesn't know or that
  went stale (also after **Forget contents**) and watches containers and signs. It does one scan and turns off, or with
  `stay-on` keeps watching. It never moves items: no
  taking, depositing, compacting, labelling, kits or picking up from the ground, and it opens containers only to read
  them. The status and the log say "scan only". stash-overlay and search keep working, with stash-sorter in this mode
  and with it off.

#### Picking up from the ground
- With `collect-ground` (and Baritone), between jobs it walks onto items other players left lying in the stash area
  (the containers plus `collect-range` blocks around them, 4 blocks up and down) and sorts them with the rest. It
  never leaves that area.
- Only items that have lain still for 3 seconds, and only while no other player is loaded: if one turns up on the way
  it stops at once and waits until every other player is gone.
- Never items you dropped or threw (the drop keys, throw clicks, clicking outside a window, other modules) or that
  came from blocks you dug. Items in or above lava, fire, cacti, magma, berry bushes or powder snow are skipped. During
  a trip Baritone may not break or place blocks, and the trip ends if you end up more than 2 blocks outside the area.
  An item it can't reach or doesn't pick up within 20 seconds (or 2 seconds of standing on it) is left alone for
  5 minutes. Trips run after everything is put away, before kits and compacting.
- What it picks up counts as stash cargo, so tools and food from the ground are put away like anything else. The
  module's GUI shows the ground status.

#### Carry slots
- By default it carries items in every inventory slot that's empty when it starts, so your own items are never touched.
- `carry-slots` picks exactly which slots to use, like `28-36`. `keep-slots` marks slots never to touch. Slots 1-9 are
  the hotbar and 10-36 the inventory from the top left.
- When the container an item belongs in is full, it swaps the item with something in there that belongs elsewhere, so
  a stash where every container is full can still be sorted.

#### Search
- Type in the search box in the module's settings to find anything in the saved stash, including inside shulkers.
  Each result shows the item, how many you have, and where. **View** opens it in a container screen: hover it for its
  name, enchantments and everything else (press F3+H for full item data).
- With `whisper-search`, players on your `whitelist` can whisper you, for example `find diamonds`, and you whisper back
  `Diamond Can be Found At 120, 64, -300 @ stash`. Works with the module off. Replies are spaced out to avoid spam
  kicks.

### Stash Overlay
- Draws the item each container holds most of as an icon on the container, so you can see what's where at a glance.
  It uses the stash saved by stash-sorter (the one named in its `stash-name`), so scan a stash with stash-sorter first.
- Icons grow as you walk up to a container and shrink as you walk away, between `max-grow` and `min-shrink`. They
  disappear once a container is beyond your render distance.
- A container mostly full of shulkers shows what the shulkers mostly hold, with a small shulker in the corner.
- Icons update as the stash does: whenever stash-sorter sorts or you open a container yourself.

### Reset Pearl
New in 0.4.0-dev and still being tested.
- After you're pulled through a saved stasis chamber, it climbs out of the shaft (opening the trapdoors on the way),
  throws a fresh pearl into the water from the rim, and closes the trapdoors it opened.
- Save a chamber with the module's **Set location here** button, standing on the closed cover above the shaft, on the rim
  beside it, or in the water.
- `speed`: Safe, Fast (the default) or Max. Safe keeps the longer waits; the faster modes overlap the safe steps.
- `pearl-wait` (20 seconds by default): when you're pulled without a pearl, it waits this long for one. 0 gives up at once.
- `elytra-handling` (Auto by default) takes an elytra off for the climb and puts it back afterwards.
- `silent-rotations` (on by default, New in 0.5.0): the pearl is thrown with rotations sent to the server only, so your
  camera does not move. Off turns the camera to the aim. Walking out of the chamber still turns you. Some anti-cheats may
  flag silent rotations.
- Throw safety (New in 0.5.0): the throw is skipped when no pearl is in the hand, and a queued throw expires after 5 ticks
  if its rotation never runs.
- It stops with a chat message on a timeout, damage, a nearby player or hostile mob, an open screen, or a dimension change.
- It closes only the covers, never the stasis trapdoor at the water. In a shaft with no trapdoor at the water, the lowest
  trapdoor is taken for the stasis one (never closed) only when it is at most 2 blocks above the water top, where a stasis
  trapdoor sits; higher up, two or more plain covers over a water column are all covers and all get closed. A trapdoor at or below the block above the pearl stays
  open (closing it would pull the pearl): the module says so once in chat ("Left open on purpose ...") and in the debug
  log, so that run does not look like a full success. The pearl margin is 0.3. Nothing is closed until the pearl is
  resting: slower than 0.2 blocks per tick, or, for a pearl held in an upward bubble column, whose height stays within
  0.05 blocks for 3 ticks.
- `debug` logs every decision to the client log with a `reset-pearl:` prefix. `experimental-sprint-swim` and
  `experimental-climb-throw` are off by default.

### Messenger
New in 0.4.0-dev.16, extended in 0.4.0-dev.17 and in 0.5.0 (display, theme and chat replacement). Not tested in game yet.

Messenger reads your whispers into a small chat window with a history per player and a Global view of the public chat,
and sends from the window. Turn it on with the **Messenger** module (category Qwerte).

- **The window.** Drawn over the game while you play (`show-overlay`, on; `window-opacity`, 75 by default, from 20 to 100).
  It takes the keyboard and mouse only when you focus it: with `focus-key` (Right Ctrl by default, from 0.5.0; a key you
  already set, or cleared, is kept), or by clicking it while the chat is open. Esc always lets go. The window has its own input line; a draft in the chat is not carried into it.
  `notification-sound` (on) plays a quiet sound when a whisper arrives that you are not reading.
- **Left pane.** Global first (selected when the window opens), then your conversations: friends first, then the most
  recent, each with an unread badge. Delete asks first. On Global the button is Clear, which empties the Global log after
  a confirmation.
- **New.** Opens a search field and a list: Meteor friends, then players online (tab list), then past conversations, with
  no doubles and never yourself. Typing filters the list, names that start with the text first. A valid name you type
  (3 to 16 letters, digits or `_`) that is not in the list is offered as "Message <name>". Click it or press Enter to open
  or start that conversation; nothing is sent. Esc closes the list. Names the whisper command can't address (such as
  Bedrock's `.Name`) are not listed.
- **Global.** The public chat as the chat shows it after other modules (cancelled lines are left out). Whispers stay in
  their own conversation. Typing there sends public chat through the game's normal chat send: a text starting with the
  Meteor prefix (`.` by default) runs as a Meteor command, and chat modules such as BetterChat may change the text. A text
  starting with `/` is sent as a command; a space right after the `/` is refused. Global messages share the whisper queue
  and rate limit (`send-interval-ms`, at most 3 waiting, never retried, dropped when you change server), and a command's
  arguments are never shown. `global-scrollback` (500 by default, 50 to 2000) is the most lines kept per server.
- **Whispers.** Found by the game's own translation key (vanilla), and by the text patterns `pattern-vanilla` ("X whispers
  to you: ..."), `pattern-2b2t` ("X whispers: ..." and "to X: ..."), `pattern-essentials` ("[X -> me] ...") and
  `pattern-from-to` ("From X: ..." and "To X: ..."), all on by default. A pattern must match the whole chat line. A decorated
  name ("[Mod] Steve") is resolved from the display name's own insertion; otherwise from the one online player named in the
  text, else its last word.
- **Your own patterns.** `extra-patterns`, one per line: `in:` (a message to you, the default) or `out:` (a message you sent),
  then a regular expression for the whole chat line, with `(?<name>...)` for the other player and `(?<msg>...)` for the text.
  Example: `in:\[(?<name>\w{3,16}) whispers\] (?<msg>.*)`. An invalid pattern is refused with a chat message; one that is
  too slow is switched off.
- **Sending.** `command-template` must be exactly `/<command> {name} {text}`: one command word (letters, digits, `_`, `:`,
  `.` or `-`, up to 32 characters), then `{name}`, then `{text}`, with nothing before `{name}` or after `{text}`, so what you
  type can only be the message. The default is `/msg {name} {text}`; on 2b2t use `/w {name} {text}`. Anything else is
  refused with a warning. Nothing is sent unless you press Enter or Send. Messages go out at least `send-interval-ms` apart
  (1500 by default, from 500 to 10000); three may wait, and more are refused.
- **Emoji.** Drawn as pictures in messages and in the emoji picker (see [Third-party](#third-party)).
- **History.** Kept in memory for each server (its address, or `local` for singleplayer and LAN). `max-history` is the most
  messages kept per player (200 by default, from 10 to 500). "Clear history" forgets every conversation and the Global
  log on every server, and deletes the file.
- **Saving.** `save-history` (off by default) keeps the history in `meteor-client/qwerte/messenger-history.json` (version 2:
  the conversations and the Global log of each server). Saves run in the background: whispers at most every 20 seconds,
  Global every 5 minutes, and everything when you leave a world or turn the module off. The file is written owner-only
  where the system supports it. The Messenger's own notes, unsent messages and drafts are not saved. A version 1 file's
  conversations go to the server you are on when it is loaded, and older files without Global still load. A corrupt file
  is moved aside (`messenger-history.json.corrupt`); one that is too big or can't be read is left alone and not saved
  until the module is turned on again. Nothing is sent anywhere.
- **Keys.** `take-chat-key` (off by default) makes the game's chat key (T by default) open the Messenger on Global instead;
  `/` still opens the normal chat. Only a chat that opens empty is taken: on 1.21.11 a chat that opens with a saved draft
  still opens the normal chat. `overlay-global` (off by default) puts Global in the overlay while the window isn't focused;
  off, the overlay shows your conversations instead.
- **Display (New in 0.5.0).** `auto-font-scale` (on) grows and shrinks the text, rows, badges and emoji with the window: its
  size against the default 330 x 190, from 0.75x to 2x. Off, `font-scale` (1.0, from 0.75 to 2.0) is used instead.
  `system-message-opacity` (55 per cent, from 10 to 100) dims the console lines in Global (server messages, joins and
  leaves, and notices from Meteor and its addons). Player chat is always at full opacity. `show-player-heads` (on) draws the
  player's head in front of a public chat line written `<Name> text`.
- **Theme (New in 0.5.0).** The window's colours are set in the Theme settings. `follow-meteor-theme` (off) uses the colours
  of Meteor's current GUI theme all the time, checked twice a second. The **Sync theme with Meteor** button copies them once
  instead.
- **Replacing the chat (New in 0.5.0).** `replace-chat` (off) turns the game's chat list off: the normal chat lines are not
  drawn, the Messenger window is the only chat display, and Global shows in the overlay. The chat key opens the Messenger
  (as with `take-chat-key`); `/` still opens the normal input line, with its suggestions. The hidden lines can't be clicked
  or hovered. Alerts that normally show in the vanilla chat (kicks, module warnings, server notices) show only in the
  Messenger while this is on, so keep `show-overlay` on. Turn it off to get the normal chat back.
- **Debugging (New in 0.5.0).** `debug-lines` (off, in the Debug group) writes every public chat line to `latest.log` with
  how it was read. It can log private text, so leave it off unless you are troubleshooting.
- **Settings (defaults).** Patterns: `pattern-vanilla`, `pattern-2b2t`, `pattern-essentials` and `pattern-from-to` on,
  `extra-patterns` empty. Sending: `command-template` `/msg {name} {text}`, `send-interval-ms` 1500. History: `save-history`
  off, `max-history` 200, `global-scrollback` 500. Window: `show-overlay` on, `window-opacity` 75, `focus-key` Right Ctrl,
  `take-chat-key` off, `overlay-global` off, `notification-sound` on, `replace-chat` off, `auto-font-scale` on,
  `system-message-opacity` 55, `show-player-heads` on. Theme: `follow-meteor-theme` off. Debug: `debug-lines` off.

### Stash Manager
New in 0.4.0-dev and still being tested.
- Sends what is in the stash saved by stash-sorter to the [Stash Manager server](stash-manager-server/README.md), a
  small website for your network that shows the stash like a Minecraft inventory. Set `server-url` and `api-key` (the
  server shows its key in the Link addon panel).
- With `accept-orders` on, the bot takes orders from the website. It gathers the items, loose stacks first and then out
  of shulkers, packs them into empty shulkers and puts them into the delivery container. Leftovers and unpacked
  shulkers go back where they came from. stash-sorter doesn't need to be on; to stop the bot, turn off `accept-orders`
  or this module.
- Pick the delivery container with `select-delivery` and a right-click on the container. It is never sorted into.
- An order in progress is saved with the stash, so it carries on after a disconnect.
- **Repairing items.** An order can ask for "Repair all items" (only damaged Mending items are repaired) or a "Repair"
  toggle on a single line. The bot repairs those items with Bottles o' Enchanting from the stash before it packs them
  (about 14 durability per bottle). If a player comes within 8 blocks or the orbs vanish, the throwing stops and is tried
  once more; after that the items still damaged go out as they are. Repair never refuses an order for too few bottles: items that are not
  fully repaired are delivered as they are, and the order's message says how many. The addon also takes bottles out of
  shulker boxes in the stash; the Stash Sorter plugin on AquariusProxy uses loose bottles only.

### Order Notifications
New in 0.5.0. Not tested in game yet.

Plays a sound and shows a toast (like an advancement) when one of your Stash Manager orders is delivered, delivered with
shortfalls, fails or is cancelled. It is on by default (module `order-notifications`, category Qwerte). It needs the Stash
Window's `connect-to-server` on and a login.

- `enable` (on): turns the notifications on or off.
- Sounds: `complete-sound` (level up) for a full delivery, `partial-sound` (note block) for a delivery with shortfalls,
  `failed-sound` (villager no) for a failed or cancelled order, and `arriving-sound` (none by default) for the bot starting
  the delivery trip. `volume` (100 per cent, from 0 to 200) and `pitch` (1.0, from 0.5 to 2.0) apply to all of them.
- `toast` (on) shows a toast for each notification. `chat-messages` (off) writes a chat line for each one; with streamer
  display on in the Stash Window, nothing that could hold a position is shown.
- A failed or invalid answer from the server changes nothing: an order is not announced again, and nothing is forgotten.

### Stash Window
New in 0.4.0-dev and still being tested.
- A window (keybind `open-window`, the `.stash-window` command, or the module's button) to search the containers you have
  opened. It works on its own: no server, no login, no network. It remembers chests, barrels and shulker boxes you open,
  including what shulker boxes inside them hold, and saves them under `meteor-client/qwerte/local-index/`.
- Optionally turn on `connect-to-server`, set `server-url` and log in to see the stash of your Stash Manager server with
  your website account's permissions. Plain `http://` outside your own network is refused unless `allow-insecure-http` is on.
- With a server connected, the window also has the server's pages, each shown only when your account's permissions allow
  it: Kits, Kit Builder, Orders, Live, Build Planner, Bots, Activity, Proxy, Console and Settings. Settings holds the bot settings, your
  account and, for the owner, the Users list; the bot settings show one section at a time, picked from a drop-down. The
  palette button in the header opens Settings > Appearance, where the window's themes are. The Build Planner works from
  your game's `schematics` folder (or an uploaded file) and can order a build's materials part by part.
- The server pages follow the website's layout. Orders has two columns: the order maker (with item-type buttons) and the
  cart (the delivery chest, the lines, the shulker names and the Repair box), with the order history below. Build Planner
  shows five summary tiles (six with the Craftable tile, while crafting shows), Bots one card per bot above the stash table, and Activity puts Now doing and Events side by
  side. Live has a Chat column beside the map and the bot's 27-slot inventory with its hotbar and gear. The console's input
  row stays pinned at the bottom. Not matched to the website yet: no drag-and-drop in Orders, no drop zone in the Build
  Planner, and no latency overlay on Live.
- The Repair box in the cart has "Repair all items" and a "Repair" toggle on each repairable item line; a damaged line
  that can't be repaired says why. It shows the estimate ("about N bottles needed, M in the stash") and warns when the
  stash is short or the bot can't repair. The order is still placed. Finished orders show the bottles used and the items
  that were not fully repaired.
- The window is laid out like the website's stash page: a header bar (the palette button, your role label, the stash and
  bot pickers), the creative tabs, the item grid with small counts in the corner of each slot, a search box and sort select under it, and the details of a clicked item in a drawer over the right
  side of the grid (closed, it takes no room). Drag
  its header to move it, drag its edges or corners to resize it (the grid gets more or fewer columns), double-click the
  header to maximize it. Its place and size are remembered. It follows Minecraft's GUI scale (`window-scale` in the module
  overrides that; Meteor's own GUI scale does not apply to it) and uses the game's font, at about the website's size next to
  the slots (`window-text-scale`: Auto, Small, Normal or Large).
- **Kits** (rebuilt in 0.6.0 and not tested in game yet; needs `view`, and not shown to a user the owner forced into
  streamer mode): the kits chest picker, a chest per kit type and per kit name, and a table of every kit the bot found,
  with where it is kept, its status and a **Treat as** choice. Changing a setting needs the `settings` permission. A
  server older than kit v2 gets the earlier layout, and the **Advanced** section shows the v1 points while an older bot
  reports. The window's streamer mode shows kits as "Kit 1" and chests as "Chest 1" with no labels or coordinates. See
  [Kit sorting](#kit-sorting).
- **Kit Builder** (New in 0.6.0 and not tested in game yet; server mode only; needs `view`): the stash's kit templates,
  grouped by type, with their stock (complete and incomplete copies, and what the incomplete ones are short of). **New
  kit** and the **From a preset** list start a new template, and **Edit** opens an existing one in its editor: a 27-slot
  grid where you click a slot to pick it, right-click to empty it, and click an item
  below to put it in the picked slot. Each slot is either an exact item or any variant of it, and a slot the stash no longer
  has is shown in red. The editor also has presets, a shulker colour, and the restock keep (0 is off). Saving needs
  `settings`, and deleting a template keeps its boxes, which are no longer copies of it. **Order** needs `order`, and shows
  only where the stash's bot can carry out kit orders, which the addon's bot can't yet.
- **Import local kits** (on the Kit Builder tab, when this client has saved kits): makes a template from each kit you saved
  in stash-sorter's Kits section. Press it once, then **Confirm import?** to go ahead. Kits of one kind of item, and copies
  of a template already on the server, are skipped, and the import stops at the server's template limit (50 templates).
  Local kits are kept. They are saved per client, not per server, so the import can't be limited to one server's kits.
  The server takes 60 writes a minute; if the import stops there, run it again.
- **Build Planner crafting** (New in 0.4.0-dev.18; not tested in game yet). The planner also shows what the stash can make
  from what it holds.
  - A Craft column shows how many of each row can be made: "-" when there is nothing to make or nothing can be made, "0"
    when a recipe exists but makes none, and "..." while the recipes load. Hover for what is taken from the stash, what
    is made and what goes back.
  - A row the stash covers by crafting reads "craftable" in the Missing column. A partly covered row shows what is still
    missing, with "+N craftable".
  - A Craftable tile shows how many items the stash can make, in how many kinds, or why they can't be ordered. The Missing
    tile shows what is left after crafting.
  - Banners say when the recipes are loading, when crafting info isn't available for the Minecraft version, when the plan
    was cut short, and why orders can't use crafting: the stash's bot can't craft orders, its recipe table differs from the
    server's, there is no crafting table for the stash, or the table isn't usable.
  - A craft checkbox, "Craft what the stash can make (N items from the stash)", adds the craftable amounts to the order as
    craft lines. It is on by default when the stash can take craft orders. Otherwise it is off and disabled, with the reason beside it.
- **Orders crafting.** A craft line reads "Craft: 8 Spruce Stairs" and "Crafting takes ... from the stash." A finished order
  shows "Crafted: ...", "Not crafted: ..." with the reason for each item, and "Returned to the stash: ...".
- **Stash area editor (New in 0.5.0; not tested in game yet).** The Area page shows the stash's area as stored, and what the
  bot has done with it. Edits are made on a draft, and nothing is sent until you press **Save**; the bot applies it between
  tasks. The draft can set corners at your feet or at the block you look at, expand a side (or all six) by N blocks, include
  the block where you stand, take a rectangle dragged on the map for X and Z, or start from the bot's current area. The draft
  shows in the world as an amber box with its size. Your account needs permission to change the stash area. A draft is
  dropped when you leave the world.
- `window-theme` (or the palette button in the header, which opens Settings > Appearance) picks its colours: Classic (the default), Modern, Light, Dark,
  Midnight, High contrast or Mac, the same palettes as the website. Classic matches the website's default.
- The session token of a server login is stored in plain text in Meteor's `modules.nbt`. Do not share your `meteor-client`
  folder or Meteor profiles, and use the window's Log out to end (revoke) the session.

### Container Tweaks
New in 0.4.0-dev and still being tested.

#### Shulker preview
- Hover a shulker box in a container or your inventory to see what is inside as a grid, in the box's colour.
- Hold `freeze-key` (Left Alt by default) to freeze the grid where it is. Hover an item in the frozen grid to see its full
  tooltip. Clicks inside the frozen grid do nothing. It stays hold-to-freeze. The key is read straight from the keyboard
  (New in 0.4.0-dev.18.1), so it still works when another mod takes the key; before, holding Alt could do nothing with
  some mods installed. A key bound with modifiers is still checked by Meteor.
- While `preview` is on, Meteor's own shulker preview is hidden, and Meteor's BetterTooltips settings are left as they are.
  `hide-text-list` (on by default) also leaves the text list of contents (such as "Dirt x64, and 5 more") out of the tooltip.
  On 1.21.11 that applies to every item with contents, since the game doesn't say which item the list belongs to.
- `icon` (on) draws the item a shulker box holds the most of on top of it, in containers and your inventory, and with
  `hotbar-icon` (on) on your hotbar too. It counts items by type across the whole box, and empty shulker boxes get no icon.
  `icon-scale` sets its size (0.5 of a slot by default, from 0.3 to 1) and `icon-corner` its corner (top left by default).

#### Smart Shulker and Smart Echest
Both are off by default. Turn on `smart-shulker` for shulker boxes and `smart-echest` for ender chests.
- Right-click a shulker box (or an ender chest) in your main inventory, your hotbar or an open container. Qwerte places it at
  the closest spot in reach, opens it, and mines it again when you close the screen, so it goes back into your inventory.
- A shulker box in your inventory must be in the main inventory or the hotbar. From a container, the hotbar needs an empty
  slot, since nothing of yours goes into someone else's container. It does nothing in creative mode.
- If there is no spot, it says why (for example "no solid block to place it on" or "no room above for the lid") and
  does nothing.
- Qwerte does at most one thing in the world per tick, and waits `action-delay` ticks between steps.
- With Baritone it walks back to the block and to the dropped item when they are out of reach. Without Baritone, anything
  out of reach is left where it is and listed (see below).
- **Left behind.** If Qwerte has to stop (the cancel key, turning off the module or the feature, a world change, a full
  inventory that stays full for 5 minutes, or an item not picked up in time), it says where the item is and lists it as
  "Left behind" with its coordinates when you next enter that world. An entry goes away once the block and the drop are
  gone. Dropped items despawn after 5 minutes.
- **Smart Echest** needs a silk touch pickaxe in your inventory to pick the chest up again. `no-silk-touch` says what to do
  without one:
  - Refuse (the default): does nothing and says so.
  - Place and leave: places and opens it, then leaves it there and tells you where. Left ender chests are not added to the
    "Left behind" list.
  - Mine anyway: mines it with any pickaxe, but it drops 8 obsidian and the ender chest is lost. Without a pickaxe it is
    left there, as with Place and leave.

#### General
- `rotate` (on) looks at the block before placing, opening and mining it.
- `swing` (on) swings your arm. Off only hides the animation on your screen; the swing is still sent to the server.
- `action-delay` (3 ticks, from 0 to 20) is the wait between steps.
- `pickup-with-baritone` (on) lets Baritone walk back for things out of reach. `safe-walk` (on, shown while that is on)
  keeps the walk away from parkour, diagonal drops, falls of more than one block, and breaking or placing blocks. Qwerte
  refuses to start while Baritone is busy with something else.
- `pickup-timeout` (20 seconds, from 5 to 120) is how long to try getting back in reach, and to pick the item up, before
  leaving it.
- `cancel-key` (none by default) stops a running flow. The block stays where it is and is listed under "Left behind".

### Printer
New in 0.4.0-dev and still being tested. Not tested in game yet.

Printer builds a Litematica schematic in the world, one block at a time, with each block's facing and state. It places
what is missing, leaves the blocks that are already right alone, and lists what it skipped. Turn it on with the
**Printer** module (category Qwerte) and off to stop it.

#### Source
- `source` chooses what to build:
  - **LitematicaLatest** (the default): the schematic placement you last selected in Litematica, or the last one you
    added when none is selected. It keeps the placement's own position, rotation and mirror.
  - **LitematicaByName**: the Litematica placement named in `file-name`. Capitals don't matter, and part of a name works
    when only one placement matches.
  - **File**: the `.litematic` file named in `file-name`, placed with `origin-x`, `origin-y`, `origin-z`, `rotation` and
    `mirror`.
- Litematica is optional. Qwerte reads it by reflection, so it is not a dependency. Without it, or when a placement's own
  file can't be read, the file named in `file-name` is read with the settings above.
- **Schematic queue** (`queue-enabled`, off by default, and `queue`): build several schematics one after the other. Each
  `queue` entry is a Litematica placement name (exact name first, else the one placement whose name contains it) or
  `file:<name>` for a `.litematic` in the schematics folder; `file:<name>@x,y,z` gives that file its own origin, otherwise
  `origin-x/y/z` are used (`rotation` and `mirror` always apply to file entries). `file:` names are checked like the File
  source: no `..`, no absolute or drive paths. A missing, ambiguous or unreadable entry is skipped with a warning and the
  queue moves on. When one schematic is done (the report and finish sound play) the next starts without the Printer
  turning off; after the last, "Queue done: N schematic(s) built". Cancelling or leaving the world stops the queue, and
  turning the Printer on again starts from the first entry. The info line shows "Queue 2/5: name". With the queue on, `source` and
  `file-name` are not used. At most 50 entries are used. New in 0.4.0-dev.16, not tested in game yet.
- `chat-messages` (off by default; in the General group): lets the Printer write to the chat. Off, it sends no chat line at
  all (progress, warnings, errors, the stash order lines, Meteor's "toggled" line); everything is still written to the
  game log.
- Litematica's **render layers** are respected (single layer, all above, all below, layer range, on any axis). Only blocks
  inside the current range are placed, counted, planned for and ordered; the range is read again every 5 ticks, so a change
  applies while the Printer runs: blocks that fall outside leave the build, blocks that come inside join it. Without
  Litematica, or with a `.litematic` file read by this module's own position, there is no filter. When every block inside
  the range is built, the Printer pauses with "Layer done: move Litematica's render layer to continue" and resumes when the
  layer changes. The same pause comes at the start when the range holds nothing to build. A failed read keeps the last
  range that was read (everything is built until one has been read), is tried again 5 seconds later, and is logged once.
- `file-name` is a path below the game's `schematics` folder. The `.litematic` ending is added when you leave it out, and
  any other extension is refused. Up to two subfolders, at most 128 characters. Refused: control characters, `: \ < > " | ? *`,
  a leading `/`, `.` or `..` parts, names that start or end with a space or end with a dot, Windows device names (such as
  `CON` or `COM1`), and links out of the folder.
- Limits: a file up to 64 MiB compressed or 256 MiB unpacked, at most 64 regions and 33,554,432 blocks in total, read within
  20 seconds. Litematica format versions 4 to 7 are read (a file from an older version must be opened in Litematica and
  saved again); a newer version gets a warning and is tried. Schematics from Minecraft 1.13 or later are converted to your version. Unknown block IDs are listed and
  never placed.

#### Placing
- Each block is placed with a click that the game's own placement rules turn into the schematic's state: facing, half,
  hinge, axis, attachment and so on. The printer checks the block a few ticks after placing it and places it again, up to
  twice, if it did not appear.
- Supported: full blocks; logs, pillars and chains; furnaces and other facing blocks (anvils, campfires, decorated pots,
  pumpkins, glazed terracotta, chiseled bookshelves, ender chests, looms, stonecutters, lecterns, beehives); pistons,
  droppers, dispensers, barrels, observers; hoppers; shulker boxes; stairs; slabs; trapdoors; doors; fence gates; beds;
  buttons, levers and grindstones (on floors, walls and ceilings); torches, including every wall torch (torch, redstone,
  soul, copper); signs, banners and skulls (standing and on walls); wall hanging signs; ladders; tripwire hooks; wall coral
  fans; cocoa pods; hanging lanterns;
  end rods and lightning rods; amethyst clusters; bells; repeaters; comparators; note blocks; daylight detectors; candles,
  sea pickles, turtle eggs and snow layers; single chests; carpets, pressure plates, rails, redstone wire and plants (placed
  on a block that holds them).
- Set by extra clicks: a repeater's delay; a comparator's mode; a note block's pitch (after the block below it exists);
  a door, trapdoor or fence gate that is open; a daylight detector that is inverted; the count of candles, pickles, eggs
  and snow layers; a double slab (a second click). Iron doors and trapdoors can't be opened by hand, so an open one is
  skipped and listed.
- Set by the game from its neighbours or redstone, so not set by the printer: stair shape, fence, wall, pane and bar
  connections, redstone wire connections and power, powered, lit and triggered states, leaf distance, crop age and the
  like. A rail whose shape doesn't match after placing is listed.
- **Tool actions** (new in 0.4.0-dev.16, not tested in game yet). A block that a tool finishes is placed in its raw form and the tool is used on it right after, on a face
  chosen for the tool (toward you, never the bottom for a shovel or hoe). If you carry the finished item (a stripped log,
  waxed copper, a carved pumpkin) it is placed as it is. The tool is moved into the hotbar by the same helper as the
  building blocks, which never moves weapons, food or shulker boxes. Without the tool the raw block is still placed
  (except unwaxed copper, which would age), you get one warning per tool, and the block stays on a pending list: it is
  finished as soon as you carry the tool and are in reach (the Printer walks there with Baritone when nothing else is
  left). The Printer stays on for pending blocks only while the tool is carried, the block is loaded and a time limit has
  not passed; otherwise the run ends and the report lists them. A raw block that the tool turns into the wanted one is
  "right block, needs its tool", never a wrong block. The game's own tables are used where they exist.
  | Wanted | Placed | Tool |
  | --- | --- | --- |
  | Stripped log, wood, stem, hyphae, bamboo block | the unstripped block, same axis | axe (`AxeItem.STRIPPABLES`); with a shield in the off hand the game only strips while you sneak, so the Printer sneaks for it |
  | Dirt path | dirt (or grass block, podzol, coarse dirt, mycelium, rooted dirt) | shovel (`ShovelItem.FLATTENABLES`), air above |
  | Campfire wanted out (`lit=false`) | campfire (placed lit) | shovel, to douse it |
  | Farmland | dirt | hoe, air above; farmland counts as dirt in the materials |
  | Waxed copper | the unwaxed block, only when the waxed item is not carried | honeycomb (`HoneycombItem.WAXABLES`); not placed without honeycomb |
  | Carved pumpkin | pumpkin | shears; the side clicked gives the facing |
  | Lit candle | the candle | flint and steel; never on a waterlogged candle |
  | Potted plant (`potted_*`) | an empty flower pot | the plant item, right-clicked on the pot (not while sneaking); the materials count the pot and the plant |

  The hoe and shovel need air above the block, and farmland under a solid block turns back to dirt, so while a hoe or
  shovel is carried nothing is placed above a block that waits for it (crops included). A pot holding the wrong plant is a
  wrong block (see **Wrong blocks**). Farmland in a dry place can dry back into dirt later and is tilled again.
- **Waterlogged blocks.** `waterlog` (on): a block the schematic wants waterlogged is placed, then a water bucket from your
  hotbar or inventory is used on it. The bucket is emptied by that. Without a bucket, or with `waterlog` off, the block is
  placed dry and you get one warning for the run (and the count at the end). `refill-water-bucket` (off, shown with
  `waterlog`): when an empty bucket is carried and a water source block is within reach, the water is picked up again for
  the next block. It does not walk to water. Only a real water source block is used: never a waterlogged block, nor a cell
  the schematic builds in. With `rotation-mode` None, waterlog and refill are skipped with one warning (a bucket needs a look).
- Skipped and listed, never placed: fluids; fire; portals; bubble columns; vines, glow lichen and
  sculk veins; pointed dripstone; cauldrons; composters; respawn anchor charges; cake with bites; command, structure and jigsaw blocks; piston heads and extended pistons; hanging signs that hang from a
  ceiling; lily pads; double
  chest halves; end portal frame eyes; unknown blocks and properties. Block-entity data is not copied (signs are placed
  blank, chests and shulker boxes empty), and entities are not placed.

#### Speed
- `blocks-per-tick` (1 by default, the slider goes to 10, but the setting has no upper limit): the most blocks placed in
  one tick. 1 is the safe rate for 2b2t. Raising it can get you kicked, and the printer does not check the server's limits.
- `packet-budget` (55 by default, 0 turns it off): packets per second the printer may send (placements, swings, looks and
  slot changes). The limit is that number times 7 in any 7-second window, so the default allows 385. The default is
  chosen to stay under a 500-packets-per-7-seconds limit; that figure is an assumption about the server, not something
  the addon checks.
- `tick-delay` (0 by default, 0 to 10): ticks to wait after a tick that placed blocks.
- `multi-rotate-per-tick` (off): allows several different looks in one tick when `blocks-per-tick` is above 1. Each extra
  look is another packet.
- `range` (4.5 by default): how far from your eyes a block is placed. It is never more than your reach, minus 0.25.
- `rotation-mode`: **Smart** (the default) looks at the spot that is clicked, kept inside the look the block needs;
  **Snap** does the same rounded to 45 degrees; **None** never turns, so only blocks your current look already suits are
  placed.
- `airplace` (on): a block may be placed against nothing, but only when no neighbour can be clicked.
- `require-line-of-sight` (off; this was `visible-faces-only` before 0.4.0-dev.15, and the old saved value is dropped):
  only click a face with a clear line from your eyes to the spot.
- Leaving the schematic area (farther than your reach from every region) gives one warning per exit (in the game log, and in
  chat only with `chat-messages`) and the Printer keeps
  running: it only has nothing in reach to place. It walks back when `walk` is on, and places again when you are in reach.
- When nothing can be built for 3 seconds (nothing to place against, blocked, or in unloaded chunks) the Printer pauses with
  "Stuck: ..." and lists the first cells, and carries on by itself when something becomes placeable or walkable. It never
  ends for that reason. Only "everything that can be built is built" ends the run.
- `sneak-ticks` (2 by default, 1 to 6): ticks the sneak key is held before a click that needs it (a chest or door would
  otherwise open).
- `action-delay` (3 ticks by default, 0 to 20): the wait between steps when fetching materials.
- `order`: **LayerThenDistance** (the default), **Distance** or **Layer**. With `layer-order` on it ranks the blocks inside
  the active layer. `bottom-up` (on) builds from the lowest layer up; it is only used (and only shown) when `layer-order`
  is Off.

#### Layer building and scaffolding
New in 0.4.0-dev.16. Not tested in game yet.

- `layer-order` (Order group): **BottomToTop** (the default), **TopToBottom**, **MostEfficient** or **Off**. The Printer
  builds one y level at a time. Blocks above (or below) the active level are left alone until that level has no open block
  left, then it moves to the next level, and so on until the whole schematic is done. A level is open while it has a block
  that is queued, being checked, waiting to be tried again, or a wrong block in the way. Tool and water jobs never hold a
  level back. The run only ends when every level is done. **MostEfficient** picks the level with the most blocks ready to
  place right now (then the nearest in height to you, then the lower one) each time a level is finished. **Off** keeps the
  old behaviour: all levels at once, ranked by `order` and `bottom-up`. Changing it applies to the next run (or a reload).
- A level that can't be built yet is carried over: a lantern or vine hanging under a block of a later level when building
  bottom-up, sand with nothing below it when building top-down, a block with nothing to be placed against. When nothing in
  the active level can be placed, walked to, fetched or waited for for 1 second, the Printer carries that level over and
  moves on. When only carried levels are left, they are tried again (the levels they were waiting for are built by then).
  If a whole round gets nowhere, the layer rule is dropped and the Printer builds whatever it can, anywhere, until a block
  gets built; it never locks up on a level. It says "Layer Y=...: N blocks" when the active level changes (chat with
  `chat-messages`, and the game log).
- Wrong blocks are only dealt with in the active level and in levels already passed.
- Litematica's render layers still work and come first: they decide which blocks exist for the Printer, and `layer-order`
  then works inside them (blocks outside the render layer are not counted in any level).
- `scaffold` (on; shown when `layer-order` is on): when the nearest blocks of the active level have nothing to be placed
  against (`airplace` off), the Printer builds a chain of spare blocks from a block that exists up to a face of the
  block, places the block, and breaks the chain when nothing needs it any more. It walks to the chain's base with Baritone
  when it is out of reach, also outside your normal range. A spare block is any block you carry that the schematic does not
  use, a plain full cube that does not fall, open or hold items, and that the hotbar helper does not protect (no weapons,
  food or shulker boxes). It never breaks printed blocks to get access. With no spare block it warns once ("no spare blocks
  for scaffolding: carry some blocks the schematic doesn't use") and carries the block over.
- `scaffold-max` (64, 1 to 4096): most scaffold blocks standing at once. With the spare blocks you carry, it is the real cap
  on a chain's length.
- `scaffold-length` (Order group, 0, 0 to 256): the longest scaffold chain, and how far outside the schematic it may go.
  0 means no limit on either. New in 0.4.0-dev.16.1; before it, both were fixed at 6 blocks.
- Scaffold limits: only plain solid blocks are helped (a torch, a lantern or a falling block loses its support when the
  scaffold goes, so those wait for their real support); a chain is the shortest one, at most `scaffold-length` blocks, never
  inside a cell the schematic wants a block in, and within `scaffold-length` blocks of the schematic's area (no limit at 0).
  The search tries the straight columns from the block's faces first, then an L-shaped chain, then a short search for a
  shorter chain within its time budget. A long column down to the ground is found; long winding detours may not be. The
  "No scaffold can reach..." warning names the limit only when one is set. It is not used with `airplace` on; the
  Printer does not build stairs to stand on, so a spot out of reach that Baritone can't walk to is not made reachable. If
  you stop the Printer in the middle, the scaffold blocks that stand are listed in the report and left in the world.

#### Moving to tall and many-layer builds
New in 0.4.0-dev.16. Not tested in game yet. All of it needs `walk` on; `walk` stays the master switch for any automatic
movement (off: only what is in reach is built).

- `movement` (Baritone group): **StandOnBuild** (the default), **Fly** or **StandThenFly**. Flying is never used unless you
  pick Fly or StandThenFly.
- **StandOnBuild.** Baritone walks to the blocks and climbs onto the blocks already printed. While it walks it may place
  your spare blocks (the same definition as scaffold: carried blocks the schematic does not use, plain full cubes that do not
  fall, open or hold items, not protected by the hotbar helper) to pillar up and bridge, and it is only allowed those items
  (`acceptableThrowawayItems`). It never breaks anything (`allowBreak` stays off), and the cells still to be built are never
  chosen as the place to stand. The Printer sets these Baritone settings for the walk only and gives your own values back
  afterwards. Trips for containers and shulker boxes walk without placing. With no spare block carried it warns once
  ("No spare blocks to climb with") and walks only.
- Blocks Baritone placed outside the schematic are noted (a spare block that shows up where there was air next to your feet)
  and broken again once nothing needs them, like scaffold (`remove-stand-blocks`, on). A block it places inside a cell the
  schematic wants is ours: it is broken and the cell built, whatever `wrong-blocks` says (the container and fluid rules still
  apply). Stand blocks are never broken while a walk is going or while you are on or beside them (within 2 blocks sideways
  and at or below your feet); they come down from the top, and at the very end you are walked away from the last ones
  first. A stand block still standing after 3 failed walks away from it is left in place and listed. Stand blocks do not
  count toward `scaffold-max`.
- **Fly.** The Printer flies with a flight module that moves you with the movement keys, Meteor's Flight by default
  (`flight-modules`: the first one already on is used, otherwise the first in the list is switched on and, afterwards, off
  again, only if the Printer switched it on). It chooses a hover point within reach of the next blocks, above or beside the
  active layer and never in a block or a cell still to be built, and holds forward, back, left, right, jump or sneak to get
  there. It does not turn the camera, so placing and the module's own look handling are untouched. It stops moving while it
  places, lets go of the keys when you turn the Printer off, leave the world, open a screen, or after losing more than 3
  health in one tick (then it holds still for 2 seconds). The module is not switched off in the air: if you would fall more
  than 3 blocks it is left on, with a message. Solid ground or water below counts as safe to land on; lava or another fluid
  does not.
- **StandThenFly.** Walks as in StandOnBuild; flies when there has been no progress toward the target for 5 seconds, or two
  searches in a row found no path. It walks again only after flying at least 3 seconds, standing on the ground for 1 second
  and a back-off (10 seconds, doubled after each failed try, at most 1 minute).
- Path searches that fail are not repeated for 1 second, so a failing walk does not ask Baritone every tick (new in
  0.4.0-dev.16, not tested in game yet).
- **Flight can get you kicked or banned on servers that do not allow it (for example 2b2t's anticheat).** The default never
  flies.
- Limits: Baritone's path search picks the cheapest standing spot, it does not prefer printed blocks beyond that; Baritone with
  `safe-walk` cannot climb down more than one block, so after pillaring up, trips to containers (resupply, shulker boxes) from
  the top of a tall build can find no path (flying there is not done: stock up before, or let the build bring you down by
  breaking the stand blocks at the end); the stand blocks are broken from the top down, so you fall one block at a time
  while they go; flight modules that do not use the movement keys are not supported.

#### Phantom blocks
New in 0.5.0. Not tested in game yet.

- While the Printer runs, the blocks still to place are drawn as translucent ghost models of the real block, with its
  facing, slab half, stair shape and so on. A ghost goes away when its block is placed, and comes back if it is broken.
- A wrong block (where the schematic wants another one) gets an outline, or with `phantom-wrong-style` **Phantom**, the
  ghost of the right block over it. `phantom-wrong-color` (red by default) colours both.
- `phantom-blocks` (on) turns them on. `phantom-opacity` (35 per cent, from 5 to 100) sets how solid they are.
  `phantom-tint` (off) with `phantom-tint-color` multiplies a colour into the texture; the colour's alpha is not used.
- `phantom-range` (32 blocks) limits them to blocks near you. `phantom-max-blocks` (4096) caps how many are drawn, the
  nearest first.
- `phantom-current-layer-only` (on) shows only the layer being printed, when `layer-order` is not Off.
- `phantom-wrong-blocks` (on) also marks wrong blocks. `phantom-hide-with-litematica` (on) hides the ghosts while
  Litematica draws the same schematic blocks, so the schematic is not drawn twice. Litematica's wireframe alone does not hide them.
- Known limits: ghosts are not drawn for block-entity blocks (chests, signs, beds, banners, heads), they have no ambient
  occlusion, and the cap can overshoot by one distance shell.

#### Wrong blocks
`wrong-blocks` says what to do with a block that is where the schematic wants another one:
- **SkipAndList** (the default): leaves it and lists it at the end.
- **BreakAndReplace**: breaks it and builds the right block. The drops are not collected. It skips blocks next to a fluid
  block (water or lava), a falling block, a hazard or anything it can't break. A block next to a waterlogged block (slab,
  stair, kelp, seagrass) or itself waterlogged is only broken when its replacement can be placed straight after (the
  item is carried, the wanted block is not air and a placement reaches the cell from where you stand); otherwise it is
  skipped as "next to water". With `never-break-containers` on (the default, shown only with
  this mode), a block that has contents is skipped too.
- **Pause**: stops until the block is gone, then carries on by itself.

#### Materials
- `disable-when-out-of-materials` (off): off, running out of an item never turns the Printer off: it skips the blocks it
  has no item for, keeps placing the rest, and goes back to them when the item turns up (restock, stash delivery, ender
  chest); with nothing else to do it pauses and resumes by itself. On, when an item is missing and no source and no
  stash order can supply it (or a fetch just failed with no order open), the Printer turns itself off. Waiting for a stash
  order is the same either way.
- The inventory (main inventory and hotbar) is always a source. Items the build needs are moved to the hotbar when they
  are needed there.
- `keep-foreign-items` (on): items that are not on the build list stay in their slots. Protected items (weapons, food,
  potions, totems, armour, arrows, ender pearls, elytra, shulker boxes and similar) are never moved out of the hotbar.
- `free-slots` (2 by default, 1 to 20): slots kept empty when taking materials out of a container, so a shulker box can
  come back.
- `use-shulkers` (on): places a shulker box in your inventory in reach, takes what is needed out of it, mines it and picks
  it up again. Up to 6 boxes per fetch. `pickup-timeout` (20 seconds by default, from 5 to 120) is how long to mine and pick
  it up before it is left where it is and listed. It is shown while `use-shulkers` or `use-ender-chest` is on.
- `use-containers` (on) and `containers`: the containers to take from, one per line as `x,y,z|dimension` (for example
  `10,64,-20|minecraft:overworld`). Press `add-container-key` (none by default) while looking at a container to add it, or
  to remove it when it is already listed. Only storage is used: chests (trapped ones too), barrels, shulker boxes, ender
  chests, hoppers and dispensers, in the dimension they are listed for, in a loaded chunk and within 128 blocks of you.
  Anything else is refused with a message when the printer is about to open it.
- `use-ender-chest` (off): takes materials out of the ender chest you carry, in this order:
  1. The printer places the chest, and takes out the shulker boxes that hold materials it needs, and loose stacks of
     them. Up to 6 boxes per fetch, the same limit as `use-shulkers`.
  2. The boxes are unpacked, as with `use-shulkers`.
  3. The printer places the chest again, puts the emptied or leftover boxes back, and mines the chest with silk touch.

  It needs an ender chest in your inventory or hotbar, and a silk touch pickaxe unless `ender-chest-no-silk` says
  otherwise. After a trip that found none of the items it needs, the chest is not tried again for those items for 10
  minutes.
- `ender-chest-no-silk` (**Refuse** by default, or **MineAnyway**; shown while `use-ender-chest` is on): what to do without
  a silk touch pickaxe. **Refuse** does not use the chest. **MineAnyway** mines it with any pickaxe, and the chest block is
  then lost (it drops 8 obsidian). The items inside stay safe on the server, but the boxes can't be put back: they stay in
  your inventory, and the printer says how many.
- The boxes are told apart by their item and components. In rare cases a different box of the same kind goes back into the
  chest. The count is right and nothing is lost.
- The Stash Window's local index is no longer a source (`use-stash-index` was removed). For stock from the stash, use
  `order-from-stash` below.
- A full inventory pauses the printer with a message until a slot is free, when there is a source to fetch from.
- Before a materials, shulker or ender chest trip, a flying player lands first: a flight module the Printer switched on is
  switched off on the ground. With no solid ground or water below, the trip is skipped with a message. New in 0.4.0-dev.16,
  not tested in game yet.

#### Stash orders
Off by default. With `order-from-stash` on, the printer orders what it is running low on from the Stash Manager server,
from the stash bots on this game server that have it. Nothing is asked of you: the orders are placed by themselves, with no
confirmation. The bot delivers into its delivery chest, and you collect it. Stock is taken from the bots, not from the
Stash Window's local index.

- `order-from-stash` (off): turns it on. It needs the **Stash Window** module with `connect-to-server` on, a server address
  set there, and a logged-in account with the order permission. When one of these is missing, the chat says which, once.
  If the server asks for your password again, the printer says so and waits 5 minutes; it never asks for the password
  itself.
- `order-when-below` (64 by default, from 1 to 2304; the slider goes from 16 to 576): an item is ordered when you carry
  fewer than this many of it (or fewer than the build still needs, when that is less), and what is already on order or
  waiting to be collected does not cover the rest. So orders go ahead of need.
- `order-max-shulkers` (9 by default, from 1 to 54): the most shulker boxes one order may need. The server takes at most 54.
- `order-max-shulkers-per-run` (27 by default, from 1 to 540; the slider goes from 1 to 108): the most shulker boxes all
  orders of one build may need together. The count starts again when you turn the module on, when you leave the world, and
  when the source, file name, origin, rotation or mirror changes. A reload keeps it.
- `craft-missing-from-stash` (on by default; it shows only while `order-from-stash` is on): uses stash materials to craft
  what no stash holds. When no stash holds an item the printer needs, but a stash can make it from what it does hold
  (logs for planks and stairs, for example), the printer orders it crafted. The stash bot takes the ingredients from the
  stash, crafts them at that stash's crafting table, and delivers the result with the order. This spends stash materials
  without asking, and the chat says what was crafted from what. It needs a crafting table set for the stash on the Stash
  Manager website and a bot that can carry out craft orders. Without them, or on an older server, the chat says so once
  and only what the stash holds is ordered. Off: only items the stash holds are ordered.

How orders are placed:
- Orders go to the stash bots on this game server that have the items, online bots first, and at most 12 stashes are asked
  in one round. A stash that already has an open order of yours is not asked again, and a stash with 20 open orders (the
  server's limit) is skipped. With a forced-streamer account, orders go only to the stash selected in the Stash Window.
- Each order is cut to the limits above: whole stacks, plus one partial stack when a slot is left.
- Nothing already covered is ordered again. Open order lines count as covered, and so do delivered lines you have not
  collected yet. A delivery is assumed to cover what was ordered until it is collected or 20 minutes have passed, so after
  a partial delivery, a re-order for the rest can wait up to 20 minutes.
- Crafting (with `craft-missing-from-stash`): the server's plan is asked for up to 3 stashes, the ones that hold the items
  first. Its craftable amounts go into the same order as craft lines, next to the item lines, and count as supply on
  their way, like item lines. Crafted items count toward `order-max-shulkers` and `order-max-shulkers-per-run`.
- An item is never ordered twice. A stash whose delivery left some of a crafted item uncrafted is left out of crafting
  that item for 10 minutes. An item whose order was cancelled is not ordered or crafted again this run, and one that
  failed at a stash is not asked of that stash again this run.
- A stash that is busy (rate limited or unreachable) is asked again later. Items a busy stash may hold wait up to 3
  minutes; the others are crafted at once.
- After three 503 answers from the server for one order, that order goes ahead without crafting, and that stash is left
  out of crafting for 5 minutes. A stash the server doesn't know is left out alone; crafting stays on for the others.
- One chat line when an order is placed, for example `Ordered 2 shulkers [3 kinds, 150 items] from stash NAME @ SERVER
  [order ID].` (the server is left out in streamer mode). With crafting, the line reads like
  `Ordered 64 Oak Planks + crafting 8 Spruce Stairs (from 2 Spruce Log) [2 shulkers] from stash NAME @ SERVER [order ID].`
  One more when it is delivered, fails, is cancelled or is no longer listed on the server.

Out of materials:
- The printer keeps building with what it carries until it is out of the next block's material. Then it pauses:
  `Out of ITEM. Waiting for the order from stash STASH: collect the delivery and put it in your inventory. Printing
  resumes by itself.`
- It never walks to the delivery chest. You collect the delivery and put it in your inventory, and printing resumes by
  itself.
- Other waits are "your open stash order(s) to finish" and "the stash order to be placed". When stash orders can't help,
  the pause says why, for example "no stash bot for this server has it" or "order-max-shulkers-per-run was reached".

#### Baritone
- `walk` (on) lets Baritone walk to blocks that are out of reach and to containers. It needs
  [dekrom's Baritone fork](https://github.com/dekrom/baritone/releases). Off: only what is in reach is built.
- While Baritone walks it never breaks blocks. It places only your spare blocks, and only on the building walk with
  `movement` StandOnBuild (see Moving to tall and many-layer builds above); trips for containers and shulker boxes walk
  without placing. `safe-walk` (on) keeps the walk
  away from parkour, diagonal drops and falls of more than one block. Everything is given back when the run ends.
- The printer never stands in a block it has yet to build.
- It pauses while Baritone is busy with something else, and while you press a movement key (`pause-on-input`, on).
- It won't walk while another module has confined Baritone.

#### Controls and leftovers
- `reload-key` (none by default) loads the schematic again and starts over. Blocks already built are noticed and skipped.
- `cancel-key` (none by default) stops, puts everything back (sneaking, Baritone and your selected hotbar slot) and turns
  the module off.
- When the run ends, the chat says how many blocks were placed, already there, failed, skipped as wrong, not buildable
  and left unbuilt, and lists them.
- At the end it also reports, per tool, how many blocks were finished and which were left (Unstripped, Not flattened or
  doused, Not tilled, Not waxed, Not carved, Not lit, Pots without plant).
- Finished cells are looked at again later (about 5 seconds after, then every 10 seconds while they are within reach, at
  most three looks per run; new in 0.4.0-dev.16, not tested in game yet): one that
  became wrong, empty or dry goes back in the queue, and a log, candle or farmland block that needs its tool again gets it.
- Sounds (own group, new in 0.4.0-dev.16, not tested in game yet): `finish-sound` and `error-sound` (both on; with
  `finish-sound-event`, `error-sound-event` and `sound-volume`) play when the run finishes, when it stops, and when it pauses
  because it needs you (stuck, no materials source, a failed fetch). At most one sound every 1.5 seconds.
- Blocks can be placed from the off hand (new in 0.4.0-dev.16, not tested in game yet).
- Changing `wrong-blocks` in the middle of a run looks again at the cells that were skipped as wrong (new in 0.4.0-dev.16,
  not tested in game yet).
- The Litematica layer mode is logged when it is read and when it changes.
- A pause or a resume is announced only once it has held for 1 second, so a flapping condition says nothing. "Stuck",
  "Layer done" and "could not get the materials" are announced at once. A pause that must stop walking (inventory full,
  Baritone busy, walking off) stops it at once; only its message waits. Work that goes on again shows as building at once
  (new in 0.4.0-dev.16, not tested in game yet).
- "Your inventory is full" is only said when there is a source to fetch from (new in 0.4.0-dev.16, not tested in game yet).
- A warning is given once per run when another printer, kill aura or crystal aura is on, since they fight over your look and hotbar (new in 0.4.0-dev.16, not tested in game yet).
- The Printer's rotations go out at priority 100 with the look set on your client too, so another module that rotates in the same tick does not take the look from a click (waterlogging with a bucket failed that way). New in 0.4.0-dev.16, not tested in game yet.
- A shulker box that couldn't be picked up is listed as **Left behind** with its coordinates when you next enter that
  world. The entry goes away once the box and its drop are gone. An ender chest that was left in the world is listed the
  same way.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft **1.21.11** or **1.21.4**.
2. Put [Meteor Client](https://meteorclient.com) for the same Minecraft version in your `mods` folder.
3. Download the `qwerte-addon` jar for your Minecraft version from the
   [releases page](https://github.com/Qwertze/Qwertze/releases) (this build is the pre-release `v0.6.0`, see
   [Versioning](#versioning) and [Dev builds](#dev-builds)): `qwerte-addon-0.6.0+1.21.11.jar` or `qwerte-addon-0.6.0+1.21.4.jar`.
   Put it in your `mods` folder. It needs Stash Manager server 1.1.0 or newer for kit v2, and works alongside Stash Sorter plugin 1.17.0.
4. *(Optional, for auto-walk and refilling)* Download `baritone-api-fabric-1.3.0-<minecraft>.jar` for your version from
   [dekrom's Baritone releases](https://github.com/dekrom/baritone/releases) (for example
   [1.21.11](https://github.com/dekrom/baritone/releases/tag/v1.3.0-1.21.11) or
   [1.21.4](https://github.com/dekrom/baritone/releases/tag/v1.3.0-1.21.4)) and put it in your `mods` folder. Only have
   one Baritone jar installed at a time.
5. Start the game. Qwerte shows up under Meteor in Mod Menu, and all of its modules are in
   Meteor's **Qwerte** category.

> **On 1.21.4:** Baritone for 1.21.4 has no setting to stop it placing blocks in water, so Qwerte can't switch that off
> during trips. Filling buckets from the bank still keeps pools intact; just keep an eye on your pool the first time.

> **Updating:** close Minecraft before replacing the jar, and delete the old one. Swapping a mod's jar while the game is
> running can crash it later.

## Versioning

Qwerte uses `MAJOR.MINOR.PATCH` versions:

- **x.0.0**: major breaking changes.
- **x.y.0**: new features, bug fixes and more.
- **x.y.z**: minor bug fixes, single one-off patches and hotfixes.

The `0.4.0-dev.N` counting has ended. 0.5.0 is the first release under this scheme. 0.5.x releases are fixes, 0.6.0 is the
next feature batch, and 1.0.0 will mark the first stable release.

**Compatibility:** Qwerte **0.6.0** needs [Stash Manager server](stash-manager-server/README.md) **1.1.0** or newer for kit v2
(**1.2.0** is current), and works alongside the Stash Sorter plugin **1.17.0** (see
[Stash Sorter for AquariusProxy](https://github.com/Qwertze/Stash-Sorter)).

## Dev builds

New features are tested on the [`dev`](https://github.com/Qwertze/Qwertze/tree/dev) branch before they reach `main`.
Each round of testing gets a **pre-release** on the [releases page](https://github.com/Qwertze/Qwertze/releases), named
by its version, like `v0.6.0`. These are unofficial: they may be buggy and settings may change. Use the latest regular release
unless you're helping test.

## Setup guide

Open Meteor's click GUI (Right Shift by default), go to **Qwerte** and open **auto-till**.

Out of the box, auto-till waters, plants and walks with Baritone. Each step below says which setting controls that part.

### Just tilling
Turn off **auto-walk**, **auto-water** and **auto-plant**, then turn the module on with a hoe in your hotbar and walk
around. The purple boxes are the blocks it's about to till.

### Farming with water
1. Leave **auto-water** on in the **Water** group.
2. Carry a water bucket, plus a shovel if you want the holes dug faster.
3. Stand in a dry, flat patch. A magenta box marks where it digs the hole. Once it's filled, the land around it gets
   tilled.

### Planting
1. Leave **auto-plant** on in the **Planting** group and pick your crops in **seeds**.
2. Leave **bone-meal** on to grow them instantly while you have bone meal.

### Seed restocking
1. Turn on **seed-restock** and set **restock-at** (default 1; 0 means restock only once you've run out).
2. Pick a **restock-mode**:
   - **Storage:** turn on **pick-storage** and right-click each seed chest you want to use. Right-click one again to
     remove it. The storages are listed under the module's settings. If one is out of reach you'll need auto-walk on.
   - **Ender Chest:** keep an ender chest and a silk touch pickaxe on you, with a shulker of seeds stored in your ender
     chest. Keep at least 2 inventory slots free. If Meteor has already seen inside your ender chest, Qwerte checks for
     a seed shulker before placing anything; otherwise it checks once the chest is open.

### Full automation with Baritone
1. Install dekrom's Baritone (see [Installation](#installation)).
2. Leave **auto-walk** on in the **Automation** group and set **search-radius** (default 64) to the size of the area
   to farm. It's measured from where you turn the module on.
3. Turn on **refill-water** and choose a **refill-source**:
   - **Nearest Pool:** finds the closest 4x4 water source within **water-search-radius**.
   - **Station:** turn on **pick-station** and right-click a water block, or type its coordinates into **station**.
4. Leave **combine-trips** on to restock seeds and refill water on the same trip. Raise **seed-top-up-at** if you
   want it to top up seeds more eagerly while it's out.
5. Carry a hoe, a few empty buckets, a water bucket, seeds and bone meal, then turn the module on and let it work.

### Covering ground with dirt
1. Open **auto-dirt** in the **Qwerte** category.
2. Carry dirt (or the block picked in **dirt-item**) and turn the module on. The brown boxes are the spots it's about
   to fill.
3. To restock, turn on **restock** in the **Restock** group and pick a **restock-mode**, set up the same way as seed
   restocking. Turn on **walk-to-storage** if your dirt storage is out of reach.
4. Turn on **auto-till** too to till the new dirt as it goes down.
5. *(Optional, needs Baritone)* Turn on **auto-walk** in the **Automation** group and set **search-radius** to how far
   from where you start to look for ground to cover.

### Sorting a stash
1. Empty some inventory slots, or set `carry-slots`. The more slots, the fewer trips.
2. For **Signs** mode, put signs on your containers saying what goes in each, in your own words.
3. *(Optional)* Set `pick` to **Exclude** or **Drop-off** and right-click containers to mark them, then set it back to
   **Off**.
4. Stand among the containers, open **stash-sorter** in the **Qwerte** category, name the stash in `stash-name`, pick
   a `mode` and turn it on. The first run reads every container, later runs go straight to sorting.
5. Turn on `stay-on` to keep it running as a sorting bot.
6. Search the stash anytime from the module's settings.
7. Turn on **stash-overlay** to see what each container holds.
8. *(Optional)* To build kits, save them in the **Kits** section of the module's settings, put a chest signed with each
   kit's name, then turn on `build-kits` in Signs mode. Saved kits that are also Kit Builder templates are skipped here.
9. *(Optional, not tested in game yet)* For kit sorting, link the addon to your Stash Manager server (see
   `stash-manager` above), then pick a kits chest on the website or in the Stash Window's Kits tab, a chest for each kit
   type you want, and a chest for any
   kit name you want its own. `kit-sorting` is on by default.

## Settings reference

The defaults are a tuned setup that works well on 2b2t, so most people only need to set their seed storage or refill
station. If you'd rather work hands-on, turn off `auto-walk`, `auto-water` and `auto-plant`.

### auto-till

| Group | Setting | Default | Description |
|---|---|---|---|
| General | `reach` | 4.5 | Max distance from your eyes to a block's center. |
| General | `delay` | 2 | Ticks to wait between batches. |
| General | `blocks-per-tick` | 7 | How many blocks to work on per batch. |
| General | `blocks` | Grass Block, Dirt, Dirt Path, Coarse Dirt | Blocks to turn into farmland. Coarse dirt is hoed into dirt first. |
| General | `only-hydrated` | off | Only till blocks that would be hydrated. |
| General | `auto-switch` | on | Switch to the right item from your hotbar. |
| General | `swap-back` | on | Switch back to your previous slot afterwards. |
| General | `rotate` | off | Face blocks while working on them. Placing water, ice and glowstone always rotates. |
| Planting | `auto-plant` | on | Plant seeds on empty farmland. |
| Planting | `seeds` | Wheat Seeds | Which seeds to plant. |
| Planting | `bone-meal` | on | Bone meal crops until fully grown. |
| Planting | `seed-restock` | off | Restock seeds when low. |
| Planting | `restock-mode` | Storage | Storage or Ender Chest. |
| Planting | `restock-at` | 1 | Restock at this many seeds or fewer. |
| Planting | `seed-storages` | empty | The containers to restock from, managed with `pick-storage` and the list in the module's settings. |
| Planting | `pick-storage` | off | Right-click a container to add it as a storage, or remove it if it's already one. |
| Planting | `bone-meal-restock` | off | Restock bone meal when low. |
| Planting | `bone-meal-restock-mode` | Storage | Storage or Ender Chest. |
| Planting | `bone-meal-restock-at` | 4 | Restock at this much bone meal or less. |
| Planting | `bone-meal-storages` | empty | The containers to restock bone meal from, managed with `pick-bone-meal-storage`. |
| Planting | `pick-bone-meal-storage` | off | Right-click a container to add it as a bone meal storage, or remove it if it's already one. |
| Water | `auto-water` | on | Dig and fill water holes for dry land. |
| Water | `water-source` | Bucket | What fills the holes. Bucket pours a water bucket. Ice places an ice block in the hole and breaks it, which turns it into water (needs a solid block under the hole, and doesn't work in the Nether or in creative). |
| Water | `fill-holes` | on | Fill empty 1x1 holes that would water dry land, even while walking. |
| Water | `water-side-color` / `water-line-color` | magenta | Colors of the planned water hole. |
| Water | `light-holes` | off | Put a glowstone block above each water hole this module fills. Glowstone comes from your inventory. |
| Water | `light-air-place` | on | Place that glowstone by clicking the empty spot above the hole when no block is next to it. Turn off on servers that reject or flag air placing. |
| Water | `glowstone-restock` | off | Restock glowstone when low. |
| Water | `glowstone-restock-mode` | Storage | Storage or Ender Chest. |
| Water | `glowstone-restock-at` | 1 | Restock at this much glowstone or less. |
| Water | `glowstone-storages` | empty | The containers to restock glowstone from, managed with `pick-glowstone-storage`. |
| Water | `pick-glowstone-storage` | off | Right-click a container to add it as a glowstone storage, or remove it if it's already one. |
| Ice | `ice-restock` | off | Restock ice when low. Used with `water-source` set to Ice. |
| Ice | `ice-restock-mode` | Storage | Storage or Ender Chest. |
| Ice | `ice-restock-at` | 2 | Restock at this much ice or less. |
| Ice | `ice-storages` | empty | The containers to restock ice from, managed with `pick-ice-storage`. |
| Ice | `pick-ice-storage` | off | Right-click a container to add it as an ice storage, or remove it if it's already one. |
| Hotbar | `auto-hotbar` | on | Keeps the hoe and the items you need in your hotbar: pulls them out of your inventory when missing, and tops up seeds, bone meal, ice and glowstone when the stack runs low. Never drops anything. Protected items (weapons, totems, food, ender pearls and the like) are never moved out of the way. |
| Hotbar | `hoe-slot` | 0 | The hotbar slot (1-9) to keep the hoe in. 0 uses any free slot. |
| Hotbar | `seed-slot` | 0 | The hotbar slot (1-9) to keep the seeds in. 0 uses any free slot. |
| Hotbar | `bone-meal-slot` | 0 | The hotbar slot (1-9) to keep the bone meal in. 0 uses any free slot. |
| Hotbar | `water-bucket-slot` | 0 | The hotbar slot (1-9) to keep the water bucket in. 0 uses any free slot. |
| Hotbar | `ice-slot` | 0 | The hotbar slot (1-9) to keep the ice in. 0 uses any free slot. |
| Hotbar | `glowstone-slot` | 0 | The hotbar slot (1-9) to keep the glowstone in. 0 uses any free slot. |
| Automation | `auto-walk` | on | Walk to more land with Baritone. Turns itself off if Baritone isn't installed. |
| Automation | `refill-water` | on | Walk to water and refill empty buckets. Bucket mode only. |
| Automation | `refill-source` | Nearest Pool | Nearest Pool or Station. |
| Automation | `station` | 0, 0, 0 | The water block to refill at. |
| Automation | `pick-station` | off | Right-click a water block to set it as the station. |
| Automation | `water-search-radius` | 96 | How far to look for a 4x4 pool. |
| Automation | `work-pattern` | Nearest | The order to cover land in when nothing is left in reach: Nearest, Rows, Spiral Out or Spiral In. |
| Automation | `row-direction` | Auto | Which way the Rows lanes run: Auto, North-South or East-West. Auto uses the way you were facing when you turned the module on. |
| Automation | `lane-width` | 0 | How wide each lane or ring is in blocks. 0 works it out from your reach. |
| Automation | `search-radius` | 64 | How far from your start point to look for land. |
| Automation | `search-height` | 1 | How far up and down to look for land. |
| Automation | `combine-trips` | on | Restocks seeds, ice, bone meal and glowstone on the way between trips, and refills empty buckets on the way back from restocking. |
| Automation | `seed-top-up-at` | 128 | On a water trip, restock seeds if you have fewer than this. |
| Automation | `ice-top-up-at` | 64 | When combining trips, restock ice if you have fewer than this. |
| Automation | `bone-meal-top-up-at` | 64 | When combining trips, restock bone meal if you have fewer than this. |
| Automation | `glowstone-top-up-at` | 32 | When combining trips, restock glowstone if you have fewer than this. |
| Safety | `anti-break` | on | Don't use hoes that are about to break. |
| Safety | `min-durability` | 10 | Uses left at which a hoe counts as about to break. |
| Safety | `disable-when-out` | on | Turn off when you have no usable hoe left. |
| Safety | `hoe-restock` | off | Restock hoes when low. |
| Safety | `hoe-restock-mode` | Storage | Storage or Ender Chest. |
| Safety | `hoe-restock-at` | 0 | Restock at this many usable hoes or fewer. 0 restocks once you have none left. |
| Safety | `hoe-restock-amount` | 2 | How many hoes to take from each storage visited. |
| Safety | `hoe-storages` | empty | The containers to restock hoes from, managed with `pick-hoe-storage`. |
| Safety | `pick-hoe-storage` | off | Right-click a container to add it as a hoe storage, or remove it if it's already one. |
| Render | `render` | on | Highlight blocks that will be tilled. |
| Render | `shape-mode` | Both | How the highlight is drawn. |
| Render | `side-color` / `line-color` | purple / pink | Colors of the highlight. |

### auto-dirt

| Group | Setting | Default | Description |
|---|---|---|---|
| General | `reach` | 4.5 | Max distance from your eyes to the spot to fill. |
| General | `delay` | 1 | Ticks to wait between batches. |
| General | `blocks-per-tick` | 6 | How many blocks to place per batch. |
| General | `dirt-item` | Dirt | Dirt, Coarse Dirt, Rooted Dirt or Grass Block. |
| General | `below-feet-only` | on | Only cover ground below your feet, so walls and ledges are left alone. |
| General | `depth` | 1 | How many blocks below your feet to look for ground. Shown with `below-feet-only`. |
| General | `skip-own-column` | on | Leave the block you're standing on uncovered. |
| General | `ignore` | chests, trapped chests, ender chests, barrel, crafting table, furnace | Blocks never covered. |
| General | `rotate` | off | Face the block you're placing against. |
| General | `swap-back` | on | Switch back to your previous slot afterwards. |
| General | `disable-when-out` | on | Turn off when out of dirt and unable to restock. |
| Restock | `restock` | off | Restock dirt when low. |
| Restock | `restock-mode` | Storage | Storage or Ender Chest. |
| Restock | `restock-at` | 0 | Restock at this much dirt or less. |
| Restock | `dirt-storages` | empty | The containers to restock from, managed with `pick-storage` and the list in the module's settings. |
| Restock | `pick-storage` | off | Right-click a container to add it as a storage, or remove it if it's already one. |
| Restock | `walk-to-storage` | off | Walk to an out-of-reach storage with Baritone, then walk back. |
| Hotbar | `auto-hotbar` | on | Keeps dirt in your hotbar: pulls it out of your inventory when it's missing, and tops the stack up when it runs low. Never drops anything. |
| Hotbar | `dirt-slot` | 0 | The hotbar slot (1-9) to keep the dirt in. 0 uses any free slot. |
| Automation | `auto-walk` | off | Walk to more uncovered ground with Baritone. Turns itself off if Baritone isn't installed. |
| Automation | `work-pattern` | Nearest | The order to cover ground in when nothing is left in reach: Nearest, Rows, Spiral Out or Spiral In. |
| Automation | `row-direction` | Auto | Which way the Rows lanes run: Auto, North-South or East-West. |
| Automation | `lane-width` | 0 | How wide each lane or ring is in blocks. 0 works it out from your reach. |
| Automation | `search-radius` | 64 | How far from where you enabled the module to look for ground to cover. |
| Automation | `search-height` | 1 | How many blocks above and below you to search. With `below-feet-only` on, `depth` is used below you instead. |
| Render | `render` | on | Highlight the spots dirt will be placed in. |
| Render | `shape-mode` | Both | How the highlight is drawn. |
| Render | `side-color` / `line-color` | brown | Colors of the highlight. |

### stash-sorter

| Group | Setting | Default | Description |
|---|---|---|---|
| General | `stash-name` | stash | The name the stash is saved under. |
| General | `sort-items` | on | Off is scan only: keeps the saved stash current but never moves items. |
| General | `safe-walk` | on | While the sorter walks, Baritone never parkours or falls more than one block, and the sorter sneaks at ledges with a drop of 2 or more blocks. |
| General | `mode` | Tiers | Tiers or Signs. |
| General | `pick` | Off | Right-click to mark containers: Off, Exclude, Drop-off, Compact skip, Area corner 1 or Area corner 2 (the clicked block becomes that corner of the stash perimeter). |
| General | `radius` | 8 | How far around you to look for new containers. |
| General | `height` | 3 | How far above and below you to look. |
| General | `barrels` | on | Include barrels as well as chests. |
| General | `reach` | 4.5 | How close a container has to be to open it. |
| General | `delay` | 3 | Ticks between inventory clicks. Raise it if the server rejects clicks. |
| General | `container-delay` | 0 | Extra ticks of pause around opening containers: none is opened until this many ticks after the last one was closed, and the first click in a freshly opened one waits this long too. Higher is slower but gentler on servers (2b2t can kick or rubber-band when containers are opened very quickly). 0 is as fast as possible. |
| General | `quiet` | 3 | Seconds to wait after you or another module stops using the client before carrying on. Doubles for each repeated interruption within 5 minutes, up to 60 seconds. |
| General | `walk` | off | Walk to containers out of reach with Baritone. |
| General | `rotate` | on | Face containers when opening them. |
| General | `unsigned-overflow` | on | Signs mode: items with nowhere to go, and no chest signed misc, other or overflow, go to a chest with no sign at all (one already in use first). Never a chest signed for something else. |
| General | `kit-sorting` | on | Send shulker boxes that are kits (by type or template) to the chests picked for them on the Stash Manager website or in the Stash Window's Kits tab (see Kit sorting). Off: kits sort like any shulker and nothing about kits is sent. The settings come from the Stash Manager server; the last ones it sent are kept while it is offline. |
| General | `log-level` | Normal | Quiet: now/next lines, errors and alerts (alerts are also whispered to the whitelist). Normal: the usual messages. Debug: also why it decides what it does. Replaces `debug`. |
| Bot | `stay-on` | off | Keep running after sorting, as a sorting bot. |
| Bot | `recheck` | 30 | Seconds between drop-off checks (the shortest wait with `smart-recheck`). |
| Bot | `smart-recheck` | on | Only check drop-offs that can change unseen (hopper, dropper or dispenser next to or above, or unloaded), waiting longer, up to 5 minutes, while nothing is found. Off checks every drop-off every `recheck` seconds. |
| Ground | `collect-ground` | on | Between jobs, picks up items other players left lying in the stash area, when no other player is loaded. Needs Baritone. Never your own drops or items from blocks you broke. |
| Ground | `collect-range` | 8 | Blocks around the outermost containers to pick up from (and 4 up and down). |
| Labels | `auto-label` | on | Signs mode: puts a sign on an empty, unsigned chest next to the signed ones for items nothing is signed for. Never places or breaks a chest, never breaks a sign. |
| Labels | `label-gap` | 10 | Seconds to wait between putting up signs. |
| Watch | `watch` | on | Notices when another player opens and closes a stash container, and reads it again after `watch-delay`. Works with the module off. |
| Watch | `watch-delay` | 5 | Seconds a container has to stay closed after someone else used it before it's read again. |
| Watch | `watch-signs` | on | Signs mode, while sorting: notices when a sign's text changes, and sorts by the new text after `sign-delay`. |
| Watch | `sign-delay` | 10 | Seconds a changed sign is left alone before the stash is sorted by what it says now. |
| Shulkers | `compact` | on | Signs mode, once everything is sorted: packs 27 full stacks of one item (things that don't stack, from 2 pieces) into an empty unnamed shulker and files it in the item's chest. |
| Shulkers | `compact-top-up-at` | 9 | Tops up a part-full shulker of one item once this many full stacks of it are waiting. |
| Shulkers | `compact-exclude` | empty | Items that are never compacted, like ender pearls. |
| Shulkers | `repack` | on | Signs mode: clean mixed shulkers and pack loose items into bulk shulkers. |
| Shulkers | `new-bulk-at` | 9 | Start a new bulk shulker from an empty one at this many stacks of one item. 0 only tops up existing ones. Only used while `compact` is off. |
| Kits | `build-kits` | off | Signs mode only: build the saved kits out of the stash, into chests signed with the kit's name. Saved kits whose name is a Kit Builder template are skipped (from 0.6.0). |
| Slots | `carry-slots` | blank | Slots to carry items in. Blank uses every slot that's empty when sorting starts. |
| Slots | `keep-slots` | blank | Slots never to touch. |
| Search | `whisper-search` | off | Let whitelisted players search by whisper. |
| Search | `whitelist` | empty | Who can search by whisper. |
| Search | `prefix` | find | Whispers starting with this are searches. |
| Render | `render` | on | Highlight the container being worked on, and marked containers. |
| Render | `shape-mode` | Both | How the highlight is drawn. |
| Render | `area-color` | green | The outline of the stash perimeter. |
| Render | `work-color` / `excluded-color` / `compact-skip-color` / `drop-off-color` | orange / red / purple / blue | Highlight colors. |

### stash-overlay

| Setting | Default | Description |
|---|---|---|
| `size` | 3 | How big the icons are, at 6 blocks away. |
| `max-grow` | 2.5 | How much bigger icons can grow as you get close. |
| `min-shrink` | 0.35 | How small icons can shrink far away. |
| `height` | 0.5 | Where on the container the icon sits: 0 bottom, 0.5 middle, 1 top. |
| `shulker-contents` | on | For containers of shulkers, show what the shulkers hold. |
| `show-count` | off | Show how many of the item the container holds. |
| `hide-excluded` | off | No icons on containers excluded from sorting. |

## Building from source

You need **JDK 21** or newer. Everything else, including Minecraft, Meteor and the Baritone API, is downloaded by Gradle.

```sh
git clone https://github.com/Qwertze/Qwertze.git
cd Qwertze
./gradlew build              # Minecraft 1.21.11
./gradlew build -Pmc=1.21.4  # Minecraft 1.21.4
```

On Windows use `gradlew.bat` instead of `./gradlew`. The jar ends up in
`build/libs/qwerte-addon-<version>+<minecraft>.jar`.

To test in a development client, run `./gradlew runClient`, adding `-Pmc=1.21.4` for 1.21.4. Baritone isn't included
there; add its jar to `run/mods` if you want to try the automation. Litematica isn't included either; add its jar to
`run/mods` to try the Printer with a Litematica placement.

### Project layout

```
src/main/java/com/porter/qwerte/
├── QwerteAddon.java          Addon entrypoint: the Qwerte category, the ten modules and the stash-window command
├── commands/
│   └── StashWindowCommand.java  The .stash-window (and .stash) command
├── modules/                  The Meteor modules, and the helpers they use (the ten modules are registered in QwerteAddon)
│   ├── AutoTill.java         The auto-till module: tilling, watering, planting, automation
│   ├── AutoDirt.java         The auto-dirt module: covering ground with dirt
│   ├── StashSorter.java      The stash-sorter module: sorting, kits, bot mode, picking, search
│   ├── StashOverlay.java     The stash-overlay module: an icon of each container's main item
│   ├── StashManager.java     The stash-manager module: sends the index and the kit report to the Stash Manager server, takes its orders and kit settings
│   ├── ResetPearl.java       The reset-pearl module: climbs out of a stasis chamber and re-arms it
│   ├── Messenger.java        The messenger module: the whisper window, its patterns, sending and history
│   ├── StashWindow.java      The stash-window module: the in-game Stash Window and its local index
│   ├── ContainerTweaks.java  The container-tweaks module: shulker previews and icons, Smart Shulker and Smart Echest
│   ├── Printer.java          The printer module: builds a schematic block by block, fetching or ordering its materials
│   ├── PrinterActions.java   The printer's clicks: placing, breaking, sneaking, checking blocks and quick-moves out of containers
│   ├── FlyController.java    The printer's flight: flies with a flight module to a hover point, and lands before trips (a helper, not a module)
│   ├── SmartPlaceSession.java  The shulker box session (place, open, take, mine, pick up), shared by Container Tweaks and Printer
│   ├── OrderRunner.java      Carries out a server order for stash-sorter: plans, builds and delivers the shulkers
│   ├── LocalOrders.java      Carries out the window's own orders with this client as the bot (no server)
│   ├── KitBuilder.java       Building saved kits out of the stash, for stash-sorter
│   ├── Repairer.java         Repairing an order's items with XP bottles, for stash-sorter (a helper, not a module)
│   ├── Compactor.java        Packs a full shulker's worth of one item into a shulker, for stash-sorter
│   ├── Crafter.java          Crafts plain shulker boxes for stash-sorter
│   ├── ChestPlacer.java      Puts up the extra chests of the delivery storage
│   ├── StashLabeller.java    Puts up signs for new categories, for stash-sorter
│   ├── GroundCollector.java  Picking up items left on the ground in the stash area, for stash-sorter
│   ├── StashYield.java       Tells when someone else is using the client, so the sorter gives way
│   ├── Restocker.java        Storage and ender chest restocking, shared by auto-till and auto-dirt
│   └── StorageList.java      The list of storages a module restocks from, in the order it tries them
├── stash/                    The saved stash, its index, the orders and the repair rules
│   ├── Stash.java            A saved stash: its containers and contents, saving, loading and search
│   ├── StashIndex.java       Builds the index the stash-manager module sends to the server
│   ├── IndexJson.java        The JSON shape of an index's items and shulkers
│   ├── Categories.java       What sign words and searches mean: categories, slang and synonyms
│   ├── Containers.java       Where a container's signs can be, and what they say
│   ├── Kit.java              A saved kit: 27 slots, and how to tell if a shulker is finished or part built
│   ├── Kits.java             The saved kits of the current server, saved and loaded from disk
│   ├── KitItems.java         The shared kit-items table (kit-items.json): the OP items and the item-mix groups
│   ├── KitTypes.java         The shared kit-types table (kit-types.json): the seven kit types, their words and item types
│   ├── KitClassifier.java    Works out a box's kit type, or the reason it isn't a kit (templates, Treat as, types)
│   ├── KitTemplate.java      One kit template, checked: its name, category, chest and 27 slots
│   ├── KitTemplates.java     Matches a box against the templates: complete and incomplete copies
│   ├── KitNames.java         Turns a shulker's custom name into a kit key, and says whether it is a kit
│   ├── KitScore.java         The kit score of one shulker box: name, mix, full and OP points, and the threshold (older bots)
│   ├── KitRouting.java       Where a kit shulker goes: along its chain (name rule, template, type, kits chest)
│   ├── KitSettings.java      One stash's kit settings from the Stash Manager server, checked whole (or refused)
│   ├── KitStore.java         The kit settings kept per server and stash (kit-settings.json), for when the server is offline
│   ├── KitReport.java        The kit report sent to the Stash Manager server: containers, kits and carried kits
│   ├── Order.java            An order from the server: its lines, and the packer into shulker loads
│   ├── Mending.java          Whether a stack has the Mending enchantment (by registry key)
│   ├── RepairEstimate.java   The repair rules of the order contract: the formula, the reasons and the estimate
│   ├── RepairRun.java        The pure parts of repairing: batches, the throwing guard and the result
│   ├── DeliveryBook.java     The delivery storage: its container and the chests put up above it
│   ├── DeliveryContents.java The delivery storage's contents, as the Stash Manager server gets them
│   ├── DeliveryRoom.java     What the delivery storage can take now
│   ├── SpaceWait.java        An order waiting for room in the delivery storage
│   ├── StackInfo.java        What the sorter knows about one stack
│   ├── StashSafety.java      Guards against stash data that points far away
│   ├── StashWatch.java       Notices other players' changes to containers and signs
│   ├── ItemPreviewScreen.java  Shows a found item in a look-only container screen
│   └── KitPreviewScreen.java   Shows a saved kit in a look-only container screen
├── manager/                  The Stash Window: gui/ (the screen, tabs such as KitsTab and BuilderTab, and widgets), model/ (its data and rules, such as KitsModel, KitsV2 and BuilderModel),
│                             net/ (the connection to the Stash Manager server), local/ (the local index and orders),
│                             map/ (the live map)
├── pearl/                    Reset Pearl's parts: the chamber, the shaft scan, the trapdoor clicks, the sequence and the pearl's rest check (PearlRest)
├── messenger/                The Messenger's parts: whisper patterns and chat lines (DmFormat, DmParser, ChatLines, UserPatterns), conversations
│                             and their history (ConversationStore, ServerStores, HistoryJson), sending (SendText, Outbox), emoji (EmojiSet,
│                             EmojiSegmenter, EmojiPixels, ImageScale), and the window's layout and text (MessengerCore, MessengerUi, TextLayout).
│                             The window itself is in src/<minecraft>/java/com/porter/qwerte/messenger/; tests are in src/test/java/com/porter/qwerte/messenger/
├── tweaks/                   Container Tweaks' parts: the preview grid and its cache, the spot search and the Smart Shulker/Echest steps
│   └── Orphans.java          The list of shulker boxes left behind (its format and checks), shared with the printer
├── printer/                  The printer's parts: pure logic (unit-tested) and the game-facing steps
│   ├── schematic/            Reading .litematic files: the format, the reader and its limits, file names and transforms
│   ├── source/               Where the schematic comes from: Litematica (by reflection, LitematicaBridge), files, block states,
│                             and the render-layer filter (LayerFilter; LayerCache keeps the last range read), and the schematic queue (BuildQueue)
│   ├── place/                How each block is placed: placement rules (PlacementMapper, PropertyRules, StateClassifier), stripped
│                             logs and other tool finishes (ToolRules, ToolTables, Tool, ToolAction, HandTables, ShovelTable), wrong-block breaks
│                             (BreakRules), layer order (LayerCursor), scaffold chains (ScaffoldPlanner), waterlogging (WaterlogRules), look windows, build order, batching,
│                             the packet budget, the area watch (AreaWatch) and the jobs that hold the end of a run (JobHold)
│   ├── materials/            What the build needs, and where it is fetched from (inventory, shulkers, containers, ender chest),
│                             and what happens when an item runs out (MaterialPolicy)
│   ├── move/                 Getting to blocks out of reach: MovePolicy (walk or fly), HoverPoint and FlySteer (flying), StandShield (stand blocks), SpareItems (spare blocks)
│   ├── stash/                Stash orders: the low-stock rule, the stash picker, order batches, the order ledger and the chat text
│   └── loop/                 The printer's state machine: what to do next on each tick, and why it pauses (PrintLoop), and the slow re-check of finished cells (RecheckQueue)
├── craft/                    Crafting for stash-sorter: the recipes, the planner, and the table and anvil finders
└── utils/
    ├── AutoHotbar.java       Keeps the items a module works with in the hotbar
    ├── BaritoneHelper.java   The main class that calls Baritone, used only when it's installed
    ├── ChestStack.java       The rules for putting up the delivery storage's chests
    ├── ContainerPacing.java  The container-delay rule
    ├── PlaceSearch.java      Where to put a shulker or anvil down when there's no room beside the bot
    ├── SnapshotSearch.java   Searches a list the game changes on another thread (the tab list)
    ├── SurveyRetry.java      When a container that couldn't be read is tried again
    ├── WalkProgress.java     Whether a walk is getting anywhere
    └── WorkPattern.java      The order auto-walk covers an area in: Nearest, Rows, Spiral Out or Spiral In

src/<minecraft>/java/com/porter/qwerte/compat/   (the same eleven files in src/1.21.11 and src/1.21.4)
├── BaritoneFluids.java       Keeps Baritone from placing blocks in water, where the version allows it
├── Drops.java                Tells from outgoing packets when you drop items or break blocks
├── GameVersion.java          The game's version name and data version (its accessor is named differently across versions)
├── ItemIcons.java            Drawing items on screen, for stash-overlay and the Stash Window
├── LegacyItems.java          Shulker items kept in block entity data (ViaVersion), read the way Meteor reads them
├── MapTexture.java           The texture the Stash Window's live map is drawn into
├── Players.java              Player helpers that differ between versions (the selected hotbar slot)
├── RightClick.java           Right mouse button presses, for picking storages and stations
├── ScaledText.java           Text at the Stash Window's own size
├── ScreenOverlay.java        Drawing on top of everything in a screen, for Container Tweaks' preview
└── SignEditors.java          Sign editor screens (the accessor for the sign being edited differs per version)

versions/<minecraft>.properties   Minecraft, Meteor and Baritone versions for each supported Minecraft version
```

Shared code is in `src/main`, and so are the shared kit tables, `src/main/resources/kit-items.json` and
`src/main/resources/kit-types.json` (the same files the ZenithProxy plugin uses). The little that differs between Minecraft versions is in `src/<minecraft>`, and only the
folder for the version being built is compiled. The addon version, Fabric Loader and Loom versions live in
`gradle/libs.versions.toml`.

The Messenger window (`messenger/` in each `src/<minecraft>/java`) and the emoji pictures (`src/main/resources/assets/qwerte/textures/emoji`, Twemoji, see [Third-party](#third-party)) follow the same rule: the pictures are shared, and the window's version-specific classes are in each build's folder.

The 1.21.11 build also has `ClientPacketListenerMixin` in `src/1.21.11/java/com/porter/qwerte/mixin/` (listed in
`src/1.21.11/resources/qwerte.mixins.json`). It works around a game crash when a name-only player head is looked up while
chests are read on a busy server. Container Tweaks adds its own mixins for drawing the shulker previews and icons:
`AbstractContainerScreenMixin` and `GuiMixin` are shared (in `src/main/java/com/porter/qwerte/mixin/`), and each build has
its own slot mixin and tooltip mixin, listed in its `qwerte.mixins.json`.

To add another Minecraft version, add a `versions/<minecraft>.properties` and a `src/<minecraft>/java` folder with the
compat classes (copy the eleven in `src/1.21.4/java/com/porter/qwerte/compat/` and adapt them), then build with `-Pmc=<minecraft>`.

## Third-party

### Twemoji
The emoji pictures bundled in assets/qwerte/textures/emoji are Twemoji 17.0.3 (https://github.com/jdecked/twemoji), 181 emoji at 72x72. Copyright 2019 Twitter, Inc and other contributors (graphics), continued by the jdecked/twemoji project. Licensed under CC-BY 4.0 (https://creativecommons.org/licenses/by/4.0/). Used unmodified; shrunk to 36x36 in memory when drawn. The licence text and notice are in the same folder.

## License

[GPL-3.0](LICENSE)
