package com.kodari.ownershiptracker;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class OwnershipTrackerPlugin extends JavaPlugin {
    private OwnershipManager ownershipManager;
    private TrackingManager trackingManager;

    public OwnershipManager getOwnershipManager() {
        return ownershipManager;
    }

    @Override
    public void onEnable() {
        ownershipManager = new OwnershipManager(this);
        ownershipManager.initializeDragonEggStateFromPlacedRecords();
        trackingManager = new TrackingManager(this, ownershipManager);

        Bukkit.getPluginManager().registerEvents(new OwnershipListener(this, ownershipManager, trackingManager), this);

        OwnershipCommand ownershipCommand = new OwnershipCommand(ownershipManager, trackingManager);
        PluginCommand command = Objects.requireNonNull(getCommand("ownership"));
        command.setExecutor(ownershipCommand);
        command.setTabCompleter(ownershipCommand);

        Bukkit.getScheduler().runTaskTimer(this, ownershipManager::saveOnlineLocations, 20L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                ownershipManager.refreshInventory(player);
                ownershipManager.tickDragonEggInventory(player);
            }
        }, 20L, 20L);
    }

    @Override
    public void onDisable() {
        ownershipManager.saveOnlineLocations();
        saveConfig();
        trackingManager.stopAll();
    }
}