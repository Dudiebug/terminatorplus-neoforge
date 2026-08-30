package net.nuggetmc.tplus.compat.bukkit.command.defaults;

import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;

/** Compatibility command base; NeoForge command registration is performed by the mod bootstrap. */
public abstract class BukkitCommand {
    private final String name;
    protected BukkitCommand(String name) { this.name = name; }
    public String getName() { return name; }
    public boolean execute(CommandSender sender, String label, String[] args) { return false; }
    public java.util.List<String> tabComplete(CommandSender sender, String alias, String[] args) { return java.util.List.of(); }
}
