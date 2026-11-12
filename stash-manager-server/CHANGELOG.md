# Changelog

All notable changes to the Stash Manager server are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Versioning

The server uses the same rule as the Qwerte addon:

- **x.0.0**: major breaking changes.
- **x.y.0**: new features, bug fixes and more.
- **x.y.z**: minor bug fixes, single one-off patches and hotfixes.

The version is set in `stash-manager-server/build.gradle.kts`. Version 1.1.0 is the first release with a version number. Releases
before it are headed by their date and jar name. Qwerte addon 0.5.0 works with this server.

## [Unreleased]

## [1.3.0] - 2026-11-11

Release jar `stash-manager-server-1.3.0-pre.jar` (in `qwerte-release`), SHA-256 `a41f8bb0f81f0be7f5fea12171f7fa925a2f360cfebaa8aba78c56bd9097a055`. Automated tests: 551, 0 failures, 1 skipped.

### Added
- **Pearl travel** (PEARL-CONTRACT v1.0 rev 7; Stash Sorter plugin 1.18.0 uses it; not tried in the game yet). Stations (a `ps-` id, the loader's player name, server, dimension, an optional home station per stash, the loader's approval and its allowed travellers), trips (Task records `t-` with kind `travel`, the six states, the chamber and loader locks) and the heartbeat and reply fields. See [Pearl travel](README.md#pearl-travel) and PROTOCOL.md.
- **Loader reports.** `POST /api/v1/bot/pearls` (signed, after a `bot/hello`) for the plugin loader, and the heartbeat field `pearlLoader` for the addon loader. The reply lists the owner-approved travellers (`allow`), each with its pinned uuid.
- **Traveller heartbeat and reply.** The heartbeat's `pearlTravel` object (needs `player`; `null` clears) and the reply's `pearlTrip` and `pearlAwayClear`. `pearlAwayClear` is repeated until the bot reports `away: null`, for at most 10 minutes (rev 7). A bot's local `go`, `back` or `home` is audited.
- **Browser routes.** `GET /api/v1/pearl` (activity permission). Owner writes: stations and their deletion, assign, pins, approve and dismiss, allowed travellers, chamber reset and the away clear. `settings` writes: trip start and stop. Rate limits: 60 writes and 6 trip starts or stops per user per minute. Audit actions `pearl.station`, `pearl.station.delete`, `pearl.assign`, `pearl.pin.reset`, `pearl.loader.approve`, `pearl.loader.dismiss`, `pearl.allowed`, `pearl.chamber.reset`, `pearl.trip`, `pearl.stop` and `pearl.away.clear`.
- **Website.** A "Pearl travel" panel on the **Kits** tab, below Repair: the travellers (pearls carried, away, Go with a confirmation, Back, Stop before the whisper, "It's home"), the stations and their chambers, the trips and the alerts.
- **Stash list.** `GET /api/v1/stashes` entries gain `pearl: { away, trip }` (a boolean and the trip's state; no names or ids).
- **Storage.** `pearl.json` in the data folder: `{ version: 1, stations, trips, pins }`, written atomically, owner only, with a `pearl.json.bak` from a clean previous file. When neither can be read, pearl travel is off (`503`) and nothing is overwritten.
- **Streamer mode.** A user forced into streamer mode gets `403 { permission: "streamer" }` on every pearl route, like the repair routes.

### Compatibility
- Stash Sorter plugin 1.18.0 needs this server for pearl travel. With an older server it makes no trips (its reply has no `pearlTrip`), and its loader gets no `allow` and reports no chambers. Its other features are unchanged.
- Bots that send no pearl fields (the plugin before 1.18.0, and the Qwerte addon until its pearl release) work as before and are never offered a trip.

### Notes
- No coordinates are stored or sent in pearl travel: stations, trips, reports and whispers carry ids only. Pearl ids are names the owner chose and are shown to every user who sees the page, so don't name chambers after coordinates.
- Pearl travel has not been tested in game.

## [1.2.0] - 2026-11-10

Release jar `stash-manager-server-repair-pre.jar`, SHA-256 `be83fb27596720e8544f0613c9c843bee3b3e98fc990d607aaa2498832d66528`,
500 automated tests. The build output is `stash-manager-server/build/libs/stash-manager-server.jar`.

### Added
- **Repair passes** (REPAIR-CONTRACT v2.0, with the "opening unnamed shulkers" revision). The stash bot repairs low-durability items when it is idle. The server keeps the settings, shows the bot's status and last pass on the website, and sends the bot its run and stop requests. See [Repair passes](README.md#repair-passes).
- **Repair settings per stash, in `repair.json`.** `enabled` (repair mode, on by default), `auto` (automatic passes when idle, on), `threshold` (1 to 100 percent, default 50; "below 50%" is strict), `reserve` (0 to 1,000,000 bottles, default 1728), `needsRepairChest` (a container id, or null) and `shulkers` (open unnamed, non-kit boxes, on by default). Each change raises `rev` by 1; `hash` is the contract's SHA-1 that the bot caches. The file is written atomically and owner-only, with a `repair.json.bak` from a clean previous file. At most 64 stashes. A damaged entry answers `503` for that stash only and is kept as it is.
- **Heartbeat fields.** A bot that runs passes sends `repairHash` and `repairPass` (state, why, progress, candidates including `inBoxes` and `boxes`, bottles, the run and stop ids it acknowledged, and the hash of its last report). The reply carries `repairHash` always, `repairSettings` when the bot's hash differs, `repairRun` and `repairStop` while a website request is newer than what the bot acknowledged, and `needRepairReport`. A bot that sends no `repairHash` is never asked to run a pass.
- **Signed repair report, `POST /api/v1/repair/report`.** Signed like the index, with `player` required. At most 512 KiB (`413`) and 200 items; `containers` is taken when the bot sends no kit report. An unknown stash answers `400` ("send a heartbeat first"), another player's ownership `409 { owner }`. Kept in memory only, for at most 64 stashes, and never logged.
- **Browser routes.** `GET /api/v1/repair` (view), and `POST /api/v1/repair/settings`, `/chest`, `/run` and `/stop` (the settings permission). Rate limits: 60 settings or chest writes and 6 run or stop requests per user per minute, then `429 { error, retryAfter }` with `Retry-After`. Bodies are at most 4 KiB. A run or stop answers `409` ("this bot can't run repair passes") when the stash's last heartbeat had no `repairHash`; an offline bot gets the request when it reconnects. Audit actions `repair.settings`, `repair.chest`, `repair.run` and `repair.stop`.
- **Forced streamer mode.** A user the owner has forced into streamer mode gets `403 { permission: "streamer" }` on all five routes. `GET /api/v1/stashes` entries gain `repairPass: { supports, synced, enabled, state, why }`, with no positions.
- **Repair panel (Kits tab, below the Stash area and Crafting table panels).** Switches for repair mode, automatic when idle and open unnamed boxes (named boxes and kits are never opened); the threshold; the reserve, entered as shulkers, stacks and bottles and stored as bottles; the needs-repair chest picker with Clear; the bot's status in plain words; Save, Run repair pass and Stop; estimate tiles; and the last pass, with its skip reasons and item list (from and to). View-only users see it read-only.
- **Chest checks against kit chests.** The needs-repair chest can't be a kit chest: `POST /api/v1/repair/chest` answers `400 "that container is reserved for kits"` for the general kits chest, a rule target, or a type, category or template chest. Setting a chest needs the bot's container list (`409`) and a pickable container (`400 "that container can't take items"`); clearing always works.
- **Acknowledged run and stop ids are kept and never lowered.** A heartbeat that leaves them out, or clears the pass, keeps what the server holds, and a lower id is ignored. After a server restart the ids are learned again from the bot's next heartbeat.

### Changed
- The kit chest, rule, type and template routes answer `400 "that container is the needs-repair chest"` for the chest set as the needs-repair chest. Nothing else in the kit routes changed.

### Compatibility
- A bot that sends no `repairHash` (plugin before 1.17.0, and the Qwerte addon 0.5.0) works as before: the Repair panel says the bot can't run passes, a run is `409`, and settings are stored for later.
- An older server sends no `repairHash`: a plugin 1.17.0 keeps its cached settings and makes no automatic passes. `stash repair run` still works on the bot.

### Known limits
- An open order for an item that a pass repaired is not moved to the repaired item.
- The report's `to` is never sent, so every item's `to` is null. The Repair panel's item list therefore shows "stayed" as every item's destination, so that column is unknown until a later release.
- `toNeedsRepair` and `rehomed` are approximations (see the plugin's 1.17.0 notes).
- The box cooldown is kept in the bot's memory: a bot restart can open a box again sooner than 24 hours.
- Pass reports and bot status are kept in memory. After a server restart a bot is unknown until its next heartbeat.
- The Qwerte addon has no repair passes yet.
- Nothing in this release has been tested in game. The 500 automated tests cover the routes, the settings store and the hash vectors.

## [1.1.0] - 2026-11-09 (stash-manager-server-kit2.jar)

Release jar `stash-manager-server-kit2.jar`, SHA-256 `72b971164593eb5d2240b5f77202354117afa94a0da102405aedebd7ed4df0f6`,
487 automated tests. The build output is `stash-manager-server/build/libs/stash-manager-server.jar`.

### Added
- **Kit types (kit sorting v2).** A bot sorts each kit box into one of seven types from the shared table `kits/kit-types.json`:
  PvP / Crystal, Travel, Redstone, Farming / Food, Building, Starter / Base and Other kit. A box of one kind of item is stock,
  never a kit. The v1 score is kept for bots that don't send the v2 hash (see Changed).
- **Custom categories.** Up to 16 per stash, named with up to 32 characters (no `§` and no control characters). Each gets a chest
  like a type. A bot never detects one: a box is in a custom category only through a template or a treat-as.
- **Chests by kind and the general kits chest.** A chest for each type and each custom category, and a general kits chest. A kit
  goes to the first usable of: its kit name's rule, its template's chest, its category's chest, the general kits chest. A stored
  kit only moves up that chain, never down because a chest filled up. A full chest is not a warning; a missing chest is.
  Category and template chests are kit-reserved, like the general kits chest.
- **Treat-as overrides.** For one kit name: a kind, "Not a kit", or Auto (the classifier decides).
- **Kit Builder.** A Kit Builder tab on the website, with templates of 27 slots. Each slot holds one exact variant of an item or any
  variant of it, with a count up to the stack size. A template has a name (1 to 50 characters), a kind, a chest, a shulker colour
  (any, plain or one of the 16 dye colours) and a restock count (off, or keep 1 to 27 complete copies). A template needs at least two
  kinds of item, no two templates in a stash may hold the same contents, and a stash has at most 50 templates. Nine presets
  (`kits/kit-presets.json`: Crystal PvP, Sword PvP, Elytra Travel, Builder Basics, Assorted Concrete, Redstone Basics, Starter Base,
  Farmer, Food) fill a new template.
- **Matching kits to templates.** A box matches a template when it has the same items in the same counts, with the same enchantments,
  trims, potion contents and other components. Slot positions, stack splits, custom item names, damage and repair cost are ignored, so
  a renamed or damaged copy still matches. A **complete copy** has everything the template has. An **incomplete copy** carries the
  template's name with some items missing. It is kept in its chest and topped up, and is never delivered for an order.
- **Kit orders.** A kit line (`type: "kit"`, 1 to 54 per line) in an order. One order has at most 16 different kits and reserves at
  most 256 item keys. Complete copies in the stash are used first; the rest are built from reserved items. Short plain items are
  crafted when the bot can craft. A short order fails with `400` and lists the missing items. A bot that can't build kits is refused
  with `409` (`no_bot` or `disabled`). A retry plans again against the current template.
- **Kit reports and heartbeat.** The kit report carries the classifier, each type with its reason, named boxes that are not kits,
  the templates' copies (complete, incomplete, elsewhere, carried, missing) and the restock state. The heartbeat carries `kitsHash2`
  and `kitOrders`.
- **Website.** The Kits tab is rebuilt for chests by kind and treat-as. The Kit Builder tab is new. Kit lines in Orders show the kit's
  name and any reason it can't be ordered.
- **Streamer mode for kits.** A forced streamer doesn't see the Kits tab, and the kit settings routes refuse them with `403`. The Kit
  Builder is read-only for them: kits read "Kit 1", "Kit 2" and so on, custom categories read "Category 1" and so on, chests are hidden,
  and kit orders still work (they show no positions).
- **Area home reset cooldown.** The owner can reset a stash's home point once an hour. The cooldown is saved in `area.json`, so it
  survives a restart, and it is reserved atomically: two resets can't both pass, and a failed save puts the old cooldown back.

### Changed
- A bot that sends no `kitsHash2` gets only the v1 settings and keeps the v1 score. It gets no kit orders or restock.
- `kits.json` entries gain `typeChests`, `treatAs`, `categories` and `templates`. An older server that rewrites the file drops them,
  so keep the server up to date once you use them.
- A kit report counts for kit orders only while the bot's latest heartbeat (at most 10 minutes old) carries the report's hash. A bot
  that has gone quiet, or has changed its kits since, is not counted as holding ready copies.
- An order that needs a capability the claim lacks (kit or craft) is no longer failed at once when a bot claims it without the
  flag. The claim gets no order for up to 2 minutes after the first such claim. The order fails only after three or more such claims
  more than 2 minutes after the first, and only when the capability has not come back, or at 10 minutes after the first such claim.
  A new run of such claims starts after 10 minutes without one.

### Security
- **The API key is printed once.** The key appears in the start-up output only on the run that generated it ("first setup: copy it
  now"). Every other start prints only "API key: set". The key stays in `config.properties`, and the website's Link addon button links
  a bot without anyone typing the key. If a start script tees the output to a file, delete the printed key line from that file.

### Known limits
- Exact-amount gathering takes whole stacks only.
- Bots without the v2 hash (`kitsHash2`) keep the v1 score and get no kit orders or restock.
- The Qwerte addon does kit sorting only. Its kit v2 and Kit Builder bot work come in a later addon release, and its local
  `build-kits` stays until then.
- Kit orders and restock have not been tested in game yet.
