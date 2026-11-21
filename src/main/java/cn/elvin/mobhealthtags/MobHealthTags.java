package cn.elvin.mobhealthtags;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家攻击生物后，临时在其头顶显示中文名字与血量，默认 5 秒后自动还原。
 *
 * Folia 线程模型：
 * - 伤害事件由受害者所在区域线程触发，名称牌写入通过生物自己的 EntityScheduler 完成。
 * - 全局对账任务只操作 UUID/时间戳，不跨区域直接修改实体。
 * - 通过反射软依赖 MythicMobs，不把 Mythic 的 jar 打进插件。
 */
public final class MobHealthTags extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /** 生物 UUID -> 名称牌应保留到的时间戳（毫秒） */
    private final ConcurrentHashMap<UUID, Long> activeUntil = new ConcurrentHashMap<>();
    /** 生物 UUID -> 原始名称牌状态，用于到期后还原 */
    private final ConcurrentHashMap<UUID, TagBackup> backups = new ConcurrentHashMap<>();

    private ScheduledTask reconciler;

    private long durationMillis;
    private long interval;
    private String healthMode;
    private boolean showName;
    private boolean showHealth;
    private boolean healthColor;
    private boolean overrideNames;
    private String format;
    private Set<String> extraTypes = new HashSet<>();
    private Set<String> excludedTypes = new HashSet<>();
    private boolean mythicEnabled;
    private boolean mythicUseDisplayName;

    private Object mythicMobManager;
    private Method mythicIsMythicMob;

    private static final Map<String, String> ZH_NAMES = new HashMap<>();

    static {
        zh("ZOMBIE", "僵尸");
        zh("ZOMBIE_VILLAGER", "僵尸村民");
        zh("HUSK", "尸壳");
        zh("DROWNED", "溺尸");
        zh("SKELETON", "骷髅");
        zh("WITHER_SKELETON", "凋灵骷髅");
        zh("STRAY", "流浪者");
        zh("BOGGED", "沼骸");
        zh("CREEPER", "苦力怕");
        zh("SPIDER", "蜘蛛");
        zh("CAVE_SPIDER", "洞穴蜘蛛");
        zh("ENDERMAN", "末影人");
        zh("ENDERMITE", "末影螨");
        zh("SILVERFISH", "蠹虫");
        zh("WITCH", "女巫");
        zh("GHAST", "恶魂");
        zh("SLIME", "史莱姆");
        zh("MAGMA_CUBE", "岩浆怪");
        zh("BLAZE", "烈焰人");
        zh("PIGLIN", "猪灵");
        zh("PIGLIN_BRUTE", "猪灵蛮兵");
        zh("ZOGLIN", "僵尸猪灵");
        zh("HOGLIN", "疣猪兽");
        zh("RAVAGER", "劫掠兽");
        zh("VINDICATOR", "卫道士");
        zh("PILLAGER", "掠夺者");
        zh("ILLUSIONER", "幻术师");
        zh("EVOKER", "唤魔者");
        zh("VEX", "恼鬼");
        zh("WARDEN", "监守者");
        zh("ENDER_DRAGON", "末影龙");
        zh("WITHER", "凋灵");
        zh("GUARDIAN", "守卫者");
        zh("ELDER_GUARDIAN", "远古守卫者");
        zh("PHANTOM", "幻翼");
        zh("SHULKER", "潜影贝");
        zh("BREEZE", "旋风人");
        zh("CREAKING", "吱嘎");
        zh("IRON_GOLEM", "铁傀儡");
        zh("SNOW_GOLEM", "雪傀儡");
        zh("WOLF", "狼");
        zh("POLAR_BEAR", "北极熊");
        zh("BEE", "蜜蜂");
        zh("PANDA", "熊猫");
        zh("LLAMA", "羊驼");
        zh("TRADER_LLAMA", "行商羊驼");
        zh("GOAT", "山羊");
        zh("AXOLOTL", "美西螈");
        zh("FROG", "青蛙");
        zh("TADPOLE", "蝌蚪");
        zh("CAMEL", "骆驼");
        zh("SNIFFER", "嗅探兽");
        zh("VILLAGER", "村民");
        zh("WANDERING_TRADER", "流浪商人");
        zh("BAT", "蝙蝠");
        zh("CAT", "猫");
        zh("OCELOT", "豹猫");
        zh("FOX", "狐狸");
        zh("RABBIT", "兔子");
    }

    private static void zh(String type, String name) {
        ZH_NAMES.put(type, name);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        initMythicHook();

        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("mobhealthtags")).setExecutor(this);
        startReconciler();

        getLogger().info("已启用 | 攻击触发 | 显示 " + (durationMillis / 1000) + " 秒"
                + (mythicIsMythicMob != null ? " | MythicMobs 兼容已开启" : ""));
    }

    @Override
    public void onDisable() {
        stopReconciler();
        for (UUID id : List.copyOf(backups.keySet())) {
            restoreTag(id);
        }
        activeUntil.clear();
    }

    private void startReconciler() {
        stopReconciler();
        reconciler = Bukkit.getServer().getGlobalRegionScheduler().runAtFixedRate(
                this,
                task -> reconcile(),
                1L,
                Math.max(1L, interval)
        );
    }

    private void stopReconciler() {
        if (reconciler != null) {
            reconciler.cancel();
            reconciler = null;
        }
    }

    private void loadSettings() {
        reloadConfig();
        var cfg = getConfig();
        durationMillis = Math.max(100L, cfg.getLong("display-seconds", 5L)) * 1000L;
        interval = Math.max(1L, cfg.getLong("update-interval", 5L));
        healthMode = cfg.getString("health-mode", "both").toLowerCase(Locale.ROOT);
        if (!healthMode.equals("absolute") && !healthMode.equals("percent") && !healthMode.equals("both")) {
            healthMode = "both";
        }
        showName = cfg.getBoolean("show-name", true);
        showHealth = cfg.getBoolean("show-health", true);
        healthColor = cfg.getBoolean("health-color", true);
        overrideNames = cfg.getBoolean("override-custom-names", false);
        format = cfg.getString("format", "&f{name} &8[&8{health}&8]");
        extraTypes = new HashSet<>(cfg.getStringList("extra-types"));
        excludedTypes = new HashSet<>(cfg.getStringList("excluded-types"));
        mythicEnabled = cfg.getBoolean("mythicmobs.enabled", true);
        mythicUseDisplayName = cfg.getBoolean("mythicmobs.use-display-name", true);
    }

    // ---------------- MythicMobs 软依赖 ----------------

    private void initMythicHook() {
        mythicMobManager = null;
        mythicIsMythicMob = null;
        if (!mythicEnabled) {
            return;
        }
        try {
            Class<?> mythicBukkit = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = mythicBukkit.getMethod("inst").invoke(null);
            Object manager = inst.getClass().getMethod("getMobManager").invoke(inst);
            Method method = manager.getClass().getMethod("isMythicMob", Entity.class);
            method.setAccessible(true);
            mythicMobManager = manager;
            mythicIsMythicMob = method;
        } catch (Throwable ex) {
            getLogger().info("未检测到 MythicMobs，将只处理原版生物。");
        }
    }

    private boolean isMythicMob(Entity entity) {
        if (mythicIsMythicMob == null || mythicMobManager == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(mythicIsMythicMob.invoke(mythicMobManager, entity));
        } catch (Throwable ex) {
            return false;
        }
    }

    // ---------------- 攻击触发 ----------------

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || !isTarget(victim)) {
            return;
        }
        UUID id = victim.getUniqueId();
        activeUntil.put(id, System.currentTimeMillis() + durationMillis);
        // 延迟 1 tick，让本次伤害结算后血量已经更新
        applyTag(id, true);
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    private boolean isTarget(LivingEntity entity) {
        if (entity instanceof Player) {
            return false;
        }
        // MythicMobs 可能使用任意原版类型作为载体，优先按 Mythic 身份放行
        if (isMythicMob(entity)) {
            return true;
        }
        String type = entity.getType().name();
        if (excludedTypes.contains(type)) {
            return false;
        }
        if (entity instanceof Monster || entity instanceof Slime
                || entity instanceof EnderDragon || entity instanceof Ghast
                || entity instanceof Phantom) {
            return true;
        }
        return extraTypes.contains(type);
    }

    // ---------------- 全局对账：到期还原 ----------------

    private void reconcile() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> iterator = activeUntil.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            Long until = entry.getValue();
            if (until == null || now >= until) {
                iterator.remove();
                restoreTag(entry.getKey());
            } else if (!backups.containsKey(entry.getKey())) {
                applyTag(entry.getKey(), false);
            }
        }
        // 兜底：实体已消失或状态被外部清理时，避免留下孤立的备份
        for (UUID id : List.copyOf(backups.keySet())) {
            if (!activeUntil.containsKey(id)) {
                restoreTag(id);
            }
        }
    }

    private void applyTag(UUID id, boolean force) {
        Entity entity = Bukkit.getEntity(id);
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        entity.getScheduler().execute(this, () -> {
            if (!living.isValid() || living.isDead()) {
                return;
            }
            boolean mythic = isMythicMob(living);
            TagBackup existing = backups.get(id);

            // 已在显示中：刷新血量（多次攻击时保持最新）
            if (existing != null) {
                living.customName(buildDisplayName(living, existing.name(), mythic));
                living.setCustomNameVisible(true);
                return;
            }

            Component originalName = living.customName();
            boolean originalVisible = living.isCustomNameVisible();
            if (originalName != null && !mythic && !overrideNames && !force) {
                return;
            }
            backups.put(id, new TagBackup(originalName, originalVisible));
            living.customName(buildDisplayName(living, originalName, mythic));
            living.setCustomNameVisible(true);
        }, () -> backups.remove(id), 1L);
    }

    private void restoreTag(UUID id) {
        Entity entity = Bukkit.getEntity(id);
        if (entity == null) {
            backups.remove(id);
            return;
        }
        entity.getScheduler().execute(this, () -> {
            TagBackup backup = backups.remove(id);
            if (backup == null || !entity.isValid()) {
                return;
            }
            if (entity instanceof LivingEntity living) {
                living.customName(backup.name());
                living.setCustomNameVisible(backup.visible());
            }
        }, () -> backups.remove(id), 0L);
    }

    // ---------------- 显示文本 ----------------

    private Component buildDisplayName(LivingEntity entity, Component originalName, boolean mythic) {
        double health = Math.max(0.0, entity.getHealth());
        double maxHealth = entity.getMaxHealth();
        int percent = maxHealth <= 0 ? 0 : (int) Math.round(health / maxHealth * 100.0);
        String color = healthColor ? (percent >= 66 ? "&a" : percent >= 33 ? "&e" : "&c") : "";

        String name;
        if (originalName != null && (mythic ? mythicUseDisplayName : overrideNames)) {
            // MythicMobs 的 Display（或已命名的原版生物）本身就是名字
            name = LEGACY.serialize(originalName);
        } else {
            name = chineseName(entity.getType().name());
        }

        String text = format
                .replace("{name}", showName ? name : "")
                .replace("{health}", showHealth ? healthText(health, maxHealth, percent, color) : "")
                .replace("{max_health}", showHealth ? number(maxHealth) : "")
                .replace("{percent}", showHealth ? color + percent + "%" : "")
                .replace("{heart}", showHealth ? "❤" : "")
                .trim();

        return LEGACY.deserialize(text);
    }

    private String healthText(double health, double maxHealth, int percent, String color) {
        return switch (healthMode) {
            case "absolute" -> color + number(health) + "&8/&8" + color + number(maxHealth);
            case "percent" -> color + percent + "%";
            default -> color + number(health) + "&8/&8" + color + number(maxHealth)
                    + " &7(" + color + percent + "%&7)";
        };
    }

    private static String chineseName(String entityType) {
        String mapped = ZH_NAMES.get(entityType);
        if (mapped != null) {
            return mapped;
        }
        String[] parts = entityType.toLowerCase(Locale.ROOT).split("_");
        StringBuilder fallback = new StringBuilder();
        for (String part : parts) {
            if (fallback.length() > 0) {
                fallback.append(' ');
            }
            fallback.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return fallback.toString();
    }

    private static String number(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value)
                : String.format(Locale.US, "%.1f", value);
    }

    // ---------------- 命令 ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("mobhealthtags")) {
            return false;
        }
        if (!sender.hasPermission("mobhealthtags.reload")) {
            sender.sendMessage("§c你没有权限使用该命令。");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            for (UUID id : List.copyOf(backups.keySet())) {
                restoreTag(id);
            }
            activeUntil.clear();
            loadSettings();
            initMythicHook();
            startReconciler();
            sender.sendMessage("§a[MobHealthTags] 配置已重载。");
            return true;
        }
        sender.sendMessage("§e用法: /mobhealthtags reload");
        return true;
    }

    private record TagBackup(Component name, boolean visible) {
    }
}
