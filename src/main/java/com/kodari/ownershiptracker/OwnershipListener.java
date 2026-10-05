package com.kodari.ownershiptracker;

import com.cryptomorin.xseries.XMaterial;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.event.EventPriority;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        ItemStack item = droppedItem.getItemStack();
        ownershipManager.restoreDragonEggOwnership(item);
        ownershipManager.claimIfUnowned(item, event.getPlayer());
        ownershipManager.restoreDragonEggOwnership(item);
        droppedItem.setItemStack(item);
        plugin.getServer().getScheduler().runTask(plugin,
                () -> ownershipManager.refreshInventory(event.getPlayer()));
    }

    @EventHandler
    public void onDragonEggItemSpawn(ItemSpawnEvent event) {
        ItemStack item = event.getEntity().getItemStack();
        if (!item.getType().name().equals("DRAGON_EGG")) {
            return;
        }
        ownershipManager.restoreDragonEggOwnership(item);
        event.getEntity().setItemStack(item);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEggPlace(BlockPlaceEvent event) {
        ItemStack placed = event.getItemInHand();
        if (!placed.getType().name().equals("DRAGON_EGG")) {
            return;
        }
        ownershipManager.restoreDragonEggOwnership(placed);
        ownershipManager.claimIfUnowned(placed, event.getPlayer());
        ownershipManager.restoreDragonEggOwnership(placed);
        ItemStack saved = placed.clone();
        saved.setAmount(1);
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
        event.setDropItems(false);
        Location location = event.getBlock().getLocation();
        boolean creative = event.getPlayer().getGameMode() == GameMode.CREATIVE;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (location.getBlock().getType().name().equals("DRAGON_EGG")) {
                return;
            }
            if (plugin.getConfig().getItemStack(path) == null) {
                return;
            }
            Location movedEgg = findNearbyUntrackedEgg(location);
            if (movedEgg != null) {
                moveEggRecord(location, movedEgg);
                return;
            }
            ItemStack current = plugin.getConfig().getItemStack(path);
            if (current == null) {
                return;
            }
            plugin.getConfig().set(path, null);
            plugin.saveConfig();
            if (!creative) {
                dropTrackedEgg(location, current);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDragonEggInteract(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (clicked == null || !clicked.getType().name().equals("DRAGON_EGG")) {
            return;
        }
        Location origin = clicked.getLocation();
        if (plugin.getConfig().getItemStack(eggBlockPath(origin)) == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (origin.getBlock().getType().name().equals("DRAGON_EGG")) {
                return;
            }
            Location movedEgg = findNearbyUntrackedEgg(origin);
            if (movedEgg != null) {
                moveEggRecord(origin, movedEgg);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        preserveExplodedEggs(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        preserveExplodedEggs(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEggTeleport(BlockFromToEvent event) {
        if (!event.getBlock().getType().name().equals("DRAGON_EGG")) {
            return;
        }
        moveEggRecord(event.getBlock().getLocation(), event.getToBlock().getLocation());
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

    private void preserveExplodedEggs(List<Block> affectedBlocks) {
        for (Block block : new ArrayList<>(affectedBlocks)) {
            if (!block.getType().name().equals("DRAGON_EGG")) {
                continue;
            }
            Location location = block.getLocation();
            if (plugin.getConfig().getItemStack(eggBlockPath(location)) == null) {
                continue;
            }
            affectedBlocks.remove(block);
            plugin.getServer().getScheduler().runTask(plugin, () -> removeExplodedEgg(location));
        }
    }

    private void removeExplodedEgg(Location location) {
        String path = eggBlockPath(location);
        ItemStack saved = plugin.getConfig().getItemStack(path);
        if (saved == null) {
            return;
        }
        if (location.getBlock().getType().name().equals("DRAGON_EGG")) {
            XMaterial.matchXMaterial("AIR").map(XMaterial::parseMaterial)
                    .ifPresent(air -> location.getBlock().setType(air, false));
        }
        plugin.getConfig().set(path, null);
        plugin.saveConfig();
        dropTrackedEgg(location, saved);
    }

    private void dropTrackedEgg(Location location, ItemStack item) {
        ItemStack dropped = item.clone();
        dropped.setAmount(1);
        location.getWorld().dropItemNaturally(location, dropped);
    }

    private void moveEggRecord(Location from, Location to) {
        String fromPath = eggBlockPath(from);
        Object saved = plugin.getConfig().get(fromPath);
        if (saved == null) {
            return;
        }
        plugin.getConfig().set(eggBlockPath(to), saved);
        plugin.getConfig().set(fromPath, null);
        plugin.saveConfig();
    }

    private Location findNearbyUntrackedEgg(Location origin) {
        int centerX = origin.getBlockX();
        int centerY = origin.getBlockY();
        int centerZ = origin.getBlockZ();
        Location found = null;
        for (int x = centerX - 16; x <= centerX + 16; x++) {
            for (int y = Math.max(origin.getWorld().getMinHeight(), centerY - 16);
                 y <= Math.min(origin.getWorld().getMaxHeight() - 1, centerY + 16); y++) {
                for (int z = centerZ - 16; z <= centerZ + 16; z++) {
                    if (!origin.getWorld().isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    Block candidate = origin.getWorld().getBlockAt(x, y, z);
                    if (!candidate.getType().name().equals("DRAGON_EGG")
                            || plugin.getConfig().getItemStack(eggBlockPath(candidate.getLocation())) != null) {
                        continue;
                    }
                    if (found != null) {
                        return null;
                    }
                    found = candidate.getLocation();
                }
            }
        }
        return found;
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