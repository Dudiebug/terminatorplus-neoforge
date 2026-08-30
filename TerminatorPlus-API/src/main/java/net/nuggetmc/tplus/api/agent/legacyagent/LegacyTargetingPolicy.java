package net.nuggetmc.tplus.api.agent.legacyagent;

import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;

/**
 * Compatibility targeting policy. It preserves LegacyAgent's current target
 * goal, region weighting, bot-list filtering, and locate-target event behavior.
 */
final class LegacyTargetingPolicy implements TargetingPolicy {

    private final LegacyAgent legacy;

    LegacyTargetingPolicy(LegacyAgent legacy) {
        this.legacy = legacy;
    }

    @Override
    public LivingEntity selectTarget(Terminator bot, Location botLocation) {
        return legacy.locateTarget(bot, botLocation);
    }
}
