package com.farmsaver;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of every setting read on hot paths (block drops, block breaks, XP).
 * Built once in {@link FarmSaverPlugin#reloadAll()}, so gameplay events never do YAML path lookups.
 */
public final class Settings {
    private static final int MAX_TIER = 10;

    public record XpCfg(double perHarvest, double perBonus, double[] tierMult) {
        double tier(int t) {
            return t >= 0 && t < tierMult.length ? tierMult[t] : 1.0 + 0.75 * (t - 1);
        }
    }

    private final double[] fortuneFarming = new double[MAX_TIER + 1];
    private final double[] fortuneForaging = new double[MAX_TIER + 1];

    public final boolean autoReplantFortune;
    public final boolean autoPickupIncludesReplant;

    public final boolean replenishEnabled;
    public final boolean replenishConsume;
    public final boolean seedFromDrops;
    public final boolean replenishMissingMessage;

    public final int treecapMaxBlocks;
    public final boolean treecapRequireLeaves;
    public final boolean treecapSneakDisables;
    public final int treecapDurability;

    public final boolean fortuneBonusXp;
    public final double xpGlobal;
    private final XpCfg xpFarming;
    private final XpCfg xpForaging;

    private Settings(FileConfiguration c) {
        for (int t = 1; t <= MAX_TIER; t++) {
            fortuneFarming[t] = c.getDouble("fortune.farming." + t, 0);
            fortuneForaging[t] = c.getDouble("fortune.foraging." + t, 0);
        }
        autoReplantFortune = c.getBoolean("foraging.auto-replant", true);
        autoPickupIncludesReplant = c.getBoolean("auto-pickup.includes-replant", false);

        replenishEnabled = c.getBoolean("replenish.enabled", true);
        replenishConsume = c.getBoolean("replenish.consume-items", true);
        seedFromDrops = "drops-then-inventory".equalsIgnoreCase(c.getString("replenish.seed-source", "inventory"));
        replenishMissingMessage = c.getBoolean("replenish.missing-item-message", true);

        treecapMaxBlocks = Math.max(1, c.getInt("treecapitator.max-blocks", 256));
        treecapRequireLeaves = c.getBoolean("treecapitator.require-leaves", true);
        treecapSneakDisables = c.getBoolean("treecapitator.sneak-to-disable", true);
        treecapDurability = c.getInt("treecapitator.durability-per-block", 1);

        fortuneBonusXp = c.getBoolean("auraskills.xp.fortune-bonus-enabled", true);
        xpGlobal = c.getDouble("auraskills.xp.global-multiplier", 1.0);
        xpFarming = loadXp(c, "farming");
        xpForaging = loadXp(c, "foraging");
    }

    private static XpCfg loadXp(FileConfiguration c, String skill) {
        String base = "auraskills.xp." + skill + ".";
        double[] tiers = new double[MAX_TIER + 1];
        for (int t = 1; t <= MAX_TIER; t++)
            tiers[t] = c.getDouble(base + "tier-multiplier." + t, 1.0 + 0.75 * (t - 1));
        return new XpCfg(c.getDouble(base + "per-harvest", 2.0), c.getDouble(base + "per-bonus-item", 4.0), tiers);
    }

    public static Settings load(FileConfiguration c) {
        return new Settings(c);
    }

    /** Fortune value (+20 etc.) for the tier; 0 when the tier is not configured. */
    public double fortune(boolean farming, int tier) {
        double[] a = farming ? fortuneFarming : fortuneForaging;
        return tier >= 1 && tier < a.length ? a[tier] : 0;
    }

    /** Bonus AuraSkills XP for a Fortune harvest: (per-harvest + bonusItems*per-bonus-item) * tier * global. */
    public double bonusXp(boolean farming, int bonusItems, int tier) {
        if (!fortuneBonusXp) return 0;
        XpCfg x = farming ? xpFarming : xpForaging;
        return (x.perHarvest() + bonusItems * x.perBonus()) * x.tier(tier) * xpGlobal;
    }
}
