package com.farmsaver.enchant;

import org.bukkit.inventory.ItemStack;

public enum ToolType {
    HOE, AXE, BOOTS;

    public static ToolType of(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        String n = item.getType().name();
        if (n.endsWith("_HOE")) return HOE;
        if (n.endsWith("_AXE")) return AXE;      // PICKAXE does not match ("_AXE" needs the underscore)
        if (n.endsWith("_BOOTS")) return BOOTS;
        return null;
    }
}
