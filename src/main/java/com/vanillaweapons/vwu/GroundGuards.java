package com.vanillaweapons.vwu;

import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Beacon;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/** Persistent ground guards; captured gear has one authoritative serialized bag. */
final class GroundGuards implements Listener {
    private final VanillaWeaponsPlugin plugin;
    private final CommandBeacons beacons;
    private final NamespacedKey homeKey, bagKey, releasedKey, handKey, defenderKey;
    private final Map<UUID, Mob> loaded = new HashMap<>();
    private record Credit(Location home, long until) {}
    private final Map<UUID, Credit> credits = new HashMap<>();
    private int ticks;

    GroundGuards(VanillaWeaponsPlugin plugin, CommandBeacons beacons) {
        this.plugin = plugin; this.beacons = beacons;
        homeKey = key("guard_home"); bagKey = key("husk_inventory"); releasedKey = key("husk_released");
        handKey = key("husk_hand_slot"); defenderKey = key("beacon_defender");
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) adopt(entity);
        });
    }
    private NamespacedKey key(String value) { return new NamespacedKey(plugin, value); }
    private boolean husk(Entity entity) { return entity.getPersistentDataContainer().has(bagKey, PersistentDataType.BYTE_ARRAY); }
    private boolean released(Entity entity) { return entity.getPersistentDataContainer().has(releasedKey, PersistentDataType.BYTE); }
    private String encode(Location home) { return home.getWorld().getUID() + ":" + home.getBlockX() + ":" + home.getBlockY() + ":" + home.getBlockZ(); }
    private Location origin(Entity entity) {
        String text = entity.getPersistentDataContainer().get(homeKey, PersistentDataType.STRING);
        if (text == null) return null;
        try {
            String[] fields = text.split(":"); World world = Bukkit.getWorld(UUID.fromString(fields[0]));
            return world == null ? null : new Location(world, Integer.parseInt(fields[1]), Integer.parseInt(fields[2]), Integer.parseInt(fields[3]));
        } catch (RuntimeException bad) { return null; }
    }
    Location homeOf(Entity entity) {
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) entity = shooter;
        if (!(entity instanceof Mob)) return null;
        return released(entity) ? null : origin(entity);
    }
    void track(Mob mob, Location home) {
        mob.getPersistentDataContainer().set(homeKey, PersistentDataType.STRING, encode(home));
        mob.getPersistentDataContainer().set(defenderKey, PersistentDataType.BYTE, (byte) 1);
        mob.setPersistent(true); mob.setRemoveWhenFarAway(false); mob.setCanPickupItems(false);
        loaded.put(mob.getUniqueId(), mob);
    }
    private void adopt(Entity entity) {
        if (!(entity instanceof Mob mob) || origin(entity) == null) return;
        loaded.put(mob.getUniqueId(), mob);
        mob.setPersistent(true); mob.setRemoveWhenFarAway(false);
        if (mob instanceof Zombie zombie && husk(mob)) zombie.setShouldBurnInDay(false);
        if (mob instanceof WitherSkeleton skeleton && !released(mob)) beacons.adoptSkeleton(skeleton, origin(mob));
    }
    @EventHandler public void load(EntitiesLoadEvent event) { for (Entity entity : event.getEntities()) adopt(entity); }
    private ItemStack[] bag(Mob mob) {
        byte[] bytes = mob.getPersistentDataContainer().get(bagKey, PersistentDataType.BYTE_ARRAY);
        return bytes == null ? new ItemStack[41] : ItemStack.deserializeItemsFromBytes(bytes);
    }
    private void save(Mob mob, ItemStack[] bag) { mob.getPersistentDataContainer().set(bagKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(bag)); }
    private ItemStack copy(ItemStack stack) { return stack == null || stack.getType().isAir() ? null : stack.clone(); }
    private void sync(Mob mob) {
        if (!husk(mob)) return;
        ItemStack[] bag = bag(mob); EntityEquipment gear = mob.getEquipment();
        int hand = mob.getPersistentDataContainer().getOrDefault(handKey, PersistentDataType.INTEGER, 0);
        bag[hand] = copy(gear.getItemInMainHand()); bag[40] = copy(gear.getItemInOffHand());
        bag[36] = copy(gear.getBoots()); bag[37] = copy(gear.getLeggings()); bag[38] = copy(gear.getChestplate());
        // Slot 39 holds the original helmet; the displayed head is decorative.
        save(mob, bag);
    }
    Zombie create(Player player, Location home) {
        ItemStack[] inventory = Arrays.stream(player.getInventory().getContents()).map(this::copy).toArray(ItemStack[]::new);
        if (inventory.length < 41) throw new IllegalStateException("Unsupported player inventory layout: " + inventory.length);
        byte[] serialized = ItemStack.serializeItemsAsBytes(inventory);
        int hand = player.getInventory().getHeldItemSlot();
        ItemStack head = new ItemStack(Material.PLAYER_HEAD); SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(player.getUniqueId())); head.setItemMeta(meta);
        Zombie zombie = player.getWorld().spawn(player.getLocation(), Zombie.class, entity -> {
            entity.setAdult(); entity.setShouldBurnInDay(false); entity.setCanBreakDoors(false);
            entity.setPersistent(true); entity.setRemoveWhenFarAway(false); entity.setCanPickupItems(false);
            entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).setBaseValue(40); entity.setHealth(40);
            entity.getPersistentDataContainer().set(bagKey, PersistentDataType.BYTE_ARRAY, serialized);
            entity.getPersistentDataContainer().set(handKey, PersistentDataType.INTEGER, hand);
            EntityEquipment gear = entity.getEquipment();
            gear.setItemInMainHand(copy(inventory[hand])); gear.setItemInOffHand(copy(inventory[40]));
            gear.setBoots(copy(inventory[36])); gear.setLeggings(copy(inventory[37])); gear.setChestplate(copy(inventory[38])); gear.setHelmet(head);
            gear.setItemInMainHandDropChance(0); gear.setItemInOffHandDropChance(0);
            gear.setBootsDropChance(0); gear.setLeggingsDropChance(0); gear.setChestplateDropChance(0); gear.setHelmetDropChance(0);
            entity.customName(Component.text(player.getName() + "'s Husk")); entity.setCustomNameVisible(true);
        });
        if (!zombie.isValid()) return null; // Spawn protection/plugin cancellation: keep ordinary death handling.
        track(zombie, home);
        return zombie;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void credited(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Location home = beacons.guardHome(event.getDamager());
        if (home != null) credits.put(player.getUniqueId(), new Credit(home, System.currentTimeMillis() + 15000));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void capture(PlayerDeathEvent event) {
        Player player = event.getEntity(); Location home = null;
        Entity cause = event.getDamageSource().getCausingEntity();
        if (cause == null) cause = event.getDamageSource().getDirectEntity();
        if (cause != null) home = beacons.guardHome(cause);
        EntityDamageEvent damage = player.getLastDamageCause();
        if (home == null && damage instanceof EntityDamageByEntityEvent direct) home = beacons.guardHome(direct.getDamager());
        if (home == null && damage != null && damage.getCause() == EntityDamageEvent.DamageCause.WITHER) {
            Credit credit = credits.get(player.getUniqueId());
            if (credit != null && credit.until() >= System.currentTimeMillis()) home = credit.home();
        }
        credits.remove(player.getUniqueId());
        if (home == null || !home.getWorld().isChunkLoaded(home.getBlockX() >> 4, home.getBlockZ() >> 4) || !beacons.capturing(beacons.beacon(home))) return;
        if (plugin.isProtected(player.getWorld(), player.getLocation().getBlockX(), player.getLocation().getBlockY(), player.getLocation().getBlockZ())) return;
        Zombie zombie;
        try { zombie = create(player, home); }
        catch (RuntimeException failure) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "Husk creation failed; preserving ordinary player death drops", failure); return; }
        if (zombie == null) return;
        // Transfer only after a valid inventory-bearing entity exists.
        event.getDrops().clear(); event.getItemsToKeep().clear(); event.setKeepInventory(false);
        player.getInventory().clear(); player.getInventory().setArmorContents(new ItemStack[4]); player.getInventory().setItemInOffHand(null);
        zombie.getWorld().spawnParticle(Particle.SOUL, zombie.getLocation().add(0, 1, 0), 40, 0.5, 1, 0.5, 0.03);
        zombie.getWorld().playSound(zombie.getLocation(), Sound.ENTITY_ZOMBIE_CONVERTED_TO_DROWNED, 1, 0.65f);
    }
    private void release(Mob mob) {
        if (!husk(mob) || released(mob)) return;
        sync(mob); mob.setTarget(null); mob.getPathfinder().stopPathfinding(); mob.setAI(true);
        mob.getPersistentDataContainer().set(releasedKey, PersistentDataType.BYTE, (byte) 1);
        mob.getPersistentDataContainer().remove(defenderKey);
    }
    void release(Location home) {
        for (Mob mob : loaded.values()) if (mob.isValid() && home.equals(origin(mob))) release(mob);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void death(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !husk(mob)) return;
        sync(mob); ItemStack[] inventory = bag(mob);
        event.getDrops().clear();
        for (ItemStack stack : inventory) if (stack != null && !stack.getType().isAir()) event.getDrops().add(stack);
        mob.getPersistentDataContainer().remove(bagKey); loaded.remove(mob.getUniqueId());
    }
    @EventHandler(ignoreCancelled = true) public void transform(EntityTransformEvent event) {
        if (husk(event.getEntity())) event.setCancelled(true); // Preserve the inventory owner through drowning etc.
    }
    @EventHandler(ignoreCancelled = true) public void target(EntityTargetLivingEntityEvent event) {
        Location home = homeOf(event.getEntity()); if (home == null || !loaded.containsKey(event.getEntity().getUniqueId())) return;
        if (event.getTarget() == null) return;
        Beacon beacon = beacons.beacon(home);
        if (beacon == null || !beacons.combatEnemy(event.getTarget(), beacon)) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void combat(EntityDamageByEntityEvent event) {
        Location victimHome = homeOf(event.getEntity());
        Entity attacker = event.getDamager(); if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) attacker = shooter;
        if (victimHome != null && loaded.containsKey(event.getEntity().getUniqueId()) && attacker instanceof Player player) beacons.alert(beacons.beacon(victimHome), player);
        Location sourceHome = homeOf(event.getDamager());
        if (sourceHome != null && loaded.containsKey(attacker.getUniqueId()) && (!(event.getEntity() instanceof LivingEntity living) || !beacons.combatEnemy(living, beacons.beacon(sourceHome)))) event.setCancelled(true);
    }
    private void armTotem(Mob mob) {
        if (!husk(mob) || !mob.getEquipment().getItemInOffHand().getType().isAir()) return;
        ItemStack[] inventory = bag(mob);
        for (int slot = 0; slot < 36; slot++) if (inventory[slot] != null && inventory[slot].getType() == Material.TOTEM_OF_UNDYING
                && slot != mob.getPersistentDataContainer().getOrDefault(handKey, PersistentDataType.INTEGER, 0)) {
            ItemStack totem = inventory[slot].asOne(); inventory[slot].subtract(1); inventory[40] = totem;
            save(mob, inventory); mob.getEquipment().setItemInOffHand(totem); return;
        }
    }
    void tick() {
        ticks += 5; if (ticks % 20 != 0) return;
        credits.values().removeIf(credit -> credit.until() < System.currentTimeMillis());
        var iterator = loaded.values().iterator();
        while (iterator.hasNext()) {
            Mob mob = iterator.next(); if (!mob.isValid()) { iterator.remove(); continue; }
            if (husk(mob)) { sync(mob); armTotem(mob); }
            if (released(mob)) continue;
            Location home = origin(mob); if (home == null) continue;
            if (!home.getWorld().isChunkLoaded(home.getBlockX() >> 4, home.getBlockZ() >> 4)) { mob.setTarget(null); mob.getPathfinder().stopPathfinding(); continue; }
            Beacon beacon = beacons.beacon(home);
            if (!beacons.guardActive(beacon) || husk(mob) && !beacons.capturing(beacon)) {
                if (husk(mob)) release(mob); else mob.remove(); continue;
            }
            LivingEntity target = beacons.combatTarget(beacon, mob, true);
            mob.setTarget(target);
            if (target == null && ticks % 80 == 0) {
                double angle = (ticks * 0.004 + Math.floorMod(mob.getUniqueId().hashCode(), 628) / 100.0);
                Location destination = home.clone().add(Math.cos(angle) * 9 + 0.5, 0, Math.sin(angle) * 9 + 0.5);
                if (destination.getWorld().isChunkLoaded(destination.getBlockX() >> 4, destination.getBlockZ() >> 4)) {
                    destination.setY(destination.getWorld().getHighestBlockYAt(destination) + 1);
                    mob.getPathfinder().moveTo(destination, 0.85);
                }
            }
            mob.getWorld().spawnParticle(Particle.SOUL, mob.getLocation().add(0, 1, 0), 3, 0.3, 0.5, 0.3, 0.01);
        }
    }
    void shutdown() { for (Mob mob : loaded.values()) if (mob.isValid()) sync(mob); loaded.clear(); credits.clear(); }
}
