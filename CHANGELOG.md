# Changelog

All notable changes to Qwerte are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Versions before 0.2.8 are listed on the [GitHub releases page](https://github.com/Qwertze/Qwertze/releases).

## [Unreleased]

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
