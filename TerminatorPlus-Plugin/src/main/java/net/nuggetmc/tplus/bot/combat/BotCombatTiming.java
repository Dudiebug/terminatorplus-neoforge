package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.entity.Entity;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

/**
 * Zero-allocation helpers that gate bot swings against:
 * <ol>
 *   <li>the attacker's vanilla attack-strength charge (so we never swing at 25% damage
 *       like the old 3-tick melee loop did), and</li>
 *   <li>the target's invulnerability window (the i-frame dead zone where extra hits
 *       are either ignored or reduced to a delta).</li>
 * </ol>
 *
 * <p>Both methods are pure field reads + arithmetic, safe to call every tick.
 *
 * <p>Constants follow vanilla:
 * <ul>
 *   <li>{@link #READY_CHARGE} = 0.95 — charge >= 0.95 produces full damage (0.9025+ mult)
 *       and passes vanilla's 0.848 crit/sweep/sprint-KB gate.</li>
 *   <li>{@link #SMASH_READY_CHARGE} = 0.848 — matches vanilla's crit threshold; the mace
 *       smash density bonus dominates base damage so we accept anything above this.</li>
 *   <li>{@link #IFRAME_BLOCK_THRESHOLD} = 10 — matches vanilla LivingEntity.hurt's
 *       {@code getNoDamageTicks() > 10} branch. Hits in the lower half of the window
 *       still land for full damage.</li>
 * </ul>
 */
public final class BotCombatTiming {

    public static final float READY_CHARGE = 0.95f;
    public static final float SMASH_READY_CHARGE = 0.848f;
    public static final int IFRAME_BLOCK_THRESHOLD = 10;
    /** Mace attack speed is 0.6, so vanilla needs ceil(20 / 0.6) ticks to fully recharge. */
    public static final int MACE_FULL_RECHARGE_TICKS = 34;
    private static final double CRIT_DESCENT_VELOCITY = -0.06;
    private static final double BOT_AIR_GRAVITY_PER_TICK = 0.08;
    private static final double BOT_TERMINAL_FALL_SPEED = -3.5;
    private static final double MACE_IMPACT_DESCENT_VELOCITY = -0.3;
    private static final double MACE_VERTICAL_IMPACT_RANGE = 3.5;
    private static final int MACE_AIRTIME_SEARCH_TICKS = 90;
    private static final double SWEEP_TARGET_RADIUS_XZ = 1.0;
    private static final double SWEEP_TARGET_RADIUS_Y = 0.25;
    private static final double SWEEP_MAX_HORIZONTAL_SPEED = 0.9;

    private BotCombatTiming() {}

    /** True when the bot is charged AND the target can take full damage right now. */
    public static boolean canSwing(Bot bot, LivingEntity target) {
        float charge = charge(bot);
        int iframes = target.getNoDamageTicks();
        if (!MeleeBehavior.isMeleeOrEmpty(bot.getBotInventory().getSelected())) {
            CombatDebugger.swingGate(bot, charge, READY_CHARGE, iframes, false, "held");
            return false;
        }
        if (charge < READY_CHARGE) {
            CombatDebugger.swingBlock(bot, "charge", charge);
            CombatDebugger.swingGate(bot, charge, READY_CHARGE, iframes, false, "charge");
            return false;
        }
        if (iframes > IFRAME_BLOCK_THRESHOLD) {
            CombatDebugger.swingBlock(bot, "iframes", iframes);
            CombatDebugger.swingGate(bot, charge, READY_CHARGE, iframes, false, "iframes");
            return false;
        }
        CombatDebugger.swingGate(bot, charge, READY_CHARGE, iframes, true, "ready");
        return true;
    }

    public static boolean shouldWaitForCritWindow(Bot bot, LivingEntity target, double distance) {
        // Neural networks may shape movement, but they should never suppress a legal swing.
        if (bot.hasNeuralNetwork()) return false;
        if (!bot.getBotInventory().isSelectedMeleeWeapon()) return false;
        if (targetHasIFrames(target)) return false;
        if (!chargeReady(bot)) return false;
        if (distance > MeleeBehavior.ATTACK_RANGE) return false;

        Vector velocity = bot.getVelocity();
        if (isCritWindow(bot)) return false;
        if (!bot.isBotOnGround() && velocity.getY() > CRIT_DESCENT_VELOCITY) {
            if (bot.isSprinting()) bot.setSprinting(false);
            CombatDebugger.log(bot, "melee-wait", "reason=crit-window vy=" + String.format("%.2f", velocity.getY()));
            return true;
        }
        if (bot.isBotOnGround() && distance <= 3.2) {
            if (bot.isSprinting()) bot.setSprinting(false);
            Vector jump = bot.getVelocity();
            jump.setY(0.42);
            bot.setVelocity(jump);
            CombatDebugger.log(bot, "melee-wait", "reason=crit-jump");
            return true;
        }
        return false;
    }

    public static boolean isCritWindow(Bot bot) {
        return !bot.isBotOnGround()
                && bot.getVelocity().getY() < CRIT_DESCENT_VELOCITY
                && bot.fallDistance > 0.0f
                && !bot.isSprinting();
    }

    /** Attack-strength charge scaled to [0, 1]; 1.0 means fully recharged. */
    public static boolean chargeReady(Bot bot) {
        return charge(bot) >= READY_CHARGE;
    }

    /**
     * More permissive gate for mace dive-impacts where the smash density bonus dominates.
     * Uses strict {@code >} to match vanilla's {@code attackStrengthScale > 0.848F} crit/sweep
     * gate in {@code Player.attack} — {@code >=} would fire one tick early at the boundary.
     */
    public static boolean smashChargeReady(Bot bot) {
        return bot.getAttackStrengthScale(0.0f) > SMASH_READY_CHARGE;
    }

    /**
     * Like {@link #canSwing(Bot, LivingEntity)} but bypasses the i-frame block.
     * Mace smashes scale damage with fall distance, so the landed {@code amount}
     * trivially exceeds any prior {@code lastHurt} and the {@code amount - lastHurt}
     * residual is still a meaningful hit inside the i-frame window.
     */
    public static boolean canSwingMaceSmash(Bot bot) {
        return charge(bot) > SMASH_READY_CHARGE;
    }

    /**
     * Ground-launch planning guard for mace smash branches. Selecting the mace
     * can reset attack charge, so this profiles the planned launch against the
     * bot's custom gravity instead of assuming the old vanilla-airtime comment.
     */
    public static boolean shouldPlanGroundMaceSmash(Bot bot, LivingEntity target, double launchVelocityY) {
        float startingCharge = selectedMace(bot) ? charge(bot) : 0.0f;
        return macePlanReady(bot, target, launchVelocityY, startingCharge);
    }

    /** Planning guard for opportunistic midair mace dives from the bot's current velocity. */
    public static boolean shouldPlanCurrentMaceDive(Bot bot, LivingEntity target) {
        float startingCharge = selectedMace(bot) ? charge(bot) : 0.0f;
        return macePlanReady(bot, target, bot.getVelocity().getY(), startingCharge);
    }

    /** Launch-time guard after the mace has been held through MACE_CHARGING. */
    public static boolean shouldLaunchMaceSmash(Bot bot, LivingEntity target, double launchVelocityY) {
        return macePlanReady(bot, target, launchVelocityY, charge(bot));
    }

    private static boolean macePlanReady(Bot bot, LivingEntity target, double initialVy, float startingCharge) {
        int airtime = estimateMaceImpactTicks(bot, target, initialVy);
        int recharge = ticksUntilMaceFullRecharge(startingCharge);
        boolean ready = airtime > 0 && airtime >= recharge;
        if (CombatDebugger.isOn(bot)) {
            CombatDebugger.log(bot, "mace-plan",
                    "airtime=" + airtime
                            + " recharge=" + recharge
                            + " ready=" + ready
                            + " vy=" + String.format("%.2f", initialVy));
        }
        return ready;
    }

    private static int ticksUntilMaceFullRecharge(float startingCharge) {
        float clamped = Math.max(0.0f, Math.min(1.0f, startingCharge));
        return (int) Math.ceil((1.0f - clamped) * MACE_FULL_RECHARGE_TICKS);
    }

    private static int estimateMaceImpactTicks(Bot bot, LivingEntity target, double initialVy) {
        double y = bot.getLocation().getY();
        double targetY = target.getLocation().getY();
        double vy = initialVy;

        for (int tick = 1; tick <= MACE_AIRTIME_SEARCH_TICKS; tick++) {
            y += vy;
            vy = Math.max(vy - BOT_AIR_GRAVITY_PER_TICK, BOT_TERMINAL_FALL_SPEED);
            if (vy < MACE_IMPACT_DESCENT_VELOCITY
                    && Math.abs(y - targetY) <= MACE_VERTICAL_IMPACT_RANGE) {
                return tick;
            }
        }
        return 0;
    }

    private static boolean selectedMace(Bot bot) {
        return bot.getBotInventory().getSelected().getType() == Material.MACE;
    }

    /** True if hitting the target now would be wasted on its i-frame window. */
    public static boolean targetHasIFrames(LivingEntity target) {
        return target.getNoDamageTicks() > IFRAME_BLOCK_THRESHOLD;
    }

    /** Raw vanilla attack-strength charge in [0, 1]. */
    public static float charge(Bot bot) {
        return bot.getAttackStrengthScale(0.0f);
    }

    /** Planning guard for vanilla special-hit branches (crit/sprint-kb windows). */
    public static boolean readyForVanillaSpecial(Bot bot) {
        return bot.getBotInventory().isSelectedMeleeWeapon() && charge(bot) > SMASH_READY_CHARGE;
    }

    public static boolean shouldPlanNormalMelee(Bot bot, LivingEntity target) {
        return MeleeBehavior.isMeleeOrEmpty(bot.getBotInventory().getSelected())
                && chargeReady(bot)
                && !targetHasIFrames(target);
    }

    public static boolean shouldPlanSprintReset(Bot bot, LivingEntity target) {
        return readyForVanillaSpecial(bot) && !targetHasIFrames(target);
    }

    public static void logSweepCheck(Bot bot, LivingEntity target, double distance) {
        if (!CombatDebugger.isOn(bot)) return;
        SweepDiagnostic diag = sweepDiagnostic(bot, target, distance, false);
        CombatDebugger.log(bot, "sweep-check", diag.details());
    }

    public static boolean predictsSweep(Bot bot, LivingEntity target, double distance) {
        return sweepDiagnostic(bot, target, distance, false).eligible();
    }

    public static boolean predictsSweepWithSword(Bot bot, LivingEntity target, double distance) {
        return sweepDiagnostic(bot, target, distance, true).eligible();
    }

    public static int sweepVictimCount(Bot bot, LivingEntity target) {
        return countSweepTargets(bot, target);
    }

    public static void logSweepSkipIfRelevant(Bot bot, LivingEntity target, double distance, String reason, String branch) {
        if (!CombatDebugger.isOn(bot)) return;
        if (bot.getBotInventory().findSword() < 0 || distance > MeleeBehavior.ATTACK_RANGE) return;
        SweepDiagnostic diag = sweepDiagnostic(bot, target, distance, false);
        CombatDebugger.log(bot, "sweep-skip",
                "reason=" + reason
                        + " branch=" + branch
                        + " charge=" + fmt3(diag.charge())
                        + " range=" + fmt2(distance)
                        + " sweepPred=" + diag.eligible()
                        + " sweepVictimCount=" + diag.targets());
    }

    private static SweepDiagnostic sweepDiagnostic(Bot bot, LivingEntity target, double distance, boolean assumeSwordHeld) {
        float charge = charge(bot);
        boolean range = distance <= MeleeBehavior.ATTACK_RANGE;
        boolean chargeReady = charge >= READY_CHARGE;
        boolean targetHittable = !targetHasIFrames(target);
        boolean geometry = sweepGeometryReady(bot, assumeSwordHeld);
        int targets = countSweepTargets(bot, target);

        String reason = "ready";
        if (!range) {
            reason = "range";
        } else if (!chargeReady) {
            reason = "charge";
        } else if (!targetHittable) {
            reason = "iframes";
        } else if (!geometry) {
            reason = "geometry";
        } else if (targets <= 0) {
            reason = "noTargets";
        }
        boolean eligible = reason.equals("ready");
        return new SweepDiagnostic(eligible, reason, charge, distance, targets);
    }

    private static boolean sweepGeometryReady(Bot bot, boolean assumeSwordHeld) {
        ItemStack held = bot.getBotInventory().getSelected();
        if (!assumeSwordHeld && !isSword(held)) return false;
        if (!bot.isBotOnGround()) return false;
        if (bot.isSprinting()) return false;
        if (isCritWindow(bot)) return false;
        Vector velocity = bot.getVelocity();
        double horizontalSpeed = Math.hypot(velocity.getX(), velocity.getZ());
        return horizontalSpeed < SWEEP_MAX_HORIZONTAL_SPEED;
    }

    private static int countSweepTargets(Bot bot, LivingEntity target) {
        int count = 0;
        Entity botEntity = bot.getBukkitEntity();
        for (Entity entity : target.getNearbyEntities(SWEEP_TARGET_RADIUS_XZ, SWEEP_TARGET_RADIUS_Y, SWEEP_TARGET_RADIUS_XZ)) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!living.isValid()) continue;
            if (living.getUniqueId().equals(target.getUniqueId())) continue;
            if (living.getUniqueId().equals(botEntity.getUniqueId())) continue;
            count++;
        }
        return count;
    }

    private static boolean isSword(ItemStack stack) {
        return stack != null && stack.getType().name().endsWith("_SWORD");
    }

    private static String fmt2(double value) {
        return String.format("%.2f", value);
    }

    private static String fmt3(double value) {
        return String.format("%.3f", value);
    }

    private record SweepDiagnostic(boolean eligible, String reason, float charge, double distance, int targets) {
        String details() {
            return "sweepPred=" + eligible
                    + " reason=" + reason
                    + " charge=" + fmt3(charge)
                    + " range=" + fmt2(distance)
                    + " sweepVictimCount=" + targets;
        }
    }
}
