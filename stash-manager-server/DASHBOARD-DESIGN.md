# AquariusProxy dashboard: design

What the user chose: build in phases until it covers everything, require a password for the whole website (each browser stays logged in for 30 days), and include a console behind that password. The Meteor addon is not part of the dashboard. Secrets are never sent to the dashboard and can't be edited from it.

## Facts about AquariusProxy (jar `QwerteStash/libs/AquariusProxy.jar`, source clone at `C:\Users\dmpor\Desktop\AquariusProxy`, c3243e84f)

- **Config root:** `Globals.CONFIG` (Globals.java:70) is `com.aquarius.util.config.Config`, made of nested `public static final class` sections. Plugin configs come from `PLUGIN_MANAGER.getAllPluginConfigs()` (PluginManager.java:74).
- **Saving:** `Globals.saveConfigAsync()` / `saveConfig()` (:176-183) saves the plugin configs too (PluginManager.java:367). `CommandManager.execute` calls `saveConfigAsync()` after every command (CommandManager.java:215).
- **Turning modules on and off:** `Module.enabledSetting()` / `isEnabled()` / `syncEnabledFromConfig()` (Module.java:19,30,129). `MODULE.getModules()` is at ModuleManager.java:97.
- **Running commands:** the pattern to copy is CommandHttpHandler.java:100-114: `EXECUTOR.execute(...)`, then `CommandContext.create(cmd, source)` and `COMMAND.execute(ctx)`, then read `ctx.getEmbed()` (title, description, fields) and `getMultiLineOutput()`. The source is a `CommandSource` (name, validateAccountOwner, resolveSubject, logEmbed, logMultiLine). `CommandContext.isSensitiveInput()` marks input that must not be logged.

## Data flow (every connection is opened by the plugin; the proxy runs on Proxmox and the server can't reach it)

- **The link thread:** the plugin runs a `DashboardLink` daemon thread. It is not tied to the game tick, so the console still works while the bot is offline.
- **Settings and their values:** `ConfigSchema` walks the public non-static fields of a scope and builds a schema tree and a flat map of values, each with a SHA-1. It pushes them when a hash changes. Scopes are `plugin`, `proxy` and `plugin:<id>`. Values are checked again every 3 s while a browser has that bot open and every 60 s otherwise, which also picks up changes made from the terminal or Discord.
- **Edits:** the browser POSTs a change and the server queues a job for that bot, waking its waiting long-poll. `GET bot/next` hands the job to the plugin within about 1 s. The plugin applies it on one "dashboard-apply" thread and reports back with `POST bot/results`, including the value it reads back.
- **Console:** console commands use the same job queue with `type:"command"`. The server keeps the last 200 entries per bot. The browser polls every 1 s while a command is waiting and every 5 s otherwise. There is no SSE, because the server's thread pool is small.

## Applying edits

- **Plugin settings:** a `SettingsSpec` table maps each field to its `stash …` command, using the ranges in StashCommand. Running the command applies its side effects. A reply embed in the error colour counts as a failure.
- **Proxy settings:** check the value, set the field by reflection, then for each module where `enabledSetting() != isEnabled()` call `syncEnabledFromConfig()` (each one in its own try/catch), then `saveConfigAsync()`.
- **Checks on every value:**
  - the type must match, and enum values must be among `getEnumConstants()`
  - numbers must fall within the spec's min/max or the type's range, and a nullable Integer may be null
  - strings are capped at 1000 characters and lists at 500 entries
  - each change carries `expect` (the old value); if the field no longer has that value, the reply is `conflict`
- **Read-only fields:** `final` collections and every `Map` can't be edited here; they show as "edit via command (N entries)".
- **Secrets are never sent.** The schema only shows `secret:true, set:bool`, and they can't be edited:
  - `authentication.email` and `authentication.password`
  - `discord.token`
  - `client.connectionProxy.user` and `client.connectionProxy.password`
  - `database.username`, `database.password`, `database.lock.redisUsername` and `database.lock.redisPassword`
  - `notifications.ntfy.topic`
  - the whole `server.permissions.users` subtree
  - the plugin's `managerKey`
  - anything whose name matches `(?i)token|passw|secret|api_?key|webhook|cookie|credential|topic`
- **Read-only because changing them is dangerous or needs a restart:**
  - `server.bind.*`, `server.enabled`, `server.permissions.*`, `server.viewer.*`
  - `client.bindAddress`, `client.server.*`
  - `authentication.*`
  - `discord.enable`, `discord.channelId`, `discord.accountOwnerRoleId`
  - `database.*`, `plugins.*`, `interactiveTerminal.*`, `autoUpdater.*`, `warning`
  - the plugin's `managerUrl`

## Login (server)

- **Password:** stored in config.properties as `dashboardPassword=pbkdf2-sha256$600000$<salt b64>$<hash b64>` and compared in constant time.
- **First run:** the setup page only works from the server PC itself (loopback). Any other address is told to set the password on the server PC. To reset the password, delete that line and restart.
- **Session cookie:** `sm_session=<32 random bytes, b64url>; HttpOnly; SameSite=Strict; Path=/; Max-Age=2592000`. `sessions.json` stores only SHA-256(token) and the created, last-seen and expiry times (written atomically), so logins survive a restart.
- **Login rate limit:** after 5 failures from one IP that IP is locked out for 1 minute, doubling up to 15 minutes. There is also a global cap of 30 attempts per minute. The reply is 429 with Retry-After.
- **Logout:** logs out the current browser; a "log out everywhere" option ends every session.
- **Changing the password:** needs the current password, and logs out every other session.
- **Access rules on routes** (the LAN and Host checks still run first):
  - OPEN: static files and `/api/v1/auth/state|login|setup`
  - SESSION: every GET from the browser, including `/icon/` and `/api/v1/link`
  - BROWSER_WRITE: a session plus the existing CSRF checks; login and setup also go through the CSRF checks
  - ADDON_KEY: unchanged, still Bearer only
- **API responses:** `Cache-Control: no-store`. A request that isn't logged in gets `401 {"error":"login required"}`.

## Protocol (`/api/v1`)

**Login (browser):**
- `GET auth/state` returns `{setupNeeded, setupAllowed, loggedIn}`.
- `POST auth/setup` and `POST auth/login {password}` return 200 with Set-Cookie, or 401 / 429.
- `POST auth/logout` and `POST auth/logout-all`.
- `POST auth/password {current, next}`; the new password needs at least 8 characters.

**Bot (Bearer key):**
- `POST bot/hello {botId, player, plugin:{id,version}, proxy:{version,mc}, caps:["settings","console","proxy"]}` returns `{ok, protocol:1}`. `botId` is a random UUID kept in the plugin config (`managerBotId`).
- `POST bot/state {botId, scope, schemaHash, valuesHash, schema?, values}`. The schema is only sent when the server asks for it.
  - A schema node is `{path,label,type:"group|bool|int|long|double|string|enum|stringList",min?,max?,options?,nullable?,readOnly?,reason?,secret?,set?,help?,section?,children?}`.
  - `values` is `{ "<dot.path>": json }` and never includes secrets.
- `GET bot/next?botId=` waits up to 25 s.
  - It returns `200 {jobs:[{id,type:"set",scope,path,value,expect}|{id,type:"command",command}], need:[{scope,what:"schema|values"}], watching:bool}`, or 204 when there is nothing.
  - With more than 8 bots waiting, the server answers 429 and the plugin polls every 3 s instead.
- `POST bot/results {botId, results:[{id, ok, value?, error?, sensitive?, output?:{title,description,color,fields:[{name,value}],lines:[]}, more?:bool}]}`. Output that the command logs later (through `logEmbed`) is added to the same id.

**Browser (logged in):**
- `GET bots` returns `[{botId,player,online,lastSeen,plugin,proxy,caps,stashes}]`. A bot counts as online while it is waiting on `bot/next`, or if its last poll was less than 35 s ago.
- `GET bots/{id}/settings?scope=` returns `{scope,schemaHash,valuesHash,schema,values,receivedAt}` with an ETag.
- `POST bots/{id}/settings {scope, changes:[{path,value,expect}]}` returns `{jobs:[{id,path}]}`. It answers `409 {"error":"bot offline"}` when the bot is offline, and `400 {error,path}` when a value fails the server-side schema check.
- `GET bots/{id}/jobs?ids=` returns `[{id,status:"queued|sent|done|error|expired|unknown",value,error}]`. A job no bot picks up within 10 s becomes expired; one picked up but not answered within 60 s becomes unknown.
- `POST bots/{id}/console {command}` (at most 500 characters) returns `{id}`.
- `GET bots/{id}/console?after=<seq>` returns `{entries:[{seq,id,command,at,status,output}]}`. If the command was sensitive, `command` is `"(hidden)"`.

## UI

- **Navigation:** Stash, Orders, Bots, Settings (plugin), Proxy (every module, grouped and searchable), Console. A bot picker sits in the header.
- **Controls:** a toggle for bool, a number field with min and max for numbers, a select for enum, a text field for string, a chip editor for stringList. Secrets show a set / not-set badge. Read-only fields are greyed out with the reason.
- **Saving changes:** changed fields are marked dirty. Save sends them all at once, and Revert puts them back. Each field then shows a spinner, then "applied ✓" with the value read back, or the error next to it. On a conflict the values are reloaded.
- **Offline bot:** the forms become read-only with a "last seen" banner. Edits are refused, not queued.
- **Console:**
  - Text is rendered with textContent, plus a safe subset for bold and `code`.
  - Error replies get a red border.
  - Commands that need a confirmation first: `shutdown`, `update`, `disconnect`, `auth`, `jvmArgs`, `plugins`, `releaseChannel`, `discordManage`.

## Phases

1. **Login:** server only.
2. **Plugin settings:** server: `BotHub`, `BotRoutes`, `DashboardRoutes`, the settings UI, and 32 pool threads. Plugin: `manager/DashboardLink`, `ConfigSchema`, `SettingsSpec`, `ConfigApplier`, `Secrets`, `DashboardCommandSource`, the `dashboard` and `managerBotId` config fields, and `stash manager dashboard on/off`.
3. **Console:** server: the console endpoints and UI. Plugin: `ConsoleRunner`, which logs "Dashboard command: …" unless the input is sensitive.
4. **Every proxy setting:** plugin: the `proxy` and `plugin:<id>` scopes and the deny lists. Server: a tree view with search and groups that load lazily. Includes a test that fails if a known secret appears in the pushed JSON.

## Risks

- **Secrets leaking:** handled by the deny lists, the regex, and a test that scans the payload. The console can still show a secret if a command prints one.
- **Applying changes while the proxy runs:** prefer commands, and keep fields that need a restart read-only.
- **AquariusProxy updates:** wrap calls into the proxy in try/catch for LinkageError. If `CommandSource` gains a method, the plugin needs a new build.
- **Config size:** maps are left out, and values are only sent when they change.
- **Discord formatting in the console:** remove markdown escapes, and cap the output at 64 KiB.
- **Long-polls using up server threads:** at most 8 bots wait at once.
