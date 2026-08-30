package net.nuggetmc.tplus.api;

import net.nuggetmc.tplus.compat.bukkit.block.Block;

/**
 * This class serves as a bridge between the API and internal code that interacts with NMS.
 */
public interface InternalBridge {
    void sendBlockDestructionPacket(short entityId, Block block, int progress);
}
