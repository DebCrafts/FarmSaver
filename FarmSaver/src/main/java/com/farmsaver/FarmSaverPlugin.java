package com.farmsaver;

import com.farmsaver.command.FencCommand;
import com.farmsaver.enchant.EnchantManager;
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
    private final Set<Material> crops = EnumSet.noneOf(Material.class);
    private final Set<Material> skipMaturity = EnumSet.noneOf(Material.class);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        enchants = new EnchantManager(this);
        aura = new AuraSkillsHook(this);
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
    }

    public void reloadAll() {
        reloadConfig();
        enchants.load();
        aura.init();
        loadMaterials("farming.crops", crops);
        loadMaterials("farming.skip-maturity-check", skipMaturity);
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
    public Set<Material> crops() { return crops; }
    public Set<Material> skipMaturity() { return skipMaturity; }

    /** @param type "farming" or "foraging" */
    public double fortune(String type, int tier) {
        return getConfig().getDouble("fortune." + type + "." + tier, 0);
    }

    public String raw(String path) {
        return getConfig().getString(path, "&c" + path);
    }

    public Component msg(String path) {
        String key = path.startsWith("messages.") ? path : "messages." + path;
        return Text.c(raw("messages.prefix") + raw(key));
    }
}
