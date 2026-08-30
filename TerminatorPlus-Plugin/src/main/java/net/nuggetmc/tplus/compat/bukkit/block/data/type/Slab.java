package net.nuggetmc.tplus.compat.bukkit.block.data.type;
import net.nuggetmc.tplus.compat.bukkit.block.data.*;
public class Slab extends BlockData implements Waterlogged { public enum Type{TOP,BOTTOM,DOUBLE} private Type type=Type.BOTTOM;private boolean waterlogged;public Type getType(){return type;}public void setType(Type t){type=t;}public boolean isWaterlogged(){return waterlogged;}public void setWaterlogged(boolean v){waterlogged=v;} }
