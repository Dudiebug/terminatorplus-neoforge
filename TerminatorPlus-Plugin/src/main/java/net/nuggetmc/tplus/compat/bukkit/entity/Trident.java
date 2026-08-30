package net.nuggetmc.tplus.compat.bukkit.entity;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
public class Trident extends Entity { public Trident(net.minecraft.world.entity.Entity e){super(e);} private ItemStack item;private Object shooter;public void setItem(ItemStack i){item=i;}public ItemStack getItem(){return item;}public void setShooter(Object s){shooter=s;}public Object getShooter(){return shooter;} }
