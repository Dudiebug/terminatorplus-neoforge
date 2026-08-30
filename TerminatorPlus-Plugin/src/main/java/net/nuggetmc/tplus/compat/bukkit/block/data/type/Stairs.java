package net.nuggetmc.tplus.compat.bukkit.block.data.type;
import net.nuggetmc.tplus.compat.bukkit.block.data.*;
public class Stairs extends BlockData implements Bisected,Waterlogged { private Half half=Half.BOTTOM;private boolean waterlogged;public Half getHalf(){return half;}public void setHalf(Half h){half=h;}public boolean isWaterlogged(){return waterlogged;}public void setWaterlogged(boolean v){waterlogged=v;} }
