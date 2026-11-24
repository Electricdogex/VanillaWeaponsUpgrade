package com.vanillaweapons.vwu;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class TerrainVeil {
    private record Fragment(BlockDisplay display, Location origin, int born, float angle, float speed, float size) {}
    private record Spray(Location origin, int born, double angle) {}
    private static final List<Fragment> FRAGMENTS = new ArrayList<>();
    private static final List<Spray> SPRAYS = new ArrayList<>();
    private static int clock;
    private static int admitted;

    public static void burst(VanillaWeaponsPlugin plugin, Location at, BlockData material, Location center) {
        if (admitted >= 8 || FRAGMENTS.size() >= 128) return;
        admitted++;
        at.getWorld().spawnParticle(Particle.BLOCK, at, 18, 0.8, 0.6, 0.8, material);
        at.getWorld().spawnParticle(Particle.DUST_PLUME, at, 4, 1, 0.5, 1, 0.04);
        float angle = (float) Math.atan2(at.getX() - center.getX(), at.getZ() - center.getZ());
        float size = 0.35f + (float) Math.random() * 0.5f;
        BlockDisplay display = at.getWorld().spawn(at, BlockDisplay.class, entity -> {
            entity.setBlock(material);
            entity.setPersistent(false);
            entity.setViewRange(3);
            entity.setDisplayWidth(100);
            entity.setDisplayHeight(40);
            entity.setInterpolationDuration(1);
            entity.setTransformation(new Transformation(new Vector3f(-size / 2), new Quaternionf(), new Vector3f(size), new Quaternionf()));
        });
        FRAGMENTS.add(new Fragment(display, at.clone(), clock, angle, 1.1f + (float) Math.random() * 0.8f, size));
    }

    public static void water(VanillaWeaponsPlugin plugin, Location at, Location center) {
        if (admitted >= 8 || SPRAYS.size() >= 64) return;
        admitted++;
        SPRAYS.add(new Spray(at.clone(), clock, Math.atan2(at.getX() - center.getX(), at.getZ() - center.getZ())));
        at.getWorld().spawnParticle(Particle.SPLASH, at, 18, 1, 0.3, 1, 0.2);
    }

    public static void tick() {
        clock++;
        admitted = 0;
        var sprays = SPRAYS.iterator();
        while (sprays.hasNext()) {
            Spray spray = sprays.next();
            int age = clock - spray.born();
            if (age > 16) { sprays.remove(); continue; }
            double distance = age * 1.7;
            Location head = spray.origin().clone().add(Math.sin(spray.angle()) * distance,
                    age * 0.65 - age * age * 0.035, Math.cos(spray.angle()) * distance);
            if (!head.getWorld().isChunkLoaded(head.getBlockX() >> 4, head.getBlockZ() >> 4)) { sprays.remove(); continue; }
            head.getWorld().spawnParticle(Particle.SPLASH, head, 10, 0.4 + age * 0.07, 0.25, 0.4 + age * 0.07, 0.04);
            head.getWorld().spawnParticle(Particle.FALLING_WATER, head, 7, 0.5, 0.25, 0.5, 0);
            if (age < 8) head.getWorld().spawnParticle(Particle.CLOUD, head, 2, 0.3, 0.2, 0.3, 0.01);
        }
        var iterator = FRAGMENTS.iterator();
        while (iterator.hasNext()) {
            Fragment fragment = iterator.next();
            int age = clock - fragment.born();
            BlockDisplay display = fragment.display();
            double distance = fragment.speed() * age;
            Location head = fragment.origin().clone().add(Math.sin(fragment.angle()) * distance,
                    age * 0.8 - age * age * 0.04, Math.cos(fragment.angle()) * distance);
            if (age >= 20 || !display.isValid() || !head.getWorld().isChunkLoaded(head.getBlockX() >> 4, head.getBlockZ() >> 4)) {
                display.remove(); iterator.remove(); continue;
            }
            display.setInterpolationDelay(0);
            float size = fragment.size();
            display.setTransformation(new Transformation(new Vector3f((float) (head.getX() - fragment.origin().getX()) - size / 2,
                    (float) (head.getY() - fragment.origin().getY()), (float) (head.getZ() - fragment.origin().getZ()) - size / 2),
                    new Quaternionf().rotateXYZ(age * 0.2f, fragment.angle(), age * 0.13f), new Vector3f(size), new Quaternionf()));
            if (age % 3 == 0) head.getWorld().spawnParticle(Particle.BLOCK, head, 3, 0.3, 0.3, 0.3, display.getBlock());
        }
    }

    public static void shutdown() {
        for (Fragment fragment : FRAGMENTS) fragment.display().remove();
        FRAGMENTS.clear(); SPRAYS.clear();
    }
}
