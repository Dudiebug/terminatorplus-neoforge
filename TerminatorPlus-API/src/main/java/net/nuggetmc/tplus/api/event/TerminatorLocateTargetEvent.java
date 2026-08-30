package net.nuggetmc.tplus.api.event;

import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.event.Cancellable;
import net.nuggetmc.tplus.compat.bukkit.event.Event;
import net.nuggetmc.tplus.compat.bukkit.event.HandlerList;

public class TerminatorLocateTargetEvent extends Event implements Cancellable {

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
}
