package net.nuggetmc.tplus.compat.bukkit.attribute;

public final class AttributeInstance {
    private final net.minecraft.world.entity.ai.attributes.AttributeInstance delegate;
    public AttributeInstance(net.minecraft.world.entity.ai.attributes.AttributeInstance delegate){this.delegate=delegate;}
    public double getValue(){return delegate.getValue();}
}
