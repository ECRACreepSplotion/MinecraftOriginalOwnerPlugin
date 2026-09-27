package com.kodari.ownershiptracker;

import com.cryptomorin.xseries.XMaterial;
import com.cryptomorin.xseries.particles.XParticle;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TrackingManager {
    private static final int[] TRACKING_SLOTS = {2, 3, 4, 5, 6};

    private final JavaPlugin plugin;
    private final OwnershipManager ownershipManager;
    private final Map<UUID, Inventory> trackingInventories = new HashMap<>();
    private final Map<UUID, List<BukkitTask>> activeTasks = new HashMap<>();
    private final Map<UUID, Deque<TrackingRequest>> trackingQueues = new HashMap<>();
    private final Set<UUID> currentlyTracking = new HashSet<>();

    public TrackingManager(JavaPlugin plugin, OwnershipManager ownershipManager) {
        this.plugin = plugin;
        this.ownershipManager = ownershipManager;
    }

    public void openTracker(Player player) {
        Inventory inventory = Bukkit.createInventory(player, 9, "§4Track Stolen Items");
        ItemStack filler = createFiller();
        inventory.setItem(0, filler);
        inventory.setItem(1, filler);
        inventory.setItem(7, filler);
        inventory.setItem(8, filler);
        trackingInventories.put(player.getUniqueId(), inventory);
        player.openInventory(inventory);
    }

    public boolean handleClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return false;
        }

        Inventory tracker = trackingInventories.get(player.getUniqueId());
        if (tracker == null || !event.getView().getTopInventory().equals(tracker)) {
            return false;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot >= tracker.getSize()) {
            if (event.isShiftClick()) {
                moveShiftClickedItem(player, tracker, event);
            }
            return true;
        }

        if (rawSlot < 0 || !isTrackingSlot(rawSlot) || event.getClick().isKeyboardClick()) {
            event.setCancelled(true);
            return true;
        }

        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir() && !isTrackableBy(player, cursor)) {
            event.setCancelled(true);
            player.sendMessage("§cOnly stolen eligible equipment can be used for tracking.");
        }
        return true;
    }

    public void handleDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory tracker = trackingInventories.get(player.getUniqueId());
        if (tracker == null || !event.getView().getTopInventory().equals(tracker)) {
            return;
        }

        boolean touchesTracker = event.getRawSlots().stream().anyMatch(slot -> slot < tracker.getSize());
        if (!touchesTracker) {
            return;
        }

        boolean validSlots = event.getRawSlots().stream()
                .filter(slot -> slot < tracker.getSize())
                .allMatch(this::isTrackingSlot);
        if (!validSlots || !isTrackableBy(player, event.getOldCursor())) {
            event.setCancelled(true);
            player.sendMessage("§cOnly stolen eligible equipment can be used for tracking.");
        }
    }

    public void handleClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        Inventory tracker = trackingInventories.remove(player.getUniqueId());
        if (tracker == null || !event.getInventory().equals(tracker)) {
            return;
        }

        for (int slot : TRACKING_SLOTS) {
            ItemStack item = tracker.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }

            OwnershipManager.OwnerInfo owner = ownershipManager.getOwner(item);
            if (owner == null || owner.id().equals(player.getUniqueId())) {
                returnItem(player, item);
                continue;
            }

            int durationSeconds = getTrackingDurationSeconds(item);
            consumeOne(item);
            if (!item.getType().isAir()) {
                returnItem(player, item);
            }
            trackingQueues.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>())
                    .addLast(new TrackingRequest(owner, durationSeconds));
        }
        startNextTracking(player.getUniqueId());
    }

    public void stopAll() {
        for (List<BukkitTask> tasks : activeTasks.values()) {
            for (BukkitTask task : tasks) {
                task.cancel();
            }
        }
        activeTasks.clear();
        trackingQueues.clear();
        currentlyTracking.clear();
    }

    private void startNextTracking(UUID trackerId) {
        if (currentlyTracking.contains(trackerId)) {
            return;
        }

        Deque<TrackingRequest> queue = trackingQueues.get(trackerId);
        if (queue == null || queue.isEmpty()) {
            trackingQueues.remove(trackerId);
            return;
        }

        Player tracker = Bukkit.getPlayer(trackerId);
        if (tracker == null || !tracker.isOnline()) {
            return;
        }

        TrackingRequest request = queue.removeFirst();
        OwnershipManager.OwnerInfo owner = request.owner();
        if (queue.isEmpty()) {
            trackingQueues.remove(trackerId);
        }

        Vector initialDirection = snapshotDirection(tracker, owner.id());
        if (initialDirection == null) {
            tracker.sendMessage("§cNo last known position is available for " + owner.name() + ".");
            startNextTracking(trackerId);
            return;
        }

        Vector[] direction = {initialDirection};
        long[] elapsedTicks = {0L};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!tracker.isOnline()) {
                return;
            }
            if (elapsedTicks[0] > 0 && elapsedTicks[0] % 1200L == 0) {
                Vector updatedDirection = snapshotDirection(tracker, owner.id());
                if (updatedDirection != null) {
                    direction[0] = updatedDirection;
                }
            }
            drawDirection(tracker.getLocation(), direction[0]);
            elapsedTicks[0] += 10L;
        }, 0L, 10L);

        currentlyTracking.add(trackerId);
        activeTasks.computeIfAbsent(trackerId, ignored -> new ArrayList<>()).add(task);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            task.cancel();
            List<BukkitTask> tasks = activeTasks.get(trackerId);
            if (tasks != null) {
                tasks.remove(task);
                if (tasks.isEmpty()) {
                    activeTasks.remove(trackerId);
                }
            }
            currentlyTracking.remove(trackerId);
            startNextTracking(trackerId);
        }, request.durationSeconds() * 20L);
        tracker.sendMessage("§cTracking " + owner.name() + " for " + request.durationSeconds()
                + " seconds using their latest position.");
    }

    private Vector snapshotDirection(Player tracker, UUID ownerId) {
        Location target = ownershipManager.getLastKnownLocation(ownerId);
        if (target == null) {
            return null;
        }
        Location origin = tracker.getLocation();
        Vector direction = target.toVector().subtract(origin.toVector()).setY(0);
        if (direction.lengthSquared() < 0.0001D) {
            direction = origin.getDirection().setY(0);
        }
        return direction.normalize();
    }

    private void drawDirection(Location start, Vector direction) {
        XParticle.of("DUST").ifPresent(particle -> {
            Vector step = direction.clone().multiply(0.5D);
            Location point = start.clone().add(0D, 1.0D, 0D);
            for (int index = 0; index < 16; index++) {
                point.add(step);
                start.getWorld().spawnParticle(particle.get(), point, 1, 0D, 0D, 0D, 0D,
                        new Particle.DustOptions(Color.RED, 1.4F));
            }
        });
    }

    private ItemStack createFiller() {
        ItemStack item = XMaterial.matchXMaterial("BLACK_STAINED_GLASS_PANE")
                .map(XMaterial::parseItem)
                .orElse(null);
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§8Place stolen equipment in the five open slots");
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isTrackingSlot(int slot) {
        for (int trackingSlot : TRACKING_SLOTS) {
            if (trackingSlot == slot) {
                return true;
            }
        }
        return false;
    }

    private void moveShiftClickedItem(Player player, Inventory tracker, InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if (!isTrackableBy(player, item)) {
            event.setCancelled(true);
            player.sendMessage("§cOnly stolen eligible equipment can be used for tracking.");
            return;
        }

        for (int slot : TRACKING_SLOTS) {
            ItemStack existing = tracker.getItem(slot);
            if (existing != null && !existing.getType().isAir()) {
                continue;
            }
            event.setCancelled(true);
            tracker.setItem(slot, item.clone());
            event.setCurrentItem(null);
            return;
        }

        event.setCancelled(true);
        player.sendMessage("§cAll five tracking slots are full.");
    }

    private boolean isTrackableBy(Player player, ItemStack item) {
        OwnershipManager.OwnerInfo owner = ownershipManager.getOwner(item);
        return owner != null && !owner.id().equals(player.getUniqueId());
    }

    private int getTrackingDurationSeconds(ItemStack item) {
        String type = item.getType().name();
        int baseDuration;
        if (type.equals("TRIDENT")) {
            baseDuration = 120;
        } else if (type.equals("MACE")) {
            baseDuration = 300;
        } else if (type.equals("ELYTRA")) {
            baseDuration = 180;
        } else if (type.equals("SHIELD")) {
            baseDuration = 30;
        } else if (type.startsWith("NETHERITE_")) {
            baseDuration = 60;
        } else if (type.startsWith("DIAMOND_")) {
            baseDuration = 35;
        } else if (type.startsWith("IRON_")) {
            baseDuration = 25;
        } else if (type.startsWith("COPPER_")) {
            baseDuration = 20;
        } else if (type.startsWith("STONE_")) {
            baseDuration = 15;
        } else if (type.startsWith("WOODEN_")) {
            baseDuration = 10;
        } else {
            baseDuration = 60;
        }
        return baseDuration + item.getEnchantments().size() * 10;
    }

    private void consumeOne(ItemStack item) {
        if (item.getAmount() <= 1) {
            item.setAmount(0);
            return;
        }
        item.setAmount(item.getAmount() - 1);
    }

    private void returnItem(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private record TrackingRequest(OwnershipManager.OwnerInfo owner, int durationSeconds) {
    }
}