package com.farmsaver.enchant;

import com.farmsaver.FarmSaverPlugin;
import com.farmsaver.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;

/**
 * Loads recipe.yml and stores enchant levels in the item's PersistentDataContainer.
 * (PDC is used instead of registry enchantments so it works on every Paper version.)
 */
public final class EnchantManager {
    public static final String FARMING_FORTUNE = "farming_fortune";
    public static final String FORAGING_FORTUNE = "foraging_fortune";
    public static final String TREECAPITATOR = "treecapitator";
    public static final String TRAMPLE_PROTECTION = "trample_protection";
    public static final String REPLENISH = "replenish";
    public static final String AUTO_PICKUP = "auto_pickup";

    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private final FarmSaverPlugin plugin;
    private final Map<String, EnchantDef> defs = new LinkedHashMap<>();
    private final Map<String, NamespacedKey> keys = new HashMap<>();

    public EnchantManager(FarmSaverPlugin plugin) {
        this.plugin = plugin;
    }

    public static String roman(int n) {
        return n >= 0 && n < ROMAN.length ? ROMAN[n] : String.valueOf(n);
    }

    public void load() {
        defs.clear();
        keys.clear();
        File file = new File(plugin.getDataFolder(), "recipe.yml");
        if (!file.exists()) plugin.saveResource("recipe.yml", false);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("enchantments");
        if (root == null) {
            plugin.getLogger().warning("recipe.yml has no 'enchantments' section!");
            return;
        }
        for (String rawId : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(rawId);
            if (s == null) continue;
            String id = rawId.toLowerCase(Locale.ROOT);

            EnumSet<ToolType> applies = EnumSet.noneOf(ToolType.class);
            for (String a : s.getStringList("applies-to")) {
                try {
                    applies.add(ToolType.valueOf(a.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("recipe.yml: unknown tool type '" + a + "' in " + id);
                }
            }

            TreeMap<Integer, EnchantDef.Tier> tiers = new TreeMap<>();
            ConfigurationSection ts = s.getConfigurationSection("tiers");
            if (ts != null) {
                for (String tk : ts.getKeys(false)) {
                    int tier;
                    try {
                        tier = Integer.parseInt(tk);
                    } catch (NumberFormatException ex) {
                        continue;
                    }
                    ConfigurationSection t = ts.getConfigurationSection(tk);
                    if (t == null) continue;
                    Map<Material, Integer> req = new LinkedHashMap<>();
                    ConfigurationSection rs = t.getConfigurationSection("requirements");
                    if (rs != null) {
                        for (String mk : rs.getKeys(false)) {
                            Material m = Material.matchMaterial(mk);
                            if (m == null) {
                                plugin.getLogger().warning("recipe.yml: unknown material '" + mk + "' in " + id);
                                continue;
                            }
                            req.put(m, rs.getInt(mk));
                        }
                    }
                    tiers.put(tier, new EnchantDef.Tier(t.getInt("xp-levels", 0), req));
                }
            }
            defs.put(id, new EnchantDef(id, s.getString("display-name", "&f" + id),
                    s.getString("description", ""), applies, tiers));
            keys.put(id, new NamespacedKey(plugin, id));
        }
        plugin.getLogger().info("Loaded " + defs.size() + " enchantments from recipe.yml");
    }

    public EnchantDef get(String id) {
        return defs.get(id);
    }

    public Collection<EnchantDef> all() {
        return defs.values();
    }

    public List<EnchantDef> forType(ToolType type) {
        List<EnchantDef> out = new ArrayList<>();
        for (EnchantDef d : defs.values()) if (d.appliesTo().contains(type)) out.add(d);
        return out;
    }

    public int getLevel(ItemStack item, String id) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return 0;
        NamespacedKey k = keys.get(id);
        if (k == null) return 0;
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(k, PersistentDataType.INTEGER, 0);
    }

    public int getLevel(ItemStack item, EnchantDef def) {
        return getLevel(item, def.id());
    }

    public void setLevel(ItemStack item, EnchantDef def, int level) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(keys.get(def.id()), PersistentDataType.INTEGER, level);
        refreshLore(meta);
        item.setItemMeta(meta);
    }

    private void refreshLore(ItemMeta meta) {
        Set<String> ours = new HashSet<>();
        for (EnchantDef d : defs.values())
            for (int t : d.tiers().keySet()) ours.add(Text.plain(d.displayName()) + " " + roman(t));

        List<Component> out = new ArrayList<>();
        for (EnchantDef d : defs.values()) {
            int lvl = meta.getPersistentDataContainer().getOrDefault(keys.get(d.id()), PersistentDataType.INTEGER, 0);
            if (lvl > 0) out.add(Text.c(d.displayName() + " " + roman(lvl)));
        }
        List<Component> old = meta.lore();
        if (old != null) for (Component c : old) if (!ours.contains(Text.plain(c))) out.add(c);
        meta.lore(out.isEmpty() ? null : out);
    }

    public record SkillReq(String skill, int level) {}

    public boolean itemsRequired() {
        return plugin.getConfig().getBoolean("requirements.items.enabled", true);
    }

    /** @return the skill requirement for this tier, or null if disabled / not configured. */
    public SkillReq skillReq(EnchantDef def, int tier, ToolType type) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("requirements.skill-level.enabled", true)) return null;
        String base = "requirements.skill-level.enchants." + def.id();
        String skill = null;
        var byTool = cfg.getConfigurationSection(base + ".skill-by-tool");
        if (byTool != null && type != null) skill = byTool.getString(type.name());
        if (skill == null) skill = cfg.getString(base + ".skill");
        if (skill == null) return null;
        int lvl = cfg.getInt(base + ".levels." + tier, 0);
        if (lvl <= 0) return null;
        return new SkillReq(skill.toLowerCase(Locale.ROOT), lvl);
    }

    public boolean skillMet(Player p, SkillReq r) {
        if (r == null) return true;
        int cur = plugin.aura().getLevel(p, r.skill());
        if (cur < 0) return !plugin.getConfig().getBoolean("requirements.skill-level.block-without-auraskills", false);
        return cur >= r.level();
    }

    public int count(Player p, Material m) {
        ItemStack ref = new ItemStack(m);
        int total = 0;
        for (ItemStack it : p.getInventory().getStorageContents())
            if (it != null && it.isSimilar(ref)) total += it.getAmount();
        return total;
    }

    /** @return null on success, otherwise a config path of the failure message. */
    public String apply(Player p, ItemStack tool, EnchantDef def, int tier) {
        ToolType type = ToolType.of(tool);
        if (type == null || !def.appliesTo().contains(type)) return "messages.not-applicable";
        EnchantDef.Tier cost = def.tiers().get(tier);
        if (cost == null) return "messages.unknown";
        int cur = getLevel(tool, def);
        if (cur >= tier) return "messages.already-has";
        if (tier > cur + 1) return "messages.need-previous";

        boolean free = p.getGameMode() == GameMode.CREATIVE && plugin.getConfig().getBoolean("gui.creative-free", false);
        if (!free) {
            if (!skillMet(p, skillReq(def, tier, type))) return "messages.not-enough-skill";
            if (p.getLevel() < cost.xpLevels()) return "messages.not-enough-xp";
            boolean items = itemsRequired();
            if (items)
                for (Map.Entry<Material, Integer> e : cost.requirements().entrySet())
                    if (count(p, e.getKey()) < e.getValue()) return "messages.not-enough-items";
            p.giveExpLevels(-cost.xpLevels());
            if (items)
                for (Map.Entry<Material, Integer> e : cost.requirements().entrySet())
                    p.getInventory().removeItem(new ItemStack(e.getKey(), e.getValue()));
        }
        setLevel(tool, def, tier);
        return null;
    }
}
