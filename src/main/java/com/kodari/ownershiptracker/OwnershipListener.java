package com.kodari.ownershiptracker;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class OwnershipListener implements Listener {
    private final OwnershipTrackerPlugin plugin;
    private final OwnershipManager ownershipManager;
    private final TrackingManager trackingManager;
    private final Map<String, Long> recentWorkstationAlerts = new HashMap<>();

    public OwnershipListener(OwnershipTrackerPlugin plugin, OwnershipManager ownershipManager, TrackingManager trackingManager) {
        this.plugin = plugin;
        this.ownershipManager = ownershipManager;
        this.trackingManager = trackingManager;
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ownershipManager.claimIfUnowned(event.getCurrentItem(), player);
        plugin.getServer().getScheduler().runTask(plugin, () -> ownershipManager.refreshInventory(player));
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent event) {
        Item droppedItem = event.getItem();
        ownershipManager.claimIfUnowned(droppedItem.getItemStack(), event.getPlayer());
        plugin.getServer().getScheduler().runTask(plugin,
                () -> ownershipManager.refreshInventory(event.getPlayer()));
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            plugin.getServer().getScheduler().runTask(plugin, () -> ownershipManager.refreshInventory(player));
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (trackingManager.handleClick(event)) {
            return;
        }
        if (event.getWhoClicked() instanceof Player player) {
            plugin.getServer().getScheduler().runTask(plugin, () -> ownershipManager.refreshInventory(player));
            Inventory topInventory = event.getView().getTopInventory();
            String inventoryType = topInventory.getType().name();
            if (isPropertyWorkstation(inventoryType)) {
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> alertStolenItemWorkstationUse(player, topInventory, inventoryType));
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        trackingManager.handleDrag(event);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        trackingManager.handleClose(event);
        if (event.getPlayer() instanceof Player player) {
            ownershipManager.refreshInventory(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ownershipManager.refreshInventory(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ownershipManager.saveLocation(event.getPlayer());
        plugin.saveConfig();
    }

    private boolean isPropertyWorkstation(String inventoryType) {
        return inventoryType.equals("ANVIL")
                || inventoryType.equals("GRINDSTONE")
                || inventoryType.equals("SMITHING");
    }

    private void alertStolenItemWorkstationUse(Player user, Inventory workstation, String workstationType) {
        Location location = workstation.getLocation();
        if (location == null) {
            return;
        }

        boolean warnedUser = false;
        for (org.bukkit.inventory.ItemStack item : workstation.getContents()) {
            OwnershipManager.OwnerInfo owner = ownershipManager.getOwner(item);
            if (owner == null || owner.id().equals(user.getUniqueId())) {
                continue;
            }

            String alertKey = user.getUniqueId() + ":" + owner.id() + ":" + location.getBlockX() + ":"
                    + location.getBlockY() + ":" + location.getBlockZ() + ":" + workstationType;
            long now = System.currentTimeMillis();
            Long previousAlert = recentWorkstationAlerts.put(alertKey, now);
            if (previousAlert != null && now - previousAlert < 1000L) {
                continue;
            }

            Player originalOwner = Bukkit.getPlayer(owner.id());
            if (originalOwner != null && originalOwner.isOnline()) {
                originalOwner.sendMessage("§c" + user.getName() + " is using your item at §f"
                        + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + "§c.");
            }
            if (!warnedUser) {
                user.sendMessage("§cYou feel like you're being watched");
                warnedUser = true;
            }
        }
    }
}