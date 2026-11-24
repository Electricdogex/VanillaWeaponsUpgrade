package com.vanillaweapons.vwu;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class VanillaWeaponsPlugin extends JavaPlugin {
    private CustomItems items;
    private FalloutManager fallout;
    private ExplosionEngine explosions;
    private WeaponListener weapons;
    private CommandBeacons beacons;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        items = new CustomItems(this);
        items.registerRecipes();
        beacons = new CommandBeacons(this, items);
        Bukkit.getPluginManager().registerEvents(beacons, this);
        Bukkit.getScheduler().runTaskTimer(this, beacons::tick, 5L, 5L);
        fallout = new FalloutManager(this);
        explosions = new ExplosionEngine(this, fallout);
        weapons = new WeaponListener(this, items, explosions);
        Bukkit.getPluginManager().registerEvents(items, this);
        Bukkit.getPluginManager().registerEvents(weapons, this);
        new VwuCommand(this, items, explosions, fallout).register();
        explosions.restore();
        fallout.restore();
        Bukkit.getScheduler().runTaskTimer(this, explosions::tick, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, TerrainVeil::tick, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, () -> FoliageCleanup.tick(this), 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, fallout::tickSecond, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, weapons::tickPlacedEffects, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            explosions.saveIfDirty();
            weapons.saveIfDirty();
            fallout.saveIfDirty();
        }, 200L, 200L);
        getLogger().info("Vanilla Weapons Upgrade " + getPluginMeta().getVersion() + " loaded: Paper 26.3, adaptive crater engine enabled.");
    }

    @Override
    public void onDisable() {
        BlastEffects.shutdown();
        TerrainVeil.shutdown();
        FoliageCleanup.shutdown();
        if (beacons != null) beacons.shutdown();
        if (explosions != null) explosions.shutdown();
        if (weapons != null) weapons.save();
        if (fallout != null) fallout.shutdown();
    }

    public boolean tryChainPrime(org.bukkit.block.Block block) {
        return weapons != null && weapons.primeIfCustom(block);
    }

    public boolean isProtected(org.bukkit.World world, int x, int y, int z) {
        if (getConfig().getStringList("protection.disabled-worlds").contains(world.getName())) return true;
        int r = Math.max(0, getConfig().getInt("protection.spawn-radius", 64));
        if (r > 0 && world.getEnvironment() == org.bukkit.World.Environment.NORMAL) {
            var spawn = world.getSpawnLocation();
            long dx = (long) x - spawn.getBlockX();
            long dz = (long) z - spawn.getBlockZ();
            if (dx * dx + dz * dz <= (long) r * r) return true;
        }
        return y < world.getMinHeight() || y >= world.getMaxHeight();
    }

    public boolean isProtectedBlock(org.bukkit.block.Block block) {
        if (beacons != null && beacons.protects(block)) return true;
        if (isProtected(block.getWorld(), block.getX(), block.getY(), block.getZ())) return true;
        if (getConfig().getBoolean("protection.protect-bedrock", true)
                && block.getType() == org.bukkit.Material.BEDROCK) return true;
        return getConfig().getStringList("protection.protected-block-types").contains(block.getType().name());
    }

    public CommandBeacons commandBeacons() { return beacons; }
}
