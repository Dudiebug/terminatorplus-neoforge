package net.nuggetmc.tplus.compat.bukkit;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;
import net.nuggetmc.tplus.compat.bukkit.entity.EntityBridge;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.nuggetmc.tplus.compat.bukkit.plugin.Plugin;

public class Server {
    private static volatile MinecraftServer handle;
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
    public PluginManager getPluginManager() { return new PluginManager(); }
    public double[] getTPS() { return new double[]{20,20,20}; }
    public int getProfilePermissions(com.mojang.authlib.GameProfile profile) { return 4; }
    public CommandSender getConsoleSender() { return new CommandSender() { public void sendMessage(String message) { getLogger().info(message); } public boolean isOp() { return true; } }; }
    public void dispatchCommand(CommandSender sender, String command) { }
    public static final class PluginManager {
        public void registerEvents(Object listener, Plugin plugin) { }
        public void callEvent(net.nuggetmc.tplus.compat.bukkit.event.Event event) { }
        public Plugin[] getPlugins() { return new Plugin[0]; }
    }
}
