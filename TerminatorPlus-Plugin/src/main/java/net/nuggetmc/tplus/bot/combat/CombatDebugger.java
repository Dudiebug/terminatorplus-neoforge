package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.bukkit.Bukkit;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.attribute.Attribute;
import net.nuggetmc.tplus.compat.bukkit.block.Block;
import net.nuggetmc.tplus.compat.bukkit.entity.Entity;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.inventory.PlayerInventory;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Opt-in per-bot combat/movement tracer. Call {@link #enable(UUID)} for a bot
 * and every event the pipeline emits for it will be logged to
 * {@code plugins/TerminatorPlus/debug/}.
 *
 * <p>When a bot is not enabled, {@link #isOn(Bot)} is a single hash-set
 * read and every {@code log*} method returns early — callers must still
 * avoid building payload strings before the gate, which is why the hot
 * variants take primitive/String args instead of a formatted message.
 */
public final class CombatDebugger {

    private static final Set<UUID> ENABLED = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Integer> LAST_PUNCH_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST_INV_DUMP_TICK = new ConcurrentHashMap<>();
    private static final Map<String, Integer> LAST_COMPACT_EVENT_TICK = new ConcurrentHashMap<>();
    private static final int INV_DUMP_INTERVAL_TICKS = 1; // every combat tick
    private static final ExecutorService FILE_IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "tplus-debug-writer");
        thread.setDaemon(true);
        return thread;
    });
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static volatile Path DEBUG_DIR_CACHE;
    private static volatile boolean allEnabled = false;

    private CombatDebugger() {}

    public static void enable(UUID bot) {
        ENABLED.add(bot);
    }

    public static void disable(UUID bot) {
        ENABLED.remove(bot);
        LAST_PUNCH_TICK.remove(bot);
        LAST_INV_DUMP_TICK.remove(bot);
        LAST_COMPACT_EVENT_TICK.keySet().removeIf(key -> key.startsWith(bot.toString() + "|"));
    }

    public static void enableAll() {
        allEnabled = true;
    }

    public static void disableAll() {
        allEnabled = false;
        ENABLED.clear();
        LAST_PUNCH_TICK.clear();
        LAST_INV_DUMP_TICK.clear();
        LAST_COMPACT_EVENT_TICK.clear();
    }

    public static void shutdown() {
        disableAll();
        DEBUG_DIR_CACHE = null;
        FILE_IO.shutdownNow();
    }

    public static boolean isOn(Bot bot) {
        return allEnabled || ENABLED.contains(bot.getUUID());
    }

    public static int enabledCount() {
        return allEnabled ? -1 : ENABLED.size();
    }

    /** Generic event with no payload. */
    public static void log(Bot bot, String event) {
        if (!isOn(bot)) return;
        emit(bot, event, "");
    }

    /** Event with a single pre-formatted detail string. Caller builds the string only after gate passes in isOn-checked contexts. */
    public static void log(Bot bot, String event, String detail) {
        if (!isOn(bot)) return;
        emit(bot, event, detail);
    }

    public static void dirEntry(Bot bot, double distance, CombatState.Phase phase, boolean grounded, double vy) {
        if (!isOn(bot)) return;
        emit(bot, "dir-entry",
                "dist=" + fmt(distance) + " phase=" + phase + " grounded=" + grounded + " vy=" + fmt(vy));
    }

    public static void weaponPick(Bot bot, String weapon, double distance, boolean cdReady) {
        if (!isOn(bot)) return;
        emit(bot, "weapon-pick", "w=" + weapon + " dist=" + fmt(distance) + " cdReady=" + cdReady);
    }

    public static void trainingDamage(
            Bot bot,
            double amount,
            String bucket,
            String classificationSource,
            String directType,
            String heldType,
            String loadout
    ) {
        if (!isOn(bot)) return;
        emit(bot, "training-damage",
                "amount=" + fmt(amount)
                        + " bucket=" + sanitizeToken(bucket)
                        + " classSource=" + sanitizeToken(classificationSource)
                        + " direct=" + sanitizeToken(directType)
                        + " held=" + sanitizeToken(heldType)
                        + " loadout=" + sanitizeToken(loadout));
    }

    public static void dirNoop(Bot bot, double distance, String reason, String branchAttempted) {
        if (!isOn(bot)) return;
        emit(bot, "dir-noop",
                "dist=" + fmt(distance) + " reason=" + reason
                        + " branch_attempted=" + (branchAttempted == null ? "none" : branchAttempted));
    }

    public static void meleeTry(Bot bot, float charge, boolean iframes, double distance) {
        if (!isOn(bot)) return;
        emit(bot, "melee-try", "charge=" + fmt(charge) + " iframes=" + iframes + " dist=" + fmt(distance));
    }

    public static void meleeHit(Bot bot, String weapon) {
        if (!isOn(bot)) return;
        emit(bot, "melee-hit", "w=" + weapon);
    }

    public static void macePhase(Bot bot, CombatState.Phase from, CombatState.Phase to) {
        if (!isOn(bot)) return;
        emit(bot, "mace-phase", "from=" + from + " to=" + to);
    }

    public static void maceCd(Bot bot, int ticksLeft) {
        if (!isOn(bot)) return;
        emit(bot, "mace-cd", "left=" + ticksLeft);
    }

    public static void maceSmash(Bot bot, double vy, boolean iframes, boolean onGround) {
        if (!isOn(bot)) return;
        emit(bot, "mace-smash", "vy=" + fmt(vy) + " iframes=" + iframes + " ground=" + onGround);
    }

    public static void swingBlock(Bot bot, String reason, float value) {
        if (!isOn(bot)) return;
        emit(bot, "swing-block", "reason=" + reason + " val=" + fmt(value));
    }

    public static void blockPlace(Bot bot, String source, Material material, Block block, Material previous) {
        if (!isOn(bot)) return;
        Block below = block.getRelative(0, -1, 0);
        Material belowType = below.getType();
        boolean belowSolid = belowType.isSolid();
        boolean floating = !belowSolid && block.getY() > block.getWorld().getMinHeight();
        Location loc = block.getLocation();
        emit(bot, "block-place",
                "src=" + sanitizeToken(source)
                        + " mat=" + material.name()
                        + " loc=" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
                        + " prev=" + previous.name()
                        + " below=" + belowType.name()
                        + " belowSolid=" + belowSolid
                        + " floating=" + floating);
    }

    /**
     * Full swing-decision trace: logs both passes and blocks, with the exact
     * charge / i-frame inputs used by the gate.
     */
    public static void swingGate(Bot bot, float charge, float minCharge, int iframes, boolean allowed, String reason) {
        if (!isOn(bot)) return;
        emit(bot, "swing-gate",
                "allowed=" + allowed
                        + " reason=" + reason
                        + " charge=" + fmt(charge)
                        + " need=" + fmt(minCharge)
                        + " iframes=" + iframes);
    }

    /**
     * Logs every punch animation with cadence (ticks since prior punch), held
     * item, and a best-effort source from the current call stack. Delegates
     * to {@link #punch(Bot, String)} with {@code null} so the caller-inference
     * path still runs.
     */
    public static void punch(Bot bot) {
        punch(bot, null);
    }

    /**
     * Overload that accepts an explicit semantic tag (e.g. {@code "clutch"},
     * {@code "pre-break"}, {@code "combat-swing"}). When {@code tag} is
     * non-null the stack-walk in {@link #inferCaller()} is skipped and the
     * tag is emitted verbatim as {@code src=<tag>}. When null we fall back
     * to the stack-walk and map a few known frames to short semantic names.
     */
    public static void punch(Bot bot, String tag) {
        if (!isOn(bot)) return;
        UUID id = bot.getUUID();
        int now = bot.getAliveTicks();
        Integer last = LAST_PUNCH_TICK.put(id, now);
        String cadence = (last == null) ? "first" : String.valueOf(now - last);
        float charge = bot.getAttackStrengthScale(0.0f);
        ItemStack held = bot.getBukkitEntity().getInventory().getItemInMainHand();
        String heldType = (held == null) ? "AIR" : held.getType().name();
        String src = (tag != null) ? tag : mapCallerToTag(inferCaller());
        emit(bot, "punch",
                "dt=" + cadence + " held=" + heldType + " charge=" + fmt(charge) + " src=" + src);
    }

    /**
     * Translate raw {@code ClassName#method} frames into short, stable tags
     * so post-fight grepping doesn't have to care about lambda suffixes.
     * Anything not matched falls through to the original label.
     */
    private static String mapCallerToTag(String raw) {
        if (raw == null || raw.isEmpty()) return "unknown";
        if (raw.startsWith("LegacyBlockCheck#")) return "clutch";
        if (raw.startsWith("LegacyAgent#lambda$checkUp")) return "pre-break";
        if (raw.equals("Bot#attack")) return "attack";
        if (raw.equals("Bot#attemptBlockPlace")) return "block-place";
        return raw;
    }

    /**
     * Inventory snapshot emitted each combat tick. The rest of combatdebug stays
     * compact, but held-item churn is high-value when diagnosing bad weapon swaps.
     */
    public static void inventorySnapshot(Bot bot) {
        inventorySnapshot(bot, "periodic", false);
    }

    public static void inventorySnapshotNow(Bot bot, String reason) {
        inventorySnapshot(bot, reason == null || reason.isBlank() ? "manual" : reason, true);
    }

    private static void inventorySnapshot(Bot bot, String reason, boolean force) {
        if (!isOn(bot)) return;
        UUID id = bot.getUUID();
        int now = bot.getAliveTicks();
        Integer last = LAST_INV_DUMP_TICK.get(id);
        if (!force && last != null && now - last < INV_DUMP_INTERVAL_TICKS) return;
        LAST_INV_DUMP_TICK.put(id, now);

        PlayerInventory inv = bot.getBukkitEntity().getInventory();
        StringBuilder hot = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            if (i > 0) hot.append(',');
            hot.append(i).append(':').append(shortMat(inv.getItem(i)));
        }
        int heldSlot = inv.getHeldItemSlot();
        ItemStack held = inv.getItem(heldSlot);
        ItemStack off = inv.getItemInOffHand();
        ItemStack helm = inv.getHelmet();
        ItemStack chest = inv.getChestplate();
        ItemStack legs = inv.getLeggings();
        ItemStack boots = inv.getBoots();
        emit(bot, "inventory",
                "reason=" + sanitizeToken(reason)
                        + " held=" + heldSlot + ":" + shortMat(held)
                        + " hot=[" + hot + "]"
                        + " off=" + shortMat(off)
                        + " blocking=" + bot.isBotBlocking()
                        + " kit[melee=" + (bot.getBotInventory().findSword() >= 0 || bot.getBotInventory().findAxe() >= 0)
                        + " axe=" + (bot.getBotInventory().findAxe() >= 0)
                        + " mace=" + bot.getBotInventory().hasMace()
                        + " trident=" + bot.getBotInventory().hasTrident()
                        + " pearl=" + bot.getBotInventory().hasEnderPearl()
                        + " wind=" + bot.getBotInventory().hasWindCharge()
                        + " crystal=" + bot.getBotInventory().hasCrystalKit()
                        + " anchor=" + bot.getBotInventory().hasAnchorKit()
                        + " cobweb=" + bot.getBotInventory().hasCobweb()
                        + " totem=" + bot.getBotInventory().hasTotem()
                        + " elytra=" + bot.getBotInventory().hasElytra()
                        + " firework=" + bot.getBotInventory().hasFirework()
                        + "] stock[wind=" + countMaterial(inv, Material.WIND_CHARGE)
                        + " pearl=" + countMaterial(inv, Material.ENDER_PEARL)
                        + " crystal=" + countMaterial(inv, Material.END_CRYSTAL)
                        + " obsidian=" + countMaterial(inv, Material.OBSIDIAN)
                        + " anchor=" + countMaterial(inv, Material.RESPAWN_ANCHOR)
                        + " glowstone=" + countMaterial(inv, Material.GLOWSTONE)
                        + " cobweb=" + countMaterial(inv, Material.COBWEB)
                        + " gapple=" + countMaterial(inv, Material.GOLDEN_APPLE)
                        + " totem=" + countMaterial(inv, Material.TOTEM_OF_UNDYING)
                        + " rocket=" + countMaterial(inv, Material.FIREWORK_ROCKET)
                        + "]"
                        + " H=" + shortMat(helm)
                        + " C=" + shortMat(chest)
                        + " L=" + shortMat(legs)
                        + " B=" + shortMat(boots));
    }

    private static String shortMat(ItemStack stack) {
        if (stack == null) return "AIR";
        Material m = stack.getType();
        if (m == Material.AIR) return "AIR";
        return m.name();
    }

    private static int countMaterial(PlayerInventory inv, Material type) {
        int total = 0;
        for (ItemStack stack : inv.getStorageContents()) {
            if (stack != null && stack.getType() == type) {
                total += stack.getAmount();
            }
        }
        ItemStack off = inv.getItemInOffHand();
        if (off != null && off.getType() == type) {
            total += off.getAmount();
        }
        return total;
    }

    private static void emit(Bot bot, String event, String detail) {
        if (!shouldEmitCompact(bot, event)) return;
        String payload = "[tplus-cbt] "
                + "bot=" + sanitizeToken(bot.getBotName())
                + " tick=" + bot.getAliveTicks()
                + " " + describeTarget(bot)
                + " event=" + event
                + (detail.isEmpty() ? "" : " " + detail);
        String line = TS_FORMAT.format(LocalDateTime.now()) + " " + payload;
        if (FILE_IO.isShutdown()) return;
        try {
            FILE_IO.execute(() -> writeToDebugFiles(bot, line));
        } catch (RejectedExecutionException ignored) {
        }
    }

    private static boolean shouldEmitCompact(Bot bot, String event) {
        if (event.startsWith("move-")) return false;
        int interval = compactInterval(event);
        if (interval <= 0) return true;
        String key = bot.getUUID() + "|" + event;
        int now = bot.getAliveTicks();
        Integer last = LAST_COMPACT_EVENT_TICK.get(key);
        if (last != null && now - last < interval) return false;
        LAST_COMPACT_EVENT_TICK.put(key, now);
        return true;
    }

    private static int compactInterval(String event) {
        return switch (event) {
            case "dir-entry", "dir-ready", "snapshot", "scanner-miss" -> 100;
            case "dir-noop", "commit-skip" -> 40;
            case "weapon-pick", "mace-cd", "melee-choice" -> 20;
            case "melee-try", "swing-gate", "sweep-check", "sweep-skip" -> 10;
            default -> 0;
        };
    }

    private static String describeTarget(Bot bot) {
        UUID targetId = bot.getTargetPlayer();
        if (targetId == null) {
            return "target=none";
        }

        Entity entity = Bukkit.getEntity(targetId);
        if (!(entity instanceof LivingEntity living) || !entity.isValid()) {
            return "target=stale id=" + shortId(targetId);
        }

        StringBuilder out = new StringBuilder()
                .append("target=").append(sanitizeToken(entity.getName()))
                .append(" targetType=").append(entity.getType().name());

        Location botLoc = bot.getBukkitEntity().getLocation();
        Location targetLoc = entity.getLocation();
        if (botLoc.getWorld() != null && botLoc.getWorld().equals(targetLoc.getWorld())) {
            out.append(" targetDist=").append(fmt(botLoc.distance(targetLoc)));
        }

        out.append(" targetHp=").append(fmt(living.getHealth()));
        double maxHp = maxHealthOf(living);
        if (maxHp > 0.0) {
            out.append("/").append(fmt(maxHp));
        }

        return out.toString();
    }

    private static double maxHealthOf(LivingEntity living) {
        if (living.getAttribute(Attribute.MAX_HEALTH) == null) {
            return -1.0;
        }
        return living.getAttribute(Attribute.MAX_HEALTH).getValue();
    }

    private static String fmt(double d) {
        return String.format("%.2f", d);
    }

    private static String inferCaller() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : trace) {
            String owner = frame.getClassName();
            if (owner.equals(Thread.class.getName())) continue;
            if (owner.equals(CombatDebugger.class.getName())) continue;
            if (owner.equals(Bot.class.getName()) && frame.getMethodName().equals("punch")) continue;
            String simple = owner.substring(owner.lastIndexOf('.') + 1);
            return simple + "#" + frame.getMethodName();
        }
        return "unknown";
    }

    private static void writeToDebugFiles(Bot bot, String line) {
        try {
            Path dir = resolveDebugDir();
            String safeBotName = sanitize(bot.getBotName());
            Path perBot = dir.resolve("combat-" + safeBotName + "-" + bot.getUUID() + ".log");
            Path all = dir.resolve("combat-all.log");
            appendLine(perBot, line);
            appendLine(all, line);
        } catch (Exception e) {
            // Fallback (rare): if file writes fail, still surface the trace line.
            Bukkit.getLogger().warning("[tplus-cbt] failed to write debug file: " + e.getMessage());
            Bukkit.getLogger().info(line);
        }
    }

    private static Path resolveDebugDir() throws IOException {
        Path cached = DEBUG_DIR_CACHE;
        if (cached != null) {
            return cached;
        }
        TerminatorPlus plugin = TerminatorPlus.getInstance();
        Path dir;
        if (plugin != null) {
            dir = plugin.getDataFolder().toPath().resolve("debug");
        } else {
            dir = Path.of("plugins", "TerminatorPlus", "debug");
        }
        Files.createDirectories(dir);
        DEBUG_DIR_CACHE = dir;
        return dir;
    }

    private static void appendLine(Path file, String line) throws IOException {
        Files.writeString(file, line + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String sanitizeToken(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        return value.replaceAll("\\s+", "_").replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    private static String shortId(UUID id) {
        String raw = id.toString();
        return raw.substring(0, 8);
    }
}
