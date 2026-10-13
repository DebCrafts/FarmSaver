package com.farmsaver.enchant;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Item categories used by recipe.yml "applies-to". Detection is name-based on purpose, so newer
 * materials (mace, spears) never cause a compile or startup error on older Minecraft versions.
 * The result is cached per Material, so the string checks run once per material, not once per event.
 */
public enum ToolType {
    HOE, AXE, PICKAXE, SHOVEL, SWORD, MACE, SPEAR, BOW, CROSSBOW, FISHING_ROD,
    HELMET, CHESTPLATE, LEGGINGS, BOOTS;

    private static final ToolType[] VALUES = values();
    /** 0 = not computed yet, 1 = no tool type, n >= 2 = VALUES[n - 2]. Idempotent, so races are harmless. */
    private static final byte[] CACHE = new byte[Material.values().length];

    public static ToolType of(ItemStack item) {
        if (item == null) return null;
        Material m = item.getType();
        byte c = CACHE[m.ordinal()];
        if (c == 0) {
            ToolType t = detect(m.name());
            CACHE[m.ordinal()] = t == null ? 1 : (byte) (t.ordinal() + 2);
            return t;
        }
        return c == 1 ? null : VALUES[c - 2];
    }

    static ToolType detect(String n) {
        if (n.endsWith("_HOE")) return HOE;
        if (n.endsWith("_PICKAXE")) return PICKAXE;
        if (n.endsWith("_AXE")) return AXE;          // "_PICKAXE" is matched first; "_AXE" needs the underscore
        if (n.endsWith("_SHOVEL")) return SHOVEL;
        if (n.endsWith("_SWORD")) return SWORD;
        if (n.equals("MACE")) return MACE;
        if (n.endsWith("_SPEAR")) return SPEAR;      // 1.21.11+; simply never matches on older versions
        if (n.equals("BOW")) return BOW;
        if (n.equals("CROSSBOW")) return CROSSBOW;
        if (n.equals("FISHING_ROD")) return FISHING_ROD;
        if (n.endsWith("_HELMET")) return HELMET;
        if (n.endsWith("_CHESTPLATE")) return CHESTPLATE;
        if (n.endsWith("_LEGGINGS")) return LEGGINGS;
        if (n.endsWith("_BOOTS")) return BOOTS;
        return null;
    }
}
