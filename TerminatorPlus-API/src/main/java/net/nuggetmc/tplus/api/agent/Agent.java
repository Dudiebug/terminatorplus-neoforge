package net.nuggetmc.tplus.api.agent;

import net.nuggetmc.tplus.api.BotManager;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.event.BotDamageByPlayerEvent;
import net.nuggetmc.tplus.api.event.BotDeathEvent;
import net.nuggetmc.tplus.api.event.BotFallDamageEvent;
import net.nuggetmc.tplus.api.event.BotKilledByPlayerEvent;
import net.nuggetmc.tplus.compat.bukkit.Bukkit;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.nuggetmc.tplus.compat.bukkit.plugin.Plugin;
import net.nuggetmc.tplus.compat.bukkit.scheduler.BukkitRunnable;
import net.nuggetmc.tplus.compat.bukkit.scheduler.BukkitScheduler;
import net.nuggetmc.tplus.compat.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public abstract class Agent {

    protected final Plugin plugin;
    protected final BotManager manager;
    protected final BukkitScheduler scheduler;
    protected final Set<BukkitRunnable> taskList;
    protected final Set<BukkitTask> scheduledTasks;
    protected final Random random;

    protected boolean enabled;
    protected int taskID;

    protected boolean drops;

    public Agent(BotManager manager, Plugin plugin) {
        this.plugin = plugin;
        this.manager = manager;
        this.scheduler = Bukkit.getScheduler();
        this.taskList = new HashSet<>();
        this.scheduledTasks = new HashSet<>();
        this.random = new Random();

        setEnabled(true);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean b) {
        if (enabled == b) {
            return;
        }

        enabled = b;

        if (b) {
            taskID = scheduler.scheduleSyncRepeatingTask(plugin, this::tick, 0, 1);
        } else {
            scheduler.cancelTask(taskID);
            stopAllTasks();
        }
    }

    public void stopAllTasks() {
        if (!taskList.isEmpty()) {
            taskList.stream().filter(t -> !t.isCancelled()).forEach(BukkitRunnable::cancel);
            taskList.clear();
        }
        if (!scheduledTasks.isEmpty()) {
            new ArrayList<>(scheduledTasks).stream()
                    .filter(task -> !task.isCancelled())
                    .forEach(BukkitTask::cancel);
            scheduledTasks.clear();
        }
    }

    protected BukkitTask scheduleTaskLater(Runnable action, long delayTicks) {
        final BukkitTask[] taskRef = new BukkitTask[1];
        BukkitTask task = scheduler.runTaskLater(plugin, () -> {
            try {
                action.run();
            } finally {
                if (taskRef[0] != null) {
                    scheduledTasks.remove(taskRef[0]);
                }
            }
        }, delayTicks);
        taskRef[0] = task;
        scheduledTasks.add(task);
        return task;
    }

    public void cleanupBot(Terminator bot) {
    }

    public void onBotRemoved(Terminator bot) {
        cleanupBot(bot);
    }

    public void setDrops(boolean enabled) {
        this.drops = enabled;
    }

    protected abstract void tick();

    public void onFallDamage(BotFallDamageEvent event) {
    }

    public void onPlayerDamage(BotDamageByPlayerEvent event) {
    }

    public void onBotDeath(BotDeathEvent event) {
    }

    public void onBotKilledByPlayer(BotKilledByPlayerEvent event) {
        Player player = event.getPlayer();

        // Runs on the main thread: Bukkit fires BotKilledByPlayerEvent from the bot's
        // tick path. The previous implementation hopped to async specifically to do the
        // kill-credit bookkeeping off-thread, but both touches here — manager.getBot()
        // and Terminator.incrementKills() — mutate state the main thread reads every
        // tick, so the async hop was a data race without a backing synchronization
        // primitive. Staying on main is correct, trivially thread-safe, and an order
        // of a microsecond — not worth an async boundary.
        Terminator bot = manager.getBot(player);
        if (bot != null) {
            bot.incrementKills();
        }
    }
}
