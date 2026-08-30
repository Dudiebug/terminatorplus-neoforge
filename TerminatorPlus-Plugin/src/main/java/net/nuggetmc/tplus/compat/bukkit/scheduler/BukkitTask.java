package net.nuggetmc.tplus.compat.bukkit.scheduler;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A cancellable server-tick task.  The class keeps the old Bukkit-shaped API
 * for the preserved AI code, but synchronous work is now driven exclusively
 * by {@link BukkitScheduler#tick()} on the NeoForge server thread.
 */
public final class BukkitTask {
    private final int id;
    private final Runnable action;
    private final long period;
    private volatile long nextTick;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean done;
    private volatile ScheduledFuture<?> asyncFuture;

    BukkitTask(int id, Runnable action, long nextTick, long period) {
        this.id = id;
        this.action = action;
        this.nextTick = nextTick;
        this.period = period;
    }

    /** Compatibility constructor for callers compiled against the old facade. */
    BukkitTask(int id, ScheduledFuture<?> future) {
        this.id = id;
        this.action = null;
        this.nextTick = Long.MAX_VALUE;
        this.period = 0;
        this.asyncFuture = future;
    }

    public int getTaskId() {
        return id;
    }

    public boolean isCancelled() {
        ScheduledFuture<?> future = asyncFuture;
        return cancelled.get() || done || (future != null && (future.isCancelled() || future.isDone()));
    }

    public void cancel() {
        cancelled.set(true);
        ScheduledFuture<?> future = asyncFuture;
        if (future != null) future.cancel(false);
    }

    boolean due(long tick) {
        return action != null && !isCancelled() && nextTick <= tick;
    }

    void run(long tick) {
        if (!due(tick)) return;
        try {
            action.run();
        } catch (Throwable ignored) {
            // A bad bot action must not stop the server tick loop.
        }
        if (period > 0 && !isCancelled()) {
            nextTick = tick + period;
        } else {
            done = true;
        }
    }

    boolean finished() {
        return done || isCancelled();
    }
}
