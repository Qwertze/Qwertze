# Changelog

All notable changes to Qwerte are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Versions before 0.2.8 are listed on the [GitHub releases page](https://github.com/Qwertze/Qwertze/releases).

## Versioning

Qwerte uses `MAJOR.MINOR.PATCH` versions:

- **x.0.0**: major breaking changes.
- **x.y.0**: new features, bug fixes and more.
- **x.y.z**: minor bug fixes, single one-off patches and hotfixes.

The `0.4.0-dev.N` counting has ended. 0.5.0 is the first release under this scheme. 0.5.x releases are fixes, 0.6.0 is the
next feature batch, and 1.0.0 will mark the first stable release. The version is set by `mod-version` in
`gradle/libs.versions.toml`.

## [Unreleased]

## [0.6.0] - 2026-11-10

A pre-release build from the `dev` branch. Nothing in it has been tested in game yet. It covers everything since 0.5.0.
Works with Stash Manager server 1.1.0 (kit2) or newer, and works alongside Stash Sorter plugin 1.17.0. The details of
kit sorting are in the [README](README.md#kit-sorting).

### Added
- **Kit sorting v2 in the addon's stash bot.**
  - Kit types from the shared `kit-types.json` table: PvP / Crystal, Travel, Redstone, Farming / Food, Building, Starter / Base
    and Other kit. A box is a kit of the type most of its items belong to, with enough kinds of item. A box of one kind of
    item is never a kit, whatever its name.
  - Per-type chests. A kit goes along its chain: its name's chest, its template's chest, its type's chest, then the general
    kits chest. A stored kit only moves up its chain, and never down because its chest filled up.
  - Treat-as: a name can be set to a type, or to "not a kit".
  - Kit templates. A complete copy matches the template's items, counts and components. A part-built copy carries the
    template's name and holds some of its items. Slot order, stack splits, custom names, damage and repair cost are ignored,
    and a `#*` slot takes any variant of its item.
  - Match keys in the stash index (`match` on each item and shulker-box entry), and the v2 kit report, sent with `kitsHash2`
    in the heartbeat.
- **Kits tab rebuilt** (Stash window). It shows where each kit is kept and its status, with Treat-as and the type chests. A
  server older than kit v2 still gets the v1 layout.
- **Kit Builder tab** (Stash window, server mode). A list of the stash's kit templates with their stock, and an editor with a
  27-slot grid. It has any-variant slots, presets, a shulker colour, the restock keep, and unresolved slots shown in red. Order
  appears only where the stash's bot can carry out kit orders (see Known limits).
- **Import local kits** (Kit Builder tab). It makes one template from each kit saved in this client, after a second click to
  confirm. It stops at the server's template limit, skips kits of one kind of item and copies of an existing template, and keeps
  the local kits.

### Changed
- Local `build-kits` skips kits whose name is a dashboard template. Those are left to the Kit Builder.
- Against an older Stash Manager server, which boxes count as kits follows the v2 type table, not the v1 points. A loose
  assortment with no type majority, and a full single-item box of OP items, are no longer kits. The v1 points still show under
  Advanced on the Kits tab while an older bot reports.
- The chat warning for a missing kit chest no longer shows its coordinates. The log keeps them.

### Known limits
- The addon doesn't build kit orders or restock yet (release 2). The heartbeat says so, and the Kit Builder shows "This bot
  can't build kits yet". Until then, local `build-kits` still builds the saved kits whose names aren't dashboard templates.
- Import can't be limited to one server's kits, because saved kits are stored per client.
- Import may stop at the server's 60 writes a minute. Run it again to finish; copies that were already imported are skipped.
- Match keys aren't comparable between the plugin and the addon. After a change of bot, exact template slots can stop
  resolving, and the editor asks you to pick the item again.

### Not tested in game
- The tabs and routing: the Kits and Kit Builder tabs, the import, and kit routing.

## [0.5.0] - 2026-11-10

A pre-release build from the `dev` branch. Nothing in it has been tested in game yet. It covers everything since
v0.4.0-dev.16, including the dev builds 0.4.0-dev.16.1 to 0.4.0-dev.20. Works with Stash Manager server 1.1.0 (kit2) and
Stash Sorter plugin 1.16.1.

### Added
- **Crafting from the stash.** The Printer can order what a stash can craft (`craft-missing-from-stash`, on by default), and
  the stash bot carries out craft orders (`craft-orders`, on by default). The recipe tables come from the Stash Manager server,
  signed, cached and checked against their hash. With no server set, or when the fetch fails, the bot builds the table once
  from the game's client jar. Details are in the [README](README.md#crafting).
  - The Build Planner has a Craft column, Craftable and Missing tiles, and a craft checkbox that adds craft lines to an order.
  - The Orders tab shows craft lines, "Crafted" and "Not crafted" with a reason for each item, and "Returned to the stash".
  - Crafting table for orders: the website's table first; otherwise the bot finds, places or crafts its own.
- **Stash area editor** (the Area page of the Stash window). Corners can be set at your feet or at the block you look at. Each
  side, or all six, can be expanded by N blocks. Include where I stand grows the box to hold your feet. A rectangle dragged on
  the map sets X and Z, and "Use the bot's current area" starts a draft from the bot's area. The draft shows in the world as an
  amber box with its size. Nothing is sent until you press Save; Save sends it to the Stash Manager server, and the bot applies
  it between tasks.
- **Printer phantom blocks** (new Phantom blocks group). The blocks still to place are drawn as translucent ghost models of the
  real block, with its facing and state. A ghost goes away when its block is placed and comes back if it is broken. A wrong
  block gets an outline, or the ghost of the right block over it (`phantom-wrong-style`).
  - `phantom-blocks` (on), `phantom-opacity` (35, from 5 to 100), `phantom-tint` (off) with `phantom-tint-color`,
    `phantom-range` (32 blocks), `phantom-max-blocks` (4096), `phantom-current-layer-only` (on), `phantom-wrong-blocks` (on)
    with `phantom-wrong-style` and `phantom-wrong-color`, and `phantom-hide-with-litematica` (on). The last hides the ghosts while
    Litematica draws the same blocks.
- **Printer `scaffold-length`** (0 means no limit), and `scaffold-max` up to 4096. This was added in 0.4.0-dev.16.1.
- **Order notifications** (new module, in the Qwerte category, on by default). It plays a sound, shows a toast and can write chat
  lines when one of your Stash Manager orders is delivered, delivered short, fails or is cancelled. Settings: `enable` (on),
  `complete-sound`, `partial-sound`, `failed-sound`, `arriving-sound` (none by default), `volume` (100), `pitch` (1.0), `toast`
  (on) and `chat-messages` (off). It needs the Stash Window's `connect-to-server` on and a login.
- **Reset Pearl `silent-rotations`** (on). The pearl throw is aimed with rotations that are sent to the server only, so the
  camera does not move. Walking out of the chamber still turns you.
- **Messenger.**
  - Global view, the New picker and the chat key, from 0.4.0-dev.17: `take-chat-key` (off), `overlay-global` (off) and
    `global-scrollback` (500).
  - `auto-font-scale` (on): the text, rows, badges and emoji grow and shrink with the window, from 0.75x to 2x of its 330 x 190
    default. `font-scale` (1.0) is used when it is off.
  - `system-message-opacity` (55): the console lines in Global (server messages, joins and leaves, Meteor notices) are dimmed.
    Player chat stays at full opacity.
  - `show-player-heads` (on): the player's head is drawn in front of a `<Name>` chat line.
  - Theme: `follow-meteor-theme` (off) uses Meteor's GUI colours all the time. The Theme settings can be copied once with the
    Sync theme with Meteor button.
  - `replace-chat` (off): the Messenger window replaces the game's chat list. See the README for what that hides.
  - `debug-lines` (off, in the Debug group): writes every public chat line to `latest.log`, for troubleshooting only.

### Changed
- `focus-key` defaults to Right Ctrl on fresh settings (it was none before). A key you already set, or cleared, is kept.
- The addon jar no longer carries recipe data. The table comes from the Stash Manager server, or from the client jar.

### Fixed
- Messenger: whispers with a leading timestamp or chat icon are recognised (for example `<15:05> Name whispers: hi`), and your
  own sent messages are not doubled when the server's copy arrives. (0.4.0-dev.17.1)
- Messenger: 1.21.9 and newer player heads no longer give white boxes or a doubled name. The head's text is removed, and a name
  printed twice in a row (`<Steve> <Steve> hi`) is shown once.
- Messenger: a line hidden by `replace-chat` can't be clicked or hovered.
- Messenger: a deleted conversation's draft no longer comes back, a click or Enter in the New picker opens the row shown, and the
  window re-wraps its text once a resize has settled. (0.4.0-dev.17)
- Container Tweaks: the shulker preview's freeze key is read straight from the keyboard, so it works with other mods installed.
  (0.4.0-dev.18.1)
- Stash window, Build Planner: loading a schematic no longer breaks the window (the planner theme crash). (0.4.0-dev.16.2)
- Stash area: `overworld` and `minecraft:overworld` are one dimension. They used to mismatch.
- Crafting: a crafting table the website reports as unreachable is treated as too far away, and the bot's own table reports
  whether it was found, placed or crafted.
- Reset Pearl: a throw is skipped when no pearl is in the hand, and a queued throw expires after 5 ticks if its rotation never
  runs.
- Order notifications: a failed or invalid answer from the server changes nothing, so an order is not announced again or
  forgotten.

### Known limits
- Death messages show no head icon.
- Phantoms are not drawn for block-entity blocks (chests, signs, beds, banners, heads). They have no ambient occlusion, and the
  phantom cap can overshoot by one distance shell.
- Exact-amount crafting gathers whole stacks, and the budget is charged exactly. A broken website table does not fall back to the
  bot's own table.
- An in-game area edit that the server has not acknowledged is lost on a full game restart.
- Kit sorting v2 and Kit Builder bot work are not in the addon yet. The addon still uses v1 scoring and its local `build-kits`.
  The server has them (Stash Manager server 1.1.0).
- Silent rotations may be flagged by some anti-cheats.
- Order notifications need the Stash Window's `connect-to-server` on and a login.

### Not tested in game
- Everything in this release.

## [0.4.0-dev builds before 0.5.0]

Detailed notes for the pre-release dev builds, kept as they were written. The 0.5.0 entry above covers their changes since
v0.4.0-dev.16.

Mirrors Stash Sorter plugin 1.15.1 for crafting from the stash (version 0.4.0-dev.19, which carries out craft orders at parity with it).

### Added (0.4.0-dev.19)
- **The addon's stash bot carries out craft orders**, at parity with Stash Sorter plugin 1.15.1. It needs `enable-crafting` on, `craft-orders` on (the default), and a crafting table for the stash (the website's, or its own).
  - Checks the order before any click: the recipe table, every step, the ingredients and the fill budget must match the bot's own table and the order's limits. A mismatch leaves each craft line short (`recipe_mismatch`), and the rest of the order goes on.
  - Crafts bin by bin: the craft loads of a bin are made first, from the stash's ingredients within the order's budget, in as many table trips as needed, and then the bin is built.
  - A budget ledger keeps what each order has used. It is saved with the order, so a restart carries on with what is left.
  - **Crafting table.** The website's table comes first. With none set, the bot finds a crafting table in the stash area, places one from plain stash items, or crafts one (4 planks, or a log made into planks first) and places it. It then uses that table for the stash until it breaks. The bot puts down at most 1 of its own tables per order and 3 per hour. A table set by hand outside those limits is left for shulker-box crafting, and craft orders use a table of their own.
  - **Results.** `result.craft` reports what was made, what was short and why, and what went back to the stash. The final report waits up to 90 seconds for the put-back first.
  - A circuit breaker gives up on the remaining craft lines after 3 empty chunks in a row.
  - The heartbeat says why no table is usable (`tableWhy`, including `no_material`), so the website can show it.
  - New settings in the Stash Manager module's Crafting group: `craft-orders` (on), `craft-order-max-fills` (2048, from 1 to 100000) and `craft-order-max-per-hour` (4096, at least 1).
  - **Known limits:**
    - Gathering takes whole stacks from the stash and puts back what isn't used. The budget is charged exactly.
    - A broken website table does not fall back to the bot's own table.
  - Not tested in game yet.

### Added (0.4.0-dev.18)
- **Stash Window, Build Planner: crafting.** The planner shows what the stash can make from what it holds.
  - A Craft column shows how many of each row can be made: "-" when there is nothing to make or nothing can be made, "0" when a recipe exists but makes none, and "..." while the recipe table loads. Hover for what is taken from the stash, what is made and what goes back.
  - A row the stash covers entirely by crafting reads "craftable" in the Missing column. A partly covered row shows what is still missing, with "+N craftable".
  - A Craftable tile shows how many items the stash can make and in how many kinds, or why they can't be ordered. The Missing tile shows what is left after crafting. The planner has six tiles while crafting shows, and five otherwise.
  - Banners say when recipes are loading, when crafting info isn't available for the version, when the plan was cut short, and why orders can't use crafting (the bot can't craft, its recipes differ, no crafting table, or an unusable table).
  - A craft checkbox, "Craft what the stash can make (N items from the stash)", adds the craft lines to the order. It is on by default when the stash can take craft orders. Otherwise it is off and disabled, with the reason beside it.
- **Orders tab: crafting.** A craft line reads "Craft: 8 Spruce Stairs". Finished orders show "Crafted: ...", "Not crafted: ..." with a reason for each item, and "Returned to the stash: ...".
- **Printer, `craft-missing-from-stash`** (on by default; it shows only while `order-from-stash` is on). The description says it uses stash materials automatically. When no stash holds an item the printer needs, but a stash can make it from what it holds (logs for planks and stairs, for example), the printer orders it crafted.
  - It asks the server's plan for up to 3 stashes, adds craft lines alongside the item lines, and never orders the same thing twice.
  - It never re-crafts a cancelled or failed item. It waits up to 3 minutes, and only for items a busy stash may hold.
  - After three 503s from the server, that order goes ahead without crafting, and that stash is left out of crafting for 5 minutes. A stash the server doesn't know is left out of crafting alone; crafting stays on for the others.
  - Crafted items count toward `order-max-shulkers` and `order-max-shulkers-per-run`.
  - Chat: `Ordered 64 Oak Planks + crafting 8 Spruce Stairs (from 2 Spruce Log) [2 shulkers] from stash NAME @ SERVER [order ID].`
- **Recipe tables are no longer bundled.** The addon's jar has no recipe data. It gets the table from the Stash Manager server (signed, cached and checked against its hash), or, when no server is set or the server's fetch fails, builds it once from the game's own client jar. Existing crafting (shulker boxes, compaction, chests and table finding) waits while the table is on its way. When there is no table, crafting reports that there is no recipe table.
- Not tested in game yet.

### Fixed (0.4.0-dev.18.1)
- Container Tweaks: the shulker preview's freeze key is read straight from the keyboard, so it works with other mods installed (before, holding Alt did nothing with some of them). It is still hold-to-freeze.
- Not tested in game yet.

### Fixed (0.4.0-dev.17.1)
- Messenger: incoming whispers were not picked up when another mod puts a timestamp or chat-head icon in front of chat lines (for example `<15:05> Name whispers: hi` on 2b2t). Leading timestamps (`<HH:MM>`, `[HH:MM]`, `[HH:MM:SS]`, `<3:05 PM>`), icons and colour codes are now ignored before matching, for every whisper format and your own patterns. Your sent messages are not doubled when the server's `to Name:` copy arrives.

### Added (0.4.0-dev.17.1)
- Messenger: a dot before each name in the New list and the conversation list: filled green when the player is in the server's tab list, a hollow red ring when not. Hovering it says "online (in tab list)" or "not in tab list". Updates about twice a second.
- Messenger: `focus-key` defaults to Right Ctrl on fresh settings (a key you already set, or cleared, is kept). The overlay hint names the key, or your real chat key.
- Not tested in game yet.

### Added (0.4.0-dev.17)
- **Messenger, New picker**: the New button opens a search field and a list: Meteor friends, then players online (tab list), then past conversations, without doubles and without yourself.
  - Typing filters the list; names that start with the text come first in each group. An exact match goes to the top. A valid typed name (3 to 16 letters, digits or `_`) that is not in the list is offered as "Message <name>".
  - Click or Enter opens or creates the conversation and sends nothing. Esc closes the picker.
  - Names the whisper command can't address (such as Bedrock `.Name`) are not listed.
- **Messenger, Global**: a pinned entry at the top of the left pane, selected by default.
  - It shows public chat as the chat shows it after other modules (cancelled lines are left out). Whispers stay in their own conversation.
  - Typing there sends public chat through the game's normal chat send, so a Meteor command (the Meteor prefix, `.` by default) runs as a command, and chat modules such as BetterChat may change the text. `/` sends a command; a space right after the `/` is refused.
  - Global sends use the same queue and rate limit as whispers (one per `send-interval-ms`, at most 3 waiting, never retried, dropped on a server change). A command's arguments are never shown in the log.
  - `global-scrollback` (500 by default, 50 to 2000): the most public chat lines kept per server.
  - Clear (with confirmation) empties Global.
- **Messenger, history for Global**: with `save-history` on, Global is saved too, per server. Saving runs in the background: whispers every 20 seconds, Global every 5 minutes, and everything when you leave a world or turn the module off. The file is written owner-only where the system supports it. Older history files still load.
- **Messenger, `take-chat-key`** (off by default): the chat key opens the Messenger on Global instead of the normal chat; `/` still opens the normal chat. Only a chat opened with empty text is taken: on 1.21.11 a saved vanilla chat draft still opens the normal chat, and another mod's empty chat screen is taken too.
- **Messenger, `overlay-global`** (off by default): shows the public chat in the always-on overlay while the window isn't focused. Off, the overlay shows the first conversation in the left pane (friends first, then most recent) instead of Global.
- **Messenger, left pane**: Global, then the conversations (friends first, then most recent), each with its unread badge. Delete asks for confirmation.
- Not tested in game yet.

### Fixed (0.4.0-dev.17)
- Messenger: a deleted conversation's draft no longer comes back.
- Messenger: a click or Enter in the New picker opens the row that is shown.
- Messenger: the window re-wraps its text once a resize has settled, instead of on every frame.
- Not tested in game yet.

### Fixed (0.4.0-dev.16.2)
- Stash window, Build Planner: loading a schematic no longer breaks the window with "the window's layout failed". The rows of the materials table and the chest contents card were built without a theme. A safety net now gives any widget left without a theme its parent's theme before layout, and logs one warning instead of failing.
- Not tested in game yet.

### Added (0.4.0-dev.16.1)
- Printer, `scaffold-length` (Order group, 0 by default, 0 to 256): the longest scaffold chain, and how far outside the schematic it may go. 0 means no limit on either. Scaffold still never goes in a cell the schematic wants a block in.
  - Before, both limits were fixed at 6 blocks.
  - `scaffold-max` now goes up to 4096 (64 by default). It and the spare blocks carried are the real cap on a chain's length.
  - The search tries the straight columns from the cell's faces first, then an L-shaped chain, then a short search for a shorter chain within the time budget. A long column down to the ground is found. Long winding detours may still not be found.
  - The "No scaffold can reach..." warning names the limit only when one is set, and only appears after a real search.
  - New warnings: a chain needs more blocks than `scaffold-max` or the spare blocks allow; no room for more scaffold; the search took too long (that cell is given up on for a minute). Each warns once per layer.
  - Scaffold is never planned outside the world's build height.
  - A long chain is no longer dropped halfway: unplaced scaffold blocks expire 15 s after the chain last made progress (placing, or walking to it), with a hard cap of 15 s plus 2 s per scaffold block.
  - Not tested in game yet.

### Added (0.4.0-dev.16)
- **Messenger** (new module in the Qwerte category): a small chat window that shows your whispers, keeps a history per player and sends from the window, with emoji.
  - Window: drawn over the game (`show-overlay`, on; `window-opacity`, 75). It takes the keyboard and mouse only when it is focused: with `focus-key` (none by default), or by clicking it while the chat is open. Esc always lets go. `notification-sound` (on) plays a quiet sound for a whisper you are not reading. The chat draft is not carried into the window, which has its own input line.
  - Whispers: the game's own translation key (vanilla), and the patterns `pattern-vanilla`, `pattern-2b2t`, `pattern-essentials` and `pattern-from-to` (all on). `extra-patterns` takes your own, one per line: `in:` (to you, the default) or `out:` (from you), then a regular expression for the whole chat line with `(?<name>...)` for the other player and `(?<msg>...)` for the text. A bad pattern is refused in chat; a slow one is switched off. A decorated name is resolved from the display name's own insertion, else the one online player named in the text, else its last word.
  - Sending: `command-template` must be exactly `/<command> {name} {text}` (default `/msg {name} {text}`; `/w {name} {text}` on 2b2t): one command word, then `{name}`, then `{text}`, and nothing else. Anything else is refused with a warning. Nothing is sent unless you press Enter or Send. Messages go out at least `send-interval-ms` apart (1500 by default, 500 to 10000); three may wait, and more are refused.
  - History: kept per server (its address, or `local` for singleplayer and LAN), `max-history` per player (200 by default, 10 to 500). `save-history` (off by default) writes `meteor-client/qwerte/messenger-history.json` (version 2: the conversations of each server), at most every 20 seconds, when you leave a world and when the module is turned off. A version 1 file's conversations go to the server you are on. "Clear history" forgets everything and deletes the file.
  - Emoji: drawn as Twemoji pictures in messages and in the picker (see Third-party in the README).
  - Not tested in game yet.
- Printer, **tool actions** (generalises the stripped-log feature): a block that a tool finishes is placed in its raw form and the tool is used on it at once. Each tool has a pending list, one warning when it is not carried, a walk back when it turns up, a give-up time, a hold on the end of the run only while it can progress, and its own report line.
  - Axe: stripped logs, woods, stems, hyphae and bamboo blocks (as before; `AxeItem.STRIPPABLES`). With a shield in the off hand the Printer still sneaks for it (the shovel and hoe have no such rule in vanilla).
  - Shovel: dirt paths from grass block, dirt, podzol, coarse dirt, mycelium or rooted dirt (`ShovelItem.FLATTENABLES`, read through a subclass); a campfire the schematic wants out (`lit=false`) is doused. Dirt is placed, then flattened. Never the bottom face, and only with air above.
  - Hoe: farmland (from dirt, grass block or dirt path). Farmland counts as dirt in the materials. Coarse dirt and rooted dirt are not hoed into dirt: a wanted dirt cell holding them is a wrong block. The hoe needs air above, so nothing is placed above farmland until it is tilled (cells above it wait while a hoe or shovel is carried).
  - Honeycomb: waxed copper, when the waxed item is not carried but the unwaxed one and honeycomb are (`HoneycombItem.WAXABLES`). Without honeycomb the unwaxed copper is not placed (it would age).
  - Shears: a carved pumpkin is a pumpkin with shears; the side clicked gives the facing.
  - Flint and steel: a candle the schematic wants lit. Never on a waterlogged candle.
  - Flower pots: a `potted_*` block is an empty flower pot with the plant put into it (right-click with the plant item, not sneaking). The materials count the pot and the plant. An empty pot is "right block, needs the plant"; a pot holding another plant is a wrong block.
  - The tool is moved into the hotbar by the same helper as the building blocks and never displaces weapons, food or shulker boxes.
  - Report lines: Stripped, Flattened or doused, Tilled, Waxed, Carved, Lit, Potted; and the lists Unstripped, Not flattened or doused, Not tilled, Not waxed, Not carved, Not lit, Pots without plant.
  - `StripRules` is replaced by `ToolRules`, `ToolAction`, `Tool` and `HandTables` (pure, tested); `ToolTables` and `ShovelTable` read the game's tables. No mixin.
- Printer, **schematic queue** (Source group: `queue-enabled`, default off, and `queue`, a list): the Printer builds the entries in order and goes on with the next one without turning off. An entry is a Litematica placement name (exact name first, else the one placement whose name contains it) or `file:<name>` for a `.litematic` in the schematics folder; `file:<name>@x,y,z` gives that file its own origin (strictly parsed), otherwise `origin-x/y/z` apply, and `rotation`/`mirror` always apply to file entries. File names go through the same checks as the file source (`SchematicFile`: no `..`, no absolute or drive paths, no links, at most 2 subfolders). At most 50 entries are used. A bad entry is reported when the Printer is turned on; a missing, ambiguous or unreadable entry is skipped with a warning (chat only with `chat-messages`) and the queue moves on.
  - When an entry finishes: its report, the finish sound, "Queue n/m: name done.", then the next entry starts from a clean run (same teardown as a reload: scaffold already removed by FINISH, rechecks, tool and water jobs, layer cursor and the stash order limits are reset; each entry counts as its own build for the order limits). After the last one: "Queue done: N schematic(s) built".
  - Cancelling or leaving the world stops the queue ("Queue stopped at Queue n/m"); the position is not kept: turning the Printer on starts again at the top. The info line shows "Queue 2/5: name".
  - Pure `BuildQueue` (parse, position, seek with an injected resolver) is tested; `LitematicaBridge.byNameProblem` tells "no placement" from "several match". No mixin.
- Printer, **re-checking finished cells** (`RecheckQueue`): a finished cell is looked at again about 5 seconds later and then every 10 seconds while it is in reach, at most three times per run, and at most 50,000 cells are kept. One that became wrong, empty or dry goes back on the right queue (a log that needs its axe again, a candle that went out, farmland that needs its hoe).
- Printer, **finish and error sounds** (new Sounds group: `finish-sound` and `error-sound`, both on, with `finish-sound-event` (level up), `error-sound-event` (anvil land) and `sound-volume` (1.0); at most one sound every 1.5 seconds). The error sound also plays when the Printer pauses because it needs you (stuck, no materials source, a failed fetch).
- Printer, a warning once per run when another printer, kill aura or crystal aura is on (they fight over the look and the hotbar).
- Printer, the Litematica layer mode is logged when it is read and when it changes.
- Printer, **true layer building** (new `layer-order` setting in the Order group: BottomToTop (default), TopToBottom, MostEfficient or Off). Only the active y level is printed; the next one starts when it has no open block left, and the run only finishes when every level is done.
  - New pure `LayerCursor` (active level, advance, carry-over of stalled levels, an open gate after a round with no progress so it can't deadlock) and per-y counts in `TargetBits` (O(1) per add or remove). `BuildOrderer.setActiveLayer` skips every other section and slice, for the near search and the far (walking) search alike.
  - The `order` setting ranks blocks inside the layer; `bottom-up` is hidden unless `layer-order` is Off. Litematica's render layers still decide which blocks exist.
  - Wrong blocks are only handled in the active level and in levels already passed. A level change is logged and sent to the chat (with `chat-messages`) as "Layer Y=...: N blocks". The "Stuck" pause waits for the layer cursor to carry levels over first.
- Printer, **scaffolding** (`scaffold`, on by default with `layer-order`; `scaffold-max`, 64): a block with nothing to be placed against gets a chain of up to 6 spare blocks (carried blocks the schematic does not use) from an existing block to one of its faces; the pure `ScaffoldPlanner` searches it (a time budget per tick, results cached). The chain is placed like any block (the Printer walks to its base), never inside a cell the schematic wants a block in, within 6 blocks of the schematic's area. It is broken again once no open block needs it (`REMOVE_SCAFFOLD` step) and always before the run finishes. Printed blocks are never broken for access; with no spare block or no chain, the block is carried over with one warning.
- Printer, **movement for tall and many-layer builds** (Baritone group; `walk` stays the master switch):
  - `movement`: **StandOnBuild** (default), **Fly**, **StandThenFly**. Flying is only used when picked, and can get you kicked on servers that do not allow it (for example 2b2t's anticheat).
  - StandOnBuild: Baritone walks and climbs onto the printed blocks, and may place your spare blocks (the scaffold definition, as `acceptableThrowawayItems`; `allowPlace` on, `allowBreak` off). Placing is on only for the building walk and off for container and shulker trips; the user's Baritone values are given back. Blocks it placed outside the schematic are noted and broken at the end like scaffold (`remove-stand-blocks`, on); inside the schematic they are ours and are broken whatever `wrong-blocks` says. A stand block is never broken while a walk is going or within 2 blocks sideways of the feet and at or below them; they come down from the top, and the player is walked away from the last ones. A stand block that is still standing after 3 failed walks away from it is left and listed. New `BaritoneHelper.allowPlacing`, `forbidPlacing`, `placingAllowed`.
  - Fly: `FlyController` switches a flight module on (`flight-modules`, default Meteor's Flight; the first one already on, else the first listed) and holds the movement keys toward a hover point chosen by the pure `HoverPoint` (above or beside the active layer, never in a solid, fluid or still-to-build cell, within reach) with the keys from the pure `FlySteer` (works against the current yaw, the camera is never turned). It lets go of the keys while placing, on turning off, on a screen opening and after a damage spike of more than 3 health in one tick. It switches the module off only if it switched it on and the player would not fall far; solid ground or water below counts as safe, lava or another fluid does not. The module is only remembered as ours when the toggle switched it on, and shift is not pressed or released while the Printer sneaks to place.
  - StandThenFly: the pure `MovePolicy` flies after 5 seconds with no progress or two failed path searches in a row, and walks again after at least 3 seconds of flying, 1 second on the ground and a doubling back-off (10 seconds at first, at most 1 minute). Its progress track starts again after a placing period, so a later walk does not fly at once.
  - Path searches that fail are not repeated for 1 second.
  - The spare-block list is the pure `SpareItems` (scaffold uses it too).
  - Limits: trips for containers from the top of a tall build can find no path (`safe-walk` cannot climb down); modules that do not move you with the movement keys are not supported.
  - Not tested in game yet.

### Changed (0.4.0-dev.16)
- Printer: rotations go out at priority 100 with the client-side look set, so another module that rotates in the same tick no longer takes the look (waterlogging with a bucket failed that way).
- Printer: pausing and resuming has hysteresis. A pause or a resume is announced only once it has held for 1 second, except STUCK, LAYER and RESUPPLY_FAILED (the "Stuck", "Layer done" and "could not get the materials" pauses), which are announced at once. Pauses that must stop walking (inventory full, Baritone busy, walking off) stop it at once, and only the message waits. When work goes on the phase is BUILDING at once.
- Printer: "inventory full" is only said when there is a source to fetch from.
- Printer: blocks can be placed from the off hand.
- Printer: when `wrong-blocks` changes in the middle of a run, the cells that were skipped as wrong are looked at again.
- Printer: a flower pot is only placed when its plant is carried too (the missing plant is named), and the honeycomb lack names the honeycomb.
- Printer: the materials summary counts both the empty pot and the plant of a potted plant.
- Printer: a hoe or shovel job under a solid block no longer holds the end of the run or attracts walking; a missing tool is looked up once per tick and cached for a second.
- Printer: before a materials, shulker or ender chest trip, a flying player lands first (a flight module the Printer switched on is switched off on the ground). With no safe ground below, the trip is skipped with a message.
- Printer: scaffold removal walks never place; the scaffold search time is per cell and grows up to 2 ms for a cell that keeps running out of it; only planned blocks count toward `scaffold-max`; scaffold blocks that are gone from the world are dropped.
- Reset Pearl: it closes only covers, never the stasis trapdoor at the water. With no trapdoor at the water, the lowest trapdoor is the stasis one only when it is at most 2 blocks above the water top (and there are two or more trapdoors); otherwise every trapdoor is a cover. A cover at or below the block above the pearl is kept open, and the module says so once in chat and in debug ("Left open on purpose"). The pearl margin is 0.3. Nothing is closed until the pearl is resting: slower than 0.2 blocks per tick, or, for a pearl in an upward bubble column, within 0.05 blocks in height for 3 ticks.
- Not tested in game yet.

### Fixed (0.4.0-dev.16)
- Printer: stale run timers are reset on a new run (a false "Stuck" after a restart).
- Printer: the slab recipe cache no longer merges slabs: a recipe worked out without a neighbouring slab of the same block is not reused with one.
- Printer: a wrong block next to a waterlogged block (slab, stair, kelp, seagrass, or a waterlogged wrong block itself) is broken only when its replacement can be placed straight after (the item is carried, the wanted block is not air, and a placement reaches the cell from where you stand); otherwise it is skipped as "next to water". A real water or lava block next to it always stops the break.
- Not tested in game yet.

### Added (0.4.0-dev.15)
- Printer, `chat-messages` (off by default, General group): with it off the Printer sends no chat line at all (progress, warnings, errors, stash order lines, the toggle line); all of it goes to the game log.
- Printer, leaving the schematic area (farther than your reach from every region) warns once per exit (logged; chat only with `chat-messages`) and the Printer keeps running instead of ending.
- Printer, stripped logs, woods, stems, hyphae and bamboo blocks: the stripped item is placed as it is when carried; otherwise the unstripped block is placed with the wanted axis and an axe is used on it at once (face toward you, shield in the off hand handled by sneaking). Without an axe it warns once, keeps the log on a pending list and strips it when an axe is carried and the log is in reach. An unstripped log with the right axis is "right block, needs strip", not a wrong block. Vanilla's table `AxeItem.STRIPPABLES` is read through a subclass (no mixin).
- Printer, `disable-when-out-of-materials` (off): off, an item running out never turns the Printer off (it skips those blocks, places the rest and returns to them when the item turns up); on, it turns itself off when nothing can supply the item. Stash order waits are unchanged. The decision is in `MaterialPolicy`.
- Printer, waterlogged blocks are placed and then filled with a water bucket (`waterlog`, on). Without a bucket they are placed dry with one warning. `refill-water-bucket` (off) picks water up again from a source in reach when an empty bucket is carried. They were skipped and listed before.
- Printer, Litematica's render layers (single layer, all above, all below, layer range, any axis) limit what is placed, counted, planned and ordered; read again every 5 ticks. Read by reflection through `LitematicaBridge` (`DataManager.getRenderLayerRange()`, malilib's `LayerRange`); the range test is a tested copy of malilib's.
- Printer, wall-attached blocks: redstone wall torches (they were skipped: the class is not a `WallTorchBlock`), tripwire hooks, wall coral fans, wall hanging signs and cocoa pods are placed; wall torches (including copper in 1.21.11), wall signs, banners, skulls and heads, ladders, buttons and levers were already.
- Not tested in game yet.

### Fixed (0.4.0-dev.15)
- Printer: it no longer turns itself off when nothing can be built for 3 seconds (typically after you leave the area, when the rest of the schematic is in unloaded chunks). It pauses with "Stuck: N cell(s) can't be built right now ...; the Printer keeps running and tries again", lists the first cells in chat and log once, and resumes by itself when something becomes placeable or walkable. Only "everything that can be built is built" ends the run. The nearest unloaded target is now remembered at once, so the Printer walks toward it.
- Printer: unstripped logs with no axe, and blocks waiting for water, no longer keep the Printer on at the end. A strip or water job holds the end only while it can progress (axe or bucket carried, block loaded, within its time limit). The report lists the unstripped logs and the blocks left dry.
- Printer: with Litematica's render layers on, when the current range is done it pauses with "Layer done: move Litematica's render layer to continue (N block(s) outside it)" and carries on when the layer changes. The same happens at the start when the range holds nothing to build.
- Printer: the water refill only takes from real water source blocks, never from a waterlogged block or a cell the schematic builds in. Dry blocks that were already there are walked to for waterlogging. With `rotation-mode` None, waterlog and refill are skipped with one warning (a bucket needs a look).
- Printer: a failed read of Litematica's render layers keeps the last range that was read (before any good read, everything is built), retries 5 seconds later and logs once; only a lookup failure turns the feature off.
- Printer: the axe is only used on the unstripped partner of the wanted block. The skipped totals still count out-of-range cells (not changed). Ceiling hanging signs are still skipped.

### Changed (0.4.0-dev.15)
- Printer: `visible-faces-only` is now `require-line-of-sight` (same default and meaning). The old saved value is dropped; Meteor ignores a saved setting it no longer knows.
- Printer: only hanging signs that hang from a ceiling are still skipped.
- Not tested in game yet.

### Added (0.4.0-dev.14)
- **Kit sorting** (Stash Sorter setting `kit-sorting`, on by default). A shulker box that scores as a kit goes to the chest picked for it on the Stash Manager website or in the Stash Window's Kits tab. The score has four parts: `name` 2 for a custom name with the word "kit" or "kits" (a whole word: "PvP Kit" and "kit_3" count, "Kitchen", "Toolkit" and "Kitsune" don't); `mix` 3 for a group of items from the shared `kit-items.json` table (for example three pieces of gear, or five wool colours); `full` 1 when all 27 slots are filled; and `op` 4 when the box holds at least `opMin` OP items (3 by default), counted per item by stack count (3 totems, or a sword and 2 god apples, qualify). A box is a kit when its total reaches the threshold (4 by default). A part-filled box of one item is never a kit, and an empty box never is. The points, threshold and `opMin` are set per stash on the website and in the Kits tab.
- **Where kits go.** A named kit with a rule for its name goes to that rule's chest. Otherwise it goes to the kits chest, and unnamed kits always go there. A kit with neither sorts exactly as before, so with no kits chest set, only kits that have a rule change. A shulker that KitBuilder already routes (a finished or part-built kit) keeps KitBuilder's routing.
- **Kit-reserved chests.** The kits chest and every kit's chest hold only kits. Nothing else is sorted into them, and they are left out of overflow, sign scoring, the Tiers plan, compacting, repacking and auto-labelling. A put-back into one happens only for a kit routed to it.
- **Kits kept whole.** A kit with a target (a rule or the kits chest, including one that is missing right now) is never unpacked, repacked or compacted. A kit with no target is repacked as before.
- **Missing or full chests.** A chest that is missing or can't take kits (not read yet, excluded, a drop-off, unreachable) gives the kit no destination: a kit already in the stash stays where it is, one from a drop-off or your inventory goes to overflow, and a warning is given once per kit and chest until the settings change. A full chest takes the normal path, so stored kits move once it has room.
- **Offline cache.** The settings the Stash Manager server sends are kept per server and stash in `meteor-client/qwerte/kit-settings.json`, so routing keeps working while the server is unreachable. Settings that fail their check are ignored whole and logged once. With no settings at all, sorting is as before.
- **Kit report.** The bot tells the server which containers can take kits (with their free slots), where each kit is and its score, and which kits it carries. It sends this only when the server asks or the report changed, at most every 10 seconds, and waits 5 minutes after an owner conflict. Only the first line of a sign is sent, cut to 32 characters. Turning `kit-sorting` off stops all of this and keeps the saved settings.
- **Consequence of the defaults.** A full single-item box of OP items, for example 27 totems (the way the compactor may make them), counts as a kit. Once a kits chest is set, it goes there. Raise the threshold, or set `op` to 0, to keep those boxes in their item chests.
- **Kits tab in the Stash Window** (needs a server connection and the `view` permission; not shown to a user the owner forced into streamer mode). It has a kits chest picker with **Clear**; a **Chest** dropdown for each named kit, with "Kits chest (default)" to remove its own chest; a table of every kit the bot found (name, count, score such as "10/10: name 2, mix 3, full 1, OP 4" with "(lowest 8)" when boxes of one name score differently, where it is now, where it goes, a status and its chest; up to 500 kits); and a **Kit scoring** editor for the threshold, the four points and "OP items needed", with **Save** and **Reset to defaults**. Clearing a chest, removing a kit's chest and resetting the scoring need a second click. Choosing a chest whose sign doesn't say kit asks first. Changing anything needs the `settings` permission. In the window's own streamer mode kit names show as "Kit 1", "Kit 2" and so on, and chests as "Chest 1" and so on, with no label or position. The tab refreshes every 5 seconds while it is open.
- Not tested in game yet.

### Changed (0.4.0-dev.13.1)
- Printer: `pickup-timeout` is shown while `use-shulkers` or `use-ender-chest` is on (before, only with `use-shulkers`).
- Not tested in game yet.

### Added (0.4.0-dev.13.1)
- Printer: a **Stash Orders** group. With `order-from-stash` on (off by default), the Printer orders what it is running low on from the stash bots on the current game server, through the Stash Manager server, with no confirmation. It orders ahead of need and in batches, keeps one open order per stash, never re-orders what an open or uncollected delivery already covers (a partial delivery may hold off a re-order for up to 20 minutes), and prints one chat line per order. Settings: `order-when-below` (64), `order-max-shulkers` (9) and `order-max-shulkers-per-run` (27). It needs the Stash Window module with connect-to-server on, and a logged-in account with the order permission.
- Printer: when it is out of materials, it keeps building with what it carries, then pauses "waiting for" the stash order. It never travels to the delivery chest: you collect the delivery and put it in your inventory, and printing resumes by itself.
- Printer: a new ender chest source, `use-ender-chest` (off by default), with `ender-chest-no-silk` (Refuse by default, or MineAnyway). It places your carried ender chest, takes out shulker boxes that hold needed materials, unpacks them, places the chest again to put the emptied or leftover boxes back, and mines it back with silk touch. With MineAnyway and no silk touch, the chest block is lost (its contents stay safe on the server) and the boxes stay in your inventory with a warning.
- Not tested in game yet.

### Removed (0.4.0-dev.13.1)
- Printer: the `use-stash-index` setting, which walked to containers in the Stash Window's local index. Meteor drops the old saved value. For stock from the stash, use `order-from-stash`.

### Added (0.4.0-dev.13)
- Printer: a new module in the Qwerte category that builds a Litematica schematic in the world, one block at a time, with each block's facing and state. The source is the latest Litematica placement, a placement by name, or a `.litematic` file from your `schematics` folder. Litematica is optional.
- Printer places stairs, slabs, doors, trapdoors, fence gates, beds, repeaters, comparators, note blocks, chests, signs, candles and many more. It skips fluids, waterlogged blocks, block-entity contents (sign text, chest contents) and anything else it can't reproduce, and lists what it skipped.
- Printer fetches materials from your inventory, its shulker boxes, the containers you list and the Stash Window's index. It walks with Baritone when that is installed. It places one block per tick by default; raising `blocks-per-tick` can get you kicked on 2b2t.
- Printer lists the shulker boxes it leaves behind, the same way Container Tweaks does.
- Not tested in game yet.

### Added (0.4.0-dev.12)
- Container Tweaks: a new module in the Qwerte category. Hover a shulker box in a container or your inventory to see what is inside as a grid. Hold the freeze key (Left Alt by default) to freeze the grid, then hover an item for its full tooltip. Meteor's own shulker preview is hidden while it is on, and Meteor's settings are left alone. `hide-text-list` also hides the text list of contents.
- Shulker icons: the item a shulker box holds the most of is drawn on it in containers, your inventory and the hotbar. Settings: `icon`, `icon-scale`, `icon-corner` and `hotbar-icon`.
- Smart Shulker and Smart Echest (both off by default): right-click a shulker box or ender chest in your inventory or a container to place it at the closest spot in reach, open it, and pick it up again when you close it. It says why when there is no spot. With Baritone it walks back for anything out of reach. Anything left behind is listed with its coordinates when you return to that world.
- Smart Echest without a silk touch pickaxe: `no-silk-touch` is Refuse (the default), Place and leave, or Mine anyway (drops 8 obsidian and loses the ender chest).
- Not tested in game yet.

### Fixed (0.4.0-dev.11.2)
- Stash Window: no longer gets stuck on "Connecting..." after it has been opened a couple of times. Two slow replies could tie up both of the window's network threads, so every later request waited forever. Replies are now read without holding a thread, every request has a total time limit, and an oversized reply is refused at once.
- Not tested in game yet.

### Fixed (0.4.0-dev.11.1)
- Stash Window: text no longer runs past its card or over other text (Bots cards, Activity "Now doing", Live stats row, the Stash detail drawer, every page at small windows and large GUI scales). Bold and coloured text keeps its formatting when it wraps; long ids break.
- Live: the hotbar is no longer cut off; the map shrinks to fit, and the map and chat stack in narrow windows.
- Item tooltips in the Stash grid, the Orders picker and the delivery chest now show what the website shows (details, durability, count with stacks and shulkers, shulker contents, dimmed id).
- Not tested in game yet.

### Added (0.4.0-dev.11)
Not tested in game yet.
- Orders: repair items with XP bottles before delivery. An order can ask for the cart-wide `repairAll` switch or a per-line `repair` toggle (Mending items only). Before each shulker is built, the bot takes the damaged item and the bottles o' enchanting out of the stash, stows its own Mending gear (armour, offhand) and always puts it back, holds the item in the main hand and the bottles in the offhand, and throws bottles at its own feet, looking straight down and standing still, checking the item after each batch. One bottle is about 14 durability (`ceil(damage / 14)`). If a player comes within 8 blocks or the orbs vanish without repairing, the throwing stops and is tried once more per order (kept with the saved order); after that the rest of the order's repairs are delivered as it is ("interrupted"). The throwing is capped at about 2 minutes per order. Leftover bottles are put back by the usual leftover return. Repair never fails an order: items that were not fully repaired are delivered as they are, `result.repair` (`bottlesUsed`, `repaired`, `partial`, `skipped`) tells how it went, and the order message says "N item(s) not fully repaired: reasons". The heartbeat sends `repair: true`.
- The index sends `mending: true` for stacks with the Mending enchantment (by registry key). The window's models read `repairAll`, line `repair`, `repairEstimate`, `available.bottles`, index `repairable` / `repairBottles` / `mending` (older indexes: a `Mending` entry in the details), the stash entry's `repair`, and `result.repair`; local orders store the same fields. `RepairEstimate` holds the shared formula and the estimate.

### Changed (0.4.0-dev.11)
Not tested in game yet.
- Stash Window rebuilt to match the website's layout. The header has the palette button (it opens Settings > Appearance) and your account's role label. Users and Appearance moved into Settings: Users is a card there for the owner, and the Users tab is gone. The bot settings are in Settings, one section at a time, picked from a drop-down.
- Orders has two columns: the order maker, with item-type buttons, and the cart, with the delivery chest, the cart lines, the shulker names and the Repair box ("Repair all items", and a "Repair" toggle on each repairable line). The order history is below them.
- Build Planner shows five summary tiles. Bots shows a card per bot above the stash table. Activity shows Now doing and Events side by side, then the modules and plugins.
- Live has a Chat column beside the map, and the bot's 27-slot inventory with its hotbar and gear. Console keeps its input row pinned at the bottom.
- Not matched to the website yet: no drag-and-drop in Orders, no drop zone in Build Planner, no latency overlay on Live, and Settings shows one section at a time.

### Added (0.4.0-dev.10.11)
- Stash Window: new in-game pages for the Stash Manager server. They need `connect-to-server` on and a login, and each one shows only when your account's permissions allow it. **Build Planner** (`planner`): pick a schematic from the game's `schematics` folder or upload one, see its materials against a stash (need, in stash, missing), order them part by part (`plannerOrder`), and Copy missing. **Bots** and **Activity** (`activity`; Activity also needs the picked bot to report activity). **Proxy** (`proxy`): the picked bot's proxy settings, in the same editor as Settings. **Console** (`console`): the picked bot's console, which asks for your password again when it wasn't typed in the last 15 minutes.
- Stash Sorter: when it walks to a container to read it, it now stops as soon as the container is within `reach`, instead of walking to a spot right beside it. A container up high is opened from below, the way a player would, rather than needing a free spot next to it.

### Fixed (0.4.0-dev.10.10)
- Stash Sorter: guards against a stash file with a container millions of blocks away (the case that stalled every scan in the proxy plugin). A box that stops being a delivery box (a different delivery container, the delivery storage forgetting its extra chests) gets every flag delivery set back, including "never gather for compacting" and drop-off, and a box that was never read (no contents and no read time) is removed instead of kept as an ordinary container. A delivery spot (the pick, or stash-manager's selector) that is not loaded and more than 128 blocks from you is refused with a message, and leaves no box behind. Extra delivery chests remember a container's earlier excluded and drop-off marks and give them back. Stash with no area: a never-read container more than 20000 blocks from every read, non-delivery container is removed on load and during scans (at least 5 such read containers are needed), after the stash file is copied to a `.prune-bak-` file (nothing is removed if the copy fails); the box is logged at INFO.
- Stash Sorter: pruning of containers outside the stash area (dev.10.9) is softer. Only a never-read one is deleted. One that was read is kept in the file but skipped by scanning, reading, sorting, orders, crafting, kits, the Stash Manager index and the overlay (logged at INFO). The dimension guard is unchanged. Setting or changing the area works the same way: it deletes only never-read containers outside the new area (never a delivery container), and keeps the read ones, skipped as above.

### Fixed (0.4.0-dev.10.9)
- Stash Sorter: the scan could stall for minutes on one container ("47/48 containers read" and nothing for a long time). Causes: a walk to a container that went nowhere only ended after 60 seconds (and then the container was dropped for good); a read that hung in any other way only ended at the 4 minute job watchdog; and the retry of a container that failed was a check for "the tick counter is a multiple of 1200 on a tick the idle code happens to run", which could miss for many minutes or never happen. Now a read gives up on a walk that gets no closer than a block in 30 seconds (the constant `SURVEY_WALK_STALL_TICKS`, not a setting), on a window that does not open in 4 seconds (as before, the container-delay wait does not count), and on anything else in 90 seconds. The container is skipped, the reason is logged at INFO and shown in the status line ("skipped chest at X Y Z: could not reach it (walk made no progress for 30s); will retry in 1 min"), the scan carries on with the rest, and the container is tried again after 1 minute, then 2 minutes, then every 5 minutes, checked every tick. A container that is read, or that a player used, starts again at 1 minute. A container with no path is skipped the same way instead of being given up on for good. Other jobs keep their old limits. A container outside the stash area (a foreign or damaged save, or a pick) is dropped on load and during scans, but only when the stash's dimension is known and is the one you are in; a container whose block is gone is forgotten at once instead of being walked to. Turning off the `barrels` setting no longer forgets indexed barrels: they are only skipped. Waiting containers keep their retry time when the sorter re-plans.

### Added (0.4.0-dev.10.8)
- Stash Sorter: new setting "container-delay" (General, ticks, default 0, range 0-100) to control how fast the bot goes through containers. A container (chest, barrel, crafting table, anvil, or a shulker box the bot placed) is not opened until at least this many ticks after the last one was closed, and the first click in a freshly opened one waits this many ticks more than `delay`. The wait does not count toward the open timeouts. A higher value is slower but gentler on servers; 2b2t can kick or rubber-band when containers are opened very quickly. 0 keeps the old behaviour exactly.

### Fixed (0.4.0-dev.10.7)
- Minecraft 1.21.11: the client could close with "Unreported exception thrown!" (a ConcurrentModificationException) while chests were read on a busy server. The cause is in the game itself, not in the Stash Sorter: a player head with only a name (common on 2b2t) is resolved on a worker thread, which looks the name up in the tab list while the render thread changes that list; the failed lookup is rethrown on the render thread when the head is drawn. The addon now has a small mixin (`qwerte.mixins.json`, 1.21.11 only) that makes that lookup walk a copy of the tab list when it is not on the render thread. 1.21.4 does not have this code and is unchanged.

### Fixed (0.4.0-dev.10.6)
- Reset Pearl, Fast and Max: "a trapdoor will not open" aborted the climb. With no wait between trapdoor clicks, the lowest trapdoor still shown closed was clicked again every tick, and the server's answer takes a round trip or more, so the second click toggled the trapdoor back. A clicked trapdoor is now pending: it is not clicked again until the server's block update at its position has shown what the click did, or, if none comes, until a ping-aware timeout (twice the round trip plus one tick, at least 3) has passed and the client's state is looked at. Only a click judged not to have given the wanted state counts as a failure (3 stop the run), and the cover is closed the same way. Different trapdoors can still be clicked in the same tick (Max). The climb does not leave the shaft before the clicks are confirmed. Safe is unchanged.
- Reset Pearl, Fast and Max: the wait on the edge is shorter. A player slowed by sneaking takes about 8 ticks to come to a position change under 0.0005 blocks; they now count as standing still under 0.004 blocks a tick (Safe keeps 0.0005), and the first part of the way back from the rim block, while the player's box is wholly on the rim, is walked instead of sneaked (the edge is always sneaked).
- New opt-in setting "experimental-sprint-swim" (off by default): look straight up and sprint-swim up the shaft, roughly 1.6 times faster than holding jump but less precise.

### Fixed (0.4.0-dev.10.5)
- Reset Pearl is back to the careful approach of 0.4.0-dev.10.2 in every speed mode: climb out onto the rim, sneak back to the edge, stop, throw an aimed pearl from the real position. The faster modes only remove waits and overlap safe steps (one trapdoor click a tick, two in Max; the elytra swap does not hold the climb; no fixed wait before the cover; the controls, the hotbar and the elytra come back at once while the pearl is checked; a shorter stop on the edge). Fast and Max no longer throw from inside the shaft or sprint-swim up: that throw could land on a wall and teleport you, and the sprint-swim drifted. The throw from inside the shaft is still there as the opt-in setting "experimental-climb-throw", off by default.
- Reset Pearl, edge: the walk back from the rim block now faces the hang spot on the rim axis every tick, presses only forward, and lets go at the spot (about 0.27 blocks from the shaft's middle, measured along the rim), so there is no sideways drift and no reliance on the sneak edge stop alone. It never walks onto a covered shaft: if the block under the opening or at the feet is a closed trapdoor or anything solid, the run stops with "a closed trapdoor covers the shaft, not walking onto it". The rim you stood on when you saved the chamber (or the first one found) is saved with it and tried first; old saved chambers still load.
- Reset Pearl: the keys of the climb (forward, jump) are let go when the pearl has been thrown early, instead of staying held while waiting for it. If the thrown pearl teleports you (it landed on a block instead of staying in the water), the run stops at once and says so.
- Reset Pearl, pearl into the hand: a pearl in the main inventory is now swapped into the selected slot by one SWAP click (button = the hotbar index) instead of three pick-up clicks; a pearl in the hotbar is selected and given 2 ticks before the next try. The pearl is prepared at the start of the climb in every mode, so there is time to retry. After each attempt the client log gets INFO lines (also with debug off): where the pearl was, the selected slot before and after, the main hand, the menu state id, and for the next 4 ticks whether the selection or the slot changed and whether the server answered. If the held slot changes without us the warning says "another mod changed your held slot (slot N -> M)", and the abort names it.
- Reset Pearl: the timing summary is always written to latest.log as one line ("reset-pearl: timing (speed ...)"), with or without debug. The "debug" setting is saved with the module like every other setting; nothing resets it.

### Fixed (0.4.0-dev.10.4)
- Stash Window: the window could not be moved or resized, and the grid's scroll bar could not be dragged. Cause: every drag asked the game's `MouseHandler.isLeftPressed()` whether the button was still down, and the game only updates that flag while no screen is open, so inside the window it was always false and each drag ended on its second frame. Drags now read the button from Meteor's input state (the same in both Minecraft versions), and a test keeps `isLeftPressed` out of the window's code.
- Stash Window: the grid's scroll bar is wider and easier to grab (4 GUI pixels drawn, a wider grab zone), and it lights up under the pointer.
- Stash Window: "Indexed 1 h ago ago" said "ago" twice. Fixed.

### Changed (0.4.0-dev.10.4)
- Stash Window: a change in the header (a bot going online or offline, a permission change) no longer rebuilds the page while you are typing in one of its text boxes, so nothing you typed is lost; it waits until the box loses focus.
- Stash Window: a visible resize grip (a triangle of dots) in the bottom-right corner; the whole 10 x 10 GUI pixel corner square resizes. The mouse cursor shows a resize arrow over the edges and corners (GLFW standard cursors on the game window; the diagonal ones need GLFW 3.4, older libraries show a crosshair there). It goes back to the arrow when the window closes.
- Stash Window text is smaller, about the website's size next to the slots: new module setting `window-text-scale` (Auto, Small, Normal, Large; default Auto = 0.75 of the game's font, Normal = the game's font as before). The summary, hint and index line use the website's smaller text. Buttons and page buttons use the website's padding (4 / 10 CSS pixels).
- Stash Window header like the website: row 1 title, Stash select, "Bot online, last seen 5 s ago" with a status dot, Bot select, Link addon; row 2 Streamer mode, Theme, account, Log out; then the index line ("Indexed 1 h ago, Containers: 47 / 48 known, 1 stale, minecraft:overworld, MC 1.21.4") with a "Rescan stash" pill at its right end; then the page buttons. The bot status refreshes every second and the bot list is polled while the window is up. The dimension is hidden when the owner forced streamer mode, the bot name when names are hidden.
- Stash Window page buttons like the website's: Stash, Orders, Build Planner, Bots, Activity, Live, Settings, Proxy, Console, the ones the account's permissions allow (Users stays, for the owner). The pages without an in-game version say "<page> is only on the website. Open <address> in a browser." (the address is left out while streamer mode is on). Link addon explains the same.
- Stash Window stash page: the grid takes the whole width of the panel like the website. The details of a clicked item open in a drawer over the right side of the grid, with a close button; closed, it takes no room, and the grid is the same size either way. The summary reads "Showing 260 of 260 slots · total 414,749 items" in small text, and the page's spacing follows the website's CSS values.

### Fixed (0.4.0-dev.10.3.1)
- Reset Pearl, Fast and Max: "could not get a pearl into your hand" aborted the run right after the climb began. The elytra swap and the move of the pearl into the hand were inventory clicks fired in adjacent ticks (the pearl move was repeated every tick while it was not in the hand, and each repeat used up one of 6 tries). A click sent before the server answered the one before carries a stale state id, the server rejects it and sends the whole inventory again, which undoes the client's own guess; with a click every tick each one was rejected in turn. Every inventory operation (elytra off, pearl into the hand, the pearl back, the elytra back) now goes through one queue: one in flight, the next only after its result shows in the slots and the server has had the time a rejection takes to come back (the ping plus a tick, or an earlier answer that keeps the result), with a fresh plan and a bounded number of retries if it does not. The elytra comes off first and the pearl move waits behind it; a pearl in the hotbar is only a held-item change and is selected at once. A try is only counted when an operation was really issued and its result never showed, not for the ticks spent waiting behind another one.
- Reset Pearl: when a run is aborted after the trapdoors were opened and the player is already out of the shaft, the covers it opened are closed again (never the stasis trapdoor); inside the shaft they are left, and the warning says "The cover was left open."
- Reset Pearl, debug: every inventory operation is logged with its kind, the slots and the items in them before and after the click, the menu state id, and its result.

### Changed (0.4.0-dev.10.3)
- Reset Pearl is faster. New setting "speed": Safe, Fast (default) and Max. Safe keeps the waits of 0.4.0-dev.10.2. Fast: the climb is no longer held up for 3 ticks by the elytra swap; it swims up sprinting and looking straight up (faster than holding jump alone); it opens one trapdoor per tick instead of one every 3 ticks; it picks the pearl up early and throws it straight down the shaft from inside it as soon as the eyes are above the water (the throw is computed from the real position and speed one tick ahead, the pearl inherits the player's speed, and it only goes out when the whole path fits the shaft and is clear), so there is no sneak-walk back onto the edge and no second throw; the cover is closed without the 3-tick wait (it waits only while the player is still shown as sneaking); the controls, the hotbar slot and the elytra come back as soon as the cover is closed, during the pearl check. Max does two trapdoor clicks a tick, the shortest stops on the rim and a 10-tick pearl check. In every mode the stasis trapdoor is never closed, every click names its exact block, "Re-armed" is only said after the pearl check, and the elytra always goes back. If no throw on the way up fits, it falls back to the old hang-and-throw on the rim.
- Reset Pearl, debug: a timing summary at the end of every run (ticks and ms for the pending start, the elytra, each phase, the throw, the cover closed, the pearl check and the elytra back), also as one line in latest.log.
- The shaft depth sets the floor: swimming up takes roughly 6 ticks a block with jump alone and roughly 4 with the sprint-swim, so under 20 ticks to a closed cover is only possible when the rim is about 3 blocks or less above where you land.

### Fixed (0.4.0-dev.10.2)
- Reset Pearl pulled its own new pearl: the cover step closed every trapdoor it had opened on the way out, including the stasis trapdoor at the water, which has to stay open for a pearl to stay in stasis. The trapdoors are now told apart: the stasis trapdoor is the lowest one in the water block, at the water surface or directly above it (up to the water top + 1), and everything above is a cover. The climb may open the stasis trapdoor and leaves it open; the cover step closes only covers that Reset Pearl itself opened, and never clicks a stasis trapdoor, whatever the setting. A shaft with a single high trapdoor is a cover with no stasis trapdoor (a plain water column), and a single trapdoor at the water has no cover. If two trapdoors are at the water the roles are ambiguous: "Set location here" refuses to save it and a run stops with a warning. The roles are worked out from the saved water level and trapdoor heights and from the live scan, so old saved chambers keep working.
- Reset Pearl: every trapdoor click now names the exact block (no aiming), checks that it is the trapdoor of the role meant, in the shaft column, and not already as wanted, and looks at its state again two ticks later; a wrong result is retried within the existing limits.
- Reset Pearl: "Re-armed <name>" is only said after the new pearl has been seen resting in the water for 15 ticks after the cover, with the player not moved. If the pearl vanished or the player was pulled, it says "The pearl was triggered during cover: <name> is not armed." instead. With debug on, each trapdoor's role (position, height against the water, open state) and every click (face, state before and after, result) is logged.
- Note: a waterlogged trapdoor in the middle of the water column, below the surface, is not found by the shaft scan and is therefore never touched; one at the surface or directly above it is found and counts as the stasis trapdoor.

### Fixed (0.4.0-dev.10.1)
- Stash Window: opening the window crashed the game (NullPointerException in WStashPage.calculateSize). The stash page was built without its item grid being handed to it, so the first layout failed. Fixed, and the stash page now waits until all its parts are built before laying them out.
- Stash Window: an error in the window's layout or drawing can no longer crash the game. The window logs it ("stash-window:" in latest.log), closes any half-drawn clipping it left behind, and shows an error message instead; press Esc and open it again.

### Changed (0.4.0-dev.10)
- Stash Window looks like the website, in game. It is its own window now: drag the header to move it, drag any edge or corner to resize it (minimum 240 x 170 GUI pixels, or the whole screen if that is smaller), double-click the header to maximize or restore it. Its place and size are remembered (in the module's data, like the session) and brought back onto the screen if the game window or the GUI scale got smaller. Everything in it is sized by Minecraft's GUI scale and drawn with the game's font, so it is crisp at GUI scale 2, 3 and Auto; the new `window-scale` setting (Auto, 1x to 4x) overrides that. Meteor's own GUI scale and font no longer apply to this window.
- Stash Window, stash page: the header bar (title, Stash select, Streamer mode button, Theme select, user, Log out, an info row with when the index was read and how many containers it has, Rescan, a status badge) wraps onto more rows in a narrow window; page buttons are pills (Stash, Orders, Live, Settings, Users). The Items/Shulkers select is gone: the creative tabs of the website (Search, the tabs that have items, Shulkers when filled ones exist, Orders at the right end) are across the top, with the selected one joined to the panel. The search box and a sort select (Creative order, Count, Name A-Z, ID; Search and Shulkers sort by count, a creative tab by creative order, remembered separately) sit under the grid, with the website's summary line and a hint. The grid uses vanilla slots (18 GUI pixels) centred in a tile, reflows to the window's width and height as you resize, scrolls by whole rows (wheel, or drag the bar) and keeps the first visible item in view when the number of columns changes. The details of the selected item or shulker are a panel at the right, or under the grid when the window is narrow.
- Stash Window counts: small, vanilla-style numbers in the bottom-right corner of the slot, white with a shadow, a whole multiple of the font's pixel size (1x at GUI scale 2, 2x at 3 and 4, 4x at 6), and never wider than the slot ("20k", "1.7k", "999M" fit; only a count wider than 24 font pixels, such as "12.3B" at GUI scale 1, is shrunk to fit). Durability bars and the enchantment glint are unchanged.
- Stash Window selects (stash, bot, sort, theme, and every select in Orders, Settings and Users) open a solid list on a top layer: no item icons or counts show through it, a click on a row picks it, a click anywhere else only closes it (nothing under it is clicked or selected), Esc closes it first and the window with the next press, and a long list (more than 12 rows) scrolls. The item hover card (name, count, id) is on the same layer.
- Stash Window themes: all the website's themes - Classic (the default, as on the website), Modern, Light, Dark, Midnight, High contrast - and Mac (smoky glass and the three window dots: red closes, green maximizes, yellow does nothing). Pick one with the Theme select in the header or the `window-theme` setting. They colour the header, the nav pills, the creative tabs, panels, buttons, text boxes, checkboxes and selects of every page, including Orders, Map, Settings and Users and the login and password panels. Classic also draws the vanilla bevel. The slot art is the same in every theme, as on the website. The website's fonts are not available in game; the game's font is used. `stash-manager-server/dev/themes-palette.json` gained the tokens the window needs (nav, navText, navActive, navActiveBg, trackOff, chipBg) and a test checks that the window's colours equal it.
- Not in this build: the header's Bot select and bot status (the Live and Settings pages still have their own bot select), shulker face icons on filled shulkers, the website's exact spacing on the Orders, Live, Settings and Users pages, a font setting, and resize mouse cursors.

### Changed (0.4.0-dev.9.2)
- Reset Pearl: "Set location here" works standing on the closed cover trapdoor above the shaft, or on the rim right next to it (the four neighbouring columns are checked; if more than one is a shaft it asks you to stand on the cover), as well as in the water. It scans down through the trapdoors and air to the water, and saves the shaft column at the water surface together with the water level and trapdoor heights. The status line reads "Saved <name>: shaft found, water at depth N, K trapdoor(s)", or says why nothing was saved (no water and trapdoor within 24 blocks). Old saved chambers load and work as before.
- Reset Pearl: if the column you land in has no water, the shaft saved with the chamber is scanned instead (when it is within the detection radius). With "debug" on, the save scan logs the columns tried and why each was accepted or rejected.
- Reset Pearl: a pull without any ender pearl no longer gives up. It waits (new setting "pearl-wait", default 20 s, 0 gives up at once) until a pearl is in your inventory (slots 0-35) or offhand, then starts: the scan, the timeout and every phase limit begin only then. While waiting no key is pressed and Baritone is not touched; a nearby player or hostile mob, damage, a screen, a dimension or world change, death or turning the module off still end it with a chat warning. Chat: "Waiting for a pearl (up to Ns)...", "Pearl received, re-arming...", "No pearl received in Ns, not re-arming <name>.". The pull cooldown now starts when the pull is accepted.
- Reset Pearl does not walk to a pearl item lying nearby: it sits at the bottom of the shaft, where walking could leave the chamber or drop into it, and vanilla pickup already reaches items thrown onto or next to you. With "debug" on, a pearl item within 8 blocks but out of pickup range is logged ("pearl item at N blocks, not walking to it").
- Reset Pearl: wearing an elytra no longer stops the climb. On 2b2t, holding jump in the air or at the water surface makes the client send START_FALL_FLYING, and the server then keeps the player gliding in the water instead of swimming, so the swim up never worked. New setting "elytra-handling" (Auto by default): Auto takes the elytra off the chest slot before the climb (swapping in a chestplate from the inventory if there is one, otherwise moving it to a free slot; one click per tick, only with no screen open and an empty cursor), puts it back afterwards however the sequence ends (items are checked before swapping back, retried while a screen is open, with a chat warning naming the slot, and nothing is ever dropped), and also guards: the START_FALL_FLYING packet is cancelled while the sequence runs and the jump key is held only on the ground or in the water, never while gliding. Swap Off only takes it off, Guard Only only guards, Off does nothing. With no free slot and no chestplate Auto says so and tries with the guard only.
- Reset Pearl: "could not climb out of the chamber" now says why: gliding in the water, stuck with no upward progress, a closed trapdoor that would not open, a downward bubble column, or out of time. With debug on, the climb logs the pose, fall-flying, in-water and swimming flags, y and vertical speed four times a second, plus each swap step and each cancelled packet.

### Fixed (0.4.0-dev.9.1)
- Reset Pearl: a pull was only recognised if you landed within the detection radius of the saved block in 3D, so a chamber saved from the rim or anywhere above the water was silently ignored. A pull now matches by column: within the detection radius sideways of the saved block, and from 16 blocks below to 4 above it. A saved server that differs only by a leading "connect." or "www." counts as the same server.
- Reset Pearl: your own throw is now only recognised from a pearl this client saw appear next to you (new, fast, owned by you) or from a pearl use packet, never from an old pearl that falls or loads in later.
- Reset Pearl: nothing after a detected pull is silent any more. A pull ignored for the cooldown or because of a recent own throw, a death while waiting, and every abort and give-up print a chat warning, and "Pulled into <name>: re-arming..." is printed when a pull is accepted.
- Reset Pearl: starting underwater in a downward bubble column (magma) that swimming cannot beat now stops with a clear message after 2 seconds; Baritone is stopped if it comes on during the sequence.

### Added (0.4.0-dev.9.1)
- Reset Pearl "debug" setting (Safety group, off by default): logs every decision (detection inputs, scan result, each phase, climb progress, aim, throw, cover, aborts) to the client log with a "reset-pearl:" prefix and to chat; per-tick reasons at most once a second.

### Fixed (0.4.0-dev.9)
- Stash Window: nothing could be typed into the password boxes (login, change password, confirm password, your own password on the Account page, and a user's password in Users). They were made with no character filter, which Meteor's text box calls for every key. A test now fails the build if any text box is created like that again.

### Changed
- Stash Manager (the bot side): `server-url` is now empty by default, so a fresh install sends nothing; the module's status shows "server URL not set". A config saved by an older version that set an `api-key` but kept the old default address (`http://127.0.0.1:8765`, which Meteor does not save) keeps that address.
- The bot logs "Delivery report sent: N chests" at INFO when the server takes the delivery report (no positions or names; about once a minute, and always the first success after a failure), the same line as the Stash Sorter plugin 1.11.1.

### Added
- Reset Pearl module: after you are pulled through a saved stasis chamber (a teleport of more than 8 blocks that ends within the detection radius of a chamber, with no pearl thrown by you in the last 5 seconds) it climbs out opening the trapdoors in the shaft, sneaks onto the edge of the rim, throws a fresh pearl straight down into the water and closes the trapdoors it opened. Chambers are saved from the module's settings ("Set location here"); it gives up on a timeout, a nearby hostile mob or player, damage, a screen, or having no pearl, and always lets go of the keys and the hotbar. The throw leans toward the middle of the shaft; a fall of more than about 10 ticks (roughly 15 blocks) from the rim to the water is refused, because the pearl's random spread then no longer fits a 1x1 shaft.
- Stash Window module (`stash-window`, and the commands `.stash-window` / `.stash`): the Stash Manager website's stash view inside the game, as a window you open with the `open-window` keybind (while the module is on), the command, or the module's "Open window" button. It does not pause the game. To use a Stash Manager server (optional), turn on `connect-to-server`, set `server-url` (or copy the stash-manager module's with "Use stash-manager's URL") and log in with a website user name and password; a blank user name is the owner. You get the same permissions as on the website: the window only shows what your account may use. In this build it has the Browse page: the stash selector (the stash of the server you are playing on is picked first), a search (words must all match the name, English name, id or details; `@mod` limits to a mod), Items or Shulkers, sort by Creative, Count, Name or Id, the item grid with counts and durability bars, an item's details, a filled shulker's 27 slots, Inspect (the game's own tooltip, Esc comes back), and Rescan with the `rescan` permission (click twice). Orders, the live map, settings and users follow in later builds. Without a server the window works on its own (local mode, below); the Orders, Map, Settings and Users tabs show a hint until a server is connected.
- Stash Window login: the password is sent once and never kept (the box is emptied when you submit). The session cookie is saved in the module's data (`modules.nbt`, not a setting) so you stay logged in after a restart, is only sent to the address it was issued for (changing `server-url` to another address forgets it), and is never shown, logged or put in chat. Redirects are not followed and no proxy is used. A 401 from the server shows the login again; a 429 shows how long to wait; an account that must change its password gets only the change-password form; actions the server wants confirmed ("confirm password") ask for the password and try again once. Log out ends the session on the server. Logging out on the website's "Log out everywhere" ends the window's session too (the window notices within 30 seconds). Plain http to an address outside your own network (not loopback, not a private/LAN address) is refused unless `allow-insecure-http` is on; when it is on, the window shows a warning on every page, and a saved session for such an address is not restored unless it is on. The session token is stored in Meteor's `modules.nbt` in plain text: do not share your `meteor-client` folder or Meteor profiles, and use the window's Log out (which ends the session on the server) to revoke it.
- Stash Window streamer display: hides server addresses and player names in the window. If the owner forced streamer mode on your account, it is locked on, the window never mixes in anything from your game (it does not pick a stash by your current server), and uses only the opaque ids and labels the server sends.
- Stash Window local mode (the default; `connect-to-server` is off): no login, no server, no network calls. The window remembers the chests, trapped chests, barrels and placed shulker boxes you open (a double chest is one container, keyed by its lowest half; ender chests are left out; menus that are not the clicked container, such as plugin GUIs, are ignored) including what shulker boxes and bundles inside them hold, and browses them like the server view: search, sort, shulker contents, inspect with the real item, and Where (coordinates, kind and distance of each container that holds the selected item, with a Forget button each). One page per dimension plus all dimensions. It is saved in `meteor-client/qwerte/local-index/<server>.nbt` (compressed NBT, written off the game thread and moved into place, so a crash cannot leave half a file; a damaged file is set aside as `.bad-<time>` and the index starts over). Caps: `local-max-containers` (256 to 4096, default 4096; the container read longest ago goes first), 54 slots per container, 48 MiB per file, 64 server files. A container whose block is broken is forgotten when you are near it. "Clear local index" (click twice) forgets everything for the server and deletes its file. Streamer display hides coordinates and the server address. Settings: `local-capture`, `local-max-containers`. It needs a world to be open (the stored items are read with the world's registries).
- Stash Window: `connect-to-server` (off by default) shows `server-url`, login and `allow-insecure-http`. While it is off nothing is sent even if an address or a saved session exists; a saved session is kept (for its address) and used again when you turn it on. The stash-manager module's `server-url` now also defaults to empty.
- Stash Window security fixes: server text has bidirectional controls, format, private-use and unassigned characters and unpaired surrogates removed; responses are capped at 32 MiB decoded and a larger announced length is refused before reading; a huge `Retry-After` is clamped to an hour; the stash of the server you are playing on is not preselected while streamer display is on; changing the address drops answers still on their way, and the icon cache is cleared when the session ends.
- Stash index: the bot's index JSON is now built by a shared helper (`IndexJson`) that the Stash Window's local index also uses; the output is byte-identical (a test compares it with the old code on fixture data).
- Stash Window Orders tab, for both ways of running orders. A cart (pick items in the tab, or right-click an item in Browse, or use its "Add a stack to cart" / "Add a shulker's worth" buttons), with a count box per line (clamped to what is available), the website's shulker names (none, one for all, or one for each planned shulker, up to 50 characters, no section sign) and its 54-shulker cap with the same message. The cart is planned and packed exactly like the website and the bot (`Order.expand` / `Order.pack`: packed shulkers first, then empty ones, then loaded bins), and shown as a 54-slot delivery chest: what the delivery chest already holds is dimmed and hatched (tooltip with name, count and a shulker's contents), the planned shulkers take the free slots after it, the ones past its free room are outlined, and the selected shulker's 27 slots are shown below. Under it the website's fit line ("Delivery chest: N of 54 slots used, M free; this order needs K (as of X ago)", totals over several chests, "not seen yet") and the warning when the order will not fit (the bot puts up more chests / the order pauses until a chest is emptied). The order list shows status, phase, progress, message and lines, with Cancel, Retry and Force cancel (after 60 s or while the bot is offline) and Clear finished, under the same rules and permissions as the website; second click confirms.
- Stash Window Map tab (server only; needs the `viewer` permission): the website's live viewer inside the game. One event-stream connection (`GET /api/v1/bots/<id>/viewer/stream`) on its own daemon thread while the tab is shown, reconnecting at once after the server's 45 s rotation, after 2 s at capacity, with a growing wait after errors (a 429 waits for `Retry-After`; 401, 403 and 404 stop for good), dropping a stream that is silent for 15 s, and reopening for a fresh snapshot if the window cannot keep up. One viewer id per bot is kept for the login (the server allows about 20 new ones an hour); a stream started here counts against your 2 streams per user, like the website's. The map is the game's own map colours: 16x16 chunk tiles drawn into one 512x512 texture (32x32 chunks around the bot, at most 5 uploads a second, one textured quad inside a scissor), with the bot as a heading arrow, players as a dot and name, mobs as dots (red when hostile), and dropped items as small squares. Drag to pan, the wheel zooms around the pointer, `+`/`-` and "Follow bot" (or a right click) work too. Beside the map: health and food bars, XP and armour, coordinates and dimension, the hotbar (held slot marked) and armour/offhand, and the bot's chat as plain text. Streamer display hides player names, coordinates and chat; for an account forced into streamer mode the server rebases every coordinate and sends no names or chat, and nothing from your own game is ever drawn on the map. Without a server the tab shows the "connect a server" hint.
- Stash Window Settings tab (server only): Bot settings is the website's Settings and Proxy pages: pick a bot and a scope (the stash plugin; with the `proxy` permission also AquariusProxy and other plugins), sections with a search, an editor for every kind of setting (on/off, choice, number, text, list; "unset" for nullable ones; secrets and read-only settings are shown, not edited), Save sends the changes in one batch with the value each was edited from and shows the bot's answer per setting ("applied", or "changed elsewhere meanwhile; reloaded"), Revert drops the edits. Read-only without the `settings` permission (plugin) or `proxy: change` (proxy); reading proxy settings asks for the password first. Hidden for accounts forced into streamer mode (the server refuses them too); in streamer display address/port/URL settings are hidden. Not included yet: the console and the Activity page. Account: change your password, "Lock admin now", Log out, and Log out everywhere (click twice).
- Stash Window Users tab (owner only): list the sub-users with their preset, last login and sessions; add a user (a preset or custom permissions, temporary password, must-change), edit the name and every permission (View, Order, Manage all orders, Bots and activity, Change plugin settings, Rescan, Link, Build planner, Order from the planner, Live viewer, Force streamer mode, proxy none/read/change, console none/list/full with the allowed commands, and limits to chosen stashes and bots; the rules the server applies are shown as "on, implied"), disable or enable, reset the password, log out everywhere, delete (second click confirms), rename the owner, and read the audit log (newest 200). Every write asks for your password again when the server wants it, then goes through. Force streamer mode is always written out explicitly.
- Stash Window: new setting `order-abort-health` (0 to 20, default 0 = off) in the Local orders group: a local order stops when your health drops to this or below.
- Stash Window live map hardening: a stream that ended for good (not logged in, no permission, unknown bot, revoked, an error) is no longer reopened every frame; it waits (a second after "revoked", otherwise 5 s doubling to 60 s, or at once when the bot list or the account changed) or you press Reconnect, and only a different bot clears the picture. A connection lives at most 90 s, one that completes no line for 15 s is dropped, a line or event over 5 MiB is refused unparsed, there is at least a second between connects (and a back-off after three instant reconnects without a snapshot), and turning connect-to-server off closes an open stream. Bot and user ids are checked (`A-Za-z0-9_-`, 1 to 64) and percent-encoded in every request path. The client itself refuses plain http to a foreign address unless allow-insecure-http is on. Settings and Users: a failed load no longer retries every frame, a cancelled password prompt frees the form, a save is refused while the previous one is still being applied, a setting of an unexpected shape is shown read-only instead of breaking the window, the form is not rebuilt while a box has the cursor (also in the Orders cart), chat wrapping is cheap. A user with a preset's permissions plus forced streamer mode is labelled "Viewer + forced streamer". Local orders stop at health at or below `order-abort-health`, and the message says "you died, respawned or changed dimension". The orders' force-cancel wait uses the server's clock (its Date header). Known limit: the map draws the 32x32 chunks around the bot only (the website draws every cached tile).
- Stash Window orders with a server: "Place order" sends `POST /api/v1/orders` with the cart (a refused line is highlighted), the list comes from `GET /api/v1/orders` (every 3 s on the tab, every 15 s otherwise) and the delivery contents from `GET /api/v1/delivery` when the stash's summary changes. With a server connected you choose who carries out new orders: "Send to server bots" (the default) or "Fulfil here (this client)". Not offered while streamer mode is forced on the account.
- Stash Window orders without a server (the default): this client is the bot. "Start order here" hands the order to stash-sorter's own order engine (the one stash-manager feeds from the server, no network): the sorter loop is driven the same way, the order is offered when the sorter can take it, its progress shown, and the sorter's cleanup lets go of everything when it ends. The cart is built from the index of the sorter's stash (`stash-name`), and the delivery preview from the contents the sorter last saw in the delivery chests. The tab shows the delivery container, anvil and crafting table with Set / Change / Clear (Set closes the window, then right-click the block; the same picks as stash-manager's `select-delivery`, `select-anvil` and `select-crafting-table`), and uses stash-manager's settings for crafting, `craft-never-use`, `use-named-shulkers`, `auto-expand-delivery` and `fresh-minutes`. Refused with a clear message when stash-manager is on (it drives the same sorter and may be taking server orders), when an order is already running, with no stash or no delivery container. Cancel (or turning stash-window off) parks the order the way turning stash-manager off does and stops driving the sorter.
- Stash Window local orders, AFK safety: the run stops, and says so in chat, when another player comes within `order-player-radius` blocks (default 6) or a hostile mob within `order-hostile-radius` (default 4) (0 turns either off), when it has taken longer than `order-timeout-minutes` (default 30), when stash-manager is turned on, when you die or respawn, or when you leave the game. A stop in the middle of an order says in chat that items taken for it may still be in your inventory; Cancel after the order ended lets the leftovers be put back first (Cancel again stops at once). An order is refused while stash-manager has a server order parked in the stash (turn stash-manager on again to finish it, or cancel it on the website); only saved plans of this client's own orders (ids starting `L-`) are ever dropped. The sorter's blockers (another dimension, too far from the stash) are shown before you start. `order-messages` (on) tells you in chat when an order starts, ends or is stopped. Status, progress and history of these orders are saved per server in `meteor-client/qwerte/local-orders/<server>.json` (at most 100 orders and 1 MiB, the same limit written and read; written beside itself and moved into place; a file that can't be read is set aside as `.bad-<time>`; a failed write is logged once and tried again after a growing wait; an order still open when the game stopped is shown as failed "interrupted").
- Stash Window: `StashRef` carries the bot details the Orders tab needs (naming, crafting, delivery storage, order blocker, delivery summary).
- Stash Window: nothing is requested while the window is closed. Open, it asks for the login state every 30 seconds and the stash list every 10 seconds, and downloads an index again only when the stash's index time changes.
- stash-manager: the bot reports what is in the delivery storage (`POST /api/v1/delivery`, signed like the index): every delivery chest in fill order (the chests not known to be full first, lowest first, then the full ones), with the slots, items, names (cut at 64 characters), counts and a summary of what each shulker box holds (grouped by item, at most 27). No positions. It is built from what the bot last saw in each chest (every time its window is read: delivering, looking at a full chest again, verifying, and when a player opened it and the bot read it) and is saved with the stash; a chest never read is reported as not seen. It goes out at most every 5 seconds, only when something changed or the server asks. The heartbeat gains `deliveryHash`; a reply with `needDelivery: true` makes the bot send it again. The report is sent only once a heartbeat reply has carried `needDelivery` (an older server never gets it), a failure of it has its own retry timer (60 seconds, doubling to 10 minutes) and never slows the index or heartbeat, and a report over about 230 KB is cut down (shulker contents first, then the items of the last chests). The delivery chests stay out of the index and out of searches.
- crafting: no crafting table needs to be set. When crafting needs one, the bot uses the table selected by hand, then a remembered one; else it looks in the stash area (the perimeter, or the containers widened by the container radius; at most 128 blocks across, loaded chunks only, at most once every 10 seconds) for a placed crafting table it can stand next to and remembers the nearest as found; else it takes a crafting table from the stash (never one another line needs) and places it, remembered as placed; else it crafts one in the 2x2 inventory grid from 4 planks of one wood (or a log made into planks first) taken from the stash, and places it, remembered as crafted. The table is placed with the anvil's spot rules (not next to the delivery container, the extra delivery chests or the anvil). If all of that fails the craft fails with "no crafting table available", and the order pauses or goes short exactly as it did without a table. A remembered table that is gone, or that can't be reached or opened (unless set by hand), is forgotten and the search runs again.
- stash-manager: the heartbeat's `crafting` gains `tableSource` ("set", "found", "placed", "crafted" or "none"); `table` stays true when a table is set or remembered. The chat says when a table is found, placed or crafted.
- stash-manager: `auto-expand-delivery` (Delivery group, off by default). When every chest of the delivery storage is full, the bot takes a chest from the stash (or crafts one, with `enable-crafting` on) and puts another chest up one block above the highest one: a double chest above a double chest, a single above a single or a barrel. It goes up while the new chests could be opened and the bot can reach them (build height, something in the way, a solid block above, nowhere to stand). From the floor that is the first row above the delivery container; higher rows need something to stand on beside the stack. The lowest chest with room is filled first. The chests are never sorted into, taken from, listed or searched, and are saved with the stash. A different delivery container forgets them.
- stash-manager: a chest a player emptied is used again. The bot sees the lid (the chest's lid animation, as stash-sorter's lid watch does) of the delivery chests; a full one opened and closed by another player is looked at once. Where the lid was out of sight, a full chest is looked at again every 2 minutes while an order waits.
- stash-manager: an order waits for delivery space instead of ending. With every chest full and none to put up (or auto-expand off) it is paused with "waiting for delivery space: <why>" and goes on once there is room. It ends after 2 hours with "delivery container full (x/y)". The finished shulker stays in the inventory.
- Stash Manager website: the heartbeat carries `deliveryStorage` ({`autoExpand`, `chests`, `full`, `canExpand`, `blocked`}, no positions).
- Stash index: each `items[]` entry of a damaged, damageable item has `damage` (at least 1) and `maxDamage`; other items have neither. The index hash changes for stashes that hold damaged gear.

- Changing or clearing the delivery container turns the chests put up above the old one into ordinary stash containers, and the sorter then sorts what is in them; the log says how many. Going back to 0.4.0-dev.4 does the same. The 2-hour wait for delivery space is kept in memory only, so restarting the game starts it again.

### Changed
- crafting: the per-order and per-hour caps count shulker boxes only; chests crafted for the delivery storage keep the grid fill limit alone.

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
