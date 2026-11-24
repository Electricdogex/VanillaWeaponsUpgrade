package com.vanillaweapons.vwu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.*;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class CommandBeacons implements Listener {
    private final VanillaWeaponsPlugin plugin;
    private final CustomItems items;
    private final NamespacedKey ownerKey, saltKey, codeKey, allowKey, positiveKey, negativeKey, enabledKey, stormKey;
    private final Set<Location> tracked = new HashSet<>();
    private final Map<Location, List<BlockDisplay>> displays = new HashMap<>();
    private final Map<String, Long> authorized = new HashMap<>(), blocked = new HashMap<>();
    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<UUID, Weather> weather = new HashMap<>();
    private final Set<Chunk> held = new HashSet<>();
    private final Set<Location> anchors = new HashSet<>(), loadingAnchors = new HashSet<>();
    private final Set<Location> stormLocations = new HashSet<>();
    private final Map<Location, Integer> powers = new HashMap<>();
    private final java.io.File stateFile;
    private final NamespacedKey defenderKey;
    private final Map<Location, List<WitherSkeleton>> defenders = new HashMap<>();
    private final Map<UUID, Long> guardRecharge = new HashMap<>();
    private final Map<Location, Set<UUID>> intruders = new HashMap<>();
    private final Map<Location, Map<UUID, Long>> helpTargets = new HashMap<>();
    private final Map<UUID, EnderCrystal> rechargeBeams = new HashMap<>();
    private final Map<UUID, Long> repaired = new HashMap<>();
    private record Preview(Location beacon, long until) {}
    private final Map<UUID, Preview> previews = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final NamespacedKey colorKey, filterKey;
    private static final String[] COLORS = {"PURPLE", "RED", "BLUE", "CYAN", "LIME", "YELLOW", "ORANGE", "PINK", "WHITE",
            "LIGHT_BLUE", "MAGENTA", "LIGHT_GRAY", "GRAY", "BLACK", "BROWN", "GREEN"};
    private static final int[] RANGES = {32, 64, 96, 128, 192, 256};
    // Parked feature: retain its implementation and saved settings without exposing or running it.
    private static final boolean HOMEWORLD_AVAILABLE = false;
    private int ticks;
    private final SuperWithers superWithers;
    private final GroundGuards groundGuards;
    private final CommandRifts rifts = new CommandRifts();
    private static final PotionEffectType[] GOOD = {PotionEffectType.SPEED, PotionEffectType.HASTE,
            PotionEffectType.RESISTANCE, PotionEffectType.JUMP_BOOST, PotionEffectType.STRENGTH, PotionEffectType.REGENERATION,
            PotionEffectType.FIRE_RESISTANCE, PotionEffectType.WATER_BREATHING, PotionEffectType.NIGHT_VISION,
            PotionEffectType.SLOW_FALLING, null};
    private static final PotionEffectType[] BAD = {PotionEffectType.SLOWNESS, PotionEffectType.MINING_FATIGUE,
            PotionEffectType.WEAKNESS, PotionEffectType.HUNGER, PotionEffectType.NAUSEA};
    private record Weather(boolean storm, boolean thunder, int rainTime, int thunderTime, int clearTime) {}

    private static final class Menu implements InventoryHolder {
        final Location location;
        final UUID player;
        final String page;
        String digits = "", first = "";
        Inventory inventory;
        final Map<Integer, UUID> players = new HashMap<>();
        Menu(Location location, UUID player, String page) { this.location = location; this.player = player; this.page = page; }
        @Override public Inventory getInventory() { return inventory; }
    }

    public CommandBeacons(VanillaWeaponsPlugin plugin, CustomItems items) {
        this.plugin = plugin; this.items = items;
        superWithers = new SuperWithers(plugin, this);
        Bukkit.getPluginManager().registerEvents(superWithers, plugin);
        ownerKey = key("beacon_owner"); saltKey = key("beacon_salt"); codeKey = key("beacon_code");
        allowKey = key("beacon_allow"); positiveKey = key("beacon_positive"); negativeKey = key("beacon_negative");
        enabledKey = key("beacon_enabled"); stormKey = key("beacon_storm");
        colorKey = key("beacon_color"); filterKey = key("beacon_filter");
        defenderKey = key("beacon_defender");
        groundGuards = new GroundGuards(plugin, this);
        Bukkit.getPluginManager().registerEvents(groundGuards, plugin);
        stateFile = new java.io.File(plugin.getDataFolder(), "command-beacons.yml");
        for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks()) { discover(chunk); cleanParkedRifts(Arrays.asList(chunk.getEntities())); }
        var saved = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(stateFile);
        for (Object value : saved.getList("storms", List.of())) if (value instanceof Location location && location.getWorld() != null) {
            if (stormLocations.size() >= 32) break;
            stormLocations.add(location);
            if (anchors.size() < 32) anchors.add(location);
        }
        for (Object value : saved.getList("anchors", List.of())) if (value instanceof Location location && location.getWorld() != null && anchors.size() < 32) anchors.add(location);
        for (Location location : tracked) {
            Beacon beacon = beacon(location);
            if (beacon != null && flag(beacon, enabledKey) && !text(beacon, codeKey).isEmpty() && anchors.size() < 32) anchors.add(location);
        }
        for (Location location : new ArrayList<>(anchors)) loadAnchor(location);
    }
    private void loadAnchor(Location location) {
        if (!anchors.contains(location) || loadingAnchors.contains(location)) return;
        boolean ready = true;
        for (int x = (location.getBlockX() - 4) >> 4; x <= (location.getBlockX() + 4) >> 4; x++)
            for (int z = (location.getBlockZ() - 4) >> 4; z <= (location.getBlockZ() + 4) >> 4; z++)
                if (!location.getWorld().isChunkLoaded(x, z) || !held.contains(location.getWorld().getChunkAt(x, z))) ready = false;
        if (ready) { tracked.add(location); return; }
        loadingAnchors.add(location);
        int count = (((location.getBlockX() + 4) >> 4) - ((location.getBlockX() - 4) >> 4) + 1)
                * (((location.getBlockZ() + 4) >> 4) - ((location.getBlockZ() - 4) >> 4) + 1);
        var remaining = new java.util.concurrent.atomic.AtomicInteger(count);
        for (int chunkX = (location.getBlockX() - 4) >> 4; chunkX <= (location.getBlockX() + 4) >> 4; chunkX++)
            for (int chunkZ = (location.getBlockZ() - 4) >> 4; chunkZ <= (location.getBlockZ() + 4) >> 4; chunkZ++) {
                location.getWorld().getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, failure) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (anchors.contains(location) && chunk != null && (held.contains(chunk) || held.size() < 128)) {
                            chunk.addPluginChunkTicket(plugin); held.add(chunk);
                        }
                        if (remaining.decrementAndGet() == 0) {
                            loadingAnchors.remove(location);
                            if (anchors.contains(location)) tracked.add(location);
                        }
                    });
                });
            }
    }
    private NamespacedKey key(String name) { return new NamespacedKey(plugin, name); }
    private String text(Beacon beacon, NamespacedKey key) { return beacon.getPersistentDataContainer().getOrDefault(key, PersistentDataType.STRING, ""); }
    private boolean flag(Beacon beacon, NamespacedKey key) { return beacon.getPersistentDataContainer().getOrDefault(key, PersistentDataType.BYTE, (byte) 0) == 1; }
    private void flag(Beacon beacon, NamespacedKey key, boolean value) { beacon.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) (value ? 1 : 0)); }
    Beacon beacon(Location location) {
        World world = location.getWorld();
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return null;
        return location.getBlock().getState() instanceof Beacon beacon && !text(beacon, ownerKey).isEmpty() ? beacon : null;
    }
    public boolean protects(Block block) {
        if (!supporting(block).isEmpty()) return true;
        if (block.getType() == Material.BEACON && beacon(block.getLocation()) != null) return true;
        if (block.getY() <= block.getWorld().getMinHeight()) return false;
        Beacon below = beacon(block.getRelative(BlockFace.DOWN).getLocation());
        return below != null && flag(below, filterKey) && block.getType() == colorMaterial(below);
    }
    private Material colorMaterial(Beacon beacon) { return Material.valueOf(COLORS[selection(beacon, colorKey, COLORS.length)] + "_STAINED_GLASS"); }
    private boolean filter(Beacon beacon) {
        Block above = beacon.getBlock().getRelative(BlockFace.UP);
        return flag(beacon, filterKey) && above.getType() == colorMaterial(beacon);
    }
    private void discover(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities()) if (state instanceof Beacon beacon && !text(beacon, ownerKey).isEmpty()) tracked.add(beacon.getLocation());
    }
    @EventHandler public void loaded(ChunkLoadEvent event) { discover(event.getChunk()); }
    private void cleanParkedRifts(Collection<? extends Entity> entities) {
        if (HOMEWORLD_AVAILABLE) return;
        NamespacedKey marker = key("rift_display");
        for (Entity entity : entities) if (entity instanceof BlockDisplay && entity.getPersistentDataContainer().has(marker, PersistentDataType.BYTE)) entity.remove();
    }
    @EventHandler public void loadedEntities(EntitiesLoadEvent event) { cleanParkedRifts(event.getEntities()); }
    @EventHandler public void unloaded(ChunkUnloadEvent event) {
        for (Location location : new ArrayList<>(tracked)) if (location.getWorld().equals(event.getWorld())
                && location.getBlockX() >> 4 == event.getChunk().getX() && location.getBlockZ() >> 4 == event.getChunk().getZ()) {
            clearDisplays(location); clearGuards(location); tracked.remove(location);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void placing(BlockPlaceEvent event) {
        if (items.identify(event.getItemInHand()) != CustomItems.Kind.COMMAND_BEACON) return;
        Block block = event.getBlockPlaced();
        if (plugin.isProtected(block.getWorld(), block.getX(), block.getY(), block.getZ())) {
            event.setCancelled(true); event.getPlayer().sendMessage("Command Beacons are disabled here.");
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void placed(BlockPlaceEvent event) {
        if (items.identify(event.getItemInHand()) != CustomItems.Kind.COMMAND_BEACON) return;
        Beacon beacon = (Beacon) event.getBlockPlaced().getState();
        beacon.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, event.getPlayer().getUniqueId().toString());
        flag(beacon, enabledKey, false); flag(beacon, stormKey, false);
        beacon.update(); tracked.add(beacon.getLocation());
        event.getPlayer().sendActionBar(Component.text("Open your beacon to create its access code."));
        event.getBlock().getWorld().playSound(event.getBlock().getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1, 0.6f);
    }
    private boolean owner(Player player, Beacon beacon) { return player.getUniqueId().toString().equals(text(beacon, ownerKey)); }
    private boolean previewing(Player player, Beacon beacon) {
        Preview preview = previews.get(player.getUniqueId());
        return preview != null && preview.until() > System.currentTimeMillis() && preview.beacon().equals(beacon.getLocation());
    }
    private boolean generous(Beacon beacon) { return flag(beacon, key("beacon_generous")); }
    boolean friendly(Player player, Beacon beacon) {
        if (previewing(player, beacon)) return false;
        if (owner(player, beacon)) return true;
        return generous(beacon) ? !listed(beacon, true).contains(player.getUniqueId()) : allow(beacon).contains(player.getUniqueId());
    }
    private List<Beacon> supporting(Block block) {
        if (!Tag.BEACON_BASE_BLOCKS.isTagged(block.getType())) return List.of();
        List<Beacon> result = new ArrayList<>();
        for (Location location : tracked) {
            int depth = location.getBlockY() - block.getY();
            if (!location.getWorld().equals(block.getWorld()) || depth < 1 || depth > 4
                    || Math.abs(location.getBlockX() - block.getX()) > depth || Math.abs(location.getBlockZ() - block.getZ()) > depth) continue;
            Beacon beacon = beacon(location); if (beacon != null) result.add(beacon);
        }
        return result;
    }
    int tier(Beacon beacon) {
        World world = beacon.getWorld(); Block center = beacon.getBlock();
        for (int blockY = center.getY() + 1; blockY < world.getMaxHeight(); blockY++) {
            Material material = world.getBlockAt(center.getX(), blockY, center.getZ()).getType();
            if (material == Material.TINTED_GLASS || material.isOccluding() && material != Material.BEDROCK) return 0;
        }
        int tier = 0;
        for (int depth = 1; depth <= 4; depth++) {
            if (center.getY() - depth < world.getMinHeight()) break;
            for (int offsetX = -depth; offsetX <= depth; offsetX++) for (int offsetZ = -depth; offsetZ <= depth; offsetZ++) {
                int blockX = center.getX() + offsetX, blockZ = center.getZ() + offsetZ;
                if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)
                        || !Tag.BEACON_BASE_BLOCKS.isTagged(world.getBlockAt(blockX, center.getY() - depth, blockZ).getType())) return tier;
            }
            tier = depth;
        }
        return tier;
    }
    private String token(Player player, Location location) { return player.getUniqueId() + ":" + location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ(); }
    private boolean access(Player player, Beacon beacon) {
        return !text(beacon, codeKey).isEmpty() && (owner(player, beacon)
                || authorized.getOrDefault(token(player, beacon.getLocation()), 0L) > System.currentTimeMillis());
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.getHand() != EquipmentSlot.HAND) return;
        Beacon beacon = beacon(event.getClickedBlock().getLocation());
        if (beacon == null && event.getPlayer().isSneaking()) {
            List<Beacon> supported = supporting(event.getClickedBlock());
            if (!supported.isEmpty()) beacon = supported.getFirst();
        }
        if (beacon == null) return;
        if (event.useInteractedBlock() == Event.Result.DENY) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        open(player, beacon, access(player, beacon) ? "main" : owner(player, beacon) && text(beacon, codeKey).isEmpty() ? "create" : "pin");
    }
    private ItemStack button(Material material, String name) {
        ItemStack stack = new ItemStack(material); var meta = stack.getItemMeta();
        meta.displayName(Component.text(name)); meta.lore(null); stack.setItemMeta(meta); return stack;
    }
    private void open(Player player, Beacon beacon, String page) {
        Menu menu = new Menu(beacon.getLocation(), player.getUniqueId(), page);
        menu.inventory = Bukkit.createInventory(menu, 54, Component.text("Command Beacon — " + page));
        draw(menu, beacon);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Beacon current = beacon(menu.location);
            if (!player.isOnline() || current == null || !player.getWorld().equals(menu.location.getWorld())
                    || player.getLocation().distanceSquared(menu.location) > 64) return;
            player.openInventory(menu.inventory);
        });
    }
    private void draw(Menu menu, Beacon beacon) {
        Inventory inv = menu.inventory; inv.clear(); menu.players.clear();
        if (menu.page.equals("pin") || menu.page.equals("create")) {
            inv.setItem(4, button(Material.TRIPWIRE_HOOK, (menu.page.equals("create") ? (menu.first.isEmpty() ? "Create code: " : "Confirm code: ") : "Enter code: ") + "*".repeat(menu.digits.length())));
            for (int digit = 1; digit <= 9; digit++) inv.setItem(9 + digit, button(Material.PAPER, String.valueOf(digit)));
            inv.setItem(22, button(Material.PAPER, "0")); inv.setItem(30, button(Material.ARROW, "Backspace"));
            inv.setItem(32, button(Material.LIME_DYE, "Confirm")); return;
        }
        if (menu.page.equals("allow") || menu.page.equals("deny")) {
            boolean blacklist = menu.page.equals("deny");
            List<UUID> allowed = listed(beacon, blacklist);
            int slot = 0;
            Set<UUID> listed = new LinkedHashSet<>(allowed);
            for (Player player : beacon.getWorld().getPlayers()) listed.add(player.getUniqueId());
            for (UUID id : listed) {
                if (slot >= 45) break;
                String name = Bukkit.getOfflinePlayer(id).getName();
                inv.setItem(slot, button(allowed.contains(id) ? (blacklist ? Material.RED_DYE : Material.LIME_DYE) : Material.GRAY_DYE,
                        (allowed.contains(id) ? "Remove: " : blacklist ? "Blacklist: " : "Whitelist: ") + (name == null ? id.toString() : name)));
                menu.players.put(slot++, id);
            }
            inv.setItem(49, button(Material.ARROW, "Back")); return;
        }
        inv.setItem(4, button(Material.BEACON, "Power tier " + tier(beacon) + " / 4 — radius " + range(beacon) + " blocks"));
        inv.setItem(45, button(Material.COMPASS, "Owner: Range " + range(beacon) + " blocks" + (overclocked(beacon) ? " — rift charging / 350 when open" : " — left next / right previous")));
        if (HOMEWORLD_AVAILABLE) inv.setItem(46, button(Material.ECHO_SHARD, "Owner: Overclock " + (overclocked(beacon) ? "ON — rift-powered radius 350" : "OFF — open homeworld rift")));
        for (int channel = 0; channel < 3; channel++) {
            int good = channel(beacon, true, channel), bad = channel(beacon, false, channel);
            inv.setItem(10 + channel, button(Material.EMERALD, "Positive " + (channel + 1) + ": " + choiceName(good, GOOD) + " — left cycle / right OFF"));
            inv.setItem(19 + channel, button(Material.FERMENTED_SPIDER_EYE, "Negative " + (channel + 1) + ": " + choiceName(bad, BAD) + " — left cycle / right OFF"));
        }
        inv.setItem(14, button(flag(beacon, enabledKey) ? Material.LIME_DYE : Material.GRAY_DYE, flag(beacon, enabledKey) ? "Effects ON — disable before breaking" : "Effects OFF — click to activate"));
        inv.setItem(16, button(generous(beacon) ? Material.SUNFLOWER : Material.LIGHTNING_ROD, (generous(beacon) ? "Permasun: " : "Permastorm: ") + (flag(beacon, stormKey) ? "ON" : "OFF")));
        inv.setItem(33, button(Material.WITHER_SKELETON_SKULL, "Owner: Possess Withers — " + (generous(beacon) ? "Power only" : flag(beacon, key("possess_withers")) ? "ON" : "OFF")));
        inv.setItem(34, button(Material.FIRE_CHARGE, "Owner: Friendly wither balls: " + (friendlyBalls(beacon) ? "ON" : "OFF — destructive")));
        inv.setItem(36, button(Material.ZOMBIE_HEAD, "Owner: Possess killed players: " + (flag(beacon, key("possess_players")) ? "ON" : "OFF") + " — Power only"));
        inv.setItem(37, button(Material.SHIELD, "Owner: Help friendly players: " + (flag(beacon, key("help_friends")) ? "ON" : "OFF") + " — Power only"));
        inv.setItem(28, button(Material.PLAYER_HEAD, generous(beacon) ? "Owner: manage blacklist" : "Owner: manage whitelist"));
        inv.setItem(27, button(generous(beacon) ? Material.HONEY_BOTTLE : Material.NETHERITE_SWORD,
                "Owner mode: " + (generous(beacon) ? "GENEROSITY — positive except blacklist" : "POWER — negative except whitelist") + " — click to toggle"));
        inv.setItem(30, button(Material.TRIPWIRE_HOOK, "Owner: change access code"));
        inv.setItem(32, button(Material.BARRIER, "Close"));
        String[] labels = {"Center", "Northwest", "Northeast", "Southwest", "Southeast"};
        boolean migrated = false;
        for (int beam = 1; beam < 5; beam++) if (!beacon.getPersistentDataContainer().has(key("beacon_color_" + beam), PersistentDataType.INTEGER)) {
            beacon.getPersistentDataContainer().set(key("beacon_color_" + beam), PersistentDataType.INTEGER, selection(beacon, colorKey, COLORS.length)); migrated = true;
        }
        if (migrated) beacon.update();
        String[] beamColors = beamColors(beacon);
        for (int beam = 0; beam < 5; beam++) inv.setItem(38 + beam, button(Material.valueOf(beamColors[beam] + "_STAINED_GLASS"), labels[beam] + " beam: " + beamColors[beam] + " — click to cycle"));
        inv.setItem(35, button(Material.CARVED_PUMPKIN, "Owner: toggle outsider test (30s) — survival defenses CAN HURT YOU"));
    }
    private int selection(Beacon beacon, NamespacedKey key, int length) { return Math.floorMod(beacon.getPersistentDataContainer().getOrDefault(key, PersistentDataType.INTEGER, 0), length); }
    private NamespacedKey channelKey(boolean good, int channel) { return channel == 0 ? (good ? positiveKey : negativeKey) : key("beacon_" + (good ? "positive_" : "negative_") + channel); }
    private int channel(Beacon beacon, boolean good, int channel) {
        int choice = beacon.getPersistentDataContainer().getOrDefault(channelKey(good, channel), PersistentDataType.INTEGER, channel == 0 ? 0 : -1);
        return choice >= 0 && choice < (good ? GOOD.length : BAD.length) ? choice : -1;
    }
    private String choiceName(int index, PotionEffectType[] effects) { return index < 0 ? "OFF" : effects[index] == null ? "armor_healing" : effects[index].getKey().getKey(); }
    private void cycle(Beacon beacon, boolean good, int channel, boolean clear) {
        int next = -1;
        if (!clear) {
            next = channel(beacon, good, channel);
            for (int attempt = 0; attempt <= (good ? GOOD.length : BAD.length); attempt++) {
                next++; if (next >= (good ? GOOD.length : BAD.length)) next = -1;
                boolean duplicate = false;
                for (int other = 0; other < 3; other++) if (other != channel && next >= 0 && channel(beacon, good, other) == next) duplicate = true;
                if (!duplicate) break;
            }
        }
        beacon.getPersistentDataContainer().set(channelKey(good, channel), PersistentDataType.INTEGER, next);
    }
    private List<UUID> allow(Beacon beacon) {
        return listed(beacon, false);
    }
    private List<UUID> listed(Beacon beacon, boolean blacklist) {
        List<UUID> result = new ArrayList<>();
        for (String id : text(beacon, blacklist ? key("beacon_blacklist") : allowKey).split(",")) try { result.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) {}
        return result;
    }
    private String digest(String salt, String pin) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    @EventHandler public void drag(InventoryDragEvent event) { if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true); }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !menu.player.equals(player.getUniqueId())) return;
        Beacon beacon = beacon(menu.location);
        if (beacon == null || !player.getWorld().equals(menu.location.getWorld()) || player.getLocation().distanceSquared(menu.location) > 64) { player.closeInventory(); return; }
        int slot = event.getRawSlot(); if (slot < 0 || slot >= 54) return;
        String token = token(player, menu.location);
        if (menu.page.equals("pin") || menu.page.equals("create")) {
            if (blocked.getOrDefault(token, 0L) > System.currentTimeMillis()) { player.sendMessage("Too many attempts. Wait 30 seconds."); return; }
            if (slot >= 10 && slot <= 18 && menu.digits.length() < 4) menu.digits += slot - 9;
            else if (slot == 22 && menu.digits.length() < 4) menu.digits += "0";
            else if (slot == 30 && !menu.digits.isEmpty()) menu.digits = menu.digits.substring(0, menu.digits.length() - 1);
            else if (slot == 32 && menu.digits.length() == 4) {
                if (menu.page.equals("create") && owner(player, beacon)) {
                    if (menu.first.isEmpty()) { menu.first = menu.digits; menu.digits = ""; }
                    else if (menu.first.equals(menu.digits)) {
                        byte[] salt = new byte[16]; random.nextBytes(salt);
                        String encoded = HexFormat.of().formatHex(salt);
                        beacon.getPersistentDataContainer().set(saltKey, PersistentDataType.STRING, encoded);
                        beacon.getPersistentDataContainer().set(codeKey, PersistentDataType.STRING, digest(encoded, menu.digits));
                        beacon.update(); authorized.keySet().removeIf(value -> value.endsWith(":" + menu.location.getWorld().getUID() + ":" + menu.location.getBlockX() + ":" + menu.location.getBlockY() + ":" + menu.location.getBlockZ()));
                        open(player, beacon, "main"); return;
                    } else { menu.first = ""; menu.digits = ""; player.sendMessage("Codes did not match. Start again."); }
                } else if (menu.page.equals("pin")) {
                    boolean match = MessageDigest.isEqual(digest(text(beacon, saltKey), menu.digits).getBytes(StandardCharsets.UTF_8), text(beacon, codeKey).getBytes(StandardCharsets.UTF_8));
                    if (match) { authorized.put(token, System.currentTimeMillis() + 60000); failures.remove(token); open(player, beacon, "main"); return; }
                    int attempts = failures.merge(token, 1, Integer::sum);
                    if (attempts >= 5) { blocked.put(token, System.currentTimeMillis() + 30000); failures.remove(token); }
                    menu.digits = ""; player.sendMessage("Incorrect code.");
                }
            }
            draw(menu, beacon); return;
        }
        if (!access(player, beacon)) { open(player, beacon, "pin"); return; }
        if (menu.page.equals("allow") || menu.page.equals("deny")) {
            if (!owner(player, beacon)) return;
            if (slot == 49) { open(player, beacon, "main"); return; }
            UUID id = menu.players.get(slot);
            if (id != null) {
                boolean blacklist = menu.page.equals("deny");
                if (blacklist && id.toString().equals(text(beacon, ownerKey))) { player.sendMessage("The owner always receives positive effects and cannot be blacklisted."); return; }
                List<UUID> allowed = listed(beacon, blacklist);
                if (!allowed.remove(id) && allowed.size() < 45) allowed.add(id);
                beacon.getPersistentDataContainer().set(blacklist ? key("beacon_blacklist") : allowKey, PersistentDataType.STRING, String.join(",", allowed.stream().map(UUID::toString).toList()));
                beacon.update(); draw(menu, beacon);
            }
            return;
        }
        if (slot >= 10 && slot <= 12) cycle(beacon, true, slot - 10, event.isRightClick());
        if (slot >= 19 && slot <= 21) cycle(beacon, false, slot - 19, event.isRightClick());
        if (HOMEWORLD_AVAILABLE && slot == 46 && owner(player, beacon)) {
            if (!overclocked(beacon)) {
                long others = tracked.stream().filter(at -> !at.equals(menu.location)).map(this::beacon).filter(Objects::nonNull)
                        .filter(other -> overclocked(other) && flag(other, enabledKey)).count();
                if (others >= 2) { player.sendMessage("Two homeworld rifts are already reserved. Close one before overclocking another beacon."); return; }
                if (beacon.getY() + 30 >= beacon.getWorld().getMaxHeight()) { player.sendMessage("Overclock needs 30 blocks of height above the beacon."); return; }
                beacon.getPersistentDataContainer().set(key("rift_yaw"), PersistentDataType.FLOAT, player.getLocation().getYaw());
            }
            flag(beacon, key("beacon_overclock"), !overclocked(beacon));
        }
        if (slot == 45 && owner(player, beacon) && !overclocked(beacon)) {
            int current = range(beacon), index = 0;
            for (int i = 0; i < RANGES.length; i++) if (RANGES[i] == current) index = i;
            beacon.getPersistentDataContainer().set(key("beacon_range"), PersistentDataType.INTEGER, RANGES[Math.floorMod(index + (event.isRightClick() ? -1 : 1), RANGES.length)]);
        }
        if (slot == 14) {
            if (!flag(beacon, enabledKey) && overclocked(beacon) && tracked.stream().filter(at -> !at.equals(menu.location)).map(this::beacon).filter(Objects::nonNull).filter(other -> overclocked(other) && flag(other, enabledKey)).count() >= 2) {
                player.sendMessage("Two homeworld rifts are already reserved. Disable Overclock or close another rift."); return;
            }
            if (!flag(beacon, enabledKey) && !anchors.contains(menu.location) && anchors.size() >= 32) { player.sendMessage("The server limit of 32 active beacon zones has been reached."); return; }
            if (tier(beacon) == 0 && !flag(beacon, enabledKey)) { player.sendMessage("Build a complete beacon pyramid with a clear beam path first."); return; }
            flag(beacon, enabledKey, !flag(beacon, enabledKey));
            if (!flag(beacon, enabledKey)) flag(beacon, stormKey, false);
            beacon.getWorld().playSound(menu.location, flag(beacon, enabledKey) ? Sound.BLOCK_BEACON_ACTIVATE : Sound.BLOCK_BEACON_DEACTIVATE, 1, 0.6f);
        }
        if (slot == 16) {
            if (!flag(beacon, stormKey) && stormLocations.size() >= 32) {
                player.sendMessage("The server limit of 32 Permastorm beacons has been reached."); return;
            }
            if (!flag(beacon, stormKey) && (!flag(beacon, enabledKey) || tier(beacon) == 0)) {
                player.sendMessage("Activate the powered beacon before enabling Permastorm."); return;
            }
            flag(beacon, stormKey, !flag(beacon, stormKey)); player.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 0.7f);
        }
        if (slot == 27 && owner(player, beacon)) {
            flag(beacon, key("beacon_generous"), !generous(beacon));
            clearGuards(beacon.getLocation());
            superWithers.evaporate(beacon.getLocation());
            if (generous(beacon)) flag(beacon, key("possess_withers"), false);
        }
        if (slot == 34 && owner(player, beacon)) flag(beacon, key("destructive_skulls"), !flag(beacon, key("destructive_skulls")));
        if (slot == 36 && owner(player, beacon) && !generous(beacon)) {
            flag(beacon, key("possess_players"), !flag(beacon, key("possess_players")));
            if (!flag(beacon, key("possess_players"))) groundGuards.release(beacon.getLocation());
        }
        if (slot == 37 && owner(player, beacon) && !generous(beacon)) {
            flag(beacon, key("help_friends"), !flag(beacon, key("help_friends")));
            if (!flag(beacon, key("help_friends"))) helpTargets.remove(beacon.getLocation());
        }
        if (slot == 33 && owner(player, beacon) && !generous(beacon)) {
            flag(beacon, key("possess_withers"), !flag(beacon, key("possess_withers")));
            if (!flag(beacon, key("possess_withers"))) superWithers.evaporate(beacon.getLocation());
        }
        if (slot == 28 && owner(player, beacon)) { open(player, beacon, generous(beacon) ? "deny" : "allow"); return; }
        if (slot == 30 && owner(player, beacon)) { open(player, beacon, "create"); return; }
        if (slot == 35 && owner(player, beacon)) {
            if (previewing(player, beacon)) { previews.remove(player.getUniqueId()); player.sendActionBar(Component.text("Outsider test ended.")); }
            else {
                previews.put(player.getUniqueId(), new Preview(beacon.getLocation(), System.currentTimeMillis() + 30000));
                player.sendActionBar(Component.text("Outsider test: 30s — defenses can hurt you in survival."));
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Preview preview = previews.get(player.getUniqueId());
                    if (preview != null && preview.until() <= System.currentTimeMillis()) {
                        previews.remove(player.getUniqueId());
                        if (player.isOnline()) player.sendActionBar(Component.text("Outsider test ended."));
                    }
                }, 601);
            }
        }
        int colorTarget = slot >= 38 && slot <= 42 ? slot - 38 : -1;
        if (colorTarget > 0) {
            NamespacedKey targetKey = key("beacon_color_" + colorTarget);
            int current = beacon.getPersistentDataContainer().getOrDefault(targetKey, PersistentDataType.INTEGER, selection(beacon, colorKey, COLORS.length));
            beacon.getPersistentDataContainer().set(targetKey, PersistentDataType.INTEGER, Math.floorMod(current + 1, COLORS.length));
        }
        if (colorTarget == 0 || slot == 14 && flag(beacon, enabledKey)) {
            Block above = beacon.getBlock().getRelative(BlockFace.UP);
            if (above.getY() >= beacon.getWorld().getMaxHeight() || (!above.isEmpty() && !filter(beacon))) {
                if (slot == 14) flag(beacon, enabledKey, false);
                player.sendMessage("Leave the block immediately above the beacon empty for its beam-color filter."); return;
            }
            if (colorTarget == 0) beacon.getPersistentDataContainer().set(colorKey, PersistentDataType.INTEGER, (selection(beacon, colorKey, COLORS.length) + 1) % COLORS.length);
            above.setType(colorMaterial(beacon), false); flag(beacon, filterKey, true);
        }
        beacon.update();
        if (flag(beacon, enabledKey) && !text(beacon, codeKey).isEmpty()) { anchors.add(beacon.getLocation()); loadAnchor(beacon.getLocation()); }
        else anchors.remove(beacon.getLocation());
        if (flag(beacon, stormKey) && flag(beacon, enabledKey)) stormLocations.add(beacon.getLocation());
        else stormLocations.remove(beacon.getLocation());
        saveStorms(); draw(menu, beacon);
        if (slot == 32) Bukkit.getScheduler().runTask(plugin, () -> player.closeInventory());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breaking(BlockBreakEvent event) {
        List<Beacon> supported = supporting(event.getBlock());
        if (!supported.isEmpty()) {
            for (Beacon parent : supported) if (!access(event.getPlayer(), parent) || flag(parent, enabledKey) || flag(parent, stormKey)) {
                event.setCancelled(true);
                alert(parent, event.getPlayer());
                return;
            }
            return;
        }
        if (event.getBlock().getType() != Material.BEACON && protects(event.getBlock())) {
            event.setCancelled(true); event.getPlayer().sendMessage("Change the beacon color through its menu; this is its managed beam filter."); return;
        }
        Beacon beacon = beacon(event.getBlock().getLocation()); if (beacon == null) return;
        if (!access(event.getPlayer(), beacon) || flag(beacon, enabledKey) || flag(beacon, stormKey)) {
            event.setCancelled(true); alert(beacon, event.getPlayer()); return;
        }
        event.setDropItems(false);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        Beacon beacon = beacon(event.getBlock().getLocation()); if (beacon == null) return;
        tracked.remove(beacon.getLocation()); clearDisplays(beacon.getLocation()); clearGuards(beacon.getLocation());
        stormLocations.remove(beacon.getLocation()); anchors.remove(beacon.getLocation()); saveStorms();
        if (filter(beacon)) beacon.getBlock().getRelative(BlockFace.UP).setType(Material.AIR, false);
        authorized.remove(token(event.getPlayer(), beacon.getLocation()));
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) event.getBlock().getWorld().dropItemNaturally(beacon.getLocation(), items.create(CustomItems.Kind.COMMAND_BEACON, 1));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void explosion(EntityExplodeEvent event) { event.blockList().removeIf(this::protects); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void explosion(BlockExplodeEvent event) { event.blockList().removeIf(this::protects); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void piston(BlockPistonExtendEvent event) { if (event.getBlocks().stream().anyMatch(this::protects)) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void piston(BlockPistonRetractEvent event) { if (event.getBlocks().stream().anyMatch(this::protects)) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void vanilla(com.destroystokyo.paper.event.block.BeaconEffectEvent event) { if (protects(event.getBlock())) event.setCancelled(true); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        previews.remove(event.getPlayer().getUniqueId());
        String prefix = event.getPlayer().getUniqueId() + ":";
        authorized.keySet().removeIf(value -> value.startsWith(prefix));
    }
    public void tick() {
        ticks += 5;
        Set<UUID> storms = new HashSet<>();
        Set<Chunk> needed = new HashSet<>();
        for (Location location : new ArrayList<>(tracked)) {
            if (loadingAnchors.contains(location)) continue;
            if (anchors.contains(location) && !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) { loadAnchor(location); continue; }
            Beacon beacon = beacon(location);
            if (beacon == null) { clearDisplays(location); clearGuards(location); tracked.remove(location); anchors.remove(location); continue; }
            if (flag(beacon, enabledKey) && !text(beacon, codeKey).isEmpty() && anchors.size() < 32) anchors.add(location);
            if (!flag(beacon, enabledKey)) anchors.remove(location);
            int power;
            if (ticks % 20 == 0 || !powers.containsKey(location)) { power = tier(beacon); powers.put(location, power); }
            else power = powers.get(location);
            boolean active = flag(beacon, enabledKey) && !text(beacon, codeKey).isEmpty() && power > 0;
            if (!active) { clearGuards(location); animate(location, false); continue; }
            for (Player player : beacon.getWorld().getPlayers()) if (eligibleEnemy(player, beacon) && nearFoundation(player.getLocation(), beacon)) alert(beacon, player);
            if (flag(beacon, stormKey)) {
                storms.add(location.getWorld().getUID());
            }
            animate(location, true);
            if (ticks % 20 == 0) for (WitherSkeleton guard : defenders.getOrDefault(location, List.of())) if (guard.isValid()) {
                guard.getWorld().spawnParticle(Particle.SOUL, guard.getLocation().add(0, 1, 0), 6, 0.4, 0.7, 0.4, 0.02);
                guard.getWorld().spawnParticle(Particle.REVERSE_PORTAL, guard.getLocation().add(0, 1, 0), 8, 0.5, 0.8, 0.5, 0.02);
                if (guard.getHealth() < 7 && ticks >= guardRecharge.getOrDefault(guard.getUniqueId(), 0L)) recharge(guard, location);
            }
            if (ticks % 40 == 0) {
                int tier = power;
                List<UUID> allowed = allow(beacon);
                for (Player player : beacon.getWorld().getPlayers()) {
                    Location at = player.getLocation();
                    boolean friendly = friendly(player, beacon);
                    if (!effectEligible(player, beacon)) continue;
                    if (!friendly && !previewing(player, beacon) && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)) continue;
                    for (int channel = 0; channel < 3; channel++) {
                        int selected = channel(beacon, friendly, channel); if (selected < 0) continue;
                        PotionEffectType effect = (friendly ? GOOD : BAD)[selected];
                        if (effect == null) { mend(player); continue; }
                        player.addPotionEffect(new PotionEffect(effect, effect == PotionEffectType.NIGHT_VISION ? 240 : 65,
                                friendly ? (tier == 4 ? 2 : tier >= 2 ? 1 : 0) : 0, true, false, true));
                    }
                    if (friendly) {
                        Color color = BeaconVisuals.color(COLORS[selection(beacon, colorKey, COLORS.length)]);
                        for (int point = 0; point < 12; point++) {
                            double angle = point * Math.PI / 6 + ticks * 0.035;
                            player.getWorld().spawnParticle(Particle.DUST, at.clone().add(Math.cos(angle) * 0.65, 0.15 + point % 3 * 0.5, Math.sin(angle) * 0.65),
                                    1, 0, 0, 0, 0, new Particle.DustOptions(color, 0.7f));
                        }
                    }
                }
            }
            if (ticks % 100 == 0) {
                defend(beacon);
                double angle = random.nextDouble() * Math.PI * 2, radius = random.nextDouble() * 10;
                int blockX = location.getBlockX() + (int) (Math.cos(angle) * radius), blockZ = location.getBlockZ() + (int) (Math.sin(angle) * radius);
                if (location.getWorld().isChunkLoaded(blockX >> 4, blockZ >> 4)) {
                    int blockY = location.getWorld().getHighestBlockYAt(blockX, blockZ) + 1;
                    if (!plugin.isProtected(location.getWorld(), blockX, blockY, blockZ)) location.getWorld().strikeLightningEffect(new Location(location.getWorld(), blockX + 0.5, blockY, blockZ + 0.5));
                }
                List<UUID> friends = allow(beacon);
                for (Player player : location.getWorld().getPlayers()) if (player.getLocation().distanceSquared(location) < 1600) {
                    boolean friendly = friendly(player, beacon);
                    player.playSound(location, Sound.BLOCK_BEACON_AMBIENT, friendly ? 0.15f : 0.45f, friendly ? 1.2f : 0.5f);
                    player.playSound(location, friendly ? Sound.BLOCK_AMETHYST_BLOCK_CHIME : Sound.BLOCK_NOTE_BLOCK_BASS, 0.3f, friendly ? 1.0f : 0.5f);
                    player.spawnParticle(friendly ? Particle.WAX_ON : Particle.SOUL, player.getLocation().add(0, 1, 0), 12, 2, 1, 2, 0.02);
                }
            }
        }
        updateStorms(storms);
        superWithers.tick();
        groundGuards.tick();
        for (Location location : new ArrayList<>(anchors)) {
            boolean missing = false;
            for (int chunkX = (location.getBlockX() - 4) >> 4; chunkX <= (location.getBlockX() + 4) >> 4; chunkX++)
                for (int chunkZ = (location.getBlockZ() - 4) >> 4; chunkZ <= (location.getBlockZ() + 4) >> 4; chunkZ++) {
                    if (!location.getWorld().isChunkLoaded(chunkX, chunkZ)) { missing = true; continue; }
                    Chunk chunk = location.getWorld().getChunkAt(chunkX, chunkZ);
                    needed.add(chunk);
                    if (held.contains(chunk) || held.size() < 128) { if (held.add(chunk)) chunk.addPluginChunkTicket(plugin); }
                }
            if (missing) loadAnchor(location);
        }
        for (Chunk chunk : new HashSet<>(held)) if (!needed.contains(chunk)) { chunk.removePluginChunkTicket(plugin); held.remove(chunk); }
        if (ticks % 200 == 0) {
            saveStorms();
            long now = System.currentTimeMillis(); authorized.values().removeIf(value -> value < now); blocked.values().removeIf(value -> value < now);
            if (failures.size() > 4096) failures.clear();
        }
    }
    private void updateStorms(Set<UUID> active) {
        for (UUID id : active) {
            World world = Bukkit.getWorld(id); if (world == null) continue;
            weather.computeIfAbsent(id, value -> new Weather(world.hasStorm(), world.isThundering(), world.getWeatherDuration(), world.getThunderDuration(), world.getClearWeatherDuration()));
            boolean powerStorm = tracked.stream().map(this::beacon).filter(Objects::nonNull).anyMatch(beacon -> beacon.getWorld().equals(world)
                    && flag(beacon, enabledKey) && flag(beacon, stormKey) && !generous(beacon) && tier(beacon) > 0);
            if (!powerStorm) { world.setStorm(false); world.setThundering(false); world.setClearWeatherDuration(1200); continue; }
            world.setClearWeatherDuration(0); world.setStorm(true); world.setThundering(true);
            world.setWeatherDuration(1200); world.setThunderDuration(1200);
        }
        for (UUID id : new ArrayList<>(weather.keySet())) if (!active.contains(id)) {
            Weather prior = weather.remove(id); World world = Bukkit.getWorld(id); if (world == null) continue;
            world.setStorm(prior.storm()); world.setThundering(prior.thunder()); world.setWeatherDuration(prior.rainTime());
            world.setThunderDuration(prior.thunderTime()); world.setClearWeatherDuration(prior.clearTime());
        }
    }
    private void animate(Location location, boolean active) {
        Beacon beacon = beacon(location);
        BeaconVisuals.render(location, active, ticks, beamColors(beacon), displays.computeIfAbsent(location, value -> new ArrayList<>()));
        if (!HOMEWORLD_AVAILABLE) rifts.remove(location);
        else rifts.render(location, active && overclocked(beacon), ticks,
                beacon.getPersistentDataContainer().getOrDefault(key("rift_yaw"), PersistentDataType.FLOAT, 0f));
    }
    private String[] beamColors(Beacon beacon) {
        String[] result = new String[5];
        int center = selection(beacon, colorKey, COLORS.length); result[0] = COLORS[center];
        for (int beam = 1; beam < 5; beam++) result[beam] = COLORS[Math.floorMod(beacon.getPersistentDataContainer().getOrDefault(key("beacon_color_" + beam), PersistentDataType.INTEGER, center), COLORS.length)];
        return result;
    }
    private void mend(Player player) {
        long now = System.currentTimeMillis();
        if (now - repaired.getOrDefault(player.getUniqueId(), 0L) < 10000) return;
        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int attempt = 0; attempt < armor.length; attempt++) {
            int slot = (ticks / 200 + attempt) % armor.length; ItemStack stack = armor[slot];
            if (stack == null || !(stack.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damage)
                    || damage.isUnbreakable() || damage.getDamage() <= 0) continue;
            damage.setDamage(damage.getDamage() - 1); stack.setItemMeta(damage);
            armor[slot] = stack; player.getInventory().setArmorContents(armor);
            repaired.put(player.getUniqueId(), now);
            player.spawnParticle(Particle.WAX_ON, player.getLocation().add(0, 1, 0), 5, 0.3, 0.4, 0.3, 0);
            return;
        }
    }
    private void clearDisplays(Location location) { powers.remove(location); rifts.remove(location); List<BlockDisplay> parts = displays.remove(location); if (parts != null) for (BlockDisplay part : parts) part.remove(); }
    boolean eligibleEnemy(Player player, Beacon beacon) {
        Location at = player.getLocation();
        return player.getWorld().equals(beacon.getWorld()) && !friendly(player, beacon)
                && !player.isDead()
                && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR
                && !plugin.isProtected(player.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }
    boolean enemy(Player player, Beacon beacon) {
        return beacon != null && eligibleEnemy(player, beacon)
                && intruders.getOrDefault(beacon.getLocation(), Set.of()).contains(player.getUniqueId())
                && inRange(player.getLocation(), beacon);
    }
    boolean nearFoundation(Location at, Beacon beacon) {
        Location home = beacon.getLocation();
        if (!at.getWorld().equals(home.getWorld())) return false;
        for (int layer = 1; layer <= tier(beacon); layer++) {
            double dx = Math.max(0, Math.max(home.getBlockX() - layer - at.getX(), at.getX() - (home.getBlockX() + layer + 1)));
            double dz = Math.max(0, Math.max(home.getBlockZ() - layer - at.getZ(), at.getZ() - (home.getBlockZ() + layer + 1)));
            double dy = Math.max(0, Math.max(home.getBlockY() - layer - at.getY(), at.getY() - (home.getBlockY() - layer + 1)));
            if (dx * dx + dy * dy + dz * dz <= 25) return true;
        }
        return false;
    }
    void alert(Beacon beacon, Player player) {
        if (beacon == null || !eligibleEnemy(player, beacon)) return;
        intruders.computeIfAbsent(beacon.getLocation(), value -> new HashSet<>()).add(player.getUniqueId());
        superWithers.alarm(beacon.getLocation(), player);
        for (WitherSkeleton guard : defenders.getOrDefault(beacon.getLocation(), List.of())) if (guard.isValid()) guard.setTarget(player);
    }
    @EventHandler public void intruderDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        for (Set<UUID> targets : intruders.values()) targets.remove(event.getEntity().getUniqueId());
    }
    boolean friendlyBalls(Beacon beacon) { return beacon == null || !flag(beacon, key("destructive_skulls")); }
    Color primaryColor(Beacon beacon) { return BeaconVisuals.color(COLORS[selection(beacon, colorKey, COLORS.length)]); }
    private boolean friendlyNear(Location at, Beacon beacon) {
        for (Player player : at.getWorld().getPlayers()) if (player.getLocation().distanceSquared(at) < 64
                && friendly(player, beacon)) return true;
        return false;
    }
    private void clearGuards(Location location) {
        intruders.remove(location);
        helpTargets.remove(location);
        groundGuards.release(location);
        List<WitherSkeleton> guards = defenders.remove(location);
        if (guards != null) for (WitherSkeleton guard : guards) {
            EnderCrystal crystal = rechargeBeams.remove(guard.getUniqueId()); if (crystal != null) crystal.remove();
            guardRecharge.remove(guard.getUniqueId()); guard.remove();
        }
    }
    private void recharge(WitherSkeleton guard, Location home) {
        guardRecharge.put(guard.getUniqueId(), (long) ticks + 400);
        EnderCrystal crystal = guard.getWorld().spawn(home.clone().add(0.5, 1.5, 0.5), EnderCrystal.class, entity -> {
            entity.setInvulnerable(true); entity.setPersistent(false); entity.setShowingBottom(false); entity.setBeamTarget(guard.getLocation().add(0, 1, 0));
        });
        rechargeBeams.put(guard.getUniqueId(), crystal);
        new org.bukkit.scheduler.BukkitRunnable() {
            int pulses;
            @Override public void run() {
                Beacon current = beacon(home);
                if (!guard.isValid() || current == null || !flag(current, enabledKey) || pulses++ >= 3) {
                    crystal.remove(); rechargeBeams.remove(guard.getUniqueId()); cancel(); return;
                }
                guard.setHealth(Math.min(20, guard.getHealth() + 2)); crystal.setBeamTarget(guard.getLocation().add(0, 1, 0));
            }
        }.runTaskTimer(plugin, 1, 20);
    }
    private void defend(Beacon beacon) {
        Location home = beacon.getLocation();
        List<WitherSkeleton> guards = defenders.computeIfAbsent(home, value -> new ArrayList<>());
        guards.removeIf(guard -> {
            if (guard.isValid()) return false;
            guardRecharge.remove(guard.getUniqueId()); EnderCrystal crystal = rechargeBeams.remove(guard.getUniqueId());
            if (crystal != null) crystal.remove(); return true;
        });
        Player target = beacon.getWorld().getPlayers().stream().filter(player -> enemy(player, beacon))
                .min(java.util.Comparator.comparingDouble(player -> player.getLocation().distanceSquared(home))).orElse(null);
        for (WitherSkeleton guard : guards) guard.setTarget(target);
        if (target == null) return;
        if (random.nextBoolean()) {
            Location impact = target.getLocation().clone();
            if (friendlyNear(impact, beacon)) return;
            target.sendActionBar(Component.text("Lightning marked — move!"));
            target.playSound(target.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.8f, 1.5f);
            beacon.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, impact.clone().add(0, 0.2, 0), 40, 1, 0.2, 1, 0.02);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Beacon current = beacon(home);
                if (current == null || !flag(current, enabledKey) || tier(current) == 0 || !enemy(target, current)
                        || friendlyNear(impact, current) || !impact.getWorld().isChunkLoaded(impact.getBlockX() >> 4, impact.getBlockZ() >> 4)
                        || plugin.isProtected(impact.getWorld(), impact.getBlockX(), impact.getBlockY(), impact.getBlockZ())) return;
                impact.getWorld().strikeLightning(impact);
            }, 24);
        } else if (guards.size() < 3 && defenders.values().stream().mapToInt(List::size).sum() < 32) {
            for (int attempt = 0; attempt < 8; attempt++) {
                int blockX = home.getBlockX() + random.nextInt(13) - 6, blockZ = home.getBlockZ() + random.nextInt(13) - 6;
                World world = home.getWorld(); if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) continue;
                int blockY = world.getHighestBlockYAt(blockX, blockZ) + 1;
                if (blockY + 3 >= world.getMaxHeight() || plugin.isProtected(world, blockX, blockY, blockZ)
                        || !world.getBlockAt(blockX, blockY - 1, blockZ).getType().isSolid()
                        || !world.getBlockAt(blockX, blockY, blockZ).isEmpty() || !world.getBlockAt(blockX, blockY + 1, blockZ).isEmpty()) continue;
                WitherSkeleton guard = world.spawn(new Location(world, blockX + 0.5, blockY, blockZ + 0.5), WitherSkeleton.class, skeleton -> {
                    skeleton.setPersistent(true); skeleton.setRemoveWhenFarAway(false);
                    skeleton.getPersistentDataContainer().set(defenderKey, PersistentDataType.BYTE, (byte) 1);
                });
                guards.add(guard);
                groundGuards.track(guard, home);
                guard.setTarget(target);
                world.spawnParticle(Particle.SOUL, guard.getLocation(), 24, 0.5, 1, 0.5, 0.03);
                break;
            }
        }
    }
    @EventHandler(ignoreCancelled = true)
    public void guardTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof WitherSkeleton)) return;
        if (!event.getEntity().getPersistentDataContainer().has(defenderKey, PersistentDataType.BYTE)) return;
        if (event.getTarget() == null) return;
        Location home = defenders.entrySet().stream().filter(entry -> entry.getValue().contains(event.getEntity())).map(Map.Entry::getKey).findFirst().orElse(null);
        Beacon beacon = home == null ? null : beacon(home);
        if (beacon == null || !combatEnemy(event.getTarget(), beacon)) event.setCancelled(true);
    }
    @EventHandler public void guardDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(defenderKey, PersistentDataType.BYTE)) { event.getDrops().clear(); event.setDroppedExp(0); }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void rechargeDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (rechargeBeams.containsValue(event.getEntity())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void guardDamage(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof WitherSkeleton && event.getEntity().getPersistentDataContainer().has(defenderKey, PersistentDataType.BYTE)) {
            org.bukkit.entity.Entity attacker = event.getDamager();
            if (attacker instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof org.bukkit.entity.Entity shooter) attacker = shooter;
            if (attacker instanceof Player player) {
                Location home = defenders.entrySet().stream().filter(entry -> entry.getValue().contains(event.getEntity())).map(Map.Entry::getKey).findFirst().orElse(null);
                if (home != null) alert(beacon(home), player);
            }
        }
        if (!(event.getDamager() instanceof WitherSkeleton) || !event.getDamager().getPersistentDataContainer().has(defenderKey, PersistentDataType.BYTE)) return;
        Location home = defenders.entrySet().stream().filter(entry -> entry.getValue().contains(event.getDamager())).map(Map.Entry::getKey).findFirst().orElse(null);
        Beacon beacon = home == null ? null : beacon(home);
        if (!(event.getEntity() instanceof LivingEntity living) || beacon == null || !combatEnemy(living, beacon)) event.setCancelled(true);
    }
    private void saveStorms() {
        var saved = new org.bukkit.configuration.file.YamlConfiguration(); saved.set("storms", new ArrayList<>(stormLocations)); saved.set("anchors", new ArrayList<>(anchors));
        try { saved.save(stateFile); } catch (java.io.IOException exception) { plugin.getLogger().severe("Could not save Command Beacon storm index: " + exception.getMessage()); }
    }
    public void shutdown() {
        superWithers.shutdown();
        saveStorms(); for (Location location : new ArrayList<>(displays.keySet())) clearDisplays(location); updateStorms(Set.of());
        groundGuards.shutdown(); defenders.clear();
        for (EnderCrystal crystal : rechargeBeams.values()) crystal.remove(); rechargeBeams.clear();
        for (Chunk chunk : held) chunk.removePluginChunkTicket(plugin); held.clear();
    }
    boolean possessing(Beacon beacon) { return beacon != null && !generous(beacon) && flag(beacon, enabledKey)
            && flag(beacon, key("possess_withers")) && !text(beacon, codeKey).isEmpty() && tier(beacon) > 0; }
    boolean guardActive(Beacon beacon) { return beacon != null && !generous(beacon) && flag(beacon, enabledKey) && !text(beacon, codeKey).isEmpty() && tier(beacon) > 0; }
    boolean capturing(Beacon beacon) { return guardActive(beacon) && flag(beacon, key("possess_players")); }
    int range(Beacon beacon) {
        if (beacon == null) return 32;
        if (overclocked(beacon) && rifts.ready(beacon.getLocation())) return 350;
        Integer selected = beacon.getPersistentDataContainer().get(key("beacon_range"), PersistentDataType.INTEGER);
        if (selected != null) for (int value : RANGES) if (selected == value) return value;
        Integer power = powers.get(beacon.getLocation());
        return Math.max(1, power == null ? tier(beacon) : power) * 32;
    }
    boolean overclocked(Beacon beacon) { return HOMEWORLD_AVAILABLE && beacon != null && flag(beacon, key("beacon_overclock")); }
    boolean inRange(Location at, Beacon beacon) {
        if (beacon == null || !at.getWorld().equals(beacon.getWorld())) return false;
        Location home = beacon.getLocation(); int radius = range(beacon);
        return Math.hypot(at.getX() - home.getX(), at.getZ() - home.getZ()) <= radius && Math.abs(at.getY() - home.getY()) <= radius;
    }
    boolean effectEligible(Player player, Beacon beacon) {
        Location at = player.getLocation();
        if (!inRange(at, beacon) || plugin.getConfig().getStringList("protection.disabled-worlds").contains(at.getWorld().getName())) return false;
        return friendly(player, beacon) || !plugin.isProtected(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }
    boolean combatEnemy(LivingEntity target, Beacon beacon) {
        if (beacon == null || target.isDead() || !target.getWorld().equals(beacon.getWorld())) return false;
        if (target instanceof Player player) return enemy(player, beacon);
        if (!guardActive(beacon) || !flag(beacon, key("help_friends")) || target instanceof ArmorStand || target instanceof Tameable
                || guardHome(target) != null || target.getPersistentDataContainer().has(defenderKey, PersistentDataType.BYTE)) return false;
        Location at = target.getLocation();
        return inRange(at, beacon) && !plugin.isProtected(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ())
                && helpTargets.getOrDefault(beacon.getLocation(), Map.of()).getOrDefault(target.getUniqueId(), 0L) > System.currentTimeMillis();
    }
    LivingEntity combatTarget(Beacon beacon, Mob guard, boolean sight) {
        if (!guardActive(beacon)) return null;
        List<LivingEntity> candidates = new ArrayList<>(beacon.getWorld().getPlayers());
        Map<UUID, Long> targets = helpTargets.get(beacon.getLocation());
        if (targets != null) {
            targets.entrySet().removeIf(entry -> entry.getValue() <= System.currentTimeMillis());
            for (UUID id : targets.keySet()) if (Bukkit.getEntity(id) instanceof LivingEntity living) candidates.add(living);
        }
        return candidates.stream().filter(entity -> combatEnemy(entity, beacon) && (!sight || guard.hasLineOfSight(entity)))
                .min(java.util.Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(guard.getLocation()))).orElse(null);
    }
    void requestHelp(Beacon beacon, Player friend, LivingEntity foe) {
        if (!guardActive(beacon) || !flag(beacon, key("help_friends")) || !friendly(friend, beacon) || friend.isDead()
                || !friend.getWorld().equals(beacon.getWorld()) || !foe.getWorld().equals(beacon.getWorld()) || foe.isDead()) return;
        Location at = friend.getLocation(), other = foe.getLocation(), home = beacon.getLocation();
        if (!inRange(at, beacon) || !inRange(other, beacon) || plugin.isProtected(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ())
                || plugin.isProtected(other.getWorld(), other.getBlockX(), other.getBlockY(), other.getBlockZ())) return;
        if (foe instanceof Player player) { alert(beacon, player); return; }
        if (foe instanceof ArmorStand || foe instanceof Tameable || guardHome(foe) != null || foe.getPersistentDataContainer().has(defenderKey, PersistentDataType.BYTE)) return;
        Map<UUID, Long> targets = helpTargets.computeIfAbsent(home, value -> new HashMap<>());
        if (targets.size() >= 32 && !targets.containsKey(foe.getUniqueId())) targets.entrySet().removeIf(entry -> entry.getValue() <= System.currentTimeMillis());
        if (targets.size() < 32 || targets.containsKey(foe.getUniqueId())) targets.put(foe.getUniqueId(), System.currentTimeMillis() + 30000);
        superWithers.alarm(home, foe);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void friendlyFight(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (event.getFinalDamage() <= 0) return;
        Entity attacker = event.getDamager();
        if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) attacker = shooter;
        if (!(attacker instanceof LivingEntity source) || !(event.getEntity() instanceof LivingEntity victim)) return;
        for (Location home : tracked) {
            if (!home.getWorld().equals(victim.getWorld()) || !home.getWorld().isChunkLoaded(home.getBlockX() >> 4, home.getBlockZ() >> 4)) continue;
            Beacon beacon = beacon(home);
            if (victim instanceof Player friend) requestHelp(beacon, friend, source);
            // Helping a friend attack a mob is allowed; initiating PvP does not frame the other player as an attacker.
            if (source instanceof Player friend && !(victim instanceof Player)) requestHelp(beacon, friend, victim);
        }
    }
    Location guardHome(Entity entity) {
        Location home = superWithers.homeOf(entity);
        return home == null ? groundGuards.homeOf(entity) : home;
    }
    void adoptSkeleton(WitherSkeleton mob, Location home) {
        List<WitherSkeleton> list = defenders.computeIfAbsent(home, value -> new ArrayList<>());
        if (!list.contains(mob)) list.add(mob);
    }
    List<Location> beaconLocations() { return new ArrayList<>(tracked); }
    public void recover(Player player) {
        Block block = player.getTargetBlockExact(8);
        Beacon beacon = block == null ? null : beacon(block.getLocation());
        if (beacon == null) { player.sendMessage("Look at a Command Beacon within eight blocks."); return; }
        beacon.getPersistentDataContainer().remove(codeKey); beacon.getPersistentDataContainer().remove(saltKey);
        beacon.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        flag(beacon, enabledKey, false); flag(beacon, stormKey, false); beacon.update();
        stormLocations.remove(beacon.getLocation()); anchors.remove(beacon.getLocation()); saveStorms();
        plugin.getLogger().warning(player.getName() + " reset Command Beacon access at " + block.getLocation());
        player.sendMessage("Beacon disabled and ownership recovered. Open it to create a new code.");
    }
}
