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
import org.bukkit.command.TabCompleter;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class SimpleCrates extends JavaPlugin implements Listener, TabCompleter {

    private final Map<String, List<ItemStack>> crateRewards = new HashMap<>();
    private final Map<String, Map<Integer, Double>> crateChances = new HashMap<>();
    private final Map<UUID, String> crateCreators = new HashMap<>();
    private final Map<Location, String> placedCrates = new HashMap<>();
    private final Map<Location, ArmorStand> crateHolograms = new HashMap<>();
    private final Map<UUID, Long> openCooldowns = new HashMap<>();
    private final Set<UUID> animatingPlayers = new HashSet<>();

    private FileConfiguration cratesConfig;
    private File cratesFile;
    private FileConfiguration messagesConfig;
    private File messagesFile;

    private NamespacedKey crateKey;
    private NamespacedKey crateTypeKey;
    private NamespacedKey keyTypeKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadCratesConfig();
        loadMessagesConfig();

        crateKey = new NamespacedKey(this, "is_crate");
        crateTypeKey = new NamespacedKey(this, "crate_type");
        keyTypeKey = new NamespacedKey(this, "key_type");

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("crate").setTabCompleter(this);
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

    private void loadMessagesConfig() {
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            messagesFile.getParentFile().mkdirs();
            saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    private String getMessage(String path) {
        String message = messagesConfig.getString(path, path);
        String prefix = messagesConfig.getString("prefix", "");
        return ChatColor.translateAlternateColorCodes('&', prefix + message);
    }

    private String getMessage(String path, boolean noPrefix) {
        String message = messagesConfig.getString(path, path);
        if (noPrefix) {
            return ChatColor.translateAlternateColorCodes('&', message);
        }
        String prefix = messagesConfig.getString("prefix", "");
        return ChatColor.translateAlternateColorCodes('&', prefix + message);
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
            sender.sendMessage(getMessage("commands.unknown-command"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "help":
                showHelp(sender);
                break;

            case "create":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(getMessage("player-only"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(getMessage("commands.create.usage"));
                    return true;
                }
                if (!sender.hasPermission("simplecrates.create")) {
                    sender.sendMessage(getMessage("commands.create.no-permission"));
                    return true;
                }
                createCrateGUI((Player) sender, args[1]);
                break;

            case "give":
                if (args.length < 3) {
                    sender.sendMessage(getMessage("commands.give.usage"));
                    return true;
                }
                if (!sender.hasPermission("simplecrates.give")) {
                    sender.sendMessage(getMessage("commands.give.no-permission"));
                    return true;
                }
                giveKey(sender, args);
                break;

            case "getcrate":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(getMessage("player-only"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(getMessage("commands.getcrate.usage"));
                    return true;
                }
                if (!sender.hasPermission("simplecrates.getcrate")) {
                    sender.sendMessage(getMessage("commands.getcrate.no-permission"));
                    return true;
                }
                getCrate((Player) sender, args);
                break;

            case "list":
                if (!sender.hasPermission("simplecrates.list")) {
                    sender.sendMessage(getMessage("commands.list.no-permission"));
                    return true;
                }
                listCrates(sender);
                break;

            case "delete":
                if (args.length < 2) {
                    sender.sendMessage(getMessage("commands.delete.usage"));
                    return true;
                }
                if (!sender.hasPermission("simplecrates.delete")) {
                    sender.sendMessage(getMessage("commands.delete.no-permission"));
                    return true;
                }
                deleteCrate(sender, args[1]);
                break;

            case "reload":
                if (!sender.hasPermission("simplecrates.reload")) {
                    sender.sendMessage(getMessage("commands.reload.no-permission"));
                    return true;
                }
                reloadConfigs();
                sender.sendMessage(getMessage("commands.reload.success"));
                break;

            case "preview":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(getMessage("player-only"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(getMessage("commands.preview.usage"));
                    return true;
                }
                if (!sender.hasPermission("simplecrates.preview")) {
                    sender.sendMessage(getMessage("commands.preview.no-permission"));
                    return true;
                }
                previewCrate((Player) sender, args[1]);
                break;

            default:
                sender.sendMessage(getMessage("commands.unknown-command"));
        }

        return true;
    }

    private void createCrateGUI(Player player, String crateName) {
        String title = getMessage("commands.create.gui-title", true).replace("%crate%", crateName);
        Inventory gui = Bukkit.createInventory(null, 54, title);

        ItemStack confirmButton = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta confirmMeta = confirmButton.getItemMeta();
        confirmMeta.setDisplayName(getMessage("commands.create.confirm-button", true));
        confirmMeta.setLore(Arrays.asList(getMessage("commands.create.confirm-lore", true)));
        confirmButton.setItemMeta(confirmMeta);

        gui.setItem(49, confirmButton);

        crateCreators.put(player.getUniqueId(), crateName);
        player.openInventory(gui);
    }

    private void giveKey(CommandSender sender, String[] args) {
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(getMessage("commands.give.player-not-found"));
            return;
        }

        String crateName = args[2];
        if (!crateRewards.containsKey(crateName)) {
            sender.sendMessage(getMessage("commands.give.crate-not-found"));
            return;
        }

        int amount = 1;
        if (args.length > 3) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(getMessage("commands.give.invalid-amount"));
                return;
            }
        }

        ItemStack key = new ItemStack(Material.TRIPWIRE_HOOK, amount);
        ItemMeta meta = key.getItemMeta();
        meta.setDisplayName(getMessage("commands.give.key-name", true).replace("%crate%", crateName));

        List<String> lore = new ArrayList<>();
        for (String line : messagesConfig.getStringList("commands.give.key-lore")) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line.replace("%crate%", crateName)));
        }
        meta.setLore(lore);

        meta.getPersistentDataContainer().set(keyTypeKey, PersistentDataType.STRING, crateName);
        key.setItemMeta(meta);

        target.getInventory().addItem(key);
        target.sendMessage(getMessage("commands.give.received").replace("%amount%", String.valueOf(amount)).replace("%crate%", crateName));
        sender.sendMessage(getMessage("commands.give.given").replace("%amount%", String.valueOf(amount)).replace("%crate%", crateName).replace("%player%", target.getName()));
    }

    private void listCrates(CommandSender sender) {
        if (crateRewards.isEmpty()) {
            sender.sendMessage(getMessage("commands.list.no-crates"));
            return;
        }

        sender.sendMessage(getMessage("commands.list.header", true));
        for (String crateName : crateRewards.keySet()) {
            int rewardCount = crateRewards.get(crateName).size();
            String format = getMessage("commands.list.format", true)
                    .replace("%crate%", crateName)
                    .replace("%amount%", String.valueOf(rewardCount));
            sender.sendMessage(format);
        }
    }

    private void deleteCrate(CommandSender sender, String crateName) {
        if (!crateRewards.containsKey(crateName)) {
            sender.sendMessage(getMessage("commands.delete.crate-not-found"));
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
        sender.sendMessage(getMessage("commands.delete.success").replace("%crate%", crateName));
    }

    private void previewCrate(Player player, String crateName) {
        if (!crateRewards.containsKey(crateName)) {
            player.sendMessage(getMessage("commands.preview.crate-not-found"));
            return;
        }

        List<ItemStack> rewards = crateRewards.get(crateName);
        Map<Integer, Double> chances = crateChances.get(crateName);

        int size = Math.min(54, ((rewards.size() / 9) + 1) * 9);
        String title = getMessage("commands.preview.gui-title", true).replace("%crate%", crateName);
        Inventory preview = Bukkit.createInventory(null, size, title);

        for (int i = 0; i < rewards.size() && i < size; i++) {
            ItemStack display = rewards.get(i).clone();
            ItemMeta meta = display.getItemMeta();
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(getMessage("commands.preview.chance-lore", true).replace("%chance%", String.valueOf(chances.getOrDefault(i, 10.0))));
            meta.setLore(lore);
            display.setItemMeta(meta);
            preview.setItem(i, display);
        }

        player.openInventory(preview);
    }

    private void reloadConfigs() {
        reloadConfig();
        loadCratesConfig();
        loadMessagesConfig();
        crateRewards.clear();
        crateChances.clear();
        loadCrates();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        String previewTitle = getMessage("commands.preview.gui-title", true).split("%")[0];
        if (event.getView().getTitle().startsWith(previewTitle)) {
            event.setCancelled(true);
            return;
        }

        String createTitle = getMessage("commands.create.gui-title", true).split("%")[0];
        if (!event.getView().getTitle().startsWith(createTitle)) return;

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
                player.sendMessage(getMessage("commands.create.no-rewards"));
                return;
            }

            saveCrate(crateName, rewards);
            crateCreators.remove(player.getUniqueId());
            player.closeInventory();
            player.sendMessage(getMessage("commands.create.success").replace("%crate%", crateName).replace("%amount%", String.valueOf(rewards.size())));
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player) {
            Player player = (Player) event.getPlayer();

            String openingTitle = getMessage("crate.opening-title", true).split("%")[0];
            if (event.getView().getTitle().startsWith(openingTitle)) {
                if (animatingPlayers.contains(player.getUniqueId())) {
                    event.getPlayer().sendMessage(getMessage("crate.cannot-close"));
                    Bukkit.getScheduler().runTask(this, () -> player.openInventory(event.getInventory()));
                    return;
                }
            }

            crateCreators.remove(player.getUniqueId());
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

        event.getPlayer().sendMessage(getMessage("crate.placed").replace("%crate%", crateType));
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Location loc = event.getBlock().getLocation();
        if (!placedCrates.containsKey(loc)) return;

        if (!event.getPlayer().hasPermission("simplecrates.break")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(getMessage("crate.break-no-permission"));
            return;
        }

        String crateType = placedCrates.remove(loc);
        removeHologram(loc);

        ItemStack crateItem = new ItemStack(Material.CHEST);
        ItemMeta meta = crateItem.getItemMeta();
        meta.setDisplayName(getMessage("commands.getcrate.crate-item-name", true).replace("%crate%", crateType));
        meta.setLore(Arrays.asList(getMessage("commands.getcrate.crate-item-lore", true)));
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
            player.sendMessage(getMessage("crate.animation-running"));
            return;
        }

        Long lastOpen = openCooldowns.get(player.getUniqueId());
        if (lastOpen != null && System.currentTimeMillis() - lastOpen < 3000) {
            player.sendMessage(getMessage("crate.cooldown"));
            return;
        }

        ItemStack keyItem = findKey(player, crateType);
        if (keyItem == null) {
            player.sendMessage(getMessage("crate.need-key").replace("%crate%", crateType));
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

        String title = getMessage("crate.opening-title", true).replace("%crate%", crateType);
        Inventory animationInv = Bukkit.createInventory(null, 27, title);
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

                            String rewardName = finalReward.hasItemMeta() && finalReward.getItemMeta().hasDisplayName() ?
                                    finalReward.getItemMeta().getDisplayName() : finalReward.getType().toString();

                            player.sendMessage(getMessage("crate.won").replace("%reward%", rewardName));

                            String broadcast = getMessage("crate.broadcast", true)
                                    .replace("%player%", player.getName())
                                    .replace("%crate%", crateType)
                                    .replace("%reward%", rewardName);
                            Bukkit.broadcastMessage(broadcast);

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

    private void showHelp(CommandSender sender) {
        sender.sendMessage(getMessage("commands.help-header", true));
        String format = getMessage("commands.help-format", true);

        sender.sendMessage(format.replace("%command%", "/crate help").replace("%description%", getMessage("help.help", true)));
        sender.sendMessage(format.replace("%command%", "/crate create <n>").replace("%description%", getMessage("help.create", true)));
        sender.sendMessage(format.replace("%command%", "/crate give <player> <crate> [amount]").replace("%description%", getMessage("help.give", true)));
        sender.sendMessage(format.replace("%command%", "/crate getcrate <n> [amount]").replace("%description%", getMessage("help.getcrate", true)));
        sender.sendMessage(format.replace("%command%", "/crate list").replace("%description%", getMessage("help.list", true)));
        sender.sendMessage(format.replace("%command%", "/crate delete <n>").replace("%description%", getMessage("help.delete", true)));
        sender.sendMessage(format.replace("%command%", "/crate reload").replace("%description%", getMessage("help.reload", true)));
        sender.sendMessage(format.replace("%command%", "/crate preview <n>").replace("%description%", getMessage("help.preview", true)));
    }

    private void getCrate(Player player, String[] args) {
        String crateName = args[1];
        if (!crateRewards.containsKey(crateName)) {
            player.sendMessage(getMessage("commands.getcrate.crate-not-found"));
            return;
        }

        int amount = 1;
        if (args.length > 2) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                player.sendMessage(getMessage("commands.getcrate.invalid-amount"));
                return;
            }
        }

        ItemStack crateItem = new ItemStack(Material.CHEST, amount);
        ItemMeta meta = crateItem.getItemMeta();
        meta.setDisplayName(getMessage("commands.getcrate.crate-item-name", true).replace("%crate%", crateName));
        meta.setLore(Arrays.asList(getMessage("commands.getcrate.crate-item-lore", true)));
        meta.getPersistentDataContainer().set(crateKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(crateTypeKey, PersistentDataType.STRING, crateName);
        crateItem.setItemMeta(meta);

        player.getInventory().addItem(crateItem);
        player.sendMessage(getMessage("commands.getcrate.received").replace("%amount%", String.valueOf(amount)).replace("%crate%", crateName));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("crate")) return null;

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> subcommands = Arrays.asList("help", "create", "give", "getcrate", "list", "delete", "reload", "preview");
            for (String sub : subcommands) {
                if (sub.toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "give":
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                            completions.add(player.getName());
                        }
                    }
                    break;
                case "delete":
                case "preview":
                case "getcrate":
                    for (String crate : crateRewards.keySet()) {
                        if (crate.toLowerCase().startsWith(args[1].toLowerCase())) {
                            completions.add(crate);
                        }
                    }
                    break;
            }
        } else if (args.length == 3) {
            if (args[0].equalsIgnoreCase("give")) {
                for (String crate : crateRewards.keySet()) {
                    if (crate.toLowerCase().startsWith(args[2].toLowerCase())) {
                        completions.add(crate);
                    }
                }
            }
        }

        return completions;
    }
}