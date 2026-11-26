package com.chaossmp.custommob;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class ChaosCustomMobPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private NamespacedKey npcIdKey;
    private File skinsFolder;
    private File npcsFolder;
    private BukkitTask randomSpawnTask;

    private final Map<String, NpcProfile> profiles = new LinkedHashMap<>();
    private final Map<String, Set<UUID>> activeEntities = new HashMap<>();
    private final Map<String, Long> nextAllowedRandomRoll = new HashMap<>();

    private enum Behavior {
        PEACEFUL,
        NEUTRAL,
        HOSTILE;

        static Behavior fromString(String value) {
            if (value == null) {
                return NEUTRAL;
            }
            return switch (value.trim().toUpperCase(Locale.ROOT)) {
                case "PEACEFUL" -> PEACEFUL;
                case "HOSTILE" -> HOSTILE;
                default -> NEUTRAL;
            };
        }
    }

    private enum SpawnMode {
        NEAR_MOB,
        NEAR_PLAYER,
        RANDOM_WORLD;

        static SpawnMode fromString(String value) {
            if (value == null) {
                return NEAR_MOB;
            }
            return switch (value.trim().toUpperCase(Locale.ROOT)) {
                case "NEAR_PLAYER", "PLAYER", "RANDOM_PLAYER" -> NEAR_PLAYER;
                case "RANDOM_WORLD", "WORLD", "RANDOM" -> RANDOM_WORLD;
                default -> NEAR_MOB;
            };
        }
    }

    private record AttackEffectSpec(PotionEffectType type, int duration, int amplifier) {}

    private record NpcProfile(
        String id,
        boolean enabled,
        String name,
        EntityType entityType,
        Behavior behavior,
        String worldName,
        int respawnSeconds,
        SpawnMode spawnMode,
        double spawnChance,
        int spawnIntervalSeconds,
        double spawnRadius,
        int maxActive,
        boolean fallbackToWorld,
        boolean debugLogs,
        double health,
        double damage,
        double speed,
        boolean glowing,
        String skinFile,
        ItemStack helmet,
        ItemStack chestplate,
        ItemStack leggings,
        ItemStack boots,
        ItemStack mainHand,
        List<String> onInteractMessages,
        List<AttackEffectSpec> onAttackEffects
    ) {}

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.npcIdKey = new NamespacedKey(this, "npc_profile_id");

        setupFoldersAndExample();
        loadNpcProfiles();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("chaosmob") != null) {
            getCommand("chaosmob").setExecutor(this);
            getCommand("chaosmob").setTabCompleter(this);
        }

        if (getConfig().getBoolean("settings.auto-spawn-on-start", true)) {
            Bukkit.getScheduler().runTask(this, this::spawnInitialGuaranteed);
        }

        startRandomSpawnLoop();
        getLogger().info("ChaosCustomMob успешно запущен! Загружено NPC: " + profiles.size());
    }

    @Override
    public void onDisable() {
        if (randomSpawnTask != null) {
            randomSpawnTask.cancel();
            randomSpawnTask = null;
        }
        despawnAllTracked();
    }

    private void startRandomSpawnLoop() {
        if (randomSpawnTask != null) {
            randomSpawnTask.cancel();
        }
        randomSpawnTask = Bukkit.getScheduler().runTaskTimer(this, this::tickRandomSpawns, 20L, 20L);
    }

    private void tickRandomSpawns() {
        long now = System.currentTimeMillis();
        for (NpcProfile profile : profiles.values()) {
            if (!profile.enabled()) {
                continue;
            }

            long nextTime = nextAllowedRandomRoll.getOrDefault(profile.id(), 0L);
            if (now < nextTime) {
                continue;
            }

            long intervalMs = Math.max(1, profile.spawnIntervalSeconds()) * 1000L;
            nextAllowedRandomRoll.put(profile.id(), now + intervalMs);

            cleanupDeadEntities(profile.id());
            int currentCount = activeEntities.getOrDefault(profile.id(), Set.of()).size();
            if (currentCount >= profile.maxActive()) {
                if (profile.debugLogs()) {
                    getLogger().info("[DEBUG " + profile.id() + "] Пропуск: достигнут лимит max-active (" + currentCount + "/" + profile.maxActive() + ")");
                }
                continue;
            }

            double roll = ThreadLocalRandom.current().nextDouble();
            if (roll > profile.spawnChance()) {
                if (profile.debugLogs()) {
                    getLogger().info("[DEBUG " + profile.id() + "] Шанс не сработал: roll=" + String.format(Locale.ROOT, "%.4f", roll) + " > chance=" + profile.spawnChance());
                }
                continue;
            }

            Location targetLoc = pickSpawnLocation(profile);
            if (targetLoc != null) {
                LivingEntity spawned = spawnNpcAt(profile, targetLoc);
                if (spawned != null && profile.debugLogs()) {
                    getLogger().info("[DEBUG " + profile.id() + "] Успешный рандомный спавн в мире '" + profile.worldName() + "' на "
                        + targetLoc.getBlockX() + ", " + targetLoc.getBlockY() + ", " + targetLoc.getBlockZ());
                }
            } else if (profile.debugLogs()) {
                getLogger().info("[DEBUG " + profile.id() + "] Шанс сработал, но не найдена точка спавна в мире '" + profile.worldName() + "'");
            }
        }
    }

    private Location pickSpawnLocation(NpcProfile profile) {
        World world = Bukkit.getWorld(profile.worldName());
        if (world == null) {
            getLogger().warning("Мир '" + profile.worldName() + "' для NPC '" + profile.id() + "' не загружен.");
            return null;
        }

        Location loc = switch (profile.spawnMode()) {
            case NEAR_MOB -> findNearRegularMob(world, profile.spawnRadius());
            case NEAR_PLAYER -> findNearPlayer(world, profile.spawnRadius());
            case RANDOM_WORLD -> findRandomLoadedGround(world);
        };

        if (loc == null && profile.fallbackToWorld()) {
            loc = findRandomLoadedGround(world);
        }
        return loc;
    }

    private Location findNearRegularMob(World world, double radius) {
        List<LivingEntity> candidates = new ArrayList<>();
        for (LivingEntity entity : world.getLivingEntities()) {
            if (entity instanceof Player || entity.isDead() || !entity.isValid()) {
                continue;
            }
            if (entity.getPersistentDataContainer().has(npcIdKey, PersistentDataType.STRING)) {
                continue;
            }
            candidates.add(entity);
        }

        if (candidates.isEmpty()) {
            return null;
        }

        LivingEntity anchor = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        Location anchorLoc = anchor.getLocation().clone();

        Location around = randomSafeGroundAround(world, anchorLoc.getX(), anchorLoc.getY(), anchorLoc.getZ(), radius);
        if (around != null) {
            return around;
        }
        return anchorLoc.add(1.0, 0.0, 0.0);
    }

    private Location findNearPlayer(World world, double radius) {
        List<Player> players = new ArrayList<>();
        for (Player p : world.getPlayers()) {
            if (p.isOnline() && !p.isDead()) {
                players.add(p);
            }
        }
        if (players.isEmpty()) {
            return null;
        }
        Player chosen = players.get(ThreadLocalRandom.current().nextInt(players.size()));
        Location pLoc = chosen.getLocation();
        Location around = randomSafeGroundAround(world, pLoc.getX(), pLoc.getY(), pLoc.getZ(), radius);
        return around != null ? around : pLoc.clone().add(2.0, 0.0, 0.0);
    }

    private Location findRandomLoadedGround(World world) {
        Chunk[] chunks = world.getLoadedChunks();
        if (chunks.length == 0) {
            return null;
        }

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 16; attempt++) {
            Chunk chunk = chunks[rng.nextInt(chunks.length)];
            int x = (chunk.getX() << 4) + rng.nextInt(0, 16);
            int z = (chunk.getZ() << 4) + rng.nextInt(0, 16);

            int y = world.getHighestBlockYAt(x, z);
            Block ground = world.getBlockAt(x, y, z);
            Block feet = world.getBlockAt(x, y + 1, z);
            Block head = world.getBlockAt(x, y + 2, z);

            if (ground.getType().isSolid() && !ground.isLiquid() && feet.isPassable() && !feet.isLiquid() && head.isPassable()) {
                return new Location(world, x + 0.5, y + 1.0, z + 0.5);
            }
        }
        return null;
    }

    private Location randomSafeGroundAround(World world, double centerX, double centerY, double centerZ, double radius) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int baseY = (int) Math.floor(centerY);

        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = rng.nextDouble(0.0, Math.PI * 2.0);
            double dist = Math.sqrt(rng.nextDouble()) * Math.max(1.0, radius);
            int x = (int) Math.floor(centerX + Math.cos(angle) * dist);
            int z = (int) Math.floor(centerZ + Math.sin(angle) * dist);

            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }

            for (int dy = 4; dy >= -4; dy--) {
                int y = baseY + dy;
                if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 2) {
                    continue;
                }
                Block ground = world.getBlockAt(x, y, z);
                Block feet = world.getBlockAt(x, y + 1, z);
                Block head = world.getBlockAt(x, y + 2, z);

                if (ground.getType().isSolid() && !ground.isLiquid() && feet.isPassable() && !feet.isLiquid() && head.isPassable()) {
                    return new Location(world, x + 0.5, y + 1.0, z + 0.5);
                }
            }
        }
        return null;
    }

    private void setupFoldersAndExample() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Не удалось создать папку плагина: " + getDataFolder().getAbsolutePath());
        }

        this.skinsFolder = new File(getDataFolder(), "skins");
        if (!skinsFolder.exists() && !skinsFolder.mkdirs()) {
            getLogger().warning("Не удалось создать папку skins.");
        }

        File skinReadme = new File(skinsFolder, "README.txt");
        if (!skinReadme.exists()) {
            String text = """
                Сюда закидывайте файлы изображений/скинов персонажей (например: guard.png).
                Имя файла указывается в параметре appearance.skin-file в файлах папки npcs/.
                """;
            try {
                Files.writeString(skinReadme.toPath(), text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                getLogger().warning("Не удалось создать skins/README.txt: " + e.getMessage());
            }
        }

        this.npcsFolder = new File(getDataFolder(), "npcs");
        if (!npcsFolder.exists() && !npcsFolder.mkdirs()) {
            getLogger().warning("Не удалось создать папку npcs.");
        }

        File exampleNpc = new File(npcsFolder, "guard.yml");
        if (!exampleNpc.exists()) {
            String example = """
                id: tavern_guard
                enabled: true
                name: "&6Страж Таверны"
                behavior: neutral # peaceful | neutral | hostile

                world: "world"
                respawn-seconds: 60

                spawn:
                  mode: "NEAR_MOB" # NEAR_MOB | NEAR_PLAYER | RANDOM_WORLD
                  chance: 0.25
                  interval-seconds: 30
                  radius: 16.0
                  max-active: 1
                  fallback-to-world: true
                  debug-logs: false

                stats:
                  health: 80.0
                  damage: 10.0
                  speed: 0.28

                appearance:
                  skin-file: "guard.png"
                  glowing: false
                  equipment:
                    main-hand: "IRON_SWORD"
                    chestplate: "IRON_CHESTPLATE"

                actions:
                  on-interact:
                    - "&eСтраж Таверны: &fПриветствую! Держи ухо востро."
                  on-attack-hit:
                    - effect: "SLOWNESS"
                      duration: 40
                      amplifier: 1
                """;
            try {
                Files.writeString(exampleNpc.toPath(), example, StandardCharsets.UTF_8);
            } catch (IOException e) {
                getLogger().warning("Не удалось создать пример npcs/guard.yml: " + e.getMessage());
            }
        }
    }

    private void loadNpcProfiles() {
        profiles.clear();
        nextAllowedRandomRoll.clear();

        File[] files = npcsFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return;
        }

        int defaultRespawn = getConfig().getInt("settings.default-respawn-seconds", 60);

        for (File file : files) {
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            String baseId = file.getName().substring(0, file.getName().length() - 4);
            String id = cfg.getString("id", baseId).trim();
            if (id.isEmpty()) {
                id = baseId;
            }

            boolean enabled = cfg.getBoolean("enabled", true);
            String name = color(cfg.getString("name", id));
            Behavior behavior = Behavior.fromString(cfg.getString("behavior", "neutral"));
            EntityType entityType = parseEntityType(cfg.getString("entity-type"), behavior);

            String worldName = cfg.contains("world")
                ? cfg.getString("world", "world")
                : cfg.getString("location.world", "world");
            int respawnSeconds = cfg.contains("respawn-seconds")
                ? cfg.getInt("respawn-seconds", defaultRespawn)
                : cfg.getInt("location.respawn-seconds", defaultRespawn);

            SpawnMode spawnMode = SpawnMode.fromString(cfg.getString("spawn.mode", "NEAR_MOB"));
            double spawnChance = clamp01(cfg.getDouble("spawn.chance", 0.1));
            int spawnIntervalSeconds = Math.max(1, cfg.getInt("spawn.interval-seconds", 30));
            double spawnRadius = Math.max(1.0, cfg.getDouble("spawn.radius", 16.0));
            int maxActive = Math.max(1, cfg.getInt("spawn.max-active", 1));
            boolean fallbackToWorld = cfg.getBoolean("spawn.fallback-to-world", true);
            boolean debugLogs = cfg.getBoolean("spawn.debug-logs", false);

            double health = cfg.getDouble("stats.health", 40.0);
            double damage = cfg.getDouble("stats.damage", 6.0);
            double speed = cfg.getDouble("stats.speed", 0.26);

            boolean glowing = cfg.getBoolean("appearance.glowing", false);
            String skinFile = cfg.getString("appearance.skin-file", "");

            ItemStack helmet = parseItem(cfg.getString("appearance.equipment.helmet"));
            ItemStack chestplate = parseItem(cfg.getString("appearance.equipment.chestplate"));
            ItemStack leggings = parseItem(cfg.getString("appearance.equipment.leggings"));
            ItemStack boots = parseItem(cfg.getString("appearance.equipment.boots"));
            ItemStack mainHand = parseItem(cfg.getString("appearance.equipment.main-hand"));

            List<String> interactMessages = new ArrayList<>();
            for (String line : cfg.getStringList("actions.on-interact")) {
                interactMessages.add(color(line));
            }

            List<AttackEffectSpec> attackEffects = new ArrayList<>();
            List<?> rawEffects = cfg.getList("actions.on-attack-hit");
            if (rawEffects != null) {
                for (Object rawEntry : rawEffects) {
                    if (!(rawEntry instanceof Map<?, ?> entry)) {
                        continue;
                    }
                    Object effObj = entry.get("effect");
                    if (effObj == null) {
                        continue;
                    }
                    PotionEffectType effType = parseEffectType(effObj.toString());
                    if (effType == null) {
                        continue;
                    }
                    int duration = entry.containsKey("duration") && entry.get("duration") != null
                        ? Integer.parseInt(entry.get("duration").toString())
                        : 40;
                    int amplifier = entry.containsKey("amplifier") && entry.get("amplifier") != null
                        ? Integer.parseInt(entry.get("amplifier").toString())
                        : 0;
                    attackEffects.add(new AttackEffectSpec(effType, duration, amplifier));
                }
            }

            if (skinFile != null && !skinFile.isBlank()) {
                File candidate = new File(skinsFolder, skinFile);
                if (!candidate.exists()) {
                    getLogger().warning("Для NPC '" + id + "' указан skin-file '" + skinFile + "', но файл не найден в папке skins/");
                }
            }

            profiles.put(id, new NpcProfile(
                id, enabled, name, entityType, behavior,
                worldName, respawnSeconds,
                spawnMode, spawnChance, spawnIntervalSeconds, spawnRadius, maxActive, fallbackToWorld, debugLogs,
                health, damage, speed, glowing, skinFile,
                helmet, chestplate, leggings, boots, mainHand,
                interactMessages, attackEffects
            ));
        }
    }

    private double clamp01(double val) {
        if (val < 0.0) return 0.0;
        if (val > 1.0) return 1.0;
        return val;
    }

    private EntityType parseEntityType(String configured, Behavior behavior) {
        if (configured != null && !configured.isBlank()) {
            try {
                EntityType type = EntityType.valueOf(configured.trim().toUpperCase(Locale.ROOT));
                if (type.isAlive()) {
                    return type;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return switch (behavior) {
            case PEACEFUL -> EntityType.VILLAGER;
            case NEUTRAL -> EntityType.HUSK;
            case HOSTILE -> EntityType.ZOMBIE;
        };
    }

    @SuppressWarnings("deprecation")
    private PotionEffectType parseEffectType(String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("SLOWNESS")) {
            normalized = "SLOW";
        }
        PotionEffectType byName = PotionEffectType.getByName(normalized);
        if (byName != null) {
            return byName;
        }
        NamespacedKey key = NamespacedKey.minecraft(raw.trim().toLowerCase(Locale.ROOT));
        return Registry.EFFECT.get(key);
    }

    private ItemStack parseItem(String materialName) {
        if (materialName == null || materialName.isBlank()) {
            return null;
        }
        try {
            return new ItemStack(Material.valueOf(materialName.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private String prefix() {
        return color(getConfig().getString("messages.prefix", "&8[&dChaosCustomMob&8] &r"));
    }

    public LivingEntity spawnNpc(String id) {
        NpcProfile profile = profiles.get(id);
        if (profile == null || !profile.enabled()) {
            return null;
        }

        Location loc = pickSpawnLocation(profile);
        if (loc == null) {
            World world = Bukkit.getWorld(profile.worldName());
            if (world == null) {
                getLogger().warning("Мир '" + profile.worldName() + "' для NPC '" + id + "' не загружен.");
            } else {
                getLogger().warning("Не удалось найти подходящую точку спавна в мире '" + profile.worldName() + "' для NPC '" + id + "'.");
            }
            return null;
        }

        return spawnNpcAt(profile, loc);
    }

    private LivingEntity spawnNpcAt(NpcProfile profile, Location loc) {
        if (loc.getWorld() == null) {
            return null;
        }

        cleanupDeadEntities(profile.id());
        Set<UUID> tracked = activeEntities.computeIfAbsent(profile.id(), k -> new HashSet<>());
        while (tracked.size() >= profile.maxActive()) {
            UUID oldest = tracked.iterator().next();
            tracked.remove(oldest);
            Entity old = Bukkit.getEntity(oldest);
            if (old != null && old.isValid()) {
                old.remove();
            }
        }

        if (!(loc.getWorld().spawnEntity(loc, profile.entityType()) instanceof LivingEntity entity)) {
            return null;
        }

        entity.setCustomName(profile.name());
        entity.setCustomNameVisible(true);
        entity.setGlowing(profile.glowing());
        entity.setRemoveWhenFarAway(false);
        entity.getPersistentDataContainer().set(npcIdKey, PersistentDataType.STRING, profile.id());

        setAttribute(entity, Attribute.MAX_HEALTH, profile.health());
        entity.setHealth(profile.health());
        setAttribute(entity, Attribute.ATTACK_DAMAGE, profile.damage());
        setAttribute(entity, Attribute.MOVEMENT_SPEED, profile.speed());

        if (entity instanceof Mob mob) {
            switch (profile.behavior()) {
                case PEACEFUL -> mob.setTarget(null);
                case NEUTRAL -> {
                    // Атакует только в ответ на удар
                }
                case HOSTILE -> {
                    Player nearest = findNearestPlayer(loc, 16.0);
                    if (nearest != null) {
                        mob.setTarget(nearest);
                    }
                }
            }
        }

        EntityEquipment eq = entity.getEquipment();
        if (eq != null) {
            eq.setHelmet(profile.helmet());
            eq.setChestplate(profile.chestplate());
            eq.setLeggings(profile.leggings());
            eq.setBoots(profile.boots());
            eq.setItemInMainHand(profile.mainHand());

            eq.setHelmetDropChance(0.0f);
            eq.setChestplateDropChance(0.0f);
            eq.setLeggingsDropChance(0.0f);
            eq.setBootsDropChance(0.0f);
            eq.setItemInMainHandDropChance(0.0f);
        }

        tracked.add(entity.getUniqueId());
        return entity;
    }

    private void cleanupDeadEntities(String id) {
        Set<UUID> tracked = activeEntities.get(id);
        if (tracked == null) {
            return;
        }
        tracked.removeIf(uuid -> {
            Entity e = Bukkit.getEntity(uuid);
            return e == null || !e.isValid() || e.isDead();
        });
    }

    private Player findNearestPlayer(Location origin, double radius) {
        if (origin.getWorld() == null) {
            return null;
        }
        double maxSq = radius * radius;
        Player best = null;
        double bestSq = maxSq;
        for (Player player : origin.getWorld().getPlayers()) {
            double distSq = player.getLocation().distanceSquared(origin);
            if (distSq <= bestSq) {
                bestSq = distSq;
                best = player;
            }
        }
        return best;
    }

    private void setAttribute(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance attr = entity.getAttribute(attribute);
        if (attr != null) {
            attr.setBaseValue(value);
        }
    }

    public void spawnInitialGuaranteed() {
        for (NpcProfile profile : profiles.values()) {
            if (profile.enabled() && profile.spawnChance() >= 1.0) {
                spawnNpc(profile.id());
            }
        }
    }

    public void despawnAllTracked() {
        for (Set<UUID> set : activeEntities.values()) {
            for (UUID uuid : set) {
                Entity entity = Bukkit.getEntity(uuid);
                if (entity != null && entity.isValid()) {
                    entity.remove();
                }
            }
        }
        activeEntities.clear();
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        String npcId = event.getRightClicked().getPersistentDataContainer().get(npcIdKey, PersistentDataType.STRING);
        if (npcId == null) {
            return;
        }
        NpcProfile profile = profiles.get(npcId);
        if (profile == null) {
            return;
        }

        if (profile.behavior() == Behavior.PEACEFUL) {
            event.setCancelled(true);
        }

        Player player = event.getPlayer();
        for (String line : profile.onInteractMessages()) {
            player.sendMessage(line.replace("%player%", player.getName()));
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof LivingEntity attacker && event.getEntity() instanceof Player target) {
            String npcId = attacker.getPersistentDataContainer().get(npcIdKey, PersistentDataType.STRING);
            if (npcId != null) {
                NpcProfile profile = profiles.get(npcId);
                if (profile != null) {
                    for (AttackEffectSpec spec : profile.onAttackEffects()) {
                        target.addPotionEffect(new PotionEffect(spec.type(), spec.duration(), spec.amplifier()));
                    }
                }
            }
        }

        if (event.getEntity() instanceof Mob victim && event.getDamager() instanceof Player attacker) {
            String npcId = victim.getPersistentDataContainer().get(npcIdKey, PersistentDataType.STRING);
            if (npcId != null) {
                NpcProfile profile = profiles.get(npcId);
                if (profile != null && profile.behavior() == Behavior.NEUTRAL) {
                    victim.setTarget(attacker);
                }
            }
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        String npcId = entity.getPersistentDataContainer().get(npcIdKey, PersistentDataType.STRING);
        if (npcId == null) {
            return;
        }

        Set<UUID> tracked = activeEntities.get(npcId);
        if (tracked != null) {
            tracked.remove(entity.getUniqueId());
        }

        NpcProfile profile = profiles.get(npcId);
        if (profile != null && profile.enabled() && profile.respawnSeconds() > 0 && profile.spawnChance() >= 1.0) {
            Bukkit.getScheduler().runTaskLater(this, () -> spawnNpc(npcId), profile.respawnSeconds() * 20L);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("chaoscustommob.admin")) {
            sender.sendMessage(prefix() + "§cНедостаточно прав.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(prefix() + "§eИспользование: /chaosmob <list|spawn|respawn|reload> [id]");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (profiles.isEmpty()) {
                    sender.sendMessage(prefix() + "§cФайлы NPC в папке npcs/ не найдены.");
                } else {
                    sender.sendMessage(prefix() + "§fNPC (" + profiles.size() + "): §e" + String.join(", ", profiles.keySet()));
                }
            }
            case "spawn" -> {
                if (args.length < 2) {
                    sender.sendMessage(prefix() + "§eУкажите ID: /chaosmob spawn <id>");
                    return true;
                }
                LivingEntity spawned = spawnNpc(args[1]);
                if (spawned == null) {
                    sender.sendMessage(prefix() + "§cНе удалось заспавнить NPC '" + args[1] + "' (проверьте ID, enabled и мир).");
                } else {
                    sender.sendMessage(prefix() + "§aЗаспавнен NPC: §e" + args[1]);
                }
            }
            case "respawn" -> {
                despawnAllTracked();
                spawnInitialGuaranteed();
                sender.sendMessage(prefix() + "§aВсе NPC переспавнены!");
            }
            case "reload" -> {
                reloadConfig();
                despawnAllTracked();
                loadNpcProfiles();
                if (getConfig().getBoolean("settings.auto-spawn-on-start", true)) {
                    spawnInitialGuaranteed();
                }
                sender.sendMessage(prefix() + color(getConfig().getString("messages.reloaded", "&aПерезагружено!")));
            }
            default -> sender.sendMessage(prefix() + "§eИспользование: /chaosmob <list|spawn|respawn|reload> [id]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("chaoscustommob.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("list", "spawn", "respawn", "reload").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            return new ArrayList<>(profiles.keySet()).stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                .toList();
        }
        return List.of();
    }
}
