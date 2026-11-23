package com.custom.landclaim;

import org.bukkit.*;
import org.bukkit.block.Block;
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
import org.bukkit.event.vehicle.VehicleEnterEvent;
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

    private static final int STARTING_BLOCKS = 100;
    private static final Material CORNER_MARKER_MATERIAL = Material.SEA_LANTERN;
    private static final String GUI_TITLE = ChatColor.DARK_GRAY + "Claim Settings";
    private static final String MEMBERS_LIST_TITLE = ChatColor.DARK_GRAY + "Member Settings";
    private static final String MEMBER_PERMS_PREFIX = ChatColor.DARK_GRAY + "Member: ";
    private static final String TP_GUI_TITLE = ChatColor.DARK_GRAY + "Claim Teleport";

    private final List<Claim> claims = new ArrayList<>();
    private final Map<UUID, Integer> playerClaimBlocks = new HashMap<>();
    private final Map<UUID, Location> corner1Map = new HashMap<>();
    private final Map<UUID, BossBar> playerBossBars = new HashMap<>();
    private final Map<UUID, String> currentClaimId = new HashMap<>();
    private final Map<UUID, Claim> editingClaim = new HashMap<>();
    private final Map<UUID, UUID> editingMember = new HashMap<>();

    private File dataFile;
    private FileConfiguration dataConfig;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("claim") != null) {
            getCommand("claim").setTabCompleter(this);
        }
        loadData();

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                addBlocks(p.getUniqueId(), 10);
                p.sendMessage(ChatColor.GREEN + "+10 Claim Blocks earned! Available: " + ChatColor.GOLD + getAvailableBlocks(p.getUniqueId()));
            }
        }, 7200L, 7200L);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Material hand = p.getInventory().getItemInMainHand().getType();
                if (hand == Material.GOLDEN_SHOVEL || hand == Material.STICK) {
                    Claim standing = getClaimAt(p.getLocation());
                    String zone = (standing == null) ? ChatColor.GREEN + "Wilderness" : ChatColor.YELLOW + standing.title;
                    p.sendActionBar(ChatColor.GOLD + "Claim Blocks: " + ChatColor.WHITE + getAvailableBlocks(p.getUniqueId())
                            + ChatColor.DARK_GRAY + " | " + ChatColor.AQUA + "Zone: " + zone);
                }
            }
        }, 20L, 20L);

        getLogger().info("AdvancedLandClaim v12.0 Enabled!");
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

    private List<Claim> getPlayerClaims(UUID uuid) {
        List<Claim> list = new ArrayList<>();
        for (Claim c : claims) {
            if (c.owner.equals(uuid)) list.add(c);
        }
        return list;
    }

    private int getNextClaimNumber(UUID uuid) {
        int maxNum = 0;
        for (Claim c : getPlayerClaims(uuid)) {
            if (c.number > maxNum) maxNum = c.number;
            if (c.title != null && c.title.toLowerCase().startsWith("claim-")) {
                try {
                    int parsed = Integer.parseInt(c.title.substring(6).trim());
                    if (parsed > maxNum) maxNum = parsed;
                } catch (NumberFormatException ignored) {}
            }
        }
        return maxNum + 1;
    }

    private Claim getClaimAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        for (Claim c : claims) {
            if (c.contains(loc)) return c;
        }
        return null;
    }

    private boolean createClaim(Player player, Location pos1, Location pos2) {
        UUID uuid = player.getUniqueId();
        int nextNum = getNextClaimNumber(uuid);
        Claim newClaim = new Claim(UUID.randomUUID().toString(), uuid, player.getName(), pos1, pos2);
        newClaim.number = nextNum;
        newClaim.title = "claim-" + nextNum;

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
                player.sendMessage(ChatColor.RED + "Already claimed! Overlaps with " + ChatColor.YELLOW + existing.ownerName + "'s" + ChatColor.RED + " claim.");
                showClaimVisuals(player, existing);
                return false;
            }
        }

        newClaim.home = player.getLocation();
        setAvailableBlocks(uuid, available - needed);
        claims.add(newClaim);
        saveData();

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.4f);
        showClaimVisuals(player, newClaim);
        handleZoneEntry(player, player.getLocation(), true);

        player.sendMessage(ChatColor.GREEN + "Created " + ChatColor.GOLD + newClaim.title + ChatColor.GREEN + " (" + ChatColor.YELLOW + newClaim.getWidth() + "x" + newClaim.getLength() + " = " + needed + " blocks" + ChatColor.GREEN + ").");
        player.sendMessage(ChatColor.GRAY + "Remaining Blocks: " + ChatColor.YELLOW + getAvailableBlocks(uuid) + ChatColor.GRAY + " | Use " + ChatColor.AQUA + "/claim setting" + ChatColor.GRAY + " or " + ChatColor.AQUA + "/claim tp" + ChatColor.GRAY + ".");
        return true;
    }

    private void showClaimVisuals(Player player, Claim claim) {
        World world = Bukkit.getWorld(claim.world);
        if (world == null) return;

        int[][] corners = {
                {claim.minX, claim.minZ},
                {claim.maxX, claim.minZ},
                {claim.minX, claim.maxZ},
                {claim.maxX, claim.maxZ}
        };

        List<Location> cornerLocs = new ArrayList<>();
        for (int[] c : corners) {
            Block highest = world.getHighestBlockAt(c[0], c[1]);
            Location markerLoc = highest.getLocation();
            if (!highest.getType().isAir() && !highest.isLiquid()) {
                markerLoc = highest.getRelative(0, 1, 0).getLocation();
            }
            player.sendBlockChange(markerLoc, CORNER_MARKER_MATERIAL.createBlockData());
            cornerLocs.add(markerLoc);
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            for (Location loc : cornerLocs) {
                player.sendBlockChange(loc, loc.getBlock().getBlockData());
            }
        }, 300L);

        double particleY = player.getLocation().getY() + 0.6;
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            int count = 0;
            @Override
            public void run() {
                if (count >= 8 || !player.isOnline()) return;
                int minX = claim.minX;
                int maxX = claim.maxX + 1;
                int minZ = claim.minZ;
                int maxZ = claim.maxZ + 1;

                for (int x = minX; x <= maxX; x++) {
                    player.spawnParticle(Particle.HAPPY_VILLAGER, x, particleY, minZ, 1, 0, 0, 0, 0);
                    player.spawnParticle(Particle.HAPPY_VILLAGER, x, particleY, maxZ, 1, 0, 0, 0, 0);
                }
                for (int z = minZ; z <= maxZ; z++) {
                    player.spawnParticle(Particle.HAPPY_VILLAGER, minX, particleY, z, 1, 0, 0, 0, 0);
                    player.spawnParticle(Particle.HAPPY_VILLAGER, maxX, particleY, z, 1, 0, 0, 0, 0);
                }
                count++;
            }
        }, 0L, 15L);
    }

    @EventHandler
    public void onToolInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getClickedBlock() == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        Material item = (event.getItem() != null) ? event.getItem().getType() : Material.AIR;
        Location clicked = event.getClickedBlock().getLocation();
        UUID uuid = player.getUniqueId();

        if (item == Material.STICK) {
            event.setCancelled(true);
            Claim found = getClaimAt(clicked);
            if (found == null) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 1.0f);
                player.sendMessage(ChatColor.GREEN + "This area is UNCLAIMED (Wilderness).");
            } else {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.5f);
                showClaimVisuals(player, found);
                player.sendMessage(ChatColor.GOLD + "====================================");
                player.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "ALREADY CLAIMED! " + ChatColor.YELLOW + "Owner: " + ChatColor.WHITE + found.ownerName);
                player.sendMessage(ChatColor.YELLOW + "Claim Name: " + ChatColor.AQUA + found.title + ChatColor.GRAY + " (#" + found.number + ")");
                player.sendMessage(ChatColor.YELLOW + "Size: " + ChatColor.WHITE + found.getWidth() + "x" + found.getLength() + " (" + found.getArea() + " blocks)");
                player.sendMessage(ChatColor.YELLOW + "Trusted Members: " + ChatColor.WHITE + found.trusted.size());
                player.sendMessage(ChatColor.GOLD + "====================================");
            }
            return;
        }

        if (item == Material.GOLDEN_SHOVEL) {
            event.setCancelled(true);

            if (player.isSneaking()) {
                Claim inside = getClaimAt(clicked);
                if (inside != null && (inside.owner.equals(uuid) || player.isOp())) {
                    openClaimSettingsGUI(player, inside);
                    return;
                }
            }

            if (!corner1Map.containsKey(uuid)) {
                corner1Map.put(uuid, clicked);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.5f);
                player.sendBlockChange(clicked, CORNER_MARKER_MATERIAL.createBlockData());
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (player.isOnline()) player.sendBlockChange(clicked, clicked.getBlock().getBlockData());
                }, 200L);
                player.sendMessage(ChatColor.YELLOW + "Corner 1 marked at (" + clicked.getBlockX() + ", " + clicked.getBlockZ() + ")! Right-click Corner 2.");
            } else {
                Location pos1 = corner1Map.remove(uuid);
                if (!pos1.getWorld().equals(clicked.getWorld())) {
                    player.sendMessage(ChatColor.RED + "Both corners must be in the same world!");
                    return;
                }
                createClaim(player, pos1, clicked);
            }
        }
    }

    private Claim findClaimByIdentifier(List<Claim> myClaims, String rawInput) {
        if (rawInput == null || rawInput.isEmpty()) return null;
        String clean = rawInput.replaceFirst("^#", "").trim();

        try {
            int num = Integer.parseInt(clean);
            for (Claim c : myClaims) {
                if (c.number == num) return c;
            }
            if (num >= 1 && num <= myClaims.size()) {
                return myClaims.get(num - 1);
            }
        } catch (NumberFormatException ignored) {}

        for (Claim c : myClaims) {
            if (c.title.equalsIgnoreCase(rawInput)) return c;
        }

        String lower = rawInput.toLowerCase();
        for (Claim c : myClaims) {
            if (c.title.toLowerCase().contains(lower)) return c;
        }

        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (!command.getName().equalsIgnoreCase("claim")) return false;

        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();

        if (args.length == 0) {
            Location center = player.getLocation();
            createClaim(player, center.clone().add(-4, 0, -4), center.clone().add(5, 0, 5));
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("tp")) {
            List<Claim> myClaims = getPlayerClaims(uuid);
            if (myClaims.isEmpty()) {
                player.sendMessage(ChatColor.RED + "You don't have any claims yet! Type /claim to create claim-1.");
                return true;
            }
            if (args.length == 1) {
                openClaimTPGUI(player);
                return true;
            }
            String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
            Claim target = findClaimByIdentifier(myClaims, query);
            if (target != null) {
                teleportToClaim(player, target);
            } else {
                player.sendMessage(ChatColor.RED + "Claim '" + query + "' not found! Use /claim list or /claim tp.");
            }
            return true;
        }

        if (sub.equals("sethome")) {
            List<Claim> myClaims = getPlayerClaims(uuid);
            Claim target = null;
            if (args.length >= 2) {
                String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
                target = findClaimByIdentifier(myClaims, query);
            }
            if (target == null) {
                Claim standing = getClaimAt(player.getLocation());
                if (standing != null && (standing.owner.equals(uuid) || player.isOp())) {
                    target = standing;
                }
            }
            if (target == null) {
                player.sendMessage(ChatColor.RED + "Stand inside your claim or use: /claim sethome <number|claim-1>");
                return true;
            }
            target.home = player.getLocation();
            saveData();
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
            player.sendMessage(ChatColor.GREEN + "Claim home set for " + ChatColor.GOLD + target.title + ChatColor.GREEN + "!");
            return true;
        }

        if (sub.equals("list")) {
            List<Claim> myClaims = getPlayerClaims(uuid);
            player.sendMessage(ChatColor.GOLD + "=== Your Claims (" + myClaims.size() + " | Available: " + getAvailableBlocks(uuid) + " blocks) ===");
            if (myClaims.isEmpty()) {
                player.sendMessage(ChatColor.GRAY + "No claims yet. Type /claim to create claim-1.");
            } else {
                for (Claim c : myClaims) {
                    player.sendMessage(ChatColor.GOLD + "#" + c.number + " " + ChatColor.AQUA + c.title
                            + ChatColor.GRAY + " [" + c.getWidth() + "x" + c.getLength() + " = " + c.getArea() + " blocks]"
                            + ChatColor.YELLOW + " (X:" + c.minX + ", Z:" + c.minZ + ")");
                }
            }
            return true;
        }

        if (sub.equals("rename")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.YELLOW + "Usage: /claim rename <name> OR /claim rename <number|claim-1> <name>");
                return true;
            }
            List<Claim> myClaims = getPlayerClaims(uuid);
            Claim targetClaim = null;
            String newName = "";

            if (args.length >= 3) {
                Claim byId = findClaimByIdentifier(myClaims, args[1]);
                if (byId != null) {
                    targetClaim = byId;
                    newName = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
                }
            }
            if (targetClaim == null) {
                Claim standing = getClaimAt(player.getLocation());
                if (standing != null && (standing.owner.equals(uuid) || player.isOp())) {
                    targetClaim = standing;
                    newName = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
                } else {
                    player.sendMessage(ChatColor.RED + "Stand inside your claim or use: /claim rename <number|claim-1> <name>");
                    return true;
                }
            }
            if (newName.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Please enter a new claim name!");
                return true;
            }
            targetClaim.title = newName;
            saveData();
            handleZoneEntry(player, player.getLocation(), true);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.3f);
            player.sendMessage(ChatColor.GREEN + "Renamed #" + targetClaim.number + " to: " + ChatColor.GOLD + targetClaim.title);
            return true;
        }

        if (sub.equals("blocks")) {
            int avail = getAvailableBlocks(uuid);
            int used = getUsedBlocks(uuid);
            player.sendMessage(ChatColor.GOLD + "=== Claim Blocks ===");
            player.sendMessage(ChatColor.GREEN + "Available: " + ChatColor.GOLD + avail);
            player.sendMessage(ChatColor.YELLOW + "Used: " + ChatColor.WHITE + used);
            player.sendMessage(ChatColor.AQUA + "Total: " + ChatColor.WHITE + (avail + used));
            return true;
        }

        if (sub.equals("info")) {
            Claim c = getClaimAt(player.getLocation());
            if (c == null) {
                player.sendMessage(ChatColor.GREEN + "Unclaimed Wilderness. Type " + ChatColor.YELLOW + "/claim" + ChatColor.GREEN + " to claim 100 blocks.");
                return true;
            }
            showClaimVisuals(player, c);
            player.sendMessage(ChatColor.GOLD + "=== Claim Info ===");
            player.sendMessage(ChatColor.YELLOW + "Owner: " + ChatColor.WHITE + c.ownerName);
            player.sendMessage(ChatColor.YELLOW + "Claim: " + ChatColor.AQUA + c.title + ChatColor.GRAY + " (#" + c.number + ")");
            player.sendMessage(ChatColor.YELLOW + "Size: " + ChatColor.WHITE + c.getWidth() + "x" + c.getLength() + " (" + c.getArea() + " blocks)");
            player.sendMessage(ChatColor.YELLOW + "Trusted: " + ChatColor.WHITE + c.trusted.size());
            return true;
        }

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
                handleZoneEntry(player, player.getLocation(), false);
                player.sendMessage(ChatColor.YELLOW + "Unclaimed " + count + " claims and refunded " + ChatColor.GOLD + refunded + " Claim Blocks" + ChatColor.YELLOW + ".");
            } else {
                player.sendMessage(ChatColor.RED + "You have no claims!");
            }
            return true;
        }

        Claim claim = getClaimAt(player.getLocation());
        if (claim == null || (!claim.owner.equals(uuid) && !player.isOp())) {
            if (sub.equals("help")) sendHelp(player);
            else player.sendMessage(ChatColor.RED + "Stand inside your own claim to use this command!");
            return true;
        }

        if (sub.equals("setting")) {
            openClaimSettingsGUI(player, claim);
            return true;
        }

        if (sub.equals("border")) {
            showClaimVisuals(player, claim);
            player.sendMessage(ChatColor.AQUA + "Showing claim border and corner markers.");
            return true;
        }

        if (sub.equals("unclaim")) {
            int refunded = claim.getArea();
            claims.remove(claim);
            addBlocks(claim.owner, refunded);
            handleZoneEntry(player, player.getLocation(), false);
            player.sendMessage(ChatColor.YELLOW + "Removed " + ChatColor.GOLD + claim.title + ChatColor.YELLOW + "! Refunded " + ChatColor.GOLD + refunded + " Claim Blocks" + ChatColor.YELLOW + ".");
            return true;
        }

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
                    player.sendMessage(ChatColor.RED + "Not enough Claim Blocks! Needed: " + extraBlocks + " | Available: " + avail);
                    return true;
                }
                for (Claim other : claims) {
                    if (!other.id.equals(claim.id) && other.overlaps(expanded)) {
                        player.sendMessage(ChatColor.RED + "Cannot expand: overlaps another claim!");
                        return true;
                    }
                }

                claim.minX = expanded.minX;
                claim.maxX = expanded.maxX;
                claim.minZ = expanded.minZ;
                claim.maxZ = expanded.maxZ;
                setAvailableBlocks(uuid, avail - extraBlocks);
                saveData();
                showClaimVisuals(player, claim);
                player.sendMessage(ChatColor.GREEN + "Expanded " + ChatColor.GOLD + claim.title + ChatColor.GREEN + "! Used " + ChatColor.GOLD + extraBlocks + " blocks" + ChatColor.GREEN + ".");
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid number!");
            }
            return true;
        }

        if (sub.equals("trust")) {
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
            claim.getMemberPerms(target.getUniqueId());
            claim.banned.remove(target.getUniqueId());
            saveData();
            player.sendMessage(ChatColor.GREEN + "Trusted " + ChatColor.GOLD + target.getName() + ChatColor.GREEN + " in " + claim.title + "!");
            target.sendMessage(ChatColor.GREEN + "You are now trusted in " + ChatColor.GOLD + claim.title + ChatColor.GREEN + "!");
            return true;
        }

        if (sub.equals("untrust")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.YELLOW + "Usage: /claim untrust <player>");
                return true;
            }
            Player target = Bukkit.getPlayer(args[1]);
            if (target != null && claim.trusted.remove(target.getUniqueId())) {
                claim.memberPerms.remove(target.getUniqueId());
                saveData();
                player.sendMessage(ChatColor.YELLOW + "Removed " + target.getName() + " from trusted players.");
            } else {
                player.sendMessage(ChatColor.RED + "Player is not trusted!");
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
            claim.memberPerms.remove(target.getUniqueId());
            saveData();
            player.sendMessage(ChatColor.RED + "Banned " + ChatColor.GOLD + target.getName() + ChatColor.RED + " from " + claim.title + "!");
            if (claim.contains(target.getLocation())) {
                target.teleport(target.getWorld().getSpawnLocation());
                target.sendMessage(ChatColor.RED + "You were banned from " + claim.title + "!");
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
                player.sendMessage(ChatColor.GREEN + "Unbanned " + target.getName() + ".");
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
                target.sendMessage(ChatColor.RED + "You were kicked from " + claim.title + "!");
                player.sendMessage(ChatColor.YELLOW + "Kicked " + target.getName() + ".");
            } else {
                player.sendMessage(ChatColor.RED + "Player is not inside your claim!");
            }
            return true;
        }

        sendHelp(player);
        return true;
    }

    private void teleportToClaim(Player player, Claim claim) {
        if (!claim.owner.equals(player.getUniqueId()) && !claim.trusted.contains(player.getUniqueId()) && !player.isOp() && !claim.allowTeleportIn) {
            player.sendMessage(ChatColor.RED + "Teleporting into " + claim.title + " is disabled!");
            return;
        }
        Location dest = claim.home;
        if (dest == null) {
            World w = Bukkit.getWorld(claim.world);
            if (w == null) return;
            int cx = (claim.minX + claim.maxX) / 2;
            int cz = (claim.minZ + claim.maxZ) / 2;
            int cy = w.getHighestBlockYAt(cx, cz) + 1;
            dest = new Location(w, cx + 0.5, cy, cz + 0.5);
        }
        player.teleport(dest);
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.2f);
        player.sendMessage(ChatColor.GREEN + "Teleported to " + ChatColor.GOLD + claim.title + ChatColor.GRAY + " (#" + claim.number + ")");
    }

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== Claim Commands ===");
        player.sendMessage(ChatColor.YELLOW + "/claim" + ChatColor.GRAY + " - Claim 10x10 area (claim-1, claim-2...)");
        player.sendMessage(ChatColor.YELLOW + "/claim setting" + ChatColor.GRAY + " - Open Claim Settings & Member Settings");
        player.sendMessage(ChatColor.YELLOW + "/claim tp [number|claim-1|name]" + ChatColor.GRAY + " - Teleport to a claim");
        player.sendMessage(ChatColor.YELLOW + "/claim sethome [number|claim-1]" + ChatColor.GRAY + " - Set claim teleport point");
        player.sendMessage(ChatColor.YELLOW + "/claim rename [number|claim-1] <name>" + ChatColor.GRAY + " - Rename a claim");
        player.sendMessage(ChatColor.YELLOW + "/claim trust | untrust <player>" + ChatColor.GRAY + " - Trust or untrust a player");
        player.sendMessage(ChatColor.YELLOW + "/claim ban | unban | kick <player>" + ChatColor.GRAY + " - Manage visitors");
        player.sendMessage(ChatColor.YELLOW + "/claim list | border | blocks" + ChatColor.GRAY + " - View & manage claims");
        player.sendMessage(ChatColor.YELLOW + "/claim expand <blocks> | unclaim" + ChatColor.GRAY + " - Expand or unclaim land");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> allSubs = Arrays.asList(
                "setting", "tp", "sethome", "trust", "untrust", "ban", "unban",
                "kick", "list", "rename", "border", "blocks", "expand",
                "unclaim", "unclaimall", "info", "help"
        );
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> matches = new ArrayList<>();
            for (String s : allSubs) {
