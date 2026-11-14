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
- `damage`, `maxDamage` (optional, plugin 1.9.0 / addon 0.4.0-dev.5): sent only for a damageable stack with damage > 0. The website draws the game's durability bar from them (width round(13 × (maxDamage − damage) / maxDamage) of 13, green to red) and shows "Durability: left / max" in the tooltip. Damaged stacks already have their own `key` (the damage is part of the component patch).
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
  - `SESSION`: the browser GETs, for example `/api/v1/stashes`, `/api/v1/index`, `/api/v1/orders`, `/api/v1/link`, `/icon/...`; the others (`/api/v1/delivery`, `/api/v1/bots`, the planner, the viewer, `/api/v1/users`, `/api/v1/audit`) are in their sections below.
  - `BROWSER_WRITE` (the CSRF checks, then a session): every other browser POST.
  - `ADDON_KEY`: unchanged (Bearer).
- Without a session the reply is `401 {"ok": false, "error": "login required"}`. API responses carry `Cache-Control: no-store`, except `GET /api/v1/index`, which keeps its ETag revalidation with `private, no-cache`; icons are `private`.

Endpoints (`/api/v1/auth/...`):

- `GET state` → `{ setupNeeded, setupAllowed, codeRequired, loggedIn, elevated, minLength }` (plus `message` when the setup must be done on the server PC). `setupAllowed` is true only for a loopback client without proxy headers while no password is set. `elevated` says whether this session is in sudo mode.
- `POST setup { password, code }`: first run only, and only from the server PC itself (loopback, and without `X-Forwarded-For`, `Forwarded`, `Via`, `X-Real-IP` or `CF-Connecting-IP` headers); anything else gets `403` with "Set the dashboard password on the server PC (open http://localhost:<port>)". `code` is the one-time setup code the server prints at startup while no password is set (`Dashboard setup code: XXXX-XXXX`; case, spaces and dashes don't matter; a wrong code gets `403`). The password needs at least 12 characters and must not be one of about 150 very common passwords (`400`); `409` once a password is set; `500 {"error":"could not save"}` when config.properties can't be written. On success: `200` with the session cookie (in sudo mode).
- `POST login { username?, password }`: `200 { ok, mustChange }` with the cookie, `401 "wrong username or password"` (the same for an unknown name, which still costs a full hash check), `403 "this account is disabled"` (only after the right password), `409` while no password is set, or `429 { error, retryAfter }` with `Retry-After` (seconds). A blank or missing `username`, or the owner's name, means the owner. See "Additions: website users".
- `POST logout`: ends this browser's session and clears the cookie. `POST logout-all`: the owner ends every session of every user; a sub-user ends their own (`{ ok, ended }`).
- `POST password { current, next }`: needs the current password (`401` when wrong, and it counts as a failed login); `next` follows the setup rules. Every other session is logged out; this one stays (`{ ok, ended }`). `500 {"error":"could not save"}` when the file can't be written.
- `POST lock`: ends sudo mode for this session now (the header's "Lock now"). `GET state` reports `elevatedUntil` (server time, ms; 0 when off).
- `POST confirm { password }` (sudo mode): puts this session in sudo mode for 15 minutes (`{ ok, elevatedForSeconds }`); a wrong password gets `401` and counts as a failure. Login and setup also start sudo mode. Sudo mode is kept in memory only, so a server restart asks again. Actions that need it answer `403 {"error":"confirm password"}` otherwise: today console commands (and, from phase 4, changes in the `proxy` scope).
- Auth request bodies are capped at 4 KiB (`413`). The JDK server's `sun.net.httpserver.maxReqTime=30` limits how long a request may take to arrive.
- **Rate limit** (login, password change, confirm): after 5 failures from one address (IPv6: per /64), that address is locked out for 1 minute, doubling with each further lockout up to 15 minutes; a success resets it. On top of that, 20 failures from anywhere within 10 minutes pause every attempt for 15 minutes, doubling with each further pause up to 1 hour. The server PC itself (loopback) is exempt from both, so its owner can always log in. Both answer `429` with `Retry-After`.
- On Windows, `config.properties`, `sessions.json` and the data folder get an ACL with only the user the server runs as (POSIX: owner-only permissions); failures are only warned about.

## Additions: website users

The website has one **owner** (the `dashboardPassword=` line, as before) and up to 50 **sub-users** with their own passwords and permissions. Nothing changes for bots (signed requests, API key).

- **Storage:** `stash-manager-data/users.json` (written atomically, owner-only): `{ version: 1, ownerName, users: [{ id: "u-<12 hex>", name, hash, perms, disabled, mustChange, createdAt, lastLoginAt, passwordChangedAt }] }`. Hashes are the same PBKDF2 format as the owner's. A users.json that can't be read stops the server at startup (it never starts without the sub-users silently). Missing file: only the owner.
- **Migration:** an existing setup needs nothing. Sessions in `sessions.json` gain a `user` field; sessions without one (made by an older server) are the owner's. The owner's name defaults to `owner`.
- **Names:** 1-32 characters `[A-Za-z0-9_.-]`, unique ignoring case, never the owner's name.
- **Sessions:** per user. `GET /api/v1/auth/state` adds `user: { id ("owner" or "u-..."), name, owner, mustChange, perms }` when logged in. Every request reads the user afresh, so permission changes apply at once; a disabled or deleted user's sessions end (401). Disabling, a password reset by the owner, "log out" by the owner and deleting end that user's sessions. `password` and `confirm` use the caller's own password; `password` also refuses the current password as the new one and clears `mustChange`.
- **Must change password:** a user created or reset with `mustChange` (the default) can only use `/api/v1/auth/*` until they change it; everything else answers `403 { ok: false, error: "change your password first", mustChange: true }`.
- **Permissions** (sub-users; the owner has all):
  - `view`: stash list, index, item icons, order list.
  - `order`: place orders; cancel, retry and force-cancel the user's own orders (orders with no `placedBy` are the owner's). `plannerOrder` covers the user's own orders the same way (retry included), but doesn't place orders here.
  - `ordersAll`: cancel, retry (also needs `order`) and force-cancel any order; Clear finished.
  - `activity`: the Bots list, Activity, job status and reading plugin settings.
  - `settings`: change plugin settings (`scope: "plugin"`).
  - `proxy`: `none`, `read` or `change` for the proxy's and other plugins' settings (plus Admin unlocked, as before).
  - `console`: `none`, `list` or `full` (plus Admin unlocked). `consoleAllow` lists command prefixes for `list`: a command is allowed when, lowercased and with whitespace collapsed, it equals a prefix or starts with prefix + space; commands with `;`, `&`, `|`, `$`, a backtick or a line break are refused in `list` mode. Reading the console needs `list` or `full`: a `list` user sees only the entries they sent, `full` (and the owner) sees everyone's. In `list` mode the whole command must also be plain ASCII from `A-Z a-z 0-9 space _ : . , @ / = + # ~ -`.
  - `rescan`: rescan a stash.
  - `link`: the Link addon panel (linked bots filtered by stash scope) and releasing stash ownership. The API key itself (`apiKey`) and `POST /api/v1/link/regenerate` are the owner's only: the key lets a bot do anything a bot can, past every user's limits. Sub-users get `apiKeyOwnerOnly: true` instead.
  - `planner`: the Build Planner: upload schematics and see their material list (see "Additions: build planner").
  - `plannerOrder`: order a build plan's materials. It does not need `order`. Like `order`, it also covers cancelling, retrying and force-cancelling the user's own orders.
  - `viewer`: the live viewer ("Additions: live viewer").
  - `streamer`: **force streamer mode**, a restriction (off by default and in every preset; the owner can never be forced). The server never sends this user positions, player names, server addresses, chat or the bots' free text; see the table below. Changes are audited with `user.update` ("forced streamer mode") and apply on the user's next request (an open live viewer stream ends with `bye {revoked}` within 5 s and reconnects redacted). `auth/state` shows it as `user.perms.streamer`; the page then shows streamer mode as on and locked. The page's blur is only a second layer: the redaction happens on the server.
  - `stashes`: `null` (all) or `[{ server, stash }]`; `bots`: `null` (all) or `[botId]`. Lists are filtered, and anything outside the scope answers `404`, as if it didn't exist.
  - Implied when saved: `plannerOrder` turns on `planner`; `order`, `ordersAll` or `planner` turn on `view`; `settings`, `proxy` other than `none` or `console` other than `none` turn on `activity`; so does `viewer`.
  - User management and the audit log are the owner's only.
  - Every logged-in route declares the permission it needs in the router (registration fails without one); handlers check the finer rules. A missing permission answers `403 { ok: false, error: "you don't have permission to ...", permission: "<key>" }`.
- **Orders** gain `placedBy: { id, name } | null` (also in the bot's claim; bots ignore it). The name is kept as it was when the order was placed. A retried order belongs to whoever pressed Retry (the audit log records "old as new"). An order can be placed again only once: afterwards it carries `retriedAs: "<new id>"`, Retry answers `409`, and the website hides its Retry button. The Build Planner's "Order part again" counts as placing it again too, and a part whose failed order was retried counts as ordered.
- **Scope details:** stash scope filters stashes, indexes, orders, rescan, release and the Link panel's linked-bot list. It doesn't hide bots or their Activity (a bot isn't tied to one stash); use bot scope for that. Each user can hold at most 20 sessions; a new login past that ends that user's least recently used one.
- **Owner only** (`403` otherwise). The POSTs also need Admin unlocked (`403 "confirm password"`); bodies up to 64 KiB.
  - `GET /api/v1/users` → `{ owner: { name, sessions }, users: [{ id, name, disabled, mustChange, perms, createdAt, lastLoginAt, sessions }], presets: { viewer, orderer, operator }, maxUsers, minLength }`. Hashes are never sent.
  - `POST /api/v1/users { name, password, perms? | preset?, mustChange? (true) }` → `{ ok, user }`. Without perms or preset: `viewer`.
  - `POST /api/v1/users/{id} { name?, perms? | preset?, disabled? }` → `{ ok, user, ended }`. `perms` is the whole object (a missing key is off). Both perms and preset, a preset that isn't one of the presets, or nothing to change: `400`.
  - `POST /api/v1/users/{id}/password { password, mustChange? (true) }`, `.../logout`, `.../delete` → `{ ok, ended }`.
  - `POST /api/v1/users/owner { name }`: renames the owner.
  - `GET /api/v1/audit?limit=200` → `{ entries: [{ at, userId, user, action, detail, address }] }`, newest first. The newest 2000 are kept in `audit.json` (owner-only). Actions: `setup`, `login`, `login.failed`, `logout-all`, `password.change`, `user.create`, `user.update`, `user.password`, `user.logout`, `user.delete`, `owner.rename`, `console` (a command that looks sensitive is logged as `(hidden)`), `settings.change`, `apikey.regenerate`, `stash.release`, `order.cancel`, `order.force-cancel`, `order.retry`, `orders.clear`, `planner.upload`, `planner.order`. Order placement is recorded on the order (`placedBy`).
- **Presets:** `viewer` = view; `orderer` = view, order, planner, plannerOrder; `operator` = view, order, ordersAll, activity, settings, rescan, proxy read, planner, plannerOrder, viewer. Users saved before the planner existed have neither planner permission until the owner turns them on, and users saved before the live viewer don't have `viewer`.
- **Credential check per session:** each session stores a fingerprint of the password hash it was made with (`cred` in sessions.json). A session whose password has changed since ends on its next request, even one created by a login that was in flight while the password changed. Sessions from older servers (no fingerprint) are accepted for the owner only.
- **Rate limits:** failures count per address and account (5 lock that pair, as before), and per address across all accounts (10 lock the address; a success doesn't reset this count). The global pause is unchanged. There is no lockout per account across addresses, so nobody can lock the owner out by guessing at their name from elsewhere. Password confirm and change count the same way. The server's own login doesn't depend on any reverse proxy in front of it.
- **Behind a reverse proxy or tunnel:** set `trustedProxies=` in config.properties to the proxy's IP address(es) (for example the machine running cloudflared). For requests from exactly those addresses, the client address for the rate limit and the audit log comes from the last `X-Forwarded-For` entry (the one the proxy added; Cloudflare and cloudflared put the real client there). Otherwise every tunnelled client shares the proxy's address and its limits. Headers from other addresses are never trusted. "From the server PC" (setup, the loopback exemption) now also needs a Host header of `localhost`, `127.x.x.x` or `[::1]`, no proxy header (`CF-Connecting-IP` included), and a socket address that is not in `trustedProxies`. A reverse proxy must add `X-Forwarded-For`.
- **Forced streamer mode, what the server leaves out** (`Redact` and `ServerIds`, applied when the user's `perms.streamer` is true; the owner and other users get the full data).
  - **Opaque ids.** A forced user never gets a server address or a bot id. Every `"server"` value in every JSON answer (stashes, index, orders, bots' stash refs, link, delivery, planner) is replaced by `sid = base64url(HMAC-SHA256(secret, "server:" + address))[:12]`, and every `"botId"` by `"b" + base64url(HMAC-SHA256(secret, "bot:" + id))[:12]` (also the stream's `hello.botId`). The secret is generated once per install (`stash-manager-data/streamer-ids.key`, owner-only). Requests from a forced user must use these ids (query, body and `/bots/{id}/…` paths): they are mapped back, and a raw address or bot id answers `404`, so it can't be used to test guesses. `serverLabel` ("Server 1", …) is numbered over the stashes the user can see.
  - **Bot free text** is always `"(hidden)"` when present: `orderBlocker`, `rescanBlocked`, `deliveryStorage.blocked`, `orders.active.phase`, order `phase` and `message`, activity titles and event lines. Stash names stay (they are the user's own configuration).

  | Endpoint | For a forced user |
  |---|---|
  | `GET /api/v1/auth/state` | The user's own `perms.stashes[].server` → sid and `perms.bots[]` → bid. |
  | `GET /api/v1/bots` (also) | `plugin` → `{}`, `scopes` → `[]`. Live viewer: `world.minY`/`height` are fixed at -64/384, so they don't tell the dimension (the terrain colours still can). |
  | `GET /api/v1/stashes` | `server` → sid; `delivery` → null; `player`, `owner`, `conflict.player` → null; bot free text hidden; `serverLabel` for display. |
  | `GET /api/v1/index` | `server` → sid; `player` and `dimension` removed; item `name` → its English name, `details` → `[]`; shulker `name` → its item's name ("Red Shulker Box"). No ETag/304 for this form. |
  | `GET /api/v1/orders`, and the order in place/cancel/force-cancel/retry replies | `server` → sid; other users' `placedBy.name` → `"another user"` and their shulker `names` → null; `phase` and `message` → `"(hidden)"`. |
  | `GET /api/v1/bots` | `botId` → bid; `player` → null, `proxy` → `{}`, `summary` → null; stash refs' `server` → sid. |
  | `GET /api/v1/bots/{bid}/activity` | only `connection.{state, queuePosition, health, food, ping, uptimeSeconds, spectators}`; `current[]` keeps `kind`, `progress`, `since`, `level`, `source` (a known plugin subsystem, else `"(hidden)"`) and `title: "(hidden)"`; `modules` and `plugins` → `[]`; `events[]` keep `at`, `level`, `source` (known or `"(hidden)"`) and `text: "(hidden)"`. |
  | `GET/POST /api/v1/bots/{bid}/settings` (both scopes), `GET /api/v1/bots/{bid}/jobs`, `GET/POST /api/v1/bots/{bid}/console` | `403 {permission: "streamer"}`, whatever the user's other permissions. |
  | `GET /api/v1/link` | `urls` → `[]`; players → null; `server` → sid. (The API key stays owner-only.) |
  | `GET /api/v1/delivery` | `server` → sid; `player` → null; item `name` → named after the item id. |
  | Build Planner | `server` → sid in every answer (the plan's stash, ordered parts). |
  | Live viewer stream (`/bots/{bid}/viewer/stream`) | every x/z rebased by a secret per-stream offset (whole chunks: the bot's chunk when the stream first sees it plus a uniform random shift in ±2^20 chunks per axis): tile and drop chunk coordinates, `self`, entities; `y` kept. `world.dim` → `"hidden"`; entity `n` removed; no `chat` events, `chat: []` in the snapshot. Default deny: only `snapshot`, `tiles`, `ents` and `hud` pass, each rewritten (the `hud`, inventory included, as is: it holds only numbers and item ids, counts and durability, nothing positional and no names). `hello` and the snapshot carry `relative: true`. Each reconnect gets a new offset. If the flag is turned on during a stream, it ends with `bye {revoked}` before its next write. |
  | `GET /api/v1/bots/{bid}/viewer`, auth, icons, rescan, release, orders clear, planner delete | nothing sensitive (the server and bot ids in them are mapped). Users and audit: owner only. |

  A test (`StreamerTest.everyLoggedInRouteIsClassified`) fails when a logged-in route isn't classified as redacted, no data, forbidden for forced users, or owner only. Every session response with an ETag carries `Vary: Cookie`, and logging out (`logout`, `logout-all`) sends `Clear-Site-Data: "cache"`.

  The page hides the Console and Proxy tabs for a forced user. The Settings tab stays (for the theme and the user's own password) but doesn't show the plugin settings.
- **Changing the flag:** only an explicit `streamer` key in `perms` changes it. A preset, or a `perms` object without the key (an older page), keeps the stored value, so nobody is un-forced by accident. The audit log records the transitions ("forced streamer mode ON" / "OFF").
- **Audit redaction:** console commands that the console hides, and login-style commands (`login`, `register`, `pass`, `password`, `auth`, `key`, with or without `/`), are logged as `(hidden)`.

## Browser endpoints (website login required)

- `GET /api/v1/stashes`: `[{ "server", "stash", "player", "online", "lastSeen", "indexedAt", "itemTypes", "itemTotal" }]`. `online` means a heartbeat or index arrived in the last 45 seconds.
- `GET /api/v1/index?server=..&stash=..`: the stored index as it was pushed, plus `"receivedAt"`, with the creative-tab fallback filled in (see "Additions: creative tabs for bots that don't send them"). Returns `404` if there isn't one.
- `GET /icon/<namespace>/<path>.png?v=<mcVersion>-<token>`: the item icon, always a static PNG. Only ids that appear in a stored index get one; any `name`/`mc` parameters are ignored.
  - `v` is set by the page to the index's Minecraft version plus a source token (today `g1`). Changing the token or the version changes the URL, so the browser doesn't reuse icons it cached under an older scheme. If some stored index has that version, the server renders from that version's game jar; otherwise it uses the version of the first index containing the id.
  - Sources, in order: a 64 px render from the local Minecraft client jar (cached per version in `icons/game-<mcVersion>/`), then the wiki pipeline (Minecraft Wiki, then mcasset.cloud; cached in `icons/<namespace>/`). The `iconSource` setting can limit this to one source. Icons are rendered or fetched for every id of an index as soon as it arrives. If no jar is on the machine and `iconDownload=true`, the server downloads the official client jar once at launch (in the background; icons answer 503 until the set is ready), renders the whole set, and later launches reuse it (completion marker + manifest.json of SHA-256 hashes). Planner materials that no index has are allowed for icons (validated id, at most 4096 remembered), so their rows have `icon: true`.
  - Ready: `200`, `Cache-Control: public, max-age=604800`.
  - Waiting on the wiki: `503`, `Retry-After: 2`, `Cache-Control: no-store`, with the placeholder PNG as the body. The request never waits for upstream.
  - Unknown id, or not available from any enabled source: the placeholder PNG with `200`.
- `GET /api/v1/index` sends an `ETag` and answers `If-None-Match` with `304`. JSON responses over 1 KiB are gzip-compressed when the request has `Accept-Encoding: gzip`; the gzip variant of the index has its own ETag (`"<hash>-gz"`) and both carry `Vary: Accept-Encoding`.

## Link addon (website)

- `GET /api/v1/link` (website login required; `apiKey` only in sudo mode, otherwise `apiKeySet: true` and `apiKeyHint`): `{ "apiKey": "...", "urls": ["http://192.168.1.20:8765", ...], "linked": { "player", "server", "stash", "lastSeen" } | null }`. `urls` are the addresses the server listens on (localhost and its LAN IPv4 addresses). `linked` stays `null` until an addon request authenticated with the *current* key arrives; it then holds that request's details.
- `POST /api/v1/link/regenerate` (browser-write guard and sudo mode): makes a new key, saves it to `config.properties`, and returns `{ "ok": true, "apiKey": "<new key>" }`. The old key gets `401` from then on, and `linked` goes back to `null`.

Only the owner can read the key this way (see "Additions: website users").

## Browser writes (CSRF guard)

Every state-changing request from the website (`POST /api/v1/link/regenerate`, `/api/v1/rescan`, `/api/v1/stashes/release`, `/api/v1/orders`, `/api/v1/orders/clear`, `/api/v1/kits/chest`, `/rule`, `/rule/delete` and `/scoring`, `/api/v1/orders/<id>/cancel` and `/force-cancel`, the `/api/v1/planner/...` JSON POSTs, the `/api/v1/auth/...` POSTs) must meet all of these, otherwise it gets `403`:

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

Order fields: `id` (`o-` followed by 12 random hex digits), `server`, `stash`, `lines`, `names` (shulker names or null, see "Additions: shulker names"), `createdAt`, `updatedAt`, `status`, `phase` (free text from the bot, such as "gathering 3/10"), `progress` (`{ "done": n, "total": m }` or null), `message` (the bot's reason for a failure, or info), `cancelRequested` (bool) and `result` (`{ "shulkers": n, "lines": [{ "key", "delivered" }] }` or null).

`status` goes through:

- `pending`: waiting for the bot.
- `accepted`: the bot has claimed it.
- `in_progress`: it is being worked on.
- `delivered`, `failed` or `cancelled`: finished; one of these is always the last status.

### Browser (access check, plus the browser-write guard on the POSTs)

- `POST /api/v1/orders`: `{ server, stash, lines, names }`. Returns `200 { ok, order }`. `names` is optional (see "Additions: shulker names"). Orders no longer have a note: a `note` field in the body is ignored, and no order JSON contains one (orders stored with a note lose it when they are loaded). Each line is validated against the current index. The key must exist, and the count must be at least 1 and no more than what is available minus what other open orders have reserved (pending, accepted or in progress). Lines with the same type and key are merged. There are at most 200 lines, and each `count` must be a whole number from 1 to 2147483647. A stash can have at most 20 open orders. If validation fails, the reply is `400` with `{ error, line }`, where `line` is the index of the offending line in the request (null when the problem isn't a single line, such as the open-order limit or a missing index).
- `GET /api/v1/orders?server=..&stash=..`: `200 { orders, available }`. `orders` is that stash's orders, newest first (the last 100 are kept; open orders are never pruned). `available` is `{ items: { <key>: n }, shulkers: { <key>: n } }`, what can still be ordered for each key of the current index (availability as above, after open orders). Both are empty when the stash has no index.
- `POST /api/v1/orders/<id>/force-cancel`: for an `accepted` or `in_progress` order whose cancel was requested but which the bot never confirmed. It becomes `cancelled` straight away with the message "cancelled from the website (bot didn't confirm)", which releases its reservations; the bot's later status reports for it get `409`, which the addon treats as done. Returns `200 { ok, order }`; `409` if no cancel was requested, the order is `pending`, or it is already finished; `404` if there is no such order. The page offers it once the cancel has waited 60 seconds (orders carry `cancelRequestedAt`, null for orders from before that field, which then use `updatedAt`) or while the bot is offline.
- `POST /api/v1/orders/clear`: `{ server, stash }`. Removes every finished order (`delivered`, `failed`, `cancelled`) of that stash, from memory and from disk. Open orders (`pending`, `accepted`, `in_progress`) are never removed, since they hold reservations and the bot may be working on them. Returns `200 { ok, removed: n }` (`0` for a stash with no orders).
- `POST /api/v1/orders/<id>/retry`: for a `failed` or `cancelled` order. Creates a new `pending` order with the same `server`, `stash`, `lines` and `names`, validated exactly like `POST /api/v1/orders` (the same `400 { error, line }` replies against the current index and open orders, the 54-shulker cap and the 20-open-orders limit), and returns `200 { ok, order }` with the new order. `409` for any other status, `404` for an unknown id. The old order is left as it is.
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

## Additions: shulker names

An order can ask for the shulkers it delivers to be renamed in an anvil.

**`names`** is part of the order JSON everywhere: the create body, the stored order, what `claim` returns to the bot, and the browser GETs. Returned orders always carry it (`null` when there is none).

- `null` or absent: no naming.
- `{ "mode": "all", "name": "<text>" }`: every shulker the order delivers gets this name.
- `{ "mode": "each", "names": ["<text>", "", ...] }`: the i-th delivered shulker (0-based) gets `names[i]` when it is present and not blank. A blank entry, or an index past the end, leaves that shulker unnamed.

The server validates and normalises `names` on create and returns `400 { error, line: null }` on bad input:

- Every name is trimmed and may then be at most 50 characters (the anvil's limit). It may not contain `§` or a control character (U+0000 to U+001F, U+007F to U+009F).
- `mode: "all"` needs a string `name`; a blank one makes `names` null.
- `mode: "each"` needs an array of at most 54 entries, each a string or `null` (`null` counts as blank). Blank entries are stored as `""`, trailing blank entries are dropped, and a list that is all blank makes `names` null.
- `names` that isn't an object or null, an unknown or missing `mode`, or a wrong type gets `400`.

Bots check every name again and skip one that fails.

**Which shulker is "i".** The units are counted in the delivery order of "Additions: delivery status and order size": shulker lines, then empty-shulker lines, then the packed bins. Every delivered unit counts, including whole packed shulkers and empty shulkers. The website shows the planned layout and, in `each` mode, a name box for each planned shulker with what it will hold. The bot re-plans from what it can actually reach, so it may deliver fewer shulkers; names map by index and extra names go unused.

**What the bot does.** It has an anvil position, set like the delivery container (the plugin's `stash anvil here | <x> <y> <z> [dimension] | clear`, the addon's `select-anvil`, `clear-anvil`, `show-anvil` and `anvil-color`). Before delivering a named shulker it renames it in that anvil, checking the XP cost against its levels. If it can't (no anvil set, the block isn't an anvil or can't be reached, too few levels, a name that fails the rules, no result within about 30 s), it delivers the shulker unnamed: naming never fails or pauses an order. The order's problems then get one combined note, `"<n> shulker(s) delivered unnamed: <reason(s)>"`.

**Heartbeat.** The body gains an optional `naming: { "anvil": <bool>, "xpLevels": <int> }`:

- `anvil` is true when an anvil position is set and (if its chunk is loaded) it is an anvil block.
- `xpLevels` is the bot's current level, or `-1` when unknown. It must be a whole number >= -1; a missing or null `xpLevels` is stored as `-1`.
- `anvilSource` (optional) says where the bot's anvil comes from: `"set"` (an anvil position is set), `"found"` (it found a placed anvil in the stash area), `"placed"` (it placed one from stock), or `"none"` (none yet). Newer bots (plugin 1.8.1, addon 0.4.0-dev.4) send it: when no anvil is set they look for one in the stash area, or place one from the stash, and deliver unnamed only when neither works. A missing or null `anvilSource` (older bots) is stored as `null`.
- Bots without naming support leave it out.

A `naming` that isn't an object or null, or whose `anvil` isn't a boolean, `xpLevels` isn't a whole number >= -1, or `anvilSource` isn't one of `"set"`, `"found"`, `"placed"`, `"none"` (or null/absent), gets `400`. The server keeps it in memory like `delivery`: a heartbeat that leaves it out keeps the stored value, and an explicit `null` clears it. `GET /api/v1/stashes` entries return it as `naming` (`{ anvil, xpLevels, anvilSource }`, with `anvilSource` `null` when the bot didn't send it; or `null` if `naming` was never sent).

The website warns on the order form, when names are wanted, if the stash's bot reports `naming: null` ("this bot can't name shulkers; they'll arrive unnamed"), `anvil: false` ("no anvil yet: the bot will look for one in the stash area, or place one from the stash" for a bot that sends `anvilSource`; "no anvil set" for an older one), or `xpLevels` below the number of named shulkers ("may run out of XP: about 1 level per shulker"). It is only a warning; the order can still be placed.

## Additions: crafting

Bots can craft missing empty shulker boxes from the stash's materials at a crafting table.

**Heartbeat.** The body gains an optional `crafting: { "enabled": <bool>, "table": <bool> }`:

- `enabled` is true when auto-crafting is on.
- `table` is true when a crafting table position is set and (if its chunk is loaded) it is a crafting table.
- Both are required. Bots without crafting support leave the field out.

- `tableSource` (optional, newer bots) says where the bot's crafting table comes from: `"set"` (a position the player set), `"found"` (found in the stash area), `"placed"` (placed from the stash), `"crafted"` (crafted from planks or logs) or `"none"` (none yet). Absent or `null` means an older bot that doesn't report it.

A `crafting` that isn't an object or null, or whose `enabled` or `table` is missing or isn't a boolean, or whose `tableSource` is present, not null and not one of those five strings, gets `400`. The server keeps it in memory like `naming`: a heartbeat that leaves it out keeps the stored value, and an explicit `null` clears it. `GET /api/v1/stashes` entries return it as `crafting` (`{ enabled, table }`, plus `tableSource` when the bot sent one; `null` if never sent).

The website shows a neutral note on the order form for the stash's bot:

- `enabled` and `table` both true: "The bot may craft missing empty shulker boxes from the stash's materials." With a `tableSource` of set, found, placed or crafted it adds " (crafting table: <source>)".
- `enabled` true, `table` false, with a `tableSource` (the bot can find, place or craft one): "No crafting table yet: the bot will look for one in the stash area, place one from the stash, or craft one from planks or logs."
- `enabled` true, `table` false, without `tableSource` (an older bot): "Crafting is on, but no crafting table is set: the bot can't craft shulker boxes."

Nothing is shown when `enabled` is false or `crafting` is `null`.

## Additions: creative tabs for bots that don't send them

Proxy bots (the AquariusProxy plugin) send `tab: "other"` for every item. So that the website can still show the creative tabs, the server ships one generated table per Minecraft version at `src/main/resources/creative-tabs/<mc>.json` (1.21.4 and 1.21.11 today):

```json
{ "version": "1.21.4", "tabs": ["building_blocks", "colored_blocks", ...], "items": { "minecraft:stone": ["building_blocks", 12], ... } }
```

The tables follow the addon's rules (CATEGORY tabs only; an item in several tabs gets the first in vanilla tab order; the order is its first index in that tab's display list).

Whenever the server returns an index to the browser (`GET /api/v1/index`), every item whose `tab` is missing, null or `"other"` is looked up by its base item `id`, and `tab` and `tabOrder` are filled from the table for the index's `mcVersion`: an exact match, otherwise the nearest lower version with the same `major.minor`, otherwise the newest table. An item missing from the table stays as it is (`other`), and a real tab sent by the bot always wins. This is done when an index is received or loaded, on the copy that is served, and the file on disk keeps what the bot sent, so indexes already stored and bots that were never updated are fixed too. With no table at all, nothing changes.

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
  - `ts` is older than the moment the server process started **minus the 60 s skew** (1.5.0). The nonce cache is empty after a restart, so without a floor anything captured shortly before it could be replayed once; the floor closes that for requests older than the skew. The trade-off: a bot whose clock runs behind by up to 60 s is not locked out right after a restart, and a request captured in the last 60 s before the restart can still be replayed once, until its own `ts` is a skew old. The answer is the same `401 bad signature`; a bot that gets one retries with a fresh `ts` and nonce (2, 4, 8 s, then every 15 s);
  - the nonce has been seen in the last 5 minutes (the server keeps a cache of recent nonces);
  - the signature doesn't verify, compared in constant time.
- The server tries the current key. After a key regenerate it also tries the previous key for 10 minutes, but only on the **legacy** bot routes (`Router.Guard.ADDON_KEY_LEGACY`: `index`, `heartbeat`, `delivery`, `orders/claim`, `orders/{id}/status`, `kits/report`, `repair/report`). Every other bot route is **strict** (`Router.Guard.ADDON_KEY`, the default for a new route): signed requests only (never Bearer, whatever `allowBearer` says), the current key only, a body of at most 1 MiB. Everything under `/api/v1/bot/` and `/api/v1/tasks/` is strict. A test lists every bot route and its class.

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

## Additions: build planner

A logged-in user uploads a schematic and gets its survival material list joined with a stash's index (have / available / missing), and can order what the stash has.

**Permissions.** `planner` for every endpoint below, `plannerOrder` for `.../order` (dry runs included). `plannerOrder` implies `planner`, which implies `view`; `plannerOrder` does not need `order`. Stash scope applies: a `server`/`stash` outside it answers `404`. A plan belongs to the user who uploaded it: anyone else's plan id answers `404` (the owner's included).

**Upload.** `POST /api/v1/planner/upload?server=..&stash=..&name=<file name>`, the raw file as the body.
- Guard `BROWSER_UPLOAD`: the browser-write checks (POST, `X-Stash-Manager: 1`, same `Origin`, `Sec-Fetch-Site`) but `Content-Type: application/octet-stream` instead of JSON, then a session. Anything else gets `403`.
- `server` and `stash` are optional (both or neither); with them the reply is joined with that stash.
- Formats: Litematica `.litematic` (a `BlockStates` array longer than needed is padding and accepted), Sponge `.schem` (versions 1-3), vanilla structure `.nbt` (gzip or raw NBT; block entries past the declared size are ignored).
- Body at most 8 MiB (`413` with no body and `Connection: close`: the rest of the upload is never read). An empty body, a file that can't be read or is over the parser's limits, or a read that takes over 10 s: `400 { error }` with a short message that never quotes the file. A pre-1.13 MCEdit `.schematic`: `422` with the hint to re-save it as `.litematic` or `.schem`.
- At most 2 files are read at once, on a dedicated pool (`429` with `Retry-After` when both are busy). Per user: 10 uploads a minute and 60 an hour (`429 { error, retryAfter }` with `Retry-After`); every accepted attempt counts, failed ones included.
- The block-items table is the one with the lowest data version not below the file's (a 1.21.5-1.21.10 file gets the 1.21.11 table), or the newest when the file is newer than every table or has no data version. A block that table doesn't know is looked up in the other bundled tables before it counts as unknown. `newerThanTable` is true when the file is newer than the newest table.
- Reply: `200 { ok, plan }` (below, with the default options). Audit action `planner.upload` (file name, format, block count).

**What is kept.** The file bytes are never written to disk or logged and are dropped when the request ends. The server keeps only the computed list, in memory: id `p-<12 hex>`, owner, the file name (everything up to the last `/` or `\` removed, unprintable and invisible characters dropped, at most 64 characters), format, data version, size, dimensions, block count, the item, fluid, chest-content and unknown-block counts, `createdAt` and the last use. A plan ends 30 minutes after its last use or 2 hours after the upload, when it is deleted, when its user has no session left (at once on logout, logout-all, disabling or deleting the user, a password reset or "log out" by the owner; on expiry at the next planner request), and on restart. At most 3 per user (the oldest goes) and 50 in all (the oldest goes).

**Endpoints.**
- `GET /api/v1/planner` → `{ plans: [{ id, fileName, format, blocks, createdAt, expiresAt }] }`, the caller's plans, oldest first.
- `GET /api/v1/planner/{id}?server=..&stash=..&multiplier=1&exclude=<id>,<id>&fluids=1&containerItems=1&round=none` → `{ ok, plan }`. `server` and `stash` are required here; switching them needs no new upload.
- `POST /api/v1/planner/{id}/view` (`BROWSER_WRITE`, `planner`) `{ server, stash, multiplier, exclude, fluids, containerItems, round }` → the same as the GET, with the options as JSON (the page uses this: a long `exclude` list doesn't fit a URL). It changes nothing.
- `POST /api/v1/planner/{id}/delete` → `{ ok }` (`BROWSER_WRITE`).
- `POST /api/v1/planner/{id}/order` (`BROWSER_WRITE`, `plannerOrder`) `{ server, stash, multiplier, exclude, fluids, containerItems, round, names, dryRun, part }`.

**Options** (view and order): `multiplier` 1-64 (default 1); `exclude` item ids left out (at most 2000; in the query, comma-separated); `fluids` adds `minecraft:water_bucket` / `minecraft:lava_bucket` for source blocks (default off); `containerItems` adds the items inside chests and other block entities (default off); `round` `none` | `stack` | `shulker` rounds each line up to whole stacks or a whole shulker (27 stacks). A bad option answers `400`.

**plan:** `{ id, fileName, format ("litematic" | "sponge" | "structure"), formatVersion, dataVersion, size, dimensions: {x, y, z}, regions, blocks, tableVersion, tableDataVersion, newerThanTable, createdAt, expiresAt, options, fluids, containerItems, unknown: [{ id, count }], unknownOmitted, stash: { server, stash, indexed, mcVersion } | null, items, totals, ordered }`.
- `items`, in creative order (tab, then the index's `tabOrder`, then id): `{ id, name, icon, mc, base, need, from: { blocks, fluids, containers }, inIndex, inStash, available, missing, want, maxStack, variants, excluded }`. `need` = `base` × multiplier. `inStash` is the index count of the plain id key only (`OrderCounts.total("item", id)`). `available` = `inStash` minus what open orders reserve (the numbers order placement uses). `missing` = max(0, need − available). `want` = min(need rounded as asked, available), 0 when excluded. `variants` counts the same id under component-patched keys (`id#hash`): shown, never counted as have and never ordered. `maxStack` comes from the index, else the block-items table. `name`, `icon` and `mc` come from any stored index that has the id (no icon: the page shows the placeholder).
- `totals`: `need`, `available` (sum of min(need, available)), `missing`, `want` over the rows not excluded; with an index also `lines`, `slots`, `shulkers` and `parts` of the split below.
- `totals.frozen` is true when `want`, `lines`, `slots`, `shulkers` and `parts` come from the split a part was placed with (see Order), so they match the parts being ordered.
- `ordered`: `{ partsTotal, placed: { "<part>": "<order id>" }, remaining: [parts never placed], again: [placed parts whose order failed or was cancelled], locked, sameOptions, options, server, stash }` once a part has been placed, else `null`. `options`, `server` and `stash` are the ones the split was made with; `sameOptions` says whether this view asked for them. The view may preview other options while the order is `locked`.

**Order.**
- Lines are the rows with `want` > 0, in creative order; rows with `want` 0 are dropped and listed as `notAvailable`.
- They are split into parts: each item line takes `ceil(count / maxStack)` slots (an empty-shulker line one shulker per unit), and a part may need at most 54 shulkers (as order placement counts them) and hold at most 200 lines. A line that doesn't fit is split at the part's remaining room (whole stacks) and the rest starts the next part. Every part is checked against the order limits.
- `dryRun: true` → `{ ok, dryRun, parts: [{ part, lines: [{type, key, count}], lineCount, shulkers, slots, items, order }], partsTotal, remaining, notAvailable: [{ id, need }], missing: [{ id, missing }] }`. Nothing is placed.
- Otherwise one part is placed (`part`, 1-based, default 1) through the normal order placement (`placedBy` is the user; every check of `POST /api/v1/orders` applies and answers the same way): `200 { ok, order, part, partsTotal, remaining, again, placed }`. The part is recorded as placed as soon as the order exists. `400` for a part number past the end or when nothing is available, `409` for a part whose order is pending, running or delivered (or no longer in the order history). A placed part whose order failed or was cancelled may be ordered again; the new order replaces it (the audit entry says "again, was ...").
- The split is remembered on the plan (one per plan) with the options and stash it was made with. Until a part is placed, every dry run or order computes it afresh; once one is placed it is kept as it is, so the later parts are the confirmed ones even though the placed part now reserves its items. While any placed part's order is not failed or cancelled the split is **locked**: a dry run or order with other options or another stash answers `409 "options can't change after a part is ordered; dismiss and upload again, or keep the options"`. Once every placed part failed or was cancelled, other options start a new split. The bots end an order when the delivery container is full, so the page offers "Order next part" once the previous part's order has finished.
- `names: true` → `{ "mode": "all", "name": "Build: <file name>" }`, plus `" <i>/<n>"` with several parts, cut to 50 characters (the file part is shortened, `§` removed) and checked like any order's names.
- Audit action `planner.order` (file name, part, order id, stash).

## Additions: delivery storage (plugin 1.9.0, addon 0.4.0-dev.5)

The heartbeat may carry `deliveryStorage` (a separate field: `delivery` stays the container position):

```json
"deliveryStorage": { "autoExpand": true, "chests": 3, "full": 1, "canExpand": true, "blocked": null }
```

- `chests`: delivery chests (the delivery container plus the chests the bot put up above it; a double chest counts once). `full`: how many of them are full (0 <= full <= chests).
- `canExpand`: auto expand is on and nothing stops the bot from putting up another chest. `blocked`: why it can't (at most 160 characters from the bot, cut to the status length here), or null. Never positions.
- Absent: an older bot (the stored value is kept); null clears it. A malformed value gets `400`, like the other status fields.
- `GET /api/v1/stashes` returns it as `deliveryStorage` (or null). The website shows "N delivery chests, M full, …" with the order maker.
- With every chest full, the bot pauses the order ("paused: waiting for delivery space: <why>", at most 2 hours) instead of ending it.

## Additions: repair with XP bottles

Mending items can be repaired by the bot with Bottles o' Enchanting from the stash before delivery. Every field is optional and additive: older bots, servers and pages ignore them. Repair never blocks or fails an order.

**Formula.** One bottle is about 14 durability (3-11 XP, about 7; Mending repairs 2 durability per XP point). `bottlesFor(damage) = ceil(damage / 14)` per item; a line needs `count × bottlesFor(damage)`. An index item is **repairable** when `mending` is true, `damage > 0` and `maxStack == 1`. Reason when not (string, null when repairable), checked in this order: `"no Mending"`, `"not damaged"`, `"not a single item"` (stackable). An `item` line for a shulker box key (`minecraft:*shulker_box`) always gets `"not a single item"`. A `shulker` line is never wanted for repair.

- **Index (bot → server).** An item may carry `mending: true` (only when the stack has Mending; a non-boolean is `400`). The stored file keeps the index as sent. If the bot sent no `mending` (older bot), the server falls back to some `details` entry equal to "Mending" or "Mending <level>" (one space, then a roman numeral or digits; case-insensitive, trimmed).
- **`GET /api/v1/index`.** Each item gains computed `repairable` (bool) and `repairBottles` (int, bottles to fully repair ONE of it; 0 when not repairable). Forced-streamer users get them too (they are numbers).
- **Heartbeat (bot → server).** Optional `repair: true` when the bot can carry out repairs (a non-boolean is `400`; absent leaves the stored value, null counts as false). Kept in memory, like `naming`. `GET /api/v1/stashes` entries return `repair` (true, or false when never sent). The order form warns "This bot can't repair items; they'll arrive as they are." when it is false.
- **`POST /api/v1/orders`.** Optional `repairAll` (bool, default false; null is the default) and per-line `repair` (bool, default false). A line is wanted for repair when it is an `item` line and either its `repair` is true, or `repairAll` is true and the index item is damaged (`damage > 0`); under `repairAll`, undamaged and non-damageable lines (cobblestone, undamaged tools) are not wanted: no estimate line. A wrong type is `400` (with the line index for a line). Asking to repair something that can't be repaired is not an error. Merged lines: `repair` is true if any merged copy had it. Bottles are not reserved and an order is never refused for too few. Retry copies `repairAll` and the lines' `repair`; the estimate is computed again. Build-planner orders: `repairAll` false, no estimate.
- **The stored order** (every order JSON): `repairAll` (bool), each line's `repair` (written only when true), and `repairEstimate`: null when no line is wanted, else `{ "needed": 12, "available": 40, "short": false, "lines": [ { "key", "count", "repairable", "reason", "bottles" } ] }` with one entry per wanted line in line order (`bottles` is the line total, 0 when not repairable; `needed` their sum; `available` = bottles available when the order was placed, as below; `short` = `available < needed`). Computed at placement, not changed later.
- **`GET /api/v1/orders`.** `available.bottles` (int): the index count of the plain key `minecraft:experience_bottle` minus what open orders reserve as `item` lines of that key (0 when the stash has none).
- **Status (bot → server).** `result.repair` (optional, null = none): `{ "bottlesUsed": 10, "repaired": 2, "partial": [ { "key", "damage", "maxDamage" } ], "skipped": [ { "key", "reason" } ] }`. `bottlesUsed` and `repaired` are whole numbers >= 0 (missing = 0); `partial` (items delivered not fully repaired, with the damage left; `maxDamage` optional) and `skipped` (items wanted but not tried) hold at most 400 entries each (extra entries are dropped); keys are cut to 256 characters, `reason` to 60. A wrong shape is `400`. Reasons the bots use: `"no Mending"`, `"not damaged"`, `"not a single item"`, `"no bottles"`, `"interrupted"`. When anything is partial or skipped, the bot adds `"<n> item(s) not fully repaired: <reason(s)>"` to `message`.
- **Forced-streamer redaction.** `result.repair` is numbers and item keys, served as they are, except a skipped `reason` that is not one of the five known reasons above, and a `partial`/`skipped` `key` that does not match `^[a-z0-9_.:/-]+(#[0-9a-f]+)?$`: those read `"(hidden)"` (free bot text could hold a position). The order `message` is hidden as before.
- **The bot** (plugin and addon local orders): after gathering and before packing, for each wanted item it checks live as repairable it moves its own equipped Mending gear into free slots, takes bottles from the stash (`ceil(remaining damage / 14)` plus a few spare), holds the item to repair in the main hand and the bottles in the offhand, one item at a time, and throws bottles at its feet standing still until repaired or out of bottles, re-checking durability after each batch. A player within 8 blocks or vanishing orbs stop it and it retries once; then it delivers as is with reason `"interrupted"` (hard cap about 2 minutes of throwing). Leftover bottles go back to the stash; thrown bottles are `bottlesUsed`.
- **The website** (Orders tab): a cart-wide "Repair all items" switch, a "Repair" toggle on repairable item lines ("can't repair (no Mending)" and so on on damaged ones that can't be), the live estimate "about N bottles needed, M in the stash" with a warning when short or when the bot reports `repair` false; order cards show the estimate and, on finished orders, the bottles used and the items not fully repaired.

## Additions: live viewer

A logged-in user with the `viewer` permission can watch a bot live: a map of the chunks around it (vanilla map colours), its position and heading, nearby players, mobs and dropped items, its HUD (health, food, XP, armour, hotbar, offhand) and its chat. Bot to server: signed frames at up to 5 Hz, sent only while someone watches. Server to browser: Server-Sent Events. Everything is in memory: nothing is written to disk, and no position, name or chat line is logged. Plugin bots only (the Meteor addon has no signed dashboard link).

**Permission.** `perms.viewer` (boolean; the owner has it). It implies `activity` (the bot list). Presets: `operator` has it, `viewer` and `orderer` don't. `Router.Perm.VIEWER` guards both browser routes; a bot outside the user's bot scope is `404`.

**Start and stop (bot/next).** The `bot/next` reply gains `"watchingViewer": <bool>` and, only while watching, `"viewer": {"radius": 8, "hz": 5, "session": "<16 hex>"}`. A change of either wakes the long-poll. Every open browser stream refreshes the watch every 4 s and once more when it closes, so watching lingers 10 s after the last stream (a reconnect keeps the session). A new session is minted when watching starts, or when the server has no viewer state for the bot; a new session means the bot's next frame must be full.

### POST /api/v1/bot/viewer (signed, like every bot/* request)

Body at most 512 KiB (`413` above). At most 15 frames per second per bot (`429 {"ok":false,"error":…,"retryMs":n}`; every attempt counts). Unknown bot (no `bot/hello`): `409`. Anything malformed: `400`.

```json
{"botId":"ct113","v":1,"session":"9f3c…","seq":1042,"t":1730790000123,"full":false,
 "world":{"dim":"minecraft:overworld","minY":-64,"height":384},
 "self":{"x":123.4,"y":64,"z":-88.0,"yaw":91},
 "tiles":[{"x":7,"z":-6,"c":"<base64 of 256 bytes>","h":"<base64 of 512 bytes>"}],
 "drop":[[5,-9]],
 "ents":[{"id":311,"k":"player","n":"Steve","x":130.2,"y":64,"z":-80.5,"yaw":180},
         {"id":402,"k":"mob","t":"zombie","x":1,"y":2,"z":3,"yaw":12},
         {"id":509,"k":"item","t":"minecraft:diamond","c":3,"x":1,"y":2,"z":3}],
 "hud":{"hp":18.5,"food":17,"sat":2.0,"xp":30,"xpP":0.42,"armor":15,"held":2,
        "hotbar":[{"id":"minecraft:diamond_sword","c":1,"d":120,"md":1561},null,null,null,null,null,null,null,null],
        "armorSlots":[null,null,null,null],"offhand":{"id":"minecraft:totem_of_undying","c":1},
        "inv":[{"id":"minecraft:dirt","c":64},null, … 27 entries in all …]},
 "chat":[{"t":1730790000001,"k":"public","s":"Steve","to":null,"m":"hello"}]}
```

- `v` is 1; `session` is the 16-hex session from `bot/next`; `seq` (≥ 0) increases within a session; `t` is the bot's clock (epoch ms) when the frame was captured.
- `full:true` replaces the server's state: it needs `world`, `self`, `tiles` and `ents` (`hud` may be missing for a moment at login: the page shows "-"; `chat` optional, at most 50 lines; the plugin sends at most 20). A full frame needn't cover the whole radius (the plugin sends the nearest 300 tiles); later frames add the rest.
- Otherwise a missing `world`/`self`/`tiles`/`drop`/`ents`/`hud`/`chat` means unchanged. `ents`, when present, is the complete list (at most 256). A delta whose `seq` isn't above the last one applied is ignored; one more than 100,000 above it is not applied (`resync`).
- **Tiles:** chunk `x`/`z` (|v| ≤ 2,000,000); `c` = 256 packed map bytes (`colourId*4 + brightness`, 0 = transparent) and `h` = 512 bytes (int16 little-endian top y per column, -32768 = none), columns `i = lz*16 + lx`. Standard base64 with padding; a tile whose `c` isn't 344 characters / 256 bytes, or whose `h` isn't 684 / 512, is `400`. At most 625 `tiles` and 625 `drop` (`[x, z]` pairs). A tile or drop more than (radius + 2) x 16 = 160 blocks from the bot's position on either axis is ignored. The server keeps at most 1,024 tiles per bot: past that the farthest from the bot go and every stream gets a fresh snapshot.
- **Entities:** `id` (int), `k` one of `player`, `mob`, `item`, `other`; optional `n` (≤ 16 characters, players), `t` (≤ 64: a mob type such as `zombie`, an item id such as `minecraft:diamond`), `c` (item count), `yaw`; `x`/`y`/`z` required. Only these fields are kept; positions are rounded to 0.1, yaw to a whole number.
- **HUD:** optional numbers `hp`, `food`, `sat`, `xp`, `xpP` (0-1), `armor`, `held` (0-8); `hotbar` exactly 9 entries, `armorSlots` (optional) exactly 4, `offhand` (optional), `inv` (optional: the main inventory, window 0 slots 9-35, top left to bottom right) exactly 27; each an item `{id, c, d?, md?}` (id ≤ 64 characters, `namespace:path`; ids and the dimension are lowercased before they are checked) or null. `d` (damage, whole, ≥ 0) and `md` (max damage, whole, 1-100,000) come together and only for damageable items, with `d` ≤ `md`; one without the other, or `d` > `md`, is `400`. Nothing else of an item is kept (no custom names). Plugins before 1.12.0 send no `inv` and no `d`/`md`: the page then shows no durability bars and says the inventory isn't sent; an older server ignores the new keys (it keeps only the fields listed). The plugin sends the HUD when it changes (inventory included) and at least every 5 s, never more often than its 5 Hz frame tick.
- **Chat:** at most 20 lines per delta; `t`, `k` one of `public`, `whisper`, `system`, `death`, `s` and `to` (text or null, cut to 16 characters; system and death lines have `s: ""`, `to` is null except on whispers), `m` (text, cut to 256). Control characters become spaces. Long text is cut, never refused.
- **Reply:** `200 {"ok":true,"watching":<bool>,"resync":<bool>}`. `watching:false`: stop sending. `resync:true`: the frame was not applied (a session that isn't the current one, or a delta with no full frame to build on); the next frame must be full, with the session from `bot/next`.

### Browser routes (website login, `viewer` permission)

- Item icons (`/icon/…`) need `view` or `viewer` (the hotbar and inventory), for item ids as before.
- `GET /api/v1/bots/{id}/viewer` → `{now, live, lastFrameAt, streams, maxStreams, mc}` plus `yours`, `maxPerUser`, `total`, `maxTotal`, `online`. `live`: a snapshot younger than 3 s; `streams`/`maxStreams` count this bot's streams. The page uses it to explain a failed stream and as a clock for the latency figures.
- `GET /api/v1/bots/{id}/viewer/stream?vs=<16 hex>` (GET only): `vs` names one viewer page and is kept across its reconnects. Checked before the headers: login and permission (`401`/`403`), the bot and its scope (`404`), the `Origin` / `Sec-Fetch-Site` rules of browser writes (`403`), `vs` (`400`), and the caps (`429`, `Retry-After: 5`): 8 streams on the server, 2 per user, 4 per bot; and, counted from the handler's start to its end (a replaced stream may still be finishing a write), at most 3 viewer connections per user and 10 on the server. A user may open at most 12 streams a minute and 20 new viewer pages (`vs`) an hour (`429`). A new stream of the same `vs` replaces the old one (which gets `bye {capacity}`) and doesn't count against the caps.
- Response: `200`, `Content-Type: text/event-stream; charset=utf-8`, `Cache-Control: no-cache, no-transform`, `X-Accel-Buffering: no`, chunked, never compressed. It starts with `retry: 1000`; each event is `event: <name>`, `id: <session>:<rev>` (when there is state) and `data: <one line of JSON>`. A `: hb` comment every 10 s, and a `:` ping comment whenever nothing was sent for 3 s (so a closed tab is noticed within seconds). Each batch is flushed at once. Writes go through a few dedicated writer threads: a write that doesn't finish within 8 s (a reader that stopped reading) ends the stream and frees its pool thread at once, and a stream to a very slow reader is cut 15 s after its rotate time. Such stuck writes are counted until they end: a user with 2 of them, or the server with 8, gets `429` for new streams, so the 16 writer threads never all stall.

| event | data |
|---|---|
| `hello` | `{botId, mc, st, rotateMs}` |
| `snapshot` | `{session, seq, t, rt, st, world, self, tiles[], ents[], hud, chat[≤100]}` |
| `tiles` | `{seq, t, rt, st, tiles[], drop[]}` |
| `ents` | `{seq, t, rt, st, self, ents[]}` |
| `hud` | `{seq, t, rt, st, hud}` |
| `chat` | `{lines[]}` |
| `status` | `{state}`: `waiting` (no state yet), `live`, `stale` (no frame for 3 s) or `offline` (the bot isn't polling) |
| `bye` | `{reason}`: `rotate`, `revoked` or `capacity` |

`t` = the bot's capture time, `rt` = the server's receive time, `st` = the server's send time (all epoch ms); `seq`/`t`/`rt` are those of the latest frame that changed that part.

- **Rotation:** each response ends itself with `bye {rotate}` after 45 s (the JDK server kills any response 60 s after its request); the page reconnects at once and gets a fresh snapshot.
- **Revocation:** before a batch is written (at most every 5 s) the session and permission are checked again (`Router.stillAllowed`); on failure only `bye {revoked}` is sent: a logged-out, disabled or deleted user, a changed password, a lost `viewer` permission or a bot taken out of the user's scope ends the stream with `bye {revoked}`.
- **Coalescing:** per stream, tiles are queued newest-wins per chunk, entities and HUD latest-only, chat at most 100 lines. A stream holding more than 2 MiB (a slow reader) drops its queue and gets one fresh snapshot instead. A full frame sends every stream a snapshot.
- **Memory:** about 0.7 MB per bot (625 tiles; at most 1,024); a bot's state is dropped 60 s after its last stream closed. Snapshots are put together under the bot's lock and serialized after it is released.
- **Audit:** `viewer.open` when a user starts watching a bot (at most once per user and bot per 15 minutes), `viewer.close` once that user has had no stream to that bot for 15 s (only after an audited open). Only the bot id and how long; never positions, names or chat.

**Colours.** `src/main/resources/web/map-palette.json` = `{"v":1,"mc":["1.21.4","1.21.11"],"rgb":[256 ints]}`: index = packed map byte, value = `0xRRGGBB`, or `-1` for transparent (ids 0-3, and ids the game doesn't define). Generated by `dev/map-colors` from the game's own `MapColor`, with both versions checked equal.

## Additions: delivery storage contents

The Orders tab's delivery chest preview shows what is **already** in the delivery storage (left over from earlier orders), so you can see whether a new order fits. These contents are **never** part of the index, search, the stock counts or the build planner's "have", and they hold no positions. The server keeps them **in memory only** (never written to disk or logged); after a restart it asks again.

**Heartbeat.** The body gains an optional `"deliveryHash": "<40 lowercase hex>"` (the hash of the bot's current report, below): absent from older bots, `null` when no delivery container is set (the stored report, if any, is dropped). Anything else gets `400`. The reply **always** carries `"needDelivery": <bool>` from servers that support this (a bot only sends reports once it has seen the key): `true` when the heartbeat has a `deliveryHash` and the server holds no report for that (server, stash) or one with a different hash; `false` otherwise (no `deliveryHash` included).

**`POST /api/v1/delivery`** (signed, like `/api/v1/index`, with the same owner check: `409 {owner}` for another bot's stash). Body at most 256 KiB (`413` above).

```json
{"version":1,"server":"2b2t.org","stash":"stash","player":"Qwerttze",
 "hash":"<sha1 hex of the chests array>","readAt":1767225600000,
 "chests":[
   {"n":0,"size":54,"full":false,"readAt":1767225500000,
    "items":[{"slot":0,"id":"minecraft:red_shulker_box","name":"Gems","count":1,"maxStack":1,
              "shulker":true,"contents":[{"id":"minecraft:diamond","count":1728}]},
             {"slot":5,"id":"minecraft:cobblestone","name":"Cobblestone","count":64,"maxStack":64}]},
   {"n":1,"size":54,"full":true,"readAt":0,"items":null}
 ]}
```

- `chests`: every delivery chest (at most 16) in **fill order**: `n` = 0 is the lowest chest with room, then the other chests with room (lowest first), then the full ones. `n` is 0-15 and unique; `size` is 27 or 54; `full` is the bot's view; `readAt` is when the bot last saw the chest (epoch ms), or 0 = never, in which case `items` is `null` (not seen). The bot may also send `items: null` for the last chests, and leave out shulker `contents`, to stay under the size limit.
- `items`: the non-empty slots (at most `size`): `slot` (0 to size-1, unique), `id` (`namespace:path`; lowercased), `name` (untrusted: control characters are dropped and it is cut to 64 characters; it may end with "…"), `count` (1-10,000), `maxStack` (default 64), optional `damage`/`maxDamage`. Shulker boxes: `shulker: true` and `contents` (at most 27 `{id, count}` grouped by id; `[]` = empty; absent = not reported).
- `hash`: sha1 of the canonical JSON of `chests` without the `readAt` fields; `readAt` (top level): the newest chest `readAt`.
- The bot sends a report after delivering into a chest, after any read of a delivery chest, and when `needDelivery` asks; at most once every 5 s, and only when the hash changed or it was asked.
- Reply: `200 {"ok":true}`; `400` for anything malformed (with the reason); `409` owner conflict; `413` too big. Only the known fields are kept.

**Browser.**
- `GET /api/v1/stashes` entries gain `"deliveryContents": {readAt, receivedAt, hash, chests, usedSlots, freeSlots, unknownChests}` or `null` (no report). `usedSlots`/`freeSlots` count the chests that were seen.
- `GET /api/v1/delivery?server=&stash=` (login, `view`, stash scope: `404` outside it; `400` without both parameters): the cleaned report plus `receivedAt`, or `null` when there is none.

**Website.** In the Orders tab's planned delivery chest, the items already in chest `n` = 0 are drawn in their slots, hatched and dimmed, with an "Already in the delivery chest" tooltip (name, count, a shulker's contents); the planned shulkers take the free slots after them, and those past the chest's free room are outlined (they go to the next chest). Below the chest: "Delivery chest: N of 54 slots used, M free; this order needs K (as of X ago)", or with several chests "3 delivery chests: U of T slots used, F free (1 not seen yet); the first (filled first) has M of 54 free; …". A chest never read shows "not seen yet". When the order needs more slots than are free (and every chest was seen), a warning says what happens: with `deliveryStorage.autoExpand` on, "the bot will put up more chests (if it can)"; otherwise "the order will pause until a chest is emptied". Names are shown as text only. Older bots (no report): the preview is as before.

## Additions: kits (kit sorting)

Shulkers that score as a kit go to a chest the user picks per kit name. The kit contract is v1.1: the score, the name normalisation, container ids, the hash, and the report and route fields. The bots compute the score; the server only stores the scoring settings and shows what the bots report. Everything is additive: a bot or page that doesn't know the fields works as before. This section is what the server does.

- **Heartbeat** (`POST /api/v1/heartbeat`). Optional body fields `kitsHash` and `kitsReportHash`: 40 lowercase hex digits, `null` (the bot has none) or absent (an older bot). Any other type or value is a `400`. The reply gains `kitsHash` (the server's hash for the stash, always), `kits` (the full settings object `{ rev, hash, kitsChest, scoring, rules }`, only when the body had the `kitsHash` key and it differs, `null` counting as different) and `needKitsReport` (always; true when the body had a non-null `kitsReportHash` and the server holds no report for the stash, or one with another hash). `scoring` is always complete: `{ threshold, name, mix, full, op, opMin }` (defaults 4, 2, 3, 1, 4, 3; `opMin` is how many OP items the `op` points need, 1..1728). A scoring without `opMin` (an old `kits.json`, an older page) means 3. The hash is the SHA-1 of `"v1\n" + chest + "\n" + "score t n m f o opMin\n"` followed by `key\tcontainer\n` per rule sorted by key. When the server can't read `kits.json` it leaves out all three.
- **`POST /api/v1/kits/report`** (signed, same owner check as `/api/v1/index`: `409 { owner }`). The body is at most 512 KiB (`413`). `400` with a reason for a malformed one: `player` must be non-empty text; the stash must already be known to the server (a heartbeat or index came first), else `400 unknown stash`; numbers are whole and at most 20 characters; `version` must be 1; `hash` 40 hex; at most 4096 containers with unique ids (`"x,y,z"`, ranges as in the contract), `size` 27 or 54, `free` 0..size, `pickable` boolean; at most 4096 `kits` entries, one per (key, container), whose `container` is in `containers`, `count` 1..54, `score` `{ total 0..40, name, mix, full, op 0..10 }` where `total` must equal the sum of the four parts, and `minTotal` 0..40 and not above `total`; at most 36 `carried` entries with `count` 1..36 and a `score`. A `key` is a normalised name (no "kit" needed) or `null` for an unnamed kit (its `name` is then ignored); `null` may appear once per container in `kits` and once in `carried`. Labels (cut to 32) and names (cut to 64) lose control and format (bidi, zero-width) characters. The report is kept in memory only (64 stashes, oldest dropped), never written to disk and never logged. `200 { ok }`.
- **Browser routes** (website login, stash scope: `404` outside it): `GET /api/v1/kits?server=&stash=` (`view`), `POST /api/v1/kits/chest { server, stash, container | null }`, `POST /api/v1/kits/rule { server, stash, key, container }`, `POST /api/v1/kits/rule/delete { server, stash, key }` and `POST /api/v1/kits/scoring { server, stash, threshold, name, mix, full, op, opMin? }` (`opMin` is optional: missing or `null` means 3) (browser-write guard, `settings` and `view` permissions, no sudo, bodies up to 4 KiB). The writes also answer `404` for a stash the server knows nothing about (no index, heartbeat or stored settings), so made-up names can't use up the 64-stash limit. All five answer the GET body. A forced-streamer user gets `403 { permission: "streamer" }` on all five. At most 60 kit writes per user per minute, then `429 { error, retryAfter }` with a `Retry-After` header. Setting a container needs a report (`409`) and a container that is `pickable` in it (`400`); clearing, deleting and changing the scoring always work. A bad or null key or a bad id, a `server` or `stash` over 256 characters or with an unpaired surrogate, out-of-range scoring (threshold 1..40, points 0..10, `opMin` 1..1728, whole numbers), or a 201st rule is `400`; an unreadable settings file is `503`. The audit log gets `kits.chest`, `kits.rule`, `kits.rule.delete` and `kits.scoring`.
- **GET body:** adds `scoring` (`{ threshold, name, mix, full, op, opMin, max }`, where `max` is the sum of the four points). Each row carries `score` (the highest-total breakdown among its entries) and `minTotal` (the lowest total); both are `null` for a rule whose kit isn't in the stash. The unnamed kits are one row with `key` null and `name` null: no rule, `target` is the kits chest. Row `count` is every kit of that key the bot reported: stored in containers plus carried (`carried` says how many of those). `kitsChestStatus` is `missing` when no report exists yet and a kits chest is set.
- **`GET /api/v1/stashes`** entries gain `kits: { supports, synced, flagged }` (numbers and booleans only), or `null` when the settings file is unreadable. `flagged` counts the rows whose status is `full` or `missing`.
- **Storage:** `stash-manager-data/kits.json` by default, or `kits.json` in the `--data` folder: `{ version: 1, stashes: [ { server, stash, rev, kitsChest, rules, scoring?, updatedAt } ] }`, at most 64 stashes, written atomically after copying the old file to `kits.json.bak` (only when the old file reads with no damaged entries). `scoring` is written only when it isn't the default; a file without it means the defaults. One unreadable stash entry only switches kits off for that stash (`503`, no kit fields in its heartbeat reply); the entry is written back unchanged by later saves, and a file with such entries never replaces the `.bak`. Fix the entry by hand and restart. A missing or unreadable file (not JSON, unknown version, a stash twice) falls back to the `.bak`; when neither can be read kit sorting is off: nothing is ever written over them, the routes answer `503` and the heartbeat leaves the kit fields out. Setting a value a stash already has changes nothing and keeps `rev`.

## Additions: stash-to-stash pearling (PEARL-CONTRACT v1.0 rev 7)

A traveller bot T at a stash whispers `load <pearlId>` to a loader bot L at another station; PearlPlus (or the addon loader) pulls T through its stasis chamber; T and L both report the arrival, T picks up the pearl L dropped and throws a fresh one into the chamber. The server keeps the stations and the trips and decides from both sides' reports. Everything is additive; a bot without `pearlTravel` or `pearlLoader` is never offered a trip. **No coordinates exist anywhere** in these shapes (chamber positions stay in PearlPlus or the addon's local config). The full rules and the shared test vectors are in `PEARL-CONTRACT-v1.0.md` and `pearl-vectors.json`; this section is the wire format.

- **Stations** (owner, website): `{ id: "ps-xxxxxxxx", name (1..32), server, dim, loader: { player }, stash (home station, at most one per stash), assign: { <lower-case player>: <chamber id> }, chambers: [ { id, owner, ownerKnown, presence, state, reportedAt, suspect } ], loaderState: { online, reportedAt, busy, playerNear, dropPearlAfterLoad, temporaryMode, warning, approved, and for the owner: source, pending, bound }, allowed (owner only): [ { name, uuid } ], loadsLastHour }`. `chambers` come from the loader's reports, never typed in. Chamber `state`: `armed`, `empty`, `owner_offline`, `unknown` (also when the report is older than 60 s), `broken`; an `empty` of a traveller that is in the queue or out of the game reads as `owner_offline`. At most 16 stations per install, 32 chambers per station. Stored (without chambers and loader state; each station also keeps its loader `binding`) in `pearl.json` `{ version: 1, stations, trips, pins }` (`pins`: player name to the uuid it was first seen with; every id, player, dimension and uuid is re-checked on load): atomic, owner only, `pearl.json.bak` from a clean previous file; both unreadable: pearl travel is off (`503`).
- **Trips** are Task records: `{ id: "t-<12 hex>", kind: "travel", action: go|back, state: pending|running|paused|completed|failed|cancelled, pauseReason, interrupt, origin: website|plugin|addon|system, priority, band, scope, target { botId, player, role }, params { oneWay, route { legs: [ { method: "pearl", station, chamber } ] } }, locks: [ { res: "chamber:<station>/<chamber>", mode }, { res: "loader:<station>", mode } ], checkpoint { step, data }, children [ { id, parent, kind: pearl.load | pearl.rearm, target, state } ], phase, error { code, text }, warning, pearls { before, after }, createdBy, createdAt, updatedAt, finishedAt }`. Steps: `precheck, arm_home, ready, load_sent, awaiting_pull, arrived, pickup, rearm, done`. The locks are taken together (all or none) when the server precheck passes. An automatic return is High 400, everything else Normal 200. At most 100 trips kept.
- **Heartbeat from a traveller** `pearlTravel` (object, `null` clears, absent keeps; needs `player`; wrong shape `400`): `{ v: 1, enabled, at, uuid, game: in|queue|out, joinedAt, client: plugin|addon, away: null | { station, trip, since }, pearls: { carried }, trip: null | { id, step, pause, error, whispers, loadSentAt, teleportAt, teleportDim, rearm: { result } | null, pearls: { before, after } }, local: null | { localId, action: go|back|home, station, oneWay } }`. Times are the bot's clock; the server normalises them with the skew `receivedAt - at` before comparing. **Reply:** `pearlTrip: { id, action, state, step, station, dim, loader, pearlId, oneWay, go, armHome, retry, rearm, hold, cancel, whispers, pullDeadlineAt (the bot's clock), code }` while a trip is open for the stash and once more when it ends; `pearlAwayClear: true` after the owner (or the bot's own `home` command) cleared "away": it is repeated in every reply until the bot's heartbeat reports `away: null` (the acknowledgement), for at most 10 minutes; then the server audits `pearl.away.clear` with a note and stops (old plugins never acknowledge). `go` and `rearm` are false once the trip is over, and the final `pearlTrip` is repeated until the bot's next heartbeat no longer carries that trip id. A local `go`/`back` starts a trip (idempotent by `localId`); a local `home` is honoured only when the server also sees the bot away, the stash's newest trip completed, no trip is open and the stash has no alert, and is audited as `pearl.away.clear` (user `bot <player>`, with the address, the client as origin); a local `go`/`back` is audited as `pearl.trip` the same way. **uuid pinning:** only the owner pins a uuid (`POST /api/v1/pearl/pins/reset { player, uuid }`, for an approved name: assigned, listed with `POST /api/v1/pearl/allowed { station, player, allowed }`, or the traveller of a home stash); a heartbeat never pins. A different reported uuid is ignored, raises the alert `uuid_mismatch` and the bot gets no trips (`409`) until the owner sets the new one; a bot without a pin is refused `uuid_unpinned`. With pearl fields the heartbeat `player` must be a player name (`400`).
- **Loader report** (the same body on both transports): the heartbeat field `pearlLoader` (the addon; the report's `player` must be the heartbeat's player, `409` otherwise; the sources that report for a station's loader (`hb:<server>/<stash>` or `bot:<botId>`; up to 4 kept per station, oldest dropped, shown to the owner as `loaderState.pending: [ { source, lastSeen, stash } ]`) are only pending: their reports are display only until the owner approves one by source string (`POST /api/v1/pearl/stations/approve { id, source }`; `POST /api/v1/pearl/stations/dismiss { id, source }` drops one), the approval is stored in `pearl.json` and survives station edits unless the loader name changes; a report from another source is `409` with a hint that the owner can approve it on the station), or the signed `POST /api/v1/bot/pearls` (the plugin; the body is `{ botId, ...report }`, a `bot/hello` is required (`409` otherwise) and the report's player must be the hello's). Body: `{ v: 1, player, at, dropPearlAfterLoad, temporaryMode, busy, playerNear, chambers: [ { id, owner, ownerKnown, presence: armed|empty|owner_offline|unknown, since } ] (at most 32), load: null | { id, requester, state: queued|loading|loaded|aborted, reason, at, pearlGoneAt, requesterNearAt, pearlPresent, reopened } }`. Reply: `{ allow: [ { name, uuid } ], intervalMs: 30000 }` (heartbeat: `pearlLoader: { allow, intervalMs }`): only the owner-approved travellers (a name with an `assign` entry, or the traveller of a stash with a home station), each with its pinned uuid (null before one was seen); `[]` for a player that is no station's loader; a loader reports only those owners' chambers and **must refuse a load unless the sender's uuid equals the pearl owner's `allow` uuid**. Pearl ids are names the owner chose and can't be checked for coordinates. The sources can be copied by a key holder, so the loader-side uuid check against the owner-pinned uuid (plugin and addon, each with a test) is the real defence. A report whose player is no station's loader is accepted and kept (at most 16 such players, newest kept). Reports are in memory only and never logged.
- **Server rules** (details in the contract): the precheck at `ready` (loader reported within 60 s and not in `temporaryMode`; the chamber `armed` with a report newer than T's join; T in game for 10 s with 2 or more pearls; no non-friend near the loader and the loader not busy; the locks free; fewer than 20 loads an hour at the station; a 5-minute cooldown between non-`back` trips; a return pearl armed at the home station unless `oneWay`, or `armHome`); counted pauses are retried 3 times 30 s apart, then fail; a paused trip is cancelled after 10 minutes; the pull deadline is `loadSentAt + 150 s`, or `load.at + 150 s` once the loader says `loading`; arrival needs T's teleport and L's `pearlGoneAt` and `requesterNearAt` within 10 s of it (clocks normalised) and no later than the deadline; `loader_no_answer` after 15 s without a `load` entry; one retry whisper after an abort with the pearl still there; a chamber is `broken` after two clicked loads that left the pearl for an online owner (or two `path_failed`), cleared only by the owner; after a server restart a trip before the whisper ends `server_restarted`, past it `arrival_unconfirmed` with an owner alert; an order arriving while a bot is away starts an automatic `back` trip when a return pearl is armed (retried after 60 s, 120 s, ...; after 3 failures in a row the alert `away_order` and no more tries until the owner clears the stash), else raises the alert `away_order`; an order before the whisper makes a `go` trip wait as `busy_order` (not counted) instead of cancelling it; no `load_sent` within 2 minutes of `go` ends the trip `go_timeout`; for 5 minutes after `pull_timeout`, `loader_no_answer` or `load_aborted` a trip report with `teleportAt` (or the error `unexpected_location`) turns the trip into `unexpected_location` with `hold` and an alert; past the whisper with no report from either bot for 30 minutes the trip ends `arrival_unconfirmed` with an alert; reading `GET /api/v1/pearl` never changes a trip.
- **Browser routes** (website login; forced streamers get `403 { permission: "streamer" }` on all of them; bodies at most 4 KiB; `429 { error, retryAfter }` with `Retry-After`: 60 writes per user per minute and 6 trip starts or stops per user per minute): `GET /api/v1/pearl` (`activity`), `POST /api/v1/pearl/stations { id?, name, server, dim, loader: { player }, stash? }` (owner; no `id` creates; a second home station for a stash is `409`), `POST /api/v1/pearl/stations/delete { id }` (owner; `409` while a trip uses it), `POST /api/v1/pearl/assign { station, player, chamber | null }` (owner; `400` unless the chamber's owner is that traveller), `POST /api/v1/pearl/pins/reset { player, uuid }` (owner; sets the pinned uuid), `POST /api/v1/pearl/stations/approve { id, source }` (owner; "This is my loader"), `POST /api/v1/pearl/allowed { station, player, allowed }` (owner; at most 16 per station), `POST /api/v1/pearl/chambers/reset { station, chamber }` (owner), `POST /api/v1/pearl/trips { server, stash, station?, action: go|back, oneWay? }` (`settings`, no separate travel permission; `404` outside the user's scope; `409` when the bot can't travel by pearl, has a uuid mismatch, already has an open trip, is already away or is in its cooldown), `POST /api/v1/pearl/trips/stop { id }` (`settings`; `409` after the whisper: a pull can't be undone) and `POST /api/v1/pearl/away/clear { stash, server? }` (owner). Audit actions `pearl.station`, `pearl.station.delete`, `pearl.assign`, `pearl.pin.reset`, `pearl.loader.approve`, `pearl.loader.dismiss`, `pearl.allowed`, `pearl.chamber.reset`, `pearl.trip`, `pearl.stop`, `pearl.away.clear`. Every write answers with the GET body (plus `id` for a created station or trip): `{ stations, trips (newest first), travellers: [ { server, stash, player, supports, enabled, uuidMismatch, reportedUuid, pinnedUuid and uuidConflict (owner only), away, pearls, game, online, trip } ], alerts: [ { kind, stash, station, trip, at } ], limits }`.
- **`GET /api/v1/stashes`** entries gain `pearl: { away, trip }` (a boolean and the open trip's state or null: no names, no ids; also served to forced streamers).
- **Website:** the "Pearl travel" panel in the Kits tab (after Repair): the bots that can travel (pearls carried, away, Go (asks for confirmation) / Back / Stop before the whisper, "It's home" for the owner, "Trust the new uuid" on a `uuid_mismatch` alert), the stations with their chambers (assign and reset: owner only) and an add form for the owner, the trips and the alerts. Names and players are `data-sensitive`; in voluntary streamer mode the station dropdown is numbered.

## Additions: repair passes (REPAIR-CONTRACT v2.0)

When the stash bot is idle it finds low-durability items, repairs the Mending ones with Bottles o' Enchanting from the stash, and sorting puts them in their best chest; items without Mending go to a "Needs repair" chest. The v1.2 order repair is unchanged. Everything here is additive: an older bot, server or page ignores it, and a bot that sent no `repairHash` is never asked to run a pass.

- **Formula** (shared by every codebase; test vectors in `RepairRoutesTest`): `bottlesFor(damage) = ceil(damage / 14)`; `low(item) = (maxDamage - damage) * 100 < T * maxDamage` in integers for a damageable single item with `damage > 0` (T 1..100; "below 50%" is strict, T = 100 means any damage). `usable = max(0, stashBottles - heldOrderBottles - reserve)`.
- **Settings** per server and stash, stored by the server: `{ rev, hash, enabled, auto, threshold, reserve, needsRepairChest, shulkers }`. Nothing stored: `enabled true, auto true, threshold 50, reserve 1728, needsRepairChest null, shulkers true` (repair mode is on by default once synced; items are repaired fully). `threshold` 1..100, `reserve` 0..1,000,000, `needsRepairChest` a container id or null, `shulkers` = open unnamed, non-kit shulker boxes. `rev` goes up by 1 on every change. `hash` is the SHA-1 of `"repair-v1\n" + enabled + "\n" + auto + "\n" + threshold + "\n" + reserve + "\n" + (needsRepairChest or "") + "\n" + shulkers + "\n"` (booleans `true`/`false`); vectors: defaults `cc21b16556872c97d51a1a7d5c9164beae9a7860`, `"repair-v1\ntrue\nfalse\n40\n0\n120,64,-33\nfalse\n"` gives `52a63a5332bc3f89e9c9d6431a60864e678e9132`. Stored in `repair.json` (`{ version: 1, stashes: [ { server, stash, rev, enabled, auto, threshold, reserve, needsRepairChest, shulkers, updatedAt } ] }`, at most 64 stashes, atomic, owner only, `repair.json.bak` from a clean previous file; a damaged entry is kept raw and answers 503 for that stash only; file and backup both unreadable: repair settings are off and nothing is overwritten).
- **Heartbeat** body: `repairHash` (40 hex, `null` = none, absent = a bot without passes) and `repairPass` (an object, `null` clears, absent keeps; both need `player`): `{ v, state: off|idle|waiting|running|yielding|blocked, why: null|disabled|no_settings|reserve|no_candidates|order|player_near|foreign|sorter_off|offline|cooldown|inventory_full|kit_conflict, pass, trigger: auto|manual|null, progress: {done,total}|null, candidates: {mend, mendBottles, needsRepair, inShulkers, inBoxes?, boxes?}, bottles: {stash, reserve, usable}, runDone, stopDone, reportHash }` (whole numbers >= 0; a wrong type or value is a `400`; no positions). Reply: `repairHash` always, `repairSettings` (the full settings object above) only when the body had `repairHash` and it differs (`null` counts as different), `repairRun` / `repairStop` (an id, increasing integers from Unix seconds like `rescan`) while a website request is newer than the bot's `runDone` / `stopDone`, `needRepairReport` (true only when the body's non-null `reportHash` isn't the one the server holds). An unreadable settings file leaves out `repairHash` and `repairSettings`. All of this is in memory: after a restart a bot is unknown until its next heartbeat.
- **`POST /api/v1/repair/report`** (signed like the index, owner check `409 { owner }`, at most 512 KiB `413`, `player` required, unknown stash `400 "unknown stash: send a heartbeat first"`): `{ version: 1, server, stash, player, hash, pass: { id, trigger, state: running|done|yielded|stopped|failed, startedAt, endedAt, threshold, repaired, partial, bottlesUsed, rehomed, toNeedsRepair, skipped, left, boxesOpened?, boxesFailed?, skippedBy: { reason: count } }, items: [ { key, status: repaired|partial|skipped|needs_repair, reason, from, to, damage, maxDamage, box } ], containers?: [ kit-report container entries ] }`. At most 200 items; `key` an item key; `reason` one of `no bottles`, `reserve`, `interrupted`, `in a shulker`, `no room`, `stopped`, `order`, `cooldown`, `in a named shulker`, `in a kit`, `box cap`, `box failed`, `player near`, or null; `from`/`to` container ids or null; `box` a shulker key `s:...` or null. Kept in memory only (at most 64 stashes), never logged; labels have control and format characters dropped and are cut to 32.
- **Browser routes** (stash scope: `404` outside it; forced streamers get `403 { permission: "streamer" }` on all five; no sudo; bodies at most 4 KiB; `429 { error, retryAfter }` with `Retry-After`): `GET /api/v1/repair?server=&stash=` (`view`), `POST /api/v1/repair/settings { server, stash, enabled, auto, threshold, reserve, shulkers? }`, `POST /api/v1/repair/chest { server, stash, container | null }`, `POST /api/v1/repair/run { server, stash }`, `POST /api/v1/repair/stop { server, stash }` (all `settings` and `view`). 60 settings or chest writes and 6 run or stop requests per user per minute. A chest needs a container list from the newer of the kit report and the repair report (`409 "the bot hasn't reported its containers yet"`), a pickable container (`400 "that container can't take items"`) that is not reserved for kits (`400 "that container is reserved for kits"`: the kits chest, rule targets, type, category and template chests); clearing always works. The kit chest, rule, type chest and template chest routes answer `400 "that container is the needs-repair chest"`. `run` answers `409 { error: "this bot can't run repair passes" }` when the stash's last heartbeat had no `repairHash`. Out-of-range values `400`, unreadable file `503`. Audit actions `repair.settings`, `repair.chest`, `repair.run`, `repair.stop`. Every route answers with the GET body.
- **GET body:** `{ server, stash, rev, hash, settings: { enabled, auto, threshold, reserve, needsRepairChest, shulkers }, needsRepairChestStatus: unset|ok|full|missing|reserved, bot: { supports, synced, online, state, why, progress, trigger, runPending, stopPending, candidates, bottles }, estimate, lastPass, items, containers, limits }`. `bot.supports`: the last heartbeat had `repairHash`; `bot.synced`: it equals `hash`; `runPending` / `stopPending`: the request id while the bot hasn't acknowledged it, else null. `estimate` (display only; the bot's `candidates` are authoritative): from the index and the stored threshold, `{ mend: { items, bottles, inShulkers, inShulkersBottles }, needsRepair: { items, inShulkers }, bottles: { stash, openOrders, reserve, usable } }` (`inShulkers` = contents of shulker groups x group count; bottles = plain key count less open orders' reservations), or null without an index. `lastPass` is the report's `pass` plus `receivedAt`; `items` and `containers` (id, label, size, free, pickable, full) come from the reports.
- **`GET /api/v1/stashes`** entries gain `repairPass: { supports, synced, enabled, state, why }` (no positions; also served to forced streamers) or null when the settings can't be read. The v1.2 `repair` flag is unchanged.
- **Compatibility:** a v1.2 bot sends no `repairHash`: the panel says the bot can't run passes and `run` is `409`, settings are stored for later. An older server answers no `repairHash`: a bot keeps its cache or does nothing.
- **Website:** the "Repair" panel in the Kits tab (next to Stash area and Crafting table): switches (repair mode, automatic, open unnamed shulker boxes), threshold %, reserve as shulkers + stacks + bottles, the needs-repair chest picker, the bot's status in words, Run and Stop buttons, estimate tiles and the last pass. View-only users see it read-only. For a user the owner forced into streamer mode the Kits tab, and with it the panel, is hidden (the routes answer `403`); voluntary streamer mode blurs the positions.

## Additions: kit types, templates and kit orders (KIT-CONTRACT-v2)

Everything here is additive: a v1 bot, page or server ignores it and works as in v1.1. The v1 hash never covers any of it. The type table (`kits/kit-types.json`, fingerprint `c3ffa89424c6998aebcc40339e9d750df410952beb31e5e2dc05d7a59d663e3e`) and the presets (`kits/kit-presets.json`) are resources of the jar; the bots classify boxes, the server only stores and shows what they report.

- **Settings** (`kits` in the heartbeat reply, and the settings of each stash) gain `types: { hash, chests: { <category>: container }, treatAs: { <key>: <category> | "none" }, categories: [ { id, name } ], templates: [ template ] }`. A category is one of the seven types (`pvp travel redstone farming building starter other`) or a custom `c:<slug>` (at most 16 per stash, name 1..32 characters). `treatAs` has at most 200 entries. `types.hash` is the SHA-1 of `"v2\n" + v1hash + "\n"`, then `"chest <category> <container>\n"` (the seven types in type order, then custom categories by id), `"cat <id>\t<name>\n"` by id, `"as <key>\t<value>\n"` by key, `"tpl <id> <tplHash>\n"` by id. Empty settings: v1 `cf9dcdb2...`, v2 `ae46bc461e485b4ad61f4cf81983e22c21d070d0`.
- **Template** `{ id, name, category, chest | null, color | null, keep, slots[27] }`; a slot is `null` or `{ key, match, count }` (`key` an index key or `<plain id>#*`; `match` the slot's match key, equal to `key` for `#*`). `id` is `t-` and 8 lowercase hex digits (made by the server; the grammar accepts `^t-[0-9a-z]{8}$`). `name` 1..50 characters (no `§`, no control characters), unique by normalised name; `color` null, `plain` or one of the 16 dye colours; `keep` 0..27; counts 1..stack size (index, else the block-items table, else 64); no shulker boxes; at least two kinds of item; no two templates with the same contents (`400 "same contents as <name>"`); at most 50. `tplHash` is the SHA-1 of `"tpl-v2\n" + id + "\n" + name + "\n" + category + "\n" + (chest or "") + "\n" + (color or "") + "\n" + keep + "\n"` + the 27 slots joined by `","` (`""` for null, else `key + " " + match + " " + count`) + `"\n"`. On save the server fills `match` from the index entry of `key`; a plain key without one matches itself; a variant key needs `match` in the body (imports) else `400 "that item variant isn't in the stash index; pick it again or use any variant"`.
- **Index** `items[]` and a shulker group's `contents[]` entries may carry `match` (the match key, the item-key grammar; anything else is a `400`). Older bots don't send it: a plain key is its own match key.
- **Heartbeat** body: `kitsHash2` (the cached v2 hash, `null` for none, absent for a v1 bot) and `kitOrders` (`{ v, enabled, restock, craft, import? }` or `null`; needs `player`). Reply: `kitsHash2` always; `kits` when the body had `kitsHash2` and it differs from the server's, or, without `kitsHash2`, when `kitsHash` differs (v1.1). `kitOrders.craft` counts only when the bot also carries out craft orders. The capability is in memory (this run). `orders.pending` leaves out kit orders for a bot without `kitOrders.enabled`.
- **`POST /api/v1/kits/report`** (`version` stays 1) may add `classifier { version: 2, table }`, `types` on every `kits` and `carried` entry (`[ { type, count, why, template? } ]`, counts adding up to the entry's count), `others` (named non-kits: `{ key, name, container, count, why }`, at most 1024, one per key and container), `templates` (`[ { id, complete, incomplete, elsewhere, carried, missing: [ { key, match?, count } ] } ]`) and `restock { state, template, reason }`. `why` is `{ code, type, slots, covered?, kinds?, need?, minKinds?, items[<=3], words[<=4], template?, missing[<=8]? }`; codes `assortment named_assortment override template template_incomplete` (kits) and `empty stock stock_neutral no_purpose few_kinds mixed override_none` (not kits); numbers 0..27. A bad value is a `400` for the whole report.
- **Browser routes** (all `settings`, `view`, same 60 writes a minute, 4 KiB; forced streamers `403 { permission: "streamer" }`): `POST /api/v1/kits/typeChest { server, stash, type, container | null }` (any category; same 409 / 400 checks as `chest`), `POST /api/v1/kits/treatAs { server, stash, key, type | "none" | null }` (`400 "at most 200 kit overrides"`), `POST /api/v1/kits/template { server, stash, template }` (16 KiB; no `id` creates, `id` replaces, `404` for an unknown one), `POST /api/v1/kits/template/delete { server, stash, id }`, `POST /api/v1/kits/category { server, stash, id?, name }` (no `id` creates `c:<slug>`), `POST /api/v1/kits/category/delete { server, stash, id }` (`409 "used by N kits"`). typeChest and treatAs answer with the `GET /api/v1/kits` body; template and category routes with the templates body plus `id`. Audit actions `kits.typeChest`, `kits.treatAs`, `kits.template`, `kits.template.delete`, `kits.category`, `kits.category.delete`.
- **`GET /api/v1/kits`** adds `typesHash`, `types` (`[ { id, name, custom, chest, chestStatus, count } ]`, the seven then custom), `treatAs` (`[ { key, type } ]`), `others` (`[ { key, name, count, locations, why, treatAs, status } ]`; `status` `present` or `absent` for a `none` override with no such box), `report.classifier`, `bot.types` / `bot.typesSynced` and `limits.maxTreatAs`. Rows are one per (key, type, template) and gain `type`, `typeSource` (`auto override template template_incomplete`), `why`, `template`, `templateName`, `incomplete`, `treatAs`, `chain` (`[ { container, source: rule|template|typeChest|kitsChest, status: ok|full|missing } ]`), `target` (the first `ok` entry, else `null`), `targetSource`, `fallback` (the target is not the first entry). `status`: `absent`, `none`, `missing` (every entry missing), `home`, `full` (no `ok` entry, one full), `pending`. `GET /api/v1/stashes` `kits.flagged` also counts category chests that are `full` or `missing`. Because `target` is now the first usable entry, a row whose first choice is full or missing shows the fallback (v1 showed the rule or kits chest whatever its state).
- **`GET /api/v1/kits/templates?server=&stash=`** (`view`): `{ templates, categories, presets, capability: { kitOrders, restock, craft, import }, stock: { <id>: { complete, incomplete, elsewhere, carried, missing } }, restock, limits: { maxTemplates, maxCategories } }`. Each template has `effectiveChest` (the first entry of its chain, not the first usable one), `chestSource` (`rule`, `template`, `category` or `kitsChest`, or `null`; the chain `source` of the type chest is `typeChest` in `GET /api/v1/kits` and `category` here), `effectiveChestStatus`, and `resolved` on every slot (an exact variant not in the index any more is `false`). A forced streamer gets it with every container id `"hidden"`, the kit names as `"Kit 1"`, `"Kit 2"` ... (list order) and the custom categories as `{ id: "c:1", name: "Category 1" }` ... (the templates' `category` follows), so the read-only Builder still groups. **Kit template and category names are hidden from forced streamers** everywhere: in orders the snapshot's `kit.lines[].template.name` is `"Kit <n>"` (n = the template's 1-based position in the stash's current template list, the same number as in the templates body; `"Kit"` when the template has been deleted) and a custom category id is `"custom"`, `result.kit.made[].name` is `(hidden)`, and the placement error "only N complete <name> kits can be made" says `Kit <n>` for the first kit (the same number). Ids (`t-...`) are random and stay.
- **Kit orders:** a line `{ type: "kit", key: <template id>, count: 1..54 }` (permission `order`; one shulker per kit in the 54 cap). `409 { error, kit: "no_bot" | "disabled" }` unless the bot's last heartbeat this run had `kitOrders.enabled`; `400` with `kit: "deleted"` for an unknown template. `ready` = complete copies (`complete + elsewhere` of the report) less the open orders' claims; `build = count - ready`; the needs (one copy's slot totals x build, less what the chain target's incomplete copies hold) are checked against the index by match key (an any-variant slot against every key of that item) less reservations, plus `build` empty shulkers. Plain shortfalls are crafted when `kitOrders.craft`; else `400 { error: "only N complete <name> kits can be made: short <Item> x<n>, ...", line, short: [ { key, count } ] }`. The stored order gains `kit: { v: 1, lines: [ { template (no chest), count, ready, build } ], take: [ { match, count, keys: [ { key, count } ] } ] }`; `keys` is where the order will take each entry from, resolved at placement (an exact slot: the index keys with that match key; an any-variant slot: the plain key first, then the others in key order, after the exact needs), and every key is reserved as `item:<key>` like an item line, so item lines, `GET /api/v1/orders` `available`, the planner and the craft stock all see the same numbers; `take` is reserved while the order is open. A retry re-plans against the current template (`409 kit: "deleted"` when it is gone). The claim body may carry `kitOrders: 1`; without it pending kit orders are skipped. A held order that needs a capability the claim lacks (`kitOrders` for a kit order, `craftOrders` for an order with craft lines or craft steps, so also a kit order with a crafted shortfall) gets the craft grace: it stays held (claim answers `204`) and becomes `failed` only on the third such claim, more than 120 s after the first, when the server has been up for more than 120 s and no heartbeat with the missing capability came since the first, or flagless claims have gone on for more than 10 minutes since the first whatever the heartbeats say ("the bot can no longer build kits; retry the order", or the craft message when craft is what is missing). Limits: at most 16 different kits in an order, and at most 256 reserved index keys / take entries (`400` each, clear message). The report counts for `ready` (and the incomplete copies) only while it is current: the bot's latest heartbeat carries its hash in `kitsReportHash` and is no more than 10 minutes old (a bot sends a report only when its kit state changes, so the report's own age does not matter). When the bot has been quiet for over 10 minutes, or its heartbeat hash differs (or is `null`), the report is ignored and the kits are built. An incomplete copy only counts when the report's `missing` keys fit the template's slots.
- **`result.kit`** (status report, optional): `{ made: [ { template, name, wanted, ready, built, delivered } ], short: [ { template, kits, items: [ { key, match?, count } ], stored?: incomplete|returned, reason } ], notes: [ "colour_substituted" ] }`; reasons `items_short no_shulker inventory_full template_mismatch interrupted timeout cancelled cap`; lists cut at 200; a wrong shape is a `400`. Forced streamers see `made[].name` as `(hidden)`.
- **Storage:** `kits.json` entries gain optional `typeChests`, `treatAs`, `categories` (id to name) and `templates`; an older server rewriting the file drops them.

## Additions: crafting from the stash

The build planner and the Printer's stash orders can order items the stash does not hold but can make from what it holds: the stash bot takes the ingredients, crafts at a crafting table set for the stash on the website, and delivers the results with the order. This section is what the server does (the contract is `CRAFT-CONTRACT.md` v1.0). Everything is additive: older bots, servers and pages ignore the new fields and work as before, and an older bot is never given a craft order. Scope is crafting-table recipes only (shaped and shapeless); no smelting, stonecutting, smithing, dyeing shulker boxes or bundles.

### Recipe data

- Per Minecraft version, built in the same one-time pass as the icon set from the official client jar: the jar of this machine (same search as the icons) or, if none and `recipeDownload` allows (default: the value of `iconDownload`), downloaded once. When either set of a version is incomplete the jar is taken once and every incomplete set is built from it before a downloaded jar is deleted. Only `data/minecraft/recipe/*.json` and `data/minecraft/tags/item/*.json` entries are read (names `[a-z0-9_/]{1,128}`, at most 64 KiB each, 4096 recipes, 1024 tags, 16 MiB in all; an entry that doesn't match is never read and no path is built from entry bytes).
- Written to `<data>/recipes/<mc>/`: `raw/` (the entries read, plus `raw/items.json`, the item names), then `table.json`, then `manifest.json` (`{ mc, format, jarSha1, tableHash, recipes, otherStations, dropped }`), then `.complete` last. When the normaliser's `format` goes up, `table.json` is rebuilt from `raw/` at start with no jar.
- `table.json` is the shape the bots parse: `{ "version", "format": 1, "recipes": [ { "id", "type": "shaped"|"shapeless", "w", "h", "cells" | "ingredients", "result", "count" } ], "otherStations": { item: "smelting"|"blasting"|"smoking"|"campfire_cooking"|"stonecutting"|"smithing"|"transmute"|"special" } }`. Shaped recipes: `cells` row-major `w*h` (`null` = empty); shapeless: `ingredients`, in file order. Every cell is a sorted list of item ids (tags expanded, at most 8 levels). Recipes whose result has components, recipes with a crafting remainder (milk, water, lava and powder snow buckets, honey bottle, dragon's breath) and recipes with an unknown tag are left out.
- `tableHash`: lowercase hex SHA-1 of one line per recipe sorted by id, `id TAB type TAB w TAB h TAB result TAB count TAB body LF` (UTF-8). `body` of a shaped recipe: the cells joined by `|`, a null cell as `-`, a cell's alternatives joined by `,`; of a shapeless one: each ingredient's alternatives joined by `,`, those strings sorted and joined by `|`. `w` and `h` are 0 for shapeless recipes. `otherStations` is not hashed. The tests assert 1.21.4 = `202980a8ab599d1b4ae340108e0d734a700f74bc` (913 recipes) and 1.21.11 = `190d2f3ed0f133215ffaf5cfadda5e6f6888cc59` (1007 recipes) against a client jar when one is on the machine.

### `GET /api/v1/bot/recipes?mc=<version>` (signed, like every `bot/*` request)

The version's `table.json` with `ETag: "<tableHash>"`; `If-None-Match` with that tag (or `*`) gets `304` with no body. `404` when the server has no recipes for the version (none built, failed, or downloading is off and no jar is on the machine); `503` with `Retry-After` while they are being built; `400` for a bad `mc`; `429` with `Retry-After` above 6 requests a minute per bot (the bot id in the `X-SM-Auth` header; every address also has an overall limit of 60 a minute). Bots cache the table, recompute its hash and fetch again when the heartbeat reply's `recipesHash` differs from the cached one.

### Heartbeat (`POST /api/v1/heartbeat`)

Optional body fields (a wrong type is a `400`; an absent key keeps the stored value, `null` clears it):

```json
{ "craftOrders": { "v": 1, "enabled": true, "recipes": "<tableHash, 40 hex>", "mc": "1.21.4", "table": "ok",
                   "maxFills": 2048, "seen": ["118,64,-30"] },
  "craftHash": "<40 hex> | null" }
```

`craftOrders` is sent only by a bot that can carry out craft orders (it has a valid recipe table). `v` whole number >= 1; `table` is `unset`, `ok`, `unloaded`, `not_table`, `unreachable`, `other_dimension` or `auto` (no website table is set and the bot will find, place or craft one itself); `maxFills` 1..100000; `mc`, `seen`, `tableWhy` and `botTable` are optional. `seen`: at most 8 positions, for the page's quick pick. `tableWhy` (null or absent when the table is fine): why the bot cannot use a table, one of `no_area`, `no_dimension`, `wrong_dimension`, `too_far`, `not_table`, `no_stand`, `path_failed`, `not_loaded`, `no_material` (no table is known or found, none is in the stash, and the stash has no planks or logs to make one; another value is a `400`). `botTable`: `{ "pos": "x,y,z", "source": "found" | "placed" | "crafted" }` or null, the table the bot itself uses when no website table is set. The capability is kept in memory only (after a server restart no craft order is placed until the bot's next heartbeat). `craftHash` is the hash of the craft settings the bot has cached, `null` when it has none. `player` is required whenever `craftOrders` or `craftHash` is sent (`400` otherwise), and the `craft` settings object (it holds coordinates) is only in the reply of a heartbeat that passed the stash-owner check. The existing `crafting` object (shulker-box crafting) is unchanged.

Reply additions:

```json
{ "craftHash": "<server's hash for the stash>",
  "craft": { "rev": 3, "hash": "<40 hex>", "table": { "x": 118, "y": 64, "z": -30, "dimension": "minecraft:overworld" } },
  "recipesHash": "<tableHash of the stash's version>" }
```

`craftHash` is always sent (unless the settings file is unreadable: then `craftHash` and `craft` are both left out and bots keep what they have). `craft` only when the body had the `craftHash` key and it differs (`null` counts as different); `table` is `null` when none is set. `hash` is the SHA-1 of `"craft-v1\n" + (table ? x + "," + y + "," + z + "," + dimension : "") + "\n"`. The never-use list is server side only. `recipesHash` is sent when the recipes of the stash's Minecraft version are ready. `orders.pending` does not count craft orders for a bot that has not sent a `craftOrders` capability.

### Craft lines in orders

`POST /api/v1/orders` (and retry, and the planner's parts) accept a third line type: `{ "type": "craft", "key": "minecraft:spruce_stairs", "count": 64 }`. `key` is a plain item id (`^[a-z0-9_.-]+:[a-z0-9_./-]{1,128}$`) that has an eligible recipe; `count` 1..2147483647. Lines are merged per key and count against the 200 lines; a craft line takes `ceil(count / maxStack)` slots in the 54-shulker check (stack size from the index, else the block-items table, else 64). At placement the server plans all craft lines of the order together (the order's item lines are the direct takes of the same ingredients):

- `409 { ok:false, error, line, craft: "<code>" }` when the stash cannot take craft orders: `no_recipes` (no recipes ready for the stash's version), `no_bot` (no capability heard since the server started, or the bot reported `enabled: false`), `recipes_differ` (the bot's `recipes` hash is not the server's), `no_table` (no table set on the website, and the bot cannot find, place or craft one: it does not report `auto`, or `ok` with a `botTable`) or `table_bad` (the bot's `table` status is not `ok` or `unloaded`, or the bot has not picked up the current settings yet, or it says `unloaded` for a table it has never reported as `ok` with these settings). Orders without craft lines are never affected.
- `400 { error: "only N craftable for <Name>: <reason>", line }` when a line is not fully craftable (each line is all or nothing); `400 { error: "this bot crafts at most N grid fills per order", line: null }` above the bot's `maxFills`; `400` for a key with no eligible recipe or a plan of more than 64 steps or 64 ingredients.
- **Eligible recipes:** in the table, result not a shulker box or bundle, id not `minecraft:dye_*`, and every cell keeps an alternative after removing never-use items (shulker boxes, bundles, the stash's never-use list) and the recipe's own result. The planner is deterministic and server side: costs are estimated from the stash, alternatives are tried cheapest first, at most 4 crafting levels (the item asked for is depth 0; an item with something left to make at depth 4 fails with "too deep", so at most 4 crafts in a chain including the item itself, and what is at depth 4 can only come from stock), with budgets (50,000 steps a row, 1,000,000 and 3 s a plan). At most 3 plans are computed at once on the server; when all slots are taken the request gets `503` with `Retry-After` (orders with craft lines, quotes, and planner views that need a new plan). A user's planner views that need a new plan are limited to 30 a minute (`429`). Crafting fills the amount asked for, never rounded up; what is made over goes back to the stash.
- **Every order JSON** gains `craft` (`null` without craft lines), computed at placement and not changed later; no positions, so it is safe for forced-streamer users:

```json
"craft": { "v": 1, "mc": "1.21.4", "recipes": "<tableHash>",
  "lines": [ { "key": "minecraft:spruce_stairs", "count": 8 } ],
  "steps": [ { "recipe": "minecraft:spruce_planks", "result": "minecraft:spruce_planks", "count": 4, "fills": 6,
               "inputs": [ { "id": "minecraft:spruce_log", "count": 6 } ] } ],
  "take": [ { "id": "minecraft:spruce_log", "count": 6 } ],
  "leftovers": [ { "id": "minecraft:spruce_planks", "count": 3 } ],
  "fills": 11 }
```

- **Reservations:** `take` is reserved while the order is open (`pending`, `accepted`, `in_progress`) like item lines: order placement, `GET /api/v1/orders` `available` and the planner's `available` all subtract it from the plain item key (`id#hash` keys never count).
- **Claim** (`POST /api/v1/orders/claim { server, stash, player, craftOrders: 1 }`): without `craftOrders >= 1` (an older bot, or the flag absent, `0`, not a number) pending orders with a craft line are skipped and stay pending (the next claimable order is returned), and a held (`accepted`/`in_progress`) order with craft lines becomes `failed` with the message `the bot can no longer craft (was it downgraded?); retry the order`, which releases its reservations. That happens only when no heartbeat with an enabled `craftOrders` came in the last 2 minutes, or on the second flagless claim in a row; before that the claim answers `204` and the order stays held (a bot that is restarting, whose recipe fetch got a `503` or `429`, keeps it). A claim with the flag resets the count.
- **Result** (`POST /api/v1/orders/<id>/status`, `result.craft`, optional; a wrong shape is a `400`):

```json
"craft": { "fills": 11,
  "made": [ { "key": "minecraft:spruce_stairs", "wanted": 8, "made": 8 } ],
  "short": [ { "key": "minecraft:spruce_fence", "count": 3, "reason": "ingredients_short" } ],
  "used": [ { "id": "minecraft:spruce_log", "count": 6 } ],
  "returned": [ { "id": "minecraft:spruce_planks", "count": 3 } ] }
```

Whole numbers >= 0, at most 200 entries per list (more are dropped), keys cut to 256 characters, `reason` to 60. Reasons: `no_table`, `table_missing`, `table_blocked`, `ingredients_short`, `inventory_full`, `recipe_mismatch`, `grid_stuck`, `timeout`, `interrupted`, `cap`, `cancelled`; any other reason, and a key that is not an item key, is shown as `(hidden)` to forced-streamer users. Crafting never fails an order by itself: short lines are delivered as far as they were made and the bot adds a note to `message`.

### Craft settings (per server + stash)

`stash-manager-data/craft.json` (or `craft.json` in the `--data` folder): `{ version: 1, stashes: [ { server, stash, rev, table, neverUse, updatedAt } ] }`, at most 64 stashes, written atomically (owner only) after copying the old file to `craft.json.bak`; loaded like `kits.json` (a damaged entry only switches crafting off for that stash: `503`, no craft fields in its heartbeat reply; neither file readable: all routes answer `503`, nothing is written). `table` is `{ x, y, z, dimension }` or `null` (`|x|, |z| <= 30000000`, `dimension` like `minecraft:overworld`); `neverUse` at most 200 plain item ids. A stash dropped from the server's memory also loses its craft entry.

**Rules for a table set through `POST /api/v1/craft/table`** (`400` with the reason otherwise; clearing is always allowed): `y` from -64 to 320; `dimension` is the stash index's `dimension` (when the index has none: `minecraft:overworld`, `minecraft:the_nether` or `minecraft:the_end`); and the position is within 64 blocks (straight line) of an anchor: a kits chest or kit rule container, the bot's delivery container (same dimension), or a table the bot reported in `craftOrders.seen`. With no kit or delivery anchor, only a position the bot reported in `seen` is accepted.

Browser routes (website login; a stash outside the user's scope answers `404`; `404` also for a stash the server knows nothing about):

| Route | Guard | Permission |
|---|---|---|
| `GET /api/v1/craft?server=&stash=` | SESSION | `view` |
| `POST /api/v1/craft/table { server, stash, table \| null }` | BROWSER_WRITE | `settings` (and `view`) |
| `POST /api/v1/craft/never-use { server, stash, ids }` | BROWSER_WRITE | `settings` (and `view`) |
| `POST /api/v1/craft/plan { server, stash, want: [ { id, count } ], direct?: [ { id, count } ] }` | BROWSER_WRITE | `order` or `plannerOrder` |

- Bodies are at most 4 KiB (`413` above); `never-use` and `plan` take 16 KiB (200 ids fit), at most 200 entries. At most 60 settings writes and 30 quotes per user per minute, then `429 { error, retryAfter }` with `Retry-After`. Bad coordinates or ids are `400`; an unreadable file is `503`. Audit actions `craft.table` and `craft.neverUse`.
- The three settings routes answer the GET body; a **forced-streamer** user gets `403 { permission: "streamer" }` on all three (positions). The quote carries no positions and is allowed.

```json
{ "server": "...", "stash": "...", "rev": 3, "hash": "<40 hex>",
  "table": { "x": 118, "y": 64, "z": -30, "dimension": "minecraft:overworld" },
  "tableStatus": "unset|auto|ok|unloaded|not_table|unreachable|other_dimension|unknown",
  "tableWhy": null,
  "botTable": { "pos": "120,64,-31", "source": "placed" },
  "neverUse": [ "minecraft:stone" ],
  "bot": { "supports": true, "enabled": true, "online": true, "synced": true, "recipesMatch": true, "maxFills": 2048 },
  "recipes": { "mc": "1.21.4", "state": "ready|building|failed|none" },
  "seen": [ "118,64,-30" ] }
```

`tableStatus` is `unset` without a website table, `unknown` while the bot has not cached the current settings (`synced` false) or has not reported, else the bot's own word. `bot.maxFills` is `null` without a capability.

**No website table (`table: null`):** a bot that reports `table: "auto"` (or `"ok"` together with a `botTable`) and has picked up the current settings can take craft orders: `canOrder` is true, `tableStatus` is `auto`, and `message` reads "The bot will find, place or craft a crafting table." A bot with crafting on but no table route sends `craftOrders` with `table: "unset"`, `enabled: true` and a `tableWhy`: `canOrder` stays false with `no_table` (never `no_bot`), and the message carries the why. A bot that reports `auto` but has not picked up the current settings yet (its `craftHash` differs) gets `no_table` with the message "Waiting for the bot to pick up the latest settings."; with a website table set, an `auto` report is shown as `unknown`. A website table always takes priority: once one is set, the bot's word about that table decides, and a `botTable` is not used. `tableWhy` is returned as `tableWhy` in `GET /api/v1/craft`, in the `craft` entry of `GET /api/v1/stashes` and in the planner's `craft` object (no positions), and its sentence is appended to the `table_bad` message. `GET /api/v1/craft` also returns `botTable: { pos, source } | null` (a position, so the route stays closed to forced-streamer users; the stash list and the planner never carry it).

**`POST /craft/table` refusals** name the reason: the height ("table.y is 400, but ... -64 to 320"), the dimension ("table.dimension is X, but this stash is in Y"; the index's dimension is compared with or without the `minecraft:` prefix), the distance ("table is 200 blocks from the nearest of the stash's known spots (the delivery container at x,y,z); it must be within 64 blocks"), or that no stash area is known yet (no kits chest, kit container or delivery container: only a table the bot has seen is accepted).

**`POST /api/v1/craft/plan`** (the Printer's quote; nothing is reserved): plans the wanted items as an order's craft lines would be planned right now, in the order given (duplicates merged). `direct` (optional, an addition to the contract) is what the same order takes as itself, so the quote and the order see the same pool.

```json
{ "ok": true, "craft": { "canOrder": true, "reason": null, "message": null },
  "lines": [ { "id": "minecraft:spruce_slab", "want": 6, "craftable": 6, "reason": null,
               "uses": [ { "id": "minecraft:spruce_log", "count": 1 } ] } ],
  "take": [ { "id": "minecraft:spruce_log", "count": 1 } ], "leftovers": [ { "id": "minecraft:spruce_planks", "count": 3 } ],
  "fills": 2, "truncated": false }
```

When `canOrder` is false, `reason` is a placement code above and the numbers are still given (except `no_recipes`: every `craftable` is 0).

**`GET /api/v1/stashes`** entries gain `craft: { supports, enabled, synced, recipesMatch, tableStatus, tableWhy, canOrder, reason, maxFills }`, or `null` when the settings file is unreadable. No positions.

### Build planner additions

- Option `craft` (bool, default false) in the query/body of view, dry run and order, part of the options key (locked with the others once a part is placed). With `craft: true` and a stash that can take craft orders, every row's `craftable` becomes a craft line, and the split puts the craft lines first (in the first parts) and keeps each part's fills at or below the bot's `maxFills`; each part is planned with what the earlier parts take. A later part whose ingredients are gone gets a `400` at placement, as item lines do today. `options` in the plan JSON gains `craft`.
- The plan JSON gains `craft` (null without a stash): `{ available, canOrder, reason, message, recipes: { mc, state }, tableStatus, tableWhy, bot: { supports, recipesMatch }, truncated }`. `available` false means the stash's version has no recipes ready (or the settings are unreadable); the rows then carry no craft fields. `reason` is a placement code, or `null` when `canOrder`.
- Rows gain (when `available`): `craftable`, `missingAfterCraft` (`max(0, need - available - craftable)`), `craftStatus` (`none` nothing to make, `full`, `partial`, `short` has a recipe but made 0, `no_recipe`, `station` needs a furnace or similar, `excluded`), `craftReason`, `craftUses` (this row's stash takes, at most 32), `craftSteps` (at most 16, `{ recipe, result, made, fills, inputs: [ { id, count } ] }`), `craftLeftovers`. `missing` is unchanged (`need - available`).
- `totals` gain `craftable`, `craftKinds`, `missingAfterCraft`, `craftFills` and `wantWithCraft` (`want` plus `craftable`). `totals.want` is the item lines only and never includes craft lines. Dry-run parts gain `craft: { lines, fills, take }` (null for a part with no craft lines), the dry run gains `craft`, and each `missing` entry gains `missingAfterCraft`. The planner's numbers are cached on the plan until the index, the reservations, the settings, the recipes or the options change.

## Additions: stash area

The stash area (the box the bots keep their stash in) can be set, expanded and cleared from the website; the bots take it on their next heartbeat. This section is what the server does (the contract is `AREA-CONTRACT.md`). Everything is additive: older bots, servers and pages ignore the new fields, and an older bot keeps its own area.

- **Area object:** `{ "box": [minX, minY, minZ, maxX, maxY, maxZ], "dimension": "minecraft:overworld" }`, or `null` (no area). Inclusive block coordinates, `min <= max`, whole numbers, `|x|` and `|z|` up to 30,000,000. Size caps (width × height × depth) 1..256 × 1..128 × 1..256. Y limits: `minecraft:overworld` -64..319, `minecraft:the_nether` and `minecraft:the_end` 0..255, any other dimension -64..319. `dimension` matches `^[a-z0-9_.-]+:[a-z0-9_./-]{1,100}$` and, from the website, must equal the stash's dimension from its index (compared with or without `minecraft:`); a stash with no known dimension accepts no area from the website.
- **`hash`:** lowercase hex SHA-1 of `"area-v1\n" + (area ? minX,minY,minZ,maxX,maxY,maxZ,dimension : "") + "\n"` (numbers joined by `,`). Vectors: `"area-v1\n100,60,-40,163,90,23,minecraft:overworld\n"` gives `2a5139e37493cc1588efdc9d86155c55374e0fc1`; no area gives `382410b4646b2c35b8ce6911faf42958ec60b6e2`.
- **Heartbeat** (`POST /api/v1/heartbeat`), optional body field from new bots (`player` is required with it; a wrong type in the envelope is a `400`, but an `area` that is out of the contract - shape, caps, Y limits - is not: it is treated as invalid, never stored, logged once per stash, and the rest of the heartbeat is handled):

```json
"area": { "v": 1, "hash": "<hash of the area the bot uses now>", "area": { "box": [..], "dimension": ".." } | null,
          "derived": [6 ints] | null, "edited": false,
          "pending": null | { "hash": "<40 hex>", "why": "busy" | "far" | "dimension" },
          "containersOutside": 0 }
```

  `derived` is the bot's working box when it has no area (the server drops it when it is not a box of the contract's size and height in the stash's dimension); `edited` is true once after the area was changed in game, until a reply's `areaHash` equals `hash`; `pending` is a website area received but not applied yet (`busy`: waiting for between tasks, `far`: the bot is more than 128 blocks away horizontally, `dimension`: it is in another dimension). On a heartbeat that passed the owner check the server, in this order: (1) `edited` true: stores the bot's area (`by: "bot"`, rev + 1, audit `area.bot`); (2) else a stored entry whose hash differs from the body's hash: replies `area` (the stored object, or `null` when it was cleared) and `areaHash`; (3) else no stored entry and the bot has an area: stores it (`by: "bot"`); (4) replies `areaHash` always (the stored hash; an older bot without the `area` key gets only `areaHash`, never `area`). When `area.json` can't be read both are left out. Every area taken from a bot (an edit or an adoption) passes the website's checks (the stash's dimension, the distance from the stash's home, below) and at most 3 stored changes a minute per stash are taken; one that fails is ignored. An `edited: true` heartbeat counts only when its `hash` differs from the hash last stored from that bot's edit, so a repeated one cannot overwrite a later website change. Adoption never replaces an entry that appeared meanwhile. A change that leaves the hash the same changes no revision and writes no audit entry. Whichever change reaches the server last wins. Bots apply a received area only between tasks and never farther than 128 blocks from where they stand.
- **Home reset limits (contract note):** with no delivery container the owner can move a stash's home at most 128 blocks per reset and one reset per hour, so at most 128 blocks an hour; the area's centre only counts when the saved area is in the stash's current dimension. The hour is reserved atomically before the reset is made (and given back if it fails) and is kept in `area.json` (`homeResets`), so a restart does not clear it (an entry dated more than a day ahead of the clock is dropped on load).
- **Storage:** `stash-manager-data/area.json` (or the `--data` folder): `{ version: 1, stashes: [ { server, stash, rev, area, by: "website" | "bot", user?, updatedAt } ], homes: [ { server, stash, x, y, z } ], homeResets: [ { server, stash, at } ] }`, at most 64 stashes and 64 homes, written atomically (owner only) after copying the old file to `area.json.bak`; loaded like `kits.json` (a damaged entry only switches areas off for that stash: `503`). A stash dropped from the server's memory loses its entry. A website clear for a stash with no entry still stores an entry with `area: null`, so a bot that has an area is told to drop it.
- **Browser routes** (website login; a stash outside the user's scope answers `404`):

| Route | Guard | Permission |
|---|---|---|
| `GET /api/v1/area?server=&stash=` | SESSION | `view` |
| `POST /api/v1/area { server, stash, area: { box, dimension } \| null, rev? }` | BROWSER_WRITE | `settings` (and `view`) |
| `POST /api/v1/area/home { server, stash, reset: true }` | BROWSER_WRITE | owner, Admin unlocked |

  Bodies are at most 4 KiB (`413`). At most 30 writes per user per minute (`429 { error, retryAfter }` with `Retry-After`). Audit actions `area.set` and `area.clear`. `400` with the reason for a bad shape, the caps ("that area is 300×20×40; the largest is 256×128×256"), the Y limits or the dimension. **Distance:** every stored area (from the website or a bot) must be within 256 blocks horizontally of the stash's **home**, or of its delivery container when that lies within 256 blocks of the home: else `400 "that area is <n> blocks from the stash (the limit is 256)"`. The home is a point stored per stash (with the dimension it was set in): it is set once and is never moved by bot reports, derived boxes, kit chests or area changes, so a series of small steps cannot walk an area away. It comes from the delivery container; until the stash has one it can be **provisional** - the centre of the containers of the first kit report (only when they lie within 256 blocks of each other in X and Z) or, for a bot's own first area, that area's centre. A provisional home is replaced once, when a delivery container first appears, and dropped when the stash's dimension changes; a delivery-based home whose dimension no longer matches is flagged (`homeStale`) and nothing is stored until the owner resets it. A heartbeat that carries an in-game edit (`edited: true`) never sets a home, and an edit is never an adoption. With no home yet (the website only) `409 "the bot hasn't reported where the stash is yet"`. Clearing needs no check. `rev` (optional) is the revision the page was showing: a save against an older one is `409 "the area changed meanwhile; reload the page and try again"`. **`POST /api/v1/area/home { reset: true }`** is owner only, needs Admin unlocked (`403 "confirm password"` otherwise) and works once an hour per stash (`409`): it sets the home at the delivery container; without one, at the stored area's centre but only when that is within 128 blocks of the current home (else `409`). Audit `area.home`; it answers the GET body. Unknown stash `404`, unreadable file `503`. A forced-streamer user gets `403 { permission: "streamer" }` on both routes. GET (and every POST reply):

```json
{ "server": "...", "stash": "...", "rev": 3, "hash": "<40 hex>", "area": { "box": [..], "dimension": ".." } | null,
  "by": "website" | "bot" | null, "user": "<website user>" | null, "updatedAt": 1767225600000 | null,
  "dimension": "<stash dimension, namespaced, or null>", "home": [x, y, z] | null, "homeSource": "delivery" | "bot" | null, "homeStale": false,
  "bot": { "supports": true | null | false, "online": true, "synced": true, "applied": { "box": [..], "dimension": ".." } | null,
           "derived": [6 ints] | null, "pending": null | { "why": "busy" }, "containersOutside": 0 },
  "impact": { "reported": 120, "outside": 3 } | null,
  "limits": { "maxW": 256, "maxH": 128, "maxD": 256, "yMin": -64, "yMax": 319, "farBlocks": 256 } }  // yMin and yMax are those of the stash's dimension
```

  `bot.supports` is true while the bot has sent the `area` key within the last 2 minutes, `null` (unknown) when its last report is older or the server started less than 2 minutes ago, `false` when it never reported; `synced` means its reported hash equals the stored one. `impact` counts the containers of the latest kit report that lie outside the stored area (null without a report or without an area). `user` is an addition to the contract (who set it on the website). **`GET /api/v1/stashes`** entries gain `area: { set, synced, pending }` (no positions), or `null` when the file is unreadable.
- **Craft table check:** a stored area counts as a known stash spot for `POST /craft/table`: the table must be within 64 blocks of the area's box (0 inside it); the refusal then names "the stash area".
- **Website:** the Kits tab has a "Stash area" panel (min/max fields, size against the caps, expand by N per side, use the bot's current area, clear, Save/Cancel; the draft lives only in the page until Save) and the Live page draws the stored area and the draft on the map and lets you drag a rectangle to set X and Z.

## Enchant orders (ENCHANT-CONTRACT v1.0)

The orderer picks an item and the enchantments; the server checks them against the game rules, finds the cheapest anvil order (fewest XP points, every step at most 39 levels) and reserves the base item, the books, the anvils and the XP bottles; the stash bot withdraws everything, levels up with the held bottles and does the anvil work. This section is what the server does (the contract is `ENCHANT-CONTRACT-v1.0.md`, draft 3.4; the tables and the optimizer are the `qwerte-core` library, `com.porter.qwerte.core.enchant`, the same code the bots plan with). Everything is additive: older bots, servers and pages ignore the new fields, and a bot that sends no `enchant` heartbeat key is never given an enchant order. Ids on the wire are namespaced (`minecraft:diamond_sword`, `minecraft:sharpness`); `take` and `materials[].key` are index keys exactly as the bot's index reports them.

### Index additions (bot to server)

Item entries of `POST /api/v1/index` gain optional fields: `ench: [ { "id": "minecraft:sharpness", "lvl": 5 } ]` (the stored enchantments of a book or an enchanted item), `rc: <int>` (one stored repair cost for the stacks of the entry; absent = unknown, planned as 0 and the plan is an estimate) and `named: true` (custom-named: never offered as a base). **An index post that carries any of the three requires `player` equal to the stash owner** (`400` without `player`, `409 stash name in use` for another player, compared case-insensitively like the owner rule). Index values are unverified claims; a bad field is **dropped, not the entry** (nothing about it is echoed): `ench` is an array of at most 64 `{id, lvl}` with `id` matching `^[a-z0-9_.:/-]{1,64}$` and `lvl` a whole number 1..255 (a duplicate id, after the `minecraft:` prefix is stripped, drops the whole field), `rc` a whole number 0..2147483647 (parsed with `BigDecimal`), `named` a boolean. What stays is rewritten in one form (`minecraft:` ids, whole numbers). A level above the table's maximum is kept, and the entry is unplannable; an entry without `ench` is unplannable (an order refuses it as `no_base`; the plan route plans it as a fresh item, flagged as an estimate).

### Heartbeat (`POST /api/v1/heartbeat`)

Optional body field (a wrong type is a `400`, an absent key keeps the stored value, `null` clears it; `player` is required with it and must match `^[A-Za-z0-9_]{3,16}$`; memory only, so after a server restart no enchant order is placed or handed out until the bot's next heartbeat):

```json
{ "enchant": { "v": 1, "enabled": true, "mc": "1.21.11", "carry": 30 } }
```

`v` must be the whole number `1`, `enabled` a boolean, `mc` one of `1.21.4`, `1.21.11` (checked before any table is loaded), `carry` optional, a whole number 0..36: the bot's free carry slots after its gear is stowed (absent: 20 at plan time). Reply addition, to an enchant-capable or repair-capable bot:

```json
{ "enchant": { "bottlesReserved": 14 } }
```

`bottlesReserved` is the total of the XP bottles reserved by every open enchant order of the stash (pending, accepted, in progress, and a force-cancelled one whose reservations are still held; orders awaiting curse approval reserve nothing). The bot passes it as `heldOrderBottles` to `RepairRules.usable`, so repair cannot spend them (older bots ignore the field: their repair can dip into those bottles, and an enchant run then tops up or waits). The server's own repair estimate (`GET /api/v1/repair`, `estimate.bottles.openOrders` and `usable`) already counts them, because the enchant `take` is part of the order reservations. `orders.pending` counts an enchant order only for a bot with the capability, and never counts an order awaiting curse approval. `GET /api/v1/stashes` entries gain `enchant: { "supports": true, "mc": "1.21.11", "canOrder": true }` (`supports`: a fresh enabled block; `canOrder`: the claim rules below hold for the stash's owner; no positions, no names).

### Claim and status (`POST /api/v1/orders/claim`, `/orders/{id}/status`)

An **enchant order is handed out only when all of these hold**: the claim carries `enchantOrders >= 1`; a stored heartbeat `enchant.enabled` exists and is newer than 2 minutes; `enchant.mc` equals the index's `mcVersion`; and the claim's `player` equals both the stash owner and the heartbeat's `player`. A claim without them is answered as if the bot had no capability (the held-order downgrade grace of craft and kit orders covers enchant orders: after more than 120 s and three claims without the capability the held order becomes `failed`, "the bot can no longer enchant; retry the order"). The server records `claimedBy` (the claim's player; never sent to a page or a bot) and **requires the same `player` on every `status` call of that order** (`409` otherwise, nothing changes); a held enchant order is handed back only to the player that claimed it. In the server the claim's flags are one capability record (`OrderStore.Claim`).

### Enchant lines in orders

`POST /api/v1/orders` and retry accept a fourth line type: `{ "type": "enchant", "key": "minecraft:diamond_sword", "count": 1..4, "enchants": [ { "id": "minecraft:sharpness", "lvl": 5 } ] }`. **Enchant lines never merge.** Caps (checked before planning): at most 8 enchant lines, at most 16 items over all enchant lines, at most 12 ordered enchantments per item, `take` at most 256 entries. At placement the server plans every item with `qwerte-core` (stock sorted by key first; every plan is replayed with `Planner.verify`), draws the materials from the index entries that hold them, limited to **count minus what open orders reserve**, and reserves the `take`:

```json
"enchant": { "v": 1, "mc": "1.21.11",
  "lines": [ { "key": "minecraft:diamond_sword", "count": 1, "enchants": [ { "id": "minecraft:sharpness", "lvl": 5 } ],
               "plans": [ { "materials": [ { "kind": "base", "key": "minecraft:diamond_sword", "ench": [], "rc": 0 },
                                           { "kind": "book", "key": "minecraft:enchanted_book#a1111111", "ench": [ { "id": "minecraft:sharpness", "lvl": 5 } ], "rc": 0 } ],
                            "steps": [ { "left": 0, "right": 1, "cost": 5, "rc": 1 } ], "levels": [ { "id": "minecraft:sharpness", "lvl": 5 } ],
                            "extras": [], "curse": [], "totalLevels": 5, "points": 55, "bottles": 14, "finalRc": 1 } ] } ],
  "take": [ { "key": "minecraft:diamond_sword", "count": 1 }, { "key": "minecraft:enchanted_book#a1111111", "count": 1 },
            { "key": "minecraft:experience_bottle", "count": 14 }, { "key": "minecraft:anvil", "count": 2 } ],
  "curse": [] }
```

`plans` has one plan per item (each is planned against the stock the earlier items left). Items are numbered `0..materials-1` (0 the base) and `materials + i` is the result of step `i`; left is the target, right the sacrifice. `take` is reserved as `item:<key>` refs while the order is open (so item lines, the planner and repair see the same numbers) and released when the order finishes. Anvils: 2 when the bot reports no anvil standing at the stash (`naming.anvil`), else 1 spare (what the stash holds, at most that many). Bottles are `minecraft:experience_bottle`, summed over the items; the order passes only when the stash holds the repair reserve (`repair.json`, default 1,728) **plus** the order's bottles (`xp_short`). Each item needs `1 + books + 2 + 1 + ceil(bottles / 64) <= carry` slots (`inventory_full`). Each item takes one slot in the 54-shulker check. The line is stored with `enchants`; the order JSON keeps `lines[].enchants` and `enchant` across a restart. Retry plans again against the current index. **Per order** (create, retry and accept-curse): at most 24,000,000 work units and a 4 s clock summed over the items, else `too_complex` and nothing is stored. Every plan call uses the library's default work limit. An undamaged base is preferred when two are alike.

Errors follow the craft precedent (`{ ok: false, error, line, enchant: "<code>" }`): `409` with `no_bot` (no enchant-capable bot right now) or `mc_unsupported` (the stash's version has no table); `400` with the order-check codes `nothing_wanted`, `unknown_enchant`, `invalid_level`, `duplicate`, `wrong_item`, `conflict`, `bad_request` (a malformed line, a count outside 1..4) and the planning codes `no_base`, `base_unusable`, `books_short`, `too_expensive`, `too_complex`, `xp_short`, `inventory_full`. A plan that rests on an index entry without `rc` is an estimate and cannot be ordered (`books_short`, with the reason in `error`). Messages never repeat unvalidated input. `503` with `Retry-After` when order planning has no slot (its own 2 slots, separate from the plan route's pool). Order create, retry and accept-curse each count once against the user's 12 a minute and run one at a time per user (`429 { ok:false, error, retryAfter }` with `Retry-After`); the per-order clock and unit total are kept across the re-plans of one placement. An Error from the planner is a `503` and nothing is stored. A placement is audited as `order.place`.

### Orders awaiting curse approval (`held_curse`)

If a plan needs a book whose extras include a curse (Binding or Vanishing) that the item would take and nobody ordered, the order is stored with `status: "held_curse"`, `hold: { "reason": "curse", "curse": [ "minecraft:vanishing_curse" ] }` and `enchant.take: []`. It **reserves nothing**, counts toward the 20 open orders per stash, at most 3 per user (`400` for a fourth), is cancelled immediately by `POST .../cancel`, is never claimed and never counted in `orders.pending`, and is shown in the list, the summary and retry. `POST /api/v1/orders/{id}/accept-curse { "curse": [ids] }` (BROWSER_WRITE, `order` permission; audited as `order.accept-curse`): only the person who placed the order may accept (an `ordersAll` admin can cancel it, not accept it). Check order: the stash is in the caller's scope (`404`), the caller placed the order (`403`), the order is `held_curse` (`409`), the list equals the stored one (`409 { ok:false, error, curse: [current list] }`; a malformed body is `400`). Then the order is planned again outside the book's lock, and the book's version is checked under it (planned again when it moved). A curse-free plan, or one that needs only the consented curses, makes it `pending` and reserves its `take` (`200 { ok, order }`); a plan that needs another curse keeps it awaiting with the new list (`409` with `curse`); an infeasible plan fails the order with the plan's reason in `message` (`200`, `status: "failed"`, like retry).

### Result, cancel and force-cancel

`result.enchant`: `{ made: [ { key, wanted, made } ], short: [ { key, reason } ], levels: [ { id, lvl } ], bottlesUsed, anvilsBroken, returned: [ { key, count } ] }`, checked on arrival (`400`, never echoing the input): `short[].reason` is one of `too_expensive`, `books_short`, `xp_short`, `no_anvil`, `inventory_full`, `cost_mismatch`, `mc_unsupported`, `mc_mismatch`, `busy`, `too_complex`, `no_base`, `anvil_broke`, `wrong_result`, `module_conflict`, `cap`, `cancelled`, `timeout`; `lvl` 1..255; counts whole numbers 0..2147483647; lists at most 400 entries; keys cut to 256, and a `returned[].key` must be an item key (`[a-z0-9_.:/-]+`, optionally `#hex`); `made <= wanted <= the line's count`; `made` and `short` keys must be keys of the order's enchant lines; `result.lines[].delivered <= count` for those keys; `result.enchant` on an order without enchant lines is a `400`. A forced-streamer user sees any key or id that fails the safe-key pattern, and any reason outside the closed set, as `(hidden)`. Cancel: before the bot starts it is immediate; after it, `cancelRequested` is set and the bot applies it when the current item finishes. **Force-cancel of an enchant order** (`POST /api/v1/orders/{id}/force-cancel`, after a cancel was requested) needs the body `{ "confirm": true }` (a strict boolean; `400` otherwise, checked after the scope `404`, the orderer or `ordersAll` `403` and the state `409` checks). The order becomes `cancelled`, but **its reservations are held** until the bot acknowledges (its next `status` call, answered `409` as for any finished order) or until 2 minutes after its heartbeat block goes stale (4 minutes after its last heartbeat, counted from the force-cancel when it was never heard), and **never longer than 10 minutes after the force-cancel**, whatever the heartbeats say; "clear" and the history pruning do not remove it while the hold is active. Once a hold has ended it never switches back on (a later heartbeat does not revive it). After a restart the hold is evaluated the same way (the force-cancel time is stored). `bottlesUsed` may exceed the order's held bottles (a top-up) but at most by the stash's repair reserve plus 2,048 (`400` above `take bottles + reserve + 2048`). Audited as `order.force-cancel` with `enchant: true`.

### `POST /api/v1/enchant/plan`

Browser route (BROWSER_WRITE, `order` permission, body at most 4 KiB). `{ "server", "stash", "key": "minecraft:diamond_sword", "enchants": [ { "id", "lvl" } ] }` (unknown fields are ignored); `server` is resolved like every order route (an unknown or out-of-scope stash is `404`; a forced streamer sends the opaque id and gets the same answer as anyone, there are no positions or names in it). Check order: scope, validation (`400 { ok:false, code:"bad_request", message, plan:null }`, nothing echoed), the cheap game-rule and version checks (a refusal here, `200`, costs no rate token and no permit), rate, cache, single flight, permit, plan. A forced streamer sees index keys that are not item keys (`plan.materials[].key`, `availability.books[].key`, and the same in order `enchant` plans and `take`) as `(hidden)`. An Error from the planner (not a refusal) is logged by class name only and answered as `503 busy`; the permit and the user's in-flight mark are released in the worker's `finally`. Reply `200`:

```json
{ "ok": true, "estimate": false, "canOrder": true, "mc": "1.21.11",
  "plan": { "materials": [..], "steps": [..], "levels": [..], "extras": [], "curse": [], "totalLevels": 5, "points": 55, "bottles": 14, "finalRc": 1 },
  "availability": { "books": [ { "key": "...", "need": 1, "have": 2 } ], "base": { "have": 3 }, "anvil": { "need": 2, "have": 3 },
                    "bottles": { "need": 14, "usable": 1272 } },
  "truncated": false }
```

`bottles.usable` is the REPAIR-CONTRACT v2.0 `usable` formula before this order's own reservation (default reserve 1,728 when `repair.json` is unreadable). With no enchant-capable bot (or an entry without `rc`) the plan is an estimate (`estimate: true`, `canOrder: false`), still `200`. `truncated`: a cap, the unit budget or the clock cut the search short; a plan cut off by the clock is cached only for 3 s and never stored. A refusal is `200 { ok:false, code, message, plan:null }` with the order-check codes, the planning codes (including `xp_short` and `inventory_full`) and `mc_unsupported`. The answer is **advisory**: order create plans again and re-checks stock and reservations itself. Limits: planning runs on the route's own pool (2 threads, no queue; `503 { ok:false, code:"busy", plan:null }` with `Retry-After` when both are busy or a request waited more than the plan's clock plus 250 ms), the plan's clock is 1,000 ms, the permit is released in the worker's `finally`; per user 1 plan in flight, 12 cache-miss plans a minute with a burst of 5, and 4 s of planner time a minute (`429 { ok:false, code:"rate_limited", retryAfter }` with `Retry-After`). Order create, retry and accept-curse each count once against the same 12 a minute and draw from neither the burst nor the time bucket. The cache holds about 256 answers for 30 s, keyed by the real server address, the index's ETag, the reservations, the request, the usable bottles, the bot's readiness and its carry. Identical requests in flight share one computation.

### Crafting a missing base (ENCHANT-CONTRACT draft 3.4, item 1.9)

When an enchant line has **no usable base** in the stash (`no_base`, or `base_unusable`: every base carries enchantments that were not ordered) and the stash can take craft orders right now (`CraftService.status(...).canOrder`: recipes for the stash's version, a craft-capable bot, a usable table; the same check as a craft line), the server plans **one crafted base per missing item** instead of refusing. The base is a fresh item (no enchantments, repair cost 0, undamaged, not named) and is **made by the order's `craft` block and used by the enchant run, never delivered** (like the shortfall crafts of a kit order). Nothing changes for an order that finds its bases in the stash: no `craft` block, no new field. Wire shape (all additive):

- **`craft.forEnchant: [ { "key": "minecraft:diamond_sword", "count": 2 } ]`**, a new optional member of the order's existing `craft` block: how many of each item in `craft.lines` are bases for the enchant plans. `craft.lines` (merged per key with any ordered craft line of the same item), `steps`, `take`, `leftovers` and `fills` are exactly the craft block of CRAFT-CONTRACT (`v: 1`, `mc`, `recipes` hash), so the bot's craft check and craft engine apply unchanged. The crafted items are **not** order lines: the order's `lines` hold only what was ordered, nothing crafted for a base is delivered or counted in the shulker check, and `result.craft` reports the craft as for any craft order (a base that was not made is `short`).
- **`enchant.lines[].plans[].materials[]`**: the base material (`kind: "base"`) of a plan whose base is crafted gains `crafted: true`, its `key` is the plain item id (not an index key), `ench: []`, `rc: 0`. A crafted base is **not in `enchant.take`** (its ingredients are in `craft.take`). An older bot ignores both fields.
- **Reservations:** `craft.take` is reserved like any craft take (plain item keys, `item:<id>`), `enchant.take` as before; both are released when the order finishes. A held order (`held_curse`) reserves neither until it is accepted.

**Planning order and sharing.** A placement plans the order's own craft lines and kit shortfalls first, then the enchant lines (first pass, which finds how many bases are missing); if any, the craft is planned **again together with those base crafts** (one `CraftService` plan: shared ingredients, `maxFills`, 64 steps, 64 ingredients) against the stock minus the open orders' reservations minus the first pass's enchant take (the craft can never eat what the enchant plan needs), and the enchant lines are planned **a second time** with the craft's take reserved and the crafted bases offered as fresh stock (so enchant and craft never use the same stock). If the second pass needs a different number of bases the craft is planned once more; a third disagreement is `409` "the stash changed while this order was being planned; try again". A real usable base is always drawn before a crafted one. Both passes share the one per-order budget (24,000,000 units and 4 s) and the order's re-plan loop (`409` after two changes). The crafted base can't cause a curse question by itself; the curse rules are unchanged.

**Carry slots with crafted bases.** The bot crafts every missing base before the first item, so it carries the bases of the items not yet started. The inventory check of each item is `slots(item) + L`, where `slots(item) = 1 + books + 2 + 1 + ceil(bottles / 64)` as before (the item's own base, crafted or not, is the `1`) and `L` = the number of items **after it in the order** (across lines, in plan order) whose base is crafted, one slot each (unenchanted gear does not stack). It must be at most `enchant.carry`, else `400 { enchant: "inventory_full", line: <that item's line> }` with the numbers in `error`. The first item is the tightest. Example: three crafted bases, 6 slots each, carry 7: the first item needs 6 + 2 = 8, refused; carry 8 passes.

**Re-plans.** Retry plans everything again (a new craft block). Accept-curse re-plans only the enchant lines, **keeping the stored craft block**: the bases are offered again as fresh stock, no more per item than `craft.forEnchant` says; a plan that needs more than that (the stash changed meanwhile) fails the order like any infeasible re-plan. If a real base turned up, the plan may use fewer crafted bases than `forEnchant` says; the bot still crafts the stored block and **returns the unused base to the stash**.

**Errors.** With no craft capability (the bot never said it can craft, crafting is off, no table, recipes differ, no recipes) a missing base is the existing `400 { enchant: "no_base" }` with the existing message. With the capability: an item with no crafting-table recipe (a trident) or a netherite item (for `base_unusable` the original refusal stays) (it needs a smithing table) is `400 { enchant: "no_base", error: "There is no <item> in stock to enchant, and it can't be crafted ... put one in the stash", line }` (a netherite base already in the stash is used as before); an unscanned base (no `ench` in the index) is still refused as before and never replaced by a craft; a craft that fails (not enough materials, `maxFills`, too many steps) is the craft error shape, `400 { ok: false, error: "only 0 craftable for Diamond Sword: not enough Diamond", line: <the enchant line> }` with no `enchant` code (or `409 { craft: <code> }` when the stash stopped being able to craft between the checks). No new error code.

**Claim.** The order has both `craft` (so it needs `craftOrders >= 1` and the craft grace rules of CRAFT-CONTRACT) and enchant lines (so it needs the full enchant capability record): a bot with only one of the two never gets it (`204`, stays pending; `orders.pending` does not count it), and a held order whose bot loses either is failed by the existing downgrade rules (`the bot can no longer craft ...` when the craft flag is missing, else `the bot can no longer enchant; retry the order`).

**Plan route.** `POST /api/v1/enchant/plan` plans with the same fallback: the answer's `plan.materials[0]` has `crafted: true` and `availability.base` gains `craft: true` (`have` is the stash's count of usable bases, 0); the ingredients are **not** checked there (advisory; order create plans the craft and refuses with the craft error). The cache key includes whether the stash can craft right now.

**Website.** The preview says the base will be crafted (the materials list and the base row of "In the stash"); the order card shows "Base will be crafted: 1 x Diamond Sword (used for the enchanting, not delivered)" next to the craft's own "Crafts from the stash" line.

### Compatibility and notes

All fields, states and routes are new; an old page sees an unknown status `held_curse` and an unknown line type `enchant`, an old bot never claims one. The missing-base craft (item 1.9) is the section above; without the craft capability a missing base is `no_base`. **Website:** the order page has an enchant picker. It reads the enchantment tables (levels, conflicts, which items take what) from `GET /enchant-<mc>.json` for the stash's version (`1.21.4`, `1.21.11`), static files that the build copies from qwerte-core's resources into the web resources (so they are always the planner's own tables; a test fails if they are missing or differ), and previews with `POST /api/v1/enchant/plan`; the server re-checks everything at placement. A force-cancelled enchant order's JSON carries `reservationsHeld: true` while its reservations are still held (until the bot acknowledges, 2 minutes after its heartbeat goes stale, and at most 10 minutes after the force-cancel); the page shows a note. The `Order.pack` of the contract is the addon's packer; the server's part is `shulkersNeeded` (one slot per enchanted item).

## Farms, tasks, STOP and bot roles (FARM-CONTRACT v1.0 and TASK-CONTRACT v1.0 slice, server 1.5.0)

All additive: an older bot, server or page ignores what it does not know, and a bot that never calls `tasks/sync` is never offered a task. **The hard rule: no world position, and nothing from which one can be derived, is ever accepted, stored, served, logged or audited by these routes.** The server never sees a farm program (only its hash and counts).

New state files in the data folder (owner-only, written with `AtomicFiles` before a call returns): `bots.json` (profiles, roles, per-bot STOP), `ops.json` (`rev`, `stopAll`), `tasks.json` (open tasks plus the newest 2,000 finished), `automation/farms.json` (+ `.bak` from the last clean file; both unreadable: the farm routes answer `503 farms_unavailable` and nothing is overwritten). An unreadable `bots.json`, `ops.json` or `tasks.json` is moved aside to `<name>.bad-<ts>` and the server starts with an empty store (one log line, no content).

### Guards on every body (G1, G2) and error shape

A body of any of the routes below is checked **before** a field is read:
- **G1**: a key named `x`, `y`, `z`, `pos`, `position`, `origin`, `coords` or `anchor` (case-insensitive, whole name, any depth, inside arrays too) is `400 { code: "position_field", field: "<the fixed lower-case name>" }`. `anchors` and `anchorId` are not matched. There is **no exception**: a checkpoint names its anchor with the key `anchorId` (contract draft 2.4), never `anchor`.
- **G2**: any string value (or key) that matches the plugin's `ActivityFeed.POSITION` pattern (`x=1 y=2 z=3`, `1 2 3`, `-10,64,20`) is `400 { code: "position_like" }`. Decimal triples are not matched (the pattern is a safety net, not a proof).
- Nesting deeper than 32 is `400 bad_field`. A rejected body is never echoed, never logged, never audited, and nothing from it is stored.

Errors from these routes are `{ ok: false, error: "<fixed text>", code: "<machine code>", field?: "<name>" }`; `error` is fixed per `code` and never repeats what was sent. Codes: `400` `position_field`, `position_like`, `bad_field` (+`field`), `bad_vocab` (+`field`), `reserved_field` (+`field`), `botid_mismatch`; `403` `permission: "owner"` (`Router.forbid`, before the handler) and `code: "farmer"` for a viewer frame; `404` `no_farm`, `no_task`, `unknown_bot`; `409` `rev_conflict` (+`farm`), `name_in_use`, `session_open`, `no_session`, `no_draft`, `farm_limit`, the start refusals (`no_program`, `no_farmer`, `farmer_offline`, `stopped`, `busy`, `farmer_busy`, `disabled`, `mode_not_ready`, `program_mode_mismatch`), `taken`, `not_target`, `lease_lost`, `bad_transition`, `not_claimable`; `413` body too large; `429` `slow_down` (with `Retry-After`; browser writes also `retryAfter`); `503` `farms_unavailable` or `storage_error`. A `bad_vocab` or `bad_field` answer names the field, never the value.

### Bot routes (strict, signed, header `botId` required)

The header `X-SM-Auth: v1 <botId> ...` must carry the bot id (not `-`) and equal the body's `botId`, else `400 botid_mismatch`. Bodies are JSON objects of at most 64 KiB (router cap 1 MiB). Bots should send `Content-Type: application/json`.

**`POST /api/v1/tasks/sync`** (every 5 s while a task runs, 15 s idle; one per second per bot at most, else `429 slow_down` with `Retry-After: 2`; not a long-poll).

```json
{ "botId": "farmer", "type": "plugin", "player": "FarmerName", "server": "2b2t.org", "contract": "1.0",
  "caps": ["farm"],
  "status": { "inGame": true, "dim": "minecraft:overworld", "health": 20, "food": 20, "freeSlots": 20, "totems": 1 },
  "running": { "taskId": "t-0123456789ab", "epoch": 2, "state": "running", "phase": "loop", "progress": { "done": 12, "total": 0 }, "seq": 41 },
  "stopAck": 3,
  "farmer": { "enabled": true, "acceptUploads": false, "version": "1.20.0", "modes": ["custom", "afk_still"], "farmsRev": 7, "forgot": ["f-0a0a0a0a"] },
  "programs": [ { "farm": "f-1a2b3c4d", "hash": "<64 hex>", "mode": "custom", "ticks": 2400, "loop": 2120, "anchors": 4, "unsupported": 0 } ],
  "draft": { "hash": "<64 hex>", "mode": "custom", "ticks": 2400, "loop": 2120, "anchors": 4 } }
```

`botId` 1 to 64 of `[A-Za-z0-9_-]`; `type` `addon` or `plugin`; `player` at most 16 of `[A-Za-z0-9_]` or null; `server` a host name with an optional port (`^[A-Za-z0-9.-]{1,253}(:\d{1,5})?$`, at most 64 characters); `contract` a string of at most 8 characters (`"1.0"`; another value gets no offer); `caps` at most 16 strings of at most 32; `status.dim` a namespaced id of at most 64, `health` and `food` 0..20, `freeSlots` 0..41, `totems` 0..64; `running` null or `{ taskId, epoch (1..2^31-1), state: running|paused, phase (closed list, below), progress { done, total 0..1,000,000 }, seq }`; `stopAck` an integer 0..2^31-1. `farmer.modes` at most 12 of the FARM modes; `farmer.forgot` at most 64 farm ids; `programs` at most 32 (the full list the farmer holds) and `draft` null or one entry. Wrong types or ranges are `400 bad_field`; **nothing is stored when any field is refused**.

Reply:

```json
{ "ok": true, "now": 0, "roles": ["farmer"],
  "stop": { "global": false, "bot": false, "rev": 3, "reason": null },
  "lease": { "taskId": "t-0123456789ab", "epoch": 2, "expiresAt": 0 },
  "offer": null, "cancel": [],
  "farmsRev": 7, "farms": null, "forget": [], "attach": null }
```

- `now` is the server clock in ms; the bot works with `expiresAt - now`. `roles` is the owner's setting (a `roles` field in a sync is ignored). `stop.rev` is the current `ops.rev`; `reason` is the owner's text and only while a STOP is on.
- A sync with a matching `running` renews the lease to `now + 90 s`. A `running` with a stale epoch, an unknown or finished task, or another bot's task gets `lease: null` and its id in `cancel`. The renewal is applied **before** leases are checked for expiry, so a late sync keeps its lease. A `running` whose epoch matches a task the server had parked as `lease_lost` takes the lease back and the task goes `running` again **when the bot says `running`**; if the bot says it is `paused` too, nothing is renewed (`lease: null`, nothing cancelled) and its next report decides.
- `offer` is null or `{ id, kind: "farm.session", title: "Farm session", params, target }` for the one claimable task of this bot, **only** when the bot holds the `farmer` role set by the owner, no STOP applies, it holds no other task, and `contract` is `"1.0"`. `cancel` (at most 20) lists the bot's tasks it must stop: finished or stale ones it still reports, and every open one while a STOP applies. A reply that cancels anything carries **no offer**: the bot drops the old task first and is offered the next one at its following sync (so a new session of a farm whose session was just stopped is never offered while the old one is still parked).
- The `farmer`, `programs`, `draft`, `caps` and `status` fields are **informational**: they decide what the page shows and which start refusals are friendly, never who may do what. A bot that reports `farmer.enabled: true` is flagged `farmerSeen` (sticky, persisted; any holder of the API key can set it for a known bot id, accepted as a one-way tightening; a reset route comes in M3). The first time `farmerSeen` or the `farmer` role turns on for a bot, the server clears what it stored for it (activity snapshot, events, `bot/state` values, `bot/results`, console and jobs, the viewer snapshot).
- A bot with no profile (never said `bot/hello` and has no role) is **not stored**: it gets `200` with `roles: []`, `offer: null`, and counts against a limit of 4 new ids per hour per source address (IPv6 by its /64) and 64 distinct new ids per hour over all addresses (then `429 slow_down`); the table of addresses is capped at 1,024. Profiles are limited to 64.
- Farm part of the reply, when the body carried `farmer`: `farmsRev` (the farm registry `rev`), `farms` (`[ { id, name, mode, enabled, session, thresholds, travel } ]` for this bot, only when `farmer.farmsRev` is older), `forget` (farm ids to delete the program and location for, until a sync lists them in `farmer.forgot`, or 7 days) and `attach` (`null` or `{ farm, draftHash }`, cleared when `programs[]` lists that farm with that hash). `programs[]` entries for farms not assigned to this bot are ignored; a farm assigned to the bot and missing from `programs[]` loses its `program` after two consecutive syncs.

**`POST /api/v1/tasks/{id}/claim`** `{ botId }`: `200 { ok, task, lease }`; the epoch goes up by one, attempts by one, the state is `pending` with a lease. `404 no_task`; `409 not_claimable` (finished, or three attempts used), `not_target` (not this bot, or it has no `farmer` role), `stopped`, `taken`. A **live lease is never taken over**, not even by its own bot (`409 taken`; nothing is reset): a restarted bot waits for its old lease to lapse, and a task parked by a lost lease can be claimed again only from its `nextAttemptAt` (`409 not_claimable` before). The claim then fences the old run.

**`POST /api/v1/tasks/{id}/report`** `{ botId, epoch, seq, state, phase, progress, checkpoint, result, error }`: `200 { ok: true }`, `{ ok: true, dup: true }` for a `seq` not greater than the last accepted one of that epoch (nothing changes), or the same terminal state again. `409 lease_lost` for another epoch (the bot stops at once), `409 bad_transition` (pending may only go to running, failed, cancelled; a finished task never changes), `409 not_target`, `404 no_task`. `running` and `paused` reports renew the lease.

**Closed vocabularies for `farm.session`** (no free text from the bot; anything else is `400 bad_vocab` naming the field): `phase` one of `setup`, `loop`, `recover`, `stop_steps`, `parked`, `waiting`; `checkpoint` null or `{ step: setup|loop|recover|stop_steps|parked, data }` with `data` keys only `loop` (0..1,000,000), `anchorId` (`a0` to `a63`, nothing else), `tick` (0..72,000), `recoveries` (0..1,000); `result` exactly `{ loops, recoveries, runtimeMs, endedBy, gained: [] }` (integers 0..2^31-1, `endedBy` a code below); `error` `{ code }` (a `text` from the bot is ignored; the server writes its own from the code); `progress` `{ done, total }` 0..1,000,000. Codes by state: `paused` (park) `controller_connected`, `disconnected`, `pathfinder_busy`, `server_unreachable`, `lease_lost`; `cancelled` `stopped`, `ops_stop`, `pause_timeout`, `offer_timeout` (the server ends a task that stayed `pending` with no lease for 10 minutes), `role_removed` (the owner took the `farmer` role away); `completed` ends with `time_up`; `failed` `player_near`, `low_health`, `no_food`, `no_totem`, `drifted`, `farm_changed`, `inventory_full`, `item_missing`, `not_at_farm`, `wrong_dim`, `chunk_not_loaded`, `module_conflict`, `guard_off`, `unsafe_logging`, `unsafe_discord`, `unsafe_bridge`, `unsafe_rotation`, `unsafe_viewer`, `key_missing`, `key_mismatch`, `key_invalid`, `key_perms`, `mode_not_ready`, `program_missing`, `program_mismatch`, `error`. `failed` needs an `error`; a code of the wrong kind for its state is `400 bad_field error`. A `result` belongs to an end: one sent with a `running` or `paused` report is `400 bad_field result`.

**Leases and retries.** TTL 90 s, renewed by a matching sync or report (renewals are written to `tasks.json` at most every 30 s). After a **server restart** every open leased task gets a fresh 90 s lease, so a restart never fails a task on its last attempt before its bot can sync, and the time a task sat paused while the server was down is not counted toward the 60 minutes. With no renewal the server parks the task (`paused`, `pauseReason: "lease_lost"`) and, while no STOP applies, offers it again after `60 s x 2^attempts` (jitter +/-20%); after the third attempt it fails the task (`lease_lost`). A task paused for more than 60 minutes in total is cancelled (`pause_timeout`). A farm session holds no items, so a lost lease orphans nothing. The task id space (`t-` + 12 hex) is shared with pearl trips; a new id is checked against both.

**STOP.** The server persists it before it answers. While any STOP applies to a bot: no offer, `claim` is `409 stopped`, reports are still accepted, and every open task of the bot is in `cancel`. The bot parks within one tick, reports `cancelled` / `ops_stop`, and sends `stopAck = stop.rev`. **Setting a STOP finishes, as `cancelled` / `ops_stop`, every open task of the scope that holds no lease and is not running** (a session nobody claimed, one paused with no lease); nobody would report those. A lease that lapses while a STOP applies ends the task the same way. Only leased or running tasks are listed for the delivery rule. **Delivery** (mitigation a, `GET /api/v1/ops`): `delivered` only when the bot's sync after the STOP shows `running: null` **and** every listed task is cancelled (`ops_stop`, or a bare `cancelled` report with no code) or ended before it, or, with no listed task, `stopAck >= rev`; `stopAck` alone never counts when a task was running. `sent` until then, `unconfirmed` after 30 s, `unsupported` for a bot that never synced, `null` when no STOP applies.

**`POST /api/v1/heartbeat`** replies gain `"ops": { "stop": bool, "rev": n }`: the global STOP only (a heartbeat names no bot id; a per-bot STOP reaches a bot through `tasks/sync`).

**Tasks that nobody claims, and roles.** A `pending` task with no lease for more than 10 minutes ends `cancelled` / `offer_timeout`. Taking the `farmer` role away ends every open task of that bot `cancelled` / `role_removed`; a sync, `report` or `claim` from a bot without the role is not renewed and gets `not_target` (`report`, `claim`) or the task in `cancel` (sync). At start, while a STOP applies (a forced one included), every open task that holds no lease ends `cancelled` / `ops_stop`. A `claim` whose write failed is taken back (epoch, attempts, lease), so the bot's retry after the `503` is a normal first claim.

**Writes that fail.** A dirty file is cleared only after it was written; a failed write leaves it dirty, the other files are still written, and every later call retries (`housekeeping`). A bot call whose own change could not be saved answers `503 storage_error` (the bot retries; a repeated report `seq` is a no-op). A **farm mutation** (save, delete, start, stop, attach) is one step: on a failed write the farm and task records go back to what they were and the answer is `503 storage_error`. A **STOP, resume or role change** stays applied in memory (a STOP must never be lost to a full disk) and the answer is `503 { ok: false, code: "storage_error", applied: true, saved: false, changed, error: "applied, not saved: ..." }`; it is written when the disk works again, and lost if the server restarts first. `ops.json` and `bots.json` keep a `.bak` (the last readable file) like `farms.json`; an **unreadable `ops.json` fails closed**: the global STOP is forced on (by `server`, reason "ops.json was unreadable", a counter above anything a bot acknowledged) until the owner resumes; an unreadable `bots.json` is restored from its backup (roles and `farmerSeen` with it), and with no readable backup the profiles start empty; **either way, and also when `bots.json` is missing on a server that has run before (`ops.json` exists), the global STOP is forced on** (by `server`, reason "bots.json was unreadable", a counter above anything acknowledged) until the owner resumes, because per-bot STOPs and acknowledgements may be gone or stale. A first run (no state file at all) starts with the STOP off. A failing backup copy (`*.bak`) never blocks the primary write: it is logged as `state_backup_failed <file>` and the old backup stays; a damaged primary file still never replaces a good backup. Dirty files are also retried by a background task every 30 s and once more at shutdown, so an "applied, not saved" STOP is written without waiting for another call.

### Browser routes (owner only unless stated)

Every route below declares `Router.Perm.FARMS` (key `owner`, text "manage farms (owner only)"): a non-owner (a forced streamer included) gets `403 { ok: false, error: "you don't have permission to manage farms (owner only)", permission: "owner" }` from `Router.forbid` **before** the handler runs, the same for any id or none. Not logged in: `401`. Writes need `POST`, `X-Stash-Manager: 1`, JSON and the same origin; bodies are at most 4 KiB; 60 writes per user per minute, 6 farm starts or stops and 6 `ops/stop` or `ops/resume` per user per minute (`429` with `Retry-After`).

| Route | Perm | Body and answer |
|---|---|---|
| `GET /api/v1/farms` | FARMS | `{ farms: [ Farm + status ], draft, farmer: { botId, online, modes, acceptUploads, version } or null, rev, limits }` |
| `POST /api/v1/farms` | FARMS | create (no `id`) or edit (`id`, `rev`): the list body plus `id`. `409 rev_conflict` carries the current `farm` |
| `POST /api/v1/farms/delete` `{ id }` | FARMS | `409 session_open` while a session is open; queues the `forget` for the farmer |
| `POST /api/v1/farms/start` `{ id, maxMs? }` | FARMS | the list body plus `taskId`; `maxMs` 60,000..43,200,000 |
| `POST /api/v1/farms/stop` `{ id }` | FARMS | cancels the open session (`cancelled`, `stopped`); `409 no_session` if none |
| `POST /api/v1/farms/attach` `{ id }` | FARMS | orders the farmer to bind its draft; `409 no_draft`, `409 busy` |
| `GET /api/v1/tasks?kind=&bot=&state=` | FARMS | `{ tasks: [...] }` newest first, at most 200 |
| `GET /api/v1/ops[?bot=]` | ACTIVITY | `{ stopAll, bots: [ { botId, stop, stopAck, online, delivered, roles?, farmer? } ], rev }` |
| `POST /api/v1/ops/stop` `{ scope, reason? }` | FARMS | `scope` `"all"` or a bot id; `reason` the owner's text (at most 100 characters, G2-checked); the `GET /ops` body plus `changed` |
| `POST /api/v1/ops/resume` `{ scope }` | FARMS | the same; turns the flag off and bumps `rev`; does **not** restart cancelled tasks |
| `POST /api/v1/bots/roles` `{ botId, roles }` | FARMS | `roles` at most 2 of `farmer`, `loader`; the `GET /ops` body plus `changed` |

A repeat (stopping what is stopped, resuming what runs, the same roles) is `200` with `changed: false` and does not bump `rev`. `ops.rev` is one counter for the whole file, bumped by every STOP and resume. **`GET /api/v1/ops` is the only route here open to non-owners.** It shows the stop state; for a non-owner the `reason` and `by` fields are **left out entirely**, and there are no roles, names or tasks. A forced streamer gets opaque bot ids (the `botId` of every answer is masked like everywhere else, and `?bot=` takes the opaque id only: a raw id is `404`). `GET /api/v1/bots` entries gain `roles` for the owner only.

**Farm record** (`GET /api/v1/farms`): `{ id: "f-xxxxxxxx", name, server, dim, mode, botId, enabled, program (null until the farmer reports one: { hash, mode, at, ticks, loop, anchors, unsupported }), produces, needs, session { minMs, maxMs }, thresholds { healthStop, food, totems, playerRadius, playerSeconds, invFullPct }, travel { preset, out, back, station, route }, dropStash, supplyStash, schedule, offlineOk, lastSession { taskId, endedBy, state, at, loops, recoveries, runtimeMs } or null, rev, createdAt, updatedAt, status { state: idle|pending|running|paused, phase, loops, pauseReason, taskId, farmerOnline } }`. Validation: `name` 1..32 characters after NFKC normalisation (the stored name is the normalised one), trimmed, no `§`, no control, format (zero-width, bidi), line or paragraph separator, private-use or unassigned character, **fewer than 3 digit characters** (full-width digits count; `400 bad_field name`), no position-like text, unique per install case-insensitively on the normalised text (`409 name_in_use`); `server` the host pattern above (at most 64 characters), set at creation and immutable; `dim` namespaced id; `mode` one of `afk_still`, `custom`, `afk_swing`, `afk_use`, `hold_use`, `fishing`, `raid`, `gold_iron`, `wither_shulker`, `trading`, `crop_replant`; `botId` must hold the `farmer` role (omitted: the one bot that does); `produces` and `needs` at most 16 each (`key` an item key of at most 64 characters, `perHour` 0..1,000,000, `min <= max` 0..100,000); `session.maxMs` 60,000..43,200,000 (default 7,200,000), `minMs <= maxMs`; thresholds `healthStop` 1..19 (10), `food` 0..19 (6), `totems` 0..64 (1), `playerRadius` 8..64 (16), `playerSeconds` 1..120 (10), `invFullPct` 50..100 (90); `travel.preset` `pearl_both` (needs `station`, a `ps-` id), `walk`, `nether_elytra`, `pitch40_walk`, `out_then_pearl_home` (default), `out` and `back` `walk|pearl|nether_elytra|pitch40_walk`, `route` must be null; `dropStash`, `supplyStash` and `schedule` must be null in M2 and `program` and `lastSession` are never written by the browser (`400 reserved_field`). At most 64 farms (`409 farm_limit`). `rev` is an optimistic lock for edits.

**Start refusals**, in this order: `404 no_farm`, `409` `no_program`, `no_farmer`, `farmer_offline` (last sync older than 45 s), `stopped`, `busy`, `farmer_busy`, `disabled`, `mode_not_ready` (**M2 runs only `custom` and `afk_still`**; any other mode is refused whatever the farmer reports, and a mode the farmer's last-reported `modes` lacks too), `program_mode_mismatch`. A session is a task of kind `farm.session` (priority 100, band `low`, interrupt `timed`, `maxAttempts` 3, title the constant `"Farm session"`, params `{ farm, programHash, maxMs, origin: "website" }`, target `{ botId, role: "farmer" }`); a farm name never goes into a task, audit entry or any list a non-owner can read.

### The farmer bot's other routes (owner only, no coordinates)

A bot is a **farmer** when the owner gave it the `farmer` role or it has ever reported `farmer.enabled` (`farmerSeen`, sticky; **decided for 1.5.0**: it is not cleared, an owner reset comes in M3). **Decided:** a non-owner with the Bots view keeps seeing a farmer bot's card in the Bots list (the role itself is not secret and not sent to them); only the farmer's activity, settings, jobs and console are owner only. The viewer stream of a bot that becomes a farmer ends with `bye: revoked`.
- `GET|POST /api/v1/bots/{id}/(settings|jobs|console|activity)` answer `403 permission: owner` ("manage farms (owner only)") to everyone but the owner, GET and POST, **before** the body is read and before the console, proxy and sudo checks. The id is resolved with `Router.botParam` first (a forced streamer must use the opaque id); an unknown bot or one outside the user's bot scope keeps the old `404`, a bot that is not a farmer keeps all its old answers. That a non-owner gets 403 for a farmer bot reveals that it is one: accepted. What the bot pushed through `bot/state` and `bot/results` is only ever returned to the owner.
- `POST /api/v1/bot/viewer` from a farmer is `403 { code: "farmer" }`; so are `GET /api/v1/bots/{id}/viewer` and `/viewer/stream` for a farmer bot (for any user, the owner included). A frame or an activity snapshot stored in the instant a bot became a farmer is cleared again right after it is stored.
- `POST /api/v1/bot/activity` from a farmer is rebuilt **at ingestion** from an allowlist, key by key (unknown keys dropped, never a deny list): `at` (integer 0..2^53), `connection { state (online|queue|connecting|offline|disconnected), queuePosition 0..100,000, health and food 0..20, ping 0..60,000, uptimeSeconds 0..31,536,000, spectators 0..1,000 }`, `current[] { kind (plugin|module|process), level (info|warn|error), progress { done, total 0..1,000,000 }, since }` at most 20 items with `source` the constant `"Farmer"`, and `eventCount` (a count; no event text is kept). Integers must be JSON integers; any other type, a float, or a number out of range drops that field (it is not clamped). `connection.x/y/z`, `dimension`, `server`, `queueEta`, `controllingPlayer`, `title`, `detail`, `modules[]`, `plugins[]` and event texts are never stored or served. The answer is still `200`.

### Audit

`farm.save` (`f-xxxxxxxx new|edit mode=<mode>`), `farm.delete`, `farm.start` (`f-xxxxxxxx t-xxxxxxxxxxxx`), `farm.stop`, `farm.attach` (`f-xxxxxxxx`), `ops.stop` (`all|<botId>: <reason>`), `ops.resume`, `bot.roles` (`<botId>: farmer,loader`), all by the owner; `farm.session.end` by the user name `bot <botId>` (`f-xxxxxxxx endedBy=<code>`). Details carry ids and codes only, never names or positions.

### Which routes to call (website item 2.7, plugin item 2.6)

- **Website:** `GET /api/v1/farms` (the whole page), `POST /api/v1/farms` (save), `/farms/start`, `/farms/stop`, `/farms/attach`, `/farms/delete`, `GET /api/v1/tasks`, `GET /api/v1/ops` (STOP state and delivery), `POST /api/v1/ops/stop`, `/ops/resume`, `POST /api/v1/bots/roles`, and `roles` in `GET /api/v1/bots`.
- **Plugin:** `POST /api/v1/bot/hello` once, then `POST /api/v1/tasks/sync` every 5 s (15 s idle) with the header bot id, `POST /api/v1/tasks/{id}/claim` for an `offer`, `POST /api/v1/tasks/{id}/report` on every state change, and the heartbeat `ops` field. It sends no activity, viewer or state data before its first successful sync.
