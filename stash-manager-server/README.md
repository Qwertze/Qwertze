# Stash Manager server

A small standalone web server for the Qwerte addon's `stash-manager` module. The module pushes the item index of a Minecraft stash (what is in every known container) to this server, and the server shows it as a website that looks like a Minecraft inventory, for anyone on your local network.

It also takes orders for the bots to fill, and can show and change the AquariusProxy plugin's settings, console and activity. Orders, the dashboard and streamer mode are new and still being tested. Kit sorting v2, the Kit Builder and kit orders are new too, and have not been tested in game yet. Repair passes are new as well and have not been tested in game yet.

The wire format and the access rules are in [PROTOCOL.md](PROTOCOL.md).

## Versioning

The server uses the same rule as the Qwerte addon: **x.0.0** for major breaking changes, **x.y.0** for new features, bug
fixes and more, and **x.y.z** for minor bug fixes, single one-off patches and hotfixes. The version is set in
`build.gradle.kts` (`version`). This is version **1.2.0** (the repair release, see [CHANGELOG.md](CHANGELOG.md)).

Compatibility: Qwerte addon **0.5.0** works with Stash Manager server **1.2.0**, and the Stash Sorter plugin **1.17.0** works with it too. Repair passes need the plugin 1.17.0; the addon has none yet.

## Build

You need JDK 21 or newer. From the repository root:

```bash
./gradlew -p stash-manager-server build
```

On Windows use `gradlew.bat -p stash-manager-server build`. This is a separate Gradle build. It doesn't touch the addon's Fabric build.

The output is `stash-manager-server/build/libs/stash-manager-server.jar`, a single runnable jar that includes Gson.

## Run

On Windows, the easiest way to start it is from the folder where its data should live (for example the repo root):

```bat
stash-manager-server\run-server.bat
```

The script copies the built jar to `stash-manager-data\server-running.jar` and runs that copy. Rebuilding the project then can't replace the jar under a running server. Java loads classes from the jar as it needs them, so replacing the jar of a running server breaks requests that need classes it hasn't loaded yet.

To run it directly instead, use `java -jar stash-manager-server.jar`. If you do, stop the server before rebuilding.

Options:

- `--data <dir>`: where config, indexes and the icon cache are stored. The default is `./stash-manager-data`, relative to the folder you start the server from.

On startup the server prints the URLs it can be reached at (localhost and your LAN IPs) and, on the very first run only, the API key (see "What the server prints at start"). Open one of the URLs in a browser. Stop the server with Ctrl+C. If the port is already in use, the server says so and exits; change `port=` in the config.

## Configuration

The first run creates `stash-manager-data/config.properties` with comments:

| Key | Default | Meaning |
| --- | --- | --- |
| `port` | `8765` | TCP port. |
| `bind` | `0.0.0.0` | Address to listen on (all interfaces by default). |
| `apiKey` | generated | The key the addon must send. Shown in the website's Link addon panel. |
| `allowRemote` | `false` | `true` turns off the LAN-only address check. Leave it off. |
| `allowedHosts` | empty | Extra host names (comma separated) the browser may use, for example a LAN DNS name. |
| `minecraftJar` | empty | Minecraft client jar (or a launcher/`.minecraft` folder) for rendering icons. Empty: found automatically. See Item icons. |
| `iconSource` | `auto` | `auto` (game files, then the wiki), `game` or `wiki`. |
| `iconDownload` | `true` | When no Minecraft client jar is found on the machine, download the official one once from Mojang (piston-meta.mojang.com, piston-data.mojang.com; sha1 verified), render every item icon into `icons/game-<version>/` in the background, then delete the jar. `false` = never download. |
| `recipeDownload` | empty | The same as `iconDownload`, for the crafting recipes the planner and the bots use. Empty = follow `iconDownload`; `true`/`false` to decide separately. The recipes are built in the same one-time pass as the icons, from the same jar. See Crafting from the stash. |
| `trustedProxies` | empty | IP addresses of a reverse proxy / tunnel in front of the server. Only requests from these have their client address read from the last `X-Forwarded-For` entry (rate limit and audit log), and they never count as the server PC. |

Restart the server after editing.

## Website password

The whole website is behind one password (the bots don't need it; they use the API key).

- **First run:** the server window shows a one-time **setup code** (`Dashboard setup code: XXXX-XXXX`). Open the website **on the server PC itself** (`http://localhost:8765`), type the code and choose a password (at least 12 characters; very common passwords are refused). Other computers see "Set the dashboard password on the server PC" until then.
- **Logging in:** each browser stays logged in for 30 days. **Log out** in the header logs out this browser; the Link addon panel has **Change password** (needs the current one and logs out every other browser) and **Log out everywhere**.
- **Forgot it / reset:** stop the server, delete the `dashboardPassword=` line from `stash-manager-data/config.properties`, and start it again. Every login ends (sub-users' too), and the first-run setup appears again on the server PC. Sub-users are kept; to remove them all as well, delete `stash-manager-data/users.json` too.
- **Users (Settings > Users, owner only):** add sub-users with their own password and pick what each may do: view stashes, place orders (and cancel or retry their own), change anyone's orders, see Bots, change plugin settings, proxy settings (read or change), the console (none, a list of allowed commands, or full), rescan, Link addon, the Build Planner, ordering from the Build Planner, and the live viewer. A user can be limited to some stashes or bots. Presets: viewer, orderer, operator (orderer and operator include both Build Planner permissions; operator also the live viewer). New users choose their own password at first login (unless you untick that). Disabling, resetting a password or deleting a user logs them out at once. Sub-users log in with their name; the owner logs in with the name left blank (or the owner's name, `owner` unless renamed). User management, the audit log (logins, account changes, console commands, settings changes, order changes, Build Planner uploads and orders) and the API key are the owner's only. Each user's Admin unlocked uses their own password. **Force streamer mode** (a switch per user, off by default) is enforced by the server: that user is never sent coordinates, player names, server addresses or bot ids (only opaque ids; servers show as "Server 1"), chat, the bots' free text, or names players gave items; the live viewer shows relative coordinates; settings, proxy settings and the console are refused; and the streamer toggle is locked on. Presets and older pages never turn it off; only the switch does (audited as ON/OFF). Stash names are still shown. Use it for anyone who logs in from outside your home network (see PROTOCOL.md, "Additions: website users", for the full list). Give `console: full` and `proxy: change` only to people you trust like yourself: the proxy's own commands and config can do anything.
- **Behind a reverse proxy or tunnel (optional):** add the proxy machine's IP to `trustedProxies=` in config.properties, so the login limits and the audit log see each visitor's real address instead of the proxy's. Empty by default: forwarding headers are then never trusted.
- **Too many wrong passwords:** after 5 failures from one computer it has to wait 1 minute (doubling up to 15 minutes). 20 failures from anywhere within 10 minutes pause logins from every other computer for 15 minutes. The server PC itself is never locked out.
- **Console and Proxy settings** (viewing and changing them) need the password again if it wasn't typed in the last 15 minutes in that browser; the page asks for it. If a bot's Console or Proxy tab says "Disabled on the proxy", allow it in the proxy's own terminal with `stash manager dashboard console on` or `stash manager dashboard proxy on`.

## Upgrading from an earlier version

- Update every Qwerte addon and Qwerte Stash plugin: bots must now sign their requests (see below).
- **Once, after upgrading: open the Link addon panel and press "Log out everywhere"**, then log in again. Logins made with earlier versions could, in rare cases, have been mixed up between browsers, and this ends them all.
- Admin actions (console, proxy settings, showing or regenerating the API key) now need the password typed in the last 15 minutes. While that window is open, the header shows "Admin unlocked for N min" with a **Lock now** button.

## Signed bot requests

Bots never send the API key over the network: each request is signed with it (HMAC), and the server signs its replies, so a sniffer can't reuse the key and a fake server can't feed the bot jobs. Bots from before this change send the key in clear text ("Bearer") and are refused with 401 until they are updated: **update every addon and plugin to the current version**. If you need an old bot running meanwhile, set `allowBearer=true` in `stash-manager-data/config.properties` and restart (the server warns about it at startup; it never applies to the dashboard), then set it back to `false` once every bot is updated. After **Regenerate key**, bots still using the old key keep working for 10 minutes (not on the dashboard), so you have time to paste the new key.

## Linking the addon

The easy way is the website:

1. Open the website (one of the URLs printed on startup) and click **Link addon** in the header. The panel also opens by itself while there is no stash index yet.
2. In Meteor, open the Qwerte addon's `stash-manager` module.
3. Copy the **server-url** and the **api-key** from the panel into the module's `server-url` and `api-key` settings. The key is hidden until you click Show. On plain `http://` pages some browsers block the clipboard; the Copy button then selects the text, so press Ctrl+C.
4. Enable the module with a stash loaded.

The panel switches from "Waiting for addon..." to "Linked: <player>" as soon as the addon makes a request with the current key. The addon sends a heartbeat every 15 seconds and pushes the index whenever it changes. The page shows the bot as online while heartbeats keep arriving (within the last 45 seconds).

Fallback without the website: the key is printed every time the server starts, and it is saved as `apiKey=` in `stash-manager-data/config.properties`. Use one of the printed URLs as `server-url`, for example `http://192.168.1.20:8765`.

### If the key leaks

Click **Regenerate key** in the Link addon panel. The old key stops working at once, so paste the new one into the addon. You can also delete the `apiKey=` line from `config.properties` and restart the server.

## Using the website

- **Tabs.** Items are grouped like the creative inventory: Search (everything), one tab per creative tab that has items in the stash, Shulkers, and on the right Orders, Kits and Kit Builder (see [Kits](#kits)). Inside a creative tab items are in creative-menu order by default; the sort menu can switch to count, name or id. The search box filters the current tab, and searches everything on the Search tab. With the search box not focused, the left and right arrow keys switch tabs. Proxy bots, and indexes from older addon versions, send no tab data. For them the server fills the tabs in from its own creative-tab table for the index's Minecraft version (1.21.4 and 1.21.11 ship with it), so their items show in the creative tabs too; an item the table doesn't know stays under Other.
- **Shulkers.** The Shulkers tab shows every filled shulker kind (identical shulkers are grouped, with a count). Right-click one to see its contents in a shulker-box window; close it with X, Escape or a click outside. Right-clicking a shulker box item in another tab lists the filled ones of that kind, or says they are all empty. **Shulker icons:** a plain shulker box icon always means empty shulkers. A filled shulker is drawn as the box with the item it holds most of on top (the most of by total count over all its stacks; items with the same id count together; a tie goes to the one in the lowest slot), and a small **+** in the top-left corner when it also holds other kinds of items. This is the same everywhere shulkers appear as icons: the creative tabs, Search, the Shulkers tab, the order picker, cart lines and the planned delivery chest. In the creative tabs and Search, a shulker box item's slot counts only its empty boxes, and its filled groups follow right after it (they are also found by what they hold: searching "diamond" lists shulkers full of diamonds). The tooltip and the shulker window still list the full contents.
- **Orders.** The Orders tab is the order maker. Pick an item type, then drag an item onto the cart or double-click it to add one stack (hold Shift for a shulker's worth, 27 stacks). Click an item for an exact amount. The cart shows the planned delivery as a double chest: each planned shulker is one slot, with a fill bar (orange when 27/27). Below it, a shulker-box view shows the selected shulker's slots; it follows the last shulker as you add things, and says so when one fills up. Click a shulker in the chest to look inside, and right-click a stack there to remove it. One order can fill at most 54 shulkers (one double chest); the page and the server refuse more. The top of the tab shows the delivery container the bot reported, or a warning when none is set, and why the bot can't take orders, if it says so. The cart is kept in the browser per stash. "Place order" sends it to the server, and the bot picks it up, packs the items into shulkers and delivers them to the delivery container you set in game in the stash-manager module. Orders can be placed while the bot is offline; they wait until it reconnects. Shift-click an item in the other tabs to add a stack to the cart, and use "Add to order" in a shulker window to order a packed shulker as it is. An item line for a shulker box means empty shulkers. **Shulker names:** under the cart, choose "No names" (the default), "Same name for all" (one name for every shulker), or "Name each shulker" (one box per planned shulker, in delivery order, showing what it will hold; leave a box empty to keep that shulker unnamed). Names are up to 50 characters, without `§`. The bot renames the shulkers in an anvil before delivering them; if it can't (no anvil set, not enough XP, ...), they arrive unnamed and the order still completes. The form warns when the bot can't name shulkers, has no anvil set, or may run out of XP (about 1 level per shulker). When the bot reports auto-crafting on, a note says it may craft missing empty shulker boxes from the stash's materials (or that it can't, because no crafting table is set). The bot plans again from what it can reach, so it may deliver fewer shulkers; names go in order. The order list shows each order's status and progress and its names, and open orders can be cancelled (a running order stops at a safe point). **Retry** on a failed or cancelled order places it again as a new order (same items and names), checked against what is in the stash now. **Clear history** removes the finished orders (delivered, failed, cancelled) from the list; open orders are kept. A kit can be ordered too: the Kit Builder's **Order** button adds a kit line to the cart (see [Kit orders](#kit-orders)).
- **Repair.** Under the cart, the Repair box has "Repair all items" (only damaged items with Mending are repaired, with Bottles o' Enchanting from the stash) and a "Repair" toggle on each repairable item line (the line toggles are greyed out while "Repair all items" is on). A damaged line that can't be repaired says why, for example "can't repair (no Mending)". The box shows the estimate ("About N bottles needed, M in the stash") and warns when the stash is short or when the bot reports that it can't repair. The order is still placed, and anything the bot can't finish is delivered as it is. A finished order shows the estimate it was placed with, the bottles used, and the items that were not fully repaired, with the damage left or the reason.

Anyone with the website password can place and cancel orders, the same as everything else on the page.

## Build Planner

The **Build Planner** tab turns a schematic into a material list for a stash.

- **Upload** a `.litematic`, a Sponge `.schem` or a vanilla structure `.nbt` (up to 8 MiB) by dropping it on the page or with "Choose a file". Old pre-1.13 `.schematic` files are refused with a hint to re-save them in Litematica or WorldEdit. The server reads the file, works out the survival items (a door is one item, a slab pair two, and so on) and **does not keep the file**: it is never written to disk or logged. Only the computed list stays, in memory, until 30 minutes after you last used it (2 hours at most), until you log out, or until the server restarts. Each user can have 3 lists at once.
- **The list** shows each item's icon, what the build needs, what the stash holds, what is available (minus what open orders have reserved) and what is missing, as `2 sh + 5 st + 12` (shulkers of 27 stacks, stacks, the rest). Sort by clicking a column, filter by name, or show only missing items. Untick a row to leave that item out. Items the stash has only with extra data (enchanted, renamed) are mentioned but never counted or ordered. Blocks the version's table doesn't know, and items inside chests in the schematic, have their own sections.
- **Options:** the stash to compare with (switching needs no new upload), the number of copies (1-64), rounding every line up to full stacks or full shulkers, water and lava buckets, and chest contents.
- **Export missing** downloads the missing items as CSV or text, or copies them.
- **Order build materials now** (needs the "Order from the build planner" permission) orders what the stash has. One order can fill at most 54 shulkers, so a big build is split into parts; the confirmation lists every part, what is left out because the stash has none, and the same bot warnings as the cart. It places part 1; once that order is delivered (empty the delivery chest first), "Order next part" places the next. A part whose order failed or was cancelled can be ordered again ("Order part i again"). While parts are ordered the options and the stash are locked, so nothing is ordered twice; to start over, Dismiss and upload the file again. Ticking "Name the shulkers" names them `Build: <file> 1/3` and so on.
- **Dismiss** deletes the list from the server.

Sub-users need the **Build planner** permission for the tab, and **Order from the build planner** to order from it (it doesn't need Place orders; it also lets them cancel and retry their own orders). The Orderer and Operator presets have both.

## Dashboard: proxy bots

Proxy bots running the Qwerte Stash plugin with the dashboard turned on (`stash manager dashboard on` in the proxy) show up on the website. Pick one in the header's **Bot** menu (remembered per browser; ● online, ○ offline).

- **Activity** (bots whose plugin reports it; the page opens here until you pick another tab): what the proxy is doing right now. The top card shows the connection (online, in queue with position and ETA, connecting, offline), server, dimension, position, health and food, ping, uptime, spectators and who is controlling the bot, plus when the last report came (a warning when it is over a minute old). **Now doing** has a card for each thing that is working (stash sorter, stash order, pathfinder, pearl loader, queue, ...), with a progress bar where there is one. **Modules** lists every module of the proxy and its plugins with an on/off dot (filter by name, or show enabled ones only); click one to open its Proxy settings. **Plugins** lists the installed plugins and versions. **Events** is a live feed (newest first) that can be filtered by source and level; the server keeps the last 500 per bot. The page refreshes every 2 seconds while it is open. The Bots page and the bot menu's tooltip show a one-line "now doing" for each bot.
- **Bots:** every proxy bot with its plugin and proxy versions and the stashes of its player, plus the stash table (owner, status, delivery container, open orders, Release).
- **Settings:** the plugin's settings, grouped by section. Change any number of them, then **Save** (or **Revert**). Each changed setting shows a spinner, then "applied ✓" with the value the bot read back, or the error next to it. If a setting was changed elsewhere meanwhile (in the terminal or Discord), it is reloaded instead of overwritten. Secrets only show whether they are set; read-only settings are greyed out with the reason. While the bot is offline the form is read-only.
- **Proxy** (bots with the plugin's proxy access): every AquariusProxy setting, plus the configs of other installed plugins (the menu next to Save). The left list has every section and module with its number of settings (a dot marks unsaved changes); the search box finds settings by path, name or help text across all of them. A module's on/off switch is shown first, highlighted. Saving works like Settings, but needs the password typed in the last 15 minutes. Secrets show only set / not set and are never sent to the website; settings that need a restart or are dangerous to change while running are read-only with the reason. The first time a config is opened, the page asks the bot for it (about a second).
- **Console:** run proxy commands as if typed in the proxy's terminal, with the reply rendered under each command. Up/Down recall earlier commands. Commands that can disconnect or restart things (`shutdown`, `update`, `disconnect`, `auth`, `jvmArgs`, `plugins`, `releaseChannel`, `discordManage`) ask first. Commands whose input is sensitive show as "(hidden)". The page keeps the last 200 commands per bot (until the server restarts).
- **Orders: what is already in the delivery chest** (bots that report it: plugin 1.11.0, addon 0.4.0-dev.7): the planned delivery chest shows the items left over from earlier orders in their slots (hatched; hover for the name, count and a shulker's contents), the planned shulkers in the free slots after them, and a line such as "Delivery chest: 20 of 54 slots used, 34 free; this order needs 3 (as of 1m ago)" (totals when there are several chests; "not seen yet" for a chest the bot hasn't read). When the order won't fit, a warning says whether the bot will put up more chests or the order will pause until a chest is emptied. It is only shown here: never counted as stock or searched. The server keeps it in memory only.
- **Kits** (bots that support them: plugin 1.14.0 and later for kit sorting; kit types, the Kit Builder and kit orders need plugin 1.16.0; the Qwerte addon does kit sorting only so far). The Kits tab picks the chests for each kind of kit and for each kit name, shows each kit's status, and edits the v1 scoring. The Kit Builder designs kits and their restock. The bots do the classifying and the sorting. See [Kits](#kits).
- **Repair** (bots that run repair passes: plugin 1.17.0 and later). The Repair panel on the Kits tab sets the passes for each stash and shows the bot's status and last pass. See [Repair passes](#repair-passes).
- **Live** (the live viewer; bots whose plugin has it): the bot's surroundings as a live map (vanilla map colours, north up, 16x16 chunk tiles out to 8 chunks), with the bot as an arrow along its heading, other players as dots with their names, mobs as small dots (red when hostile) and dropped items as small squares. Drag to pan, pinch or scroll to zoom, double-click or double-tap to zoom in; panning stops following the bot and **Centre** follows it again. The top shows health, food, XP level, armour points and the coordinates with the dimension; the bottom the hotbar (the held slot outlined), under **Inventory** the 27 main inventory slots (open by default on wide screens, collapsed on phones; the choice is remembered; plugin 1.12.0 and later) and, under **Gear**, armour and offhand, with item counts and durability bars as in the stash grid. Chat (public, whispers, system and death messages; the last 200 lines) is a side panel on wide screens and a drawer with an unread count on phones. **Latency** shows how far behind the view is (p50/p95 of bot to server, server to browser, drawing, and end to end). In streamer mode the coordinates and dimension and the chat text are blurred, chat names read "Player" and the map shows no names. The bot sends frames (up to 5 a second) only while someone has the page open, and stops 10 s after the last viewer closes; the stream closes 30 s after the tab is hidden, and when you leave the page. Up to 8 viewers can be open on the server, 2 per user and 4 per bot. Nothing of it is stored or logged: the audit log only notes who started and stopped watching which bot (at most once per 15 minutes per user and bot). Sub-users need the **Live viewer** permission (it also turns on Bots and activity); the Operator preset has it.

## Kits

Kit sorting sends the shulker boxes that are kits to the chests you choose here. The Kit Builder designs kits, which the bots keep in stock and build for kit orders. The bots do the classifying, sorting and building. This server keeps your choices, the templates and the scoring, and shows what the bots report. A bot that doesn't know kits ignores all of it, and the Kits tab says so.

What each bot does:

- **Plugin 1.14.0 and later:** kit sorting with the v1 score (see [The score](#the-score-v1)).
- **Plugin 1.16.0:** kit types with their chests, treat-as, the Kit Builder's templates, kit orders and restock. Kit orders and restock have not been tested in game yet.
- **Qwerte addon:** kit sorting only. It doesn't do kit v2 or Kit Builder bot work yet; that comes in a later addon release, and its local `build-kits` stays until then.

A bot that sends no v2 hash in its heartbeat keeps the v1 score, gets only the v1 settings, and gets no kit orders or restock.

### Kit types and chests

- **Types.** A bot sorts each shulker box into one of seven kit types, from the shared table `kits/kit-types.json` (built into the server): **PvP / Crystal**, **Travel**, **Redstone**, **Farming / Food**, **Building**, **Starter / Base** and **Other kit**. A box is a kit of a type when enough of its slots hold that type's items (three quarters of the slots, or half when its name has the word kit) and it holds enough different kinds of item for that type. A box of one kind of item is stock and is never a kit.
- **Custom categories.** Up to 16 per stash, each named with up to 32 characters (no `§` and no control characters). A bot never detects a custom category: a box gets one only through a template or a treat-as.
- **Chests.** Each type and each custom category can have a chest. The **general kits chest** takes the kits that have no usable chest of their own.
- **Where a kit goes.** The first usable of: its kit name's chest rule (see [The Kits tab](#the-kits-tab)), its template's chest, its category's chest, the general kits chest. A stored kit only moves up this chain, never down because a chest filled up. A full chest is not a warning; a missing chest is.
- **Reserved chests.** A type chest, a category chest and a template chest hold kits only, like the general kits chest.
- **Treat as.** For one kit name, pick a kind (a type or a custom category: its boxes are kits of that kind), **Not a kit** (its boxes are never kits), or **Auto** (the classifier decides; the default). It overrides the classifier, but a box of one kind of item is still stock.

### Kit Builder

The Kit Builder tab lists the stash's templates by kind. A template has:

- a **name** (1 to 50 characters, no `§` and no control characters, unique in the stash), a **kind** (a type or a custom category), a **chest** (blank: the kind's chest), a **shulker colour** (any, plain, or one of the 16 dye colours) and a **restock** count (off, or keep 1 to 27 complete copies);
- **27 slots**. Each slot is empty or holds an item with a count (up to its stack size). A slot takes one exact variant of the item (its components must match), or **any variant** of it, for example any enchanted sword.

A template needs at least two different items, and no two templates in a stash may hold the same contents. A stash has at most 50 templates. Nine presets (Crystal PvP, Sword PvP, Elytra Travel, Builder Basics, Assorted Concrete, Redstone Basics, Starter Base, Farmer and Food) fill a new template; a preset is not saved until you save it.

**Matching.** A box matches a template when it holds the same items in the same counts, with the same enchantments, trims, potion contents and other components. Slot positions, how stacks are split, custom item names, damage and repair cost are ignored, so a renamed or damaged copy still matches. Any-variant slots take any variant of their item. Then:

- a **complete copy** has everything the template has and nothing else;
- an **incomplete copy** carries the template's name, has some of the template's items missing and nothing the template doesn't have. The bot keeps it in its chest and tops it up. An incomplete copy is never delivered for an order.

**Restock.** With a restock count above 0, an idle bot builds or tops up copies from unreserved items, so the chest holds that many complete copies. Restock never crafts. Setting the count back to off stops it.

Creating, editing and deleting templates and kinds needs the **Change plugin settings** permission. Deleting a template that an open order uses is allowed, because the order keeps its own copy of it. Deleting a custom category that a template or a treat-as uses is refused with `409`.

### Kit orders

- **Ordering.** The Kit Builder's **Order** button puts a kit line in the cart, with a count from 1 to 54. The order and the Orders tab show the kit's name.
- **Limits.** One order has at most 16 different kits and reserves at most 256 item keys ("order fewer kits" otherwise). Each kit takes one shulker, so the 54-shulker cap of an order applies as well.
- **Placement.** Complete copies the stash already holds are used first. For the rest, the items are reserved from the stash. Short plain items are crafted when the bot can craft (plugin 1.16.0 with craft orders). Anything still short fails the order with `400`, listing the items: "only N complete <name> kits can be made: short <item> ×<n>".
- **Refused** with `409` when the bot can't build kits: `no_bot` (no bot that can build kits) or `disabled` (kit orders are off on the bot).
- **Complete copies count only while current.** The server counts a bot's kit report only while the bot's latest heartbeat (at most 10 minutes old) carries the hash of that report. A bot that has gone quiet, or has changed its kits since, is not counted, so its kits are built instead. Incomplete copies never count as ready.
- **A bot without the kit flag.** A bot that restarted, or has not loaded its kit settings yet, may claim a held kit order without the kit flag. The server doesn't fail the order at once: the claim gets nothing for up to 2 minutes after the first such claim. The order fails when no heartbeat with kit orders on has come in that time, and at 10 minutes after the first such claim at the latest. Flagless claims more than 10 minutes apart start a new episode, and an order that has seen two such episodes fails.
- **Retry** of a failed or cancelled kit order plans again against the template as it is now (`409` when the template is deleted).
- **Results.** A kit that can't be completed is never delivered. With two or more kinds of item and a name, it is stored as an incomplete copy in its chest; otherwise its items go back into the stash. The order's message says how many kits were not made, and why.

### The score (v1)

Bots that send no v2 hash (plugin before 1.16.0, and the addon) use the v1 score. Each shulker box gets points: `name` 2 for a custom name with the word "kit" or "kits" (a whole word: "PvP Kit" and
"kit_3" count, "Kitchen" and "Toolkit" don't), `mix` 3 for a mix of useful items, `full` 1 when all 27 slots are used, and `op` 4
when the box holds at least `opMin` OP items (3 by default, counted per item by stack count). The defaults give a threshold of 4.
A box of a single item is never a kit unless all 27 slots are filled. The OP and mix item lists are in `kit-items.json`, the same
table in every bot. The scoring editor sets the threshold, the points and `opMin` for each stash.

### The Kits tab

The tab is on the stash page, on the right just before Orders. Seeing it needs the **view** permission. It isn't shown to a user
the owner has forced into streamer mode, and the server refuses kit requests from that user with `403`. Changing anything needs
**Change plugin settings** (the `settings` permission). Without it the tab is read-only, and its banner says so.

- **Banner.** Says when the bot doesn't know kits yet, is offline, hasn't picked up the latest settings, hasn't reported its
  containers yet, is too old for kit types (its type chests may get other items), or when the general kits chest is missing or full. No chest can be picked until the bot has reported its containers.
- **General kits chest.** Takes every kit that has no chest of its own. Unnamed kits without a rule always go there. **Clear** (after a confirm)
  stops using it: those kits go back to normal sorting. Only chests the bot reported as able to take kits are listed. The chest a kit already
  uses is kept in the list, marked "can't take kits", when it no longer qualifies. Picking a chest whose sign doesn't say "kit"
  asks you to confirm first, because it will then take only kit shulkers.
- **Chests by kind.** A chest for each type and each custom category, chosen the same way as the general kits chest.
- **Chest per kit.** The **Chest** column gives one kit name its own chest. "Kits chest (default)" (after a confirm) removes that rule. Unnamed kits
  show "(unnamed)" and have no rule: they always use the general kits chest.
- **Treat as.** Per kit name, on the rows the classifier does not count as kits: pick a kind, **Not a kit**, or **Auto**.
- **Table.** One row per kit name, up to 500 rows: the name, how many there are (in chests plus carried), where they
  are now, where they go, the status and the chest. Kit Builder kits are marked as such, and an incomplete copy shows its reason.
  The v1 score reads like "10/10: name 2, mix 3, full 1, OP 4". "(lowest 8)" is added when boxes of that name score differently.
- **Statuses.**
  - *In place*: all of them are in the target chest, and none is carried.
  - *Waiting for the sorter*: the bot hasn't moved them there yet.
  - *Target full*: the target chest has no free slot.
  - *Target missing*: the target isn't in the bot's report, or it can't take kits.
  - *Normal sorting*: no chest applies to them.
  - *None in the stash*: there is a rule for this name, but no such kit is in the stash or carried.
- **Scoring editor.** Six boxes for the v1 score: Threshold (1 to 40), Name, Mix, Full and OP (0 to 10 each), and OP items needed (1 to 1728).
  The highest possible score is shown next to them. **Save** is enabled when the values are valid and changed. **Reset to defaults**
  is disabled at the defaults and asks you to confirm. The bots re-score every box on their next pass after a save.
- **Streamer display.** In the page's own streamer mode, kit names are blurred, chests show as "Chest 1", "Chest 2" and so on, and
  the confirm texts carry no label or position.
- **Limits.** 200 kit names per stash, and 60 kit changes a minute per user (then `429` with a `Retry-After` header).

### Streamer mode and kits

A user the owner has forced into streamer mode doesn't see the Kits tab, and the server refuses the kit settings requests from them with `403`. They can see the Kit Builder, read-only: kit names read "Kit 1", "Kit 2" and so on, custom categories read "Category 1" and so on, every chest is hidden, and template changes are refused. Kit orders still work for them, because they show no positions.

### Stored settings: kits.json and kits.json.bak

Each stash's kit settings (the general kits chest, the chests by kind, a chest per kit name, treat-as, custom categories, the Kit Builder templates, the scoring and a revision number) are in `stash-manager-data/kits.json`, or in the folder you give with `--data`. The file holds at most 64 stashes. It is written
atomically and only the owner can read it. Don't edit it while the server runs: stop the server, edit, then start it again.
An older server that rewrites this file drops the kit types parts (chests by kind, treat-as, categories and templates), so keep the server up to date once you use them.

- **`kits.json.bak`** is the file as it was before the last write. It is only refreshed when the old `kits.json` reads with no
  damaged entries, so a damaged file never replaces a good backup. If `kits.json` is missing or unreadable at startup, the server
  restores from the backup.
- **Neither file readable:** kit sorting is off for every stash. The kit routes answer `503`, the bots get no kit fields in their
  heartbeat replies, and the server never writes over either file. Repair one of them by hand and restart.
- **A damaged entry** (one stash's entry that doesn't read) affects only that stash: its kit routes answer `503` and its bots get
  no kit fields, while the other stashes work. The console says so at startup. The bad entry is kept as it is and written back
  unchanged on every save, so nothing is lost. To repair it, fix that entry by hand (its `server` and `stash` must stay the same),
  or delete the entry to drop that stash's kit settings, then restart.

The kit table (`kits/kit-types.json`) and the presets (`kits/kit-presets.json`) are read-only resources built into the jar.

The kit reports the bots send are kept in memory only, for at most 64 stashes. They are never written to disk or logged. After a
restart the Kits tab has no report for a stash until its bot sends a new one, which happens on the bot's next heartbeat.

## Crafting from the stash

The Build Planner and the Printer's stash orders can order items the stash does not hold but can make from what it holds. The server decides what is craftable (one planner for the website, the in-game window and the Printer); the stash bot carries the order out: it takes the ingredients from the stash, crafts at the crafting table you set for that stash, and delivers the results with the rest of the order. Only crafting-table recipes (shaped and shapeless) are used: no smelting, stonecutting, smithing, shulker-box dyeing or bundles, and no recolouring recipes. Kit orders use the same planner for the plain items a kit is short of, when the bot can craft (see [Kit orders](#kit-orders)).

- **Recipes** come from the Minecraft client jar, in the same one-time pass as the icons: `stash-manager-data/recipes/<version>/` holds the saved recipe and tag files, `table.json` (served to the bots at `GET /api/v1/bot/recipes?mc=<version>`), `manifest.json` with the table's `tableHash`, and the `.complete` marker. If there is no jar on the machine the official one is downloaded once (`recipeDownload`, which follows `iconDownload`), and a version whose icons are done but whose recipes are missing downloads once more. Nothing from the jar is kept anywhere but that folder. A new server version that changes the table format rebuilds `table.json` from the saved files with no download.
- **Settings per stash** (`POST /api/v1/craft/table` and `/never-use`): the crafting table's position and dimension (the bots' own table only serves shulker-box crafting; craft orders need this one) and a list of items the planner must never use as ingredients (shulker boxes and bundles never are). They are in `stash-manager-data/craft.json`, written atomically with a `craft.json.bak`, loaded like `kits.json` (a damaged entry switches crafting off for that stash only; neither file readable switches it off everywhere and nothing is overwritten). Forced-streamer users cannot read or change them, because they hold a position.
- **When can an order include crafting?** The recipes for the stash's version are ready, the stash's bot told the server (in its last heartbeat since the server started) that it can carry out craft orders, its recipe table is the server's (same `tableHash`), a table is set, and the bot says the table is usable. Otherwise the order is refused with `409` and a `craft` code (`no_recipes`, `no_bot`, `recipes_differ`, `no_table`, `table_bad`), and the planner says why. Bots that are not new enough are never given a craft order: their claims skip it, and a craft order they already held becomes failed ("the bot can no longer craft") and can be retried.
- **Reservations:** the ingredients of an open craft order are reserved like item lines, everywhere the stash's free amounts are counted.
- **Limits:** at most 4 crafting levels below the item asked for; at most 64 steps and 64 ingredient kinds per order; the bot's own cap on grid fills per order (it reports it); 3 s and 1,000,000 steps per plan. The planner never rounds crafting up to stacks, and what is made over goes back to the stash.

## Repair passes

When the stash bot is idle, it can repair the items in its stash that are low on durability. Mending items are repaired with Bottles o' Enchanting from the stash, and sorting then puts each one in its best chest. Items without Mending go to a **Needs repair** chest that you pick here. The bot does the work. This server keeps the settings, shows the bot's status and its last pass, and passes the website's run and stop requests on to the bot. Orders always come first. The bots that run passes are Stash Sorter plugin 1.17.0 and later; the Qwerte addon has none yet. Nothing here has been tested in game.

The panel is on the **Kits** tab, below the Stash area and Crafting table panels. Seeing it needs the **view** permission, and changing it needs **Change plugin settings** (the `settings` permission). A user the owner has forced into streamer mode doesn't see the Kits tab, and the server refuses their repair requests with `403`.

### Settings

Each stash has its own settings, which the server stores (see below). The bot caches them and sends back a hash of them, so it always knows which version it has.

| Setting (panel) | Default | Range | What it does |
| --- | --- | --- | --- |
| Repair mode (`enabled`) | on | on or off | Off: no passes run, a run request is dropped, and needs-repair items are sorted like any other. The bot reports "disabled". |
| Automatic when idle (`auto`) | on | on or off | Off: passes run only when you press **Run repair pass**. |
| Open unnamed shulker boxes (`shulkers`) | on | on or off | An unnamed box that isn't a kit can be opened for the items in it. Named boxes and kits are never opened. |
| Threshold (`threshold`) | 50 | 1 to 100 % | Items below this durability are repaired. "Below 50%" is strict: exactly half is not low. 100 means any damage. |
| Reserve (`reserve`) | 1728 | 0 to 1,000,000 bottles | A pass never takes the stash below this many bottles. One full shulker is 1728. The panel takes shulkers, stacks and bottles and stores bottles. |
| Needs-repair chest (`needsRepairChest`) | none | a chest the bot reports | Where items without Mending go. Clear it to stop. |

The bot's own limits (how many items and boxes one pass takes, how long it may throw, the gap between passes and its bottle floor) are set on the bot, not here. Their defaults are in the Stash Sorter plugin's README and CHANGELOG (1.17.0).

### What the bot does

- It starts a pass only when it is idle: nothing carried, no job running, no order held or waiting, no other module working, and no player within 8 blocks (friends count too). It gives way at once to an order, a player, a stop, or the sorter being switched off, and puts everything back.
- A pass takes only the bottles above the reserve. An item whose bottles don't fit is skipped with the reason "reserve".
- Opening a box needs nobody within 16 blocks (friends count too). One box is open at a time, and a box is not opened again for 24 hours unless its contents change. Items that come back unrepaired are not retried by automatic passes for 6 hours.
- The bot reports each pass to this server, and the last pass shows on the panel. After a server restart the status and the last pass are empty until the bot's next heartbeat, and the server asks for the report again then.

### The needs-repair chest

- Only a chest the bot reports as able to take items can be picked. A kit chest can't be the needs-repair chest, and a needs-repair chest can't be a kit chest: the kit routes and this panel each refuse the other with `400`.
- The bot reserves this chest. Nothing else is sorted into it, and Mending items never go there. An item that has been repaired by hand, or is no longer low, is sorted normally.
- The panel says what state the chest is in: not set, ready, full (it has no free slot), missing (the bot doesn't see it), or used for kits.

### The panel

- **Status line:** the bot's state in plain words ("idle", "waiting: an order comes first", "repairing (3 of 12 items)", "blocked: repair mode is off"). It says so when the bot hasn't picked up the latest settings yet, or when it can't run passes at all (a bot without this release).
- **Estimate tiles:** Mending items to repair and the bottles they need; items without Mending; items in boxes the bot will open; items in named boxes or kits, which aren't opened; and the bottles usable above the reserve. The bot's own numbers are shown when it reports them.
- **Run repair pass** asks the bot to start a pass when it is idle. **Stop** ends a running pass and drops a queued run.
- **Last pass:** the trigger (automatic or manual), how it ended, how many items were repaired or only partly, the bottles used, how many moved, the reasons items were skipped, and the item list. The "to" part of each item is not reported yet (see the known limits in the CHANGELOG).

### Limits

- Settings and needs-repair chest changes: 60 per minute per user. Run and stop: 6 per minute per user. Over the limit, the server answers `429` with a `Retry-After` header.
- A run or stop for a bot that can't run passes answers `409`. A bot that is offline gets the request when it reconnects.

### Stored settings: repair.json and repair.json.bak

Each stash's repair settings are in `stash-manager-data/repair.json`, or in the folder you give with `--data`. The rules are the same as for `kits.json`: the file is written atomically and only the owner can read it, the previous file is kept as `repair.json.bak` when it reads clean, and the backup is used when the file is missing or unreadable. At most 64 stashes are stored.

- **One stash's entry can't be read:** repair is off for that stash only (its routes answer `503`). The entry is kept as it is and written back unchanged on later saves. Fix it by hand, keeping its `server` and `stash`, then restart.
- **Neither file can be read:** repair is off for every stash, the routes answer `503`, and the server never writes over either file.

## What the server prints at start

The start-up output goes to the console and, for a service, to its journal, which more people can read than the data folder. So the bots' **API key is printed only on the run that generated it** ("first setup: copy it now; it won't be shown again"). Every other start only says "API key: set". The key stays in `config.properties`, and the website's **Link addon** button links a bot without typing it. The dashboard setup code is shown only while no website password is set, and is never shown again once one is. No password, session or token is ever logged. If a start script tees the console to a file (`java -jar ... | tee server.log`, a redirect, a service that logs to a file), the key printed on the first run stays in that file: delete the line from it, or restrict the file, after you have copied the key.

## Running several bots

One server and one API key can serve any number of bots (Meteor addons or proxy plugins), for example two ZenithProxy accounts at two stash locations. Each bot needs its own **stash name** (Meteor: stash-sorter's `stash-name`; proxy: the stash name in the plugin). Every (server, stash name) pair is its own stash on the website, with its own index, orders, delivery container and rescans, so the bots never mix up items or orders.

- A stash name belongs to the first bot (player) that uses it. Another bot that uses the same name on the same server is refused (HTTP 409) and should tell you to pick another name; the website then shows "Another bot (<player>) tried to use stash '<stash>'" on that stash.
- **Bots** in the header lists every stash with its bot, online status, what it is doing, its delivery container and open orders. Click a row to switch to that stash. **Release** frees a stash name so another bot can take it (the old bot takes it back on its next heartbeat if it still uses that name). A bot that hasn't been seen for 7 days loses its stash names automatically to the next bot that uses them.
- The stash picker shows `● stash · player @ server` (● online, ○ offline). The Link addon panel lists every linked bot.

## Setting up an already-sorted stash

If the stash is already sorted and you only want the bot to index it and fill orders:

1. In Meteor, turn on **stash-sorter** with **sort-items** off, so it only reads containers and never moves items, and turn on **stash-manager** (linked as above).
2. On the website, press **Rescan stash** in the header and confirm. The bot re-reads every container in the stash, nearest first, and also picks up new containers inside the stash area. Items already listed stay visible until their container is read again.
3. The header shows the progress: "Rescan requested: waiting for the bot", then "Re-reading containers: X / total" with a bar, then "Rescan complete". If the bot is offline, the rescan starts when it reconnects; if it says "Can't rescan yet: sorter off", turn the sorter on.
4. Turn on **accept-orders** in stash-manager and set the **delivery container** (stash-manager → Delivery → select-delivery, then right-click a chest). The Orders tab shows the delivery container once the bot reports it.

## Testing without Minecraft

`dev/fake-addon.py` plays the addon: it pushes a sample index with tabs and shulkers, sends heartbeats, claims orders and reports their progress. It needs only Python 3:

```bash
python -I dev/fake-addon.py --url http://127.0.0.1:8765 --data stash-manager-data push
python -I dev/fake-addon.py --url http://127.0.0.1:8765 --data stash-manager-data heartbeat
python -I dev/fake-addon.py --url http://127.0.0.1:8765 --data stash-manager-data run   # claim and deliver one order
```

`--data` is only used to read the API key from `config.properties` (or pass `--key`). Run it with `-h` for the other commands (`push --no-tabs`, `claim`, `status`, `run --fail`). `serve` keeps sending heartbeats (2 minutes by default) and answers a website rescan: it acknowledges it, then pushes indexes with `containers.stale` counting down (`--rescan-blocked "sorter off"` acknowledges but reports the reason instead). `--player NAME` (before the command) plays a second bot, for testing stash ownership. `dashboard` (add `--activity` for changing activity data: a queue countdown, the sorter, an order, the pathfinder, pearl loads and modules toggling; add `--proxy` for a big fake AquariusProxy config of about 400 settings in 29 sections and a `plugin:autoreply` config, and `--leak-secrets` to act like a buggy bot that sends secret values, which the server must drop) plays a proxy bot for the dashboard (a fake plugin config with a secret, read-only settings and a nested group; console commands `help`, `status`, `slow` (a second output part after 2 s), `fail`, `auth ...` (sensitive) and `drift` (changes a setting without telling the server, to try a conflict)). Heartbeats report a delivery container at 120, 64, -340; put `--no-delivery`, `--delivery x,y,z[,dimension]` or `--blocker "no delivery box"` before the command to change what they report. `--naming true:30` (anvil set, 30 XP levels), `--naming false:0` or `--naming null` (a bot that can't name shulkers) adds the heartbeat's `naming` field; without it the field is left out, like an older bot. `--crafting true:true` (auto-crafting on, crafting table set), `--crafting true:false` or `--crafting null` adds the heartbeat's `crafting` field the same way. `push --other-tabs` pushes the sample index with every tab `other`, like a proxy bot, to see the server fill the tabs in. `delivery` plays a bot that reports its delivery storage contents (`--used N` stacks in the first chest, `--chests N`, `--unseen` for a never-read last chest, `--auto-expand`); `viewer` plays a live-viewer bot (a fake world with water, sand, grass, trees and stone; it walks a circle with 30 entities around it and chats every 2 s), sending frames only while the Live page is open: `--bot-id`, `--hz`, `--radius`, `--max-frames`, `--ents`, `--walk-radius`, `--seconds`.

## Access

- Only loopback, private LAN (10/8, 172.16/12, 192.168/16), link-local and IPv6 ULA addresses can connect. Everything else gets `403`.
- The browser has to reach the server through an IP address, `localhost`, this machine's name (also with `.local` or `.lan`), or a name you add to `allowedHosts`. This blocks DNS rebinding.
- The website needs a login (see Website password). What a logged-in user can do depends on their account: the owner can do everything; sub-users only what the owner allows them (Settings > Users). The API key is shown to the owner only.
- **Behind an HTTPS reverse proxy** (optional; for example nginx, Caddy or a tunnel service forwarding to `http://<server>:8765`): add the public name to `allowedHosts`, e.g. `allowedHosts=stash.example.com`. The website then works at `https://stash.example.com`: the server accepts an `https://` Origin only for names listed there, and compares just the name (see "Browser writes" in PROTOCOL.md). The proxy's own connection must still come from a LAN address. The session cookie stays HttpOnly and SameSite=Strict, without the Secure flag, because the server itself only speaks HTTP.
- The addon's endpoints (`/api/v1/index`, `/api/v1/heartbeat`, claiming orders and reporting their status) need the API key. Changes made from the website (regenerating the key, placing and cancelling orders) need a same-origin request with a special header, so other websites you visit can't trigger them. See PROTOCOL.md.

## Security notes

- **Everything is plain HTTP.** The API key, the index, the website password (when logging in or confirming) and the session cookie are sent unencrypted, so anyone who can watch your network traffic can read them and take over the website, including the bots' console. Only use the website on a trusted home LAN, or reach it over a VPN or an SSH tunnel; never over public Wi-Fi or an untrusted network. Use a password you don't use anywhere else.
- The console can do anything the proxy's terminal can. That is why it needs the password to have been typed in the last 15 minutes.
- The password is stored only as a salted PBKDF2-SHA256 hash (600,000 iterations) in `config.properties`. `sessions.json` holds only hashes of the login tokens. The session cookie is HttpOnly and SameSite=Strict, and every change made from the website also needs a same-origin request with a special header.
- The addon stores the API key in plaintext in the Meteor config.
- By default the server only accepts LAN addresses. Do not port-forward its plain-HTTP port. If you want it reachable from outside, that is your choice and your setup: put it behind an HTTPS reverse proxy or a VPN you control, add your own access control there if you want it, list the public name in `allowedHosts`, and the proxy's address in `trustedProxies`. The server depends on no particular proxy or service, and its own login, rate limits and permissions work the same with or without one.
- **Accounts:** one owner (the first-run password) and optional sub-users with their own passwords and permissions (view, order, all orders, bots, settings, proxy, console, rescan, link, Build Planner, live viewer; optionally limited to some stashes or bots). The server enforces every permission on every request; the page only hides what a user can't use. User management, the audit log and the API key are the owner's only. Give `console: full` and `proxy: change` only to people you trust like yourself.
- **Logins:** failed logins are rate limited per address and account, per address, and globally; the server PC itself (localhost, no proxy headers) is never locked out. Each session remembers the password it was made with and ends when that password changes; disabling or deleting a user ends their sessions at once. An audit log records logins, account changes, console commands, settings changes and order actions (`audit.json`).
- **Build Planner uploads** are parsed in memory with size, depth, tag, memory, volume and time limits, and never written to disk; only the computed material list is kept in memory, for at most 2 hours.
- Item names can be set by players, so the page treats all of them as untrusted text.
- `config.properties`, `sessions.json`, `users.json`, `audit.json`, `kits.json`, `kits.json.bak` and the data folder are restricted to the user the server runs as (owner-only permissions on Linux and macOS, an ACL with just that user on Windows).

## Item icons

Icons come from two sources:

1. **Your Minecraft installation (primary).** The server renders each item's inventory icon from the client jar of the index's Minecraft version (1.21.4 or newer), at 64 px. This takes a few milliseconds per item and needs no internet. Block-entity items, which the game draws from an entity model instead of a block model (`minecraft:special` item models), are rebuilt from vanilla's entity models with the jar's own textures: every shulker box colour, chests (normal, trapped, ender, copper), beds, banners (base colour only, without patterns), skeleton, wither skeleton, zombie, creeper and player heads, the shield, the conduit and the decorated pot (plain sides). Each is drawn as the same isometric item the game shows.
2. **The Minecraft Wiki (fallback).** Used for items the renderer can't draw (dragon and piglin heads, copper golem statues), for versions without a usable jar, and on machines without Minecraft.

The client jar is found automatically. For version `<v>` the server looks in:

- Prism Launcher: `%APPDATA%\PrismLauncher\libraries\com\mojang\minecraft\<v>\minecraft-<v>-client.jar` (on Linux `~/.local/share/PrismLauncher/...` or the Prism flatpak; on macOS `~/Library/Application Support/PrismLauncher/...`);
- the vanilla launcher: `%APPDATA%\.minecraft\versions\<v>\<v>.jar` (on Linux `~/.minecraft/...`; on macOS `~/Library/Application Support/minecraft/...`).

Set `minecraftJar=` in `config.properties` to use a specific jar, or a launcher or `.minecraft` folder to search. Use forward slashes on Windows (`C:/Users/me/AppData/Roaming/PrismLauncher`), because a single backslash is an escape character in `.properties` files. If that path has no usable jar, the server says so in its log and falls back to the automatic search. `iconSource=` picks the sources: `auto` (game first, then the wiki; the default), `game` (the game only; items it can't draw get the placeholder) or `wiki` (the wiki only). The startup log names the jar used for each version, or lists the places it looked.

As soon as an index arrives (or is loaded at startup), the server renders every item that isn't cached yet and queues only the rest for the wiki, so icons are usually ready before you open the page. Only items that appear in a stored index get icons.

Caches, both in `stash-manager-data/icons/`:

- `game-<version>/<namespace>/<item>.png`: game renders, one folder per Minecraft version;
- `<namespace>/<item>.png`: wiki icons. These are never mixed with game renders, and they stay on disk when game renders take over.

### Wiki details

For each item the wiki pipeline tries, in order:

1. The Minecraft Wiki inventory icon, looked up 50 at a time through the wiki's API: first `Invicon_<English name>`, then `Invicon_<Title_Cased_Id>` (that one catches items whose English name is generic, like music discs).
2. Only when the wiki definitely has no icon: the vanilla item texture from [mcasset.cloud](https://mcasset.cloud) for the index's Minecraft version.
3. Then the vanilla block texture from mcasset.cloud.
4. A built-in purple and black "missing texture" placeholder.

Steps 2 and 3 are skipped for block-entity items (shulker boxes, chests, beds, banners, heads and skulls, the shield, the conduit, the decorated pot, copper golem statues): their block texture is a flat face or particle sprite, not the item. If the wiki only failed for now (network error, 429, 5xx), the item is retried in 2 minutes instead of falling back to a texture.

Animated GIFs from the wiki (enchanted book, bottle o' enchanting, written book) are stored as their first frame; the page draws its own enchantment glint. Anything larger than 64 px is scaled down.

The server keeps to about one wiki API call a second and ten image downloads a second; icons the page is waiting for jump the queue. Until an icon is ready the page shows the placeholder and asks again every few seconds.

When every source says an icon doesn't exist, the server remembers that for 24 hours. After network errors, 429 or 5xx it tries again after 2 minutes. On startup it removes old retry markers and GIF/WebP icons from earlier versions so they are fetched again as PNG.

The wiki cache has a version, stored in `icons/.cache-version`. Version 2 fixes icons that older servers cached from a flat block texture after a failed wiki download (the flat "shulker tops"): on the first start of a newer server, every wiki icon and `.miss` marker in `icons/<namespace>/` is deleted once and fetched again. Game renders in `icons/game-<version>/` are kept. The page also asks for icons under a new URL, so browsers don't reuse their week-long cached copies. Delete the `icons` folder to refetch and re-render everything. Icons from your game files, the Minecraft Wiki and mcasset.cloud are Mojang's artwork; this tool only caches them for local viewing.
