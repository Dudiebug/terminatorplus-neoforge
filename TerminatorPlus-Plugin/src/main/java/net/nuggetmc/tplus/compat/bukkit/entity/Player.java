package net.nuggetmc.tplus.compat.bukkit.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.nuggetmc.tplus.compat.bukkit.*;
import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;
import net.nuggetmc.tplus.compat.bukkit.inventory.*;
import net.nuggetmc.tplus.compat.bukkit.event.inventory.InventoryView;
import net.nuggetmc.tplus.NeoForgePermissions;

public class Player extends HumanEntity implements CommandSender {
    protected final ServerPlayer player;
    private InventoryView openInventory;
    private ItemStack cursor = new ItemStack(net.nuggetmc.tplus.compat.bukkit.Material.AIR);
    public Player(ServerPlayer handle){super(handle);this.player=handle;}
    public ServerPlayer getHandle(){return player;} public String getName(){return player.getGameProfile().getName();} public String getDisplayName(){return getName();}
    public PlayerInventory getInventory(){return new PlayerInventory(player.getInventory());} @Override public EntityEquipment getEquipment(){return new EntityEquipment(player);}
    public void sendMessage(String message){player.sendSystemMessage(Component.literal(message==null?"":message));} public void sendRichMessage(String message){sendMessage(strip(message));}
    private static String strip(String s){return s==null?"":s.replaceAll("<[^>]+>","");}
    public boolean isOp(){return player.getServer()!=null&&player.getServer().getProfilePermissions(player.getGameProfile())>=4;}
    public boolean hasPermission(String node){
        if (node == null || node.isBlank()) return true;
        return NeoForgePermissions.has(player, node);
    }
    public boolean hasPermission(int level){return player.hasPermissions(level);}
    public void setGameMode(GameMode mode){player.setGameMode(switch(mode){case CREATIVE->net.minecraft.world.level.GameType.CREATIVE;case ADVENTURE->net.minecraft.world.level.GameType.ADVENTURE;case SPECTATOR->net.minecraft.world.level.GameType.SPECTATOR;default->net.minecraft.world.level.GameType.SURVIVAL;});}
    public GameMode getGameMode(){return switch(player.gameMode.getGameModeForPlayer()){case CREATIVE->GameMode.CREATIVE;case ADVENTURE->GameMode.ADVENTURE;case SPECTATOR->GameMode.SPECTATOR;default->GameMode.SURVIVAL;};}
    public boolean isOnline(){return player.connection!=null;} public void openInventory(Inventory inventory){openInventory=new InventoryView(inventory,getInventory(),this);player.openMenu(new net.minecraft.world.SimpleMenuProvider((id,inv,p)->net.minecraft.world.inventory.ChestMenu.sixRows(id,inv),Component.literal(inventory.getTitle())));}
    public InventoryView getOpenInventory(){return openInventory==null?new InventoryView(getInventory(),getInventory(),this):openInventory;}
    public ItemStack getItemOnCursor(){return cursor==null?null:cursor.clone();} public void setItemOnCursor(ItemStack item){cursor=item==null?new ItemStack(net.nuggetmc.tplus.compat.bukkit.Material.AIR):item.clone();}
    public ItemStack getActiveItem(){return new ItemStack(player.getUseItem());}
    public boolean breakBlock(net.nuggetmc.tplus.compat.bukkit.block.Block block){return block!=null&&block.breakNaturally(getInventory().getItemInMainHand());}
    public void setGliding(boolean value){if(value)player.startFallFlying();else player.stopFallFlying();}
    public void closeInventory(){player.closeContainer();} public void updateInventory(){player.containerMenu.broadcastChanges();} public void kickPlayer(String message){player.connection.disconnect(Component.literal(message==null?"":message));}
}
