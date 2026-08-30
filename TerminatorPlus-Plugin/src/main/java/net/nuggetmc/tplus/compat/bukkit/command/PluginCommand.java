package net.nuggetmc.tplus.compat.bukkit.command;

import java.util.Objects;
import java.util.function.BiFunction;

public class PluginCommand {
    private final String name;
    private BiFunction<CommandSender,String[],Boolean> executor;
    public PluginCommand(String name) { this.name = Objects.requireNonNull(name); }
    public String getName() { return name; }
    public boolean execute(CommandSender sender, String label, String[] args) { if (commandExecutor != null) return commandExecutor.onCommand(sender,this,label,args); return executor == null || Boolean.TRUE.equals(executor.apply(sender,args)); }
    @FunctionalInterface public interface CommandExecutor { boolean onCommand(CommandSender sender, PluginCommand command, String label, String[] args); }
    @FunctionalInterface public interface TabCompleter { java.util.List<String> onTabComplete(CommandSender sender, PluginCommand command, String label, String[] args); }
    private CommandExecutor commandExecutor;
    private TabCompleter tabCompleter;
    public void setExecutor(CommandExecutor executor) { this.commandExecutor=executor; }
    public void setTabCompleter(TabCompleter completer) { this.tabCompleter=completer; }
    public java.util.List<String> tabComplete(CommandSender sender,String label,String[] args){return tabCompleter==null?java.util.List.of():tabCompleter.onTabComplete(sender,this,label,args);}
    public void setExecutor(BiFunction<CommandSender,String[],Boolean> executor) { this.executor = executor; }
    public void setExecutor(Object executor) { }
    public void setTabCompleter(Object completer) { }
}
