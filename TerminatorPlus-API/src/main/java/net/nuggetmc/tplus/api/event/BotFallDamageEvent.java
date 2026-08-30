package net.nuggetmc.tplus.api.event;

import java.util.List;

import net.nuggetmc.tplus.compat.bukkit.block.Block;

import net.nuggetmc.tplus.api.Terminator;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.minecraft.core.BlockPos;
import java.util.stream.Collectors;

/** Fired when a bot is about to receive custom fall damage. */
public class BotFallDamageEvent extends Event implements ICancellableEvent {

    private final Terminator bot;
    private final List<Block> standingOn;

    private boolean cancelled;

    public BotFallDamageEvent(Terminator bot, List<Block> standingOn) {
        this.bot = bot;
        this.standingOn = standingOn;
    }

    public Terminator getBot() {
        return bot;
    }

    public List<Block> getStandingOn() {
        return standingOn;
    }

    /** Native block positions corresponding to the standing-on payload. */
    public List<BlockPos> getStandingOnPositions() {
        if (standingOn == null) return List.of();
        return standingOn.stream().filter(java.util.Objects::nonNull)
                .map(block -> new BlockPos(block.getX(), block.getY(), block.getZ()))
                .collect(Collectors.toUnmodifiableList());
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public boolean isCanceled() {
        return cancelled;
    }

    @Override
    public void setCanceled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
