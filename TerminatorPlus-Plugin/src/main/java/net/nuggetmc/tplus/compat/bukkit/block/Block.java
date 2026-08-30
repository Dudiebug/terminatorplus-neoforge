package net.nuggetmc.tplus.compat.bukkit.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.nuggetmc.tplus.compat.bukkit.*;
import net.nuggetmc.tplus.compat.bukkit.block.data.BlockData;

public class Block {
    private final ServerLevel level; private final BlockPos pos;
    public Block(ServerLevel level,BlockPos pos){this.level=level;this.pos=pos.immutable();} public ServerLevel getHandleLevel(){return level;} public BlockPos getPos(){return pos;}
    public World getWorld(){return new World(level);} public Location getLocation(){return new Location(getWorld(),pos.getX(),pos.getY(),pos.getZ());} public int getX(){return pos.getX();} public int getY(){return pos.getY();} public int getZ(){return pos.getZ();}
    public Material getType(){return material(level.getBlockState(pos));} public BlockData getBlockData(){return new BlockData(level.getBlockState(pos));} public BlockState getNmsState(){return level.getBlockState(pos);}
    public void setType(Material material){setType(material,false);} public void setType(Material material,boolean applyPhysics){if(material!=null&&material.item()!=null){var block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(material.name().toLowerCase(java.util.Locale.ROOT)));level.setBlock(pos,block.defaultBlockState(),3);}}
    public Block getRelative(BlockFace face){return new Block(level,pos.offset(face.getModX(),face.getModY(),face.getModZ()));} public Block getRelative(int x,int y,int z){return new Block(level,pos.offset(x,y,z));}
    public net.nuggetmc.tplus.compat.bukkit.block.BlockState getState(){return new net.nuggetmc.tplus.compat.bukkit.block.BlockState(this,level.getBlockState(pos));} public boolean isLiquid(){return !level.getFluidState(pos).isEmpty();} public byte getLightLevel(){return (byte)level.getMaxLocalRawBrightness(pos);}
    public Object getBoundingBox(){return level.getBlockState(pos).getShape(level,pos);} public Material[] getDrops(){return new Material[]{getType()};} public boolean isPassable(){return getType().isAir()||isLiquid();} public boolean canPlace(Object data){return isPassable();}
    public void setBlockData(net.nuggetmc.tplus.compat.bukkit.block.data.BlockData data,boolean applyPhysics){if(data!=null&&data.nms()!=null)level.setBlock(pos,data.nms(),3);} public void setBlockData(Object data,boolean applyPhysics){if(data instanceof net.nuggetmc.tplus.compat.bukkit.block.data.BlockData b)setBlockData(b,applyPhysics);} public void setBlockData(Object data){setBlockData(data,false);}
    public boolean breakNaturally(){return level.destroyBlock(pos,true);} public boolean breakNaturally(net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack tool){return level.destroyBlock(pos,true);}
    private static Material material(BlockState state){String id=state.getBlockHolder().unwrapKey().map(k->k.location().getPath().toUpperCase(java.util.Locale.ROOT)).orElse("AIR");try{return Material.valueOf(id);}catch(IllegalArgumentException e){return state.isAir()?Material.AIR:Material.STONE;}}
}
