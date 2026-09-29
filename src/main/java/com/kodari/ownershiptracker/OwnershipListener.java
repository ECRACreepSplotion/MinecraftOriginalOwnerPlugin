package com.kodari.ownershiptracker;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
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
import org.bukkit.inventory.ItemStack;

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEggPlace(BlockPlaceEvent event) {
        ItemStack placed = event.getItemInHand();
        if (!placed.getType().name().equals("DRAGON_EGG")) {
            return;
        }
        ItemStack saved = placed.clone();
        saved.setAmount(1);
        ownershipManager.claimIfUnowned(saved, event.getPlayer());
        plugin.getConfig().set(eggBlockPath(event.getBlock().getLocation()), saved);
        plugin.saveConfig();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEggBreak(BlockBreakEvent event) {
        if (!event.getBlock().getType().name().equals("DRAGON_EGG")) {
            return;
        }
        String path = eggBlockPath(event.getBlock().getLocation());
        ItemStack saved = plugin.getConfig().getItemStack(path);
        if (saved == null) {
            return;
        }
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            event.setDropItems(false);
        }
        Location location = event.getBlock().getLocation();
        boolean creative = event.getPlayer().getGameMode() == GameMode.CREATIVE;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (location.getBlock().getType().name().equals("DRAGON_EGG")) {
                return;
            }
            plugin.getConfig().set(path, null);
            plugin.saveConfig();
            if (!creative) {
                location.getWorld().dropItemNaturally(location, saved);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEggTeleport(BlockFromToEvent event) {
        if (!event.getBlock().getType().name().equals("DRAGON_EGG")) {
            return;
        }
        String from = eggBlockPath(event.getBlock().getLocation());
        Object saved = plugin.getConfig().get(from);
        if (saved != null) {
            plugin.getConfig().set(eggBlockPath(event.getToBlock().getLocation()), saved);
            plugin.getConfig().set(from, null);
            plugin.saveConfig();
        }
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
        ownershipManager.notifyPendingEggLoss(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ownershipManager.saveLocation(event.getPlayer());
        plugin.saveConfig();
    }

    private String eggBlockPath(Location location) {
        return "placed-dragon-eggs." + location.getWorld().getUID() + "."
                + location.getBlockX() + "." + location.getBlockY() + "." + location.getBlockZ();
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