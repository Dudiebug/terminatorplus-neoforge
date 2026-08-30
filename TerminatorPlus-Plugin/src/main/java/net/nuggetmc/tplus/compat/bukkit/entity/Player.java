package net.nuggetmc.tplus.compat.bukkit.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.nuggetmc.tplus.compat.bukkit.*;
import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;
import net.nuggetmc.tplus.compat.bukkit.inventory.*;

public class Player extends HumanEntity implements CommandSender {
    protected final ServerPlayer player;
    public Player(ServerPlayer handle){super(handle);this.player=handle;}
    public ServerPlayer getHandle(){return player;} public String getName(){return player.getGameProfile().getName();} public String getDisplayName(){return getName();}
    public PlayerInventory getInventory(){return new PlayerInventory(player.getInventory());} @Override public EntityEquipment getEquipment(){return new EntityEquipment(player);}
    public void sendMessage(String message){player.sendSystemMessage(Component.literal(message==null?"":message));} public void sendRichMessage(String message){sendMessage(strip(message));}
    private static String strip(String s){return s==null?"":s.replaceAll("<[^>]+>","");}
    public boolean isOp(){return player.getServer()!=null&&player.getServer().getProfilePermissions(player.getGameProfile())>=4;} public boolean hasPermission(String node){return isOp();} public boolean hasPermission(int level){return player.hasPermissions(level);}
    public void setGameMode(GameMode mode){player.setGameMode(switch(mode){case CREATIVE->net.minecraft.world.level.GameType.CREATIVE;case ADVENTURE->net.minecraft.world.level.GameType.ADVENTURE;case SPECTATOR->net.minecraft.world.level.GameType.SPECTATOR;default->net.minecraft.world.level.GameType.SURVIVAL;});}
    public GameMode getGameMode(){return switch(player.gameMode.getGameModeForPlayer()){case CREATIVE->GameMode.CREATIVE;case ADVENTURE->GameMode.ADVENTURE;case SPECTATOR->GameMode.SPECTATOR;default->GameMode.SURVIVAL;};}
    public boolean isOnline(){return player.connection!=null;} public void openInventory(Inventory inventory){player.openMenu(new net.minecraft.world.SimpleMenuProvider((id,inv,p)->new net.minecraft.world.inventory.ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x6,id,inv,6),Component.literal(inventory.getTitle())));}
    public void closeInventory(){player.closeContainer();} public void updateInventory(){player.containerMenu.broadcastChanges();} public void kickPlayer(String message){player.connection.disconnect(Component.literal(message==null?"":message));}
}
