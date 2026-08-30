package net.nuggetmc.tplus.bot.movement;

import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementBrainBank;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementNetwork;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.combat.BotActionState;
import net.nuggetmc.tplus.bot.combat.CombatDebugger;
import net.nuggetmc.tplus.bot.combat.CombatIntent;
import net.nuggetmc.tplus.bot.combat.MovementState;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.configuration.Configuration;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts movement-network preferences into bot movement. It never selects
 * weapons, starts combat branches, uses items, or calls attack methods.
 */
public final class MovementOutputApplier {

    private static final double WALK_SPEED = 0.32;
    private static final double SPRINT_SPEED = 0.42;
    private static final double JUMP_THRESHOLD = 0.65;
    private static final double SPRINT_THRESHOLD = 0.55;
    private static final double HOLD_THRESHOLD = 0.65;
    private static final Map<UUID, String> LAST_INVALID_SHAPE = new ConcurrentHashMap<>();

    private final MovementBrainRouter router = new MovementBrainRouter();
    private final MovementBaselinePolicy baselinePolicy = new MovementBaselinePolicy();
    private final MovementOutputMixer mixer = new MovementOutputMixer();
    private boolean enabled = true;

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ApplyResult tryApply(Bot bot, LivingEntity target, MovementNetwork network) {
        MovementBrainBank bank = MovementBrainBank.singleFallback(network,
                net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementBrainPersistence.TrainingMetadata.manual(),
                "",
                "single-network");
        return tryApply(bot, target, bank);
    }

    public ApplyResult tryApply(Bot bot, LivingEntity target, MovementBrainBank bank) {
        return tryApply(bot, target, bank, target == null ? null : target.getLocation(), false);
    }

    /**
     * Evaluate the movement brain against the real combat target while steering
     * its locomotion output toward a validated route waypoint.
     */
    public ApplyResult tryApply(Bot bot, LivingEntity target, MovementBrainBank bank, Location steeringTarget) {
        return tryApply(bot, target, bank, steeringTarget, true);
    }

    private ApplyResult tryApply(
            Bot bot,
            LivingEntity target,
            MovementBrainBank bank,
            Location steeringTarget,
            boolean routeLocked
    ) {
        if (bot == null || target == null || !target.isValid()) {
            return ApplyResult.fallback("missing-bot-or-target");
        }
        if (steeringTarget == null || steeringTarget.getWorld() != bot.getLocation().getWorld()) {
            return ApplyResult.fallback("missing-or-cross-world-steering-target");
        }
        if (!enabled || !configEnabled()) {
            writeObservedState(bot, target, false);
            return ApplyResult.fallback("disabled");
        }
        MovementInput input = MovementInput.from(bot, target, bot.getCombatIntent());
        MovementOutput baseline = baselinePolicy.compute(bot, target, input);
        MovementOutput nnOutput = null;
        boolean nnAvailable = false;
        String fallbackReason = "";

        if (!neuralEnabled()) {
            fallbackReason = "neural-disabled";
        } else if (bank == null) {
            fallbackReason = "missing-bank";
        } else {
            MovementBrainRouter.RouteResult route = router.route(bot, bank);
            MovementNetwork network = route.network();
            if (network == null) {
                fallbackReason = "missing-network:" + route.reason();
            } else if (!input.valid()) {
                fallbackReason = "invalid-input:" + input.fallbackReason();
            } else {
                MovementNetwork.Validation validation = network.validate(MovementInput.COUNT, MovementOutput.COUNT);
                if (!validation.valid()) {
                    reportInvalidShape(bot, validation.reason());
                    fallbackReason = "invalid-shape:" + validation.reason();
                } else {
                    nnOutput = MovementOutput.fromRaw(network.evaluate(input.values()));
                    nnAvailable = true;
                }
            }
        }

        CombatIntent intent = bot.getCombatIntent();
        MovementOutputMixer.MixResult mixed = mixer.mix(intent, baseline, nnOutput, nnAvailable);
        MovementOutput output = constrainForActiveAction(bot, mixed.output());
        logMovementTelemetry(bot, intent, baseline, nnOutput, output, mixed, nnAvailable, fallbackReason);
        if (intent.wantsHoldPosition() || (!routeLocked && output.holdPosition() >= HOLD_THRESHOLD)) {
            writeObservedState(bot, target, false);
            return ApplyResult.held(output);
        }

        applyMovement(bot, steeringTarget, output, routeLocked);
        writeObservedState(bot, target,
                !routeLocked && output.jumpDesire() >= JUMP_THRESHOLD && bot.isBotOnGround());
        return ApplyResult.applied(output);
    }

    private static void logMovementTelemetry(
            Bot bot,
            CombatIntent intent,
            MovementOutput baseline,
            MovementOutput nn,
            MovementOutput output,
            MovementOutputMixer.MixResult mixed,
            boolean nnAvailable,
            String fallbackReason
    ) {
        if (!CombatDebugger.isOn(bot)) return;
        CombatIntent safeIntent = intent == null ? CombatIntent.DEFAULT : intent;
        CombatDebugger.log(bot, "move-mix",
                "obj=" + safeIntent.movementObjective()
                        + " action=" + safeIntent.actionCategory()
                        + " desired=" + fmt(safeIntent.desiredRange())
                        + " min=" + fmt(safeIntent.minSafeRange())
                        + " max=" + fmt(safeIntent.maxUsefulRange())
                        + " err=" + fmt(safeIntent.rangeErrorSigned())
                        + " commitLeft=" + safeIntent.commitTicksRemaining()
                        + " mode=" + mixed.mode()
                        + " safety=" + mixed.safetyReason()
                        + " nn=" + nnAvailable
                        + " fallback=" + fallbackReason
                        + " baseline=" + shortOutput(baseline)
                        + " nnOut=" + shortOutput(nn)
                        + " final=" + shortOutput(output));
    }

    private static String shortOutput(MovementOutput output) {
        if (output == null) return "none";
        return "[f=" + fmt(output.forwardPressure())
                + ",s=" + fmt(output.strafePressure())
                + ",j=" + fmt(output.jumpDesire())
                + ",sp=" + fmt(output.sprintDesire())
                + ",r=" + fmt(output.retreatDesire())
                + ",u=" + fmt(output.urgency())
                + ",h=" + fmt(output.holdPosition()) + "]";
    }

    private static MovementOutput constrainForActiveAction(Bot bot, MovementOutput output) {
        if (bot == null || output == null || !bot.getActionController().active()) return output;
        BotActionState state = bot.getActionController().state();
        MovementOutput constrained = switch (state) {
            case USING_CONSUMABLE, DRINKING_POTION -> new MovementOutput(
                    output.forwardPressure() * 0.20,
                    output.strafePressure() * 0.25,
                    0.0,
                    0.0,
                    output.retreatDesire() * 0.35,
                    output.facingAdjustment() * 0.35,
                    Math.min(output.urgency(), 0.35),
                    Math.max(output.holdPosition(), 0.45));
            case THROWING_PROJECTILE, USING_PEARL, OPENING, PLACING_BLOCK, PILLARING,
                    MINING, FALL_CLUTCH -> new MovementOutput(
                    output.forwardPressure() * 0.45,
                    output.strafePressure() * 0.50,
                    0.0,
                    0.0,
                    output.retreatDesire() * 0.60,
                    output.facingAdjustment() * 0.60,
                    Math.min(output.urgency(), 0.55),
                    output.holdPosition());
            default -> output;
        };
        if (constrained != output) {
            CombatDebugger.log(bot, "move-action-constraint",
                    "state=" + state
                            + " src=" + bot.getActionController().source()
                            + " left=" + bot.getActionController().remainingTicks()
                            + " before=" + shortOutput(output)
                            + " after=" + shortOutput(constrained));
        }
        return constrained;
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }

    private static void applyMovement(
            Bot bot,
            Location steeringTarget,
            MovementOutput output,
            boolean routeLocked
    ) {
        Location botLoc = bot.getLocation();
        Vector toTarget = steeringTarget.toVector().subtract(botLoc.toVector()).setY(0);
        if (toTarget.lengthSquared() > 1.0e-9) {
            toTarget.normalize();
        } else {
            toTarget = botLoc.getDirection().setY(0);
            if (toTarget.lengthSquared() > 1.0e-9) {
                toTarget.normalize();
            } else {
                toTarget = new Vector(0, 0, 1);
            }
        }

        Vector desired;
        if (routeLocked) {
            desired = toTarget;
        } else {
            toTarget.rotateAroundY(output.facingAdjustment() * Math.PI / 3.0);
            Vector strafe = new Vector(-toTarget.getZ(), 0, toTarget.getX());
            double forward = output.retreatDesire() > 0.55
                    ? -Math.max(Math.abs(output.forwardPressure()), output.retreatDesire())
                    : output.forwardPressure();
            desired = toTarget.multiply(forward).add(strafe.multiply(output.strafePressure()));
        }
        if (desired.lengthSquared() > 1.0) desired.normalize();

        boolean sprinting = shouldSprint(routeLocked, output);
        bot.setSprinting(sprinting);
        double speed = movementSpeed(routeLocked, sprinting, output.urgency());
        desired.multiply(speed);

        if (!routeLocked && output.jumpDesire() >= JUMP_THRESHOLD && bot.isBotOnGround()) {
            bot.jump(new Vector(desired.getX(), 0.42, desired.getZ()));
        } else if (routeLocked) {
            bot.walkRoute(desired);
        } else {
            bot.walk(desired);
        }
    }

    static boolean shouldSprint(boolean routeLocked, MovementOutput output) {
        return routeLocked || (output.sprintDesire() >= SPRINT_THRESHOLD
                && output.retreatDesire() < 0.55);
    }

    static double movementSpeed(boolean routeLocked, boolean sprinting, double urgency) {
        return routeLocked
                ? SPRINT_SPEED
                : (sprinting ? SPRINT_SPEED : WALK_SPEED) * Math.max(0.25, urgency);
    }

    private static void writeObservedState(Bot bot, LivingEntity target, boolean justJumped) {
        Vector velocity = bot.getVelocity();
        Vector horizontal = velocity.clone().setY(0);
        Vector toTarget = target.getLocation().toVector().subtract(bot.getLocation().toVector()).setY(0);
        double approachSpeed = 0.0;
        boolean retreating = false;
        boolean circling = false;

        if (horizontal.lengthSquared() > 1.0e-9 && toTarget.lengthSquared() > 1.0e-9) {
            Vector direction = toTarget.clone().normalize();
            double forwardSpeed = horizontal.dot(direction);
            double lateralSpeed = horizontal.getX() * direction.getZ() - horizontal.getZ() * direction.getX();
            approachSpeed = Math.max(0.0, forwardSpeed);
            retreating = forwardSpeed < -0.03;
            circling = Math.abs(lateralSpeed) > Math.abs(forwardSpeed) && Math.abs(lateralSpeed) > 0.03;
        }

        bot.setMovementState(new MovementState(
                bot.isSprinting(),
                justJumped || bot.getMovementState().justJumped(),
                bot.isFalling(),
                retreating,
                circling,
                approachSpeed,
                bot.getLocation().getDirection()
        ));
    }

    private static void reportInvalidShape(Bot bot, String reason) {
        String previous = LAST_INVALID_SHAPE.put(bot.getUUID(), reason);
        if (reason.equals(previous) && bot.getAliveTicks() % 100 != 0) return;
        CombatDebugger.log(bot, "net-invalid", "reason=" + reason);
    }

    public static void clearBot(UUID botId) {
        if (botId != null) {
            LAST_INVALID_SHAPE.remove(botId);
        }
    }

    public static void clearAll() {
        LAST_INVALID_SHAPE.clear();
    }

    private static boolean configEnabled() {
        TerminatorPlus plugin = TerminatorPlus.getInstance();
        if (plugin == null) return true;
        if (plugin.getConfig().contains("ai.movement.bank.enabled")) {
            return plugin.getConfig().getBoolean("ai.movement.bank.enabled");
        }
        return plugin.getConfig().getBoolean("ai.movement.enabled", true);
    }

    private static boolean neuralEnabled() {
        TerminatorPlus plugin = TerminatorPlus.getInstance();
        return plugin != null && neuralEnabled(plugin.getConfig());
    }

    static boolean neuralEnabled(Configuration config) {
        return config != null && config.getBoolean("ai.movement.neural-enabled", false);
    }

    public record ApplyResult(boolean applied, boolean fallback, boolean held, String reason, MovementOutput output) {
        public static ApplyResult applied(MovementOutput output) {
            return new ApplyResult(true, false, false, "applied", output);
        }

        public static ApplyResult held(MovementOutput output) {
            return new ApplyResult(false, false, true, "hold-position", output);
        }

        public static ApplyResult fallback(String reason) {
            return new ApplyResult(false, true, false, reason, MovementOutput.ZERO);
        }
    }
}
