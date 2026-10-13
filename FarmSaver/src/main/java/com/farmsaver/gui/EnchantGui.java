package com.farmsaver.gui;

import com.farmsaver.FarmSaverPlugin;
import com.farmsaver.enchant.EnchantDef;
import com.farmsaver.enchant.EnchantManager;
import com.farmsaver.enchant.ToolType;
import com.farmsaver.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * 9x6 enchant GUI, laid out like the reference screenshot:
 *
 *   row 0:  . . . . . . . . .
 *   row 1:  . . . [ E1 tiers: slots 12-16 ] .
 *   row 2:  . TOOL . [ E2 tiers: slots 21-25 ] .
 *   row 3:  . TABLE . [ E3 tiers: slots 30-34 ] INFO
 *   row 4:  . . . . . . . . .
 *   row 5:  . . . SHELF CLOSE HELP . . .
 */
public final class EnchantGui implements InventoryHolder {
    public static final int TOOL = 19;
    public static final int APPLY = 28;      // enchanting table
    public static final int INFO = 35;
    public static final int SHELF = 48;      // bookshelf
    public static final int CLOSE = 49;      // barrier
    public static final int HELP = 50;       // book
    public static final int PREV = 47;       // previous page arrow
    public static final int NEXT = 51;       // next page arrow
    private static final int[][] ROWS = {
            {12, 13, 14, 15, 16},
            {21, 22, 23, 24, 25},
            {30, 31, 32, 33, 34}
    };

    /** Open GUIs by player: listeners find ours with a map lookup and never call Inventory#getHolder(). */
    private static final Map<UUID, EnchantGui> OPEN = new HashMap<>();

    public static void closeAll() {
        for (EnchantGui g : new ArrayList<>(OPEN.values())) g.player.closeInventory();
    }

    public static EnchantGui of(HumanEntity who) {
        return OPEN.get(who.getUniqueId());
    }

    private record Selection(EnchantDef def, int tier) {}

    private final FarmSaverPlugin plugin;
    private final Player player;
    private final Inventory inv;
    private final NamespacedKey placeholderKey;
    private final Map<Integer, Selection> slots = new HashMap<>();
    private int selectedSlot = -1;
    private ToolType viewType;
    private final ItemStack filler = Text.item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());   // built once per GUI
    private int page = 0;
    private int pageCount = 1;

    public EnchantGui(FarmSaverPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.placeholderKey = new NamespacedKey(plugin, "gui_placeholder");
        this.inv = org.bukkit.Bukkit.createInventory(this, 54, Text.c(plugin.raw("gui.title")));
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    public void open() {
        refresh();
        player.openInventory(inv);       // closes any previous GUI first (its close event runs before we register)
        OPEN.put(player.getUniqueId(), this);
    }

    // ------------------------------------------------------------------ rendering

    private ItemStack placeholder() {
        ItemStack it = Text.item(Material.GRAY_STAINED_GLASS_PANE, "&7Place a tool here",
                List.of("&8Hoe, axe or boots", "", "&eClick with an item on your cursor"));
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(placeholderKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean isPlaceholder(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(placeholderKey, PersistentDataType.BYTE);
    }

    /** The real tool in the tool slot, or null. */
    private ItemStack tool() {
        ItemStack it = inv.getItem(TOOL);
        if (it == null || it.getType().isAir() || isPlaceholder(it)) return null;
        return it;
    }

    private boolean valid(ItemStack it) {
        ToolType t = ToolType.of(it);
        if (t == null) return false;
        return !plugin.enchants().forType(t).isEmpty();
    }

    public void refresh() {
        EnchantManager mgr = plugin.enchants();
        slots.clear();
        for (int i = 0; i < 54; i++) if (i != TOOL) inv.setItem(i, filler);

        ItemStack tool = tool();
        inv.setItem(TOOL, tool != null ? tool : placeholder());

        ToolType type = tool == null ? null : ToolType.of(tool);
        viewType = type;
        if (type != null) {
            // Lay every (enchant, tier) out on pages of 3 rows x 5 columns; one enchant's tiers stay on one row.
            record Placed(int slot, Selection sel, int cur) {}
            List<List<Placed>> pages = new ArrayList<>();
            List<Placed> pg = new ArrayList<>();
            int row = 0;
            int col = 0;
            for (EnchantDef def : mgr.forType(type)) {
                int n = Math.min(def.tiers().size(), 5);
                if (col > 0 && col + n > 5) {
                    row++;
                    col = 0;
                }
                if (row >= ROWS.length) {          // page is full -> start a new one
                    pages.add(pg);
                    pg = new ArrayList<>();
                    row = 0;
                    col = 0;
                }
                int cur = mgr.getLevel(tool, def);
                for (Map.Entry<Integer, EnchantDef.Tier> en : def.tiers().entrySet()) {
                    if (col >= 5) break;
                    pg.add(new Placed(ROWS[row][col++], new Selection(def, en.getKey()), cur));
                }
            }
            if (!pg.isEmpty()) pages.add(pg);
            pageCount = Math.max(1, pages.size());
            page = Math.max(0, Math.min(page, pageCount - 1));
            if (!pages.isEmpty()) {
                for (Placed pl : pages.get(page)) {
                    slots.put(pl.slot(), pl.sel());
                    EnchantDef.Tier cost = pl.sel().def().tiers().get(pl.sel().tier());
                    inv.setItem(pl.slot(), book(pl.sel().def(), pl.sel().tier(), cost, pl.cur(), pl.slot() == selectedSlot));
                }
            }
        } else {
            page = 0;
            pageCount = 1;
        }
        if (page > 0)
            inv.setItem(PREV, Text.item(Material.ARROW, "&ePrevious page", List.of("&7Page " + page + "/" + pageCount)));
        if (page < pageCount - 1)
            inv.setItem(NEXT, Text.item(Material.ARROW, "&eNext page", List.of("&7Page " + (page + 2) + "/" + pageCount)));
        if (!slots.containsKey(selectedSlot)) selectedSlot = -1;

        inv.setItem(APPLY, applyButton());
        inv.setItem(INFO, Text.item(Material.DROPPER, "&bYour Resources",
                List.of("&7XP Levels: &a" + player.getLevel(), "", "&8Costs are set in recipe.yml")));
        inv.setItem(SHELF, shelf(type));
        inv.setItem(CLOSE, Text.item(Material.BARRIER, "&cClose", List.of("&7Returns your tool.")));
        inv.setItem(HELP, Text.item(Material.BOOK, "&eHow it works", List.of(
                "&71. Place a tool or armor piece in the slot.",
                "&72. Click an enchantment book to select it.",
                "&73. Click the enchanting table to apply.",
                "", "&7Tiers must be applied in order.")));
    }

    private ItemStack book(EnchantDef def, int tier, EnchantDef.Tier cost, int cur, boolean selected) {
        boolean owned = cur >= tier;
        boolean next = tier == cur + 1;
        EnchantManager.SkillReq sr = plugin.enchants().skillReq(def, tier, viewType);
        boolean skillOk = plugin.enchants().skillMet(player, sr);
        Material mat = (owned || (next && skillOk)) ? Material.ENCHANTED_BOOK : Material.BOOK;
        String prefix = selected ? "&f\u25B6 " : (owned ? "&a\u2714 " : "");
        String name = prefix + def.displayName() + " " + EnchantManager.roman(tier);

        List<String> lore = new ArrayList<>();
        if (!def.description().isEmpty()) lore.add(def.description());
        lore.add("");
        if (owned) lore.add("&aApplied");
        else if (next && !skillOk) lore.add("&cRequirement not met: " + skillName(sr) + " Level " + sr.level());
        else if (next) lore.add("&eAvailable");
        else lore.add("&cRequires tier " + (tier - 1) + " first");
        lore.add("");
        lore.addAll(costLore(def, tier));
        if (next && skillOk) {
            lore.add("");
            lore.add("&8Click to select");
        }
        return Text.item(mat, name, lore);
    }

    private String skillName(EnchantManager.SkillReq sr) {
        String n = sr.skill();
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private List<String> costLore(EnchantDef def, int tier) {
        EnchantDef.Tier cost = def.tiers().get(tier);
        EnchantManager mgr = plugin.enchants();
        List<String> l = new ArrayList<>();

        EnchantManager.SkillReq sr = mgr.skillReq(def, tier, viewType);
        if (sr != null) {
            int cur = plugin.aura().getLevel(player, sr.skill());
            boolean ok = mgr.skillMet(player, sr);
            l.add("&7Requirement:");
            l.add((ok ? "&a\u2714 " : "&c\u2718 ") + "&f" + skillName(sr) + " Level " + sr.level()
                    + (cur >= 0 ? " &8(you: " + cur + ")" : " &8(AuraSkills not found)"));
            l.add("");
        }

        l.add("&7Cost:");
        l.add((player.getLevel() >= cost.xpLevels() ? "&a\u2714 " : "&c\u2718 ") + "&f" + cost.xpLevels() + " XP levels");
        if (mgr.itemsRequired()) {
            for (Map.Entry<Material, Integer> e : cost.requirements().entrySet()) {
                boolean ok = mgr.count(player, e.getKey()) >= e.getValue();
                l.add((ok ? "&a\u2714 " : "&c\u2718 ") + "&f" + e.getValue() + "x " + Text.pretty(e.getKey()));
            }
        }
        return l;
    }

    private ItemStack applyButton() {
        Selection s = selectedSlot >= 0 ? slots.get(selectedSlot) : null;
        if (s == null) {
            return Text.item(Material.ENCHANTING_TABLE, "&dApply Enchantment",
                    List.of("&7Select an enchantment book first."));
        }
        List<String> lore = new ArrayList<>();
        lore.add("&7Selected: " + s.def().displayName() + " " + EnchantManager.roman(s.tier()));
        lore.add("");
        lore.addAll(costLore(s.def(), s.tier()));
        lore.add("");
        lore.add("&aClick to apply!");
        return Text.item(Material.ENCHANTING_TABLE, "&dApply Enchantment", lore);
    }

    private ItemStack shelf(ToolType type) {
        List<String> lore = new ArrayList<>();
        if (type == null) {
            lore.add("&7Insert a tool to see its enchantments.");
        } else {
            lore.add("&7Enchantments for &f" + type.name().toLowerCase(Locale.ROOT) + "&7:");
            for (EnchantDef d : plugin.enchants().forType(type))
                lore.add(" " + d.displayName() + " &8(" + d.tiers().size() + " tier" + (d.tiers().size() == 1 ? "" : "s") + ")");
        }
        return Text.item(Material.BOOKSHELF, "&6Available Enchantments", lore);
    }

    // ------------------------------------------------------------------ input

    public void handleClick(InventoryClickEvent e) {
        ClickType ct = e.getClick();
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR || ct == ClickType.DOUBLE_CLICK) {
            e.setCancelled(true);
            return;
        }
        Inventory clicked = e.getClickedInventory();
        if (clicked == null) return;

        if (clicked != inv) {
            // Player's own inventory: only intercept shift-click (it would try to fill our GUI).
            if (e.isShiftClick()) {
                e.setCancelled(true);
                ItemStack it = e.getCurrentItem();
                if (tool() == null && valid(it)) {
                    inv.setItem(TOOL, it.clone());
                    e.setCurrentItem(null);
                    page = 0;
                    refresh();
                } else if (it != null && !it.getType().isAir() && tool() == null) {
                    player.sendMessage(plugin.msg("invalid-tool"));
                }
            }
            return;
        }

        e.setCancelled(true);
        int slot = e.getSlot();
        if (slot == TOOL) {
            handleToolSlot(e);
            return;
        }
        if (slot == CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == PREV && page > 0) {
            page--;
            selectedSlot = -1;
            refresh();
            return;
        }
        if (slot == NEXT && page < pageCount - 1) {
            page++;
            selectedSlot = -1;
            refresh();
            return;
        }
        if (slot == APPLY) {
            applySelected();
            return;
        }
        if (slots.containsKey(slot)) {
            selectedSlot = slot;
            player.playSound(player.getLocation(), "ui.button.click", 0.6f, 1.2f);
            refresh();
        }
    }

    private void handleToolSlot(InventoryClickEvent e) {
        ItemStack tool = tool();
        ItemStack cursor = e.getCursor();
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();
        ItemStack air = new ItemStack(Material.AIR);

        switch (e.getClick()) {
            case LEFT, RIGHT -> {
                if (!cursorEmpty) {
                    if (!valid(cursor)) {
                        player.sendMessage(plugin.msg("invalid-tool"));
                        return;
                    }
                    inv.setItem(TOOL, cursor.clone());
                    player.setItemOnCursor(tool != null ? tool : air);
                } else if (tool != null) {
                    player.setItemOnCursor(tool);
                    inv.setItem(TOOL, null);
                }
            }
            case SHIFT_LEFT, SHIFT_RIGHT -> {
                if (tool != null && player.getInventory().addItem(tool).isEmpty()) inv.setItem(TOOL, null);
            }
            case NUMBER_KEY -> {
                int b = e.getHotbarButton();
                if (b < 0) return;
                ItemStack hb = player.getInventory().getItem(b);
                boolean hbEmpty = hb == null || hb.getType().isAir();
                if (!hbEmpty && !valid(hb)) {
                    player.sendMessage(plugin.msg("invalid-tool"));
                    return;
                }
                player.getInventory().setItem(b, tool);
                inv.setItem(TOOL, hbEmpty ? null : hb.clone());
            }
            default -> {
                return;
            }
        }
        selectedSlot = -1;
        page = 0;
        refresh();
    }

    private void applySelected() {
        Selection s = selectedSlot >= 0 ? slots.get(selectedSlot) : null;
        if (s == null) {
            player.sendMessage(plugin.msg(tool() == null ? "no-tool" : "select-first"));
            return;
        }
        ItemStack tool = tool();
        if (tool == null) {
            player.sendMessage(plugin.msg("no-tool"));
            return;
        }
        String error = plugin.enchants().apply(player, tool, s.def(), s.tier());
        if (error != null) {
            EnchantManager.SkillReq sr = plugin.enchants().skillReq(s.def(), s.tier(), ToolType.of(tool));
            String raw = plugin.raw(error);
            if (sr != null) raw = raw.replace("{skill}", skillName(sr)).replace("{level}", String.valueOf(sr.level()));
            player.sendMessage(Text.c(plugin.raw("messages.prefix") + raw));
            player.playSound(player.getLocation(), "entity.villager.no", 0.8f, 1f);
        } else {
            inv.setItem(TOOL, tool);
            String name = s.def().displayName() + " " + EnchantManager.roman(s.tier());
            player.sendMessage(Text.c(plugin.raw("messages.prefix")
                    + plugin.raw("messages.applied").replace("{enchant}", name)));
            player.playSound(player.getLocation(), "block.enchantment_table.use", 1f, 1f);
            player.playSound(player.getLocation(), "entity.player.levelup", 0.6f, 1.2f);
        }
        refresh();
    }

    public void handleDrag(InventoryDragEvent e) {
        int size = inv.getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < size) {
                e.setCancelled(true);
                return;
            }
        }
    }

    public void handleClose() {
        OPEN.remove(player.getUniqueId(), this);
        ItemStack tool = tool();
        if (tool == null) return;
        inv.setItem(TOOL, null);
        Map<Integer, ItemStack> left = player.getInventory().addItem(tool);
        for (ItemStack it : left.values()) player.getWorld().dropItemNaturally(player.getLocation(), it);
    }
}
