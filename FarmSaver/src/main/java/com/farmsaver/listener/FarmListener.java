package com.farmsaver.listener;

import com.farmsaver.FarmSaverPlugin;
import com.farmsaver.Settings;
import com.farmsaver.enchant.EnchantManager;
import com.farmsaver.enchant.ToolType;
import com.farmsaver.replenish.ReplenishService;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gameplay: Fortune, Replenish, Auto Pickup, Treecapitator, Trample Protection.
 * Block break events are never cancelled, so AuraSkills still awards its normal XP.
 *
 * Performance rules used here: tools without item metadata exit first (they cannot carry our
 * enchantments), the metadata is read ONCE per event, and settings come from the cached Settings snapshot.
 */
public final class FarmListener implements Listener {
    private final FarmSaverPlugin plugin;
    /** Players whose Treecapitator is currently running (prevents recursion, per player only). */
    private final Set<UUID> busy = new HashSet<>();

    private record Planting(Location loc, Material sapling) {}

    public FarmListener(FarmSaverPlugin plugin) {
        this.plugin = plugin;
    }

    // ================================================== crops (hoe) and the first log (axe)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent e) {
        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!tool.hasItemMeta()) return;                     // no metadata -> no enchantments, nothing to do
        ToolType type = ToolType.of(tool);
        if (type != ToolType.HOE && type != ToolType.AXE) return;
        boolean hoe = type == ToolType.HOE;

        EnchantManager m = plugin.enchants();
        PersistentDataContainer pdc = m.pdc(tool);           // single metadata read for all lookups below
        Settings s = plugin.settings();
        int fortune = m.level(pdc, hoe ? EnchantManager.FARMING_FORTUNE : EnchantManager.FORAGING_FORTUNE);
        boolean pickup = m.level(pdc, EnchantManager.AUTO_PICKUP) > 0;
        boolean replenish = hoe && (m.level(pdc, EnchantManager.REPLENISH) > 0 || (pickup && s.autoPickupIncludesReplant));
        if (fortune <= 0 && !pickup && !replenish) return;   // none of our enchantments are on this tool

        BlockState st = e.getBlockState();
        ReplenishService.CropDef cropDef = null;
        if (hoe) {
            boolean crop = isCrop(st);
            cropDef = plugin.replenish().get(st.getType());
            if (!crop && cropDef == null) return;
            if (!crop) fortune = 0;
            if (cropDef == null) replenish = false;
        } else if (!Tag.LOGS.isTagged(st.getType())) {
            return;
        }
        if (fortune <= 0 && !pickup && !replenish) return;

        Location loc = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
        List<Item> items = e.getItems();
        List<ItemStack> overflow = fortune > 0 ? new ArrayList<>(2) : Collections.emptyList();

        int extra = 0;
        if (fortune > 0) extra = multiply(items, roll(s.fortune(hoe, fortune)), overflow);
        if (replenish) plugin.replenish().replant(p, st, items, overflow, cropDef);

        if (pickup) {
            for (Item it : items) give(p, loc, it.getItemStack());
            items.clear();                       // nothing spawns in the world
            for (ItemStack o : overflow) give(p, loc, o);
        } else {
            for (ItemStack o : overflow) loc.getWorld().dropItemNaturally(loc, o);
        }

        if (fortune > 0) plugin.aura().addXp(p, hoe ? "farming" : "foraging", s.bonusXp(hoe, extra, fortune));
    }

    private boolean isCrop(BlockState st) {
        Material m = st.getType();
        if (!plugin.crops().contains(m)) return false;
        if (plugin.skipMaturity().contains(m)) return true;
        BlockData data = st.getBlockData();
        return !(data instanceof Ageable a) || a.getAge() >= a.getMaximumAge();
    }

    /** fortune 20 => 20% chance of x2; 120 => x2 guaranteed, 20% chance of x3. */
    private int roll(double fortune) {
        double extra = Math.max(0, fortune) / 100.0;
        int whole = (int) Math.floor(extra);
        double frac = extra - whole;
        return 1 + whole + (ThreadLocalRandom.current().nextDouble() < frac ? 1 : 0);
    }

    /** Multiplies the drops in place. Stacks above max size go to {@code overflow}. @return bonus item count. */
    private int multiply(List<Item> items, int mult, List<ItemStack> overflow) {
        if (mult <= 1) return 0;
        int extra = 0;
        for (Item it : items) {
            ItemStack s = it.getItemStack().clone();
            int base = s.getAmount();
            int total = base * mult;
            int max = s.getMaxStackSize();
            s.setAmount(Math.min(total, max));
            it.setItemStack(s);
            extra += total - base;
            int rem = total - s.getAmount();
            while (rem > 0) {
                int n = Math.min(rem, max);
                ItemStack c = s.clone();
                c.setAmount(n);
                overflow.add(c);
                rem -= n;
            }
        }
        return extra;
    }

    private void give(Player p, Location loc, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return;
        for (ItemStack left : p.getInventory().addItem(stack.clone()).values())
            loc.getWorld().dropItemNaturally(loc, left);
    }

    /** Multiplies/delivers a list of drops (used for Treecapitator blocks). @return bonus item count. */
    private int deliver(Player p, Location loc, Collection<ItemStack> drops, int mult, boolean pickup) {
        int extra = 0;
        for (ItemStack d : drops) {
            int base = d.getAmount();
            int total = base * Math.max(1, mult);
            int max = d.getMaxStackSize();
            extra += total - base;
            while (total > 0) {
                int n = Math.min(total, max);
                ItemStack c = d.clone();
                c.setAmount(n);
                if (pickup) give(p, loc, c);
                else loc.getWorld().dropItemNaturally(loc, c);
                total -= n;
            }
        }
        return extra;
    }

    // ------------------------------------------- Treecapitator + Replenish (trees) + pickup

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!tool.hasItemMeta()) return;                     // cheapest exit first: ordinary tools and bare hands
        if (busy.contains(p.getUniqueId())) return;
        if (ToolType.of(tool) != ToolType.AXE) return;
        Block origin = e.getBlock();
        if (!Tag.LOGS.isTagged(origin.getType())) return;

        EnchantManager m = plugin.enchants();
        PersistentDataContainer pdc = m.pdc(tool);           // single metadata read
        Settings s = plugin.settings();
        int tc = m.level(pdc, EnchantManager.TREECAPITATOR);
        int ff = m.level(pdc, EnchantManager.FORAGING_FORTUNE);
        boolean pickup = m.level(pdc, EnchantManager.AUTO_PICKUP) > 0;
        boolean replant = m.level(pdc, EnchantManager.REPLENISH) > 0
                || (pickup && s.autoPickupIncludesReplant)
                || (ff > 0 && s.autoReplantFortune);
        boolean treecap = tc > 0 && !(s.treecapSneakDisables && p.isSneaking());
        if (!treecap && !replant) return;

        List<Block> tree = treecap ? findTree(origin, s) : List.of(origin);

        List<Planting> plants = null;
        if (replant) {
            for (Block b : tree) {
                if (!Tag.DIRT.isTagged(b.getRelative(BlockFace.DOWN).getType())) continue;
                Material sap = saplingFor(b.getType());
                if (sap == null) continue;
                if (plants == null) plants = new ArrayList<>(4);
                plants.add(new Planting(b.getLocation(), sap));
            }
        }

        if (tree.size() > 1) breakOthers(p, tool, tree, ff, pickup, s);

        if (plants != null) {
            final List<Planting> toPlant = plants;
            final boolean consume = s.replenishConsume && p.getGameMode() != GameMode.CREATIVE;
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Planting pl : toPlant) {
                    Block b = pl.loc().getBlock();
                    if (!b.getType().isAir()) continue;
                    if (!Tag.DIRT.isTagged(b.getRelative(BlockFace.DOWN).getType())) continue;
                    if (consume && !plugin.replenish().takeItems(p, pl.sapling(), 1)) continue;
                    b.setType(pl.sapling());
                }
            });
        }
    }

    private void breakOthers(Player p, ItemStack tool, List<Block> tree, int foragingTier, boolean pickup, Settings s) {
        boolean survival = p.getGameMode() != GameMode.CREATIVE;
        int perBlock = s.treecapDurability;

        // How many logs the tool can still pay for (it is never broken, it keeps 1 durability): one metadata read.
        int allowed = Integer.MAX_VALUE;
        if (survival && perBlock > 0 && tool.getItemMeta() instanceof Damageable d && !d.isUnbreakable())
            allowed = Math.max(0, (tool.getType().getMaxDurability() - d.getDamage() - 1) / perBlock);

        int broken = 0;
        double bonusXp = 0;
        busy.add(p.getUniqueId());
        try {
            for (int i = 1; i < tree.size() && broken < allowed; i++) {
                Block b = tree.get(i);
                if (b.getType().isAir()) continue;

                // Fire a real break event so claim plugins and AuraSkills (XP) see every block.
                BlockBreakEvent ev = new BlockBreakEvent(b, p);
                Bukkit.getPluginManager().callEvent(ev);
                if (ev.isCancelled()) continue;

                Collection<ItemStack> drops = (survival && ev.isDropItems()) ? b.getDrops(tool, p) : List.of();
                b.setType(Material.AIR);
                broken++;

                int mult = foragingTier > 0 ? roll(s.fortune(false, foragingTier)) : 1;
                int extra = deliver(p, b.getLocation().add(0.5, 0.5, 0.5), drops, mult, pickup);
                if (foragingTier > 0) bonusXp += s.bonusXp(false, extra, foragingTier);   // summed, paid once below
            }
        } finally {
            busy.remove(p.getUniqueId());
        }

        if (survival && perBlock > 0 && broken > 0 && tool.getItemMeta() instanceof Damageable d && !d.isUnbreakable()) {
            d.setDamage(d.getDamage() + broken * perBlock);   // all durability in one metadata write
            tool.setItemMeta(d);
            p.getInventory().setItemInMainHand(tool);
        }
        if (bonusXp > 0) plugin.aura().addXp(p, "foraging", bonusXp);   // one AuraSkills call for the whole tree
    }

    /** Breadth-first search over connected logs using packed coordinates (no Block object per neighbour). */
    private List<Block> findTree(Block origin, Settings s) {
        int max = s.treecapMaxBlocks;
        World w = origin.getWorld();
        List<Block> out = new ArrayList<>(Math.min(max, 64));
        Set<Long> seen = new HashSet<>(256);
        out.add(origin);
        seen.add(pack(origin.getX(), origin.getY(), origin.getZ()));
        boolean leaves = false;
        for (int i = 0; i < out.size(); i++) {
            Block b = out.get(i);
            int bx = b.getX(), by = b.getY(), bz = b.getZ();
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int x = bx + dx, y = by + dy, z = bz + dz;
                        if (!seen.add(pack(x, y, z))) continue;
                        Material mt = w.getType(x, y, z);
                        if (Tag.LOGS.isTagged(mt)) {
                            if (out.size() < max) out.add(w.getBlockAt(x, y, z));
                        } else if (!leaves && Tag.LEAVES.isTagged(mt)) {
                            leaves = true;
                        }
                    }
        }
        if (s.treecapRequireLeaves && !leaves) return List.of(origin);
        return out;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFFL);
    }

    private Material saplingFor(Material log) {
        String n = log.name();
        if (n.startsWith("STRIPPED_")) n = n.substring(9);
        if (!n.endsWith("_LOG")) return null;          // skips nether stems etc.
        String base = n.substring(0, n.length() - 4);
        if (base.equals("MANGROVE")) return Material.matchMaterial("MANGROVE_PROPAGULE");
        return Material.matchMaterial(base + "_SAPLING");
    }

    // ------------------------------------------------------------ Trample Protection

    private boolean hasTrampleProtection(Player p) {
        ItemStack boots = p.getInventory().getBoots();
        return boots != null && boots.hasItemMeta() && plugin.enchants().getLevel(boots, EnchantManager.TRAMPLE_PROTECTION) > 0;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTrample(PlayerInteractEvent e) {
        if (e.getAction() != Action.PHYSICAL) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.FARMLAND) return;
        if (hasTrampleProtection(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFarmlandChange(EntityChangeBlockEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;     // cheapest check first: most callers are falling blocks etc.
        if (e.getBlock().getType() != Material.FARMLAND) return;
        if (hasTrampleProtection(p)) e.setCancelled(true);
    }

    // ------------------------------------------------------------ cleanup

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        busy.remove(id);
        plugin.replenish().forget(id);
    }
}
