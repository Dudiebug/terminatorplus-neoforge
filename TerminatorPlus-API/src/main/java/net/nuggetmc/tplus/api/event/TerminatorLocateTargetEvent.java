package net.nuggetmc.tplus.api.event;

import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.event.Cancellable;
import net.nuggetmc.tplus.compat.bukkit.event.Event;
import net.nuggetmc.tplus.compat.bukkit.event.HandlerList;
import net.neoforged.bus.api.ICancellableEvent;

public class TerminatorLocateTargetEvent extends Event implements Cancellable, ICancellableEvent {

    private static final HandlerList handlerList = new HandlerList();
    private Terminator terminator;
    private LivingEntity target;
    private boolean cancelled;

    public TerminatorLocateTargetEvent(Terminator terminator, LivingEntity target) {
        this.terminator = terminator;
        this.target = target;
    }

    public static HandlerList getHandlerList() {
        return handlerList;
    }

    @Override
    public HandlerList getHandlers() {
        return handlerList;
    }


    public Terminator getTerminator() {
        return terminator;
    }

    public LivingEntity getTarget() {
        return target;
    }

    /** Native target view; null means no target was selected. */
    public net.minecraft.world.entity.LivingEntity getNativeTarget() {
        if (target == null) return null;
        if (target instanceof net.nuggetmc.tplus.compat.bukkit.entity.Player player) return player.getHandle();
        if (target instanceof net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity entity) return entity.getHandle();
        return null;
    }

    public void setTarget(LivingEntity target) {
        this.target = target;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }

    @Override
    public boolean isCanceled() {
        return cancelled;
    }

    @Override
    public void setCanceled(boolean cancel) {
        cancelled = cancel;
    }
}
