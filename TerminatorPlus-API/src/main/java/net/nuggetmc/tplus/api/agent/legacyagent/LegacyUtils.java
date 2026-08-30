package net.nuggetmc.tplus.api.agent.legacyagent;

import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.Sound;
import net.nuggetmc.tplus.compat.bukkit.block.Block;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

public class LegacyUtils {

    public static boolean checkFreeSpace(Location a, Location b) {
        Vector v = b.toVector().subtract(a.toVector());

        int n = 32;
        double m = 1 / (double) n;

        double j = Math.floor(v.length() * n);
        v.multiply(m / v.length());

        net.nuggetmc.tplus.compat.bukkit.World world = a.getWorld();
        if (world == null) return false;

        for (int i = 0; i <= j; i++) {
            Block block = world.getBlockAt((a.toVector().add(v.clone().multiply(i))).toLocation(world));

            if (!LegacyMats.AIR.contains(block.getType())) {
                return false;
            }
        }

        return true;
    }

    public static Sound breakBlockSound(Block block) {
        return block.getBlockData().getSoundGroup().getBreakSound();
    }
}
