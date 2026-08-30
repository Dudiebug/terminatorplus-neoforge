package net.nuggetmc.tplus.compat.bukkit.command;

import net.nuggetmc.tplus.compat.bukkit.permissions.ServerOperator;

/** Minimal command sender facade retained internally while commands migrate to Brigadier. */
public interface CommandSender extends ServerOperator {
    void sendMessage(String message);
    default void sendMessage(String... messages) { if (messages != null) for (String message : messages) sendMessage(message); }
    default boolean hasPermission(String permission) { return isOp(); }
    default String getName() { return "Server"; }
    default Spigot spigot(){return new Spigot(this);} final class Spigot { private final CommandSender sender; public Spigot(CommandSender s){sender=s;} public void sendMessage(net.nuggetmc.tplus.compat.bungee.chat.BaseComponent... components){if(components!=null)for(var c:components)sender.sendMessage(c==null?"":c.toString());} }
}
