package net.nuggetmc.tplus.bot.navigation;

import net.nuggetmc.tplus.bot.combat.CombatIntent;
import net.nuggetmc.tplus.bot.combat.MovementObjective;
import net.nuggetmc.tplus.compat.bukkit.Location;

import java.util.List;
import java.util.UUID;

/** Keeps per-bot route state and decides whether this tick moves, acts, or falls back. */
public final class MovementV2Controller {

    private static final int PROGRESS_WINDOW_TICKS = 12;
    private static final int FALLBACK_TICKS = 20;
    private static final double WAYPOINT_HORIZONTAL_SQ = 0.55 * 0.55;

    private final MovementV2Planner planner = new MovementV2Planner();
    private List<MovementV2Planner.Step> route = List.of();
    private int routeIndex;
    private UUID targetId;
    private MovementV2Planner.Pos plannedTarget;
    private int contextChunkX = Integer.MIN_VALUE;
    private int contextChunkZ = Integer.MIN_VALUE;
    private int fallbackUntilTick;
    private Location progressLocation;
    private int progressStartedTick;
    private int consecutiveStalls;
    private ActionOutcome actionOutcome = ActionOutcome.NONE;
    private String actionReason = "none";

    private long plans;
    private long replans;
    private long fallbacks;
    private long completedRoutes;
    private long actionFailures;
    private MovementV2Planner.Result lastPlan;
    private String lastReason = "not-run";

    public Decision decide(
            Location botLocation,
            UUID currentTargetId,
            Location targetLocation,
            CombatIntent intent,
            int tick,
            BukkitNavigationContext context,
            MovementV2Planner.Capabilities capabilities,
            MovementV2Planner.Policy policy
    ) {
        if (botLocation == null || targetLocation == null || context == null
                || botLocation.getWorld() == null || targetLocation.getWorld() == null
                || botLocation.getWorld() != targetLocation.getWorld()) {
            return fallback("missing-or-cross-world", tick);
        }
        if (actionOutcome == ActionOutcome.RUNNING
                && routeIndex < route.size()
                && route.get(routeIndex).kind().changesWorld()) {
            MovementV2Planner.Step running = route.get(routeIndex);
            if (!context.inBounds(running.actionPos())) {
                actionOutcome = ActionOutcome.NONE;
                return fallback("running-action-left-context", tick);
            }
            lastReason = "action-running:" + running.kind().name().toLowerCase();
            return Decision.action(running);
        }
        if (actionOutcome == ActionOutcome.FAILED) {
            actionFailures++;
            String reason = actionReason;
            actionOutcome = ActionOutcome.NONE;
            return fallback("action-failed:" + reason, tick);
        }
        if (actionOutcome == ActionOutcome.SUCCESS) {
            actionOutcome = ActionOutcome.NONE;
            route = List.of();
            routeIndex = 0;
            replans++;
            lastReason = "action-complete-replan";
        }
        if (!ownsPursuit(intent)) {
            clearRoute("intent-not-pursuit");
            return Decision.fallback("intent-not-pursuit");
        }
        if (tick < fallbackUntilTick) {
            return Decision.fallback("fallback-window");
        }

        MovementV2Planner.Pos botPos = pos(botLocation);
        MovementV2Planner.Pos targetPos = pos(targetLocation);
        MovementV2Planner.Pos localGoal = context.projectInside(targetPos);
        boolean frontierPlan = !localGoal.equals(targetPos);
        boolean contextChanged = context.centerChunkX() != contextChunkX
                || context.centerChunkZ() != contextChunkZ;
        boolean targetChanged = targetId == null || !targetId.equals(currentTargetId);
        boolean targetMoved = plannedTarget == null || plannedTarget.distance(targetPos) > 2.0;

        if (route.isEmpty() || routeIndex >= route.size() || contextChanged || targetChanged || targetMoved) {
            if (!route.isEmpty()) replans++;
            MovementV2Planner.Result result = planner.plan(
                    context,
                    botPos,
                    localGoal,
                    frontierPlan ? 0.75 : Math.max(0.75, desiredGoalRadius(intent)),
                    capabilities,
                    policy);
            plans++;
            lastPlan = result;
            lastReason = result.status().name().toLowerCase()
                    + (frontierPlan ? "-frontier" : "");
            route = result.steps();
            routeIndex = 0;
            targetId = currentTargetId;
            plannedTarget = targetPos;
            contextChunkX = context.centerChunkX();
            contextChunkZ = context.centerChunkZ();
            resetProgress(botLocation, tick);

            if (!result.usable()) {
                return fallback("plan-" + lastReason, tick);
            }
        }

        advanceReachedSteps(botLocation, tick);
        if (routeIndex >= route.size()) {
            completedRoutes++;
            route = List.of();
            lastReason = "route-finished-replan-next-tick";
            return Decision.hold(lastReason);
        }

        MovementV2Planner.Step step = route.get(routeIndex);
        if (!context.inBounds(step.to())) {
            route = List.of();
            replans++;
            return softFallback("step-left-context");
        }

        if (step.kind().changesWorld()) {
            lastReason = "action:" + step.kind().name().toLowerCase();
            return Decision.action(step);
        }

        if (!routeWindowStillTraversable(context)) {
            route = List.of();
            replans++;
            return softFallback("route-window-invalidated");
        }

        if (!madeProgress(botLocation, tick, step.to())) {
            consecutiveStalls++;
            route = List.of();
            replans++;
            if (consecutiveStalls >= 2) {
                return fallback("physical-stall", tick);
            }
            return softFallback("stalled-replan");
        }

        lastReason = "follow:" + step.kind().name().toLowerCase();
        return Decision.move(step);
    }

    public void recordActionResult(ActionOutcome outcome, String reason) {
        actionOutcome = outcome == null ? ActionOutcome.FAILED : outcome;
        actionReason = token(reason);
    }

    public void reset() {
        route = List.of();
        routeIndex = 0;
        targetId = null;
        plannedTarget = null;
        contextChunkX = Integer.MIN_VALUE;
        contextChunkZ = Integer.MIN_VALUE;
        fallbackUntilTick = 0;
        progressLocation = null;
        consecutiveStalls = 0;
        actionOutcome = ActionOutcome.NONE;
        actionReason = "none";
        lastReason = "reset";
    }

    public Status status() {
        return new Status(
                plans,
                replans,
                fallbacks,
                completedRoutes,
                actionFailures,
                route.size(),
                routeIndex,
                fallbackUntilTick,
                lastReason,
                lastPlan
        );
    }

    private static boolean ownsPursuit(CombatIntent intent) {
        CombatIntent safe = intent == null ? CombatIntent.DEFAULT : intent;
        if (safe.wantsHoldPosition() || safe.isCommitted()) return false;
        if (safe.rangeErrorSigned() <= 0.45) return false;
        MovementObjective objective = safe.movementObjective();
        return objective == MovementObjective.APPROACH
                || objective == MovementObjective.ORBIT
                || objective == MovementObjective.SHIELD_PRESSURE
                || objective == MovementObjective.VERTICAL_SETUP
                || objective == MovementObjective.RANGED_LOS
                || objective == MovementObjective.COBWEB_PRESSURE;
    }

    private static double desiredGoalRadius(CombatIntent intent) {
        CombatIntent safe = intent == null ? CombatIntent.DEFAULT : intent;
        return Math.max(safe.minSafeRange(), Math.min(safe.desiredRange(), safe.maxUsefulRange()));
    }

    private void advanceReachedSteps(Location botLocation, int tick) {
        while (routeIndex < route.size()) {
            MovementV2Planner.Step step = route.get(routeIndex);
            // Being pushed near an action destination does not mean the door
            // opened or the block was placed/broken. Only a SUCCESS outcome
            // may retire a world-changing step.
            if (step.kind().changesWorld()) break;
            MovementV2Planner.Pos to = step.to();
            double dx = botLocation.getX() - (to.x() + 0.5);
            double dz = botLocation.getZ() - (to.z() + 0.5);
            double dy = Math.abs(botLocation.getY() - to.y());
            if (dx * dx + dz * dz > WAYPOINT_HORIZONTAL_SQ || dy > 1.1) break;
            routeIndex++;
            consecutiveStalls = 0;
            resetProgress(botLocation, tick);
        }
    }

    private boolean madeProgress(Location current, int tick, MovementV2Planner.Pos waypoint) {
        if (progressLocation == null || progressLocation.getWorld() != current.getWorld()) {
            resetProgress(current, tick);
            return true;
        }
        if (tick - progressStartedTick < PROGRESS_WINDOW_TICKS) return true;
        double oldDistance = horizontalDistance(progressLocation, waypoint);
        double newDistance = horizontalDistance(current, waypoint);
        boolean moved = oldDistance - newDistance >= 0.12;
        resetProgress(current, tick);
        return moved;
    }

    private static double horizontalDistance(Location location, MovementV2Planner.Pos waypoint) {
        double dx = location.getX() - (waypoint.x() + 0.5);
        double dz = location.getZ() - (waypoint.z() + 0.5);
        return Math.hypot(dx, dz);
    }

    private void resetProgress(Location location, int tick) {
        progressLocation = location == null ? null : location.clone();
        progressStartedTick = tick;
    }

    private Decision fallback(String reason, int tick) {
        fallbacks++;
        fallbackUntilTick = Math.max(fallbackUntilTick, tick + FALLBACK_TICKS);
        clearRoute(reason);
        return Decision.fallback(reason);
    }

    private Decision softFallback(String reason) {
        fallbacks++;
        lastReason = token(reason);
        return Decision.fallback(reason);
    }

    private void clearRoute(String reason) {
        route = List.of();
        routeIndex = 0;
        lastReason = token(reason);
    }

    private boolean routeWindowStillTraversable(BukkitNavigationContext context) {
        int end = Math.min(route.size(), routeIndex + 3);
        for (int index = routeIndex; index < end; index++) {
            MovementV2Planner.Step candidate = route.get(index);
            if (candidate.kind().changesWorld()) {
                // Later steps can depend on a world change that has not
                // happened yet. Validate them after that action and replan.
                return index != routeIndex || context.inBounds(candidate.actionPos());
            }
            if (!stillTraversable(context, candidate)) return false;
        }
        return true;
    }

    private static boolean stillTraversable(BukkitNavigationContext context, MovementV2Planner.Step step) {
        return switch (step.kind()) {
            case PARKOUR -> context.bodyClear(step.to())
                    && context.standable(step.to().below())
                    && context.parkourClear(step.from(), step.to());
            case WALK, DIAGONAL, STEP_UP, DROP -> context.bodyClear(step.to())
                    && context.standable(step.to().below());
            default -> true;
        };
    }

    private static MovementV2Planner.Pos pos(Location location) {
        return new MovementV2Planner.Pos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static String token(String value) {
        if (value == null || value.isBlank()) return "none";
        return value.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    public enum ActionOutcome {
        NONE,
        RUNNING,
        SUCCESS,
        FAILED
    }

    public enum DecisionType {
        MOVE,
        ACTION,
        HOLD,
        FALLBACK
    }

    public record Decision(DecisionType type, MovementV2Planner.Step step, String reason) {
        static Decision move(MovementV2Planner.Step step) {
            return new Decision(DecisionType.MOVE, step, "move");
        }

        static Decision action(MovementV2Planner.Step step) {
            return new Decision(DecisionType.ACTION, step, "action");
        }

        static Decision hold(String reason) {
            return new Decision(DecisionType.HOLD, null, token(reason));
        }

        static Decision fallback(String reason) {
            return new Decision(DecisionType.FALLBACK, null, token(reason));
        }
    }

    public record Status(
            long plans,
            long replans,
            long fallbacks,
            long completedRoutes,
            long actionFailures,
            int routeLength,
            int routeIndex,
            int fallbackUntilTick,
            String lastReason,
            MovementV2Planner.Result lastPlan
    ) {
    }
}
