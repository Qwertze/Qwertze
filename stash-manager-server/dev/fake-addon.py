#!/usr/bin/env python3
"""Fake Qwerte addon for testing the Stash Manager server without Minecraft.

Standard library only. Run it isolated so nothing is imported from the current folder:

    python -I dev/fake-addon.py --url http://127.0.0.1:8768 --data <dataDir> push
    python -I dev/fake-addon.py --url ... --data ... heartbeat
    python -I dev/fake-addon.py --url ... --data ... --no-delivery --blocker "no delivery box" heartbeat
    python -I dev/fake-addon.py --url ... --data ... claim
    python -I dev/fake-addon.py --url ... --data ... status o-0123456789ab in_progress --phase "gathering" --done 1 --total 3
    python -I dev/fake-addon.py --url ... --data ... serve        # heartbeats for 2 min; answers rescan requests
    python -I dev/fake-addon.py --url ... --data ... serve --rescan-blocked "sorter off"
    python -I dev/fake-addon.py --url ... --data ... dashboard    # a dashboard bot: settings + console for 2 min
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


def heartbeat(c, index_hash, extra=None):
    body = {"server": SERVER, "stash": STASH, "player": PLAYER, "indexHash": index_hash,
            "delivery": DELIVERY, "orderBlocker": BLOCKER}
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


def main():
    global SERVER, STASH, PLAYER, DELIVERY, BLOCKER
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
    sub = p.add_subparsers(dest="cmd", required=True)
    push = sub.add_parser("push", help="push the sample index")
    push.add_argument("--no-tabs", action="store_true", help="an old-style index without tab/tabOrder/shulkers")
    push.add_argument("--mc", default="1.21.11")
    push.add_argument("--extra-diamonds", type=int, default=0)
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
    c = Client(args.url, load_key(args), bearer=args.bearer)
    sample_hash = build_index()["indexHash"]

    if args.cmd == "push":
        index = build_index(tabs=not args.no_tabs, mc=args.mc, extra_diamonds=args.extra_diamonds)
        show("index", *c.post("/api/v1/index", index))
    elif args.cmd == "heartbeat":
        show("heartbeat", *heartbeat(c, sample_hash))
    elif args.cmd == "dashboard":
        DashBot(c, args.bot_id, PLAYER, args.seconds, proxy=args.proxy, leak=args.leak_secrets, activity=args.activity).run()
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
