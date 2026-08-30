package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.loadout.BotInventory;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.Sound;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.nuggetmc.tplus.compat.bukkit.entity.WindCharge;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

/**
 * Jump-smash behavior for the 1.21 mace. The bot holds the mace long enough for
 * the vanilla attack-strength clock to recharge, then launches and tracks the
 * target while airborne so the impact can use real mace fall-damage scaling.
 */
public final class MaceBehavior {

    public static final String COOLDOWN_KEY = "mace";
    private static final double MIN_DISTANCE = 0.5;
    private static final double MAX_DISTANCE = 6.0;
    private static final double ATTACK_RANGE = 3.5;
    private static final int JUMP_COOLDOWN = 55;
    private static final int PRE_JUMP_MIN_HOLD_TICKS = 10;
    private static final int PRE_JUMP_TIMEOUT_TICKS = 45;
    private static final int PRE_JUMP_AIR_GRACE_TICKS = 18;
    private static final int HEIGHT_LOG_TICKS = 10;
    private static final int AIRBORNE_GROUND_CLIP_TOLERANCE_TICKS = 2;
    private static final int AIRBORNE_MIN_TICKS_BEFORE_GROUND_RESET = 6;

    static final double LAUNCH_Y = 2.0;
    private static final double JUMP_XZ = 0.25;
    private static final double AIRBORNE_TRACK_DESCENT_VELOCITY = -0.2;
    private static final double AIRBORNE_FAST_DESCENT_VELOCITY = -0.6;
    private static final double AIRBORNE_FAST_CORRECTION = 0.20;
    private static final double AIRBORNE_SLOW_CORRECTION = 0.10;
    private static final double AIRBORNE_MAX_CLOSING_SPEED = 0.30;
    private static final double AIRBORNE_CLOSING_GAIN = 0.18;
    private static final double AIRBORNE_DAMPING_DISTANCE = 1.25;
    private static final double AIRBORNE_DEADZONE = 0.35;

    public int ticksFor(Bot bot, LivingEntity target, double distance) {
        CombatState state = bot.getCombatState();
        Location targetLoc = target.getLocation();
        bot.faceLocation(targetLoc);

        if (distance < MIN_DISTANCE && state.getPhase() != CombatState.Phase.AIRBORNE) {
            state.reset();
            return 0;
        }
        if (distance > MAX_DISTANCE && state.getPhase() != CombatState.Phase.AIRBORNE) {
            state.reset();
            return 0;
        }

        Vector botPos = bot.getLocation().toVector();
        Vector toTarget = targetLoc.toVector().subtract(botPos);

        switch (state.getPhase()) {
            case CHARGING:
            case IDLE:
            case RELEASE: {
                if (targetBlocking(target)) {
                    CombatDebugger.log(bot, "mace-skip",
                            "reason=target-blocking held=" + heldType(bot));
                    state.reset();
                    return 0;
                }
                if (!bot.getBotCooldowns().ready(COOLDOWN_KEY, bot.getAliveTicks())) {
                    int left = bot.getBotCooldowns().remaining(COOLDOWN_KEY, bot.getAliveTicks());
                    CombatDebugger.maceCd(bot, left);
                    if (distance <= ATTACK_RANGE && BotCombatTiming.canSwing(bot, target)) {
                        doAttack(bot, target);
                    }
                    return 0;
                }
                if (!bot.isBotOnGround()) {
                    if (tryCommitCurrentDive(bot, target, state, "idle-airborne", distance, bot.getVelocity())) {
                        return 0;
                    }
                    CombatDebugger.log(bot, "mace-charge-airborne-wait",
                            "src=idle vy=" + fmt(bot.getVelocity().getY())
                                    + " charge=" + fmt(bot.getAttackStrengthScale(0.0f))
                                    + " held=" + heldType(bot));
                    CombatDebugger.macePhase(bot, state.getPhase(), CombatState.Phase.MACE_CHARGING);
                    state.setPhase(CombatState.Phase.MACE_CHARGING);
                    state.setPhaseStartY(bot.getLocation().getY());
                    return 0;
                }

                CombatDebugger.log(bot, "mace-charge-start",
                        "minHold=" + PRE_JUMP_MIN_HOLD_TICKS
                                + " charge=" + fmt(bot.getAttackStrengthScale(0.0f))
                                + " held=" + heldType(bot));
                CombatDebugger.macePhase(bot, state.getPhase(), CombatState.Phase.MACE_CHARGING);
                state.setPhase(CombatState.Phase.MACE_CHARGING);
                state.setPhaseStartY(bot.getLocation().getY());
                return 0;
            }
            case MACE_CHARGING: {
                int chargeTicks = state.tickPhase();
                float charge = bot.getAttackStrengthScale(0.0f);
                boolean ready = BotCombatTiming.smashChargeReady(bot);
                boolean planReady = BotCombatTiming.shouldLaunchMaceSmash(bot, target, LAUNCH_Y);
                CombatDebugger.log(bot, "mace-charge",
                        "ticks=" + chargeTicks
                                + " charge=" + fmt(charge)
                                + " ready=" + ready
                                + " airReady=" + planReady
                                + " held=" + heldType(bot)
                                + " ground=" + bot.isBotOnGround());

                if (targetBlocking(target)) {
                    CombatDebugger.log(bot, "mace-reset",
                            "reason=target-blocking held=" + heldType(bot));
                    CombatDebugger.macePhase(bot, state.getPhase(), CombatState.Phase.IDLE);
                    state.reset();
                    return 0;
                }
                if (!bot.isBotOnGround()) {
                    if (tryCommitCurrentDive(bot, target, state, "charge-airborne", distance, bot.getVelocity())) {
                        return 0;
                    }

                    int airTicks = state.tickMacePrejumpAirTicks();
                    if (airTicks <= PRE_JUMP_AIR_GRACE_TICKS) {
                        CombatDebugger.log(bot, "mace-charge-airborne-wait",
                                "grace=" + (PRE_JUMP_AIR_GRACE_TICKS - airTicks + 1)
                                        + " ticks=" + airTicks
                                        + " charge=" + fmt(charge)
                                        + " airReady=" + planReady
                                        + " held=" + heldType(bot));
                        return 0;
                    }
                    CombatDebugger.log(bot, "mace-reset",
                            "reason=prejump-airborne-timeout held=" + heldType(bot));
                    CombatDebugger.macePhase(bot, state.getPhase(), CombatState.Phase.IDLE);
                    state.reset();
                    return 0;
                }
                state.clearMacePrejumpAirTicks();
                if (chargeTicks >= PRE_JUMP_TIMEOUT_TICKS && (!ready || !planReady)) {
                    CombatDebugger.log(bot, "mace-reset",
                            "reason=prejump-timeout charge=" + fmt(charge)
                                    + " airReady=" + planReady
                                    + " held=" + heldType(bot));
                    CombatDebugger.macePhase(bot, state.getPhase(), CombatState.Phase.IDLE);
                    state.reset();
                    return 0;
                }
                if (chargeTicks < PRE_JUMP_MIN_HOLD_TICKS || !ready || !planReady) {
                    CombatDebugger.log(bot, "mace-charge-moving",
                            "ticks=" + chargeTicks
                                    + " charge=" + fmt(charge)
                                    + " ready=" + ready
                                    + " airReady=" + planReady
                                    + " held=" + heldType(bot));
                    return 0;
                }

                launch(bot, state, toTarget);
                return 0;
            }
            case AIRBORNE: {
                int airborneTicks = state.tickPhase();
                Vector vel = bot.getVelocity();
                logAirborneHeight(bot, state, airborneTicks, vel, distance);

                if (shouldResetAirborneForGround(bot, state, airborneTicks, vel, distance)) {
                    CombatDebugger.macePhase(bot, state.getPhase(), CombatState.Phase.IDLE);
                    state.reset();
                    return 0;
                }

                if (!bot.isBotOnGround()) {
                    trackAirborne(bot, toTarget, vel);
                    vel = bot.getVelocity();
                }

                if (distance <= ATTACK_RANGE && !bot.isBotOnGround() && vel.getY() < -0.3) {
                    boolean iframes = BotCombatTiming.targetHasIFrames(target);
                    CombatDebugger.maceSmash(bot, vel.getY(), iframes, bot.isBotOnGround());
                    if (!BotCombatTiming.canSwingMaceSmash(bot)) {
                        logImpactMiss(bot, "charge", distance, vel);
                        CombatDebugger.log(bot, "mace-smash-wait",
                                "reason=charge charge=" + fmt(bot.getAttackStrengthScale(0.0f)));
                        return 0;
                    }
                    if (!iframes) {
                        doAttack(bot, target);
                    } else {
                        logImpactMiss(bot, "iframes", distance, vel);
                    }
                    CombatDebugger.macePhase(bot, state.getPhase(), CombatState.Phase.IDLE);
                    state.reset();
                    return 0;
                }

                if (airborneTicks > 80) {
                    String reason = bot.isBotOnGround()
                            ? "grounded"
                            : distance > ATTACK_RANGE
                            ? "range"
                            : vel.getY() >= -0.3
                            ? "vy"
                            : "timeout";
                    logImpactMiss(bot, reason, distance, vel);
                    CombatDebugger.log(bot, "mace-reset", "reason=airborne-timeout");
                    CombatDebugger.macePhase(bot, state.getPhase(), CombatState.Phase.IDLE);
                    state.reset();
                }
                return 0;
            }
            default:
                return 0;
        }
    }

    private boolean tryCommitCurrentDive(
            Bot bot,
            LivingEntity target,
            CombatState state,
            String source,
            double distance,
            Vector velocity
    ) {
        if (!BotCombatTiming.shouldPlanCurrentMaceDive(bot, target)) {
            return false;
        }
        CombatDebugger.log(bot, "mace-aerial-commit",
                "src=" + source
                        + " distance=" + fmt(distance)
                        + " vy=" + fmt(velocity.getY())
                        + " charge=" + fmt(bot.getAttackStrengthScale(0.0f))
                        + " held=" + heldType(bot));
        CombatDebugger.macePhase(bot, state.getPhase(), CombatState.Phase.AIRBORNE);
        state.setPhase(CombatState.Phase.AIRBORNE);
        state.setPhaseStartY(bot.getLocation().getY());
        state.clearMacePrejumpAirTicks();
        return true;
    }

    private void launch(Bot bot, CombatState state, Vector toTarget) {
        Vector horiz = toTarget.clone();
        horiz.setY(0);
        if (horiz.lengthSquared() > 1.0e-6) {
            horiz.normalize().multiply(JUMP_XZ);
        } else {
            horiz.setX(0).setZ(0);
        }

        Vector launch = new Vector(horiz.getX(), LAUNCH_Y, horiz.getZ());
        double startY = bot.getLocation().getY();

        throwWindChargeAtFeet(bot);
        bot.setVelocity(launch);

        CombatDebugger.log(bot, "mace-jump",
                "src=behavior vel=" + fmtVec(launch)
                        + " y0=" + fmt(startY)
                        + " charge=" + fmt(bot.getAttackStrengthScale(0.0f))
                        + " held=" + heldType(bot));
        bot.getBotCooldowns().set(COOLDOWN_KEY, JUMP_COOLDOWN, bot.getAliveTicks());
        bot.getBotCooldowns().set(WindChargeBehavior.COOLDOWN_KEY, 55, bot.getAliveTicks());
        CombatDebugger.macePhase(bot, state.getPhase(), CombatState.Phase.AIRBORNE);
        state.setPhase(CombatState.Phase.AIRBORNE);
        state.setPhaseStartY(startY);
        state.clearMaceAirborneGroundTicks();
        bot.getLocation().getWorld().playSound(bot.getLocation(), Sound.ENTITY_PLAYER_BIG_FALL, 0.3f, 1.6f);
    }

    private static boolean shouldResetAirborneForGround(Bot bot, CombatState state, int airborneTicks, Vector vel, double distance) {
        if (!bot.isBotOnGround()) {
            state.clearMaceAirborneGroundTicks();
            return false;
        }

        int groundTicks = state.tickMaceAirborneGroundTicks();
        if (airborneTicks <= AIRBORNE_MIN_TICKS_BEFORE_GROUND_RESET
                || groundTicks <= AIRBORNE_GROUND_CLIP_TOLERANCE_TICKS) {
            CombatDebugger.log(bot, "mace-air",
                    "reset=groundClip grace=" + Math.max(0, AIRBORNE_GROUND_CLIP_TOLERANCE_TICKS - groundTicks + 1)
                            + " airTick=" + airborneTicks);
            return false;
        }

        logImpactMiss(bot, "grounded", distance, vel);
        CombatDebugger.log(bot, "mace-reset",
                "reason=grounded airTick=" + airborneTicks + " groundTicks=" + groundTicks);
        return true;
    }

    private static void logImpactMiss(Bot bot, String reason, double distance, Vector vel) {
        CombatDebugger.log(bot, "mace-impact-miss",
                "reason=" + reason
                        + " dist=" + fmt(distance)
                        + " vy=" + fmt(vel.getY())
                        + " ground=" + bot.isBotOnGround()
                        + " charge=" + fmt(bot.getAttackStrengthScale(0.0f)));
    }

    private static void trackAirborne(Bot bot, Vector toTarget, Vector vel) {
        if (vel.getY() >= AIRBORNE_TRACK_DESCENT_VELOCITY) return;

        Vector horiz = toTarget.clone();
        horiz.setY(0);
        double distance = horiz.length();
        if (distance <= AIRBORNE_DEADZONE) {
            dampHorizontalOvershoot(bot, vel, horiz, distance);
            return;
        }

        Vector dir = horiz.multiply(1.0 / distance);
        double currentClosing = horizontalClosingSpeed(vel, dir);
        double desiredClosing = desiredClosingSpeed(distance);
        dampHorizontalOvershoot(bot, vel, dir, distance);

        vel = bot.getVelocity();
        currentClosing = horizontalClosingSpeed(vel, dir);
        double correction = desiredClosing - currentClosing;
        if (correction <= 0.0) return;

        double correctionCap = vel.getY() < AIRBORNE_FAST_DESCENT_VELOCITY
                ? AIRBORNE_FAST_CORRECTION
                : AIRBORNE_SLOW_CORRECTION;
        double applied = Math.min(correction, correctionCap);
        if (applied <= 1.0e-4) return;

        if (CombatDebugger.isOn(bot) && applied + 1.0e-4 < correctionCap) {
            CombatDebugger.log(bot, "mace-air",
                    "clamp dx=" + fmt(distance)
                            + " before=" + fmt(currentClosing)
                            + " after=" + fmt(currentClosing + applied));
        }
        bot.walk(dir.multiply(applied));
    }

    private static void dampHorizontalOvershoot(Bot bot, Vector vel, Vector towardTarget, double distance) {
        if (distance > AIRBORNE_DAMPING_DISTANCE) return;
        if (towardTarget.lengthSquared() <= 1.0e-6) return;

        Vector dir = towardTarget.clone().setY(0).normalize();
        double currentClosing = horizontalClosingSpeed(vel, dir);
        double desiredClosing = desiredClosingSpeed(distance);
        if (currentClosing <= desiredClosing) return;

        double excess = currentClosing - desiredClosing;
        Vector next = vel.clone();
        next.setX(next.getX() - dir.getX() * excess);
        next.setZ(next.getZ() - dir.getZ() * excess);
        bot.setVelocity(next);
        CombatDebugger.log(bot, "mace-air",
                "clamp dx=" + fmt(distance)
                        + " before=" + fmt(currentClosing)
                        + " after=" + fmt(desiredClosing));
    }

    private static double desiredClosingSpeed(double distance) {
        return Math.min(AIRBORNE_MAX_CLOSING_SPEED, distance * AIRBORNE_CLOSING_GAIN);
    }

    private static double horizontalClosingSpeed(Vector velocity, Vector towardTarget) {
        return velocity.getX() * towardTarget.getX() + velocity.getZ() * towardTarget.getZ();
    }

    private static void logAirborneHeight(Bot bot, CombatState state, int airborneTicks, Vector vel, double distance) {
        if (!CombatDebugger.isOn(bot) || airborneTicks > HEIGHT_LOG_TICKS) return;
        double y = bot.getLocation().getY();
        double startY = state.getPhaseStartY();
        double height = Double.isNaN(startY) ? 0.0 : y - startY;
        CombatDebugger.log(bot, "mace-air",
                "airTick=" + airborneTicks
                        + " y=" + fmt(y)
                        + " height=" + fmt(height)
                        + " vy=" + fmt(vel.getY())
                        + " dist=" + fmt(distance)
                        + " charge=" + fmt(bot.getAttackStrengthScale(0.0f))
                        + " held=" + heldType(bot)
                        + " ground=" + bot.isBotOnGround()
                        + " fall=" + fmt(bot.fallDistance));
    }

    private void doAttack(Bot bot, LivingEntity target) {
        boolean critPred = BotCombatTiming.isCritWindow(bot);
        float charge = bot.getAttackStrengthScale(0.0f);
        double before = target.getHealth();
        boolean targetBlocking = targetBlocking(target);
        if (CombatDebugger.isOn(bot)) {
            CombatDebugger.log(bot, "mace-attack",
                    "critPred=" + critPred
                            + " charge=" + fmt(charge)
                            + " fall=" + fmt(bot.fallDistance)
                            + " vy=" + fmt(bot.getVelocity().getY())
                            + " targetBlocking=" + targetBlocking
                            + " targetHp=" + fmt(before)
                            + " held=" + heldType(bot));
        }

        bot.punch();
        if (bot.getBukkitEntity() instanceof Player attacker) {
            attacker.attack(target);
        } else {
            bot.attack(target);
        }

        if (CombatDebugger.isOn(bot)) {
            double after = Math.max(0.0, target.getHealth());
            CombatDebugger.log(bot, "mace-damage",
                    "critPred=" + critPred
                            + " targetHp=" + fmt(before) + "->" + fmt(after)
                            + " targetHpDelta=" + fmt(before - after)
                            + " targetBlocking=" + targetBlocking);
        }
    }

    private static void throwWindChargeAtFeet(Bot bot) {
        BotInventory inv = bot.getBotInventory();
        if (!inv.hasWindCharge()) {
            return;
        }

        Location botLoc = bot.getLocation();
        Location spawn = botLoc.clone().add(0, bot.getBukkitEntity().getEyeHeight() - 0.1, 0);
        Vector downward = new Vector(0, -1.5, 0);

        spawn.getWorld().spawn(spawn, WindCharge.class, w -> {
            w.setShooter(bot.getBukkitEntity());
            w.setVelocity(downward);
        });
        botLoc.getWorld().playSound(botLoc, Sound.ENTITY_WIND_CHARGE_THROW, 1f, 1.1f);
        consumeOneWindCharge(inv);
    }

    private static void consumeOneWindCharge(BotInventory inv) {
        inv.decrementMaterialOrOffhand(Material.WIND_CHARGE);
    }

    private static boolean targetBlocking(LivingEntity target) {
        return target instanceof Player player && player.isBlocking();
    }

    private static String heldType(Bot bot) {
        ItemStack held = bot.getBukkitEntity().getInventory().getItemInMainHand();
        return held == null ? "AIR" : held.getType().name();
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }

    private static String fmtVec(Vector vec) {
        return fmt(vec.getX()) + "," + fmt(vec.getY()) + "," + fmt(vec.getZ());
    }
}
