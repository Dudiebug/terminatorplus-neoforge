package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.loadout.BotInventory;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;

/**
 * Cooldown-aware melee. Swings only when:
 * <ul>
 *   <li>attack-strength charge &gt;= 0.95 (full-damage + crit/sweep eligible), and</li>
 *   <li>the target isn't deep in an i-frame window that would swallow the hit, and</li>
 *   <li>the currently-held item is AIR or an actual melee weapon — otherwise a
 *       scanner play that left a cobweb / wind charge / pearl / crystal in hand
 *       last tick would make the bot "swing" that utility item, wasting the
 *       swing cooldown and producing melee-hit log lines with w=COBWEB / etc.</li>
 * </ul>
 *
 * <p>Only reached via {@link CombatDirector#tick}, which short-circuits for neural-network
 * training bots — training still uses the deterministic damage table.
 */
public final class MeleeBehavior {

    public static final double ATTACK_RANGE = 5.0;

    public int ticksFor(Bot bot, LivingEntity target, double distance) {
        if (distance > ATTACK_RANGE) {
            CombatDebugger.log(bot, "melee-oor", "dist=" + String.format("%.2f", distance));
            return 0;
        }
        ItemStack held = bot.getBotInventory().getSelected();
        if (!isMeleeOrEmpty(held)) {
            CombatDebugger.log(bot, "melee-skip", "reason=non-melee-held held=" + held.getType().name());
            return 0;
        }
        float charge = bot.getAttackStrengthScale(0.0f);
        boolean iframes = BotCombatTiming.targetHasIFrames(target);
        CombatDebugger.meleeTry(bot, charge, iframes, distance);
        BotCombatTiming.logSweepCheck(bot, target, distance);
        if (BotCombatTiming.shouldWaitForCritWindow(bot, target, distance)) {
            BotCombatTiming.logSweepSkipIfRelevant(bot, target, distance, "higherPriority", "critWindow");
            return 0;
        }
        if (!BotCombatTiming.canSwing(bot, target)) {
            return 0;
        }
        boolean debug = CombatDebugger.isOn(bot);
        double targetHpBefore = 0.0;
        boolean critPred = false;
        boolean sweepPred = false;
        int sweepVictimCount = 0;
        if (debug) {
            targetHpBefore = target.getHealth();
            critPred = BotCombatTiming.isCritWindow(bot);
            sweepPred = BotCombatTiming.predictsSweep(bot, target, distance);
            sweepVictimCount = BotCombatTiming.sweepVictimCount(bot, target);
        }
        bot.attack(target);
        if (debug) {
            logAttackSummary(bot, target, held, charge, targetHpBefore, critPred, sweepPred, sweepVictimCount);
        }
        CombatDebugger.meleeHit(bot, held.getType().name());
        return 0;
    }

    private static void logAttackSummary(
            Bot bot,
            LivingEntity target,
            ItemStack held,
            float chargeBefore,
            double targetHpBefore,
            boolean critPred,
            boolean sweepPred,
            int sweepVictimCount
    ) {
        if (!CombatDebugger.isOn(bot)) return;

        double targetHpAfter = Math.max(0.0, target.getHealth());

        CombatDebugger.log(bot, "attack-summary",
                "branch=melee"
                        + " weapon=" + held.getType().name()
                        + " chargeBefore=" + fmt3(chargeBefore)
                        + " critPred=" + critPred
                        + " sweepPred=" + sweepPred
                        + " sweepVictimCount=" + sweepVictimCount
                        + " targetHp=" + fmt2(targetHpBefore) + "->" + fmt2(targetHpAfter)
                        + " targetHpDelta=" + fmt2(targetHpBefore - targetHpAfter));
    }

    /** True only for an empty hand or a real melee weapon; utility items remain illegal. */
    public static boolean isMeleeOrEmpty(ItemStack stack) {
        return stack != null
                && (stack.getType() == Material.AIR || BotInventory.isMeleeWeapon(stack));
    }

    private static String fmt2(double value) {
        return String.format("%.2f", value);
    }

    private static String fmt3(double value) {
        return String.format("%.3f", value);
    }
}
