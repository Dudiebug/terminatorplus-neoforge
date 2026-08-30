package net.nuggetmc.tplus.api;

import net.nuggetmc.tplus.api.agent.Agent;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.NeuralNetwork;
import net.nuggetmc.tplus.api.utils.SkinData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface BotManager {
    /** Native NeoForge creation entry point. */
    default Terminator createBot(ServerLevel level, Vec3 position, float yaw, float pitch,
                                 String name, SkinData skin) {
        throw new UnsupportedOperationException("Native bot creation is unavailable");
    }

    default Terminator createBot(ServerLevel level, double x, double y, double z,
                                 float yaw, float pitch, String name, SkinData skin) {
        return createBot(level, new Vec3(x, y, z), yaw, pitch, name, skin);
    }
    Set<Terminator> fetch();

    Agent getAgent();

    void add(Terminator bot);

    Terminator getFirst(String name, Location target);

    List<String> fetchNames();

    Terminator createBot(Location loc, String name, String skin, String signature);

    void createBots(CommandSender sender, String name, String skinName, int n, Location location);

    void createBots(CommandSender sender, String name, String skinName, int n, NeuralNetwork network, Location location);

    Set<Terminator> createBots(Location loc, String name, String[] skin, List<NeuralNetwork> networks);

    Set<Terminator> createBots(Location loc, String name, String[] skin, int n, NeuralNetwork network);

    void remove(Terminator bot);

    void reset();

    /**
     * Get a bot from a Player object
     *
     * @param player
     * @return
     * @deprecated Use {@link #getBot(UUID)} instead as this may no longer work
     */
    @Deprecated
    Terminator getBot(Player player);

    Terminator getBot(UUID uuid);

    default Terminator getBot(ServerPlayer player) {
        return player == null ? null : getBot(player.getUUID());
    }

    Terminator getBot(int entityId);

    boolean isMobTarget();

    void setMobTarget(boolean mobTarget);

    boolean addToPlayerList();

    void setAddToPlayerList(boolean addPlayerList);
}
