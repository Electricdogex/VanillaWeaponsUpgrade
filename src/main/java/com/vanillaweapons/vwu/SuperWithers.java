package com.vanillaweapons.vwu;

import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Beacon;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

final class SuperWithers implements Listener {
    private final VanillaWeaponsPlugin plugin;
    private final CommandBeacons beacons;
    private final NamespacedKey marker;
    private final Map<UUID, Guard> guards = new HashMap<>();
    private final Map<UUID, Guard> shots = new HashMap<>();
    private int ticks;
    private final org.bukkit.scheduler.BukkitTask movementTask;
    private final Map<UUID, Input> inputs = new HashMap<>();
    private static final class Guard {
        final Wither mob; final Location home;
        long healAt; int healLeft, fireAt; EnderCrystal crystal; UUID alarm;
        Vector movement = new Vector();
        int tractorUntil, respondAt;
        final net.kyori.adventure.bossbar.BossBar health = net.kyori.adventure.bossbar.BossBar.bossBar(Component.text("SuperWither"), 1f, net.kyori.adventure.bossbar.BossBar.Color.PURPLE, net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
        final Set<Player> viewers = new HashSet<>();
        Guard(Wither mob, Location home) { this.mob = mob; this.home = home; }
    }
    SuperWithers(VanillaWeaponsPlugin plugin, CommandBeacons beacons) {
        this.plugin = plugin; this.beacons = beacons; marker = new NamespacedKey(plugin, "super_wither");
        movementTask = Bukkit.getScheduler().runTaskTimer(plugin, this::move, 1, 1);
    }
    private Guard guard(Entity entity) {
        if (shots.containsKey(entity.getUniqueId())) return shots.get(entity.getUniqueId());
        if (entity instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Entity shooter) entity = shooter;
        return guards.get(entity.getUniqueId());
    }
    Location homeOf(Entity entity) { Guard guard = guard(entity); return guard == null ? null : guard.home.clone(); }
    private boolean hostile(Player player, Guard guard) {
        Beacon beacon = beacons.beacon(guard.home);
        Location at = player.getLocation();
        return beacons.possessing(beacon) && beacons.enemy(player, beacon);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void built(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Wither wither) || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.BUILD_WITHER || guards.size() >= 8) return;
        Location home = beacons.beaconLocations().stream().filter(location -> location.getWorld().equals(wither.getWorld())
                && beacons.inRange(wither.getLocation(), beacons.beacon(location)) && beacons.possessing(beacons.beacon(location))
                && guards.values().stream().filter(guard -> guard.home.equals(location)).count() < 2)
                .min(java.util.Comparator.comparingDouble(location -> location.distanceSquared(wither.getLocation()))).orElse(null);
        if (home == null) return;
        wither.setInvulnerabilityTicks(0); wither.setAI(false); wither.setGravity(false); wither.setPersistent(false); wither.setCanTravelThroughPortals(false);
        wither.getAttribute(Attribute.MAX_HEALTH).setBaseValue(600); wither.setHealth(300);
        wither.customName(Component.text("SuperWither")); wither.setCustomNameVisible(true);
        wither.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        guards.put(wither.getUniqueId(), new Guard(wither, home.clone()));
        wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.6f, 0.6f);
    }
    void alarm(Location home, LivingEntity target) {
        for (Guard guard : guards.values()) if (guard.home.equals(home) && beacons.combatEnemy(target, beacons.beacon(home)) && !target.getUniqueId().equals(guard.alarm)) {
            guard.alarm = target.getUniqueId(); guard.respondAt = ticks + 40;
        }
    }
    private void remove(Guard guard) {
        for (Player viewer : guard.viewers) viewer.hideBossBar(guard.health);
        guard.viewers.clear();
        if (guard.crystal != null) guard.crystal.remove();
        if (guard.mob.isValid()) {
            guard.mob.getWorld().spawnParticle(Particle.SOUL, guard.mob.getLocation(), 40, 1, 1, 1, 0.06);
            guard.mob.eject(); guard.mob.remove();
        }
    }
    void evaporate(Location home) {
        var iterator = guards.values().iterator();
        while (iterator.hasNext()) { Guard guard = iterator.next(); if (guard.home.equals(home)) { remove(guard); iterator.remove(); } }
    }
    void shutdown() {
        movementTask.cancel(); inputs.clear();
        for (UUID id : shots.keySet()) { Entity projectile = Bukkit.getEntity(id); if (projectile != null) projectile.remove(); }
        shots.clear(); for (Guard guard : guards.values()) remove(guard); guards.clear();
    }
    @EventHandler(ignoreCancelled = true) public void ride(PlayerInteractEntityEvent event) {
        Guard guard = guard(event.getRightClicked()); if (guard == null || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        event.setCancelled(true); Beacon beacon = beacons.beacon(guard.home);
        if (beacons.possessing(beacon) && beacons.friendly(event.getPlayer(), beacon) && guard.mob.getPassengers().isEmpty()
                && guard.mob.addPassenger(event.getPlayer())) inputs.put(event.getPlayer().getUniqueId(), event.getPlayer().getCurrentInput());
    }
    @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent event) { inputs.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void input(org.bukkit.event.player.PlayerInputEvent event) {
        if (event.getPlayer().getVehicle() instanceof Wither wither && guards.containsKey(wither.getUniqueId())) inputs.put(event.getPlayer().getUniqueId(), event.getInput());
    }
    @EventHandler(ignoreCancelled = true) public void tractorKey(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        if (!(event.getPlayer().getVehicle() instanceof Wither wither)) return;
        Guard guard = guards.get(wither.getUniqueId()); if (guard == null) return;
        event.setCancelled(true);
        Beacon beacon = beacons.beacon(guard.home);
        if (beacon != null && beacons.friendly(event.getPlayer(), beacon)) {
            guard.tractorUntil = ticks < guard.tractorUntil ? 0 : ticks + 200;
            event.getPlayer().sendActionBar(Component.text(guard.tractorUntil > ticks ? "Tractor aura ON" : "Tractor aura OFF"));
        }
    }
    private boolean canHit(LivingEntity entity, Guard guard) {
        if (entity.getUniqueId().equals(guard.mob.getUniqueId()) || guards.containsKey(entity.getUniqueId()) || guard.mob.getPassengers().stream().anyMatch(passenger -> passenger.getUniqueId().equals(entity.getUniqueId()))) return false;
        if (entity instanceof ArmorStand) return false;
        Beacon beacon = beacons.beacon(guard.home);
        if (beacon == null || plugin.isProtected(entity.getWorld(), entity.getLocation().getBlockX(), entity.getLocation().getBlockY(), entity.getLocation().getBlockZ())) return false;
        if (entity instanceof Player player) return beacons.eligibleEnemy(player, beacon);
        if (entity.getPersistentDataContainer().has(new NamespacedKey(plugin, "beacon_defender"), PersistentDataType.BYTE)) return false;
        if (entity instanceof Tameable pet && pet.getOwner() != null) {
            Player owner = Bukkit.getPlayer(pet.getOwner().getUniqueId());
            if (owner != null && beacons.friendly(owner, beacon)) return false;
        }
        return true;
    }
    private void move() {
        for (Guard guard : guards.values()) {
            Wither mob = guard.mob; if (!mob.isValid()) continue;
            Vector movement = guard.movement.clone();
            Player rider = mob.getPassengers().stream().filter(Player.class::isInstance).map(Player.class::cast).findFirst().orElse(null);
            if (rider != null) {
                Beacon beacon = beacons.beacon(guard.home);
                if (beacon == null || !beacons.friendly(rider, beacon)) { mob.removePassenger(rider); continue; }
                Input input = inputs.getOrDefault(rider.getUniqueId(), rider.getCurrentInput());
                if (input.isSneak()) { mob.removePassenger(rider); inputs.remove(rider.getUniqueId()); continue; }
                Vector direction = rider.getEyeLocation().getDirection();
                movement = direction.clone().multiply(input.isForward() ? 0.55 : input.isBackward() ? -0.3 : 0);
                Vector right = new Vector(-direction.getZ(), 0, direction.getX());
                if (right.lengthSquared() > 0.01) movement.add(right.normalize().multiply(input.isRight() ? 0.35 : input.isLeft() ? -0.35 : 0));
                if (movement.lengthSquared() > 0.55 * 0.55) movement.normalize().multiply(0.55);
                if (input.isJump()) shoot(guard, direction);
            }
            if (movement.lengthSquared() < 0.0001) continue;
            Location next = mob.getLocation().add(movement);
            if (next.getY() < mob.getWorld().getMinHeight() + 1 || next.getY() > mob.getWorld().getMaxHeight() - 5
                    || !beacons.inRange(next, beacons.beacon(guard.home))) continue;
            org.bukkit.util.BoundingBox box = mob.getBoundingBox().clone().shift(movement);
            boolean loaded = true;
            for (int chunkX = ((int) Math.floor(box.getMinX())) >> 4; chunkX <= ((int) Math.floor(box.getMaxX())) >> 4; chunkX++)
                for (int chunkZ = ((int) Math.floor(box.getMinZ())) >> 4; chunkZ <= ((int) Math.floor(box.getMaxZ())) >> 4; chunkZ++)
                    if (!mob.getWorld().isChunkLoaded(chunkX, chunkZ)) loaded = false;
            if (!loaded || mob.wouldCollideUsing(box)) continue;
            if (rider != null) next.setYaw(rider.getLocation().getYaw());
            else next.setDirection(movement).setPitch(0);
            mob.teleport(next, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void terrain(EntityChangeBlockEvent event) { if (guard(event.getEntity()) != null) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void crystalDamage(EntityDamageEvent event) {
        for (Guard guard : guards.values()) if (guard.crystal != null && guard.crystal.equals(event.getEntity())) { event.setCancelled(true); return; }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void explosion(EntityExplodeEvent event) {
        Guard guard = guard(event.getEntity());
        if (guard != null && beacons.friendlyBalls(beacons.beacon(guard.home))) { event.blockList().clear(); event.setCancelled(true); }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void hit(ProjectileHitEvent event) {
        Guard guard = guard(event.getEntity()); if (guard == null) return;
        if (event.getHitEntity() instanceof LivingEntity living && !canHit(living, guard)) { event.setCancelled(true); event.getEntity().remove(); }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void damage(EntityDamageByEntityEvent event) {
        Guard source = guard(event.getDamager());
        if (source != null && (!(event.getEntity() instanceof LivingEntity living) || !canHit(living, source))) event.setCancelled(true);
        Guard victim = guards.get(event.getEntity().getUniqueId());
        if (victim != null) {
            Entity attacker = event.getDamager();
            if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) attacker = shooter;
            if (attacker instanceof Player player) beacons.alert(beacons.beacon(victim.home), player);
        }
    }
    @EventHandler public void death(EntityDeathEvent event) {
        Guard guard = guards.remove(event.getEntity().getUniqueId());
        if (guard != null) { event.getDrops().clear(); event.setDroppedExp(0); for (Player viewer : guard.viewers) viewer.hideBossBar(guard.health); if (guard.crystal != null) guard.crystal.remove(); }
    }
    private void shoot(Guard guard, Vector direction) {
        if (ticks < guard.fireAt || direction.lengthSquared() < 0.01) return;
        guard.fireAt = ticks + 30;
        WitherSkull skull = guard.mob.launchProjectile(WitherSkull.class, direction.normalize().multiply(0.85));
        shots.put(skull.getUniqueId(), guard);
        boolean safe = beacons.friendlyBalls(beacons.beacon(guard.home));
        skull.setYield(safe ? 0 : 1); skull.setIsIncendiary(!safe); skull.setCharged(false); skull.setPersistent(false);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { shots.remove(skull.getUniqueId()); if (skull.isValid()) skull.remove(); }, 100);
    }
    private void pull(Guard guard, LivingEntity living) {
        Wither mob = guard.mob;
        Vector pull = mob.getLocation().add(0, 1, 0).toVector().subtract(living.getLocation().toVector()); double distance = pull.length();
        if (distance >= 14 || distance <= 0.2 || !mob.hasLineOfSight(living)) return;
        living.setVelocity(living.getVelocity().multiply(0.3).add(pull.normalize().multiply(distance > 3 ? 1.25 : 0.45)));
        mob.getWorld().spawnParticle(Particle.REVERSE_PORTAL, living.getLocation().add(0, 1, 0), 8, 0.3, 0.4, 0.3, 0.02);
        if (distance < 2.4 && ticks % 20 == 0) living.damage(2, mob);
    }
    void tick() {
        ticks += 5;
        var iterator = guards.values().iterator();
        while (iterator.hasNext()) {
            Guard guard = iterator.next(); Wither mob = guard.mob;
            if (!mob.isValid() || !beacons.possessing(beacons.beacon(guard.home))) { remove(guard); iterator.remove(); continue; }
            mob.setInvulnerabilityTicks(0);
            if (mob.getHealth() > 300) mob.setHealth(300);
            Beacon beacon = beacons.beacon(guard.home);
            Color tint = beacons.primaryColor(beacon);
            Component name = Component.text("SuperWither", net.kyori.adventure.text.format.TextColor.color(tint.asRGB()));
            mob.customName(name);
            if (mob.getBossBar() != null) mob.getBossBar().setVisible(false);
            guard.health.name(name.append(Component.text("  " + (int) Math.ceil(mob.getHealth()) + " / 300"))).progress((float) Math.max(0, Math.min(1, mob.getHealth() / 300)));
            for (Player viewer : new HashSet<>(guard.viewers)) if (!viewer.isOnline() || !viewer.getWorld().equals(mob.getWorld()) || viewer.getLocation().distanceSquared(mob.getLocation()) > 64 * 64) { viewer.hideBossBar(guard.health); guard.viewers.remove(viewer); }
            for (Player viewer : mob.getWorld().getPlayers()) if (viewer.getLocation().distanceSquared(mob.getLocation()) <= 64 * 64 && guard.viewers.add(viewer)) viewer.showBossBar(guard.health);
            mob.getWorld().spawnParticle(Particle.SOUL, mob.getLocation().add(0, 1, 0), 8, 1, 0.6, 1, 0.02);
            mob.getWorld().spawnParticle(Particle.REVERSE_PORTAL, mob.getLocation().add(0, 1, 0), 12, 1.3, 0.8, 1.3, 0.02);
            Entity alarm = guard.alarm == null ? null : Bukkit.getEntity(guard.alarm);
            LivingEntity target = alarm instanceof LivingEntity living && beacons.combatEnemy(living, beacon) ? living : beacons.combatTarget(beacon, mob, false);
            if (target == null) guard.alarm = null;
            if (ticks < guard.respondAt) target = null;
            Player rider = mob.getPassengers().stream().filter(Player.class::isInstance).map(Player.class::cast).findFirst().orElse(null);
            if (rider != null) {
                if (!beacons.friendly(rider, beacons.beacon(guard.home))) mob.removePassenger(rider);
                else {
                    Input input = rider.getCurrentInput(); Vector direction = rider.getEyeLocation().getDirection();
                    Vector movement = direction.clone().multiply(input.isForward() ? 0.65 : input.isBackward() ? -0.35 : 0);
                    Vector right = new Vector(-direction.getZ(), 0, direction.getX());
                    if (right.lengthSquared() > 0.01) movement.add(right.normalize().multiply(input.isRight() ? 0.4 : input.isLeft() ? -0.4 : 0));
                    guard.movement = movement;
                    if (input.isSneak()) mob.removePassenger(rider);
                    if (input.isJump()) shoot(guard, direction);
                }
            } else {
                double orbit = ticks * 0.009;
                Location destination = target == null ? guard.home.clone().add(0.5 + Math.cos(orbit) * 6, 0, 0.5 + Math.sin(orbit) * 6) : target.getLocation().add(0, 2, 0);
                if (target == null && mob.getWorld().isChunkLoaded(destination.getBlockX() >> 4, destination.getBlockZ() >> 4)) destination.setY(mob.getWorld().getHighestBlockYAt(destination) + 1.2);
                if (target == null) {
                    Player friend = mob.getWorld().getPlayers().stream().filter(player -> beacons.friendly(player, beacon) && beacons.nearFoundation(player.getLocation(), beacon) && !player.isInsideVehicle() && player.getLocation().distanceSquared(mob.getLocation()) < 12 * 12)
                            .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(mob.getLocation()))).orElse(null);
                    if (friend != null) { Vector look = friend.getLocation().getDirection().setY(0); if (look.lengthSquared() > 0.01) look.normalize().multiply(2); destination = friend.getLocation().add(look).add(0, 0.3, 0); }
                }
                Vector delta = destination.toVector().subtract(mob.getLocation().toVector());
                guard.movement = delta.lengthSquared() > 0.5 ? delta.normalize().multiply(0.24) : new Vector();
                if (target != null && mob.hasLineOfSight(target)) shoot(guard, target.getEyeLocation().toVector().subtract(mob.getEyeLocation().toVector()));
            }
            if (!beacons.inRange(mob.getLocation(), beacon)) { mob.eject(); mob.teleport(guard.home.clone().add(0.5, 5, 0.5)); }
            for (Entity entity : mob.getNearbyEntities(14, 14, 14)) if (entity instanceof LivingEntity living && canHit(living, guard)
                    && (rider != null && ticks < guard.tractorUntil || rider == null && living instanceof Player player && hostile(player, guard))) {
                pull(guard, living);
            }
            if (mob.getHealth() < 300 && guard.healLeft <= 0 && ticks >= guard.healAt) {
                guard.healAt = ticks + 400; guard.healLeft = 60;
                guard.crystal = mob.getWorld().spawn(guard.home.clone().add(0.5, 1.5, 0.5), EnderCrystal.class, crystal -> { crystal.setShowingBottom(false); crystal.setInvulnerable(true); crystal.setPersistent(false); });
            }
            if (guard.healLeft > 0) {
                if (guard.crystal == null || !guard.crystal.isValid()) { guard.healLeft = 0; guard.crystal = null; continue; }
                guard.healLeft -= 5; guard.crystal.setBeamTarget(mob.getLocation().add(0, 1, 0));
                if (ticks % 20 == 0) mob.setHealth(Math.min(300, mob.getHealth() + 10));
                if (guard.healLeft <= 0 || mob.getHealth() >= 300) { guard.healLeft = 0; guard.crystal.remove(); guard.crystal = null; }
            }
        }
    }
}
