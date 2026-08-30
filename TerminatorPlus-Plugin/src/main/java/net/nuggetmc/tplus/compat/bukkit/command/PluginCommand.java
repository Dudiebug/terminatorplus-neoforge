package net.nuggetmc.tplus.compat.bukkit.command;

import java.util.Objects;
import java.util.function.BiFunction;

public class PluginCommand {
    private final String name;
    private BiFunction<CommandSender,String[],Boolean> executor;
    public PluginCommand(String name) { this.name = Objects.requireNonNull(name); }
    public String getName() { return name; }
    public boolean execute(CommandSender sender, String label, String[] args) { return executor == null || Boolean.TRUE.equals(executor.apply(sender,args)); }
    public void setExecutor(BiFunction<CommandSender,String[],Boolean> executor) { this.executor = executor; }
    public void setExecutor(Object executor) { }
    public void setTabCompleter(Object completer) { }
}
