package net.nuggetmc.tplus.api.agent.legacyagent;

import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;

/**
 * Compatibility movement router. It preserves the existing LEGACY,
 * FULL_REPLACEMENT_NN, and MOVEMENT_CONTROLLER_NN mode selection rules.
 */
final class LegacyMovementControllerRouter implements MovementControllerRouter {

    private final LegacyAgent legacy;
    private final MovementStrategy movementStrategy;

    LegacyMovementControllerRouter(LegacyAgent legacy) {
        this.legacy = legacy;
        this.movementStrategy = new LegacyMovementStrategy(legacy);
    }

    @Override
    public LegacyAgent.MovementMode mode(Terminator bot) {
        return legacy.movementMode(bot);
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
        movementStrategy.move(bot, target, botLocation, targetLocation, mode, allowMovement);
    }
}
