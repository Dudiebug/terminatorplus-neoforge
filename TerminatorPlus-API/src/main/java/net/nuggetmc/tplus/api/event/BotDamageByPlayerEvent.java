package net.nuggetmc.tplus.api.event;

import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.minecraft.server.level.ServerPlayer;

/** Fired immediately before a player damages a Terminator bot. */
public class BotDamageByPlayerEvent extends Event implements ICancellableEvent {

    private final Terminator bot;
    private final Player player;

    private float damage;

    private boolean cancelled;

    public BotDamageByPlayerEvent(Terminator bot, Player player, float damage) {
        this.bot = bot;
        this.player = player;
        this.damage = damage;
    }

    public BotDamageByPlayerEvent(Terminator bot, ServerPlayer player, float damage) {
        this(bot, player == null ? null : net.nuggetmc.tplus.compat.bukkit.entity.EntityBridge.player(player), damage);
    }

    public Terminator getBot() {
        return bot;
    }

    public Player getPlayer() {
        return player;
    }

    /** Native NeoForge player payload. */
    public ServerPlayer getServerPlayer() {
        return player == null ? null : player.getHandle();
    }

    public ServerPlayer getNativePlayer() {
        return getServerPlayer();
    }

    public float getDamage() {
        return damage;
    }

    public void setDamage(float damage) {
        this.damage = damage;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public boolean isCanceled() {
        return cancelled;
    }

    @Override
    public void setCanceled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
