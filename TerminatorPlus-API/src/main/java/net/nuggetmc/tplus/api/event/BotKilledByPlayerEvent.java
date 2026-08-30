package net.nuggetmc.tplus.api.event;

import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.neoforged.bus.api.Event;
import net.minecraft.server.level.ServerPlayer;

/** Fired after a bot death is attributed to a player. */
public class BotKilledByPlayerEvent extends Event {

    // eventually also call this event for deaths from other damage causes within combat time
    // (like hitting the ground too hard)

    private final Terminator bot;
    private final Player player;

    public BotKilledByPlayerEvent(Terminator bot, Player player) {
        this.bot = bot;
        this.player = player;
    }

    public BotKilledByPlayerEvent(Terminator bot, ServerPlayer player) {
        this(bot, player == null ? null : net.nuggetmc.tplus.compat.bukkit.entity.EntityBridge.player(player));
    }

    public Terminator getBot() {
        return bot;
    }

    public Player getPlayer() {
        return player;
    }

    public ServerPlayer getServerPlayer() {
        return player == null ? null : player.getHandle();
    }

    public ServerPlayer getNativePlayer() {
        return getServerPlayer();
    }
}
