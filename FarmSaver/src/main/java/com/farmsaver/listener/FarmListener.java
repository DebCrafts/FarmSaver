package com.farmsaver.listener;

import com.farmsaver.FarmSaverPlugin;
import com.farmsaver.enchant.EnchantManager;
import com.farmsaver.enchant.ToolType;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gameplay: Fortune, Replenish, Auto Pickup, Treecapitator, Trample Protection.
 * Block break events are never cancelled, so AuraSkills still awards its normal XP.
 */
public final class FarmListener implements Listener {
    private final FarmSaverPlugin plugin;
    /** Players whose Treecapitator is currently running (prevents recursion). */
    private final Set<UUID> busy = new HashSet<>();

    private record Planting(Location loc, Material sapling) {}

    public FarmListener(FarmSaverPlugin plugin) {
        this.plugin = plugin;
    }

    private EnchantManager mgr() {
        return plugin.enchants();
    }

    // ================================================== crops (hoe) and the first log (axe)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent e) {
        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        ToolType type = ToolType.of(tool);
        if (type != ToolType.HOE && type != ToolType.AXE) return;
        boolean hoe = type == ToolType.HOE;

        BlockState st = e.getBlockState();
        if (hoe ? !isCrop(st) : !Tag.LOGS.isTagged(st.getType())) return;

        EnchantManager m = mgr();
        int fortune = m.getLevel(tool, hoe ? EnchantManager.FARMING_FORTUNE : EnchantManager.FORAGING_FORTUNE);
        boolean pickup = m.getLevel(tool, EnchantManager.AUTO_PICKUP) > 0;
        boolean replenish = m.getLevel(tool, EnchantManager.REPLENISH) > 0
                || (pickup && plugin.getConfig().getBoolean("auto-pickup.includes-replant", false));
        if (fortune <= 0 && !pickup && !(hoe && replenish)) return;

        String skill = hoe ? "farming" : "foraging";
        Location loc = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
        List<Item> items = e.getItems();
        List<ItemStack> overflow = new ArrayList<>();

        int extra = 0;
        if (fortune > 0) extra = multiply(items, roll(plugin.fortune(skill, fortune)), overflow);
        if (hoe && replenish) replantCrop(p, st, items, overflow);

        if (pickup) {
            for (Item it : items) give(p, loc, it.getItemStack());
            items.clear();                       // nothing spawns in the world
            for (ItemStack o : overflow) give(p, loc, o);
        } else {
            for (ItemStack o : overflow) loc.getWorld().dropItemNaturally(loc, o);
        }

        if (fortune > 0) awardXp(p, skill, extra, fortune);
    }

    private boolean isCrop(BlockState st) {
        Material m = st.getType();
        if (!plugin.crops().contains(m)) return false;
        BlockData data = st.getBlockData();
        if (data instanceof Ageable a && !plugin.skipMaturity().contains(m)) return a.getAge() >= a.getMaximumAge();
        return true;
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

    // ------------------------------------------------------------------ Replenish (crops)

    private Material seedFor(Material crop) {
        return switch (crop) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            case NETHER_WART -> Material.NETHER_WART;
            case SWEET_BERRY_BUSH -> Material.SWEET_BERRIES;
            case COCOA -> Material.COCOA_BEANS;
            default -> null;
        };
    }

    private void replantCrop(Player p, BlockState st, List<Item> items, List<ItemStack> overflow) {
        Material crop = st.getType();
        Material seed = seedFor(crop);
        BlockData data = st.getBlockData();
        if (seed == null || !(data instanceof Ageable)) return;

        if (plugin.getConfig().getBoolean("replenish.consume-items", true) && !takeSeed(p, items, overflow, seed)) return;

        Ageable fresh = (Ageable) data.clone();
        fresh.setAge(crop == Material.SWEET_BERRY_BUSH ? 1 : 0);
        Location loc = st.getLocation();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Block b = loc.getBlock();
            if (!b.getType().isAir()) return;
            if (!fresh.isSupported(b)) return;
            b.setBlockData(fresh);
        });
    }

    private boolean takeSeed(Player p, List<Item> items, List<ItemStack> overflow, Material seed) {
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            ItemStack s = it.getItemStack();
            if (s.getType() != seed) continue;
            if (s.getAmount() <= 1) {
                items.remove(i);
            } else {
                s.setAmount(s.getAmount() - 1);
                it.setItemStack(s);
            }
            return true;
        }
        for (int i = 0; i < overflow.size(); i++) {
            ItemStack s = overflow.get(i);
            if (s.getType() != seed) continue;
            if (s.getAmount() <= 1) overflow.remove(i);
            else s.setAmount(s.getAmount() - 1);
            return true;
        }
        return p.getInventory().removeItem(new ItemStack(seed, 1)).isEmpty();
    }

    // ------------------------------------------------------------------ AuraSkills XP

    /** bonus XP = (per-harvest + bonusItems * per-bonus-item) * tier-multiplier * global-multiplier */
    private void awardXp(Player p, String skill, int bonusItems, int tier) {
        var cfg = plugin.getConfig();
        String base = "auraskills.xp." + skill + ".";
        double perHarvest = cfg.getDouble(base + "per-harvest", 2.0);
        double perBonus = cfg.getDouble(base + "per-bonus-item", 4.0);
        double tierMult = cfg.getDouble(base + "tier-multiplier." + tier, 1.0 + 0.75 * (tier - 1));
        double global = cfg.getDouble("auraskills.xp.global-multiplier", 1.0);
        plugin.aura().addXp(p, skill, (perHarvest + bonusItems * perBonus) * tierMult * global);
    }

    // ------------------------------------------- Treecapitator + Replenish (trees) + pickup

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (busy.contains(p.getUniqueId())) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (ToolType.of(tool) != ToolType.AXE) return;
        Block origin = e.getBlock();
        if (!Tag.LOGS.isTagged(origin.getType())) return;

        var cfg = plugin.getConfig();
        int tc = mgr().getLevel(tool, EnchantManager.TREECAPITATOR);
        int ff = mgr().getLevel(tool, EnchantManager.FORAGING_FORTUNE);
        boolean pickup = mgr().getLevel(tool, EnchantManager.AUTO_PICKUP) > 0;
        boolean replant = mgr().getLevel(tool, EnchantManager.REPLENISH) > 0
                || (pickup && cfg.getBoolean("auto-pickup.includes-replant", false))
                || (ff > 0 && cfg.getBoolean("foraging.auto-replant", true));
        boolean treecap = tc > 0 && !(cfg.getBoolean("treecapitator.sneak-to-disable", true) && p.isSneaking());
        if (!treecap && !replant) return;

        List<Block> tree = treecap ? findTree(origin) : List.of(origin);

        List<Planting> plants = new ArrayList<>();
        if (replant) {
            for (Block b : tree) {
                if (!Tag.DIRT.isTagged(b.getRelative(BlockFace.DOWN).getType())) continue;
                Material sap = saplingFor(b.getType());
                if (sap != null) plants.add(new Planting(b.getLocation(), sap));
            }
        }

        if (tree.size() > 1) breakOthers(p, tool, tree, ff, pickup);

        if (!plants.isEmpty()) {
            boolean consume = cfg.getBoolean("replenish.consume-items", true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Planting pl : plants) {
                    Block b = pl.loc().getBlock();
                    if (!b.getType().isAir()) continue;
                    if (!Tag.DIRT.isTagged(b.getRelative(BlockFace.DOWN).getType())) continue;
                    if (consume && p.getGameMode() != GameMode.CREATIVE
                            && !p.getInventory().removeItem(new ItemStack(pl.sapling(), 1)).isEmpty())
                        continue;
                    b.setType(pl.sapling());
                }
            });
        }
    }

    private void breakOthers(Player p, ItemStack tool, List<Block> tree, int foragingTier, boolean pickup) {
        boolean survival = p.getGameMode() != GameMode.CREATIVE;
        int perBlock = plugin.getConfig().getInt("treecapitator.durability-per-block", 1);

        busy.add(p.getUniqueId());
        try {
            for (int i = 1; i < tree.size(); i++) {
                Block b = tree.get(i);
                if (b.getType().isAir()) continue;
                if (survival && !canAfford(tool, perBlock)) break;

                // Fire a real break event so claim plugins and AuraSkills (XP) see every block.
                BlockBreakEvent ev = new BlockBreakEvent(b, p);
                Bukkit.getPluginManager().callEvent(ev);
                if (ev.isCancelled()) continue;

                Collection<ItemStack> drops = (survival && ev.isDropItems()) ? b.getDrops(tool, p) : List.of();
                if (survival) damage(tool, perBlock);
                b.setType(Material.AIR);

                int mult = foragingTier > 0 ? roll(plugin.fortune("foraging", foragingTier)) : 1;
                int extra = deliver(p, b.getLocation().add(0.5, 0.5, 0.5), drops, mult, pickup);
                if (foragingTier > 0) awardXp(p, "foraging", extra, foragingTier);
            }
        } finally {
            busy.remove(p.getUniqueId());
        }
        p.getInventory().setItemInMainHand(tool);
    }

    private boolean canAfford(ItemStack tool, int amt) {
        if (amt <= 0 || !(tool.getItemMeta() instanceof Damageable d) || d.isUnbreakable()) return true;
        return d.getDamage() + amt < tool.getType().getMaxDurability();
    }

    private void damage(ItemStack tool, int amt) {
        if (amt <= 0 || !(tool.getItemMeta() instanceof Damageable d) || d.isUnbreakable()) return;
        d.setDamage(d.getDamage() + amt);
        tool.setItemMeta(d);
    }

    private List<Block> findTree(Block origin) {
        int max = Math.max(1, plugin.getConfig().getInt("treecapitator.max-blocks", 256));
        boolean needLeaves = plugin.getConfig().getBoolean("treecapitator.require-leaves", true);
        Set<Block> seen = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        List<Block> out = new ArrayList<>();
        queue.add(origin);
        seen.add(origin);
        boolean leaves = false;
        while (!queue.isEmpty() && out.size() < max) {
            Block b = queue.poll();
            out.add(b);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block n = b.getRelative(dx, dy, dz);
                        if (!seen.add(n)) continue;
                        Material m = n.getType();
                        if (Tag.LOGS.isTagged(m)) queue.add(n);
                        else if (Tag.LEAVES.isTagged(m)) leaves = true;
                    }
        }
        if (needLeaves && !leaves) return List.of(origin);
        return out;
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
        return boots != null && mgr().getLevel(boots, EnchantManager.TRAMPLE_PROTECTION) > 0;
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
        if (e.getBlock().getType() != Material.FARMLAND) return;
        if (e.getEntity() instanceof Player p && hasTrampleProtection(p)) e.setCancelled(true);
    }
}
