package net.nuggetmc.tplus.bot;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.BotManager;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.Agent;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.NeuralNetwork;
import net.nuggetmc.tplus.api.event.BotDeathEvent;
import net.nuggetmc.tplus.api.utils.MojangAPI;
import net.nuggetmc.tplus.api.utils.SkinData;
import net.nuggetmc.tplus.compat.bukkit.*;
import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;
import net.nuggetmc.tplus.compat.bukkit.craftbukkit.entity.CraftPlayer;
import net.nuggetmc.tplus.compat.bukkit.entity.Entity;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.nuggetmc.tplus.compat.bukkit.event.EventHandler;
import net.nuggetmc.tplus.compat.bukkit.event.Listener;
import net.nuggetmc.tplus.compat.bukkit.event.entity.EntityDeathEvent;
import net.nuggetmc.tplus.compat.bukkit.event.entity.EntityTargetLivingEntityEvent;
import net.nuggetmc.tplus.compat.bukkit.event.player.PlayerJoinEvent;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.scheduler.BukkitTask;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class BotManagerImpl implements BotManager, Listener {

    private final Agent agent;
    private final Set<Terminator> bots;
    private final NumberFormat numberFormat;

    public boolean joinMessages = false;
    private boolean mobTarget = false;
    private boolean addPlayerList = false;
    private boolean respawnEnabled = false;
    private final Map<UUID, BotRespawnState> pendingRespawns = new HashMap<>();
    private final Map<UUID, BukkitTask> pendingRespawnTasks = new HashMap<>();

    public BotManagerImpl() {
        this.agent = new LegacyAgent(this, TerminatorPlus.getInstance());
        this.bots = ConcurrentHashMap.newKeySet();
        this.numberFormat = NumberFormat.getInstance(Locale.US);
    }

    @Override
    public Set<Terminator> fetch() {
        return bots;
    }

    @Override
    public void add(Terminator bot) {
        if (joinMessages) {
            Bukkit.broadcastMessage("§e" + bot.getBotName() + " joined the game");
        }

        bots.add(bot);
    }

    public List<Bot> getAllByName(String name) {
        List<Bot> result = new ArrayList<>();
        for (Terminator t : bots) {
            if (t instanceof Bot b && name.equals(b.getBotName())) result.add(b);
        }
        return result;
    }

    @Override
    public Terminator getFirst(String name, Location target) {
        if (target != null) {
            Terminator closest = null;
            for (Terminator bot : bots) {
                if (name.equals(bot.getBotName()) && (closest == null
                        || target.distanceSquared(bot.getLocation()) < target.distanceSquared(closest.getLocation()))) {
                    closest = bot;
                }
            }
            return closest;
        }
        for (Terminator bot : bots) {
            if (name.equals(bot.getBotName())) {
                return bot;
            }
        }

        return null;
    }

    @Override
    public List<String> fetchNames() {
        return bots.stream().map(terminator -> {
            if (terminator instanceof Bot bot) return bot.getName().getString();
            else return terminator.getBotName();
        }).collect(Collectors.toList());
    }

    @Override
    public Terminator createBot(Location loc, String name, String skin, String sig) {
        return Bot.createBot(loc, name, SkinData.fromLegacy(new String[]{skin, sig}).orElse(null));
    }

    @Override
    public Agent getAgent() {
        return agent;
    }

    @Override
    public void createBots(CommandSender sender, String name, String skinName, int n, Location loc) {
        createBots(sender, name, skinName, n, null, loc);
    }

    /**
     * Command-facing async skin lookup path. Spawning still happens on the
     * primary thread; only Mojang API fetch is off-thread.
     */
    public void createBotsAsync(CommandSender sender, String name, String skinName, int n, Location location) {
        long timestamp = System.currentTimeMillis();
        int amount = Math.max(1, n);
        announceCreation(sender, name, skinName, amount);
        resolveSkinAndCreate(sender, name, skinName, amount, null, location, timestamp);
    }

    private Location resolveSpawnLocation(CommandSender sender, Location location) {
        if (location != null) return location;
        if (sender instanceof Player player) return player.getLocation();

        Location spawnLocation = new Location(Bukkit.getWorlds().get(0), 0, 0, 0);
        if (sender != null) {
            sender.sendRichMessage("<red>No location specified, defaulting to " + spawnLocation.getX() + ", "
                    + spawnLocation.getY() + ", " + spawnLocation.getZ() + ".");
        }
        return spawnLocation;
    }

    @Override
    public void createBots(CommandSender sender, String name, String skinName, int n, NeuralNetwork network, Location location) {
        long timestamp = System.currentTimeMillis();
        int amount = Math.max(1, n);
        announceCreation(sender, name, skinName, amount);
        resolveSkinAndCreate(sender, name, skinName, amount, network, location, timestamp);
    }

    private void announceCreation(CommandSender sender, String name, String skinName, int amount) {
        if (sender == null) return;
        String message = "Creating " + (amount == 1 ? "new bot" : "<red>" + numberFormat.format(amount) + "<reset>" + " new bots")
                + " with name " + "<green>" + name.replace("%", "<light_purple>%" + "<reset>")
                + (skinName == null || skinName.isBlank() ? "" : "<reset>" + " and skin " + "<green>" + skinName)
                + "<reset>...";
        sender.sendRichMessage(message);
    }

    private void resolveSkinAndCreate(CommandSender sender, String name, String skinName, int amount,
                                      NeuralNetwork network, Location location, long timestamp) {
        String requestedSkin = skinName == null || skinName.isBlank() ? name : skinName;
        final Location finalSpawnLoc = resolveSpawnLocation(sender, location);

        MojangAPI.getSkinAsync(requestedSkin).whenComplete((lookup, error) ->
                Bukkit.getScheduler().runTask(TerminatorPlus.getInstance(), () -> {
                    MojangAPI.SkinLookup result = lookup == null
                            ? MojangAPI.SkinLookup.unavailable(error)
                            : lookup;
                    if (sender != null && result.failure() != null) {
                        if (result.failure() == MojangAPI.SkinLookup.Failure.NOT_FOUND) {
                            sender.sendRichMessage("<yellow>No usable Minecraft skin was found for <green>"
                                    + requestedSkin + "<yellow>. Spawning bot(s) with fallback skin.");
                        } else {
                            sender.sendRichMessage("<red>Skin lookup failed for <yellow>" + requestedSkin
                                    + "<red>. Spawning bot(s) with fallback skin.");
                        }
                    }
                    createBots(finalSpawnLoc, name, result.skin(), amount, network);
                    if (sender != null) {
                        sender.sendRichMessage("Process completed (<red>" + ((System.currentTimeMillis() - timestamp) / 1000D) + "s<reset>).");
                    }
                }));
    }

    @Override
    public Set<Terminator> createBots(Location loc, String name, String[] skin, int n, NeuralNetwork network) {
        return createBots(loc, name, SkinData.fromLegacy(skin).orElse(null), n, network);
    }

    @Override
    public Set<Terminator> createBots(Location loc, String name, String[] skin, List<NeuralNetwork> networks) {
        return createBots(loc, name, SkinData.fromLegacy(skin).orElse(null), networks);
    }

    private Set<Terminator> createBots(Location loc, String name, SkinData skin, int n, NeuralNetwork network) {
        return createBots(loc, name, skin, Collections.nCopies(Math.max(0, n), network));
    }

    private Set<Terminator> createBots(Location loc, String name, SkinData skin, List<NeuralNetwork> networks) {
        Set<Terminator> bots = new HashSet<>();
        World world = loc.getWorld();

        int n = networks.size();
        int i = 1;

        double f = n < 100 ? .004 * n : .4;

        // If the user supplied a `%` placeholder (e.g. "bot%"), substitute the
        // index into that. If not and we're spawning more than one bot, append
        // the index so they're distinguishable — was spawning 200 bots all
        // named "bot" otherwise, which made every debug log and command
        // ambiguous.
        boolean hasPlaceholder = name.contains("%");
        for (NeuralNetwork network : networks) {
            String botName = hasPlaceholder
                    ? name.replace("%", String.valueOf(i))
                    : (n > 1 ? name + i : name);
            Bot bot = Bot.createBot(loc, botName, skin);

            if (network != null) {
                bot.setNeuralNetwork(network == NeuralNetwork.RANDOM ? NeuralNetwork.generateRandomNetwork() : network);
                bot.setShield(true);
                bot.setDefaultItem(new ItemStack(Material.WOODEN_AXE));
                bot.setVelocity(randomVelocity());
            } else if (i > 1) {
                bot.setVelocity(randomVelocity().multiply(f));
            }

            bots.add(bot);
            i++;
        }

        if (world != null) {
            world.spawnParticle(Particle.CLOUD, loc, 100, 1, 1, 1, 0.5);
        }

        return bots;
    }

    private Vector randomVelocity() {
        return new Vector(Math.random() - 0.5, 0.5, Math.random() - 0.5).normalize();
    }

    @Override
    public void remove(Terminator bot) {
        if (bot == null) return;
        if (bot instanceof Bot b) {
            cancelPendingRespawn(b.getUUID());
        }
        try {
            agent.onBotRemoved(bot);
        } finally {
            bots.remove(bot);
        }
    }

    public boolean isRespawnEnabled() {
        return respawnEnabled;
    }

    public void setRespawnEnabled(boolean enabled) {
        respawnEnabled = enabled;
        if (!enabled) cancelPendingRespawns();
    }

    public int pendingRespawnCount() {
        return pendingRespawns.size();
    }

    boolean hasPendingRespawn(UUID botId) {
        return botId != null && pendingRespawns.containsKey(botId);
    }

    public void prepareRespawn(Bot bot) {
        if (!respawnEnabled || bot == null || !bot.isAutoRespawnAllowed()) return;

        UUID botId = bot.getUUID();
        if (pendingRespawns.containsKey(botId)) return;

        BotRespawnState captured = BotRespawnState.capture(bot);
        if (captured == null) return;
        pendingRespawns.put(botId, captured);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(TerminatorPlus.getInstance(), () -> {
            pendingRespawnTasks.remove(botId);
            BotRespawnState state = pendingRespawns.remove(botId);
            if (!respawnEnabled || state == null) return;

            // The normal death path also schedules cleanup at 20 ticks. Ensure
            // the old entity is gone before reusing its UUID, regardless of
            // scheduler ordering within this tick.
            try {
                bot.removeBot();
                if (state.respawn() == null) {
                    TerminatorPlus.getInstance().getLogger().warning(
                            "Could not find a safe respawn location for " + state.name());
                }
            } catch (RuntimeException error) {
                TerminatorPlus.getInstance().getLogger().severe(
                        "Could not respawn bot " + state.name() + ": " + error.getMessage());
            }
        }, 20L);
        pendingRespawnTasks.put(botId, task);
    }

    void cancelPendingRespawn(UUID botId) {
        if (botId == null) return;
        BukkitTask task = pendingRespawnTasks.remove(botId);
        if (task != null && !task.isCancelled()) task.cancel();
        pendingRespawns.remove(botId);
    }

    private void cancelPendingRespawns() {
        pendingRespawnTasks.values().stream()
                .filter(task -> task != null && !task.isCancelled())
                .forEach(BukkitTask::cancel);
        pendingRespawnTasks.clear();
        pendingRespawns.clear();
    }

    @Override
    public void reset() {
        cancelPendingRespawns();
        if (!bots.isEmpty()) {
            new HashSet<>(bots).forEach(Terminator::removeBot);
            bots.clear();
        }

        agent.stopAllTasks();
    }


    @Override
    public Terminator getBot(Player player) {
        int id = player.getEntityId();
        return getBot(id);
    }

    @Override
    public Terminator getBot(UUID uuid) {
        Entity entity = Bukkit.getEntity(uuid);
        if (entity == null) return null;
        return getBot(entity.getEntityId());
    }

    @Override
    public Terminator getBot(int entityId) {
        for (Terminator bot : bots) {
            if (bot.getEntityId() == entityId) {
                return bot;
            }
        }
        return null;
    }

    @Override
    public boolean isMobTarget() {
        return mobTarget;
    }

    @Override
    public void setMobTarget(boolean mobTarget) {
        this.mobTarget = mobTarget;
    }

    @Override
    public boolean addToPlayerList() {
        return addPlayerList;
    }

    @Override
    public void setAddToPlayerList(boolean addPlayerList) {
        this.addPlayerList = addPlayerList;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ServerGamePacketListenerImpl connection = ((CraftPlayer) event.getPlayer()).getHandle().connection;
        bots.forEach(bot -> bot.renderBot(connection, true));
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity bukkitEntity = event.getEntity();
        Terminator bot = getBot(bukkitEntity.getEntityId());
        if (bot != null) {
            agent.onBotDeath(new BotDeathEvent(event, bot));
            if (pendingRespawns.containsKey(bukkitEntity.getUniqueId())) {
                event.getDrops().clear();
                event.setDroppedExp(0);
            }
        }
    }

    @EventHandler
    public void onMobTarget(EntityTargetLivingEntityEvent event) {
        if (mobTarget || event.getTarget() == null)
            return;
        Bot bot = (Bot) getBot(event.getTarget().getUniqueId());
        if (bot != null) {
            event.setCancelled(true);
        }
    }
}
