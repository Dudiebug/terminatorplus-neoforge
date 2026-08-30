package net.nuggetmc.tplus.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.nuggetmc.tplus.api.InternalBridge;
import net.nuggetmc.tplus.compat.bukkit.block.Block;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;

public class InternalBridgeImpl implements InternalBridge {
    @Override
    public void sendBlockDestructionPacket(short entityId, Block block, int progress) {
        ClientboundBlockDestructionPacket crack = new ClientboundBlockDestructionPacket(entityId, new BlockPos(block.getX(), block.getY(), block.getZ()), progress);
        for (Player all : block.getLocation().getNearbyPlayers(64)) {
            all.getHandle().connection.send(crack);
        }
    }
}
