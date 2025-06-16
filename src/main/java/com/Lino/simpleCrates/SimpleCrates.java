package com.Lino.simpleCrates;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class SimpleCrates extends JavaPlugin implements Listener {

    private final Map<String, List<ItemStack>> crateRewards = new HashMap<>();
    private final Map<String, Map<Integer, Double>> crateChances = new HashMap<>();
    private final Map<UUID, String> crateCreators = new HashMap<>();
    private final Map<Location, String> placedCrates = new HashMap<>();
    private final Map<Location, ArmorStand> crateHolograms = new HashMap<>();
    private final Map<UUID, Long> openCooldowns = new HashMap<>();
    private final Set<UUID> animatingPlayers = new HashSet<>();

    private FileConfiguration cratesConfig;
    private File cratesFile;

    private NamespacedKey crateKey;
    private NamespacedKey crateTypeKey;
    private NamespacedKey keyTypeKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadCratesConfig();

        crateKey = new NamespacedKey(this, "is_crate");
        crateTypeKey = new NamespacedKey(this, "crate_type");
        keyTypeKey = new NamespacedKey(this, "key_type");

        getServer().getPluginManager().registerEvents(this, this);
        loadCrates();
        loadPlacedCrates();
    }

    @Override
    public void onDisable() {
        savePlacedCrates();
        removeAllHolograms();
    }

    private void loadCratesConfig() {
        cratesFile = new File(getDataFolder(), "crates.yml");
        if (!cratesFile.exists()) {
            cratesFile.getParentFile().mkdirs();
            saveResource("crates.yml", false);
        }
        cratesConfig = YamlConfiguration.loadConfiguration(cratesFile);
    }

    private void saveCratesConfig() {
        try {
            cratesConfig.save(cratesFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadCrates() {
        ConfigurationSection cratesSection = getConfig().getConfigurationSection("crates");
        if (cratesSection == null) return;

        for (String crateName : cratesSection.getKeys(false)) {
            ConfigurationSection crateSection = cratesSection.getConfigurationSection(crateName);
            if (crateSection == null) continue;

            List<ItemStack> rewards = new ArrayList<>();
            Map<Integer, Double> chances = new HashMap<>();

            ConfigurationSection rewardsSection = crateSection.getConfigurationSection("rewards");
            if (rewardsSection != null) {
                for (String key : rewardsSection.getKeys(false)) {
                    ConfigurationSection rewardSection = rewardsSection.getConfigurationSection(key);
                    if (rewardSection == null) continue;

                    ItemStack item = rewardSection.getItemStack("item");
                    double chance = rewardSection.getDouble("chance", 10.0);

                    if (item != null) {
                        rewards.add(item);
                        chances.put(rewards.size() - 1, chance);
                    }
                }
            }

            crateRewards.put(crateName, rewards);
            crateChances.put(crateName, chances);
        }
    }

    private void loadPlacedCrates() {
        ConfigurationSection placedSection = cratesConfig.getConfigurationSection("placed");
        if (placedSection == null) return;

        for (String key : placedSection.getKeys(false)) {
            ConfigurationSection locSection = placedSection.getConfigurationSection(key);
            if (locSection == null) continue;

            World world = Bukkit.getWorld(locSection.getString("world", ""));
            if (world == null) continue;

            Location loc = new Location(
                    world,
                    locSection.getDouble("x"),
                    locSection.getDouble("y"),
                    locSection.getDouble("z")
            );

            String crateType = locSection.getString("type");
            if (crateType != null) {
                placedCrates.put(loc, crateType);
                createHologram(loc, crateType);
            }
        }
    }

    private void savePlacedCrates() {
        cratesConfig.set("placed", null);
        ConfigurationSection placedSection = cratesConfig.createSection("placed");

        int index = 0;
        for (Map.Entry<Location, String> entry : placedCrates.entrySet()) {
            Location loc = entry.getKey();
            ConfigurationSection locSection = placedSection.createSection(String.valueOf(index++));

            locSection.set("world", loc.getWorld().getName());
            locSection.set("x", loc.getX());
            locSection.set("y", loc.getY());
            locSection.set("z", loc.getZ());
            locSection.set("type", entry.getValue());
        }

        saveCratesConfig();
    }

    private void createHologram(Location loc, String crateName) {
        Location hologramLoc = loc.clone().add(0.5, 2.5, 0.5);

        ArmorStand hologram = loc.getWorld().spawn(hologramLoc, ArmorStand.class);
        hologram.setCustomName(ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("hologram-format", "&6&l%crate%").replace("%crate%", crateName)));
        hologram.setCustomNameVisible(true);
        hologram.setGravity(false);
        hologram.setVisible(false);
        hologram.setInvulnerable(true);
        hologram.setMarker(true);

        crateHolograms.put(loc, hologram);
    }

    private void removeHologram(Location loc) {
        ArmorStand hologram = crateHolograms.remove(loc);
        if (hologram != null && !hologram.isDead()) {
            hologram.remove();
        }
    }

    private void removeAllHolograms() {
        for (ArmorStand hologram : crateHolograms.values()) {
            if (hologram != null && !hologram.isDead()) {
                hologram.remove();
            }
        }
        crateHolograms.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("crate")) return false;

        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /crate <create|give|list|delete|reload|preview>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + "Only players can use this command!");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /crate create <name>");
                    return true;
                }
                if (!sender.hasPermission("simplecrates.create")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to create crates!");
                    return true;
                }
                createCrateGUI((Player) sender, args[1]);
                break;

            case "give":
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /crate give <player> <crate> [amount]");
                    return true;
                }
                if (!sender.hasPermission("simplecrates.give")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to give crate keys!");
                    return true;
                }
                giveKey(sender, args);
                break;

            case "list":
                if (!sender.hasPermission("simplecrates.list")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to list crates!");
                    return true;
                }
                listCrates(sender);
                break;

            case "delete":
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /crate delete <name>");
                    return true;
                }
                if (!sender.hasPermission("simplecrates.delete")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to delete crates!");
                    return true;
                }
                deleteCrate(sender, args[1]);
                break;

            case "reload":
                if (!sender.hasPermission("simplecrates.reload")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to reload the config!");
                    return true;
                }
                reloadConfigs();
                sender.sendMessage(ChatColor.GREEN + "Configuration reloaded!");
                break;

            case "preview":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + "Only players can use this command!");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /crate preview <name>");
                    return true;
                }
                if (!sender.hasPermission("simplecrates.preview")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to preview crates!");
                    return true;
                }
                previewCrate((Player) sender, args[1]);
                break;

            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand!");
        }

        return true;
    }

    private void createCrateGUI(Player player, String crateName) {
        Inventory gui = Bukkit.createInventory(null, 54, "Create Crate: " + crateName);

        ItemStack confirmButton = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta confirmMeta = confirmButton.getItemMeta();
        confirmMeta.setDisplayName(ChatColor.GREEN + "Confirm");
        confirmMeta.setLore(Arrays.asList(ChatColor.GRAY + "Click to save this crate"));
        confirmButton.setItemMeta(confirmMeta);

        gui.setItem(49, confirmButton);

        crateCreators.put(player.getUniqueId(), crateName);
        player.openInventory(gui);
    }

    private void giveKey(CommandSender sender, String[] args) {
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found!");
            return;
        }

        String crateName = args[2];
        if (!crateRewards.containsKey(crateName)) {
            sender.sendMessage(ChatColor.RED + "Crate not found!");
            return;
        }

        int amount = 1;
        if (args.length > 3) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid amount!");
                return;
            }
        }

        ItemStack key = new ItemStack(Material.TRIPWIRE_HOOK, amount);
        ItemMeta meta = key.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + crateName + " Key");
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "Right-click a " + crateName + " crate",
                ChatColor.GRAY + "to open it!"
        ));
        meta.getPersistentDataContainer().set(keyTypeKey, PersistentDataType.STRING, crateName);
        key.setItemMeta(meta);

        target.getInventory().addItem(key);
        target.sendMessage(ChatColor.GREEN + "You received " + amount + " " + crateName + " key(s)!");
        sender.sendMessage(ChatColor.GREEN + "Gave " + amount + " " + crateName + " key(s) to " + target.getName());
    }

    private void listCrates(CommandSender sender) {
        if (crateRewards.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No crates found!");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "Available crates:");
        for (String crateName : crateRewards.keySet()) {
            int rewardCount = crateRewards.get(crateName).size();
            sender.sendMessage(ChatColor.YELLOW + "- " + crateName + " (" + rewardCount + " rewards)");
        }
    }

    private void deleteCrate(CommandSender sender, String crateName) {
        if (!crateRewards.containsKey(crateName)) {
            sender.sendMessage(ChatColor.RED + "Crate not found!");
            return;
        }

        crateRewards.remove(crateName);
        crateChances.remove(crateName);

        getConfig().set("crates." + crateName, null);
        saveConfig();

        List<Location> toRemove = placedCrates.entrySet().stream()
                .filter(e -> e.getValue().equals(crateName))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        for (Location loc : toRemove) {
            placedCrates.remove(loc);
            removeHologram(loc);
        }

        savePlacedCrates();
        sender.sendMessage(ChatColor.GREEN + "Crate " + crateName + " deleted!");
    }

    private void previewCrate(Player player, String crateName) {
        if (!crateRewards.containsKey(crateName)) {
            player.sendMessage(ChatColor.RED + "Crate not found!");
            return;
        }

        List<ItemStack> rewards = crateRewards.get(crateName);
        Map<Integer, Double> chances = crateChances.get(crateName);

        int size = Math.min(54, ((rewards.size() / 9) + 1) * 9);
        Inventory preview = Bukkit.createInventory(null, size, "Preview: " + crateName);

        for (int i = 0; i < rewards.size() && i < size; i++) {
            ItemStack display = rewards.get(i).clone();
            ItemMeta meta = display.getItemMeta();
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GOLD + "Chance: " + ChatColor.YELLOW + chances.getOrDefault(i, 10.0) + "%");
            meta.setLore(lore);
            display.setItemMeta(meta);
            preview.setItem(i, display);
        }

        player.openInventory(preview);
    }

    private void reloadConfigs() {
        reloadConfig();
        loadCratesConfig();
        crateRewards.clear();
        crateChances.clear();
        loadCrates();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        if (event.getView().getTitle().startsWith("Preview:")) {
            event.setCancelled(true);
            return;
        }

        if (!event.getView().getTitle().startsWith("Create Crate:")) return;

        if (event.getSlot() == 49) {
            event.setCancelled(true);
            String crateName = crateCreators.get(player.getUniqueId());
            if (crateName == null) return;

            List<ItemStack> rewards = new ArrayList<>();
            for (int i = 0; i < 45; i++) {
                ItemStack item = event.getInventory().getItem(i);
                if (item != null && item.getType() != Material.AIR) {
                    rewards.add(item.clone());
                }
            }

            if (rewards.isEmpty()) {
                player.sendMessage(ChatColor.RED + "You must add at least one reward!");
                return;
            }

            saveCrate(crateName, rewards);
            player.closeInventory();
            player.sendMessage(ChatColor.GREEN + "Crate " + crateName + " created with " + rewards.size() + " rewards!");
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player) {
            crateCreators.remove(event.getWhoClicked().getUniqueId());
        }
    }

    private void saveCrate(String crateName, List<ItemStack> rewards) {
        ConfigurationSection crateSection = getConfig().createSection("crates." + crateName);
        ConfigurationSection rewardsSection = crateSection.createSection("rewards");

        Map<Integer, Double> chances = new HashMap<>();
        double defaultChance = 100.0 / rewards.size();

        for (int i = 0; i < rewards.size(); i++) {
            ConfigurationSection rewardSection = rewardsSection.createSection(String.valueOf(i));
            rewardSection.set("item", rewards.get(i));
            rewardSection.set("chance", defaultChance);
            chances.put(i, defaultChance);
        }

        crateRewards.put(crateName, rewards);
        crateChances.put(crateName, chances);
        saveConfig();
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getBlock().getType() != Material.CHEST) return;

        ItemStack item = event.getItemInHand();
        if (item == null || !item.hasItemMeta()) return;

        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
        if (!container.has(crateKey, PersistentDataType.BYTE)) return;

        String crateType = container.get(crateTypeKey, PersistentDataType.STRING);
        if (crateType == null || !crateRewards.containsKey(crateType)) return;

        Location loc = event.getBlock().getLocation();
        placedCrates.put(loc, crateType);
        createHologram(loc, crateType);

        event.getPlayer().sendMessage(ChatColor.GREEN + "Placed " + crateType + " crate!");
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Location loc = event.getBlock().getLocation();
        if (!placedCrates.containsKey(loc)) return;

        if (!event.getPlayer().hasPermission("simplecrates.break")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "You can't break crates!");
            return;
        }

        String crateType = placedCrates.remove(loc);
        removeHologram(loc);

        ItemStack crateItem = new ItemStack(Material.CHEST);
        ItemMeta meta = crateItem.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + crateType + " Crate");
        meta.setLore(Arrays.asList(ChatColor.GRAY + "Place this to create a crate"));
        meta.getPersistentDataContainer().set(crateKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(crateTypeKey, PersistentDataType.STRING, crateType);
        crateItem.setItemMeta(meta);

        event.setDropItems(false);
        event.getBlock().getWorld().dropItemNaturally(loc, crateItem);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.CHEST) return;

        Location loc = event.getClickedBlock().getLocation();
        if (!placedCrates.containsKey(loc)) return;

        event.setCancelled(true);

        Player player = event.getPlayer();
        String crateType = placedCrates.get(loc);

        if (animatingPlayers.contains(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "Please wait for the animation to finish!");
            return;
        }

        Long lastOpen = openCooldowns.get(player.getUniqueId());
        if (lastOpen != null && System.currentTimeMillis() - lastOpen < 3000) {
            player.sendMessage(ChatColor.RED + "Please wait before opening another crate!");
            return;
        }

        ItemStack keyItem = findKey(player, crateType);
        if (keyItem == null) {
            player.sendMessage(ChatColor.RED + "You need a " + crateType + " key to open this crate!");
            return;
        }

        keyItem.setAmount(keyItem.getAmount() - 1);
        openCooldowns.put(player.getUniqueId(), System.currentTimeMillis());

        playOpenAnimation(player, crateType, loc);
    }

    private ItemStack findKey(Player player, String crateType) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.hasItemMeta()) continue;

            PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
            String keyType = container.get(keyTypeKey, PersistentDataType.STRING);

            if (crateType.equals(keyType)) {
                return item;
            }
        }
        return null;
    }

    private void playOpenAnimation(Player player, String crateType, Location crateLoc) {
        animatingPlayers.add(player.getUniqueId());

        List<ItemStack> rewards = crateRewards.get(crateType);
        Map<Integer, Double> chances = crateChances.get(crateType);

        Inventory animationInv = Bukkit.createInventory(null, 27, "Opening " + crateType + "...");
        player.openInventory(animationInv);

        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glass.getItemMeta();
        glassMeta.setDisplayName(" ");
        glass.setItemMeta(glassMeta);

        for (int i = 0; i < 27; i++) {
            if (i != 13) animationInv.setItem(i, glass);
        }

        new BukkitRunnable() {
            int ticks = 0;
            int maxTicks = 60;
            ItemStack finalReward = selectReward(rewards, chances);

            @Override
            public void run() {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory() != animationInv) {
                    cancel();
                    animatingPlayers.remove(player.getUniqueId());
                    return;
                }

                if (ticks < maxTicks) {
                    ItemStack randomItem = rewards.get(ThreadLocalRandom.current().nextInt(rewards.size()));
                    animationInv.setItem(13, randomItem);

                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.0f + (ticks * 0.02f));

                    crateLoc.getWorld().spawnParticle(Particle.CRIT, crateLoc.clone().add(0.5, 1.2, 0.5), 3, 0.2, 0.2, 0.2, 0.1);

                    ticks += Math.max(1, ticks / 10);
                } else {
                    animationInv.setItem(13, finalReward);

                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                    player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.5f, 1.5f);

                    crateLoc.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, crateLoc.clone().add(0.5, 1.5, 0.5), 50, 0.5, 0.5, 0.5, 0.1);
                    crateLoc.getWorld().spawnParticle(Particle.FIREWORK, crateLoc.clone().add(0.5, 1.5, 0.5), 30, 0.3, 0.3, 0.3, 0.2);

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            player.closeInventory();
                            player.getInventory().addItem(finalReward.clone());
                            player.sendMessage(ChatColor.GREEN + "You won: " + ChatColor.YELLOW +
                                    (finalReward.hasItemMeta() && finalReward.getItemMeta().hasDisplayName() ?
                                            finalReward.getItemMeta().getDisplayName() : finalReward.getType().toString()));

                            animatingPlayers.remove(player.getUniqueId());

                            Bukkit.getLogger().info("Player " + player.getName() + " opened " + crateType +
                                    " crate and won " + finalReward.getType() + " x" + finalReward.getAmount());
                        }
                    }.runTaskLater(SimpleCrates.this, 40L);

                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    private ItemStack selectReward(List<ItemStack> rewards, Map<Integer, Double> chances) {
        double totalWeight = chances.values().stream().mapToDouble(Double::doubleValue).sum();
        double random = ThreadLocalRandom.current().nextDouble() * totalWeight;

        double currentWeight = 0;
        for (int i = 0; i < rewards.size(); i++) {
            currentWeight += chances.getOrDefault(i, 10.0);
            if (random <= currentWeight) {
                return rewards.get(i).clone();
            }
        }

        return rewards.get(ThreadLocalRandom.current().nextInt(rewards.size())).clone();
    }
}