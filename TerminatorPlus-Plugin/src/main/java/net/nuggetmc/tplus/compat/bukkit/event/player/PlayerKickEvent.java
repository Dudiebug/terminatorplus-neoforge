package net.nuggetmc.tplus.compat.bukkit.event.player;
import net.nuggetmc.tplus.compat.bukkit.event.*; import net.nuggetmc.tplus.compat.bukkit.entity.Player;
public class PlayerKickEvent extends Event { private final Player player; public PlayerKickEvent(Player p){player=p;} public Player getPlayer(){return player;} }
