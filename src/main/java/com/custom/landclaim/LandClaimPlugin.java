package com.custom.landclaim;

import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class LandClaimPlugin extends JavaPlugin implements Listener, TabCompleter {

    private final List<Claim> claims = new ArrayList<>();
    private final Map<UUID, Integer> playerClaimBlocks = new HashMap<>();
    private final Map<UUID, Location> corner1Map = new HashMap<>();
    private final Map<UUID, BossBar> playerBossBars = new HashMap<>();
    private final Map<UUID, Claim> editingClaim = new HashMap<>();

    private File dataFile;
    private FileConfiguration dataConfig;
    private static final int STARTING_BLOCKS = 100;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("claim") != null) {
            getCommand("claim").setTabCompleter(this);
        }
        loadData();

        // Give +10 Claim Blocks every 6 minutes (100 blocks/hour) to online players
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                addBlocks(p.getUniqueId(), 10);
                p.sendMessage(ChatColor.GREEN + "+10 Claim Blocks earned for playing! Total: " + ChatColor.GOLD + getAvailableBlocks(p.getUniqueId()));
            }
        }, 7200L, 7200L);

        getLogger().info("AdvancedLandClaim v4.0 Enabled!");
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
    }

    private void addBlocks(UUID uuid, int amount) {
        setAvailableBlocks(uuid, getAvailableBlocks(uuid) + amount);
        saveData();
    }

    private int getUsedBlocks(UUID uuid) {
        int used = 0;
        for (Claim c : claims) {
            if (c.owner.equals(uuid)) used += c.getArea();
        }
        return used;
    }

    private Claim getClaimAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        for (Claim c : claims) {
            if (c.contains(loc)) return c;
        }
        return null;
    }

    // --- CREATE CLAIM HELPER ---
    private boolean createClaim(Player player, Location pos1, Location pos2) {
        UUID uuid = player.getUniqueId();
        Claim newClaim = new Claim(UUID.randomUUID().toString(), uuid, player.getName(), pos1, pos2);
        int needed = newClaim.getArea();
        int available = getAvailableBlocks(uuid);

        if (needed < 9) {
            player.sendMessage(ChatColor.RED + "Claim must be at least 3x3 (9 blocks)!");
            return false;
        }

        if (available < needed) {
            player.sendMessage(ChatColor.RED + "Not enough Claim Blocks! Needed: " + ChatColor.GOLD + needed + ChatColor.RED + " | Available: " + ChatColor.YELLOW + available);
            return false;
        }

        for (Claim existing : claims) {
            if (existing.overlaps(newClaim)) {
                player.sendMessage(ChatColor.RED + "This area overlaps with " + ChatColor.YELLOW + existing.ownerName + "'s" + ChatColor.RED + " claim!");
                return false;
            }
        }

        newClaim.home = player.getLocation();
        setAvailableBlocks(uuid, available - needed);
        claims.add(newClaim);
        saveData();

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
        showClaimBorder(player, newClaim);
        updateBossBar(player, player.getLocation());

        player.sendMessage(ChatColor.GREEN + "Land Claimed! Size: " + ChatColor.GOLD + newClaim.getWidth() + "x" + newClaim.getLength() + " (" + needed + " blocks)" + ChatColor.GREEN + ".");
        player.sendMessage(ChatColor.GRAY + "Remaining Claim Blocks: " + ChatColor.YELLOW + getAvailableBlocks(uuid) + ChatColor.GRAY + " | Use " + ChatColor.AQUA + "/claim gui" + ChatColor.GRAY + " for settings.");
        return true;
    }

    // --- GLOWING PARTICLE BORDER VISUALIZER ---
    private void showClaimBorder(Player player, Claim claim) {
        double y = player.getLocation().getY() + 1.0;
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            int count = 0;
            @Override
            public void run() {
                if (count >= 6 || !player.isOnline()) return;
                int minX = claim.minX;
                int maxX = claim.maxX + 1;
                int minZ = claim.minZ;
                int maxZ = claim.maxZ + 1;

                player.spawnParticle(Particle.FLAME, minX, y, minZ, 3, 0, 0.3, 0, 0);
                player.spawnParticle(Particle.FLAME, maxX, y, minZ, 3, 0, 0.3, 0, 0);
                player.spawnParticle(Particle.FLAME, minX, y, maxZ, 3, 0, 0.3, 0, 0);
                player.spawnParticle(Particle.FLAME, maxX, y, maxZ, 3, 0, 0.3, 0, 0);

                for (int x = minX; x <= maxX; x += 2) {
                    player.spawnParticle(Particle.HAPPY_VILLAGER, x, y, minZ, 1, 0, 0, 0, 0);
                    player.spawnParticle(Particle.HAPPY_VILLAGER, x, y, maxZ, 1, 0, 0, 0, 0);
                }
                for (int z = minZ; z <= maxZ; z += 2) {
                    player.spawnParticle(Particle.HAPPY_VILLAGER, minX, y, z, 1, 0, 0, 0, 0);
                    player.spawnParticle(Particle.HAPPY_VILLAGER, maxX, y, z, 1, 0, 0, 0, 0);
                }
                count++;
            }
        }, 0L, 15L);
    }

    // --- GOLDEN SHOVEL CLAIMING ---
    @EventHandler
    public void onShovelClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (event.getItem() == null || event.getItem().getType() != Material.GOLDEN_SHOVEL) return;
        if (event.getClickedBlock() == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        event.setCancelled(true);
        Location clicked = event.getClickedBlock().getLocation();
        UUID uuid = player.getUniqueId();

        if (player.isSneaking()) {
            Claim inside = getClaimAt(clicked);
            if (inside != null && (inside.owner.equals(uuid) || player.isOp())) {
                openControlPanelGUI(player, inside);
                return;
            }
        }

        if (!corner1Map.containsKey(uuid)) {
            corner1Map.put(uuid, clicked);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.5f);
            player.spawnParticle(Particle.FLAME, clicked.clone().add(0.5, 1.2, 0.5), 10, 0.1, 0.3, 0.1, 0);
            player.sendMessage(ChatColor.YELLOW + "Corner 1 set at (" + clicked.getBlockX() + ", " + clicked.getBlockZ() + ")! Available Blocks: " + ChatColor.GOLD + getAvailableBlocks(uuid) + ChatColor.YELLOW + ". Right-click Corner 2.");
        } else {
            Location pos1 = corner1Map.remove(uuid);
            if (!pos1.getWorld().equals(clicked.getWorld())) {
                player.sendMessage(ChatColor.RED + "Both corners must be in the same world!");
                return;
            }
            createClaim(player, pos1, clicked);
        }
    }

    // --- COMMANDS (/claim & /claimsettings) ---
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can run this command.");
            return true;
        }
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();

        if (command.getName().equalsIgnoreCase("claimsettings")) {
            Claim claim = getClaimAt(player.getLocation());
            if (claim == null) {
                player.sendMessage(ChatColor.RED + "Stand inside your claim to open settings!");
                return true;
            }
            if (!claim.owner.equals(uuid) && !player.isOp()) {
                player.sendMessage(ChatColor.RED + "You do not own this claim!");
                return true;
            }
            openControlPanelGUI(player, claim);
            return true;
        }

        if (command.getName().equalsIgnoreCase("claim")) {
            // /claim -> Claims a 10x10 = 100 blocks area around the player!
            if (args.length == 0) {
                Location center = player.getLocation();
                Location pos1 = center.clone().add(-4, 0, -4);
                Location pos2 = center.clone().add(5, 0, 5);
                createClaim(player, pos1, pos2);
                return true;
            }

            String sub = args[0].toLowerCase();

            // /claim 100 -> Also claims 10x10 = 100 blocks area
            if (sub.equals("100")) {
                Location center = player.getLocation();
                Location pos1 = center.clone().add(-4, 0, -4);
                Location pos2 = center.clone().add(5, 0, 5);
                createClaim(player, pos1, pos2);
                return true;
            }

            // /claim chunk -> Claims current 16x16 chunk (256 blocks)
            if (sub.equals("chunk")) {
                Chunk ch = player.getLocation().getChunk();
                Location pos1 = new Location(ch.getWorld(), ch.getX() * 16, 64, ch.getZ() * 16);
                Location pos2 = new Location(ch.getWorld(), ch.getX() * 16 + 15, 64, ch.getZ() * 16 + 15);
                createClaim(player, pos1, pos2);
                return true;
            }

            // /claim blocks -> View Claim Blocks balance
            if (sub.equals("blocks") || sub.equals("balance")) {
                int avail = getAvailableBlocks(uuid);
                int used = getUsedBlocks(uuid);
                player.sendMessage(ChatColor.GOLD + "=== Your Claim Blocks ===");
                player.sendMessage(ChatColor.GREEN + "Available Blocks: " + ChatColor.GOLD + avail);
                player.sendMessage(ChatColor.YELLOW + "Used in Claims: " + ChatColor.WHITE + used);
                player.sendMessage(ChatColor.AQUA + "Total Earned: " + ChatColor.WHITE + (avail + used));
                return true;
            }

            // /claim giveblocks <player> <amount> (Admin)
            if (sub.equals("giveblocks")) {
                if (!player.isOp()) {
                    player.sendMessage(ChatColor.RED + "You don't have permission!");
                    return true;
                }
                if (args.length < 3) {
                    player.sendMessage(ChatColor.YELLOW + "Usage: /claim giveblocks <player> <amount>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    player.sendMessage(ChatColor.RED + "Player is offline!");
                    return true;
                }
                try {
                    int amt = Integer.parseInt(args[2]);
                    addBlocks(target.getUniqueId(), amt);
                    player.sendMessage(ChatColor.GREEN + "Gave " + ChatColor.GOLD + amt + " Claim Blocks" + ChatColor.GREEN + " to " + target.getName() + "!");
                    target.sendMessage(ChatColor.GREEN + "You received " + ChatColor.GOLD + amt + " Claim Blocks" + ChatColor.GREEN + "!");
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Please enter a valid number!");
                }
                return true;
            }

            // /claim home -> Teleport to your claim
            if (sub.equals("home") || sub.equals("tp")) {
                for (Claim c : claims) {
                    if (c.owner.equals(uuid) && c.home != null) {
                        player.teleport(c.home);
                        player.sendMessage(ChatColor.GREEN + "Teleported to your claim home!");
                        return true;
                    }
                }
                player.sendMessage(ChatColor.RED + "You do not have a claim yet! Type /claim first.");
                return true;
            }

            // /claim list -> List all your claims
            if (sub.equals("list")) {
                player.sendMessage(ChatColor.GOLD + "=== Your Claims (Available Blocks: " + getAvailableBlocks(uuid) + ") ===");
                int idx = 1;
                for (Claim c : claims) {
                    if (c.owner.equals(uuid)) {
                        player.sendMessage(ChatColor.YELLOW + "#" + idx + " " + ChatColor.WHITE + c.title + ChatColor.GRAY + " [" + c.getWidth() + "x" + c.getLength() + " = " + c.getArea() + " blocks] at X:" + c.minX + ", Z:" + c.minZ);
                        idx++;
                    }
                }
                if (idx == 1) player.sendMessage(ChatColor.GRAY + "You have no claims.");
                return true;
            }

            // /claim info -> Info about claim you stand in
            if (sub.equals("info")) {
                Claim c = getClaimAt(player.getLocation());
                if (c == null) {
                    player.sendMessage(ChatColor.GREEN + "Unclaimed Wilderness. Type " + ChatColor.YELLOW + "/claim" + ChatColor.GREEN + " to claim 100 blocks (10x10)!");
                    return true;
                }
                player.sendMessage(ChatColor.GOLD + "=== Claim Information ===");
                player.sendMessage(ChatColor.YELLOW + "Owner: " + ChatColor.WHITE + c.ownerName);
                player.sendMessage(ChatColor.YELLOW + "Title: " + ChatColor.WHITE + c.title);
                player.sendMessage(ChatColor.YELLOW + "Size: " + ChatColor.WHITE + c.getWidth() + "x" + c.getLength() + " (" + c.getArea() + " blocks)");
                player.sendMessage(ChatColor.YELLOW + "Trusted Players: " + ChatColor.WHITE + c.trusted.size());
                return true;
            }

            // /claim unclaimall
            if (sub.equals("unclaimall")) {
                int refunded = 0;
                int count = 0;
                Iterator<Claim> it = claims.iterator();
                while (it.hasNext()) {
                    Claim c = it.next();
                    if (c.owner.equals(uuid)) {
                        refunded += c.getArea();
                        count++;
                        it.remove();
                    }
                }
                if (count > 0) {
                    addBlocks(uuid, refunded);
                    updateBossBar(player, player.getLocation());
                    player.sendMessage(ChatColor.YELLOW + "Removed " + count + " claims and refunded " + ChatColor.GOLD + refunded + " Claim Blocks" + ChatColor.YELLOW + "!");
                } else {
                    player.sendMessage(ChatColor.RED + "You don't have any claims!");
                }
                return true;
            }

            // Commands that require standing inside your claim
            Claim claim = getClaimAt(player.getLocation());
            if (claim == null || (!claim.owner.equals(uuid) && !player.isOp())) {
                if (sub.equals("help")) {
                    sendHelp(player);
                } else {
                    player.sendMessage(ChatColor.RED + "Stand inside your own claim to use this command! (Type /claim help for all commands)");
                }
                return true;
            }

            if (sub.equals("gui") || sub.equals("settings") || sub.equals("menu")) {
                openControlPanelGUI(player, claim);
                return true;
            }

            if (sub.equals("border") || sub.equals("view")) {
                showClaimBorder(player, claim);
                player.sendMessage(ChatColor.AQUA + "Showing glowing claim borders!");
                return true;
            }

            if (sub.equals("sethome")) {
                claim.home = player.getLocation();
                saveData();
                player.sendMessage(ChatColor.GREEN + "Claim home updated to your current position!");
                return true;
            }

            if (sub.equals("unclaim")) {
                int refunded = claim.getArea();
                claims.remove(claim);
                addBlocks(claim.owner, refunded);
                updateBossBar(player, player.getLocation());
                player.sendMessage(ChatColor.YELLOW + "Claim removed! Refunded " + ChatColor.GOLD + refunded + " Claim Blocks" + ChatColor.YELLOW + ".");
                return true;
            }

            // /claim expand <blocks>
            if (sub.equals("expand")) {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "Usage: /claim expand <blocks>");
                    return true;
                }
                try {
                    int amount = Integer.parseInt(args[1]);
                    if (amount <= 0) {
                        player.sendMessage(ChatColor.RED + "Enter a positive number!");
                        return true;
                    }
                    Claim expanded = new Claim(claim.id, claim.owner, claim.ownerName, claim.world,
                            claim.minX - amount, claim.maxX + amount, claim.minZ - amount, claim.maxZ + amount);
                    int extraBlocks = expanded.getArea() - claim.getArea();
                    int avail = getAvailableBlocks(uuid);

                    if (avail < extraBlocks) {
                        player.sendMessage(ChatColor.RED + "Not enough Claim Blocks to expand! Needed: " + extraBlocks + " | Available: " + avail);
                        return true;
                    }
                    for (Claim other : claims) {
                        if (!other.id.equals(claim.id) && other.overlaps(expanded)) {
                            player.sendMessage(ChatColor.RED + "Cannot expand: overlaps with another claim!");
                            return true;
                        }
                    }

                    claim.minX = expanded.minX;
                    claim.maxX = expanded.maxX;
                    claim.minZ = expanded.minZ;
                    claim.maxZ = expanded.maxZ;
                    setAvailableBlocks(uuid, avail - extraBlocks);
                    saveData();
                    showClaimBorder(player, claim);
                    player.sendMessage(ChatColor.GREEN + "Claim expanded! Used " + ChatColor.GOLD + extraBlocks + " blocks" + ChatColor.GREEN + ". New size: " + claim.getWidth() + "x" + claim.getLength());
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Invalid number!");
                }
                return true;
            }

            if (sub.equals("trust") || sub.equals("add")) {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "Usage: /claim trust <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    player.sendMessage(ChatColor.RED + "Player is offline!");
                    return true;
                }
                claim.trusted.add(target.getUniqueId());
                claim.banned.remove(target.getUniqueId());
                saveData();
                player.sendMessage(ChatColor.GREEN + "Trusted " + ChatColor.GOLD + target.getName() + ChatColor.GREEN + " in this claim!");
                target.sendMessage(ChatColor.GREEN + "You have been trusted in " + ChatColor.GOLD + player.getName() + "'s" + ChatColor.GREEN + " claim!");
                return true;
            }

            if (sub.equals("untrust") || sub.equals("remove")) {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "Usage: /claim untrust <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target != null && claim.trusted.remove(target.getUniqueId())) {
                    saveData();
                    player.sendMessage(ChatColor.YELLOW + "Removed " + target.getName() + " from trusted players.");
                } else {
                    player.sendMessage(ChatColor.RED + "Player is not trusted in this claim!");
                }
                return true;
            }

            if (sub.equals("ban")) {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "Usage: /claim ban <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || target.equals(player)) {
                    player.sendMessage(ChatColor.RED + "Invalid player!");
                    return true;
                }
                claim.banned.add(target.getUniqueId());
                claim.trusted.remove(target.getUniqueId());
                saveData();
                player.sendMessage(ChatColor.RED + "Banned " + ChatColor.GOLD + target.getName() + ChatColor.RED + " from this claim!");
                if (claim.contains(target.getLocation())) {
                    target.teleport(target.getWorld().getSpawnLocation());
                    target.sendMessage(ChatColor.RED + "You were banned from " + claim.ownerName + "'s claim!");
                }
                return true;
            }

            if (sub.equals("unban")) {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "Usage: /claim unban <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target != null && claim.banned.remove(target.getUniqueId())) {
                    saveData();
                    player.sendMessage(ChatColor.GREEN + "Unbanned " + target.getName() + " from this claim.");
                } else {
                    player.sendMessage(ChatColor.RED + "Player is not banned!");
                }
                return true;
            }

            if (sub.equals("kick")) {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "Usage: /claim kick <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target != null && claim.contains(target.getLocation()) && !target.equals(player)) {
                    target.teleport(target.getWorld().getSpawnLocation());
                    target.sendMessage(ChatColor.RED + "You were kicked from " + claim.ownerName + "'s claim!");
                    player.sendMessage(ChatColor.YELLOW + "Kicked " + target.getName() + " from your claim.");
                } else {
                    player.sendMessage(ChatColor.RED + "That player is not inside your claim!");
                }
                return true;
            }

            if (sub.equals("rename")) {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "Usage: /claim rename <New Title>");
                    return true;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < args.length; i++) sb.append(args[i]).append(" ");
                claim.title = sb.toString().trim();
                saveData();
                updateBossBar(player, player.getLocation());
                player.sendMessage(ChatColor.GREEN + "Claim BossBar title changed to: " + ChatColor.YELLOW + claim.title);
                return true;
            }

            if (sub.equals("transfer")) {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "Usage: /claim transfer <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    player.sendMessage(ChatColor.RED + "Player is offline!");
                    return true;
                }
                claim.owner = target.getUniqueId();
                claim.ownerName = target.getName();
                claim.title = target.getName() + "'s Claim";
                saveData();
                updateBossBar(player, player.getLocation());
                player.sendMessage(ChatColor.GREEN + "Transferred claim ownership to " + ChatColor.GOLD + target.getName() + ChatColor.GREEN + "!");
                return true;
            }

            sendHelp(player);
            return true;
        }
        return false;
    }

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== AdvancedLandClaim Commands ===");
        player.sendMessage(ChatColor.YELLOW + "/claim" + ChatColor.GRAY + " - Claim a 10x10 area (100 Claim Blocks)");
        player.sendMessage(ChatColor.YELLOW + "/claim chunk" + ChatColor.GRAY + " - Claim a 16x16 chunk (256 Claim Blocks)");
        player.sendMessage(ChatColor.YELLOW + "/claim gui" + ChatColor.GRAY + " - Open 45-slot Claim Control Panel GUI");
        player.sendMessage(ChatColor.YELLOW + "/claim blocks" + ChatColor.GRAY + " - Check your Claim Blocks balance");
        player.sendMessage(ChatColor.YELLOW + "/claim expand <blocks>" + ChatColor.GRAY + " - Expand claim in all directions");
        player.sendMessage(ChatColor.YELLOW + "/claim trust | untrust <player>" + ChatColor.GRAY + " - Manage friends");
        player.sendMessage(ChatColor.YELLOW + "/claim ban | unban | kick <player>" + ChatColor.GRAY + " - Manage visitors");
        player.sendMessage(ChatColor.YELLOW + "/claim home | sethome" + ChatColor.GRAY + " - Teleport or set claim home");
        player.sendMessage(ChatColor.YELLOW + "/claim border" + ChatColor.GRAY + " - Show glowing claim border");
        player.sendMessage(ChatColor.YELLOW + "/claim rename <title>" + ChatColor.GRAY + " - Customize BossBar title");
        player.sendMessage(ChatColor.YELLOW + "/claim unclaim | unclaimall" + ChatColor.GRAY + " - Unclaim & refund blocks");
        player.sendMessage(ChatColor.YELLOW + "/claim info | list" + ChatColor.GRAY + " - View claim info & your list");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("100", "chunk", "gui", "blocks", "expand", "trust", "untrust", "ban", "unban", "kick", "home", "sethome", "border", "rename", "transfer", "unclaim", "unclaimall", "info", "list", "giveblocks", "help");
        }
        return null;
    }

    // --- 45-SLOT SIMPLECLAIMSYSTEM-STYLE GUI CONTROL PANEL ---
    private void openControlPanelGUI(Player player, Claim claim) {
        editingClaim.put(player.getUniqueId(), claim);
        Inventory gui = Bukkit.createInventory(null, 45, ChatColor.DARK_GRAY + "Claim Control Panel");

        ItemStack borderGlass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta bMeta = borderGlass.getItemMeta();
        if (bMeta != null) {
            bMeta.setDisplayName(" ");
            borderGlass.setItemMeta(bMeta);
        }
        for (int i = 0; i < 45; i++) gui.setItem(i, borderGlass);

        gui.setItem(10, createFlagItem(Material.GRASS_BLOCK, "Block Break & Place", claim.allowBuild));
        gui.setItem(11, createFlagItem(Material.CHEST, "Chests & Containers", claim.allowContainers));
        gui.setItem(12, createFlagItem(Material.OAK_DOOR, "Doors & Fence Gates", claim.allowDoors));
        gui.setItem(13, createFlagItem(Material.LEVER, "Buttons, Levers & Plates", claim.allowRedstone));
        gui.setItem(14, createFlagItem(Material.DIAMOND_SWORD, "PvP Combat", claim.allowPvP));
        gui.setItem(15, createFlagItem(Material.TNT, "TNT & Creeper Explosions", claim.allowExplosions));
        gui.setItem(16, createFlagItem(Material.ZOMBIE_HEAD, "Monster Spawning", claim.allowMobSpawn));

        gui.setItem(19, createFlagItem(Material.FLINT_AND_STEEL, "Fire & Lava Spread", claim.allowFireSpread));
        gui.setItem(20, createFlagItem(Material.EMERALD, "Villager Trading", claim.allowVillagerTrade));
        gui.setItem(21, createFlagItem(Material.HOPPER, "Item Pickup & Drop", claim.allowItemPickup));
        gui.setItem(22, createFlagItem(Material.ENDER_PEARL, "Ender Pearl Teleport", claim.allowEnderPearl));
        gui.setItem(23, createFlagItem(Material.COOKED_BEEF, "Animal Damage", claim.allowAnimalDamage));
        gui.setItem(24, createFlagItem(Material.WATER_BUCKET, "Water & Lava Buckets", claim.allowBuckets));
        gui.setItem(25, createFlagItem(Material.PISTON, "Piston Extension", claim.allowPistons));

        gui.setItem(37, createButton(Material.BEACON, ChatColor.AQUA + "Show Glowing Border", Arrays.asList(ChatColor.GRAY + "Display particles around your claim")));
        gui.setItem(38, createButton(Material.PLAYER_HEAD, ChatColor.LIGHT_PURPLE + "Manage Trusted Players", Arrays.asList(ChatColor.GRAY + "Trusted: " + ChatColor.WHITE + claim.trusted.size(), ChatColor.YELLOW + "Click to view & remove members")));
        gui.setItem(40, createButton(Material.GOLD_INGOT, ChatColor.GOLD + "Claim Blocks & Info", Arrays.asList(
                ChatColor.GRAY + "Size: " + ChatColor.WHITE + claim.getWidth() + "x" + claim.getLength() + " (" + claim.getArea() + " blocks)",
                ChatColor.GRAY + "Available Blocks: " + ChatColor.GREEN + getAvailableBlocks(claim.owner)
        )));
        gui.setItem(42, createButton(Material.ENDER_EYE, ChatColor.GREEN + "Teleport to Claim Home", Arrays.asList(ChatColor.GRAY + "Click to teleport to your claim")));
        gui.setItem(43, createButton(Material.BARRIER, ChatColor.RED + "Unclaim & Refund Blocks", Arrays.asList(ChatColor.GRAY + "Refunds " + ChatColor.GOLD + claim.getArea() + " Claim Blocks")));

        player.openInventory(gui);
    }

    private void openTrustedGUI(Player player, Claim claim) {
        editingClaim.put(player.getUniqueId(), claim);
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.DARK_GRAY + "Trusted Players");

        int slot = 0;
        for (UUID trustedId : claim.trusted) {
            if (slot >= 26) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(trustedId);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(op);
                meta.setDisplayName(ChatColor.GREEN + (op.getName() != null ? op.getName() : trustedId.toString()));
                meta.setLore(Collections.singletonList(ChatColor.RED + "Click to untrust this player!"));
                head.setItemMeta(meta);
            }
            gui.setItem(slot++, head);
        }

        gui.setItem(26, createButton(Material.ARROW, ChatColor.YELLOW + "Back to Control Panel", Collections.singletonList(ChatColor.GRAY + "Return to main menu")));
        player.openInventory(gui);
    }

    private ItemStack createFlagItem(Material mat, String name, boolean state) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + name);
            List<String> lore = new ArrayList<>();
            lore.add(state ? ChatColor.GREEN + "Status: ALLOWED (ON)" : ChatColor.RED + "Status: BLOCKED (OFF)");
            lore.add(ChatColor.GRAY + "Click to toggle!");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createButton(Material mat, String title, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.BOLD + title);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();

        if (title.equals(ChatColor.DARK_GRAY + "Trusted Players")) {
            event.setCancelled(true);
            Claim claim = editingClaim.get(player.getUniqueId());
            if (claim == null) return;
            int slot = event.getRawSlot();
            if (slot == 26) {
                openControlPanelGUI(player, claim);
                return;
            }
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && clicked.getType() == Material.PLAYER_HEAD && clicked.getItemMeta() instanceof SkullMeta) {
                SkullMeta sm = (SkullMeta) clicked.getItemMeta();
                if (sm.getOwningPlayer() != null) {
                    claim.trusted.remove(sm.getOwningPlayer().getUniqueId());
                    saveData();
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                    openTrustedGUI(player, claim);
                }
            }
            return;
        }

        if (!title.equals(ChatColor.DARK_GRAY + "Claim Control Panel")) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 45) return;

        Claim claim = editingClaim.get(player.getUniqueId());
        if (claim == null) return;

        if (slot == 10) claim.allowBuild = !claim.allowBuild;
        else if (slot == 11) claim.allowContainers = !claim.allowContainers;
        else if (slot == 12) claim.allowDoors = !claim.allowDoors;
        else if (slot == 13) claim.allowRedstone = !claim.allowRedstone;
        else if (slot == 14) claim.allowPvP = !claim.allowPvP;
        else if (slot == 15) claim.allowExplosions = !claim.allowExplosions;
        else if (slot == 16) claim.allowMobSpawn = !claim.allowMobSpawn;
        else if (slot == 19) claim.allowFireSpread = !claim.allowFireSpread;
        else if (slot == 20) claim.allowVillagerTrade = !claim.allowVillagerTrade;
        else if (slot == 21) claim.allowItemPickup = !claim.allowItemPickup;
        else if (slot == 22) claim.allowEnderPearl = !claim.allowEnderPearl;
        else if (slot == 23) claim.allowAnimalDamage = !claim.allowAnimalDamage;
        else if (slot == 24) claim.allowBuckets = !claim.allowBuckets;
        else if (slot == 25) claim.allowPistons = !claim.allowPistons;
        else if (slot == 37) {
            player.closeInventory();
            showClaimBorder(player, claim);
            player.sendMessage(ChatColor.AQUA + "Displaying glowing claim border!");
            return;
        } else if (slot == 38) {
            openTrustedGUI(player, claim);
            return;
        } else if (slot == 40) {
            player.closeInventory();
            player.performCommand("claim blocks");
            return;
        } else if (slot == 42) {
            player.closeInventory();
            if (claim.home != null) player.teleport(claim.home);
            return;
        } else if (slot == 43) {
            player.closeInventory();
            int refunded = claim.getArea();
            claims.remove(claim);
            addBlocks(claim.owner, refunded);
            updateBossBar(player, player.getLocation());
            player.sendMessage(ChatColor.YELLOW + "Unclaimed! Refunded " + ChatColor.GOLD + refunded + " Claim Blocks" + ChatColor.YELLOW + ".");
            return;
        } else {
            return;
        }

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.3f);
        saveData();
        openControlPanelGUI(player, claim);
    }

    // --- YELLOW BOSSBAR & BAN CHECK ---
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
            event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        Player player = event.getPlayer();
        Claim claim = getClaimAt(event.getTo());

        if (claim != null && !claim.owner.equals(player.getUniqueId()) && !player.isOp() && claim.banned.contains(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You are banned from entering " + claim.ownerName + "'s claim!");
            return;
        }

        updateBossBar(player, event.getTo());
    }

    private void updateBossBar(Player player, Location loc) {
        Claim claim = getClaimAt(loc);
        BossBar bar = playerBossBars.get(player.getUniqueId());

        if (claim != null) {
            String title = ChatColor.YELLOW + "" + ChatColor.BOLD + "⚡ " + claim.title + " ⚡";
            if (bar == null) {
                bar = Bukkit.createBossBar(title, BarColor.YELLOW, BarStyle.SOLID);
                bar.setProgress(1.0);
                bar.addPlayer(player);
                playerBossBars.put(player.getUniqueId(), bar);
            } else {
                bar.setTitle(title);
                bar.setProgress(1.0);
            }
        } else if (bar != null) {
            bar.removePlayer(player);
            playerBossBars.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        corner1Map.remove(event.getPlayer().getUniqueId());
        BossBar bar = playerBossBars.remove(event.getPlayer().getUniqueId());
        if (bar != null) bar.removeAll();
    }

    // --- PROTECTION LISTENERS ---
    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Claim claim = getClaimAt(event.getBlock().getLocation());
        if (claim != null && !claim.canBypass(event.getPlayer()) && !claim.allowBuild) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "You cannot break blocks in this claim!");
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        Claim claim = getClaimAt(event.getBlock().getLocation());
        if (claim != null && !claim.canBypass(event.getPlayer()) && !claim.allowBuild) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "You cannot place blocks in this claim!");
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Claim claim = getClaimAt(event.getClickedBlock().getLocation());
        if (claim == null || claim.canBypass(event.getPlayer())) return;

        String name = event.getClickedBlock().getType().name();
        if (name.contains("CHEST") || name.contains("FURNACE") || name.equals("BARREL") || name.contains("SHULKER") || name.equals("ANVIL") || name.equals("BREWING_STAND")) {
            if (!claim.allowContainers) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "Containers are locked in this claim!");
            }
        } else if (name.contains("DOOR") || name.contains("GATE")) {
            if (!claim.allowDoors) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "Doors are locked in this claim!");
            }
        } else if (name.contains("BUTTON") || name.equals("LEVER") || name.contains("PRESSURE_PLATE")) {
            if (!claim.allowRedstone) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "Buttons and levers are locked in this claim!");
            }
        }
    }

    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Claim claim = getClaimAt(event.getBlock().getLocation());
        if (claim != null && !claim.canBypass(event.getPlayer()) && !claim.allowBuckets) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "You cannot use buckets in this claim!");
        }
    }

    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent event) {
        Claim claim = getClaimAt(event.getBlock().getLocation());
        if (claim != null && !claim.canBypass(event.getPlayer()) && !claim.allowBuckets) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "You cannot use buckets in this claim!");
        }
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent event) {
        Claim sourceClaim = getClaimAt(event.getBlock().getLocation());
        for (org.bukkit.block.Block b : event.getBlocks()) {
            Claim targetClaim = getClaimAt(b.getRelative(event.getDirection()).getLocation());
            if (targetClaim != null && !targetClaim.allowPistons && (sourceClaim == null || !sourceClaim.id.equals(targetClaim.id))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = null;
        if (event.getDamager() instanceof Player) attacker = (Player) event.getDamager();
        else if (event.getDamager() instanceof Projectile && ((Projectile) event.getDamager()).getShooter() instanceof Player) {
            attacker = (Player) ((Projectile) event.getDamager()).getShooter();
        }
        if (attacker == null) return;

        Claim claim = getClaimAt(event.getEntity().getLocation());
        if (claim == null) return;

        if (event.getEntity() instanceof Player) {
            if (!claim.allowPvP) {
                event.setCancelled(true);
                attacker.sendMessage(ChatColor.RED + "PvP is disabled in this claim!");
            }
        } else if (event.getEntity() instanceof Animals) {
            if (!claim.canBypass(attacker) && !claim.allowAnimalDamage) {
                event.setCancelled(true);
                attacker.sendMessage(ChatColor.RED + "You cannot hurt animals in this claim!");
            }
        }
    }

    @EventHandler
    public void onVillagerTrade(PlayerInteractEntityEvent event) {
        if (event.getRightClicked().getType() != EntityType.VILLAGER) return;
        Claim claim = getClaimAt(event.getRightClicked().getLocation());
        if (claim != null && !claim.canBypass(event.getPlayer()) && !claim.allowVillagerTrade) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Villager trading is disabled in this claim!");
        }
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> {
            Claim claim = getClaimAt(b.getLocation());
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
    public void onFire(BlockIgniteEvent event) {
        Claim claim = getClaimAt(event.getBlock().getLocation());
        if (claim != null && !claim.allowFireSpread) event.setCancelled(true);
    }

    @EventHandler
    public void onBurn(BlockBurnEvent event) {
        Claim claim = getClaimAt(event.getBlock().getLocation());
        if (claim != null && !claim.allowFireSpread) event.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Claim claim = getClaimAt(event.getItem().getLocation());
        if (claim != null && !claim.canBypass((Player) event.getEntity()) && !claim.allowItemPickup) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        Claim claim = getClaimAt(event.getPlayer().getLocation());
        if (claim != null && !claim.canBypass(event.getPlayer()) && !claim.allowItemPickup) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPearl(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL || event.getTo() == null) return;
        Claim claim = getClaimAt(event.getTo());
        if (claim != null && !claim.canBypass(event.getPlayer()) && !claim.allowEnderPearl) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Ender Pearls are disabled in this claim!");
        }
    }

    // --- PERMANENT DATA SAVE/LOAD (claims.yml) ---
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
            dataConfig.set(path + ".title", c.title);
            dataConfig.set(path + ".world", c.world);
            dataConfig.set(path + ".minX", c.minX);
            dataConfig.set(path + ".maxX", c.maxX);
            dataConfig.set(path + ".minZ", c.minZ);
            dataConfig.set(path + ".maxZ", c.maxZ);
            if (c.home != null) dataConfig.set(path + ".home", c.home);

            List<String> trustedList = new ArrayList<>();
            for (UUID u : c.trusted) trustedList.add(u.toString());
            dataConfig.set(path + ".trusted", trustedList);

            List<String> bannedList = new ArrayList<>();
            for (UUID u : c.banned) bannedList.add(u.toString());
            dataConfig.set(path + ".banned", bannedList);

            dataConfig.set(path + ".allowBuild", c.allowBuild);
            dataConfig.set(path + ".allowContainers", c.allowContainers);
            dataConfig.set(path + ".allowDoors", c.allowDoors);
            dataConfig.set(path + ".allowRedstone", c.allowRedstone);
            dataConfig.set(path + ".allowPvP", c.allowPvP);
            dataConfig.set(path + ".allowExplosions", c.allowExplosions);
            dataConfig.set(path + ".allowMobSpawn", c.allowMobSpawn);
            dataConfig.set(path + ".allowFireSpread", c.allowFireSpread);
            dataConfig.set(path + ".allowVillagerTrade", c.allowVillagerTrade);
            dataConfig.set(path + ".allowItemPickup", c.allowItemPickup);
            dataConfig.set(path + ".allowEnderPearl", c.allowEnderPearl);
            dataConfig.set(path + ".allowAnimalDamage", c.allowAnimalDamage);
            dataConfig.set(path + ".allowBuckets", c.allowBuckets);
            dataConfig.set(path + ".allowPistons", c.allowPistons);
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

        ConfigurationSection bSec = dataConfig.getConfigurationSection("blocks");
        if (bSec != null) {
            for (String key : bSec.getKeys(false)) {
                playerClaimBlocks.put(UUID.fromString(key), bSec.getInt(key));
            }
        }

        ConfigurationSection cSec = dataConfig.getConfigurationSection("claims");
        if (cSec != null) {
            for (String id : cSec.getKeys(false)) {
                String path = "claims." + id;
                UUID owner = UUID.fromString(dataConfig.getString(path + ".owner"));
                String ownerName = dataConfig.getString(path + ".ownerName", "Player");
                String world = dataConfig.getString(path + ".world");
                int minX = dataConfig.getInt(path + ".minX");
                int maxX = dataConfig.getInt(path + ".maxX");
                int minZ = dataConfig.getInt(path + ".minZ");
                int maxZ = dataConfig.getInt(path + ".maxZ");

                Claim c = new Claim(id, owner, ownerName, world, minX, maxX, minZ, maxZ);
                c.title = dataConfig.getString(path + ".title", ownerName + "'s Claim");
                c.home = dataConfig.getLocation(path + ".home");

                for (String s : dataConfig.getStringList(path + ".trusted")) c.trusted.add(UUID.fromString(s));
                for (String s : dataConfig.getStringList(path + ".banned")) c.banned.add(UUID.fromString(s));

                c.allowBuild = dataConfig.getBoolean(path + ".allowBuild", false);
                c.allowContainers = dataConfig.getBoolean(path + ".allowContainers", false);
                c.allowDoors = dataConfig.getBoolean(path + ".allowDoors", false);
                c.allowRedstone = dataConfig.getBoolean(path + ".allowRedstone", false);
                c.allowPvP = dataConfig.getBoolean(path + ".allowPvP", false);
                c.allowExplosions = dataConfig.getBoolean(path + ".allowExplosions", false);
                c.allowMobSpawn = dataConfig.getBoolean(path + ".allowMobSpawn", true);
                c.allowFireSpread = dataConfig.getBoolean(path + ".allowFireSpread", false);
                c.allowVillagerTrade = dataConfig.getBoolean(path + ".allowVillagerTrade", false);
                c.allowItemPickup = dataConfig.getBoolean(path + ".allowItemPickup", true);
                c.allowEnderPearl = dataConfig.getBoolean(path + ".allowEnderPearl", false);
                c.allowAnimalDamage = dataConfig.getBoolean(path + ".allowAnimalDamage", false);
                c.allowBuckets = dataConfig.getBoolean(path + ".allowBuckets", false);
                c.allowPistons = dataConfig.getBoolean(path + ".allowPistons", false);

                claims.add(c);
            }
        }
    }

    private static class Claim {
        String id;
        UUID owner;
        String ownerName;
        String title;
        String world;
        int minX, maxX, minZ, maxZ;
        Location home;
        Set<UUID> trusted = new HashSet<>();
        Set<UUID> banned = new HashSet<>();

        boolean allowBuild = false;
        boolean allowContainers = false;
        boolean allowDoors = false;
        boolean allowRedstone = false;
        boolean allowPvP = false;
        boolean allowExplosions = false;
        boolean allowMobSpawn = true;
        boolean allowFireSpread = false;
        boolean allowVillagerTrade = false;
        boolean allowItemPickup = true;
        boolean allowEnderPearl = false;
        boolean allowAnimalDamage = false;
        boolean allowBuckets = false;
        boolean allowPistons = false;

        Claim(String id, UUID owner, String ownerName, Location l1, Location l2) {
            this(id, owner, ownerName, l1.getWorld().getName(),
                    Math.min(l1.getBlockX(), l2.getBlockX()), Math.max(l1.getBlockX(), l2.getBlockX()),
                    Math.min(l1.getBlockZ(), l2.getBlockZ()), Math.max(l1.getBlockZ(), l2.getBlockZ()));
        }

        Claim(String id, UUID owner, String ownerName, String world, int minX, int maxX, int minZ, int maxZ) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.title = ownerName + "'s Claim";
            this.world = world;
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }

        int getWidth() { return maxX - minX + 1; }
        int getLength() { return maxZ - minZ + 1; }
        int getArea() { return getWidth() * getLength(); }

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
            return p.getUniqueId().equals(owner) || trusted.contains(p.getUniqueId()) || p.isOp();
        }
    }
}
