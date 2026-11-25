package com.chaossmp.custommob;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ChaosCustomMobPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private NamespacedKey npcIdKey;
    private File skinsFolder;
    private File npcsFolder;

    private final Map<String, NpcProfile> profiles = new LinkedHashMap<>();
    private final Map<String, UUID> activeEntities = new HashMap<>();

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

    private record AttackEffectSpec(PotionEffectType type, int duration, int amplifier) {}

    private record NpcProfile(
        String id,
        boolean enabled,
        String name,
        EntityType entityType,
        Behavior behavior,
        String worldName,
        double x,
        double y,
        double z,
        int respawnSeconds,
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
    ) {
        Location toLocation() {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return null;
            }
            return new Location(world, x, y, z);
        }
    }

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
            Bukkit.getScheduler().runTask(this, this::spawnAllProfiles);
        }

        getLogger().info("ChaosCustomMob успешно запущен! Загружено NPC: " + profiles.size());
    }

    @Override
    public void onDisable() {
        despawnAllTracked();
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

                location:
                  world: "world"
                  x: 0.5
                  y: 65.0
                  z: 0.5
                  respawn-seconds: 60

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

            String worldName = cfg.getString("location.world", "world");
            double x = cfg.getDouble("location.x", 0.0);
            double y = cfg.getDouble("location.y", 65.0);
            double z = cfg.getDouble("location.z", 0.0);
            int respawnSeconds = cfg.getInt("location.respawn-seconds", defaultRespawn);

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
            List> effectMaps = cfg.getMapList("actions.on-attack-hit");
            for (Map<?, ?> entry : effectMaps) {
                Object effObj = entry.get("effect");
                if (effObj == null) {
                    continue;
                }
                PotionEffectType effType = parseEffectType(effObj.toString());
                if (effType == null) {
                    continue;
                }
                int duration = entry.containsKey("duration") ? Integer.parseInt(entry.get("duration").toString()) : 40;
                int amplifier = entry.containsKey("amplifier") ? Integer.parseInt(entry.get("amplifier").toString()) : 0;
                attackEffects.add(new AttackEffectSpec(effType, duration, amplifier));
            }

            if (skinFile != null && !skinFile.isBlank()) {
                File candidate = new File(skinsFolder, skinFile);
                if (!candidate.exists()) {
                    getLogger().warning("Для NPC '" + id + "' указан skin-file '" + skinFile + "', но файл не найден в папке skins/");
                }
            }

            profiles.put(id, new NpcProfile(
                id, enabled, name, entityType, behavior,
                worldName, x, y, z, respawnSeconds,
                health, damage, speed, glowing, skinFile,
                helmet, chestplate, leggings, boots, mainHand,
                interactMessages, attackEffects
            ));
        }
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

        Location loc = profile.toLocation();
        if (loc == null) {
            getLogger().warning("Мир '" + profile.worldName() + "' для NPC '" + id + "' не загружен.");
            return null;
        }

        UUID previousUuid = activeEntities.remove(id);
        if (previousUuid != null) {
            Entity old = Bukkit.getEntity(previousUuid);
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
                    // Атакует только в ответ на удар (обрабатывается в EntityDamageByEntityEvent)
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

        activeEntities.put(profile.id(), entity.getUniqueId());
        return entity;
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

    public void spawnAllProfiles() {
        for (String id : profiles.keySet()) {
            spawnNpc(id);
        }
    }

    public void despawnAllTracked() {
        for (UUID uuid : activeEntities.values()) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null && entity.isValid()) {
                entity.remove();
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

        activeEntities.remove(npcId, entity.getUniqueId());
        NpcProfile profile = profiles.get(npcId);
        if (profile != null && profile.enabled() && profile.respawnSeconds() > 0) {
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
                spawnAllProfiles();
                sender.sendMessage(prefix() + "§aВсе NPC переспавнены!");
            }
            case "reload" -> {
                reloadConfig();
                despawnAllTracked();
                loadNpcProfiles();
                if (getConfig().getBoolean("settings.auto-spawn-on-start", true)) {
                    spawnAllProfiles();
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
