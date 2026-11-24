package com.vanillaweapons.vwu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class VwuCommand implements CommandExecutor, TabCompleter {
    private final VanillaWeaponsPlugin plugin;
    private final CustomItems items;
    private final ExplosionEngine explosions;
    private final FalloutManager fallout;

    public VwuCommand(VanillaWeaponsPlugin plugin, CustomItems items,
                      ExplosionEngine explosions, FalloutManager fallout) {
        this.plugin = plugin;
        this.items = items;
        this.explosions = explosions;
        this.fallout = fallout;
    }

    public void register() {
        var command = plugin.getCommand("vwu");
        if (command == null) throw new IllegalStateException("/vwu missing from plugin.yml");
        command.setExecutor(this);
        command.setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp() || !sender.hasPermission("vwu.admin")) {
            sender.sendMessage("Only server operators may use VWU commands.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage("Vanilla Weapons Upgrade: /vwu recipes | /vwu give <player> <nether_core|super_tnt|formidibomb> [amount]");
            sender.sendMessage("Admin: /vwu status | reload | cancel <job> | weather stop [world] | benchmark | cleanup | beaconreset");
            return true;
        }
        if (args[0].equalsIgnoreCase("recipes")) {
            sender.sendMessage("Nether Core: GGG / GSG / GGG (G=gold, S=nether star)");
            sender.sendMessage("Super TNT: T_T / _C_ / T_T (T=TNT, C=Nether Core)");
            sender.sendMessage("Formidibomb: NNN / NSN / NNN (N=netherite, S=Super TNT)");
            sender.sendMessage("Corrupted Nether Star: command block + Nether Star (shapeless). Command Beacon: GGG / GSG / OOO (glass, corrupted star, obsidian).");
            return true;
        }
        if (!sender.hasPermission("vwu.admin")) { sender.sendMessage("No permission."); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "beaconreset" -> {
                if (sender instanceof Player player) plugin.commandBeacons().recover(player);
                else sender.sendMessage("Use /vwu beaconreset in-game while looking at the beacon.");
            }
            case "cleanup" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Run /vwu cleanup in-game near the affected crater."); return true; }
                sender.sendMessage(FoliageCleanup.scan(player.getLocation(), 120)
                        ? "Started support cleanup within 120 blocks in loaded chunks; completion will appear in the server log."
                        : "A foliage scan is already running.");
            }
            case "give" -> {
                if (args.length < 3) { sender.sendMessage("Usage: /vwu give <player> <item> [amount]"); return true; }
                Player player = Bukkit.getPlayerExact(args[1]);
                CustomItems.Kind kind = CustomItems.Kind.parse(args[2]);
                if (player == null || kind == null) { sender.sendMessage("Unknown player or item."); return true; }
                int amount = 1;
                if (args.length > 3) {
                    try { amount = Integer.parseInt(args[3]); }
                    catch (NumberFormatException ignored) { sender.sendMessage("Amount must be 1–64."); return true; }
                }
                if (amount < 1 || amount > 64) { sender.sendMessage("Amount must be 1–64."); return true; }
                for (ItemStack remaining : player.getInventory().addItem(items.create(kind, amount)).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), remaining);
                }
                sender.sendMessage("Gave " + amount + " " + kind.title + " to " + player.getName() + ".");
            }
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage("Configuration reloaded. Existing crater jobs keep their original dimensions.");
            }
            case "status" -> {
                sender.sendMessage("VWU: " + explosions.activeJobs().size() + " queued jobs. paused="
                        + explosions.isPaused() + "; last processing="
                        + String.format(Locale.ROOT, "%.3f", explosions.lastProcessingMs()) + " ms; Paper MSPT="
                        + String.format(Locale.ROOT, "%.2f", Bukkit.getAverageTickTime()));
                for (var job : explosions.activeJobs()) {
                    sender.sendMessage("#" + job.id + " " + job.label() + " phase="
                            + (job.phase == 0 ? "crater" : "glass") + " chunk=" + job.chunkX + "," + job.chunkZ
                            + " removed=" + job.removed + " glass=" + job.glassed);
                }
            }
            case "cancel" -> {
                if (args.length < 2) { sender.sendMessage("Usage: /vwu cancel <job-id>"); return true; }
                try {
                    sender.sendMessage(explosions.cancel(Integer.parseInt(args[1])) ? "Job cancelled." : "Unknown job ID.");
                } catch (NumberFormatException ignored) { sender.sendMessage("Invalid job ID."); }
            }
            case "weather" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("stop")) {
                    sender.sendMessage("Usage: /vwu weather stop [world]"); return true;
                }
                World world = args.length >= 3 ? Bukkit.getWorld(args[2])
                        : sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().getFirst();
                if (world == null) { sender.sendMessage("Unknown world."); return true; }
                sender.sendMessage(fallout.stopWinter(world) ? "Stopped nuclear winter in " + world.getName()
                        : "No nuclear winter active in " + world.getName());
            }
            case "benchmark" -> sender.sendMessage(explosions.benchmark());
            default -> sender.sendMessage("Unknown subcommand. Use /vwu help.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp() || !sender.hasPermission("vwu.admin") || args.length == 0) return List.of();
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("help", "recipes"));
            if (sender.hasPermission("vwu.admin")) options.addAll(List.of("give", "reload", "status", "cancel", "weather", "benchmark", "cleanup", "beaconreset"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (CustomItems.Kind kind : CustomItems.Kind.values()) options.add(kind.id);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("cancel")) {
            for (var job : explosions.activeJobs()) options.add(String.valueOf(job.id));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("weather")) {
            options.add("stop");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("weather")) {
            for (World w : Bukkit.getWorlds()) options.add(w.getName());
        }
        String current = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(current)).toList();
    }
}
