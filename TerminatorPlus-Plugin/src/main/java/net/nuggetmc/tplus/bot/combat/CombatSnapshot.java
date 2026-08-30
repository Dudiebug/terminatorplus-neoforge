package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.World;
import net.nuggetmc.tplus.compat.bukkit.block.Block;
import net.nuggetmc.tplus.compat.bukkit.entity.EnderPearl;
import net.nuggetmc.tplus.compat.bukkit.entity.Entity;
import net.nuggetmc.tplus.compat.bukkit.entity.HumanEntity;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

/**
 * Per-tick battlefield reading shared between {@link OpportunityScanner} and
 * the standard combat pipeline. One instance is reused per bot; {@link #update}
 * overwrites every field, so the snapshot is a cheap pass of observations
 * rather than a per-tick allocation.
 *
 * <p>All fields are {@code public} on purpose — the scanner reads them directly
 * and keeping them as plain fields avoids getter overhead at 500-bot scale.
 * Optional fields (the ones the scanner reflects into) are still populated
 * here; reflection is only a safety net for future additions.
 */
public final class CombatSnapshot {

    // --- Core fields (always populated) --------------------------------
    public double distance;
    public boolean botOnGround;
    public float botHpFraction;
    public float targetHpFraction;
    public boolean targetAirborne;
    public boolean targetRising;
    public boolean targetOverVoid;
    public boolean targetBlocking;
    public boolean targetNearWall;
    public boolean targetEating;
    public boolean openSkyAboveBot;

    // --- Optional fields (accessed via reflection by the scanner) -------
    public boolean targetSprintingAway;
    public boolean targetDrawingBow;
    public boolean targetDrinkingPotion;
    public boolean targetInWater;
    public boolean targetInCobweb;
    public boolean botInLavaArea;
    public boolean botOnFire;
    public boolean targetThrowingPearl;

    public void update(Bot bot, LivingEntity target) {
        Location botLoc = bot.getLocation();
        Location targetLoc = target.getLocation();
        World world = botLoc.getWorld();

        distance = botLoc.distance(targetLoc);
        botOnGround = bot.isBotOnGround();
        botHpFraction = bot.getBotHealth() / Math.max(1f, bot.getBotMaxHealth());

        double tMax = Math.max(1.0, target.getMaxHealth());
        targetHpFraction = (float) (target.getHealth() / tMax);

        targetAirborne = !isGroundedCheap(target);
        Vector tVel = target.getVelocity();
        targetRising = tVel.getY() > 0.08;
        targetOverVoid = computeTargetOverVoid(targetLoc);
        targetBlocking = target instanceof HumanEntity he && he.isBlocking();
        targetNearWall = hasAdjacentWall(targetLoc);
        targetEating = isEating(target);
        openSkyAboveBot = hasOpenSkyAbove(botLoc);

        // Optional / reflection-backed fields --------------------------
        targetSprintingAway = isSprintingAway(botLoc, targetLoc, tVel);
        targetDrawingBow = isUsingItem(target, Material.BOW);
        targetDrinkingPotion = isUsingItem(target, Material.POTION);
        targetInWater = target.isInWater();
        targetInCobweb = targetLoc.getBlock().getType() == Material.COBWEB;
        botInLavaArea = hasLavaNearby(world, botLoc);
        botOnFire = bot.getBukkitEntity().getFireTicks() > 0;
        targetThrowingPearl = isTargetThrowingPearl(target, world);
    }

    private static boolean isGroundedCheap(LivingEntity e) {
        return e.isOnGround();
    }

    private static boolean computeTargetOverVoid(Location loc) {
        World w = loc.getWorld();
        int minY = w.getMinHeight();
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();
        for (int y = loc.getBlockY() - 1; y >= minY; y--) {
            Block b = w.getBlockAt(bx, y, bz);
            if (!b.getType().isAir()) return false;
        }
        return true;
    }

    private static boolean hasAdjacentWall(Location loc) {
        World w = loc.getWorld();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : offsets) {
            Block b = w.getBlockAt(bx + o[0], by + o[1], bz + o[2]);
            Block b2 = w.getBlockAt(bx + o[0], by + 1 + o[1], bz + o[2]);
            if (b.getType().isSolid() && b2.getType().isSolid()) return true;
        }
        return false;
    }

    private static boolean hasOpenSkyAbove(Location loc) {
        World w = loc.getWorld();
        int top = Math.min(w.getMaxHeight() - 1, loc.getBlockY() + 6);
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();
        for (int y = loc.getBlockY() + 1; y <= top; y++) {
            if (!w.getBlockAt(bx, y, bz).getType().isAir()) return false;
        }
        return true;
    }

    private static boolean isSprintingAway(Location botLoc, Location tLoc, Vector tVel) {
        Vector away = tLoc.toVector().subtract(botLoc.toVector()).setY(0);
        if (away.lengthSquared() < 1.0e-6) return false;
        away.normalize();
        Vector flat = tVel.clone().setY(0);
        if (flat.lengthSquared() < 0.0225) return false;
        return flat.dot(away) > 0.0;
    }

    private static boolean isEating(LivingEntity target) {
        if (!(target instanceof Player p)) return false;
        ItemStack hand = p.getActiveItem();
        if (hand == null) return false;
        Material t = hand.getType();
        return t == Material.GOLDEN_APPLE
                || t == Material.ENCHANTED_GOLDEN_APPLE
                || t.isEdible();
    }

    private static boolean isUsingItem(LivingEntity target, Material expected) {
        if (!(target instanceof Player p)) return false;
        ItemStack hand = p.getActiveItem();
        return hand != null && hand.getType() == expected;
    }

    private static boolean hasLavaNearby(World w, Location loc) {
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Material m = w.getBlockAt(bx + dx, by + dy, bz + dz).getType();
                    if (m == Material.LAVA) return true;
                }
            }
        }
        return false;
    }

    private static boolean isTargetThrowingPearl(LivingEntity target, World world) {
        // Scan nearby EnderPearl entities (bounded radius) with this target as shooter.
        Location loc = target.getLocation();
        for (Entity e : world.getNearbyEntities(loc, 6.0, 6.0, 6.0)) {
            if (!(e instanceof EnderPearl pearl)) continue;
            if (pearl.getShooter() == target) return true;
        }
        return false;
    }
}
