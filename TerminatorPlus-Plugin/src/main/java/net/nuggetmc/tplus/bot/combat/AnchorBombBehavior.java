package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.loadout.BotInventory;
import net.nuggetmc.tplus.compat.bukkit.FluidCollisionMode;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.Sound;
import net.nuggetmc.tplus.compat.bukkit.World;
import net.nuggetmc.tplus.compat.bukkit.block.Block;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

/**
 * Overworld / End respawn-anchor bomb: place a respawn anchor next to the target,
 * charge it with glowstone, then "use" it so vanilla detonates it. Vanilla anchors
 * only function as a spawn point in the Nether — in every other dimension, using a
 * charged anchor explodes with power 5.0F. We gate OUT of the Nether so the
 * detonation actually happens.
 *
 * <p>The blast radius is ~5 blocks, so the bot must stand at least {@link #MIN_DISTANCE}
 * away from the target when placing the anchor; otherwise it kills itself. The director
 * is also expected to trigger this only when the bot has open space behind it.
 */
public final class AnchorBombBehavior {

    public static final String COOLDOWN_KEY = "anchor";
    private static final int COOLDOWN = 50;
    /** Stay outside the ~5-block vanilla explosion radius so the bot survives its own bomb. */
    public static final double MIN_DISTANCE = 6.0;
    public static final double MAX_DISTANCE = 8.0;

    public int ticksFor(Bot bot, LivingEntity target, double distance) {
        // Vanilla anchors only explode OUTSIDE the Nether. In the Nether they just set spawn.
        int alive = bot.getAliveTicks();
        if (target.getWorld().getEnvironment() == World.Environment.NETHER) {
            CombatDebugger.log(bot, "anchor-skip", "reason=nether");
            return 0;
        }
        if (distance < MIN_DISTANCE || distance > MAX_DISTANCE) {
            CombatDebugger.log(bot, "anchor-skip", "reason=range dist=" + String.format("%.2f", distance));
            return 0;
        }
        if (!bot.getBotCooldowns().ready(COOLDOWN_KEY, alive)) {
            CombatDebugger.log(bot, "anchor-skip", "reason=cooldown left=" + bot.getBotCooldowns().remaining(COOLDOWN_KEY, alive));
            return 0;
        }

        BotInventory inv = bot.getBotInventory();
        if (!inv.hasAnchorKit()) {
            CombatDebugger.log(bot, "anchor-skip", "reason=no-kit");
            return 0;
        }
        if (inv.findMainInventory(Material.RESPAWN_ANCHOR) < 0 || inv.findMainInventory(Material.GLOWSTONE) < 0) {
            CombatDebugger.log(bot, "anchor-skip", "reason=missing-resource");
            return 0;
        }

        World world = target.getWorld();
        Block placeAt = findPlaceableBlock(target.getLocation());
        if (placeAt == null) {
            CombatDebugger.log(bot, "anchor-skip", "reason=no-placeable");
            return 0;
        }

        Location boom = placeAt.getLocation().add(0.5, 0.5, 0.5);
        if (!hasClearLine(bot, boom)) {
            CombatDebugger.log(bot, "anchor-skip", "reason=blocked-line");
            return 0;
        }

        bot.getActionController().recordDirectShortcut(bot, BotActionState.ANCHOR_SEQUENCE,
                "direct-anchor-place-charge-explode", inv.findMainInventory(Material.RESPAWN_ANCHOR));
        placeAt.setType(Material.RESPAWN_ANCHOR);
        consumeOne(inv, Material.RESPAWN_ANCHOR);
        consumeOne(inv, Material.GLOWSTONE);
        bot.faceLocation(boom);
        bot.punch();

        // Replace with air before detonating so the block-form anchor doesn't absorb the blast.
        placeAt.setType(Material.AIR);
        world.createExplosion(boom, 5.0f, false, true, bot.getBukkitEntity());
        world.playSound(boom, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1f, 0.7f);
        CombatDebugger.log(bot, "anchor-boom", "dist=" + String.format("%.2f", distance));

        bot.getBotCooldowns().set(COOLDOWN_KEY, COOLDOWN, alive);
        return COOLDOWN;
    }

    private Block findPlaceableBlock(Location around) {
        World w = around.getWorld();
        int bx = around.getBlockX();
        int by = around.getBlockY();
        int bz = around.getBlockZ();
        // Prefer the block the target is standing on (replacement) or the adjacent air block.
        Block onFeet = w.getBlockAt(bx, by, bz);
        if (onFeet.getType().isAir()) return onFeet;
        for (int[] o : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}}) {
            Block b = w.getBlockAt(bx + o[0], by + o[1], bz + o[2]);
            if (b.getType().isAir()) return b;
        }
        return null;
    }

    private boolean hasClearLine(Bot bot, Location boom) {
        Location eye = bot.getBukkitEntity().getEyeLocation();
        Vector direction = boom.toVector().subtract(eye.toVector());
        double length = direction.length();
        if (length < 1.0e-6) return true;
        direction.normalize();
        return eye.getWorld().rayTraceBlocks(eye, direction, length, FluidCollisionMode.NEVER, true) == null;
    }

    private void consumeOne(BotInventory inv, Material type) {
        inv.decrementMaterial(type);
    }
}
