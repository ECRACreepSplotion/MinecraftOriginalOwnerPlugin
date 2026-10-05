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
import org.bukkit.configuration.ConfigurationSection;
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
        if (isDragonEgg(item)) {
            initializeDragonEggState(item, new OwnerInfo(player.getUniqueId(), player.getName()));
        }
        refreshDisplay(item, player.getUniqueId());
    }

    public OwnerInfo getOwner(ItemStack item) {
        if (!isEligible(item)) {
            return null;
        }

        if (isDragonEgg(item)) {
            OwnerInfo savedOwner = getSavedDragonEggOwner();
            if (savedOwner != null) {
                return savedOwner;
            }
        }

        OwnerInfo itemOwner = getItemOwner(item);
        if (isDragonEgg(item) && itemOwner != null) {
            initializeDragonEggState(item, itemOwner);
        }
        return itemOwner;
    }

    private OwnerInfo getItemOwner(ItemStack item) {
        if (item == null || item.getItemMeta() == null) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
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
        if (isDragonEgg(item)) {
            persistDragonEggOwner(item);
        }
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
        if (isDragonEgg(item)) {
            getOwner(item);
            String savedHistory = plugin.getConfig().getString(eggStatePath("history"));
            if (savedHistory != null) {
                return deserializeHistory(savedHistory);
            }
        }
        return getItemHistory(item);
    }

    private List<OwnerInfo> getItemHistory(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return List.of();
        }

        String serialized = meta.getPersistentDataContainer().get(historyKey, PersistentDataType.STRING);
        if (serialized == null || serialized.isEmpty()) {
            return List.of();
        }

        return deserializeHistory(serialized);
    }

    private List<OwnerInfo> deserializeHistory(String serialized) {
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
                    Integer remaining = plugin.getConfig().getInt(eggStatePath("remaining-seconds"), -1);
                    if (remaining < 0) {
                        remaining = meta.getPersistentDataContainer().get(eggRemainingKey, PersistentDataType.INTEGER);
                    }
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
            restoreDragonEggOwnership(item);
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
            restoreDragonEggOwnership(item);
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
            int remaining = Math.max(0, plugin.getConfig().getInt(eggStatePath("remaining-seconds"), EGG_SECONDS) - 1);
            plugin.getConfig().set(eggStatePath("remaining-seconds"), remaining);
            data.set(eggRemainingKey, PersistentDataType.INTEGER, remaining);
            item.setItemMeta(meta);
            if (remaining % 60 == 0) {
                plugin.saveConfig();
            }
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
        String holderId = plugin.getConfig().getString(eggStatePath("holder-id"));
        if (holderId == null) {
            holderId = data.get(eggHolderKey, PersistentDataType.STRING);
        }
        if (owner.id().equals(player.getUniqueId())) {
            boolean hadTimer = plugin.getConfig().contains(eggStatePath("holder-id"))
                    || plugin.getConfig().contains(eggStatePath("remaining-seconds"));
            if (hadTimer || data.has(eggHolderKey, PersistentDataType.STRING)
                    || data.has(eggRemainingKey, PersistentDataType.INTEGER)) {
                plugin.getConfig().set(eggStatePath("holder-id"), null);
                plugin.getConfig().set(eggStatePath("remaining-seconds"), null);
                data.remove(eggHolderKey);
                data.remove(eggRemainingKey);
                item.setItemMeta(meta);
                plugin.saveConfig();
            }
        } else {
            String currentHolderId = player.getUniqueId().toString();
            if (!currentHolderId.equals(holderId)) {
                plugin.getConfig().set(eggStatePath("holder-id"), currentHolderId);
                plugin.getConfig().set(eggStatePath("remaining-seconds"), EGG_SECONDS);
                data.set(eggHolderKey, PersistentDataType.STRING, currentHolderId);
                data.set(eggRemainingKey, PersistentDataType.INTEGER, EGG_SECONDS);
                item.setItemMeta(meta);
                plugin.saveConfig();
            } else {
                int remaining = plugin.getConfig().getInt(eggStatePath("remaining-seconds"),
                        data.getOrDefault(eggRemainingKey, PersistentDataType.INTEGER, EGG_SECONDS));
                plugin.getConfig().set(eggStatePath("remaining-seconds"), remaining);
                data.set(eggHolderKey, PersistentDataType.STRING, currentHolderId);
                data.set(eggRemainingKey, PersistentDataType.INTEGER, remaining);
                item.setItemMeta(meta);
            }
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
        plugin.getConfig().set(eggStatePath("owner-id"), holder.getUniqueId().toString());
        plugin.getConfig().set(eggStatePath("owner-name"), holder.getName());
        plugin.getConfig().set(eggStatePath("history"), serializeHistory(history));
        plugin.getConfig().set(eggStatePath("holder-id"), null);
        plugin.getConfig().set(eggStatePath("remaining-seconds"), null);
        plugin.saveConfig();

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

    public void initializeDragonEggStateFromPlacedRecords() {
        if (getSavedDragonEggOwner() != null) {
            return;
        }
        ConfigurationSection placedEggs = plugin.getConfig().getConfigurationSection("placed-dragon-eggs");
        if (placedEggs == null) {
            return;
        }
        for (String worldId : placedEggs.getKeys(false)) {
            ConfigurationSection worldSection = placedEggs.getConfigurationSection(worldId);
            if (worldSection == null) {
                continue;
            }
            for (String x : worldSection.getKeys(false)) {
                ConfigurationSection xSection = worldSection.getConfigurationSection(x);
                if (xSection == null) {
                    continue;
                }
                for (String y : xSection.getKeys(false)) {
                    ConfigurationSection ySection = xSection.getConfigurationSection(y);
                    if (ySection == null) {
                        continue;
                    }
                    for (String z : ySection.getKeys(false)) {
                        ItemStack savedEgg = ySection.getItemStack(z);
                        OwnerInfo owner = getItemOwner(savedEgg);
                        if (isDragonEgg(savedEgg) && owner != null) {
                            initializeDragonEggState(savedEgg, owner);
                            return;
                        }
                    }
                }
            }
        }
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

    public void restoreDragonEggOwnership(ItemStack item) {
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
        data.set(ownerIdKey, PersistentDataType.STRING, owner.id().toString());
        data.set(ownerNameKey, PersistentDataType.STRING, owner.name());
        String savedHistory = plugin.getConfig().getString(eggStatePath("history"));
        if (savedHistory == null) {
            savedHistory = serializeHistory(getItemHistory(item));
            plugin.getConfig().set(eggStatePath("history"), savedHistory);
            plugin.saveConfig();
        }
        data.set(historyKey, PersistentDataType.STRING, savedHistory);

        String holderId = plugin.getConfig().getString(eggStatePath("holder-id"));
        Integer remaining = plugin.getConfig().getInt(eggStatePath("remaining-seconds"), -1);
        if (holderId == null || remaining < 0) {
            data.remove(eggHolderKey);
            data.remove(eggRemainingKey);
        } else {
            data.set(eggHolderKey, PersistentDataType.STRING, holderId);
            data.set(eggRemainingKey, PersistentDataType.INTEGER, remaining);
        }
        item.setItemMeta(meta);
    }

    private OwnerInfo getSavedDragonEggOwner() {
        String id = plugin.getConfig().getString(eggStatePath("owner-id"));
        String name = plugin.getConfig().getString(eggStatePath("owner-name"));
        if (id == null || name == null) {
            return null;
        }
        try {
            return new OwnerInfo(UUID.fromString(id), name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void initializeDragonEggState(ItemStack item, OwnerInfo owner) {
        if (plugin.getConfig().contains(eggStatePath("owner-id"))) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        plugin.getConfig().set(eggStatePath("owner-id"), owner.id().toString());
        plugin.getConfig().set(eggStatePath("owner-name"), owner.name());
        plugin.getConfig().set(eggStatePath("history"),
                data.getOrDefault(historyKey, PersistentDataType.STRING, ""));
        String holderId = data.get(eggHolderKey, PersistentDataType.STRING);
        Integer remaining = data.get(eggRemainingKey, PersistentDataType.INTEGER);
        if (holderId != null && remaining != null) {
            plugin.getConfig().set(eggStatePath("holder-id"), holderId);
            plugin.getConfig().set(eggStatePath("remaining-seconds"), remaining);
        }
        plugin.saveConfig();
    }

    private void persistDragonEggOwner(ItemStack item) {
        OwnerInfo owner = getItemOwner(item);
        if (owner == null) {
            return;
        }
        plugin.getConfig().set(eggStatePath("owner-id"), owner.id().toString());
        plugin.getConfig().set(eggStatePath("owner-name"), owner.name());
        plugin.getConfig().set(eggStatePath("history"), serializeHistory(getItemHistory(item)));
        plugin.getConfig().set(eggStatePath("holder-id"), null);
        plugin.getConfig().set(eggStatePath("remaining-seconds"), null);
        plugin.saveConfig();
    }

    private String eggStatePath(String key) {
        return "dragon-egg." + key;
    }

    public record OwnerInfo(UUID id, String name) {
    }
}