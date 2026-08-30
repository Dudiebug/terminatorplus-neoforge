package net.nuggetmc.tplus.compat.bukkit.plugin.java;

import net.nuggetmc.tplus.compat.bukkit.plugin.Plugin;

/** Legacy facade used internally; NeoForge instantiates the actual mod class. */
public class JavaPlugin implements Plugin {
    public void saveDefaultConfig() { }
    public void onEnable() { }
    public void onDisable() { }
}
