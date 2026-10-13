package com.farmsaver.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Routes by player instead of Inventory#getHolder(): getHolder() can snapshot block entities
 * (chests etc.), which is costly for every click on the whole server. A player can only have one
 * inventory open, so "this player has our GUI open" is exactly equivalent.
 */
public final class GuiListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        EnchantGui gui = EnchantGui.of(e.getWhoClicked());
        if (gui != null) gui.handleClick(e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        EnchantGui gui = EnchantGui.of(e.getWhoClicked());
        if (gui != null) gui.handleDrag(e);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        EnchantGui gui = EnchantGui.of(e.getPlayer());
        if (gui != null) gui.handleClose();
    }
}
