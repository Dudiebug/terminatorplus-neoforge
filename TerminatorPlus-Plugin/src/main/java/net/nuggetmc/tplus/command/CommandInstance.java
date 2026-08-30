package net.nuggetmc.tplus.command;

import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.utils.ChatUtils;
import net.nuggetmc.tplus.command.annotation.Arg;
import net.nuggetmc.tplus.command.annotation.Command;
import net.nuggetmc.tplus.command.annotation.OptArg;
import net.nuggetmc.tplus.command.annotation.TextArg;
import net.nuggetmc.tplus.command.exception.ArgCountException;
import net.nuggetmc.tplus.command.exception.ArgParseException;
import net.nuggetmc.tplus.command.exception.NonPlayerException;
import net.nuggetmc.tplus.compat.bukkit.Bukkit;
import net.nuggetmc.tplus.compat.bukkit.ChatColor;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.World;
import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;
import net.nuggetmc.tplus.compat.bukkit.command.defaults.BukkitCommand;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.stream.Collectors;

public abstract class CommandInstance extends BukkitCommand {

    private static final String MANAGE_PERMISSION = "terminatorplus.manage";
    protected final CommandHandler commandHandler;
    private final Map<String, CommandMethod> methods;
    private final Map<String, String> aliasesToNames;

    public CommandInstance(CommandHandler handler, String name, String description, @Nullable String... aliases) {
        super(name, description, "", aliases == null ? new ArrayList<>() : Arrays.asList(aliases));

        this.commandHandler = handler;
        this.methods = new HashMap<>();
        this.aliasesToNames = new HashMap<>();
    }

    public static String getArgumentName(Parameter parameter) {
        if (parameter.isAnnotationPresent(OptArg.class)) {
            OptArg arg = parameter.getAnnotation(OptArg.class);

            if (!arg.value().isEmpty()) {
                return "[" + ChatUtils.camelToDashed(arg.value()) + "]";
            }
        } else if (parameter.isAnnotationPresent(Arg.class)) {
            Arg arg = parameter.getAnnotation(Arg.class);

            if (!arg.value().isEmpty()) {
                return "<" + ChatUtils.camelToDashed(arg.value()) + ">";
            }
        }

        return "<" + ChatUtils.camelToDashed(parameter.getName()) + ">";
    }

    public Map<String, CommandMethod> getMethods() {
        return methods;
    }

    protected void addMethod(String name, CommandMethod method) {
        methods.put(name, method);
    }

    protected void addAlias(String alias, String name) {
        aliasesToNames.put(alias, name);
    }

    protected static Location parseSpawnLocation(CommandSender sender, String loc) {
        Location location = sender instanceof Player
                ? ((Player) sender).getLocation()
                : new Location(Bukkit.getWorlds().get(0), 0, 0, 0);
        if (loc != null && !loc.isEmpty()) {
            Player player = Bukkit.getPlayer(loc);
            if (player != null) {
                return player.getLocation();
            }

            String[] split = loc.split(" ");
            if (split.length < 3) {
                sender.sendMessage("The location '" + ChatColor.YELLOW + loc + ChatColor.RESET + "' is not valid!");
                return null;
            }
            try {
                double x = Double.parseDouble(split[0]);
                double y = Double.parseDouble(split[1]);
                double z = Double.parseDouble(split[2]);
                World world = Bukkit.getWorld(split.length >= 4 ? split[3] : location.getWorld().getName());
                return new Location(world, x, y, z);
            } catch (NumberFormatException e) {
                sender.sendMessage("The location '" + ChatColor.YELLOW + loc + ChatColor.RESET + "' is not valid!");
                return null;
            }
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("Spawning bot at 0, 0, 0 in world " + location.getWorld().getName()
                    + " because no location was specified.");
        }
        return location;
    }

    protected static double parseDoubleOrRelative(String pos, Location loc, int type) {
        if (loc == null || pos.length() == 0 || pos.charAt(0) != '~')
            return Double.parseDouble(pos);
        double relative = pos.length() == 1 ? 0 : Double.parseDouble(pos.substring(1));
        switch (type) {
            case 0:
                return relative + Math.round(loc.getX() * 1000) / 1000D;
            case 1:
                return relative + Math.round(loc.getY() * 1000) / 1000D;
            case 2:
                return relative + Math.round(loc.getZ() * 1000) / 1000D;
            default:
                return 0;
        }
    }

    @Override
    public boolean execute(@Nonnull CommandSender sender, @Nonnull String label, @Nonnull String[] args) {
        if (!sender.hasPermission(MANAGE_PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command. (Check if you are OP.)");
            return false;
        }
        if (!TerminatorPlus.isCorrectVersion()) {
            sender.sendMessage(ChatColor.RED + "You are not running the correct server version of Minecraft!");
            sender.sendMessage(ChatColor.RED + "You are using MC server version " + TerminatorPlus.getMcVersion() + " but this plugin requires " + TerminatorPlus.REQUIRED_VERSION);
            return false;
        }

        CommandMethod method;

        if (args.length == 0) {
            method = methods.get("");
        } else {
            String methodName = aliasesToNames.getOrDefault(args[0], args[0]);
            method = methods.containsKey(methodName) ? methods.get(methodName) : methods.get("");
        }

        if (method == null) {
            sender.sendMessage(ChatColor.RED + "There is no root command present for the " + ChatColor.YELLOW + getName() + ChatColor.RED + " command.");
            return true;
        }
        String requiredPermission = method.getPermission();
        if (requiredPermission != null && !requiredPermission.isEmpty() && !sender.hasPermission(requiredPermission)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this subcommand.");
            sender.sendMessage(ChatColor.RED + "Required: " + ChatColor.YELLOW + requiredPermission);
            return true;
        }

        List<String> arguments = new ArrayList<>(Arrays.asList(args));

        if (arguments.size() > 0) {
            arguments.remove(0);
        }

        List<Object> parsedArguments = new ArrayList<>();

        int index = 0;

        try {
            for (Parameter parameter : method.getMethod().getParameters()) {
                Class<?> type = parameter.getType();

                boolean required = !parameter.isAnnotationPresent(OptArg.class);

                if (type == CommandSender.class) {
                    parsedArguments.add(sender);
                } else if (type == Player.class) {
                    if (!(sender instanceof Player)) {
                        throw new NonPlayerException();
                    }

                    parsedArguments.add(sender);
                } else if (type == List.class) {
                    parsedArguments.add(arguments);
                } else {
                    if (parameter.isAnnotationPresent(TextArg.class)) {
                        if (index >= arguments.size()) {
                            parsedArguments.add("");
                        } else {
                            parsedArguments.add(String.join(" ", arguments.subList(index, arguments.size())));
                        }

                        continue;
                    }

                    if (index >= arguments.size() && required) {
                        throw new ArgCountException();
                    }

                    String arg;

                    if (index >= arguments.size()) {
                        arg = null;
                    } else {
                        arg = arguments.get(index);
                    }

                    index++;

                    if (type == String.class) {
                        parsedArguments.add(arg);
                    } else if (type == int.class) {
                        if (arg == null) {
                            parsedArguments.add(0);
                            continue;
                        }

                        try {
                            parsedArguments.add(Integer.parseInt(arg));
                        } catch (NumberFormatException e) {
                            throw new ArgParseException(parameter);
                        }
                    } else if (type == double.class) {
                        if (arg == null) {
                            parsedArguments.add(0);
                            continue;
                        }

                        try {
                            parsedArguments.add(Double.parseDouble(arg));
                        } catch (NumberFormatException e) {
                            throw new ArgParseException(parameter);
                        }
                    } else if (type == float.class) {
                        if (arg == null) {
                            parsedArguments.add(0);
                            continue;
                        }

                        try {
                            parsedArguments.add(Float.parseFloat(arg));
                        } catch (NumberFormatException e) {
                            throw new ArgParseException(parameter);
                        }
                    } else if (type == boolean.class) {
                        if (arg == null) {
                            parsedArguments.add(false);
                            continue;
                        }

                        if (arg.equalsIgnoreCase("true") || arg.equalsIgnoreCase("false")) {
                            parsedArguments.add(Boolean.parseBoolean(arg));
                        } else {
                            throw new ArgParseException(parameter);
                        }
                    } else {
                        parsedArguments.add(arg);
                    }
                }
            }
        } catch (NonPlayerException e) {
            sender.sendMessage("This is a player-only command.");
            return true;
        } catch (ArgParseException e) {
            Parameter parameter = e.getParameter();
            String name = getArgumentName(parameter);
            sender.sendMessage("The parameter " + ChatColor.YELLOW + name + ChatColor.RESET + " must be of type " + ChatColor.YELLOW + parameter.getType().toString() + ChatColor.RESET + ".");
            return true;
        } catch (ArgCountException e) {
            List<String> usageArgs = new ArrayList<>();

            Arrays.stream(method.getMethod().getParameters()).forEach(parameter -> {
                Class<?> type = parameter.getType();

                if (type != CommandSender.class && type != Player.class) {
                    usageArgs.add(getArgumentName(parameter));
                }
            });

            sender.sendMessage("Command Usage: " + net.nuggetmc.tplus.compat.bukkit.ChatColor.YELLOW + "/" + getName() + (method.getName().isEmpty() ? "" : " " + method.getName())
                    + " " + String.join(" ", usageArgs));
            return true;
        }

        try {
            method.getMethod().invoke(method.getHandler(), parsedArguments.toArray());
        } catch (InvocationTargetException | IllegalAccessException e) {
            sender.sendMessage(ChatColor.RED + "Failed to perform command.");
            e.printStackTrace();
        }

        return true;
    }

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public List<String> tabComplete(@Nonnull CommandSender sender, @Nonnull String label, @Nonnull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> result = methods.values().stream()
                    .filter(method -> visibleTo(sender, method))
                    .map(CommandMethod::getName)
                    .filter(name -> !name.isEmpty() && name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .collect(Collectors.toList());
            if (result.isEmpty()) {
                methods.values().stream()
                        .filter(method -> visibleTo(sender, method))
                        .flatMap(method -> method.getAliases().stream())
                        .filter(alias -> alias.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .forEach(result::add);
            }
            return result;
        }

        if (args.length > 1) {
            CommandMethod commandMethod = methods.get(args[0]);
            if (commandMethod == null)
                commandMethod = methods.values().stream().filter(m -> m.getAliases().contains(args[0])).findFirst().orElse(null);
            if (commandMethod == null) return new ArrayList<>();
            Method autofiller = commandMethod.getAutofiller();

            if (autofiller != null) {
                try {
                    String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
                    return ((List<String>) autofiller.invoke(commandMethod.getHandler(), sender, args)).stream()
                            .filter(c -> c.toLowerCase(Locale.ROOT).contains(prefix)).collect(Collectors.toList());
                } catch (InvocationTargetException | IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }

        return new ArrayList<>();
    }

    private static boolean visibleTo(CommandSender sender, CommandMethod method) {
        Command command = method.getMethod().getAnnotation(Command.class);
        if (command == null || !command.visible()) return false;
        String permission = method.getPermission();
        return permission == null || permission.isEmpty() || sender.hasPermission(permission);
    }
}
