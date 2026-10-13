package com.farmsaver.exp;

import com.farmsaver.FarmSaverPlugin;
import com.farmsaver.enchant.EnchantDef;
import com.farmsaver.enchant.EnchantManager;
import com.farmsaver.enchant.ToolType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Works out the EXP multiplier of a player for one AuraSkills skill.
 * Everything is parsed in reload(); an XP event only reads the equipment slots of that skill.
 *
 * Defense EXP stacks (ADDITIVE by default): every EQUIPPED armor piece carrying the book adds its bonus,
 * final = 1 + sum(multiplier - 1). Items in the inventory, hand or containers never count, because only
 * the configured equipment slots are read at the moment the XP is earned (so there is no stale cache).
 */
public final class ExpMultiplierService {
    public enum Slot { MAIN_HAND, OFF_HAND, HELMET, CHESTPLATE, LEGGINGS, BOOTS }

    /** @param mult multiplier per level, index = level (floor semantics for gaps, last value above the top) */
    private record SkillCfg(EnchantDef def, Slot[] sources, Stacking stacking, double[] mult) {}

    private static final Map<String, List<Slot>> DEFAULT_SOURCES = Map.ofEntries(
            Map.entry("archery", List.of(Slot.MAIN_HAND, Slot.OFF_HAND)),
            Map.entry("fighting", List.of(Slot.MAIN_HAND)),
            Map.entry("defense", List.of(Slot.HELMET, Slot.CHESTPLATE, Slot.LEGGINGS, Slot.BOOTS)),
            Map.entry("farming", List.of(Slot.MAIN_HAND)),
            Map.entry("foraging", List.of(Slot.MAIN_HAND)),
            Map.entry("mining", List.of(Slot.MAIN_HAND)),
            Map.entry("fishing", List.of(Slot.MAIN_HAND, Slot.OFF_HAND)),
            Map.entry("excavation", List.of(Slot.MAIN_HAND)),
            Map.entry("agility", List.of(Slot.BOOTS)),
            Map.entry("alchemy", List.of(Slot.LEGGINGS)),
            Map.entry("enchanting", List.of(Slot.CHESTPLATE)));

    private static final SkillCfg NONE = new SkillCfg(null, new Slot[0], Stacking.HIGHEST, new double[0]);

    private final FarmSaverPlugin plugin;
    /** keyed by UPPER-case AuraSkills skill name (what Skill#name() returns) */
    private final Map<String, SkillCfg> skills = new HashMap<>();
    /** memo for raw event names, including "no multiplier for this skill" */
    private final Map<String, SkillCfg> lookup = new ConcurrentHashMap<>();
    private boolean enabled;
    private double maxMultiplier = 10.0;

    public ExpMultiplierService(FarmSaverPlugin plugin) {
        this.plugin = plugin;
    }

    public static String enchantId(String skill) {
        return "exp_" + skill;
    }

    public void reload() {
        skills.clear();
        lookup.clear();
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("exp-multiplier.enabled", true);
        maxMultiplier = cfg.getDouble("exp-multiplier.max-multiplier", 10.0);
        if (!(maxMultiplier >= 1.0)) {
            plugin.getLogger().warning("exp-multiplier.max-multiplier must be >= 1, using 10.0");
            maxMultiplier = 10.0;
        }
        ConfigurationSection root = cfg.getConfigurationSection("exp-multiplier.skills");
        if (!enabled || root == null) return;

        for (String skill : DEFAULT_SOURCES.keySet()) {
            ConfigurationSection s = root.getConfigurationSection(skill);
            if (s == null) continue;
            EnchantDef def = plugin.enchants().get(enchantId(skill));
            if (def == null) continue;                                // disabled/missing in recipe.yml

            LinkedHashSet<Slot> srcSet = new LinkedHashSet<>();      // a slot can never be counted twice
            for (String n : s.getStringList("sources")) {
                try {
                    srcSet.add(Slot.valueOf(n.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("config.yml: unknown source '" + n + "' for exp-multiplier." + skill);
                }
            }
            if (srcSet.isEmpty()) srcSet.addAll(DEFAULT_SOURCES.get(skill));

            // Defense stacks by default, every other skill keeps "best single item".
            Stacking fallback = skill.equals("defense") ? Stacking.ADDITIVE : Stacking.HIGHEST;
            Stacking stacking = fallback;
            String raw = s.getString("stacking");
            if (raw != null) {
                try {
                    stacking = Stacking.valueOf(raw.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("config.yml: invalid stacking '" + raw + "' for exp-multiplier." + skill + ", using " + fallback);
                }
            }

            TreeMap<Integer, Double> levels = new TreeMap<>();
            ConfigurationSection ls = s.getConfigurationSection("levels");
            if (ls != null) {
                for (String k : ls.getKeys(false)) {
                    int lvl;
                    try {
                        lvl = Integer.parseInt(k);
                    } catch (NumberFormatException ex) {
                        plugin.getLogger().warning("config.yml: bad level '" + k + "' in exp-multiplier." + skill);
                        continue;
                    }
                    double m = ls.getDouble(k, Double.NaN);
                    if (lvl < 1 || Double.isNaN(m) || Double.isInfinite(m) || m <= 0) {
                        plugin.getLogger().warning("config.yml: invalid level/multiplier for exp-multiplier." + skill + " level " + k + " (must be > 0)");
                        continue;
                    }
                    levels.put(lvl, Math.min(m, maxMultiplier));
                }
            }
            if (levels.isEmpty()) {
                plugin.getLogger().warning("config.yml: exp-multiplier." + skill + " has no valid levels, skill ignored");
                continue;
            }
            double[] mult = new double[levels.lastKey() + 1];
            double cur = 0;
            for (int l = 0; l < mult.length; l++) {
                Double v = levels.get(l);
                if (v != null) cur = v;
                mult[l] = cur;
            }
            skills.put(skill.toUpperCase(Locale.ROOT), new SkillCfg(def, srcSet.toArray(new Slot[0]), stacking, mult));
        }
    }

    /**
     * @param skillName the AuraSkills skill name as given by Skill#name()
     * @return the multiplier (1.0 = no change)
     */
    public double multiplier(Player p, String skillName) {
        if (!enabled) return 1.0;
        SkillCfg c = lookup.get(skillName);
        if (c == null) {
            c = skills.get(skillName.toUpperCase(Locale.ROOT));
            lookup.put(skillName, c == null ? NONE : c);
        }
        if (c == NONE || c == null) return 1.0;

        EnchantManager mgr = plugin.enchants();
        Slot[] sources = c.sources();
        double[] vals = null;
        int n = 0;
        for (Slot slot : sources) {
            ItemStack it = item(p, slot);
            if (it == null || !it.hasItemMeta()) continue;             // cheap: no metadata, no enchantment
            ToolType t = ToolType.of(it);
            if (t == null || !c.def().appliesTo().contains(t)) continue;   // item no longer compatible
            int lvl = mgr.getLevel(it, c.def());
            if (lvl <= 0) continue;
            double[] mult = c.mult();
            double m = mult[Math.min(lvl, mult.length - 1)];
            if (m <= 0) continue;
            if (vals == null) vals = new double[sources.length];
            vals[n++] = m;
            if (c.stacking() == Stacking.FIRST) break;
        }
        return StackingMath.combine(c.stacking(), vals, n, maxMultiplier);
    }

    private ItemStack item(Player p, Slot slot) {
        PlayerInventory inv = p.getInventory();
        return switch (slot) {
            case MAIN_HAND -> inv.getItemInMainHand();
            case OFF_HAND -> inv.getItemInOffHand();
            case HELMET -> inv.getHelmet();
            case CHESTPLATE -> inv.getChestplate();
            case LEGGINGS -> inv.getLeggings();
            case BOOTS -> inv.getBoots();
        };
    }
}
