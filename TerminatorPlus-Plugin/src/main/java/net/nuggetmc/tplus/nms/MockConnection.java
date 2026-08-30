package net.nuggetmc.tplus.nms;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.nuggetmc.tplus.compat.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.logging.Level;

@SuppressWarnings("JavaReflectionMemberAccess")
public class MockConnection extends Connection {
    private static final Field PACKET_LISTENER_FIELD;
    private static final Field DISCONNECT_LISTENER_FIELD;

    static {
        // Resolve by Mojang-mapped name first (26.x and any runtime that runs
        // Paper's unmapped internals — Paper stopped shipping reobf mappings,
        // so every modern server is effectively Mojang-mapped at runtime).
        // Fall back to type + declaration order if a future Paper bump renames
        // either field; this keeps the plugin booting rather than NPE'ing on
        // the first bot spawn, and the startup log makes the drift diagnosable.
        Field packetListenerField = findFieldByName("packetListener");
        Field disconnectListenerField = findFieldByName("disconnectListener");
        if (packetListenerField == null || disconnectListenerField == null) {
            Field[] fallback = resolveByTypeOrder();
            if (disconnectListenerField == null) disconnectListenerField = fallback[0];
            if (packetListenerField == null) packetListenerField = fallback[1];
        }
        if (disconnectListenerField == null || packetListenerField == null) {
            throw new RuntimeException(
                "MockConnection could not locate PacketListener fields on net.minecraft.network.Connection"
                + " — this is almost certainly a Paper field rename; check Connection.class.getDeclaredFields()."
            );
        }
        disconnectListenerField.setAccessible(true);
        packetListenerField.setAccessible(true);
        DISCONNECT_LISTENER_FIELD = disconnectListenerField;
        PACKET_LISTENER_FIELD = packetListenerField;

        // Loud, one-line diagnostic at classload so every Paper build bump leaves
        // a breadcrumb in the log: "did the field names change?" becomes a grep.
        try {
            Bukkit.getLogger().log(Level.INFO,
                "[TerminatorPlus] MockConnection resolved: packetListener=" + PACKET_LISTENER_FIELD.getName()
                + ", disconnectListener=" + DISCONNECT_LISTENER_FIELD.getName()
                + " on " + Connection.class.getName());
        } catch (Throwable ignored) {
            // Bukkit.getLogger() may not exist in a unit-test classpath; ignore.
        }
    }

    private static Field findFieldByName(String name) {
        try {
            return Connection.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Field[] resolveByTypeOrder() {
        Field disconnectListenerField = null;
        Field packetListenerField = null;
        for (Field field : Connection.class.getDeclaredFields()) {
            if (!field.getType().equals(PacketListener.class)) continue;
            if (disconnectListenerField == null) {
                disconnectListenerField = field;
            } else if (packetListenerField == null) {
                packetListenerField = field;
                break;
            }
        }
        return new Field[]{disconnectListenerField, packetListenerField};
    }

    public MockConnection() {
        super(PacketFlow.SERVERBOUND);
        setField("channel", new MockChannel(null));
        setField("address", new InetSocketAddress("127.0.0.1", 0));
    }

    @Override
    public void flushChannel() {
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public void send(@NotNull Packet<?> packet) {
    }

    @Override
    public void send(@NotNull Packet<?> packet, PacketSendListener sendListener) {
    }

    @Override
    public void send(@NotNull Packet<?> packet, PacketSendListener sendListener, boolean flag) {
    }

    @Override
    public void setListenerForServerboundHandshake(@NotNull PacketListener packetListener) {
        try {
            PACKET_LISTENER_FIELD.set(this, packetListener);
            DISCONNECT_LISTENER_FIELD.set(this, null);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void setField(String name, Object value) {
        try {
            Field field = Connection.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(this, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to initialise mock connection field " + name, e);
        }
    }
}
