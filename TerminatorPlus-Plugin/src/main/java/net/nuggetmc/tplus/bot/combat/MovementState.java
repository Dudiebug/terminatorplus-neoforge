package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.compat.bukkit.util.Vector;

/**
 * MovementNetwork -> CombatDirector contract. Reports physical movement facts;
 * it never grants permission to attack or choose combat actions.
 */
public record MovementState(
        boolean isSprinting,
        boolean justJumped,
        boolean isFalling,
        boolean isRetreating,
        boolean isCircling,
        double approachSpeed,
        Vector currentFacing
) {
    public static final MovementState DEFAULT = new MovementState(
            false,
            false,
            false,
            false,
            false,
            0.0,
            new Vector(0, 0, 1)
    );

    public MovementState {
        approachSpeed = Double.isFinite(approachSpeed) ? Math.max(0.0, approachSpeed) : 0.0;
        currentFacing = safeFacing(currentFacing);
    }

    @Override
    public Vector currentFacing() {
        return currentFacing.clone();
    }

    private static Vector safeFacing(Vector value) {
        if (value == null || !Double.isFinite(value.getX())
                || !Double.isFinite(value.getY()) || !Double.isFinite(value.getZ())) {
            return new Vector(0, 0, 1);
        }
        Vector facing = value.clone();
        if (facing.lengthSquared() > 1.0e-9) {
            facing.normalize();
        } else {
            facing.setX(0).setY(0).setZ(1);
        }
        return facing;
    }
}
