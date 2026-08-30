package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;

/**
 * Central owner for player-like primary action state. The first migration step
 * is telemetry-first: existing direct-result behaviors can mark themselves here
 * without changing execution timing yet.
 */
public final class PlayerLikeActionController {

    private BotActionState state = BotActionState.IDLE;
    private int startedTick = -1;
    private int remainingTicks;
    private int selectedSlot = -1;
    private Material selectedMaterial = Material.AIR;
    private String source = "none";
    private String interruptionReason = "";
    private String completionResult = "";
    private Runnable completionCallback;
    private int tick = -1;
    private int primaryActionsThisTick;
    private int sameTickActionViolations;
    private int directShortcutCount;
    private int instantConsumeShortcutCount;
    private int interruptionCount;
    private int healCompletionCount;
    private int healCancelCount;

    public void beginTick(Bot bot) {
        int now = bot == null ? -1 : bot.getAliveTicks();
        if (now == tick) return;
        tick = now;
        primaryActionsThisTick = 0;
        if (state != BotActionState.IDLE && remainingTicks > 0) {
            remainingTicks--;
            if (remainingTicks <= 0) {
                complete(bot, "elapsed");
            }
        }
    }

    public boolean start(Bot bot, BotActionState nextState, int durationTicks, int lockedSlot, String nextSource) {
        return start(bot, nextState, durationTicks, lockedSlot, nextSource, null);
    }

    public boolean start(
            Bot bot,
            BotActionState nextState,
            int durationTicks,
            int lockedSlot,
            String nextSource,
            Runnable onComplete
    ) {
        beginTick(bot);
        if (state != BotActionState.IDLE) {
            CombatDebugger.log(bot, "action-busy",
                    "active=" + state
                            + " next=" + safeState(nextState)
                            + " src=" + token(nextSource)
                            + " activeSrc=" + token(source)
                            + " left=" + remainingTicks);
            return false;
        }
        reservePrimary(bot, nextState, nextSource, lockedSlot, false);
        state = nextState == null ? BotActionState.IDLE : nextState;
        startedTick = bot == null ? -1 : bot.getAliveTicks();
        remainingTicks = Math.max(0, durationTicks);
        selectedSlot = lockedSlot;
        selectedMaterial = selectedMaterial(bot, lockedSlot);
        source = token(nextSource);
        interruptionReason = "";
        completionResult = "";
        completionCallback = onComplete;
        CombatDebugger.log(bot, "action-start", describe());
        return true;
    }

    public void complete(Bot bot, String result) {
        if (state == BotActionState.IDLE) return;
        completionResult = token(result);
        if (isHealSource(source)) {
            healCompletionCount++;
        }
        Runnable callback = completionCallback;
        completionCallback = null;
        if (callback != null) {
            callback.run();
        }
        CombatDebugger.log(bot, "action-complete", describe() + " result=" + completionResult);
        reset();
    }

    public void interrupt(Bot bot, String reason) {
        if (state == BotActionState.IDLE) return;
        interruptionReason = token(reason);
        if (isHealSource(source)) {
            healCancelCount++;
        }
        completionCallback = null;
        interruptionCount++;
        CombatDebugger.log(bot, "action-interrupt", describe() + " reason=" + interruptionReason);
        reset();
    }

    public void recordDirectShortcut(Bot bot, BotActionState shortcutState, String shortcutSource, int slot) {
        beginTick(bot);
        directShortcutCount++;
        if (shortcutState == BotActionState.USING_CONSUMABLE
                || shortcutState == BotActionState.DRINKING_POTION
                || token(shortcutSource).contains("instant")) {
            instantConsumeShortcutCount++;
        }
        reservePrimary(bot, shortcutState, shortcutSource, slot, true);
        Material material = selectedMaterial(bot, slot);
        CombatDebugger.log(bot, "action-shortcut",
                "state=" + safeState(shortcutState)
                        + " src=" + token(shortcutSource)
                        + " slot=" + slot
                        + " item=" + material.name()
                        + " active=" + state
                        + " selectedBefore=" + selectedSlotBefore(bot)
                        + " activeItem=" + activeItem(bot));
    }

    public void recordPrimaryAction(Bot bot, BotActionState actionState, String actionSource, int slot) {
        beginTick(bot);
        reservePrimary(bot, actionState, actionSource, slot, false);
        CombatDebugger.log(bot, "action-primary",
                "state=" + safeState(actionState)
                        + " src=" + token(actionSource)
                        + " slot=" + slot
                        + " item=" + selectedMaterial(bot, slot).name()
                        + " active=" + state);
    }

    public BotActionState state() {
        return state;
    }

    public boolean active() {
        return state != BotActionState.IDLE;
    }

    public boolean active(BotActionState expected) {
        return state == expected;
    }

    public boolean blocksCombatAction() {
        return switch (state) {
            case USING_CONSUMABLE, DRINKING_POTION, THROWING_PROJECTILE, OPENING,
                    PLACING_BLOCK, PILLARING, MINING, FALL_CLUTCH, USING_PEARL -> true;
            default -> false;
        };
    }

    public boolean isMovementV2TraversalAction() {
        return active() && source.startsWith("movement-v2-");
    }

    public int remainingTicks() {
        return remainingTicks;
    }

    public String source() {
        return source;
    }

    public int sameTickActionViolations() {
        return sameTickActionViolations;
    }

    public int directShortcutCount() {
        return directShortcutCount;
    }

    public int instantConsumeShortcutCount() {
        return instantConsumeShortcutCount;
    }

    public int interruptionCount() {
        return interruptionCount;
    }

    public int healCompletionCount() {
        return healCompletionCount;
    }

    public int healCancelCount() {
        return healCancelCount;
    }

    private void reservePrimary(Bot bot, BotActionState actionState, String actionSource, int slot, boolean shortcut) {
        primaryActionsThisTick++;
        if (primaryActionsThisTick > 1) {
            sameTickActionViolations++;
            CombatDebugger.log(bot, "action-same-tick",
                    "count=" + primaryActionsThisTick
                            + " state=" + safeState(actionState)
                            + " src=" + token(actionSource)
                            + " slot=" + slot
                            + " shortcut=" + shortcut
                            + " total=" + sameTickActionViolations);
        }
    }

    private String describe() {
        return "state=" + state
                + " src=" + source
                + " started=" + startedTick
                + " left=" + remainingTicks
                + " slot=" + selectedSlot
                + " item=" + selectedMaterial.name();
    }

    private void reset() {
        state = BotActionState.IDLE;
        startedTick = -1;
        remainingTicks = 0;
        selectedSlot = -1;
        selectedMaterial = Material.AIR;
        source = "none";
        completionCallback = null;
    }

    private static String safeState(BotActionState value) {
        return value == null ? BotActionState.IDLE.name() : value.name();
    }

    private static String token(String value) {
        if (value == null || value.isBlank()) return "none";
        return value.trim().replaceAll("\\s+", "_").replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    private static Material selectedMaterial(Bot bot, int slot) {
        if (bot == null || slot < 0) return Material.AIR;
        ItemStack stack = bot.getBotInventory().raw().getItem(slot);
        return stack == null ? Material.AIR : stack.getType();
    }

    private static int selectedSlotBefore(Bot bot) {
        return bot == null ? -1 : bot.getBotInventory().getSelectedHotbarSlot();
    }

    private static String activeItem(Bot bot) {
        if (bot == null || !(bot.getBukkitEntity() instanceof Player player)) return "AIR";
        ItemStack active = player.getActiveItem();
        if (active == null) return "AIR";
        return active.getType().name();
    }

    private static boolean isHealSource(String value) {
        String token = token(value).toLowerCase(java.util.Locale.ROOT);
        return token.contains("heal") || token.contains("gapple") || token.contains("apple");
    }
}
