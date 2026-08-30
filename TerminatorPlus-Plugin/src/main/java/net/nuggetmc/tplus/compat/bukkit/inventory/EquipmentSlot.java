package net.nuggetmc.tplus.compat.bukkit.inventory;

public enum EquipmentSlot {
    HAND(net.minecraft.world.entity.EquipmentSlot.MAINHAND), OFF_HAND(net.minecraft.world.entity.EquipmentSlot.OFFHAND),
    FEET(net.minecraft.world.entity.EquipmentSlot.FEET), LEGS(net.minecraft.world.entity.EquipmentSlot.LEGS),
    CHEST(net.minecraft.world.entity.EquipmentSlot.CHEST), HEAD(net.minecraft.world.entity.EquipmentSlot.HEAD);
    private final net.minecraft.world.entity.EquipmentSlot nms;
    EquipmentSlot(net.minecraft.world.entity.EquipmentSlot nms){this.nms=nms;}
    public net.minecraft.world.entity.EquipmentSlot nms(){return nms;}
}
