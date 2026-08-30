package net.nuggetmc.tplus.compat.bukkit;

/** Small server-side view of the native world border used by navigation code. */
public final class WorldBorder {
    private final net.minecraft.world.level.border.WorldBorder border;

    public WorldBorder(net.minecraft.world.level.border.WorldBorder border) {
        this.border = border;
    }

    public Location getCenter() {
        return new Location(null, border.getCenterX(), 0.0, border.getCenterZ());
    }

    public double getSize() {
        return border.getSize();
    }

    public boolean isInside(Location location) {
        if (location == null) return false;
        double half = Math.max(0.0, getSize() * 0.5);
        return location.getX() >= border.getCenterX() - half
                && location.getX() <= border.getCenterX() + half
                && location.getZ() >= border.getCenterZ() - half
                && location.getZ() <= border.getCenterZ() + half;
    }
}
