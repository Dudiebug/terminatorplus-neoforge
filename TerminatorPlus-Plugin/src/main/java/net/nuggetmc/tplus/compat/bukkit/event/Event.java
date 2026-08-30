package net.nuggetmc.tplus.compat.bukkit.event;

/**
 * Internal source-compatibility event base.  It also extends NeoForge's bus
 * event so public TerminatorPlus events can be observed by native consumers.
 */
public class Event extends net.neoforged.bus.api.Event {
    public enum Result { DEFAULT, ALLOW, DENY }
    public boolean isAsynchronous(){return false;}
    public HandlerList getHandlers(){return new HandlerList();}
}
