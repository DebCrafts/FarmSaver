# Changelog

## 1.1.0 - Defense EXP stacking, Replenish optimization, performance pass

### Defense EXP stacking
- Every EQUIPPED armor piece (helmet, chestplate, leggings, boots) carrying Defense EXP now contributes:
  `final = 1 + sum(multiplier - 1)` (ADDITIVE). Four pieces at 1.25x = 2.0x, mixed levels use each piece's own level.
- Only the four equipment slots are read at the moment XP is earned, so inventory/hand/container items never count and there
  is no cache that could go stale (armor swap, death, respawn, reconnect, reload are all correct by construction).
- Other 10 skills are unchanged (best single item by default). A slot listed twice in `sources` is counted once.
- `config.yml`: `exp-multiplier.skills.defense.stacking: ADDITIVE`. Existing configs: a one-time migration (`config-version: 2`)
  switches the old untouched default `HIGHEST` to `ADDITIVE`; any other value you set is kept.

### Replenish / whole-plugin performance (source-level audit - no live profiler was available)
Identified inefficiencies and fixes:
| Finding | Fix |
|---|---|
| `getLevel()` cloned the item metadata on every call; block-drop/break handlers called it 3-4x per event | tools without metadata exit first; metadata is read once per event (`EnchantManager.pdc/level`) |
| `ToolType.of` ran a chain of string `endsWith` checks on every drop/break/XP event | result cached per `Material` (micro-benchmark: ~19 ns -> ~3 ns per call) |
| ~10 YAML path lookups (string splitting) per harvest, repeated per log by Treecapitator | immutable `Settings` snapshot built on load/reload |
| One scheduler task per replanted crop | all replants of a tick handled by ONE task (queue) |
| Seed check = full inventory scan allocating `ItemStack`s, then `removeItem` scanned again | single allocation-free scan that removes in place (`takeItems`) |
| `setBlockData(.., true)` triggered a physics cascade per crop | `false` - support is verified with `BlockData#isSupported` first; `BlockPlaceEvent` still fired for protection |
| Treecapitator per log: 2 metadata reads for durability + one reflective AuraSkills call + ~26 Block objects for the search | durability computed up-front and written once, XP summed and paid once per tree, search uses packed coordinates |
| GUI listeners called `Inventory#getHolder()` for EVERY inventory click on the server (can snapshot chests) | routed by "player has our GUI open" (map lookup) |
| GUI rebuilt ~40 identical filler items per refresh | built once per GUI |
| Replenish "missing item" cooldown map never cleaned | cleared on quit and shutdown |
| Skill name lower-casing/allocations per XP event; defense lookup scanned lists | pre-parsed arrays, memoized skill lookup |
| `onDisable` left scheduled work | pending replants finished, tasks cancelled, GUIs closed |

No feature, cooldown, reward value, recipe cost or crop was removed or changed. Recursion safety: replanting never fires a
break event; Treecapitator uses a per-player guard only while that player's own tree is being processed.

### Tests
- `src/test/java/.../StackingMathTest.java` (JUnit 5, runs with `./gradlew test`).
- Everything else needs a live Paper server (see README).
