package net.nuggetmc.tplus.compat.bukkit.entity;

import net.nuggetmc.tplus.compat.bukkit.*;
import net.nuggetmc.tplus.compat.bukkit.attribute.Attribute;
import net.nuggetmc.tplus.compat.bukkit.inventory.*;
import net.nuggetmc.tplus.compat.bukkit.potion.*;
import java.util.*;

public class LivingEntity extends Entity implements Damageable {
    protected final net.minecraft.world.entity.LivingEntity living;
    public LivingEntity(net.minecraft.world.entity.LivingEntity handle){super(handle);this.living=handle;}
    public double getHealth(){return living.getHealth();} public void setHealth(double h){living.setHealth((float)Math.max(0,Math.min(getMaxHealth(),h)));} public float getMaxHealth(){return living.getMaxHealth();} public int getNoDamageTicks(){return living.invulnerableTime;} public void setNoDamageTicks(int ticks){living.invulnerableTime=Math.max(0,ticks);} public double getEyeHeight(){return living.getEyeHeight();} public double getHeight(){return living.getBbHeight();}
    public boolean isBlocking(){return living.isBlocking();} public boolean isGliding(){return living.isFallFlying();} public boolean isSwimming(){return living.isSwimming();} public boolean isSneaking(){return living.isCrouching();} public void setSneaking(boolean b){living.setShiftKeyDown(b);}
    public void damage(double amount){living.hurt(living.damageSources().generic(),(float)amount);} public void damage(double amount,Entity source){living.hurt(living.damageSources().mobAttack(source.getHandle() instanceof net.minecraft.world.entity.LivingEntity l?l:living),(float)amount);}
    public void attack(Entity target){if(living instanceof net.minecraft.server.level.ServerPlayer p) p.attack(target.getHandle());}
    public EntityEquipment getEquipment(){return new EntityEquipment(living);} public ItemStack getItemInHand(EquipmentSlot slot){return new ItemStack(living.getItemBySlot(slot.nms()));}
    public void setItemInHand(EquipmentSlot slot,ItemStack stack){living.setItemSlot(slot.nms(),stack==null?net.minecraft.world.item.ItemStack.EMPTY:stack.asNmsCopy());}
    public net.nuggetmc.tplus.compat.bukkit.attribute.AttributeInstance getAttribute(Attribute attribute){if(attribute==null)return null;var instance=living.getAttribute(attribute.nms());return instance==null?null:new net.nuggetmc.tplus.compat.bukkit.attribute.AttributeInstance(instance);} public void addPotionEffect(PotionEffect effect){if(effect!=null)living.addEffect(effect.toNms());} public boolean addPotionEffect(PotionEffect effect, boolean force){addPotionEffect(effect);return true;}
    public boolean hasPotionEffect(PotionEffectType type){return living.hasEffect(type.nms());} public void removePotionEffect(PotionEffectType type){living.removeEffect(type.nms());}
    public void swingMainHand(){living.swing(net.minecraft.world.InteractionHand.MAIN_HAND);} public void swingOffHand(){living.swing(net.minecraft.world.InteractionHand.OFF_HAND);}
    public net.nuggetmc.tplus.compat.bukkit.inventory.PlayerInventory getInventory(){return living instanceof net.minecraft.server.level.ServerPlayer p?new net.nuggetmc.tplus.compat.bukkit.inventory.PlayerInventory(p.getInventory()):null;} public void setSwimming(boolean value){if(value)living.setSwimming(true);} 
    public net.nuggetmc.tplus.compat.bukkit.block.BlockFace getFacing(){return net.nuggetmc.tplus.compat.bukkit.block.BlockFace.NORTH;} public void playSound(Location location,Sound sound,SoundCategory category,float volume,float pitch){if(location!=null)getWorld().playSound(location,sound,category,volume,pitch);}
    public void playSound(Location location,Sound sound,float volume,float pitch){playSound(location,sound,SoundCategory.PLAYERS,volume,pitch);}
}
