package net.nuggetmc.tplus.utils;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.Agent;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.IntelligenceAgent;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.NeuralNetwork;
import net.nuggetmc.tplus.api.utils.DebugLogUtils;
import net.nuggetmc.tplus.api.utils.MathUtils;
import net.nuggetmc.tplus.api.utils.MojangAPI;
import net.nuggetmc.tplus.api.utils.PlayerUtils;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.command.commands.AICommand;
import net.nuggetmc.tplus.compat.bukkit.*;
import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;
import net.nuggetmc.tplus.compat.bukkit.entity.ArmorStand;
import net.nuggetmc.tplus.compat.bukkit.entity.Entity;
import net.nuggetmc.tplus.compat.bukkit.entity.EntityType;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.permissions.ServerOperator;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.stream.Collectors;

public class Debugger {

    private final CommandSender sender;
    public static final Set<String> AUTOFILL_METHODS = new HashSet<>();

    /**
     * Whitelist of invocable debug methods keyed by name. Populated at class init
     * from {@link Debugger}'s own declared methods, excluding internal plumbing
     * ({@code print}, {@code execute}, {@code buildObjects}) and synthetic
     * {@code lambda$} / {@code access$} helpers. Replaces the previous
     * {@link java.beans.Statement}-based dispatch — Statement resolves by name
     * against the whole class hierarchy, which could in principle invoke any
     * public {@code Object} method (or any method a future refactor exposes),
     * while this map is restricted to Debugger-defined commands only.
     */
    private static final Map<String, List<Method>> ALLOWED_METHODS = new HashMap<>();
    private static final Map<UUID, Integer> TRACK_Y_TASKS = new HashMap<>();
    private static final Set<String> RESERVED_NAMES = Set.of("print", "execute", "buildObjects");

    static {
        for (Method method : Debugger.class.getDeclaredMethods()) {
            String mname = method.getName();
            if (RESERVED_NAMES.contains(mname)) continue;
            if (mname.startsWith("lambda$") || mname.startsWith("access$")) continue;
            if (method.isSynthetic() || method.isBridge()) continue;

            ALLOWED_METHODS.computeIfAbsent(mname, k -> new ArrayList<>()).add(method);

            String autofill = mname + "(";
            for (Parameter par : method.getParameters()) {
                autofill += par.getType().getSimpleName() + ",";
            }
            autofill = method.getParameters().length > 0 ? autofill.substring(0, autofill.length() - 1) : autofill;
            autofill += ")";
            AUTOFILL_METHODS.add(autofill);
        }
    }

    public Debugger(CommandSender sender) {
        this.sender = sender;
    }

    public static void shutdown() {
        TRACK_Y_TASKS.values().forEach(id -> Bukkit.getScheduler().cancelTask(id));
        TRACK_Y_TASKS.clear();
    }


    private void print(Object... objects) {
        sender.sendMessage(DebugLogUtils.PREFIX + String.join(" ", DebugLogUtils.fromStringArray(objects)));
    }

    public void execute(String cmd) {
        try {
            int[] pts = {cmd.indexOf('('), cmd.indexOf(')')};
            if (pts[0] == -1 || pts[1] == -1) throw new IllegalArgumentException();

            String name = cmd.substring(0, pts[0]);
            String content = cmd.substring(pts[0] + 1, pts[1]);

            Object[] args = content.isEmpty() ? new Object[0] : buildObjects(content);

            List<Method> candidates = ALLOWED_METHODS.get(name);
            if (candidates == null || candidates.isEmpty()) {
                print("Error: \"" + ChatColor.AQUA + name + ChatColor.RESET + "\" is not a recognized debug method.");
                return;
            }

            Method chosen = resolveOverload(candidates, args);
            if (chosen == null) {
                print("Error: no overload of \"" + ChatColor.AQUA + name + ChatColor.RESET
                        + "\" matches " + args.length + " argument(s).");
                return;
            }

            print("Running the expression \"" + ChatColor.AQUA + cmd + ChatColor.RESET + "\"...");
            Object[] coerced = coerceArgs(chosen, args);
            chosen.setAccessible(true);
            chosen.invoke(this, coerced);
        } catch (Exception e) {
            print("Error: the expression \"" + ChatColor.AQUA + cmd + ChatColor.RESET + "\" failed to execute.");
            print(e.toString());
        }
    }

    /**
     * Pick the overload of {@code candidates} whose arity matches {@code args}.
     * We don't try to score by type-fit; the arity check plus the per-argument
     * coercion in {@link #coerceArgs} is what {@code Statement.execute} used to
     * do via its {@code java.beans.Expression} machinery.
     */
    private static Method resolveOverload(List<Method> candidates, Object[] args) {
        for (Method m : candidates) {
            if (m.getParameterCount() == args.length) return m;
        }
        return null;
    }

    /**
     * Coerce the raw parsed args ({@link Object}: Integer / Double / Boolean / String)
     * into the concrete parameter types of {@code target}. {@link #buildObjects}
     * parses the text as Integer first, then Double, then Boolean, then String —
     * so an {@code int} parameter slot receiving a parsed Double (e.g. "3.0")
     * must widen back to int here.
     */
    private static Object[] coerceArgs(Method target, Object[] args) {
        Class<?>[] paramTypes = target.getParameterTypes();
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object v = args[i];
            Class<?> pt = paramTypes[i];
            if (v == null) { out[i] = null; continue; }
            if (pt.isInstance(v)) { out[i] = v; continue; }
            if ((pt == int.class || pt == Integer.class) && v instanceof Number) {
                out[i] = ((Number) v).intValue();
            } else if ((pt == long.class || pt == Long.class) && v instanceof Number) {
                out[i] = ((Number) v).longValue();
            } else if ((pt == double.class || pt == Double.class) && v instanceof Number) {
                out[i] = ((Number) v).doubleValue();
            } else if ((pt == float.class || pt == Float.class) && v instanceof Number) {
                out[i] = ((Number) v).floatValue();
            } else if ((pt == boolean.class || pt == Boolean.class) && v instanceof Boolean) {
                out[i] = v;
            } else if (pt == String.class) {
                out[i] = String.valueOf(v);
            } else {
                out[i] = v; // let invoke() fail loudly if truly incompatible
            }
        }
        return out;
    }

    public Object[] buildObjects(String content) {
        List<Object> list = new ArrayList<>();

        if (!content.isEmpty()) {
            String[] values = content.split(",");

            for (String str : values) {
                String value = str.startsWith(" ") ? str.substring(1) : str;
                Object obj = value;

                try {
                    obj = Double.parseDouble(value);
                } catch (NumberFormatException ignored) {
                }

                try {
                    obj = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                }

                if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                    obj = Boolean.parseBoolean(value);
                }

                list.add(obj);
            }
        }

        return list.toArray();
    }

    /*
     * DEBUGGER METHODS
     */

    public void mobWaves(int n) {
        World world = Bukkit.getWorld("world");

        if (world == null) {
            print("world is null");
            return;
        }

        Set<Location> locs = new HashSet<>();
        locs.add(new Location(world, 128, 36, -142));
        locs.add(new Location(world, 236, 44, -179));
        locs.add(new Location(world, 310, 36, -126));
        locs.add(new Location(world, 154, 35, -101));
        locs.add(new Location(world, 202, 46, -46));
        locs.add(new Location(world, 274, 52, -44));
        locs.add(new Location(world, 297, 38, -97));
        locs.add(new Location(world, 271, 43, -173));
        locs.add(new Location(world, 216, 50, -187));
        locs.add(new Location(world, 181, 35, -150));

        if (sender instanceof Player) {
            Player player = (Player) sender;

            Bukkit.dispatchCommand(player, "team join a @a");

            Bukkit.broadcastMessage(ChatColor.YELLOW + "Starting wave " + ChatColor.RED + n + ChatColor.YELLOW + "...");

            Bukkit.broadcastMessage(ChatColor.YELLOW + "Unleashing the Super Zombies...");

            String[] skin = MojangAPI.getSkin("Lozimac");

            String name = "*";

            switch (n) {
                case 1: {
                    for (int i = 0; i < 20; i++) {
                        Bot.createBot(MathUtils.getRandomSetElement(locs), name, skin);
                    }
                    break;
                }

                case 2: {
                    for (int i = 0; i < 30; i++) {
                        Bot bot = Bot.createBot(MathUtils.getRandomSetElement(locs), name, skin);
                        bot.setDefaultItem(new ItemStack(Material.WOODEN_AXE));
                    }
                    break;
                }

                case 3: {
                    for (int i = 0; i < 30; i++) {
                        Bot bot = Bot.createBot(MathUtils.getRandomSetElement(locs), name, skin);
                        bot.setNeuralNetwork(NeuralNetwork.generateRandomNetwork());
                        bot.setShield(true);
                        bot.setDefaultItem(new ItemStack(Material.STONE_AXE));
                    }
                    break;
                }

                case 4: {
                    for (int i = 0; i < 40; i++) {
                        Bot bot = Bot.createBot(MathUtils.getRandomSetElement(locs), name, skin);
                        bot.setNeuralNetwork(NeuralNetwork.generateRandomNetwork());
                        bot.setShield(true);
                        bot.setDefaultItem(new ItemStack(Material.IRON_AXE));
                    }
                    break;
                }

                case 5: {
                    for (int i = 0; i < 50; i++) {
                        Bot bot = Bot.createBot(MathUtils.getRandomSetElement(locs), name, skin);
                        bot.setNeuralNetwork(NeuralNetwork.generateRandomNetwork());
                        bot.setShield(true);
                        bot.setDefaultItem(new ItemStack(Material.DIAMOND_AXE));
                    }
                    break;
                }
            }

            Bukkit.broadcastMessage(ChatColor.YELLOW + "The Super Zombies have been unleashed.");

            hideNametags();
        }
    }

    public void renderBots() {
        int rendered = 0;
        for (Terminator fetch : TerminatorPlus.getInstance().getManager().fetch()) {
            rendered++;
            Bot bot = (Bot) fetch;
            ServerGamePacketListenerImpl connection = bot.getBukkitEntity().getHandle().connection;
            fetch.renderBot(connection, true);
        }
        print("Rendered " + rendered + " bots.");
    }

    public void lol(String name, String skinName) {
        String[] skin = MojangAPI.getSkin(skinName);

        for (Player player : Bukkit.getOnlinePlayers()) {
            Bot.createBot(player.getLocation(), name, skin);
        }
    }

    public void colorTest() {
        Player player = (Player) sender;
        Location loc = player.getLocation();

        String[] skin = MojangAPI.getSkin("Kubepig");

        TerminatorPlus plugin = TerminatorPlus.getInstance();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (int n = 1; n <= 40; n++) {
                int wait = (int) (Math.pow(1.05, 130 - n) + 100);

                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                Bukkit.getScheduler().runTask(plugin, () -> Bot.createBot(PlayerUtils.findBottom(loc.clone().add(Math.random() * 20 - 10, 0, Math.random() * 20 - 10)), ChatColor.GREEN + "-$26.95", skin));

                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1, 1);
            }
        });
    }

    public void tpall() {
        Player player = (Player) sender;
        TerminatorPlus.getInstance().getManager().fetch().stream().filter(Terminator::isBotAlive).forEach(bot -> bot.getBukkitEntity().teleport(player));
    }

    public void viewsession() {
        IntelligenceAgent session = ((AICommand) TerminatorPlus.getInstance().getHandler().getCommand("ai")).getSession();
        Bukkit.getOnlinePlayers().stream().filter(ServerOperator::isOp).forEach(session::addUser);
    }

    public void block() {
        TerminatorPlus.getInstance().getManager().fetch().forEach(bot -> bot.block(10, 10));
    }

    public void shield() {
        TerminatorPlus.getInstance().getManager().fetch().forEach(bot -> bot.setShield(true));
    }

    public void totem() {
        TerminatorPlus.getInstance().getManager().fetch().forEach(bot -> bot.setItemOffhand(new ItemStack(Material.TOTEM_OF_UNDYING)));
    }

    public void clearMainHand() {
        TerminatorPlus.getInstance().getManager().fetch().forEach(bot -> bot.setItem(new ItemStack(Material.AIR)));
    }

    public void clearOffHand() {
        TerminatorPlus.getInstance().getManager().fetch().forEach(bot -> bot.setItemOffhand(new ItemStack(Material.AIR)));
    }

    public void offsets(boolean b) {
        Agent agent = TerminatorPlus.getInstance().getManager().getAgent();
        if (!(agent instanceof LegacyAgent)) {
            print("This method currently only supports " + ChatColor.AQUA + "LegacyAgent" + ChatColor.RESET + ".");
            return;
        }

        LegacyAgent legacyAgent = (LegacyAgent) agent;
        legacyAgent.offsets = b;

        print("Bot target offsets are now " + (legacyAgent.offsets ? ChatColor.GREEN + "ENABLED" : ChatColor.RED + "DISABLED") + ChatColor.RESET + ".");
    }

    public void confuse(int n) {
        if (!(sender instanceof Player)) return;

        Player player = (Player) sender;
        Location loc = player.getLocation();

        double f = n < 100 ? .004 * n : .4;

        for (int i = 0; i < n; i++) {
            Player target = Bukkit.getOnlinePlayers().stream().skip((int) (Bukkit.getOnlinePlayers().size() * Math.random())).findFirst().orElse(null);
            String name = target == null ? "Steve" : target.getName();
            Bot bot = Bot.createBot(loc, name);
            bot.setVelocity(new Vector(Math.random() - 0.5, 0.5, Math.random() - 0.5).normalize().multiply(f));
            bot.faceLocation(bot.getLocation().add(Math.random() - 0.5, Math.random() - 0.5, Math.random() - 0.5));
        }

        player.getWorld().spawnParticle(Particle.CLOUD, loc, 100, 1, 1, 1, 0.5);
    }

    public void dreamsmp() {
        spawnBots(Arrays.asList("Dream", "GeorgeNotFound", "Callahan", "Sapnap", "awesamdude", "Ponk", "BadBoyHalo", "TommyInnit", "Tubbo_", "ItsFundy", "Punz", "Purpled", "WilburSoot", "Jschlatt", "Skeppy", "The_Eret", "JackManifoldTV", "Nihachu", "Quackity", "KarlJacobs", "HBomb94", "Technoblade", "Antfrost", "Ph1LzA", "ConnorEatsPants", "CaptainPuffy", "Vikkstar123", "LazarCodeLazar", "Ranboo", "FoolishG", "hannahxxrose", "Slimecicle", "Michaelmcchill"));
    }

    private void spawnBots(List<String> players) {
        if (!(sender instanceof Player)) return;

        print("Processing request asynchronously...");

        Bukkit.getScheduler().runTaskAsynchronously(TerminatorPlus.getInstance(), () -> {
            try {
                print("Fetching skin data from the Mojang API for:");

                Player player = (Player) sender;
                Location loc = player.getLocation();

                Collections.shuffle(players);

                Map<String, String[]> skinCache = new HashMap<>();

                int size = players.size();
                int i = 1;

                for (String name : players) {
                    print(name, ChatColor.GRAY + "(" + ChatColor.GREEN + i + ChatColor.GRAY + "/" + size + ")");
                    String[] skin = MojangAPI.getSkin(name);
                    skinCache.put(name, skin);

                    i++;
                }

                print("Creating bots...");

                double f = .004 * players.size();

                Bukkit.getScheduler().runTask(TerminatorPlus.getInstance(), () -> {
                    skinCache.forEach((name, skin) -> {
                        Bot bot = Bot.createBot(loc, name, skin);
                        bot.setVelocity(new Vector(Math.random() - 0.5, 0.5, Math.random() - 0.5).normalize().multiply(f));
                        bot.faceLocation(bot.getLocation().add(Math.random() - 0.5, Math.random() - 0.5, Math.random() - 0.5));
                    });

                    player.getWorld().spawnParticle(Particle.CLOUD, loc, 100, 1, 1, 1, 0.5);

                    print("Done.");
                });
            } catch (Exception e) {
                print(e);
            }
        });
    }

    public void item() {
        TerminatorPlus.getInstance().getManager().fetch().forEach(b -> b.setDefaultItem(new ItemStack(Material.IRON_SWORD)));
    }

    public void j(boolean b) {
        TerminatorPlus.getInstance().getManager().joinMessages = b;
    }

    public void epic(int n) {
        if (!(sender instanceof Player)) return;

        print("Fetching names asynchronously...");

        List<String> players = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            String name = PlayerUtils.randomName();
            players.add(name);
            print(name);
        }

        spawnBots(players);
    }

    public void tp() {
        Terminator bot = MathUtils.getRandomSetElement(TerminatorPlus.getInstance().getManager().fetch().stream().filter(Terminator::isBotAlive).collect(Collectors.toSet()));

        if (bot == null) {
            print("Failed to locate a bot.");
            return;
        }

        print("Located bot", (ChatColor.GREEN + bot.getBotName() + ChatColor.RESET + "."));

        if (sender instanceof Player) {
            print("Teleporting...");
            ((Player) sender).teleport(bot.getLocation());
        }
    }

    public void setTarget(int n) {
        print("This has been established as a feature as \"" + ChatColor.AQUA + "/bot settings setgoal" + ChatColor.RESET + "\"!");
    }

    public void trackYVel() {
        if (!(sender instanceof Player)) return;

        Player player = (Player) sender;
        UUID playerId = player.getUniqueId();
        Integer existing = TRACK_Y_TASKS.remove(playerId);
        if (existing != null) {
            Bukkit.getScheduler().cancelTask(existing);
        }

        final int[] ticks = {0};
        int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(TerminatorPlus.getInstance(), () -> {
            if (!player.isOnline() || ticks[0]++ >= 20 * 60) {
                Integer id = TRACK_Y_TASKS.remove(playerId);
                if (id != null) {
                    Bukkit.getScheduler().cancelTask(id);
                }
                return;
            }
            print(player.getVelocity().getY());
        }, 0, 1);
        TRACK_Y_TASKS.put(playerId, taskId);
    }

    public void hideNametags() { // this works for some reason
        Set<Terminator> bots = TerminatorPlus.getInstance().getManager().fetch();

        for (Terminator bot : bots) {
            Location loc = bot.getLocation();
            World world = loc.getWorld();

            if (world == null) continue;

            loc.setX(loc.getBlockX());
            loc.setY(loc.getBlockY());
            loc.setZ(loc.getBlockZ());

            loc.add(0.5, 0.5, 0.5);

            ArmorStand seat = (ArmorStand) world.spawnEntity(loc, EntityType.ARMOR_STAND);
            seat.setVisible(false);
            seat.setSmall(true);

            bot.getBukkitEntity().setPassenger(seat);
        }
    }

    public void sit() {
        Set<Terminator> bots = TerminatorPlus.getInstance().getManager().fetch();

        for (Terminator bot : bots) {
            Location loc = bot.getLocation();
            World world = loc.getWorld();

            if (world == null) continue;

            loc.setX(loc.getBlockX());
            loc.setY(loc.getBlockY());
            loc.setZ(loc.getBlockZ());

            loc.add(0.5, -1.5, 0.5);

            ArmorStand seat = (ArmorStand) world.spawnEntity(loc, EntityType.ARMOR_STAND);
            seat.setVisible(false);
            seat.setGravity(false);
            seat.setSmall(true);

            seat.addPassenger(bot.getBukkitEntity());
        }
    }

    public void look() {
        if (!(sender instanceof Player)) {
            print("Unspecified player.");
            return;
        }

        Player player = (Player) sender;

        for (Terminator bot : TerminatorPlus.getInstance().getManager().fetch()) {
            bot.faceLocation(player.getEyeLocation());
        }
    }

    public void toggleAgent() {
        Agent agent = TerminatorPlus.getInstance().getManager().getAgent();

        boolean b = agent.isEnabled();
        agent.setEnabled(!b);

        print("The Bot Agent is now " + (b ? ChatColor.RED + "DISABLED" : ChatColor.GREEN + "ENABLED") + ChatColor.RESET + ".");
    }

    public void printSurroundingMobs(double dist) {
        if (!(sender instanceof Player)) {
            print("You must be a player to call this.");
            return;
        }

        Player player = (Player) sender;
        double distSq = Math.pow(dist, 2);
        for (Entity en : player.getWorld().getEntities()) {
            Location loc = en.getLocation();
            if (loc.distanceSquared(player.getLocation()) < distSq)
                print(String.format("Entity at " + ChatColor.BLUE + "(%d, %d, %d)" + ChatColor.RESET + ": Type " + ChatColor.GREEN + "%s" + ChatColor.RESET,
                        loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), en.getType().toString()));
        }
    }
}
