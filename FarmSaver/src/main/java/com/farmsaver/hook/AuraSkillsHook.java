package com.farmsaver.hook;

import com.farmsaver.FarmSaverPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Soft integration with AuraSkills through reflection, so the plugin compiles and runs
 * with or without AuraSkills installed.
 */
public final class AuraSkillsHook {
    private final FarmSaverPlugin plugin;
    private boolean active;
    private Object api;
    private Method getUser;
    private Method addSkillXp;
    private Method getSkillLevel;
    private Object farming;
    private Object foraging;

    public AuraSkillsHook(FarmSaverPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        active = false;
        if (!plugin.getConfig().getBoolean("auraskills.enabled", true)) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("AuraSkills")) return;
        try {
            Class<?> apiCls = Class.forName("dev.aurelium.auraskills.api.AuraSkillsApi");
            Class<?> userCls = Class.forName("dev.aurelium.auraskills.api.user.SkillsUser");
            Class<?> skillIface = Class.forName("dev.aurelium.auraskills.api.skill.Skill");
            Class<?> skillsEnum = Class.forName("dev.aurelium.auraskills.api.skill.Skills");

            api = apiCls.getMethod("get").invoke(null);
            getUser = apiCls.getMethod("getUser", UUID.class);
            addSkillXp = userCls.getMethod("addSkillXp", skillIface, double.class);
            getSkillLevel = userCls.getMethod("getSkillLevel", skillIface);
            for (Object c : skillsEnum.getEnumConstants()) {
                String n = ((Enum<?>) c).name();
                if (n.equals("FARMING")) farming = c;
                if (n.equals("FORAGING")) foraging = c;
            }
            active = farming != null && foraging != null;
            if (active) plugin.getLogger().info("Hooked into AuraSkills.");
        } catch (Throwable t) {
            plugin.getLogger().warning("AuraSkills found but the hook failed (" + t + "). Integration disabled.");
        }
    }

    public boolean isActive() {
        return active;
    }

    /** @return the player's AuraSkills level for "farming"/"foraging", or -1 if unavailable. */
    public int getLevel(Player p, String skill) {
        if (!active) return -1;
        try {
            Object user = getUser.invoke(api, p.getUniqueId());
            if (user == null) return -1;
            Object r = getSkillLevel.invoke(user, skill.equals("farming") ? farming : foraging);
            return r instanceof Number n ? n.intValue() : -1;
        } catch (Throwable t) {
            plugin.getLogger().warning("AuraSkills getLevel failed: " + t);
            return -1;
        }
    }

    /** @param skill "farming" or "foraging" */
    public void addXp(Player p, String skill, double amount) {
        if (!active || amount <= 0) return;
        try {
            Object user = getUser.invoke(api, p.getUniqueId());
            if (user == null) return;
            addSkillXp.invoke(user, skill.equals("farming") ? farming : foraging, amount);
        } catch (Throwable t) {
            plugin.getLogger().warning("AuraSkills addXp failed: " + t);
            active = false;
        }
    }
}
