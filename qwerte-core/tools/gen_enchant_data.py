"""Generates the compact enchantment tables (src/main/resources/com/porter/qwerte/core/enchant/enchant-<mc>.json)
from the vanilla data pack inside a Minecraft jar. These are game facts (numbers and id lists); no game code is read.

Usage: python -I gen_enchant_data.py <mc-version> <minecraft jar> <output json>
The jar is the Gradle/Loom cache's minecraft-extracted_server.jar (or any jar with data/minecraft/...).
"""
import json
import sys
import zipfile

mc, jar, out = sys.argv[1:4]
z = zipfile.ZipFile(jar)
names = set(z.namelist())


def rd(path):
    return json.loads(z.read(path).decode("utf-8"))


def strip(s):
    return s[len("minecraft:"):] if s.startswith("minecraft:") else s


def resolve_tag(kind, tag, seen=()):
    """Flatten tags/<kind>/<tag>.json (nested #tags resolved) into a sorted list of ids."""
    path = "data/minecraft/tags/%s/%s.json" % (kind, tag)
    if path not in names:
        raise SystemExit("missing tag " + path)
    res = set()
    for v in rd(path)["values"]:
        if isinstance(v, dict):  # optional entry form {id, required}
            v = v["id"]
        if v.startswith("#"):
            res |= set(resolve_tag(kind, strip(v[1:]), seen + (tag,)))
        else:
            res.add(strip(v))
    return sorted(res)


def ref_list(kind, ref):
    """supported_items / exclusive_set may be '#minecraft:tag', 'minecraft:id' or a list of ids."""
    if isinstance(ref, list):
        return sorted(strip(r) for r in ref), None
    if ref.startswith("#"):
        tag = strip(ref[1:])
        return resolve_tag(kind, tag), tag
    return [strip(ref)], None


ench_files = sorted(n for n in names if n.startswith("data/minecraft/enchantment/") and n.endswith(".json"))
enchants = []
item_tags = {}
excl_sets = {}
for f in ench_files:
    eid = f.rsplit("/", 1)[1][:-5]
    d = rd(f)
    item_cost = d["anvil_cost"]
    e = {
        "id": eid,
        "max": d["max_level"],
        "itemMult": item_cost,
        "bookMult": max(1, item_cost // 2),
    }
    items, tag = ref_list("item", d["supported_items"])
    if tag is None:
        tag = "inline_" + eid
    item_tags[tag] = items
    e["supports"] = tag
    if "exclusive_set" in d:
        members, xt = ref_list("enchantment", d["exclusive_set"])
        if xt is None:
            xt = "inline_" + eid
        excl_sets[xt] = members
        e["exclusive"] = xt
    enchants.append(e)

curses = resolve_tag("enchantment", "curse")  # tags/enchantment/curse.json

doc = {
    "mc": mc,
    "source": "vanilla data pack in the Minecraft %s jar: data/minecraft/enchantment/*.json "
              "(max_level, anvil_cost, supported_items, exclusive_set), tags/enchantment/exclusive_set/*, "
              "tags/item/enchantable/*, tags/enchantment/curse; bookMult = max(1, anvil_cost / 2)" % mc,
    "enchantments": enchants,
    "curses": curses,
    "exclusiveSets": dict(sorted(excl_sets.items())),
    "itemTags": dict(sorted(item_tags.items())),
}
with open(out, "w", encoding="utf-8", newline="\n") as fh:
    json.dump(doc, fh, indent=1, sort_keys=False)
    fh.write("\n")
print(mc, len(enchants), "enchantments,", len(item_tags), "item tags,", len(excl_sets), "exclusive sets")
