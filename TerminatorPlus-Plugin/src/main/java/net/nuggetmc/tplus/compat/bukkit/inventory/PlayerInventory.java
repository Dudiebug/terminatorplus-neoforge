package net.nuggetmc.tplus.compat.bukkit.inventory;

import java.util.*;
import net.minecraft.world.entity.player.Inventory;

public final class PlayerInventory implements net.nuggetmc.tplus.compat.bukkit.inventory.Inventory {
    private final Inventory nms;
    public PlayerInventory(Inventory nms){this.nms=nms;}
    public Inventory getHandle(){return nms;}
    public int getSize(){return 41;}
    public ItemStack getItem(int slot){if(slot<0||slot>=36)return switch(slot){case 36->getBoots();case 37->getLeggings();case 38->getChestplate();case 39->getHelmet();case 40->getItemInOffHand();default->null;};return new ItemStack(nms.getItem(slot));}
    public void setItem(int slot,ItemStack item){net.minecraft.world.item.ItemStack x=item==null?net.minecraft.world.item.ItemStack.EMPTY:item.asNmsCopy();if(slot<36)nms.setItem(slot,x);else switch(slot){case 36->nms.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,x);case 37->nms.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS,x);case 38->nms.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,x);case 39->nms.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,x);case 40->nms.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,x);}nms.setChanged();}
    public ItemStack getItemInMainHand(){return new ItemStack(nms.getSelected());} public void setItemInMainHand(ItemStack s){setItem(nms.selected,s);}
    public ItemStack getItemInOffHand(){return new ItemStack(nms.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND));} public void setItemInOffHand(ItemStack s){setItem(40,s);}
    public ItemStack[] getStorageContents(){ItemStack[] a=new ItemStack[36];for(int i=0;i<36;i++)a[i]=getItem(i);return a;}
    public void setStorageContents(ItemStack[] a){for(int i=0;i<36;i++)setItem(i,a!=null&&i<a.length?a[i]:null);}
    public ItemStack[] getArmorContents(){return new ItemStack[]{getBoots(),getLeggings(),getChestplate(),getHelmet()};}
    public void setArmorContents(ItemStack[] a){setItem(36,a!=null&&a.length>0?a[0]:null);setItem(37,a!=null&&a.length>1?a[1]:null);setItem(38,a!=null&&a.length>2?a[2]:null);setItem(39,a!=null&&a.length>3?a[3]:null);}
    public ItemStack[] getExtraContents(){return new ItemStack[]{getItemInOffHand()};} public void setExtraContents(ItemStack[] a){setItem(40,a!=null&&a.length>0?a[0]:null);}
    public ItemStack getBoots(){return new ItemStack(nms.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET));} public ItemStack getLeggings(){return new ItemStack(nms.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS));} public ItemStack getChestplate(){return new ItemStack(nms.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST));} public ItemStack getHelmet(){return new ItemStack(nms.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD));}
    public void setBoots(ItemStack item){setItem(36,item);} public void setLeggings(ItemStack item){setItem(37,item);} public void setChestplate(ItemStack item){setItem(38,item);} public void setHelmet(ItemStack item){setItem(39,item);}
    public void setHeldItemSlot(int slot){if(slot>=0&&slot<9)nms.selected=slot;}
    public ItemStack[] addItem(ItemStack... items){for(ItemStack item:items){int slot=firstEmpty();if(slot>=0)setItem(slot,item);}return new ItemStack[0];}
    public void removeItem(ItemStack item){for(int i=0;i<getSize();i++){if(item!=null&&item.isSimilar(getItem(i)))setItem(i,null);}}
}
