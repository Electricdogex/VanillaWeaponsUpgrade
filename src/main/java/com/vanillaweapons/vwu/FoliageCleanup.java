package com.vanillaweapons.vwu;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Waterlogged;

public final class FoliageCleanup {
    private static final ArrayDeque<Location> PENDING = new ArrayDeque<>();
    private static final Set<Location> QUEUED = new HashSet<>();
    private static Location scanCenter;
    private static int scanRadius;
    private static long scanCursor;
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
            BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    public static void changed(Block block) {
        for (BlockFace face : FACES) {
            int blockX = block.getX() + face.getModX(), blockZ = block.getZ() + face.getModZ();
            int blockY = block.getY() + face.getModY();
            World world = block.getWorld();
            if (blockY < world.getMinHeight() || blockY >= world.getMaxHeight()
                    || !world.isChunkLoaded(blockX >> 4, blockZ >> 4)) continue;
            Block neighbor = world.getBlockAt(blockX, blockY, blockZ);
            if (!plant(neighbor.getType())) continue;
            Location location = neighbor.getLocation();
            if (PENDING.size() < 32768 && QUEUED.add(location)) PENDING.add(location);
        }
    }

    private static boolean plant(Material material) {
        if (Tag.FLOWERS.isTagged(material) || Tag.SAPLINGS.isTagged(material)
                || Tag.CROPS.isTagged(material) || Tag.CORAL_PLANTS.isTagged(material)) return true;
        return switch (material) {
            case SNOW, SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, DEAD_BUSH, SEAGRASS, TALL_SEAGRASS,
                    KELP, KELP_PLANT, VINE, CAVE_VINES, CAVE_VINES_PLANT, WEEPING_VINES,
                    WEEPING_VINES_PLANT, TWISTING_VINES, TWISTING_VINES_PLANT, HANGING_ROOTS,
                    SUGAR_CANE, CACTUS, BAMBOO, BAMBOO_SAPLING, BROWN_MUSHROOM, RED_MUSHROOM,
                    CRIMSON_ROOTS, WARPED_ROOTS, NETHER_SPROUTS, SWEET_BERRY_BUSH,
                    SHORT_DRY_GRASS, TALL_DRY_GRASS, BUSH, FIREFLY_BUSH, CACTUS_FLOWER,
                    BIG_DRIPLEAF, BIG_DRIPLEAF_STEM, SMALL_DRIPLEAF, SPORE_BLOSSOM,
                    LILY_PAD, MOSS_CARPET, PALE_MOSS_CARPET, PINK_PETALS, WILDFLOWERS,
                    CHORUS_PLANT, CHORUS_FLOWER, CRIMSON_FUNGUS, WARPED_FUNGUS,
                    MANGROVE_PROPAGULE, PALE_HANGING_MOSS, LEAF_LITTER,
                    PUMPKIN_STEM, MELON_STEM, ATTACHED_PUMPKIN_STEM, ATTACHED_MELON_STEM -> true;
            default -> false;
        };
    }

    public static boolean scan(Location center, int radius) {
        if (scanCenter != null) return false;
        scanCenter = center.clone();
        scanRadius = Math.max(8, Math.min(160, radius));
        scanCursor = 0;
        return true;
    }

    private static void check(VanillaWeaponsPlugin plugin, Block block) {
        if (!plant(block.getType()) || plugin.isProtectedBlock(block) || block.canPlace(block.getBlockData())) return;
        boolean water = block.getType() == Material.SEAGRASS || block.getType() == Material.TALL_SEAGRASS
                || block.getType() == Material.KELP || block.getType() == Material.KELP_PLANT
                || block.getBlockData() instanceof Waterlogged wet && wet.isWaterlogged();
        block.setType(water ? Material.WATER : Material.AIR, false);
        changed(block);
    }

    public static void tick(VanillaWeaponsPlugin plugin) {
        long deadline = System.nanoTime() + 1_500_000;
        int work = 0;
        while (!PENDING.isEmpty() && work++ < 2048 && System.nanoTime() < deadline) {
            Location location = PENDING.remove();
            QUEUED.remove(location);
            if (location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) check(plugin, location.getBlock());
        }
        while (scanCenter != null && work++ < 8192 && System.nanoTime() < deadline) {
            World world = scanCenter.getWorld();
            int width = scanRadius * 2 + 1;
            int height = world.getMaxHeight() - world.getMinHeight();
            long total = (long) width * width * height;
            if (scanCursor >= total) { scanCenter = null; plugin.getLogger().info("Foliage cleanup scan completed."); break; }
            long column = scanCursor / height;
            int blockX = scanCenter.getBlockX() - scanRadius + (int) (column % width);
            int blockZ = scanCenter.getBlockZ() - scanRadius + (int) (column / width);
            if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) { scanCursor = (column + 1) * height; continue; }
            int blockY = world.getMinHeight() + (int) (scanCursor++ % height);
            check(plugin, world.getBlockAt(blockX, blockY, blockZ));
        }
    }

    public static void shutdown() { PENDING.clear(); QUEUED.clear(); scanCenter = null; }
}
