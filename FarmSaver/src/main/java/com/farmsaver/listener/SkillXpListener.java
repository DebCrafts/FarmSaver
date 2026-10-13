package com.farmsaver.listener;

import com.farmsaver.FarmSaverPlugin;
import dev.aurelium.auraskills.api.event.skill.XpGainEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Applies the EXP multiplier enchantments through AuraSkills' official XpGainEvent
 * (getSkill / getAmount / setAmount). Only registered when AuraSkills is present.
 *
 * Runs at HIGH priority: getAmount() already contains AuraSkills' own multipliers, ours is applied
 * once on top. Events caused by FarmSaver's own bonus XP are skipped (no double multiplication).
 */
public final class SkillXpListener implements Listener {
    private final FarmSaverPlugin plugin;

    public SkillXpListener(FarmSaverPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onXpGain(XpGainEvent e) {
        if (plugin.aura().isInternal()) return;
        double amount = e.getAmount();
        if (!(amount > 0)) return;
        double mult = plugin.exp().multiplier(e.getPlayer(), e.getSkill().name());   // Skill#name(): no namespace
        if (mult == 1.0) return;
        e.setAmount(amount * mult);
    }
}
