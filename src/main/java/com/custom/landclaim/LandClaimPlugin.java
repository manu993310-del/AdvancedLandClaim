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

    private static final int STARTING_BLOCKS = 100;
    private static final String GUI_TITLE = ChatColor.DARK_GRAY + "Claim Settings";
    private static final String TRUST_GUI_TITLE = ChatColor.DARK_GRAY + "Trusted Players";
    private static final String TP_GUI_TITLE = ChatColor.DARK_GRAY + "Claim Teleport";

    private final List<Claim> claims = new ArrayList<>();
    private final Map<UUID, Integer> playerClaimBlocks = new HashMap<>();
    private final Map<UUID, Location> corner1Map = new HashMap<>();
    private final Map<UUID, BossBar> playerBossBars = new HashMap<>();
    private final Map<UUID, String> currentClaimId = new HashMap<>();
    private final Map<UUID, Claim> editingClaim = new HashMap<>();

    private File dataFile;
    private FileConfiguration dataConfig;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("claim") != null) {
            getCommand("claim").setTabCompleter(this);
        }
        loadData();

        // +10 Claim Blocks every 6 minutes (100 blocks/hour)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                addBlocks(p.getUniqueId(), 10);
                p.sendMessage(ChatColor.GREEN + "+10 Claim Blocks earned! Available: " + ChatColor.GOLD + getAvailableBlocks(p.getUniqueId()));
            }
        }, 7200L, 7200L);

        // ActionBar meter when holding Golden Shovel or Stick
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

        getLogger().info("AdvancedLandClaim v9.0 (Premium SMP Edition) Enabled!");
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
            if (c.number > maxNum) {
                maxNum = c.number;
            }
            if (c.title != null && c.title.toLowerCase().startsWith("claim-")) {
                try {
                    int parsed = Integer.parseInt(c.title.substring(6).trim());
                    if (parsed > maxNum) {
                        maxNum = parsed;
                    }
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

        List<Location> fakeBlockLocs = new ArrayList<>();
        for (int[] c : corners) {
            int y = world.getHighestBlockYAt(c[0], c[1]);
            Location baseLoc = new Location(world, c[0], y, c[1]);
            Location topLoc = new Location(world, c[0], y + 1, c[1]);
            player.sendBlockChange(baseLoc, Material.GOLD_BLOCK.createBlockData());
            player.sendBlockChange(topLoc, Material.GLOWSTONE.createBlockData());
            fakeBlockLocs.add(baseLoc);
            fakeBlockLocs.add(topLoc);
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            for (Location loc : fakeBlockLocs) {
                player.sendBlockChange(loc, loc.getBlock().getBlockData());
            }
        }, 300L);

        double particleY = player.getLocation().getY() + 1.0;
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
                player.sendMessage(ChatColor.YELLOW + "Trusted Players: " + ChatColor.WHITE + found.trusted.size());
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
                player.sendBlockChange(clicked, Material.GOLD_BLOCK.createBlockData());
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (player.isOnline()) player.sendBlockChange(clicked, clicked.getBlock().getBlockData());
                }, 200L);
                player.sendMessage(ChatColor.YELLOW + "Corner 1 set at (" + clicked.getBlockX() + ", " + clicked.getBlockZ() + ")! Right-click Corner 2.");
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
            if (c.title.equalsIgnoreCase(rawInput)) {
                return c;
            }
        }

        String lower = rawInput.toLowerCase();
        for (Claim c : myClaims) {
            if (c.title.toLowerCase().contains(lower)) {
                return c;
            }
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

        // /claim -> Claim 10x10 = 100 blocks area (claim-1, claim-2...)
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
        player.sendMessage(ChatColor.YELLOW + "/claim setting" + ChatColor.GRAY + " - Open Claim Settings menu");
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
                if (s.startsWith(prefix)) matches.add(s);
            }
            return matches;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("tp") || args[0].equalsIgnoreCase("sethome") || args[0].equalsIgnoreCase("rename")) && sender instanceof Player) {
            List<Claim> myClaims = getPlayerClaims(((Player) sender).getUniqueId());
            List<String> suggestions = new ArrayList<>();
            for (Claim c : myClaims) {
                suggestions.add(String.valueOf(c.number));
                suggestions.add(c.title);
            }
            return suggestions;
        }
        return Collections.emptyList();
    }

    // --- PREMIUM SIMPLECLAIMSYSTEM-STYLE "Claim Settings" GUI ---
    private void openClaimSettingsGUI(Player player, Claim claim) {
        editingClaim.put(player.getUniqueId(), claim);
        Inventory gui = Bukkit.createInventory(null, 36, GUI_TITLE);

        ItemStack pane = new ItemStack(Material.CYAN_STAINED_GLASS_PANE);
        ItemMeta pm = pane.getItemMeta();
        if (pm != null) {
            pm.setDisplayName(" ");
            pane.setItemMeta(pm);
        }
        for (int i = 0; i < 36; i++) {
            gui.setItem(i, pane);
        }

        // Row 2: Protection Permissions
        gui.setItem(10, createFlagItem(Material.GRASS_BLOCK, "Build & Break", claim.allowBuild, "Place or break blocks inside claim"));
        gui.setItem(11, createFlagItem(Material.CHEST, "Containers", claim.allowContainers, "Open chests, barrels & shulkers"));
        gui.setItem(12, createFlagItem(Material.OAK_DOOR, "Doors & Gates", claim.allowDoors, "Open doors & fence gates"));
        gui.setItem(13, createFlagItem(Material.DIAMOND_SWORD, "PvP Combat", claim.allowPvP, "Fight other players in claim"));
        gui.setItem(14, createFlagItem(Material.TNT, "Explosions", claim.allowExplosions, "TNT & creeper explosion damage"));
        gui.setItem(15, createFlagItem(Material.ZOMBIE_HEAD, "Mob Spawning", claim.allowMobSpawn, "Hostile monsters spawning"));
        gui.setItem(16, createFlagItem(Material.FLINT_AND_STEEL, "Fire Spread", claim.allowFireSpread, "Fire & lava block spread"));

        // Row 3: Claim Management Actions
        gui.setItem(20, createButton(Material.PLAYER_HEAD, ChatColor.GREEN + "" + ChatColor.BOLD + "Trusted Players",
                Arrays.asList(ChatColor.GRAY + "Members: " + ChatColor.WHITE + claim.trusted.size(), ChatColor.YELLOW + "Click to manage trusted list")));
        gui.setItem(21, createButton(Material.ENDER_PEARL, ChatColor.AQUA + "" + ChatColor.BOLD + "Claim Teleport",
                Collections.singletonList(ChatColor.GRAY + "Open your claims teleport menu")));
        gui.setItem(22, createButton(Material.PAPER, ChatColor.GOLD + "" + ChatColor.BOLD + "Claim Info",
                Arrays.asList(
                        ChatColor.GRAY + "Name: " + ChatColor.AQUA + claim.title + ChatColor.DARK_GRAY + " (#" + claim.number + ")",
                        ChatColor.GRAY + "Size: " + ChatColor.WHITE + claim.getWidth() + "x" + claim.getLength() + " (" + claim.getArea() + " blocks)",
                        ChatColor.GRAY + "Blocks Left: " + ChatColor.GREEN + getAvailableBlocks(claim.owner)
                )));
        gui.setItem(23, createButton(Material.COMPASS, ChatColor.YELLOW + "" + ChatColor.BOLD + "Show Border",
                Collections.singletonList(ChatColor.GRAY + "Show Gold corners & border")));
        gui.setItem(24, createButton(Material.BARRIER, ChatColor.RED + "" + ChatColor.BOLD + "Unclaim",
                Collections.singletonList(ChatColor.GRAY + "Refund " + ChatColor.GOLD + claim.getArea() + " Claim Blocks")));

        player.openInventory(gui);
    }

    private void openTrustedGUI(Player player, Claim claim) {
        editingClaim.put(player.getUniqueId(), claim);
        Inventory gui = Bukkit.createInventory(null, 27, TRUST_GUI_TITLE);

        int slot = 0;
        for (UUID trustedId : claim.trusted) {
            if (slot >= 26) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(trustedId);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(op);
                meta.setDisplayName(ChatColor.GREEN + (op.getName() != null ? op.getName() : trustedId.toString()));
                meta.setLore(Collections.singletonList(ChatColor.RED + "Click to untrust"));
                head.setItemMeta(meta);
            }
            gui.setItem(slot++, head);
        }

        gui.setItem(26, createButton(Material.ARROW, ChatColor.YELLOW + "Back", Collections.singletonList(ChatColor.GRAY + "Back to Claim Settings")));
        player.openInventory(gui);
    }

    private void openClaimTPGUI(Player player) {
        List<Claim> myClaims = getPlayerClaims(player.getUniqueId());
        Inventory gui = Bukkit.createInventory(null, 27, TP_GUI_TITLE);

        for (int i = 0; i < myClaims.size() && i < 27; i++) {
            Claim c = myClaims.get(i);
            ItemStack item = new ItemStack(Material.ENDER_PEARL);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GOLD + "#" + c.number + " " + ChatColor.AQUA + c.title);
                meta.setLore(Arrays.asList(
                        ChatColor.GRAY + "Size: " + ChatColor.WHITE + c.getWidth() + "x" + c.getLength(),
                        ChatColor.GRAY + "Coords: " + ChatColor.YELLOW + c.minX + ", " + c.minZ,
                        ChatColor.GREEN + "Click to teleport"
                ));
                item.setItemMeta(meta);
            }
            gui.setItem(i, item);
        }

        player.openInventory(gui);
    }

    private ItemStack createFlagItem(Material mat, String name, boolean state, String desc) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + name);
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + desc,
                    state ? ChatColor.GREEN + "" + ChatColor.BOLD + "Status: ALLOWED" : ChatColor.RED + "" + ChatColor.BOLD + "Status: DENIED",
                    ChatColor.YELLOW + "Click to toggle"
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createButton(Material mat, String title, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(title);
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

        if (title.equals(TP_GUI_TITLE)) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            List<Claim> myClaims = getPlayerClaims(player.getUniqueId());
            if (slot >= 0 && slot < myClaims.size()) {
                player.closeInventory();
                teleportToClaim(player, myClaims.get(slot));
            }
            return;
        }

        if (title.equals(TRUST_GUI_TITLE)) {
            event.setCancelled(true);
            Claim claim = editingClaim.get(player.getUniqueId());
            if (claim == null) return;
            int slot = event.getRawSlot();
            if (slot == 26) {
                openClaimSettingsGUI(player, claim);
                return;
            }
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && clicked.getType() == Material.PLAYER_HEAD && clicked.getItemMeta() instanceof SkullMeta) {
                SkullMeta sm = (SkullMeta) clicked.getItemMeta();
                if (sm.getOwningPlayer() != null) {
                    claim.trusted.remove(sm.getOwningPlayer().getUniqueId());
                    saveData();
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.1f);
                    openTrustedGUI(player, claim);
                }
            }
            return;
        }

        if (!title.equals(GUI_TITLE)) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 36) return;

        Claim claim = editingClaim.get(player.getUniqueId());
        if (claim == null) return;

        if (slot == 10) claim.allowBuild = !claim.allowBuild;
        else if (slot == 11) claim.allowContainers = !claim.allowContainers;
        else if (slot == 12) claim.allowDoors = !claim.allowDoors;
        else if (slot == 13) claim.allowPvP = !claim.allowPvP;
        else if (slot == 14) claim.allowExplosions = !claim.allowExplosions;
        else if (slot == 15) claim.allowMobSpawn = !claim.allowMobSpawn;
        else if (slot == 16) claim.allowFireSpread = !claim.allowFireSpread;
        else if (slot == 20) {
            openTrustedGUI(player, claim);
            return;
        } else if (slot == 21) {
            openClaimTPGUI(player);
            return;
        } else if (slot == 22) {
            player.closeInventory();
            player.performCommand("claim info");
            return;
        } else if (slot == 23) {
            player.closeInventory();
            showClaimVisuals(player, claim);
            return;
        } else if (slot == 24) {
            player.closeInventory();
            int refunded = claim.getArea();
            claims.remove(claim);
            addBlocks(claim.owner, refunded);
            handleZoneEntry(player, player.getLocation(), false);
            player.sendMessage(ChatColor.YELLOW + "Unclaimed! Refunded " + ChatColor.GOLD + refunded + " Claim Blocks" + ChatColor.YELLOW + ".");
            return;
        } else {
            return;
        }

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.3f);
        saveData();
        openClaimSettingsGUI(player, claim);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
            event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        Player player = event.getPlayer();
        Claim claim = getClaimAt(event.getTo());

        if (claim != null && !claim.owner.equals(player.getUniqueId()) && !player.isOp() && claim.banned.contains(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You are banned from entering " + claim.title + "!");
            return;
        }

        handleZoneEntry(player, event.getTo(), false);
    }

    private void handleZoneEntry(Player player, Location loc, boolean forceTitle) {
        Claim claim = getClaimAt(loc);
        UUID uuid = player.getUniqueId();
        BossBar bar = playerBossBars.get(uuid);
        String prevId = currentClaimId.get(uuid);

        if (claim != null) {
            String bossTitle = ChatColor.YELLOW + "" + ChatColor.BOLD + "⚡ " + claim.title + " ⚡";
            if (bar == null) {
                bar = Bukkit.createBossBar(bossTitle, BarColor.YELLOW, BarStyle.SOLID);
                bar.setProgress(1.0);
                bar.addPlayer(player);
                playerBossBars.put(uuid, bar);
            } else {
                bar.setTitle(bossTitle);
                bar.setProgress(1.0);
            }

            if (forceTitle || !claim.id.equals(prevId)) {
                currentClaimId.put(uuid, claim.id);
                player.sendTitle(
                        ChatColor.GOLD + "" + ChatColor.BOLD + claim.title,
                        ChatColor.YELLOW + "Owner: " + ChatColor.WHITE + claim.ownerName,
                        10, 45, 15
                );
            }
        } else {
            if (prevId != null) {
                currentClaimId.remove(uuid);
                player.sendTitle(
                        ChatColor.GREEN + "" + ChatColor.BOLD + "Wilderness",
                        ChatColor.GRAY + "Unclaimed Territory",
                        10, 30, 10
                );
            }
            if (bar != null) {
                bar.removePlayer(player);
                playerBossBars.remove(uuid);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        corner1Map.remove(uuid);
        currentClaimId.remove(uuid);
        editingClaim.remove(uuid);
        BossBar bar = playerBossBars.remove(uuid);
        if (bar != null) bar.removeAll();
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Claim claim = getClaimAt(event.getBlock().getLocation());
        if (claim != null && !claim.canBypass(event.getPlayer()) && !claim.allowBuild) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "You cannot break blocks in " + claim.title + "!");
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        Claim claim = getClaimAt(event.getBlock().getLocation());
        if (claim != null && !claim.canBypass(event.getPlayer()) && !claim.allowBuild) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "You cannot place blocks in " + claim.title + "!");
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
                event.getPlayer().sendMessage(ChatColor.RED + "Containers are locked in " + claim.title + "!");
            }
        } else if (name.contains("DOOR") || name.contains("GATE")) {
            if (!claim.allowDoors) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "Doors are locked in " + claim.title + "!");
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
        if (attacker == null || !(event.getEntity() instanceof Player)) return;

        Claim claim = getClaimAt(event.getEntity().getLocation());
        if (claim != null && !claim.allowPvP) {
            event.setCancelled(true);
            attacker.sendMessage(ChatColor.RED + "PvP is disabled in " + claim.title + "!");
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

    private void saveData() {
        if (dataFile == null) dataFile = new File(getDataFolder(), "claims.yml");
        dataConfig = new YamlConfiguration();

        for (Map.Entry<UUID, Integer> entry : playerClaimBlocks.entrySet()) {
            dataConfig.set("blocks." + entry.getKey().toString(), entry.getValue());
        }

        for (Claim c : claims) {
            String path = "claims." + c.id;
            dataConfig.set(path + ".number", c.number);
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
            dataConfig.set(path + ".allowPvP", c.allowPvP);
            dataConfig.set(path + ".allowExplosions", c.allowExplosions);
            dataConfig.set(path + ".allowMobSpawn", c.allowMobSpawn);
            dataConfig.set(path + ".allowFireSpread", c.allowFireSpread);
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

        Map<UUID, Integer> ownerCounter = new HashMap<>();
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

                int nextDefault = ownerCounter.getOrDefault(owner, 0) + 1;
                ownerCounter.put(owner, nextDefault);

                Claim c = new Claim(id, owner, ownerName, world, minX, maxX, minZ, maxZ);
                c.number = dataConfig.getInt(path + ".number", nextDefault);
                c.title = dataConfig.getString(path + ".title", "claim-" + c.number);
                c.home = dataConfig.getLocation(path + ".home");

                for (String s : dataConfig.getStringList(path + ".trusted")) c.trusted.add(UUID.fromString(s));
                for (String s : dataConfig.getStringList(path + ".banned")) c.banned.add(UUID.fromString(s));

                c.allowBuild = dataConfig.getBoolean(path + ".allowBuild", false);
                c.allowContainers = dataConfig.getBoolean(path + ".allowContainers", false);
                c.allowDoors = dataConfig.getBoolean(path + ".allowDoors", false);
                c.allowPvP = dataConfig.getBoolean(path + ".allowPvP", false);
                c.allowExplosions = dataConfig.getBoolean(path + ".allowExplosions", false);
                c.allowMobSpawn = dataConfig.getBoolean(path + ".allowMobSpawn", true);
                c.allowFireSpread = dataConfig.getBoolean(path + ".allowFireSpread", false);

                claims.add(c);
            }
        }
    }

    private static class Claim {
        String id;
        int number = 1;
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
        boolean allowPvP = false;
        boolean allowExplosions = false;
        boolean allowMobSpawn = true;
        boolean allowFireSpread = false;

        Claim(String id, UUID owner, String ownerName, Location l1, Location l2) {
            this(id, owner, ownerName, l1.getWorld().getName(),
                    Math.min(l1.getBlockX(), l2.getBlockX()), Math.max(l1.getBlockX(), l2.getBlockX()),
                    Math.min(l1.getBlockZ(), l2.getBlockZ()), Math.max(l1.getBlockZ(), l2.getBlockZ()));
        }

        Claim(String id, UUID owner, String ownerName, String world, int minX, int maxX, int minZ, int maxZ) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.title = "claim-1";
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
