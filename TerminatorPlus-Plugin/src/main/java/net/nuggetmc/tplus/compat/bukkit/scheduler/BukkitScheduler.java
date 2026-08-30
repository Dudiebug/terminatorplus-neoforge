package net.nuggetmc.tplus.compat.bukkit.scheduler;

import net.nuggetmc.tplus.compat.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-thread scheduler used by the ported Paper code.
 *
 * <p>Unlike the old compatibility shim, synchronous tasks never run from a
 * wall-clock executor.  NeoForge calls {@link #tick()} from
 * {@code ServerTickEvent.Post}; this gives delayed actions deterministic tick
 * semantics and makes cancellation safe during shutdown.  Asynchronous tasks
 * retain a small daemon pool for network/profile lookups.</p>
 */
public final class BukkitScheduler {
    private static final BukkitScheduler INSTANCE = new BukkitScheduler();

    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, BukkitTask> tasks = new ConcurrentHashMap<>();
    private final ExecutorService async = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "terminatorplus-async");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long currentTick;
    private volatile Thread tickThread;
    private volatile boolean shuttingDown;

    private BukkitScheduler() {
    }

    public static BukkitScheduler instance() {
        return INSTANCE;
    }

    public long currentTick() {
        return currentTick;
    }

    public boolean isTickThread() {
        return Thread.currentThread() == tickThread;
    }

    /** Called once from NeoForge's server tick event. */
    public void tick() {
        if (shuttingDown) return;
        tickThread = Thread.currentThread();
        long tick = ++currentTick;
        // Snapshot so tasks can safely schedule/cancel other tasks while they
        // execute. Newly scheduled zero-delay work runs on the following tick.
        for (BukkitTask task : new ArrayList<>(tasks.values())) {
            if (task.due(tick)) task.run(tick);
            if (task.finished()) tasks.remove(task.getTaskId(), task);
        }
    }

    public BukkitTask runTask(Plugin plugin, Runnable action) {
        return runTaskLater(plugin, action, 0);
    }

    public BukkitTask runTaskLater(Plugin plugin, Runnable action, long ticks) {
        return schedule(action, Math.max(1L, ticks <= 0 ? 1L : ticks), 0L);
    }

    public BukkitTask runTaskTimer(Plugin plugin, Runnable action, long delayTicks, long periodTicks) {
        return schedule(action, Math.max(1L, delayTicks <= 0 ? 1L : delayTicks), Math.max(1L, periodTicks));
    }

    public BukkitTask runTaskAsynchronously(Plugin plugin, Runnable action) {
        int id = ids.incrementAndGet();
        BukkitTask task = new BukkitTask(id, async.submit(() -> {
            if (shuttingDown) return;
            try {
                action.run();
            } catch (Throwable ignored) {
            }
        }));
        tasks.put(id, task);
        return task;
    }

    public int scheduleSyncRepeatingTask(Plugin plugin, Runnable action, long delayTicks, long periodTicks) {
        return runTaskTimer(plugin, action, delayTicks, periodTicks).getTaskId();
    }

    public void cancelTask(int id) {
        BukkitTask task = tasks.remove(id);
        if (task != null) task.cancel();
    }

    /**
     * Schedule a callable on the server tick thread.  The returned future is
     * completed on the tick that executes the callable, matching Bukkit's
     * callSyncMethod contract without blocking the tick loop.
     */
    public <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> callable) {
        CompletableFuture<T> result = new CompletableFuture<>();
        runTask(plugin, () -> {
            try {
                result.complete(callable.call());
            } catch (Exception error) {
                result.completeExceptionally(new CompletionException(error));
            }
        });
        return result;
    }

    private BukkitTask schedule(Runnable action, long delayTicks, long periodTicks) {
        if (shuttingDown) {
            BukkitTask cancelled = new BukkitTask(ids.incrementAndGet(), action, currentTick + 1, periodTicks);
            cancelled.cancel();
            return cancelled;
        }
        int id = ids.incrementAndGet();
        BukkitTask task = new BukkitTask(id, action, currentTick + delayTicks, periodTicks);
        tasks.put(id, task);
        return task;
    }

    public void shutdown() {
        shuttingDown = true;
        tasks.values().forEach(BukkitTask::cancel);
        tasks.clear();
        async.shutdownNow();
        tickThread = null;
    }

    /** Re-enable the singleton when a dedicated server starts again in tests. */
    public void restart() {
        shuttingDown = false;
        currentTick = 0;
    }
}
