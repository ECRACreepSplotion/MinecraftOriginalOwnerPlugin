package com.kodari.ownershiptracker;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class OwnershipManager {
    private static final String ORIGINAL_STATUS = "§aOriginal";
    private static final String STOLEN_STATUS = "§cStolen";
    private static final String RETURN_PREFIX = "§7Return to §f";

    private final JavaPlugin plugin;
    private final NamespacedKey ownerIdKey;
    private final NamespacedKey ownerNameKey;
    private final NamespacedKey historyKey;
    private final NamespacedKey itemIdKey;

    public OwnershipManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.ownerIdKey = new NamespacedKey(plugin, "owner-id");
        this.ownerNameKey = new NamespacedKey(plugin, "owner-name");
        this.historyKey = new NamespacedKey(plugin, "ownership-history");
        this.itemIdKey = new NamespacedKey(plugin, "item-id");
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public boolean isEligible(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        String type = item.getType().name();
        return type.matches(".+_(SWORD|PICKAXE|AXE|SHOVEL|HOE)$")
                || type.matches(".+_(HELMET|CHESTPLATE|LEGGINGS|BOOTS)$")
                || type.equals("MACE")
                || type.equals("ELYTRA")
                || type.equals("BOW")
                || type.equals("CROSSBOW")
                || type.equals("SHIELD")
                || type.equals("TRIDENT")
                || type.equals("SPEAR") || type.endsWith("_SPEAR")
                || type.equals("WOLF_ARMOR");
    }

    public void claimIfUnowned(ItemStack item, Player player) {
        if (!isEligible(item) || getOwner(item) != null) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(ownerIdKey, PersistentDataType.STRING, player.getUniqueId().toString());
        data.set(ownerNameKey, PersistentDataType.STRING, player.getName());
        item.setItemMeta(meta);
        refreshDisplay(item, player.getUniqueId());
    }

    public OwnerInfo getOwner(ItemStack item) {
        if (!isEligible(item)) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }

        PersistentDataContainer data = meta.getPersistentDataContainer();
        String id = data.get(ownerIdKey, PersistentDataType.STRING);
        String name = data.get(ownerNameKey, PersistentDataType.STRING);
        if (id == null || name == null) {
            return null;
        }

        try {
            return new OwnerInfo(UUID.fromString(id), name);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public boolean transfer(ItemStack item, Player from, Player to) {
        OwnerInfo owner = getOwner(item);
        if (owner == null || !owner.id().equals(from.getUniqueId())) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        PersistentDataContainer data = meta.getPersistentDataContainer();
        List<OwnerInfo> history = new ArrayList<>(getHistory(item));
        history.add(owner);
        data.set(historyKey, PersistentDataType.STRING, serializeHistory(history));
        data.set(ownerIdKey, PersistentDataType.STRING, to.getUniqueId().toString());
        data.set(ownerNameKey, PersistentDataType.STRING, to.getName());
        item.setItemMeta(meta);
        refreshDisplay(item, from.getUniqueId());
        return true;
    }

    public String getOrCreateItemId(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }

        PersistentDataContainer data = meta.getPersistentDataContainer();
        String itemId = data.get(itemIdKey, PersistentDataType.STRING);
        if (itemId != null) {
            return itemId;
        }

        itemId = UUID.randomUUID().toString();
        data.set(itemIdKey, PersistentDataType.STRING, itemId);
        item.setItemMeta(meta);
        return itemId;
    }

    public boolean hasItemId(ItemStack item, String itemId) {
        if (item == null || item.getType().isAir() || itemId == null) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        return meta != null && itemId.equals(meta.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING));
    }

    public List<OwnerInfo> getHistory(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return List.of();
        }

        String serialized = meta.getPersistentDataContainer().get(historyKey, PersistentDataType.STRING);
        if (serialized == null || serialized.isEmpty()) {
            return List.of();
        }

        List<OwnerInfo> history = new ArrayList<>();
        for (String entry : serialized.split(";")) {
            String[] values = entry.split(",", 2);
            if (values.length != 2) {
                continue;
            }
            try {
                history.add(new OwnerInfo(UUID.fromString(values[0]), values[1]));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return history;
    }

    public void refreshDisplay(ItemStack item, UUID viewerId) {
        OwnerInfo owner = getOwner(item);
        if (owner == null) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        List<String> lore = meta.hasLore() && meta.getLore() != null
                ? new ArrayList<>(meta.getLore())
                : new ArrayList<>();
        lore.removeIf(line -> line.equals(ORIGINAL_STATUS) || line.equals(STOLEN_STATUS) || line.startsWith(RETURN_PREFIX));

        if (owner.id().equals(viewerId)) {
            lore.add(0, ORIGINAL_STATUS);
        } else {
            lore.add(0, RETURN_PREFIX + owner.name());
            lore.add(0, STOLEN_STATUS);
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    public void refreshInventory(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (isEligible(item) && getOwner(item) == null) {
                claimIfUnowned(item, player);
                player.getInventory().setItem(slot, item);
            } else {
                refreshDisplay(item, player.getUniqueId());
            }
        }
    }

    public void saveLocation(Player player) {
        Location location = player.getLocation();
        String path = "last-locations." + player.getUniqueId();
        plugin.getConfig().set(path + ".world", location.getWorld().getName());
        plugin.getConfig().set(path + ".x", location.getX());
        plugin.getConfig().set(path + ".y", location.getY());
        plugin.getConfig().set(path + ".z", location.getZ());
    }

    public void saveOnlineLocations() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            saveLocation(player);
        }
        plugin.saveConfig();
    }

    public Location getLastKnownLocation(UUID playerId) {
        Player onlinePlayer = Bukkit.getPlayer(playerId);
        if (onlinePlayer != null) {
            return onlinePlayer.getLocation().clone();
        }

        String path = "last-locations." + playerId;
        String worldName = plugin.getConfig().getString(path + ".world");
        if (worldName == null) {
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world,
                plugin.getConfig().getDouble(path + ".x"),
                plugin.getConfig().getDouble(path + ".y"),
                plugin.getConfig().getDouble(path + ".z"));
    }

    private String serializeHistory(List<OwnerInfo> history) {
        StringBuilder result = new StringBuilder();
        for (OwnerInfo owner : history) {
            if (!result.isEmpty()) {
                result.append(';');
            }
            result.append(owner.id()).append(',').append(owner.name());
        }
        return result.toString();
    }

    public record OwnerInfo(UUID id, String name) {
    }
}