package net.nuggetmc.tplus.bot.navigation;

import net.nuggetmc.tplus.compat.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementV2SettingsTest {

    @Test
    void migrationEnablesMovementV2OnceAndThenPreservesAdminChoice() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set(MovementV2Settings.CONFIG_PATH, false);

        assertTrue(MovementV2Settings.migrateDefaultEnabled(config));
        assertTrue(config.getBoolean(MovementV2Settings.CONFIG_PATH));

        config.set(MovementV2Settings.CONFIG_PATH, false);
        assertFalse(MovementV2Settings.migrateDefaultEnabled(config));
        assertFalse(config.getBoolean(MovementV2Settings.CONFIG_PATH));
    }
}
