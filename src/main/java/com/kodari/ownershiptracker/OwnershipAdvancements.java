package com.kodari.ownershiptracker;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class OwnershipAdvancements implements Listener {
    private final OwnershipTrackerPlugin plugin;
    private final OwnershipManager ownership;
    private final NamespacedKey itemKey;
    private final NamespacedKey root;
    private final NamespacedKey ten;
    private final NamespacedKey hundred;
    private final NamespacedKey steal;
    private final NamespacedKey stealTen;
    private final NamespacedKey stealHundred;
    private final List<NamespacedKey> collectionKeys = new ArrayList<>();
    private final List<NamespacedKey> categoryKeys = new ArrayList<>();
    private final Set<String> eligibleTypes = new HashSet<>();
    private final List<Set<String>> categories = new ArrayList<>();
    private static final int[] COLLECTION_THRESHOLDS = {5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 78};

    public OwnershipAdvancements(OwnershipTrackerPlugin plugin, OwnershipManager ownership) {
        this.plugin = plugin;
        this.ownership = ownership;
        itemKey = new NamespacedKey(plugin, "advancement-item-id");
        root = new NamespacedKey(plugin, "own_an_asset");
        ten = new NamespacedKey(plugin, "tenfold");
        hundred = new NamespacedKey(plugin, "tenfold_again");
        steal = new NamespacedKey(plugin, "steal_an_asset");
        stealTen = new NamespacedKey(plugin, "stealing_tenfold");
        stealHundred = new NamespacedKey(plugin, "stealing_tenfold_again");
        register(root, null, "Own an Asset", "Own your first item", "minecraft:wooden_sword", "task");
        register(ten, root, "Tenfold!", "Own 10 different items", "minecraft:iron_sword", "task");
        register(hundred, ten, "Tenfold! Again!", "Own 100 different items", "minecraft:diamond_sword", "challenge");
        register(steal, root, "Steal an Asset", "Have a stolen item in your inventory", "minecraft:wooden_sword", "task");
        register(stealTen, steal, "Stealing Tenfold!", "Acquire stolen items 10 times", "minecraft:iron_sword", "task");
        register(stealHundred, stealTen, "Stealing Tenfold! Again!", "Acquire stolen items 100 times", "minecraft:diamond_sword", "challenge");
        String[] names = {"Asset Collector", "Serious Asset Collector", "Asset Accumulator", "Asset Amasser",
                "Asset Accrual", "Asset Accretion", "Asset Acquisition", "Asset Array", "Asset Assembly",
                "Asset Aggregation", "Asset Album", "Asset Arsenal", "Asset Agglomeration", "Asset Anthology",
                "Asset Assortment", "Asset Archivist"};
        NamespacedKey parent = root;
        for (int i = 0; i < names.length; i++) {
            NamespacedKey key = new NamespacedKey(plugin, names[i].toLowerCase(java.util.Locale.ROOT).replace(' ', '_'));
            register(key, parent, names[i], "Own " + COLLECTION_THRESHOLDS[i] + " unique asset types",
                    "minecraft:chest", i < 9 ? "task" : "challenge");
            collectionKeys.add(key);
            parent = key;
        }
        NamespacedKey all = new NamespacedKey(plugin, "you_are_the_asset");
        register(all, parent, "You Are The Asset!", "Own every unique asset, including the Dragon Egg", "minecraft:dragon_egg", "challenge");
        collectionKeys.add(all);

        String[] categoryNames = {"The Greatest Haul!", "The Sharpest Of Points!", "The Best Of Protection", "Out Of This World"};
        String[] categoryIds = {"the_greatest_haul", "the_sharpest_of_points", "the_best_of_protection", "out_of_this_world"};
        String[] categoryDescriptions = {"Own every unique tool and weapon", "Own every unique spear",
                "Own every unique armor piece", "Own every unique other asset"};
        String[] icons = {"minecraft:diamond_pickaxe", "minecraft:diamond_sword", "minecraft:diamond_chestplate", "minecraft:elytra"};
        parent = collectionKeys.get(0);
        for (int i = 0; i < categoryNames.length; i++) {
            NamespacedKey key = new NamespacedKey(plugin, categoryIds[i]);
            register(key, parent, categoryNames[i], categoryDescriptions[i], icons[i], "challenge");
            categoryKeys.add(key);
            categories.add(new HashSet<>());
            parent = key;
        }
        for (Material material : Material.values()) {
            if (!material.isItem() || !ownership.isEligible(new ItemStack(material))) continue;
            String type = material.name();
            eligibleTypes.add(type);
            if (type.matches(".+_(SWORD|PICKAXE|AXE|SHOVEL|HOE)$")) categories.get(0).add(type);
            else if (type.equals("SPEAR") || type.endsWith("_SPEAR")) categories.get(1).add(type);
            else if (type.matches(".+_(HELMET|CHESTPLATE|LEGGINGS|BOOTS)$")) categories.get(2).add(type);
            else categories.get(3).add(type);
        }
    }

    @SuppressWarnings("deprecation")
    private void register(NamespacedKey key, NamespacedKey parent, String title, String description, String icon, String frame) {
        if (Bukkit.getAdvancement(key) != null) {
            return;
        }
        String hierarchy = parent == null ? "" : "\"parent\":\"" + parent + "\",";
        String background = parent == null ? "\"background\":\"minecraft:textures/block/stone.png\"," : "";
        String json = "{" + hierarchy + "\"display\":{" + background
                + "\"icon\":{\"id\":\"" + icon + "\"},"
                + "\"title\":{\"text\":\"" + title + "\"},"
                + "\"description\":{\"text\":\"" + description + "\"},"
                + "\"frame\":\"" + frame + "\",\"show_toast\":true,\"announce_to_chat\":true,\"hidden\":false},"
                + "\"criteria\":{\"owned\":{\"trigger\":\"minecraft:impossible\"}}}";
        Bukkit.getUnsafe().loadAdvancement(key, json);
    }

    public void observeInventory(Player holder) {
        boolean changed = false;
        String holderId = holder.getUniqueId().toString();
        String heldPath = "advancement-stolen-held." + holderId;
        Set<String> previous = new HashSet<>(plugin.getConfig().getStringList(heldPath));
        Set<String> current = new HashSet<>();
        String countedPath = "advancement-stolen-awaiting-return." + holderId;
        Set<String> awaitingReturn = new HashSet<>(plugin.getConfig().getStringList(countedPath));
        if (!plugin.getConfig().isSet(countedPath)) {
            awaitingReturn.addAll(previous);
            awaitingReturn.addAll(plugin.getConfig().getStringList("advancement-stolen-items." + holderId));
            plugin.getConfig().set(countedPath, new ArrayList<>(awaitingReturn));
            changed = true;
        }
        String countPath = "advancement-stolen-count." + holderId;
        if (!plugin.getConfig().isSet(countPath)) {
            previous.addAll(plugin.getConfig().getStringList("advancement-stolen-items." + holderId));
        }
        int stolenCount = plugin.getConfig().getInt(countPath,
                plugin.getConfig().getStringList("advancement-stolen-items." + holderId).size());
        ItemStack[] contents = holder.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            OwnershipManager.OwnerInfo owner = ownership.getOwner(item);
            if (owner == null) {
                continue;
            }
            String typesPath = "advancement-owned-types." + owner.id();
            List<String> types = plugin.getConfig().getStringList(typesPath);
            String type = item.getType().name();
            if (!types.contains(type)) {
                types.add(type);
                plugin.getConfig().set(typesPath, types);
                changed = true;
            }
            String path = "advancement-owned-items." + owner.id();
            List<String> recorded = plugin.getConfig().getStringList(path);
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                continue;
            }
            String id = meta.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
            if (id == null) {
                id = item.getType().name().equals("DRAGON_EGG") ? "dragon-egg" : UUID.randomUUID().toString();
                meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);
                item.setItemMeta(meta);
                holder.getInventory().setItem(slot, item);
            }
            if (recorded.size() < 100 && !recorded.contains(id)) {
                recorded.add(id);
                plugin.getConfig().set(path, recorded);
                changed = true;
            }
            if (!owner.id().equals(holder.getUniqueId())) {
                current.add(id);
                if (awaitingReturn.add(id)) {
                    stolenCount = Math.min(100, stolenCount + 1);
                    plugin.getConfig().set(countedPath, new ArrayList<>(awaitingReturn));
                    changed = true;
                }
            } else {
                org.bukkit.configuration.ConfigurationSection pending = plugin.getConfig().getConfigurationSection("advancement-stolen-awaiting-return");
                if (pending != null) {
                    for (String playerId : pending.getKeys(false)) {
                        String playerPath = "advancement-stolen-awaiting-return." + playerId;
                        List<String> items = plugin.getConfig().getStringList(playerPath);
                        if (items.remove(id)) {
                            plugin.getConfig().set(playerPath, items);
                            if (playerId.equals(holderId)) awaitingReturn.remove(id);
                            changed = true;
                        }
                    }
                }
            }
        }
        if (!current.equals(previous)) {
            plugin.getConfig().set(heldPath, new ArrayList<>(current));
            changed = true;
        }
        if (!plugin.getConfig().isSet(countPath) || plugin.getConfig().getInt(countPath) != stolenCount) {
            plugin.getConfig().set(countPath, stolenCount);
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
        }
    }

    public void awardOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            int count = plugin.getConfig().getStringList("advancement-owned-items." + player.getUniqueId()).size();
            if (count >= 1) award(player, root);
            if (count >= 10) award(player, ten);
            if (count >= 100) award(player, hundred);
            int stolenCount = plugin.getConfig().getInt("advancement-stolen-count." + player.getUniqueId(),
                    plugin.getConfig().getStringList("advancement-stolen-items." + player.getUniqueId()).size());
            if (stolenCount >= 1) award(player, steal);
            if (stolenCount >= 10) award(player, stealTen);
            if (stolenCount >= 100) award(player, stealHundred);
            Set<String> types = new HashSet<>(plugin.getConfig().getStringList("advancement-owned-types." + player.getUniqueId()));
            types.retainAll(eligibleTypes);
            for (int i = 0; i < COLLECTION_THRESHOLDS.length; i++) {
                if (types.size() >= COLLECTION_THRESHOLDS[i]) award(player, collectionKeys.get(i));
            }
            if (!eligibleTypes.isEmpty() && types.containsAll(eligibleTypes)) {
                award(player, collectionKeys.get(COLLECTION_THRESHOLDS.length));
            }
            for (int i = 0; i < categories.size(); i++) {
                if (!categories.get(i).isEmpty() && types.containsAll(categories.get(i))) award(player, categoryKeys.get(i));
            }
        }
    }

    private void observeNextTick(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                observeInventory(player);
                awardOnlinePlayers();
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent event) {
        observeNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        observeNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) observeNextTick(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) observeNextTick(player);
    }

    private void award(Player player, NamespacedKey key) {
        Advancement advancement = Bukkit.getAdvancement(key);
        if (advancement == null) {
            return;
        }
        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        for (String criterion : progress.getRemainingCriteria()) {
            progress.awardCriteria(criterion);
        }
    }
}