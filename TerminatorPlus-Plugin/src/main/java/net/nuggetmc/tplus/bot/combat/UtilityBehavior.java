package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.block.Block;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

/**
 * Low-priority anti-mobility: if the target is moving away from the bot
 * at a good clip, drop a cobweb at their feet to slow them down.
 */
public final class UtilityBehavior {

    public static final String COOLDOWN_KEY = "cobweb";
    private static final int COOLDOWN = 30;
    private static final double MAX_DISTANCE = 4.5;
    private static final double FLEE_VELOCITY = 0.25;

    public int ticksFor(Bot bot, LivingEntity target, double distance) {
        int alive = bot.getAliveTicks();
        if (distance > MAX_DISTANCE) {
            CombatDebugger.log(bot, "cobweb-skip", "reason=range dist=" + String.format("%.2f", distance));
            return 0;
        }
        if (!bot.getBotCooldowns().ready(COOLDOWN_KEY, alive)) {
            CombatDebugger.log(bot, "cobweb-skip", "reason=cooldown left=" + bot.getBotCooldowns().remaining(COOLDOWN_KEY, alive));
            return 0;
        }
        if (!bot.getBotInventory().hasCobweb()) {
            CombatDebugger.log(bot, "cobweb-skip", "reason=no-cobweb");
            return 0;
        }

        // Only drop a cobweb if the target is fleeing *away* from us.
        Vector toTarget = target.getLocation().toVector().subtract(bot.getLocation().toVector()).setY(0);
        if (toTarget.lengthSquared() < 1.0e-6) {
            CombatDebugger.log(bot, "cobweb-skip", "reason=zero-vector");
            return 0;
        }
        toTarget.normalize();
        Vector vel = target.getVelocity().clone().setY(0);
        double fleeDot = vel.dot(toTarget);
        if (fleeDot < FLEE_VELOCITY) {
            CombatDebugger.log(bot, "cobweb-skip", "reason=target-not-fleeing dot=" + String.format("%.2f", fleeDot));
            return 0;
        }

        int slot = bot.getBotInventory().findHotbar(Material.COBWEB);
        if (slot < 0) {
            CombatDebugger.log(bot, "cobweb-skip", "reason=no-hotbar-slot");
            return 0;
        }
        int previousSlot = bot.getBotInventory().getSelectedHotbarSlot();
        slot = bot.getBotInventory().selectMainInventorySlot(slot);
        if (slot < 0) {
            CombatDebugger.log(bot, "cobweb-skip", "reason=no-selectable-slot");
            return 0;
        }
        bot.faceLocation(target.getLocation());
        bot.punch();

        Block block = target.getLocation().getBlock();
        if (!block.getType().isAir()) {
            block = block.getRelative(0, 1, 0);
        }
        if (!block.getType().isAir()) {
            CombatDebugger.log(bot, "cobweb-skip", "reason=placement-blocked");
            return 0;
        }
        if (!hasPlaceSupport(block)) {
            CombatDebugger.log(bot, "cobweb-skip",
                    "reason=no-support below=" + block.getRelative(0, -1, 0).getType().name());
            return 0;
        }
        int selectedSlot = slot;
        Block targetBlock = block;
        boolean started = bot.getActionController().start(bot, BotActionState.PLACING_BLOCK, 4,
                selectedSlot, "timed-cobweb-place", () -> {
                    if (!targetBlock.getType().isAir() || !hasPlaceSupport(targetBlock)) {
                        CombatDebugger.log(bot, "cobweb-cancel", "reason=blocked-on-release slot=" + selectedSlot);
                        bot.getBotInventory().restoreSelectedSlotOrBestWeapon(previousSlot);
                        return;
                    }
                    bot.getActionController().recordDirectShortcut(bot, BotActionState.PLACING_BLOCK,
                            "action-cobweb-setType-release", selectedSlot);
                    placeBlock(bot, targetBlock, Material.COBWEB, "utility-cobweb");
                    bot.getBotInventory().decrementMainInventorySlot(selectedSlot, 1);
                    bot.getBotInventory().restoreSelectedSlotOrBestWeapon(previousSlot);
                    CombatDebugger.log(bot, "cobweb-place", "slot=" + selectedSlot + " dot=" + String.format("%.2f", fleeDot));
                });
        if (!started) {
            bot.getBotInventory().restoreSelectedSlotOrBestWeapon(previousSlot);
            return 0;
        }
        bot.getBotCooldowns().set(COOLDOWN_KEY, COOLDOWN, alive);
        return COOLDOWN;
    }

    private static void placeBlock(Bot bot, Block block, Material material, String source) {
        Material previous = block.getType();
        CombatDebugger.blockPlace(bot, source, material, block, previous);
        block.setType(material);
    }

    private static boolean hasPlaceSupport(Block block) {
        return block.getRelative(0, -1, 0).getType().isSolid()
                || block.getRelative(1, 0, 0).getType().isSolid()
                || block.getRelative(-1, 0, 0).getType().isSolid()
                || block.getRelative(0, 0, 1).getType().isSolid()
                || block.getRelative(0, 0, -1).getType().isSolid()
                || block.getRelative(0, 1, 0).getType().isSolid();
    }
}
