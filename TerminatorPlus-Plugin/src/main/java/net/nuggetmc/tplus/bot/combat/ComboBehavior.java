package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.Sound;
import net.nuggetmc.tplus.compat.bukkit.entity.EnderPearl;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.entity.WindCharge;
import net.nuggetmc.tplus.compat.bukkit.plugin.Plugin;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Two-step wind-charge + ender-pearl engage combo. The wind charge
 * detonation stacks its velocity onto the pearl thrown immediately after,
 * halving travel time and closing a 20+ block gap in one launch.
 *
 * <p>Bots don't disengage. A previous version had a mirror "escape"
 * combo; it was removed because bots were running from fights and
 * never re-engaging. Only the gap-closer remains.
 *
 * <p>A single instance is shared across all bots. Per-bot state lives
 * in the {@link #active} map and is cleaned up after the scheduled
 * pearl fires.
 */
public final class ComboBehavior {

    public static final String COOLDOWN_KEY = "combo";
    private static final int COOLDOWN_TICKS = 100;
    /** Delay between wind charge spawn and pearl throw. Short enough that the pearl rides the blast wave. */
    private static final int PEARL_DELAY_TICKS = 2;

    public enum ComboType {
        WIND_PEARL_ENGAGE
    }

    private final Plugin plugin;
    private final Map<UUID, Integer> active = new HashMap<>();

    public ComboBehavior(Plugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException(
                "ComboBehavior requires a non-null Plugin — runTaskLater on a null plugin NPEs.");
        }
        this.plugin = plugin;
    }

    /**
     * Convenience constructor that grabs the TerminatorPlus singleton directly
     * rather than going through {@code Bukkit.getPluginManager().getPlugin("TerminatorPlus")}.
     * The previous by-name lookup returned {@code null} if the plugin was renamed /
     * shaded under a different key, which later NPE'd inside {@code runTaskLater};
     * {@link TerminatorPlus#getInstance()} is always correct once {@code onEnable}
     * has run (which is well before any bot exists to combo).
     */
    public ComboBehavior() {
        this(TerminatorPlus.getInstance());
    }

    public boolean inProgress(Bot bot) {
        Integer endTick = active.get(bot.getUUID());
        if (endTick == null) return false;
        if (bot.getAliveTicks() > endTick) {
            active.remove(bot.getUUID());
            return false;
        }
        return true;
    }

    public void clear(UUID botId) {
        if (botId != null) {
            active.remove(botId);
        }
    }

    public boolean canCombo(Bot bot) {
        if (inProgress(bot)) return false;
        return bot.getBotCooldowns().ready(COOLDOWN_KEY, bot.getAliveTicks());
    }

    public boolean start(Bot bot, LivingEntity target, ComboType type) {
        if (!canCombo(bot)) return false;
        if (!bot.getBotInventory().hasWindCharge()) return false;
        if (!bot.getBotInventory().hasEnderPearl()) return false;

        int alive = bot.getAliveTicks();
        active.put(bot.getUUID(), alive + PEARL_DELAY_TICKS + 10);

        bot.getBotCooldowns().set(COOLDOWN_KEY, COOLDOWN_TICKS, alive);
        bot.getBotCooldowns().set(WindChargeBehavior.COOLDOWN_KEY, 55, alive);

        boolean launched = switch (type) {
            case WIND_PEARL_ENGAGE -> launchEngage(bot, target);
        };
        if (!launched) {
            active.remove(bot.getUUID());
        }
        return launched;
    }

    private boolean launchEngage(Bot bot, LivingEntity target) {
        Location botLoc = bot.getLocation();
        Vector forward = horizontalTo(botLoc, target.getLocation());
        if (forward == null) return false;

        // Step 1: wind charge behind the bot. Explosion pushes the bot forward.
        Location behind = botLoc.clone().add(forward.clone().multiply(-0.8)).add(0, 0.4, 0);
        spawnWindCharge(bot, behind, forward.clone().multiply(-0.4).setY(-0.2));

        // Step 2 (2 ticks later): throw pearl forward along the same line.
        bot.scheduleBotTask(() -> {
            try {
                if (!bot.isBotAlive()) return;
                int slot = bot.getBotInventory().findHotbar(Material.ENDER_PEARL);
                if (slot >= 0) {
                    slot = bot.getBotInventory().selectMainInventorySlot(slot);
                }
                if (slot < 0) {
                    return;
                }
                Location spawn = bot.getLocation().add(0, bot.getBukkitEntity().getEyeHeight() - 0.1, 0);
                Vector aim = target.getEyeLocation().toVector().subtract(spawn.toVector()).normalize();
                aim.setY(aim.getY() + 0.08).normalize();
                bot.faceLocation(target.getLocation());
                bot.punch();
                spawn.getWorld().spawn(spawn, EnderPearl.class, p -> {
                    p.setShooter(bot.getBukkitEntity());
                    p.setVelocity(aim.multiply(2.2));
                });
                spawn.getWorld().playSound(spawn, Sound.ENTITY_ENDER_PEARL_THROW, 1f, 1f);
                bot.getBotInventory().decrementMainInventorySlot(slot, 1);
            } finally {
                active.remove(bot.getUUID());
            }
        }, PEARL_DELAY_TICKS);
        return true;
    }

    private static void spawnWindCharge(Bot bot, Location at, Vector velocity) {
        int slot = bot.getBotInventory().findHotbar(Material.WIND_CHARGE);
        if (slot >= 0) {
            slot = bot.getBotInventory().selectMainInventorySlot(slot);
        }
        bot.punch();
        at.getWorld().spawn(at, WindCharge.class, w -> {
            w.setShooter(bot.getBukkitEntity());
            w.setVelocity(velocity);
        });
        at.getWorld().playSound(at, Sound.ENTITY_WIND_CHARGE_THROW, 1f, 1.0f);
        if (slot >= 0) {
            bot.getBotInventory().decrementMainInventorySlot(slot, 1);
        } else {
            bot.getBotInventory().decrementMaterialOrOffhand(Material.WIND_CHARGE);
        }
    }

    private static Vector horizontalTo(Location from, Location to) {
        Vector v = to.toVector().subtract(from.toVector()).setY(0);
        if (v.lengthSquared() < 1.0e-6) return null;
        return v.normalize();
    }
}
