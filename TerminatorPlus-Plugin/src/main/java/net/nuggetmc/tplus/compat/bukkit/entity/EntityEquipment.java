package net.nuggetmc.tplus.compat.bukkit.entity;

import net.nuggetmc.tplus.compat.bukkit.inventory.*;

public final class EntityEquipment {
    private final net.minecraft.world.entity.LivingEntity entity;
    public EntityEquipment(net.minecraft.world.entity.LivingEntity entity){this.entity=entity;}
    public ItemStack getItem(EquipmentSlot slot){return new ItemStack(entity.getItemBySlot(slot.nms()));}
    public ItemStack getItemInMainHand(){return getItem(EquipmentSlot.HAND);} public ItemStack getItemInOffHand(){return getItem(EquipmentSlot.OFF_HAND);}
    public ItemStack getHelmet(){return getItem(EquipmentSlot.HEAD);} public ItemStack getChestplate(){return getItem(EquipmentSlot.CHEST);} public ItemStack getLeggings(){return getItem(EquipmentSlot.LEGS);} public ItemStack getBoots(){return getItem(EquipmentSlot.FEET);}
    public void setItem(EquipmentSlot slot,ItemStack item){entity.setItemSlot(slot.nms(),item==null?net.minecraft.world.item.ItemStack.EMPTY:item.asNmsCopy());}
}
