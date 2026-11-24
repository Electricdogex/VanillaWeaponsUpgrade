package com.vanillaweapons.vwu;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Main-thread, time-budgeted crater processing. One explosion chunk is held at a time.
 * Never scans a full sphere or edits chunks asynchronously. Chunk requests are async,
 * and jobs are journaled so that a restart can safely repeat partially written air blocks.
 */
public final class ExplosionEngine {
    public static final class Job {
        public int id;
        public CustomItems.Kind kind;
        public UUID worldId, owner;
        public int x, y, z, radius, depth, visual;
        public int chunkX, chunkZ;
        public int column;
        public int nextY = Integer.MIN_VALUE;
        public int phase; // 0 = excavation, 1 = glass conversion
        public int band;
        public long removed, glassed;

        public String label() { return kind == CustomItems.Kind.SUPER_TNT ? "Super TNT" : "Formidibomb"; }
        public Location center(World world) { return new Location(world, x + 0.5, y + 0.5, z + 0.5); }
        public int minX() { return Math.floorDiv(x - radius, 16); }
        public int maxX() { return Math.floorDiv(x + radius, 16); }
        public int minZ() { return Math.floorDiv(z - radius, 16); }
        public int maxZ() { return Math.floorDiv(z + radius, 16); }
    }

    private final VanillaWeaponsPlugin plugin;
    private final FalloutManager fallout;
    private final File file;
    private final Map<Integer, Job> jobs = new LinkedHashMap<>();
    private int nextId = 1;
    private boolean dirty, paused;
    private boolean journalHealthy = true;
    private Chunk loadedChunk;
    private Job loadedJob;
    private boolean loading;
    private long requestGeneration, requestTick;
    private long ticks;
    private double lastProcessingMs;

    public ExplosionEngine(VanillaWeaponsPlugin plugin, FalloutManager fallout) {
        this.plugin = plugin;
        this.fallout = fallout;
        this.file = new File(plugin.getDataFolder(), "jobs.yml");
    }

    private String settings(CustomItems.Kind kind) {
        return "explosions." + (kind == CustomItems.Kind.SUPER_TNT ? "super-tnt" : "formidibomb");
    }

    public boolean detonate(Location location, CustomItems.Kind kind, UUID owner) {
        if (kind != CustomItems.Kind.SUPER_TNT && kind != CustomItems.Kind.FORMIDIBOMB) return false;
        World world = location.getWorld();
        if (world == null || plugin.isProtected(world, location.getBlockX(), location.getBlockY(), location.getBlockZ())) return false;
        int limit = Math.max(1, plugin.getConfig().getInt("explosions.max-active-jobs", 6));
        long same = jobs.values().stream().filter(j -> j.kind == kind).count();
        int sameLimit = Math.max(1, plugin.getConfig().getInt("explosions.max-"
                + (kind == CustomItems.Kind.SUPER_TNT ? "super-jobs" : "formidibomb-jobs"), 1));
        if (jobs.size() >= limit || same >= sameLimit) {
            plugin.getLogger().warning("Explosion queue full. Cancelled " + kind.id + " at " + location);
            return false;
        }
        String base = settings(kind);
        Job job = new Job();
        job.id = nextId++;
        job.kind = kind;
        job.worldId = world.getUID();
        job.owner = owner;
        job.x = location.getBlockX(); job.y = location.getBlockY(); job.z = location.getBlockZ();
        job.radius = Math.max(4, Math.min(160, plugin.getConfig().getInt(base + ".terrain-radius", 32)));
        job.depth = Math.max(1, Math.min(48, plugin.getConfig().getInt(base + ".max-depth", 15)));
        job.visual = Math.max(job.radius, Math.min(256, plugin.getConfig().getInt(base + ".visual-radius", job.radius)));
        job.chunkX = job.minX(); job.chunkZ = job.minZ();
        jobs.put(job.id, job);
        dirty = true;
        save(); // Save admission before destructively changing terrain.
        if (!journalHealthy) {
            jobs.remove(job.id);
            dirty = true;
            return false;
        }
        visuals(job, world);
        damageEntities(job, world);
        if (kind == CustomItems.Kind.FORMIDIBOMB) fallout.startWinter(world, location);
        plugin.getLogger().info("Queued " + job.label() + " job #" + job.id + " in " + world.getName()
                + " at " + job.x + ", " + job.y + ", " + job.z + " (owner=" + owner + ")");
        return true;
    }

    public void tick() {
        ticks++;
        if (!journalHealthy) { lastProcessingMs = 0; return; }
        if (jobs.isEmpty()) { releaseChunk(); lastProcessingMs = 0; return; }
        double mspt = Bukkit.getAverageTickTime();
        double stop = plugin.getConfig().getDouble("explosions.pause-above-mspt", 45.0);
        double resume = plugin.getConfig().getDouble("explosions.resume-below-mspt", 40.0);
        if (paused && mspt <= resume) paused = false;
        if (!paused && mspt >= stop) paused = true;
        if (paused) { lastProcessingMs = 0; return; }

        // Super TNT receives priority over a background Formidibomb excavation.
        Job job = jobs.values().stream()
                .min(Comparator.comparingInt(j -> j.kind == CustomItems.Kind.SUPER_TNT ? 0 : 1))
                .orElse(null);
        if (job == null) return;
        if (loadedJob != job) releaseChunk();
        if (!ensureChunk(job)) return;

        long start = System.nanoTime();
        long budgetNs = (long) (1_000_000 * Math.max(0.2,
                Math.min(15.0, plugin.getConfig().getDouble("explosions.time-budget-ms", 10.0))));
        int maxChanges = Math.max(1, Math.min(32768,
                plugin.getConfig().getInt("explosions.max-block-changes-per-tick", 4096)));
        int changes = 0;
        int operations = 0;
        World world = loadedChunk.getWorld();
        while (changes < maxChanges && operations < 32768) {
            if ((operations & 7) == 0 && System.nanoTime() - start >= budgetNs) break;
            if (job.column >= 256) {
                advanceChunk(job);
                if (!ensureChunk(job)) break;
                continue;
            }
            int scatteredColumn = (job.column * 149 + 37) & 255;
            int bx = (job.chunkX << 4) + (scatteredColumn & 15);
            int bz = (job.chunkZ << 4) + (scatteredColumn >> 4);
            long dx = (long) bx - job.x, dz = (long) bz - job.z;
            long dist2 = dx * dx + dz * dz;
            if (dist2 < (long) job.band * job.band || dist2 >= (long) (job.band + 4) * (job.band + 4)) {
                nextColumn(job);
                continue;
            }
            if (dist2 > (long) job.radius * job.radius) {
                nextColumn(job);
                continue;
            }
            double roughRadius = job.radius * (0.96 + 0.04 * terrainNoise(bx, bz, 17, job.id));
            if (dist2 > roughRadius * roughRadius) { nextColumn(job); continue; }
            double frac = Math.max(0, 1.0 - dist2 / (roughRadius * roughRadius));
            if (job.phase == 0) {
                // Bowl profile. Fixed center height makes repeating partially cleared columns idempotent.
                double noise = terrainNoise(bx, bz, 9, job.id) * 0.75 + terrainNoise(bx, bz, 23, job.id + 71) * 0.25;
                int floor = Math.max(world.getMinHeight(), job.y - Math.max(1,
                        Math.min(job.depth, (int) Math.ceil(job.depth * frac + noise * 1.7 * frac))));
                if (job.nextY == Integer.MIN_VALUE) {
                    int ceiling = Math.min(world.getMaxHeight() - 1, Math.max(job.y,
                            world.getHighestBlockYAt(bx, bz)));
                    job.nextY = ceiling;
                }
                if (job.nextY < floor) { nextColumn(job); continue; }
                Block block = loadedChunk.getBlock(bx & 15, job.nextY, bz & 15);
                job.nextY--;
                operations++;
                if (!block.isEmpty() && !plugin.isProtectedBlock(block)) {
                    if (block.getType() == Material.TNT && plugin.tryChainPrime(block)) {
                        changes++;
                        dirty = true;
                        continue;
                    }
                    boolean fluid = block.isLiquid() || block.getBlockData() instanceof org.bukkit.block.data.Waterlogged wet && wet.isWaterlogged();
                    if ((job.removed & 31) == 0 && plugin.getConfig().getBoolean("visuals.enabled", true)) {
                        if (fluid && block.getType() != Material.LAVA) {
                            TerrainVeil.water(plugin, block.getLocation().add(0.5, 0.5, 0.5), job.center(world));
                        } else if ((job.removed & 63) == 0) {
                            TerrainVeil.burst(plugin, block.getLocation().add(0.5, 0.5, 0.5), block.getBlockData(), job.center(world));
                        }
                    }
                    boolean updateFluid = fluid;
                    if (!updateFluid) {
                        for (org.bukkit.block.BlockFace face : new org.bukkit.block.BlockFace[] {
                                org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN, org.bukkit.block.BlockFace.NORTH,
                                org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
                            int neighborX = bx + face.getModX(), neighborZ = bz + face.getModZ();
                            int neighborY = block.getY() + face.getModY();
                            if (neighborY < world.getMinHeight() || neighborY >= world.getMaxHeight()
                                    || !world.isChunkLoaded(neighborX >> 4, neighborZ >> 4)) continue;
                            Block neighbor = world.getBlockAt(neighborX, neighborY, neighborZ);
                            if (neighbor.isLiquid() || neighbor.getBlockData() instanceof org.bukkit.block.data.Waterlogged wet && wet.isWaterlogged()) {
                                updateFluid = true;
                                break;
                            }
                        }
                    }
                    block.setType(Material.AIR, updateFluid || !plugin.getConfig().getBoolean("explosions.suppress-block-physics", true));
                    FoliageCleanup.changed(block);
                    if ((job.removed & 127) == 0 && plugin.getConfig().getBoolean("visuals.enabled", true)) {
                        world.spawnParticle(Particle.DUST_PLUME, block.getLocation().add(0.5, 0.5, 0.5), 3, 0.7, 0.5, 0.7, 0.03);
                    }
                    job.removed++;
                    changes++;
                    dirty = true;
                }
            } else {
                // Only newly exposed surfaces, and only at most two layers per column.
                int levels = Math.max(1, Math.min(2,
                        plugin.getConfig().getInt("fallout.glass-surface-layers", 2)));
                if (job.nextY == Integer.MIN_VALUE) job.nextY = 0;
                if (job.nextY >= levels || !shouldGlass(job, bx, bz)) {
                    nextColumn(job);
                    continue;
                }
                int y = world.getHighestBlockYAt(bx, bz) - job.nextY;
                job.nextY++;
                operations++;
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
                Block block = loadedChunk.getBlock(bx & 15, y, bz & 15);
                if (plugin.isProtectedBlock(block)) continue;
                Material target = switch (block.getType()) {
                    case SAND, SANDSTONE, CUT_SANDSTONE, CHISELED_SANDSTONE, SMOOTH_SANDSTONE,
                            SANDSTONE_SLAB, SANDSTONE_STAIRS, SANDSTONE_WALL, CUT_SANDSTONE_SLAB,
                            SMOOTH_SANDSTONE_SLAB, SMOOTH_SANDSTONE_STAIRS -> Material.GLASS;
                    case RED_SAND, RED_SANDSTONE, CUT_RED_SANDSTONE, CHISELED_RED_SANDSTONE,
                            SMOOTH_RED_SANDSTONE, RED_SANDSTONE_SLAB, RED_SANDSTONE_STAIRS,
                            RED_SANDSTONE_WALL, CUT_RED_SANDSTONE_SLAB, SMOOTH_RED_SANDSTONE_SLAB,
                            SMOOTH_RED_SANDSTONE_STAIRS -> Material.ORANGE_STAINED_GLASS;
                    default -> null;
                };
                if (target != null) {
                    block.setType(target, !plugin.getConfig().getBoolean("explosions.suppress-block-physics", true));
                    job.glassed++;
                    changes++;
                    dirty = true;
                }
            }
        }
        lastProcessingMs = (System.nanoTime() - start) / 1_000_000.0;
    }

    private boolean shouldGlass(Job job, int x, int z) {
        if (!plugin.getConfig().getBoolean("fallout.convert-sand-to-glass", true)) return false;
        double chance = plugin.getConfig().getDouble("fallout.glass-conversion-probability", 0.65);
        long h = (long) x * 73856093L ^ (long) z * 19349663L ^ (long) job.id * 83492791L;
        h ^= h >>> 13;
        return (h & 65535L) / 65535.0 < chance;
    }

    private static double terrainNoise(int blockX, int blockZ, int scale, int seed) {
        int gridX = Math.floorDiv(blockX, scale), gridZ = Math.floorDiv(blockZ, scale);
        double blendX = Math.floorMod(blockX, scale) / (double) scale;
        double blendZ = Math.floorMod(blockZ, scale) / (double) scale;
        blendX = blendX * blendX * (3 - 2 * blendX);
        blendZ = blendZ * blendZ * (3 - 2 * blendZ);
        double north = hash(gridX, gridZ, seed) * (1 - blendX) + hash(gridX + 1, gridZ, seed) * blendX;
        double south = hash(gridX, gridZ + 1, seed) * (1 - blendX) + hash(gridX + 1, gridZ + 1, seed) * blendX;
        return north * (1 - blendZ) + south * blendZ;
    }

    private static double hash(int gridX, int gridZ, int seed) {
        long value = gridX * 341873128712L + gridZ * 132897987541L + seed * 42317861L;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return (value & 0xffffffL) / (double) 0xffffffL * 2 - 1;
    }

    private void nextColumn(Job job) {
        job.column++;
        job.nextY = Integer.MIN_VALUE;
        dirty = true;
    }

    private boolean ensureChunk(Job job) {
        World world = Bukkit.getWorld(job.worldId);
        if (world == null) return false;
        // Skip chunk columns wholly outside the circle without loading those chunks.
        int skips = 0;
        while (job.chunkX <= job.maxX() && skips++ < 300) {
            int nearX = Math.max(job.chunkX << 4, Math.min(job.x, (job.chunkX << 4) + 15));
            int nearZ = Math.max(job.chunkZ << 4, Math.min(job.z, (job.chunkZ << 4) + 15));
            long dx = (long) nearX - job.x, dz = (long) nearZ - job.z;
            int farX = Math.max(Math.abs((job.chunkX << 4) - job.x), Math.abs((job.chunkX << 4) + 15 - job.x));
            int farZ = Math.max(Math.abs((job.chunkZ << 4) - job.z), Math.abs((job.chunkZ << 4) + 15 - job.z));
            if (dx * dx + dz * dz <= (long) job.radius * job.radius
                    && dx * dx + dz * dz < (long) (job.band + 4) * (job.band + 4)
                    && (long) farX * farX + (long) farZ * farZ >= (long) job.band * job.band) break;
            moveChunkCursor(job);
        }
        if (job.chunkX > job.maxX()) {
            finishPhase(job);
            return false;
        }
        if (loadedChunk != null && loadedJob == job
                && loadedChunk.getX() == job.chunkX && loadedChunk.getZ() == job.chunkZ) return true;
        if (loading) {
            if (ticks - requestTick > 200) {
                plugin.getLogger().warning("Timed out loading chunk for job #" + job.id + "; will retry.");
                loading = false;
                requestGeneration++;
            }
            return false;
        }
        if (world.isChunkLoaded(job.chunkX, job.chunkZ)) {
            releaseChunk();
            loadedJob = job;
            loadedChunk = world.getChunkAt(job.chunkX, job.chunkZ);
            loadedChunk.addPluginChunkTicket(plugin);
            return true;
        }
        // Never generate new chunks merely because an explosive was used.
        if (!world.isChunkGenerated(job.chunkX, job.chunkZ)) {
            moveChunkCursor(job);
            return false;
        }
        loading = true;
        requestTick = ticks;
        long generation = ++requestGeneration;
        int targetX = job.chunkX, targetZ = job.chunkZ;
        world.getChunkAtAsync(targetX, targetZ, false).whenComplete((chunk, ex) ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (generation != requestGeneration || !jobs.containsKey(job.id)) return;
                loading = false;
                if (ex != null || chunk == null) {
                    plugin.getLogger().warning("Skipping unreadable chunk " + targetX + "," + targetZ
                            + " in job #" + job.id);
                    moveChunkCursor(job);
                    return;
                }
                releaseChunk();
                loadedJob = job;
                loadedChunk = chunk;
                chunk.addPluginChunkTicket(plugin);
            })
        );
        return false;
    }

    private void moveChunkCursor(Job job) {
        job.column = 0;
        job.nextY = Integer.MIN_VALUE;
        job.chunkZ++;
        if (job.chunkZ > job.maxZ()) {
            job.chunkZ = job.minZ();
            job.chunkX++;
        }
        dirty = true;
    }

    private void advanceChunk(Job job) {
        releaseChunk();
        moveChunkCursor(job);
        dirty = true;
    }

    private void finishPhase(Job job) {
        releaseChunk();
        if (job.band + 4 <= job.radius) {
            job.band += 4;
            job.chunkX = job.minX(); job.chunkZ = job.minZ();
            job.column = 0;
            job.nextY = Integer.MIN_VALUE;
            dirty = true;
            save();
            return;
        }
        if (job.phase == 0 && plugin.getConfig().getBoolean("fallout.convert-sand-to-glass", true)) {
            job.phase = 1;
            job.band = 0;
            job.chunkX = job.minX(); job.chunkZ = job.minZ();
            job.column = 0;
            job.nextY = Integer.MIN_VALUE;
            dirty = true;
            save();
            return;
        }
        jobs.remove(job.id);
        dirty = true;
        save();
        World world = Bukkit.getWorld(job.worldId);
        if (world != null) fallout.finishExplosion(world, job);
        plugin.getLogger().info("Completed " + job.label() + " #" + job.id + ": "
                + job.removed + " blocks removed, " + job.glassed + " sand blocks vitrified.");
    }

    private void releaseChunk() {
        if (loadedChunk != null) {
            try { loadedChunk.removePluginChunkTicket(plugin); }
            catch (IllegalStateException ignored) { }
        }
        loadedChunk = null;
        loadedJob = null;
    }

    private void damageEntities(Job job, World world) {
        Location center = job.center(world);
        double baseDamage = plugin.getConfig().getDouble(settings(job.kind) + ".damage", 22.0);
        int count = 0, max = plugin.getConfig().getInt("explosions.max-blast-entities", 250);
        for (Entity entity : world.getNearbyEntities(center, job.visual, Math.min(job.visual, 65), job.visual)) {
            if (++count > max) break;
            if (!(entity instanceof LivingEntity living)) continue;
            if (plugin.isProtected(world, entity.getLocation().getBlockX(),
                    entity.getLocation().getBlockY(), entity.getLocation().getBlockZ())) continue;
            Vector delta = entity.getLocation().toVector().subtract(center.toVector());
            double distance = delta.length();
            if (distance >= job.visual) continue;
            double strength = Math.max(0, 1 - distance / job.visual);
            if (strength < 0.03) continue;
            living.damage(baseDamage * strength);
            if (!entity.isValid()) continue;
            if (distance < 0.1) delta = new Vector(0, 1, 0);
            entity.setVelocity(delta.normalize().multiply(Math.min(2.0, 1.5 * strength)).setY(Math.min(1.0, 0.5 + strength)));
        }
    }

    private void visuals(Job job, World world) {
        if (!plugin.getConfig().getBoolean("visuals.enabled", true)) return;
        new BlastEffects(plugin, job, world).start();
    }

    public List<Job> activeJobs() { return new ArrayList<>(jobs.values()); }
    public boolean isPaused() { return paused; }
    public double lastProcessingMs() { return lastProcessingMs; }

    public boolean cancel(int jobId) {
        Job j = jobs.remove(jobId);
        if (j == null) return false;
        if (loadedJob == j) releaseChunk();
        requestGeneration++;
        loading = false;
        dirty = true;
        save();
        return true;
    }

    public String benchmark() {
        // Analytic lower-bound estimate for a flat solid world, without accessing chunks.
        int superN = estimate(32, 15);
        int formN = estimate(100, 25);
        return "Flat-world crater estimates: Super TNT ~" + superN + " blocks; Formidibomb ~" + formN
                + " blocks. Actual processing depends on terrain and hardware.";
    }

    private int estimate(int radius, int depth) {
        long sum = 0;
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            int d2 = dx * dx + dz * dz;
            if (d2 <= radius * radius) sum += (int) Math.ceil(depth * (1 - (double) d2 / (radius * radius)));
        }
        return (int) sum;
    }

    public void saveIfDirty() { if (dirty) save(); }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next-id", nextId);
        for (Job j : jobs.values()) {
            String p = "jobs." + j.id + ".";
            yaml.set(p + "kind", j.kind.id);
            yaml.set(p + "world", j.worldId.toString());
            yaml.set(p + "owner", j.owner == null ? "" : j.owner.toString());
            yaml.set(p + "x", j.x); yaml.set(p + "y", j.y); yaml.set(p + "z", j.z);
            yaml.set(p + "radius", j.radius); yaml.set(p + "depth", j.depth); yaml.set(p + "visual", j.visual);
            yaml.set(p + "chunkX", j.chunkX); yaml.set(p + "chunkZ", j.chunkZ);
            yaml.set(p + "column", j.column); yaml.set(p + "nextY", j.nextY);
            yaml.set(p + "phase", j.phase);
            yaml.set(p + "band", j.band);
            yaml.set(p + "removed", j.removed); yaml.set(p + "glassed", j.glassed);
        }
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            Files.writeString(temp.toPath(), yaml.saveToString());
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
            journalHealthy = true;
        } catch (IOException e) {
            journalHealthy = false;
            dirty = true;
            plugin.getLogger().severe("Could not save explosion job journal: " + e.getMessage());
        }
    }

    public void restore() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        nextId = Math.max(1, yaml.getInt("next-id", 1));
        ConfigurationSection section = yaml.getConfigurationSection("jobs");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            try {
                String p = "jobs." + id + ".";
                Job j = new Job();
                j.id = Integer.parseInt(id);
                j.kind = CustomItems.Kind.parse(yaml.getString(p + "kind", ""));
                if (j.kind != CustomItems.Kind.SUPER_TNT && j.kind != CustomItems.Kind.FORMIDIBOMB) continue;
                j.worldId = UUID.fromString(yaml.getString(p + "world", ""));
                if (Bukkit.getWorld(j.worldId) == null) {
                    plugin.getLogger().warning("Skipping job " + j.id + " because world is unavailable.");
                    continue;
                }
                String owner = yaml.getString(p + "owner", "");
                j.owner = owner.isBlank() ? null : UUID.fromString(owner);
                j.x = yaml.getInt(p + "x"); j.y = yaml.getInt(p + "y"); j.z = yaml.getInt(p + "z");
                j.radius = yaml.getInt(p + "radius"); j.depth = yaml.getInt(p + "depth");
                j.visual = Math.max(j.radius, Math.min(256, yaml.getInt(p + "visual")));
                if (j.radius < 4 || j.radius > 160 || j.depth < 1 || j.depth > 48) continue;
                j.chunkX = yaml.getInt(p + "chunkX", j.minX());
                j.chunkZ = yaml.getInt(p + "chunkZ", j.minZ());
                j.column = Math.max(0, Math.min(256, yaml.getInt(p + "column", 0)));
                j.nextY = yaml.getInt(p + "nextY", Integer.MIN_VALUE);
                j.phase = yaml.getInt(p + "phase", 0);
                j.band = Math.max(0, Math.min(j.radius, yaml.getInt(p + "band", 0)));
                j.removed = yaml.getLong(p + "removed", 0);
                j.glassed = yaml.getLong(p + "glassed", 0);
                jobs.put(j.id, j);
                nextId = Math.max(nextId, j.id + 1);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Invalid explosion job " + id + ": " + ex.getMessage());
            }
        }
        if (!jobs.isEmpty()) plugin.getLogger().info("Resumed " + jobs.size() + " queued crater jobs.");
    }

    public void shutdown() {
        releaseChunk();
        requestGeneration++;
        loading = false;
        save();
    }
}
