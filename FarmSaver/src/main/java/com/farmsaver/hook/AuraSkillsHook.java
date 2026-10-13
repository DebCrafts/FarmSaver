package com.farmsaver.hook;

import com.farmsaver.FarmSaverPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Soft integration with AuraSkills (reflection, so the plugin runs with or without it).
 * Verified against the AuraSkills API docs: AuraSkillsApi#get(), #getUser(UUID),
 * SkillsUser#addSkillXp(Skill,double), SkillsUser#getSkillLevel(Skill), Skills enum.
 */
public final class AuraSkillsHook {
    private final FarmSaverPlugin plugin;
    private boolean active;
    private Object api;
    private Method getUser;
    private Method addSkillXp;
    private Method getSkillLevel;
    private final Map<String, Object> skills = new HashMap<>();
    /** True while FarmSaver itself is adding XP, so the XP multiplier listener ignores it. */
    private boolean internal;

    public AuraSkillsHook(FarmSaverPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        active = false;
        skills.clear();
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
            for (Object c : skillsEnum.getEnumConstants())
                skills.put(((Enum<?>) c).name().toLowerCase(Locale.ROOT), c);
            active = skills.containsKey("farming");
            if (active) plugin.getLogger().info("Hooked into AuraSkills (" + skills.size() + " skills).");
        } catch (Throwable t) {
            plugin.getLogger().warning("AuraSkills found but the hook failed (" + t + "). Integration disabled.");
        }
    }

    public boolean isActive() {
        return active;
    }

    public boolean isInternal() {
        return internal;
    }

    /** @param skill lower-case skill key, e.g. "farming"
     *  @return the player's AuraSkills level for the skill, or -1 if unavailable. */
    public int getLevel(Player p, String skill) {
        if (!active) return -1;
        Object sk = skills.get(skill);
        if (sk == null) return -1;
        try {
            Object user = getUser.invoke(api, p.getUniqueId());
            if (user == null) return -1;
            Object r = getSkillLevel.invoke(user, sk);
            return r instanceof Number n ? n.intValue() : -1;
        } catch (Throwable t) {
            plugin.getLogger().warning("AuraSkills getLevel failed: " + t);
            return -1;
        }
    }

    /** Adds bonus XP. The XP multiplier listener skips it (no double multiplication). */
    public void addXp(Player p, String skill, double amount) {
        if (!active || amount <= 0) return;
        Object sk = skills.get(skill);
        if (sk == null) return;
        internal = true;
        try {
            Object user = getUser.invoke(api, p.getUniqueId());
            if (user == null) return;
            addSkillXp.invoke(user, sk, amount);
        } catch (Throwable t) {
            plugin.getLogger().warning("AuraSkills addXp failed: " + t);
            active = false;
        } finally {
            internal = false;
        }
    }
}
