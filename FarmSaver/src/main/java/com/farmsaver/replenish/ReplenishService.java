package com.farmsaver.replenish;

import com.farmsaver.FarmSaverPlugin;
import com.farmsaver.Settings;
import com.farmsaver.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Crop-definition based replanting for the Replenish enchantment.
 *
 * Flow: the crop is harvested normally by vanilla (we never cancel or re-run the break), then on the
 * next tick the planting item is taken from the player's inventory, the crop is placed at the same
 * position and a BlockPlaceEvent is fired so claim/protection plugins can veto it. If anything fails
 * the items are given back, so a failed replant never costs the player anything.
 */
public final class ReplenishService {
    /** AGEABLE: same block reset to age 0 (cocoa keeps its facing). PLANT_BLOCK: the mature block
     *  is a different block than the planted one (torchflower, pitcher plant). SEGMENT: sugar cane,
     *  bamboo, cactus - only replanted when the broken block was the base. */
    public enum Kind { AGEABLE, PLANT_BLOCK, SEGMENT }

    public record CropDef(Material block, Material item, int amount, Kind kind, Material placeBlock) {
        boolean mature(BlockData d) {
            if (kind == Kind.AGEABLE) return d instanceof Ageable a && a.getAge() >= a.getMaximumAge();
            return true;   // PLANT_BLOCK / SEGMENT blocks only exist in their harvestable form
        }
    }

    private final FarmSaverPlugin plugin;
    private final Map<Material, CropDef> crops = new EnumMap<>(Material.class);
    private final Map<UUID, Long> lastMissingMsg = new HashMap<>();
    /** Replants are collected and handled by ONE scheduler task per tick instead of one task per crop. */
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private boolean flushScheduled;
    /** Reused slot buffer for inventory removal (server thread only). */
    private final int[] slotBuf = new int[36];

    private record Pending(Player p, Location loc, CropDef def, BlockData template, int taken, int need) {}

    public ReplenishService(FarmSaverPlugin plugin) {
        this.plugin = plugin;
    }

    public CropDef get(Material block) {
        return crops.get(block);
    }

    // ------------------------------------------------------------------ definitions

    private record Default(String block, String item, Kind kind, String place, boolean enabled) {}

    private static final List<Default> DEFAULTS = List.of(
            new Default("WHEAT", "WHEAT_SEEDS", Kind.AGEABLE, null, true),
            new Default("CARROTS", "CARROT", Kind.AGEABLE, null, true),
            new Default("POTATOES", "POTATO", Kind.AGEABLE, null, true),
            new Default("BEETROOTS", "BEETROOT_SEEDS", Kind.AGEABLE, null, true),
            new Default("NETHER_WART", "NETHER_WART", Kind.AGEABLE, null, true),
            new Default("COCOA", "COCOA_BEANS", Kind.AGEABLE, null, true),
            new Default("SWEET_BERRY_BUSH", "SWEET_BERRIES", Kind.AGEABLE, null, true),
            new Default("TORCHFLOWER", "TORCHFLOWER_SEEDS", Kind.PLANT_BLOCK, "TORCHFLOWER_CROP", true),
            new Default("PITCHER_PLANT", "PITCHER_POD", Kind.PLANT_BLOCK, "PITCHER_CROP", false),
            new Default("SUGAR_CANE", "SUGAR_CANE", Kind.SEGMENT, null, false),
            new Default("BAMBOO", "BAMBOO", Kind.SEGMENT, null, false),
            new Default("CACTUS", "CACTUS", Kind.SEGMENT, null, false));

    public void reload() {
        crops.clear();
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection("replenish.crops");
        Set<String> known = new HashSet<>();
        for (Default d : DEFAULTS) {
            known.add(d.block());
            Material block = Material.matchMaterial(d.block());          // null on versions without it
            Material item = Material.matchMaterial(d.item());
            Material place = d.place() == null ? block : Material.matchMaterial(d.place());
            boolean enabled = d.enabled();
            int amount = 1;
            ConfigurationSection s = cs == null ? null : cs.getConfigurationSection(d.block());
            if (s != null) {
                enabled = s.getBoolean("enabled", enabled);
                String in = s.getString("item");
                if (in != null) {
                    Material m = Material.matchMaterial(in);
                    if (m == null) plugin.getLogger().warning("config.yml: unknown item '" + in + "' for replenish.crops." + d.block());
                    else item = m;
                }
                amount = s.getInt("amount", 1);
                if (amount <= 0) {
                    plugin.getLogger().warning("config.yml: replenish.crops." + d.block() + ".amount must be > 0, using 1");
                    amount = 1;
                }
            }
            if (!enabled) continue;
            if (block == null || item == null || place == null) {
                plugin.getLogger().info("Replenish: " + d.block() + " is not available on this Minecraft version, skipped.");
                continue;
            }
            crops.put(block, new CropDef(block, item, amount, d.kind(), place));
        }
        if (cs != null)
            for (String k : cs.getKeys(false))
                if (!known.contains(k))
                    plugin.getLogger().warning("config.yml: replenish.crops." + k + " is not a supported crop and was ignored");
    }

    // ------------------------------------------------------------------ replanting

    /** Called from the block-drop handler after fortune has been applied. Never cancels anything. */
    public void replant(Player p, BlockState st, List<Item> items, List<ItemStack> overflow, CropDef def) {
        Settings cfg = plugin.settings();
        if (!cfg.replenishEnabled) return;
        BlockData data = st.getBlockData();
        if (!def.mature(data)) return;                       // immature crops are never touched

        Block block = st.getBlock();
        if (def.kind() == Kind.SEGMENT && block.getRelative(BlockFace.DOWN).getType() == def.block()) return; // base survives
        if (def.kind() == Kind.PLANT_BLOCK && data instanceof Bisected bi && bi.getHalf() == Bisected.Half.TOP)
            block = block.getRelative(BlockFace.DOWN);       // replant at the lower half

        final boolean consume = cfg.replenishConsume && p.getGameMode() != GameMode.CREATIVE;
        int fromDrops = 0;
        if (consume && cfg.seedFromDrops) fromDrops = takeFromDrops(items, overflow, def.item(), def.amount());

        // Next tick: vanilla has finished removing the crop, and auto-pickup has put the drops in the inventory.
        enqueue(new Pending(p, block.getLocation(), def, data, fromDrops, consume ? def.amount() - fromDrops : 0));
    }

    private void enqueue(Pending pd) {
        queue.add(pd);
        if (!flushScheduled) {
            flushScheduled = true;
            Bukkit.getScheduler().runTask(plugin, this::flush);
        }
    }

    private void flush() {
        flushScheduled = false;
        Pending pd;
        while ((pd = queue.poll()) != null) {
            try {
                finish(pd);
            } catch (Throwable t) {
                plugin.getLogger().warning("Replenish failed for one crop: " + t);
            }
        }
    }

    /** Plugin disable: finish what is queued so no taken seed is lost, then drop all state. */
    public void shutdown() {
        flush();
        lastMissingMsg.clear();
    }

    private void finish(Pending pd) {
        Player p = pd.p();
        Block b = pd.loc().getBlock();
        boolean ok = false;
        boolean missing = false;
        if (b.getType().isAir() && p.isOnline()) {            // isAir() also stops two players planting the same spot
            int need = pd.need();
            if (need > 0 && !takeItems(p, pd.def().item(), need)) {
                missing = true;                               // nothing was removed
            } else {
                ok = place(p, b, pd.def(), pd.template());
                if (!ok && need > 0) giveBack(p, pd.loc(), pd.def().item(), need);
            }
        }
        if (!ok) {
            if (pd.taken() > 0) giveBack(p, pd.loc(), pd.def().item(), pd.taken());
            if (missing) notifyMissing(p, pd.def());
        }
    }

    /**
     * Removes {@code amount} plain items of {@code m} from the player's storage slots in a single scan
     * (no ItemStack allocation, no second scan). Nothing is removed unless the full amount is available.
     */
    public boolean takeItems(Player p, Material m, int amount) {
        var inv = p.getInventory();
        int found = 0;
        int n = 0;
        for (int i = 0; i < 36 && found < amount; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() != m || it.hasItemMeta()) continue;
            slotBuf[n++] = i;
            found += it.getAmount();
        }
        if (found < amount) return false;
        int left = amount;
        for (int k = 0; k < n && left > 0; k++) {
            int slot = slotBuf[k];
            ItemStack it = inv.getItem(slot);
            int take = Math.min(left, it.getAmount());
            if (take >= it.getAmount()) inv.setItem(slot, null);
            else it.setAmount(it.getAmount() - take);
            left -= take;
        }
        return true;
    }

    private boolean place(Player p, Block b, CropDef def, BlockData template) {
        BlockData fresh;
        if (def.kind() == Kind.AGEABLE) {
            fresh = template.clone();
            if (!(fresh instanceof Ageable a)) return false;
            a.setAge(0);
        } else {
            fresh = def.placeBlock().createBlockData();
        }
        if (!fresh.isSupported(b)) return false;              // e.g. cocoa without a jungle log, no farmland

        BlockState replaced = b.getState();
        b.setBlockData(fresh, false);                         // no physics cascade; support was verified above
        BlockPlaceEvent ev = new BlockPlaceEvent(b, replaced, b.getRelative(BlockFace.DOWN),
                new ItemStack(def.item()), p, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(ev);              // lets claim / region plugins veto the planting
        if (ev.isCancelled() || !ev.canBuild()) {
            replaced.update(true, false);
            return false;
        }
        return true;
    }

    private int takeFromDrops(List<Item> items, List<ItemStack> overflow, Material seed, int want) {
        int got = 0;
        for (int i = 0; i < items.size() && got < want; i++) {
            Item it = items.get(i);
            ItemStack s = it.getItemStack();
            if (s.getType() != seed) continue;
            int n = Math.min(s.getAmount(), want - got);
            got += n;
            if (n >= s.getAmount()) {
                items.remove(i--);
            } else {
                s.setAmount(s.getAmount() - n);
                it.setItemStack(s);
            }
        }
        for (int i = 0; i < overflow.size() && got < want; i++) {
            ItemStack s = overflow.get(i);
            if (s.getType() != seed) continue;
            int n = Math.min(s.getAmount(), want - got);
            got += n;
            if (n >= s.getAmount()) overflow.remove(i--);
            else s.setAmount(s.getAmount() - n);
        }
        return got;
    }

    private void giveBack(Player p, Location loc, Material m, int amount) {
        ItemStack stack = new ItemStack(m, amount);
        if (p.isOnline()) {
            for (ItemStack left : p.getInventory().addItem(stack).values())
                loc.getWorld().dropItemNaturally(loc, left);
        } else {
            loc.getWorld().dropItemNaturally(loc, stack);
        }
    }

    private void notifyMissing(Player p, CropDef def) {
        if (!plugin.settings().replenishMissingMessage) return;
        long now = System.currentTimeMillis();
        Long last = lastMissingMsg.get(p.getUniqueId());
        if (last != null && now - last < 3000) return;         // no chat spam while harvesting a field
        lastMissingMsg.put(p.getUniqueId(), now);
        p.sendMessage(Text.c(plugin.raw("messages.prefix") + plugin.raw("messages.replenish-missing-item")
                .replace("{amount}", String.valueOf(def.amount()))
                .replace("{item}", Text.pretty(def.item()))));
    }

    /** Called when a player quits so no per-player state lingers. */
    public void forget(UUID id) {
        lastMissingMsg.remove(id);
    }
}
