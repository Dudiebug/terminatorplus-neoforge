package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.Sound;
import net.nuggetmc.tplus.compat.bukkit.entity.Firework;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.entity.Trident;
import net.nuggetmc.tplus.compat.bukkit.inventory.EquipmentSlot;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.inventory.meta.FireworkMeta;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

/**
 * Controls when the bot glides, when it uses a firework boost, and
 * when it combines a dive with a trident throw for a devastating
 * aerial strike. Runs every tick while the bot carries an elytra
 * — ambient, not gated on a specific "selected" weapon.
 */
public final class ElytraBehavior {

    public static final String FIREWORK_CD = "elytra_firework";
    public static final String DIVE_TRIDENT_CD = "elytra_dive_trident";
    private static final int FIREWORK_COOLDOWN = 60;
    private static final int DIVE_TRIDENT_COOLDOWN = 80;
    private static final double GLIDE_TRIGGER_FALL = 3.0;
    private static final double TARGET_DIVE_MIN_DY = 4.0;

    public void tick(Bot bot, LivingEntity target) {
        if (!bot.getBotInventory().hasElytra()) {
            // If bot was gliding, ensure glide is off.
            if (bot.getBukkitEntity().isGliding()) bot.getBukkitEntity().setGliding(false);
            return;
        }

        boolean onGround = bot.isBotOnGround();
        Vector vel = bot.getVelocity();

        // Dynamic chestplate/elytra swap:
        // - Airborne & falling ≥ GLIDE_TRIGGER_FALL blocks → put on elytra
        // - On ground → put the chestplate back on for defense
        if (!onGround && fallDistance(bot) >= GLIDE_TRIGGER_FALL) {
            bot.getBotInventory().swapChest(Material.ELYTRA);
        } else if (onGround) {
            // Prefer any non-elytra chestpiece when grounded.
            swapToBestChestplate(bot);
        }

        ItemStack chest = bot.getBukkitEntity().getInventory().getChestplate();
        boolean wearingElytra = chest != null && chest.getType() == Material.ELYTRA;

        if (wearingElytra && !onGround && vel.getY() < -0.3) {
            bot.getBukkitEntity().setGliding(true);

            // Forward drift toward target so the bot doesn't just drop straight down.
            if (target != null) {
                Vector toTarget = target.getLocation().toVector().subtract(bot.getLocation().toVector());
                toTarget.setY(0);
                if (toTarget.lengthSquared() > 1.0e-6) {
                    toTarget.normalize().multiply(0.08);
                    bot.walk(toTarget);
                }
            }

            // Dampen Y so the glide feels sustained rather than vanilla free-fall.
            Vector v = bot.getVelocity();
            if (v.getY() < -0.6) {
                v.setY(-0.6);
                bot.setVelocity(v);
            }

            // Firework boost.
            if (bot.getBotInventory().hasFirework()
                    && bot.getBotCooldowns().ready(FIREWORK_CD, bot.getAliveTicks())
                    && target != null
                    && horizontalDistance(bot, target) > 6.0) {
                launchFireworkBoost(bot, target);
                bot.getBotCooldowns().set(FIREWORK_CD, FIREWORK_COOLDOWN, bot.getAliveTicks());
            }

            // Devastating aerial trident: when gliding high above target, release a trident riding the glide velocity.
            if (target != null
                    && bot.getBotInventory().hasTrident()
                    && bot.getBotCooldowns().ready(DIVE_TRIDENT_CD, bot.getAliveTicks())
                    && (bot.getLocation().getY() - target.getLocation().getY()) >= TARGET_DIVE_MIN_DY
                    && horizontalDistance(bot, target) < 18.0) {
                hurlDiveTrident(bot, target);
                bot.getBotCooldowns().set(DIVE_TRIDENT_CD, DIVE_TRIDENT_COOLDOWN, bot.getAliveTicks());
            }
        } else {
            if (bot.getBukkitEntity().isGliding()) bot.getBukkitEntity().setGliding(false);
        }
    }

    private void swapToBestChestplate(Bot bot) {
        ItemStack chest = bot.getBukkitEntity().getInventory().getChestplate();
        if (chest == null || chest.getType() != Material.ELYTRA) return;
        // Try progressively tougher chestplates stored in the inventory.
        Material[] preferred = {
                Material.NETHERITE_CHESTPLATE,
                Material.DIAMOND_CHESTPLATE,
                Material.IRON_CHESTPLATE,
                Material.GOLDEN_CHESTPLATE,
                Material.CHAINMAIL_CHESTPLATE,
                Material.LEATHER_CHESTPLATE
        };
        for (Material m : preferred) {
            if (bot.getBotInventory().swapChest(m)) return;
        }
    }

    private void launchFireworkBoost(Bot bot, LivingEntity target) {
        Location loc = bot.getLocation().add(0, 1.0, 0);
        ItemStack rocket = new ItemStack(Material.FIREWORK_ROCKET);
        Firework fw = loc.getWorld().spawn(loc, Firework.class, f -> {
            FireworkMeta meta = f.getFireworkMeta();
            meta.setPower(2);
            f.setFireworkMeta(meta);
            f.setShooter(bot.getBukkitEntity());
            f.setVelocity(bot.getVelocity().clone().normalize().multiply(0.5));
        });
        if (fw != null) {
            fw.setTicksToDetonate(20);
        }
        consumeOne(bot, Material.FIREWORK_ROCKET);
        loc.getWorld().playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
    }

    private void hurlDiveTrident(Bot bot, LivingEntity target) {
        Location spawn = bot.getLocation().add(0, bot.getBukkitEntity().getEyeHeight() - 0.2, 0);
        Vector aim = target.getEyeLocation().toVector().subtract(spawn.toVector()).normalize();
        Vector momentum = bot.getVelocity();
        double speed = 2.8 + Math.min(1.2, momentum.length() * 0.8);
        bot.punch();
        spawn.getWorld().spawn(spawn, Trident.class, t -> {
            t.setVelocity(aim.multiply(speed));
            t.setShooter(bot.getBukkitEntity());
            t.setItem(new ItemStack(Material.TRIDENT));
        });
        spawn.getWorld().playSound(spawn, Sound.ITEM_TRIDENT_THROW, 1f, 0.9f);
    }

    private double horizontalDistance(Bot bot, LivingEntity target) {
        double dx = bot.getLocation().getX() - target.getLocation().getX();
        double dz = bot.getLocation().getZ() - target.getLocation().getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private double fallDistance(Bot bot) {
        // Bot tracks fall via Y velocity; use that as a proxy when the vanilla field isn't reliable.
        return Math.max(0, -bot.getVelocity().getY() * 10.0);
    }

    private void consumeOne(Bot bot, Material type) {
        bot.getBotInventory().decrementMaterial(type);
    }
}
