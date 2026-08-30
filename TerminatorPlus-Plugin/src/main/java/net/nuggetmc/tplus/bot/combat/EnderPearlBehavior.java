package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.Sound;
import net.nuggetmc.tplus.compat.bukkit.entity.EnderPearl;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

/**
 * Long-range movement: when the target is far enough that walking would waste time
 * (>= {@link #MIN_DISTANCE} blocks), throw a pearl in their direction. Vanilla pearl
 * landing teleports the bot. Pearls are part of the bot's automatic movement kit
 * (refilled in {@link net.nuggetmc.tplus.bot.loadout.BotInventory#ensureMovementKit()}),
 * so this fires whenever the cooldown is ready and the target is in range.
 */
public final class EnderPearlBehavior {

    public static final String COOLDOWN_KEY = "pearl";
    private static final int COOLDOWN = 60;
    /** Match the user-facing "use pearls beyond 28 blocks" rule. Inside this radius, walk/trident. */
    private static final double MIN_DISTANCE = 28.0;
    /** Vanilla pearls have ~30-block reach before falling out of the air; cap so we don't waste throws. */
    private static final double MAX_DISTANCE = 64.0;
    private static final double SPEED = 1.8;
    private static final int RELEASE_TICKS = 5;

    public int ticksFor(Bot bot, LivingEntity target, double distance) {
        int alive = bot.getAliveTicks();
        if (distance < MIN_DISTANCE || distance > MAX_DISTANCE) {
            CombatDebugger.log(bot, "pearl-skip", "reason=range dist=" + String.format("%.2f", distance));
            return 0;
        }
        if (!bot.getBotCooldowns().ready(COOLDOWN_KEY, alive)) {
            CombatDebugger.log(bot, "pearl-skip", "reason=cooldown left=" + bot.getBotCooldowns().remaining(COOLDOWN_KEY, alive));
            return 0;
        }

        int slot = bot.getBotInventory().findHotbar(Material.ENDER_PEARL);
        if (slot < 0) {
            CombatDebugger.log(bot, "pearl-skip", "reason=no-pearl");
            return 0;
        }

        int previousSlot = bot.getBotInventory().getSelectedHotbarSlot();
        slot = bot.getBotInventory().selectMainInventorySlot(slot);
        if (slot < 0) {
            CombatDebugger.log(bot, "pearl-skip", "reason=no-selectable-slot");
            return 0;
        }
        bot.faceLocation(target.getLocation());
        bot.punch();
        int selectedSlot = slot;
        boolean started = bot.getActionController().start(bot, BotActionState.USING_PEARL, RELEASE_TICKS,
                selectedSlot, "timed-pearl-release", () -> {
                    if (!target.isValid()) {
                        CombatDebugger.log(bot, "pearl-cancel", "reason=target-invalid slot=" + selectedSlot);
                        bot.getBotInventory().restoreSelectedSlotOrBestWeapon(previousSlot);
                        return;
                    }
                    bot.getActionController().recordDirectShortcut(bot, BotActionState.USING_PEARL,
                            "projectile-pearl-release", selectedSlot);
                    Location spawn = bot.getLocation().add(0, bot.getBukkitEntity().getEyeHeight() - 0.1, 0);
                    // Lead the target slightly based on their horizontal velocity.
                    Vector targetVel = target.getVelocity();
                    Vector aimPoint = target.getLocation().toVector()
                            .add(targetVel.clone().multiply(distance / 12.0))
                            .add(new Vector(0, target.getHeight() * 0.5, 0));
                    Vector aim = aimPoint.subtract(spawn.toVector()).normalize();
                    // Arc slightly upward so the pearl travels over short cover.
                    aim.setY(aim.getY() + 0.12).normalize();

                    spawn.getWorld().spawn(spawn, EnderPearl.class, p -> {
                        p.setShooter(bot.getBukkitEntity());
                        p.setVelocity(aim.multiply(SPEED));
                    });

                    spawn.getWorld().playSound(spawn, Sound.ENTITY_ENDER_PEARL_THROW, 1f, 1f);
                    CombatDebugger.log(bot, "pearl-throw", "dist=" + String.format("%.2f", distance) + " slot=" + selectedSlot);
                    bot.getBotInventory().decrementMainInventorySlot(selectedSlot, 1);
                    bot.getBotInventory().restoreSelectedSlotOrBestWeapon(previousSlot);
                });
        if (!started) {
            bot.getBotInventory().restoreSelectedSlotOrBestWeapon(previousSlot);
            return 0;
        }
        bot.getBotCooldowns().set(COOLDOWN_KEY, COOLDOWN, alive);
        return COOLDOWN;
    }
}
