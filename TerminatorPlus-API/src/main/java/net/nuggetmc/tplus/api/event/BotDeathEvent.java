package net.nuggetmc.tplus.api.event;

import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.compat.bukkit.event.entity.EntityDeathEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

public class BotDeathEvent extends EntityDeathEvent {

    private final Terminator bot;

    public BotDeathEvent(EntityDeathEvent event, Terminator bot) {
        super(event.getEntity(), event.getDamageSource(), event.getDrops(), event.getDroppedExp());
        this.bot = bot;
    }

    public Terminator getBot() {
        return bot;
    }

    public ServerPlayer getServerPlayer() {
        return bot == null ? null : bot.getServerPlayer();
    }

    public DamageSource getNativeDamageSource() {
        return getDamageSource() instanceof DamageSource source ? source : null;
    }
}
