package com.vanillaweapons.vwu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class RadioactiveSnow {
    private final VanillaWeaponsPlugin plugin;
    private final Random random = new Random();
    private final Map<UUID, List<BlockDisplay>> flakes = new HashMap<>();

    public RadioactiveSnow(VanillaWeaponsPlugin plugin) { this.plugin = plugin; }

    public void prune(Set<UUID> worlds) {
        for (UUID playerId : new ArrayList<>(flakes.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !worlds.contains(player.getWorld().getUID())) remove(playerId);
        }
    }

    private void remove(UUID playerId) {
        List<BlockDisplay> displays = flakes.remove(playerId);
        if (displays != null) for (BlockDisplay display : displays) display.remove();
    }

    public void shutdown() {
        for (UUID playerId : new ArrayList<>(flakes.keySet())) remove(playerId);
    }

    public void tick(World world, Location center) {
        int players = 0;
        for (Player player : world.getPlayers()) {
            if (++players > 32) break;
            Location at = player.getLocation();
            if (plugin.isProtected(world, at.getBlockX(), at.getBlockY(), at.getBlockZ())
                    || world.getHighestBlockYAt(at) > at.getBlockY() + 2) { remove(player.getUniqueId()); continue; }
            player.spawnParticle(Particle.SNOWFLAKE, at.clone().add(0, 8, 0), 180, 18, 9, 18, 0.02);
            player.spawnParticle(Particle.DUST, at.clone().add(0, 4, 0), 20, 12, 5, 12, 0,
                    new Particle.DustOptions(Color.fromRGB(130, 190, 95), 0.8f));
            List<BlockDisplay> displays = flakes.computeIfAbsent(player.getUniqueId(), key -> new ArrayList<>());
            for (int index = 0; index < 16; index++) {
                if (displays.size() <= index) {
                    displays.add(world.spawn(at, BlockDisplay.class, display -> {
                        display.setBlock(Material.SNOW_BLOCK.createBlockData());
                        display.setPersistent(false);
                        display.setVisibleByDefault(false);
                        display.setTeleportDuration(20);
                        display.setInterpolationDuration(20);
                        display.setViewRange(1);
                        player.showEntity(plugin, display);
                    }));
                }
                BlockDisplay display = displays.get(index);
                if (!display.getWorld().equals(world)) { remove(player.getUniqueId()); break; }
                display.teleport(at);
                display.setInterpolationDelay(0);
                display.setTransformation(new Transformation(new Vector3f((float) (random.nextDouble() * 24 - 12),
                        (float) (random.nextDouble() * 14), (float) (random.nextDouble() * 24 - 12)),
                        new Quaternionf().rotateXYZ(random.nextFloat(), random.nextFloat(), random.nextFloat()),
                        new Vector3f(0.12f, 0.025f, 0.12f), new Quaternionf()));
            }
        }
        if (center == null) return;
        int radius = Math.max(32, Math.min(256, plugin.getConfig().getInt("fallout.snow-radius", 200)));
        long deadline = System.nanoTime() + 2_000_000;
        for (int sample = 0; sample < 512 && System.nanoTime() < deadline; sample++) {
            int blockX = center.getBlockX() + random.nextInt(radius * 2 + 1) - radius;
            int blockZ = center.getBlockZ() + random.nextInt(radius * 2 + 1) - radius;
            if (Math.hypot(blockX - center.getX(), blockZ - center.getZ()) > radius
                    || !world.isChunkLoaded(blockX >> 4, blockZ >> 4)) continue;
            int blockY = world.getHighestBlockYAt(blockX, blockZ);
            if (blockY + 1 >= world.getMaxHeight()) continue;
            Block ground = world.getBlockAt(blockX, blockY, blockZ);
            Block target = ground.getRelative(0, 1, 0);
            if (!ground.getType().isOccluding() || !target.isEmpty()
                    || plugin.isProtectedBlock(ground) || plugin.isProtectedBlock(target)) continue;
            if (!target.canPlace(Material.SNOW.createBlockData())) continue;
            target.setType(Material.SNOW, false);
        }
    }
}
