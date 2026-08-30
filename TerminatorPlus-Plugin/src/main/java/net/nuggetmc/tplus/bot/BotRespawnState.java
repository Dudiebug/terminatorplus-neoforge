package net.nuggetmc.tplus.bot;

import net.nuggetmc.tplus.api.agent.legacyagent.ai.NeuralNetwork;
import net.nuggetmc.tplus.api.utils.SkinData;
import net.nuggetmc.tplus.compat.bukkit.Particle;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.World;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.inventory.PlayerInventory;

import java.util.UUID;

record BotRespawnState(
        UUID uuid,
        String name,
        Location spawnLocation,
        SkinData skin,
        ItemStack[] storage,
        ItemStack[] armor,
        ItemStack[] extra,
        int selectedHotbarSlot,
        ItemStack defaultItem,
        NeuralNetwork network,
        boolean shield,
        UUID targetPlayer,
        int kills,
        boolean respectLoadout,
        String trainingLoadout,
        boolean inPlayerList
) {

    static BotRespawnState capture(Bot bot) {
        Location anchor = bot.respawnAnchor();
        if (anchor == null) return null;

        var botInventory = bot.getBotInventory();
        return new BotRespawnState(
                bot.getUUID(),
                bot.getBotName(),
                anchor,
                bot.skinData(),
                botInventory.respawnStorageContents(),
                botInventory.respawnArmorContents(),
                botInventory.respawnExtraContents(),
                botInventory.respawnSelectedHotbarSlot(),
                cloneItem(bot.defaultItem),
                bot.getNeuralNetwork(),
                bot.hasShieldEnabled(),
                bot.getTargetPlayer(),
                bot.getKills(),
                botInventory.isRespectingLoadout(),
                bot.trainingLoadout(),
                bot.isInPlayerList()
        );
    }

    Bot respawn() {
        Location location = RespawnSafety.findNearestSafe(spawnLocation);
        if (location == null) return null;

        Bot bot = Bot.createBot(location, name, skin, uuid, inPlayerList);
        PlayerInventory inventory = bot.getBukkitEntity().getInventory();
        inventory.setStorageContents(copy(storage));
        inventory.setArmorContents(copy(armor));
        inventory.setExtraContents(copy(extra));
        bot.getBotInventory().setSelectedHotbarSlot(selectedHotbarSlot);
        bot.setDefaultItem(cloneItem(defaultItem));
        bot.setNeuralNetwork(network);
        bot.restoreShieldFlag(shield);
        bot.setTargetPlayer(targetPlayer);
        bot.restoreKills(kills);
        bot.restoreTrainingLoadout(trainingLoadout);
        if (respectLoadout) {
            bot.getBotInventory().markLoadoutApplied();
        } else {
            bot.getBotInventory().saveForRespawn();
        }
        bot.getBukkitEntity().updateInventory();
        RespawnSafety.emitPoof(bot.getLocation(), BotRespawnState::spawnPoof);
        return bot;
    }

    private static void spawnPoof(Location location) {
        World world = location.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.CLOUD, location, 12, 0.25, 0.35, 0.25, 0.02);
        }
    }

    static ItemStack[] copy(ItemStack[] contents) {
        if (contents == null) return new ItemStack[0];
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = cloneItem(contents[i]);
        }
        return copy;
    }

    private static ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }
}
