# Changelog

All notable changes to the Stash Manager server are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

The server has no release numbers yet, so each release is headed by its date and jar name.

## [Unreleased]

## [2026-11-09] - kit2 (`stash-manager-server-kit2.jar`)

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
