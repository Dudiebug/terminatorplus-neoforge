package net.nuggetmc.tplus.compat.bukkit;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;
import net.nuggetmc.tplus.compat.bukkit.entity.EntityBridge;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.nuggetmc.tplus.compat.bukkit.event.Event;
import net.nuggetmc.tplus.compat.bukkit.event.EventHandler;
import net.nuggetmc.tplus.compat.bukkit.plugin.Plugin;

public class Server {
    private static volatile MinecraftServer handle;
    private static final PluginManager PLUGIN_MANAGER = new PluginManager();
    protected Server() { }
    public static Server instance() { return new Server(); }
    public static void bind(MinecraftServer server) { handle = server; }
    public MinecraftServer getHandle() { return handle; }
    public String getMinecraftVersion() { return "1.21.1"; }
    public String getVersion() { return "NeoForge 21.1.249 (Minecraft 1.21.1)"; }
    public String getName() { return "NeoForge"; }
    public File getWorldContainer() { return new File("."); }
    public Logger getLogger() { return Logger.getLogger("Minecraft"); }
    public List<World> getWorlds() { if (handle == null) return List.of(); List<World> out=new ArrayList<>(); for(var level:handle.getAllLevels())out.add(new World(level)); return out; }
    public Collection<Player> getOnlinePlayers() { if (handle == null) return List.of(); return handle.getPlayerList().getPlayers().stream().map(EntityBridge::player).toList(); }
    public Player getPlayer(String name) { return getOnlinePlayers().stream().filter(p -> p.getName().equalsIgnoreCase(name)).findFirst().orElse(null); }
    public PluginManager getPluginManager() { return PLUGIN_MANAGER; }
    public double[] getTPS() { return new double[]{20,20,20}; }
    public int getProfilePermissions(com.mojang.authlib.GameProfile profile) { return 4; }
    public CommandSender getConsoleSender() { return new CommandSender() { public void sendMessage(String message) { getLogger().info(message); } public boolean isOp() { return true; } }; }
    public boolean dispatchCommand(CommandSender sender, String command) { return false; }
    public int getSpawnRadius() { return 0; }
    public static final class PluginManager {
        private final java.util.concurrent.CopyOnWriteArrayList<Object> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

        public void registerEvents(Object listener, Plugin plugin) {
            if (listener != null && !listeners.contains(listener)) listeners.add(listener);
        }

        public void unregisterAll() {
            listeners.clear();
        }

        public void callEvent(Event event) {
            if (event == null) return;
            // Public TerminatorPlus events are NeoForge events as well. Posting
            // first lets external NeoForge consumers cancel or mutate them;
            // the preserved internal listeners then observe the final state.
            if (event instanceof net.neoforged.bus.api.Event neoEvent) {
                NeoForge.EVENT_BUS.post(neoEvent);
            }
            for (Object listener : listeners) {
                for (java.lang.reflect.Method method : listener.getClass().getMethods()) {
                    EventHandler handler = method.getAnnotation(EventHandler.class);
                    if (handler == null || method.getParameterCount() != 1
                            || !method.getParameterTypes()[0].isAssignableFrom(event.getClass())) continue;
                    if (handler.ignoreCancelled() && event instanceof net.nuggetmc.tplus.compat.bukkit.event.Cancellable cancellable
                            && cancellable.isCancelled()) continue;
                    try {
                        method.invoke(listener, event);
                    } catch (ReflectiveOperationException ignored) {
                    }
                }
            }
        }

        public Plugin[] getPlugins() { return new Plugin[0]; }
    }
}
