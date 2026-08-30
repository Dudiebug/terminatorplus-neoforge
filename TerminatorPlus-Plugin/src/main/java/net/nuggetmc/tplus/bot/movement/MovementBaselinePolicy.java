package net.nuggetmc.tplus.bot.movement;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.combat.CombatActionCategory;
import net.nuggetmc.tplus.bot.combat.CombatIntent;
import net.nuggetmc.tplus.bot.combat.MovementBranchFamily;
import net.nuggetmc.tplus.bot.combat.MovementObjective;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;

/**
 * Deterministic movement floor. It returns movement preferences only; the
 * applier owns locomotion and CombatDirector owns every combat action.
 */
public final class MovementBaselinePolicy {

    public MovementOutput compute(Bot bot, LivingEntity target, MovementInput input) {
        CombatIntent intent = bot == null ? CombatIntent.DEFAULT : bot.getCombatIntent();
        boolean obstructed = input != null && input.valid() && input.obstructed();
        return fromIntent(intent, obstructed);
    }

    static MovementOutput fromIntent(CombatIntent intent, boolean obstructed) {
        CombatIntent safeIntent = intent == null ? CombatIntent.DEFAULT : intent;
        double urgency = clamp01(Math.max(0.25, safeIntent.rangeUrgency()));
        double rangeError = safeIntent.rangeErrorSigned();
        double estimatedDistance = Math.max(0.0, safeIntent.desiredRange() + rangeError);
        double minSafeRange = Math.max(0.0, safeIntent.minSafeRange());
        double maxUsefulRange = Math.max(minSafeRange + 0.25, safeIntent.maxUsefulRange());
        boolean tooClose = estimatedDistance < minSafeRange || rangeError < -0.45;
        boolean outsideMaxRange = estimatedDistance > maxUsefulRange;
        boolean dangerRetreat = safeIntent.botHealthFraction() < 0.35f && safeIntent.healthAdvantage() < -0.15;

        boolean maceIntent = isMaceIntent(safeIntent);
        if (maceIntent && dangerRetreat) {
            return retreat(Math.max(urgency, 0.75), true);
        }
        if (maceIntent) {
            return macePressure(rangeError, urgency, safeIntent.openSkyAboveBot(), tooClose, outsideMaxRange);
        }
        if (safeIntent.movementObjective() == MovementObjective.HOLD || safeIntent.wantsHoldPosition()) {
            return hold(urgency);
        }
        if (safetyRetreatRequired(safeIntent, tooClose, dangerRetreat)) {
            return retreat(Math.max(urgency, 0.75), true);
        }

        return switch (safeIntent.movementObjective()) {
            case HOLD -> hold(urgency);
            case RETREAT, DISENGAGE -> retreat(Math.max(urgency, 0.75), true);
            case EXPLOSIVE_ESCAPE -> retreat(Math.max(urgency, 0.9), true);
            case PEARL_DISENGAGE -> retreat(Math.max(urgency, 0.8), true);
            case APPROACH -> outsideMaxRange
                    ? approach(rangeError, Math.max(urgency, 0.65), true)
                    : pressure(rangeError, urgency, true);
            case ORBIT -> orbit(rangeError, urgency, tooClose, obstructed, outsideMaxRange);
            case SHIELD_PRESSURE -> shieldPressure(rangeError, Math.max(urgency, 0.65), outsideMaxRange);
            case VERTICAL_SETUP -> verticalSetup(rangeError, urgency, safeIntent.openSkyAboveBot(), outsideMaxRange);
            case RANGED_LOS -> rangedLos(rangeError, urgency, safeIntent.needsLineOfSight(), outsideMaxRange);
            case COBWEB_PRESSURE -> cobweb(rangeError, urgency, outsideMaxRange);
            case PEARL_ENGAGE -> approach(rangeError, Math.max(urgency, 0.8), true);
            case STUCK_RECOVERY -> stuckRecovery(urgency);
        };
    }

    private static MovementOutput hold(double urgency) {
        return new MovementOutput(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, urgency, 1.0);
    }

    private static MovementOutput approach(double rangeError, double urgency, boolean sprint) {
        double forward = rangeError > 0.25 ? 0.85 : 0.35;
        double strafe = rangeError > 1.0 ? 0.15 : 0.35;
        return new MovementOutput(forward, strafe, 0.0, sprint ? urgency : 0.35, 0.0, 0.0, urgency, 0.0);
    }

    private static MovementOutput pressure(double rangeError, double urgency, boolean sprint) {
        double forward = rangeError > 0.35 ? 0.55 : 0.25;
        return new MovementOutput(forward, 0.35, 0.0, sprint ? urgency : 0.35, 0.0, 0.0, urgency, 0.0);
    }

    private static MovementOutput retreat(double urgency, boolean strafe) {
        return new MovementOutput(-0.65, strafe ? 0.35 : 0.0, 0.0, 0.0, 0.9, 0.0, urgency, 0.0);
    }

    private static MovementOutput orbit(
            double rangeError,
            double urgency,
            boolean tooClose,
            boolean obstructed,
            boolean outsideMaxRange
    ) {
        double forward = tooClose ? -0.45 : outsideMaxRange ? 0.65 : rangeError > 0.65 ? 0.45 : 0.08;
        double retreat = tooClose ? 0.65 : 0.0;
        double strafe = obstructed ? 0.85 : 0.65;
        return new MovementOutput(forward, strafe, obstructed ? 0.35 : 0.0,
                outsideMaxRange || rangeError > 1.2 ? 0.6 : 0.25, retreat, obstructed ? 0.25 : 0.0, urgency, 0.0);
    }

    private static MovementOutput shieldPressure(double rangeError, double urgency, boolean outsideMaxRange) {
        double forward = outsideMaxRange || rangeError > 0.4 ? 0.65 : 0.35;
        return new MovementOutput(forward, 0.25, 0.0, 0.35, 0.0, 0.0, urgency, 0.0);
    }

    private static MovementOutput verticalSetup(double rangeError, double urgency, boolean openSky, boolean outsideMaxRange) {
        double forward = outsideMaxRange || rangeError > 0.5 ? 0.55 : 0.1;
        double jump = openSky && !outsideMaxRange ? 0.75 : 0.15;
        return new MovementOutput(forward, 0.25, jump, 0.45, 0.0, 0.0, urgency, 0.0);
    }

    private static MovementOutput macePressure(
            double rangeError,
            double urgency,
            boolean openSky,
            boolean tooClose,
            boolean outsideMaxRange
    ) {
        double forward = tooClose ? -0.25 : outsideMaxRange ? 0.75 : rangeError > 0.55 ? 0.45 : 0.12;
        double strafe = outsideMaxRange ? 0.25 : 0.75;
        double jump = openSky && !outsideMaxRange ? 0.65 : 0.1;
        double sprint = outsideMaxRange || rangeError > 0.9 ? 0.65 : 0.35;
        double retreat = tooClose ? 0.35 : 0.0;
        return new MovementOutput(forward, strafe, jump, sprint, retreat, 0.0, urgency, 0.0);
    }

    private static MovementOutput rangedLos(
            double rangeError,
            double urgency,
            boolean needsLineOfSight,
            boolean outsideMaxRange
    ) {
        double forward = outsideMaxRange || rangeError > 1.5 ? 0.65 : rangeError < -1.0 ? -0.55 : 0.05;
        double retreat = rangeError < -1.0 ? 0.65 : 0.0;
        return new MovementOutput(forward, needsLineOfSight ? 0.25 : 0.55, 0.0,
                outsideMaxRange || rangeError > 2.0 ? 0.65 : 0.25,
                retreat, 0.0, urgency, 0.0);
    }

    private static MovementOutput cobweb(double rangeError, double urgency, boolean outsideMaxRange) {
        if (rangeError < -0.25) return hold(urgency);
        return new MovementOutput(outsideMaxRange ? 0.65 : 0.45, 0.25, 0.0, 0.25, 0.0, 0.0, urgency, 0.0);
    }

    private static MovementOutput stuckRecovery(double urgency) {
        return new MovementOutput(0.25, 0.9, 0.8, 0.0, 0.0, 0.35, Math.max(urgency, 0.8), 0.0);
    }

    private static boolean safetyRetreatRequired(CombatIntent intent, boolean tooClose, boolean dangerRetreat) {
        if (!tooClose && !dangerRetreat) return false;
        return switch (intent.movementObjective()) {
            case RETREAT, DISENGAGE, EXPLOSIVE_ESCAPE, PEARL_DISENGAGE -> false;
            case COBWEB_PRESSURE -> dangerRetreat;
            default -> true;
        };
    }

    private static boolean isMaceIntent(CombatIntent intent) {
        if (intent.branchFamily() == MovementBranchFamily.MACE) return true;
        CombatActionCategory category = intent.actionCategory();
        return category == CombatActionCategory.MACE_CHARGE
                || category == CombatActionCategory.MACE_AIRBORNE
                || category == CombatActionCategory.MACE_SMASH
                || category == CombatActionCategory.AERIAL_DIVE;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
