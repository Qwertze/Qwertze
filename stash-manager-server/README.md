# Stash Manager server

A small standalone web server for the Qwerte addon's `stash-manager` module. The module pushes the item index of a Minecraft stash (what is in every known container) to this server, and the server shows it as a website that looks like a Minecraft inventory, for anyone on your local network.

It also takes orders for the bots to fill, and can show and change the AquariusProxy plugin's settings, console and activity. Orders, the dashboard and streamer mode are new and still being tested.

The wire format and the access rules are in [PROTOCOL.md](PROTOCOL.md).

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

On startup the server prints the URLs it can be reached at (localhost and your LAN IPs) and the API key. Open one of the URLs in a browser. Stop the server with Ctrl+C. If the port is already in use, the server says so and exits; change `port=` in the config.

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

Restart the server after editing.

## Website password

The whole website is behind one password (the bots don't need it; they use the API key).

- **First run:** the server window shows a one-time **setup code** (`Dashboard setup code: XXXX-XXXX`). Open the website **on the server PC itself** (`http://localhost:8765`), type the code and choose a password (at least 12 characters; very common passwords are refused). Other computers see "Set the dashboard password on the server PC" until then.
- **Logging in:** each browser stays logged in for 30 days. **Log out** in the header logs out this browser; the Link addon panel has **Change password** (needs the current one and logs out every other browser) and **Log out everywhere**.
- **Forgot it / reset:** stop the server, delete the `dashboardPassword=` line from `stash-manager-data/config.properties`, and start it again. Every login ends, and the first-run setup appears again on the server PC.
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

- **Tabs.** Items are grouped like the creative inventory: Search (everything), one tab per creative tab that has items in the stash, Shulkers, and Orders on the right. Inside a creative tab items are in creative-menu order by default; the sort menu can switch to count, name or id. The search box filters the current tab, and searches everything on the Search tab. With the search box not focused, the left and right arrow keys switch tabs. Proxy bots, and indexes from older addon versions, send no tab data. For them the server fills the tabs in from its own creative-tab table for the index's Minecraft version (1.21.4 and 1.21.11 ship with it), so their items show in the creative tabs too; an item the table doesn't know stays under Other.
- **Shulkers.** The Shulkers tab shows every filled shulker kind (identical shulkers are grouped, with a count). Right-click one to see its contents in a shulker-box window; close it with X, Escape or a click outside. Right-clicking a shulker box item in another tab lists the filled ones of that kind, or says they are all empty. **Shulker icons:** a plain shulker box icon always means empty shulkers. A filled shulker is drawn as the box with the item it holds most of on top (the most of by total count over all its stacks; items with the same id count together; a tie goes to the one in the lowest slot), and a small **+** in the top-left corner when it also holds other kinds of items. This is the same everywhere shulkers appear as icons: the creative tabs, Search, the Shulkers tab, the order picker, cart lines and the planned delivery chest. In the creative tabs and Search, a shulker box item's slot counts only its empty boxes, and its filled groups follow right after it (they are also found by what they hold: searching "diamond" lists shulkers full of diamonds). The tooltip and the shulker window still list the full contents.
- **Orders.** The Orders tab is the order maker. Pick an item type, then drag an item onto the cart or double-click it to add one stack (hold Shift for a shulker's worth, 27 stacks). Click an item for an exact amount. The cart shows the planned delivery as a double chest: each planned shulker is one slot, with a fill bar (orange when 27/27). Below it, a shulker-box view shows the selected shulker's slots; it follows the last shulker as you add things, and says so when one fills up. Click a shulker in the chest to look inside, and right-click a stack there to remove it. One order can fill at most 54 shulkers (one double chest); the page and the server refuse more. The top of the tab shows the delivery container the bot reported, or a warning when none is set, and why the bot can't take orders, if it says so. The cart is kept in the browser per stash. "Place order" sends it to the server, and the bot picks it up, packs the items into shulkers and delivers them to the delivery container you set in game in the stash-manager module. Orders can be placed while the bot is offline; they wait until it reconnects. Shift-click an item in the other tabs to add a stack to the cart, and use "Add to order" in a shulker window to order a packed shulker as it is. An item line for a shulker box means empty shulkers. **Shulker names:** under the cart, choose "No names" (the default), "Same name for all" (one name for every shulker), or "Name each shulker" (one box per planned shulker, in delivery order, showing what it will hold; leave a box empty to keep that shulker unnamed). Names are up to 50 characters, without `§`. The bot renames the shulkers in an anvil before delivering them; if it can't (no anvil set, not enough XP, ...), they arrive unnamed and the order still completes. The form warns when the bot can't name shulkers, has no anvil set, or may run out of XP (about 1 level per shulker). When the bot reports auto-crafting on, a note says it may craft missing empty shulker boxes from the stash's materials (or that it can't, because no crafting table is set). The bot plans again from what it can reach, so it may deliver fewer shulkers; names go in order. The order list shows each order's status and progress and its names, and open orders can be cancelled (a running order stops at a safe point). **Retry** on a failed or cancelled order places it again as a new order (same items and names), checked against what is in the stash now. **Clear history** removes the finished orders (delivered, failed, cancelled) from the list; open orders are kept.

Anyone with the website password can place and cancel orders, the same as everything else on the page.

## Dashboard: proxy bots

Proxy bots running the Qwerte Stash plugin with the dashboard turned on (`stash manager dashboard on` in the proxy) show up on the website. Pick one in the header's **Bot** menu (remembered per browser; ● online, ○ offline).

- **Activity** (bots whose plugin reports it; the page opens here until you pick another tab): what the proxy is doing right now. The top card shows the connection (online, in queue with position and ETA, connecting, offline), server, dimension, position, health and food, ping, uptime, spectators and who is controlling the bot, plus when the last report came (a warning when it is over a minute old). **Now doing** has a card for each thing that is working (stash sorter, stash order, pathfinder, pearl loader, queue, ...), with a progress bar where there is one. **Modules** lists every module of the proxy and its plugins with an on/off dot (filter by name, or show enabled ones only); click one to open its Proxy settings. **Plugins** lists the installed plugins and versions. **Events** is a live feed (newest first) that can be filtered by source and level; the server keeps the last 500 per bot. The page refreshes every 2 seconds while it is open. The Bots page and the bot menu's tooltip show a one-line "now doing" for each bot.
- **Bots:** every proxy bot with its plugin and proxy versions and the stashes of its player, plus the stash table (owner, status, delivery container, open orders, Release).
- **Settings:** the plugin's settings, grouped by section. Change any number of them, then **Save** (or **Revert**). Each changed setting shows a spinner, then "applied ✓" with the value the bot read back, or the error next to it. If a setting was changed elsewhere meanwhile (in the terminal or Discord), it is reloaded instead of overwritten. Secrets only show whether they are set; read-only settings are greyed out with the reason. While the bot is offline the form is read-only.
- **Proxy** (bots with the plugin's proxy access): every AquariusProxy setting, plus the configs of other installed plugins (the menu next to Save). The left list has every section and module with its number of settings (a dot marks unsaved changes); the search box finds settings by path, name or help text across all of them. A module's on/off switch is shown first, highlighted. Saving works like Settings, but needs the password typed in the last 15 minutes. Secrets show only set / not set and are never sent to the website; settings that need a restart or are dangerous to change while running are read-only with the reason. The first time a config is opened, the page asks the bot for it (about a second).
- **Console:** run proxy commands as if typed in the proxy's terminal, with the reply rendered under each command. Up/Down recall earlier commands. Commands that can disconnect or restart things (`shutdown`, `update`, `disconnect`, `auth`, `jvmArgs`, `plugins`, `releaseChannel`, `discordManage`) ask first. Commands whose input is sensitive show as "(hidden)". The page keeps the last 200 commands per bot (until the server restarts).

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

`--data` is only used to read the API key from `config.properties` (or pass `--key`). Run it with `-h` for the other commands (`push --no-tabs`, `claim`, `status`, `run --fail`). `serve` keeps sending heartbeats (2 minutes by default) and answers a website rescan: it acknowledges it, then pushes indexes with `containers.stale` counting down (`--rescan-blocked "sorter off"` acknowledges but reports the reason instead). `--player NAME` (before the command) plays a second bot, for testing stash ownership. `dashboard` (add `--activity` for changing activity data: a queue countdown, the sorter, an order, the pathfinder, pearl loads and modules toggling; add `--proxy` for a big fake AquariusProxy config of about 400 settings in 29 sections and a `plugin:autoreply` config, and `--leak-secrets` to act like a buggy bot that sends secret values, which the server must drop) plays a proxy bot for the dashboard (a fake plugin config with a secret, read-only settings and a nested group; console commands `help`, `status`, `slow` (a second output part after 2 s), `fail`, `auth ...` (sensitive) and `drift` (changes a setting without telling the server, to try a conflict)). Heartbeats report a delivery container at 120, 64, -340; put `--no-delivery`, `--delivery x,y,z[,dimension]` or `--blocker "no delivery box"` before the command to change what they report. `--naming true:30` (anvil set, 30 XP levels), `--naming false:0` or `--naming null` (a bot that can't name shulkers) adds the heartbeat's `naming` field; without it the field is left out, like an older bot. `--crafting true:true` (auto-crafting on, crafting table set), `--crafting true:false` or `--crafting null` adds the heartbeat's `crafting` field the same way. `push --other-tabs` pushes the sample index with every tab `other`, like a proxy bot, to see the server fill the tabs in.

## Access

- Only loopback, private LAN (10/8, 172.16/12, 192.168/16), link-local and IPv6 ULA addresses can connect. Everything else gets `403`.
- The browser has to reach the server through an IP address, `localhost`, this machine's name (also with `.local` or `.lan`), or a name you add to `allowedHosts`. This blocks DNS rebinding.
- The website needs the password (see Website password). Logged-in users can see the stash index, place and cancel orders, and see or regenerate the API key in the Link addon panel.
- **Behind an HTTPS reverse proxy** (e.g. a Cloudflare Tunnel to `http://<server>:8765`): add the public name to `allowedHosts`, e.g. `allowedHosts=stash.example.com`. The website then works at `https://stash.example.com`: the server accepts an `https://` Origin only for names listed there, and compares just the name (see "Browser writes" in PROTOCOL.md). The proxy's own connection must still come from a LAN address. The session cookie stays HttpOnly and SameSite=Strict, without the Secure flag, because the server itself only speaks HTTP.
- The addon's endpoints (`/api/v1/index`, `/api/v1/heartbeat`, claiming orders and reporting their status) need the API key. Changes made from the website (regenerating the key, placing and cancelling orders) need a same-origin request with a special header, so other websites you visit can't trigger them. See PROTOCOL.md.

## Security notes

- **Everything is plain HTTP.** The API key, the index, the website password (when logging in or confirming) and the session cookie are sent unencrypted, so anyone who can watch your network traffic can read them and take over the website, including the bots' console. Only use the website on a trusted home LAN, or reach it over a VPN or an SSH tunnel; never over public Wi-Fi or an untrusted network. Use a password you don't use anywhere else.
- The console can do anything the proxy's terminal can. That is why it needs the password to have been typed in the last 15 minutes.
- The password is stored only as a salted PBKDF2-SHA256 hash (600,000 iterations) in `config.properties`. `sessions.json` holds only hashes of the login tokens. The session cookie is HttpOnly and SameSite=Strict, and every change made from the website also needs a same-origin request with a special header.
- The addon stores the API key in plaintext in the Meteor config.
- Do not port-forward this server or expose it to the internet. It is meant for a trusted home network only.
- Item names can be set by players, so the page treats all of them as untrusted text.
- `config.properties`, `sessions.json` and the data folder are restricted to the user the server runs as (owner-only permissions on Linux and macOS, an ACL with just that user on Windows).

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
