package net.nuggetmc.tplus.api.agent.legacyagent;

import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;

/**
 * Selects the movement implementation for a bot. The selected strategy may
 * produce locomotion only; CombatDirector remains combat authority.
 */
interface MovementControllerRouter {

    LegacyAgent.MovementMode mode(Terminator bot);

    void move(
            Terminator bot,
            LivingEntity target,
            Location botLocation,
            Location targetLocation,
            LegacyAgent.MovementMode mode,
            boolean allowMovement
    );
}
