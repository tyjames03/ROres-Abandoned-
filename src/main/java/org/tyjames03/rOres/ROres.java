package org.tyjames03.rOres;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.inventory.Inventory;
import org.bukkit.Particle;
import org.bukkit.ChatColor;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.file.YamlConfiguration;

public final class ROres extends JavaPlugin implements Listener, CommandExecutor {

    private NamespacedKey skulkKey;
    private NamespacedKey drillKey;
    private final Set<String> droppedShards = new HashSet<>();
    private File dataFile;
    private YamlConfiguration blockData;

    // Configurable values for custom enchants, fatigue, and upgrade prices/max levels
    private double explosiveBaseChance, explosivePerLevelIncrease;
    private int explosiveMaxLevel, explosiveRadius;
    private double speedBaseBoost, speedPerLevelIncrease;
    private int speedMaxLevel;
    private double dustBoosterBaseChance, dustBoosterPerLevelIncrease;
    private int dustBoosterMaxLevel;
    private double itemFinderBaseChance, itemFinderPerLevelIncrease;
    private int itemFinderMaxLevel;
    private int miningFatigueLevel, miningFatigueDuration;
    private int particleDurationTicks, particleAmount;
    private double particleSpread;
    private int dustBasePrice, dustPriceIncrease;

    private final Map<String, RegenBlockInfo> regenBlocks = new HashMap<>();
    private final Random random = new Random();

    private final Map<UUID, BukkitRunnable> fatigueTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> purifiedDust = new HashMap<>();
    private final Map<UUID, Integer> speedLevel = new HashMap<>();
    private final Map<UUID, Integer> explosiveLevel = new HashMap<>();
    private final Map<UUID, Integer> dustBoosterLevel = new HashMap<>();
    private final Map<UUID, Integer> itemFinderLevel = new HashMap<>();

    private static class RegenBlockInfo {
        String world;
        int x, y, z;
        String state;
        long regenEndTime;
        RegenBlockInfo(String world, int x, int y, int z, String state, long regenEndTime) {
            this.world = world;
            this.x = x; this.y = y; this.z = z;
            this.state = state;
            this.regenEndTime = regenEndTime;
        }
        String getId() { return world + ":" + x + "," + y + "," + z; }
    }

    @Override
    public void onEnable() {
        reloadROresConfig();

        skulkKey = new NamespacedKey(this, "rores_skulk");
        drillKey = new NamespacedKey(this, "lightborn_drill");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("rores").setExecutor(this);
        dataFile = new File(getDataFolder(), "regen_blocks.yml");
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        blockData = YamlConfiguration.loadConfiguration(dataFile);
        loadBlocks();

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        }
        fatigueTasks.clear();

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    Block target = player.getTargetBlockExact(5);
                    if (target != null && target.getType() == Material.SCULK && target.hasMetadata("rores_skulk")) {
                        PotionEffect effect = player.getPotionEffect(PotionEffectType.MINING_FATIGUE);
                        if (effect == null || effect.getAmplifier() < 1) {
                            applyMiningFatigue(player, miningFatigueLevel, miningFatigueDuration);
                        }
                    }
                }
            }
        }.runTaskTimer(this, 10, 10);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PurifiedDustPlaceholder(this).register();
        }
    }

    @Override
    public void onDisable() {
        saveBlocks();
        droppedShards.clear();
        regenBlocks.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        }
        fatigueTasks.clear();
    }

    public void reloadROresConfig() {
        reloadConfig();
        this.explosiveBaseChance = getConfig().getDouble("enchants.explosive.base-chance", 20.0);
        this.explosivePerLevelIncrease = getConfig().getDouble("enchants.explosive.per-level-increase", 20.0);
        this.explosiveMaxLevel = getConfig().getInt("enchants.explosive.max-level", 5);
        this.explosiveRadius = getConfig().getInt("enchants.explosive.radius", 2);

        this.speedBaseBoost = getConfig().getDouble("enchants.speed.base-boost", 1.0);
        this.speedPerLevelIncrease = getConfig().getDouble("enchants.speed.per-level-increase", 0.5);
        this.speedMaxLevel = getConfig().getInt("enchants.speed.max-level", 5);

        this.dustBoosterBaseChance = getConfig().getDouble("enchants.dustbooster.base-chance", 10.0);
        this.dustBoosterPerLevelIncrease = getConfig().getDouble("enchants.dustbooster.per-level-increase", 10.0);
        this.dustBoosterMaxLevel = getConfig().getInt("enchants.dustbooster.max-level", 5);

        this.itemFinderBaseChance = getConfig().getDouble("enchants.itemfinder.base-chance", 5.0);
        this.itemFinderPerLevelIncrease = getConfig().getDouble("enchants.itemfinder.per-level-increase", 5.0);
        this.itemFinderMaxLevel = getConfig().getInt("enchants.itemfinder.max-level", 5);

        this.miningFatigueLevel = getConfig().getInt("mining-fatigue.level", 2);
        this.miningFatigueDuration = getConfig().getInt("mining-fatigue.duration", 300);

        this.particleDurationTicks = getConfig().getInt("particle.duration-ticks", 60);
        this.particleAmount = getConfig().getInt("particle.amount", 10);
        this.particleSpread = getConfig().getDouble("particle.spread", 0.7);

        this.dustBasePrice = getConfig().getInt("upgrade.dust-base-price", 5);
        this.dustPriceIncrease = getConfig().getInt("upgrade.dust-price-increase", 5);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadROresConfig();
            sender.sendMessage(ChatColor.GREEN + "ROres config reloaded!");
            return true;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("give") && args[2].equalsIgnoreCase("skulk")) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage("Player not found!");
                return true;
            }
            target.getInventory().addItem(createRegeneratingSkulk());
            sender.sendMessage("Gave " + target.getName() + " a regenerating skulk block!");
            return true;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("give") && args[2].equalsIgnoreCase("drill")) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage("Player not found!");
                return true;
            }
            target.getInventory().addItem(createLightbornDrill(target));
            sender.sendMessage("Gave " + target.getName() + " a LightBorn Drill!");
            return true;
        }
        sender.sendMessage("Usage: /rores give <player> skulk|drill OR /rores reload");
        return true;
    }

    private ItemStack createRegeneratingSkulk() {
        ItemStack skulk = new ItemStack(Material.SCULK, 1);
        ItemMeta meta = skulk.getItemMeta();
        meta.setDisplayName("Regenerating Skulk Block");
        meta.getPersistentDataContainer().set(skulkKey, PersistentDataType.BYTE, (byte)1);
        skulk.setItemMeta(meta);
        return skulk;
    }

    private ItemStack createAstroShard() {
        ItemStack astroShard = new ItemStack(Material.ECHO_SHARD, 1);
        ItemMeta meta = astroShard.getItemMeta();
        meta.setDisplayName("§8Astro Shard");
        meta.addEnchant(Enchantment.UNBREAKING, 10, true);
        astroShard.setItemMeta(meta);
        return astroShard;
    }

    private ItemStack createVeltrium() {
        ItemStack veltrium = new ItemStack(Material.SCULK_VEIN, 1);
        ItemMeta meta = veltrium.getItemMeta();
        meta.setDisplayName("§8Veltrium");
        meta.addEnchant(Enchantment.UNBREAKING, 10, true);
        veltrium.setItemMeta(meta);
        return veltrium;
    }

    /** Always call this after upgrades to give player correct lore! */
    private void giveUpdatedDrill(Player player) {
        // Remove old drill from main hand if present
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (isLightbornDrill(hand)) {
            player.getInventory().setItemInMainHand(createLightbornDrill(player));
        } else {
            player.getInventory().addItem(createLightbornDrill(player));
        }
    }

    private ItemStack createLightbornDrill(Player player) {
        ItemStack drill = new ItemStack(Material.BREEZE_ROD, 1);
        ItemMeta meta = drill.getItemMeta();

        StringBuilder sb = new StringBuilder();
        sb.append("§x§E§0§8§5§F§B").append("L");
        sb.append("§x§E§1§8§C§F§B").append("i");
        sb.append("§x§E§3§9§3§F§C").append("g");
        sb.append("§x§E§4§9§A§F§C").append("h");
        sb.append("§x§E§6§A§1§F§C").append("t");
        sb.append("§x§E§7§A§8§F§D").append("B");
        sb.append("§x§E§8§A§F§D").append("o");
        sb.append("§x§E§A§B§6§F§D").append("r");
        sb.append("§x§E§B§B§D§F§D").append("n");
        sb.append(" ");
        sb.append("§x§E§C§C§4§F§E").append("D");
        sb.append("§x§E§E§C§B§F§E").append("r");
        sb.append("§x§E§F§D§2§F§E").append("i");
        sb.append("§x§F§1§D§9§F§F").append("l");
        sb.append("§x§F§2§E§0§F§F").append("l");

        meta.setDisplayName(sb.toString());
        meta.getPersistentDataContainer().set(drillKey, PersistentDataType.BYTE, (byte)1);
        meta.addEnchant(Enchantment.EFFICIENCY, 10, true);

        List<String> lore = new ArrayList<>();
        int explosiveLvl = Math.min(explosiveLevel.getOrDefault(player.getUniqueId(), 0), explosiveMaxLevel);
        int speedLvl = Math.min(speedLevel.getOrDefault(player.getUniqueId(), 0), speedMaxLevel);
        int dustBoosterLvl = Math.min(dustBoosterLevel.getOrDefault(player.getUniqueId(), 0), dustBoosterMaxLevel);
        int itemFinderLvl = Math.min(itemFinderLevel.getOrDefault(player.getUniqueId(), 0), itemFinderMaxLevel);

        if (explosiveLvl > 0)
            lore.add("§x§FF§C§0§0§0§0Explosion: §f" + toRoman(explosiveLvl) + " §7Chance: §b" +
                    String.format("%.1f", explosiveBaseChance + explosivePerLevelIncrease * (explosiveLvl - 1)) +
                    "% §7Radius: §b" + explosiveRadius + " blocks");
        if (speedLvl > 0)
            lore.add("§x§0§C§F§F§F§FSpeed: §f" + toRoman(speedLvl));
        if (dustBoosterLvl > 0)
            lore.add("§x§FF§E§D§D§00Dust Booster: §f" + toRoman(dustBoosterLvl) + " §7Chance: §b" +
                    String.format("%.1f", dustBoosterBaseChance + dustBoosterPerLevelIncrease * (dustBoosterLvl - 1)) + "%");
        if (itemFinderLvl > 0)
            lore.add("§x§9§9§F§F§00Item Finder: §f" + toRoman(itemFinderLvl) + " §7Chance: §b" +
                    String.format("%.1f", itemFinderBaseChance + itemFinderPerLevelIncrease * (itemFinderLvl - 1)) + "%");

        meta.setLore(lore);
        drill.setItemMeta(meta);
        return drill;
    }

    private static String toRoman(int number) {
        if (number < 1) return "";
        final String[] romans = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
                "XI", "XII", "XIII", "XIV", "XV"};
        return number < romans.length ? romans[number] : String.valueOf(number);
    }

    public void addPurifiedDust(Player player, int amount) {
        purifiedDust.put(player.getUniqueId(), getPurifiedDust(player) + amount);
    }
    public int getPurifiedDust(Player player) {
        return purifiedDust.getOrDefault(player.getUniqueId(), 0);
    }
    private boolean spendPurifiedDust(Player player, int amount) {
        int current = getPurifiedDust(player);
        if (current < amount) return false;
        purifiedDust.put(player.getUniqueId(), current - amount);
        return true;
    }

    private void openUpgradeGUI(Player player) {
        Inventory gui = Bukkit.createInventory(player, 27, "§8LightBorn Drill Upgrades");
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);

        for (int i = 0; i < 27; i++) gui.setItem(i, filler);

        gui.setItem(10, getUpgradeItem("Speed", Material.SUGAR, speedLevel.getOrDefault(player.getUniqueId(), 0), getPurifiedDust(player), speedMaxLevel));
        gui.setItem(12, getUpgradeItem("Explosive", Material.TNT, explosiveLevel.getOrDefault(player.getUniqueId(), 0), getPurifiedDust(player), explosiveMaxLevel));
        gui.setItem(14, getUpgradeItem("Dust Booster", Material.GLOWSTONE_DUST, dustBoosterLevel.getOrDefault(player.getUniqueId(), 0), getPurifiedDust(player), dustBoosterMaxLevel));
        gui.setItem(16, getUpgradeItem("Item Finder", Material.COMPASS, itemFinderLevel.getOrDefault(player.getUniqueId(), 0), getPurifiedDust(player), itemFinderMaxLevel));

        player.openInventory(gui);
    }

    private ItemStack getUpgradeItem(String name, Material mat, int level, int dust, int maxLevel) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§e" + name + " Upgrade");
        List<String> lore = new ArrayList<>();
        lore.add("§7Current Level: §b" + level + "§7/§b" + maxLevel);
        int nextCost = dustBasePrice + dustPriceIncrease * level;
        if (level >= maxLevel) {
            lore.add("§aMax level reached!");
        } else {
            lore.add("§7Upgrade Cost: §a" + nextCost + " Purified Dust");
            if (dust >= nextCost) {
                lore.add("§aYou can upgrade!");
            } else {
                lore.add("§cNot enough Purified Dust!");
            }
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item.getType() == Material.SCULK && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta.getPersistentDataContainer().has(skulkKey, PersistentDataType.BYTE)) {
                Block block = event.getBlockPlaced();
                block.setMetadata("rores_skulk", new FixedMetadataValue(this, true));
                String blockId = blockToId(block);
                RegenBlockInfo info = new RegenBlockInfo(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), "SCULK", 0);
                regenBlocks.put(blockId, info);
                saveBlocks();
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        String blockId = blockToId(block);

        // Always play particle effect for ANY skulk block broken
        if (block.getType() == Material.SCULK) {
            playWaxOffParticles(block);
        }

        if (block.getType() == Material.SCULK && block.hasMetadata("rores_skulk")) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            boolean isDrill = isLightbornDrill(hand);

            if (!isDrill) {
                event.setCancelled(true);
                player.sendMessage("§cYou cannot mine this block with that tool!");
                return;
            }
            int speedLvl = Math.min(speedLevel.getOrDefault(player.getUniqueId(), 0), speedMaxLevel);
            double speedBoost = speedBaseBoost + speedPerLevelIncrease * (speedLvl - 1);
            int duration = (int) Math.round(miningFatigueDuration / speedBoost);

            PotionEffect effect = player.getPotionEffect(PotionEffectType.MINING_FATIGUE);
            if (effect == null || effect.getAmplifier() < 1) {
                event.setCancelled(true);
                player.sendMessage("§cYou must wait a moment before mining this block!");
                applyMiningFatigue(player, miningFatigueLevel, 100);
                return;
            }
            event.setCancelled(true);
            block.setType(Material.BEDROCK);
            block.removeMetadata("rores_skulk", this);

            // Economy
            int dustAmt = 1;
            int dustBoosterLvl = Math.min(dustBoosterLevel.getOrDefault(player.getUniqueId(), 0), dustBoosterMaxLevel);
            double dustChance = dustBoosterBaseChance + dustBoosterPerLevelIncrease * (dustBoosterLvl - 1);
            if (dustBoosterLvl > 0 && random.nextDouble() * 100 < dustChance) dustAmt++;

            addPurifiedDust(player, dustAmt);

            // Only drop once per block placement/regeneration
            if (!droppedShards.contains(blockId)) {
                ItemStack reward;
                String itemName;
                int itemFinderLvl = Math.min(itemFinderLevel.getOrDefault(player.getUniqueId(), 0), itemFinderMaxLevel);
                double itemChance = itemFinderBaseChance + itemFinderPerLevelIncrease * (itemFinderLvl - 1);
                boolean foundRare = itemFinderLvl > 0 && random.nextDouble() * 100 < itemChance;
                if (foundRare) {
                    reward = createVeltrium();
                    itemName = "Veltrium";
                } else {
                    reward = createAstroShard();
                    itemName = "Astro Shard";
                }
                player.getInventory().addItem(reward);
                droppedShards.add(blockId);
                player.sendMessage("§fYou mined a §8Veltrium Block §fand obtained §8§n" + itemName);
            }

            // Explosive enchant: chance to break 1-4 nearby skulk blocks
            int expLevel = Math.min(explosiveLevel.getOrDefault(player.getUniqueId(), 0), explosiveMaxLevel);
            double chance = explosiveBaseChance + explosivePerLevelIncrease * (expLevel - 1);
            if (expLevel > 0 && random.nextDouble() * 100 < chance) {
                int extraBlocks = 1 + random.nextInt(4);
                breakNearbySkulk(block, player, extraBlocks, explosiveRadius);
            }

            applyMiningFatigue(player, miningFatigueLevel, duration);

            long regenEndTime = System.currentTimeMillis() + 20 * 60 * 50;
            RegenBlockInfo info = new RegenBlockInfo(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), "BEDROCK", regenEndTime);
            regenBlocks.put(blockId, info);
            saveBlocks();

            Block b = block;
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (b.getType() == Material.BEDROCK) {
                        b.setType(Material.SCULK);
                        b.setMetadata("rores_skulk", new FixedMetadataValue(ROres.this, true));
                        droppedShards.remove(blockId);
                        RegenBlockInfo info2 = new RegenBlockInfo(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), "SCULK", 0);
                        regenBlocks.put(blockId, info2);
                        saveBlocks();
                    }
                }
            }.runTaskLater(this, 20 * 60);

            // Ensure player always has updated drill after upgrades/mining events
            giveUpdatedDrill(player);
        }
    }

    /** Plays a Wax Off particle effect at the block's location for a configurable duration and spread. */
    private void playWaxOffParticles(Block block) {
        World w = block.getWorld();
        double x = block.getX() + 0.5;
        double y = block.getY() + 0.5;
        double z = block.getZ() + 0.5;
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (ticks++ >= particleDurationTicks) {
                    cancel();
                    return;
                }
                for (int i = 0; i < particleAmount; i++) {
                    double dx = (random.nextDouble() - 0.5) * particleSpread;
                    double dy = (random.nextDouble() - 0.5) * particleSpread;
                    double dz = (random.nextDouble() - 0.5) * particleSpread;
                    w.spawnParticle(Particle.WAX_OFF, x + dx, y + dy, z + dz, 1, 0, 0, 0, 0);
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void breakNearbySkulk(Block origin, Player player, int count, int radius) {
        World w = origin.getWorld();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        int broken = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (broken >= count) return;
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    Block b = w.getBlockAt(ox + dx, oy + dy, oz + dz);
                    if (b.getType() == Material.SCULK && b.hasMetadata("rores_skulk")) {
                        playWaxOffParticles(b);

                        BlockBreakEvent fakeBreak = new BlockBreakEvent(b, player);
                        Bukkit.getPluginManager().callEvent(fakeBreak);
                        if (!fakeBreak.isCancelled()) {
                            int dustAmt = 1;
                            int dustBoosterLvl = Math.min(dustBoosterLevel.getOrDefault(player.getUniqueId(), 0), dustBoosterMaxLevel);
                            double dustChance = dustBoosterBaseChance + dustBoosterPerLevelIncrease * (dustBoosterLvl - 1);
                            if (dustBoosterLvl > 0 && random.nextDouble() * 100 < dustChance) dustAmt++;
                            addPurifiedDust(player, dustAmt);

                            b.setType(Material.BEDROCK);
                            b.removeMetadata("rores_skulk", this);

                            String blockId = blockToId(b);
                            long regenEndTime = System.currentTimeMillis() + 20 * 60 * 50;
                            RegenBlockInfo info = new RegenBlockInfo(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), "BEDROCK", regenEndTime);
                            regenBlocks.put(blockId, info);
                            saveBlocks();

                            Block blockToRegen = b;
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    if (blockToRegen.getType() == Material.BEDROCK) {
                                        blockToRegen.setType(Material.SCULK);
                                        blockToRegen.setMetadata("rores_skulk", new FixedMetadataValue(ROres.this, true));
                                        droppedShards.remove(blockId);
                                        RegenBlockInfo info2 = new RegenBlockInfo(blockToRegen.getWorld().getName(), blockToRegen.getX(), blockToRegen.getY(), blockToRegen.getZ(), "SCULK", 0);
                                        regenBlocks.put(blockId, info2);
                                        saveBlocks();
                                    }
                                }
                            }.runTaskLater(this, 20 * 60);
                            broken++;
                        }
                    }
                }
            }
        }
        // Always update the drill after explosive proc
        giveUpdatedDrill(player);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isLightbornDrill(hand)) return;

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (player.isSneaking()) {
                event.setCancelled(true);
                openUpgradeGUI(player);
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!event.getView().getTitle().contains("LightBorn Drill Upgrades")) return;
        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot == 10) tryUpgrade(player, speedLevel, "Speed", speedMaxLevel);
        if (slot == 12) tryUpgrade(player, explosiveLevel, "Explosive", explosiveMaxLevel);
        if (slot == 14) tryUpgrade(player, dustBoosterLevel, "Dust Booster", dustBoosterMaxLevel);
        if (slot == 16) tryUpgrade(player, itemFinderLevel, "Item Finder", itemFinderMaxLevel);
        // Add/replace drill in main hand after upgrade
        Bukkit.getScheduler().runTaskLater(this, () -> giveUpdatedDrill(player), 2);
        Bukkit.getScheduler().runTaskLater(this, () -> openUpgradeGUI(player), 3);
    }

    private void tryUpgrade(Player player, Map<UUID, Integer> map, String upgradeName, int maxLevel) {
        int level = map.getOrDefault(player.getUniqueId(), 0);
        int cost = dustBasePrice + dustPriceIncrease * level;
        if (level >= maxLevel) {
            player.sendMessage("§cMax level reached for " + upgradeName + "!");
            return;
        }
        if (spendPurifiedDust(player, cost)) {
            map.put(player.getUniqueId(), level + 1);
            player.sendMessage("§aUpgraded " + upgradeName + " to level " + (level+1) + "!");
        } else {
            player.sendMessage("§cNot enough Purified Dust for " + upgradeName + " upgrade!");
        }
    }

    private boolean isLightbornDrill(ItemStack item) {
        if (item == null) return false;
        if (item.getType() != Material.BREEZE_ROD) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(drillKey, PersistentDataType.BYTE);
    }

    private void applyMiningFatigue(Player player, int level, int durationTicks) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, durationTicks, level - 1, true, false, true));
        BukkitRunnable oldTask = fatigueTasks.get(player.getUniqueId());
        if (oldTask != null) oldTask.cancel();
        if (durationTicks > 100) {
            BukkitRunnable removalTask = new BukkitRunnable() {
                @Override
                public void run() {
                    removeMiningFatigue(player);
                }
            };
            removalTask.runTaskLater(this, durationTicks);
            fatigueTasks.put(player.getUniqueId(), removalTask);
        }
    }

    private void removeMiningFatigue(Player player) {
        player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        BukkitRunnable oldTask = fatigueTasks.remove(player.getUniqueId());
        if (oldTask != null) oldTask.cancel();
    }

    private String blockToId(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private void saveBlocks() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RegenBlockInfo info : regenBlocks.values()) {
            Map<String, Object> map = new HashMap<>();
            map.put("world", info.world);
            map.put("x", info.x);
            map.put("y", info.y);
            map.put("z", info.z);
            map.put("state", info.state);
            if ("BEDROCK".equals(info.state)) {
                map.put("regenEndTime", info.regenEndTime);
            }
            out.add(map);
        }
        blockData.set("regen_blocks", out);
        try {
            blockData.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save regenerating block data!");
        }
    }

    private void loadBlocks() {
        List<Map<?, ?>> blocks = blockData.getMapList("regen_blocks");
        regenBlocks.clear();

        for (Map<?, ?> map : blocks) {
            String worldName = (String) map.get("world");
            int x = (int) map.get("x");
            int y = (int) map.get("y");
            int z = (int) map.get("z");
            String state = (String) map.get("state");
            long regenEndTime = map.containsKey("regenEndTime") ? Long.parseLong(map.get("regenEndTime").toString()) : 0;
            World world = Bukkit.getWorld(worldName);
            if (world == null) continue;
            Block block = world.getBlockAt(x, y, z);
            String blockId = worldName + ":" + x + "," + y + "," + z;
            regenBlocks.put(blockId, new RegenBlockInfo(worldName, x, y, z, state, regenEndTime));

            if ("SCULK".equals(state)) {
                block.setType(Material.SCULK);
                block.setMetadata("rores_skulk", new FixedMetadataValue(this, true));
            } else if ("BEDROCK".equals(state)) {
                block.setType(Material.BEDROCK);
                long waitMs = regenEndTime - System.currentTimeMillis();
                if (waitMs > 0) {
                    long waitTicks = Math.max(1, waitMs / 50);
                    Block b = block;
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (b.getType() == Material.BEDROCK) {
                                b.setType(Material.SCULK);
                                b.setMetadata("rores_skulk", new FixedMetadataValue(ROres.this, true));
                                droppedShards.remove(blockId);
                                RegenBlockInfo info2 = new RegenBlockInfo(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), "SCULK", 0);
                                regenBlocks.put(blockId, info2);
                                saveBlocks();
                            }
                        }
                    }.runTaskLater(this, waitTicks);
                } else {
                    block.setType(Material.SCULK);
                    block.setMetadata("rores_skulk", new FixedMetadataValue(this, true));
                    RegenBlockInfo info2 = new RegenBlockInfo(worldName, x, y, z, "SCULK", 0);
                    regenBlocks.put(blockId, info2);
                    saveBlocks();
                }
            }
        }
    }
}