package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.compat.bukkit.util.Vector;

/**
 * Cached plan for a wind-charge self-propulsion throw. Calculated once when the bot
 * decides to boost, then held for a short windup before the charge actually spawns —
 * the delay is what the player sees as the bot "building" the throw.
 *
 * <p>Fields are final. One instance lives on {@link net.nuggetmc.tplus.bot.Bot} at a
 * time; set to null after firing or when the situation changes (target moves out of
 * range, bot leaves the ground, combat state changes).
 */
public final class WindChargeMovePlan {

    /** Tick value at which the charge should be spawned. */
    public final int fireAtTick;

    /** Spawn offset relative to the bot's feet (where the charge entity appears). */
    public final Vector placementOffset;

    /** Initial velocity of the spawned wind charge. */
    public final Vector velocity;

    /** Which launch mode this plan produces. Used for animation/logging only. */
    public final Mode mode;

    public enum Mode {
        /** Charge below the bot — launches UP (for reaching a target on a ledge). */
        LAUNCH_UP,
        /** Charge above the bot — launches DOWN (for dropping onto a target below). */
        LAUNCH_DOWN,
        /** Charge behind the bot — launches forward toward the target on flat ground. */
        LAUNCH_FORWARD
    }

    public WindChargeMovePlan(int fireAtTick, Vector placementOffset, Vector velocity, Mode mode) {
        this.fireAtTick = fireAtTick;
        this.placementOffset = placementOffset;
        this.velocity = velocity;
        this.mode = mode;
    }
}
