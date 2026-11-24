package com.vanillaweapons.vwu;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataType;

public final class WeaponListener implements Listener {
    record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey from(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
        String encode() { return worldId + ":" + x + ":" + y + ":" + z; }
        static BlockKey decode(String s) {
            String[] pieces = s.split(":", -1);
            if (pieces.length != 4) throw new IllegalArgumentException("Invalid placed block key");
            return new BlockKey(UUID.fromString(pieces[0]), Integer.parseInt(pieces[1]),
                    Integer.parseInt(pieces[2]), Integer.parseInt(pieces[3]));
        }
    }
    record Placed(CustomItems.Kind kind, UUID owner) {}

    private final VanillaWeaponsPlugin plugin;
    private final CustomItems items;
    private final ExplosionEngine engine;
    private final Map<BlockKey, Placed> placed = new HashMap<>();
    private final NamespacedKey primedKind;
    private final NamespacedKey primedOwner;
    private final File file;
    private boolean dirty;

    public WeaponListener(VanillaWeaponsPlugin plugin, CustomItems items, ExplosionEngine engine) {
        this.plugin = plugin;
        this.items = items;
        this.engine = engine;
        this.primedKind = new NamespacedKey(plugin, "primed_kind");
        this.primedOwner = new NamespacedKey(plugin, "primed_owner");
        this.file = new File(plugin.getDataFolder(), "placed.yml");
        restore();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(org.bukkit.event.block.BlockDispenseEvent event) {
        CustomItems.Kind kind = items.identify(event.getItem());
        if (kind == CustomItems.Kind.SUPER_TNT || kind == CustomItems.Kind.FORMIDIBOMB) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        CustomItems.Kind kind = items.identify(e.getItemInHand());
        if (kind != CustomItems.Kind.SUPER_TNT && kind != CustomItems.Kind.FORMIDIBOMB) {
            if (placed.remove(BlockKey.from(e.getBlockPlaced())) != null) dirty = true;
            return;
        }
        Block block = e.getBlockPlaced();
        if (plugin.isProtected(block.getWorld(), block.getX(), block.getY(), block.getZ())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("This area does not allow custom explosives.");
            return;
        }
        placed.put(BlockKey.from(block), new Placed(kind, e.getPlayer().getUniqueId()));
        dirty = true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Placed record = placed.remove(BlockKey.from(e.getBlock()));
        if (record == null) return;
        dirty = true;
        e.setDropItems(false);
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE) {
            e.getBlock().getWorld().dropItemNaturally(
                    e.getBlock().getLocation().add(0.5, 0.5, 0.5), items.create(record.kind(), 1));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrime(TNTPrimeEvent e) {
        if (placed.containsKey(BlockKey.from(e.getBlock()))) {
            e.setCancelled(true);
            prime(e.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (placed.containsKey(BlockKey.from(e.getBlock()))) {
            e.setCancelled(true);
            prime(e.getBlock());
        }
    }

    public boolean primeIfCustom(Block block) {
        if (block.getType() != Material.TNT || !placed.containsKey(BlockKey.from(block))) return false;
        prime(block);
        return true;
    }

    private void prime(Block block) {
        Placed data = placed.remove(BlockKey.from(block));
        if (data == null) return;
        dirty = true;
        if (block.getType() != Material.TNT) return;
        block.setType(Material.AIR, false);
        Location at = block.getLocation().add(0.5, 0.25, 0.5);
        int fuse = plugin.getConfig().getInt("explosions."
                + (data.kind() == CustomItems.Kind.SUPER_TNT ? "super-tnt" : "formidibomb") + ".fuse-ticks", 80);
        block.getWorld().spawn(at, TNTPrimed.class, tnt -> {
            tnt.setFuseTicks(Math.max(20, Math.min(fuse, 1200)));
            tnt.setYield(0);
            tnt.getPersistentDataContainer().set(primedKind, PersistentDataType.STRING, data.kind().id);
            if (data.owner() != null) {
                tnt.getPersistentDataContainer().set(primedOwner, PersistentDataType.STRING, data.owner().toString());
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        Entity entity = e.getEntity();
        if (entity instanceof TNTPrimed) {
            String id = entity.getPersistentDataContainer().get(primedKind, PersistentDataType.STRING);
            CustomItems.Kind kind = id == null ? null : CustomItems.Kind.parse(id);
            if (kind == CustomItems.Kind.SUPER_TNT || kind == CustomItems.Kind.FORMIDIBOMB) {
                e.setCancelled(true); // Never allow a giant vanilla explosion.
                UUID owner = null;
                String ownerText = entity.getPersistentDataContainer().get(primedOwner, PersistentDataType.STRING);
                try { if (ownerText != null) owner = UUID.fromString(ownerText); }
                catch (IllegalArgumentException ignored) { }
                engine.detonate(entity.getLocation(), kind, owner);
                return;
            }
        }
        chainPrime(e.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { chainPrime(e.blockList()); }

    private void chainPrime(java.util.List<Block> blocks) {
        // Remove special blocks from vanilla block destruction and prime them ourselves.
        java.util.Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (placed.containsKey(BlockKey.from(b))) {
                it.remove();
                prime(b);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (placed.containsKey(BlockKey.from(b))) { e.setCancelled(true); return; }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (placed.containsKey(BlockKey.from(b))) { e.setCancelled(true); return; }
        }
    }

    /** Bounded ambient effects for loaded, placed custom TNT blocks. */
    public void tickPlacedEffects() {
        if (!plugin.getConfig().getBoolean("visuals.enabled", true)) return;
        int emitted = 0;
        var iterator = placed.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockKey, Placed> e = iterator.next();
            if (emitted >= 24) break;
            BlockKey key = e.getKey();
            var world = Bukkit.getWorld(key.worldId());
            if (world == null || !world.isChunkLoaded(Math.floorDiv(key.x(), 16),
                    Math.floorDiv(key.z(), 16))) continue;
            if (world.getBlockAt(key.x(), key.y(), key.z()).getType() != Material.TNT) {
                iterator.remove(); dirty = true; continue;
            }
            Location at = new Location(world, key.x() + 0.5, key.y() + 1.12, key.z() + 0.5);
            world.spawnParticle(Particle.END_ROD, at, 1, 0.20, 0.12, 0.20, 0);
            if (e.getValue().kind() == CustomItems.Kind.FORMIDIBOMB) {
                world.spawnParticle(Particle.SMOKE, at, 1, 0.22, 0.1, 0.22, 0.01);
            }
            emitted++;
        }
    }

    public void restore() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("placed");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                BlockKey block = BlockKey.decode(key);
                CustomItems.Kind kind = CustomItems.Kind.parse(section.getString(key + ".kind", ""));
                if (kind != CustomItems.Kind.SUPER_TNT && kind != CustomItems.Kind.FORMIDIBOMB) continue;
                String rawOwner = section.getString(key + ".owner", "");
                UUID owner = rawOwner.isBlank() ? null : UUID.fromString(rawOwner);
                placed.put(block, new Placed(kind, owner));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping invalid placed-TNT record: " + key);
            }
        }
    }

    public void saveIfDirty() { if (dirty) save(); }
    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<BlockKey, Placed> e : placed.entrySet()) {
            String path = "placed." + e.getKey().encode();
            yaml.set(path + ".kind", e.getValue().kind().id);
            yaml.set(path + ".owner", e.getValue().owner() == null ? "" : e.getValue().owner().toString());
        }
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save custom TNT placements: " + ex.getMessage());
        }
    }
}
