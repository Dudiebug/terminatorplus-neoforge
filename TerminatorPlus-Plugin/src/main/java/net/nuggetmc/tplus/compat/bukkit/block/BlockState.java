package net.nuggetmc.tplus.compat.bukkit.block;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.block.data.BlockData;
public final class BlockState { private final Block owner;private final net.minecraft.world.level.block.state.BlockState nms; public BlockState(Block owner,net.minecraft.world.level.block.state.BlockState nms){this.owner=owner;this.nms=nms;} public Material getType(){return owner.getType();}public BlockData getBlockData(){return owner.getBlockData();}public Block getBlock(){return owner;}public net.minecraft.world.level.block.state.BlockState getNms(){return nms;} }
