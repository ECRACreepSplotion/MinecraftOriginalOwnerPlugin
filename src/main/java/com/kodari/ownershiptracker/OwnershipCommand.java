package com.kodari.ownershiptracker;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OwnershipCommand implements CommandExecutor, TabCompleter {
    private final OwnershipManager ownershipManager;
    private final TrackingManager trackingManager;
    private final Map<UUID, PendingTransfer> pendingTransfers = new HashMap<>();

    public OwnershipCommand(OwnershipManager ownershipManager, TrackingManager trackingManager) {
        this.ownershipManager = ownershipManager;
        this.trackingManager = trackingManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("§e/ownership transfer <player> §7- transfer your held item");
            player.sendMessage("§e/ownership accept|reject §7- respond to an ownership transfer");
            player.sendMessage("§e/ownership history §7- view previous owners of your held item");
            player.sendMessage("§e/ownership track §7- track owners using stolen equipment");
            player.sendMessage("§e/ownership values §7- view item tracking durations");
            return true;
        }

        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (args[0].equalsIgnoreCase("transfer")) {
            if (args.length != 2) {
                player.sendMessage("§cUsage: /ownership transfer <player>");
                return true;
            }
            Player recipient = Bukkit.getPlayerExact(args[1]);
            if (recipient == null) {
                player.sendMessage("§cThat player must be online to receive ownership.");
                return true;
            }
            if (recipient.equals(player)) {
                player.sendMessage("§cYou already own this item.");
                return true;
            }
            OwnershipManager.OwnerInfo owner = ownershipManager.getOwner(heldItem);
            if (owner == null || !owner.id().equals(player.getUniqueId())) {
                player.sendMessage("§cYou can only transfer an eligible item that you currently own.");
                return true;
            }
            String itemId = ownershipManager.getOrCreateItemId(heldItem);
            if (itemId == null) {
                player.sendMessage("§cThat item cannot be transferred.");
                return true;
            }

            PendingTransfer previous = pendingTransfers.put(recipient.getUniqueId(), new PendingTransfer(
                    player.getUniqueId(), player.getName(), recipient.getUniqueId(), itemId, System.currentTimeMillis() + 180_000L));
            if (previous != null) {
                Player previousSender = Bukkit.getPlayer(previous.senderId());
                if (previousSender != null) {
                    previousSender.sendMessage("§eYour pending ownership transfer was replaced by a new request.");
                }
            }
            player.sendMessage("§eTransfer request sent to " + recipient.getName() + ". It expires in 3 minutes.");
            recipient.sendMessage("§e" + player.getName() + " wants to transfer ownership of their held item to you.");
            recipient.sendMessage("§a/ownership accept §7or §c/ownership reject");
            Bukkit.getScheduler().runTaskLater(ownershipManager.getPlugin(), () -> expireTransfer(recipient.getUniqueId(), itemId), 3600L);
            return true;
        }

        if (args[0].equalsIgnoreCase("accept")) {
            acceptTransfer(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("reject")) {
            PendingTransfer request = pendingTransfers.remove(player.getUniqueId());
            if (request == null || request.expiresAt() <= System.currentTimeMillis()) {
                player.sendMessage("§cYou have no pending ownership transfer request.");
                return true;
            }
            player.sendMessage("§eYou rejected the ownership transfer.");
            Player senderPlayer = Bukkit.getPlayer(request.senderId());
            if (senderPlayer != null) {
                senderPlayer.sendMessage("§c" + player.getName() + " rejected your ownership transfer.");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("history")) {
            List<OwnershipManager.OwnerInfo> history = ownershipManager.getHistory(heldItem);
            if (ownershipManager.getOwner(heldItem) == null) {
                player.sendMessage("§cYour held item has no ownership record.");
            } else if (history.isEmpty()) {
                player.sendMessage("§eThis item has never been transferred.");
            } else {
                player.sendMessage("§6Previous owners:");
                for (OwnershipManager.OwnerInfo owner : history) {
                    player.sendMessage("§7- §f" + owner.name());
                }
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("track")) {
            trackingManager.openTracker(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("values")) {
            player.sendMessage("§6Tracking durations per item:");
            player.sendMessage("§eWooden §7- 10s  §eStone §7- 15s  §eCopper §7- 20s");
            player.sendMessage("§eIron §7- 25s  §eShield §7- 30s  §eDiamond §7- 35s");
            player.sendMessage("§eNetherite §7- 60s  §eTrident §7- 2m  §eElytra §7- 3m  §eMace §7- 5m");
            player.sendMessage("§eOther eligible equipment §7- 60s");
            player.sendMessage("§a+10 seconds per distinct enchantment (not per level).");
            return true;
        }

        player.sendMessage("§cUsage: /ownership <transfer|accept|reject|history|track|values>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("transfer", "accept", "reject", "history", "track", "values");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("transfer")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }

    private void acceptTransfer(Player recipient) {
        PendingTransfer request = pendingTransfers.remove(recipient.getUniqueId());
        if (request == null || request.expiresAt() <= System.currentTimeMillis()) {
            recipient.sendMessage("§cYou have no pending ownership transfer request.");
            return;
        }

        Player sender = Bukkit.getPlayer(request.senderId());
        if (sender == null || !sender.isOnline()) {
            recipient.sendMessage("§cThe sender is no longer online. The transfer was cancelled.");
            return;
        }

        ItemStack heldItem = sender.getInventory().getItemInMainHand();
        if (!ownershipManager.hasItemId(heldItem, request.itemId()) || !ownershipManager.transfer(heldItem, sender, recipient)) {
            recipient.sendMessage("§cThe sender is no longer holding the requested item. The transfer was cancelled.");
            sender.sendMessage("§cYour ownership transfer was cancelled because you are no longer holding that item.");
            return;
        }

        recipient.sendMessage("§aYou accepted ownership of " + sender.getName() + "'s item.");
        sender.sendMessage("§a" + recipient.getName() + " accepted your ownership transfer.");
    }

    private void expireTransfer(UUID recipientId, String itemId) {
        PendingTransfer request = pendingTransfers.get(recipientId);
        if (request == null || !request.itemId().equals(itemId) || request.expiresAt() > System.currentTimeMillis()) {
            return;
        }

        pendingTransfers.remove(recipientId);
        Player recipient = Bukkit.getPlayer(recipientId);
        Player sender = Bukkit.getPlayer(request.senderId());
        if (recipient != null) {
            recipient.sendMessage("§eThe ownership transfer request expired.");
        }
        if (sender != null) {
            sender.sendMessage("§eYour ownership transfer request expired without a response.");
        }
    }

    private record PendingTransfer(UUID senderId, String senderName, UUID recipientId, String itemId, long expiresAt) {
    }
}