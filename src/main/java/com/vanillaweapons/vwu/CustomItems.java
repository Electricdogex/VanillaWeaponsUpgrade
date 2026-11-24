package com.vanillaweapons.vwu;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class CustomItems implements Listener {
    public enum Kind {
        NETHER_CORE("nether_core", Material.NETHER_STAR, "Nether Core"),
        SUPER_TNT("super_tnt", Material.TNT, "Super TNT"),
        FORMIDIBOMB("formidibomb", Material.TNT, "Formidibomb"),
        CORRUPTED_STAR("corrupted_star", Material.NETHER_STAR, "Corrupted Nether Star"),
        COMMAND_BEACON("command_beacon", Material.BEACON, "Command Beacon");
        public final String id;
        public final Material material;
        public final String title;
        Kind(String id, Material material, String title) {
            this.id = id; this.material = material; this.title = title;
        }
        public static Kind parse(String s) {
            for (Kind k : values()) if (k.id.equalsIgnoreCase(s)) return k;
            return null;
        }
    }

    private final VanillaWeaponsPlugin plugin;
    private final NamespacedKey itemKey;

    public CustomItems(VanillaWeaponsPlugin plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "weapon_id");
    }

    public ItemStack create(Kind kind, int amount) {
        ItemStack stack = new ItemStack(kind.material, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(kind.title, kind == Kind.FORMIDIBOMB ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GOLD));
        meta.lore(null);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, kind.id);
        stack.setItemMeta(meta);
        return stack;
    }

    public Kind identify(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        ItemMeta meta = stack.getItemMeta();
        String s = meta.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        Kind kind = s == null ? null : Kind.parse(s);
        return kind != null && kind.material == stack.getType() ? kind : null;
    }

    public void registerRecipes() {
        var fusion = new org.bukkit.inventory.ShapelessRecipe(new NamespacedKey(plugin, "corrupted_star"), create(Kind.CORRUPTED_STAR, 1));
        fusion.addIngredient(Material.COMMAND_BLOCK);
        fusion.addIngredient(Material.NETHER_STAR);
        Bukkit.addRecipe(fusion);
        var beacon = new ShapedRecipe(new NamespacedKey(plugin, "command_beacon"), create(Kind.COMMAND_BEACON, 1));
        beacon.shape("GGG", "GSG", "OOO");
        beacon.setIngredient('G', Material.GLASS);
        beacon.setIngredient('O', Material.OBSIDIAN);
        beacon.setIngredient('S', RecipeChoice.exactChoice(create(Kind.CORRUPTED_STAR, 1)));
        Bukkit.addRecipe(beacon);
        ShapedRecipe core = new ShapedRecipe(new NamespacedKey(plugin, Kind.NETHER_CORE.id), create(Kind.NETHER_CORE, 1));
        core.shape("GGG", "GSG", "GGG");
        core.setIngredient('G', Material.GOLD_INGOT);
        core.setIngredient('S', Material.NETHER_STAR);
        Bukkit.addRecipe(core);

        ShapedRecipe superTnt = new ShapedRecipe(new NamespacedKey(plugin, Kind.SUPER_TNT.id), create(Kind.SUPER_TNT, 1));
        superTnt.shape("T T", " C ", "T T");
        superTnt.setIngredient('T', Material.TNT);
        superTnt.setIngredient('C', RecipeChoice.exactChoice(create(Kind.NETHER_CORE, 1)));
        Bukkit.addRecipe(superTnt);

        ShapedRecipe form = new ShapedRecipe(new NamespacedKey(plugin, Kind.FORMIDIBOMB.id), create(Kind.FORMIDIBOMB, 1));
        form.shape("NNN", "NSN", "NNN");
        form.setIngredient('N', Material.NETHERITE_INGOT);
        form.setIngredient('S', RecipeChoice.exactChoice(create(Kind.SUPER_TNT, 1)));
        Bukkit.addRecipe(form);
    }

    private boolean hasCustom(ItemStack[] items) {
        for (ItemStack item : items) if (identify(item) != null) return true;
        return false;
    }

    public void stripLore(ItemStack stack) {
        if (identify(stack) == null) return;
        ItemMeta meta = stack.getItemMeta();
        if (meta.hasLore()) { meta.lore(null); stack.setItemMeta(meta); }
    }

    @EventHandler public void joined(org.bukkit.event.player.PlayerJoinEvent event) {
        for (ItemStack stack : event.getPlayer().getInventory().getContents()) stripLore(stack);
    }

    @EventHandler public void opened(org.bukkit.event.inventory.InventoryOpenEvent event) {
        for (ItemStack stack : event.getInventory().getContents()) stripLore(stack);
    }

    @EventHandler public void clicked(org.bukkit.event.inventory.InventoryClickEvent event) {
        stripLore(event.getCurrentItem()); stripLore(event.getCursor());
    }

    private boolean belongsToUs(Recipe recipe) {
        if (!(recipe instanceof org.bukkit.Keyed keyed)) return false;
        return Objects.equals(keyed.getKey().getNamespace(), plugin.getName().toLowerCase(Locale.ROOT))
                && Kind.parse(keyed.getKey().getKey()) != null;
    }

    private boolean validCustomIngredients(ItemStack[] matrix, Recipe recipe) {
        if (!hasCustom(matrix)) return true;
        if (!belongsToUs(recipe)) return false;
        String result = ((org.bukkit.Keyed) recipe).getKey().getKey();
        Kind expected = switch (result) {
            case "super_tnt" -> Kind.NETHER_CORE;
            case "formidibomb" -> Kind.SUPER_TNT;
            case "command_beacon" -> Kind.CORRUPTED_STAR;
            default -> null;
        };
        for (ItemStack stack : matrix) if (identify(stack) != null && identify(stack) != expected) return false;
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareItemCraftEvent event) {
        CraftingInventory inv = event.getInventory();
        for (ItemStack stack : inv.getMatrix()) stripLore(stack);
        if (!validCustomIngredients(inv.getMatrix(), event.getRecipe())) inv.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!validCustomIngredients(event.getInventory().getMatrix(), event.getRecipe())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent event) {
        if (!(event.getBlock().getState() instanceof org.bukkit.block.Crafter crafter)) return;
        if (!validCustomIngredients(crafter.getInventory().getContents(), event.getRecipe())) event.setCancelled(true);
    }
}
