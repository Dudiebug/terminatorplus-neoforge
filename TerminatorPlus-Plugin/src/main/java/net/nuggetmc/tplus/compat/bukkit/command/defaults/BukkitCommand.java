package net.nuggetmc.tplus.compat.bukkit.command.defaults;

import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;

/** Compatibility command base; NeoForge command registration is performed by the mod bootstrap. */
public abstract class BukkitCommand {
    private final String name;
    private final String description;
    private final java.util.List<String> aliases;
    protected BukkitCommand(String name) { this(name,"","",java.util.List.of()); }
    protected BukkitCommand(String name, String description, String permission, java.util.List<String> aliases) {
        this.name = name;
        this.description=description==null?"":description;
        this.aliases = aliases == null ? java.util.List.of() : java.util.List.copyOf(aliases);
    }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public java.util.List<String> getAliases() { return aliases; }
    public boolean execute(CommandSender sender, String label, String[] args) { return false; }
    public java.util.List<String> tabComplete(CommandSender sender, String alias, String[] args) { return java.util.List.of(); }
}
