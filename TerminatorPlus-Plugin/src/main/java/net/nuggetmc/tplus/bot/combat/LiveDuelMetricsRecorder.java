package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.LiveDuelMetricsSnapshot;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.movement.MovementOutputApplier;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight live-duel accumulator. It records facts from the actual runtime
 * loop; report-only evaluation exports should keep their null metrics.
 */
public final class LiveDuelMetricsRecorder {
    private static final Map<UUID, Mutable> METRICS = new ConcurrentHashMap<>();

    private LiveDuelMetricsRecorder() {
    }

    public static void recordTick(Bot bot, LivingEntity target, double distance) {
        if (bot == null || target == null || !target.isValid()) return;
        Mutable metrics = mutable(bot);
        metrics.ticks++;

        CombatIntent intent = bot.getCombatIntent();
        CombatIntent safeIntent = intent == null ? CombatIntent.DEFAULT : intent;
        double min = safeIntent.minSafeRange();
        double max = safeIntent.maxUsefulRange();
        if (distance < min) {
            metrics.tooCloseTicks++;
        } else if (distance > max) {
            metrics.tooFarTicks++;
        } else {
            metrics.desiredRangeTicks++;
        }

        String activeFamily = safeIntent.movementLocked(bot.getAliveTicks())
                ? safeIntent.lockFamily().id()
                : safeIntent.branchFamily().id();
        if (!metrics.lastRouteFamily.isBlank() && !metrics.lastRouteFamily.equals(activeFamily)) {
            metrics.routeThrashCount++;
        }
        metrics.lastRouteFamily = activeFamily;

        if (bot.getMovementState().isRetreating()) {
            metrics.retreatTicks++;
            if (distance >= min) {
                metrics.retreatSuccessTicks++;
            } else {
                metrics.retreatFailureTicks++;
            }
        }
    }

    public static void recordMovementResult(Bot bot, MovementOutputApplier.ApplyResult result) {
        if (bot == null || result == null) return;
        Mutable metrics = mutable(bot);
        if (result.fallback()) metrics.movementFallbackTicks++;
        if (result.held()) metrics.movementHeldTicks++;
    }

    public static void recordDamageDealt(Bot bot, double amount) {
        if (bot != null && amount > 0.0) mutable(bot).damageDealt += amount;
    }

    public static void recordDamageTaken(Bot bot, double amount) {
        if (bot != null && amount > 0.0) mutable(bot).damageTaken += amount;
    }

    public static LiveDuelMetricsSnapshot snapshot(Bot bot) {
        if (bot == null) return LiveDuelMetricsSnapshot.unavailable();
        Mutable metrics = METRICS.get(bot.getUUID());
        if (metrics == null || metrics.ticks <= 0) return LiveDuelMetricsSnapshot.unavailable();
        PlayerLikeActionController actions = bot.getActionController();
        double ticks = Math.max(1.0, metrics.ticks);
        double damageRatio = metrics.damageTaken <= 0.0 ? metrics.damageDealt : metrics.damageDealt / metrics.damageTaken;
        double retreatRate = metrics.retreatTicks <= 0
                ? 0.0
                : metrics.retreatSuccessTicks / (double) metrics.retreatTicks;
        return new LiveDuelMetricsSnapshot(
                true,
                metrics.ticks,
                metrics.damageDealt,
                metrics.damageTaken,
                metrics.damageDealt - metrics.damageTaken,
                damageRatio,
                metrics.desiredRangeTicks,
                metrics.tooCloseTicks,
                metrics.tooFarTicks,
                metrics.desiredRangeTicks / ticks,
                metrics.tooCloseTicks / ticks,
                metrics.tooFarTicks / ticks,
                metrics.movementFallbackTicks,
                metrics.movementHeldTicks,
                metrics.routeThrashCount,
                metrics.movementFallbackTicks / ticks,
                actions.directShortcutCount(),
                actions.instantConsumeShortcutCount(),
                actions.sameTickActionViolations(),
                actions.interruptionCount(),
                actions.healCompletionCount(),
                actions.healCancelCount(),
                metrics.retreatTicks,
                metrics.retreatSuccessTicks,
                metrics.retreatFailureTicks,
                retreatRate
        );
    }

    public static void clearBot(UUID botId) {
        if (botId != null) METRICS.remove(botId);
    }

    private static Mutable mutable(Bot bot) {
        return METRICS.computeIfAbsent(bot.getUUID(), ignored -> new Mutable());
    }

    private static final class Mutable {
        private int ticks;
        private double damageDealt;
        private double damageTaken;
        private int desiredRangeTicks;
        private int tooCloseTicks;
        private int tooFarTicks;
        private int movementFallbackTicks;
        private int movementHeldTicks;
        private int routeThrashCount;
        private int retreatTicks;
        private int retreatSuccessTicks;
        private int retreatFailureTicks;
        private String lastRouteFamily = "";
    }
}
