package com.stickmoney;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class StickmanMoneySMP extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    static final String[] TIERS = {"S", "A", "B", "C", "D", "F"};

    /** Event keys, in menu order. */
    static final String[] EVENT_KEYS = {"capture-points", "ctf", "gladiators", "finale",
            "money-trees", "anonymous", "double-dough", "opposite-day"};

    /** Events that actually do something right now. The others are saved but not built yet. */
    static final List<String> BUILT_EVENTS = List.of("capture-points", "double-dough", "opposite-day", "money-trees", "anonymous", "ctf", "finale", "gladiators");

    /** Potion effects this plugin manages for debt and wealth. */
    private static final PotionEffectType[] MANAGED = {
            PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS,
            PotionEffectType.STRENGTH, PotionEffectType.SPEED};

    private static final long EFFECT_DURATION_TICKS = 60L;

    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<UUID, String> tiers = new HashMap<>();
    private final Map<String, Boolean> events = new LinkedHashMap<>();
    private final Map<UUID, AttributeModifier> heartMods = new HashMap<>();
    private final Set<UUID> anonApplied = new HashSet<>();

    // Team points and Capture the Flag
    private final Map<String, Integer> scores = new HashMap<>();
    private NamespacedKey ctfKey;
    private boolean ctfBuilt;
    private String ctfWorld;
    private int ctfX, ctfY, ctfZ;
    private UUID flagCarrier;

    // Finale: the world border shrinks to a chosen area
    private String finaleWorld;
    private double finaleX, finaleZ, finaleFinalSize = -1;
    private double savedBorderX, savedBorderZ, savedBorderSize = -1;

    // Gladiators: 3 vs 3 kit fights between two teams
    private final Map<String, ItemStack[]> gladKits = new LinkedHashMap<>();
    private Location glaCentre;
    private boolean glaSky;
    private boolean glaBuilding;
    private boolean glaDirty;
    private Location glaSpawnA, glaSpawnB;
    private String glaTeamA, glaTeamB;
    private String glaKit;
    private boolean glaActive, glaFighting, glaSpinning;
    private final Set<UUID> glaSideA = new HashSet<>();
    private final Set<UUID> glaSideB = new HashSet<>();
    private final Set<UUID> glaOut = new HashSet<>();
    private final Map<UUID, GlaSnap> glaSaved = new HashMap<>();
    private final Map<UUID, GlaSnap> glaRespawn = new HashMap<>();
    private final Map<UUID, GlaSnap> glaPending = new HashMap<>();

    private record GlaSnap(ItemStack[] contents, Location origin) {}

    /** Marks the kit wheel inventory so clicks can be cancelled. */
    private static final class WheelHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
    private final Random rnd = new Random();

    // Capture points
    private static final int MOUNTAIN_R = 40;
    private static final int MOUNTAIN_H = 36;
    private static final int PLATEAU_R = 10;
    private static final int POINT_R = 7;
    private static final double CAP_RADIUS = 3.0;
    private static final int BUILD_PER_TICK = 5000;

    private record BuildOp(World w, int x, int y, int z, Material m) {}

    private final Map<UUID, String> teams = new HashMap<>();
    private final boolean[] captured = new boolean[6];
    private final int[] progress = new int[6];
    private final String[] holder = new String[6];
    private boolean capBuilt;
    private String capWorld;
    private int capX, capY, capZ;
    private int currentSet;
    private long setStart;

    private File dataFile;
    private YamlConfiguration data;
    private NamespacedKey noteKey;
    private NamespacedKey heartKey;

    /** Marks the menu inventory so clicks can be handled. */
    private static final class MenuHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    // ---------- Setup ----------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        noteKey = new NamespacedKey(this, "money-note");
        heartKey = new NamespacedKey(this, "debt-hearts");
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, this::resumeInterrupted, 40L);
        if (getCommand("msmp") != null) {
            getCommand("msmp").setExecutor(this);
            getCommand("msmp").setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::captureTick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::ctfTick, 20L, 20L);
        ctfKey = new NamespacedKey(this, "ctf-flag");
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void loadData() {
        balances.clear();
        tiers.clear();
        ConfigurationSection bal = data.getConfigurationSection("balances");
        if (bal != null) {
            for (String key : bal.getKeys(false)) {
                try {
                    balances.put(UUID.fromString(key), bal.getLong(key));
                } catch (IllegalArgumentException ignored) {
                    getLogger().warning("Skipping bad player id in data.yml: " + key);
                }
            }
        }
        ConfigurationSection tierSec = data.getConfigurationSection("tiers");
        if (tierSec != null) {
            for (String key : tierSec.getKeys(false)) {
                try {
                    tiers.put(UUID.fromString(key), tierSec.getString(key));
                } catch (IllegalArgumentException ignored) {
                    getLogger().warning("Skipping bad player id in data.yml: " + key);
                }
            }
        }
        teams.clear();
        ConfigurationSection teamSec = data.getConfigurationSection("teams");
        if (teamSec != null) {
            for (String key : teamSec.getKeys(false)) {
                try {
                    teams.put(UUID.fromString(key), teamSec.getString(key));
                } catch (IllegalArgumentException ignored) {
                    getLogger().warning("Skipping bad player id in data.yml: " + key);
                }
            }
        }
        capBuilt = data.getBoolean("capture.built", false);
        capWorld = data.getString("capture.world");
        capX = data.getInt("capture.x");
        capY = data.getInt("capture.y");
        capZ = data.getInt("capture.z");
        currentSet = data.getInt("capture.set", 0);
        for (int i = 0; i < 6; i++) captured[i] = data.getBoolean("capture.captured." + i, false);
        scores.clear();
        ConfigurationSection scoreSec = data.getConfigurationSection("scores");
        if (scoreSec != null) {
            for (String team : scoreSec.getKeys(false)) scores.put(team, scoreSec.getInt(team));
        }
        ctfBuilt = data.getBoolean("ctf.built", false);
        glaSpawnA = data.getLocation("gladiators.a");
        glaSpawnB = data.getLocation("gladiators.b");
        glaTeamA = data.getString("gladiators.team-a");
        glaTeamB = data.getString("gladiators.team-b");
        glaCentre = data.getLocation("gladiators.centre");
        glaSky = data.getBoolean("gladiators.sky", false);
        glaDirty = data.getBoolean("gladiators.dirty", false);
        gladKits.clear();
        ConfigurationSection kitSec = data.getConfigurationSection("gladiator-kits");
        if (kitSec != null) {
            for (String name : kitSec.getKeys(false)) gladKits.put(name, toItems(kitSec.getList(name, List.of())));
        }
        if (gladKits.isEmpty()) gladKits.putAll(gladiatorKits());
        finaleWorld = data.getString("finale.world");
        finaleX = data.getDouble("finale.x");
        finaleZ = data.getDouble("finale.z");
        finaleFinalSize = data.getDouble("finale.final-size", -1);
        savedBorderX = data.getDouble("finale.saved.x");
        savedBorderZ = data.getDouble("finale.saved.z");
        savedBorderSize = data.getDouble("finale.saved.size", -1);
        ctfWorld = data.getString("ctf.world");
        ctfX = data.getInt("ctf.x");
        ctfY = data.getInt("ctf.y");
        ctfZ = data.getInt("ctf.z");
        events.clear();
        for (String key : EVENT_KEYS) {
            events.put(key, data.getBoolean("events." + key, getConfig().getBoolean("events." + key, false)));
        }
    }

    private void saveData() {
        YamlConfiguration out = new YamlConfiguration();
        for (Map.Entry<UUID, Long> en : balances.entrySet()) out.set("balances." + en.getKey(), en.getValue());
        for (Map.Entry<UUID, String> en : tiers.entrySet()) out.set("tiers." + en.getKey(), en.getValue());
        for (Map.Entry<String, Boolean> en : events.entrySet()) out.set("events." + en.getKey(), en.getValue());
        for (Map.Entry<UUID, String> en : teams.entrySet()) out.set("teams." + en.getKey(), en.getValue());
        out.set("capture.built", capBuilt);
        out.set("capture.world", capWorld);
        out.set("capture.x", capX);
        out.set("capture.y", capY);
        out.set("capture.z", capZ);
        out.set("capture.set", currentSet);
        for (int i = 0; i < 6; i++) out.set("capture.captured." + i, captured[i]);
        for (Map.Entry<String, Integer> en : scores.entrySet()) out.set("scores." + en.getKey(), en.getValue());
        out.set("ctf.built", ctfBuilt);
        out.set("gladiators.a", glaSpawnA);
        out.set("gladiators.b", glaSpawnB);
        out.set("gladiators.team-a", glaTeamA);
        out.set("gladiators.team-b", glaTeamB);
        out.set("gladiators.centre", glaCentre);
        out.set("gladiators.sky", glaSky);
        out.set("gladiators.dirty", glaDirty);
        for (Map.Entry<String, ItemStack[]> en : gladKits.entrySet()) out.set("gladiator-kits." + en.getKey(), Arrays.asList(en.getValue()));
        out.set("finale.world", finaleWorld);
        out.set("finale.x", finaleX);
        out.set("finale.z", finaleZ);
        out.set("finale.final-size", finaleFinalSize);
        out.set("finale.saved.x", savedBorderX);
        out.set("finale.saved.z", savedBorderZ);
        out.set("finale.saved.size", savedBorderSize);
        out.set("ctf.world", ctfWorld);
        out.set("ctf.x", ctfX);
        out.set("ctf.y", ctfY);
        out.set("ctf.z", ctfZ);
        data = out;
        try {
            out.save(dataFile);
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not save data.yml", ex);
        }
    }

    // ---------- Money and tiers ----------

    private long balance(UUID id) {
        return balances.getOrDefault(id, getConfig().getLong("starting-balance", 100));
    }

    private void setBalance(UUID id, long value) {
        balances.put(id, value);
    }

    private boolean eventOn(String key) {
        return events.getOrDefault(key, false);
    }

    private static ItemStack[] toItems(List<?> raw) {
        ItemStack[] c = new ItemStack[41];
        for (int i = 0; i < Math.min(41, raw.size()); i++) {
            if (raw.get(i) instanceof ItemStack is) c[i] = is;
        }
        return c;
    }

    private static int tierIndex(String tier) {
        return Arrays.asList(TIERS).indexOf(tier.toUpperCase(Locale.ROOT));
    }

    /** Money taken from a victim of this tier, taking the active events into account. */
    private long stealAmount(String tier) {
        if (tier == null) return getConfig().getLong("unranked-value", 0);
        String used = tier;
        if (eventOn("opposite-day")) used = TIERS[TIERS.length - 1 - tierIndex(tier)];
        long base = getConfig().getLong("tier-values." + used, 0);
        if (eventOn("double-dough") && tier.equalsIgnoreCase(getConfig().getString("double-dough-tier", "S"))) {
            base *= 2;
        }
        return base;
    }

    private String tierPrefix(String tier) {
        String hex = getConfig().getString("tier-colors." + tier, "#FFFFFF");
        return ChatColor.of(hex) + "" + ChatColor.BOLD + tier + " TIER " + ChatColor.RESET;
    }

    /** One scoreboard team per player, holding the tier (prefix) and money (suffix) above their name. */
    private Team teamFor(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String name = "m" + p.getUniqueId().toString().replace("-", "").substring(0, 15);
        Team t = sb.getTeam(name);
        if (t == null) t = sb.registerNewTeam(name);
        if (!t.hasEntry(p.getName())) t.addEntry(p.getName());
        return t;
    }

    /** Updates the name tag, tab list name, and money shown above the name. */
    private void refresh(Player p) {
        boolean anon = eventOn("anonymous");
        String tier = tiers.get(p.getUniqueId());
        String prefix = anon || tier == null ? "" : tierPrefix(tier);
        Team t = teamFor(p);
        t.setPrefix(prefix);
        t.setSuffix(anon ? "" : ChatColor.GREEN + "" + ChatColor.BOLD + " $" + balance(p.getUniqueId()));
        t.setOption(Team.Option.NAME_TAG_VISIBILITY, anon ? Team.OptionStatus.NEVER : Team.OptionStatus.ALWAYS);
        p.setPlayerListName(anon ? ChatColor.GRAY + "Anonymous" : prefix + ChatColor.BOLD + p.getName());
    }

    // ---------- Debt and wealth effects ----------

    private void applyEffects(Player p, long bal) {
        Map<PotionEffectType, Integer> want = new HashMap<>();
        int heartsLost = 0;
        if (bal < 0) want.put(PotionEffectType.SLOWNESS, 0);
        if (bal <= -100) {
            want.put(PotionEffectType.WEAKNESS, 0);
            heartsLost = 2;
        }
        if (bal <= -250) {
            want.put(PotionEffectType.SLOWNESS, 1);
            want.put(PotionEffectType.WEAKNESS, 1);
            heartsLost = 4;
        }
        if (bal >= 500) want.put(PotionEffectType.STRENGTH, 0);
        if (bal >= 1000) {
            want.put(PotionEffectType.STRENGTH, 1);
            want.put(PotionEffectType.SPEED, 0);
        }

        for (Map.Entry<PotionEffectType, Integer> en : want.entrySet()) {
            p.addPotionEffect(new PotionEffect(en.getKey(), (int) EFFECT_DURATION_TICKS, en.getValue(), false, false, true));
        }
        for (PotionEffectType type : MANAGED) {
            if (!want.containsKey(type) && p.hasPotionEffect(type)) p.removePotionEffect(type);
        }

        AttributeInstance maxHealth = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealth == null) return;
        AttributeModifier old = heartMods.remove(p.getUniqueId());
        if (old != null) maxHealth.removeModifier(old);
        if (heartsLost > 0) {
            AttributeModifier mod = new AttributeModifier(heartKey, -2.0 * heartsLost, AttributeModifier.Operation.ADD_NUMBER);
            maxHealth.addModifier(mod);
            heartMods.put(p.getUniqueId(), mod);
        }
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            long bal = balance(p.getUniqueId());
            applyEffects(p, bal);
            if (eventOn("anonymous")) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 60, 0, false, false, false));
                anonApplied.add(p.getUniqueId());
            } else if (anonApplied.remove(p.getUniqueId()) && p.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                p.removePotionEffect(PotionEffectType.INVISIBILITY);
            }
            refresh(p);
            p.sendActionBar(Component.text("Money: $" + bal, NamedTextColor.GREEN));
        }
    }

    // ---------- Chat ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent e) {
        Player sender = e.getPlayer();
        boolean anon = eventOn("anonymous");
        String tier = tiers.get(sender.getUniqueId());
        Component tierPart;
        if (anon || tier == null) {
            tierPart = Component.empty();
        } else {
            TextColor colour = TextColor.fromHexString(getConfig().getString("tier-colors." + tier, "#FFFFFF"));
            tierPart = Component.text(tier + " TIER ", colour == null ? NamedTextColor.WHITE : colour, TextDecoration.BOLD);
        }
        final Component finalTier = tierPart;
        Component name = Component.text(anon ? "Anonymous" : sender.getName(), NamedTextColor.WHITE, TextDecoration.BOLD);
        e.renderer((source, displayName, message, viewer) -> finalTier
                .append(name)
                .append(Component.text(": ", NamedTextColor.WHITE))
                .append(message));
    }

    // ---------- Joining, leaving, kills ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        balances.putIfAbsent(p.getUniqueId(), getConfig().getLong("starting-balance", 100));
        refresh(p);
        GlaSnap pending = glaPending.remove(p.getUniqueId());
        if (pending != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) glaRestore(p, pending);
            }, 20L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        gladQuit(e.getPlayer());
        if (flagCarrier != null && flagCarrier.equals(e.getPlayer().getUniqueId())) returnFlag();
        saveData();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return;
        long amount = stealAmount(tiers.get(victim.getUniqueId()));
        if (amount <= 0) return;
        setBalance(victim.getUniqueId(), balance(victim.getUniqueId()) - amount);
        setBalance(killer.getUniqueId(), balance(killer.getUniqueId()) + amount);
        victim.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "You lost $" + amount + " to " + killer.getName() + ".");
        killer.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "You stole $" + amount + " from " + victim.getName() + ".");
        refresh(victim);
        refresh(killer);
        saveData();
    }

    // ---------- Team points ----------

    private void scoreCommand(CommandSender sender, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "show";
        if (sub.equals("show")) {
            sender.sendMessage(ChatColor.GOLD + "--- Team points ---");
            if (scores.isEmpty()) sender.sendMessage(ChatColor.GRAY + "No points yet.");
            for (Map.Entry<String, Integer> en : scores.entrySet()) {
                sender.sendMessage(ChatColor.WHITE + en.getKey() + ChatColor.GRAY + ": " + en.getValue());
            }
            return;
        }
        if (!isAdmin(sender)) { sender.sendMessage(ChatColor.RED + "Operators only."); return; }
        if (sub.equals("reset")) {
            scores.clear();
            saveData();
            sender.sendMessage(ChatColor.GREEN + "All team points reset.");
            return;
        }
        if (sub.equals("add")) {
            if (args.length < 4 || !args[3].matches("-?\\d+")) {
                sender.sendMessage(ChatColor.RED + "Usage: /msmp score add <team> <points>");
                return;
            }
            int n = Integer.parseInt(args[3]);
            scores.merge(args[2], n, Integer::sum);
            saveData();
            sender.sendMessage(ChatColor.GREEN + args[2] + " now has " + scores.get(args[2]) + " points.");
            return;
        }
        sender.sendMessage(ChatColor.RED + "Usage: /msmp score <show|add|reset>");
    }

    // ---------- Capture the Flag ----------

    private static final String FLAG_NAME = ChatColor.RED + "" + ChatColor.BOLD + "Flag";
    private static final int CTF_BASE_R = 4;

    private boolean isFlag(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(ctfKey, PersistentDataType.BYTE);
    }

    private Location flagHome() {
        World w = ctfWorld == null ? null : Bukkit.getWorld(ctfWorld);
        return w == null ? null : new Location(w, ctfX, ctfY + 7, ctfZ);
    }

    private void placeFlagHome() {
        Location home = flagHome();
        if (home != null) home.getBlock().setType(Material.RED_BANNER, false);
    }

    private void buildFlagTower(Player admin) {
        World w = admin.getWorld();
        int ox = admin.getLocation().getBlockX();
        int oz = admin.getLocation().getBlockZ();
        int floorY = admin.getLocation().getBlockY() - 1;
        while (floorY > w.getMinHeight() && w.getBlockAt(ox, floorY, oz).isPassable()) floorY--;
        int oy = floorY + 1;
        returnFlag();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = 0; y <= 5; y++) w.getBlockAt(ox + dx, oy + y, oz + dz).setType(Material.STONE_BRICKS, false);
                w.getBlockAt(ox + dx, oy + 6, oz + dz).setType(Material.POLISHED_ANDESITE, false);
            }
        }
        for (int y = 0; y <= 6; y++) {
            Ladder ladder = (Ladder) Material.LADDER.createBlockData();
            ladder.setFacing(BlockFace.NORTH);
            w.getBlockAt(ox, oy + y, oz + 2).setBlockData(ladder, false);
        }
        ctfBuilt = true;
        ctfWorld = w.getName();
        ctfX = ox;
        ctfY = oy;
        ctfZ = oz;
        placeFlagHome();
        saveData();
        admin.sendMessage(ChatColor.GREEN + "Capture the flag tower built. Climb the ladder and right-click the flag.");
    }

    /** Puts the flag back on top of the tower and removes any copies being carried. */
    private void returnFlag() {
        if (flagCarrier != null) {
            Player c = Bukkit.getPlayer(flagCarrier);
            if (c != null) {
                ItemStack[] contents = c.getInventory().getContents();
                for (int i = 0; i < contents.length; i++) {
                    if (isFlag(contents[i])) c.getInventory().setItem(i, null);
                }
            }
            flagCarrier = null;
        }
        if (ctfBuilt) {
            placeFlagHome();
            Bukkit.broadcastMessage(ChatColor.GOLD + "The flag is back on the tower.");
        }
    }

    private ItemStack flagItem() {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(FLAG_NAME);
        m.setLore(List.of(ChatColor.GRAY + "Carry this back to your base to score."));
        m.getPersistentDataContainer().set(ctfKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private void ctfCommand(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) { sender.sendMessage(ChatColor.RED + "Operators only."); return; }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("setup")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
            buildFlagTower(p);
        } else if (sub.equals("reset")) {
            returnFlag();
            sender.sendMessage(ChatColor.GREEN + "The flag is back on the tower.");
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /msmp ctf <setup|reset>");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFlagPickup(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (!eventOn("ctf") || !ctfBuilt || flagCarrier != null) return;
        Location home = flagHome();
        if (home == null || !e.getClickedBlock().getLocation().equals(home)) return;
        Player p = e.getPlayer();
        if (teams.get(p.getUniqueId()) == null) {
            p.sendMessage(ChatColor.RED + "Only players on a team can take the flag.");
            e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        home.getBlock().setType(Material.AIR, false);
        flagCarrier = p.getUniqueId();
        p.getInventory().addItem(flagItem());
        Bukkit.broadcastMessage(ChatColor.GOLD + p.getName() + " took the flag! Bring it back to base.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFlagDrop(PlayerDropItemEvent e) {
        if (!isFlag(e.getItemDrop().getItemStack())) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(ChatColor.RED + "You cannot drop the flag.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFlagDeath(PlayerDeathEvent e) {
        if (flagCarrier == null || !flagCarrier.equals(e.getEntity().getUniqueId())) return;
        e.getDrops().removeIf(this::isFlag);
        Bukkit.broadcastMessage(ChatColor.RED + e.getEntity().getName() + " dropped the flag!");
        returnFlag();
    }

    private void ctfTick() {
        if (!ctfBuilt || !eventOn("ctf") || flagCarrier == null) return;
        Player c = Bukkit.getPlayer(flagCarrier);
        if (c == null) return;
        String team = teams.get(c.getUniqueId());
        Location l = c.getLocation();
        boolean atBase = l.getWorld() != null && l.getWorld().getName().equals(ctfWorld)
                && Math.hypot(l.getX() - ctfX, l.getZ() - ctfZ) <= CTF_BASE_R && Math.abs(l.getY() - ctfY) <= 4;
        if (team == null || !atBase) return;
        scores.merge(team, 1, Integer::sum);
        saveData();
        Bukkit.broadcastMessage(ChatColor.GREEN + "Team " + team + " scored a flag capture! Points: " + scores.get(team));
        returnFlag();
    }

    // ---------- Finale ----------

    private void finaleCommand(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) { sender.sendMessage(ChatColor.RED + "Operators only."); return; }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        if (sub.equals("area")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
            double size = getConfig().getDouble("finale.final-size", 100);
            if (args.length >= 3) {
                try {
                    size = Double.parseDouble(args[2]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(ChatColor.RED + "Final size must be a number of blocks.");
                    return;
                }
            }
            if (size < 2 || size > 29999984) { sender.sendMessage(ChatColor.RED + "Final size must be between 2 and 29999984."); return; }
            finaleWorld = p.getWorld().getName();
            finaleX = p.getLocation().getBlockX() + 0.5;
            finaleZ = p.getLocation().getBlockZ() + 0.5;
            finaleFinalSize = size;
            saveData();
            sender.sendMessage(ChatColor.GREEN + "Finale area set here, closing to " + (int) size + " blocks across.");
        } else if (sub.equals("duration")) {
            if (args.length < 3 || !args[2].matches("\\d+")) { sender.sendMessage(ChatColor.RED + "Usage: /msmp finale duration <minutes>"); return; }
            long mins = Long.parseLong(args[2]);
            if (mins < 1 || mins > 240) { sender.sendMessage(ChatColor.RED + "Duration must be 1 to 240 minutes."); return; }
            getConfig().set("finale.duration-minutes", mins);
            saveConfig();
            sender.sendMessage(ChatColor.GREEN + "The border will close over " + mins + " minutes.");
        } else {
            if (finaleWorld == null) {
                sender.sendMessage(ChatColor.YELLOW + "No finale area set. Use /msmp finale area [finalSize] where you want the border to close.");
            } else {
                sender.sendMessage(ChatColor.GOLD + "Finale area: " + finaleWorld + " (" + finaleX + ", " + finaleZ + "), final size "
                        + (int) finaleFinalSize + ", closes over " + getConfig().getLong("finale.duration-minutes", 15) + " minutes.");
            }
        }
    }

    private void finaleStart() {
        World w = finaleWorld == null ? null : Bukkit.getWorld(finaleWorld);
        if (w == null || finaleFinalSize <= 0) {
            Bukkit.broadcastMessage(ChatColor.YELLOW + "The finale can't start yet: no finale area is set.");
            events.put("finale", false);
            saveData();
            return;
        }
        WorldBorder b = w.getWorldBorder();
        if (savedBorderSize < 0) {
            savedBorderX = b.getCenter().getX();
            savedBorderZ = b.getCenter().getZ();
            savedBorderSize = b.getSize();
        }
        long seconds = getConfig().getLong("finale.duration-minutes", 15) * 60L;
        b.setCenter(finaleX, finaleZ);
        b.setSize(finaleFinalSize, seconds);
        saveData();
        Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "The finale has started. The border is closing.");
    }

    private void finaleStop() {
        World w = finaleWorld == null ? null : Bukkit.getWorld(finaleWorld);
        if (w != null && savedBorderSize >= 0) {
            WorldBorder b = w.getWorldBorder();
            b.setCenter(savedBorderX, savedBorderZ);
            b.setSize(savedBorderSize);
            Bukkit.broadcastMessage(ChatColor.GREEN + "The finale is over. The border is back to normal.");
        }
        savedBorderSize = -1;
        saveData();
    }

    // ---------- Gladiator arenas and kits (copied from StickDuels) ----------

    static final int ARENA_R = 55;
    static final double BARRIER_RADIUS = 28.0;
    static final int CONTENTS_SIZE = 41;
    static final int ARENA_SPAWN_OFFSET = 22;

    private record ArenaOp(World w, int x, int y, int z, Material m, BlockFace facing) {}

    private List<ArenaOp> arenaQueue = new ArrayList<>();

    // ---------- Kits ----------

    /** Gear with enchantments, given as pairs: Enchantment, level. Unsafe levels are allowed on purpose. */
    private static ItemStack gear(Material m, Object... pairs) {
        ItemStack it = new ItemStack(m);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            it.addUnsafeEnchantment((Enchantment) pairs[i], (Integer) pairs[i + 1]);
        }
        return it;
    }

    private static ItemStack armor(Material m) {
        return gear(m, Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1);
    }

    private static ItemStack[] netherite() {
        return new ItemStack[]{armor(Material.NETHERITE_BOOTS), armor(Material.NETHERITE_LEGGINGS),
                armor(Material.NETHERITE_CHESTPLATE), armor(Material.NETHERITE_HELMET)};
    }

    private static ItemStack[] diamond() {
        return new ItemStack[]{armor(Material.DIAMOND_BOOTS), armor(Material.DIAMOND_LEGGINGS),
                armor(Material.DIAMOND_CHESTPLATE), armor(Material.DIAMOND_HELMET)};
    }

    /** Builds a 41-slot kit: hotbar and main inventory, then boots, leggings, chest, helmet, offhand. */
    private static ItemStack[] kit(List<ItemStack> items, ItemStack[] armorPieces, ItemStack offhand) {
        ItemStack[] c = new ItemStack[CONTENTS_SIZE];
        for (int i = 0; i < items.size() && i < 36; i++) c[i] = items.get(i);
        if (armorPieces != null) {
            for (int i = 0; i < 4; i++) c[36 + i] = armorPieces[i];
        }
        c[40] = offhand;
        return c;
    }

    private Map<String, ItemStack[]> gladiatorKits() {
        Material spearMat = Material.matchMaterial("NETHERITE_SPEAR");
        if (spearMat == null) spearMat = Material.NETHERITE_SWORD;
        ItemStack shield = new ItemStack(Material.SHIELD);
        ItemStack totem = new ItemStack(Material.TOTEM_OF_UNDYING);

        Map<String, ItemStack[]> m = new LinkedHashMap<>();
        m.put("Sword", kit(List.of(
                gear(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5, Enchantment.FIRE_ASPECT, 2, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1),
                new ItemStack(Material.GOLDEN_APPLE, 8),
                new ItemStack(Material.ENDER_PEARL, 4)), netherite(), shield));
        m.put("Axe", kit(List.of(
                gear(Material.NETHERITE_AXE, Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1),
                new ItemStack(Material.GOLDEN_APPLE, 8),
                new ItemStack(Material.ENDER_PEARL, 2)), netherite(), shield));
        m.put("Mace", kit(List.of(
                gear(Material.MACE, Enchantment.DENSITY, 5, Enchantment.BREACH, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1),
                new ItemStack(Material.GOLDEN_APPLE, 8),
                new ItemStack(Material.ENDER_PEARL, 2)), netherite(), shield));
        m.put("Spear", kit(List.of(
                gear(spearMat, Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1),
                new ItemStack(Material.GOLDEN_APPLE, 8)), netherite(), totem));
        m.put("Archer", kit(List.of(
                gear(Material.BOW, Enchantment.POWER, 5, Enchantment.INFINITY, 1, Enchantment.PUNCH, 2, Enchantment.UNBREAKING, 3),
                gear(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 4, Enchantment.UNBREAKING, 3),
                new ItemStack(Material.ARROW, 1),
                new ItemStack(Material.GOLDEN_APPLE, 8)), diamond(), null));
        m.put("Sumo", kit(List.of(
                gear(Material.STICK, Enchantment.KNOCKBACK, 3)), null, null));
        return m;
    }

    private void put(World w, int x, int y, int z, Material m) {
        arenaQueue.add(new ArenaOp(w, x, y, z, m, null));
    }

    private void putStair(World w, int x, int y, int z, Material stair, BlockFace facing) {
        arenaQueue.add(new ArenaOp(w, x, y, z, stair, facing));
    }

    private static BlockFace inward(int x, int z) {
        if (Math.abs(x) >= Math.abs(z)) return x > 0 ? BlockFace.WEST : BlockFace.EAST;
        return z > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
    }

    /** Queues a floating rock underside below the arena floor, tapering as it goes down. */
    private void buildIsland(World w, int cx, int by, int cz) {
        int R = ARENA_R;
        for (int k = 1; k <= 14; k++) {
            int radius = R - k * 3;
            if (radius <= 0) break;
            Material m = k <= 2 ? Material.POLISHED_ANDESITE : (k <= 7 ? Material.STONE : Material.DEEPSLATE);
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + z * z > radius * radius) continue;
                    put(w, cx + x, by - k, cz + z, m);
                }
            }
        }
    }

    /** Queues one large themed arena centred on (cx, by, cz). */
    private void buildArena(World w, int cx, int by, int cz, boolean sky) {
        int R = ARENA_R;

        if (sky) buildIsland(w, cx, by, cz);

        // Clear the airspace.
        for (int x = -R - 5; x <= R + 5; x++) {
            for (int z = -R - 5; z <= R + 5; z++) {
                if (x * x + z * z > (R + 5) * (R + 5)) continue;
                for (int y = 1; y <= 26; y++) put(w, cx + x, by + y, cz + z, Material.AIR);
            }
        }

        // Moat: a water ring outside the wall on a stone bed.
        for (int x = -R - 5; x <= R + 5; x++) {
            for (int z = -R - 5; z <= R + 5; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= R + 2 || d > R + 4) continue;
                put(w, cx + x, by - 1, cz + z, Material.STONE_BRICKS);
                put(w, cx + x, by, cz + z, Material.WATER);
            }
        }

        // Floor: inner polished checker, gold inlay ring, outer prismarine checker, chiselled rim, sea lantern grid.
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > R) continue;
                Material m;
                if (d > R - 1.5) m = Material.CHISELED_STONE_BRICKS;
                else if (d >= 20 && d < 21) m = Material.GOLD_BLOCK;
                else if (d < 20) m = ((x + z) & 1) == 0 ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS;
                else m = ((x + z) & 1) == 0 ? Material.PRISMARINE_BRICKS : Material.DARK_PRISMARINE;
                if (m != Material.GOLD_BLOCK && x % 12 == 0 && z % 12 == 0 && d < R - 3) m = Material.SEA_LANTERN;
                put(w, cx + x, by, cz + z, m);
            }
        }

        // Amethyst clusters around the rim.
        for (int k = 0; k < 24; k++) {
            double ang = Math.toRadians(7.5 + 15 * k);
            int ax = (int) Math.round((R - 3) * Math.cos(ang));
            int az = (int) Math.round((R - 3) * Math.sin(ang));
            put(w, cx + ax, by + 1, cz + az, Material.AMETHYST_CLUSTER);
        }

        // Crenellated outer wall, with lanterns on the tall sections.
        for (int x = -R - 2; x <= R + 2; x++) {
            for (int z = -R - 2; z <= R + 2; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= R || d > R + 1.2) continue;
                boolean tall = (Math.abs(x) + Math.abs(z)) % 2 == 0;
                int h = tall ? 5 : 2;
                for (int y = 1; y <= h; y++) put(w, cx + x, by + y, cz + z, Material.STONE_BRICK_WALL);
                if (tall && (Math.abs(x) + Math.abs(z)) % 12 == 0) put(w, cx + x, by + 6, cz + z, Material.LANTERN);
            }
        }

        // Four gates: a stone bridge over the moat, a gap in the wall, two pillars, a lintel and a lantern.
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : dirs) {
            int dx = dir[0], dz = dir[1];
            int lx = -dz, lz = dx;
            for (int s = R + 2; s <= R + 4; s++) {
                for (int l = -2; l <= 2; l++) {
                    put(w, cx + dx * s + lx * l, by, cz + dz * s + lz * l, Material.STONE_BRICKS);
                }
            }
            int sx = dx * (R + 1), sz = dz * (R + 1);
            for (int l = -2; l <= 2; l++) {
                int gx = cx + sx + lx * l, gz = cz + sz + lz * l;
                if (Math.abs(l) <= 1) {
                    for (int y = 1; y <= 4; y++) put(w, gx, by + y, gz, Material.AIR);
                    put(w, gx, by + 5, gz, Material.STONE_BRICKS);
                } else {
                    for (int y = 1; y <= 6; y++) put(w, gx, by + y, gz, Material.POLISHED_DEEPSLATE);
                }
            }
            put(w, cx + sx, by + 4, cz + sz, Material.LANTERN);
        }

        // Cover: twelve quartz pillars with lanterns on the barrier line.
        for (int k = 0; k < 12; k++) {
            double ang = Math.toRadians(15 + 30 * k);
            int px = (int) Math.round(BARRIER_RADIUS * Math.cos(ang));
            int pz = (int) Math.round(BARRIER_RADIUS * Math.sin(ang));
            for (int y = 1; y <= 6; y++) put(w, cx + px, by + y, cz + pz, Material.QUARTZ_PILLAR);
            put(w, cx + px, by + 7, cz + pz, Material.LANTERN);
        }

        // Tier one: polished platform with stairs all round.
        for (int x = -13; x <= 13; x++) {
            for (int z = -13; z <= 13; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= 12) put(w, cx + x, by + 1, cz + z, Material.POLISHED_ANDESITE);
                else if (d <= 13) putStair(w, cx + x, by + 1, cz + z, Material.STONE_BRICK_STAIRS, inward(x, z));
            }
        }
        // Tier two: quartz platform with stairs, a light on top.
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= 7) put(w, cx + x, by + 2, cz + z, Material.QUARTZ_BLOCK);
                else if (d <= 8) putStair(w, cx + x, by + 2, cz + z, Material.QUARTZ_STAIRS, inward(x, z));
            }
        }
        put(w, cx, by + 3, cz, Material.SEA_LANTERN);

        // Four tall corner towers with balconies, crenellated tops, lanterns and coloured banners.
        int tower = (int) Math.round(R * 0.72);
        Material[] banners = {Material.RED_WOOL, Material.BLUE_WOOL, Material.YELLOW_WOOL, Material.GREEN_WOOL};
        int bi = 0;
        for (int sx : new int[]{-1, 1}) {
            for (int sz : new int[]{-1, 1}) {
                int tx = cx + sx * tower;
                int tz = cz + sz * tower;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int y = 1; y <= 26; y++) put(w, tx + dx, by + y, tz + dz, Material.DEEPSLATE_TILES);
                        put(w, tx + dx, by + 14, tz + dz, Material.POLISHED_DEEPSLATE);
                        put(w, tx + dx, by + 27, tz + dz, Material.POLISHED_DEEPSLATE);
                    }
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        put(w, tx + dx, by + 28, tz + dz, Material.COBBLESTONE_WALL);
                    }
                }
                put(w, tx, by + 28, tz, Material.LANTERN);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (Math.abs(dx) == 2 || Math.abs(dz) == 2) put(w, tx + dx, by + 15, tz + dz, Material.STONE_BRICK_SLAB);
                    }
                }
                for (int y = 29; y <= 31; y++) put(w, tx, by + y, tz, Material.OAK_FENCE);
                put(w, tx, by + 32, tz, banners[bi++ % banners.length]);
            }
        }
    }

    /** Builds one arena centred on (cx, by, cz) and sets the two spawns, as the StickDuels arenas do. */
    private void buildGladiatorArena(Player admin, boolean sky) {
        World w = admin.getWorld();
        int cx = admin.getLocation().getBlockX();
        int cz = admin.getLocation().getBlockZ();
        int by = sky ? 200 : admin.getLocation().getBlockY() - 1;
        arenaQueue = new ArrayList<>();
        buildArena(w, cx, by, cz, sky);
        List<ArenaOp> ops = arenaQueue;
        arenaQueue = new ArrayList<>();
        Location a = new Location(w, cx - ARENA_SPAWN_OFFSET + 0.5, by + 1, cz + 0.5, 90f, 0f);
        Location b = new Location(w, cx + ARENA_SPAWN_OFFSET + 0.5, by + 1, cz + 0.5, -90f, 0f);
        runArenaBatch(ops, () -> {
            glaSpawnA = a;
            glaSpawnB = b;
            glaCentre = new Location(w, cx, by, cz);
            glaSky = sky;
            saveData();
            Bukkit.broadcastMessage(ChatColor.GOLD + "The gladiator arena is ready.");
        });
        admin.sendMessage(ChatColor.GREEN + "Building the gladiator arena" + (sky ? " high in the sky" : "")
                + ". This runs in batches and takes a few seconds.");
    }

    private void runArenaBatch(List<ArenaOp> ops, Runnable done) {
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                int end = Math.min(ops.size(), i + BUILD_PER_TICK);
                for (; i < end; i++) {
                    ArenaOp o = ops.get(i);
                    if (o.facing() == null) {
                        o.w().getBlockAt(o.x(), o.y(), o.z()).setType(o.m(), false);
                    } else {
                        BlockData bd = o.m().createBlockData();
                        if (bd instanceof Stairs st) st.setFacing(o.facing());
                        o.w().getBlockAt(o.x(), o.y(), o.z()).setBlockData(bd, false);
                    }
                }
                if (i >= ops.size()) {
                    cancel();
                    done.run();
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    /** The kit a gladiator match uses, from the StickDuels kit list. */
    private ItemStack[] gladKit(String kit) {
        ItemStack[] found = gladKits.get(kit);
        return found != null ? found : gladKits.values().iterator().next();
    }

    // ---------- Gladiators ----------

    private void gladCommand(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) { sender.sendMessage(ChatColor.RED + "Operators only."); return; }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "setspawn" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
                if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Usage: /msmp gladiators setspawn <a|b>"); return; }
                Location here = p.getLocation().clone();
                if (args[2].equalsIgnoreCase("a")) glaSpawnA = here;
                else if (args[2].equalsIgnoreCase("b")) glaSpawnB = here;
                else { sender.sendMessage(ChatColor.RED + "Use a or b."); return; }
                saveData();
                sender.sendMessage(ChatColor.GREEN + "Spawn " + args[2].toUpperCase(Locale.ROOT) + " set here.");
            }
            case "teams" -> {
                if (args.length < 4) { sender.sendMessage(ChatColor.RED + "Usage: /msmp gladiators teams <teamA> <teamB>"); return; }
                glaTeamA = args[2];
                glaTeamB = args[3];
                saveData();
                sender.sendMessage(ChatColor.GREEN + "Gladiators: " + glaTeamA + " vs " + glaTeamB + ".");
            }
            case "addkit" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
                if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Usage: /msmp gladiators addkit <name> (saves your inventory)"); return; }
                gladKits.put(args[2], p.getInventory().getContents());
                saveData();
                sender.sendMessage(ChatColor.GREEN + "Gladiator kit " + args[2] + " saved from your inventory.");
            }
            case "removekit" -> {
                if (args.length < 3 || gladKits.remove(args[2]) == null) { sender.sendMessage(ChatColor.RED + "No gladiator kit with that name."); return; }
                saveData();
                sender.sendMessage(ChatColor.GREEN + "Gladiator kit removed.");
            }
            case "kits" -> sender.sendMessage(ChatColor.GOLD + "Gladiator kits: " + ChatColor.WHITE + String.join(", ", gladKits.keySet()));
            case "arena" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
                boolean sky = args.length >= 3 && args[2].equalsIgnoreCase("sky");
                buildGladiatorArena(p, sky);
            }
            case "spin" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
                if (!eventOn("gladiators")) { p.sendMessage(ChatColor.RED + "Turn the gladiators event on first."); return; }
                if (glaActive || glaSpinning) { p.sendMessage(ChatColor.RED + "A gladiator match is already running."); return; }
                if (glaBuilding) { p.sendMessage(ChatColor.RED + "The arena is still being rebuilt."); return; }
                if (gladKits.isEmpty()) { p.sendMessage(ChatColor.RED + "Add a kit first with /msmp gladiators addkit <name>."); return; }
                if (glaSpawnA == null || glaSpawnB == null || glaTeamA == null || glaTeamB == null) {
                    p.sendMessage(ChatColor.RED + "Set spawns A and B and the two teams first.");
                    return;
                }
                openWheel(p);
            }
            case "stop" -> {
                if (!glaActive && !glaSpinning) { sender.sendMessage(ChatColor.YELLOW + "No match is running."); return; }
                endMatch(null);
                sender.sendMessage(ChatColor.GREEN + "Gladiator match stopped.");
            }
            default -> sender.sendMessage(ChatColor.GOLD + "Gladiators: spawns "
                    + (glaSpawnA != null && glaSpawnB != null ? "set" : "not set")
                    + ", teams " + glaTeamA + " vs " + glaTeamB
                    + ", match " + (glaActive ? (glaFighting ? "fighting" : "starting") : "not running") + ".");
        }
    }

    private static Material gladIcon(String kit) {
        return switch (kit) {
            case "Axe" -> Material.NETHERITE_AXE;
            case "Mace" -> Material.MACE;
            case "Archer" -> Material.BOW;
            case "Sumo" -> Material.STICK;
            case "Spear" -> Material.NETHERITE_SWORD;
            default -> Material.NETHERITE_SWORD;
        };
    }

    private void openWheel(Player admin) {
        glaSpinning = true;
        Inventory inv = Bukkit.createInventory(new WheelHolder(), 27, Component.text("Gladiator kit wheel"));
        admin.openInventory(inv);
        new BukkitRunnable() {
            int step = 0;
            final int total = 24;

            @Override
            public void run() {
                if (!glaSpinning) {
                    cancel();
                    return;
                }
                List<String> names = new ArrayList<>(gladKits.keySet());
                String kit = names.get((int) (Math.random() * names.size()));
                ItemStack icon = new ItemStack(gladIcon(kit));
                ItemMeta im = icon.getItemMeta();
                im.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + kit);
                icon.setItemMeta(im);
                inv.setItem(13, icon);
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof WheelHolder) {
                        viewer.getOpenInventory().getTopInventory().setItem(13, icon);
                    }
                }
                step++;
                if (step >= total) {
                    cancel();
                    glaKit = kit;
                    Bukkit.broadcastMessage(ChatColor.GOLD + "The kit is " + ChatColor.WHITE + kit + ChatColor.GOLD + "! Gladiators, get ready.");
                    Bukkit.getScheduler().runTaskLater(StickmanMoneySMP.this, () -> startMatch(), 60L);
                }
            }
        }.runTaskTimer(this, 0L, 4L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onWheelClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof WheelHolder) e.setCancelled(true);
    }

    private void startMatch() {
        glaSpinning = false;
        glaSideA.clear();
        glaSideB.clear();
        glaOut.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            String t = teams.get(p.getUniqueId());
            if (t == null) continue;
            if (t.equals(glaTeamA) && glaSideA.size() < 3) glaSideA.add(p.getUniqueId());
            else if (t.equals(glaTeamB) && glaSideB.size() < 3) glaSideB.add(p.getUniqueId());
        }
        if (glaSideA.isEmpty() || glaSideB.isEmpty()) {
            Bukkit.broadcastMessage(ChatColor.RED + "The gladiator match can't start: both teams need online players.");
            glaKit = null;
            return;
        }
        glaActive = true;
        glaFighting = false;
        ItemStack[] kit = gladKit(glaKit);
        for (UUID id : new HashSet<>(glaSideA)) prepareGladiator(id, kit, glaSpawnA);
        for (UUID id : new HashSet<>(glaSideB)) prepareGladiator(id, kit, glaSpawnB);
        Bukkit.broadcastMessage(ChatColor.GOLD + "Gladiators: " + glaTeamA + " vs " + glaTeamB + " with " + glaKit + ". Fight starts in 5 seconds.");
        new BukkitRunnable() {
            int n = 5;

            @Override
            public void run() {
                if (!glaActive) {
                    cancel();
                    return;
                }
                if (n == 0) {
                    glaFighting = true;
                    Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "FIGHT!");
                    cancel();
                    return;
                }
                n--;
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    private void prepareGladiator(UUID id, ItemStack[] kit, Location spawn) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) {
            glaOut.add(id);
            return;
        }
        glaSaved.put(id, new GlaSnap(p.getInventory().getContents(), p.getLocation().clone()));
        p.getInventory().clear();
        p.getInventory().setContents(kit.clone());
        for (PotionEffect eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.teleport(spawn);
    }

    private void glaRestore(Player p, GlaSnap s) {
        p.getInventory().clear();
        p.getInventory().setContents(s.contents().clone());
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        if (s.origin() != null) {
            lockBypassGlad.add(p.getUniqueId());
            p.teleport(s.origin());
            lockBypassGlad.remove(p.getUniqueId());
        }
    }

    private final Set<UUID> lockBypassGlad = new HashSet<>();

    /** Which side a gladiator is on: 'A', 'B', or ' ' if not in the match. */
    private char gladSide(UUID id) {
        if (!glaActive) return ' ';
        if (glaSideA.contains(id)) return 'A';
        if (glaSideB.contains(id)) return 'B';
        return ' ';
    }

    private boolean sideAlive(Set<UUID> side) {
        for (UUID id : side) if (!glaOut.contains(id)) return true;
        return false;
    }

    private void endMatch(String winnerSide) {
        if (!glaActive) return;
        glaActive = false;
        glaFighting = false;
        glaSpinning = false;
        String winnerTeam = null;
        if ("A".equals(winnerSide)) winnerTeam = glaTeamA;
        if ("B".equals(winnerSide)) winnerTeam = glaTeamB;
        for (UUID id : new HashSet<>(glaSideA)) restoreOrQueue(id);
        for (UUID id : new HashSet<>(glaSideB)) restoreOrQueue(id);
        if (winnerTeam != null) {
            long reward = getConfig().getLong("gladiators.reward", 50);
            scores.merge(winnerTeam, getConfig().getInt("gladiators.points-win", 1), Integer::sum);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!winnerTeam.equals(teams.get(p.getUniqueId()))) continue;
                setBalance(p.getUniqueId(), balance(p.getUniqueId()) + reward);
                refresh(p);
            }
            Bukkit.broadcastMessage(ChatColor.GREEN + "" + ChatColor.BOLD + winnerTeam + " won the gladiator match!");
        } else {
            Bukkit.broadcastMessage(ChatColor.YELLOW + "The gladiator match was stopped.");
        }
        glaSideA.clear();
        glaSideB.clear();
        glaOut.clear();
        glaKit = null;
        saveData();
        resetGladArena();
    }

    private void restoreOrQueue(UUID id) {
        GlaSnap s = glaSaved.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (s == null) return;
        if (p == null) {
            glaPending.put(id, s);
            return;
        }
        if (p.getHealth() <= 0) {
            glaRespawn.put(id, s);
            return;
        }
        glaRestore(p, s);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGladDamage(EntityDamageByEntityEvent e) {
        if (!glaActive || !(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player dp) attacker = dp;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player sp) attacker = sp;
        char vs = gladSide(victim.getUniqueId());
        char as = attacker == null ? ' ' : gladSide(attacker.getUniqueId());
        if (vs == ' ' && as == ' ') return;
        if (!glaFighting || vs == ' ' || as == ' ' || vs == as) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onGladDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        char side = gladSide(p.getUniqueId());
        if (side == ' ') return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        GlaSnap s = glaSaved.remove(p.getUniqueId());
        if (s != null) glaRespawn.put(p.getUniqueId(), s);
        glaOut.add(p.getUniqueId());
        Bukkit.broadcastMessage(ChatColor.GRAY + p.getName() + " is out of the gladiator match.");
        if (!sideAlive(glaSideA)) endMatch("B");
        else if (!sideAlive(glaSideB)) endMatch("A");
    }

    @EventHandler
    public void onGladRespawn(PlayerRespawnEvent e) {
        GlaSnap s = glaRespawn.remove(e.getPlayer().getUniqueId());
        if (s == null) return;
        if (s.origin() != null) e.setRespawnLocation(s.origin());
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) glaRestore(p, s);
        }, 1L);
    }

    private void gladQuit(Player p) {
        char side = gladSide(p.getUniqueId());
        if (side == ' ') return;
        GlaSnap s = glaSaved.remove(p.getUniqueId());
        if (s != null) glaPending.put(p.getUniqueId(), s);
        glaOut.add(p.getUniqueId());
        if (!sideAlive(glaSideA)) endMatch("B");
        else if (!sideAlive(glaSideB)) endMatch("A");
    }

    /** Rebuilds the gladiator arena from scratch, which clears cobwebs, dropped items and any damage. */
    private void resetGladArena() {
        if (glaCentre == null || glaCentre.getWorld() == null) return;
        World w = glaCentre.getWorld();
        glaBuilding = true;
        glaDirty = true;
        saveData();
        for (Entity en : w.getNearbyEntities(glaCentre, ARENA_R + 10, 90, ARENA_R + 10)) {
            if (en instanceof Item || en instanceof Projectile) en.remove();
        }
        arenaQueue = new ArrayList<>();
        buildArena(w, glaCentre.getBlockX(), glaCentre.getBlockY(), glaCentre.getBlockZ(), glaSky);
        List<ArenaOp> ops = arenaQueue;
        arenaQueue = new ArrayList<>();
        runArenaBatch(ops, () -> {
            glaBuilding = false;
            glaDirty = false;
            saveData();
            Bukkit.broadcastMessage(ChatColor.GOLD + "The gladiator arena has been reset.");
        });
    }

    /** True inside the gladiator arena area. */
    private boolean inGladArena(Location l) {
        if (glaCentre == null || l == null || l.getWorld() == null || !l.getWorld().equals(glaCentre.getWorld())) return false;
        if (Math.abs(l.getY() - glaCentre.getY()) > 60) return false;
        return Math.hypot(l.getX() - glaCentre.getX(), l.getZ() - glaCentre.getZ()) <= ARENA_R + 6;
    }

    /** Blocks that players can't break: mountain, flag tower or gladiator arena. */
    private boolean breakProtected(Location l) {
        return protectedBlock(l) || inGladArena(l);
    }

    private boolean inGladMatch(UUID id) {
        return glaActive && (glaSideA.contains(id) || glaSideB.contains(id));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onGladInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (!inGladMatch(e.getPlayer().getUniqueId())) return;
        if (e.getClickedBlock().getType() == Material.ENDER_CHEST) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "Ender chests are disabled during a gladiator match.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onGladInventoryOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !inGladMatch(p.getUniqueId())) return;
        if (e.getInventory().getType() == InventoryType.ENDER_CHEST) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Ender chests are disabled during a gladiator match.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onGladDrop(PlayerDropItemEvent e) {
        if (!inGladMatch(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(ChatColor.RED + "You cannot drop items during a gladiator match.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onGladCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (p.isOp() || !inGladMatch(p.getUniqueId())) return;
        e.setCancelled(true);
        p.sendMessage(ChatColor.RED + "Commands are disabled during a gladiator match.");
    }

    // ---------- Money trees ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onLeafBreak(BlockBreakEvent e) {
        if (!eventOn("money-trees") || !Tag.LEAVES.isTagged(e.getBlock().getType())) return;
        Player p = e.getPlayer();
        long money = getConfig().getLong("money-trees.leaf-money", 2);
        if (money > 0) {
            setBalance(p.getUniqueId(), balance(p.getUniqueId()) + money);
            refresh(p);
        }
        String held = p.getInventory().getItemInMainHand().getType().name();
        if (held.endsWith("_HOE") && rnd.nextDouble() < getConfig().getDouble("money-trees.apple-chance", 0.25)) {
            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), new ItemStack(Material.GOLDEN_APPLE));
        }
    }

    // ---------- Mountain protection ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMountainBreak(BlockBreakEvent e) {
        if (breakProtected(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProtectedPlace(BlockPlaceEvent e) {
        if (protectedBlock(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMountainBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> breakProtected(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMountainEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> breakProtected(b.getLocation()));
    }

    // ---------- Event rewards menu ----------

    /** Marks the rewards inventory so clicks can be handled. */
    private static final class RewardHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private ItemStack rewardItem(String label, String path, long def) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + label + ": $" + getConfig().getLong(path, def));
        m.setLore(List.of(ChatColor.GRAY + "Left click: +10   Right click: -10",
                ChatColor.GRAY + "Shift + left: +50   Shift + right: -50"));
        it.setItemMeta(m);
        return it;
    }

    private void openRewards(Player p) {
        Inventory inv = Bukkit.createInventory(new RewardHolder(), 27, Component.text("MSMP Event Rewards"));
        inv.setItem(10, rewardItem("Capture point reward", "capture-points.reward", 50));
        for (int i = 0; i < TIERS.length; i++) {
            long def = i < 2 ? 40 : (i < 4 ? 30 : 20);
            inv.setItem(12 + i, rewardItem(TIERS[i] + " tier kill steal", "tier-values." + TIERS[i], def));
        }
        inv.setItem(19, rewardItem("Unranked kill steal", "unranked-value", 0));
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta bm = back.getItemMeta();
        bm.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Back");
        back.setItemMeta(bm);
        inv.setItem(22, back);
        p.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRewardClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof RewardHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !isAdmin(p)) return;
        int slot = e.getRawSlot();
        if (slot == 22) {
            openMenu(p);
            return;
        }
        String path;
        long def;
        if (slot == 10) {
            path = "capture-points.reward";
            def = 50;
        } else if (slot >= 12 && slot <= 17) {
            path = "tier-values." + TIERS[slot - 12];
            def = slot - 12 < 2 ? 40 : (slot - 12 < 4 ? 30 : 20);
        } else if (slot == 19) {
            path = "unranked-value";
            def = 0;
        } else {
            return;
        }
        if (!e.isLeftClick() && !e.isRightClick()) return;
        long step = e.isShiftClick() ? 50 : 10;
        long current = getConfig().getLong(path, def);
        long next = Math.max(0, current + (e.isLeftClick() ? step : -step));
        getConfig().set(path, next);
        saveConfig();
        openRewards(p);
    }

    // ---------- Menu ----------

    private static String pretty(String key) {
        StringBuilder sb = new StringBuilder();
        for (String w : key.split("-")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private void openMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new MenuHolder(), 27, Component.text("Stickman MSMP"));
        ItemStack bal = new ItemStack(Material.PAPER);
        ItemMeta bm = bal.getItemMeta();
        bm.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Your money: $" + balance(p.getUniqueId()));
        bal.setItemMeta(bm);
        inv.setItem(4, bal);
        for (int i = 0; i < EVENT_KEYS.length && i < 8; i++) {
            String key = EVENT_KEYS[i];
            boolean built = BUILT_EVENTS.contains(key);
            ItemStack item = new ItemStack(!built ? Material.BARRIER : eventOn(key) ? Material.LIME_DYE : Material.GRAY_DYE);
            ItemMeta im = item.getItemMeta();
            im.setDisplayName((built && eventOn(key) ? ChatColor.GREEN : ChatColor.GRAY) + "" + ChatColor.BOLD + pretty(key)
                    + (!built ? " (not built yet)" : eventOn(key) ? " - ON" : " - OFF"));
            im.setLore(List.of(ChatColor.GRAY + (built ? "Operators: click to toggle" : "Saved, but nothing happens yet")));
            item.setItemMeta(im);
            inv.setItem(10 + i, item);
        }
        ItemStack rewards = new ItemStack(Material.PAPER);
        ItemMeta rm = rewards.getItemMeta();
        rm.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Event rewards");
        rm.setLore(List.of(ChatColor.GRAY + "Click to set how much money events give."));
        rewards.setItemMeta(rm);
        inv.setItem(22, rewards);
        p.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot == 22) {
            openRewards(p);
            return;
        }
        if (!isAdmin(p) || slot < 10 || slot > 17) return;
        String key = EVENT_KEYS[slot - 10];
        if (!BUILT_EVENTS.contains(key)) {
            p.sendMessage(ChatColor.YELLOW + pretty(key) + " isn't built yet.");
            return;
        }
        setEventState(key, !eventOn(key));
        p.sendMessage(ChatColor.GOLD + pretty(key) + " is now " + (eventOn(key) ? "ON" : "OFF") + ".");
        openMenu(p);
    }

    // ---------- Commands ----------

    private static boolean isAdmin(CommandSender s) {
        return s.isOp() || s.hasPermission("stickmoney.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "menu" -> {
                if (!isAdmin(sender)) { sender.sendMessage(ChatColor.RED + "Operators only."); return true; }
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                openMenu(p);
            }
            case "balance" -> {
                if (args.length >= 2) {
                    OfflinePlayer t = findPlayer(args[1]);
                    if (t == null) { sender.sendMessage(ChatColor.RED + "Unknown player."); return true; }
                    sender.sendMessage(ChatColor.GREEN + args[1] + "'s balance: $" + balance(t.getUniqueId()));
                } else if (sender instanceof Player p) {
                    sender.sendMessage(ChatColor.GREEN + "Your balance: $" + balance(p.getUniqueId()));
                } else {
                    sender.sendMessage("Usage: /msmp balance <player>");
                }
            }
            case "pay" -> pay(sender, args);
            case "give", "take" -> adjust(sender, args, args[0].equalsIgnoreCase("give"));
            case "tier" -> setTier(sender, args, true);
            case "removetier" -> setTier(sender, args, false);
            case "event" -> event(sender, args);
            case "color" -> tierColor(sender, args);
            case "colors" -> listColors(sender);
            case "points" -> pointsCommand(sender, args);
            case "team" -> teamCommand(sender, args);
            case "score" -> scoreCommand(sender, args);
            case "ctf" -> ctfCommand(sender, args);
            case "finale" -> finaleCommand(sender, args);
            case "gladiators" -> gladCommand(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "--- Stickman MSMP ---");
        s.sendMessage(ChatColor.WHITE + "/msmp menu" + ChatColor.GRAY + " - events menu");
        s.sendMessage(ChatColor.WHITE + "/msmp balance [player]" + ChatColor.GRAY + " - show money");
        s.sendMessage(ChatColor.WHITE + "/msmp pay <player> <amount>" + ChatColor.GRAY + " - send money to a player");
        if (isAdmin(s)) {
            s.sendMessage(ChatColor.WHITE + "/msmp give <player> <amount>" + ChatColor.GRAY + " - add money");
            s.sendMessage(ChatColor.WHITE + "/msmp take <player> <amount>" + ChatColor.GRAY + " - remove money");
            s.sendMessage(ChatColor.WHITE + "/msmp tier <player> <tier>" + ChatColor.GRAY + " - give a tier (S, A, B, C, D, F)");
            s.sendMessage(ChatColor.WHITE + "/msmp removetier <player> <tier>" + ChatColor.GRAY + " - remove a tier");
            s.sendMessage(ChatColor.WHITE + "/msmp event <name> <on|off>" + ChatColor.GRAY + " - turn an event on or off");
            s.sendMessage(ChatColor.WHITE + "/msmp color <tier> <#RRGGBB>" + ChatColor.GRAY + " - change a tier colour");
            s.sendMessage(ChatColor.WHITE + "/msmp colors" + ChatColor.GRAY + " - list tier colours");
            s.sendMessage(ChatColor.WHITE + "/msmp team <player> <name|none>" + ChatColor.GRAY + " - set a player's team");
            s.sendMessage(ChatColor.WHITE + "/msmp score <show|add|reset>" + ChatColor.GRAY + " - team points");
            s.sendMessage(ChatColor.WHITE + "/msmp ctf <setup|reset>" + ChatColor.GRAY + " - capture the flag tower");
            s.sendMessage(ChatColor.WHITE + "/msmp finale <area [finalSize]|duration <minutes>|status>" + ChatColor.GRAY + " - finale border settings");
            s.sendMessage(ChatColor.WHITE + "/msmp gladiators <arena [sky]|setspawn a|b|teams A B|addkit <name>|removekit <name>|kits|spin|stop|status>" + ChatColor.GRAY + " - team kit fights");
            s.sendMessage(ChatColor.WHITE + "/msmp points <build|status|reset|time|rotate>" + ChatColor.GRAY + " - capture point mountain");
        }
    }

    private OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return off.hasPlayedBefore() ? off : null;
    }

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage(ChatColor.RED + "Players only."); return; }
        if (args.length < 3) { p.sendMessage(ChatColor.RED + "Usage: /msmp pay <player> <amount>"); return; }
        OfflinePlayer target = findPlayer(args[1]);
        if (target == null) { p.sendMessage(ChatColor.RED + "Unknown player."); return; }
        if (target.getUniqueId().equals(p.getUniqueId())) { p.sendMessage(ChatColor.RED + "You can't pay yourself."); return; }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException ex) {
            p.sendMessage(ChatColor.RED + "Amount must be a whole number.");
            return;
        }
        if (amount <= 0) { p.sendMessage(ChatColor.RED + "Amount must be more than 0."); return; }
        if (balance(p.getUniqueId()) < amount) { p.sendMessage(ChatColor.RED + "You don't have $" + amount + "."); return; }
        setBalance(p.getUniqueId(), balance(p.getUniqueId()) - amount);
        setBalance(target.getUniqueId(), balance(target.getUniqueId()) + amount);
        refresh(p);
        p.sendMessage(ChatColor.GREEN + "Paid $" + amount + " to " + args[1] + ".");
        Player online = target.getPlayer();
        if (online != null) {
            refresh(online);
            online.sendMessage(ChatColor.GREEN + "You received $" + amount + " from " + p.getName() + ".");
        }
        saveData();
    }

    private void adjust(CommandSender sender, String[] args, boolean give) {
        if (!isAdmin(sender)) { sender.sendMessage(ChatColor.RED + "Operators only."); return; }
        if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Usage: /msmp " + args[0] + " <player> <amount>"); return; }
        OfflinePlayer t = findPlayer(args[1]);
        if (t == null) { sender.sendMessage(ChatColor.RED + "Unknown player."); return; }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(ChatColor.RED + "Amount must be a whole number.");
            return;
        }
        if (amount < 0) { sender.sendMessage(ChatColor.RED + "Use a positive amount."); return; }
        long next = give ? balance(t.getUniqueId()) + amount : balance(t.getUniqueId()) - amount;
        setBalance(t.getUniqueId(), next);
        if (t.getPlayer() != null) refresh(t.getPlayer());
        saveData();
        sender.sendMessage(ChatColor.GREEN + (give ? "Gave $" : "Took $") + amount + (give ? " to " : " from ")
                + args[1] + ". New balance: $" + next + ".");
    }

    private void setTier(CommandSender sender, String[] args, boolean add) {
        if (!isAdmin(sender)) { sender.sendMessage(ChatColor.RED + "Operators only."); return; }
        if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Usage: /msmp " + args[0] + " <player> <tier>"); return; }
        OfflinePlayer t = findPlayer(args[1]);
        if (t == null) { sender.sendMessage(ChatColor.RED + "Unknown player."); return; }
        String tier = args[2].toUpperCase(Locale.ROOT);
        if (tierIndex(tier) < 0) { sender.sendMessage(ChatColor.RED + "Tier must be S, A, B, C, D or F."); return; }
        if (add) {
            tiers.put(t.getUniqueId(), tier);
            sender.sendMessage(ChatColor.GREEN + args[1] + " is now " + tier + " tier.");
        } else {
            if (!tier.equals(tiers.get(t.getUniqueId()))) {
                sender.sendMessage(ChatColor.RED + args[1] + " doesn't have " + tier + " tier.");
                return;
            }
            tiers.remove(t.getUniqueId());
            sender.sendMessage(ChatColor.GREEN + args[1] + " no longer has " + tier + " tier.");
        }
        if (t.getPlayer() != null) refresh(t.getPlayer());
        saveData();
    }

    // ---------- Capture points ----------

    private static long packKey(int x, int y, int z) {
        return ((long) (x + 1_000_000) << 42) | ((long) (y + 1000) << 21) | (long) (z + 1_000_000);
    }

    /** Places blocks in batches. If snap is given, the original block at each position is recorded first. */
    private void runBatch(List<BuildOp> ops, List<String> snap, Runnable done) {
        Set<Long> seen = new HashSet<>();
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                int end = Math.min(ops.size(), i + BUILD_PER_TICK);
                for (; i < end; i++) {
                    BuildOp o = ops.get(i);
                    if (snap != null && seen.add(packKey(o.x(), o.y(), o.z()))) {
                        snap.add(o.x() + " " + o.y() + " " + o.z() + " "
                                + o.w().getBlockAt(o.x(), o.y(), o.z()).getBlockData().getAsString());
                    }
                    o.w().getBlockAt(o.x(), o.y(), o.z()).setType(o.m(), false);
                }
                if (i >= ops.size()) {
                    cancel();
                    done.run();
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    /** Block coordinates of capture point i (0 to 5). The standing spot is one block above. */
    private int[] pointPos(int i) {
        double ang = Math.toRadians(60 * i);
        return new int[]{capX + (int) Math.round(POINT_R * Math.cos(ang)), capY + MOUNTAIN_H - 1,
                capZ + (int) Math.round(POINT_R * Math.sin(ang))};
    }

    private boolean activePoint(int i) {
        return i % 3 == currentSet && !captured[i];
    }

    private File snapshotFile() {
        return new File(getDataFolder(), "mountain-snapshot.txt");
    }

    private void buildMountain(Player admin) {
        if (capBuilt) {
            admin.sendMessage(ChatColor.RED + "A mountain is already built. Turn the event off to restore the ground first.");
            return;
        }
        World w = admin.getWorld();
        int ox = admin.getLocation().getBlockX();
        int oz = admin.getLocation().getBlockZ();
        // Sit the mountain on the ground under the admin.
        int floorY = admin.getLocation().getBlockY() - 1;
        while (floorY > w.getMinHeight() && w.getBlockAt(ox, floorY, oz).isPassable()) floorY--;
        int oy = floorY + 1;
        int R = MOUNTAIN_R, H = MOUNTAIN_H, P = PLATEAU_R;
        List<BuildOp> ops = new ArrayList<>();
        for (int x = -R - 2; x <= R + 2; x++) {
            for (int z = -R - 2; z <= R + 2; z++) {
                if (x * x + z * z > (R + 2) * (R + 2)) continue;
                for (int y = 1; y <= H + 10; y++) ops.add(new BuildOp(w, ox + x, oy + y, oz + z, Material.AIR));
            }
        }
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > R) continue;
                int h = d <= P ? H : (int) Math.round(H * Math.pow((R - d) / (double) (R - P), 1.4));
                for (int y = 0; y < h; y++) {
                    Material m = y == h - 1 ? Material.GRASS_BLOCK : (y >= h - 4 ? Material.DIRT : Material.STONE);
                    ops.add(new BuildOp(w, ox + x, oy + y, oz + z, m));
                }
            }
        }
        List<String> snap = new ArrayList<>();
        runBatch(ops, snap, () -> {
            try {
                Files.write(snapshotFile().toPath(), snap);
            } catch (IOException ex) {
                getLogger().log(Level.SEVERE, "Could not save the mountain snapshot. The ground cannot be restored.", ex);
            }
            capWorld = w.getName();
            capX = ox;
            capY = oy;
            capZ = oz;
            capBuilt = true;
            Arrays.fill(captured, false);
            Arrays.fill(progress, 0);
            Arrays.fill(holder, null);
            currentSet = 0;
            setStart = System.currentTimeMillis();
            updateBlocks();
            saveData();
            Bukkit.broadcastMessage(ChatColor.GOLD + "The capture point mountain is ready.");
        });
        admin.sendMessage(ChatColor.GREEN + "Building the capture point mountain on the ground here. This runs in batches and takes a few seconds.");
    }

    /** Puts back the original ground from the snapshot, then clears the mountain state. */
    private void restoreMountain() {
        File f = snapshotFile();
        capBuilt = false;
        Arrays.fill(captured, false);
        Arrays.fill(progress, 0);
        Arrays.fill(holder, null);
        saveData();
        World w = capWorld == null ? null : Bukkit.getWorld(capWorld);
        if (w == null || !f.exists()) {
            f.delete();
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(f.toPath());
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not read the mountain snapshot.", ex);
            return;
        }
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                int end = Math.min(lines.size(), i + BUILD_PER_TICK);
                for (; i < end; i++) {
                    String[] parts = lines.get(i).split(" ", 4);
                    if (parts.length < 4) continue;
                    try {
                        w.getBlockAt(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]))
                                .setBlockData(Bukkit.createBlockData(parts[3]), false);
                    } catch (IllegalArgumentException ex) {
                        // Skip a line that doesn't parse.
                    }
                }
                if (i >= lines.size()) {
                    cancel();
                    f.delete();
                    Bukkit.broadcastMessage(ChatColor.GOLD + "The capture point mountain has been removed and the ground restored.");
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private boolean inMountain(Location l) {
        if (!capBuilt || capWorld == null || l == null || l.getWorld() == null || !l.getWorld().getName().equals(capWorld)) return false;
        int r = MOUNTAIN_R + 2;
        return Math.abs(l.getBlockX() - capX) <= r && Math.abs(l.getBlockZ() - capZ) <= r
                && l.getBlockY() >= capY - 2 && l.getBlockY() <= capY + MOUNTAIN_H + 12;
    }

    /** True for blocks in the capture the flag tower, including its ladder. */
    private boolean inCtfTower(Location l) {
        if (!ctfBuilt || ctfWorld == null || l == null || l.getWorld() == null || !l.getWorld().getName().equals(ctfWorld)) return false;
        return Math.abs(l.getBlockX() - ctfX) <= 2 && Math.abs(l.getBlockZ() - ctfZ) <= 2
                && l.getBlockY() >= ctfY - 2 && l.getBlockY() <= ctfY + 8;
    }

    /** True for anything that must not change: the capture point mountain or the flag tower. */
    private boolean protectedBlock(Location l) {
        return inMountain(l) || inCtfTower(l);
    }

    /** Finishes work a restart may have interrupted: a mountain restore and a gladiator arena rebuild. */
    private void resumeInterrupted() {
        if (!capBuilt && snapshotFile().exists()) {
            getLogger().info("Finishing an interrupted capture point mountain restore.");
            restoreMountain();
        }
        if (glaDirty && glaCentre != null && !glaActive) {
            getLogger().info("Rebuilding the gladiator arena after an interrupted reset.");
            resetGladArena();
        }
    }

    private void setEventState(String key, boolean on) {
        events.put(key, on);
        if (key.equals("ctf") && !on) returnFlag();
        if (key.equals("gladiators") && !on) endMatch(null);
        if (key.equals("finale")) {
            if (on) finaleStart();
            else finaleStop();
        }
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
        if (key.equals("capture-points") && !on && capBuilt) restoreMountain();
        saveData();
    }

    /** Sets each point's block: emerald when open, gold when captured, grey when waiting. */
    private void updateBlocks() {
        World w = capWorld == null ? null : Bukkit.getWorld(capWorld);
        if (w == null) return;
        for (int i = 0; i < 6; i++) {
            int[] pos = pointPos(i);
            Material m = captured[i] ? Material.LIME_CONCRETE : (activePoint(i) ? Material.YELLOW_CONCRETE : Material.GRAY_CONCRETE);
            w.getBlockAt(pos[0], pos[1], pos[2]).setType(m, false);
        }
    }

    private void rotate(boolean announce) {
        for (int i = 0; i < 6; i++) {
            if (!captured[i]) {
                progress[i] = 0;
                holder[i] = null;
            }
        }
        for (int k = 1; k <= 3; k++) {
            int next = (currentSet + k) % 3;
            if (!captured[next] || !captured[next + 3]) {
                currentSet = next;
                setStart = System.currentTimeMillis();
                updateBlocks();
                saveData();
                if (announce) Bukkit.broadcastMessage(ChatColor.GOLD + "New capture points are open!");
                return;
            }
        }
        events.put("capture-points", false);
        Bukkit.broadcastMessage(ChatColor.GOLD + "All capture points have been captured! The mountain is being removed.");
        restoreMountain();
    }

    private void capturePoint(int i, String team) {
        captured[i] = true;
        progress[i] = 0;
        holder[i] = team;
        long reward = getConfig().getLong("capture-points.reward", 50);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!team.equals(teams.get(p.getUniqueId()))) continue;
            setBalance(p.getUniqueId(), balance(p.getUniqueId()) + reward);
            refresh(p);
            p.sendMessage(ChatColor.GREEN + "Your team captured a point! +$" + reward + ".");
        }
        Bukkit.broadcastMessage(ChatColor.GOLD + "Team " + team + " captured capture point " + (i + 1) + "!");
        updateBlocks();
        saveData();
        if (captured[currentSet] && captured[currentSet + 3]) rotate(true);
    }

    private void captureTick() {
        if (!capBuilt || !eventOn("capture-points")) return;
        World w = Bukkit.getWorld(capWorld == null ? "" : capWorld);
        if (w == null) return;
        long now = System.currentTimeMillis();
        if (setStart == 0) {
            setStart = now;
            updateBlocks();
        }
        if (now - setStart > getConfig().getLong("capture-points.rotate-minutes", 40) * 60_000L) {
            rotate(true);
            return;
        }
        long hold = getConfig().getLong("capture-points.hold-minutes", 10) * 60L;
        for (int i = 0; i < 6; i++) {
            if (!activePoint(i)) continue;
            int[] pos = pointPos(i);
            double cx = pos[0] + 0.5, cz = pos[2] + 0.5, standY = pos[1] + 1;
            Set<String> present = new HashSet<>();
            for (Player p : w.getPlayers()) {
                Location l = p.getLocation();
                if (Math.hypot(l.getX() - cx, l.getZ() - cz) > CAP_RADIUS || Math.abs(l.getY() - standY) > 3) continue;
                String t = teams.get(p.getUniqueId());
                if (t != null) present.add(t);
            }
            // Empty or contested points make no progress.
            if (present.size() != 1) continue;
            String t = present.iterator().next();
            if (holder[i] == null) holder[i] = t;
            if (!t.equals(holder[i])) continue;
            progress[i]++;
            if (progress[i] >= hold) {
                capturePoint(i, t);
                return;
            }
        }
    }

    private void pointsCommand(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) { sender.sendMessage(ChatColor.RED + "Operators only."); return; }
        if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /msmp points <build|status|reset|time|rotate>"); return; }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "build" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
                buildMountain(p);
            }
            case "status" -> {
                if (!capBuilt) { sender.sendMessage(ChatColor.YELLOW + "No mountain built yet. Use /msmp points build."); return; }
                sender.sendMessage(ChatColor.GOLD + "Open set: " + (currentSet + 1) + " of 3. Captured: "
                        + (captured[0] ? "1" : "-") + (captured[1] ? "2" : "-") + (captured[2] ? "3" : "-")
                        + (captured[3] ? "4" : "-") + (captured[4] ? "5" : "-") + (captured[5] ? "6" : "-") + ".");
            }
            case "time" -> {
                if (args.length < 3 || !args[2].matches("\\d+")) { sender.sendMessage(ChatColor.RED + "Usage: /msmp points time <minutes>"); return; }
                long mins = Long.parseLong(args[2]);
                if (mins < 1 || mins > 180) { sender.sendMessage(ChatColor.RED + "Capture time must be 1 to 180 minutes."); return; }
                getConfig().set("capture-points.hold-minutes", mins);
                saveConfig();
                sender.sendMessage(ChatColor.GREEN + "Capture time set to " + mins + " minutes.");
            }
            case "rotate" -> {
                if (args.length < 3 || !args[2].matches("\\d+")) { sender.sendMessage(ChatColor.RED + "Usage: /msmp points rotate <minutes>"); return; }
                long mins = Long.parseLong(args[2]);
                if (mins < 1 || mins > 360) { sender.sendMessage(ChatColor.RED + "Rotation must be 1 to 360 minutes."); return; }
                getConfig().set("capture-points.rotate-minutes", mins);
                saveConfig();
                sender.sendMessage(ChatColor.GREEN + "Points rotate every " + mins + " minutes if not finished.");
            }
            case "reset" -> {
                if (!capBuilt) { sender.sendMessage(ChatColor.YELLOW + "No mountain built yet."); return; }
                Arrays.fill(captured, false);
                Arrays.fill(progress, 0);
                Arrays.fill(holder, null);
                currentSet = 0;
                setStart = System.currentTimeMillis();
                updateBlocks();
                saveData();
                sender.sendMessage(ChatColor.GREEN + "Capture points reset.");
            }
            default -> sender.sendMessage(ChatColor.RED + "Usage: /msmp points <build|status|reset>");
        }
    }

    private void teamCommand(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) { sender.sendMessage(ChatColor.RED + "Operators only."); return; }
        if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Usage: /msmp team <player> <name|none>"); return; }
        OfflinePlayer t = findPlayer(args[1]);
        if (t == null) { sender.sendMessage(ChatColor.RED + "Unknown player."); return; }
        if (args[2].equalsIgnoreCase("none")) {
            teams.remove(t.getUniqueId());
            sender.sendMessage(ChatColor.GREEN + args[1] + " is no longer on a team.");
        } else {
            teams.put(t.getUniqueId(), args[2]);
            sender.sendMessage(ChatColor.GREEN + args[1] + " is now on team " + args[2] + ".");
        }
        if (t.getPlayer() != null) refresh(t.getPlayer());
        saveData();
    }

    private void tierColor(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) { sender.sendMessage(ChatColor.RED + "Operators only."); return; }
        if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Usage: /msmp color <tier> <#RRGGBB>"); return; }
        String tier = args[1].toUpperCase(Locale.ROOT);
        if (tierIndex(tier) < 0) { sender.sendMessage(ChatColor.RED + "Tier must be S, A, B, C, D or F."); return; }
        String hex = args[2].startsWith("#") ? args[2] : "#" + args[2];
        if (!hex.matches("#[0-9A-Fa-f]{6}")) {
            sender.sendMessage(ChatColor.RED + "Colour must be 6 hex digits, for example #FF69B4.");
            return;
        }
        hex = hex.toUpperCase(Locale.ROOT);
        getConfig().set("tier-colors." + tier, hex);
        saveConfig();
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
        sender.sendMessage(ChatColor.of(hex) + "" + ChatColor.BOLD + tier + " TIER" + ChatColor.RESET
                + ChatColor.GREEN + " colour set to " + hex + ".");
    }

    private void listColors(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "--- Tier colours ---");
        for (String tier : TIERS) {
            String hex = getConfig().getString("tier-colors." + tier, "#FFFFFF");
            sender.sendMessage(ChatColor.of(hex) + "" + ChatColor.BOLD + tier + " TIER" + ChatColor.RESET
                    + ChatColor.GRAY + " " + hex);
        }
    }

    private void event(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) { sender.sendMessage(ChatColor.RED + "Operators only."); return; }
        if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Usage: /msmp event <name> <on|off>"); return; }
        String key = args[1].toLowerCase(Locale.ROOT);
        if (!events.containsKey(key)) {
            sender.sendMessage(ChatColor.RED + "Unknown event. Options: " + String.join(", ", EVENT_KEYS));
            return;
        }
        boolean on = args[2].equalsIgnoreCase("on");
        setEventState(key, on);
        String note = BUILT_EVENTS.contains(key) ? "" : " (saved, but this event isn't built yet)";
        sender.sendMessage(ChatColor.GREEN + pretty(key) + " is now " + (on ? "ON" : "OFF") + note + ".");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("balance", "pay", "help"));
            if (isAdmin(sender)) subs.addAll(List.of("menu", "give", "take", "tier", "removetier", "event", "color", "colors", "team", "points", "score", "ctf", "finale", "gladiators"));
            return filter(subs, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("balance") || sub.equals("pay") || (isAdmin(sender) && List.of("give", "take", "tier", "removetier").contains(sub)))) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            return filter(out, args[1]);
        }
        if (args.length == 2 && isAdmin(sender) && sub.equals("color")) return filter(Arrays.asList(TIERS), args[1]);
        if (args.length == 3 && isAdmin(sender) && (sub.equals("tier") || sub.equals("removetier"))) {
            return filter(Arrays.asList(TIERS), args[2]);
        }
        if (args.length == 2 && isAdmin(sender) && sub.equals("event")) return filter(Arrays.asList(EVENT_KEYS), args[1]);
        if (args.length == 3 && isAdmin(sender) && sub.equals("event")) return filter(List.of("on", "off"), args[2]);
        return out;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }
}
