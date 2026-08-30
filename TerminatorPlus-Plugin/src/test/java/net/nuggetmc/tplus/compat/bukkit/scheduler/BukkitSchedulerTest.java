package net.nuggetmc.tplus.compat.bukkit.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for the server-thread scheduler used by the NeoForge port. */
class BukkitSchedulerTest {
    private final BukkitScheduler scheduler = BukkitScheduler.instance();

    @BeforeEach
    void setUp() {
        scheduler.restart();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
        scheduler.restart();
    }

    @Test
    void delayedTasksRunOnTheirTargetTick() {
        AtomicInteger calls = new AtomicInteger();
        scheduler.runTaskLater(null, calls::incrementAndGet, 2);

        scheduler.tick();
        assertEquals(0, calls.get());
        scheduler.tick();
        assertEquals(1, calls.get());
        assertTrue(scheduler.isTickThread());
    }

    @Test
    void cancellationPreventsRepeatingTask() {
        AtomicInteger calls = new AtomicInteger();
        BukkitTask task = scheduler.runTaskTimer(null, calls::incrementAndGet, 1, 1);

        scheduler.tick();
        assertEquals(1, calls.get());
        task.cancel();
        scheduler.tick();
        scheduler.tick();
        assertEquals(1, calls.get());
        assertTrue(task.isCancelled());
    }
}
