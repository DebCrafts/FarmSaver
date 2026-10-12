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
