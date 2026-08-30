package net.nuggetmc.tplus.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;
import net.nuggetmc.tplus.compat.bukkit.entity.EntityBridge;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;

/** Adapts a native Brigadier source to the internal command API. */
public final class NeoForgeCommandSender implements CommandSender {
    private final CommandSourceStack source;

    public NeoForgeCommandSender(CommandSourceStack source) {
        this.source = source;
    }

    public CommandSourceStack source() {
        return source;
    }

    public ServerPlayer serverPlayer() {
        return source.getEntity() instanceof ServerPlayer player ? player : null;
    }

    public Player player() {
        ServerPlayer player = serverPlayer();
        return player == null ? null : EntityBridge.player(player);
    }

    @Override
    public void sendMessage(String message) {
        source.sendSuccess(() -> Component.literal(message == null ? "" : message), false);
    }

    @Override
    public boolean isOp() {
        return source.hasPermission(4);
    }

    @Override
    public boolean hasPermission(String permission) {
        if (permission == null || permission.isBlank()) return true;
        ServerPlayer player = serverPlayer();
        if (player == null) return true; // console and command blocks are admin senders
        return NeoForgePermissions.has(player, permission);
    }

    @Override
    public String getName() {
        return source.getTextName();
    }
}
