# Stash Manager protocol (v1)

How the Qwerte addon's `stash-manager` module talks to the Stash Manager server. Everything is JSON over plain HTTP and is meant for a local network only.

## Auth and access

- **Addon → server** (`/api/v1/index`, `/api/v1/heartbeat`): header `Authorization: Bearer <apiKey>`. The server generates the key the first time it runs, saves it in `stash-manager-data/config.properties` and prints it. The key is only accepted in that header (never in a query string or cookie) and is compared in constant time. A wrong or missing key gets `401`.
- **Every request** (browser and addon) is refused with `403` unless:
  - the remote socket address is loopback, site-local (10/8, 172.16/12, 192.168/16), link-local, or IPv6 ULA (fc00::/7). The `allowRemote=true` config setting turns this check off. `X-Forwarded-For` is never read.
  - the `Host` header (without its port) is an IP literal, `localhost`, the machine's hostname (or that hostname plus `.local`/`.lan`), or appears in the config's `allowedHosts` list. This blocks DNS rebinding.
- No CORS headers are ever sent.

## POST /api/v1/index

The addon sends this when its index hash changes, or when a heartbeat reply says `needIndex: true`. The body is at most 8 MiB; anything larger gets `413`.

```json
{
  "version": 1,
  "server": "2b2t.org",
  "stash": "stash",
  "player": "Qwerttze",
  "mcVersion": "1.21.11",
  "dimension": "minecraft:overworld",
  "generatedAt": 1767225600000,
  "indexHash": "sha1 hex of the items array",
  "containers": { "known": 120, "total": 130, "stale": 3 },
  "items": [
    {
      "key": "minecraft:diamond_sword#3f2a9c1d",
      "id": "minecraft:diamond_sword",
      "name": "Diamond Sword",
      "englishName": "Diamond Sword",
      "count": 12,
      "maxStack": 1,
      "details": ["Sharpness V", "Unbreaking III"],
      "enchanted": true
    }
  ]
}
```

- `key`: the item id, plus `#` and the first 8 hex digits of the SHA-1 of the canonical component-patch NBT string, but only when the stack has a component patch (CONTAINER and BUNDLE_CONTENTS are dropped for shulkers and bundles first). The key stays the same across sessions, and orders will refer to items by it later.
- `name`: the stack's hover name. It may be a custom name set by a player, so the page must treat it as untrusted text.
- `englishName`: the base item's en_us name, used to look up its icon.
- Shulkers and bundles are counted as their base item, with contents ignored, and their contents are added to the totals as well.
- If two items share a `key`, the server merges them: the counts are added up and the first entry's other fields are kept. `GET /api/v1/index` returns the merged list.
- The server holds at most 64 (server, stash) pairs. A new pair past that limit gets `400` (an offline pair that never sent an index is dropped first to make room).
- Response: `200 {"ok": true}`.

## POST /api/v1/heartbeat

Sent every 15 seconds while the module is on and a stash is loaded.

```json
{ "server": "2b2t.org", "stash": "stash", "player": "Qwerttze", "indexHash": "..." }
```

Response: `200 {"ok": true, "needIndex": false}`. `needIndex` is true when the server doesn't hold an index for that (server, stash) with that hash.

## Website login

The whole website is behind one password. The page's static files (`/`, `/app.js`, `/style.css`, `/dirt.svg`, ...) and three auth endpoints are open; everything else the browser asks for needs a session. The bot API is unchanged: Bearer key only, no session.

- **Password:** stored in `config.properties` as `dashboardPassword=pbkdf2-sha256$600000$<salt b64>$<hash b64>` (PBKDF2WithHmacSHA256, a random 16-byte salt, a 32-byte hash), checked in constant time. The server only ever edits that one line. Deleting the line and restarting resets the password and ends every session.
- **Session:** `Set-Cookie: sm_session=<32 random bytes, base64url>; HttpOnly; SameSite=Strict; Path=/; Max-Age=2592000` (30 days from login). `stash-manager-data/sessions.json` stores only the SHA-256 of each token with its created, last-seen and expiry times (written atomically, owner-only), so logins survive a restart. Expired sessions are pruned.
- **Route guards** (the LAN address and `Host` checks run first, as before):
  - `OPEN`: static files, `GET /api/v1/auth/state`.
  - `CSRF` (the browser-write checks below, no session): `POST /api/v1/auth/setup`, `POST /api/v1/auth/login`.
  - `SESSION`: every browser GET: `/api/v1/stashes`, `/api/v1/index`, `/api/v1/orders`, `/api/v1/link`, `/icon/...`.
  - `BROWSER_WRITE` (the CSRF checks, then a session): every other browser POST.
  - `ADDON_KEY`: unchanged (Bearer).
- Without a session the reply is `401 {"ok": false, "error": "login required"}`. API responses carry `Cache-Control: no-store`, except `GET /api/v1/index`, which keeps its ETag revalidation with `private, no-cache`; icons are `private`.

Endpoints (`/api/v1/auth/...`):

- `GET state` → `{ setupNeeded, setupAllowed, codeRequired, loggedIn, elevated, minLength }` (plus `message` when the setup must be done on the server PC). `setupAllowed` is true only for a loopback client without proxy headers while no password is set. `elevated` says whether this session is in sudo mode.
- `POST setup { password, code }`: first run only, and only from the server PC itself (loopback, and without `X-Forwarded-For`, `Forwarded`, `Via` or `X-Real-IP` headers); anything else gets `403` with "Set the dashboard password on the server PC (open http://localhost:<port>)". `code` is the one-time setup code the server prints at startup while no password is set (`Dashboard setup code: XXXX-XXXX`; case, spaces and dashes don't matter; a wrong code gets `403`). The password needs at least 12 characters and must not be one of about 150 very common passwords (`400`); `409` once a password is set; `500 {"error":"could not save"}` when config.properties can't be written. On success: `200` with the session cookie (in sudo mode).
- `POST login { password }`: `200` with the cookie, `401 "wrong password"`, `409` while no password is set, or `429 { error, retryAfter }` with `Retry-After` (seconds).
- `POST logout`: ends this browser's session and clears the cookie. `POST logout-all`: ends every session (`{ ok, ended }`).
- `POST password { current, next }`: needs the current password (`401` when wrong, and it counts as a failed login); `next` follows the setup rules. Every other session is logged out; this one stays (`{ ok, ended }`). `500 {"error":"could not save"}` when the file can't be written.
- `POST lock`: ends sudo mode for this session now (the header's "Lock now"). `GET state` reports `elevatedUntil` (server time, ms; 0 when off).
- `POST confirm { password }` (sudo mode): puts this session in sudo mode for 15 minutes (`{ ok, elevatedForSeconds }`); a wrong password gets `401` and counts as a failure. Login and setup also start sudo mode. Sudo mode is kept in memory only, so a server restart asks again. Actions that need it answer `403 {"error":"confirm password"}` otherwise: today console commands (and, from phase 4, changes in the `proxy` scope).
- Auth request bodies are capped at 4 KiB (`413`). The JDK server's `sun.net.httpserver.maxReqTime=30` limits how long a request may take to arrive.
- **Rate limit** (login, password change, confirm): after 5 failures from one address (IPv6: per /64), that address is locked out for 1 minute, doubling with each further lockout up to 15 minutes; a success resets it. On top of that, 20 failures from anywhere within 10 minutes pause every attempt for 15 minutes, doubling with each further pause up to 1 hour. The server PC itself (loopback) is exempt from both, so its owner can always log in. Both answer `429` with `Retry-After`.
- On Windows, `config.properties`, `sessions.json` and the data folder get an ACL with only the user the server runs as (POSIX: owner-only permissions); failures are only warned about.

## Browser endpoints (website login required)

- `GET /api/v1/stashes`: `[{ "server", "stash", "player", "online", "lastSeen", "indexedAt", "itemTypes", "itemTotal" }]`. `online` means a heartbeat or index arrived in the last 45 seconds.
- `GET /api/v1/index?server=..&stash=..`: the stored index exactly as it was pushed, plus `"receivedAt"`. Returns `404` if there isn't one.
- `GET /icon/<namespace>/<path>.png?v=<mcVersion>-<token>`: the item icon, always a static PNG. Only ids that appear in a stored index get one; any `name`/`mc` parameters are ignored.
  - `v` is set by the page to the index's Minecraft version plus a source token (today `g1`). Changing the token or the version changes the URL, so the browser doesn't reuse icons it cached under an older scheme. If some stored index has that version, the server renders from that version's game jar; otherwise it uses the version of the first index containing the id.
  - Sources, in order: a 64 px render from the local Minecraft client jar (cached per version in `icons/game-<mcVersion>/`), then the wiki pipeline (Minecraft Wiki, then mcasset.cloud; cached in `icons/<namespace>/`). The `iconSource` setting can limit this to one source. Icons are rendered or fetched for every id of an index as soon as it arrives.
  - Ready: `200`, `Cache-Control: public, max-age=604800`.
  - Waiting on the wiki: `503`, `Retry-After: 2`, `Cache-Control: no-store`, with the placeholder PNG as the body. The request never waits for upstream.
  - Unknown id, or not available from any enabled source: the placeholder PNG with `200`.
- `GET /api/v1/index` sends an `ETag` and answers `If-None-Match` with `304`. JSON responses over 1 KiB are gzip-compressed when the request has `Accept-Encoding: gzip`; the gzip variant of the index has its own ETag (`"<hash>-gz"`) and both carry `Vary: Accept-Encoding`.

## Link addon (website)

- `GET /api/v1/link` (website login required; `apiKey` only in sudo mode, otherwise `apiKeySet: true` and `apiKeyHint`): `{ "apiKey": "...", "urls": ["http://192.168.1.20:8765", ...], "linked": { "player", "server", "stash", "lastSeen" } | null }`. `urls` are the addresses the server listens on (localhost and its LAN IPv4 addresses). `linked` stays `null` until an addon request authenticated with the *current* key arrives; it then holds that request's details.
- `POST /api/v1/link/regenerate` (browser-write guard and sudo mode): makes a new key, saves it to `config.properties`, and returns `{ "ok": true, "apiKey": "<new key>" }`. The old key gets `401` from then on, and `linked` goes back to `null`.

Anyone logged in to the website can read the key this way.

## Browser writes (CSRF guard)

Every state-changing request from the website (`POST /api/v1/link/regenerate`, `/api/v1/rescan`, `/api/v1/stashes/release`, `/api/v1/orders`, `/api/v1/orders/clear`, `/api/v1/orders/<id>/cancel` and `/force-cancel`, the `/api/v1/auth/...` POSTs) must meet all of these, otherwise it gets `403`:

- the method is `POST`;
- the header `X-Stash-Manager: 1` is present. A cross-site page can only send a custom header after a CORS preflight, and the preflight fails: `OPTIONS` always gets `405` and no `Access-Control-*` headers are ever sent;
- `Content-Type` is `application/json` (parameters such as `charset` are allowed);
- if an `Origin` header is present, it is one of:
  - `http://<host>:<port>`, where that host and port equal the request's `Host` header (port 80 when omitted);
  - `https://<name>` (port 443, written or not), where `<name>` is listed in `allowedHosts` in `config.properties` and equals the host name in the `Host` header. Only the names are compared, because an HTTPS reverse proxy (e.g. a Cloudflare Tunnel) forwards the request over plain HTTP with the bare name, so the `Host` port is 80 after normalising. IP addresses and `localhost` are never accepted with `https`;
- if a `Sec-Fetch-Site` header is present, it is `same-origin` or `none`.

The usual access check (LAN address and `Host` allowlist) applies first. Apart from `auth/login` and `auth/setup`, these requests also need a website session (see Website login).

---

# Additions: creative tabs, shulker contents, orders

These are all optional, additive fields within `version: 1`. A server must accept an index without them, and an addon must ignore reply fields it doesn't know.

## Index additions

Each entry in `items[]` gains:

- `tab`: the creative-menu tab the base item belongs to. It is the vanilla tab id path: `building_blocks`, `colored_blocks`, `natural_blocks`, `functional_blocks`, `redstone_blocks`, `tools_and_utilities`, `combat`, `food_and_drinks`, `ingredients`, `spawn_eggs` or `op_blocks`, or `other` when the item isn't in any of them. An item that appears in several tabs gets the first one in that order.
- `tabOrder`: the item's position in that tab's display list (an integer, the first time it appears), so the website can order a tab the way the creative menu does. Items in `other` use `1000000`.

The index gains a top-level `shulkers` array: every **non-empty** shulker box in the stash, from the same containers and `held` items the counts come from. Identical shulkers are grouped together:

```json
"shulkers": [
  {
    "key": "s:3f2a9c1d0b",
    "id": "minecraft:red_shulker_box",
    "name": "Red Shulker Box",
    "count": 4,
    "contents": [ { "slot": 0, "key": "minecraft:diamond", "count": 64 }, { "slot": 5, "key": "minecraft:diamond_sword#3f2a9c1d", "count": 1 } ]
  }
]
```

- `key`: `s:` followed by the first 10 hex digits of the SHA-1 of the shulker's item key and the canonical list of its contents (`slot:itemKey:count` joined). It stays the same across sessions.
- `itemKey`: the shulker item's own key in `items[]` (for example `minecraft:red_shulker_box`, or `minecraft:red_shulker_box#…` for a named shulker).
- `name`: the hover name. A custom name is untrusted text.
- `count`: how many identical shulkers exist.
- `contents[].key` always refers to an entry in `items[]` (an item inside a shulker is counted in `items[]` too). Only non-empty slots are listed. A bundle inside a shulker is listed as the bundle item.

## Orders

An order is a cart of lines, taken from the stash by the bot, packed into as few shulkers as possible, and delivered to the delivery container set in game.

```json
{ "type": "item", "key": "minecraft:diamond", "count": 640 }
{ "type": "shulker", "key": "s:3f2a9c1d0b", "count": 2 }
```

A `shulker` line asks for that many of an existing, already packed shulker group, delivered as it is.

An `item` line whose item is a shulker box (any `minecraft:*shulker_box`) means **empty** shulkers of that kind; packed ones are ordered with `shulker` lines. Its availability is `items[K].count` minus the `count` of every `shulkers[]` group whose `itemKey` is K. For groups without `itemKey` (older indexes) the server matches on `id` instead.

**Availability** of a line is: the index count (for an `item` line, adjusted for shulker boxes as above) or the group `count` (for a `shulker` line), minus the counts of the same type and key in every open order (`pending`, `accepted`, `in_progress`) for that stash. The server doesn't cross-check the two: diamonds inside a packed shulker are also part of `items["minecraft:diamond"].count`, so a `shulker` line and an `item` line for those diamonds can both be reserved.

Order fields: `id` (`o-` followed by 12 random hex digits), `server`, `stash`, `lines`, `note` (at most 200 characters, untrusted), `createdAt`, `updatedAt`, `status`, `phase` (free text from the bot, such as "gathering 3/10"), `progress` (`{ "done": n, "total": m }` or null), `message` (the bot's reason for a failure, or info), `cancelRequested` (bool) and `result` (`{ "shulkers": n, "lines": [{ "key", "delivered" }] }` or null).

`status` goes through:

- `pending`: waiting for the bot.
- `accepted`: the bot has claimed it.
- `in_progress`: it is being worked on.
- `delivered`, `failed` or `cancelled`: finished; one of these is always the last status.

### Browser (access check, plus the browser-write guard on the POSTs)

- `POST /api/v1/orders`: `{ server, stash, lines, note }`. Returns `200 { ok, order }`. Each line is validated against the current index. The key must exist, and the count must be at least 1 and no more than what is available minus what other open orders have reserved (pending, accepted or in progress). Lines with the same type and key are merged. There are at most 200 lines, and each `count` must be a whole number from 1 to 2147483647. A stash can have at most 20 open orders. If validation fails, the reply is `400` with `{ error, line }`, where `line` is the index of the offending line in the request (null when the problem isn't a single line, such as the open-order limit or a missing index).
- `GET /api/v1/orders?server=..&stash=..`: `200 { orders, available }`. `orders` is that stash's orders, newest first (the last 100 are kept; open orders are never pruned). `available` is `{ items: { <key>: n }, shulkers: { <key>: n } }`, what can still be ordered for each key of the current index (availability as above, after open orders). Both are empty when the stash has no index.
- `POST /api/v1/orders/<id>/force-cancel`: for an `accepted` or `in_progress` order whose cancel was requested but which the bot never confirmed. It becomes `cancelled` straight away with the message "cancelled from the website (bot didn't confirm)", which releases its reservations; the bot's later status reports for it get `409`, which the addon treats as done. Returns `200 { ok, order }`; `409` if no cancel was requested, the order is `pending`, or it is already finished; `404` if there is no such order. The page offers it once the cancel has waited 60 seconds (orders carry `cancelRequestedAt`, null for orders from before that field, which then use `updatedAt`) or while the bot is offline.
- `POST /api/v1/orders/clear`: `{ server, stash }`. Removes every finished order (`delivered`, `failed`, `cancelled`) of that stash, from memory and from disk. Open orders (`pending`, `accepted`, `in_progress`) are never removed, since they hold reservations and the bot may be working on them. Returns `200 { ok, removed: n }` (`0` for a stash with no orders).
- `POST /api/v1/orders/<id>/cancel`: when the order is `pending`, it becomes `cancelled` straight away. When it is `accepted` or `in_progress`, `cancelRequested` is set to true and the bot stops at a safe point and reports `cancelled`. Returns `200 { ok, order }`; `409` if the order is already finished, `404` if there is no such order.

### Addon (Bearer key)

- The heartbeat reply gains `"orders": { "pending": n, "cancel": ["o-..."] }`. `pending` is the number of orders waiting for this (server, stash). `cancel` lists the orders the bot holds that have had a cancel requested.
- `POST /api/v1/orders/claim`: `{ server, stash }`. Claims the oldest `pending` order for that stash, which then becomes `accepted`. Returns `200 { ok, order }`, or `204` if there is nothing pending. The bot works on one order at a time.
- `POST /api/v1/orders/<id>/status`: `{ status, phase, progress, message, result }`. The bot may only move its order forward: `accepted` to `in_progress`, and either of those to a finished status. Sending the current status again (`in_progress` to `in_progress`) is how the bot reports a new `phase` or `progress`. Anything else gets `409`, including a `pending` order (not claimed yet) and a finished order, which can't be changed afterwards. Fields that are left out or null keep their previous value. `phase` is cut to 100 characters and `message` to 300 (longer strings are truncated, not rejected, so a finished status always gets through); `result.lines` keeps its first 400 entries and keys are cut to 256 characters. `progress` and `result` must have the shapes above (whole numbers >= 0), and a field of the wrong type still gets `400`. Returns `200 { ok, order }`.
- When the bot reconnects, it can call `POST /api/v1/orders/claim` again. If it already holds an `accepted` or `in_progress` order for that stash, the server returns that order instead of a new one, so the job resumes after a restart.

## Additions: delivery status and order size

- The heartbeat body gains optional fields:
  - `delivery`: `{ "x", "y", "z", "dimension" }` (the delivery container the bot will fill), or `null` when none is set. `x`, `y` and `z` must be whole numbers in int range and `dimension` a string (cut to 100 characters).
  - `orderBlocker`: why the bot can't take orders right now (e.g. `"no delivery box"`, `"sorter off"`, `"accept-orders off"`, `"busy: building a kit"`), or `null` when it can. Longer strings are cut to 100 characters; an empty string counts as `null`.

  A heartbeat whose `delivery` or `orderBlocker` has the wrong type gets `400`. The server keeps the latest values in memory (not across restarts) and returns them in each `GET /api/v1/stashes` entry as `delivery` and `orderBlocker`. A heartbeat that leaves a field out keeps its stored value; an explicit `null` clears it. A stash that never sent them returns `null` for both.
- An order may need at most **54 shulkers**, one double chest. This is checked on `POST /api/v1/orders` (after the per-line checks) by counting `ceil(count / maxStack)` slots for each item line, grouping them into shulkers of 27 slots, then adding one shulker per unit of every `shulker` line and every empty-shulker line. `maxStack` comes from the index (64 when missing). A larger order gets `400 { error, line: null }`.

  The web page plans the cart the same way the addon packs orders (`Order.pack`, used by `OrderRunner`), and refuses additions past 54. The addon's limit is also 54. The packing, in order:

  1. Shulker lines: one unit per shulker, in line order, delivered as they are.
  2. Empty-shulker lines (an `item` line for a shulker box): one unit per shulker, in line order.
  3. Every other item line becomes slot loads: `floor(count / maxStack)` loads of `maxStack`, plus one load with the remainder if there is one.
  4. The loads are grouped by item key, visiting keys in ascending order (Java `String.compareTo`, UTF-16 code units). The groups are stable-sorted by number of loads, most first, and the loads in each group by count, largest first.
  5. The loads are concatenated and fill 27-slot shulkers in sequence, so the bins are `ceil(loads / 27)` and a load's slot is its position in its bin. Items may share a shulker.

  The units are delivered in the order 1, 2, 5. The page labels this as the planned layout: the bot plans again from what it can actually reach, so it may deliver less.

## Additions: manual rescan

The website can ask the bot to re-read every container in the stash. This is for setting up a stash that is already sorted: run the sorter with `sort-items` off so the bot only indexes, then use it to pick and deliver orders.

- **Browser:** `POST /api/v1/rescan` with body `{ server, stash }`, behind the browser-write guard. The server stores a new rescan request id for that stash (an increasing integer, kept in memory; the latest one wins) and replies `200 { ok, rescanId }`. Ids start from the current Unix time in seconds, so they keep increasing across server restarts and still fit a 32-bit int. If the server knows no such stash (it has had neither an index nor a heartbeat for it), the reply is `404`; a known stash whose bot is offline gets the request when it reconnects.
- **Heartbeat reply:** gains `"rescan": <id>` while there is a request the bot hasn't acknowledged yet, and leaves the field out otherwise.
- **Heartbeat body:** the addon sends `"rescanDone": <id>` once it has started the rescan. The server then stops sending that id. A request is acknowledged by any `rescanDone` that is at least its id. `rescanDone` must be a whole number >= 0 (or null), and `rescanBlocked` a string (cut to 100 characters) or null (empty counts as null); a wrong type gets `400`. A heartbeat that leaves `rescanBlocked` out keeps the stored value.
- **What the bot does:** it marks every container it knows as stale and restarts exploring the area, so new containers inside the perimeter are found too. Then it re-reads them all, nearest first, as stash-sorter does when it is on, even with `sort-items` off. Contents already in the index stay visible until each container is read again. Progress shows up through the index's `containers.stale` count, which goes down to 0.
- If the sorter is off, the bot still acknowledges the request, and sends `orderBlocker` / `rescanBlocked: "sorter off"`. The containers start being read when the sorter is turned on.
- `GET /api/v1/stashes` entries gain `rescanPending` (the unacknowledged id or `null`), `rescanBlocked` (a string or `null`) and `containers` (`{ known, total, stale }` from the latest index, or `null` without one).

## Additions: multiple bots, stash ownership

Several bots, whether Meteor addons or proxy plugins, can share one server and one API key. Each (server, stash) pair is its own stash with its own index, orders, delivery container and rescans. To keep two bots from overwriting each other's stash by mistake:

- **Owner:** a (server, stash) pair belongs to the player that first pushed an index or heartbeat for it. The owner is stored, kept across server restarts, and returned as `owner` in `GET /api/v1/stashes`.
- **Other players:** an index, heartbeat, claim or status request for that pair from any other `player` gets `409 { "error": "stash name in use", "owner": "<player>" }`. The bot should show "stash name '<stash>' is used by <owner>: pick another stash name". There is one exception: if the owner hasn't been seen for 7 days, the new player takes the stash over, and the takeover is logged.
- **Release:** `POST /api/v1/stashes/release { server, stash }` (browser-write guard) clears the owner, so another bot can take the stash over.
- **Orders:** claims and status updates are accepted only from the owner.

Server details:

- Player names are compared case-insensitively. Owners are saved in `stash-manager-data/owners.json` with the time each was last seen. Stashes stored before ownership existed are adopted by the player in their stored index.
- An index or heartbeat from the owner (or for a pair without an owner) is accepted, and a pair without an owner becomes that player's. A request without a `player` can't be checked and is let through.
- Claims (`POST /api/v1/orders/claim`) and status reports (`POST /api/v1/orders/<id>/status`) carry the bot's `player` in the body; from anyone but the owner they get the same `409`. They never take a stash over. Requests without `player` (older addons) are let through.
- The 7-day takeover only happens through an index or heartbeat, and is printed to the server log.
- `POST /api/v1/stashes/release` replies `200 { ok, released }` (`released` false when there was no owner) and `404` when the server knows nothing about the pair. If the old owner keeps running with the same stash name, its next heartbeat takes the stash back.
- The server keeps the last refused player per pair in memory. `GET /api/v1/stashes` entries gain `owner` (a string or `null`), `conflict` (`{ player, at }` or `null`) and `orders` (`{ open: n, active: { id, status, phase, progress } | null }`, for the website's bot overview).
- `GET /api/v1/link` gains `bots`: every (server, stash) that used the current key, `[{ player, server, stash, lastSeen }]`, most recent first. `linked` still holds the most recent one.

---

# Dashboard (proxy bots): settings and console

Phases 2 and 3 of `DASHBOARD-DESIGN.md`. A proxy bot (the Qwerte Stash plugin's `DashboardLink`) opens every connection; the server never connects to it. Bot endpoints use the Bearer API key; browser endpoints need a website session (writes: the browser-write guard too). The server keeps all of this in memory: bots say hello again after a restart.

## Bot side (Bearer key)

- `POST /api/v1/bot/hello { botId, player?, plugin: {id, version}, proxy: {version, mc}, caps: [], scopes: [] }` → `200 { ok, protocol: 1 }`. `botId`: 1-64 letters, digits, `-` or `_` (the plugin's `managerBotId`). Caps: `settings`, `console`, `proxy`. `scopes` (at most 50) are the settings scopes the bot can show: `plugin` (its own settings), `proxy` (the whole AquariusProxy config) and `plugin:<id>` (another plugin's config; `<id>` is 1-56 letters, digits, `_`, `.` or `-`). At most 64 bots (`503` when full of online ones). A hello starts a new session: the server forgets what it asked for.
- `POST /api/v1/bot/state { botId, scope, schemaHash, valuesHash, schema?, values }` → `200 { ok, need? }`.
  - A schema is stored whenever it comes, asked for or not (the plugin sends it on its first push of a session and when its hash changes). When `schemaHash` differs from the stored schema and no schema came, the reply's `need` asks for it: `[{ scope, what: "schema" }]`. The reply lists the needs of every scope, not just the pushed one.
  - The bot pushes only `plugin` on its own. For `proxy` and `plugin:<id>`, the server asks (a `need` in `bot/next` or in a `bot/state` reply) once a browser opens that scope and no schema is stored; the bot then keeps its values flowing while watched.
  - **Secrets never get stored:** values of nodes marked `secret`, of every path matching `(?i)token|passw|pass|pwd|secret|api_?key|key|auth|bearer|jwt|session|cookie|credential|cred|private|licen[sc]e|dsn|salt|signature|webhook|topic`, and every string value that is a URL with user info (`^[a-z][a-z0-9+.-]*://[^/@\s]+@`, whose node is then marked secret) are dropped before storing, even if a buggy bot sends them. A leaf whose path matches but isn't marked `secret` is turned into one (`secret: true, readOnly: true, reason: "secret"`), so it can't be edited either.
  - Checked: scope `[a-z0-9_.:-]{1,64}`; hashes up to 128 characters; `values` an object of at most 5000 entries; schema nodes `{path, type, label?, help?, reason?, section?, min?, max?, options?, nullable?, readOnly?, secret?, set?, children?}` with a type from `group, bool, int, long, double, string, enum, stringList`, at most 5000 nodes and 12 levels. A bad body gets `400`; a bot that hasn't said hello `409 "say hello first"` (the plugin then says hello again).
- `GET /api/v1/bot/next?botId=` waits up to 25 s. → `200 { jobs, need, watching }` or `204`.
  - `jobs`: `{ id, type: "set", scope, path, value, expect }` or `{ id, type: "command", command }`. Handing a job out marks it `sent`.
  - It answers at once when there are jobs, when `watching` changed (a browser opened or left this bot's settings or console; it counts for 10 s after the page's last request), or when the server needs something (`need`, repeated at most every 10 s).
  - At most 8 bots wait at once; the 9th gets `429` (`Retry-After: 3`) and the plugin polls every 3 s. The server's pool has 32 threads.
- `POST /api/v1/bot/results { botId, results: [{ id, ok, value?, error?, sensitive?, more?, output? }] }` → `200 { ok }`. At most 50 results per call.
  - A `set` result finishes its job (`done` or `error`; `value` is the read-back value, `error` e.g. `"conflict"`).
  - A `command` result: the first one finishes the job and starts its console output; later ones with `more: true` add more output to the same entry, in order (at most 50 parts, 256 KiB).
  - `output` is reduced to `{ title?, description?, color?, fields: [{ name, value }], lines: [] }` with capped strings. `color` must be `error`, `success`, `primary`, `queue` or `#rrggbb`, else it is dropped.
  - `sensitive: true`: the entry's command becomes `"(hidden)"` and only the output's `title` and `color` are kept. Commands that obviously carry a secret (starting with `auth`, or containing `manager key`, `manager url`, `passw`, `token`, `secret`, `api_key`, `webhook`, `cookie`, `credential`) are hidden from the moment they are queued.

## Browser side (website login)

- `GET /api/v1/bots` → `[{ botId, player, online, lastSeen, plugin, proxy, caps, scopes, stashes: [{server, stash}] }]`. `scopes`: the declared ones plus any the bot pushed. A bot is online while it waits on `bot/next` or polled less than 35 s ago. `stashes` are the stashes this player owns (or last used).
- `GET /api/v1/bots/{id}/settings?scope=plugin|proxy|plugin:<id>` (`proxy` and `plugin:<id>` need sudo mode: `403 {"error":"confirm password"}`) → `{ scope, schemaHash, valuesHash, schema, values, receivedAt, online, lastSeen }` with an `ETag` (the hashes plus the online state); `If-None-Match` gives `304`. `404` until the bot has sent that scope (`{ loading: true }` while the server is asking an online bot for it; the page polls every second then), or for an unknown bot.
- `POST /api/v1/bots/{id}/settings { scope, changes: [{ path, value, expect }] }` → `{ ok, jobs: [{ id, path }] }`.
  - Every change is checked against the stored schema first: the setting must exist and not be a group, a secret or read-only; `bool` true/false; `int`/`long` whole numbers within `min`/`max` (else the type's range); `double` finite within `min`/`max`; `string` at most 1000 characters; `enum` one of `options`; `stringList` at most 500 strings of at most 1000 characters; `null` only when `nullable`. A failure gets `400 { error, path }` and nothing is queued. 1-100 changes, each setting once.
  - `409 {"error":"bot offline"}` when the bot is offline: edits are refused, not queued. `409` also before the bot has sent settings.
  - `proxy` and `plugin:<id>` need the `proxy` cap (`400`) and sudo mode (`403 {"error":"confirm password"}`); `plugin` (the stash settings) needs neither, like orders.
  - `expect` is the value the page showed; the bot answers `conflict` (with the current value) when the setting changed meanwhile.
- `GET /api/v1/bots/{id}/jobs?ids=a,b` → `[{ id, status, value?, error? }]`. Status: `queued`, `sent`, `done`, `error`, `expired` (not picked up within 10 s), `unknown` (picked up but not answered within 60 s, or an id the server doesn't hold).
- `POST /api/v1/bots/{id}/console { command }` → `{ ok, id }`. At most 500 characters, no control characters; needs sudo mode (`403 {"error":"confirm password"}`); `409` when the bot is offline; `400` when the bot has no `console` cap.
- `GET /api/v1/bots/{id}/console?after=<rev>` (needs sudo mode: `403 {"error":"confirm password"}`) → `{ entries, rev, first, online }`. The server keeps the last 200 entries per bot. Every change to an entry (queued, sent, finished, more output) gives it a new `rev`; `after` returns the entries changed since that revision, so the page polls with the last `rev` it saw. Entry: `{ seq, rev, id, command, hidden, at, status, error?, output, outputs }` (`output` is the first part, `outputs` all of them). `first` is the oldest `seq` still kept.
- Opening a bot's settings, jobs or console marks it watched for 10 s, so the plugin re-checks its values every 3 s.

## Signed requests (HMAC), replacing the Bearer key on the wire

The API key must never be sent over the network: the connection is plain HTTP and can be sniffed. Instead, every bot request is signed with the key, and every response the server sends to a signed request is signed too. That way a spoofed or man-in-the-middle server can't inject jobs, and a captured request can't be replayed.

The signing key is `K = HMAC-SHA256(key = UTF-8(apiKey), msg = "stash-manager v1")`, as raw bytes. The `hex()` used below is lowercase hex.

### Request (bot → server)

Header: `X-SM-Auth: v1 <botId-or-"-"> <ts> <nonce> <sig>`

- `ts` is Unix time in milliseconds, as a decimal number. `nonce` is 16 random bytes, hex-encoded.
- `sig = hex(HMAC-SHA256(K, METHOD + "\n" + PATH_WITH_QUERY + "\n" + ts + "\n" + nonce + "\n" + hex(SHA-256(body))))`.
  - `body` is the exact bytes sent. For a GET the body is empty, so its hash is the SHA-256 of zero bytes.
  - `PATH_WITH_QUERY` is the raw request path as sent, for example `/api/v1/bot/next?botId=abc`.
- The server rejects the request with `401 {"error":"bad signature"}` if any of these hold:
  - `|now - ts| > 60 s`;
  - the nonce has been seen in the last 5 minutes (the server keeps a cache of recent nonces);
  - the signature doesn't verify, compared in constant time.
- The server tries the current key. After a key regenerate it also tries the previous key for 10 minutes, but only for requests that aren't dashboard jobs.

### Response (server → bot), for every request that had a valid `X-SM-Auth`

Header: `X-SM-Sig: v1 <ts> <sig>`

- `sig = hex(HMAC-SHA256(K, "resp\n" + requestNonce + "\n" + status + "\n" + ts + "\n" + hex(SHA-256(responseBody))))`.
  - `responseBody` is the exact bytes sent. For a 204 the body is empty.
  - If the response is gzip-compressed, the signature covers the uncompressed body.
- The bot checks that the response comes back with the nonce it sent, that `|now - ts| <= 60 s`, and that the signature is valid. If any check fails it **discards the response** and doesn't run any jobs from it. It also shows "server signature invalid" as an error and backs off before trying again.

### Bearer

The old `Authorization: Bearer <key>` header is still accepted for index, heartbeat and order requests only while the server config has `allowBearer=true`. The default is **false** once this version ships, and `bot/*` (dashboard) endpoints never accept Bearer. The README documents that you must update every bot before turning Bearer off.

### Local opt-in on the proxy (plugin side)

The proxy executes console commands and proxy/other-plugin setting changes only while the plugin's `dashboardConsole` / `dashboardProxy` settings are on. Both default to off. They can only be changed from the proxy's own terminal, not from the dashboard and not through a `multi` command. The `caps` sent in `hello` include `console` / `proxy` only while those settings are on.

### Test vector

Every implementation (server, plugin, addon) must reproduce these values (lowercase hex):

| Input | Value |
| --- | --- |
| apiKey | `test-key` |
| K = HMAC-SHA256(`test-key`, `stash-manager v1`) | `d4e20f4ea5ec22c2b66d810c6c95cddc10ca254d4ead1c5904bfdf3c69410706` |
| request: method, path, ts, nonce | `POST`, `/api/v1/heartbeat`, `1700000000000`, `00112233445566778899aabbccddeeff` |
| body | `{"a":1}` (7 bytes) |
| hex(SHA-256(body)) | `015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862` |
| request sig | `7d2f41009d9f0c6c61216ee00811bc643c646652c3d96b36fb89df09bc9356f1` |
| response: nonce as above, status `200`, ts `1700000000123`, body `{"ok":true}` | `6ca0727bb6e3f5d8a8a11285906bb2078e6e899efe39e86cfc0926b3bb2a9dce` |
| hex(SHA-256(empty body)) (GET, 204) | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |

So the request header is `X-SM-Auth: v1 - 1700000000000 00112233445566778899aabbccddeeff 7d2f41009d9f0c6c61216ee00811bc643c646652c3d96b36fb89df09bc9356f1`.

### Server notes

- Every response to a request with a valid `X-SM-Auth` is signed, whatever its status (200, 204, 4xx, 5xx). Requests rejected before or at the signature check (403 access check, 401 bad signature, 413 too large, an unknown route) get unsigned replies; bots accept unsigned replies only for 401, 403 and 5xx.
- The botId in the header is **not** part of the signed message (only method, path with query, ts, nonce and the body hash are); it is informational and isn't checked against the body. `-` for non-dashboard requests.
- The header's format, timestamp and nonce syntax are checked before the body is read, so unsigned junk never ties up a thread reading a body. Bodies are capped at 1 MiB on bot/* and 8 MiB on index.
- `allowBearer` is a `config.properties` setting (default `false`, added with a comment to existing files). With `false`, a Bearer request gets `401 "Bearer is not accepted: update the bot (requests must be signed)"`; bot/* never accepts Bearer. The server logs at startup which mode it is in.
- The nonce cache only holds nonces of correctly signed requests (at most 200,000, each for 5 minutes), so it can't be filled by an attacker without the key.

## Additions: activity (what the proxy is doing)

The bot pushes a live snapshot of everything it is doing. The website shows it in the **Activity** tab.

### Bot → server (signed, `bot/*` route)

`POST /api/v1/bot/activity {botId, activity}`. The bot sends it every 2 s while `watching` (set when a browser has that bot's Activity tab open; it arrives in the `bot/next` reply), every 30 s otherwise, and right away when the connection state or an item's title changes. The body is at most 256 KiB.

```json
{
  "at": 1767225600000,
  "connection": {
    "state": "online|queue|connecting|offline|disconnected",
    "server": "2b2t.org",
    "queuePosition": 123,
    "queueEta": "12m",
    "dimension": "minecraft:overworld",
    "x": 0, "y": 64, "z": 0,
    "health": 20, "food": 20,
    "ping": 85,
    "uptimeSeconds": 3600,
    "spectators": 0,
    "controllingPlayer": "name|null"
  },
  "current": [
    { "source": "Stash Sorter", "kind": "plugin|module|process", "title": "Sorting: depositing into \"Ores\"", "detail": "next: compact diamonds", "progress": { "done": 3, "total": 10 }, "since": 1767225500000, "level": "info|warn|error" }
  ],
  "modules": [ { "name": "AntiAFK", "plugin": "aquarius|<pluginId>", "enabled": true, "status": "optional short text" } ],
  "plugins": [ { "id": "qwerte-stash", "version": "1.5.0", "name": "StashSorter" } ],
  "events": [ { "at": 1767225590000, "source": "Pearl Loader", "level": "info|warn|error", "text": "Loaded pearl for Qwerttze" } ]
}
```

- `current` has one item per thing that is actively working. Examples: the stash sorter's job and status line, the active stash order (phase and progress), the stash manager's link status, the pathfinder's active process and goal, the pearl loader's current request or queue, AutoReconnect or queue waiting, and any enabled module with a meaningful state. Idle sources are left out. Every string is capped at 300 characters.
- `modules` lists every proxy module (from AquariusProxy and from plugins) and whether it is enabled. It has at most 300 entries.
- `events` holds only the entries added since the last push that the server acknowledged. Each push sends at most 100. Sources include the bot's own alerts and notable state changes: connected, disconnected, queue done, an order finished, a pearl loaded, death, and so on.
- No secrets go anywhere in the snapshot. Coordinates are sent; the dashboard is behind the login.

Reply: `{ok}`. The `bot/next` reply gains `watchingActivity: bool`.

### Browser (session)

- `GET /api/v1/bots/{id}/activity` returns `{activity, receivedAt, events:[…last 500, newest first…]}`. The server keeps the 500 most recent events per bot in memory. Fetching this marks the Activity tab as watched for 10 s.
- The Activity tab is shown for bots whose `caps` include `"activity"`.
