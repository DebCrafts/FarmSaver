package com.farmsaver.enchant;

import org.bukkit.Material;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public record EnchantDef(String id, String displayName, String description,
                         Set<ToolType> appliesTo, TreeMap<Integer, Tier> tiers) {

    public record Tier(int xpLevels, Map<Material, Integer> requirements) {}
}
