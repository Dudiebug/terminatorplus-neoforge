package net.nuggetmc.tplus;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

import java.util.UUID;

/** The two operator-default permission nodes exposed by the mod. */
public final class NeoForgePermissions {
    public static final PermissionNode<Boolean> MANAGE = node("manage", "Manage TerminatorPlus bots");
    public static final PermissionNode<Boolean> ADMIN = node("admin", "Use TerminatorPlus administrative commands");
    private static volatile boolean installed;

    private NeoForgePermissions() {
    }

    static synchronized void install() {
        if (installed) return;
        installed = true;
        NeoForge.EVENT_BUS.addListener(NeoForgePermissions::gather);
    }

    private static PermissionNode<Boolean> node(String name, String description) {
        return new PermissionNode<>(TerminatorPlus.MOD_ID, name, PermissionTypes.BOOLEAN,
                (player, uuid, context) -> player != null && player.hasPermissions(4))
                .setInformation(net.minecraft.network.chat.Component.literal(name),
                        net.minecraft.network.chat.Component.literal(description));
    }

    private static void gather(PermissionGatherEvent.Nodes event) {
        event.addNodes(MANAGE, ADMIN);
    }

    public static boolean has(ServerPlayer player, String node) {
        if (player == null) return false;
        PermissionNode<Boolean> permission;
        if (MANAGE.getNodeName().equals(node) || (TerminatorPlus.MOD_ID + ".manage").equals(node)) {
            permission = MANAGE;
        } else if (ADMIN.getNodeName().equals(node) || (TerminatorPlus.MOD_ID + ".admin").equals(node)) {
            permission = ADMIN;
        } else {
            return false;
        }
        try {
            return PermissionAPI.getPermission(player, permission);
        } catch (RuntimeException ignored) {
            return player.hasPermissions(4);
        }
    }
}
