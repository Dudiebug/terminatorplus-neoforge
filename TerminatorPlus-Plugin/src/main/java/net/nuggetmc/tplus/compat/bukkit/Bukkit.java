package net.nuggetmc.tplus.compat.bukkit;

import java.util.*;
import java.util.logging.Logger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;
import net.nuggetmc.tplus.compat.bukkit.entity.EntityBridge;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;

/** Small internal server facade. It is deliberately not exported as part of the public API. */
public final class Bukkit {
    private static volatile Server server = Server.instance();
    private Bukkit() { }
    public static Server getServer() { return server; }
    public static void bind(net.minecraft.server.MinecraftServer handle) { Server.bind(handle); server = Server.instance(); }
    public static Logger getLogger() { return server.getLogger(); }
    public static boolean isPrimaryThread() { return server.getHandle() == null || server.isPrimaryThread() || getScheduler().isTickThread(); }
    public static List<World> getWorlds() { return server.getWorlds(); }
    public static Collection<Player> getOnlinePlayers() { return server.getOnlinePlayers(); }
    public static Player getPlayer(String name) { return server.getPlayer(name); }
    public static Player getPlayer(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return getOnlinePlayers().stream().filter(p -> p.getWorld() == location.getWorld() && p.getLocation().distanceSquared(location) < 1).findFirst().orElse(null);
    }
    public static CommandSender getConsoleSender() { return server.getConsoleSender(); }
    public static boolean dispatchCommand(CommandSender sender, String command) { return server.dispatchCommand(sender, command); }
    public static void broadcastMessage(String message) { for (Player player : getOnlinePlayers()) player.sendMessage(message); }
    public static World getWorld(String name) { return getWorlds().stream().filter(w -> w.getName().equals(name) || w.getName().endsWith(":" + name) || w.getName().endsWith("/" + name)).findFirst().orElse(null); }
    public static String getVersion() { return server.getVersion(); }
    public static String getName() { return server.getName(); }
    public static double[] getTPS() { return server.getTPS(); }
    public static Server.PluginManager getPluginManager() { return server.getPluginManager(); }
    public static net.nuggetmc.tplus.compat.bukkit.scheduler.BukkitScheduler getScheduler(){return net.nuggetmc.tplus.compat.bukkit.scheduler.BukkitScheduler.instance();}
    public static Player getPlayer(java.util.UUID id){return getOnlinePlayers().stream().filter(p->id!=null&&id.equals(p.getUniqueId())).findFirst().orElse(null);}
    public static net.nuggetmc.tplus.compat.bukkit.entity.Entity getEntity(java.util.UUID id){
        if(id==null||server.getHandle()==null)return null;
        for(var level:server.getHandle().getAllLevels())for(var entity:level.getAllEntities())if(id.equals(entity.getUUID()))return EntityBridge.wrap(entity);
        return null;
    }
    public static net.nuggetmc.tplus.compat.bukkit.inventory.Inventory createInventory(net.nuggetmc.tplus.compat.bukkit.inventory.InventoryHolder holder,int size,String title){return new net.nuggetmc.tplus.compat.bukkit.inventory.SimpleInventory(holder,size,title);}
    public static com.mojang.authlib.GameProfile createProfile(String name) { return new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name); }
}
