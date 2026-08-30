package net.nuggetmc.tplus.compat.bukkit.block.data.type;
import net.nuggetmc.tplus.compat.bukkit.block.data.*;
public class Door extends BlockData implements Bisected,Openable,Waterlogged { private Half half=Half.BOTTOM; private boolean open,waterlogged; public Half getHalf(){return half;}public void setHalf(Half h){half=h;}public boolean isOpen(){return open;}public void setOpen(boolean v){open=v;}public boolean isWaterlogged(){return waterlogged;}public void setWaterlogged(boolean v){waterlogged=v;} }
