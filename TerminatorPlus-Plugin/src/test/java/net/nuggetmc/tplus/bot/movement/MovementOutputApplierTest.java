package net.nuggetmc.tplus.bot.movement;

import net.nuggetmc.tplus.compat.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementOutputApplierTest {

    @Test
    void neuralMovementIsOptIn() {
        MemoryConfiguration config = new MemoryConfiguration();

        assertFalse(MovementOutputApplier.neuralEnabled(config));
        config.set("ai.movement.neural-enabled", true);
        assertTrue(MovementOutputApplier.neuralEnabled(config));
    }

    @Test
    void validatedRouteTraversalAlwaysSprints() {
        assertTrue(MovementOutputApplier.shouldSprint(true, MovementOutput.ZERO));
        assertFalse(MovementOutputApplier.shouldSprint(false, MovementOutput.ZERO));
        assertTrue(MovementOutputApplier.shouldSprint(false,
                new MovementOutput(1, 0, 0, 0.6, 0, 0, 1, 0)));
        assertFalse(MovementOutputApplier.shouldSprint(false,
                new MovementOutput(1, 0, 0, 1, 0.6, 0, 1, 0)));
        assertEquals(0.42, MovementOutputApplier.movementSpeed(true, true, 0.0));
        assertEquals(0.08, MovementOutputApplier.movementSpeed(false, false, 0.0));
    }
}
