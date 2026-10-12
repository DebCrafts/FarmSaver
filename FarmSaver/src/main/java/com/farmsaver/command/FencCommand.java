package com.farmsaver.command;

import com.farmsaver.FarmSaverPlugin;
import com.farmsaver.gui.EnchantGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class FencCommand implements CommandExecutor, TabCompleter {
    private final FarmSaverPlugin plugin;

    public FencCommand(FarmSaverPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("farmsaver.admin")) {
                sender.sendMessage(plugin.msg("no-permission"));
                return true;
            }
            plugin.reloadAll();
            sender.sendMessage(plugin.msg("reloaded"));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.msg("players-only"));
            return true;
        }
        if (!p.hasPermission("farmsaver.use")) {
            p.sendMessage(plugin.msg("no-permission"));
            return true;
        }
        new EnchantGui(plugin, p).open();
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("farmsaver.admin")) return List.of("reload");
        return List.of();
    }
}
