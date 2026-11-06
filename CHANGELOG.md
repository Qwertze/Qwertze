# Changelog

All notable changes to Qwerte are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Versions before 0.2.8 are listed on the [GitHub releases page](https://github.com/Qwertze/Qwertze/releases).

## [Unreleased]

## [0.4.0-dev.4] - 2026-11-05

Dev pre-release. Mirrors Stash Sorter plugin 1.8.1.

### Removed
- Stash Manager website: the order note.

### Fixed
- Stash Manager website: stashes from proxy bots showed only Search, Other and Shulkers. The server now fills in the creative tabs from vanilla tables for 1.21.4 and 1.21.11, also for indexes it already holds.

### Added
- stash-manager: shulker names for orders. With an anvil set (Anvil group: `select-anvil`, then right-click an anvil; `clear-anvil`, `show-anvil`, `anvil-color`) the bot renames each shulker the order asks a name for before delivering it, about 1 XP level each. A shulker it can't name (no anvil, too few levels, anvil gone) is delivered unnamed, and the order says so.
- Stash Manager website: name an order's shulkers, all with one name or each one separately next to the planned layout. Warns when the bot has no anvil or too few levels.
- Stash Manager website: a Retry button on failed and cancelled orders places the same order again.
- Stash Manager website: themes (Classic, Modern, Light, Dark, Midnight, High contrast, or Match system), picked in Settings > Appearance or with the palette button in the header. Saved per browser. The theme fonts (Inter, IBM Plex Sans, Space Mono, JetBrains Mono, Atkinson Hyperlegible; SIL Open Font License, licences in the jar under licenses/fonts/) are served by the server itself, with no third-party requests.
- Stash Manager website: a phone layout below 768px wide: compact sticky header, status chips, bottom tab bar with a More sheet, a stash index card, switches for on/off settings and a save bar that only shows with unsaved changes. On touch screens, tap a shulker box to look inside, and long-press an item or tap its + to add a stack to the cart.
- stash-manager: an anvil is found or put down when none is set. The order of preference is the anvil selected by hand, then one remembered from before, then a placed anvil found in the stash area, then an anvil taken from the stash and placed. With none, the shulker is delivered unnamed with "no anvils available to rename with" and the order goes on.
- stash-manager: crafting (off by default, `enable-crafting`). Orders and compaction may craft a plain Shulker Box when no empty one is left, from stash shells and a chest (made from planks, made from logs), at a crafting table set with `select-crafting-table` (`clear-crafting-table`, `show-crafting-table`, `crafting-table-color`). Limits: `craft-max-per-order` (27), `craft-max-ops-per-task` (128), `craft-max-per-hour` (54), and `craft-never-use` (empty by default). Kits never craft. Damaged items, stacks that hold contents, kit, excluded, drop-off and delivery containers, and items another line needs are never used.
- stash-sorter: with no room beside the bot, a shulker or anvil is put down at the nearest good spot within 5 blocks that the bot can walk to. The same search as the plugin's.
- Stash Manager website: shulker box icons show their colour and a contents overlay, with flat tops on every version.
- Stash Manager website: the Bots view shows each bot's crafting status (on/off, table set) and where its anvil came from (set, found, placed, none).

## [0.4.0-dev.1] - 2026-11-05

A big test build: the Stash Manager website and stash orders. Not fully tested in game yet.

### Added
- **stash-manager** module: sends what is in the stash saved by stash-sorter to the Stash Manager server on your network (`server-url`, `api-key`). Only changes are sent, checked every `scan-interval` seconds. `include-excluded` lists excluded containers too.
- stash-manager: **orders**. With `accept-orders` on, the bot takes orders placed on the website. It gathers the items from the stash, loose stacks first and then out of shulkers, packs them into empty shulkers and puts the shulkers into the delivery container. Leftovers go back to the container they came from, and so do the shulkers it unpacked. stash-sorter doesn't need to be on: stash-manager drives it, using stash-sorter's settings, and never sorts. To stop the bot, turn off `accept-orders` or stash-manager.
- stash-manager: a delivery container (Delivery group: `select-delivery`, then right-click a container; `clear-delivery`; `show-delivery` and `delivery-color` outline it). It is never sorted into.
- stash-manager: `use-named-shulkers` (on) lets orders take items out of shulkers with a custom name. `order-messages` (on) says in chat when an order is taken, delivered or fails.
- stash-manager: `fresh-minutes` (5). A container read within that many minutes isn't read again when stash-sorter or an order starts, unless something marked it changed. 0 reads everything every time. A rescan from the website always reads everything.
- stash-manager: an order in progress is saved in the stash file with its plan, so it carries on after a disconnect or restart.
- **Stash Manager server** (`stash-manager-server/`, a separate jar, see its README):
  - A website for your network that shows the stash like a Minecraft inventory, with search and item icons.
  - Orders, a page for each bot, plugin and proxy settings, a console, and an Activity view of what each bot is doing.
  - One password protects the whole website. Each browser stays logged in for 30 days.
  - A **Streamer mode** button blurs coordinates, keys, server addresses and console output.
  - Works behind an HTTPS reverse proxy listed in `allowedHosts`.

### Changed
- stash-sorter: the delivery container is never sorted, compacted or used as a source, and items in it don't show in search.
- stash-sorter: kits can be built for an order, matching the exact items asked for, and an order's shulker is never taken apart or filed away by the sorter.
- stash-sorter: shulkers that came through ViaVersion, which keep their items in block entity data, no longer look empty.

## [0.3.2-dev.4] - 2026-10-31

### Changed
- auto-dirt: `skip-dirt-blocks` is now `dirt-blocks`, a list of the dirt-like blocks it never covers. Remove a block to cover it, for example Mycelium to pave a mushroom island. If you had `skip-dirt-blocks` off, remove the blocks you want covered from the list.
- auto-dirt: with Grass Block as the `dirt-item` it now always puts grass on plain dirt, for example the dirt you uncover by mining mycelium, while grass, mycelium and the other `dirt-blocks` are still skipped.

## [0.3.2-dev.3] - 2026-10-31

### Added
- auto-dirt: `skip-dirt-blocks` (on by default). Turn it off to cover dirt-like blocks too, such as mycelium, so a mushroom island can be paved with grass blocks. The `ignore` list still applies.

### Changed
- auto-dirt: never places a block on the same block, so with Grass Block as `dirt-item` grass never goes on grass.

## [0.3.2-dev.2] - 2026-10-16

### Added
- stash-sorter: `log-level` (Quiet, Normal, Debug) replaces the `debug` setting. Quiet shows what it is doing now and next, errors and alerts; alerts (things that need a person) are also whispered to the `whitelist`, queued and rate limited. Debug adds the reasons behind its decisions. Whatever a level hides still goes to the log file.
- stash-sorter: "now / next" lines (`Stash: sorting: taking 5 stacks from "Diamonds" at x y z; next: ...`) for starting, exploring, reading, sorting, repacking, compacting, labelling, ground pickup, bundles, waiting, idle and done. An identical line is never repeated, and progress is said at most every 10 percent or 60 seconds.
- stash-sorter: a shulker the sorter put down is saved in the stash file with what it is for, and what is carried is saved with it. After a disconnect or stopping the module the next start checks the shulker, finishes packing it, breaks and picks it up and puts it away, before everything is read again. A shulker in another dimension is kept and blocks new shulkers until you are back.
- stash-sorter: `smart-recheck`: drop-offs are only re-checked when they can change without a lid (hopper, dropper or dispenser next to or above, or unloaded), and less often while nothing is found.
- stash-sorter: ground pickup in batches, taking on the way to a deposit when a source is nearer, staying at an open container to take or merge instead of reopening it, and reading unknown containers within reach while exploring.
- stash-sorter: an "Unstuck" button drops whatever the sorter is doing and starts over. The unfinished shulker step is not resumed, the "no more shulkers until restarted" block is lifted, and a shulker of ours still standing is left where it is and listed with the orphaned shulkers. With the module off it only forgets the saved shulker step.

### Changed
- stash-sorter: every start reads every container again. Old contents stay in use for search and planning, but nothing is taken from a container until it has been read. Containers other players used are marked this way too, instead of being forgotten.
- stash-sorter: the planner works out how much room each container has once per pass and remembers which containers fit an item for the pass.
- stash-sorter: compaction picks up its shulker instead of leaving it standing when fewer than 3 stacks of the item are left to pack.
- stash-sorter: the stash file is written to a temporary file and moved over the old one.
- stash-sorter: the old `debug` setting is gone, set `log-level` to Debug instead.

### Fixed
- stash-sorter: a shulker standing for a compaction when the connection dropped was forgotten with no record of it.
- stash-sorter: a repacked shulker already picked up could be looked for on the ground and then abandoned, because the shulker count was off or a resume expected one more shulker than could be held. Picking up is now told from the count just before the break, and failing that, after 3 s with nothing dropped in sight, from the one held shulker that matches it, only near the spot and with the block gone. Matches Stash Sorter 1.4.0-dev.2.

## [0.3.2-dev.1] - 2026-10-15

### Added
- stash-sorter: signs written in Unicode font letters (mathematical, fullwidth, circled, small capitals) are recognised, and signs naming an enchantment with a level (`Protection IV`, `Efficiency 5`) take enchanted books with exactly that level.
- stash-sorter: bundles. A bundle with things in it that the sorter finds in the stash is emptied into free carry slots and the contents are sorted; empty bundles go to a chest signed `Bundles`, else one signed for shulkers or containers. Nothing is packed into bundles, and kits can't contain them.
- stash-sorter: a stash perimeter (two corners stored with the stash). Set it with the `pick` modes Area corner 1 and 2 or the "Set corner 1/2 here" buttons, clear it with "Clear area". It replaces `radius` and `height`, the sorter explores its chunks so they load, walks anywhere inside it, and ground pickup uses it. The outline is drawn in `area-color` while stash-sorter or stash-overlay is on.
- stash-sorter: safe walk (`safe-walk`, on by default): while the sorter walks, Baritone never parkours or falls more than one block, and the sorter sneaks at ledges with a drop of 2 or more blocks.
- stash-sorter: Baritone is never allowed to break or place blocks while the sorter walks. A container that can't be reached that way is reported once and skipped.

## [0.3.1] - 2026-10-15

### Added
- stash-sorter: picking up items other players left on the ground in the stash area (`collect-ground`, `collect-range`). Only with no other player loaded, only items lying still for 3 seconds, never your own drops or items from blocks you dug.
- stash-sorter: scan-only mode (`sort-items` off). It reads unknown or stale containers and keeps the saved stash current, but never moves items. stash-overlay keeps working in it.

## [0.3.0] - 2026-10-15

Includes everything from 0.2.8, which wasn't released separately.

### Added
- stash-sorter: shulker compaction (`compact`, `compact-top-up-at`, `compact-exclude`, and the Compact skip pick mode).
- stash-sorter: auto-labelling (`auto-label`, `label-gap`) and the Labels report button.
- stash-sorter: watching chests and signs (`watch`, `watch-delay`, `watch-signs`, `sign-delay`).
- stash-sorter: giving way to you and other modules (`quiet`), overflow into unsigned chests (`unsigned-overflow`), and `debug`.
- stash-sorter: orphaned shulkers are reported in chat and listed in the module.
- auto-till and auto-dirt: work patterns (`work-pattern`, `row-direction`, `lane-width`).
- auto-till: ice water holes (`water-source` Ice, `ice-restock`) and glowstone lighting (`light-holes`, `light-air-place`, `glowstone-restock`).
- auto-till and auto-dirt: auto-hotbar with per-item slots, and restocking of bone meal, glowstone and hoes.

### Changed
- stash-sorter: items are identified by item ID and never by their anvil name. Labels show `Diamond Sword (named steak)`.
- stash-sorter: `End Blocks` only matches end blocks. Shulker boxes and elytra no longer match it.
- stash-sorter: own gear (tools, armor, food, totems, ender pearls and the like) is never sorted unless the sorter took it from the stash.
- stash-sorter: shulkers are never placed next to a hopper, on top of a stash chest, or where a player or mob stands, and are at least 5 seconds apart. After a placement problem, shulker work pauses for 3 minutes instead of for the rest of the run.
- stash-sorter: `Rescan` is now `Forget contents`. It doesn't read anything by itself.
- stash-sorter: in stay-on mode, the "Sorted" summary is only logged when it changes.
- auto-till and auto-dirt: wait while stash-sorter is mid-job. Stash-sorter gives way to them only while they are actually working.

### Fixed
- stash-sorter: no longer loops forever opening and closing the same chests. Only real sign text changes count, and stuck work is given up on.
- stash-sorter: stacks the kit builder moves into carry slots are treated as stash stacks.

## [0.2.8] - 2026-10-14

Not released separately. Included in 0.3.0.

### Added
- auto-till and auto-dirt: work patterns (Nearest, Rows, Spiral Out, Spiral In) with row direction and lane width.
- auto-till: ice water holes (`water-source` Ice) with ice restock.
- auto-till: glowstone lighting for water holes (`light-holes`, `light-air-place`).
- auto-till and auto-dirt: auto-hotbar with per-item slots. Protected items are never evicted.
- auto-till: container restock for bone meal, glowstone and hoes.

### Fixed
- auto-till: crash when the refill source changed mid-trip.
