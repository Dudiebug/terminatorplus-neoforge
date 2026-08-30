package net.nuggetmc.tplus.api.agent.legacyagent;

import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;

/**
 * Compatibility movement strategy. It delegates to LegacyAgent's current
 * legacy/full-NN/movement-controller move path unchanged.
 */
final class LegacyMovementStrategy implements MovementStrategy {

    private final LegacyAgent legacy;

    LegacyMovementStrategy(LegacyAgent legacy) {
        this.legacy = legacy;
    }

    @Override
    public void move(
            Terminator bot,
            LivingEntity target,
            Location botLocation,
            Location targetLocation,
            LegacyAgent.MovementMode mode,
            boolean allowMovement
    ) {
        legacy.move(bot, target, botLocation, targetLocation, mode, allowMovement);
    }
}
