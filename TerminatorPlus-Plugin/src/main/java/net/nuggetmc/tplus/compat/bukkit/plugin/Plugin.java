package net.nuggetmc.tplus.compat.bukkit.plugin;

import java.io.File;
import java.util.logging.Logger;
import net.nuggetmc.tplus.compat.bukkit.Server;
import net.nuggetmc.tplus.compat.bukkit.command.PluginCommand;
import net.nuggetmc.tplus.compat.bukkit.configuration.file.FileConfiguration;
import net.nuggetmc.tplus.compat.bukkit.scheduler.BukkitScheduler;

public interface Plugin {
    default File getDataFolder() { return new File("config/terminatorplus"); }
    default FileConfiguration getConfig() { return new FileConfiguration(); }
    default void saveConfig() { }
    default void reloadConfig() { }
    default Logger getLogger() { return Logger.getLogger(getClass().getName()); }
    default Server getServer() { return Server.instance(); }
    default PluginCommand getCommand(String name) { return new PluginCommand(name); }
    default BukkitScheduler getScheduler() { return BukkitScheduler.instance(); }
    default String getName() { return getClass().getSimpleName(); }
    default PluginDescriptionFile getDescription(){return new PluginDescriptionFile(getName(),"6.2.7-neoforge-1.21.1");}
}
