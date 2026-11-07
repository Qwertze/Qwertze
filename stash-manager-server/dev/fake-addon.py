#!/usr/bin/env python3
"""Fake Qwerte addon for testing the Stash Manager server without Minecraft.

Standard library only. Run it isolated so nothing is imported from the current folder:

    python -I dev/fake-addon.py --url http://127.0.0.1:8768 --data <dataDir> push
    python -I dev/fake-addon.py --url ... --data ... push --shulker-colours --mc 1.21.4   # icon test index
    python -I dev/fake-addon.py --url ... --data ... heartbeat
    python -I dev/fake-addon.py --url ... --data ... --no-delivery --blocker "no delivery box" heartbeat
    python -I dev/fake-addon.py --url ... --data ... --naming true:30 heartbeat   # anvil set, 30 XP levels
    python -I dev/fake-addon.py --url ... --data ... --naming null serve          # a bot that can't name shulkers
    python -I dev/fake-addon.py --url ... --data ... --crafting true:true heartbeat   # crafting on, table set
    python -I dev/fake-addon.py --url ... --data ... claim
    python -I dev/fake-addon.py --url ... --data ... status o-0123456789ab in_progress --phase "gathering" --done 1 --total 3
    python -I dev/fake-addon.py --url ... --data ... serve        # heartbeats for 2 min; answers rescan requests
    python -I dev/fake-addon.py --url ... --data ... serve --rescan-blocked "sorter off"
    python -I dev/fake-addon.py --url ... --data ... dashboard    # a dashboard bot: settings + console for 2 min
    python -I dev/fake-addon.py --url ... --data ... viewer       # a live-viewer bot: frames while watched, for 2 min
    python -I dev/fake-addon.py --url ... --data ... viewer --bot-id b2 --hz 5 --radius 8 --max-frames 500
    python -I dev/fake-addon.py --url ... --data ... delivery --used 20 --chests 3   # delivery storage contents
    python -I dev/fake-addon.py --url ... --data ... run          # claim and walk one order to delivered
    python -I dev/fake-addon.py --url ... --data ... run --fail   # ... or to failed

The API key comes from --key, $STASH_MANAGER_KEY, or apiKey= in <dataDir>/config.properties.
"""

import argparse
import copy
import hashlib
import hmac
import json
import os
import sys
import threading
import time
import urllib.parse
import urllib.error
import urllib.request

SERVER = "2b2t.org"
STASH = "dev"
PLAYER = "FakeBot"


def item(key, name, count, tab, tab_order, max_stack=64, details=None, enchanted=False, english=None):
    item_id = key.split("#", 1)[0]
    return {
        "key": key,
        "id": item_id,
        "name": name,
        "englishName": english or name,
        "count": count,
        "maxStack": max_stack,
        "details": details or [],
        "enchanted": enchanted,
        "tab": tab,
        "tabOrder": tab_order,
    }


def shulker_key(item_key, contents):
    canon = item_key + "|" + ",".join(f"{c['slot']}:{c['key']}:{c['count']}" for c in contents)
    return "s:" + hashlib.sha1(canon.encode()).hexdigest()[:10]


def build_index(tabs=True, mc="1.21.11", player=None, extra_diamonds=0, containers=None):
    diamonds_box = [{"slot": s, "key": "minecraft:diamond", "count": 64} for s in range(27)]
    kit = [
        {"slot": 0, "key": "minecraft:diamond_sword#3f2a9c1d", "count": 1},
        {"slot": 1, "key": "minecraft:golden_apple", "count": 64},
        {"slot": 13, "key": "minecraft:totem_of_undying", "count": 1},
        {"slot": 26, "key": "minecraft:ender_pearl", "count": 16},
    ]
    shulkers = [
        {"key": shulker_key("minecraft:red_shulker_box", diamonds_box), "id": "minecraft:red_shulker_box",
         "itemKey": "minecraft:red_shulker_box", "name": "Red Shulker Box", "count": 4, "contents": diamonds_box},
        {"key": shulker_key("minecraft:shulker_box#abcd1234", kit), "id": "minecraft:shulker_box",
         "itemKey": "minecraft:shulker_box#abcd1234", "name": "PvP Kit", "count": 1, "contents": kit},
    ]
    items = [
        item("minecraft:stone", "Stone", 3456, "building_blocks", 1),
        item("minecraft:oak_planks", "Oak Planks", 1200, "building_blocks", 40),
        item("minecraft:oak_stairs", "Oak Stairs", 300, "building_blocks", 41),
        item("minecraft:cyan_wool", "Cyan Wool", 640, "colored_blocks", 10),
        item("minecraft:red_shulker_box", "Red Shulker Box", 6, "colored_blocks", 300, max_stack=1),
        item("minecraft:shulker_box", "Shulker Box", 3, "colored_blocks", 290, max_stack=1),
        item("minecraft:shulker_box#abcd1234", "PvP Kit", 1, "colored_blocks", 290, max_stack=1, english="Shulker Box"),
        item("minecraft:grass_block", "Grass Block", 128, "natural_blocks", 1),
        item("minecraft:torch", "Torch", 900, "functional_blocks", 1),
        item("minecraft:chest", "Chest", 54, "functional_blocks", 60),
        item("minecraft:redstone", "Redstone Dust", 2000, "redstone_blocks", 1),
        item("minecraft:diamond_pickaxe", "Diamond Pickaxe", 3, "tools_and_utilities", 20, max_stack=1),
        item("minecraft:diamond_sword#3f2a9c1d", "Diamond Sword", 2, "combat", 5, max_stack=1,
             details=["Sharpness V", "Unbreaking III"], enchanted=True),
        item("minecraft:totem_of_undying", "Totem of Undying", 40, "combat", 80, max_stack=1),
        item("minecraft:golden_apple", "Golden Apple", 64 + 320, "food_and_drinks", 30),
        item("minecraft:ender_pearl", "Ender Pearl", 16 + 160, "ingredients", 5, max_stack=16),
        item("minecraft:diamond", "Diamond", 4 * 27 * 64 + 500 + extra_diamonds, "ingredients", 20),
        item("minecraft:pig_spawn_egg", "Pig Spawn Egg", 2, "spawn_eggs", 50),
        item("minecraft:knowledge_book", "<b>Knowledge</b> & \"Book\"", 1, "other", 1000000, max_stack=1),
    ]
    if not tabs:
        for i in items:
            i.pop("tab")
            i.pop("tabOrder")
    index = {
        "version": 1,
        "server": SERVER,
        "stash": STASH,
        "player": player or PLAYER,
        "mcVersion": mc,
        "dimension": "minecraft:overworld",
        "generatedAt": int(time.time() * 1000),
        "containers": containers or {"known": 42, "total": 45, "stale": 1},
        "items": items,
    }
    if tabs:
        index["shulkers"] = shulkers
    index["indexHash"] = hashlib.sha1(json.dumps(items, sort_keys=True).encode()).hexdigest()
    return index


COLOURS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
           "purple", "blue", "brown", "green", "red", "black"]


def add_colour_shulkers(index):
    """Every shulker colour (some only empty, some filled with one item, some mixed) plus the other
    block-entity items, for checking icons: push --shulker-colours."""
    items = index["items"]
    shulkers = index.setdefault("shulkers", [])
    have = {i["key"] for i in items}
    fills = {
        "white": [("minecraft:stone", 64)] * 27,
        "orange": [("minecraft:torch", 64)] * 10,
        "light_blue": [("minecraft:diamond", 64)] * 5 + [("minecraft:golden_apple", 64)] * 2,
        "yellow": [("minecraft:golden_apple", 64)] * 3 + [("minecraft:ender_pearl", 16)] * 9 + [("minecraft:redstone", 64)],
        "lime": [("minecraft:oak_planks", 64)] * 27,
        "gray": [("minecraft:redstone", 64)] * 12,
        "cyan": [("minecraft:totem_of_undying", 1)] * 27,
        "purple": [("minecraft:ender_pearl", 16)] * 27,
        "blue": [("minecraft:diamond", 64), ("minecraft:stone", 64)],
        "green": [("minecraft:grass_block", 64)] * 6,
        "black": [("minecraft:diamond_pickaxe", 1), ("minecraft:totem_of_undying", 1), ("minecraft:golden_apple", 64)],
    }
    for n, colour in enumerate(COLOURS):
        key = f"minecraft:{colour}_shulker_box"
        name = colour.replace("_", " ").title() + " Shulker Box"
        groups = []
        if colour in fills:
            contents = [{"slot": s, "key": k, "count": c} for s, (k, c) in enumerate(fills[colour])]
            groups.append({"key": shulker_key(key, contents), "id": key, "itemKey": key, "name": name,
                           "count": 1 + n % 3, "contents": contents})
        empty = 0 if colour in ("lime", "purple") else 2 + n
        total = empty + sum(g["count"] for g in groups)
        if key in have:
            for i in items:
                if i["key"] == key:
                    i["count"] = i["count"] + total
        else:
            items.append(item(key, name, total, "colored_blocks", 300 + n, max_stack=1))
        shulkers.extend(groups)
    for n, (key, name, tab) in enumerate([
            ("minecraft:trapped_chest", "Trapped Chest", "redstone_blocks"), ("minecraft:ender_chest", "Ender Chest", "functional_blocks"),
            ("minecraft:red_bed", "Red Bed", "colored_blocks"), ("minecraft:white_banner", "White Banner", "colored_blocks"),
            ("minecraft:black_banner", "Black Banner", "colored_blocks"), ("minecraft:skeleton_skull", "Skeleton Skull", "functional_blocks"),
            ("minecraft:player_head", "Player Head", "functional_blocks"), ("minecraft:dragon_head", "Dragon Head", "functional_blocks"),
            ("minecraft:shield", "Shield", "combat"), ("minecraft:conduit", "Conduit", "functional_blocks"),
            ("minecraft:decorated_pot", "Decorated Pot", "functional_blocks")]):
        if key not in have:
            items.append(item(key, name, 1 + n, tab, 400 + n, max_stack=1 if "banner" not in key else 16))
    index["indexHash"] = hashlib.sha1(json.dumps(items, sort_keys=True).encode()).hexdigest()


# ---------- signed requests (PROTOCOL.md, "Signed requests (HMAC)") ----------

def signing_key(api_key):
    """K = HMAC-SHA256(key = UTF-8(apiKey), msg = "stash-manager v1")."""
    return hmac.new(api_key.encode(), b"stash-manager v1", hashlib.sha256).digest()


def request_sig(k, method, path_with_query, ts, nonce, body):
    msg = "%s\n%s\n%d\n%s\n%s" % (method, path_with_query, ts, nonce, hashlib.sha256(body).hexdigest())
    return hmac.new(k, msg.encode(), hashlib.sha256).hexdigest()


def response_sig(k, nonce, status, ts, body):
    msg = "resp\n%s\n%d\n%d\n%s" % (nonce, status, ts, hashlib.sha256(body).hexdigest())
    return hmac.new(k, msg.encode(), hashlib.sha256).hexdigest()


def verify_response(k, nonce, status, header, body, now_ms=None):
    """The checks a bot makes on a response: the header is there, fresh (60 s) and signed for our nonce."""
    if not header:
        return False
    parts = header.split(" ")
    if len(parts) != 3 or parts[0] != "v1" or not parts[1].isdigit():
        return False
    ts = int(parts[1])
    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)
    if abs(now_ms - ts) > 60000:
        return False
    return hmac.compare_digest(parts[2], response_sig(k, nonce, status, ts, body))


class Client:
    """Talks to the server the way the bots do: every request signed (X-SM-Auth), every response verified (X-SM-Sig).
    bearer=True sends the old "Authorization: Bearer" header instead (only for testing allowBearer)."""

    def __init__(self, url, key, bearer=False):
        self.url = url.rstrip("/")
        self.key = key
        self.k = signing_key(key)
        self.bearer = bearer
        self.last_error = None

    def request(self, method, path, body=None, timeout=15, bot_id="-", ts=None, nonce=None, key=None, raw=False):
        data = b"" if body is None else json.dumps(body).encode()
        headers = {}
        if body is not None:
            headers["Content-Type"] = "application/json"
        nonce = nonce or os.urandom(16).hex()
        if self.bearer:
            headers["Authorization"] = "Bearer " + self.key
        else:
            ts = ts if ts is not None else int(time.time() * 1000)
            k = signing_key(key) if key else self.k
            headers["X-SM-Auth"] = "v1 %s %d %s %s" % (bot_id, ts, nonce, request_sig(k, method, path, ts, nonce, data))
        req = urllib.request.Request(self.url + path, data=data if method != "GET" else None, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                status, raw_body, sig = r.status, r.read(), r.headers.get("X-SM-Sig")
        except urllib.error.HTTPError as e:
            status, raw_body, sig = e.code, e.read(), e.headers.get("X-SM-Sig")
        except (urllib.error.URLError, OSError) as e:
            return -1, str(e)  # server restarting or unreachable: the caller retries
        if raw:
            return status, raw_body, sig, nonce
        if not self.bearer and status not in (401, 403) and status < 500 and not verify_response(self.k, nonce, status, sig, raw_body):
            # A spoofed server or a man in the middle: the response is thrown away, no jobs from it run
            self.last_error = "server signature invalid"
            return -2, "server signature invalid"
        text = raw_body.decode()
        try:
            return status, (json.loads(text) if text else None)
        except ValueError:
            return status, text

    def get(self, path, timeout=40):
        bot = "-"
        if "botId=" in path:
            bot = urllib.parse.unquote(path.split("botId=", 1)[1].split("&")[0])
        return self.request("GET", path, timeout=timeout, bot_id=bot)

    def post(self, path, body):
        if path.startswith("/api/v1/orders/") and isinstance(body, dict):
            body = dict(body, player=PLAYER)  # claims and status reports name the bot (stash ownership)
        bot = body.get("botId", "-") if isinstance(body, dict) else "-"
        return self.request("POST", path, body, bot_id=bot)


def load_key(args):
    if args.key:
        return args.key
    if os.environ.get("STASH_MANAGER_KEY"):
        return os.environ["STASH_MANAGER_KEY"]
    if args.data:
        with open(os.path.join(args.data, "config.properties"), encoding="utf-8") as f:
            for line in f:
                if line.strip().startswith("apiKey="):
                    return line.split("=", 1)[1].strip()
    sys.exit("no API key: use --key, STASH_MANAGER_KEY or --data")


def show(label, status, body):
    print(f"{label}: {status} {json.dumps(body) if body is not None else ''}")


# Bot status sent with every heartbeat (PROTOCOL.md, "Additions: delivery status and order size").
DELIVERY = {"x": 120, "y": 64, "z": -340, "dimension": "minecraft:overworld"}
BLOCKER = None
NAMING = ...  # PROTOCOL.md, "Additions: shulker names": ... = not sent (an older bot), None = null, else {anvil, xpLevels}
CRAFTING = ...  # PROTOCOL.md, "Additions: crafting": ... = not sent, None = null, else {enabled, table}


def heartbeat(c, index_hash, extra=None):
    body = {"server": SERVER, "stash": STASH, "player": PLAYER, "indexHash": index_hash,
            "delivery": DELIVERY, "orderBlocker": BLOCKER}
    if NAMING is not ...:
        body["naming"] = NAMING
    if CRAFTING is not ...:
        body["crafting"] = CRAFTING
    body.update(extra or {})
    return c.post("/api/v1/heartbeat", body)


def serve(c, args):
    """Heartbeats every --interval seconds. A rescan request in the reply is acknowledged with rescanDone; then,
    unless --rescan-blocked is set, every container is marked stale and re-read --step at a time, each step pushed
    as a new index (PROTOCOL.md, "Additions: manual rescan")."""
    total = args.containers
    stale = 0
    blocked = args.rescan_blocked
    extra = {"rescanBlocked": blocked}
    end = time.time() + args.seconds
    while time.time() < end:
        index = build_index(extra_diamonds=args.extra_diamonds,
                            containers={"known": total, "total": total, "stale": stale})
        status, body = heartbeat(c, index["indexHash"], extra)
        show("heartbeat", status, body)
        rescan = body.get("rescan") if isinstance(body, dict) else None
        changed = isinstance(body, dict) and bool(body.get("needIndex"))
        if rescan is not None:
            changed = True
            extra["rescanDone"] = rescan
            show("heartbeat (rescanDone)", *heartbeat(c, index["indexHash"], extra))
            if not blocked:
                stale = total
        elif stale > 0:
            stale = max(0, stale - args.step)
            changed = True
        if changed:
            index = build_index(extra_diamonds=args.extra_diamonds,
                                containers={"known": total, "total": total, "stale": stale})
            show(f"index (stale {stale}/{total})", *c.post("/api/v1/index", index))
        time.sleep(args.interval)


# ---------- dashboard bot (DASHBOARD-DESIGN.md phases 2-3) ----------
# A StashConfig-like plugin config: what the real plugin's ConfigSchema would push for scope "plugin".

def dash_schema():
    def leaf(path, label, type_, section, **kw):
        n = {"path": path, "label": label, "type": type_, "section": section}
        n.update(kw)
        return n
    stash = [
        leaf("stashName", "Stash name", "string", "Stash", help="Each bot needs its own stash name."),
        leaf("sortItems", "Sort items", "bool", "Stash", help="Off: only index the stash, never move items."),
        leaf("scanInterval", "Scan interval (s)", "int", "Stash", min=5, max=600),
        leaf("mode", "Walk mode", "enum", "Stash", options=["SAFE", "FAST", "SLOW"]),
        leaf("radius", "Reach radius", "double", "Stash", min=1, max=64),
        leaf("maxOrderItems", "Max items per order", "long", "Stash", min=1),
        leaf("ignoreItems", "Ignored items", "stringList", "Stash", help="Item ids the sorter leaves alone."),
        leaf("nickname", "Nickname", "string", "Stash", nullable=True),
        leaf("kitDelay", "Kit delay (ticks)", "int", "Stash", nullable=True, min=0, max=200),
    ]
    manager = [
        leaf("managerUrl", "Server URL", "string", "Manager", readOnly=True, reason="set in the plugin config file"),
        leaf("managerKey", "API key", "string", "Manager", secret=True, set=True, readOnly=True, reason="secret"),
        leaf("acceptOrders", "Accept orders", "bool", "Manager"),
        leaf("kits", "Kits", "string", "Manager", readOnly=True, reason="edit via command (3 entries)"),
        {"path": "alerts", "label": "Alerts", "type": "group", "children": [
            leaf("alerts.enabled", "Enabled", "bool", "Manager"),
            leaf("alerts.minLevel", "Minimum level", "enum", "Manager", options=["INFO", "WARN", "ERROR"]),
        ]},
    ]
    return [
        {"path": "section:Stash", "label": "Stash", "type": "group", "children": stash},
        {"path": "section:Manager", "label": "Manager", "type": "group", "children": manager},
    ]


SECRET_KEY = "super-secret-manager-key-123"   # never sent: the schema only says set:true


def dash_values():
    return {
        "stashName": "north", "sortItems": True, "scanInterval": 30, "mode": "SAFE", "radius": 4.5,
        "maxOrderItems": 100000, "ignoreItems": ["minecraft:dirt", "minecraft:cobblestone"], "nickname": None,
        "kitDelay": None, "managerUrl": "http://192.168.1.20:8765", "acceptOrders": True,
        "alerts.enabled": True, "alerts.minLevel": "WARN",
    }


def leaves(nodes, out=None):
    out = {} if out is None else out
    for n in nodes:
        if n["type"] == "group":
            leaves(n["children"], out)
        else:
            out[n["path"]] = n
    return out


def sha1(obj):
    return hashlib.sha1(json.dumps(obj, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def proxy_schema(leak=False):
    """A big fake AquariusProxy config: ~20 sections, several hundred settings, modules with enable toggles."""
    def leaf(path, label, type_, section, **kw):
        n = {"path": path, "label": label, "type": type_, "section": section}
        n.update(kw)
        return n
    values = {}
    sections = []

    def section(name, nodes):
        sections.append({"path": "section:" + name, "label": name, "type": "group", "children": nodes})

    # Plain config sections
    for name, count in [("client", 30), ("server", 25), ("theme", 12), ("database", 10), ("discord", 14),
                        ("notifications", 10), ("autoUpdater", 6), ("interactiveTerminal", 5), ("logging", 12),
                        ("debug", 15), ("viaVersion", 8), ("autoReconnect", 9)]:
        nodes = []
        for i in range(count):
            p = name + ".option" + str(i)
            kind = ["bool", "int", "string", "enum", "double", "stringList"][i % 6]
            kw = {}
            if kind == "int":
                kw.update(min=0, max=1000)
                values[p] = i * 7
            elif kind == "bool":
                values[p] = i % 4 == 0
            elif kind == "string":
                values[p] = name + "-value-" + str(i)
            elif kind == "enum":
                kw["options"] = ["OFF", "LOW", "MEDIUM", "HIGH"]
                values[p] = "LOW"
            elif kind == "double":
                kw.update(min=0, max=10)
                values[p] = round(i / 3, 2)
            else:
                values[p] = ["entry-" + str(i), "entry-" + str(i + 1)]
            nodes.append(leaf(p, name.capitalize() + " option " + str(i), kind, name, help="Fake help for " + p + "." if i % 3 == 0 else None, **kw))
        sections.append(None)
        section(name, nodes)
    # Secrets and read-only bits
    sec = [leaf("discord.token", "Bot token", "string", "discord", secret=True, set=True, readOnly=True, reason="secret"),
           leaf("discord.channelId", "Channel", "string", "discord", readOnly=True, reason="needs a restart")]
    values["discord.channelId"] = "123456789"
    auth = [leaf("authentication.email", "Email", "string", "authentication", secret=True, set=True, readOnly=True, reason="secret"),
            leaf("authentication.password", "Password", "string", "authentication", secret=True, set=False, readOnly=True, reason="secret"),
            leaf("authentication.accountType", "Account type", "enum", "authentication", options=["MSA", "OFFLINE"], readOnly=True, reason="read-only here")]
    values["authentication.accountType"] = "MSA"
    # Not marked secret by the (buggy) bot, but the name says so: the server must treat it as one
    ntfy = [leaf("notifications.ntfy.topic", "ntfy topic", "string", "notifications")]
    if leak:
        values["discord.token"] = "LEAKED-DISCORD-TOKEN-xyz"
        values["authentication.password"] = "LEAKED-PASSWORD"
        values["notifications.ntfy.topic"] = "LEAKED-TOPIC"
    section("authentication", auth)
    section("secrets", sec + ntfy)
    # Modules: client.extra.<module>, each with an enabled toggle, a nested group and a map
    modules = ["antiAfk", "autoEat", "autoTotem", "chatHistory", "killAura", "spook", "queueWarning", "autoRespawn",
               "visualRange", "spammer", "autoReply", "actionLimiter", "antiLeak", "coordObf", "replayMod", "pathing"]
    for m in modules:
        base = "client.extra." + m
        nodes = [leaf(base + ".enabled", "Enabled", "bool", m, help="Turn the " + m + " module on or off.")]
        values[base + ".enabled"] = m in ("antiAfk", "autoEat", "autoTotem", "queueWarning")
        for i in range(12):
            p = base + ".setting" + str(i)
            kind = ["int", "bool", "enum", "string", "stringList", "double"][i % 6]
            kw = {}
            if kind == "int":
                kw.update(min=1, max=100)
                values[p] = 10 + i
            elif kind == "bool":
                values[p] = True
            elif kind == "enum":
                kw["options"] = ["SAFE", "NORMAL", "AGGRESSIVE"]
                values[p] = "NORMAL"
            elif kind == "string":
                values[p] = "text " + str(i)
            elif kind == "stringList":
                values[p] = []
            else:
                kw.update(min=0, max=1)
                values[p] = 0.5
            nodes.append(leaf(p, m + " setting " + str(i), kind, m, **kw))
        nodes.append({"path": base + ".advanced", "label": "Advanced", "type": "group", "children": [
            leaf(base + ".advanced.delayMs", "Delay (ms)", "long", m, min=0, max=60000),
            leaf(base + ".advanced.whitelist", "Whitelist", "string", m, readOnly=True, reason="edit via command (4 entries)"),
        ]})
        values[base + ".advanced.delayMs"] = 250
        section(m, nodes)
    return [x for x in sections if x], values


def plugin2_schema():
    nodes = [
        {"path": "enabled", "label": "Enabled", "type": "bool", "section": "Auto reply"},
        {"path": "message", "label": "Reply message", "type": "string", "section": "Auto reply"},
        {"path": "cooldownSeconds", "label": "Cooldown (s)", "type": "int", "section": "Auto reply", "min": 1, "max": 3600},
        {"path": "ignore", "label": "Ignored players", "type": "stringList", "section": "Auto reply"},
    ]
    return [{"path": "section:Auto reply", "label": "Auto reply", "type": "group", "children": nodes}], {
        "enabled": True, "message": "I'm AFK", "cooldownSeconds": 60, "ignore": ["Notch"]}


class FakeActivity:
    """Changing activity data: a queue that counts down, then online with the sorter working, an order, the
    pathfinder, a pearl load now and then, modules toggling."""

    MODULES = [("AntiAFK", "aquarius"), ("AutoEat", "aquarius"), ("AutoTotem", "aquarius"), ("AutoReconnect", "aquarius"),
               ("ChatHistory", "aquarius"), ("KillAura", "aquarius"), ("Spook", "aquarius"), ("QueueWarning", "aquarius"),
               ("AutoRespawn", "aquarius"), ("VisualRange", "aquarius"), ("Spammer", "aquarius"), ("AutoReply", "aquarius"),
               ("ActionLimiter", "aquarius"), ("AntiLeak", "aquarius"), ("CoordObf", "aquarius"), ("ReplayMod", "aquarius"),
               ("StashSorter", "qwerte-stash"), ("StashManager", "qwerte-stash"), ("PearlLoader", "pearlplus"),
               ("PearlQueue", "pearlplus")]

    def __init__(self, player):
        self.player = player
        self.start = time.time()
        self.queue = 40
        self.enabled = {m: m in ("AntiAFK", "AutoEat", "AutoTotem", "AutoReconnect", "QueueWarning", "StashSorter",
                                 "StashManager", "PearlLoader") for m, _ in self.MODULES}
        self.events = []
        self.sorted = 0
        self.order_step = 0
        self.pos = [120.0, 64.0, -340.0]
        self.state = "queue"
        self.since = {}
        self.event("Proxy", "info", "Connecting to 2b2t.org")
        self.event("Queue", "info", "In queue, position 40")

    def now_ms(self):
        return int(time.time() * 1000)

    def event(self, source, level, text):
        self.events.append({"at": self.now_ms(), "source": source, "level": level, "text": text})

    def step(self):
        t = time.time() - self.start
        if self.state == "queue":
            self.queue = max(0, self.queue - 3)
            if self.queue == 0:
                self.state = "online"
                self.event("Queue", "info", "Queue done, connected to 2b2t.org")
        else:
            self.sorted = (self.sorted + 1) % 31
            self.order_step = (self.order_step + 1) % 13
            self.pos[0] += 0.7
            self.pos[2] += 0.3
            if self.sorted == 10:
                self.event("Pearl Loader", "info", "Loaded pearl for Qwerttze")
            if self.sorted == 20:
                self.event("Stash Sorter", "warn", "Chest at 130 64 -330 is full, skipping")
            if self.order_step == 12:
                self.event("Stash Order", "info", "Order o-1a2b3c4d5e6f delivered (3 shulkers)")
            if self.sorted % 7 == 0:
                m = ["KillAura", "VisualRange", "Spammer", "CoordObf"][self.sorted % 4]
                self.enabled[m] = not self.enabled[m]
                self.event("Modules", "info", m + (" enabled" if self.enabled[m] else " disabled"))

    def snapshot(self):
        now = self.now_ms()
        c = {"state": self.state, "server": "2b2t.org", "dimension": "minecraft:overworld",
             "x": round(self.pos[0], 1), "y": self.pos[1], "z": round(self.pos[2], 1),
             "health": 17 if self.state == "online" else 20, "food": 13, "ping": 85 + int(time.time()) % 20,
             "uptimeSeconds": int(time.time() - self.start) + 3600, "spectators": 1, "controllingPlayer": None}
        if self.state == "queue":
            c["queuePosition"] = self.queue
            c["queueEta"] = "%dm" % max(1, self.queue // 3)
        cur = []
        if self.state == "queue":
            cur.append({"source": "Queue", "kind": "process", "title": "Waiting in queue", "detail": "position %d" % self.queue,
                        "since": int(self.start * 1000), "level": "info"})
        else:
            cur.append({"source": "Stash Sorter", "kind": "plugin", "title": 'Sorting: depositing into "Ores"',
                        "detail": "next: compact diamonds", "progress": {"done": self.sorted, "total": 30},
                        "since": now - 95000, "level": "info"})
            cur.append({"source": "Stash Order", "kind": "plugin", "title": "Order o-1a2b3c4d5e6f: filling shulker %d of 3" % (self.order_step // 4 + 1),
                        "detail": "128 x Diamond, 64 x Golden Apple", "progress": {"done": self.order_step, "total": 12},
                        "since": now - 40000, "level": "info"})
            cur.append({"source": "Pathfinder", "kind": "process", "title": "Walking to the drop-off chest",
                        "detail": "goal 130, 64, -330 (12 blocks)", "since": now - 6000, "level": "info"})
            if self.sorted > 25:
                cur.append({"source": "Pearl Loader", "kind": "plugin", "title": "Loading pearl for Qwerttze",
                            "detail": "queue: 1 more", "since": now - 2000, "level": "warn"})
        mods = [{"name": m, "plugin": p, "enabled": self.enabled[m]} for m, p in self.MODULES]
        for m in mods:
            if m["name"] == "AntiAFK" and m["enabled"]:
                m["status"] = "next move in 12s"
            if m["name"] == "StashSorter":
                m["status"] = "sorting" if self.state == "online" else "waiting for the server"
        events, self.events = self.events, []
        return {"at": now, "connection": c, "current": cur, "modules": mods,
                "plugins": [{"id": "qwerte-stash", "version": "1.5.0", "name": "QwerteStash"},
                            {"id": "pearlplus", "version": "2.1.0", "name": "PearlPlus"},
                            {"id": "autoreply", "version": "0.3.0", "name": "AutoReply"}],
                "events": events}


class DashBot:
    def __init__(self, c, bot_id, player, seconds, proxy=False, leak=False, activity=False):
        self.c = c
        self.bot_id = bot_id
        self.player = player
        self.end = time.time() + seconds
        self.schema = dash_schema()
        self.nodes = leaves(self.schema)
        self.values = dash_values()
        # scope -> {schema, nodes, values, need}; "plugin" shares the objects above
        self.scopes = {"plugin": {"schema": self.schema, "nodes": self.nodes, "values": self.values, "need": True}}
        self.proxy = proxy
        if proxy:
            ps, pv = proxy_schema(leak)
            # Like the real plugin: only "plugin" is pushed unasked; these wait for a need from the server
            self.scopes["proxy"] = {"schema": ps, "nodes": leaves(ps), "values": pv, "need": False, "active": False}
            qs, qv = plugin2_schema()
            self.scopes["plugin:autoreply"] = {"schema": qs, "nodes": leaves(qs), "values": qv, "need": False, "active": False}
        self.watching = False
        self.watching_activity = False
        self.activity = FakeActivity(player) if activity else None
        self.need_schema = True
        self.push_soon = True
        self.hold_until = 0     # "drift": a change the server isn't told about for a while
        self.lock = threading.Lock()

    def log(self, *a):
        print("[dash]", *a, flush=True)

    def post(self, path, body):
        return self.c.post("/api/v1/" + path, body)

    def hello(self):
        st, body = self.post("bot/hello", {"botId": self.bot_id, "player": self.player,
                                           "plugin": {"id": "qwerte-stash", "version": "0.4.0-fake"},
                                           "proxy": {"version": "aquarius-fake", "mc": "1.21.4"},
                                           "caps": ["settings", "console"] + (["proxy"] if self.proxy else []) + (["activity"] if self.activity else []),
                                           "scopes": list(self.scopes.keys())})
        self.log("hello", st, body)
        if st == 200:
            # A new session at the server: it gets the schema and values again (like the real plugin)
            self.need_schema = True
            for sc in self.scopes.values():
                sc["need"] = sc.get("active", True)
            self.push_soon = True
        return st == 200

    def push(self):
        st = 0
        for name, sc in self.scopes.items():
            with self.lock:
                values = copy.deepcopy(sc["values"])
            h = sha1(values)
            if not sc.get("active", True) or (not sc["need"] and sc.get("sent") == h and name != "plugin"):
                continue
            body = {"botId": self.bot_id, "scope": name, "schemaHash": sha1(sc["schema"]), "valuesHash": h, "values": values}
            if sc["need"]:
                body["schema"] = sc["schema"]
            st, reply = self.post("bot/state", body)
            if st == 200:
                sc["need"] = False
                sc["sent"] = h
                for n in (reply or {}).get("need", []):
                    if n.get("what") == "schema" and n.get("scope") in self.scopes:
                        self.scopes[n["scope"]]["need"] = True
                        self.scopes[n["scope"]]["active"] = True
                        self.push_soon = True
            else:
                self.log("state", name, st, reply)
        self.need_schema = False
        return st

    def state_loop(self):
        last = 0
        last_hash = None
        while time.time() < self.end:
            time.sleep(0.5)
            if time.time() < self.hold_until:
                continue
            every = 3 if self.watching else 60
            with self.lock:
                h = sha1(self.values)
            if self.push_soon or any(sc["need"] for sc in self.scopes.values()) or h != last_hash or time.time() - last >= every:
                self.push_soon = False
                if self.push() == 200:
                    last_hash = h
                last = time.time()

    def poll_loop(self):
        q = urllib.parse.quote(self.bot_id)
        while time.time() < self.end:
            st, body = self.c.get("/api/v1/bot/next?botId=" + q)
            if st == 200:
                if "watching" in body:
                    self.watching = bool(body["watching"])
                if "watchingActivity" in body:
                    self.watching_activity = bool(body["watchingActivity"])
                for n in body.get("need", []):
                    if n.get("what") == "schema" and n.get("scope") in self.scopes:
                        self.scopes[n["scope"]]["need"] = True
                        self.scopes[n["scope"]]["active"] = True
                    self.push_soon = True
                for job in body.get("jobs", []):
                    threading.Thread(target=self.run_job, args=(job,), daemon=True).start()
            elif st == 204:
                pass
            elif st == 429:
                time.sleep(3)
            else:
                self.log("next", st, body)
                time.sleep(2)
                self.hello()

    def results(self, results):
        st, _ = self.post("bot/results", {"botId": self.bot_id, "results": results})
        return st

    def run_job(self, job):
        if job.get("type") == "set":
            r = self.apply(job)
            self.log("set", job.get("path"), "=", json.dumps(job.get("value")), "->", r)
        else:
            r = self.command(job)
            self.log("command", job.get("command"), "->", r.get("ok"))
        self.results([r])
        self.push_soon = True

    def apply(self, job):
        path, value = job.get("path"), job.get("value")
        sc = self.scopes.get(job.get("scope"))
        if sc is None:
            return {"id": job["id"], "ok": False, "error": "unsupported scope"}
        node = sc["nodes"].get(path)
        values = sc["values"]
        r = {"id": job["id"]}
        if node is None or node.get("readOnly") or node.get("secret"):
            r.update(ok=False, error="can't change " + str(path))
            return r
        with self.lock:
            now = values.get(path)
            if "expect" in job and job["expect"] != now:
                r.update(ok=False, value=now, error="conflict")
                return r
            if path == "scanInterval" and value == 13:
                r.update(ok=False, value=now, error="the command failed: 13 is an unlucky interval")
                return r
            time.sleep(0.4)  # like running the stash command
            values[path] = value
            r.update(ok=True, value=values[path])
        return r

    def command(self, job):
        cmd = (job.get("command") or "").strip()
        rid = job["id"]
        word = cmd.split(" ")[0].lower()
        out = {"fields": [], "lines": []}
        if word == "help":
            out.update(title="Commands", color="primary",
                       description="Use **stash** commands. Type `help <command>` for details.",
                       fields=[{"name": "stash status", "value": "Shows the **sorter** state and `scan` progress"},
                               {"name": "stash order", "value": "Lists the open orders"}])
            return {"id": rid, "ok": True, "output": out}
        if word == "status":
            out.update(title="Stash sorter", color="success", description="Running. **42** containers known.",
                       fields=[{"name": "Stash", "value": "`" + str(self.values["stashName"]) + "`"}],
                       lines=["sorted 1,234 items", "next scan in 12 s"])
            return {"id": rid, "ok": True, "output": out}
        if word == "fail":
            out.update(title="Unknown command", color="error", description="No command named `fail`.")
            return {"id": rid, "ok": False, "output": out}
        if word == "auth":
            # Sensitive input: only the title and colour go back
            return {"id": rid, "ok": True, "sensitive": True, "output": {"title": "Logged in", "color": "success"}}
        if word == "slow":
            def later():
                time.sleep(2)
                self.results([{"id": rid, "ok": True, "more": True,
                               "output": {"title": "Done", "color": "#55ffff", "fields": [], "lines": ["finished after 2 s"]}}])
            threading.Thread(target=later, daemon=True).start()
            out.update(title="Working...", color="queue", lines=["started"])
            return {"id": rid, "ok": True, "output": out}
        if word == "drift":
            # Changed "from the terminal", and the server isn't told for 15 s: a settings save now conflicts
            with self.lock:
                self.values["stashName"] = "changed-in-terminal"
            self.hold_until = time.time() + 15
            out.update(lines=["stashName changed behind the dashboard's back"])
            return {"id": rid, "ok": True, "output": out}
        out["lines"] = ["ran: " + cmd]
        return {"id": rid, "ok": True, "output": out}

    def activity_loop(self):
        last = 0
        last_state = None
        while time.time() < self.end:
            time.sleep(1)
            self.activity.step()
            every = 2 if self.watching_activity else 30
            if time.time() - last < every and self.activity.state == last_state:
                continue
            snap = self.activity.snapshot()
            st, body = self.post("bot/activity", {"botId": self.bot_id, "activity": snap})
            if st != 200:
                self.log("activity", st, body)
                self.activity.events = snap["events"] + self.activity.events  # not acknowledged: send again
            last = time.time()
            last_state = self.activity.state

    def run(self):
        while time.time() < self.end and not self.hello():
            time.sleep(3)
        threading.Thread(target=self.poll_loop, daemon=True).start()
        if self.activity:
            threading.Thread(target=self.activity_loop, daemon=True).start()
        self.state_loop()


# ---------- delivery storage contents (PROTOCOL.md, "Additions: delivery storage contents") ----------

def delivery_chests(chests, used, unseen):
    """Fake delivery chests in fill order: chest 0 has `used` stacks (named shulkers, an empty one, loose items), the
    others are full; with `unseen`, the last one was never read."""
    kinds = [
        {"id": "minecraft:red_shulker_box", "name": "Gems", "count": 1, "maxStack": 1, "shulker": True,
         "contents": [{"id": "minecraft:diamond", "count": 1728}]},
        {"id": "minecraft:shulker_box", "name": "PvP Kit", "count": 1, "maxStack": 1, "shulker": True,
         "contents": [{"id": "minecraft:diamond_sword", "count": 1}, {"id": "minecraft:golden_apple", "count": 64},
                      {"id": "minecraft:totem_of_undying", "count": 1}]},
        {"id": "minecraft:blue_shulker_box", "name": "Blue Shulker Box", "count": 1, "maxStack": 1, "shulker": True, "contents": []},
        {"id": "minecraft:cobblestone", "name": "Cobblestone", "count": 64, "maxStack": 64},
        {"id": "minecraft:diamond_pickaxe", "name": "<b>Pick \u2026", "count": 1, "maxStack": 1, "damage": 900, "maxDamage": 1561},
    ]
    now = int(time.time() * 1000)
    out = []
    for n in range(chests):
        if n == 0:
            items = [dict(kinds[i % len(kinds)], slot=i) for i in range(used)]
            out.append({"n": 0, "size": 54, "full": used >= 54, "readAt": now - 90_000, "items": items})
        elif unseen and n == chests - 1:
            out.append({"n": n, "size": 27, "full": True, "readAt": 0, "items": None})
        else:
            items = [{"slot": i, "id": "minecraft:white_shulker_box", "name": "Order %d" % n, "count": 1, "maxStack": 1,
                      "shulker": True, "contents": [{"id": "minecraft:oak_log", "count": 1728}]} for i in range(54)]
            out.append({"n": n, "size": 54, "full": True, "readAt": now - 600_000, "items": items})
    return out


def delivery_hash(chests):
    """sha1 of the canonical JSON of the chests without their readAt fields."""
    bare = [{k: v for k, v in c.items() if k != "readAt"} for c in chests]
    return hashlib.sha1(json.dumps(bare, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def delivery(c, args):
    """Heartbeats with deliveryHash; sends POST /api/v1/delivery when the server says needDelivery."""
    chests = delivery_chests(args.chests, args.used, args.unseen)
    h = delivery_hash(chests)
    storage = {"autoExpand": args.auto_expand, "chests": args.chests, "full": max(0, args.chests - 1), "canExpand": args.auto_expand,
               "blocked": None if args.auto_expand else "auto expand is off"}
    end = time.time() + args.seconds
    sample_hash = build_index()["indexHash"]
    while time.time() < end:
        st, body = heartbeat(c, sample_hash, {"deliveryHash": h, "deliveryStorage": storage})
        need = isinstance(body, dict) and body.get("needDelivery")
        show("heartbeat (needDelivery %s)" % need, st, None)
        if need:
            report = {"version": 1, "server": SERVER, "stash": STASH, "player": PLAYER, "hash": h,
                      "readAt": max(ch["readAt"] for ch in chests), "chests": chests}
            show("delivery", *c.post("/api/v1/delivery", report))
        time.sleep(args.interval)


# ---------- live viewer (PROTOCOL.md, "Additions: live viewer") ----------

MOBS = ["zombie", "skeleton", "creeper", "spider", "cow", "sheep", "pig", "chicken", "enderman", "villager"]
ITEMS = ["minecraft:diamond", "minecraft:cobblestone", "minecraft:rotten_flesh", "minecraft:bone", "minecraft:arrow"]
CHAT = ["hello", "anyone selling elytra?", "lag lol", "gg", "where is spawn", "nice base", "brb"]


def fake_column(wx, wz):
    """A deterministic fake world: (map colour id, top y) for a block column. Water, sand, grass, forest, stone."""
    import math
    hgt = 64 + 6 * math.sin(wx / 23.0) + 5 * math.cos(wz / 17.0) + 3 * math.sin((wx + wz) / 9.0)
    y = int(hgt)
    if y < 62:
        return 12, 62, 62 - y  # water, its surface and depth
    if y < 63:
        return 2, y, 0         # sand
    if y > 70:
        return 11, y, 0        # stone
    if (wx * 7 + wz * 13) % 11 == 0:
        return 7, y + 4, 0     # a tree (foliage)
    return 1, y, 0             # grass


def fake_tile(cx, cz):
    """(c, h) base64 strings for chunk (cx, cz): shaded against the column to the north, like a vanilla map."""
    import base64
    import struct
    c = bytearray(256)
    h = bytearray(512)
    for lz in range(16):
        for lx in range(16):
            wx, wz = cx * 16 + lx, cz * 16 + lz
            colour, y, depth = fake_column(wx, wz)
            if colour == 12:
                d = depth + ((wx + wz) & 1) * 0.5
                shade = 2 if d < 2 else 1 if d < 4 else 0
            else:
                _, ny, _ = fake_column(wx, wz - 1)
                shade = 3 if y > ny else 2 if y == ny else 1
            i = lz * 16 + lx
            c[i] = colour * 4 + shade
            struct.pack_into("<h", h, i * 2, y)
    return base64.b64encode(bytes(c)).decode(), base64.b64encode(bytes(h)).decode()


class ViewerBot:
    """A bot that streams viewer frames while the server says someone watches: it walks a circle, has 30 entities
    around it (3 players, mobs, dropped items) and chats every 2 s. One request in flight, never queued."""

    def __init__(self, c, bot_id, player, seconds, hz, radius, max_frames, ents, walk_radius):
        self.c = c
        self.bot_id = bot_id
        self.player = player
        self.end = time.time() + seconds
        self.hz = hz
        self.radius = radius
        self.max_frames = max_frames
        self.walk_radius = walk_radius
        self.watching = False
        self.session = None
        self.need_full = True
        self.seq = 0
        self.frames = 0
        self.sent_tiles = set()
        self.pending_tiles = []
        self.chat_ring = []
        self.last_chat = 0
        self.last_hud = 0
        self.last_keepalive = 0
        self.hud = None
        self.lock = threading.Lock()
        import random
        self.rnd = random.Random(bot_id)
        self.ents = []
        for i in range(ents):
            kind = "player" if i < 3 else "item" if i >= ents - 7 else "mob"
            e = {"id": 1000 + i, "k": kind, "dx": self.rnd.uniform(-60, 60), "dz": self.rnd.uniform(-60, 60), "yaw": 0}
            if kind == "player":
                e["n"] = "Player%d" % (i + 1)
            elif kind == "mob":
                e["t"] = self.rnd.choice(MOBS)
            else:
                e["t"] = self.rnd.choice(ITEMS)
                e["c"] = self.rnd.randint(1, 64)
            self.ents.append(e)
        self.started = time.time()

    def log(self, *a):
        print("[viewer %s]" % self.bot_id, *a, flush=True)

    def hello(self):
        st, body = self.c.post("/api/v1/bot/hello", {"botId": self.bot_id, "player": self.player,
                                                       "plugin": {"id": "qwerte-stash", "version": "fake-viewer"},
                                                       "proxy": {"version": "aquarius-fake", "mc": "1.21.4"},
                                                       "caps": ["viewer"], "scopes": []})
        self.log("hello", st)
        return st == 200

    def poll_loop(self):
        q = urllib.parse.quote(self.bot_id)
        while time.time() < self.end:
            st, body = self.c.get("/api/v1/bot/next?botId=" + q)
            if st == 200 and isinstance(body, dict):
                with self.lock:
                    w = bool(body.get("watchingViewer"))
                    v = body.get("viewer") or {}
                    if w and v.get("session") != self.session:
                        self.session = v.get("session")
                        self.radius = min(12, int(v.get("radius", self.radius)))
                        self.hz = float(v.get("hz", self.hz))
                        self.need_full = True
                        self.log("watching, session", self.session)
                    if not w and self.watching:
                        self.log("not watching")
                    self.watching = w
            elif st == 204:
                pass
            elif st == 429:
                time.sleep(3)
            else:
                self.log("next", st, body)
                time.sleep(2)
                self.hello()

    def position(self, now):
        import math
        a = (now - self.started) * 4.0 / max(1.0, self.walk_radius)  # about 4 blocks/s
        x = self.walk_radius * math.cos(a)
        z = self.walk_radius * math.sin(a)
        # Minecraft yaw: 0 = +z (south), 90 = -x (west); walking along the circle's tangent
        dx, dz = -math.sin(a), math.cos(a)
        yaw = math.degrees(math.atan2(-dx, dz))
        return round(x, 1), 64.0, round(z, 1), round(yaw)

    def ents_now(self, x, z, now):
        import math
        out = []
        for e in self.ents:
            if e["k"] != "item":
                e["dx"] += self.rnd.uniform(-0.4, 0.4)
                e["dz"] += self.rnd.uniform(-0.4, 0.4)
                e["yaw"] = (e["yaw"] + self.rnd.randint(-10, 10)) % 360
            ex, ez = x + e["dx"], z + e["dz"]
            o = {"id": e["id"], "k": e["k"], "x": round(ex, 1), "y": 64.0, "z": round(ez, 1)}
            for f in ("n", "t", "c"):
                if f in e:
                    o[f] = e[f]
            if e["k"] != "item":
                o["yaw"] = e["yaw"]
            out.append(o)
        players = [o for o in out if o["k"] == "player"]
        rest = sorted([o for o in out if o["k"] != "player"], key=lambda o: (o["x"] - x) ** 2 + (o["z"] - z) ** 2)
        return players + rest

    def hud_now(self, now):
        t = int(now) // 5
        hotbar = [{"id": "minecraft:diamond_sword", "c": 1, "d": (t * 7) % 1561, "md": 1561}, {"id": "minecraft:golden_apple", "c": 12},
                  {"id": "minecraft:ender_pearl", "c": 16}, None, {"id": "minecraft:cobblestone", "c": 64}, None, None,
                  {"id": "minecraft:totem_of_undying", "c": 1}, {"id": "minecraft:cooked_beef", "c": 32}]
        return {"hp": float(20 - t % 8), "food": 20 - t % 5, "sat": 3.5, "xp": 30, "xpP": round((t % 10) / 10.0, 2),
                "armor": 15, "held": t % 9, "hotbar": hotbar,
                "armorSlots": [{"id": "minecraft:diamond_helmet", "c": 1}, {"id": "minecraft:diamond_chestplate", "c": 1},
                               {"id": "minecraft:diamond_leggings", "c": 1}, {"id": "minecraft:diamond_boots", "c": 1}],
                "offhand": {"id": "minecraft:totem_of_undying", "c": 1},
                # The main inventory (plugin 1.12.0+): 27 slots, one stack changing every 5 s, a worn pickaxe.
                "inv": [{"id": "minecraft:diamond_pickaxe", "c": 1, "d": 1200, "md": 1561},
                        {"id": "minecraft:dirt", "c": 1 + t % 64}, None, {"id": "minecraft:elytra", "c": 1, "d": 400, "md": 432}]
                       + [None] * 21 + [{"id": "minecraft:shulker_box", "c": 1}, {"id": "minecraft:obsidian", "c": 64}]}

    def chat_now(self, now):
        if now - self.last_chat < 2:
            return []
        self.last_chat = now
        n = int(now)
        k = ["public", "public", "public", "whisper", "system", "death"][n % 6]
        line = {"t": int(now * 1000), "k": k, "s": "Player%d" % (n % 3 + 1), "to": None, "m": self.rnd.choice(CHAT)}
        if k == "whisper":
            line["to"] = self.player
        if k in ("system", "death"):
            line["s"] = ""
            line["m"] = "Player%d was slain by Zombie" % (n % 3 + 1) if k == "death" else "Server restarts in 1 hour"
        self.chat_ring = (self.chat_ring + [line])[-20:]
        return [line]

    def frame(self, now):
        x, y, z, yaw = self.position(now)
        cx, cz = int(x) >> 4, int(z) >> 4
        r = self.radius
        want = {(cx + i, cz + j) for i in range(-r, r + 1) for j in range(-r, r + 1)}
        near = sorted(want, key=lambda p: (p[0] - cx) ** 2 + (p[1] - cz) ** 2)
        body = {"botId": self.bot_id, "v": 1, "session": self.session, "seq": self.seq, "t": int(now * 1000)}
        self.seq += 1
        body["self"] = {"x": x, "y": y, "z": z, "yaw": yaw}
        ents = self.ents_now(x, z, now)
        if self.need_full:
            body["full"] = True
            body["world"] = {"dim": "minecraft:overworld", "minY": -64, "height": 384}
            first = near[:300]
            body["tiles"] = [self.tile(p) for p in first]
            self.sent_tiles = set(first)
            self.pending_tiles = [p for p in near[300:]]
            body["ents"] = ents
            self.hud = self.hud_now(now)
            body["hud"] = self.hud
            self.last_hud = now
            self.chat_now(now)
            body["chat"] = list(self.chat_ring)
            return body
        drop = [p for p in self.sent_tiles if p not in want]
        for p in drop:
            self.sent_tiles.discard(p)
        missing = [p for p in near if p not in self.sent_tiles and p not in self.pending_tiles]
        self.pending_tiles = [p for p in self.pending_tiles if p in want] + missing
        batch, self.pending_tiles = self.pending_tiles[:150], self.pending_tiles[150:]
        if batch:
            body["tiles"] = [self.tile(p) for p in batch]
            self.sent_tiles.update(batch)
        if drop:
            body["drop"] = [list(p) for p in drop]
        body["ents"] = ents
        hud = self.hud_now(now)
        if hud != self.hud or now - self.last_hud >= 5:
            self.hud = hud
            self.last_hud = now
            body["hud"] = hud
        chat = self.chat_now(now)
        if chat:
            body["chat"] = chat
        return body

    def tile(self, p):
        c, h = fake_tile(p[0], p[1])
        return {"x": p[0], "z": p[1], "c": c, "h": h}

    def frame_loop(self):
        period = 1.0 / max(0.5, self.hz)
        backoff = 0
        while time.time() < self.end and (self.max_frames <= 0 or self.frames < self.max_frames):
            tick = time.time()
            with self.lock:
                watching, session = self.watching, self.session
            if not watching or not session:
                time.sleep(0.2)
                continue
            body = self.frame(tick)
            st, reply = self.c.post("/api/v1/bot/viewer", body)
            self.frames += 1
            if st == 200 and isinstance(reply, dict):
                backoff = 0
                self.need_full = False
                if reply.get("resync"):
                    self.need_full = True
                if not reply.get("watching"):
                    with self.lock:
                        self.watching = False
                    self.log("told to stop")
            elif st == 429:
                time.sleep(max(0.05, (reply or {}).get("retryMs", 200) / 1000.0) if isinstance(reply, dict) else 0.2)
            elif st == 409:
                self.hello()
                time.sleep(2)
                self.need_full = True
            else:
                backoff = min(10, backoff * 2 or 1)
                self.log("frame", st, reply if not isinstance(reply, str) else reply[:200])
                time.sleep(backoff)
                self.need_full = True
            left = period - (time.time() - tick)
            if left > 0:
                time.sleep(left)
        self.log("done after", self.frames, "frames")

    def run(self):
        while time.time() < self.end and not self.hello():
            time.sleep(3)
        threading.Thread(target=self.poll_loop, daemon=True).start()
        self.frame_loop()


def main():
    global SERVER, STASH, PLAYER, DELIVERY, BLOCKER, NAMING, CRAFTING
    p = argparse.ArgumentParser(description="Fake Qwerte addon for the Stash Manager server")
    p.add_argument("--url", default="http://127.0.0.1:8765")
    p.add_argument("--key")
    p.add_argument("--data", help="server data dir, to read the API key from config.properties")
    p.add_argument("--stash", default=STASH)
    p.add_argument("--server", default=SERVER)
    p.add_argument("--bearer", action="store_true", help="send the old Bearer header instead of signing (tests allowBearer)")
    p.add_argument("--player", default=PLAYER, help="the bot's player name (stash ownership; default FakeBot)")
    p.add_argument("--no-delivery", action="store_true", help="heartbeats say no delivery container is set (delivery: null)")
    p.add_argument("--delivery", help="delivery container as x,y,z[,dimension] (default 120,64,-340,minecraft:overworld)")
    p.add_argument("--blocker", help="orderBlocker to report, e.g. \"no delivery box\" (default: null)")
    p.add_argument("--naming", help="heartbeat naming: anvil:xpLevels such as true:30 or false:0, or null (default: not sent)")
    p.add_argument("--crafting", help="heartbeat crafting: enabled:table such as true:true or true:false, or null (default: not sent)")
    sub = p.add_subparsers(dest="cmd", required=True)
    push = sub.add_parser("push", help="push the sample index")
    push.add_argument("--no-tabs", action="store_true", help="an old-style index without tab/tabOrder/shulkers")
    push.add_argument("--other-tabs", action="store_true", help="like a proxy bot: every item has tab \"other\" (the server fills them in)")
    push.add_argument("--mc", default="1.21.11")
    push.add_argument("--extra-diamonds", type=int, default=0)
    push.add_argument("--shulker-colours", action="store_true",
                      help="add every shulker colour (empty, filled, mixed) and the other block-entity items")
    sub.add_parser("heartbeat")
    sub.add_parser("claim")
    st = sub.add_parser("status")
    st.add_argument("id")
    st.add_argument("status")
    st.add_argument("--phase")
    st.add_argument("--message")
    st.add_argument("--done", type=int)
    st.add_argument("--total", type=int)
    st.add_argument("--result-shulkers", type=int)
    sv = sub.add_parser("serve", help="send heartbeats for a while and answer rescan requests")
    sv.add_argument("--seconds", type=float, default=120)
    sv.add_argument("--interval", type=float, default=2)
    sv.add_argument("--containers", type=int, default=45, help="containers in the fake stash")
    sv.add_argument("--step", type=int, default=9, help="containers re-read per heartbeat during a rescan")
    sv.add_argument("--rescan-blocked", help="acknowledge rescans but report this reason (e.g. \"sorter off\")")
    sv.add_argument("--extra-diamonds", type=int, default=0)
    db = sub.add_parser("dashboard", help="be a dashboard bot (settings and console) for a while")
    db.add_argument("--seconds", type=float, default=120)
    db.add_argument("--bot-id", default="fake-bot-0001")
    db.add_argument("--proxy", action="store_true", help="also offer the proxy scope (a big fake AquariusProxy config) and plugin:autoreply")
    db.add_argument("--activity", action="store_true", help="also report activity (a queue, the sorter, an order, the pathfinder, events)")
    db.add_argument("--leak-secrets", action="store_true", help="a buggy bot: send values for secret settings (the server must drop them)")
    vw = sub.add_parser("viewer", help="be a live-viewer bot: walk a circle, 30 entities, chat every 2 s, frames while watched")
    vw.add_argument("--seconds", type=float, default=120)
    vw.add_argument("--bot-id", default="fake-viewer-01")
    vw.add_argument("--hz", type=float, default=5, help="frames per second while watched (the server's bot/next value wins)")
    vw.add_argument("--radius", type=int, default=8, help="chunk radius (the server's bot/next value wins; at most 12)")
    vw.add_argument("--max-frames", type=int, default=0, help="stop after this many frames (0: no limit)")
    vw.add_argument("--ents", type=int, default=30, help="entities around the bot (3 players, 7 items, the rest mobs)")
    vw.add_argument("--walk-radius", type=float, default=40, help="radius of the circle the bot walks, in blocks")
    dl = sub.add_parser("delivery", help="heartbeats with deliveryHash; send the delivery storage contents when asked")
    dl.add_argument("--seconds", type=float, default=120)
    dl.add_argument("--interval", type=float, default=5)
    dl.add_argument("--chests", type=int, default=1, help="delivery chests (1-16; after the first they are full)")
    dl.add_argument("--used", type=int, default=20, help="stacks already in the first chest (0-54)")
    dl.add_argument("--unseen", action="store_true", help="the last chest was never read (items: null)")
    dl.add_argument("--auto-expand", action="store_true", help="deliveryStorage.autoExpand true")
    run = sub.add_parser("run", help="claim one order and walk it to a finished status")
    run.add_argument("--fail", action="store_true")
    run.add_argument("--steps", type=int, default=3)
    run.add_argument("--delay", type=float, default=0.5)
    args = p.parse_args()

    SERVER, STASH, PLAYER = args.server, args.stash, args.player
    if args.no_delivery:
        DELIVERY = None
    elif args.delivery:
        parts = args.delivery.split(",")
        DELIVERY = {"x": int(parts[0]), "y": int(parts[1]), "z": int(parts[2]),
                    "dimension": parts[3] if len(parts) > 3 else "minecraft:overworld"}
    BLOCKER = args.blocker
    if args.naming == "null":
        NAMING = None
    elif args.naming:
        anvil, _, xp = args.naming.partition(":")
        NAMING = {"anvil": anvil == "true", "xpLevels": int(xp or "-1")}
    if args.crafting == "null":
        CRAFTING = None
    elif args.crafting:
        enabled, _, table = args.crafting.partition(":")
        CRAFTING = {"enabled": enabled == "true", "table": table == "true"}
    c = Client(args.url, load_key(args), bearer=args.bearer)
    sample_hash = build_index()["indexHash"]

    if args.cmd == "push":
        index = build_index(tabs=not args.no_tabs, mc=args.mc, extra_diamonds=args.extra_diamonds)
        if args.shulker_colours:
            add_colour_shulkers(index)
        if args.other_tabs:
            for i in index["items"]:
                i["tab"], i["tabOrder"] = "other", 1000000
            index["indexHash"] = hashlib.sha1(json.dumps(index["items"], sort_keys=True).encode()).hexdigest()
        show("index", *c.post("/api/v1/index", index))
    elif args.cmd == "heartbeat":
        show("heartbeat", *heartbeat(c, sample_hash))
    elif args.cmd == "dashboard":
        DashBot(c, args.bot_id, PLAYER, args.seconds, proxy=args.proxy, leak=args.leak_secrets, activity=args.activity).run()
    elif args.cmd == "delivery":
        delivery(c, args)
    elif args.cmd == "viewer":
        ViewerBot(c, args.bot_id, PLAYER, args.seconds, args.hz, args.radius, args.max_frames, args.ents, args.walk_radius).run()
    elif args.cmd == "serve":
        serve(c, args)
    elif args.cmd == "claim":
        show("claim", *c.post("/api/v1/orders/claim", {"server": SERVER, "stash": STASH}))
    elif args.cmd == "status":
        body = {"status": args.status}
        if args.phase is not None:
            body["phase"] = args.phase
        if args.message is not None:
            body["message"] = args.message
        if args.done is not None:
            body["progress"] = {"done": args.done, "total": args.total or args.done}
        if args.result_shulkers is not None:
            body["result"] = {"shulkers": args.result_shulkers, "lines": []}
        show("status", *c.post(f"/api/v1/orders/{args.id}/status", body))
    elif args.cmd == "run":
        status, body = c.post("/api/v1/orders/claim", {"server": SERVER, "stash": STASH})
        show("claim", status, body)
        if status != 200:
            return
        order = body["order"]
        oid = order["id"]
        names = order.get("names")
        if names:
            print("names: " + json.dumps(names))
        total = args.steps
        for step in range(1, total + 1):
            hs, hb = heartbeat(c, sample_hash)
            if oid in hb.get("orders", {}).get("cancel", []):
                show("heartbeat (cancel requested)", hs, hb)
                show("status", *c.post(f"/api/v1/orders/{oid}/status", {
                    "status": "cancelled", "phase": "stopped", "message": "Cancelled from the website."}))
                return
            show("status", *c.post(f"/api/v1/orders/{oid}/status", {
                "status": "in_progress", "phase": f"gathering {step}/{total}",
                "progress": {"done": step, "total": total}}))
            time.sleep(args.delay)
        if args.fail:
            show("status", *c.post(f"/api/v1/orders/{oid}/status", {
                "status": "failed", "message": "Delivery container is full."}))
        else:
            delivered = [{"key": l["key"], "delivered": l["count"]} for l in order["lines"]]
            show("status", *c.post(f"/api/v1/orders/{oid}/status", {
                "status": "delivered", "phase": "done", "progress": {"done": total, "total": total},
                "result": {"shulkers": 1, "lines": delivered}}))


if __name__ == "__main__":
    main()
