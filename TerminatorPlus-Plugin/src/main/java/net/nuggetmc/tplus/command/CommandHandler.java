package net.nuggetmc.tplus.command;

import com.google.common.collect.Sets;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.utils.ChatUtils;
import net.nuggetmc.tplus.api.utils.DebugLogUtils;
import net.nuggetmc.tplus.command.annotation.Command;
import net.nuggetmc.tplus.command.annotation.Require;
import net.nuggetmc.tplus.command.commands.AICommand;
import net.nuggetmc.tplus.command.commands.BotCommand;
import net.nuggetmc.tplus.command.commands.BotEnvironmentCommand;
import net.nuggetmc.tplus.command.commands.MainCommand;
import net.nuggetmc.tplus.compat.bukkit.ChatColor;
import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CommandHandler {

    private final TerminatorPlus plugin;

    private final Map<String, List<String>> help;
    private final Map<String, CommandInstance> commandMap;

    public CommandHandler(TerminatorPlus plugin) {
        this.plugin = plugin;
        this.help = new HashMap<>();
        this.commandMap = new HashMap<>();
        this.registerCommands();
    }

    public Map<String, CommandInstance> getCommands() {
        return commandMap;
    }

    private void registerCommands() {
        registerCommands(
                new MainCommand(this, "terminatorplus", "The TerminatorPlus main command.", "tplus"),
                new BotCommand(this, "bot", "The root command for bot management.", "npc"),
                new AICommand(this, "ai", "The root command for bot AI training."),
                new BotEnvironmentCommand(this, "botenvironment", "Do /botenvironment help for more information.", "botenv")
        );
    }

    private void registerCommands(CommandInstance... commands) {
        for (CommandInstance command : commands) {
            commandMap.put(command.getName(), command);

            net.nuggetmc.tplus.compat.bukkit.command.PluginCommand pluginCmd = plugin.getCommand(command.getName());
            if (pluginCmd != null) {
                pluginCmd.setExecutor((sender, cmd, label, args) -> command.execute(sender, label, args));
                pluginCmd.setTabCompleter((sender, cmd, label, args) -> command.tabComplete(sender, label, args));
            }

            Method[] methods = command.getClass().getMethods();

            for (Method method : methods) {
                if (method.isAnnotationPresent(Command.class)) {
                    try {
                        method.setAccessible(true);
                    } catch (SecurityException e) {
                        DebugLogUtils.log("Failed to access method " + method.getName() + ".");
                        continue;
                    }

                    Command cmd = method.getAnnotation(Command.class);

                    String perm = "";
                    if (method.isAnnotationPresent(Require.class)) {
                        Require require = method.getAnnotation(Require.class);
                        perm = require.value();
                    }

                    String autofillName = cmd.autofill();
                    Method autofiller = null;

                    if (!autofillName.isEmpty()) {
                        for (Method m : methods) {
                            if (m.getName().equals(autofillName)) {
                                autofiller = m;
                            }
                        }
                    }

                    String methodName = cmd.name();
                    CommandMethod commandMethod = new CommandMethod(methodName, Sets.newHashSet(cmd.aliases()), cmd.desc(), perm, command, method, autofiller);

                    command.addMethod(methodName, commandMethod);
                    commandMethod.getAliases().forEach(alias -> command.addAlias(alias, methodName));
                }
            }

            setHelp(command);
        }
    }

    public CommandInstance getCommand(String name) {
        return commandMap.get(name);
    }

    /**
     * Register the complete legacy command surface with Brigadier.  A greedy
     * argument node intentionally delegates token parsing to the existing
     * reflective command implementation, preserving every alias, optional
     * argument, text argument, and tab-completion provider without maintaining
     * a second divergent command grammar.
     */
    public void registerBrigadier(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        commandMap.forEach((name, command) -> {
            registerLiteral(dispatcher, name, command);
            for (String alias : command.getAliases()) registerLiteral(dispatcher, alias, command);
        });
    }

    private void registerLiteral(CommandDispatcher<CommandSourceStack> dispatcher, String name, CommandInstance command) {
        LiteralArgumentBuilder<CommandSourceStack> root = LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(source -> new NeoForgeCommandSender(source).hasPermission("terminatorplus.manage"))
                .executes(context -> executeBrigadier(command, context, ""));
        root.then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, String>argument(
                        "arguments", StringArgumentType.greedyString())
                .suggests((context, builder) -> suggestBrigadier(command, context, builder))
                .executes(context -> executeBrigadier(command, context,
                        StringArgumentType.getString(context, "arguments"))));
        dispatcher.register(root);
    }

    private int executeBrigadier(CommandInstance command, CommandContext<CommandSourceStack> context, String rawArguments) {
        CommandSender sender = new NeoForgeCommandSender(context.getSource());
        String[] tail = tokenize(rawArguments);
        return command.execute(sender, command.getName(), tail) ? 1 : 0;
    }

    private java.util.concurrent.CompletableFuture<Suggestions> suggestBrigadier(
            CommandInstance command, CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String raw = builder.getRemaining();
        String[] tail = tokenize(raw);
        if (tail.length == 0) tail = new String[]{""};
        CommandSender sender = new NeoForgeCommandSender(context.getSource());
        for (String suggestion : command.tabComplete(sender, command.getName(), tail)) builder.suggest(suggestion);
        return builder.buildFuture();
    }

    /** Shell-like tokenization for the greedy Brigadier tail. */
    static String[] tokenize(String raw) {
        if (raw == null || raw.isBlank()) return new String[0];
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if ((ch == '\'' || ch == '"')) {
                if (quoted && ch == quote) quoted = false;
                else if (!quoted) { quoted = true; quote = ch; }
                else token.append(ch);
            } else if (Character.isWhitespace(ch) && !quoted) {
                if (token.length() > 0) { result.add(token.toString()); token.setLength(0); }
            } else {
                token.append(ch);
            }
        }
        if (token.length() > 0 || raw.endsWith(" ")) result.add(token.toString());
        return result.toArray(String[]::new);
    }


    public void sendRootInfo(CommandInstance commandInstance, CommandSender sender) {
        sender.sendMessage(ChatUtils.LINE);
        sender.sendMessage(ChatColor.GOLD + plugin.getName() + ChatUtils.BULLET_FORMATTED + ChatColor.GRAY
                + "[" + ChatColor.YELLOW + "/" + commandInstance.getName() + ChatColor.GRAY + "]");
        help.get(commandInstance.getName()).forEach(sender::sendMessage);
        sender.sendMessage(ChatUtils.LINE);
    }

    private void setHelp(CommandInstance commandInstance) {
        help.put(commandInstance.getName(), getCommandInfo(commandInstance));
    }

    private List<String> getCommandInfo(CommandInstance commandInstance) {
        List<String> output = new ArrayList<>();

        for (CommandMethod method : commandInstance.getMethods().values()) {
            if (!method.getMethod().getAnnotation(Command.class).visible() || method.getName().isEmpty()) {
                continue;
            }

            output.add(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + "/" + commandInstance.getName() + " " + method.getName()
                    + ChatUtils.BULLET_FORMATTED + method.getDescription());
        }

        return output.stream().sorted().collect(Collectors.toList());
    }
}
