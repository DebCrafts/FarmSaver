# FarmSaver

Paper plugin (1.20.6 → latest, Java 21) with custom farming / foraging enchantments and an `/fenc` GUI.

## Build in GitHub Codespaces
1. Open the repo in a Codespace (the included `.devcontainer` installs Java 21 + Gradle).
2. Run once: `gradle wrapper --gradle-version 8.10`
3. Build: `./gradlew build`
4. Jar: `build/libs/FarmSaver-1.0.0.jar` → put it in your server's `plugins/` folder.

## Commands
- `/fenc` – open the enchant GUI (`farmsaver.use`, default true)
- `/fenc reload` – reload config.yml + recipe.yml (`farmsaver.admin`)

## Enchantments
| Enchantment | Item | Notes |
|---|---|---|
| Farming Fortune I–III | Hoe | +20 / +60 / +120 (config.yml) |
| Foraging Fortune I–III | Axe | +20 / +60 / +120, auto-replant |
| Treecapitator | Axe | fells the whole tree, sneak to disable |
| Trample Protection | Boots | 100% farmland protection |
| Replenish | Hoe, Axe | Replants crops / saplings (costs 1 seed or sapling, toggle in config) |
| Auto Pickup | Hoe, Axe | Crops / logs go straight to your inventory |

Fortune: 100 = one guaranteed extra multiplier; the remainder is a % chance of one more.
All costs (XP levels, iron, diamond, netherite…) live in `recipe.yml`.

## Requirements (config.yml)
- `requirements.skill-level` – AuraSkills level needed per enchant tier (`enabled: true/false`).
  Unmet books show "Requirement not met" in the GUI.
- `requirements.items.enabled` – set `false` to ignore all item costs from recipe.yml (XP levels still apply).

## AuraSkills EXP multiplier books (new)
11 books: Archery, Fighting, Defense, Farming, Foraging, Mining, Fishing, Excavation, Agility, Alchemy, Enchanting EXP (levels I-III).

| Book | Items |
|---|---|
| Archery EXP | bow |
| Fighting EXP | swords, axes, maces, spears |
| Defense EXP | helmet, chestplate, leggings, boots |
| Farming EXP | **hoes and axes** |
| Foraging EXP | axes |
| Mining EXP | pickaxes |
| Fishing EXP | fishing rods |
| Excavation EXP | shovels |
| Agility EXP | boots |
| Alchemy EXP | leggings |
| Enchanting EXP | chestplates |

**How the multiplier works:** the plugin listens to AuraSkills' official `XpGainEvent` (HIGH priority) and sets
`final = event.getAmount() x multiplier`. `getAmount()` already contains AuraSkills' own multipliers, so ours is applied
once on top. Only the skill of the book is changed (Farming EXP never touches Mining XP). XP that FarmSaver adds
itself (Fortune bonus XP) is skipped, so nothing is multiplied twice.

**Defense EXP stacks:** every worn armor piece with the book adds its bonus (ADDITIVE: 1 + sum of each piece's bonus, e.g. four pieces at 1.5x = 3.0x, capped by `max-multiplier`). Other skills use the best single item unless you change `stacking`.

**Which item counts:** per skill, `exp-multiplier.skills.<skill>.sources` lists the equipment slots that are checked and
`stacking` decides what happens with several items (HIGHEST default, FIRST, ADDITIVE, MULTIPLICATIVE). Items that are only
in the inventory never count. A Farming EXP axe in your main hand multiplies the Farming XP you get for breaking crops with
it, and nothing else. Farming EXP and Foraging EXP can sit on the same axe.

Limits: if AuraSkills is missing the books can still be applied but do nothing. Custom (non-default) skills are ignored.

## Replenish (crops)
Harvest a **mature** crop with a Replenish hoe: vanilla drops are kept, the planting item is taken from your inventory
(`replenish.seed-source`), and the crop is replanted at age 0 next tick. A `BlockPlaceEvent` is fired so claim plugins can
veto it; on any failure the items are returned. Immature crops, missing seeds, and unsupported soil do nothing.

Supported: wheat, carrots, potatoes, beetroot, nether wart, cocoa (needs a jungle log), sweet berries, torchflower.
Optional (off by default): pitcher plant (untested), sugar cane / bamboo / cactus (replanted only if the base is broken).
Not replanted on purpose: melon & pumpkin (stems stay), glow berries (harvested by right-click), mushrooms.

## Costs & the material toggle
`recipe.yml` has `xp-levels` + `requirements` for every tier. `config.yml -> requirements.items.enabled: false` ignores
all item costs (XP levels are always charged). All checks pass before anything is taken, so a failed purchase costs nothing.

## Updating an existing server
Just replace the jar. New options are added to `config.yml` and new enchantments are appended to `recipe.yml`
automatically on start/reload; existing values are never changed.

## Build
`gradle wrapper --gradle-version 9.1.0` (once), then `./gradlew build`. Needs internet for Maven Central
(AuraSkills API 2.2.4, compile-only).

## Performance notes (1.1.0)
See CHANGELOG.md. Settings are cached in an immutable snapshot rebuilt on `/fenc reload`; the XP and block handlers read the
item metadata once; Replenish replants are batched into one task per tick. Re-measure on your own server (e.g. with Spark)
before and after updating - the numbers in the changelog are source-level findings plus one micro-benchmark, not server tick data.
