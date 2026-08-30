package net.nuggetmc.tplus.compat.bukkit.event;
public class Event { public enum Result { DEFAULT, ALLOW, DENY } public boolean isAsynchronous(){return false;} public HandlerList getHandlers(){return new HandlerList();} }
