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
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class OwnershipManager {
    private static final String ORIGINAL_STATUS = "§aOriginal";
    private static final String STOLEN_STATUS = "§cStolen";
    private static final String RETURN_PREFIX = "§7Return to §f";
    private static final String EGG_TIMER_PREFIX = "§6Dragon Egg: §f";
    private static final int EGG_SECONDS = 24 * 60 * 60;

    private final JavaPlugin plugin;
    private final NamespacedKey ownerIdKey;
    private final NamespacedKey ownerNameKey;
    private final NamespacedKey historyKey;
    private final NamespacedKey itemIdKey;
    private final NamespacedKey eggHolderKey;
    private final NamespacedKey eggRemainingKey;

    public OwnershipManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.ownerIdKey = new NamespacedKey(plugin, "owner-id");
        this.ownerNameKey = new NamespacedKey(plugin, "owner-name");
        this.historyKey = new NamespacedKey(plugin, "ownership-history");
        this.itemIdKey = new NamespacedKey(plugin, "item-id");
        this.eggHolderKey = new NamespacedKey(plugin, "dragon-egg-holder");
        this.eggRemainingKey = new NamespacedKey(plugin, "dragon-egg-remaining");
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
                || type.equals("DRAGON_EGG")
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
        if (isDragonEgg(item)) {
            data.remove(eggHolderKey);
            data.remove(eggRemainingKey);
        }
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
        lore.removeIf(line -> line.equals(ORIGINAL_STATUS) || line.equals(STOLEN_STATUS)
                || line.startsWith(RETURN_PREFIX) || line.startsWith(EGG_TIMER_PREFIX));

        if (owner.id().equals(viewerId)) {
            lore.add(0, ORIGINAL_STATUS);
        } else {
            lore.add(0, RETURN_PREFIX + owner.name());
            lore.add(0, STOLEN_STATUS);
            if (isDragonEgg(item)) {
                Integer remaining = meta.getPersistentDataContainer().get(eggRemainingKey, PersistentDataType.INTEGER);
                if (remaining != null) {
                    lore.add(EGG_TIMER_PREFIX + String.format("%02dh %02dm %02ds",
                            remaining / 3600, remaining / 60 % 60, remaining % 60));
                }
            }
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
                updateEggHolder(item, player);
                refreshDisplay(item, player.getUniqueId());
                if (isDragonEgg(item)) {
                    player.getInventory().setItem(slot, item);
                }
            }
        }
    }

    public void tickDragonEggInventory(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!isDragonEgg(item)) {
                continue;
            }
            OwnerInfo owner = getOwner(item);
            if (owner == null) {
                continue;
            }
            updateEggHolder(item, player);
            if (owner.id().equals(player.getUniqueId())) {
                player.getInventory().setItem(slot, item);
                continue;
            }

            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                continue;
            }
            PersistentDataContainer data = meta.getPersistentDataContainer();
            int remaining = Math.max(0, data.getOrDefault(eggRemainingKey, PersistentDataType.INTEGER, EGG_SECONDS) - 1);
            data.set(eggRemainingKey, PersistentDataType.INTEGER, remaining);
            item.setItemMeta(meta);
            if (remaining == 0) {
                transferDragonEgg(item, owner, player);
            } else if (remaining % 600 == 0) {
                leakEggLocation(owner, player);
            }
            refreshDisplay(item, player.getUniqueId());
            player.getInventory().setItem(slot, item);
        }
    }

    public void notifyPendingEggLoss(Player player) {
        String path = "pending-egg-loss." + player.getUniqueId();
        if (plugin.getConfig().getBoolean(path)) {
            player.sendMessage("§cThe Dragon Egg is no longer yours to take");
            plugin.getConfig().set(path, null);
            plugin.saveConfig();
        }
    }

    private boolean isDragonEgg(ItemStack item) {
        return item != null && item.getType().name().equals("DRAGON_EGG");
    }

    private void updateEggHolder(ItemStack item, Player player) {
        if (!isDragonEgg(item)) {
            return;
        }
        OwnerInfo owner = getOwner(item);
        if (owner == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (owner.id().equals(player.getUniqueId())) {
            if (data.has(eggHolderKey, PersistentDataType.STRING)
                    || data.has(eggRemainingKey, PersistentDataType.INTEGER)) {
                data.remove(eggHolderKey);
                data.remove(eggRemainingKey);
                item.setItemMeta(meta);
            }
        } else if (!player.getUniqueId().toString().equals(data.get(eggHolderKey, PersistentDataType.STRING))) {
            data.set(eggHolderKey, PersistentDataType.STRING, player.getUniqueId().toString());
            data.set(eggRemainingKey, PersistentDataType.INTEGER, EGG_SECONDS);
            item.setItemMeta(meta);
        }
    }

    private void transferDragonEgg(ItemStack item, OwnerInfo previous, Player holder) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        List<OwnerInfo> history = new ArrayList<>(getHistory(item));
        history.add(previous);
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(historyKey, PersistentDataType.STRING, serializeHistory(history));
        data.set(ownerIdKey, PersistentDataType.STRING, holder.getUniqueId().toString());
        data.set(ownerNameKey, PersistentDataType.STRING, holder.getName());
        data.remove(eggHolderKey);
        data.remove(eggRemainingKey);
        item.setItemMeta(meta);

        Player oldOwner = Bukkit.getPlayer(previous.id());
        if (oldOwner != null && oldOwner.isOnline()) {
            oldOwner.sendMessage("§cThe Dragon Egg is no longer yours to take");
        } else {
            plugin.getConfig().set("pending-egg-loss." + previous.id(), true);
            plugin.saveConfig();
        }
        Location location = getLastKnownLocation(previous.id());
        if (location != null) {
            holder.sendMessage("§eThe Dragon Egg is now yours. " + previous.name() + " is at " + formatLocation(location) + ".");
        } else {
            holder.sendMessage("§eThe Dragon Egg is now yours. " + previous.name() + "'s location is unknown.");
        }
    }

    private void leakEggLocation(OwnerInfo owner, Player holder) {
        holder.sendMessage("§cYou feel like you're being watched");
        Player originalOwner = Bukkit.getPlayer(owner.id());
        if (originalOwner == null || !originalOwner.isOnline()) {
            return;
        }
        Location location = holder.getLocation();
        if (holder.getActivePotionEffects().stream().noneMatch(effect -> effect.getType().getName().equalsIgnoreCase("INVISIBILITY"))) {
            originalOwner.sendMessage("§eYour Dragon Egg is at " + formatLocation(location) + ", held by " + holder.getName());
            return;
        }

        int obscuredAxis = ThreadLocalRandom.current().nextInt(3);
        originalOwner.sendMessage("§eYour Dragon Egg is at " + formatBlurredLocation(location, obscuredAxis, "???")
                + ", held by " + holder.getName());
        final int[] frames = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!originalOwner.isOnline() || frames[0]++ >= 30) {
                task[0].cancel();
                return;
            }
            String noise = Integer.toString(ThreadLocalRandom.current().nextInt(100, 1000));
            originalOwner.sendActionBar(Component.text("Your Dragon Egg is at "
                    + formatBlurredLocation(holder.getLocation(), obscuredAxis, noise)
                    + ", held by " + holder.getName()));
        }, 0L, 2L);
    }

    private String formatBlurredLocation(Location location, int axis, String noise) {
        return (axis == 0 ? noise : Integer.toString(location.getBlockX())) + ", "
                + (axis == 1 ? noise : Integer.toString(location.getBlockY())) + ", "
                + (axis == 2 ? noise : Integer.toString(location.getBlockZ())) + " ("
                + location.getWorld().getName() + ", " + location.getWorld().getEnvironment().name() + ")";
    }

    private String formatLocation(Location location) {
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ()
                + " (" + location.getWorld().getName() + ", " + location.getWorld().getEnvironment().name() + ")";
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