package com.vanillaweapons.vwu;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.Pig;
import org.bukkit.entity.PigZombie;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

/** Server-side fallout. Never searches unloaded chunks or spawns whole swarms in one tick. */
public final class FalloutManager {
    private final VanillaWeaponsPlugin plugin;
    private final File file;
    private final NamespacedKey spawnedCreeper;
    private final Map<UUID, Integer> winter = new HashMap<>();
    private final Map<UUID, Location> winterCenters = new HashMap<>();
    private final RadioactiveSnow snow;
    private final Random rng = new Random();
    private boolean dirty;

    public FalloutManager(VanillaWeaponsPlugin plugin) {
        this.plugin = plugin;
        this.snow = new RadioactiveSnow(plugin);
        this.file = new File(plugin.getDataFolder(), "winter.yml");
        this.spawnedCreeper = new NamespacedKey(plugin, "fallout_creeper");
    }

    public void startWinter(World world, Location center) {
        if (!plugin.getConfig().getBoolean("fallout.enabled", true)) return;
        if (plugin.getConfig().getBoolean("fallout.winter-overworld-only", true)
                && world.getEnvironment() != World.Environment.NORMAL) return;
        int days = Math.max(1, Math.min(14, plugin.getConfig().getInt("fallout.winter-days", 3)));
        int duration = days * 24000; // 3 Minecraft days = 72,000 ticks at standard speed
        winter.put(world.getUID(), duration);
        winterCenters.put(world.getUID(), center.clone());
        world.setStorm(false);
        world.setThundering(false);
        dirty = true;
        save();
    }

    public void tickSecond() {
        snow.prune(winter.keySet());
        if (winter.isEmpty()) return;
        List<UUID> finished = new ArrayList<>();
        for (var e : winter.entrySet()) {
            World world = Bukkit.getWorld(e.getKey());
            if (world == null) continue;
            int left = e.getValue() - 20;
            if (left <= 0) {
                world.setThundering(false);
                world.setStorm(false);
                finished.add(e.getKey());
            } else {
                e.setValue(left);
                snow.tick(world, winterCenters.get(world.getUID()));
            }
        }
        for (UUID id : finished) winter.remove(id);
        dirty = true;
    }

    public boolean stopWinter(World world) {
        if (winter.remove(world.getUID()) == null) return false;
        winterCenters.remove(world.getUID());
        snow.prune(winter.keySet());
        world.setThundering(false);
        world.setStorm(false);
        dirty = true;
        save();
        return true;
    }

    public void finishExplosion(World world, ExplosionEngine.Job job) {
        if (!plugin.getConfig().getBoolean("fallout.enabled", true)) return;
        String base = "fallout." + (job.kind == CustomItems.Kind.SUPER_TNT ? "super-tnt" : "formidibomb");
        Location center = job.center(world);
        convertAnimals(world, center, job.visual,
                plugin.getConfig().getDouble(base + ".mutation-probability", 0.35), job.id);
        int min = Math.max(0, Math.min(30, plugin.getConfig().getInt(base + ".charged-creepers-min", 3)));
        int max = Math.max(min, Math.min(30, plugin.getConfig().getInt(base + ".charged-creepers-max", 5)));
        int desired = min + rng.nextInt(max - min + 1);
        // Cap spawning based only on still-loaded existing plugin creepers.
        int cap = Math.max(0, plugin.getConfig().getInt("fallout.max-plugin-creepers-per-world", 20));
        int existing = 0;
        for (Creeper creeper : world.getEntitiesByClass(Creeper.class)) {
            if (creeper.getPersistentDataContainer().has(spawnedCreeper, PersistentDataType.BYTE)) existing++;
            if (existing >= cap) break;
        }
        desired = Math.min(desired, Math.max(0, cap - existing));
        final int count = desired;
        // Spawn at most one per 5 ticks; nearby chunks must already be loaded.
        for (int i = 0; i < count; i++) {
            int index = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> spawnCreeper(world, job, index), 5L + i * 5L);
        }
    }

    private void convertAnimals(World world, Location center, int radius, double chance, int jobId) {
        int max = Math.max(0, plugin.getConfig().getInt("fallout.max-mutations-per-blast", 60));
        if (max == 0) return;
        List<Entity> candidates = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(center, radius, Math.min(radius, 60), radius)) {
            if (!(entity instanceof Pig) && !(entity instanceof Cow)) continue;
            if (entity instanceof MushroomCow) continue;
            if (plugin.isProtected(world, entity.getLocation().getBlockX(),
                    entity.getLocation().getBlockY(), entity.getLocation().getBlockZ())) continue;
            if (plugin.getConfig().getBoolean("fallout.protect-named-animals", true)
                    && entity.getCustomName() != null) continue;
            if (entity instanceof io.papermc.paper.entity.Leashable leashable && leashable.isLeashed()) continue;
            if (rng.nextDouble() < chance) candidates.add(entity);
            if (candidates.size() >= max) break;
        }
        // Spread conversions out to avoid entity initialization spikes.
        for (int i = 0; i < candidates.size(); i++) {
            Entity old = candidates.get(i);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!old.isValid()) return;
                Location at = old.getLocation();
                if (plugin.isProtected(world, at.getBlockX(), at.getBlockY(), at.getBlockZ())) return;
                if (old instanceof Pig) {
                    world.spawn(at, PigZombie.class, mob -> mob.setPersistent(false));
                } else if (old instanceof Cow) {
                    world.spawn(at, MushroomCow.class, mob -> mob.setPersistent(false));
                } else return;
                old.remove();
                world.spawnParticle(Particle.PORTAL, at.add(0, 0.5, 0), 10, 0.3, 0.5, 0.3);
            }, 4L + i * 2L);
        }
    }

    private void spawnCreeper(World world, ExplosionEngine.Job job, int index) {
        if (Bukkit.getWorld(world.getUID()) == null) return;
        int cap = Math.max(0, plugin.getConfig().getInt("fallout.max-plugin-creepers-per-world", 20));
        if (cap == 0) return;
        int active = 0;
        for (Creeper existing : world.getEntitiesByClass(Creeper.class)) {
            if (existing.getPersistentDataContainer().has(spawnedCreeper, PersistentDataType.BYTE) && ++active >= cap) return;
        }
        Location center = job.center(world);
        // Limited attempts within the already-loaded fallout ring.
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = rng.nextDouble() * 2 * Math.PI;
            double radius = job.radius * (0.75 + rng.nextDouble() * 0.4);
            int x = (int) Math.floor(center.getX() + Math.cos(angle) * radius);
            int z = (int) Math.floor(center.getZ() + Math.sin(angle) * radius);
            int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
            if (!world.isChunkLoaded(cx, cz)) continue;
            int y = world.getHighestBlockYAt(x, z) + 1;
            if (y <= world.getMinHeight() || y + 2 >= world.getMaxHeight()) continue;
            if (plugin.isProtected(world, x, y, z)) continue;
            if (!world.getBlockAt(x, y, z).isEmpty() || !world.getBlockAt(x, y + 1, z).isEmpty()) continue;
            Location at = new Location(world, x + 0.5, y, z + 0.5);
            Creeper creeper = world.spawn(at, Creeper.class, mob -> {
                mob.setPowered(true);
                mob.setPersistent(false);
                mob.getPersistentDataContainer().set(spawnedCreeper, PersistentDataType.BYTE, (byte) 1);
            });
            int lifespan = Math.max(10, plugin.getConfig().getInt("fallout.creeper-despawn-seconds", 90));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (creeper.isValid()) creeper.remove();
            }, Math.min(600, lifespan) * 20L);
            return;
        }
    }

    public void saveIfDirty() { if (dirty) save(); }
    public void shutdown() { snow.shutdown(); save(); }
    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> e : winter.entrySet()) yaml.set("winter." + e.getKey(), e.getValue());
        for (var entry : winterCenters.entrySet()) yaml.set("centers." + entry.getKey(), entry.getValue());
        try { yaml.save(file); dirty = false; }
        catch (IOException e) { plugin.getLogger().severe("Failed saving winter status: " + e.getMessage()); }
    }
    public void restore() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = yaml.getConfigurationSection("winter");
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                World world = Bukkit.getWorld(id);
                int remaining = s.getInt(key, 0);
                if (world == null || remaining <= 0) continue;
                winter.put(id, remaining);
                Location center = yaml.getLocation("centers." + id);
                if (center != null) winterCenters.put(id, center);
                world.setStorm(false);
                world.setThundering(false);
            } catch (IllegalArgumentException ignored) { }
        }
    }
}
