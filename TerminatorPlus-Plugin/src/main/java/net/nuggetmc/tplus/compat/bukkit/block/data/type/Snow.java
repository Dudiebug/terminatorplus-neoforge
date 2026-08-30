package net.nuggetmc.tplus.compat.bukkit.block.data.type;

import net.nuggetmc.tplus.compat.bukkit.block.data.BlockData;

public final class Snow extends BlockData {
    public Snow() { super(); }
    public Snow(net.minecraft.world.level.block.state.BlockState state) { super(state); }
    public int getLayers() { return state == null ? 1 : state.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS); }
}
