package com.vanillaweapons.vwu;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.joml.Quaternionf;

public final class BlastEffects extends BukkitRunnable {
    private final VanillaWeaponsPlugin plugin;
    private final World world;
    private final Location center;
    private final double radius;
    private final boolean large;
    private final Random random = new Random();
    private final List<Player> viewers = new ArrayList<>();
    private int age;
    private int fires;
    private final List<BlockDisplay> debris = new ArrayList<>();
    private final List<BlockDisplay> cloud = new ArrayList<>();
    private final List<org.bukkit.util.Vector> trajectories = new ArrayList<>();
    private static final List<BlastEffects> ACTIVE = new ArrayList<>();

    public BlastEffects(VanillaWeaponsPlugin plugin, ExplosionEngine.Job job, World world) {
        this.plugin = plugin;
        this.world = world;
        center = job.center(world);
        radius = Math.min(180, job.visual);
        large = job.kind == CustomItems.Kind.FORMIDIBOMB;
    }

    public void start() {
        if (ACTIVE.size() >= 3) { ACTIVE.getFirst().dispose(); }
        ACTIVE.add(this);
        runTaskTimer(plugin, 0, 2);
    }

    public static void shutdown() {
        for (BlastEffects effect : new ArrayList<>(ACTIVE)) effect.dispose();
    }

    private void dispose() {
        for (BlockDisplay display : debris) display.remove();
        for (BlockDisplay display : cloud) display.remove();
        cloud.clear();
        debris.clear();
        ACTIVE.remove(this);
        cancel();
    }

    @Override public void run() {
        int duration = Math.max(10, Math.min(600, plugin.getConfig().getInt("visuals.fallout-seconds", large ? 180 : 60))) * 20;
        if (!plugin.isEnabled() || age > duration) { dispose(); return; }
        viewers.clear();
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= 356 * 356) viewers.add(player);
        }
        if (age == 0) {
            for (int index = 0; index < (large ? 240 : 160); index++) {
                BlockDisplay display = world.spawn(center, BlockDisplay.class, entity -> {
                    entity.setBlock(Material.LIGHT_GRAY_CONCRETE.createBlockData());
                    entity.setPersistent(false);
                    entity.setViewRange(8);
                    entity.setDisplayWidth(2200);
                    entity.setDisplayHeight(240);
                    entity.setInterpolationDuration(4);
                    entity.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                });
                cloud.add(display);
            }
            for (int index = 0; index < (large ? 96 : 48); index++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double speed = (large ? 1.4 : 0.8) + random.nextDouble() * 1.2;
                BlockDisplay display = world.spawn(center, BlockDisplay.class, entity -> {
                    entity.setBlock((random.nextBoolean() ? Material.STONE : Material.DEEPSLATE).createBlockData());
                    entity.setPersistent(false);
                    entity.setTeleportDuration(2);
                    entity.setViewRange(4);
                    entity.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                });
                debris.add(display);
                trajectories.add(new org.bukkit.util.Vector(Math.cos(angle) * speed, 1.4 + random.nextDouble() * 1.4, Math.sin(angle) * speed));
            }
            for (Player player : viewers) {
                Location at = player.getLocation();
                if (plugin.isProtected(world, at.getBlockX(), at.getBlockY(), at.getBlockZ())) continue;
                if (large && plugin.getConfig().getBoolean("visuals.formidibomb-flash", true)) {
                    player.spawnParticle(Particle.FLASH, player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(0.6)),
                            1, 0, 0, 0, 0, org.bukkit.Color.WHITE);
                    org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline() && player.getWorld().equals(world)) {
                            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, true, false, false));
                        }
                    }, 4);
                }
                long delay = Math.max(2, Math.min(30, Math.round(at.distance(center) / 12)));
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!player.isOnline() || !player.getWorld().equals(world)) return;
                    player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.5f);
                    player.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.3f, 0.5f);
                    player.playSound(player.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 0.8f, 0.5f);
                }, delay);
            }
        }
        if (age % 4 == 0 && age <= 600) animateCloud();
        if (age > 600 && !cloud.isEmpty()) {
            for (BlockDisplay display : cloud) display.remove();
            cloud.clear();
        }
        if (age <= 100) {
            for (int index = 0; index < debris.size(); index++) {
                BlockDisplay display = debris.get(index);
                org.bukkit.util.Vector velocity = trajectories.get(index);
                double seconds = age;
                Location target = center.clone().add(velocity.clone().multiply(seconds)).add(0, -0.055 * seconds * seconds, 0);
                if (!world.isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)
                        || target.getY() < world.getMinHeight()) { display.remove(); continue; }
                display.teleport(target);
                float size = large ? 0.85f : 0.55f;
                display.setTransformation(new Transformation(new Vector3f(-size / 2),
                        new Quaternionf().rotateXYZ(age * 0.08f, index, age * 0.05f), new Vector3f(size), new Quaternionf()));
                emit(Particle.FLAME, target, 3, 0.3, 0.3, 0.3, 0.02);
                emit(Particle.SMOKE, target, 2, 0.4, 0.4, 0.4, 0.01);
            }
        } else if (!debris.isEmpty()) {
            for (BlockDisplay display : debris) display.remove();
            debris.clear();
        }
        if (age <= 160) heatWave();
        if (age <= 200) {
            double wave = age * 5.0;
            for (int point = 0; point < 128; point++) {
                double angle = point * Math.PI * 2 / 128;
                Location at = center.clone().add(Math.cos(angle) * wave, 1 + age * 0.025, Math.sin(angle) * wave);
                emit(Particle.CLOUD, at, 8, 1.5, 1.2, 1.5, 0.15);
                if (age < 30) emit(Particle.FLAME, at, 2, 0.4, 0.3, 0.4, 0.08);
            }
            if (age < 24) emit(Particle.EXPLOSION_EMITTER, center.clone().add(0, age * 0.25, 0), 1, 0, 0, 0, 0);
        }
        if (age < 240) {
            double scale = large ? 1.8 : 1;
            double height = Math.min(age * 0.35, 32) * scale;
            for (int point = 0; point < 96; point++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double spread = Math.sqrt(random.nextDouble()) * (4 + height * 0.38);
                Location cap = center.clone().add(Math.cos(angle) * spread, height + random.nextDouble() * 5, Math.sin(angle) * spread);
                emit(Particle.CAMPFIRE_SIGNAL_SMOKE, cap, 4, 2, 2, 2, 0.06);
                if (age < 70) emit(Particle.FLAME, cap, 2, 1, 1, 1, 0.02);
                Location stem = center.clone().add(random.nextGaussian() * 2 * scale, random.nextDouble() * height, random.nextGaussian() * 2 * scale);
                emit(Particle.LARGE_SMOKE, stem, 6, 2, 2, 2, 0.06);
            }
        }
        if (age % 20 == 0 && plugin.getConfig().getBoolean("fallout.enabled", true)
                && plugin.getConfig().getBoolean("fallout.radiation.enabled", true)) {
            for (Player player : viewers) {
                Location at = player.getLocation();
                double horizontal = Math.hypot(at.getX() - center.getX(), at.getZ() - center.getZ());
                if (horizontal > radius || Math.abs(at.getY() - center.getY()) > 45) continue;
                if (plugin.isProtected(world, at.getBlockX(), at.getBlockY(), at.getBlockZ())) continue;
                player.spawnParticle(Particle.ASH, at.clone().add(0, 2, 0), 24, 6, 3, 6, 0);
                for (int patch = 0; patch < 5; patch++) {
                    double patchAngle = patch * 2.3999632297;
                    double patchRadius = radius * (0.2 + patch * 0.13);
                    int patchX = (int) Math.floor(center.getX() + Math.cos(patchAngle) * patchRadius);
                    int patchZ = (int) Math.floor(center.getZ() + Math.sin(patchAngle) * patchRadius);
                    if (!world.isChunkLoaded(patchX >> 4, patchZ >> 4)) continue;
                    int patchY = world.getHighestBlockYAt(patchX, patchZ) + 1;
                    if (plugin.isProtected(world, patchX, patchY, patchZ)) continue;
                    player.spawnParticle(Particle.DUST, new Location(world, patchX + 0.5, patchY + 0.6, patchZ + 0.5),
                            30, 4, 0.8, 4, 0, new Particle.DustOptions(org.bukkit.Color.fromRGB(90, 210, 45), 1.8f));
                }
                if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) continue;
                player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 160, 0, true, false, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 80, 0, true, false, true));
            }
        }
        age += 2;
    }

    private void heatWave() {
        double reach = Math.max(radius, Math.min(256, plugin.getConfig().getDouble("visuals.heat-wave-radius-multiplier", 2.0) * radius));
        double wave = reach * age / 160.0;
        int points = 512;
        int cap = Math.max(0, Math.min(4096, plugin.getConfig().getInt("visuals.max-heat-fires", 2048)));
        long deadline = System.nanoTime() + 2_000_000;
        int initialFires = fires;
        for (int point = 0; point < points; point++) {
            if (System.nanoTime() > deadline || fires - initialFires >= 32) break;
            double angle = point * Math.PI * 2 / points + age * 0.017;
            int blockX = (int) Math.floor(center.getX() + Math.cos(angle) * wave);
            int blockZ = (int) Math.floor(center.getZ() + Math.sin(angle) * wave);
            if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) continue;
            int blockY = world.getHighestBlockYAt(blockX, blockZ);
            if (blockY + 1 >= world.getMaxHeight()) continue;
            org.bukkit.block.Block wood = world.getBlockAt(blockX, blockY, blockZ);
            Location at = wood.getLocation().add(0.5, 1.2, 0.5);
            if (point % 4 == 0) emit(Particle.CLOUD, at, 12, 2.5, 1.8, 2.5, 0.15);
            if (!plugin.getConfig().getBoolean("visuals.heat-wave-ignites-wood", true) || fires >= cap) continue;
            for (int depth = 0; depth < 12 && blockY - depth >= world.getMinHeight(); depth++) {
                org.bukkit.block.Block fuel = world.getBlockAt(blockX, blockY - depth, blockZ);
                if (!fuel.getType().isFlammable() || plugin.isProtectedBlock(fuel)) continue;
                for (org.bukkit.block.BlockFace face : new org.bukkit.block.BlockFace[] {
                        org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                        org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
                    int fireX = blockX + face.getModX(), fireZ = blockZ + face.getModZ();
                    if (!world.isChunkLoaded(fireX >> 4, fireZ >> 4)) continue;
                    org.bukkit.block.Block fire = fuel.getRelative(face);
                    if (fire.getY() >= world.getMaxHeight() || !fire.isEmpty() || plugin.isProtectedBlock(fire)) continue;
                    if (fires >= cap || fires - initialFires >= 32) break;
                    BlockIgniteEvent event = new BlockIgniteEvent(fire, BlockIgniteEvent.IgniteCause.EXPLOSION, (Entity) null);
                    org.bukkit.Bukkit.getPluginManager().callEvent(event);
                    if (event.isCancelled()) continue;
                    fire.setType(Material.FIRE);
                    fires++;
                }
            }
        }
    }

    private void animateCloud() {
        double growth = Math.min(1, (age + 4) / 150.0);
        double scale = (large ? 1.8 : 1.0) * growth;
        double fade = age > 440 ? Math.max(0.02, (600 - age) / 160.0) : 1;
        for (int index = 0; index < cloud.size(); index++) {
            BlockDisplay display = cloud.get(index);
            double angle = index * 2.3999632297;
            double offsetX, offsetY, offsetZ, size;
            if (index < 40) {
                if (age > 200) { display.remove(); continue; }
                double waveRadius = age * 5.0;
                double waveAngle = index * Math.PI * 2 / 40;
                offsetX = Math.cos(waveAngle) * waveRadius;
                offsetZ = Math.sin(waveAngle) * waveRadius;
                offsetY = 2;
                size = 5;
                if (age == 0) display.setBlock(Material.WHITE_CONCRETE_POWDER.createBlockData());
            } else if (index < 40 + (cloud.size() - 40) * 0.28) {
                double height = ((index - 40) / ((cloud.size() - 40) * 0.28)) * 48;
                double width = 3 + height * 0.07;
                offsetX = Math.cos(angle) * width * scale;
                offsetZ = Math.sin(angle) * width * scale;
                offsetY = height * scale;
                size = (7 + index % 4) * scale;
            } else {
                double spread = Math.sqrt((index * 0.61803398875) % 1);
                double radial = spread * 29;
                offsetX = Math.cos(angle) * radial * scale;
                offsetZ = Math.sin(angle) * radial * scale;
                offsetY = (46 + Math.sqrt(1 - spread * spread) * 15 + Math.sin(angle * 3) * 2) * scale;
                size = (9 + index % 5) * scale;
            }
            if (age > 440 && index >= 40) {
                double dissolveAt = 440 + ((index * 73) % 160);
                if (age >= dissolveAt) {
                    if (display.isValid()) {
                        emit(Particle.CAMPFIRE_SIGNAL_SMOKE, center.clone().add(offsetX, offsetY + size / 2, offsetZ),
                                12, size / 3, size / 3, size / 3, 0.04);
                        display.remove();
                    }
                    continue;
                }
            }
            if (!display.isValid()) continue;
            if (index >= 40 && (age == 0 || age == 80)) {
                Material material = age < 80 ? (index % 3 == 0 ? Material.ORANGE_CONCRETE : Material.YELLOW_CONCRETE)
                        : (index % 3 == 0 ? Material.GRAY_CONCRETE : Material.LIGHT_GRAY_CONCRETE);
                display.setBlock(material.createBlockData());
            }
            display.setInterpolationDelay(0);
            display.setTransformation(new Transformation(new Vector3f((float) (offsetX - size / 2), (float) offsetY,
                    (float) (offsetZ - size / 2)), index < 40 ? new Quaternionf()
                            : new Quaternionf().rotateXYZ(index * 0.73f, index * 1.17f, index * 0.37f),
                    index < 40 ? new Vector3f(5, 0.6f, 5) : new Vector3f((float) size), new Quaternionf()));
        }
    }

    private void emit(Particle particle, Location at, int count, double spreadX, double spreadY, double spreadZ, double speed) {
        for (Player player : viewers) {
            player.spawnParticle(particle, at, count, spreadX, spreadY, spreadZ, speed);
        }
    }
}
