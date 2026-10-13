package com.farmsaver;

import com.farmsaver.command.FencCommand;
import com.farmsaver.enchant.EnchantManager;
import com.farmsaver.exp.ExpMultiplierService;
import com.farmsaver.replenish.ReplenishService;
import com.farmsaver.listener.SkillXpListener;
import com.farmsaver.gui.EnchantGui;
import com.farmsaver.gui.GuiListener;
import com.farmsaver.hook.AuraSkillsHook;
import com.farmsaver.listener.FarmListener;
import com.farmsaver.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.Set;

public final class FarmSaverPlugin extends JavaPlugin {
    private EnchantManager enchants;
    private AuraSkillsHook aura;
    private ExpMultiplierService exp;
    private ReplenishService replenish;
    private boolean xpListenerRegistered;
    private volatile Settings settings;
    private static final int CONFIG_VERSION = 2;
    private final Set<Material> crops = EnumSet.noneOf(Material.class);
    private final Set<Material> skipMaturity = EnumSet.noneOf(Material.class);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        enchants = new EnchantManager(this);
        aura = new AuraSkillsHook(this);
        exp = new ExpMultiplierService(this);
        replenish = new ReplenishService(this);
        reloadAll();

        getServer().getPluginManager().registerEvents(new FarmListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(), this);

        FencCommand cmd = new FencCommand(this);
        getCommand("fenc").setExecutor(cmd);
        getCommand("fenc").setTabCompleter(cmd);
    }

    @Override
    public void onDisable() {
        // Close open GUIs so tools in the tool slot are returned to players.
        EnchantGui.closeAll();
        replenish.shutdown();
        getServer().getScheduler().cancelTasks(this);
    }

    public void reloadAll() {
        reloadConfig();
        migrateConfig();
        mergeConfigDefaults();
        settings = Settings.load(getConfig());
        enchants.load();
        aura.init();
        exp.reload();
        replenish.reload();
        registerXpListener();
        loadMaterials("farming.crops", crops);
        loadMaterials("farming.skip-maturity-check", skipMaturity);
    }

    /** The XP listener references AuraSkills classes, so it is only created when AuraSkills is present. */
    private void registerXpListener() {
        if (xpListenerRegistered || !aura.isActive()) return;
        try {
            Class.forName("dev.aurelium.auraskills.api.event.skill.XpGainEvent");
            getServer().getPluginManager().registerEvents(new SkillXpListener(this), this);
            xpListenerRegistered = true;
            getLogger().info("EXP multiplier enchantments are active (AuraSkills XpGainEvent).");
        } catch (Throwable t) {
            getLogger().warning("AuraSkills XpGainEvent not found - EXP multiplier enchantments are inactive: " + t);
        }
    }

    /**
     * One-time migration (config-version 1 -> 2): Defense EXP now stacks across armor pieces. The old shipped
     * default was HIGHEST, so only that untouched default is switched to ADDITIVE; any other value is kept.
     */
    private void migrateConfig() {
        var cfg = getConfig();
        int stored = cfg.contains("config-version", true) ? cfg.getInt("config-version") : 1;
        if (stored >= CONFIG_VERSION) return;
        String path = "exp-multiplier.skills.defense.stacking";
        if (cfg.contains(path, true) && "HIGHEST".equalsIgnoreCase(cfg.getString(path))) {
            cfg.set(path, "ADDITIVE");
            getLogger().info("config.yml: Defense EXP stacking switched from HIGHEST to ADDITIVE (every armor piece stacks).");
        }
        cfg.set("config-version", CONFIG_VERSION);
        saveConfig();
    }

    /** Adds options that are new in this version to an existing config.yml (existing values are never changed). */
    private void mergeConfigDefaults() {
        var cfg = getConfig();
        var defaults = cfg.getDefaults();
        if (defaults == null) return;
        boolean missing = false;
        for (String k : defaults.getKeys(true))
            if (!defaults.isConfigurationSection(k) && !cfg.contains(k, true)) {
                missing = true;
                break;
            }
        if (!missing) return;
        cfg.options().copyDefaults(true);
        saveConfig();
        getLogger().info("config.yml: added new default options (existing values untouched).");
    }

    private void loadMaterials(String path, Set<Material> target) {
        target.clear();
        for (String s : getConfig().getStringList(path)) {
            Material m = Material.matchMaterial(s);
            if (m == null) getLogger().warning("config.yml: unknown material '" + s + "' in " + path);
            else target.add(m);
        }
    }

    public EnchantManager enchants() { return enchants; }
    public AuraSkillsHook aura() { return aura; }
    public ExpMultiplierService exp() { return exp; }
    public ReplenishService replenish() { return replenish; }
    public Set<Material> crops() { return crops; }
    public Set<Material> skipMaturity() { return skipMaturity; }

    public Settings settings() { return settings; }

    public String raw(String path) {
        return getConfig().getString(path, "&c" + path);
    }

    public Component msg(String path) {
        String key = path.startsWith("messages.") ? path : "messages." + path;
        return Text.c(raw("messages.prefix") + raw(key));
    }
}
