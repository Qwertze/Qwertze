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
  - Sources, in order: a 64 px render from the local Minecraft client jar (cached per version in `icons/game-<mcVersion>/`), then the wiki pipeline (Minecraft Wiki, then mcasset.cloud; cached in `icons/<namespace>/`). The `iconSource` setting can limit this to one source. Icons are rendered or fetched for every id of an index as soon as it arrives.
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
