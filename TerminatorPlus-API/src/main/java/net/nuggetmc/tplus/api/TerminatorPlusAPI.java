package net.nuggetmc.tplus.api;

import net.neoforged.neoforge.common.NeoForge;

public class TerminatorPlusAPI {
    private static InternalBridge internalBridge;
    private static BotManager botManager;

    public static InternalBridge getInternalBridge() {
        return internalBridge;
    }

    public static void setInternalBridge(InternalBridge internalBridge) {
        TerminatorPlusAPI.internalBridge = internalBridge;
    }

    public static BotManager getBotManager() {
        return botManager;
    }

    public static void setBotManager(BotManager botManager) {
        TerminatorPlusAPI.botManager = botManager;
    }

    /** Register a native NeoForge event subscriber for TerminatorPlus events. */
    public static void registerListener(Object listener) {
        if (listener != null) NeoForge.EVENT_BUS.register(listener);
    }

    /** Remove a previously registered native event subscriber. */
    public static void unregisterListener(Object listener) {
        if (listener != null) NeoForge.EVENT_BUS.unregister(listener);
    }

    public static net.neoforged.bus.api.IEventBus getEventBus() {
        return NeoForge.EVENT_BUS;
    }
}
