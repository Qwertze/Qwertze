# Crafting recipe table generator

Generates `out/<mc>.json` by decoding every `data/minecraft/recipe/*.json` of the vanilla data pack with the real
`Recipe.CODEC` (registries + tags bound exactly like `../creative-tabs/GenCreativeTabs`), so tags such as `#minecraft:planks`
are expanded to concrete item ids through `Ingredient.items()`.

Included: `crafting_shaped` / `crafting_shapeless` whose result stack has an empty component patch and whose ingredient
alternatives have no crafting remainder (`Item.getCraftingRemainder()` non-empty: buckets, honey/dragon's breath bottles).
`otherStations` maps items without an included recipe to smelting/blasting/smoking/campfire_cooking/stonecutting/smithing/
transmute/special (first match in that priority). `crafting_special_*` has no result in JSON; the item is derived from the
recipe name when it equals an item id (only `firework_star` does).

    cd stash-manager-server/dev/recipes
    ./run.sh 1.21.4
    ./run.sh 1.21.11

Same prerequisites as the creative-tabs generator. `out/*.json` are the deliverables; the rest of `out/` is scratch.
