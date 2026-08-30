package net.nuggetmc.tplus.compat.bukkit.attribute;
public enum Attribute {
    GENERIC_MAX_HEALTH(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH),
    GENERIC_ATTACK_DAMAGE(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE),
    GENERIC_ATTACK_SPEED(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED),
    GENERIC_MOVEMENT_SPEED(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED),
    GENERIC_ARMOR(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR),
    GENERIC_ARMOR_TOUGHNESS(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS),
    GENERIC_KNOCKBACK_RESISTANCE(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE),
    GENERIC_FOLLOW_RANGE(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
    private final net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> nms;
    Attribute(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> nms){this.nms=nms;}
    public net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> nms(){return nms;}
}
