package com.custom.landclaim;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class LandClaimPlugin extends JavaPlugin implements Listener {

    private final List<Claim> claims = new ArrayList<>();
    private final Map<UUID, Integer> playerClaimBlocks = new HashMap<>();
    private final Map<UUID, Location> pos1Map = new HashMap<>();
    private final Map<UUID, BossBar> playerBossBars = new HashMap<>();
    private final Map<UUID, Claim> editingClaim = new HashMap<>();

    private File dataFile;
    private FileConfiguration dataConfig;
    private static final int STARTING_BLOCKS = 1000;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        loadData();
        getLogger().info("AdvancedLandClaim v2.0 Enabled with Claim Blocks & Persistent Storage!");
    }

    @Override
    public void onDisable() {
        saveData();
        for (BossBar bar : playerBossBars.values()) {
            bar.removeAll();
        }
    }

    private int getAvailableBlocks(UUID uuid) {
        return playerClaimBlocks.computeIfAbsent(uuid, k -> STARTING_BLOCKS);
    }

    private void setAvailableBlocks(UUID uuid, int amount) {
        playerClaimBlocks.put(uuid, Math.max(0, amount));
        saveData();
    }

    // --- 1. GOLDEN SHOVEL CLAIMING WITH CLAIM BLOCKS ---
    @EventHandler
    public void onShovelClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getItem() == null || event.getItem().getType() != Material.GOLDEN_SHOVEL) return;
        if (event.getClickedBlock() == null) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        event.setCancelled(true);
        Location clicked = event.getClickedBlock().getLocation();
        UUID uuid = player.getUniqueId();

        if (!pos1Map.containsKey(uuid)) {
            pos1Map.put(uuid, clicked);
            int avail = getAvailableBlocks(uuid);
            player.sendMessage(ChatColor.YELLOW + "पहला कोना (Corner 1) सेट! आपके पास " + ChatColor.GOLD + avail + ChatColor.YELLOW + " Claim Blocks हैं। अब दूसरे कोने पर Right-Click करें।");
        } else {
            Location pos1 = pos1Map.remove(uuid);
            if (!pos1.getWorld().equals(clicked.getWorld())) {
                player.sendMessage(ChatColor.RED + "दोनों कोने एक ही वर्ल्ड में होने चाहिए!");
                return;
            }

            Claim newClaim = new Claim(UUID.randomUUID().toString(), uuid, player.getName(), pos1, clicked);
            int requiredBlocks = newClaim.getArea();
            int availableBlocks = getAvailableBlocks(uuid);

            if (requiredBlocks < 9) {
                player.sendMessage(ChatColor.RED + "क्लेम कम से कम 3x3 (9 ब्लॉक्स) का होना चाहिए!");
                return;
            }

            if (availableBlocks < requiredBlocks) {
                player.sendMessage(ChatColor.RED + "आपके पास पर्याप्त Claim Blocks नहीं हैं! ज़रूरत: " + requiredBlocks + " | आपके पास: " + availableBlocks);
                return;
            }

            for (Claim existing : claims) {
                if (existing.overlaps(newClaim)) {
                    player.sendMessage(ChatColor.RED + "यह ज़मीन किसी दूसरे के क्लेम से टकरा रही है!");
                    return;
                }
            }

            setAvailableBlocks(uuid, availableBlocks - requiredBlocks);
            claims.add(newClaim);
            saveData();

            player.sendMessage(ChatColor.GREEN + "क्लेम सफल! " + ChatColor.GOLD + requiredBlocks + ChatColor.GREEN + " ब्लॉक्स इस्तेमाल हुए। बचे हुए ब्लॉक्स: " + ChatColor.YELLOW + getAvailableBlocks(uuid));
            player.sendMessage(ChatColor.AQUA + "सेटिंग्स खोलने के लिए /claimsettings और दोस्तों को जोड़ने के लिए /claim add <नाम> लिखें।");
            updateBossBar(player, player.getLocation());
        }
    }

    // --- 2. FULL YELLOW BOSSBAR LINE ---
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
            event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        updateBossBar(event.getPlayer(), event.getTo());
    }

    private void updateBossBar(Player player, Location loc) {
        Claim claim = getClaimAt(loc);
        BossBar bar = playerBossBars.get(player.getUniqueId());

        if (claim != null) {
            String title = ChatColor.YELLOW + "" + ChatColor.BOLD + "⚡ " + claim.ownerName + "'s Claim ⚡";
            if (bar == null) {
                bar = Bukkit.createBossBar(title, BarColor.YELLOW, BarStyle.SOLID);
                bar.setProgress(1.0);
                bar.addPlayer(player);
                playerBossBars.put(player.getUniqueId(), bar);
            } else {
                bar.setTitle(title);
                bar.setProgress(1.0);
                if (!bar.isVisible()) bar.setVisible(true);
            }
        } else {
            if (bar != null) {
                bar.removePlayer(player);
                playerBossBars.remove(player.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        BossBar bar = playerBossBars.remove(event.getPlayer().getUniqueId());
        if (bar != null) bar.removeAll();
    }

    // --- 3. COMMANDS (/claim, /claimsettings, /giveclaimblocks) ---
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("giveclaimblocks")) {
            if (!sender.hasPermission("landclaim.admin")) {
                sender.sendMessage(ChatColor.RED + "आपके पास इसकी परमिशन नहीं है!");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.YELLOW + "इस्तेमाल: /giveclaimblocks <player> <amount>");
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "खिलाड़ी ऑनलाइन नहीं है!");
                return true;
            }
            try {
                int amt = Integer.parseInt(args[1]);
                setAvailableBlocks(target.getUniqueId(), getAvailableBlocks(target.getUniqueId()) + amt);
                sender.sendMessage(ChatColor.GREEN + target.getName() + " को " + amt + " Claim Blocks दे दिए गए!");
                target.sendMessage(ChatColor.GREEN + "आपको " + ChatColor.GOLD + amt + ChatColor.GREEN + " नए Claim Blocks मिले हैं!");
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "सही संख्या लिखें!");
            }
            return true;
        }

        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;
        Claim claim = getClaimAt(player.getLocation());

        if (command.getName().equalsIgnoreCase("claimsettings")) {
            if (claim == null) {
                player.sendMessage(ChatColor.RED + "आप किसी क्लेम के अंदर नहीं खड़े हैं!");
                return true;
            }
            if (!claim.owner.equals(player.getUniqueId()) && !player.isOp()) {
                player.sendMessage(ChatColor.RED + "यह आपका क्लेम नहीं है!");
                return true;
            }
            openSettingsGUI(player, claim);
            return true;
        }

        if (command.getName().equalsIgnoreCase("claim")) {
            if (args.length == 0) {
                player.sendMessage(ChatColor.GOLD + "--- Land Claim Commands ---");
                player.sendMessage(ChatColor.YELLOW + "/claim blocks" + ChatColor.WHITE + " - अपने बचे हुए क्लेम ब्लॉक्स देखें");
                player.sendMessage(ChatColor.YELLOW + "/claim add <player>" + ChatColor.WHITE + " - क्लेम में किसी दोस्त को जोड़ें");
                player.sendMessage(ChatColor.YELLOW + "/claim remove <player>" + ChatColor.WHITE + " - क्लेम से किसी को हटाएं");
                player.sendMessage(ChatColor.YELLOW + "/claim unclaim" + ChatColor.WHITE + " - क्लेम हटाएं और ब्लॉक्स वापस पाएं");
                player.sendMessage(ChatColor.YELLOW + "/claimsettings" + ChatColor.WHITE + " - क्लेम परमिशन मेनू खोलें");
                return true;
            }

            if (args[0].equalsIgnoreCase("blocks")) {
                player.sendMessage(ChatColor.GREEN + "आपके पास कुल " + ChatColor.GOLD + getAvailableBlocks(player.getUniqueId()) + ChatColor.GREEN + " Claim Blocks बचे हैं।");
                return true;
            }

            if (claim == null || (!claim.owner.equals(player.getUniqueId()) && !player.isOp())) {
                player.sendMessage(ChatColor.RED + "अपने क्लेम के अंदर खड़े होकर यह कमांड लिखें!");
                return true;
            }

            if (args[0].equalsIgnoreCase("add")) {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "इस्तेमाल करें: /claim add <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    player.sendMessage(ChatColor.RED + "खिलाड़ी ऑनलाइन नहीं है!");
                    return true;
                }
                claim.members.add(target.getUniqueId());
                saveData();
                player.sendMessage(ChatColor.GREEN + target.getName() + " को आपके क्लेम में Add कर दिया गया है!");
                return true;
            }

            if (args[0].equalsIgnoreCase("remove")) {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "इस्तेमाल करें: /claim remove <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target != null && claim.members.remove(target.getUniqueId())) {
                    saveData();
                    player.sendMessage(ChatColor.YELLOW + target.getName() + " को क्लेम से हटा दिया गया है।");
                } else {
                    player.sendMessage(ChatColor.RED + "यह खिलाड़ी आपकी लिस्ट में नहीं है!");
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("unclaim")) {
                int refunded = claim.getArea();
                setAvailableBlocks(claim.owner, getAvailableBlocks(claim.owner) + refunded);
                claims.remove(claim);
                saveData();
                updateBossBar(player, player.getLocation());
                player.sendMessage(ChatColor.YELLOW + "क्लेम हटा दिया गया है और आपको " + ChatColor.GOLD + refunded + ChatColor.YELLOW + " Claim Blocks वापस मिल गए हैं!");
                return true;
            }
        }
        return false;
    }

    // --- 4. ADVANCED 9-OPTION GUI MENU (/claimsettings) ---
    private void openSettingsGUI(Player player, Claim claim) {
        editingClaim.put(player.getUniqueId(), claim);
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.DARK_GRAY + "Claim Settings: " + claim.ownerName);

        gui.setItem(1, createMenuItem(Material.GRASS_BLOCK, "Block Break & Place", claim.allowBuild));
        gui.setItem(3, createMenuItem(Material.CHEST, "Containers (Chest/Furnace)", claim.allowContainers));
        gui.setItem(5, createMenuItem(Material.OAK_DOOR, "Doors, Buttons & Levers", claim.allowInteract));
        gui.setItem(7, createMenuItem(Material.EMERALD, "Villager Trading", claim.allowVillagerTrade));

        gui.setItem(19, createMenuItem(Material.DIAMOND_SWORD, "PvP (Player Fighting)", claim.allowPvP));
        gui.setItem(21, createMenuItem(Material.TNT, "Explosion Damage", claim.allowExplosions));
        gui.setItem(23, createMenuItem(Material.ZOMBIE_HEAD, "Monster Spawning", claim.allowMobSpawn));
        gui.setItem(25, createMenuItem(Material.HOPPER, "Item Pickup & Drop", claim.allowItemDropPickup));
        gui.setItem(13, createMenuItem(Material.ENDER_PEARL, "Ender Pearl Teleport", claim.allowEnderPearl));

        player.openInventory(gui);
    }

    private ItemStack createMenuItem(Material mat, String name, boolean enabled) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + name);
            List<String> lore = new ArrayList<>();
            lore.add(enabled ? ChatColor.GREEN + "✔ Status: ALLOWED (ON)" : ChatColor.RED + "✘ Status: BLOCKED (OFF)");
            lore.add(ChatColor.GRAY + "बदलने के लिए क्लिक करें!");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!event.getView().getTitle().startsWith(ChatColor.DARK_GRAY + "Claim Settings:")) return;

        event.setCancelled(true);
        Claim claim = editingClaim.get(player.getUniqueId());
        if (claim == null) return;

        int slot = event.getRawSlot();
        if (slot == 1) claim.allowBuild = !claim.allowBuild;
        else if (slot == 3) claim.allowContainers = !claim.allowContainers;
        else if (slot == 5) claim.allowInteract = !claim.allowInteract;
        else if (slot == 7) claim.allowVillagerTrade = !claim.allowVillagerTrade;
        else if (slot == 13) claim.allowEnderPearl = !claim.allowEnderPearl;
        else if (slot == 19) claim.allowPvP = !claim.allowPvP;
        else if (slot == 21) claim.allowExplosions = !claim.allowExplosions;
        else if (slot == 23) claim.allowMobSpawn = !claim.allowMobSpawn;
        else if (slot == 25) claim.allowItemDropPickup = !claim.allowItemDropPickup;
        else return;

        saveData();
        openSettingsGUI(player, claim);
    }

    // --- 5. PROTECTION LISTENERS ---
    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Claim claim = getClaimAt(event.getBlock().getLocation());
        if (claim != null && !claim.canBypass(event.getPlayer()) && !claim.allowBuild) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "आप इस क्लेम में ब्लॉक नहीं तोड़ सकते!");
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        Claim claim = getClaimAt(event.getBlock().getLocation());
        if (claim != null && !claim.canBypass(event.getPlayer()) && !claim.allowBuild) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "आप इस क्लेम में ब्लॉक नहीं लगा सकते!");
        }
    }

    @EventHandler
    public void onBlockInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Claim claim = getClaimAt(event.getClickedBlock().getLocation());
        if (claim == null || claim.canBypass(event.getPlayer())) return;

        Material type = event.getClickedBlock().getType();
        if (type.name().contains("CHEST") || type.name().contains("FURNACE") || type == Material.BARREL || type.name().contains("SHULKER") || type == Material.ANVIL || type == Material.BREWING_STAND) {
            if (!claim.allowContainers) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "आप यहाँ चेस्ट या कंटेनर नहीं खोल सकते!");
            }
        } else if (type.name().contains("DOOR") || type.name().contains("BUTTON") || type == Material.LEVER || type.name().contains("GATE") || type.name().contains("PRESSURE_PLATE")) {
            if (!claim.allowInteract) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "आप यहाँ दरवाजे या बटन इस्तेमाल नहीं कर सकते!");
            }
        }
    }

    @EventHandler
    public void onVillagerInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked().getType() != EntityType.VILLAGER) return;
        Claim claim = getClaimAt(event.getRightClicked().getLocation());
        if (claim != null && !claim.canBypass(event.getPlayer()) && !claim.allowVillagerTrade) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "इस क्लेम में Villager Trading बंद है!");
        }
    }

    @EventHandler
    public void onPvP(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player) || !(event.getDamager() instanceof Player)) return;
        Claim claim = getClaimAt(event.getEntity().getLocation());
        if (claim != null && !claim.allowPvP) {
            event.setCancelled(true);
            event.getDamager().sendMessage(ChatColor.RED + "इस क्लेम में PvP बंद है!");
        }
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> {
            Claim claim = getClaimAt(block.getLocation());
            return claim != null && !claim.allowExplosions;
        });
    }

    @EventHandler
    public void onMobSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Monster)) return;
        Claim claim = getClaimAt(event.getLocation());
        if (claim != null && !claim.allowMobSpawn) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        Claim claim = getClaimAt(event.getItem().getLocation());
        if (claim != null && !claim.canBypass(player) && !claim.allowItemDropPickup) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        Claim claim = getClaimAt(event.getPlayer().getLocation());
        if (claim != null && !claim.canBypass(event.getPlayer()) && !claim.allowItemDropPickup) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL || event.getTo() == null) return;
        Claim claim = getClaimAt(event.getTo());
        if (claim != null && !claim.canBypass(event.getPlayer()) && !claim.allowEnderPearl) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "इस क्लेम में Ender Pearl इस्तेमाल करना मना है!");
        }
    }

    private Claim getClaimAt(Location loc) {
        for (Claim c : claims) {
            if (c.contains(loc)) return c;
        }
        return null;
    }

    // --- 6. SAVE & LOAD DATA (claims.yml) ---
    private void saveData() {
        if (dataFile == null) dataFile = new File(getDataFolder(), "claims.yml");
        dataConfig = new YamlConfiguration();

        for (Map.Entry<UUID, Integer> entry : playerClaimBlocks.entrySet()) {
            dataConfig.set("blocks." + entry.getKey().toString(), entry.getValue());
        }

        for (Claim c : claims) {
            String path = "claims." + c.id;
            dataConfig.set(path + ".owner", c.owner.toString());
            dataConfig.set(path + ".ownerName", c.ownerName);
            dataConfig.set(path + ".world", c.world);
            dataConfig.set(path + ".minX", c.minX);
            dataConfig.set(path + ".maxX", c.maxX);
            dataConfig.set(path + ".minZ", c.minZ);
            dataConfig.set(path + ".maxZ", c.maxZ);

            List<String> memberList = new ArrayList<>();
            for (UUID m : c.members) memberList.add(m.toString());
            dataConfig.set(path + ".members", memberList);

            dataConfig.set(path + ".allowBuild", c.allowBuild);
            dataConfig.set(path + ".allowContainers", c.allowContainers);
            dataConfig.set(path + ".allowInteract", c.allowInteract);
            dataConfig.set(path + ".allowVillagerTrade", c.allowVillagerTrade);
            dataConfig.set(path + ".allowPvP", c.allowPvP);
            dataConfig.set(path + ".allowExplosions", c.allowExplosions);
            dataConfig.set(path + ".allowMobSpawn", c.allowMobSpawn);
            dataConfig.set(path + ".allowItemDropPickup", c.allowItemDropPickup);
            dataConfig.set(path + ".allowEnderPearl", c.allowEnderPearl);
        }

        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save claims.yml!");
        }
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "claims.yml");
        if (!dataFile.exists()) return;
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection blockSec = dataConfig.getConfigurationSection("blocks");
        if (blockSec != null) {
            for (String key : blockSec.getKeys(false)) {
                playerClaimBlocks.put(UUID.fromString(key), blockSec.getInt(key));
            }
        }

        ConfigurationSection claimSec = dataConfig.getConfigurationSection("claims");
        if (claimSec != null) {
            for (String id : claimSec.getKeys(false)) {
                String path = "claims." + id;
                UUID owner = UUID.fromString(dataConfig.getString(path + ".owner"));
                String ownerName = dataConfig.getString(path + ".ownerName");
                String world = dataConfig.getString(path + ".world");
                int minX = dataConfig.getInt(path + ".minX");
                int maxX = dataConfig.getInt(path + ".maxX");
                int minZ = dataConfig.getInt(path + ".minZ");
                int maxZ = dataConfig.getInt(path + ".maxZ");

                Claim c = new Claim(id, owner, ownerName, world, minX, maxX, minZ, maxZ);
                for (String mStr : dataConfig.getStringList(path + ".members")) {
                    c.members.add(UUID.fromString(mStr));
                }
                c.allowBuild = dataConfig.getBoolean(path + ".allowBuild");
                c.allowContainers = dataConfig.getBoolean(path + ".allowContainers");
                c.allowInteract = dataConfig.getBoolean(path + ".allowInteract");
                c.allowVillagerTrade = dataConfig.getBoolean(path + ".allowVillagerTrade");
                c.allowPvP = dataConfig.getBoolean(path + ".allowPvP");
                c.allowExplosions = dataConfig.getBoolean(path + ".allowExplosions");
                c.allowMobSpawn = dataConfig.getBoolean(path + ".allowMobSpawn", true);
                c.allowItemDropPickup = dataConfig.getBoolean(path + ".allowItemDropPickup", true);
                c.allowEnderPearl = dataConfig.getBoolean(path + ".allowEnderPearl", false);
                claims.add(c);
            }
        }
    }

    // --- CLAIM DATA CLASS ---
    private static class Claim {
        String id;
        UUID owner;
        String ownerName;
        String world;
        int minX, maxX, minZ, maxZ;
        Set<UUID> members = new HashSet<>();

        boolean allowBuild = false;
        boolean allowContainers = false;
        boolean allowInteract = false;
        boolean allowVillagerTrade = false;
        boolean allowPvP = false;
        boolean allowExplosions = false;
        boolean allowMobSpawn = true;
        boolean allowItemDropPickup = true;
        boolean allowEnderPearl = false;

        Claim(String id, UUID owner, String ownerName, Location l1, Location l2) {
            this(id, owner, ownerName, l1.getWorld().getName(),
                 Math.min(l1.getBlockX(), l2.getBlockX()), Math.max(l1.getBlockX(), l2.getBlockX()),
                 Math.min(l1.getBlockZ(), l2.getBlockZ()), Math.max(l1.getBlockZ(), l2.getBlockZ()));
        }

        Claim(String id, UUID owner, String ownerName, String world, int minX, int maxX, int minZ, int maxZ) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.world = world;
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }

        int getArea() {
            return (maxX - minX + 1) * (maxZ - minZ + 1);
        }

        boolean contains(Location loc) {
            if (!loc.getWorld().getName().equals(world)) return false;
            int x = loc.getBlockX();
            int z = loc.getBlockZ();
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        boolean overlaps(Claim other) {
            if (!this.world.equals(other.world)) return false;
            return this.minX <= other.maxX && this.maxX >= other.minX &&
                   this.minZ <= other.maxZ && this.maxZ >= other.minZ;
        }

        boolean canBypass(Player p) {
            return p.getUniqueId().equals(owner) || members.contains(p.getUniqueId()) || p.isOp();
        }
    }
}
