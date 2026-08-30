package net.nuggetmc.tplus.command.commands;

import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.legacyagent.EnumTargetGoal;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyMats;
import net.nuggetmc.tplus.api.utils.ChatUtils;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotManagerImpl;
import net.nuggetmc.tplus.bot.loadout.BotInventory;
import net.nuggetmc.tplus.bot.navigation.MovementV2Settings;
import net.nuggetmc.tplus.bot.preset.BotPreset;
import net.nuggetmc.tplus.bot.preset.PresetManager;
import net.nuggetmc.tplus.command.CommandHandler;
import net.nuggetmc.tplus.command.CommandInstance;
import net.nuggetmc.tplus.command.annotation.*;
import net.nuggetmc.tplus.utils.Debugger;
import net.nuggetmc.tplus.compat.bukkit.*;
import net.nuggetmc.tplus.compat.bukkit.block.Block;
import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.nuggetmc.tplus.compat.bukkit.inventory.EquipmentSlot;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.inventory.meta.PotionMeta;
import net.nuggetmc.tplus.compat.bukkit.block.data.Waterlogged;
import net.nuggetmc.tplus.compat.bukkit.potion.PotionType;
import net.nuggetmc.tplus.compat.bukkit.util.BoundingBox;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

import java.text.DecimalFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

public class BotCommand extends CommandInstance {

    private static final String ADMIN_PERMISSION = "terminatorplus.admin";
    static final double DEFAULT_SCATTER_RADIUS = 8.0;
    static final double MIN_SCATTER_RADIUS = 1.0;
    private static final double SCATTER_MIN_SEPARATION = 0.75;
    private static final int SCATTER_FALLBACK_RANGE = 4;

    private final TerminatorPlus plugin;
    private final BotManagerImpl manager;
    private final LegacyAgent agent;
    private final DecimalFormat formatter;
    private final Map<String, ItemStack[]> armorTiers;
    private AICommand aiManager;

    public BotCommand(CommandHandler handler, String name, String description, String... aliases) {
        super(handler, name, description, aliases);

        this.plugin = TerminatorPlus.getInstance();
        this.manager = plugin.getManager();
        this.agent = (LegacyAgent) manager.getAgent();
        this.formatter = new DecimalFormat("0.##");
        this.armorTiers = new HashMap<>();

        this.armorTierSetup();
    }

    @Command
    public void root(CommandSender sender) {
        if (sender instanceof Player player) {
            plugin.getManagementUI().openMain(player);
        } else {
            commandHandler.sendRootInfo(this, sender);
        }
    }

    @Command(
            name = "spawn",
            desc = "Spawn one or more bots.",
            autofill = "spawnAutofill"
    )
    public void spawn(CommandSender sender, List<String> args) {
        if (args.isEmpty()) {
            sendGroupHelp(sender, "/bot spawn", "single <name> [skin] [location]", "multiple <amount> <name> [skin] [location]");
            return;
        }

        switch (args.get(0).toLowerCase(Locale.ROOT)) {
            case "single" -> {
                if (args.size() < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /bot spawn single <name> [skin] [location]");
                    return;
                }
                String skin = args.size() >= 3 ? args.get(2) : null;
                String location = args.size() >= 4 ? String.join(" ", args.subList(3, args.size())) : null;
                createBots(sender, args.get(1), skin, location, 1);
            }
            case "multiple" -> {
                if (args.size() < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /bot spawn multiple <amount> <name> [skin] [location]");
                    return;
                }
                int amount;
                try {
                    amount = Integer.parseInt(args.get(1));
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Amount must be a number.");
                    return;
                }
                String skin = args.size() >= 4 ? args.get(3) : null;
                String location = args.size() >= 5 ? String.join(" ", args.subList(4, args.size())) : null;
                createBots(sender, args.get(2), skin, location, amount);
            }
            default -> sendGroupHelp(sender, "/bot spawn", "single <name> [skin] [location]", "multiple <amount> <name> [skin] [location]");
        }
    }

    public List<String> spawnAutofill(CommandSender sender, String[] args) {
        return args.length == 2 ? List.of("single", "multiple") : List.of();
    }

    @Command(
            name = "inspect",
            desc = "Inspect bots and their status.",
            autofill = "inspectAutofill"
    )
    public void inspect(CommandSender sender, List<String> args) {
        if (args.isEmpty()) {
            sendGroupHelp(sender, "/bot inspect", "list", "info [bot-name]", "weapons [bot-name]");
            return;
        }

        switch (args.get(0).toLowerCase(Locale.ROOT)) {
            case "list" -> count(sender);
            case "info" -> info(sender, args.size() >= 2 ? args.get(1) : null);
            case "weapons" -> weapons(sender, args.size() >= 2 ? args.get(1) : null);
            default -> sendGroupHelp(sender, "/bot inspect", "list", "info [bot-name]", "weapons [bot-name]");
        }
    }

    public List<String> inspectAutofill(CommandSender sender, String[] args) {
        if (args.length == 2) return List.of("list", "info", "weapons");
        if (args.length == 3 && (args[1].equalsIgnoreCase("info") || args[1].equalsIgnoreCase("weapons"))) {
            return manager.fetchNames();
        }
        return List.of();
    }

    @Command(
            name = "move",
            desc = "Move bots as a group.",
            autofill = "moveAutofill"
    )
    public void move(CommandSender sender, List<String> args) {
        if (args.isEmpty()) {
            sendGroupHelp(sender, "/bot move", "gather", "scatter [radius]");
            return;
        }

        switch (args.get(0).toLowerCase(Locale.ROOT)) {
            case "gather" -> gather(sender);
            case "scatter" -> {
                if (args.size() > 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /bot move scatter [radius]");
                    return;
                }
                scatter(sender, args.size() == 2 ? args.get(1) : null);
            }
            default -> sendGroupHelp(sender, "/bot move", "gather", "scatter [radius]");
        }
    }

    public List<String> moveAutofill(CommandSender sender, String[] args) {
        return args.length == 2 ? List.of("gather", "scatter") : List.of();
    }

    @Command(
            name = "equipment",
            desc = "Manage bot equipment, inventories, and loadouts.",
            autofill = "equipmentAutofill"
    )
    public void equipment(CommandSender sender, List<String> args) {
        if (args.isEmpty()) {
            sendGroupHelp(sender, "/bot equipment", "inventory <bot-name>", "give <item> [bot-name] [slot]",
                    "armor <tier>", "loadout <name> [bot-name]", "mixed-loadout <mix> [bot-prefix]");
            return;
        }

        switch (args.get(0).toLowerCase(Locale.ROOT)) {
            case "inventory" -> {
                if (args.size() < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /bot equipment inventory <bot-name>");
                    return;
                }
                inventory(sender, args.get(1));
            }
            case "give" -> give(sender, args.subList(1, args.size()));
            case "armor" -> {
                if (args.size() < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /bot equipment armor <tier>");
                    return;
                }
                armor(sender, args.get(1));
            }
            case "loadout" -> {
                if (args.size() < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /bot equipment loadout <name> [bot-name]");
                    return;
                }
                loadout(sender, args.get(1), args.size() >= 3 ? args.get(2) : null);
            }
            case "mixed-loadout", "loadoutmix" -> {
                if (args.size() < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /bot equipment mixed-loadout <mix> [bot-prefix]");
                    return;
                }
                loadoutMix(sender, args.get(1), args.size() >= 3 ? args.get(2) : null);
            }
            default -> sendGroupHelp(sender, "/bot equipment", "inventory <bot-name>", "give <item> [bot-name] [slot]",
                    "armor <tier>", "loadout <name> [bot-name]", "mixed-loadout <mix> [bot-prefix]");
        }
    }

    public List<String> equipmentAutofill(CommandSender sender, String[] args) {
        if (args.length == 2) return List.of("inventory", "give", "armor", "loadout", "mixed-loadout");
        if (args.length == 3) {
            return switch (args[1].toLowerCase(Locale.ROOT)) {
                case "inventory" -> manager.fetchNames();
                case "give" -> giveAutofill(sender, new String[]{"give", args[2]});
                case "armor" -> new ArrayList<>(armorTiers.keySet());
                case "loadout" -> Arrays.asList(LOADOUT_NAMES);
                case "mixed-loadout" -> Arrays.asList(LOADOUT_MIX_NAMES);
                default -> List.of();
            };
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("give")) {
            return manager.fetchNames();
        }
        if (args.length == 5 && args[1].equalsIgnoreCase("give")) {
            return giveAutofill(sender, new String[]{"give", args[2], args[3], args[4]});
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("loadout")) return manager.fetchNames();
        if (args.length == 4 && args[1].equalsIgnoreCase("mixed-loadout")) return manager.fetchNames();
        return List.of();
    }

    @Command(
            name = "admin",
            desc = "Administrative bot actions.",
            autofill = "adminAutofill"
    )
    @Require(ADMIN_PERMISSION)
    public void admin(CommandSender sender, List<String> args) {
        if (args.size() == 1 && args.get(0).equalsIgnoreCase("reset")) {
            reset(sender);
            return;
        }
        sendGroupHelp(sender, "/bot admin", "reset");
    }

    public List<String> adminAutofill(CommandSender sender, String[] args) {
        return args.length == 2 ? List.of("reset") : List.of();
    }

    @Command(
            name = "environment",
            desc = "Inspect and configure bot environment data.",
            autofill = "environmentAutofill"
    )
    public void environment(CommandSender sender, List<String> args) {
        BotEnvironmentCommand environment = environmentCommand();
        if (environment == null) {
            sender.sendMessage(ChatColor.RED + "Environment commands are unavailable.");
            return;
        }
        environment.dispatchCanonical(sender, args);
    }

    public List<String> environmentAutofill(CommandSender sender, String[] args) {
        if (args.length == 2) return List.of("inspect", "material", "solid-block", "custom-mob", "mob-list-mode");
        if (args.length == 3 && (args[1].equalsIgnoreCase("inspect") || args[1].equalsIgnoreCase("material"))) {
            return List.of("material");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("solid-block")) return List.of("add", "remove", "list", "clear");
        if (args.length == 3 && args[1].equalsIgnoreCase("custom-mob")) return List.of("add", "remove", "list", "clear");
        if (args.length == 3 && args[1].equalsIgnoreCase("mob-list-mode")) return List.of("get", "set");
        if (args.length == 4 && args[1].equalsIgnoreCase("mob-list-mode") && args[2].equalsIgnoreCase("set")) {
            return Arrays.stream(net.nuggetmc.tplus.api.agent.legacyagent.CustomListMode.values())
                    .map(mode -> mode.name().toLowerCase(Locale.ROOT)).toList();
        }
        return List.of();
    }

    @Command(
            name = "create",
            desc = "Create a bot.",
            visible = false
    )
    public void create(CommandSender sender, @Arg("name") String name, @OptArg("skin") String skin, @TextArg @OptArg("loc") String loc) {
        createBots(sender, name, skin, loc, 1);
    }

    @Command(
            name = "multi",
            desc = "Create multiple bots at once.",
            visible = false
    )
    public void multi(CommandSender sender, @Arg("amount") int amount, @Arg("name") String name, @OptArg("skin") String skin, @TextArg @OptArg("loc") String loc) {
        createBots(sender, name, skin, loc, amount);
    }

    @Command(
            name = "respawn",
            desc = "Enable, disable, or show automatic bot respawning.",
            autofill = "respawnAutofill",
            visible = false
    )
    @Require(ADMIN_PERMISSION)
    public void respawn(CommandSender sender, @OptArg("enabled") String value) {
        if (value == null) {
            sender.sendMessage("Automatic bot respawning is "
                    + (manager.isRespawnEnabled() ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled")
                    + ChatColor.RESET + ". Pending: " + manager.pendingRespawnCount() + ".");
            return;
        }

        Boolean enabled = parseBoolean(value);
        if (enabled == null) {
            sender.sendMessage(ChatColor.RED + "You must specify true or false.");
            return;
        }

        manager.setRespawnEnabled(enabled);
        sender.sendMessage("Automatic bot respawning is now "
                + (enabled ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled")
                + ChatColor.RESET + ".");
    }

    public List<String> respawnAutofill(CommandSender sender, String[] args) {
        return args.length == 2 ? List.of("true", "false") : List.of();
    }

    @Command(
            name = "setspawn",
            desc = "Set the respawn anchor for all living bots or one named bot.",
            aliases = "set-spawn",
            autofill = "setSpawnAutofill",
            visible = false
    )
    @Require(ADMIN_PERMISSION)
    public void setSpawn(CommandSender sender, @OptArg("bot-name") String botName) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be run by a player.");
            return;
        }

        List<Bot> bots = manager.fetch().stream()
                .filter(Bot.class::isInstance)
                .map(Bot.class::cast)
                .filter(Bot::isBotAlive)
                .filter(bot -> botName == null || bot.getBotName().equalsIgnoreCase(botName))
                .toList();
        if (bots.isEmpty()) {
            sender.sendMessage(ChatColor.RED + (botName == null
                    ? "No living bots were found."
                    : "No living bot named " + botName + " was found."));
            return;
        }

        Location spawn = player.getLocation();
        bots.forEach(bot -> bot.setRespawnAnchor(spawn));
        sender.sendMessage(ChatColor.YELLOW.toString() + bots.size() + ChatColor.RESET
                + " bot respawn anchor(s) set to your location.");
    }

    public List<String> setSpawnAutofill(CommandSender sender, String[] args) {
        return args.length == 2 ? manager.fetchNames() : List.of();
    }

    static Boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        return null;
    }

    @Command(
            name = "movementv2",
            desc = "Enable, disable, or show Movement V2.",
            autofill = "movementV2Autofill",
            visible = false
    )
    @Require(ADMIN_PERMISSION)
    public void movementV2(CommandSender sender, @OptArg("on-off-status") String action) {
        MovementV2Action parsed = MovementV2Action.parse(action);
        if (parsed == null) {
            sender.sendMessage(ChatColor.RED + "Usage: /bot movementv2 on|off|status");
            return;
        }
        if (parsed != MovementV2Action.STATUS) {
            MovementV2Settings.setEnabled(plugin, parsed == MovementV2Action.ON);
        }
        boolean enabled = MovementV2Settings.isEnabled(plugin);
        sender.sendMessage("Movement V2 is "
                + (enabled ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled")
                + ChatColor.RESET + ".");
    }

    public List<String> movementV2Autofill(CommandSender sender, String[] args) {
        return args.length == 2 ? List.of("on", "off", "status") : List.of();
    }

    enum MovementV2Action {
        ON, OFF, STATUS;

        static MovementV2Action parse(String value) {
            if (value == null) return STATUS;
            return switch (value.toLowerCase(Locale.ENGLISH)) {
                case "on" -> ON;
                case "off" -> OFF;
                case "status" -> STATUS;
                default -> null;
            };
        }
    }

    private void createBots(CommandSender sender, String name, String skin, String loc, int amount) {
        Location location = parseSpawnLocation(sender, loc);
        if (location == null) return;
        manager.createBotsAsync(sender, name, skin, amount, location);
    }

    @Command(
            name = "give",
            desc = "Give an item to bot(s). Usage: /bot give <item> [bot-name] [slot]",
            autofill = "giveAutofill",
            visible = false
    )
    public void give(CommandSender sender, List<String> args) {
        if (args.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Usage: /bot give <item> [bot-name] [slot]");
            return;
        }
        String itemName = args.get(0);
        Material type = Material.matchMaterial(itemName);
        if (type == null) {
            sender.sendMessage("The item " + ChatColor.YELLOW + itemName + ChatColor.RESET + " is not valid!");
            return;
        }
        ItemStack item = new ItemStack(type);

        // Single-arg form: legacy behavior â€” set default item for all bots.
        if (args.size() == 1) {
            manager.fetch().forEach(bot -> bot.setDefaultItem(item));
            sender.sendMessage("Successfully set the default item to " + ChatColor.YELLOW + item.getType() + ChatColor.RESET + " for all current bots.");
            return;
        }

        String botName = args.get(1);
        Integer slot = null;
        if (args.size() >= 3) {
            try {
                slot = Integer.parseInt(args.get(2));
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Slot must be a number 0-40.");
                return;
            }
        }

        Location viewer = sender instanceof Player p ? p.getLocation() : null;
        Bot bot = findBot(botName, viewer);
        if (bot == null) {
            sender.sendMessage(ChatColor.RED + "Bot not found: " + ChatColor.YELLOW + botName);
            return;
        }
        if (slot == null) {
            // Put it in the first empty hotbar slot, falling back to slot 0.
            int target = 0;
            for (int i = 0; i < 9; i++) {
                if (bot.getBukkitEntity().getInventory().getItem(i) == null) {
                    target = i;
                    break;
                }
            }
            slot = target;
        }
        if (slot < 0 || slot > 40) {
            sender.sendMessage(ChatColor.RED + "Slot must be 0-40 (0-8 hotbar, 9-35 storage, 36 boots, 37 legs, 38 chest, 39 head, 40 offhand).");
            return;
        }
        applyLoadoutSlot(bot, slot, item);
        bot.getBotInventory().saveForRespawn();
        sender.sendMessage("Gave " + ChatColor.YELLOW + type + ChatColor.RESET + " to "
                + ChatColor.GREEN + bot.getBotName() + ChatColor.RESET + " at slot "
                + ChatColor.BLUE + slot + ChatColor.RESET + ".");
    }

    public List<String> giveAutofill(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 2) {
            String prefix = args[1].toUpperCase();
            for (Material m : Material.values()) {
                if (m.isItem() && m.name().startsWith(prefix)) out.add(m.name());
                if (out.size() >= 40) break;
            }
        } else if (args.length == 3) {
            out.addAll(manager.fetchNames());
        } else if (args.length == 4) {
            for (int i = 0; i <= 40; i++) out.add(String.valueOf(i));
        }
        return out;
    }

    @Command(
            name = "place",
            desc = "Set the block bots use when placing blocks.",
            autofill = "placeAutofill",
            visible = false
    )
    public void place(CommandSender sender, @Arg("material") String materialName) {
        Material material = materialName == null ? null : Material.matchMaterial(materialName);
        if (!isValidPlacementMaterial(material)) {
            sender.sendMessage(ChatColor.RED + "Material " + ChatColor.YELLOW + materialName
                    + ChatColor.RED + " is not a valid block placement material.");
            return;
        }

        agent.setPlacementMaterial(material);
        sender.sendMessage("Successfully set the bot placement material to "
                + ChatColor.YELLOW + material + ChatColor.RESET + ".");
    }

    public List<String> placeAutofill(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 2) return out;

        String prefix = args[1].toUpperCase(Locale.ROOT);
        for (Material material : Material.values()) {
            if (isValidPlacementMaterial(material) && material.name().startsWith(prefix)) {
                out.add(material.name());
            }
        }
        return out;
    }

    private static boolean isValidPlacementMaterial(Material material) {
        return material != null && material.isBlock() && material.isItem() && !material.isAir();
    }

    private void armorTierSetup() {
        armorTiers.put("none", new ItemStack[]{
                new ItemStack(Material.AIR),
                new ItemStack(Material.AIR),
                new ItemStack(Material.AIR),
                new ItemStack(Material.AIR),
        });

        armorTiers.put("leather", new ItemStack[]{
                new ItemStack(Material.LEATHER_BOOTS),
                new ItemStack(Material.LEATHER_LEGGINGS),
                new ItemStack(Material.LEATHER_CHESTPLATE),
                new ItemStack(Material.LEATHER_HELMET),
        });

        armorTiers.put("chain", new ItemStack[]{
                new ItemStack(Material.CHAINMAIL_BOOTS),
                new ItemStack(Material.CHAINMAIL_LEGGINGS),
                new ItemStack(Material.CHAINMAIL_CHESTPLATE),
                new ItemStack(Material.CHAINMAIL_HELMET),
        });

        armorTiers.put("gold", new ItemStack[]{
                new ItemStack(Material.GOLDEN_BOOTS),
                new ItemStack(Material.GOLDEN_LEGGINGS),
                new ItemStack(Material.GOLDEN_CHESTPLATE),
                new ItemStack(Material.GOLDEN_HELMET),
        });

        armorTiers.put("iron", new ItemStack[]{
                new ItemStack(Material.IRON_BOOTS),
                new ItemStack(Material.IRON_LEGGINGS),
                new ItemStack(Material.IRON_CHESTPLATE),
                new ItemStack(Material.IRON_HELMET),
        });

        armorTiers.put("diamond", new ItemStack[]{
                new ItemStack(Material.DIAMOND_BOOTS),
                new ItemStack(Material.DIAMOND_LEGGINGS),
                new ItemStack(Material.DIAMOND_CHESTPLATE),
                new ItemStack(Material.DIAMOND_HELMET),
        });

        armorTiers.put("netherite", new ItemStack[]{
                new ItemStack(Material.NETHERITE_BOOTS),
                new ItemStack(Material.NETHERITE_LEGGINGS),
                new ItemStack(Material.NETHERITE_CHESTPLATE),
                new ItemStack(Material.NETHERITE_HELMET),
        });
    }

    @Command(
            name = "armor",
            desc = "Gives all bots an armor set.",
            autofill = "armorAutofill",
            visible = false
    )
    public void armor(CommandSender sender, @Arg("armor-tier") String armorTier) {
        String tier = armorTier.toLowerCase();

        if (!armorTiers.containsKey(tier)) {
            sender.sendMessage(ChatColor.YELLOW + tier + ChatColor.RESET + " is not a valid tier!");
            sender.sendMessage("Available tiers: " + ChatColor.YELLOW + String.join(ChatColor.RESET + ", " + ChatColor.YELLOW, armorTiers.keySet()));
            return;
        }

        ItemStack[] armor = armorTiers.get(tier);

        manager.fetch().forEach(bot -> {
            if (bot.getBukkitEntity() instanceof Player botPlayer) {
                botPlayer.getInventory().setArmorContents(armor);
                botPlayer.updateInventory();

                // packet sending to ensure
                bot.setItem(armor[0], EquipmentSlot.FEET);
                bot.setItem(armor[1], EquipmentSlot.LEGS);
                bot.setItem(armor[2], EquipmentSlot.CHEST);
                bot.setItem(armor[3], EquipmentSlot.HEAD);
                if (bot instanceof Bot concreteBot) concreteBot.getBotInventory().saveForRespawn();
            }
        });

        sender.sendMessage("Successfully set the armor tier to " + ChatColor.YELLOW + tier + ChatColor.RESET + " for all current bots.");
    }

    public List<String> armorAutofill(CommandSender sender, String[] args) {
        return args.length == 2 ? new ArrayList<>(armorTiers.keySet()) : new ArrayList<>();
    }

    @Command(
            name = "info",
            desc = "Information about loaded bots.",
            autofill = "infoAutofill",
            visible = false
    )
    public void info(CommandSender sender, @OptArg("bot-name") String name) {
        if (name == null) {
            commandHandler.sendRootInfo(this, sender);
            return;
        }

        sender.sendMessage("Processing request...");
        try {
            Terminator bot = manager.getFirst(name, (sender instanceof Player pl) ? pl.getLocation() : null);

            if (bot == null) {
                sender.sendMessage("Could not find bot " + ChatColor.GREEN + name + ChatColor.RESET + "!");
                return;
            }

            String botName = bot.getBotName();
            String world = ChatColor.YELLOW + bot.getBukkitEntity().getWorld().getName();
            Location loc = bot.getLocation();
            String strLoc = ChatColor.YELLOW + formatter.format(loc.getX()) + ", " + formatter.format(loc.getY()) + ", " + formatter.format(loc.getZ());
            Vector vel = bot.getVelocity();
            String strVel = ChatColor.AQUA + formatter.format(vel.getX()) + ", " + formatter.format(vel.getY()) + ", " + formatter.format(vel.getZ());

            sender.sendMessage(ChatUtils.LINE);
            sender.sendMessage(ChatColor.GREEN + botName);
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + "World: " + world);
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + "Position: " + strLoc);
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + "Velocity: " + strVel);
            sender.sendMessage(ChatUtils.LINE);
        } catch (Exception e) {
            sender.sendMessage(ChatUtils.EXCEPTION_MESSAGE);
        }
    }

    public List<String> infoAutofill(CommandSender sender, String[] args) {
        return args.length == 2 ? manager.fetchNames() : new ArrayList<>();
    }

    @Command(
            name = "count",
            desc = "Counts the amount of bots on screen by name.",
            aliases = {
                    "list"
            },
            visible = false
    )
    public void count(CommandSender sender) {
        List<String> names = manager.fetchNames();
        Map<String, Integer> freqMap = names.stream().collect(Collectors.toMap(s -> s, s -> 1, Integer::sum));
        List<Entry<String, Integer>> entries = freqMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).collect(Collectors.toList());

        sender.sendMessage(ChatUtils.LINE);
        entries.forEach(en -> sender.sendMessage(ChatColor.GREEN + en.getKey()
                + ChatColor.RESET + " - " + ChatColor.BLUE + en.getValue().toString() + ChatColor.RESET));
        sender.sendMessage("Total bots: " + ChatColor.BLUE + freqMap.values().stream().reduce(0, Integer::sum) + ChatColor.RESET);
        sender.sendMessage(ChatUtils.LINE);
    }

    @Command(
            name = "reset",
            desc = "Remove all loaded bots.",
            visible = false
    )
    @Require(ADMIN_PERMISSION)
    public void reset(CommandSender sender) {
        sender.sendMessage("Removing every bot...");
        int size = manager.fetch().size();
        manager.reset();
        sender.sendMessage("Removed " + ChatColor.RED + ChatUtils.NUMBER_FORMAT.format(size) + ChatColor.RESET + " entit" + (size == 1 ? "y" : "ies") + ".");

        if (aiManager == null) {
            this.aiManager = (AICommand) commandHandler.getCommand("ai");
        }

        if (aiManager != null && aiManager.hasActiveSession()) {
            Bukkit.dispatchCommand(sender, "ai stop");
        }
    }

    @Command(
            name = "settings",
            desc = "Make changes to the global configuration file and bot-specific settings.",
            aliases = "options",
            autofill = "settingsAutofill"
    )
    public void settings(CommandSender sender, List<String> args) {
        String arg1 = args.isEmpty() ? null : canonicalSettingsAction(args.get(0));
        String arg2 = args.size() < 2 ? null : args.get(1);

        String extra = ChatColor.GRAY + " [" + ChatColor.YELLOW + "/bot settings" + ChatColor.GRAY + "]";

        if (arg1 == null || (!arg1.equalsIgnoreCase("combat-goal") && !arg1.equalsIgnoreCase("target-mobs") && !arg1.equalsIgnoreCase("target-player")
                && !arg1.equalsIgnoreCase("show-in-player-list") && !arg1.equalsIgnoreCase("target-region")
                && !arg1.equalsIgnoreCase("auto-respawn") && !arg1.equalsIgnoreCase("movement-v2")
                && !arg1.equalsIgnoreCase("set-spawn") && !arg1.equalsIgnoreCase("placement-material"))) {
            sender.sendMessage(ChatUtils.LINE);
            sender.sendMessage(ChatColor.GOLD + "Bot Settings" + extra);
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + "combat-goal" + ChatUtils.BULLET_FORMATTED + "Set the global bot target selection method.");
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + "target-mobs" + ChatUtils.BULLET_FORMATTED + "Allow all bots to be targeted by hostile mobs.");
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + "target-player" + ChatUtils.BULLET_FORMATTED + "Set a player name for spawned bots to focus on if the goal is PLAYER.");
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + "target-region" + ChatUtils.BULLET_FORMATTED + "Set a region for the bots to prioritize entities inside.");
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + "show-in-player-list" + ChatUtils.BULLET_FORMATTED + "Add newly spawned bots to the player list.");
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + "auto-respawn" + ChatUtils.BULLET_FORMATTED + "Enable or disable automatic bot respawning.");
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + "set-spawn" + ChatUtils.BULLET_FORMATTED + "Set bot respawn anchors to your location.");
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + "movement-v2" + ChatUtils.BULLET_FORMATTED + "Enable, disable, or inspect Movement V2.");
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + "placement-material" + ChatUtils.BULLET_FORMATTED + "Set the block bots use when placing blocks.");
            sender.sendMessage(ChatUtils.LINE);
            return;
        } else if (arg1.equalsIgnoreCase("combat-goal")) {
            if (arg2 == null) {
                sender.sendMessage("The global bot goal is currently " + ChatColor.BLUE + agent.getTargetType() + ChatColor.RESET + ".");
                return;
            }
            EnumTargetGoal goal = EnumTargetGoal.from(arg2);

            if (goal == null) {
                sender.sendMessage(ChatUtils.LINE);
                sender.sendMessage(ChatColor.GOLD + "Goal Selection Types" + extra);
                Arrays.stream(EnumTargetGoal.values()).forEach(g -> sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + g.name().replace("_", "").toLowerCase()
                        + ChatUtils.BULLET_FORMATTED + g.description()));
                sender.sendMessage(ChatUtils.LINE);
                return;
            }
            agent.setTargetType(goal);
            sender.sendMessage("The global bot goal has been set to " + ChatColor.BLUE + goal.name() + ChatColor.RESET + ".");
        } else if (arg1.equalsIgnoreCase("target-mobs")) {
            if (arg2 == null) {
                sender.sendMessage("Mob targeting is currently " + (manager.isMobTarget() ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled") + ChatColor.RESET + ".");
                return;
            }
            Boolean enabled = parseBooleanValue(arg2);
            if (enabled == null) {
                sender.sendMessage(ChatColor.RED + "You must specify true or false!");
                return;
            }
            manager.setMobTarget(enabled);
            sender.sendMessage("Mob targeting is now " + (manager.isMobTarget() ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled") + ChatColor.RESET + ".");
        } else if (arg1.equalsIgnoreCase("target-player")) {
            if (args.size() < 2) {
                sender.sendMessage(ChatColor.RED + "You must specify a player name!");
                return;
            }
            String playerName = arg2;
            Player player = Bukkit.getPlayer(playerName);
            if (player == null) {
                sender.sendMessage(ChatColor.RED + "Could not find player " + ChatColor.YELLOW + playerName + ChatColor.RED + "!");
                return;
            }
            for (Terminator fetch : manager.fetch()) {
                fetch.setTargetPlayer(player.getUniqueId());
            }
            sender.sendMessage("All spawned bots are now set to target " + ChatColor.BLUE + player.getName() + ChatColor.RESET + ". They will target the closest player if they can't be found.\nYou may need to set the goal to PLAYER.");
        } else if (arg1.equalsIgnoreCase("show-in-player-list")) {
            if (arg2 == null) {
                sender.sendMessage("Adding bots to the player list is currently " + (manager.addToPlayerList() ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled") + ChatColor.RESET + ".");
                return;
            }
            Boolean enabled = parseBooleanValue(arg2);
            if (enabled == null) {
                sender.sendMessage(ChatColor.RED + "You must specify true or false!");
                return;
            }
            manager.setAddToPlayerList(enabled);
            sender.sendMessage("Adding bots to the player list is now " + (manager.addToPlayerList() ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled") + ChatColor.RESET + ".");
        } else if (arg1.equalsIgnoreCase("target-region")) {
            if (arg2 == null) {
                if (agent.getRegion() == null) {
                    sender.sendMessage("No region has been set.");
                    return;
                }
                sender.sendMessage("The current region is " + ChatColor.BLUE + agent.getRegion() + ChatColor.RESET + ".");
                if (agent.getRegionWeightX() == 0 && agent.getRegionWeightY() == 0 && agent.getRegionWeightZ() == 0)
                    sender.sendMessage("Entities out of range will not be targeted.");
                else {
                    sender.sendMessage("The region X weight is " + ChatColor.BLUE + agent.getRegionWeightX() + ChatColor.RESET + ".");
                    sender.sendMessage("The region Y weight is " + ChatColor.BLUE + agent.getRegionWeightY() + ChatColor.RESET + ".");
                    sender.sendMessage("The region Z weight is " + ChatColor.BLUE + agent.getRegionWeightZ() + ChatColor.RESET + ".");
                }
                return;
            }
            if (arg2.equalsIgnoreCase("clear")) {
                agent.setRegion(null, 0, 0, 0);
                sender.sendMessage("The region has been cleared.");
                return;
            }
            boolean strict = args.size() == 8 && args.get(7).equalsIgnoreCase("strict");
            if (args.size() != 10 && !strict) {
                sender.sendMessage(ChatUtils.LINE);
                sender.sendMessage(ChatColor.GOLD + "Bot Region Settings" + extra);
                sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + "<x1> <y1> <z1> <x2> <y2> <z2> <wX> <wY> <wZ>" + ChatUtils.BULLET_FORMATTED
                        + "Sets a region for bots to prioritize entities within.");
                sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + "<x1> <y1> <z1> <x2> <y2> <z2> strict" + ChatUtils.BULLET_FORMATTED
                        + "Sets a region so that the bots only target entities within the region.");
                sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + "clear" + ChatUtils.BULLET_FORMATTED
                        + "Clears the region.");
                sender.sendMessage("Without strict mode, the entity distance from the region is multiplied by the weight values if outside the region.");
                sender.sendMessage("The resulting value is added to the entity distance when selecting an entity.");
                sender.sendMessage(ChatUtils.LINE);
                return;
            }
            double x1, y1, z1, x2, y2, z2, wX, wY, wZ;
            try {
                Location loc = sender instanceof Player pl ? pl.getLocation() : null;
                x1 = parseDoubleOrRelative(args.get(1), loc, 0);
                y1 = parseDoubleOrRelative(args.get(2), loc, 1);
                z1 = parseDoubleOrRelative(args.get(3), loc, 2);
                x2 = parseDoubleOrRelative(args.get(4), loc, 0);
                y2 = parseDoubleOrRelative(args.get(5), loc, 1);
                z2 = parseDoubleOrRelative(args.get(6), loc, 2);
                if (strict)
                    wX = wY = wZ = 0;
                else {
                    wX = Double.parseDouble(args.get(7));
                    wY = Double.parseDouble(args.get(8));
                    wZ = Double.parseDouble(args.get(9));
                    if (wX <= 0 || wY <= 0 || wZ <= 0) {
                        sender.sendMessage("The region weights must be positive values!");
                        return;
                    }
                }
            } catch (NumberFormatException e) {
                sender.sendMessage("The region bounds and weights must be valid numbers!");
                sender.sendMessage("Correct syntax: " + ChatColor.YELLOW + "/bot settings target-region <x1> <y1> <z1> <x2> <y2> <z2> <wX> <wY> <wZ>"
                        + ChatColor.RESET);
                return;
            }
            agent.setRegion(new BoundingBox(x1, y1, z1, x2, y2, z2), wX, wY, wZ);
            sender.sendMessage("The region has been set to " + ChatColor.BLUE + agent.getRegion() + ChatColor.RESET + ".");
            if (wX == 0 && wY == 0 && wZ == 0)
                sender.sendMessage("Entities out of range will not be targeted.");
            else {
                sender.sendMessage("The region X weight is " + ChatColor.BLUE + agent.getRegionWeightX() + ChatColor.RESET + ".");
                sender.sendMessage("The region Y weight is " + ChatColor.BLUE + agent.getRegionWeightY() + ChatColor.RESET + ".");
                sender.sendMessage("The region Z weight is " + ChatColor.BLUE + agent.getRegionWeightZ() + ChatColor.RESET + ".");
            }
        } else if (arg1.equalsIgnoreCase("auto-respawn")) {
            if (!requireAdmin(sender)) return;
            if (arg2 == null || arg2.equalsIgnoreCase("status")) {
                respawn(sender, null);
                return;
            }
            Boolean enabled = parseToggleValue(arg2);
            if (enabled == null) {
                sender.sendMessage(ChatColor.RED + "Usage: /bot settings auto-respawn <true|false|on|off>");
                return;
            }
            respawn(sender, Boolean.toString(enabled));
        } else if (arg1.equalsIgnoreCase("set-spawn")) {
            if (!requireAdmin(sender)) return;
            setSpawn(sender, arg2);
        } else if (arg1.equalsIgnoreCase("movement-v2")) {
            if (!requireAdmin(sender)) return;
            movementV2(sender, arg2);
        } else if (arg1.equalsIgnoreCase("placement-material")) {
            if (arg2 == null) {
                sender.sendMessage(ChatColor.RED + "Usage: /bot settings placement-material <material>");
                return;
            }
            place(sender, arg2);
        }
    }

    public List<String> settingsAutofill(CommandSender sender, String[] args) {
        List<String> output = new ArrayList<>();

        if (args.length == 2) {
            output.add("combat-goal");
            output.add("target-mobs");
            output.add("target-player");
            output.add("target-region");
            output.add("show-in-player-list");
            output.add("auto-respawn");
            output.add("set-spawn");
            output.add("movement-v2");
            output.add("placement-material");
        } else if (args.length == 3) {
            String action = canonicalSettingsAction(args[1]);
            if ("combat-goal".equals(action)) {
                Arrays.stream(EnumTargetGoal.values()).forEach(goal -> output.add(goal.name().replace("_", "").toLowerCase()));
            }
            if ("target-mobs".equals(action) || "show-in-player-list".equals(action) || "auto-respawn".equals(action)) {
                output.add("true");
                output.add("false");
            }
            if ("target-player".equals(action)) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    output.add(player.getName());
                }
            }
            if ("set-spawn".equals(action)) output.addAll(manager.fetchNames());
            if ("movement-v2".equals(action)) output.addAll(List.of("on", "off", "status"));
            if ("placement-material".equals(action)) output.addAll(placeAutofill(sender, new String[]{"place", args[2]}));
        }

        return output;
    }

    @Command(
            name = "debug",
            desc = "Inspect bot behavior and debug output.",
            autofill = "debugAutofill"
    )
    @Require(ADMIN_PERMISSION)
    public void debug(CommandSender sender, List<String> args) {
        if (args.isEmpty()) {
            sendGroupHelp(sender, "/bot debug", "behavior <expression>", "combat <bot-name|all> <on|off>", "movement [bot-name]");
            return;
        }

        String action = args.get(0).toLowerCase(Locale.ROOT);
        if (action.equals("behavior") || action.equals("expression")) {
            if (args.size() < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /bot debug behavior <expression>");
                return;
            }
            new Debugger(sender).execute(String.join(" ", args.subList(1, args.size())));
        } else if (action.equals("combat")) {
            if (args.size() != 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /bot debug combat <bot-name|all> <on|off>");
                return;
            }
            combatDebug(sender, args.get(1), args.get(2));
        } else if (action.equals("movement")) {
            if (args.size() > 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /bot debug movement [bot-name]");
                return;
            }
            BotEnvironmentCommand environment = environmentCommand();
            if (environment == null) {
                sender.sendMessage(ChatColor.RED + "Movement debug is unavailable.");
                return;
            }
            environment.movementV2Status(sender, args.size() == 2 ? List.of(args.get(1)) : List.of());
        } else {
            // Keep the old /bot debug <expression> form working.
            new Debugger(sender).execute(String.join(" ", args));
        }
    }

    public List<String> debugAutofill(CommandSender sender, String[] args) {
        if (args.length == 2) {
            List<String> output = new ArrayList<>(List.of("behavior", "combat", "movement", "expression"));
            output.addAll(Debugger.AUTOFILL_METHODS);
            return output;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("combat")) {
            List<String> output = new ArrayList<>(manager.fetchNames());
            output.add("all");
            return output;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("combat")) return List.of("on", "off");
        if (args.length == 3 && (args[1].equalsIgnoreCase("behavior") || args[1].equalsIgnoreCase("expression"))) {
            return new ArrayList<>(Debugger.AUTOFILL_METHODS);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("movement")) return manager.fetchNames();
        return List.of();
    }

    @Command(
            name = "weapons",
            desc = "Show which combat behaviors each bot's inventory unlocks.",
            autofill = "weaponsAutofill",
            visible = false
    )
    public void weapons(CommandSender sender, @OptArg("bot-name") String botName) {
        Location viewer = sender instanceof Player p ? p.getLocation() : null;

        java.util.List<Bot> bots = new ArrayList<>();
        if (botName != null && !botName.isEmpty()) {
            Bot b = findBot(botName, viewer);
            if (b == null) {
                sender.sendMessage(ChatColor.RED + "Bot not found: " + ChatColor.YELLOW + botName);
                return;
            }
            bots.add(b);
        } else {
            for (Terminator t : manager.fetch()) {
                if (t instanceof Bot b) bots.add(b);
            }
        }
        if (bots.isEmpty()) {
            sender.sendMessage("No bots to report on.");
            return;
        }

        sender.sendMessage(ChatUtils.LINE);
        sender.sendMessage(ChatColor.GOLD + "Bot Weapons");
        for (Bot bot : bots) {
            net.nuggetmc.tplus.bot.loadout.BotInventory inv = bot.getBotInventory();
            StringBuilder sb = new StringBuilder(ChatUtils.BULLET_FORMATTED);
            sb.append(ChatColor.GREEN).append(bot.getBotName()).append(ChatColor.RESET).append(": ");
            appendFlag(sb, "melee", inv.findSword() >= 0 || inv.findAxe() >= 0);
            appendFlag(sb, "mace", inv.hasMace());
            appendFlag(sb, "trident", inv.hasTrident());
            appendFlag(sb, "pearl", inv.hasEnderPearl());
            appendFlag(sb, "windcharge", inv.hasWindCharge());
            appendFlag(sb, "crystal", inv.hasCrystalKit());
            appendFlag(sb, "anchor", inv.hasAnchorKit());
            appendFlag(sb, "cobweb", inv.hasCobweb());
            appendFlag(sb, "totem", inv.hasTotem());
            appendFlag(sb, "elytra", inv.hasElytra());
            appendFlag(sb, "firework", inv.hasFirework());
            sender.sendMessage(sb.toString());
        }
        sender.sendMessage(ChatUtils.LINE);
    }

    private static void appendFlag(StringBuilder sb, String label, boolean on) {
        sb.append(on ? ChatColor.YELLOW : ChatColor.DARK_GRAY).append(label).append(ChatColor.RESET).append(' ');
    }

    public List<String> weaponsAutofill(CommandSender sender, String[] args) {
        return args.length == 2 ? manager.fetchNames() : new ArrayList<>();
    }

    @Command(
            name = "combatdebug",
            desc = "Toggle full combat + movement trace logging for one bot or all bots.",
            aliases = {"cdbg", "comatdebug"},
            autofill = "combatDebugAutofill",
            visible = false
    )
    @Require(ADMIN_PERMISSION)
    public void combatDebug(CommandSender sender, @Arg("name-or-all") String target, @Arg("on-off") String state) {
        boolean turnOn;
        if (state.equalsIgnoreCase("on") || state.equalsIgnoreCase("true") || state.equalsIgnoreCase("1")) {
            turnOn = true;
        } else if (state.equalsIgnoreCase("off") || state.equalsIgnoreCase("false") || state.equalsIgnoreCase("0")) {
            turnOn = false;
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /bot combatdebug <botName|all> <on|off>");
            return;
        }
        String debugDir = new java.io.File(plugin.getDataFolder(), "debug").getAbsolutePath();

        if (target.equalsIgnoreCase("all")) {
            if (turnOn) {
                net.nuggetmc.tplus.bot.combat.CombatDebugger.enableAll();
                for (Terminator t : manager.fetch()) {
                    if (t instanceof Bot bot) {
                        net.nuggetmc.tplus.bot.combat.CombatDebugger.inventorySnapshotNow(bot, "debug-enable");
                    }
                }
                sender.sendMessage(ChatColor.GREEN + "Full debug enabled for ALL bots.");
                sender.sendMessage(ChatColor.GRAY + "Logs: " + debugDir + ChatColor.DARK_GRAY + " (combat-all.log + per-bot files)");
            } else {
                net.nuggetmc.tplus.bot.combat.CombatDebugger.disableAll();
                sender.sendMessage(ChatColor.YELLOW + "Combat debug disabled for all bots.");
            }
            return;
        }

        java.util.List<Bot> matches = manager.getAllByName(target);
        if (matches.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No bots named " + ChatColor.YELLOW + target);
            return;
        }
        for (Bot b : matches) {
            if (turnOn) {
                net.nuggetmc.tplus.bot.combat.CombatDebugger.enable(b.getUUID());
                net.nuggetmc.tplus.bot.combat.CombatDebugger.inventorySnapshotNow(b, "debug-enable");
            } else {
                net.nuggetmc.tplus.bot.combat.CombatDebugger.disable(b.getUUID());
            }
        }
        sender.sendMessage((turnOn ? ChatColor.GREEN + "Enabled" : ChatColor.YELLOW + "Disabled")
                + ChatColor.RESET + " combat debug for " + matches.size() + " bot(s) named "
                + ChatColor.YELLOW + target + ChatColor.RESET
                + (turnOn ? "." : "."));
        if (turnOn) {
            sender.sendMessage(ChatColor.GRAY + "Logs: " + debugDir + ChatColor.DARK_GRAY + " (combat-all.log + per-bot files)");
        }
    }

    public List<String> combatDebugAutofill(CommandSender sender, String[] args) {
        if (args.length == 2) {
            List<String> out = new ArrayList<>(manager.fetchNames());
            out.add("all");
            return out;
        }
        if (args.length == 3) return Arrays.asList("on", "off");
        return new ArrayList<>();
    }

    @Command(
            name = "gather",
            desc = "Teleport every living bot to your current location.",
            aliases = {"tpall"},
            visible = false
    )
    public void gather(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be run by a player.");
            return;
        }
        Location dest = player.getLocation();
        int moved = 0;
        for (net.nuggetmc.tplus.api.Terminator t : manager.fetch()) {
            if (!t.isBotAlive()) continue;
            t.getBukkitEntity().teleport(dest);
            moved++;
        }
        sender.sendMessage(ChatColor.YELLOW.toString() + moved + ChatColor.RESET
                + " bot(s) gathered to you.");
    }

    @Command(
            name = "scatter",
            desc = "Distribute every living bot around your current location within an optional radius.",
            visible = false
    )
    public void scatter(CommandSender sender, @OptArg("radius") String radiusText) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be run by a player.");
            return;
        }

        final double radius;
        try {
            radius = parseScatterRadius(radiusText);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(ChatColor.RED + e.getMessage());
            sender.sendMessage(ChatColor.GRAY + "Usage: /bot move scatter [radius] (default "
                    + DEFAULT_SCATTER_RADIUS + ").");
            return;
        }

        List<Terminator> bots = manager.fetch().stream()
                .filter(Terminator::isBotAlive)
                .toList();
        if (bots.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No living bots selected to scatter.");
            return;
        }

        List<Location> destinations = findScatterDestinations(player.getLocation(), bots.size(), radius);
        if (destinations.size() < bots.size()) {
            sender.sendMessage(ChatColor.RED + "Only found " + destinations.size()
                    + " safe, unique position(s) for " + bots.size()
                    + " bot(s); no bots were moved.");
            return;
        }

        World world = player.getWorld();
        int moved = 0;
        for (int i = 0; i < bots.size(); i++) {
            Terminator bot = bots.get(i);
            if (bot.getBukkitEntity().teleport(destinations.get(i))) {
                moved++;
                world.spawnParticle(Particle.CLOUD, destinations.get(i), 100, 1, 1, 1, 0.5);
            }
        }
        if (moved == 0) {
            sender.sendMessage(ChatColor.RED + "No selected bots could be teleported; no bots were moved.");
            return;
        }
        sender.sendMessage(ChatColor.YELLOW.toString() + moved + ChatColor.RESET
                + " bot(s) teleported into a circular spread within radius "
                + ChatColor.YELLOW + radius + ChatColor.RESET + ".");
    }

    static double parseScatterRadius(String radiusText) {
        if (radiusText == null || radiusText.isBlank()) return DEFAULT_SCATTER_RADIUS;

        final double radius;
        try {
            radius = Double.parseDouble(radiusText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Radius must be a finite number of at least "
                    + MIN_SCATTER_RADIUS + ".");
        }
        if (!Double.isFinite(radius) || radius < MIN_SCATTER_RADIUS) {
            throw new IllegalArgumentException("Radius must be a finite number of at least "
                    + MIN_SCATTER_RADIUS + ".");
        }
        return radius;
    }

    static List<ScatterOffset> evenlySpacedOffsets(int count, double radius) {
        if (count <= 0 || !Double.isFinite(radius) || radius <= 0) return List.of();

        List<ScatterOffset> offsets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double distance = radius * Math.sqrt((i + 1.0) / count);
            double angle = i * Math.PI * (3.0 - Math.sqrt(5.0));
            offsets.add(new ScatterOffset(Math.cos(angle) * distance, Math.sin(angle) * distance));
        }
        return offsets;
    }

    static List<ScatterOffset> selectScatterOffsets(List<ScatterOffset> candidates, int count, double radius) {
        if (count <= 0 || candidates == null || candidates.isEmpty()) return List.of();

        List<ScatterOffset> remaining = new ArrayList<>(new LinkedHashSet<>(candidates));
        List<ScatterOffset> ideals = evenlySpacedOffsets(count, radius);
        List<ScatterOffset> selected = new ArrayList<>(Collections.nCopies(count, null));

        // Preserve exact ideal points whenever they are safe and spaced apart.
        for (int i = 0; i < ideals.size(); i++) {
            ScatterOffset ideal = ideals.get(i);
            if (remaining.contains(ideal) && isScatterSpacingAvailable(selected, ideal)) {
                remaining.remove(ideal);
                selected.set(i, ideal);
            }
        }

        for (int i = 0; i < ideals.size(); i++) {
            if (selected.get(i) != null || remaining.isEmpty()) continue;

            ScatterOffset ideal = ideals.get(i);
            ScatterOffset best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (ScatterOffset candidate : remaining) {
                double distance = Math.hypot(candidate.x(), candidate.z());
                if (distance > radius + 1.0e-6 || distance < 1.0e-6) continue;
                if (!isScatterSpacingAvailable(selected, candidate)) continue;

                double desiredDistance = Math.hypot(ideal.x(), ideal.z());
                double angleScore = desiredDistance < 1.0e-6 ? 0.0
                        : angleDifference(
                                Math.atan2(candidate.z(), candidate.x()),
                                Math.atan2(ideal.z(), ideal.x())) * Math.max(1.0, radius) * 4;
                double score = angleScore + Math.abs(distance - desiredDistance);
                if (score < bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
            if (best != null) {
                selected.set(i, best);
                remaining.remove(best);
            }
        }

        return selected.stream().filter(Objects::nonNull).toList();
    }

    private static List<Location> findScatterDestinations(Location center, int count, double radius) {
        if (center == null || center.getWorld() == null || count <= 0) return List.of();

        Map<ScatterOffset, Location> candidates = new LinkedHashMap<>();
        List<ScatterOffset> ideals = evenlySpacedOffsets(count, radius);
        for (ScatterOffset offset : ideals) {
            addScatterCandidate(center, offset, candidates);
        }

        // Search a fixed neighbourhood around each ideal instead of scanning the
        // whole circle, so runtime depends on bot count rather than radius.
        for (ScatterOffset ideal : ideals) {
            int idealX = Location.locToBlock(center.getX() + ideal.x());
            int idealZ = Location.locToBlock(center.getZ() + ideal.z());
            for (int dx = -SCATTER_FALLBACK_RANGE; dx <= SCATTER_FALLBACK_RANGE; dx++) {
                for (int dz = -SCATTER_FALLBACK_RANGE; dz <= SCATTER_FALLBACK_RANGE; dz++) {
                    double offsetX = idealX + dx + 0.5 - center.getX();
                    double offsetZ = idealZ + dz + 0.5 - center.getZ();
                    if (Math.hypot(offsetX, offsetZ) > radius
                            || (idealX + dx == center.getBlockX() && idealZ + dz == center.getBlockZ())) continue;
                    addScatterCandidate(center, new ScatterOffset(offsetX, offsetZ), candidates);
                }
            }
        }

        return selectScatterOffsets(new ArrayList<>(candidates.keySet()), count, radius).stream()
                .map(candidates::get)
                .toList();
    }

    private static boolean isScatterSpacingAvailable(List<ScatterOffset> selected, ScatterOffset candidate) {
        for (ScatterOffset existing : selected) {
            if (existing != null && Math.hypot(existing.x() - candidate.x(), existing.z() - candidate.z())
                    < SCATTER_MIN_SEPARATION) return false;
        }
        return true;
    }

    private static void addScatterCandidate(Location center, ScatterOffset offset,
                                            Map<ScatterOffset, Location> candidates) {
        Location destination = findSafeScatterLocation(center, offset);
        if (destination == null) return;

        candidates.putIfAbsent(offset, destination);
    }

    private static Location findSafeScatterLocation(Location center, ScatterOffset offset) {
        World world = center.getWorld();
        double x = center.getX() + offset.x();
        double z = center.getZ() + offset.z();
        if (!isInsideScatterBorder(world, x, z)) return null;
        int blockX = Location.locToBlock(x);
        int blockZ = Location.locToBlock(z);
        if (blockX == center.getBlockX() && blockZ == center.getBlockZ()) return null;

        int top = Math.min(world.getMaxHeight() - 2, world.getHighestBlockYAt(blockX, blockZ) + 1);
        for (int y = top; y > world.getMinHeight(); y--) {
            if (!isSafeScatterFloor(world, x, y, z)) continue;
            if (!isSafeScatterBody(world, x, y, z)) continue;
            return new Location(world, x, y, z, center.getYaw(), center.getPitch());
        }
        return null;
    }

    private static boolean isSafeScatterBody(World world, double x, int y, double z) {
        for (double dx : new double[]{-0.3, 0.0, 0.3}) {
            for (double dz : new double[]{-0.3, 0.0, 0.3}) {
                if (!isSafeScatterClear(world.getBlockAt(Location.locToBlock(x + dx), y,
                        Location.locToBlock(z + dz)))) return false;
                if (!isSafeScatterClear(world.getBlockAt(Location.locToBlock(x + dx), y + 1,
                        Location.locToBlock(z + dz)))) return false;
            }
        }
        return true;
    }

    private static boolean isSafeScatterFloor(Block block) {
        Material material = block.getType();
        return !isScatterHazard(material)
                && !block.isLiquid()
                && (!(block.getBlockData() instanceof Waterlogged waterlogged) || !waterlogged.isWaterlogged())
                && Math.abs(block.getBoundingBox().getMaxY() - (block.getY() + 1.0)) < 1.0e-6
                && (LegacyMats.isSolid(material) || LegacyMats.canStandOn(material));
    }

    private static boolean isSafeScatterFloor(World world, double x, int y, double z) {
        for (double dx : new double[]{-0.3, 0.3}) {
            for (double dz : new double[]{-0.3, 0.3}) {
                Block floor = world.getBlockAt(
                        Location.locToBlock(x + dx), y - 1, Location.locToBlock(z + dz));
                if (!isSafeScatterFloor(floor)) return false;
            }
        }
        return true;
    }

    private static boolean isSafeScatterClear(Block block) {
        return !isScatterHazard(block.getType())
                && !block.isLiquid()
                && (!(block.getBlockData() instanceof Waterlogged waterlogged) || !waterlogged.isWaterlogged())
                && block.isPassable();
    }

    static boolean isScatterHazard(Material material) {
        return material == Material.WATER
                || material == Material.LAVA
                || material == Material.FIRE
                || material == Material.SOUL_FIRE
                || material == Material.CAMPFIRE
                || material == Material.SOUL_CAMPFIRE
                || material == Material.CACTUS
                || material == Material.MAGMA_BLOCK
                || material == Material.SWEET_BERRY_BUSH
                || material == Material.POWDER_SNOW
                || material == Material.POINTED_DRIPSTONE
                || material == Material.WITHER_ROSE
                || material == Material.COBWEB
                || material == Material.VINE;
    }

    private static boolean isInsideScatterBorder(World world, double x, double z) {
        for (double dx : new double[]{-0.3, 0.3}) {
            for (double dz : new double[]{-0.3, 0.3}) {
                if (!world.getWorldBorder().isInside(new Location(world, x + dx, 0, z + dz))) return false;
            }
        }
        return true;
    }

    private static double angleDifference(double first, double second) {
        return Math.abs(Math.atan2(Math.sin(first - second), Math.cos(first - second)));
    }

    record ScatterOffset(double x, double z) {
    }

    @Command(
            name = "inventory",
            desc = "Opens the inventory editor GUI for a bot.",
            aliases = {"inv"},
            autofill = "inventoryAutofill",
            visible = false
    )
    public void inventory(CommandSender sender, @Arg("bot-name") String name) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be run by a player.");
            return;
        }
        List<Bot> matches = manager.fetch().stream()
                .filter(t -> t instanceof Bot)
                .map(t -> (Bot) t)
                .filter(bot -> name.equalsIgnoreCase(bot.getBotName()))
                .toList();
        if (matches.isEmpty()) {
            sender.sendMessage("Could not find bot " + ChatColor.GREEN + name + ChatColor.RESET + "!");
            return;
        }
        if (matches.size() > 1) {
            sender.sendMessage(ChatColor.RED + "Inventory editing is ambiguous: multiple bots use the name "
                    + ChatColor.YELLOW + name + ChatColor.RED + ". Select the exact bot in /bot first.");
            return;
        }
        if (plugin.getInventoryListener() == null) {
            sender.sendMessage(ChatColor.RED + "The bot inventory editor is unavailable right now.");
            return;
        }
        plugin.getInventoryListener().open(player, matches.get(0));
    }

    public List<String> inventoryAutofill(CommandSender sender, String[] args) {
        return args.length == 2 ? manager.fetchNames() : new ArrayList<>();
    }

    @Command(
            name = "preset",
            desc = "Save, load, list, and delete bot presets.",
            autofill = "presetAutofill"
    )
    public void preset(CommandSender sender, List<String> args) {
        PresetManager presets = plugin.getPresetManager();
        String action = args.isEmpty() ? null : args.get(0);

        if (action == null) {
            sender.sendMessage(ChatUtils.LINE);
            sender.sendMessage(ChatColor.GOLD + "Bot Presets" + ChatColor.GRAY + " [" + ChatColor.YELLOW + "/bot preset" + ChatColor.GRAY + "]");
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + "save <name> <bot-name>" + ChatUtils.BULLET_FORMATTED + "Save a bot's state as a preset.");
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + "apply <name> [bot-name]" + ChatUtils.BULLET_FORMATTED + "Apply a preset to one bot, or to ALL if no name given.");
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + "list" + ChatUtils.BULLET_FORMATTED + "List all saved presets.");
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + "delete <name>" + ChatUtils.BULLET_FORMATTED + "Delete a preset.");
            sender.sendMessage(ChatUtils.LINE);
            return;
        }

        switch (action.toLowerCase()) {
            case "list" -> {
                List<String> names = presets.list();
                if (names.isEmpty()) {
                    sender.sendMessage("No presets saved yet. Use " + ChatColor.YELLOW + "/bot preset save <name> <bot-name>" + ChatColor.RESET + ".");
                    return;
                }
                sender.sendMessage(ChatUtils.LINE);
                sender.sendMessage(ChatColor.GOLD + "Saved Presets (" + names.size() + ")");
                names.forEach(n -> sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + n));
                sender.sendMessage(ChatUtils.LINE);
            }
            case "save" -> {
                if (args.size() < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /bot preset save <preset-name> <bot-name>");
                    return;
                }
                String presetName = args.get(1);
                String botName = args.get(2);
                Location viewer = sender instanceof Player p ? p.getLocation() : null;
                Bot bot = findBot(botName, viewer);
                if (bot == null) {
                    sender.sendMessage(ChatColor.RED + "Bot not found: " + ChatColor.YELLOW + botName);
                    return;
                }
                try {
                    BotPreset preset = presets.capture(bot, presetName);
                    presets.save(preset);
                    sender.sendMessage("Saved preset " + ChatColor.YELLOW + presetName + ChatColor.RESET + " from " + ChatColor.GREEN + botName + ChatColor.RESET + ".");
                } catch (Exception e) {
                    sender.sendMessage(ChatColor.RED + "Failed to save preset: " + e.getMessage());
                }
            }
            case "load", "apply" -> {
                if (args.size() < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /bot preset apply <preset-name> [bot-name]");
                    return;
                }
                String presetName = args.get(1);
                BotPreset preset = presets.load(presetName);
                if (preset == null) {
                    sender.sendMessage(ChatColor.RED + "Preset not found: " + ChatColor.YELLOW + presetName);
                    return;
                }
                if (args.size() >= 3) {
                    String botName = args.get(2);
                    Location viewer = sender instanceof Player p ? p.getLocation() : null;
                    Bot bot = findBot(botName, viewer);
                    if (bot == null) {
                        sender.sendMessage(ChatColor.RED + "Bot not found: " + ChatColor.YELLOW + botName);
                        return;
                    }
                    presets.apply(preset, bot);
                    sender.sendMessage("Applied preset " + ChatColor.YELLOW + presetName + ChatColor.RESET + " to " + ChatColor.GREEN + botName + ChatColor.RESET + ".");
                } else {
                    int n = presets.applyToAll(preset);
                    sender.sendMessage("Applied preset " + ChatColor.YELLOW + presetName + ChatColor.RESET + " to " + ChatColor.BLUE + n + ChatColor.RESET + " bot(s).");
                }
            }
            case "delete" -> {
                if (!sender.hasPermission(ADMIN_PERMISSION)) {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to delete presets.");
                    sender.sendMessage(ChatColor.RED + "Required: " + ChatColor.YELLOW + ADMIN_PERMISSION);
                    return;
                }
                if (args.size() < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /bot preset delete <preset-name>");
                    return;
                }
                String presetName = args.get(1);
                if (presets.delete(presetName)) {
                    sender.sendMessage("Deleted preset " + ChatColor.YELLOW + presetName + ChatColor.RESET + ".");
                } else {
                    sender.sendMessage(ChatColor.RED + "Preset not found: " + ChatColor.YELLOW + presetName);
                }
            }
            default -> sender.sendMessage(ChatColor.RED + "Unknown action. See /bot preset for help.");
        }
    }

    public List<String> presetAutofill(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 2) {
            out.add("save");
            out.add("apply");
            out.add("list");
            out.add("delete");
        } else if (args.length == 3) {
            String action = args[1].toLowerCase();
            if (action.equals("load") || action.equals("apply") || action.equals("delete")) {
                out.addAll(plugin.getPresetManager().list());
            } else if (action.equals("save")) {
                out.add("<preset-name>");
            }
        } else if (args.length == 4) {
            String action = args[1].toLowerCase();
            if (action.equals("save") || action.equals("apply")) {
                out.addAll(manager.fetchNames());
            }
        }
        return out;
    }

    @Command(
            name = "loadout",
            desc = "Apply a predefined combat loadout. Usage: /bot loadout <name> [bot-name]",
            autofill = "loadoutAutofill",
            visible = false
    )
    public void loadout(CommandSender sender, @Arg("name") String name, @OptArg("bot-name") String botName) {
        String key = name == null ? "" : name.toLowerCase();
        ItemStack[] kit = buildLoadout(key);
        if (kit == null) {
            sender.sendMessage(ChatColor.RED + "Unknown loadout: " + ChatColor.YELLOW + key);
            sender.sendMessage("Available: " + ChatColor.YELLOW + String.join(", ", LOADOUT_NAMES));
            return;
        }

        // `clear` releases the loadout lock so baseline movement-kit refills resume;
        // every other preset is a deliberate authoritative kit.
        boolean respectAfterApply = !"clear".equals(key);

        if (botName != null && !botName.isEmpty()) {
            Location viewer = sender instanceof Player p ? p.getLocation() : null;
            Bot bot = findBot(botName, viewer);
            if (bot == null) {
                sender.sendMessage(ChatColor.RED + "Bot not found: " + ChatColor.YELLOW + botName);
                return;
            }
            applyLoadoutToBot(bot, kit, respectAfterApply);
            sender.sendMessage("Applied loadout " + ChatColor.YELLOW + key + ChatColor.RESET + " to " + ChatColor.GREEN + bot.getBotName() + ChatColor.RESET + ".");
            return;
        }

        int count = 0;
        for (Terminator t : manager.fetch()) {
            if (!(t instanceof Bot bot)) continue;
            applyLoadoutToBot(bot, kit, respectAfterApply);
            count++;
        }
        sender.sendMessage("Applied loadout " + ChatColor.YELLOW + key + ChatColor.RESET + " to " + ChatColor.BLUE + count + ChatColor.RESET + " bot(s).");
    }

    private static void applyLoadoutToBot(Bot bot, ItemStack[] kit, boolean respectAfterApply) {
        BotInventory inv = bot.getBotInventory();
        // Clear via centralized NMS-backed writes (same path used by presets/GUI).
        inv.applyMainInventorySnapshot(new ItemStack[36]);
        bot.setItem(new ItemStack(Material.AIR), EquipmentSlot.HEAD);
        bot.setItem(new ItemStack(Material.AIR), EquipmentSlot.CHEST);
        bot.setItem(new ItemStack(Material.AIR), EquipmentSlot.LEGS);
        bot.setItem(new ItemStack(Material.AIR), EquipmentSlot.FEET);
        bot.setItemOffhand(new ItemStack(Material.AIR));

        for (int i = 0; i < kit.length && i < 41; i++) {
            if (kit[i] == null) continue;
            applyLoadoutSlot(bot, i, kit[i].clone());
        }
        bot.selectHotbarSlot(0);
        // Run autoEquip so any "wind charges in offhand when mace in hand"
        // rule (see BotInventory.autoEquip) takes effect.
        inv.autoEquip();
        // Flip the loadout lock so the 40-tick ensureMovementKit refill respects
        // what the preset actually chose (including deliberate omission of pearls /
        // wind charges). The `clear` preset passes false to restore the default refill.
        if (respectAfterApply) {
            inv.markLoadoutApplied();
        } else {
            inv.markLoadoutCleared();
        }
    }

    public static boolean applyNamedLoadoutToBot(Bot bot, String name) {
        String key = name == null ? "" : name.toLowerCase();
        ItemStack[] kit = buildLoadout(key);
        if (kit == null) return false;
        applyLoadoutToBot(bot, kit, !"clear".equals(key));
        return true;
    }

    public static boolean isNamedLoadout(String name) {
        return buildLoadout(name == null ? "" : name.toLowerCase()) != null;
    }

    public List<String> loadoutAutofill(CommandSender sender, String[] args) {
        if (args.length == 2) return new ArrayList<>(Arrays.asList(LOADOUT_NAMES));
        if (args.length == 3) return manager.fetchNames();
        return new ArrayList<>();
    }

    @Command(
            name = "loadoutmix",
            desc = "Apply rotating combat loadouts. Usage: /bot loadoutmix <alltypes|core|problem> [bot-prefix]",
            autofill = "loadoutMixAutofill",
            visible = false
    )
    public void loadoutMix(CommandSender sender, @Arg("mix") String mix, @OptArg("bot-prefix") String botPrefix) {
        String key = mix == null ? "" : mix.toLowerCase();
        String[] loadouts = loadoutMixFor(key);
        if (loadouts == null) {
            sender.sendMessage(ChatColor.RED + "Unknown loadout mix: " + ChatColor.YELLOW + key);
            sender.sendMessage("Available: " + ChatColor.YELLOW + String.join(", ", LOADOUT_MIX_NAMES));
            return;
        }

        List<Bot> bots = new ArrayList<>();
        for (Terminator t : manager.fetch()) {
            if (!(t instanceof Bot bot)) continue;
            if (botPrefix != null && !botPrefix.isEmpty() && !bot.getBotName().startsWith(botPrefix)) continue;
            bots.add(bot);
        }
        bots.sort(Comparator.comparing(Bot::getBotName, BotCommand::compareNatural));
        if (bots.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No bots matched"
                    + (botPrefix == null || botPrefix.isEmpty() ? "." : " prefix " + ChatColor.YELLOW + botPrefix));
            return;
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < bots.size(); i++) {
            Bot bot = bots.get(i);
            String loadout = loadouts[i % loadouts.length];
            ItemStack[] kit = buildLoadout(loadout);
            if (kit == null) continue;
            applyLoadoutToBot(bot, kit, true);
            net.nuggetmc.tplus.bot.combat.CombatDebugger.log(bot, "loadout-assign",
                    "mix=" + key + " loadout=" + loadout + " index=" + (i + 1) + "/" + bots.size());
            net.nuggetmc.tplus.bot.combat.CombatDebugger.inventorySnapshotNow(bot, "loadout-assign:" + loadout);
            counts.merge(loadout, 1, Integer::sum);
        }

        sender.sendMessage("Applied loadout mix " + ChatColor.YELLOW + key + ChatColor.RESET
                + " to " + ChatColor.BLUE + bots.size() + ChatColor.RESET + " bot(s)"
                + (botPrefix == null || botPrefix.isEmpty() ? "." : " with prefix " + ChatColor.GREEN + botPrefix + ChatColor.RESET + "."));
        sender.sendMessage(ChatColor.GRAY + "Distribution: " + ChatColor.YELLOW + describeCounts(counts));
    }

    public List<String> loadoutMixAutofill(CommandSender sender, String[] args) {
        if (args.length == 2) return new ArrayList<>(Arrays.asList(LOADOUT_MIX_NAMES));
        if (args.length == 3) return manager.fetchNames();
        return new ArrayList<>();
    }

    private static final String[] LOADOUT_NAMES = {
            "sword", "mace", "trident", "windcharge", "skydiver",
            "crystalpvp", "anchorbomb", "pvp", "hybrid",
            // Minecraft Wiki PvP kit taxonomy (cart + UHC intentionally excluded).
            "vanilla", "axe", "smp", "pot", "spear", "projectile",
            "clear"
    };

    private static final String[] LOADOUT_MIX_NAMES = {"alltypes", "core", "problem"};

    private static final String[] LOADOUT_MIX_ALL_TYPES = {
            "sword", "mace", "trident", "windcharge", "skydiver",
            "crystalpvp", "anchorbomb", "pvp", "hybrid",
            "vanilla", "axe", "smp", "pot", "spear", "projectile"
    };

    private static final String[] LOADOUT_MIX_CORE = {
            "sword", "axe", "smp", "mace", "trident", "spear", "pot", "projectile"
    };

    private static final String[] LOADOUT_MIX_PROBLEM = {
            "mace", "mace", "mace",
            "axe", "axe", "axe",
            "smp", "smp",
            "vanilla", "hybrid"
    };

    private static String[] loadoutMixFor(String key) {
        return switch (key) {
            case "alltypes", "all", "balanced" -> LOADOUT_MIX_ALL_TYPES;
            case "core" -> LOADOUT_MIX_CORE;
            case "problem", "combatdata", "bugs" -> LOADOUT_MIX_PROBLEM;
            default -> null;
        };
    }

    private static String describeCounts(Map<String, Integer> counts) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) out.append(", ");
            first = false;
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }

    private static int compareNatural(String left, String right) {
        int li = trailingNumberStart(left);
        int ri = trailingNumberStart(right);
        String lp = left.substring(0, li);
        String rp = right.substring(0, ri);
        int prefix = lp.compareToIgnoreCase(rp);
        if (prefix != 0) return prefix;
        if (li < left.length() && ri < right.length()) {
            try {
                int ln = Integer.parseInt(left.substring(li));
                int rn = Integer.parseInt(right.substring(ri));
                int number = Integer.compare(ln, rn);
                if (number != 0) return number;
            } catch (NumberFormatException ignored) {
                // Fall through to lexical compare.
            }
        }
        return left.compareToIgnoreCase(right);
    }

    private static int trailingNumberStart(String value) {
        int i = value.length();
        while (i > 0 && Character.isDigit(value.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /**
     * Build a 41-slot loadout array for a named preset.
     * 0â€“8 hotbar, 9â€“35 storage, 36 boots, 37 legs, 38 chest, 39 head, 40 offhand.
     */
    private static ItemStack[] buildLoadout(String key) {
        ItemStack[] kit = new ItemStack[41];
        switch (key) {
            case "sword" -> {
                kit[0] = new ItemStack(Material.NETHERITE_SWORD);
                kit[36] = new ItemStack(Material.NETHERITE_BOOTS);
                kit[37] = new ItemStack(Material.NETHERITE_LEGGINGS);
                kit[38] = new ItemStack(Material.NETHERITE_CHESTPLATE);
                kit[39] = new ItemStack(Material.NETHERITE_HELMET);
                kit[40] = new ItemStack(Material.SHIELD);
            }
            case "mace" -> {
                kit[0] = new ItemStack(Material.MACE);
                kit[1] = new ItemStack(Material.IRON_SWORD);
                ItemStack windCharges = new ItemStack(Material.WIND_CHARGE);
                windCharges.setAmount(16);
                kit[40] = windCharges; // offhand â€” wiki Mace-PvP pairing
                kit[36] = new ItemStack(Material.NETHERITE_BOOTS);
                kit[37] = new ItemStack(Material.NETHERITE_LEGGINGS);
                kit[38] = new ItemStack(Material.NETHERITE_CHESTPLATE);
                kit[39] = new ItemStack(Material.NETHERITE_HELMET);
            }
            case "trident" -> {
                kit[0] = new ItemStack(Material.TRIDENT);
                kit[1] = new ItemStack(Material.IRON_SWORD);
                kit[36] = new ItemStack(Material.IRON_BOOTS);
                kit[37] = new ItemStack(Material.IRON_LEGGINGS);
                kit[38] = new ItemStack(Material.IRON_CHESTPLATE);
                kit[39] = new ItemStack(Material.IRON_HELMET);
            }
            case "windcharge" -> {
                ItemStack wc = new ItemStack(Material.WIND_CHARGE);
                wc.setAmount(16);
                kit[0] = wc;
                kit[1] = new ItemStack(Material.IRON_SWORD);
                kit[36] = new ItemStack(Material.IRON_BOOTS);
                kit[37] = new ItemStack(Material.IRON_LEGGINGS);
                kit[38] = new ItemStack(Material.IRON_CHESTPLATE);
                kit[39] = new ItemStack(Material.IRON_HELMET);
            }
            case "skydiver" -> {
                // Elytra kit that swaps to chestplate on the ground (stored in inv).
                kit[0] = new ItemStack(Material.TRIDENT);
                kit[1] = new ItemStack(Material.IRON_SWORD);
                ItemStack rockets = new ItemStack(Material.FIREWORK_ROCKET);
                rockets.setAmount(8);
                kit[2] = rockets;
                kit[9] = new ItemStack(Material.DIAMOND_CHESTPLATE);
                kit[36] = new ItemStack(Material.DIAMOND_BOOTS);
                kit[37] = new ItemStack(Material.DIAMOND_LEGGINGS);
                kit[38] = new ItemStack(Material.ELYTRA);
                kit[39] = new ItemStack(Material.DIAMOND_HELMET);
            }
            case "hybrid" -> {
                kit[0] = new ItemStack(Material.NETHERITE_SWORD);
                kit[1] = new ItemStack(Material.MACE);
                kit[2] = new ItemStack(Material.TRIDENT);
                ItemStack wc = new ItemStack(Material.WIND_CHARGE);
                wc.setAmount(16);
                kit[3] = wc;
                ItemStack apples = new ItemStack(Material.GOLDEN_APPLE);
                apples.setAmount(4);
                kit[8] = apples;
                kit[36] = new ItemStack(Material.NETHERITE_BOOTS);
                kit[37] = new ItemStack(Material.NETHERITE_LEGGINGS);
                kit[38] = new ItemStack(Material.NETHERITE_CHESTPLATE);
                kit[39] = new ItemStack(Material.NETHERITE_HELMET);
                kit[40] = new ItemStack(Material.SHIELD);
            }
            case "crystalpvp" -> {
                kit[0] = new ItemStack(Material.NETHERITE_SWORD);
                ItemStack crystals = new ItemStack(Material.END_CRYSTAL);
                crystals.setAmount(32);
                kit[1] = crystals;
                ItemStack obsidian = new ItemStack(Material.OBSIDIAN);
                obsidian.setAmount(32);
                kit[2] = obsidian;
                ItemStack pearls = new ItemStack(Material.ENDER_PEARL);
                pearls.setAmount(16);
                kit[3] = pearls;
                ItemStack gaps = new ItemStack(Material.GOLDEN_APPLE);
                gaps.setAmount(8);
                kit[4] = gaps;
                kit[7] = new ItemStack(Material.TOTEM_OF_UNDYING);
                kit[36] = new ItemStack(Material.NETHERITE_BOOTS);
                kit[37] = new ItemStack(Material.NETHERITE_LEGGINGS);
                kit[38] = new ItemStack(Material.NETHERITE_CHESTPLATE);
                kit[39] = new ItemStack(Material.NETHERITE_HELMET);
                kit[40] = new ItemStack(Material.TOTEM_OF_UNDYING);
            }
            case "anchorbomb" -> {
                // Nether-focused: respawn anchors + glowstone + pearls to reposition.
                kit[0] = new ItemStack(Material.NETHERITE_SWORD);
                ItemStack anchors = new ItemStack(Material.RESPAWN_ANCHOR);
                anchors.setAmount(16);
                kit[1] = anchors;
                ItemStack glow = new ItemStack(Material.GLOWSTONE);
                glow.setAmount(32);
                kit[2] = glow;
                ItemStack pearls = new ItemStack(Material.ENDER_PEARL);
                pearls.setAmount(16);
                kit[3] = pearls;
                kit[4] = makePotion(Material.POTION, PotionType.FIRE_RESISTANCE);
                kit[7] = new ItemStack(Material.TOTEM_OF_UNDYING);
                kit[36] = new ItemStack(Material.NETHERITE_BOOTS);
                kit[37] = new ItemStack(Material.NETHERITE_LEGGINGS);
                kit[38] = new ItemStack(Material.NETHERITE_CHESTPLATE);
                kit[39] = new ItemStack(Material.NETHERITE_HELMET);
                kit[40] = new ItemStack(Material.TOTEM_OF_UNDYING);
            }
            case "pvp" -> {
                // Everything bagel: tries every behavior.
                kit[0] = new ItemStack(Material.NETHERITE_SWORD);
                kit[1] = new ItemStack(Material.MACE);
                kit[2] = new ItemStack(Material.TRIDENT);
                ItemStack wc = new ItemStack(Material.WIND_CHARGE);
                wc.setAmount(16);
                kit[3] = wc;
                ItemStack pearls = new ItemStack(Material.ENDER_PEARL);
                pearls.setAmount(16);
                kit[4] = pearls;
                ItemStack crystals = new ItemStack(Material.END_CRYSTAL);
                crystals.setAmount(16);
                kit[5] = crystals;
                ItemStack obsidian = new ItemStack(Material.OBSIDIAN);
                obsidian.setAmount(32);
                kit[6] = obsidian;
                ItemStack webs = new ItemStack(Material.COBWEB);
                webs.setAmount(16);
                kit[7] = webs;
                ItemStack gaps = new ItemStack(Material.GOLDEN_APPLE);
                gaps.setAmount(8);
                kit[8] = gaps;
                ItemStack rockets = new ItemStack(Material.FIREWORK_ROCKET);
                rockets.setAmount(16);
                kit[9] = rockets;
                kit[10] = new ItemStack(Material.DIAMOND_CHESTPLATE);
                kit[36] = new ItemStack(Material.NETHERITE_BOOTS);
                kit[37] = new ItemStack(Material.NETHERITE_LEGGINGS);
                kit[38] = new ItemStack(Material.ELYTRA);
                kit[39] = new ItemStack(Material.NETHERITE_HELMET);
                kit[40] = new ItemStack(Material.TOTEM_OF_UNDYING);
            }
            case "vanilla" -> {
                // Vanilla PvP / VPvP â€” full arsenal minus enchanted apples + elytra.
                // Nether fallback (anchors + glowstone) lives in storage since the hotbar is full.
                kit[0] = new ItemStack(Material.NETHERITE_SWORD);
                kit[1] = new ItemStack(Material.MACE);
                ItemStack crystals = new ItemStack(Material.END_CRYSTAL);
                crystals.setAmount(16);
                kit[2] = crystals;
                ItemStack obsidian = new ItemStack(Material.OBSIDIAN);
                obsidian.setAmount(32);
                kit[3] = obsidian;
                ItemStack wc = new ItemStack(Material.WIND_CHARGE);
                wc.setAmount(16);
                kit[4] = wc;
                ItemStack pearls = new ItemStack(Material.ENDER_PEARL);
                pearls.setAmount(16);
                kit[5] = pearls;
                ItemStack gaps = new ItemStack(Material.GOLDEN_APPLE);
                gaps.setAmount(8);
                kit[6] = gaps;
                ItemStack webs = new ItemStack(Material.COBWEB);
                webs.setAmount(8);
                kit[7] = webs;
                kit[8] = new ItemStack(Material.TOTEM_OF_UNDYING);
                ItemStack anchors = new ItemStack(Material.RESPAWN_ANCHOR);
                anchors.setAmount(16);
                kit[9] = anchors;
                ItemStack glow = new ItemStack(Material.GLOWSTONE);
                glow.setAmount(16);
                kit[10] = glow;
                kit[36] = new ItemStack(Material.NETHERITE_BOOTS);
                kit[37] = new ItemStack(Material.NETHERITE_LEGGINGS);
                kit[38] = new ItemStack(Material.NETHERITE_CHESTPLATE);
                kit[39] = new ItemStack(Material.NETHERITE_HELMET);
                kit[40] = new ItemStack(Material.SHIELD);
            }
            case "axe" -> {
                // Axe PvP â€” axe disables shields; sword as secondary for follow-ups.
                kit[0] = new ItemStack(Material.NETHERITE_AXE);
                kit[1] = new ItemStack(Material.NETHERITE_SWORD);
                ItemStack gaps = new ItemStack(Material.GOLDEN_APPLE);
                gaps.setAmount(4);
                kit[2] = gaps;
                kit[36] = new ItemStack(Material.NETHERITE_BOOTS);
                kit[37] = new ItemStack(Material.NETHERITE_LEGGINGS);
                kit[38] = new ItemStack(Material.NETHERITE_CHESTPLATE);
                kit[39] = new ItemStack(Material.NETHERITE_HELMET);
                kit[40] = new ItemStack(Material.SHIELD);
            }
            case "smp" -> {
                // SMP / Netherite PvP â€” sword primary, axe fallback. No mace/crystals/anchors (explosive-banned).
                kit[0] = new ItemStack(Material.NETHERITE_SWORD);
                kit[1] = new ItemStack(Material.NETHERITE_AXE);
                ItemStack gaps = new ItemStack(Material.GOLDEN_APPLE);
                gaps.setAmount(4);
                kit[2] = gaps;
                kit[36] = new ItemStack(Material.NETHERITE_BOOTS);
                kit[37] = new ItemStack(Material.NETHERITE_LEGGINGS);
                kit[38] = new ItemStack(Material.NETHERITE_CHESTPLATE);
                kit[39] = new ItemStack(Material.NETHERITE_HELMET);
                kit[40] = new ItemStack(Material.SHIELD);
            }
            case "pot" -> {
                // Pot PvP â€” splash healing self-heals are the core mechanic. No shield per spec;
                // pearls added per user for practical bot combat (reposition / gap-close).
                // Splash potions are non-stackable, so each gets its own slot.
                kit[0] = new ItemStack(Material.NETHERITE_SWORD);
                kit[1] = makePotion(Material.SPLASH_POTION, PotionType.STRONG_HEALING);
                kit[2] = makePotion(Material.SPLASH_POTION, PotionType.STRONG_HEALING);
                kit[3] = makePotion(Material.SPLASH_POTION, PotionType.STRONG_HEALING);
                kit[4] = makePotion(Material.SPLASH_POTION, PotionType.STRONG_HEALING);
                ItemStack pearls = new ItemStack(Material.ENDER_PEARL);
                pearls.setAmount(4);
                kit[5] = pearls;
                ItemStack gaps = new ItemStack(Material.GOLDEN_APPLE);
                gaps.setAmount(4);
                kit[6] = gaps;
                kit[36] = new ItemStack(Material.NETHERITE_BOOTS);
                kit[37] = new ItemStack(Material.NETHERITE_LEGGINGS);
                kit[38] = new ItemStack(Material.NETHERITE_CHESTPLATE);
                kit[39] = new ItemStack(Material.NETHERITE_HELMET);
                // No offhand per spec.
            }
            case "spear" -> {
                // Spear PvP â€” trident only. Note: "spear" is community slang for a trident
                // used as a melee weapon in vanilla; there's no separate spear item.
                // Explicitly excludes mace / wind charges / elytra / fireworks per spec.
                kit[0] = new ItemStack(Material.TRIDENT);
                ItemStack gaps = new ItemStack(Material.GOLDEN_APPLE);
                gaps.setAmount(4);
                kit[1] = gaps;
                kit[36] = new ItemStack(Material.NETHERITE_BOOTS);
                kit[37] = new ItemStack(Material.NETHERITE_LEGGINGS);
                kit[38] = new ItemStack(Material.NETHERITE_CHESTPLATE);
                kit[39] = new ItemStack(Material.NETHERITE_HELMET);
                kit[40] = new ItemStack(Material.SHIELD);
            }
            case "projectile", "archer" -> {
                kit[0] = new ItemStack(Material.BOW);
                kit[1] = new ItemStack(Material.CROSSBOW);
                kit[2] = new ItemStack(Material.NETHERITE_SWORD);
                ItemStack arrows = new ItemStack(Material.ARROW);
                arrows.setAmount(64);
                kit[3] = arrows;
                ItemStack tipped = new ItemStack(Material.TIPPED_ARROW);
                tipped.setAmount(32);
                kit[4] = tipped;
                ItemStack pearls = new ItemStack(Material.ENDER_PEARL);
                pearls.setAmount(4);
                kit[5] = pearls;
                ItemStack projectileGaps = new ItemStack(Material.GOLDEN_APPLE);
                projectileGaps.setAmount(4);
                kit[6] = projectileGaps;
                kit[36] = new ItemStack(Material.CHAINMAIL_BOOTS);
                kit[37] = new ItemStack(Material.CHAINMAIL_LEGGINGS);
                kit[38] = new ItemStack(Material.CHAINMAIL_CHESTPLATE);
                kit[39] = new ItemStack(Material.CHAINMAIL_HELMET);
                kit[40] = new ItemStack(Material.SHIELD);
            }
            case "clear" -> {
                // Empty array â†’ everything clears.
            }
            default -> {
                return null;
            }
        }
        return kit;
    }

    /**
     * Build a potion item (regular / splash / lingering) with the given base {@link PotionType}.
     * Used by kits that need specific potion effects baked in (fire-res, strong healing, etc).
     */
    private static ItemStack makePotion(Material container, PotionType type) {
        ItemStack it = new ItemStack(container);
        if (it.getItemMeta() instanceof PotionMeta pm) {
            pm.setBasePotionType(type);
            it.setItemMeta(pm);
        }
        return it;
    }

    private static void applyLoadoutSlot(Bot bot, int slot, ItemStack item) {
        if (slot < 36) {
            bot.getBotInventory().setMainInventorySlot(slot, item);
        } else if (slot == 36) {
            bot.setItem(item, EquipmentSlot.FEET);
        } else if (slot == 37) {
            bot.setItem(item, EquipmentSlot.LEGS);
        } else if (slot == 38) {
            bot.setItem(item, EquipmentSlot.CHEST);
        } else if (slot == 39) {
            bot.setItem(item, EquipmentSlot.HEAD);
        } else if (slot == 40) {
            bot.setItem(item, EquipmentSlot.OFF_HAND);
        }
    }

    private Bot findBot(String name, Location near) {
        Terminator t = manager.getFirst(name, near);
        if (t instanceof Bot b) return b;
        return null;
    }

    static String canonicalSettingsAction(String action) {
        if (action == null) return null;
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "setgoal", "combat-goal", "target-goal" -> "combat-goal";
            case "mobtarget", "target-mobs" -> "target-mobs";
            case "playertarget", "target-player" -> "target-player";
            case "region", "target-region" -> "target-region";
            case "addplayerlist", "show-in-player-list" -> "show-in-player-list";
            case "respawn", "auto-respawn" -> "auto-respawn";
            case "setspawn", "set-spawn" -> "set-spawn";
            case "movementv2", "movement-v2" -> "movement-v2";
            case "place", "placement-material" -> "placement-material";
            default -> null;
        };
    }

    private Boolean parseToggleValue(String value) {
        if ("on".equalsIgnoreCase(value)) return true;
        if ("off".equalsIgnoreCase(value)) return false;
        return parseBoolean(value);
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(ADMIN_PERMISSION)) return true;
        sender.sendMessage(ChatColor.RED + "You do not have permission to use this subcommand.");
        sender.sendMessage(ChatColor.RED + "Required: " + ChatColor.YELLOW + ADMIN_PERMISSION);
        return false;
    }

    private void sendGroupHelp(CommandSender sender, String command, String... entries) {
        sender.sendMessage(ChatUtils.LINE);
        sender.sendMessage(ChatColor.GOLD + "Command help" + ChatColor.GRAY + " [" + ChatColor.YELLOW + command + ChatColor.GRAY + "]");
        for (String entry : entries) {
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + command + " " + entry);
        }
        sender.sendMessage(ChatUtils.LINE);
    }

    private BotEnvironmentCommand environmentCommand() {
        CommandInstance command = commandHandler.getCommand("botenvironment");
        return command instanceof BotEnvironmentCommand environment ? environment : null;
    }

    private Boolean parseBooleanValue(String value) {
        if (value == null) return null;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        return null;
    }
}
