package net.nuggetmc.tplus.api.agent.legacyagent;

import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;

/**
 * Compatibility survival controller. It delegates to the same LegacyAgent and
 * LegacyBlockCheck methods that owned these behaviors before extraction.
 */
final class LegacySurvivalController implements SurvivalController {

    private final LegacyAgent legacy;

    LegacySurvivalController(LegacyAgent legacy) {
        this.legacy = legacy;
    }

    @Override
    public void beforeTarget(Terminator bot, Location botLocation) {
        if (bot.movementV2ActionActive()) return;
        legacy.blockCheck().tryPreMLG(bot, botLocation);
    }

    @Override
    public void onIdle(Terminator bot) {
        bot.cancelMovementV2Action("no-target");
        legacy.stopMining(bot);
        legacy.clearIdleTracking(bot);
    }

    @Override
    public void beforeMovement(Terminator bot, LivingEntity target) {
        if (bot.movementV2ActionActive()) return;
        legacy.blockCheck().clutch(bot, target);
        legacy.fallDamageCheck(bot);
        legacy.miscellaneousChecks(bot, target);
    }
}
