# Qwerte
A Meteor Addon For 2b2t Dedicated to Regrowing &amp; Creating Farmland

Qwerte adds four modules to [Meteor Client](https://meteorclient.com):

- **Auto Till** turns nearby dirt and grass into farmland, digs and fills water holes so the farmland stays hydrated,
  plants and bone meals crops, and restocks its own seeds. With [Baritone](https://github.com/dekrom/baritone)
  installed it can also walk to new land and to water by itself, so a whole field can be built hands-free.
- **Auto Dirt** covers the ground around you with dirt, so stone, sand or netherrack can be turned into farmland. It
  never places dirt on top of dirt, and restocks its own dirt. With Baritone installed it can also walk to uncovered
  ground by itself.
- **Stash Sorter** sorts the chests and barrels around you, either into tiers or by the signs on them. It repacks
  mixed shulkers, merges partial stacks, empties drop-off chests, builds saved kits and can be searched. Want it on a
  proxy bot instead?
  See [Stash Sorter for AquariusProxy](https://github.com/Qwertze/Stash-Sorter).
- **Stash Overlay** shows what each container in a saved stash mostly holds, as an item icon on the container.

| | |
|---|---|
| **Minecraft** | 1.21.11 or 1.21.4, each with its own jar |
| **Meteor Client** | The build for your Minecraft version |
| **Fabric Loader** | 0.18.2 or newer |
| **Java** | 21 or newer |
| **Optional** | [dekrom's Baritone fork](https://github.com/dekrom/baritone/releases) for walking, refilling and storage trips |

## Features

All four modules are in Meteor's **Qwerte** category. Every feature can be
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
anywhere else, and doesn't stop repacking.

Once everything is in place, partial stacks of the same item in each container are merged ("downstacked"), so a
chest with three stacks of 20 cobblestone ends up with one of 60.

#### Kits
A kit is a set of items packed together in one shulker, slot for slot with the same counts, like a PvP kit.

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
  potions and trims.
- **Where kits go:** a finished kit goes to the chest whose sign is the kit's name, "<name> kit" or "<name> kits". With
  no such chest the kit isn't built, and the chat says so once.
- **Kit chests are reserved:** a sign naming a kit's chest keeps loose items out of it, even with `build-kits` off. So
  don't name kits after categories, like `pvp` or `totems`.
- Finished kits are never unpacked or topped up, and neither is a part-built kit holding more than one kind of item. A
  part-built kit in its own chest is carried on with. A part-built kit holding only one kind of item, outside its kit's
  chest, is treated as an ordinary bulk shulker.
- Each step makes up to two inventory clicks, then waits `delay` ticks. Raise `delay` if the server complains about
  clicks.

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

### Stash Window
New in 0.4.0-dev and still being tested.
- A window (keybind `open-window`, the `.stash-window` command, or the module's button) to search the containers you have
  opened. It works on its own: no server, no login, no network. It remembers chests, barrels and shulker boxes you open,
  including what shulker boxes inside them hold, and saves them under `meteor-client/qwerte/local-index/`.
- Optionally turn on `connect-to-server`, set `server-url` and log in to see the stash of your Stash Manager server with
  your website account's permissions. Plain `http://` outside your own network is refused unless `allow-insecure-http` is on.
- With a server connected, the window also has the server's pages, each shown only when your account's permissions allow
  it: Orders, Live (the map), Build Planner, Bots, Activity, Settings, Proxy, Console and, for the owner, Users. The Build
  Planner works from your game's `schematics` folder (or an uploaded file) and can order a build's materials part by part.
- The window is laid out like the website's stash page: a header bar, the creative tabs, the item grid with small counts in
  the corner of each slot, a search box and sort select under it, and the details of a clicked item in a drawer over the right
  side of the grid (closed, it takes no room). Drag
  its header to move it, drag its edges or corners to resize it (the grid gets more or fewer columns), double-click the
  header to maximize it. Its place and size are remembered. It follows Minecraft's GUI scale (`window-scale` in the module
  overrides that; Meteor's own GUI scale does not apply to it) and uses the game's font, at about the website's size next to
  the slots (`window-text-scale`: Auto, Small, Normal or Large).
- `window-theme` (or the Theme select in the window's header) picks its colours: Classic (the default), Modern, Light, Dark,
  Midnight, High contrast or Mac, the same palettes as the website. Classic matches the website's default.
- The session token of a server login is stored in plain text in Meteor's `modules.nbt`. Do not share your `meteor-client`
  folder or Meteor profiles, and use the window's Log out to end (revoke) the session.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft **1.21.11** or **1.21.4**.
2. Put [Meteor Client](https://meteorclient.com) for the same Minecraft version in your `mods` folder.
3. Download the `qwerte-addon` jar for your Minecraft version from the
   [latest release](https://github.com/Qwertze/Qwertze/releases/latest): `qwerte-addon-0.3.1+1.21.11.jar` or
   `qwerte-addon-0.3.1+1.21.4.jar`. Put it in your `mods` folder.
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

## Dev builds

New features are tested on the [`dev`](https://github.com/Qwertze/Qwertze/tree/dev) branch before they reach `main`.
Each round of testing gets a **pre-release** on the [releases page](https://github.com/Qwertze/Qwertze/releases), named
like `v0.2.7-dev.1`. These are unofficial: they may be buggy and settings may change. Use the latest regular release
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
   kit's name, then turn on `build-kits` in Signs mode.

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
| Kits | `build-kits` | off | Signs mode only: build the saved kits out of the stash, into chests signed with the kit's name. |
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
there; add its jar to `run/mods` if you want to try the automation.

### Project layout

```
src/main/java/com/porter/qwerte/
├── QwerteAddon.java          Addon entrypoint, registers the Qwerte category and modules
├── modules/
│   ├── AutoTill.java         The auto-till module: tilling, watering, planting, automation
│   ├── AutoDirt.java         The auto-dirt module: covering ground with dirt
│   ├── StashSorter.java      The stash-sorter module: sorting, kits, bot mode, picking, search
│   ├── KitBuilder.java       Building saved kits out of the stash, for stash-sorter
│   ├── GroundCollector.java  Picking up items left on the ground in the stash area, for stash-sorter
│   ├── StashOverlay.java     The stash-overlay module: an icon of each container's main item
│   ├── Restocker.java        Storage and ender chest restocking, shared by auto-till and auto-dirt
│   └── StorageList.java      The list of storages a module restocks from, in the order it tries them
├── stash/
│   ├── Stash.java            A saved stash: its containers and contents, saving, loading and search
│   ├── Categories.java       What sign words and searches mean: categories, slang and synonyms
│   ├── Kit.java              A saved kit: 27 slots, and how to tell if a shulker is finished or part built
│   ├── Kits.java             The saved kits of the current server, saved and loaded from disk
│   ├── ItemPreviewScreen.java  Shows a found item in a look-only container screen
│   └── KitPreviewScreen.java   Shows a saved kit in a look-only container screen
└── utils/
    └── BaritoneHelper.java   The main class that calls Baritone, used only when it's installed

src/<minecraft>/java/com/porter/qwerte/compat/
├── Players.java              The selected hotbar slot
├── Drops.java                Telling from outgoing packets when you drop items or break blocks
├── RightClick.java           Right clicks, for picking storages and stations
├── ItemIcons.java            Drawing item icons on screen, for stash-overlay
└── BaritoneFluids.java       Keeping Baritone from placing blocks in water, where the version allows it

versions/<minecraft>.properties   Minecraft, Meteor and Baritone versions for each supported Minecraft version
```

Shared code is in `src/main`. The little that differs between Minecraft versions is in `src/<minecraft>`, and only the
folder for the version being built is compiled. The addon version, Fabric Loader and Loom versions live in
`gradle/libs.versions.toml`.

The 1.21.11 build also has one mixin, `ClientPacketListenerMixin` in `src/1.21.11/java/com/porter/qwerte/mixin/` (listed in
`src/1.21.11/resources/qwerte.mixins.json`). It works around a game crash when a name-only player head is looked up while
chests are read on a busy server. The 1.21.4 build has no mixin: its `qwerte.mixins.json` lists none.

To add another Minecraft version, add a `versions/<minecraft>.properties` and a `src/<minecraft>/java` folder with the
four compat classes, then build with `-Pmc=<minecraft>`.

## License

[GPL-3.0](LICENSE)
