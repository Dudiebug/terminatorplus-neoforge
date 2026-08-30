package net.nuggetmc.tplus.api.utils;

import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.util.BoundingBox;
import net.nuggetmc.tplus.compat.bukkit.util.NumberConversions;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class BotUtils {

    public static final Set<Material> NO_FALL = new HashSet<>(Arrays.asList(
        Material.WATER,
        Material.LAVA,
        Material.TWISTING_VINES,
        Material.TWISTING_VINES_PLANT,
        Material.WEEPING_VINES,
        Material.WEEPING_VINES_PLANT,
        Material.SWEET_BERRY_BUSH,
        Material.POWDER_SNOW,
        Material.COBWEB,
        Material.VINE
    ));

    public static UUID randomSteveUUID() {
        while (true) {
            UUID random = UUID.randomUUID();
            long npcMostSignificantBits = random.getMostSignificantBits() & 0xffffffffffff0fffL | 0x2000L;
            UUID uuid = new UUID(npcMostSignificantBits, random.getLeastSignificantBits());
            if ((uuid.hashCode() & 1) == 0) {
                return uuid;
            }
        }
    }

    public static boolean overlaps(BoundingBox playerBox, BoundingBox blockBox) {
        return playerBox.overlaps(blockBox);
    }

    public static double getHorizSqDist(Location blockLoc, Location pLoc) {
        return NumberConversions.square(blockLoc.getX() + 0.5 - pLoc.getX()) + NumberConversions.square(blockLoc.getZ() + 0.5 - pLoc.getZ());
    }
}
