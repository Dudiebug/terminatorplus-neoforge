package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.Sound;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.entity.Trident;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

/**
 * Momentum-building trident use. The bot sprints toward the target for
 * up to {@link #MAX_CHARGE_TICKS}, then releases a thrown trident whose
 * exit velocity is amplified by the momentum it built.
 */
public final class TridentBehavior {

    public static final String COOLDOWN_KEY = "trident";
    private static final int MAX_CHARGE_TICKS = 18;
    private static final int MIN_RELEASE_TICKS = 6;
    private static final int RELEASE_COOLDOWN = 45;
    public static final double MIN_DISTANCE = 5.0;
    public static final double MAX_DISTANCE = 30.0;
    public static final double MELEE_FALLBACK_DISTANCE = 5.0;
    private static final double THROW_BASE_SPEED = 2.5;
    private static final double THROW_MOMENTUM_BONUS = 1.4;

    public int ticksFor(Bot bot, LivingEntity target, double distance) {
        int alive = bot.getAliveTicks();
        CombatState state = bot.getCombatState();
        boolean charging = state.getPhase() == CombatState.Phase.CHARGING;
        if (!bot.getBotCooldowns().ready(COOLDOWN_KEY, alive)) {
            CombatDebugger.log(bot, "trident-skip", "reason=cooldown left=" + bot.getBotCooldowns().remaining(COOLDOWN_KEY, alive));
            return 0;
        }
        if (!charging && (distance < MIN_DISTANCE || distance > MAX_DISTANCE)) {
            CombatDebugger.log(bot, "trident-skip", "reason=range dist=" + String.format("%.2f", distance));
            return 0;
        }

        Location targetLoc = target.getLocation();
        Location botLoc = bot.getLocation();
        Vector toTarget = targetLoc.toVector().subtract(botLoc.toVector());
        toTarget.setY(0);
        if (toTarget.lengthSquared() < 1.0e-6) {
            CombatDebugger.log(bot, "trident-skip", "reason=zero-vector");
            return 0;
        }
        Vector dir = toTarget.normalize();

        bot.faceLocation(targetLoc);

        if (state.getPhase() != CombatState.Phase.CHARGING) {
            int slot = bot.getBotInventory().findHotbar(Material.TRIDENT);
            if (slot < 0) {
                CombatDebugger.log(bot, "trident-skip", "reason=no-trident-slot");
                return 0;
            }
            if (!bot.getActionController().start(bot, BotActionState.THROWING_TRIDENT,
                    MAX_CHARGE_TICKS + 4, slot, "timed-trident-charge")) {
                CombatDebugger.log(bot, "trident-skip",
                        "reason=action-busy active=" + bot.getActionController().state());
                return 0;
            }
            CombatDebugger.log(bot, "trident-charge-start", "dist=" + String.format("%.2f", distance));
            state.setPhase(CombatState.Phase.CHARGING);
            state.setChargeDirection(dir);
        }

        int charge = state.tickPhase();

        // Run up: accumulate horizontal velocity toward the target.
        if (bot.isBotOnGround()) {
            bot.walk(dir.clone().multiply(0.38));
        }

        boolean out = distance < MIN_DISTANCE || distance > MAX_DISTANCE;
        boolean aimReady = charge >= MIN_RELEASE_TICKS;
        CombatDebugger.log(bot, "trident-charge", "ticks=" + charge + " out=" + out + " aimReady=" + aimReady + " dist=" + String.format("%.2f", distance));
        if (charge >= MAX_CHARGE_TICKS || (aimReady && out) || (aimReady && hasClearThrow(bot, target))) {
            release(bot, target, dir);
            state.reset();
            bot.getBotCooldowns().set(COOLDOWN_KEY, RELEASE_COOLDOWN, alive);
            return RELEASE_COOLDOWN;
        }

        return 0;
    }

    private boolean hasClearThrow(Bot bot, LivingEntity target) {
        Location eye = bot.getLocation().add(0, bot.getBukkitEntity().getEyeHeight() - 0.2, 0);
        return eye.getWorld() == target.getWorld();
    }

    private void release(Bot bot, LivingEntity target, Vector runDir) {
        Location spawn = bot.getLocation().add(0, bot.getBukkitEntity().getEyeHeight() - 0.2, 0)
                .add(runDir.clone().multiply(0.6));
        Vector aim = target.getEyeLocation().toVector().subtract(spawn.toVector()).normalize();
        Vector momentum = bot.getVelocity();
        double momentumBoost = Math.min(THROW_MOMENTUM_BONUS, momentum.length() * 1.2);
        Vector velocity = aim.multiply(THROW_BASE_SPEED + momentumBoost);
        CombatDebugger.log(bot, "trident-release",
                "speed=" + String.format("%.2f", velocity.length()) + " momentum=" + String.format("%.2f", momentum.length()));

        int slot = bot.getBotInventory().findHotbar(Material.TRIDENT);
        bot.getActionController().recordDirectShortcut(bot, BotActionState.THROWING_TRIDENT,
                "projectile-trident-release", slot);
        bot.punch();

        Trident trident = spawn.getWorld().spawn(spawn, Trident.class, t -> {
            t.setVelocity(velocity);
            t.setShooter(bot.getBukkitEntity());
            t.setItem(new ItemStack(Material.TRIDENT));
        });

        spawn.getWorld().playSound(spawn, Sound.ITEM_TRIDENT_THROW, 1f, 1f);
        if (bot.getActionController().active(BotActionState.THROWING_TRIDENT)) {
            bot.getActionController().complete(bot, "released");
        }
    }
}
