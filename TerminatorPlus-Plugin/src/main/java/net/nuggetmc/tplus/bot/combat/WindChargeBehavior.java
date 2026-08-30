package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.Sound;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.entity.WindCharge;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

/**
 * Wind charges are deliberately thrown to launch the bot in a calculated direction
 * (up over a ledge, down onto a target, or forward across a flat gap). This runs
 * only outside combat range, uses a short windup so the throw reads as a deliberate
 * build, and is paced by a long cooldown so the bot is not constantly blasting itself.
 */
public final class WindChargeBehavior {

    // Shared with deliberate offensive wind-charge plays.
    public static final String COOLDOWN_KEY = "windcharge";

    // -- Self-propulsion wind charge --
    public static final String BOOST_COOLDOWN_KEY = "windcharge_boost";
    /** Long cooldown — this is a deliberate strategic tool, not a constant boost. */
    private static final int BOOST_COOLDOWN = 120;
    /** Windup ticks between "decide to boost" and "spawn the charge" — the visible build. */
    private static final int BOOST_WINDUP_TICKS = 4;
    /** Below this, the bot is in combat range — let melee/mace/trident work without wind-charge interference. */
    private static final double BOOST_MIN_DISTANCE = 12.0;
    /** Above this, pearls take over (see {@link EnderPearlBehavior}). */
    private static final double BOOST_MAX_DISTANCE = 28.0;
    /** Vertical delta that triggers an up/down launch instead of a horizontal one. */
    private static final double VERTICAL_TRIGGER = 3.0;

    /**
     * Self-boost: plans a calculated wind-charge throw and fires it after a short windup.
     * Called every tick by {@link CombatDirector} but the vast majority of ticks no-op
     * quickly — the real throw only happens 10 times per minute or so.
     *
     * <p>Flow:
     * <ol>
     *   <li>If a plan is pending, check if the windup has elapsed. If so, spawn the
     *       charge and clear the plan. Otherwise do nothing (let it wind up).</li>
     *   <li>If no plan: guard on distance (12–28), on-ground, idle combat state, and
     *       cooldown. If all pass, compute an aim direction based on target geometry
     *       and record a plan that'll fire in {@link #BOOST_WINDUP_TICKS} ticks.</li>
     * </ol>
     */
    public void tickMovementBoost(Bot bot, LivingEntity target, double distance) {
        WindChargeMovePlan plan = bot.pendingWindChargePlan;
        if (plan != null) {
            // Abort the throw if the situation changed mid-windup: bot took off, got
            // knocked into a combat action (mace jump, trident charge), or similar.
            // Firing the wind charge now would disrupt that higher-priority action.
            boolean stillValid = bot.getCombatState().getPhase() == CombatState.Phase.IDLE
                    && bot.isBotOnGround();
            if (!stillValid) {
                CombatDebugger.log(bot, "wind-boost-cancel", "reason=state-changed");
                bot.pendingWindChargePlan = null;
                return;
            }
            if (bot.getAliveTicks() >= plan.fireAtTick) {
                CombatDebugger.log(bot, "wind-boost-fire", "mode=" + plan.mode.name());
                executePlan(bot, plan);
                bot.pendingWindChargePlan = null;
            }
            return;
        }

        if (distance < BOOST_MIN_DISTANCE || distance > BOOST_MAX_DISTANCE) return;
        if (!bot.isBotOnGround()) return;
        if (!bot.getBotInventory().hasWindCharge()) return;
        // Only boost when NOT mid-combat-action — don't interrupt a mace jump, trident charge, etc.
        if (bot.getCombatState().getPhase() != CombatState.Phase.IDLE) return;
        if (!bot.getBotCooldowns().ready(BOOST_COOLDOWN_KEY, bot.getAliveTicks())) return;

        Vector toTarget = target.getLocation().toVector().subtract(bot.getLocation().toVector());
        double dy = toTarget.getY();
        Vector horiz = toTarget.clone().setY(0);
        if (horiz.lengthSquared() < 0.01) return;
        horiz.normalize();

        Vector placement;
        Vector velocity;
        WindChargeMovePlan.Mode mode;
        if (dy >= VERTICAL_TRIGGER) {
            // Target is high above — place below bot so the explosion launches it UP.
            placement = new Vector(0, -0.8, 0);
            velocity = new Vector(0, -0.4, 0);
            mode = WindChargeMovePlan.Mode.LAUNCH_UP;
        } else if (dy <= -VERTICAL_TRIGGER) {
            // Target is far below — place above bot's head so the explosion launches it DOWN.
            placement = new Vector(0, 2.0, 0);
            velocity = new Vector(0, 0.4, 0);
            mode = WindChargeMovePlan.Mode.LAUNCH_DOWN;
        } else {
            // Roughly level — place behind bot so the explosion launches it FORWARD.
            placement = horiz.clone().multiply(-0.7);
            placement.setY(0.4);
            velocity = horiz.clone().multiply(-0.5);
            velocity.setY(-0.3);
            mode = WindChargeMovePlan.Mode.LAUNCH_FORWARD;
        }

        // Face the target now so the windup animation reads correctly.
        bot.faceLocation(target.getLocation());

        bot.pendingWindChargePlan = new WindChargeMovePlan(
                bot.getAliveTicks() + BOOST_WINDUP_TICKS,
                placement,
                velocity,
                mode
        );
        CombatDebugger.log(bot, "wind-boost-plan",
                "mode=" + mode.name() + " dist=" + String.format("%.2f", distance) + " dy=" + String.format("%.2f", dy));

        // Audible wind-up so players can hear/see the bot is building the throw.
        bot.getLocation().getWorld().playSound(bot.getLocation(),
                Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 2.0f);

    }

    private static void executePlan(Bot bot, WindChargeMovePlan plan) {
        if (!bot.getBotInventory().decrementMaterialOrOffhand(Material.WIND_CHARGE)) {
            CombatDebugger.log(bot, "wind-boost-cancel", "reason=no-wind-charge");
            return;
        }
        Location spawn = bot.getLocation().add(plan.placementOffset);
        Vector velocity = plan.velocity.clone();
        bot.punch();
        bot.getActionController().recordDirectShortcut(bot, BotActionState.USING_WIND_CHARGE,
                "direct-windcharge-boost-spawn", bot.getBotInventory().findMainInventory(Material.WIND_CHARGE));
        spawn.getWorld().spawn(spawn, WindCharge.class, w -> {
            w.setShooter(bot.getBukkitEntity());
            w.setVelocity(velocity);
        });
        spawn.getWorld().playSound(spawn, Sound.ENTITY_WIND_CHARGE_THROW, 1f, 1f);
        bot.getBotCooldowns().set(BOOST_COOLDOWN_KEY, BOOST_COOLDOWN, bot.getAliveTicks());
    }
}
